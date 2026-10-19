package dev.quantti.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariDataSource;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import dev.quantti.fplassistant.sync.CatalogueScheduleParser.Season;
import dev.quantti.fplassistant.defcon.DefensiveStatsSchema;
import dev.quantti.fplassistant.sync.HistoryParser.HistoryRow;
import dev.quantti.fplassistant.sync.OwnedSyncStore.EndpointKind;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Lease;
import dev.quantti.fplassistant.sync.OwnedSyncStore.RuntimeFence;
import dev.quantti.fplassistant.sync.OwnedSyncStore.SourceRead;
import dev.quantti.fplassistant.sync.OwnedSyncStore.SyncKind;
import dev.quantti.fplassistant.sync.OwnedSyncHttpClient.WaitKind;
import dev.quantti.fplassistant.sync.OwnedSyncHttpClient.WaitObservation;
import dev.quantti.fplassistant.sync.OwnedSyncHttpClient.WaitPhase;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Validation;
import dev.quantti.fplassistant.sync.OwnedSyncStore.ValidationState;
import dev.quantti.fplassistant.testsupport.TapasSeed;
import dev.quantti.fplassistant.tools.PfsReadSource;

/** Failing baseline for real two-worker HTTP admission under a fixture-first backlog. */
class OwnedMatchdayAdmissionProgressIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OwnedSyncTables TABLES = new OwnedSyncTables(
            "fpl_acc_run_test", "fpl_acc_payload_test", "fpl_acc_receipt_test",
            "fpl_acc_request_state_test", "fpl_acc_request_attempt_test", "fpl_acc_fixture_test",
            "fpl_acc_pfs_evidence_test", "fpl_acc_planner_slot_test", "fpl_acc_planner_runtime_test",
            "fpl_acc_fixture_manifest_test");
    private static final CatalogueScheduleTables CAPTURED = new CatalogueScheduleTables(
            "fpl_acc_daily_price_test", "fpl_acc_gameweek_test", "fpl_acc_fixture_capture_test");
    private static final Season SEASON = new Season(27, "2026-27", LocalDate.of(2026, 8, 21), null);
    private static final Instant START = Instant.parse("2026-10-01T12:00:00Z");
    private static final Duration RESPONSE_TIME = Duration.ofMillis(100);
    private static final Duration PLANNER_TICK_PENDING_RESPONSE = Duration.ofMinutes(1).plusMillis(100);
    private static final Duration WORKER_DELAY = Duration.ofSeconds(1);
    private static final Duration VIRTUAL_LIMIT = Duration.ofSeconds(60);
    private static final Duration DRAIN_VIRTUAL_LIMIT = Duration.ofHours(2);
    private static final int ACTIVE_RECOVERY_DISPATCH_LIMIT = 6_000;
    private static final ZoneId UK = ZoneId.of("Europe/London");
    private static final Instant ACTIVE_PRICE_CAPTURE = LocalDate.of(2026, 10, 1)
            .atTime(2, 0).atZone(UK).toInstant();
    private static final Instant ACTIVE_SCHEDULE_CAPTURE = LocalDate.of(2026, 10, 1)
            .atTime(2, 10).atZone(UK).toInstant();
    private static final Instant ACTIVE_RECOVERY_START = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant LEGACY_GATE_OBSERVED_AT = Instant.parse("2026-09-30T10:00:00Z");
    private static final Instant LEGACY_DAILY_DUE = Instant.parse("2026-10-01T02:00:00Z");
    private static final Instant BASELINE_SOURCE_START = Instant.parse("2026-09-26T12:00:00Z");
    private static final Instant SAME_FINGERPRINT_CORRECTION =
            BASELINE_SOURCE_START.plus(Duration.ofHours(49));
    private static final int DISPATCH_LIMIT = 150;
    private static final int DRAIN_ADMISSION_LIMIT = 5_000;
    private static final int DRAIN_DISPATCH_LIMIT = 6_000;
    private static final long ROSTER_BASELINE_DRAIN_RESERVE_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final int FIXTURE_RUNS = 50;
    private static final int HISTORY_RUNS = 68;
    private static final int FULL_ROSTER_PLAYERS = 657;
    private static final int SEEDED_PARTIALS = 10;
    private static final long WALL_LIMIT_NANOS = TimeUnit.SECONDS.toNanos(110);
    private static final long DRAIN_CLEANUP_RESERVE_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static SourceData source;

    @BeforeAll
    static void initialize() throws Exception {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(required("DATABASE_URL"));
        dataSource.setUsername(required("DB_USER"));
        dataSource.setPassword(required("DB_PASSWORD"));
        dataSource.setMaximumPoolSize(3);
        dataSource.setMinimumIdle(1);
        dataSource.setConnectionTimeout(2_000);
        dataSource.addDataSourceProperty("connectTimeout", "3");
        dataSource.addDataSourceProperty("socketTimeout", "10");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(10);
        TapasSeed.ensureOwnedSyncCaptureSeason(jdbc);
        new OwnedSyncSchema(jdbc, TABLES).initialize();
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.setTimeout(30);
        source = SourceData.load();
    }

    @AfterAll
    static void closePool() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void minutePlannerTickObservesPublishedParentBeforeFirstPendingDiagnostic() throws Exception {
        SourceData activeSource = SourceData.load();
        Set<Integer> neverPublished = java.util.stream.IntStream.rangeClosed(41, 50).boxed()
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        prepareActiveAcceptanceSeason();
        MutableClock clock = new MutableClock(BASELINE_SOURCE_START);
        long wallStartedAt = System.nanoTime();
        long wallDeadline = wallStartedAt + TimeUnit.MINUTES.toNanos(4);
        long baselineEpoch = -1;
        try {
            try (Harness baseline = Harness.active(1, wallDeadline, wallStartedAt, activeSource,
                    clock, true, false)) {
                baselineEpoch = baseline.fence.epoch();
                baseline.catalogue.enqueueCycle(SEASON, clock.instant());
                assertThat(baseline.dispatchOneWithControlledResponse("schedule-boundary-baseline-price"))
                        .isEqualTo(1);
                assertThat(baseline.dispatchOneWithControlledResponse("schedule-boundary-baseline-schedule"))
                        .isEqualTo(1);
                new PlannerInputRepository(jdbc, baseline.store, TABLES, CAPTURED, clock).load(SEASON);
                advanceGlobalTimeline(clock, LEGACY_GATE_OBSERVED_AT);
                baseline.catalogue.enqueueSchedule(SEASON, clock.instant());
                assertThat(baseline.dispatchOneWithControlledResponse("schedule-boundary-old-root"))
                        .isEqualTo(1);
                new PlannerInputRepository(jdbc, baseline.store, TABLES, CAPTURED, clock).load(SEASON);
                advanceGlobalTimeline(clock, ACTIVE_RECOVERY_START);
                seedLegacyDailyOnlyGates(baseline, neverPublished);
            }

            try (Harness resumed = Harness.active(1, wallDeadline, wallStartedAt, activeSource,
                    clock, false, true)) {
                var beforePrice = resumed.coordinator.tick();
                assertThat(beforePrice.nextDueAt()).isEqualTo(ACTIVE_PRICE_CAPTURE);
                Instant previousScheduleSlot = Instant.parse("2026-09-30T01:10:00Z");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.plannerSlot()
                        + " WHERE season=? AND kind='DAILY_SCHEDULE' AND due_at=?", Integer.class,
                        SEASON.code(), Timestamp.from(previousScheduleSlot))).isEqualTo(1);

                advanceGlobalTimeline(clock, ACTIVE_PRICE_CAPTURE);
                resumed.coordinator.tick();
                assertThat(resumed.dispatchOneWithControlledResponse("schedule-boundary-price"))
                        .isEqualTo(1);
                assertThat(resumed.store.currentCanonicalParentEvidence(SEASON.code(), 41, clock.instant())
                        .currentPublished()).isTrue();
                assertThat(resumed.dispatchOneWithControlledResponse("schedule-boundary-before-cutover"))
                        .isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT source_read_started_at FROM " + TABLES.receipt()
                        + " WHERE season=? AND publication_key=?", Timestamp.class,
                        SEASON.code(), CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY).toInstant())
                        .isEqualTo(LEGACY_GATE_OBSERVED_AT);

                Instant beforeScheduleCapture = ACTIVE_SCHEDULE_CAPTURE.minusSeconds(1);
                advanceGlobalTimeline(clock, beforeScheduleCapture);
                var beforeScheduledSlot = resumed.coordinator.tick();
                assertThat(beforeScheduledSlot.nextDueAt()).isEqualTo(ACTIVE_SCHEDULE_CAPTURE);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.plannerSlot()
                        + " WHERE season=? AND kind='DAILY_SCHEDULE' AND due_at=?", Integer.class,
                        SEASON.code(), Timestamp.from(ACTIVE_SCHEDULE_CAPTURE))).isZero();
                Map<String, Object> scheduleBeforeCapture = jdbc.queryForMap("SELECT generation,due_at "
                        + "FROM " + TABLES.run() + " WHERE season=? AND runtime_mode='ACTIVE' AND runtime_epoch=? "
                        + "AND coalescing_key=? AND publication_key=? AND state='PENDING'",
                        SEASON.code(), resumed.fence.epoch(), CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                        CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY);
                assertThat(((Timestamp) scheduleBeforeCapture.get("due_at")).toInstant())
                        .isEqualTo(LEGACY_DAILY_DUE);

                advanceGlobalTimeline(clock, ACTIVE_SCHEDULE_CAPTURE);
                resumed.coordinator.tick();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.plannerSlot()
                        + " WHERE season=? AND kind='DAILY_SCHEDULE' AND due_at=?", Integer.class,
                        SEASON.code(), Timestamp.from(ACTIVE_SCHEDULE_CAPTURE))).isEqualTo(1);
                Map<String, Object> scheduleRun = jdbc.queryForMap("SELECT generation,due_at,requested_source_after "
                        + "FROM " + TABLES.run() + " WHERE season=? AND runtime_mode='ACTIVE' AND runtime_epoch=? "
                        + "AND coalescing_key=? AND publication_key=? AND state='PENDING'",
                        SEASON.code(), resumed.fence.epoch(), CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                        CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY);
                assertThat(scheduleRun.get("generation")).isEqualTo(scheduleBeforeCapture.get("generation"));
                assertThat(((Timestamp) scheduleRun.get("due_at")).toInstant())
                        .isEqualTo(ACTIVE_SCHEDULE_CAPTURE);
                assertThat(((Timestamp) scheduleRun.get("requested_source_after")).toInstant())
                        .isEqualTo(ACTIVE_SCHEDULE_CAPTURE);
                assertThat(resumed.dispatchOneWithControlledResponse("schedule-boundary-at-capture"))
                        .isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT source_read_started_at FROM " + TABLES.receipt()
                        + " WHERE season=? AND publication_key=?", Timestamp.class,
                        SEASON.code(), CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY).toInstant())
                        .isEqualTo(LEGACY_GATE_OBSERVED_AT);
                Instant stagedStart = jdbc.queryForObject("SELECT min(p.read_started_at) FROM " + TABLES.payload()
                        + " p JOIN " + TABLES.run() + " r ON r.generation=p.run_generation "
                        + "WHERE r.generation=? AND p.endpoint_kind IN ('BOOTSTRAP_STATIC','FIXTURES') "
                        + "AND p.validation_state='VALID'", Timestamp.class,
                        ((Number) scheduleRun.get("generation")).longValue()).toInstant();
                assertThat(stagedStart).isEqualTo(ACTIVE_SCHEDULE_CAPTURE);
                assertThat(jdbc.queryForObject("SELECT due_at FROM " + TABLES.run()
                        + " WHERE generation=?", Timestamp.class,
                        ((Number) scheduleRun.get("generation")).longValue()).toInstant())
                        .isEqualTo(LEGACY_DAILY_DUE);

                advanceGlobalTimeline(clock, LEGACY_DAILY_DUE);
                resumed.coordinator.tick();
                assertThat(resumed.store.currentCanonicalParentEvidence(SEASON.code(), 41, clock.instant())
                        .currentPublished()).isTrue();
                var beforeFreshReceipt = new PlannerInputRepository(jdbc, resumed.store, TABLES, CAPTURED, clock)
                        .load(SEASON);
                var oldPublishedFixture = beforeFreshReceipt.fixtures().stream()
                        .filter(row -> row.id() == 41).findFirst().orElseThrow();
                assertThat(oldPublishedFixture.recoverySourceStatus())
                        .as("the existing exact current publication remains known before the due capture is published")
                        .isEqualTo(dev.quantti.fplassistant.sync.SharedFixtureRecoveryPolicy.SourceStatus.FINISHED);
                assertThat(pendingFixtureRuns(resumed.fence.epoch(), neverPublished))
                        .as("a valid old current publication can materialize overdue recovery before the new receipt")
                        .isEqualTo(neverPublished.size());
                ManualDriver preparationGoal = new ManualDriver(resumed,
                        DriverGoal.firstPendingFixtures(neverPublished), Duration.ofMinutes(15), 200, 200, 200,
                        PLANNER_TICK_PENDING_RESPONSE);
                preparationGoal.maxActiveWorkers = 1;
                assertThat(preparationGoal.hasDueCanonicalRuns()).isTrue();
                assertThat(preparationGoal.goalSatisfied())
                        .as("no target can become first-pending before its new parent is observed")
                        .isFalse();

                assertThat(resumed.dispatchOneWithControlledResponse("schedule-boundary-publication"))
                        .isEqualTo(1);
                assertDailyCapture(CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY, ACTIVE_SCHEDULE_CAPTURE);
                assertThat(jdbc.queryForObject("SELECT source_read_started_at FROM " + TABLES.receipt()
                        + " WHERE season=? AND publication_key=?", Timestamp.class,
                        SEASON.code(), CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY).toInstant())
                        .isEqualTo(ACTIVE_SCHEDULE_CAPTURE);
                Instant parentPublishedAt = clock.instant();
                ParentSelectionDiagnostic initialParent = plannerParentSelection(resumed, 41);
                Map<String, Object> initialMarker = targetFinishedMarker(41);
                List<Map<String, Object>> initialRootReceipts = canonicalRootReceipts();
                var initialParentEvidence = resumed.store.currentCanonicalParentEvidence(
                        SEASON.code(), 41, clock.instant());
                System.out.printf("OWNED_PLANNER_MARKER initialAt=%s newestValid=%s currentPublished=%s "
                                + "marker=%s parent=%s nextPlannerTick=%s roots=%s%n",
                        clock.instant(), initialParent.newestValidCandidate(), initialParent.currentPublishedParent(),
                        initialMarker, initialParentEvidence, preparationGoal.nextPlannerTickAt, initialRootReceipts);
                assertThat(initialParent.currentPublishedParent().publicationGeneration())
                        .as("the selected exact source pair is current-published")
                        .isNotNull();
                assertThat(initialParent.currentPublishedParent().fixtureFinished())
                        .as("the selected parent body itself says this target is finished")
                        .isTrue();
                assertThat(initialParentEvidence).isEqualTo(
                        new OwnedSyncStore.CanonicalParentEvidence(true, false));
                assertThat(markerMatches(initialMarker, initialParent.currentPublishedParent())).isFalse();
                assertCanonicalSourceInterval(initialParent.currentPublishedParent(), initialRootReceipts);

                Set<Integer> firstFixturePlayers = activeSource.requiredHistoryPlayersByFixture(50).get(41);
                int timingHistoryPlayer = activeSource.playerIds().stream()
                        .filter(playerId -> !firstFixturePlayers.contains(playerId)).findFirst().orElseThrow();
                Instant tickAt = preparationGoal.nextPlannerTickAt;
                resumed.histories.enqueuePlayerHistory(SEASON, timingHistoryPlayer, tickAt.minusMillis(50));
                resumed.http.setWaitObserverForTests(preparationGoal::observeWait);
                preparationGoal.run();

                ParentSelectionDiagnostic observedParent = plannerParentSelection(resumed, 41);
                Map<String, Object> observedMarker = targetFinishedMarker(41);
                var observedParentEvidence = resumed.store.currentCanonicalParentEvidence(
                        SEASON.code(), 41, clock.instant());
                List<Map<String, Object>> finalRootReceipts = canonicalRootReceipts();
                var minuteTick = preparationGoal.plannerTicks.stream()
                        .filter(tick -> tick.at().equals(tickAt)).findFirst().orElseThrow();
                var plannerInputAtOneMinute = preparationGoal.plannerSnapshotAtFirstFixedTick;
                assertThat(plannerInputAtOneMinute).isNotNull();
                var plannerFixtureAtOneMinute = plannerInputAtOneMinute.fixtures().stream()
                        .filter(row -> row.id() == 41).findFirst().orElseThrow();
                System.out.printf("OWNED_PLANNER_MARKER next=%s newestValid=%s currentPublished=%s marker=%s "
                                + "parent=%s roots=%s tick=%s stop=%s claims=%d dispatches=%d firstPending=%s "
                                + "targetTransitions=%s gates=%s ticks=%s admissions=%s runStates=%s fixtureRuns=%s "
                                + "virtualElapsed=%s%n",
                        clock.instant(), observedParent.newestValidCandidate(), observedParent.currentPublishedParent(),
                        observedMarker, observedParentEvidence, finalRootReceipts, minuteTick,
                        preparationGoal.stopReason, preparationGoal.claims, preparationGoal.dispatches,
                        preparationGoal.firstPendingObservations, preparationGoal.targetTransitions,
                        fixtureGateTrace(neverPublished), preparationGoal.plannerTicks,
                        preparationGoal.admissions.subList(Math.max(0, preparationGoal.admissions.size() - 20),
                                preparationGoal.admissions.size()),
                        jdbc.queryForList("SELECT kind,state,count(*) count,max(attempt_count) attempts,"
                                + "min(due_at) min_due,max(due_at) max_due FROM " + TABLES.run()
                                + " WHERE season=? AND runtime_mode='ACTIVE' AND runtime_epoch=? GROUP BY kind,state "
                                + "ORDER BY kind,state", SEASON.code(), resumed.fence.epoch()),
                        jdbc.queryForList("SELECT generation,publication_key,state,attempt_count,due_at,"
                                + "requested_source_after FROM " + TABLES.run()
                                + " WHERE season=? AND runtime_mode='ACTIVE' AND runtime_epoch=?"
                                + " AND kind='FIXTURE_VERIFICATION' AND publication_key IN ("
                                + neverPublished.stream().sorted().map(id -> "'fixture:" + id + "'")
                                        .collect(java.util.stream.Collectors.joining(","))
                                + ") ORDER BY generation", SEASON.code(), resumed.fence.epoch()),
                        Duration.between(preparationGoal.startAt, clock.instant()));
                assertThat(preparationGoal.stopReason).isIn("DRAINED", "VIRTUAL_LIMIT");
                assertThat(preparationGoal.claims).isLessThanOrEqualTo(200);
                assertThat(Duration.between(preparationGoal.startAt, clock.instant()))
                        .isLessThanOrEqualTo(Duration.ofMinutes(15));
                assertThat(minuteTick.at()).isEqualTo(preparationGoal.plannerTicks.getFirst().at()
                        .plus(Duration.ofMinutes(1)));
                assertThat(Duration.between(parentPublishedAt, minuteTick.at()))
                        .isLessThanOrEqualTo(Duration.ofMinutes(1));
                assertThat(minuteTick.activeWorkers()).isNotEmpty();
                assertThat(minuteTick.pendingResponseReleases()).contains(tickAt.plusMillis(100));
                assertThat(minuteTick.acknowledgedWorkerStates().values())
                        .anySatisfy(state -> assertThat(state).startsWith("PENDING_RESPONSE"));
                assertThat(plannerFixtureAtOneMinute.recoverySourceStatus())
                        .isEqualTo(dev.quantti.fplassistant.sync.SharedFixtureRecoveryPolicy.SourceStatus.FINISHED);
                assertThat(minuteTick.completedWorkers())
                        .as("an in-flight source callback is not collected before its controlled response release")
                        .isEmpty();
                Set<Integer> callbacksInFlight = minuteTick.acknowledgedWorkerStates().entrySet().stream()
                        .filter(entry -> entry.getValue().startsWith("PENDING_RESPONSE"))
                        .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
                assertThat(callbacksInFlight).isNotEmpty();
                assertThat(minuteTick.workerGenerations().keySet()).containsAll(callbacksInFlight);
                assertThat(preparationGoal.plannerTicks.getFirst().activeWorkers())
                        .as("the initial zero-delay planner tick runs while the driver is idle")
                        .isEmpty();
                if (preparationGoal.firstPendingObservations.isEmpty()) {
                    assertThat(preparationGoal.stopReason)
                            .as("this bounded diagnostic is not target acceptance")
                            .isEqualTo("VIRTUAL_LIMIT");
                } else {
                    assertThat(preparationGoal.firstPendingObservations.keySet())
                            .containsAnyElementsOf(neverPublished);
                }
                assertThat(observedParent.currentPublishedParent())
                        .isEqualTo(initialParent.currentPublishedParent());
                assertThat(observedParent.newestValidCandidate())
                        .isEqualTo(initialParent.newestValidCandidate());
                assertThat(observedParentEvidence).isEqualTo(
                        new OwnedSyncStore.CanonicalParentEvidence(true, true));
                assertThat(markerMatches(observedMarker, observedParent.currentPublishedParent())).isTrue();
                assertThat(observedMarker.get("first_finished_observed_at"))
                        .isEqualTo(initialMarker.get("first_finished_observed_at"));
                assertThat(finalRootReceipts).as("both canonical parent keys remain stable after the marker is known")
                        .isEqualTo(initialRootReceipts);
                assertThat(canonicalAttemptsAfter(resumed, minuteTick.at()))
                        .as("no canonical root refresh follows the marker")
                        .isZero();
                for (int index = 1; index < preparationGoal.plannerTicks.size(); index++) {
                    assertThat(Duration.between(preparationGoal.plannerTicks.get(index - 1).at(),
                            preparationGoal.plannerTicks.get(index).at()))
                            .as("manual planner ticks use fixed delay, not the plan's next source due")
                            .isEqualTo(Duration.ofMinutes(1));
                }
                assertThat(activeClaimAttempts(baselineEpoch)
                        + activeClaimAttempts(resumed.fence.epoch())).isLessThan(300);
            }
        } finally {
            cleanActiveAcceptanceSeason();
        }
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void diagnosticNewestUnpublishedPairDoesNotShadowPublishedPlannerParent() throws Exception {
        SourceData activeSource = SourceData.load();
        prepareActiveAcceptanceSeason();
        MutableClock clock = new MutableClock(BASELINE_SOURCE_START);
        long wallStartedAt = System.nanoTime();
        long wallDeadline = wallStartedAt + TimeUnit.SECONDS.toNanos(150);
        try (Harness harness = Harness.active(1, wallDeadline, wallStartedAt, activeSource,
                clock, true, false)) {
            harness.dispatchCanonicalCycle();
            var repository = new PlannerInputRepository(jdbc, harness.store, TABLES, CAPTURED, clock);
            repository.load(SEASON);
            ParentSelectionDiagnostic published = plannerParentSelection(harness, 41);
            assertThat(published.newestValidCandidate())
                    .isEqualTo(published.currentPublishedParent());

            harness.seedNewerUnpublishedCanonicalPairWithoutAdvancingDemand();
            ParentSelectionDiagnostic afterUnpublished = plannerParentSelection(harness, 41);
            var plannerInput = repository.load(SEASON);
            var fixture = plannerInput.fixtures().stream().filter(row -> row.id() == 41)
                    .findFirst().orElseThrow();
            Map<String, Object> marker = targetFinishedMarker(41);
            System.out.printf("OWNED_UNPUBLISHED_PARENT newestValid=%s currentPublished=%s visited=%d "
                            + "plannerSource=%s marker=%s currentPublishedEvidence=%s%n",
                    afterUnpublished.newestValidCandidate(), afterUnpublished.currentPublishedParent(),
                    afterUnpublished.parseableCandidatesVisited(), fixture.recoverySourceStatus(), marker,
                    harness.store.currentCanonicalParentEvidence(SEASON.code(), 41, clock.instant()));
            assertThat(afterUnpublished.newestValidCandidate().publicationGeneration()).isNull();
            assertThat(afterUnpublished.newestValidCandidate().sourceGeneration())
                    .isGreaterThan(published.currentPublishedParent().sourceGeneration());
            assertThat(afterUnpublished.newestValidCandidate().fixturesRead().finishedAt())
                    .isAfter(published.currentPublishedParent().fixturesRead().finishedAt());
            assertThat(afterUnpublished.currentPublishedParent())
                    .isEqualTo(published.currentPublishedParent());
            assertThat(fixture.recoverySourceStatus())
                    .isEqualTo(dev.quantti.fplassistant.sync.SharedFixtureRecoveryPolicy.SourceStatus.FINISHED);
            assertThat(markerMatches(marker, afterUnpublished.currentPublishedParent())).isTrue();
            assertThat(nullableLong(marker.get("finished_parent_generation")))
                    .isEqualTo(afterUnpublished.currentPublishedParent().sourceGeneration());
            assertThat(nullableLong(marker.get("finished_parent_publication_generation")))
                    .isEqualTo(afterUnpublished.currentPublishedParent().publicationGeneration());
            assertThat(nullableLong(marker.get("finished_parent_bootstrap_payload_id")))
                    .isEqualTo(afterUnpublished.currentPublishedParent().bootstrapPayloadId());
            assertThat(nullableLong(marker.get("finished_parent_fixtures_payload_id")))
                    .isEqualTo(afterUnpublished.currentPublishedParent().fixturesPayloadId());
        } finally {
            cleanActiveAcceptanceSeason();
        }
    }

    @Test
    void activeAcceptanceCleanupPreservesSharedCaptureSeasonAndOtherFixtureEvidence() {
        OwnedSyncTables otherClassTables = OwnedSyncTables.tests();
        new OwnedSyncSchema(jdbc, otherClassTables).initialize();
        int fixtureId = 99027;
        int seeded = 0;
        try {
            seeded = jdbc.update("INSERT INTO " + otherClassTables.fixtureSync()
                            + "(season_id,season_code,fixture_id,state,updated_at) "
                            + "VALUES (27,'2026-27',?,'SCHEDULED',?)",
                    fixtureId, Timestamp.from(START));
            assertThat(seeded).isOne();

            prepareActiveAcceptanceSeason();
            assertThat(jdbc.queryForObject("SELECT code FROM season WHERE id=27", String.class))
                    .isEqualTo("2026-27");
            assertThat(jdbc.queryForObject("SELECT start_date FROM season WHERE id=27", java.sql.Date.class))
                    .isEqualTo(java.sql.Date.valueOf("2026-08-21"));
            assertThat(jdbc.queryForObject("SELECT is_current FROM season WHERE id=27", Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + otherClassTables.fixtureSync()
                    + " WHERE season_id=27 AND fixture_id=?)", Boolean.class, fixtureId)).isTrue();

            cleanActiveAcceptanceSeason();
            assertThat(jdbc.queryForObject("SELECT code FROM season WHERE id=27", String.class))
                    .isEqualTo("2026-27");
            assertThat(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + otherClassTables.fixtureSync()
                    + " WHERE season_id=27 AND fixture_id=?)", Boolean.class, fixtureId)).isTrue();
            assertThat(jdbc.queryForObject("SELECT is_current FROM season WHERE id=?", Boolean.class,
                    TapasSeed.CURRENT_SEASON_ID)).isTrue();
        } finally {
            try {
                cleanActiveAcceptanceSeason();
            } finally {
                if (seeded == 1) {
                    jdbc.update("DELETE FROM " + otherClassTables.fixtureSync()
                            + " WHERE season_id=27 AND fixture_id=?", fixtureId);
                }
            }
        }
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void compactActiveBaselineAndOracleRetainRealReceiptsAcrossReactivation() throws Exception {
        SourceData compactSource = SourceData.load();
        Set<Integer> fixtureIds = Set.of(1);
        prepareActiveAcceptanceSeason();
        MutableClock clock = new MutableClock(BASELINE_SOURCE_START);
        long wallStartedAt = System.nanoTime();
        long wallDeadline = wallStartedAt + TimeUnit.MINUTES.toNanos(4);
        String publicationChecksum;
        String fingerprintChecksum;
        Map<Integer, Long> initialReceipts;
        Map<Integer, Long> correctionReceipts;
        Map<Integer, Instant> firstVerifiedAt;
        long baselineEpoch;
        try {
            try (Harness baseline = Harness.active(1, wallDeadline, wallStartedAt, compactSource,
                    clock, true, false)) {
                baseline.dispatchCanonicalCycle();
                new PlannerInputRepository(jdbc, baseline.store, TABLES, CAPTURED, clock).load(SEASON);
                reconcileHistoryFingerprints(baseline, fixtureIds);
                Map<Integer, FixtureVerification.Decision> first = publishDirectFixtureBatch(baseline, 1, 1);
                assertThat(first.get(1).state()).isEqualTo(FixtureVerification.State.PENDING);
                assertThat(first.get(1).reasons())
                        .containsExactly(FixtureVerification.Reason.NEEDS_SECOND_OBSERVATION);
                Instant firstFinished = first.get(1).observation().reads().completedAt();
                advanceGlobalTimeline(clock, firstFinished.plus(Duration.ofMinutes(11)));
                Map<Integer, FixtureVerification.Decision> verified = publishDirectFixtureBatch(baseline, 1, 1);
                assertThat(verified.get(1).state()).isEqualTo(FixtureVerification.State.VERIFIED);
                assertThat(verified.get(1).observation().reads().startedAt())
                        .isAfterOrEqualTo(firstFinished.plus(Duration.ofMinutes(10)));
                assertTargetReceiptIntervalNegativeCases(baseline, 1);
                assertBaselineCandidateCoverage(fixtureIds, compactSource);
                assertReceiptBackedFixtures(baseline.fence, fixtureIds);

                initialReceipts = Map.of(1, jdbc.queryForObject("SELECT latest_generation FROM "
                        + TABLES.receipt() + " WHERE season=? AND publication_key=?", Long.class,
                        SEASON.code(), "fixture:1"));
                firstVerifiedAt = fixtureFirstVerifiedTimes(fixtureIds);
                advanceGlobalTimeline(clock, SAME_FINGERPRINT_CORRECTION);
                correctionReceipts = publishSameFingerprintCorrectionBatch(baseline, 1, 1);
                assertQualifyingBaselineReceipts(fixtureIds, correctionReceipts, initialReceipts, firstVerifiedAt);
                assertBaselineCandidateCoverage(fixtureIds, compactSource);
                assertReceiptBackedFixtures(baseline.fence, fixtureIds);
                publicationChecksum = baselinePublicationChecksum(fixtureIds);
                fingerprintChecksum = historyFingerprintChecksum(fixtureIds);
                FixtureOracle oracle = sourceFixtureOracles(compactSource, fixtureIds).get(1);
                assertStoredFixtureOracle(1, oracle);
                baselineEpoch = baseline.fence.epoch();
            }

            try (Harness resumed = Harness.active(1, wallDeadline, wallStartedAt, compactSource,
                    clock, false, true)) {
                resumed.dispatchCanonicalCycle();
                new PlannerInputRepository(jdbc, resumed.store, TABLES, CAPTURED, clock).load(SEASON);
                for (Map.Entry<Integer, Long> receipt : correctionReceipts.entrySet()) {
                    assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + TABLES.receipt()
                                    + " WHERE season=? AND publication_key=?", Long.class,
                            SEASON.code(), "fixture:" + receipt.getKey())).isEqualTo(receipt.getValue());
                    assertThat(jdbc.queryForObject("SELECT last_verified_generation FROM " + TABLES.fixtureSync()
                                    + " WHERE season_id=? AND fixture_id=?", Long.class,
                            SEASON.id(), receipt.getKey())).isEqualTo(receipt.getValue());
                    assertThat(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + TABLES.run()
                                    + " WHERE generation=? AND runtime_mode='ACTIVE' AND runtime_epoch=?"
                                    + " AND state='COMPLETED')", Boolean.class, receipt.getValue(), baselineEpoch))
                            .isTrue();
                }
                assertThat(baselinePublicationChecksum(fixtureIds)).isEqualTo(publicationChecksum);
                assertThat(historyFingerprintChecksum(fixtureIds)).isEqualTo(fingerprintChecksum);
                assertCanonicalPairProof(resumed, compactSource);
                PayloadReadProbe.Snapshot workerReadsBeforeOracle = resumed.payloadReads.snapshot();
                CanonicalParentProof diagnosticParent = currentCanonicalParentProof(resumed);
                assertThat(diagnosticParent.currentPublished()).isTrue();
                assertThat(diagnosticParent.immutable()).isTrue();
                assertThat(numberLong(diagnosticParent.trace().get("diagnosticPayloadQueries"))).isEqualTo(1);
                assertThat(numberLong(diagnosticParent.trace().get("diagnosticPayloadRawBytes"))).isPositive();
                assertThat(resumed.payloadReads.snapshot())
                        .as("non-probe oracle body reads are separate from worker measurements")
                        .isEqualTo(workerReadsBeforeOracle);
                assertStoredFixtureOracle(1, sourceFixtureOracles(compactSource, fixtureIds).get(1));
            }
        } finally {
            cleanActiveAcceptanceSeason();
        }
    }

    @Test
    void realPlannerAndSourcesKeepTheTwelveHourUnknownParentGateAndFirstPendingState() throws Exception {
        Instant due = LEGACY_DAILY_DUE;
        Instant firstFinished = LEGACY_GATE_OBSERVED_AT;
        assertThat(firstFinished.plus(Duration.ofHours(12))).isBeforeOrEqualTo(due);
        var fixtureRead = new CatalogueScheduleParser.ReadInterval(due.minusMillis(40), due.minusMillis(30));
        var liveRead = new CatalogueScheduleParser.ReadInterval(due.minusMillis(20), due.minusMillis(10));
        var historyRead = new CatalogueScheduleParser.ReadInterval(due.minusMillis(9), due.minusMillis(5));
        var bootstrap = new CatalogueScheduleParser.SourceDocument(source.bootstrapBytes(), fixtureRead);
        var fixtures = new CatalogueScheduleParser.SourceDocument(source.fixtureBytes(), fixtureRead);
        var snapshot = CatalogueScheduleParser.parse(SEASON, bootstrap, fixtures);
        var fixture = snapshot.fixtures().stream().filter(row -> row.id() == 41).findFirst().orElseThrow();
        var live = new CatalogueScheduleParser.SourceDocument(source.liveBytes(fixture.gameweek()), liveRead);
        Set<Integer> candidates = candidatePlayers(snapshot, fixture, live);
        List<HistoryParser.CanonicalFixture> canonical = snapshot.fixtures().stream()
                .map(HistoryParser::canonicalFixture).toList();
        List<HistoryParser.HistoryBatch> histories = candidates.stream().sorted()
                .map(playerId -> HistoryParser.parse(SEASON, playerId,
                        new CatalogueScheduleParser.SourceDocument(source.historyBytes(playerId), historyRead),
                        canonical))
                .toList();
        FixtureVerification.Decision first = FixtureVerification.verify(SEASON, fixture, fixtureRead,
                live, histories, null, due);
        assertThat(first.state()).isEqualTo(FixtureVerification.State.PENDING);
        assertThat(first.reasons()).containsExactly(FixtureVerification.Reason.NEEDS_SECOND_OBSERVATION);
        assertThat(first.observation().coherent()).isTrue();

        AdaptiveMatchdayPlanner.Fixture unknown = new AdaptiveMatchdayPlanner.Fixture(fixture.id(),
                fixture.gameweek(), fixture.kickoff(), true, true, true, firstFinished, candidates,
                SharedFixtureRecoveryPolicy.SourceStatus.UNKNOWN);
        AdaptiveMatchdayPlanner.Fixture finished = new AdaptiveMatchdayPlanner.Fixture(fixture.id(),
                fixture.gameweek(), fixture.kickoff(), true, true, true, firstFinished, candidates,
                SharedFixtureRecoveryPolicy.SourceStatus.FINISHED);
        AdaptiveMatchdayPlanner.FixtureProgress progress = new AdaptiveMatchdayPlanner.FixtureProgress(
                fixture.id(), true, firstFinished, null, 0, due, null, firstFinished, Set.of(),
                AdaptiveMatchdayPlanner.RecoveryMetadata.empty());
        AdaptiveMatchdayPlanner planner = new AdaptiveMatchdayPlanner(Clock.fixed(due, ZoneOffset.UTC));
        var blocked = planner.plan(new AdaptiveMatchdayPlanner.Input(SEASON.code(), 0,
                List.of(unknown), List.of(progress), List.of()));
        assertThat(blocked.dueSlots()).noneMatch(slot -> slot.kind()
                == AdaptiveMatchdayPlanner.WorkKind.FIXTURE_VERIFICATION);
        var eligible = planner.plan(new AdaptiveMatchdayPlanner.Input(SEASON.code(), 0,
                List.of(finished), List.of(progress), List.of()));
        assertThat(eligible.dueSlots()).filteredOn(slot -> slot.kind()
                == AdaptiveMatchdayPlanner.WorkKind.FIXTURE_VERIFICATION)
                .singleElement().satisfies(slot -> {
                    assertThat(slot.reason()).isEqualTo(AdaptiveMatchdayPlanner.SlotReason.DAILY_RECOVERY);
                    assertThat(slot.dueAt()).isEqualTo(due);
                    assertThat(slot.fixtureIds()).containsExactly(fixture.id());
                });
    }

    @Test
    void unrelatedDailyDeadlineDoesNotProveFixtureSpecificRecoveryWork() {
        Instant now = ACTIVE_PRICE_CAPTURE;
        int fixtureId = 99;
        AdaptiveMatchdayPlanner.Fixture fixture = new AdaptiveMatchdayPlanner.Fixture(fixtureId, 1,
                now.minus(Duration.ofHours(1)), true, true, true, now.minus(Duration.ofHours(14)),
                Set.of(1), SharedFixtureRecoveryPolicy.SourceStatus.UNKNOWN);
        AdaptiveMatchdayPlanner.FixtureProgress progress = new AdaptiveMatchdayPlanner.FixtureProgress(
                fixtureId, true, now.minus(Duration.ofHours(14)), now.minus(Duration.ofHours(13)), 1,
                now.minus(Duration.ofHours(1)), null, null, Set.of(),
                AdaptiveMatchdayPlanner.RecoveryMetadata.empty());
        AdaptiveMatchdayPlanner.Plan plan = new AdaptiveMatchdayPlanner(Clock.fixed(now, ZoneOffset.UTC)).plan(
                new AdaptiveMatchdayPlanner.Input(SEASON.code(), 0, List.of(fixture), List.of(progress), List.of()));
        SharedFixtureRecoveryPolicy.Decision recovery = fixtureOnlyRecoveryDecision(fixture, progress, now);
        Instant fixtureOnlyDue = fixtureOnlyFutureDue(SEASON.code(), 0, Clock.fixed(now, ZoneOffset.UTC),
                fixture, progress, recovery);
        AdaptiveMatchdayPlanner.WorkSlot dailySlot = new AdaptiveMatchdayPlanner.WorkSlot(
                SEASON.code() + ":daily_schedule:unrelated", AdaptiveMatchdayPlanner.WorkKind.DAILY_SCHEDULE,
                ACTIVE_SCHEDULE_CAPTURE, Set.of(), Set.of(), AdaptiveMatchdayPlanner.SlotReason.ROUTINE, 2);

        assertThat(plan.dueSlots()).anyMatch(slot -> slot.kind()
                == AdaptiveMatchdayPlanner.WorkKind.DAILY_CATALOGUE);
        assertThat(plan.nextDueAt()).contains(ACTIVE_SCHEDULE_CAPTURE);
        assertThat(plan.dueSlots()).noneMatch(slot -> slot.fixtureIds().contains(fixtureId));
        assertThat(recovery.action()).isEqualTo(SharedFixtureRecoveryPolicy.Action.WAIT_FOR_PARENT);
        assertThat(fixtureOnlyDue).isNull();
        assertThat(hasFixtureSpecificFutureWork(fixtureId, false,
                List.of(new PlannerRuntimeRepository.DurableSlot(dailySlot, now)), fixtureOnlyDue,
                plan.nextDueAt().orElse(null), now))
                .as("an unrelated daily wake and a pending observation cannot stand in for this fixture's work")
                .isFalse();
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void scheduleCorrectionCanRepublishOnlyAfterTwoFreshCoordinatorBatches() throws Exception {
        SourceData scheduleSource = SourceData.load();
        prepareActiveAcceptanceSeason();
        MutableClock clock = new MutableClock(BASELINE_SOURCE_START);
        long wallStartedAt = System.nanoTime();
        long wallDeadline = wallStartedAt + TimeUnit.MINUTES.toNanos(4);
        try {
            try (Harness baseline = Harness.active(1, wallDeadline, wallStartedAt, scheduleSource,
                    clock, true, false)) {
                baseline.dispatchCanonicalCycle();
                new PlannerInputRepository(jdbc, baseline.store, TABLES, CAPTURED, clock).load(SEASON);
                reconcileHistoryFingerprints(baseline, Set.of(3));
                Map<Integer, FixtureVerification.Decision> first = publishDirectFixtureBatch(baseline, 3, 3);
                assertThat(first.get(3).state()).isEqualTo(FixtureVerification.State.PENDING);
                assertThat(first.get(3).reasons())
                        .containsExactly(FixtureVerification.Reason.NEEDS_SECOND_OBSERVATION);
                advanceGlobalTimeline(clock, first.get(3).observation().reads().completedAt()
                        .plus(Duration.ofMinutes(11)));
                Map<Integer, FixtureVerification.Decision> verified = publishDirectFixtureBatch(baseline, 3, 3);
                assertThat(verified.get(3).state()).isEqualTo(FixtureVerification.State.VERIFIED);

                FixtureOwnershipSnapshot before = fixtureOwnershipSnapshot(3);
                Map<Integer, FixturePublicationComponents> beforeComponents =
                        fixturePublicationComponents(Set.of(3));
                String oldHeaderFingerprint = before.verifiedHeader();
                String oldCanonicalFingerprint = canonicalScheduleFrame().get("source_pair_fingerprint").toString();
                assertThat(before.currentVerifiedGeneration()).isNotNull();
                assertThat(before.lastVerifiedGeneration()).isEqualTo(before.currentVerifiedGeneration());
                scheduleSource.correctFixtureScores(Set.of(3));
                baseline.catalogue.enqueueSchedule(SEASON, clock.instant());
                assertThat(baseline.dispatchOneWithControlledResponse("fixture-3-schedule-correction")).isEqualTo(1);
                new PlannerInputRepository(jdbc, baseline.store, TABLES, CAPTURED, clock).load(SEASON);

                FixtureOwnershipSnapshot after = fixtureOwnershipSnapshot(3);
                Map<Integer, FixturePublicationComponents> afterComponents =
                        fixturePublicationComponents(Set.of(3));
                Map<String, Object> correctedRootFrame = canonicalScheduleFrame();
                Map<String, Object> correctedFixtureRoot = canonicalRootFixtureHeader(baseline, 3);
                String correctedCanonicalFingerprint = correctedRootFrame
                        .get("source_pair_fingerprint").toString();
                FixtureOracle correctedOracle = sourceFixtureOracles(scheduleSource, Set.of(3)).get(3);
                System.out.printf("OWNED_SCHEDULE_INVALIDATION fixture=3 before=%s after=%s components=%s "
                                + "rootFingerprint=%s->%s correctedOracle=%s%n",
                        before.flags(), after.flags(), fixturePublicationComponentDiffs(beforeComponents, afterComponents),
                        oldCanonicalFingerprint, correctedCanonicalFingerprint, correctedOracle);
                assertThat(correctedCanonicalFingerprint).isNotEqualTo(oldCanonicalFingerprint);
                assertThat(correctedOracle.headerFingerprint()).isNotEqualTo(oldHeaderFingerprint);
                assertThat(correctedOracle.statsFingerprint()).isEqualTo(before.verifiedStatsFingerprint());
                assertScheduleInvalidationRetainsActualOwnership(3, before, after);
                assertScheduleCorrectionComponentTransition(beforeComponents, afterComponents, Set.of(3));
                assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats"
                        + " WHERE season_id=? AND fixture_id=3", Integer.class, SEASON.id())).isPositive();
                assertThat(pfsRows("OFF", 3)).as("fixture 3 physical PFS is masked after revocation").isEmpty();
                assertThat(pfsRows("ACTIVE", 3)).as("fixture 3 is unreadable until reverified").isEmpty();

                List<Long> correctionRuns = afterComponents.get(3).verificationRunGenerations();
                baseline.catalogue.enqueueSchedule(SEASON, clock.instant());
                assertThat(baseline.dispatchOneWithControlledResponse("fixture-3-unrelated-schedule-refresh"))
                        .isEqualTo(1);
                new PlannerInputRepository(jdbc, baseline.store, TABLES, CAPTURED, clock).load(SEASON);
                Map<String, Object> unrelatedRootFrame = canonicalScheduleFrame();
                String unrelatedCanonicalFingerprint = unrelatedRootFrame
                        .get("source_pair_fingerprint").toString();
                Map<Integer, FixturePublicationComponents> unrelatedComponents =
                        fixturePublicationComponents(Set.of(3));
                assertThat(unrelatedCanonicalFingerprint).isNotEqualTo(correctedCanonicalFingerprint);
                assertThat(unrelatedRootFrame.get("source_bootstrap_sha256"))
                        .as("unrelated capture did not change bootstrap content").isEqualTo(correctedRootFrame.get("source_bootstrap_sha256"));
                assertThat(unrelatedRootFrame.get("source_fixtures_sha256"))
                        .as("unrelated capture did not change fixture-source content").isEqualTo(correctedRootFrame.get("source_fixtures_sha256"));
                assertThat(canonicalRootFixtureHeader(baseline, 3))
                        .as("unrelated publication preserves the authoritative fixture source")
                        .isEqualTo(correctedFixtureRoot);
                assertThat(unrelatedComponents.get(3).sourceHeader()).isEqualTo(afterComponents.get(3).sourceHeader());
                assertThat(unrelatedComponents.get(3).capturedHeader())
                        .isEqualTo(afterComponents.get(3).capturedHeader());
                assertThat(unrelatedComponents.get(3).latestGeneration()).isEqualTo(before.lastVerifiedGeneration());
                assertThat(unrelatedComponents.get(3).ownership().currentVerifiedGeneration()).isNull();
                assertThat(unrelatedComponents.get(3).verificationRunGenerations()).isEqualTo(correctionRuns);
                assertThat(fixtureOwnershipSnapshot(3).lastVerifiedGeneration())
                        .isEqualTo(before.lastVerifiedGeneration());
                System.out.printf("OWNED_UNRELATED_SCHEDULE_PUBLICATION fixture=3 rootFingerprint=%s->%s "
                                + "fixtureHeader=%s runs=%s currentReceipt=%s%n",
                        correctedCanonicalFingerprint, unrelatedCanonicalFingerprint,
                        unrelatedComponents.get(3).sourceHeader(), correctionRuns,
                        unrelatedComponents.get(3).latestGeneration());

                long firstFreshGeneration = dispatchFixtureUntilFreshObservation(baseline, 3,
                        before.observationGeneration());
                FixtureOwnershipSnapshot freshPending = fixtureOwnershipSnapshot(3);
                Map<String, Object> firstFreshRun = fixtureRunSourceFrame(firstFreshGeneration);
                String details = "fixture 3 first post-correction observation=" + freshPending.flags();
                assertThat(freshPending.state()).as(details).isEqualTo("VERIFYING");
                assertThat(freshPending.currentVerifiedGeneration()).as(details).isNull();
                assertThat(freshPending.lastVerifiedGeneration()).as(details)
                        .isEqualTo(before.lastVerifiedGeneration());
                assertThat(freshPending.verifiedHeader()).as(details + " retains last-good header proof")
                        .isEqualTo(before.verifiedHeader());
                assertThat(freshPending.verifiedStatsFingerprint()).as(details + " retains last-good stats proof")
                        .isEqualTo(before.verifiedStatsFingerprint());
                FixturePublicationComponents pendingComponents = fixturePublicationComponents(Set.of(3)).get(3);
                assertThat(pendingComponents.receiptIdentity()).as(details + " retains receipt identity")
                        .isEqualTo(beforeComponents.get(3).receiptIdentity());
                assertThat(pendingComponents.latestGeneration()).as(details + " retains receipt generation")
                        .isEqualTo(before.lastVerifiedGeneration());
                assertThat(pendingComponents.pfsRows()).as(details + " retains physical last-good PFS")
                        .isEqualTo(beforeComponents.get(3).pfsRows());
                assertThat(pendingComponents.evidenceRows()).as(details + " retains last-good evidence")
                        .isEqualTo(beforeComponents.get(3).evidenceRows());
                assertThat(pendingComponents.verificationValid()).as(details + " old receipt is not current-valid")
                        .isFalse();
                assertThat(freshPending.observationGeneration()).as(details).isEqualTo(firstFreshGeneration);
                assertThat(freshPending.observationCoherent()).as(details).isEqualTo("true");
                assertThat(freshPending.validationFailures()).contains("NEEDS_SECOND_OBSERVATION");
                assertThat(pfsRows("OFF", 3)).as("fresh pending data remains masked").isEmpty();
                assertThat(firstFreshRun.get("state")).isEqualTo("COMPLETED");
                assertThat(firstFreshRun.get("runtime_epoch")).isEqualTo(baseline.fence.epoch());
                assertThat(firstFreshRun.get("source_read_started_at")).isNotNull();
                assertThat(firstFreshRun.get("source_read_finished_at")).isNotNull();
                assertThat(firstFreshRun.get("fixture_score")).isEqualTo(1);
                assertPendingObservationMatchesSource(baseline, 3, firstFreshGeneration,
                        correctedOracle.requiredPlayers(), scheduleSource);

                FixtureVerification.Observation firstObservation = fixtureObservation(3);
                SourceRead firstBatch = observationInterval(firstObservation);
                Timestamp persistedDue = jdbc.queryForObject("SELECT next_due_at FROM " + TABLES.fixtureSync()
                        + " WHERE season_id=? AND fixture_id=?", Timestamp.class, SEASON.id(), 3);
                assertThat(persistedDue).isNotNull();
                Instant secondStart = firstBatch.finishedAt().plus(Duration.ofMinutes(11));
                if (persistedDue.toInstant().isAfter(secondStart)) secondStart = persistedDue.toInstant();
                baseline.advanceControlledTime(secondStart);
                var secondRun = baseline.histories.enqueuePersistedFixtureRetry(SEASON, 3, secondStart);
                assertThat(secondRun.generation()).isGreaterThan(firstFreshGeneration);
                long correctedGeneration = dispatchFixtureUntilVerified(baseline, 3,
                        before.lastVerifiedGeneration());
                FixtureOwnershipSnapshot corrected = fixtureOwnershipSnapshot(3);
                Map<Integer, FixturePublicationComponents> correctedComponents =
                        fixturePublicationComponents(Set.of(3));
                assertThat(corrected.state()).isEqualTo("VERIFIED");
                assertThat(corrected.currentVerifiedGeneration()).isEqualTo(correctedGeneration);
                assertThat(corrected.lastVerifiedGeneration()).isEqualTo(correctedGeneration);
                assertThat(correctedGeneration).isGreaterThan(before.lastVerifiedGeneration());
                assertThat(fixtureReadEvidenceProof(baseline, 3, correctedGeneration,
                        correctedOracle.requiredPlayers())).isTrue();
                Map<String, Object> completedCorrectionRun = fixtureRunSourceFrame(correctedGeneration);
                SourceRead secondBatch = observationInterval(fixtureObservation(3));
                assertThat(secondBatch.startedAt()).isAfterOrEqualTo(firstBatch.finishedAt()
                        .plus(Duration.ofMinutes(10)));
                assertThat(completedCorrectionRun.get("fixture_score")).isEqualTo(1);
                assertStoredFixtureOracle(3, correctedOracle);
                assertPhysicalPfsMatchesSource(3, scheduleSource, correctedGeneration);
                assertPfsMetricRowsUnchanged(beforeComponents.get(3).pfsRows(),
                        correctedComponents.get(3).pfsRows());
                assertThat(correctedComponents.get(3).sourceHeader().get("team_h_score")).isEqualTo(1);
                assertThat(changedHeaderFields(beforeComponents.get(3).sourceHeader(),
                        correctedComponents.get(3).sourceHeader())).containsExactly("team_h_score");
                assertThat(correctedComponents.get(3).capturedHeader().get("team_h_score")).isEqualTo(1);
                assertThat(changedHeaderFields(beforeComponents.get(3).capturedHeader(),
                        correctedComponents.get(3).capturedHeader())).containsExactly("team_h_score");
                assertThat(correctedComponents.get(3).evidenceRows()).hasSizeGreaterThan(
                        beforeComponents.get(3).evidenceRows().size());
                assertThat(pfsRows("ACTIVE", 3)).isNotEmpty();
                System.out.printf("OWNED_SCHEDULE_CORRECTION_PUBLISHED fixture=3 oldGeneration=%d "
                                + "firstFresh=%d corrected=%d batches=%s/%s root=%s oracle=%s componentDiff=%s%n",
                        before.lastVerifiedGeneration(), firstFreshGeneration, correctedGeneration,
                        firstBatch, secondBatch, canonicalScheduleFrame(), correctedOracle,
                        fixturePublicationComponentDiffs(beforeComponents, correctedComponents));
            }
        } finally {
            cleanActiveAcceptanceSeason();
        }
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void scoreCorrectionSourceDiffIsExactlyFixturesOneThroughFive() throws Exception {
        SourceData source = SourceData.loadActiveAcceptanceRoster();
        prepareActiveAcceptanceSeason();
        MutableClock clock = new MutableClock(BASELINE_SOURCE_START);
        long wallStartedAt = System.nanoTime();
        long wallDeadline = wallStartedAt + TimeUnit.MINUTES.toNanos(4);
        Set<Integer> fixtureIds = java.util.stream.IntStream.rangeClosed(1, 40).boxed()
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<Integer> changedIds = Set.of(1, 2, 3, 4, 5);
        try {
            try (Harness harness = Harness.active(1, wallDeadline, wallStartedAt, source, clock, true, false)) {
                harness.dispatchCanonicalCycle();
                new PlannerInputRepository(jdbc, harness.store, TABLES, CAPTURED, clock).load(SEASON);
                Map<Integer, FixtureHeaderState> oldRows = currentFixtureHeaderStates(fixtureIds);
                Map<Integer, FixtureSourceState> oldRoot = currentCanonicalFixtureStates(harness, 1);
                Map<String, Object> oldRootFrame = canonicalScheduleFrame();

                source.correctFixtureScores(changedIds);
                harness.catalogue.enqueueSchedule(SEASON, clock.instant());
                assertThat(harness.dispatchOneWithControlledResponse("five-fixture-source-diff")).isEqualTo(1);
                new PlannerInputRepository(jdbc, harness.store, TABLES, CAPTURED, clock).load(SEASON);
                Map<Integer, FixtureHeaderState> newRows = currentFixtureHeaderStates(fixtureIds);
                Map<Integer, FixtureSourceState> newRoot = currentCanonicalFixtureStates(harness, 1);
                Map<String, Object> newRootFrame = canonicalScheduleFrame();

                Set<Integer> changedCore = new java.util.TreeSet<>();
                Set<Integer> changedCapture = new java.util.TreeSet<>();
                Set<Integer> changedRoot = new java.util.TreeSet<>();
                for (int fixtureId : fixtureIds) {
                    FixtureHeaderState before = oldRows.get(fixtureId);
                    FixtureHeaderState after = newRows.get(fixtureId);
                    FixtureSourceState oldSource = oldRoot.get(fixtureId);
                    FixtureSourceState newSource = newRoot.get(fixtureId);
                    Set<String> coreFields = changedHeaderFields(before.core(), after.core());
                    Set<String> captureFields = changedHeaderFields(before.captured(), after.captured());
                    if (!coreFields.isEmpty()) changedCore.add(fixtureId);
                    if (!captureFields.isEmpty()) changedCapture.add(fixtureId);
                    if (!oldSource.headerFingerprint().equals(newSource.headerFingerprint())) changedRoot.add(fixtureId);
                    if (changedIds.contains(fixtureId)) {
                        assertThat(coreFields).as("fixture %d corrected core header", fixtureId)
                                .containsExactly("team_h_score");
                        assertThat(captureFields).as("fixture %d corrected capture header", fixtureId)
                                .containsExactly("captured_home_score");
                        assertThat(before.core().get("team_h_score")).isEqualTo(0);
                        assertThat(after.core().get("team_h_score")).isEqualTo(1);
                        assertThat(before.captured().get("captured_home_score")).isEqualTo(0);
                        assertThat(after.captured().get("captured_home_score")).isEqualTo(1);
                        assertThat(oldSource.homeScore()).isEqualTo(0);
                        assertThat(newSource.homeScore()).isEqualTo(1);
                        assertThat(oldSource.headerFingerprint()).isNotEqualTo(newSource.headerFingerprint());
                    } else {
                        assertThat(coreFields).as("fixture %d unrelated core header", fixtureId).isEmpty();
                        assertThat(captureFields).as("fixture %d unrelated captured header", fixtureId).isEmpty();
                        assertThat(oldSource).as("fixture %d authoritative source remains unchanged", fixtureId)
                                .isEqualTo(newSource);
                    }
                    assertThat(oldSource.statsFingerprint()).as("fixture %d metric fingerprint", fixtureId)
                            .isEqualTo(newSource.statsFingerprint());
                }
                assertThat(changedCore).containsExactlyInAnyOrderElementsOf(changedIds);
                assertThat(changedCapture).containsExactlyInAnyOrderElementsOf(changedIds);
                assertThat(changedRoot).containsExactlyInAnyOrderElementsOf(changedIds);
                assertThat(newRootFrame.get("source_pair_fingerprint"))
                        .isNotEqualTo(oldRootFrame.get("source_pair_fingerprint"));
                assertThat(newRootFrame.get("source_bootstrap_sha256"))
                        .isEqualTo(oldRootFrame.get("source_bootstrap_sha256"));
                assertThat(newRootFrame.get("source_fixtures_sha256"))
                        .isNotEqualTo(oldRootFrame.get("source_fixtures_sha256"));
                System.out.printf("OWNED_FIVE_FIXTURE_SOURCE_DIFF changed=%s core=%s capture=%s root=%s "
                                + "scheduleHashes=%s->%s fixtureHeaderDiffs=%s%n",
                        changedIds, changedCore, changedCapture, changedRoot,
                        List.of(oldRootFrame.get("source_bootstrap_sha256"), oldRootFrame.get("source_fixtures_sha256")),
                        List.of(newRootFrame.get("source_bootstrap_sha256"), newRootFrame.get("source_fixtures_sha256")),
                        changedIds.stream().sorted().collect(java.util.stream.Collectors.toMap(
                                id -> id, id -> changedHeaderFields(oldRows.get(id).core(), newRows.get(id).core()),
                                (left, right) -> left, TreeMap::new)));
            }
        } finally {
            cleanActiveAcceptanceSeason();
        }
    }


    private static void prepareActiveAcceptanceSeason() {
        TapasSeed.ensureOwnedSyncCaptureSeason(jdbc);
        createActiveCapturedTables();
        new dev.quantti.fplassistant.schedule.ScheduleSchema(jdbc,
                CAPTURED.gameweek(), CAPTURED.fixture()).ensureSchema();
        new DefensiveStatsSchema(jdbc).ensureColumns();
        cleanActiveAcceptanceSeason();
        TapasSeed.setCurrentSeason(jdbc, SEASON.id());
    }

    private static void createActiveCapturedTables() {
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

    private static void cleanActiveAcceptanceSeason() {
        TapasSeed.ensureOwnedSyncCaptureSeason(jdbc);
        clearOwnedRows();
        for (String table : List.of(CAPTURED.fixture(), CAPTURED.gameweek(), CAPTURED.dailyPrice())) {
            jdbc.update("DELETE FROM " + table + " WHERE season=?", SEASON.code());
        }
        jdbc.update("DELETE FROM player_fixture_stats WHERE season_id=?", SEASON.id());
        jdbc.update("DELETE FROM fixture WHERE season_id=?", SEASON.id());
        jdbc.update("DELETE FROM gameweek WHERE season_id=?", SEASON.id());
        jdbc.update("DELETE FROM player WHERE season_id=?", SEASON.id());
        jdbc.update("DELETE FROM team WHERE season_id=?", SEASON.id());
        TapasSeed.setCurrentSeason(jdbc, TapasSeed.CURRENT_SEASON_ID);
    }

    private static Map<Integer, FixtureVerification.Decision> publishDirectFixtureBatch(Harness harness,
                                                                                         int fromInclusive,
                                                                                         int toInclusive)
            throws Exception {
        Map<Integer, Long> generations = new TreeMap<>();
        for (int fixtureId = fromInclusive; fixtureId <= toInclusive; fixtureId++) {
            generations.put(fixtureId, harness.histories.enqueueFixtureVerification(
                    SEASON, fixtureId, harness.clock.instant()).generation());
        }
        refreshActiveCanonical(harness, "active-baseline-root");
        Map<Integer, FixtureVerification.Decision> decisions = new TreeMap<>();
        for (Map.Entry<Integer, Long> entry : generations.entrySet()) {
            decisions.put(entry.getKey(), publishDirectFixtureObservation(
                    harness, entry.getKey(), entry.getValue()));
        }
        return Map.copyOf(decisions);
    }

    private static FixtureVerification.Decision publishDirectFixtureObservation(Harness harness,
                                                                                 int fixtureId,
                                                                                 long generation)
            throws IOException {
        Map<String, Object> queued = jdbc.queryForMap("SELECT requested_source_after,state,publication_key FROM "
                + TABLES.run() + " WHERE generation=?", generation);
        assertThat(queued.get("state")).isEqualTo("PENDING");
        assertThat(queued.get("publication_key")).isEqualTo("fixture:" + fixtureId);
        Instant sourceFloor = ((Timestamp) queued.get("requested_source_after")).toInstant();
        Lease lease = harness.store.acquireForSeason(SEASON.code(), "active-baseline-" + fixtureId,
                Duration.ofMinutes(5)).orElseThrow();
        assertThat(lease.generation()).isEqualTo(generation);

        RootDocuments root = currentCanonicalDocuments(harness, fixtureId);
        assertThat(root.bootstrap().readInterval().startedAt()).isAfterOrEqualTo(sourceFloor);
        assertThat(root.fixtures().readInterval().startedAt()).isAfterOrEqualTo(sourceFloor);
        var snapshot = CatalogueScheduleParser.parse(SEASON, root.bootstrap(), root.fixtures());
        var fixture = snapshot.fixtures().stream().filter(row -> row.id() == fixtureId).findFirst().orElseThrow();
        Instant sourceBase = later(harness.clock.instant(),
                root.fixtures().readInterval().completedAt().plusMillis(1));
        Instant liveFinished = sourceBase.plusMillis(20);
        var live = new CatalogueScheduleParser.SourceDocument(harness.source.liveBytes(fixture.gameweek()),
                new CatalogueScheduleParser.ReadInterval(sourceBase, liveFinished));
        Set<Integer> candidatePlayers = candidatePlayers(snapshot, fixture, live);
        assertThat(candidatePlayers).hasSizeBetween(1, 86);
        Instant historyStarted = liveFinished.plusMillis(1);
        Instant historyFinished = historyStarted.plusMillis(20);
        var historyInterval = new CatalogueScheduleParser.ReadInterval(historyStarted, historyFinished);
        Map<Integer, CatalogueScheduleParser.SourceDocument> histories = new TreeMap<>();
        candidatePlayers.forEach(playerId -> histories.put(playerId,
                new CatalogueScheduleParser.SourceDocument(harness.source.historyBytes(playerId), historyInterval)));
        assertThat(histories).hasSize(candidatePlayers.size());
        int knownZeroMinuteRows = 0;
        List<HistoryParser.CanonicalFixture> canonical = snapshot.fixtures().stream()
                .map(HistoryParser::canonicalFixture).toList();
        for (int playerId : candidatePlayers) {
            var batch = HistoryParser.parse(SEASON, playerId, histories.get(playerId), canonical);
            List<HistoryParser.HistoryRow> fixtureRows = batch.rows().stream()
                    .filter(row -> row.fixtureId() == fixtureId).toList();
            assertThat(fixtureRows)
                    .as("candidate %d has a real fixture row, including known zero minutes", playerId)
                    .hasSize(1);
            if (fixtureRows.getFirst().minutes() == 0) knownZeroMinuteRows++;
        }
        assertThat(knownZeroMinuteRows).as("known zero-minute candidates stay in the baseline denominator")
                .isPositive();
        harness.advanceControlledTime(historyFinished);
        return harness.publisher.publish(lease, SEASON, fixtureId, root.bootstrap(), root.fixtures(), live, histories);
    }

    private static Map<Integer, Long> publishSameFingerprintCorrectionBatch(Harness harness,
                                                                             int fromInclusive, int toInclusive)
            throws Exception {
        Map<Integer, FixtureVerification.Decision> decisions =
                publishDirectFixtureBatch(harness, fromInclusive, toInclusive);
        assertThat(decisions.values()).allSatisfy(decision ->
                assertThat(decision.state()).isEqualTo(FixtureVerification.State.VERIFIED));
        Map<Integer, Long> receipts = new TreeMap<>();
        for (int fixtureId = fromInclusive; fixtureId <= toInclusive; fixtureId++) {
            receipts.put(fixtureId, jdbc.queryForObject("SELECT latest_generation FROM " + TABLES.receipt()
                    + " WHERE season=? AND publication_key=?", Long.class,
                    SEASON.code(), "fixture:" + fixtureId));
        }
        return Map.copyOf(receipts);
    }

    private static void reconcileHistoryFingerprints(Harness harness, Set<Integer> fixtureIds) throws IOException {
        RootDocuments root = currentCanonicalDocuments(harness, fixtureIds.iterator().next());
        var snapshot = CatalogueScheduleParser.parse(SEASON, root.bootstrap(), root.fixtures());
        Set<Integer> candidates = fixtureIds.stream().flatMap(id -> {
            var fixture = snapshot.fixtures().stream().filter(row -> row.id() == id).findFirst().orElseThrow();
            Set<Integer> players = snapshot.players().stream()
                    .filter(player -> player.teamId() == fixture.homeTeamId()
                            || player.teamId() == fixture.awayTeamId())
                    .map(CatalogueScheduleParser.PlayerRow::id)
                    .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
            var live = new CatalogueScheduleParser.SourceDocument(harness.source.liveBytes(fixture.gameweek()),
                    root.fixtures().readInterval());
            players.addAll(FixtureVerification.participantIds(fixture, live));
            return players.stream();
        }).collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        List<HistoryParser.CanonicalFixture> canonical = snapshot.fixtures().stream()
                .map(HistoryParser::canonicalFixture).toList();
        for (int playerId : candidates) {
            Instant started = harness.clock.instant();
            Instant finished = started.plusMillis(20);
            var document = new CatalogueScheduleParser.SourceDocument(harness.source.historyBytes(playerId),
                    new CatalogueScheduleParser.ReadInterval(started, finished));
            HistoryParser.HistoryBatch batch = HistoryParser.parse(SEASON, playerId, document, canonical);
            harness.advanceControlledTime(finished);
            harness.publisher.reconcileHistoryFingerprints(batch);
            harness.advanceControlledTime(finished.plusMillis(1));
        }
        for (int fixtureId : fixtureIds) {
            var fixture = snapshot.fixtures().stream().filter(row -> row.id() == fixtureId).findFirst().orElseThrow();
            var live = new CatalogueScheduleParser.SourceDocument(harness.source.liveBytes(fixture.gameweek()),
                    root.fixtures().readInterval());
            List<Integer> expected = candidatePlayers(snapshot, fixture, live).stream().sorted().toList();
            List<Integer> fingerprintPlayers = jdbc.queryForList("SELECT player_id FROM "
                    + TABLES.historyFingerprint() + " WHERE season_id=? AND fixture_id=? ORDER BY player_id",
                    Integer.class, SEASON.id(), fixtureId);
            assertThat(fingerprintPlayers)
                    .as("every candidate, including known-zero players, has a source fingerprint for fixture %d",
                            fixtureId)
                    .containsExactlyElementsOf(expected);
        }
    }

    private static Set<Integer> candidatePlayers(CatalogueScheduleParser.Snapshot snapshot,
                                                  CatalogueScheduleParser.FixtureRow fixture,
                                                  CatalogueScheduleParser.SourceDocument live) {
        Set<Integer> players = snapshot.players().stream()
                .filter(player -> player.teamId() == fixture.homeTeamId()
                        || player.teamId() == fixture.awayTeamId())
                .map(CatalogueScheduleParser.PlayerRow::id)
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        players.addAll(FixtureVerification.participantIds(fixture, live));
        return Set.copyOf(players);
    }

    private static void refreshActiveCanonical(Harness harness, String workerPrefix) throws Exception {
        harness.catalogue.enqueueCycle(SEASON, harness.clock.instant());
        assertThat(harness.dispatchOneWithControlledResponse(workerPrefix + "-price")).isEqualTo(1);
        assertThat(harness.dispatchOneWithControlledResponse(workerPrefix + "-schedule")).isEqualTo(1);
    }

    private static RootDocuments currentCanonicalDocuments(Harness harness, int fixtureId) {
        assertThat(harness.store.currentCanonicalParentEvidence(SEASON.code(), fixtureId,
                harness.clock.instant()).currentPublished()).isTrue();
        Map<String, Object> root = jdbc.queryForMap("SELECT b.body bootstrap_body,b.read_started_at bootstrap_started,"
                + "b.read_finished_at bootstrap_finished,p.source_bootstrap_sha256 bootstrap_hash,"
                + "f.body fixtures_body,f.read_started_at fixtures_started,f.read_finished_at fixtures_finished,"
                + "p.source_fixtures_sha256 fixtures_hash,p.source_bootstrap_sha256,p.source_fixtures_sha256,"
                + "p.published_at"
                + " FROM " + TABLES.receipt() + " p JOIN " + TABLES.run() + " r"
                + " ON r.generation=p.latest_generation JOIN " + TABLES.payload() + " b"
                + " ON b.id=p.source_bootstrap_payload_id JOIN " + TABLES.payload() + " f"
                + " ON f.id=p.source_fixtures_payload_id WHERE p.season=? AND p.publication_key=?"
                + " AND r.kind='CATALOGUE_SCHEDULE' AND r.state='COMPLETED' AND r.runtime_mode='ACTIVE'"
                + " AND r.runtime_epoch=? AND b.validation_state='VALID' AND f.validation_state='VALID'"
                + " AND b.source_published AND f.source_published ORDER BY p.published_at DESC LIMIT 1",
                SEASON.code(), CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY, harness.fence.epoch());
        byte[] bootstrapBytes = (byte[]) root.get("bootstrap_body");
        byte[] fixtureBytes = (byte[]) root.get("fixtures_body");
        assertThat(root.get("bootstrap_hash")).isEqualTo(OwnedSyncStore.sha256(bootstrapBytes));
        assertThat(root.get("fixtures_hash")).isEqualTo(OwnedSyncStore.sha256(fixtureBytes));
        assertThat(root.get("source_bootstrap_sha256")).isEqualTo(root.get("bootstrap_hash"));
        assertThat(root.get("source_fixtures_sha256")).isEqualTo(root.get("fixtures_hash"));
        assertThat(((Timestamp) root.get("published_at")).toInstant()).isBeforeOrEqualTo(harness.clock.instant());
        return new RootDocuments(
                new CatalogueScheduleParser.SourceDocument(bootstrapBytes,
                        new CatalogueScheduleParser.ReadInterval(
                                ((Timestamp) root.get("bootstrap_started")).toInstant(),
                                ((Timestamp) root.get("bootstrap_finished")).toInstant())),
                new CatalogueScheduleParser.SourceDocument(fixtureBytes,
                        new CatalogueScheduleParser.ReadInterval(
                                ((Timestamp) root.get("fixtures_started")).toInstant(),
                                ((Timestamp) root.get("fixtures_finished")).toInstant())));
    }

    private static Map<String, Object> canonicalScheduleFrame() {
        return jdbc.queryForMap("SELECT p.latest_generation,p.source_pair_fingerprint,"
                + "p.source_bootstrap_payload_id,p.source_fixtures_payload_id,p.source_read_started_at,"
                + "p.source_read_finished_at,p.source_bootstrap_read_started_at,p.source_bootstrap_read_finished_at,"
                + "p.source_fixtures_read_started_at,p.source_fixtures_read_finished_at,p.source_bootstrap_sha256,"
                + "p.source_fixtures_sha256,p.published_at,r.runtime_mode,r.runtime_epoch "
                + "FROM " + TABLES.receipt() + " p JOIN " + TABLES.run()
                + " r ON r.generation=p.latest_generation AND r.season=p.season WHERE p.season=? "
                + "AND p.publication_key=?", SEASON.code(), CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY);
    }

    private static void assertCurrentCanonicalSourceMatches(Harness harness, SourceData source) {
        RootDocuments current = currentCanonicalDocuments(harness, 3);
        assertThat(current.bootstrap().bytes()).containsExactly(source.bootstrapBytes());
        assertThat(current.fixtures().bytes()).containsExactly(source.fixtureBytes());
        Map<String, Object> root = canonicalRootFixtureHeader(harness, 3);
        assertThat(root.get("home_score")).isEqualTo(1);
    }

    private static Map<String, Object> canonicalRootFixtureHeader(Harness harness, int fixtureId) {
        RootDocuments documents = currentCanonicalDocuments(harness, fixtureId);
        var snapshot = CatalogueScheduleParser.parse(SEASON, documents.bootstrap(), documents.fixtures());
        var fixture = snapshot.fixtures().stream().filter(row -> row.id() == fixtureId).findFirst().orElseThrow();
        return Map.of("fixture_id", fixture.id(), "home_score", fixture.homeScore(),
                "away_score", fixture.awayScore(), "header_fingerprint", FixtureVerification.verifiedHeader(fixture),
                "stats_fingerprint", FixtureStatsFingerprint.of(fixture));
    }

    private static long dispatchFixtureUntilFreshObservation(Harness harness, int fixtureId,
                                                              Long previousObservation) throws Exception {
        for (int dispatch = 0; dispatch < 500; dispatch++) {
            FixtureOwnershipSnapshot current = fixtureOwnershipSnapshot(fixtureId);
            if (current.observationGeneration() != null
                    && (previousObservation == null || current.observationGeneration() > previousObservation)
                    && "true".equals(current.observationCoherent())
                    && current.validationFailures().contains("NEEDS_SECOND_OBSERVATION")) {
                return current.observationGeneration();
            }
            int claimed = harness.dispatchOneWithControlledResponse("fixture-" + fixtureId + "-fresh-first-" + dispatch);
            if (claimed != 0) continue;
            Instant now = harness.clock.instant();
            Instant next = harness.earliestActiveRunDue();
            if (next == null) {
                throw new AssertionError("coordinator drained before fixture " + fixtureId
                        + " recorded a fresh first observation: " + current.flags());
            }
            if (!next.isAfter(now)) {
                throw new AssertionError("coordinator could not claim due correction work: fixture=" + fixtureId
                        + " next=" + next + " now=" + now + " state=" + current.flags());
            }
            harness.advanceControlledTime(next);
        }
        throw new AssertionError("fixture " + fixtureId + " exceeded bounded first-observation dispatches");
    }

    private static long dispatchFixtureUntilVerified(Harness harness, int fixtureId,
                                                      long previousVerifiedGeneration) throws Exception {
        for (int dispatch = 0; dispatch < 500; dispatch++) {
            FixtureOwnershipSnapshot current = fixtureOwnershipSnapshot(fixtureId);
            if (current.currentVerifiedGeneration() != null
                    && current.currentVerifiedGeneration() > previousVerifiedGeneration
                    && "VERIFIED".equals(current.state())) {
                return current.currentVerifiedGeneration();
            }
            int claimed = harness.dispatchOneWithControlledResponse("fixture-" + fixtureId + "-fresh-second-" + dispatch);
            if (claimed != 0) continue;
            Instant now = harness.clock.instant();
            Instant next = harness.earliestActiveRunDue();
            if (next == null) {
                Timestamp fixtureDue = jdbc.queryForObject("SELECT next_due_at FROM " + TABLES.fixtureSync()
                        + " WHERE season_id=? AND fixture_id=?", Timestamp.class, SEASON.id(), fixtureId);
                if (fixtureDue == null) {
                    throw new AssertionError("coordinator drained without a receipt or persisted fixture due: "
                            + current.flags());
                }
                next = fixtureDue.toInstant();
                if (next.isBefore(now)) next = now;
                harness.advanceControlledTime(next);
                harness.histories.enqueuePersistedFixtureRetry(SEASON, fixtureId, next);
                continue;
            }
            if (!next.isAfter(now)) {
                throw new AssertionError("coordinator could not claim due second-batch work: fixture=" + fixtureId
                        + " next=" + next + " now=" + now + " state=" + current.flags());
            }
            harness.advanceControlledTime(next);
        }
        throw new AssertionError("fixture " + fixtureId + " exceeded bounded second-observation dispatches");
    }

    private static Map<String, Object> fixtureRunSourceFrame(long generation) {
        Map<String, Object> row = jdbc.queryForMap("SELECT r.generation,r.publication_key,r.state,r.runtime_mode,"
                + "r.runtime_epoch,r.requested_source_after,r.source_read_started_at,r.source_read_finished_at,"
                + "r.completed_at,m.source_floor,m.required_player_ids,m.fixtures_payload_id,f.body fixtures_body "
                + "FROM " + TABLES.run() + " r JOIN " + TABLES.fixtureDependencyManifest()
                + " m ON m.run_generation=r.generation JOIN " + TABLES.payload()
                + " f ON f.id=m.fixtures_payload_id WHERE r.generation=?", generation);
        Map<String, Object> result = new TreeMap<>(row);
        try {
            ArrayNode fixtureRows = (ArrayNode) JSON.readTree((byte[]) row.get("fixtures_body"));
            JsonNode target = null;
            String publicationKey = (String) row.get("publication_key");
            int fixtureId = Integer.parseInt(publicationKey.substring("fixture:".length()));
            for (JsonNode candidate : fixtureRows) {
                if (candidate.path("id").asInt() == fixtureId) {
                    target = candidate;
                    break;
                }
            }
            if (target != null) result.put("fixture_score", target.path("team_h_score").intValue());
            else result.put("fixture_score", null);
        } catch (IOException invalid) {
            throw new IllegalArgumentException("fixture run staged invalid fixture source", invalid);
        }
        result.remove("fixtures_body");
        result.put("source_payload_roles", jdbc.queryForList("SELECT id,endpoint_kind,event_id,player_id,"
                + "validation_state,source_published,read_started_at,read_finished_at FROM " + TABLES.payload()
                + " WHERE run_generation=? ORDER BY id", generation));
        return result;
    }

    private static FixtureVerification.Observation fixtureObservation(int fixtureId) {
        String observation = jdbc.queryForObject("SELECT observation::text FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", String.class, SEASON.id(), fixtureId);
        return FixtureObservationCodec.decode(observation);
    }

    private static SourceRead observationInterval(FixtureVerification.Observation observation) {
        return new SourceRead(observation.reads().startedAt(), observation.reads().completedAt());
    }

    private static void assertPendingObservationMatchesSource(Harness harness, int fixtureId, long generation,
                                                               Set<Integer> requiredPlayers, SourceData source)
            throws IOException {
        Map<String, Object> row = jdbc.queryForMap("SELECT observation::text,observation_generation,"
                + "current_verified_generation,last_verified_generation,validation_failures::text "
                + "FROM " + TABLES.fixtureSync() + " WHERE season_id=? AND fixture_id=?", SEASON.id(), fixtureId);
        FixtureVerification.Observation observation = FixtureObservationCodec.decode((String) row.get("observation"));
        assertThat(row.get("observation_generation")).isEqualTo(generation);
        assertThat(row.get("current_verified_generation")).isNull();
        assertThat(observation.coherent()).isTrue();
        assertThat(observation.finished()).isTrue();
        assertThat(observation.reads().histories().keySet()).containsExactlyInAnyOrderElementsOf(requiredPlayers);
        Map<String, Object> run = fixtureRunSourceFrame(generation);
        Instant wanted = ((Timestamp) run.get("requested_source_after")).toInstant();
        Instant completed = ((Timestamp) run.get("completed_at")).toInstant();
        Instant runStarted = ((Timestamp) run.get("source_read_started_at")).toInstant();
        Instant runFinished = ((Timestamp) run.get("source_read_finished_at")).toInstant();
        assertThat(runStarted).isAfterOrEqualTo(wanted);
        assertThat(runFinished).isAfterOrEqualTo(runStarted).isBeforeOrEqualTo(completed);
        assertThat(run.get("source_floor")).isEqualTo(run.get("requested_source_after"));
        assertThat(run.get("fixture_score")).isEqualTo(1);
        List<Map<String, Object>> ownPayloads = jdbc.queryForList("SELECT endpoint_kind,body,validation_state,"
                + "read_started_at,read_finished_at FROM " + TABLES.payload()
                + " WHERE run_generation=? ORDER BY id", generation);
        assertThat(ownPayloads).filteredOn(payload -> "BOOTSTRAP_STATIC".equals(payload.get("endpoint_kind")))
                .isNotEmpty().allSatisfy(payload -> assertThat(payload.get("body")).isEqualTo(source.bootstrapBytes()));
        assertThat(ownPayloads).filteredOn(payload -> "FIXTURES".equals(payload.get("endpoint_kind")))
                .isNotEmpty().allSatisfy(payload -> assertThat(payload.get("body")).isEqualTo(source.fixtureBytes()));
        int event = sourceGameweek(source, fixtureId);
        assertThat(ownPayloads).filteredOn(payload -> "EVENT_LIVE".equals(payload.get("endpoint_kind")))
                .isNotEmpty().allSatisfy(payload -> assertThat(payload.get("body")).isEqualTo(source.liveBytes(event)));
        ownPayloads.forEach(payload -> {
            assertThat(payload.get("validation_state")).isEqualTo("VALID");
            assertThat(((Timestamp) payload.get("read_started_at")).toInstant()).isAfterOrEqualTo(wanted);
            assertThat(((Timestamp) payload.get("read_finished_at")).toInstant()).isBeforeOrEqualTo(completed);
        });
        for (int playerId : requiredPlayers) {
            var read = observation.reads().histories().get(playerId);
            Map<String, Object> evidence = jdbc.queryForMap("SELECT hp.body,hp.validation_state,hp.read_started_at,"
                    + "hp.read_finished_at,hr.kind,hr.state,hr.runtime_mode,hr.runtime_epoch,hr.completed_at "
                    + "FROM " + TABLES.payload() + " hp JOIN " + TABLES.run()
                    + " hr ON hr.generation=hp.run_generation WHERE hp.endpoint_kind='ELEMENT_SUMMARY' "
                    + "AND hp.event_id IS NULL AND hp.player_id=? AND hp.read_started_at=? AND hp.read_finished_at=?",
                    playerId, Timestamp.from(read.startedAt()), Timestamp.from(read.completedAt()));
            assertThat(evidence.get("body")).isEqualTo(source.historyBytes(playerId));
            assertThat(read.startedAt()).isAfterOrEqualTo(wanted);
            assertThat(read.startedAt()).isAfterOrEqualTo(observation.reads().fixture().completedAt().plusMillis(1));
            assertThat(evidence.get("validation_state")).isEqualTo("VALID");
            assertThat(evidence.get("kind")).isEqualTo("PLAYER_HISTORY");
            assertThat(evidence.get("state")).isEqualTo("COMPLETED");
            assertThat(evidence.get("runtime_mode")).isEqualTo("ACTIVE");
            assertThat(((Number) evidence.get("runtime_epoch")).longValue()).isEqualTo(harness.fence.epoch());
            assertThat(((Timestamp) evidence.get("read_started_at")).toInstant()).isAfterOrEqualTo(wanted);
            assertThat(((Timestamp) evidence.get("completed_at")).toInstant()).isBeforeOrEqualTo(completed);
        }
        assertCurrentCanonicalSourceMatches(harness, source);
    }


    private static Map<Integer, FixtureHeaderState> currentFixtureHeaderStates(Set<Integer> fixtureIds) {
        List<Integer> ids = sorted(fixtureIds);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT f.id,f.gameweek,f.team_h,f.team_a,"
                + "f.team_h_score,f.team_a_score,f.kickoff_time,f.started,f.finished,f.finished_provisional,"
                + "f.minutes,f.stats::text core_stats,c.gameweek captured_gameweek,c.team_h captured_team_h,"
                + "c.team_a captured_team_a,c.kickoff_time captured_kickoff,c.started captured_started,"
                + "c.finished captured_finished,c.team_h_score captured_home_score,"
                + "c.team_a_score captured_away_score FROM fixture f JOIN " + CAPTURED.fixture()
                + " c ON c.season=? AND c.id=f.id WHERE f.season_id=? AND f.id IN ("
                + placeholders(ids.size()) + ") ORDER BY f.id", concat(SEASON.code(), SEASON.id(), ids));
        Map<Integer, FixtureHeaderState> result = new TreeMap<>();
        for (Map<String, Object> row : rows) {
            int fixtureId = ((Number) row.get("id")).intValue();
            Map<String, Object> core = new TreeMap<>();
            for (String field : List.of("gameweek", "team_h", "team_a", "team_h_score", "team_a_score",
                    "kickoff_time", "started", "finished", "finished_provisional", "minutes", "core_stats")) {
                core.put(field, row.get(field));
            }
            Map<String, Object> captured = new TreeMap<>();
            for (String field : List.of("captured_gameweek", "captured_team_h", "captured_team_a",
                    "captured_kickoff", "captured_started", "captured_finished", "captured_home_score",
                    "captured_away_score")) {
                captured.put(field, row.get(field));
            }
            result.put(fixtureId, new FixtureHeaderState(Map.copyOf(core), Map.copyOf(captured)));
        }
        assertThat(result.keySet()).containsExactlyInAnyOrderElementsOf(fixtureIds);
        return Map.copyOf(result);
    }

    private static Map<Integer, FixtureSourceState> currentCanonicalFixtureStates(Harness harness, int fixtureId) {
        RootDocuments root = currentCanonicalDocuments(harness, fixtureId);
        var snapshot = CatalogueScheduleParser.parse(SEASON, root.bootstrap(), root.fixtures());
        Map<Integer, FixtureSourceState> result = new TreeMap<>();
        for (var fixture : snapshot.fixtures()) {
            result.put(fixture.id(), new FixtureSourceState(FixtureVerification.verifiedHeader(fixture),
                    FixtureStatsFingerprint.of(fixture), fixture.homeScore(), fixture.awayScore()));
        }
        return Map.copyOf(result);
    }

    private static int sourceGameweek(SourceData source, int fixtureId) throws IOException {
        var read = new CatalogueScheduleParser.ReadInterval(START, START);
        var snapshot = CatalogueScheduleParser.parse(SEASON,
                new CatalogueScheduleParser.SourceDocument(source.bootstrapBytes(), read),
                new CatalogueScheduleParser.SourceDocument(source.fixtureBytes(), read));
        return snapshot.fixtures().stream().filter(row -> row.id() == fixtureId)
                .findFirst().orElseThrow().gameweek();
    }


    private static ParentSelectionDiagnostic plannerParentSelection(Harness harness, int fixtureId) {
        List<ParentSourceDiagnostic> validCandidates = new ArrayList<>();
        harness.store.findInLatestValidPublicPayloadPairs(SEASON.code(), 20, pair -> {
            try {
                var sourcePair = OwnedSyncStore.CanonicalSourcePair.from(pair.bootstrap(), pair.fixtures());
                Long publicationGeneration = harness.store.currentPublishedCanonicalPair(
                        SEASON.code(), sourcePair, harness.clock.instant())
                        .map(OwnedSyncStore.CanonicalPublication::publicationGeneration).orElse(null);
                validCandidates.add(parentDiagnostic(harness, fixtureId, pair, publicationGeneration));
                return java.util.Optional.of(Boolean.TRUE);
            } catch (IllegalArgumentException ignored) {
                return java.util.Optional.empty();
            }
        });
        ParentSourceDiagnostic newestValid = validCandidates.stream().findFirst()
                .orElseThrow(() -> new AssertionError("planner source selection returned no parseable pair"));
        ParentSourceDiagnostic currentPublished = harness.store.findInLatestCurrentPublishedCanonicalPairs(
                SEASON.code(), 20, harness.clock.instant(), selected -> {
                    try {
                        return java.util.Optional.of(parentDiagnostic(harness, fixtureId,
                                selected.pair(), selected.publication().publicationGeneration()));
                    } catch (IllegalArgumentException ignored) {
                        return java.util.Optional.empty();
                    }
                }).orElseThrow(() -> new AssertionError("native current-publication selection returned no pair"));
        return new ParentSelectionDiagnostic(newestValid, currentPublished, validCandidates.size());
    }

    private static ParentSourceDiagnostic parentDiagnostic(Harness harness, int fixtureId,
                                                            OwnedSyncStore.PublicPayloadPair pair,
                                                            Long publicationGeneration) {
        var snapshot = CatalogueScheduleParser.parse(SEASON,
                new CatalogueScheduleParser.SourceDocument(pair.bootstrap().body(),
                        new CatalogueScheduleParser.ReadInterval(pair.bootstrap().read().startedAt(),
                                pair.bootstrap().read().finishedAt())),
                new CatalogueScheduleParser.SourceDocument(pair.fixtures().body(),
                        new CatalogueScheduleParser.ReadInterval(pair.fixtures().read().startedAt(),
                                pair.fixtures().read().finishedAt())));
        var fixture = snapshot.fixtures().stream().filter(row -> row.id() == fixtureId)
                .findFirst().orElseThrow();
        var sourcePair = OwnedSyncStore.CanonicalSourcePair.from(pair.bootstrap(), pair.fixtures());
        return new ParentSourceDiagnostic(pair.runGeneration(), publicationGeneration,
                pair.bootstrap().id(), pair.fixtures().id(), pair.bootstrap().read(),
                pair.fixtures().read(), fixture.finished(), sourcePair.fingerprint(),
                OwnedSyncStore.sha256(FixtureVerification.verifiedHeader(fixture)));
    }

    private static Map<String, Object> targetFinishedMarker(int fixtureId) {
        return jdbc.queryForMap("SELECT first_finished_observed_at,finished_parent_generation,"
                + "finished_parent_publication_generation,finished_parent_bootstrap_payload_id,"
                + "finished_parent_fixtures_payload_id,finished_parent_source_fingerprint,"
                + "finished_parent_fixture_fingerprint,finished_parent_finished FROM "
                + TABLES.fixtureSync() + " WHERE season_id=? AND fixture_id=?", SEASON.id(), fixtureId);
    }

    private static boolean markerMatches(Map<String, Object> marker, ParentSourceDiagnostic parent) {
        return Boolean.TRUE.equals(marker.get("finished_parent_finished"))
                && java.util.Objects.equals(nullableLong(marker.get("finished_parent_generation")),
                        parent.sourceGeneration())
                && java.util.Objects.equals(nullableLong(marker.get("finished_parent_publication_generation")),
                        parent.publicationGeneration())
                && java.util.Objects.equals(nullableLong(marker.get("finished_parent_bootstrap_payload_id")),
                        parent.bootstrapPayloadId())
                && java.util.Objects.equals(nullableLong(marker.get("finished_parent_fixtures_payload_id")),
                        parent.fixturesPayloadId())
                && java.util.Objects.equals(marker.get("finished_parent_source_fingerprint"),
                        parent.sourceFingerprint())
                && java.util.Objects.equals(marker.get("finished_parent_fixture_fingerprint"),
                        parent.fixtureFingerprint());
    }

    private static List<Map<String, Object>> canonicalRootReceipts() {
        return jdbc.queryForList("SELECT publication_key,latest_generation,source_bootstrap_payload_id,"
                + "source_fixtures_payload_id,source_read_started_at,source_read_finished_at,published_at FROM "
                + TABLES.receipt() + " WHERE season=? AND publication_key IN (?,?) ORDER BY publication_key",
                SEASON.code(), CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY);
    }

    private static void assertCanonicalSourceInterval(ParentSourceDiagnostic parent,
                                                      List<Map<String, Object>> receipts) {
        Map<String, Object> publication = receipts.stream().filter(row ->
                java.util.Objects.equals(nullableLong(row.get("latest_generation")),
                        parent.publicationGeneration())
                        && java.util.Objects.equals(nullableLong(row.get("source_bootstrap_payload_id")),
                                parent.bootstrapPayloadId())
                        && java.util.Objects.equals(nullableLong(row.get("source_fixtures_payload_id")),
                                parent.fixturesPayloadId())).findFirst().orElseThrow();
        Instant sourceStarted = ((Timestamp) publication.get("source_read_started_at")).toInstant();
        Instant sourceFinished = ((Timestamp) publication.get("source_read_finished_at")).toInstant();
        assertThat(sourceStarted).isBeforeOrEqualTo(parent.bootstrapRead().startedAt())
                .isBeforeOrEqualTo(parent.fixturesRead().startedAt());
        assertThat(sourceFinished).isAfterOrEqualTo(parent.bootstrapRead().finishedAt())
                .isAfterOrEqualTo(parent.fixturesRead().finishedAt());
        assertThat(((Timestamp) publication.get("published_at")).toInstant()).isAfterOrEqualTo(sourceFinished);
    }

    private static int canonicalAttemptsAfter(Harness harness, Instant instant) {
        return jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE season=? AND runtime_mode='ACTIVE' AND runtime_epoch=?"
                + " AND kind='CATALOGUE_SCHEDULE' AND publication_key IN (?,?) AND last_attempt_at>?",
                Integer.class, SEASON.code(), harness.fence.epoch(),
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY, Timestamp.from(instant));
    }

    private static Instant later(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    private static void advanceGlobalTimeline(MutableClock clock, Instant target) {
        Instant current = clock.instant();
        if (target.isBefore(current)) throw new IllegalArgumentException("test time cannot move backwards");
        clock.advance(Duration.between(current, target));
    }

    private static void assertReceiptBackedFixtures(RuntimeFence fence, Set<Integer> fixtureIds) {
        for (int fixtureId : fixtureIds) {
            Long current = jdbc.queryForObject("SELECT current_verified_generation FROM " + TABLES.fixtureSync()
                    + " WHERE season_id=? AND fixture_id=?", Long.class, SEASON.id(), fixtureId);
            assertThat(current).as("fixture %d has current owned verification", fixtureId).isNotNull();
            assertThat(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + TABLES.receipt()
                    + " p JOIN " + TABLES.run() + " r ON r.generation=p.latest_generation"
                    + " WHERE p.season=? AND p.publication_key=? AND p.latest_generation=?"
                    + " AND r.runtime_mode=? AND r.runtime_epoch=? AND r.state='COMPLETED')",
                    Boolean.class, SEASON.code(), "fixture:" + fixtureId, current,
                    fence.mode().name(), fence.epoch())).as("fixture %d receipt belongs to the current epoch", fixtureId)
                    .isTrue();
        }
    }


    private static void assertBaselineCandidateCoverage(Set<Integer> fixtureIds, SourceData source) {
        Map<Integer, Set<Integer>> required = source.requiredHistoryPlayersByFixture(
                fixtureIds.stream().mapToInt(Integer::intValue).max().orElseThrow());
        for (int fixtureId : fixtureIds) {
            int candidateCount = required.get(fixtureId).size();
            long generation = jdbc.queryForObject("SELECT latest_generation FROM " + TABLES.receipt()
                    + " WHERE season=? AND publication_key=?", Long.class,
                    SEASON.code(), "fixture:" + fixtureId);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats"
                    + " WHERE season_id=? AND fixture_id=?", Integer.class, SEASON.id(), fixtureId))
                    .as("the baseline stores every current-club candidate for fixture %d", fixtureId)
                    .isEqualTo(candidateCount);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.playerFixtureEvidence()
                    + " WHERE season_id=? AND fixture_id=? AND generation=?", Integer.class,
                    SEASON.id(), fixtureId, generation)).isEqualTo(candidateCount);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats"
                    + " WHERE season_id=? AND fixture_id=? AND minutes=0", Integer.class,
                    SEASON.id(), fixtureId)).as("known zero-minute rows count in the baseline")
                    .isPositive();
            assertThat(jdbc.queryForObject("SELECT expected_participants=received_participants"
                    + " AND cardinality(missing_player_ids)=0 FROM " + TABLES.fixtureSync()
                    + " WHERE season_id=? AND fixture_id=?", Boolean.class, SEASON.id(), fixtureId)).isTrue();
        }
    }

    private static Map<Integer, Instant> fixtureFirstVerifiedTimes(Set<Integer> fixtureIds) {
        Map<Integer, Instant> result = new TreeMap<>();
        for (int fixtureId : fixtureIds) {
            result.put(fixtureId, jdbc.queryForObject("SELECT first_verified_at FROM " + TABLES.fixtureSync()
                    + " WHERE season_id=? AND fixture_id=?", Timestamp.class,
                    SEASON.id(), fixtureId).toInstant());
        }
        return Map.copyOf(result);
    }

    private static void assertQualifyingBaselineReceipts(Set<Integer> fixtureIds,
                                                          Map<Integer, Long> generations,
                                                          Map<Integer, Long> initialReceipts,
                                                          Map<Integer, Instant> firstVerifiedAt) {
        for (int fixtureId : fixtureIds) {
            Map<String, Object> row = jdbc.queryForMap("SELECT p.latest_generation,p.source_read_started_at,"
                    + "p.source_read_finished_at,p.published_at,r.requested_source_after,r.source_read_started_at "
                    + "run_source_started,r.source_read_finished_at run_source_finished,r.completed_at,"
                    + "fs.data_checked_transition_at FROM " + TABLES.receipt() + " p JOIN " + TABLES.run()
                    + " r ON r.generation=p.latest_generation JOIN " + TABLES.fixtureSync()
                    + " fs ON fs.season_id=? AND p.publication_key='fixture:' || fs.fixture_id::text"
                    + " WHERE p.season=? AND p.publication_key=? AND fs.fixture_id=? AND r.state='COMPLETED'"
                    + " AND r.runtime_mode='ACTIVE' AND r.runtime_epoch=1",
                    SEASON.id(), SEASON.code(), "fixture:" + fixtureId, fixtureId);
            Instant sourceStarted = ((Timestamp) row.get("source_read_started_at")).toInstant();
            Instant sourceFinished = ((Timestamp) row.get("source_read_finished_at")).toInstant();
            Instant publishedAt = ((Timestamp) row.get("published_at")).toInstant();
            Instant completedAt = ((Timestamp) row.get("completed_at")).toInstant();
            Instant transitionAt = ((Timestamp) row.get("data_checked_transition_at")).toInstant();
            assertThat(((Number) row.get("latest_generation")).longValue()).isEqualTo(generations.get(fixtureId))
                    .isGreaterThan(initialReceipts.get(fixtureId));
            assertThat(((Timestamp) row.get("run_source_started")).toInstant()).isEqualTo(sourceStarted);
            assertThat(((Timestamp) row.get("run_source_finished")).toInstant()).isEqualTo(sourceFinished);
            assertThat(sourceStarted).isAfterOrEqualTo(((Timestamp) row.get("requested_source_after")).toInstant());
            assertThat(sourceStarted).isAfterOrEqualTo(transitionAt);
            assertThat(sourceStarted).isAfterOrEqualTo(firstVerifiedAt.get(fixtureId).plus(Duration.ofHours(48)));
            assertThat(sourceFinished).isAfterOrEqualTo(sourceStarted);
            assertThat(completedAt).isAfterOrEqualTo(sourceFinished);
            assertThat(publishedAt).isAfterOrEqualTo(completedAt);
            assertThat(List.of(sourceStarted, sourceFinished, completedAt, publishedAt))
                    .allSatisfy(value -> assertThat(isDatabaseMicrosecond(value)).isTrue());
        }
    }

    private static Map<Integer, FixturePublicationComponents> fixturePublicationComponents(Set<Integer> fixtureIds) {
        Map<Integer, FixturePublicationComponents> result = new TreeMap<>();
        for (int fixtureId : fixtureIds.stream().sorted().toList()) {
            List<Map<String, Object>> receiptRows = jdbc.queryForList("SELECT to_jsonb(p)::text receipt_identity,"
                    + "p.latest_generation,(fs.state='VERIFIED' AND fs.current_verified_generation=p.latest_generation"
                    + " AND EXISTS (SELECT 1 FROM " + TABLES.run() + " r WHERE r.generation=p.latest_generation"
                    + " AND r.season=p.season AND r.kind='FIXTURE_VERIFICATION' AND r.publication_key=p.publication_key"
                    + " AND r.state='COMPLETED' AND r.runtime_mode='ACTIVE' AND r.runtime_epoch>0"
                    + " AND r.completed_at<=p.published_at AND r.source_read_started_at=p.source_read_started_at"
                    + " AND r.source_read_finished_at=p.source_read_finished_at)) verification_valid"
                    + " FROM " + TABLES.receipt() + " p JOIN " + TABLES.fixtureSync()
                    + " fs ON p.publication_key='fixture:' || fs.fixture_id::text AND fs.season_id=?"
                    + " WHERE p.season=? AND fs.fixture_id=?", SEASON.id(), SEASON.code(), fixtureId);
            List<String> pfsRows = jdbc.queryForList("SELECT to_jsonb(p)::text FROM player_fixture_stats p"
                            + " WHERE p.season_id=? AND p.fixture_id=? ORDER BY p.player_id",
                    String.class, SEASON.id(), fixtureId);
            List<String> evidenceRows = jdbc.queryForList("SELECT to_jsonb(e)::text FROM "
                            + TABLES.playerFixtureEvidence() + " e WHERE e.season_id=? AND e.fixture_id=?"
                            + " ORDER BY e.generation,e.player_id",
                    String.class, SEASON.id(), fixtureId);
            Map<String, Object> sourceHeader = jdbc.queryForMap("SELECT id,season_id,gameweek,code,team_h,team_a,"
                    + "team_h_score,team_a_score,kickoff_time,started,finished,finished_provisional,minutes,"
                    + "stats::text stats FROM fixture WHERE season_id=? AND id=?", SEASON.id(), fixtureId);
            Map<String, Object> capturedHeader = jdbc.queryForMap("SELECT id,season,gameweek,team_h,team_a,"
                    + "kickoff_time,started,finished,team_h_score,team_a_score FROM " + CAPTURED.fixture()
                    + " WHERE season=? AND id=?", SEASON.code(), fixtureId);
            List<Long> runGenerations = jdbc.queryForList("SELECT generation FROM " + TABLES.run()
                            + " WHERE season=? AND kind='FIXTURE_VERIFICATION' AND publication_key=? ORDER BY generation",
                    Long.class, SEASON.code(), "fixture:" + fixtureId);
            Map<String, Object> receipt = receiptRows.isEmpty() ? Map.of() : receiptRows.getFirst();
            result.put(fixtureId, new FixturePublicationComponents((String) receipt.get("receipt_identity"),
                    nullableLong(receipt.get("latest_generation")), Boolean.TRUE.equals(receipt.get("verification_valid")),
                    List.copyOf(pfsRows), List.copyOf(evidenceRows), sourceHeader, capturedHeader,
                    List.copyOf(runGenerations), fixtureOwnershipSnapshot(fixtureId)));
        }
        return Map.copyOf(result);
    }

    private static void assertScheduleCorrectionComponentTransition(
            Map<Integer, FixturePublicationComponents> before,
            Map<Integer, FixturePublicationComponents> after, Set<Integer> changed) {
        System.out.println("OWNED_TASK4_SCHEDULE_COMPONENT_DIFF "
                + fixturePublicationComponentDiffs(before, after));
        assertThat(after.keySet()).containsExactlyInAnyOrderElementsOf(before.keySet());
        for (int fixtureId : before.keySet()) {
            FixturePublicationComponents old = before.get(fixtureId);
            FixturePublicationComponents updated = after.get(fixtureId);
            String details = "fixture " + fixtureId + " schedule component diff="
                    + fixturePublicationComponentDiffs(Map.of(fixtureId, old), Map.of(fixtureId, updated));
            assertThat(updated.receiptIdentity()).as(details + " receipt publication identity").isEqualTo(old.receiptIdentity());
            assertThat(updated.latestGeneration()).as(details + " receipt latest generation")
                    .isEqualTo(old.latestGeneration());
            assertThat(updated.pfsRows()).as(details + " immutable physical PFS rows").isEqualTo(old.pfsRows());
            assertThat(updated.evidenceRows()).as(details + " source evidence rows").isEqualTo(old.evidenceRows());
            if (changed.contains(fixtureId)) {
                assertThat(changedHeaderFields(old.sourceHeader(), updated.sourceHeader()))
                        .as(details + " corrected core source-header fields").containsExactly("team_h_score");
                assertThat(old.sourceHeader().get("team_h_score")).isEqualTo(0);
                assertThat(updated.sourceHeader().get("team_h_score")).isEqualTo(1);
                assertThat(changedHeaderFields(old.capturedHeader(), updated.capturedHeader()))
                        .as(details + " corrected captured source-header fields").containsExactly("team_h_score");
                assertThat(old.capturedHeader().get("team_h_score")).isEqualTo(0);
                assertThat(updated.capturedHeader().get("team_h_score")).isEqualTo(1);
                assertThat(old.verificationValid()).as(details).isTrue();
                assertThat(updated.verificationValid()).as(details).isFalse();
                assertThat(updated.ownership().currentVerifiedGeneration()).as(details).isNull();
                assertThat(updated.ownership().lastVerifiedGeneration()).as(details)
                        .isEqualTo(old.latestGeneration());
                assertScheduleInvalidationRetainsActualOwnership(fixtureId, old.ownership(), updated.ownership());
                assertThat(updated.verificationRunGenerations()).as(details + " queued source correction")
                        .containsAll(old.verificationRunGenerations());
                assertThat(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + TABLES.run()
                        + " WHERE season=? AND publication_key=? AND kind='FIXTURE_VERIFICATION'"
                        + " AND runtime_mode='ACTIVE' AND runtime_epoch=1 AND state='PENDING')",
                        Boolean.class, SEASON.code(), "fixture:" + fixtureId))
                        .as(details + " schedule invalidation enqueues its correction").isTrue();
            } else {
                assertThat(updated.sourceHeader()).as(details + " unchanged core source header")
                        .isEqualTo(old.sourceHeader());
                assertThat(updated.capturedHeader()).as(details + " unchanged captured source header")
                        .isEqualTo(old.capturedHeader());
                assertThat(updated.verificationValid()).as(details).isTrue();
                assertThat(updated.ownership().state()).as(details).isEqualTo("VERIFIED");
                assertThat(updated.ownership().currentVerifiedGeneration()).as(details)
                        .isEqualTo(old.latestGeneration());
                assertThat(updated.ownership().lastVerifiedGeneration()).as(details)
                        .isEqualTo(old.latestGeneration());
                assertThat(updated.verificationRunGenerations()).as(details + " no correction replay")
                        .isEqualTo(old.verificationRunGenerations());
            }
        }
    }



    private static SharedFixtureRecoveryPolicy.Decision fixtureOnlyRecoveryDecision(
            AdaptiveMatchdayPlanner.Fixture fixture, AdaptiveMatchdayPlanner.FixtureProgress progress,
            Instant now) {
        AdaptiveMatchdayPlanner.RecoveryMetadata recovery = progress.recovery();
        return SharedFixtureRecoveryPolicy.decide(new SharedFixtureRecoveryPolicy.Input(now,
                fixture.recoverySourceStatus(), progress.verifiedAt() != null,
                progress.firstFinishedObservedAt(), progress.nextDueAt(), recovery.dueReason(),
                recovery.transportRetryAt(), recovery.cooldownUntil(), recovery.recoveryDay(),
                recovery.episodeStartedAt(), recovery.episodeUntil(), recovery.publishedAt(),
                recovery.dependencyRetryAt()));
    }

    private static boolean hasFutureFixtureSlot(int fixtureId,
                                                List<PlannerRuntimeRepository.DurableSlot> slots, Instant now) {
        return slots.stream().map(PlannerRuntimeRepository.DurableSlot::slot).anyMatch(slot ->
                (slot.kind() == AdaptiveMatchdayPlanner.WorkKind.FIXTURE_VERIFICATION
                        || slot.kind() == AdaptiveMatchdayPlanner.WorkKind.FIXTURE_CORRECTION)
                        && slot.fixtureIds().equals(Set.of(fixtureId))
                        && slot.id().endsWith(":fixtures=" + fixtureId + ":" + slot.dueAt())
                        && slot.dueAt().isAfter(now));
    }

    private static Instant fixtureOnlyFutureDue(String season, int activePlayerCount, Clock clock,
                                                AdaptiveMatchdayPlanner.Fixture fixture,
                                                AdaptiveMatchdayPlanner.FixtureProgress progress,
                                                SharedFixtureRecoveryPolicy.Decision recovery) {
        Instant now = clock.instant();
        Instant candidate = switch (recovery.action()) {
            case WAIT -> recovery.notBefore();
            case NORMAL -> progress.nextDueAt();
            case WAIT_FOR_PARENT, WAIT_FOR_SOURCE, RESERVE_EPISODE, CONTINUE_EPISODE -> null;
        };
        if (candidate == null || !candidate.isAfter(now)) return null;
        AdaptiveMatchdayPlanner.Plan plan = new AdaptiveMatchdayPlanner(clock).plan(
                new AdaptiveMatchdayPlanner.Input(season, activePlayerCount, List.of(fixture),
                        List.of(progress), List.of()));
        return plan.nextDueAt().filter(candidate::equals).orElse(null);
    }

    private static boolean hasFixtureSpecificFutureWork(int fixtureId, boolean queuedRun,
                                                         List<PlannerRuntimeRepository.DurableSlot> slots,
                                                         Instant fixtureOnlyDue, Instant coordinatorNext, Instant now) {
        boolean policyFuture = fixtureOnlyDue != null && fixtureOnlyDue.isAfter(now)
                && coordinatorNext != null && coordinatorNext.isAfter(now)
                && !coordinatorNext.isAfter(fixtureOnlyDue);
        return queuedRun || hasFutureFixtureSlot(fixtureId, slots, now) || policyFuture;
    }


    private static Map<Integer, String> fixturePublicationComponentDiffs(
            Map<Integer, FixturePublicationComponents> before,
            Map<Integer, FixturePublicationComponents> after) {
        Map<Integer, String> result = new TreeMap<>();
        Set<Integer> fixtureIds = new java.util.TreeSet<>(before.keySet());
        fixtureIds.addAll(after.keySet());
        for (int fixtureId : fixtureIds) {
            FixturePublicationComponents old = before.get(fixtureId);
            FixturePublicationComponents updated = after.get(fixtureId);
            if (old == null || updated == null) {
                result.put(fixtureId, "componentSnapshot=" + (old == null ? "missing-before" : "missing-after"));
                continue;
            }
            List<String> changes = new ArrayList<>();
            if (!java.util.Objects.equals(old.receiptIdentity(), updated.receiptIdentity())) {
                changes.add("receiptIdentity=" + old.receiptIdentity() + "->" + updated.receiptIdentity());
            }
            if (!java.util.Objects.equals(old.latestGeneration(), updated.latestGeneration())) {
                changes.add("latestGeneration=" + old.latestGeneration() + "->" + updated.latestGeneration());
            }
            if (old.verificationValid() != updated.verificationValid()) {
                changes.add("verificationValid=" + old.verificationValid() + "->" + updated.verificationValid());
            }
            Set<String> sourceChanges = changedHeaderFields(old.sourceHeader(), updated.sourceHeader());
            if (!sourceChanges.isEmpty()) changes.add("sourceHeader=" + sourceChanges.stream().collect(
                    java.util.stream.Collectors.toMap(field -> field,
                            field -> old.sourceHeader().get(field) + "->" + updated.sourceHeader().get(field),
                            (first, second) -> first, TreeMap::new)));
            if (!old.pfsRows().equals(updated.pfsRows())) {
                changes.add("PFS=" + rowDifference(old.pfsRows(), updated.pfsRows()));
            }
            Set<String> capturedChanges = changedHeaderFields(old.capturedHeader(), updated.capturedHeader());
            if (!capturedChanges.isEmpty()) changes.add("capturedHeader=" + capturedChanges.stream().collect(
                    java.util.stream.Collectors.toMap(field -> field,
                            field -> old.capturedHeader().get(field) + "->" + updated.capturedHeader().get(field),
                            (first, second) -> first, TreeMap::new)));
            if (!old.evidenceRows().equals(updated.evidenceRows())) {
                changes.add("sourceEvidence=" + rowDifference(old.evidenceRows(), updated.evidenceRows()));
            }
            if (!old.ownership().equals(updated.ownership())) {
                changes.add("ownership=" + old.ownership().flags() + "->" + updated.ownership().flags());
            }
            if (!old.verificationRunGenerations().equals(updated.verificationRunGenerations())) {
                changes.add("verificationRuns=" + old.verificationRunGenerations()
                        + "->" + updated.verificationRunGenerations());
            }
            if (!changes.isEmpty()) result.put(fixtureId, String.join(";", changes));
        }
        return Map.copyOf(result);
    }

    private static Set<String> changedHeaderFields(Map<String, Object> before, Map<String, Object> after) {
        Set<String> keys = new java.util.TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        return keys.stream().filter(key -> !java.util.Objects.equals(before.get(key), after.get(key)))
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
    }

    private static String rowDifference(List<String> before, List<String> after) {
        Set<String> removed = new java.util.TreeSet<>(before);
        removed.removeAll(after);
        Set<String> added = new java.util.TreeSet<>(after);
        added.removeAll(before);
        return "rows=" + before.size() + "->" + after.size() + ",removed="
                + removed.stream().limit(2).toList() + ",added=" + added.stream().limit(2).toList();
    }

    private static String baselinePublicationChecksum(Set<Integer> fixtureIds) {
        StringBuilder canonical = new StringBuilder();
        for (int fixtureId : fixtureIds.stream().sorted().toList()) {
            Map<String, Object> receipt = jdbc.queryForMap("SELECT p.latest_generation,p.source_read_started_at,"
                    + "p.source_read_finished_at,p.published_at,p.source_pair_fingerprint,"
                    + "p.source_bootstrap_sha256,p.source_fixtures_sha256,fs.last_verified_generation,"
                    + "fs.verified_header,fs.verified_stats_fingerprint,fs.observation->>'fingerprint' observation_fp,"
                    + "fs.expected_participants,fs.received_participants,fs.missing_player_ids::text missing"
                    + " FROM " + TABLES.receipt() + " p JOIN " + TABLES.fixtureSync()
                    + " fs ON p.publication_key='fixture:' || fs.fixture_id::text AND fs.season_id=?"
                    + " WHERE p.season=? AND fs.fixture_id=?", SEASON.id(), SEASON.code(), fixtureId);
            canonical.append(fixtureId).append('|').append(receipt).append('\n');
            jdbc.queryForList("SELECT to_jsonb(p)::text FROM player_fixture_stats p"
                            + " WHERE p.season_id=? AND p.fixture_id=? ORDER BY p.player_id",
                    String.class, SEASON.id(), fixtureId).forEach(row -> canonical.append("p|").append(row).append('\n'));
            jdbc.queryForList("SELECT to_jsonb(e)::text FROM " + TABLES.playerFixtureEvidence()
                            + " e WHERE e.season_id=? AND e.fixture_id=? ORDER BY e.generation,e.player_id",
                    String.class, SEASON.id(), fixtureId).forEach(row -> canonical.append("e|").append(row).append('\n'));
        }
        return OwnedSyncStore.sha256(canonical.toString());
    }

    private static String historyFingerprintChecksum(Set<Integer> fixtureIds) {
        String keys = fixtureIds.stream().sorted().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        String rows = jdbc.queryForList("SELECT player_id || ':' || fixture_id || ':' || fingerprint FROM "
                        + TABLES.historyFingerprint() + " WHERE season_id=? AND fixture_id IN (" + keys + ")"
                        + " ORDER BY fixture_id,player_id", String.class, SEASON.id()).stream()
                .collect(java.util.stream.Collectors.joining("\n"));
        return OwnedSyncStore.sha256(rows);
    }


    private static FixtureOwnershipSnapshot fixtureOwnershipSnapshot(int fixtureId) {
        Map<String, Object> row = jdbc.queryForMap("SELECT state,observation::text observation,"
                + "observation->>'coherent' observation_coherent,observation->>'fingerprint' observation_fingerprint,"
                + "observation_generation,current_verified_generation,last_verified_generation,verified_header,"
                + "verified_stats_fingerprint,validation_failures::text validation_failures,next_due_at FROM "
                + TABLES.fixtureSync() + " WHERE season_id=? AND fixture_id=?", SEASON.id(), fixtureId);
        Timestamp nextDue = (Timestamp) row.get("next_due_at");
        return new FixtureOwnershipSnapshot((String) row.get("state"), (String) row.get("observation"),
                (String) row.get("observation_coherent"), (String) row.get("observation_fingerprint"),
                nullableLong(row.get("observation_generation")),
                nullableLong(row.get("current_verified_generation")),
                nullableLong(row.get("last_verified_generation")), (String) row.get("verified_header"),
                (String) row.get("verified_stats_fingerprint"), (String) row.get("validation_failures"),
                nextDue == null ? null : nextDue.toInstant());
    }

    private static Long nullableLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static void assertScheduleInvalidationRetainsActualOwnership(int fixtureId,
                                                                         FixtureOwnershipSnapshot before,
                                                                         FixtureOwnershipSnapshot after) {
        String details = "fixture " + fixtureId + " schedule ownership before=" + before.flags()
                + " after=" + after.flags();
        assertThat(before.state()).as(details).isEqualTo("VERIFIED");
        assertThat(before.currentVerifiedGeneration()).as(details).isNotNull();
        assertThat(before.lastVerifiedGeneration()).as(details).isEqualTo(before.currentVerifiedGeneration());
        if (before.observation() != null) {
            assertThat(before.observationCoherent()).as(details).isEqualTo("true");
        }
        assertInvalidatedOwnershipRemains(fixtureId, before, after);
    }

    private static void assertInvalidatedOwnershipRemains(int fixtureId,
                                                           FixtureOwnershipSnapshot expected,
                                                           FixtureOwnershipSnapshot actual) {
        String details = "fixture " + fixtureId + " retained invalidation ownership expected="
                + expected.flags() + " actual=" + actual.flags();
        assertThat(actual.state()).as(details).isNotEqualTo("VERIFIED");
        assertThat(actual.currentVerifiedGeneration()).as(details).isNull();
        assertThat(actual.lastVerifiedGeneration()).as(details).isNotNull()
                .isEqualTo(expected.lastVerifiedGeneration());
        assertThat(actual.observationGeneration()).as(details).isEqualTo(expected.observationGeneration());
        assertThat(actual.verifiedHeader()).as(details + " retained verified source header")
                .isEqualTo(expected.verifiedHeader());
        assertThat(actual.verifiedStatsFingerprint()).as(details + " retained verified statistics fingerprint")
                .isEqualTo(expected.verifiedStatsFingerprint());
        if (expected.observation() == null) {
            assertThat(actual.observation()).as(details + "; do not invent an observation pair").isNull();
        } else {
            assertThat(actual.observationCoherent()).as(details).isEqualTo("false");
            assertThat(jdbc.queryForObject("SELECT (?::jsonb - 'coherent')=(?::jsonb - 'coherent')",
                    Boolean.class, expected.observation(), actual.observation())).as(details).isTrue();
        }
    }

    private static void assertUnverifiedAfterScheduleCorrection(int fixtureId,
                                                                  FixtureOwnershipSnapshot verifiedBaseline,
                                                                  FixtureOwnershipSnapshot actual) {
        String details = "fixture " + fixtureId + " remains unverified after schedule correction baseline="
                + verifiedBaseline.flags() + " actual=" + actual.flags();
        assertThat(actual.state()).as(details).isNotEqualTo("VERIFIED");
        assertThat(actual.currentVerifiedGeneration()).as(details).isNull();
        assertThat(actual.lastVerifiedGeneration()).as(details).isNotNull()
                .isEqualTo(verifiedBaseline.lastVerifiedGeneration());
        assertThat(actual.verifiedHeader()).as(details + " retained verified source header")
                .isEqualTo(verifiedBaseline.verifiedHeader());
        assertThat(actual.verifiedStatsFingerprint()).as(details + " retained verified statistics fingerprint")
                .isEqualTo(verifiedBaseline.verifiedStatsFingerprint());
    }

    private static void seedLegacyDailyOnlyGates(Harness harness, Set<Integer> fixtureIds) {
        jdbc.update("UPDATE " + TABLES.fixtureSync()
                + " SET alert_state='DAILY_ONLY', next_due_at=NULL, due_reason=NULL, updated_at=?"
                + " WHERE season_id=? AND fixture_id IN (" + placeholders(fixtureIds.size()) + ")",
                concat(Timestamp.from(harness.clock.instant()), SEASON.id(), sorted(fixtureIds)));
        FixtureSyncRepository repository = new FixtureSyncRepository(jdbc, TABLES);
        harness.store.withinRuntimeFence(() -> {
            fixtureIds.stream().sorted().forEach(fixtureId ->
                    repository.scheduleNextAttempt(SEASON, fixtureId, harness.clock.instant()));
            return null;
        });
        jdbc.update("UPDATE " + TABLES.fixtureSync() + " SET due_reason=NULL WHERE season_id=?"
                + " AND fixture_id IN (" + placeholders(fixtureIds.size()) + ")",
                concat(SEASON.id(), sorted(fixtureIds)));
    }


    private static List<Map<String, Object>> fixtureGateTrace(Set<Integer> fixtureIds) {
        String ids = fixtureIds.stream().sorted().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        return jdbc.queryForList("SELECT fixture_id,state,first_finished_observed_at,next_due_at,due_reason,"
                + "alert_state,attempt_count,recovery_day,recovery_started_at,recovery_until,"
                + "current_verified_generation,last_verified_generation FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id IN (" + ids + ") ORDER BY fixture_id", SEASON.id());
    }


    private static List<Map<String, Object>> pfsRows(String mode, int fixtureId) {
        PfsReadSource readSource = new PfsReadSource(jdbc, mode, SEASON.code(),
                TABLES.fixtureSync(), TABLES.playerFixtureEvidence());
        return jdbc.queryForList("WITH " + readSource.cte()
                + " SELECT * FROM pfs_read WHERE season_id=? AND fixture_id=? ORDER BY player_id",
                SEASON.id(), fixtureId);
    }



    private static void assertDailyCapture(String publicationKey, Instant scheduledAt) {
        Map<String, Object> receipt = jdbc.queryForMap("SELECT source_read_started_at,source_read_finished_at,"
                + "published_at FROM " + TABLES.receipt() + " WHERE season=? AND publication_key=?",
                SEASON.code(), publicationKey);
        Instant startedAt = ((Timestamp) receipt.get("source_read_started_at")).toInstant();
        Instant finishedAt = ((Timestamp) receipt.get("source_read_finished_at")).toInstant();
        Instant publishedAt = ((Timestamp) receipt.get("published_at")).toInstant();
        assertThat(startedAt).as("%s source frame starts no earlier than its UK schedule", publicationKey)
                .isAfterOrEqualTo(scheduledAt);
        assertThat(finishedAt).as("%s source frame is ordered", publicationKey).isAfterOrEqualTo(startedAt);
        assertThat(publishedAt).as("%s publication follows the captured source frame", publicationKey)
                .isAfterOrEqualTo(finishedAt);
        assertThat(List.of(startedAt, finishedAt, publishedAt)).allSatisfy(instant ->
                assertThat(isDatabaseMicrosecond(instant)).as("%s source timestamp precision", publicationKey)
                        .isTrue());
    }


    private static int activeClaimAttempts(long epoch) {
        return jdbc.queryForObject("SELECT COALESCE(sum(attempt_count),0) FROM " + TABLES.run()
                + " WHERE season=? AND runtime_mode='ACTIVE' AND runtime_epoch=?",
                Integer.class, SEASON.code(), epoch);
    }


    private static int pendingFixtureRuns(long epoch, Set<Integer> fixtureIds) {
        String keys = fixtureIds.stream().sorted().map(id -> "'fixture:" + id + "'")
                .collect(java.util.stream.Collectors.joining(","));
        return jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE season=? AND kind='FIXTURE_VERIFICATION' AND runtime_mode='ACTIVE'"
                + " AND runtime_epoch=? AND state IN ('PENDING','LEASED')"
                + " AND publication_key IN (" + keys + ")", Integer.class, SEASON.code(), epoch);
    }






    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static Object[] concat(Object first, Object second, List<Integer> rest) {
        Object[] result = new Object[2 + rest.size()];
        result[0] = first;
        result[1] = second;
        for (int index = 0; index < rest.size(); index++) result[index + 2] = rest.get(index);
        return result;
    }

    private static Object[] concat(Object first, List<Integer> rest) {
        Object[] result = new Object[1 + rest.size()];
        result[0] = first;
        for (int index = 0; index < rest.size(); index++) result[index + 1] = rest.get(index);
        return result;
    }

    private static List<Integer> sorted(Set<Integer> ids) {
        return ids.stream().sorted().toList();
    }

    private static void assertStoredFixtureOracle(int fixtureId, FixtureOracle oracle) {
        Map<String, Object> stored = jdbc.queryForMap("SELECT verified_header,verified_stats_fingerprint,"
                + "observation->>'fingerprint' observation_fingerprint FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", SEASON.id(), fixtureId);
        assertThat(stored.get("verified_header")).isEqualTo(oracle.headerFingerprint());
        assertThat(stored.get("verified_stats_fingerprint")).isEqualTo(oracle.statsFingerprint());
        assertThat(stored.get("observation_fingerprint")).isEqualTo(oracle.observationFingerprint());
    }

    private static Map<Integer, FixtureOracle> sourceFixtureOracles(SourceData source,
                                                                     Set<Integer> fixtureIds) throws IOException {
        var read = new CatalogueScheduleParser.ReadInterval(START, START);
        var fixtureRead = new CatalogueScheduleParser.ReadInterval(START, START);
        var liveRead = new CatalogueScheduleParser.ReadInterval(START.plusMillis(1), START.plusMillis(2));
        var historyRead = new CatalogueScheduleParser.ReadInterval(START.plusMillis(3), START.plusMillis(4));
        var bootstrap = new CatalogueScheduleParser.SourceDocument(source.bootstrapBytes(), read);
        var fixtures = new CatalogueScheduleParser.SourceDocument(source.fixtureBytes(), read);
        var snapshot = CatalogueScheduleParser.parse(SEASON, bootstrap, fixtures);
        List<HistoryParser.CanonicalFixture> canonical = snapshot.fixtures().stream()
                .map(HistoryParser::canonicalFixture).toList();
        Map<Integer, FixtureOracle> result = new TreeMap<>();
        for (int fixtureId : fixtureIds) {
            var fixture = snapshot.fixtures().stream().filter(row -> row.id() == fixtureId)
                    .findFirst().orElseThrow();
            var live = new CatalogueScheduleParser.SourceDocument(source.liveBytes(fixture.gameweek()), liveRead);
            Set<Integer> candidates = candidatePlayers(snapshot, fixture, live);
            List<HistoryParser.HistoryBatch> histories = candidates.stream().sorted()
                    .map(playerId -> HistoryParser.parse(SEASON, playerId,
                            new CatalogueScheduleParser.SourceDocument(source.historyBytes(playerId), historyRead),
                            canonical))
                    .toList();
            FixtureVerification.Decision expected = FixtureVerification.verify(SEASON, fixture, fixtureRead,
                    live, histories, null, START.plusMillis(5));
            result.put(fixtureId, new FixtureOracle(
                    FixtureVerification.verifiedHeader(fixture),
                    FixtureStatsFingerprint.of(fixture), expected.observation().fingerprint(),
                    fixture.gameweek(), candidates, expected.expectedParticipants(), expected.receivedParticipants()));
        }
        return Map.copyOf(result);
    }

    private static Map<Integer, HistoryRow> sourceHistoryRows(SourceData source, int fixtureId) throws IOException {
        var sourceRead = new CatalogueScheduleParser.ReadInterval(START, START);
        var bootstrap = new CatalogueScheduleParser.SourceDocument(source.bootstrapBytes(), sourceRead);
        var fixtures = new CatalogueScheduleParser.SourceDocument(source.fixtureBytes(), sourceRead);
        var snapshot = CatalogueScheduleParser.parse(SEASON, bootstrap, fixtures);
        var fixture = snapshot.fixtures().stream().filter(row -> row.id() == fixtureId).findFirst().orElseThrow();
        var live = new CatalogueScheduleParser.SourceDocument(source.liveBytes(fixture.gameweek()), sourceRead);
        List<HistoryParser.CanonicalFixture> canonical = snapshot.fixtures().stream()
                .map(HistoryParser::canonicalFixture).toList();
        Map<Integer, HistoryRow> rows = new TreeMap<>();
        for (int playerId : candidatePlayers(snapshot, fixture, live).stream().sorted().toList()) {
            var history = new CatalogueScheduleParser.SourceDocument(source.historyBytes(playerId), sourceRead);
            List<HistoryRow> fixtureRows = HistoryParser.parse(SEASON, playerId, history, canonical).rows().stream()
                    .filter(row -> row.fixtureId() == fixtureId).toList();
            assertThat(fixtureRows).as("source history for player %d and fixture %d", playerId, fixtureId)
                    .hasSize(1);
            rows.put(playerId, fixtureRows.getFirst());
        }
        return Map.copyOf(rows);
    }

    private static void assertPhysicalPfsMatchesSource(int fixtureId, SourceData source, long generation)
            throws IOException {
        Map<Integer, HistoryRow> expected = sourceHistoryRows(source, fixtureId);
        List<Map<String, Object>> actual = jdbc.queryForList("SELECT player_id,gameweek,player_team_id,"
                + "opponent_team_id,was_home,kickoff_time,minutes,total_points,bonus,bps,goals_scored,assists,"
                + "clean_sheets,goals_conceded,own_goals,penalties_saved,penalties_missed,saves,yellow_cards,"
                + "red_cards,value,selected,transfers_in,transfers_out,starts,expected_goals,expected_assists,"
                + "expected_goal_involvements,expected_goals_conceded,influence,creativity,threat,ict_index,"
                + "clearances_blocks_interceptions,recoveries,tackles,defensive_contribution FROM player_fixture_stats "
                + "WHERE season_id=? AND fixture_id=? ORDER BY player_id", SEASON.id(), fixtureId);
        assertThat(actual).as("complete physical PFS rows for fixture %d", fixtureId).hasSize(expected.size());
        for (Map<String, Object> row : actual) {
            int playerId = ((Number) row.get("player_id")).intValue();
            HistoryRow sourceRow = expected.get(playerId);
            assertThat(sourceRow).as("source history exists for stored PFS player %d", playerId).isNotNull();
            assertThat(((Number) row.get("gameweek")).intValue()).isEqualTo(sourceRow.gameweek());
            assertThat(((Number) row.get("player_team_id")).intValue()).isEqualTo(sourceRow.playerTeamId());
            assertThat(((Number) row.get("opponent_team_id")).intValue()).isEqualTo(sourceRow.opponentTeamId());
            assertThat(row.get("was_home")).isEqualTo(sourceRow.wasHome());
            assertThat(((Timestamp) row.get("kickoff_time")).toInstant()).isEqualTo(sourceRow.kickoffTime());
            assertThat(((Number) row.get("minutes")).intValue()).isEqualTo((int) sourceRow.minutes());
            assertThat(((Number) row.get("total_points")).intValue()).isEqualTo((int) sourceRow.totalPoints());
            assertThat(((Number) row.get("bonus")).intValue()).isEqualTo((int) sourceRow.bonus());
            assertThat(((Number) row.get("bps")).intValue()).isEqualTo((int) sourceRow.bps());
            assertThat(((Number) row.get("goals_scored")).intValue()).isEqualTo((int) sourceRow.goalsScored());
            assertThat(((Number) row.get("assists")).intValue()).isEqualTo((int) sourceRow.assists());
            assertThat(((Number) row.get("clean_sheets")).intValue()).isEqualTo((int) sourceRow.cleanSheets());
            assertThat(((Number) row.get("goals_conceded")).intValue()).isEqualTo((int) sourceRow.goalsConceded());
            assertThat(((Number) row.get("own_goals")).intValue()).isEqualTo((int) sourceRow.ownGoals());
            assertThat(((Number) row.get("penalties_saved")).intValue()).isEqualTo((int) sourceRow.penaltiesSaved());
            assertThat(((Number) row.get("penalties_missed")).intValue()).isEqualTo((int) sourceRow.penaltiesMissed());
            assertThat(((Number) row.get("saves")).intValue()).isEqualTo((int) sourceRow.saves());
            assertThat(((Number) row.get("yellow_cards")).intValue()).isEqualTo((int) sourceRow.yellowCards());
            assertThat(((Number) row.get("red_cards")).intValue()).isEqualTo((int) sourceRow.redCards());
            assertThat(((Number) row.get("value")).intValue()).isEqualTo(sourceRow.value());
            assertThat(((Number) row.get("selected")).intValue()).isEqualTo(sourceRow.selected());
            assertThat(((Number) row.get("transfers_in")).intValue()).isEqualTo(sourceRow.transfersIn());
            assertThat(((Number) row.get("transfers_out")).intValue()).isEqualTo(sourceRow.transfersOut());
            assertThat(((Number) row.get("starts")).intValue()).isEqualTo((int) sourceRow.starts());
            assertOptionalMetric(row, "expected_goals", sourceRow.expectedGoals(),
                    sourceRow.has(HistoryParser.Component.EXPECTED_GOALS));
            assertOptionalMetric(row, "expected_assists", sourceRow.expectedAssists(),
                    sourceRow.has(HistoryParser.Component.EXPECTED_ASSISTS));
            assertOptionalMetric(row, "expected_goal_involvements", sourceRow.expectedGoalInvolvements(),
                    sourceRow.has(HistoryParser.Component.EXPECTED_GOAL_INVOLVEMENTS));
            assertOptionalMetric(row, "expected_goals_conceded", sourceRow.expectedGoalsConceded(),
                    sourceRow.has(HistoryParser.Component.EXPECTED_GOALS_CONCEDED));
            assertOptionalMetric(row, "influence", sourceRow.influence(),
                    sourceRow.has(HistoryParser.Component.INFLUENCE));
            assertOptionalMetric(row, "creativity", sourceRow.creativity(),
                    sourceRow.has(HistoryParser.Component.CREATIVITY));
            assertOptionalMetric(row, "threat", sourceRow.threat(),
                    sourceRow.has(HistoryParser.Component.THREAT));
            assertOptionalMetric(row, "ict_index", sourceRow.ictIndex(),
                    sourceRow.has(HistoryParser.Component.ICT_INDEX));
            assertOptionalMetric(row, "clearances_blocks_interceptions", sourceRow.clearancesBlocksInterceptions(),
                    sourceRow.has(HistoryParser.Component.CLEARANCES_BLOCKS_INTERCEPTIONS));
            assertOptionalMetric(row, "recoveries", sourceRow.recoveries(),
                    sourceRow.has(HistoryParser.Component.RECOVERIES));
            assertOptionalMetric(row, "tackles", sourceRow.tackles(),
                    sourceRow.has(HistoryParser.Component.TACKLES));
            assertOptionalMetric(row, "defensive_contribution", sourceRow.defensiveContribution(),
                    sourceRow.has(HistoryParser.Component.DEFENSIVE_CONTRIBUTION));
        }
        List<Map<String, Object>> evidence = jdbc.queryForList("SELECT player_id,generation,component_presence "
                + "FROM " + TABLES.playerFixtureEvidence() + " WHERE season_id=? AND fixture_id=? AND generation=? "
                + "ORDER BY player_id", SEASON.id(), fixtureId, generation);
        assertThat(evidence).as("current source evidence rows for fixture %d", fixtureId).hasSize(expected.size());
        for (Map<String, Object> row : evidence) {
            int playerId = ((Number) row.get("player_id")).intValue();
            assertThat(row.get("generation")).isEqualTo(generation);
            assertThat(((Number) row.get("component_presence")).longValue())
                    .isEqualTo(expected.get(playerId).presenceMask());
        }
    }

    private static void assertOptionalMetric(Map<String, Object> row, String field,
                                             Object expected, boolean present) {
        if (!present) return;
        Object actual = row.get(field);
        if (expected instanceof BigDecimal decimal) {
            assertThat((BigDecimal) actual).as("validated source metric %s", field)
                    .isEqualByComparingTo(decimal);
        } else {
            assertThat(((Number) actual).intValue()).as("validated source metric %s", field)
                    .isEqualTo(((Number) expected).intValue());
        }
    }

    private static void assertPfsMetricRowsUnchanged(List<String> before, List<String> after) throws IOException {
        List<String> normalizedBefore = before.stream().map(OwnedMatchdayAdmissionProgressIntegrationTest::withoutPfsTimestamp)
                .sorted().toList();
        List<String> normalizedAfter = after.stream().map(OwnedMatchdayAdmissionProgressIntegrationTest::withoutPfsTimestamp)
                .sorted().toList();
        assertThat(normalizedAfter).as("a score-only source correction does not invent player-stat changes")
                .isEqualTo(normalizedBefore);
    }

    private static String withoutPfsTimestamp(String row) {
        try {
            ObjectNode object = (ObjectNode) JSON.readTree(row);
            object.remove("updated_at");
            return JSON.writeValueAsString(object);
        } catch (IOException invalid) {
            throw new IllegalArgumentException("invalid stored PFS JSON", invalid);
        }
    }




    private static boolean firstPendingIdentityMatches(long capturedGeneration, long sourceGeneration,
                                                        int capturedSeasonId, String capturedSeasonCode,
                                                        Long currentVerifiedGeneration, int observationFixtureId,
                                                        int observationSeasonId, String observationSeasonCode,
                                                        boolean coherent, boolean finished, String reasons) {
        return capturedGeneration > 0 && capturedGeneration == sourceGeneration
                && capturedSeasonId == SEASON.id() && SEASON.code().equals(capturedSeasonCode)
                && currentVerifiedGeneration == null && observationFixtureId > 0
                && observationSeasonId == SEASON.id() && SEASON.code().equals(observationSeasonCode)
                && coherent && finished && reasons.contains("NEEDS_SECOND_OBSERVATION");
    }




    private static CanonicalParentProof currentCanonicalParentProof(Harness harness) {
        String sql = "SELECT p.publication_key,p.latest_generation,p.published_at,"
                + "p.source_read_started_at receipt_started,p.source_read_finished_at receipt_finished,"
                + "p.source_pair_fingerprint,p.source_bootstrap_payload_id,p.source_fixtures_payload_id,"
                + "p.source_bootstrap_read_started_at,p.source_bootstrap_read_finished_at,"
                + "p.source_fixtures_read_started_at,p.source_fixtures_read_finished_at,"
                + "p.source_bootstrap_sha256,p.source_fixtures_sha256,"
                + "r.generation publication_generation,r.kind publication_kind,r.coalescing_key publication_coalescing,"
                + "r.publication_key publication_key,r.state publication_state,r.runtime_mode publication_mode,"
                + "r.runtime_epoch publication_epoch,r.completed_at,r.source_read_started_at run_started,"
                + "r.source_read_finished_at run_finished,"
                + "b.id b_id,b.run_generation b_generation,b.endpoint_kind b_role,b.event_id b_event,"
                + "b.player_id b_player,b.validation_state b_validation,b.source_published b_published,"
                + "b.read_started_at b_started,b.read_finished_at b_finished,b.body AS b_body,"
                + "f.id f_id,f.run_generation f_generation,f.endpoint_kind f_role,f.event_id f_event,"
                + "f.player_id f_player,f.validation_state f_validation,f.source_published f_published,"
                + "f.read_started_at f_started,f.read_finished_at f_finished,f.body AS f_body,"
                + "sr.generation source_run_generation,sr.kind source_run_kind,sr.season source_run_season,"
                + "sr.coalescing_key source_coalescing,sr.publication_key source_publication,"
                + "sr.runtime_mode source_mode,sr.runtime_epoch source_epoch "
                + "FROM " + TABLES.receipt() + " p JOIN " + TABLES.run() + " r"
                + " ON r.generation=p.latest_generation AND r.season=p.season"
                + " JOIN " + TABLES.payload() + " b ON b.id=p.source_bootstrap_payload_id"
                + " AND b.endpoint_kind='BOOTSTRAP_STATIC' AND b.event_id IS NULL AND b.player_id IS NULL"
                + " AND b.validation_state='VALID' AND b.source_published IS TRUE"
                + " AND b.read_started_at=p.source_bootstrap_read_started_at"
                + " AND b.read_finished_at=p.source_bootstrap_read_finished_at"
                + " JOIN " + TABLES.payload() + " f ON f.id=p.source_fixtures_payload_id"
                + " AND f.endpoint_kind='FIXTURES' AND f.event_id IS NULL AND f.player_id IS NULL"
                + " AND f.validation_state='VALID' AND f.source_published IS TRUE"
                + " AND f.read_started_at=p.source_fixtures_read_started_at"
                + " AND f.read_finished_at=p.source_fixtures_read_finished_at"
                + " JOIN " + TABLES.run() + " sr ON sr.generation=b.run_generation"
                + " AND sr.season=p.season AND sr.kind='CATALOGUE_SCHEDULE'"
                + " AND sr.runtime_mode='ACTIVE' AND sr.runtime_epoch=r.runtime_epoch"
                + " AND ((sr.coalescing_key=? AND sr.publication_key=?)"
                + " OR (sr.coalescing_key=? AND sr.publication_key=?))"
                + " WHERE p.season=? AND p.publication_key IN (?,?)"
                + " AND r.kind='CATALOGUE_SCHEDULE' AND r.runtime_mode='ACTIVE' AND r.runtime_epoch=?"
                + " AND r.state='COMPLETED' AND r.completed_at<=p.published_at AND p.published_at<=?"
                + " AND r.source_read_started_at=p.source_read_started_at"
                + " AND r.source_read_finished_at=p.source_read_finished_at"
                + " AND r.source_read_started_at=LEAST(b.read_started_at,f.read_started_at)"
                + " AND r.source_read_finished_at=GREATEST(b.read_finished_at,f.read_finished_at)"
                + " AND b.run_generation=f.run_generation AND b.body IS NOT NULL AND f.body IS NOT NULL"
                + " AND p.source_bootstrap_sha256 IS NOT NULL AND p.source_fixtures_sha256 IS NOT NULL"
                + " AND p.source_pair_fingerprint IS NOT NULL"
                + " AND b.read_started_at<=b.read_finished_at AND f.read_started_at<=f.read_finished_at"
                + " AND ((p.publication_key=? AND r.coalescing_key=? AND r.publication_key=?)"
                + " OR (p.publication_key=? AND r.coalescing_key=? AND r.publication_key=?))"
                + " AND (SELECT max(d.requested_source_after) FROM " + TABLES.run() + " d"
                + " WHERE d.kind='CATALOGUE_SCHEDULE' AND d.season=r.season"
                + " AND d.runtime_mode=r.runtime_mode AND d.runtime_epoch=r.runtime_epoch"
                + " AND d.coalescing_key=r.coalescing_key AND d.publication_key=r.publication_key)"
                + " <= b.read_started_at"
                + " AND (SELECT max(d.requested_source_after) FROM " + TABLES.run() + " d"
                + " WHERE d.kind='CATALOGUE_SCHEDULE' AND d.season=r.season"
                + " AND d.runtime_mode=r.runtime_mode AND d.runtime_epoch=r.runtime_epoch"
                + " AND d.coalescing_key=r.coalescing_key AND d.publication_key=r.publication_key)"
                + " <= f.read_started_at"
                + " AND (SELECT max(d.requested_source_after) FROM " + TABLES.run() + " d"
                + " WHERE d.kind='CATALOGUE_SCHEDULE' AND d.season=sr.season"
                + " AND d.runtime_mode=sr.runtime_mode AND d.runtime_epoch=sr.runtime_epoch"
                + " AND d.coalescing_key=sr.coalescing_key AND d.publication_key=sr.publication_key)"
                + " <= b.read_started_at"
                + " AND (SELECT max(d.requested_source_after) FROM " + TABLES.run() + " d"
                + " WHERE d.kind='CATALOGUE_SCHEDULE' AND d.season=sr.season"
                + " AND d.runtime_mode=sr.runtime_mode AND d.runtime_epoch=sr.runtime_epoch"
                + " AND d.coalescing_key=sr.coalescing_key AND d.publication_key=sr.publication_key)"
                + " <= f.read_started_at"
                + " ORDER BY GREATEST(b.read_finished_at,f.read_finished_at) DESC,"
                + "p.published_at DESC,r.generation DESC";
        List<CanonicalParentProof> rows = jdbc.query(sql, (rs, index) -> {
            String publicationKey = rs.getString("publication_key");
            long publicationGeneration = rs.getLong("publication_generation");
            long sourceGeneration = rs.getLong("b_generation");
            long bootstrapId = rs.getLong("b_id");
            long fixturesId = rs.getLong("f_id");
            Timestamp publishedAtValue = rs.getTimestamp("published_at");
            Timestamp completedAtValue = rs.getTimestamp("completed_at");
            Timestamp runStartedValue = rs.getTimestamp("run_started");
            Timestamp runFinishedValue = rs.getTimestamp("run_finished");
            Timestamp receiptStartedValue = rs.getTimestamp("receipt_started");
            Timestamp receiptFinishedValue = rs.getTimestamp("receipt_finished");
            Timestamp bStartedValue = rs.getTimestamp("b_started");
            Timestamp bFinishedValue = rs.getTimestamp("b_finished");
            Timestamp fStartedValue = rs.getTimestamp("f_started");
            Timestamp fFinishedValue = rs.getTimestamp("f_finished");
            String bHash = rs.getString("source_bootstrap_sha256");
            String fHash = rs.getString("source_fixtures_sha256");
            String fingerprint = rs.getString("source_pair_fingerprint");
            byte[] bootstrapBody = rs.getBytes("b_body");
            byte[] fixturesBody = rs.getBytes("f_body");
            boolean publicationIdentity = "CATALOGUE_SCHEDULE".equals(rs.getString("publication_kind"))
                    && "ACTIVE".equals(rs.getString("publication_mode"))
                    && rs.getLong("publication_epoch") == harness.fence.epoch()
                    && (CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY.equals(publicationKey)
                    && CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY.equals(
                            rs.getString("publication_coalescing"))
                    || CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY.equals(publicationKey)
                    && CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY.equals(
                            rs.getString("publication_coalescing")));
            boolean sourceIdentity = rs.getLong("source_run_generation") == sourceGeneration
                    && "CATALOGUE_SCHEDULE".equals(rs.getString("source_run_kind"))
                    && SEASON.code().equals(rs.getString("source_run_season"))
                    && "ACTIVE".equals(rs.getString("source_mode"))
                    && rs.getLong("source_epoch") == harness.fence.epoch()
                    && (CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY.equals(
                            rs.getString("source_coalescing"))
                    && CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY.equals(
                            rs.getString("source_publication"))
                    || CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY.equals(
                            rs.getString("source_coalescing"))
                    && CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY.equals(
                            rs.getString("source_publication")));
            boolean payloadIdentity = bootstrapId > 0 && fixturesId > 0
                    && sourceGeneration > 0 && sourceGeneration == rs.getLong("f_generation")
                    && "BOOTSTRAP_STATIC".equals(rs.getString("b_role"))
                    && rs.getObject("b_event") == null && rs.getObject("b_player") == null
                    && "FIXTURES".equals(rs.getString("f_role"))
                    && rs.getObject("f_event") == null && rs.getObject("f_player") == null
                    && "VALID".equals(rs.getString("b_validation"))
                    && "VALID".equals(rs.getString("f_validation"))
                    && rs.getBoolean("b_published") && rs.getBoolean("f_published")
                    && bStartedValue != null && bFinishedValue != null
                    && fStartedValue != null && fFinishedValue != null
                    && bStartedValue.equals(rs.getTimestamp("source_bootstrap_read_started_at"))
                    && bFinishedValue.equals(rs.getTimestamp("source_bootstrap_read_finished_at"))
                    && fStartedValue.equals(rs.getTimestamp("source_fixtures_read_started_at"))
                    && fFinishedValue.equals(rs.getTimestamp("source_fixtures_read_finished_at"));
            boolean sourceInterval = canonicalSourceSpanProof(runStartedValue, runFinishedValue,
                    receiptStartedValue, receiptFinishedValue, completedAtValue, publishedAtValue,
                    bStartedValue, bFinishedValue, fStartedValue, fFinishedValue, harness.clock.instant());
            boolean currentPublished = rs.getLong("latest_generation") == publicationGeneration
                    && "COMPLETED".equals(rs.getString("publication_state"))
                    && publicationIdentity && sourceIdentity && payloadIdentity && sourceInterval
                    && fingerprint != null && bHash != null && fHash != null;
            boolean immutable = payloadIdentity && bootstrapBody != null && fixturesBody != null
                    && bHash != null && fHash != null
                    && bHash.equals(OwnedSyncStore.sha256(bootstrapBody))
                    && fHash.equals(OwnedSyncStore.sha256(fixturesBody));
            if (immutable) {
                try {
                    var pair = OwnedSyncStore.CanonicalSourcePair.of(
                            new OwnedSyncStore.CanonicalSourcePayload(bootstrapId, sourceGeneration,
                                    EndpointKind.BOOTSTRAP_STATIC,
                                    new SourceRead(bStartedValue.toInstant(), bFinishedValue.toInstant()), bHash),
                            new OwnedSyncStore.CanonicalSourcePayload(fixturesId, sourceGeneration,
                                    EndpointKind.FIXTURES,
                                    new SourceRead(fStartedValue.toInstant(), fFinishedValue.toInstant()), fHash));
                    immutable = pair.fingerprint().equals(fingerprint);
                } catch (RuntimeException malformed) {
                    immutable = false;
                }
            }
            Map<String, Object> trace = new TreeMap<>();
            trace.put("canonicalLane", publicationKey);
            trace.put("publicationGeneration", publicationGeneration);
            trace.put("publisherKeys", java.util.Arrays.asList(rs.getString("publication_coalescing"),
                    rs.getString("publication_key")));
            trace.put("sourceGeneration", sourceGeneration);
            trace.put("sourceKeys", java.util.Arrays.asList(rs.getString("source_coalescing"),
                    rs.getString("source_publication")));
            trace.put("currentEpoch", publicationIdentity && sourceIdentity);
            trace.put("sourcePairIds", List.of(bootstrapId, fixturesId));
            trace.put("sourcePairFingerprint", fingerprint);
            trace.put("sourceIntervals", java.util.Arrays.asList(bStartedValue, bFinishedValue,
                    fStartedValue, fFinishedValue));
            trace.put("receiptInterval", java.util.Arrays.asList(receiptStartedValue,
                    receiptFinishedValue, completedAtValue, publishedAtValue));
            trace.put("diagnosticPayloadQueries", 1);
            trace.put("diagnosticPayloadRawBytes", (bootstrapBody == null ? 0 : bootstrapBody.length)
                    + (fixturesBody == null ? 0 : fixturesBody.length));
            trace.put("bodyHashMatches", immutable);
            trace.put("currentPublished", currentPublished);
            trace.put("immutable", immutable);
            return new CanonicalParentProof(publicationKey, publicationGeneration, sourceGeneration,
                    bootstrapId, fixturesId, fingerprint, currentPublished, immutable,
                    java.util.Collections.unmodifiableMap(trace));
        }, CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY,
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY,
                SEASON.code(), CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY, harness.fence.epoch(),
                Timestamp.from(harness.clock.instant()),
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY,
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY,
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY);
        return rows.stream().filter(CanonicalParentProof::currentPublished)
                .filter(CanonicalParentProof::immutable).findFirst().orElseGet(CanonicalParentProof::missing);
    }

    private static boolean canonicalSourceSpanProof(Timestamp runStarted, Timestamp runFinished,
                                                     Timestamp receiptStarted, Timestamp receiptFinished,
                                                     Timestamp completedAt, Timestamp publishedAt,
                                                     Timestamp bootstrapStarted, Timestamp bootstrapFinished,
                                                     Timestamp fixturesStarted, Timestamp fixturesFinished,
                                                     Instant asOf) {
        if (bootstrapStarted == null || bootstrapFinished == null || fixturesStarted == null
                || fixturesFinished == null || runStarted == null || runFinished == null) return false;
        Timestamp expectedStarted = earlier(bootstrapStarted, fixturesStarted);
        Timestamp expectedFinished = later(bootstrapFinished, fixturesFinished);
        return !bootstrapStarted.after(bootstrapFinished) && !fixturesStarted.after(fixturesFinished)
                && runStarted.equals(expectedStarted) && runFinished.equals(expectedFinished)
                && receiptIntervalProof(runStarted, runFinished, receiptStarted, receiptFinished,
                        completedAt, publishedAt, asOf)
                && databaseMicroseconds(bootstrapStarted, bootstrapFinished, fixturesStarted, fixturesFinished);
    }

    private static Timestamp earlier(Timestamp first, Timestamp second) {
        if (first == null || second == null) return null;
        return first.before(second) ? first : second;
    }

    private static Timestamp later(Timestamp first, Timestamp second) {
        if (first == null || second == null) return null;
        return first.after(second) ? first : second;
    }

    private static boolean receiptIntervalProof(Timestamp sourceStarted, Timestamp sourceFinished,
                                               Timestamp receiptStarted, Timestamp receiptFinished,
                                               Timestamp completedAt, Timestamp publishedAt, Instant asOf) {
        if (!databaseMicroseconds(sourceStarted, sourceFinished, receiptStarted, receiptFinished,
                completedAt, publishedAt)) return false;
        return !sourceStarted.after(sourceFinished) && sourceStarted.equals(receiptStarted)
                && sourceFinished.equals(receiptFinished) && !sourceFinished.after(completedAt)
                && !completedAt.after(publishedAt) && !publishedAt.toInstant().isAfter(asOf);
    }

    private static boolean databaseMicroseconds(Timestamp... values) {
        for (Timestamp value : values) {
            if (value == null || value.getNanos() % 1_000 != 0) return false;
        }
        return true;
    }

    private static long numberLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static int fixtureId(String publicationKey) {
        String prefix = "fixture:";
        if (publicationKey == null || !publicationKey.startsWith(prefix)) return -1;
        return Integer.parseInt(publicationKey.substring(prefix.length()));
    }



    private static boolean sourceObservationProof(Map<String, Boolean> checks) {
        return List.of("generationRolesMatch", "epochCurrent", "seasonCurrent", "keyCurrent", "runCompleted",
                "sourceFloorWanted", "readIdentity", "anchorValidation", "bodyPresent", "timeOrder",
                "eventHeader", "membershipKnown", "requiredIdsMatch", "coherent", "participantsMatch",
                "observationIntervalMatches", "sourceIntervals", "timestampPrecision", "historyPayloadProof")
                .stream().allMatch(key -> Boolean.TRUE.equals(checks.get(key)));
    }

    private static void assertSourceObservationProofNegativeCases() {
        List<String> checks = List.of("generationRolesMatch", "epochCurrent", "seasonCurrent", "keyCurrent",
                "runCompleted", "sourceFloorWanted", "readIdentity", "anchorValidation", "bodyPresent",
                "timeOrder", "eventHeader", "membershipKnown", "requiredIdsMatch", "coherent",
                "participantsMatch", "observationIntervalMatches", "sourceIntervals", "timestampPrecision",
                "historyPayloadProof");
        Map<String, Boolean> valid = new TreeMap<>();
        checks.forEach(key -> valid.put(key, true));
        assertThat(sourceObservationProof(valid)).isTrue();
        assertThat(firstPendingIdentityMatches(42, 42, SEASON.id(), SEASON.code(), null, 41,
                SEASON.id(), SEASON.code(), true, true, "{NEEDS_SECOND_OBSERVATION}")).isTrue();
        assertThat(firstPendingIdentityMatches(42, 43, SEASON.id(), SEASON.code(), null, 41,
                SEASON.id(), SEASON.code(), true, true, "{NEEDS_SECOND_OBSERVATION}")).isFalse();
        assertThat(firstPendingIdentityMatches(42, 42, SEASON.id() + 1, SEASON.code(), null, 41,
                SEASON.id(), SEASON.code(), true, true, "{NEEDS_SECOND_OBSERVATION}")).isFalse();
        assertThat(firstPendingIdentityMatches(42, 42, SEASON.id(), SEASON.code(), 42L, 41,
                SEASON.id(), SEASON.code(), true, true, "{NEEDS_SECOND_OBSERVATION}"))
                .as("first pending evidence must be captured before any current receipt").isFalse();
        Instant pairFloor = Instant.parse("2026-09-30T12:00:00Z");
        SourceRead pairFirstInterval = new SourceRead(pairFloor, pairFloor.plus(Duration.ofMinutes(1)));
        SourceRead pairSecondInterval = new SourceRead(pairFloor.plus(Duration.ofMinutes(11)),
                pairFloor.plus(Duration.ofMinutes(12)));
        FixtureSourceBatchProof pairFirst = new FixtureSourceBatchProof(42, pairFirstInterval, pairFloor,
                true, new TreeMap<String, Object>(valid));
        FixtureSourceBatchProof pairSecond = new FixtureSourceBatchProof(43, pairSecondInterval,
                pairSecondInterval.startedAt(), true, new TreeMap<String, Object>(valid));
        assertThat(correctionBatchesValid(pairFirstInterval, pairSecondInterval, pairFloor)).isTrue();
        assertThat(new FixtureSourcePairProof(pairFirst, pairSecond, false, true, true, true).valid())
                .as("a different captured generation cannot substitute for the first pair").isFalse();
        assertThat(new FixtureSourcePairProof(pairFirst, pairSecond, true, true, true, false).valid())
                .as("the pair must belong to ACTIVE epoch 3").isFalse();
        for (String failedCheck : List.of("generationRolesMatch", "epochCurrent", "seasonCurrent",
                "readIdentity", "anchorValidation", "bodyPresent", "sourceFloorWanted", "requiredIdsMatch",
                "participantsMatch", "historyPayloadProof")) {
            Map<String, Boolean> invalid = new TreeMap<>(valid);
            invalid.put(failedCheck, false);
            assertThat(sourceObservationProof(invalid))
                    .as("first pending source proof rejects %s", failedCheck).isFalse();
            Instant floor = Instant.parse("2026-09-30T12:00:00Z");
            SourceRead firstInterval = new SourceRead(floor, floor.plus(Duration.ofMinutes(1)));
            SourceRead secondInterval = new SourceRead(floor.plus(Duration.ofMinutes(11)),
                    floor.plus(Duration.ofMinutes(12)));
            assertThat(correctionBatchesValid(firstInterval, secondInterval, floor)).isTrue();
            FixtureSourceBatchProof invalidFirst = new FixtureSourceBatchProof(1, firstInterval, floor,
                    sourceObservationProof(invalid), new TreeMap<String, Object>(invalid));
            FixtureSourceBatchProof validSecond = new FixtureSourceBatchProof(2, secondInterval,
                    secondInterval.startedAt(), true, new TreeMap<String, Object>(valid));
            FixtureSourcePairProof pair = new FixtureSourcePairProof(invalidFirst, validSecond,
                    true, true, true, true);
            assertThat(pair.valid()).as("two valid intervals cannot excuse first-proof %s", failedCheck).isFalse();
        }
        Map<String, Boolean> incomplete = new TreeMap<>(valid);
        incomplete.remove("historyPayloadProof");
        assertThat(sourceObservationProof(incomplete))
                .as("missing source proof evidence cannot pass by omission").isFalse();
    }

    private static Timestamp rTimestamp(Map<String, Object> row, String key) {
        return (Timestamp) row.get(key);
    }


    private static boolean fullSourceIntervalProof(long generation, long expectedGeneration, SourceRead runInterval,
                                                   Instant completedAt, List<SourceRead> allSourceReads,
                                                   List<SourceRead> fixtureSourceReads,
                                                   SourceRead expectedFixtureInterval) {
        if (completedAt == null || allSourceReads.isEmpty() || fixtureSourceReads.isEmpty()) return false;
        Instant expectedStarted = allSourceReads.stream().map(SourceRead::startedAt)
                .min(Instant::compareTo).orElseThrow();
        Instant expectedFinished = allSourceReads.stream().map(SourceRead::finishedAt)
                .max(Instant::compareTo).orElseThrow();
        Instant fixtureStarted = fixtureSourceReads.stream().map(SourceRead::startedAt)
                .min(Instant::compareTo).orElseThrow();
        Instant fixtureFinished = fixtureSourceReads.stream().map(SourceRead::finishedAt)
                .max(Instant::compareTo).orElseThrow();
        return generation == expectedGeneration
                && allSourceReads.stream().allMatch(read -> !read.startedAt().isAfter(read.finishedAt()))
                && runInterval.startedAt().equals(expectedStarted) && runInterval.finishedAt().equals(expectedFinished)
                && expectedFixtureInterval.startedAt().equals(fixtureStarted)
                && expectedFixtureInterval.finishedAt().equals(fixtureFinished)
                && !expectedFixtureInterval.startedAt().isBefore(runInterval.startedAt())
                && !expectedFixtureInterval.finishedAt().isAfter(runInterval.finishedAt())
                && !runInterval.startedAt().isAfter(runInterval.finishedAt())
                && !runInterval.finishedAt().isAfter(completedAt)
                && java.util.stream.Stream.concat(allSourceReads.stream(), fixtureSourceReads.stream())
                        .allMatch(read -> isDatabaseMicrosecond(read.startedAt())
                                && isDatabaseMicrosecond(read.finishedAt()))
                && isDatabaseMicrosecond(runInterval.startedAt()) && isDatabaseMicrosecond(runInterval.finishedAt())
                && isDatabaseMicrosecond(completedAt) && isDatabaseMicrosecond(expectedFixtureInterval.startedAt())
                && isDatabaseMicrosecond(expectedFixtureInterval.finishedAt());
    }



    private static Map<String, Object> legacyFixtureManifestPredicateTrace(Harness harness, int fixtureId,
                                                                            long generation, FixtureOracle oracle) {
        Map<String, Object> row = jdbc.queryForMap("SELECT r.generation,r.kind,r.season,r.coalescing_key,"
                + "r.publication_key,r.runtime_mode,r.runtime_epoch,r.state,r.requested_source_after,"
                + "r.source_read_started_at run_started,r.source_read_finished_at run_finished,r.completed_at,"
                + "m.run_generation manifest_generation,m.event_id,m.source_floor,"
                + "b.id b_id,b.run_generation b_generation,b.endpoint_kind b_role,b.event_id b_event,"
                + "b.player_id b_player,b.validation_state b_validation,b.body IS NOT NULL b_body,"
                + "b.read_started_at b_started,b.read_finished_at b_finished,"
                + "f.id f_id,f.run_generation f_generation,f.endpoint_kind f_role,f.event_id f_event,"
                + "f.player_id f_player,f.validation_state f_validation,f.body IS NOT NULL f_body,"
                + "f.read_started_at f_started,f.read_finished_at f_finished,"
                + "l.id l_id,l.run_generation l_generation,l.endpoint_kind l_role,l.event_id l_event,"
                + "l.player_id l_player,l.validation_state l_validation,l.body IS NOT NULL l_body,"
                + "l.read_started_at l_started,l.read_finished_at l_finished,"
                + "p.latest_generation receipt_latest,fs.season_id,fs.season_code,fs.state fixture_state,"
                + "fs.current_verified_generation,fs.observation_generation,fs.observation->>'coherent' coherent,"
                + "fs.verified_header,fs.verified_stats_fingerprint,fs.observation->>'fingerprint' observation_fp,"
                + "fs.finished_parent_generation,fs.finished_parent_publication_generation,"
                + "fs.finished_parent_bootstrap_payload_id,fs.finished_parent_fixtures_payload_id,"
                + "fs.finished_parent_source_fingerprint,fs.finished_parent_fixture_fingerprint,"
                + "fs.finished_parent_finished,fs.expected_participants,fs.received_participants,"
                + "cardinality(fs.missing_player_ids) missing_count,fx.gameweek fixture_event,"
                + "fpl_sync_required_ids_valid(m.required_player_ids) membership_known,"
                + "(SELECT min(e.source_read_started_at) FROM " + TABLES.playerFixtureEvidence()
                + " e WHERE e.season_id=fs.season_id AND e.fixture_id=fs.fixture_id AND e.generation=r.generation)"
                + " evidence_started,(SELECT max(e.source_read_finished_at) FROM "
                + TABLES.playerFixtureEvidence() + " e WHERE e.season_id=fs.season_id AND e.fixture_id=fs.fixture_id"
                + " AND e.generation=r.generation) evidence_finished,"
                + "GREATEST(r.requested_source_after,m.source_floor,f.read_finished_at + INTERVAL '1 millisecond')"
                + " effective_floor FROM " + TABLES.run() + " r LEFT JOIN "
                + TABLES.fixtureDependencyManifest() + " m ON m.run_generation=r.generation LEFT JOIN "
                + TABLES.payload() + " b ON b.id=m.bootstrap_payload_id LEFT JOIN " + TABLES.payload()
                + " f ON f.id=m.fixtures_payload_id LEFT JOIN " + TABLES.payload()
                + " l ON l.id=m.live_payload_id JOIN " + TABLES.receipt()
                + " p ON p.season=r.season AND p.publication_key=r.publication_key"
                + " AND p.latest_generation=r.generation JOIN " + TABLES.fixtureSync()
                + " fs ON fs.season_id=? AND fs.fixture_id=? AND fs.current_verified_generation=r.generation"
                + " JOIN fixture fx ON fx.season_id=fs.season_id AND fx.id=fs.fixture_id"
                + " WHERE r.generation=?", SEASON.id(), fixtureId, generation);
        List<Integer> required = List.of((Integer[]) jdbc.queryForObject("SELECT required_player_ids FROM "
                + TABLES.fixtureDependencyManifest() + " WHERE run_generation=?",
                (rs, index) -> (Integer[]) rs.getArray(1).getArray(), generation));
        boolean requiredIdsMatch = new java.util.TreeSet<>(required)
                .equals(new java.util.TreeSet<>(oracle.requiredPlayers()));
        List<Map<String, Object>> parents = jdbc.queryForList("SELECT p.publication_key,p.latest_generation,"
                + "b.run_generation source_generation,p.source_bootstrap_payload_id,"
                + "p.source_fixtures_payload_id,p.source_pair_fingerprint,p.source_bootstrap_sha256,"
                + "p.source_fixtures_sha256,p.published_at,r.state,r.runtime_mode,r.runtime_epoch,"
                + "b.validation_state b_validation,f.validation_state f_validation,"
                + "b.source_published b_published,f.source_published f_published "
                + "FROM " + TABLES.receipt() + " p JOIN " + TABLES.run()
                + " r ON r.generation=p.latest_generation JOIN " + TABLES.payload()
                + " b ON b.id=p.source_bootstrap_payload_id JOIN " + TABLES.payload()
                + " f ON f.id=p.source_fixtures_payload_id WHERE p.season=? AND p.publication_key IN (?,?)"
                + " AND p.published_at<=? ORDER BY p.published_at DESC,p.latest_generation DESC LIMIT 1",
                SEASON.code(), CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY, Timestamp.from(harness.clock.instant()));
        Map<String, Object> parent = parents.isEmpty() ? Map.of() : parents.getFirst();
        boolean parentMarkerMatches = !parent.isEmpty()
                && java.util.Objects.equals(row.get("finished_parent_generation"), parent.get("source_generation"))
                && java.util.Objects.equals(row.get("finished_parent_publication_generation"),
                        parent.get("latest_generation"))
                && java.util.Objects.equals(row.get("finished_parent_bootstrap_payload_id"),
                        parent.get("source_bootstrap_payload_id"))
                && java.util.Objects.equals(row.get("finished_parent_fixtures_payload_id"),
                        parent.get("source_fixtures_payload_id"))
                && java.util.Objects.equals(row.get("finished_parent_source_fingerprint"),
                        parent.get("source_pair_fingerprint"))
                && Boolean.TRUE.equals(row.get("finished_parent_finished"));
        List<Map<String, Object>> scheduleParents = jdbc.queryForList("SELECT p.latest_generation,"
                + "b.run_generation source_generation,p.source_bootstrap_payload_id,p.source_fixtures_payload_id,"
                + "p.source_pair_fingerprint FROM " + TABLES.receipt() + " p JOIN " + TABLES.payload()
                + " b ON b.id=p.source_bootstrap_payload_id WHERE p.season=? AND p.publication_key=?",
                SEASON.code(), CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY);
        Map<String, Object> scheduleParent = scheduleParents.isEmpty() ? Map.of() : scheduleParents.getFirst();
        boolean scheduleOnlyMarkerMatches = !scheduleParent.isEmpty()
                && java.util.Objects.equals(row.get("finished_parent_generation"),
                        scheduleParent.get("source_generation"))
                && java.util.Objects.equals(row.get("finished_parent_publication_generation"),
                        scheduleParent.get("latest_generation"))
                && java.util.Objects.equals(row.get("finished_parent_bootstrap_payload_id"),
                        scheduleParent.get("source_bootstrap_payload_id"))
                && java.util.Objects.equals(row.get("finished_parent_fixtures_payload_id"),
                        scheduleParent.get("source_fixtures_payload_id"))
                && java.util.Objects.equals(row.get("finished_parent_source_fingerprint"),
                        scheduleParent.get("source_pair_fingerprint"));
        boolean sourceReceiptHashesMatch = !parent.isEmpty()
                && OwnedSyncStore.sha256(harness.source.bootstrapBytes()).equals(parent.get("source_bootstrap_sha256"))
                && OwnedSyncStore.sha256(harness.source.fixtureBytes()).equals(parent.get("source_fixtures_sha256"));
        var actualParent = harness.store.currentCanonicalParentEvidence(SEASON.code(), fixtureId,
                harness.clock.instant());

        Map<String, Object> result = new TreeMap<>();
        result.put("generation", generation);
        result.put("runState", row.get("state"));
        result.put("fixtureState", row.get("fixture_state"));
        result.put("anchorValidation", "VALID".equals(row.get("b_validation"))
                && "VALID".equals(row.get("f_validation")) && "VALID".equals(row.get("l_validation")));
        result.put("bodyPresent", Boolean.TRUE.equals(row.get("b_body"))
                && Boolean.TRUE.equals(row.get("f_body")) && Boolean.TRUE.equals(row.get("l_body")));
        result.put("readIdentity", "BOOTSTRAP_STATIC".equals(row.get("b_role")) && row.get("b_event") == null
                && row.get("b_player") == null && "FIXTURES".equals(row.get("f_role"))
                && row.get("f_event") == null && row.get("f_player") == null
                && "EVENT_LIVE".equals(row.get("l_role")) && row.get("l_player") == null);
        result.put("generation", java.util.Objects.equals(row.get("generation"), row.get("manifest_generation"))
                && java.util.Objects.equals(row.get("generation"), row.get("b_generation"))
                && java.util.Objects.equals(row.get("generation"), row.get("f_generation"))
                && java.util.Objects.equals(row.get("generation"), row.get("l_generation"))
                && java.util.Objects.equals(row.get("generation"), row.get("receipt_latest"))
                && java.util.Objects.equals(row.get("generation"), row.get("current_verified_generation"))
                && java.util.Objects.equals(row.get("generation"), row.get("observation_generation")));
        result.put("epoch", "ACTIVE".equals(row.get("runtime_mode"))
                && ((Number) row.get("runtime_epoch")).longValue() == harness.fence.epoch());
        result.put("season", SEASON.code().equals(row.get("season"))
                && SEASON.code().equals(row.get("season_code"))
                && ((Number) row.get("season_id")).intValue() == SEASON.id());
        result.put("key", "FIXTURE_VERIFICATION".equals(row.get("kind"))
                && ("fixture:" + fixtureId).equals(row.get("coalescing_key"))
                && ("fixture:" + fixtureId).equals(row.get("publication_key")));
        result.put("eventHeader", java.util.Objects.equals(row.get("event_id"), oracle.gameweek())
                && java.util.Objects.equals(row.get("l_event"), oracle.gameweek())
                && java.util.Objects.equals(row.get("fixture_event"), oracle.gameweek())
                && oracle.headerFingerprint().equals(row.get("verified_header"))
                && oracle.statsFingerprint().equals(row.get("verified_stats_fingerprint"))
                && oracle.observationFingerprint().equals(row.get("observation_fp")));
        result.put("sourceFloor", java.util.Objects.equals(row.get("source_floor"),
                        row.get("requested_source_after"))
                && !((Timestamp) row.get("b_started")).before((Timestamp) row.get("source_floor"))
                && !((Timestamp) row.get("f_started")).before((Timestamp) row.get("source_floor"))
                && !((Timestamp) row.get("l_started")).before((Timestamp) row.get("source_floor")));
        result.put("latestWanted", java.util.Objects.equals(row.get("source_floor"),
                row.get("requested_source_after")));
        result.put("membershipKnown", Boolean.TRUE.equals(row.get("membership_known")));
        result.put("requiredIdsMatchSource", requiredIdsMatch);
        result.put("coherent", "true".equals(row.get("coherent")));
        result.put("fixtureReadEvidenceProof", fixtureReadEvidenceProof(harness, fixtureId, generation,
                Set.copyOf(required)));
        result.put("canonicalMatchesCurrent", parentMarkerMatches);
        result.put("canonicalLane", parent.get("publication_key"));
        result.put("scheduleOnlyMarkerMatches", scheduleOnlyMarkerMatches);
        result.put("receiptHash", sourceReceiptHashesMatch);
        result.put("actualRuleCurrentPublished", actualParent.currentPublished());
        result.put("actualRuleFixtureFinished", actualParent.fixtureFinished());
        result.put("participants", Map.of("expected", row.get("expected_participants"),
                "received", row.get("received_participants"), "missing", row.get("missing_count")));
        result.put("sourceTimes", Map.of("wanted", row.get("requested_source_after"),
                "manifestFloor", row.get("source_floor"), "effectiveFloor", row.get("effective_floor"),
                "bootstrap", List.of(row.get("b_started"), row.get("b_finished")),
                "fixtures", List.of(row.get("f_started"), row.get("f_finished")),
                "live", List.of(row.get("l_started"), row.get("l_finished")),
                "histories", List.of(row.get("evidence_started"), row.get("evidence_finished")),
                "fixtureRead", List.of(row.get("run_started"), row.get("run_finished")),
                "completed", row.get("completed_at")));
        result.put("manifestCount", required.size());
        return result;
    }

    private static boolean fixtureReadEvidenceProof(Harness harness, int fixtureId, long generation,
                                                    Set<Integer> requiredPlayers) {
        List<Map<String, Object>> observations = jdbc.queryForList("SELECT observation::text FROM "
                + TABLES.fixtureSync() + " WHERE season_id=? AND fixture_id=?"
                + " AND current_verified_generation=? AND observation_generation=?",
                SEASON.id(), fixtureId, generation, generation);
        if (observations.size() != 1) return false;
        FixtureVerification.Observation observation;
        try {
            observation = FixtureObservationCodec.decode((String) observations.getFirst().get("observation"));
        } catch (RuntimeException malformed) {
            return false;
        }
        return fixtureReadEvidenceProof(harness, fixtureId, generation, requiredPlayers, observation);
    }

    private static boolean fixtureReadEvidenceProof(Harness harness, int fixtureId, long generation,
                                                    Set<Integer> requiredPlayers,
                                                    FixtureVerification.Observation observation) {
        if (observation.fixtureId() != fixtureId || observation.seasonId() != SEASON.id()
                || !observation.seasonCode().equals(SEASON.code()) || !observation.coherent()
                || !observation.finished() || !observation.reads().histories().keySet().equals(requiredPlayers)) {
            return false;
        }
        Map<String, Object> manifest = jdbc.queryForMap("SELECT r.requested_source_after,r.completed_at,"
                + "m.source_floor,f.read_started_at AS fixtures_started,f.read_finished_at AS fixtures_finished,"
                + "l.read_started_at AS live_started,l.read_finished_at AS live_finished"
                + " FROM " + TABLES.run() + " r JOIN " + TABLES.fixtureDependencyManifest()
                + " m ON m.run_generation=r.generation JOIN " + TABLES.payload()
                + " f ON f.id=m.fixtures_payload_id JOIN " + TABLES.payload()
                + " l ON l.id=m.live_payload_id WHERE r.generation=? AND f.run_generation=r.generation"
                + " AND l.run_generation=r.generation", generation);
        Instant requestedFloor = ((Timestamp) manifest.get("requested_source_after")).toInstant();
        Instant sourceFloor = ((Timestamp) manifest.get("source_floor")).toInstant();
        Instant completedAt = ((Timestamp) manifest.get("completed_at")).toInstant();
        SourceRead fixtureRead = new SourceRead(observation.reads().fixture().startedAt(),
                observation.reads().fixture().completedAt());
        SourceRead liveRead = new SourceRead(observation.reads().live().startedAt(),
                observation.reads().live().completedAt());
        if (!fixtureRead.startedAt().equals(((Timestamp) manifest.get("fixtures_started")).toInstant())
                || !fixtureRead.finishedAt().equals(((Timestamp) manifest.get("fixtures_finished")).toInstant())
                || !liveRead.startedAt().equals(((Timestamp) manifest.get("live_started")).toInstant())
                || !liveRead.finishedAt().equals(((Timestamp) manifest.get("live_finished")).toInstant())
                || !sourceFloor.equals(requestedFloor) || fixtureRead.startedAt().isBefore(sourceFloor)
                || liveRead.startedAt().isBefore(sourceFloor)
                || liveRead.startedAt().isBefore(fixtureRead.finishedAt().plusMillis(1))
                || fixtureRead.finishedAt().isAfter(completedAt) || liveRead.finishedAt().isAfter(completedAt)) {
            return false;
        }
        if (!isDatabaseMicrosecond(fixtureRead.startedAt()) || !isDatabaseMicrosecond(fixtureRead.finishedAt())
                || !isDatabaseMicrosecond(liveRead.startedAt()) || !isDatabaseMicrosecond(liveRead.finishedAt())
                || !isDatabaseMicrosecond(requestedFloor) || !isDatabaseMicrosecond(sourceFloor)
                || !isDatabaseMicrosecond(completedAt)) return false;

        List<Map<String, Object>> evidence = jdbc.queryForList("SELECT e.player_id,e.source_read_started_at,"
                + "e.source_read_finished_at,hp.id AS payload_id,hp.validation_state,"
                + "hp.read_started_at AS history_started,hp.read_finished_at AS history_finished,hp.staged_at,"
                + "hr.kind,hr.state,hr.season,hr.runtime_mode,hr.runtime_epoch,hr.coalescing_key,"
                + "hr.publication_key,hr.completed_at AS history_completed FROM "
                + TABLES.playerFixtureEvidence() + " e LEFT JOIN " + TABLES.payload()
                + " hp ON hp.endpoint_kind='ELEMENT_SUMMARY' AND hp.event_id IS NULL"
                + " AND hp.player_id=e.player_id AND hp.read_started_at=e.source_read_started_at"
                + " AND hp.read_finished_at=e.source_read_finished_at LEFT JOIN " + TABLES.run()
                + " hr ON hr.generation=hp.run_generation WHERE e.season_id=? AND e.fixture_id=?"
                + " AND e.generation=?", SEASON.id(), fixtureId, generation);
        if (evidence.size() != requiredPlayers.size()) return false;
        Set<Integer> evidencePlayers = new java.util.TreeSet<>();
        Instant effectiveFloor = fixtureRead.finishedAt().plusMillis(1);
        for (Map<String, Object> row : evidence) {
            int playerId = ((Number) row.get("player_id")).intValue();
            if (!evidencePlayers.add(playerId) || !requiredPlayers.contains(playerId)
                    || row.get("payload_id") == null) return false;
            Instant startedAt = ((Timestamp) row.get("source_read_started_at")).toInstant();
            Instant finishedAt = ((Timestamp) row.get("source_read_finished_at")).toInstant();
            Instant payloadStartedAt = ((Timestamp) row.get("history_started")).toInstant();
            Instant payloadFinishedAt = ((Timestamp) row.get("history_finished")).toInstant();
            Instant historyCompletedAt = ((Timestamp) row.get("history_completed")).toInstant();
            var observed = observation.reads().histories().get(playerId);
            if (observed == null || !observed.startedAt().equals(startedAt)
                    || !observed.completedAt().equals(finishedAt)
                    || !payloadStartedAt.equals(startedAt) || !payloadFinishedAt.equals(finishedAt)
                    || payloadStartedAt.isBefore(requestedFloor) || payloadStartedAt.isBefore(sourceFloor)
                    || payloadStartedAt.isBefore(effectiveFloor) || payloadFinishedAt.isAfter(completedAt)
                    || historyCompletedAt.isAfter(completedAt)
                    || ((Timestamp) row.get("staged_at")).toInstant().isAfter(completedAt)
                    || !"VALID".equals(row.get("validation_state"))
                    || !"PLAYER_HISTORY".equals(row.get("kind")) || !"COMPLETED".equals(row.get("state"))
                    || !SEASON.code().equals(row.get("season")) || !"ACTIVE".equals(row.get("runtime_mode"))
                    || ((Number) row.get("runtime_epoch")).longValue() != harness.fence.epoch()
                    || !("player-history:" + playerId).equals(row.get("coalescing_key"))
                    || !row.get("coalescing_key").equals(row.get("publication_key"))
                    || !isDatabaseMicrosecond(startedAt) || !isDatabaseMicrosecond(finishedAt)
                    || !isDatabaseMicrosecond(payloadStartedAt) || !isDatabaseMicrosecond(payloadFinishedAt)
                    || !isDatabaseMicrosecond(historyCompletedAt)) return false;
        }
        return evidencePlayers.equals(requiredPlayers);
    }


    private static List<Map<String, Object>> fixtureHistoryPayloadRows(Harness harness, Set<Integer> requiredPlayers,
                                                                        FixtureVerification.Observation observation) {
        List<Integer> players = requiredPlayers.stream().sorted().toList();
        String values = players.stream().map(ignored -> "(?::integer,?::timestamptz,?::timestamptz)")
                .collect(java.util.stream.Collectors.joining(","));
        List<Object> parameters = new ArrayList<>();
        for (int playerId : players) {
            var read = observation.reads().histories().get(playerId);
            if (read == null) return List.of();
            parameters.add(playerId);
            parameters.add(Timestamp.from(read.startedAt()));
            parameters.add(Timestamp.from(read.completedAt()));
        }
        String sql = "SELECT expected.player_id expected_player_id,hp.id payload_id,hp.endpoint_kind,hp.event_id,"
                + "hp.validation_state,hp.body IS NOT NULL body_present,hp.body,hp.read_started_at,hp.read_finished_at,"
                + "hp.staged_at,hr.generation history_generation,hr.kind history_kind,hr.state history_state,"
                + "hr.season history_season,hr.runtime_mode history_mode,hr.runtime_epoch history_epoch,"
                + "hr.coalescing_key history_coalescing,hr.publication_key history_publication,"
                + "hr.completed_at history_completed FROM (VALUES " + values + ")"
                + " AS expected(player_id,read_started_at,read_finished_at) LEFT JOIN " + TABLES.payload()
                + " hp ON hp.player_id=expected.player_id AND hp.endpoint_kind='ELEMENT_SUMMARY'"
                + " AND hp.event_id IS NULL AND hp.read_started_at=expected.read_started_at"
                + " AND hp.read_finished_at=expected.read_finished_at LEFT JOIN " + TABLES.run()
                + " hr ON hr.generation=hp.run_generation ORDER BY expected.player_id";
        return jdbc.queryForList(sql, parameters.toArray());
    }

    private static void assertFixtureGenerationBodiesMatchSource(Harness harness, int fixtureId, long generation,
                                                                  FixtureOracle oracle, SourceData source,
                                                                  FixtureVerification.Observation observation) {
        assertThat(fixtureGenerationBodiesMatchSource(harness, fixtureId, generation, oracle, source, observation))
                .as("fixture %d generation %d source bodies and hashes", fixtureId, generation).isTrue();
    }

    private static boolean fixtureGenerationBodiesMatchSource(Harness harness, int fixtureId, long generation,
                                                                FixtureOracle oracle, SourceData source,
                                                                FixtureVerification.Observation observation) {
        List<Map<String, Object>> anchors = jdbc.queryForList("SELECT p.endpoint_kind,p.event_id,p.player_id,"
                + "p.validation_state,p.read_started_at,p.read_finished_at,p.body FROM "
                + TABLES.fixtureDependencyManifest() + " m JOIN " + TABLES.payload()
                + " p ON p.id=m.bootstrap_payload_id WHERE m.run_generation=? UNION ALL SELECT"
                + " p.endpoint_kind,p.event_id,p.player_id,p.validation_state,p.read_started_at,p.read_finished_at,p.body"
                + " FROM " + TABLES.fixtureDependencyManifest() + " m JOIN " + TABLES.payload()
                + " p ON p.id=m.fixtures_payload_id WHERE m.run_generation=? UNION ALL SELECT"
                + " p.endpoint_kind,p.event_id,p.player_id,p.validation_state,p.read_started_at,p.read_finished_at,p.body"
                + " FROM " + TABLES.fixtureDependencyManifest() + " m JOIN " + TABLES.payload()
                + " p ON p.id=m.live_payload_id WHERE m.run_generation=? ORDER BY endpoint_kind",
                generation, generation, generation);
        if (anchors.size() != 3
                || !anchorBodyMatches(anchors, "BOOTSTRAP_STATIC", null, source.bootstrapBytes())
                || !anchorBodyMatches(anchors, "FIXTURES", null, source.fixtureBytes())
                || !anchorBodyMatches(anchors, "EVENT_LIVE", oracle.gameweek(), source.liveBytes(oracle.gameweek()))) {
            return false;
        }
        List<Map<String, Object>> histories = fixtureHistoryPayloadRows(harness, oracle.requiredPlayers(), observation);
        if (histories.size() != oracle.requiredPlayers().size()) return false;
        for (Map<String, Object> row : histories) {
            int playerId = ((Number) row.get("expected_player_id")).intValue();
            var read = observation.reads().histories().get(playerId);
            if (read == null || row.get("payload_id") == null
                    || !"ELEMENT_SUMMARY".equals(row.get("endpoint_kind")) || row.get("event_id") != null
                    || !"VALID".equals(row.get("validation_state"))
                    || !bodyMatches((byte[]) row.get("body"), source.historyBytes(playerId))
                    || !Timestamp.from(read.startedAt()).equals(row.get("read_started_at"))
                    || !Timestamp.from(read.completedAt()).equals(row.get("read_finished_at"))) return false;
        }
        return true;
    }

    private static boolean anchorBodyMatches(List<Map<String, Object>> anchors, String role,
                                             Integer eventId, byte[] expectedBody) {
        List<Map<String, Object>> matches = anchors.stream()
                .filter(row -> role.equals(row.get("endpoint_kind"))).toList();
        if (matches.size() != 1) return false;
        Map<String, Object> row = matches.getFirst();
        return java.util.Objects.equals(row.get("event_id"), eventId) && row.get("player_id") == null
                && "VALID".equals(row.get("validation_state"))
                && bodyMatches((byte[]) row.get("body"), expectedBody);
    }

    private static boolean bodyMatches(byte[] actualBody, byte[] expectedBody) {
        return actualBody != null && java.util.Arrays.equals(actualBody, expectedBody)
                && OwnedSyncStore.sha256(actualBody).equals(OwnedSyncStore.sha256(expectedBody));
    }

    private static boolean correctionBatchesValid(SourceRead first, SourceRead second, Instant correctionFloor) {
        return !first.startedAt().isAfter(first.finishedAt())
                && !second.startedAt().isAfter(second.finishedAt())
                && !first.startedAt().isBefore(correctionFloor)
                && !second.startedAt().isBefore(correctionFloor)
                && !second.startedAt().isBefore(first.finishedAt().plus(Duration.ofMinutes(10)));
    }

    private static void assertCorrectionBatchPairNegativeCases() {
        Instant floor = Instant.parse("2026-09-30T12:00:00Z");
        SourceRead first = new SourceRead(floor, floor.plus(Duration.ofMinutes(1)));
        SourceRead exactBoundary = new SourceRead(floor.plus(Duration.ofMinutes(11)),
                floor.plus(Duration.ofMinutes(12)));
        SourceRead oneMillisecondEarly = new SourceRead(
                first.finishedAt().plus(Duration.ofMinutes(10)).minusMillis(1),
                floor.plus(Duration.ofMinutes(12)));
        assertThat(correctionBatchesValid(first, exactBoundary, floor))
                .as("independent source batches exactly ten minutes apart are accepted").isTrue();
        assertThat(correctionBatchesValid(new SourceRead(floor.minusMillis(1), first.finishedAt()),
                exactBoundary, floor)).as("a first source read before the correction floor is rejected").isFalse();
        assertThat(correctionBatchesValid(first,
                new SourceRead(floor.minusMillis(1), floor.plus(Duration.ofMinutes(12))), floor))
                .as("a second source read before the correction floor is rejected").isFalse();
        assertThat(correctionBatchesValid(first, oneMillisecondEarly, floor))
                .as("source batches less than ten minutes apart are rejected").isFalse();
        assertThatThrownBy(() -> new SourceRead(floor.plusSeconds(1), floor))
                .as("a reversed first source interval is rejected")
                .isInstanceOf(IllegalArgumentException.class).hasMessage("source read interval is reversed");
        assertThatThrownBy(() -> new SourceRead(floor.plus(Duration.ofMinutes(12)),
                floor.plus(Duration.ofMinutes(11))))
                .as("a reversed second source interval is rejected")
                .isInstanceOf(IllegalArgumentException.class).hasMessage("source read interval is reversed");
    }

    private static boolean isDatabaseMicrosecond(Instant instant) {
        return instant.getNano() % 1_000 == 0;
    }




    private static void assertTargetReceiptIntervalNegativeCases(Harness harness, int fixtureId) throws IOException {
        assertCorrectionBatchPairNegativeCases();
        assertSourceObservationProofNegativeCases();
        Map<String, Object> receipt = jdbc.queryForMap("SELECT r.source_read_started_at run_started,"
                + "r.source_read_finished_at run_finished,r.completed_at,p.source_read_started_at receipt_started,"
                + "p.source_read_finished_at receipt_finished,p.published_at receipt_published FROM "
                + TABLES.fixtureSync() + " fs JOIN " + TABLES.receipt() + " p ON p.season=fs.season_code"
                + " AND p.publication_key='fixture:' || fs.fixture_id::text"
                + " AND p.latest_generation=fs.current_verified_generation JOIN " + TABLES.run()
                + " r ON r.generation=p.latest_generation AND r.season=p.season"
                + " WHERE fs.season_id=? AND fs.fixture_id=? AND fs.state='VERIFIED'"
                + " AND r.kind='FIXTURE_VERIFICATION' AND r.runtime_mode='ACTIVE' AND r.runtime_epoch=?",
                SEASON.id(), fixtureId, harness.fence.epoch());
        Timestamp runStarted = rTimestamp(receipt, "run_started");
        Timestamp runFinished = rTimestamp(receipt, "run_finished");
        Timestamp receiptStarted = rTimestamp(receipt, "receipt_started");
        Timestamp receiptFinished = rTimestamp(receipt, "receipt_finished");
        Timestamp completedAt = rTimestamp(receipt, "completed_at");
        Timestamp publishedAt = rTimestamp(receipt, "receipt_published");
        Instant now = harness.clock.instant();

        assertThat(receiptIntervalProof(runStarted, runFinished, receiptStarted, receiptFinished,
                completedAt, publishedAt, now)).as("actual current receipt interval").isTrue();
        assertThat(receiptIntervalProof(runStarted, runFinished, null, receiptFinished,
                completedAt, publishedAt, now)).as("NULL receipt start is rejected").isFalse();
        assertThat(receiptIntervalProof(runStarted, runFinished,
                Timestamp.from(runStarted.toInstant().plusNanos(1_000)), receiptFinished,
                completedAt, publishedAt, now)).as("a one-microsecond receipt/run start mismatch is rejected")
                .isFalse();
        assertThat(receiptIntervalProof(runStarted, runFinished, receiptStarted, receiptFinished,
                completedAt, Timestamp.from(now.plusNanos(1_000)), now))
                .as("a future publication timestamp is rejected").isFalse();
        assertJointlyShortenedFixtureFinishIsRejected(harness, fixtureId);
        assertJointlyShortenedCanonicalStartIsRejected(harness);
    }

    private static void assertJointlyShortenedFixtureFinishIsRejected(Harness harness, int fixtureId) {
        Map<String, Object> row = jdbc.queryForMap("SELECT fs.current_verified_generation,r.source_read_started_at,"
                + "r.source_read_finished_at,p.source_read_started_at receipt_started,"
                + "p.source_read_finished_at receipt_finished,r.completed_at,p.published_at FROM "
                + TABLES.fixtureSync() + " fs JOIN " + TABLES.receipt()
                + " p ON p.season=fs.season_code AND p.publication_key='fixture:' || fs.fixture_id::text"
                + " AND p.latest_generation=fs.current_verified_generation JOIN " + TABLES.run()
                + " r ON r.generation=p.latest_generation AND r.season=p.season"
                + " WHERE fs.season_id=? AND fs.fixture_id=? AND fs.state='VERIFIED'",
                SEASON.id(), fixtureId);
        long generation = numberLong(row.get("current_verified_generation"));
        Timestamp runStarted = rTimestamp(row, "source_read_started_at");
        Timestamp runFinished = rTimestamp(row, "source_read_finished_at");
        Timestamp receiptStarted = rTimestamp(row, "receipt_started");
        Timestamp receiptFinished = rTimestamp(row, "receipt_finished");
        Timestamp completedAt = rTimestamp(row, "completed_at");
        Timestamp publishedAt = rTimestamp(row, "published_at");
        RootDocuments root = currentCanonicalDocuments(harness, fixtureId);
        FixtureVerification.Observation observation = fixtureObservation(fixtureId);
        SourceRead bootstrap = sourceRead(root.bootstrap());
        SourceRead fixtures = new SourceRead(observation.reads().fixture().startedAt(),
                observation.reads().fixture().completedAt());
        SourceRead live = new SourceRead(observation.reads().live().startedAt(),
                observation.reads().live().completedAt());
        List<SourceRead> histories = observation.reads().histories().values().stream()
                .map(read -> new SourceRead(read.startedAt(), read.completedAt())).toList();
        List<SourceRead> allReads = new ArrayList<>(List.of(bootstrap, fixtures, live));
        allReads.addAll(histories);
        List<SourceRead> fixtureReads = new ArrayList<>(List.of(fixtures, live));
        fixtureReads.addAll(histories);
        SourceRead fixtureInterval = observationInterval(observation);
        assertThat(fullSourceIntervalProof(generation, generation,
                new SourceRead(runStarted.toInstant(), runFinished.toInstant()), completedAt.toInstant(),
                allReads, fixtureReads, fixtureInterval))
                .as("actual fixture run spans bootstrap, fixture, live and every history read").isTrue();
        Timestamp shortenedFinish = Timestamp.from(fixtures.finishedAt());
        assertThat(shortenedFinish).isBefore(runFinished);
        try {
            jdbc.update("UPDATE " + TABLES.run() + " SET source_read_finished_at=? WHERE generation=?",
                    shortenedFinish, generation);
            jdbc.update("UPDATE " + TABLES.receipt() + " SET source_read_finished_at=?"
                    + " WHERE season=? AND publication_key=? AND latest_generation=?",
                    shortenedFinish, SEASON.code(), "fixture:" + fixtureId, generation);
            Map<String, Object> shortened = jdbc.queryForMap("SELECT r.source_read_started_at run_started,"
                    + "r.source_read_finished_at run_finished,p.source_read_started_at receipt_started,"
                    + "p.source_read_finished_at receipt_finished,r.completed_at,p.published_at FROM "
                    + TABLES.run() + " r JOIN " + TABLES.receipt()
                    + " p ON p.season=r.season AND p.publication_key=r.publication_key"
                    + " AND p.latest_generation=r.generation WHERE r.generation=?", generation);
            assertThat(receiptIntervalProof(rTimestamp(shortened, "run_started"),
                    rTimestamp(shortened, "run_finished"), rTimestamp(shortened, "receipt_started"),
                    rTimestamp(shortened, "receipt_finished"), rTimestamp(shortened, "completed_at"),
                    rTimestamp(shortened, "published_at"), harness.clock.instant()))
                    .as("jointly shortened run and receipt retain the local receipt predicate").isTrue();
            assertThat(fullSourceIntervalProof(generation, generation,
                    new SourceRead(rTimestamp(shortened, "run_started").toInstant(),
                            rTimestamp(shortened, "run_finished").toInstant()),
                    rTimestamp(shortened, "completed_at").toInstant(), allReads, fixtureReads, fixtureInterval))
                    .as("a receipt ending before its latest history read is not full-source proof").isFalse();
        } finally {
            jdbc.update("UPDATE " + TABLES.run() + " SET source_read_finished_at=? WHERE generation=?",
                    runFinished, generation);
            jdbc.update("UPDATE " + TABLES.receipt() + " SET source_read_finished_at=?"
                    + " WHERE season=? AND publication_key=? AND latest_generation=?",
                    receiptFinished, SEASON.code(), "fixture:" + fixtureId, generation);
        }
    }

    private static void assertJointlyShortenedCanonicalStartIsRejected(Harness harness) {
        CanonicalParentProof selected = currentCanonicalParentProof(harness);
        assertThat(selected.currentPublished()).isTrue();
        Map<String, Object> row = jdbc.queryForMap("SELECT p.publication_key,p.latest_generation,"
                + "p.source_read_started_at receipt_started,p.source_read_finished_at receipt_finished,"
                + "p.published_at,r.source_read_started_at run_started,r.source_read_finished_at run_finished,"
                + "r.completed_at,b.read_started_at bootstrap_started,b.read_finished_at bootstrap_finished,"
                + "f.read_started_at fixtures_started,f.read_finished_at fixtures_finished,"
                + "p.source_bootstrap_sha256,p.source_fixtures_sha256,p.source_pair_fingerprint FROM "
                + TABLES.receipt() + " p JOIN " + TABLES.run() + " r ON r.generation=p.latest_generation"
                + " AND r.season=p.season JOIN " + TABLES.payload() + " b ON b.id=p.source_bootstrap_payload_id"
                + " JOIN " + TABLES.payload() + " f ON f.id=p.source_fixtures_payload_id"
                + " WHERE p.season=? AND p.publication_key=? AND p.latest_generation=?",
                SEASON.code(), selected.publicationKey(), selected.publicationGeneration());
        long generation = numberLong(row.get("latest_generation"));
        Timestamp runStarted = rTimestamp(row, "run_started");
        Timestamp receiptStarted = rTimestamp(row, "receipt_started");
        Timestamp runFinished = rTimestamp(row, "run_finished");
        Timestamp receiptFinished = rTimestamp(row, "receipt_finished");
        Timestamp completedAt = rTimestamp(row, "completed_at");
        Timestamp publishedAt = rTimestamp(row, "published_at");
        Timestamp bootstrapStarted = rTimestamp(row, "bootstrap_started");
        Timestamp bootstrapFinished = rTimestamp(row, "bootstrap_finished");
        Timestamp fixturesStarted = rTimestamp(row, "fixtures_started");
        Timestamp fixturesFinished = rTimestamp(row, "fixtures_finished");
        String bootstrapHash = (String) row.get("source_bootstrap_sha256");
        String fixturesHash = (String) row.get("source_fixtures_sha256");
        String fingerprint = (String) row.get("source_pair_fingerprint");
        assertThat(fixturesStarted).isAfter(bootstrapStarted);
        assertThat(canonicalSourceSpanProof(runStarted, runFinished, receiptStarted, receiptFinished,
                completedAt, publishedAt, bootstrapStarted, bootstrapFinished, fixturesStarted, fixturesFinished,
                harness.clock.instant())).isTrue();
        try {
            jdbc.update("UPDATE " + TABLES.run() + " SET source_read_started_at=? WHERE generation=?",
                    fixturesStarted, generation);
            jdbc.update("UPDATE " + TABLES.receipt() + " SET source_read_started_at=?"
                    + " WHERE season=? AND publication_key=? AND latest_generation=?",
                    fixturesStarted, SEASON.code(), selected.publicationKey(), generation);
            Map<String, Object> shortened = jdbc.queryForMap("SELECT p.source_read_started_at receipt_started,"
                    + "p.source_read_finished_at receipt_finished,p.published_at,"
                    + "r.source_read_started_at run_started,r.source_read_finished_at run_finished,r.completed_at,"
                    + "b.read_started_at bootstrap_started,b.read_finished_at bootstrap_finished,"
                    + "f.read_started_at fixtures_started,f.read_finished_at fixtures_finished,"
                    + "p.source_bootstrap_sha256,p.source_fixtures_sha256,p.source_pair_fingerprint FROM "
                    + TABLES.receipt() + " p JOIN " + TABLES.run()
                    + " r ON r.generation=p.latest_generation AND r.season=p.season JOIN " + TABLES.payload()
                    + " b ON b.id=p.source_bootstrap_payload_id JOIN " + TABLES.payload()
                    + " f ON f.id=p.source_fixtures_payload_id WHERE p.season=? AND p.publication_key=?"
                    + " AND p.latest_generation=?", SEASON.code(), selected.publicationKey(), generation);
            assertThat(receiptIntervalProof(rTimestamp(shortened, "run_started"),
                    rTimestamp(shortened, "run_finished"), rTimestamp(shortened, "receipt_started"),
                    rTimestamp(shortened, "receipt_finished"), rTimestamp(shortened, "completed_at"),
                    rTimestamp(shortened, "published_at"), harness.clock.instant()))
                    .as("jointly shortened canonical run and receipt retain the local receipt predicate").isTrue();
            assertThat(canonicalSourceSpanProof(rTimestamp(shortened, "run_started"),
                    rTimestamp(shortened, "run_finished"), rTimestamp(shortened, "receipt_started"),
                    rTimestamp(shortened, "receipt_finished"), rTimestamp(shortened, "completed_at"),
                    rTimestamp(shortened, "published_at"), rTimestamp(shortened, "bootstrap_started"),
                    rTimestamp(shortened, "bootstrap_finished"), rTimestamp(shortened, "fixtures_started"),
                    rTimestamp(shortened, "fixtures_finished"), harness.clock.instant()))
                    .as("full published-source proof includes the actual earliest body start").isFalse();
            assertThat(shortened.get("source_bootstrap_sha256")).isEqualTo(bootstrapHash);
            assertThat(shortened.get("source_fixtures_sha256")).isEqualTo(fixturesHash);
            assertThat(shortened.get("source_pair_fingerprint")).isEqualTo(fingerprint);
        } finally {
            jdbc.update("UPDATE " + TABLES.run() + " SET source_read_started_at=? WHERE generation=?",
                    runStarted, generation);
            jdbc.update("UPDATE " + TABLES.receipt() + " SET source_read_started_at=?"
                    + " WHERE season=? AND publication_key=? AND latest_generation=?",
                    receiptStarted, SEASON.code(), selected.publicationKey(), generation);
        }
    }

    private static SourceRead sourceRead(CatalogueScheduleParser.SourceDocument document) {
        return new SourceRead(document.readInterval().startedAt(), document.readInterval().completedAt());
    }

    private static void assertCanonicalPairProof(Harness harness, SourceData source) {
        Map<String, Object> parent = jdbc.queryForMap("SELECT p.source_pair_fingerprint,"
                + "p.source_bootstrap_sha256,p.source_fixtures_sha256,p.source_bootstrap_payload_id,"
                + "p.source_fixtures_payload_id,p.source_read_started_at receipt_started,"
                + "p.source_read_finished_at receipt_finished,p.published_at,"
                + "r.source_read_started_at run_started,r.source_read_finished_at run_finished,r.completed_at,"
                + "b.run_generation AS source_generation,b.read_started_at AS bootstrap_started,"
                + "b.read_finished_at AS bootstrap_finished,f.read_started_at AS fixtures_started,"
                + "f.read_finished_at AS fixtures_finished FROM " + TABLES.receipt() + " p"
                + " JOIN " + TABLES.run() + " r ON r.generation=p.latest_generation AND r.season=p.season"
                + " JOIN " + TABLES.payload() + " b ON b.id=p.source_bootstrap_payload_id"
                + " JOIN " + TABLES.payload() + " f ON f.id=p.source_fixtures_payload_id"
                + " WHERE p.season=? AND p.publication_key=? AND p.latest_generation=r.generation"
                + " AND r.kind='CATALOGUE_SCHEDULE' AND r.runtime_mode='ACTIVE'"
                + " AND r.runtime_epoch=? AND r.state='COMPLETED' AND b.run_generation=f.run_generation"
                + " AND b.validation_state='VALID' AND f.validation_state='VALID'"
                + " AND b.source_published=true AND f.source_published=true"
                + " ORDER BY p.published_at DESC LIMIT 1", SEASON.code(),
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY, harness.fence.epoch());
        long sourceGeneration = ((Number) parent.get("source_generation")).longValue();
        String bootstrapHash = (String) parent.get("source_bootstrap_sha256");
        String fixturesHash = (String) parent.get("source_fixtures_sha256");
        assertThat(bootstrapHash).isEqualTo(OwnedSyncStore.sha256(source.bootstrapBytes()));
        assertThat(fixturesHash).isEqualTo(OwnedSyncStore.sha256(source.fixtureBytes()));
        Timestamp publishedAt = rTimestamp(parent, "published_at");
        Timestamp runStarted = rTimestamp(parent, "run_started");
        Timestamp runFinished = rTimestamp(parent, "run_finished");
        Timestamp receiptStarted = rTimestamp(parent, "receipt_started");
        Timestamp receiptFinished = rTimestamp(parent, "receipt_finished");
        Timestamp completedAt = rTimestamp(parent, "completed_at");
        Timestamp bootstrapStarted = rTimestamp(parent, "bootstrap_started");
        Timestamp bootstrapFinished = rTimestamp(parent, "bootstrap_finished");
        Timestamp fixturesStarted = rTimestamp(parent, "fixtures_started");
        Timestamp fixturesFinished = rTimestamp(parent, "fixtures_finished");
        assertThat(databaseMicroseconds(publishedAt, runStarted, runFinished, receiptStarted, receiptFinished,
                completedAt, bootstrapStarted, bootstrapFinished, fixturesStarted, fixturesFinished)).isTrue();
        assertThat(bootstrapStarted).isBeforeOrEqualTo(bootstrapFinished);
        assertThat(fixturesStarted).isBeforeOrEqualTo(fixturesFinished);
        assertThat(runStarted).isEqualTo(earlier(bootstrapStarted, fixturesStarted));
        assertThat(runFinished).isEqualTo(later(bootstrapFinished, fixturesFinished));
        assertThat(receiptIntervalProof(runStarted, runFinished, receiptStarted, receiptFinished,
                completedAt, publishedAt, harness.clock.instant())).isTrue();
        var pair = OwnedSyncStore.CanonicalSourcePair.of(
                new OwnedSyncStore.CanonicalSourcePayload(((Number) parent.get("source_bootstrap_payload_id")).longValue(),
                        sourceGeneration, EndpointKind.BOOTSTRAP_STATIC,
                        new SourceRead(bootstrapStarted.toInstant(), bootstrapFinished.toInstant()), bootstrapHash),
                new OwnedSyncStore.CanonicalSourcePayload(((Number) parent.get("source_fixtures_payload_id")).longValue(),
                        sourceGeneration, EndpointKind.FIXTURES,
                        new SourceRead(fixturesStarted.toInstant(), fixturesFinished.toInstant()), fixturesHash));
        assertThat(parent.get("source_pair_fingerprint")).isEqualTo(pair.fingerprint());
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void twoWorkerFixtureFanoutDoesNotStarveDueHistories() throws Exception {
        long wallDeadline = System.nanoTime() + WALL_LIMIT_NANOS;
        List<Metrics> baselines = new ArrayList<>();
        for (int firstWorker : List.of(1, 2)) {
            try (Harness harness = new Harness(firstWorker, wallDeadline)) {
                harness.seedCanonicalPair();
                harness.seedMixedBacklog();
                harness.resetPayloadReads();
                Metrics metrics = harness.driveMixedBacklog();
                baselines.add(metrics);
                if (metrics.deferrals() > 0) {
                    System.out.printf("OWNED_ADMISSION_DEFERRALS firstWorker=%d reasons=%s attempts=%s errors=%s%n",
                            firstWorker, harness.deferralReasons,
                            jdbc.query("SELECT completed_at IS NULL, hold_until_expiry, count(*) FROM "
                                            + TABLES.requestAttempt()
                                            + " GROUP BY completed_at IS NULL, hold_until_expiry",
                                    (rs, row) -> List.of(rs.getBoolean(1), rs.getBoolean(2), rs.getLong(3))),
                            jdbc.query("SELECT last_error, count(*) FROM " + TABLES.run()
                                            + " WHERE season=? AND last_error LIKE 'HTTP admission deferred:%'"
                                            + " GROUP BY last_error",
                                    (rs, row) -> List.of(rs.getString(1), rs.getLong(2)), SEASON.code()));
                }
            }
        }

        baselines.forEach(metrics -> System.out.printf(
                "OWNED_ADMISSION_BASELINE firstWorker=%d firstAdmissionWorker=%d firstHistoryGetSeconds=%s "
                        + "firstHistoryCompleteSeconds=%s distinctCompletedPlayers=%s historiesWithin60=%d "
                        + "stagedFixtures=%d claims=%d deferrals=%d admitted=%d gets=%s "
                        + "payloadRows=%d stagedRawBytes=%d returnedGets=%d returnedRawBytes=%d "
                        + "newPartialBootstraps=%d peakNewPartialPairs=%d dispatches=%d predecessorWaits=%d "
                        + "spacingWaits=%d virtualSeconds=%.3f%n",
                metrics.firstWorker(), metrics.firstAdmissionWorker(), offset(metrics.firstHistoryGet()),
                offset(metrics.firstHistoryCompleted()), metrics.completedPlayerIds(),
                metrics.historiesWithin60(), metrics.stagedFixtures(), metrics.claims(),
                metrics.deferrals(), metrics.admittedAttempts(),
                metrics.getsByEndpoint(), metrics.payloadRows(), metrics.stagedRawBytes(),
                metrics.returnedGets(), metrics.returnedRawBytes(), metrics.newPartialBootstraps(),
                metrics.peakNewPartialPairs(), metrics.dispatches(), metrics.predecessorWaits(),
                metrics.spacingWaits(), metrics.virtualSeconds()));
        baselines.forEach(metrics -> System.out.printf(
                "OWNED_ADMISSION_DB_BODY_READS firstWorker=%d oldOwn=%s guardedSingle=%s canonicalPair=%s "
                        + "other=%s totals=%d/%d/%d/%d%n",
                metrics.firstWorker(),
                metrics.payloadReads().family(PayloadReadProbe.Family.OLD_OWN_CHECKPOINT).counts(),
                metrics.payloadReads().family(PayloadReadProbe.Family.GUARDED_SINGLE).counts(),
                metrics.payloadReads().family(PayloadReadProbe.Family.CANONICAL_PAIR).counts(),
                metrics.payloadReads().family(PayloadReadProbe.Family.OTHER_BODY_SELECT).counts(),
                metrics.payloadReads().attempts(), metrics.payloadReads().resultRows(),
                metrics.payloadReads().bodyValues(), metrics.payloadReads().rawBytes()));

        assertThat(baselines).hasSize(2).allSatisfy(metrics -> {
            assertThat(metrics.firstAdmissionWorker()).isEqualTo(metrics.firstWorker());
            assertThat(metrics.virtualSeconds()).isLessThanOrEqualTo(VIRTUAL_LIMIT.toNanos() / 1e9);
            assertThat(metrics.dispatches()).isLessThanOrEqualTo(DISPATCH_LIMIT);
            assertThat(metrics.predecessorWaits())
                    .as("the mixed backlog must observe a real queued FIFO wait")
                    .isGreaterThan(0);
            assertThat(metrics.spacingWaits())
                    .as("the mixed backlog must observe a real minimum-spacing wait")
                    .isGreaterThan(0);
            assertThat(metrics.firstHistoryCompleted())
                    .as("a valid history must complete within 15 virtual seconds")
                    .isNotNull()
                    .isBeforeOrEqualTo(START.plusSeconds(15));
            assertThat(metrics.historiesWithin60())
                    .as("at least ten distinct valid histories must complete within 60 virtual seconds")
                    .isGreaterThanOrEqualTo(10);
            assertThat(metrics.peakNewPartialPairs())
                    .as("the mixed backlog leaves at most two new partial bootstrap pairs outstanding")
                    .isLessThanOrEqualTo(2);
        });
    }



    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void coldParentHistoryDeferralDoesNotBlockCanonicalPrerequisites() throws Exception {
        ColdParentEvidence cold = runColdParentCase(System.nanoTime() + WALL_LIMIT_NANOS);
        assertThat(cold.historyDeferred()).isTrue();
        assertThat(cold.canonicalParentCompleted()).isTrue();
        assertThat(cold.historyCompleted()).isTrue();
        assertThat(cold.historyReusedCanonicalPair()).isTrue();
        assertThat(cold.getsByEndpoint()).containsEntry(EndpointKind.BOOTSTRAP_STATIC, 1)
                .containsEntry(EndpointKind.FIXTURES, 1)
                .containsEntry(EndpointKind.ELEMENT_SUMMARY, 1);
        assertThat(cold.pendingParents()).isEqualTo(2);
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void readyFixtureContinuationProgressesUnderBlockedFixtureAndHistoryBacklog() throws Exception {
        long wallDeadline = System.nanoTime() + WALL_LIMIT_NANOS;
        try (Harness harness = new Harness(1, wallDeadline)) {
            harness.seedCanonicalPair();
            FixturePressureSeed seeded = harness.seedReadyFixturePressure();
            assertReadyFixturePressure(harness, seeded.ready(), seeded.untouched(), harness.clock.instant());
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void maturedDependencyRetryBecomesReadyAfterItsRecordedDue() throws Exception {
        long wallDeadline = System.nanoTime() + WALL_LIMIT_NANOS;
        try (Harness harness = new Harness(1, wallDeadline)) {
            harness.seedCanonicalPair();
            MaturedFixturePressureSeed seeded = harness.seedMaturedReadyFixturePressure();
            assertReadyFixturePressure(harness, seeded.ready(), seeded.untouched(), harness.clock.instant());
        }
    }

    private static FocusMetrics assertReadyFixturePressure(Harness harness, FixtureSeed ready,
                                                            FixtureSeed untouched, Instant claimableAt)
            throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE season=? AND kind='PLAYER_HISTORY' AND state='PENDING' AND due_at<=?",
                Integer.class, SEASON.code(), Timestamp.from(claimableAt))).isGreaterThanOrEqualTo(8);

        FocusMetrics metrics = harness.driveReadyFixturePressure(ready.generation(), untouched.generation());
        assertThat(metrics.readyCompletedAt()).isNotNull();
        assertThat(harness.server.historyGetsBetween(claimableAt, metrics.readyCompletedAt()))
                .as("a real loopback history GET occurs while ready fixture generation %d is outstanding",
                        ready.generation())
                .isNotEmpty();
        assertThat(metrics.readyClaimedAt()).isNotNull();
        assertThat(metrics.untouchedClaimedAt()).isNotNull();
        double readyClaimSeconds = Duration.between(claimableAt, metrics.readyClaimedAt()).toNanos() / 1e9;
        double readyStageSeconds = Duration.between(claimableAt, metrics.readyCompletedAt()).toNanos() / 1e9;
        double untouchedClaimSeconds = Duration.between(claimableAt, metrics.untouchedClaimedAt()).toNanos() / 1e9;
        System.out.printf("OWNED_READY_FIXTURE generation=%d seedAttempts=%d firstClaimAttempt=%d "
                        + "totalAttempts=%d claimSeconds=%.3f completedThroughFirstClaim=%d "
                        + "finalRecheckPaths=%s finalRecheckOrdinals=%s durableAdmissionsThroughFinalRecheck=%d "
                        + "stagedSeconds=%.3f untouchedGeneration=%d untouchedSeedAttempts=%d "
                        + "untouchedFirstClaimAttempt=%d untouchedTotalAttempts=%d untouchedClaimSeconds=%.3f "
                        + "completedThroughUntouchedClaim=%d driverClaims=%d driverDispatches=%d "
                        + "gets=%s virtualSeconds=%.3f%n",
                metrics.readyGeneration(), metrics.readySeedAttemptCount(),
                metrics.readyFirstClaimAttemptCount(), metrics.readyAttemptCount(), readyClaimSeconds,
                metrics.completedThroughReadyClaim(), metrics.readyRequestPaths(), metrics.readyRequestOrdinals(),
                metrics.admissionsThroughFinalRecheck(), readyStageSeconds,
                metrics.untouchedGeneration(), metrics.untouchedSeedAttemptCount(),
                metrics.untouchedFirstClaimAttemptCount(), metrics.untouchedAttemptCount(), untouchedClaimSeconds,
                metrics.completedThroughUntouchedClaim(), metrics.driverClaims(),
                metrics.driverDispatches(), metrics.getsByEndpoint(), metrics.virtualSeconds());

        assertThat(metrics.readyFirstClaimAttemptCount())
                .as("first observed ready claim is exactly one above its seeded attempt count")
                .isEqualTo(metrics.readySeedAttemptCount() + 1);
        assertThat(readyClaimSeconds).as("ready retry claim latency").isBetween(0.0, 10.0);
        assertThat(metrics.completedThroughReadyClaim())
                .as("completed dispatch opportunities through the first ready claim, including same-tick completions")
                .isLessThanOrEqualTo(4);
        assertThat(metrics.readyRequestPaths())
                .as("the ready fixture makes only its two final public recheck GETs")
                .containsExactly("/api/bootstrap-static/", "/api/fixtures/");
        assertThat(metrics.admissionsThroughFinalRecheck())
                .as("durable request_attempt admissions, including no-fetch permits, through the last final recheck")
                .isLessThanOrEqualTo(4);
        assertThat(readyStageSeconds).as("ready fixture reaches STAGED").isBetween(0.0, 20.0);
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run()
                + " WHERE generation=?", String.class, ready.generation()))
                .as("STAGING completion").isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                + " WHERE run_generation=?", Integer.class, ready.generation())).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                + " WHERE run_generation=? AND validation_state<>'VALID'", Integer.class,
                ready.generation())).isZero();
        assertThat(jdbc.queryForObject("SELECT source_read_started_at IS NULL FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", Boolean.class, SEASON.code(), "fixture:1"))
                .as("STAGING does not publish a fixture receipt").isTrue();
        assertThat(metrics.untouchedFirstClaimAttemptCount())
                .as("first observed untouched claim is exactly one above its seeded attempt count")
                .isEqualTo(metrics.untouchedSeedAttemptCount() + 1);
        assertThat(untouchedClaimSeconds).as("untouched fixture claim latency").isBetween(0.0, 20.0);
        assertThat(metrics.completedThroughUntouchedClaim())
                .as("completed dispatch opportunities through the first untouched claim, including same-tick completions")
                .isLessThanOrEqualTo(8);
        return metrics;
    }

    private static ColdParentEvidence runColdParentCase(long wallDeadline) throws Exception {
        try (Harness harness = new Harness(1, wallDeadline)) {
            harness.enqueueHistoryOnly();
            boolean historyClaim = harness.coordinator.dispatchOne("cold-history");
            int parentRunsAfterHistory = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                    + " WHERE season=? AND kind='CATALOGUE_SCHEDULE' AND state='PENDING'",
                    Integer.class, SEASON.code());
            String historyError = jdbc.queryForObject("SELECT last_error FROM " + TABLES.run()
                    + " WHERE season=? AND kind='PLAYER_HISTORY'", String.class, SEASON.code());
            long firstParent = jdbc.queryForObject("SELECT generation FROM " + TABLES.run()
                    + " WHERE season=? AND kind='CATALOGUE_SCHEDULE' AND state='PENDING' "
                    + "ORDER BY due_at,generation LIMIT 1", Long.class, SEASON.code());

            harness.dispatchOneWithControlledResponse("cold-parent-first");
            boolean parentCompleted = jdbc.queryForObject("SELECT state='COMPLETED' FROM " + TABLES.run()
                    + " WHERE generation=?", Boolean.class, firstParent);
            int validParentPayloads = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                    + " WHERE run_generation=? AND endpoint_kind IN ('BOOTSTRAP_STATIC','FIXTURES') "
                    + "AND validation_state='VALID'", Integer.class, firstParent);
            jdbc.update("UPDATE " + TABLES.run() + " SET due_at=? WHERE season=? "
                            + "AND kind='CATALOGUE_SCHEDULE' AND state='PENDING' AND generation<>?",
                    Timestamp.from(harness.clock.instant().plus(Duration.ofHours(1))), SEASON.code(), firstParent);

            Map<EndpointKind, Integer> beforeHistoryGets = harness.server.getsByEndpoint();
            Long historyGeneration = jdbc.queryForObject("SELECT generation FROM " + TABLES.run()
                    + " WHERE season=? AND kind='PLAYER_HISTORY' AND state='PENDING' "
                    + "ORDER BY generation LIMIT 1", Long.class, SEASON.code());
            jdbc.update("UPDATE " + TABLES.run() + " SET due_at=? WHERE generation=?",
                    Timestamp.from(harness.clock.instant()), historyGeneration);
            boolean historyRetryClaimed = harness.dispatchOneWithControlledResponse("cold-history-retry") == 1;
            Map<EndpointKind, Integer> gets = harness.server.getsByEndpoint();
            boolean historyCompleted = jdbc.queryForObject("SELECT state='COMPLETED' FROM " + TABLES.run()
                    + " WHERE generation=?", Boolean.class, historyGeneration);
            boolean historyReusedCanonicalPair = beforeHistoryGets.getOrDefault(
                    EndpointKind.BOOTSTRAP_STATIC, 0) == gets.getOrDefault(EndpointKind.BOOTSTRAP_STATIC, 0)
                    && beforeHistoryGets.getOrDefault(EndpointKind.FIXTURES, 0)
                    == gets.getOrDefault(EndpointKind.FIXTURES, 0)
                    && gets.getOrDefault(EndpointKind.ELEMENT_SUMMARY, 0) == 1;
            return new ColdParentEvidence(parentRunsAfterHistory,
                    historyClaim && historyError != null
                            && historyError.contains("Canonical schedule is not available"),
                    parentCompleted && validParentPayloads == 2,
                    historyRetryClaimed && historyCompleted,
                    historyReusedCanonicalPair, gets);
        }
    }

    private static void clearOwnedRows() {
        jdbc.execute("TRUNCATE " + TABLES.playerFixtureEvidence() + "," + TABLES.fixtureSync() + ","
                + TABLES.historyFingerprint() + "," + TABLES.payload() + "," + TABLES.run() + ","
                + TABLES.receipt() + " CASCADE");
        jdbc.update("DELETE FROM " + TABLES.plannerSlot());
        jdbc.update("DELETE FROM " + TABLES.plannerRuntime());
        jdbc.update("DELETE FROM " + TABLES.requestAttempt());
        jdbc.update("UPDATE " + TABLES.requestState() + " SET last_admitted_at=NULL,cooldown_until=NULL");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static String offset(Instant value) {
        return value == null ? "none" : String.format("%.3f", Duration.between(START, value).toNanos() / 1e9);
    }

    private record Metrics(int firstWorker, int firstAdmissionWorker, Instant firstHistoryGet,
                           Instant firstHistoryCompleted, Set<Integer> completedPlayerIds,
                           int historiesWithin60, int stagedFixtures, int claims, int deferrals,
                           int admittedAttempts,
                           Map<EndpointKind, Integer> getsByEndpoint, int payloadRows, long stagedRawBytes,
                           int returnedGets, long returnedRawBytes, int newPartialBootstraps,
                           int peakNewPartialPairs, int dispatches, int predecessorWaits,
                           int spacingWaits, double virtualSeconds, PayloadReadProbe.Snapshot payloadReads) { }



    private record SeededFixture(int fixtureId, long generation) { }

    private enum DriverGoalKind { VERIFIED_FIXTURES, PENDING_FIXTURE_RUNS, FIRST_PENDING_OBSERVATION }

    private record DriverGoal(Set<Integer> fixtureIds, DriverGoalKind kind) {
        static DriverGoal fixtures(Set<Integer> ids) {
            return new DriverGoal(Set.copyOf(ids), DriverGoalKind.VERIFIED_FIXTURES);
        }
        static DriverGoal firstPendingFixtures(Set<Integer> ids) {
            return new DriverGoal(Set.copyOf(ids), DriverGoalKind.FIRST_PENDING_OBSERVATION);
        }
    }

    private record FirstPendingObservation(long generation, int seasonId, String seasonCode,
                                           Long currentVerifiedGeneration, SourceRead reads, String reasons,
                                           FixtureVerification.Observation observation,
                                           int expectedParticipants, int receivedParticipants,
                                           int missingParticipants) { }

    private record FixtureSourceBatchProof(long generation, SourceRead interval, Instant requestedFloor,
                                           boolean valid, Map<String, Object> trace) { }

    private record FixtureSourcePairProof(FixtureSourceBatchProof first, FixtureSourceBatchProof second,
                                          boolean capturedFirstPending, boolean generationOrder,
                                          boolean independentIntervals, boolean activeEpochThree) {
        boolean valid() {
            return first.valid() && second.valid() && capturedFirstPending && generationOrder
                    && independentIntervals && activeEpochThree;
        }

        Map<String, Object> trace() {
            Map<String, Object> trace = new TreeMap<>();
            trace.put("firstPendingGeneration", first.generation());
            trace.put("currentReceiptGeneration", second.generation());
            trace.put("firstInterval", first.interval());
            trace.put("secondInterval", second.interval());
            trace.put("firstRequestedFloor", first.requestedFloor());
            trace.put("secondRequestedFloor", second.requestedFloor());
            trace.put("capturedFirstPendingMatches", capturedFirstPending);
            trace.put("generationOrder", generationOrder);
            trace.put("independentIntervals", independentIntervals);
            trace.put("activeEpochThree", activeEpochThree);
            trace.put("pairProof", valid());
            trace.put("firstSourceProof", first.valid());
            trace.put("secondSourceProof", second.valid());
            trace.put("firstProof", first.trace());
            trace.put("secondProof", second.trace());
            return java.util.Collections.unmodifiableMap(trace);
        }
    }

    private record FixtureOwnershipSnapshot(String state, String observation, String observationCoherent,
                                            String observationFingerprint, Long observationGeneration,
                                            Long currentVerifiedGeneration, Long lastVerifiedGeneration,
                                            String verifiedHeader, String verifiedStatsFingerprint,
                                            String validationFailures, Instant nextDueAt) {
        String flags() {
            return "{state=" + state + ", observation=" + (observation == null ? "NULL" : "present")
                    + ", coherent=" + observationCoherent + ", fingerprint=" + observationFingerprint
                    + ", observation_generation=" + observationGeneration
                    + ", current_verified_generation=" + currentVerifiedGeneration
                    + ", last_verified_generation=" + lastVerifiedGeneration
                    + ", verified_header=" + verifiedHeader + ", verified_stats=" + verifiedStatsFingerprint
                    + ", validation_failures=" + validationFailures + ", next_due_at=" + nextDueAt + "}";
        }
    }

    private record FixturePublicationComponents(String receiptIdentity, Long latestGeneration,
                                                boolean verificationValid, List<String> pfsRows,
                                                List<String> evidenceRows, Map<String, Object> sourceHeader,
                                                Map<String, Object> capturedHeader,
                                                List<Long> verificationRunGenerations,
                                                FixtureOwnershipSnapshot ownership) { }

    private record RootDocuments(CatalogueScheduleParser.SourceDocument bootstrap,
                                 CatalogueScheduleParser.SourceDocument fixtures) { }

    private record ParentSourceDiagnostic(long sourceGeneration, Long publicationGeneration,
                                          long bootstrapPayloadId, long fixturesPayloadId,
                                          SourceRead bootstrapRead, SourceRead fixturesRead,
                                          boolean fixtureFinished, String sourceFingerprint,
                                          String fixtureFingerprint) { }

    private record ParentSelectionDiagnostic(ParentSourceDiagnostic newestValidCandidate,
                                              ParentSourceDiagnostic currentPublishedParent,
                                              int parseableCandidatesVisited) { }

    private record FixtureHeaderState(Map<String, Object> core, Map<String, Object> captured) { }
    private record FixtureSourceState(String headerFingerprint, String statsFingerprint,
                                      Integer homeScore, Integer awayScore) { }

    private record FixtureOracle(String headerFingerprint, String statsFingerprint,
                                 String observationFingerprint, int gameweek,
                                 Set<Integer> requiredPlayers, int expectedParticipants,
                                 int receivedParticipants) { }


    private record ColdParentEvidence(int pendingParents, boolean historyDeferred,
                                      boolean canonicalParentCompleted, boolean historyCompleted,
                                      boolean historyReusedCanonicalPair,
                                      Map<EndpointKind, Integer> getsByEndpoint) { }

    private record DispatchCompletion(int worker, boolean claimed, Instant completedAt) { }

    private record CanonicalParentProof(String publicationKey, long publicationGeneration,
                                        long sourceGeneration, long bootstrapPayloadId,
                                        long fixturesPayloadId, String sourceFingerprint,
                                        boolean currentPublished, boolean immutable,
                                        Map<String, Object> trace) {
        private static CanonicalParentProof missing() {
            return new CanonicalParentProof("missing", 0, 0, 0, 0, null, false, false,
                    Map.of("canonicalLane", "missing", "currentPublished", false, "immutable", false));
        }
    }

    private record AdmissionTrace(int ordinal, long generation, String worker,
                                  EndpointKind endpoint, String path, Instant admittedAt,
                                  Instant claimedAt, int attemptCount, String kind,
                                  String publicationKey) { }

    private record ControlledDispatchTrace(int ordinal, boolean claimed, long generation,
                                           SyncKind kind, Instant completedAt) { }

    private record RunProgress(int attemptCount, String state, Instant lastAttemptAt, Instant completedAt) { }




    private record FixtureSeed(long generation, List<Integer> requiredPlayers, Instant historyFloor) { }

    private record FixturePressureSeed(FixtureSeed ready, FixtureSeed untouched) { }

    private record MaturedFixturePressureSeed(FixtureSeed ready, FixtureSeed untouched) { }

    private record FocusMetrics(long readyGeneration, int readySeedAttemptCount,
                                int readyFirstClaimAttemptCount, int readyAttemptCount,
                                Instant readyClaimedAt, Instant readyCompletedAt,
                                int completedThroughReadyClaim, List<String> readyRequestPaths,
                                List<Integer> readyRequestOrdinals, int admissionsThroughFinalRecheck,
                                long untouchedGeneration, int untouchedSeedAttemptCount,
                                int untouchedFirstClaimAttemptCount, int untouchedAttemptCount,
                                Instant untouchedClaimedAt, int completedThroughUntouchedClaim,
                                int driverClaims, int driverDispatches,
                                Map<EndpointKind, Integer> getsByEndpoint, double virtualSeconds) { }

    private record ClaimEvidence(int attemptCount, Instant claimedAt) { }

    private record ClaimAttempt(long generation, int attemptCount) { }

    private record ScheduledResponse(RequestArrival arrival, Instant releaseAt) { }

    private record HistoryCompletion(String publicationKey, Instant completedAt) {
        int playerId() {
            return Integer.parseInt(publicationKey.substring("player-history:".length()));
        }
    }

    private static final class Harness implements AutoCloseable {
        private final int firstWorker;
        private final long wallDeadline;
        private final long wallStartedAtNanos;
        private final long cleanupDeadline;
        private final boolean finiteDrain;
        private final SourceData source;
        private final boolean cleanOnClose;
        private final String instanceId = "admission-repro";
        private volatile String finiteStopReason;
        private final MutableClock clock;
        private final RuntimeFence fence;
        private final PlannerRuntimeRepository activation;
        private final OwnedSyncStore store;
        private final PayloadReadProbe payloadReads;
        private final OwnedSyncRequestBudget budget;
        private final OwnedSyncDbOperations dbOperations;
        private final FixtureSyncPublisher publisher;
        private final CatalogueScheduleDispatcher catalogue;
        private final HistoryFixtureDispatcher histories;
        private final List<AdmissionTrace> singleDispatchAdmissions = new ArrayList<>();
        private final List<ControlledDispatchTrace> controlledDispatchTrace = new ArrayList<>();
        private int singleAdmissionOrdinal;
        private int controlledDispatchCalls;
        private final AtomicLong turnNanoTime = new AtomicLong(System.nanoTime());
        private final RequestServer server;
        private final OwnedSyncHttpClient http;
        private final OwnedMatchdayCoordinator coordinator;
        private final ExecutorService dispatchWorkers = Executors.newFixedThreadPool(2,
                Thread.ofPlatform().name("admission-test-worker-", 0).factory());
        private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("admission-test-watchdog").daemon(true).factory());
        private final AtomicBoolean clientClosed = new AtomicBoolean();
        private final AtomicBoolean watchdogFired = new AtomicBoolean();
        private final ScheduledFuture<?> watchdogTask;
        private final Set<Long> seededPartialGenerations = new HashSet<>();
        private final Set<ClaimAttempt> countedDeferrals = new HashSet<>();
        private final Map<String, Integer> deferralReasons = new HashMap<>();
        private final List<SeededFixture> seededFixtureRuns = new ArrayList<>();
        private Map<Integer, Set<Integer>> requiredHistoryPlayersByFixture = Map.of();
        private Set<Integer> seededHistoryPlayerIds = Set.of();

        Harness(int firstWorker, long wallDeadline) throws Exception {
            this(firstWorker, wallDeadline, wallDeadline - WALL_LIMIT_NANOS, false,
                    OwnedMatchdayAdmissionProgressIntegrationTest.source);
        }

        Harness(int firstWorker, long wallDeadline, long wallStartedAtNanos) throws Exception {
            this(firstWorker, wallDeadline, wallStartedAtNanos, true,
                    OwnedMatchdayAdmissionProgressIntegrationTest.source);
        }

        Harness(int firstWorker, long wallDeadline, long wallStartedAtNanos, SourceData source) throws Exception {
            this(firstWorker, wallDeadline, wallStartedAtNanos, true, source);
        }

        private Harness(int firstWorker, long wallDeadline, long wallStartedAtNanos,
                        boolean finiteDrain, SourceData source) throws Exception {
            this(firstWorker, wallDeadline, wallStartedAtNanos, finiteDrain, source,
                    new MutableClock(START), OwnedSyncMode.STAGING, true, true);
        }

        static Harness active(int firstWorker, long wallDeadline, long wallStartedAtNanos,
                              SourceData source, MutableClock clock,
                              boolean clearBeforeActivate, boolean cleanOnClose) throws Exception {
            return new Harness(firstWorker, wallDeadline, wallStartedAtNanos, true, source,
                    clock, OwnedSyncMode.ACTIVE, clearBeforeActivate, cleanOnClose);
        }

        private Harness(int firstWorker, long wallDeadline, long wallStartedAtNanos,
                        boolean finiteDrain, SourceData source, MutableClock clock,
                        OwnedSyncMode mode, boolean clearBeforeActivate, boolean cleanOnClose) throws Exception {
            this.firstWorker = firstWorker;
            this.wallDeadline = wallDeadline;
            this.wallStartedAtNanos = wallStartedAtNanos;
            this.finiteDrain = finiteDrain;
            this.source = source;
            this.clock = clock;
            this.cleanOnClose = cleanOnClose;
            this.cleanupDeadline = wallDeadline + (finiteDrain ? DRAIN_CLEANUP_RESERVE_NANOS : 0);
            if (clearBeforeActivate) clearOwnedRows();
            activation = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES);
            fence = activation.activate(SEASON.code(), mode);
            payloadReads = new PayloadReadProbe(jdbc.getDataSource());
            payloadReads.setQueryTimeout(10);
            store = new OwnedSyncStore(payloadReads, tx, clock, TABLES, fence);
            budget = new OwnedSyncRequestBudget(jdbc, tx, clock, TABLES);
            dbOperations = new OwnedSyncDbOperations();
            server = new RequestServer(source, clock);
            http = new OwnedSyncHttpClient(budget, server.baseUri(), Duration.ofSeconds(30), clock,
                    dbOperations, Duration.ofSeconds(180), turnNanoTime::get);
            publisher = new FixtureSyncPublisher(jdbc, clock, store,
                    new FixtureSyncRepository(jdbc, TABLES), TABLES);
            if (mode == OwnedSyncMode.ACTIVE) {
                catalogue = new CatalogueScheduleDispatcher(store,
                        new CatalogueScheduleWriter(jdbc, clock, store, TABLES, CAPTURED), http, clock);
                histories = new HistoryFixtureDispatcher(store, publisher, http, clock);
            } else {
                catalogue = CatalogueScheduleDispatcher.staging(store, http, clock);
                histories = HistoryFixtureDispatcher.staging(store, http, clock);
            }
            PlannerRuntimeRepository runtime = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES, fence);
            PlannerInputRepository inputs = new PlannerInputRepository(jdbc, store, TABLES, CAPTURED, clock);
            coordinator = new OwnedMatchdayCoordinator(SEASON, new AdaptiveMatchdayPlanner(clock), inputs,
                    runtime, store, catalogue, histories, instanceId);
            long watchdogDelay = Math.max(1, wallDeadline - System.nanoTime());
            watchdogTask = watchdog.schedule(this::emergencyStop, watchdogDelay, TimeUnit.NANOSECONDS);
        }

        void resetPayloadReads() {
            payloadReads.reset();
        }

        void dispatchCanonicalCycle() throws Exception {
            catalogue.enqueueCycle(SEASON, clock.instant());
            dispatchDueUntilIdle("baseline-canonical");
        }

        int dispatchDueUntilIdle(String workerPrefix) throws Exception {
            int dispatched = 0;
            for (int index = 0; index < ACTIVE_RECOVERY_DISPATCH_LIMIT; index++) {
                int claimed = dispatchOneWithControlledResponse(workerPrefix + "-" + index);
                if (claimed == 0) return dispatched;
                dispatched++;
            }
            throw new AssertionError("dispatcher exceeded its declared bound: "
                    + workerPrefix + " dispatched=" + dispatched + " now=" + clock.instant());
        }

        int dispatchCanonicalDueUntilIdle(String workerPrefix) throws Exception {
            int dispatched = 0;
            for (int index = 0; index < ACTIVE_RECOVERY_DISPATCH_LIMIT; index++) {
                int dueCanonical = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                        + " WHERE season=? AND runtime_mode=? AND runtime_epoch=?"
                        + " AND kind='CATALOGUE_SCHEDULE' AND due_at<=? AND requested_source_after<=?"
                        + " AND (state='PENDING' OR (state='LEASED' AND lease_expires_at<=?))",
                        Integer.class, SEASON.code(), fence.mode().name(), fence.epoch(),
                        Timestamp.from(clock.instant()), Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
                if (dueCanonical == 0) return dispatched;
                int claimed = dispatchOneWithControlledResponse(workerPrefix + "-" + index);
                if (claimed == 0) return dispatched;
                dispatched++;
            }
            throw new AssertionError("canonical dispatcher exceeded its declared bound: "
                    + workerPrefix + " dispatched=" + dispatched + " now=" + clock.instant());
        }

        void seedNewerUnpublishedCanonicalPairWithoutAdvancingDemand() throws IOException {
            Instant sourceFloor = BASELINE_SOURCE_START;
            clock.advance(Duration.ofMillis(1));
            Instant now = clock.instant();
            long generation = jdbc.queryForObject("INSERT INTO " + TABLES.run()
                    + "(kind,season,coalescing_key,publication_key,priority,due_at,requested_source_after,"
                    + "runtime_mode,runtime_epoch,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?) "
                    + "RETURNING generation", Long.class, SyncKind.CATALOGUE_SCHEDULE.name(), SEASON.code(),
                    CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                    CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY, 0, Timestamp.from(now),
                    Timestamp.from(sourceFloor), fence.mode().name(), fence.epoch(), Timestamp.from(now),
                    Timestamp.from(now));
            Lease lease = store.acquireForSeason(SEASON.code(), "seed-unpublished-canonical",
                    Duration.ofMinutes(5)).orElseThrow();
            assertThat(lease.generation()).isEqualTo(generation);
            SourceRead read = new SourceRead(clock.instant(), clock.instant());
            CatalogueScheduleParser.parse(SEASON,
                    new CatalogueScheduleParser.SourceDocument(source.bootstrapBytes(),
                            new CatalogueScheduleParser.ReadInterval(read.startedAt(), read.finishedAt())),
                    new CatalogueScheduleParser.SourceDocument(source.fixtureBytes(),
                            new CatalogueScheduleParser.ReadInterval(read.startedAt(), read.finishedAt())));
            long bootstrapId = store.stage(lease, OwnedSyncStore.Endpoint.bootstrap(), read,
                    Validation.unchecked(), source.bootstrapBytes());
            long fixturesId = store.stage(lease, OwnedSyncStore.Endpoint.fixtures(), read,
                    Validation.unchecked(), source.fixtureBytes());
            store.validateStagedPayloads(lease, List.of(bootstrapId, fixturesId), Validation.valid());
            store.completeStaged(lease, read, List.of(read, read));
        }

        void seedCanonicalPair() {
            CatalogueScheduleDispatcher catalogue = CatalogueScheduleDispatcher.staging(store, http, clock);
            var run = catalogue.enqueueCatalogue(SEASON, clock.instant());
            Lease lease = store.acquireForSeason(SEASON.code(), "seed-canonical", Duration.ofMinutes(5))
                    .orElseThrow();
            assertThat(lease.generation()).isEqualTo(run.generation());
            SourceRead read = new SourceRead(clock.instant(), clock.instant());
            CatalogueScheduleParser.parse(SEASON,
                    new CatalogueScheduleParser.SourceDocument(source.bootstrapBytes(),
                            new CatalogueScheduleParser.ReadInterval(read.startedAt(), read.finishedAt())),
                    new CatalogueScheduleParser.SourceDocument(source.fixtureBytes(),
                            new CatalogueScheduleParser.ReadInterval(read.startedAt(), read.finishedAt())));
            long bootstrapId = store.stage(lease, OwnedSyncStore.Endpoint.bootstrap(), read,
                    Validation.unchecked(), source.bootstrapBytes());
            long fixturesId = store.stage(lease, OwnedSyncStore.Endpoint.fixtures(), read,
                    Validation.unchecked(), source.fixtureBytes());
            store.validateStagedPayloads(lease, List.of(bootstrapId, fixturesId), Validation.valid());
            store.completeStaged(lease, read, List.of(read, read));
        }


        void seedMixedBacklog() {
            var histories = HistoryFixtureDispatcher.staging(store, http, clock);
            requiredHistoryPlayersByFixture = source.requiredHistoryPlayersByFixture(FIXTURE_RUNS);
            var waiting = histories.enqueueFixtureVerification(SEASON, FIXTURE_RUNS, clock.instant());
            seededFixtureRuns.add(new SeededFixture(FIXTURE_RUNS, waiting.generation()));
            Lease waitingLease = store.acquireForSeason(SEASON.code(), "seed-dependency", Duration.ofMinutes(5))
                    .orElseThrow();
            assertThat(waitingLease.generation()).isEqualTo(waiting.generation());
            store.retryForDependency(waitingLease, clock.instant(), "Waiting for seeded player histories");

            List<Long> fixtureGenerations = new ArrayList<>();
            for (int fixtureId = 1; fixtureId < FIXTURE_RUNS; fixtureId++) {
                long generation = histories.enqueueFixtureVerification(
                        SEASON, fixtureId, clock.instant()).generation();
                fixtureGenerations.add(generation);
                seededFixtureRuns.add(new SeededFixture(fixtureId, generation));
            }
            assertThat(fixtureGenerations).hasSize(49);
            for (int index = 0; index < SEEDED_PARTIALS; index++) {
                Lease lease = store.acquireForSeason(SEASON.code(), "seed-partial-" + index,
                        Duration.ofMinutes(5)).orElseThrow();
                assertThat(lease.kind()).isEqualTo(SyncKind.FIXTURE_VERIFICATION);
                SourceRead read = new SourceRead(clock.instant(), clock.instant());
                store.stage(lease, OwnedSyncStore.Endpoint.bootstrap(), read,
                        Validation.unchecked(), source.bootstrapBytes());
                store.retry(lease, clock.instant().plus(Duration.ofHours(1)),
                        "Seeded usable partial bootstrap checkpoint");
                seededPartialGenerations.add(lease.generation());
            }
            for (long generation : seededPartialGenerations) {
                jdbc.update("UPDATE " + TABLES.run() + " SET due_at=? WHERE generation=?",
                        Timestamp.from(clock.instant()), generation);
            }
            assertThat(source.playerIds()).as("history jobs follow the coordinator's player-id ordering")
                    .hasSize(source.fullRoster() ? FULL_ROSTER_PLAYERS : HISTORY_RUNS);
            Set<Integer> requiredPlayers = requiredHistoryPlayersByFixture.values().stream()
                    .flatMap(Set::stream).collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
            List<Integer> historiesToEnqueue = new ArrayList<>(source.playerIds());
            Set<Integer> historiesToSeed = new HashSet<>(source.playerIds());
            requiredPlayers.stream().sorted().filter(historiesToSeed::add).forEach(historiesToEnqueue::add);
            historiesToEnqueue.forEach(playerId -> source.historyBytes(playerId));
            seededHistoryPlayerIds = Set.copyOf(historiesToSeed);
            for (int playerId : historiesToEnqueue) {
                histories.enqueuePlayerHistory(SEASON, playerId, clock.instant());
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                    + " WHERE season=? AND kind='FIXTURE_VERIFICATION'", Integer.class, SEASON.code()))
                    .isEqualTo(FIXTURE_RUNS);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                    + " WHERE season=? AND kind='PLAYER_HISTORY' AND state='PENDING'",
                    Integer.class, SEASON.code())).isEqualTo(seededHistoryPlayerIds.size());
            assertThat(seededFixtureRuns).hasSize(FIXTURE_RUNS);
            assertThat(seededHistoryPlayerIds).containsAll(requiredPlayers);
            assertThat(seededPartialGenerations).hasSize(SEEDED_PARTIALS);
        }

        FixturePressureSeed seedReadyFixturePressure() {
            var pairRead = new SourceRead(clock.instant(), clock.instant());
            var pairInterval = new CatalogueScheduleParser.ReadInterval(
                    pairRead.startedAt(), pairRead.finishedAt());
            var snapshot = CatalogueScheduleParser.parse(SEASON,
                    new CatalogueScheduleParser.SourceDocument(source.bootstrapBytes(), pairInterval),
                    new CatalogueScheduleParser.SourceDocument(source.fixtureBytes(), pairInterval));
            var histories = HistoryFixtureDispatcher.staging(store, http, clock);

            FixtureSeed ready = seedFixtureRetry(histories, snapshot, 1, START.minusSeconds(10), "seed-ready");
            List<FixtureSeed> blocked = new ArrayList<>();
            for (int fixtureId = 2; fixtureId <= 9; fixtureId++) {
                blocked.add(seedFixtureRetry(histories, snapshot, fixtureId,
                        START.minusSeconds(60L - fixtureId), "seed-blocked-" + fixtureId));
            }
            assertThat(blocked).hasSize(8);

            for (int playerId : ready.requiredPlayers()) {
                seedValidHistory(snapshot, playerId, ready.historyFloor());
            }
            Set<Integer> readyPlayers = Set.copyOf(ready.requiredPlayers());
            Set<Integer> validHistoryPlayers = Set.copyOf(jdbc.queryForList("SELECT DISTINCT p.player_id FROM "
                    + TABLES.payload() + " p JOIN " + TABLES.run() + " r ON r.generation=p.run_generation"
                    + " WHERE r.kind='PLAYER_HISTORY' AND r.season=? AND p.endpoint_kind='ELEMENT_SUMMARY'"
                    + " AND p.validation_state='VALID'", Integer.class, SEASON.code()));
            for (FixtureSeed fixture : blocked) {
                Integer[] requiredIds = jdbc.queryForObject("SELECT required_player_ids FROM "
                                + TABLES.fixtureDependencyManifest() + " WHERE run_generation=?",
                        (rs, row) -> (Integer[]) rs.getArray(1).getArray(), fixture.generation());
                assertThat(java.util.Arrays.stream(requiredIds).anyMatch(id -> !validHistoryPlayers.contains(id)))
                        .as("fixture generation %d has a required player without valid history", fixture.generation())
                        .isTrue();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                        + " WHERE run_generation=? AND endpoint_kind IN ('BOOTSTRAP_STATIC','FIXTURES','EVENT_LIVE')"
                        + " AND validation_state='VALID'", Integer.class, fixture.generation())).isEqualTo(3);
                assertThat(jdbc.queryForObject("SELECT fpl_sync_required_ids_valid(required_player_ids) FROM "
                        + TABLES.fixtureDependencyManifest() + " WHERE run_generation=?",
                        Boolean.class, fixture.generation())).isTrue();
            }
            for (int playerId : source.playerIds()) {
                if (!readyPlayers.contains(playerId)) {
                    histories.enqueuePlayerHistory(SEASON, playerId, clock.instant());
                }
            }
            FixtureSeed untouched = new FixtureSeed(
                    histories.enqueueFixtureVerification(SEASON, 10, START.minusSeconds(20)).generation(),
                    List.of(), null);

            Set<Long> readyGenerations = store.readyFixtureGenerations(SEASON.code(), FIXTURE_RUNS).stream()
                    .map(OwnedSyncStore.Run::generation).collect(java.util.stream.Collectors.toSet());
            assertThat(readyGenerations).contains(ready.generation());
            blocked.forEach(fixture -> assertThat(readyGenerations)
                    .as("fixture:%d has a real manifest but still lacks valid required history", fixture.generation())
                    .doesNotContain(fixture.generation()));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.fixtureDependencyManifest()
                    + " WHERE run_generation IN (" + String.join(",", blocked.stream()
                    .map(fixture -> Long.toString(fixture.generation())).toList()) + ")",
                    Integer.class)).isEqualTo(8);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                    + " WHERE run_generation=? AND validation_state='VALID'",
                    Integer.class, ready.generation())).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.fixtureDependencyManifest()
                    + " WHERE run_generation=? AND fpl_sync_required_ids_valid(required_player_ids)",
                    Integer.class, ready.generation())).isOne();
            clock.advance(Duration.ofMillis(1));
            return new FixturePressureSeed(ready, untouched);
        }

        MaturedFixturePressureSeed seedMaturedReadyFixturePressure() {
            var pairRead = new SourceRead(clock.instant(), clock.instant());
            var pairInterval = new CatalogueScheduleParser.ReadInterval(
                    pairRead.startedAt(), pairRead.finishedAt());
            var snapshot = CatalogueScheduleParser.parse(SEASON,
                    new CatalogueScheduleParser.SourceDocument(source.bootstrapBytes(), pairInterval),
                    new CatalogueScheduleParser.SourceDocument(source.fixtureBytes(), pairInterval));
            var histories = HistoryFixtureDispatcher.staging(store, http, clock);

            Instant dependencyDueAt = START.plus(Duration.ofMinutes(2));
            FixtureSeed ready = seedFixtureRetryThroughDispatcher(
                    histories, snapshot, 1, "seed-matured-ready");
            List<FixtureSeed> blocked = new ArrayList<>();
            for (int fixtureId = 2; fixtureId <= 9; fixtureId++) {
                Instant blockedDueAt = START.minusSeconds(60L - fixtureId);
                blocked.add(seedFixtureRetry(histories, snapshot, fixtureId,
                        blockedDueAt, blockedDueAt, "seed-matured-blocked-" + fixtureId));
            }
            assertThat(blocked).hasSize(8);
            blocked.forEach(fixture -> assertThat(jdbc.queryForObject("SELECT due_at FROM " + TABLES.run()
                    + " WHERE generation=?", Timestamp.class, fixture.generation()).toInstant())
                    .as("blocked retry %d is due before the matured retry", fixture.generation())
                    .isBefore(dependencyDueAt));
            assertThat(jdbc.queryForObject("SELECT requested_source_after FROM " + TABLES.run()
                    + " WHERE generation=?", Timestamp.class, ready.generation()).toInstant()).isEqualTo(START);
            Instant recordedDependencyDueAt = jdbc.queryForObject("SELECT due_at FROM " + TABLES.run()
                    + " WHERE generation=?", Timestamp.class, ready.generation()).toInstant();
            assertThat(recordedDependencyDueAt).isEqualTo(ready.historyFloor().plus(Duration.ofMinutes(2)));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                    + " WHERE run_generation=? AND endpoint_kind IN ('BOOTSTRAP_STATIC','FIXTURES','EVENT_LIVE') "
                    + "AND validation_state='VALID'", Integer.class, ready.generation())).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT fpl_sync_required_ids_valid(required_player_ids) FROM "
                    + TABLES.fixtureDependencyManifest() + " WHERE run_generation=?",
                    Boolean.class, ready.generation())).isTrue();
            for (int playerId : ready.requiredPlayers()) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                        + " p JOIN " + TABLES.run() + " r ON r.generation=p.run_generation"
                        + " WHERE r.season=? AND r.kind='PLAYER_HISTORY' AND p.player_id=? "
                        + "AND p.endpoint_kind='ELEMENT_SUMMARY' AND p.validation_state='VALID'",
                        Integer.class, SEASON.code(), playerId))
                        .as("required player %d has no history when the fixture enters dependency retry", playerId)
                        .isZero();
            }

            Instant historiesCompletedAt = START.plus(Duration.ofMinutes(1));
            advanceControlledTime(historiesCompletedAt);
            for (int playerId : ready.requiredPlayers()) {
                seedValidHistory(snapshot, playerId, ready.historyFloor());
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                        + " p JOIN " + TABLES.run() + " r ON r.generation=p.run_generation"
                        + " WHERE r.season=? AND r.kind='PLAYER_HISTORY' AND r.state='COMPLETED' "
                        + "AND p.player_id=? AND p.endpoint_kind='ELEMENT_SUMMARY' "
                        + "AND p.validation_state='VALID' AND p.read_started_at>=?",
                        Integer.class, SEASON.code(), playerId, Timestamp.from(historiesCompletedAt)))
                        .as("required player %d history is completed from the later one-minute read", playerId)
                        .isOne();
            }
            Set<Integer> readyPlayers = Set.copyOf(ready.requiredPlayers());
            Set<Integer> validHistoryPlayers = Set.copyOf(jdbc.queryForList("SELECT DISTINCT p.player_id FROM "
                    + TABLES.payload() + " p JOIN " + TABLES.run() + " r ON r.generation=p.run_generation"
                    + " WHERE r.kind='PLAYER_HISTORY' AND r.season=? AND p.endpoint_kind='ELEMENT_SUMMARY'"
                    + " AND p.validation_state='VALID'", Integer.class, SEASON.code()));
            for (FixtureSeed fixture : blocked) {
                Integer[] requiredIds = jdbc.queryForObject("SELECT required_player_ids FROM "
                                + TABLES.fixtureDependencyManifest() + " WHERE run_generation=?",
                        (rs, row) -> (Integer[]) rs.getArray(1).getArray(), fixture.generation());
                assertThat(java.util.Arrays.stream(requiredIds).anyMatch(id -> !validHistoryPlayers.contains(id)))
                        .as("earlier fixture generation %d still lacks a required history", fixture.generation())
                        .isTrue();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                        + " WHERE run_generation=? AND endpoint_kind IN ('BOOTSTRAP_STATIC','FIXTURES','EVENT_LIVE')"
                        + " AND validation_state='VALID'", Integer.class, fixture.generation())).isEqualTo(3);
            }
            Set<Long> readyBeforeDue = store.readyFixtureGenerations(SEASON.code(), FIXTURE_RUNS).stream()
                    .map(OwnedSyncStore.Run::generation).collect(java.util.stream.Collectors.toSet());
            assertThat(readyBeforeDue).as("validated histories do not make the retry claimable before its due time")
                    .doesNotContain(ready.generation());
            blocked.forEach(fixture -> assertThat(readyBeforeDue)
                    .as("earlier due retry %d remains blocked on missing history", fixture.generation())
                    .doesNotContain(fixture.generation()));

            for (int playerId : source.playerIds()) {
                if (!readyPlayers.contains(playerId)) {
                    histories.enqueuePlayerHistory(SEASON, playerId, clock.instant());
                }
            }
            FixtureSeed untouched = new FixtureSeed(
                    histories.enqueueFixtureVerification(SEASON, 10, START.minusSeconds(20)).generation(),
                    List.of(), null);

            Instant recordedDueAt = jdbc.queryForObject("SELECT due_at FROM " + TABLES.run()
                    + " WHERE generation=?", Timestamp.class, ready.generation()).toInstant();
            assertThat(recordedDueAt).isEqualTo(recordedDependencyDueAt);
            advanceControlledTime(recordedDueAt.minusMillis(1));
            Set<Long> readyImmediatelyBeforeDue = store.readyFixtureGenerations(SEASON.code(), FIXTURE_RUNS).stream()
                    .map(OwnedSyncStore.Run::generation).collect(java.util.stream.Collectors.toSet());
            assertThat(readyImmediatelyBeforeDue)
                    .as("validated histories do not make the dependency retry ready before its recorded due")
                    .doesNotContain(ready.generation());
            advanceControlledTime(recordedDueAt);
            Set<Long> readyAfterDue = store.readyFixtureGenerations(SEASON.code(), FIXTURE_RUNS).stream()
                    .map(OwnedSyncStore.Run::generation).collect(java.util.stream.Collectors.toSet());
            assertThat(readyAfterDue).as("dependency retry becomes claimable at its recorded two-minute due")
                    .contains(ready.generation());
            blocked.forEach(fixture -> assertThat(readyAfterDue)
                    .as("earlier due retry %d still lacks at least one valid history", fixture.generation())
                    .doesNotContain(fixture.generation()));
            return new MaturedFixturePressureSeed(ready, untouched);
        }

        private FixtureSeed seedFixtureRetry(HistoryFixtureDispatcher histories,
                                             CatalogueScheduleParser.Snapshot snapshot,
                                             int fixtureId, Instant dueAt, String owner) {
            return seedFixtureRetry(histories, snapshot, fixtureId, dueAt, dueAt, owner);
        }

        private FixtureSeed seedFixtureRetryThroughDispatcher(HistoryFixtureDispatcher histories,
                                                               CatalogueScheduleParser.Snapshot snapshot,
                                                               int fixtureId, String owner) {
            return seedFixtureRetry(histories, snapshot, fixtureId, clock.instant(), clock.instant(), owner, true);
        }

        private FixtureSeed seedFixtureRetry(HistoryFixtureDispatcher histories,
                                             CatalogueScheduleParser.Snapshot snapshot,
                                             int fixtureId, Instant demandedAt, Instant retryDueAt, String owner) {
            return seedFixtureRetry(histories, snapshot, fixtureId, demandedAt, retryDueAt, owner, false);
        }

        private FixtureSeed seedFixtureRetry(HistoryFixtureDispatcher histories,
                                             CatalogueScheduleParser.Snapshot snapshot,
                                             int fixtureId, Instant demandedAt, Instant retryDueAt, String owner,
                                             boolean processDependencyWait) {
            var run = histories.enqueueFixtureVerification(SEASON, fixtureId, demandedAt);
            Lease lease = store.acquireForSeason(SEASON.code(), owner, Duration.ofMinutes(5)).orElseThrow();
            assertThat(lease.generation()).isEqualTo(run.generation());
            assertThat(lease.kind()).isEqualTo(SyncKind.FIXTURE_VERIFICATION);

            SourceRead pairRead = new SourceRead(clock.instant(), clock.instant());
            var pairInterval = new CatalogueScheduleParser.ReadInterval(
                    pairRead.startedAt(), pairRead.finishedAt());
            byte[] bootstrapBytes = source.bootstrapBytes();
            byte[] fixtureBytes = source.fixtureBytes();
            long bootstrapId = store.stage(lease, OwnedSyncStore.Endpoint.bootstrap(), pairRead,
                    Validation.unchecked(), bootstrapBytes);
            long fixturesId = store.stage(lease, OwnedSyncStore.Endpoint.fixtures(), pairRead,
                    Validation.unchecked(), fixtureBytes);
            CatalogueScheduleParser.Snapshot parsed = CatalogueScheduleParser.parse(SEASON,
                    new CatalogueScheduleParser.SourceDocument(bootstrapBytes, pairInterval),
                    new CatalogueScheduleParser.SourceDocument(fixtureBytes, pairInterval));
            assertThat(parsed.fixtures()).containsAll(snapshot.fixtures());
            var fixture = parsed.fixtures().stream().filter(row -> row.id() == fixtureId).findFirst().orElseThrow();
            store.validateStagedPayloads(lease, List.of(bootstrapId, fixturesId), Validation.valid());

            SourceRead liveRead = new SourceRead(pairRead.finishedAt().plusMillis(1),
                    pairRead.finishedAt().plusMillis(1));
            if (clock.instant().isBefore(liveRead.startedAt())) advanceControlledTime(liveRead.startedAt());
            byte[] liveBytes = source.liveBytes(fixture.gameweek());
            var liveDocument = new CatalogueScheduleParser.SourceDocument(liveBytes,
                    new CatalogueScheduleParser.ReadInterval(liveRead.startedAt(), liveRead.finishedAt()));
            Set<Integer> required = new java.util.LinkedHashSet<>();
            parsed.players().stream().filter(player -> player.teamId() == fixture.homeTeamId()
                            || player.teamId() == fixture.awayTeamId())
                    .map(CatalogueScheduleParser.PlayerRow::id).forEach(required::add);
            required.addAll(FixtureVerification.participantIds(fixture, liveDocument));
            long liveId = store.stage(lease, OwnedSyncStore.Endpoint.eventLive(fixture.gameweek()), liveRead,
                    Validation.unchecked(), liveBytes);
            store.validateStagedPayloads(lease, List.of(liveId), Validation.valid());

            var storedPair = new OwnedSyncStore.PublicPayloadPair(run.generation(),
                    new OwnedSyncStore.StagedPayload(bootstrapId, run.generation(), EndpointKind.BOOTSTRAP_STATIC,
                            pairRead, ValidationState.VALID, null, bootstrapBytes),
                    new OwnedSyncStore.StagedPayload(fixturesId, run.generation(), EndpointKind.FIXTURES,
                            pairRead, ValidationState.VALID, null, fixtureBytes));
            var storedLive = new OwnedSyncStore.StagedPayload(liveId, run.generation(), EndpointKind.EVENT_LIVE,
                    liveRead, ValidationState.VALID, null, liveBytes);
            if (processDependencyWait) {
                Instant processingAt = clock.instant();
                assertThat(histories.process(lease, SEASON).outcome())
                        .isEqualTo(HistoryFixtureDispatcher.Outcome.WAITING_FOR_HISTORIES);
                assertThat(jdbc.queryForObject("SELECT due_at FROM " + TABLES.run()
                        + " WHERE generation=?", Timestamp.class, run.generation()).toInstant())
                        .isEqualTo(processingAt.plus(Duration.ofMinutes(2)));
            } else {
                store.retryForDependency(lease, retryDueAt,
                        "Seeded source-valid fixture retry missing required histories",
                        fixtureId, fixture.gameweek(), storedPair, storedLive, List.copyOf(required));
            }
            return new FixtureSeed(run.generation(), required.stream().sorted().toList(),
                    pairRead.finishedAt().plusMillis(1));
        }

        private void seedValidHistory(CatalogueScheduleParser.Snapshot snapshot, int playerId,
                                      Instant historyFloor) {
            var run = store.ensurePlayerHistoryAtLeast(SEASON.code(), playerId, historyFloor);
            Lease lease = store.acquireForSeason(SEASON.code(), "seed-history-" + playerId,
                    Duration.ofMinutes(5)).orElseThrow();
            assertThat(lease.generation()).isEqualTo(run.generation());
            byte[] body = source.historyBytes(playerId);
            SourceRead read = new SourceRead(clock.instant(), clock.instant());
            var document = new CatalogueScheduleParser.SourceDocument(body,
                    new CatalogueScheduleParser.ReadInterval(read.startedAt(), read.finishedAt()));
            HistoryParser.parse(SEASON, playerId, document, snapshot.fixtures().stream()
                    .map(HistoryParser::canonicalFixture).toList());
            long payloadId = store.stage(lease, OwnedSyncStore.Endpoint.elementSummary(playerId), read,
                    Validation.unchecked(), body);
            store.validateStagedPayloads(lease, List.of(payloadId), Validation.valid());
            store.completeStaged(lease, read, List.of(read));
        }

        FocusMetrics driveReadyFixturePressure(long readyGeneration, long untouchedGeneration) throws Exception {
            ManualDriver driver = new ManualDriver(this, readyGeneration, untouchedGeneration);
            http.setWaitObserverForTests(driver::observeWait);
            driver.run();
            RunProgress ready = progress(readyGeneration);
            RunProgress untouched = progress(untouchedGeneration);
            assertThat(ready.state()).as("ready fixture must return through the STAGED path")
                    .isEqualTo("COMPLETED");
            assertThat(ready.completedAt()).isNotNull();
            assertThat(driver.readyFirstClaim).as("first ready claim sampled at an acknowledged driver boundary")
                    .isNotNull();
            assertThat(driver.untouchedFirstClaim)
                    .as("first untouched claim sampled at an acknowledged driver boundary").isNotNull();
            ClaimEvidence readyClaim = driver.readyFirstClaim;
            ClaimEvidence untouchedClaim = driver.untouchedFirstClaim;
            List<AdmissionTrace> readyAdmissions = driver.admissions.stream()
                    .filter(admission -> admission.generation() == readyGeneration)
                    .sorted(java.util.Comparator.comparingInt(AdmissionTrace::ordinal)).toList();
            assertThat(readyAdmissions).hasSize(2);
            Instant lastFinalRecheck = readyAdmissions.getLast().admittedAt();
            int durableAdmissionsThroughFinalRecheck = jdbc.queryForObject("SELECT count(*) FROM "
                            + TABLES.requestAttempt() + " WHERE admitted_at>=? AND admitted_at<=?",
                    Integer.class, Timestamp.from(readyClaim.claimedAt()), Timestamp.from(lastFinalRecheck));
            int completedThroughReadyClaim = (int) driver.completions.stream()
                    .filter(completion -> !completion.completedAt().isAfter(readyClaim.claimedAt())).count();
            int completedThroughUntouchedClaim = (int) driver.completions.stream()
                    .filter(completion -> !completion.completedAt().isAfter(untouchedClaim.claimedAt())).count();
            return new FocusMetrics(readyGeneration, driver.readySeedAttemptCount,
                    readyClaim.attemptCount(), ready.attemptCount(), readyClaim.claimedAt(), ready.completedAt(),
                    completedThroughReadyClaim, readyAdmissions.stream().map(AdmissionTrace::path).toList(),
                    readyAdmissions.stream().map(AdmissionTrace::ordinal).toList(),
                    durableAdmissionsThroughFinalRecheck, untouchedGeneration,
                    driver.untouchedSeedAttemptCount, untouchedClaim.attemptCount(), untouched.attemptCount(),
                    untouchedClaim.claimedAt(), completedThroughUntouchedClaim, driver.claims,
                    driver.dispatches, server.getsByEndpoint(),
                    Duration.between(driver.startAt, clock.instant()).toNanos() / 1e9);
        }

        private RunProgress progress(long generation) {
            return jdbc.queryForObject("SELECT attempt_count,state,last_attempt_at,completed_at FROM "
                            + TABLES.run() + " WHERE generation=?",
                    (rs, row) -> {
                        Timestamp claimedAt = rs.getTimestamp(3);
                        Timestamp completedAt = rs.getTimestamp(4);
                        return new RunProgress(rs.getInt(1), rs.getString(2),
                                claimedAt == null ? null : claimedAt.toInstant(),
                                completedAt == null ? null : completedAt.toInstant());
                    }, generation);
        }

        void enqueueHistoryOnly() {
            HistoryFixtureDispatcher.staging(store, http, clock)
                    .enqueuePlayerHistory(SEASON, source.playerIds().getFirst(), clock.instant());
        }

        Metrics driveMixedBacklog() throws Exception {
            ManualDriver driver = new ManualDriver(this);
            http.setWaitObserverForTests(driver::observeWait);
            driver.run();
            return metrics(driver);
        }





        private Metrics metrics(ManualDriver driver) {
            Instant end = clock.instant();
            Instant historyLimit = START.plus(VIRTUAL_LIMIT);
            List<HistoryCompletion> completed = jdbc.query("SELECT publication_key,completed_at FROM "
                            + TABLES.run() + " WHERE season=? AND kind='PLAYER_HISTORY' AND state='COMPLETED' "
                            + "ORDER BY completed_at,generation",
                    (rs, row) -> new HistoryCompletion(rs.getString(1), rs.getTimestamp(2).toInstant()),
                    SEASON.code());
            Instant firstCompleted = completed.isEmpty() ? null : completed.getFirst().completedAt();
            Set<Integer> completedPlayers = completed.stream().map(HistoryCompletion::playerId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            Set<Integer> completedWithin60 = completed.stream()
                    .filter(row -> !row.completedAt().isAfter(historyLimit))
                    .map(HistoryCompletion::playerId).collect(java.util.stream.Collectors.toUnmodifiableSet());
            int completedFixtures = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                    + " WHERE season=? AND kind='FIXTURE_VERIFICATION' AND state='COMPLETED'",
                    Integer.class, SEASON.code());
            int payloadRows = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                    + " p JOIN " + TABLES.run() + " r ON r.generation=p.run_generation"
                    + " WHERE r.season=?", Integer.class, SEASON.code());
            long stagedBytes = jdbc.queryForObject("SELECT COALESCE(sum(octet_length(p.body)),0) FROM "
                    + TABLES.payload() + " p JOIN " + TABLES.run() + " r ON r.generation=p.run_generation"
                    + " WHERE r.season=?", Long.class, SEASON.code());
            List<Long> partialGenerations = jdbc.queryForList("SELECT r.generation FROM " + TABLES.run()
                    + " r WHERE r.season=? AND r.kind='FIXTURE_VERIFICATION'"
                    + " AND EXISTS (SELECT 1 FROM " + TABLES.payload() + " b WHERE b.run_generation=r.generation"
                    + " AND b.endpoint_kind='BOOTSTRAP_STATIC') AND NOT EXISTS (SELECT 1 FROM " + TABLES.payload()
                    + " f WHERE f.run_generation=r.generation AND f.endpoint_kind='FIXTURES')",
                    Long.class, SEASON.code());
            int partials = (int) partialGenerations.stream()
                    .filter(generation -> !seededPartialGenerations.contains(generation)).count();
            return new Metrics(firstWorker, driver.firstAdmissionWorker,
                    server.firstHistoryGet(), firstCompleted, completedPlayers, completedWithin60.size(),
                    completedFixtures, driver.claims, driver.deferrals,
                    jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt(), Integer.class),
                    server.getsByEndpoint(), payloadRows, stagedBytes, server.returnedGets(),
                    server.returnedRawBytes(), partials, driver.peakNewPartialPairs, driver.dispatches,
                    driver.predecessorWaits.get(), driver.spacingWaits.get(),
                    Duration.between(START, end).toNanos() / 1e9, payloadReads.snapshot());
        }



        void printFiniteProgress(ManualDriver driver) {
            int completedFixtures = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                    + " WHERE season=? AND kind='FIXTURE_VERIFICATION' AND state='COMPLETED'",
                    Integer.class, SEASON.code());
            int completedHistories = jdbc.queryForObject("SELECT count(DISTINCT publication_key) FROM "
                    + TABLES.run() + " WHERE season=? AND kind='PLAYER_HISTORY' AND state='COMPLETED'",
                    Integer.class, SEASON.code());
            System.out.printf("OWNED_ADMISSION_DRAIN_PROGRESS virtualSeconds=%.3f completedDispatches=%d "
                            + "admittedAttempts=%d completedFixtures=%d completedHistoryPlayers=%d endpoints=%s "
                            + "currentPartialPairs=%d peakPartialPairs=%d%n",
                    Duration.between(START, clock.instant()).toNanos() / 1e9, driver.completions.size(),
                    durableAdmissionCount(), completedFixtures, completedHistories, server.getsByEndpoint(),
                    newOutstandingPartialPairs(), driver.peakNewPartialPairs);
        }

        boolean hasDueActiveRuns() {
            Instant now = clock.instant();
            return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + TABLES.run()
                    + " WHERE season=? AND runtime_mode=? AND runtime_epoch=?"
                    + " AND due_at<=? AND requested_source_after<=?"
                    + " AND (state='PENDING' OR (state='LEASED' AND lease_expires_at<=?)))",
                    Boolean.class, SEASON.code(), fence.mode().name(), fence.epoch(),
                    Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        }

        Instant earliestActiveRunDue() {
            Timestamp due = jdbc.queryForObject("SELECT min(CASE WHEN state='LEASED'"
                    + " THEN GREATEST(due_at,requested_source_after,lease_expires_at)"
                    + " ELSE GREATEST(due_at,requested_source_after) END) FROM " + TABLES.run()
                    + " WHERE season=? AND runtime_mode=? AND runtime_epoch=?"
                    + " AND state IN ('PENDING','LEASED')", Timestamp.class,
                    SEASON.code(), fence.mode().name(), fence.epoch());
            return due == null ? null : due.toInstant();
        }

        int durableAdmissionCount() {
            return jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt(), Integer.class);
        }

        void captureDeferrals(ManualDriver driver) {
            jdbc.query("SELECT generation,attempt_count,last_error FROM " + TABLES.run()
                            + " WHERE season=? AND state='PENDING' AND last_error LIKE 'HTTP admission deferred:%'",
                    (rs, row) -> Map.entry(new ClaimAttempt(rs.getLong(1), rs.getInt(2)), rs.getString(3)),
                    SEASON.code()).forEach(entry -> {
                        if (countedDeferrals.add(entry.getKey())) {
                            driver.deferrals++;
                            deferralReasons.merge(entry.getValue(), 1, Integer::sum);
                        }
                    });
        }

        int dispatchOneWithControlledResponse(String workerId) throws Exception {
            controlledDispatchCalls++;
            Map<Long, Integer> attemptsBefore = runAttemptCounts();
            BlockingQueue<WaitObservation> waitObservations = new LinkedBlockingQueue<>();
            http.setWaitObserverForTests(waitObservations::add);
            Future<Boolean> future = dispatchWorkers.submit(() -> coordinator.dispatchOne(workerId));
            List<ScheduledResponse> responses = new ArrayList<>();
            Map<Long, WaitObservation> parked = new HashMap<>();
            Set<Long> spacingWoken = new HashSet<>();
            while (!future.isDone()) {
                RequestArrival arrival;
                while ((arrival = server.pollArrival(0, TimeUnit.MILLISECONDS)) != null) {
                    recordSingleDispatchAdmission(arrival, workerId);
                    responses.add(new ScheduledResponse(arrival, arrival.at().plus(RESPONSE_TIME)));
                }
                WaitObservation observation;
                while ((observation = waitObservations.poll()) != null) {
                    if (observation.phase() == WaitPhase.START) {
                        if (parked.putIfAbsent(observation.token(), observation) != null) {
                            throw new AssertionError("single dispatch reused an active wait token");
                        }
                        if (observation.kind() != WaitKind.SPACING) {
                            throw new AssertionError("single dispatch unexpectedly parked for " + observation.kind());
                        }
                    } else {
                        WaitObservation start = parked.remove(observation.token());
                        spacingWoken.remove(observation.token());
                        if (start == null || start.kind() != observation.kind()
                                || !java.util.Objects.equals(start.nextDue(), observation.nextDue())) {
                            throw new AssertionError("spacing wait RESUME did not match its acknowledged START");
                        }
                    }
                }

                ScheduledResponse nextResponse = responses.stream()
                        .min(java.util.Comparator.comparing(ScheduledResponse::releaseAt)).orElse(null);
                WaitObservation nextSpacing = parked.values().stream()
                        .filter(wait -> !spacingWoken.contains(wait.token()))
                        .min(java.util.Comparator.comparing(WaitObservation::nextDue)).orElse(null);
                boolean releaseResponse = nextResponse != null && (nextSpacing == null
                        || !nextSpacing.nextDue().isBefore(nextResponse.releaseAt()));
                if (releaseResponse) {
                    advanceControlledTime(nextResponse.releaseAt());
                    nextResponse.arrival().release().countDown();
                    responses.remove(nextResponse);
                    continue;
                }
                if (nextSpacing != null) {
                    advanceControlledTime(nextSpacing.nextDue());
                    spacingWoken.add(nextSpacing.token());
                    continue;
                }

                RequestArrival observed = server.pollArrival(
                        Math.min(TimeUnit.MILLISECONDS.toNanos(5), remainingWallNanos()), TimeUnit.NANOSECONDS);
                if (observed != null) {
                    recordSingleDispatchAdmission(observed, workerId);
                    responses.add(new ScheduledResponse(observed, observed.at().plus(RESPONSE_TIME)));
                }
            }
            WaitObservation observation;
            while ((observation = waitObservations.poll()) != null) {
                if (observation.phase() == WaitPhase.RESUME) {
                    WaitObservation start = parked.remove(observation.token());
                    spacingWoken.remove(observation.token());
                    if (start == null || start.kind() != observation.kind()
                            || !java.util.Objects.equals(start.nextDue(), observation.nextDue())) {
                        throw new AssertionError("spacing wait RESUME did not match its acknowledged START");
                    }
                } else if (parked.putIfAbsent(observation.token(), observation) != null) {
                    throw new AssertionError("single dispatch reused an active wait token");
                }
            }
            if (!parked.isEmpty() || !responses.isEmpty()) {
                throw new AssertionError("single dispatch ended with an unacknowledged wait or response");
            }
            boolean claimed = future.get(remainingWallNanos(), TimeUnit.NANOSECONDS);
            Map.Entry<Long, Integer> claimedRun = runAttemptCounts().entrySet().stream()
                    .filter(entry -> entry.getValue() > attemptsBefore.getOrDefault(entry.getKey(), 0))
                    .max(Map.Entry.comparingByKey()).orElse(null);
            long generation = claimedRun == null ? 0 : claimedRun.getKey();
            SyncKind kind = generation == 0 ? null : SyncKind.valueOf(jdbc.queryForObject(
                    "SELECT kind FROM " + TABLES.run() + " WHERE generation=?", String.class, generation));
            controlledDispatchTrace.add(new ControlledDispatchTrace(controlledDispatchCalls,
                    claimed, generation, kind, clock.instant()));
            return claimed ? 1 : 0;
        }

        private Map<Long, Integer> runAttemptCounts() {
            Map<Long, Integer> result = new HashMap<>();
            jdbc.query("SELECT generation,attempt_count FROM " + TABLES.run()
                            + " WHERE season=? AND runtime_mode=? AND runtime_epoch=?",
                    (rs, row) -> result.put(rs.getLong(1), rs.getInt(2)),
                    SEASON.code(), fence.mode().name(), fence.epoch());
            return result;
        }

        private void recordSingleDispatchAdmission(RequestArrival arrival, String workerId) {
            String owner = "admission-repro:" + workerId;
            Map<String, Object> leased = jdbc.queryForMap("SELECT generation,kind,publication_key,"
                    + "attempt_count,last_attempt_at FROM " + TABLES.run()
                    + " WHERE state='LEASED' AND lease_owner=?", owner);
            singleDispatchAdmissions.add(new AdmissionTrace(++singleAdmissionOrdinal,
                    ((Number) leased.get("generation")).longValue(), workerId,
                    arrival.kind(), arrival.path(), arrival.at(),
                    ((Timestamp) leased.get("last_attempt_at")).toInstant(),
                    ((Number) leased.get("attempt_count")).intValue(),
                    (String) leased.get("kind"), (String) leased.get("publication_key")));
        }

        private void advanceControlledTime(Instant instant) {
            Instant current = clock.instant();
            if (instant.isAfter(current)) {
                Duration elapsed = Duration.between(current, instant);
                turnNanoTime.addAndGet(elapsed.toNanos());
                clock.advance(elapsed);
            }
            http.clockAdvancedForTests();
        }

        int newOutstandingPartialPairs() {
            List<Long> generations = jdbc.queryForList("SELECT r.generation FROM " + TABLES.run()
                    + " r WHERE r.season=? AND r.kind='FIXTURE_VERIFICATION'"
                    + " AND r.state IN ('PENDING','LEASED')"
                    + " AND EXISTS (SELECT 1 FROM " + TABLES.payload() + " b WHERE b.run_generation=r.generation"
                    + " AND b.endpoint_kind='BOOTSTRAP_STATIC') AND NOT EXISTS (SELECT 1 FROM " + TABLES.payload()
                    + " f WHERE f.run_generation=r.generation AND f.endpoint_kind='FIXTURES')",
                    Long.class, SEASON.code());
            return (int) generations.stream().filter(generation -> !seededPartialGenerations.contains(generation))
                    .count();
        }

        private void emergencyStop() {
            watchdogFired.set(true);
            dispatchWorkers.shutdownNow();
            closeClient();
            server.releaseAll();
        }

        private void closeClient() {
            if (clientClosed.compareAndSet(false, true)) {
                http.close();
                dbOperations.close();
            }
        }

        void stopDispatchWorkers() throws InterruptedException {
            dispatchWorkers.shutdownNow();
            closeClient();
            server.releaseAll();
            long stopDeadline = finiteDrain ? cleanupDeadline : wallDeadline;
            long remaining = Math.min(TimeUnit.SECONDS.toNanos(5),
                    Math.max(1, stopDeadline - System.nanoTime()));
            if (!dispatchWorkers.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                throw new IllegalStateException("driver cannot yet verify FIFO: workers did not quiesce at cutoff");
            }
            if (finiteDrain) watchdogTask.cancel(false);
        }

        void recordFiniteStopReason(String reason) {
            if (finiteDrain) finiteStopReason = reason;
        }

        void cancelWorkWatchdog() {
            if (finiteDrain) watchdogTask.cancel(false);
        }

        private long remainingWallNanos() {
            long remaining = wallDeadline - System.nanoTime();
            if (remaining <= 0) {
                throw new IllegalStateException("driver cannot yet verify FIFO: workers did not reach "
                        + "an observed response release or completion before the wall watchdog");
            }
            return remaining;
        }

        @Override
        public void close() throws Exception {
            server.releaseAll();
            dispatchWorkers.shutdownNow();
            closeClient();
            long workerWait = finiteDrain
                    ? Math.min(TimeUnit.SECONDS.toNanos(5), Math.max(1, cleanupDeadline - System.nanoTime()))
                    : TimeUnit.SECONDS.toNanos(5);
            boolean workerClean = dispatchWorkers.awaitTermination(workerWait, TimeUnit.NANOSECONDS);
            if (workerClean) watchdogTask.cancel(false);
            boolean dbWorkerClean = false;
            try {
                server.close();
            } finally {
                if (workerClean) watchdog.shutdownNow();
                else watchdog.shutdown();
                boolean watchdogClean = watchdog.awaitTermination(5, TimeUnit.SECONDS);
                try {
                    long dbWait = finiteDrain
                            ? Math.min(TimeUnit.SECONDS.toNanos(5),
                                    Math.max(0, cleanupDeadline - System.nanoTime()))
                            : TimeUnit.SECONDS.toNanos(5);
                    dbWorkerClean = dbOperations.awaitTerminationForTests(Duration.ofNanos(dbWait));
                    if (!workerClean || !dbWorkerClean || !watchdogClean) {
                        throw new IllegalStateException(
                                "workers or watchdog did not terminate; refusing to clear owned rows");
                    }
                    activation.deactivate(fence);
                } finally {
                    if (workerClean && dbWorkerClean && watchdogClean && cleanOnClose) clearOwnedRows();
                }
                assertThat(!watchdogFired.get() || (finiteDrain && "WALL_LIMIT".equals(finiteStopReason)))
                        .as("watchdog may fire only when the finite drain stops at WALL_LIMIT").isTrue();
            }
        }
    }

    private static final class ManualDriver {
        private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(5);
        private static final Duration PLANNER_FIXED_DELAY = Duration.ofMinutes(1);

        private final Harness harness;
        private final long readyGeneration;
        private final long untouchedGeneration;
        private final boolean finiteDrain;
        private final boolean firstStageBaseline;
        private final Duration virtualLimit;
        private final int admissionLimit;
        private final int admissionCountAtStart;
        private final int dispatchStartLimit;
        private final int claimLimit;
        private final DriverGoal goal;
        private int maxActiveWorkers = 2;
        private final int readySeedAttemptCount;
        private final int untouchedSeedAttemptCount;
        private ClaimEvidence readyFirstClaim;
        private ClaimEvidence untouchedFirstClaim;
        private final Instant startAt;
        private final Instant stopAt;
        private final Instant[] nextAt;
        private Instant plannerNextDue;
        private Instant nextPlannerTickAt;
        private PlannerInputRepository.PlannerSnapshot plannerSnapshotAtFirstFixedTick;
        private final Map<Integer, DispatchState> active = new LinkedHashMap<>();
        private final List<DispatchCompletion> completions = new ArrayList<>();
        private final List<AdmissionTrace> admissions = new ArrayList<>();
        private final Map<Integer, FirstPendingObservation> firstPendingObservations = new TreeMap<>();
        private Set<Integer> additionalFirstPendingFixtureIds = Set.of();
        private final Map<Integer, List<String>> targetTransitions = new TreeMap<>();
        private final Map<Thread, DispatchState> dispatchByThread = new ConcurrentHashMap<>();
        private Set<Long> lastReadyTargetGenerations = Set.of();
        private String lastNextWake = "none";
        private int advancesWithReadyTargets;
        private final List<ControlledResponse> responses = new ArrayList<>();
        private final List<PlannerTickTrace> plannerTicks = new ArrayList<>();
        private final List<RequestArrival> unassignedArrivals = new ArrayList<>();
        private final Duration delayedFirstResponse;
        private boolean delayedFirstResponseUsed;
        private final AtomicReference<String> observerFailure = new AtomicReference<>();
        private final AtomicInteger predecessorWaits = new AtomicInteger();
        private final AtomicInteger predecessorResumes = new AtomicInteger();
        private final AtomicInteger spacingWaits = new AtomicInteger();
        private final AtomicInteger spacingResumes = new AtomicInteger();
        private int nextTieWorker;
        private int dispatches;
        private int admissionOrdinal;
        private int claims;
        private int deferrals;
        private int firstAdmissionWorker;
        private int peakNewPartialPairs;
        private int preferentialHistoryClaims;
        private int fifoHistoryClaims;
        private boolean sawReadyFixture;
        private boolean sawBlockedFixture;
        private boolean sawUntouchedFixture;
        private int nextProgressDispatchCount = 100;
        private String stopReason;
        private long workStoppedAtNanos = -1;

        ManualDriver(Harness harness) {
            this(harness, 0, 0, false);
        }

        ManualDriver(Harness harness, long readyGeneration, long untouchedGeneration) {
            this(harness, readyGeneration, untouchedGeneration, false);
        }

        ManualDriver(Harness harness, boolean finiteDrain) {
            this(harness, 0, 0, finiteDrain);
        }

        ManualDriver(Harness harness, Duration virtualLimit, int admissionLimit, int dispatchStartLimit) {
            this(harness, 0, 0, true, true, virtualLimit, admissionLimit,
                    dispatchStartLimit, null, Integer.MAX_VALUE);
        }

        ManualDriver(Harness harness, DriverGoal goal, Duration virtualLimit,
                     int admissionLimit, int dispatchStartLimit) {
            this(harness, 0, 0, true, false, virtualLimit, admissionLimit,
                    dispatchStartLimit, goal, Integer.MAX_VALUE);
        }

        ManualDriver(Harness harness, DriverGoal goal, Duration virtualLimit,
                     int claimLimit, int admissionLimit, int dispatchStartLimit) {
            this(harness, goal, virtualLimit, claimLimit, admissionLimit, dispatchStartLimit, RESPONSE_TIME);
        }

        ManualDriver(Harness harness, DriverGoal goal, Duration virtualLimit,
                     int claimLimit, int admissionLimit, int dispatchStartLimit,
                     Duration delayedFirstResponse) {
            this(harness, 0, 0, true, false, virtualLimit, admissionLimit,
                    dispatchStartLimit, goal, claimLimit, delayedFirstResponse);
        }

        private ManualDriver(Harness harness, long readyGeneration, long untouchedGeneration,
                             boolean finiteDrain) {
            this(harness, readyGeneration, untouchedGeneration, finiteDrain, false,
                    finiteDrain ? DRAIN_VIRTUAL_LIMIT : VIRTUAL_LIMIT,
                    DRAIN_ADMISSION_LIMIT, DRAIN_DISPATCH_LIMIT, null, Integer.MAX_VALUE);
        }

        private ManualDriver(Harness harness, long readyGeneration, long untouchedGeneration,
                             boolean finiteDrain, boolean firstStageBaseline, Duration virtualLimit,
                             int admissionLimit, int dispatchStartLimit) {
            this(harness, readyGeneration, untouchedGeneration, finiteDrain, firstStageBaseline,
                    virtualLimit, admissionLimit, dispatchStartLimit, null, Integer.MAX_VALUE);
        }

        private ManualDriver(Harness harness, long readyGeneration, long untouchedGeneration,
                             boolean finiteDrain, boolean firstStageBaseline, Duration virtualLimit,
                             int admissionLimit, int dispatchStartLimit, DriverGoal goal,
                             int claimLimit) {
            this(harness, readyGeneration, untouchedGeneration, finiteDrain, firstStageBaseline,
                    virtualLimit, admissionLimit, dispatchStartLimit, goal, claimLimit, RESPONSE_TIME);
        }

        private ManualDriver(Harness harness, long readyGeneration, long untouchedGeneration,
                             boolean finiteDrain, boolean firstStageBaseline, Duration virtualLimit,
                             int admissionLimit, int dispatchStartLimit, DriverGoal goal,
                             int claimLimit, Duration delayedFirstResponse) {
            this.harness = harness;
            this.readyGeneration = readyGeneration;
            this.untouchedGeneration = untouchedGeneration;
            this.finiteDrain = finiteDrain;
            this.firstStageBaseline = firstStageBaseline;
            this.virtualLimit = virtualLimit;
            this.admissionLimit = admissionLimit;
            this.admissionCountAtStart = harness.durableAdmissionCount();
            this.dispatchStartLimit = dispatchStartLimit;
            this.claimLimit = claimLimit;
            this.goal = goal;
            this.delayedFirstResponse = delayedFirstResponse;
            this.startAt = harness.clock.instant();
            this.stopAt = startAt.plus(virtualLimit);
            this.plannerNextDue = null;
            if (goal != null) {
                OwnedMatchdayCoordinator.TickResult initialTick = harness.coordinator.tick();
                plannerNextDue = initialTick.nextDueAt();
                plannerTicks.add(new PlannerTickTrace(startAt, Set.of(), List.of(), Map.of(),
                        Map.of(), Set.of(), initialTick.nextDueAt()));
                nextPlannerTickAt = harness.clock.instant().plus(PLANNER_FIXED_DELAY);
            }
            this.nextAt = new Instant[] { startAt, startAt };
            this.readySeedAttemptCount = readyGeneration > 0
                    ? harness.progress(readyGeneration).attemptCount() : 0;
            this.untouchedSeedAttemptCount = untouchedGeneration > 0
                    ? harness.progress(untouchedGeneration).attemptCount() : 0;
            nextTieWorker = harness.firstWorker;
        }

        void run() throws Exception {
            setToScheduledStart(harness.firstWorker);
            if (!canStartDispatch()) {
                stopReason = limitReason();
                if (stopReason == null) stopReason = softWallCutoffReached() ? "WALL_LIMIT" : "DISPATCH_LIMIT";
                recordWorkStop();
                harness.recordFiniteStopReason(stopReason);
                harness.cancelWorkWatchdog();
                return;
            }
            sampleAcceptanceQueueClasses();
            DispatchState first = start(harness.firstWorker);
            RequestArrival firstArrival = awaitFirstArrival(first);
            if (firstArrival == null && !first.future.isDone()) {
                stopReason = "WALL_LIMIT";
                recordWorkStop();
                harness.recordFiniteStopReason(stopReason);
                quiesceAtCutoff();
                harness.cancelWorkWatchdog();
                return;
            }
            if (firstArrival != null) unassignedArrivals.add(firstArrival);
            else collectCompleted();
            assignPendingResponses();
            captureFirstPendingObservations();

            int secondWorker = 3 - harness.firstWorker;
            if (maxActiveWorkers > active.size() && canStartDispatch() && mayStartAlongsideActive()
                    && !nextAt[secondWorker - 1].isAfter(harness.clock.instant())) {
                start(secondWorker);
                nextTieWorker = harness.firstWorker;
            }

            while (true) {
                if (softWallCutoffReached()) stopReason = "WALL_LIMIT";
                String entryLimit = finiteDrain ? limitReason() : null;
                if (entryLimit != null) {
                    stopReason = entryLimit;
                    recordWorkStop();
                    if (!active.isEmpty()) quiesceAtCutoff();
                    break;
                }
                if (!finiteDrain) harness.remainingWallNanos();
                if (observerFailure.get() != null) {
                    throw new IllegalStateException(observerFailure.get());
                }
                drainArrivals();
                assignPendingResponses();
                // Snapshot the seeded+1 attempt before releasing responses or restarting a worker.
                sampleFirstClaimsAtAcknowledgedBoundary();
                if (wallCutoffReached()) {
                    stopReason = "WALL_LIMIT";
                    recordWorkStop();
                    if (!active.isEmpty()) quiesceAtCutoff();
                    break;
                }
                releaseDueResponses();
                collectCompleted();
                captureFirstPendingObservations();
                assignPendingResponses();
                sampleFirstClaimsAtAcknowledgedBoundary();
                samplePartialPairs();
                sampleAcceptanceQueueClasses();
                harness.captureDeferrals(this);
                maybePrintFiniteProgress();
                if (softWallCutoffReached()) stopReason = "WALL_LIMIT";

                String limit = finiteDrain ? limitReason() : null;
                if (limit != null) {
                    stopReason = limit;
                    recordWorkStop();
                    if (!active.isEmpty()) quiesceAtCutoff();
                    break;
                }

                runPeriodicPlannerTickIfDue();
                if (harness.clock.instant().isBefore(stopAt) && !focusSatisfied()
                        && !softWallCutoffReached()) startReadyWorkers();

                if (active.isEmpty() && focusSatisfied()) break;
                if (active.isEmpty() && !hasActiveRuns() && plannerNextDue == null
                        && nextPlannerTickAt == null) break;
                if (active.isEmpty() && !canStartDispatch()) break;
                if (!harness.clock.instant().isBefore(stopAt)) {
                    if (finiteDrain) {
                        stopReason = "VIRTUAL_LIMIT";
                        recordWorkStop();
                    }
                    if (!active.isEmpty()) quiesceAtCutoff();
                    break;
                }

                if (!activeStatesAcknowledged()) {
                    waitForEvent();
                    continue;
                }
                Event next = nextEvent();
                if (next == null) {
                    waitForEvent();
                    continue;
                }
                if (next.at().isAfter(stopAt)) {
                    if (active.isEmpty()) {
                        advanceTo(stopAt, "virtual-limit");
                        continue;
                    }
                    if (finiteDrain) {
                        stopReason = "VIRTUAL_LIMIT";
                        recordWorkStop();
                    }
                    quiesceAtCutoff();
                    break;
                }
                if (next.at().isAfter(harness.clock.instant())
                        && !advanceToIfAcknowledged(next.at(), next.kind().name())) {
                    // A worker may leave its observed wait while the next event is selected.
                    waitForEvent();
                }
            }
            if (!active.isEmpty()) {
                throw new IllegalStateException("driver cannot yet verify FIFO: workers remain active at snapshot");
            }
            if (finiteDrain && (harness.watchdogFired.get()
                    || (stopReason == null && System.nanoTime() >= harness.wallDeadline))) {
                stopReason = "WALL_LIMIT";
            }
            if (firstStageBaseline && focusSatisfied()) stopReason = "STAGED";
            else if (stopReason == null) stopReason = "DRAINED";
            recordWorkStop();
            harness.recordFiniteStopReason(stopReason);
            if (finiteDrain) harness.cancelWorkWatchdog();
            samplePartialPairs();
            if (stopReason.equals("DRAINED")) assertWaitObservationsBalanced();
        }

        private void recordWorkStop() {
            if (finiteDrain && "WALL_LIMIT".equals(stopReason)) {
                workStoppedAtNanos = firstStageBaseline && !harness.watchdogFired.get()
                        ? System.nanoTime() : harness.wallDeadline;
            } else if (workStoppedAtNanos < 0) {
                workStoppedAtNanos = System.nanoTime();
            }
        }

        private boolean wallCutoffReached() {
            return finiteDrain && (harness.watchdogFired.get() || System.nanoTime() >= harness.wallDeadline);
        }

        private boolean softWallCutoffReached() {
            return firstStageBaseline && System.nanoTime() >= harness.wallDeadline
                    - ROSTER_BASELINE_DRAIN_RESERVE_NANOS;
        }


        private String limitReason() {
            if (!finiteDrain) return null;
            if (wallCutoffReached()) return "WALL_LIMIT";
            if (goal != null && active.isEmpty() && focusSatisfied()) return null;
            if (softWallCutoffReached()) return null;
            if (!harness.clock.instant().isBefore(stopAt)) return "VIRTUAL_LIMIT";
            int admissions = harness.durableAdmissionCount() - admissionCountAtStart;
            if (wallCutoffReached()) return "WALL_LIMIT";
            if (claims >= claimLimit) return "CLAIM_LIMIT";
            if (admissions >= admissionLimit) return "ADMISSION_LIMIT";
            if (dispatches >= dispatchStartLimit) return "DISPATCH_LIMIT";
            return null;
        }

        private boolean canStartDispatch() {
            if (!finiteDrain) return dispatches < DISPATCH_LIMIT;
            if (wallCutoffReached() || softWallCutoffReached()
                    || (firstStageBaseline && focusSatisfied())) return false;
            int admissions = harness.durableAdmissionCount() - admissionCountAtStart;
            return !wallCutoffReached()
                    && claims < claimLimit
                    && dispatches < dispatchStartLimit
                    && admissions < admissionLimit
                    && harness.clock.instant().isBefore(stopAt);
        }

        private void maybePrintFiniteProgress() {
            if (!finiteDrain || firstStageBaseline || completions.size() < nextProgressDispatchCount) return;
            harness.printFiniteProgress(this);
            nextProgressDispatchCount += 100;
        }

        private RequestArrival awaitFirstArrival(DispatchState dispatch) throws Exception {
            while (true) {
                if (wallCutoffReached()) return null;
                WaitState wait = dispatch.acknowledgedWait;
                if (wait != null && wait.kind == WaitKind.SPACING) {
                    if (wait.nextDue.isAfter(stopAt)) return null;
                    if (wait.nextDue.isAfter(harness.clock.instant())) advanceTo(wait.nextDue, "spacing-" + wait.kind);
                    else harness.http.clockAdvancedForTests();
                }
                RequestArrival arrival = harness.server.pollArrival(pollTimeoutNanos(), TimeUnit.NANOSECONDS);
                if (arrival != null) return arrival;
                if (dispatch.future.isDone()) {
                    dispatch.future.get();
                    return null;
                }
            }
        }

        private void observeWait(WaitObservation observation) {
            DispatchState dispatch = dispatchByThread.get(observation.thread());
            if (dispatch == null || dispatch.thread != observation.thread()) {
                observerFailure.compareAndSet(null, "wait observer thread is not linked to an active dispatch");
                return;
            }
            dispatch.observe(observation, observerFailure);
            if (observation.phase() == WaitPhase.START && observerFailure.get() == null) {
                if (observation.kind() == WaitKind.PREDECESSOR) predecessorWaits.incrementAndGet();
                else spacingWaits.incrementAndGet();
            } else if (observation.phase() == WaitPhase.RESUME && observerFailure.get() == null) {
                if (observation.kind() == WaitKind.PREDECESSOR) predecessorResumes.incrementAndGet();
                else spacingResumes.incrementAndGet();
            }
        }

        private void drainArrivals() throws Exception {
            RequestArrival arrival;
            while ((arrival = harness.server.pollArrival(0, TimeUnit.MILLISECONDS)) != null) {
                unassignedArrivals.add(arrival);
            }
        }

        private void assignPendingResponses() {
            for (RequestArrival arrival : List.copyOf(unassignedArrivals)) {
                List<DispatchState> candidates = active.values().stream()
                        .filter(dispatch -> !dispatch.future.isDone())
                        .filter(dispatch -> dispatch.acknowledgedWait == null && dispatch.pendingResponse == null)
                        .toList();
                if (candidates.size() != 1) {
                    throw new IllegalStateException("source callback does not have exactly one active owner: path="
                            + arrival.path() + " at=" + arrival.at() + " candidates="
                            + candidates.stream().map(dispatch -> Integer.toString(dispatch.worker)).toList());
                }
                DispatchState owner = candidates.getFirst();
                long generation = focusedGeneration(owner);
                if (generation == 0) {
                    throw new IllegalStateException("source callback has no live leased generation: worker="
                            + owner.worker + " owner=" + harness.instanceId + ":" + owner.worker
                            + " path=" + arrival.path() + " at=" + arrival.at());
                }
                Duration responseDelay = delayedFirstResponseUsed ? RESPONSE_TIME : delayedFirstResponse;
                delayedFirstResponseUsed = true;
                ControlledResponse response = new ControlledResponse(owner, arrival,
                        arrival.at().plus(responseDelay), generation);
                owner.pendingResponse = response;
                responses.add(response);
                Map<String, Object> run = jdbc.queryForMap("SELECT kind,publication_key,attempt_count,last_attempt_at "
                        + "FROM " + TABLES.run() + " WHERE generation=? AND state='LEASED' AND lease_owner=? "
                        + "AND season=? AND runtime_mode=? AND runtime_epoch=?", generation,
                        harness.instanceId + ":" + owner.worker, SEASON.code(),
                        harness.fence.mode().name(), harness.fence.epoch());
                admissions.add(new AdmissionTrace(++admissionOrdinal, generation,
                        Integer.toString(owner.worker), arrival.kind(), arrival.path(), arrival.at(),
                        ((Timestamp) run.get("last_attempt_at")).toInstant(),
                        ((Number) run.get("attempt_count")).intValue(),
                        (String) run.get("kind"), (String) run.get("publication_key")));
                if (goal != null && "FIXTURE_VERIFICATION".equals(run.get("kind"))
                        && goal.fixtureIds().contains(fixtureId((String) run.get("publication_key")))) {
                    owner.targetGenerations.add(generation);
                    owner.targetRoles.add(arrival.kind() + ":" + arrival.path() + "@" + arrival.at());
                }
                recordHistoryLane(generation, arrival.kind());
                unassignedArrivals.remove(arrival);
                if (firstAdmissionWorker == 0) firstAdmissionWorker = owner.worker;
            }
        }

        private void releaseDueResponses() {
            Instant now = harness.clock.instant();
            for (ControlledResponse response : List.copyOf(responses)) {
                if (!response.releaseAt.isAfter(now)) {
                    if (response.released) {
                        throw new IllegalStateException("controlled source response was released twice");
                    }
                    if (response.owner.pendingResponse != response) {
                        throw new IllegalStateException("controlled response is no longer bound to its worker");
                    }
                    response.released = true;
                    response.owner.pendingResponse = null;
                    response.arrival.release().countDown();
                    responses.remove(response);
                }
            }
        }

        private void collectCompleted() throws Exception {
            for (DispatchState dispatch : List.copyOf(active.values())) {
                if (!dispatch.future.isDone()) continue;
                if (dispatch.pendingResponse != null && !dispatch.pendingResponse.released) {
                    throw new IllegalStateException("dispatch completed before its controlled response release: worker="
                            + dispatch.worker + " generation=" + dispatch.generation + " endpoint="
                            + dispatch.pendingResponse.arrival.kind() + " releaseAt="
                            + dispatch.pendingResponse.releaseAt + " now=" + harness.clock.instant());
                }
                DispatchCompletion result = dispatch.future.get();
                if (dispatch.pendingResponse != null) {
                    throw new IllegalStateException("released response remained bound to completed dispatch: worker="
                            + dispatch.worker + " generation=" + dispatch.generation);
                }
                if (result.claimed()) claims++;
                if (goal != null) {
                    for (long generation : dispatch.targetGenerations) {
                        recordTargetTransition(dispatch, generation, result);
                    }
                }
                completions.add(result);
                nextAt[result.worker() - 1] = result.completedAt().plus(WORKER_DELAY);
                dispatch.releaseGenerationMapping();
                active.remove(result.worker());
            }
        }

        private void startReadyWorkers() {
            while (active.size() < maxActiveWorkers && canStartDispatch()
                    && hasDueActiveRuns() && mayStartAlongsideActive()) {
                sampleAcceptanceQueueClasses();
                int worker = readyWorker();
                if (worker == 0) return;
                start(worker);
                nextTieWorker = 3 - worker;
            }
        }

        private boolean mayStartAlongsideActive() {
            return active.values().stream().allMatch(dispatch ->
                    dispatch.future.isDone() || dispatch.pendingResponse != null);
        }

        private int readyWorker() {
            List<Integer> ready = new ArrayList<>(2);
            for (int worker : List.of(1, 2)) {
                if (!active.containsKey(worker) && !nextAt[worker - 1].isAfter(harness.clock.instant())) {
                    ready.add(worker);
                }
            }
            if (ready.isEmpty()) return 0;
            if (ready.size() == 1) return ready.getFirst();
            return ready.contains(nextTieWorker) ? nextTieWorker : ready.getFirst();
        }

        private long focusedGeneration(DispatchState dispatch) {
            if (dispatch.generation != null) return dispatch.generation;
            String owner = harness.instanceId + ":" + dispatch.worker;
            List<Long> leased = jdbc.queryForList("SELECT generation FROM " + TABLES.run()
                    + " WHERE state='LEASED' AND lease_owner=? AND season=? AND runtime_mode=? AND runtime_epoch=?",
                    Long.class, owner, SEASON.code(), harness.fence.mode().name(), harness.fence.epoch());
            if (leased.size() > 1) {
                throw new IllegalStateException("dispatch owner has multiple live generation mappings: worker="
                        + dispatch.worker + " owner=" + owner + " generations=" + leased);
            }
            if (leased.size() == 1) dispatch.generation = leased.getFirst();
            return dispatch.generation == null ? 0 : dispatch.generation;
        }

        private boolean focusSatisfied() {
            if (goal != null) {
                if (goal.kind() == DriverGoalKind.FIRST_PENDING_OBSERVATION
                        && plannerTicks.stream().noneMatch(tick -> tick.at().equals(
                                startAt.plus(PLANNER_FIXED_DELAY)))) return false;
                return goalSatisfied();
            }
            if (firstStageBaseline) {
                return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + TABLES.run()
                        + " WHERE season=? AND kind='FIXTURE_VERIFICATION' AND state='COMPLETED')",
                        Boolean.class, SEASON.code());
            }
            if (readyGeneration <= 0 || untouchedGeneration <= 0) return false;
            boolean readyStaged = jdbc.queryForObject("SELECT state='COMPLETED' FROM " + TABLES.run()
                    + " WHERE generation=?", Boolean.class, readyGeneration);
            int untouchedAttempts = jdbc.queryForObject("SELECT attempt_count FROM " + TABLES.run()
                    + " WHERE generation=?", Integer.class, untouchedGeneration);
            return readyStaged && untouchedAttempts > 0;
        }

        private void captureFirstPendingObservations() {
            Set<Integer> observedFixtureIds = new java.util.TreeSet<>(additionalFirstPendingFixtureIds);
            if (goal != null) observedFixtureIds.addAll(goal.fixtureIds());
            if (observedFixtureIds.isEmpty()) return;
            String keys = observedFixtureIds.stream().sorted().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(","));
            jdbc.query("SELECT fs.fixture_id,fs.season_id,fs.season_code,fs.current_verified_generation,"
                            + "fs.observation_generation,fs.source_read_started_at,fs.source_read_finished_at,"
                            + "fs.validation_failures::text validation_failures,fs.observation::text observation,"
                            + "fs.expected_participants,fs.received_participants,"
                            + "cardinality(fs.missing_player_ids) missing_participants FROM "
                            + TABLES.fixtureSync() + " fs JOIN "
                            + TABLES.run() + " r ON r.generation=fs.observation_generation"
                            + " WHERE fs.season_id=? AND fs.fixture_id IN (" + keys + ")"
                            + " AND fs.current_verified_generation IS NULL"
                            + " AND fs.validation_failures @> ARRAY['NEEDS_SECOND_OBSERVATION']::text[]"
                            + " AND fs.observation->>'coherent'='true' AND r.kind='FIXTURE_VERIFICATION'"
                            + " AND r.state='COMPLETED' AND r.runtime_mode=? AND r.runtime_epoch=?",
                    (rs, row) -> firstPendingObservations.putIfAbsent(rs.getInt("fixture_id"),
                            new FirstPendingObservation(rs.getLong("observation_generation"),
                                    rs.getInt("season_id"), rs.getString("season_code"),
                                    nullableLong(rs.getObject("current_verified_generation")),
                                    new SourceRead(rs.getTimestamp("source_read_started_at").toInstant(),
                                            rs.getTimestamp("source_read_finished_at").toInstant()),
                                    rs.getString("validation_failures"),
                                    FixtureObservationCodec.decode(rs.getString("observation")),
                                    rs.getInt("expected_participants"), rs.getInt("received_participants"),
                                    rs.getInt("missing_participants"))),
                    SEASON.id(), harness.fence.mode().name(), harness.fence.epoch());
        }

        private void sampleFirstClaimsAtAcknowledgedBoundary() {
            if (!activeStatesAcknowledged()) return;
            if (readyFirstClaim == null) {
                readyFirstClaim = sampleFirstClaim(readyGeneration, readySeedAttemptCount);
            }
            if (untouchedFirstClaim == null) {
                untouchedFirstClaim = sampleFirstClaim(untouchedGeneration, untouchedSeedAttemptCount);
            }
        }

        private ClaimEvidence sampleFirstClaim(long generation, int seededAttemptCount) {
            if (generation <= 0) return null;
            int expectedAttemptCount = seededAttemptCount + 1;
            RunProgress progress = harness.progress(generation);
            if (progress.attemptCount() < expectedAttemptCount) return null;
            if (progress.attemptCount() > expectedAttemptCount) {
                throw new AssertionError("first observed claim skipped its seeded attempt boundary: generation="
                        + generation + " seededAttemptCount=" + seededAttemptCount
                        + " expectedFirstClaimAttempt=" + expectedAttemptCount
                        + " observedAttemptCount=" + progress.attemptCount()
                        + " virtualNow=" + harness.clock.instant()
                        + " latestLastAttemptAtNotFirstClaimEvidence=" + progress.lastAttemptAt()
                        + " latestLastAttemptOffsetSeconds=" + offset(progress.lastAttemptAt()));
            }
            if (progress.lastAttemptAt() == null) {
                throw new AssertionError("first observed claim has no durable last_attempt_at: generation="
                        + generation + " attemptCount=" + progress.attemptCount());
            }
            return new ClaimEvidence(progress.attemptCount(), progress.lastAttemptAt());
        }

        private boolean activeStatesAcknowledged() {
            if (observerFailure.get() != null) return false;
            for (DispatchState dispatch : active.values()) {
                if (dispatch.future.isDone() || dispatch.acknowledgedWait != null) continue;
                if (dispatch.pendingResponse != null) continue;
                return false;
            }
            return true;
        }

        private Event nextEvent() {
            Event next = null;
            Instant now = harness.clock.instant();
            for (ControlledResponse response : responses) {
                next = earlier(next, new Event(response.releaseAt, EventKind.RESPONSE));
            }
            for (DispatchState dispatch : active.values()) {
                WaitState wait = dispatch.acknowledgedWait;
                if (wait != null && wait.kind == WaitKind.SPACING && wait.nextDue.isAfter(now)) {
                    next = earlier(next, new Event(wait.nextDue, EventKind.SPACING));
                }
            }
            if (!focusSatisfied() && nextPlannerTickAt != null && nextPlannerTickAt.isAfter(now)) {
                next = earlier(next, new Event(nextPlannerTickAt, EventKind.PLANNER_TICK));
            }
            if (!focusSatisfied() && active.size() < maxActiveWorkers && mayStartAlongsideActive()
                    && canStartDispatch() && hasDueActiveRuns()) {
                for (int worker : List.of(1, 2)) {
                    if (!active.containsKey(worker)) {
                        next = earlier(next, new Event(nextAt[worker - 1], EventKind.WORKER_START));
                    }
                }
            } else if (!focusSatisfied() && active.isEmpty()) {
                Instant runDue = earliestActiveRunDue();
                if (runDue != null && runDue.isAfter(now)) {
                    next = earlier(next, new Event(runDue, EventKind.RUN_DUE));
                }
            }
            return next;
        }

        private Event earlier(Event current, Event candidate) {
            return current == null || candidate.at().isBefore(current.at()) ? candidate : current;
        }

        private void waitForEvent() throws Exception {
            RequestArrival arrival = harness.server.pollArrival(pollTimeoutNanos(), TimeUnit.NANOSECONDS);
            if (arrival != null) unassignedArrivals.add(arrival);
        }

        private long pollTimeoutNanos() {
            if (!finiteDrain) return Math.min(POLL_NANOS, harness.remainingWallNanos());
            return Math.max(1, Math.min(POLL_NANOS, harness.wallDeadline - System.nanoTime()));
        }

        private void quiesceAtCutoff() throws InterruptedException {
            harness.stopDispatchWorkers();
            if (!dispatchByThread.isEmpty()) {
                throw new IllegalStateException("dispatch threads remain linked after worker shutdown");
            }
            active.values().forEach(DispatchState::releaseGenerationMapping);
            active.clear();
            responses.clear();
            unassignedArrivals.clear();
        }

        private void samplePartialPairs() {
            peakNewPartialPairs = Math.max(peakNewPartialPairs, harness.newOutstandingPartialPairs());
        }

        private boolean goalSatisfied() {
            if (goal.kind() == DriverGoalKind.PENDING_FIXTURE_RUNS) {
                if (harness.clock.instant().isBefore(LEGACY_DAILY_DUE) || hasDueCanonicalRuns()
                        || !harness.store.currentCanonicalParentEvidence(SEASON.code(),
                        goal.fixtureIds().iterator().next(), harness.clock.instant()).currentPublished()) return false;
                return pendingFixtureRuns(harness.fence.epoch(), goal.fixtureIds()) == goal.fixtureIds().size();
            }
            if (goal.kind() == DriverGoalKind.FIRST_PENDING_OBSERVATION) {
                return goal.fixtureIds().stream().anyMatch(firstPendingObservations::containsKey);
            }
            String keys = goal.fixtureIds().stream().sorted().map(id -> "'fixture:" + id + "'")
                    .collect(java.util.stream.Collectors.joining(","));
            return jdbc.queryForObject("SELECT count(DISTINCT r.publication_key) FROM " + TABLES.run()
                    + " r JOIN " + TABLES.fixtureSync() + " fs ON fs.season_id=?"
                    + " AND r.publication_key='fixture:' || fs.fixture_id::text"
                    + " JOIN " + TABLES.receipt() + " p ON p.season=? AND p.publication_key=r.publication_key"
                    + " AND p.latest_generation=r.generation AND fs.current_verified_generation=r.generation"
                    + " WHERE r.season=? AND r.runtime_mode=? AND r.runtime_epoch=?"
                    + " AND r.kind='FIXTURE_VERIFICATION' AND r.state='COMPLETED'"
                    + " AND fs.state='VERIFIED' AND r.publication_key IN (" + keys + ")",
                    Integer.class, SEASON.id(), SEASON.code(), SEASON.code(),
                    harness.fence.mode().name(), harness.fence.epoch()) == goal.fixtureIds().size();
        }

        private void sampleAcceptanceQueueClasses() {
            if (goal == null || goal.fixtureIds().isEmpty()) {
                lastReadyTargetGenerations = Set.of();
                return;
            }
            Set<Long> ready = harness.store.readyFixtureGenerations(SEASON.code(), FIXTURE_RUNS).stream()
                    .filter(run -> goal.fixtureIds().contains(fixtureId(run.publicationKey())))
                    .map(OwnedSyncStore.Run::generation).collect(java.util.stream.Collectors.toSet());
            lastReadyTargetGenerations = Set.copyOf(ready);
            String keys = goal.fixtureIds().stream().sorted().map(id -> "'fixture:" + id + "'")
                    .collect(java.util.stream.Collectors.joining(","));
            jdbc.query("SELECT r.generation,r.attempt_count,"
                            + "EXISTS(SELECT 1 FROM " + TABLES.fixtureDependencyManifest()
                            + " m WHERE m.run_generation=r.generation) AS has_manifest,"
                            + "EXISTS(SELECT 1 FROM " + TABLES.payload()
                            + " p WHERE p.run_generation=r.generation) AS has_payload FROM " + TABLES.run()
                            + " r WHERE r.season=? AND r.runtime_mode=? AND r.runtime_epoch=?"
                            + " AND r.kind='FIXTURE_VERIFICATION' AND r.state='PENDING'"
                            + " AND r.publication_key IN (" + keys + ")",
                    (rs, row) -> {
                        long generation = rs.getLong("generation");
                        int attempts = rs.getInt("attempt_count");
                        boolean manifest = rs.getBoolean("has_manifest");
                        boolean payload = rs.getBoolean("has_payload");
                        if (ready.contains(generation)) sawReadyFixture = true;
                        if (attempts == 0 && !manifest && !payload) sawUntouchedFixture = true;
                        if (attempts > 0 && manifest && !ready.contains(generation)) sawBlockedFixture = true;
                        return null;
                    }, SEASON.code(), harness.fence.mode().name(), harness.fence.epoch());
        }

        private void recordTargetTransition(DispatchState dispatch, long generation,
                                            DispatchCompletion completion) {
            Map<String, Object> row = jdbc.queryForMap("SELECT r.generation,r.publication_key,r.state run_state,"
                    + "r.attempt_count,r.due_at,r.requested_source_after,r.last_attempt_at,r.last_error,"
                    + "fs.state fixture_state,fs.observation_generation,fs.current_verified_generation,"
                    + "fs.validation_failures::text validation_failures,fs.next_due_at,"
                    + "fs.source_read_started_at,fs.source_read_finished_at,m.source_floor,"
                    + "cardinality(m.required_player_ids) required_players FROM " + TABLES.run() + " r"
                    + " LEFT JOIN " + TABLES.fixtureSync() + " fs ON fs.season_id=?"
                    + " AND fs.fixture_id=substring(r.publication_key FROM 9)::integer"
                    + " LEFT JOIN " + TABLES.fixtureDependencyManifest()
                    + " m ON m.run_generation=r.generation WHERE r.generation=?",
                    SEASON.id(), generation);
            int fixtureId = fixtureId((String) row.get("publication_key"));
            List<String> transitions = targetTransitions.computeIfAbsent(fixtureId, ignored -> new ArrayList<>());
            if (transitions.size() >= 10) return;
            transitions.add("at=" + completion.completedAt() + ",worker=" + dispatch.worker
                    + ",gen=" + generation + ",attempt=" + row.get("attempt_count")
                    + ",dispatchStart=" + dispatch.startedAt + ",roles=" + dispatch.targetRoles
                    + ",run=" + row.get("run_state")
                    + ",fixture=" + row.get("fixture_state") + ",observation=" + row.get("observation_generation")
                    + ",verified=" + row.get("current_verified_generation")
                    + ",validation=" + row.get("validation_failures") + ",lastError=" + row.get("last_error")
                    + ",wanted=" + row.get("requested_source_after") + ",manifestFloor=" + row.get("source_floor")
                    + ",read=" + row.get("source_read_started_at") + ".." + row.get("source_read_finished_at")
                    + ",due=" + row.get("due_at") + ",nextDue=" + row.get("next_due_at")
                    + ",requiredPlayers=" + row.get("required_players"));
            dispatch.targetRoles.clear();
        }

        private void recordHistoryLane(long generation, EndpointKind endpoint) {
            if (endpoint != EndpointKind.ELEMENT_SUMMARY || generation <= 0) return;
            String kind = jdbc.queryForObject("SELECT kind FROM " + TABLES.run()
                    + " WHERE generation=?", String.class, generation);
            if (!"PLAYER_HISTORY".equals(kind)) return;
            int olderPending = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                    + " WHERE season=? AND runtime_mode=? AND runtime_epoch=? AND kind='PLAYER_HISTORY'"
                    + " AND state='PENDING' AND due_at<=? AND requested_source_after<=? AND generation<?",
                    Integer.class, SEASON.code(), harness.fence.mode().name(), harness.fence.epoch(),
                    Timestamp.from(harness.clock.instant()), Timestamp.from(harness.clock.instant()), generation);
            if (olderPending > 0) preferentialHistoryClaims++;
            else fifoHistoryClaims++;
        }

        private boolean hasActiveRuns() {
            return jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                    + " WHERE season=? AND runtime_mode=? AND runtime_epoch=?"
                    + " AND state IN ('PENDING','LEASED')", Integer.class, SEASON.code(),
                    harness.fence.mode().name(), harness.fence.epoch()) > 0;
        }

        private boolean hasDueCanonicalRuns() {
            Instant now = harness.clock.instant();
            return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + TABLES.run()
                    + " WHERE season=? AND runtime_mode=? AND runtime_epoch=? AND kind='CATALOGUE_SCHEDULE'"
                    + " AND ((coalescing_key=? AND publication_key=?) OR (coalescing_key=? AND publication_key=?))"
                    + " AND due_at<=? AND requested_source_after<=?"
                    + " AND (state='PENDING' OR (state='LEASED' AND lease_expires_at<=?)))",
                    Boolean.class, SEASON.code(), harness.fence.mode().name(), harness.fence.epoch(),
                    CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                    CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY,
                    CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                    CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY,
                    Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        }

        private boolean hasDueActiveRuns() {
            Instant now = harness.clock.instant();
            return jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                    + " WHERE season=? AND runtime_mode=? AND runtime_epoch=?"
                    + " AND due_at<=? AND requested_source_after<=?"
                    + " AND (state='PENDING' OR (state='LEASED' AND lease_expires_at<=?))",
                    Integer.class, SEASON.code(), harness.fence.mode().name(), harness.fence.epoch(),
                    Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)) > 0;
        }

        private Instant earliestActiveRunDue() {
            Timestamp due = jdbc.queryForObject("SELECT min(CASE WHEN state='LEASED'"
                    + " THEN GREATEST(due_at,requested_source_after,lease_expires_at)"
                    + " ELSE GREATEST(due_at,requested_source_after) END) FROM " + TABLES.run()
                    + " WHERE season=? AND runtime_mode=? AND runtime_epoch=?"
                    + " AND state IN ('PENDING','LEASED')", Timestamp.class, SEASON.code(),
                    harness.fence.mode().name(), harness.fence.epoch());
            return due == null ? null : due.toInstant();
        }

        private void runPeriodicPlannerTickIfDue() {
            if (goal == null || focusSatisfied() || nextPlannerTickAt == null
                    || nextPlannerTickAt.isAfter(harness.clock.instant()) || !activeStatesAcknowledged()) return;
            Instant tickAt = harness.clock.instant();
            Set<Integer> activeWorkers = Set.copyOf(active.keySet());
            List<Instant> pendingResponseReleases = active.values().stream()
                    .filter(dispatch -> dispatch.pendingResponse != null)
                    .map(dispatch -> dispatch.pendingResponse.releaseAt).toList();
            Map<Integer, String> acknowledgedWorkerStates = activeWorkerStates();
            Map<Integer, Long> workerGenerations = new TreeMap<>();
            Set<Integer> completedWorkers = new HashSet<>();
            active.forEach((worker, dispatch) -> {
                if (dispatch.generation != null) workerGenerations.put(worker, dispatch.generation);
                if (dispatch.future.isDone()) completedWorkers.add(worker);
            });
            OwnedMatchdayCoordinator.TickResult tick = harness.coordinator.tick();
            plannerNextDue = tick.nextDueAt();
            plannerTicks.add(new PlannerTickTrace(tickAt, activeWorkers, pendingResponseReleases,
                    acknowledgedWorkerStates, workerGenerations, completedWorkers, tick.nextDueAt()));
            if (tickAt.equals(startAt.plus(PLANNER_FIXED_DELAY))) {
                plannerSnapshotAtFirstFixedTick = new PlannerInputRepository(jdbc, harness.store,
                        TABLES, CAPTURED, harness.clock).load(SEASON);
            }
            nextPlannerTickAt = harness.clock.instant().plus(PLANNER_FIXED_DELAY);
        }

        private Map<Integer, String> activeWorkerStates() {
            Map<Integer, String> states = new TreeMap<>();
            active.forEach((worker, dispatch) -> {
                String state;
                if (dispatch.pendingResponse != null) {
                    state = "PENDING_RESPONSE " + dispatch.pendingResponse.arrival.kind()
                            + " generation=" + dispatch.pendingResponse.generation
                            + " releaseAt=" + dispatch.pendingResponse.releaseAt;
                } else if (dispatch.acknowledgedWait != null) {
                    state = "ACKNOWLEDGED_WAIT " + dispatch.acknowledgedWait.kind
                            + " nextDue=" + dispatch.acknowledgedWait.nextDue;
                } else if (dispatch.future.isDone()) {
                    state = "COMPLETED";
                } else {
                    state = "UNACKNOWLEDGED";
                }
                states.put(worker, state);
            });
            return Map.copyOf(states);
        }

        private void setToScheduledStart(int worker) {
            Instant due = nextAt[worker - 1];
            if (due.isAfter(harness.clock.instant())) advanceTo(due, "worker-start");
        }

        private void advanceTo(Instant instant, String reason) {
            if (!advanceToIfAcknowledged(instant, reason)) {
                throw new IllegalStateException("driver cannot advance without an acknowledged wait, "
                        + "controlled response release or completed dispatch for every active worker");
            }
        }

        private boolean advanceToIfAcknowledged(Instant instant, String reason) {
            Instant current = harness.clock.instant();
            if (!instant.isAfter(current)) return true;
            if (!activeStatesAcknowledged()) return false;
            if (!unassignedArrivals.isEmpty()) {
                throw new IllegalStateException("driver cannot advance with an unassigned source callback");
            }
            if (responses.stream().anyMatch(response -> response.releaseAt.isBefore(instant))) {
                throw new IllegalStateException("virtual time cannot advance past an unreleased source callback");
            }
            if (instant.isAfter(stopAt)) throw new IllegalStateException("virtual time ceiling exceeded");
            if (!lastReadyTargetGenerations.isEmpty()) advancesWithReadyTargets++;
            Instant nextRunDue = earliestActiveRunDue();
            lastNextWake = "reason=" + reason + ",from=" + current + ",to=" + instant
                    + ",plannerNextDue=" + plannerNextDue + ",earliestRunDue=" + nextRunDue
                    + ",readyTargetGenerations=" + lastReadyTargetGenerations
                    + ",activeWorkers=" + active.keySet();
            harness.clock.advance(Duration.between(current, instant));
            harness.turnNanoTime.addAndGet(Duration.between(current, instant).toNanos());
            harness.http.clockAdvancedForTests();
            return true;
        }

        private DispatchState start(int worker) {
            if (!canStartDispatch()) throw new IllegalStateException("dispatch ceiling exceeded");
            if (active.containsKey(worker)) throw new IllegalStateException("worker already has an active dispatch");
            DispatchState dispatch = new DispatchState(worker);
            dispatch.startedAt = harness.clock.instant();
            active.put(worker, dispatch);
            dispatches++;
            FutureTask<DispatchCompletion> task = new FutureTask<>(() -> {
                Thread current = Thread.currentThread();
                dispatch.thread = current;
                if (dispatchByThread.putIfAbsent(current, dispatch) != null) {
                    throw new IllegalStateException("dispatch worker thread was already linked");
                }
                try {
                    boolean claimed = harness.coordinator.dispatchOne(Integer.toString(worker));
                    return new DispatchCompletion(worker, claimed, harness.clock.instant());
                } finally {
                    if (!dispatchByThread.remove(current, dispatch)) {
                        throw new IllegalStateException("dispatch thread lost its owner mapping before completion");
                    }
                }
            });
            dispatch.future = task;
            harness.dispatchWorkers.execute(task);
            return dispatch;
        }

        private void assertWaitObservationsBalanced() {
            assertThat(observerFailure.get()).as("wait observer must link each park to its dispatch").isNull();
            assertThat(predecessorResumes).as("FIFO wait START/RESUME tokens must pair")
                    .hasValue(predecessorWaits.get());
            assertThat(spacingResumes).as("spacing wait START/RESUME tokens must pair")
                    .hasValue(spacingWaits.get());
            assertThat(dispatchByThread).as("completed dispatch threads must be unlinked").isEmpty();
        }

        private enum EventKind { RESPONSE, SPACING, WORKER_START, RUN_DUE, PLANNER_TICK }

        private record Event(Instant at, EventKind kind) { }

        private record PlannerTickTrace(Instant at, Set<Integer> activeWorkers,
                                        List<Instant> pendingResponseReleases,
                                        Map<Integer, String> acknowledgedWorkerStates,
                                        Map<Integer, Long> workerGenerations,
                                        Set<Integer> completedWorkers, Instant nextDueAt) {
            PlannerTickTrace {
                activeWorkers = Set.copyOf(activeWorkers);
                pendingResponseReleases = List.copyOf(pendingResponseReleases);
                acknowledgedWorkerStates = Map.copyOf(acknowledgedWorkerStates);
                workerGenerations = Map.copyOf(workerGenerations);
                completedWorkers = Set.copyOf(completedWorkers);
            }
        }

        private record WaitState(WaitKind kind, Instant nextDue, long token) { }

        private static final class ControlledResponse {
            private final DispatchState owner;
            private final RequestArrival arrival;
            private final Instant releaseAt;
            private final long generation;
            private boolean released;
            ControlledResponse(DispatchState owner, RequestArrival arrival, Instant releaseAt, long generation) {
                this.owner = owner;
                this.arrival = arrival;
                this.releaseAt = releaseAt;
                this.generation = generation;
            }
        }

        private static final class DispatchState {
            private final int worker;
            private final Set<Long> targetGenerations = new java.util.LinkedHashSet<>();
            private final List<String> targetRoles = new ArrayList<>();
            private Instant startedAt;
            private volatile Thread thread;
            private volatile Future<DispatchCompletion> future;
            private volatile WaitState acknowledgedWait;
            private ControlledResponse pendingResponse;
            private Long generation;
            private boolean generationMappingReleased;

            DispatchState(int worker) {
                this.worker = worker;
            }

            void releaseGenerationMapping() {
                if (generationMappingReleased) {
                    throw new IllegalStateException("dispatch generation mapping released more than once: "
                            + "worker=" + worker + " generation=" + generation);
                }
                generationMappingReleased = true;
            }

            synchronized void observe(WaitObservation observation, AtomicReference<String> failure) {
                WaitState current = acknowledgedWait;
                if (observation.phase() == WaitPhase.START) {
                    if (current != null) {
                        failure.compareAndSet(null, "a dispatch started a new wait before resuming its prior token");
                        acknowledgedWait = null;
                    } else {
                        acknowledgedWait = new WaitState(observation.kind(), observation.nextDue(),
                                observation.token());
                    }
                    return;
                }
                if (current == null || current.token != observation.token()
                        || current.kind != observation.kind()
                        || !java.util.Objects.equals(current.nextDue, observation.nextDue())) {
                    failure.compareAndSet(null, "wait RESUME did not match its START generation token");
                    acknowledgedWait = null;
                    return;
                }
                acknowledgedWait = null;
            }
        }
    }

    private static final class RequestServer implements AutoCloseable {
        private final MutableClock clock;
        private final HttpServer server;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final BlockingQueue<RequestArrival> arrivals = new LinkedBlockingQueue<>();
        private final List<RequestArrival> observed = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final SourceData source;
        private final AtomicReference<Instant> firstHistoryGet = new AtomicReference<>();
        private final AtomicReference<Integer> returnedGets = new AtomicReference<>(0);
        private final AtomicReference<Long> returnedRawBytes = new AtomicReference<>(0L);

        RequestServer(SourceData source, MutableClock clock) throws IOException {
            this.source = source;
            this.clock = clock;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/api/", this::handle);
            server.start();
        }

        URI baseUri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/");
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            SourceData.Body body;
            try {
                body = source.response(path);
            } catch (RuntimeException invalid) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            RequestArrival arrival = new RequestArrival(path, body.kind(), body.bytes(), clock.instant(),
                    new CountDownLatch(1));
            observed.add(arrival);
            if (body.kind() == EndpointKind.ELEMENT_SUMMARY) firstHistoryGet.compareAndSet(null, arrival.at());
            arrivals.add(arrival);
            try {
                if (!arrival.release().await(90, TimeUnit.SECONDS)) {
                    exchange.close();
                    return;
                }
                byte[] bytes = arrival.body();
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                returnedGets.updateAndGet(value -> value + 1);
                returnedRawBytes.updateAndGet(value -> value + bytes.length);
                exchange.close();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        }

        RequestArrival pollArrival(long timeout, TimeUnit unit) throws InterruptedException {
            return arrivals.poll(timeout, unit);
        }

        RequestArrival awaitArrival(long wallDeadline) throws Exception {
            while (System.nanoTime() < wallDeadline) {
                RequestArrival result = arrivals.poll(10, TimeUnit.MILLISECONDS);
                if (result != null) return result;
            }
            return null;
        }

        void releaseAll() {
            observed.forEach(arrival -> arrival.release().countDown());
        }

        Instant firstHistoryGet() { return firstHistoryGet.get(); }
        int returnedGets() { return returnedGets.get(); }
        long returnedRawBytes() { return returnedRawBytes.get(); }

        List<RequestArrival> historyGetsBetween(Instant fromInclusive, Instant toExclusive) {
            return observed.stream().filter(arrival -> arrival.kind() == EndpointKind.ELEMENT_SUMMARY
                    && !arrival.at().isBefore(fromInclusive) && arrival.at().isBefore(toExclusive)).toList();
        }

        Map<EndpointKind, Integer> getsByEndpoint() {
            Map<EndpointKind, Integer> result = new LinkedHashMap<>();
            observed.forEach(arrival -> result.merge(arrival.kind(), 1, Integer::sum));
            return Map.copyOf(result);
        }


        @Override
        public void close() throws Exception {
            releaseAll();
            server.stop(0);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS))
                    .as("loopback HTTP handlers must stop during cleanup").isTrue();
        }
    }

    private record RequestArrival(String path, EndpointKind kind, byte[] body, Instant at,
                                 CountDownLatch release) { }

    private static final class SourceData {
        private final byte[] bootstrap;
        private byte[] fixtures;
        private final List<Integer> playerIds;
        private final Map<Integer, byte[]> liveByEvent;
        private final Map<Integer, byte[]> histories;
        private final boolean fullRoster;
        private final int optionalOmittedPlayerId;

        private SourceData(byte[] bootstrap, byte[] fixtures, List<Integer> playerIds,
                           Map<Integer, byte[]> liveByEvent, Map<Integer, byte[]> histories,
                           boolean fullRoster, int optionalOmittedPlayerId) {
            this.bootstrap = bootstrap;
            this.fixtures = fixtures;
            this.playerIds = playerIds;
            this.liveByEvent = liveByEvent;
            this.histories = histories;
            this.fullRoster = fullRoster;
            this.optionalOmittedPlayerId = optionalOmittedPlayerId;
        }

        static SourceData load() throws Exception {
            return load(false);
        }


        static SourceData loadActiveAcceptanceRoster() throws Exception {
            return load(true);
        }

        private static SourceData load(boolean fullRoster) throws Exception {
            ObjectNode bootstrap = (ObjectNode) JSON.readTree(resource("bootstrap-static.json"));
            ArrayNode fixtureRows = (ArrayNode) JSON.readTree(resource("fixtures.json"));
            Map<Integer, List<JsonNode>> sourcePlayersByTeam = new TreeMap<>();
            bootstrap.withArray("elements").forEach(player -> sourcePlayersByTeam
                    .computeIfAbsent(player.get("team").intValue(), ignored -> new ArrayList<>()).add(player));
            ArrayNode selected = JSON.createArrayNode();
            Map<Integer, List<Integer>> playersByTeam = new TreeMap<>();
            Map<Integer, Integer> playerTeams = new HashMap<>();
            List<Integer> playerIds = new ArrayList<>();
            for (Map.Entry<Integer, List<JsonNode>> entry : sourcePlayersByTeam.entrySet()) {
                int count = fullRoster ? entry.getValue().size() : entry.getKey() <= 8 ? 4 : 3;
                List<Integer> ids = entry.getValue().stream().limit(count)
                        .map(player -> player.get("id").intValue()).sorted().toList();
                playersByTeam.put(entry.getKey(), ids);
                ids.forEach(id -> {
                    playerIds.add(id);
                    playerTeams.put(id, entry.getKey());
                });
            }
            Set<Integer> selectedIds = Set.copyOf(playerIds);
            ArrayNode trimmed = JSON.createArrayNode();
            bootstrap.withArray("elements").forEach(player -> {
                if (selectedIds.contains(player.get("id").intValue())) trimmed.add(player);
            });
            bootstrap.set("elements", trimmed);
            bootstrap.withArray("events").forEach(eventNode -> {
                ObjectNode event = (ObjectNode) eventNode;
                int id = event.get("id").intValue();
                event.put("finished", id <= 5).put("data_checked", id <= 5)
                        .put("is_current", id == 5).put("is_next", id == 6);
            });

            for (int index = 0; index < 50; index++) {
                ObjectNode fixture = (ObjectNode) fixtureRows.get(index);
                int home = fixture.get("team_h").intValue();
                int away = fixture.get("team_a").intValue();
                fixture.put("started", true).put("finished", true).put("finished_provisional", true)
                        .put("minutes", 90).put("team_h_score", 0).put("team_a_score", 0);
                ArrayNode stats = fixture.putArray("stats");
                ObjectNode minutes = stats.addObject().put("identifier", "minutes");
                addParticipants(minutes.putArray("h"), playersByTeam.get(home));
                addParticipants(minutes.putArray("a"), playersByTeam.get(away));
            }
            if (fullRoster) playerIds.sort(Integer::compareTo);
            int optionalOmittedPlayerId = omittedDefconPlayer(fixtureRows, playerIds, playerTeams);
            byte[] bootstrapBytes = JSON.writeValueAsBytes(bootstrap);
            byte[] fixtureBytes = JSON.writeValueAsBytes(fixtureRows);
            Map<Integer, byte[]> lives = createLiveManifests(fixtureRows, playersByTeam);
            Map<Integer, byte[]> histories = createHistories(fixtureRows, playerIds, playerTeams,
                    playersByTeam, optionalOmittedPlayerId);
            return new SourceData(bootstrapBytes, fixtureBytes, List.copyOf(playerIds),
                    Map.copyOf(lives), Map.copyOf(histories), fullRoster, optionalOmittedPlayerId);
        }

        Map<Integer, Set<Integer>> requiredHistoryPlayersByFixture(int fixtureCount) {
            Instant capturedAt = START;
            var interval = new CatalogueScheduleParser.ReadInterval(capturedAt, capturedAt);
            var snapshot = CatalogueScheduleParser.parse(SEASON,
                    new CatalogueScheduleParser.SourceDocument(bootstrap, interval),
                    new CatalogueScheduleParser.SourceDocument(fixtures, interval));
            Map<Integer, Set<Integer>> result = new TreeMap<>();
            for (int fixtureId = 1; fixtureId <= fixtureCount; fixtureId++) {
                int selectedFixtureId = fixtureId;
                var fixture = snapshot.fixtures().stream().filter(row -> row.id() == selectedFixtureId)
                        .findFirst().orElseThrow(() -> new IllegalArgumentException(
                                "Pinned schedule has no fixture " + selectedFixtureId));
                Set<Integer> required = new java.util.TreeSet<>();
                snapshot.players().stream().filter(player -> player.teamId() == fixture.homeTeamId()
                                || player.teamId() == fixture.awayTeamId())
                        .map(CatalogueScheduleParser.PlayerRow::id).forEach(required::add);
                var live = new CatalogueScheduleParser.SourceDocument(liveBytes(fixture.gameweek()), interval);
                required.addAll(FixtureVerification.participantIds(fixture, live));
                result.put(fixtureId, Set.copyOf(required));
            }
            return Map.copyOf(result);
        }

        byte[] bootstrapBytes() { return bootstrap; }
        byte[] fixtureBytes() { return fixtures; }
        byte[] liveBytes(int eventId) {
            byte[] body = liveByEvent.get(eventId);
            if (body == null) throw new IllegalArgumentException("No pinned live source for event " + eventId);
            return body;
        }
        byte[] historyBytes(int playerId) {
            byte[] body = histories.get(playerId);
            if (body == null) throw new IllegalArgumentException("No pinned history for player " + playerId);
            return body;
        }
        List<Integer> playerIds() { return playerIds; }
        boolean fullRoster() { return fullRoster; }
        int optionalOmittedPlayerId() { return optionalOmittedPlayerId; }

        void correctFixtureScores(Set<Integer> fixtureIds) throws IOException {
            ArrayNode corrected = (ArrayNode) JSON.readTree(fixtures);
            corrected.forEach(fixture -> {
                if (fixtureIds.contains(fixture.path("id").asInt())) {
                    ((ObjectNode) fixture).put("team_h_score", 1);
                }
            });
            fixtures = JSON.writeValueAsBytes(corrected);
        }

        Body response(String path) {
            if (path.equals("/api/bootstrap-static/")) return new Body(EndpointKind.BOOTSTRAP_STATIC, bootstrap);
            if (path.equals("/api/fixtures/")) return new Body(EndpointKind.FIXTURES, fixtures);
            if (path.startsWith("/api/event/") && path.endsWith("/live/")) {
                int event = Integer.parseInt(path.substring("/api/event/".length(), path.length() - "/live/".length()));
                return new Body(EndpointKind.EVENT_LIVE, liveByEvent.getOrDefault(event,
                        "{\"elements\":[]}".getBytes(StandardCharsets.UTF_8)));
            }
            if (path.startsWith("/api/element-summary/") && path.endsWith("/")) {
                int player = Integer.parseInt(path.substring("/api/element-summary/".length(), path.length() - 1));
                byte[] body = histories.get(player);
                if (body == null) throw new IllegalArgumentException("Unknown pinned player id");
                return new Body(EndpointKind.ELEMENT_SUMMARY, body);
            }
            throw new IllegalArgumentException("Unsupported local FPL path");
        }

        private static void addParticipants(ArrayNode rows, List<Integer> ids) {
            ids.stream().limit(2).forEach(id -> rows.addObject().put("element", id).put("value", 90));
        }

        private static int omittedDefconPlayer(ArrayNode fixtures, List<Integer> players,
                                                Map<Integer, Integer> playerTeams) {
            JsonNode target = null;
            for (JsonNode fixture : fixtures) {
                if (fixture.path("id").asInt() == 45) {
                    target = fixture;
                    break;
                }
            }
            if (target == null) throw new IllegalArgumentException("Acceptance source lacks fixture 45");
            int home = target.path("team_h").asInt();
            int away = target.path("team_a").asInt();
            return players.stream().filter(player -> playerTeams.get(player) == home
                            || playerTeams.get(player) == away)
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                            "Acceptance fixture 45 has no full-roster player"));
        }

        private static byte[] padHistory(byte[] source) {
            int targetSize = 8 * 1024;
            if (source.length >= targetSize) return source;
            byte[] padded = new byte[targetSize];
            System.arraycopy(source, 0, padded, 0, source.length);
            for (int index = source.length; index < padded.length; index++) padded[index] = ' ';
            return padded;
        }

        private static Map<Integer, byte[]> createLiveManifests(ArrayNode fixtures,
                                                                Map<Integer, List<Integer>> playersByTeam)
                throws IOException {
            Map<Integer, Map<Integer, ObjectNode>> events = new TreeMap<>();
            for (JsonNode fixture : fixtures) {
                if (!fixture.get("finished").booleanValue()) continue;
                int eventId = fixture.get("event").intValue();
                int fixtureId = fixture.get("id").intValue();
                for (int team : List.of(fixture.get("team_h").intValue(), fixture.get("team_a").intValue())) {
                    for (int player : playersByTeam.get(team).stream().limit(2).toList()) {
                        ObjectNode element = events.computeIfAbsent(eventId, ignored -> new TreeMap<>())
                                .computeIfAbsent(player, id -> {
                                    ObjectNode created = JSON.createObjectNode().put("id", id);
                                    created.putArray("explain");
                                    return created;
                                });
                        ObjectNode explanation = element.withArray("explain").addObject().put("fixture", fixtureId);
                        explanation.putArray("stats").addObject().put("identifier", "minutes")
                                .put("value", 90).put("points", 0).put("points_modification", 0);
                    }
                }
            }
            Map<Integer, byte[]> result = new TreeMap<>();
            for (Map.Entry<Integer, Map<Integer, ObjectNode>> event : events.entrySet()) {
                ObjectNode root = JSON.createObjectNode();
                ArrayNode elements = root.putArray("elements");
                event.getValue().values().forEach(elements::add);
                result.put(event.getKey(), JSON.writeValueAsBytes(root));
            }
            return result;
        }

        private static Map<Integer, byte[]> createHistories(ArrayNode fixtures, List<Integer> players,
                                                              Map<Integer, Integer> playerTeams,
                                                              Map<Integer, List<Integer>> playersByTeam,
                                                              int optionalOmittedPlayerId) throws IOException {
            Map<Integer, byte[]> result = new TreeMap<>();
            for (int player : players) {
                int team = playerTeams.get(player);
                boolean participant = playersByTeam.get(team).indexOf(player) < 2;
                ObjectNode root = JSON.createObjectNode();
                ArrayNode history = root.putArray("history");
                for (JsonNode fixture : fixtures) {
                    if (!fixture.get("finished").booleanValue()) continue;
                    int home = fixture.get("team_h").intValue();
                    int away = fixture.get("team_a").intValue();
                    if (team != home && team != away) continue;
                    boolean wasHome = team == home;
                    ObjectNode row = history.addObject().put("element", player)
                            .put("fixture", fixture.get("id").intValue())
                            .put("round", fixture.get("event").intValue())
                            .put("opponent_team", wasHome ? away : home).put("was_home", wasHome)
                            .put("kickoff_time", fixture.get("kickoff_time").textValue())
                            .put("minutes", participant ? 90 : 0).put("total_points", 0).put("bonus", 0)
                            .put("bps", 0).put("goals_scored", 0).put("assists", 0)
                            .put("clean_sheets", 0).put("goals_conceded", 0).put("own_goals", 0)
                            .put("penalties_saved", 0).put("penalties_missed", 0).put("saves", 0)
                            .put("yellow_cards", 0).put("red_cards", 0).put("starts", participant ? 1 : 0)
                            .put("value", 50).put("selected", 0).put("transfers_in", 0).put("transfers_out", 0)
                            .put("expected_goals", "0.00").put("expected_assists", "0.00")
                            .put("expected_goal_involvements", "0.00").put("expected_goals_conceded", "0.00")
                            .put("influence", "0.0").put("creativity", "0.0").put("threat", "0.0")
                            .put("ict_index", "0.0").put("clearances_blocks_interceptions", 0)
                            .put("recoveries", 0).put("tackles", 0);
                    if (player != optionalOmittedPlayerId || fixture.get("id").intValue() != 45) {
                        row.put("defensive_contribution", 0);
                    }
                }
                byte[] encoded = JSON.writeValueAsBytes(root);
                result.put(player, padHistory(encoded));
            }
            return result;
        }

        private static InputStream resource(String name) {
            InputStream input = OwnedMatchdayAdmissionProgressIntegrationTest.class
                    .getResourceAsStream("/owned-sync-source-fixtures/" + name);
            if (input == null) throw new IllegalArgumentException("Missing pinned " + name);
            return input;
        }

        private static byte[] readResource(String name) throws IOException {
            try (InputStream input = resource(name)) {
                return input.readAllBytes();
            }
        }

        private record Body(EndpointKind kind, byte[] bytes) { }
    }

    private static String resource(String name) throws IOException {
        return new String(SourceData.readResource(name), StandardCharsets.UTF_8);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        MutableClock(Instant initial) { current = new AtomicReference<>(initial); }
        void advance(Duration duration) { current.updateAndGet(value -> value.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current.get(); }
    }
}
