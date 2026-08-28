package dev.quantti.fplassistant.tools;

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

    @Autowired
    ExpectedStatsTools tools;

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
    }

    @Test
    void enoughCurrentFixturesMeansNoFillAndNoSecondProvenanceLine() {
        String result = tools.getPlayerExpectedStats("Ferreira", 2);

        assertThat(result).doesNotContain("COMPLETED PREVIOUS");
        assertThat(result).contains("(last 2 fixtures):");
        assertThat(result).doesNotContain("from " + SharedStatsSeed.POPULATED_SEASON_CODE);
    }

    /**
     * The same fill for a club. Ashfield played both of this season's seeded fixtures and has
     * fixtures in the populated season too, so asking for four reaches back.
     */
    @Test
    void aTeamsShortCurrentWindowIsAlsoFilledFromLastSeason() {
        String result = tools.getTeamExpectedGoals(SharedStatsSeed.ASHFIELD, 4);

        assertThat(result).contains("Data source: season " + SharedStatsSeed.POPULATED_SEASON_CODE
                + " — a COMPLETED PREVIOUS season");
        assertThat(result).contains("from " + SharedStatsSeed.CURRENT_SEASON_CODE);
        assertThat(result).contains("from " + SharedStatsSeed.POPULATED_SEASON_CODE);
        assertThat(result).contains("  " + SharedStatsSeed.CURRENT_SEASON_CODE + " GW101");
    }

    /**
     * A name that belongs to two players in the previous season cannot be bridged: attaching one
     * of them would put another player's football on this card. Rask is exactly that pair.
     */
    @Test
    void anAmbiguousNameInThePreviousSeasonIsNotFilled() {
        String result = tools.getPlayerExpectedStats("Rask", 5);

        assertThat(result).doesNotContain("COMPLETED PREVIOUS");
        assertThat(result).doesNotContain("from " + SharedStatsSeed.POPULATED_SEASON_CODE);
    }
}
