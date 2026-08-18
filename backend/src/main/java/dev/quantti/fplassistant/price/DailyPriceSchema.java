package dev.quantti.fplassistant.price;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Idempotent DDL for {@code fpl_daily_price}, the daily price series fetched straight from
 * FPL's own {@code bootstrap-static} endpoint. Same shape as {@link PriceSchema}: every
 * statement is {@code IF NOT EXISTS}, and the table name is parameterised so the {@code test}
 * profile writes to an isolated {@code _test} table.
 *
 * <p><b>Why a separate table rather than another {@code source} in {@code fpl_player_price}.</b>
 * That table is keyed by gameweek and written only when {@code SeasonContext.currentSeason()}
 * is true, because both of its writers depend on the shared {@code player} table for the season
 * id and the player name. This one depends on nothing but the FPL API: it is keyed by calendar
 * date, it carries its own denormalised names, and it must keep working during the season
 * changeover — exactly when the shared-backed path goes quiet. Keeping them apart means a
 * gap in one never silently becomes a gap in the other.
 *
 * <p><b>Grain: one row per player per UK calendar date.</b> FPL changes prices once a day at
 * 00:00 UK (moved from ~01:30 for 2026/27), so a date is the natural key — {@code price_date}
 * is the UK date the price was in effect when captured, not the instant of capture, which is
 * kept separately in {@code captured_at}. Re-running the sync on the same day overwrites that
 * day's row rather than appending, so a retry after a failure is free.
 *
 * <p>{@code cost_change_event} and {@code cost_change_start} are FPL's own counters (change so
 * far this gameweek, and since the season opened) and are stored rather than derived: FPL is
 * authoritative about its own price movements, and deriving deltas from our series would go
 * wrong for any day the fetch did not run.
 *
 * <p><b>This table also carries the daily injury and availability picture, and that is a
 * deliberate second purpose rather than a by-product.</b> {@code bootstrap-static} returns
 * {@code status} and {@code news} on the same elements as the price, so capturing them costs
 * no extra request and gives them the same daily cadence:
 *
 * <ul>
 *   <li>{@code status} — FPL's availability code: {@code a} available, {@code d} doubtful,
 *       {@code i} injured, {@code s} suspended, {@code u} unavailable, {@code n} on loan or
 *       not in the squad. 103 of 590 players carried a non-{@code a} status on 2026-08-18.</li>
 *   <li>{@code news} — the free-text note FPL publishes with a flag ("Knock - 75% chance of
 *       playing"), empty for an available player.</li>
 * </ul>
 *
 * <p>This matters as much as the price. The shared {@code player} table is this project's only
 * other source for availability and is synced weekly, so a week-old {@code status} would tell
 * a user a player is fit after he has been ruled out — the kind of wrong answer that costs a
 * gameweek. Because the rows are dated, the series also answers questions a single snapshot
 * cannot: when a flag first appeared, and whether a doubt is clearing or worsening.
 *
 * <p>Nothing reads these columns yet; the capture runs ahead of the tools so the history
 * exists when they want it. Backfill is impossible — FPL publishes only today's state — which
 * is the whole argument for capturing daily from the start.
 *
 * <p>Never truncated; the writer only upserts. The series is the point — a price on its own
 * says nothing, a price with yesterday's beside it says whether the player is rising.
 */
@Component
public class DailyPriceSchema {

    private static final Logger log = LoggerFactory.getLogger(DailyPriceSchema.class);

    private final JdbcTemplate jdbc;
    private final String tableName;

    public DailyPriceSchema(JdbcTemplate jdbc,
                            @Value("${fpl.daily-price.table-name}") String tableName) {
        this.jdbc = jdbc;
        this.tableName = tableName;
    }

    @PostConstruct
    void ensureSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    price_date DATE NOT NULL,
                    element_id INTEGER NOT NULL,
                    season VARCHAR(10) NOT NULL,
                    web_name VARCHAR(100) NOT NULL,
                    full_name VARCHAR(200),
                    team_id INTEGER,
                    team_short VARCHAR(10),
                    element_type SMALLINT,
                    now_cost INTEGER NOT NULL,
                    cost_change_event INTEGER NOT NULL DEFAULT 0,
                    cost_change_start INTEGER NOT NULL DEFAULT 0,
                    selected_by_percent NUMERIC(5,2),
                    status VARCHAR(4),
                    news TEXT,
                    captured_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (season, price_date, element_id)
                )
                """.formatted(tableName));
        // Per-player history: "show me this player's price over the last N days" reads the
        // series backwards, which the primary key (season, price_date, element_id) cannot serve.
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_%s_element_date ON %s(element_id, price_date DESC)"
                .formatted(tableName, tableName));
        // "Who moved today" scans one date across all players.
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_%s_date_change ON %s(price_date, cost_change_event)"
                .formatted(tableName, tableName));
        log.debug("{} schema ensured", tableName);
    }
}
