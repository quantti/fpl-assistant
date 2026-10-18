package fi.karivanttinen.fplassistant.teamxg;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnderstatParserTest {
    private static final Instant NOW = Instant.parse("2026-10-18T08:00:00Z");
    private static final String MATCH = """
            {"id":"12","isResult":true,"datetime":"2026-10-17 16:30:00",
             "h":{"title":"Manchester City"},"a":{"title":"Leeds"},
             "goals":{"h":"2","a":"0"},"xG":{"h":"2.082201","a":"0"}}
            """;

    @Test
    void readsBothTeamTotalsWithoutAnyPlayerOrFplDataAndPreservesZero() {
        var matches = UnderstatParser.parse(2026, "{\"dates\":[" + MATCH + "]}", NOW);
        assertThat(matches).hasSize(1);
        var match = matches.getFirst();
        assertThat(match.home()).isEqualTo("MCI");
        assertThat(match.away()).isEqualTo("LEE");
        assertThat(match.homeXg()).isEqualByComparingTo(new BigDecimal("2.082201"));
        assertThat(match.awayXg()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(match.kickoff()).isEqualTo(Instant.parse("2026-10-17T16:30:00Z"));
    }

    @Test
    void numericJsonXgRetainsItsDecimalValueWithoutDoubleRounding() {
        String decimal = "2.082201000000000000000001";
        var match = UnderstatParser.parse(2026,
                "{\"dates\":[" + MATCH.replace("\"2.082201\"", decimal) + "]}", NOW).getFirst();
        assertThat(match.homeXg()).isEqualByComparingTo(new BigDecimal(decimal));
    }

    @Test
    void numericJsonMustNotRoundInvalidBoundsOrFractionalIntegersIntoValidValues() {
        for (String match : new String[]{MATCH.replace("\"2.082201\"", "100.00000000000000001"),
                MATCH.replace("\"2.082201\"", "-1e-999"),
                MATCH.replace("\"2\"", "2.00000000000000001"),
                MATCH.replace("\"12\"", "12.00000000000000001")}) {
            assertThatThrownBy(() -> UnderstatParser.parse(2026, "{\"dates\":[" + match + "]}", NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void unplayedMatchesAreNotObservedZeroes() {
        assertThat(UnderstatParser.parse(2026, "{\"dates\":[" + MATCH.replace("true", "false") + "]}", NOW)).isEmpty();
    }

    @Test
    void rejectsMissingNegativeNonNumericAndOutOfRangeXg() {
        for (String value : new String[]{"null", "\"-1\"", "\"NaN\"", "\"101\"", "{}"}) {
            String body = MATCH.replace("\"2.082201\"", value);
            assertThatThrownBy(() -> UnderstatParser.parse(2026, "{\"dates\":[" + body + "]}", NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsWrongSeasonFutureResultsUnknownClubsAndFractionalScores() {
        for (String body : new String[]{MATCH.replace("2026-10-17", "2025-10-17"),
                MATCH.replace("2026-10-17", "2026-10-19"), MATCH.replace("Leeds", "Unknown Club"),
                MATCH.replace("\"2\"", "\"2.5\"")}) {
            assertThatThrownBy(() -> UnderstatParser.parse(2026, "{\"dates\":[" + body + "]}", NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsDuplicateMatchIdsOrPairingsRatherThanDoubleCounting() {
        for (String second : new String[]{MATCH, MATCH.replace("\"12\"", "\"13\"")}) {
            assertThatThrownBy(() -> UnderstatParser.parse(2026, "{\"dates\":[" + MATCH + "," + second + "]}", NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void exactFplShortNamesTakePrecedenceOverPartialClubNames() {
        for (String code : new String[]{"ARS", "AVL", "BOU", "BRE", "BHA", "BUR", "CHE", "COV",
                "CRY", "EVE", "FUL", "HUL", "IPS", "LEE", "LIV", "MCI", "MUN", "NEW", "NFO",
                "SUN", "TOT", "WHU", "WOL"}) {
            assertThat(UnderstatParser.matchingClubs(code)).as(code).containsExactly(code);
            assertThat(UnderstatParser.matchingClubs(" " + code.toLowerCase(java.util.Locale.ROOT) + " "))
                    .as("normalised " + code).containsExactly(code);
        }
    }

    @Test
    void clubSearchIsExplicitAndAmbiguousNamesAreNotGuessed() {
        assertThat(UnderstatParser.matchingClubs("Man City")).containsExactly("MCI");
        assertThat(UnderstatParser.matchingClubs("NFO")).containsExactly("NFO");
        assertThat(UnderstatParser.matchingClubs("Leeds United")).containsExactly("LEE");
        assertThat(UnderstatParser.matchingClubs("Brighton & Hove Albion")).containsExactly("BHA");
        assertThat(UnderstatParser.matchingClubs("Manchester")).containsExactly("MCI", "MUN");
        assertThat(UnderstatParser.matchingClubs("!")).isEmpty();
        assertThat(UnderstatParser.matchingClubs("Unknown")).isEmpty();
    }

    @Test
    void acceptsKnownEmptyButRejectsMissingDatesDuplicateKeysAndTrailingJson() {
        assertThat(UnderstatParser.parse(2026, "{\"dates\":[]}", NOW)).isEmpty();
        for (String body : new String[]{"{}", "{\"dates\":null}", "{\"dates\":[],\"dates\":[]}", "{\"dates\":[]}{}"}) {
            assertThatThrownBy(() -> UnderstatParser.parse(2026, body, NOW)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
