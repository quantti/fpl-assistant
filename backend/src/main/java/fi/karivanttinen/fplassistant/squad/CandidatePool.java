package fi.karivanttinen.fplassistant.squad;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import fi.karivanttinen.fplassistant.tools.ExpectedPoints;
import fi.karivanttinen.fplassistant.tools.SeasonContext;
import fi.karivanttinen.fplassistant.tools.SeasonResolver;
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
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT season, price_date FROM " + dailyTable + " ORDER BY price_date DESC LIMIT 1");
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.getFirst();
        return Optional.of(new Capture((String) row.get("season"),
                ((java.sql.Date) row.get("price_date")).toLocalDate()));
    }

    /**
     * The season being played once it holds a real sample, the previous one until then — the
     * same early-season rule the system prompt states, applied where the ordering is decided.
     */
    public MetricWindow metricWindow(SeasonContext season) {
        int played = seasonResolver.completedGameweeks(season.seasonId());
        Optional<SeasonContext> better = seasonResolver.betterMetricSeason(season, played,
                ExpectedPoints.MIN_SAMPLE_GAMEWEEKS);
        return better
                .map(p -> new MetricWindow(p.seasonId(), p.code(), false, false,
                        ExpectedPoints.WHOLE_SEASON))
                .orElseGet(() -> new MetricWindow(season.seasonId(), season.code(), true,
                        season.currentSeason(),
                        season.currentSeason() ? ExpectedPoints.WINDOW_MATCHES
                                : ExpectedPoints.WHOLE_SEASON));
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
    public List<SquadCandidate> load(Capture capture, MetricWindow window) {
        if (capture == null) {
            return List.of();
        }
        String metricKey = window.isResolvedSeason() ? "mp.id::text" : "LOWER(mp.web_name)";
        String poolKey = window.isResolvedSeason() ? "pool.element_id::text" : "LOWER(pool.web_name)";
        String metricUnique = window.isResolvedSeason() ? "" : "WHERE " + nameIsUnique("mp");
        String poolUnique = window.isResolvedSeason() ? "" : "AND pool.name_count = 1";

        List<Map<String, Object>> rows = jdbc.queryForList("""
                WITH pool AS (
                    SELECT element_id, web_name, full_name, team_id, team_short, team_name,
                           element_type, now_cost, selected_by_percent, status, news,
                           COUNT(*) OVER (PARTITION BY LOWER(web_name)) AS name_count
                    FROM %s
                    WHERE season = ? AND price_date = ? AND status IN ('a', 'd')
                ),
                recent AS (
                    SELECT pfs.player_id, pfs.player_team_id, pfs.gameweek, pfs.minutes,
                           pfs.total_points, pfs.expected_goals AS xg,
                           pfs.expected_assists AS xa, pfs.expected_goals_conceded AS xgc,
                           ROW_NUMBER() OVER (PARTITION BY pfs.player_id
                                              ORDER BY pfs.gameweek DESC, pfs.fixture_id DESC) AS rn
                    FROM player_fixture_stats pfs
                    WHERE pfs.season_id = ?
                ),
                windowed AS (
                    SELECT player_id,
                           MIN(player_team_id) AS metric_team_id,
                           MIN(gameweek) AS min_gw,
                           MAX(gameweek) AS max_gw,
                           SUM(minutes) AS mins,
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
                club_fixtures AS (
                    -- The club's own fixtures between his first and last windowed gameweek,
                    -- not a count of his rows: tapas omits a row entirely for a fixture he was
                    -- not in the squad for, so a row count silently shrinks whenever a player
                    -- misses part of the window and inflates his expected minutes as a result.
                    SELECT w.player_id,
                           (SELECT count(*) FROM fixture f
                            WHERE f.season_id = ?
                              AND f.gameweek BETWEEN w.min_gw AND w.max_gw
                              AND (f.team_h = w.metric_team_id OR f.team_a = w.metric_team_id)
                           ) AS fixtures
                    FROM windowed w
                ),
                metrics AS (
                    SELECT %s AS k, min(w.mins) AS mins, min(cf.fixtures) AS fixtures,
                           min(w.xg90) AS xg90, min(w.xa90) AS xa90, min(w.pts90) AS pts90,
                           min(w.xgc_per_match) AS xgc_per_match
                    FROM windowed w
                    JOIN club_fixtures cf ON cf.player_id = w.player_id
                    JOIN player mp ON mp.id = w.player_id AND mp.season_id = ?
                    %s
                    GROUP BY 1
                    HAVING count(*) = 1
                )
                SELECT pool.*, m.mins, m.fixtures, m.xg90, m.xa90, m.pts90, m.xgc_per_match
                FROM pool
                LEFT JOIN metrics m ON m.k = %s %s
                """.formatted(dailyTable, window.windowLimit(), ExpectedPoints.MIN_WINDOW_MINUTES,
                        metricKey, metricUnique, poolKey, poolUnique),
                capture.season(), capture.date(), window.seasonId(), window.seasonId(),
                window.seasonId());

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
