package dev.quantti.fplassistant.tools;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Shared reader for {@code fpl_daily_price} — the daily capture from FPL's own API — used by
 * {@link PriceTools} and {@link AvailabilityTools}.
 *
 * <p><b>Why these tools do not resolve players through the tapas {@code player} table.</b>
 * That table is synced weekly by {@code tapas-fpl-app} and, measured on 2026-08-18, held zero
 * rows for season 2026-27 while the season was three days from kicking off — so a name lookup
 * against it returned "player not found" for every current-season player. {@code fpl_daily_price}
 * carries its own {@code web_name}/{@code full_name}, captured in the same request as the price,
 * so a lookup here works whenever the capture has run.
 *
 * <p><b>Why these tools do not use {@link SeasonResolver} either.</b> The resolver falls back to
 * the most recent season holding data, which during the changeover is the completed previous
 * season — it would have answered "2025-26, a completed season" over prices captured today for
 * 2026-27. The daily table names its own season on every row, so the season is read from the
 * data being reported rather than resolved separately. This is the same reasoning that makes
 * {@code HistoricalTools} a documented exception in {@code AGENTS.md}: the provenance discipline
 * exists so numbers are attributed correctly, and here the resolver would attribute them wrongly.
 */
@Component
public class DailyPriceSource {

    static final int MAX_NAME_MATCHES = 4;

    private final JdbcTemplate jdbc;
    private final String table;

    public DailyPriceSource(JdbcTemplate jdbc,
                            @Value("${fpl.daily-price.table-name}") String table) {
        this.jdbc = jdbc;
        this.table = table;
    }

    String table() {
        return table;
    }

    /**
     * The most recent captured date, or {@code null} when the table is empty (no capture has
     * run yet). Every read is anchored to this rather than to "today" so a missed 02:00 run
     * degrades to yesterday's prices — clearly labelled — instead of an empty answer.
     */
    LocalDate latestCaptureDate() {
        return jdbc.queryForObject(
                "SELECT MAX(price_date) FROM %s".formatted(table), LocalDate.class);
    }

    /** Best name matches on the given date, best first; empty when nothing matched. */
    List<Map<String, Object>> matchPlayers(String playerName, LocalDate date) {
        String pattern = "%" + LikePatterns.escape(playerName.toLowerCase(Locale.ROOT)) + "%";
        return jdbc.queryForList("""
                SELECT element_id, web_name, full_name, team_short, element_type,
                       now_cost, cost_change_event, cost_change_start,
                       selected_by_percent, status, news, season, price_date
                FROM %s
                WHERE price_date = ?
                  AND (LOWER(web_name) LIKE ? OR LOWER(full_name) LIKE ?)
                ORDER BY now_cost DESC
                LIMIT ?
                """.formatted(table), date, pattern, pattern, MAX_NAME_MATCHES);
    }

    /**
     * States which day's capture the numbers come from, and how old it is.
     *
     * <p>This is the daily-capture counterpart of {@code SeasonContext.provenance()}: it names
     * the season and the exact capture date rather than the season alone, because for a value
     * that moves every night at 00:00 UK the day matters as much as the season. A capture more
     * than a day old is called out explicitly — a stale price presented as current is the
     * failure this whole table exists to prevent.
     */
    static String provenance(String season, LocalDate captured, LocalDate today) {
        // Clamped at zero: a capture dated ahead of "today" means clock skew between the
        // container and the database, and "captured -1 days ago" is worse than useless in an
        // answer a user is meant to trust.
        long daysOld = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(captured, today));
        String age = switch ((int) Math.min(daysOld, 2)) {
            case 0 -> "captured today";
            case 1 -> "captured yesterday — FPL has changed prices since, at 00:00 UK";
            default -> "captured %d days ago — FPL has changed prices since, at 00:00 UK each day"
                    .formatted(daysOld);
        };
        return "Data source: FPL live prices for season %s, %s (%s). FPL changes prices once a day at 00:00 UK."
                .formatted(season, age, captured);
    }
}
