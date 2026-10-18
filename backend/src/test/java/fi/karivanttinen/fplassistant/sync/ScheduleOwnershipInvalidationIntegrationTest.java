package fi.karivanttinen.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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

import fi.karivanttinen.fplassistant.defcon.DefensiveStatsSchema;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.FixtureRow;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.ReadInterval;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.Season;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.SourceDocument;
import fi.karivanttinen.fplassistant.sync.HistoryParser.Component;
import fi.karivanttinen.fplassistant.sync.HistoryParser.HistoryBatch;
import fi.karivanttinen.fplassistant.sync.HistoryParser.HistoryRow;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Lease;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.RuntimeFence;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.SyncKind;
import fi.karivanttinen.fplassistant.testsupport.TapasSeed;
import fi.karivanttinen.fplassistant.tools.PfsReadSource;

class ScheduleOwnershipInvalidationIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OwnedSyncTables OWNED = new OwnedSyncTables(
            "fpl_schown_run_test", "fpl_schown_payload_test", "fpl_schown_receipt_test",
            "fpl_schown_req_state_test", "fpl_schown_req_attempt_test", "fpl_schown_fixture_test",
            "fpl_schown_pfs_test", "fpl_schown_plan_slot_test", "fpl_schown_plan_runtime_test",
            "fpl_schown_manifest_test");
    private static final CatalogueScheduleTables CAPTURED = new CatalogueScheduleTables(
            "fpl_schown_price_test", "fpl_schown_gw_test", "fpl_schown_capture_fixture_test");
    private static final String FAIL_FIXTURE = "fpl_schown_fail_fixture_test";
    private static final Season SEASON = new Season(29196, "2196-97",
            LocalDate.of(2196, 8, 21), null);
    private static final LocalDate RECOVERY_DAY = LocalDate.of(2196, 9, 13);
    private static final Instant CLOCK_START = Instant.parse("2196-09-13T17:44:00Z");
    private static final String FIXTURES = "/owned-sync-source-fixtures/fixtures.json";
    private static final String BOOTSTRAP = "/owned-sync-source-fixtures/bootstrap-static.json";
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static List<Integer> previousCurrent;

    @BeforeAll
    static void initialize() {
        dataSource = new DriverManagerDataSource(requiredEnv("DATABASE_URL"),
                requiredEnv("DB_USER"), requiredEnv("DB_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        TapasSeed.ensureOwnedSyncCaptureSeason(jdbc);
        assertThat(jdbc.queryForObject("SELECT code FROM season WHERE id=27", String.class))
                .isEqualTo("2026-27");
        new DefensiveStatsSchema(jdbc).ensureColumns();
        previousCurrent = jdbc.queryForList("SELECT id FROM season WHERE is_current=true", Integer.class);
        jdbc.update("INSERT INTO season(id,code,name,start_date,end_date,is_current) "
                        + "VALUES (?,?,?, ?,NULL,false) ON CONFLICT (id) DO NOTHING",
                SEASON.id(), SEASON.code(), "Schedule ownership test", SEASON.startsOn());
        new OwnedSyncSchema(jdbc, OWNED).initialize();
        createCapturedTables();
        new fi.karivanttinen.fplassistant.schedule.ScheduleSchema(jdbc,
                CAPTURED.gameweek(), CAPTURED.fixture()).ensureSchema();
        jdbc.execute("ALTER TABLE " + FAIL_FIXTURE + " ADD COLUMN IF NOT EXISTS finished_provisional BOOLEAN");
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    static void cleanup() {
        if (jdbc == null) return;
        cleanOwnedRows();
        cleanCoreRows();
        jdbc.update("UPDATE season SET is_current=false WHERE id=?", SEASON.id());
        jdbc.update("DELETE FROM season WHERE id=?", SEASON.id());
        previousCurrent.forEach(id -> jdbc.update("UPDATE season SET is_current=true WHERE id=?", id));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM season WHERE id=?", Integer.class, SEASON.id()))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT code FROM season WHERE id=27", String.class))
                .isEqualTo("2026-27");
    }

    @Test
    void changedScheduleRetainsFirstObservationOwnershipQueuesAtomicallyAndNeedsTwoFreshBatches() throws Exception {
        cleanOwnedRows();
        cleanCoreRows();
        jdbc.update("UPDATE season SET is_current=false WHERE is_current=true");
        jdbc.update("UPDATE season SET is_current=true WHERE id=?", SEASON.id());
        MutableClock clock = new MutableClock(CLOCK_START);
        RuntimeFence fence = new PlannerRuntimeRepository(jdbc, tx, clock, OWNED)
                .activate(SEASON.code(), OwnedSyncMode.ACTIVE);
        OwnedSyncStore store = new OwnedSyncStore(jdbc, tx, clock, OWNED, fence);
        CatalogueScheduleWriter writer = new CatalogueScheduleWriter(jdbc, clock, store, OWNED, CAPTURED);
        var catalogue = scheduleDocuments(clock.instant().plusSeconds(60), false, false);
        Lease catalogueLease = enqueueCatalogue(store, clock);
        clock.set(catalogue.fixtures().readInterval().completedAt().plusSeconds(1));
        writer.publishCataloguePrice(catalogueLease, SEASON, catalogue.bootstrap(), catalogue.fixtures());

        var original = scheduleDocuments(clock.instant().plusSeconds(60), false, false);
        Lease originalSchedule = enqueueSchedule(store, clock);
        clock.set(original.fixtures().readInterval().completedAt().plusSeconds(1));
        writer.publishSchedule(originalSchedule, SEASON, original.bootstrap(), original.fixtures());
        assertThat(capturedFrame(29)).isEqualTo(original.fixtures().readInterval().completedAt());

        FixtureRow originalFixture = fixture(original, 29);
        int homePlayer = playerForTeam(original.bootstrap(), originalFixture.homeTeamId());
        int legacyAwayPlayer = playerForTeam(original.bootstrap(), originalFixture.awayTeamId());
        seedLegacyPfs(originalFixture, homePlayer);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats WHERE season_id=? AND fixture_id=?",
                Integer.class, SEASON.id(), originalFixture.id())).isOne();

        Inputs first = inputs(originalFixture, homePlayer, legacyAwayPlayer,
                original.fixtures().readInterval().completedAt().plusSeconds(3), new BigDecimal("0.30"));
        clock.set(first.reads().startedAt());
        Lease firstLease = enqueueFixture(store, originalFixture.id(), clock);
        clock.set(first.reads().finishedAt().plusSeconds(1));
        var pending = publisher(jdbc, clock, store).publishParsed(firstLease, SEASON, originalFixture,
                first.fixtureRead(), first.live(), first.histories());
        assertThat(pending.reasons()).containsExactly(FixtureVerification.Reason.NEEDS_SECOND_OBSERVATION);
        long firstObservationGeneration = firstLease.generation();
        jdbc.update("UPDATE " + OWNED.fixtureSync() + " SET first_finished_observed_at=?, recovery_day=?, "
                        + "recovery_started_at=?, recovery_until=?, due_reason='DEPENDENCY', "
                        + "dependency_retry_at=?, transport_retry_at=? WHERE season_id=? AND fixture_id=?",
                Timestamp.from(clock.instant().minusSeconds(60)), RECOVERY_DAY,
                Timestamp.from(clock.instant().minusSeconds(30)), Timestamp.from(clock.instant().plusSeconds(600)),
                Timestamp.from(clock.instant().plusSeconds(200)), Timestamp.from(clock.instant().plusSeconds(300)),
                SEASON.id(), originalFixture.id());
        Map<String, Object> pendingBefore = fixtureState(originalFixture.id());
        assertThat(pendingBefore.get("current_verified_generation")).isNull();
        assertThat(pendingBefore.get("last_verified_generation")).isNull();
        assertThat(pendingBefore.get("observation_generation")).isEqualTo(firstObservationGeneration);
        assertThat(JSON.readTree((String) pendingBefore.get("observation")).path("coherent").booleanValue())
                .isTrue();

        var unchanged = scheduleDocuments(clock.instant().plusSeconds(60), false, false);
        Lease unchangedLease = enqueueSchedule(store, clock);
        clock.set(unchanged.fixtures().readInterval().completedAt().plusSeconds(1));
        writer.publishSchedule(unchangedLease, SEASON, unchanged.bootstrap(), unchanged.fixtures());
        assertThat(fixtureState(originalFixture.id())).isEqualTo(pendingBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.run()
                + " WHERE kind='FIXTURE_VERIFICATION' AND publication_key=? AND state IN ('PENDING','LEASED')",
                Integer.class, FixtureSyncPublisher.publicationKey(originalFixture.id()))).isZero();

        Instant correctionReadAt = clock.instant().plusSeconds(10);
        var corrected = scheduleDocuments(correctionReadAt, true, false);
        FixtureRow correctedFixture = fixture(corrected, 29);
        int currentAwayPlayer = playerForTeam(corrected.bootstrap(), correctedFixture.awayTeamId());
        assertThat(correctedFixture.homeTeamId()).isEqualTo(originalFixture.homeTeamId());
        assertThat(correctedFixture.awayTeamId()).isNotEqualTo(originalFixture.awayTeamId());
        assertThat(correctedFixture.kickoff()).isNotEqualTo(originalFixture.kickoff());
        assertThat(correctedFixture.code()).isNotEqualTo(originalFixture.code());

        Lease correctionLease = enqueueSchedule(store, clock);
        clock.set(corrected.fixtures().readInterval().completedAt().plusSeconds(1));
        CatalogueScheduleWriter failingWriter = new CatalogueScheduleWriter(jdbc, clock, store, OWNED,
                new CatalogueScheduleTables(CAPTURED.dailyPrice(), CAPTURED.gameweek(), FAIL_FIXTURE));
        Map<String, Object> originalScheduleReceipt = sourceState(
                CatalogueScheduleWriter.SHARED_TEAM_SOURCE_RECEIPT_KEY);
        Map<String, Object> originalFixtureSourceReceipt = sourceState(CatalogueScheduleWriter.FIXTURE_SOURCE_RECEIPT_KEY);
        Map<String, Object> originalCapturedFixture = capturedFixture(originalFixture.id());
        Map<String, Object> originalPublicationReceipt = publicationState(
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY);
        assertThatThrownBy(() -> failingWriter.publishSchedule(correctionLease, SEASON,
                corrected.bootstrap(), corrected.fixtures())).rootCause()
                .hasMessageContaining("fpl_schown_fail_fixture_id");

        assertThat(sourceState(CatalogueScheduleWriter.SHARED_TEAM_SOURCE_RECEIPT_KEY))
                .isEqualTo(originalScheduleReceipt);
        assertThat(sourceState(CatalogueScheduleWriter.FIXTURE_SOURCE_RECEIPT_KEY))
                .isEqualTo(originalFixtureSourceReceipt);
        assertThat(publicationState(CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY))
                .isEqualTo(originalPublicationReceipt);
        assertThat(fixtureState(originalFixture.id())).isEqualTo(pendingBefore);
        assertThat(coreFixture(originalFixture.id()).get("team_a")).isEqualTo(originalFixture.awayTeamId());
        assertThat(coreFixture(originalFixture.id()).get("kickoff_time")).isEqualTo(
                Timestamp.from(originalFixture.kickoff()));
        assertThat(capturedFixture(originalFixture.id())).isEqualTo(originalCapturedFixture);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.run()
                + " WHERE kind='FIXTURE_VERIFICATION' AND publication_key=? AND state IN ('PENDING','LEASED')",
                Integer.class, FixtureSyncPublisher.publicationKey(originalFixture.id()))).isZero();

        writer.publishSchedule(correctionLease, SEASON, corrected.bootstrap(), corrected.fixtures());
        assertThat(coreFixture(originalFixture.id()).get("team_a")).isEqualTo(correctedFixture.awayTeamId());
        assertThat(coreFixture(originalFixture.id()).get("kickoff_time")).isEqualTo(
                Timestamp.from(correctedFixture.kickoff()));
        assertThat(capturedFixture(originalFixture.id()).get("team_a")).isEqualTo(correctedFixture.awayTeamId());
        Map<String, Object> invalidated = fixtureState(originalFixture.id());
        assertThat(invalidated.get("state")).isEqualTo("VERIFYING");
        assertThat(invalidated.get("observation_generation")).isEqualTo(firstObservationGeneration);
        assertThat(invalidated.get("current_verified_generation")).isNull();
        assertThat(invalidated.get("last_verified_generation")).isNull();
        assertThat(JSON.readTree((String) invalidated.get("observation")).path("coherent").booleanValue())
                .isFalse();
        assertThat(invalidated.get("recovery_day")).isEqualTo(pendingBefore.get("recovery_day"));
        assertThat(invalidated.get("recovery_started_at")).isEqualTo(pendingBefore.get("recovery_started_at"));
        assertThat(invalidated.get("recovery_until")).isEqualTo(pendingBefore.get("recovery_until"));
        assertThat(invalidated.get("due_reason")).isEqualTo("DEPENDENCY");
        assertThat(invalidated.get("dependency_retry_at")).isEqualTo(pendingBefore.get("dependency_retry_at"));
        assertThat(invalidated.get("transport_retry_at")).isEqualTo(pendingBefore.get("transport_retry_at"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.run()
                + " WHERE kind='FIXTURE_VERIFICATION' AND publication_key=? AND state='PENDING'",
                Integer.class, FixtureSyncPublisher.publicationKey(originalFixture.id()))).isOne();
        assertThat(readRows("OFF", originalFixture.id())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT expected_goals FROM player_fixture_stats "
                + "WHERE season_id=? AND fixture_id=? AND player_id=?", BigDecimal.class,
                SEASON.id(), originalFixture.id(), homePlayer)).isEqualByComparingTo("0.77");
        Inputs freshFirst = inputs(correctedFixture, homePlayer, currentAwayPlayer,
                clock.instant().plusSeconds(1), new BigDecimal("0.20"));
        Lease freshFirstLease = store.acquireForSeason(SEASON.code(), "schedule-test", Duration.ofMinutes(5))
                .orElseThrow();
        assertThat(store.requestedSourceAfter(freshFirstLease)).isBeforeOrEqualTo(freshFirst.reads().startedAt());
        clock.set(freshFirst.reads().finishedAt().plusSeconds(1));
        var firstFreshDecision = publisher(jdbc, clock, store).publishParsed(freshFirstLease, SEASON,
                correctedFixture, freshFirst.fixtureRead(), freshFirst.live(), freshFirst.histories());
        assertThat(firstFreshDecision.state()).isEqualTo(FixtureVerification.State.PENDING);
        assertThat(firstFreshDecision.reasons()).containsExactly(FixtureVerification.Reason.NEEDS_SECOND_OBSERVATION);
        assertThat(currentVerified(originalFixture.id())).isNull();

        Instant secondStart = freshFirst.reads().finishedAt().plus(Duration.ofMinutes(11));
        Inputs freshSecond = inputs(correctedFixture, homePlayer, currentAwayPlayer,
                secondStart, new BigDecimal("0.20"));
        clock.set(secondStart);
        Lease freshSecondLease = enqueueFixture(store, correctedFixture.id(), clock);
        clock.set(freshSecond.reads().finishedAt().plusSeconds(1));
        var verified = publisher(jdbc, clock, store).publishParsed(freshSecondLease, SEASON,
                correctedFixture, freshSecond.fixtureRead(), freshSecond.live(), freshSecond.histories());
        assertThat(verified.state()).isEqualTo(FixtureVerification.State.VERIFIED);
        assertThat(currentVerified(originalFixture.id())).isEqualTo(freshSecondLease.generation());
        assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + OWNED.receipt()
                + " WHERE season=? AND publication_key=?", Long.class, SEASON.code(),
                FixtureSyncPublisher.publicationKey(originalFixture.id()))).isEqualTo(freshSecondLease.generation());
        Map<String, Object> homeRead = readRows("ACTIVE", originalFixture.id()).stream()
                .filter(row -> ((Number) row.get("player_id")).intValue() == homePlayer)
                .findFirst().orElseThrow();
        assertThat(homeRead.get("expected_goals")).isEqualTo(new BigDecimal("0.20"));
        assertThat(homeRead.get("expected_assists")).isNull();
        assertThat(jdbc.queryForObject("SELECT expected_assists FROM player_fixture_stats "
                + "WHERE season_id=? AND fixture_id=? AND player_id=?", BigDecimal.class,
                SEASON.id(), originalFixture.id(), homePlayer)).isEqualByComparingTo("0.15");

        Instant removedHeaderReadAt = clock.instant().plusSeconds(10);
        var removedHeader = scheduleDocuments(removedHeaderReadAt, true, true);
        Lease headerLease = enqueueSchedule(store, clock);
        clock.set(removedHeader.fixtures().readInterval().completedAt().plusSeconds(1));
        writer.publishSchedule(headerLease, SEASON, removedHeader.bootstrap(), removedHeader.fixtures());
        FixtureRow headerlessFixture = fixture(removedHeader, originalFixture.id());
        assertThat(headerlessFixture.gameweek()).isNull();
        assertThat(headerlessFixture.kickoff()).isNull();
        Map<String, Object> headerInvalidation = fixtureState(originalFixture.id());
        assertThat(headerInvalidation.get("observation_generation")).isEqualTo(freshSecondLease.generation());
        assertThat(headerInvalidation.get("current_verified_generation")).isNull();
        assertThat(headerInvalidation.get("last_verified_generation")).isEqualTo(freshSecondLease.generation());
        assertThat(JSON.readTree((String) headerInvalidation.get("observation")).path("coherent").booleanValue())
                .isFalse();
        assertThat(headerInvalidation.get("verified_header")).isNotNull();
        assertThat(currentVerified(originalFixture.id())).isNull();
        assertThat(readRows("ACTIVE", originalFixture.id())).isEmpty();
        assertThat(readRows("OFF", originalFixture.id())).isEmpty();
    }

    private static FixtureSyncPublisher publisher(JdbcTemplate jdbc, Clock clock, OwnedSyncStore store) {
        return new FixtureSyncPublisher(jdbc, clock, store,
                new FixtureSyncRepository(jdbc, OWNED), OWNED);
    }

    private static Lease enqueueCatalogue(OwnedSyncStore store, MutableClock clock) {
        store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(),
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY, clock.instant());
        return store.acquireForSeason(SEASON.code(), "schedule-test", Duration.ofMinutes(5)).orElseThrow();
    }

    private static Lease enqueueSchedule(OwnedSyncStore store, MutableClock clock) {
        store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(),
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY, clock.instant());
        return store.acquireForSeason(SEASON.code(), "schedule-test", Duration.ofMinutes(5)).orElseThrow();
    }

    private static Lease enqueueFixture(OwnedSyncStore store, int fixtureId, MutableClock clock) {
        String key = FixtureSyncPublisher.publicationKey(fixtureId);
        store.enqueue(SyncKind.FIXTURE_VERIFICATION, SEASON.code(), key, key, clock.instant());
        return store.acquireForSeason(SEASON.code(), "schedule-test", Duration.ofMinutes(5)).orElseThrow();
    }

    private static Batch scheduleDocuments(Instant start, boolean changed, boolean removeHeader) throws Exception {
        ReadInterval interval = interval(start);
        SourceDocument bootstrap = new SourceDocument(localizedScheduleResource(BOOTSTRAP, "events",
                "deadline_time"), interval);
        ArrayNode fixtures = (ArrayNode) JSON.readTree(localizedScheduleResource(FIXTURES, null,
                "kickoff_time"));
        ObjectNode target = byFixture(fixtures, 29);
        int home = playerForTeam(bootstrap, target.required("team_h").intValue());
        int oldAwayTeam = target.required("team_a").intValue();
        int away = playerForTeam(bootstrap, oldAwayTeam);
        target.set("stats", minutesStats(home, away));
        if (changed) {
            ObjectNode swapped = null;
            for (JsonNode node : fixtures) {
                if (node.required("id").intValue() != target.required("id").intValue()
                        && node.required("team_h").intValue() == target.required("team_h").intValue()
                        && node.required("team_a").intValue() == 7) {
                    swapped = (ObjectNode) node;
                    break;
                }
            }
            if (swapped == null) throw new IllegalStateException("missing home-team fixture against club 7");
            target.put("team_a", 7);
            swapped.put("team_a", oldAwayTeam);
            target.put("kickoff_time", Instant.parse(target.required("kickoff_time").textValue())
                    .plusSeconds(3_600).toString());
            target.put("code", target.required("code").intValue() + 1);
            int changedAway = playerForTeam(bootstrap, 7);
            target.set("stats", minutesStats(home, changedAway));
            if (removeHeader) {
                target.putNull("event");
                target.putNull("kickoff_time");
            }
        }
        return new Batch(bootstrap, new SourceDocument(JSON.writeValueAsBytes(fixtures), interval));
    }

    private static ArrayNode minutesStats(int home, int away) {
        ObjectNode stat = JSON.createObjectNode().put("identifier", "minutes");
        stat.putArray("h").addObject().put("element", home).put("value", 90);
        stat.putArray("a").addObject().put("element", away).put("value", 90);
        return JSON.createArrayNode().add(stat);
    }

    private static Inputs inputs(FixtureRow fixture, int homePlayer, int awayPlayer,
                                 Instant start, BigDecimal expectedGoals) throws Exception {
        ReadInterval fixtureRead = interval(start);
        SourceDocument live = new SourceDocument(JSON.writeValueAsBytes(JSON.createObjectNode()
                .set("elements", liveElements(fixture.id(), homePlayer, awayPlayer))),
                interval(start.plusSeconds(2)));
        ReadInterval historyRead = interval(start.plusSeconds(4));
        HistoryBatch home = history(fixture, homePlayer, true, historyRead, expectedGoals);
        HistoryBatch away = history(fixture, awayPlayer, false, historyRead, expectedGoals);
        return new Inputs(fixtureRead, live, List.of(home, away),
                new OwnedSyncStore.SourceRead(start, historyRead.completedAt()));
    }

    private static ArrayNode liveElements(int fixtureId, int homePlayer, int awayPlayer) {
        ArrayNode elements = JSON.createArrayNode();
        for (int player : List.of(homePlayer, awayPlayer)) {
            ObjectNode element = elements.addObject().put("id", player);
            ObjectNode explanation = element.putArray("explain").addObject().put("fixture", fixtureId);
            explanation.putArray("stats").addObject().put("identifier", "minutes")
                    .put("value", 90).put("points", 2).put("points_modification", 0);
        }
        return elements;
    }

    private static HistoryBatch history(FixtureRow fixture, int playerId, boolean home,
                                        ReadInterval read, BigDecimal expectedGoals) {
        int playerTeam = home ? fixture.homeTeamId() : fixture.awayTeamId();
        int opponent = home ? fixture.awayTeamId() : fixture.homeTeamId();
        HistoryRow row = new HistoryRow(SEASON.id(), SEASON.code(), fixture.id(), playerId,
                fixture.gameweek(), playerTeam, opponent, home, fixture.kickoff(), (short) 90,
                (short) 3, (short) 0, (short) 1, (short) 0, (short) 0, expectedGoals,
                null, null, (short) 0, (short) 1, (short) 0, (short) 0, (short) 0,
                (short) 0, null, (short) 0, (short) 0, null, null, null, null, 50, 1, 0, 0,
                (short) 1, null, null, null, null, Set.of(Component.EXPECTED_GOALS));
        return new HistoryBatch(SEASON.id(), SEASON.code(), playerId, read, List.of(row));
    }

    private static FixtureRow fixture(Batch batch, int fixtureId) {
        return CatalogueScheduleParser.parse(SEASON, batch.bootstrap(), batch.fixtures()).fixtures().stream()
                .filter(row -> row.id() == fixtureId).findFirst().orElseThrow();
    }

    private static int playerForTeam(SourceDocument bootstrap, int team) throws IOException {
        for (JsonNode player : JSON.readTree(bootstrap.bytes()).withArray("elements")) {
            if (player.required("team").intValue() == team) return player.required("id").intValue();
        }
        throw new IllegalStateException("no bootstrap player for club " + team);
    }

    private static void seedLegacyPfs(FixtureRow fixture, int playerId) {
        jdbc.update("""
                INSERT INTO player_fixture_stats (fixture_id,player_id,season_id,gameweek,player_team_id,
                    opponent_team_id,was_home,kickoff_time,minutes,total_points,expected_goals,expected_assists,value)
                VALUES (?,?,?,?,?,?,true,?,90,3,0.77,0.15,50)
                """, fixture.id(), playerId, SEASON.id(), fixture.gameweek(), fixture.homeTeamId(),
                fixture.awayTeamId(), Timestamp.from(fixture.kickoff()));
    }

    private static Map<String, Object> fixtureState(int fixtureId) {
        return jdbc.queryForMap("SELECT state,observation::text observation,observation_generation,"
                        + "current_verified_generation,last_verified_generation,recovery_day,recovery_started_at,"
                        + "recovery_until,due_reason,dependency_retry_at,transport_retry_at,verified_header "
                        + "FROM " + OWNED.fixtureSync() + " WHERE season_id=? AND fixture_id=?",
                SEASON.id(), fixtureId);
    }

    private static Map<String, Object> coreFixture(int fixtureId) {
        return jdbc.queryForMap("SELECT team_a,kickoff_time FROM fixture WHERE season_id=? AND id=?",
                SEASON.id(), fixtureId);
    }

    private static Map<String, Object> capturedFixture(int fixtureId) {
        return jdbc.queryForMap("SELECT team_a,kickoff_time FROM " + CAPTURED.fixture()
                + " WHERE season=? AND id=?", SEASON.code(), fixtureId);
    }

    private static Instant capturedFrame(int fixtureId) {
        return jdbc.queryForObject("SELECT captured_at FROM " + CAPTURED.fixture()
                + " WHERE season=? AND id=?", Timestamp.class, SEASON.code(), fixtureId).toInstant();
    }

    private static Map<String, Object> sourceState(String key) {
        return jdbc.queryForMap("SELECT latest_generation,source_read_started_at,source_read_finished_at "
                + "FROM " + OWNED.receipt() + " WHERE season=? AND publication_key=?",
                SEASON.code(), key);
    }

    private static Map<String, Object> publicationState(String key) {
        return jdbc.queryForMap("SELECT latest_generation,source_read_started_at,source_read_finished_at "
                + "FROM " + OWNED.receipt() + " WHERE season=? AND publication_key=?",
                SEASON.code(), key);
    }

    private static Long currentVerified(int fixtureId) {
        return jdbc.queryForObject("SELECT current_verified_generation FROM " + OWNED.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", Long.class, SEASON.id(), fixtureId);
    }

    private static List<Map<String, Object>> readRows(String mode, int fixtureId) {
        PfsReadSource source = new PfsReadSource(jdbc, mode, SEASON.code(),
                OWNED.fixtureSync(), OWNED.playerFixtureEvidence());
        return jdbc.queryForList("WITH " + source.cte() + " SELECT p.* FROM pfs_read p "
                + "WHERE p.season_id=? AND p.fixture_id=? ORDER BY p.player_id", SEASON.id(), fixtureId);
    }

    private static void createCapturedTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS fpl_schown_price_test (
                    season varchar(10) NOT NULL, price_date date NOT NULL, element_id integer NOT NULL,
                    web_name varchar(100) NOT NULL, full_name varchar(200), team_id integer,
                    team_short varchar(10), team_name varchar(100), element_type smallint,
                    now_cost integer NOT NULL, cost_change_event integer NOT NULL DEFAULT 0,
                    cost_change_start integer NOT NULL DEFAULT 0, selected_by_percent numeric(5,2),
                    status varchar(4), news text, penalties_order smallint, direct_fk_order smallint,
                    corners_order smallint, captured_at timestamptz NOT NULL DEFAULT now(),
                    PRIMARY KEY(season,price_date,element_id))
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS fpl_schown_gw_test (
                    season varchar(10) NOT NULL, id integer NOT NULL, name varchar(50),
                    deadline_time timestamptz NOT NULL, finished boolean NOT NULL DEFAULT false,
                    data_checked boolean NOT NULL DEFAULT false, is_current boolean NOT NULL DEFAULT false,
                    is_next boolean NOT NULL DEFAULT false, average_entry_score integer,
                    highest_score integer, most_captained integer, most_transferred_in integer,
                    captured_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(season,id))
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS fpl_schown_capture_fixture_test (
                    season varchar(10) NOT NULL, id integer NOT NULL, gameweek integer,
                    team_h integer NOT NULL, team_a integer NOT NULL, team_h_name varchar(100),
                    team_h_short varchar(10), team_a_name varchar(100), team_a_short varchar(10),
                    team_h_difficulty smallint, team_a_difficulty smallint, kickoff_time timestamptz,
                    started boolean NOT NULL DEFAULT false, finished boolean NOT NULL DEFAULT false,
                    team_h_score integer, team_a_score integer, captured_at timestamptz NOT NULL DEFAULT now(),
                    PRIMARY KEY(season,id))
                """);
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + FAIL_FIXTURE + " (LIKE " + CAPTURED.fixture()
                + " INCLUDING ALL)");
        for (String table : List.of(CAPTURED.dailyPrice(), CAPTURED.gameweek(), CAPTURED.fixture(), FAIL_FIXTURE)) {
            jdbc.execute("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY");
        }
        jdbc.execute("ALTER TABLE " + FAIL_FIXTURE + " DROP CONSTRAINT IF EXISTS fpl_schown_fail_fixture_id");
        jdbc.execute("ALTER TABLE " + FAIL_FIXTURE
                + " ADD CONSTRAINT fpl_schown_fail_fixture_id CHECK (id < 0)");
    }

    private static void cleanCoreRows() {
        jdbc.update("DELETE FROM player_fixture_stats WHERE season_id=?", SEASON.id());
        jdbc.update("DELETE FROM fixture WHERE season_id=?", SEASON.id());
        jdbc.update("DELETE FROM player WHERE season_id=?", SEASON.id());
        jdbc.update("DELETE FROM team WHERE season_id=?", SEASON.id());
        jdbc.update("DELETE FROM gameweek WHERE season_id=?", SEASON.id());
    }

    private static void cleanOwnedRows() {
        jdbc.execute("TRUNCATE " + OWNED.playerFixtureEvidence() + "," + OWNED.fixtureSync() + ","
                + OWNED.fixtureDependencyManifest() + "," + OWNED.historyFingerprint() + "," + OWNED.payload()
                + "," + OWNED.run() + "," + OWNED.receipt() + "," + OWNED.requestAttempt() + ","
                + OWNED.requestState() + "," + OWNED.plannerSlot() + "," + OWNED.plannerRuntime()
                + " CASCADE");
        jdbc.update("DELETE FROM " + CAPTURED.dailyPrice() + " WHERE season=?", SEASON.code());
        jdbc.update("DELETE FROM " + CAPTURED.gameweek() + " WHERE season=?", SEASON.code());
        jdbc.update("DELETE FROM " + CAPTURED.fixture() + " WHERE season=?", SEASON.code());
        jdbc.update("DELETE FROM " + FAIL_FIXTURE + " WHERE season=?", SEASON.code());
    }

    private static ReadInterval interval(Instant start) {
        return new ReadInterval(start, start.plusSeconds(1));
    }

    private static byte[] localizedScheduleResource(String resource, String collection, String dateField)
            throws IOException {
        JsonNode root = JSON.readTree(resource(resource));
        JsonNode rows = collection == null ? root : root.path(collection);
        for (JsonNode row : rows) {
            if (row.hasNonNull(dateField)) {
                ObjectNode object = (ObjectNode) row;
                String timestamp = object.required(dateField).textValue();
                int year = Integer.parseInt(timestamp.substring(0, 4));
                if (year != 2026 && year != 2027) {
                    throw new IllegalStateException("unexpected fixture season year: " + year);
                }
                object.put(dateField, (year + 170) + timestamp.substring(4));
            }
        }
        return JSON.writeValueAsBytes(root);
    }

    private static byte[] resource(String file) throws IOException {
        try (InputStream input = ScheduleOwnershipInvalidationIntegrationTest.class.getResourceAsStream(file)) {
            if (input == null) throw new IllegalArgumentException("missing capture " + file);
            return input.readAllBytes();
        }
    }

    private static ObjectNode byFixture(ArrayNode fixtures, int id) {
        for (JsonNode fixture : fixtures) {
            if (fixture.required("id").intValue() == id) return (ObjectNode) fixture;
        }
        throw new IllegalStateException("fixture " + id + " missing from source");
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private record Batch(SourceDocument bootstrap, SourceDocument fixtures) { }
    private record Inputs(ReadInterval fixtureRead, SourceDocument live, List<HistoryBatch> histories,
                         OwnedSyncStore.SourceRead reads) { }

    private static final class MutableClock extends Clock {
        private Instant current;
        private MutableClock(Instant current) { this.current = current; }
        void set(Instant next) { current = next; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
