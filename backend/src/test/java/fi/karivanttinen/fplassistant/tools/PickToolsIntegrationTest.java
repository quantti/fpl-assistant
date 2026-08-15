package fi.karivanttinen.fplassistant.tools;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PickTools} against real 2025-26 data. The real {@link SeasonResolver} resolves to
 * 2025-26 (a completed fallback), so the provenance line labels the picks as historical —
 * which is the correct behaviour and what these tests assert. The ranking composite itself is
 * pinned by {@link PickToolsRankingTest}: no season in the database has an unfinished fixture,
 * so an integration run always resolves an empty FDR map.
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

    @Test
    void getDifferentialPicksReturnsLowOwnershipPlayers() {
        String result = tools.getDifferentialPicks("MID", 10, 5);

        assertThat(result).contains("Differential picks");
        assertThat(result).contains("Own ");
        assertThat(result).contains("Form ");
        assertThat(result).contains("Avg FDR");
        assertThat(result).contains("Ranked by form, then average FDR");
        assertThat(result).contains("Data source: season");
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
     * ignores {@code status} recommends players who cannot play. Asserted against the names the
     * database says are unavailable, rather than against a fixed list.
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
        assertThat(unavailable).as("expected some injured/suspended players to guard against").isNotEmpty();

        String differentials = tools.getDifferentialPicks("any", 100, 5);
        String captains = tools.getCaptainCandidates(5);

        for (String name : unavailable) {
            assertThat(differentials).doesNotContain(" " + name + " (");
            assertThat(captains).doesNotContain(" " + name + " (");
        }
        assertThat(differentials).contains("Unavailable players (injured/suspended) are excluded");
    }
}
