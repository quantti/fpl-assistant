package dev.quantti.fplassistant.squad;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.quantti.fplassistant.tools.SeasonContext;
import dev.quantti.fplassistant.tools.SeasonResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Loads every player the solver may pick.
 *
 * <p><b>The pool is the daily capture, not the shared squad table.</b> {@code fpl_daily_price}
 * already carries identity, club, position, price, ownership and availability, captured from
 * FPL's own API every morning. Reading them from shared instead is issue #50, and would also
 * return nothing at all during the season changeover, when shared has not synced the new season.
 *
 * <p>Metrics come from {@code player_fixture_stats}, which is shared-owned and therefore may be
 * a season behind. When it is, players are bridged by lowercased {@code web_name} and any name
 * belonging to two players in either season is dropped rather than guessed — the same rule
 * {@code PickTools} uses, for the same reason.
 */
@Component
public class CandidatePool {

    /** Matches read per player when the metric season is the one being played: recent form. */
    static final int WINDOW_MATCHES = 10;

    /** Every gameweek of a finished season, which is read whole rather than by its run-in. */
    static final int WHOLE_SEASON = 60;

    /** Minutes needed inside the window to be judged at all. Two full matches. */
    static final int MIN_WINDOW_MINUTES = 180;

    /** Completed gameweeks the season being played needs before its own numbers are used. */
    static final int MIN_SAMPLE_GAMEWEEKS = 5;

    private final JdbcTemplate jdbc;
    private final SeasonResolver seasonResolver;
    private final String dailyTable;

    public CandidatePool(JdbcTemplate jdbc, SeasonResolver seasonResolver,
                         @Value("${fpl.daily-price.table-name}") String dailyTable) {
        this.jdbc = jdbc;
        this.seasonResolver = seasonResolver;
        this.dailyTable = dailyTable;
    }

    /** Which season the metrics come from, and how far back to read it. */
    public record MetricWindow(int seasonId, String code, boolean isResolvedSeason,
                               boolean beingPlayed, int windowLimit) {
    }

    public LocalDate latestCaptureDate() {
        return jdbc.queryForObject(
                "SELECT MAX(price_date) FROM " + dailyTable, LocalDate.class);
    }

    /**
     * The season being played once it holds a real sample, the previous one until then — the
     * same early-season rule the system prompt states, applied where the ordering is decided.
     */
    public MetricWindow metricWindow(SeasonContext season) {
        int played = completedGameweeks(season.seasonId());
        if (played >= MIN_SAMPLE_GAMEWEEKS) {
            return new MetricWindow(season.seasonId(), season.code(), true,
                    season.currentSeason(),
                    season.currentSeason() ? WINDOW_MATCHES : WHOLE_SEASON);
        }
        Optional<SeasonContext> previous = seasonResolver.previousSeason(season);
        return previous
                .map(p -> new MetricWindow(p.seasonId(), p.code(), false, false, WHOLE_SEASON))
                .orElseGet(() -> new MetricWindow(season.seasonId(), season.code(), true,
                        season.currentSeason(), WHOLE_SEASON));
    }

    private int completedGameweeks(int seasonId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM gameweek g
                WHERE g.season_id = ? AND g.finished = true
                  AND EXISTS (SELECT 1 FROM player_fixture_stats pfs
                              WHERE pfs.season_id = g.season_id AND pfs.gameweek = g.id)
                """, Integer.class, seasonId);
        return count == null ? 0 : count;
    }

    /**
     * Everyone available on the capture date, each tagged with the tier his record earns.
     *
     * <p>Availability is read from the capture, never from shared: {@code a} is fit, {@code d} is
     * doubtful. A doubtful player with a record is admitted and flagged; a doubtful player
     * without one is not admitted at all, because a body is worth buying only when it is a fit
     * one.
     */
    public List<SquadCandidate> load(SeasonContext season, MetricWindow window,
                                     LocalDate captureDate) {
        if (captureDate == null) {
            return List.of();
        }
        String metricKey = window.isResolvedSeason() ? "mp.id::text" : "LOWER(mp.web_name)";
        String poolKey = window.isResolvedSeason() ? "pool.element_id::text" : "LOWER(pool.web_name)";
        String metricUnique = window.isResolvedSeason() ? "" : "WHERE " + nameIsUnique("mp");

        List<Map<String, Object>> rows = jdbc.queryForList("""
                WITH pool AS (
                    SELECT element_id, web_name, full_name, team_id, team_short, team_name,
                           element_type, now_cost, selected_by_percent, status, news
                    FROM %s
                    WHERE season = ? AND price_date = ? AND status IN ('a', 'd')
                ),
                recent AS (
                    SELECT pfs.player_id, pfs.player_team_id, pfs.minutes, pfs.total_points,
                           pfs.expected_goals AS xg, pfs.expected_assists AS xa,
                           pfs.expected_goals_conceded AS xgc,
                           ROW_NUMBER() OVER (PARTITION BY pfs.player_id
                                              ORDER BY pfs.gameweek DESC, pfs.fixture_id DESC) AS rn
                    FROM player_fixture_stats pfs
                    WHERE pfs.season_id = ?
                ),
                windowed AS (
                    SELECT player_id,
                           MIN(player_team_id) AS metric_team_id,
                           SUM(minutes) AS mins,
                           count(*) AS fixtures,
                           SUM(xg) * 90 / NULLIF(SUM(minutes), 0) AS xg90,
                           SUM(xa) * 90 / NULLIF(SUM(minutes), 0) AS xa90,
                           SUM(total_points) * 90.0 / NULLIF(SUM(minutes), 0) AS pts90,
                           SUM(xgc) FILTER (WHERE minutes >= 60)
                               / NULLIF(count(*) FILTER (WHERE minutes >= 60), 0) AS xgc_per_match
                    FROM recent
                    WHERE rn <= %d
                    GROUP BY player_id
                    HAVING SUM(minutes) >= %d
                ),
                metrics AS (
                    SELECT %s AS k, min(w.mins) AS mins, min(w.fixtures) AS fixtures,
                           min(w.xg90) AS xg90, min(w.xa90) AS xa90, min(w.pts90) AS pts90,
                           min(w.xgc_per_match) AS xgc_per_match
                    FROM windowed w
                    JOIN player mp ON mp.id = w.player_id AND mp.season_id = ?
                    %s
                    GROUP BY 1
                    HAVING count(*) = 1
                )
                SELECT pool.*, m.mins, m.fixtures, m.xg90, m.xa90, m.pts90, m.xgc_per_match
                FROM pool
                LEFT JOIN metrics m ON m.k = %s
                """.formatted(dailyTable, window.windowLimit(), MIN_WINDOW_MINUTES,
                        metricKey, metricUnique, poolKey),
                season.code(), captureDate, window.seasonId(), window.seasonId());

        List<SquadCandidate> candidates = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            boolean rankable = row.get("mins") != null;
            String status = (String) row.get("status");
            if (!rankable && !"a".equals(status)) {
                continue;
            }
            candidates.add(toCandidate(row, rankable));
        }
        return candidates;
    }

    private static SquadCandidate toCandidate(Map<String, Object> row, boolean rankable) {
        Map<String, Object> metrics = new HashMap<>();
        if (rankable) {
            metrics.put("element_type", row.get("element_type"));
            metrics.put("xg90", row.get("xg90"));
            metrics.put("xa90", row.get("xa90"));
            metrics.put("pts90", row.get("pts90"));
            metrics.put("xgc_per_match", row.get("xgc_per_match"));
        }
        return new SquadCandidate(
                asInt(row.get("element_id")),
                (String) row.get("web_name"),
                (String) row.get("full_name"),
                (String) row.get("team_name"),
                (String) row.get("team_short"),
                asInt(row.get("element_type")),
                asInt(row.get("now_cost")),
                asDouble(row.get("selected_by_percent")),
                (String) row.get("status"),
                (String) row.get("news"),
                asInt(row.get("team_id")),
                asDouble(row.get("mins")),
                asInt(row.get("fixtures")),
                metrics,
                rankable ? SquadCandidate.Tier.RANKABLE : SquadCandidate.Tier.BODY);
    }

    private static int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    /** A name belonging to exactly one player in that row's own season. */
    private static String nameIsUnique(String alias) {
        return """
                NOT EXISTS (
                    SELECT 1 FROM player twin
                    WHERE twin.season_id = %s.season_id
                      AND LOWER(twin.web_name) = LOWER(%s.web_name)
                      AND twin.id <> %s.id
                )""".formatted(alias, alias, alias);
    }
}
