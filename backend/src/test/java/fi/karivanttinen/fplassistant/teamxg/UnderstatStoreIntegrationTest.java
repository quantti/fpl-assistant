package fi.karivanttinen.fplassistant.teamxg;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
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
    @AfterEach
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

    private List<UnderstatParser.Match> league(int year, String prefix, String homeXg, String awayXg) {
        return java.util.stream.IntStream.range(0, 20).mapToObj(i -> new UnderstatParser.Match(i + 1,
                Instant.parse(year + "-10-01T14:00:00Z"), "%s%02d".formatted(prefix, i),
                "%s%02d".formatted(prefix, (i + 1) % 20), 1, 0,
                new BigDecimal(homeXg), new BigDecimal(awayXg))).toList();
    }

    @Test
    void modelUsesDirectTotalsAndPoolsClubsNotInTheDailyPool() {
        store.replace(List.of(new UnderstatStore.Capture("2026-27", FIRST, league(2026, "T", "2", "1")),
                new UnderstatStore.Capture("2025-26", FIRST, league(2025, "P", "10", "0"))));
        var model = store.loadModel(2026, java.util.Map.of(9, "T00"), SECOND);
        assertThat(model.rates().clubs()).containsOnlyKeys(9);
        assertThat(model.rates().clubs().get(9).fixturesThisSeason()).isEqualTo(2);
        assertThat(model.rates().clubs().get(9).attack()).isEqualTo(1.5);
        assertThat(model.rates().league().orElseThrow().sides()).isEqualTo(80);
        assertThat(model.rates().league().orElseThrow().meanXg()).isEqualTo(3.25);
        assertThat(model.capturedAt()).containsEntry("2026-27", FIRST).containsEntry("2025-26", FIRST);
        assertThat(model.through()).containsEntry("2026-27", Instant.parse("2026-10-01T14:00:00Z"));
    }

    @Test
    void clubBlendUsesOwnUnderstatFixtureCountAndStableIdentityNotFplSeasonIds() {
        store.replace(List.of(new UnderstatStore.Capture("2026-27", FIRST, league(2026, "T", "2", "1")),
                new UnderstatStore.Capture("2025-26", FIRST, league(2025, "T", "1", "3"))));
        var model = store.loadModel(2026, java.util.Map.of(9900, "T00"), SECOND);
        var rate = model.rates().clubs().get(9900);
        assertThat(rate.hasPrevious()).isTrue();
        assertThat(rate.fixturesThisSeason()).isEqualTo(2);
        assertThat(rate.weight()).isEqualTo(0.3);
        assertThat(rate.attack()).isCloseTo(0.3 * 1.5 + 0.7 * 2.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(rate.defence()).isCloseTo(0.3 * 1.5 + 0.7 * 2.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(model.rates().clubs()).containsOnlyKeys(9900);
    }

    @Test
    void archiveOnlyEvidenceIsNotLabelledAsCurrentSeason() {
        store.replace(List.of(new UnderstatStore.Capture("2025-26", FIRST, league(2025, "T", "1", "3"))));
        var model = store.loadModel(2026, java.util.Map.of(9900, "T00"), SECOND);
        assertThat(model.capturedAt()).containsOnlyKeys("2025-26");
        assertThat(model.through()).containsOnlyKeys("2025-26");
        assertThat(model.rates().clubs().get(9900).fixturesThisSeason()).isZero();
        assertThat(model.rates().clubs().get(9900).attack()).isEqualTo(2);
    }

    @Test
    void futureMatchRowsCannotAdvanceCountsOrTheSampleDate() {
        var data = new java.util.ArrayList<>(league(2026, "T", "2", "1"));
        data.add(new UnderstatParser.Match(99, SECOND.plusSeconds(1), "T00", "T02", 1, 0,
                BigDecimal.ONE, BigDecimal.ZERO));
        store.replace(List.of(new UnderstatStore.Capture("2026-27", FIRST, data)));
        var model = store.loadModel(2026, java.util.Map.of(9900, "T00"), SECOND);
        assertThat(model.rates().clubs().get(9900).fixturesThisSeason()).isEqualTo(2);
        assertThat(model.through().get("2026-27")).isEqualTo(Instant.parse("2026-10-01T14:00:00Z"));
    }

    @Test
    void realZeroXgaStillContributesFixturesRatherThanBeingTreatedAsUnpopulated() {
        store.replace(List.of(new UnderstatStore.Capture("2026-27", FIRST, league(2026, "T", "0", "0"))));
        var model = store.loadModel(2026, java.util.Map.of(9, "T00"), SECOND);
        assertThat(model.rates().clubs().get(9).fixturesThisSeason()).isEqualTo(2);
        assertThat(model.rates().clubs().get(9).defence()).isZero();
        assertThat(model.rates().league()).isEmpty();
    }

    @Test
    void missingFutureAndAmbiguousIdentityNeverBecomeAZeroModel() {
        assertThat(store.loadModel(2026, java.util.Map.of(9, "T00"), SECOND).rates().clubs()).isEmpty();
        store.replace(List.of(new UnderstatStore.Capture("2026-27", SECOND, league(2026, "T", "2", "1"))));
        assertThat(store.loadModel(2026, java.util.Map.of(9, "T00"), FIRST).capturedAt()).isEmpty();
        assertThat(store.loadModel(2026, java.util.Map.of(9, "T00", 10, "T00"), SECOND).rates().clubs()).isEmpty();
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
