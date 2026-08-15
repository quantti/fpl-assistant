package fi.karivanttinen.fplassistant.historical;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Idempotent DDL for {@code pl_match_result}, run once at startup — the same pattern
 * {@code PgVectorStore} uses for {@code fpl_embeddings}, since this project has no migration
 * tool. Safe to run on every boot: every statement is {@code IF NOT EXISTS}.
 *
 * <p>{@code season} is {@code VARCHAR(10)} holding the {@code season.code} format (e.g.
 * {@code '2019-20'}) and is deliberately <b>not</b> a foreign key to {@code season} — no rows
 * exist there for any season before 2025-26, so a FK would reject every historical row. Team
 * identity is the persistent FPL {@code code}, not the season-scoped {@code team.id}; club
 * names are denormalised onto the row because no {@code team} row exists for historical seasons
 * to join against. See the Phase 3 spec's Schema section for the reasoning behind each column.
 *
 * <p>{@code UNIQUE (season, home_team_code, away_team_code)} is the natural key both importers
 * upsert on — see {@code IngestionService}'s sibling reasoning in the RAG package for why an
 * upsert keyed this way, not an insert keyed on a source id, is what keeps two overlapping data
 * sources from double-counting the same match.
 *
 * <p>Unlike {@code fpl_embeddings}, this table is never truncated — both importers only ever
 * upsert. A test writing directly to the production table would not destroy anything, but would
 * pollute it with fictitious matches permanently. So the table name is still parameterised and
 * pinned to an isolated name under the {@code test} profile, for the same reason Phase 2 pinned
 * {@code fpl_embeddings_test}.
 */
@Component
public class HistoricalSchema {

    private static final Logger log = LoggerFactory.getLogger(HistoricalSchema.class);

    private final JdbcTemplate jdbc;
    private final String tableName;

    public HistoricalSchema(JdbcTemplate jdbc,
                            @Value("${fpl.historical.table-name}") String tableName) {
        this.jdbc = jdbc;
        this.tableName = tableName;
    }

    @PostConstruct
    void ensureSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    id BIGSERIAL PRIMARY KEY,
                    season VARCHAR(10) NOT NULL,
                    gameweek INTEGER,
                    home_team_code INTEGER NOT NULL,
                    away_team_code INTEGER NOT NULL,
                    home_team_name VARCHAR(100) NOT NULL,
                    away_team_name VARCHAR(100) NOT NULL,
                    home_goals INTEGER NOT NULL,
                    away_goals INTEGER NOT NULL,
                    home_difficulty INTEGER,
                    away_difficulty INTEGER,
                    kickoff_time TIMESTAMPTZ,
                    source VARCHAR(20) NOT NULL,
                    ingested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    CONSTRAINT uq_%s_season_teams UNIQUE (season, home_team_code, away_team_code)
                )
                """.formatted(tableName, tableName));
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_%s_home_code ON %s(home_team_code)"
                .formatted(tableName, tableName));
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_%s_away_code ON %s(away_team_code)"
                .formatted(tableName, tableName));
        log.debug("{} schema ensured", tableName);
    }
}
