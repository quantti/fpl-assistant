package fi.karivanttinen.fplassistant.sync;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import fi.karivanttinen.fplassistant.sync.AdaptiveMatchdayPlanner.SlotReason;
import fi.karivanttinen.fplassistant.sync.AdaptiveMatchdayPlanner.WorkKind;
import fi.karivanttinen.fplassistant.sync.AdaptiveMatchdayPlanner.WorkSlot;

/** Durable, inert persistence boundary for planner slots and coordinator liveness. */
public final class PlannerRuntimeRepository {

    private static final int MAX_ERROR_LENGTH = 1_000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final OwnedSyncTables tables;
    private final OwnedSyncStore.RuntimeFence runtimeFence;

    public PlannerRuntimeRepository(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock,
                                    OwnedSyncTables tables) {
        this(jdbc, tx, clock, tables, null);
    }

    public PlannerRuntimeRepository(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock,
                                    OwnedSyncTables tables, OwnedSyncStore.RuntimeFence runtimeFence) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.tx = Objects.requireNonNull(tx);
        this.clock = Objects.requireNonNull(clock);
        this.tables = Objects.requireNonNull(tables);
        this.runtimeFence = runtimeFence;
    }

    /**
     * Claims a stable planner slot and invokes its database enqueue operation in one transaction.
     * The callback is skipped when this or another instance has already committed the slot.
     */
    public boolean enqueueOnce(String season, WorkSlot slot, SlotEnqueuer enqueuer) {
        requireText(season, 10, "season");
        Objects.requireNonNull(slot);
        Objects.requireNonNull(enqueuer);
        requireText(slot.id(), 300, "slot id");
        AtomicBoolean inserted = new AtomicBoolean();
        tx.executeWithoutResult(status -> {
            assertRuntimeFence(season);
            int changed = jdbc.update(connection -> {
                var statement = connection.prepareStatement("""
                        INSERT INTO %s(slot_id, season, kind, due_at, fixture_ids, player_ids,
                            reason, estimated_requests, enqueued_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (slot_id) DO NOTHING
                        """.formatted(tables.plannerSlot()));
                statement.setString(1, slot.id());
                statement.setString(2, season);
                statement.setString(3, slot.kind().name());
                statement.setTimestamp(4, Timestamp.from(slot.dueAt()));
                statement.setArray(5, integerArray(connection, slot.fixtureIds()));
                statement.setArray(6, integerArray(connection, slot.playerIds()));
                statement.setString(7, slot.reason().name());
                statement.setInt(8, slot.estimatedRequests());
                statement.setTimestamp(9, Timestamp.from(clock.instant()));
                return statement;
            });
            if (changed == 1) {
                enqueuer.enqueue(slot);
                inserted.set(true);
            }
        });
        return inserted.get();
    }

    public List<DurableSlot> loadSlots(String season) {
        requireText(season, 10, "season");
        return jdbc.query("""
                SELECT slot_id, kind, due_at, fixture_ids, player_ids, reason,
                       estimated_requests, enqueued_at
                FROM %s WHERE season = ? ORDER BY due_at, slot_id
                """.formatted(tables.plannerSlot()), (rs, row) -> mapSlot(rs), season);
    }

    /**
     * Acquires leadership for one process activation.
     *
     * <p>The epoch advances for every activation, even when the persisted mode has the same text.
     * Thus an ACTIVE process after OFF cannot reuse an old source batch, and a concurrently late
     * worker from either mode loses its fence. Normal planner ticks never advance the epoch.
     */
    public OwnedSyncStore.RuntimeFence activate(String season, OwnedSyncMode mode) {
        requireText(season, 10, "season");
        if (mode == null || mode == OwnedSyncMode.OFF) throw new IllegalArgumentException("mode must be active");
        String key = "mode:" + season;
        return tx.execute(status -> {
            Instant now = clock.instant();
            jdbc.update("""
                    INSERT INTO %s(instance_id, started_at, heartbeat_at, runtime_mode, runtime_epoch)
                    VALUES (?, ?, ?, 'OFF', 0) ON CONFLICT (instance_id) DO NOTHING
                    """.formatted(tables.plannerRuntime()), key, Timestamp.from(now), Timestamp.from(now));
            List<ActivationState> prior = jdbc.query("SELECT runtime_epoch,runtime_mode FROM %s "
                    .formatted(tables.plannerRuntime()) + "WHERE instance_id=? FOR UPDATE",
                    (rs, row) -> new ActivationState(rs.getLong(1), rs.getString(2)), key);
            if (prior.size() != 1) throw new IllegalStateException("Owned-sync mode row is missing");
            List<Long> handoff = jdbc.query("SELECT latest_generation FROM " + tables.receipt()
                            + " WHERE season=? AND publication_key='importer-handoff' FOR UPDATE",
                    (rs, row) -> rs.getObject(1, Long.class), season);
            if ("ACTIVE".equals(prior.getFirst().mode()) && handoff.size() == 1
                    && Objects.equals(handoff.getFirst(), prior.getFirst().epoch())) {
                throw new IllegalStateException("Importer owns the ACTIVE epoch; retire it before legacy startup");
            }
            long epoch = Math.addExact(prior.getFirst().epoch(), 1);
            fenceRuns(season, now, "Run fenced at owned-sync activation " + mode + " epoch " + epoch);
            jdbc.update("DELETE FROM %s WHERE season=?".formatted(tables.plannerSlot()), season);
            jdbc.update("UPDATE %s SET runtime_mode=?, runtime_epoch=?, activation_boundary=?, heartbeat_at=? WHERE instance_id=?"
                    .formatted(tables.plannerRuntime()), mode.name(), epoch, Timestamp.from(now), Timestamp.from(now), key);
            return new OwnedSyncStore.RuntimeFence(season, mode, epoch, now);
        });
    }

    /**
     * Explicitly hands a drained OFF legacy epoch to the importer. Never called by startup.
     * The old runtime's epoch is fenced before OFF; this new epoch cannot be reused by it.
     */
    public OwnedSyncStore.RuntimeFence handoffImporterFromOff(String season, long expectedOffEpoch) {
        requireText(season, 10, "season");
        if (expectedOffEpoch <= 0) throw new IllegalArgumentException("invalid OFF epoch");
        return tx.execute(status -> {
            Integer playing = jdbc.queryForObject("SELECT count(*) FROM season WHERE is_current=true "
                    + "AND code=?", Integer.class, season);
            if (playing == null || playing != 1) {
                throw new IllegalStateException("Importer handoff requires the configured playing season");
            }
            String key = "mode:" + season;
            List<OffState> off = jdbc.query("SELECT runtime_epoch,activation_boundary FROM "
                            + tables.plannerRuntime() + " WHERE instance_id=? AND runtime_mode='OFF' FOR UPDATE",
                    (rs, row) -> new OffState(rs.getLong(1), rs.getTimestamp(2)), key);
            if (off.size() != 1 || off.getFirst().epoch() != expectedOffEpoch
                    || off.getFirst().activationBoundary() == null) {
                throw new OwnedSyncStore.RuntimeFenceLostException("Expected drained OFF epoch is stale");
            }
            Instant now = clock.instant();
            Long recentWorkers = jdbc.queryForObject("SELECT count(*) FROM " + tables.plannerRuntime()
                    + " WHERE instance_id NOT LIKE 'mode:%' AND instance_id NOT LIKE 'watchdog:%' "
                    + "AND instance_id NOT LIKE 'delivery%' AND heartbeat_at>=?", Long.class,
                    Timestamp.from(now.minus(java.time.Duration.ofMinutes(10))));
            Long unfinished = jdbc.queryForObject("SELECT count(*) FROM " + tables.run()
                    + " WHERE season=? AND state IN ('PENDING','LEASED')", Long.class, season);
            if (recentWorkers == null || recentWorkers != 0 || unfinished == null || unfinished != 0) {
                throw new OwnedSyncStore.RuntimeFenceLostException("Old worker or permit has not drained");
            }
            long epoch = Math.addExact(expectedOffEpoch, 1);
            jdbc.update("DELETE FROM " + tables.plannerSlot() + " WHERE season=?", season);
            int changed = jdbc.update("UPDATE " + tables.plannerRuntime()
                    + " SET runtime_mode='ACTIVE', runtime_epoch=?, activation_boundary=?, heartbeat_at=? "
                    + "WHERE instance_id=? AND runtime_mode='OFF' AND runtime_epoch=?", epoch,
                    Timestamp.from(now), Timestamp.from(now), key, expectedOffEpoch);
            if (changed != 1) throw new OwnedSyncStore.RuntimeFenceLostException("OFF epoch changed");
            jdbc.update("INSERT INTO " + tables.receipt()
                    + "(season,publication_key,latest_generation,published_at) VALUES "
                    + "(?,'importer-handoff',?,?) ON CONFLICT (season,publication_key) DO UPDATE "
                    + "SET latest_generation=EXCLUDED.latest_generation,published_at=EXCLUDED.published_at",
                    season, epoch, Timestamp.from(now));
            return new OwnedSyncStore.RuntimeFence(season, OwnedSyncMode.ACTIVE, epoch, now);
        });
    }

    /** Explicit rollback fence for an epoch created by handoffImporterFromOff, not a legacy ACTIVE writer. */
    public long retireImporterToOff(String season, long expectedImporterEpoch) {
        requireText(season, 10, "season");
        if (expectedImporterEpoch <= 0) throw new IllegalArgumentException("invalid importer epoch");
        return tx.execute(status -> {
            String key = "mode:" + season;
            List<Long> active = jdbc.query("SELECT runtime_epoch FROM " + tables.plannerRuntime()
                            + " WHERE instance_id=? AND runtime_mode='ACTIVE' FOR UPDATE",
                    (rs, row) -> rs.getLong(1), key);
            List<Long> handoff = jdbc.query("SELECT latest_generation FROM " + tables.receipt()
                            + " WHERE season=? AND publication_key='importer-handoff' FOR UPDATE",
                    (rs, row) -> rs.getObject(1, Long.class), season);
            if (active.size() != 1 || active.getFirst() != expectedImporterEpoch
                    || handoff.size() != 1 || !Objects.equals(handoff.getFirst(), expectedImporterEpoch)) {
                throw new OwnedSyncStore.RuntimeFenceLostException("Importer epoch is stale or not handoff-owned");
            }
            Instant now = clock.instant();
            long offEpoch = Math.addExact(expectedImporterEpoch, 1);
            fenceRuns(season, now, "Run fenced at importer rollback to OFF");
            jdbc.update("DELETE FROM " + tables.plannerSlot() + " WHERE season=?", season);
            int changed = jdbc.update("UPDATE " + tables.plannerRuntime()
                    + " SET runtime_mode='OFF', runtime_epoch=?, heartbeat_at=? "
                    + "WHERE instance_id=? AND runtime_mode='ACTIVE' AND runtime_epoch=?", offEpoch,
                    Timestamp.from(now), key, expectedImporterEpoch);
            if (changed != 1) throw new OwnedSyncStore.RuntimeFenceLostException("Importer epoch changed");
            return offEpoch;
        });
    }

    /** Compatibility helper for old administrative callers; runtime construction uses activate(). */
    public void enterMode(String season, OwnedSyncMode mode) {
        activate(season, mode);
    }

    /** Explicit drain/deactivation invalidates the epoch before waiting for old workers. */
    public void deactivate(OwnedSyncStore.RuntimeFence fence) {
        Objects.requireNonNull(fence);
        tx.executeWithoutResult(status -> {
            String key = "mode:" + fence.season();
            List<Long> current = jdbc.query("SELECT runtime_epoch FROM " + tables.plannerRuntime()
                            + " WHERE instance_id=? AND runtime_mode=? FOR UPDATE",
                    (rs, row) -> rs.getLong(1), key, fence.mode().name());
            if (current.size() != 1 || current.getFirst() != fence.epoch()) return;
            Instant now = clock.instant();
            long offEpoch = Math.addExact(fence.epoch(), 1);
            fenceRuns(fence.season(), now, "Run fenced at explicit owned-sync deactivation");
            jdbc.update("UPDATE %s SET runtime_mode='OFF', runtime_epoch=?, heartbeat_at=? WHERE instance_id=?"
                    .formatted(tables.plannerRuntime()), offEpoch, Timestamp.from(now), key);
        });
    }

    private void fenceRuns(String season, Instant now, String reason) {
        jdbc.update("""
                UPDATE %s SET state='FAILED', failed_at=?, last_error=?, updated_at=?,
                    lease_owner=NULL, lease_token=NULL, lease_expires_at=NULL
                WHERE season=? AND state IN ('PENDING','LEASED')
                """.formatted(tables.run()), Timestamp.from(now), reason, Timestamp.from(now), season);
    }

    public void heartbeat(String instanceId) {
        writeRuntime(instanceId, null);
    }

    public void recordError(String instanceId, String error) {
        if (error == null || error.isBlank()) throw new IllegalArgumentException("invalid error");
        writeRuntime(instanceId, error.length() <= MAX_ERROR_LENGTH
                ? error : error.substring(0, MAX_ERROR_LENGTH));
    }

    public Optional<RuntimeState> loadRuntime(String instanceId) {
        requireText(instanceId, 128, "instance id");
        return jdbc.query("""
                SELECT instance_id, started_at, heartbeat_at, last_error, last_error_at
                FROM %s WHERE instance_id = ?
                """.formatted(tables.plannerRuntime()), (rs, row) -> new RuntimeState(
                rs.getString("instance_id"), instant(rs, "started_at"),
                nullableInstant(rs, "heartbeat_at"), rs.getString("last_error"),
                nullableInstant(rs, "last_error_at")), instanceId).stream().findFirst();
    }

    private void writeRuntime(String instanceId, String error) {
        requireText(instanceId, 128, "instance id");
        if (runtimeFence != null) assertRuntimeFence(runtimeFence.season());
        Instant now = clock.instant();
        if (error == null) {
            jdbc.update("""
                    INSERT INTO %s(instance_id, started_at, heartbeat_at)
                    VALUES (?, ?, ?) ON CONFLICT (instance_id) DO UPDATE
                    SET heartbeat_at = EXCLUDED.heartbeat_at
                    """.formatted(tables.plannerRuntime()), instanceId,
                    Timestamp.from(now), Timestamp.from(now));
        } else {
            jdbc.update("""
                    INSERT INTO %s(instance_id, started_at, heartbeat_at, last_error, last_error_at)
                    VALUES (?, ?, NULL, ?, ?) ON CONFLICT (instance_id) DO UPDATE
                    SET last_error = EXCLUDED.last_error, last_error_at = EXCLUDED.last_error_at
                    """.formatted(tables.plannerRuntime()), instanceId, Timestamp.from(now),
                    error, Timestamp.from(now));
        }
    }

    private void assertRuntimeFence(String season) {
        if (runtimeFence == null) return;
        if (!runtimeFence.season().equals(season)) throw new OwnedSyncStore.RuntimeFenceLostException(
                "Planner runtime season fence is stale");
        List<Long> current = jdbc.query("SELECT runtime_epoch FROM " + tables.plannerRuntime()
                        + " WHERE instance_id=? AND runtime_mode=? FOR UPDATE",
                (rs, row) -> rs.getLong(1), "mode:" + season, runtimeFence.mode().name());
        if (current.size() != 1 || current.getFirst() != runtimeFence.epoch()) {
            throw new OwnedSyncStore.RuntimeFenceLostException("Planner runtime epoch is stale");
        }
    }

    private static DurableSlot mapSlot(ResultSet rs) throws SQLException {
        WorkSlot slot = new WorkSlot(rs.getString("slot_id"),
                WorkKind.valueOf(rs.getString("kind")), instant(rs, "due_at"),
                integerSet(rs.getArray("fixture_ids")), integerSet(rs.getArray("player_ids")),
                SlotReason.valueOf(rs.getString("reason")), rs.getInt("estimated_requests"));
        return new DurableSlot(slot, instant(rs, "enqueued_at"));
    }

    private static Array integerArray(java.sql.Connection connection, Set<Integer> values)
            throws SQLException {
        return connection.createArrayOf("integer", values.stream().sorted().toArray(Integer[]::new));
    }

    private static Set<Integer> integerSet(Array array) throws SQLException {
        Object[] values = (Object[]) array.getArray();
        return Arrays.stream(values).map(value -> ((Number) value).intValue()).collect(Collectors.toUnmodifiableSet());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static void requireText(String value, int maximum, String label) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException("invalid " + label);
        }
    }

    @FunctionalInterface
    public interface SlotEnqueuer {
        void enqueue(WorkSlot slot);
    }

    public record DurableSlot(WorkSlot slot, Instant enqueuedAt) { }

    public record RuntimeState(String instanceId, Instant startedAt, Instant heartbeatAt,
                               String lastError, Instant lastErrorAt) { }
    private record ActivationState(long epoch, String mode) { }
    private record OffState(long epoch, Timestamp activationBoundary) { }
}
