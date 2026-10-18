package fi.karivanttinen.fplassistant.tools;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import fi.karivanttinen.fplassistant.teamxg.UnderstatParser;
import fi.karivanttinen.fplassistant.teamxg.UnderstatStore;
import fi.karivanttinen.fplassistant.testsupport.RankingSeed;
import fi.karivanttinen.fplassistant.testsupport.TapasSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RankingUnderstatIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-09T12:00:00Z");
    private static final Instant CAPTURED = Instant.parse("2026-09-09T01:10:00Z");
    private static final String SEASON = "UX-2026";
    @TestConfiguration
    static class Time {
        @Bean @Primary Clock understatClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired RankingMetrics ranking;
    @Autowired UnderstatStore source;

    @BeforeEach
    void seed() {
        TapasSeed.ensure(jdbc);
        jdbc.update("DELETE FROM fpl_understat_capture_test");
        jdbc.update("DELETE FROM fpl_daily_price_test WHERE season=?", SEASON);
        RankingSeed.cleanSchedule(jdbc, "fpl_gameweek_test", "fpl_fixture_test", SEASON);
        RankingSeed.seedSchedule(jdbc, "fpl_gameweek_test", "fpl_fixture_test", SEASON, CAPTURED,
                Instant.parse("2026-09-18T17:30:00Z"));
        for (int i = 0; i < 20; i++) {
            jdbc.update("""
                    INSERT INTO fpl_daily_price_test (season,price_date,element_id,web_name,
                        team_id,team_short,element_type,now_cost,status,captured_at)
                    VALUES (?,DATE '2026-09-09',?,?,?, ?,3,60,'a',?)
                    """, SEASON, 20000 + i, "Understat player " + i, RankingSeed.team(i + 1),
                    "T%02d".formatted(i), java.sql.Timestamp.from(CAPTURED));
        }
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM fpl_understat_capture_test");
        jdbc.update("DELETE FROM fpl_daily_price_test WHERE season=?", SEASON);
        RankingSeed.cleanSchedule(jdbc, "fpl_gameweek_test", "fpl_fixture_test", SEASON);
    }

    private MetricSeasons metrics() {
        return new MetricSeasons(new SeasonContext(TapasSeed.CURRENT_SEASON_ID, SEASON, true,
                "2026-27", LocalDate.of(2026, 8, 21)), Optional.empty(), 0, 0);
    }

    @Test
    void currentTeamModelDoesNotNeedAnyFplPlayerRowsOrRosterProof() {
        var matches = java.util.stream.IntStream.range(0, 20).mapToObj(i -> new UnderstatParser.Match(i + 1,
                Instant.parse("2026-08-30T14:00:00Z"), "T%02d".formatted(i),
                "T%02d".formatted((i + 1) % 20), 2, 1, BigDecimal.valueOf(2), BigDecimal.ONE)).toList();
        source.replace(List.of(new UnderstatStore.Capture("2026-27", CAPTURED, matches)));
        var snapshot = ranking.load(new DailyPriceSource.Capture(SEASON,
                LocalDate.of(2026, 9, 9)), metrics());
        assertThat(snapshot.clubRates().clubs()).hasSize(20);
        assertThat(snapshot.clubRates().clubs().get(RankingSeed.team(1)).fixturesThisSeason()).isEqualTo(2);
        assertThat(snapshot.clubRates().league().orElseThrow().sides()).isEqualTo(40);
        assertThat(snapshot.clubSampleThrough()).containsEntry("2026-27", Instant.parse("2026-08-30T14:00:00Z"));
        assertThat(snapshot.candidates()).allSatisfy(c -> assertThat(c.current().ability()).isEmpty());
    }

    @Test
    void contradictoryDailyClubNamesCannotAttachAnArbitraryUnderstatClub() {
        var matches = java.util.stream.IntStream.range(0, 20).mapToObj(i -> new UnderstatParser.Match(i + 1,
                Instant.parse("2026-08-30T14:00:00Z"), "T%02d".formatted(i),
                "T%02d".formatted((i + 1) % 20), 2, 1, BigDecimal.valueOf(2), BigDecimal.ONE)).toList();
        source.replace(List.of(new UnderstatStore.Capture("2026-27", CAPTURED, matches)));
        jdbc.update("UPDATE fpl_daily_price_test SET team_id=? WHERE season=? AND element_id=20001",
                RankingSeed.team(1), SEASON);
        var snapshot = ranking.load(new DailyPriceSource.Capture(SEASON, LocalDate.of(2026, 9, 9)), metrics());
        assertThat(snapshot.clubRates().clubs()).doesNotContainKey(RankingSeed.team(1));
        assertThat(snapshot.clubRates().league()).isPresent();
    }

    @Test
    void unavailableUnderstatNeverFallsBackToNumericalFplTeamSums() {
        var played = new SeasonContext(TapasSeed.POPULATED_SEASON_ID, TapasSeed.POPULATED_SEASON_CODE,
                false, "2026-27", LocalDate.of(2026, 8, 21));
        var snapshot = ranking.load(new DailyPriceSource.Capture(SEASON, LocalDate.of(2026, 9, 9)),
                new MetricSeasons(played, Optional.empty(), 0, 0));
        assertThat(snapshot.clubRates().clubs()).isEmpty();
        assertThat(snapshot.clubRates().league()).isEmpty();
    }
}
