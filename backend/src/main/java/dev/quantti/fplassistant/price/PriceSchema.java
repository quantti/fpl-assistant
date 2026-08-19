package dev.quantti.fplassistant.price;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Idempotent DDL for {@code fpl_player_price}, run once at startup — the same pattern
 * {@code HistoricalSchema} uses for {@code pl_match_result}. Safe on every boot: every
 * statement is {@code IF NOT EXISTS}.
 *
 * <p>Tracks only the season currently being played. The key is the season-scoped FPL element
 * id (vaastav's {@code element} / tapas {@code player.id}), <b>not</b> the persistent FPL
 * {@code code}: the tapas {@code player} table does not carry {@code code}, and since this
 * table only ever holds one season, cross-season identity is not needed. {@code gameweek = 0}
 * is the latest live snapshot of {@code player.now_cost}; {@code gameweek = 1..38} are the
 * official end-of-GW prices from vaastav's {@code merged_gw.csv}.
 *
 * <p>Like {@code pl_match_result}, this table is never truncated — both writers only upsert.
 * The name is parameterised and pinned to an isolated {@code _test} version under the
 * {@code test} profile, for the same reason Phase 2/3 pin their tables.
 */
@Component
public class PriceSchema {

    private static final Logger log = LoggerFactory.getLogger(PriceSchema.class);

    private final JdbcTemplate jdbc;
    private final String tableName;

    public PriceSchema(JdbcTemplate jdbc,
                       @Value("${fpl.price.table-name}") String tableName) {
        this.jdbc = jdbc;
        this.tableName = tableName;
    }

    @PostConstruct
    void ensureSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    id BIGSERIAL PRIMARY KEY,
                    season VARCHAR(10) NOT NULL,
                    gameweek INTEGER NOT NULL,
                    element_id INTEGER NOT NULL,
                    player_name VARCHAR(100) NOT NULL,
                    price INTEGER NOT NULL,
                    source VARCHAR(20) NOT NULL,
                    ingested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    CONSTRAINT uq_%s_season_gw_element_source UNIQUE (season, gameweek, element_id, source)
                )
                """.formatted(tableName, tableName));
        // PostgREST publishes every table in `public` to the anon key, which is public by
        // design. RLS with no policies is what makes that exposure empty; without it a fresh
        // database is born open (measured 2026-08-19, see docs/STATUS.md). A no-op for this
        // application, which connects as a role with rolbypassrls.
        jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(tableName));

        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_%s_season_element ON %s(season, element_id)"
                .formatted(tableName, tableName));
        log.debug("{} schema ensured", tableName);
    }
}
