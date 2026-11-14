package dev.quantti.fplassistant.tools;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import dev.quantti.fplassistant.teamxg.UnderstatParser;
import dev.quantti.fplassistant.teamxg.UnderstatStore;
import dev.quantti.fplassistant.testsupport.SharedStatsSeed;
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
class TeamUnderstatIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-09T12:00:00Z");
    @TestConfiguration
    static class Time { @Bean @Primary Clock teamClock() { return Clock.fixed(NOW, ZoneOffset.UTC); } }
    @Autowired ExpectedStatsTools tools;
    @Autowired UnderstatStore source;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        SharedStatsSeed.ensure(jdbc);
        jdbc.update("DELETE FROM fpl_understat_capture_test");
        source.replace(List.of(new UnderstatStore.Capture("2026-27", NOW.minusSeconds(10), List.of(
                new UnderstatParser.Match(1, NOW.minusSeconds(86400), "MCI", "LEE", 2, 0,
                        new BigDecimal("2.082201"), BigDecimal.ZERO))),
                new UnderstatStore.Capture("2025-26", NOW.minusSeconds(10), List.of(
                        new UnderstatParser.Match(2, Instant.parse("2026-05-24T15:00:00Z"), "ARS", "MCI", 1, 0,
                                new BigDecimal("0.65"), new BigDecimal("1.23"))))));
    }

    @AfterEach
    void cleanUp() { jdbc.update("DELETE FROM fpl_understat_capture_test"); }

    @Test
    void returnsDirectAttackAndDefenceWithBothSourceSeasonsButNoPlayerSums() {
        String result = tools.getTeamExpectedGoals("Man City", 5);
        assertThat(result).contains("Understat", "2026/27", "2025/26", "2.08", "1.23", "0.65", "0.00")
                .doesNotContain("xA", "xGI", "Stored match coverage:", "Metric refresh:");
    }

    @Test
    void noCaptureIsExplicitAndDoesNotBorrowFplRows() {
        jdbc.update("DELETE FROM fpl_understat_capture_test");
        assertThat(tools.getTeamExpectedGoals("MCI", 5)).contains("Understat", "unavailable");
    }

    @Test
    void ambiguousClubNamesRequireClarification() {
        assertThat(tools.getTeamExpectedGoals("Manchester", 5)).contains("Multiple", "MCI", "MUN");
    }

    @Test
    void requestClampsToOneAndNeverFillsItWithOlderSeasonUnnecessarily() {
        assertThat(tools.getTeamExpectedGoals("MCI", 0)).contains("2026/27", "2.08")
                .doesNotContain("2025/26", "1.23");
    }
}
