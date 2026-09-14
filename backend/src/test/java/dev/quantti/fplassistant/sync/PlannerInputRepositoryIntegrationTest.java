package dev.quantti.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import dev.quantti.fplassistant.sync.CatalogueScheduleParser.Season;
import dev.quantti.fplassistant.testsupport.TapasSeed;

class PlannerInputRepositoryIntegrationTest {

    private static final OwnedSyncTables TABLES = OwnedSyncTables.tests();
    private static final CatalogueScheduleTables CAPTURED = new CatalogueScheduleTables(
            "fpl_plan_price_test", "fpl_plan_gw_test", "fpl_plan_fixture_test");
    private static final Season SEASON = new Season(97, "2097-98",
            LocalDate.of(2097, 8, 1), LocalDate.of(2098, 5, 31));

    @Test
    void observesLeagueSizedFixtureTransitionsAsOneBatch() {
        try (OwnedSyncMonitoringDatabase work = OwnedSyncMonitoringDatabase.work(environment())) {
            var jdbc = spy(work.jdbc());
            TapasSeed.ensure(jdbc);
            new OwnedSyncSchema(jdbc, TABLES).initialize();
            createCapturedTables(jdbc);
            jdbc.update("DELETE FROM " + TABLES.fixtureSync() + " WHERE season_id=?", SEASON.id());
            jdbc.update("DELETE FROM " + TABLES.plannerSlot() + " WHERE season=?", SEASON.code());
            jdbc.update("DELETE FROM " + TABLES.plannerRuntime() + " WHERE instance_id=?", "mode:" + SEASON.code());
            jdbc.update("DELETE FROM season WHERE id=?", SEASON.id());
            jdbc.update("INSERT INTO season(id,code,name,start_date,end_date,is_current) VALUES (?,?,?,?,?,false)",
                    SEASON.id(), SEASON.code(), "Planner batch season", SEASON.startsOn(), SEASON.endsOn());
            jdbc.update("INSERT INTO " + CAPTURED.dailyPrice()
                    + "(season,price_date,element_id,team_id) VALUES (?,'2097-09-19',1,1)", SEASON.code());
            jdbc.update("INSERT INTO " + CAPTURED.gameweek()
                    + "(season,id,data_checked) SELECT ?,g,false FROM generate_series(1,38) g", SEASON.code());
            jdbc.update("INSERT INTO " + CAPTURED.fixture()
                    + "(season,id,gameweek,team_h,team_a,kickoff_time,started,finished,captured_at)"
                    + " SELECT ?,10000+g,1+((g-1)%38),1,2,'2097-09-20'::timestamptz,false,false,?"
                    + " FROM generate_series(1,380) g", SEASON.code(),
                    java.sql.Timestamp.from(Instant.parse("2097-09-19T03:00:00Z")));

            Clock clock = Clock.fixed(Instant.parse("2097-09-19T03:01:00Z"), ZoneOffset.UTC);
            var activator = new PlannerRuntimeRepository(jdbc, work.transaction(), clock, TABLES);
            var fence = activator.activate(SEASON.code(), OwnedSyncMode.STAGING);
            var store = new OwnedSyncStore(jdbc, work.transaction(), clock, TABLES, fence);
            var repository = new PlannerInputRepository(jdbc, store, TABLES, CAPTURED, clock);

            assertThat(repository.load(SEASON).fixtures()).hasSize(380);
            verify(jdbc).batchUpdate(startsWith("INSERT INTO " + TABLES.fixtureSync()),
                    argThat(rows -> rows.size() == 380), any(int[].class));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.fixtureSync()
                    + " WHERE season_id=?", Integer.class, SEASON.id())).isEqualTo(380);
        }
    }

    private static void createCapturedTables(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + CAPTURED.dailyPrice()
                + " (season varchar(10), price_date date, element_id integer, team_id integer)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + CAPTURED.gameweek()
                + " (season varchar(10), id integer, data_checked boolean)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + CAPTURED.fixture()
                + " (season varchar(10), id integer, gameweek integer, team_h integer, team_a integer,"
                + " kickoff_time timestamptz, started boolean, finished boolean, captured_at timestamptz)");
        jdbc.update("DELETE FROM " + CAPTURED.dailyPrice() + " WHERE season=?", SEASON.code());
        jdbc.update("DELETE FROM " + CAPTURED.gameweek() + " WHERE season=?", SEASON.code());
        jdbc.update("DELETE FROM " + CAPTURED.fixture() + " WHERE season=?", SEASON.code());
    }

    private static MockEnvironment environment() {
        return new MockEnvironment()
                .withProperty("spring.datasource.url", required("DATABASE_URL"))
                .withProperty("spring.datasource.username", required("DB_USER"))
                .withProperty("spring.datasource.password", required("DB_PASSWORD"));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
