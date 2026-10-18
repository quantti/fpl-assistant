package fi.karivanttinen.fplassistant.shortlist;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import fi.karivanttinen.fplassistant.testsupport.RankingSeed;
import fi.karivanttinen.fplassistant.testsupport.TapasSeed;
import fi.karivanttinen.fplassistant.tools.RankingMetrics;
import fi.karivanttinen.fplassistant.tools.PfsReadSource;
import fi.karivanttinen.fplassistant.tools.SeasonResolver;
import fi.karivanttinen.fplassistant.sync.HistoryParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ShortlistComparisonIntegrationTest {
    private static final String OWNED_FIXTURES = "sc_comparison_fixture_test";
    private static final String OWNED_EVIDENCE = "sc_comparison_evidence_test";
    private static final int SEASON_ID = 9951;
    private static final String SEASON = "SC-CUR";
    private static final UUID OWNER = UUID.fromString("99333333-3333-4333-8333-333333333333");
    private static final Instant NOW = Instant.parse("2026-09-09T12:00:00Z");
    private static final Instant CAPTURED = Instant.parse("2026-09-09T01:15:00Z");
    private static final Instant FIRST = NOW.minus(34, ChronoUnit.DAYS);
    private static final LocalDate DAY = LocalDate.of(2026, 9, 9);
    private static final int PLAYER = 99501;

    @TestConfiguration static class Time {
        @Bean @Primary Clock comparisonClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean @Primary PfsReadSource comparisonPfs(JdbcTemplate jdbc) {
            return new PfsReadSource(jdbc, "OFF", SEASON,
                    OWNED_FIXTURES, OWNED_EVIDENCE, "sc_no_current_test", "sc_no_receipt_test");
        }
    }
    @Autowired ShortlistComparison comparison;
    @Autowired fi.karivanttinen.fplassistant.teamxg.UnderstatStore understat;
    private List<fi.karivanttinen.fplassistant.teamxg.UnderstatParser.Match> teamMatches;
    @Autowired ShortlistStore shortlist;
    @Autowired SeasonResolver seasons;
    @Autowired PlayerCatalogueStore catalogues;
    @MockitoSpyBean JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean RankingMetrics ranking;
    @MockitoSpyBean fi.karivanttinen.fplassistant.tools.DailyPriceSource prices;
    @Value("${fpl.daily-price.table-name}") String daily;
    @Value("${fpl.schedule.gameweek-table-name}") String events;
    @Value("${fpl.schedule.fixture-table-name}") String fixtures;
    @Value("${fpl.shortlist.table-name}") String saved;
    @Value("${fpl.shortlist.catalogue-table-name}") String catalogue;
    @Value("${fpl.shortlist.catalogue-player-table-name}") String cataloguePlayers;
    @Value("${fpl.shortlist.catalogue-alias-table-name}") String aliases;

    @BeforeEach void seed() {
        clean();
        jdbc.execute("CREATE TABLE " + OWNED_FIXTURES + " (season_id INTEGER, fixture_id INTEGER,"
                + " state VARCHAR(24), observation_generation BIGINT, current_verified_generation BIGINT,"
                + " last_verified_generation BIGINT, last_verified_at TIMESTAMPTZ, PRIMARY KEY(season_id,fixture_id))");
        jdbc.execute("CREATE TABLE " + OWNED_EVIDENCE + " (season_id INTEGER, season_code VARCHAR(10),"
                + " fixture_id INTEGER, player_id INTEGER, generation BIGINT, component_presence BIGINT,"
                + " source_read_started_at TIMESTAMPTZ, source_read_finished_at TIMESTAMPTZ,"
                + " PRIMARY KEY(season_id,fixture_id,player_id,generation))");
        jdbc.update("INSERT INTO season(id,code,name,start_date,is_current) VALUES (?,?,'Shortlist test','2026-08-01',false)", SEASON_ID, SEASON);
        TapasSeed.setCurrentSeason(jdbc, SEASON_ID);
        RankingSeed.seedSchedule(jdbc, events, fixtures, SEASON, CAPTURED, FIRST);
        jdbc.update("UPDATE " + fixtures + " SET started=true, finished=true WHERE season=? AND gameweek<=5", SEASON);
        jdbc.update("INSERT INTO gameweek(id,season_id,name,deadline_time) VALUES(1,?,'Test',?)", SEASON_ID, Timestamp.from(FIRST));
        for (int club = 1; club <= 20; club++) {
            int team = RankingSeed.team(club);
            jdbc.update("INSERT INTO team(id,season_id,code,name,short_name,strength) VALUES(?,?,?,'Test club',?,3)", team, SEASON_ID, team, "C" + club);
            jdbc.update("INSERT INTO player(id,season_id,team_id,web_name,element_type,now_cost,total_points,expected_goals,expected_assists) VALUES(?,?,?, ?,2,60,60,1.2,0.3)", PLAYER + club - 1, SEASON_ID, team, "Saved" + club);
            jdbc.update("INSERT INTO " + daily + "(season,price_date,element_id,web_name,team_id,team_short,element_type,now_cost,status,captured_at,penalties_order) VALUES(?,?,?,?,?,?,2,60,'d',?,1)", SEASON, DAY, PLAYER + club - 1, "Saved" + club, team, "C" + club, Timestamp.from(CAPTURED));
        }
        List<Object[]> readings = new ArrayList<>();
        for (var f : RankingSeed.fixtures(CAPTURED, FIRST)) {
            if (f.gameweek() > 5) continue;
            for (boolean home : List.of(true, false)) {
                int team = home ? f.homeTeamId() : f.awayTeamId();
                readings.add(new Object[] {f.id(), PLAYER + team - RankingSeed.team(1), SEASON_ID,
                        f.gameweek(), team, home ? f.awayTeamId() : f.homeTeamId(), home, Timestamp.from(f.kickoff())});
            }
        }
        teamMatches = RankingSeed.fixtures(CAPTURED, FIRST).stream().filter(f -> f.gameweek() <= 5)
                .map(f -> new fi.karivanttinen.fplassistant.teamxg.UnderstatParser.Match(f.id(), f.kickoff(),
                        "C" + (f.homeTeamId() - RankingSeed.team(1) + 1),
                        "C" + (f.awayTeamId() - RankingSeed.team(1) + 1), 1, 1,
                        java.math.BigDecimal.valueOf(2), java.math.BigDecimal.ONE)).toList();
        understat.replace(List.of(new fi.karivanttinen.fplassistant.teamxg.UnderstatStore.Capture(
                "2026-27", CAPTURED.minusSeconds(120), teamMatches)));
        jdbc.batchUpdate("INSERT INTO player_fixture_stats(fixture_id,player_id,season_id,gameweek,player_team_id,opponent_team_id,was_home,kickoff_time,minutes,total_points,value,expected_goals,expected_assists,expected_goals_conceded,defensive_contribution) VALUES(?,?,?,?,?,?,?,?,90,6,60,1.2,0.3,1.2,10)", readings);
        jdbc.update("UPDATE " + daily + " SET full_name=web_name,team_name=team_short,chance_of_playing_next_round=75 WHERE season=?", SEASON);
        var cat = catalogues.forCapture(SEASON, DAY).orElseThrow();
        shortlist.put(OWNER, cat.id(), SEASON, PLAYER).orElseThrow();
    }

    @AfterEach void clean() {
        TapasSeed.ensure(jdbc);
        jdbc.update("DELETE FROM fpl_understat_capture_test");
        jdbc.execute("DROP TABLE IF EXISTS " + OWNED_EVIDENCE);
        jdbc.execute("DROP TABLE IF EXISTS " + OWNED_FIXTURES);
        jdbc.update("DELETE FROM " + saved + " WHERE user_id=?", OWNER);
        jdbc.update("DELETE FROM " + aliases + " WHERE catalogue_id IN (SELECT id FROM " + catalogue + " WHERE season LIKE 'SC-%')");
        jdbc.update("DELETE FROM " + cataloguePlayers + " WHERE catalogue_id IN (SELECT id FROM " + catalogue + " WHERE season LIKE 'SC-%')");
        jdbc.update("DELETE FROM " + catalogue + " WHERE season LIKE 'SC-%'");
        jdbc.update("DELETE FROM " + daily + " WHERE season LIKE 'SC-%'");
        RankingSeed.cleanSchedule(jdbc, events, fixtures, SEASON);
        jdbc.update("DELETE FROM player_fixture_stats WHERE season_id=?", SEASON_ID);
        jdbc.update("DELETE FROM player WHERE season_id=?", SEASON_ID);
        jdbc.update("DELETE FROM team WHERE season_id=?", SEASON_ID);
        jdbc.update("DELETE FROM gameweek WHERE season_id=?", SEASON_ID);
        jdbc.update("DELETE FROM season WHERE id=?", SEASON_ID);
        TapasSeed.setCurrentSeason(jdbc, TapasSeed.CURRENT_SEASON_ID);
    }

    @Test void marketExposesCapturedPlayingChance() {
        for (Integer chance : java.util.Arrays.asList(75, 50, 0, null)) {
            jdbc.update("UPDATE " + daily + " SET chance_of_playing_next_round=? WHERE season=? AND price_date=? AND element_id=?", chance, SEASON, DAY, PLAYER);
            var market = comparison.read(OWNER).rows().getFirst().market();
            assertThat(market.chanceOfPlayingNextRound()).isEqualTo(chance);
            assertThat(market.status()).isEqualTo("d");
            assertThat(market.capturedAt()).isEqualTo(CAPTURED);
        }
    }

    @Test void playingChanceIsSelectedFromTheSameSeasonAndCaptureDay() {
        // Bind the selected capture: two artificial seasons on one date are not a selector test.
        org.mockito.Mockito.doReturn(java.util.Optional.of(
                new fi.karivanttinen.fplassistant.tools.DailyPriceSource.Capture(SEASON, DAY)))
                .when(prices).latestCapture();
        jdbc.update("INSERT INTO " + daily + " (season,price_date,element_id,web_name,now_cost,status,captured_at,chance_of_playing_next_round) VALUES (?,?,?,'Yesterday',60,'d',?,25),('SC-OLD',?,?,'Old season',60,'d',?,100)",
                SEASON, DAY.minusDays(1), PLAYER, Timestamp.from(CAPTURED.minus(1, ChronoUnit.DAYS)), DAY, PLAYER, Timestamp.from(CAPTURED));
        assertThat(comparison.read(OWNER).rows().getFirst().market().chanceOfPlayingNextRound()).isEqualTo(75);
    }

    @Test void sourceMetadataKeepsScheduleCaptureSeparateFromAssessmentAndMarket() {
        Instant scheduleCapture = CAPTURED.minusSeconds(300);
        jdbc.update("UPDATE " + fixtures + " SET captured_at=? WHERE season=?", Timestamp.from(scheduleCapture), SEASON);
        jdbc.update("UPDATE " + events + " SET captured_at=? WHERE season=?", Timestamp.from(scheduleCapture), SEASON);
        var result = comparison.read(OWNER);
        assertThat(result.scheduleCapturedAt()).isEqualTo(scheduleCapture);
        assertThat(result.asOf()).isEqualTo(NOW);
        assertThat(result.rows().getFirst().market().capturedAt()).isEqualTo(CAPTURED);
        assertThat(result.modelSeasons()).containsExactly("2026-27");
        assertThat(result.modelCapturedAt()).containsEntry("2026-27", CAPTURED.minusSeconds(120));
        assertThat(result.modelThrough().get("2026-27")).isEqualTo(jdbc.queryForObject(
                "SELECT MAX(kickoff_time) FROM " + fixtures + " WHERE season=? AND finished", Timestamp.class, SEASON).toInstant());
        jdbc.update("UPDATE " + fixtures + " SET captured_at=? WHERE season=?", Timestamp.from(scheduleCapture.plusSeconds(30)), SEASON);
        jdbc.update("UPDATE " + events + " SET captured_at=? WHERE season=?", Timestamp.from(scheduleCapture.plusSeconds(30)), SEASON);
        assertThat(comparison.read(OWNER).scheduleCapturedAt()).isEqualTo(scheduleCapture.plusSeconds(30));
    }

    @Test void realReadIncludesObservedTotalsAndFlaggedPlayersTeamForecast() {
        var result = comparison.read(OWNER);
        var row = result.rows().getFirst();
        assertThat(result.horizon()).startsWith("GW6–10");
        assertThat(row.market().status()).isEqualTo("d");
        assertThat(row.market().fullName()).isEqualTo("Saved1");
        assertThat(row.market().penalties()).isEqualTo(1);
        assertThat(row.totals().points()).isEqualTo(60);
        assertThat(row.pointsPerMillion()).isEqualTo(10);
        assertThat(row.totals().xgi()).isEqualTo(1.5);
        assertThat(row.forecast().expectedCleanSheets()).isPositive();
        assertThat(row.forecast().weeks()).hasSize(5);
        assertThat(row.history()).hasSize(5);
        assertThat(row.defcon().readings()).isEqualTo(5);
        assertThat(row.observationSources().getLast()).contains("unavailable");
    }

    @Test void ownedMissingOptionalComponentsMaskRetainedPhysicalValuesInTheResponse() {
        int fixture = ownLatestObservation(0);
        var row = comparison.read(OWNER).rows().getFirst();
        var match = row.history().getFirst();
        assertThat(match.id()).isEqualTo(fixture);
        assertThat(match.minutes()).isEqualTo(90);
        assertThat(match.xg()).isNull();
        assertThat(match.xa()).isNull();
        assertThat(match.xgi()).isNull();
        assertThat(match.defcon()).isNull();
        assertThat(match.evidence()).isEqualTo("VERIFIED");
        assertThat(row.defcon().readings()).isEqualTo(4);
        assertThat(row.forecast().expectedCleanSheets()).isPositive();
        assertThat(jdbc.queryForObject("SELECT defensive_contribution FROM player_fixture_stats WHERE season_id=? AND fixture_id=? AND player_id=?", Integer.class, SEASON_ID, fixture, PLAYER)).isEqualTo(99);
    }

    @Test void ownedKnownZeroRemainsAReadingInTheResponse() {
        long presence = HistoryParser.Component.EXPECTED_GOALS.bit()
                | HistoryParser.Component.EXPECTED_ASSISTS.bit()
                | HistoryParser.Component.DEFENSIVE_CONTRIBUTION.bit();
        int fixture = ownLatestObservation(presence);
        jdbc.update("UPDATE player_fixture_stats SET expected_goals=0,expected_assists=0,defensive_contribution=0 WHERE season_id=? AND fixture_id=? AND player_id=?", SEASON_ID, fixture, PLAYER);
        var row = comparison.read(OWNER).rows().getFirst();
        var match = row.history().getFirst();
        assertThat(match.xg()).isZero();
        assertThat(match.xa()).isZero();
        assertThat(match.xgi()).isZero();
        assertThat(match.defcon()).isZero();
        assertThat(row.defcon().readings()).isEqualTo(5);
        assertThat(row.defcon().hits()).isEqualTo(4);
    }

    @Test void ownedMismatchedReceiptCannotFallBackToThePhysicalRow() {
        int fixture = ownLatestObservation(0);
        jdbc.update("UPDATE " + OWNED_FIXTURES + " SET current_verified_generation=8 WHERE season_id=? AND fixture_id=?", SEASON_ID, fixture);
        var row = comparison.read(OWNER).rows().getFirst();
        var match = row.history().getFirst();
        assertThat(match.id()).isEqualTo(fixture);
        assertThat(match.minutes()).isNull();
        assertThat(match.evidence()).isEqualTo("MISSING");
        assertThat(match.xg()).isNull();
        assertThat(match.defcon()).isNull();
        assertThat(row.defcon().readings()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT minutes FROM player_fixture_stats WHERE season_id=? AND fixture_id=? AND player_id=?", Integer.class, SEASON_ID, fixture, PLAYER)).isEqualTo(90);
    }

    private int ownLatestObservation(long presence) {
        int fixture = comparison.read(OWNER).rows().getFirst().history().getFirst().id();
        jdbc.update("UPDATE player_fixture_stats SET expected_goals=9,expected_assists=8,defensive_contribution=99 WHERE season_id=? AND fixture_id=? AND player_id=?", SEASON_ID, fixture, PLAYER);
        jdbc.update("INSERT INTO " + OWNED_FIXTURES + " (season_id,fixture_id,state,observation_generation,current_verified_generation,last_verified_generation,last_verified_at) VALUES (?,?,'VERIFIED',7,7,7,?)", SEASON_ID, fixture, Timestamp.from(CAPTURED));
        jdbc.update("INSERT INTO " + OWNED_EVIDENCE + " (season_id,season_code,fixture_id,player_id,generation,component_presence,source_read_started_at,source_read_finished_at) VALUES (?,?,?,?,7,?,?,?)", SEASON_ID, SEASON, fixture, PLAYER, presence, Timestamp.from(CAPTURED.minusSeconds(60)), Timestamp.from(CAPTURED));
        return fixture;
    }

    @Test void absentRecentReadingDoesNotTurnIntoZeroButKnownZeroSurvives() {
        jdbc.update("DELETE FROM player_fixture_stats WHERE season_id=? AND player_id=? AND gameweek=5", SEASON_ID, PLAYER);
        jdbc.update("UPDATE player_fixture_stats SET minutes=0,defensive_contribution=0 WHERE season_id=? AND player_id=? AND gameweek=4", SEASON_ID, PLAYER);
        var row = comparison.read(OWNER).rows().getFirst();
        assertThat(row.history().get(0).minutes()).isNull();
        assertThat(row.history().get(0).evidence()).isEqualTo("MISSING");
        assertThat(row.history().get(1).minutes()).isZero();
        assertThat(row.defcon().readings()).isEqualTo(3);
        // Missing FPL player observations do not erase the independently captured Understat model.
        assertThat(row.forecast().expectedCleanSheets()).isPositive();
    }

    @Test void staleCaptureAndReusedSeasonIdentityCannotSupplyCurrentPrice() {
        jdbc.update("UPDATE " + daily + " SET price_date=price_date-1,captured_at=captured_at-INTERVAL '1 day' WHERE season=?", SEASON);
        assertThat(comparison.read(OWNER).rows().getFirst().market()).isNull();
        jdbc.update("UPDATE " + saved + " SET season='SC-OLD' WHERE user_id=?", OWNER);
        var archived = comparison.read(OWNER).rows().getFirst();
        assertThat(archived.totals()).isNull();
        assertThat(archived.forecast().weeks()).isEmpty();
        assertThat(archived.reason()).contains("Historical");
    }

    @Test void unfinishedMatchDoesNotSuppressTheCompletedMatchModel() {
        int fixture = comparison.read(OWNER).rows().getFirst().history().getFirst().id();
        jdbc.update("UPDATE " + fixtures + " SET started=true,finished=false WHERE season=? AND id=?", SEASON, fixture);
        var standardRanking = ranking.load(prices.latestCapture().orElseThrow(),
                seasons.metricSeasons(seasons.resolve()));
        assertThat(standardRanking.comparisonCoverage().complete()).isFalse();
        assertThat(comparison.read(OWNER).rows().getFirst().forecast().expectedCleanSheets()).isPositive();
    }

    @Test void unverifiedFplMatchDoesNotSuppressIndependentUnderstatForecast() {
        int fixture = ownLatestObservation(0);
        jdbc.update("UPDATE " + OWNED_FIXTURES + " SET current_verified_generation=8 WHERE season_id=? AND fixture_id=?", SEASON_ID, fixture);
        var row = comparison.read(OWNER).rows().getFirst();
        assertThat(row.history().getFirst().minutes()).isNull();
        assertThat(row.forecast().expectedCleanSheets()).isPositive();
    }

    @Test void corruptOrMissingFplSidesCannotContaminateTheUnderstatModel() {
        var before = comparison.read(OWNER).rows().getFirst();
        int fixture = before.history().getFirst().id();
        boolean home = before.history().getFirst().home();
        jdbc.update("DELETE FROM player_fixture_stats WHERE season_id=? AND fixture_id=? AND was_home=?", SEASON_ID, fixture, !home);
        jdbc.update("UPDATE player_fixture_stats SET expected_goals=99,expected_goals_conceded=99 WHERE season_id=? AND fixture_id=?", SEASON_ID, fixture);
        Double oneSide = comparison.read(OWNER).rows().getFirst().forecast().expectedCleanSheets();
        assertThat(oneSide).isCloseTo(before.forecast().expectedCleanSheets(), within(1e-12));
        jdbc.update("DELETE FROM player_fixture_stats WHERE season_id=? AND fixture_id=?", SEASON_ID, fixture);
        assertThat(comparison.read(OWNER).rows().getFirst().forecast().expectedCleanSheets()).isCloseTo(oneSide, within(1e-12));
    }

    @Test void modelCutoffFollowsTheUnderstatCaptureNotFplVerificationState() {
        Instant latest = teamMatches.stream().map(fi.karivanttinen.fplassistant.teamxg.UnderstatParser.Match::kickoff)
                .max(Instant::compareTo).orElseThrow();
        jdbc.update("UPDATE " + fixtures + " SET finished=false WHERE season=? AND kickoff_time=?", SEASON, Timestamp.from(latest));
        assertThat(comparison.read(OWNER).modelThrough()).containsEntry("2026-27", latest);
        var corrected = teamMatches.stream().filter(m -> m.kickoff().isBefore(latest)).toList();
        understat.replace(List.of(new fi.karivanttinen.fplassistant.teamxg.UnderstatStore.Capture(
                "2026-27", CAPTURED, corrected)));
        var result = comparison.read(OWNER);
        assertThat(result.modelThrough().get("2026-27")).isBefore(latest);
        assertThat(result.modelCapturedAt()).containsEntry("2026-27", CAPTURED);
        assertThat(result.rows().getFirst().forecast().expectedCleanSheets()).isPositive();
    }

    @Test void emptyUnderstatEvidenceRemainsUnavailableEvenWithCompleteFplRows() {
        understat.replace(List.of(new fi.karivanttinen.fplassistant.teamxg.UnderstatStore.Capture(
                "2026-27", CAPTURED, List.of())));
        var result = comparison.read(OWNER);
        assertThat(result.rows().getFirst().forecast().expectedCleanSheets()).isNull();
        assertThat(result.rows().getFirst().forecast().reason()).contains("Understat");
        assertThat(result.modelThrough()).isEmpty();
    }

    @Test void missingUnderstatCaptureNamesTheActualReason() {
        jdbc.update("DELETE FROM fpl_understat_capture_test");
        var row = comparison.read(OWNER).rows().getFirst();
        assertThat(row.forecast().expectedCleanSheets()).isNull();
        assertThat(row.forecast().reason()).contains("No stored Understat", "full-window total unavailable");
    }

    @Test void boundedFullPopulationReadHasNoPerShortlistedPlayerQueryLoop() {
        jdbc.update("INSERT INTO player(id,season_id,team_id,web_name,element_type,now_cost) SELECT p.id+n*100,season_id,team_id,web_name||n,element_type,now_cost FROM player p CROSS JOIN generate_series(1,29) n WHERE season_id=?", SEASON_ID);
        jdbc.update("INSERT INTO " + daily + "(season,price_date,element_id,web_name,full_name,team_id,team_short,team_name,element_type,now_cost,status,captured_at) SELECT season,price_date,element_id+n*100,web_name||n,full_name||n,team_id,team_short,team_name,element_type,now_cost,status,captured_at FROM " + daily + " CROSS JOIN generate_series(1,29) n WHERE season=?", SEASON);
        jdbc.update("INSERT INTO player_fixture_stats(fixture_id,player_id,season_id,gameweek,player_team_id,opponent_team_id,was_home,kickoff_time,minutes,value,expected_goals,expected_assists,expected_goals_conceded) SELECT fixture_id,player_id+n*100,season_id,gameweek,player_team_id,opponent_team_id,was_home,kickoff_time,minutes,value,expected_goals,expected_assists,expected_goals_conceded FROM player_fixture_stats CROSS JOIN generate_series(1,29) n WHERE season_id=?", SEASON_ID);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + daily + " WHERE season=?", Integer.class, SEASON)).isEqualTo(600);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM player_fixture_stats WHERE season_id=?", Integer.class, SEASON_ID)).isEqualTo(3000);
        int[] counters = {0, 0};
        org.mockito.stubbing.Answer<Object> count = call -> {
            var result = (List<?>) call.callRealMethod();
            counters[0]++; counters[1] += result.size();
            return result;
        };
        doAnswer(count).when(jdbc).queryForList(org.mockito.ArgumentMatchers.anyString(), any(Object[].class));
        doAnswer(count).when(jdbc).queryForList(org.mockito.ArgumentMatchers.anyString());
        long start = System.nanoTime();
        assertThat(comparison.read(OWNER).rows()).hasSize(1);
        int onePlayerCalls = counters[0], onePlayerRows = counters[1];
        long oneMillis = (System.nanoTime() - start) / 1_000_000;
        var cat = catalogues.forCapture(SEASON, DAY).orElseThrow();
        for (int id = PLAYER + 1; id < PLAYER + 20; id++) shortlist.put(OWNER, cat.id(), SEASON, id).orElseThrow();
        counters[0] = 0; counters[1] = 0;
        start = System.nanoTime();
        assertThat(comparison.read(OWNER).rows()).hasSize(20);
        assertThat(counters[0]).isEqualTo(onePlayerCalls);
        System.out.printf("Shortlist probe: 600 players, 3000 PFS; queryForList calls=%d; returned rows=%d/%d; elapsed 1/20 shortlist=%d/%d ms%n", onePlayerCalls, onePlayerRows, counters[1], oneMillis, (System.nanoTime() - start) / 1_000_000);
    }

    @Test void outerTransactionKeepsSupplementaryReadOnTheOriginalSnapshot() {
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            var result = call.callRealMethod();
            var other = new TransactionTemplate(transactions);
            other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            other.executeWithoutResult(s -> {
                jdbc.update("UPDATE player SET total_points=999 WHERE season_id=? AND id=?", SEASON_ID, PLAYER);
                jdbc.update("UPDATE " + daily + " SET chance_of_playing_next_round=25,status='i',news='Changed',captured_at=? WHERE season=? AND price_date=? AND element_id=?", Timestamp.from(CAPTURED.plusSeconds(60)), SEASON, DAY, PLAYER);
            });
            return result;
        }).when(ranking).load(any(), any());
        var row = comparison.read(OWNER).rows().getFirst();
        assertThat(row.totals().points()).isEqualTo(60);
        assertThat(row.market().chanceOfPlayingNextRound()).isEqualTo(75);
        assertThat(row.market().status()).isEqualTo("d");
        assertThat(row.market().news()).isNull();
        assertThat(row.market().capturedAt()).isEqualTo(CAPTURED);
        assertThat(jdbc.queryForObject("SELECT chance_of_playing_next_round FROM " + daily + " WHERE season=? AND price_date=? AND element_id=?", Integer.class, SEASON, DAY, PLAYER)).isEqualTo(25);
        assertThat(jdbc.queryForObject("SELECT total_points FROM player WHERE season_id=? AND id=?", Integer.class, SEASON_ID, PLAYER)).isEqualTo(999);
    }
}
