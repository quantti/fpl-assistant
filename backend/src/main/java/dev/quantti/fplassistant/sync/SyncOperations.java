package dev.quantti.fplassistant.sync;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit operator-only status and additive retry operations. No reset or delete exists here. */
public final class SyncOperations {

    private static final Duration HEALTH_LIMIT = Duration.ofMinutes(10);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final OwnedSyncTables tables;
    private final OwnedSyncMode localMode;
    private final boolean alertDeliveryRequired;
    private final boolean alertDeliveryConfigured;
    private final boolean externalMonitor;
    private final boolean incrementalImporter;
    private final String handoffSeason;
    private final boolean handoffAllowed;
    private final int requestLimit;

    public SyncOperations(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock, OwnedSyncTables tables) {
        this(jdbc, tx, clock, tables, OwnedSyncMode.OFF);
    }

    public SyncOperations(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock,
                          OwnedSyncTables tables, OwnedSyncMode localMode) {
        this(jdbc, tx, clock, tables, localMode, false, true);
    }

    public SyncOperations(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock,
                          OwnedSyncTables tables, OwnedSyncMode localMode,
                          boolean alertDeliveryRequired, boolean alertDeliveryConfigured) {
        this(jdbc, tx, clock, tables, localMode, alertDeliveryRequired, alertDeliveryConfigured, false);
    }

    public SyncOperations(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock,
                          OwnedSyncTables tables, OwnedSyncMode localMode,
                          boolean alertDeliveryRequired, boolean alertDeliveryConfigured,
                          boolean externalMonitor) {
        this(jdbc, tx, clock, tables, localMode, alertDeliveryRequired, alertDeliveryConfigured,
                externalMonitor, false);
    }

    public SyncOperations(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock,
                          OwnedSyncTables tables, OwnedSyncMode localMode,
                          boolean alertDeliveryRequired, boolean alertDeliveryConfigured,
                          boolean externalMonitor, boolean incrementalImporter) {
        this(jdbc, tx, clock, tables, localMode, alertDeliveryRequired, alertDeliveryConfigured,
                externalMonitor, incrementalImporter, null, false);
    }

    SyncOperations(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock,
                   OwnedSyncTables tables, OwnedSyncMode localMode,
                   boolean alertDeliveryRequired, boolean alertDeliveryConfigured,
                   boolean externalMonitor, boolean incrementalImporter,
                   String handoffSeason, boolean handoffAllowed) {
        this(jdbc, tx, clock, tables, localMode, alertDeliveryRequired, alertDeliveryConfigured,
                externalMonitor, incrementalImporter, handoffSeason, handoffAllowed, 5_000);
    }

    SyncOperations(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock,
                   OwnedSyncTables tables, OwnedSyncMode localMode,
                   boolean alertDeliveryRequired, boolean alertDeliveryConfigured,
                   boolean externalMonitor, boolean incrementalImporter,
                   String handoffSeason, boolean handoffAllowed, int requestLimit) {
        this.requestLimit = requestLimit;
        this.externalMonitor = externalMonitor;
        this.incrementalImporter = incrementalImporter;
        this.handoffSeason = handoffSeason;
        this.handoffAllowed = handoffAllowed;
        this.jdbc = Objects.requireNonNull(jdbc);
        this.tx = Objects.requireNonNull(tx);
        this.clock = Objects.requireNonNull(clock);
        this.tables = Objects.requireNonNull(tables);
        this.localMode = Objects.requireNonNull(localMode);
        this.alertDeliveryRequired = alertDeliveryRequired;
        this.alertDeliveryConfigured = alertDeliveryConfigured;
    }

    public Status status() {
        try {
            List<RuntimeRow> runtimes = jdbc.query("SELECT instance_id,runtime_mode,runtime_epoch,"
                            + "heartbeat_at,last_error,last_error_at FROM " + tables.plannerRuntime()
                            + " ORDER BY instance_id",
                    (rs, row) -> new RuntimeRow(rs.getString(1), rs.getString(2), rs.getLong(3),
                            instant(rs.getTimestamp(4)), rs.getString(5), instant(rs.getTimestamp(6))));
            Map<String, Long> runs = counts("SELECT state,count(*) FROM " + tables.run() + " GROUP BY state");
            Map<String, Long> fixtures = counts("SELECT state,count(*) FROM " + tables.fixtureSync()
                    + " GROUP BY state");
            Map<String, Long> alerts = counts("SELECT delivery_state,count(*) FROM " + tables.alert()
                    + " GROUP BY delivery_state");
            List<FailedRunView> failedRuns = jdbc.query("SELECT generation,season,kind,publication_key,"
                            + "attempt_count,last_error,failed_at FROM " + tables.run()
                            + " WHERE state='FAILED' ORDER BY failed_at DESC NULLS LAST,generation DESC LIMIT 50",
                    (rs, row) -> new FailedRunView(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getInt(5), rs.getString(6), instant(rs.getTimestamp(7))));
            List<ReceiptView> receipts = jdbc.query("SELECT season,publication_key,source_read_finished_at,"
                            + "published_at FROM " + tables.receipt()
                            + " ORDER BY published_at DESC NULLS LAST LIMIT 50",
                    (rs, row) -> new ReceiptView(rs.getString(1), rs.getString(2),
                            instant(rs.getTimestamp(3)), instant(rs.getTimestamp(4))));
            List<FailedAlertView> failedAlerts = jdbc.query("SELECT id,incident_key,event_type,"
                            + "delivery_attempts,last_delivery_error,created_at FROM " + tables.alert()
                            + " WHERE delivery_state='FAILED' ORDER BY created_at DESC LIMIT 50",
                    (rs, row) -> new FailedAlertView(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getInt(4), rs.getString(5), instant(rs.getTimestamp(6))));
            BudgetStatus budget = incrementalImporter ? jdbc.queryForObject(
                    "SELECT count(*),min(admitted_at) FROM " + tables.requestAttempt() + " WHERE admitted_at>?",
                    (rs, row) -> new BudgetStatus(rs.getLong(1), requestLimit,
                            rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant().plus(Duration.ofHours(24))),
                    Timestamp.from(clock.instant().minus(Duration.ofHours(24)))) : null;
            List<PendingRunView> pending = incrementalImporter ? jdbc.query("SELECT generation,due_at,last_error,"
                            + "updated_at FROM " + tables.run() + " WHERE producer_key='INCREMENTAL_CORE' "
                            + "AND state='PENDING' ORDER BY due_at LIMIT 50",
                    (rs, row) -> new PendingRunView(rs.getLong(1), instant(rs.getTimestamp(2)), rs.getString(3),
                            instant(rs.getTimestamp(4)))) : List.of();
            return new Status(clock.instant(), runtimes, runs, fixtures, alerts,
                    failedRuns, receipts, failedAlerts, budget, pending);
        } catch (DataAccessException unavailable) {
            throw new OperationsUnavailableException("Owned-sync metadata is unavailable", unavailable);
        }
    }

    /** Minimal external signal: no season, fixture, queue, error, or owner details are exposed. */
    public boolean healthy() {
        try {
            Timestamp planner = jdbc.queryForObject("SELECT max(heartbeat_at) FROM "
                    + tables.plannerRuntime() + (incrementalImporter
                        ? " WHERE instance_id LIKE 'importer:%'"
                        : " WHERE instance_id NOT LIKE 'mode:%' AND instance_id NOT LIKE 'importer:%' "
                          + "AND instance_id NOT LIKE 'watchdog:%' AND instance_id NOT LIKE 'delivery%'"),
                    Timestamp.class);
            Timestamp watchdog = jdbc.queryForObject("SELECT max(heartbeat_at) FROM "
                    + tables.plannerRuntime() + " WHERE instance_id LIKE 'watchdog:%'", Timestamp.class);
            if (externalMonitor) {
                // Kuma delivers DOWN/recovery notifications. Never interpret a delivered webhook,
                // an old recovery, or a disabled outbox as proof that the current incident cleared.
                Long open = jdbc.queryForObject("SELECT count(*) FROM " + tables.alert() + " a "
                        + "WHERE a.event_type='INCIDENT' "
                        + (incrementalImporter ? "AND a.category<>'" + OwnedSyncWatchdog.BUDGET_CATEGORY + "' " : "")
                        + "AND NOT EXISTS (SELECT 1 FROM "
                        + tables.alert() + " r WHERE r.incident_key=a.incident_key "
                        + "AND r.occurrence=a.occurrence AND r.event_type='RECOVERY')", Long.class);
                Instant cutoff = clock.instant().minus(HEALTH_LIMIT);
                return localMode != OwnedSyncMode.OFF && fresh(planner, cutoff)
                        && fresh(watchdog, cutoff) && open != null && open == 0;
            }
            Timestamp delivery = jdbc.queryForObject("SELECT max(heartbeat_at) FROM "
                    + tables.plannerRuntime() + " WHERE instance_id LIKE 'delivery%'", Timestamp.class);
            Long undelivered = jdbc.queryForObject("SELECT count(*) FROM " + tables.alert() + " failed "
                    + "WHERE failed.delivery_state IN ('UNCONFIGURED','DISABLED') "
                    + "OR (failed.delivery_state='FAILED' AND failed.delivery_attempts>=3 "
                    + "AND NOT EXISTS (SELECT 1 FROM " + tables.alert() + " later "
                    + "WHERE later.incident_key=failed.incident_key AND later.id>failed.id "
                    + "AND later.delivery_state='DELIVERED'))", Long.class);
            Instant cutoff = clock.instant().minus(HEALTH_LIMIT);
            boolean deliveryHealthy = (undelivered == null || undelivered == 0)
                    && (!alertDeliveryRequired || alertDeliveryConfigured && fresh(delivery, cutoff));
            return fresh(planner, cutoff) && fresh(watchdog, cutoff) && deliveryHealthy;
        } catch (DataAccessException unavailable) {
            return false;
        }
    }

    /** Operator-confirmed OFF → importer ACTIVE epoch; never part of application startup. */
    public long handoffImporter(String playingSeason, long expectedOffEpoch) {
        if (!handoffAllowed || localMode != OwnedSyncMode.OFF || incrementalImporter) {
            throw new StaleOperatorRequestException("Importer handoff is disabled or a writer is configured");
        }
        if (handoffSeason == null || handoffSeason.isBlank() || !handoffSeason.equals(playingSeason)) {
            throw new IllegalArgumentException("Importer handoff season does not match configuration");
        }
        try {
            return new PlannerRuntimeRepository(jdbc, tx, clock, tables)
                    .handoffImporterFromOff(playingSeason, expectedOffEpoch).epoch();
        } catch (OwnedSyncStore.RuntimeFenceLostException stale) {
            throw new StaleOperatorRequestException(stale.getMessage());
        }
    }

    /** Operator-confirmed rollback fence; only an importer handoff epoch may be retired here. */
    public long retireImporter(String playingSeason, long expectedImporterEpoch) {
        if (!handoffAllowed || localMode != OwnedSyncMode.OFF || incrementalImporter) {
            throw new StaleOperatorRequestException("Importer rollback is disabled or a writer is configured");
        }
        if (handoffSeason == null || handoffSeason.isBlank() || !handoffSeason.equals(playingSeason)) {
            throw new IllegalArgumentException("Importer rollback season does not match configuration");
        }
        try {
            return new PlannerRuntimeRepository(jdbc, tx, clock, tables)
                    .retireImporterToOff(playingSeason, expectedImporterEpoch);
        } catch (OwnedSyncStore.RuntimeFenceLostException stale) {
            throw new StaleOperatorRequestException(stale.getMessage());
        }
    }

    /**
     * Creates one new pending generation from a failed run. The expected activation epoch is
     * mandatory, locked and copied; stale CLI/API requests cannot cross a restart or mode change.
     */
    public long retry(long failedGeneration, long expectedRuntimeEpoch) {
        if (failedGeneration <= 0 || expectedRuntimeEpoch <= 0) {
            throw new IllegalArgumentException("generation and expectedRuntimeEpoch must be positive");
        }
        if (localMode == OwnedSyncMode.OFF) {
            throw new StaleOperatorRequestException("Local owned-sync mode is OFF");
        }
        return tx.execute(status -> {
            List<FailedRun> rows = jdbc.query("SELECT generation,kind,season,coalescing_key,"
                            + "publication_key,priority,runtime_mode,runtime_epoch FROM " + tables.run()
                            + " WHERE generation=? AND state='FAILED' FOR UPDATE",
                    (rs, row) -> new FailedRun(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getShort(6), rs.getString(7), rs.getLong(8)),
                    failedGeneration);
            if (rows.size() != 1) throw new IllegalArgumentException("generation is not a failed run");
            FailedRun failed = rows.getFirst();
            List<ModeRow> modes = jdbc.query("SELECT runtime_mode,runtime_epoch FROM "
                            + tables.plannerRuntime() + " WHERE instance_id=? FOR UPDATE",
                    (rs, row) -> new ModeRow(rs.getString(1), rs.getLong(2)), "mode:" + failed.season());
            if (modes.size() != 1 || "OFF".equals(modes.getFirst().mode())
                    || modes.getFirst().epoch() != expectedRuntimeEpoch
                    || failed.epoch() != expectedRuntimeEpoch
                    || !failed.mode().equals(modes.getFirst().mode())) {
                throw new StaleOperatorRequestException("Owned-sync activation epoch changed or is OFF");
            }
            Instant now = clock.instant();
            Long generation = jdbc.queryForObject("INSERT INTO " + tables.run()
                            + "(kind,season,coalescing_key,publication_key,priority,due_at,"
                            + "requested_source_after,runtime_mode,runtime_epoch,created_at,updated_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?) RETURNING generation", Long.class,
                    failed.kind(), failed.season(), failed.coalescingKey(), failed.publicationKey(),
                    failed.priority(), Timestamp.from(now), Timestamp.from(now), failed.mode(), failed.epoch(),
                    Timestamp.from(now), Timestamp.from(now));
            if (generation == null) throw new IllegalStateException("retry generation was not returned");
            return generation;
        });
    }

    /** Additive redelivery: the exhausted row stays immutable evidence and a new outbox row is created. */
    public long redriveAlert(long failedAlertId) {
        if (externalMonitor) {
            throw new IllegalArgumentException("Notification retries belong to the external monitor");
        }
        if (failedAlertId <= 0) throw new IllegalArgumentException("failedAlertId must be positive");
        if (localMode == OwnedSyncMode.OFF) throw new StaleOperatorRequestException("Local owned-sync mode is OFF");
        return tx.execute(status -> {
            List<Long> rows = jdbc.query("SELECT id FROM " + tables.alert()
                    + " WHERE id=? AND delivery_state='FAILED' AND delivery_attempts>=3 FOR UPDATE",
                    (rs, row) -> rs.getLong(1), failedAlertId);
            if (rows.size() != 1) throw new IllegalArgumentException("alert is not an exhausted delivery");
            jdbc.query("SELECT pg_advisory_xact_lock(hashtext(incident_key)) FROM " + tables.alert()
                            + " WHERE id=?", (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                    failedAlertId);
            Instant now = clock.instant();
            Long id = jdbc.queryForObject("INSERT INTO " + tables.alert()
                            + "(incident_key,occurrence,event_type,category,severity,season,fixture_id,payload,"
                            + "delivery_state,next_delivery_at,created_at) SELECT a.incident_key,a.occurrence,"
                            + "'REDRIVE',a.category,a.severity,a.season,a.fixture_id,a.payload,'PENDING',?,? "
                            + "FROM " + tables.alert() + " a WHERE a.id=? RETURNING id", Long.class,
                    Timestamp.from(now), Timestamp.from(now), failedAlertId);
            jdbc.update("UPDATE " + tables.alert() + " SET delivery_state='SUPERSEDED' WHERE id=?", failedAlertId);
            return Objects.requireNonNull(id);
        });
    }

    private static boolean fresh(Timestamp value, Instant cutoff) {
        return value != null && !value.toInstant().isBefore(cutoff);
    }

    private Map<String, Long> counts(String sql) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.query(sql, (RowCallbackHandler) rs -> result.put(rs.getString(1), rs.getLong(2)));
        return Map.copyOf(result);
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    public record Status(Instant observedAt, List<RuntimeRow> runtimes, Map<String, Long> runs,
                         Map<String, Long> fixtures, Map<String, Long> alerts,
                         List<FailedRunView> failedRuns, List<ReceiptView> receipts,
                         List<FailedAlertView> failedAlerts, BudgetStatus budget,
                         List<PendingRunView> pendingRuns) { }
    public record BudgetStatus(long attemptsLast24Hours, int limit, Instant nextAttemptExpiresAt) { }
    public record PendingRunView(long generation, Instant nextCoordinatorAt, String reason, Instant updatedAt) { }
    public record RuntimeRow(String instanceId, String mode, long epoch, Instant lastSuccessfulHeartbeat,
                             String lastError, Instant lastErrorAt) { }
    public record FailedRunView(long generation, String season, String kind, String publicationKey,
                                int attempts, String lastError, Instant failedAt) { }
    public record ReceiptView(String season, String publicationKey, Instant sourceReadFinishedAt,
                              Instant publishedAt) { }
    public record FailedAlertView(long id, String incidentKey, String eventType, int attempts,
                                  String lastError, Instant createdAt) { }
    private record FailedRun(long generation, String kind, String season, String coalescingKey,
                             String publicationKey, short priority, String mode, long epoch) { }
    private record ModeRow(String mode, long epoch) { }

    public static final class OperationsUnavailableException extends RuntimeException {
        OperationsUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
    public static final class StaleOperatorRequestException extends RuntimeException {
        StaleOperatorRequestException(String message) { super(message); }
    }
}
