package dev.quantti.fplassistant.squad;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.quantti.fplassistant.tools.DailyPriceSource;
import dev.quantti.fplassistant.tools.ExpectedPoints;
import dev.quantti.fplassistant.tools.MetricSeasons;
import dev.quantti.fplassistant.tools.SeasonBlend;
import dev.quantti.fplassistant.tools.SeasonResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Loads every player the solver may pick.
 *
 * <p><b>The pool is the daily capture, not the tapas squad table.</b> {@code fpl_daily_price}
 * already carries identity, club, position, price, ownership and availability, captured from
 * FPL's own API every morning. Reading them from tapas instead is issue #50, and would also
 * return nothing at all during the season changeover, when tapas has not synced the new season.
 *
 * <p>Metrics come from {@code player_fixture_stats}, which is tapas-owned and therefore may be
 * a season behind. When it is, players are bridged by lowercased {@code web_name} and any name
 * belonging to two players in either season is dropped rather than guessed — the same rule
 * {@code PickTools} uses, for the same reason.
 */
@Component
public class CandidatePool {

    private final JdbcTemplate jdbc;
    private final SeasonResolver seasonResolver;
    private final DailyPriceSource daily;
    private final String dailyTable;

    public CandidatePool(JdbcTemplate jdbc, SeasonResolver seasonResolver, DailyPriceSource daily,
                         @Value("${fpl.daily-price.table-name}") String dailyTable) {
        this.jdbc = jdbc;
        this.seasonResolver = seasonResolver;
        this.daily = daily;
        this.dailyTable = dailyTable;
    }

    /**
     * The season and date of the most recent daily-price capture, read from the capture itself
     * rather than from {@link SeasonResolver}. During the changeover the resolver still names the
     * previous season — tapas has not synced the new one — while this table already carries
     * today's prices for it; filtering {@link #load} on the resolver's season would then match
     * nothing at all, even though the capture holds hundreds of fit players. See
     * {@code DailyPriceSource#provenanceFor}, which reads a row's own season the same way.
     */
    public record Capture(String season, LocalDate date) {
    }

    public Optional<Capture> latestCapture() {
        return daily.latestCapture().map(capture -> new Capture(capture.season(), capture.date()));
    }

    /**
     * Everyone available on the capture date, each tagged with the tier his record earns.
     *
     * <p>Availability is read from the capture, never from tapas: {@code a} is fit, {@code d} is
     * doubtful. A doubtful player with a record is admitted and flagged; a doubtful player
     * without one is not admitted at all, because a body is worth buying only when it is a fit
     * one.
     *
     * <p><b>The pool-side name guard uses a {@code LEFT JOIN}, deliberately, and that changes
     * what "ambiguous" must do to the row.</b> When the metric season is not the pool's own
     * season, a metric row is bridged to a pool row by lowercased {@code web_name}. If two
     * current-season candidates shared a name, both would otherwise match the same single
     * historical row through that key and both would print one player's numbers. The guard here
     * only breaks the join's {@code ON} condition for an ambiguous pool name — it does not filter
     * the row out of {@code pool} itself, because an ambiguous name is still a fit, purchasable
     * player; he simply loses the metric attachment and reads as a {@link SquadCandidate.Tier#BODY}.
     * This is the opposite of {@code PickTools}'s equivalent guard, which is safe to drop the row
     * entirely only because it joins with an {@code INNER JOIN} onto a pool that is not the thing
     * being returned.
     */
    public List<SquadCandidate> load(Capture capture, MetricSeasons metric) {
        if (capture == null) {
            return List.of();
        }
        // FPL reassigns element ids every season. They are the strongest key within one season,
        // but across the changeover the same number names a different player and only an
        // unambiguous name may bridge the capture to the completed season's metrics.
        boolean sameSeason = capture.season().equals(metric.played().code());
        String metricJoin = sameSeason
                ? "c.player_id = pool.element_id"
                : "c.k = LOWER(pool.web_name) AND c.unique_name AND pool.name_count = 1";

        List<Map<String, Object>> rows = jdbc.queryForList("""
                WITH whole_capture AS (
                    -- Count names before availability filtering. An injured namesake is not a
                    -- candidate, but still makes identity ambiguous and must block the bridge.
                    SELECT element_id, web_name, full_name, team_id, team_short, team_name,
                           element_type, now_cost, selected_by_percent, status, news,
                           COUNT(*) OVER (PARTITION BY LOWER(web_name)) AS name_count
                    FROM %s
                    WHERE season = ? AND price_date = ?
                ),
                pool AS (
                    SELECT * FROM whole_capture WHERE status IN ('a', 'd')
                ),
                recent AS (
                    SELECT pfs.season_id, pfs.player_id, pfs.player_team_id, pfs.gameweek,
                           pfs.minutes, pfs.total_points, pfs.expected_goals AS xg,
                           pfs.expected_assists AS xa, pfs.expected_goals_conceded AS xgc,
                           pfs.defensive_contribution,
                           ROW_NUMBER() OVER (PARTITION BY pfs.season_id, pfs.player_id
                                              ORDER BY pfs.gameweek DESC, pfs.fixture_id DESC) AS rn,
                           -- The ramp's input: every qualifying match of the season, not just the
                           -- windowed ones.
                           count(*) FILTER (WHERE pfs.minutes >= 60)
                               OVER (PARTITION BY pfs.season_id, pfs.player_id) AS matches60
                    FROM player_fixture_stats pfs
                    WHERE pfs.season_id IN (?, ?)
                ),
                windowed AS (
                    SELECT season_id, player_id,
                           MIN(player_team_id) AS metric_team_id,
                           MIN(gameweek) AS min_gw,
                           MAX(gameweek) AS max_gw,
                           min(matches60) AS matches60,
                           SUM(minutes) AS mins,
                           SUM(xg) * 90 / NULLIF(SUM(minutes), 0) AS xg90,
                           SUM(xa) * 90 / NULLIF(SUM(minutes), 0) AS xa90,
                           SUM(total_points) * 90.0 / NULLIF(SUM(minutes), 0) AS pts90,
                           SUM(xgc) FILTER (WHERE minutes >= 60)
                               / NULLIF(count(*) FILTER (WHERE minutes >= 60), 0) AS xgc_per_match,
                           -- The mean is what the defcon model needs; ExpectedPoints reads the
                           -- chance of crossing the threshold out of it, and the match count says
                           -- how much to believe the mean.
                           count(*) FILTER (WHERE minutes >= 60
                               AND defensive_contribution IS NOT NULL) AS defcon_matches,
                           AVG(defensive_contribution) FILTER (WHERE minutes >= 60) AS defcon_avg
                    FROM recent
                    WHERE rn <= CASE WHEN season_id = ? THEN %d ELSE %d END
                    GROUP BY season_id, player_id
                ),
                club_fixtures AS (
                    -- The club's own fixtures between his first and last windowed gameweek, not a
                    -- count of his rows: tapas omits a row entirely for a fixture he was not in
                    -- the squad for, so a row count silently shrinks whenever a player misses part
                    -- of the window and inflates his expected minutes as a result.
                    SELECT w.season_id, w.player_id,
                           (SELECT count(*) FROM fixture f
                            WHERE f.season_id = w.season_id
                              AND f.gameweek BETWEEN w.min_gw AND w.max_gw
                              AND (f.team_h = w.metric_team_id OR f.team_a = w.metric_team_id)
                           ) AS fixtures
                    FROM windowed w
                ),
                cur AS (
                    -- The played season keyed for both possible joins. The weekly player row is
                    -- needed only for the cross-season name bridge, so keep it a LEFT JOIN: same-
                    -- season fixture metrics can exist before that row lands.
                    SELECT w.*, LOWER(mp.web_name) AS k, %s AS unique_name
                    FROM windowed w
                    LEFT JOIN player mp ON mp.id = w.player_id AND mp.season_id = w.season_id
                    WHERE w.season_id = ?
                ),
                prev AS (
                    SELECT LOWER(mp.web_name) AS k,
                           min(w.matches60) AS matches60, min(w.mins) AS mins,
                           min(cf.fixtures) AS fixtures,
                           min(w.xg90) AS xg90, min(w.xa90) AS xa90, min(w.pts90) AS pts90,
                           min(w.xgc_per_match) AS xgc_per_match,
                           min(w.defcon_matches) AS defcon_matches, min(w.defcon_avg) AS defcon_avg
                    FROM windowed w
                    JOIN club_fixtures cf ON cf.season_id = w.season_id AND cf.player_id = w.player_id
                    JOIN player mp ON mp.id = w.player_id AND mp.season_id = w.season_id
                    WHERE w.season_id = ? AND %s
                    GROUP BY 1
                    HAVING count(*) = 1
                )
                SELECT pool.*,
                       c.matches60 AS cur_matches60, c.mins AS cur_mins,
                       cc.fixtures AS cur_fixtures,
                       c.xg90 AS cur_xg90, c.xa90 AS cur_xa90, c.pts90 AS cur_pts90,
                       c.xgc_per_match AS cur_xgc_per_match,
                       c.defcon_matches AS cur_defcon_matches, c.defcon_avg AS cur_defcon_avg,
                       pv.matches60 AS prev_matches60, pv.mins AS prev_mins,
                       pv.fixtures AS prev_fixtures,
                       pv.xg90 AS prev_xg90, pv.xa90 AS prev_xa90, pv.pts90 AS prev_pts90,
                       pv.xgc_per_match AS prev_xgc_per_match,
                       pv.defcon_matches AS prev_defcon_matches, pv.defcon_avg AS prev_defcon_avg
                FROM pool
                LEFT JOIN cur c ON %s
                LEFT JOIN club_fixtures cc ON cc.season_id = c.season_id AND cc.player_id = c.player_id
                LEFT JOIN prev pv ON pv.k = LOWER(pool.web_name) AND pool.name_count = 1
                """.formatted(dailyTable, metric.windowLimit(),
                        ExpectedPoints.WHOLE_SEASON, nameIsUnique("mp"), nameIsUnique("mp"),
                        metricJoin),
                capture.season(), capture.date(),
                metric.played().seasonId(), metric.previousSeasonId(),
                metric.played().seasonId(),
                metric.played().seasonId(),
                metric.previousSeasonId());

        List<SquadCandidate> candidates = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            SeasonBlend.Blended blended = SeasonBlend.blend(
                    SeasonBlend.side(row, SeasonBlend.CURRENT_PREFIX),
                    SeasonBlend.side(row, SeasonBlend.PREVIOUS_PREFIX));
            String status = (String) row.get("status");
            if (blended == null && !"a".equals(status)) {
                continue;
            }
            candidates.add(toCandidate(row, blended));
        }
        return candidates;
    }

    private static SquadCandidate toCandidate(Map<String, Object> row,
                                              SeasonBlend.Blended blended) {
        boolean rankable = blended != null;
        Map<String, Object> metrics = new HashMap<>();
        if (rankable) {
            metrics.putAll(blended.metrics());
            metrics.put("element_type", row.get("element_type"));
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
                rankable ? asDouble(metrics.get("mins")) : 0,
                rankable ? asInt(metrics.get("fixtures")) : 0,
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
