package dev.quantti.fplassistant.sync;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable queue and payload staging for the future owned FPL sync.
 *
 * <p>There is no HTTP, scheduling or core-table writer here. Worker mutations lock the run and
 * validate owner, random token, monotonically increasing fence and unexpired lease. Publication
 * additionally locks a per-season, per-publication-key receipt before invoking the callback, so
 * an older run cannot publish after a newer generation even when both leases are otherwise valid.
 * Staged evidence is retained across retries, bounded to {@value #MAX_RETAINED_PAYLOAD_BYTES_PER_RUN}
 * bytes per run; hitting that limit rejects the new body without deleting required active evidence.
 */
public final class OwnedSyncStore {

    public static final int MAX_PAYLOAD_BYTES = OwnedSyncSchema.MAX_PAYLOAD_BYTES;
    public static final int MAX_RETAINED_PAYLOAD_BYTES_PER_RUN =
            OwnedSyncSchema.MAX_RETAINED_PAYLOAD_BYTES_PER_RUN;
    public static final int MAX_ERROR_CHARS = 1_000;
    static final String INCREMENTAL_CORE_PRODUCER = "INCREMENTAL_CORE";
    static final String INCREMENTAL_CORE_KEY = "incremental-core-import";
    static final String INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY = "incremental-compatibility-catalogue";
    static final String FINISHED_FIXTURE_RECEIPT_PREFIX = "incremental-finished-fixture:";
    static final String INCREMENTAL_LIVE_EVENT_RECEIPT_PREFIX = "incremental-live-event:";
    static final String INCREMENTAL_PARTICIPANT_RECEIPT_PREFIX = "incremental-participant:fixture:";
    static final String INCREMENTAL_DISCOVERY_RECEIPT_PREFIX = "incremental-discovery:fixture:";
    static final String INCREMENTAL_POPULATION_RECEIPT_PREFIX = "incremental-population:fixture:";
    private static final Duration MAX_LEASE = Duration.ofHours(1);
    private static final int PUBLIC_PAIR_CACHE_ENTRIES = 2;
    private static final int INCREMENTAL_DISCOVERY_BATCH_SIZE = 500;
    static final int HISTORY_HYDRATION_FETCH_SIZE = 8;
    private static final Set<Integer> PREFERENTIAL_HISTORY_TURNS = Set.of(0, 4);
    private static final List<ClaimClass> FAIR_CYCLE = List.of(
            ClaimClass.HISTORY, ClaimClass.READY_FIXTURE,
            ClaimClass.HISTORY, ClaimClass.BLOCKED_FIXTURE,
            ClaimClass.HISTORY, ClaimClass.READY_FIXTURE,
            ClaimClass.HISTORY, ClaimClass.UNTOUCHED_FIXTURE);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final OwnedSyncTables tables;
    private final RuntimeFence runtimeFence;
    private final Map<PublicPayloadPairCacheKey, PublicPayloadPair> publicPairCache =
            new LinkedHashMap<>(PUBLIC_PAIR_CACHE_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<PublicPayloadPairCacheKey,
                        PublicPayloadPair> eldest) {
                    return size() > PUBLIC_PAIR_CACHE_ENTRIES;
                }
            };

    /** Unfenced constructor retained for isolated store tooling; the owned runtime always supplies a fence. */
    public OwnedSyncStore(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock, OwnedSyncTables tables) {
        this(jdbc, tx, clock, tables, null);
    }

    public OwnedSyncStore(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock,
                          OwnedSyncTables tables, RuntimeFence runtimeFence) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.tx = Objects.requireNonNull(tx);
        this.clock = Objects.requireNonNull(clock);
        this.tables = Objects.requireNonNull(tables);
        this.runtimeFence = runtimeFence;
    }

    GameweekFinalityRepository gameweekFinalityRepository() {
        return new GameweekFinalityRepository(jdbc, this, tables, clock);
    }

    public Run enqueue(SyncKind kind, String season, String coalescingKey,
                       String publicationKey, Instant dueAt) {
        return enqueue(kind, season, coalescingKey, publicationKey, dueAt, true);
    }

    /** Ensures a fixture dependency has history at least as new as its canonical fixture read. */
    public Run ensurePlayerHistoryAtLeast(String season, int playerId, Instant historyFloor) {
        String validSeason = bounded("season", season, 10);
        String key = HistoryFixtureDispatcher.playerHistoryPublicationKey(playerId);
        Objects.requireNonNull(historyFloor);
        return tx.execute(status -> {
            assertRuntimeFence(validSeason);
            Instant enqueuedAt = now();
            jdbc.update("INSERT INTO %s(season, publication_key) VALUES (?, ?) ON CONFLICT DO NOTHING"
                    .formatted(tables.receipt()), validSeason, key);
            try {
                return jdbc.queryForObject("""
                    INSERT INTO %s(kind, season, coalescing_key, publication_key, priority,
                                   due_at, requested_source_after, runtime_mode, runtime_epoch,
                                   created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (kind, season, coalescing_key)
                        WHERE state IN ('PENDING', 'LEASED')
                    DO UPDATE SET due_at = CASE
                                      WHEN EXCLUDED.requested_source_after > %s.requested_source_after
                                          THEN LEAST(%s.due_at, EXCLUDED.due_at)
                                      ELSE %s.due_at END,
                                  requested_source_after = GREATEST(
                                      %s.requested_source_after, EXCLUDED.requested_source_after),
                                  updated_at = EXCLUDED.updated_at
                    WHERE %s.publication_key = EXCLUDED.publication_key
                    RETURNING generation, kind, season, coalescing_key, publication_key,
                              due_at, state, attempt_count
                    """.formatted(tables.run(), tables.run(), tables.run(), tables.run(),
                        tables.run(), tables.run()), this::run,
                        SyncKind.PLAYER_HISTORY.name(), validSeason, key, key,
                        SyncKind.PLAYER_HISTORY.priority, timestamp(historyFloor), timestamp(historyFloor),
                        runtimeMode(), runtimeEpoch(), timestamp(enqueuedAt), timestamp(enqueuedAt));
            } catch (EmptyResultDataAccessException e) {
                throw new IllegalArgumentException(
                        "Active coalescing key is already bound to a different publicationKey");
            }
        });
    }

    /** Keeps checkpoint source floors intact when merely reflecting an already durable retry. */
    public Run enqueuePreservingActiveDemand(SyncKind kind, String season, String coalescingKey,
                                             String publicationKey, Instant dueAt) {
        return enqueue(kind, season, coalescingKey, publicationKey, dueAt, false);
    }

    /** Creates or returns the one producer-isolated core-import run for this season. */
    Run enqueueIncrementalCoreImport(CatalogueScheduleParser.Season season, Instant dueAt) {
        Objects.requireNonNull(season);
        Objects.requireNonNull(dueAt);
        return tx.execute(status -> {
            requireIncrementalRuntime(season);
            List<Run> active = jdbc.query("""
                    SELECT generation, kind, season, coalescing_key, publication_key,
                           due_at, state, attempt_count
                    FROM %s WHERE kind='PLAYER_HISTORY' AND producer_key=? AND season=?
                      AND coalescing_key=? AND publication_key=?
                      AND state IN ('PENDING','LEASED') FOR UPDATE
                    """.formatted(tables.run()), this::run, INCREMENTAL_CORE_PRODUCER,
                    season.code(), INCREMENTAL_CORE_KEY, INCREMENTAL_CORE_KEY);
            if (!active.isEmpty()) return active.getFirst();

            Instant createdAt = now();
            Instant sourceFloor = dueAt.isAfter(createdAt) ? dueAt : createdAt;
            jdbc.update("INSERT INTO %s(season, publication_key) VALUES (?, ?) ON CONFLICT DO NOTHING"
                    .formatted(tables.receipt()), season.code(), INCREMENTAL_CORE_KEY);
            return jdbc.queryForObject("""
                    INSERT INTO %s(kind, producer_key, season, coalescing_key, publication_key, priority,
                                   due_at, requested_source_after, runtime_mode, runtime_epoch,
                                   created_at, updated_at)
                    VALUES ('PLAYER_HISTORY', ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                    RETURNING generation, kind, season, coalescing_key, publication_key,
                              due_at, state, attempt_count
                    """.formatted(tables.run()), this::run, INCREMENTAL_CORE_PRODUCER, season.code(),
                    INCREMENTAL_CORE_KEY, INCREMENTAL_CORE_KEY, SyncKind.PLAYER_HISTORY.priority,
                    timestamp(dueAt), timestamp(sourceFloor), runtimeEpoch(), timestamp(createdAt),
                    timestamp(createdAt));
        });
    }

    /** Claims only this collector's active-season run; legacy dispatchers cannot see it. */
    Optional<Lease> acquireIncrementalCoreImport(String season, String owner, Duration duration) {
        String validSeason = bounded("season", season, 10);
        String validOwner = bounded("owner", owner, 128);
        Duration validDuration = leaseDuration(duration);
        return tx.execute(status -> {
            requireIncrementalRuntime(validSeason);
            Instant checkedAt = now();
            List<Long> generations = jdbc.queryForList("""
                    SELECT generation FROM %s
                    WHERE kind='PLAYER_HISTORY' AND producer_key=? AND season=?
                      AND coalescing_key=? AND publication_key=?
                      AND runtime_mode='ACTIVE' AND runtime_epoch=? AND due_at<=?
                      AND (state='PENDING' OR (state='LEASED' AND lease_expires_at<=?))
                    ORDER BY due_at,generation LIMIT 1 FOR UPDATE SKIP LOCKED
                    """.formatted(tables.run()), Long.class, INCREMENTAL_CORE_PRODUCER, validSeason,
                    INCREMENTAL_CORE_KEY, INCREMENTAL_CORE_KEY, runtimeEpoch(), timestamp(checkedAt),
                    timestamp(checkedAt));
            if (generations.isEmpty()) return Optional.empty();
            return Optional.of(leaseGeneration(generations.getFirst(), validOwner, validDuration, checkedAt));
        });
    }

    private void requireIncrementalRuntime(CatalogueScheduleParser.Season season) {
        Objects.requireNonNull(season);
        if (runtimeFence == null || runtimeFence.mode() != OwnedSyncMode.ACTIVE
                || !runtimeFence.season().equals(season.code())) {
            throw new RuntimeFenceLostException("Incremental core import requires its configured ACTIVE season");
        }
        requireIncrementalRuntime(season.code());
        List<SeasonIdentity> current = jdbc.query("""
                SELECT id, code FROM season WHERE is_current=true ORDER BY id FOR UPDATE
                """, (rs, row) -> new SeasonIdentity(rs.getInt("id"), rs.getString("code")));
        if (current.size() != 1 || current.getFirst().id() != season.id()
                || !current.getFirst().code().equals(season.code())) {
            throw new RuntimeFenceLostException("Incremental core import season is not the database playing season");
        }
    }

    private void requireIncrementalCompatibilitySeason(CatalogueScheduleParser.Season season) {
        requireIncrementalRuntime(season);
        List<SeasonCalendar> current = jdbc.query("""
                SELECT start_date, end_date FROM season WHERE is_current=true ORDER BY id FOR UPDATE
                """, (rs, row) -> new SeasonCalendar(rs.getDate("start_date") == null ? null
                : rs.getDate("start_date").toLocalDate(), rs.getDate("end_date") == null ? null
                : rs.getDate("end_date").toLocalDate()));
        if (current.size() != 1 || !Objects.equals(current.getFirst().startsOn(), season.startsOn())
                || !Objects.equals(current.getFirst().endsOn(), season.endsOn())) {
            throw new RuntimeFenceLostException("Incremental compatibility season calendar is not current");
        }
    }

    private void requireIncrementalRuntime(String season) {
        if (runtimeFence == null || runtimeFence.mode() != OwnedSyncMode.ACTIVE
                || !runtimeFence.season().equals(season)) {
            throw new RuntimeFenceLostException("Incremental core import requires its configured ACTIVE season");
        }
        assertRuntimeFence(season);
        List<String> current = jdbc.query("SELECT code FROM season WHERE is_current=true",
                (rs, row) -> rs.getString(1));
        if (current.size() != 1 || !season.equals(current.getFirst())) {
            throw new RuntimeFenceLostException("Incremental core import season is not the database playing season");
        }
    }

    private Run enqueue(SyncKind kind, String season, String coalescingKey,
                        String publicationKey, Instant dueAt, boolean advanceActiveDemand) {
        Objects.requireNonNull(kind);
        String validSeason = bounded("season", season, 10);
        String validCoalescingKey = bounded("coalescingKey", coalescingKey, 200);
        String validPublicationKey = bounded("publicationKey", publicationKey, 200);
        Objects.requireNonNull(dueAt);
        return tx.execute(status -> {
            assertRuntimeFence(validSeason);
            Instant enqueuedAt = now();
            Instant requestedSourceAfter = dueAt.isAfter(enqueuedAt) ? dueAt : enqueuedAt;
            jdbc.update("INSERT INTO %s(season, publication_key) VALUES (?, ?) ON CONFLICT DO NOTHING"
                    .formatted(tables.receipt()), validSeason, validPublicationKey);
            Run result;
            try {
                result = jdbc.queryForObject("""
                    INSERT INTO %s(kind, season, coalescing_key, publication_key, priority,
                                   due_at, requested_source_after, runtime_mode, runtime_epoch,
                                   created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (kind, season, coalescing_key)
                        WHERE state IN ('PENDING', 'LEASED')
                    DO UPDATE SET due_at = LEAST(%s.due_at, EXCLUDED.due_at),
                                  requested_source_after = CASE WHEN ? THEN GREATEST(
                                      %s.requested_source_after, EXCLUDED.requested_source_after)
                                      ELSE %s.requested_source_after END,
                                  updated_at = EXCLUDED.updated_at
                    WHERE %s.publication_key = EXCLUDED.publication_key
                    RETURNING generation, kind, season, coalescing_key, publication_key,
                              due_at, state, attempt_count
                    """.formatted(tables.run(), tables.run(), tables.run(), tables.run(), tables.run()), this::run,
                        kind.name(), validSeason, validCoalescingKey, validPublicationKey, kind.priority,
                        timestamp(dueAt), timestamp(requestedSourceAfter), runtimeMode(), runtimeEpoch(),
                        timestamp(enqueuedAt), timestamp(enqueuedAt), advanceActiveDemand);
            } catch (EmptyResultDataAccessException e) {
                throw new IllegalArgumentException(
                        "Active coalescing key is already bound to a different publicationKey");
            }
            return result;
        });
    }

    /** True only when reads can be tied to a known activation epoch for this season. */
    boolean hasCurrentRuntimeEpoch(String season) {
        return runtimeFence != null && runtimeFence.season().equals(season);
    }

    /** Whether a source pair has an exact completed publication in the current ACTIVE epoch. */
    boolean isCurrentPublishedCanonicalPair(String season, long sourceGeneration, Instant now) {
        return currentPublishedCanonicalPair(season, sourceGeneration, null, now).isPresent();
    }

    boolean isCurrentPublishedCanonicalPair(String season, long sourceGeneration,
                                             CanonicalSourcePair expectedSource, Instant now) {
        return currentPublishedCanonicalPair(season, sourceGeneration, expectedSource, now).isPresent();
    }

    Optional<CanonicalPublication> currentPublishedCanonicalPair(String season,
            CanonicalSourcePair expectedSource, Instant now) {
        Objects.requireNonNull(expectedSource);
        return currentPublishedCanonicalPair(season, expectedSource.bootstrap().runGeneration(),
                expectedSource, now);
    }

    private Optional<CanonicalPublication> currentPublishedCanonicalPair(String season, long sourceGeneration,
            CanonicalSourcePair expectedSource, Instant now) {
        if (runtimeFence == null || runtimeFence.mode() != OwnedSyncMode.ACTIVE
                || !runtimeFence.season().equals(season)) return Optional.empty();
        String expected = expectedSource == null ? "" : "AND p.source_pair_fingerprint=? "
                + "AND p.source_bootstrap_payload_id=? AND p.source_fixtures_payload_id=? "
                + "AND p.source_bootstrap_read_started_at=? AND p.source_bootstrap_read_finished_at=? "
                + "AND p.source_fixtures_read_started_at=? AND p.source_fixtures_read_finished_at=? "
                + "AND p.source_bootstrap_sha256=? AND p.source_fixtures_sha256=? ";
        String sql = """
                SELECT r.generation AS publication_generation, b.run_generation AS source_generation
                FROM %s r
                JOIN %s p ON p.season=r.season AND p.publication_key=r.publication_key
                    AND p.latest_generation=r.generation
                JOIN %s b ON b.id=p.source_bootstrap_payload_id
                    AND b.endpoint_kind='BOOTSTRAP_STATIC' AND b.event_id IS NULL AND b.player_id IS NULL
                    AND b.validation_state='VALID' AND b.source_published IS TRUE
                    AND b.read_started_at=p.source_bootstrap_read_started_at
                    AND b.read_finished_at=p.source_bootstrap_read_finished_at
                JOIN %s f ON f.id=p.source_fixtures_payload_id
                    AND f.endpoint_kind='FIXTURES' AND f.event_id IS NULL AND f.player_id IS NULL
                    AND f.validation_state='VALID' AND f.source_published IS TRUE
                    AND f.read_started_at=p.source_fixtures_read_started_at
                    AND f.read_finished_at=p.source_fixtures_read_finished_at
                JOIN %s br ON br.generation=b.run_generation AND br.season=r.season
                    AND br.kind='CATALOGUE_SCHEDULE' AND br.producer_key='LEGACY'
                    AND br.runtime_mode='ACTIVE' AND br.runtime_epoch=r.runtime_epoch
                    AND ((br.coalescing_key=? AND br.publication_key=?)
                         OR (br.coalescing_key=? AND br.publication_key=?))
                JOIN %s fr ON fr.generation=f.run_generation AND fr.season=r.season
                    AND fr.kind='CATALOGUE_SCHEDULE' AND fr.producer_key='LEGACY'
                    AND fr.runtime_mode='ACTIVE' AND fr.runtime_epoch=r.runtime_epoch
                    AND ((fr.coalescing_key=? AND fr.publication_key=?)
                         OR (fr.coalescing_key=? AND fr.publication_key=?))
                WHERE r.season=? AND r.kind='CATALOGUE_SCHEDULE' AND r.producer_key='LEGACY'
                  AND ((r.coalescing_key=? AND r.publication_key=?)
                       OR (r.coalescing_key=? AND r.publication_key=?))
                  AND r.runtime_mode='ACTIVE' AND r.runtime_epoch=? AND r.state='COMPLETED'
                  AND r.completed_at IS NOT NULL AND r.completed_at<=p.published_at
                  AND p.published_at<=?
                  AND r.source_read_started_at IS NOT NULL AND r.source_read_finished_at IS NOT NULL
                  AND r.source_read_started_at<=r.source_read_finished_at
                  AND r.source_read_finished_at<=p.published_at
                  AND %s
                  AND p.source_read_started_at=r.source_read_started_at
                  AND p.source_read_finished_at=r.source_read_finished_at
                  AND p.source_pair_fingerprint ~ '^[0-9a-f]{64}$'
                  AND p.source_bootstrap_sha256 ~ '^[0-9a-f]{64}$'
                  AND p.source_fixtures_sha256 ~ '^[0-9a-f]{64}$'
                  AND b.run_generation=f.run_generation AND b.run_generation=?
                  AND b.read_started_at<=b.read_finished_at AND f.read_started_at<=f.read_finished_at
                  AND b.read_finished_at<=p.published_at AND f.read_finished_at<=p.published_at
                  AND r.source_read_started_at<=b.read_started_at
                  AND r.source_read_started_at<=f.read_started_at
                  AND r.source_read_finished_at>=b.read_finished_at
                  AND r.source_read_finished_at>=f.read_finished_at
                  %s
                ORDER BY %s
                LIMIT 1
                """.formatted(tables.run(), tables.receipt(), tables.payload(), tables.payload(),
                tables.run(), tables.run(), canonicalDemandFloorPredicate("r", "br", "fr", "b", "f"), expected,
                canonicalParentWinnerOrder("b.read_finished_at", "f.read_finished_at", "p.published_at",
                        "r.generation"));
        List<Object> parameters = new ArrayList<>(List.of(
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY,
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY,
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY,
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY,
                season, CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY,
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY,
                runtimeFence.epoch(), timestamp(now), sourceGeneration));
        if (expectedSource != null) {
            parameters.add(expectedSource.fingerprint());
            parameters.add(expectedSource.bootstrap().id());
            parameters.add(expectedSource.fixtures().id());
            parameters.add(timestamp(expectedSource.bootstrap().read().startedAt()));
            parameters.add(timestamp(expectedSource.bootstrap().read().finishedAt()));
            parameters.add(timestamp(expectedSource.fixtures().read().startedAt()));
            parameters.add(timestamp(expectedSource.fixtures().read().finishedAt()));
            parameters.add(expectedSource.bootstrap().sha256());
            parameters.add(expectedSource.fixtures().sha256());
        }
        return jdbc.query(sql, (rs, row) -> new CanonicalPublication(
                rs.getLong("source_generation"), rs.getLong("publication_generation"),
                expectedSource == null ? null : expectedSource.fingerprint()), parameters.toArray())
                .stream().findFirst();
    }

    private String canonicalParentWinnerOrder(String bootstrapFinishedAt, String fixturesFinishedAt,
                                              String publishedAt, String publicationGeneration) {
        return "GREATEST(" + bootstrapFinishedAt + ", " + fixturesFinishedAt + ") DESC, "
                + publishedAt + " DESC, " + publicationGeneration + " DESC";
    }

    /**
     * Requires every current-epoch demand for each source/publisher lane to be covered by both reads.
     * A completed receipt can remain latest while a later coalesced generation is pending, so the
     * maximum lane demand is the source floor; checking only the attached completed run would reuse stale data.
     */
    private String canonicalDemandFloorPredicate(String publicationRun, String bootstrapRun, String fixturesRun,
                                                 String bootstrapPayload, String fixturesPayload) {
        return String.join(" AND ", List.of(
                canonicalDemandFloor(publicationRun, bootstrapPayload),
                canonicalDemandFloor(publicationRun, fixturesPayload),
                canonicalDemandFloor(bootstrapRun, bootstrapPayload),
                canonicalDemandFloor(bootstrapRun, fixturesPayload),
                canonicalDemandFloor(fixturesRun, bootstrapPayload),
                canonicalDemandFloor(fixturesRun, fixturesPayload)));
    }

    private String canonicalDemandFloor(String runAlias, String payloadAlias) {
        return """
                (SELECT MAX(demand.requested_source_after) FROM %s demand
                 WHERE demand.kind='CATALOGUE_SCHEDULE' AND demand.producer_key='LEGACY'
                   AND demand.season=%s.season
                   AND demand.runtime_mode=%s.runtime_mode AND demand.runtime_epoch=%s.runtime_epoch
                   AND demand.coalescing_key=%s.coalescing_key
                   AND demand.publication_key=%s.publication_key) <= %s.read_started_at
                """.formatted(tables.run(), runAlias, runAlias, runAlias, runAlias, runAlias, payloadAlias);
    }

    /** Returns metadata-only evidence for the newest current-epoch canonical parent pair. */
    CanonicalParentEvidence currentCanonicalParentEvidence(String season, int fixtureId, Instant now) {
        if (runtimeFence == null || runtimeFence.mode() != OwnedSyncMode.ACTIVE
                || !runtimeFence.season().equals(season)) return CanonicalParentEvidence.MISSING;
        return jdbc.query("""
                WITH candidate AS (
                    SELECT r.generation, b.run_generation AS source_generation, r.season,
                           r.coalescing_key, r.publication_key, r.runtime_mode, r.runtime_epoch,
                           r.state, r.completed_at, r.source_read_started_at, r.source_read_finished_at,
                           p.latest_generation, p.published_at, p.source_read_started_at AS receipt_started,
                           p.source_read_finished_at AS receipt_finished,
                           p.source_bootstrap_payload_id, p.source_fixtures_payload_id,
                           p.source_bootstrap_read_started_at, p.source_bootstrap_read_finished_at,
                           p.source_fixtures_read_started_at, p.source_fixtures_read_finished_at,
                           p.source_bootstrap_sha256, p.source_fixtures_sha256,
                           p.source_pair_fingerprint,
                           (b.id IS NOT NULL AND f.id IS NOT NULL AND br.generation IS NOT NULL
                               AND fr.generation IS NOT NULL AND b.run_generation=f.run_generation)
                               AS source_valid
                    FROM %s r
                    JOIN %s p ON p.season=r.season AND p.publication_key=r.publication_key
                        AND p.latest_generation=r.generation
                    LEFT JOIN %s b ON b.id=p.source_bootstrap_payload_id
                        AND b.endpoint_kind='BOOTSTRAP_STATIC' AND b.event_id IS NULL AND b.player_id IS NULL
                        AND b.validation_state='VALID' AND b.source_published IS TRUE
                        AND b.read_started_at=p.source_bootstrap_read_started_at
                        AND b.read_finished_at=p.source_bootstrap_read_finished_at
                        AND b.read_finished_at<=p.published_at
                    LEFT JOIN %s f ON f.id=p.source_fixtures_payload_id
                        AND f.endpoint_kind='FIXTURES' AND f.event_id IS NULL AND f.player_id IS NULL
                        AND f.validation_state='VALID' AND f.source_published IS TRUE
                        AND f.read_started_at=p.source_fixtures_read_started_at
                        AND f.read_finished_at=p.source_fixtures_read_finished_at
                        AND f.read_finished_at<=p.published_at
                    LEFT JOIN %s br ON br.generation=b.run_generation AND br.season=r.season
                        AND br.kind='CATALOGUE_SCHEDULE' AND br.producer_key='LEGACY'
                        AND br.runtime_mode='ACTIVE' AND br.runtime_epoch=r.runtime_epoch
                        AND ((br.coalescing_key=? AND br.publication_key=?)
                          OR (br.coalescing_key=? AND br.publication_key=?))
                    LEFT JOIN %s fr ON fr.generation=f.run_generation AND fr.season=r.season
                        AND fr.kind='CATALOGUE_SCHEDULE' AND fr.producer_key='LEGACY'
                        AND fr.runtime_mode='ACTIVE' AND fr.runtime_epoch=r.runtime_epoch
                        AND ((fr.coalescing_key=? AND fr.publication_key=?)
                          OR (fr.coalescing_key=? AND fr.publication_key=?))
                    WHERE r.kind='CATALOGUE_SCHEDULE' AND r.producer_key='LEGACY' AND r.season=?
                      AND r.runtime_mode='ACTIVE' AND r.runtime_epoch=?
                      AND ((r.coalescing_key=? AND r.publication_key=?)
                           OR (r.coalescing_key=? AND r.publication_key=?))
                      AND r.state='COMPLETED' AND r.completed_at IS NOT NULL
                      AND r.completed_at<=p.published_at AND p.published_at IS NOT NULL
                      AND p.published_at<=?
                      AND r.source_read_started_at IS NOT NULL AND r.source_read_finished_at IS NOT NULL
                      AND r.source_read_started_at<=r.source_read_finished_at
                      AND r.source_read_finished_at<=p.published_at
                      AND p.source_read_started_at=r.source_read_started_at
                      AND p.source_read_finished_at=r.source_read_finished_at
                      AND b.id IS NOT NULL AND f.id IS NOT NULL
                      AND br.generation IS NOT NULL AND fr.generation IS NOT NULL
                      AND b.run_generation=f.run_generation
                      AND b.read_started_at<=b.read_finished_at AND f.read_started_at<=f.read_finished_at
                      AND b.read_finished_at<=p.published_at AND f.read_finished_at<=p.published_at
                      AND r.source_read_started_at<=b.read_started_at
                      AND r.source_read_started_at<=f.read_started_at
                      AND r.source_read_finished_at>=b.read_finished_at
                      AND r.source_read_finished_at>=f.read_finished_at
                      AND p.source_pair_fingerprint ~ '^[0-9a-f]{64}$'
                      AND p.source_bootstrap_sha256 ~ '^[0-9a-f]{64}$'
                      AND p.source_fixtures_sha256 ~ '^[0-9a-f]{64}$'
                      AND %s
                ), proof AS (
                    SELECT c.generation, c.published_at, c.source_generation,
                           c.source_bootstrap_payload_id, c.source_fixtures_payload_id,
                           c.source_bootstrap_read_started_at, c.source_bootstrap_read_finished_at,
                           c.source_fixtures_read_started_at, c.source_fixtures_read_finished_at,
                           c.source_bootstrap_sha256, c.source_fixtures_sha256, c.source_pair_fingerprint,
                           c.latest_generation=c.generation AND c.state='COMPLETED'
                               AND c.completed_at IS NOT NULL AND c.published_at IS NOT NULL
                               AND c.completed_at<=c.published_at AND c.published_at<=?
                               AND c.source_read_started_at IS NOT NULL
                               AND c.source_read_finished_at IS NOT NULL
                               AND c.source_read_started_at<=c.source_read_finished_at
                               AND c.source_read_finished_at<=c.published_at
                               AND c.receipt_started=c.source_read_started_at
                               AND c.receipt_finished=c.source_read_finished_at
                               AND c.source_pair_fingerprint IS NOT NULL
                               AND c.source_bootstrap_sha256 IS NOT NULL
                               AND c.source_fixtures_sha256 IS NOT NULL
                               AND c.source_valid AS current_published,
                           fs.first_finished_observed_at IS NOT NULL
                               AND fs.finished_parent_generation=c.source_generation
                               AND fs.finished_parent_publication_generation=c.generation
                               AND fs.finished_parent_bootstrap_payload_id=c.source_bootstrap_payload_id
                               AND fs.finished_parent_fixtures_payload_id=c.source_fixtures_payload_id
                               AND fs.finished_parent_source_fingerprint=c.source_pair_fingerprint
                               AND fs.finished_parent_fixture_fingerprint ~ '^[0-9a-f]{64}$'
                               AND fs.finished_parent_finished IS TRUE AS fixture_finished
                    FROM candidate c
                    LEFT JOIN %s fs ON fs.season_code=c.season AND fs.fixture_id=?
                )
                SELECT current_published,
                       current_published AND COALESCE(fixture_finished,FALSE) AS fixture_finished,
                       source_generation, source_bootstrap_payload_id, source_fixtures_payload_id,
                       source_bootstrap_read_started_at, source_bootstrap_read_finished_at,
                       source_fixtures_read_started_at, source_fixtures_read_finished_at,
                       source_bootstrap_sha256, source_fixtures_sha256, source_pair_fingerprint
                FROM proof
                ORDER BY %s
                """.formatted(tables.run(), tables.receipt(), tables.payload(), tables.payload(),
                tables.run(), tables.run(), canonicalDemandFloorPredicate("r", "br", "fr", "b", "f"),
                tables.fixtureSync(), canonicalParentWinnerOrder("source_bootstrap_read_finished_at",
                        "source_fixtures_read_finished_at", "published_at", "generation")), (rs, row) -> {
                    long sourceGeneration = rs.getLong("source_generation");
                    CanonicalSourcePair sourcePair = CanonicalSourcePair.of(
                            new CanonicalSourcePayload(rs.getLong("source_bootstrap_payload_id"), sourceGeneration,
                                    EndpointKind.BOOTSTRAP_STATIC,
                                    new SourceRead(rs.getTimestamp("source_bootstrap_read_started_at").toInstant(),
                                            rs.getTimestamp("source_bootstrap_read_finished_at").toInstant()),
                                    rs.getString("source_bootstrap_sha256")),
                            new CanonicalSourcePayload(rs.getLong("source_fixtures_payload_id"), sourceGeneration,
                                    EndpointKind.FIXTURES,
                                    new SourceRead(rs.getTimestamp("source_fixtures_read_started_at").toInstant(),
                                            rs.getTimestamp("source_fixtures_read_finished_at").toInstant()),
                                    rs.getString("source_fixtures_sha256")));
                    return new CanonicalParentCandidate(sourcePair, rs.getString("source_pair_fingerprint"),
                            new CanonicalParentEvidence(rs.getBoolean("current_published"),
                                    rs.getBoolean("fixture_finished")));
                },
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY,
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY,
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY,
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY,
                season, runtimeEpoch(),
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY,
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY,
                timestamp(now), timestamp(now), fixtureId)
                .stream().filter(CanonicalParentCandidate::fingerprintMatches)
                .map(CanonicalParentCandidate::evidence)
                .findFirst().orElse(CanonicalParentEvidence.MISSING);
    }

    /** Records one compact source fingerprint per leased cycle without retaining either body. */
    boolean recordIncrementalSourceSnapshot(Lease lease, String fingerprint, SourceRead read) {
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid incremental source fingerprint");
        }
        Objects.requireNonNull(read);
        return tx.execute(status -> {
            LockedRun run = lockAndValidate(lease);
            requireIncrementalRun(run);
            List<SnapshotReceipt> prior = jdbc.query("""
                    SELECT latest_generation, source_pair_fingerprint
                    FROM %s WHERE season=? AND publication_key=? FOR UPDATE
                    """.formatted(tables.receipt()), (rs, row) -> new SnapshotReceipt(
                    (Long) rs.getObject("latest_generation"), rs.getString("source_pair_fingerprint")),
                    run.season, INCREMENTAL_CORE_KEY);
            if (prior.size() != 1) throw new IllegalStateException("Incremental runner receipt is missing");
            SnapshotReceipt current = prior.getFirst();
            if (current.generation() != null && current.generation() == run.generation) {
                return fingerprint.equals(current.fingerprint());
            }
            if (current.generation() != null && current.generation() > run.generation) {
                throw new StaleGenerationException(run.generation, current.generation(), run.season,
                        INCREMENTAL_CORE_KEY);
            }
            Instant recordedAt = now();
            jdbc.update("""
                    UPDATE %s SET latest_generation=?, source_read_started_at=?, source_read_finished_at=?,
                        source_pair_fingerprint=?, published_at=?
                    WHERE season=? AND publication_key=?
                    """.formatted(tables.receipt()), run.generation, timestamp(read.startedAt()),
                    timestamp(read.finishedAt()), fingerprint, timestamp(recordedAt), run.season,
                    INCREMENTAL_CORE_KEY);
            includeIncrementalSourceRead(run, read);
            return true;
        });
    }

    CanonicalSourcePair incrementalSourcePair(Lease lease,
            CatalogueScheduleParser.SourceDocument bootstrap,
            CatalogueScheduleParser.SourceDocument fixtures) {
        Objects.requireNonNull(bootstrap);
        Objects.requireNonNull(fixtures);
        StagedPayload stagedBootstrap = latestStagedPayload(lease, Endpoint.bootstrap())
                .orElseThrow(() -> new IllegalStateException("Bootstrap source payload was not staged"));
        StagedPayload stagedFixtures = latestStagedPayload(lease, Endpoint.fixtures())
                .orElseThrow(() -> new IllegalStateException("Fixture source payload was not staged"));
        if (stagedBootstrap.validationState() != ValidationState.VALID
                || stagedFixtures.validationState() != ValidationState.VALID
                || !stagedBootstrap.read().equals(new SourceRead(
                        postgresTimestampPrecision(bootstrap.readInterval().startedAt()),
                        postgresTimestampPrecision(bootstrap.readInterval().completedAt())))
                || !stagedFixtures.read().equals(new SourceRead(
                        postgresTimestampPrecision(fixtures.readInterval().startedAt()),
                        postgresTimestampPrecision(fixtures.readInterval().completedAt())))
                || !Arrays.equals(stagedBootstrap.body(), bootstrap.bytes())
                || !Arrays.equals(stagedFixtures.body(), fixtures.bytes())) {
            throw new IllegalArgumentException("Validated bootstrap and fixture payloads must match this source pair");
        }
        return CanonicalSourcePair.from(stagedBootstrap, stagedFixtures);
    }

    /** Returns the immutable source pair already published by this same incremental generation. */
    Optional<IncrementalCompatibilitySnapshot> incrementalCompatibilitySnapshot(Lease lease) {
        return tx.execute(status -> {
            LockedRun run = lockAndValidate(lease);
            requireIncrementalRun(run);
            List<IncrementalCompatibilityReceipt> receipts = jdbc.query("""
                    SELECT latest_generation, source_read_started_at, source_read_finished_at,
                           source_bootstrap_payload_id, source_fixtures_payload_id,
                           source_bootstrap_read_started_at, source_bootstrap_read_finished_at,
                           source_fixtures_read_started_at, source_fixtures_read_finished_at,
                           source_bootstrap_sha256, source_fixtures_sha256, source_pair_fingerprint
                    FROM %s WHERE season=? AND publication_key=?
                    """.formatted(tables.receipt()), (rs, row) -> new IncrementalCompatibilityReceipt(
                    rs.getObject("latest_generation", Long.class), instant(rs, "source_read_started_at"),
                    instant(rs, "source_read_finished_at"), rs.getObject("source_bootstrap_payload_id", Long.class),
                    rs.getObject("source_fixtures_payload_id", Long.class),
                    instant(rs, "source_bootstrap_read_started_at"),
                    instant(rs, "source_bootstrap_read_finished_at"),
                    instant(rs, "source_fixtures_read_started_at"),
                    instant(rs, "source_fixtures_read_finished_at"), rs.getString("source_bootstrap_sha256"),
                    rs.getString("source_fixtures_sha256"), rs.getString("source_pair_fingerprint")),
                    run.season, INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY);
            if (receipts.size() > 1) throw new IllegalStateException("Incremental compatibility receipt is ambiguous");
            if (receipts.isEmpty() || receipts.getFirst().generation() == null
                    || receipts.getFirst().generation() != run.generation) return Optional.empty();
            IncrementalCompatibilityReceipt receipt = receipts.getFirst();
            if (receipt.bootstrapId() == null || receipt.fixturesId() == null || receipt.sourceRead() == null
                    || receipt.bootstrapRead() == null || receipt.fixturesRead() == null
                    || receipt.bootstrapHash() == null || receipt.fixturesHash() == null
                    || receipt.fingerprint() == null) {
                throw new IllegalStateException("Incremental compatibility receipt is incomplete");
            }
            List<IncrementalSourcePayload> payloads = jdbc.query("""
                    SELECT id, run_generation, endpoint_kind, event_id, player_id,
                           read_started_at, read_finished_at, validation_state, validation_error, body,
                           source_published
                    FROM %s WHERE id IN (?, ?) ORDER BY id
                    """.formatted(tables.payload()), (rs, row) -> new IncrementalSourcePayload(
                    stagedPayload(rs, row), rs.getObject("event_id", Integer.class),
                    rs.getObject("player_id", Integer.class), rs.getBoolean("source_published")),
                    receipt.bootstrapId(), receipt.fixturesId());
            if (payloads.size() != 2) throw new IllegalStateException("Incremental compatibility payload disappeared");
            IncrementalSourcePayload bootstrap = payloads.stream()
                    .filter(payload -> payload.payload().id() == receipt.bootstrapId()).findFirst().orElseThrow();
            IncrementalSourcePayload fixtures = payloads.stream()
                    .filter(payload -> payload.payload().id() == receipt.fixturesId()).findFirst().orElseThrow();
            StagedPayload bootstrapPayload = bootstrap.payload();
            StagedPayload fixturePayload = fixtures.payload();
            if (bootstrap.eventId() != null || bootstrap.playerId() != null
                    || fixtures.eventId() != null || fixtures.playerId() != null
                    || !bootstrap.sourcePublished() || !fixtures.sourcePublished()
                    || bootstrapPayload.runGeneration() != run.generation
                    || fixturePayload.runGeneration() != run.generation
                    || bootstrapPayload.endpointKind() != EndpointKind.BOOTSTRAP_STATIC
                    || fixturePayload.endpointKind() != EndpointKind.FIXTURES
                    || bootstrapPayload.validationState() != ValidationState.VALID
                    || fixturePayload.validationState() != ValidationState.VALID) {
                throw new IllegalStateException("Incremental compatibility payload identity or proof changed");
            }
            CanonicalSourcePair pair = CanonicalSourcePair.from(bootstrapPayload, fixturePayload);
            if (!pair.bootstrap().read().equals(receipt.bootstrapRead())
                    || !pair.fixtures().read().equals(receipt.fixturesRead())
                    || !pair.bootstrap().sha256().equals(receipt.bootstrapHash())
                    || !pair.fixtures().sha256().equals(receipt.fixturesHash())
                    || !pair.fingerprint().equals(receipt.fingerprint())) {
                throw new IllegalStateException("Incremental compatibility pair no longer matches its receipt");
            }
            SourceRead sourceRead = receipt.sourceRead();
            requireCanonicalPairCoverage(sourceRead, pair);
            SourceRead canonicalSpan = combinedSourceRead(pair.bootstrap().read(), pair.fixtures().read());
            if (!sourceRead.equals(canonicalSpan)) {
                throw new IllegalStateException("Incremental compatibility receipt does not preserve its source span");
            }
            Instant sourceFloor = jdbc.queryForObject("SELECT requested_source_after FROM %s WHERE generation=?"
                    .formatted(tables.run()), Timestamp.class, run.generation).toInstant();
            if (pair.bootstrap().read().startedAt().isBefore(sourceFloor)
                    || pair.fixtures().read().startedAt().isBefore(sourceFloor)) {
                throw new IllegalStateException("Incremental compatibility pair predates its run source floor");
            }
            return Optional.of(new IncrementalCompatibilitySnapshot(pair, bootstrapPayload, fixturePayload, sourceRead));
        });
    }

    /** Atomically publishes the incremental source pair and its compatibility rows under the collector lease. */
    <T> T publishIncrementalCompatibility(Lease lease, CatalogueScheduleParser.Season season,
            CanonicalSourcePair sourcePair, SourceRead sourceRead, Supplier<T> publication) {
        Objects.requireNonNull(lease);
        Objects.requireNonNull(season);
        Objects.requireNonNull(sourcePair);
        Objects.requireNonNull(sourceRead);
        Objects.requireNonNull(publication);
        return tx.execute(status -> {
            LockedRun run = lockAndValidate(lease);
            requireIncrementalRun(run);
            if (lease.kind() != SyncKind.PLAYER_HISTORY || !season.code().equals(run.season)
                    || sourcePair.bootstrap().runGeneration() != run.generation
                    || sourcePair.fixtures().runGeneration() != run.generation) {
                throw new IllegalArgumentException("Compatibility source pair must belong to the leased season run");
            }
            requireIncrementalCompatibilitySeason(season);
            validateIncrementalCompatibilityPair(run, sourcePair, sourceRead);
            Instant now = now();
            requireNotExpired(run, now);
            requireReadNotFuture(sourceRead, now);
            Timestamp requestedAfter = jdbc.queryForObject("SELECT requested_source_after FROM %s WHERE generation=?"
                    .formatted(tables.run()), Timestamp.class, run.generation);
            if (requestedAfter == null
                    || sourcePair.bootstrap().read().startedAt().isBefore(requestedAfter.toInstant())
                    || sourcePair.fixtures().read().startedAt().isBefore(requestedAfter.toInstant())) {
                throw new SourceDemandAdvancedException(run.generation,
                        requestedAfter == null ? now : requestedAfter.toInstant());
            }
            requireCanonicalPairCoverage(sourceRead, sourcePair);
            lockCompatibilityReceipt(run, sourcePair);
            recordIncrementalSourceReceipt(run, CatalogueScheduleWriter.SHARED_TEAM_SOURCE_RECEIPT_KEY,
                    sourcePair.bootstrap().read());
            recordIncrementalSourceReceipt(run, CatalogueScheduleWriter.FIXTURE_SOURCE_RECEIPT_KEY,
                    sourcePair.fixtures().read());

            T result = publication.get();
            requireNotExpired(run, now());
            int marked = jdbc.update("""
                    UPDATE %s SET source_published=TRUE
                    WHERE id IN (?, ?) AND run_generation=? AND validation_state='VALID'
                      AND endpoint_kind IN ('BOOTSTRAP_STATIC','FIXTURES')
                    """.formatted(tables.payload()), sourcePair.bootstrap().id(),
                    sourcePair.fixtures().id(), run.generation);
            if (marked != 2) throw new IllegalStateException("Compatibility source pair changed during publication");
            Instant publishedAt = now();
            jdbc.update("""
                    UPDATE %s SET latest_generation=?, source_read_started_at=?, source_read_finished_at=?,
                        published_at=?, source_bootstrap_payload_id=?, source_fixtures_payload_id=?,
                        source_bootstrap_read_started_at=?, source_bootstrap_read_finished_at=?,
                        source_fixtures_read_started_at=?, source_fixtures_read_finished_at=?,
                        source_bootstrap_sha256=?, source_fixtures_sha256=?, source_pair_fingerprint=?
                    WHERE season=? AND publication_key=?
                    """.formatted(tables.receipt()), run.generation, timestamp(sourceRead.startedAt()),
                    timestamp(sourceRead.finishedAt()), timestamp(publishedAt), sourcePair.bootstrap().id(),
                    sourcePair.fixtures().id(), timestamp(sourcePair.bootstrap().read().startedAt()),
                    timestamp(sourcePair.bootstrap().read().finishedAt()),
                    timestamp(sourcePair.fixtures().read().startedAt()),
                    timestamp(sourcePair.fixtures().read().finishedAt()), sourcePair.bootstrap().sha256(),
                    sourcePair.fixtures().sha256(), sourcePair.fingerprint(), run.season,
                    INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY);
            requireNotExpired(run, now());
            return result;
        });
    }

    private void validateIncrementalCompatibilityPair(LockedRun run, CanonicalSourcePair sourcePair,
                                                       SourceRead publicationRead) {
        List<IncrementalSourcePayload> payloads = jdbc.query("""
                SELECT id, run_generation, endpoint_kind, event_id, player_id,
                       read_started_at, read_finished_at, validation_state, validation_error, body
                FROM %s WHERE id IN (?, ?) ORDER BY id FOR UPDATE
                """.formatted(tables.payload()), (rs, row) -> new IncrementalSourcePayload(
                stagedPayload(rs, row), rs.getObject("event_id", Integer.class),
                rs.getObject("player_id", Integer.class)),
                sourcePair.bootstrap().id(), sourcePair.fixtures().id());
        if (payloads.size() != 2) throw new IllegalStateException("Compatibility source payload disappeared");
        IncrementalSourcePayload bootstrap = payloads.stream()
                .filter(payload -> payload.payload().id() == sourcePair.bootstrap().id()).findFirst().orElseThrow();
        IncrementalSourcePayload fixtures = payloads.stream()
                .filter(payload -> payload.payload().id() == sourcePair.fixtures().id()).findFirst().orElseThrow();
        if (bootstrap.payload().runGeneration() != run.generation
                || fixtures.payload().runGeneration() != run.generation
                || bootstrap.payload().endpointKind() != EndpointKind.BOOTSTRAP_STATIC
                || fixtures.payload().endpointKind() != EndpointKind.FIXTURES
                || bootstrap.eventId() != null || bootstrap.playerId() != null
                || fixtures.eventId() != null || fixtures.playerId() != null
                || bootstrap.payload().validationState() != ValidationState.VALID
                || fixtures.payload().validationState() != ValidationState.VALID
                || !sourcePair.equals(CanonicalSourcePair.from(bootstrap.payload(), fixtures.payload()))) {
            throw new IllegalStateException("Compatibility source payload identity or validation changed");
        }
        requireReadNotFuture(publicationRead, now());
    }

    private void lockCompatibilityReceipt(LockedRun run, CanonicalSourcePair sourcePair) {
        jdbc.update("INSERT INTO %s(season, publication_key) VALUES (?, ?) ON CONFLICT DO NOTHING"
                .formatted(tables.receipt()), run.season, INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY);
        List<CompatibilityReceipt> receipts = jdbc.query("""
                SELECT latest_generation, source_pair_fingerprint, source_bootstrap_payload_id,
                       source_fixtures_payload_id
                FROM %s WHERE season=? AND publication_key=? FOR UPDATE
                """.formatted(tables.receipt()), (rs, ignored) -> new CompatibilityReceipt(
                (Long) rs.getObject("latest_generation"), rs.getString("source_pair_fingerprint"),
                (Long) rs.getObject("source_bootstrap_payload_id"),
                (Long) rs.getObject("source_fixtures_payload_id"), null, null), run.season,
                INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY);
        if (receipts.size() != 1) throw new IllegalStateException("Compatibility publication receipt is missing");
        CompatibilityReceipt previous = receipts.getFirst();
        if (previous.generation() != null && previous.generation() > run.generation) {
            throw new StaleGenerationException(run.generation, previous.generation(), run.season,
                    INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY);
        }
        if (previous.generation() != null && previous.generation() == run.generation
                && (!Objects.equals(previous.fingerprint(), sourcePair.fingerprint())
                    || !Objects.equals(previous.bootstrapPayloadId(), sourcePair.bootstrap().id())
                    || !Objects.equals(previous.fixturesPayloadId(), sourcePair.fixtures().id()))) {
            throw new IllegalStateException("One incremental generation cannot publish two compatibility source pairs");
        }
    }

    private void recordIncrementalSourceReceipt(LockedRun run, String streamKey, SourceRead read) {
        jdbc.update("INSERT INTO %s(season, publication_key) VALUES (?, ?) ON CONFLICT DO NOTHING"
                .formatted(tables.receipt()), run.season, streamKey);
        List<CompatibilityReceipt> rows = jdbc.query("""
                SELECT latest_generation, source_read_started_at, source_read_finished_at
                FROM %s WHERE season=? AND publication_key=? FOR UPDATE
                """.formatted(tables.receipt()), (rs, ignored) -> new CompatibilityReceipt(
                (Long) rs.getObject("latest_generation"), null, null, null,
                instant(rs, "source_read_started_at"), instant(rs, "source_read_finished_at")),
                run.season, streamKey);
        if (rows.size() != 1) throw new IllegalStateException("Shared source chronology receipt is missing");
        CompatibilityReceipt previous = rows.getFirst();
        if (previous.generation() != null && previous.generation() > run.generation
                || previous.sourceFinishedAt() != null && previous.sourceFinishedAt().isAfter(read.finishedAt())
                || previous.generation() != null && previous.generation() < run.generation
                    && previous.sourceFinishedAt() != null
                    && read.startedAt().isBefore(previous.sourceFinishedAt())) {
            throw new CatalogueScheduleWriter.SourceRegressionException(
                    streamKey + " source read predates its shared chronology receipt");
        }
        Instant recordedAt = now();
        jdbc.update("""
                UPDATE %s SET latest_generation=GREATEST(COALESCE(latest_generation, ?), ?),
                    source_read_started_at=?, source_read_finished_at=?, published_at=?
                WHERE season=? AND publication_key=?
                """.formatted(tables.receipt()), run.generation, run.generation,
                timestamp(read.startedAt()), timestamp(read.finishedAt()), timestamp(recordedAt),
                run.season, streamKey);
    }

    /** Stores source-discovery checkpoints and returns only compact metadata for this snapshot. */
    IncrementalDiscovery observeIncrementalSnapshot(Lease lease,
            CatalogueScheduleParser.Snapshot snapshot) {
        Objects.requireNonNull(snapshot);
        return tx.execute(status -> {
            LockedRun run = lockAndValidate(lease);
            requireIncrementalRun(run);
            requireIncrementalRuntime(snapshot.season());
            Map<Integer, IncrementalEvent> events = new LinkedHashMap<>();
            Instant observedAt = max(snapshot.bootstrapRead().completedAt(), snapshot.fixturesRead().completedAt());
            for (CatalogueScheduleParser.GameweekRow event : snapshot.gameweeks()) {
                GameweekFinality.SourceSnapshot source = GameweekFinality.SourceSnapshot.from(snapshot, event.id());
                IncrementalEvent state = observeIncrementalEvent(snapshot, source, event, observedAt);
                events.put(event.id(), state);
            }

            List<CatalogueScheduleParser.FixtureRow> finished = snapshot.fixtures().stream()
                    .filter(CatalogueScheduleParser.FixtureRow::finished).toList();
            Instant recordedAt = now();
            for (CatalogueScheduleParser.FixtureRow fixture : finished) {
                String key = finishedFixtureReceiptKey(fixture.id());
                jdbc.update("""
                        INSERT INTO %s(season, publication_key, latest_generation,
                            source_read_started_at, source_read_finished_at, source_pair_fingerprint, published_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (season, publication_key) DO UPDATE SET
                            latest_generation=EXCLUDED.latest_generation,
                            source_read_started_at=EXCLUDED.source_read_started_at,
                            source_read_finished_at=EXCLUDED.source_read_finished_at,
                            source_pair_fingerprint=EXCLUDED.source_pair_fingerprint,
                            published_at=EXCLUDED.published_at
                        WHERE %s.latest_generation<=EXCLUDED.latest_generation
                          AND %s.source_pair_fingerprint IS DISTINCT FROM EXCLUDED.source_pair_fingerprint
                        """.formatted(tables.receipt(), tables.receipt(), tables.receipt()), run.season, key,
                        run.generation, timestamp(snapshot.fixturesRead().startedAt()),
                        timestamp(snapshot.fixturesRead().completedAt()),
                        incrementalFixtureFingerprint(fixture), timestamp(recordedAt));
            }
            List<CatalogueScheduleParser.FixtureRow> notFinished = snapshot.fixtures().stream()
                    .filter(fixture -> !fixture.finished()).toList();
            deleteFinishedMarkers(run.season, notFinished);
            Map<Integer, Instant> finishedFloors = readFinishedMarkers(run.season, finished);
            return new IncrementalDiscovery(events, finishedFloors);
        });
    }

    private IncrementalEvent observeIncrementalEvent(CatalogueScheduleParser.Snapshot schedule,
            GameweekFinality.SourceSnapshot source, CatalogueScheduleParser.GameweekRow event,
            Instant observedAt) {
        String signature = source.signature();
        List<GameweekSyncRow> rows = jdbc.query("""
                SELECT state, source_signature, first_checked_at, current_invalidation_revision,
                       current_invalidation_floor, finalized_source_signature, finalized_invalidation_revision
                FROM %s WHERE season_id=? AND gameweek=? FOR UPDATE
                """.formatted(tables.gameweekStatSync()), (rs, row) -> new GameweekSyncRow(
                rs.getString("state"), rs.getString("source_signature"), instant(rs, "first_checked_at"),
                rs.getLong("current_invalidation_revision"), instant(rs, "current_invalidation_floor"),
                rs.getString("finalized_source_signature"),
                (Long) rs.getObject("finalized_invalidation_revision")), schedule.season().id(), event.id());
        GameweekSyncRow prior = rows.stream().findFirst().orElse(null);
        boolean eligible = source.finalSweepEligible();
        boolean changed = prior == null || !signature.equals(prior.sourceSignature());
        boolean finalized = eligible && prior != null && !changed && "FINALIZED".equals(prior.state())
                && signature.equals(prior.finalizedSourceSignature())
                && Objects.equals(prior.finalizedInvalidationRevision(), prior.invalidationRevision());
        String state = finalized ? "FINALIZED" : eligible ? "FINAL_PENDING" : "OPEN";
        Instant firstCheckedAt = eligible
                ? prior != null && !changed && prior.firstCheckedAt() != null
                    ? prior.firstCheckedAt() : observedAt
                : null;
        long invalidationRevision = prior == null ? 0
                : prior.invalidationRevision() + (changed ? 1 : 0);
        Instant invalidationFloor = prior == null
                ? eligible ? observedAt : null
                : changed ? max(prior.invalidationFloor(), observedAt) : prior.invalidationFloor();

        if (prior == null) {
            jdbc.update("""
                    INSERT INTO %s(season_id, season_code, gameweek, state, event_finished, data_checked,
                        fixture_manifest_complete, all_assigned_fixtures_finished, source_signature,
                        first_checked_at, current_invalidation_revision, current_invalidation_floor, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, TRUE, ?, ?, ?, ?, ?, ?)
                    """.formatted(tables.gameweekStatSync()), schedule.season().id(), schedule.season().code(),
                    event.id(), state, event.finished(), event.dataChecked(), source.allAssignedFixturesFinished(),
                    signature, nullableTimestamp(firstCheckedAt), invalidationRevision,
                    nullableTimestamp(invalidationFloor), timestamp(now()));
        } else if (changed || !state.equals(prior.state())
                || !Objects.equals(firstCheckedAt, prior.firstCheckedAt())) {
            jdbc.update("""
                    UPDATE %s SET state=?, event_finished=?, data_checked=?, fixture_manifest_complete=TRUE,
                        all_assigned_fixtures_finished=?, source_signature=?, first_checked_at=?,
                        current_invalidation_revision=?, current_invalidation_floor=?,
                        finalized_source_signature=NULL, finalized_population_fingerprint=NULL,
                        finalized_row_revision_fingerprint=NULL, finalized_source_floor=NULL,
                        finalized_validation_read_started_at=NULL, finalized_validation_read_finished_at=NULL,
                        finalized_source_read_started_at=NULL, finalized_source_read_finished_at=NULL,
                        finalized_invalidation_revision=NULL, finalized_run_generation=NULL,
                        finalized_page_evidence=NULL, finalized_at=NULL,
                        finalized_mandatory_manifest_complete=NULL, finalized_optional_component_coverage=NULL,
                        updated_at=?
                    WHERE season_id=? AND gameweek=?
                    """.formatted(tables.gameweekStatSync()), state, event.finished(), event.dataChecked(),
                    source.allAssignedFixturesFinished(), signature, nullableTimestamp(firstCheckedAt),
                    invalidationRevision, nullableTimestamp(invalidationFloor), timestamp(now()),
                    schedule.season().id(), event.id());
        }
        return new IncrementalEvent(event.id(), state, firstCheckedAt, signature);
    }

    private static String incrementalFixtureFingerprint(CatalogueScheduleParser.FixtureRow fixture) {
        return sha256(FixtureVerification.verifiedHeader(fixture) + "|" + FixtureStatsFingerprint.of(fixture));
    }

    private void deleteFinishedMarkers(String season, List<CatalogueScheduleParser.FixtureRow> fixtures) {
        if (fixtures.isEmpty()) return;
        String placeholders = String.join(",", java.util.Collections.nCopies(fixtures.size(), "?"));
        List<Object> parameters = new ArrayList<>(fixtures.size() + 1);
        parameters.add(season);
        fixtures.stream().map(fixture -> finishedFixtureReceiptKey(fixture.id())).forEach(parameters::add);
        jdbc.update("DELETE FROM %s WHERE season=? AND publication_key IN (%s)"
                .formatted(tables.receipt(), placeholders), parameters.toArray());
    }

    private Map<Integer, Instant> readFinishedMarkers(String season,
            List<CatalogueScheduleParser.FixtureRow> fixtures) {
        if (fixtures.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(fixtures.size(), "?"));
        List<Object> parameters = new ArrayList<>(fixtures.size() + 1);
        parameters.add(season);
        fixtures.stream().map(fixture -> finishedFixtureReceiptKey(fixture.id())).forEach(parameters::add);
        Map<Integer, Instant> result = new LinkedHashMap<>();
        jdbc.query("SELECT publication_key, source_read_finished_at FROM %s WHERE season=? "
                .formatted(tables.receipt()) + "AND publication_key IN (" + placeholders + ")",
                rs -> {
                    result.put(Integer.parseInt(rs.getString("publication_key")
                            .substring(FINISHED_FIXTURE_RECEIPT_PREFIX.length())),
                            instant(rs, "source_read_finished_at"));
                }, parameters.toArray());
        return Map.copyOf(result);
    }

    Map<Integer, Set<Integer>> knownPlayerIds(Lease lease, int seasonId, List<Integer> fixtureIds) {
        if (fixtureIds.isEmpty()) return Map.of();
        return tx.execute(status -> {
            LockedRun run = lockAndValidate(lease);
            requireIncrementalRun(run);
            String placeholders = String.join(",", java.util.Collections.nCopies(fixtureIds.size(), "?"));
            String sql = "SELECT fixture_id, player_id FROM player_fixture_stats WHERE season_id=? AND fixture_id IN ("
                    + placeholders + ") UNION SELECT fixture_id, player_id FROM " + tables.playerFixtureCurrent()
                    + " WHERE season_id=? AND fixture_id IN (" + placeholders + ")";
            List<Object> parameters = new ArrayList<>(fixtureIds.size() * 2 + 2);
            parameters.add(seasonId);
            parameters.addAll(fixtureIds);
            parameters.add(seasonId);
            parameters.addAll(fixtureIds);
            Map<Integer, Set<Integer>> result = new LinkedHashMap<>();
            jdbc.query(sql, rs -> {
                result.computeIfAbsent(rs.getInt("fixture_id"), ignored -> new java.util.TreeSet<>())
                        .add(rs.getInt("player_id"));
            }, parameters.toArray());
            mergeDiscoveredPlayerIds(result, readParticipantLedger(run.season, fixtureIds));
            return immutablePlayerIds(result);
        });
    }

    Map<Integer, Set<Integer>> incrementalDiscoveredPlayerIds(Lease lease, List<Integer> fixtureIds) {
        if (fixtureIds.isEmpty()) return Map.of();
        return tx.execute(status -> {
            LockedRun run = lockAndValidate(lease);
            requireIncrementalRun(run);
            return readFixturePlayerReceipts(run.season, fixtureIds, INCREMENTAL_DISCOVERY_RECEIPT_PREFIX,
                    INCREMENTAL_PARTICIPANT_RECEIPT_PREFIX);
        });
    }

    Set<Integer> incrementalFixturesNeedingDiscovery(Lease lease, Map<Integer, String> sourceFingerprints) {
        if (sourceFingerprints.isEmpty()) return Set.of();
        sourceFingerprints.forEach((fixtureId, fingerprint) -> {
            if (fixtureId == null || fixtureId <= 0 || fingerprint == null
                    || !fingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid fixture population checkpoint");
            }
        });
        return tx.execute(status -> {
            LockedRun run = lockAndValidate(lease);
            requireIncrementalRun(run);
            List<Integer> ids = sourceFingerprints.keySet().stream().sorted().toList();
            String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
            List<Object> parameters = new ArrayList<>(ids.size() + 1);
            parameters.add(run.season);
            ids.stream().map(id -> INCREMENTAL_POPULATION_RECEIPT_PREFIX + id).forEach(parameters::add);
            Map<Integer, String> stored = new HashMap<>();
            jdbc.query("SELECT publication_key, source_pair_fingerprint FROM " + tables.receipt()
                    + " WHERE season=? AND publication_key IN (" + placeholders + ")", rs -> {
                String key = rs.getString("publication_key");
                stored.put(Integer.parseInt(key.substring(INCREMENTAL_POPULATION_RECEIPT_PREFIX.length())),
                        rs.getString("source_pair_fingerprint"));
            }, parameters.toArray());
            Set<Integer> needsDiscovery = new HashSet<>();
            sourceFingerprints.forEach((id, fingerprint) -> {
                if (!fingerprint.equals(stored.get(id))) needsDiscovery.add(id);
            });
            return Set.copyOf(needsDiscovery);
        });
    }

    void recordIncrementalPopulation(Lease lease, Map<Integer, Set<Integer>> sourceParticipantsByFixture,
                                     Map<Integer, Set<Integer>> candidatePlayerIdsByFixture,
                                     Map<Integer, Set<Integer>> previouslyDiscoveredPlayerIdsByFixture,
                                     Map<Integer, String> sourceFingerprints, SourceRead sourceRead) {
        Objects.requireNonNull(sourceParticipantsByFixture);
        Objects.requireNonNull(candidatePlayerIdsByFixture);
        Objects.requireNonNull(previouslyDiscoveredPlayerIdsByFixture);
        Objects.requireNonNull(sourceFingerprints);
        Objects.requireNonNull(sourceRead);
        if (!candidatePlayerIdsByFixture.keySet().equals(sourceFingerprints.keySet())
                || !sourceFingerprints.keySet().containsAll(sourceParticipantsByFixture.keySet())) {
            throw new IllegalArgumentException("Fixture population and checkpoint sets must match");
        }
        tx.executeWithoutResult(status -> {
            LockedRun run = lockAndValidate(lease);
            requireIncrementalRun(run);
            Instant recordedAt = now();
            List<Object[]> newDiscoveryCandidates = new ArrayList<>();
            for (int fixtureId : candidatePlayerIdsByFixture.keySet().stream().sorted().toList()) {
                String participantPrefix = INCREMENTAL_PARTICIPANT_RECEIPT_PREFIX + fixtureId + ":player:";
                for (int playerId : sourceParticipantsByFixture.getOrDefault(fixtureId, Set.of())
                        .stream().sorted().toList()) {
                    insertIncrementalReceipt(run.season, participantPrefix + playerId, run.generation,
                            sourceRead, null, recordedAt);
                }
                Set<Integer> existing = previouslyDiscoveredPlayerIdsByFixture.getOrDefault(fixtureId, Set.of());
                for (int playerId : candidatePlayerIdsByFixture.get(fixtureId).stream().sorted().toList()) {
                    if (existing.contains(playerId)) continue;
                    String discoveryKey = INCREMENTAL_DISCOVERY_RECEIPT_PREFIX + fixtureId + ":player:" + playerId;
                    newDiscoveryCandidates.add(new Object[] {run.season, discoveryKey, run.generation,
                            timestamp(sourceRead.startedAt()), timestamp(sourceRead.finishedAt()), timestamp(recordedAt)});
                }
                insertIncrementalPopulationReceipt(run.season, INCREMENTAL_POPULATION_RECEIPT_PREFIX + fixtureId,
                        run.generation, sourceRead, sourceFingerprints.get(fixtureId), recordedAt);
            }
            insertIncrementalDiscoveryCandidates(newDiscoveryCandidates);
        });
    }

    void recordIncrementalPopulation(Lease lease, Map<Integer, Set<Integer>> sourceParticipantsByFixture,
                                     Map<Integer, Set<Integer>> candidatePlayerIdsByFixture,
                                     Map<Integer, String> sourceFingerprints, SourceRead sourceRead) {
        Map<Integer, Set<Integer>> existing = incrementalDiscoveredPlayerIds(lease,
                candidatePlayerIdsByFixture.keySet().stream().sorted().toList());
        recordIncrementalPopulation(lease, sourceParticipantsByFixture, candidatePlayerIdsByFixture,
                existing, sourceFingerprints, sourceRead);
    }

    void recordIncrementalPopulation(Lease lease, Map<Integer, Set<Integer>> playersByFixture,
                                     Map<Integer, String> sourceFingerprints, SourceRead sourceRead) {
        recordIncrementalPopulation(lease, playersByFixture, playersByFixture, sourceFingerprints, sourceRead);
    }

    private Map<Integer, Set<Integer>> readParticipantLedger(String season, List<Integer> fixtureIds) {
        return readFixturePlayerReceipts(season, fixtureIds, INCREMENTAL_PARTICIPANT_RECEIPT_PREFIX);
    }

    private Map<Integer, Set<Integer>> readFixturePlayerReceipts(String season, List<Integer> fixtureIds,
                                                                  String... prefixes) {
        Set<Integer> requestedFixtures = Set.copyOf(fixtureIds);
        Map<Integer, Set<Integer>> result = new LinkedHashMap<>();
        List<Object> parameters = new ArrayList<>(prefixes.length + 1);
        parameters.add(season);
        for (String prefix : prefixes) parameters.add(prefix + "%:player:%");
        String matchesPrefix = String.join(" OR ", java.util.Collections.nCopies(prefixes.length,
                "publication_key LIKE ?"));
        jdbc.queryForList("SELECT publication_key FROM %s WHERE season=? AND (%s)"
                .formatted(tables.receipt(), matchesPrefix), String.class, parameters.toArray()).forEach(key -> {
            String matchingPrefix = null;
            for (String prefix : prefixes) {
                if (key.startsWith(prefix)) {
                    matchingPrefix = prefix;
                    break;
                }
            }
            if (matchingPrefix == null) return;
            int separator = key.indexOf(":player:", matchingPrefix.length());
            if (separator < 0) return;
            try {
                int fixtureId = Integer.parseInt(key.substring(matchingPrefix.length(), separator));
                int playerId = Integer.parseInt(key.substring(separator + ":player:".length()));
                if (requestedFixtures.contains(fixtureId)) {
                    result.computeIfAbsent(fixtureId, ignored -> new java.util.TreeSet<>()).add(playerId);
                }
            } catch (NumberFormatException invalidKey) {
                throw new IllegalStateException("Invalid incremental fixture player receipt key", invalidKey);
            }
        });
        return immutablePlayerIds(result);
    }

    private void insertIncrementalDiscoveryCandidates(List<Object[]> candidates) {
        String sql = """
                /* incremental discovery candidate batch */
                INSERT INTO %s(season, publication_key, latest_generation, source_read_started_at,
                    source_read_finished_at, published_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (season, publication_key) DO NOTHING
                """.formatted(tables.receipt());
        for (int offset = 0; offset < candidates.size(); offset += INCREMENTAL_DISCOVERY_BATCH_SIZE) {
            jdbc.batchUpdate(sql, candidates.subList(offset,
                    Math.min(offset + INCREMENTAL_DISCOVERY_BATCH_SIZE, candidates.size())));
        }
    }

    private void insertIncrementalPopulationReceipt(String season, String key, long generation,
                                                     SourceRead read, String fingerprint, Instant publishedAt) {
        jdbc.update("""
                INSERT INTO %s(season, publication_key, latest_generation, source_read_started_at,
                    source_read_finished_at, source_pair_fingerprint, published_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (season, publication_key) DO UPDATE SET
                    latest_generation=EXCLUDED.latest_generation,
                    source_read_started_at=EXCLUDED.source_read_started_at,
                    source_read_finished_at=EXCLUDED.source_read_finished_at,
                    source_pair_fingerprint=EXCLUDED.source_pair_fingerprint,
                    published_at=EXCLUDED.published_at
                WHERE %s.latest_generation<=EXCLUDED.latest_generation
                  AND %s.source_pair_fingerprint IS DISTINCT FROM EXCLUDED.source_pair_fingerprint
                """.formatted(tables.receipt(), tables.receipt(), tables.receipt()), season, key, generation,
                timestamp(read.startedAt()), timestamp(read.finishedAt()), fingerprint, timestamp(publishedAt));
    }

    private void insertIncrementalSourceFixtureReceipt(String season, String key, long generation,
                                                       SourceRead read, String fingerprint, Instant publishedAt) {
        jdbc.update("""
                INSERT INTO %s(season, publication_key, latest_generation, source_read_started_at,
                    source_read_finished_at, source_pair_fingerprint, published_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (season, publication_key) DO UPDATE SET
                    latest_generation=EXCLUDED.latest_generation,
                    source_read_started_at=EXCLUDED.source_read_started_at,
                    source_read_finished_at=EXCLUDED.source_read_finished_at,
                    source_pair_fingerprint=EXCLUDED.source_pair_fingerprint,
                    published_at=EXCLUDED.published_at
                WHERE %s.latest_generation<=EXCLUDED.latest_generation
                  AND %s.source_pair_fingerprint IS DISTINCT FROM EXCLUDED.source_pair_fingerprint
                """.formatted(tables.receipt(), tables.receipt(), tables.receipt()), season, key, generation,
                timestamp(read.startedAt()), timestamp(read.finishedAt()), fingerprint, timestamp(publishedAt));
    }

    private void insertIncrementalReceipt(String season, String key, long generation, SourceRead read,
                                         String fingerprint, Instant publishedAt) {
        jdbc.update("""
                INSERT INTO %s(season, publication_key, latest_generation, source_read_started_at,
                    source_read_finished_at, source_pair_fingerprint, published_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (season, publication_key) DO UPDATE SET
                    latest_generation=EXCLUDED.latest_generation,
                    source_read_started_at=EXCLUDED.source_read_started_at,
                    source_read_finished_at=EXCLUDED.source_read_finished_at,
                    source_pair_fingerprint=COALESCE(EXCLUDED.source_pair_fingerprint,
                        %s.source_pair_fingerprint),
                    published_at=EXCLUDED.published_at
                WHERE %s.latest_generation<=EXCLUDED.latest_generation
                  AND (%s.source_read_started_at IS NULL OR
                       (%s.source_read_started_at,%s.source_read_finished_at)
                           <= (EXCLUDED.source_read_started_at,EXCLUDED.source_read_finished_at))
                """.formatted(tables.receipt(), tables.receipt(), tables.receipt(),
                tables.receipt(), tables.receipt(), tables.receipt()), season, key, generation,
                timestamp(read.startedAt()), timestamp(read.finishedAt()), fingerprint, timestamp(publishedAt));
    }

    private static void mergeDiscoveredPlayerIds(Map<Integer, Set<Integer>> target,
                                                   Map<Integer, Set<Integer>> discovered) {
        discovered.forEach((fixture, players) ->
                target.computeIfAbsent(fixture, ignored -> new java.util.TreeSet<>()).addAll(players));
    }

    private static Map<Integer, Set<Integer>> immutablePlayerIds(Map<Integer, Set<Integer>> source) {
        Map<Integer, Set<Integer>> result = new LinkedHashMap<>();
        source.forEach((id, players) -> result.put(id, Set.copyOf(players)));
        return Map.copyOf(result);
    }

    Map<Integer, PageReceipt> incrementalPageReceipts(Lease lease, Set<Integer> playerIds) {
        return incrementalReceiptMap(lease, playerIds, IncrementalPlayerFixturePublisher.PAGE_RECEIPT_PREFIX);
    }

    Map<Integer, PageReceipt> incrementalLiveEventReceipts(Lease lease, Set<Integer> eventIds) {
        return incrementalReceiptMap(lease, eventIds, INCREMENTAL_LIVE_EVENT_RECEIPT_PREFIX);
    }

    Map<Integer, IncrementalAppearanceState> incrementalAppearanceStates(Lease lease, Set<Integer> fixtureIds) {
        Objects.requireNonNull(fixtureIds);
        if (fixtureIds.isEmpty()) return Map.of();
        return tx.execute(status -> {
            LockedRun run = lockAndValidate(lease);
            requireIncrementalRun(run);
            List<Integer> ordered = fixtureIds.stream().sorted().toList();
            String placeholders = String.join(",", java.util.Collections.nCopies(ordered.size(), "?"));
            List<Object> parameters = new ArrayList<>(ordered.size() + 1);
            parameters.add(run.season);
            ordered.stream().map(IncrementalAppearanceReceipt::appearanceStateKey).forEach(parameters::add);
            Map<Integer, IncrementalAppearanceState> result = new LinkedHashMap<>();
            jdbc.query("""
                    SELECT publication_key, latest_generation, source_read_started_at,
                           source_read_finished_at, source_pair_fingerprint
                    FROM %s WHERE season=? AND publication_key IN (%s)
                    """.formatted(tables.receipt(), placeholders), rs -> {
                String key = rs.getString("publication_key");
                int fixtureId = Integer.parseInt(key.substring(
                        IncrementalAppearanceReceipt.APPEARANCE_STATE_PREFIX.length()));
                Long generation = rs.getObject("latest_generation", Long.class);
                Instant started = instant(rs, "source_read_started_at");
                Instant finished = instant(rs, "source_read_finished_at");
                String fingerprint = rs.getString("source_pair_fingerprint");
                if (generation == null || generation <= 0 || started == null || finished == null
                        || finished.isBefore(started) || fingerprint == null
                        || !fingerprint.matches("[0-9a-f]{64}")) {
                    throw new IllegalStateException("Persisted appearance invalidation state is malformed");
                }
                result.put(fixtureId, new IncrementalAppearanceState(fingerprint, finished));
            }, parameters.toArray());
            return Map.copyOf(result);
        });
    }

    private Map<Integer, PageReceipt> incrementalReceiptMap(Lease lease, Set<Integer> ids, String prefix) {
        if (ids.isEmpty()) return Map.of();
        return tx.execute(status -> {
            LockedRun run = lockAndValidate(lease);
            requireIncrementalRun(run);
            List<Integer> ordered = ids.stream().sorted().toList();
            String placeholders = String.join(",", java.util.Collections.nCopies(ordered.size(), "?"));
            List<Object> parameters = new ArrayList<>(ordered.size() + 1);
            parameters.add(run.season);
            ordered.stream().map(id -> prefix + id).forEach(parameters::add);
            Map<Integer, PageReceipt> result = new LinkedHashMap<>();
            jdbc.query("""
                    SELECT publication_key, latest_generation, source_read_started_at, source_read_finished_at
                    FROM %s WHERE season=? AND publication_key IN (%s)
                      AND latest_generation IS NOT NULL AND source_read_started_at IS NOT NULL
                      AND source_read_finished_at IS NOT NULL
                    """.formatted(tables.receipt(), placeholders), rs -> {
                String key = rs.getString("publication_key");
                int id = Integer.parseInt(key.substring(prefix.length()));
                result.put(id, new PageReceipt(rs.getLong("latest_generation"),
                        new SourceRead(instant(rs, "source_read_started_at"),
                                instant(rs, "source_read_finished_at"))));
            }, parameters.toArray());
            return Map.copyOf(result);
        });
    }

    Set<Integer> recordIncrementalLiveEvent(Lease lease, int eventId,
                                            Map<Integer, Set<Integer>> participants,
                                            List<CatalogueScheduleParser.FixtureRow> fixtures,
                                            Map<Integer, FixtureVerification.FixtureEvidence> evidence,
                                            SourceRead read) {
        if (eventId <= 0) throw new IllegalArgumentException("event id must be positive");
        Objects.requireNonNull(participants);
        Objects.requireNonNull(fixtures);
        Objects.requireNonNull(evidence);
        Objects.requireNonNull(read);
        participants.forEach((fixture, playerIds) -> {
            if (fixture == null || fixture <= 0 || playerIds == null
                    || playerIds.size() > HistoryFixtureDispatcher.MAX_FIXTURE_PLAYERS
                    || playerIds.stream().anyMatch(player -> player == null || player <= 0)) {
                throw new IllegalArgumentException("Invalid incremental fixture participant manifest");
            }
        });
        Map<Integer, CatalogueScheduleParser.FixtureRow> fixturesById = new LinkedHashMap<>();
        for (CatalogueScheduleParser.FixtureRow fixture : fixtures) {
            FixtureVerification.FixtureEvidence fixtureEvidence = evidence.get(fixture.id());
            if (fixture.gameweek() == null || fixture.gameweek() != eventId || fixtureEvidence == null
                    || !fixtureEvidence.liveRead().startedAt().equals(read.startedAt())
                    || !fixtureEvidence.liveRead().completedAt().equals(read.finishedAt())
                    || fixturesById.putIfAbsent(fixture.id(), fixture) != null) {
                throw new IllegalArgumentException("Invalid incremental fixture appearance evidence");
            }
        }
        if (!fixturesById.keySet().equals(participants.keySet())
                || !fixturesById.keySet().equals(evidence.keySet())
                || evidence.entrySet().stream().anyMatch(entry -> {
                    CatalogueScheduleParser.FixtureRow fixture = fixturesById.get(entry.getKey());
                    return !participants.get(entry.getKey()).containsAll(entry.getValue().positivePlayerIds())
                            || !participants.get(entry.getKey()).containsAll(
                                    FixtureVerification.fixtureStatParticipantIds(fixture));
                })) {
            throw new IllegalArgumentException("Live fixture, participant and appearance sets must match");
        }
        return tx.execute(status -> {
            LockedRun run = lockAndValidate(lease);
            requireIncrementalRun(run);
            requireReadNotFuture(read, now());
            List<Instant> priorEventReads = jdbc.query("SELECT source_read_finished_at FROM " + tables.receipt()
                    + " WHERE season=? AND publication_key=? AND source_read_finished_at IS NOT NULL",
                    (rs, row) -> instant(rs, "source_read_finished_at"), run.season,
                    INCREMENTAL_LIVE_EVENT_RECEIPT_PREFIX + eventId);
            if (priorEventReads.size() > 1 || !priorEventReads.isEmpty()
                    && read.startedAt().isBefore(priorEventReads.getFirst())) {
                throw new IllegalArgumentException("Live appearance capture is not ordered after its event source");
            }
            Set<Integer> invalidatedFixtures = new LinkedHashSet<>();
            Map<Integer, String> appearanceFingerprints = new LinkedHashMap<>();
            for (CatalogueScheduleParser.FixtureRow fixture : fixturesById.values()) {
                List<SourceFixtureRevision> sourceRevision = jdbc.query("SELECT source_pair_fingerprint,"
                        + "source_read_finished_at FROM " + tables.receipt()
                        + " WHERE season=? AND publication_key=?", (rs, row) -> new SourceFixtureRevision(
                                rs.getString("source_pair_fingerprint"), instant(rs, "source_read_finished_at")),
                        run.season, IncrementalAppearanceReceipt.sourceFixtureKey(fixture.id()));
                String sourceFingerprint = IncrementalAppearanceReceipt.fixtureRevisionFingerprint(fixture, eventId);
                if (sourceRevision.size() != 1
                        || !sourceFingerprint.equals(sourceRevision.getFirst().fingerprint())
                        || sourceRevision.getFirst().finishedAt() == null
                        || !read.startedAt().isAfter(sourceRevision.getFirst().finishedAt())) {
                    throw new IllegalArgumentException("Live appearance capture is not ordered after its fixture source");
                }
                String appearanceFingerprint = IncrementalAppearanceReceipt.fingerprint(
                        fixture, eventId, evidence.get(fixture.id()));
                appearanceFingerprints.put(fixture.id(), appearanceFingerprint);
                String stateKey = IncrementalAppearanceReceipt.appearanceStateKey(fixture.id());
                List<AppearanceStateReceipt> previous = jdbc.query("SELECT latest_generation,"
                                + "source_read_started_at,source_read_finished_at,source_pair_fingerprint FROM "
                                + tables.receipt() + " WHERE season=? AND publication_key=? FOR UPDATE",
                        (rs, row) -> new AppearanceStateReceipt(rs.getLong("latest_generation"),
                                instant(rs, "source_read_started_at"), instant(rs, "source_read_finished_at"),
                                rs.getString("source_pair_fingerprint")), run.season, stateKey);
                if (previous.size() > 1) {
                    throw new IllegalStateException("Fixture appearance state is not unique");
                }
                AppearanceStateReceipt priorState = previous.isEmpty() ? null : previous.getFirst();
                if (priorState != null && (priorState.generation() <= 0 || priorState.startedAt() == null
                        || priorState.finishedAt() == null || priorState.finishedAt().isBefore(priorState.startedAt())
                        || priorState.fingerprint() == null
                        || !priorState.fingerprint().matches("[0-9a-f]{64}"))) {
                    throw new IllegalStateException("Persisted appearance invalidation state is malformed");
                }
                if (priorState == null || !appearanceFingerprint.equals(priorState.fingerprint())) {
                    if (priorState != null && priorState.generation() > run.generation) {
                        throw new StaleGenerationException(run.generation, priorState.generation(),
                                run.season, stateKey);
                    }
                    invalidatedFixtures.add(fixture.id());
                    int updated = jdbc.update("""
                            INSERT INTO %s(season,publication_key,latest_generation,source_read_started_at,
                                source_read_finished_at,source_pair_fingerprint,published_at)
                            VALUES (?,?,?,?,?,?,?)
                            ON CONFLICT (season,publication_key) DO UPDATE SET
                                latest_generation=EXCLUDED.latest_generation,
                                source_read_started_at=EXCLUDED.source_read_started_at,
                                source_read_finished_at=EXCLUDED.source_read_finished_at,
                                source_pair_fingerprint=EXCLUDED.source_pair_fingerprint,
                                published_at=EXCLUDED.published_at
                            WHERE %s.latest_generation<=EXCLUDED.latest_generation
                            """.formatted(tables.receipt(), tables.receipt()), run.season, stateKey,
                            run.generation, timestamp(read.startedAt()), timestamp(read.finishedAt()),
                            appearanceFingerprint, timestamp(now()));
                    if (updated != 1) {
                        throw new StaleGenerationException(run.generation,
                                priorState == null ? run.generation : priorState.generation(), run.season, stateKey);
                    }
                }
            }
            Instant recordedAt = now();
            String key = INCREMENTAL_LIVE_EVENT_RECEIPT_PREFIX + eventId;
            jdbc.update("""
                    INSERT INTO %s(season, publication_key, latest_generation, source_read_started_at,
                        source_read_finished_at, published_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (season, publication_key) DO UPDATE SET
                        latest_generation=EXCLUDED.latest_generation,
                        source_read_started_at=EXCLUDED.source_read_started_at,
                        source_read_finished_at=EXCLUDED.source_read_finished_at,
                        published_at=EXCLUDED.published_at
                    WHERE %s.latest_generation<=EXCLUDED.latest_generation
                    """.formatted(tables.receipt(), tables.receipt()), run.season, key, run.generation,
                    timestamp(read.startedAt()), timestamp(read.finishedAt()), timestamp(recordedAt));
            insertIncrementalReceipt(run.season,
                    IncrementalAppearanceReceipt.liveSourceKey(eventId, run.generation,
                            read.startedAt(), read.finishedAt()),
                    run.generation, read, null, recordedAt);
            includeIncrementalSourceRead(run, read);
            for (Map.Entry<Integer, Set<Integer>> fixture : participants.entrySet()) {
                String fixturePrefix = INCREMENTAL_PARTICIPANT_RECEIPT_PREFIX
                        + fixture.getKey() + ":player:";
                for (int playerId : fixture.getValue().stream().sorted().toList()) {
                    jdbc.update("""
                            INSERT INTO %s(season, publication_key, latest_generation,
                                source_read_started_at, source_read_finished_at, published_at)
                            VALUES (?, ?, ?, ?, ?, ?)
                            ON CONFLICT (season, publication_key) DO UPDATE SET
                                latest_generation=EXCLUDED.latest_generation,
                                source_read_started_at=EXCLUDED.source_read_started_at,
                                source_read_finished_at=EXCLUDED.source_read_finished_at,
                                published_at=EXCLUDED.published_at
                            WHERE %s.latest_generation<=EXCLUDED.latest_generation
                            """.formatted(tables.receipt(), tables.receipt()), run.season,
                            fixturePrefix + playerId, run.generation, timestamp(read.startedAt()),
                            timestamp(read.finishedAt()), timestamp(recordedAt));
                }
                CatalogueScheduleParser.FixtureRow fixtureRow = fixturesById.get(fixture.getKey());
                FixtureVerification.FixtureEvidence fixtureEvidence = evidence.get(fixture.getKey());
                insertIncrementalReceipt(run.season,
                        IncrementalAppearanceReceipt.snapshotKey(fixtureRow, eventId, fixtureEvidence,
                                fixtureEvidence.liveRead()),
                        run.generation, read, appearanceFingerprints.get(fixture.getKey()), recordedAt);
                for (Map.Entry<Integer, FixtureVerification.Appearance> appearance
                        : new java.util.TreeMap<>(fixtureEvidence.appearances()).entrySet()) {
                    insertIncrementalReceipt(run.season,
                            IncrementalAppearanceReceipt.playerKey(fixture.getKey(), appearance.getKey(),
                                    appearance.getValue(), fixtureEvidence.liveRead()),
                            run.generation, read, null, recordedAt);
                }
            }
            return Set.copyOf(invalidatedFixtures);
        });
    }

    void recordIncrementalFixtureSourceRevisions(Lease lease,
            List<CatalogueScheduleParser.FixtureRow> fixtures, SourceRead fixtureRead) {
        Objects.requireNonNull(fixtures);
        Objects.requireNonNull(fixtureRead);
        tx.executeWithoutResult(status -> {
            LockedRun run = lockAndValidate(lease);
            requireIncrementalRun(run);
            requireReadNotFuture(fixtureRead, now());
            Map<Integer, String> sourceFingerprints = new LinkedHashMap<>();
            for (CatalogueScheduleParser.FixtureRow fixture : fixtures) {
                if (!run.season.equals(fixture.seasonCode())
                        || sourceFingerprints.putIfAbsent(fixture.id(),
                                IncrementalAppearanceReceipt.fixtureRevisionFingerprint(
                                        fixture, fixture.gameweek())) != null) {
                    throw new IllegalArgumentException("Invalid fixture-specific source revision set");
                }
            }
            if (sourceFingerprints.isEmpty()) return;
            List<Integer> orderedIds = sourceFingerprints.keySet().stream().sorted().toList();
            String placeholders = String.join(",", java.util.Collections.nCopies(orderedIds.size(), "?"));
            List<Object> parameters = new ArrayList<>(orderedIds.size() + 1);
            parameters.add(run.season);
            orderedIds.stream().map(IncrementalAppearanceReceipt::sourceFixtureKey).forEach(parameters::add);
            Map<Integer, String> existing = new HashMap<>();
            jdbc.query("SELECT publication_key,source_pair_fingerprint FROM " + tables.receipt()
                    + " WHERE season=? AND publication_key IN (" + placeholders + ")", rs -> {
                String key = rs.getString("publication_key");
                existing.put(Integer.parseInt(key.substring(
                        IncrementalAppearanceReceipt.SOURCE_FIXTURE_PREFIX.length())),
                        rs.getString("source_pair_fingerprint"));
            }, parameters.toArray());
            Instant recordedAt = now();
            for (int fixtureId : orderedIds) {
                if (sourceFingerprints.get(fixtureId).equals(existing.get(fixtureId))) continue;
                insertIncrementalSourceFixtureReceipt(run.season,
                        IncrementalAppearanceReceipt.sourceFixtureKey(fixtureId), run.generation,
                        fixtureRead, sourceFingerprints.get(fixtureId), recordedAt);
            }
        });
    }

    private void includeIncrementalSourceRead(LockedRun run, SourceRead read) {
        jdbc.update("""
                UPDATE %s SET
                    source_read_started_at=CASE WHEN source_read_started_at IS NULL THEN ?
                        ELSE LEAST(source_read_started_at, ?) END,
                    source_read_finished_at=CASE WHEN source_read_finished_at IS NULL THEN ?
                        ELSE GREATEST(source_read_finished_at, ?) END
                WHERE generation=? AND producer_key=?
                """.formatted(tables.run()), timestamp(read.startedAt()), timestamp(read.startedAt()),
                timestamp(read.finishedAt()), timestamp(read.finishedAt()), run.generation,
                INCREMENTAL_CORE_PRODUCER);
    }

    void includeIncrementalSourceRead(Lease lease, SourceRead read) {
        Objects.requireNonNull(read);
        tx.executeWithoutResult(status -> {
            LockedRun run = lockAndValidate(lease);
            requireIncrementalRun(run);
            requireReadNotFuture(read, now());
            includeIncrementalSourceRead(run, read);
        });
    }

    SourceRead incrementalSourceInterval(Lease lease) {
        return tx.execute(status -> {
            LockedRun run = lockAndValidate(lease);
            requireIncrementalRun(run);
            List<SourceRead> reads = jdbc.query("""
                    SELECT source_read_started_at, source_read_finished_at FROM %s WHERE generation=?
                    """.formatted(tables.run()), (rs, row) -> {
                Instant started = instant(rs, "source_read_started_at");
                Instant finished = instant(rs, "source_read_finished_at");
                return started == null || finished == null ? null : new SourceRead(started, finished);
            }, run.generation);
            if (reads.size() != 1 || reads.getFirst() == null) {
                throw new IllegalStateException("Incremental run has no durable successful source reads");
            }
            return reads.getFirst();
        });
    }

    private static String finishedFixtureReceiptKey(int fixtureId) {
        if (fixtureId <= 0) throw new IllegalArgumentException("fixture id must be positive");
        return FINISHED_FIXTURE_RECEIPT_PREFIX + fixtureId;
    }

    private static void requireIncrementalRun(LockedRun run) {
        if (run.kind() != SyncKind.PLAYER_HISTORY || !INCREMENTAL_CORE_PRODUCER.equals(run.producerKey())
                || !INCREMENTAL_CORE_KEY.equals(run.coalescingKey())
                || !INCREMENTAL_CORE_KEY.equals(run.publicationKey())
                || !"ACTIVE".equals(run.runtimeMode())) {
            throw new LeaseLostException("Lease does not own the incremental core-import runner");
        }
    }

    /** Executes a non-run runtime mutation while holding the durable activation fence. */
    public <T> T withinRuntimeFence(Supplier<T> callback) {
        Objects.requireNonNull(callback);
        if (runtimeFence == null) return callback.get();
        return tx.execute(status -> {
            assertRuntimeFence(runtimeFence.season());
            return callback.get();
        });
    }

    /** Runs one database mutation under the current runtime and run-lease fences. */
    <T> T withinRunLease(Lease lease, Supplier<T> callback) {
        Objects.requireNonNull(callback);
        return tx.execute(status -> {
            LockedRun run = lockAndValidate(lease);
            T result = callback.get();
            requireNotExpired(run, now());
            return result;
        });
    }

    <T> T withinIncrementalCoreLease(Lease lease, int seasonId, String seasonCode, Supplier<T> callback) {
        Objects.requireNonNull(callback);
        if (seasonId <= 0 || seasonCode == null || !lease.season().equals(seasonCode)) {
            throw new IllegalArgumentException("Invalid incremental finality season");
        }
        return tx.execute(status -> {
            LockedRun run = lockAndValidate(lease);
            requireIncrementalRun(run);
            requireIncrementalRuntime(seasonCode);
            lockCurrentPlayingSeason(seasonId, seasonCode);
            T result = callback.get();
            requireNotExpired(run, now());
            return result;
        });
    }

    void invalidateIncrementalGameweeks(Lease lease,
            Set<IncrementalPfsRepository.RowKey> changedKeys, SourceRead changedPageRead) {
        if (changedKeys.isEmpty()) return;
        tx.executeWithoutResult(status -> {
            LockedRun run = lockAndValidate(lease);
            if (!INCREMENTAL_CORE_PRODUCER.equals(run.producerKey())) return;
            requireIncrementalRun(run);
            Instant floor = postgresTimestampPrecision(changedPageRead.finishedAt());
            Set<Integer> gameweeks = new java.util.TreeSet<>();
            Integer seasonId = null;
            for (IncrementalPfsRepository.RowKey key : changedKeys) {
                if (key.seasonId() <= 0 || !run.season().equals(key.seasonCode()) || key.gameweek() <= 0
                        || seasonId != null && seasonId != key.seasonId()) {
                    throw new IllegalArgumentException("Changed player-fixture key is outside the leased season");
                }
                seasonId = key.seasonId();
                gameweeks.add(key.gameweek());
            }
            int activeSeasonId = Objects.requireNonNull(seasonId);
            for (int gameweek : gameweeks) {
                List<GameweekSyncRow> rows = jdbc.query("""
                        SELECT state, source_signature, first_checked_at, current_invalidation_revision,
                               current_invalidation_floor, finalized_source_signature,
                               finalized_invalidation_revision
                        FROM %s WHERE season_id=? AND gameweek=? FOR UPDATE
                        """.formatted(tables.gameweekStatSync()), (rs, row) -> new GameweekSyncRow(
                        rs.getString("state"), rs.getString("source_signature"), instant(rs, "first_checked_at"),
                        rs.getLong("current_invalidation_revision"), instant(rs, "current_invalidation_floor"),
                        rs.getString("finalized_source_signature"),
                        (Long) rs.getObject("finalized_invalidation_revision")), activeSeasonId, gameweek);
                if (rows.isEmpty()) continue;
                boolean checkedPage = changedKeys.stream().filter(key -> key.gameweek() == gameweek)
                        .allMatch(key -> isCurrentFinalAttemptPage(activeSeasonId, gameweek,
                                key.playerId(), changedPageRead));
                if (checkedPage) continue;
                Boolean finalPending = jdbc.queryForObject("SELECT event_finished IS TRUE AND data_checked IS TRUE "
                        + "AND fixture_manifest_complete AND all_assigned_fixtures_finished FROM "
                        + tables.gameweekStatSync() + " WHERE season_id=? AND gameweek=?",
                        Boolean.class, activeSeasonId, gameweek);
                String nextState = Boolean.TRUE.equals(finalPending) ? "FINAL_PENDING" : "OPEN";
                jdbc.update("""
                        UPDATE %s SET state=?, current_invalidation_revision=current_invalidation_revision+1,
                            current_invalidation_floor=CASE WHEN current_invalidation_floor IS NULL THEN ?
                                ELSE GREATEST(current_invalidation_floor, ?) END,
                            finalized_source_signature=NULL, finalized_population_fingerprint=NULL,
                            finalized_row_revision_fingerprint=NULL, finalized_source_floor=NULL,
                            finalized_validation_read_started_at=NULL, finalized_validation_read_finished_at=NULL,
                            finalized_source_read_started_at=NULL, finalized_source_read_finished_at=NULL,
                            finalized_invalidation_revision=NULL, finalized_run_generation=NULL,
                            finalized_page_evidence=NULL, finalized_at=NULL,
                            finalized_mandatory_manifest_complete=NULL, finalized_optional_component_coverage=NULL,
                            updated_at=?
                        WHERE season_id=? AND gameweek=?
                        """.formatted(tables.gameweekStatSync()), nextState, timestamp(floor), timestamp(floor),
                        timestamp(now()), activeSeasonId, gameweek);
            }
        });
    }

    private boolean isCurrentFinalAttemptPage(int seasonId, int gameweek, int playerId, SourceRead read) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM %s
                    WHERE season_id=? AND gameweek=? AND state='FINAL_PENDING'
                      AND event_finished IS TRUE AND data_checked IS TRUE
                      AND final_attempt_source_signature=source_signature
                      AND final_attempt_invalidation_revision=current_invalidation_revision
                      AND final_attempt_source_floor<=? AND ?=ANY(final_attempt_required_player_ids))
                """.formatted(tables.gameweekStatSync()), Boolean.class, seasonId, gameweek,
                timestamp(read.startedAt()), playerId));
    }

    /**
     * Publishes one already-parsed player page without completing its run or advancing a fixture
     * receipt. The page must still be owned by the current ACTIVE PLAYER_HISTORY lease, match the
     * locked database playing season and meet its source-demand/page watermark; all writes share
     * this short transaction, including the dedicated incremental-page receipt for empty pages.
     */
    <T> T publishPlayerHistoryPage(Lease lease, int playerId, int seasonId, String seasonCode,
                                   SourceRead read, Supplier<T> publication) {
        Objects.requireNonNull(lease);
        Objects.requireNonNull(seasonCode);
        Objects.requireNonNull(read);
        Objects.requireNonNull(publication);
        if (playerId <= 0 || seasonId <= 0 || !lease.season().equals(seasonCode)) {
            throw new IllegalArgumentException("Player page must identify the leased season and player");
        }
        String expectedKey = HistoryFixtureDispatcher.playerHistoryPublicationKey(playerId);
        String pageReceiptKey = IncrementalPlayerFixturePublisher.pageReceiptKey(playerId);
        return tx.execute(status -> {
            LockedRun run = lockAndValidate(lease);
            boolean legacyPage = "LEGACY".equals(run.producerKey)
                    && expectedKey.equals(run.publicationKey);
            boolean incrementalPage = INCREMENTAL_CORE_PRODUCER.equals(run.producerKey)
                    && INCREMENTAL_CORE_KEY.equals(run.coalescingKey)
                    && INCREMENTAL_CORE_KEY.equals(run.publicationKey)
                    && "ACTIVE".equals(run.runtimeMode);
            if (run.kind != SyncKind.PLAYER_HISTORY || lease.kind() != run.kind
                    || !lease.season().equals(run.season)
                    || !lease.coalescingKey().equals(run.coalescingKey)
                    || !lease.publicationKey().equals(run.publicationKey)
                    || !(legacyPage || incrementalPage)) {
                throw new LeaseLostException("Lease does not own this player-history page");
            }
            Instant requestedAfter = jdbc.queryForObject(
                    "SELECT requested_source_after FROM %s WHERE generation = ?"
                            .formatted(tables.run()), Timestamp.class, lease.generation()).toInstant();
            if (read.startedAt().isBefore(requestedAfter)) {
                throw new SourceDemandAdvancedException(lease.generation(), requestedAfter);
            }
            if (runtimeFence == null || runtimeFence.mode() != OwnedSyncMode.ACTIVE
                    || !runtimeFence.season().equals(seasonCode)) {
                throw new RuntimeFenceLostException("Player-history publication requires its ACTIVE season fence");
            }
            lockCurrentPlayingSeason(seasonId, seasonCode);
            Instant publishingAt = now();
            requireNotExpired(run, publishingAt);
            requireReadNotFuture(read, publishingAt);
            jdbc.update("INSERT INTO %s(season, publication_key) VALUES (?, ?) ON CONFLICT DO NOTHING"
                    .formatted(tables.receipt()), run.season, pageReceiptKey);
            PageWatermark watermark = lockPlayerPageWatermark(run.season, pageReceiptKey);
            verifyPlayerPageOrder(lease.generation(), read, watermark, expectedKey);
            T result = publication.get();
            if (INCREMENTAL_CORE_PRODUCER.equals(run.producerKey)) includeIncrementalSourceRead(run, read);
            Instant publishedAt = now();
            requireNotExpired(run, publishedAt);
            int receiptUpdated = jdbc.update("""
                    UPDATE %s SET latest_generation=?, source_read_started_at=?,
                        source_read_finished_at=?, published_at=?
                    WHERE season=? AND publication_key=?
                    """.formatted(tables.receipt()), lease.generation(), timestamp(read.startedAt()),
                    timestamp(read.finishedAt()), timestamp(publishedAt), run.season, pageReceiptKey);
            if (receiptUpdated != 1) {
                throw new IllegalStateException("Player-history receipt disappeared during page publication");
            }
            return result;
        });
    }

    private void lockCurrentPlayingSeason(int seasonId, String seasonCode) {
        List<SeasonIdentity> current = jdbc.query("""
                SELECT id, code FROM season WHERE is_current=true ORDER BY id FOR UPDATE
                """, (rs, row) -> new SeasonIdentity(rs.getInt("id"), rs.getString("code")));
        if (current.size() != 1 || current.getFirst().id() != seasonId
                || !seasonCode.equals(current.getFirst().code())
                || !runtimeFence.season().equals(current.getFirst().code())) {
            throw new RuntimeFenceLostException("Player-history season is not the locked database playing season");
        }
    }

    private PageWatermark lockPlayerPageWatermark(String season, String pageReceiptKey) {
        List<PageWatermark> rows = jdbc.query("""
                SELECT receipt.latest_generation, receipt.source_read_started_at,
                       receipt.source_read_finished_at, run.requested_source_after,
                       run.producer_key, run.coalescing_key, run.publication_key
                FROM %s receipt
                LEFT JOIN %s run ON run.generation=receipt.latest_generation
                    AND run.season=receipt.season AND run.kind='PLAYER_HISTORY'
                WHERE receipt.season=? AND receipt.publication_key=? FOR UPDATE OF receipt
                """.formatted(tables.receipt(), tables.run()), (rs, row) -> new PageWatermark(
                rs.getObject("latest_generation", Long.class), instant(rs, "source_read_started_at"),
                instant(rs, "source_read_finished_at"), instant(rs, "requested_source_after"),
                rs.getString("producer_key"), rs.getString("coalescing_key"),
                rs.getString("publication_key")), season, pageReceiptKey);
        if (rows.size() != 1) {
            throw new IllegalStateException("Player-history receipt disappeared before page publication");
        }
        return rows.getFirst();
    }

    private static void verifyPlayerPageOrder(long generation, SourceRead read, PageWatermark watermark,
                                               String expectedLegacyKey) {
        if (watermark.generation() == null) {
            if (watermark.startedAt() != null || watermark.finishedAt() != null || watermark.sourceFloor() != null
                    || watermark.producerKey() != null || watermark.coalescingKey() != null
                    || watermark.publicationKey() != null) {
                throw new IllegalStateException("Player-history receipt has an incomplete page watermark");
            }
            return;
        }
        boolean legacy = "LEGACY".equals(watermark.producerKey())
                && expectedLegacyKey.equals(watermark.publicationKey());
        boolean incremental = INCREMENTAL_CORE_PRODUCER.equals(watermark.producerKey())
                && INCREMENTAL_CORE_KEY.equals(watermark.coalescingKey())
                && INCREMENTAL_CORE_KEY.equals(watermark.publicationKey());
        if (!legacy && !incremental) {
            throw new IllegalStateException("Player-history watermark generation has an unknown producer");
        }
        if (watermark.startedAt() == null || watermark.finishedAt() == null || watermark.sourceFloor() == null) {
            throw new IllegalStateException("Player-history receipt has an incomplete page watermark");
        }
        if (generation < watermark.generation()) {
            throw new IncrementalPfsRepository.SourceOrderConflictException(
                    "Older player-history generation cannot replace the latest completed page, including an empty page");
        }
        if (generation == watermark.generation()) {
            if (!read.startedAt().equals(watermark.startedAt())
                    || !read.finishedAt().equals(watermark.finishedAt())) {
                throw new IncrementalPfsRepository.SourceOrderConflictException(
                        "One player-history generation cannot publish conflicting page intervals");
            }
            return;
        }
        if (read.startedAt().isBefore(watermark.finishedAt())
                || read.startedAt().isBefore(watermark.sourceFloor())) {
            throw new IncrementalPfsRepository.SourceOrderConflictException(
                    "Player-history page predates the latest source watermark or its source floor");
        }
    }

    /** Returns a fixture's exact, reusable dependency manifest and missing histories without reading bodies. */
    Optional<FixtureDependencyManifest> fixtureDependencyManifest(Lease lease, int fixtureId) {
        Objects.requireNonNull(lease);
        if (fixtureId <= 0) throw new IllegalArgumentException("fixture id must be positive");
        return tx.execute(status -> {
            lockAndValidate(lease);
            return fixtureDependencyManifestLocked(lease, fixtureId);
        });
    }

    private Optional<FixtureDependencyManifest> fixtureDependencyManifestLocked(Lease lease, int fixtureId) {
        Instant checkedAt = now();
        String sql = """
                SELECT m.run_generation, m.bootstrap_payload_id, m.fixtures_payload_id, m.live_payload_id,
                       m.required_player_ids, m.source_floor, r.requested_source_after, l.event_id,
                       b.read_started_at AS b_started, b.read_finished_at AS b_finished,
                       f.read_started_at AS f_started, f.read_finished_at AS f_finished,
                       l.read_started_at AS l_started, l.read_finished_at AS l_finished,
                       GREATEST(r.requested_source_after, m.source_floor,
                           f.read_finished_at + INTERVAL '1 millisecond') AS effective_floor,
                       ARRAY(SELECT required.player_id FROM unnest(m.required_player_ids)
                                 AS required(player_id)
                             WHERE %s ORDER BY required.player_id) AS missing_player_ids
                FROM %s r
                JOIN %s m ON m.run_generation = r.generation
                JOIN %s b ON b.id = m.bootstrap_payload_id AND b.run_generation = r.generation
                JOIN %s f ON f.id = m.fixtures_payload_id AND f.run_generation = r.generation
                JOIN %s l ON l.id = m.live_payload_id AND l.run_generation = r.generation
                WHERE r.generation = ? AND r.kind = 'FIXTURE_VERIFICATION'
                  AND r.season = ? AND r.runtime_mode = ? AND r.runtime_epoch = ?
                  AND r.coalescing_key = ? AND r.publication_key = ?
                  AND m.source_floor = r.requested_source_after
                  AND r.requested_source_after <= ?
                  AND GREATEST(r.requested_source_after, m.source_floor,
                      f.read_finished_at + INTERVAL '1 millisecond') <= ?
                  AND %s
                """.formatted(missingHistoryEvidencePredicate("r", "required.player_id"),
                tables.run(), tables.fixtureDependencyManifest(), tables.payload(), tables.payload(),
                tables.payload(), fixtureManifestAnchorConditions("r"));
        return jdbc.query(sql, (rs, row) -> {
            Integer[] required = (Integer[]) rs.getArray("required_player_ids").getArray();
            Integer[] missing = (Integer[]) rs.getArray("missing_player_ids").getArray();
            SourceRead bootstrapRead = new SourceRead(instant(rs, "b_started"), instant(rs, "b_finished"));
            SourceRead fixturesRead = new SourceRead(instant(rs, "f_started"), instant(rs, "f_finished"));
            SourceRead liveRead = new SourceRead(instant(rs, "l_started"), instant(rs, "l_finished"));
            long generation = rs.getLong("run_generation");
            return new FixtureDependencyManifest(generation, lease.season(), runtimeMode(), runtimeEpoch(),
                    fixtureId, rs.getInt("event_id"), rs.getLong("bootstrap_payload_id"),
                    rs.getLong("fixtures_payload_id"), rs.getLong("live_payload_id"),
                    List.of(required), List.of(missing), rs.getTimestamp("source_floor").toInstant(),
                    rs.getTimestamp("requested_source_after").toInstant(),
                    rs.getTimestamp("effective_floor").toInstant(),
                    new StagedPayloadMetadata(rs.getLong("bootstrap_payload_id"), generation,
                            Endpoint.bootstrap(), bootstrapRead, ValidationState.VALID),
                    new StagedPayloadMetadata(rs.getLong("fixtures_payload_id"), generation,
                            Endpoint.fixtures(), fixturesRead, ValidationState.VALID),
                    new StagedPayloadMetadata(rs.getLong("live_payload_id"), generation,
                            Endpoint.eventLive(rs.getInt("event_id")), liveRead, ValidationState.VALID));
        }, lease.generation(), lease.season(), runtimeMode(), runtimeEpoch(),
                FixtureSyncPublisher.publicationKey(fixtureId), FixtureSyncPublisher.publicationKey(fixtureId),
                timestamp(checkedAt), timestamp(checkedAt)).stream().findFirst();
    }

    /** Persists a metadata-only dependency retry, atomically refreshing only currently missing histories. */
    boolean retryForDependency(Lease lease, FixtureDependencyManifest expected, Instant dueAt,
                               String error, Supplier<Instant> retryMutation) {
        Objects.requireNonNull(lease);
        Objects.requireNonNull(expected);
        Objects.requireNonNull(dueAt);
        return tx.execute(status -> {
            LockedRun run = lockAndValidate(lease);
            Optional<FixtureDependencyManifest> current = fixtureDependencyManifestLocked(lease,
                    expected.fixtureId());
            if (current.isEmpty() || !expected.sameAnchorsAndDemand(current.get())
                    || current.get().missingPlayerIds().isEmpty()) return false;
            FixtureDependencyManifest manifest = current.get();
            for (int playerId : manifest.missingPlayerIds()) {
                ensurePlayerHistoryAtLeast(run.season, playerId, manifest.effectiveFloor());
            }
            Instant callbackDueAt = retryMutation == null ? dueAt
                    : Objects.requireNonNull(retryMutation.get(), "retry mutation due time");
            Instant selectedDueAt = dueAt.isAfter(callbackDueAt) ? dueAt : callbackDueAt;
            Instant requeuedAt = now();
            requireNotExpired(run, requeuedAt);
            int released = jdbc.update("""
                    UPDATE %s SET state = 'PENDING', priority = ?, due_at = ?, last_error = ?,
                        failed_at = NULL, lease_owner = NULL, lease_token = NULL,
                        lease_expires_at = NULL, updated_at = ?
                    WHERE generation = ? AND state = 'LEASED' AND lease_owner = ?
                      AND lease_token = ? AND lease_fence = ?
                    """.formatted(tables.run()), SyncKind.DEPENDENCY_WAITING_PRIORITY,
                    timestamp(selectedDueAt), boundedNullable(error, MAX_ERROR_CHARS), timestamp(requeuedAt),
                    run.generation, lease.owner(), lease.token(), lease.fence());
            if (released != 1) throw new LeaseLostException("Lease is stale for generation " + run.generation);
            return true;
        });
    }

    /** Returns due fixture runs whose durable dependency evidence is complete, without loading bodies. */
    List<Run> readyFixtureGenerations(String season, int limit) {
        String validSeason = bounded("season", season, 10);
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        Instant checkedAt = now();
        return tx.execute(status -> {
            assertRuntimeFence(validSeason);
            String sql = """
                    SELECT r.generation, r.kind, r.season, r.coalescing_key,
                           r.publication_key, r.due_at, r.state, r.attempt_count
                    FROM %s r
                    WHERE r.kind = 'FIXTURE_VERIFICATION'
                      AND r.season = ? AND r.runtime_mode = ? AND r.runtime_epoch = ?
                      AND r.due_at <= ?
                      AND (r.state = 'PENDING'
                           OR (r.state = 'LEASED' AND r.lease_expires_at <= ?))
                      AND %s
                    ORDER BY r.due_at, r.generation
                    LIMIT ?
                    """.formatted(tables.run(), fixtureReadinessPredicate());
            return jdbc.query(sql, this::run, validSeason, runtimeMode(), runtimeEpoch(),
                    timestamp(checkedAt), timestamp(checkedAt), limit);
        });
    }

    /** Claims one due run. Priority, due time and generation make selection deterministic. */
    public Optional<Lease> acquire(String owner, Duration duration) {
        return acquire(owner, duration, null);
    }

    /** Claims one due run for an explicitly configured season without consuming archived backlog. */
    public Optional<Lease> acquireForSeason(String season, String owner, Duration duration) {
        return acquire(owner, duration, bounded("season", season, 10));
    }

    /**
     * Claims one run using a durable, cross-instance rotation for the owned matchday dispatcher.
     * Canonical catalogue/schedule work preempts the rotation; non-canonical turns are derived
     * from all retained attempts in this season's current runtime epoch.
     */
    public Optional<Lease> acquireFairForSeason(String season, String owner, Duration duration) {
        if (runtimeFence == null) {
            throw new IllegalStateException("Fair acquisition requires an activated runtime fence");
        }
        String validSeason = bounded("season", season, 10);
        String validOwner = bounded("owner", owner, 128);
        Duration validDuration = leaseDuration(duration);
        return tx.execute(status -> {
            // This existing mode-row lock serializes the aggregate and claim across all store instances.
            assertRuntimeFence(validSeason);
            Instant checkedAt = now();
            // The two exact canonical keys plus uq_active_work prove at most two active candidates.
            Long generation = fairCandidate(validSeason, checkedAt, "r.kind='CATALOGUE_SCHEDULE' AND "
                    + "((r.coalescing_key=? AND r.publication_key=?) OR "
                    + "(r.coalescing_key=? AND r.publication_key=?))",
                    CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                    CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY,
                    CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                    CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY);

            if (generation == null) {
                long attempts = jdbc.queryForObject("""
                        SELECT COALESCE(SUM(attempt_count), 0)
                        FROM %s
                        WHERE season=? AND producer_key='LEGACY' AND runtime_mode=? AND runtime_epoch=?
                          AND kind IN ('PLAYER_HISTORY', 'FIXTURE_VERIFICATION')
                        """.formatted(tables.run()), Long.class, validSeason, runtimeMode(), runtimeEpoch());
                int firstTurn = (int) (attempts % FAIR_CYCLE.size());
                Map<ClaimClass, Integer> borrowingOrder = new LinkedHashMap<>();
                for (int offset = 0; offset < FAIR_CYCLE.size(); offset++) {
                    int cycleTurn = (firstTurn + offset) % FAIR_CYCLE.size();
                    borrowingOrder.putIfAbsent(FAIR_CYCLE.get(cycleTurn), cycleTurn);
                }
                for (Map.Entry<ClaimClass, Integer> entry : borrowingOrder.entrySet()) {
                    ClaimClass claimClass = entry.getKey();
                    int cycleTurn = entry.getValue();
                    if (claimClass == ClaimClass.HISTORY
                            && PREFERENTIAL_HISTORY_TURNS.contains(cycleTurn)) {
                        generation = fairDemandedHistoryCandidate(validSeason, checkedAt);
                    }
                    if (generation == null) {
                        generation = claimClass == ClaimClass.READY_FIXTURE
                                ? fairCandidateOrdered(validSeason, checkedAt, claimClass.predicate(this),
                                        "r.due_at, r.generation")
                                : fairCandidate(validSeason, checkedAt, claimClass.predicate(this));
                    }
                    if (generation != null) break;
                }
                // Preserve the coordinator's existing failure handling for unsupported work.
                if (generation == null) {
                    generation = fairCandidate(validSeason, checkedAt, "r.kind='SOURCE_STATUS'");
                }
            }
            if (generation == null) return Optional.empty();
            return Optional.of(leaseGeneration(generation, validOwner, validDuration, checkedAt));
        });
    }

    private Long fairCandidate(String season, Instant checkedAt, String predicate, Object... predicateValues) {
        return fairCandidateOrdered(season, checkedAt, predicate,
                "r.priority, r.due_at, r.generation", predicateValues);
    }

    /** Prefer one missing history from the oldest coherent active fixture manifest. */
    private Long fairDemandedHistoryCandidate(String season, Instant checkedAt) {
        String sql = """
                SELECT h.generation
                FROM %s fr
                JOIN %s m ON m.run_generation = fr.generation
                JOIN %s b ON b.id = m.bootstrap_payload_id AND b.run_generation = fr.generation
                JOIN %s f ON f.id = m.fixtures_payload_id AND f.run_generation = fr.generation
                JOIN %s l ON l.id = m.live_payload_id AND l.run_generation = fr.generation
                CROSS JOIN LATERAL unnest(m.required_player_ids) AS required(player_id)
                JOIN %s h ON h.coalescing_key = 'player-history:' || required.player_id::text
                         AND h.publication_key = 'player-history:' || required.player_id::text
                         AND h.producer_key='LEGACY'
                WHERE fr.season = ? AND fr.runtime_mode = ? AND fr.runtime_epoch = ?
                  AND fr.producer_key='LEGACY'
                  AND fr.state IN ('PENDING', 'LEASED')
                  AND GREATEST(fr.requested_source_after, m.source_floor,
                      f.read_finished_at + INTERVAL '1 millisecond') <= ?
                  AND %s AND %s
                  AND h.kind = 'PLAYER_HISTORY'
                  AND h.season = ? AND h.runtime_mode = ? AND h.runtime_epoch = ?
                  AND h.due_at <= ? AND h.requested_source_after <= ?
                  AND (h.state = 'PENDING' OR (h.state = 'LEASED' AND h.lease_expires_at <= ?))
                ORDER BY fr.generation, h.priority, h.due_at, h.generation
                LIMIT 1 FOR UPDATE OF h SKIP LOCKED
                """.formatted(tables.run(), tables.fixtureDependencyManifest(),
                tables.payload(), tables.payload(), tables.payload(), tables.run(),
                fixtureManifestAnchorConditions("fr"),
                missingHistoryEvidencePredicate("fr", "required.player_id"));
        return jdbc.queryForList(sql, Long.class, season, runtimeMode(), runtimeEpoch(),
                timestamp(checkedAt), season, runtimeMode(), runtimeEpoch(),
                timestamp(checkedAt), timestamp(checkedAt), timestamp(checkedAt))
                .stream().findFirst().orElse(null);
    }

    private Long fairCandidateOrdered(String season, Instant checkedAt, String predicate,
                                      String orderBy, Object... predicateValues) {
        String sql = """
                SELECT r.generation
                FROM %s r
                WHERE r.season=? AND r.producer_key='LEGACY' AND r.runtime_mode=? AND r.runtime_epoch=?
                  AND r.due_at<=? AND r.requested_source_after<=?
                  AND (r.state='PENDING' OR (r.state='LEASED' AND r.lease_expires_at<=?))
                  AND (%s)
                ORDER BY %s
                LIMIT 1 FOR UPDATE OF r SKIP LOCKED
                """.formatted(tables.run(), predicate, orderBy);
        List<Object> parameters = new ArrayList<>(List.of(season, runtimeMode(), runtimeEpoch(),
                timestamp(checkedAt), timestamp(checkedAt), timestamp(checkedAt)));
        parameters.addAll(List.of(predicateValues));
        return jdbc.queryForList(sql, Long.class, parameters.toArray()).stream().findFirst().orElse(null);
    }

    private Lease leaseGeneration(long generation, String owner, Duration duration, Instant claimedAt) {
        UUID token = UUID.randomUUID();
        Instant expiresAt = claimedAt.plus(duration);
        jdbc.update("""
                UPDATE %s SET state='LEASED', attempt_count=attempt_count + 1,
                    last_attempt_at=?, lease_owner=?, lease_token=?, lease_fence=lease_fence + 1,
                    lease_expires_at=?, updated_at=?
                WHERE generation=?
                """.formatted(tables.run()), timestamp(claimedAt), owner, token,
                timestamp(expiresAt), timestamp(claimedAt), generation);
        return jdbc.queryForObject("""
                SELECT generation, kind, season, coalescing_key, publication_key,
                       lease_owner, lease_token, lease_fence, lease_expires_at, attempt_count
                FROM %s WHERE generation=?
                """.formatted(tables.run()), this::lease, generation);
    }

    private Optional<Lease> acquire(String owner, Duration duration, String season) {
        String validOwner = bounded("owner", owner, 128);
        Duration validDuration = leaseDuration(duration);
        Instant now = now();
        return tx.execute(status -> {
            if (runtimeFence != null) assertRuntimeFence(runtimeFence.season());
            Long generation;
            try {
                String seasonFilter = season == null ? "" : " AND season = ?";
                String fenceFilter = runtimeFence == null
                        ? " AND runtime_mode = 'OFF' AND runtime_epoch = 0"
                        : " AND runtime_mode = ? AND runtime_epoch = ?";
                String sql = """
                        SELECT generation FROM %s
                        WHERE producer_key='LEGACY' AND due_at <= ?%s%s
                          AND (state = 'PENDING'
                               OR (state = 'LEASED' AND lease_expires_at <= ?))
                        ORDER BY priority, due_at, generation
                        LIMIT 1 FOR UPDATE SKIP LOCKED
                        """.formatted(tables.run(), seasonFilter, fenceFilter);
                if (runtimeFence == null) {
                    generation = season == null
                            ? jdbc.queryForObject(sql, Long.class, timestamp(now), timestamp(now))
                            : jdbc.queryForObject(sql, Long.class, timestamp(now), season, timestamp(now));
                } else {
                    generation = season == null
                            ? jdbc.queryForObject(sql, Long.class, timestamp(now), runtimeMode(), runtimeEpoch(), timestamp(now))
                            : jdbc.queryForObject(sql, Long.class, timestamp(now), season,
                                    runtimeMode(), runtimeEpoch(), timestamp(now));
                }
            } catch (EmptyResultDataAccessException e) {
                return Optional.empty();
            }
            UUID token = UUID.randomUUID();
            Instant expiresAt = now.plus(validDuration);
            jdbc.update("""
                    UPDATE %s SET state = 'LEASED', attempt_count = attempt_count + 1,
                        last_attempt_at = ?, lease_owner = ?, lease_token = ?,
                        lease_fence = lease_fence + 1, lease_expires_at = ?, updated_at = ?
                    WHERE generation = ?
                    """.formatted(tables.run()), timestamp(now), validOwner, token,
                    timestamp(expiresAt), timestamp(now), generation);
            return Optional.of(jdbc.queryForObject("""
                    SELECT generation, kind, season, coalescing_key, publication_key,
                           lease_owner, lease_token, lease_fence, lease_expires_at, attempt_count
                    FROM %s WHERE generation = ?
                    """.formatted(tables.run()), this::lease, generation));
        });
    }

    public Lease renew(Lease lease, Duration duration) {
        Objects.requireNonNull(lease);
        Duration validDuration = leaseDuration(duration);
        return tx.execute(status -> {
            LockedRun run = lockAndValidate(lease);
            Instant renewedAt = now();
            Instant expiresAt = renewedAt.plus(validDuration);
            jdbc.update("UPDATE %s SET lease_expires_at = ?, updated_at = ? WHERE generation = ?"
                    .formatted(tables.run()), timestamp(expiresAt), timestamp(renewedAt), lease.generation());
            return new Lease(run.generation, run.kind, run.season, run.coalescingKey,
                    run.publicationKey, lease.owner(), lease.token(), lease.fence(), expiresAt,
                    run.attemptCount);
        });
    }

    /**
     * Fences a caller immediately before HTTP without holding a transaction over the request.
     * The returned expiry is read from the locked durable lease, so a still-current pre-renewal
     * lease value is accepted; callers must renew separately when needed before network I/O.
     */
    public HttpFenceResult httpFence(Lease lease, Endpoint endpoint, Instant endpointFloor) {
        Objects.requireNonNull(lease);
        Objects.requireNonNull(endpoint);
        Objects.requireNonNull(endpointFloor);
        if (runtimeFence == null) {
            throw new RuntimeFenceLostException("HTTP fencing requires a configured owned-sync runtime");
        }
        validateEndpoint(endpoint);
        return tx.execute(status -> {
            LockedRun run = lockAndValidate(lease);
            if (!lease.season().equals(run.season)
                    || lease.kind() != run.kind
                    || !lease.coalescingKey().equals(run.coalescingKey)
                    || !lease.publicationKey().equals(run.publicationKey)) {
                throw new LeaseLostException("Lease identity no longer matches generation " + lease.generation());
            }
            if (runtimeFence != null && !runtimeFence.season().equals(run.season)) {
                throw new RuntimeFenceLostException("Run belongs to another configured season");
            }
            if (INCREMENTAL_CORE_PRODUCER.equals(run.producerKey)
                    && (runtimeFence == null || runtimeFence.mode() != OwnedSyncMode.ACTIVE)) {
                throw new RuntimeFenceLostException("Incremental core import HTTP requires ACTIVE mode");
            }
            requireHttpEndpoint(run, endpoint);
            Instant sourceFloor = effectiveSourceFloor(lease, endpointFloor);
            Instant checkedAt = now();
            requireNotExpired(run, checkedAt);
            if (sourceFloor.isAfter(checkedAt)) return new FutureFloor(sourceFloor);
            return new Ready(run.expiresAt, sourceFloor);
        });
    }

    /** Stores arbitrary (including malformed) source bytes, provided the bounded lease is current. */
    public long stage(Lease lease, Endpoint endpoint, SourceRead read, Validation validation, byte[] body) {
        Objects.requireNonNull(endpoint);
        Objects.requireNonNull(read);
        Objects.requireNonNull(validation);
        Objects.requireNonNull(body);
        if (body.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
        }
        validateEndpoint(endpoint);
        return tx.execute(status -> {
            lockAndValidate(lease);
            Instant stagedAt = now();
            requireReadNotFuture(read, stagedAt);
            Long retainedBytes = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(octet_length(body)), 0) FROM %s WHERE run_generation = ?
                    """.formatted(tables.payload()), Long.class, lease.generation());
            if (retainedBytes == null || retainedBytes + body.length > MAX_RETAINED_PAYLOAD_BYTES_PER_RUN) {
                throw new PayloadRetentionLimitException(lease.generation(), retainedBytes == null ? 0 : retainedBytes,
                        body.length);
            }
            Long id = jdbc.queryForObject("""
                    INSERT INTO %s(run_generation, endpoint_kind, event_id, player_id,
                                   read_started_at, read_finished_at, validation_state,
                                   validation_error, body, staged_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """.formatted(tables.payload()), Long.class, lease.generation(),
                    endpoint.kind().name(), endpoint.eventId(), endpoint.playerId(),
                    timestamp(read.startedAt()), timestamp(read.finishedAt()), validation.state().name(),
                    boundedNullable(validation.error(), MAX_ERROR_CHARS), body, timestamp(stagedAt));
            jdbc.update("""
                    UPDATE %s SET
                        source_read_started_at = LEAST(COALESCE(source_read_started_at, ?), ?),
                        source_read_finished_at = GREATEST(COALESCE(source_read_finished_at, ?), ?),
                        updated_at = ?
                    WHERE generation = ?
                    """.formatted(tables.run()), timestamp(read.startedAt()), timestamp(read.startedAt()),
                    timestamp(read.finishedAt()), timestamp(read.finishedAt()), timestamp(stagedAt), lease.generation());
            return id;
        });
    }

    /** Returns the durable source-read floor while proving the supplied lease is still current. */
    public Instant requestedSourceAfter(Lease lease) {
        return tx.execute(status -> {
            lockAndValidate(lease);
            return jdbc.queryForObject("SELECT requested_source_after FROM %s WHERE generation = ?"
                    .formatted(tables.run()), Timestamp.class, lease.generation()).toInstant();
        });
    }

    /** Returns the latest staged public response for this run while proving the lease is current. */
    public Optional<StagedPayload> latestStagedPublicPayload(Lease lease, Endpoint endpoint) {
        requirePublicEndpoint(endpoint);
        return latestStagedPayload(lease, endpoint);
    }

    /** Returns only an exact endpoint match; event/player ids can never alias another payload. */
    public Optional<StagedPayload> latestStagedPayload(Lease lease, Endpoint endpoint) {
        Objects.requireNonNull(endpoint);
        validateEndpoint(endpoint);
        return tx.execute(status -> {
            lockAndValidate(lease);
            return jdbc.query("""
                    SELECT id, run_generation, endpoint_kind, read_started_at, read_finished_at,
                           validation_state, validation_error, body
                    FROM %s WHERE run_generation = ? AND endpoint_kind = ?
                      AND event_id IS NOT DISTINCT FROM ? AND player_id IS NOT DISTINCT FROM ?
                    ORDER BY id DESC LIMIT 1
                    """.formatted(tables.payload()), this::stagedPayload, lease.generation(),
                    endpoint.kind().name(), endpoint.eventId(), endpoint.playerId()).stream().findFirst();
        });
    }

    /** Returns the latest exact endpoint payload in one validation state. */
    public Optional<StagedPayload> latestStagedPayload(Lease lease, Endpoint endpoint,
                                                        ValidationState state) {
        Objects.requireNonNull(endpoint);
        Objects.requireNonNull(state);
        validateEndpoint(endpoint);
        return tx.execute(status -> {
            lockAndValidate(lease);
            return jdbc.query("""
                    SELECT id, run_generation, endpoint_kind, read_started_at, read_finished_at,
                           validation_state, validation_error, body
                    FROM %s WHERE run_generation = ? AND endpoint_kind = ?
                      AND event_id IS NOT DISTINCT FROM ? AND player_id IS NOT DISTINCT FROM ?
                      AND validation_state = ? ORDER BY id DESC LIMIT 1
                    """.formatted(tables.payload()), this::stagedPayload, lease.generation(),
                    endpoint.kind().name(), endpoint.eventId(), endpoint.playerId(), state.name())
                    .stream().findFirst();
        });
    }

    /**
     * Earliest validated response this run staged at or after a floor. Reuse deliberately prefers
     * the oldest usable read: the newest one is the post-fan-out recheck, and anchoring a later
     * attempt to it would invalidate every player history already fetched against the older read.
     */
    public Optional<StagedPayload> earliestValidStagedPayload(Lease lease, Endpoint endpoint,
                                                              Instant floor) {
        Objects.requireNonNull(endpoint);
        Objects.requireNonNull(floor);
        validateEndpoint(endpoint);
        return tx.execute(status -> {
            lockAndValidate(lease);
            return jdbc.query("""
                    SELECT id, run_generation, endpoint_kind, read_started_at, read_finished_at,
                           validation_state, validation_error, body
                    FROM %s WHERE run_generation = ? AND endpoint_kind = ?
                      AND event_id IS NOT DISTINCT FROM ? AND player_id IS NOT DISTINCT FROM ?
                      AND validation_state = 'VALID' AND read_started_at >= ?
                    ORDER BY read_started_at ASC, id ASC LIMIT 1
                    """.formatted(tables.payload()), this::stagedPayload, lease.generation(),
                    endpoint.kind().name(), endpoint.eventId(), endpoint.playerId(),
                    timestamp(floor)).stream().findFirst();
        });
    }

    /** Latest reusable validated response for one exact endpoint, season and source-read floor. */
    public Optional<StagedPayload> latestValidPayload(String season, Endpoint endpoint,
                                                       Instant requestedSourceAfter) {
        String validSeason = bounded("season", season, 10);
        Objects.requireNonNull(endpoint);
        Objects.requireNonNull(requestedSourceAfter);
        validateEndpoint(endpoint);
        return jdbc.query("""
                SELECT p.id, p.run_generation, p.endpoint_kind, p.read_started_at,
                       p.read_finished_at, p.validation_state, p.validation_error, p.body
                FROM %s p JOIN %s r ON r.generation=p.run_generation
                WHERE r.season=? AND p.endpoint_kind=?
                  AND p.event_id IS NOT DISTINCT FROM ? AND p.player_id IS NOT DISTINCT FROM ?
                  AND p.validation_state='VALID' AND p.read_started_at >= ?
                  AND (? = FALSE OR (r.runtime_mode=? AND r.runtime_epoch=?))
                ORDER BY p.read_finished_at DESC, p.id DESC LIMIT 1
                """.formatted(tables.payload(), tables.run()), this::stagedPayload, validSeason,
                endpoint.kind().name(), endpoint.eventId(), endpoint.playerId(),
                timestamp(requestedSourceAfter), runtimeFence != null,
                runtimeMode(), runtimeEpoch()).stream().findFirst();
    }

    /** Selects one exact staged payload without transferring its body. */
    Optional<StagedPayloadMetadata> latestStagedPayloadMetadata(Lease lease, Endpoint endpoint) {
        Objects.requireNonNull(endpoint);
        validateEndpoint(endpoint);
        return tx.execute(status -> {
            lockAndValidate(lease);
            return jdbc.query("""
                    SELECT id, run_generation, endpoint_kind, event_id, player_id,
                           read_started_at, read_finished_at, validation_state
                    FROM %s WHERE run_generation = ? AND endpoint_kind = ?
                      AND event_id IS NOT DISTINCT FROM ? AND player_id IS NOT DISTINCT FROM ?
                    ORDER BY id DESC LIMIT 1
                    """.formatted(tables.payload()), this::stagedPayloadMetadata, lease.generation(),
                    endpoint.kind().name(), endpoint.eventId(), endpoint.playerId()).stream().findFirst();
        });
    }

    /** Selects one exact staged payload in one validation state without transferring its body. */
    Optional<StagedPayloadMetadata> latestStagedPayloadMetadata(Lease lease, Endpoint endpoint,
                                                                 ValidationState state) {
        Objects.requireNonNull(endpoint);
        Objects.requireNonNull(state);
        validateEndpoint(endpoint);
        return tx.execute(status -> {
            lockAndValidate(lease);
            return jdbc.query("""
                    SELECT id, run_generation, endpoint_kind, event_id, player_id,
                           read_started_at, read_finished_at, validation_state
                    FROM %s WHERE run_generation = ? AND endpoint_kind = ?
                      AND event_id IS NOT DISTINCT FROM ? AND player_id IS NOT DISTINCT FROM ?
                      AND validation_state = ? ORDER BY id DESC LIMIT 1
                    """.formatted(tables.payload()), this::stagedPayloadMetadata, lease.generation(),
                    endpoint.kind().name(), endpoint.eventId(), endpoint.playerId(), state.name())
                    .stream().findFirst();
        });
    }

    /** Selects the earliest valid exact payload after a floor without transferring its body. */
    Optional<StagedPayloadMetadata> earliestValidStagedPayloadMetadata(Lease lease, Endpoint endpoint,
                                                                        Instant floor) {
        Objects.requireNonNull(endpoint);
        Objects.requireNonNull(floor);
        validateEndpoint(endpoint);
        return tx.execute(status -> {
            lockAndValidate(lease);
            return jdbc.query("""
                    SELECT id, run_generation, endpoint_kind, event_id, player_id,
                           read_started_at, read_finished_at, validation_state
                    FROM %s WHERE run_generation = ? AND endpoint_kind = ?
                      AND event_id IS NOT DISTINCT FROM ? AND player_id IS NOT DISTINCT FROM ?
                      AND validation_state = 'VALID' AND read_started_at >= ?
                    ORDER BY read_started_at ASC, id ASC LIMIT 1
                    """.formatted(tables.payload()), this::stagedPayloadMetadata, lease.generation(),
                    endpoint.kind().name(), endpoint.eventId(), endpoint.playerId(),
                    timestamp(floor)).stream().findFirst();
        });
    }

    /** Selects the latest reusable valid payload without transferring its body. */
    Optional<StagedPayloadMetadata> latestValidPayloadMetadata(String season, Endpoint endpoint,
                                                                Instant requestedSourceAfter) {
        String validSeason = bounded("season", season, 10);
        Objects.requireNonNull(endpoint);
        Objects.requireNonNull(requestedSourceAfter);
        validateEndpoint(endpoint);
        if (endpoint.kind() != EndpointKind.ELEMENT_SUMMARY) {
            throw new IllegalArgumentException("Cross-run valid metadata requires an element-summary endpoint");
        }
        return jdbc.query("""
                SELECT p.id, p.run_generation, p.endpoint_kind, p.event_id, p.player_id,
                       p.read_started_at, p.read_finished_at, p.validation_state
                FROM %s p JOIN %s r ON r.generation=p.run_generation
                WHERE r.kind='PLAYER_HISTORY' AND r.season=? AND p.endpoint_kind=?
                  AND p.event_id IS NULL AND p.player_id=?
                  AND r.coalescing_key='player-history:' || p.player_id::text
                  AND r.publication_key='player-history:' || p.player_id::text
                  AND p.validation_state='VALID' AND p.read_started_at >= ?
                  AND r.runtime_mode=? AND r.runtime_epoch=?
                ORDER BY p.read_finished_at DESC, p.id DESC LIMIT 1
                """.formatted(tables.payload(), tables.run()), this::stagedPayloadMetadata, validSeason,
                endpoint.kind().name(), endpoint.playerId(), timestamp(requestedSourceAfter),
                runtimeMode(), runtimeEpoch()).stream().findFirst();
    }

    /**
     * Loads one same-run checkpoint after rechecking its exact endpoint, validation state,
     * source floor, season and activation fence.
     */
    Optional<StagedPayload> hydrateStagedPayload(Lease lease, Endpoint endpoint,
                                                  StagedPayloadMetadata candidate, Instant floor) {
        Objects.requireNonNull(lease);
        Objects.requireNonNull(endpoint);
        Objects.requireNonNull(candidate);
        Objects.requireNonNull(floor);
        validateEndpoint(endpoint);
        if (!endpoint.equals(candidate.endpoint())) {
            throw new IllegalArgumentException("Payload metadata endpoint mismatch");
        }
        return tx.execute(status -> {
            lockAndValidate(lease);
            Instant effectiveFloor = effectiveSourceFloor(lease, floor);
            if (candidate.runGeneration() != lease.generation()
                    || candidate.validationState() == ValidationState.INVALID) {
                return Optional.empty();
            }
            return jdbc.query("""
                    SELECT p.id, p.run_generation, p.endpoint_kind, p.read_started_at,
                           p.read_finished_at, p.validation_state, p.validation_error, p.body
                    FROM %s p JOIN %s r ON r.generation=p.run_generation
                    WHERE p.id = ? AND p.run_generation = ? AND p.endpoint_kind = ?
                      AND p.event_id IS NOT DISTINCT FROM ? AND p.player_id IS NOT DISTINCT FROM ?
                      AND p.validation_state = ? AND p.read_started_at >= ?
                      AND r.season = ?
                      AND (? = FALSE OR (r.runtime_mode=? AND r.runtime_epoch=?))
                    """.formatted(tables.payload(), tables.run()), this::stagedPayload, candidate.id(),
                    candidate.runGeneration(), endpoint.kind().name(), endpoint.eventId(), endpoint.playerId(),
                    candidate.validationState().name(), timestamp(effectiveFloor), lease.season(),
                    runtimeFence != null, runtimeMode(), runtimeEpoch()).stream().findFirst();
        });
    }

    /**
     * Loads one cross-run history only after rechecking its exact endpoint, validation state,
     * source floor, season and both the caller's and source run's activation fences.
     */
    Optional<StagedPayload> hydrateLatestValidPayload(Lease lease, String season, Endpoint endpoint,
                                                       StagedPayloadMetadata candidate,
                                                       Instant requestedSourceAfter) {
        Objects.requireNonNull(lease);
        String validSeason = bounded("season", season, 10);
        Objects.requireNonNull(endpoint);
        Objects.requireNonNull(candidate);
        Objects.requireNonNull(requestedSourceAfter);
        validateEndpoint(endpoint);
        if (!lease.season().equals(validSeason)) throw new IllegalArgumentException("Lease season mismatch");
        if (!endpoint.equals(candidate.endpoint())) {
            throw new IllegalArgumentException("Payload metadata endpoint mismatch");
        }
        return tx.execute(status -> {
            lockAndValidate(lease);
            Instant effectiveFloor = effectiveSourceFloor(lease, requestedSourceAfter);
            if (candidate.validationState() != ValidationState.VALID) return Optional.empty();
            return jdbc.query("""
                    SELECT p.id, p.run_generation, p.endpoint_kind, p.read_started_at,
                           p.read_finished_at, p.validation_state, p.validation_error, p.body
                    FROM %s p JOIN %s r ON r.generation=p.run_generation
                    WHERE p.id = ? AND p.run_generation = ? AND r.kind='PLAYER_HISTORY' AND r.season = ?
                      AND p.endpoint_kind = ? AND p.event_id IS NULL
                      AND p.event_id IS NOT DISTINCT FROM ? AND p.player_id IS NOT DISTINCT FROM ?
                      AND r.coalescing_key='player-history:' || p.player_id::text
                      AND r.publication_key='player-history:' || p.player_id::text
                      AND p.validation_state = 'VALID' AND p.read_started_at >= ?
                      AND r.runtime_mode=? AND r.runtime_epoch=?
                    """.formatted(tables.payload(), tables.run()), this::stagedPayload, candidate.id(),
                    candidate.runGeneration(), validSeason, endpoint.kind().name(), endpoint.eventId(),
                    endpoint.playerId(), timestamp(effectiveFloor), runtimeMode(), runtimeEpoch())
                    .stream().findFirst();
        });
    }

    /** Hydrates a complete set of previously selected histories, returning no bodies on any miss. */
    FixtureHistoryHydration hydrateValidHistoryBatch(Lease lease,
            Map<Integer, StagedPayloadMetadata> candidates, Instant requestedSourceAfter) {
        Objects.requireNonNull(lease);
        Objects.requireNonNull(candidates);
        Objects.requireNonNull(requestedSourceAfter);
        if (candidates.isEmpty() || candidates.size() > HistoryFixtureDispatcher.MAX_FIXTURE_PLAYERS) {
            throw new IllegalArgumentException("History hydration requires 1 to 80 candidates");
        }
        List<Integer> playerIds = candidates.keySet().stream().sorted().toList();
        for (int playerId : playerIds) {
            StagedPayloadMetadata candidate = candidates.get(playerId);
            if (candidate == null || candidate.validationState() != ValidationState.VALID
                    || !candidate.endpoint().equals(Endpoint.elementSummary(playerId))) {
                throw new IllegalArgumentException("History candidate does not match its player id");
            }
        }
        return tx.execute(status -> {
            lockAndValidate(lease);
            Instant effectiveFloor = effectiveSourceFloor(lease, requestedSourceAfter);
            String wantedRows = String.join(",", java.util.Collections.nCopies(playerIds.size(),
                    "(?::integer,?::bigint,?::bigint,?::timestamptz,?::timestamptz)"));
            String sql = """
                    WITH wanted(player_id,payload_id,run_generation,read_started_at,read_finished_at) AS (
                        VALUES %s
                    ), eligible AS MATERIALIZED (
                        SELECT p.id, p.run_generation, w.player_id, p.read_started_at,
                               p.read_finished_at, p.body AS history_body
                        FROM wanted w JOIN %s p ON p.id=w.payload_id
                            AND p.run_generation=w.run_generation AND p.player_id=w.player_id
                        JOIN %s r ON r.generation=p.run_generation
                        WHERE p.endpoint_kind='ELEMENT_SUMMARY' AND p.event_id IS NULL
                          AND p.validation_state='VALID'
                          AND p.read_started_at=w.read_started_at
                          AND p.read_finished_at=w.read_finished_at
                          AND p.read_started_at>=?
                          AND r.kind='PLAYER_HISTORY' AND r.season=?
                          AND r.runtime_mode=? AND r.runtime_epoch=?
                          AND r.coalescing_key='player-history:' || w.player_id::text
                          AND r.publication_key='player-history:' || w.player_id::text
                    )
                    SELECT id,run_generation,player_id,read_started_at,read_finished_at,
                           history_body,ARRAY[]::integer[] AS missing_player_ids
                    FROM eligible WHERE (SELECT count(*) FROM eligible)=?
                    UNION ALL
                    SELECT NULL::bigint,NULL::bigint,NULL::integer,NULL::timestamptz,
                           NULL::timestamptz,NULL::bytea,
                           ARRAY(SELECT w.player_id FROM wanted w LEFT JOIN eligible e
                                     ON e.player_id=w.player_id
                                 WHERE e.id IS NULL ORDER BY w.player_id)
                    WHERE (SELECT count(*) FROM eligible)<>?
                    ORDER BY player_id NULLS LAST
                    """.formatted(wantedRows, tables.payload(), tables.run());
            List<Object> parameters = new ArrayList<>();
            for (int playerId : playerIds) {
                StagedPayloadMetadata candidate = candidates.get(playerId);
                parameters.add(playerId);
                parameters.add(candidate.id());
                parameters.add(candidate.runGeneration());
                parameters.add(timestamp(candidate.read().startedAt()));
                parameters.add(timestamp(candidate.read().finishedAt()));
            }
            parameters.add(timestamp(effectiveFloor));
            parameters.add(lease.season());
            parameters.add(runtimeMode());
            parameters.add(runtimeEpoch());
            parameters.add(playerIds.size());
            parameters.add(playerIds.size());
            Map<Integer, CatalogueScheduleParser.SourceDocument> documents = new LinkedHashMap<>();
            List<Integer> missing = new ArrayList<>();
            jdbc.query(new HistoryHydrationStatement(sql, parameters), (ResultSetExtractor<Void>) rs -> {
                while (rs.next()) {
                    Long id = rs.getObject("id", Long.class);
                    if (id == null) {
                        Integer[] absent = (Integer[]) rs.getArray("missing_player_ids").getArray();
                        missing.addAll(List.of(absent));
                    } else {
                        int playerId = rs.getInt("player_id");
                        SourceRead read = new SourceRead(instant(rs, "read_started_at"),
                                instant(rs, "read_finished_at"));
                        documents.put(playerId, new CatalogueScheduleParser.SourceDocument(
                                rs.getBytes("history_body"), new CatalogueScheduleParser.ReadInterval(
                                        read.startedAt(), read.finishedAt())));
                    }
                }
                return null;
            });
            if (documents.size() != playerIds.size() && missing.isEmpty()) {
                missing.addAll(playerIds.stream().filter(id -> !documents.containsKey(id)).toList());
            }
            return new FixtureHistoryHydration(documents, missing);
        });
    }

    /**
     * Visits a bounded newest-first set of complete, validated bootstrap/fixtures batches.
     * Candidate metadata is fetched first; bodies are loaded only for the pair currently being
     * visited. Both documents always come from the same run, and the first mapped value wins.
     */
    public <T> Optional<T> findInLatestValidPublicPayloadPairs(
            String season, int limit, Function<PublicPayloadPair, Optional<T>> visitor) {
        return findInLatestValidPublicPayloadPairs(season, limit, null, visitor);
    }

    /** Rejects stale candidates from metadata before fetching any of their body bytes. */
    public <T> Optional<T> findInLatestValidPublicPayloadPairs(
            String season, int limit, Instant minimumReadStart,
            Function<PublicPayloadPair, Optional<T>> visitor) {
        String validSeason = bounded("season", season, 10);
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("limit must be between 1 and 20");
        Objects.requireNonNull(visitor);
        List<PublicPayloadPairMetadata> candidates = jdbc.query("""
                SELECT r.generation, b.id AS b_id, f.id AS f_id,
                       b.read_started_at AS b_started, f.read_started_at AS f_started
                FROM %s r
                JOIN LATERAL (
                    SELECT id, read_started_at, read_finished_at FROM %s p WHERE p.run_generation = r.generation
                      AND p.endpoint_kind = 'BOOTSTRAP_STATIC' AND p.validation_state = 'VALID'
                    ORDER BY p.id DESC LIMIT 1
                ) b ON TRUE
                JOIN LATERAL (
                    SELECT id, read_started_at, read_finished_at FROM %s p WHERE p.run_generation = r.generation
                      AND p.endpoint_kind = 'FIXTURES' AND p.validation_state = 'VALID'
                    ORDER BY p.id DESC LIMIT 1
                ) f ON TRUE
                WHERE r.kind = 'CATALOGUE_SCHEDULE' AND r.producer_key = 'LEGACY' AND r.season = ?
                  AND (? = FALSE OR (r.runtime_mode=? AND r.runtime_epoch=?))
                ORDER BY GREATEST(b.read_finished_at, f.read_finished_at) DESC, r.generation DESC
                LIMIT ?
                """.formatted(tables.run(), tables.payload(), tables.payload()), (rs, row) ->
                new PublicPayloadPairMetadata(rs.getLong("generation"), rs.getLong("b_id"),
                        rs.getLong("f_id"), instant(rs, "b_started"), instant(rs, "f_started")),
                validSeason, runtimeFence != null, runtimeMode(), runtimeEpoch(), limit);
        return visitPublicPayloadPairs(validSeason, candidates, minimumReadStart, visitor);
    }

    /** Visits only exact current-ACTIVE publications before hydrating any candidate pair. */
    <T> Optional<T> findInLatestCurrentPublishedCanonicalPairs(String season, int limit, Instant now,
            Function<CurrentPublishedCanonicalPair, Optional<T>> visitor) {
        String validSeason = bounded("season", season, 10);
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("limit must be between 1 and 20");
        Objects.requireNonNull(now);
        Objects.requireNonNull(visitor);
        if (runtimeFence == null || runtimeFence.mode() != OwnedSyncMode.ACTIVE
                || !runtimeFence.season().equals(validSeason)) return Optional.empty();
        List<PublicPayloadPairMetadata> candidates = jdbc.query("""
                SELECT generation, publication_generation, b_id, f_id, b_started, f_started FROM (
                    SELECT DISTINCT ON (b.id, f.id)
                           source_run.generation, publication_run.generation AS publication_generation,
                           b.id AS b_id, f.id AS f_id,
                           b.read_started_at AS b_started, f.read_started_at AS f_started,
                           b.read_finished_at AS b_finished, f.read_finished_at AS f_finished,
                           p.published_at
                    FROM %s p
                    JOIN %s publication_run ON publication_run.generation=p.latest_generation
                        AND publication_run.season=p.season
                    JOIN %s b ON b.id=p.source_bootstrap_payload_id
                        AND b.endpoint_kind='BOOTSTRAP_STATIC' AND b.event_id IS NULL AND b.player_id IS NULL
                        AND b.validation_state='VALID' AND b.source_published IS TRUE
                        AND b.read_started_at=p.source_bootstrap_read_started_at
                        AND b.read_finished_at=p.source_bootstrap_read_finished_at
                    JOIN %s f ON f.id=p.source_fixtures_payload_id
                        AND f.endpoint_kind='FIXTURES' AND f.event_id IS NULL AND f.player_id IS NULL
                        AND f.validation_state='VALID' AND f.source_published IS TRUE
                        AND f.read_started_at=p.source_fixtures_read_started_at
                        AND f.read_finished_at=p.source_fixtures_read_finished_at
                    JOIN %s source_run ON source_run.generation=b.run_generation
                        AND source_run.season=p.season AND f.run_generation=source_run.generation
                    WHERE p.season=? AND publication_run.publication_key=p.publication_key
                      AND p.published_at IS NOT NULL AND p.published_at<=?
                      AND publication_run.kind='CATALOGUE_SCHEDULE'
                      AND publication_run.producer_key='LEGACY'
                      AND publication_run.runtime_mode='ACTIVE' AND publication_run.runtime_epoch=?
                      AND publication_run.state='COMPLETED' AND publication_run.completed_at IS NOT NULL
                      AND publication_run.completed_at<=p.published_at
                      AND ((publication_run.coalescing_key=? AND publication_run.publication_key=?)
                           OR (publication_run.coalescing_key=? AND publication_run.publication_key=?))
                      AND source_run.kind='CATALOGUE_SCHEDULE' AND source_run.producer_key='LEGACY'
                      AND source_run.runtime_mode='ACTIVE' AND source_run.runtime_epoch=?
                      AND ((source_run.coalescing_key=? AND source_run.publication_key=?)
                           OR (source_run.coalescing_key=? AND source_run.publication_key=?))
                      AND b.run_generation=f.run_generation
                      AND b.read_started_at<=b.read_finished_at AND f.read_started_at<=f.read_finished_at
                      AND b.read_finished_at<=p.published_at AND f.read_finished_at<=p.published_at
                      AND publication_run.source_read_started_at IS NOT NULL
                      AND publication_run.source_read_finished_at IS NOT NULL
                      AND publication_run.source_read_started_at<=publication_run.source_read_finished_at
                      AND publication_run.source_read_finished_at<=p.published_at
                      AND p.source_read_started_at=publication_run.source_read_started_at
                      AND p.source_read_finished_at=publication_run.source_read_finished_at
                      AND publication_run.source_read_started_at<=b.read_started_at
                      AND publication_run.source_read_started_at<=f.read_started_at
                      AND publication_run.source_read_finished_at>=b.read_finished_at
                      AND publication_run.source_read_finished_at>=f.read_finished_at
                      AND %s
                      AND p.source_pair_fingerprint ~ '^[0-9a-f]{64}$'
                      AND p.source_bootstrap_sha256 ~ '^[0-9a-f]{64}$'
                      AND p.source_fixtures_sha256 ~ '^[0-9a-f]{64}$'
                    ORDER BY b.id, f.id, %s
                ) published_pairs
                ORDER BY %s
                LIMIT ?
                """.formatted(tables.receipt(), tables.run(), tables.payload(), tables.payload(), tables.run(),
                        canonicalDemandFloorPredicate("publication_run", "source_run", "source_run", "b", "f"),
                        canonicalParentWinnerOrder("b.read_finished_at", "f.read_finished_at", "p.published_at",
                                "publication_run.generation"),
                        canonicalParentWinnerOrder("b_finished", "f_finished", "published_at",
                                "publication_generation")),
                (rs, row) -> new PublicPayloadPairMetadata(rs.getLong("generation"), rs.getLong("b_id"),
                        rs.getLong("f_id"), instant(rs, "b_started"), instant(rs, "f_started")),
                validSeason, timestamp(now), runtimeFence.epoch(),
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY,
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY,
                runtimeFence.epoch(),
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY,
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY, limit);
        return visitPublicPayloadPairs(validSeason, candidates, null, pair -> {
            CanonicalSourcePair sourcePair = CanonicalSourcePair.from(pair.bootstrap(), pair.fixtures());
            Optional<CanonicalPublication> publication = currentPublishedCanonicalPair(
                    validSeason, sourcePair, now);
            if (publication.isEmpty()) return Optional.empty();
            return Objects.requireNonNull(visitor.apply(new CurrentPublishedCanonicalPair(
                    pair, sourcePair, publication.get())), "published canonical pair visitor result");
        });
    }

    private <T> Optional<T> visitPublicPayloadPairs(String season,
            List<PublicPayloadPairMetadata> candidates, Instant minimumReadStart,
            Function<PublicPayloadPair, Optional<T>> visitor) {
        for (PublicPayloadPairMetadata candidate : candidates) {
            if (minimumReadStart != null && (candidate.bootstrapStartedAt().isBefore(minimumReadStart)
                    || candidate.fixturesStartedAt().isBefore(minimumReadStart))) continue;
            Optional<PublicPayloadPair> pair = cachedOrLoadPublicPayloadPair(season, candidate);
            if (pair.isEmpty()) continue;
            Optional<T> mapped = Objects.requireNonNull(visitor.apply(pair.get()),
                    "public payload pair visitor result");
            if (mapped.isPresent()) return mapped;
        }
        return Optional.empty();
    }

    private Optional<PublicPayloadPair> cachedOrLoadPublicPayloadPair(
            String season, PublicPayloadPairMetadata candidate) {
        PublicPayloadPairCacheKey key = new PublicPayloadPairCacheKey(season,
                candidate.runGeneration(), candidate.bootstrapId(), candidate.fixturesId(),
                runtimeFence != null, runtimeMode(), runtimeEpoch());
        synchronized (publicPairCache) {
            PublicPayloadPair cached = publicPairCache.get(key);
            if (cached != null) {
                if (publicPayloadPairStillValid(key)) return Optional.of(cached);
                publicPairCache.remove(key);
                return Optional.empty();
            }
            Optional<PublicPayloadPair> loaded = loadPublicPayloadPair(season, candidate);
            loaded.ifPresent(pair -> publicPairCache.put(key, pair));
            return loaded;
        }
    }

    private boolean publicPayloadPairStillValid(PublicPayloadPairCacheKey key) {
        Boolean valid = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM %s r
                    JOIN %s b ON b.run_generation=r.generation AND b.id=?
                      AND b.endpoint_kind='BOOTSTRAP_STATIC' AND b.validation_state='VALID'
                    JOIN %s f ON f.run_generation=r.generation AND f.id=?
                      AND f.endpoint_kind='FIXTURES' AND f.validation_state='VALID'
                    WHERE r.generation=? AND r.kind='CATALOGUE_SCHEDULE' AND r.producer_key='LEGACY'
                      AND r.season=? AND (? = FALSE OR (r.runtime_mode=? AND r.runtime_epoch=?))
                )
                """.formatted(tables.run(), tables.payload(), tables.payload()), Boolean.class,
                key.bootstrapId(), key.fixturesId(), key.runGeneration(), key.season(),
                key.fenced(), key.runtimeMode(), key.runtimeEpoch());
        return Boolean.TRUE.equals(valid);
    }

    private Optional<PublicPayloadPair> loadPublicPayloadPair(
            String season, PublicPayloadPairMetadata candidate) {
        return jdbc.query("""
                SELECT r.generation,
                       b.id AS b_id, b.read_started_at AS b_started, b.read_finished_at AS b_finished,
                       b.validation_state AS b_validation, b.validation_error AS b_error, b.body AS b_body,
                       f.id AS f_id, f.read_started_at AS f_started, f.read_finished_at AS f_finished,
                       f.validation_state AS f_validation, f.validation_error AS f_error, f.body AS f_body
                FROM %s r
                JOIN %s b ON b.run_generation=r.generation AND b.id=?
                  AND b.endpoint_kind='BOOTSTRAP_STATIC' AND b.validation_state='VALID'
                JOIN %s f ON f.run_generation=r.generation AND f.id=?
                  AND f.endpoint_kind='FIXTURES' AND f.validation_state='VALID'
                WHERE r.generation=? AND r.kind='CATALOGUE_SCHEDULE' AND r.producer_key='LEGACY'
                  AND r.season=? AND (? = FALSE OR (r.runtime_mode=? AND r.runtime_epoch=?))
                """.formatted(tables.run(), tables.payload(), tables.payload()), (rs, row) ->
                new PublicPayloadPair(rs.getLong("generation"),
                        stagedPayload(rs, "b", EndpointKind.BOOTSTRAP_STATIC),
                        stagedPayload(rs, "f", EndpointKind.FIXTURES)), candidate.bootstrapId(),
                candidate.fixturesId(), candidate.runGeneration(), season, runtimeFence != null,
                runtimeMode(), runtimeEpoch()).stream().findFirst();
    }

    /** Locks and rechecks the exact fixture anchors and cross-run histories used for publication. */
    void validateFixtureManifestForPublication(Lease lease, FixtureDependencyManifest expected,
                                               Map<Integer, StagedPayloadMetadata> histories) {
        Objects.requireNonNull(lease);
        Objects.requireNonNull(expected);
        Objects.requireNonNull(histories);
        lockAndValidate(lease);
        Optional<FixtureDependencyManifest> current = fixtureDependencyManifestLocked(lease,
                expected.fixtureId());
        if (current.isEmpty() || !expected.sameAnchorsAndDemand(current.get())
                || !current.get().missingPlayerIds().isEmpty()
                || !expected.requiredPlayerIds().equals(histories.keySet().stream().sorted().toList())) {
            throw new FixtureEvidenceChangedException("Fixture dependency manifest changed before publication");
        }
        FixtureDependencyManifest manifest = current.get();
        String anchorSql = """
                SELECT m.run_generation, m.bootstrap_payload_id, m.fixtures_payload_id, m.live_payload_id,
                       m.source_floor, r.requested_source_after, l.event_id,
                       b.read_started_at AS b_started, b.read_finished_at AS b_finished,
                       f.read_started_at AS f_started, f.read_finished_at AS f_finished,
                       l.read_started_at AS l_started, l.read_finished_at AS l_finished
                FROM %s r JOIN %s m ON m.run_generation=r.generation
                JOIN %s b ON b.id=m.bootstrap_payload_id AND b.run_generation=r.generation
                JOIN %s f ON f.id=m.fixtures_payload_id AND f.run_generation=r.generation
                JOIN %s l ON l.id=m.live_payload_id AND l.run_generation=r.generation
                WHERE r.generation=? AND r.kind='FIXTURE_VERIFICATION' AND r.season=?
                  AND r.runtime_mode=? AND r.runtime_epoch=?
                  AND r.coalescing_key=? AND r.publication_key=?
                  AND m.bootstrap_payload_id=? AND m.fixtures_payload_id=? AND m.live_payload_id=?
                  AND m.source_floor=? AND r.requested_source_after=?
                  AND %s
                FOR SHARE OF m,b,f,l
                """.formatted(tables.run(), tables.fixtureDependencyManifest(), tables.payload(),
                tables.payload(), tables.payload(), fixtureManifestAnchorConditions("r"));
        List<AnchorIdentity> anchors = jdbc.query(anchorSql, (rs, row) -> new AnchorIdentity(
                rs.getLong("run_generation"), rs.getLong("bootstrap_payload_id"),
                rs.getLong("fixtures_payload_id"), rs.getLong("live_payload_id"),
                rs.getInt("event_id"), instant(rs, "b_started"), instant(rs, "b_finished"),
                instant(rs, "f_started"), instant(rs, "f_finished"), instant(rs, "l_started"),
                instant(rs, "l_finished")), lease.generation(), lease.season(), runtimeMode(), runtimeEpoch(),
                FixtureSyncPublisher.publicationKey(expected.fixtureId()),
                FixtureSyncPublisher.publicationKey(expected.fixtureId()), expected.bootstrapPayloadId(),
                expected.fixturesPayloadId(), expected.livePayloadId(), timestamp(expected.manifestSourceFloor()),
                timestamp(expected.requestedSourceAfter()));
        if (anchors.size() != 1) {
            throw new FixtureEvidenceChangedException("Fixture anchors changed before publication");
        }
        AnchorIdentity anchor = anchors.getFirst();
        if (anchor.eventId() != expected.eventId()
                || !anchor.bootstrapRead().equals(expected.bootstrap().read())
                || !anchor.fixturesRead().equals(expected.fixtures().read())
                || !anchor.liveRead().equals(expected.live().read())) {
            throw new FixtureEvidenceChangedException("Fixture anchor metadata changed before publication");
        }

        List<Integer> playerIds = expected.requiredPlayerIds();
        String wanted = String.join(",", java.util.Collections.nCopies(playerIds.size(), "(?,?)"));
        String historySql = """
                SELECT hp.id, hp.run_generation, hp.player_id, hp.read_started_at, hp.read_finished_at
                FROM (VALUES %s) AS wanted(player_id,payload_id)
                JOIN %s hp ON hp.id=wanted.payload_id AND hp.player_id=wanted.player_id
                JOIN %s hr ON hr.generation=hp.run_generation
                WHERE hp.endpoint_kind='ELEMENT_SUMMARY' AND hp.event_id IS NULL
                  AND hp.validation_state='VALID' AND hp.read_started_at>=?
                  AND hr.kind='PLAYER_HISTORY' AND hr.season=?
                  AND hr.runtime_mode=? AND hr.runtime_epoch=?
                  AND hr.coalescing_key='player-history:' || wanted.player_id::text
                  AND hr.publication_key='player-history:' || wanted.player_id::text
                ORDER BY hp.player_id
                FOR SHARE OF hp,hr
                """.formatted(wanted, tables.payload(), tables.run());
        List<Object> parameters = new ArrayList<>();
        for (int playerId : playerIds) {
            StagedPayloadMetadata metadata = histories.get(playerId);
            if (metadata == null || metadata.validationState() != ValidationState.VALID
                    || !metadata.endpoint().equals(Endpoint.elementSummary(playerId))) {
                throw new FixtureEvidenceChangedException("History metadata changed before publication");
            }
            parameters.add(playerId);
            parameters.add(metadata.id());
        }
        parameters.add(timestamp(manifest.effectiveFloor()));
        parameters.add(lease.season());
        parameters.add(runtimeMode());
        parameters.add(runtimeEpoch());
        List<HistoryIdentity> selected = jdbc.query(historySql, (rs, row) -> new HistoryIdentity(
                rs.getLong("id"), rs.getLong("run_generation"), rs.getInt("player_id"),
                new SourceRead(instant(rs, "read_started_at"), instant(rs, "read_finished_at"))),
                parameters.toArray());
        if (selected.size() != playerIds.size()) {
            throw new FixtureEvidenceChangedException("Required histories changed before publication");
        }
        for (HistoryIdentity identity : selected) {
            StagedPayloadMetadata metadata = histories.get(identity.playerId());
            if (identity.id() != metadata.id() || identity.runGeneration() != metadata.runGeneration()
                    || !identity.read().equals(metadata.read())) {
                throw new FixtureEvidenceChangedException("Required history metadata changed before publication");
            }
        }
    }

    /** Changes validation only for payloads owned by the current fenced run. */
    public void validateStagedPayloads(Lease lease, List<Long> payloadIds, Validation validation) {
        Objects.requireNonNull(payloadIds);
        Objects.requireNonNull(validation);
        if (payloadIds.isEmpty() || payloadIds.size() > 2 || payloadIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("one or two positive payload ids are required");
        }
        tx.executeWithoutResult(status -> {
            lockAndValidate(lease);
            int updated = 0;
            for (Long payloadId : payloadIds) {
                updated += jdbc.update("""
                        UPDATE %s SET validation_state = ?, validation_error = ?
                        WHERE run_generation = ? AND id = ?
                        """.formatted(tables.payload()), validation.state().name(),
                        boundedNullable(validation.error(), MAX_ERROR_CHARS),
                        lease.generation(), payloadId);
            }
            if (updated != payloadIds.size()) {
                throw new IllegalArgumentException("Payload ids must belong to the leased run");
            }
        });
        evictPublicPairs(payloadIds);
    }

    /** Read-only chronology evidence used to avoid replaying obsolete staged source data. */
    public Optional<SourceRead> sourceReceipt(String season, String publicationKey) {
        String validSeason = bounded("season", season, 10);
        String validKey = bounded("publicationKey", publicationKey, 200);
        return jdbc.query("""
                SELECT source_read_started_at, source_read_finished_at FROM %s
                WHERE season = ? AND publication_key = ?
                  AND source_read_started_at IS NOT NULL AND source_read_finished_at IS NOT NULL
                """.formatted(tables.receipt()), (rs, row) -> new SourceRead(
                instant(rs, "source_read_started_at"), instant(rs, "source_read_finished_at")),
                validSeason, validKey).stream().findFirst();
    }

    /** Completes validated staging without invoking a publisher or advancing a publication receipt. */
    public void completeStaged(Lease lease, SourceRead read, List<SourceRead> demandReads) {
        Objects.requireNonNull(read);
        Objects.requireNonNull(demandReads);
        if (demandReads.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("demand reads cannot contain null");
        }
        tx.executeWithoutResult(status -> {
            LockedRun run = lockAndValidate(lease);
            Instant requestedAfter = jdbc.queryForObject(
                    "SELECT requested_source_after FROM %s WHERE generation = ?"
                            .formatted(tables.run()), Timestamp.class, lease.generation()).toInstant();
            if (demandReads.stream().anyMatch(source -> source.startedAt().isBefore(requestedAfter))) {
                throw new SourceDemandAdvancedException(lease.generation(), requestedAfter);
            }
            Instant completedAt = now();
            requireNotExpired(run, completedAt);
            requireReadNotFuture(read, completedAt);
            jdbc.update("""
                    UPDATE %s SET state = 'COMPLETED', lease_owner = NULL, lease_token = NULL,
                        lease_expires_at = NULL, source_read_started_at = ?, source_read_finished_at = ?,
                        last_error = NULL, completed_at = ?, updated_at = ? WHERE generation = ?
                    """.formatted(tables.run()), timestamp(read.startedAt()), timestamp(read.finishedAt()),
                    timestamp(completedAt), timestamp(completedAt), lease.generation());
        });
    }

    /** Runs a database-only publication callback and advances the receipt on success. */
    public <T> T publish(Lease lease, SourceRead read, Supplier<T> callback) {
        return publish(lease, read, List.of(), callback);
    }

    public <T> T publish(Lease lease, SourceRead read, List<SourceRead> demandReads,
                         Supplier<T> callback) {
        return publish(lease, read, demandReads, null, callback);
    }

    <T> T publish(Lease lease, SourceRead read, List<SourceRead> demandReads,
                  CanonicalSourcePair sourcePair, Supplier<T> callback) {
        Objects.requireNonNull(callback);
        return publishConditionally(lease, read, demandReads, sourcePair,
                () -> ConditionalPublication.published(callback.get()));
    }

    /**
     * Invokes one database-only callback exactly once while holding the run and publication receipt
     * locks. Both publication and durable non-publication outcomes complete the run atomically;
     * only a published outcome advances the receipt. Any exception rolls everything back.
     */
    public <T> T publishConditionally(Lease lease, SourceRead read,
                                      Supplier<ConditionalPublication<T>> callback) {
        return publishConditionally(lease, read, List.of(), callback);
    }

    public <T> T publishConditionally(Lease lease, SourceRead read, List<SourceRead> demandReads,
                                      Supplier<ConditionalPublication<T>> callback) {
        return publishConditionally(lease, read, demandReads, null, callback);
    }

    private <T> T publishConditionally(Lease lease, SourceRead read, List<SourceRead> demandReads,
                                       CanonicalSourcePair sourcePair,
                                       Supplier<ConditionalPublication<T>> callback) {
        Objects.requireNonNull(read);
        Objects.requireNonNull(demandReads);
        Objects.requireNonNull(callback);
        if (demandReads.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("demand reads cannot contain null");
        }
        return tx.execute(status -> {
            LockedRun run = lockAndValidate(lease);
            Instant requestedAfter = jdbc.queryForObject(
                    "SELECT requested_source_after FROM %s WHERE generation = ?"
                            .formatted(tables.run()), Timestamp.class, lease.generation()).toInstant();
            if (demandReads.stream().anyMatch(source -> source.startedAt().isBefore(requestedAfter))) {
                throw new SourceDemandAdvancedException(lease.generation(), requestedAfter);
            }
            Long latest = jdbc.queryForObject("""
                    SELECT latest_generation FROM %s
                    WHERE season = ? AND publication_key = ? FOR UPDATE
                    """.formatted(tables.receipt()), Long.class, run.season, run.publicationKey);
            Instant callbackStartedAt = now();
            requireNotExpired(run, callbackStartedAt);
            requireReadNotFuture(read, callbackStartedAt);
            if (latest != null && latest >= run.generation) {
                throw new StaleGenerationException(run.generation, latest, run.season, run.publicationKey);
            }
            if (sourcePair != null) requireCanonicalPairCoverage(read, sourcePair);
            ConditionalPublication<T> outcome = Objects.requireNonNull(callback.get(), "callback outcome");
            CanonicalSourcePair persistedSourcePair = null;
            if (outcome.published() && sourcePair != null) {
                requireNotExpired(run, now());
                persistedSourcePair = lockAndMarkCanonicalSourcePublished(run, sourcePair);
            }
            Instant completedAt = now();
            requireNotExpired(run, completedAt);
            if (outcome.published()) {
                jdbc.update("""
                        UPDATE %s SET latest_generation = ?, source_read_started_at = ?,
                            source_read_finished_at = ?, published_at = ?,
                            source_bootstrap_payload_id=?, source_fixtures_payload_id=?,
                            source_bootstrap_read_started_at=?, source_bootstrap_read_finished_at=?,
                            source_fixtures_read_started_at=?, source_fixtures_read_finished_at=?,
                            source_bootstrap_sha256=?, source_fixtures_sha256=?, source_pair_fingerprint=?
                        WHERE season = ? AND publication_key = ?
                        """.formatted(tables.receipt()), run.generation, timestamp(read.startedAt()),
                        timestamp(read.finishedAt()), timestamp(completedAt),
                        persistedSourcePair == null ? null : persistedSourcePair.bootstrap().id(),
                        persistedSourcePair == null ? null : persistedSourcePair.fixtures().id(),
                        persistedSourcePair == null ? null
                                : timestamp(persistedSourcePair.bootstrap().read().startedAt()),
                        persistedSourcePair == null ? null
                                : timestamp(persistedSourcePair.bootstrap().read().finishedAt()),
                        persistedSourcePair == null ? null
                                : timestamp(persistedSourcePair.fixtures().read().startedAt()),
                        persistedSourcePair == null ? null
                                : timestamp(persistedSourcePair.fixtures().read().finishedAt()),
                        persistedSourcePair == null ? null : persistedSourcePair.bootstrap().sha256(),
                        persistedSourcePair == null ? null : persistedSourcePair.fixtures().sha256(),
                        persistedSourcePair == null ? null : persistedSourcePair.fingerprint(),
                        run.season, run.publicationKey);
            }
            jdbc.update("""
                    UPDATE %s SET state = 'COMPLETED', lease_owner = NULL, lease_token = NULL,
                        lease_expires_at = NULL, source_read_started_at = ?, source_read_finished_at = ?,
                        last_error = NULL, completed_at = ?, updated_at = ? WHERE generation = ?
                    """.formatted(tables.run()), timestamp(read.startedAt()), timestamp(read.finishedAt()),
                    timestamp(completedAt), timestamp(completedAt), run.generation);
            return outcome.value();
        });
    }

    static void requireCanonicalPairCoverage(SourceRead publicationRead, CanonicalSourcePair pair) {
        SourceRead persistedRead = postgresTimestampPrecision(publicationRead);
        SourceRead bootstrapRead = postgresTimestampPrecision(pair.bootstrap().read());
        SourceRead fixturesRead = postgresTimestampPrecision(pair.fixtures().read());
        if (persistedRead.startedAt().isAfter(bootstrapRead.startedAt())
                || persistedRead.startedAt().isAfter(fixturesRead.startedAt())
                || persistedRead.finishedAt().isBefore(bootstrapRead.finishedAt())
                || persistedRead.finishedAt().isBefore(fixturesRead.finishedAt())) {
            throw new IllegalArgumentException("Publication source interval must cover both canonical reads");
        }
    }

    private CanonicalSourcePair lockAndMarkCanonicalSourcePublished(LockedRun publicationRun,
                                                                      CanonicalSourcePair pair) {
        if (!"LEGACY".equals(publicationRun.producerKey())) {
            throw new IllegalArgumentException("Only legacy catalogue runs may publish canonical source pairs");
        }
        List<CanonicalSourceMetadata> rows = jdbc.query("""
                SELECT b.id AS b_id, b.run_generation AS b_generation, b.read_started_at AS b_started,
                       b.read_finished_at AS b_finished, f.id AS f_id,
                       f.run_generation AS f_generation, f.read_started_at AS f_started,
                       f.read_finished_at AS f_finished
                FROM %s b JOIN %s f ON f.id=? AND f.run_generation=b.run_generation
                    AND f.endpoint_kind='FIXTURES' AND f.event_id IS NULL AND f.player_id IS NULL
                    AND f.read_started_at=? AND f.read_finished_at=? AND f.validation_state='VALID'
                JOIN %s source_run ON source_run.generation=b.run_generation
                WHERE b.id=? AND b.run_generation=? AND b.endpoint_kind='BOOTSTRAP_STATIC'
                  AND b.event_id IS NULL AND b.player_id IS NULL
                  AND b.read_started_at=? AND b.read_finished_at=? AND b.validation_state='VALID'
                  AND source_run.season=? AND source_run.kind='CATALOGUE_SCHEDULE'
                  AND source_run.producer_key='LEGACY'
                  AND source_run.runtime_mode=? AND source_run.runtime_epoch=?
                  AND ((source_run.coalescing_key=? AND source_run.publication_key=?)
                    OR (source_run.coalescing_key=? AND source_run.publication_key=?))
                FOR UPDATE OF b,f,source_run
                """.formatted(tables.payload(), tables.payload(), tables.run()), (rs, row) ->
                new CanonicalSourceMetadata(rs.getLong("b_id"), rs.getLong("b_generation"),
                        new SourceRead(instant(rs, "b_started"), instant(rs, "b_finished")),
                        rs.getLong("f_id"), rs.getLong("f_generation"),
                        new SourceRead(instant(rs, "f_started"), instant(rs, "f_finished"))),
                pair.fixtures().id(), timestamp(pair.fixtures().read().startedAt()),
                timestamp(pair.fixtures().read().finishedAt()), pair.bootstrap().id(),
                pair.bootstrap().runGeneration(), timestamp(pair.bootstrap().read().startedAt()),
                timestamp(pair.bootstrap().read().finishedAt()), publicationRun.season,
                publicationRun.runtimeMode, publicationRun.runtimeEpoch,
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY,
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY);
        if (rows.size() != 1) throw new IllegalStateException("Canonical source payload pair changed before publish");
        CanonicalSourceMetadata metadata = rows.getFirst();
        String bootstrapHash = canonicalSourceBodyHash(metadata.bootstrapId());
        String fixturesHash = canonicalSourceBodyHash(metadata.fixturesId());
        if (metadata.bootstrapGeneration() != pair.bootstrap().runGeneration()
                || metadata.fixturesGeneration() != pair.fixtures().runGeneration()
                || !metadata.bootstrapRead().equals(postgresTimestampPrecision(pair.bootstrap().read()))
                || !metadata.fixturesRead().equals(postgresTimestampPrecision(pair.fixtures().read()))) {
            throw new IllegalStateException("Canonical source payload metadata changed before publish");
        }
        if (!bootstrapHash.equals(pair.bootstrap().sha256())
                || !fixturesHash.equals(pair.fixtures().sha256())) {
            throw new IllegalStateException("Canonical source payload bodies changed before publish");
        }
        CanonicalSourcePair persistedPair = CanonicalSourcePair.of(
                new CanonicalSourcePayload(metadata.bootstrapId(), metadata.bootstrapGeneration(),
                        EndpointKind.BOOTSTRAP_STATIC, metadata.bootstrapRead(), bootstrapHash),
                new CanonicalSourcePayload(metadata.fixturesId(), metadata.fixturesGeneration(),
                        EndpointKind.FIXTURES, metadata.fixturesRead(), fixturesHash));
        int updated = jdbc.update("UPDATE %s SET source_published=TRUE WHERE id IN (?,?) AND validation_state='VALID'"
                .formatted(tables.payload()), metadata.bootstrapId(), metadata.fixturesId());
        if (updated != 2) throw new IllegalStateException("Canonical source payloads were invalidated before publish");
        return persistedPair;
    }

    private String canonicalSourceBodyHash(long payloadId) {
        List<String> hashes = jdbc.query("SELECT body AS source_body_digest FROM %s WHERE id=?"
                .formatted(tables.payload()), (rs, row) -> sha256(rs.getBytes("source_body_digest")), payloadId);
        if (hashes.size() != 1) throw new IllegalStateException("Locked canonical source payload disappeared");
        return hashes.getFirst();
    }

    /** Requeues work without treating admission or scheduling delay as a failed source fetch. */
    public void retry(Lease lease, Instant dueAt, String error) {
        finishAttempt(lease, dueAt, error, false, false, false, null);
    }

    /** Applies a database-only retry mutation after validating the lease, in the retry transaction. */
    public void retry(Lease lease, Instant dueAt, String error, Supplier<Instant> retryMutation) {
        finishAttempt(lease, dueAt, error, false, false, false, Objects.requireNonNull(retryMutation));
    }

    /** Requeues a dependent behind the work needed to make it ready. */
    public void retryForDependency(Lease lease, Instant dueAt, String error) {
        finishAttempt(lease, dueAt, error, false, false, true, null);
    }

    /** Stores the fixture's validated history dependency manifest without releasing its lease. */
    public void recordDependencyManifest(Lease lease, int fixtureId, int eventId,
                                         PublicPayloadPair pair, StagedPayload live,
                                         List<Integer> requiredPlayerIds) {
        Objects.requireNonNull(lease);
        Objects.requireNonNull(pair);
        Objects.requireNonNull(live);
        List<Integer> sortedPlayerIds = sortedRequiredPlayerIds(requiredPlayerIds);
        tx.executeWithoutResult(status -> {
            LockedRun run = lockAndValidate(lease);
            writeFixtureDependencyManifest(lease, run, fixtureId, eventId, pair, live, sortedPlayerIds);
        });
    }

    /** Writes the fixture's validated history dependency manifest and releases its lease atomically. */
    public void retryForDependency(Lease lease, Instant dueAt, String error, int fixtureId, int eventId,
                                   PublicPayloadPair pair, StagedPayload live,
                                   List<Integer> requiredPlayerIds) {
        retryForDependency(lease, dueAt, error, fixtureId, eventId, pair, live, requiredPlayerIds, null);
    }

    /** Runs the dependency mutation only after lease/runtime validation, atomically with requeue. */
    public void retryForDependency(Lease lease, Instant dueAt, String error, int fixtureId, int eventId,
                                   PublicPayloadPair pair, StagedPayload live,
                                   List<Integer> requiredPlayerIds, Supplier<Instant> retryMutation) {
        Objects.requireNonNull(lease);
        Objects.requireNonNull(dueAt);
        Objects.requireNonNull(pair);
        Objects.requireNonNull(live);
        List<Integer> sortedPlayerIds = sortedRequiredPlayerIds(requiredPlayerIds);

        tx.executeWithoutResult(status -> {
            LockedRun run = lockAndValidate(lease);
            Instant selectedDueAt = retryMutation == null ? dueAt
                    : Objects.requireNonNull(retryMutation.get(), "retry mutation due time");
            Instant recordedAt = writeFixtureDependencyManifest(
                    lease, run, fixtureId, eventId, pair, live, sortedPlayerIds);
            int released = jdbc.update("""
                    UPDATE %s SET state = 'PENDING', priority = ?, due_at = ?, last_error = ?,
                        failed_at = NULL, lease_owner = NULL, lease_token = NULL,
                        lease_expires_at = NULL, updated_at = ?
                    WHERE generation = ? AND state = 'LEASED' AND lease_owner = ?
                      AND lease_token = ? AND lease_fence = ?
                    """.formatted(tables.run()), SyncKind.DEPENDENCY_WAITING_PRIORITY,
                    timestamp(selectedDueAt), boundedNullable(error, MAX_ERROR_CHARS), timestamp(recordedAt),
                    run.generation, lease.owner(), lease.token(), lease.fence());
            if (released != 1) {
                throw new LeaseLostException("Lease is stale for generation " + run.generation);
            }
        });
    }

    private Instant writeFixtureDependencyManifest(Lease lease, LockedRun run, int fixtureId, int eventId,
                                                   PublicPayloadPair pair, StagedPayload live,
                                                   List<Integer> sortedPlayerIds) {
        if (fixtureId <= 0 || eventId <= 0) {
            throw new IllegalArgumentException("fixture and event ids must be positive");
        }
        if (!lease.season().equals(run.season) || lease.kind() != run.kind
                || !lease.coalescingKey().equals(run.coalescingKey)
                || !lease.publicationKey().equals(run.publicationKey)) {
            throw new LeaseLostException("Lease identity no longer matches generation " + lease.generation());
        }
        if (run.kind != SyncKind.FIXTURE_VERIFICATION
                || !canonicalNumericRunKey(run, "fixture:")
                || numericRunKeyId(run, "fixture:") != fixtureId) {
            throw new IllegalArgumentException("Dependency manifest does not match fixture run identity");
        }
        if (pair.runGeneration() != run.generation || live.runGeneration() != run.generation) {
            throw new IllegalArgumentException("Dependency payloads must belong to the leased fixture run");
        }

        DependencyPayload bootstrap = dependencyPayload(lease, pair.bootstrap(),
                EndpointKind.BOOTSTRAP_STATIC, null);
        DependencyPayload fixtures = dependencyPayload(lease, pair.fixtures(),
                EndpointKind.FIXTURES, null);
        DependencyPayload livePayload = dependencyPayload(lease, live, EndpointKind.EVENT_LIVE, eventId);

        Instant sourceFloor = jdbc.queryForObject("SELECT requested_source_after FROM %s WHERE generation = ?"
                .formatted(tables.run()), Timestamp.class, run.generation).toInstant();
        if (bootstrap.read().startedAt().isBefore(sourceFloor)
                || fixtures.read().startedAt().isBefore(sourceFloor)
                || livePayload.read().startedAt().isBefore(sourceFloor)) {
            throw new SourceDemandAdvancedException(run.generation, sourceFloor);
        }
        if (livePayload.read().startedAt().isBefore(fixtures.read().finishedAt().plusMillis(1))) {
            throw new IllegalArgumentException("Live dependency payload predates the fixture response floor");
        }

        Instant recordedAt = now();
        requireNotExpired(run, recordedAt);
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO %s(run_generation, bootstrap_payload_id, fixtures_payload_id,
                                   live_payload_id, event_id, required_player_ids, source_floor)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (run_generation) DO UPDATE SET
                        bootstrap_payload_id = EXCLUDED.bootstrap_payload_id,
                        fixtures_payload_id = EXCLUDED.fixtures_payload_id,
                        live_payload_id = EXCLUDED.live_payload_id,
                        event_id = EXCLUDED.event_id,
                        required_player_ids = EXCLUDED.required_player_ids,
                        source_floor = EXCLUDED.source_floor
                    """.formatted(tables.fixtureDependencyManifest()));
            statement.setLong(1, run.generation);
            statement.setLong(2, bootstrap.id());
            statement.setLong(3, fixtures.id());
            statement.setLong(4, livePayload.id());
            statement.setInt(5, eventId);
            statement.setArray(6, connection.createArrayOf("integer",
                    sortedPlayerIds.toArray(Integer[]::new)));
            statement.setTimestamp(7, timestamp(sourceFloor));
            return statement;
        });
        return recordedAt;
    }

    /** Requeues a real source failure and durably advances its consecutive-failure count. */
    public void retryFetchFailure(Lease lease, Instant dueAt, String error) {
        finishAttempt(lease, dueAt, error, false, true, false, null);
    }

    /** Applies the failure retry mutation only after validating the lease, in the retry transaction. */
    public void retryFetchFailure(Lease lease, Instant dueAt, String error, Supplier<Instant> retryMutation) {
        finishAttempt(lease, dueAt, error, false, true, false, Objects.requireNonNull(retryMutation));
    }

    public void fail(Lease lease, String error) {
        finishAttempt(lease, null, error, true, false, false, null);
    }

    /** Ends an incremental producer run after a validated canonical source revision supersedes it. */
    void supersedeIncrementalCoreImport(Lease lease) {
        supersedeIncrementalCoreImport(lease, "Superseded by a validated newer canonical source pair");
    }

    void supersedeIncrementalCoreImport(Lease lease, String reason) {
        tx.executeWithoutResult(status -> {
            LockedRun run = lockAndValidate(lease);
            requireIncrementalRun(run);
            Instant supersededAt = now();
            requireNotExpired(run, supersededAt);
            int updated = jdbc.update("""
                    UPDATE %s SET state='SUPERSEDED', last_error=?,
                        failed_at=NULL, completed_at=?, lease_owner=NULL, lease_token=NULL,
                        lease_expires_at=NULL, updated_at=?
                    WHERE generation=? AND state='LEASED' AND lease_owner=? AND lease_token=? AND lease_fence=?
                    """.formatted(tables.run()), boundedNullable(reason, MAX_ERROR_CHARS),
                    timestamp(supersededAt), timestamp(supersededAt), run.generation,
                    lease.owner(), lease.token(), lease.fence());
            if (updated != 1) throw new LeaseLostException("Lease is stale for generation " + run.generation);
        });
    }

    public void failFetch(Lease lease, String error) {
        finishAttempt(lease, null, error, true, true, false, null);
    }

    /** A successful source response ends the consecutive failure sequence before later processing. */
    public void recordFetchSuccess(Lease lease) {
        tx.executeWithoutResult(status -> {
            lockAndValidate(lease);
            jdbc.update("UPDATE %s SET consecutive_fetch_failures=0 WHERE generation=?"
                    .formatted(tables.run()), lease.generation());
        });
    }

    private void finishAttempt(Lease lease, Instant dueAt, String error,
                               boolean terminal, boolean fetchFailure, boolean dependencyWaiting,
                               Supplier<Instant> retryMutation) {
        if (!terminal) Objects.requireNonNull(dueAt);
        tx.executeWithoutResult(status -> {
            LockedRun run = lockAndValidate(lease);
            Instant selectedDueAt = terminal ? null : retryMutation == null
                    ? dueAt : Objects.requireNonNull(retryMutation.get(), "retry mutation due time");
            Instant failedAt = now();
            requireNotExpired(run, failedAt);
            int priority = dependencyWaiting ? SyncKind.DEPENDENCY_WAITING_PRIORITY : run.kind.priority;
            jdbc.update("""
                    UPDATE %s SET state = ?, priority = ?, due_at = COALESCE(?, due_at), last_error = ?,
                        failed_at = ?, consecutive_fetch_failures = consecutive_fetch_failures + ?,
                        lease_owner = NULL, lease_token = NULL,
                        lease_expires_at = NULL, updated_at = ? WHERE generation = ?
                    """.formatted(tables.run()), terminal ? "FAILED" : "PENDING", priority,
                    selectedDueAt == null ? null : timestamp(selectedDueAt), boundedNullable(error, MAX_ERROR_CHARS),
                    terminal ? timestamp(failedAt) : null, fetchFailure ? 1 : 0,
                    timestamp(failedAt), lease.generation());
        });
    }

    /** Deletes old staged bodies only for terminal runs; active work is always retained. */
    public int deleteTerminalPayloadsOlderThan(Duration retention) {
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("retention must be positive");
        }
        Instant cutoff = now().minus(retention);
        int deleted = jdbc.update("""
                DELETE FROM %s p USING %s r
                WHERE p.run_generation = r.generation
                  AND r.state IN ('COMPLETED', 'FAILED') AND p.staged_at < ?
                """.formatted(tables.payload(), tables.run()), timestamp(cutoff));
        if (deleted > 0) {
            synchronized (publicPairCache) {
                publicPairCache.clear();
            }
        }
        return deleted;
    }

    private void evictPublicPairs(List<Long> payloadIds) {
        synchronized (publicPairCache) {
            publicPairCache.keySet().removeIf(key -> payloadIds.contains(key.bootstrapId())
                    || payloadIds.contains(key.fixturesId()));
        }
    }

    /** Reusable metadata-only admission predicate for a fixture run aliased as {@code r}. */
    private String fixtureReadinessPredicate() {
        return """
                EXISTS (
                    SELECT 1
                    FROM %s m
                    JOIN %s b ON b.id = m.bootstrap_payload_id
                              AND b.run_generation = r.generation
                    JOIN %s f ON f.id = m.fixtures_payload_id
                              AND f.run_generation = r.generation
                    JOIN %s l ON l.id = m.live_payload_id
                              AND l.run_generation = r.generation
                    WHERE %s
                      AND NOT EXISTS (
                          SELECT 1 FROM unnest(m.required_player_ids) AS required(player_id)
                          WHERE %s
                      )
                )
                """.formatted(tables.fixtureDependencyManifest(), tables.payload(), tables.payload(),
                tables.payload(), fixtureManifestAnchorConditions("r"),
                missingHistoryEvidencePredicate("r", "required.player_id"));
    }

    /** The exact source, endpoint and validation fences shared by readiness and history preference. */
    private String fixtureManifestAnchorConditions(String runAlias) {
        return """
                m.run_generation = %1$s.generation
                AND %1$s.kind = 'FIXTURE_VERIFICATION'
                AND b.endpoint_kind = 'BOOTSTRAP_STATIC'
                AND b.event_id IS NULL AND b.player_id IS NULL
                AND b.validation_state = 'VALID'
                AND m.source_floor = %1$s.requested_source_after
                AND b.read_started_at >= %1$s.requested_source_after
                AND f.endpoint_kind = 'FIXTURES'
                AND f.event_id IS NULL AND f.player_id IS NULL
                AND f.validation_state = 'VALID'
                AND f.read_started_at >= %1$s.requested_source_after
                AND l.endpoint_kind = 'EVENT_LIVE'
                AND m.event_id > 0 AND l.event_id = m.event_id AND l.player_id IS NULL
                AND l.validation_state = 'VALID'
                AND l.read_started_at >= GREATEST(
                    %1$s.requested_source_after,
                    m.source_floor,
                    f.read_finished_at + INTERVAL '1 millisecond')
                AND fpl_sync_required_ids_valid(m.required_player_ids)
                """.formatted(runAlias);
    }

    /** True only when this exact player lacks valid history at the manifest's readiness floor. */
    private String missingHistoryEvidencePredicate(String fixtureRunAlias, String playerIdExpression) {
        return """
                NOT EXISTS (
                    SELECT 1
                    FROM %s hp
                    JOIN %s hr ON hr.generation = hp.run_generation
                    WHERE hp.endpoint_kind = 'ELEMENT_SUMMARY'
                      AND hp.event_id IS NULL
                      AND hp.player_id = %s
                      AND hp.validation_state = 'VALID'
                      AND hp.read_started_at >= GREATEST(
                          %s.requested_source_after,
                          m.source_floor,
                          f.read_finished_at + INTERVAL '1 millisecond')
                      AND hr.kind = 'PLAYER_HISTORY'
                      AND hr.coalescing_key = 'player-history:' || %s::text
                      AND hr.publication_key = 'player-history:' || %s::text
                      AND hr.season = %s.season
                      AND hr.runtime_mode = %s.runtime_mode
                      AND hr.runtime_epoch = %s.runtime_epoch
                )
                """.formatted(tables.payload(), tables.run(), playerIdExpression,
                fixtureRunAlias, playerIdExpression, playerIdExpression,
                fixtureRunAlias, fixtureRunAlias, fixtureRunAlias);
    }

    private Instant effectiveSourceFloor(Lease lease, Instant callerFloor) {
        Instant durableFloor = jdbc.queryForObject("SELECT requested_source_after FROM %s WHERE generation = ?"
                .formatted(tables.run()), Timestamp.class, lease.generation()).toInstant();
        return callerFloor.isAfter(durableFloor) ? callerFloor : durableFloor;
    }

    private static void requireHttpEndpoint(LockedRun run, Endpoint endpoint) {
        // A fixture publication key holds the fixture id, not the selected event id. The dispatcher
        // owns that association; validate EVENT_LIVE's positive id without equating those domains.
        if (INCREMENTAL_CORE_PRODUCER.equals(run.producerKey)) {
            if (run.kind != SyncKind.PLAYER_HISTORY || !INCREMENTAL_CORE_KEY.equals(run.coalescingKey)
                    || !INCREMENTAL_CORE_KEY.equals(run.publicationKey) || !"ACTIVE".equals(run.runtimeMode)) {
                throw new IllegalArgumentException("Invalid incremental core-import lease");
            }
            return;
        }
        boolean matches = switch (run.kind) {
            case CATALOGUE_SCHEDULE -> isCatalogueRun(run)
                    && (endpoint.kind == EndpointKind.BOOTSTRAP_STATIC
                    || endpoint.kind == EndpointKind.FIXTURES);
            case FIXTURE_VERIFICATION -> canonicalNumericRunKey(run, "fixture:")
                    && (endpoint.kind == EndpointKind.BOOTSTRAP_STATIC
                    || endpoint.kind == EndpointKind.FIXTURES
                    || endpoint.kind == EndpointKind.EVENT_LIVE);
            case PLAYER_HISTORY -> canonicalNumericRunKey(run, "player-history:")
                    && endpoint.kind == EndpointKind.ELEMENT_SUMMARY
                    && endpoint.playerId == numericRunKeyId(run, "player-history:");
            case SOURCE_STATUS -> false;
        };
        if (!matches) throw new IllegalArgumentException("Endpoint does not match leased run");
    }

    private static boolean isCatalogueRun(LockedRun run) {
        boolean catalogue = CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY.equals(run.coalescingKey)
                && CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY.equals(run.publicationKey);
        boolean schedule = CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY.equals(run.coalescingKey)
                && CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY.equals(run.publicationKey);
        return catalogue || schedule;
    }

    private static boolean canonicalNumericRunKey(LockedRun run, String prefix) {
        if (!run.publicationKey.equals(run.coalescingKey)
                || !run.publicationKey.startsWith(prefix)) return false;
        try {
            int id = Integer.parseInt(run.publicationKey.substring(prefix.length()));
            return id > 0 && run.publicationKey.equals(prefix + id);
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static int numericRunKeyId(LockedRun run, String prefix) {
        return Integer.parseInt(run.publicationKey.substring(prefix.length()));
    }

    private DependencyPayload dependencyPayload(Lease lease, StagedPayload supplied,
                                               EndpointKind endpointKind, Integer eventId) {
        if (supplied.id() <= 0 || supplied.runGeneration() != lease.generation()
                || supplied.endpointKind() != endpointKind) {
            throw new IllegalArgumentException("Dependency payload is not a valid own endpoint anchor"
                    + "; expected " + endpointKind + ", got kind=" + supplied.endpointKind()
                    + ", generation=" + supplied.runGeneration());
        }
        List<DependencyPayload> rows = jdbc.query("""
                SELECT id, run_generation, endpoint_kind, event_id, player_id,
                       read_started_at, read_finished_at, validation_state
                FROM %s
                WHERE id = ? AND run_generation = ? AND endpoint_kind = ?
                  AND event_id IS NOT DISTINCT FROM ? AND player_id IS NULL
                  AND validation_state = 'VALID'
                """.formatted(tables.payload()), (rs, row) -> new DependencyPayload(
                rs.getLong("id"), rs.getLong("run_generation"),
                EndpointKind.valueOf(rs.getString("endpoint_kind")),
                (Integer) rs.getObject("event_id"), (Integer) rs.getObject("player_id"),
                new SourceRead(instant(rs, "read_started_at"), instant(rs, "read_finished_at")),
                ValidationState.valueOf(rs.getString("validation_state"))), supplied.id(),
                lease.generation(), endpointKind.name(), eventId);
        if (rows.size() != 1) {
            throw new IllegalArgumentException("Dependency payload is not a valid own endpoint anchor"
                    + " for " + endpointKind);
        }
        DependencyPayload payload = rows.getFirst();
        if (payload.id() != supplied.id() || payload.runGeneration() != supplied.runGeneration()
                || payload.endpointKind() != supplied.endpointKind()
                || !Objects.equals(payload.eventId(), eventId) || payload.playerId() != null
                || !payload.read().equals(postgresTimestampPrecision(supplied.read()))
                || payload.validationState() != ValidationState.VALID) {
            throw new IllegalArgumentException("Dependency payload metadata changed before manifest write");
        }
        return payload;
    }

    private static SourceRead postgresTimestampPrecision(SourceRead read) {
        return new SourceRead(postgresTimestampPrecision(read.startedAt()),
                postgresTimestampPrecision(read.finishedAt()));
    }

    static Instant postgresTimestampPrecision(Instant value) {
        // PostgreSQL rounds timestamptz to microseconds, carrying into the next second when needed.
        long roundedMicros = (value.getNano() + 500L) / 1_000L;
        return Instant.ofEpochSecond(value.getEpochSecond(), roundedMicros * 1_000L);
    }

    private static List<Integer> sortedRequiredPlayerIds(List<Integer> playerIds) {
        if (playerIds == null || playerIds.isEmpty() || playerIds.size() > HistoryFixtureDispatcher.MAX_FIXTURE_PLAYERS
                || playerIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Dependency manifest requires 1 to 80 player ids");
        }
        List<Integer> sorted = playerIds.stream().sorted().toList();
        for (int index = 0; index < sorted.size(); index++) {
            if (sorted.get(index) <= 0 || (index > 0 && sorted.get(index).equals(sorted.get(index - 1)))) {
                throw new IllegalArgumentException("Dependency player ids must be positive and unique");
            }
        }
        return sorted;
    }

    private LockedRun lockAndValidate(Lease lease) {
        Objects.requireNonNull(lease);
        assertRuntimeFence(lease.season());
        LockedRun run;
        try {
            run = jdbc.queryForObject("""
                    SELECT generation, kind, season, coalescing_key, publication_key, producer_key, state,
                           lease_owner, lease_token, lease_fence, lease_expires_at, attempt_count,
                           runtime_mode, runtime_epoch
                    FROM %s WHERE generation = ? FOR UPDATE
                    """.formatted(tables.run()), this::lockedRun, lease.generation());
        } catch (EmptyResultDataAccessException e) {
            throw new LeaseLostException("Run no longer exists: " + lease.generation());
        }
        if (runtimeFence == null && (!OwnedSyncMode.OFF.name().equals(run.runtimeMode)
                || run.runtimeEpoch != 0)) {
            throw new RuntimeFenceLostException("A runtime-bound run requires its activation fence");
        }
        if (runtimeFence != null && (!runtimeFence.mode().name().equals(run.runtimeMode)
                || runtimeFence.epoch() != run.runtimeEpoch)) {
            throw new RuntimeFenceLostException("Run belongs to another owned-sync activation");
        }
        if (!"LEASED".equals(run.state)
                || !lease.owner().equals(run.owner)
                || !lease.token().equals(run.token)
                || lease.fence() != run.fence) {
            throw new LeaseLostException("Lease is stale or expired for generation " + lease.generation());
        }
        requireNotExpired(run, now());
        return run;
    }

    private static void requireNotExpired(LockedRun run, Instant now) {
        if (run.expiresAt == null || !run.expiresAt.isAfter(now)) {
            throw new LeaseLostException("Lease is stale or expired for generation " + run.generation);
        }
    }

    private static void requireReadNotFuture(SourceRead read, Instant now) {
        if (read.startedAt().isAfter(now) || read.finishedAt().isAfter(now)) {
            throw new IllegalArgumentException("source read timestamps cannot be in the future");
        }
    }

    private Run run(ResultSet rs, int row) throws SQLException {
        return new Run(rs.getLong("generation"), SyncKind.valueOf(rs.getString("kind")),
                rs.getString("season"), rs.getString("coalescing_key"),
                rs.getString("publication_key"), instant(rs, "due_at"),
                RunState.valueOf(rs.getString("state")), rs.getInt("attempt_count"));
    }

    private Lease lease(ResultSet rs, int row) throws SQLException {
        return new Lease(rs.getLong("generation"), SyncKind.valueOf(rs.getString("kind")),
                rs.getString("season"), rs.getString("coalescing_key"),
                rs.getString("publication_key"), rs.getString("lease_owner"),
                rs.getObject("lease_token", UUID.class), rs.getLong("lease_fence"),
                instant(rs, "lease_expires_at"), rs.getInt("attempt_count"));
    }

    private LockedRun lockedRun(ResultSet rs, int row) throws SQLException {
        return new LockedRun(rs.getLong("generation"), SyncKind.valueOf(rs.getString("kind")),
                rs.getString("season"), rs.getString("coalescing_key"), rs.getString("publication_key"),
                rs.getString("producer_key"), rs.getString("state"), rs.getString("lease_owner"),
                rs.getObject("lease_token", UUID.class),
                rs.getLong("lease_fence"), instant(rs, "lease_expires_at"), rs.getInt("attempt_count"),
                rs.getString("runtime_mode"), rs.getLong("runtime_epoch"));
    }

    private StagedPayload stagedPayload(ResultSet rs, int row) throws SQLException {
        return new StagedPayload(rs.getLong("id"), rs.getLong("run_generation"),
                EndpointKind.valueOf(rs.getString("endpoint_kind")),
                new SourceRead(instant(rs, "read_started_at"), instant(rs, "read_finished_at")),
                ValidationState.valueOf(rs.getString("validation_state")),
                rs.getString("validation_error"), rs.getBytes("body"));
    }

    private StagedPayloadMetadata stagedPayloadMetadata(ResultSet rs, int row) throws SQLException {
        Endpoint endpoint = new Endpoint(EndpointKind.valueOf(rs.getString("endpoint_kind")),
                (Integer) rs.getObject("event_id"), (Integer) rs.getObject("player_id"));
        return new StagedPayloadMetadata(rs.getLong("id"), rs.getLong("run_generation"), endpoint,
                new SourceRead(instant(rs, "read_started_at"), instant(rs, "read_finished_at")),
                ValidationState.valueOf(rs.getString("validation_state")));
    }

    private static StagedPayload stagedPayload(ResultSet rs, String prefix, EndpointKind kind) throws SQLException {
        return new StagedPayload(rs.getLong(prefix + "_id"), rs.getLong("generation"), kind,
                new SourceRead(instant(rs, prefix + "_started"), instant(rs, prefix + "_finished")),
                ValidationState.valueOf(rs.getString(prefix + "_validation")),
                rs.getString(prefix + "_error"), rs.getBytes(prefix + "_body"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private void assertRuntimeFence(String season) {
        if (runtimeFence == null) return;
        if (!runtimeFence.season().equals(season)) {
            throw new RuntimeFenceLostException("Runtime is not activated for season " + season);
        }
        List<Long> epochs = jdbc.query("SELECT runtime_epoch FROM " + tables.plannerRuntime()
                        + " WHERE instance_id=? AND runtime_mode=? FOR UPDATE",
                (rs, row) -> rs.getLong(1), "mode:" + season, runtimeFence.mode().name());
        if (epochs.size() != 1 || epochs.getFirst() != runtimeFence.epoch()) {
            throw new RuntimeFenceLostException("Owned-sync runtime fence is no longer current for " + season);
        }
    }

    private String runtimeMode() {
        return runtimeFence == null ? OwnedSyncMode.OFF.name() : runtimeFence.mode().name();
    }

    private long runtimeEpoch() {
        return runtimeFence == null ? 0 : runtimeFence.epoch();
    }

    private Instant now() {
        return clock.instant();
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(postgresTimestampPrecision(value));
    }

    private static Timestamp nullableTimestamp(Instant value) {
        return value == null ? null : timestamp(value);
    }

    private static SourceRead combinedSourceRead(SourceRead first, SourceRead second) {
        return new SourceRead(first.startedAt().isBefore(second.startedAt())
                ? first.startedAt() : second.startedAt(),
                first.finishedAt().isAfter(second.finishedAt())
                        ? first.finishedAt() : second.finishedAt());
    }

    private static Instant max(Instant first, Instant second) {
        return first == null || second.isAfter(first) ? second : first;
    }

    private static Duration leaseDuration(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero() || duration.compareTo(MAX_LEASE) > 0) {
            throw new IllegalArgumentException("lease duration must be positive and at most " + MAX_LEASE);
        }
        return duration;
    }

    private static String bounded(String name, String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " must contain 1-" + max + " characters");
        }
        return value;
    }

    private static String boundedNullable(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static void requirePublicEndpoint(Endpoint endpoint) {
        validateEndpoint(endpoint);
        if (endpoint.kind != EndpointKind.BOOTSTRAP_STATIC && endpoint.kind != EndpointKind.FIXTURES) {
            throw new IllegalArgumentException("Only bounded public catalogue payloads can be read here");
        }
    }

    private static void validateEndpoint(Endpoint endpoint) {
        boolean valid = switch (endpoint.kind) {
            case BOOTSTRAP_STATIC, FIXTURES -> endpoint.eventId == null && endpoint.playerId == null;
            case EVENT_LIVE -> endpoint.eventId != null && endpoint.eventId > 0 && endpoint.playerId == null;
            case ELEMENT_SUMMARY -> endpoint.eventId == null && endpoint.playerId != null && endpoint.playerId > 0;
        };
        if (!valid) throw new IllegalArgumentException("Endpoint identifiers do not match " + endpoint.kind);
    }

    private enum ClaimClass {
        HISTORY {
            @Override String predicate(OwnedSyncStore store) {
                return "r.kind='PLAYER_HISTORY'";
            }
        },
        READY_FIXTURE {
            @Override String predicate(OwnedSyncStore store) {
                return "r.kind='FIXTURE_VERIFICATION' AND " + store.fixtureReadinessPredicate();
            }
        },
        BLOCKED_FIXTURE {
            @Override String predicate(OwnedSyncStore store) {
                return fixturePredicate(store, true);
            }
        },
        UNTOUCHED_FIXTURE {
            @Override String predicate(OwnedSyncStore store) {
                return fixturePredicate(store, false);
            }
        };

        abstract String predicate(OwnedSyncStore store);

        private static String fixturePredicate(OwnedSyncStore store, boolean hasEvidence) {
            String manifest = "EXISTS (SELECT 1 FROM " + store.tables.fixtureDependencyManifest()
                    + " m2 WHERE m2.run_generation=r.generation)";
            String payload = "EXISTS (SELECT 1 FROM " + store.tables.payload()
                    + " p2 WHERE p2.run_generation=r.generation)";
            String evidence = hasEvidence ? "(" + manifest + " OR " + payload + ")"
                    : "NOT (" + manifest + " OR " + payload + ")";
            return "r.kind='FIXTURE_VERIFICATION' AND NOT (" + store.fixtureReadinessPredicate()
                    + ") AND " + evidence;
        }
    }

    public enum SyncKind {
        CATALOGUE_SCHEDULE(0), FIXTURE_VERIFICATION(1), SOURCE_STATUS(2), PLAYER_HISTORY(3);
        private static final int DEPENDENCY_WAITING_PRIORITY = 4;
        private final int priority;
        SyncKind(int priority) { this.priority = priority; }
    }

    public enum EndpointKind { BOOTSTRAP_STATIC, FIXTURES, EVENT_LIVE, ELEMENT_SUMMARY }
    public enum ValidationState { UNCHECKED, VALID, INVALID }
    public enum RunState { PENDING, LEASED, COMPLETED, FAILED, SUPERSEDED }

    public record Endpoint(EndpointKind kind, Integer eventId, Integer playerId) {
        public Endpoint { Objects.requireNonNull(kind); }
        public static Endpoint bootstrap() { return new Endpoint(EndpointKind.BOOTSTRAP_STATIC, null, null); }
        public static Endpoint fixtures() { return new Endpoint(EndpointKind.FIXTURES, null, null); }
        public static Endpoint eventLive(int eventId) { return new Endpoint(EndpointKind.EVENT_LIVE, eventId, null); }
        public static Endpoint elementSummary(int playerId) {
            return new Endpoint(EndpointKind.ELEMENT_SUMMARY, null, playerId);
        }
    }

    public record SourceRead(Instant startedAt, Instant finishedAt) {
        public SourceRead {
            Objects.requireNonNull(startedAt);
            Objects.requireNonNull(finishedAt);
            if (finishedAt.isBefore(startedAt)) throw new IllegalArgumentException("source read interval is reversed");
        }
    }

    public record Validation(ValidationState state, String error) {
        public Validation { Objects.requireNonNull(state); }
        public static Validation unchecked() { return new Validation(ValidationState.UNCHECKED, null); }
        public static Validation valid() { return new Validation(ValidationState.VALID, null); }
        public static Validation invalid(String error) { return new Validation(ValidationState.INVALID, error); }
    }

    public record ConditionalPublication<T>(T value, boolean published) {
        public static <T> ConditionalPublication<T> published(T value) {
            return new ConditionalPublication<>(value, true);
        }
        public static <T> ConditionalPublication<T> pending(T value) {
            return new ConditionalPublication<>(value, false);
        }
    }

    public record RuntimeFence(String season, OwnedSyncMode mode, long epoch, Instant sourceBoundary) {
        public RuntimeFence {
            bounded("season", season, 10);
            Objects.requireNonNull(mode);
            Objects.requireNonNull(sourceBoundary);
            if (mode == OwnedSyncMode.OFF || epoch <= 0) throw new IllegalArgumentException("invalid runtime fence");
        }
    }

    public record Run(long generation, SyncKind kind, String season, String coalescingKey,
                      String publicationKey, Instant dueAt, RunState state, int attemptCount) { }

    public record Lease(long generation, SyncKind kind, String season, String coalescingKey,
                        String publicationKey, String owner, UUID token, long fence,
                        Instant expiresAt, int attemptCount) { }

    interface SqlPreparedStatementCreator extends PreparedStatementCreator {
        String sql();
    }

    static final class HistoryHydrationStatement implements SqlPreparedStatementCreator {
        private final String sql;
        private final List<Object> parameters;

        HistoryHydrationStatement(String sql, List<Object> parameters) {
            this.sql = Objects.requireNonNull(sql);
            this.parameters = List.copyOf(parameters);
        }

        @Override public String sql() { return sql; }
        List<Object> parameters() { return parameters; }

        @Override public PreparedStatement createPreparedStatement(Connection connection) throws SQLException {
            if (connection.getAutoCommit()) {
                throw new SQLException("History hydration cursor requires an active transaction");
            }
            PreparedStatement statement = connection.prepareStatement(sql,
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            statement.setFetchSize(HISTORY_HYDRATION_FETCH_SIZE);
            for (int index = 0; index < parameters.size(); index++) {
                statement.setObject(index + 1, parameters.get(index));
            }
            return statement;
        }
    }

    record FixtureHistoryHydration(Map<Integer, CatalogueScheduleParser.SourceDocument> documents,
                                    List<Integer> missingPlayerIds) {
        FixtureHistoryHydration {
            documents = java.util.Collections.unmodifiableMap(documents);
            missingPlayerIds = List.copyOf(missingPlayerIds);
        }

        boolean complete() { return missingPlayerIds.isEmpty(); }
    }

    record CanonicalParentEvidence(boolean currentPublished, boolean fixtureFinished) {
        static final CanonicalParentEvidence MISSING = new CanonicalParentEvidence(false, false);
    }

    private record CanonicalParentCandidate(CanonicalSourcePair sourcePair, String persistedFingerprint,
                                            CanonicalParentEvidence evidence) {
        private boolean fingerprintMatches() {
            return sourcePair.fingerprint().equals(persistedFingerprint);
        }
    }

    public record FixtureDependencyManifest(long runGeneration, String season, String runtimeMode,
                                            long runtimeEpoch, int fixtureId, int eventId,
                                            long bootstrapPayloadId, long fixturesPayloadId, long livePayloadId,
                                            List<Integer> requiredPlayerIds, List<Integer> missingPlayerIds,
                                            Instant manifestSourceFloor, Instant requestedSourceAfter,
                                            Instant effectiveFloor, StagedPayloadMetadata bootstrap,
                                            StagedPayloadMetadata fixtures, StagedPayloadMetadata live) {
        public FixtureDependencyManifest {
            requiredPlayerIds = List.copyOf(requiredPlayerIds);
            missingPlayerIds = List.copyOf(missingPlayerIds);
            Objects.requireNonNull(bootstrap);
            Objects.requireNonNull(fixtures);
            Objects.requireNonNull(live);
        }

        boolean sameAnchorsAndDemand(FixtureDependencyManifest other) {
            return runGeneration == other.runGeneration && season.equals(other.season)
                    && runtimeMode.equals(other.runtimeMode) && runtimeEpoch == other.runtimeEpoch
                    && fixtureId == other.fixtureId && eventId == other.eventId
                    && bootstrapPayloadId == other.bootstrapPayloadId
                    && fixturesPayloadId == other.fixturesPayloadId && livePayloadId == other.livePayloadId
                    && requiredPlayerIds.equals(other.requiredPlayerIds)
                    && manifestSourceFloor.equals(other.manifestSourceFloor)
                    && requestedSourceAfter.equals(other.requestedSourceAfter)
                    && effectiveFloor.equals(other.effectiveFloor)
                    && bootstrap.read().equals(other.bootstrap.read())
                    && fixtures.read().equals(other.fixtures.read())
                    && live.read().equals(other.live.read());
        }
    }

    public sealed interface HttpFenceResult permits Ready, FutureFloor { }

    public record Ready(Instant expiresAt, Instant sourceFloor) implements HttpFenceResult {
        public Ready {
            Objects.requireNonNull(expiresAt);
            Objects.requireNonNull(sourceFloor);
        }
    }

    public record FutureFloor(Instant nextDue) implements HttpFenceResult {
        public FutureFloor { Objects.requireNonNull(nextDue); }
    }

    record StagedPayloadMetadata(long id, long runGeneration, Endpoint endpoint,
                                 SourceRead read, ValidationState validationState) {
        StagedPayloadMetadata {
            if (id <= 0 || runGeneration <= 0) throw new IllegalArgumentException("invalid payload metadata id");
            Objects.requireNonNull(endpoint);
            Objects.requireNonNull(read);
            Objects.requireNonNull(validationState);
            validateEndpoint(endpoint);
        }
    }

    public record StagedPayload(long id, long runGeneration, EndpointKind endpointKind,
                                SourceRead read, ValidationState validationState,
                                String validationError, byte[] body) {
        public StagedPayload {
            Objects.requireNonNull(endpointKind);
            Objects.requireNonNull(read);
            Objects.requireNonNull(validationState);
            body = Objects.requireNonNull(body).clone();
            if (body.length > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("staged body is oversized");
        }
        @Override public byte[] body() { return body.clone(); }
    }

    record CanonicalSourcePayload(long id, long runGeneration, EndpointKind endpointKind,
                                  SourceRead read, String sha256) {
        CanonicalSourcePayload {
            if (id <= 0 || runGeneration <= 0) throw new IllegalArgumentException("invalid source payload identity");
            Objects.requireNonNull(endpointKind);
            read = postgresTimestampPrecision(Objects.requireNonNull(read));
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid source payload hash");
            }
        }
    }

    record CanonicalSourcePair(CanonicalSourcePayload bootstrap, CanonicalSourcePayload fixtures,
                               String fingerprint) {
        CanonicalSourcePair {
            Objects.requireNonNull(bootstrap);
            Objects.requireNonNull(fixtures);
            if (bootstrap.endpointKind() != EndpointKind.BOOTSTRAP_STATIC
                    || fixtures.endpointKind() != EndpointKind.FIXTURES
                    || bootstrap.runGeneration() != fixtures.runGeneration()
                    || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")
                    || !fingerprint.equals(canonicalFingerprint(bootstrap, fixtures))) {
                throw new IllegalArgumentException("invalid canonical source pair");
            }
        }

        static CanonicalSourcePair from(StagedPayload bootstrap, StagedPayload fixtures) {
            CanonicalSourcePayload b = new CanonicalSourcePayload(bootstrap.id(), bootstrap.runGeneration(),
                    bootstrap.endpointKind(), bootstrap.read(), sha256(bootstrap.body()));
            CanonicalSourcePayload f = new CanonicalSourcePayload(fixtures.id(), fixtures.runGeneration(),
                    fixtures.endpointKind(), fixtures.read(), sha256(fixtures.body()));
            return of(b, f);
        }

        static CanonicalSourcePair of(CanonicalSourcePayload bootstrap, CanonicalSourcePayload fixtures) {
            return new CanonicalSourcePair(bootstrap, fixtures, canonicalFingerprint(bootstrap, fixtures));
        }

        private static String canonicalFingerprint(CanonicalSourcePayload bootstrap,
                                                   CanonicalSourcePayload fixtures) {
            String canonical = String.join("|", Long.toString(bootstrap.id()),
                    Long.toString(bootstrap.runGeneration()), bootstrap.endpointKind().name(),
                    bootstrap.read().startedAt().toString(), bootstrap.read().finishedAt().toString(),
                    bootstrap.sha256(), Long.toString(fixtures.id()), Long.toString(fixtures.runGeneration()),
                    fixtures.endpointKind().name(), fixtures.read().startedAt().toString(),
                    fixtures.read().finishedAt().toString(), fixtures.sha256());
            return sha256(canonical);
        }
    }

    static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record PublicPayloadPair(long runGeneration, StagedPayload bootstrap,
                                    StagedPayload fixtures) {
        public PublicPayloadPair {
            Objects.requireNonNull(bootstrap);
            Objects.requireNonNull(fixtures);
            if (bootstrap.runGeneration() != runGeneration || fixtures.runGeneration() != runGeneration
                    || bootstrap.endpointKind() != EndpointKind.BOOTSTRAP_STATIC
                    || fixtures.endpointKind() != EndpointKind.FIXTURES) {
                throw new IllegalArgumentException("public payload pair must come from one run");
            }
        }
    }

    private record PublicPayloadPairMetadata(long runGeneration, long bootstrapId, long fixturesId,
                                             Instant bootstrapStartedAt, Instant fixturesStartedAt) { }
    private record PublicPayloadPairCacheKey(String season, long runGeneration, long bootstrapId,
                                             long fixturesId, boolean fenced,
                                             String runtimeMode, long runtimeEpoch) { }

    record CurrentPublishedCanonicalPair(PublicPayloadPair pair, CanonicalSourcePair sourcePair,
                                         CanonicalPublication publication) { }

    private record SeasonIdentity(int id, String code) { }
    private record SeasonCalendar(java.time.LocalDate startsOn, java.time.LocalDate endsOn) { }
    private record CompatibilityReceipt(Long generation, String fingerprint, Long bootstrapPayloadId,
                                        Long fixturesPayloadId, Instant sourceStartedAt,
                                        Instant sourceFinishedAt) { }
    private record IncrementalSourcePayload(StagedPayload payload, Integer eventId, Integer playerId,
                                            boolean sourcePublished) {
        IncrementalSourcePayload(StagedPayload payload, Integer eventId, Integer playerId) {
            this(payload, eventId, playerId, false);
        }
    }

    record IncrementalCompatibilitySnapshot(CanonicalSourcePair pair, StagedPayload bootstrap,
                                            StagedPayload fixtures, SourceRead sourceRead) { }

    private record IncrementalCompatibilityReceipt(Long generation, Instant sourceStartedAt,
            Instant sourceFinishedAt, Long bootstrapId, Long fixturesId, Instant bootstrapStartedAt,
            Instant bootstrapFinishedAt, Instant fixturesStartedAt, Instant fixturesFinishedAt,
            String bootstrapHash, String fixturesHash, String fingerprint) {
        SourceRead sourceRead() {
            return sourceStartedAt == null || sourceFinishedAt == null
                    ? null : new SourceRead(sourceStartedAt, sourceFinishedAt);
        }
        SourceRead bootstrapRead() {
            return bootstrapStartedAt == null || bootstrapFinishedAt == null
                    ? null : new SourceRead(bootstrapStartedAt, bootstrapFinishedAt);
        }
        SourceRead fixturesRead() {
            return fixturesStartedAt == null || fixturesFinishedAt == null
                    ? null : new SourceRead(fixturesStartedAt, fixturesFinishedAt);
        }
    }

    private record PageWatermark(Long generation, Instant startedAt, Instant finishedAt, Instant sourceFloor,
                                 String producerKey, String coalescingKey, String publicationKey) { }
    private record SnapshotReceipt(Long generation, String fingerprint) { }
    private record SourceFixtureRevision(String fingerprint, Instant finishedAt) { }
    private record GameweekSyncRow(String state, String sourceSignature, Instant firstCheckedAt,
                                   long invalidationRevision, Instant invalidationFloor,
                                   String finalizedSourceSignature, Long finalizedInvalidationRevision) { }

    record IncrementalEvent(int gameweek, String state, Instant firstCheckedAt, String sourceSignature) {
        boolean finalPending() { return "FINAL_PENDING".equals(state); }
        boolean finalized() { return "FINALIZED".equals(state); }
    }

    record IncrementalDiscovery(Map<Integer, IncrementalEvent> events,
                                Map<Integer, Instant> finishedObservationTimes) {
        IncrementalDiscovery {
            events = Map.copyOf(events);
            finishedObservationTimes = Map.copyOf(finishedObservationTimes);
        }
    }

    record PageReceipt(long generation, SourceRead read) { }

    record IncrementalAppearanceState(String fullFixtureFingerprint, Instant invalidationFloor) {
        IncrementalAppearanceState {
            if (fullFixtureFingerprint == null || !fullFixtureFingerprint.matches("[0-9a-f]{64}")
                    || invalidationFloor == null) {
                throw new IllegalArgumentException("Invalid persisted appearance invalidation state");
            }
        }
    }

    private record AppearanceStateReceipt(long generation, Instant startedAt, Instant finishedAt,
                                          String fingerprint) { }

    private record LockedRun(long generation, SyncKind kind, String season, String coalescingKey,
                             String publicationKey, String producerKey, String state, String owner, UUID token,
                             long fence, Instant expiresAt, int attemptCount,
                             String runtimeMode, long runtimeEpoch) { }

    private record DependencyPayload(long id, long runGeneration, EndpointKind endpointKind,
                                     Integer eventId, Integer playerId, SourceRead read,
                                     ValidationState validationState) { }

    private record CanonicalSourceMetadata(long bootstrapId, long bootstrapGeneration, SourceRead bootstrapRead,
                                           long fixturesId, long fixturesGeneration, SourceRead fixturesRead) { }

    record CanonicalPublication(long sourceGeneration, long publicationGeneration, String sourceFingerprint) { }

    private record AnchorIdentity(long generation, long bootstrapId, long fixturesId, long liveId,
                                  int eventId, Instant bootstrapStarted, Instant bootstrapFinished,
                                  Instant fixturesStarted, Instant fixturesFinished,
                                  Instant liveStarted, Instant liveFinished) {
        SourceRead bootstrapRead() { return new SourceRead(bootstrapStarted, bootstrapFinished); }
        SourceRead fixturesRead() { return new SourceRead(fixturesStarted, fixturesFinished); }
        SourceRead liveRead() { return new SourceRead(liveStarted, liveFinished); }
    }

    private record HistoryIdentity(long id, long runGeneration, int playerId, SourceRead read) { }

    public static class FixtureEvidenceChangedException extends IllegalStateException {
        public FixtureEvidenceChangedException(String message) { super(message); }
    }

    public static class LeaseLostException extends IllegalStateException {
        public LeaseLostException(String message) { super(message); }
    }

    public static final class RuntimeFenceLostException extends LeaseLostException {
        public RuntimeFenceLostException(String message) { super(message); }
    }

    public static final class SourceDemandAdvancedException extends IllegalStateException {
        public SourceDemandAdvancedException(long generation, Instant requestedAfter) {
            super("Run " + generation + " requires source requests from " + requestedAfter + " or later");
        }
    }

    public static final class StaleGenerationException extends IllegalStateException {
        public StaleGenerationException(long attempted, long latest, String season, String key) {
            super("Generation " + attempted + " is older than receipt " + latest
                    + " for " + season + "/" + key);
        }
    }

    public static final class PayloadRetentionLimitException extends IllegalStateException {
        public PayloadRetentionLimitException(long generation, long retainedBytes, int attemptedBytes) {
            super("Run " + generation + " retains " + retainedBytes + " bytes; staging " + attemptedBytes
                    + " would exceed the " + MAX_RETAINED_PAYLOAD_BYTES_PER_RUN + " byte per-run limit");
        }
    }
}
