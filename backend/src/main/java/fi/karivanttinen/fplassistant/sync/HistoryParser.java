package fi.karivanttinen.fplassistant.sync;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.FixtureRow;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.ReadInterval;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.Season;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.SourceDocument;

/** Strict, side-effect-free mapping of one current-season element-summary history response. */
public final class HistoryParser {

    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final DecimalShape EXPECTED = new DecimalShape(5, 2);
    private static final DecimalShape ICT = new DecimalShape(6, 1);

    /** Fields whose absence or invalid type rejects an entire current-season player page. */
    public static final Set<String> MANDATORY_HISTORY_FIELDS = Set.of(
            "element", "fixture", "round", "opponent_team", "was_home", "minutes",
            "total_points", "bonus", "bps", "goals_scored", "assists", "clean_sheets",
            "goals_conceded", "own_goals", "penalties_saved", "penalties_missed", "saves",
            "yellow_cards", "red_cards", "starts", "value", "selected", "transfers_in",
            "transfers_out");

    /** Optional source components are independently tracked by {@link Component} presence bits. */
    public static final Set<Component> OPTIONAL_HISTORY_COMPONENTS =
            java.util.Collections.unmodifiableSet(EnumSet.allOf(Component.class));

    private HistoryParser() {
    }

    /**
     * Maps only rows returned by the source. Every row must belong to the supplied configured
     * season schedule; no resolver fallback or current bootstrap club is consulted.
     */
    public static HistoryBatch parse(Season season, int requestedPlayerId, SourceDocument document,
                                     Collection<CanonicalFixture> fixtures) {
        Objects.requireNonNull(season, "season");
        Objects.requireNonNull(document, "document");
        if (requestedPlayerId <= 0) throw invalid("requested player id must be positive");
        Map<Integer, CanonicalFixture> byId = indexFixtures(season, fixtures);
        JsonNode root = read(document.bytes());
        if (!root.isObject()) throw invalid("element-summary root must be an object");
        JsonNode history = root.get("history");
        if (history == null || !history.isArray()) throw invalid("missing or non-array history");

        List<HistoryRow> rows = new ArrayList<>(history.size());
        Set<Integer> seen = new HashSet<>();
        for (JsonNode node : history) {
            if (!node.isObject()) throw invalid("history row must be an object");
            String context = "element " + requestedPlayerId + " history row";
            int playerId = positiveInt(node, "element", context);
            if (playerId != requestedPlayerId) {
                throw invalid("history element " + playerId + " does not match requested element "
                        + requestedPlayerId);
            }
            int fixtureId = positiveInt(node, "fixture", context);
            if (!seen.add(fixtureId)) {
                throw invalid("duplicate fixture " + fixtureId + " in element " + requestedPlayerId + " history");
            }
            CanonicalFixture fixture = byId.get(fixtureId);
            if (fixture == null) {
                throw parentContradiction("fixture " + fixtureId + " is outside configured season "
                        + season.code());
            }
            rows.add(parseRow(season, playerId, node, fixture));
        }
        return new HistoryBatch(season.id(), season.code(), requestedPlayerId,
                document.readInterval(), rows);
    }

    /** Converts the already-validated Task 3 schedule row without consulting bootstrap players. */
    public static CanonicalFixture canonicalFixture(FixtureRow fixture) {
        Objects.requireNonNull(fixture, "fixture");
        return new CanonicalFixture(fixture.seasonId(), fixture.seasonCode(), fixture.id(),
                fixture.gameweek(), fixture.homeTeamId(), fixture.awayTeamId(), fixture.kickoff());
    }

    private static HistoryRow parseRow(Season season, int playerId, JsonNode node,
                                       CanonicalFixture fixture) {
        String context = "element " + playerId + " fixture " + fixture.fixtureId();
        int gameweek = positiveSmallInt(node, "round", context);
        if (fixture.gameweek() == null || fixture.gameweek() != gameweek) {
            throw parentContradiction(context + " round contradicts configured-season fixture");
        }
        int opponent = positiveInt(node, "opponent_team", context);
        boolean wasHome = requiredBoolean(node, "was_home", context);
        int expectedOpponent = wasHome ? fixture.awayTeamId() : fixture.homeTeamId();
        if (opponent != expectedOpponent) {
            throw parentContradiction(context + " opponent contradicts configured-season fixture");
        }
        int playerTeam = wasHome ? fixture.homeTeamId() : fixture.awayTeamId();
        Instant kickoff = nullableInstant(node, "kickoff_time", context);
        if (!Objects.equals(kickoff, fixture.kickoff())) {
            throw parentContradiction(context + " kickoff contradicts configured-season fixture");
        }

        short minutes = nonNegativeSmallInt(node, "minutes", context);
        if (minutes > 130) throw invalid("minutes out of range in " + context);
        short totalPoints = smallInt(node, "total_points", context);
        short bonus = nonNegativeSmallInt(node, "bonus", context);
        short bps = smallInt(node, "bps", context);
        short goals = nonNegativeSmallInt(node, "goals_scored", context);
        short assists = nonNegativeSmallInt(node, "assists", context);
        short cleanSheets = nonNegativeSmallInt(node, "clean_sheets", context);
        short goalsConceded = nonNegativeSmallInt(node, "goals_conceded", context);
        short ownGoals = nonNegativeSmallInt(node, "own_goals", context);
        short penaltiesSaved = nonNegativeSmallInt(node, "penalties_saved", context);
        short penaltiesMissed = nonNegativeSmallInt(node, "penalties_missed", context);
        short saves = nonNegativeSmallInt(node, "saves", context);
        short yellowCards = nonNegativeSmallInt(node, "yellow_cards", context);
        short redCards = nonNegativeSmallInt(node, "red_cards", context);
        short starts = nonNegativeSmallInt(node, "starts", context);
        if (starts > 1) throw invalid("starts must be 0 or 1 in " + context);
        int value = positiveInt(node, "value", context);
        int selected = nonNegativeInt(node, "selected", context);
        int transfersIn = nonNegativeInt(node, "transfers_in", context);
        int transfersOut = nonNegativeInt(node, "transfers_out", context);

        EnumSet<Component> presence = EnumSet.noneOf(Component.class);
        BigDecimal expectedGoals = optionalDecimal(node, "expected_goals", EXPECTED, context,
                Component.EXPECTED_GOALS, presence);
        BigDecimal expectedAssists = optionalDecimal(node, "expected_assists", EXPECTED, context,
                Component.EXPECTED_ASSISTS, presence);
        BigDecimal expectedGoalInvolvements = optionalDecimal(node, "expected_goal_involvements",
                EXPECTED, context, Component.EXPECTED_GOAL_INVOLVEMENTS, presence);
        BigDecimal expectedGoalsConceded = optionalDecimal(node, "expected_goals_conceded", EXPECTED,
                context, Component.EXPECTED_GOALS_CONCEDED, presence);
        BigDecimal influence = optionalDecimal(node, "influence", ICT, context,
                Component.INFLUENCE, presence);
        BigDecimal creativity = optionalDecimal(node, "creativity", ICT, context,
                Component.CREATIVITY, presence);
        BigDecimal threat = optionalDecimal(node, "threat", ICT, context, Component.THREAT, presence);
        BigDecimal ictIndex = optionalDecimal(node, "ict_index", ICT, context,
                Component.ICT_INDEX, presence);
        Short cbi = optionalNonNegativeSmallInt(node, "clearances_blocks_interceptions", context,
                Component.CLEARANCES_BLOCKS_INTERCEPTIONS, presence);
        Short recoveries = optionalNonNegativeSmallInt(node, "recoveries", context,
                Component.RECOVERIES, presence);
        Short tackles = optionalNonNegativeSmallInt(node, "tackles", context,
                Component.TACKLES, presence);
        Short defensiveContribution = optionalNonNegativeSmallInt(node, "defensive_contribution",
                context, Component.DEFENSIVE_CONTRIBUTION, presence);

        return new HistoryRow(season.id(), season.code(), fixture.fixtureId(), playerId, gameweek,
                playerTeam, opponent, wasHome, kickoff, minutes, totalPoints, bonus, bps, goals,
                assists, expectedGoals, expectedAssists, expectedGoalInvolvements, cleanSheets,
                goalsConceded, ownGoals, penaltiesSaved, penaltiesMissed, saves,
                expectedGoalsConceded, yellowCards, redCards, influence, creativity, threat,
                ictIndex, value, selected, transfersIn, transfersOut, starts, cbi, recoveries,
                tackles, defensiveContribution, presence);
    }

    private static Map<Integer, CanonicalFixture> indexFixtures(Season season,
                                                                 Collection<CanonicalFixture> fixtures) {
        Objects.requireNonNull(fixtures, "fixtures");
        Map<Integer, CanonicalFixture> result = new HashMap<>();
        for (CanonicalFixture fixture : fixtures) {
            Objects.requireNonNull(fixture, "fixture");
            if (fixture.seasonId() != season.id() || !fixture.seasonCode().equals(season.code())) {
                throw invalid("canonical fixture belongs to another season");
            }
            if (result.putIfAbsent(fixture.fixtureId(), fixture) != null) {
                throw invalid("duplicate canonical fixture " + fixture.fixtureId());
            }
        }
        return Map.copyOf(result);
    }

    private static JsonNode read(byte[] bytes) {
        if (bytes.length == 0) throw invalid("element-summary body is empty");
        try {
            JsonNode root = JSON.readTree(bytes);
            if (root == null || root.isNull()) throw invalid("element-summary root is empty or null");
            return root;
        } catch (IOException e) {
            throw invalid("malformed element-summary JSON", e);
        }
    }

    private static int positiveInt(JsonNode node, String field, String context) {
        int value = exactInt(node, field, context);
        if (value <= 0) throw invalid(field + " must be positive in " + context);
        return value;
    }

    private static int positiveSmallInt(JsonNode node, String field, String context) {
        int value = positiveInt(node, field, context);
        if (value > Short.MAX_VALUE) throw invalid(field + " exceeds SMALLINT in " + context);
        return value;
    }

    private static int nonNegativeInt(JsonNode node, String field, String context) {
        int value = exactInt(node, field, context);
        if (value < 0) throw invalid(field + " must be non-negative in " + context);
        return value;
    }

    private static short smallInt(JsonNode node, String field, String context) {
        int value = exactInt(node, field, context);
        if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
            throw invalid(field + " exceeds SMALLINT in " + context);
        }
        return (short) value;
    }

    private static short nonNegativeSmallInt(JsonNode node, String field, String context) {
        short value = smallInt(node, field, context);
        if (value < 0) throw invalid(field + " must be non-negative in " + context);
        return value;
    }

    private static Short optionalNonNegativeSmallInt(JsonNode node, String field, String context,
                                                      Component component, Set<Component> presence) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        short parsed = nonNegativeSmallInt(node, field, context);
        presence.add(component);
        return parsed;
    }

    private static int exactInt(JsonNode node, String field, String context) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid("missing or non-integer " + field + " in " + context);
        }
        return value.intValue();
    }

    private static BigDecimal optionalDecimal(JsonNode node, String field, DecimalShape shape,
                                              String context, Component component,
                                              Set<Component> presence) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw invalid("non-text decimal " + field + " in " + context);
        try {
            BigDecimal decimal = new BigDecimal(value.textValue());
            BigDecimal significant = decimal.stripTrailingZeros();
            int fractionalDigits = Math.max(significant.scale(), 0);
            int integerDigits = Math.max(significant.precision() - significant.scale(), 0);
            if (decimal.signum() < 0 || fractionalDigits > shape.scale()
                    || integerDigits > shape.precision() - shape.scale()) {
                throw invalid(field + " exceeds NUMERIC(" + shape.precision() + "," + shape.scale()
                        + ") or is negative in " + context);
            }
            presence.add(component);
            return decimal;
        } catch (NumberFormatException e) {
            throw invalid("invalid decimal " + field + " in " + context, e);
        }
    }

    private static boolean requiredBoolean(JsonNode node, String field, String context) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw invalid("missing or non-boolean " + field + " in " + context);
        }
        return value.booleanValue();
    }

    private static Instant nullableInstant(JsonNode node, String field, String context) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw invalid("non-text timestamp " + field + " in " + context);
        try {
            return OffsetDateTime.parse(value.textValue()).toInstant();
        } catch (DateTimeParseException e) {
            throw invalid("invalid timestamp " + field + " in " + context, e);
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid FPL history payload: " + message);
    }

    private static IllegalArgumentException invalid(String message, Exception cause) {
        return new IllegalArgumentException("Invalid FPL history payload: " + message, cause);
    }

    private static ParentContradictionException parentContradiction(String message) {
        return new ParentContradictionException("Invalid FPL history payload: " + message);
    }

    /** A well-shaped history row that may be newer than its catalogue/schedule parent. */
    public static final class ParentContradictionException extends IllegalArgumentException {
        private ParentContradictionException(String message) { super(message); }
    }

    private record DecimalShape(int precision, int scale) {
    }

    public enum Component {
        EXPECTED_GOALS(1L << 0), EXPECTED_ASSISTS(1L << 1),
        EXPECTED_GOAL_INVOLVEMENTS(1L << 2), EXPECTED_GOALS_CONCEDED(1L << 3),
        INFLUENCE(1L << 4), CREATIVITY(1L << 5), THREAT(1L << 6), ICT_INDEX(1L << 7),
        CLEARANCES_BLOCKS_INTERCEPTIONS(1L << 8), RECOVERIES(1L << 9),
        TACKLES(1L << 10), DEFENSIVE_CONTRIBUTION(1L << 11);

        private final long bit;

        Component(long bit) { this.bit = bit; }

        public long bit() { return bit; }

        public static long allMask() {
            long mask = 0;
            for (Component component : values()) mask |= component.bit;
            return mask;
        }
    }

    public record CanonicalFixture(int seasonId, String seasonCode, int fixtureId, Integer gameweek,
                                   int homeTeamId, int awayTeamId, Instant kickoff) {
        public CanonicalFixture {
            if (seasonId <= 0 || seasonCode == null || fixtureId <= 0 || homeTeamId <= 0
                    || awayTeamId <= 0 || homeTeamId == awayTeamId
                    || gameweek != null && (gameweek <= 0 || gameweek > Short.MAX_VALUE)) {
                throw new IllegalArgumentException("Invalid canonical fixture identity");
            }
        }
    }

    public record HistoryBatch(int seasonId, String seasonCode, int playerId, ReadInterval sourceRead,
                               List<HistoryRow> rows) {
        public HistoryBatch {
            Objects.requireNonNull(seasonCode);
            Objects.requireNonNull(sourceRead);
            rows = List.copyOf(rows);
        }

        public List<Evidence> evidence(long generation) {
            if (generation <= 0) throw new IllegalArgumentException("generation must be positive");
            return rows.stream().map(row -> new Evidence(seasonId, seasonCode, row.fixtureId(),
                    playerId, generation, row.presenceMask(), sourceRead)).toList();
        }
    }

    public record HistoryRow(
            int seasonId, String seasonCode, int fixtureId, int playerId, int gameweek,
            int playerTeamId, int opponentTeamId, boolean wasHome, Instant kickoffTime,
            short minutes, short totalPoints, short bonus, short bps, short goalsScored,
            short assists, BigDecimal expectedGoals, BigDecimal expectedAssists,
            BigDecimal expectedGoalInvolvements, short cleanSheets, short goalsConceded,
            short ownGoals, short penaltiesSaved, short penaltiesMissed, short saves,
            BigDecimal expectedGoalsConceded, short yellowCards, short redCards,
            BigDecimal influence, BigDecimal creativity, BigDecimal threat, BigDecimal ictIndex,
            int value, int selected, int transfersIn, int transfersOut, short starts,
            Short clearancesBlocksInterceptions, Short recoveries, Short tackles,
            Short defensiveContribution, Set<Component> presentComponents) {
        public HistoryRow {
            presentComponents = Set.copyOf(presentComponents);
        }

        public long presenceMask() {
            long mask = 0;
            for (Component component : presentComponents) mask |= component.bit();
            return mask;
        }

        public boolean has(Component component) {
            return presentComponents.contains(component);
        }
    }

    public record Evidence(int seasonId, String seasonCode, int fixtureId, int playerId,
                           long generation, long componentPresence, ReadInterval sourceRead) {
        public Evidence {
            if (seasonId <= 0 || seasonCode == null || fixtureId <= 0 || playerId <= 0
                    || generation <= 0 || componentPresence < 0
                    || (componentPresence & ~Component.allMask()) != 0) {
                throw new IllegalArgumentException("Invalid player-fixture evidence");
            }
            Objects.requireNonNull(sourceRead);
        }

        public boolean has(Component component) {
            return (componentPresence & component.bit()) != 0;
        }
    }
}
