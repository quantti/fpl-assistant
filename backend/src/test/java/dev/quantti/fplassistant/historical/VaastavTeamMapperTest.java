package dev.quantti.fplassistant.historical;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests against real {@code teams.csv} fixtures (2019-20 and 2025-26, saved under
 * {@code src/test/resources/vaastav-fixtures/}) — the bugs this class can have are all in the
 * real files' shape, not in hand-written rows.
 */
class VaastavTeamMapperTest {

    private static String readFixture(String name) {
        try {
            return Files.readString(Path.of("src/test/resources/vaastav-fixtures/" + name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void resolvesAClubPresentInBothSeasons() {
        VaastavTeamMapper mapper = VaastavTeamMapper.from(Map.of(
                "2019-20", readFixture("2019-20-teams.csv"),
                "2025-26", readFixture("2025-26-teams.csv")));

        // Arsenal is vaastav id 1 in both files, and code 3 in both — the whole point of code
        // over id: the id assignment is per-season alphabetical, code is not.
        assertThat(mapper.resolve("2019-20", 1)).isEqualTo(new TeamIdentity(3, "Arsenal"));
        assertThat(mapper.resolve("2025-26", 1)).isEqualTo(new TeamIdentity(3, "Arsenal"));
    }

    @Test
    void resolvesARelegatedClubOnlyPresentInTheOlderSeason() {
        VaastavTeamMapper mapper = VaastavTeamMapper.from(Map.of(
                "2019-20", readFixture("2019-20-teams.csv")));

        // Watford and Norwich were both relegated after 2019-20 and do not appear in the
        // current season's team table — exactly the case FixtureTools' resolver cannot serve.
        assertThat(mapper.resolve("2019-20", 18)).isEqualTo(new TeamIdentity(57, "Watford"));
        assertThat(mapper.resolve("2019-20", 14)).isEqualTo(new TeamIdentity(45, "Norwich"));
    }

    @Test
    void idReassignmentAcrossSeasonsDoesNotCauseCrossTalk() {
        VaastavTeamMapper mapper = VaastavTeamMapper.from(Map.of(
                "2019-20", readFixture("2019-20-teams.csv"),
                "2025-26", readFixture("2025-26-teams.csv")));

        // id 3 is Bournemouth in 2019-20 but Burnley in 2025-26 - if seasons were ever merged
        // into one lookup instead of kept separate, this pair would silently swap.
        assertThat(mapper.resolve("2019-20", 3).name()).isEqualTo("Bournemouth");
        assertThat(mapper.resolve("2025-26", 3).name()).isEqualTo("Burnley");
    }

    @Test
    void unknownVaastavIdFailsNamingTheSeasonAndId() {
        VaastavTeamMapper mapper = VaastavTeamMapper.from(Map.of(
                "2019-20", readFixture("2019-20-teams.csv")));

        assertThatThrownBy(() -> mapper.resolve("2019-20", 999))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2019-20")
                .hasMessageContaining("999");
    }

    @Test
    void unloadedSeasonFailsNamingTheSeason() {
        VaastavTeamMapper mapper = VaastavTeamMapper.from(Map.of(
                "2019-20", readFixture("2019-20-teams.csv")));

        assertThatThrownBy(() -> mapper.resolve("2018-19", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2018-19");
    }

    @Test
    void allTwentyClubsResolveInARealSeasonFile() {
        Map<Integer, TeamIdentity> teams = VaastavTeamMapper.parseSeason(
                readFixture("2019-20-teams.csv"), "2019-20");

        assertThat(teams).hasSize(20);
        assertThat(teams.values()).extracting(TeamIdentity::code).doesNotHaveDuplicates();
    }
}
