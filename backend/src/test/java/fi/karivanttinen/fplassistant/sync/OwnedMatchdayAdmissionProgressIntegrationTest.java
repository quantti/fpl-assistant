package fi.karivanttinen.fplassistant.sync;

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

import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.Season;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.EndpointKind;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Lease;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.RuntimeFence;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.SourceRead;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.SyncKind;
import fi.karivanttinen.fplassistant.sync.OwnedSyncHttpClient.WaitKind;
import fi.karivanttinen.fplassistant.sync.OwnedSyncHttpClient.WaitObservation;
import fi.karivanttinen.fplassistant.sync.OwnedSyncHttpClient.WaitPhase;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Validation;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.ValidationState;
import fi.karivanttinen.fplassistant.testsupport.TapasSeed;

/** Failing baseline for real two-worker HTTP admission under a fixture-first backlog. */
class OwnedMatchdayAdmissionProgressIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OwnedSyncTables TABLES = OwnedSyncTables.tests();
    private static final CatalogueScheduleTables CAPTURED = new CatalogueScheduleTables(
            "fpl_admission_daily_price_test", "fpl_admission_gameweek_test", "fpl_admission_fixture_test");
    private static final Season SEASON = new Season(27, "2026-27", LocalDate.of(2026, 8, 21), null);
    private static final Instant START = Instant.parse("2026-10-01T12:00:00Z");
    private static final Duration RESPONSE_TIME = Duration.ofMillis(100);
    private static final Duration WORKER_DELAY = Duration.ofSeconds(1);
    private static final Duration VIRTUAL_LIMIT = Duration.ofSeconds(60);
    private static final Duration DRAIN_VIRTUAL_LIMIT = Duration.ofHours(2);
    private static final Duration ROSTER_BASELINE_VIRTUAL_LIMIT = Duration.ofSeconds(400);
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

    private record ColdParentEvidence(int pendingParents, boolean historyDeferred,
                                      boolean canonicalParentCompleted, boolean historyCompleted,
                                      boolean historyReusedCanonicalPair,
                                      Map<EndpointKind, Integer> getsByEndpoint) { }

    private record DispatchCompletion(int worker, boolean claimed, Instant completedAt) { }

    private record AdmissionTrace(int ordinal, long generation, int worker,
                                  EndpointKind endpoint, String path, Instant admittedAt) { }

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
        private volatile String finiteStopReason;
        private final MutableClock clock = new MutableClock(START);
        private final RuntimeFence fence;
        private final PlannerRuntimeRepository activation;
        private final OwnedSyncStore store;
        private final PayloadReadProbe payloadReads;
        private final OwnedSyncRequestBudget budget;
        private final OwnedSyncDbOperations dbOperations;
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
            this.firstWorker = firstWorker;
            this.wallDeadline = wallDeadline;
            this.wallStartedAtNanos = wallStartedAtNanos;
            this.finiteDrain = finiteDrain;
            this.source = source;
            this.cleanupDeadline = wallDeadline + (finiteDrain ? DRAIN_CLEANUP_RESERVE_NANOS : 0);
            clearOwnedRows();
            activation = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES);
            fence = activation.activate(SEASON.code(), OwnedSyncMode.STAGING);
            payloadReads = new PayloadReadProbe(jdbc.getDataSource());
            payloadReads.setQueryTimeout(10);
            store = new OwnedSyncStore(payloadReads, tx, clock, TABLES, fence);
            budget = new OwnedSyncRequestBudget(jdbc, tx, clock, TABLES);
            dbOperations = new OwnedSyncDbOperations();
            server = new RequestServer(source, clock);
            http = new OwnedSyncHttpClient(budget, server.baseUri(), Duration.ofSeconds(30), clock,
                    dbOperations, Duration.ofSeconds(180), turnNanoTime::get);
            var catalogue = CatalogueScheduleDispatcher.staging(store, http, clock);
            var histories = HistoryFixtureDispatcher.staging(store, http, clock);
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
            BlockingQueue<WaitObservation> waitObservations = new LinkedBlockingQueue<>();
            http.setWaitObserverForTests(waitObservations::add);
            Future<Boolean> future = dispatchWorkers.submit(() -> coordinator.dispatchOne(workerId));
            List<ScheduledResponse> responses = new ArrayList<>();
            Map<Long, WaitObservation> parked = new HashMap<>();
            Set<Long> spacingWoken = new HashSet<>();
            while (!future.isDone()) {
                RequestArrival arrival;
                while ((arrival = server.pollArrival(0, TimeUnit.MILLISECONDS)) != null) {
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
            return future.get(remainingWallNanos(), TimeUnit.NANOSECONDS) ? 1 : 0;
        }

        private void advanceControlledTime(Instant instant) {
            Instant current = clock.instant();
            if (instant.isAfter(current)) {
                turnNanoTime.addAndGet(Duration.between(current, instant).toNanos());
                clock.set(instant);
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
                    if (workerClean && dbWorkerClean && watchdogClean) clearOwnedRows();
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
        private final int dispatchStartLimit;
        private final int readySeedAttemptCount;
        private final int untouchedSeedAttemptCount;
        private ClaimEvidence readyFirstClaim;
        private ClaimEvidence untouchedFirstClaim;
        private final Instant startAt;
        private final Instant stopAt;
        private final Instant[] nextAt;
        private final Map<Integer, DispatchState> active = new LinkedHashMap<>();
        private final List<DispatchCompletion> completions = new ArrayList<>();
        private final List<AdmissionTrace> admissions = new ArrayList<>();
        private final Map<Thread, DispatchState> dispatchByThread = new ConcurrentHashMap<>();
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
            this(harness, 0, 0, true, true, virtualLimit, admissionLimit, dispatchStartLimit);
        }

        private ManualDriver(Harness harness, long readyGeneration, long untouchedGeneration,
                             boolean finiteDrain) {
            this(harness, readyGeneration, untouchedGeneration, finiteDrain, false,
                    finiteDrain ? DRAIN_VIRTUAL_LIMIT : VIRTUAL_LIMIT,
                    DRAIN_ADMISSION_LIMIT, DRAIN_DISPATCH_LIMIT);
        }

        private ManualDriver(Harness harness, long readyGeneration, long untouchedGeneration,
                             boolean finiteDrain, boolean firstStageBaseline, Duration virtualLimit,
                             int admissionLimit, int dispatchStartLimit) {
            this.harness = harness;
            this.readyGeneration = readyGeneration;
            this.untouchedGeneration = untouchedGeneration;
            this.finiteDrain = finiteDrain;
            this.firstStageBaseline = firstStageBaseline;
            this.virtualLimit = virtualLimit;
            this.admissionLimit = admissionLimit;
            this.dispatchStartLimit = dispatchStartLimit;
            this.startAt = harness.clock.instant();
            this.stopAt = startAt.plus(virtualLimit);
            this.nextAt = new Instant[] { startAt, startAt };
            this.readySeedAttemptCount = readyGeneration > 0
                    ? harness.progress(readyGeneration).attemptCount() : 0;
            this.untouchedSeedAttemptCount = untouchedGeneration > 0
                    ? harness.progress(untouchedGeneration).attemptCount() : 0;
            nextTieWorker = harness.firstWorker;
        }

        void run() throws Exception {
            setToScheduledStart(harness.firstWorker);
            DispatchState first = start(harness.firstWorker);
            RequestArrival firstArrival = awaitFirstArrival(first);
            if (firstArrival == null) {
                stopReason = "WALL_LIMIT";
                recordWorkStop();
                harness.recordFiniteStopReason(stopReason);
                quiesceAtCutoff();
                harness.cancelWorkWatchdog();
                return;
            }
            unassignedArrivals.add(firstArrival);

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
                assignPendingResponses();
                sampleFirstClaimsAtAcknowledgedBoundary();
                samplePartialPairs();
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

                if (active.isEmpty() && (focusSatisfied() || !hasActiveRuns() || !canStartDispatch())) break;
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
                        advanceTo(stopAt);
                        continue;
                    }
                    if (finiteDrain) {
                        stopReason = "VIRTUAL_LIMIT";
                        recordWorkStop();
                    }
                    quiesceAtCutoff();
                    break;
                }
                if (next.at().isAfter(harness.clock.instant())) advanceTo(next.at());
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
            if (softWallCutoffReached()) return null;
            if (!harness.clock.instant().isBefore(stopAt)) return "VIRTUAL_LIMIT";
            int admissions = harness.durableAdmissionCount();
            if (wallCutoffReached()) return "WALL_LIMIT";
            if (firstStageBaseline) {
                if (admissions >= admissionLimit) return "ADMISSION_LIMIT";
                if (dispatches >= dispatchStartLimit) return "DISPATCH_LIMIT";
            } else {
                if (admissions >= DRAIN_ADMISSION_LIMIT) return "ADMISSION_LIMIT";
                if (completions.size() >= DRAIN_DISPATCH_LIMIT) return "DISPATCH_LIMIT";
            }
            return null;
        }

        private boolean canStartDispatch() {
            if (!finiteDrain) return dispatches < DISPATCH_LIMIT;
            if (wallCutoffReached() || softWallCutoffReached()
                    || (firstStageBaseline && focusSatisfied())) return false;
            int admissions = harness.durableAdmissionCount();
            if (firstStageBaseline) {
                return !wallCutoffReached() && dispatches < dispatchStartLimit
                        && admissions < admissionLimit && harness.clock.instant().isBefore(stopAt);
            }
            return !wallCutoffReached()
                    && dispatches < DRAIN_DISPATCH_LIMIT
                    && admissions < DRAIN_ADMISSION_LIMIT
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
                RequestArrival arrival = harness.server.pollArrival(pollTimeoutNanos(), TimeUnit.NANOSECONDS);
                if (arrival != null) return arrival;
                if (dispatch.future.isDone()) {
                    dispatch.future.get();
                    throw new AssertionError("first worker completed without a server-observed GET");
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
                admissions.add(new AdmissionTrace(++admissionOrdinal, focusedGeneration(owner), owner.worker,
                        arrival.kind(), arrival.path(), arrival.at()));
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
                completions.add(result);
                nextAt[result.worker() - 1] = result.completedAt().plus(WORKER_DELAY);
                active.remove(result.worker());
            }
        }

        private void startReadyWorkers() {
            while (canStartDispatch() && hasActiveRuns()) {
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
            for (long generation : List.of(readyGeneration, untouchedGeneration)) {
                if (generation > 0 && jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + TABLES.run()
                        + " WHERE generation=? AND state='LEASED' AND lease_owner=?)", Boolean.class,
                        generation, owner)) return generation;
            }
            return 0;
        }

        private boolean focusSatisfied() {
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
            if (!focusSatisfied() && canStartDispatch() && hasActiveRuns()) {
                for (int worker : List.of(1, 2)) {
                    if (!active.containsKey(worker)) {
                        next = earlier(next, new Event(nextAt[worker - 1], EventKind.WORKER_START));
                    }
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

        private boolean hasActiveRuns() {
            return jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                    + " WHERE season=? AND state IN ('PENDING','LEASED')", Integer.class, SEASON.code()) > 0;
        }

        private void setToScheduledStart(int worker) {
            Instant due = nextAt[worker - 1];
            if (due.isAfter(harness.clock.instant())) advanceTo(due);
        }

        private void advanceTo(Instant instant) {
            Instant current = harness.clock.instant();
            if (!instant.isAfter(current)) return;
            if (!activeStatesAcknowledged()) {
                throw new IllegalStateException("driver cannot advance without an acknowledged wait, "
                        + "controlled response release or completed dispatch for every active worker");
            }
            if (instant.isAfter(stopAt)) throw new IllegalStateException("virtual time ceiling exceeded");
            harness.clock.set(instant);
            harness.turnNanoTime.addAndGet(Duration.between(current, instant).toNanos());
            harness.http.clockAdvancedForTests();
        }

        private DispatchState start(int worker) {
            if (!canStartDispatch()) throw new IllegalStateException("dispatch ceiling exceeded");
            if (active.containsKey(worker)) throw new IllegalStateException("worker already has an active dispatch");
            DispatchState dispatch = new DispatchState(worker);
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

        private enum EventKind { RESPONSE, SPACING, WORKER_START }

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
        private final byte[] fixtures;
        private final List<Integer> playerIds;
        private final Map<Integer, byte[]> liveByEvent;
        private final Map<Integer, byte[]> histories;
        private final boolean fullRoster;

        private SourceData(byte[] bootstrap, byte[] fixtures, List<Integer> playerIds,
                           Map<Integer, byte[]> liveByEvent, Map<Integer, byte[]> histories,
                           boolean fullRoster) {
            this.bootstrap = bootstrap;
            this.fixtures = fixtures;
            this.playerIds = playerIds;
            this.liveByEvent = liveByEvent;
            this.histories = histories;
            this.fullRoster = fullRoster;
        }

        static SourceData load() throws Exception {
            return load(false);
        }

        static SourceData loadFullRoster() throws Exception {
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
            byte[] bootstrapBytes = JSON.writeValueAsBytes(bootstrap);
            byte[] fixtureBytes = JSON.writeValueAsBytes(fixtureRows);
            Map<Integer, byte[]> lives = createLiveManifests(fixtureRows, playersByTeam);
            Map<Integer, byte[]> histories = createHistories(fixtureRows, playerIds, playerTeams, playersByTeam);
            return new SourceData(bootstrapBytes, fixtureBytes, List.copyOf(playerIds),
                    Map.copyOf(lives), Map.copyOf(histories), fullRoster);
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
                                                              Map<Integer, List<Integer>> playersByTeam)
                throws IOException {
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
                            .put("recoveries", 0).put("tackles", 0).put("defensive_contribution", 0);
                }
                result.put(player, JSON.writeValueAsBytes(root));
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
        void set(Instant value) { current.set(value); }
        void advance(Duration duration) { current.updateAndGet(value -> value.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current.get(); }
    }
}
