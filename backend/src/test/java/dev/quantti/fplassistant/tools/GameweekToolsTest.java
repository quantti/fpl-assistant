package dev.quantti.fplassistant.tools;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import dev.quantti.fplassistant.schedule.ScheduleSource;
import dev.quantti.fplassistant.testsupport.DataAgeClock;
import dev.quantti.fplassistant.testsupport.TapasSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class GameweekToolsTest {

    static final Instant AS_OF = DataAgeClock.AS_OF;

    /** Built with the fixed clock rather than autowired; see {@link DataAgeClock}. */
    GameweekTools gameweekTools;

    @Autowired
    SeasonResolver seasonResolver;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ScheduleSource schedule;

    @BeforeEach
    void buildTools() {
        gameweekTools = new GameweekTools(jdbc, seasonResolver, schedule, DataAgeClock.provider());
    }

    @Value("${fpl.schedule.gameweek-table-name}")
    String scheduleTable;

    @BeforeEach
    void seed() {
        TapasSeed.ensure(jdbc);
        // The tapas path is what this class tests; it is taken only while no schedule capture
        // exists. This project's own _test table, so clearing it is allowed.
        jdbc.update("DELETE FROM " + scheduleTable);
    }

    @Test
    void getGameweekInfoReturnsGameweekData() {
        String result = gameweekTools.getGameweekInfo();

        // The seeded season has gameweek 2 current and gameweek 3 next; the tool reports both.
        assertThat(result).contains("Gameweek 2").contains("Gameweek 3");
        assertThat(result).contains("Status: NEXT");
        // The plan's original assertion (containsAnyOf "current", "next", "Gameweek") also passed
        // on the empty-data message, because that message contains the word "current". Assert the
        // absence of that message explicitly instead.
        assertThat(result).doesNotContain("No gameweek data");
    }

    @Test
    void everyAnswerNamesTheSeasonItCameFrom() {
        String result = gameweekTools.getGameweekInfo();
        SeasonContext season = seasonResolver.resolve();

        assertThat(result).startsWith("Data source: season " + season.code());
    }

    /**
     * The seed reproduces the production changeover deliberately: the current season exists but
     * holds no gameweeks or players, so the resolver falls back to the completed one and the
     * provenance line has to say so.
     */
    @Test
    void fallbackToAPreviousSeasonIsLabelledAsHistorical() {
        SeasonContext season = seasonResolver.resolve();

        assertThat(season.code()).isEqualTo(TapasSeed.POPULATED_SEASON_CODE);
        assertThat(season.currentSeason()).isFalse();
        assertThat(season.provenance())
                .contains("COMPLETED PREVIOUS season")
                .contains(TapasSeed.CURRENT_SEASON_CODE);
    }

    /** Each returned row is dated by its own write; the gameweek table has no update trigger. */
    @Test
    @Transactional
    void eachFallbackGameweekRowCarriesItsOwnWriteAge() {
        writtenAt(2, Instant.parse("2026-09-07T12:00:00Z"));
        writtenAt(3, Instant.parse("2026-07-12T12:00:00Z"));

        String result = gameweekTools.getGameweekInfo();

        String current = "Stored-data age: gameweek row Gameweek 2 (id=2), season " + TapasSeed.POPULATED_SEASON_CODE
                + ", last recorded write 2026-09-07 13:00 UK (3 days ago)."
                + " This is a database record time, not a live FPL check.";
        String next = "Stored-data age: gameweek row Gameweek 3 (id=3), season " + TapasSeed.POPULATED_SEASON_CODE
                + ", last recorded write 2026-07-12 13:00 UK (60 days ago).";
        assertThat(result).containsOnlyOnce(current).containsOnlyOnce(next);
        int separator = result.indexOf("\n---\n");
        assertThat(result.indexOf(current)).isBetween(result.indexOf("Gameweek 2 (id=2)"), separator);
        assertThat(result.indexOf(next)).isGreaterThan(result.indexOf("Gameweek 3 (id=3)"));
        assertThat(result).containsOnlyOnce(DataAge.GAMEWEEK_CAVEAT);
        assertThat(result).endsWith(DataAge.GAMEWEEK_CAVEAT);
        // Values and status are unchanged by the age lines.
        assertThat(result).contains("Status: FINISHED").contains("Status: NEXT")
                .contains("Average score: 58 | Highest: 131");
    }

    @Test
    @Transactional
    void aNullGameweekWriteTimeStaysUnknown() {
        writtenAt(3, null);

        String result = gameweekTools.getGameweekInfo();

        assertThat(result).contains("Stored-data age: gameweek row Gameweek 3 (id=3), season "
                + TapasSeed.POPULATED_SEASON_CODE + ", write time unavailable.");
    }

    private void writtenAt(int gameweekId, Instant at) {
        OffsetDateTime value = at == null ? null : OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
        int updated = jdbc.update("UPDATE gameweek SET updated_at = ? WHERE id = ? AND season_id = ?",
                value, gameweekId, TapasSeed.POPULATED_SEASON_ID);
        assertThat(updated).isOne();
        assertThat(jdbc.queryForObject("SELECT updated_at FROM gameweek WHERE id = ? AND season_id = ?",
                OffsetDateTime.class, gameweekId, TapasSeed.POPULATED_SEASON_ID))
                .as("the seeded write time must stick").isEqualTo(value);
    }
}
