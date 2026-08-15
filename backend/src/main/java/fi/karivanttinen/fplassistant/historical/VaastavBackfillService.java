package fi.karivanttinen.fplassistant.historical;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-shot backfill of completed Premier League results from vaastav CSVs into
 * {@code pl_match_result}, 2019-20 through 2025-26 — the range chosen because every one of
 * those seasons has both {@code teams.csv} and {@code fixtures.csv} in the source repository,
 * so no name-based fallback mapping is ever needed. See the Phase 3 spec for the seasons below
 * that floor and why they cost more than they are worth.
 *
 * <p>Only finished matches with both scores present are written — vaastav publishes
 * {@code fixtures.csv} for the season in progress too, entirely unplayed, and that must not
 * become a table full of {@code NULL} scores.
 *
 * <p>Upserts on the natural key {@code (season, home_team_code, away_team_code)}, so running
 * this twice leaves the table unchanged rather than duplicating every row.
 */
@Component
public class VaastavBackfillService {

    private static final Logger log = LoggerFactory.getLogger(VaastavBackfillService.class);

    static final List<String> SEASONS = List.of(
            "2019-20", "2020-21", "2021-22", "2022-23", "2023-24", "2024-25", "2025-26");

    private final VaastavSource source;
    private final JdbcTemplate jdbc;
    private final String tableName;

    public VaastavBackfillService(VaastavSource source, JdbcTemplate jdbc,
                                  @Value("${fpl.historical.table-name}") String tableName) {
        this.source = source;
        this.jdbc = jdbc;
        this.tableName = tableName;
    }

    /** Backfills every season in {@link #SEASONS}. */
    public void backfill() {
        backfill(SEASONS);
    }

    /** Backfills the given seasons — the smaller entry point tests use. */
    public void backfill(List<String> seasons) {
        Map<String, String> teamsCsvBySeason = new LinkedHashMap<>();
        for (String season : seasons) {
            teamsCsvBySeason.put(season, source.fetch(season, "teams.csv"));
        }
        VaastavTeamMapper teamMapper = VaastavTeamMapper.from(teamsCsvBySeason);

        int totalWritten = 0;
        for (String season : seasons) {
            String fixturesCsv = source.fetch(season, "fixtures.csv");
            List<VaastavFixtureRow> rows = VaastavFixtureParser.parse(fixturesCsv, season);
            int written = 0;
            for (VaastavFixtureRow row : rows) {
                if (!isCompleted(row)) {
                    continue;
                }
                upsert(season, row, teamMapper);
                written++;
            }
            log.info("{}: {} completed matches written of {} total rows", season, written, rows.size());
            totalWritten += written;
        }
        log.info("vaastav backfill complete: {} matches across {} seasons", totalWritten, seasons.size());
    }

    private static boolean isCompleted(VaastavFixtureRow row) {
        return row.finished() && row.teamHScore() != null && row.teamAScore() != null;
    }

    private void upsert(String season, VaastavFixtureRow row, VaastavTeamMapper teamMapper) {
        TeamIdentity home = teamMapper.resolve(season, row.teamHId());
        TeamIdentity away = teamMapper.resolve(season, row.teamAId());
        jdbc.update("""
                INSERT INTO %s (season, gameweek, home_team_code, away_team_code,
                                 home_team_name, away_team_name, home_goals, away_goals,
                                 home_difficulty, away_difficulty, kickoff_time, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'vaastav')
                ON CONFLICT (season, home_team_code, away_team_code) DO UPDATE SET
                    gameweek = EXCLUDED.gameweek,
                    home_team_name = EXCLUDED.home_team_name,
                    away_team_name = EXCLUDED.away_team_name,
                    home_goals = EXCLUDED.home_goals,
                    away_goals = EXCLUDED.away_goals,
                    home_difficulty = EXCLUDED.home_difficulty,
                    away_difficulty = EXCLUDED.away_difficulty,
                    kickoff_time = EXCLUDED.kickoff_time,
                    source = 'vaastav',
                    ingested_at = NOW()
                """.formatted(tableName), // tableName is config-injected, not user input
                season, row.gameweek(), home.code(), away.code(),
                home.name(), away.name(), row.teamHScore(), row.teamAScore(),
                row.teamHDifficulty(), row.teamADifficulty(), parseKickoff(row.kickoffTime()));
    }

    private static Timestamp parseKickoff(String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return Timestamp.from(Instant.parse(iso));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
