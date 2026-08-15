package dev.quantti.fplassistant.historical;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * Parses a football-data.org {@code GET /v4/competitions/PL/matches} response body into
 * {@link FootballDataMatch}es. Response shape per the API's documented list-resource pattern:
 * top-level {@code filters}/{@code resultSet}/{@code competition}/{@code matches}, each match
 * carrying {@code status}, {@code matchday}, {@code utcDate}, {@code homeTeam.name},
 * {@code awayTeam.name}, {@code score.fullTime.home}/{@code .away}.
 *
 * <p>Verified 2026-08-15 against a real captured {@code GET /v4/competitions/PL/matches}
 * response body (checked into {@code src/test/resources/football-data-fixtures/pl-matches-2026-27.json})
 * — the parser shape below matches the live API, including the {@code score.winner}/{@code duration}/
 * {@code halfTime} fields this parser ignores. The strict integer handling and fail-loud
 * behaviour are pinned by unit tests.
 */
public final class FootballDataResponseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FootballDataResponseParser() {
    }

    public static List<FootballDataMatch> parse(String responseBody) {
        JsonNode root;
        try {
            root = MAPPER.readTree(responseBody);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("football-data response is not valid JSON", e);
        }
        JsonNode matches = root.get("matches");
        if (matches == null || !matches.isArray()) {
            throw new IllegalStateException("football-data response has no 'matches' array");
        }
        List<FootballDataMatch> result = new ArrayList<>();
        for (JsonNode m : matches) {
            result.add(parseMatch(m));
        }
        return result;
    }

    private static FootballDataMatch parseMatch(JsonNode m) {
        String status = textOrNull(m, "status");
        String homeTeamName = textOrNull(m, "homeTeam", "name");
        String awayTeamName = textOrNull(m, "awayTeam", "name");
        if (homeTeamName == null || awayTeamName == null) {
            throw new IllegalStateException("football-data match is missing homeTeam.name or awayTeam.name: " + m);
        }
        return new FootballDataMatch(
                status,
                homeTeamName,
                awayTeamName,
                intOrNull(m, "score", "fullTime", "home"),
                intOrNull(m, "score", "fullTime", "away"),
                intOrNull(m, "matchday"),
                textOrNull(m, "utcDate"));
    }

    private static String textOrNull(JsonNode node, String... path) {
        JsonNode n = navigate(node, path);
        return (n == null || n.isNull()) ? null : n.asText();
    }

    private static Integer intOrNull(JsonNode node, String... path) {
        JsonNode n = navigate(node, path);
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isIntegralNumber()) {
            return n.intValue();
        }
        if (n.isTextual()) {
            try {
                return Integer.parseInt(n.asText());
            } catch (NumberFormatException e) {
                // Same fail-loud class as VaastavFixtureParser's strict score parsing: a
                // fractional or non-numeric score must surface as an error, never silently
                // truncate to a wrong number that would then be stored as fact.
                throw new IllegalStateException("football-data score is not an integer: '"
                        + n.asText() + "' in " + n);
            }
        }
        throw new IllegalStateException("football-data score is not an integer: " + n);
    }

    private static JsonNode navigate(JsonNode node, String... path) {
        JsonNode current = node;
        for (String segment : path) {
            if (current == null) {
                return null;
            }
            current = current.get(segment);
        }
        return current;
    }
}
