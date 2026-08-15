package fi.karivanttinen.fplassistant.historical;

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
 * <p>No real captured response was available to verify this against — this project has no
 * football-data.org API key and cannot register for one autonomously. Built from the API's
 * public documentation only. <b>Treat this parser as unverified against the live API until
 * Task 7's live check runs</b>; see the Phase 3 spec/STATUS.md for the open item.
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
