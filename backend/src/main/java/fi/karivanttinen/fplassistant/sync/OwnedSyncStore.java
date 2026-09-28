package fi.karivanttinen.fplassistant.sync;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private static final Duration MAX_LEASE = Duration.ofHours(1);
    private static final int PUBLIC_PAIR_CACHE_ENTRIES = 2;
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
                    DO UPDATE SET due_at = LEAST(%s.due_at, EXCLUDED.due_at),
                                  requested_source_after = GREATEST(
                                      %s.requested_source_after, EXCLUDED.requested_source_after),
                                  updated_at = EXCLUDED.updated_at
                    WHERE %s.publication_key = EXCLUDED.publication_key
                    RETURNING generation, kind, season, coalescing_key, publication_key,
                              due_at, state, attempt_count
                    """.formatted(tables.run(), tables.run(), tables.run(), tables.run()), this::run,
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

    /** Executes a non-run runtime mutation while holding the durable activation fence. */
    public <T> T withinRuntimeFence(Supplier<T> callback) {
        Objects.requireNonNull(callback);
        if (runtimeFence == null) return callback.get();
        return tx.execute(status -> {
            assertRuntimeFence(runtimeFence.season());
            return callback.get();
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
                    ORDER BY r.priority, r.due_at, r.generation
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
                        WHERE season=? AND runtime_mode=? AND runtime_epoch=?
                          AND kind IN ('PLAYER_HISTORY', 'FIXTURE_VERIFICATION')
                        """.formatted(tables.run()), Long.class, validSeason, runtimeMode(), runtimeEpoch());
                int firstTurn = (int) (attempts % FAIR_CYCLE.size());
                LinkedHashSet<ClaimClass> borrowingOrder = new LinkedHashSet<>();
                for (int offset = 0; offset < FAIR_CYCLE.size(); offset++) {
                    borrowingOrder.add(FAIR_CYCLE.get((firstTurn + offset) % FAIR_CYCLE.size()));
                }
                for (ClaimClass claimClass : borrowingOrder) {
                    generation = fairCandidate(validSeason, checkedAt, claimClass.predicate(this));
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
        String sql = """
                SELECT r.generation
                FROM %s r
                WHERE r.season=? AND r.runtime_mode=? AND r.runtime_epoch=?
                  AND r.due_at<=? AND r.requested_source_after<=?
                  AND (r.state='PENDING' OR (r.state='LEASED' AND r.lease_expires_at<=?))
                  AND (%s)
                ORDER BY r.priority, r.due_at, r.generation
                LIMIT 1 FOR UPDATE OF r SKIP LOCKED
                """.formatted(tables.run(), predicate);
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
                        WHERE due_at <= ?%s%s
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
        return jdbc.query("""
                SELECT p.id, p.run_generation, p.endpoint_kind, p.event_id, p.player_id,
                       p.read_started_at, p.read_finished_at, p.validation_state
                FROM %s p JOIN %s r ON r.generation=p.run_generation
                WHERE r.season=? AND p.endpoint_kind=?
                  AND p.event_id IS NOT DISTINCT FROM ? AND p.player_id IS NOT DISTINCT FROM ?
                  AND p.validation_state='VALID' AND p.read_started_at >= ?
                  AND (? = FALSE OR (r.runtime_mode=? AND r.runtime_epoch=?))
                ORDER BY p.read_finished_at DESC, p.id DESC LIMIT 1
                """.formatted(tables.payload(), tables.run()), this::stagedPayloadMetadata, validSeason,
                endpoint.kind().name(), endpoint.eventId(), endpoint.playerId(),
                timestamp(requestedSourceAfter), runtimeFence != null,
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
                    WHERE p.id = ? AND p.run_generation = ? AND r.season = ?
                      AND p.endpoint_kind = ?
                      AND p.event_id IS NOT DISTINCT FROM ? AND p.player_id IS NOT DISTINCT FROM ?
                      AND p.validation_state = 'VALID' AND p.read_started_at >= ?
                      AND (? = FALSE OR (r.runtime_mode=? AND r.runtime_epoch=?))
                    """.formatted(tables.payload(), tables.run()), this::stagedPayload, candidate.id(),
                    candidate.runGeneration(), validSeason, endpoint.kind().name(), endpoint.eventId(),
                    endpoint.playerId(), timestamp(effectiveFloor), runtimeFence != null,
                    runtimeMode(), runtimeEpoch()).stream().findFirst();
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
                WHERE r.kind = 'CATALOGUE_SCHEDULE' AND r.season = ?
                  AND (? = FALSE OR (r.runtime_mode=? AND r.runtime_epoch=?))
                ORDER BY GREATEST(b.read_finished_at, f.read_finished_at) DESC, r.generation DESC
                LIMIT ?
                """.formatted(tables.run(), tables.payload(), tables.payload()), (rs, row) ->
                new PublicPayloadPairMetadata(rs.getLong("generation"), rs.getLong("b_id"),
                        rs.getLong("f_id"), instant(rs, "b_started"), instant(rs, "f_started")),
                validSeason, runtimeFence != null, runtimeMode(), runtimeEpoch(), limit);
        for (PublicPayloadPairMetadata candidate : candidates) {
            if (minimumReadStart != null && (candidate.bootstrapStartedAt().isBefore(minimumReadStart)
                    || candidate.fixturesStartedAt().isBefore(minimumReadStart))) continue;
            Optional<PublicPayloadPair> pair = cachedOrLoadPublicPayloadPair(validSeason, candidate);
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
                    WHERE r.generation=? AND r.kind='CATALOGUE_SCHEDULE' AND r.season=?
                      AND (? = FALSE OR (r.runtime_mode=? AND r.runtime_epoch=?))
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
                WHERE r.generation=? AND r.kind='CATALOGUE_SCHEDULE' AND r.season=?
                  AND (? = FALSE OR (r.runtime_mode=? AND r.runtime_epoch=?))
                """.formatted(tables.run(), tables.payload(), tables.payload()), (rs, row) ->
                new PublicPayloadPair(rs.getLong("generation"),
                        stagedPayload(rs, "b", EndpointKind.BOOTSTRAP_STATIC),
                        stagedPayload(rs, "f", EndpointKind.FIXTURES)), candidate.bootstrapId(),
                candidate.fixturesId(), candidate.runGeneration(), season, runtimeFence != null,
                runtimeMode(), runtimeEpoch()).stream().findFirst();
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
        Objects.requireNonNull(callback);
        return publishConditionally(lease, read, demandReads,
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
            ConditionalPublication<T> outcome = Objects.requireNonNull(callback.get(), "callback outcome");
            Instant completedAt = now();
            requireNotExpired(run, completedAt);
            if (outcome.published()) {
                jdbc.update("""
                        UPDATE %s SET latest_generation = ?, source_read_started_at = ?,
                            source_read_finished_at = ?, published_at = ?
                        WHERE season = ? AND publication_key = ?
                        """.formatted(tables.receipt()), run.generation, timestamp(read.startedAt()),
                        timestamp(read.finishedAt()), timestamp(completedAt), run.season, run.publicationKey);
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

    /** Requeues work without treating admission or scheduling delay as a failed source fetch. */
    public void retry(Lease lease, Instant dueAt, String error) {
        finishAttempt(lease, dueAt, error, false, false, false);
    }

    /** Requeues a dependent behind the work needed to make it ready. */
    public void retryForDependency(Lease lease, Instant dueAt, String error) {
        finishAttempt(lease, dueAt, error, false, false, true);
    }

    /** Writes the fixture's validated history dependency manifest and releases its lease atomically. */
    public void retryForDependency(Lease lease, Instant dueAt, String error, int fixtureId, int eventId,
                                   PublicPayloadPair pair, StagedPayload live,
                                   List<Integer> requiredPlayerIds) {
        Objects.requireNonNull(lease);
        Objects.requireNonNull(dueAt);
        Objects.requireNonNull(pair);
        Objects.requireNonNull(live);
        List<Integer> sortedPlayerIds = sortedRequiredPlayerIds(requiredPlayerIds);
        if (fixtureId <= 0 || eventId <= 0) {
            throw new IllegalArgumentException("fixture and event ids must be positive");
        }

        tx.executeWithoutResult(status -> {
            LockedRun run = lockAndValidate(lease);
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
            DependencyPayload livePayload = dependencyPayload(lease, live,
                    EndpointKind.EVENT_LIVE, eventId);

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
                                       live_payload_id, required_player_ids, source_floor)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (run_generation) DO UPDATE SET
                            bootstrap_payload_id = EXCLUDED.bootstrap_payload_id,
                            fixtures_payload_id = EXCLUDED.fixtures_payload_id,
                            live_payload_id = EXCLUDED.live_payload_id,
                            required_player_ids = EXCLUDED.required_player_ids,
                            source_floor = EXCLUDED.source_floor
                        """.formatted(tables.fixtureDependencyManifest()));
                statement.setLong(1, run.generation);
                statement.setLong(2, bootstrap.id());
                statement.setLong(3, fixtures.id());
                statement.setLong(4, livePayload.id());
                statement.setArray(5, connection.createArrayOf("integer",
                        sortedPlayerIds.toArray(Integer[]::new)));
                statement.setTimestamp(6, timestamp(sourceFloor));
                return statement;
            });
            int released = jdbc.update("""
                    UPDATE %s SET state = 'PENDING', priority = ?, due_at = ?, last_error = ?,
                        failed_at = NULL, lease_owner = NULL, lease_token = NULL,
                        lease_expires_at = NULL, updated_at = ?
                    WHERE generation = ? AND state = 'LEASED' AND lease_owner = ?
                      AND lease_token = ? AND lease_fence = ?
                    """.formatted(tables.run()), SyncKind.DEPENDENCY_WAITING_PRIORITY,
                    timestamp(dueAt), boundedNullable(error, MAX_ERROR_CHARS), timestamp(recordedAt),
                    run.generation, lease.owner(), lease.token(), lease.fence());
            if (released != 1) {
                throw new LeaseLostException("Lease is stale for generation " + run.generation);
            }
        });
    }

    /** Requeues a real source failure and durably advances its consecutive-failure count. */
    public void retryFetchFailure(Lease lease, Instant dueAt, String error) {
        finishAttempt(lease, dueAt, error, false, true, false);
    }

    public void fail(Lease lease, String error) {
        finishAttempt(lease, null, error, true, false, false);
    }

    public void failFetch(Lease lease, String error) {
        finishAttempt(lease, null, error, true, true, false);
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
                               boolean terminal, boolean fetchFailure, boolean dependencyWaiting) {
        if (!terminal) Objects.requireNonNull(dueAt);
        tx.executeWithoutResult(status -> {
            LockedRun run = lockAndValidate(lease);
            Instant failedAt = now();
            int priority = dependencyWaiting ? SyncKind.DEPENDENCY_WAITING_PRIORITY : run.kind.priority;
            jdbc.update("""
                    UPDATE %s SET state = ?, priority = ?, due_at = COALESCE(?, due_at), last_error = ?,
                        failed_at = ?, consecutive_fetch_failures = consecutive_fetch_failures + ?,
                        lease_owner = NULL, lease_token = NULL,
                        lease_expires_at = NULL, updated_at = ? WHERE generation = ?
                    """.formatted(tables.run()), terminal ? "FAILED" : "PENDING", priority,
                    dueAt == null ? null : timestamp(dueAt), boundedNullable(error, MAX_ERROR_CHARS),
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
                    WHERE m.run_generation = r.generation
                      AND r.kind = 'FIXTURE_VERIFICATION'
                      AND b.endpoint_kind = 'BOOTSTRAP_STATIC'
                      AND b.event_id IS NULL AND b.player_id IS NULL
                      AND b.validation_state = 'VALID'
                      AND b.read_started_at >= r.requested_source_after
                      AND f.endpoint_kind = 'FIXTURES'
                      AND f.event_id IS NULL AND f.player_id IS NULL
                      AND f.validation_state = 'VALID'
                      AND f.read_started_at >= r.requested_source_after
                      AND l.endpoint_kind = 'EVENT_LIVE'
                      AND l.event_id > 0 AND l.player_id IS NULL
                      AND l.validation_state = 'VALID'
                      AND l.read_started_at >= GREATEST(
                          r.requested_source_after,
                          f.read_finished_at + INTERVAL '1 millisecond')
                      AND fpl_sync_required_ids_valid(m.required_player_ids)
                      AND NOT EXISTS (
                          SELECT 1
                          FROM unnest(m.required_player_ids) AS required(player_id)
                          WHERE NOT EXISTS (
                              SELECT 1
                              FROM %s hp
                              JOIN %s hr ON hr.generation = hp.run_generation
                              WHERE hp.endpoint_kind = 'ELEMENT_SUMMARY'
                                AND hp.event_id IS NULL
                                AND hp.player_id = required.player_id
                                AND hp.validation_state = 'VALID'
                                AND hp.read_started_at >= GREATEST(
                                    r.requested_source_after,
                                    m.source_floor,
                                    f.read_finished_at + INTERVAL '1 millisecond')
                                AND hr.kind = 'PLAYER_HISTORY'
                                AND hr.coalescing_key = 'player-history:' || required.player_id::text
                                AND hr.publication_key = 'player-history:' || required.player_id::text
                                AND hr.season = r.season
                                AND hr.runtime_mode = r.runtime_mode
                                AND hr.runtime_epoch = r.runtime_epoch
                          )
                      )
                )
                """.formatted(tables.fixtureDependencyManifest(), tables.payload(), tables.payload(),
                tables.payload(), tables.payload(), tables.run());
    }

    private Instant effectiveSourceFloor(Lease lease, Instant callerFloor) {
        Instant durableFloor = jdbc.queryForObject("SELECT requested_source_after FROM %s WHERE generation = ?"
                .formatted(tables.run()), Timestamp.class, lease.generation()).toInstant();
        return callerFloor.isAfter(durableFloor) ? callerFloor : durableFloor;
    }

    private static void requireHttpEndpoint(LockedRun run, Endpoint endpoint) {
        // A fixture publication key holds the fixture id, not the selected event id. The dispatcher
        // owns that association; validate EVENT_LIVE's positive id without equating those domains.
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

    private static Instant postgresTimestampPrecision(Instant value) {
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
                    SELECT generation, kind, season, coalescing_key, publication_key, state,
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
                rs.getString("state"), rs.getString("lease_owner"), rs.getObject("lease_token", UUID.class),
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
        return Timestamp.from(value);
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
    public enum RunState { PENDING, LEASED, COMPLETED, FAILED }

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

    private record LockedRun(long generation, SyncKind kind, String season, String coalescingKey,
                             String publicationKey, String state, String owner, UUID token,
                             long fence, Instant expiresAt, int attemptCount,
                             String runtimeMode, long runtimeEpoch) { }

    private record DependencyPayload(long id, long runGeneration, EndpointKind endpointKind,
                                     Integer eventId, Integer playerId, SourceRead read,
                                     ValidationState validationState) { }

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
