package dev.quantti.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import dev.quantti.fplassistant.sync.CatalogueScheduleParser.FixtureRow;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.ReadInterval;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.Season;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.SourceDocument;
import dev.quantti.fplassistant.sync.HistoryParser.Component;
import dev.quantti.fplassistant.sync.HistoryParser.HistoryBatch;
import dev.quantti.fplassistant.sync.HistoryParser.HistoryRow;
import dev.quantti.fplassistant.sync.HistoryFixtureDispatcher.DispatchResult;
import dev.quantti.fplassistant.sync.HistoryFixtureDispatcher.FailureType;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Endpoint;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Lease;
import dev.quantti.fplassistant.sync.OwnedSyncStore.PublicPayloadPair;
import dev.quantti.fplassistant.sync.OwnedSyncStore.SourceRead;
import dev.quantti.fplassistant.sync.OwnedSyncStore.StagedPayload;
import dev.quantti.fplassistant.sync.OwnedSyncStore.SyncKind;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Validation;
import dev.quantti.fplassistant.sync.OwnedSyncStore.ValidationState;
import dev.quantti.fplassistant.sync.SharedFixtureRecoveryPolicy.SourceStatus;
import dev.quantti.fplassistant.testsupport.SharedStatsSeed;

class FixtureRecoveryAdmissionIntegrationTest {

    private static final OwnedSyncTables TABLES = new OwnedSyncTables(
            "fpl_recov_adm_run", "fpl_recov_adm_payload", "fpl_recov_adm_receipt",
            "fpl_recov_adm_req_state", "fpl_recov_adm_req_attempt", "fpl_recov_adm_fixture",
            "fpl_recov_adm_evidence", "fpl_recov_adm_plan_slot", "fpl_recov_adm_plan_runtime",
            "fpl_recov_adm_fixture_manifest");
    private static final CatalogueScheduleTables CAPTURED = new CatalogueScheduleTables(
            "fpl_recov_adm_price_test", "fpl_recov_adm_gw_test", "fpl_recov_adm_capture_fixture_test");
    private static final Season SEASON = new Season(27, "2026-27", LocalDate.of(2026, 8, 21), null);
    private static final int FIXTURE_ID = 21;
    private static final Instant NOW = Instant.parse("2026-09-20T08:00:00Z");
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static MutableClock clock;
    private static FixtureSyncRepository repository;
    private static OwnedSyncStore store;

    @BeforeAll
    static void initialize() {
        var dataSource = new DriverManagerDataSource(required("DATABASE_URL"), required("DB_USER"),
                required("DB_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        SharedStatsSeed.ensureOwnedSyncCaptureSeason(jdbc);
        SharedStatsSeed.ensureOwnedSyncFixture(jdbc);
        new OwnedSyncSchema(jdbc, TABLES).initialize();
        createCapturedTables();
    }

    @AfterAll
    static void cleanUp() {
        if (jdbc == null) return;
        cleanCapturedRows();
        jdbc.execute("TRUNCATE " + TABLES.playerFixtureEvidence() + ", " + TABLES.fixtureSync() + ", "
                + TABLES.historyFingerprint() + ", " + TABLES.payload() + ", " + TABLES.run() + ", "
                + TABLES.receipt() + ", " + TABLES.plannerSlot() + ", " + TABLES.plannerRuntime() + ", "
                + TABLES.requestAttempt() + ", " + TABLES.requestState() + " CASCADE");
    }

    @BeforeEach
    void reset() {
        cleanCapturedRows();
        jdbc.execute("TRUNCATE " + TABLES.playerFixtureEvidence() + ", " + TABLES.fixtureSync() + ", "
                + TABLES.historyFingerprint() + ", " + TABLES.payload() + ", " + TABLES.run() + ", "
                + TABLES.receipt() + ", " + TABLES.plannerSlot() + ", " + TABLES.plannerRuntime() + ", "
                + TABLES.requestAttempt() + ", " + TABLES.requestState() + " CASCADE");
        jdbc.update("INSERT INTO " + TABLES.requestState() + "(singleton) VALUES (TRUE)");
        clock = new MutableClock(NOW);
        repository = new FixtureSyncRepository(jdbc, TABLES);
        store = new OwnedSyncStore(jdbc, tx, clock, TABLES);
    }

    @Test
    void legacyFutureDueIsHonoredUntilItsExactDueThenStartsOneEpisode() {
        Instant firstFinished = NOW.minus(Duration.ofHours(13));
        Instant legacyDue = Instant.parse("2026-09-21T02:00:00Z");
        seedFixture(firstFinished, legacyDue, "DAILY_ONLY");
        var fence = activate();
        store = new OwnedSyncStore(jdbc, tx, clock, TABLES, fence);

        var early = store.withinRuntimeFence(() -> repository.admitAttempt(
                SEASON, FIXTURE_ID, clock.instant(), SourceStatus.FINISHED));

        assertThat(early.admitted()).isFalse();
        assertThat(early.nextDueAt()).isEqualTo(legacyDue);
        assertNoEpisode();

        clock.set(legacyDue);
        var due = store.withinRuntimeFence(() -> repository.admitAttempt(
                SEASON, FIXTURE_ID, clock.instant(), SourceStatus.FINISHED));

        assertThat(due.admitted()).isTrue();
        assertEpisode(legacyDue);
        var resumed = store.withinRuntimeFence(() -> repository.admitAttempt(
                SEASON, FIXTURE_ID, clock.instant(), SourceStatus.FINISHED));
        assertThat(resumed.admitted()).isTrue();
        assertEpisode(legacyDue);
    }

    @Test
    void coordinatorKeepsLegacyDueThenDispatcherReservesAndCoalescesOneRecoveryAttempt() throws Exception {
        Instant legacyDue = Instant.parse("2026-09-21T02:00:00Z");
        seedFixture(NOW.minus(Duration.ofHours(13)), legacyDue, "DAILY_ONLY");
        var fence = seedCurrentCanonicalParent();
        store = new OwnedSyncStore(jdbc, tx, clock, TABLES, fence);
        repository = new FixtureSyncRepository(jdbc, TABLES);
        var publisher = new FixtureSyncPublisher(jdbc, clock, store, repository, TABLES);
        var fetch = new SingleFailureFetch(new OwnedSyncHttpClient.Response(500, new byte[0], null,
                new SourceRead(legacyDue, legacyDue), legacyDue.plusSeconds(5)));
        var histories = new HistoryFixtureDispatcher(store, publisher, fetch, clock);
        var catalogue = new CatalogueScheduleDispatcher(store, null,
                (endpoint, attempt) -> { throw new AssertionError("canonical run is delayed in this test"); },
                clock, false);
        var realInputs = new PlannerInputRepository(jdbc, store, TABLES, CAPTURED, clock);
        var inputs = mock(PlannerInputRepository.class);
        when(inputs.load(any())).thenAnswer(ignored -> {
            var full = realInputs.load(SEASON);
            return new PlannerInputRepository.PlannerSnapshot(Set.of(98201),
                    full.fixtures().stream().filter(fixture -> fixture.id() == FIXTURE_ID).toList(),
                    full.progress().stream().filter(progress -> progress.fixtureId() == FIXTURE_ID).toList(),
                    false);
        });
        var runtime = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES, fence);
        var coordinator = new OwnedMatchdayCoordinator(SEASON, new AdaptiveMatchdayPlanner(clock), inputs,
                runtime, store, catalogue, histories, "recovery-planner");

        coordinator.tick();
        assertThat(runtime.loadSlots(SEASON.code()).stream().map(row -> row.slot())
                .filter(slot -> slot.kind() == AdaptiveMatchdayPlanner.WorkKind.FIXTURE_VERIFICATION)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT next_due_at FROM " + TABLES.fixtureSync()
                + " WHERE fixture_id=?", java.sql.Timestamp.class, FIXTURE_ID).toInstant()).isEqualTo(legacyDue);

        clock.set(legacyDue);
        tickTogether(coordinator);
        var recoverySlot = runtime.loadSlots(SEASON.code()).stream().map(row -> row.slot())
                .filter(slot -> slot.kind() == AdaptiveMatchdayPlanner.WorkKind.FIXTURE_VERIFICATION)
                .filter(slot -> slot.fixtureIds().contains(FIXTURE_ID)).toList();
        assertThat(recoverySlot).hasSize(1);
        assertThat(recoverySlot.getFirst().dueAt()).isEqualTo(legacyDue);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE kind='FIXTURE_VERIFICATION' AND publication_key=? AND state='PENDING'",
                Integer.class, "fixture:" + FIXTURE_ID)).isOne();
        jdbc.update("UPDATE " + TABLES.run() + " SET due_at=? WHERE kind='CATALOGUE_SCHEDULE' AND state='PENDING'",
                timestamp(legacyDue.plus(Duration.ofDays(1))));

        assertThat(coordinator.dispatchOne("legacy-recovery")).isTrue();
        assertThat(fetch.endpoints).containsExactly(Endpoint.bootstrap());
        assertThat(jdbc.queryForObject("SELECT recovery_started_at FROM " + TABLES.fixtureSync()
                + " WHERE fixture_id=?", java.sql.Timestamp.class, FIXTURE_ID).toInstant()).isEqualTo(legacyDue);
        Instant transportDue = jdbc.queryForObject("SELECT transport_retry_at FROM " + TABLES.fixtureSync()
                + " WHERE fixture_id=?", java.sql.Timestamp.class, FIXTURE_ID).toInstant();
        assertThat(transportDue).isAfter(legacyDue);

        clock.advance(Duration.ofMinutes(1));
        coordinator.tick();
        assertThat(runtime.loadSlots(SEASON.code()).stream().map(row -> row.slot())
                .filter(slot -> slot.kind() == AdaptiveMatchdayPlanner.WorkKind.FIXTURE_VERIFICATION)
                .filter(slot -> slot.fixtureIds().contains(FIXTURE_ID))).hasSize(1);
        assertThat(coordinator.dispatchOne("before-transport-retry")).isFalse();
        assertThat(fetch.endpoints).containsExactly(Endpoint.bootstrap());
    }

    @Test
    void unknownSourceDoesNotTurnOldFirstFinishedIntoRecoveryEvidence() {
        seedFixture(NOW.minus(Duration.ofDays(2)), null, "DAILY_ONLY");
        var fence = activate();
        store = new OwnedSyncStore(jdbc, tx, clock, TABLES, fence);

        var result = store.withinRuntimeFence(() -> repository.admitAttempt(
                SEASON, FIXTURE_ID, clock.instant(), SourceStatus.UNKNOWN));

        assertThat(result.admitted()).isFalse();
        assertThat(result.parentRefreshRequired()).isTrue();
        assertThat(result.nextDueAt()).isNull();
        assertNoEpisode();
    }

    @Test
    void unknownSourceCannotBypassAnExpiredEpisodeOrItsTransportGate() {
        seedExpiredEpisodeWithTransportRetry();
        var fence = activate();
        store = new OwnedSyncStore(jdbc, tx, clock, TABLES, fence);

        var result = store.withinRuntimeFence(() -> repository.admitAttempt(
                SEASON, FIXTURE_ID, clock.instant(), SourceStatus.UNKNOWN));

        assertThat(result.admitted()).isFalse();
        assertThat(result.parentRefreshRequired()).isTrue();
        assertThat(result.nextDueAt()).isNull();
        assertExpiredEpisodeAndTransportUnchanged();
    }

    @Test
    void dependencyWaitKeepsTheLatestNewDependencyAndExistingRetryDeadlines() {
        seedFixture(NOW.minus(Duration.ofDays(2)), null, "NONE");
        Instant dependencyDue = NOW.plus(Duration.ofMinutes(10));
        Instant transportDue = NOW.plus(Duration.ofMinutes(20));
        repository.recordDependencyWait(SEASON.code(), FIXTURE_ID, dependencyDue, "old wait", NOW);

        Instant retainedDependency = repository.recordDependencyWait(SEASON.code(), FIXTURE_ID,
                NOW.plus(Duration.ofMinutes(2)), "new short wait", NOW.plusSeconds(1));

        assertThat(retainedDependency).isEqualTo(dependencyDue);
        assertThat(jdbc.queryForObject("SELECT dependency_retry_at FROM " + TABLES.fixtureSync()
                + " WHERE fixture_id=?", java.sql.Timestamp.class, FIXTURE_ID).toInstant())
                .isEqualTo(dependencyDue);
        jdbc.update("UPDATE " + TABLES.fixtureSync() + " SET transport_retry_at=? WHERE fixture_id=?",
                timestamp(transportDue), FIXTURE_ID);
        Instant retainedTransport = repository.recordDependencyWait(SEASON.code(), FIXTURE_ID,
                NOW.plus(Duration.ofMinutes(15)), "another wait", NOW.plusSeconds(2));

        assertThat(retainedTransport).isEqualTo(transportDue);
        assertThat(jdbc.queryForObject("SELECT next_due_at FROM " + TABLES.fixtureSync()
                + " WHERE fixture_id=?", java.sql.Timestamp.class, FIXTURE_ID).toInstant())
                .isEqualTo(transportDue);
        assertThat(jdbc.queryForObject("SELECT dependency_retry_at FROM " + TABLES.fixtureSync()
                + " WHERE fixture_id=?", java.sql.Timestamp.class, FIXTURE_ID).toInstant())
                .isEqualTo(transportDue);
        assertThat(jdbc.queryForObject("SELECT transport_retry_at FROM " + TABLES.fixtureSync()
                + " WHERE fixture_id=?", java.sql.Timestamp.class, FIXTURE_ID).toInstant())
                .isEqualTo(transportDue);
    }

    @Test
    void invalidLeaseCannotApplyDependencyRetryMutation() {
        seedFixture(NOW.minus(Duration.ofDays(2)), null, "NONE");
        var fence = activate();
        store = new OwnedSyncStore(jdbc, tx, clock, TABLES, fence);
        var run = enqueueFixture(SEASON, FIXTURE_ID, NOW);
        Lease lease = store.acquire("dependency-worker", Duration.ofMinutes(5)).orElseThrow();
        var anchors = stageDependencyAnchors(lease, Validation.valid());
        Instant due = NOW.plus(Duration.ofMinutes(2));
        jdbc.update("UPDATE " + TABLES.run() + " SET lease_fence=lease_fence+1 WHERE generation=?",
                run.generation());
        AtomicBoolean callbackRan = new AtomicBoolean();
        Lease staleLease = lease;
        assertThatThrownBy(() -> store.retryForDependency(staleLease, due, "waiting", FIXTURE_ID, 3,
                anchors.pair(), anchors.live(), List.of(98201), () -> {
                    callbackRan.set(true);
                    return repository.recordDependencyWait(SEASON.code(), FIXTURE_ID, due,
                            "waiting", clock.instant());
                })).isInstanceOf(OwnedSyncStore.LeaseLostException.class);

        assertThat(callbackRan).isFalse();
        assertFixtureRetryStateUnchanged();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.fixtureDependencyManifest()
                + " WHERE run_generation=?", Integer.class, run.generation())).isZero();

        AtomicBoolean failedRetryCallbackRan = new AtomicBoolean();
        assertThatThrownBy(() -> store.retryFetchFailure(staleLease, due, "transport failed", () -> {
            failedRetryCallbackRan.set(true);
            return repository.recordFailedAttempt(SEASON.code(), FIXTURE_ID, due,
                    "transport failed", clock.instant());
        })).isInstanceOf(OwnedSyncStore.LeaseLostException.class);
        assertThat(failedRetryCallbackRan).isFalse();
        assertFixtureRetryStateUnchanged();
    }

    @Test
    void dependencyMutationRollsBackWhenLaterManifestValidationFails() {
        seedFixture(NOW.minus(Duration.ofDays(2)), null, "NONE");
        var fence = activate();
        store = new OwnedSyncStore(jdbc, tx, clock, TABLES, fence);
        var run = enqueueFixture(SEASON, FIXTURE_ID, NOW);
        Lease lease = store.acquire("dependency-worker", Duration.ofMinutes(5)).orElseThrow();
        var anchors = stageDependencyAnchors(lease, Validation.unchecked());
        Instant due = NOW.plus(Duration.ofMinutes(2));

        assertThatThrownBy(() -> store.retryForDependency(lease, due, "waiting", FIXTURE_ID, 3,
                anchors.pair(), anchors.live(), List.of(98201), () -> repository.recordDependencyWait(
                        SEASON.code(), FIXTURE_ID, due, "waiting", clock.instant())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("valid own endpoint");

        assertFixtureRetryStateUnchanged();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.fixtureDependencyManifest()
                + " WHERE run_generation=?", Integer.class, run.generation())).isZero();
    }

    @Test
    void unknownSourcePreservesALegacyFutureDueDate() {
        Instant legacyDue = NOW.plus(Duration.ofHours(2));
        seedFixture(NOW.minus(Duration.ofDays(2)), legacyDue, "DAILY_ONLY");
        var fence = activate();
        store = new OwnedSyncStore(jdbc, tx, clock, TABLES, fence);

        var result = store.withinRuntimeFence(() -> repository.admitAttempt(
                SEASON, FIXTURE_ID, clock.instant(), SourceStatus.UNKNOWN));

        assertThat(result.admitted()).isFalse();
        assertThat(result.parentRefreshRequired()).isTrue();
        assertThat(jdbc.queryForObject("SELECT next_due_at FROM " + TABLES.fixtureSync()
                + " WHERE fixture_id=?", java.sql.Timestamp.class, FIXTURE_ID).toInstant()).isEqualTo(legacyDue);
        assertNoEpisode();
    }

    @Test
    void futureTransportRetryOutranksCalendarPolicyWithoutBeingRewritten() {
        Instant retryAt = NOW.plus(Duration.ofHours(2));
        Instant policyDue = NOW.plus(Duration.ofDays(1));
        seedFixture(NOW.minus(Duration.ofDays(2)), policyDue, "DAILY_ONLY");
        jdbc.update("UPDATE " + TABLES.fixtureSync()
                + " SET due_reason='POLICY' WHERE fixture_id=?", FIXTURE_ID);
        var fence = activate();
        store = new OwnedSyncStore(jdbc, tx, clock, TABLES, fence);
        jdbc.update("UPDATE " + TABLES.fixtureSync() + " SET transport_retry_at=? WHERE fixture_id=?",
                timestamp(retryAt), FIXTURE_ID);

        var result = store.withinRuntimeFence(() -> repository.admitAttempt(
                SEASON, FIXTURE_ID, clock.instant(), SourceStatus.FINISHED));

        assertThat(result.admitted()).isFalse();
        assertThat(result.nextDueAt()).isEqualTo(retryAt);
        assertThat(jdbc.queryForObject("SELECT transport_retry_at FROM " + TABLES.fixtureSync()
                + " WHERE fixture_id=?", java.sql.Timestamp.class, FIXTURE_ID).toInstant()).isEqualTo(retryAt);
        assertNoEpisode();
    }

    @Test
    void unfinishedCurrentSourceAndFutureCooldownCannotReserveRecovery() {
        seedFixture(NOW.minus(Duration.ofDays(2)), null, "NONE");
        var fence = activate();
        store = new OwnedSyncStore(jdbc, tx, clock, TABLES, fence);
        var unfinished = store.withinRuntimeFence(() -> repository.admitAttempt(
                SEASON, FIXTURE_ID, clock.instant(), SourceStatus.UNFINISHED));
        assertThat(unfinished.admitted()).isFalse();
        assertNoEpisode();

        jdbc.update("UPDATE " + TABLES.requestState() + " SET cooldown_until=? WHERE singleton=TRUE",
                timestamp(NOW.plus(Duration.ofHours(1))));
        var cooldown = store.withinRuntimeFence(() -> repository.admitAttempt(
                SEASON, FIXTURE_ID, clock.instant(), SourceStatus.FINISHED));
        assertThat(cooldown.admitted()).isFalse();
        assertThat(cooldown.nextDueAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertNoEpisode();
    }

    @Test
    void activeCurrentParentResumesTheFixedEpisodeAndTransportFailureKeepsItsRetryLadder() throws Exception {
        seedCurrentCanonicalParent();
        seedFixture(NOW.minus(Duration.ofDays(2)), null, "DAILY_ONLY");
        var admitted = store.withinRuntimeFence(() -> repository.admitAttempt(
                SEASON, FIXTURE_ID, clock.instant(), SourceStatus.FINISHED));
        assertThat(admitted.admitted()).isTrue();

        var publisher = new FixtureSyncPublisher(jdbc, clock, store,
                new FixtureSyncRepository(jdbc, TABLES), TABLES);
        var fetch = new SingleFailureFetch(new OwnedSyncHttpClient.Response(500, new byte[0], null,
                new SourceRead(NOW, NOW), NOW.plus(Duration.ofSeconds(5))));
        var dispatcher = new HistoryFixtureDispatcher(store, publisher, fetch, clock);
        var run = dispatcher.enqueueFixtureVerification(SEASON, FIXTURE_ID, NOW);

        DispatchResult result = dispatcher.process(store.acquire("recovery-worker", Duration.ofMinutes(5))
                .filter(lease -> lease.generation() == run.generation()).orElseGet(() -> {
                    throw new AssertionError("fixture run was not claimed");
                }), SEASON);

        assertThat(result.failure()).isEqualTo(FailureType.HTTP_FAILURE);
        assertThat(fetch.endpoints).containsExactly(Endpoint.bootstrap());
        assertEpisode(NOW);
        Instant retryAt = NOW.plus(Duration.ofMinutes(10));
        assertThat(jdbc.queryForObject("SELECT transport_retry_at FROM " + TABLES.fixtureSync()
                + " WHERE fixture_id=?", java.sql.Timestamp.class, FIXTURE_ID).toInstant()).isEqualTo(retryAt);
        assertThat(jdbc.queryForObject("SELECT due_at FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, run.generation()).toInstant()).isEqualTo(retryAt);
    }

    @Test
    void reactivationWithoutCurrentParentDefersExpiredRecoveryUntilPublishedFinishedEvidence() throws Exception {
        var previousFence = seedCurrentCanonicalParent();
        seedExpiredEpisodeWithTransportRetry();
        new PlannerRuntimeRepository(jdbc, tx, clock, TABLES).deactivate(previousFence);
        var currentFence = activate();
        store = new OwnedSyncStore(jdbc, tx, clock, TABLES, currentFence);
        var publisher = new FixtureSyncPublisher(jdbc, clock, store,
                new FixtureSyncRepository(jdbc, TABLES), TABLES);
        var fetch = new SingleFailureFetch(new OwnedSyncHttpClient.Response(500, new byte[0], null,
                new SourceRead(clock.instant(), clock.instant()), clock.instant().plusSeconds(5)));
        var dispatcher = new HistoryFixtureDispatcher(store, publisher, fetch, clock);
        var fixtureRun = dispatcher.enqueueFixtureVerification(SEASON, FIXTURE_ID, clock.instant());
        Lease fixtureLease = store.acquire("reactivated-worker", Duration.ofMinutes(5))
                .filter(candidate -> candidate.generation() == fixtureRun.generation())
                .orElseThrow(() -> new AssertionError("fixture run was not claimed"));

        DispatchResult unknownResult = dispatcher.process(fixtureLease, SEASON);

        assertThat(unknownResult.failure()).isEqualTo(FailureType.HTTP_DEFERRED);
        assertThat(fetch.endpoints).isEmpty();
        assertExpiredEpisodeAndTransportUnchanged();
        assertThat(jdbc.queryForObject("SELECT due_at FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, fixtureRun.generation()).toInstant())
                .isEqualTo(clock.instant().plusSeconds(30));
        assertThat(jdbc.queryForObject("SELECT consecutive_fetch_failures FROM " + TABLES.run()
                + " WHERE generation=?", Integer.class, fixtureRun.generation())).isZero();
        assertThat(jdbc.queryForObject("SELECT priority FROM " + TABLES.run()
                + " WHERE generation=?", Integer.class, fixtureRun.generation())).isEqualTo(4);

        for (int index = 0; index < 2; index++) {
            Lease parentLease = store.acquire("current-parent-worker", Duration.ofMinutes(5)).orElseThrow();
            assertThat(parentLease.kind()).isEqualTo(SyncKind.CATALOGUE_SCHEDULE);
            publishParent(parentLease);
        }
        assertThat(store.isCurrentPublishedCanonicalPair(SEASON.code(),
                jdbc.queryForObject("SELECT generation FROM " + TABLES.run()
                        + " WHERE season=? AND coalescing_key=? AND state='COMPLETED'"
                        + " ORDER BY generation DESC LIMIT 1", Long.class,
                        SEASON.code(), CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY),
                clock.instant())).isTrue();

        Instant nextBoundary = Instant.parse("2026-09-21T02:00:00Z");
        clock.set(jdbc.queryForObject("SELECT due_at FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, fixtureRun.generation()).toInstant());
        Lease resumed = store.acquire("reactivated-worker", Duration.ofMinutes(5))
                .filter(candidate -> candidate.generation() == fixtureRun.generation())
                .orElseThrow(() -> new AssertionError("deferred fixture run was not claimable"));

        DispatchResult finishedResult = dispatcher.process(resumed, SEASON);

        assertThat(finishedResult.failure()).isEqualTo(FailureType.HTTP_DEFERRED);
        assertThat(fetch.endpoints).isEmpty();
        assertThat(jdbc.queryForObject("SELECT next_due_at FROM " + TABLES.fixtureSync()
                + " WHERE fixture_id=?", java.sql.Timestamp.class, FIXTURE_ID).toInstant())
                .isEqualTo(nextBoundary);
        assertExpiredEpisodeAndTransportFieldsPreserved(nextBoundary);
    }

    @Test
    void thirteenHourPendingSourceCompletesTwoBatchesInOneEpisodeAndInvalidationKeepsConsumption() {
        Season publishedSeason = new Season(9825, "2024-25", LocalDate.of(2024, 8, 16),
                LocalDate.of(2025, 5, 25));
        List<Integer> previousCurrent = jdbc.queryForList(
                "SELECT id FROM season WHERE is_current=true", Integer.class);
        jdbc.update("UPDATE season SET is_current=(id=?)", publishedSeason.id());
        try {
            seedFixture(publishedSeason, 98101, NOW.minus(Duration.ofHours(13)), null, "NONE");
            var fence = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES)
                    .activate(publishedSeason.code(), OwnedSyncMode.ACTIVE);
            store = new OwnedSyncStore(jdbc, tx, clock, TABLES, fence);
            repository = new FixtureSyncRepository(jdbc, TABLES);
            var publisher = new FixtureSyncPublisher(jdbc, clock, store, repository, TABLES);

            assertThat(store.withinRuntimeFence(() -> publisher.admitAttempt(
                    publishedSeason, 98101, SourceStatus.FINISHED)).admitted()).isTrue();
            assertEpisode(publishedSeason, 98101, NOW);

            Inputs first = inputs(publishedSeason, NOW);
            clock.set(NOW.plusSeconds(6));
            assertThat(publish(publisher, publishedSeason, first).state())
                    .isEqualTo(FixtureVerification.State.PENDING);
            Instant followUp = jdbc.queryForObject("SELECT next_due_at FROM " + TABLES.fixtureSync()
                    + " WHERE season_id=? AND fixture_id=98101", java.sql.Timestamp.class,
                    publishedSeason.id()).toInstant();
            assertThat(jdbc.queryForObject("SELECT due_reason FROM " + TABLES.fixtureSync()
                    + " WHERE season_id=? AND fixture_id=98101", String.class,
                    publishedSeason.id())).isEqualTo("DEPENDENCY");
            assertThat(followUp).isEqualTo(NOW.plusSeconds(606));

            clock.set(followUp);
            assertThat(store.withinRuntimeFence(() -> publisher.admitAttempt(
                    publishedSeason, 98101, SourceStatus.FINISHED)).admitted()).isTrue();
            Inputs second = inputs(publishedSeason, followUp);
            clock.set(followUp.plusSeconds(6));
            assertThat(publish(publisher, publishedSeason, second).state())
                    .isEqualTo(FixtureVerification.State.VERIFIED);
            assertEpisode(publishedSeason, 98101, NOW);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.playerFixtureEvidence()
                    + " WHERE season_id=? AND fixture_id=98101", Integer.class,
                    publishedSeason.id())).isEqualTo(2);

            Instant publishedAt = jdbc.queryForObject("SELECT published_at FROM " + TABLES.receipt()
                    + " WHERE season=? AND publication_key='fixture:98101'", java.sql.Timestamp.class,
                    publishedSeason.code()).toInstant();
            assertThat(publishedAt).isAfter(NOW);
            store.withinRuntimeFence(() -> publisher.invalidateHistoryCorrections(
                    publishedSeason, Set.of(98101), clock.instant()));
            enqueueFixture(publishedSeason, 98101, clock.instant());

            var afterInvalidation = store.withinRuntimeFence(() -> publisher.admitAttempt(
                    publishedSeason, 98101, SourceStatus.FINISHED));
            assertThat(afterInvalidation.admitted()).isFalse();
            assertThat(afterInvalidation.nextDueAt()).isEqualTo(Instant.parse("2026-09-21T02:00:00Z"));
            assertEpisode(publishedSeason, 98101, NOW);
            assertThat(jdbc.queryForObject("SELECT last_verified_generation FROM " + TABLES.fixtureSync()
                    + " WHERE season_id=? AND fixture_id=98101", Long.class,
                    publishedSeason.id())).isNotNull();
            assertThat(jdbc.queryForObject("SELECT current_verified_generation FROM " + TABLES.fixtureSync()
                    + " WHERE season_id=? AND fixture_id=98101", Long.class,
                    publishedSeason.id())).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                    + " WHERE kind='FIXTURE_VERIFICATION' AND state='PENDING' AND publication_key='fixture:98101'",
                    Integer.class)).isEqualTo(1);
        } finally {
            jdbc.update("UPDATE season SET is_current=false");
            previousCurrent.forEach(id -> jdbc.update("UPDATE season SET is_current=true WHERE id=?", id));
            jdbc.update("DELETE FROM player_fixture_stats WHERE season_id=? AND fixture_id=98101",
                    publishedSeason.id());
        }
    }

    @Test
    void pendingObservationCrossingTwelveHoursKeepsItsIndependentFollowUpBeforeRecovery() {
        Season publishedSeason = new Season(9825, "2024-25", LocalDate.of(2024, 8, 16),
                LocalDate.of(2025, 5, 25));
        List<Integer> previousCurrent = jdbc.queryForList(
                "SELECT id FROM season WHERE is_current=true", Integer.class);
        jdbc.update("UPDATE season SET is_current=(id=?)", publishedSeason.id());
        try {
            Instant observationStart = NOW.plusSeconds(2);
            Instant twelveHourCutoff = NOW.plusSeconds(4);
            seedFixture(publishedSeason, 98101,
                    twelveHourCutoff.minus(SharedFixtureRecoveryPolicy.FOLLOW_UP_LIMIT), null, "NONE");
            var fence = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES)
                    .activate(publishedSeason.code(), OwnedSyncMode.ACTIVE);
            store = new OwnedSyncStore(jdbc, tx, clock, TABLES, fence);
            repository = new FixtureSyncRepository(jdbc, TABLES);
            var publisher = new FixtureSyncPublisher(jdbc, clock, store, repository, TABLES);

            assertThat(store.withinRuntimeFence(() -> publisher.admitAttempt(
                    publishedSeason, 98101, SourceStatus.FINISHED)).admitted()).isTrue();
            assertNoEpisode(publishedSeason, 98101);

            clock.set(NOW.plusSeconds(8));
            var decision = publish(publisher, publishedSeason, inputs(publishedSeason, observationStart));
            assertThat(decision.state()).isEqualTo(FixtureVerification.State.PENDING);
            assertNoEpisode(publishedSeason, 98101);

            Instant dependencyDue = clock.instant().plus(Duration.ofMinutes(10));
            Instant policyDue = jdbc.queryForObject("SELECT next_due_at FROM " + TABLES.fixtureSync()
                    + " WHERE season_id=? AND fixture_id=98101", java.sql.Timestamp.class,
                    publishedSeason.id()).toInstant();
            assertThat(policyDue).isAfter(dependencyDue);
            assertThat(jdbc.queryForObject("SELECT due_reason FROM " + TABLES.fixtureSync()
                    + " WHERE season_id=? AND fixture_id=98101", String.class,
                    publishedSeason.id())).isEqualTo("POLICY");
            assertThat(jdbc.queryForObject("SELECT dependency_retry_at FROM " + TABLES.fixtureSync()
                    + " WHERE season_id=? AND fixture_id=98101", java.sql.Timestamp.class,
                    publishedSeason.id()).toInstant()).isEqualTo(dependencyDue);

            clock.set(dependencyDue.minusSeconds(1));
            var early = store.withinRuntimeFence(() -> publisher.admitAttempt(
                    publishedSeason, 98101, SourceStatus.FINISHED));
            assertThat(early.admitted()).isFalse();
            assertThat(early.nextDueAt()).isEqualTo(dependencyDue);
            assertNoEpisode(publishedSeason, 98101);

            Instant cooldownDue = dependencyDue.plus(Duration.ofMinutes(2));
            jdbc.update("UPDATE " + TABLES.requestState() + " SET cooldown_until=? WHERE singleton=TRUE",
                    timestamp(cooldownDue));
            clock.set(dependencyDue);
            var cooldown = store.withinRuntimeFence(() -> publisher.admitAttempt(
                    publishedSeason, 98101, SourceStatus.FINISHED));
            assertThat(cooldown.admitted()).isFalse();
            assertThat(cooldown.nextDueAt()).isEqualTo(cooldownDue);
            assertNoEpisode(publishedSeason, 98101);

            clock.set(cooldownDue);
            var due = store.withinRuntimeFence(() -> publisher.admitAttempt(
                    publishedSeason, 98101, SourceStatus.FINISHED));
            assertThat(due.admitted()).isTrue();
            assertEpisode(publishedSeason, 98101, cooldownDue);
            assertThat(jdbc.queryForObject("SELECT dependency_retry_at FROM " + TABLES.fixtureSync()
                    + " WHERE season_id=? AND fixture_id=98101", java.sql.Timestamp.class,
                    publishedSeason.id())).isNull();
        } finally {
            jdbc.update("UPDATE season SET is_current=false");
            previousCurrent.forEach(id -> jdbc.update("UPDATE season SET is_current=true WHERE id=?", id));
            jdbc.update("DELETE FROM player_fixture_stats WHERE season_id=? AND fixture_id=98101",
                    publishedSeason.id());
        }
    }

    @Test
    void offActivationAndLateCallbackCannotExtendOrEraseAnEpisode() {
        seedFixture(NOW.minus(Duration.ofDays(2)), null, "NONE");
        var firstFence = activate();
        store = new OwnedSyncStore(jdbc, tx, clock, TABLES, firstFence);
        assertThat(store.withinRuntimeFence(() -> repository.admitAttempt(
                SEASON, FIXTURE_ID, clock.instant(), SourceStatus.FINISHED)).admitted()).isTrue();
        var oldRun = enqueueFixture(SEASON, FIXTURE_ID, clock.instant());
        Lease oldLease = store.acquire("old-worker", Duration.ofMinutes(5)).orElseThrow();
        assertThat(oldLease.generation()).isEqualTo(oldRun.generation());

        new PlannerRuntimeRepository(jdbc, tx, clock, TABLES).deactivate(firstFence);
        clock.advance(Duration.ofHours(4));
        var nextFence = activate();
        OwnedSyncStore nextStore = new OwnedSyncStore(jdbc, tx, clock, TABLES, nextFence);
        store = nextStore;
        AtomicBoolean callback = new AtomicBoolean();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new OwnedSyncStore(jdbc, tx, clock,
                TABLES, firstFence).publish(oldLease, new SourceRead(clock.instant(), clock.instant()), () -> {
                    callback.set(true);
                    return null;
                })).isInstanceOf(OwnedSyncStore.RuntimeFenceLostException.class);
        assertThat(callback.get()).isFalse();
        assertThat(store.withinRuntimeFence(() -> repository.admitAttempt(
                SEASON, FIXTURE_ID, clock.instant(), SourceStatus.FINISHED)).admitted()).isTrue();
        assertEpisode(NOW);

        clock.set(NOW.plus(Duration.ofHours(13)));
        var expired = store.withinRuntimeFence(() -> repository.admitAttempt(
                SEASON, FIXTURE_ID, clock.instant(), SourceStatus.FINISHED));
        assertThat(expired.admitted()).isFalse();
        assertThat(expired.nextDueAt()).isEqualTo(Instant.parse("2026-09-21T02:00:00Z"));
        assertEpisode(NOW);
    }

    @Test
    void transactionRollbackRestoresGrantFingerprintAndQueuedCorrectionTogether() {
        seedFixture(NOW.minus(Duration.ofDays(2)), null, "NONE");
        var fence = activate();
        store = new OwnedSyncStore(jdbc, tx, clock, TABLES, fence);
        Inputs input = inputs(new Season(9825, "2024-25", LocalDate.of(2024, 8, 16),
                LocalDate.of(2025, 5, 25)), NOW);

        tx.executeWithoutResult(status -> {
            store.withinRuntimeFence(() -> repository.admitAttempt(
                    SEASON, FIXTURE_ID, clock.instant(), SourceStatus.FINISHED));
            repository.reconcileHistoryFingerprints(input.histories().getFirst(), clock.instant());
            enqueueFixture(SEASON, FIXTURE_ID, clock.instant());
            status.setRollbackOnly();
        });

        assertNoEpisode();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.historyFingerprint(), Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE kind='FIXTURE_VERIFICATION'", Integer.class)).isZero();
    }

    @Test
    void expiredDispatcherLeaseCannotReserveRecoveryOrFetch() throws Exception {
        seedCurrentCanonicalParent();
        seedFixture(NOW.minus(Duration.ofDays(2)), null, "NONE");
        var fixtureRun = enqueueFixture(SEASON, FIXTURE_ID, NOW);
        Lease lease = store.acquire("expired-worker", Duration.ofMinutes(5))
                .filter(candidate -> candidate.generation() == fixtureRun.generation()).orElseThrow();
        Map<String, Object> before = fixtureRecoveryState();
        var fetch = new SingleFailureFetch(null);
        var publisher = new FixtureSyncPublisher(jdbc, clock, store,
                new FixtureSyncRepository(jdbc, TABLES), TABLES);
        var dispatcher = new HistoryFixtureDispatcher(store, publisher, fetch, clock);
        clock.advance(Duration.ofMinutes(6));

        assertThatThrownBy(() -> dispatcher.process(lease, SEASON))
                .isInstanceOf(OwnedSyncStore.LeaseLostException.class);

        assertThat(fetch.endpoints).isEmpty();
        assertThat(fixtureRecoveryState()).isEqualTo(before);
    }

    @Test
    void stolenSameEpochDispatcherLeaseCannotReserveRecoveryOrFetch() throws Exception {
        seedCurrentCanonicalParent();
        seedFixture(NOW.minus(Duration.ofDays(2)), null, "NONE");
        var fixtureRun = enqueueFixture(SEASON, FIXTURE_ID, NOW);
        Lease staleLease = store.acquire("old-worker", Duration.ofMinutes(5))
                .filter(candidate -> candidate.generation() == fixtureRun.generation()).orElseThrow();
        Map<String, Object> before = fixtureRecoveryState();
        var fetch = new SingleFailureFetch(null);
        var publisher = new FixtureSyncPublisher(jdbc, clock, store,
                new FixtureSyncRepository(jdbc, TABLES), TABLES);
        var dispatcher = new HistoryFixtureDispatcher(store, publisher, fetch, clock);
        clock.advance(Duration.ofMinutes(6));
        Lease replacement = store.acquire("replacement-worker", Duration.ofMinutes(5)).orElseThrow();

        assertThat(replacement.generation()).isEqualTo(staleLease.generation());
        assertThatThrownBy(() -> dispatcher.process(staleLease, SEASON))
                .isInstanceOf(OwnedSyncStore.LeaseLostException.class);

        assertThat(fetch.endpoints).isEmpty();
        assertThat(fixtureRecoveryState()).isEqualTo(before);
    }

    @Test
    void leaseExpiryAfterAdmissionCallbackRollsBackEveryRecoveryAndRetryField() throws Exception {
        seedCurrentCanonicalParent();
        Instant pastDue = NOW.minusSeconds(30);
        seedFixture(NOW.minus(Duration.ofDays(2)), pastDue, "DAILY_ONLY");
        jdbc.update("UPDATE " + TABLES.fixtureSync() + " SET due_reason='POLICY', transport_retry_at=?, "
                        + "dependency_retry_at=?, attempt_count=3, last_error='prior retry' "
                        + "WHERE season_id=? AND fixture_id=?",
                timestamp(NOW.minusSeconds(20)), timestamp(NOW.minusSeconds(10)), SEASON.id(), FIXTURE_ID);
        var fixtureRun = enqueueFixture(SEASON, FIXTURE_ID, NOW);
        Lease lease = store.acquire("expiring-worker", Duration.ofMinutes(1))
                .filter(candidate -> candidate.generation() == fixtureRun.generation()).orElseThrow();
        Map<String, Object> before = fixtureRecoveryState();
        var publisher = new FixtureSyncPublisher(jdbc, clock, store,
                new FixtureSyncRepository(jdbc, TABLES), TABLES);

        assertThatThrownBy(() -> store.withinRunLease(lease, () -> {
            var admission = publisher.admitAttempt(SEASON, FIXTURE_ID, SourceStatus.FINISHED);
            assertThat(admission.admitted()).isTrue();
            clock.set(lease.expiresAt());
            return admission;
        })).isInstanceOf(OwnedSyncStore.LeaseLostException.class);

        assertThat(fixtureRecoveryState()).isEqualTo(before);
    }

    @Test
    void concurrentLockedWorkersShareOneFixedRecoveryGrant() throws Exception {
        seedFixture(NOW.minus(Duration.ofDays(2)), null, "NONE");
        var fence = activate();
        store = new OwnedSyncStore(jdbc, tx, clock, TABLES, fence);
        var fixtureRun = enqueueFixture(SEASON, FIXTURE_ID, NOW);
        Lease lease = store.acquire("concurrent-worker", Duration.ofMinutes(5))
                .filter(candidate -> candidate.generation() == fixtureRun.generation()).orElseThrow();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var calls = List.of(workers.submit(() -> admitTogether(lease, ready, start)),
                    workers.submit(() -> admitTogether(lease, ready, start)));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(calls.get(0).get(10, TimeUnit.SECONDS).admitted()).isTrue();
            assertThat(calls.get(1).get(10, TimeUnit.SECONDS).admitted()).isTrue();
        }

        assertEpisode(NOW);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.fixtureSync()
                + " WHERE fixture_id=? AND recovery_day=?", Integer.class, FIXTURE_ID,
                SharedFixtureRecoveryPolicy.recoveryDayAt(NOW))).isEqualTo(1);
    }

    @Test
    void aTransportRetryInsideRecoveryUsesTheFailureTimeAndRemainsASeparateGate() {
        seedFixture(NOW.minus(Duration.ofDays(2)), null, "NONE");
        var fence = activate();
        store = new OwnedSyncStore(jdbc, tx, clock, TABLES, fence);
        var admitted = store.withinRuntimeFence(() -> repository.admitAttempt(
                SEASON, FIXTURE_ID, clock.instant(), SourceStatus.FINISHED));
        assertThat(admitted.admitted()).isTrue();

        clock.advance(Duration.ofMinutes(5));
        Instant minimumDue = clock.instant().plusSeconds(1);
        Instant retry = store.withinRuntimeFence(() -> repository.recordFailedAttempt(
                SEASON.code(), FIXTURE_ID, minimumDue, "HTTP 500", clock.instant()));

        Instant episodeDue = clock.instant().plus(Duration.ofMinutes(10));
        assertThat(retry).isEqualTo(episodeDue);
        assertThat(jdbc.queryForObject("SELECT due_reason FROM " + TABLES.fixtureSync()
                + " WHERE fixture_id=?", String.class, FIXTURE_ID)).isEqualTo("TRANSPORT");
        assertThat(jdbc.queryForObject("SELECT transport_retry_at FROM " + TABLES.fixtureSync()
                + " WHERE fixture_id=?", java.sql.Timestamp.class, FIXTURE_ID).toInstant())
                .isEqualTo(episodeDue);

        clock.advance(Duration.ofMinutes(1));
        var waiting = store.withinRuntimeFence(() -> repository.admitAttempt(
                SEASON, FIXTURE_ID, clock.instant(), SourceStatus.FINISHED));
        assertThat(waiting.admitted()).isFalse();
        assertThat(waiting.nextDueAt()).isEqualTo(episodeDue);
        assertEpisode(NOW);
    }

    @Test
    void lateDispatcherFailuresPersistTheFullRetryLadderFromEachFailureTime() throws Exception {
        seedCurrentCanonicalParent();
        seedFixture(NOW.minus(Duration.ofDays(2)), null, "NONE");
        store.withinRuntimeFence(() -> repository.admitAttempt(
                SEASON, FIXTURE_ID, clock.instant(), SourceStatus.FINISHED));
        clock.set(NOW.plus(Duration.ofHours(2)));

        var fetch = new SingleFailureFetch(null);
        var publisher = new FixtureSyncPublisher(jdbc, clock, store,
                new FixtureSyncRepository(jdbc, TABLES), TABLES);
        var dispatcher = new HistoryFixtureDispatcher(store, publisher, fetch, clock);
        List<Duration> delays = List.of(Duration.ofMinutes(10), Duration.ofMinutes(20),
                Duration.ofMinutes(40), Duration.ofMinutes(60));

        for (int attempt = 0; attempt < delays.size(); attempt++) {
            Instant failedAt = clock.instant();
            fetch.setResult(new OwnedSyncHttpClient.Response(500, new byte[0], null,
                    new SourceRead(failedAt, failedAt), failedAt.plusSeconds(5)));
            var run = dispatcher.enqueueFixtureVerification(SEASON, FIXTURE_ID, failedAt);
            Lease lease = store.acquire("late-failure-worker", Duration.ofMinutes(5))
                    .filter(candidate -> candidate.generation() == run.generation())
                    .orElseThrow(() -> new AssertionError("fixture run was not claimed"));

            DispatchResult result = dispatcher.process(lease, SEASON);

            Instant expectedDue = failedAt.plus(delays.get(attempt));
            assertThat(result.failure()).isEqualTo(FailureType.HTTP_FAILURE);
            assertThat(jdbc.queryForObject("SELECT next_due_at FROM " + TABLES.fixtureSync()
                    + " WHERE fixture_id=?", java.sql.Timestamp.class, FIXTURE_ID).toInstant())
                    .isEqualTo(expectedDue);
            assertThat(jdbc.queryForObject("SELECT transport_retry_at FROM " + TABLES.fixtureSync()
                    + " WHERE fixture_id=?", java.sql.Timestamp.class, FIXTURE_ID).toInstant())
                    .isEqualTo(expectedDue);
            assertThat(jdbc.queryForObject("SELECT due_at FROM " + TABLES.run()
                    + " WHERE generation=?", java.sql.Timestamp.class, run.generation()).toInstant())
                    .isEqualTo(expectedDue);
            assertThat(jdbc.queryForObject("SELECT due_reason FROM " + TABLES.fixtureSync()
                    + " WHERE fixture_id=?", String.class, FIXTURE_ID)).isEqualTo("TRANSPORT");
            if (attempt < delays.size() - 1) clock.set(expectedDue);
        }

        assertThat(fetch.endpoints).containsExactly(Endpoint.bootstrap(), Endpoint.bootstrap(),
                Endpoint.bootstrap(), Endpoint.bootstrap());
        assertEpisode(NOW);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM " + TABLES.fixtureSync()
                + " WHERE fixture_id=?", Integer.class, FIXTURE_ID)).isEqualTo(4);
    }

    @Test
    void currentPublishedParentRequiresCanonicalKeyPairAndNonFutureActiveEvidence() throws Exception {
        seedCurrentCanonicalParent();
        long canonicalGeneration = jdbc.queryForObject("SELECT generation FROM " + TABLES.run()
                + " WHERE season=? AND coalescing_key=? AND publication_key=?",
                Long.class, SEASON.code(), CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY);
        assertThat(store.isCurrentPublishedCanonicalPair(SEASON.code(), canonicalGeneration, NOW)).isTrue();
        assertThat(store.isCurrentPublishedCanonicalPair(SEASON.code(), canonicalGeneration,
                NOW.minusSeconds(21))).isFalse();
        var catalogueDispatcher = new CatalogueScheduleDispatcher(store, null,
                (endpoint, attempt) -> { throw new AssertionError("seeded parent must not fetch"); }, clock, false);
        long catalogueGeneration = publishParent(catalogueDispatcher.enqueueCatalogue(SEASON, clock.instant()));
        assertThat(store.isCurrentPublishedCanonicalPair(SEASON.code(), catalogueGeneration,
                clock.instant())).isTrue();
        jdbc.update("UPDATE " + TABLES.run() + " SET runtime_mode='STAGING' WHERE generation=?",
                catalogueGeneration);
        assertThat(store.isCurrentPublishedCanonicalPair(SEASON.code(), catalogueGeneration,
                clock.instant())).isFalse();

        long inventedGeneration = publishParent("invented-current-parent", "invented-current-parent");
        assertThat(store.isCurrentPublishedCanonicalPair(SEASON.code(), inventedGeneration, clock.instant())).isFalse();
        long mismatchedGeneration = publishParent(CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                "invented-schedule-publication");
        assertThat(store.isCurrentPublishedCanonicalPair(SEASON.code(), mismatchedGeneration, clock.instant())).isFalse();

        var stagingFence = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES)
                .activate(SEASON.code(), OwnedSyncMode.STAGING);
        var stagingStore = new OwnedSyncStore(jdbc, tx, clock, TABLES, stagingFence);
        assertThat(stagingStore.isCurrentPublishedCanonicalPair(SEASON.code(), canonicalGeneration,
                clock.instant())).isFalse();
    }

    private static void tickTogether(OwnedMatchdayCoordinator coordinator) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("tick start timed out");
                return coordinator.tick();
            });
            var second = workers.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("tick start timed out");
                return coordinator.tick();
            });
            if (!ready.await(5, TimeUnit.SECONDS)) throw new AssertionError("tick workers were not ready");
            start.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }
    }

    private OwnedSyncStore.RuntimeFence seedCurrentCanonicalParent() throws Exception {
        clock.set(NOW.minusSeconds(20));
        var fence = activate();
        store = new OwnedSyncStore(jdbc, tx, clock, TABLES, fence);
        var dispatcher = new CatalogueScheduleDispatcher(store, null,
                (endpoint, attempt) -> { throw new AssertionError("seeded parent must not fetch"); }, clock, false);
        long generation = publishParent(dispatcher.enqueueSchedule(SEASON, clock.instant()));
        assertThat(generation).isPositive();
        clock.set(NOW);
        return fence;
    }

    private long publishParent(String coalescingKey, String publicationKey) throws Exception {
        var run = store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(), coalescingKey,
                publicationKey, clock.instant());
        return publishParent(run);
    }

    private long publishParent(OwnedSyncStore.Run run) throws Exception {
        Lease lease = store.acquire("parent-worker", Duration.ofMinutes(5))
                .filter(candidate -> candidate.generation() == run.generation())
                .orElseThrow(() -> new AssertionError("canonical parent run was not claimed"));
        return publishParent(lease);
    }

    private long publishParent(Lease lease) throws Exception {
        Instant started = clock.instant().plusSeconds(1);
        Instant finished = started.plusSeconds(1);
        SourceRead read = new SourceRead(started, finished);
        clock.set(finished);
        store.stage(lease, Endpoint.bootstrap(), read, Validation.valid(),
                resource("bootstrap-static.json"));
        store.stage(lease, Endpoint.fixtures(), read, Validation.valid(), resource("fixtures.json"));
        store.publish(lease, read, () -> null);
        return lease.generation();
    }

    private static void createCapturedTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    season varchar(10) NOT NULL, price_date date NOT NULL, element_id integer NOT NULL,
                    web_name varchar(100) NOT NULL, full_name varchar(200), team_id integer,
                    team_short varchar(10), team_name varchar(100), element_type smallint,
                    now_cost integer NOT NULL, cost_change_event integer NOT NULL DEFAULT 0,
                    cost_change_start integer NOT NULL DEFAULT 0, selected_by_percent numeric(5,2),
                    status varchar(4), news text, penalties_order smallint, direct_fk_order smallint,
                    corners_order smallint, captured_at timestamptz NOT NULL DEFAULT now(),
                    PRIMARY KEY(season, price_date, element_id))
                """.formatted(CAPTURED.dailyPrice()));
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    season varchar(10) NOT NULL, id integer NOT NULL, name varchar(50),
                    deadline_time timestamptz NOT NULL, finished boolean NOT NULL DEFAULT false,
                    data_checked boolean NOT NULL DEFAULT false, is_current boolean NOT NULL DEFAULT false,
                    is_next boolean NOT NULL DEFAULT false, average_entry_score integer,
                    highest_score integer, most_captained integer, most_transferred_in integer,
                    captured_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(season,id))
                """.formatted(CAPTURED.gameweek()));
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    season varchar(10) NOT NULL, id integer NOT NULL, gameweek integer,
                    team_h integer NOT NULL, team_a integer NOT NULL, team_h_name varchar(100),
                    team_h_short varchar(10), team_a_name varchar(100), team_a_short varchar(10),
                    team_h_difficulty smallint, team_a_difficulty smallint, kickoff_time timestamptz,
                    started boolean NOT NULL DEFAULT false, finished boolean NOT NULL DEFAULT false,
                    team_h_score integer, team_a_score integer,
                    captured_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(season,id))
                """.formatted(CAPTURED.fixture()));
    }

    private static void cleanCapturedRows() {
        jdbc.update("DELETE FROM " + CAPTURED.fixture());
        jdbc.update("DELETE FROM " + CAPTURED.gameweek());
        jdbc.update("DELETE FROM " + CAPTURED.dailyPrice());
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream input = FixtureRecoveryAdmissionIntegrationTest.class.getResourceAsStream(
                "/owned-sync-source-fixtures/" + name)) {
            if (input == null) throw new IllegalArgumentException("Missing " + name);
            return input.readAllBytes();
        }
    }

    private OwnedSyncStore.Run enqueueFixture(Season season, int fixtureId, Instant dueAt) {
        String key = FixtureSyncPublisher.publicationKey(fixtureId);
        return store.enqueue(SyncKind.FIXTURE_VERIFICATION, season.code(), key, key, dueAt);
    }

    private FixtureVerification.Decision publish(FixtureSyncPublisher publisher, Season season, Inputs inputs) {
        var run = enqueueFixture(season, inputs.fixture().id(), clock.instant());
        Lease lease = store.acquire("fixture-publisher", Duration.ofMinutes(5))
                .filter(candidate -> candidate.generation() == run.generation())
                .orElseThrow(() -> new AssertionError("fixture publication run was not claimed"));
        return publisher.publishParsed(lease, season, inputs.fixture(), inputs.fixtureRead(),
                inputs.live(), inputs.histories());
    }

    private static Inputs inputs(Season season, Instant readAt) {
        FixtureRow fixture = fixture(season);
        ReadInterval fixtureRead = interval(readAt);
        SourceDocument live = new SourceDocument(("{\"elements\":[" + live(98201, 90)
                + "," + live(98202, 90) + "]}").getBytes(StandardCharsets.UTF_8), interval(readAt.plusSeconds(2)));
        HistoryBatch home = new HistoryBatch(season.id(), season.code(), 98201,
                interval(readAt.plusSeconds(4)), List.of(history(season, 98201, true, false)));
        HistoryBatch away = new HistoryBatch(season.id(), season.code(), 98202,
                interval(readAt.plusSeconds(4)), List.of(history(season, 98202, false, true)));
        return new Inputs(fixture, fixtureRead, live, List.of(home, away));
    }

    private static FixtureRow fixture(Season season) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("finished_provisional", true);
        fields.put("minutes", 90);
        fields.put("stats", "[{\"identifier\":\"minutes\",\"h\":[{\"element\":98201,\"value\":90}],"
                + "\"a\":[{\"element\":98202,\"value\":90}]}]");
        return new FixtureRow(season.id(), season.code(), 98101, 98101, 1, 9801, 9802,
                "Sync Home", "SYH", "Sync Away", "SYA", 2, 1, 2, 3,
                Instant.parse("2024-08-17T14:00:00Z"), true, true, fields);
    }

    private static HistoryRow history(Season season, int player, boolean home, boolean allOptional) {
        EnumSet<Component> components = allOptional ? EnumSet.allOf(Component.class)
                : EnumSet.of(Component.EXPECTED_GOALS);
        BigDecimal zero = BigDecimal.ZERO;
        return new HistoryRow(season.id(), season.code(), 98101, player, 1,
                home ? 9801 : 9802, home ? 9802 : 9801, home,
                Instant.parse("2024-08-17T14:00:00Z"), (short) 90, (short) 2, (short) 0,
                (short) 1, (short) 0, (short) 0, zero, allOptional ? zero : null,
                allOptional ? zero : null, (short) 0, (short) 1, (short) 0, (short) 0,
                (short) 0, (short) 0, allOptional ? zero : null, (short) 0, (short) 0,
                allOptional ? zero : null, allOptional ? zero : null, allOptional ? zero : null,
                allOptional ? zero : null, 75, 1, 0, 0, (short) 1,
                allOptional ? (short) 0 : null, allOptional ? (short) 0 : null,
                allOptional ? (short) 0 : null, allOptional ? (short) 0 : null, components);
    }

    private static String live(int player, int minutes) {
        return "{\"id\":" + player + ",\"explain\":[{\"fixture\":98101,\"stats\":["
                + "{\"identifier\":\"minutes\",\"value\":" + minutes
                + ",\"points\":2,\"points_modification\":0}]}]}";
    }

    private static ReadInterval interval(Instant instant) {
        return new ReadInterval(instant, instant.plusSeconds(1));
    }

    private Map<String, Object> fixtureRecoveryState() {
        return jdbc.queryForMap("SELECT state, first_finished_observed_at, next_due_at, due_reason, "
                + "alert_state, attempt_count, last_error, transport_retry_at, dependency_retry_at, "
                + "recovery_day, recovery_started_at, recovery_until, updated_at FROM "
                + TABLES.fixtureSync() + " WHERE season_id=? AND fixture_id=?", SEASON.id(), FIXTURE_ID);
    }

    private FixtureSyncRepository.AttemptAdmission admitTogether(Lease lease,
                                                                 CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
        return store.withinRunLease(lease, () -> repository.admitAttempt(
                SEASON, FIXTURE_ID, clock.instant(), SourceStatus.FINISHED));
    }

    private OwnedSyncStore.RuntimeFence activate() {
        return new PlannerRuntimeRepository(jdbc, tx, clock, TABLES).activate(SEASON.code(), OwnedSyncMode.ACTIVE);
    }

    private void seedExpiredEpisodeWithTransportRetry() {
        Instant episodeStart = Instant.parse("2026-09-20T07:00:00Z");
        Instant episodeUntil = episodeStart.plus(SharedFixtureRecoveryPolicy.FOLLOW_UP_LIMIT);
        Instant transportDue = Instant.parse("2026-09-20T20:05:00Z");
        clock.set(transportDue);
        seedFixture(episodeStart.minus(Duration.ofHours(13)), transportDue, "DAILY_ONLY");
        jdbc.update("UPDATE " + TABLES.fixtureSync()
                        + " SET recovery_day=?, recovery_started_at=?, recovery_until=?, due_reason='TRANSPORT',"
                        + " transport_retry_at=? WHERE season_id=? AND fixture_id=?",
                LocalDate.of(2026, 9, 20), timestamp(episodeStart), timestamp(episodeUntil),
                timestamp(transportDue), SEASON.id(), FIXTURE_ID);
    }

    private void assertExpiredEpisodeAndTransportUnchanged() {
        assertExpiredEpisodeAndTransportFieldsPreserved(Instant.parse("2026-09-20T20:05:00Z"));
    }

    private void assertExpiredEpisodeAndTransportFieldsPreserved(Instant expectedNextDue) {
        var row = jdbc.queryForMap("SELECT recovery_day, recovery_started_at, recovery_until, next_due_at, "
                + "transport_retry_at, attempt_count FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", SEASON.id(), FIXTURE_ID);
        assertThat(((java.sql.Date) row.get("recovery_day")).toLocalDate())
                .isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(((java.sql.Timestamp) row.get("recovery_started_at")).toInstant())
                .isEqualTo(Instant.parse("2026-09-20T07:00:00Z"));
        assertThat(((java.sql.Timestamp) row.get("recovery_until")).toInstant())
                .isEqualTo(Instant.parse("2026-09-20T19:00:00Z"));
        assertThat(((java.sql.Timestamp) row.get("next_due_at")).toInstant()).isEqualTo(expectedNextDue);
        assertThat(((java.sql.Timestamp) row.get("transport_retry_at")).toInstant())
                .isEqualTo(Instant.parse("2026-09-20T20:05:00Z"));
        assertThat((Integer) row.get("attempt_count")).isZero();
    }

    private void assertFixtureRetryStateUnchanged() {
        var row = jdbc.queryForMap("SELECT next_due_at, dependency_retry_at, last_error, updated_at, attempt_count "
                + "FROM " + TABLES.fixtureSync() + " WHERE season_id=? AND fixture_id=?",
                SEASON.id(), FIXTURE_ID);
        assertThat(row.get("next_due_at")).isNull();
        assertThat(row.get("dependency_retry_at")).isNull();
        assertThat(row.get("last_error")).isNull();
        assertThat(((java.sql.Timestamp) row.get("updated_at")).toInstant()).isEqualTo(NOW);
        assertThat(row.get("attempt_count")).isEqualTo(0);
    }

    private ManifestAnchors stageDependencyAnchors(Lease lease, Validation validation) {
        Instant floor = store.requestedSourceAfter(lease);
        SourceRead pairRead = new SourceRead(floor, floor.plusSeconds(1));
        SourceRead liveRead = new SourceRead(pairRead.finishedAt().plusMillis(1),
                pairRead.finishedAt().plusSeconds(2));
        clock.set(liveRead.finishedAt().plusSeconds(1));
        byte[] bootstrapBody = "{}".getBytes(StandardCharsets.UTF_8);
        byte[] fixturesBody = "[]".getBytes(StandardCharsets.UTF_8);
        byte[] liveBody = "{}".getBytes(StandardCharsets.UTF_8);
        long bootstrapId = store.stage(lease, Endpoint.bootstrap(), pairRead, validation, bootstrapBody);
        long fixturesId = store.stage(lease, Endpoint.fixtures(), pairRead, validation, fixturesBody);
        long liveId = store.stage(lease, Endpoint.eventLive(3), liveRead, validation, liveBody);
        StagedPayload bootstrap = new StagedPayload(bootstrapId, lease.generation(),
                OwnedSyncStore.EndpointKind.BOOTSTRAP_STATIC, pairRead, validation.state(), null, bootstrapBody);
        StagedPayload fixtures = new StagedPayload(fixturesId, lease.generation(),
                OwnedSyncStore.EndpointKind.FIXTURES, pairRead, validation.state(), null, fixturesBody);
        StagedPayload live = new StagedPayload(liveId, lease.generation(),
                OwnedSyncStore.EndpointKind.EVENT_LIVE, liveRead, validation.state(), null, liveBody);
        return new ManifestAnchors(new PublicPayloadPair(lease.generation(), bootstrap, fixtures), live);
    }

    private void seedFixture(Instant firstFinished, Instant nextDue, String alertState) {
        seedFixture(SEASON, FIXTURE_ID, firstFinished, nextDue, alertState);
    }

    private void seedFixture(Season season, int fixtureId, Instant firstFinished,
                             Instant nextDue, String alertState) {
        jdbc.update("""
                INSERT INTO %s(season_id, season_code, fixture_id, state, first_finished_observed_at,
                    next_due_at, alert_state, updated_at)
                VALUES (?, ?, ?, 'VERIFYING', ?, ?, ?, ?)
                """.formatted(TABLES.fixtureSync()), season.id(), season.code(), fixtureId,
                timestamp(firstFinished), timestamp(nextDue), alertState, timestamp(NOW));
    }

    private void assertEpisode(Instant startedAt) {
        assertEpisode(SEASON, FIXTURE_ID, startedAt);
    }

    private void assertEpisode(Season season, int fixtureId, Instant startedAt) {
        var row = jdbc.queryForMap("SELECT recovery_day, recovery_started_at, recovery_until FROM "
                + TABLES.fixtureSync() + " WHERE season_id=? AND fixture_id=?", season.id(), fixtureId);
        assertThat(((java.sql.Date) row.get("recovery_day")).toLocalDate())
                .isEqualTo(SharedFixtureRecoveryPolicy.recoveryDayAt(startedAt));
        assertThat(((java.sql.Timestamp) row.get("recovery_started_at")).toInstant()).isEqualTo(startedAt);
        assertThat(((java.sql.Timestamp) row.get("recovery_until")).toInstant())
                .isEqualTo(startedAt.plus(SharedFixtureRecoveryPolicy.FOLLOW_UP_LIMIT));
    }

    private void assertNoEpisode() {
        assertNoEpisode(SEASON, FIXTURE_ID);
    }

    private void assertNoEpisode(Season season, int fixtureId) {
        assertThat(jdbc.queryForObject("SELECT recovery_started_at FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", java.sql.Timestamp.class,
                season.id(), fixtureId)).isNull();
    }

    private static java.sql.Timestamp timestamp(Instant value) {
        return value == null ? null : java.sql.Timestamp.from(value);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static final class SingleFailureFetch implements HistoryFixtureDispatcher.Fetch {
        private OwnedSyncHttpClient.Result result;
        private final List<Endpoint> endpoints = new ArrayList<>();

        private SingleFailureFetch(OwnedSyncHttpClient.Result result) { this.result = result; }
        private void setResult(OwnedSyncHttpClient.Result next) { result = next; }

        @Override public OwnedSyncHttpClient.Result get(Endpoint endpoint, int failureAttempt) {
            endpoints.add(endpoint);
            return result;
        }

        @Override public OwnedSyncHttpClient.Result get(Lease lease, Endpoint endpoint,
                                                        int failureAttempt, Instant sourceFloor) {
            return get(endpoint, failureAttempt);
        }
    }

    private record Inputs(FixtureRow fixture, ReadInterval fixtureRead,
                          SourceDocument live, List<HistoryBatch> histories) { }

    private record ManifestAnchors(PublicPayloadPair pair, StagedPayload live) { }

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        void set(Instant value) { now = value; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
