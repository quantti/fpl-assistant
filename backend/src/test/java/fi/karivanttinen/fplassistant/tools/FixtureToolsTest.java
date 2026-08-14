package fi.karivanttinen.fplassistant.tools;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FixtureToolsTest {

    @Autowired
    FixtureTools fixtureTools;

    @Autowired
    SeasonResolver seasonResolver;

    @Test
    void getUpcomingFixturesForKnownTeam() {
        String result = fixtureTools.getUpcomingFixtures("Arsenal", 5);

        assertThat(result).contains("Arsenal");
        assertThat(result).doesNotContain("not found");
        assertThat(result).containsPattern("GW\\d+:");
    }

    @Test
    void everyAnswerNamesTheSeasonItCameFrom() {
        String result = fixtureTools.getUpcomingFixtures("Arsenal", 5);
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

    @Test
    void nextNBoundsDistinctGameweeksNotFixtureRows() {
        String result = fixtureTools.getUpcomingFixtures("Arsenal", 1);

        // Guard against a vacuous pass: an empty result would also satisfy the size bound.
        assertThat(result).doesNotContain("not found");
        assertThat(result).containsPattern("GW\\d+:");

        Matcher m = Pattern.compile("GW(\\d+):").matcher(result);
        Set<String> gameweeks = m.results()
                .map(r -> r.group(1))
                .collect(Collectors.toSet());
        // In a double gameweek this may be two fixture lines, but never two gameweeks.
        assertThat(gameweeks).hasSizeLessThanOrEqualTo(1);
    }
}
