package dev.quantti.fplassistant.tools;

import java.util.List;

import dev.quantti.fplassistant.testsupport.TapasSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PickTools} against the seeded fixture league. The real {@link SeasonResolver} resolves to
 * the completed season (the seeded current season holds no data, as in production during the
 * changeover), so the provenance line labels the picks as historical — which is the correct
 * behaviour and what these tests assert. The ranking composite itself is pinned by
 * {@link PickToolsRankingTest}; here the seeded fixtures give the FDR map something real to
 * resolve, which production data never did.
 */
@SpringBootTest
@ActiveProfiles("test")
class PickToolsIntegrationTest {

    @Autowired
    PickTools tools;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SeasonResolver seasonResolver;

    @BeforeEach
    void seed() {
        TapasSeed.ensure(jdbc);
    }

    @Test
    void getDifferentialPicksReturnsLowOwnershipPlayers() {
        String result = tools.getDifferentialPicks("MID", 10, 5);

        assertThat(result).contains("Differential picks");
        assertThat(result).contains("Own ");
        assertThat(result).contains("Score ");
        assertThat(result).contains("Avg FDR");
        // Form is deliberately not a column here: it is 30 days of points, and showing it beside
        // a ranking invites the model to explain the order with it.
        assertThat(result).doesNotContain("| Form ");
        assertThat(result).contains("Ranked by Score");
        assertThat(result).contains("Data source: season");
        // The seeded midfielders straddle the ownership filter: one at 3.2%, one at 12.5%.
        assertThat(result).contains(TapasSeed.DIFFERENTIAL_PLAYER);
        assertThat(result).doesNotContain(TapasSeed.PLAYER_WEB_NAME);
    }

    @Test
    void getDifferentialPicksAnyPositionWorks() {
        String result = tools.getDifferentialPicks("any", 15, 3);
        assertThat(result).contains("Differential picks");
    }

    @Test
    void getCaptainCandidatesReturnsAttackers() {
        String result = tools.getCaptainCandidates(5);

        assertThat(result).contains("Captain candidates");
        assertThat(result).contains("xPts/90 ");
        assertThat(result).contains("Avg FDR");
        assertThat(result).contains("Data source: season");
    }

    /**
     * The seeded season has fixtures still to play, so the FDR column carries actual numbers.
     * Against production data the map was always empty and every pick showed n/a.
     */
    @Test
    void upcomingFixturesGiveThePicksARealFdr() {
        String result = tools.getCaptainCandidates(3);

        assertThat(result).containsPattern("Avg FDR [0-9]");
    }

    @Test
    void noMatchesForAbsurdOwnershipFilter() {
        // No player has negative ownership, so this returns the empty-filter message.
        assertThat(tools.getDifferentialPicks("FWD", -1, 5)).contains("No differentials match");
    }

    @Test
    void anUnrecognisedPositionIsRejectedInsteadOfSilentlyReturningEveryPosition() {
        String result = tools.getDifferentialPicks("striker", 10, 5);

        assertThat(result).contains("Unrecognised position");
        assertThat(result).doesNotContain("Differential picks");
    }

    /**
     * Form is a rolling average that stays high for weeks after an injury, so a pick tool that
     * ignores {@code status} recommends players who cannot play. The seed carries one such
     * player: the highest form in the league, and unavailable.
     */
    @Test
    void unavailablePlayersAreNeverRecommended() {
        int seasonId = seasonResolver.resolve().seasonId();
        // web_name is not unique, so a name shared with an available player proves nothing —
        // it would appear in the output legitimately. Guard only on unambiguously-out names.
        List<String> unavailable = jdbc.queryForList("""
                SELECT web_name FROM player
                WHERE season_id = ? AND form > 0 AND status NOT IN ('a', 'd')
                  AND web_name NOT IN (
                      SELECT web_name FROM player WHERE season_id = ? AND status IN ('a', 'd'))
                """, String.class, seasonId, seasonId);
        assertThat(unavailable)
                .as("expected some injured/suspended players to guard against")
                .contains(TapasSeed.INJURED_PLAYER);

        String differentials = tools.getDifferentialPicks("any", 100, 5);
        String captains = tools.getCaptainCandidates(5);

        for (String name : unavailable) {
            assertThat(differentials).doesNotContain(" " + name + " (");
            assertThat(captains).doesNotContain(" " + name + " (");
        }
        assertThat(differentials).contains("Unavailable players (injured/suspended) are excluded");
    }

    /**
     * The defect this ranking replaced: FPL's {@code form} is average points per match over the
     * last 30 days, so early in a season it is one scoreline. Points still reach the score, but
     * through Pts/90 and weighted by how much season there is behind them — never as the
     * headline.
     */
    @Test
    void theOutputSeparatesWhatIsExpectedFromWhatWasScored() {
        String result = tools.getCaptainCandidates(3);

        assertThat(result).contains("Score ");
        assertThat(result).contains("xPts/90 ");
        assertThat(result).contains("Pts/90 ");
        assertThat(result).contains("Realised points carry ");
    }

    /**
     * The score has to be readable back to its parts, or the model cites a number it cannot
     * explain — and "why this defender" is exactly the question a user asks next.
     */
    @Test
    void everyPickShowsTheComponentsBehindItsExpectedPoints() {
        String result = tools.getDifferentialPicks("MID", 100, 3);

        assertThat(result).containsPattern("xG/90 [0-9]\\.[0-9]{2}, xA/90 [0-9]\\.[0-9]{2}");
    }

    /**
     * Two kinds of defender have to be visible as different picks: the clean-sheet term is what
     * separates a stopper from an attacking full-back, so it is shown for everyone who earns from
     * it — and for nobody who does not.
     */
    @Test
    void defendersAndKeepersShowACleanSheetProbabilityAndForwardsDoNot() {
        assertThat(tools.getDifferentialPicks("DEF", 100, 3))
                .containsPattern("CS [0-9]+% at xGC [0-9]\\.[0-9]{2}");
        assertThat(tools.getDifferentialPicks("GK", 100, 3))
                .containsPattern("CS [0-9]+% at xGC [0-9]\\.[0-9]{2}");
        assertThat(tools.getDifferentialPicks("FWD", 100, 3))
                .as("a forward is paid nothing for a clean sheet")
                .doesNotContain("CS ");
    }

    /** What the model must not do is present the model as complete. */
    @Test
    void theReplySaysWhichPointsTheExpectedModelCannotSee() {
        assertThat(tools.getDifferentialPicks("DEF", 100, 3))
                .contains("Defensive-contribution points, save points and penalty saves are not"
                        + " in the data");
    }

    @Test
    void playersWithoutAnUnderlyingRecordAreExcludedAndCounted() {
        String result = tools.getDifferentialPicks("any", 100, 3);

        assertThat(result).contains("not listed: they have no comparable");
        assertThat(result).doesNotContain(" " + TapasSeed.PLAYER_WITHOUT_UNDERLYING + " (");
    }

    /**
     * The count used to be a second query listing the reasons a player could be absent, and it
     * missed two of them: too few minutes, and a name that is ambiguous across the seasons. Those
     * players left the list without leaving a trace. It is now the pool minus what came back, so
     * every absence is accounted for whatever caused it.
     */
    @Test
    void aPlayerBelowTheMinutesFloorIsCountedAndNotSilentlyDropped() {
        String result = tools.getDifferentialPicks("DEF", 100, 3);

        assertThat(result).doesNotContain(" " + TapasSeed.PLAYER_BELOW_MINUTES_FLOOR + " (");
        assertThat(result).containsPattern("\\b1 further player\\(s\\) matching these filters are not listed");
    }
}
