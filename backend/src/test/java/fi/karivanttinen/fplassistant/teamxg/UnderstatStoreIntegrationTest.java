package fi.karivanttinen.fplassistant.teamxg;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {"fpl.understat.capture-table-name=fpl_understat_capture_test",
        "fpl.understat.match-table-name=fpl_understat_match_test"})
class UnderstatStoreIntegrationTest {
    @Autowired UnderstatStore store;
    @Autowired JdbcTemplate jdbc;
    private static final Instant FIRST = Instant.parse("2026-10-17T20:00:00Z");
    private static final Instant SECOND = FIRST.plusSeconds(3600);

    @BeforeEach
    void clear() { jdbc.update("DELETE FROM fpl_understat_capture_test"); }

    private UnderstatParser.Match match(long id, String home, String xg) {
        return new UnderstatParser.Match(id, FIRST.minusSeconds(10000), home, "LEE", 2, 0,
                new BigDecimal(xg), BigDecimal.ZERO);
    }

    @Test
    void storesExactDirectTotalsAndReplacesCorrectionsWithoutPlayerRows() {
        store.replace(List.of(new UnderstatStore.Capture("2026-27", FIRST, List.of(match(12, "MCI", "2.082201")))));
        assertThat(jdbc.queryForObject("SELECT home_xg FROM fpl_understat_match_test", BigDecimal.class))
                .isEqualByComparingTo("2.082201");
        assertThat(jdbc.queryForObject("SELECT away_xg FROM fpl_understat_match_test", BigDecimal.class))
                .isEqualByComparingTo(BigDecimal.ZERO);
        store.replace(List.of(new UnderstatStore.Capture("2026-27", SECOND, List.of(match(12, "MCI", "2.1")))));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fpl_understat_match_test", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT home_xg FROM fpl_understat_match_test", BigDecimal.class)).isEqualByComparingTo("2.1");
    }

    @Test
    void emptyCaptureRemovesWithdrawnResultsButKeepsTheCaptureTime() {
        store.replace(List.of(new UnderstatStore.Capture("2026-27", FIRST, List.of(match(12, "MCI", "2")))));
        store.replace(List.of(new UnderstatStore.Capture("2026-27", SECOND, List.of())));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fpl_understat_match_test", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT captured_at FROM fpl_understat_capture_test", java.sql.Timestamp.class).toInstant()).isEqualTo(SECOND);
    }

    @Test
    void olderConcurrentCaptureCannotOverwriteNewerEvidence() {
        store.replace(List.of(new UnderstatStore.Capture("2026-27", SECOND, List.of(match(12, "MCI", "2.1")))));
        store.replace(List.of(new UnderstatStore.Capture("2026-27", FIRST, List.of())));
        assertThat(jdbc.queryForObject("SELECT home_xg FROM fpl_understat_match_test", BigDecimal.class)).isEqualByComparingTo("2.1");
    }

    @Test
    void failedBatchRollsBackBothSeasonsAndCaptureMetadata() {
        store.replace(List.of(new UnderstatStore.Capture("2026-27", FIRST, List.of(match(12, "MCI", "2")))));
        var invalid = new UnderstatStore.Capture("2026-27", SECOND,
                List.of(match(13, "ARS", "1"), match(14, "MCI", "-1")));
        assertThatThrownBy(() -> store.replace(List.of(new UnderstatStore.Capture("2025-26", SECOND,
                List.of(match(11, "ARS", "1"))), invalid))).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForList("SELECT season FROM fpl_understat_capture_test", String.class)).containsExactly("2026-27");
        assertThat(jdbc.queryForObject("SELECT captured_at FROM fpl_understat_capture_test", java.sql.Timestamp.class).toInstant()).isEqualTo(FIRST);
        assertThat(jdbc.queryForObject("SELECT match_id FROM fpl_understat_match_test", Long.class)).isEqualTo(12L);
    }
}
