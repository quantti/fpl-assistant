package fi.karivanttinen.fplassistant.tools;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fi.karivanttinen.fplassistant.testsupport.TapasSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FixtureToolsTest {

    @Autowired
    FixtureTools fixtureTools;

    @Autowired
    SeasonResolver seasonResolver;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        TapasSeed.ensure(jdbc);
    }

    @Test
    void getUpcomingFixturesForKnownTeam() {
        String result = fixtureTools.getUpcomingFixtures(TapasSeed.ASHFIELD, 5);

        assertThat(result).contains(TapasSeed.ASHFIELD);
        assertThat(result).doesNotContain("not found");
        assertThat(result).containsPattern("GW\\d+:");
    }

    @Test
    void everyAnswerNamesTheSeasonItCameFrom() {
        String result = fixtureTools.getUpcomingFixtures(TapasSeed.ASHFIELD, 5);
        SeasonContext season = seasonResolver.resolve();

        assertThat(result).startsWith("Data source: season " + season.code());
    }

    @Test
    void unknownTeamReturnsNotFound() {
        String result = fixtureTools.getUpcomingFixtures("ZZZUnknownFC", 5);

        assertThat(result).containsIgnoringCase("not found");
    }

    @Test
    void likeWildcardsAreEscapedNotInterpreted() {
        // Unescaped, "%" would match the first team in the table instead of failing.
        String result = fixtureTools.getUpcomingFixtures("%", 5);

        assertThat(result).containsIgnoringCase("not found");
    }

    /**
     * The seeded club plays twice in the first gameweek of the window, so asking for one gameweek
     * must return both of those fixtures and nothing from the gameweek after. Against production
     * data this test could only ever see single gameweeks and passed without exercising the rule.
     */
    @Test
    void nextNBoundsDistinctGameweeksNotFixtureRows() {
        String result = fixtureTools.getUpcomingFixtures(TapasSeed.ASHFIELD, 1);

        // Guard against a vacuous pass: an empty result would also satisfy the size bound.
        assertThat(result).doesNotContain("not found");

        Matcher m = Pattern.compile("GW(\\d+):").matcher(result);
        List<String> gameweeks = m.results().map(r -> r.group(1)).toList();

        assertThat(gameweeks)
                .as("a double gameweek is two fixture lines, but still one gameweek")
                .hasSize(2)
                .containsOnly(String.valueOf(TapasSeed.DOUBLE_GAMEWEEK));
    }
}
