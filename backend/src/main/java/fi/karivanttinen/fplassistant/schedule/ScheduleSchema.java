package fi.karivanttinen.fplassistant.schedule;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Idempotent DDL for {@code fpl_gameweek} and {@code fpl_fixture}: the season's gameweeks and
 * fixtures as FPL publishes them, captured daily. Same shape as {@code DailyPriceSchema} —
 * every statement {@code IF NOT EXISTS}, table names parameterised so the test profile writes
 * to {@code _test} tables.
 *
 * <p><b>Why these exist.</b> {@code GameweekTools} and {@code FixtureTools} read tapas-owned
 * tables that are synced weekly and empty for weeks across the season changeover, so the
 * assistant had no current-season deadline or fixture until tapas caught up. These are the
 * schedule counterpart of {@code fpl_daily_price}: read straight from FPL, keyed by a season
 * code derived from the date, owing tapas nothing.
 *
 * <p><b>Snapshot, not a series.</b> One row per gameweek and per fixture per season, overwritten
 * on every capture; a fixture FPL stops publishing is deleted in the same transaction. Prices
 * need history, a fixture list needs to be current. {@code is_current}/{@code is_next} are
 * stored as published but the tools derive status from {@code deadline_time} at read time, so a
 * deadline that passed this morning is reported as passed this morning, not tomorrow at 02:10.
 */
@Component
public class ScheduleSchema {

    private final JdbcTemplate jdbc;
    private final String gameweekTable;
    private final String fixtureTable;

    public ScheduleSchema(JdbcTemplate jdbc,
                          @Value("${fpl.schedule.gameweek-table-name}") String gameweekTable,
                          @Value("${fpl.schedule.fixture-table-name}") String fixtureTable) {
        this.jdbc = jdbc;
        this.gameweekTable = gameweekTable;
        this.fixtureTable = fixtureTable;
    }

    @PostConstruct
    public void ensureSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    season VARCHAR(10) NOT NULL,
                    id INTEGER NOT NULL,
                    name VARCHAR(50),
                    deadline_time TIMESTAMPTZ NOT NULL,
                    finished BOOLEAN NOT NULL DEFAULT false,
                    data_checked BOOLEAN NOT NULL DEFAULT false,
                    is_current BOOLEAN NOT NULL DEFAULT false,
                    is_next BOOLEAN NOT NULL DEFAULT false,
                    average_entry_score INTEGER,
                    highest_score INTEGER,
                    most_captained INTEGER,
                    most_transferred_in INTEGER,
                    captured_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (season, id)
                )
                """.formatted(gameweekTable));
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    season VARCHAR(10) NOT NULL,
                    id INTEGER NOT NULL,
                    gameweek INTEGER,
                    team_h INTEGER NOT NULL,
                    team_a INTEGER NOT NULL,
                    team_h_name VARCHAR(100),
                    team_h_short VARCHAR(10),
                    team_a_name VARCHAR(100),
                    team_a_short VARCHAR(10),
                    team_h_difficulty SMALLINT,
                    team_a_difficulty SMALLINT,
                    kickoff_time TIMESTAMPTZ,
                    started BOOLEAN NOT NULL DEFAULT false,
                    finished BOOLEAN NOT NULL DEFAULT false,
                    team_h_score INTEGER,
                    team_a_score INTEGER,
                    captured_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (season, id)
                )
                """.formatted(fixtureTable));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS finished_provisional BOOLEAN"
                .formatted(fixtureTable));
        // Every fixture read is "this team's fixtures from this gameweek on".
        jdbc.execute(("CREATE INDEX IF NOT EXISTS idx_%s_season_gw ON %s(season, gameweek)")
                .formatted(fixtureTable, fixtureTable));
        // Same reason as every other fpl_ table: PostgREST publishes public tables to the anon
        // key, and RLS with no policies is what makes that exposure empty.
        jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(gameweekTable));
        jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(fixtureTable));
    }
}
