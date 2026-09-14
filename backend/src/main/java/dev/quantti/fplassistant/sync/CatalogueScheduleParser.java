package dev.quantti.fplassistant.sync;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.quantti.fplassistant.schedule.ScheduleSource;

/**
 * Strict, side-effect-free mapper for one bootstrap/catalogue and schedule source batch.
 * It deliberately knows nothing about {@code SeasonResolver}: the writer supplies the composite
 * season identity and calendar that the source has to prove before any rows can be published.
 */
public final class CatalogueScheduleParser {

    private static final ZoneId UK = ZoneId.of("Europe/London");
    private static final Pattern SEASON_CODE = Pattern.compile("\\d{4}-\\d{2}");
    private static final long MAX_BATCH_SPAN_MINUTES = 10;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final List<String> PLAYER_INTEGER_FIELDS = List.of(
            "total_points", "minutes", "goals_scored", "assists", "clean_sheets",
            "goals_conceded", "own_goals", "penalties_saved", "penalties_missed",
            "yellow_cards", "red_cards", "saves", "bonus", "bps");
    private static final Map<String, DecimalShape> PLAYER_DECIMAL_FIELDS = Map.ofEntries(
            Map.entry("expected_goals", new DecimalShape(6, 2)),
            Map.entry("expected_assists", new DecimalShape(6, 2)),
            Map.entry("expected_goal_involvements", new DecimalShape(6, 2)),
            Map.entry("expected_goals_conceded", new DecimalShape(6, 2)),
            Map.entry("influence", new DecimalShape(6, 1)),
            Map.entry("creativity", new DecimalShape(6, 1)),
            Map.entry("threat", new DecimalShape(6, 1)),
            Map.entry("ict_index", new DecimalShape(6, 1)),
            Map.entry("selected_by_percent", new DecimalShape(5, 2)),
            Map.entry("form", new DecimalShape(4, 1)),
            Map.entry("points_per_game", new DecimalShape(4, 1)));

    private CatalogueScheduleParser() {
    }

    public static Snapshot parse(Season season, SourceDocument bootstrapDocument,
                                 SourceDocument fixturesDocument) {
        Objects.requireNonNull(season, "season");
        Objects.requireNonNull(bootstrapDocument, "bootstrapDocument");
        Objects.requireNonNull(fixturesDocument, "fixturesDocument");
        validateBatchWindow(bootstrapDocument.readInterval(), fixturesDocument.readInterval());

        JsonNode bootstrap = read(bootstrapDocument.bytes(), "bootstrap-static");
        if (!bootstrap.isObject()) {
            throw invalid("bootstrap-static root must be an object");
        }
        JsonNode fixturesRoot = read(fixturesDocument.bytes(), "fixtures");
        if (!fixturesRoot.isArray()) {
            throw invalid("fixtures root must be an array");
        }

        List<TeamRow> teams = parseTeams(season, requiredArray(bootstrap, "teams", "bootstrap-static"));
        Map<Integer, TeamRow> teamsById = indexTeams(teams);
        List<PlayerRow> players = parsePlayers(season,
                requiredArray(bootstrap, "elements", "bootstrap-static"), teamsById);
        List<GameweekRow> gameweeks = parseGameweeks(season,
                requiredArray(bootstrap, "events", "bootstrap-static"));
        List<FixtureRow> fixtures = parseFixtures(season, fixturesRoot, teamsById);

        validateSeasonAndLeague(season, gameweeks, fixtures, teamsById.keySet(),
                max(bootstrapDocument.readInterval().completedAt(),
                        fixturesDocument.readInterval().completedAt()));

        LocalDate priceDate = LocalDate.ofInstant(bootstrapDocument.readInterval().completedAt(), UK);
        List<DailyPriceRow> dailyPrices = players.stream()
                .map(player -> dailyPrice(player, teamsById.get(player.teamId()), priceDate,
                        bootstrapDocument.readInterval()))
                .toList();
        return new Snapshot(season, bootstrapDocument.readInterval(), fixturesDocument.readInterval(),
                teams, players, dailyPrices, gameweeks, fixtures);
    }

    private static List<TeamRow> parseTeams(Season season, JsonNode nodes) {
        if (nodes.size() != 20) {
            throw invalid("catalogue must contain exactly 20 teams");
        }
        List<TeamRow> rows = new ArrayList<>(nodes.size());
        Set<Integer> ids = new HashSet<>();
        for (JsonNode node : nodes) {
            requireObject(node, "team");
            int id = positiveInt(node, "id", "team");
            if (!ids.add(id)) {
                throw invalid("duplicate team id " + id);
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            for (String field : List.of("strength", "strength_overall_home", "strength_overall_away",
                    "strength_attack_home", "strength_attack_away", "strength_defence_home",
                    "strength_defence_away")) {
                fields.put(field, nullableInt(node, field, "team " + id));
            }
            rows.add(new TeamRow(season.id(), id, exactInt(node, "code", "team " + id),
                    requiredText(node, "name", 100, "team " + id),
                    requiredText(node, "short_name", 3, "team " + id), fields));
        }
        return List.copyOf(rows);
    }

    private static List<PlayerRow> parsePlayers(Season season, JsonNode nodes,
                                                Map<Integer, TeamRow> teams) {
        if (nodes.isEmpty()) {
            throw invalid("catalogue elements must not be empty");
        }
        List<PlayerRow> rows = new ArrayList<>(nodes.size());
        Set<Integer> ids = new HashSet<>();
        for (JsonNode node : nodes) {
            requireObject(node, "element");
            int id = positiveInt(node, "id", "element");
            if (!ids.add(id)) {
                throw invalid("duplicate element id " + id);
            }
            String context = "element " + id;
            int teamId = positiveInt(node, "team", context);
            TeamRow team = teams.get(teamId);
            if (team == null) {
                throw invalid(context + " references unknown team " + teamId);
            }
            if (exactInt(node, "team_code", context) != team.code()) {
                throw invalid(context + " team_code contradicts team " + teamId);
            }
            int type = exactInt(node, "element_type", context);
            if (type < 1 || type > 4) {
                throw invalid("invalid element_type in " + context);
            }
            int nowCost = positiveInt(node, "now_cost", context);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("first_name", nullableText(node, "first_name", 100, context));
            fields.put("second_name", nullableText(node, "second_name", 100, context));
            String status = requiredText(node, "status", 1, context);
            fields.put("status", status);
            fields.put("news", nullableText(node, "news", Integer.MAX_VALUE, context));
            fields.put("news_added", nullableInstant(node, "news_added", context));
            for (String field : PLAYER_INTEGER_FIELDS) {
                int value = exactInt(node, field, context);
                if (!field.equals("bps") && !field.equals("total_points") && value < 0) {
                    throw invalid(field + " must be non-negative in " + context);
                }
                fields.put(field, value);
            }
            for (Map.Entry<String, DecimalShape> field : PLAYER_DECIMAL_FIELDS.entrySet()) {
                fields.put(field.getKey(), nullableDecimal(node, field.getKey(), field.getValue(), context));
            }
            fields.put("cost_change_event", exactInt(node, "cost_change_event", context));
            fields.put("cost_change_start", exactInt(node, "cost_change_start", context));
            fields.put("penalties_order", nullableSmallInt(node, "penalties_order", context));
            fields.put("direct_fk_order", nullableSmallInt(node, "direct_freekicks_order", context));
            fields.put("corners_order", nullableSmallInt(node,
                    "corners_and_indirect_freekicks_order", context));
            rows.add(new PlayerRow(season.id(), season.code(), id, teamId,
                    requiredText(node, "web_name", 100, context), type, nowCost, fields));
        }
        return List.copyOf(rows);
    }

    private static List<GameweekRow> parseGameweeks(Season season, JsonNode nodes) {
        List<GameweekRow> rows = new ArrayList<>(nodes.size());
        Set<Integer> ids = new HashSet<>();
        for (JsonNode node : nodes) {
            requireObject(node, "event");
            int id = positiveInt(node, "id", "event");
            if (!ids.add(id)) {
                throw invalid("duplicate event id " + id);
            }
            String context = "event " + id;
            Map<String, Object> fields = new LinkedHashMap<>();
            for (String field : List.of("average_entry_score", "highest_score", "transfers_made")) {
                fields.put(field, nullableInt(node, field, context));
            }
            for (String field : List.of("most_selected", "most_transferred_in", "most_captained",
                    "most_vice_captained", "top_element")) {
                Integer playerId = nullableInt(node, field, context);
                if (playerId != null && playerId <= 0) {
                    throw invalid(field + " must be positive in " + context);
                }
                fields.put(field, playerId);
            }
            rows.add(new GameweekRow(season.id(), season.code(), id,
                    requiredText(node, "name", 50, context), requiredInstant(node, "deadline_time", context),
                    requiredBoolean(node, "finished", context), requiredBoolean(node, "data_checked", context),
                    requiredBoolean(node, "is_current", context), requiredBoolean(node, "is_next", context), fields));
        }
        return List.copyOf(rows);
    }

    private static List<FixtureRow> parseFixtures(Season season, JsonNode nodes,
                                                  Map<Integer, TeamRow> teams) {
        List<FixtureRow> rows = new ArrayList<>(nodes.size());
        Set<Integer> ids = new HashSet<>();
        for (JsonNode node : nodes) {
            requireObject(node, "fixture");
            int id = positiveInt(node, "id", "fixture");
            if (!ids.add(id)) {
                throw invalid("duplicate fixture id " + id);
            }
            String context = "fixture " + id;
            int home = positiveInt(node, "team_h", context);
            int away = positiveInt(node, "team_a", context);
            if (home == away || !teams.containsKey(home) || !teams.containsKey(away)) {
                throw invalid(context + " has invalid team references");
            }
            Integer gameweek = nullableInt(node, "event", context);
            Instant kickoff = nullableInstant(node, "kickoff_time", context);
            boolean started = requiredBoolean(node, "started", context);
            boolean finished = requiredBoolean(node, "finished", context);
            boolean provisional = requiredBoolean(node, "finished_provisional", context);
            Integer homeScore = nullableInt(node, "team_h_score", context);
            Integer awayScore = nullableInt(node, "team_a_score", context);
            int minutes = exactInt(node, "minutes", context);
            if (minutes < 0 || minutes > 130 || (homeScore == null) != (awayScore == null)
                    || homeScore != null && (homeScore < 0 || awayScore < 0)) {
                throw invalid(context + " has incoherent minutes/scores");
            }
            if (!started && (finished || provisional || minutes != 0 || homeScore != null)) {
                throw invalid(context + " has incoherent not-started status");
            }
            if (finished && (!started || homeScore == null || homeScore < 0 || awayScore < 0 || minutes < 90)) {
                throw invalid(context + " has incoherent finished status/scores");
            }
            JsonNode stats = requiredArray(node, "stats", context);
            validateRawStats(stats, context);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("finished_provisional", provisional);
            fields.put("minutes", minutes);
            fields.put("stats", canonicalJson(stats));
            Integer homeDifficulty = nullableDifficulty(node, "team_h_difficulty", context);
            Integer awayDifficulty = nullableDifficulty(node, "team_a_difficulty", context);
            rows.add(new FixtureRow(season.id(), season.code(), id, exactInt(node, "code", context),
                    gameweek, home, away, teams.get(home).name(), teams.get(home).shortName(),
                    teams.get(away).name(), teams.get(away).shortName(), homeScore, awayScore,
                    homeDifficulty, awayDifficulty, kickoff, started, finished, fields));
        }
        return List.copyOf(rows);
    }

    private static void validateRawStats(JsonNode stats, String context) {
        Set<String> identifiers = new HashSet<>();
        for (JsonNode stat : stats) {
            requireObject(stat, context + " stat");
            String identifier = requiredText(stat, "identifier", 100, context + " stat");
            if (!identifiers.add(identifier)) {
                throw invalid(context + " has duplicate stat identifier " + identifier);
            }
            for (String side : List.of("h", "a")) {
                JsonNode values = requiredArray(stat, side, context + " " + identifier);
                Set<Integer> sidePlayers = new HashSet<>();
                for (JsonNode value : values) {
                    int player = positiveInt(value, "element", context + " " + identifier);
                    exactInt(value, "value", context + " " + identifier);
                    if (!sidePlayers.add(player)) {
                        throw invalid(context + " has duplicate player in " + identifier + " " + side);
                    }
                }
            }
        }
    }

    private static void validateSeasonAndLeague(Season season, List<GameweekRow> gameweeks,
                                                List<FixtureRow> fixtures, Set<Integer> teamIds,
                                                Instant capturedAt) {
        Instant previous = null;
        for (GameweekRow gameweek : gameweeks) {
            requireInSeason(season, gameweek.deadline(), "event " + gameweek.id() + " deadline");
            if (previous != null && !gameweek.deadline().isAfter(previous)) {
                throw invalid("event deadlines are not strictly increasing by id");
            }
            previous = gameweek.deadline();
        }
        Map<Integer, Instant> deadlines = new LinkedHashMap<>();
        gameweeks.forEach(row -> deadlines.put(row.id(), row.deadline()));
        boolean hasDatedFixture = false;
        for (FixtureRow fixture : fixtures) {
            if (fixture.kickoff() == null) {
                continue;
            }
            hasDatedFixture = true;
            requireInSeason(season, fixture.kickoff(), "fixture " + fixture.id() + " kickoff");
            if (fixture.gameweek() != null) {
                Instant ownDeadline = deadlines.get(fixture.gameweek());
                Instant nextDeadline = deadlines.get(fixture.gameweek() + 1);
                if (ownDeadline == null || !fixture.kickoff().isAfter(ownDeadline)
                        || nextDeadline != null && !fixture.kickoff().isBefore(nextDeadline)) {
                    throw invalid("fixture " + fixture.id()
                            + " kickoff contradicts assigned gameweek deadlines");
                }
            }
        }
        if (!hasDatedFixture) {
            throw invalid("fixtures endpoint has no dated row to validate against the season calendar");
        }
        List<ScheduleSource.EventRow> scheduleEvents = gameweeks.stream()
                .map(row -> new ScheduleSource.EventRow(row.id(), row.deadline(), capturedAt)).toList();
        List<ScheduleSource.FixtureRow> scheduleFixtures = fixtures.stream()
                .map(row -> new ScheduleSource.FixtureRow(row.id(), row.gameweek(), row.homeTeamId(),
                        row.awayTeamId(), row.kickoff(), row.started(), row.finished(),
                        row.homeDifficulty(), row.awayDifficulty(), capturedAt)).toList();
        ScheduleSource.Completeness completeness = ScheduleSource.assess(
                java.util.Optional.of(new ScheduleSource.Snapshot(season.code(), capturedAt)),
                scheduleEvents, scheduleFixtures);
        if (!completeness.complete()) {
            throw invalid("incomplete league schedule: " + completeness.reason());
        }
        Set<Integer> scheduleTeams = new HashSet<>();
        fixtures.forEach(row -> {
            scheduleTeams.add(row.homeTeamId());
            scheduleTeams.add(row.awayTeamId());
        });
        if (!scheduleTeams.equals(teamIds)) {
            throw invalid("schedule clubs do not equal catalogue clubs");
        }
    }

    private static DailyPriceRow dailyPrice(PlayerRow player, TeamRow team, LocalDate date,
                                             ReadInterval interval) {
        String first = (String) player.fields().get("first_name");
        String second = (String) player.fields().get("second_name");
        String fullName = first == null || second == null ? null : first + " " + second;
        Map<String, Object> fields = new LinkedHashMap<>();
        for (String key : List.of("cost_change_event", "cost_change_start", "selected_by_percent",
                "status", "news", "penalties_order", "direct_fk_order", "corners_order")) {
            fields.put(key, player.fields().get(key));
        }
        return new DailyPriceRow(player.seasonCode(), date, player.id(), player.webName(), fullName,
                player.teamId(), team.shortName(), team.name(), player.elementType(), player.nowCost(),
                interval, fields);
    }

    private static Map<Integer, TeamRow> indexTeams(List<TeamRow> teams) {
        Map<Integer, TeamRow> result = new LinkedHashMap<>();
        teams.forEach(team -> result.put(team.id(), team));
        return Collections.unmodifiableMap(result);
    }

    private static void validateBatchWindow(ReadInterval first, ReadInterval second) {
        Instant start = first.startedAt().isBefore(second.startedAt()) ? first.startedAt() : second.startedAt();
        Instant end = max(first.completedAt(), second.completedAt());
        if (ChronoUnit.MINUTES.between(start, end) > MAX_BATCH_SPAN_MINUTES) {
            throw invalid("endpoint reads do not belong to one bounded source window");
        }
    }

    private static void requireInSeason(Season season, Instant instant, String context) {
        LocalDate date = LocalDate.ofInstant(instant, UK);
        if (date.isBefore(season.validationStartsOn()) || date.isAfter(season.validationEndsOn())) {
            throw invalid(context + " falls outside configured season " + season.code());
        }
    }

    private static JsonNode read(byte[] bytes, String endpoint) {
        if (bytes.length == 0) {
            throw invalid(endpoint + " body is empty");
        }
        try {
            JsonNode root = JSON.readTree(bytes);
            if (root == null || root.isNull()) {
                throw invalid(endpoint + " root is empty or null");
            }
            return root;
        } catch (IOException e) {
            throw invalid("malformed " + endpoint + " JSON", e);
        }
    }

    private static JsonNode requiredArray(JsonNode node, String field, String context) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw invalid("missing or non-array " + field + " in " + context);
        }
        return value;
    }

    private static void requireObject(JsonNode node, String context) {
        if (!node.isObject()) {
            throw invalid(context + " must be an object");
        }
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

    private static Integer nullableInt(JsonNode node, String field, String context) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return exactInt(node, field, context);
    }

    private static Integer nullableSmallInt(JsonNode node, String field, String context) {
        Integer value = nullableInt(node, field, context);
        if (value != null && (value < Short.MIN_VALUE || value > Short.MAX_VALUE)) {
            throw invalid(field + " is outside SMALLINT range in " + context);
        }
        return value;
    }

    private static Integer nullableDifficulty(JsonNode node, String field, String context) {
        Integer value = nullableSmallInt(node, field, context);
        if (value != null && (value < 1 || value > 5)) {
            throw invalid(field + " must be 1-5 or null in " + context);
        }
        return value;
    }

    private static String requiredText(JsonNode node, String field, int maxLength, String context) {
        String value = nullableText(node, field, maxLength, context);
        if (value == null || value.isBlank()) {
            throw invalid("missing or blank " + field + " in " + context);
        }
        return value;
    }

    private static String nullableText(JsonNode node, String field, int maxLength, String context) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.textValue().length() > maxLength) {
            throw invalid("invalid text " + field + " in " + context);
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

    private static Instant requiredInstant(JsonNode node, String field, String context) {
        Instant value = nullableInstant(node, field, context);
        if (value == null) {
            throw invalid("missing " + field + " in " + context);
        }
        return value;
    }

    private static Instant nullableInstant(JsonNode node, String field, String context) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid("non-text timestamp " + field + " in " + context);
        }
        try {
            return OffsetDateTime.parse(value.textValue()).toInstant();
        } catch (DateTimeParseException e) {
            throw invalid("invalid timestamp " + field + " in " + context, e);
        }
    }

    private static BigDecimal nullableDecimal(JsonNode node, String field, DecimalShape shape,
                                              String context) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid("non-text decimal " + field + " in " + context);
        }
        try {
            BigDecimal decimal = new BigDecimal(value.textValue());
            BigDecimal significant = decimal.stripTrailingZeros();
            int fractionalDigits = Math.max(significant.scale(), 0);
            int integerDigits = Math.max(significant.precision() - significant.scale(), 0);
            if (fractionalDigits > shape.scale()
                    || integerDigits > shape.precision() - shape.scale()) {
                throw invalid(field + " exceeds NUMERIC(" + shape.precision() + "," + shape.scale()
                        + ") in " + context);
            }
            return decimal;
        } catch (NumberFormatException e) {
            throw invalid("invalid decimal " + field + " in " + context, e);
        }
    }

    private static String canonicalJson(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (IOException e) {
            throw invalid("could not preserve fixture stats", e);
        }
    }

    private static Instant max(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    private static Map<String, Object> immutableNullableMap(Map<String, Object> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid FPL catalogue/schedule payload: " + message);
    }

    private static IllegalArgumentException invalid(String message, Exception cause) {
        return new IllegalArgumentException("Invalid FPL catalogue/schedule payload: " + message, cause);
    }

    private record DecimalShape(int precision, int scale) {
    }

    public record Season(int id, String code, LocalDate startsOn, LocalDate endsOn) {
        public Season {
            if (id <= 0 || code == null || !SEASON_CODE.matcher(code).matches()) {
                throw new IllegalArgumentException("Invalid configured season identity/calendar");
            }
            int startYear = Integer.parseInt(code.substring(0, 4));
            int expectedEnd = (startYear + 1) % 100;
            if (Integer.parseInt(code.substring(5)) != expectedEnd) {
                throw new IllegalArgumentException("Configured season code does not match its calendar");
            }
            LocalDate validationStart = LocalDate.of(startYear, 7, 1);
            LocalDate validationEnd = LocalDate.of(startYear + 1, 6, 30);
            if (startsOn != null && (startsOn.isBefore(validationStart) || startsOn.isAfter(validationEnd))
                    || endsOn != null && (endsOn.isBefore(validationStart) || endsOn.isAfter(validationEnd))
                    || startsOn != null && endsOn != null && endsOn.isBefore(startsOn)) {
                throw new IllegalArgumentException("Configured season dates contradict its code");
            }
        }

        private LocalDate validationStartsOn() {
            return LocalDate.of(Integer.parseInt(code.substring(0, 4)), 7, 1);
        }

        private LocalDate validationEndsOn() {
            return validationStartsOn().plusYears(1).minusDays(1);
        }
    }

    public record ReadInterval(Instant startedAt, Instant completedAt) {
        public ReadInterval {
            Objects.requireNonNull(startedAt);
            Objects.requireNonNull(completedAt);
            if (completedAt.isBefore(startedAt)) {
                throw new IllegalArgumentException("Source read completed before it started");
            }
        }
    }

    public record SourceDocument(byte[] bytes, ReadInterval readInterval) {
        public SourceDocument {
            bytes = Objects.requireNonNull(bytes).clone();
            Objects.requireNonNull(readInterval);
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public record TeamRow(int seasonId, int id, int code, String name, String shortName,
                          Map<String, Object> fields) {
        public TeamRow { fields = immutableNullableMap(fields); }
    }

    public record PlayerRow(int seasonId, String seasonCode, int id, int teamId, String webName,
                            int elementType, int nowCost, Map<String, Object> fields) {
        public PlayerRow { fields = immutableNullableMap(fields); }
    }

    public record DailyPriceRow(String seasonCode, LocalDate priceDate, int elementId,
                                String webName, String fullName, int teamId, String teamShort,
                                String teamName, int elementType, int nowCost,
                                ReadInterval sourceRead, Map<String, Object> fields) {
        public DailyPriceRow { fields = immutableNullableMap(fields); }
    }

    public record GameweekRow(int seasonId, String seasonCode, int id, String name, Instant deadline,
                              boolean finished, boolean dataChecked, boolean current, boolean next,
                              Map<String, Object> fields) {
        public GameweekRow { fields = immutableNullableMap(fields); }
    }

    public record FixtureRow(int seasonId, String seasonCode, int id, int code, Integer gameweek,
                             int homeTeamId, int awayTeamId, String homeTeamName, String homeTeamShort,
                             String awayTeamName, String awayTeamShort, Integer homeScore,
                             Integer awayScore, Integer homeDifficulty, Integer awayDifficulty,
                             Instant kickoff, boolean started, boolean finished,
                             Map<String, Object> fields) {
        public FixtureRow { fields = immutableNullableMap(fields); }
    }

    public record Snapshot(Season season, ReadInterval bootstrapRead, ReadInterval fixturesRead,
                           List<TeamRow> teams, List<PlayerRow> players,
                           List<DailyPriceRow> dailyPrices, List<GameweekRow> gameweeks,
                           List<FixtureRow> fixtures) {
        public Snapshot {
            Objects.requireNonNull(season);
            Objects.requireNonNull(bootstrapRead);
            Objects.requireNonNull(fixturesRead);
            teams = List.copyOf(teams);
            players = List.copyOf(players);
            dailyPrices = List.copyOf(dailyPrices);
            gameweeks = List.copyOf(gameweeks);
            fixtures = List.copyOf(fixtures);
        }
    }
}
