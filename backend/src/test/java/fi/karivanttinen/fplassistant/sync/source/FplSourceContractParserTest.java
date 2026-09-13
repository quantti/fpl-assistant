package fi.karivanttinen.fplassistant.sync.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fi.karivanttinen.fplassistant.sync.source.FplSourceContractParser.FixtureSides;
import fi.karivanttinen.fplassistant.sync.source.FplSourceContractParser.HistoryFixtureEvidence;
import fi.karivanttinen.fplassistant.sync.source.FplSourceContractParser.LiveEvent;
import fi.karivanttinen.fplassistant.sync.source.FplSourceContractParser.LiveFixtureEvidence;
import fi.karivanttinen.fplassistant.sync.source.FplSourceContractParser.LiveStatEvidence;

class FplSourceContractParserTest {

    private static final String FIXTURES = "/owned-sync-source-fixtures/";

    @Test
    void capturedFinishedEventProvidesFixtureScopedAppearedParticipantManifest() throws IOException {
        LiveEvent live = parseLive("event-3-live.json");

        List<LiveFixtureEvidence> fixture = live.fixture(29);
        assertThat(fixture).hasSize(62);
        assertThat(fixture).allSatisfy(row -> assertThat(row.minutes()).isBetween(0, 130));
        Set<Integer> appeared = fixture.stream().filter(LiveFixtureEvidence::appeared)
                .map(LiveFixtureEvidence::playerId).collect(java.util.stream.Collectors.toSet());
        assertThat(appeared).hasSize(31).isEqualTo(fixtureStatParticipants(29));

        HistoryFixtureEvidence raya = history(1, "element-summary-1.json", 29);
        LiveFixtureEvidence rayaLive = fixture.stream().filter(row -> row.playerId() == 1).findFirst().orElseThrow();
        assertThat(rayaLive.minutes()).isEqualTo(raya.minutes()).isEqualTo(90);
        assertThat(raya.totalPoints()).isEqualTo(3);
        assertThat(rayaLive.stat("minutes"))
                .isEqualTo(new LiveStatEvidence("minutes", 90, 2, 0));
        assertThat(raya.wasHome()).isTrue();
        assertThat(raya.opponentTeamId()).isEqualTo(6);
    }

    @Test
    void capturedSourceZeroIsAnObservationNotAMissingValue() throws IOException {
        LiveFixtureEvidence liveZero = parseLive("event-3-live.json").fixture(29).stream()
                .filter(row -> row.playerId() == 2).findFirst().orElseThrow();
        HistoryFixtureEvidence historyZero = history(2, "element-summary-2.json", 29);

        assertThat(liveZero.minutes()).isZero();
        assertThat(liveZero.stat("minutes"))
                .isEqualTo(new LiveStatEvidence("minutes", 0, 0, 0));
        assertThat(historyZero.minutes()).isZero();
        assertThat(historyZero.totalPoints()).isZero();
        assertThat(historyZero.expectedGoals()).contains(new BigDecimal("0.00"));
        assertThat(historyZero.defensiveContribution()).contains(0);
    }

    @Test
    void capturedOngoingEventDoesNotTurnScheduledPlayersIntoAppearances() throws IOException {
        List<LiveFixtureEvidence> notStarted = parseLive("event-4-live.json").fixture(40);

        assertThat(notStarted).hasSize(60);
        assertThat(notStarted).noneMatch(LiveFixtureEvidence::appeared);
    }

    @Test
    void syntheticDoubleGameweekKeepsLegsAndChangedStatFieldsSeparate() {
        LiveEvent live = FplSourceContractParser.parseLiveEvent(json("""
                {"elements":[{"id":7,"stats":{"minutes":135},"explain":[
                  {"fixture":101,"stats":[
                    {"identifier":"minutes","points":2,"points_modification":0,"value":90},
                    {"identifier":"bonus","points":1,"points_modification":-1,"value":1}]},
                  {"fixture":109,"stats":[
                    {"identifier":"minutes","points":1,"points_modification":0,"value":45},
                    {"identifier":"yellow_cards","points":-1,"points_modification":0,"value":1}]}
                ]}]}
                """));

        LiveFixtureEvidence first = live.fixture(101).getFirst();
        LiveFixtureEvidence second = live.fixture(109).getFirst();
        assertThat(first.minutes()).isEqualTo(90);
        assertThat(first.rawStats()).containsExactly(
                new LiveStatEvidence("minutes", 90, 2, 0),
                new LiveStatEvidence("bonus", 1, 1, -1));
        assertThat(second.minutes()).isEqualTo(45);
        assertThat(second.rawStats()).containsExactly(
                new LiveStatEvidence("minutes", 45, 1, 0),
                new LiveStatEvidence("yellow_cards", 1, -1, 0));
    }

    @Test
    void rawPointsAndModificationRemainSeparateWithoutInferredFixtureTotal() {
        LiveFixtureEvidence evidence = FplSourceContractParser.parseLiveEvent(json("""
                {"elements":[{"id":7,"explain":[{"fixture":101,"stats":[
                  {"identifier":"minutes","value":90,"points":2,"points_modification":-1}
                ]}]}]}
                """)).fixture(101).getFirst();

        assertThat(evidence.stat("minutes").points()).isEqualTo(2);
        assertThat(evidence.stat("minutes").pointsModification()).isEqualTo(-1);
    }

    @Test
    void syntheticHistoryReconstructsEachFixtureSideAndRejectsContradictoryOpponent() {
        List<HistoryFixtureEvidence> history = parseHistory(7, json("""
                {"history":[
                  {"element":7,"fixture":101,"round":8,"opponent_team":20,"was_home":true,
                   "minutes":90,"total_points":6},
                  {"element":7,"fixture":109,"round":8,"opponent_team":30,"was_home":false,
                   "minutes":45,"total_points":1}
                ]}
                """));

        assertThat(FplSourceContractParser.reconstructPlayerTeam(history.get(0),
                new FixtureSides(101, 10, 20))).isEqualTo(10);
        assertThat(FplSourceContractParser.reconstructPlayerTeam(history.get(1),
                new FixtureSides(109, 30, 10))).isEqualTo(10);
        assertThatThrownBy(() -> FplSourceContractParser.reconstructPlayerTeam(history.get(0),
                new FixtureSides(101, 10, 99)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("opponent contradicts fixture");
    }

    @Test
    void syntheticPreTransferCurrentTeamIsNotUsedForHistoricalSide() {
        HistoryFixtureEvidence oldMatch = parseHistory(7, json("""
                {"history":[{"element":7,"fixture":101,"round":1,"opponent_team":20,"was_home":true,
                  "minutes":90,"total_points":2}]}
                """)).getFirst();
        int currentBootstrapTeam = 30;

        int historicalTeam = FplSourceContractParser.reconstructPlayerTeam(oldMatch,
                new FixtureSides(101, 10, 20));

        assertThat(historicalTeam).isEqualTo(10).isNotEqualTo(currentBootstrapTeam);
    }

    @Test
    void syntheticPendingAndPostponedShapesRetainRawNullableState() throws IOException {
        JsonNode fixtures = new ObjectMapper().readTree("""
                [{"id":1,"event":4,"kickoff_time":"2026-09-13T15:00:00Z",
                  "started":true,"finished_provisional":true,"finished":false},
                 {"id":2,"event":null,"kickoff_time":null,
                  "started":false,"finished_provisional":false,"finished":false}]
                """);

        assertThat(fixtures.get(0).path("started").booleanValue()).isTrue();
        assertThat(fixtures.get(0).path("finished_provisional").booleanValue()).isTrue();
        assertThat(fixtures.get(0).path("finished").booleanValue()).isFalse();
        assertThat(fixtures.get(1).get("event").isNull()).isTrue();
        assertThat(fixtures.get(1).get("kickoff_time").isNull()).isTrue();
    }

    @Test
    void missingHistoryRowRemainsMissingRatherThanBecomingZero() throws IOException {
        List<HistoryFixtureEvidence> history = parseHistory(658, "element-summary-658.json");

        assertThat(history).extracting(HistoryFixtureEvidence::fixtureId).containsExactly(38);
        assertThat(history).noneMatch(row -> row.fixtureId() == 29);
    }

    @Test
    void optionalMetricMissingDiffersFromSourceZero() {
        HistoryFixtureEvidence missing = parseHistory(9, json("""
                {"history":[{"element":9,"fixture":1,"round":1,"opponent_team":2,"was_home":true,
                  "minutes":0,"total_points":0}]}
                """)).getFirst();
        HistoryFixtureEvidence zero = parseHistory(9, json("""
                {"history":[{"element":9,"fixture":1,"round":1,"opponent_team":2,"was_home":true,
                  "minutes":0,"total_points":0,"expected_goals":"0.00","defensive_contribution":0}]}
                """)).getFirst();

        assertThat(missing.expectedGoals()).isEmpty();
        assertThat(missing.defensiveContribution()).isEmpty();
        assertThat(zero.expectedGoals()).contains(new BigDecimal("0.00"));
        assertThat(zero.defensiveContribution()).contains(0);
    }

    @Test
    void duplicateJsonFieldsTrailingTokensAndEmptyRootsAreRejected() {
        assertInvalidLive("{\"elements\":[],\"elements\":[]}", "malformed JSON");
        assertInvalidLive("{\"elements\":[{\"id\":1,\"id\":2,\"explain\":[]}]}" , "malformed JSON");
        assertInvalidLive("{\"elements\":[]} []", "malformed JSON");
        assertInvalidLive("", "empty or null JSON root");
        assertInvalidLive("   ", "empty or null JSON root");
        assertInvalidLive("null", "empty or null JSON root");
        assertInvalidLive("42", "non-container JSON root");
    }

    @Test
    void malformedAndNegativeNumericFieldsAreRejected() {
        assertInvalidLive("""
                {"elements":[{"id":-1,"explain":[]}]}
                """, "id must be positive");
        assertInvalidLive("""
                {"elements":[{"id":1.5,"explain":[]}]}
                """, "non-integer id");
        assertInvalidLive("""
                {"elements":[{"id":2147483648,"explain":[]}]}
                """, "non-integer id");
        assertInvalidLive("""
                {"elements":[{"id":1,"explain":[{"fixture":3,"stats":[
                  {"identifier":"minutes","points":0,"points_modification":0,"value":-1}]}]}]}
                """, "minutes out of range");

        assertInvalidHistory("""
                {"history":[{"element":1,"fixture":3,"round":-1,"opponent_team":2,
                  "was_home":true,"minutes":0,"total_points":0}]}
                """, "round must be positive");
        assertInvalidHistory("""
                {"history":[{"element":1,"fixture":3,"round":1,"opponent_team":2,
                  "was_home":true,"minutes":0,"total_points":0,"defensive_contribution":1.5}]}
                """, "non-integer defensive_contribution");
        assertInvalidHistory("""
                {"history":[{"element":1,"fixture":3,"round":1,"opponent_team":2,
                  "was_home":true,"minutes":0,"total_points":0,"expected_goals":"not-a-number"}]}
                """, "non-decimal expected_goals");
    }

    @Test
    void missingMinutesAndDuplicateIdentitiesAreRejected() {
        assertInvalidLive("""
                {"elements":[{"id":1,"explain":[{"fixture":3,"stats":[]}]}]}
                """, "missing minutes");
        assertInvalidLive("""
                {"elements":[{"id":1,"explain":[]},{"id":1,"explain":[]}]}
                """, "duplicate live element id");
        assertInvalidLive("""
                {"elements":[{"id":1,"explain":[
                  {"fixture":3,"stats":[{"identifier":"minutes","points":1,"points_modification":0,"value":45}]},
                  {"fixture":3,"stats":[{"identifier":"minutes","points":1,"points_modification":0,"value":45}]}
                ]}]}
                """, "duplicate fixture");

        assertInvalidHistory("""
                {"history":[
                  {"element":1,"fixture":3,"round":1,"opponent_team":2,"was_home":true,"minutes":0,"total_points":0},
                  {"element":1,"fixture":3,"round":1,"opponent_team":2,"was_home":true,"minutes":0,"total_points":0}
                ]}
                """, "duplicate fixture");
        assertInvalidHistory("""
                {"history":[{"element":2,"fixture":3,"round":1,"opponent_team":2,
                  "was_home":true,"minutes":0,"total_points":0}]}
                """, "does not match requested");
    }

    private static void assertInvalidLive(String payload, String message) {
        assertThatThrownBy(() -> FplSourceContractParser.parseLiveEvent(json(payload)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
    }

    private static void assertInvalidHistory(String payload, String message) {
        assertThatThrownBy(() -> parseHistory(1, json(payload)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
    }

    private static Set<Integer> fixtureStatParticipants(int fixtureId) throws IOException {
        try (InputStream input = resource("fixtures.json")) {
            JsonNode fixtures = new ObjectMapper().readTree(input);
            JsonNode fixture = null;
            for (JsonNode candidate : fixtures) {
                if (candidate.path("id").asInt() == fixtureId) {
                    fixture = candidate;
                    break;
                }
            }
            if (fixture == null) {
                throw new IllegalArgumentException("Missing fixture " + fixtureId);
            }
            Set<Integer> participants = new HashSet<>();
            for (JsonNode stat : fixture.path("stats")) {
                for (String side : List.of("h", "a")) {
                    for (JsonNode value : stat.path(side)) {
                        participants.add(value.path("element").asInt());
                    }
                }
            }
            return participants;
        }
    }

    private static LiveEvent parseLive(String file) throws IOException {
        try (InputStream input = resource(file)) {
            return FplSourceContractParser.parseLiveEvent(input);
        }
    }

    private static List<HistoryFixtureEvidence> parseHistory(int playerId, String file) throws IOException {
        try (InputStream input = resource(file)) {
            return parseHistory(playerId, input);
        }
    }

    private static List<HistoryFixtureEvidence> parseHistory(int playerId, InputStream input) {
        return FplSourceContractParser.parseElementHistory(playerId, input);
    }

    private static HistoryFixtureEvidence history(int playerId, String file, int fixtureId) throws IOException {
        return parseHistory(playerId, file).stream().filter(row -> row.fixtureId() == fixtureId)
                .findFirst().orElseThrow();
    }

    private static InputStream resource(String file) {
        InputStream input = FplSourceContractParserTest.class.getResourceAsStream(FIXTURES + file);
        if (input == null) {
            throw new IllegalArgumentException("Missing test resource " + file);
        }
        return input;
    }

    private static InputStream json(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
