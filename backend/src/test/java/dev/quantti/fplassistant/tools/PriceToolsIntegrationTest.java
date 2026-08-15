package dev.quantti.fplassistant.tools;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link PriceTools#getPriceHistory} against the isolated {@code fpl_player_price_test} table.
 * Resolves a real 2025-26 player (so the shared {@code player} name lookup actually finds
 * someone) through a mocked {@link SeasonResolver} that reports the season as current, then
 * verifies the per-GW formatting and the descriptive trend summary.
 */
@SpringBootTest
@ActiveProfiles("test")
class PriceToolsIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Value("${fpl.price.table-name}")
    String priceTable;

    private String code;
    private int pid;

    @BeforeEach
    void refuseProductionAndResetFixture() {
        assertThat(priceTable).as("price tools tests must use the isolated table").endsWith("_test");
    }

    @AfterEach
    void cleanUp() {
        if (code != null && pid != 0) {
            jdbc.update("DELETE FROM " + priceTable + " WHERE season = ? AND element_id = ?", code, pid);
        }
    }

    private int realSeasonId() {
        return ((Number) jdbc.queryForMap("SELECT id, code FROM season WHERE code = '2025-26'").get("id")).intValue();
    }

    private Map<String, Object> salah() {
        return jdbc.queryForMap(
                "SELECT id, web_name FROM player WHERE season_id = ? AND LOWER(second_name) LIKE '%salah%' LIMIT 1",
                realSeasonId());
    }

    @Test
    void returnsPriceHistoryAndDescriptiveTrend() {
        code = "2025-26";
        Map<String, Object> player = salah();
        pid = ((Number) player.get("id")).intValue();

        jdbc.update("INSERT INTO " + priceTable
                + " (season, gameweek, element_id, player_name, price, source) VALUES (?, 1, ?, ?, 140, 'vaastav')",
                code, pid, player.get("web_name"));
        jdbc.update("INSERT INTO " + priceTable
                + " (season, gameweek, element_id, player_name, price, source) VALUES (?, 2, ?, ?, 143, 'vaastav')",
                code, pid, player.get("web_name"));
        jdbc.update("INSERT INTO " + priceTable
                + " (season, gameweek, element_id, player_name, price, source) VALUES (?, 0, ?, ?, 145, 'snapshot')",
                code, pid, player.get("web_name"));

        SeasonResolver mockResolver = mock(SeasonResolver.class);
        when(mockResolver.resolve()).thenReturn(
                new SeasonContext(realSeasonId(), code, true, code, LocalDate.of(2025, 8, 15)));
        PriceTools tools = new PriceTools(jdbc, mockResolver, priceTable);

        String result = tools.getPriceHistory("Salah", 10);

        assertThat(result).contains("Price history for");
        assertThat(result).contains("GW2");
        assertThat(result).contains("live");
        assertThat(result).contains("Trend:");
        assertThat(result).contains("risen"); // 140 -> 145
    }

    @Test
    void unknownPlayerReturnsNotFound() {
        SeasonResolver mockResolver = mock(SeasonResolver.class);
        when(mockResolver.resolve()).thenReturn(
                new SeasonContext(999999, "no-such-season", true, "2026-27", LocalDate.of(2026, 8, 21)));
        PriceTools tools = new PriceTools(jdbc, mockResolver, priceTable);

        assertThat(tools.getPriceHistory("Definitely Not A Real Player", 10)).contains("Player not found");
    }

    @Test
    void noPriceHistoryYetIsReportedPlainly() {
        code = "2025-26";
        pid = ((Number) salah().get("id")).intValue();

        SeasonResolver mockResolver = mock(SeasonResolver.class);
        when(mockResolver.resolve()).thenReturn(
                new SeasonContext(realSeasonId(), code, true, code, LocalDate.of(2025, 8, 15)));
        PriceTools tools = new PriceTools(jdbc, mockResolver, priceTable);

        assertThat(tools.getPriceHistory("Salah", 10)).contains("No price history yet");
    }
}
