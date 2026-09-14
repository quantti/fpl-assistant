package fi.karivanttinen.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.ReadInterval;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.Season;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.SourceDocument;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleWriter.PublicationDeferredException;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleWriter.SourceRegressionException;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Lease;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.SyncKind;
import fi.karivanttinen.fplassistant.testsupport.TapasSeed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class CatalogueScheduleWriterIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OwnedSyncTables OWNED = OwnedSyncTables.tests();
    private static final CatalogueScheduleTables CAPTURED = CatalogueScheduleTables.tests();
    private static final Season SEASON = new Season(27, "2026-27", LocalDate.of(2026, 8, 21), null);
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static byte[] bootstrap;
    private static byte[] fixtures;

    private MutableClock clock;
    private OwnedSyncStore store;
    private CatalogueScheduleWriter writer;

    @BeforeAll
    static void initialize() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(required("DATABASE_URL"),
                required("DB_USER"), required("DB_PASSWORD"));
        TapasSeed.ensure(new JdbcTemplate(dataSource)); // safety guard must run before shared-table seeding
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        new OwnedSyncSchema(jdbc, OWNED).initialize();
        createCapturedTables();
        createFailureTables();
        bootstrap = resource("bootstrap-static.json");
        fixtures = resource("fixtures.json");
    }

    @BeforeEach
    void setUp() {
        cleanWriterRows();
        jdbc.update("UPDATE season SET is_current = false");
        jdbc.update("""
                INSERT INTO season(id, code, name, start_date, end_date, is_current)
                VALUES (27, '2026-27', '2026/27', DATE '2026-08-21', NULL, true)
                ON CONFLICT (id) DO UPDATE SET code=EXCLUDED.code, name=EXCLUDED.name,
                    start_date=EXCLUDED.start_date, end_date=EXCLUDED.end_date, is_current=true
                """);
        clock = new MutableClock(Instant.parse("2026-09-13T18:00:00Z"));
        store = new OwnedSyncStore(jdbc, tx, clock, OWNED);
        writer = new CatalogueScheduleWriter(jdbc, clock, store, OWNED, CAPTURED);
    }

    @AfterEach
    void restoreSeedSeason() {
        cleanWriterRows();
        jdbc.update("DELETE FROM season WHERE id = 27");
        jdbc.update("UPDATE season SET is_current = (id = ?)", TapasSeed.CURRENT_SEASON_ID);
    }

    @Test
    void cataloguePriceIsAtomicFullDayAndKeepsDepartedCorePlayerAndArchives() throws Exception {
        Documents first = documents(Instant.parse("2026-09-13T17:44:00Z"));
        writer.publishCataloguePrice(lease("first", CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY),
                SEASON, first.bootstrap, first.fixtures);
        int removedId = JSON.readTree(bootstrap).withArray("elements").get(0).get("id").intValue();
        jdbc.update("INSERT INTO " + CAPTURED.dailyPrice() + " (season, price_date, element_id, web_name, now_cost)"
                + " VALUES ('2026-27', DATE '2026-09-12', 999999, 'Yesterday', 50)");
        jdbc.update("INSERT INTO team(id, season_id, code, name, short_name) VALUES (1, 9027, 1, 'Archive', 'ARC')"
                + " ON CONFLICT DO NOTHING");

        ObjectNode changed = (ObjectNode) JSON.readTree(bootstrap);
        changed.withArray("elements").remove(0);
        ((ObjectNode) changed.withArray("elements").get(0)).put("status", "i");
        Documents second = documents(Instant.parse("2026-09-13T17:50:00Z"), JSON.writeValueAsBytes(changed), fixtures);
        writer.publishCataloguePrice(lease("second", CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY),
                SEASON, second.bootstrap, second.fixtures);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + CAPTURED.dailyPrice()
                + " WHERE season='2026-27' AND price_date=DATE '2026-09-13'", Integer.class)).isEqualTo(656);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + CAPTURED.dailyPrice()
                + " WHERE element_id=999999", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player WHERE season_id=27 AND id=?",
                Integer.class, removedId)).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM " + CAPTURED.dailyPrice()
                + " WHERE season='2026-27' AND price_date=DATE '2026-09-13' ORDER BY element_id LIMIT 1",
                String.class)).isEqualTo("i");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM team WHERE season_id=9027 AND id=1",
                Integer.class)).isOne();
        assertThat(capturedAt(CAPTURED.dailyPrice(), "season='2026-27' AND price_date=DATE '2026-09-13'"))
                .isEqualTo(second.bootstrap.readInterval().completedAt());
        assertThat(updatedAt("team")).isEqualTo(clock.instant());
        assertThat(updatedAt("player")).isEqualTo(clock.instant());
    }

    @Test
    void sourceCaptureTimeAndLaterCorePublicationTimeRemainDistinct() {
        clock.set(Instant.parse("2026-09-13T03:00:00Z"));
        Documents docs = documents(Instant.parse("2026-09-13T02:00:00Z"));

        writer.publishCataloguePrice(lease("read-two-publish-three",
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY), SEASON,
                docs.bootstrap, docs.fixtures);

        assertThat(updatedAt("team")).isEqualTo(clock.instant());
        assertThat(updatedAt("player")).isEqualTo(clock.instant());
        assertThat(capturedAt(CAPTURED.dailyPrice(),
                "season='2026-27' AND price_date=DATE '2026-09-13'"))
                .isEqualTo(docs.bootstrap.readInterval().completedAt());
        assertSourceReceipt(CatalogueScheduleWriter.SHARED_TEAM_SOURCE_RECEIPT_KEY,
                docs.bootstrap.readInterval());
    }

    @Test
    void schedulePublishesParentsCoreAndBothCompleteCaptureBlocksAtOneSourceTime() throws Exception {
        ArrayNode changedFixtures = (ArrayNode) JSON.readTree(fixtures);
        ((ObjectNode) changedFixtures.get(377)).putNull("event");
        ((ObjectNode) changedFixtures.get(378)).putNull("kickoff_time");
        ((ObjectNode) changedFixtures.get(379)).putNull("event").putNull("kickoff_time");
        Documents docs = documents(Instant.parse("2026-09-13T17:45:00Z"), bootstrap,
                JSON.writeValueAsBytes(changedFixtures));
        jdbc.update("INSERT INTO " + CAPTURED.fixture()
                + " (season,id,team_h,team_a,started,finished) VALUES ('old-season',1,1,2,false,false)");

        writer.publishSchedule(lease("schedule", CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY),
                SEASON, docs.bootstrap, docs.fixtures);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM gameweek WHERE season_id=27", Integer.class))
                .isEqualTo(38);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixture WHERE season_id=27", Integer.class))
                .isEqualTo(380);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + CAPTURED.gameweek()
                + " WHERE season='2026-27'", Integer.class)).isEqualTo(38);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + CAPTURED.fixture()
                + " WHERE season='2026-27'", Integer.class)).isEqualTo(380);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + CAPTURED.fixture()
                + " WHERE season='old-season'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT gameweek IS NULL AND kickoff_time IS NOT NULL FROM fixture"
                + " WHERE season_id=27 AND id=378", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT gameweek IS NOT NULL AND kickoff_time IS NULL FROM fixture"
                + " WHERE season_id=27 AND id=379", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT gameweek IS NULL AND kickoff_time IS NULL FROM fixture"
                + " WHERE season_id=27 AND id=380", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT finished_provisional FROM fixture WHERE season_id=27 AND id=1",
                Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT stats::text LIKE '%goals_scored%' FROM fixture"
                + " WHERE season_id=27 AND id=1", Boolean.class)).isTrue();
        Instant expected = docs.bootstrap.readInterval().completedAt().isAfter(docs.fixtures.readInterval().completedAt())
                ? docs.bootstrap.readInterval().completedAt() : docs.fixtures.readInterval().completedAt();
        assertThat(capturedAt(CAPTURED.gameweek(), "season='2026-27'")).isEqualTo(expected);
        assertThat(capturedAt(CAPTURED.fixture(), "season='2026-27'")).isEqualTo(expected);
        assertThat(updatedAt("team")).isEqualTo(clock.instant());
        assertThat(updatedAt("gameweek")).isEqualTo(clock.instant());
        assertThat(updatedAt("fixture")).isEqualTo(clock.instant());
        assertSourceReceipt(CatalogueScheduleWriter.SHARED_TEAM_SOURCE_RECEIPT_KEY,
                docs.bootstrap.readInterval());
        assertSourceReceipt(CatalogueScheduleWriter.FIXTURE_SOURCE_RECEIPT_KEY,
                docs.fixtures.readInterval());

        jdbc.update("INSERT INTO gameweek(id,season_id,name,deadline_time,updated_at) VALUES"
                + " (99,27,'Core-only',TIMESTAMPTZ '2027-06-01 10:00:00Z',TIMESTAMPTZ '2026-09-13 17:44:00Z')");
        jdbc.update("INSERT INTO fixture(id,season_id,code,team_h,team_a,updated_at) VALUES"
                + " (999,27,999,1,2,TIMESTAMPTZ '2026-09-13 17:44:00Z')");
        jdbc.update("INSERT INTO " + CAPTURED.gameweek()
                + " (season,id,name,deadline_time,captured_at) VALUES"
                + " ('2026-27',99,'Absent',TIMESTAMPTZ '2027-06-01 10:00:00Z',TIMESTAMPTZ '2026-09-13 17:44:00Z')");
        jdbc.update("INSERT INTO " + CAPTURED.fixture()
                + " (season,id,team_h,team_a,captured_at) VALUES"
                + " ('2026-27',999,1,2,TIMESTAMPTZ '2026-09-13 17:44:00Z')");
        writer.publishSchedule(lease("schedule-again", CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY),
                SEASON, docs.bootstrap, docs.fixtures);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM gameweek WHERE season_id=27 AND id=99",
                Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixture WHERE season_id=27 AND id=999",
                Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + CAPTURED.gameweek()
                + " WHERE season='2026-27' AND id=99", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + CAPTURED.fixture()
                + " WHERE season='2026-27' AND id=999", Integer.class)).isZero();
    }

    @Test
    void statsOnlyScheduleCorrectionRevokesVerifiedFixture() throws Exception {
        Documents original = documents(Instant.parse("2026-09-13T17:45:00Z"));
        Lease originalLease = lease("original-schedule", CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY);
        writer.publishSchedule(originalLease, SEASON, original.bootstrap, original.fixtures);
        var fixture = CatalogueScheduleParser.parse(SEASON, original.bootstrap, original.fixtures)
                .fixtures().getFirst();
        jdbc.update("INSERT INTO " + OWNED.fixtureSync() + " (season_id, season_code, fixture_id,"
                        + " state, current_verified_generation, last_verified_generation, verified_header,"
                        + " verified_stats_fingerprint, last_verified_at, validation_failures, updated_at)"
                        + " VALUES (?, ?, ?, 'VERIFIED', ?, ?, ?, ?, ?, '{}', ?)",
                SEASON.id(), SEASON.code(), fixture.id(), originalLease.generation(),
                originalLease.generation(), FixtureVerification.verifiedHeader(fixture),
                FixtureStatsFingerprint.of(fixture), java.sql.Timestamp.from(clock.instant()),
                java.sql.Timestamp.from(clock.instant()));

        ArrayNode correctedFixtures = (ArrayNode) JSON.readTree(fixtures);
        ((ObjectNode) correctedFixtures.get(0).withArray("stats").get(0)
                .withArray("h").get(0)).put("value", 2);
        Documents corrected = documents(Instant.parse("2026-09-13T17:50:00Z"), bootstrap,
                JSON.writeValueAsBytes(correctedFixtures));
        writer.publishSchedule(lease("corrected-schedule", CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY),
                SEASON, corrected.bootstrap, corrected.fixtures);

        assertThat(jdbc.queryForObject("SELECT state FROM " + OWNED.fixtureSync()
                + " WHERE season_id=27 AND fixture_id=1", String.class)).isEqualTo("VERIFYING");
        assertThat(jdbc.queryForObject("SELECT validation_failures::text FROM " + OWNED.fixtureSync()
                + " WHERE season_id=27 AND fixture_id=1", String.class)).isEqualTo("{SOURCE_CORRECTION}");
        assertThat(jdbc.queryForObject("SELECT current_verified_generation IS NULL FROM "
                + OWNED.fixtureSync() + " WHERE season_id=27 AND fixture_id=1", Boolean.class)).isTrue();
    }

    @Test
    void exactCutoffsAcceptButEarlierPublicationDefersAndWrongDayOrFutureReadsReject() {
        clock.set(Instant.parse("2026-09-13T00:30:00Z")); // 01:30 BST
        Documents oneAm = documents(Instant.parse("2026-09-13T00:10:00Z"));
        Lease deferred = lease("deferred", CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY);
        assertThatThrownBy(() -> writer.publishCataloguePrice(deferred, SEASON,
                oneAm.bootstrap, oneAm.fixtures)).isInstanceOf(PublicationDeferredException.class);

        clock.set(Instant.parse("2026-09-13T01:00:00Z")); // exactly 02:00 BST
        writer.publishCataloguePrice(deferred, SEASON, oneAm.bootstrap, oneAm.fixtures);

        clock.set(Instant.parse("2026-09-13T02:00:00Z")); // exactly 03:00 BST
        Documents beforeThree = documents(Instant.parse("2026-09-13T01:50:00Z"));
        writer.publishSchedule(lease("three", CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY),
                SEASON, beforeThree.bootstrap, beforeThree.fixtures);

        Documents yesterday = documents(Instant.parse("2026-09-12T22:50:00Z"));
        assertThatThrownBy(() -> writer.publishSchedule(
                lease("old", CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY), SEASON,
                yesterday.bootstrap, yesterday.fixtures)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("today's UK date");
        Documents future = documents(clock.instant().plusSeconds(1));
        assertThatThrownBy(() -> writer.publishSchedule(
                lease("future", CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY), SEASON,
                future.bootstrap, future.fixtures)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("future");
    }

    @Test
    void canonicalLeaseSeasonAndExactlyOneMatchingCurrentSeasonAreRequired() {
        Documents docs = documents(Instant.parse("2026-09-13T17:45:00Z"));
        Lease wrongKey = lease("wrong", "another-key");
        assertThatThrownBy(() -> writer.publishSchedule(wrongKey, SEASON, docs.bootstrap, docs.fixtures))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("canonical");
        Lease wrongCatalogueKey = lease("wrong-catalogue", "invented-catalogue-key");
        assertThatThrownBy(() -> writer.publishCataloguePrice(
                wrongCatalogueKey, SEASON, docs.bootstrap, docs.fixtures))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("canonical");

        jdbc.update("UPDATE season SET is_current=false WHERE id=27");
        assertThatThrownBy(() -> writer.publishSchedule(
                lease("none", CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY), SEASON,
                docs.bootstrap, docs.fixtures)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one");

        jdbc.update("UPDATE season SET is_current=true WHERE id IN (27, ?)", TapasSeed.CURRENT_SEASON_ID);
        assertThatThrownBy(() -> writer.publishSchedule(
                lease("multiple", CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY), SEASON,
                docs.bootstrap, docs.fixtures)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one");

        jdbc.update("UPDATE season SET is_current=(id=?)", TapasSeed.CURRENT_SEASON_ID);
        assertThatThrownBy(() -> writer.publishSchedule(
                lease("mismatch", CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY), SEASON,
                docs.bootstrap, docs.fixtures)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not exactly match");
    }

    @Test
    void newerGenerationCannotRegressTheDurableBootstrapSourceReceipt() {
        Documents newer = documents(Instant.parse("2026-09-13T17:50:00Z"));
        writer.publishCataloguePrice(lease("newer", CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY),
                SEASON, newer.bootstrap, newer.fixtures);
        Documents older = documents(Instant.parse("2026-09-13T17:40:00Z"));

        assertThatThrownBy(() -> writer.publishCataloguePrice(
                lease("older", CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY), SEASON,
                older.bootstrap, older.fixtures)).isInstanceOf(SourceRegressionException.class)
                .hasMessageContaining("source:bootstrap-static");
        assertSourceReceipt(CatalogueScheduleWriter.SHARED_TEAM_SOURCE_RECEIPT_KEY,
                newer.bootstrap.readInterval());
    }

    @Test
    void crossKeyPublicationSerializesOnSeasonAndRejectsOlderGroupAtomically() throws Exception {
        Documents newer = documents(Instant.parse("2026-09-13T17:50:00Z"));
        Documents older = documents(Instant.parse("2026-09-13T17:40:00Z"));
        Lease newerCatalogue = lease("newer-catalogue",
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY);
        Lease olderSchedule = lease("older-schedule", CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY);
        CountDownLatch seasonLocked = new CountDownLatch(1);
        CountDownLatch releaseSeason = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(3)) {
            Future<?> blocker = executor.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM season WHERE id=27 FOR UPDATE", Integer.class);
                seasonLocked.countDown();
                await(releaseSeason);
            }));
            assertThat(seasonLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Integer> newerResult = executor.submit(() -> writer.publishCataloguePrice(
                    newerCatalogue, SEASON, newer.bootstrap, newer.fixtures));
            awaitSeasonLockWaiters(1);
            Future<Integer> olderResult = executor.submit(() -> writer.publishSchedule(
                    olderSchedule, SEASON, older.bootstrap, older.fixtures));
            awaitSeasonLockWaiters(2);

            releaseSeason.countDown();
            blocker.get(5, TimeUnit.SECONDS);
            assertThat(newerResult.get(20, TimeUnit.SECONDS)).isPositive();
            assertThatThrownBy(() -> olderResult.get(20, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseInstanceOf(SourceRegressionException.class)
                    .hasRootCauseMessage("source:bootstrap-static completion predates its source receipt");
        } finally {
            releaseSeason.countDown();
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM gameweek WHERE season_id=27", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixture WHERE season_id=27", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + CAPTURED.gameweek()
                + " WHERE season='2026-27'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + CAPTURED.fixture()
                + " WHERE season='2026-27'", Integer.class)).isZero();
        assertReceiptUnpublished(olderSchedule);
        assertThat(jdbc.queryForObject("SELECT source_read_finished_at FROM " + OWNED.receipt()
                + " WHERE season='2026-27' AND publication_key=?", java.sql.Timestamp.class,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY).toInstant())
                .isEqualTo(newer.bootstrap.readInterval().completedAt());
        assertThat(jdbc.queryForObject("SELECT MIN(updated_at)=MAX(updated_at) FROM team WHERE season_id=27",
                Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT MAX(updated_at) FROM team WHERE season_id=27",
                java.sql.Timestamp.class).toInstant()).isEqualTo(clock.instant());
    }

    @Test
    void bootstrapReceiptCanBeReusedAcrossCatalogueAndSchedulePublications() {
        Documents docs = documents(Instant.parse("2026-09-13T17:45:00Z"));
        writer.publishCataloguePrice(lease("catalogue-shared-source",
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY), SEASON,
                docs.bootstrap, docs.fixtures);

        writer.publishSchedule(lease("schedule-shared-source",
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY), SEASON,
                docs.bootstrap, docs.fixtures);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixture WHERE season_id=27", Integer.class))
                .isEqualTo(380);
        assertSourceReceipt(CatalogueScheduleWriter.SHARED_TEAM_SOURCE_RECEIPT_KEY,
                docs.bootstrap.readInterval());
    }

    @Test
    void aNewerCombinedScheduleReadCannotHideOneRegressedEndpoint() {
        Documents first = documents(Instant.parse("2026-09-13T17:45:00Z"));
        writer.publishSchedule(lease("first-schedule", CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY),
                SEASON, first.bootstrap, first.fixtures);
        SourceDocument olderBootstrap = new SourceDocument(bootstrap, new ReadInterval(
                Instant.parse("2026-09-13T17:40:00Z"), Instant.parse("2026-09-13T17:40:02Z")));
        SourceDocument newerFixtures = new SourceDocument(fixtures, new ReadInterval(
                Instant.parse("2026-09-13T17:49:00Z"), Instant.parse("2026-09-13T17:49:02Z")));

        assertThatThrownBy(() -> writer.publishSchedule(
                lease("mixed-schedule", CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY), SEASON,
                olderBootstrap, newerFixtures)).isInstanceOf(SourceRegressionException.class)
                .hasMessageContaining("source:bootstrap-static");
        assertSourceReceipt(CatalogueScheduleWriter.SHARED_TEAM_SOURCE_RECEIPT_KEY,
                first.bootstrap.readInterval());
        assertSourceReceipt(CatalogueScheduleWriter.FIXTURE_SOURCE_RECEIPT_KEY,
                first.fixtures.readInterval());
    }

    @Test
    void firstOwnedPublicationDoesNotTreatLegacyUpdatedAtAsSourceEvidence() {
        Instant legacyWrite = clock.instant().plusSeconds(3600);
        jdbc.update("INSERT INTO team(id,season_id,code,name,short_name,updated_at)"
                + " VALUES (1,27,1,'Legacy','LEG',?)", java.sql.Timestamp.from(legacyWrite));
        jdbc.update("INSERT INTO player(id,season_id,team_id,web_name,element_type,now_cost,updated_at)"
                + " VALUES (1,27,1,'Legacy',1,40,?)", java.sql.Timestamp.from(legacyWrite));
        Documents docs = documents(Instant.parse("2026-09-13T17:45:00Z"));

        writer.publishCataloguePrice(lease("legacy-cutover",
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY), SEASON,
                docs.bootstrap, docs.fixtures);

        assertThat(updatedAt("team")).isEqualTo(clock.instant());
        assertThat(updatedAt("player")).isEqualTo(clock.instant());
        assertSourceReceipt(CatalogueScheduleWriter.SHARED_TEAM_SOURCE_RECEIPT_KEY,
                docs.bootstrap.readInterval());
    }

    @Test
    void lateFailureRollsBackReservedReceiptAdvancementWithRows() {
        Documents first = documents(Instant.parse("2026-09-13T17:40:00Z"));
        writer.publishSchedule(lease("receipt-baseline", CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY),
                SEASON, first.bootstrap, first.fixtures);
        Documents newer = documents(Instant.parse("2026-09-13T17:50:00Z"));
        CatalogueScheduleWriter badSchedule = new CatalogueScheduleWriter(jdbc, clock, store, OWNED,
                new CatalogueScheduleTables(CAPTURED.dailyPrice(), CAPTURED.gameweek(),
                        "fpl_fixture_fail_test"));

        assertThatThrownBy(() -> badSchedule.publishSchedule(
                lease("receipt-rollback", CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY), SEASON,
                newer.bootstrap, newer.fixtures)).isInstanceOf(RuntimeException.class);

        assertSourceReceipt(CatalogueScheduleWriter.SHARED_TEAM_SOURCE_RECEIPT_KEY,
                first.bootstrap.readInterval());
        assertSourceReceipt(CatalogueScheduleWriter.FIXTURE_SOURCE_RECEIPT_KEY,
                first.fixtures.readInterval());
        assertThat(jdbc.queryForObject("SELECT source_read_finished_at FROM " + OWNED.receipt()
                + " WHERE season='2026-27' AND publication_key=?", java.sql.Timestamp.class,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY).toInstant())
                .isEqualTo(first.fixtures.readInterval().completedAt());
    }

    @Test
    void lateFailureRollsBackEachFencedUnitIncludingReceipt() {
        Documents docs = documents(Instant.parse("2026-09-13T17:45:00Z"));
        CatalogueScheduleWriter badPrice = new CatalogueScheduleWriter(jdbc, clock, store, OWNED,
                new CatalogueScheduleTables("fpl_daily_price_fail_test", CAPTURED.gameweek(), CAPTURED.fixture()));
        Lease priceLease = lease("bad-price", CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY);
        assertThatThrownBy(() -> badPrice.publishCataloguePrice(priceLease, SEASON, docs.bootstrap, docs.fixtures))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player WHERE season_id=27", Integer.class)).isZero();
        assertReceiptUnpublished(priceLease);
        assertSourceReceiptAbsent(CatalogueScheduleWriter.SHARED_TEAM_SOURCE_RECEIPT_KEY);

        CatalogueScheduleWriter badSchedule = new CatalogueScheduleWriter(jdbc, clock, store, OWNED,
                new CatalogueScheduleTables(CAPTURED.dailyPrice(), CAPTURED.gameweek(),
                        "fpl_fixture_fail_test"));
        Lease scheduleLease = lease("bad-schedule", CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY);
        assertThatThrownBy(() -> badSchedule.publishSchedule(scheduleLease, SEASON, docs.bootstrap, docs.fixtures))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixture WHERE season_id=27", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + CAPTURED.gameweek()
                + " WHERE season='2026-27'", Integer.class)).isZero();
        assertReceiptUnpublished(scheduleLease);
        assertSourceReceiptAbsent(CatalogueScheduleWriter.SHARED_TEAM_SOURCE_RECEIPT_KEY);
        assertSourceReceiptAbsent(CatalogueScheduleWriter.FIXTURE_SOURCE_RECEIPT_KEY);
    }

    private Lease lease(String coalescing, String publicationKey) {
        store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(), coalescing, publicationKey, clock.instant());
        return store.acquire("writer", Duration.ofHours(1)).orElseThrow();
    }

    private static Documents documents(Instant start) {
        return documents(start, bootstrap, fixtures);
    }

    private static Documents documents(Instant start, byte[] bootstrapBytes, byte[] fixtureBytes) {
        SourceDocument bootstrapDoc = new SourceDocument(bootstrapBytes,
                new ReadInterval(start, start.plusSeconds(2)));
        SourceDocument fixturesDoc = new SourceDocument(fixtureBytes,
                new ReadInterval(start.plusSeconds(3), start.plusSeconds(4)));
        return new Documents(bootstrapDoc, fixturesDoc);
    }

    private static void awaitSeasonLockWaiters(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND pid <> pg_backend_pid()
                      AND wait_event_type = 'Lock'
                      AND query LIKE '%FROM season WHERE id =%'
                      AND query LIKE '%FOR UPDATE%'
                    """, Integer.class);
            if (waiting != null && waiting >= expected) return;
            Thread.onSpinWait();
        }
        throw new AssertionError("Expected " + expected + " publishers waiting on the season row");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static Instant capturedAt(String table, String predicate) {
        return jdbc.queryForObject("SELECT MAX(captured_at) FROM " + table + " WHERE " + predicate,
                java.sql.Timestamp.class).toInstant();
    }

    private static Instant updatedAt(String table) {
        return jdbc.queryForObject("SELECT MAX(updated_at) FROM " + table + " WHERE season_id=27",
                java.sql.Timestamp.class).toInstant();
    }

    private static void assertSourceReceipt(String key, ReadInterval expected) {
        var row = jdbc.queryForMap("SELECT source_read_started_at, source_read_finished_at FROM "
                + OWNED.receipt() + " WHERE season='2026-27' AND publication_key=?", key);
        assertThat(((java.sql.Timestamp) row.get("source_read_started_at")).toInstant())
                .isEqualTo(expected.startedAt());
        assertThat(((java.sql.Timestamp) row.get("source_read_finished_at")).toInstant())
                .isEqualTo(expected.completedAt());
    }

    private static void assertSourceReceiptAbsent(String key) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.receipt()
                + " WHERE season='2026-27' AND publication_key=?", Integer.class, key)).isZero();
    }

    private static void assertReceiptUnpublished(Lease lease) {
        assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + OWNED.receipt()
                + " WHERE season=? AND publication_key=?", Long.class, lease.season(), lease.publicationKey())).isNull();
        assertThat(jdbc.queryForObject("SELECT state FROM " + OWNED.run() + " WHERE generation=?",
                String.class, lease.generation())).isEqualTo("LEASED");
    }

    private static void cleanWriterRows() {
        jdbc.update("DELETE FROM " + OWNED.fixtureSync());
        jdbc.update("DELETE FROM " + OWNED.payload());
        jdbc.update("DELETE FROM " + OWNED.run());
        jdbc.update("DELETE FROM " + OWNED.receipt());
        jdbc.update("DELETE FROM " + CAPTURED.fixture());
        jdbc.update("DELETE FROM " + CAPTURED.gameweek());
        jdbc.update("DELETE FROM " + CAPTURED.dailyPrice());
        jdbc.update("DELETE FROM fpl_daily_price_fail_test");
        jdbc.update("DELETE FROM fpl_fixture_fail_test");
        jdbc.update("DELETE FROM fixture WHERE season_id=27");
        jdbc.update("DELETE FROM gameweek WHERE season_id=27");
        jdbc.update("DELETE FROM player WHERE season_id=27");
        jdbc.update("DELETE FROM team WHERE season_id=27");
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

    private static void createFailureTables() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS fpl_daily_price_fail_test (LIKE "
                + CAPTURED.dailyPrice() + " INCLUDING ALL)");
        jdbc.execute("ALTER TABLE fpl_daily_price_fail_test ENABLE ROW LEVEL SECURITY");
        jdbc.execute("ALTER TABLE fpl_daily_price_fail_test DROP CONSTRAINT IF EXISTS fail_price");
        jdbc.execute("ALTER TABLE fpl_daily_price_fail_test ADD CONSTRAINT fail_price CHECK (now_cost < 0)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fpl_fixture_fail_test (LIKE "
                + CAPTURED.fixture() + " INCLUDING ALL)");
        jdbc.execute("ALTER TABLE fpl_fixture_fail_test ENABLE ROW LEVEL SECURITY");
        jdbc.execute("ALTER TABLE fpl_fixture_fail_test DROP CONSTRAINT IF EXISTS fail_fixture");
        jdbc.execute("ALTER TABLE fpl_fixture_fail_test ADD CONSTRAINT fail_fixture CHECK (id < 0)");
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream input = CatalogueScheduleWriterIntegrationTest.class.getResourceAsStream(
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

    private record Documents(SourceDocument bootstrap, SourceDocument fixtures) { }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        private void set(Instant instant) { this.instant = instant; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
