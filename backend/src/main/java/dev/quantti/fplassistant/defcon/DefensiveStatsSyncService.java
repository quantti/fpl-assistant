package dev.quantti.fplassistant.defcon;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Fills the defensive-contribution columns on {@code player_fixture_stats} from FPL's
 * {@code element-summary} endpoint — the same endpoint tapas already reads for the rest of that
 * table, so this adds no new source, only the fields tapas' column list omits.
 *
 * <p><b>UPDATE only, never INSERT.</b> Tapas owns the rows; this owns four columns on them. A
 * player whose gameweek tapas has not synced yet simply has nothing to update, and the count in
 * the log says so. Creating rows here would mean inventing values for columns this class knows
 * nothing about ({@code value}, {@code selected}, the points breakdown) and would put two writers
 * on the same key.
 *
 * <p><b>One request per player, and that is the point.</b> {@code bootstrap-static} carries the
 * same four fields as season totals in a single request, but defensive contribution is a
 * threshold per match — 2 points at 10 actions for a defender, 12 for a midfielder or forward —
 * so a season mean cannot say how often a player crossed it. A player steady on 9.8 scores less
 * often than one averaging 9.5 with a wide spread. The per-match rows are the only shape that
 * answers the question, and they can be backfilled, which a daily total never can.
 *
 * <p>Requests are spaced by {@code fpl.defcon.request-delay-ms} and the season's player list
 * bounds them: only players who already have a row in the current season are fetched, so this
 * asks FPL for nothing it cannot use.
 */
@Component
public class DefensiveStatsSyncService {

    private static final Logger log = LoggerFactory.getLogger(DefensiveStatsSyncService.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);
    private static final int BATCH_SIZE = 500;

    private final RestClient restClient;
    private final JdbcTemplate jdbc;
    private final String summaryUrl;
    private final long requestDelayMs;

    public DefensiveStatsSyncService(RestClient.Builder restClientBuilder, JdbcTemplate jdbc,
                                     @Value("${fpl.defcon.element-summary-url}") String summaryUrl,
                                     @Value("${fpl.defcon.request-delay-ms}") long requestDelayMs) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        this.restClient = restClientBuilder.requestFactory(factory).build();
        this.jdbc = jdbc;
        this.summaryUrl = summaryUrl;
        this.requestDelayMs = requestDelayMs;
    }

    /** @return number of rows updated, for logging and tests */
    public int sync() {
        Integer seasonId = currentSeasonId();
        if (seasonId == null) {
            log.warn("defcon sync: no season is flagged is_current; nothing written");
            return 0;
        }
        List<Integer> players = playersWithFixtures(seasonId);
        if (players.isEmpty()) {
            log.warn("defcon sync: season {} has no player_fixture_stats rows yet;"
                    + " nothing to update", seasonId);
            return 0;
        }

        List<Object[]> updates = new ArrayList<>();
        int fetched = 0;
        int failed = 0;
        for (Integer playerId : players) {
            List<Object[]> rows = fetchPlayer(playerId, seasonId);
            if (rows == null) {
                failed++;
                continue;
            }
            fetched++;
            updates.addAll(rows);
            pause();
        }

        int updated = applyAll(updates);
        log.info("defcon sync: {} rows updated for season {} ({} players fetched, {} failed)",
                updated, seasonId, fetched, failed);
        return updated;
    }

    /**
     * The season the database flags as current, not the one {@link
     * dev.quantti.fplassistant.tools.SeasonResolver} resolves. Same reasoning as
     * {@code FootballDataSyncService}: the resolver falls back to a completed season while the
     * new one holds no data, so keying a write on it would stamp this season's defensive numbers
     * onto last season's rows.
     */
    private Integer currentSeasonId() {
        List<Integer> ids = jdbc.queryForList(
                "SELECT id FROM season WHERE is_current = true ORDER BY id DESC LIMIT 1",
                Integer.class);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private List<Integer> playersWithFixtures(int seasonId) {
        return jdbc.queryForList(
                "SELECT DISTINCT player_id FROM player_fixture_stats WHERE season_id = ?"
                        + " ORDER BY player_id",
                Integer.class, seasonId);
    }

    /** @return update arguments for this player, or null if the fetch failed */
    private List<Object[]> fetchPlayer(int playerId, int seasonId) {
        Map<String, Object> body;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri(summaryUrl, playerId)
                    .retrieve()
                    .body(Map.class);
            body = response;
        } catch (RestClientException e) {
            // Never logs the URI or the exception body at warn: one player failing is routine
            // (FPL 404s an element that has left the game) and must not drown the run.
            log.debug("defcon sync: element-summary failed for player {}", playerId, e);
            return null;
        }
        if (body == null) {
            return null;
        }
        return toUpdates(body, playerId, seasonId);
    }

    /**
     * Maps one {@code element-summary} body to update arguments. Package-private and taking the
     * decoded map so the mapping is unit-testable against a captured fixture without a network
     * call — the same shape {@code DailyPriceSyncService.toRows} uses.
     */
    static List<Object[]> toUpdates(Map<String, Object> body, int playerId, int seasonId) {
        List<Object[]> updates = new ArrayList<>();
        if (!(body.get("history") instanceof List<?> history)) {
            return updates;
        }
        for (Object entry : history) {
            if (!(entry instanceof Map<?, ?> row)) {
                continue;
            }
            Integer fixtureId = asInt(row.get("fixture"));
            if (fixtureId == null) {
                continue;
            }
            updates.add(new Object[] {
                    asInt(row.get("defensive_contribution")),
                    asInt(row.get("clearances_blocks_interceptions")),
                    asInt(row.get("recoveries")),
                    asInt(row.get("tackles")),
                    fixtureId, playerId, seasonId,
            });
        }
        return updates;
    }

    private int applyAll(List<Object[]> updates) {
        String sql = """
                UPDATE player_fixture_stats
                   SET defensive_contribution = ?,
                       clearances_blocks_interceptions = ?,
                       recoveries = ?,
                       tackles = ?
                 WHERE fixture_id = ? AND player_id = ? AND season_id = ?
                """;
        int updated = 0;
        for (int from = 0; from < updates.size(); from += BATCH_SIZE) {
            List<Object[]> batch = updates.subList(from, Math.min(from + BATCH_SIZE, updates.size()));
            for (int count : jdbc.batchUpdate(sql, batch)) {
                updated += Math.max(count, 0);
            }
        }
        return updated;
    }

    /**
     * Spaces the requests. Six hundred back-to-back calls to a public API nobody is paying for
     * is not something to do at full speed, and the job has all night.
     */
    private void pause() {
        if (requestDelayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(requestDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Integer asInt(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }
}
