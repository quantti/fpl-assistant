package fi.karivanttinen.fplassistant.historical;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the parser and the team-alias table against a <b>real captured response body</b> from
 * football-data.org's {@code GET /v4/competitions/PL/matches} (2026-08-15, live API, 380
 * matches, season 2026) — the plan's acceptance criterion for Task 5. This project had no API
 * key when the task was written, so the first cut used a hand-built body; the captured file
 * closes that gap. The file is intentionally unmodified from what the API returned.
 *
 * <p>Every distinct team name in the response must resolve through
 * {@link FootballDataTeamAliases} — this is what surfaced the two promoted clubs (Coventry
 * City, Hull City) that were missing from the table until this check.
 */
class FootballDataCapturedResponseTest {

    private static final String RESOURCE = "/football-data-fixtures/pl-matches-2026-27.json";

    @Test
    void parsesTheCapturedRealResponse() throws IOException {
        List<FootballDataMatch> matches = FootballDataResponseParser.parse(readResource());

        assertThat(matches).hasSize(380);
        // The 2026-27 season had not kicked off when captured: no finished match, no scores.
        assertThat(matches.stream().filter(FootballDataMatch::isFinished)).isEmpty();
        assertThat(matches.stream().allMatch(m -> m.homeGoals() == null && m.awayGoals() == null))
                .as("no played match should carry a score in the captured response")
                .isTrue();
        assertThat(matches.stream().map(FootballDataMatch::matchday).distinct())
                .as("all 38 gameweeks present")
                .hasSize(38);
    }

    @Test
    void everyDistinctTeamNameInTheCapturedResponseResolvesToACode() throws IOException {
        List<FootballDataMatch> matches = FootballDataResponseParser.parse(readResource());

        Set<String> names = new HashSet<>();
        for (FootballDataMatch m : matches) {
            names.add(m.homeTeamName());
            names.add(m.awayTeamName());
        }
        assertThat(names).hasSize(20); // a Premier League season has twenty clubs

        for (String name : names) {
            // throws IllegalStateException on an unknown name — the failure mode this
            // assertion exists to catch
            FootballDataTeamAliases.resolve(name);
        }
    }

    @Test
    void thePromotedClubsThatWereMissingBeforeNowResolve() {
        assertThat(FootballDataTeamAliases.resolve("Coventry City FC")).isEqualTo(9);
        assertThat(FootballDataTeamAliases.resolve("Hull City AFC")).isEqualTo(88);
    }

    private static String readResource() throws IOException {
        try (InputStream in = FootballDataCapturedResponseTest.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing captured response resource " + RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
