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
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.ReadInterval;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.Season;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.SourceDocument;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.CanonicalSourcePair;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Endpoint;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Lease;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.RuntimeFence;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.SourceRead;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Validation;
import fi.karivanttinen.fplassistant.testsupport.TapasSeed;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class CompatibilityCatalogueWriterIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OwnedSyncTables TABLES = new OwnedSyncTables(
            "fpl_cat_compat_run", "fpl_cat_compat_payload", "fpl_cat_compat_receipt",
            "fpl_cat_compat_request_state", "fpl_cat_compat_request_attempt",
            "fpl_cat_compat_fixture", "fpl_cat_compat_pfs_evidence",
            "fpl_cat_compat_planner_slot", "fpl_cat_compat_planner_runtime",
            "fpl_cat_compat_fixture_dep");
    private static final CatalogueScheduleTables CAPTURED = CatalogueScheduleTables.tests();
    private static final Season SEASON = new Season(27, "2026-27", LocalDate.of(2026, 8, 21), null);
    private static final Instant BASE = Instant.parse("2026-09-13T18:00:00Z");
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static byte[] bootstrap;
    private static byte[] fixtures;
    private static List<Integer> originalCurrentSeasons;
    private static boolean disposableDatabaseValidated;
    private static boolean seasonBaselineCaptured;
    private static boolean seasonFlagsChanged;
    private static boolean cleanupAuthorized;

    private MutableClock clock;
    private OwnedSyncStore store;
    private CompatibilityCatalogueWriter writer;
    private RuntimeFence runtimeFence;
    private Lease lease;

    @BeforeAll
    static void initialize() throws Exception {
        String url = required("DATABASE_URL");
        String user = required("DB_USER");
        String password = required("DB_PASSWORD");
        initialize(url, user, password,
                () -> new JdbcTemplate(new DriverManagerDataSource(url, user, password)));
    }

    static void initialize(String url, String user, String password, Supplier<JdbcTemplate> jdbcSupplier)
            throws Exception {
        jdbc = TapasSeed.openDisposableTestJdbc(url, user, password, jdbcSupplier);
        TapasSeed.ensure(jdbc);
        disposableDatabaseValidated = true;
        TapasSeed.ensureOwnedSyncCaptureSeason(jdbc);
        TapasSeed.ensureOwnedSyncFixture(jdbc);
        originalCurrentSeasons = jdbc.query("SELECT id FROM season WHERE is_current=true ORDER BY id",
                (rs, row) -> rs.getInt(1));
        seasonBaselineCaptured = true;
        tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        new OwnedSyncSchema(jdbc, TABLES).initialize();
        createCapturedTables();
        bootstrap = resource("bootstrap-static.json");
        fixtures = resource("fixtures.json");
        cleanupAuthorized = true;
    }

    @BeforeEach
    void setUp() {
        cleanRows();
        TapasSeed.setCurrentSeason(jdbc, SEASON.id());
        seasonFlagsChanged = true;
        clock = new MutableClock(BASE);
        runtimeFence = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES)
                .activate(SEASON.code(), OwnedSyncMode.ACTIVE);
        store = new OwnedSyncStore(jdbc, tx, clock, TABLES, runtimeFence);
        writer = new CompatibilityCatalogueWriter(jdbc, clock, store, TABLES);
        seedArchiveTeamSentinel();
        seedCaptureSentinels();
    }

    @AfterEach
    void tearDown() {
        if (!cleanupAuthorized || jdbc == null) return;
        if (runtimeFence != null) {
            new PlannerRuntimeRepository(jdbc, tx, clock, TABLES).deactivate(runtimeFence);
        }
        cleanRows();
        restoreCurrentSeasons();
    }

    @AfterAll
    static void restoreSeedSeason() {
        restoreCurrentSeasons();
    }

    @Test
    void writesOneCompleteCurrentSnapshotThenDoesNoCompatibilityWritesForEqualValues() throws Exception {
        Documents first = documents(bootstrap, fixtures, BASE.plusSeconds(1));
        Lease firstLease = newLease(BASE.plusMillis(900));
        stage(firstLease, first);
        var initial = writer.publish(firstLease, SEASON, first.bootstrap(), first.fixtures());

        assertThat(initial.teamRows()).isEqualTo(20);
        assertThat(initial.playerRows()).isEqualTo(657);
        assertThat(initial.gameweekRows()).isEqualTo(38);
        assertThat(initial.fixtureRows()).isEqualTo(380);
        assertThat(initial.changedFixtureIds()).isEmpty();
        assertThat(initial.writtenRows()).isEqualTo(1_095);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM team WHERE season_id=27", Integer.class)).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player WHERE season_id=27", Integer.class)).isEqualTo(657);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gameweek WHERE season_id=27", Integer.class)).isEqualTo(38);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixture WHERE season_id=27", Integer.class)).isEqualTo(380);
        assertThat(jdbc.queryForObject("SELECT team_id FROM player WHERE season_id=27 AND id=1", Integer.class))
                .isEqualTo(CatalogueScheduleParser.parse(SEASON, first.bootstrap(), first.fixtures())
                        .players().stream().filter(player -> player.id() == 1).findFirst().orElseThrow().teamId());
        Instant firstWrite = timestamp("SELECT updated_at FROM player WHERE season_id=27 AND id=1");
        Instant archiveTeamTimestamp = timestamp("SELECT updated_at FROM team WHERE season_id=9825 AND id=1");
        assertCaptureSentinels();

        complete(firstLease);
        Documents equalValues = documents(bootstrap, fixtures, BASE.plusSeconds(10));
        Lease secondLease = newLease(BASE.plusSeconds(9));
        stage(secondLease, equalValues);
        var unchanged = writer.publish(secondLease, SEASON, equalValues.bootstrap(), equalValues.fixtures());

        assertThat(unchanged.writtenRows()).isZero();
        assertThat(unchanged.changedFixtureIds()).isEmpty();
        for (String table : List.of("team", "player", "gameweek", "fixture")) {
            assertThat(timestamp("SELECT updated_at FROM " + table + " WHERE season_id=27" + idClause(table)))
                    .as(table + " unchanged rows retain their publication time").isEqualTo(firstWrite);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM team WHERE season_id=9825 AND id=9801",
                Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT name FROM team WHERE season_id=9825 AND id=1", String.class))
                .isEqualTo("Archive sentinel");
        assertThat(timestamp("SELECT updated_at FROM team WHERE season_id=9825 AND id=1"))
                .isEqualTo(archiveTeamTimestamp);
        assertCaptureSentinels();
        assertPublishedSourcePairIsNotAnOldCanonicalParent(secondLease, equalValues);
    }

    @Test
    void publishesNanosecondReadIntervalsAfterTheirPostgresMicrosecondRoundTrip() throws Exception {
        Instant start = Instant.parse("2026-09-13T18:00:00.123456100Z");
        Documents docs = new Documents(
                new SourceDocument(bootstrap, new ReadInterval(start,
                        Instant.parse("2026-09-13T18:00:00.123456500Z"))),
                new SourceDocument(fixtures, new ReadInterval(
                        Instant.parse("2026-09-13T18:00:00.123456600Z"),
                        Instant.parse("2026-09-13T18:00:00.123456900Z"))));
        clock.set(start.plusMillis(500));
        Lease current = newLease(start.minusMillis(1));
        stage(current, docs);

        var publication = writer.publish(current, SEASON, docs.bootstrap(), docs.fixtures());

        assertThat(publication.writtenRows()).isEqualTo(1_095);
        assertThat(jdbc.queryForObject("SELECT source_bootstrap_read_started_at FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", java.sql.Timestamp.class, SEASON.code(),
                OwnedSyncStore.INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY).toInstant())
                .isEqualTo(OwnedSyncStore.postgresTimestampPrecision(start));
        assertCaptureSentinels();
    }

    @Test
    void writesOnlyChangedNamedValuesRevokesPriorFixtureProofAndReturnsChangedHeaders() throws Exception {
        Documents initial = documents(bootstrap, fixtures, BASE.plusSeconds(1));
        Lease firstLease = newLease(BASE.plusMillis(900));
        stage(firstLease, initial);
        writer.publish(firstLease, SEASON, initial.bootstrap(), initial.fixtures());
        var parsed = CatalogueScheduleParser.parse(SEASON, initial.bootstrap(), initial.fixtures());
        var changedFixture = parsed.fixtures().stream().filter(row -> row.id() == 1).findFirst().orElseThrow();
        int changedGameweek = changedFixture.gameweek();
        long oldRevision = jdbc.queryForObject("SELECT current_invalidation_revision FROM "
                + TABLES.gameweekStatSync() + " WHERE season_id=27 AND gameweek=?", Long.class, changedGameweek);
        jdbc.update("""
                INSERT INTO %s(season_id,season_code,fixture_id,state,current_verified_generation,
                    last_verified_generation,verified_header,verified_stats_fingerprint,last_verified_at,updated_at)
                VALUES (27,'2026-27',1,'VERIFIED',?,?,?,?,?,?)
                """.formatted(TABLES.fixtureSync()), firstLease.generation(), firstLease.generation(),
                FixtureVerification.verifiedHeader(changedFixture), FixtureStatsFingerprint.of(changedFixture),
                java.sql.Timestamp.from(clock.instant()), java.sql.Timestamp.from(clock.instant()));
        int pfsRowsBefore = jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats WHERE season_id=27",
                Integer.class);

        complete(firstLease);
        byte[] revisedBootstrap = reviseBootstrap(bootstrap);
        byte[] revisedFixtures = reviseFixtureScore(fixtures, 1);
        Documents revised = documents(revisedBootstrap, revisedFixtures, BASE.plusSeconds(10));
        Lease secondLease = newLease(BASE.plusSeconds(9));
        stage(secondLease, revised);
        var publication = writer.publish(secondLease, SEASON, revised.bootstrap(), revised.fixtures());

        assertThat(publication.teamRows()).isEqualTo(1);
        assertThat(publication.playerRows()).isEqualTo(1);
        assertThat(publication.gameweekRows()).isEqualTo(1);
        assertThat(publication.fixtureRows()).isEqualTo(1);
        assertThat(publication.changedFixtureIds()).containsExactly(1);
        assertThat(publication.invalidatedFixtureIds()).containsExactly(1);
        assertThat(publication.writtenRows()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT team_id FROM player WHERE season_id=27 AND id=1", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player WHERE season_id=27 AND id=2", Integer.class))
                .isOne();
        int originalStrength = find(JSON.readTree(bootstrap).withArray("teams"), "id", 1)
                .get("strength").intValue();
        assertThat(jdbc.queryForObject("SELECT strength FROM team WHERE season_id=27 AND id=1", Integer.class))
                .isNotEqualTo(originalStrength);
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.fixtureSync()
                + " WHERE season_id=27 AND fixture_id=1", String.class)).isEqualTo("VERIFYING");
        assertThat(jdbc.queryForObject("SELECT current_verified_generation IS NULL FROM " + TABLES.fixtureSync()
                + " WHERE season_id=27 AND fixture_id=1", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT last_verified_generation FROM " + TABLES.fixtureSync()
                + " WHERE season_id=27 AND fixture_id=1", Long.class)).isEqualTo(firstLease.generation());
        assertThat(jdbc.queryForObject("SELECT current_invalidation_revision FROM "
                + TABLES.gameweekStatSync() + " WHERE season_id=27 AND gameweek=?", Long.class, changedGameweek))
                .isGreaterThan(oldRevision);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats WHERE season_id=27",
                Integer.class)).isEqualTo(pfsRowsBefore);
        assertCaptureSentinels();
    }

    @Test
    void malformedOrForeignKeyInvalidSnapshotAndRejectedPlayingSeasonWriteNothing() throws Exception {
        Lease malformedLease = newLease(BASE.plusMillis(900));
        ObjectNode invalidBootstrap = (ObjectNode) JSON.readTree(bootstrap);
        ((ObjectNode) invalidBootstrap.withArray("elements").get(0)).put("team", 999);
        Documents malformed = documents(JSON.writeValueAsBytes(invalidBootstrap), fixtures, BASE.plusSeconds(1));
        stage(malformedLease, malformed);
        assertThatThrownBy(() -> writer.publish(malformedLease, SEASON,
                malformed.bootstrap(), malformed.fixtures())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("references unknown team");
        assertCoreRowsEmpty();

        complete(malformedLease);

        Documents stale = documents(bootstrap, fixtures, BASE.plusSeconds(8));
        Lease staleLease = newLease(BASE.plusSeconds(9));
        stage(staleLease, stale);
        assertThatThrownBy(() -> writer.publish(staleLease, SEASON, stale.bootstrap(), stale.fixtures()))
                .isInstanceOf(OwnedSyncStore.SourceDemandAdvancedException.class);
        assertCoreRowsEmpty();
        complete(staleLease);

        Documents partial = documents(bootstrap, omitFixture(fixtures, 1), BASE.plusSeconds(13));
        Lease partialLease = newLease(BASE.plusSeconds(12));
        stage(partialLease, partial);
        assertThatThrownBy(() -> writer.publish(partialLease, SEASON, partial.bootstrap(), partial.fixtures()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("incomplete league schedule");
        assertCoreRowsEmpty();
        complete(partialLease);

        Documents valid = documents(bootstrap, fixtures, BASE.plusSeconds(16));
        Lease wrongSeasonLease = newLease(BASE.plusSeconds(15));
        stage(wrongSeasonLease, valid);
        TapasSeed.setCurrentSeason(jdbc, originalCurrentSeasons.getFirst());
        assertThatThrownBy(() -> writer.publish(wrongSeasonLease, SEASON, valid.bootstrap(), valid.fixtures()))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("playing");
        assertCoreRowsEmpty();
        assertCaptureSentinels();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.receipt()
                + " WHERE season='2026-27' AND publication_key IN (?,?)",
                Integer.class, CatalogueScheduleWriter.SHARED_TEAM_SOURCE_RECEIPT_KEY,
                CatalogueScheduleWriter.FIXTURE_SOURCE_RECEIPT_KEY)).isZero();
    }

    @Test
    void leaseExpiringInsidePublicationCallbackRollsBackRowsAndSourcePublication() throws Exception {
        Documents docs = documents(bootstrap, fixtures, BASE.plusSeconds(1));
        Lease active = newLease(BASE.plusMillis(900));
        stage(active, docs);
        CanonicalSourcePair pair = store.incrementalSourcePair(active, docs.bootstrap(), docs.fixtures());
        SourceRead combined = combined(docs);

        assertThatThrownBy(() -> store.publishIncrementalCompatibility(active, SEASON, pair, combined, () -> {
            jdbc.update("INSERT INTO team(id,season_id,code,name,short_name) "
                    + "VALUES (9001,27,9001,'Callback','CBK')");
            clock.set(active.expiresAt());
            return 1;
        })).isInstanceOf(OwnedSyncStore.LeaseLostException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM team WHERE season_id=27", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.receipt()
                + " WHERE season='2026-27' AND publication_key IN (?,?)", Integer.class,
                CatalogueScheduleWriter.SHARED_TEAM_SOURCE_RECEIPT_KEY,
                CatalogueScheduleWriter.FIXTURE_SOURCE_RECEIPT_KEY)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.receipt()
                + " WHERE season='2026-27' AND publication_key=?", Integer.class,
                OwnedSyncStore.INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                + " WHERE run_generation=? AND source_published", Integer.class, active.generation())).isZero();
        assertCaptureSentinels();
    }

    private Lease newLease(Instant sourceStart) {
        if (lease != null) complete(lease);
        clock.set(sourceStart.minusMillis(1));
        store.enqueueIncrementalCoreImport(SEASON, clock.instant());
        lease = store.acquireIncrementalCoreImport(SEASON.code(), "compatibility-test", Duration.ofMinutes(5))
                .orElseThrow();
        return lease;
    }

    private void stage(Lease current, Documents docs) {
        Instant sourceCompletion = docs.fixtures().readInterval().completedAt().plusMillis(200);
        if (clock.instant().isBefore(sourceCompletion)) clock.set(sourceCompletion);
        long bootstrapId = store.stage(current, Endpoint.bootstrap(), read(docs.bootstrap()),
                Validation.unchecked(), docs.bootstrap().bytes());
        long fixtureId = store.stage(current, Endpoint.fixtures(), read(docs.fixtures()),
                Validation.unchecked(), docs.fixtures().bytes());
        store.validateStagedPayloads(current, List.of(bootstrapId, fixtureId), Validation.valid());
    }

    private void complete(Lease current) {
        if (current == null) return;
        store.completeStaged(current, store.incrementalSourceInterval(current), List.of());
        if (lease != null && lease.generation() == current.generation()) lease = null;
    }

    private Documents documents(byte[] bootstrapBytes, byte[] fixtureBytes, Instant start) {
        SourceDocument bootstrapDoc = new SourceDocument(bootstrapBytes,
                new ReadInterval(start, start.plusMillis(100)));
        SourceDocument fixtureDoc = new SourceDocument(fixtureBytes,
                new ReadInterval(start.plusMillis(200), start.plusMillis(300)));
        clock.set(start.plusMillis(500));
        return new Documents(bootstrapDoc, fixtureDoc);
    }

    private static SourceRead read(SourceDocument document) {
        return new SourceRead(document.readInterval().startedAt(), document.readInterval().completedAt());
    }

    private static SourceRead combined(Documents docs) {
        return new SourceRead(docs.bootstrap().readInterval().startedAt(),
                docs.fixtures().readInterval().completedAt());
    }

    private static byte[] reviseBootstrap(byte[] bytes) throws Exception {
        ObjectNode root = (ObjectNode) JSON.readTree(bytes);
        ObjectNode team = find(root.withArray("teams"), "id", 1);
        team.put("strength", team.get("strength").intValue() + 1);
        ObjectNode player = find(root.withArray("elements"), "id", 1);
        ObjectNode newTeam = find(root.withArray("teams"), "id", 2);
        player.put("team", 2).put("team_code", newTeam.get("code").intValue())
                .put("status", "d").put("now_cost", player.get("now_cost").intValue() + 1);
        ArrayNode elements = root.withArray("elements");
        for (int index = 0; index < elements.size(); index++) {
            if (elements.get(index).get("id").intValue() == 2) {
                elements.remove(index);
                break;
            }
        }
        ObjectNode event = find(root.withArray("events"), "id", 3);
        event.put("highest_score", event.path("highest_score").isInt()
                ? event.get("highest_score").intValue() + 1 : 100);
        return JSON.writeValueAsBytes(root);
    }

    private static byte[] reviseFixtureScore(byte[] bytes, int id) throws Exception {
        ArrayNode root = (ArrayNode) JSON.readTree(bytes);
        ObjectNode fixture = find(root, "id", id);
        fixture.put("team_h_score", fixture.get("team_h_score").intValue() + 1);
        return JSON.writeValueAsBytes(root);
    }

    private static byte[] omitFixture(byte[] bytes, int id) throws Exception {
        ArrayNode root = (ArrayNode) JSON.readTree(bytes);
        for (int index = 0; index < root.size(); index++) {
            if (root.get(index).get("id").intValue() == id) {
                root.remove(index);
                break;
            }
        }
        return JSON.writeValueAsBytes(root);
    }

    private static ObjectNode find(ArrayNode rows, String field, int value) {
        for (var row : rows) if (row.get(field).intValue() == value) return (ObjectNode) row;
        throw new IllegalArgumentException("Missing " + field + "=" + value);
    }

    private void assertPublishedSourcePairIsNotAnOldCanonicalParent(Lease lease, Documents docs) {
        CanonicalSourcePair pair = store.incrementalSourcePair(lease, docs.bootstrap(), docs.fixtures());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                + " WHERE run_generation=? AND source_published", Integer.class, lease.generation())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE generation=? AND producer_key='INCREMENTAL_CORE' AND kind='PLAYER_HISTORY'",
                Integer.class, lease.generation())).isOne();
        assertThat(store.currentPublishedCanonicalPair(SEASON.code(), pair, clock.instant())).isEmpty();
        assertCaptureSentinels();
    }

    private static String idClause(String table) {
        return switch (table) {
            case "team", "player", "fixture", "gameweek" -> " AND id=1";
            default -> "";
        };
    }

    private static Instant timestamp(String sql) {
        return jdbc.queryForObject(sql, java.sql.Timestamp.class).toInstant();
    }

    private void assertCoreRowsEmpty() {
        for (String table : List.of("team", "player", "gameweek", "fixture")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE season_id=27",
                    Integer.class)).as(table).isZero();
        }
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

    private void seedArchiveTeamSentinel() {
        jdbc.update("""
                INSERT INTO team(id,season_id,code,name,short_name,strength,updated_at)
                VALUES (1,9825,1,'Archive sentinel','ARC',1,TIMESTAMPTZ '2025-01-01 00:00:00Z')
                ON CONFLICT (id,season_id) DO UPDATE SET name=EXCLUDED.name,
                    short_name=EXCLUDED.short_name,strength=EXCLUDED.strength,updated_at=EXCLUDED.updated_at
                """);
    }

    private void seedCaptureSentinels() {
        jdbc.update("INSERT INTO " + CAPTURED.dailyPrice()
                + "(season,price_date,element_id,web_name,now_cost,captured_at) VALUES"
                + "('guard',DATE '2026-09-01',1,'Guard',50,TIMESTAMPTZ '2026-09-01 00:00:00Z')");
        jdbc.update("INSERT INTO " + CAPTURED.gameweek()
                + "(season,id,name,deadline_time,captured_at) VALUES"
                + "('guard',99,'Guard',TIMESTAMPTZ '2026-09-01 10:00:00Z',TIMESTAMPTZ '2026-09-01 00:00:00Z')");
        jdbc.update("INSERT INTO " + CAPTURED.fixture()
                + "(season,id,team_h,team_a,captured_at) VALUES"
                + "('guard',999,1,2,TIMESTAMPTZ '2026-09-01 00:00:00Z')");
    }

    private static void assertCaptureSentinels() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + CAPTURED.dailyPrice(), Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + CAPTURED.gameweek(), Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + CAPTURED.fixture(), Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + CAPTURED.dailyPrice()
                + " WHERE season='guard' AND element_id=1", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT captured_at FROM " + CAPTURED.dailyPrice()
                + " WHERE season='guard' AND element_id=1", java.sql.Timestamp.class).toInstant())
                .isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + CAPTURED.gameweek()
                + " WHERE season='guard' AND id=99", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT captured_at FROM " + CAPTURED.gameweek()
                + " WHERE season='guard' AND id=99", java.sql.Timestamp.class).toInstant())
                .isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + CAPTURED.fixture()
                + " WHERE season='guard' AND id=999", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT captured_at FROM " + CAPTURED.fixture()
                + " WHERE season='guard' AND id=999", java.sql.Timestamp.class).toInstant())
                .isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
    }

    private static void cleanRows() {
        jdbc.update("DELETE FROM " + TABLES.gameweekStatSync());
        jdbc.update("DELETE FROM " + TABLES.playerFixtureCurrent());
        jdbc.update("DELETE FROM " + TABLES.historyFingerprint());
        jdbc.update("DELETE FROM " + TABLES.playerFixtureEvidence());
        jdbc.update("DELETE FROM " + TABLES.fixtureDependencyManifest());
        jdbc.update("DELETE FROM " + TABLES.fixtureSync());
        jdbc.update("DELETE FROM " + TABLES.requestAttempt());
        jdbc.update("DELETE FROM " + TABLES.requestState());
        jdbc.update("DELETE FROM " + TABLES.plannerSlot());
        jdbc.update("DELETE FROM " + TABLES.payload());
        jdbc.update("DELETE FROM " + TABLES.run());
        jdbc.update("DELETE FROM " + TABLES.receipt());
        jdbc.update("DELETE FROM " + TABLES.plannerRuntime());
        jdbc.update("DELETE FROM " + CAPTURED.dailyPrice());
        jdbc.update("DELETE FROM " + CAPTURED.gameweek());
        jdbc.update("DELETE FROM " + CAPTURED.fixture());
        jdbc.update("DELETE FROM fixture WHERE season_id=27");
        jdbc.update("DELETE FROM gameweek WHERE season_id=27");
        jdbc.update("DELETE FROM player WHERE season_id=27");
        jdbc.update("DELETE FROM team WHERE season_id=27");
    }

    private static void restoreCurrentSeasons() {
        if (!disposableDatabaseValidated || !seasonBaselineCaptured || !seasonFlagsChanged || jdbc == null) return;
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE season SET is_current=false");
            originalCurrentSeasons.forEach(id -> jdbc.update("UPDATE season SET is_current=true WHERE id=?", id));
        });
        seasonFlagsChanged = false;
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream input = CompatibilityCatalogueWriterIntegrationTest.class
                .getResourceAsStream("/owned-sync-source-fixtures/" + name)) {
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
        private void set(Instant next) { instant = next; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
