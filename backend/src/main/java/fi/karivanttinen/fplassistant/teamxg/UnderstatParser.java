package fi.karivanttinen.fplassistant.teamxg;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Direct, complete match totals; no player aggregation or inferred missing values. */
public final class UnderstatParser {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final Map<String, String> CLUBS = Map.ofEntries(
            Map.entry("Arsenal", "ARS"), Map.entry("Aston Villa", "AVL"),
            Map.entry("Bournemouth", "BOU"), Map.entry("Brentford", "BRE"),
            Map.entry("Brighton", "BHA"), Map.entry("Burnley", "BUR"),
            Map.entry("Chelsea", "CHE"), Map.entry("Coventry", "COV"),
            Map.entry("Crystal Palace", "CRY"), Map.entry("Everton", "EVE"),
            Map.entry("Fulham", "FUL"), Map.entry("Hull", "HUL"), Map.entry("Ipswich", "IPS"),
            Map.entry("Leeds", "LEE"), Map.entry("Liverpool", "LIV"),
            Map.entry("Manchester City", "MCI"), Map.entry("Manchester United", "MUN"),
            Map.entry("Newcastle United", "NEW"), Map.entry("Nottingham Forest", "NFO"),
            Map.entry("Sunderland", "SUN"), Map.entry("Tottenham", "TOT"),
            Map.entry("West Ham", "WHU"), Map.entry("Wolverhampton Wanderers", "WOL"),
            Map.entry("Man City", "MCI"), Map.entry("Man Utd", "MUN"), Map.entry("Man United", "MUN"),
            Map.entry("Wolves", "WOL"), Map.entry("Brighton & Hove Albion", "BHA"),
            Map.entry("Hull City", "HUL"), Map.entry("Ipswich Town", "IPS"),
            Map.entry("Leeds United", "LEE"), Map.entry("Coventry City", "COV"),
            Map.entry("West Ham United", "WHU"), Map.entry("Tottenham Hotspur", "TOT"),
            Map.entry("Sunderland AFC", "SUN"), Map.entry("AFC Bournemouth", "BOU"));

    public record Match(long id, Instant kickoff, String home, String away,
                        int homeGoals, int awayGoals, BigDecimal homeXg, BigDecimal awayXg) {}

    private UnderstatParser() {}

    public static String season(int year) {
        if (year < 2014 || year > 2100) throw new IllegalArgumentException("Invalid Understat season year");
        return "%d-%02d".formatted(year, (year + 1) % 100);
    }

    public static List<Match> parse(int year, String body, Instant asOf) {
        season(year);
        try {
            JsonNode dates = JSON.readTree(body).get("dates");
            if (dates == null || !dates.isArray()) throw new IllegalArgumentException("Understat dates must be an array");
            var ids = new HashSet<Long>();
            var pairs = new HashSet<String>();
            List<Match> result = new ArrayList<>();
            for (JsonNode row : dates) {
                long id = new BigDecimal(text(row.get("id"))).longValueExact();
                String home = club(row.path("h").get("title"));
                String away = club(row.path("a").get("title"));
                if (id <= 0 || home.equals(away) || !ids.add(id) || !pairs.add(home + ":" + away)) {
                    throw new IllegalArgumentException("Duplicate or invalid Understat fixture identity");
                }
                LocalDateTime date = LocalDateTime.parse(text(row.get("datetime")), DATE);
                if (date.toLocalDate().isBefore(LocalDate.of(year, 7, 1))
                        || !date.toLocalDate().isBefore(LocalDate.of(year + 1, 7, 1))) {
                    throw new IllegalArgumentException("Understat match belongs to a different season");
                }
                JsonNode finished = row.get("isResult");
                if (finished == null || !finished.isBoolean()) throw new IllegalArgumentException("Missing result flag");
                if (!finished.booleanValue()) continue;
                // Understat's naive datetimes match UTC, not the host or UK summer clock.
                Instant kickoff = date.toInstant(ZoneOffset.UTC);
                if (kickoff.isAfter(asOf)) throw new IllegalArgumentException("Future Understat result");
                result.add(new Match(id, kickoff, home, away,
                        number(row.path("goals").get("h")).intValueExact(),
                        number(row.path("goals").get("a")).intValueExact(),
                        number(row.path("xG").get("h")), number(row.path("xG").get("a"))));
            }
            return List.copyOf(result);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Understat " + season(year) + " capture: " + e.getMessage(), e);
        }
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || !(node.isTextual() || node.isNumber())) {
            throw new IllegalArgumentException("Missing or invalid scalar");
        }
        return node.asText();
    }

    private static BigDecimal number(JsonNode node) {
        String value = text(node);
        if (value.length() > 40) throw new IllegalArgumentException("Numeric value too long");
        BigDecimal decimal = new BigDecimal(value);
        if (decimal.signum() < 0 || decimal.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("Expected a nonnegative match value at most 100");
        }
        return decimal;
    }

    public static List<String> matchingClubs(String input) {
        if (input == null || input.isBlank()) return List.of();
        String query = normalize(input);
        if (query.isEmpty()) return List.of();
        return CLUBS.entrySet().stream()
                .filter(e -> normalize(e.getKey()).contains(query) || normalize(e.getValue()).equals(query))
                .map(Map.Entry::getValue).distinct().sorted().toList();
    }

    private static String normalize(String value) {
        return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String club(JsonNode node) {
        String name = text(node);
        String club = CLUBS.get(name);
        if (club == null) throw new IllegalArgumentException("Unknown Understat club: " + name);
        return club;
    }
}
