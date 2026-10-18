package dev.quantti.fplassistant.tools;

import java.time.Instant;

import dev.quantti.fplassistant.testsupport.DataAgeClock;
import dev.quantti.fplassistant.testsupport.NewSeasonSeed;
import dev.quantti.fplassistant.testsupport.SharedStatsSeed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExpectedStatsTools} across the season boundary. Unlike the ranking tools it shows
 * fixtures as rows, and a row cannot be weighted — so a short current-season window is
 * <em>filled</em> from last season instead of blended, with every row naming its own season.
 *
 * <p>Without this class the fill would first run in production, on the day the new season's
 * first gameweek syncs and every player has two fixtures to his name.
 */
@SpringBootTest
@ActiveProfiles("test")
class ExpectedStatsToolsCrossSeasonTest {

    static final Instant AS_OF = DataAgeClock.AS_OF;

    /** Built with the fixed clock rather than autowired; see {@link DataAgeClock}. */
    ExpectedStatsTools tools;
    @Autowired dev.quantti.fplassistant.teamxg.UnderstatStore understat;

    @Autowired
    SeasonResolver seasonResolver;

    @Autowired
    PlayerNameSearch names;

    @BeforeEach
    void buildTools() {
        tools = new ExpectedStatsTools(jdbc, seasonResolver, DataAgeClock.provider(), names, understat);
    }

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        SharedStatsSeed.ensure(jdbc);
        NewSeasonSeed.begin(jdbc);
        NewSeasonSeed.giveFerreiraTwoMatches(jdbc);
    }

    @AfterEach
    void endTheNewSeason() {
        NewSeasonSeed.end(jdbc);
    }

    /**
     * Ferreira has two fixtures this season and two in the populated season. Asking for five
     * shows all four, each labelled, under two provenance lines.
     */
    @Test
    void fewerCurrentFixturesThanAskedForAreFilledFromLastSeasonAndLabelled() {
        String result = tools.getPlayerExpectedStats("Ferreira", 5);

        assertThat(result).contains("Data source: season " + SharedStatsSeed.CURRENT_SEASON_CODE
                + " (the season currently being played)");
        assertThat(result).contains("Data source: season " + SharedStatsSeed.POPULATED_SEASON_CODE
                + " — a COMPLETED PREVIOUS season");
        assertThat(result).contains("last 4 fixtures: 2 from " + SharedStatsSeed.CURRENT_SEASON_CODE
                + ", 2 from " + SharedStatsSeed.POPULATED_SEASON_CODE);
        assertThat(result).contains("  " + SharedStatsSeed.CURRENT_SEASON_CODE + " GW101");
        assertThat(result).contains("  " + SharedStatsSeed.POPULATED_SEASON_CODE + " GW");

        // KAR-9: each season carries its own coverage range; the two never share one.
        String current = "Stored match coverage: Ferreira, season " + SharedStatsSeed.CURRENT_SEASON_CODE
                + " (2 returned fixture rows), known match dates from 2026-08-15 15:00 UK (25 days ago)"
                + " to 2026-08-16 15:00 UK (24 days ago).";
        String previous = "Stored match coverage: Ferreira, season " + SharedStatsSeed.POPULATED_SEASON_CODE
                + " (2 returned fixture rows), known match dates from 2025-08-16 17:30 UK (389 days ago)"
                + " to 2025-08-23 17:30 UK (382 days ago).";
        String currentRefresh = "Metric refresh: season " + SharedStatsSeed.CURRENT_SEASON_CODE
                + ", returned sample;";
        String previousRefresh = "Metric refresh: season " + SharedStatsSeed.POPULATED_SEASON_CODE
                + ", returned sample;";
        assertThat(result).containsOnlyOnce(currentRefresh).containsOnlyOnce(previousRefresh)
                .containsOnlyOnce(current).containsOnlyOnce(previous);
        int previousBanner = result.indexOf("a COMPLETED PREVIOUS season");
        assertThat(result.indexOf(currentRefresh)).isGreaterThan(previousBanner);
        assertThat(result.indexOf(current)).isGreaterThan(previousBanner)
                .isLessThan(result.indexOf("Expected stats for"));
        assertThat(result.indexOf(previous)).isLessThan(result.indexOf("Expected stats for"));
    }

    /** KAR-58: a filled window never pools two seasons' DefCon into one unattributed rate. */
    @Test
    void aFilledWindowReportsDefconPerSeason() {
        String result = tools.getPlayerExpectedStats("Ferreira", 5);

        assertThat(result)
                .containsOnlyOnce("Defensive contribution, season " + SharedStatsSeed.CURRENT_SEASON_CODE)
                .containsOnlyOnce("Defensive contribution, season " + SharedStatsSeed.POPULATED_SEASON_CODE);
        assertThat(result.indexOf("Defensive contribution, season " + SharedStatsSeed.CURRENT_SEASON_CODE))
                .isLessThan(result.indexOf("Defensive contribution, season " + SharedStatsSeed.POPULATED_SEASON_CODE));
    }

    @Test
    void enoughCurrentFixturesMeansNoFillAndNoSecondProvenanceLine() {
        String result = tools.getPlayerExpectedStats("Ferreira", 2);

        assertThat(result).doesNotContain("COMPLETED PREVIOUS");
        assertThat(result).contains("(last 2 fixtures):");
        assertThat(result).doesNotContain("from " + SharedStatsSeed.POPULATED_SEASON_CODE);
        assertThat(result).containsOnlyOnce("Stored match coverage:")
                .contains("Stored match coverage: Ferreira, season " + SharedStatsSeed.CURRENT_SEASON_CODE + " (")
                .doesNotContain("2025-");
    }

    /**
     * The same fill for a club. Ashfield played both of this season's seeded fixtures and has
     * fixtures in the populated season too, so asking for four reaches back.
     */
    @Test
    void anAmbiguousNameInThePreviousSeasonIsNotFilled() {
        String result = tools.getPlayerExpectedStats("Rask", 5);

        assertThat(result).doesNotContain("COMPLETED PREVIOUS");
        assertThat(result).doesNotContain("from " + SharedStatsSeed.POPULATED_SEASON_CODE);
    }
}
