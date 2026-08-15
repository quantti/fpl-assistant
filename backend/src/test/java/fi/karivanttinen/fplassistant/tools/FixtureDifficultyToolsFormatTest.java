package fi.karivanttinen.fplassistant.tools;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the fixture-difficulty rendering.
 *
 * <p>These exist because an integration test cannot reach this code at all: no season in the
 * shared database has a single {@code finished = false} fixture (2025-26 is complete, 2026-27
 * has no fixture rows yet), so every database-backed test takes the "no upcoming fixtures"
 * branch. The previous integration test asserted
 * {@code containsAnyOf("Fixture difficulty for", "No upcoming fixtures found")} and therefore
 * passed without ever exercising the FDR selection, the average, or the venue logic — the same
 * always-green assertion Phase 1's {@code GameweekToolsTest} was corrected for.
 */
class FixtureDifficultyToolsFormatTest {

    private static Map<String, Object> fixture(Integer gameweek, String home, String away,
                                               Integer homeFdr, Integer awayFdr,
                                               boolean isHome, Instant kickoff) {
        Map<String, Object> row = new HashMap<>();
        row.put("gameweek", gameweek);
        row.put("home_short", home);
        row.put("away_short", away);
        row.put("team_h_difficulty", homeFdr);
        row.put("team_a_difficulty", awayFdr);
        row.put("is_home", isHome);
        row.put("kickoff_time", kickoff == null ? null : Timestamp.from(kickoff));
        return row;
    }

    @Test
    void takesTheRatingFromTheTeamsOwnSideOfTheFixture() {
        String out = FixtureDifficultyTools.format("Arsenal", "ARS", List.of(
                fixture(1, "MUN", "ARS", 2, 4, false, Instant.parse("2026-08-21T18:00:00Z")),
                fixture(2, "ARS", "LEE", 2, 5, true, Instant.parse("2026-08-29T14:00:00Z"))
        ), List.of());

        // Away at Man Utd: Arsenal's rating is team_a_difficulty (4), not the home side's 2.
        assertThat(out).contains("GW1: vs MUN (A) | FDR 4");
        // Home to Leeds: Arsenal's rating is team_h_difficulty (2), not the away side's 5.
        assertThat(out).contains("GW2: vs LEE (H) | FDR 2");
        assertThat(out).contains("Average FDR over the next 2 rated fixture(s): 3.0");
    }

    @Test
    void kickoffIsRenderedInUkTimeAndLabelled() {
        // The container runs UTC on Fly; 18:00Z in late August is 19:00 British Summer Time.
        String out = FixtureDifficultyTools.format("Arsenal", "ARS", List.of(
                fixture(1, "ARS", "LEE", 2, 5, true, Instant.parse("2026-08-21T18:00:00Z"))
        ), List.of());

        assertThat(out).contains("2026-08-21 19:00 UK");
    }

    @Test
    void missingKickoffSaysSoInsteadOfPrintingNull() {
        String out = FixtureDifficultyTools.format("Arsenal", "ARS", List.of(
                fixture(3, "ARS", "LEE", 2, 5, true, null)
        ), List.of());

        assertThat(out).contains("kickoff TBD");
        assertThat(out).doesNotContain("null");
    }

    @Test
    void anUnratedFixtureIsExcludedFromTheAverageRatherThanCountedAsZero() {
        String out = FixtureDifficultyTools.format("Arsenal", "ARS", List.of(
                fixture(1, "ARS", "LEE", 2, 5, true, Instant.parse("2026-08-21T18:00:00Z")),
                fixture(2, "MUN", "ARS", null, null, false, Instant.parse("2026-08-29T14:00:00Z"))
        ), List.of());

        assertThat(out).contains("FDR unknown");
        // Counting the unrated fixture as 0 would report 1.0; the rated fixture alone is 2.0.
        assertThat(out).contains("Average FDR over the next 1 rated fixture(s): 2.0");
        assertThat(out).contains("1 fixture(s) carry no FDR rating yet");
        assertThat(out).doesNotContain("FDR 0");
    }

    @Test
    void noRatedFixturesGivesNoAverageRatherThanDivideByZero() {
        String out = FixtureDifficultyTools.format("Arsenal", "ARS", List.of(
                fixture(1, "ARS", "LEE", null, null, true, Instant.parse("2026-08-21T18:00:00Z"))
        ), List.of());

        assertThat(out).contains("None of these fixtures carry an FDR rating yet");
        assertThat(out).doesNotContain("Average FDR");
    }

    @Test
    void postponedFixturesAreListedSeparatelyInsteadOfDropped() {
        String out = FixtureDifficultyTools.format("Arsenal", "ARS",
                List.of(fixture(1, "ARS", "LEE", 2, 5, true, Instant.parse("2026-08-21T18:00:00Z"))),
                List.of(fixture(null, "EVE", "ARS", 3, 4, false, null)));

        assertThat(out).contains("Postponed / not yet assigned to a gameweek:");
        assertThat(out).contains("vs EVE (A) | FDR 4 | date to be confirmed");
        // The postponed fixture must not silently join the scheduled average.
        assertThat(out).contains("Average FDR over the next 1 rated fixture(s): 2.0");
    }
}
