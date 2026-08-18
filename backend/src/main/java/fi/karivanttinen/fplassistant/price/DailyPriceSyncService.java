package fi.karivanttinen.fplassistant.price;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Captures today's FPL prices straight from {@code bootstrap-static} into
 * {@code fpl_daily_price}.
 *
 * <p><b>Why this exists.</b> Nothing else in the project knows today's price. vaastav
 * publishes prices only once a gameweek has been played, and the tapas {@code player} table
 * this project reads is synced weekly by the other project — measured at 85 days stale on
 * 2026-08-18, with the 2026/27 season not yet loaded at all. FPL moves prices every day at
 * 00:00 UK, so a weekly source can be up to seven price changes behind, and price movement is
 * a first-order FPL concern rather than a detail.
 *
 * <p><b>Season code.</b> Derived from the UK date rather than read from the database, so the
 * fetch stays independent of {@code SeasonResolver} and keeps working across the changeover —
 * the window this table exists to cover. FPL seasons open in August, so any month from July
 * onwards belongs to {@code YYYY-YY+1}.
 *
 * <p>The HTTP body is parsed into rows by {@link #toRows}, which is package-private and takes
 * the decoded map so it can be unit-tested against a captured fixture without a network call.
 */
@Component
public class DailyPriceSyncService {

    private static final Logger log = LoggerFactory.getLogger(DailyPriceSyncService.class);

    /** FPL applies price changes at 00:00 UK, so "today" is a UK calendar date. */
    static final ZoneId UK = ZoneId.of("Europe/London");
    private static final int BATCH_SIZE = 1000;

    private final RestClient restClient;
    private final JdbcTemplate jdbc;
    private final String tableName;
    private final String bootstrapUrl;

    public DailyPriceSyncService(RestClient.Builder restClientBuilder, JdbcTemplate jdbc,
                                 @Value("${fpl.daily-price.table-name}") String tableName,
                                 @Value("${fpl.daily-price.bootstrap-url}") String bootstrapUrl) {
        this.restClient = restClientBuilder.build();
        this.jdbc = jdbc;
        this.tableName = tableName;
        this.bootstrapUrl = bootstrapUrl;
    }

    /** @return number of rows written, for logging and tests */
    public int sync() {
        LocalDate today = LocalDate.now(UK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = restClient.get()
                .uri(bootstrapUrl)
                .retrieve()
                .body(Map.class);

        if (body == null) {
            log.warn("daily price sync: bootstrap-static returned an empty body; nothing written");
            return 0;
        }

        List<Object[]> rows = toRows(body, today, seasonCode(today));
        if (rows.isEmpty()) {
            log.warn("daily price sync: no elements in the response; nothing written");
            return 0;
        }

        int written = upsertAll(rows);
        log.info("daily price sync: {} prices captured for {}", written, today);
        return written;
    }

    /**
     * Maps the decoded {@code bootstrap-static} body to upsert argument arrays.
     *
     * <p>Team names are resolved from the response's own {@code teams} block rather than the
     * tapas {@code team} table — the whole point of this path is to owe nothing to a source
     * that may not have been synced yet.
     */
    static List<Object[]> toRows(Map<String, Object> body, LocalDate priceDate, String season) {
        Map<Integer, String> teamShortNames = new HashMap<>();
        Object teams = body.get("teams");
        if (teams instanceof List<?> teamList) {
            for (Object t : teamList) {
                if (t instanceof Map<?, ?> team) {
                    Integer id = asInt(team.get("id"));
                    if (id != null) {
                        teamShortNames.put(id, asString(team.get("short_name")));
                    }
                }
            }
        }

        Object elements = body.get("elements");
        if (!(elements instanceof List<?> elementList)) {
            return List.of();
        }

        List<Object[]> rows = new ArrayList<>(elementList.size());
        for (Object o : elementList) {
            if (!(o instanceof Map<?, ?> e)) {
                continue;
            }
            Integer id = asInt(e.get("id"));
            Integer nowCost = asInt(e.get("now_cost"));
            if (id == null || nowCost == null) {
                continue; // Without an id and a price the row carries nothing.
            }
            Integer teamId = asInt(e.get("team"));
            String first = asString(e.get("first_name"));
            String second = asString(e.get("second_name"));
            String fullName = (first == null || second == null) ? null : first + " " + second;

            rows.add(new Object[]{
                    season,
                    java.sql.Date.valueOf(priceDate),
                    id,
                    asString(e.get("web_name")),
                    fullName,
                    teamId,
                    teamId == null ? null : teamShortNames.get(teamId),
                    asInt(e.get("element_type")),
                    nowCost,
                    zeroIfNull(asInt(e.get("cost_change_event"))),
                    zeroIfNull(asInt(e.get("cost_change_start"))),
                    asDecimal(e.get("selected_by_percent")),
                    asString(e.get("status")),
                    asString(e.get("news"))
            });
        }
        return rows;
    }

    /**
     * FPL seasons run August to May, so July onwards already belongs to the season about to
     * start. Matches the {@code YYYY-YY} form the rest of this codebase uses (e.g. 2026-27).
     */
    static String seasonCode(LocalDate date) {
        int startYear = date.getMonthValue() >= 7 ? date.getYear() : date.getYear() - 1;
        return "%d-%02d".formatted(startYear, (startYear + 1) % 100);
    }

    private int upsertAll(List<Object[]> rows) {
        String sql = """
                INSERT INTO %s (season, price_date, element_id, web_name, full_name,
                                team_id, team_short, element_type, now_cost,
                                cost_change_event, cost_change_start, selected_by_percent,
                                status, news)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (season, price_date, element_id) DO UPDATE SET
                    web_name = EXCLUDED.web_name,
                    full_name = EXCLUDED.full_name,
                    team_id = EXCLUDED.team_id,
                    team_short = EXCLUDED.team_short,
                    element_type = EXCLUDED.element_type,
                    now_cost = EXCLUDED.now_cost,
                    cost_change_event = EXCLUDED.cost_change_event,
                    cost_change_start = EXCLUDED.cost_change_start,
                    selected_by_percent = EXCLUDED.selected_by_percent,
                    status = EXCLUDED.status,
                    news = EXCLUDED.news,
                    captured_at = NOW()
                """.formatted(tableName);

        int written = 0;
        for (int from = 0; from < rows.size(); from += BATCH_SIZE) {
            List<Object[]> batch = rows.subList(from, Math.min(from + BATCH_SIZE, rows.size()));
            for (int c : jdbc.batchUpdate(sql, batch)) {
                written += Math.max(c, 0);
            }
        }
        return written;
    }

    private static Integer zeroIfNull(Integer value) {
        return value == null ? 0 : value;
    }

    private static Integer asInt(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /** FPL sends {@code selected_by_percent} as a string ("34.1"), not a number. */
    private static BigDecimal asDecimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
