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
        assertThat(result).contains("Form ");
        assertThat(result).contains("Avg FDR");
        assertThat(result).contains("Ranked by form, then average FDR");
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
        assertThat(result).contains("Form ");
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
}
