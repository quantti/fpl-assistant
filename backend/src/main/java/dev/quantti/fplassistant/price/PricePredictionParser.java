package dev.quantti.fplassistant.price;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.quantti.fplassistant.price.PricePredictionSnapshot.Change;
import dev.quantti.fplassistant.price.PricePredictionSnapshot.Forecast;
import dev.quantti.fplassistant.price.PricePredictionSnapshot.Player;
import dev.quantti.fplassistant.price.PricePredictionSnapshot.RecentChanges;
import dev.quantti.fplassistant.price.PricePredictionSnapshot.Signal;
import dev.quantti.fplassistant.price.PricePredictionSnapshot.State;
import dev.quantti.fplassistant.price.PricePredictionSnapshot.Team;

public final class PricePredictionParser {

    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Pattern SEASON = Pattern.compile("(\\d{4})-(\\d{2})");
    private static final Pattern SOURCE_SEASON = Pattern.compile(
            "https://fantasy\\.premierleague\\.com/gcs/plfpl-prod-static-content/"
                    + "plfpl-production/(\\d{4})_(\\d{2})/");
    private static final Pattern DECIMAL = Pattern.compile("[+-]?\\d{1,8}(\\.\\d{1,6})?");
    private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
    private static final int MAX_PLAYERS = 5000;
    private static final int MAX_CHANGES = 10000;

    private PricePredictionParser() { }

    public static PricePredictionSnapshot parse(byte[] body, String configuredSeason,
                                                Instant fetchedAt) {
        Objects.requireNonNull(fetchedAt, "fetchedAt");
        JsonNode root = read(body);
        if (!root.isObject()) throw invalid("bootstrap root must be an object");
        JsonNode settings = root.path("game_config").path("settings");
        validateSeason(configuredSeason, settings, root.path("events"));
        Map<Integer, Team> teams = teams(root.path("teams"));
        Map<Integer, Player> players = players(root.path("elements"), teams, fetchedAt);
        Instant updatedAt = instant(root.path("game_config").path("status")
                .path("price_change_last_updated"));
        if (updatedAt != null && updatedAt.isAfter(fetchedAt)) updatedAt = null;
        JsonNode deadlines = settings.path("price_change_deadlines");
        Instant target = deadlines.isArray() && !deadlines.isEmpty()
                ? instant(deadlines.get(0)) : null;
        return new PricePredictionSnapshot(configuredSeason, fetchedAt, updatedAt, target,
                teams, players);
    }

    public static RecentChanges parseRecentChanges(byte[] body, PricePredictionSnapshot identity,
                                                   Instant fetchedAt) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(fetchedAt, "fetchedAt");
        Duration separation = Duration.between(identity.fetchedAt(), fetchedAt);
        if (separation.isNegative() || separation.compareTo(Duration.ofMinutes(10)) > 0) {
            throw invalid("recent changes require a companion bootstrap read within ten minutes");
        }
        JsonNode root = read(body);
        if (!root.isArray() || root.size() > MAX_CHANGES) {
            throw invalid("recent changes must be a bounded array");
        }
        int startYear = seasonStartYear(identity.season());
        Instant seasonStart = LocalDate.of(startYear, 7, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant seasonEnd = LocalDate.of(startYear + 1, 7, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
        var changes = new ArrayList<Change>();
        Set<ChangeKey> keys = new HashSet<>();
        for (JsonNode row : root) {
            int elementId = positive(row, "element");
            int teamId = positive(row, "team");
            int gameweek = positive(row, "event");
            int before = positive(row, "cost_before");
            int after = positive(row, "cost_after");
            Instant changedAt = instant(row.path("changed_at"));
            if (!identity.players().containsKey(elementId) || !identity.teams().containsKey(teamId)
                    || gameweek > 38 || before == after || changedAt == null
                    || changedAt.isBefore(seasonStart) || !changedAt.isBefore(seasonEnd)
                    || changedAt.isAfter(fetchedAt)) {
                throw invalid("recent change has inconsistent identity, date or price evidence");
            }
            if (!keys.add(new ChangeKey(elementId, changedAt))) {
                throw invalid("duplicate recent price change");
            }
            changes.add(new Change(elementId, teamId, gameweek, changedAt, before, after));
        }
        return new RecentChanges(identity.season(), fetchedAt, identity.fetchedAt(), changes);
    }

    private static void validateSeason(String configuredSeason, JsonNode settings, JsonNode events) {
        int year = seasonStartYear(configuredSeason);
        var marker = SOURCE_SEASON.matcher(settings.path("static_content_url").asText(""));
        if (!marker.matches() || !marker.group(1).equals(Integer.toString(year))
                || !marker.group(2).equals(configuredSeason.substring(5))) {
            throw invalid("official source season does not match configured playing season");
        }
        if (!events.isArray() || events.size() != 38) {
            throw invalid("season evidence requires 38 events");
        }
        Instant[] deadlines = new Instant[38];
        for (JsonNode event : events) {
            int id = positive(event, "id");
            Instant deadline = instant(event.path("deadline_time"));
            if (id > 38 || deadlines[id - 1] != null || deadline == null) {
                throw invalid("invalid season event identity or deadline");
            }
            deadlines[id - 1] = deadline;
        }
        LocalDate first = deadlines[0].atOffset(ZoneOffset.UTC).toLocalDate();
        LocalDate last = deadlines[37].atOffset(ZoneOffset.UTC).toLocalDate();
        if (first.getYear() != year || first.getMonthValue() < 7
                || last.getYear() != year + 1 || last.getMonthValue() > 6) {
            throw invalid("event calendar contradicts source season");
        }
        for (int index = 1; index < deadlines.length; index++) {
            if (!deadlines[index].isAfter(deadlines[index - 1])) {
                throw invalid("season event deadlines are not strictly increasing");
            }
        }
    }

    private static int seasonStartYear(String season) {
        var matcher = SEASON.matcher(Objects.requireNonNull(season, "configuredSeason"));
        if (!matcher.matches()) throw invalid("configured season must be YYYY-YY");
        int year = Integer.parseInt(matcher.group(1));
        if (year < 2000 || year > 9998
                || Integer.parseInt(matcher.group(2)) != (year + 1) % 100) {
            throw invalid("configured season suffix must name the following year");
        }
        return year;
    }

    private static Map<Integer, Team> teams(JsonNode rows) {
        if (!rows.isArray() || rows.size() != 20) throw invalid("expected 20 source teams");
        Map<Integer, Team> teams = new HashMap<>();
        for (JsonNode row : rows) {
            int id = positive(row, "id");
            Team team = new Team(id, text(row, "name"), text(row, "short_name"));
            if (teams.put(id, team) != null) throw invalid("duplicate team identity");
        }
        return teams;
    }

    private static Map<Integer, Player> players(JsonNode rows, Map<Integer, Team> teams,
                                                 Instant fetchedAt) {
        if (!rows.isArray() || rows.isEmpty() || rows.size() > MAX_PLAYERS) {
            throw invalid("players must be a nonempty bounded array");
        }
        Map<Integer, Player> players = new HashMap<>();
        for (JsonNode row : rows) {
            int id = positive(row, "id");
            int team = positive(row, "team");
            int position = positive(row, "element_type");
            if (!teams.containsKey(team) || position > 4) {
                throw invalid("invalid required player club or position");
            }
            Player player = new Player(id, team, position, text(row, "web_name"),
                    text(row, "first_name") + " " + text(row, "second_name"),
                    positive(row, "now_cost"), forecast(row, fetchedAt));
            if (players.put(id, player) != null) throw invalid("duplicate player identity");
        }
        return players;
    }

    private static Forecast forecast(JsonNode row, Instant fetchedAt) {
        BigDecimal progress = decimal(row.path("price_change_percent"));
        JsonNode lockNode = row.path("price_change_locked_until");
        if (lockNode.isMissingNode()) return emptyForecast(State.UNAVAILABLE, progress, null);
        if (!lockNode.isNull()) {
            Instant lock = instant(lockNode);
            return emptyForecast(lock != null && lock.isAfter(fetchedAt)
                    ? State.LOCKED : State.UNAVAILABLE, progress, lock);
        }
        JsonNode calibration = row.path("price_change_calibrating");
        if (!calibration.isBoolean()) return emptyForecast(State.UNAVAILABLE, progress, null);
        if (calibration.booleanValue()) return emptyForecast(State.CALIBRATING, progress, null);
        JsonNode projections = row.path("price_change_projections");
        if (!projections.isArray() || projections.isEmpty()) {
            return emptyForecast(State.UNAVAILABLE, progress, null);
        }
        JsonNode projection = projections.get(0);
        Integer offset = integer(projection.path("offset"));
        Integer likelihood = integer(projection.path("likelihood"));
        BigDecimal projected = decimal(projection.path("projected_percent"));
        if (offset == null || likelihood == null || projected == null || progress == null) {
            return emptyForecast(State.UNAVAILABLE, progress, null);
        }
        Signal signal = switch (likelihood) {
            case 5 -> Signal.VERY_LIKELY_RISE;
            case 4 -> Signal.LIKELY_RISE;
            case -4 -> Signal.LIKELY_DROP;
            case -5 -> Signal.VERY_LIKELY_DROP;
            case -3, -2, -1, 0, 1, 2, 3 -> Signal.UNLIKELY_TO_CHANGE;
            default -> null;
        };
        return new Forecast(offset != 0 || signal == null ? State.UNSUPPORTED : State.READY,
                progress, projected, likelihood, offset == 0 ? signal : null, null);
    }

    private static Forecast emptyForecast(State state, BigDecimal progress, Instant lock) {
        return new Forecast(state, progress, null, null, null, lock);
    }

    private static JsonNode read(byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) {
            throw invalid("source body is empty or exceeds limit");
        }
        try {
            return JSON.readTree(body);
        } catch (IOException exception) {
            throw invalid("malformed source JSON");
        }
    }

    private static int positive(JsonNode row, String field) {
        Integer value = integer(row.path(field));
        if (value == null || value <= 0) throw invalid("invalid required integer: " + field);
        return value;
    }

    private static Integer integer(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToInt() ? node.intValue() : null;
    }

    private static String text(JsonNode row, String field) {
        JsonNode node = row.path(field);
        if (!node.isTextual() || node.textValue().isBlank() || node.textValue().length() > 256) {
            throw invalid("invalid required text: " + field);
        }
        return node.textValue();
    }

    private static BigDecimal decimal(JsonNode node) {
        if (!node.isTextual() && !node.isNumber()) return null;
        String value = node.asText();
        return DECIMAL.matcher(value).matches() ? new BigDecimal(value) : null;
    }

    private static Instant instant(JsonNode node) {
        if (!node.isTextual()) return null;
        try {
            return Instant.parse(node.textValue());
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private record ChangeKey(int elementId, Instant changedAt) { }
}
