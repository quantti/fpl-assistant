package dev.quantti.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import dev.quantti.fplassistant.defcon.DefensiveStatsSchema;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.Season;
import dev.quantti.fplassistant.sync.OwnedSyncHttpClient.Response;
import dev.quantti.fplassistant.sync.OwnedSyncRequestBudget.Allowed;
import dev.quantti.fplassistant.sync.OwnedSyncRequestBudget.Deferred;
import dev.quantti.fplassistant.sync.OwnedSyncRequestBudget.Limits;
import dev.quantti.fplassistant.sync.HistoryFixtureDispatcher.Outcome;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Endpoint;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Lease;
import dev.quantti.fplassistant.sync.OwnedSyncStore.RuntimeFence;
import dev.quantti.fplassistant.sync.OwnedSyncStore.SourceRead;
import dev.quantti.fplassistant.sync.OwnedSyncStore.SyncKind;
import dev.quantti.fplassistant.testsupport.SharedStatsSeed;
import dev.quantti.fplassistant.tools.PfsReadSource;

/** Task 10 regression: the real durable pipeline, with only the clock and FPL transport replaced. */
class OwnedMatchdayEndToEndIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OwnedSyncTables TABLES = OwnedSyncTables.tests();
    private static final CatalogueScheduleTables CAPTURED = CatalogueScheduleTables.tests();
    private static final Season SEASON = new Season(27, "2026-27", LocalDate.of(2026, 8, 21), null);
    private static final int DELAYED = 29;
    private static final int INDEPENDENT = 30;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;

    @BeforeAll
    static void initialize() {
        dataSource = new DriverManagerDataSource(required("DATABASE_URL"), required("DB_USER"), required("DB_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        SharedStatsSeed.ensure(jdbc);
        new DefensiveStatsSchema(jdbc).ensureColumns();
        new OwnedSyncSchema(jdbc, TABLES).initialize();
        createCapturedTables();
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    static void restoreSeed() {
        cleanSeason();
        jdbc.update("UPDATE season SET is_current=(id=?)", SharedStatsSeed.CURRENT_SEASON_ID);
    }

    @Test
    void fakeMultiMatchdaySurvivesPartialHistoryRestartAndCorrectionWithoutFalseCoverage() throws Exception {
        cleanSeason();
        jdbc.update("UPDATE season SET is_current=false");
        jdbc.update("INSERT INTO season(id,code,name,start_date,end_date,is_current) VALUES "
                + "(27,'2026-27','E2E season','2026-08-21',NULL,true)");
        seedLegacyRow();

        MutableClock clock = new MutableClock(Instant.parse("2026-09-06T17:00:00Z"));
        PlannerRuntimeRepository activator = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES);
        RuntimeFence stagingFence = activator.activate(SEASON.code(), OwnedSyncMode.STAGING);
        OwnedSyncStore staleStore = new OwnedSyncStore(jdbc, tx, clock, TABLES, stagingFence);
        assertThat(readRows("STAGING", 990029)).hasSize(1);
        staleStore.enqueue(SyncKind.FIXTURE_VERIFICATION, SEASON.code(), "fixture:29", "fixture:29", clock.instant());

        clock.advance(Duration.ofSeconds(1));
        RuntimeFence activeFence = activator.activate(SEASON.code(), OwnedSyncMode.ACTIVE);
        assertThatThrownBy(() -> staleStore.enqueue(SyncKind.FIXTURE_VERIFICATION, SEASON.code(),
                "stale", "stale", clock.instant())).isInstanceOf(OwnedSyncStore.RuntimeFenceLostException.class);
        assertThat(readRows("ACTIVE", 990029)).isEmpty();

        FakeFpl fpl = new FakeFpl(clock);
        Harness harness = harness(clock, activeFence, fpl);
        harness.catalogue.enqueueCycle(SEASON, clock.instant());
        harness.drainDue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player WHERE season_id=27", Integer.class))
                .withFailMessage("runs=%s", jdbc.queryForList("SELECT generation,publication_key,state,last_error FROM " + TABLES.run()))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT finished FROM fixture WHERE season_id=27 AND id=29", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT min(read_started_at)>=? FROM " + TABLES.payload(),
                Boolean.class, Timestamp.from(activeFence.sourceBoundary()))).isTrue();

        // The first fixture read is checkpointed before a 429. Rebuilding every runtime collaborator
        // proves the durable checkpoint is reused and cannot be labelled complete.
        int failedPlayer = fpl.playersByFixture.get(DELAYED).getLast();
        fpl.failPlayerOnce = failedPlayer;
        fpl.rateLimitNextFixtures = true;
        harness.histories.enqueueFixtureVerification(SEASON, DELAYED, clock.instant());
        harness.coordinator.dispatchOne("rate-limited");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                + " p JOIN " + TABLES.run() + " r ON r.generation=p.run_generation"
                + " WHERE r.publication_key='fixture:29' AND p.endpoint_kind='BOOTSTRAP_STATIC'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats WHERE season_id=27 AND fixture_id=29",
                Integer.class)).isZero();

        harness = harness(clock, activeFence, fpl); // process restart
        clock.advance(Duration.ofSeconds(31));
        harness.coordinator.dispatchOne("resumed-checkpoint");
        assertThat(fpl.fixtureCallsAfterRateLimit).isPositive();

        // One player history fails after its peers have completed. No partial PFS/evidence publication is visible.
        // Stop at the injected failure, not at an ordering-dependent end of the whole queue.
        for (int i = 0; i < 500 && fpl.failPlayerOnce != null; i++) {
            assertThat(harness.coordinator.dispatchOne("until-history-failure-" + i)).isTrue();
        }
        assertThat(fpl.failPlayerOnce).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats WHERE season_id=27 AND fixture_id=29",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.playerFixtureEvidence()
                + " WHERE fixture_id=29", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE publication_key=? AND state='PENDING'", Integer.class,
                HistoryFixtureDispatcher.playerHistoryPublicationKey(failedPlayer)))
                .withFailMessage("runs=%s", jdbc.queryForList("SELECT publication_key,state,last_error FROM " + TABLES.run()))
                .isOne();

        harness = harness(clock, activeFence, fpl); // second restart resumes the partial sweep
        clock.advance(Duration.ofMinutes(3));
        harness.drainDue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.fixtureSync()
                + " WHERE fixture_id=29 AND state='VERIFIED'", Integer.class)).isZero();

        // The other match publishes independently while fixture 29 still lacks a finished source.
        observeTwice(harness, fpl, INDEPENDENT, clock);
        assertVerified(INDEPENDENT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats WHERE season_id=27 AND fixture_id=30",
                Integer.class)).isEqualTo(2);

        clock.set(Instant.parse("2026-09-06T18:31:00Z"));
        List<String> delivered = new ArrayList<>();
        OwnedSyncWatchdog watchdog = new OwnedSyncWatchdog(jdbc, tx, clock, TABLES,
                new OwnedSyncWatchdog.Notification(true, true), delivered::add);
        watchdog.check(SEASON.code());
        watchdog.deliver();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.alert()
                + " WHERE fixture_id=29 AND event_type='INCIDENT'", Integer.class)).isPositive();
        assertThat(delivered).anySatisfy(message -> assertThat(message).contains("fixtureId", "29", "verification overdue"));

        // Delayed finish: publish the corrected schedule, then require two genuinely separate source batches.
        fpl.finished29 = true;
        publishSchedule(harness, clock);
        observeTwice(harness, fpl, DELAYED, clock);
        assertVerified(DELAYED);
        assertThat(lastTwoReads(DELAYED)).satisfies(reads -> {
            assertThat(reads.get(1).started()).isAfterOrEqualTo(
                    reads.get(0).finished().plus(Duration.ofMinutes(10)));
            assertThat(reads.get(0).started()).isAfterOrEqualTo(reads.get(0).sourceFloor());
            assertThat(reads.get(1).started()).isAfterOrEqualTo(reads.get(1).sourceFloor());
        });

        watchdog.check(SEASON.code());
        watchdog.deliver();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.alert()
                + " WHERE fixture_id=29 AND event_type='RECOVERY'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT payload::text FROM " + TABLES.alert()
                + " WHERE fixture_id=29 AND event_type='RECOVERY'", String.class))
                .contains("\"event\": \"recovery\"", "29");

        // A later score/stat correction revokes the receipt immediately. Two new stable observations
        // republish it; an omitted optional value masks the retained physical column at the reader boundary.
        long oldGeneration = currentGeneration(DELAYED);
        fpl.corrected29 = true;
        fpl.optionalUnknown = true;
        publishSchedule(harness, clock);
        assertThat(jdbc.queryForObject("SELECT current_verified_generation IS NULL FROM "
                + TABLES.fixtureSync() + " WHERE fixture_id=29", Boolean.class)).isTrue();
        assertThat(readRows("ACTIVE", DELAYED)).isEmpty();
        var invalidated = new PlannerInputRepository(jdbc, harness.store, TABLES, CAPTURED, clock)
                .load(SEASON).progress().stream().filter(progress -> progress.fixtureId() == DELAYED)
                .findFirst().orElseThrow();
        assertThat(invalidated.verifiedAt()).isNull();
        assertThat(invalidated.completedCorrections()).isEmpty();
        var invalidatedPlan = new AdaptiveMatchdayPlanner(clock).plan(new AdaptiveMatchdayPlanner.Input(
                SEASON.code(), 1, List.of(new AdaptiveMatchdayPlanner.Fixture(DELAYED, 1,
                clock.instant().minus(Duration.ofHours(1)), true, true, false, clock.instant(), Set.of(1))),
                List.of(invalidated), List.of()));
        assertThat(invalidatedPlan.dueSlots()).anySatisfy(slot -> {
            assertThat(slot.kind()).isEqualTo(AdaptiveMatchdayPlanner.WorkKind.FIXTURE_VERIFICATION);
            assertThat(slot.fixtureIds()).contains(DELAYED);
        });

        observeTwice(harness, fpl, DELAYED, clock);
        assertThat(currentGeneration(DELAYED)).isGreaterThan(oldGeneration);
        Map<String, Object> masked = readRows("ACTIVE", DELAYED).stream()
                .filter(row -> ((Number) row.get("player_id")).intValue() == fpl.playersByFixture.get(DELAYED).getFirst())
                .findFirst().orElseThrow();
        assertThat(masked.get("expected_goals")).isEqualTo(new BigDecimal("0.20"));
        assertThat(masked.get("expected_assists")).isNull();
        assertThat(((Number) masked.get("component_presence")).longValue()
                & HistoryParser.Component.EXPECTED_ASSISTS.bit()).isZero();
        assertThat(jdbc.queryForObject("SELECT expected_assists FROM player_fixture_stats WHERE season_id=27"
                + " AND fixture_id=29 AND player_id=?", BigDecimal.class,
                fpl.playersByFixture.get(DELAYED).getFirst())).isNotNull();
    }

    @Test
    void activeCorrectionPublicationSurvivesOffRestartWithoutReplay() throws Exception {
        cleanSeason();
        jdbc.update("UPDATE season SET is_current=false");
        jdbc.update("INSERT INTO season(id,code,name,start_date,end_date,is_current) VALUES "
                + "(27,'2026-27','Correction restart season','2026-08-21',NULL,true)");
        seedLegacyRow();

        MutableClock clock = new MutableClock(Instant.parse("2026-09-06T17:00:00Z"));
        PlannerRuntimeRepository activator = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES);
        RuntimeFence firstFence = activator.activate(SEASON.code(), OwnedSyncMode.ACTIVE);
        FakeFpl fpl = new FakeFpl(clock);
        Harness harness = harness(clock, firstFence, fpl);
        harness.catalogue.enqueueCycle(SEASON, clock.instant());
        harness.drainDue();
        observeTwice(harness, fpl, INDEPENDENT, clock);
        assertVerified(INDEPENDENT);
        long firstVerifiedGeneration = currentGeneration(INDEPENDENT);
        Instant correctionDue = jdbc.queryForObject("SELECT first_verified_at FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", Timestamp.class, SEASON.id(), INDEPENDENT)
                .toInstant().plus(Duration.ofHours(2));

        clock.set(correctionDue);
        harness.coordinator.tick();
        Long correctionGeneration = jdbc.queryForObject("SELECT generation FROM " + TABLES.run()
                + " WHERE season=? AND publication_key=? AND state='PENDING' ORDER BY generation DESC LIMIT 1",
                Long.class, SEASON.code(), "fixture:" + INDEPENDENT);
        assertThat(correctionGeneration).isNotNull();
        jdbc.update("UPDATE " + TABLES.run() + " SET due_at=? WHERE season=? AND state='PENDING' AND generation<>?",
                Timestamp.from(correctionDue.plus(Duration.ofDays(30))), SEASON.code(), correctionGeneration);
        observe(harness, INDEPENDENT);
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run()
                + " WHERE generation=?", String.class, correctionGeneration)).isEqualTo("COMPLETED");
        assertThat(currentGeneration(INDEPENDENT)).isGreaterThan(firstVerifiedGeneration);
        var receipt = jdbc.queryForMap("SELECT source_read_started_at,source_read_finished_at,published_at FROM "
                + TABLES.receipt() + " WHERE season=? AND publication_key=?",
                SEASON.code(), "fixture:" + INDEPENDENT);
        Instant sourceStarted = ((Timestamp) receipt.get("source_read_started_at")).toInstant();
        Instant sourceFinished = ((Timestamp) receipt.get("source_read_finished_at")).toInstant();
        Instant publishedAt = ((Timestamp) receipt.get("published_at")).toInstant();
        assertThat(sourceStarted).isAfterOrEqualTo(correctionDue);
        assertThat(sourceFinished).isAfterOrEqualTo(sourceStarted).isBeforeOrEqualTo(publishedAt);
        assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", Long.class,
                SEASON.code(), "fixture:" + INDEPENDENT)).isEqualTo(currentGeneration(INDEPENDENT));

        activator.deactivate(firstFence);
        RuntimeFence restartedFence = activator.activate(SEASON.code(), OwnedSyncMode.ACTIVE);
        Harness restarted = harness(clock, restartedFence, fpl);
        restarted.coordinator.tick();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE season=? AND publication_key=? AND state='PENDING'",
                Integer.class, SEASON.code(), "fixture:" + INDEPENDENT)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE generation=? AND state='COMPLETED' AND runtime_mode='ACTIVE'",
                Integer.class, correctionGeneration)).isOne();
        assertThat(restartedFence.epoch()).isGreaterThan(firstFence.epoch());
    }

    @Test
    void historyOnlyCorrectionSurvivesOffBeforeFixtureRunAndQueuesFreshVerification() throws Exception {
        cleanSeason();
        jdbc.update("UPDATE season SET is_current=false");
        jdbc.update("INSERT INTO season(id,code,name,start_date,end_date,is_current) VALUES "
                + "(27,'2026-27','History correction season','2026-08-21',NULL,true)");
        seedLegacyRow();

        MutableClock clock = new MutableClock(Instant.parse("2026-09-06T17:00:00Z"));
        PlannerRuntimeRepository activator = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES);
        RuntimeFence firstFence = activator.activate(SEASON.code(), OwnedSyncMode.ACTIVE);
        FakeFpl fpl = new FakeFpl(clock);
        Harness harness = harness(clock, firstFence, fpl);
        harness.catalogue.enqueueCycle(SEASON, clock.instant());
        harness.drainDue();
        new PlannerInputRepository(jdbc, harness.store, TABLES, CAPTURED, clock).load(SEASON);
        observeTwice(harness, fpl, INDEPENDENT, clock);
        assertVerified(INDEPENDENT);

        int historyPlayer = fpl.playersByFixture.get(INDEPENDENT).getFirst();
        long verifiedGeneration = currentGeneration(INDEPENDENT);
        Map<String, Object> fingerprintBefore = jdbc.queryForMap("SELECT fingerprint,source_read_finished_at,updated_at "
                + "FROM " + TABLES.historyFingerprint() + " WHERE season_id=? AND player_id=? AND fixture_id=?",
                SEASON.id(), historyPlayer, INDEPENDENT);
        Map<String, Object> fixtureDatesBeforeRollback = jdbc.queryForMap("SELECT first_verified_at,next_due_at,updated_at,"
                + "observation::text,observation_generation,current_verified_generation,last_verified_generation "
                + "FROM " + TABLES.fixtureSync() + " WHERE season_id=? AND fixture_id=?",
                SEASON.id(), INDEPENDENT);
        Map<String, Object> historyReceiptBefore = jdbc.queryForMap("SELECT latest_generation,source_read_started_at,"
                + "source_read_finished_at,published_at FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", SEASON.code(),
                HistoryFixtureDispatcher.playerHistoryPublicationKey(historyPlayer));
        int physicalRows = jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats "
                + "WHERE season_id=? AND fixture_id=?", Integer.class, SEASON.id(), INDEPENDENT);
        int evidenceRows = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.playerFixtureEvidence()
                + " WHERE season_id=? AND fixture_id=?", Integer.class, SEASON.id(), INDEPENDENT);

        // Roll back the real history publish after its callback reconciles fingerprints,
        // invalidates the fixture and enqueues verification work.
        fpl.correctedHistory30 = true;
        Lease rollbackLease = enqueueHistoryAndAcquire(harness, historyPlayer);
        tx.executeWithoutResult(status -> {
            assertThat(harness.histories.process(rollbackLease, SEASON).outcome()).isEqualTo(Outcome.COMPLETED);
            status.setRollbackOnly();
        });
        Map<String, Object> fingerprintAfterRollback = jdbc.queryForMap("SELECT fingerprint,source_read_finished_at,updated_at "
                + "FROM " + TABLES.historyFingerprint() + " WHERE season_id=? AND player_id=? AND fixture_id=?",
                SEASON.id(), historyPlayer, INDEPENDENT);
        assertThat(fingerprintAfterRollback).isEqualTo(fingerprintBefore);
        assertThat(jdbc.queryForMap("SELECT first_verified_at,next_due_at,updated_at,observation::text,"
                + "observation_generation,current_verified_generation,last_verified_generation FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", SEASON.id(), INDEPENDENT))
                .isEqualTo(fixtureDatesBeforeRollback);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE kind='FIXTURE_VERIFICATION' AND publication_key=? AND state IN ('PENDING','LEASED')",
                Integer.class, "fixture:" + INDEPENDENT)).isZero();
        assertThat(jdbc.queryForMap("SELECT latest_generation,source_read_started_at,source_read_finished_at,"
                + "published_at FROM " + TABLES.receipt() + " WHERE season=? AND publication_key=?",
                SEASON.code(), HistoryFixtureDispatcher.playerHistoryPublicationKey(historyPlayer)))
                .isEqualTo(historyReceiptBefore);
        assertThat(currentGeneration(INDEPENDENT)).isEqualTo(verifiedGeneration);
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run()
                + " WHERE generation=?", String.class, rollbackLease.generation())).isEqualTo("LEASED");
        assertThat(harness.histories.process(rollbackLease, SEASON).outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(currentGenerationOrNull(INDEPENDENT)).isNull();
        Map<String, Object> invalidatedObservation = jdbc.queryForMap("SELECT observation::text,observation_generation "
                + "FROM " + TABLES.fixtureSync() + " WHERE season_id=? AND fixture_id=?",
                SEASON.id(), INDEPENDENT);
        assertThat(JSON.readTree((String) invalidatedObservation.get("observation"))
                .path("coherent").booleanValue()).isFalse();
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", String.class, SEASON.id(), INDEPENDENT))
                .isEqualTo("VERIFYING");
        assertThat(jdbc.queryForObject("SELECT observation->>'coherent'='false' "
                + "AND observation_generation=? AND current_verified_generation IS NULL "
                + "AND first_verified_at IS NULL AND last_verified_generation=? "
                + "AND first_finished_observed_at IS NOT NULL FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", Boolean.class, verifiedGeneration, verifiedGeneration,
                SEASON.id(), INDEPENDENT)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats "
                + "WHERE season_id=? AND fixture_id=?", Integer.class, SEASON.id(), INDEPENDENT))
                .isEqualTo(physicalRows);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.playerFixtureEvidence()
                + " WHERE season_id=? AND fixture_id=?", Integer.class, SEASON.id(), INDEPENDENT))
                .isEqualTo(evidenceRows);
        assertThat(readRows("ACTIVE", INDEPENDENT)).isEmpty();
        Long invalidatedRun = jdbc.queryForObject("SELECT generation FROM " + TABLES.run()
                + " WHERE kind='FIXTURE_VERIFICATION' AND publication_key=? AND state='PENDING'",
                Long.class, "fixture:" + INDEPENDENT);
        assertThat(invalidatedRun).isNotNull();

        activator.deactivate(firstFence);
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run()
                + " WHERE generation=?", String.class, invalidatedRun)).isEqualTo("FAILED");
        RuntimeFence restartedFence = activator.activate(SEASON.code(), OwnedSyncMode.ACTIVE);
        Harness restarted = harness(clock, restartedFence, fpl);
        restarted.catalogue.enqueueCycle(SEASON, clock.instant());
        restarted.drainDue();
        int fixtureRunCount = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE kind='FIXTURE_VERIFICATION' AND publication_key=?", Integer.class,
                "fixture:" + INDEPENDENT);
        assertThat(publishPlayerHistory(restarted, historyPlayer).outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE kind='FIXTURE_VERIFICATION' AND publication_key=?", Integer.class,
                "fixture:" + INDEPENDENT)).isEqualTo(fixtureRunCount);
        assertThat(jdbc.queryForMap("SELECT observation::text,observation_generation FROM "
                + TABLES.fixtureSync() + " WHERE season_id=? AND fixture_id=?",
                SEASON.id(), INDEPENDENT)).isEqualTo(invalidatedObservation);

        var invalidated = new PlannerInputRepository(jdbc, restarted.store, TABLES, CAPTURED, clock)
                .load(SEASON).progress().stream().filter(progress -> progress.fixtureId() == INDEPENDENT)
                .findFirst().orElseThrow();
        assertThat(invalidated.verifiedAt()).isNull();
        assertThat(invalidated.completedCorrections()).isEmpty();
        restarted.coordinator.tick();
        var freshRun = jdbc.queryForMap("SELECT generation,runtime_epoch,runtime_mode,state FROM "
                + TABLES.run() + " WHERE kind='FIXTURE_VERIFICATION' AND publication_key=? "
                + "AND state='PENDING'", "fixture:" + INDEPENDENT);
        assertThat(((Number) freshRun.get("generation")).longValue()).isGreaterThan(invalidatedRun);
        assertThat(((Number) freshRun.get("runtime_epoch")).longValue()).isEqualTo(restartedFence.epoch());
        assertThat(freshRun.get("runtime_mode")).isEqualTo("ACTIVE");
        jdbc.update("UPDATE " + TABLES.run() + " SET due_at=? WHERE season=? AND state='PENDING' "
                        + "AND generation<>?", Timestamp.from(clock.instant().plus(Duration.ofDays(30))),
                SEASON.code(), ((Number) freshRun.get("generation")).longValue());

        observeTwice(restarted, fpl, INDEPENDENT, clock);
        assertThat(currentGeneration(INDEPENDENT)).isGreaterThan(verifiedGeneration);
        assertThat(readRows("ACTIVE", INDEPENDENT)).isNotEmpty();

        fpl.removedHistory30 = true;
        assertThat(publishPlayerHistory(restarted, historyPlayer).outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.historyFingerprint()
                + " WHERE season_id=? AND player_id=? AND fixture_id=?", Integer.class,
                SEASON.id(), historyPlayer, INDEPENDENT)).isZero();
        assertThat(currentGenerationOrNull(INDEPENDENT)).isNull();
        assertThat(jdbc.queryForObject("SELECT observation->>'coherent'='false' "
                + "AND observation_generation IS NOT NULL AND last_verified_generation IS NOT NULL "
                + "FROM " + TABLES.fixtureSync() + " WHERE season_id=? AND fixture_id=?",
                Boolean.class, SEASON.id(), INDEPENDENT)).isTrue();
        assertThat(readRows("ACTIVE", INDEPENDENT)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats "
                + "WHERE season_id=? AND fixture_id=?", Integer.class, SEASON.id(), INDEPENDENT))
                .isEqualTo(physicalRows);
    }

    @Test
    void firstPendingHistoryCorrectionsKeepOwnershipAndRequireTwoFreshObservations() throws Exception {
        cleanSeason();
        jdbc.update("UPDATE season SET is_current=false");
        jdbc.update("INSERT INTO season(id,code,name,start_date,end_date,is_current) VALUES "
                + "(27,'2026-27','First pending history correction season','2026-08-21',NULL,true)");
        seedLegacyRow();

        MutableClock clock = new MutableClock(Instant.parse("2026-09-06T17:00:00Z"));
        PlannerRuntimeRepository activator = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES);
        RuntimeFence firstFence = activator.activate(SEASON.code(), OwnedSyncMode.ACTIVE);
        FakeFpl fpl = new FakeFpl(clock);
        Harness harness = harness(clock, firstFence, fpl);
        harness.catalogue.enqueueCycle(SEASON, clock.instant());
        harness.drainDue();

        int historyPlayer = fpl.playersByFixture.get(INDEPENDENT).getFirst();
        assertThat(seedRetainedLegacyPfs(INDEPENDENT, historyPlayer)).isOne();
        assertThat(readRows("OFF", INDEPENDENT)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT expected_goals FROM player_fixture_stats "
                + "WHERE season_id=? AND fixture_id=? AND player_id=?", BigDecimal.class,
                SEASON.id(), INDEPENDENT, historyPlayer)).isEqualByComparingTo("0.77");

        observe(harness, INDEPENDENT);
        Map<String, Object> firstPending = jdbc.queryForMap("SELECT state,observation::text,"
                + "observation_generation,current_verified_generation,last_verified_generation "
                + "FROM " + TABLES.fixtureSync() + " WHERE season_id=? AND fixture_id=?",
                SEASON.id(), INDEPENDENT);
        assertThat(firstPending.get("state")).isEqualTo("VERIFYING");
        assertThat(JSON.readTree((String) firstPending.get("observation")).path("coherent").booleanValue())
                .isTrue();
        assertThat(firstPending.get("observation_generation")).isNotNull();
        assertThat(firstPending.get("current_verified_generation")).isNull();
        assertThat(firstPending.get("last_verified_generation")).isNull();
        long firstGeneration = ((Number) firstPending.get("observation_generation")).longValue();

        fpl.correctedHistory30 = true;
        assertThat(publishPlayerHistory(harness, historyPlayer).outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT observation->>'coherent'='false' "
                + "AND observation_generation=? FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", Boolean.class,
                firstGeneration, SEASON.id(), INDEPENDENT)).isTrue();
        observe(harness, INDEPENDENT);
        assertThat(jdbc.queryForObject("SELECT validation_failures[1] FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", String.class, SEASON.id(), INDEPENDENT))
                .isEqualTo("NEEDS_SECOND_OBSERVATION");
        long changedPendingGeneration = jdbc.queryForObject("SELECT observation_generation FROM "
                + TABLES.fixtureSync() + " WHERE season_id=? AND fixture_id=?",
                Long.class, SEASON.id(), INDEPENDENT);
        assertThat(changedPendingGeneration).isGreaterThan(firstGeneration);

        fpl.removedHistory30 = true;
        assertThat(publishPlayerHistory(harness, historyPlayer).outcome()).isEqualTo(Outcome.COMPLETED);

        Map<String, Object> invalidated = jdbc.queryForMap("SELECT observation::text,observation_generation,"
                + "current_verified_generation,last_verified_generation FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", SEASON.id(), INDEPENDENT);
        assertThat(JSON.readTree((String) invalidated.get("observation")).path("coherent").booleanValue())
                .isFalse();
        assertThat(invalidated.get("observation_generation")).isEqualTo(changedPendingGeneration);
        assertThat(invalidated.get("current_verified_generation")).isNull();
        assertThat(invalidated.get("last_verified_generation")).isNull();
        assertThat(jdbc.queryForObject("SELECT expected_goals FROM player_fixture_stats "
                + "WHERE season_id=? AND fixture_id=? AND player_id=?", BigDecimal.class,
                SEASON.id(), INDEPENDENT, historyPlayer)).isEqualByComparingTo("0.77");
        assertThat(readRows("ACTIVE", INDEPENDENT)).isEmpty();

        FixtureSyncRepository repository = new FixtureSyncRepository(jdbc, TABLES);
        var stored = tx.execute(status -> repository.lockObservation(SEASON, INDEPENDENT, clock.instant())
                .orElseThrow());
        assertThat(stored.generation()).isEqualTo(changedPendingGeneration);
        assertThat(stored.observation().coherent()).isFalse();

        activator.deactivate(firstFence);
        PfsReadSource off = new PfsReadSource(jdbc, "OFF", SEASON.code(),
                TABLES.fixtureSync(), TABLES.playerFixtureEvidence());
        assertThat(readRows("OFF", INDEPENDENT)).isEmpty();
        assertThat(off.fixtureReceipts(SEASON.id(), SEASON.code(), Set.of(INDEPENDENT)))
                .containsEntry(INDEPENDENT, PfsReadSource.FixtureReceipt.UNVERIFIED_OWNED);

        RuntimeFence restartedFence = activator.activate(SEASON.code(), OwnedSyncMode.ACTIVE);
        Harness restarted = harness(clock, restartedFence, fpl);
        fpl.removedHistory30 = false;
        restarted.catalogue.enqueueCycle(SEASON, clock.instant());
        restarted.drainDue();
        assertThat(publishPlayerHistory(restarted, historyPlayer).outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT observation_generation FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", Long.class, SEASON.id(), INDEPENDENT))
                .isEqualTo(changedPendingGeneration);
        assertThat(jdbc.queryForObject("SELECT observation->>'coherent'='false' FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", Boolean.class, SEASON.id(), INDEPENDENT)).isTrue();

        observe(restarted, INDEPENDENT);
        assertThat(jdbc.queryForObject("SELECT validation_failures[1] FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", String.class, SEASON.id(), INDEPENDENT))
                .isEqualTo("NEEDS_SECOND_OBSERVATION");
        assertThat(jdbc.queryForObject("SELECT current_verified_generation FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", Long.class, SEASON.id(), INDEPENDENT)).isNull();
        assertThat(readRows("ACTIVE", INDEPENDENT)).isEmpty();

        clock.advance(Duration.ofMinutes(11));
        observe(restarted, INDEPENDENT);
        assertVerified(INDEPENDENT);
        assertThat(currentGeneration(INDEPENDENT)).isGreaterThan(changedPendingGeneration);
        assertThat(lastTwoReads(INDEPENDENT)).satisfies(reads -> {
            assertThat(reads.get(1).started()).isAfterOrEqualTo(
                    reads.get(0).finished().plus(Duration.ofMinutes(10)));
            assertThat(reads.get(0).started()).isAfterOrEqualTo(reads.get(0).sourceFloor());
            assertThat(reads.get(1).started()).isAfterOrEqualTo(reads.get(1).sourceFloor());
        });
        assertThat(jdbc.queryForObject("SELECT expected_goals FROM player_fixture_stats "
                + "WHERE season_id=? AND fixture_id=? AND player_id=?", BigDecimal.class,
                SEASON.id(), INDEPENDENT, historyPlayer)).isEqualByComparingTo("0.99");
    }

    @Test
    void fullTenMatchdayDemandIsMaterializedAndBudgetExhaustionCannotCompleteWork() {
        jdbc.update("DELETE FROM " + TABLES.plannerSlot());
        jdbc.update("DELETE FROM " + TABLES.plannerRuntime());
        jdbc.update("DELETE FROM " + TABLES.run());
        jdbc.update("DELETE FROM " + TABLES.receipt());
        jdbc.update("DELETE FROM " + TABLES.requestAttempt());
        jdbc.update("UPDATE " + TABLES.requestState() + " SET last_admitted_at=NULL,cooldown_until=NULL");

        MutableClock clock = new MutableClock(Instant.parse("2026-09-19T03:00:00Z"));
        List<AdaptiveMatchdayPlanner.Fixture> fixtures = new ArrayList<>();
        int player = 1;
        Set<Integer> allPlayers = new HashSet<>();
        for (int match = 0; match < 10; match++) {
            Set<Integer> players = new HashSet<>();
            for (int count = 0; count < 60; count++) { players.add(player); allPlayers.add(player++); }
            fixtures.add(new AdaptiveMatchdayPlanner.Fixture(100 + match, 1,
                    Instant.parse("2026-09-19T%02d:00:00Z".formatted(11 + match)),
                    false, false, false, clock.instant(), players));
        }
        PlannerInputRepository input = org.mockito.Mockito.mock(PlannerInputRepository.class);
        org.mockito.Mockito.when(input.load(org.mockito.ArgumentMatchers.any())).thenReturn(
                new PlannerInputRepository.PlannerSnapshot(allPlayers, fixtures, List.of(), true));
        PlannerRuntimeRepository runtime = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES);
        OwnedSyncStore store = new OwnedSyncStore(jdbc, tx, clock, TABLES);
        CatalogueScheduleDispatcher catalogue = org.mockito.Mockito.mock(CatalogueScheduleDispatcher.class);
        HistoryFixtureDispatcher histories = org.mockito.Mockito.mock(HistoryFixtureDispatcher.class);
        OwnedMatchdayCoordinator coordinator = new OwnedMatchdayCoordinator(SEASON,
                new AdaptiveMatchdayPlanner(clock), input, runtime, store, catalogue, histories, "ten-matchday");

        // Ten minutes is the smallest production cadence, so this covers every planner boundary
        // without turning the integration test into 1,440 redundant database heartbeats.
        for (int minute = 0; minute < 24 * 60; minute += 10) {
            clock.set(Instant.parse("2026-09-19T03:00:00Z").plusSeconds(minute * 60L));
            coordinator.tick();
        }
        Map<String, Object> demand = jdbc.queryForMap("SELECT count(*) slots, sum(estimated_requests) requests,"
                + " count(*) FILTER (WHERE kind='MATCHDAY_SOURCE') source_slots FROM " + TABLES.plannerSlot());
        assertThat(((Number) demand.get("slots")).intValue()).isPositive();
        // Thirty registered players per club is intentionally not just the matchday squad.
        // Cadence demand can exceed the hard ceiling: deferral, not an impossible SLA, is expected.
        assertThat(((Number) demand.get("requests")).intValue()).isGreaterThan(5_000);
        assertThat(((Number) demand.get("source_slots")).intValue()).isEqualTo(92);

        clock.set(Instant.parse("2026-09-20T00:00:00Z"));
        Limits tiny = new Limits(Duration.ofSeconds(1), 2, 5, Duration.ofHours(24),
                Duration.ofMinutes(2), Duration.ofSeconds(1), Duration.ofMinutes(1));
        OwnedSyncRequestBudget budget = new OwnedSyncRequestBudget(jdbc, tx, clock, TABLES, tiny);
        for (int attempt = 0; attempt < 5; attempt++) {
            Allowed allowed = (Allowed) budget.admit();
            budget.complete(allowed.permit(), OwnedSyncRequestBudget.Completion.success());
            clock.advance(Duration.ofSeconds(1));
        }
        assertThat(budget.admit()).isInstanceOf(Deferred.class);
        var run = store.enqueue(SyncKind.PLAYER_HISTORY, SEASON.code(), "budget-exhausted",
                "budget-exhausted", clock.instant());
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run() + " WHERE generation=?",
                String.class, run.generation())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT completed_at IS NULL FROM " + TABLES.run()
                + " WHERE generation=?", Boolean.class, run.generation())).isTrue();
    }

    private static Harness harness(MutableClock clock, RuntimeFence fence, FakeFpl fpl) {
        OwnedSyncStore store = new OwnedSyncStore(jdbc, tx, clock, TABLES, fence);
        PlannerRuntimeRepository runtime = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES, fence);
        CatalogueScheduleWriter writer = new CatalogueScheduleWriter(jdbc, clock, store, TABLES, CAPTURED);
        CatalogueScheduleDispatcher catalogue = new CatalogueScheduleDispatcher(store, writer, fpl, clock);
        FixtureSyncPublisher publisher = new FixtureSyncPublisher(jdbc, clock, store,
                new FixtureSyncRepository(jdbc, TABLES), TABLES);
        HistoryFixtureDispatcher histories = new HistoryFixtureDispatcher(store, publisher, fpl, clock);
        PlannerInputRepository inputs = new PlannerInputRepository(jdbc, store, TABLES, CAPTURED, clock);
        OwnedMatchdayCoordinator coordinator = new OwnedMatchdayCoordinator(SEASON,
                new AdaptiveMatchdayPlanner(clock), inputs, runtime, store, catalogue, histories, "e2e");
        return new Harness(store, catalogue, histories, coordinator, clock);
    }

    private static void observeTwice(Harness harness, FakeFpl fpl, int fixture, MutableClock clock) {
        observe(harness, fixture);
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.fixtureSync()
                + " WHERE fixture_id=?", String.class, fixture)).isNotEqualTo("VERIFIED");
        clock.advance(Duration.ofMinutes(11));
        observe(harness, fixture);
    }

    private static Lease enqueueHistoryAndAcquire(Harness harness, int playerId) {
        var run = harness.histories.enqueuePlayerHistory(SEASON, playerId, harness.clock.instant());
        Lease lease = harness.store.acquire("history-regression", Duration.ofMinutes(5)).orElseThrow();
        assertThat(lease.generation()).isEqualTo(run.generation());
        return lease;
    }

    private static HistoryFixtureDispatcher.DispatchResult publishPlayerHistory(Harness harness, int playerId) {
        Lease lease = enqueueHistoryAndAcquire(harness, playerId);
        return harness.histories.process(lease, SEASON);
    }

    private static Long currentGenerationOrNull(int fixture) {
        return jdbc.queryForObject("SELECT current_verified_generation FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", Long.class, SEASON.id(), fixture);
    }

    private static void observe(Harness harness, int fixture) {
        harness.histories.enqueueFixtureVerification(SEASON, fixture, harness.clock.instant());
        for (int round = 0; round < 20; round++) {
            harness.drainDue();
            Integer active = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                    + " WHERE publication_key=? AND state IN ('PENDING','LEASED')", Integer.class, "fixture:" + fixture);
            if (active == 0) return;
            Timestamp due = jdbc.queryForObject("SELECT min(due_at) FROM " + TABLES.run()
                    + " WHERE state='PENDING'", Timestamp.class);
            if (due == null) return;
            if (due.toInstant().isAfter(harness.clock.instant())) harness.clock.set(due.toInstant());
        }
        throw new AssertionError("fixture observation did not settle");
    }

    private static void publishSchedule(Harness harness, MutableClock clock) {
        harness.catalogue.enqueueSchedule(SEASON, clock.instant());
        harness.drainDue();
    }

    private static void assertVerified(int fixture) {
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.fixtureSync()
                + " WHERE fixture_id=?", String.class, fixture)).isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.playerFixtureEvidence()
                + " WHERE fixture_id=?", Integer.class, fixture)).isGreaterThanOrEqualTo(2);
    }

    private static long currentGeneration(int fixture) {
        return jdbc.queryForObject("SELECT current_verified_generation FROM " + TABLES.fixtureSync()
                + " WHERE fixture_id=?", Long.class, fixture);
    }

    private static List<Read> lastTwoReads(int fixture) {
        return jdbc.query("SELECT requested_source_after,source_read_started_at,source_read_finished_at FROM "
                        + TABLES.run()
                        + " WHERE publication_key=? AND state='COMPLETED' AND source_read_started_at IS NOT NULL"
                        + " ORDER BY generation DESC LIMIT 2",
                (rs, row) -> new Read(rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant(),
                        rs.getTimestamp(1).toInstant()),
                "fixture:" + fixture).reversed();
    }

    private static List<Map<String, Object>> readRows(String mode, int fixture) {
        PfsReadSource source = new PfsReadSource(jdbc, mode, SEASON.code(),
                TABLES.fixtureSync(), TABLES.playerFixtureEvidence());
        return jdbc.queryForList("WITH " + source.cte() + " SELECT p.*,e.component_presence FROM pfs_read p"
                + " LEFT JOIN " + TABLES.fixtureSync() + " fs ON fs.season_id=p.season_id AND fs.fixture_id=p.fixture_id"
                + " LEFT JOIN " + TABLES.playerFixtureEvidence() + " e ON e.season_id=p.season_id"
                + " AND e.fixture_id=p.fixture_id AND e.player_id=p.player_id"
                + " AND e.generation=fs.current_verified_generation"
                + " WHERE p.season_id=27 AND p.fixture_id=? ORDER BY p.player_id", fixture);
    }

    private static void createCapturedTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS fpl_daily_price_test (
                    season varchar(10) NOT NULL, price_date date NOT NULL, element_id integer NOT NULL,
                    web_name varchar(100) NOT NULL, full_name varchar(200), team_id integer,
                    team_short varchar(10), team_name varchar(100), element_type smallint,
                    now_cost integer NOT NULL, cost_change_event integer NOT NULL DEFAULT 0,
                    cost_change_start integer NOT NULL DEFAULT 0, selected_by_percent numeric(5,2),
                    status varchar(4), news text, penalties_order smallint, direct_fk_order smallint,
                    corners_order smallint, captured_at timestamptz NOT NULL DEFAULT now(),
                    PRIMARY KEY(season, price_date, element_id))
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS fpl_gameweek_test (
                    season varchar(10) NOT NULL, id integer NOT NULL, name varchar(50),
                    deadline_time timestamptz NOT NULL, finished boolean NOT NULL DEFAULT false,
                    data_checked boolean NOT NULL DEFAULT false, is_current boolean NOT NULL DEFAULT false,
                    is_next boolean NOT NULL DEFAULT false, average_entry_score integer,
                    highest_score integer, most_captained integer, most_transferred_in integer,
                    captured_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(season,id))
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS fpl_fixture_test (
                    season varchar(10) NOT NULL, id integer NOT NULL, gameweek integer,
                    team_h integer NOT NULL, team_a integer NOT NULL, team_h_name varchar(100),
                    team_h_short varchar(10), team_a_name varchar(100), team_a_short varchar(10),
                    team_h_difficulty smallint, team_a_difficulty smallint, kickoff_time timestamptz,
                    started boolean NOT NULL DEFAULT false, finished boolean NOT NULL DEFAULT false,
                    team_h_score integer, team_a_score integer,
                    captured_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(season,id))
                """);
    }

    private static int seedRetainedLegacyPfs(int fixtureId, int playerId) {
        return jdbc.update("""
                INSERT INTO player_fixture_stats (fixture_id,player_id,season_id,gameweek,player_team_id,
                    opponent_team_id,was_home,kickoff_time,minutes,total_points,expected_goals,expected_assists,value)
                SELECT f.id,p.id,f.season_id,f.gameweek,p.team_id,
                    CASE WHEN p.team_id=f.team_h THEN f.team_a ELSE f.team_h END,
                    p.team_id=f.team_h,f.kickoff_time,90,3,0.77,0.15,50
                FROM fixture f JOIN player p ON p.season_id=f.season_id
                WHERE f.season_id=? AND f.id=? AND p.id=?
                """, SEASON.id(), fixtureId, playerId);
    }

    private static void seedLegacyRow() {
        jdbc.update("INSERT INTO team(id,season_id,code,name,short_name,strength) VALUES"
                + "(1,27,1,'Legacy home','LEG',3),(6,27,6,'Legacy away','LEA',3)");
        jdbc.update("INSERT INTO player(id,season_id,team_id,web_name,element_type,now_cost,status)"
                + " VALUES (1,27,1,'Legacy',3,50,'a')");
        jdbc.update("INSERT INTO fixture(id,season_id,gameweek,code,team_h,team_a,kickoff_time,started,finished)"
                + " VALUES (990029,27,1,990029,1,6,'2026-08-21T14:00:00Z',true,true)");
        jdbc.update("INSERT INTO player_fixture_stats(fixture_id,player_id,season_id,gameweek,player_team_id,"
                + "opponent_team_id,was_home,kickoff_time,minutes,total_points,expected_goals,expected_assists,value)"
                + " VALUES (990029,1,27,1,1,6,true,'2026-08-21T14:00:00Z',90,2,0.1,0.1,50)");
    }

    private static void cleanSeason() {
        if (jdbc == null) return;
        jdbc.update("DELETE FROM " + TABLES.alert());
        jdbc.update("DELETE FROM " + TABLES.plannerSlot());
        jdbc.update("DELETE FROM " + TABLES.plannerRuntime());
        jdbc.execute("TRUNCATE " + TABLES.playerFixtureEvidence() + "," + TABLES.fixtureSync() + ","
                + TABLES.historyFingerprint() + "," + TABLES.payload() + "," + TABLES.run() + ","
                + TABLES.receipt() + " CASCADE");
        jdbc.update("DELETE FROM player_fixture_stats WHERE season_id=27");
        jdbc.update("DELETE FROM fixture WHERE season_id=27");
        jdbc.update("DELETE FROM player WHERE season_id=27");
        jdbc.update("DELETE FROM team WHERE season_id=27");
        jdbc.update("DELETE FROM gameweek WHERE season_id=27");
        jdbc.update("DELETE FROM " + CAPTURED.fixture() + " WHERE season='2026-27'");
        jdbc.update("DELETE FROM " + CAPTURED.gameweek() + " WHERE season='2026-27'");
        jdbc.update("DELETE FROM " + CAPTURED.dailyPrice() + " WHERE season='2026-27'");
        jdbc.update("DELETE FROM season WHERE id=27");
    }

    private record Harness(OwnedSyncStore store, CatalogueScheduleDispatcher catalogue,
                           HistoryFixtureDispatcher histories, OwnedMatchdayCoordinator coordinator,
                           MutableClock clock) {
        void drainDue() {
            for (int i = 0; i < 500 && coordinator.dispatchOne("drain-" + i); i++) { }
        }
    }

    private record Read(Instant started, Instant finished, Instant sourceFloor) { }

    private static final class FakeFpl implements CatalogueScheduleDispatcher.Fetch,
            HistoryFixtureDispatcher.Fetch {
        private final MutableClock clock;
        private final ObjectNode bootstrap;
        private final ArrayNode fixtures;
        private final Map<Integer, Integer> playerTeams = new HashMap<>();
        private final Map<Integer, List<Integer>> playersByFixture = new HashMap<>();
        private boolean rateLimitNextFixtures;
        private boolean countedRateLimit;
        private int fixtureCallsAfterRateLimit;
        private Integer failPlayerOnce;
        private boolean finished29;
        private boolean corrected29;
        private boolean correctedHistory30;
        private boolean removedHistory30;
        private boolean optionalUnknown;

        private FakeFpl(MutableClock clock) throws Exception {
            this.clock = clock;
            bootstrap = (ObjectNode) JSON.readTree(resource("bootstrap-static.json"));
            fixtures = (ArrayNode) JSON.readTree(resource("fixtures.json"));
            Set<Integer> teams = Set.of(1, 6, 9, 16);
            Map<Integer, JsonNode> first = new HashMap<>();
            bootstrap.withArray("elements").forEach(player -> first.putIfAbsent(player.get("team").intValue(), player));
            ArrayNode selected = JSON.createArrayNode();
            teams.stream().sorted().forEach(team -> {
                JsonNode player = first.get(team);
                selected.add(player);
                playerTeams.put(player.get("id").intValue(), team);
            });
            bootstrap.set("elements", selected);
            playersByFixture.put(DELAYED, selectedFor(1, 6));
            playersByFixture.put(INDEPENDENT, selectedFor(9, 16));
            updateFixtures();
        }

        @Override public OwnedSyncHttpClient.Result get(Endpoint endpoint, int attempt) {
            try {
                // The direct fake bypasses the HTTP fence; leave the fixture/live source floor's 1 ms gap.
                clock.advance(Duration.ofMillis(1));
                Instant started = clock.instant();
                clock.advance(Duration.ofMillis(100));
                SourceRead read = new SourceRead(started, clock.instant());
                if (endpoint.equals(Endpoint.fixtures()) && rateLimitNextFixtures) {
                    rateLimitNextFixtures = false;
                    countedRateLimit = true;
                    return new Response(429, "busy".getBytes(), "30", read, clock.instant().plusSeconds(30));
                }
                if (countedRateLimit && endpoint.equals(Endpoint.fixtures())) fixtureCallsAfterRateLimit++;
                if (endpoint.kind() == OwnedSyncStore.EndpointKind.ELEMENT_SUMMARY
                        && endpoint.playerId().equals(failPlayerOnce)) {
                    failPlayerOnce = null;
                    return new Response(500, new byte[0], null, read, clock.instant().plusSeconds(30));
                }
                byte[] body = switch (endpoint.kind()) {
                    case BOOTSTRAP_STATIC -> JSON.writeValueAsBytes(bootstrap);
                    case FIXTURES -> { updateFixtures(); yield JSON.writeValueAsBytes(fixtures); }
                    case EVENT_LIVE -> live();
                    case ELEMENT_SUMMARY -> history(endpoint.playerId());
                };
                return new Response(200, body, null, read, null);
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        }

        private List<Integer> selectedFor(int home, int away) {
            return playerTeams.entrySet().stream().filter(entry -> entry.getValue() == home || entry.getValue() == away)
                    .map(Map.Entry::getKey).sorted().toList();
        }

        private void updateFixtures() {
            for (JsonNode node : fixtures) {
                ObjectNode fixture = (ObjectNode) node;
                int id = fixture.get("id").intValue();
                if (id != DELAYED && id != INDEPENDENT) continue;
                boolean finished = id == INDEPENDENT || finished29;
                fixture.put("started", true).put("finished", finished)
                        .put("finished_provisional", finished).put("minutes", finished ? 90 : 70);
                fixture.put("team_h_score", id == DELAYED && corrected29 ? 3 : 2).put("team_a_score", 1);
                ArrayNode stats = fixture.putArray("stats");
                ObjectNode minutes = stats.addObject().put("identifier", "minutes");
                minutes.putArray("h").addObject().put("element", playersByFixture.get(id).getFirst()).put("value", 90);
                minutes.putArray("a").addObject().put("element", playersByFixture.get(id).getLast()).put("value", 90);
            }
        }

        private byte[] live() throws Exception {
            ObjectNode root = JSON.createObjectNode();
            ArrayNode elements = root.putArray("elements");
            playersByFixture.forEach((fixture, players) -> players.forEach(player -> {
                ObjectNode element = elements.addObject().put("id", player);
                ObjectNode explanation = element.putArray("explain").addObject().put("fixture", fixture);
                explanation.putArray("stats").addObject().put("identifier", "minutes")
                        .put("value", 90).put("points", corrected29 && fixture == DELAYED ? 3 : 2)
                        .put("points_modification", 0);
            }));
            return JSON.writeValueAsBytes(root);
        }

        private byte[] history(int player) throws Exception {
            int fixture = playersByFixture.get(DELAYED).contains(player) ? DELAYED : INDEPENDENT;
            int team = playerTeams.get(player);
            ObjectNode sourceFixture = null;
            for (JsonNode value : fixtures) if (value.get("id").intValue() == fixture) sourceFixture = (ObjectNode) value;
            int home = sourceFixture.get("team_h").intValue();
            int away = sourceFixture.get("team_a").intValue();
            boolean wasHome = team == home;
            ObjectNode row = JSON.createObjectNode();
            row.put("element", player).put("fixture", fixture).put("round", 3)
                    .put("opponent_team", wasHome ? away : home).put("was_home", wasHome)
                    .put("kickoff_time", sourceFixture.get("kickoff_time").textValue()).put("minutes", 90)
                    .put("total_points", corrected29 && fixture == DELAYED ? 3 : 2).put("bonus", 0).put("bps", 1)
                    .put("goals_scored", 0).put("assists", 0).put("clean_sheets", 0).put("goals_conceded", 1)
                    .put("own_goals", 0).put("penalties_saved", 0).put("penalties_missed", 0).put("saves", 0)
                    .put("yellow_cards", 0).put("red_cards", 0).put("starts", 1).put("value", 50)
                    .put("selected", 1).put("transfers_in", 0).put("transfers_out", 0)
                    .put("expected_goals", correctedHistory30 && fixture == INDEPENDENT
                            && player == playersByFixture.get(INDEPENDENT).getFirst() ? "0.99" : "0.20")
                    .put("expected_goal_involvements", "0.20")
                    .put("expected_goals_conceded", "1.00").put("influence", "1.0")
                    .put("creativity", "1.0").put("threat", "1.0").put("ict_index", "1.0")
                    .put("clearances_blocks_interceptions", 0).put("recoveries", 0).put("tackles", 0)
                    .put("defensive_contribution", 0);
            if (!(optionalUnknown && fixture == DELAYED && player == playersByFixture.get(DELAYED).getFirst())) {
                row.put("expected_assists", "0.10");
            }
            ObjectNode root = JSON.createObjectNode();
            ArrayNode history = root.putArray("history");
            if (!(removedHistory30 && fixture == INDEPENDENT
                    && player == playersByFixture.get(INDEPENDENT).getFirst())) history.add(row);
            return JSON.writeValueAsBytes(root);
        }
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream input = OwnedMatchdayEndToEndIntegrationTest.class.getResourceAsStream(
                "/owned-sync-source-fixtures/" + name)) {
            if (input == null) throw new IllegalArgumentException("missing " + name);
            return input.readAllBytes();
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static final class MutableClock extends Clock {
        private Instant value;
        private MutableClock(Instant value) { this.value = value; }
        private void set(Instant value) { this.value = value; }
        private void advance(Duration duration) { value = value.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
    }
}
