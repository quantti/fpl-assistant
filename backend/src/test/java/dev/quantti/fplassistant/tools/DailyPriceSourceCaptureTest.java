package dev.quantti.fplassistant.tools;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class DailyPriceSourceCaptureTest {

    private static final String SEASON = "T-2098-99";
    private static final int ELEMENT = 9901;

    @Autowired JdbcTemplate jdbc;
    @Autowired DailyPriceSource source;
    @Value("${fpl.daily-price.table-name}") String table;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM " + table + " WHERE season = ? AND element_id = ?", SEASON, ELEMENT);
    }

    @Test
    void theLatestCaptureIsTheNewestDateInTheTable() {
        LocalDate day = LocalDate.now().plusYears(50); // newer than anything another class seeds
        jdbc.update("INSERT INTO " + table + " (season, price_date, element_id, web_name, full_name,"
                + " team_short, team_name, element_type, now_cost, status, news)"
                + " VALUES (?, ?, ?, 'Capturetest', 'Capturetest Fictitious', 'TST', 'Testville', 3, 50, 'a', '')",
                SEASON, day, ELEMENT);

        Optional<DailyPriceSource.Capture> latest = source.latestCapture();

        assertThat(latest).isPresent();
        assertThat(latest.get().date()).isEqualTo(day);
        assertThat(latest.get().season()).isEqualTo(SEASON);
    }
}
