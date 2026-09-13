package fi.karivanttinen.fplassistant.sync.source;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Test-only, deliberately partial parser for the captured source-contract evidence. */
final class FplSourceContractParser {

    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private FplSourceContractParser() {
    }

    static LiveEvent parseLiveEvent(InputStream input) {
        JsonNode root = read(input);
        JsonNode elements = requiredArray(root, "elements", "live event");
        Map<Integer, List<LiveFixtureEvidence>> byPlayer = new HashMap<>();
        for (JsonNode element : elements) {
            int playerId = positiveInt(element, "id", "live element");
            if (byPlayer.containsKey(playerId)) {
                throw invalid("duplicate live element id " + playerId);
            }
            JsonNode explanations = requiredArray(element, "explain", "live element " + playerId);
            List<LiveFixtureEvidence> evidence = new ArrayList<>();
            Set<Integer> fixtureIds = new HashSet<>();
            for (JsonNode explanation : explanations) {
                int fixtureId = positiveInt(explanation, "fixture", "live element " + playerId);
                if (!fixtureIds.add(fixtureId)) {
                    throw invalid("duplicate fixture " + fixtureId + " for live element " + playerId);
                }
                JsonNode stats = requiredArray(explanation, "stats",
                        "live element " + playerId + " fixture " + fixtureId);
                Integer minutes = null;
                Set<String> identifiers = new HashSet<>();
                List<LiveStatEvidence> rawStats = new ArrayList<>();
                for (JsonNode stat : stats) {
                    String identifier = requiredText(stat, "identifier", "live explanation stat");
                    if (!identifiers.add(identifier)) {
                        throw invalid("duplicate " + identifier + " stat for live element " + playerId
                                + " fixture " + fixtureId);
                    }
                    int value = exactInt(stat, "value", identifier + " value");
                    int awarded = exactInt(stat, "points", identifier + " points");
                    int modification = exactInt(stat, "points_modification",
                            identifier + " points modification");
                    rawStats.add(new LiveStatEvidence(identifier, value, awarded, modification));
                    if (identifier.equals("minutes")) {
                        if (value < 0 || value > 130) {
                            throw invalid("minutes out of range for live element " + playerId);
                        }
                        minutes = value;
                    }
                }
                if (minutes == null) {
                    throw invalid("missing minutes for live element " + playerId + " fixture " + fixtureId);
                }
                evidence.add(new LiveFixtureEvidence(playerId, fixtureId, minutes, List.copyOf(rawStats)));
            }
            byPlayer.put(playerId, List.copyOf(evidence));
        }
        return new LiveEvent(Map.copyOf(byPlayer));
    }

    static List<HistoryFixtureEvidence> parseElementHistory(int playerId, InputStream input) {
        if (playerId <= 0) {
            throw invalid("player id must be positive");
        }
        JsonNode history = requiredArray(read(input), "history", "element summary " + playerId);
        List<HistoryFixtureEvidence> rows = new ArrayList<>();
        Set<Integer> fixtureIds = new HashSet<>();
        for (JsonNode row : history) {
            String context = "element " + playerId + " history row";
            int rowPlayerId = positiveInt(row, "element", context);
            if (rowPlayerId != playerId) {
                throw invalid("history element " + rowPlayerId + " does not match requested element " + playerId);
            }
            int fixtureId = positiveInt(row, "fixture", context);
            if (!fixtureIds.add(fixtureId)) {
                throw invalid("duplicate fixture " + fixtureId + " in element " + playerId + " history");
            }
            int gameweek = positiveInt(row, "round", context);
            int opponent = positiveInt(row, "opponent_team", context);
            boolean wasHome = requiredBoolean(row, "was_home", context);
            int minutes = exactInt(row, "minutes", context);
            if (minutes < 0 || minutes > 130) {
                throw invalid("minutes out of range in " + context);
            }
            int totalPoints = exactInt(row, "total_points", context);
            Optional<BigDecimal> expectedGoals = optionalDecimal(row, "expected_goals", context);
            Optional<Integer> defensiveContribution = optionalInt(row, "defensive_contribution", context);
            rows.add(new HistoryFixtureEvidence(playerId, fixtureId, gameweek, opponent, wasHome,
                    minutes, totalPoints, expectedGoals, defensiveContribution));
        }
        return List.copyOf(rows);
    }

    static int reconstructPlayerTeam(HistoryFixtureEvidence history, FixtureSides fixture) {
        if (fixture.fixtureId() <= 0 || fixture.homeTeamId() <= 0 || fixture.awayTeamId() <= 0
                || fixture.homeTeamId() == fixture.awayTeamId()) {
            throw invalid("invalid fixture sides for fixture " + fixture.fixtureId());
        }
        if (history.fixtureId() != fixture.fixtureId()) {
            throw invalid("history fixture does not match fixture " + fixture.fixtureId());
        }
        int expectedOpponent = history.wasHome() ? fixture.awayTeamId() : fixture.homeTeamId();
        if (history.opponentTeamId() != expectedOpponent) {
            throw invalid("history opponent contradicts fixture " + fixture.fixtureId());
        }
        return history.wasHome() ? fixture.homeTeamId() : fixture.awayTeamId();
    }

    private static JsonNode read(InputStream input) {
        if (input == null) {
            throw invalid("missing JSON input");
        }
        try {
            JsonNode root = JSON.readTree(input);
            if (root == null || root.isNull() || root.isMissingNode()) {
                throw invalid("empty or null JSON root");
            }
            if (!root.isContainerNode()) {
                throw invalid("non-container JSON root");
            }
            return root;
        } catch (IOException e) {
            throw invalid("malformed JSON", e);
        }
    }

    private static JsonNode requiredArray(JsonNode node, String field, String context) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw invalid("missing or non-array " + field + " in " + context);
        }
        return value;
    }

    private static int positiveInt(JsonNode node, String field, String context) {
        int value = exactInt(node, field, context);
        if (value <= 0) {
            throw invalid(field + " must be positive in " + context);
        }
        return value;
    }

    private static int exactInt(JsonNode node, String field, String context) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid("missing or non-integer " + field + " in " + context);
        }
        return value.intValue();
    }

    private static String requiredText(JsonNode node, String field, String context) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid("missing or non-text " + field + " in " + context);
        }
        return value.textValue();
    }

    private static boolean requiredBoolean(JsonNode node, String field, String context) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw invalid("missing or non-boolean " + field + " in " + context);
        }
        return value.booleanValue();
    }

    private static Optional<Integer> optionalInt(JsonNode node, String field, String context) {
        if (!node.has(field) || node.get(field).isNull()) {
            return Optional.empty();
        }
        return Optional.of(exactInt(node, field, context));
    }

    private static Optional<BigDecimal> optionalDecimal(JsonNode node, String field, String context) {
        if (!node.has(field) || node.get(field).isNull()) {
            return Optional.empty();
        }
        JsonNode value = node.get(field);
        try {
            if (value.isNumber()) {
                return Optional.of(value.decimalValue());
            }
            if (value.isTextual()) {
                return Optional.of(new BigDecimal(value.textValue()));
            }
        } catch (NumberFormatException ignored) {
            // Converted into the source-contract error below.
        }
        throw invalid("non-decimal " + field + " in " + context);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid FPL source payload: " + message);
    }

    private static IllegalArgumentException invalid(String message, Exception cause) {
        return new IllegalArgumentException("Invalid FPL source payload: " + message, cause);
    }

    record LiveEvent(Map<Integer, List<LiveFixtureEvidence>> byPlayer) {
        List<LiveFixtureEvidence> fixture(int fixtureId) {
            return byPlayer.values().stream().flatMap(List::stream)
                    .filter(row -> row.fixtureId() == fixtureId).toList();
        }
    }

    record LiveFixtureEvidence(int playerId, int fixtureId, int minutes, List<LiveStatEvidence> rawStats) {
        boolean appeared() {
            return minutes > 0;
        }

        LiveStatEvidence stat(String identifier) {
            return rawStats.stream().filter(stat -> stat.identifier().equals(identifier))
                    .findFirst().orElseThrow();
        }
    }

    record LiveStatEvidence(String identifier, int value, int points, int pointsModification) {
    }

    record HistoryFixtureEvidence(int playerId, int fixtureId, int gameweek, int opponentTeamId,
                                  boolean wasHome, int minutes, int totalPoints,
                                  Optional<BigDecimal> expectedGoals,
                                  Optional<Integer> defensiveContribution) {
    }

    record FixtureSides(int fixtureId, int homeTeamId, int awayTeamId) {
    }
}
