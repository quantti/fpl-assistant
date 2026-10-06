package dev.quantti.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
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
import dev.quantti.fplassistant.testsupport.SharedStatsSeed;
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
    private static final Duration WORKER_DELAY = Duration.ofSeconds(1);
    private static final Duration VIRTUAL_LIMIT = Duration.ofSeconds(60);
    private static final Duration DRAIN_VIRTUAL_LIMIT = Duration.ofHours(2);
    private static final Duration ROSTER_BASELINE_VIRTUAL_LIMIT = Duration.ofSeconds(400);
    private static final Duration ACTIVE_RECOVERY_VIRTUAL_LIMIT = Duration.ofHours(48);
    private static final Duration BLOCKED_HISTORY_HOLD = Duration.ofHours(1);
    private static final int ACTIVE_RECOVERY_CLAIM_LIMIT = 6_000;
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
    private static final int ROSTER_BASELINE_ADMISSION_LIMIT = 350;
    private static final int ROSTER_BASELINE_DISPATCH_LIMIT = 500;
    private static final long ROSTER_BASELINE_WALL_LIMIT_NANOS = TimeUnit.SECONDS.toNanos(240);
    private static final long ROSTER_BASELINE_DRAIN_RESERVE_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final int FIXTURE_RUNS = 50;
    private static final int HISTORY_RUNS = 68;
    private static final int FULL_ROSTER_PLAYERS = 657;
    private static final int FULL_ROSTER_CLUBS = 20;
    private static final int SEEDED_PARTIALS = 10;
    private static final long WALL_LIMIT_NANOS = TimeUnit.SECONDS.toNanos(110);
    private static final long DRAIN_WALL_LIMIT_NANOS = TimeUnit.SECONDS.toNanos(300);
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
        SharedStatsSeed.ensureOwnedSyncCaptureSeason(jdbc);
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
    void pendingPreparationGoalWaitsForTheDueScheduleReceipt() throws Exception {
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
                assertThat(pendingFixtureRuns(resumed.fence.epoch(), neverPublished))
                        .as("the old unknown-future parent gate does not enqueue a recovery before the due capture")
                        .isZero();
                var unknownParent = resumed.publisher.admitAttempt(SEASON, 41,
                        dev.quantti.fplassistant.sync.SharedFixtureRecoveryPolicy.SourceStatus.UNKNOWN);
                assertThat(unknownParent.admitted()).isFalse();
                assertThat(unknownParent.parentRefreshRequired()).isTrue();
                assertThat(jdbc.queryForObject("SELECT recovery_started_at IS NULL FROM "
                        + TABLES.fixtureSync() + " WHERE season_id=? AND fixture_id=41",
                        Boolean.class, SEASON.id())).isTrue();
                ManualDriver preparationGoal = new ManualDriver(resumed,
                        DriverGoal.pendingFixtures(neverPublished), Duration.ofMinutes(1), 300, 300, 300);
                assertThat(preparationGoal.hasDueCanonicalRuns()).isTrue();
                assertThat(preparationGoal.goalSatisfied())
                        .as("the pending-only goal must not exit ahead of the due canonical receipt")
                        .isFalse();

                assertThat(resumed.dispatchOneWithControlledResponse("schedule-boundary-publication"))
                        .isEqualTo(1);
                assertDailyCapture(CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY, ACTIVE_SCHEDULE_CAPTURE);
                assertThat(jdbc.queryForObject("SELECT source_read_started_at FROM " + TABLES.receipt()
                        + " WHERE season=? AND publication_key=?", Timestamp.class,
                        SEASON.code(), CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY).toInstant())
                        .isEqualTo(ACTIVE_SCHEDULE_CAPTURE);
                resumed.coordinator.tick();
                assertThat(pendingFixtureRuns(resumed.fence.epoch(), neverPublished)).isEqualTo(10);
                var reservation = resumed.publisher.admitAttempt(SEASON, 41,
                        dev.quantti.fplassistant.sync.SharedFixtureRecoveryPolicy.SourceStatus.FINISHED);
                assertThat(reservation.admitted()).isTrue();
                assertThat(jdbc.queryForObject("SELECT recovery_started_at FROM " + TABLES.fixtureSync()
                        + " WHERE season_id=? AND fixture_id=41", Timestamp.class, SEASON.id()).toInstant())
                        .isEqualTo(clock.instant());
                assertThat(preparationGoal.goalSatisfied()).isTrue();
                Instant firstFinishedAt = jdbc.queryForObject("SELECT first_finished_observed_at FROM "
                        + TABLES.fixtureSync() + " WHERE season_id=? AND fixture_id=41", Timestamp.class,
                        SEASON.id()).toInstant();
                assertThat(firstFinishedAt.plus(Duration.ofHours(12)))
                        .as("the honest planner observation is at least 12h old at the preserved legacy due")
                        .isBeforeOrEqualTo(LEGACY_DAILY_DUE);
                assertThat(activeClaimAttempts(baselineEpoch)
                        + activeClaimAttempts(resumed.fence.epoch())).isLessThan(300);
            }
        } finally {
            cleanActiveAcceptanceSeason();
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
    void scheduleCorrectionPreservesFixtureThreeBaselineOwnershipWithoutInventingAnObservation() throws Exception {
        SourceData scheduleSource = SourceData.loadActiveAcceptanceRoster();
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
                assertThat(before.currentVerifiedGeneration()).isNotNull();
                assertThat(before.lastVerifiedGeneration()).isEqualTo(before.currentVerifiedGeneration());
                scheduleSource.correctFixtureScores(Set.of(3));
                baseline.catalogue.enqueueSchedule(SEASON, clock.instant());
                assertThat(baseline.dispatchOneWithControlledResponse("fixture-3-schedule-correction")).isEqualTo(1);

                FixtureOwnershipSnapshot after = fixtureOwnershipSnapshot(3);
                Map<Integer, FixturePublicationComponents> afterComponents =
                        fixturePublicationComponents(Set.of(3));
                System.out.printf("OWNED_SCHEDULE_INVALIDATION fixture=3 before=%s after=%s components=%s%n",
                        before.flags(), after.flags(), fixturePublicationComponentDiffs(beforeComponents, afterComponents));
                assertScheduleInvalidationRetainsActualOwnership(3, before, after);
                assertScheduleCorrectionComponentTransition(beforeComponents, afterComponents, Set.of(3));
                assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats"
                        + " WHERE season_id=? AND fixture_id=3", Integer.class, SEASON.id())).isPositive();
                assertThat(pfsRows("OFF", 3)).as("fixture 3 physical PFS is masked after revocation").isEmpty();
                assertThat(pfsRows("ACTIVE", 3)).as("fixture 3 is unreadable until reverified").isEmpty();

                Map<Integer, FixtureVerification.Decision> firstFresh = publishDirectFixtureBatch(baseline, 3, 3);
                assertThat(firstFresh.get(3).state()).isEqualTo(FixtureVerification.State.PENDING);
                assertThat(firstFresh.get(3).reasons())
                        .containsExactly(FixtureVerification.Reason.NEEDS_SECOND_OBSERVATION);
                FixtureOwnershipSnapshot freshPending = fixtureOwnershipSnapshot(3);
                System.out.printf("OWNED_SCHEDULE_INVALIDATION fixture=3 freshPending=%s%n", freshPending.flags());
                String details = "fixture 3 first post-correction observation=" + freshPending.flags();
                assertThat(freshPending.state()).as(details).isNotEqualTo("VERIFIED");
                assertThat(freshPending.currentVerifiedGeneration()).as(details).isNull();
                assertThat(freshPending.lastVerifiedGeneration()).as(details)
                        .isEqualTo(before.lastVerifiedGeneration());
                assertThat(freshPending.observationGeneration()).as(details).isNotNull();
                assertThat(freshPending.observationCoherent()).as(details).isEqualTo("true");
                assertThat(pfsRows("OFF", 3)).as("fresh pending data remains masked").isEmpty();
                Map<Integer, FixturePublicationComponents> freshComponents =
                        fixturePublicationComponents(Set.of(3));
                assertPostRecoveryPublicationComponents(afterComponents, freshComponents, Set.of(3));
                assertChangedCorrectionsRemainScheduled(baseline, Set.of(3),
                        Map.of(3, before.lastVerifiedGeneration()));
            }
        } finally {
            cleanActiveAcceptanceSeason();
        }
    }

    @Test
    @Timeout(value = 1_800, unit = TimeUnit.SECONDS)
    void combinedActiveRecoveryPublishesAllTenNeverPublishedFixturesAfterRestart() throws Exception {
        assertThat(ACTIVE_RECOVERY_VIRTUAL_LIMIT).isEqualTo(Duration.ofHours(48));
        assertThat(ACTIVE_RECOVERY_CLAIM_LIMIT).isEqualTo(6_000);
        assertThat(ACTIVE_RECOVERY_DISPATCH_LIMIT).isEqualTo(6_000);
        SourceData activeSource = SourceData.loadActiveAcceptanceRoster();
        assertThat(activeSource.playerIds()).hasSize(FULL_ROSTER_PLAYERS).doesNotHaveDuplicates();
        assertThat(activeSource.teamCount()).isEqualTo(FULL_ROSTER_CLUBS);
        assertThat(activeSource.finishedFixtureCount()).isEqualTo(FIXTURE_RUNS);
        Set<Integer> neverPublished = java.util.stream.IntStream.rangeClosed(41, 50).boxed()
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        prepareActiveAcceptanceSeason();

        MutableClock clock = new MutableClock(BASELINE_SOURCE_START);
        long wallStartedAt = System.nanoTime();
        long wallDeadline = wallStartedAt + TimeUnit.SECONDS.toNanos(1_500);
        PayloadReadProbe.Snapshot blockedRetryBodies;
        PayloadReadProbe.Snapshot completePublicationBodies;
        ActiveAcceptanceMetrics accepted;
        Map<Integer, Long> servedCorrectionReceipts;
        Map<Integer, Long> initialBaselineReceipts;
        Map<Integer, Instant> baselineFirstVerifiedAt;
        Map<Integer, FixturePublicationComponents> baselineComponents;
        String servedFingerprintChecksum;
        FixtureOwnershipSnapshot fixtureThreeBeforeScheduleCorrection;
        FixtureOwnershipSnapshot fixtureThreeAfterScheduleCorrection;
        try {
            try (Harness baseline = Harness.active(1, wallDeadline, wallStartedAt, activeSource,
                    clock, true, false)) {
                baseline.dispatchCanonicalCycle();
                new PlannerInputRepository(jdbc, baseline.store, TABLES, CAPTURED, clock).load(SEASON);
                Set<Integer> baselineFixtures = java.util.stream.IntStream.rangeClosed(1, 40).boxed()
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
                reconcileHistoryFingerprints(baseline, baselineFixtures);
                assertThat(baseline.store.currentCanonicalParentEvidence(SEASON.code(), 1, clock.instant())
                        .currentPublished()).as("the active canonical source pair is independently published")
                        .isTrue();
                Map<Integer, FixtureVerification.Decision> firstObservations =
                        publishDirectFixtureBatch(baseline, 1, 40);
                assertThat(firstObservations.values()).allSatisfy(decision -> {
                    assertThat(decision.state()).isEqualTo(FixtureVerification.State.PENDING);
                    assertThat(decision.reasons()).containsExactly(FixtureVerification.Reason.NEEDS_SECOND_OBSERVATION);
                });
                clock.advance(Duration.ofMinutes(11));
                Map<Integer, FixtureVerification.Decision> baselineVerifications =
                        publishDirectFixtureBatch(baseline, 1, 40);
                assertThat(baselineVerifications.values()).allSatisfy(decision ->
                        assertThat(decision.state()).isEqualTo(FixtureVerification.State.VERIFIED));
                Set<Integer> baselineFixtureIds = java.util.stream.IntStream.rangeClosed(1, 40).boxed()
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
                assertBaselineCandidateCoverage(baselineFixtureIds, activeSource);
                assertReceiptBackedFixtures(baseline.fence, baselineFixtureIds);
                servedCorrectionReceipts = new TreeMap<>();
                for (int fixtureId : baselineFixtureIds) {
                    servedCorrectionReceipts.put(fixtureId, jdbc.queryForObject("SELECT latest_generation FROM "
                            + TABLES.receipt() + " WHERE season=? AND publication_key=?",
                            Long.class, SEASON.code(), "fixture:" + fixtureId));
                }

                baselineFirstVerifiedAt = fixtureFirstVerifiedTimes(baselineFixtureIds);
                initialBaselineReceipts = Map.copyOf(servedCorrectionReceipts);
                advanceGlobalTimeline(clock, SAME_FINGERPRINT_CORRECTION);
                servedCorrectionReceipts.putAll(publishSameFingerprintCorrectionBatch(baseline, 1, 40));
                assertThat(servedCorrectionReceipts).hasSize(40);
                assertQualifyingBaselineReceipts(baselineFixtureIds, servedCorrectionReceipts,
                        initialBaselineReceipts, baselineFirstVerifiedAt);
                servedFingerprintChecksum = historyFingerprintChecksum(baselineFixtureIds);
                for (int fixtureId : baselineFixtureIds) {
                    Instant firstVerified = jdbc.queryForObject("SELECT first_verified_at FROM "
                            + TABLES.fixtureSync() + " WHERE season_id=? AND fixture_id=?",
                            Timestamp.class, SEASON.id(), fixtureId).toInstant();
                    Instant correctionRead = jdbc.queryForObject("SELECT source_read_started_at FROM "
                            + TABLES.receipt() + " WHERE season=? AND publication_key=?",
                            Timestamp.class, SEASON.code(), "fixture:" + fixtureId).toInstant();
                    assertThat(correctionRead)
                            .as("fixture %d has a same-fingerprint read covering 48h", fixtureId)
                            .isAfterOrEqualTo(firstVerified.plus(Duration.ofHours(48)));
                }

                fixtureThreeBeforeScheduleCorrection = fixtureOwnershipSnapshot(3);
                Map<Integer, FixturePublicationComponents> beforeScheduleComponents =
                        fixturePublicationComponents(baselineFixtureIds);
                activeSource.correctFixtureScores(Set.of(1, 2, 3, 4, 5));
                baseline.catalogue.enqueueSchedule(SEASON, clock.instant());
                assertThat(baseline.dispatchOneWithControlledResponse("schedule-correction")).isEqualTo(1);
                Set<Integer> changedCorrections = Set.of(1, 2, 3, 4, 5);
                assertScheduleCorrectionsArePending(servedCorrectionReceipts, changedCorrections);
                Map<Integer, FixturePublicationComponents> afterScheduleComponents =
                        fixturePublicationComponents(baselineFixtureIds);
                assertScheduleCorrectionComponentTransition(beforeScheduleComponents, afterScheduleComponents,
                        changedCorrections);
                fixtureThreeAfterScheduleCorrection = fixtureOwnershipSnapshot(3);
                System.out.printf("OWNED_ACTIVE_SCHEDULE_INVALIDATION fixture=3 before=%s after=%s%n",
                        fixtureThreeBeforeScheduleCorrection.flags(), fixtureThreeAfterScheduleCorrection.flags());
                assertScheduleInvalidationRetainsActualOwnership(3, fixtureThreeBeforeScheduleCorrection,
                        fixtureThreeAfterScheduleCorrection);
                // Preserve the publication/core/evidence baseline after the intentional source correction.
                baselineComponents = afterScheduleComponents;

                advanceGlobalTimeline(clock, LEGACY_GATE_OBSERVED_AT);
                baseline.catalogue.enqueueSchedule(SEASON, clock.instant());
                assertThat(baseline.dispatchOneWithControlledResponse("legacy-gate-snapshot")).isEqualTo(1);
                new PlannerInputRepository(jdbc, baseline.store, TABLES, CAPTURED, clock).load(SEASON);
                advanceGlobalTimeline(clock, ACTIVE_RECOVERY_START);
                seedLegacyDailyOnlyGates(baseline, neverPublished);
                assertLegacyDailyOnlyGates(neverPublished);
                assertNeverPublished(neverPublished);
            }
            assertThat(runtimeMode()).isEqualTo("OFF");
            assertThat(pfsRows("OFF", 3)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats WHERE season_id=? AND fixture_id=3",
                    Integer.class, SEASON.id())).isGreaterThan(0);

            try (Harness resumed = Harness.active(1, wallDeadline, wallStartedAt, activeSource,
                    clock, false, false)) {
                assertRetainedReceiptHistory(servedCorrectionReceipts);
                assertPostRecoveryPublicationComponents(baselineComponents,
                        fixturePublicationComponents(java.util.stream.IntStream.rangeClosed(1, 40).boxed()
                                .collect(java.util.stream.Collectors.toUnmodifiableSet())), Set.of(1, 2, 3, 4, 5));
                assertThat(historyFingerprintChecksum(java.util.stream.IntStream.rangeClosed(1, 40).boxed()
                        .collect(java.util.stream.Collectors.toUnmodifiableSet())))
                        .as("baseline history fingerprints remain unchanged after reactivation")
                        .isEqualTo(servedFingerprintChecksum);
                var beforeParent = resumed.coordinator.tick();
                assertThat(pendingFixtureRuns(resumed.fence.epoch(), neverPublished)).isZero();
                assertNeverPublished(neverPublished);
                assertThat(beforeParent.dueSlots()).isGreaterThanOrEqualTo(0);
                assertThat(beforeParent.nextDueAt()).isEqualTo(ACTIVE_PRICE_CAPTURE);
                assertThat(ACTIVE_PRICE_CAPTURE.atZone(UK).getHour()).isEqualTo(2);
                assertThat(ACTIVE_SCHEDULE_CAPTURE.atZone(UK).getHour()).isEqualTo(2);
                assertThat(ACTIVE_SCHEDULE_CAPTURE.atZone(UK).getMinute()).isEqualTo(10);
                assertThat(LEGACY_DAILY_DUE.atZone(UK).getHour()).isEqualTo(3);
                assertThat(LEGACY_DAILY_DUE.atZone(UK).getMinute()).isZero();

                ManualDriver preparationDriver = new ManualDriver(resumed,
                        DriverGoal.pendingFixtures(neverPublished),
                        ACTIVE_RECOVERY_VIRTUAL_LIMIT.plus(
                                Duration.between(ACTIVE_RECOVERY_START, LEGACY_DAILY_DUE)),
                        Math.max(0, ACTIVE_RECOVERY_CLAIM_LIMIT - activeClaimAttempts(resumed.fence.epoch())),
                        Math.max(0, ACTIVE_RECOVERY_CLAIM_LIMIT - resumed.durableAdmissionCount()),
                        Math.max(0, ACTIVE_RECOVERY_DISPATCH_LIMIT - resumed.controlledDispatchCalls));
                resumed.http.setWaitObserverForTests(preparationDriver::observeWait);
                preparationDriver.run();
                System.out.printf("OWNED_ACTIVE_PREPARATION stop=%s clock=%s next=%s sourceReceipts=%s "
                                + "pending=%d fixtureRuns=%s gates=%s claims=%d admissions=%d dispatches=%d "
                                + "driverClaims=%d driverAdmissions=%d%n",
                        preparationDriver.stopReason, clock.instant(), preparationDriver.plannerNextDue,
                        jdbc.queryForList("SELECT publication_key,latest_generation,source_read_started_at,"
                                + "source_read_finished_at,published_at FROM "
                                + TABLES.receipt() + " WHERE season=? AND publication_key IN (?,?)"
                                + " ORDER BY publication_key", SEASON.code(),
                                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY,
                                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY),
                        pendingFixtureRuns(resumed.fence.epoch(), neverPublished),
                        jdbc.queryForList("SELECT generation,publication_key,state,attempt_count,due_at "
                                + "FROM " + TABLES.run() + " WHERE season=? AND runtime_mode='ACTIVE' "
                                + "AND runtime_epoch=? AND kind='FIXTURE_VERIFICATION' "
                                + "AND state IN ('PENDING','LEASED') ORDER BY due_at,generation",
                                SEASON.code(), resumed.fence.epoch()), fixtureGateTrace(neverPublished),
                        activeClaimAttempts(resumed.fence.epoch()), resumed.durableAdmissionCount(),
                        preparationDriver.dispatches + resumed.controlledDispatchCalls,
                        preparationDriver.claims, preparationDriver.admissions.size());
                assertThat(clock.instant()).isAfterOrEqualTo(LEGACY_DAILY_DUE);
                assertThat(preparationDriver.stopReason).isEqualTo("DRAINED");
                assertThat(preparationDriver.goalSatisfied()).isTrue();
                assertThat(resumed.store.currentCanonicalParentEvidence(SEASON.code(), 41, clock.instant())
                        .currentPublished()).isTrue();
                assertThat(pendingFixtureRuns(resumed.fence.epoch(), neverPublished))
                        .as("legacy DAILY_ONLY jobs become work at their real 03:00 UK due: gateTrace=%s pending=%s",
                                fixtureGateTrace(neverPublished),
                                jdbc.queryForList("SELECT generation,publication_key,state FROM " + TABLES.run()
                                        + " WHERE season=? AND runtime_mode='ACTIVE' AND runtime_epoch=?"
                                        + " AND kind='FIXTURE_VERIFICATION' AND state IN ('PENDING','LEASED')",
                                        SEASON.code(), resumed.fence.epoch()))
                        .isEqualTo(10);
                assertDailyCapture(CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY,
                        ACTIVE_PRICE_CAPTURE);
                assertDailyCapture(CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY,
                        ACTIVE_SCHEDULE_CAPTURE);
                assertNeverPublished(neverPublished);
                int nonCanonicalAttemptsBefore = nonCanonicalAttempts(resumed.fence.epoch());
                resumed.catalogue.enqueueSchedule(SEASON, clock.instant());
                assertThat(resumed.dispatchOneWithControlledResponse("canonical-preemption")).isEqualTo(1);
                assertThat(nonCanonicalAttempts(resumed.fence.epoch()))
                        .as("a due canonical refresh preempts the queued fixture/history backlog")
                        .isEqualTo(nonCanonicalAttemptsBefore);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                        + " WHERE season=? AND runtime_mode='ACTIVE' AND runtime_epoch=? AND kind='CATALOGUE_SCHEDULE' "
                        + "AND state='COMPLETED'", Integer.class, SEASON.code(), resumed.fence.epoch()))
                        .isGreaterThanOrEqualTo(1);

                Map<Long, Instant> heldHistoryRuns = new TreeMap<>();
                mergeHistoryDueTimes(heldHistoryRuns, deferPendingHistoriesUntil(
                        clock.instant().plus(BLOCKED_HISTORY_HOLD)));
                for (int attempt = 0; attempt < ACTIVE_RECOVERY_DISPATCH_LIMIT
                        && pendingManifestCount(resumed.fence.epoch()) == 0; attempt++) {
                    assertThat(resumed.dispatchOneWithControlledResponse("active-fixture-manifest-" + attempt))
                            .isEqualTo(1);
                    mergeHistoryDueTimes(heldHistoryRuns, deferPendingHistoriesUntil(
                            clock.instant().plus(BLOCKED_HISTORY_HOLD)));
                }
                long blockedGeneration = newestPendingManifestGeneration(resumed.fence.epoch());
                int blockedAttemptsBefore = jdbc.queryForObject("SELECT attempt_count FROM " + TABLES.run()
                        + " WHERE generation=?", Integer.class, blockedGeneration);
                mergeHistoryDueTimes(heldHistoryRuns, deferPendingHistoriesUntil(
                        clock.instant().plus(BLOCKED_HISTORY_HOLD)));
                boolean sawBodyFreeBlockedRetry = false;
                resumed.resetPayloadReads();
                blockedRetryBodies = resumed.payloadReads.snapshot();
                for (int attempt = 0; attempt < ACTIVE_RECOVERY_DISPATCH_LIMIT; attempt++) {
                    int getsBefore = resumed.server.returnedGets();
                    resumed.resetPayloadReads();
                    int claimed = resumed.dispatchOneWithControlledResponse("active-blocked-retry-" + attempt);
                    ControlledDispatchTrace trace = resumed.controlledDispatchTrace.getLast();
                    if (trace.generation() == blockedGeneration) {
                        assertThat(claimed).isEqualTo(1);
                        blockedRetryBodies = resumed.payloadReads.snapshot();
                        assertThat(resumed.server.returnedGets()).as("blocked retry makes no HTTP request")
                                .isEqualTo(getsBefore);
                        sawBodyFreeBlockedRetry = true;
                        break;
                    }
                    if (claimed == 0) {
                        OwnedMatchdayCoordinator.TickResult tick = resumed.coordinator.tick();
                        Instant nextEvent = tick.nextDueAt();
                        Instant nextRunDue = resumed.earliestActiveRunDue();
                        if (nextRunDue != null && (nextEvent == null || nextRunDue.isBefore(nextEvent))) {
                            nextEvent = nextRunDue;
                        }
                        if (nextEvent == null) break;
                        if (!nextEvent.isAfter(clock.instant())) {
                            throw new AssertionError("global timeline found due queue work that was not claimable: "
                                    + "now=" + clock.instant() + " nextEvent=" + nextEvent
                                    + " runs=" + jdbc.queryForList("SELECT generation,state,due_at,"
                                    + "requested_source_after FROM " + TABLES.run()
                                    + " WHERE season=? AND runtime_mode='ACTIVE' AND runtime_epoch=?"
                                    + " AND state IN ('PENDING','LEASED') ORDER BY generation",
                                    SEASON.code(), resumed.fence.epoch()));
                        }
                        resumed.advanceControlledTime(nextEvent);
                        continue;
                    }
                    mergeHistoryDueTimes(heldHistoryRuns, deferPendingHistoriesUntil(
                            clock.instant().plus(BLOCKED_HISTORY_HOLD)));
                }
                assertThat(sawBodyFreeBlockedRetry).as("FIFO dispatcher eventually retries the blocked manifest")
                        .isTrue();
                assertThat(jdbc.queryForObject("SELECT state='PENDING' AND attempt_count=? FROM "
                        + TABLES.run() + " WHERE generation=?", Boolean.class,
                        blockedAttemptsBefore + 1, blockedGeneration))
                        .as("the real blocked fixture lane retries the same unfinished manifest")
                        .isTrue();
                assertThat(blockedRetryBodies.bodyValues()).as("blocked manifest retries hydrate no bodies")
                        .isZero();
                assertThat(blockedRetryBodies.logicalBodies().values())
                        .allSatisfy(metrics -> assertThat(metrics.bodyValues()).isZero());
                assertThat(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + TABLES.fixtureDependencyManifest()
                        + " WHERE run_generation=? AND cardinality(required_player_ids)>0)",
                        Boolean.class, blockedGeneration)).isTrue();
                restoreHistoryDueTimes(heldHistoryRuns);

                resumed.resetPayloadReads();
                int claimsBeforeDriver = activeClaimAttempts(resumed.fence.epoch());
                int admissionsBeforeDriver = resumed.durableAdmissionCount();
                int dispatchesBeforeDriver = resumed.controlledDispatchCalls + preparationDriver.dispatches;
                Duration virtualRemaining = ACTIVE_RECOVERY_VIRTUAL_LIMIT.minus(
                        Duration.between(LEGACY_DAILY_DUE, clock.instant()));
                if (virtualRemaining.isNegative()) virtualRemaining = Duration.ZERO;
                ManualDriver driver = new ManualDriver(resumed,
                        DriverGoal.fixtures(neverPublished), virtualRemaining,
                        Math.max(0, ACTIVE_RECOVERY_CLAIM_LIMIT - claimsBeforeDriver),
                        Math.max(0, ACTIVE_RECOVERY_CLAIM_LIMIT - admissionsBeforeDriver),
                        Math.max(0, ACTIVE_RECOVERY_DISPATCH_LIMIT - dispatchesBeforeDriver));
                resumed.http.setWaitObserverForTests(driver::observeWait);
                driver.run();
                completePublicationBodies = resumed.payloadReads.snapshot();
                System.out.printf("OWNED_ACTIVE_RECOVERY_DRIVER stop=%s clock=%s next=%s "
                                + "claims=%d admissions=%d dispatches=%d lastAdmissions=%s completions=%d "
                                + "pendingTarget=%d gates=%s%n",
                        driver.stopReason, clock.instant(), driver.plannerNextDue,
                        activeClaimAttempts(resumed.fence.epoch()), resumed.durableAdmissionCount(),
                        preparationDriver.dispatches + driver.dispatches + resumed.controlledDispatchCalls,
                        driver.admissions.subList(Math.max(0, driver.admissions.size() - 10),
                                driver.admissions.size()), driver.completions.size(),
                        pendingFixtureRuns(resumed.fence.epoch(), neverPublished), fixtureGateTrace(neverPublished));
                accepted = activeAcceptanceMetrics(resumed, preparationDriver, driver, neverPublished,
                        servedCorrectionReceipts);
                System.out.println(activeAcceptanceTrace(resumed, preparationDriver, driver,
                        neverPublished, accepted, blockedRetryBodies, completePublicationBodies));
                assertThat(accepted.verifiedFixtureIds()).as("combined ACTIVE acceptance trace above")
                        .containsExactlyInAnyOrderElementsOf(neverPublished);
                assertThat(accepted.verifiedReceipts()).isEqualTo(10);
                assertThat(accepted.verifiedReceiptGenerations().keySet())
                        .containsExactlyInAnyOrderElementsOf(neverPublished);
                assertThat(accepted.verifiedReceiptGenerations().values()).hasSize(10).doesNotHaveDuplicates()
                        .allMatch(generation -> generation > 0);
                assertThat(accepted.firstNewClaim()).isNotNull().isAfterOrEqualTo(LEGACY_DAILY_DUE);
                assertThat(neverPublished).contains(accepted.firstNewFixture());
                assertThat(accepted.firstNewGeneration()).isPositive();
                assertThat(driver.firstPendingObservations.keySet())
                        .as("each target has a recorded coherent first-pending generation")
                        .containsExactlyInAnyOrderElementsOf(neverPublished);
                for (int fixtureId : neverPublished) {
                    FirstPendingObservation firstPending = driver.firstPendingObservations.get(fixtureId);
                    long receiptGeneration = accepted.verifiedReceiptGenerations().get(fixtureId);
                    assertThat(firstPending.generation())
                            .as("fixture %d publishes a newer-generation receipt than its first pending observation",
                                    fixtureId)
                            .isLessThan(receiptGeneration);
                    assertThat(firstPending.reads().startedAt())
                            .as("fixture %d first source observation respects the due-time floor", fixtureId)
                            .isAfterOrEqualTo(LEGACY_DAILY_DUE);
                }
                assertThat(accepted.firstNewAttempt()).isPositive();
                assertThat(accepted.firstNewWorker()).isNotBlank();
                assertThat(accepted.firstVerifiedAt()).isNotNull().isAfterOrEqualTo(accepted.firstNewClaim());
                assertThat(accepted.maxVirtualElapsed()).isLessThanOrEqualTo(ACTIVE_RECOVERY_VIRTUAL_LIMIT);
                assertThat(accepted.dispatches()).isLessThanOrEqualTo(ACTIVE_RECOVERY_DISPATCH_LIMIT);
                assertThat(accepted.claims()).isLessThanOrEqualTo(ACTIVE_RECOVERY_CLAIM_LIMIT);
                assertThat(accepted.missingHistoryEvidence()).isZero();
                assertThat(accepted.badFixtureManifests()).isZero();
                assertThat(accepted.badBatchPairs()).isZero();
                assertThat(accepted.failedRuns()).isZero();
                assertThat(accepted.servedCorrectionReceipts()).isEqualTo(40);
                assertThat(accepted.correctionReplayRuns()).isZero();
                assertThat(accepted.unservedChangedCorrections()).containsExactlyInAnyOrder(1, 2, 3, 4, 5);
                assertThat(accepted.sawReadyFixture()).isTrue();
                assertThat(accepted.sawBlockedFixture()).isTrue();
                assertThat(accepted.sawUntouchedFixture()).isTrue();
                assertThat(accepted.preferentialHistoryClaims()).isPositive();
                assertThat(accepted.fifoHistoryClaims()).isPositive();
                assertThat(completePublicationBodies.bodyFamily(PayloadReadProbe.BodyFamily.OWN_ANCHORS)
                        .bodyValues()).isPositive();
                assertThat(completePublicationBodies.bodyFamily(PayloadReadProbe.BodyFamily.CROSS_RUN_HISTORIES)
                        .bodyValues()).isPositive();
                assertWorkerCanonicalPairReads(completePublicationBodies);
                assertThat(completePublicationBodies.bodyFamily(PayloadReadProbe.BodyFamily.OWN_ANCHORS).rawBytes())
                        .isPositive();
                assertThat(completePublicationBodies.bodyFamily(PayloadReadProbe.BodyFamily.CROSS_RUN_HISTORIES)
                        .rawBytes()).isPositive();

                assertUnverifiedAfterScheduleCorrection(3, fixtureThreeBeforeScheduleCorrection,
                        fixtureOwnershipSnapshot(3));
                assertChangedCorrectionsRemainScheduled(resumed, Set.of(1, 2, 3, 4, 5),
                        servedCorrectionReceipts);
                assertPostRecoveryPublicationComponents(baselineComponents,
                        fixturePublicationComponents(java.util.stream.IntStream.rangeClosed(1, 40).boxed()
                                .collect(java.util.stream.Collectors.toUnmodifiableSet())), Set.of(1, 2, 3, 4, 5));
                assertThat(pfsRows("ACTIVE", 3)).isEmpty();
                assertThat(pfsRows("ACTIVE", 45)).isNotEmpty();
                assertOptionalComponentIsMasked(45, sourcePlayerForFixture(activeSource, 45));
                assertCanonicalPairProof(resumed, activeSource);
                assertThat(historyFingerprintChecksum(java.util.stream.IntStream.rangeClosed(1, 40).boxed()
                        .collect(java.util.stream.Collectors.toUnmodifiableSet())))
                        .as("history fingerprint identities remain stable through target recovery")
                        .isEqualTo(servedFingerprintChecksum);

                System.out.printf("OWNED_ACTIVE_RECOVERY firstNewFixture=%d firstNewGeneration=%d "
                                + "firstNewAttempt=%d firstNewWorker=%s firstNewClaim=%s firstVerifiedAt=%s "
                                + "finalFixtureVerifiedAt=%s elapsedVirtualSeconds=%.3f "
                                + "claims=%d dispatches=%d verified=%d receipts=%s ids=%s "
                                + "servedCorrections=%d correctionReplayRuns=%d "
                                + "unservedChangedCorrections=%s blockedBodyReads=%s/%s/%s completeBodyReads=%s/%s/%s "
                                + "ready=%s blocked=%s untouched=%s historyPreferenceClaims=%d historyFifoClaims=%d "
                                + "canonicalPreemptions=%d pendingUnknownGates=%d%n",
                        accepted.firstNewFixture(), accepted.firstNewGeneration(), accepted.firstNewAttempt(),
                        accepted.firstNewWorker(), accepted.firstNewClaim(), accepted.firstVerifiedAt(),
                        accepted.lastVerifiedAt(), accepted.maxVirtualElapsed().toNanos() / 1e9,
                        accepted.claims(), accepted.dispatches(), accepted.verifiedReceipts(),
                        accepted.verifiedReceiptGenerations(), accepted.verifiedFixtureIds(),
                        accepted.servedCorrectionReceipts(), accepted.correctionReplayRuns(),
                        accepted.unservedChangedCorrections(),
                        blockedRetryBodies.bodyFamily(PayloadReadProbe.BodyFamily.OWN_ANCHORS).counts(),
                        blockedRetryBodies.bodyFamily(PayloadReadProbe.BodyFamily.CROSS_RUN_HISTORIES).counts(),
                        blockedRetryBodies.bodyFamily(PayloadReadProbe.BodyFamily.CANONICAL_PAIRS).counts(),
                        completePublicationBodies.bodyFamily(PayloadReadProbe.BodyFamily.OWN_ANCHORS).counts(),
                        completePublicationBodies.bodyFamily(PayloadReadProbe.BodyFamily.CROSS_RUN_HISTORIES).counts(),
                        completePublicationBodies.bodyFamily(PayloadReadProbe.BodyFamily.CANONICAL_PAIRS).counts(),
                        accepted.sawReadyFixture(), accepted.sawBlockedFixture(), accepted.sawUntouchedFixture(),
                        accepted.preferentialHistoryClaims(), accepted.fifoHistoryClaims(),
                        accepted.canonicalPreemptions(), accepted.pendingUnknownGates());
            }
        } finally {
            cleanActiveAcceptanceSeason();
        }
    }

    private static void prepareActiveAcceptanceSeason() {
        createActiveCapturedTables();
        new DefensiveStatsSchema(jdbc).ensureColumns();
        cleanActiveAcceptanceSeason();
        SharedStatsSeed.ensureOwnedSyncCaptureSeason(jdbc);
        jdbc.update("UPDATE season SET is_current=false");
        jdbc.update("UPDATE season SET is_current=true WHERE id=?", SEASON.id());
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
        clearOwnedRows();
        for (String table : List.of(CAPTURED.fixture(), CAPTURED.gameweek(), CAPTURED.dailyPrice())) {
            jdbc.update("DELETE FROM " + table + " WHERE season=?", SEASON.code());
        }
        jdbc.update("DELETE FROM player_fixture_stats WHERE season_id=?", SEASON.id());
        jdbc.update("DELETE FROM fixture WHERE season_id=?", SEASON.id());
        jdbc.update("DELETE FROM gameweek WHERE season_id=?", SEASON.id());
        jdbc.update("DELETE FROM player WHERE season_id=?", SEASON.id());
        jdbc.update("DELETE FROM team WHERE season_id=?", SEASON.id());
        jdbc.update("DELETE FROM season WHERE id=?", SEASON.id());
        jdbc.update("UPDATE season SET is_current=(id=?)", SharedStatsSeed.CURRENT_SEASON_ID);
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

    private static void assertRetainedReceiptHistory(Map<Integer, Long> served) {
        assertThat(served).hasSize(40);
        for (int fixtureId = 1; fixtureId <= 40; fixtureId++) {
            long generation = served.get(fixtureId);
            assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + TABLES.receipt()
                    + " WHERE season=? AND publication_key=?", Long.class,
                    SEASON.code(), "fixture:" + fixtureId)).isEqualTo(generation);
            assertThat(jdbc.queryForObject("SELECT last_verified_generation FROM " + TABLES.fixtureSync()
                    + " WHERE season_id=? AND fixture_id=?", Long.class,
                    SEASON.id(), fixtureId)).isEqualTo(generation);
            assertThat(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + TABLES.run()
                    + " WHERE generation=? AND runtime_mode='ACTIVE' AND runtime_epoch=1"
                    + " AND state='COMPLETED')", Boolean.class, generation)).isTrue();
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
            List<Long> runGenerations = jdbc.queryForList("SELECT generation FROM " + TABLES.run()
                            + " WHERE season=? AND kind='FIXTURE_VERIFICATION' AND publication_key=? ORDER BY generation",
                    Long.class, SEASON.code(), "fixture:" + fixtureId);
            Map<String, Object> receipt = receiptRows.isEmpty() ? Map.of() : receiptRows.getFirst();
            result.put(fixtureId, new FixturePublicationComponents((String) receipt.get("receipt_identity"),
                    nullableLong(receipt.get("latest_generation")), Boolean.TRUE.equals(receipt.get("verification_valid")),
                    List.copyOf(pfsRows), List.copyOf(evidenceRows), sourceHeader, List.copyOf(runGenerations),
                    fixtureOwnershipSnapshot(fixtureId)));
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
                        .as(details + " corrected source-header fields").containsExactly("team_h_score");
                assertThat(old.sourceHeader().get("team_h_score")).isEqualTo(0);
                assertThat(updated.sourceHeader().get("team_h_score")).isEqualTo(1);
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
                assertThat(updated.sourceHeader()).as(details + " unchanged source header").isEqualTo(old.sourceHeader());
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

    private static void assertPostRecoveryPublicationComponents(
            Map<Integer, FixturePublicationComponents> expected,
            Map<Integer, FixturePublicationComponents> actual, Set<Integer> changed) {
        Map<Integer, String> diffs = fixturePublicationComponentDiffs(expected, actual);
        System.out.println("OWNED_TASK4_POST_RECOVERY_COMPONENT_DIFF " + diffs);
        assertThat(actual.keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());
        for (int fixtureId : expected.keySet()) {
            FixturePublicationComponents baseline = expected.get(fixtureId);
            FixturePublicationComponents recovered = actual.get(fixtureId);
            String details = "fixture " + fixtureId + " post-recovery component diff="
                    + fixturePublicationComponentDiffs(Map.of(fixtureId, baseline), Map.of(fixtureId, recovered));
            assertThat(recovered.receiptIdentity()).as(details + " retained publication identity")
                    .isEqualTo(baseline.receiptIdentity());
            assertThat(recovered.latestGeneration()).as(details + " retained receipt latest generation")
                    .isEqualTo(baseline.latestGeneration());
            assertThat(recovered.sourceHeader()).as(details + " source header after schedule correction")
                    .isEqualTo(baseline.sourceHeader());
            assertThat(recovered.pfsRows()).as(details + " immutable physical PFS content")
                    .isEqualTo(baseline.pfsRows());
            assertThat(recovered.evidenceRows()).as(details + " immutable per-player source evidence")
                    .isEqualTo(baseline.evidenceRows());
            if (changed.contains(fixtureId)) {
                assertThat(baseline.verificationValid()).as(details + " invalidated before recovery").isFalse();
                assertThat(recovered.verificationValid()).as(details + " remains actually invalid").isFalse();
                assertThat(recovered.ownership().state()).as(details).isNotEqualTo("VERIFIED");
                assertThat(recovered.ownership().currentVerifiedGeneration()).as(details).isNull();
                assertThat(recovered.ownership().lastVerifiedGeneration()).as(details)
                        .isEqualTo(baseline.latestGeneration());
                assertThat(recovered.ownership().verifiedHeader()).as(details + " retained verified header")
                        .isEqualTo(baseline.ownership().verifiedHeader());
                assertThat(recovered.ownership().verifiedStatsFingerprint())
                        .as(details + " retained verified statistics fingerprint")
                        .isEqualTo(baseline.ownership().verifiedStatsFingerprint());
                assertThat(recovered.verificationRunGenerations()).as(details + " no loss of run history")
                        .containsAll(baseline.verificationRunGenerations());
                if (java.util.Objects.equals(recovered.ownership().observationGeneration(),
                        baseline.ownership().observationGeneration())) {
                    assertInvalidatedOwnershipRemains(fixtureId, baseline.ownership(), recovered.ownership());
                } else {
                    assertThat(recovered.ownership().observationGeneration()).as(details)
                            .isGreaterThan(baseline.ownership().observationGeneration());
                    assertThat(recovered.ownership().state()).as(details + " normal pending observation")
                            .isEqualTo("VERIFYING");
                    assertThat(recovered.ownership().observationCoherent()).as(details + " fresh source observation")
                            .isEqualTo("true");
                    assertThat(recovered.ownership().validationFailures()).as(details)
                            .contains("NEEDS_SECOND_OBSERVATION");
                    assertThat(recovered.ownership().observationFingerprint()).as(details).isNotBlank();
                }
            } else {
                assertThat(recovered.verificationValid()).as(details + " unchanged verified receipt").isTrue();
                assertThat(recovered.ownership().state()).as(details).isEqualTo("VERIFIED");
                assertThat(recovered.ownership().currentVerifiedGeneration()).as(details)
                        .isEqualTo(baseline.latestGeneration());
                assertThat(recovered.ownership().lastVerifiedGeneration()).as(details)
                        .isEqualTo(baseline.latestGeneration());
                assertThat(recovered.verificationRunGenerations()).as(details + " no restart replay")
                        .isEqualTo(baseline.verificationRunGenerations());
            }
        }
    }

    private static void assertChangedCorrectionsRemainScheduled(Harness harness, Set<Integer> fixtureIds,
                                                                  Map<Integer, Long> actualVerifiedGenerations) {
        PlannerInputRepository inputs = new PlannerInputRepository(jdbc, harness.store, TABLES, CAPTURED,
                harness.clock);
        PlannerInputRepository.PlannerSnapshot snapshot = inputs.load(SEASON);
        List<AdaptiveMatchdayPlanner.Fixture> fixtures = snapshot.fixtures().stream()
                .filter(fixture -> fixtureIds.contains(fixture.id())).toList();
        List<AdaptiveMatchdayPlanner.FixtureProgress> progress = snapshot.progress().stream()
                .filter(row -> fixtureIds.contains(row.fixtureId())).toList();
        assertThat(progress).as("an invalidated receipt is not completion evidence for its old corrections")
                .allSatisfy(row -> assertThat(row.completedCorrections()).isEmpty());
        AdaptiveMatchdayPlanner.Plan fixturePlan = new AdaptiveMatchdayPlanner(harness.clock).plan(
                new AdaptiveMatchdayPlanner.Input(SEASON.code(), snapshot.playerIds().size(), fixtures,
                        progress, List.of()));
        OwnedMatchdayCoordinator.TickResult coordinatorTick = harness.coordinator.tick();
        PlannerRuntimeRepository runtime = new PlannerRuntimeRepository(jdbc, tx, harness.clock, TABLES,
                harness.fence);
        List<PlannerRuntimeRepository.DurableSlot> ledger = runtime.loadSlots(SEASON.code());
        for (int fixtureId : fixtureIds.stream().sorted().toList()) {
            Map<String, Object> row = jdbc.queryForMap("SELECT state,observation,observation_generation,"
                    + "current_verified_generation,last_verified_generation,next_due_at,validation_failures::text"
                    + " validation_failures FROM " + TABLES.fixtureSync()
                    + " WHERE season_id=? AND fixture_id=?", SEASON.id(), fixtureId);
            assertThat(row.get("current_verified_generation")).as("fixture %d remains unverified", fixtureId).isNull();
            assertThat(nullableLong(row.get("last_verified_generation")))
                    .as("fixture %d retains its actual last publication", fixtureId)
                    .isEqualTo(actualVerifiedGenerations.get(fixtureId));
            boolean queued = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + TABLES.run()
                    + " WHERE season=? AND coalescing_key=? AND publication_key=?"
                    + " AND kind='FIXTURE_VERIFICATION' AND runtime_mode='ACTIVE' AND runtime_epoch=?"
                    + " AND state IN ('PENDING','LEASED') AND generation>?)", Boolean.class, SEASON.code(),
                    "fixture:" + fixtureId, "fixture:" + fixtureId, harness.fence.epoch(),
                    actualVerifiedGenerations.get(fixtureId));
            boolean pendingObservation = "VERIFYING".equals(row.get("state"))
                    && "true".equals(jdbc.queryForObject("SELECT observation->>'coherent' FROM "
                            + TABLES.fixtureSync() + " WHERE season_id=? AND fixture_id=?",
                            String.class, SEASON.id(), fixtureId))
                    && row.get("observation_generation") != null
                    && String.valueOf(row.get("validation_failures")).contains("NEEDS_SECOND_OBSERVATION")
                    && jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + TABLES.run()
                            + " WHERE generation=? AND season=? AND coalescing_key=? AND publication_key=?"
                            + " AND kind='FIXTURE_VERIFICATION' AND runtime_mode='ACTIVE'"
                            + " AND runtime_epoch=? AND state='COMPLETED')", Boolean.class,
                            nullableLong(row.get("observation_generation")), SEASON.code(),
                            "fixture:" + fixtureId, "fixture:" + fixtureId, harness.fence.epoch());
            AdaptiveMatchdayPlanner.Fixture fixture = fixtures.stream()
                    .filter(value -> value.id() == fixtureId).findFirst().orElseThrow();
            AdaptiveMatchdayPlanner.FixtureProgress fixtureProgress = progress.stream()
                    .filter(value -> value.fixtureId() == fixtureId).findFirst().orElseThrow();
            SharedFixtureRecoveryPolicy.Decision fixtureRecovery = fixtureOnlyRecoveryDecision(
                    fixture, fixtureProgress, harness.clock.instant());
            Instant fixtureOnlyDue = fixtureOnlyFutureDue(SEASON.code(), snapshot.playerIds().size(),
                    harness.clock, fixture, fixtureProgress, fixtureRecovery);
            boolean fixtureWork = hasFixtureSpecificFutureWork(fixtureId, queued, ledger, fixtureOnlyDue,
                    coordinatorTick.nextDueAt(), harness.clock.instant());
            System.out.printf("OWNED_TASK4_CORRECTION_WORK fixture=%d completedCorrections=%s queued=%s "
                            + "pendingObservation=%s fixtureRecovery=%s fixtureOnlyDue=%s coordinatorNext=%s globalPlanNext=%s "
                            + "durableFixtureSlot=%s row=%s%n",
                    fixtureId, fixtureProgress.completedCorrections(), queued, pendingObservation, fixtureRecovery,
                    fixtureOnlyDue, coordinatorTick.nextDueAt(), fixturePlan.nextDueAt().orElse(null),
                    hasFutureFixtureSlot(fixtureId, ledger, harness.clock.instant()), row);
            assertThat(fixtureWork)
                    .as("fixture %d remains unverified with exact queued/durable work or a fixture-only future "
                                    + "recovery decision and coordinator wake; queued=%s pendingObservation=%s "
                                    + "fixtureRecovery=%s fixtureOnlyDue=%s coordinatorNext=%s globalPlanNext=%s row=%s",
                            fixtureId, queued, pendingObservation, fixtureRecovery, fixtureOnlyDue,
                            coordinatorTick.nextDueAt(),
                            fixturePlan.nextDueAt().orElse(null), row)
                    .isTrue();
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

    private static void assertWorkerCanonicalPairReads(PayloadReadProbe.Snapshot workerReads) {
        PayloadReadProbe.FamilyMetrics pairReads = workerReads.bodyFamily(
                PayloadReadProbe.BodyFamily.CANONICAL_PAIRS);
        assertThat(pairReads.bodyValues()).isIn(0L, 2L);
        if (pairReads.bodyValues() == 0) {
            assertThat(pairReads.attempts()).isZero();
            assertThat(pairReads.resultRows()).isZero();
            assertThat(pairReads.rawBytes()).isZero();
        } else {
            assertThat(pairReads.attempts()).isEqualTo(1);
            assertThat(pairReads.resultRows()).isEqualTo(1);
            assertThat(pairReads.rawBytes()).isPositive();
        }
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

    private static void assertScheduleCorrectionsArePending(Map<Integer, Long> served, Set<Integer> changed) {
        for (int fixtureId = 1; fixtureId <= 40; fixtureId++) {
            Long current = jdbc.queryForObject("SELECT current_verified_generation FROM " + TABLES.fixtureSync()
                    + " WHERE season_id=? AND fixture_id=?", Long.class, SEASON.id(), fixtureId);
            if (changed.contains(fixtureId)) {
                assertThat(current).as("changed fixture %d loses current verification", fixtureId).isNull();
                assertThat(jdbc.queryForObject("SELECT last_verified_generation FROM " + TABLES.fixtureSync()
                        + " WHERE season_id=? AND fixture_id=?", Long.class, SEASON.id(), fixtureId))
                        .isEqualTo(served.get(fixtureId));
            } else {
                assertThat(current).as("unmodified fixture %d retains its receipt", fixtureId)
                        .isEqualTo(served.get(fixtureId));
            }
        }
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

    private static void assertLegacyDailyOnlyGates(Set<Integer> fixtureIds) {
        for (int fixtureId : fixtureIds) {
            Map<String, Object> gate = jdbc.queryForMap("SELECT alert_state,due_reason,next_due_at,attempt_count"
                    + " FROM " + TABLES.fixtureSync() + " WHERE season_id=? AND fixture_id=?",
                    SEASON.id(), fixtureId);
            assertThat(gate.get("alert_state")).isEqualTo("DAILY_ONLY");
            assertThat(gate.get("due_reason")).isNull();
            assertThat(((Timestamp) gate.get("next_due_at")).toInstant()).isEqualTo(LEGACY_DAILY_DUE);
            assertThat(gate.get("attempt_count")).isEqualTo(0);
            Instant firstFinished = jdbc.queryForObject("SELECT first_finished_observed_at FROM "
                    + TABLES.fixtureSync() + " WHERE season_id=? AND fixture_id=?",
                    Timestamp.class, SEASON.id(), fixtureId).toInstant();
            assertThat(firstFinished.plus(Duration.ofHours(12)))
                    .as("the planner's real first-finished observation is at least 12h old at the preserved due")
                    .isBeforeOrEqualTo(LEGACY_DAILY_DUE);
            Map<String, Object> checked = jdbc.queryForMap("SELECT data_checked_transition_at,last_data_checked"
                    + " FROM " + TABLES.fixtureSync() + " WHERE season_id=? AND fixture_id=?",
                    SEASON.id(), fixtureId);
            assertThat(checked.get("last_data_checked")).isEqualTo(true);
            assertThat(((Timestamp) checked.get("data_checked_transition_at")).toInstant())
                    .as("the first real checked-source transition predates the 48h baseline publication")
                    .isBeforeOrEqualTo(SAME_FINGERPRINT_CORRECTION);
        }
    }

    private static List<Map<String, Object>> fixtureGateTrace(Set<Integer> fixtureIds) {
        String ids = fixtureIds.stream().sorted().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        return jdbc.queryForList("SELECT fixture_id,state,first_finished_observed_at,next_due_at,due_reason,"
                + "alert_state,attempt_count,recovery_day,recovery_started_at,recovery_until,"
                + "current_verified_generation,last_verified_generation FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id IN (" + ids + ") ORDER BY fixture_id", SEASON.id());
    }

    private static String runtimeMode() {
        return jdbc.queryForObject("SELECT runtime_mode FROM " + TABLES.plannerRuntime()
                + " WHERE instance_id=?", String.class, "mode:" + SEASON.code());
    }

    private static List<Map<String, Object>> pfsRows(String mode, int fixtureId) {
        PfsReadSource readSource = new PfsReadSource(jdbc, mode, SEASON.code(),
                TABLES.fixtureSync(), TABLES.playerFixtureEvidence());
        return jdbc.queryForList("WITH " + readSource.cte()
                + " SELECT * FROM pfs_read WHERE season_id=? AND fixture_id=? ORDER BY player_id",
                SEASON.id(), fixtureId);
    }

    private static void assertOptionalComponentIsMasked(int fixtureId, int playerId) {
        Object physical = jdbc.queryForObject("SELECT defensive_contribution FROM player_fixture_stats"
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", Object.class,
                SEASON.id(), fixtureId, playerId);
        Object storedExpectedGoals = jdbc.queryForObject("SELECT expected_goals FROM player_fixture_stats"
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", Object.class,
                SEASON.id(), fixtureId, playerId);
        Map<String, Object> visible = pfsRows("ACTIVE", fixtureId).stream()
                .filter(row -> ((Number) row.get("player_id")).intValue() == playerId)
                .findFirst().orElseThrow();
        assertThat(physical).isNull();
        assertThat(visible.get("defensive_contribution")).isNull();
        assertThat(storedExpectedGoals).isNotNull();
        assertThat(visible.get("expected_goals")).isNotNull();
        Map<String, Object> knownZero = pfsRows("ACTIVE", fixtureId).stream()
                .filter(row -> row.get("defensive_contribution") instanceof Number value
                        && value.intValue() == 0)
                .findFirst().orElseThrow();
        assertThat(jdbc.queryForObject("SELECT defensive_contribution FROM player_fixture_stats"
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", Object.class,
                SEASON.id(), fixtureId, knownZero.get("player_id"))).isEqualTo(0);
    }

    private static int sourcePlayerForFixture(SourceData source, int fixtureId) {
        return source.optionalOmittedPlayerId();
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

    private static void assertNeverPublished(Set<Integer> fixtureIds) {
        String keys = fixtureIds.stream().sorted().map(id -> "'fixture:" + id + "'")
                .collect(java.util.stream.Collectors.joining(","));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key IN (" + keys + ") AND latest_generation IS NOT NULL",
                Integer.class, SEASON.code())).isZero();
    }

    private static int activeClaimAttempts(long epoch) {
        return jdbc.queryForObject("SELECT COALESCE(sum(attempt_count),0) FROM " + TABLES.run()
                + " WHERE season=? AND runtime_mode='ACTIVE' AND runtime_epoch=?",
                Integer.class, SEASON.code(), epoch);
    }

    private static int nonCanonicalAttempts(long epoch) {
        return jdbc.queryForObject("SELECT COALESCE(sum(attempt_count),0) FROM " + TABLES.run()
                + " WHERE season=? AND runtime_mode='ACTIVE' AND runtime_epoch=?"
                + " AND kind IN ('PLAYER_HISTORY','FIXTURE_VERIFICATION')", Integer.class, SEASON.code(), epoch);
    }

    private static int pendingFixtureRuns(long epoch, Set<Integer> fixtureIds) {
        String keys = fixtureIds.stream().sorted().map(id -> "'fixture:" + id + "'")
                .collect(java.util.stream.Collectors.joining(","));
        return jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE season=? AND kind='FIXTURE_VERIFICATION' AND runtime_mode='ACTIVE'"
                + " AND runtime_epoch=? AND state IN ('PENDING','LEASED')"
                + " AND publication_key IN (" + keys + ")", Integer.class, SEASON.code(), epoch);
    }

    private static Map<Long, Instant> deferPendingHistoriesUntil(Instant dueAt) {
        Map<Long, Instant> originals = new TreeMap<>();
        jdbc.query("SELECT generation,due_at FROM " + TABLES.run()
                        + " WHERE season=? AND kind='PLAYER_HISTORY' AND runtime_mode='ACTIVE'"
                        + " AND state='PENDING' AND due_at<? ORDER BY generation",
                (rs, row) -> originals.put(rs.getLong("generation"), rs.getTimestamp("due_at").toInstant()),
                SEASON.code(), Timestamp.from(dueAt));
        originals.forEach((generation, originalDue) -> jdbc.update("UPDATE " + TABLES.run()
                + " SET due_at=? WHERE generation=? AND state='PENDING'", Timestamp.from(dueAt), generation));
        return Map.copyOf(originals);
    }

    private static void restoreHistoryDueTimes(Map<Long, Instant> originals) {
        originals.forEach((generation, dueAt) -> jdbc.update("UPDATE " + TABLES.run()
                + " SET due_at=? WHERE generation=? AND state='PENDING'", Timestamp.from(dueAt), generation));
    }

    private static int pendingManifestCount(long epoch) {
        return jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run() + " r JOIN "
                + TABLES.fixtureDependencyManifest() + " m ON m.run_generation=r.generation"
                + " WHERE r.season=? AND r.kind='FIXTURE_VERIFICATION' AND r.runtime_mode='ACTIVE'"
                + " AND r.runtime_epoch=? AND r.state='PENDING'", Integer.class, SEASON.code(), epoch);
    }

    private static void mergeHistoryDueTimes(Map<Long, Instant> retained, Map<Long, Instant> fresh) {
        fresh.forEach(retained::putIfAbsent);
    }

    private static long newestPendingManifestGeneration(long epoch) {
        return jdbc.queryForObject("SELECT r.generation FROM " + TABLES.run() + " r JOIN "
                + TABLES.fixtureDependencyManifest() + " m ON m.run_generation=r.generation"
                + " WHERE r.season=? AND r.kind='FIXTURE_VERIFICATION' AND r.runtime_mode='ACTIVE'"
                + " AND r.runtime_epoch=? AND r.state='PENDING'"
                + " ORDER BY r.last_attempt_at DESC,r.generation DESC LIMIT 1", Long.class, SEASON.code(), epoch);
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

    private static ActiveAcceptanceMetrics activeAcceptanceMetrics(Harness harness,
                                                                  ManualDriver preparationDriver,
                                                                  ManualDriver driver,
                                                                  Set<Integer> targetFixtures,
                                                                  Map<Integer, Long> expectedCorrectionReceipts) throws IOException {
        Set<Integer> verified = new java.util.TreeSet<>();
        Map<Integer, Long> verifiedReceiptGenerations = new TreeMap<>();
        List<Map<String, Object>> fixtureProofs = new ArrayList<>();
        Instant firstVerified = null;
        Instant lastVerified = null;
        int missingReceipts = 0;
        int badManifests = 0;
        int badBatchPairs = 0;
        Map<Integer, Set<Integer>> requiredByFixture = harness.source.requiredHistoryPlayersByFixture(FIXTURE_RUNS);
        Map<Integer, FixtureOracle> sourceOracles = sourceFixtureOracles(harness.source, targetFixtures);
        CanonicalParentProof canonicalParent = currentCanonicalParentProof(harness);
        for (int fixtureId : targetFixtures.stream().sorted().toList()) {
            FixtureOracle oracle = sourceOracles.get(fixtureId);
            Map<String, Object> proof = fixtureManifestPredicateTrace(harness, fixtureId, oracle, canonicalParent);
            fixtureProofs.add(proof);
            List<Map<String, Object>> receiptRows = jdbc.queryForList("SELECT fs.current_verified_generation,"
                    + "fs.first_verified_at,fs.last_verified_at,p.latest_generation,p.published_at,r.state,"
                    + "r.runtime_mode,r.runtime_epoch FROM " + TABLES.fixtureSync() + " fs"
                    + " JOIN " + TABLES.receipt() + " p ON p.season=? AND p.publication_key='fixture:' || fs.fixture_id::text"
                    + " AND p.latest_generation=fs.current_verified_generation"
                    + " JOIN " + TABLES.run() + " r ON r.generation=p.latest_generation"
                    + " WHERE fs.season_id=? AND fs.fixture_id=? AND fs.state='VERIFIED'",
                    SEASON.code(), SEASON.id(), fixtureId);
            if (receiptRows.size() != 1) {
                missingReceipts++;
                proof.put("status", "missing_receipt");
                continue;
            }
            Map<String, Object> receipt = receiptRows.getFirst();
            long generation = ((Number) receipt.get("current_verified_generation")).longValue();
            if (generation != ((Number) receipt.get("latest_generation")).longValue()
                    || !"COMPLETED".equals(receipt.get("state"))
                    || !"ACTIVE".equals(receipt.get("runtime_mode"))
                    || ((Number) receipt.get("runtime_epoch")).longValue() != harness.fence.epoch()) {
                badManifests++;
                proof.put("status", "bad_published_manifest");
                proof.put("receiptGeneration", generation);
                continue;
            }
            Integer[] rawIds = jdbc.queryForObject("SELECT required_player_ids FROM "
                            + TABLES.fixtureDependencyManifest() + " WHERE run_generation=?",
                    (rs, row) -> (Integer[]) rs.getArray(1).getArray(), generation);
            Set<Integer> requiredIds = Set.of(rawIds);
            boolean requiredIdsMatch = requiredIds.equals(requiredByFixture.get(fixtureId))
                    && requiredIds.equals(oracle.requiredPlayers());
            boolean fixtureEvidence = fixtureReadEvidenceProof(harness, fixtureId, generation, requiredIds);
            boolean stagedSources = Boolean.TRUE.equals(proof.get("publishedManifestProof"));
            boolean participantCounts = Boolean.TRUE.equals(proof.get("participantCounts"));
            boolean sourceFloor = Boolean.TRUE.equals(proof.get("sourceFloorWanted"));

            List<SourceRead> batches = jdbc.query("SELECT source_read_started_at,source_read_finished_at FROM "
                            + TABLES.run() + " WHERE season=? AND runtime_mode='ACTIVE' AND runtime_epoch=?"
                            + " AND kind='FIXTURE_VERIFICATION' AND publication_key=? AND state='COMPLETED'"
                            + " AND source_read_started_at IS NOT NULL AND source_read_finished_at IS NOT NULL"
                            + " ORDER BY completed_at DESC,generation DESC LIMIT 2",
                    (rs, row) -> new SourceRead(rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()),
                    SEASON.code(), harness.fence.epoch(), "fixture:" + fixtureId);
            boolean batchValid = batches.size() >= 2 && !batches.getFirst().startedAt()
                    .isBefore(batches.getLast().finishedAt().plus(Duration.ofMinutes(10)))
                    && batches.stream().noneMatch(batch -> batch.startedAt().getNano() % 1_000 != 0
                            || batch.finishedAt().getNano() % 1_000 != 0);
            if (!batchValid) badBatchPairs++;
            boolean latestGeneration = numberLong(proof.get("latestTargetGeneration")) == generation;
            boolean validManifest = requiredIdsMatch && fixtureEvidence && stagedSources
                    && participantCounts && sourceFloor && latestGeneration;
            if (!validManifest) badManifests++;
            boolean validProof = validManifest && batchValid;
            proof.put("status", validProof ? "verified"
                    : validManifest ? "bad_batch_pair" : "bad_published_manifest");
            proof.put("batchProof", batchValid);
            proof.put("latestTwoBatchIntervals", jdbc.queryForList("SELECT generation,source_read_started_at,"
                    + "source_read_finished_at,completed_at FROM " + TABLES.run()
                    + " WHERE season=? AND runtime_mode='ACTIVE' AND runtime_epoch=?"
                    + " AND kind='FIXTURE_VERIFICATION' AND publication_key=? AND state='COMPLETED'"
                    + " ORDER BY completed_at DESC,generation DESC LIMIT 2",
                    SEASON.code(), harness.fence.epoch(), "fixture:" + fixtureId));
            proof.put("requiredIdsMatch", requiredIdsMatch);
            proof.put("fixtureReadEvidenceProof", fixtureEvidence);
            proof.put("receiptGeneration", generation);
            proof.put("latestGenerationGate", latestGeneration);
            if (validProof) {
                verified.add(fixtureId);
                verifiedReceiptGenerations.put(fixtureId, generation);
                Instant fixtureLast = ((Timestamp) receipt.get("last_verified_at")).toInstant();
                if (firstVerified == null || fixtureLast.isBefore(firstVerified)) firstVerified = fixtureLast;
                if (lastVerified == null || fixtureLast.isAfter(lastVerified)) lastVerified = fixtureLast;
            }
        }
        String targetKeys = targetFixtures.stream().sorted().map(id -> "'fixture:" + id + "'")
                .collect(java.util.stream.Collectors.joining(","));
        int missingHistoryEvidence = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " r JOIN " + TABLES.fixtureDependencyManifest() + " m ON m.run_generation=r.generation"
                + " JOIN " + TABLES.payload() + " f ON f.id=m.fixtures_payload_id"
                + " CROSS JOIN LATERAL unnest(m.required_player_ids) AS required(player_id)"
                + " WHERE r.season=? AND r.runtime_mode='ACTIVE' AND r.runtime_epoch=?"
                + " AND r.kind='FIXTURE_VERIFICATION' AND r.state='COMPLETED'"
                + " AND r.publication_key IN (" + targetKeys + ") AND r.generation IN (SELECT fs.current_verified_generation"
                + " FROM " + TABLES.fixtureSync() + " fs WHERE fs.season_id=? AND fs.fixture_id BETWEEN 41 AND 50)"
                + " AND NOT EXISTS (SELECT 1 FROM " + TABLES.payload() + " hp JOIN " + TABLES.run()
                + " hr ON hr.generation=hp.run_generation WHERE hp.endpoint_kind='ELEMENT_SUMMARY'"
                + " AND hp.event_id IS NULL AND hp.player_id=required.player_id AND hp.validation_state='VALID'"
                + " AND hp.read_started_at>=GREATEST(r.requested_source_after,m.source_floor,"
                + " f.read_finished_at + INTERVAL '1 millisecond') AND hp.read_finished_at<=r.completed_at"
                + " AND hp.staged_at<=r.completed_at AND hr.kind='PLAYER_HISTORY' AND hr.state='COMPLETED'"
                + " AND hr.completed_at<=r.completed_at AND hr.coalescing_key='player-history:' || required.player_id::text"
                + " AND hr.publication_key=hr.coalescing_key AND hr.season=r.season"
                + " AND hr.runtime_mode=r.runtime_mode AND hr.runtime_epoch=r.runtime_epoch)", Integer.class,
                SEASON.code(), harness.fence.epoch(), SEASON.id());
        int servedCorrectionReceipts = 0;
        for (Map.Entry<Integer, Long> entry : expectedCorrectionReceipts.entrySet()) {
            Long current = jdbc.queryForObject("SELECT latest_generation FROM " + TABLES.receipt()
                    + " WHERE season=? AND publication_key=?", Long.class,
                    SEASON.code(), "fixture:" + entry.getKey());
            if (entry.getValue().equals(current)) servedCorrectionReceipts++;
        }
        int correctionReplayRuns = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE season=? AND runtime_mode='ACTIVE' AND runtime_epoch=?"
                + " AND kind='FIXTURE_VERIFICATION' AND publication_key IN ("
                + java.util.stream.IntStream.rangeClosed(6, 40).mapToObj(id -> "'fixture:" + id + "'")
                .collect(java.util.stream.Collectors.joining(",")) + ")",
                Integer.class, SEASON.code(), harness.fence.epoch());
        List<AdmissionTrace> traces = new ArrayList<>(harness.singleDispatchAdmissions);
        traces.addAll(preparationDriver.admissions);
        traces.addAll(driver.admissions);
        int firstNewFixture = -1;
        long firstNewGeneration = 0;
        int firstNewAttempt = 0;
        String firstNewWorker = null;
        Instant firstNewClaim = null;
        for (AdmissionTrace trace : traces) {
            if (trace.generation() <= 0 || !"FIXTURE_VERIFICATION".equals(trace.kind())
                    || trace.claimedAt() == null || trace.publicationKey() == null) continue;
            String key = trace.publicationKey();
            if (!key.startsWith("fixture:")) continue;
            int fixtureId = Integer.parseInt(key.substring("fixture:".length()));
            if (!targetFixtures.contains(fixtureId)) continue;
            if (firstNewClaim == null || trace.claimedAt().isBefore(firstNewClaim)) {
                firstNewClaim = trace.claimedAt();
                firstNewFixture = fixtureId;
                firstNewGeneration = trace.generation();
                firstNewAttempt = trace.attemptCount();
                firstNewWorker = trace.worker();
            }
        }
        int claims = jdbc.queryForObject("SELECT COALESCE(sum(attempt_count),0) FROM " + TABLES.run()
                + " WHERE season=? AND runtime_mode='ACTIVE' AND runtime_epoch=?",
                Integer.class, SEASON.code(), harness.fence.epoch());
        int failedRuns = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE season=? AND runtime_mode='ACTIVE' AND runtime_epoch=? AND state='FAILED'",
                Integer.class, SEASON.code(), harness.fence.epoch());
        int canonicalPreemptions = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE season=? AND runtime_mode='ACTIVE' AND runtime_epoch=?"
                + " AND kind='CATALOGUE_SCHEDULE' AND state='COMPLETED' AND attempt_count>0",
                Integer.class, SEASON.code(), harness.fence.epoch());
        int pendingUnknownGates = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id BETWEEN 41 AND 50 AND state<>'VERIFIED'",
                Integer.class, SEASON.id());
        Set<Integer> unservedChangedCorrections = new java.util.TreeSet<>();
        for (int fixtureId : List.of(1, 2, 3, 4, 5)) {
            boolean unverified = jdbc.queryForObject("SELECT current_verified_generation IS NULL"
                    + " AND last_verified_generation=? AND state<>'VERIFIED' FROM " + TABLES.fixtureSync()
                    + " WHERE season_id=? AND fixture_id=?", Boolean.class,
                    expectedCorrectionReceipts.get(fixtureId), SEASON.id(), fixtureId);
            // A completed first observation is not a receipt-backed correction publication.
            if (unverified) unservedChangedCorrections.add(fixtureId);
        }
        return new ActiveAcceptanceMetrics(
                Collections.unmodifiableSet(new java.util.TreeSet<>(verified)),
                Collections.unmodifiableMap(new TreeMap<>(verifiedReceiptGenerations)),
                verified.size(), missingReceipts, targetFixtures.size() - fixtureProofs.size(),
                List.copyOf(fixtureProofs), canonicalParent.trace(), servedCorrectionReceipts, correctionReplayRuns,
                Collections.unmodifiableSet(new java.util.TreeSet<>(unservedChangedCorrections)),
                firstVerified, lastVerified, firstNewFixture, firstNewGeneration, firstNewAttempt,
                firstNewWorker, firstNewClaim,
                Duration.between(LEGACY_DAILY_DUE, harness.clock.instant()), claims,
                preparationDriver.dispatches + driver.dispatches + harness.controlledDispatchCalls,
                missingHistoryEvidence, badManifests,
                badBatchPairs, failedRuns, driver.sawReadyFixture, driver.sawBlockedFixture,
                driver.sawUntouchedFixture, driver.preferentialHistoryClaims, driver.fifoHistoryClaims,
                canonicalPreemptions, pendingUnknownGates);
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
                + " LEFT JOIN " + TABLES.payload() + " b ON b.id=p.source_bootstrap_payload_id"
                + " AND b.endpoint_kind='BOOTSTRAP_STATIC' AND b.event_id IS NULL AND b.player_id IS NULL"
                + " AND b.validation_state='VALID' AND b.source_published IS TRUE"
                + " AND b.read_started_at=p.source_bootstrap_read_started_at"
                + " AND b.read_finished_at=p.source_bootstrap_read_finished_at"
                + " LEFT JOIN " + TABLES.payload() + " f ON f.id=p.source_fixtures_payload_id"
                + " AND f.endpoint_kind='FIXTURES' AND f.event_id IS NULL AND f.player_id IS NULL"
                + " AND f.validation_state='VALID' AND f.source_published IS TRUE"
                + " AND f.read_started_at=p.source_fixtures_read_started_at"
                + " AND f.read_finished_at=p.source_fixtures_read_finished_at"
                + " LEFT JOIN " + TABLES.run() + " sr ON sr.generation=b.run_generation"
                + " AND sr.season=p.season AND sr.kind='CATALOGUE_SCHEDULE'"
                + " AND sr.runtime_mode='ACTIVE' AND sr.runtime_epoch=r.runtime_epoch"
                + " AND ((sr.coalescing_key=? AND sr.publication_key=?)"
                + " OR (sr.coalescing_key=? AND sr.publication_key=?))"
                + " WHERE p.season=? AND p.publication_key IN (?,?)"
                + " AND r.kind='CATALOGUE_SCHEDULE' AND r.runtime_mode='ACTIVE' AND r.runtime_epoch=?"
                + " AND ((p.publication_key=? AND r.coalescing_key=? AND r.publication_key=?)"
                + " OR (p.publication_key=? AND r.coalescing_key=? AND r.publication_key=?))"
                + " ORDER BY p.published_at DESC,r.generation DESC LIMIT 1";
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
            Timestamp combinedStarted = earlier(bStartedValue, fStartedValue);
            Timestamp combinedFinished = later(bFinishedValue, fFinishedValue);
            boolean sourceRolesOrdered = bStartedValue != null && bFinishedValue != null
                    && fStartedValue != null && fFinishedValue != null
                    && !bStartedValue.after(bFinishedValue) && !fStartedValue.after(fFinishedValue);
            boolean sourceInterval = sourceRolesOrdered && runStartedValue != null && runFinishedValue != null
                    && runStartedValue.equals(combinedStarted) && runFinishedValue.equals(combinedFinished)
                    && receiptIntervalProof(runStartedValue, runFinishedValue, receiptStartedValue,
                            receiptFinishedValue, completedAtValue, publishedAtValue, harness.clock.instant())
                    && databaseMicroseconds(bStartedValue, bFinishedValue, fStartedValue, fFinishedValue);
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
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY,
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY,
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY);
        return rows.isEmpty() ? CanonicalParentProof.missing() : rows.getFirst();
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

    private static Map<String, Object> fixtureManifestPredicateTrace(Harness harness, int fixtureId,
                                                                       FixtureOracle oracle,
                                                                       CanonicalParentProof parent) {
        Map<String, Object> row = jdbc.queryForMap("SELECT fs.fixture_id,fs.state fixture_state,"
                + "fs.season_id,fs.season_code,fs.observation_generation,fs.current_verified_generation,"
                + "fs.last_verified_generation,"
                + "fs.observation->>'coherent' coherent,fs.observation->>'fingerprint' observation_fp,"
                + "fs.verified_header,fs.verified_stats_fingerprint,fs.validation_failures::text validation_failures,"
                + "fs.finished_parent_generation,"
                + "fs.finished_parent_publication_generation,fs.finished_parent_bootstrap_payload_id,"
                + "fs.finished_parent_fixtures_payload_id,fs.finished_parent_source_fingerprint,"
                + "fs.finished_parent_fixture_fingerprint,fs.finished_parent_finished,"
                + "fs.recovery_day,fs.recovery_started_at,fs.recovery_until,"
                + "fs.source_read_started_at fixture_started,fs.source_read_finished_at fixture_finished,"
                + "fs.expected_participants,fs.received_participants,cardinality(fs.missing_player_ids) missing_count,"
                + "fx.gameweek fixture_event,latest.generation latest_target_generation,"
                + "r.generation run_generation,r.kind,r.season,r.coalescing_key,r.publication_key,"
                + "r.runtime_mode,r.runtime_epoch,r.state run_state,r.requested_source_after,r.completed_at,"
                + "r.last_error run_error,r.source_read_started_at run_started,r.source_read_finished_at run_finished,"
                + "m.run_generation manifest_generation,m.event_id,m.source_floor,"
                + "cardinality(m.required_player_ids) manifest_player_count,"
                + "fpl_sync_required_ids_valid(m.required_player_ids) membership_known,"
                + "b.id b_id,b.run_generation b_generation,b.endpoint_kind b_role,b.event_id b_event,"
                + "b.player_id b_player,b.validation_state b_validation,b.body IS NOT NULL b_body,"
                + "b.read_started_at b_started,b.read_finished_at b_finished,"
                + "f.id f_id,f.run_generation f_generation,f.endpoint_kind f_role,f.event_id f_event,"
                + "f.player_id f_player,f.validation_state f_validation,f.body IS NOT NULL f_body,"
                + "f.read_started_at f_started,f.read_finished_at f_finished,"
                + "l.id l_id,l.run_generation l_generation,l.endpoint_kind l_role,l.event_id l_event,"
                + "l.player_id l_player,l.validation_state l_validation,l.body IS NOT NULL l_body,"
                + "l.read_started_at l_started,l.read_finished_at l_finished,"
                + "p.latest_generation receipt_latest,p.source_read_started_at receipt_started,"
                + "p.source_read_finished_at receipt_finished,p.published_at receipt_published,"
                + "(SELECT min(e.source_read_started_at) FROM " + TABLES.playerFixtureEvidence()
                + " e WHERE e.season_id=fs.season_id AND e.fixture_id=fs.fixture_id"
                + " AND e.generation=r.generation) evidence_started,"
                + "(SELECT max(e.source_read_finished_at) FROM " + TABLES.playerFixtureEvidence()
                + " e WHERE e.season_id=fs.season_id AND e.fixture_id=fs.fixture_id"
                + " AND e.generation=r.generation) evidence_finished,"
                + "GREATEST(r.requested_source_after,m.source_floor,"
                + "f.read_finished_at + INTERVAL '1 millisecond') effective_floor "
                + "FROM " + TABLES.fixtureSync() + " fs JOIN fixture fx"
                + " ON fx.season_id=fs.season_id AND fx.id=fs.fixture_id"
                + " LEFT JOIN LATERAL (SELECT generation FROM " + TABLES.run()
                + " WHERE season=? AND runtime_mode='ACTIVE' AND runtime_epoch=?"
                + " AND kind='FIXTURE_VERIFICATION' AND coalescing_key=? AND publication_key=?"
                + " ORDER BY generation DESC LIMIT 1) latest ON TRUE"
                + " LEFT JOIN " + TABLES.run() + " r ON r.generation=COALESCE(fs.current_verified_generation,"
                + "latest.generation) LEFT JOIN " + TABLES.fixtureDependencyManifest()
                + " m ON m.run_generation=r.generation LEFT JOIN " + TABLES.payload()
                + " b ON b.id=m.bootstrap_payload_id AND b.run_generation=r.generation LEFT JOIN "
                + TABLES.payload() + " f ON f.id=m.fixtures_payload_id AND f.run_generation=r.generation"
                + " LEFT JOIN " + TABLES.payload() + " l ON l.id=m.live_payload_id AND l.run_generation=r.generation"
                + " LEFT JOIN " + TABLES.receipt()
                + " p ON p.season=? AND p.publication_key='fixture:' || fs.fixture_id::text"
                + " WHERE fs.season_id=? AND fs.fixture_id=?",
                SEASON.code(), harness.fence.epoch(), "fixture:" + fixtureId, "fixture:" + fixtureId,
                SEASON.code(), SEASON.id(), fixtureId);
        var actualParent = harness.store.currentCanonicalParentEvidence(SEASON.code(), fixtureId,
                harness.clock.instant());
        long generation = numberLong(row.get("run_generation"));
        long currentGeneration = numberLong(row.get("current_verified_generation"));
        long latestGeneration = numberLong(row.get("latest_target_generation"));
        long manifestGeneration = numberLong(row.get("manifest_generation"));
        long receiptLatest = numberLong(row.get("receipt_latest"));
        Timestamp wanted = (Timestamp) row.get("requested_source_after");
        Timestamp floor = (Timestamp) row.get("source_floor");
        Timestamp effective = (Timestamp) row.get("effective_floor");
        Timestamp receiptStarted = (Timestamp) row.get("receipt_started");
        Timestamp receiptFinished = (Timestamp) row.get("receipt_finished");
        Timestamp receiptPublished = (Timestamp) row.get("receipt_published");
        Timestamp bStarted = (Timestamp) row.get("b_started");
        Timestamp bFinished = (Timestamp) row.get("b_finished");
        Timestamp fStarted = (Timestamp) row.get("f_started");
        Timestamp fFinished = (Timestamp) row.get("f_finished");
        Timestamp lStarted = (Timestamp) row.get("l_started");
        Timestamp lFinished = (Timestamp) row.get("l_finished");
        boolean generationGate = generation > 0 && generation == manifestGeneration
                && generation == numberLong(row.get("b_generation"))
                && generation == numberLong(row.get("f_generation"))
                && generation == numberLong(row.get("l_generation"))
                && generation == currentGeneration && generation == numberLong(row.get("observation_generation"))
                && generation == receiptLatest;
        boolean epochCurrent = "ACTIVE".equals(row.get("runtime_mode"))
                && numberLong(row.get("runtime_epoch")) == harness.fence.epoch();
        boolean seasonCurrent = numberLong(row.get("season_id")) == SEASON.id()
                && SEASON.code().equals(row.get("season_code")) && SEASON.code().equals(row.get("season"));
        boolean keyCurrent = "FIXTURE_VERIFICATION".equals(row.get("kind"))
                && ("fixture:" + fixtureId).equals(row.get("coalescing_key"))
                && ("fixture:" + fixtureId).equals(row.get("publication_key"));
        boolean sourceFloorWanted = wanted != null && floor != null && wanted.equals(floor)
                && bStarted != null && fStarted != null && lStarted != null
                && !bStarted.before(floor) && !fStarted.before(floor)
                && effective != null && !lStarted.before(effective);
        boolean readIdentity = "BOOTSTRAP_STATIC".equals(row.get("b_role"))
                && row.get("b_event") == null && row.get("b_player") == null
                && "FIXTURES".equals(row.get("f_role")) && row.get("f_event") == null
                && row.get("f_player") == null && "EVENT_LIVE".equals(row.get("l_role"))
                && row.get("l_player") == null && java.util.Objects.equals(row.get("l_event"), oracle.gameweek());
        boolean anchorValidation = "VALID".equals(row.get("b_validation"))
                && "VALID".equals(row.get("f_validation")) && "VALID".equals(row.get("l_validation"));
        boolean bodyPresent = Boolean.TRUE.equals(row.get("b_body"))
                && Boolean.TRUE.equals(row.get("f_body")) && Boolean.TRUE.equals(row.get("l_body"));
        boolean timeOrder = bStarted != null && bFinished != null && fStarted != null && fFinished != null
                && lStarted != null && lFinished != null && rTimestamp(row, "completed_at") != null
                && !bFinished.after(rTimestamp(row, "completed_at"))
                && !fFinished.after(rTimestamp(row, "completed_at"))
                && !lFinished.after(rTimestamp(row, "completed_at"))
                && !lStarted.before(Timestamp.from(fFinished.toInstant().plusMillis(1)));
        boolean eventHeader = java.util.Objects.equals(row.get("event_id"), oracle.gameweek())
                && java.util.Objects.equals(row.get("fixture_event"), oracle.gameweek())
                && oracle.headerFingerprint().equals(row.get("verified_header"))
                && oracle.statsFingerprint().equals(row.get("verified_stats_fingerprint"))
                && oracle.observationFingerprint().equals(row.get("observation_fp"));
        boolean membershipKnown = Boolean.TRUE.equals(row.get("membership_known"));
        boolean coherent = "true".equals(row.get("coherent"));
        boolean participantsMatch = numberLong(row.get("expected_participants")) == oracle.expectedParticipants()
                && numberLong(row.get("received_participants")) == oracle.receivedParticipants()
                && numberLong(row.get("missing_count")) == 0;
        boolean markerMatches = parent.currentPublished() && parent.immutable()
                && Boolean.TRUE.equals(row.get("finished_parent_finished"))
                && numberLong(row.get("finished_parent_generation")) == parent.sourceGeneration()
                && numberLong(row.get("finished_parent_publication_generation")) == parent.publicationGeneration()
                && numberLong(row.get("finished_parent_bootstrap_payload_id")) == parent.bootstrapPayloadId()
                && numberLong(row.get("finished_parent_fixtures_payload_id")) == parent.fixturesPayloadId()
                && java.util.Objects.equals(row.get("finished_parent_source_fingerprint"), parent.sourceFingerprint())
                && OwnedSyncStore.sha256(oracle.headerFingerprint())
                        .equals(row.get("finished_parent_fixture_fingerprint"));
        boolean recoveryWindow = jdbc.queryForObject("SELECT recovery_day IS NOT NULL"
                + " AND recovery_started_at IS NOT NULL AND recovery_until>recovery_started_at"
                + " AND recovery_until<=recovery_started_at+INTERVAL '12 hours'"
                + " AND recovery_day=CASE WHEN (recovery_started_at AT TIME ZONE 'Europe/London')::time"
                + " < TIME '03:00' THEN (recovery_started_at AT TIME ZONE 'Europe/London')::date-1"
                + " ELSE (recovery_started_at AT TIME ZONE 'Europe/London')::date END FROM "
                + TABLES.fixtureSync() + " WHERE season_id=? AND fixture_id=?", Boolean.class,
                SEASON.id(), fixtureId);
        boolean sourceIntervals = generation > 0 && sourceIntervalProof(generation, row);
        boolean targetReceiptInterval = receiptIntervalProof(rTimestamp(row, "run_started"),
                rTimestamp(row, "run_finished"), receiptStarted, receiptFinished,
                rTimestamp(row, "completed_at"), receiptPublished, harness.clock.instant());
        boolean timestampPrecision = timestampPrecision(row, wanted, floor, bStarted, bFinished,
                fStarted, fFinished, lStarted, lFinished, rTimestamp(row, "evidence_started"),
                rTimestamp(row, "evidence_finished"),
                rTimestamp(row, "run_started"), rTimestamp(row, "run_finished"),
                rTimestamp(row, "completed_at"), receiptStarted, receiptFinished, receiptPublished,
                rTimestamp(row, "fixture_started"), rTimestamp(row, "fixture_finished"), effective);
        boolean parentCurrent = parent.currentPublished() && parent.immutable()
                && actualParent.currentPublished();
        boolean parentProofMatchesProduction = parent.currentPublished() == actualParent.currentPublished();
        boolean publishedManifestProof = generationGate && epochCurrent && seasonCurrent && keyCurrent && sourceFloorWanted
                && readIdentity && anchorValidation && bodyPresent && timeOrder && eventHeader
                && membershipKnown && coherent && participantsMatch && markerMatches && recoveryWindow
                && sourceIntervals && targetReceiptInterval && timestampPrecision
                && parentCurrent && parentProofMatchesProduction
                && actualParent.fixtureFinished();
        Map<String, Object> result = new TreeMap<>();
        result.put("fixtureId", fixtureId);
        result.put("status", currentGeneration == 0 || receiptLatest != currentGeneration
                ? "missing_receipt" : "unclassified");
        result.put("generation", generation);
        result.put("latestTargetGeneration", latestGeneration);
        result.put("currentVerifiedGeneration", currentGeneration);
        result.put("receiptLatestGeneration", receiptLatest);
        result.put("fixtureState", row.get("fixture_state"));
        result.put("runState", row.get("run_state"));
        result.put("runReason", row.get("run_error"));
        result.put("validationFailures", row.get("validation_failures"));
        result.put("generationEpochKey", Map.of("generation", generationGate,
                "epochCurrent", epochCurrent, "seasonCurrent", seasonCurrent, "keyCurrent", keyCurrent));
        result.put("sourceFloorWanted", sourceFloorWanted);
        result.put("effectiveFloor", effective);
        Map<String, Object> sourceReads = new TreeMap<>();
        sourceReads.put("bootstrap", java.util.Arrays.asList(bStarted, bFinished));
        sourceReads.put("fixtures", java.util.Arrays.asList(fStarted, fFinished));
        sourceReads.put("live", java.util.Arrays.asList(lStarted, lFinished));
        sourceReads.put("history", java.util.Arrays.asList(row.get("evidence_started"), row.get("evidence_finished")));
        sourceReads.put("fixtureRun", java.util.Arrays.asList(row.get("run_started"), row.get("run_finished")));
        sourceReads.put("fixtureReceipt", java.util.Arrays.asList(receiptStarted, receiptFinished,
                row.get("completed_at"), receiptPublished));
        result.put("sourceRead3Anchors", sourceReads);
        result.put("anchorRolesValid", readIdentity && anchorValidation);
        result.put("coherentBodyPresent", Map.of("coherent", coherent, "bodyPresent", bodyPresent));
        result.put("eventHeader", eventHeader);
        result.put("membershipKnown", membershipKnown);
        Map<String, Object> participants = new TreeMap<>();
        participants.put("expected", row.get("expected_participants"));
        participants.put("received", row.get("received_participants"));
        participants.put("missing", row.get("missing_count"));
        result.put("participants", participants);
        result.put("parentCurrentPublished", parent.currentPublished());
        result.put("parentImmutable", parent.immutable());
        result.put("parentRootMarkerMatches", markerMatches);
        result.put("parentProofMatchesProduction", parentProofMatchesProduction);
        result.put("parentCanonicalLane", parent.trace());
        result.put("actualRuleCurrentPublished", actualParent.currentPublished());
        result.put("actualRuleFixtureFinished", actualParent.fixtureFinished());
        result.put("recoveryWindow", recoveryWindow);
        result.put("sourceIntervalsCoherent", sourceIntervals);
        result.put("targetReceiptInterval", targetReceiptInterval);
        result.put("timestampPrecision", timestampPrecision);
        result.put("participantCounts", participantsMatch);
        result.put("publishedManifestProof", publishedManifestProof);
        result.put("manifestPlayerCount", numberLong(row.get("manifest_player_count")));
        result.put("batchIntervals", fixtureBatchTrace(harness, fixtureId));
        return result;
    }

    private static Timestamp rTimestamp(Map<String, Object> row, String key) {
        return (Timestamp) row.get(key);
    }

    private static boolean sourceIntervalProof(long generation, Map<String, Object> row) {
        Timestamp runStarted = (Timestamp) row.get("run_started");
        Timestamp runFinished = (Timestamp) row.get("run_finished");
        Timestamp completedAt = (Timestamp) row.get("completed_at");
        Timestamp bStarted = (Timestamp) row.get("b_started");
        Timestamp bFinished = (Timestamp) row.get("b_finished");
        Timestamp fStarted = (Timestamp) row.get("f_started");
        Timestamp fFinished = (Timestamp) row.get("f_finished");
        Timestamp lStarted = (Timestamp) row.get("l_started");
        Timestamp lFinished = (Timestamp) row.get("l_finished");
        Timestamp evidenceStarted = (Timestamp) row.get("evidence_started");
        Timestamp evidenceFinished = (Timestamp) row.get("evidence_finished");
        Timestamp fixtureStarted = (Timestamp) row.get("fixture_started");
        Timestamp fixtureFinished = (Timestamp) row.get("fixture_finished");
        if (runStarted == null || runFinished == null || completedAt == null || bStarted == null || bFinished == null
                || fStarted == null || fFinished == null || lStarted == null || lFinished == null
                || evidenceStarted == null || evidenceFinished == null
                || fixtureStarted == null || fixtureFinished == null) return false;
        Instant expectedStarted = List.of(bStarted.toInstant(), fStarted.toInstant(), lStarted.toInstant(),
                evidenceStarted.toInstant()).stream().min(Instant::compareTo).orElseThrow();
        Instant expectedFinished = List.of(bFinished.toInstant(), fFinished.toInstant(), lFinished.toInstant(),
                evidenceFinished.toInstant()).stream().max(Instant::compareTo).orElseThrow();
        Instant expectedFixtureStarted = List.of(fStarted.toInstant(), lStarted.toInstant(),
                evidenceStarted.toInstant()).stream().min(Instant::compareTo).orElseThrow();
        Instant expectedFixtureFinished = List.of(fFinished.toInstant(), lFinished.toInstant(),
                evidenceFinished.toInstant()).stream().max(Instant::compareTo).orElseThrow();
        boolean rolesOrdered = !bStarted.after(bFinished) && !fStarted.after(fFinished)
                && !lStarted.after(lFinished) && !evidenceStarted.after(evidenceFinished);
        return rolesOrdered && runStarted.toInstant().equals(expectedStarted)
                && runFinished.toInstant().equals(expectedFinished)
                && fixtureStarted.toInstant().equals(expectedFixtureStarted)
                && fixtureFinished.toInstant().equals(expectedFixtureFinished)
                && !fixtureStarted.before(runStarted) && !fixtureFinished.after(runFinished)
                && !runStarted.after(runFinished) && !runFinished.after(completedAt)
                && databaseMicroseconds(runStarted, runFinished, completedAt, bStarted, bFinished,
                        fStarted, fFinished, lStarted, lFinished, evidenceStarted, evidenceFinished,
                        fixtureStarted, fixtureFinished)
                && generation == numberLong(row.get("run_generation"));
    }

    private static boolean timestampPrecision(Map<String, Object> row, Timestamp... values) {
        for (Timestamp value : values) {
            if (value == null || value.getNanos() % 1_000 != 0) return false;
        }
        for (String column : List.of("completed_at", "run_started", "run_finished", "receipt_started",
                "receipt_finished", "receipt_published", "fixture_started", "fixture_finished",
                "evidence_started", "evidence_finished", "recovery_started_at", "recovery_until")) {
            Timestamp value = (Timestamp) row.get(column);
            if (value != null && value.getNanos() % 1_000 != 0) return false;
        }
        return true;
    }

    private static List<Map<String, Object>> fixtureBatchTrace(Harness harness, int fixtureId) {
        return jdbc.queryForList("SELECT generation,state,runtime_mode,runtime_epoch,source_read_started_at,"
                + "source_read_finished_at,completed_at FROM " + TABLES.run()
                + " WHERE season=? AND runtime_mode='ACTIVE' AND runtime_epoch=?"
                + " AND kind='FIXTURE_VERIFICATION' AND publication_key=?"
                + " AND state='COMPLETED' ORDER BY generation ASC LIMIT 2",
                SEASON.code(), harness.fence.epoch(), "fixture:" + fixtureId);
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

    private static boolean isDatabaseMicrosecond(Instant instant) {
        return instant.getNano() % 1_000 == 0;
    }

    private static String activeAcceptanceTrace(Harness harness, ManualDriver preparationDriver,
                                                ManualDriver driver, Set<Integer> targetFixtures,
                                                ActiveAcceptanceMetrics metrics,
                                                PayloadReadProbe.Snapshot blockedRetryBodies,
                                                PayloadReadProbe.Snapshot completePublicationBodies) {
        String keys = targetFixtures.stream().sorted().map(id -> "'fixture:" + id + "'")
                .collect(java.util.stream.Collectors.joining(","));
        List<Map<String, Object>> syncRows = jdbc.queryForList("SELECT fixture_id,state,attempt_count,"
                + "observation_generation,validation_failures,source_read_started_at,source_read_finished_at,"
                + "first_finished_observed_at,next_due_at,due_reason,dependency_retry_at,alert_state,"
                + "recovery_day,recovery_started_at,recovery_until,current_verified_generation,"
                + "last_verified_generation,last_error FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id BETWEEN 41 AND 50 ORDER BY fixture_id", SEASON.id());
        List<Map<String, Object>> fixtureRuns = jdbc.queryForList("SELECT generation,publication_key,state,"
                + "attempt_count,due_at,requested_source_after,last_attempt_at,last_error FROM " + TABLES.run()
                + " WHERE season=? AND runtime_mode='ACTIVE' AND runtime_epoch=?"
                + " AND kind='FIXTURE_VERIFICATION' AND publication_key IN (" + keys + ")"
                + " ORDER BY generation", SEASON.code(), harness.fence.epoch());
        List<Map<String, Object>> recoverySlots = jdbc.queryForList("SELECT slot_id,kind,due_at,fixture_ids,"
                + "reason,enqueued_at FROM " + TABLES.plannerSlot()
                + " WHERE season=? AND fixture_ids && ARRAY[41,42,43,44,45,46,47,48,49,50]::integer[]"
                + " ORDER BY enqueued_at,slot_id", SEASON.code());
        List<Map<String, Object>> manifests = jdbc.queryForList("SELECT r.generation,r.publication_key,r.state,"
                + "r.attempt_count,r.due_at,m.source_floor,cardinality(m.required_player_ids) AS required_players,"
                + "(SELECT count(*) FROM unnest(m.required_player_ids) required(player_id) WHERE EXISTS("
                + "SELECT 1 FROM " + TABLES.payload() + " hp JOIN " + TABLES.run()
                + " hr ON hr.generation=hp.run_generation WHERE hp.player_id=required.player_id"
                + " AND hp.endpoint_kind='ELEMENT_SUMMARY' AND hp.validation_state='VALID'"
                + " AND hr.runtime_mode='ACTIVE' AND hr.runtime_epoch=? AND hr.state='COMPLETED'"
                + " AND hp.read_started_at>=GREATEST(r.requested_source_after,m.source_floor)))"
                + " AS fresh_histories FROM " + TABLES.fixtureDependencyManifest() + " m JOIN "
                + TABLES.run() + " r ON r.generation=m.run_generation"
                + " WHERE r.season=? AND r.runtime_mode='ACTIVE' AND r.runtime_epoch=?"
                + " AND r.publication_key IN (" + keys + ") ORDER BY r.generation",
                harness.fence.epoch(), SEASON.code(), harness.fence.epoch());
        return "OWNED_ACTIVE_RECOVERY_TRACE stop=" + driver.stopReason
                + " firstNewFixture=" + metrics.firstNewFixture()
                + " firstNewGeneration=" + metrics.firstNewGeneration()
                + " firstNewAttempt=" + metrics.firstNewAttempt()
                + " firstNewWorker=" + metrics.firstNewWorker()
                + " firstNewClaim=" + metrics.firstNewClaim()
                + " firstPending=" + driver.firstPendingObservations
                + " verifiedReceipts=" + metrics.verifiedReceiptGenerations()
                + " servedCorrections=" + metrics.servedCorrectionReceipts()
                + " correctionReplayRuns=" + metrics.correctionReplayRuns()
                + " preparationStarts=" + preparationDriver.dispatches
                + " preparationClaims=" + preparationDriver.claims
                + " driverStarts=" + driver.dispatches + " driverCompletions=" + driver.completions.size()
                + " driverClaims=" + driver.claims
                + " virtualNow=" + harness.clock.instant() + " maxVirtual=" + metrics.maxVirtualElapsed()
                + " claims=" + metrics.claims() + " dispatches=" + metrics.dispatches()
                + " admitted=" + harness.durableAdmissionCount() + " endpoints=" + harness.server.getsByEndpoint()
                + " returnedBytes=" + harness.server.returnedRawBytes()
                + " verified=" + metrics.verifiedFixtureIds() + " failedRuns=" + metrics.failedRuns()
                + " recoverySlots=" + recoverySlots
                + " missingHistory=" + metrics.missingHistoryEvidence() + " badManifests=" + metrics.badFixtureManifests()
                + " missingReceipts=" + metrics.missingReceipts() + " zeroSkipped=" + metrics.skippedFixtureTraces()
                + " targetRows=" + metrics.fixtureProofs().size() + "/" + targetFixtures.size()
                + " badBatches=" + metrics.badBatchPairs() + " sourceProofFlags=" + metrics.fixtureProofs()
                + " canonicalParent=" + metrics.canonicalParentProof()
                + " driverFirst10Transitions=" + mergeTargetTransitions(preparationDriver, driver)
                + " lastNextWake=" + driver.lastNextWake
                + " advancesWithReadyTargets=" + driver.advancesWithReadyTargets
                + " bounds={virtual=" + metrics.maxVirtualElapsed() + "/" + ACTIVE_RECOVERY_VIRTUAL_LIMIT
                + ",claims=" + metrics.claims() + "/" + ACTIVE_RECOVERY_CLAIM_LIMIT
                + ",dispatches=" + metrics.dispatches() + "/" + ACTIVE_RECOVERY_DISPATCH_LIMIT
                + ",wallSeconds=" + Duration.ofNanos(System.nanoTime() - harness.wallStartedAtNanos).toMillis() / 1000.0
                + "/1500} blockedRetryWorkerLogicalReads=" + logicalReadStats(blockedRetryBodies)
                + " workerPublicationLogicalReads=" + logicalReadStats(completePublicationBodies)
                + " diagnosticCanonicalParentPayload={queries="
                + metrics.canonicalParentProof().get("diagnosticPayloadQueries") + ",rawBytes="
                + metrics.canonicalParentProof().get("diagnosticPayloadRawBytes") + "}"
                + " syncRows=" + syncRows + " fixtureRuns=" + fixtureRuns + " manifests=" + manifests;
    }

    private static Map<Integer, List<String>> mergeTargetTransitions(ManualDriver preparationDriver,
                                                                       ManualDriver driver) {
        Map<Integer, List<String>> result = new TreeMap<>();
        for (ManualDriver selected : List.of(preparationDriver, driver)) {
            selected.targetTransitions.forEach((fixtureId, transitions) -> {
                List<String> combined = result.computeIfAbsent(fixtureId, ignored -> new ArrayList<>());
                for (String transition : transitions) {
                    if (combined.size() == 10) break;
                    combined.add(transition);
                }
            });
        }
        return result;
    }

    private static Map<PayloadReadProbe.BodyFamily, String> logicalReadStats(PayloadReadProbe.Snapshot snapshot) {
        Map<PayloadReadProbe.BodyFamily, String> result = new java.util.EnumMap<>(PayloadReadProbe.BodyFamily.class);
        snapshot.logicalBodies().forEach((family, metrics) -> result.put(family, metrics.counts()));
        return result;
    }

    private static void assertTargetReceiptIntervalNegativeCases(Harness harness, int fixtureId) {
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
    @Timeout(value = 315, unit = TimeUnit.SECONDS)
    void finiteDrainStagesAllPinnedFixturesAndHistoryDependencies() throws Exception {
        assertThat(DRAIN_VIRTUAL_LIMIT).as("finite-drain virtual stop ceiling remains two hours")
                .isEqualTo(Duration.ofHours(2));
        assertThat(DRAIN_WALL_LIMIT_NANOS).as("finite-drain work stop ceiling remains 300 seconds")
                .isEqualTo(TimeUnit.SECONDS.toNanos(300));
        assertThat(DRAIN_CLEANUP_RESERVE_NANOS).as("finite-drain cleanup watchdog reserve remains ten seconds")
                .isEqualTo(TimeUnit.SECONDS.toNanos(10));
        assertThat(DRAIN_ADMISSION_LIMIT).as("finite-drain admission stop ceiling remains 5,000")
                .isEqualTo(5_000);
        assertThat(DRAIN_DISPATCH_LIMIT).as("finite-drain dispatch stop ceiling remains 6,000")
                .isEqualTo(6_000);
        long wallStartedAtNanos = System.nanoTime();
        long wallDeadline = wallStartedAtNanos + DRAIN_WALL_LIMIT_NANOS;
        FiniteDrainMetrics metrics;
        try (Harness harness = new Harness(1, wallDeadline, wallStartedAtNanos)) {
            harness.seedCanonicalPair();
            harness.seedMixedBacklog();
            metrics = harness.driveFiniteDrain();
        }

        double postWorkSeconds = (System.nanoTime() - wallStartedAtNanos) / 1_000_000_000.0
                - metrics.wallSeconds();
        assertThat(postWorkSeconds)
                .as("metrics SQL and cleanup after the work stop fit within the ten-second reserve")
                .isLessThanOrEqualTo(DRAIN_CLEANUP_RESERVE_NANOS / 1_000_000_000.0);
        assertThat(metrics.stopReason()).isEqualTo("DRAINED");
        assertThat(metrics.virtualSeconds()).isLessThanOrEqualTo(DRAIN_VIRTUAL_LIMIT.toNanos() / 1e9);
        assertThat(metrics.wallSeconds()).isLessThanOrEqualTo(DRAIN_WALL_LIMIT_NANOS / 1e9);
        assertThat(metrics.admittedAttempts()).isLessThanOrEqualTo(DRAIN_ADMISSION_LIMIT);
        assertThat(metrics.dispatches()).as("all dispatch attempts, including unsuccessful ones, count toward the ceiling")
                .isLessThanOrEqualTo(DRAIN_DISPATCH_LIMIT);
        assertThat(metrics.completedDispatches()).isLessThanOrEqualTo(DRAIN_DISPATCH_LIMIT);
        assertThat(metrics.seededFixtureCount()).isEqualTo(FIXTURE_RUNS);
        assertThat(metrics.seededFixtureRunsCompleted()).isEqualTo(FIXTURE_RUNS);
        assertThat(metrics.completedFixtures()).isEqualTo(FIXTURE_RUNS);
        assertThat(metrics.failedRuns()).as("failed runs are never counted as drained work").isZero();
        assertThat(metrics.unfinishedFixtureRuns()).isZero();
        assertThat(metrics.unfinishedHistoryRuns()).isZero();
        assertThat(metrics.requiredUniquePlayers()).isGreaterThan(0);
        assertThat(metrics.completedRequiredHistoryPlayers())
                .as("every pinned fixture dependency has valid history from a completed run")
                .isEqualTo(metrics.requiredUniquePlayers());
        assertThat(metrics.fixtureManifestsChecked())
                .as("all seeded fixtures have an exact valid dependency manifest and source anchor")
                .isEqualTo(FIXTURE_RUNS);
        assertThat(metrics.fixtureManifestMismatches())
                .as("all 50 durable fixture manifests match their pinned source participants")
                .isZero();
        assertThat(metrics.predecessorWaits()).as("real FIFO wait advancement was observed").isGreaterThan(0);
        assertThat(metrics.spacingWaits()).as("real spacing wait advancement was observed").isGreaterThan(0);
    }

    @Test
    @Timeout(value = 255, unit = TimeUnit.SECONDS)
    void fullRosterTwoWorkersStageSourceCompleteFixtureWithinBudget() throws Exception {
        SourceData fullRoster = SourceData.loadFullRoster();
        JsonNode bootstrap = JSON.readTree(fullRoster.bootstrapBytes());
        Map<Integer, Integer> clubSizes = new HashMap<>();
        bootstrap.withArray("elements").forEach(player ->
                clubSizes.merge(player.get("team").intValue(), 1, Integer::sum));
        assertThat(fullRoster.playerIds()).hasSize(FULL_ROSTER_PLAYERS).doesNotHaveDuplicates();
        assertThat(fullRoster.playerIds()).isEqualTo(fullRoster.playerIds().stream().sorted().toList());
        assertThat(clubSizes).hasSize(FULL_ROSTER_CLUBS);
        assertThat(clubSizes.values()).allMatch(size -> size >= 25 && size <= 43);
        Map<Integer, Set<Integer>> requiredByFixture = fullRoster.requiredHistoryPlayersByFixture(FIXTURE_RUNS);
        assertThat(requiredByFixture).hasSize(FIXTURE_RUNS);
        assertThat(requiredByFixture.values()).allSatisfy(players ->
                assertThat(players.size()).isBetween(50, 86));

        long wallStartedAt = System.nanoTime();
        long wallDeadline = wallStartedAt + ROSTER_BASELINE_WALL_LIMIT_NANOS;
        RosterBaselineMetrics metrics;
        try (Harness harness = new Harness(1, wallDeadline, wallStartedAt, fullRoster)) {
            harness.seedCanonicalPair();
            harness.seedPreAnchorHistories();
            harness.seedMixedBacklog();
            harness.resetPayloadReads();
            metrics = harness.driveRosterBaseline();
            harness.assertStagedHistoryCoverage();
        }
        double wallSeconds = (System.nanoTime() - wallStartedAt) / 1_000_000_000.0;
        double postWorkSeconds = wallSeconds - metrics.workWallSeconds();
        System.out.printf("OWNED_ADMISSION_ROSTER_BASELINE stop=%s firstStageFixture=%s "
                        + "firstStageSeconds=%s firstStageAdmissions=%s admissions=%d dispatchStarts=%d "
                        + "completedDispatches=%d endpoints=%s returnedGets=%d returnedRawBytes=%d "
                        + "payloadBodyValues=%d payloadReturnedRawBytes=%d preAnchorHistories=%d "
                        + "historyRunsCompleted=%d historyRunsPending=%d historyJobsNotYetClaimed=%d "
                        + "historyRequestsAdvancedPastPreAnchor=%d "
                        + "repeatedHistoryGets=%d playersWithRepeatedHistoryGets=%d fixturesCompleted=%d "
                        + "untouchedFixtures=%d readyCoverage=%d manifests=%d validManifests=%d "
                        + "manifestRequiredIds=%d..%d manifestOverlap=%d manifestedPlayers=%d "
                        + "unmanifestedRosterPlayers=%d manifestMismatches=%d sourceFloorMismatches=%d "
                        + "epochMismatches=%d failedRuns=%d invalidPayloads=%d uncertainAttempts=%d "
                        + "unresolvedAttempts=%d virtualSeconds=%.3f wallSeconds=%.3f%n",
                metrics.stopReason(), metrics.firstStageFixture(), offset(metrics.firstStageAt()),
                metrics.firstStageAt() == null ? "none" : metrics.admissionsAtFirstStage(),
                metrics.admissions(), metrics.dispatchStarts(),
                metrics.completedDispatches(), metrics.endpoints(), metrics.returnedGets(), metrics.returnedRawBytes(),
                metrics.payloadReads().bodyValues(), metrics.payloadReads().rawBytes(),
                metrics.preAnchorHistories(), metrics.historyRunsCompleted(), metrics.historyRunsPending(),
                metrics.historyJobsNotYetClaimed(), metrics.historyRequestsAdvancedPastPreAnchor(),
                metrics.repeatedHistoryGets(), metrics.playersWithRepeatedHistoryGets(),
                metrics.fixturesCompleted(), metrics.untouchedFixtures(),
                metrics.readyCoverage(), metrics.manifests(), metrics.validManifests(),
                metrics.minimumManifestPlayers(), metrics.maximumManifestPlayers(), metrics.manifestOverlap(),
                metrics.manifestedPlayers(), metrics.unmanifestedRosterPlayers(), metrics.manifestMismatches(),
                metrics.sourceFloorMismatches(), metrics.epochMismatches(), metrics.failedRuns(),
                metrics.invalidPayloads(), metrics.uncertainAttempts(), metrics.unresolvedAttempts(),
                metrics.virtualSeconds(), metrics.workWallSeconds());
        System.out.printf("OWNED_ADMISSION_ROSTER_DB_BODY_READS oldOwn=%s guardedSingle=%s canonicalPair=%s "
                        + "other=%s totals=%d/%d/%d/%d%n",
                metrics.payloadReads().family(PayloadReadProbe.Family.OLD_OWN_CHECKPOINT).counts(),
                metrics.payloadReads().family(PayloadReadProbe.Family.GUARDED_SINGLE).counts(),
                metrics.payloadReads().family(PayloadReadProbe.Family.CANONICAL_PAIR).counts(),
                metrics.payloadReads().family(PayloadReadProbe.Family.OTHER_BODY_SELECT).counts(),
                metrics.payloadReads().attempts(), metrics.payloadReads().resultRows(),
                metrics.payloadReads().bodyValues(), metrics.payloadReads().rawBytes());

        assertThat(metrics.stopReason()).isEqualTo("STAGED");
        assertThat(metrics.firstStageAt()).isNotNull();
        assertThat(metrics.fixturesCompleted()).isGreaterThan(0);
        assertThat(metrics.payloadReads().rawBytes()).isLessThanOrEqualTo(70_000_000);
        assertThat(metrics.virtualSeconds()).isLessThanOrEqualTo(ROSTER_BASELINE_VIRTUAL_LIMIT.toNanos() / 1e9);
        assertThat(metrics.admissions()).isLessThanOrEqualTo(ROSTER_BASELINE_ADMISSION_LIMIT);
        assertThat(metrics.dispatchStarts()).isLessThanOrEqualTo(ROSTER_BASELINE_DISPATCH_LIMIT);
        assertThat(metrics.workWallSeconds()).isLessThanOrEqualTo(ROSTER_BASELINE_WALL_LIMIT_NANOS / 1e9);
        assertThat(postWorkSeconds).as("post-work metrics and cleanup fit within the existing reserve")
                .isLessThanOrEqualTo(DRAIN_CLEANUP_RESERVE_NANOS / 1e9);
        assertThat(metrics.manifestMismatches()).isZero();
        assertThat(metrics.sourceFloorMismatches()).isZero();
        assertThat(metrics.epochMismatches()).isZero();
        assertThat(metrics.failedRuns()).isZero();
        assertThat(metrics.invalidPayloads()).isZero();
        assertThat(metrics.uncertainAttempts()).isZero();
        assertThat(metrics.unresolvedAttempts()).isZero();
        if (metrics.manifests() > 0) {
            assertThat(metrics.minimumManifestPlayers()).isBetween(50, 86);
            assertThat(metrics.maximumManifestPlayers()).isBetween(50, 86);
            assertThat(metrics.validManifests()).isEqualTo(metrics.manifests());
        }
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

    private record FiniteDrainMetrics(String stopReason, int seededFixtureCount,
                                      int seededFixtureRunsCompleted, int completedFixtures,
                                      int requiredUniquePlayers, int completedRequiredHistoryPlayers,
                                      int fixtureManifestsChecked, int fixtureManifestMismatches,
                                      int seededHistoryPlayers, int additionalHistoryPlayersSeeded,
                                      int completedHistoryRuns,
                                      int repeatedHistoryGets, int playersWithRepeatedHistoryGets,
                                      int failedRuns, int unfinishedFixtureRuns, int unfinishedHistoryRuns,
                                      int admittedAttempts, Map<EndpointKind, Integer> getsByEndpoint,
                                      int returnedGets, long returnedRawBytes, int payloadRows,
                                      long stagedRawBytes, int currentPartialPairs, int peakPartialPairs,
                                      int dispatches, int completedDispatches, int claims, int deferrals,
                                      int predecessorWaits, int spacingWaits,
                                      double virtualSeconds, double wallSeconds) { }

    private record RosterBaselineMetrics(String stopReason, Instant firstStageAt, Integer firstStageFixture,
                                         Integer admissionsAtFirstStage, int admissions, int dispatchStarts,
                                         int completedDispatches, Map<EndpointKind, Integer> endpoints,
                                         int returnedGets, long returnedRawBytes,
                                         PayloadReadProbe.Snapshot payloadReads, int preAnchorHistories,
                                         int historyRunsCompleted, int historyRunsPending,
                                         int historyJobsNotYetClaimed, int historyRequestsAdvancedPastPreAnchor,
                                         int repeatedHistoryGets,
                                         int playersWithRepeatedHistoryGets, int fixturesCompleted,
                                         int untouchedFixtures, int readyCoverage, int manifests,
                                         int validManifests, int minimumManifestPlayers,
                                         int maximumManifestPlayers, int manifestOverlap, int manifestedPlayers,
                                         int unmanifestedRosterPlayers, int manifestMismatches,
                                         int sourceFloorMismatches, int epochMismatches, int failedRuns,
                                         int invalidPayloads, int uncertainAttempts, int unresolvedAttempts,
                                         double virtualSeconds, double workWallSeconds) { }

    private record SeededFixture(int fixtureId, long generation) { }

    private record DriverGoal(Set<Integer> fixtureIds, boolean pendingOnly) {
        static DriverGoal fixtures(Set<Integer> ids) { return new DriverGoal(Set.copyOf(ids), false); }
        static DriverGoal pendingFixtures(Set<Integer> ids) { return new DriverGoal(Set.copyOf(ids), true); }
    }

    private record FirstPendingObservation(long generation, SourceRead reads, String reasons) { }

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
                                                List<Long> verificationRunGenerations,
                                                FixtureOwnershipSnapshot ownership) { }

    private record RootDocuments(CatalogueScheduleParser.SourceDocument bootstrap,
                                 CatalogueScheduleParser.SourceDocument fixtures) { }

    private record FixtureOracle(String headerFingerprint, String statsFingerprint,
                                 String observationFingerprint, int gameweek,
                                 Set<Integer> requiredPlayers, int expectedParticipants,
                                 int receivedParticipants) { }

    private record ActiveAcceptanceMetrics(Set<Integer> verifiedFixtureIds,
                                           Map<Integer, Long> verifiedReceiptGenerations,
                                           int verifiedReceipts, int missingReceipts, int skippedFixtureTraces,
                                           List<Map<String, Object>> fixtureProofs,
                                           Map<String, Object> canonicalParentProof,
                                           int servedCorrectionReceipts,
                                           int correctionReplayRuns, Set<Integer> unservedChangedCorrections,
                                           Instant firstVerifiedAt, Instant lastVerifiedAt,
                                           int firstNewFixture, long firstNewGeneration, int firstNewAttempt,
                                           String firstNewWorker, Instant firstNewClaim,
                                           Duration maxVirtualElapsed,
                                           int claims, int dispatches, int missingHistoryEvidence,
                                           int badFixtureManifests, int badBatchPairs, int failedRuns,
                                           boolean sawReadyFixture, boolean sawBlockedFixture,
                                           boolean sawUntouchedFixture, int preferentialHistoryClaims,
                                           int fifoHistoryClaims, int canonicalPreemptions,
                                           int pendingUnknownGates) { }

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

    private record FixturePlayerEvidence(long fixtureGeneration, int playerId, boolean historyEligible) { }

    private record FixtureManifestCheck(Set<Integer> manifestPlayers, boolean checked) { }

    private record FixturePlayer(long fixtureGeneration, int playerId) { }

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
                    runtime, store, catalogue, histories, "admission-repro");
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

        void seedPreAnchorHistories() {
            var read = new SourceRead(clock.instant(), clock.instant());
            var interval = new CatalogueScheduleParser.ReadInterval(read.startedAt(), read.finishedAt());
            var snapshot = CatalogueScheduleParser.parse(SEASON,
                    new CatalogueScheduleParser.SourceDocument(source.bootstrapBytes(), interval),
                    new CatalogueScheduleParser.SourceDocument(source.fixtureBytes(), interval));
            source.playerIds().stream().sorted().forEach(playerId ->
                    seedValidHistory(snapshot, playerId, clock.instant()));
            assertThat(jdbc.queryForObject("SELECT count(DISTINCT p.player_id) FROM " + TABLES.payload()
                    + " p JOIN " + TABLES.run() + " r ON r.generation=p.run_generation"
                    + " WHERE r.season=? AND r.kind='PLAYER_HISTORY' AND p.endpoint_kind='ELEMENT_SUMMARY'"
                    + " AND p.validation_state='VALID' AND p.read_started_at<=?",
                    Integer.class, SEASON.code(), Timestamp.from(START))).isEqualTo(source.playerIds().size());
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

        RosterBaselineMetrics driveRosterBaseline() throws Exception {
            ManualDriver driver = new ManualDriver(this, ROSTER_BASELINE_VIRTUAL_LIMIT,
                    ROSTER_BASELINE_ADMISSION_LIMIT, ROSTER_BASELINE_DISPATCH_LIMIT);
            http.setWaitObserverForTests(driver::observeWait);
            driver.run();
            return rosterBaselineMetrics(driver);
        }

        FiniteDrainMetrics driveFiniteDrain() throws Exception {
            ManualDriver driver = new ManualDriver(this, true);
            http.setWaitObserverForTests(driver::observeWait);
            driver.run();
            FiniteDrainMetrics metrics = finiteDrainMetrics(driver);
            System.out.printf("OWNED_ADMISSION_DRAIN_RESULT stopReason=%s seededFixtures=%d "
                            + "seededFixtureRunsCompleted=%d completedFixtures=%d "
                            + "requiredUniquePlayers=%d completedRequiredHistoryPlayers=%d "
                            + "fixtureManifestsChecked=%d fixtureManifestMismatches=%d seededHistoryPlayers=%d "
                            + "additionalHistoryPlayersSeeded=%d "
                            + "completedHistoryRuns=%d repeatedHistoryGets=%d playersWithRepeatedHistoryGets=%d "
                            + "failedRuns=%d unfinishedFixtureRuns=%d unfinishedHistoryRuns=%d "
                            + "admittedAttempts=%d endpoints=%s returnedGets=%d returnedRawBytes=%d "
                            + "payloadRows=%d stagedRawBytes=%d currentPartialPairs=%d peakPartialPairs=%d "
                            + "dispatchesStarted=%d completedDispatches=%d claims=%d deferrals=%d "
                            + "predecessorWaits=%d spacingWaits=%d virtualSeconds=%.3f wallSeconds=%.3f%n",
                    metrics.stopReason(), metrics.seededFixtureCount(), metrics.seededFixtureRunsCompleted(),
                    metrics.completedFixtures(), metrics.requiredUniquePlayers(),
                    metrics.completedRequiredHistoryPlayers(), metrics.fixtureManifestsChecked(),
                    metrics.fixtureManifestMismatches(), metrics.seededHistoryPlayers(),
                    metrics.additionalHistoryPlayersSeeded(), metrics.completedHistoryRuns(),
                    metrics.repeatedHistoryGets(),
                    metrics.playersWithRepeatedHistoryGets(), metrics.failedRuns(),
                    metrics.unfinishedFixtureRuns(), metrics.unfinishedHistoryRuns(), metrics.admittedAttempts(),
                    metrics.getsByEndpoint(), metrics.returnedGets(), metrics.returnedRawBytes(),
                    metrics.payloadRows(), metrics.stagedRawBytes(), metrics.currentPartialPairs(),
                    metrics.peakPartialPairs(), metrics.dispatches(), metrics.completedDispatches(),
                    metrics.claims(), metrics.deferrals(), metrics.predecessorWaits(), metrics.spacingWaits(),
                    metrics.virtualSeconds(), metrics.wallSeconds());
            return metrics;
        }

        void assertStagedHistoryCoverage() {
            int missingHistories = jdbc.queryForObject("""
                    SELECT count(*) FROM %s r
                    JOIN %s m ON m.run_generation = r.generation
                    JOIN %s f ON f.id = m.fixtures_payload_id
                    CROSS JOIN LATERAL unnest(m.required_player_ids) AS required(player_id)
                    WHERE r.season = ? AND r.runtime_mode = ? AND r.runtime_epoch = ?
                      AND r.kind = 'FIXTURE_VERIFICATION' AND r.state = 'COMPLETED'
                      AND NOT EXISTS (
                          SELECT 1 FROM %s hp JOIN %s hr ON hr.generation = hp.run_generation
                          WHERE hp.endpoint_kind = 'ELEMENT_SUMMARY' AND hp.event_id IS NULL
                            AND hp.player_id = required.player_id AND hp.validation_state = 'VALID'
                            AND hp.read_started_at >= GREATEST(r.requested_source_after, m.source_floor,
                                f.read_finished_at + INTERVAL '1 millisecond')
                            AND hp.read_finished_at <= r.completed_at AND hp.staged_at <= r.completed_at
                            AND hr.kind = 'PLAYER_HISTORY' AND hr.state = 'COMPLETED'
                            AND hr.completed_at <= r.completed_at
                            AND hr.coalescing_key = 'player-history:' || required.player_id::text
                            AND hr.publication_key = 'player-history:' || required.player_id::text
                            AND hr.season = r.season AND hr.runtime_mode = r.runtime_mode
                            AND hr.runtime_epoch = r.runtime_epoch
                      )
                    """.formatted(TABLES.run(), TABLES.fixtureDependencyManifest(), TABLES.payload(),
                    TABLES.payload(), TABLES.run()), Integer.class, SEASON.code(), fence.mode().name(), fence.epoch());
            assertThat(missingHistories).as("every staged fixture has complete post-anchor history evidence").isZero();
        }

        private RosterBaselineMetrics rosterBaselineMetrics(ManualDriver driver) {
            String fixturePlaceholders = String.join(",", seededFixtureRuns.stream()
                    .map(ignored -> "?").toList());
            Object[] fixtureGenerations = seededFixtureRuns.stream()
                    .map(SeededFixture::generation).toArray();
            List<Map.Entry<Integer, Instant>> staged = jdbc.query("SELECT substring(publication_key from 9)::int, "
                            + "completed_at FROM " + TABLES.run() + " WHERE generation IN ("
                            + fixturePlaceholders + ") AND kind='FIXTURE_VERIFICATION' AND state='COMPLETED' "
                            + "ORDER BY completed_at,generation LIMIT 1",
                    (rs, row) -> Map.entry(rs.getInt(1), rs.getTimestamp(2).toInstant()), fixtureGenerations);
            Instant firstStageAt = staged.isEmpty() ? null : staged.getFirst().getValue();
            Integer firstStageFixture = staged.isEmpty() ? null : staged.getFirst().getKey();
            int admissions = durableAdmissionCount();
            Integer admissionsAtFirstStage = firstStageAt == null ? null : jdbc.queryForObject(
                    "SELECT count(*) FROM " + TABLES.requestAttempt() + " WHERE admitted_at<=?",
                    Integer.class, Timestamp.from(firstStageAt));
            Map<Long, FixtureManifestCheck> manifestChecks = fixtureManifestChecks();
            Set<Integer> manifestedPlayers = new HashSet<>();
            int manifests = 0;
            int validManifests = 0;
            int manifestEntries = 0;
            int minimumManifestPlayers = Integer.MAX_VALUE;
            int maximumManifestPlayers = 0;
            int manifestMismatches = 0;
            for (SeededFixture fixture : seededFixtureRuns) {
                FixtureManifestCheck check = manifestChecks.get(fixture.generation());
                if (check == null || check.manifestPlayers() == null) continue;
                manifests++;
                Set<Integer> manifestPlayers = check.manifestPlayers();
                manifestEntries += manifestPlayers.size();
                manifestedPlayers.addAll(manifestPlayers);
                minimumManifestPlayers = Math.min(minimumManifestPlayers, manifestPlayers.size());
                maximumManifestPlayers = Math.max(maximumManifestPlayers, manifestPlayers.size());
                boolean expected = requiredHistoryPlayersByFixture.get(fixture.fixtureId()).equals(manifestPlayers);
                boolean realistic = manifestPlayers.size() >= 50 && manifestPlayers.size() <= 86;
                if (!check.checked() || !expected || !realistic) manifestMismatches++;
                else validManifests++;
            }
            if (manifests == 0) minimumManifestPlayers = 0;
            int sourceFloorMismatches = jdbc.queryForObject("SELECT count(*) FROM "
                            + TABLES.fixtureDependencyManifest() + " m JOIN " + TABLES.run()
                            + " r ON r.generation=m.run_generation WHERE m.run_generation IN ("
                            + fixturePlaceholders + ") AND m.source_floor IS DISTINCT FROM r.requested_source_after",
                    Integer.class, fixtureGenerations);
            int epochMismatches = jdbc.queryForObject("SELECT count(*) FROM "
                            + TABLES.fixtureDependencyManifest() + " m JOIN " + TABLES.run()
                            + " r ON r.generation=m.run_generation WHERE m.run_generation IN ("
                            + fixturePlaceholders + ") AND (r.runtime_mode<>? OR r.runtime_epoch<>?)",
                    Integer.class, java.util.stream.Stream.concat(java.util.Arrays.stream(fixtureGenerations),
                            java.util.stream.Stream.of(fence.mode().name(), fence.epoch())).toArray());
            int historyRunsCompleted = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                    + " WHERE season=? AND kind='PLAYER_HISTORY' AND state='COMPLETED'",
                    Integer.class, SEASON.code());
            int historyRunsPending = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                    + " WHERE season=? AND kind='PLAYER_HISTORY' AND state='PENDING'",
                    Integer.class, SEASON.code());
            int historyJobsNotYetClaimed = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                    + " WHERE season=? AND kind='PLAYER_HISTORY' AND state='PENDING' AND attempt_count=0",
                    Integer.class, SEASON.code());
            int historyRequestsAdvancedPastPreAnchor = jdbc.queryForObject("SELECT count(DISTINCT publication_key) "
                            + "FROM " + TABLES.run() + " WHERE season=? AND kind='PLAYER_HISTORY' "
                            + "AND requested_source_after>?",
                    Integer.class, SEASON.code(), Timestamp.from(START));
            int preAnchorHistories = jdbc.queryForObject("SELECT count(DISTINCT p.player_id) FROM "
                            + TABLES.payload() + " p JOIN " + TABLES.run()
                            + " r ON r.generation=p.run_generation WHERE r.season=?"
                            + " AND r.kind='PLAYER_HISTORY' AND p.endpoint_kind='ELEMENT_SUMMARY'"
                            + " AND p.validation_state='VALID' AND p.read_started_at<=?",
                    Integer.class, SEASON.code(), Timestamp.from(START));
            int fixturesCompleted = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                    + " WHERE season=? AND kind='FIXTURE_VERIFICATION' AND state='COMPLETED'",
                    Integer.class, SEASON.code());
            int untouchedFixtures = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                    + " WHERE season=? AND kind='FIXTURE_VERIFICATION' AND attempt_count=0",
                    Integer.class, SEASON.code());
            int failedRuns = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                    + " WHERE season=? AND state='FAILED'", Integer.class, SEASON.code());
            int invalidPayloads = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                    + " p JOIN " + TABLES.run() + " r ON r.generation=p.run_generation"
                    + " WHERE r.season=? AND p.validation_state='INVALID'", Integer.class, SEASON.code());
            int uncertainAttempts = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt()
                    + " WHERE hold_until_expiry", Integer.class);
            int unresolvedAttempts = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt()
                    + " WHERE completed_at IS NULL", Integer.class);
            Map<Integer, Integer> historyGets = server.historyGetsByPlayer();
            return new RosterBaselineMetrics(driver.stopReason, firstStageAt, firstStageFixture,
                    admissionsAtFirstStage, admissions, driver.dispatches, driver.completions.size(),
                    server.getsByEndpoint(), server.returnedGets(), server.returnedRawBytes(), payloadReads.snapshot(),
                    preAnchorHistories, historyRunsCompleted, historyRunsPending, historyJobsNotYetClaimed,
                    historyRequestsAdvancedPastPreAnchor,
                    historyGets.values().stream().mapToInt(count -> Math.max(0, count - 1)).sum(),
                    (int) historyGets.values().stream().filter(count -> count > 1).count(), fixturesCompleted,
                    untouchedFixtures, store.readyFixtureGenerations(SEASON.code(), FIXTURE_RUNS).size(),
                    manifests, validManifests, minimumManifestPlayers, maximumManifestPlayers,
                    manifestEntries - manifestedPlayers.size(), manifestedPlayers.size(),
                    Math.max(0, source.playerIds().size() - manifestedPlayers.size()), manifestMismatches,
                    sourceFloorMismatches, epochMismatches, failedRuns, invalidPayloads, uncertainAttempts,
                    unresolvedAttempts, Duration.between(START, clock.instant()).toNanos() / 1e9,
                    driver.workWallSeconds());
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

        private Map<Long, FixtureManifestCheck> fixtureManifestChecks() {
            String placeholders = String.join(",", seededFixtureRuns.stream().map(ignored -> "?").toList());
            String sql = """
                    SELECT r.generation, m.required_player_ids,
                           (r.season = ? AND r.runtime_mode = ? AND r.runtime_epoch = ?
                            AND m.run_generation IS NOT NULL
                            AND fpl_sync_required_ids_valid(m.required_player_ids)
                            AND m.source_floor = r.requested_source_after
                            AND b.id IS NOT NULL AND f.id IS NOT NULL AND l.id IS NOT NULL
                            AND b.read_started_at >= r.requested_source_after
                            AND f.read_started_at >= r.requested_source_after
                            AND l.read_started_at >= GREATEST(
                                r.requested_source_after,
                                f.read_finished_at + INTERVAL '1 millisecond')) AS checked
                    FROM %s r
                    LEFT JOIN %s m ON m.run_generation = r.generation
                    LEFT JOIN %s b ON b.id = m.bootstrap_payload_id
                               AND b.run_generation = r.generation
                               AND b.endpoint_kind = 'BOOTSTRAP_STATIC'
                               AND b.event_id IS NULL AND b.player_id IS NULL
                               AND b.validation_state = 'VALID'
                    LEFT JOIN %s f ON f.id = m.fixtures_payload_id
                               AND f.run_generation = r.generation
                               AND f.endpoint_kind = 'FIXTURES'
                               AND f.event_id IS NULL AND f.player_id IS NULL
                               AND f.validation_state = 'VALID'
                    LEFT JOIN %s l ON l.id = m.live_payload_id
                               AND l.run_generation = r.generation
                               AND l.endpoint_kind = 'EVENT_LIVE'
                               AND l.event_id > 0 AND l.player_id IS NULL
                               AND l.validation_state = 'VALID'
                    WHERE r.generation IN (%s)
                      AND r.kind = 'FIXTURE_VERIFICATION'
                    """.formatted(TABLES.run(), TABLES.fixtureDependencyManifest(), TABLES.payload(),
                    TABLES.payload(), TABLES.payload(), placeholders);
            Object[] args = java.util.stream.Stream.concat(
                    java.util.stream.Stream.of(SEASON.code(), fence.mode().name(), fence.epoch()),
                    seededFixtureRuns.stream().map(SeededFixture::generation))
                    .toArray();
            return jdbc.query(sql, rs -> {
                Map<Long, FixtureManifestCheck> result = new HashMap<>();
                while (rs.next()) {
                    java.sql.Array array = rs.getArray("required_player_ids");
                    Set<Integer> players = array == null ? null
                            : new HashSet<>(java.util.Arrays.asList((Integer[]) array.getArray()));
                    result.put(rs.getLong("generation"),
                            new FixtureManifestCheck(players, rs.getBoolean("checked")));
                }
                return result;
            }, args);
        }

        private FiniteDrainMetrics finiteDrainMetrics(ManualDriver driver) {
            int completedFixtures = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                    + " WHERE season=? AND kind='FIXTURE_VERIFICATION' AND state='COMPLETED'",
                    Integer.class, SEASON.code());
            int completedHistoryRuns = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                    + " WHERE season=? AND kind='PLAYER_HISTORY' AND state='COMPLETED'",
                    Integer.class, SEASON.code());
            int failedRuns = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                    + " WHERE season=? AND state='FAILED'", Integer.class, SEASON.code());
            int unfinishedFixtureRuns = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                    + " WHERE season=? AND kind='FIXTURE_VERIFICATION' AND state IN ('PENDING','LEASED')",
                    Integer.class, SEASON.code());
            int unfinishedHistoryRuns = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                    + " WHERE season=? AND kind='PLAYER_HISTORY' AND state IN ('PENDING','LEASED')",
                    Integer.class, SEASON.code());

            Set<Integer> requiredPlayers = requiredHistoryPlayersByFixture.values().stream()
                    .flatMap(Set::stream).collect(java.util.stream.Collectors.toUnmodifiableSet());
            String generationPlaceholders = String.join(",", seededFixtureRuns.stream()
                    .map(ignored -> "?").toList());
            String evidenceSql = """
                    SELECT r.generation, required.player_id,
                           EXISTS (
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
                                 AND hp.staged_at <= r.completed_at
                                 AND hr.kind = 'PLAYER_HISTORY'
                                 AND hr.coalescing_key = 'player-history:' || required.player_id::text
                                 AND hr.publication_key = 'player-history:' || required.player_id::text
                                 AND hr.state = 'COMPLETED'
                                 AND hr.completed_at IS NOT NULL
                                 AND hr.season = r.season
                                 AND hr.runtime_mode = r.runtime_mode
                                 AND hr.runtime_epoch = r.runtime_epoch
                           ) AS history_eligible
                    FROM %s r
                    JOIN %s m ON m.run_generation = r.generation
                    JOIN %s b ON b.id = m.bootstrap_payload_id
                               AND b.run_generation = r.generation
                               AND b.endpoint_kind = 'BOOTSTRAP_STATIC'
                               AND b.event_id IS NULL AND b.player_id IS NULL
                               AND b.validation_state = 'VALID'
                    JOIN %s f ON f.id = m.fixtures_payload_id
                               AND f.run_generation = r.generation
                               AND f.endpoint_kind = 'FIXTURES'
                               AND f.event_id IS NULL AND f.player_id IS NULL
                               AND f.validation_state = 'VALID'
                    JOIN %s l ON l.id = m.live_payload_id
                               AND l.run_generation = r.generation
                               AND l.endpoint_kind = 'EVENT_LIVE'
                               AND l.event_id > 0 AND l.player_id IS NULL
                               AND l.validation_state = 'VALID'
                    CROSS JOIN LATERAL unnest(m.required_player_ids) AS required(player_id)
                    WHERE r.generation IN (%s)
                      AND fpl_sync_required_ids_valid(m.required_player_ids)
                      AND r.season = ?
                      AND r.runtime_mode = ?
                      AND r.runtime_epoch = ?
                      AND r.kind = 'FIXTURE_VERIFICATION'
                      AND r.state = 'COMPLETED'
                      AND r.completed_at IS NOT NULL
                      AND b.read_started_at >= r.requested_source_after
                      AND f.read_started_at >= r.requested_source_after
                      AND l.read_started_at >= GREATEST(
                          r.requested_source_after,
                          f.read_finished_at + INTERVAL '1 millisecond')
                    ORDER BY r.generation, required.player_id
                    """.formatted(TABLES.payload(), TABLES.run(), TABLES.run(),
                    TABLES.fixtureDependencyManifest(), TABLES.payload(), TABLES.payload(), TABLES.payload(),
                    generationPlaceholders);
            Object[] evidenceArgs = java.util.stream.Stream.concat(
                    seededFixtureRuns.stream().map(fixture -> (Object) fixture.generation()),
                    java.util.stream.Stream.of(SEASON.code(), fence.mode().name(), fence.epoch()))
                    .toArray();
            List<FixturePlayerEvidence> fixtureEvidence = jdbc.query(evidenceSql, (rs, row) ->
                    new FixturePlayerEvidence(rs.getLong("generation"), rs.getInt("player_id"),
                            rs.getBoolean("history_eligible")), evidenceArgs);
            Set<FixturePlayer> eligibleHistoryPairs = new HashSet<>();
            for (FixturePlayerEvidence evidence : fixtureEvidence) {
                if (evidence.historyEligible()) {
                    eligibleHistoryPairs.add(new FixturePlayer(
                            evidence.fixtureGeneration(), evidence.playerId()));
                }
            }
            Map<Long, FixtureManifestCheck> manifestChecks = fixtureManifestChecks();
            int fixtureManifestsChecked = 0;
            int fixtureManifestMismatches = 0;
            for (SeededFixture fixture : seededFixtureRuns) {
                Set<Integer> expected = requiredHistoryPlayersByFixture.get(fixture.fixtureId());
                FixtureManifestCheck check = manifestChecks.get(fixture.generation());
                if (check != null && check.checked()) fixtureManifestsChecked++;
                if (expected == null || check == null || !check.checked()
                        || !expected.equals(check.manifestPlayers())) {
                    fixtureManifestMismatches++;
                }
            }
            int completedRequiredHistoryPlayers = (int) requiredPlayers.stream()
                    .filter(playerId -> seededFixtureRuns.stream()
                            .filter(fixture -> requiredHistoryPlayersByFixture.get(fixture.fixtureId())
                                    .contains(playerId))
                            .allMatch(fixture -> eligibleHistoryPairs.contains(
                                    new FixturePlayer(fixture.generation(), playerId))))
                    .count();

            Map<Integer, Integer> historyGetsByPlayer = server.historyGetsByPlayer();
            int repeatedHistoryGets = historyGetsByPlayer.values().stream().mapToInt(count -> Math.max(0, count - 1)).sum();
            int playersWithRepeatedHistoryGets = (int) historyGetsByPlayer.values().stream()
                    .filter(count -> count > 1).count();
            int payloadRows = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                    + " p JOIN " + TABLES.run() + " r ON r.generation=p.run_generation"
                    + " WHERE r.season=?", Integer.class, SEASON.code());
            long stagedBytes = jdbc.queryForObject("SELECT COALESCE(sum(octet_length(p.body)),0) FROM "
                    + TABLES.payload() + " p JOIN " + TABLES.run()
                    + " r ON r.generation=p.run_generation WHERE r.season=?", Long.class, SEASON.code());
            int seededFixtureRunsCompleted = (int) seededFixtureRuns.stream().filter(fixture ->
                    jdbc.queryForObject("SELECT state='COMPLETED' FROM " + TABLES.run()
                            + " WHERE generation=?", Boolean.class, fixture.generation())).count();
            return new FiniteDrainMetrics(driver.stopReason, seededFixtureRuns.size(),
                    seededFixtureRunsCompleted, completedFixtures, requiredPlayers.size(),
                    completedRequiredHistoryPlayers, fixtureManifestsChecked, fixtureManifestMismatches,
                    seededHistoryPlayerIds.size(), seededHistoryPlayerIds.size() - HISTORY_RUNS,
                    completedHistoryRuns, repeatedHistoryGets, playersWithRepeatedHistoryGets, failedRuns,
                    unfinishedFixtureRuns, unfinishedHistoryRuns, durableAdmissionCount(),
                    server.getsByEndpoint(), server.returnedGets(), server.returnedRawBytes(), payloadRows,
                    stagedBytes, newOutstandingPartialPairs(), driver.peakNewPartialPairs, driver.dispatches,
                    driver.completions.size(), driver.claims, driver.deferrals, driver.predecessorWaits.get(),
                    driver.spacingWaits.get(), Duration.between(START, clock.instant()).toNanos() / 1e9,
                    driver.workWallSeconds());
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
        private final int readySeedAttemptCount;
        private final int untouchedSeedAttemptCount;
        private ClaimEvidence readyFirstClaim;
        private ClaimEvidence untouchedFirstClaim;
        private final Instant startAt;
        private final Instant stopAt;
        private final Instant[] nextAt;
        private Instant plannerNextDue;
        private final Map<Integer, DispatchState> active = new LinkedHashMap<>();
        private final List<DispatchCompletion> completions = new ArrayList<>();
        private final List<AdmissionTrace> admissions = new ArrayList<>();
        private final Map<Integer, FirstPendingObservation> firstPendingObservations = new TreeMap<>();
        private final Map<Integer, List<String>> targetTransitions = new TreeMap<>();
        private final Map<Thread, DispatchState> dispatchByThread = new ConcurrentHashMap<>();
        private Set<Long> lastReadyTargetGenerations = Set.of();
        private String lastNextWake = "none";
        private int advancesWithReadyTargets;
        private final List<ControlledResponse> responses = new ArrayList<>();
        private final List<RequestArrival> unassignedArrivals = new ArrayList<>();
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
            this(harness, 0, 0, true, false, virtualLimit, admissionLimit,
                    dispatchStartLimit, goal, claimLimit);
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
            this.startAt = harness.clock.instant();
            this.stopAt = startAt.plus(virtualLimit);
            this.plannerNextDue = goal == null ? null : harness.coordinator.tick().nextDueAt();
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
            captureFirstPendingObservations();

            int secondWorker = 3 - harness.firstWorker;
            if (canStartDispatch() && !nextAt[secondWorker - 1].isAfter(harness.clock.instant())) {
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
                refreshPlannerIfIdle();
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

                if (harness.clock.instant().isBefore(stopAt) && !focusSatisfied()
                        && !softWallCutoffReached()) startReadyWorkers();

                if (active.isEmpty() && focusSatisfied()) break;
                if (active.isEmpty() && !hasActiveRuns() && plannerNextDue == null) break;
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
                if (next.at().isAfter(harness.clock.instant())) advanceTo(next.at(), next.kind().name());
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

        private double workWallSeconds() {
            if (workStoppedAtNanos < 0) throw new IllegalStateException("finite work stop was not recorded");
            return (workStoppedAtNanos - harness.wallStartedAtNanos) / 1_000_000_000.0;
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
                if (candidates.size() != 1) continue;
                DispatchState owner = candidates.getFirst();
                ControlledResponse response = new ControlledResponse(owner, arrival,
                        arrival.at().plus(RESPONSE_TIME));
                owner.pendingResponse = response;
                responses.add(response);
                long generation = focusedGeneration(owner);
                Map<String, Object> run = jdbc.queryForMap("SELECT kind,publication_key,attempt_count,last_attempt_at "
                        + "FROM " + TABLES.run() + " WHERE generation=?", generation);
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
                    response.owner.pendingResponse = null;
                    response.arrival.release().countDown();
                    responses.remove(response);
                }
            }
        }

        private void collectCompleted() throws Exception {
            for (DispatchState dispatch : List.copyOf(active.values())) {
                if (!dispatch.future.isDone()) continue;
                DispatchCompletion result = dispatch.future.get();
                if (dispatch.pendingResponse != null) {
                    throw new IllegalStateException("dispatch completed before its controlled response release: worker="
                            + dispatch.worker + " endpoint=" + dispatch.pendingResponse.arrival.kind()
                            + " releaseAt=" + dispatch.pendingResponse.releaseAt
                            + " now=" + harness.clock.instant());
                }
                if (result.claimed()) claims++;
                if (goal != null) {
                    for (long generation : dispatch.targetGenerations) {
                        recordTargetTransition(dispatch, generation, result);
                    }
                }
                completions.add(result);
                nextAt[result.worker() - 1] = result.completedAt().plus(WORKER_DELAY);
                active.remove(result.worker());
            }
        }

        private void startReadyWorkers() {
            while (canStartDispatch() && hasDueActiveRuns()) {
                sampleAcceptanceQueueClasses();
                int worker = readyWorker();
                if (worker == 0) return;
                start(worker);
                nextTieWorker = 3 - worker;
            }
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
            String owner = "admission-repro:" + dispatch.worker;
            List<Long> leased = jdbc.queryForList("SELECT generation FROM " + TABLES.run()
                    + " WHERE state='LEASED' AND lease_owner=?", Long.class, owner);
            if (leased.size() == 1) return leased.getFirst();
            for (long generation : List.of(readyGeneration, untouchedGeneration)) {
                if (generation > 0 && jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + TABLES.run()
                        + " WHERE generation=? AND state='LEASED' AND lease_owner=?)", Boolean.class,
                        generation, owner)) return generation;
            }
            return 0;
        }

        private boolean focusSatisfied() {
            if (goal != null) return goalSatisfied();
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
            if (goal == null || goal.fixtureIds().isEmpty()) return;
            String keys = goal.fixtureIds().stream().sorted().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(","));
            jdbc.query("SELECT fs.fixture_id,fs.observation_generation,fs.source_read_started_at,"
                            + "fs.source_read_finished_at,fs.validation_failures::text validation_failures FROM "
                            + TABLES.fixtureSync() + " fs JOIN "
                            + TABLES.run() + " r ON r.generation=fs.observation_generation"
                            + " WHERE fs.season_id=? AND fs.fixture_id IN (" + keys + ")"
                            + " AND fs.current_verified_generation IS NULL"
                            + " AND fs.validation_failures @> ARRAY['NEEDS_SECOND_OBSERVATION']::text[]"
                            + " AND fs.observation->>'coherent'='true' AND r.kind='FIXTURE_VERIFICATION'"
                            + " AND r.state='COMPLETED' AND r.runtime_mode=? AND r.runtime_epoch=?",
                    (rs, row) -> firstPendingObservations.putIfAbsent(rs.getInt("fixture_id"),
                            new FirstPendingObservation(rs.getLong("observation_generation"),
                                    new SourceRead(rs.getTimestamp("source_read_started_at").toInstant(),
                                            rs.getTimestamp("source_read_finished_at").toInstant()),
                                    rs.getString("validation_failures"))),
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
            if (!focusSatisfied() && plannerNextDue != null && plannerNextDue.isAfter(now)) {
                next = earlier(next, new Event(plannerNextDue, EventKind.PLANNER_TICK));
            }
            if (!focusSatisfied() && canStartDispatch() && hasDueActiveRuns()) {
                for (int worker : List.of(1, 2)) {
                    if (!active.containsKey(worker)) {
                        next = earlier(next, new Event(nextAt[worker - 1], EventKind.WORKER_START));
                    }
                }
            } else if (!focusSatisfied() && active.isEmpty()) {
                Instant runDue = earliestActiveRunDue();
                if (runDue != null && runDue.isAfter(now)) {
                    next = earlier(next, new Event(runDue, EventKind.PLANNER_TICK));
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
            active.clear();
            responses.clear();
            unassignedArrivals.clear();
        }

        private void samplePartialPairs() {
            peakNewPartialPairs = Math.max(peakNewPartialPairs, harness.newOutstandingPartialPairs());
        }

        private boolean goalSatisfied() {
            if (goal.pendingOnly()) {
                if (harness.clock.instant().isBefore(LEGACY_DAILY_DUE) || hasDueCanonicalRuns()
                        || !harness.store.currentCanonicalParentEvidence(SEASON.code(),
                        goal.fixtureIds().iterator().next(), harness.clock.instant()).currentPublished()) return false;
                return pendingFixtureRuns(harness.fence.epoch(), goal.fixtureIds()) == goal.fixtureIds().size();
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

        private void refreshPlannerIfIdle() {
            if (goal == null || focusSatisfied()) return;
            Instant now = harness.clock.instant();
            if (plannerNextDue != null && plannerNextDue.isAfter(now)) return;
            if (plannerNextDue == null && (!active.isEmpty() || hasDueActiveRuns())) return;
            OwnedMatchdayCoordinator.TickResult tick = harness.coordinator.tick();
            plannerNextDue = tick.nextDueAt();
        }

        private void setToScheduledStart(int worker) {
            Instant due = nextAt[worker - 1];
            if (due.isAfter(harness.clock.instant())) advanceTo(due, "worker-start");
        }

        private void advanceTo(Instant instant, String reason) {
            Instant current = harness.clock.instant();
            if (!instant.isAfter(current)) return;
            if (!activeStatesAcknowledged()) {
                throw new IllegalStateException("driver cannot advance without an acknowledged wait, "
                        + "controlled response release or completed dispatch for every active worker");
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
                    dispatchByThread.remove(current, dispatch);
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

        private enum EventKind { RESPONSE, SPACING, WORKER_START, PLANNER_TICK }

        private record Event(Instant at, EventKind kind) { }

        private record WaitState(WaitKind kind, Instant nextDue, long token) { }

        private static final class ControlledResponse {
            private final DispatchState owner;
            private final RequestArrival arrival;
            private final Instant releaseAt;
            ControlledResponse(DispatchState owner, RequestArrival arrival, Instant releaseAt) {
                this.owner = owner;
                this.arrival = arrival;
                this.releaseAt = releaseAt;
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

            DispatchState(int worker) {
                this.worker = worker;
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

        Map<Integer, Integer> historyGetsByPlayer() {
            Map<Integer, Integer> result = new TreeMap<>();
            observed.stream().filter(arrival -> arrival.kind() == EndpointKind.ELEMENT_SUMMARY)
                    .forEach(arrival -> {
                        int playerId = Integer.parseInt(arrival.path().substring(
                                "/api/element-summary/".length(), arrival.path().length() - 1));
                        result.merge(playerId, 1, Integer::sum);
                    });
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

        static SourceData loadFullRoster() throws Exception {
            return load(true);
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
        int teamCount() throws IOException {
            return JSON.readTree(bootstrap).withArray("teams").size();
        }
        int finishedFixtureCount() throws IOException {
            int count = 0;
            for (JsonNode fixture : JSON.readTree(fixtures)) {
                if (fixture.path("finished").asBoolean()) count++;
            }
            return count;
        }

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
