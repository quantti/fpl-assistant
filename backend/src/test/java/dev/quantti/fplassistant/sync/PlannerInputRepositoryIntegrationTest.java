package dev.quantti.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import dev.quantti.fplassistant.sync.AdaptiveMatchdayPlanner.RecoveryMetadata;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.Season;
import dev.quantti.fplassistant.testsupport.SharedStatsSeed;

class PlannerInputRepositoryIntegrationTest {

    private static final OwnedSyncTables TABLES = new OwnedSyncTables(
            "fpl_plan_run_test", "fpl_plan_payload_test", "fpl_plan_receipt_test");
    private static final CatalogueScheduleTables CAPTURED = new CatalogueScheduleTables(
            "fpl_plan_price_test", "fpl_plan_gw_test", "fpl_plan_fixture_test");
    private static final Season SEASON = new Season(97, "2097-98",
            LocalDate.of(2097, 8, 1), LocalDate.of(2098, 5, 31));

    @Test
    void observesLeagueSizedFixtureTransitionsAsOneBatch() {
        try (OwnedSyncMonitoringDatabase work = OwnedSyncMonitoringDatabase.work(environment())) {
            var jdbc = spy(work.jdbc());
            SharedStatsSeed.ensure(jdbc);
            new OwnedSyncSchema(jdbc, TABLES).initialize();
            createCapturedTables(jdbc);
            clearOwnedRows(jdbc);
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

    @Test
    void completedCorrectionsRequireTheCurrentActivePublicationSourceInterval() {
        try (OwnedSyncMonitoringDatabase work = OwnedSyncMonitoringDatabase.work(environment())) {
            var jdbc = spy(work.jdbc());
            SharedStatsSeed.ensure(jdbc);
            new OwnedSyncSchema(jdbc, TABLES).initialize();
            createCapturedTables(jdbc);
            clearOwnedRows(jdbc);
            jdbc.update("DELETE FROM season WHERE id=?", SEASON.id());
            jdbc.update("INSERT INTO season(id,code,name,start_date,end_date,is_current) VALUES (?,?,?,?,?,false)",
                    SEASON.id(), SEASON.code(), "Correction projection season", SEASON.startsOn(), SEASON.endsOn());

            Instant now = Instant.parse("2097-10-01T12:00:00Z");
            Clock clock = Clock.fixed(now, ZoneOffset.UTC);
            var activator = new PlannerRuntimeRepository(jdbc, work.transaction(), clock, TABLES);
            var firstFence = activator.activate(SEASON.code(), OwnedSyncMode.ACTIVE);

            seedVerifiedFixture(jdbc, 21001, now.minusSeconds(2 * 3600L), now,
                    now, now, now, "ACTIVE", "COMPLETED", firstFence.epoch());
            seedVerifiedFixture(jdbc, 21002, now.minusSeconds(3 * 3600L), null,
                    now.minusSeconds(3601), now.minusSeconds(3600), now, "ACTIVE", "COMPLETED",
                    firstFence.epoch());
            seedVerifiedFixture(jdbc, 21003, now.minusSeconds(60 * 3600L), null,
                    now.minusSeconds(10 * 3600L), now.minusSeconds(9 * 3600L), now.minusSeconds(8 * 3600L),
                    "STAGING", "COMPLETED", firstFence.epoch());
            seedVerifiedFixture(jdbc, 21005, now.minusSeconds(60 * 3600L), null,
                    now.minusSeconds(10 * 3600L), now.minusSeconds(9 * 3600L), now,
                    "ACTIVE", "FAILED", firstFence.epoch());
            seedVerifiedFixture(jdbc, 21006, now.minusSeconds(60 * 3600L), null,
                    null, null, now, "ACTIVE", "COMPLETED", firstFence.epoch());
            seedVerifiedFixture(jdbc, 21007, now.minusSeconds(60 * 3600L), null,
                    now.minusSeconds(10 * 3600L), now.minusSeconds(9 * 3600L), now,
                    "ACTIVE", "COMPLETED", firstFence.epoch(), true);
            seedVerifiedFixture(jdbc, 21008, now.minusSeconds(60 * 3600L), null,
                    now.minusSeconds(10 * 3600L), now.minusSeconds(9 * 3600L), now,
                    "ACTIVE", "COMPLETED", firstFence.epoch(), false, "fixture:99999");
            seedVerifiedFixture(jdbc, 21009, now.minusSeconds(60 * 3600L), null,
                    now.plusSeconds(1), now.plusSeconds(2), now.plusSeconds(3),
                    "ACTIVE", "COMPLETED", firstFence.epoch());
            seedVerifiedFixture(jdbc, 21010, now.minusSeconds(60 * 3600L), now.minusSeconds(9 * 3600L),
                    now.minusSeconds(10 * 3600L), now.minusSeconds(9 * 3600L), now.minusSeconds(8 * 3600L),
                    "ACTIVE", "COMPLETED", firstFence.epoch());
            seedVerifiedFixture(jdbc, 21011, now.minusSeconds(60 * 3600L), now.minusSeconds(10 * 3600L),
                    now.minusSeconds(10 * 3600L), now.minusSeconds(9 * 3600L), now.minusSeconds(8 * 3600L),
                    "ACTIVE", "COMPLETED", firstFence.epoch());
            seedVerifiedFixture(jdbc, 21012, now.minusSeconds(60 * 3600L), null,
                    now.minusSeconds(10 * 3600L), now.minusSeconds(9 * 3600L), now.minusSeconds(8 * 3600L),
                    "ACTIVE", "COMPLETED", firstFence.epoch(), false, null, true);
            seedWrongSeasonFixture(jdbc, 21013, now, firstFence.epoch());

            activator.deactivate(firstFence);
            var restartedFence = activator.activate(SEASON.code(), OwnedSyncMode.ACTIVE);
            seedVerifiedFixture(jdbc, 21004, now.minusSeconds(60 * 3600L), null,
                    now.minusSeconds(10 * 3600L), now.minusSeconds(9 * 3600L), now,
                    "ACTIVE", "PENDING", restartedFence.epoch());
            var store = new OwnedSyncStore(jdbc, work.transaction(), clock, TABLES, restartedFence);
            var repository = new PlannerInputRepository(jdbc, store, TABLES, CAPTURED, clock);
            clearInvocations(jdbc);
            Map<Integer, Set<String>> completed = repository.load(SEASON).progress().stream()
                    .collect(Collectors.toMap(AdaptiveMatchdayPlanner.FixtureProgress::fixtureId,
                            AdaptiveMatchdayPlanner.FixtureProgress::completedCorrections));

            assertThat(completed.get(21001)).containsExactlyInAnyOrder("2h", "data-checked");
            assertThat(completed.get(21002)).isEmpty();
            assertThat(completed.get(21003)).isEmpty();
            assertThat(completed.get(21004)).isEmpty();
            assertThat(completed.get(21005)).isEmpty();
            assertThat(completed.get(21006)).isEmpty();
            assertThat(completed.get(21007)).isEmpty();
            assertThat(completed.get(21008)).isEmpty();
            assertThat(completed.get(21009)).isEmpty();
            assertThat(completed.get(21010)).containsExactlyInAnyOrder("2h", "24h", "48h");
            assertThat(completed.get(21011)).containsExactlyInAnyOrder("2h", "24h", "48h", "data-checked");
            assertThat(completed.get(21012)).isEmpty();
            assertThat(completed.get(21013)).isEmpty();
            assertThat(restartedFence.epoch()).isGreaterThan(firstFence.epoch());
            List<String> querySql = mockingDetails(jdbc).getInvocations().stream()
                    .filter(invocation -> invocation.getMethod().getName().startsWith("query")
                            && invocation.getArguments().length > 0
                            && invocation.getArguments()[0] instanceof String)
                    .map(invocation -> ((String) invocation.getArguments()[0]).toLowerCase())
                    .distinct().toList();
            List<String> progressSql = querySql.stream().filter(sql -> sql.contains(TABLES.fixtureSync())
                    && sql.contains("first_finished_observed_at")).toList();
            assertThat(progressSql).hasSize(1);
            assertThat(progressSql.getFirst()).doesNotContain(TABLES.payload(), "player_fixture_stats", "body");
            int progressQuery = querySql.indexOf(progressSql.getFirst());
            assertThat(querySql.subList(progressQuery + 1, querySql.size()))
                    .noneMatch(sql -> sql.contains(TABLES.payload()) || sql.contains("player_fixture_stats")
                            || sql.contains("from player "));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                    + " WHERE run_generation IN (SELECT current_verified_generation FROM "
                    + TABLES.fixtureSync() + " WHERE season_id=?)", Integer.class, SEASON.id())).isZero();
        }
    }

    @Test
    void recoveryProjectionUsesOneMetadataQueryAndAnExactLastActiveReceiptAfterInvalidation() {
        try (OwnedSyncMonitoringDatabase work = OwnedSyncMonitoringDatabase.work(environment())) {
            var jdbc = spy(work.jdbc());
            SharedStatsSeed.ensure(jdbc);
            new OwnedSyncSchema(jdbc, TABLES).initialize();
            createCapturedTables(jdbc);
            clearOwnedRows(jdbc);
            jdbc.update("DELETE FROM season WHERE id=?", SEASON.id());
            jdbc.update("INSERT INTO season(id,code,name,start_date,end_date,is_current) VALUES (?,?,?,?,?,false)",
                    SEASON.id(), SEASON.code(), "Recovery projection season", SEASON.startsOn(), SEASON.endsOn());

            Instant now = Instant.parse("2097-10-01T12:00:00Z");
            Clock clock = Clock.fixed(now, ZoneOffset.UTC);
            var activator = new PlannerRuntimeRepository(jdbc, work.transaction(), clock, TABLES);
            var fence = activator.activate(SEASON.code(), OwnedSyncMode.ACTIVE);
            Instant episodeStart = now.minusSeconds(20 * 3600L);
            Instant readStarted = episodeStart.plusSeconds(60);
            Instant readFinished = readStarted.plusSeconds(60);
            Instant published = readFinished.plusSeconds(60);
            long activeGeneration = seedRun(jdbc, "fixture:21014", "ACTIVE", "COMPLETED", fence.epoch(),
                    readStarted, readFinished, published);
            updateReceipt(jdbc, "fixture:21014", activeGeneration, readStarted, readFinished, published);
            insertRecoveryFixture(jdbc, 21014, activeGeneration, now, episodeStart);

            long stagingGeneration = seedRun(jdbc, "fixture:21015", "STAGING", "COMPLETED", fence.epoch(),
                    readStarted, readFinished, published);
            updateReceipt(jdbc, "fixture:21015", stagingGeneration, readStarted, readFinished, published);
            insertRecoveryFixture(jdbc, 21015, stagingGeneration, now, episodeStart);
            jdbc.update("UPDATE " + TABLES.requestState() + " SET cooldown_until=? WHERE singleton=TRUE",
                    java.sql.Timestamp.from(now.plusSeconds(30 * 60L)));

            var store = new OwnedSyncStore(jdbc, work.transaction(), clock, TABLES, fence);
            var repository = new PlannerInputRepository(jdbc, store, TABLES, CAPTURED, clock);
            clearInvocations(jdbc);
            Map<Integer, RecoveryMetadata> metadata = repository.load(SEASON).progress().stream()
                    .collect(Collectors.toMap(AdaptiveMatchdayPlanner.FixtureProgress::fixtureId,
                            AdaptiveMatchdayPlanner.FixtureProgress::recovery));

            assertThat(metadata.get(21014).recoveryDay()).isEqualTo(LocalDate.of(2097, 9, 30));
            assertThat(metadata.get(21014).episodeStartedAt()).isEqualTo(episodeStart);
            assertThat(metadata.get(21014).publishedAt()).isEqualTo(published);
            assertThat(metadata.get(21014).dueReason())
                    .isEqualTo(SharedFixtureRecoveryPolicy.DueReason.DEPENDENCY);
            assertThat(metadata.get(21014).transportRetryAt()).isEqualTo(now.plusSeconds(20 * 60L));
            assertThat(metadata.get(21014).dependencyRetryAt()).isEqualTo(now.plusSeconds(15 * 60L));
            assertThat(metadata.get(21014).cooldownUntil()).isEqualTo(now.plusSeconds(30 * 60L));
            assertThat(metadata.get(21015).publishedAt()).isNull();

            List<String> querySql = mockingDetails(jdbc).getInvocations().stream()
                    .filter(invocation -> invocation.getMethod().getName().startsWith("query")
                            && invocation.getArguments().length > 0
                            && invocation.getArguments()[0] instanceof String)
                    .map(invocation -> ((String) invocation.getArguments()[0]).toLowerCase())
                    .distinct().toList();
            List<String> progressSql = querySql.stream().filter(sql -> sql.contains(TABLES.fixtureSync())
                    && sql.contains("first_finished_observed_at")).toList();
            assertThat(progressSql).hasSize(1);
            assertThat(progressSql.getFirst()).doesNotContain(TABLES.payload(), "player_fixture_stats", "body")
                    .contains(TABLES.receipt(), TABLES.run(), TABLES.requestState());
        }
    }

    @Test
    void stampsTheFirstFinishedObservationWhenWeSeeIt() {
        try (OwnedSyncMonitoringDatabase work = OwnedSyncMonitoringDatabase.work(environment())) {
            var jdbc = work.jdbc();
            SharedStatsSeed.ensure(jdbc);
            new OwnedSyncSchema(jdbc, TABLES).initialize();
            createCapturedTables(jdbc);
            clearOwnedRows(jdbc);
            jdbc.update("DELETE FROM season WHERE id=?", SEASON.id());
            jdbc.update("INSERT INTO season(id,code,name,start_date,end_date,is_current) VALUES (?,?,?,?,?,false)",
                    SEASON.id(), SEASON.code(), "Backlog season", SEASON.startsOn(), SEASON.endsOn());
            jdbc.update("INSERT INTO " + CAPTURED.gameweek()
                    + "(season,id,data_checked) VALUES (?,1,false)", SEASON.code());
            // The capture says this fixture finished ten days ago; we are seeing it for the first time.
            jdbc.update("INSERT INTO " + CAPTURED.fixture()
                    + "(season,id,gameweek,team_h,team_a,kickoff_time,started,finished,captured_at)"
                    + " VALUES (?,20001,1,1,2,'2097-09-09T18:00:00Z',true,true,?)", SEASON.code(),
                    java.sql.Timestamp.from(Instant.parse("2097-09-09T20:00:00Z")));

            Instant observedNow = Instant.parse("2097-09-19T03:01:00Z");
            Clock clock = Clock.fixed(observedNow, ZoneOffset.UTC);
            var activator = new PlannerRuntimeRepository(jdbc, work.transaction(), clock, TABLES);
            var fence = activator.activate(SEASON.code(), OwnedSyncMode.STAGING);
            var store = new OwnedSyncStore(jdbc, work.transaction(), clock, TABLES, fence);
            var repository = new PlannerInputRepository(jdbc, store, TABLES, CAPTURED, clock);

            repository.load(SEASON);

            assertThat(jdbc.queryForObject("SELECT first_finished_observed_at FROM " + TABLES.fixtureSync()
                    + " WHERE season_id=? AND fixture_id=?", java.sql.Timestamp.class, SEASON.id(), 20001)
                    .toInstant()).isEqualTo(observedNow);
            // The twelve-hour follow-up window now runs from that sighting, so an hour later the
            // fixture is still on the fast cadence instead of being parked until the daily sweep.
            assertThat(new FixtureSyncRepository(jdbc, TABLES)
                    .admitAttempt(SEASON, 20001, observedNow.plusSeconds(3600)).admitted()).isTrue();
        }
    }

    private static void clearOwnedRows(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM " + TABLES.fixtureSync());
        jdbc.update("DELETE FROM " + TABLES.run());
        jdbc.update("DELETE FROM " + TABLES.receipt());
        jdbc.update("DELETE FROM " + TABLES.plannerSlot());
        jdbc.update("DELETE FROM " + TABLES.plannerRuntime());
    }

    private static void insertRecoveryFixture(org.springframework.jdbc.core.JdbcTemplate jdbc, int fixtureId,
                                              long lastGeneration, Instant now, Instant episodeStart) {
        jdbc.update("""
                INSERT INTO %s(season_id,season_code,fixture_id,state,last_verified_generation,
                    first_finished_observed_at,next_due_at,due_reason,transport_retry_at,
                    dependency_retry_at,recovery_day,recovery_started_at,recovery_until,updated_at)
                VALUES (?, ?, ?, 'VERIFYING', ?, ?, ?, 'DEPENDENCY', ?, ?, ?, ?, ?, ?)
                """.formatted(TABLES.fixtureSync()), SEASON.id(), SEASON.code(), fixtureId, lastGeneration,
                java.sql.Timestamp.from(now.minusSeconds(35 * 3600L)),
                java.sql.Timestamp.from(now.plusSeconds(15 * 60L)),
                java.sql.Timestamp.from(now.plusSeconds(20 * 60L)),
                java.sql.Timestamp.from(now.plusSeconds(15 * 60L)),
                java.sql.Date.valueOf(SharedFixtureRecoveryPolicy.recoveryDayAt(episodeStart)),
                java.sql.Timestamp.from(episodeStart), java.sql.Timestamp.from(episodeStart.plus(
                        SharedFixtureRecoveryPolicy.FOLLOW_UP_LIMIT)), java.sql.Timestamp.from(now));
    }

    private static long seedVerifiedFixture(org.springframework.jdbc.core.JdbcTemplate jdbc, int fixtureId,
                                            Instant firstVerified, Instant transition, Instant readStarted,
                                            Instant readFinished, Instant published, String mode, String runState,
                                            long epoch) {
        return seedVerifiedFixture(jdbc, fixtureId, firstVerified, transition, readStarted, readFinished,
                published, mode, runState, epoch, false, null, false);
    }

    private static long seedVerifiedFixture(org.springframework.jdbc.core.JdbcTemplate jdbc, int fixtureId,
                                            Instant firstVerified, Instant transition, Instant readStarted,
                                            Instant readFinished, Instant published, String mode, String runState,
                                            long epoch, boolean superseded) {
        return seedVerifiedFixture(jdbc, fixtureId, firstVerified, transition, readStarted, readFinished,
                published, mode, runState, epoch, superseded, null, false);
    }

    private static long seedVerifiedFixture(org.springframework.jdbc.core.JdbcTemplate jdbc, int fixtureId,
                                            Instant firstVerified, Instant transition, Instant readStarted,
                                            Instant readFinished, Instant published, String mode, String runState,
                                            long epoch, boolean superseded, String runKey) {
        return seedVerifiedFixture(jdbc, fixtureId, firstVerified, transition, readStarted, readFinished,
                published, mode, runState, epoch, superseded, runKey, false);
    }

    private static long seedVerifiedFixture(org.springframework.jdbc.core.JdbcTemplate jdbc, int fixtureId,
                                            Instant firstVerified, Instant transition, Instant readStarted,
                                            Instant readFinished, Instant published, String mode, String runState,
                                            long epoch, boolean superseded, String runKey, boolean invalidated) {
        String key = "fixture:" + fixtureId;
        String actualRunKey = runKey == null ? key : runKey;
        long generation = seedRun(jdbc, actualRunKey, mode, runState, epoch,
                readStarted, readFinished, published);
        jdbc.update("INSERT INTO " + TABLES.receipt() + "(season,publication_key) VALUES (?,?) "
                + "ON CONFLICT DO NOTHING", SEASON.code(), key);
        updateReceipt(jdbc, key, generation, readStarted, readFinished, published);
        if (superseded) {
            long newer = seedRun(jdbc, key, mode, "COMPLETED", epoch,
                    readStarted, readFinished, published);
            updateReceipt(jdbc, key, newer, readStarted, readFinished, published);
        }
        jdbc.update("""
                INSERT INTO %s(season_id,season_code,fixture_id,state,current_verified_generation,
                    first_verified_at,data_checked_transition_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                """.formatted(TABLES.fixtureSync()), SEASON.id(), SEASON.code(), fixtureId,
                invalidated ? "VERIFYING" : "VERIFIED", invalidated ? null : generation,
                invalidated ? null : java.sql.Timestamp.from(firstVerified),
                transition == null ? null : java.sql.Timestamp.from(transition),
                java.sql.Timestamp.from(published == null ? firstVerified : published));
        return generation;
    }

    private static long seedRun(org.springframework.jdbc.core.JdbcTemplate jdbc, String key, String mode,
                                String state, long epoch, Instant readStarted, Instant readFinished,
                                Instant completed) {
        return seedRun(jdbc, SEASON.code(), key, mode, state, epoch, readStarted, readFinished, completed);
    }

    private static long seedRun(org.springframework.jdbc.core.JdbcTemplate jdbc, String runSeason, String key,
                                String mode, String state, long epoch, Instant readStarted,
                                Instant readFinished, Instant completed) {
        jdbc.update("INSERT INTO " + TABLES.receipt() + "(season,publication_key) VALUES (?,?) "
                + "ON CONFLICT DO NOTHING", runSeason, key);
        Instant created = Instant.parse("2097-09-01T00:00:00Z");
        return jdbc.queryForObject("""
                INSERT INTO %s(kind,season,coalescing_key,publication_key,priority,due_at,
                    requested_source_after,runtime_mode,runtime_epoch,state,source_read_started_at,
                    source_read_finished_at,completed_at,failed_at,created_at,updated_at)
                VALUES ('FIXTURE_VERIFICATION',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING generation
                """.formatted(TABLES.run()), Long.class, runSeason, key, key, 1,
                java.sql.Timestamp.from(created), java.sql.Timestamp.from(created), mode, epoch, state,
                readStarted == null ? null : java.sql.Timestamp.from(readStarted),
                readFinished == null ? null : java.sql.Timestamp.from(readFinished),
                "COMPLETED".equals(state) ? java.sql.Timestamp.from(completed) : null,
                "FAILED".equals(state) ? java.sql.Timestamp.from(completed) : null,
                java.sql.Timestamp.from(created), java.sql.Timestamp.from(created));
    }

    private static void seedWrongSeasonFixture(org.springframework.jdbc.core.JdbcTemplate jdbc, int fixtureId,
                                               Instant now, long epoch) {
        String key = "fixture:" + fixtureId;
        Instant start = now.minusSeconds(10 * 3600L);
        Instant end = now.minusSeconds(9 * 3600L);
        Instant published = now.minusSeconds(8 * 3600L);
        long generation = seedRun(jdbc, "2098-99", key, "ACTIVE", "COMPLETED", epoch,
                start, end, published);
        jdbc.update("INSERT INTO " + TABLES.receipt() + "(season,publication_key,latest_generation,"
                + "source_read_started_at,source_read_finished_at,published_at) VALUES (?,?,?,?,?,?)",
                SEASON.code(), key, generation, java.sql.Timestamp.from(start), java.sql.Timestamp.from(end),
                java.sql.Timestamp.from(published));
        jdbc.update("""
                INSERT INTO %s(season_id,season_code,fixture_id,state,current_verified_generation,
                    first_verified_at,updated_at)
                VALUES (?,?,?,'VERIFIED',?,?,?)
                """.formatted(TABLES.fixtureSync()), SEASON.id(), SEASON.code(), fixtureId, generation,
                java.sql.Timestamp.from(now.minusSeconds(60 * 3600L)), java.sql.Timestamp.from(published));
    }

    private static void updateReceipt(org.springframework.jdbc.core.JdbcTemplate jdbc, String key, long generation,
                                      Instant readStarted, Instant readFinished, Instant published) {
        jdbc.update("""
                UPDATE %s SET latest_generation=?,source_read_started_at=?,source_read_finished_at=?,published_at=?
                WHERE season=? AND publication_key=?
                """.formatted(TABLES.receipt()), generation,
                readStarted == null ? null : java.sql.Timestamp.from(readStarted),
                readFinished == null ? null : java.sql.Timestamp.from(readFinished),
                published == null ? null : java.sql.Timestamp.from(published), SEASON.code(), key);
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
