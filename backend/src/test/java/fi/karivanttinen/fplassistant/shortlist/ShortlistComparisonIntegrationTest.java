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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ShortlistComparisonIntegrationTest {
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
    }
    @Autowired ShortlistComparison comparison;
    @Autowired ShortlistStore shortlist;
    @Autowired PlayerCatalogueStore catalogues;
    @MockitoSpyBean JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean RankingMetrics ranking;
    @Value("${fpl.daily-price.table-name}") String daily;
    @Value("${fpl.schedule.gameweek-table-name}") String events;
    @Value("${fpl.schedule.fixture-table-name}") String fixtures;
    @Value("${fpl.shortlist.table-name}") String saved;
    @Value("${fpl.shortlist.catalogue-table-name}") String catalogue;
    @Value("${fpl.shortlist.catalogue-player-table-name}") String cataloguePlayers;
    @Value("${fpl.shortlist.catalogue-alias-table-name}") String aliases;

    @BeforeEach void seed() {
        clean();
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
        jdbc.batchUpdate("INSERT INTO player_fixture_stats(fixture_id,player_id,season_id,gameweek,player_team_id,opponent_team_id,was_home,kickoff_time,minutes,total_points,value,expected_goals,expected_assists,expected_goals_conceded,defensive_contribution) VALUES(?,?,?,?,?,?,?,?,90,6,60,1.2,0.3,1.2,10)", readings);
        jdbc.update("UPDATE " + daily + " SET full_name=web_name,team_name=team_short WHERE season=?", SEASON);
        var cat = catalogues.forCapture(SEASON, DAY).orElseThrow();
        shortlist.put(OWNER, cat.id(), SEASON, PLAYER).orElseThrow();
    }

    @AfterEach void clean() {
        TapasSeed.ensure(jdbc);
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

    @Test void realReadIncludesObservedTotalsAndFlaggedPlayersTeamForecast() {
        var result = comparison.read(OWNER);
        var row = result.rows().getFirst();
        assertThat(result.horizon()).startsWith("GW6–10");
        assertThat(row.market().status()).isEqualTo("d");
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

    @Test void absentRecentReadingDoesNotTurnIntoZeroButKnownZeroSurvives() {
        jdbc.update("DELETE FROM player_fixture_stats WHERE season_id=? AND player_id=? AND gameweek=5", SEASON_ID, PLAYER);
        jdbc.update("UPDATE player_fixture_stats SET minutes=0,defensive_contribution=0 WHERE season_id=? AND player_id=? AND gameweek=4", SEASON_ID, PLAYER);
        var row = comparison.read(OWNER).rows().getFirst();
        assertThat(row.history().get(0).minutes()).isNull();
        assertThat(row.history().get(0).evidence()).isEqualTo("MISSING");
        assertThat(row.history().get(1).minutes()).isZero();
        assertThat(row.defcon().readings()).isEqualTo(3);
        // Four older sides still exceed the ClubRates floor: coverage, not rates, blocks this.
        assertThat(row.forecast().expectedCleanSheets()).isNull();
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
            other.executeWithoutResult(s -> jdbc.update("UPDATE player SET total_points=999 WHERE season_id=? AND id=?", SEASON_ID, PLAYER));
            return result;
        }).when(ranking).load(any(), any());
        assertThat(comparison.read(OWNER).rows().getFirst().totals().points()).isEqualTo(60);
        assertThat(jdbc.queryForObject("SELECT total_points FROM player WHERE season_id=? AND id=?", Integer.class, SEASON_ID, PLAYER)).isEqualTo(999);
    }
}
