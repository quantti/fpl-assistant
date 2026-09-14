package dev.quantti.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import dev.quantti.fplassistant.sync.CatalogueScheduleParser.ReadInterval;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.Season;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.SourceDocument;
import dev.quantti.fplassistant.sync.HistoryParser.CanonicalFixture;
import dev.quantti.fplassistant.sync.HistoryParser.Component;
import dev.quantti.fplassistant.sync.HistoryParser.HistoryBatch;
import dev.quantti.fplassistant.sync.HistoryParser.HistoryRow;

class HistoryParserTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RESOURCES = "/owned-sync-source-fixtures/";
    private static final Season SEASON = new Season(27, "2026-27",
            LocalDate.parse("2026-08-21"), LocalDate.parse("2027-05-23"));
    private static final ReadInterval READ = new ReadInterval(
            Instant.parse("2026-09-13T17:46:18Z"), Instant.parse("2026-09-13T17:46:19Z"));

    @Test
    void capturedHistoryMapsEveryNamedPfsFieldAndDefCon() throws IOException {
        HistoryRow row = parseCaptured(557, "element-summary-557.json").rows().getFirst();

        assertThat(row.seasonId()).isEqualTo(27);
        assertThat(row.seasonCode()).isEqualTo("2026-27");
        assertThat(row.fixtureId()).isEqualTo(1);
        assertThat(row.playerId()).isEqualTo(557);
        assertThat(row.gameweek()).isEqualTo(1);
        assertThat(row.playerTeamId()).isEqualTo(1);
        assertThat(row.opponentTeamId()).isEqualTo(7);
        assertThat(row.wasHome()).isTrue();
        assertThat(row.kickoffTime()).isEqualTo(Instant.parse("2026-08-21T19:00:00Z"));
        assertThat(row.minutes()).isEqualTo((short) 75);
        assertThat(row.totalPoints()).isEqualTo((short) 6);
        assertThat(row.bonus()).isZero();
        assertThat(row.bps()).isEqualTo((short) 30);
        assertThat(row.goalsScored()).isZero();
        assertThat(row.assists()).isEqualTo((short) 1);
        assertThat(row.expectedGoals()).isEqualByComparingTo("0.19");
        assertThat(row.expectedAssists()).isEqualByComparingTo("0.14");
        assertThat(row.expectedGoalInvolvements()).isEqualByComparingTo("0.33");
        assertThat(row.cleanSheets()).isEqualTo((short) 1);
        assertThat(row.goalsConceded()).isZero();
        assertThat(row.ownGoals()).isZero();
        assertThat(row.penaltiesSaved()).isZero();
        assertThat(row.penaltiesMissed()).isZero();
        assertThat(row.saves()).isZero();
        assertThat(row.expectedGoalsConceded()).isEqualByComparingTo("0.06");
        assertThat(row.yellowCards()).isZero();
        assertThat(row.redCards()).isZero();
        assertThat(row.influence()).isEqualByComparingTo("17.0");
        assertThat(row.creativity()).isEqualByComparingTo("39.8");
        assertThat(row.threat()).isEqualByComparingTo("30.0");
        assertThat(row.ictIndex()).isEqualByComparingTo("8.7");
        assertThat(row.value()).isEqualTo(65);
        assertThat(row.selected()).isEqualTo(2_247_384);
        assertThat(row.transfersIn()).isZero();
        assertThat(row.transfersOut()).isZero();
        assertThat(row.starts()).isEqualTo((short) 1);
        assertThat(row.clearancesBlocksInterceptions()).isEqualTo((short) 2);
        assertThat(row.recoveries()).isEqualTo((short) 3);
        assertThat(row.tackles()).isEqualTo((short) 1);
        assertThat(row.defensiveContribution()).isEqualTo((short) 6);
        assertThat(row.presentComponents()).containsExactlyInAnyOrder(Component.values());
        assertThat(row.presenceMask()).isEqualTo(Component.allMask());
    }

    @Test
    void capturedZeroRemainsPresentAndMissingHistoryRowIsNotSynthesized() throws IOException {
        HistoryBatch zeroBatch = parseCaptured(2, "element-summary-2.json");
        HistoryRow zero = zeroBatch.rows().stream().filter(row -> row.fixtureId() == 29)
                .findFirst().orElseThrow();
        HistoryBatch departed = parseCaptured(658, "element-summary-658.json");

        assertThat(zero.minutes()).isZero();
        assertThat(zero.expectedGoals()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(zero.defensiveContribution()).isZero();
        assertThat(zero.has(Component.EXPECTED_GOALS)).isTrue();
        assertThat(zero.has(Component.DEFENSIVE_CONTRIBUTION)).isTrue();
        assertThat(departed.rows()).extracting(HistoryRow::fixtureId).containsExactly(38);
        assertThat(departed.rows()).noneMatch(row -> row.fixtureId() == 29);
    }

    @Test
    void historicalClubComesFromEachCanonicalFixtureAndDgwLegsStayDistinct() throws Exception {
        ObjectNode home = completeRow(7, 101, 8, 20, true, "2026-10-20T19:00:00Z");
        ObjectNode away = completeRow(7, 109, 8, 30, false, "2026-10-24T19:00:00Z");
        away.put("minutes", 45).put("total_points", 1);
        HistoryBatch batch = parse(7, array(home, away), List.of(
                fixture(101, 8, 10, 20, "2026-10-20T19:00:00Z"),
                fixture(109, 8, 30, 10, "2026-10-24T19:00:00Z")));
        int currentBootstrapClub = 40;

        assertThat(batch.rows()).extracting(HistoryRow::fixtureId).containsExactly(101, 109);
        assertThat(batch.rows()).extracting(HistoryRow::playerTeamId).containsExactly(10, 10)
                .doesNotContain(currentBootstrapClub);
        assertThat(batch.rows()).extracting(HistoryRow::minutes).containsExactly((short) 90, (short) 45);
    }

    @Test
    void correctionCanRemoveOptionalComponentsWithoutRetainingTheirOldPresence() throws Exception {
        ObjectNode before = completeRow(7, 101, 8, 20, true, "2026-10-20T19:00:00Z");
        HistoryRow known = parse(7, array(before), List.of(
                fixture(101, 8, 10, 20, "2026-10-20T19:00:00Z"))).rows().getFirst();
        ObjectNode correction = before.deepCopy();
        correction.remove(List.of("expected_goals", "influence", "defensive_contribution", "recoveries"));
        correction.put("total_points", 5);
        HistoryBatch correctedBatch = parse(7, array(correction), List.of(
                fixture(101, 8, 10, 20, "2026-10-20T19:00:00Z")));
        HistoryRow corrected = correctedBatch.rows().getFirst();

        assertThat(known.has(Component.EXPECTED_GOALS)).isTrue();
        assertThat(known.defensiveContribution()).isZero();
        assertThat(corrected.totalPoints()).isEqualTo((short) 5);
        assertThat(corrected.expectedGoals()).isNull();
        assertThat(corrected.influence()).isNull();
        assertThat(corrected.defensiveContribution()).isNull();
        assertThat(corrected.recoveries()).isNull();
        assertThat(corrected.has(Component.EXPECTED_GOALS)).isFalse();
        assertThat(corrected.has(Component.DEFENSIVE_CONTRIBUTION)).isFalse();
        assertThat(correctedBatch.evidence(12).getFirst().has(Component.EXPECTED_GOALS)).isFalse();
        assertThat(correctedBatch.evidence(12).getFirst().generation()).isEqualTo(12);
        assertThatThrownBy(() -> corrected.presentComponents().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void malformedMandatoryDuplicateAndContradictoryIdentityFailTheWholeResponse() throws Exception {
        ObjectNode row = completeRow(7, 101, 8, 20, true, "2026-10-20T19:00:00Z");
        ObjectNode missing = row.deepCopy();
        missing.remove("selected");
        assertInvalid(7, array(missing), "missing or non-integer selected");

        String duplicateJson = "{\"history\":[" + row + "," + row + "]}";
        assertThatThrownBy(() -> parse(7, duplicateJson, List.of(
                fixture(101, 8, 10, 20, "2026-10-20T19:00:00Z"))))
                .hasMessageContaining("duplicate fixture 101");

        ObjectNode wrongElement = row.deepCopy().put("element", 8);
        assertInvalid(7, array(wrongElement), "does not match requested element");
        ObjectNode wrongOpponent = row.deepCopy().put("opponent_team", 30);
        assertInvalid(7, array(wrongOpponent), "opponent contradicts");
    }

    @Test
    void exactSchemaBoundsAndNegativeRulesAreEnforced() throws Exception {
        ObjectNode row = completeRow(7, 101, 8, 20, true, "2026-10-20T19:00:00Z");
        assertFieldInvalid(row, "minutes", -1, "must be non-negative");
        assertFieldInvalid(row, "bonus", 32768, "exceeds SMALLINT");
        assertFieldInvalid(row, "selected", -1, "must be non-negative");
        assertFieldInvalid(row, "starts", 2, "starts must be 0 or 1");
        assertFieldInvalid(row, "defensive_contribution", -1, "must be non-negative");

        ObjectNode fractional = row.deepCopy();
        fractional.put("minutes", 1.5);
        assertInvalid(7, array(fractional), "non-integer minutes");
        ObjectNode expectedOverflow = row.deepCopy().put("expected_goals", "1000.00");
        assertInvalid(7, array(expectedOverflow), "NUMERIC(5,2)");
        ObjectNode ictScale = row.deepCopy().put("ict_index", "1.25");
        assertInvalid(7, array(ictScale), "NUMERIC(6,1)");
    }

    @Test
    void anotherSeasonHistoryAndFixtureAreNeverAcceptedAsFallback() throws Exception {
        ObjectNode row = completeRow(7, 999, 1, 20, true, "2025-08-20T19:00:00Z");
        assertThatThrownBy(() -> parse(7, array(row), List.of()))
                .hasMessageContaining("outside configured season 2026-27");
        CanonicalFixture oldFixture = new CanonicalFixture(26, "2025-26", 999, 1, 10, 20,
                Instant.parse("2025-08-20T19:00:00Z"));
        assertThatThrownBy(() -> parse(7, array(row), List.of(oldFixture)))
                .hasMessageContaining("canonical fixture belongs to another season");

        String historicalTotalsOnly = "{\"history\":[],\"history_past\":[{\"season_name\":\"2025/26\","
                + "\"minutes\":2000,\"total_points\":100}]}";
        assertThat(parse(7, historicalTotalsOnly, List.of()).rows()).isEmpty();
    }

    @Test
    void duplicateJsonPropertiesAndTrailingTokensAreRejected() {
        String body = "{\"history\":[],\"history\":[]}";
        assertThatThrownBy(() -> parse(7, body, List.of()))
                .hasMessageContaining("malformed element-summary JSON");
        assertThatThrownBy(() -> parse(7, "{\"history\":[]} []", List.of()))
                .hasMessageContaining("malformed element-summary JSON");
    }

    private static HistoryBatch parseCaptured(int playerId, String file) throws IOException {
        byte[] body;
        try (var input = HistoryParserTest.class.getResourceAsStream(RESOURCES + file)) {
            if (input == null) throw new IllegalArgumentException("missing " + file);
            body = input.readAllBytes();
        }
        return HistoryParser.parse(SEASON, playerId, new SourceDocument(body, READ), capturedFixtures());
    }

    private static List<CanonicalFixture> capturedFixtures() throws IOException {
        try (var input = HistoryParserTest.class.getResourceAsStream(RESOURCES + "fixtures.json")) {
            List<CanonicalFixture> result = new ArrayList<>();
            for (JsonNode fixture : JSON.readTree(input)) {
                JsonNode event = fixture.get("event");
                JsonNode kickoff = fixture.get("kickoff_time");
                result.add(new CanonicalFixture(SEASON.id(), SEASON.code(), fixture.get("id").intValue(),
                        event == null || event.isNull() ? null : event.intValue(),
                        fixture.get("team_h").intValue(), fixture.get("team_a").intValue(),
                        kickoff == null || kickoff.isNull() ? null : Instant.parse(kickoff.textValue())));
            }
            return result;
        }
    }

    private static HistoryBatch parse(int playerId, String body, List<CanonicalFixture> fixtures) {
        return HistoryParser.parse(SEASON, playerId,
                new SourceDocument(body.getBytes(StandardCharsets.UTF_8), READ), fixtures);
    }

    private static void assertFieldInvalid(ObjectNode original, String field, int value, String message) {
        ObjectNode changed = original.deepCopy().put(field, value);
        assertInvalid(7, array(changed), message);
    }

    private static void assertInvalid(int playerId, String body, String message) {
        assertThatThrownBy(() -> parse(playerId, body, List.of(
                fixture(101, 8, 10, 20, "2026-10-20T19:00:00Z"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
    }

    private static CanonicalFixture fixture(int id, int gameweek, int home, int away, String kickoff) {
        return new CanonicalFixture(SEASON.id(), SEASON.code(), id, gameweek, home, away,
                Instant.parse(kickoff));
    }

    private static String array(ObjectNode... rows) {
        var root = JSON.createObjectNode();
        var history = root.putArray("history");
        for (ObjectNode row : rows) history.add(row);
        return root.toString();
    }

    private static ObjectNode completeRow(int player, int fixture, int gameweek, int opponent,
                                          boolean wasHome, String kickoff) {
        ObjectNode row = JSON.createObjectNode();
        row.put("element", player).put("fixture", fixture).put("round", gameweek)
                .put("opponent_team", opponent).put("was_home", wasHome).put("kickoff_time", kickoff)
                .put("minutes", 90).put("total_points", 2).put("bonus", 0).put("bps", 10)
                .put("goals_scored", 0).put("assists", 0).put("clean_sheets", 0)
                .put("goals_conceded", 0).put("own_goals", 0).put("penalties_saved", 0)
                .put("penalties_missed", 0).put("saves", 0).put("yellow_cards", 0)
                .put("red_cards", 0).put("starts", 1).put("value", 50).put("selected", 100)
                .put("transfers_in", 1).put("transfers_out", 2)
                .put("expected_goals", "0.00").put("expected_assists", "0.00")
                .put("expected_goal_involvements", "0.00").put("expected_goals_conceded", "0.00")
                .put("influence", "0.0").put("creativity", "0.0").put("threat", "0.0")
                .put("ict_index", "0.0").put("clearances_blocks_interceptions", 0)
                .put("recoveries", 0).put("tackles", 0).put("defensive_contribution", 0);
        return row;
    }
}
