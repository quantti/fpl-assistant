package dev.quantti.fplassistant.historical;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests against a hand-built JSON fixture shaped per football-data.org's public
 * documentation (top-level {@code filters}/{@code resultSet}/{@code competition}/{@code matches},
 * each match's {@code homeTeam.name}/{@code awayTeam.name}/{@code score.fullTime.home}/{@code
 * .away}). <b>Not a captured real response</b> — this project has no football-data.org API key.
 * See {@link FootballDataResponseParser}'s class javadoc for why, and {@code docs/STATUS.md} for
 * the open item to verify this against the live API once a key exists.
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
                  "score": { "fullTime": { "home": 2, "away": 1 } }
                },
                {
                  "utcDate": "2026-08-23T14:00:00Z",
                  "status": "SCHEDULED",
                  "matchday": 2,
                  "homeTeam": { "id": 65, "name": "Manchester City FC", "shortName": "Man City", "tla": "MCI" },
                  "awayTeam": { "id": 66, "name": "Manchester United FC", "shortName": "Man Utd", "tla": "MUN" },
                  "score": { "fullTime": { "home": null, "away": null } }
                },
                {
                  "utcDate": "2026-08-24T14:00:00Z",
                  "status": "FINISHED",
                  "matchday": 3,
                  "homeTeam": { "id": 67, "name": "Liverpool FC", "shortName": "Liverpool", "tla": "LIV" },
                  "awayTeam": { "id": 68, "name": "Everton FC", "shortName": "Everton", "tla": "EVE" },
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
}
