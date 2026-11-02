package dev.quantti.fplassistant.sync;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable, process-shared admission and cooldown state for the future owned-sync HTTP client.
 * This class performs no network I/O and never sleeps.
 *
 * <p>The transport reserves time for settlement within its request deadline. If exchange-worker
 * termination cannot be confirmed after cancellation, the attempt remains incomplete and blocks
 * admission indefinitely until reconciled.
 */
public final class OwnedSyncRequestBudget {

    private static final Duration RETENTION = Duration.ofHours(24);
    private static final Duration UNSETTLED_RECHECK = Duration.ofSeconds(30);
    private static final Duration DEFAULT_PERMIT_TTL = Duration.ofMinutes(2);
    private static final Duration DEFAULT_MAXIMUM_BACKOFF = Duration.ofMinutes(30);
    // PostgreSQL timestamptz accepts 4713 BC through 294276 AD. Keep the upper edge
    // exclusive so no parsed Retry-After can reach JDBC with an unpersistable instant.
    private static final Instant TIMESTAMPTZ_MIN = Instant.parse("-4712-01-01T00:00:00Z");
    private static final Instant TIMESTAMPTZ_MAX_EXCLUSIVE = Instant.parse("+294277-01-01T00:00:00Z");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final OwnedSyncTables tables;
    private final Limits limits;

    public OwnedSyncRequestBudget(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock,
                                  OwnedSyncTables tables) {
        this(jdbc, tx, clock, tables, Limits.defaults());
    }

    /** Bounded limits for tests and the opt-in importer; default callers retain 5,000 attempts. */
    public OwnedSyncRequestBudget(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock,
                                  OwnedSyncTables tables, Limits limits) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.tx = Objects.requireNonNull(tx);
        this.clock = Objects.requireNonNull(clock);
        this.tables = Objects.requireNonNull(tables);
        this.limits = Objects.requireNonNull(limits);
    }

    enum Purpose { DISCOVERY, FINAL, POST_MATCH, LIVE }

    record Demand(Purpose purpose, int players) {
        static final Demand DISCOVERY = new Demand(Purpose.DISCOVERY, 0);

        Demand {
            Objects.requireNonNull(purpose);
            if (players < 0 || players > GameweekFinality.MAX_CHECKPOINT_PLAYER_IDS) {
                throw new IllegalArgumentException("Invalid budget population");
            }
        }

        int ceiling(int maximum) {
            return Math.max(0, maximum - switch (purpose) {
                case DISCOVERY -> 0;
                case FINAL -> 128;
                case POST_MATCH -> 256 + players;
                case LIVE -> 384 + 3 * players;
            });
        }
    }

    /** Atomically admits and records an attempt, or identifies when and why it should be retried. */
    public Admission admit() {
        return admit(UUID.randomUUID());
    }

    /**
     * Supervised callers allocate the attempt id before submitting this DB-only operation, so a
     * timed-out caller can later reconcile the durable row without accepting a late result.
     */
    Admission admit(UUID preallocatedAttemptId) {
        return admit(preallocatedAttemptId, Demand.DISCOVERY);
    }

    Admission admit(UUID preallocatedAttemptId, Demand demand) {
        Objects.requireNonNull(preallocatedAttemptId);
        Objects.requireNonNull(demand);
        return tx.execute(status -> {
            State state = jdbc.queryForObject("SELECT last_admitted_at, cooldown_until FROM %s "
                    .formatted(tables.requestState()) + "WHERE singleton = TRUE FOR UPDATE", (rs, row) ->
                    new State(instant(rs.getTimestamp(1)), instant(rs.getTimestamp(2))));
            // The row lock can block behind another worker. Admission and lease times must be
            // measured after that wait, not backdated to when this transaction began waiting.
            Instant now = clock.instant();

            Instant retentionCutoff = now.minus(RETENTION);
            jdbc.update("DELETE FROM %s WHERE admitted_at < ? AND completed_at IS NOT NULL"
                            .formatted(tables.requestAttempt()), timestamp(retentionCutoff));
            Boolean unsettled = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM %s WHERE completed_at IS NULL)"
                    .formatted(tables.requestAttempt()), Boolean.class);
            if (Boolean.TRUE.equals(unsettled)) {
                // This is only a bounded recheck hint. An unknown attempt stays blocking until
                // its completion is durably recorded or an operator reconciles it.
                return new Deferred(now.plus(UNSETTLED_RECHECK), DeferredReason.UNSETTLED);
            }
            Timestamp latestCompletedAt = jdbc.queryForObject("SELECT max(completed_at) FROM %s"
                    .formatted(tables.requestAttempt()), Timestamp.class);
            Instant lastCompletedAt = instant(latestCompletedAt);

            Deferral deferral = null;
            if (state.cooldownUntil != null && state.cooldownUntil.isAfter(now)) {
                deferral = later(deferral, state.cooldownUntil, DeferredReason.COOLDOWN);
            }
            if (state.lastAdmittedAt != null) {
                Instant spacedAt = state.lastAdmittedAt.plus(limits.minimumSpacing);
                if (spacedAt.isAfter(now)) deferral = later(deferral, spacedAt, DeferredReason.MINIMUM_SPACING);
            }
            if (lastCompletedAt != null) {
                Instant spacedAt = lastCompletedAt.plus(limits.minimumSpacing);
                if (spacedAt.isAfter(now)) deferral = later(deferral, spacedAt, DeferredReason.MINIMUM_SPACING);
            }

            String live = "(completed_at IS NULL OR hold_until_expiry) AND permit_expires_at > ?";
            Long liveCount = jdbc.queryForObject("SELECT count(*) FROM %s WHERE "
                    .formatted(tables.requestAttempt()) + live, Long.class, timestamp(now));
            if (liveCount != null && liveCount >= limits.maxLivePermits) {
                Instant permitDue = jdbc.queryForObject("SELECT min(permit_expires_at) FROM %s WHERE "
                        .formatted(tables.requestAttempt()) + live,
                        Timestamp.class, timestamp(now)).toInstant();
                deferral = later(deferral, permitDue, DeferredReason.LIVE_PERMITS);
            }

            Instant windowStart = now.minus(limits.attemptWindow);
            Long attempts = jdbc.queryForObject("SELECT count(*) FROM %s WHERE admitted_at > ?"
                    .formatted(tables.requestAttempt()), Long.class, timestamp(windowStart));
            int ceiling = demand.ceiling(limits.maxAttempts);
            if (ceiling == 0) {
                deferral = later(deferral, now.plus(Duration.ofHours(1)), DeferredReason.CAPACITY_RESERVED);
            } else if (attempts != null && attempts >= ceiling) {
                Instant expiryBase = jdbc.queryForObject("SELECT admitted_at FROM %s WHERE admitted_at > ? "
                        .formatted(tables.requestAttempt()) + "ORDER BY admitted_at OFFSET ? LIMIT 1",
                        Timestamp.class, timestamp(windowStart), attempts - ceiling).toInstant();
                deferral = later(deferral, expiryBase.plus(limits.attemptWindow),
                        attempts >= limits.maxAttempts ? DeferredReason.ATTEMPT_LIMIT : DeferredReason.CAPACITY_RESERVED);
            }

            if (deferral != null) return new Deferred(deferral.nextDue, deferral.reason);

            Instant expiresAt = now.plus(limits.permitTtl);
            jdbc.update("INSERT INTO %s(permit_id, admitted_at, permit_expires_at) VALUES (?, ?, ?)"
                    .formatted(tables.requestAttempt()), preallocatedAttemptId, timestamp(now), timestamp(expiresAt));
            jdbc.update("UPDATE %s SET last_admitted_at = ? WHERE singleton = TRUE"
                    .formatted(tables.requestState()), timestamp(now));
            return new Allowed(new Permit(preallocatedAttemptId, now, expiresAt));
        });
    }

    /**
     * Completes only the supplied permit and returns the cooldown committed by the same transaction.
     * Repeated and late completions reconcile the existing row without changing its outcome;
     * completing an unknown permit is an invariant error. Only transient outcomes update the shared
     * cooldown. A valid {@code Retry-After} supplies its exact, uncapped deadline when PostgreSQL can
     * persist it; only the exponential-jitter fallback for a missing, malformed, overflowing, or
     * unpersistable header is capped by {@link Limits#maximumBackoff()}.
     */
    public Settlement complete(Permit permit, Completion completion) {
        Objects.requireNonNull(permit);
        Objects.requireNonNull(completion);
        return tx.execute(status -> {
            jdbc.queryForObject("SELECT singleton FROM %s WHERE singleton = TRUE FOR UPDATE"
                    .formatted(tables.requestState()), Boolean.class);
            Instant now = clock.instant();
            int completed = jdbc.update("UPDATE %s SET completed_at = ?, hold_until_expiry = ? "
                            .formatted(tables.requestAttempt())
                            + "WHERE permit_id = ? AND completed_at IS NULL",
                    timestamp(now), completion.holdUntilExpiry, permit.id);
            if (completed == 0) {
                Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM %s WHERE permit_id = ?)"
                        .formatted(tables.requestAttempt()), Boolean.class, permit.id);
                if (!Boolean.TRUE.equals(exists)) {
                    throw new IllegalStateException("Cannot complete missing owned-sync permit " + permit.id);
                }
                return committedSettlement();
            }

            if (completion.transientFailure()) {
                Instant proposed = retryAfterDeadline(completion.retryAfter, now);
                if (proposed == null) {
                    proposed = now.plus(exponentialBackoff(completion.failureAttempt, permit.id));
                }
                if (proposed.isAfter(now)) {
                    jdbc.update("UPDATE %s SET cooldown_until = CASE WHEN cooldown_until IS NULL THEN ? "
                                    .formatted(tables.requestState())
                                    + "ELSE GREATEST(cooldown_until, ?) END WHERE singleton = TRUE",
                            timestamp(proposed), timestamp(proposed));
                }
            }
            return committedSettlement();
        });
    }

    private Settlement committedSettlement() {
        Timestamp persisted = jdbc.queryForObject("SELECT cooldown_until FROM %s WHERE singleton = TRUE"
                .formatted(tables.requestState()), Timestamp.class);
        return new Settlement(Optional.ofNullable(persisted).map(Timestamp::toInstant));
    }

    /** Persisted shared cooldown hint; reading it never admits or consumes a permit. */
    public Optional<Instant> nextAdmissionHint() {
        Timestamp value = jdbc.queryForObject("SELECT cooldown_until FROM %s WHERE singleton = TRUE"
                .formatted(tables.requestState()), Timestamp.class);
        return Optional.ofNullable(value == null ? null : value.toInstant());
    }

    private Instant retryAfterDeadline(String value, Instant now) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (trimmed.matches("[0-9]+")) {
            long seconds;
            try {
                seconds = Long.parseLong(trimmed);
            } catch (NumberFormatException ignored) {
                return null;
            }
            try {
                return persistenceRepresentable(now.plusSeconds(seconds));
            } catch (DateTimeException | ArithmeticException ignored) {
                return null;
            }
        }
        try {
            return persistenceRepresentable(
                    ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        } catch (DateTimeException ignored) {
            return null;
        }
    }

    private static Instant persistenceRepresentable(Instant value) {
        return value.isBefore(TIMESTAMPTZ_MIN) || !value.isBefore(TIMESTAMPTZ_MAX_EXCLUSIVE)
                ? null : value;
    }

    private Duration exponentialBackoff(int attempt, UUID permitId) {
        int exponent = Math.min(Math.max(attempt, 1) - 1, 30);
        long baseMillis = limits.baseBackoff.toMillis();
        long ceilingMillis = limits.maximumBackoff.toMillis();
        long exponential;
        try {
            exponential = Math.multiplyExact(baseMillis, 1L << exponent);
        } catch (ArithmeticException e) {
            exponential = ceilingMillis;
        }
        exponential = Math.min(exponential, ceilingMillis);
        long room = ceilingMillis - exponential;
        long jitterCeiling = Math.min(exponential / 4, room);
        long jitter = jitterCeiling == 0 ? 0 : Math.floorMod(permitId.hashCode(), jitterCeiling + 1);
        return Duration.ofMillis(exponential + jitter);
    }

    private static Deferral later(Deferral current, Instant candidate, DeferredReason reason) {
        return current == null || candidate.isAfter(current.nextDue) ? new Deferral(candidate, reason) : current;
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public sealed interface Admission permits Allowed, Deferred {}

    public record Allowed(Permit permit) implements Admission {
        public Allowed { Objects.requireNonNull(permit); }
    }

    public record Deferred(Instant nextDue, DeferredReason reason) implements Admission {
        public Deferred {
            Objects.requireNonNull(nextDue);
            Objects.requireNonNull(reason);
        }
    }

    /** UNSETTLED's nextDue is only a bounded recheck hint, not a promise of future eligibility. */
    public enum DeferredReason {
        MINIMUM_SPACING, LIVE_PERMITS, ATTEMPT_LIMIT, CAPACITY_RESERVED, COOLDOWN, UNSETTLED,
        DB_OPERATIONS_SATURATED, DB_OPERATIONS_CLOSED, DB_DEADLINE_EXPIRED,
        DB_INTERRUPTED, DB_ADMISSION_UNCERTAIN, NO_FETCH_UNCERTAIN, HTTP_CLIENT_CLOSED, HTTP_NOT_SUBMITTED,
        HTTP_WORKERS_SATURATED, HTTP_TURN_QUEUE_FULL, HTTP_EXCHANGE_UNCERTAIN, SETTLEMENT_UNCERTAIN;

        String durableMessage() { return "HTTP budget deferred: " + name(); }

        boolean expectedWait() {
            return this == ATTEMPT_LIMIT || this == CAPACITY_RESERVED || this == COOLDOWN
                    || this == MINIMUM_SPACING || this == LIVE_PERMITS;
        }

        static DeferredReason fromDurableMessage(String message) {
            for (var reason : values()) {
                if (reason.durableMessage().equals(message)) return reason;
            }
            return null;
        }
    }

    public record Permit(UUID id, Instant admittedAt, Instant expiresAt) {
        public Permit {
            Objects.requireNonNull(id);
            Objects.requireNonNull(admittedAt);
            Objects.requireNonNull(expiresAt);
            if (!expiresAt.isAfter(admittedAt)) throw new IllegalArgumentException("permit must have positive TTL");
        }
    }

    /** Durable result returned only after completion and cooldown state commit atomically. */
    public record Settlement(Optional<Instant> committedCooldownUntil) {
        public Settlement { Objects.requireNonNull(committedCooldownUntil); }
    }

    public record Completion(Kind kind, int statusCode, String retryAfter, int failureAttempt,
                             boolean holdUntilExpiry) {
        public Completion {
            Objects.requireNonNull(kind);
            if (failureAttempt < 1) throw new IllegalArgumentException("failureAttempt must be positive");
            if (kind == Kind.HTTP && (statusCode < 100 || statusCode > 599)) {
                throw new IllegalArgumentException("invalid HTTP status");
            }
        }

        public static Completion success() { return new Completion(Kind.SUCCESS, 0, null, 1, false); }
        /** Terminal non-HTTP transport or payload failure; releases the permit without cooldown. */
        public static Completion error() { return new Completion(Kind.ERROR, 0, null, 1, false); }
        public static Completion timeout(int failureAttempt) {
            return new Completion(Kind.TIMEOUT, 0, null, failureAttempt, false);
        }
        public static Completion http(int statusCode, String retryAfter, int failureAttempt) {
            return new Completion(Kind.HTTP, statusCode, retryAfter, failureAttempt, false);
        }

        public Completion holdingPermitUntilExpiry() {
            return holdUntilExpiry ? this
                    : new Completion(kind, statusCode, retryAfter, failureAttempt, true);
        }

        boolean transientFailure() {
            return kind == Kind.TIMEOUT || (kind == Kind.HTTP && (statusCode == 429 || statusCode >= 500));
        }
    }

    public enum Kind { SUCCESS, HTTP, TIMEOUT, ERROR }

    public record Limits(Duration minimumSpacing, int maxLivePermits, int maxAttempts,
                         Duration attemptWindow, Duration permitTtl, Duration baseBackoff,
                         Duration maximumBackoff) {
        public Limits {
            positive(minimumSpacing, "minimumSpacing");
            positive(attemptWindow, "attemptWindow");
            positive(permitTtl, "permitTtl");
            positive(baseBackoff, "baseBackoff");
            positive(maximumBackoff, "maximumBackoff");
            if (minimumSpacing.compareTo(Duration.ofSeconds(1)) < 0) {
                throw new IllegalArgumentException("minimumSpacing cannot be below one second");
            }
            if (maxLivePermits < 1 || maxLivePermits > 2) {
                throw new IllegalArgumentException("maxLivePermits must be between 1 and 2");
            }
            if (maxAttempts < 1 || maxAttempts > 6_000) {
                throw new IllegalArgumentException("maxAttempts must be between 1 and 6000");
            }
            if (attemptWindow.compareTo(RETENTION) > 0) {
                throw new IllegalArgumentException("attemptWindow cannot exceed 24 hours");
            }
            if (permitTtl.compareTo(DEFAULT_PERMIT_TTL) > 0) {
                throw new IllegalArgumentException("permitTtl cannot exceed the production limit");
            }
            if (maximumBackoff.compareTo(DEFAULT_MAXIMUM_BACKOFF) > 0) {
                throw new IllegalArgumentException("maximumBackoff cannot exceed the production limit");
            }
            if (baseBackoff.compareTo(maximumBackoff) > 0) {
                throw new IllegalArgumentException("baseBackoff cannot exceed maximumBackoff");
            }
        }

        public static Limits defaults() {
            return defaults(5_000);
        }

        public static Limits defaults(int maxAttempts) {
            return new Limits(Duration.ofSeconds(1), 2, maxAttempts, Duration.ofHours(24),
                    DEFAULT_PERMIT_TTL, Duration.ofSeconds(2), DEFAULT_MAXIMUM_BACKOFF);
        }

        private static void positive(Duration value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private record State(Instant lastAdmittedAt, Instant cooldownUntil) {}
    private record Deferral(Instant nextDue, DeferredReason reason) {}
}
