package fi.karivanttinen.fplassistant.historical;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests against a hand-built JSON fixture shaped per football-data.org's public
 * documentation (top-level {@code filters}/{@code resultSet}/{@code competition}/{@code matches},
 * each match's {@code homeTeam.name}/{@code awayTeam.name}/{@code score.fullTime.home}/{@code
 * .away}/{@code season.startDate}). The captured-response counterpart
 * ({@link FootballDataCapturedResponseTest}) proves the same shape against the live API's real
 * output.
 */
class FootballDataResponseParserTest {

    private static final String SAMPLE_RESPONSE = """
            {
              "filters": { "season": "2026" },
              "resultSet": { "count": 3 },
              "competition": { "id": 2021, "name": "Premier League" },
              "matches": [
                {
                  "utcDate": "2026-08-22T14:00:00Z",
                  "status": "FINISHED",
                  "matchday": 1,
                  "homeTeam": { "id": 57, "name": "Arsenal FC", "shortName": "Arsenal", "tla": "ARS" },
                  "awayTeam": { "id": 61, "name": "Chelsea FC", "shortName": "Chelsea", "tla": "CHE" },
                  "season": { "id": 2502, "startDate": "2026-08-21", "currentMatchday": 1 },
                  "score": { "fullTime": { "home": 2, "away": 1 } }
                },
                {
                  "utcDate": "2026-08-23T14:00:00Z",
                  "status": "SCHEDULED",
                  "matchday": 2,
                  "homeTeam": { "id": 65, "name": "Manchester City FC", "shortName": "Man City", "tla": "MCI" },
                  "awayTeam": { "id": 66, "name": "Manchester United FC", "shortName": "Man Utd", "tla": "MUN" },
                  "season": { "id": 2502, "startDate": "2026-08-21", "currentMatchday": 1 },
                  "score": { "fullTime": { "home": null, "away": null } }
                },
                {
                  "utcDate": "2026-08-24T14:00:00Z",
                  "status": "FINISHED",
                  "matchday": 3,
                  "homeTeam": { "id": 67, "name": "Liverpool FC", "shortName": "Liverpool", "tla": "LIV" },
                  "awayTeam": { "id": 68, "name": "Everton FC", "shortName": "Everton", "tla": "EVE" },
                  "season": { "id": 2502, "startDate": "2026-08-21", "currentMatchday": 1 },
                  "score": { "fullTime": { "home": 3, "away": 3 } }
                }
              ]
            }
            """;

    @Test
    void parsesAllMatchesRegardlessOfStatus() {
        List<FootballDataMatch> matches = FootballDataResponseParser.parse(SAMPLE_RESPONSE);

        assertThat(matches).hasSize(3);
    }

    @Test
    void finishedMatchesHaveScoresAndAreFlaggedFinished() {
        List<FootballDataMatch> matches = FootballDataResponseParser.parse(SAMPLE_RESPONSE);
        FootballDataMatch first = matches.getFirst();

        assertThat(first.isFinished()).isTrue();
        assertThat(first.homeTeamName()).isEqualTo("Arsenal FC");
        assertThat(first.awayTeamName()).isEqualTo("Chelsea FC");
        assertThat(first.homeGoals()).isEqualTo(2);
        assertThat(first.awayGoals()).isEqualTo(1);
        assertThat(first.matchday()).isEqualTo(1);
    }

    @Test
    void scheduledMatchIsNotFinishedAndHasNullScores() {
        List<FootballDataMatch> matches = FootballDataResponseParser.parse(SAMPLE_RESPONSE);
        FootballDataMatch scheduled = matches.get(1);

        assertThat(scheduled.isFinished()).isFalse();
        assertThat(scheduled.homeGoals()).isNull();
        assertThat(scheduled.awayGoals()).isNull();
    }

    @Test
    void missingMatchesArrayFailsLoudly() {
        assertThatThrownBy(() -> FootballDataResponseParser.parse("{\"filters\":{}}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("matches");
    }

    @Test
    void invalidJsonFailsLoudly() {
        assertThatThrownBy(() -> FootballDataResponseParser.parse("not json"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void seasonCodeIsDerivedFromTheMatchsOwnSeasonStartDate() {
        List<FootballDataMatch> matches = FootballDataResponseParser.parse(SAMPLE_RESPONSE);

        assertThat(matches.stream().map(FootballDataMatch::seasonCode).distinct())
                .containsExactly("2026-27");
    }

    @Test
    void aMatchWithoutAUsableSeasonStartDateParsesWithANullSeasonAndDoesNotAbortTheRest() {
        // The season is never guessed — the sync stamps it on the row — but one malformed match
        // must not cost the other 379: a rescheduled fixture or an upstream schema tweak would
        // otherwise abort a whole sync. A null season is the sync's signal to skip that match.
        String oneBadMatch = """
                { "matches": [
                  { "status": "FINISHED",
                    "homeTeam": { "name": "Arsenal FC" },
                    "awayTeam": { "name": "Chelsea FC" },
                    "score": { "fullTime": { "home": 1, "away": 0 } } },
                  { "status": "FINISHED", "matchday": 1,
                    "season": { "startDate": "2026-08-21" },
                    "homeTeam": { "name": "Liverpool FC" },
                    "awayTeam": { "name": "Everton FC" },
                    "score": { "fullTime": { "home": 2, "away": 0 } } } ] }
                """;

        List<FootballDataMatch> matches = FootballDataResponseParser.parse(oneBadMatch);

        assertThat(matches).hasSize(2);
        assertThat(matches.getFirst().seasonCode()).isNull();
        assertThat(matches.get(1).seasonCode()).isEqualTo("2026-27");
    }

    @Test
    void fractionalOrNonNumericScoreFailsLoudlyInsteadOfTruncating() {
        // Same fail-loud class as VaastavFixtureParser's strict score parsing: a fractional
        // score must never silently truncate to a wrong number that would be stored as fact.
        String fractional = """
                { "matches": [ { "status": "FINISHED",
                  "homeTeam": { "name": "Arsenal FC" },
                  "awayTeam": { "name": "Chelsea FC" },
                  "season": { "startDate": "2026-08-21" },
                  "score": { "fullTime": { "home": 3.5, "away": 1 } } } ] }
                """;
        assertThatThrownBy(() -> FootballDataResponseParser.parse(fractional))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not an integer");

        String textual = """
                { "matches": [ { "status": "FINISHED",
                  "homeTeam": { "name": "Arsenal FC" },
                  "awayTeam": { "name": "Chelsea FC" },
                  "season": { "startDate": "2026-08-21" },
                  "score": { "fullTime": { "home": "abc", "away": 1 } } } ] }
                """;
        assertThatThrownBy(() -> FootballDataResponseParser.parse(textual))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not an integer");
    }
}
