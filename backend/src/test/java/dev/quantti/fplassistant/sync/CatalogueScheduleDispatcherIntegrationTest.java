package dev.quantti.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import dev.quantti.fplassistant.sync.CatalogueScheduleDispatcher.Outcome;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.Season;
import dev.quantti.fplassistant.sync.OwnedSyncHttpClient.DeferredResult;
import dev.quantti.fplassistant.sync.OwnedSyncHttpClient.Failure;
import dev.quantti.fplassistant.sync.OwnedSyncHttpClient.FailureKind;
import dev.quantti.fplassistant.sync.OwnedSyncHttpClient.Response;
import dev.quantti.fplassistant.sync.OwnedSyncRequestBudget.DeferredReason;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Endpoint;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Lease;
import dev.quantti.fplassistant.sync.OwnedSyncStore.LeaseLostException;
import dev.quantti.fplassistant.sync.OwnedSyncStore.SourceRead;
import dev.quantti.fplassistant.sync.OwnedSyncStore.SyncKind;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Validation;
import dev.quantti.fplassistant.testsupport.TapasSeed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class CatalogueScheduleDispatcherIntegrationTest {

    private static final OwnedSyncTables OWNED = OwnedSyncTables.tests();
    private static final CatalogueScheduleTables CAPTURED = CatalogueScheduleTables.tests();
    private static final Season SEASON = new Season(27, "2026-27", LocalDate.of(2026, 8, 21), null);
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static byte[] bootstrap;
    private static byte[] fixtures;

    private MutableClock clock;
    private OwnedSyncStore store;
    private FakeFetch fetch;
    private CatalogueScheduleDispatcher dispatcher;

    @BeforeAll
    static void initialize() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(required("DATABASE_URL"),
                required("DB_USER"), required("DB_PASSWORD"));
        TapasSeed.ensure(new JdbcTemplate(dataSource));
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        new OwnedSyncSchema(jdbc, OWNED).initialize();
        createCapturedTables();
        bootstrap = resource("bootstrap-static.json");
        fixtures = resource("fixtures.json");
    }

    @BeforeEach
    void setUp() {
        clean();
        jdbc.update("UPDATE season SET is_current=false");
        jdbc.update("""
                INSERT INTO season(id, code, name, start_date, end_date, is_current)
                VALUES (27, '2026-27', '2026/27', DATE '2026-08-21', NULL, true)
                ON CONFLICT (id) DO UPDATE SET code=EXCLUDED.code, name=EXCLUDED.name,
                    start_date=EXCLUDED.start_date, end_date=EXCLUDED.end_date, is_current=true
                """);
        clock = new MutableClock(Instant.parse("2026-09-13T18:00:00Z"));
        store = new OwnedSyncStore(jdbc, tx, clock, OWNED);
        fetch = new FakeFetch();
        dispatcher = dispatcher(CAPTURED);
    }

    @AfterEach
    void restoreSeason() {
        clean();
        jdbc.update("DELETE FROM season WHERE id=27");
        jdbc.update("UPDATE season SET is_current=(id=?)", TapasSeed.CURRENT_SEASON_ID);
    }

    @Test
    void stagingValidatesAndStoresPayloadsWithoutCoreCaptureOrFixtureStateWrites() {
        activateStagingStore();
        dispatcher = new CatalogueScheduleDispatcher(store, null, fetch, clock, false);
        int teams = jdbc.queryForObject("SELECT count(*) FROM team WHERE season_id=27", Integer.class);
        int players = jdbc.queryForObject("SELECT count(*) FROM player WHERE season_id=27", Integer.class);
        var cycle = dispatcher.enqueueCycle(SEASON, clock.instant());
        Lease[] leases = acquireBoth();

        assertThat(dispatcher.process(forGeneration(leases, cycle.cataloguePrice().generation()), SEASON))
                .isEqualTo(Outcome.STAGED);
        assertThat(dispatcher.process(forGeneration(leases, cycle.schedule().generation()), SEASON))
                .isEqualTo(Outcome.STAGED);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM team WHERE season_id=27", Integer.class)).isEqualTo(teams);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player WHERE season_id=27", Integer.class)).isEqualTo(players);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + CAPTURED.dailyPrice(), Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + CAPTURED.fixture(), Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.fixtureSync(), Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE validation_state='VALID'", Integer.class)).isEqualTo(2);
    }

    @Test
    void twoCanonicalGroupsShareOnePostDemandPairButANewCycleFetchesAgain() {
        var cycle = dispatcher.enqueueCycle(SEASON, clock.instant());
        Lease[] leases = acquireBoth();

        assertThat(dispatcher.process(forGeneration(leases, cycle.cataloguePrice().generation()), SEASON))
                .isEqualTo(Outcome.PUBLISHED);
        assertThat(dispatcher.process(forGeneration(leases, cycle.schedule().generation()), SEASON))
                .isEqualTo(Outcome.PUBLISHED);
        assertThat(fetch.calls).isEqualTo(2);

        clock.advance(Duration.ofMinutes(1));
        var next = dispatcher.enqueueCycle(SEASON, clock.instant());
        Lease[] nextLeases = acquireBoth();
        assertThat(dispatcher.process(forGeneration(nextLeases, next.cataloguePrice().generation()), SEASON))
                .isEqualTo(Outcome.PUBLISHED);
        assertThat(fetch.calls).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + CAPTURED.dailyPrice()
                + " WHERE season='2026-27'", Integer.class)).isEqualTo(657);
    }

    @Test
    void activeHttpPublicationReloadsFingerprintAcrossPostgresMicrosecondRounding() throws Exception {
        Instant httpStart = clock.instant().minusSeconds(2).with(java.time.temporal.ChronoField.NANO_OF_SECOND,
                999_999_499);
        clock.set(httpStart);
        activateStore(OwnedSyncMode.ACTIVE);
        enqueueSchedule();
        Lease lease = acquireOne(Duration.ofMinutes(5));
        Instant httpFinished = httpStart.plusNanos(1);

        try (Loopback loopback = loopbackWithFplBodies(() -> clock.set(httpFinished))) {
            dispatcher = dispatcher(fencedHttpFetch(loopback, request -> {
                if (request.endpoint().equals(Endpoint.fixtures())) clock.set(httpFinished.plusSeconds(2));
            }));
            Outcome outcome = dispatcher.process(lease, SEASON);
            assertThat(outcome).withFailMessage("runs=%s", jdbc.queryForList("SELECT state,last_error FROM "
                    + OWNED.run() + " WHERE generation=?", lease.generation())).isEqualTo(Outcome.PUBLISHED);
            assertThat(loopback.requests).hasValue(2);
        }

        var source = store.findInLatestValidPublicPayloadPairs(SEASON.code(), 20,
                pair -> java.util.Optional.of(pair)).orElseThrow();
        var sourcePair = OwnedSyncStore.CanonicalSourcePair.from(source.bootstrap(), source.fixtures());
        Instant roundedStart = OwnedSyncStore.postgresTimestampPrecision(httpStart);
        Instant roundedFinish = OwnedSyncStore.postgresTimestampPrecision(httpFinished);
        assertThat(roundedStart).isBefore(roundedFinish);
        assertThat(jdbc.queryForObject("SELECT source_bootstrap_read_started_at FROM " + OWNED.receipt()
                + " WHERE season=? AND publication_key=?", java.sql.Timestamp.class,
                SEASON.code(), CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY).toInstant())
                .isEqualTo(roundedStart);
        assertThat(jdbc.queryForObject("SELECT source_bootstrap_read_finished_at FROM " + OWNED.receipt()
                + " WHERE season=? AND publication_key=?", java.sql.Timestamp.class,
                SEASON.code(), CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY).toInstant())
                .isEqualTo(roundedFinish);
        assertThat(jdbc.queryForObject("SELECT source_pair_fingerprint FROM " + OWNED.receipt()
                + " WHERE season=? AND publication_key=?", String.class,
                SEASON.code(), CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY)).isEqualTo(sourcePair.fingerprint());
        assertThat(store.isCurrentPublishedCanonicalPair(SEASON.code(), source.runGeneration(),
                sourcePair, clock.instant())).isTrue();

        new PlannerInputRepository(jdbc, store, OWNED, CAPTURED, clock).load(SEASON);
        assertThat(store.currentCanonicalParentEvidence(SEASON.code(), 21, clock.instant()))
                .isEqualTo(new OwnedSyncStore.CanonicalParentEvidence(true, true));
    }

    @Test
    void coalescedDemandDuringFetchIsRecheckedAtPublicationAndForcesAFreshPair() {
        var cycle = dispatcher.enqueueCycle(SEASON, clock.instant());
        Lease catalogue = forGeneration(acquireBoth(), cycle.cataloguePrice().generation());
        AtomicBoolean once = new AtomicBoolean();
        fetch.beforeResponse = endpoint -> {
            if (endpoint.equals(Endpoint.bootstrap()) && once.compareAndSet(false, true)) {
                clock.advance(Duration.ofSeconds(1));
                dispatcher.enqueueCycle(SEASON, clock.instant());
            }
        };

        assertThat(dispatcher.process(catalogue, SEASON)).isEqualTo(Outcome.PUBLISHED);
        assertThat(fetch.calls).isEqualTo(4);
        Instant floor = jdbc.queryForObject("SELECT requested_source_after FROM " + OWNED.run()
                + " WHERE generation=?", java.sql.Timestamp.class, catalogue.generation()).toInstant();
        Instant publishedRead = jdbc.queryForObject("SELECT source_read_started_at FROM " + OWNED.receipt()
                + " WHERE season='2026-27' AND publication_key='catalogue-price'",
                java.sql.Timestamp.class).toInstant();
        assertThat(publishedRead).isAfterOrEqualTo(floor);
    }

    @Test
    void futureCoalescedDemandDefersWithoutSpendingFetchBudget() {
        enqueueCatalogue();
        Lease lease = acquireOne(Duration.ofMinutes(5));
        Instant futureFloor = clock.instant().plus(Duration.ofHours(1));
        store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(),
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY, futureFloor);

        assertThat(dispatcher.process(lease, SEASON)).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertThat(fetch.calls).isZero();
        assertThat(jdbc.queryForObject("SELECT due_at FROM " + OWNED.run(),
                java.sql.Timestamp.class).toInstant()).isEqualTo(futureFloor);
        assertThat(store.acquire("too-early", Duration.ofMinutes(5))).isEmpty();
    }

    @Test
    void httpCallerFenceDefersWhenDemandAdvancesAfterSourceSelection() throws Exception {
        activateStagingStore();
        enqueueCatalogue();
        Lease lease = acquireOne(Duration.ofMinutes(2));
        Instant selectedFloor = store.requestedSourceAfter(lease);
        Instant advancedFloor = clock.instant().plus(Duration.ofHours(1));
        AtomicReference<HttpFetchRequest> observed = new AtomicReference<>();

        try (Loopback loopback = loopback()) {
            dispatcher = dispatcher(fencedHttpFetch(loopback, (request) -> {
                observed.set(request);
                store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(),
                        CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                        CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY, advancedFloor);
            }));

            assertThat(dispatcher.process(lease, SEASON)).isEqualTo(Outcome.RETRY_SCHEDULED);

            assertThat(observed.get().endpoint()).isEqualTo(Endpoint.bootstrap());
            assertThat(observed.get().sourceFloor()).isEqualTo(selectedFloor);
            assertThat(observed.get().lease().expiresAt()).isAfter(clock.instant().plusSeconds(180));
            assertNoHttpOrFetchFailure(loopback, lease.generation());
            assertThat(jdbc.queryForObject("SELECT due_at FROM " + OWNED.run()
                    + " WHERE generation=?", java.sql.Timestamp.class, lease.generation()).toInstant())
                    .isEqualTo(advancedFloor);
        }
    }

    @Test
    void httpCallerFenceRejectsReplacedEpochBeforeAdmission() throws Exception {
        activateStagingStore();
        Lease lease = enqueueAndAcquireCatalogue();
        try (Loopback loopback = loopback()) {
            dispatcher = dispatcher(fencedHttpFetch(loopback, request ->
                    new PlannerRuntimeRepository(jdbc, tx, clock, OWNED)
                            .activate(SEASON.code(), OwnedSyncMode.STAGING)));

            assertThatThrownBy(() -> dispatcher.process(lease, SEASON)).isInstanceOf(LeaseLostException.class);

            assertNoHttpOrFetchFailure(loopback, lease.generation());
        }
    }

    @Test
    void httpCallerFenceRejectsLeaseReassignmentWithoutRenewingTheExpiredLease() throws Exception {
        activateStagingStore();
        Lease lease = enqueueAndAcquireCatalogue();
        AtomicReference<Lease> replacement = new AtomicReference<>();
        try (Loopback loopback = loopback()) {
            dispatcher = dispatcher(fencedHttpFetch(loopback, request -> {
                clock.advance(Duration.ofMinutes(6));
                replacement.set(store.acquire("replacement", Duration.ofMinutes(5)).orElseThrow());
            }));

            assertThatThrownBy(() -> dispatcher.process(lease, SEASON)).isInstanceOf(LeaseLostException.class);

            assertThat(replacement.get().generation()).isEqualTo(lease.generation());
            assertThat(jdbc.queryForObject("SELECT lease_owner FROM " + OWNED.run()
                    + " WHERE generation=?", String.class, lease.generation())).isEqualTo("replacement");
            assertNoHttpOrFetchFailure(loopback, lease.generation());
        }
    }

    @Test
    void postAdmissionFenceInvalidationSettlesThePermitWithoutSendingOrStaging() throws Exception {
        activateStore(OwnedSyncMode.ACTIVE);
        Lease lease = enqueueAndAcquireCatalogue();
        try (Loopback loopback = loopback()) {
            installPostAdmissionEpochInvalidation();
            try {
                dispatcher = new CatalogueScheduleDispatcher(store,
                        new CatalogueScheduleWriter(jdbc, clock, store, OWNED, CAPTURED),
                        loopback.http, clock);

                assertThatThrownBy(() -> dispatcher.process(lease, SEASON))
                        .isInstanceOf(LeaseLostException.class);

                assertThat(loopback.requests).hasValue(0);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.requestAttempt(), Integer.class))
                        .isOne();
                assertThat(jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM "
                        + OWNED.requestAttempt(), Boolean.class)).isTrue();
                assertThat(jdbc.queryForObject("SELECT consecutive_fetch_failures FROM " + OWNED.run()
                        + " WHERE generation=?", Integer.class, lease.generation())).isZero();
                assertThat(payloadCount()).isZero();
            } finally {
                removePostAdmissionEpochInvalidation();
            }
        }
    }

    @Test
    void stagedPreCutoverPairSurvivesRetryAndRestartWithoutRefetch() {
        clock.set(Instant.parse("2026-09-13T00:30:00Z")); // 01:30 BST
        store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(),
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY, clock.instant());
        Lease first = acquireOne(Duration.ofMinutes(5));

        assertThat(dispatcher.process(first, SEASON)).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertThat(fetch.calls).isEqualTo(2);
        assertThat(payloadCount()).isEqualTo(2);

        clock.set(Instant.parse("2026-09-13T01:00:00Z"));
        dispatcher = dispatcher(CAPTURED); // process restart, same durable store/evidence
        assertThat(dispatcher.process(acquireOne(Duration.ofMinutes(5)), SEASON)).isEqualTo(Outcome.PUBLISHED);
        assertThat(fetch.calls).isEqualTo(2);
    }

    @Test
    void pendingScheduleSourceCanBePublishedByPriceLeaseAfterScheduleCutover() {
        PayloadReadProbe probe = new PayloadReadProbe(jdbc.getDataSource());
        activateStore(OwnedSyncMode.ACTIVE, probe);
        Instant priceDemandAt = Instant.parse("2026-09-13T01:00:00Z"); // 02:00 BST
        clock.set(priceDemandAt);
        var cycle = dispatcher.enqueueCycle(SEASON, priceDemandAt);
        clock.set(Instant.parse("2026-09-13T01:30:00Z")); // 02:30 BST
        Lease[] leases = acquireBoth();
        Lease schedule = forGeneration(leases, cycle.schedule().generation());
        Lease price = forGeneration(leases, cycle.cataloguePrice().generation());

        assertThat(dispatcher.process(schedule, SEASON)).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertThat(jdbc.queryForObject("SELECT state FROM " + OWNED.run()
                + " WHERE generation=?", String.class, schedule.generation())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT due_at FROM " + OWNED.run()
                + " WHERE generation=?", java.sql.Timestamp.class, schedule.generation()).toInstant())
                .isEqualTo(Instant.parse("2026-09-13T02:00:00Z")); // 03:00 BST
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE run_generation=? AND validation_state='VALID'", Integer.class,
                schedule.generation())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE run_generation=? AND source_published", Integer.class, schedule.generation())).isZero();

        assertThat(dispatcher.process(price, SEASON)).isEqualTo(Outcome.PUBLISHED);
        assertThat(fetch.calls).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT state FROM " + OWNED.run()
                + " WHERE generation=?", String.class, schedule.generation())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE run_generation=? AND source_published", Integer.class, schedule.generation())).isEqualTo(2);

        var source = store.findInLatestValidPublicPayloadPairs(SEASON.code(), 20,
                pair -> java.util.Optional.of(pair)).orElseThrow();
        var sourcePair = OwnedSyncStore.CanonicalSourcePair.from(source.bootstrap(), source.fixtures());
        assertThat(store.currentPublishedCanonicalPair(SEASON.code(), sourcePair, clock.instant()))
                .contains(new OwnedSyncStore.CanonicalPublication(schedule.generation(),
                        cycle.cataloguePrice().generation(), sourcePair.fingerprint()));
        var planned = new PlannerInputRepository(jdbc, store, OWNED, CAPTURED, clock).load(SEASON);
        var finishedFixture = planned.fixtures().stream().filter(fixture -> fixture.id() == 21)
                .findFirst().orElseThrow();
        assertThat(finishedFixture.recoverySourceStatus())
                .isEqualTo(SharedFixtureRecoveryPolicy.SourceStatus.FINISHED);
        assertThat(store.currentCanonicalParentEvidence(SEASON.code(), 21, clock.instant()))
                .isEqualTo(new OwnedSyncStore.CanonicalParentEvidence(true, true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.run(), Integer.class)).isEqualTo(2);
        assertThat(probe.snapshot().family(PayloadReadProbe.Family.CANONICAL_PAIR))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(1, 1, 2, bootstrap.length + (long) fixtures.length));
    }

    @Test
    void coherentPreCutoverPairRemainsReusableAfterMoreThanTenMinutesAtBothUkCutovers() {
        List<PublicationCase> cases = List.of(
                new PublicationCase(CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                        CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY, LocalTime.of(2, 0)),
                new PublicationCase(CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                        CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY, LocalTime.of(3, 0)));

        for (PublicationCase publication : cases) {
            cleanRunsAndCore();
            fetch.calls = 0;
            fetch.endpoints.clear();
            fetch.results.clear();
            clock.set(Instant.parse("2026-09-13T00:30:00Z")); // 01:30 BST
            PayloadReadProbe probe = installPayloadReadProbe();
            store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(), publication.coalescingKey(),
                    publication.publicationKey(), clock.instant());
            Lease first = acquireOne(Duration.ofHours(1));
            Instant sourceStarted = clock.instant();

            assertThat(dispatcher.process(first, SEASON)).isEqualTo(Outcome.RETRY_SCHEDULED);
            Instant cutoff = sourceStarted.atZone(ZoneId.of("Europe/London")).toLocalDate()
                    .atTime(publication.cutover()).atZone(ZoneId.of("Europe/London")).toInstant();
            assertThat(Duration.between(sourceStarted, cutoff)).isGreaterThan(Duration.ofMinutes(10));
            assertThat(fetch.calls).as("endpoints=%s state=%s error=%s", fetch.endpoints,
                    jdbc.queryForObject("SELECT state FROM " + OWNED.run(), String.class),
                    jdbc.queryForObject("SELECT last_error FROM " + OWNED.run(), String.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                    + " WHERE validation_state='VALID'", Integer.class)).isEqualTo(2);

            clock.set(cutoff);
            store = new OwnedSyncStore(probe, tx, clock, OWNED);
            dispatcher = dispatcher(CAPTURED); // cold process restart at the cutover
            assertThat(dispatcher.process(acquireOne(Duration.ofHours(1)), SEASON)).isEqualTo(Outcome.PUBLISHED);

            assertThat(fetch.calls).isEqualTo(2);
            assertThat(probe.snapshot().family(PayloadReadProbe.Family.GUARDED_SINGLE))
                    .isEqualTo(new PayloadReadProbe.FamilyMetrics(0, 0, 0, 0));
            assertThat(probe.snapshot().family(PayloadReadProbe.Family.CANONICAL_PAIR))
                    .isEqualTo(new PayloadReadProbe.FamilyMetrics(1, 1, 2, bootstrap.length + fixtures.length));
        }
    }

    @Test
    void malformedReusableValidPairIsSkippedForTheNextUsableCanonicalCandidate() {
        enqueueCatalogue();
        Lease target = acquireOne(Duration.ofHours(1));
        seedReusablePair(bootstrap, fixtures);
        clock.advance(Duration.ofSeconds(1));
        seedReusablePair("{}".getBytes(), fixtures);
        PayloadReadProbe probe = installPayloadReadProbe();

        assertThat(dispatcher.process(target, SEASON)).isEqualTo(Outcome.PUBLISHED);

        assertThat(fetch.calls).isZero();
        assertThat(probe.snapshot().family(PayloadReadProbe.Family.CANONICAL_PAIR))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(2, 2, 4,
                        bootstrap.length + 2L * fixtures.length + 2));
        assertThat(probe.snapshot().family(PayloadReadProbe.Family.GUARDED_SINGLE))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(0, 0, 0, 0));
    }

    @Test
    void delayedPartialRestartRefetchesInsteadOfPermanentlyFailingThePairWindow() {
        enqueueCatalogue();
        Lease first = acquireOne(Duration.ofMinutes(5));
        SourceRead bootstrapRead = new SourceRead(clock.instant(), clock.instant());
        store.stage(first, Endpoint.bootstrap(), bootstrapRead, Validation.unchecked(), bootstrap);
        store.retry(first, clock.instant(), "restart after first endpoint");
        clock.advance(Duration.ofMinutes(11));
        PayloadReadProbe probe = installPayloadReadProbe();

        assertThat(dispatcher.process(acquireOne(Duration.ofMinutes(5)), SEASON)).isEqualTo(Outcome.PUBLISHED);
        assertThat(fetch.calls).isEqualTo(3); // old bootstrap + fetched fixture was rejected, then both refreshed
        assertNoBodyReads(probe);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE validation_state='INVALID'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE validation_state='VALID'", Integer.class)).isEqualTo(2);
    }

    @Test
    void restartWithBothUncheckedBodiesOutsideTheWindowRefetchesBoth() {
        enqueueCatalogue();
        Lease first = acquireOne(Duration.ofHours(1));
        store.stage(first, Endpoint.bootstrap(), readNow(), Validation.unchecked(), bootstrap);
        clock.advance(Duration.ofMinutes(11));
        store.stage(first, Endpoint.fixtures(), readNow(), Validation.unchecked(), fixtures);
        store.retry(first, clock.instant(), "restart after both endpoints");
        PayloadReadProbe probe = installPayloadReadProbe();

        assertThat(dispatcher.process(acquireOne(Duration.ofMinutes(5)), SEASON)).isEqualTo(Outcome.PUBLISHED);
        assertThat(fetch.calls).isEqualTo(2);
        assertNoBodyReads(probe);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE validation_state='INVALID'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE validation_state='VALID'", Integer.class)).isEqualTo(2);
    }

    @Test
    void resumedPairAtExactlyTenMinutesIsAcceptedAndHydratedOncePerEndpoint() {
        assertResumedPairWindow(Duration.ofMinutes(10), false);
    }

    @Test
    void resumedPairAtTenMinutesFiftyNineSecondsIsAcceptedByWholeMinutePolicy() {
        assertResumedPairWindow(Duration.ofMinutes(10).plusSeconds(59), false);
    }

    @Test
    void resumedPairAtExactlyElevenMinutesRefreshesWithoutHydratingRejectedBodies() {
        assertResumedPairWindow(Duration.ofMinutes(11), true);
    }

    @Test
    void freshElevenMinutePairPassesPreflightThenFailsStrictParserTerminally() {
        enqueueCatalogue();
        Lease lease = acquireOne(Duration.ofHours(1));
        Instant firstRead = clock.instant();
        clock.advance(Duration.ofMinutes(11));
        fetch.results.add(new Response(200, bootstrap, null, new SourceRead(firstRead, firstRead)));
        Instant secondRead = firstRead.plus(Duration.ofMinutes(11));
        fetch.results.add(new Response(200, fixtures, null, new SourceRead(secondRead, secondRead)));
        PayloadReadProbe probe = installPayloadReadProbe();

        assertThat(dispatcher.process(lease, SEASON)).isEqualTo(Outcome.FAILED);

        assertRunState("FAILED");
        assertThat(fetch.calls).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE validation_state='INVALID'", Integer.class)).isEqualTo(2);
        assertNoBodyReads(probe);
    }

    @Test
    void freshOverWindowCrossingUkMidnightRetriesObsoleteBeforeStrictParsing() {
        clock.set(Instant.parse("2026-09-13T22:50:00Z")); // 23:50 BST
        enqueueCatalogue();
        Lease lease = acquireOne(Duration.ofHours(1));
        Instant bootstrapRead = Instant.parse("2026-09-13T22:55:00Z"); // 23:55 BST
        Instant fixturesRead = Instant.parse("2026-09-13T23:06:00Z"); // 00:06 BST next day
        clock.set(Instant.parse("2026-09-13T23:10:00Z")); // 00:10 BST next day
        assertThat(bootstrapRead.atZone(ZoneId.of("Europe/London")).toLocalDate())
                .isBefore(clock.instant().atZone(ZoneId.of("Europe/London")).toLocalDate());
        fetch.results.add(new Response(200, bootstrap, null, new SourceRead(bootstrapRead, bootstrapRead)));
        fetch.results.add(new Response(200, fixtures, null, new SourceRead(fixturesRead, fixturesRead)));
        PayloadReadProbe probe = installPayloadReadProbe();

        assertThat(dispatcher.process(lease, SEASON)).isEqualTo(Outcome.RETRY_SCHEDULED);

        assertThat(fetch.calls).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT due_at FROM " + OWNED.run()
                + " WHERE generation=?", java.sql.Timestamp.class, lease.generation()).toInstant())
                .isEqualTo(clock.instant().plusSeconds(1));
        assertThat(jdbc.queryForObject("SELECT last_error FROM " + OWNED.run()
                + " WHERE generation=?", String.class, lease.generation())).contains("obsolete");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE validation_state='INVALID'", Integer.class)).isEqualTo(2);
        assertNoBodyReads(probe);
    }

    @Test
    void freshOverWindowWithBootstrapReceiptRegressionRetriesBeforeStrictParsing() {
        enqueueCatalogue();
        Lease lease = acquireOne(Duration.ofHours(1));
        Instant firstRead = clock.instant();
        clock.advance(Duration.ofMinutes(12));
        seedSourceReceipt(CatalogueScheduleWriter.SHARED_TEAM_SOURCE_RECEIPT_KEY,
                new SourceRead(firstRead, firstRead.plusSeconds(1)));
        fetch.results.add(new Response(200, bootstrap, null, new SourceRead(firstRead, firstRead)));
        Instant secondRead = firstRead.plus(Duration.ofMinutes(11));
        fetch.results.add(new Response(200, fixtures, null, new SourceRead(secondRead, secondRead)));
        PayloadReadProbe probe = installPayloadReadProbe();

        assertThat(dispatcher.process(lease, SEASON)).isEqualTo(Outcome.RETRY_SCHEDULED);

        assertThat(fetch.calls).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT due_at FROM " + OWNED.run()
                + " WHERE generation=?", java.sql.Timestamp.class, lease.generation()).toInstant())
                .isEqualTo(clock.instant().plusSeconds(1));
        assertThat(jdbc.queryForObject("SELECT last_error FROM " + OWNED.run()
                + " WHERE generation=?", String.class, lease.generation())).contains("obsolete");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE validation_state='INVALID'", Integer.class)).isEqualTo(2);
        assertNoBodyReads(probe);
    }

    @Test
    void resumedOverWindowCrossingUkMidnightAndRegressingReceiptTakesWindowFallbackFirst() {
        clock.set(Instant.parse("2026-09-13T22:59:00Z")); // 23:59 BST
        enqueueCatalogue();
        Lease lease = acquireOne(Duration.ofHours(1));
        SourceRead oldBootstrapRead = readNow();
        store.stage(lease, Endpoint.bootstrap(), oldBootstrapRead, Validation.valid(), bootstrap);
        seedSourceReceipt(CatalogueScheduleWriter.SHARED_TEAM_SOURCE_RECEIPT_KEY,
                new SourceRead(oldBootstrapRead.startedAt(), oldBootstrapRead.finishedAt().plusSeconds(1)));
        AtomicBoolean advancedAcrossMidnight = new AtomicBoolean();
        fetch.beforeResponse = endpoint -> {
            if (endpoint.equals(Endpoint.fixtures()) && advancedAcrossMidnight.compareAndSet(false, true)) {
                clock.advance(Duration.ofMinutes(11));
            }
        };
        PayloadReadProbe probe = installPayloadReadProbe();

        Outcome outcome = dispatcher.process(lease, SEASON);
        assertThat(outcome).as("endpoints=%s state=%s error=%s", fetch.endpoints,
                jdbc.queryForObject("SELECT state FROM " + OWNED.run(), String.class),
                jdbc.queryForObject("SELECT last_error FROM " + OWNED.run(), String.class))
                .isEqualTo(Outcome.RETRY_SCHEDULED);

        assertThat(clock.instant().atZone(ZoneId.of("Europe/London")).toLocalDate())
                .isAfter(oldBootstrapRead.startedAt().atZone(ZoneId.of("Europe/London")).toLocalDate());
        assertThat(fetch.endpoints).containsExactly(Endpoint.fixtures(), Endpoint.bootstrap(), Endpoint.fixtures());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE validation_state='INVALID'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE validation_state='VALID'", Integer.class)).isEqualTo(2);
        assertNoBodyReads(probe);
    }

    @Test
    void missingPartnerWaitCrossingUkMidnightRejectsSameUkDayBeforeHydration() {
        clock.set(Instant.parse("2026-09-13T22:59:00Z")); // 23:59 BST, still 22:59 UTC
        enqueueCatalogue();
        Lease lease = acquireOne(Duration.ofHours(1));
        store.stage(lease, Endpoint.bootstrap(), readNow(), Validation.valid(), bootstrap);
        AtomicBoolean crossedMidnight = new AtomicBoolean();
        fetch.beforeResponse = endpoint -> {
            if (crossedMidnight.compareAndSet(false, true)) clock.advance(Duration.ofMinutes(2));
        };
        PayloadReadProbe probe = installPayloadReadProbe();

        assertThat(dispatcher.process(lease, SEASON)).isEqualTo(Outcome.RETRY_SCHEDULED);

        assertThat(clock.instant().atZone(ZoneId.of("UTC")).toLocalDate())
                .isEqualTo(LocalDate.of(2026, 9, 13));
        assertThat(clock.instant().atZone(ZoneId.of("Europe/London")).toLocalDate())
                .isEqualTo(LocalDate.of(2026, 9, 14));
        assertThat(fetch.endpoints).containsExactly(Endpoint.fixtures());
        assertThat(jdbc.queryForObject("SELECT due_at FROM " + OWNED.run()
                + " WHERE generation=?", java.sql.Timestamp.class, lease.generation()).toInstant())
                .isEqualTo(clock.instant().plusSeconds(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE validation_state='INVALID'", Integer.class)).isEqualTo(2);
        assertNoBodyReads(probe);
    }

    @Test
    void previousDayOwnPairStagesAreRejectedBeforeEitherBodyIsRead() {
        enqueueCatalogue();
        Lease lease = acquireOne(Duration.ofHours(1));
        SourceRead previousDay = new SourceRead(clock.instant().minus(Duration.ofDays(1)),
                clock.instant().minus(Duration.ofDays(1)));
        long bootstrapId = store.stage(lease, Endpoint.bootstrap(), previousDay,
                Validation.valid(), bootstrap);
        long fixturesId = store.stage(lease, Endpoint.fixtures(), previousDay,
                Validation.unchecked(), fixtures);
        PayloadReadProbe probe = installPayloadReadProbe();

        assertThat(dispatcher.process(lease, SEASON)).isEqualTo(Outcome.PUBLISHED);

        assertThat(fetch.endpoints).containsExactly(Endpoint.bootstrap(), Endpoint.fixtures());
        assertThat(jdbc.queryForObject("SELECT validation_state FROM " + OWNED.payload()
                + " WHERE id=?", String.class, bootstrapId)).isEqualTo("VALID");
        assertThat(jdbc.queryForObject("SELECT validation_state FROM " + OWNED.payload()
                + " WHERE id=?", String.class, fixturesId)).isEqualTo("UNCHECKED");
        assertNoBodyReads(probe);
    }

    @Test
    void bootstrapAndFixtureReceiptEqualityAreNotRegressions() {
        PairSeed pair = stageOwnPair(Duration.ZERO);
        seedSourceReceipt(CatalogueScheduleWriter.SHARED_TEAM_SOURCE_RECEIPT_KEY, pair.bootstrapRead());
        seedSourceReceipt(CatalogueScheduleWriter.FIXTURE_SOURCE_RECEIPT_KEY, pair.fixturesRead());
        PayloadReadProbe probe = installPayloadReadProbe();

        assertThat(dispatcher.process(pair.lease(), SEASON)).isEqualTo(Outcome.PUBLISHED);

        assertThat(fetch.calls).isZero();
        assertThat(probe.snapshot().family(PayloadReadProbe.Family.GUARDED_SINGLE))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(2, 2, 2, bootstrap.length + fixtures.length));
        assertThat(probe.snapshot().family(PayloadReadProbe.Family.CANONICAL_PAIR))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(0, 0, 0, 0));
    }

    @Test
    void bootstrapReceiptRegressionRejectsResumedPairBeforeHydration() {
        assertResumedReceiptRegression(CatalogueScheduleWriter.SHARED_TEAM_SOURCE_RECEIPT_KEY, true);
    }

    @Test
    void fixtureReceiptRegressionRejectsResumedPairBeforeHydration() {
        assertResumedReceiptRegression(CatalogueScheduleWriter.FIXTURE_SOURCE_RECEIPT_KEY, false);
    }

    @Test
    void bootstrapReceiptRegressionDiscoveredAfterMissingPartnerAvoidsHydration() {
        assertPartialReceiptRegression(Endpoint.bootstrap(), Endpoint.fixtures());
    }

    @Test
    void fixtureReceiptRegressionDiscoveredAfterMissingPartnerAvoidsHydration() {
        assertPartialReceiptRegression(Endpoint.fixtures(), Endpoint.bootstrap());
    }

    @Test
    void invalidOwnCheckpointIsRejectedWithoutReadingItsBody() {
        assertIneligibleOwnBootstrap(Validation.invalid("seed invalid"), readNow(), "INVALID");
    }

    @Test
    void previousDayOwnCheckpointIsRejectedWithoutReadingItsBody() {
        SourceRead previousDay = new SourceRead(clock.instant().minus(Duration.ofDays(1)),
                clock.instant().minus(Duration.ofDays(1)));
        assertIneligibleOwnBootstrap(Validation.valid(), previousDay, "VALID");
    }

    @Test
    void belowFloorOwnCheckpointIsRejectedWithoutReadingItsBody() {
        SourceRead beforeFloor = new SourceRead(clock.instant().minusSeconds(2), clock.instant().minusSeconds(1));
        assertIneligibleOwnBootstrap(Validation.valid(), beforeFloor, "VALID");
    }

    @Test
    void partialBootstrapDefersMissingFixturesWithoutReadingTheCheckpointBody() {
        assertPartialCheckpointDefers(Endpoint.bootstrap(), Endpoint.fixtures());
    }

    @Test
    void partialFixturesCheckpointDefersMissingBootstrapWithoutReadingTheCheckpointBody() {
        assertPartialCheckpointDefers(Endpoint.fixtures(), Endpoint.bootstrap());
    }

    @Test
    void newerInvalidOwnCheckpointHidesOlderValidCheckpointWithoutReadingEitherBody() {
        enqueueCatalogue();
        Lease lease = acquireOne(Duration.ofMinutes(5));
        long olderId = store.stage(lease, Endpoint.bootstrap(), readNow(), Validation.valid(), bootstrap);
        long latestId = store.stage(lease, Endpoint.bootstrap(), readNow(), Validation.invalid("newest invalid"), fixtures);
        PayloadReadProbe probe = installPayloadReadProbe();

        assertThat(dispatcher.process(lease, SEASON)).isEqualTo(Outcome.PUBLISHED);

        assertThat(fetch.endpoints).containsExactly(Endpoint.bootstrap(), Endpoint.fixtures());
        assertThat(jdbc.queryForObject("SELECT validation_state FROM " + OWNED.payload()
                + " WHERE id=?", String.class, olderId)).isEqualTo("VALID");
        assertThat(jdbc.queryForObject("SELECT validation_state FROM " + OWNED.payload()
                + " WHERE id=?", String.class, latestId)).isEqualTo("INVALID");
        assertNoBodyReads(probe);
    }

    @Test
    void partialBootstrapCheckpointHydratesOnlyAfterMissingFixturesSucceed() {
        assertPartialCheckpointResumes(Endpoint.bootstrap(), Endpoint.fixtures());
    }

    @Test
    void partialFixturesCheckpointHydratesOnlyAfterMissingBootstrapSucceed() {
        assertPartialCheckpointResumes(Endpoint.fixtures(), Endpoint.bootstrap());
    }

    @Test
    void malformedResumedOwnCheckpointStillFailsStrictlyWithoutFreshPairFallback() {
        enqueueCatalogue();
        Lease lease = acquireOne(Duration.ofMinutes(5));
        long checkpointId = store.stage(lease, Endpoint.bootstrap(), readNow(),
                Validation.unchecked(), "{}".getBytes());
        PayloadReadProbe probe = installPayloadReadProbe();

        assertThat(dispatcher.process(lease, SEASON)).isEqualTo(Outcome.FAILED);

        assertThat(fetch.endpoints).containsExactly(Endpoint.fixtures());
        assertRunState("FAILED");
        assertThat(jdbc.queryForObject("SELECT validation_state FROM " + OWNED.payload()
                + " WHERE id=?", String.class, checkpointId)).isEqualTo("INVALID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE validation_state='INVALID'", Integer.class)).isEqualTo(2);
        assertThat(probe.snapshot().family(PayloadReadProbe.Family.GUARDED_SINGLE))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(1, 1, 1, 2));
    }

    @Test
    void hydrationMissRereadsAdvancedFloorAndRetriesWithoutAnotherFetchOrInvalidation() {
        PayloadReadProbe probe = new PayloadReadProbe(jdbc.getDataSource());
        activateStagingStore(probe);
        enqueueCatalogue();
        Lease lease = acquireOne(Duration.ofMinutes(5));
        long checkpointId = store.stage(lease, Endpoint.bootstrap(), readNow(), Validation.valid(), bootstrap);
        Instant advancedFloor = clock.instant().plus(Duration.ofHours(2));
        fetch.beforeResponse = endpoint -> store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(),
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY, advancedFloor);

        assertThat(dispatcher.process(lease, SEASON)).isEqualTo(Outcome.RETRY_SCHEDULED);

        assertRunState("PENDING");
        assertThat(fetch.endpoints).containsExactly(Endpoint.fixtures());
        assertThat(jdbc.queryForObject("SELECT due_at FROM " + OWNED.run()
                + " WHERE generation=?", java.sql.Timestamp.class, lease.generation()).toInstant())
                .isEqualTo(advancedFloor);
        assertThat(jdbc.queryForObject("SELECT validation_state FROM " + OWNED.payload()
                + " WHERE id=?", String.class, checkpointId)).isEqualTo("VALID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE validation_state='INVALID'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT consecutive_fetch_failures FROM " + OWNED.run()
                + " WHERE generation=?", Integer.class, lease.generation())).isZero();
        assertThat(probe.snapshot().family(PayloadReadProbe.Family.GUARDED_SINGLE))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(1, 0, 0, 0));
    }

    @Test
    void deletedSelectedCheckpointReturnsNoBodyAndSchedulesDurableRetry() {
        PayloadReadProbe probe = new PayloadReadProbe(jdbc.getDataSource());
        activateStagingStore(probe);
        Lease lease = enqueueAndAcquireCatalogue();
        long checkpointId = store.stage(lease, Endpoint.bootstrap(), readNow(), Validation.valid(), bootstrap);
        // Inject defensive same-run loss; normal terminal-payload retention protects active runs.
        fetch.beforeResponse = endpoint -> jdbc.update("DELETE FROM " + OWNED.payload() + " WHERE id=?", checkpointId);

        assertThat(dispatcher.process(lease, SEASON)).isEqualTo(Outcome.RETRY_SCHEDULED);

        assertThat(fetch.endpoints).containsExactly(Endpoint.fixtures());
        assertThat(fetch.calls).isOne();
        assertRunState("PENDING");
        assertThat(jdbc.queryForObject("SELECT due_at FROM " + OWNED.run()
                + " WHERE generation=?", java.sql.Timestamp.class, lease.generation()).toInstant())
                .isEqualTo(clock.instant().plusSeconds(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload(), Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT consecutive_fetch_failures FROM " + OWNED.run()
                + " WHERE generation=?", Integer.class, lease.generation())).isZero();
        assertThat(probe.snapshot().family(PayloadReadProbe.Family.GUARDED_SINGLE))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(1, 0, 0, 0));
    }

    @Test
    void lossOfSecondCheckpointAfterFirstReadReturnsOnlyFirstBodyAndRetries() {
        AtomicReference<Long> firstId = new AtomicReference<>();
        AtomicReference<Long> secondId = new AtomicReference<>();
        AtomicBoolean deleted = new AtomicBoolean();
        PayloadReadProbe probe = new PayloadReadProbe(jdbc.getDataSource()) {
            @Override
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                List<T> rows = super.query(sql, rowMapper, args);
                if (sql.toLowerCase(java.util.Locale.ROOT).contains("p.id = ?")
                        && rows.stream().anyMatch(row -> row instanceof OwnedSyncStore.StagedPayload payload
                        && payload.id() == firstId.get())
                        && deleted.compareAndSet(false, true)) {
                    jdbc.update("DELETE FROM " + OWNED.payload() + " WHERE id=?", secondId.get());
                }
                return rows;
            }
        };
        activateStagingStore(probe);
        Lease lease = enqueueAndAcquireCatalogue();
        firstId.set(store.stage(lease, Endpoint.bootstrap(), readNow(), Validation.unchecked(), bootstrap));
        secondId.set(store.stage(lease, Endpoint.fixtures(), readNow(), Validation.unchecked(), fixtures));

        assertThat(dispatcher.process(lease, SEASON)).isEqualTo(Outcome.RETRY_SCHEDULED);

        assertThat(deleted).isTrue();
        assertThat(fetch.calls).isZero();
        assertRunState("PENDING");
        assertThat(jdbc.queryForObject("SELECT due_at FROM " + OWNED.run()
                + " WHERE generation=?", java.sql.Timestamp.class, lease.generation()).toInstant())
                .isEqualTo(clock.instant().plusSeconds(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE id=?", Integer.class, secondId.get())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE id=?", Integer.class, firstId.get())).isOne();
        assertThat(jdbc.queryForObject("SELECT consecutive_fetch_failures FROM " + OWNED.run()
                + " WHERE generation=?", Integer.class, lease.generation())).isZero();
        assertThat(probe.snapshot().family(PayloadReadProbe.Family.GUARDED_SINGLE))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(2, 1, 1, bootstrap.length));
    }

    @Test
    void ukDayRolloverAfterHydrationRejectsTheSelectedPairBeforePublication() {
        clock.set(Instant.parse("2026-09-13T22:58:00Z")); // 23:58 BST
        AtomicInteger hydratedRows = new AtomicInteger();
        PayloadReadProbe probe = new PayloadReadProbe(jdbc.getDataSource()) {
            @Override
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                List<T> rows = super.query(sql, rowMapper, args);
                if (sql.toLowerCase(java.util.Locale.ROOT).contains("p.id = ?") && !rows.isEmpty()
                        && hydratedRows.incrementAndGet() == 2) {
                    LocalDate nextUkDay = LocalDate.ofInstant(clock.instant(), ZoneId.of("Europe/London"))
                            .plusDays(1);
                    clock.set(nextUkDay.atStartOfDay(ZoneId.of("Europe/London")).toInstant());
                }
                return rows;
            }
        };
        activateStore(OwnedSyncMode.ACTIVE, probe);
        Lease lease = enqueueAndAcquireCatalogue(Duration.ofHours(1));
        store.stage(lease, Endpoint.bootstrap(), readNow(), Validation.unchecked(), bootstrap);
        store.stage(lease, Endpoint.fixtures(), readNow(), Validation.unchecked(), fixtures);
        List<Integer> writesBefore = publicationCounts();

        assertThat(dispatcher.process(lease, SEASON)).isEqualTo(Outcome.RETRY_SCHEDULED);

        assertThat(hydratedRows).hasValue(2);
        assertRunState("PENDING");
        assertThat(jdbc.queryForObject("SELECT due_at FROM " + OWNED.run()
                + " WHERE generation=?", java.sql.Timestamp.class, lease.generation()).toInstant())
                .isEqualTo(clock.instant().plusSeconds(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE validation_state='INVALID'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT consecutive_fetch_failures FROM " + OWNED.run()
                + " WHERE generation=?", Integer.class, lease.generation())).isZero();
        assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + OWNED.receipt()
                + " WHERE season='2026-27' AND publication_key='catalogue-price'", Long.class)).isNull();
        assertThat(publicationCounts()).isEqualTo(writesBefore);
        assertThat(probe.snapshot().family(PayloadReadProbe.Family.GUARDED_SINGLE))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(2, 2, 2, bootstrap.length + fixtures.length));
    }

    @Test
    void leaseLossOnHydrationMissFloorRereadPropagatesWithoutRetryOrFailureCount() {
        enqueueCatalogue();
        Lease lease = acquireOne(Duration.ofMinutes(5));
        long checkpointId = store.stage(lease, Endpoint.bootstrap(), readNow(), Validation.valid(), bootstrap);
        PayloadReadProbe probe = new PayloadReadProbe(jdbc.getDataSource()) {
            @Override
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                List<T> rows = super.query(sql, rowMapper, args);
                String lower = sql.toLowerCase(java.util.Locale.ROOT);
                if (rows.isEmpty() && lower.contains("p.id = ?") && lower.contains("p.body")) {
                    jdbc.update("UPDATE " + OWNED.run() + " SET lease_token=? WHERE generation=?",
                            java.util.UUID.randomUUID(), lease.generation());
                }
                return rows;
            }
        };
        store = new OwnedSyncStore(probe, tx, clock, OWNED);
        dispatcher = dispatcher(CAPTURED);
        Instant advancedFloor = clock.instant().plus(Duration.ofHours(2));
        fetch.beforeResponse = endpoint -> store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(),
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY, advancedFloor);

        assertThatThrownBy(() -> dispatcher.process(lease, SEASON)).isInstanceOf(LeaseLostException.class);

        assertThat(fetch.endpoints).containsExactly(Endpoint.fixtures());
        assertThat(jdbc.queryForObject("SELECT state FROM " + OWNED.run()
                + " WHERE generation=?", String.class, lease.generation())).isEqualTo("LEASED");
        assertThat(jdbc.queryForObject("SELECT consecutive_fetch_failures FROM " + OWNED.run()
                + " WHERE generation=?", Integer.class, lease.generation())).isZero();
        assertThat(jdbc.queryForObject("SELECT validation_state FROM " + OWNED.payload()
                + " WHERE id=?", String.class, checkpointId)).isEqualTo("VALID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE validation_state='INVALID'", Integer.class)).isZero();
        assertThat(probe.snapshot().family(PayloadReadProbe.Family.GUARDED_SINGLE))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(1, 0, 0, 0));
    }

    @Test
    void hydrationMissRetriesAtNowPlusOneSecondAndColdDispatcherResumesWithoutRefetch() {
        enqueueCatalogue();
        Lease lease = acquireOne(Duration.ofMinutes(5));
        long checkpointId = store.stage(lease, Endpoint.bootstrap(), readNow(), Validation.unchecked(), bootstrap);
        fetch.beforeResponse = endpoint -> jdbc.update("UPDATE " + OWNED.payload()
                + " SET validation_state='VALID', validation_error=NULL WHERE id=?", checkpointId);
        PayloadReadProbe probe = installPayloadReadProbe();

        assertThat(dispatcher.process(lease, SEASON)).isEqualTo(Outcome.RETRY_SCHEDULED);
        Instant due = jdbc.queryForObject("SELECT due_at FROM " + OWNED.run()
                + " WHERE generation=?", java.sql.Timestamp.class, lease.generation()).toInstant();
        assertThat(due).isEqualTo(clock.instant().plusSeconds(1));
        assertThat(fetch.endpoints).containsExactly(Endpoint.fixtures());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE validation_state='INVALID'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT consecutive_fetch_failures FROM " + OWNED.run()
                + " WHERE generation=?", Integer.class, lease.generation())).isZero();
        assertThat(probe.snapshot().family(PayloadReadProbe.Family.GUARDED_SINGLE))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(1, 0, 0, 0));

        clock.advance(Duration.ofSeconds(1));
        store = new OwnedSyncStore(probe, tx, clock, OWNED);
        dispatcher = dispatcher(CAPTURED);
        assertThat(dispatcher.process(acquireOne(Duration.ofMinutes(5)), SEASON)).isEqualTo(Outcome.PUBLISHED);

        assertThat(fetch.calls).isOne();
        assertThat(probe.snapshot().family(PayloadReadProbe.Family.GUARDED_SINGLE))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(3, 2, 2, bootstrap.length + fixtures.length));
        assertThat(probe.snapshot().family(PayloadReadProbe.Family.OLD_OWN_CHECKPOINT))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(0, 0, 0, 0));
    }

    @Test
    void realBudgetAndLoopbackDeferralPreservesPartialFencedCheckpoint() throws Exception {
        PayloadReadProbe probe = new PayloadReadProbe(jdbc.getDataSource());
        activateStagingStore(probe);
        Lease lease = enqueueAndAcquireCatalogue();
        long checkpointId = store.stage(lease, Endpoint.bootstrap(), readNow(), Validation.valid(), bootstrap);
        try (Loopback loopback = loopback()) {
            jdbc.update("UPDATE " + OWNED.requestState() + " SET last_admitted_at=? WHERE singleton=true",
                    java.sql.Timestamp.from(clock.instant()));
            dispatcher = new CatalogueScheduleDispatcher(store,
                    new CatalogueScheduleWriter(jdbc, clock, store, OWNED, CAPTURED), loopback.http, clock);
            probe.reset();

            assertThat(dispatcher.process(lease, SEASON)).isEqualTo(Outcome.RETRY_SCHEDULED);

            assertThat(loopback.requests).hasValue(0);
            assertThat(jdbc.queryForObject("SELECT due_at FROM " + OWNED.run()
                    + " WHERE generation=?", java.sql.Timestamp.class, lease.generation()).toInstant())
                    .isEqualTo(clock.instant().plusSeconds(1));
            assertThat(jdbc.queryForObject("SELECT validation_state FROM " + OWNED.payload()
                    + " WHERE id=?", String.class, checkpointId)).isEqualTo("VALID");
            assertThat(jdbc.queryForObject("SELECT consecutive_fetch_failures FROM " + OWNED.run()
                    + " WHERE generation=?", Integer.class, lease.generation())).isZero();
            assertThat(probe.snapshot().attempts()).isZero();
        }
    }

    @Test
    void malformedRaw200AndOversizedPayloadFailTerminallyWithoutPublishing() {
        enqueueCatalogue();
        fetch.results.add(new Response(200, "{}".getBytes(), null, readNow()));
        assertThat(dispatcher.process(acquireOne(Duration.ofMinutes(5)), SEASON)).isEqualTo(Outcome.FAILED);
        assertRunState("FAILED");
        assertThat(payloadCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE validation_state='INVALID'", Integer.class)).isEqualTo(2);

        cleanRunsAndCore();
        enqueueCatalogue();
        fetch.results.clear();
        fetch.results.add(new Response(200, new byte[OwnedSyncStore.MAX_PAYLOAD_BYTES + 1], null, readNow()));
        assertThat(dispatcher.process(acquireOne(Duration.ofMinutes(5)), SEASON)).isEqualTo(Outcome.FAILED);
        assertRunState("FAILED");
        assertThat(payloadCount()).isZero();
    }

    @Test
    void sourceReceiptRaceRefreshesOnceAndUsesTheSamePublicationErrorHandling() throws Exception {
        assertSourceReceiptRace(CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY,
                CatalogueScheduleWriter.SHARED_TEAM_SOURCE_RECEIPT_KEY);
    }

    @Test
    void scheduleBootstrapReceiptRaceRefreshesBeforePublishing() throws Exception {
        assertSourceReceiptRace(CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY,
                CatalogueScheduleWriter.SHARED_TEAM_SOURCE_RECEIPT_KEY);
    }

    @Test
    void scheduleFixtureReceiptRaceRefreshesBeforePublishing() throws Exception {
        assertSourceReceiptRace(CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY,
                CatalogueScheduleWriter.FIXTURE_SOURCE_RECEIPT_KEY);
    }

    @Test
    void transientHttpUsesTheAuthoritativePersistedRetryHint() {
        for (int status : List.of(429, 503)) {
            cleanRunsAndCore();
            enqueueCatalogue();
            Instant due = clock.instant().plus(Duration.ofHours(2));
            fetch.results.add(new Response(status, "busy".getBytes(), "untrusted duplicate", readNow(), due));

            assertThat(dispatcher.process(acquireOne(Duration.ofMinutes(5)), SEASON))
                    .isEqualTo(Outcome.RETRY_SCHEDULED);
            assertThat(jdbc.queryForObject("SELECT due_at FROM " + OWNED.run(),
                    java.sql.Timestamp.class).toInstant()).isEqualTo(due);
            assertThat(jdbc.queryForObject("SELECT validation_state FROM " + OWNED.payload(), String.class))
                    .isEqualTo("INVALID");
            fetch.results.clear();
        }
    }

    @Test
    void interruptedFetchReturnsOnlyAfterSchedulingADurableRetry() {
        enqueueCatalogue();
        fetch.results.add(new Failure(FailureKind.INTERRUPTED, "interrupted", null, null, readNow()));

        assertThat(dispatcher.process(acquireOne(Duration.ofMinutes(5)), SEASON))
                .isEqualTo(Outcome.RETRY_SCHEDULED);
        assertRunState("PENDING");
        assertThat(payloadCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + OWNED.receipt()
                + " WHERE season='2026-27' AND publication_key='catalogue-price'", Long.class)).isNull();
    }

    @Test
    void leaseExpiryDuringFetchCannotStageRetryOrPublish() {
        enqueueCatalogue();
        fetch.beforeResponse = endpoint -> clock.advance(Duration.ofMinutes(6));

        assertThatThrownBy(() -> dispatcher.process(acquireOne(Duration.ofMinutes(5)), SEASON))
                .isInstanceOf(LeaseLostException.class);
        assertThat(payloadCount()).isZero();
        assertRunState("LEASED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player WHERE season_id=27", Integer.class)).isZero();
    }

    @Test
    void publicationSqlRollbackKeepsValidatedStagesAndSchedulesDurableRetry() {
        PayloadReadProbe probe = new PayloadReadProbe(jdbc.getDataSource());
        OwnedSyncStore.RuntimeFence fence = activateStore(OwnedSyncMode.ACTIVE, probe);
        enqueueCatalogue();
        CatalogueScheduleTables failing = new CatalogueScheduleTables(
                "fpl_daily_price_fail_dispatch_test", CAPTURED.gameweek(), CAPTURED.fixture());
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + failing.dailyPrice() + " (LIKE "
                + CAPTURED.dailyPrice() + " INCLUDING ALL)");
        jdbc.execute("ALTER TABLE " + failing.dailyPrice() + " DROP CONSTRAINT IF EXISTS fail_dispatch_price");
        jdbc.execute("ALTER TABLE " + failing.dailyPrice()
                + " ADD CONSTRAINT fail_dispatch_price CHECK (now_cost < 0)");
        List<Integer> writesBefore = publicationCounts();
        dispatcher = dispatcher(failing);

        Lease lease = acquireOne(Duration.ofMinutes(5));
        assertThat(dispatcher.process(lease, SEASON)).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertRunState("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE validation_state='VALID'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player WHERE season_id=27", Integer.class)).isZero();
        assertThat(publicationCounts()).isEqualTo(writesBefore);
        assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + OWNED.receipt()
                + " WHERE season='2026-27' AND publication_key='catalogue-price'", Long.class)).isNull();
        assertNoBodyReads(probe);

        Instant retryAt = jdbc.queryForObject("SELECT due_at FROM " + OWNED.run()
                + " WHERE generation=?", java.sql.Timestamp.class, lease.generation()).toInstant();
        jdbc.execute("ALTER TABLE " + failing.dailyPrice() + " DROP CONSTRAINT fail_dispatch_price");
        clock.set(retryAt);
        store = new OwnedSyncStore(probe, tx, clock, OWNED, fence);
        dispatcher = dispatcher(CAPTURED);
        probe.reset();

        assertThat(dispatcher.process(acquireOne(Duration.ofMinutes(5)), SEASON)).isEqualTo(Outcome.PUBLISHED);

        assertRunState("COMPLETED");
        assertThat(fetch.calls).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE validation_state='VALID'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + OWNED.receipt()
                + " WHERE season='2026-27' AND publication_key='catalogue-price'", Long.class))
                .isEqualTo(lease.generation());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player WHERE season_id=27", Integer.class))
                .isGreaterThan(0);
        assertThat(publicationCounts()).isNotEqualTo(writesBefore);
        assertThat(probe.snapshot().family(PayloadReadProbe.Family.CANONICAL_PAIR))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(1, 1, 2, bootstrap.length + fixtures.length));
        assertThat(probe.snapshot().family(PayloadReadProbe.Family.PUBLISHED_SOURCE_DIGEST))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(2, 2, 2, bootstrap.length + fixtures.length));
        assertThat(probe.snapshot().maxBodyBytesInSingleQuery(
                PayloadReadProbe.BodyFamily.PUBLISHED_SOURCE_DIGESTS))
                .isEqualTo(Math.max(bootstrap.length, fixtures.length));
    }

    @Test
    void previousDayEvidenceIsNeverReused() {
        enqueueCatalogue();
        assertThat(dispatcher.process(acquireOne(Duration.ofMinutes(5)), SEASON)).isEqualTo(Outcome.PUBLISHED);
        assertThat(fetch.calls).isEqualTo(2);

        clock.advance(Duration.ofDays(1));
        enqueueCatalogue();
        assertThat(dispatcher.process(acquireOne(Duration.ofMinutes(5)), SEASON)).isEqualTo(Outcome.PUBLISHED);
        assertThat(fetch.calls).isEqualTo(4);
    }

    private CatalogueScheduleDispatcher dispatcher(CatalogueScheduleTables tables) {
        return new CatalogueScheduleDispatcher(store,
                new CatalogueScheduleWriter(jdbc, clock, store, OWNED, tables), fetch, clock);
    }

    private CatalogueScheduleDispatcher dispatcher(CatalogueScheduleDispatcher.Fetch httpFetch) {
        return new CatalogueScheduleDispatcher(store,
                new CatalogueScheduleWriter(jdbc, clock, store, OWNED, CAPTURED), httpFetch, clock);
    }

    private Lease enqueueAndAcquireCatalogue() {
        return enqueueAndAcquireCatalogue(Duration.ofMinutes(5));
    }

    private Lease enqueueAndAcquireCatalogue(Duration duration) {
        enqueueCatalogue();
        return acquireOne(duration);
    }

    private void activateStagingStore() {
        activateStore(OwnedSyncMode.STAGING);
    }

    private void activateStagingStore(PayloadReadProbe probe) {
        activateStore(OwnedSyncMode.STAGING, probe);
    }

    private OwnedSyncStore.RuntimeFence activateStore(OwnedSyncMode mode) {
        OwnedSyncStore.RuntimeFence fence = new PlannerRuntimeRepository(jdbc, tx, clock, OWNED)
                .activate(SEASON.code(), mode);
        store = new OwnedSyncStore(jdbc, tx, clock, OWNED, fence);
        return fence;
    }

    private OwnedSyncStore.RuntimeFence activateStore(OwnedSyncMode mode, PayloadReadProbe probe) {
        OwnedSyncStore.RuntimeFence fence = new PlannerRuntimeRepository(jdbc, tx, clock, OWNED)
                .activate(SEASON.code(), mode);
        store = new OwnedSyncStore(probe, tx, clock, OWNED, fence);
        dispatcher = dispatcher(CAPTURED);
        return fence;
    }

    private CatalogueScheduleDispatcher.Fetch fencedHttpFetch(Loopback loopback,
                                                               java.util.function.Consumer<HttpFetchRequest> beforeFence) {
        return new CatalogueScheduleDispatcher.Fetch() {
            @Override
            public OwnedSyncHttpClient.Result get(Endpoint endpoint, int failureAttempt) {
                throw new AssertionError("fenced fetch overload was not used");
            }

            @Override
            public OwnedSyncHttpClient.Result get(Lease lease, Endpoint endpoint, int failureAttempt,
                                                  Instant sourceFloor) {
                beforeFence.accept(new HttpFetchRequest(lease, endpoint, sourceFloor));
                return loopback.http.get(endpoint, failureAttempt,
                        () -> store.httpFence(lease, endpoint, sourceFloor));
            }
        };
    }

    private Loopback loopback() throws Exception {
        return createLoopback(null, null, null);
    }

    private Loopback loopbackWithFplBodies(Runnable afterBootstrapRequest) throws Exception {
        return createLoopback(bootstrap, fixtures, afterBootstrapRequest);
    }

    private Loopback createLoopback(byte[] bootstrapBody, byte[] fixturesBody,
                                    Runnable afterBootstrapRequest) throws Exception {
        jdbc.update("DELETE FROM " + OWNED.requestAttempt());
        jdbc.update("UPDATE " + OWNED.requestState() + " SET last_admitted_at=NULL, cooldown_until=NULL");
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            if (bootstrapBody == null) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            String path = exchange.getRequestURI().getPath();
            boolean isBootstrap = path.contains("bootstrap-static");
            byte[] body = isBootstrap ? bootstrapBody : fixturesBody;
            if (isBootstrap && afterBootstrapRequest != null) afterBootstrapRequest.run();
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        OwnedSyncRequestBudget budget = new OwnedSyncRequestBudget(jdbc, tx, clock, OWNED);
        OwnedSyncHttpClient http = new OwnedSyncHttpClient(budget,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/"),
                Duration.ofSeconds(2), clock);
        return new Loopback(server, http, requests);
    }

    private void assertNoHttpOrFetchFailure(Loopback loopback, long generation) {
        assertThat(loopback.requests).hasValue(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.requestAttempt(), Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT consecutive_fetch_failures FROM " + OWNED.run()
                + " WHERE generation=?", Integer.class, generation)).isZero();
        assertThat(payloadCount()).isZero();
    }

    private void installPostAdmissionEpochInvalidation() {
        jdbc.execute("CREATE OR REPLACE FUNCTION fpl_catalogue_test_bump_epoch() RETURNS trigger AS $$ "
                + "BEGIN UPDATE " + OWNED.plannerRuntime()
                + " SET runtime_epoch=runtime_epoch+1 WHERE instance_id='mode:2026-27'; "
                + "RETURN NEW; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER fpl_catalogue_test_epoch_trigger AFTER INSERT ON "
                + OWNED.requestAttempt() + " FOR EACH ROW EXECUTE FUNCTION fpl_catalogue_test_bump_epoch()");
    }

    private void removePostAdmissionEpochInvalidation() {
        jdbc.execute("DROP TRIGGER IF EXISTS fpl_catalogue_test_epoch_trigger ON " + OWNED.requestAttempt());
        jdbc.execute("DROP FUNCTION IF EXISTS fpl_catalogue_test_bump_epoch()");
    }

    private record HttpFetchRequest(Lease lease, Endpoint endpoint, Instant sourceFloor) { }
    private record PairSeed(Lease lease, SourceRead bootstrapRead, SourceRead fixturesRead) { }
    private record PublicationCase(String coalescingKey, String publicationKey, LocalTime cutover) { }

    private final class Loopback implements AutoCloseable {
        private final HttpServer server;
        private final OwnedSyncHttpClient http;
        private final AtomicInteger requests;

        private Loopback(HttpServer server, OwnedSyncHttpClient http, AtomicInteger requests) {
            this.server = server;
            this.http = http;
            this.requests = requests;
        }

        @Override public void close() {
            http.close();
            server.stop(0);
        }
    }

    private void assertResumedPairWindow(Duration span, boolean refresh) {
        PairSeed pair = stageOwnPair(span);
        PayloadReadProbe probe = installPayloadReadProbe();

        assertThat(dispatcher.process(pair.lease(), SEASON)).isEqualTo(Outcome.PUBLISHED);

        if (refresh) {
            assertThat(fetch.endpoints).containsExactly(Endpoint.bootstrap(), Endpoint.fixtures());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                    + " WHERE validation_state='INVALID'", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                    + " WHERE validation_state='VALID'", Integer.class)).isEqualTo(2);
            assertNoBodyReads(probe);
        } else {
            assertThat(fetch.calls).isZero();
            assertThat(probe.snapshot().family(PayloadReadProbe.Family.GUARDED_SINGLE))
                    .isEqualTo(new PayloadReadProbe.FamilyMetrics(2, 2, 2, bootstrap.length + fixtures.length));
            assertThat(probe.snapshot().family(PayloadReadProbe.Family.CANONICAL_PAIR))
                    .isEqualTo(new PayloadReadProbe.FamilyMetrics(0, 0, 0, 0));
        }
    }

    private PairSeed stageOwnPair(Duration span) {
        enqueueCatalogue();
        Lease lease = acquireOne(Duration.ofHours(1));
        Instant start = clock.instant();
        SourceRead bootstrapRead = new SourceRead(start, start);
        store.stage(lease, Endpoint.bootstrap(), bootstrapRead, Validation.valid(), bootstrap);
        clock.advance(span);
        Instant fixturesAt = start.plus(span);
        SourceRead fixturesRead = new SourceRead(fixturesAt, fixturesAt);
        store.stage(lease, Endpoint.fixtures(), fixturesRead, Validation.unchecked(), fixtures);
        return new PairSeed(lease, bootstrapRead, fixturesRead);
    }

    private void assertResumedReceiptRegression(String receiptKey, boolean bootstrapReceipt) {
        PairSeed pair = stageOwnPair(Duration.ZERO);
        clock.advance(Duration.ofSeconds(2));
        SourceRead endpointRead = bootstrapReceipt ? pair.bootstrapRead() : pair.fixturesRead();
        seedSourceReceipt(receiptKey, new SourceRead(endpointRead.startedAt(),
                endpointRead.finishedAt().plusSeconds(1)));
        PayloadReadProbe probe = installPayloadReadProbe();

        assertThat(dispatcher.process(pair.lease(), SEASON)).isEqualTo(Outcome.RETRY_SCHEDULED);

        assertThat(fetch.calls).isZero();
        assertThat(jdbc.queryForObject("SELECT due_at FROM " + OWNED.run()
                + " WHERE generation=?", java.sql.Timestamp.class, pair.lease().generation()).toInstant())
                .isEqualTo(clock.instant().plusSeconds(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE validation_state='INVALID'", Integer.class)).isEqualTo(2);
        assertNoBodyReads(probe);
    }

    private void assertPartialReceiptRegression(Endpoint checkpointEndpoint, Endpoint missingEndpoint) {
        enqueueCatalogue();
        Lease lease = acquireOne(Duration.ofHours(1));
        SourceRead checkpointRead = readNow();
        byte[] body = checkpointEndpoint.equals(Endpoint.bootstrap()) ? bootstrap : fixtures;
        store.stage(lease, checkpointEndpoint, checkpointRead, Validation.valid(), body);
        String receiptKey = checkpointEndpoint.equals(Endpoint.bootstrap())
                ? CatalogueScheduleWriter.SHARED_TEAM_SOURCE_RECEIPT_KEY
                : CatalogueScheduleWriter.FIXTURE_SOURCE_RECEIPT_KEY;
        fetch.beforeResponse = endpoint -> seedSourceReceipt(receiptKey,
                new SourceRead(checkpointRead.startedAt(), clock.instant().plusMillis(50)));
        PayloadReadProbe probe = installPayloadReadProbe();

        assertThat(dispatcher.process(lease, SEASON)).isEqualTo(Outcome.RETRY_SCHEDULED);

        assertThat(fetch.endpoints).containsExactly(missingEndpoint);
        assertThat(fetch.calls).isOne();
        assertThat(jdbc.queryForObject("SELECT due_at FROM " + OWNED.run()
                + " WHERE generation=?", java.sql.Timestamp.class, lease.generation()).toInstant())
                .isEqualTo(clock.instant().plusSeconds(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE validation_state='INVALID'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT consecutive_fetch_failures FROM " + OWNED.run()
                + " WHERE generation=?", Integer.class, lease.generation())).isZero();
        assertNoBodyReads(probe);
    }

    private void seedSourceReceipt(String publicationKey, SourceRead read) {
        jdbc.update("""
                INSERT INTO %s(season, publication_key, source_read_started_at,
                               source_read_finished_at, published_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (season, publication_key) DO UPDATE SET
                    source_read_started_at=EXCLUDED.source_read_started_at,
                    source_read_finished_at=EXCLUDED.source_read_finished_at,
                    published_at=EXCLUDED.published_at
                """.formatted(OWNED.receipt()), SEASON.code(), publicationKey,
                java.sql.Timestamp.from(read.startedAt()), java.sql.Timestamp.from(read.finishedAt()),
                java.sql.Timestamp.from(clock.instant()));
    }

    private void assertSourceReceiptRace(String coalescingKey, String publicationKey,
                                         String racedSourceKey) throws Exception {
        PayloadReadProbe probe = new PayloadReadProbe(jdbc.getDataSource());
        activateStore(OwnedSyncMode.ACTIVE, probe);
        store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(), coalescingKey, publicationKey, clock.instant());
        Lease lease = acquireOne(Duration.ofMinutes(5));
        List<Integer> writesBefore = publicationCounts();
        CountDownLatch seasonLocked = new CountDownLatch(1);
        CountDownLatch publisherAtSeason = new CountDownLatch(1);
        CountDownLatch releaseSeason = new CountDownLatch(1);
        CountDownLatch freshPairStarted = new CountDownLatch(1);
        CountDownLatch releaseFreshPair = new CountDownLatch(1);
        AtomicBoolean delayedFreshPair = new AtomicBoolean();
        AtomicReference<Instant> racedReceiptAt = new AtomicReference<>();
        JdbcTemplate writerJdbc = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                if (sql.toLowerCase(java.util.Locale.ROOT).contains("from season where id = ? for update")) {
                    publisherAtSeason.countDown();
                }
                return super.query(sql, rowMapper, args);
            }
        };
        dispatcher = new CatalogueScheduleDispatcher(store,
                new CatalogueScheduleWriter(writerJdbc, clock, store, OWNED, CAPTURED), fetch, clock);
        fetch.beforeResponse = endpoint -> {
            if (fetch.calls == 3 && endpoint.equals(Endpoint.bootstrap())
                    && delayedFreshPair.compareAndSet(false, true)) {
                freshPairStarted.countDown();
                await(releaseFreshPair);
            }
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> blocker = executor.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM season WHERE id=27 FOR UPDATE", Integer.class);
                seasonLocked.countDown();
                await(releaseSeason);
            }));
            assertThat(seasonLocked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Outcome> outcome = executor.submit(() -> dispatcher.process(lease, SEASON));
            assertThat(publisherAtSeason.await(5, TimeUnit.SECONDS)).isTrue();

            clock.advance(Duration.ofSeconds(1));
            racedReceiptAt.set(clock.instant());
            seedSourceReceipt(racedSourceKey, new SourceRead(racedReceiptAt.get(), racedReceiptAt.get()));
            assertThat(publicationCounts()).isEqualTo(writesBefore);
            assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + OWNED.receipt()
                    + " WHERE season=? AND publication_key=?", Long.class, SEASON.code(), publicationKey)).isNull();
            assertThat(jdbc.queryForObject("SELECT source_read_finished_at FROM " + OWNED.receipt()
                    + " WHERE season=? AND publication_key=?", java.sql.Timestamp.class,
                    SEASON.code(), racedSourceKey).toInstant()).isEqualTo(racedReceiptAt.get());

            releaseSeason.countDown();
            blocker.get(5, TimeUnit.SECONDS);
            assertThat(freshPairStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(publicationCounts()).isEqualTo(writesBefore);
            assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + OWNED.receipt()
                    + " WHERE season=? AND publication_key=?", Long.class,
                    SEASON.code(), publicationKey)).isNull();
            assertThat(jdbc.queryForObject("SELECT source_read_finished_at FROM " + OWNED.receipt()
                    + " WHERE season=? AND publication_key=?", java.sql.Timestamp.class,
                    SEASON.code(), racedSourceKey).toInstant()).isEqualTo(racedReceiptAt.get());
            releaseFreshPair.countDown();
            assertThat(outcome.get(10, TimeUnit.SECONDS)).isEqualTo(Outcome.PUBLISHED);
        } finally {
            releaseSeason.countDown();
            releaseFreshPair.countDown();
        }

        assertThat(fetch.calls).isEqualTo(4);
        assertThat(fetch.endpoints).containsExactly(Endpoint.bootstrap(), Endpoint.fixtures(),
                Endpoint.bootstrap(), Endpoint.fixtures());
        assertThat(payloadCount()).isEqualTo(4);
        assertRunState("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT consecutive_fetch_failures FROM " + OWNED.run()
                + " WHERE generation=?", Integer.class, lease.generation())).isZero();
        assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + OWNED.receipt()
                + " WHERE season=? AND publication_key=?", Long.class, SEASON.code(), publicationKey))
                .isEqualTo(lease.generation());
        assertThat(jdbc.queryForObject("SELECT source_read_finished_at FROM " + OWNED.receipt()
                + " WHERE season=? AND publication_key=?", java.sql.Timestamp.class,
                SEASON.code(), racedSourceKey).toInstant()).isAfter(racedReceiptAt.get());
        assertThat(publicationCounts()).isNotEqualTo(writesBefore);
        assertThat(probe.snapshot().family(PayloadReadProbe.Family.GUARDED_SINGLE))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(0, 0, 0, 0));
        assertThat(probe.snapshot().family(PayloadReadProbe.Family.CANONICAL_PAIR))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(0, 0, 0, 0));
        assertThat(probe.snapshot().family(PayloadReadProbe.Family.PUBLISHED_SOURCE_DIGEST))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(2, 2, 2, bootstrap.length + fixtures.length));
    }

    private List<Integer> publicationCounts() {
        return List.of(
                jdbc.queryForObject("SELECT count(*) FROM team WHERE season_id=27", Integer.class),
                jdbc.queryForObject("SELECT count(*) FROM player WHERE season_id=27", Integer.class),
                jdbc.queryForObject("SELECT count(*) FROM gameweek WHERE season_id=27", Integer.class),
                jdbc.queryForObject("SELECT count(*) FROM fixture WHERE season_id=27", Integer.class),
                jdbc.queryForObject("SELECT count(*) FROM " + CAPTURED.dailyPrice(), Integer.class),
                jdbc.queryForObject("SELECT count(*) FROM " + CAPTURED.gameweek(), Integer.class),
                jdbc.queryForObject("SELECT count(*) FROM " + CAPTURED.fixture(), Integer.class),
                jdbc.queryForObject("SELECT count(*) FROM " + OWNED.fixtureSync(), Integer.class));
    }

    private void seedReusablePair(byte[] bootstrapBody, byte[] fixturesBody) {
        dispatcher.enqueueSchedule(SEASON, clock.instant());
        Lease lease = acquireOne(Duration.ofHours(1));
        SourceRead bootstrapRead = readNow();
        SourceRead fixturesRead = readNow();
        store.stage(lease, Endpoint.bootstrap(), bootstrapRead, Validation.valid(), bootstrapBody);
        store.stage(lease, Endpoint.fixtures(), fixturesRead, Validation.valid(), fixturesBody);
        store.completeStaged(lease, readNow(), List.of(bootstrapRead, fixturesRead));
    }

    private void assertNoBodyReads(PayloadReadProbe probe) {
        PayloadReadProbe.Snapshot snapshot = probe.snapshot();
        PayloadReadProbe.FamilyMetrics none = new PayloadReadProbe.FamilyMetrics(0, 0, 0, 0);
        boolean published = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM "
                + OWNED.receipt() + " WHERE latest_generation IS NOT NULL"
                + " AND source_bootstrap_sha256 IS NOT NULL AND source_fixtures_sha256 IS NOT NULL)",
                Boolean.class));
        for (PayloadReadProbe.Family family : PayloadReadProbe.Family.values()) {
            if (family == PayloadReadProbe.Family.PUBLISHED_SOURCE_DIGEST) continue;
            assertThat(snapshot.family(family)).as("unexpected %s body read", family).isEqualTo(none);
        }
        PayloadReadProbe.FamilyMetrics expectedSourceDigest = published
                ? new PayloadReadProbe.FamilyMetrics(2, 2, 2, (long) bootstrap.length + fixtures.length)
                : none;
        assertThat(snapshot.family(PayloadReadProbe.Family.PUBLISHED_SOURCE_DIGEST)).isEqualTo(expectedSourceDigest);
        assertThat(snapshot.bodyFamily(PayloadReadProbe.BodyFamily.PUBLISHED_SOURCE_DIGESTS))
                .isEqualTo(expectedSourceDigest);
        assertThat(snapshot.maxBodyBytesInSingleQuery(PayloadReadProbe.BodyFamily.PUBLISHED_SOURCE_DIGESTS))
                .isEqualTo(published ? Math.max(bootstrap.length, fixtures.length) : 0);
        assertThat(snapshot.attempts()).isEqualTo(published ? 2 : 0);
    }

    private void assertIneligibleOwnBootstrap(Validation validation, SourceRead sourceRead,
                                               String expectedValidationState) {
        enqueueCatalogue();
        Lease lease = acquireOne(Duration.ofMinutes(5));
        long checkpointId = store.stage(lease, Endpoint.bootstrap(), sourceRead, validation, bootstrap);
        PayloadReadProbe probe = installPayloadReadProbe();

        assertThat(dispatcher.process(lease, SEASON)).isEqualTo(Outcome.PUBLISHED);
        assertThat(fetch.calls).isEqualTo(2);
        assertThat(fetch.endpoints).containsExactly(Endpoint.bootstrap(), Endpoint.fixtures());
        assertRunState("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT validation_state FROM " + OWNED.payload()
                + " WHERE id=?", String.class, checkpointId)).isEqualTo(expectedValidationState);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE validation_state='INVALID'", Integer.class))
                .isEqualTo(expectedValidationState.equals("INVALID") ? 1 : 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE validation_state='VALID'", Integer.class))
                .isEqualTo(expectedValidationState.equals("VALID") ? 3 : 2);
        assertThat(jdbc.queryForObject("SELECT consecutive_fetch_failures FROM " + OWNED.run()
                + " WHERE generation=?", Integer.class, lease.generation())).isZero();

        assertNoBodyReads(probe);
    }

    private void assertPartialCheckpointResumes(Endpoint checkpointEndpoint, Endpoint missingEndpoint) {
        enqueueCatalogue();
        Lease lease = acquireOne(Duration.ofMinutes(5));
        byte[] checkpointBody = checkpointEndpoint.equals(Endpoint.bootstrap()) ? bootstrap : fixtures;
        int checkpointLength = checkpointBody.length;
        store.stage(lease, checkpointEndpoint, readNow(), Validation.valid(), checkpointBody);
        PayloadReadProbe probe = installPayloadReadProbe();
        fetch.beforeResponse = endpoint -> assertThat(probe.snapshot().attempts())
                .as("the missing partner must succeed before checkpoint hydration")
                .isZero();

        assertThat(dispatcher.process(lease, SEASON)).isEqualTo(Outcome.PUBLISHED);

        assertThat(fetch.endpoints).containsExactly(missingEndpoint);
        assertThat(payloadCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE validation_state='VALID'", Integer.class)).isEqualTo(2);
        assertThat(probe.snapshot().family(PayloadReadProbe.Family.OLD_OWN_CHECKPOINT))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(0, 0, 0, 0));
        assertThat(probe.snapshot().family(PayloadReadProbe.Family.GUARDED_SINGLE))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(1, 1, 1, checkpointLength));
        assertThat(probe.snapshot().family(PayloadReadProbe.Family.CANONICAL_PAIR))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(0, 0, 0, 0));
    }

    private void assertPartialCheckpointDefers(Endpoint checkpointEndpoint, Endpoint missingEndpoint) {
        enqueueCatalogue();
        Lease lease = acquireOne(Duration.ofMinutes(5));
        long checkpointId = store.stage(lease, checkpointEndpoint, readNow(), Validation.valid(), bootstrap);
        Instant due = clock.instant().plus(Duration.ofMinutes(7));
        fetch.results.add(new DeferredResult(due, DeferredReason.MINIMUM_SPACING));
        PayloadReadProbe probe = installPayloadReadProbe();

        assertThat(dispatcher.process(lease, SEASON)).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertThat(fetch.calls).isOne();
        assertThat(fetch.endpoints).containsExactly(missingEndpoint);
        assertRunState("PENDING");
        assertThat(jdbc.queryForObject("SELECT due_at FROM " + OWNED.run()
                + " WHERE generation=?", java.sql.Timestamp.class, lease.generation()).toInstant()).isEqualTo(due);
        assertThat(jdbc.queryForObject("SELECT validation_state FROM " + OWNED.payload()
                + " WHERE id=?", String.class, checkpointId)).isEqualTo("VALID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload(), Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE validation_state='INVALID'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT consecutive_fetch_failures FROM " + OWNED.run()
                + " WHERE generation=?", Integer.class, lease.generation())).isZero();

        assertNoBodyReads(probe);
    }

    private PayloadReadProbe installPayloadReadProbe() {
        PayloadReadProbe probe = new PayloadReadProbe(jdbc.getDataSource());
        store = new OwnedSyncStore(probe, tx, clock, OWNED);
        dispatcher = dispatcher(CAPTURED);
        probe.reset();
        return probe;
    }

    private void enqueueCatalogue() {
        store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(),
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY, clock.instant());
    }

    private void enqueueSchedule() {
        store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(),
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY, clock.instant());
    }

    private Lease acquireOne(Duration duration) {
        return store.acquire("worker", duration).orElseThrow();
    }

    private Lease[] acquireBoth() {
        return new Lease[]{acquireOne(Duration.ofMinutes(5)), acquireOne(Duration.ofMinutes(5))};
    }

    private static Lease forGeneration(Lease[] leases, long generation) {
        return java.util.Arrays.stream(leases).filter(lease -> lease.generation() == generation)
                .findFirst().orElseThrow();
    }

    private SourceRead readNow() {
        return new SourceRead(clock.instant(), clock.instant());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("timed out awaiting test latch");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private int payloadCount() {
        return jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload(), Integer.class);
    }

    private void assertRunState(String state) {
        assertThat(jdbc.queryForObject("SELECT state FROM " + OWNED.run(), String.class)).isEqualTo(state);
    }

    private static void cleanRunsAndCore() {
        jdbc.update("DELETE FROM " + OWNED.playerFixtureEvidence());
        jdbc.update("DELETE FROM " + OWNED.fixtureSync());
        jdbc.update("DELETE FROM " + OWNED.historyFingerprint());
        jdbc.update("DELETE FROM " + OWNED.payload());
        jdbc.update("DELETE FROM " + OWNED.run());
        jdbc.update("DELETE FROM " + OWNED.receipt());
        jdbc.update("DELETE FROM " + CAPTURED.dailyPrice());
        jdbc.update("DELETE FROM " + CAPTURED.gameweek());
        jdbc.update("DELETE FROM " + CAPTURED.fixture());
        jdbc.update("DELETE FROM player WHERE season_id=27");
        jdbc.update("DELETE FROM fixture WHERE season_id=27");
        jdbc.update("DELETE FROM gameweek WHERE season_id=27");
        jdbc.update("DELETE FROM team WHERE season_id=27");
    }

    private static void clean() {
        cleanRunsAndCore();
        jdbc.update("DELETE FROM fpl_daily_price_fail_dispatch_test");
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
        jdbc.execute("CREATE TABLE IF NOT EXISTS fpl_daily_price_fail_dispatch_test (LIKE "
                + CAPTURED.dailyPrice() + " INCLUDING ALL)");
        jdbc.execute("ALTER TABLE fpl_daily_price_fail_dispatch_test ENABLE ROW LEVEL SECURITY");
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream input = CatalogueScheduleDispatcherIntegrationTest.class.getResourceAsStream(
                "/owned-sync-source-fixtures/" + name)) {
            if (input == null) throw new IllegalArgumentException("Missing " + name);
            return input.readAllBytes();
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private final class FakeFetch implements CatalogueScheduleDispatcher.Fetch {
        private final Deque<OwnedSyncHttpClient.Result> results = new ArrayDeque<>();
        private final List<Endpoint> endpoints = new java.util.ArrayList<>();
        private java.util.function.Consumer<Endpoint> beforeResponse = endpoint -> { };
        private volatile int calls;

        @Override
        public OwnedSyncHttpClient.Result get(Endpoint endpoint, int failureAttempt) {
            calls++;
            endpoints.add(endpoint);
            if (!results.isEmpty()) return results.removeFirst();
            Instant started = clock.instant();
            beforeResponse.accept(endpoint);
            clock.advance(Duration.ofMillis(100));
            byte[] body = endpoint.equals(Endpoint.bootstrap()) ? bootstrap : fixtures;
            return new Response(200, body, null, new SourceRead(started, clock.instant()));
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant value;
        private MutableClock(Instant value) { this.value = value; }
        private void set(Instant value) { this.value = value; }
        private void advance(Duration duration) { value = value.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
    }
}
