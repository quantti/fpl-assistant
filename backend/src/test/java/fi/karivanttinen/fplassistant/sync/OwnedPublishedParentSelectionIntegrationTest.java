package fi.karivanttinen.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.Season;
import fi.karivanttinen.fplassistant.sync.HistoryFixtureDispatcher.DispatchResult;
import fi.karivanttinen.fplassistant.sync.HistoryFixtureDispatcher.FailureType;
import fi.karivanttinen.fplassistant.sync.OwnedSyncHttpClient.DeferredResult;
import fi.karivanttinen.fplassistant.sync.OwnedSyncRequestBudget.DeferredReason;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.CanonicalSourcePair;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Endpoint;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.EndpointKind;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Lease;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Run;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.SourceRead;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.StagedPayload;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.SyncKind;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Validation;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.ValidationState;
import fi.karivanttinen.fplassistant.sync.SharedFixtureRecoveryPolicy.SourceStatus;
import fi.karivanttinen.fplassistant.testsupport.TapasSeed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class OwnedPublishedParentSelectionIntegrationTest {

    private static final OwnedSyncTables TABLES = new OwnedSyncTables(
            "fpl_psel_run", "fpl_psel_payload", "fpl_psel_receipt", "fpl_psel_req_state",
            "fpl_psel_req_attempt", "fpl_psel_fixture", "fpl_psel_evidence", "fpl_psel_plan_slot",
            "fpl_psel_plan_runtime", "fpl_psel_manifest");
    private static final CatalogueScheduleTables CAPTURED = new CatalogueScheduleTables(
            "fpl_psel_price", "fpl_psel_gw", "fpl_psel_capture_fixture");
    private static final Season SEASON = new Season(27, "2026-27", LocalDate.of(2026, 8, 21), null);
    private static final int FIXTURE_ID = 1;
    private static final Instant BASE = Instant.parse("2026-10-01T12:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] SMALL_BOOTSTRAP = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] SMALL_FIXTURES = "[]".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static byte[] bootstrap;
    private static byte[] fixtures;
    private static byte[] unfinishedFixtures;

    private MutableClock clock;
    private OwnedSyncStore.RuntimeFence fence;
    private OwnedSyncStore store;

    @BeforeAll
    static void initialize() throws Exception {
        var dataSource = new DriverManagerDataSource(required("DATABASE_URL"), required("DB_USER"),
                required("DB_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        TapasSeed.ensureOwnedSyncCaptureSeason(jdbc);
        new OwnedSyncSchema(jdbc, TABLES).initialize();
        createCapturedTables();
        bootstrap = resource("bootstrap-static.json");
        fixtures = resource("fixtures.json");
        unfinishedFixtures = withFixtureOneUnfinished(fixtures);
    }

    @BeforeEach
    void setUp() {
        clearOwnedRows(true);
        clock = new MutableClock(BASE);
        fence = activate(OwnedSyncMode.ACTIVE);
        store = new OwnedSyncStore(jdbc, tx, clock, TABLES, fence);
    }

    @AfterEach
    void cleanUp() {
        clearOwnedRows(true);
    }

    @Test
    void plannerDoesNotReusePublishedParentAfterUnpublishedSourceDemand() throws Exception {
        PairSeed published = seedPublishedPair(bootstrap, fixtures, BASE.plusSeconds(10),
                Lane.SCHEDULE, Lane.PRICE, true);
        PlannerInputRepository inputs = new PlannerInputRepository(jdbc, store, TABLES, CAPTURED, clock);
        var initial = inputs.load(SEASON);
        assertMarker(published);

        Instant firstFinished = clock.instant().minus(Duration.ofHours(13));
        jdbc.update("UPDATE " + TABLES.fixtureSync() + " SET first_finished_observed_at=?"
                        + " WHERE season_id=? AND fixture_id=?",
                timestamp(firstFinished), SEASON.id(), FIXTURE_ID);
        PairSeed staged = seedUnpublishedPair(bootstrap, fixtures, BASE.plusSeconds(60), Lane.PRICE);

        var planned = inputs.load(SEASON);
        var fixture = planned.fixtures().stream().filter(row -> row.id() == FIXTURE_ID).findFirst().orElseThrow();
        assertThat(initial.fixtures().stream().filter(row -> row.id() == FIXTURE_ID).findFirst().orElseThrow()
                .recoverySourceStatus()).isEqualTo(SourceStatus.FINISHED);
        assertThat(fixture.recoverySourceStatus()).isEqualTo(SourceStatus.UNKNOWN);
        assertThat(staged.sourceGeneration()).isGreaterThan(published.sourceGeneration());
        assertThat(staged.sourceGeneration()).isNotEqualTo(published.publicationGeneration());
        assertThat(jdbc.queryForObject("SELECT finished_parent_generation FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", Long.class, SEASON.id(), FIXTURE_ID)).isNull();
        assertThat(jdbc.queryForObject("SELECT first_finished_observed_at FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", Timestamp.class, SEASON.id(), FIXTURE_ID).toInstant())
                .isEqualTo(firstFinished);
        assertReceiptStillPublished(published);
        assertThat(store.currentCanonicalParentEvidence(SEASON.code(), FIXTURE_ID, clock.instant()))
                .isEqualTo(OwnedSyncStore.CanonicalParentEvidence.MISSING);

        List<Endpoint> fetched = new ArrayList<>();
        var publisher = new FixtureSyncPublisher(jdbc, clock, store,
                new FixtureSyncRepository(jdbc, TABLES), TABLES);
        var dispatcher = new HistoryFixtureDispatcher(store, publisher, (endpoint, failureAttempt) -> {
            fetched.add(endpoint);
            return new DeferredResult(clock.instant().plusSeconds(1), DeferredReason.MINIMUM_SPACING);
        }, clock);
        Run fixtureRun = dispatcher.enqueueFixtureVerification(SEASON, FIXTURE_ID, clock.instant());
        Lease lease = store.acquireForSeason(SEASON.code(), "published-parent-test", Duration.ofMinutes(5))
                .orElseThrow();
        assertThat(lease.generation()).isEqualTo(fixtureRun.generation());

        DispatchResult result = dispatcher.process(lease, SEASON);

        assertThat(result.failure()).isEqualTo(FailureType.HTTP_DEFERRED);
        assertThat(fetched).isEmpty();
        assertThat(jdbc.queryForObject("SELECT recovery_started_at FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", Timestamp.class, SEASON.id(), FIXTURE_ID)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE season=? AND kind='CATALOGUE_SCHEDULE' AND runtime_mode='ACTIVE'"
                + " AND runtime_epoch=? AND state='PENDING'", Integer.class, SEASON.code(), fence.epoch()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT first_finished_observed_at FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", Timestamp.class, SEASON.id(), FIXTURE_ID).toInstant())
                .isEqualTo(firstFinished);
        assertReceiptStillPublished(published);
    }

    @Test
    void newerPublishedUnfinishedPairClearsFinishMarkerAndDoesNotReserveRecovery() {
        PairSeed published = seedPublishedPair(bootstrap, fixtures, BASE.plusSeconds(10),
                Lane.SCHEDULE, Lane.PRICE, false);
        PlannerInputRepository inputs = new PlannerInputRepository(jdbc, store, TABLES, CAPTURED, clock);
        inputs.load(SEASON);
        assertMarker(published);
        jdbc.update("UPDATE " + TABLES.fixtureSync() + " SET first_finished_observed_at=?"
                        + " WHERE season_id=? AND fixture_id=?",
                timestamp(clock.instant().minus(Duration.ofHours(13))), SEASON.id(), FIXTURE_ID);

        PairSeed newer = seedPublishedPair(bootstrap, unfinishedFixtures, BASE.plusSeconds(60),
                Lane.PRICE, Lane.SCHEDULE, false);
        var planner = inputs.load(SEASON);
        var fixture = planner.fixtures().stream().filter(row -> row.id() == FIXTURE_ID).findFirst().orElseThrow();
        assertThat(newer.sourceGeneration()).isGreaterThan(published.sourceGeneration());
        assertThat(fixture.recoverySourceStatus()).isEqualTo(SourceStatus.UNFINISHED);
        assertThat(jdbc.queryForObject("SELECT finished_parent_generation FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", Long.class, SEASON.id(), FIXTURE_ID)).isNull();
        assertThat(store.currentCanonicalParentEvidence(SEASON.code(), FIXTURE_ID, clock.instant()))
                .isEqualTo(new OwnedSyncStore.CanonicalParentEvidence(true, false));

        List<Endpoint> fetched = new ArrayList<>();
        var publisher = new FixtureSyncPublisher(jdbc, clock, store,
                new FixtureSyncRepository(jdbc, TABLES), TABLES);
        var dispatcher = new HistoryFixtureDispatcher(store, publisher, (endpoint, failureAttempt) -> {
            fetched.add(endpoint);
            return new DeferredResult(clock.instant().plusSeconds(1), DeferredReason.MINIMUM_SPACING);
        }, clock);
        Run fixtureRun = dispatcher.enqueueFixtureVerification(SEASON, FIXTURE_ID, clock.instant());
        Lease lease = store.acquireForSeason(SEASON.code(), "unfinished-parent-test", Duration.ofMinutes(5))
                .orElseThrow();
        assertThat(lease.generation()).isEqualTo(fixtureRun.generation());

        DispatchResult result = dispatcher.process(lease, SEASON);

        assertThat(result.failure()).isEqualTo(FailureType.HTTP_DEFERRED);
        assertThat(fetched).isEmpty();
        assertThat(jdbc.queryForObject("SELECT recovery_started_at FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", Timestamp.class, SEASON.id(), FIXTURE_ID)).isNull();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", Integer.class, SEASON.id(), FIXTURE_ID)).isZero();
    }

    @Test
    void missingEligibleParentClearsMarkerAndWakesCanonicalRetry() {
        seedPublishedPair(bootstrap, fixtures, BASE.plusSeconds(10), Lane.SCHEDULE, Lane.PRICE, false);
        PlannerInputRepository inputs = new PlannerInputRepository(jdbc, store, TABLES, CAPTURED, clock);
        inputs.load(SEASON);
        Instant firstFinished = clock.instant().minus(Duration.ofHours(13));
        jdbc.update("UPDATE " + TABLES.fixtureSync() + " SET first_finished_observed_at=?"
                        + " WHERE season_id=? AND fixture_id=?",
                timestamp(firstFinished), SEASON.id(), FIXTURE_ID);
        jdbc.update("UPDATE " + TABLES.run() + " SET runtime_mode='STAGING' WHERE generation=?",
                jdbc.queryForObject("SELECT latest_generation FROM " + TABLES.receipt()
                        + " WHERE season=? AND publication_key=?", Long.class, SEASON.code(),
                        CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY));

        var planner = inputs.load(SEASON);
        var fixture = planner.fixtures().stream().filter(row -> row.id() == FIXTURE_ID).findFirst().orElseThrow();
        assertThat(fixture.recoverySourceStatus()).isEqualTo(SourceStatus.UNKNOWN);
        assertThat(jdbc.queryForObject("SELECT finished_parent_generation FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", Long.class, SEASON.id(), FIXTURE_ID)).isNull();
        assertThat(jdbc.queryForObject("SELECT first_finished_observed_at FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", Timestamp.class, SEASON.id(), FIXTURE_ID).toInstant())
                .isEqualTo(firstFinished);

        List<Endpoint> fetched = new ArrayList<>();
        var publisher = new FixtureSyncPublisher(jdbc, clock, store,
                new FixtureSyncRepository(jdbc, TABLES), TABLES);
        var dispatcher = new HistoryFixtureDispatcher(store, publisher, (endpoint, failureAttempt) -> {
            fetched.add(endpoint);
            return new DeferredResult(clock.instant().plusSeconds(1), DeferredReason.MINIMUM_SPACING);
        }, clock);
        Run fixtureRun = dispatcher.enqueueFixtureVerification(SEASON, FIXTURE_ID, clock.instant());
        Lease lease = store.acquireForSeason(SEASON.code(), "missing-parent-test", Duration.ofMinutes(5))
                .orElseThrow();
        assertThat(lease.generation()).isEqualTo(fixtureRun.generation());

        DispatchResult result = dispatcher.process(lease, SEASON);

        assertThat(result.failure()).isEqualTo(FailureType.HTTP_DEFERRED);
        assertThat(fetched).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE season=? AND kind='CATALOGUE_SCHEDULE' AND runtime_mode='ACTIVE'"
                + " AND runtime_epoch=? AND state='PENDING'", Integer.class, SEASON.code(), fence.epoch())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT recovery_started_at FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", Timestamp.class, SEASON.id(), FIXTURE_ID)).isNull();
    }

    @Test
    void activeSelectionSkipsInvalidPublicationAndChoosesAnEligibleFallback() {
        List<PublicationFault> faults = List.of(PublicationFault.OWNER_MODE, PublicationFault.OWNER_EPOCH,
                PublicationFault.DONOR_MODE, PublicationFault.DONOR_EPOCH, PublicationFault.DELETED_PAYLOAD,
                PublicationFault.INVALID_PAYLOAD, PublicationFault.FUTURE_PUBLICATION,
                PublicationFault.MISMATCHED_SOURCE_ID, PublicationFault.MISMATCHED_HASH,
                PublicationFault.INCOMPLETE_PUBLICATION_SPAN, PublicationFault.OWNER_SOURCE_FLOOR,
                PublicationFault.DONOR_SOURCE_FLOOR);
        for (PublicationFault fault : faults) {
            clearOwnedRows(false);
            clock.set(BASE);
            store = new OwnedSyncStore(jdbc, tx, clock, TABLES, fence);
            boolean floorFault = fault == PublicationFault.OWNER_SOURCE_FLOOR
                    || fault == PublicationFault.DONOR_SOURCE_FLOOR;
            PairSeed invalid = seedPublishedPair(SMALL_BOOTSTRAP, SMALL_FIXTURES, BASE.plusSeconds(60),
                    Lane.PRICE, Lane.SCHEDULE, fault == PublicationFault.DONOR_SOURCE_FLOOR);
            if (floorFault) {
                Lane demandedLane = fault == PublicationFault.OWNER_SOURCE_FLOOR ? Lane.SCHEDULE : Lane.PRICE;
                Run demand = store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(),
                        demandedLane.coalescingKey(), demandedLane.publicationKey(),
                        clock.instant().plusSeconds(1));
                if (fault == PublicationFault.DONOR_SOURCE_FLOOR) {
                    assertThat(demand.generation()).as("fault=%s", fault)
                            .isEqualTo(invalid.sourceGeneration());
                } else {
                    assertThat(demand.generation()).as("fault=%s", fault)
                            .isGreaterThan(invalid.publicationGeneration());
                }
            } else {
                fault.apply(invalid);
            }
            Instant fallbackRead = floorFault ? BASE.plusSeconds(64) : BASE.plusSeconds(55);
            PairSeed fallback = fault == PublicationFault.DONOR_SOURCE_FLOOR
                    ? publishFreshPairOnPendingDonor(SMALL_BOOTSTRAP, SMALL_FIXTURES, invalid, fallbackRead)
                    : seedPublishedPair(SMALL_BOOTSTRAP, SMALL_FIXTURES, fallbackRead,
                            Lane.SCHEDULE, Lane.PRICE, false);
            assertThat(fallback.sourceGeneration()).isNotEqualTo(fallback.publicationGeneration());
            clock.set(BASE.plusSeconds(100));

            var selected = store.findInLatestCurrentPublishedCanonicalPairs(SEASON.code(), 20, clock.instant(),
                    candidate -> Optional.of(candidate.publication()));

            assertThat(selected).as("fault=%s", fault).isPresent();
            assertThat(selected.orElseThrow().sourceGeneration()).as("fault=%s", fault)
                    .isEqualTo(fallback.sourceGeneration());
            assertThat(selected.orElseThrow().publicationGeneration()).as("fault=%s", fault)
                    .isEqualTo(fallback.publicationGeneration());
        }
    }

    @Test
    void currentActivePublicationMayReuseAValidPendingDonorPair() {
        PairSeed published = seedPublishedPair(bootstrap, fixtures, BASE.plusSeconds(10),
                Lane.SCHEDULE, Lane.PRICE, true);

        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run() + " WHERE generation=?",
                String.class, published.sourceGeneration())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run() + " WHERE generation=?",
                String.class, published.publicationGeneration())).isEqualTo("COMPLETED");
        var selected = store.findInLatestCurrentPublishedCanonicalPairs(SEASON.code(), 20, clock.instant(),
                candidate -> Optional.of(candidate));
        assertThat(selected).isPresent();
        assertThat(selected.orElseThrow().sourcePair()).isEqualTo(published.sourcePair());
        assertThat(selected.orElseThrow().publication().publicationGeneration())
                .isEqualTo(published.publicationGeneration());
        assertThat(store.currentPublishedCanonicalPair(SEASON.code(), published.sourcePair(), clock.instant()))
                .contains(new OwnedSyncStore.CanonicalPublication(published.sourceGeneration(),
                        published.publicationGeneration(), published.sourcePair().fingerprint()));

        new PlannerInputRepository(jdbc, store, TABLES, CAPTURED, clock).load(SEASON);

        assertMarker(published);
        assertThat(store.currentCanonicalParentEvidence(SEASON.code(), FIXTURE_ID, clock.instant()))
                .isEqualTo(new OwnedSyncStore.CanonicalParentEvidence(true, true));
    }

    @Test
    void advancingPendingDonorDemandInvalidatesOnlyItsOldPublicationEvidence() {
        PairSeed published = seedPublishedPair(bootstrap, fixtures, BASE.plusSeconds(10),
                Lane.SCHEDULE, Lane.PRICE, true);
        PayloadReadProbe probe = new PayloadReadProbe(jdbc.getDataSource());
        store = new OwnedSyncStore(probe, tx, clock, TABLES, fence);
        Instant demandedAfter = clock.instant().plusSeconds(10);

        Run coalesced = store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(), Lane.SCHEDULE.coalescingKey(),
                Lane.SCHEDULE.publicationKey(), demandedAfter);

        assertThat(coalesced.generation()).isEqualTo(published.sourceGeneration());
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run() + " WHERE generation=?",
                String.class, published.sourceGeneration())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT requested_source_after FROM " + TABLES.run()
                + " WHERE generation=?", Timestamp.class, published.sourceGeneration()).toInstant())
                .isEqualTo(demandedAfter);
        assertReceiptStillPublished(published);
        assertNoCurrentCanonicalPair(published);
        assertThat(probe.snapshot().bodyValues()).isZero();
    }

    @Test
    void laterCompletedDonorLaneDemandInvalidatesTheOldReceipt() {
        PairSeed published = seedPublishedPair(bootstrap, fixtures, BASE.plusSeconds(10),
                Lane.SCHEDULE, Lane.PRICE, false);
        clock.set(clock.instant().plusMillis(1));
        Instant demandedAfter = clock.instant().plusSeconds(10);

        Run later = store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(), Lane.SCHEDULE.coalescingKey(),
                Lane.SCHEDULE.publicationKey(), demandedAfter);

        assertThat(later.generation()).isGreaterThan(published.sourceGeneration());
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run() + " WHERE generation=?",
                String.class, later.generation())).isEqualTo("PENDING");
        assertReceiptStillPublished(published);
        assertNoCurrentCanonicalPair(published);
    }

    @Test
    void laterCompletedPublisherLaneDemandInvalidatesItsStillLatestReceipt() {
        PairSeed published = seedPublishedPair(bootstrap, fixtures, BASE.plusSeconds(10),
                Lane.SCHEDULE, Lane.PRICE, false);
        clock.set(clock.instant().plusMillis(1));
        Instant demandedAfter = clock.instant().plusSeconds(10);

        Run later = store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(), Lane.PRICE.coalescingKey(),
                Lane.PRICE.publicationKey(), demandedAfter);

        assertThat(later.generation()).isGreaterThan(published.publicationGeneration());
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run() + " WHERE generation=?",
                String.class, later.generation())).isEqualTo("PENDING");
        assertReceiptStillPublished(published);
        assertNoCurrentCanonicalPair(published);
    }

    @Test
    void currentEpochAndFuturePublicationAreRequiredDespiteFirstFinishedObservation() {
        PairSeed published = seedPublishedPair(bootstrap, fixtures, BASE.plusSeconds(10),
                Lane.SCHEDULE, Lane.PRICE, false);
        new PlannerInputRepository(jdbc, store, TABLES, CAPTURED, clock).load(SEASON);
        Timestamp firstFinished = jdbc.queryForObject("SELECT first_finished_observed_at FROM "
                + TABLES.fixtureSync() + " WHERE season_id=? AND fixture_id=?", Timestamp.class,
                SEASON.id(), FIXTURE_ID);
        assertThat(firstFinished).isNotNull();
        jdbc.update("UPDATE " + TABLES.fixtureSync() + " SET finished_parent_generation=NULL,"
                + "finished_parent_publication_generation=NULL, finished_parent_bootstrap_payload_id=NULL,"
                + "finished_parent_fixtures_payload_id=NULL, finished_parent_source_fingerprint=NULL,"
                + "finished_parent_fixture_fingerprint=NULL, finished_parent_finished=NULL"
                + " WHERE season_id=? AND fixture_id=?", SEASON.id(), FIXTURE_ID);
        jdbc.update("UPDATE " + TABLES.run() + " SET runtime_epoch=runtime_epoch-1 WHERE generation=?",
                published.publicationGeneration());

        assertThat(store.currentCanonicalParentEvidence(SEASON.code(), FIXTURE_ID, clock.instant()))
                .isEqualTo(OwnedSyncStore.CanonicalParentEvidence.MISSING);
        assertFirstFinishedRemainsWithoutParent(firstFinished);

        jdbc.update("UPDATE " + TABLES.run() + " SET runtime_epoch=? WHERE generation=?",
                fence.epoch(), published.publicationGeneration());
        jdbc.update("UPDATE " + TABLES.receipt() + " SET published_at=? WHERE season=? AND publication_key=?",
                timestamp(clock.instant().plus(Duration.ofDays(1))), SEASON.code(),
                published.publicationLane().publicationKey());

        assertThat(store.currentCanonicalParentEvidence(SEASON.code(), FIXTURE_ID, clock.instant()))
                .isEqualTo(OwnedSyncStore.CanonicalParentEvidence.MISSING);
        assertFirstFinishedRemainsWithoutParent(firstFinished);
    }

    @Test
    void stagingStillDiscoversNewestValidUnpublishedPair() {
        fence = activate(OwnedSyncMode.STAGING);
        store = new OwnedSyncStore(jdbc, tx, clock, TABLES, fence);
        seedUnpublishedPair(bootstrap, fixtures, BASE.plusSeconds(10), Lane.SCHEDULE);
        PairSeed newer = seedUnpublishedPair(bootstrap, fixtures, BASE.plusSeconds(60), Lane.PRICE);

        var planner = new PlannerInputRepository(jdbc, store, TABLES, CAPTURED, clock).load(SEASON);
        var fixture = planner.fixtures().stream().filter(row -> row.id() == FIXTURE_ID).findFirst().orElseThrow();

        assertThat(planner.ownedDiscovery()).isTrue();
        assertThat(fixture.sourceObservedAt()).isEqualTo(newer.fixturesRead().finishedAt());
        assertThat(fixture.recoverySourceStatus()).isEqualTo(SourceStatus.UNKNOWN);
    }

    private PairSeed seedPublishedPair(byte[] bootstrapBody, byte[] fixtureBody, Instant readStarted,
                                       Lane sourceLane, Lane publicationLane, boolean pendingDonor) {
        if (sourceLane == publicationLane) throw new IllegalArgumentException("source and publisher lanes differ");
        Instant floor = readStarted.minusSeconds(10);
        clock.set(floor);
        Run sourceRun = store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(), sourceLane.coalescingKey(),
                sourceLane.publicationKey(), floor);
        Run publicationRun = store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(),
                publicationLane.coalescingKey(), publicationLane.publicationKey(), floor);
        Lease sourceLease = store.acquireForSeason(SEASON.code(), "source-seed", Duration.ofMinutes(5))
                .orElseThrow();
        assertThat(sourceLease.generation()).isEqualTo(sourceRun.generation());

        SourceRead bootstrapRead = new SourceRead(readStarted, readStarted.plusMillis(100));
        SourceRead fixturesRead = new SourceRead(readStarted.plusSeconds(2), readStarted.plusMillis(2100));
        clock.set(fixturesRead.finishedAt().plusMillis(1));
        long bootstrapId = store.stage(sourceLease, Endpoint.bootstrap(), bootstrapRead,
                Validation.unchecked(), bootstrapBody);
        long fixturesId = store.stage(sourceLease, Endpoint.fixtures(), fixturesRead,
                Validation.unchecked(), fixtureBody);
        store.validateStagedPayloads(sourceLease, List.of(bootstrapId, fixturesId), Validation.valid());
        CanonicalSourcePair sourcePair = CanonicalSourcePair.from(
                staged(bootstrapId, sourceRun.generation(), EndpointKind.BOOTSTRAP_STATIC,
                        bootstrapRead, bootstrapBody),
                staged(fixturesId, sourceRun.generation(), EndpointKind.FIXTURES,
                        fixturesRead, fixtureBody));
        SourceRead combined = new SourceRead(bootstrapRead.startedAt(), fixturesRead.finishedAt());
        if (pendingDonor) {
            store.retry(sourceLease, clock.instant().plus(Duration.ofDays(1)), "valid pending source donor");
        } else {
            store.completeStaged(sourceLease, combined, List.of(bootstrapRead, fixturesRead));
        }

        Lease publicationLease = store.acquireForSeason(SEASON.code(), "publication-seed", Duration.ofMinutes(5))
                .orElseThrow();
        assertThat(publicationLease.generation()).isEqualTo(publicationRun.generation());
        store.publish(publicationLease, combined, List.of(bootstrapRead, fixturesRead), sourcePair, () -> null);
        return new PairSeed(sourceRun.generation(), publicationRun.generation(), bootstrapId, fixturesId,
                publicationLane, sourcePair, bootstrapRead, fixturesRead, bootstrapBody, fixtureBody);
    }

    private PairSeed publishFreshPairOnPendingDonor(byte[] bootstrapBody, byte[] fixtureBody,
                                                     PairSeed pendingDonor, Instant readStarted) {
        Instant floor = readStarted.minusSeconds(10);
        clock.set(floor);
        Run sourceRun = store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(), Lane.SCHEDULE.coalescingKey(),
                Lane.SCHEDULE.publicationKey(), floor);
        Lease sourceLease = store.acquireForSeason(SEASON.code(), "fresh-fallback-source", Duration.ofMinutes(5))
                .orElseThrow();
        assertThat(sourceLease.generation()).isEqualTo(sourceRun.generation());
        SourceRead bootstrapRead = new SourceRead(readStarted, readStarted.plusMillis(100));
        SourceRead fixturesRead = new SourceRead(readStarted.plusSeconds(2), readStarted.plusMillis(2100));
        clock.set(fixturesRead.finishedAt().plusMillis(1));
        long bootstrapId = store.stage(sourceLease, Endpoint.bootstrap(), bootstrapRead,
                Validation.valid(), bootstrapBody);
        long fixturesId = store.stage(sourceLease, Endpoint.fixtures(), fixturesRead,
                Validation.valid(), fixtureBody);
        SourceRead combined = new SourceRead(bootstrapRead.startedAt(), fixturesRead.finishedAt());
        store.completeStaged(sourceLease, combined, List.of(bootstrapRead, fixturesRead));
        CanonicalSourcePair sourcePair = CanonicalSourcePair.from(
                staged(bootstrapId, sourceRun.generation(), EndpointKind.BOOTSTRAP_STATIC,
                        bootstrapRead, bootstrapBody),
                staged(fixturesId, sourceRun.generation(), EndpointKind.FIXTURES,
                        fixturesRead, fixtureBody));
        Lease publicationLease = store.acquireForSeason(SEASON.code(), "fresh-fallback-publication",
                Duration.ofMinutes(5)).orElseThrow();
        assertThat(publicationLease.generation()).isEqualTo(pendingDonor.sourceGeneration());
        store.publish(publicationLease, combined, List.of(bootstrapRead, fixturesRead), sourcePair, () -> null);
        return new PairSeed(sourceRun.generation(), publicationLease.generation(), bootstrapId, fixturesId,
                Lane.PRICE, sourcePair, bootstrapRead, fixturesRead, bootstrapBody, fixtureBody);
    }

    private PairSeed seedUnpublishedPair(byte[] bootstrapBody, byte[] fixtureBody, Instant readStarted, Lane lane) {
        Instant floor = readStarted.minusSeconds(10);
        clock.set(floor);
        Run sourceRun = store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(), lane.coalescingKey(),
                lane.publicationKey(), floor);
        Lease lease = store.acquireForSeason(SEASON.code(), "unpublished-seed", Duration.ofMinutes(5))
                .orElseThrow();
        assertThat(lease.generation()).isEqualTo(sourceRun.generation());
        SourceRead bootstrapRead = new SourceRead(readStarted, readStarted.plusMillis(100));
        SourceRead fixturesRead = new SourceRead(readStarted.plusSeconds(2), readStarted.plusMillis(2100));
        clock.set(fixturesRead.finishedAt().plusMillis(1));
        long bootstrapId = store.stage(lease, Endpoint.bootstrap(), bootstrapRead,
                Validation.unchecked(), bootstrapBody);
        long fixturesId = store.stage(lease, Endpoint.fixtures(), fixturesRead,
                Validation.unchecked(), fixtureBody);
        store.validateStagedPayloads(lease, List.of(bootstrapId, fixturesId), Validation.valid());
        SourceRead combined = new SourceRead(bootstrapRead.startedAt(), fixturesRead.finishedAt());
        store.completeStaged(lease, combined, List.of(bootstrapRead, fixturesRead));
        CanonicalSourcePair sourcePair = CanonicalSourcePair.from(
                staged(bootstrapId, sourceRun.generation(), EndpointKind.BOOTSTRAP_STATIC,
                        bootstrapRead, bootstrapBody),
                staged(fixturesId, sourceRun.generation(), EndpointKind.FIXTURES,
                        fixturesRead, fixtureBody));
        return new PairSeed(sourceRun.generation(), 0, bootstrapId, fixturesId,
                null, sourcePair, bootstrapRead, fixturesRead, bootstrapBody, fixtureBody);
    }

    private void assertMarker(PairSeed expected) {
        var marker = jdbc.queryForMap("SELECT finished_parent_generation,"
                + "finished_parent_publication_generation, finished_parent_bootstrap_payload_id,"
                + "finished_parent_fixtures_payload_id, finished_parent_source_fingerprint,"
                + "finished_parent_fixture_fingerprint, finished_parent_finished FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", SEASON.id(), FIXTURE_ID);
        assertThat(((Number) marker.get("finished_parent_generation")).longValue())
                .isEqualTo(expected.sourceGeneration());
        assertThat(((Number) marker.get("finished_parent_publication_generation")).longValue())
                .isEqualTo(expected.publicationGeneration());
        assertThat(((Number) marker.get("finished_parent_bootstrap_payload_id")).longValue())
                .isEqualTo(expected.bootstrapId());
        assertThat(((Number) marker.get("finished_parent_fixtures_payload_id")).longValue())
                .isEqualTo(expected.fixturesId());
        assertThat(marker.get("finished_parent_source_fingerprint")).isEqualTo(expected.sourcePair().fingerprint());
        assertThat(marker.get("finished_parent_fixture_fingerprint")).isEqualTo(fixtureFingerprint(expected));
        assertThat(marker.get("finished_parent_finished")).isEqualTo(true);
    }

    private void assertReceiptStillPublished(PairSeed expected) {
        Map<String, Object> receipt = jdbc.queryForMap("SELECT latest_generation, published_at FROM "
                + TABLES.receipt() + " WHERE season=? AND publication_key=?",
                SEASON.code(), expected.publicationLane().publicationKey());
        assertThat(((Number) receipt.get("latest_generation")).longValue())
                .isEqualTo(expected.publicationGeneration());
        assertThat(receipt.get("published_at")).isNotNull();
    }

    private void assertNoCurrentCanonicalPair(PairSeed expected) {
        assertThat(store.currentPublishedCanonicalPair(SEASON.code(), expected.sourcePair(), clock.instant()))
                .isEmpty();
        assertThat(store.findInLatestCurrentPublishedCanonicalPairs(SEASON.code(), 20, clock.instant(),
                candidate -> Optional.of(candidate.publication()))).isEmpty();
        assertThat(store.currentCanonicalParentEvidence(SEASON.code(), FIXTURE_ID, clock.instant()))
                .isEqualTo(OwnedSyncStore.CanonicalParentEvidence.MISSING);
    }

    private void assertFirstFinishedRemainsWithoutParent(Timestamp expected) {
        assertThat(jdbc.queryForObject("SELECT first_finished_observed_at FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", Timestamp.class, SEASON.id(), FIXTURE_ID))
                .isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT finished_parent_generation FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", Long.class, SEASON.id(), FIXTURE_ID)).isNull();
    }

    private String fixtureFingerprint(PairSeed pair) {
        var snapshot = CatalogueScheduleParser.parse(SEASON,
                new CatalogueScheduleParser.SourceDocument(pair.bootstrapBody(),
                        new CatalogueScheduleParser.ReadInterval(pair.bootstrapRead().startedAt(),
                                pair.bootstrapRead().finishedAt())),
                new CatalogueScheduleParser.SourceDocument(pair.fixtureBody(),
                        new CatalogueScheduleParser.ReadInterval(pair.fixturesRead().startedAt(),
                                pair.fixturesRead().finishedAt())));
        var fixture = snapshot.fixtures().stream().filter(row -> row.id() == FIXTURE_ID).findFirst().orElseThrow();
        return OwnedSyncStore.sha256(FixtureVerification.verifiedHeader(fixture));
    }

    private OwnedSyncStore.RuntimeFence activate(OwnedSyncMode mode) {
        return new PlannerRuntimeRepository(jdbc, tx, clock, TABLES).activate(SEASON.code(), mode);
    }

    private void clearOwnedRows(boolean includeRuntime) {
        String runtime = includeRuntime ? ", " + TABLES.plannerRuntime() : "";
        jdbc.execute("TRUNCATE " + TABLES.playerFixtureEvidence() + ", " + TABLES.fixtureSync() + ", "
                + TABLES.historyFingerprint() + ", " + TABLES.payload() + ", " + TABLES.run() + ", "
                + TABLES.receipt() + ", " + TABLES.plannerSlot() + ", " + TABLES.requestAttempt() + ", "
                + TABLES.requestState() + ", " + TABLES.fixtureDependencyManifest() + runtime + " CASCADE");
        jdbc.execute("TRUNCATE " + CAPTURED.dailyPrice() + ", " + CAPTURED.gameweek() + ", "
                + CAPTURED.fixture());
        jdbc.update("INSERT INTO " + TABLES.requestState() + "(singleton) VALUES (TRUE) ON CONFLICT DO NOTHING");
    }

    private static void createCapturedTables() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + CAPTURED.dailyPrice() + "(season varchar(10),"
                + "price_date date,element_id integer,team_id integer,PRIMARY KEY(season,price_date,element_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + CAPTURED.gameweek()
                + "(season varchar(10),id integer,data_checked boolean,PRIMARY KEY(season,id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + CAPTURED.fixture()
                + "(season varchar(10),id integer,gameweek integer,team_h integer,team_a integer,"
                + "kickoff_time timestamptz,started boolean,finished boolean,captured_at timestamptz,"
                + "PRIMARY KEY(season,id))");
    }

    private static byte[] withFixtureOneUnfinished(byte[] source) throws Exception {
        JsonNode rows = JSON.readTree(source);
        for (JsonNode row : rows) {
            if (row.get("id").intValue() == FIXTURE_ID) {
                ((ObjectNode) row).put("finished", false).put("finished_provisional", false)
                        .put("started", false).put("minutes", 0).putNull("team_h_score").putNull("team_a_score");
                return JSON.writeValueAsBytes(rows);
            }
        }
        throw new IllegalStateException("Fixture 1 is missing from the test source");
    }

    private static StagedPayload staged(long id, long generation, EndpointKind endpoint, SourceRead read,
                                        byte[] body) {
        return new StagedPayload(id, generation, endpoint, read, ValidationState.VALID, null, body);
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream input = OwnedPublishedParentSelectionIntegrationTest.class.getResourceAsStream(
                "/owned-sync-source-fixtures/" + name)) {
            if (input == null) throw new IllegalArgumentException("Missing " + name);
            return input.readAllBytes();
        }
    }

    private static Timestamp timestamp(Instant instant) { return Timestamp.from(instant); }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private record PairSeed(long sourceGeneration, long publicationGeneration, long bootstrapId, long fixturesId,
                            Lane publicationLane, CanonicalSourcePair sourcePair,
                            SourceRead bootstrapRead, SourceRead fixturesRead,
                            byte[] bootstrapBody, byte[] fixtureBody) { }

    private enum Lane {
        PRICE(CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY),
        SCHEDULE(CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY);

        private final String coalescingKey;
        private final String publicationKey;

        Lane(String coalescingKey, String publicationKey) {
            this.coalescingKey = coalescingKey;
            this.publicationKey = publicationKey;
        }

        String coalescingKey() { return coalescingKey; }
        String publicationKey() { return publicationKey; }
    }

    private enum PublicationFault {
        OWNER_MODE, OWNER_EPOCH, DONOR_MODE, DONOR_EPOCH, DELETED_PAYLOAD, INVALID_PAYLOAD,
        FUTURE_PUBLICATION, MISMATCHED_SOURCE_ID, MISMATCHED_HASH, INCOMPLETE_PUBLICATION_SPAN,
        OWNER_SOURCE_FLOOR, DONOR_SOURCE_FLOOR;

        void apply(PairSeed pair) {
            switch (this) {
                case OWNER_MODE -> jdbc.update("UPDATE " + TABLES.run()
                        + " SET runtime_mode='STAGING' WHERE generation=?", pair.publicationGeneration());
                case OWNER_EPOCH -> jdbc.update("UPDATE " + TABLES.run()
                        + " SET runtime_epoch=? WHERE generation=?", pairEpoch(pair) - 1,
                        pair.publicationGeneration());
                case DONOR_MODE -> jdbc.update("UPDATE " + TABLES.run()
                        + " SET runtime_mode='STAGING' WHERE generation=?", pair.sourceGeneration());
                case DONOR_EPOCH -> jdbc.update("UPDATE " + TABLES.run()
                        + " SET runtime_epoch=? WHERE generation=?", pairEpoch(pair) - 1,
                        pair.sourceGeneration());
                case DELETED_PAYLOAD -> jdbc.update("DELETE FROM " + TABLES.payload()
                        + " WHERE id IN (?,?)", pair.bootstrapId(), pair.fixturesId());
                case INVALID_PAYLOAD -> jdbc.update("UPDATE " + TABLES.payload()
                        + " SET validation_state='INVALID' WHERE id=?", pair.bootstrapId());
                case FUTURE_PUBLICATION -> jdbc.update("UPDATE " + TABLES.receipt()
                        + " SET published_at=? WHERE season=? AND publication_key=?",
                        timestamp(BASE.plusSeconds(10_000)), SEASON.code(), pair.publicationLane().publicationKey());
                case MISMATCHED_SOURCE_ID -> jdbc.update("UPDATE " + TABLES.receipt()
                        + " SET source_fixtures_payload_id=-1 WHERE season=? AND publication_key=?",
                        SEASON.code(), pair.publicationLane().publicationKey());
                case MISMATCHED_HASH -> jdbc.update("UPDATE " + TABLES.receipt()
                        + " SET source_bootstrap_sha256=repeat('0',64) WHERE season=? AND publication_key=?",
                        SEASON.code(), pair.publicationLane().publicationKey());
                case INCOMPLETE_PUBLICATION_SPAN -> {
                    Instant tooEarly = pair.fixturesRead().finishedAt().minusMillis(1);
                    jdbc.update("UPDATE " + TABLES.run() + " SET source_read_finished_at=? WHERE generation=?",
                            timestamp(tooEarly), pair.publicationGeneration());
                    jdbc.update("UPDATE " + TABLES.receipt() + " SET source_read_finished_at=?"
                                    + " WHERE season=? AND publication_key=?",
                            timestamp(tooEarly), SEASON.code(), pair.publicationLane().publicationKey());
                }
                case OWNER_SOURCE_FLOOR, DONOR_SOURCE_FLOOR ->
                        throw new AssertionError("source-floor variants advance demand through enqueue");
            }
        }

        private static long pairEpoch(PairSeed pair) {
            return jdbc.queryForObject("SELECT runtime_epoch FROM " + TABLES.run()
                    + " WHERE generation=?", Long.class, pair.publicationGeneration());
        }
    }

    private static final class MutableClock extends Clock {
        private Instant value;

        private MutableClock(Instant value) { this.value = value; }
        void set(Instant instant) { value = instant; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
    }
}
