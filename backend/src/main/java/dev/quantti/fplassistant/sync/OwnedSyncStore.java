package dev.quantti.fplassistant.sync;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final OwnedSyncTables tables;

    public OwnedSyncStore(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock, OwnedSyncTables tables) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.tx = Objects.requireNonNull(tx);
        this.clock = Objects.requireNonNull(clock);
        this.tables = Objects.requireNonNull(tables);
    }

    public Run enqueue(SyncKind kind, String season, String coalescingKey,
                       String publicationKey, Instant dueAt) {
        Objects.requireNonNull(kind);
        String validSeason = bounded("season", season, 10);
        String validCoalescingKey = bounded("coalescingKey", coalescingKey, 200);
        String validPublicationKey = bounded("publicationKey", publicationKey, 200);
        Objects.requireNonNull(dueAt);
        return tx.execute(status -> {
            Instant enqueuedAt = now();
            Instant requestedSourceAfter = dueAt.isAfter(enqueuedAt) ? dueAt : enqueuedAt;
            jdbc.update("INSERT INTO %s(season, publication_key) VALUES (?, ?) ON CONFLICT DO NOTHING"
                    .formatted(tables.receipt()), validSeason, validPublicationKey);
            Run result;
            try {
                result = jdbc.queryForObject("""
                    INSERT INTO %s(kind, season, coalescing_key, publication_key, priority,
                                   due_at, requested_source_after, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                        kind.name(), validSeason, validCoalescingKey, validPublicationKey, kind.priority,
                        timestamp(dueAt), timestamp(requestedSourceAfter), timestamp(enqueuedAt), timestamp(enqueuedAt));
            } catch (EmptyResultDataAccessException e) {
                throw new IllegalArgumentException(
                        "Active coalescing key is already bound to a different publicationKey");
            }
            return result;
        });
    }

    /** Claims one due run. Priority, due time and generation make selection deterministic. */
    public Optional<Lease> acquire(String owner, Duration duration) {
        String validOwner = bounded("owner", owner, 128);
        Duration validDuration = leaseDuration(duration);
        Instant now = now();
        return tx.execute(status -> {
            Long generation;
            try {
                generation = jdbc.queryForObject("""
                        SELECT generation FROM %s
                        WHERE due_at <= ?
                          AND (state = 'PENDING'
                               OR (state = 'LEASED' AND lease_expires_at <= ?))
                        ORDER BY priority, due_at, generation
                        LIMIT 1 FOR UPDATE SKIP LOCKED
                        """.formatted(tables.run()), Long.class, timestamp(now), timestamp(now));
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
        Objects.requireNonNull(endpoint);
        requirePublicEndpoint(endpoint);
        return tx.execute(status -> {
            lockAndValidate(lease);
            return jdbc.query("""
                    SELECT id, run_generation, endpoint_kind, read_started_at, read_finished_at,
                           validation_state, validation_error, body
                    FROM %s WHERE run_generation = ? AND endpoint_kind = ?
                    ORDER BY id DESC LIMIT 1
                    """.formatted(tables.payload()), this::stagedPayload,
                    lease.generation(), endpoint.kind().name()).stream().findFirst();
        });
    }

    /**
     * Returns a bounded newest-first set of complete, validated bootstrap/fixtures batches.
     * Both documents always come from the same run; callers still parse the pair before use.
     */
    public List<PublicPayloadPair> latestValidPublicPayloadPairs(String season, int limit) {
        String validSeason = bounded("season", season, 10);
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("limit must be between 1 and 20");
        return jdbc.query("""
                SELECT r.generation,
                       b.id AS b_id, b.read_started_at AS b_started, b.read_finished_at AS b_finished,
                       b.validation_state AS b_validation, b.validation_error AS b_error, b.body AS b_body,
                       f.id AS f_id, f.read_started_at AS f_started, f.read_finished_at AS f_finished,
                       f.validation_state AS f_validation, f.validation_error AS f_error, f.body AS f_body
                FROM %s r
                JOIN LATERAL (
                    SELECT * FROM %s p WHERE p.run_generation = r.generation
                      AND p.endpoint_kind = 'BOOTSTRAP_STATIC' AND p.validation_state = 'VALID'
                    ORDER BY p.id DESC LIMIT 1
                ) b ON TRUE
                JOIN LATERAL (
                    SELECT * FROM %s p WHERE p.run_generation = r.generation
                      AND p.endpoint_kind = 'FIXTURES' AND p.validation_state = 'VALID'
                    ORDER BY p.id DESC LIMIT 1
                ) f ON TRUE
                WHERE r.kind = 'CATALOGUE_SCHEDULE' AND r.season = ?
                ORDER BY GREATEST(b.read_finished_at, f.read_finished_at) DESC, r.generation DESC
                LIMIT ?
                """.formatted(tables.run(), tables.payload(), tables.payload()), (rs, row) ->
                new PublicPayloadPair(rs.getLong("generation"),
                        stagedPayload(rs, "b", EndpointKind.BOOTSTRAP_STATIC),
                        stagedPayload(rs, "f", EndpointKind.FIXTURES)), validSeason, limit);
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

    /**
     * Runs a database-only publication callback and records completion in the same transaction.
     * The callback must perform no network I/O. Any exception rolls back callback writes, receipt
     * advancement and completion together.
     */
    public <T> T publish(Lease lease, SourceRead read, Supplier<T> callback) {
        return publish(lease, read, List.of(), callback);
    }

    /**
     * Publishes only if every supplied source request began at or after the run's current durable
     * demand floor. The run lock makes this check atomic with completion against concurrent
     * coalescing; a newer demand can therefore never be hidden by an in-flight older fetch.
     */
    public <T> T publish(Lease lease, SourceRead read, List<SourceRead> demandReads,
                         Supplier<T> callback) {
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
            // The receipt lock may have blocked. Expiry is checked again at the last boundary
            // before user publication code can run.
            Instant publicationStartedAt = now();
            requireNotExpired(run, publicationStartedAt);
            requireReadNotFuture(read, publicationStartedAt);
            if (latest != null && latest >= run.generation) {
                throw new StaleGenerationException(run.generation, latest, run.season, run.publicationKey);
            }
            T result = callback.get();
            Instant publishedAt = now();
            // A callback is database-only and expected to be short, but expiry is still a hard
            // commit fence: rolling the transaction back is safer than publishing after loss.
            requireNotExpired(run, publishedAt);
            jdbc.update("""
                    UPDATE %s SET latest_generation = ?, source_read_started_at = ?,
                        source_read_finished_at = ?, published_at = ?
                    WHERE season = ? AND publication_key = ?
                    """.formatted(tables.receipt()), run.generation, timestamp(read.startedAt()),
                    timestamp(read.finishedAt()), timestamp(publishedAt), run.season, run.publicationKey);
            jdbc.update("""
                    UPDATE %s SET state = 'COMPLETED', lease_owner = NULL, lease_token = NULL,
                        lease_expires_at = NULL, source_read_started_at = ?, source_read_finished_at = ?,
                        last_error = NULL, completed_at = ?, updated_at = ? WHERE generation = ?
                    """.formatted(tables.run()), timestamp(read.startedAt()), timestamp(read.finishedAt()),
                    timestamp(publishedAt), timestamp(publishedAt), run.generation);
            return result;
        });
    }

    public void retry(Lease lease, Instant dueAt, String error) {
        finishAttempt(lease, dueAt, error, false);
    }

    public void fail(Lease lease, String error) {
        finishAttempt(lease, null, error, true);
    }

    private void finishAttempt(Lease lease, Instant dueAt, String error, boolean terminal) {
        if (!terminal) Objects.requireNonNull(dueAt);
        tx.executeWithoutResult(status -> {
            lockAndValidate(lease);
            Instant failedAt = now();
            jdbc.update("""
                    UPDATE %s SET state = ?, due_at = COALESCE(?, due_at), last_error = ?,
                        failed_at = ?, lease_owner = NULL, lease_token = NULL,
                        lease_expires_at = NULL, updated_at = ? WHERE generation = ?
                    """.formatted(tables.run()), terminal ? "FAILED" : "PENDING",
                    dueAt == null ? null : timestamp(dueAt), boundedNullable(error, MAX_ERROR_CHARS),
                    terminal ? timestamp(failedAt) : null, timestamp(failedAt), lease.generation());
        });
    }

    /** Deletes old staged bodies only for terminal runs; active work is always retained. */
    public int deleteTerminalPayloadsOlderThan(Duration retention) {
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("retention must be positive");
        }
        Instant cutoff = now().minus(retention);
        return jdbc.update("""
                DELETE FROM %s p USING %s r
                WHERE p.run_generation = r.generation
                  AND r.state IN ('COMPLETED', 'FAILED') AND p.staged_at < ?
                """.formatted(tables.payload(), tables.run()), timestamp(cutoff));
    }

    private LockedRun lockAndValidate(Lease lease) {
        Objects.requireNonNull(lease);
        LockedRun run;
        try {
            run = jdbc.queryForObject("""
                    SELECT generation, kind, season, coalescing_key, publication_key, state,
                           lease_owner, lease_token, lease_fence, lease_expires_at, attempt_count
                    FROM %s WHERE generation = ? FOR UPDATE
                    """.formatted(tables.run()), this::lockedRun, lease.generation());
        } catch (EmptyResultDataAccessException e) {
            throw new LeaseLostException("Run no longer exists: " + lease.generation());
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
                rs.getLong("lease_fence"), instant(rs, "lease_expires_at"), rs.getInt("attempt_count"));
    }

    private StagedPayload stagedPayload(ResultSet rs, int row) throws SQLException {
        return new StagedPayload(rs.getLong("id"), rs.getLong("run_generation"),
                EndpointKind.valueOf(rs.getString("endpoint_kind")),
                new SourceRead(instant(rs, "read_started_at"), instant(rs, "read_finished_at")),
                ValidationState.valueOf(rs.getString("validation_state")),
                rs.getString("validation_error"), rs.getBytes("body"));
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

    public enum SyncKind {
        FIXTURE_VERIFICATION(0), SOURCE_STATUS(1), PLAYER_HISTORY(2), CATALOGUE_SCHEDULE(3);
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

    public record Run(long generation, SyncKind kind, String season, String coalescingKey,
                      String publicationKey, Instant dueAt, RunState state, int attemptCount) { }

    public record Lease(long generation, SyncKind kind, String season, String coalescingKey,
                        String publicationKey, String owner, UUID token, long fence,
                        Instant expiresAt, int attemptCount) { }

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

    private record LockedRun(long generation, SyncKind kind, String season, String coalescingKey,
                             String publicationKey, String state, String owner, UUID token,
                             long fence, Instant expiresAt, int attemptCount) { }

    public static final class LeaseLostException extends IllegalStateException {
        public LeaseLostException(String message) { super(message); }
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
