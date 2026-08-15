package dev.quantti.fplassistant.historical;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end backfill against the live Supabase test table, pinned to
 * {@code pl_match_result_test} by the {@code test} profile. Uses a {@link VaastavSource} that
 * reads the real CSV fixtures checked into {@code src/test/resources/vaastav-fixtures/} instead
 * of hitting the network.
 */
@SpringBootTest
@ActiveProfiles("test")
class VaastavBackfillIntegrationTest {

    @Autowired
    VaastavBackfillService backfillService;

    @Autowired
    JdbcTemplate jdbc;

    @Value("${fpl.historical.table-name}")
    String tableName;

    /** Only the two seasons with local fixtures — the full 2019-20..2025-26 range is real-world usage. */
    private static final List<String> TEST_SEASONS = List.of("2019-20", "2025-26");

    @BeforeEach
    void refuseToRunAgainstProduction() {
        assertThat(tableName)
                .as("backfill tests must be pinned to the isolated table; check @ActiveProfiles(\"test\")")
                .endsWith("_test");
        jdbc.update("DELETE FROM " + tableName + " WHERE source = 'vaastav'");
    }

    @TestConfiguration
    static class FileBackedSource {
        @Bean
        @Primary
        VaastavSource fileVaastavSource() {
            return (season, fileName) -> {
                try {
                    return Files.readString(Path.of(
                            "src/test/resources/vaastav-fixtures/" + season + "-"
                                    + fileName.replace(".csv", "") + ".csv"));
                } catch (IOException e) {
                    throw new UncheckedIOException(season + "/" + fileName + " not found in test fixtures", e);
                }
            };
        }
    }

    @Test
    void backfillWritesCompletedMatchesOnlyAndMatchesFixtureExactly() {
        backfillService.backfill(TEST_SEASONS);

        Integer written = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + tableName + " WHERE season = '2025-26' AND source = 'vaastav'",
                Integer.class);
        assertThat(written).isEqualTo(380);

        // The oracle: every 2025-26 row must match `fixture` exactly on both scores, joined
        // through team.code (fixture.team_h/team_a are season-scoped ids, not codes). Both
        // ultimately derive from the same FPL API, so any mismatch here is an import bug.
        Integer matching = jdbc.queryForObject("""
                SELECT COUNT(*) FROM %s pmr
                JOIN season s ON s.code = pmr.season
                JOIN fixture f ON f.season_id = s.id
                JOIN team th ON th.id = f.team_h AND th.season_id = f.season_id
                JOIN team ta ON ta.id = f.team_a AND ta.season_id = f.season_id
                WHERE pmr.season = '2025-26'
                  AND pmr.source = 'vaastav'
                  AND th.code = pmr.home_team_code
                  AND ta.code = pmr.away_team_code
                  AND f.team_h_score = pmr.home_goals
                  AND f.team_a_score = pmr.away_goals
                """.formatted(tableName), Integer.class);
        assertThat(matching).isEqualTo(380);
    }

    @Test
    void reRunningLeavesTheTableUnchanged() {
        backfillService.backfill(TEST_SEASONS);
        Integer firstRun = countRows();

        backfillService.backfill(TEST_SEASONS);
        Integer secondRun = countRows();

        assertThat(secondRun).isEqualTo(firstRun);
    }

    @Test
    void resolvesARelegatedClubNotInTheCurrentSeason() {
        backfillService.backfill(List.of("2019-20"));

        Integer watfordMatches = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + tableName
                        + " WHERE season = '2019-20' AND (home_team_name = 'Watford' OR away_team_name = 'Watford')",
                Integer.class);
        assertThat(watfordMatches).isGreaterThan(0);
    }

    private int countRows() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + tableName + " WHERE source = 'vaastav'", Integer.class);
        return count == null ? 0 : count;
    }
}
