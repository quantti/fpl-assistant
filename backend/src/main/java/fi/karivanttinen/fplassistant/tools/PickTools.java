package fi.karivanttinen.fplassistant.tools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Phase 4 pick-advice tools. {@link #getDifferentialPicks} and {@link #getCaptainCandidates} rank
 * on expected FPL points per 90 built from {@code player_fixture_stats}, blended with the points
 * a player actually scored, then on the average fixture-difficulty rating (FDR) of the next N
 * fixtures, then on ownership or points. Every reply is prefixed with
 * {@link SeasonContext#provenance()}.
 *
 * <p><b>They used to rank on {@code form}, and that was the defect.</b> FPL's {@code form} is
 * average points per match over the last 30 days, so in GW2 it is GW1's scoreline and nothing
 * else: a defender who scored once led the list ahead of every defender at a side that concedes
 * nothing, and the model relayed the ordering as though it were a case. A points haul is a
 * result, not a reason.
 *
 * <p>The scale is FPL's own scoring, which is what makes one number fair across positions and
 * across the two kinds of defender. A goal is worth 6 to a defender and 4 to a forward; a clean
 * sheet is worth 4 to a defender, 1 to a midfielder and nothing to a forward. So
 * {@code xPts/90 = appearance + xG/90 × goal points + xA/90 × 3 + P(clean sheet) × clean-sheet
 * points}, with {@code P(clean sheet) = exp(-xGC)} — the Poisson probability that a side with
 * that expected concession keeps one. An attacking wing-back earns through the first two terms
 * and a defender at a mean side through the last, and neither is judged on the other's strength.
 *
 * <p><b>Realised points are blended in, weighted by how far the season has run.</b> Underlying
 * numbers are the better guide to what will happen next, but a player's actual return is
 * evidence too, and it stops being a small sample as the season goes on: penalties, set pieces,
 * bonus points and defensive contributions are all real points the model above cannot see. The
 * weight rises from nothing in the opening weeks to half by the season's midpoint
 * ({@link #MAX_REALISED_WEIGHT} at {@link #FULL_WEIGHT_GAMEWEEK}), so a hot start counts for
 * little in GW2 and a season of returns counts properly in GW20.
 *
 * <p>Defensive contribution is modelled as a probability: the chance a player reaches his
 * position's action threshold in a match, read from his average actions through a negative
 * binomial, times the two points it pays. Counting how often he has managed it before is the
 * weaker estimator — it keeps only "over or under" and discards the counts themselves, and it is
 * measurably worse on any sample short enough to matter.
 *
 * <p>Still not modelled: save points and penalty saves. {@code player_fixture_stats} carries
 * {@code saves} and {@code penalties_saved}, so leaving them out is a choice, not a gap.
 * Goalkeepers are understated, which the reply says.
 *
 * <p>The metrics blend the season being played with the previous one, per player, on the ramp
 * {@link SeasonBlend} holds: a player's own matches this season decide how much of his record is
 * this season's. Across the season boundary players are bridged by name — {@code player.id} is
 * FPL's element id and is reassigned every season, and tapas keeps no stable player code. A name
 * that is not unique in either season loses the previous-season side rather than being guessed —
 * a wrong metric attached to a recommendation is the failure this class exists to prevent.
 *
 * <p>Both tools exclude players who are injured, suspended or otherwise out ({@code status} not
 * in {@code a}/{@code d}) and label the doubtful ones with their news line. Recommending a player
 * who cannot play is worse than recommending nobody.
 *
 * <p>The FDR of every team is fetched in one query and reused across the candidate pool, rather
 * than one query per player: a pool of candidates shares at most 20 clubs.
 */
@Component
public class PickTools implements FplTool {

    private static final Logger log = LoggerFactory.getLogger(PickTools.class);
    private static final int MAX_PICKS = 10;
    private static final int MAX_FIXTURES = 5;

    // Window constants (WINDOW_MATCHES, WHOLE_SEASON, MIN_WINDOW_MINUTES) live on
    // ExpectedPoints, and the cross-season ramp on SeasonBlend — both shared with CandidatePool
    // so the two tools cannot rank the same player two different ways in one conversation by
    // drifting apart on what "recent form" means.

    /** Sinks players whose fixtures are unknown below any player with a real FDR. */
    private static final double UNKNOWN_FDR = Double.MAX_VALUE;

    /** The most weight a player's realised points are ever given against the expected model. */
    private static final double MAX_REALISED_WEIGHT = 0.5;

    /** Completed gameweeks at which realised points reach that weight — the season's midpoint. */
    private static final int FULL_WEIGHT_GAMEWEEK = 19;

    private final JdbcTemplate jdbc;
    private final SeasonResolver seasonResolver;

    public PickTools(JdbcTemplate jdbc, SeasonResolver seasonResolver) {
        this.jdbc = jdbc;
        this.seasonResolver = seasonResolver;
    }

    @Tool(description = """
            Suggest differential FPL picks — players with low ownership (≤ maxOwnership
            percent) whose underlying numbers and upcoming fixtures are favourable. Filters by
            position (1=GK, 2=DEF, 3=MID, 4=FWD, or "any"). Returns up to 10 players ranked by a
            Score: expected FPL points per 90 (xG and xA at that position's own goal and assist
            values, plus the clean-sheet probability implied by expected goals conceded and the
            rate at which the player reaches the defensive-contribution threshold), blended
            with the points actually scored per 90 at a weight that rises as the season runs.
            Then the average difficulty of their next N fixtures, then ownership. Numbers blend this
            season with last on a per-player weight that rises with his matches this season, and
            each pick shows its weight. NOT ranked by form: a recent points haul is a result, not
            a reason. Each pick shows the parts behind
            its expected points, so a defender who attacks and a defender at a mean side are
            distinguishable. The reply says which season the numbers cover. Players who are
            injured, suspended or otherwise unavailable are excluded; doubtful players are
            included and flagged. Use for differential/pick questions and combine with strategy
            docs. The reply begins with the season the numbers come from.
            """)
    public String getDifferentialPicks(String position, double maxOwnership, int nextN) {
        try {
            SeasonContext season = seasonResolver.resolve();
            Integer elementType = parsePosition(position);
            if (elementType == null) {
                return season.provenance() + "\nUnrecognised position \"" + position
                        + "\". Use 1/GK, 2/DEF, 3/MID, 4/FWD, or \"any\".";
            }
            int fixtureLimit = Math.max(1, Math.min(nextN, MAX_FIXTURES));
            MetricSeasons metric = seasonResolver.metricSeasons(season);

            List<Map<String, Object>> rows = candidates(season, metric, elementType, maxOwnership);
            if (rows.isEmpty()) {
                return season.provenance() + "\nNo differentials match those filters in "
                        + season.code() + " with a comparable underlying record"
                        + " (players who are injured or suspended are excluded, and so are"
                        + " players with under " + ExpectedPoints.MIN_WINDOW_MINUTES + " minutes of "
                        + metric.played().code() + " football to judge them on).";
            }

            return season.provenance() + "\n\n"
                    + formatPicks("Differential picks", rows, season, metric, fixtureLimit,
                            elementType, maxOwnership, true);
        } catch (DataAccessException e) {
            log.error("getDifferentialPicks(\"{}\", {}, {}) failed", position, maxOwnership, nextN, e);
            return "Pick data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    @Tool(description = """
            Suggest FPL captain candidates — midfielders and forwards with the strongest
            underlying attacking numbers and favourable upcoming fixtures. Returns up to 10
            players ranked by a Score: expected FPL points per 90 from xG and xA at that
            position's own values, plus the rate at which the player reaches the
            defensive-contribution threshold, blended with the points actually scored per 90 at a weight
            that rises as the season runs. Then the average difficulty of their next N fixtures,
            then total points, each shown with ownership (so the user can see template vs
            differential captains). Numbers blend this season with last on a per-player weight
            that rises with his matches this season, and each pick shows its weight.
            NOT ranked by form: a big gameweek is a result, not a reason,
            and the reply says which season the numbers cover. Players who are injured, suspended or otherwise
            unavailable are excluded; doubtful players are included and flagged. Use for
            captaincy questions and combine with strategy docs. The reply begins with the season
            the numbers come from.
            """)
    public String getCaptainCandidates(int nextN) {
        try {
            SeasonContext season = seasonResolver.resolve();
            int fixtureLimit = Math.max(1, Math.min(nextN, MAX_FIXTURES));
            MetricSeasons metric = seasonResolver.metricSeasons(season);

            List<Map<String, Object>> rows = candidates(season, metric, ATTACKERS, 100.0);
            if (rows.isEmpty()) {
                return season.provenance() + "\nNo captain candidates in " + season.code()
                        + " have a comparable underlying record yet (players who are injured or"
                        + " suspended are excluded, and so are players with under "
                        + ExpectedPoints.MIN_WINDOW_MINUTES + " minutes of " + metric.played().code()
                        + " football to judge them on).";
            }

            return season.provenance() + "\n\n"
                    + formatPicks("Captain candidates", rows, season, metric, fixtureLimit,
                            ATTACKERS, 100.0, false);
        } catch (DataAccessException e) {
            log.error("getCaptainCandidates({}) failed", nextN, e);
            return "Pick data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    /** Position filter meaning "midfielders and forwards" — the only positions worth captaining. */
    private static final int ATTACKERS = -1;

    /**
     * The candidate pool, already carrying each player's underlying numbers.
     *
     * <p>The whole qualifying pool comes back and is ranked in Java rather than cut in SQL. The
     * score is FPL's scoring applied to expected numbers, and writing it twice — once in SQL to
     * choose the pool, once in Java to order it — is how the two drift apart. A few hundred rows
     * is not worth that risk.
     */
    private List<Map<String, Object>> candidates(SeasonContext season, MetricSeasons metric,
                                                 int elementType, double maxOwnership) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                WITH pool AS (
                    SELECT p.id, p.season_id, p.web_name, p.element_type, p.now_cost,
                           p.selected_by_percent, p.form, p.total_points, p.team_id, p.status,
                           p.news, t.short_name AS team_short
                    FROM player p
                    JOIN team t ON t.id = p.team_id AND t.season_id = p.season_id
                    WHERE p.season_id = ?
                      AND p.status IN ('a', 'd')
                      AND p.selected_by_percent <= ?
                      %s
                ),
                recent AS (
                    SELECT pfs.season_id, pfs.player_id, pfs.minutes, pfs.total_points,
                           pfs.expected_goals AS xg, pfs.expected_assists AS xa,
                           pfs.expected_goals_conceded AS xgc,
                           pfs.defensive_contribution,
                           -- fixture_id breaks the tie: a double gameweek gives one player two
                           -- rows with the same gameweek, and an unordered pair straddling the
                           -- window edge makes the same call return different numbers.
                           ROW_NUMBER() OVER (PARTITION BY pfs.season_id, pfs.player_id
                                              ORDER BY pfs.gameweek DESC, pfs.fixture_id DESC) AS rn,
                           -- The ramp's input: every qualifying match of the season, not just the
                           -- windowed ones. A player with 12 has reached full weight even though
                           -- only the last 10 feed his rates.
                           count(*) FILTER (WHERE pfs.minutes >= 60)
                               OVER (PARTITION BY pfs.season_id, pfs.player_id) AS matches60
                    FROM player_fixture_stats pfs
                    WHERE pfs.season_id IN (?, ?)
                ),
                windowed AS (
                    SELECT season_id, player_id,
                           min(matches60) AS matches60,
                           SUM(minutes) AS mins,
                           -- Appearances, not rows. tapas writes a row for every fixture in the
                           -- squad, including the ones a player watched, so counting rows would
                           -- print "450 mins (10 mt)" for someone who played five.
                           count(*) FILTER (WHERE minutes > 0) AS matches,
                           SUM(xg) * 90 / NULLIF(SUM(minutes), 0) AS xg90,
                           SUM(xa) * 90 / NULLIF(SUM(minutes), 0) AS xa90,
                           SUM(total_points) * 90.0 / NULLIF(SUM(minutes), 0) AS pts90,
                           -- Only matches the player was on for an hour count towards the clean
                           -- sheet: below that FPL awards none, and a cameo would otherwise
                           -- inherit the whole match's expected concession.
                           SUM(xgc) FILTER (WHERE minutes >= 60)
                               / NULLIF(count(*) FILTER (WHERE minutes >= 60), 0) AS xgc_per_match,
                           -- The mean is what the defcon model needs; ExpectedPoints reads the
                           -- chance of crossing the threshold out of it, and the match count says
                           -- how much to believe the mean.
                           count(*) FILTER (WHERE minutes >= 60
                               AND defensive_contribution IS NOT NULL) AS defcon_matches,
                           AVG(defensive_contribution) FILTER (WHERE minutes >= 60) AS defcon_avg
                    FROM recent
                    -- Recent form for the season being played, the whole of the previous one.
                    WHERE rn <= CASE WHEN season_id = ? THEN %d ELSE %d END
                    GROUP BY season_id, player_id
                ),
                prev AS (
                    -- Last season's row attached by name: element ids are reassigned every summer
                    -- and tapas keeps no stable code. A name that is not unique in that season is
                    -- dropped rather than guessed.
                    SELECT LOWER(mp.web_name) AS k,
                           min(w.matches60) AS matches60, min(w.mins) AS mins,
                           min(w.matches) AS matches, min(w.xg90) AS xg90, min(w.xa90) AS xa90,
                           min(w.pts90) AS pts90, min(w.xgc_per_match) AS xgc_per_match,
                           min(w.defcon_matches) AS defcon_matches, min(w.defcon_avg) AS defcon_avg
                    FROM windowed w
                    JOIN player mp ON mp.id = w.player_id AND mp.season_id = w.season_id
                    WHERE w.season_id = ? AND %s
                    GROUP BY 1
                    HAVING count(*) = 1
                )
                SELECT pool.*,
                       c.matches60 AS cur_matches60, c.mins AS cur_mins, c.matches AS cur_matches,
                       c.xg90 AS cur_xg90, c.xa90 AS cur_xa90, c.pts90 AS cur_pts90,
                       c.xgc_per_match AS cur_xgc_per_match,
                       c.defcon_matches AS cur_defcon_matches, c.defcon_avg AS cur_defcon_avg,
                       pv.matches60 AS prev_matches60, pv.mins AS prev_mins,
                       pv.matches AS prev_matches, pv.xg90 AS prev_xg90, pv.xa90 AS prev_xa90,
                       pv.pts90 AS prev_pts90, pv.xgc_per_match AS prev_xgc_per_match,
                       pv.defcon_matches AS prev_defcon_matches, pv.defcon_avg AS prev_defcon_avg
                FROM pool
                LEFT JOIN windowed c ON c.season_id = pool.season_id AND c.player_id = pool.id
                LEFT JOIN prev pv ON pv.k = LOWER(pool.web_name) AND %s
                """.formatted(positionFilter(elementType, "p"), metric.windowLimit(),
                        ExpectedPoints.WHOLE_SEASON, nameIsUnique("mp"), nameIsUnique("pool")),
                season.seasonId(), maxOwnership,
                season.seasonId(), metric.previousSeasonId(),
                season.seasonId(),
                metric.previousSeasonId());

        List<Map<String, Object>> ranked = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            SeasonBlend.Blended blended = SeasonBlend.blend(
                    SeasonBlend.side(row, SeasonBlend.CURRENT_PREFIX),
                    SeasonBlend.side(row, SeasonBlend.PREVIOUS_PREFIX));
            if (blended == null) {
                continue;   // no comparable record — counted by the coverage line, not listed
            }
            row.putAll(blended.metrics());
            ranked.add(row);
        }
        return ranked;
    }

    /**
     * A name that belongs to exactly one player in that row's own season. Checked against the
     * whole squad, not the filtered pool: the position and ownership filters would otherwise
     * hide a collision by admitting only one of the pair, and the minutes floor would hide
     * another by dropping one of them before the check ran.
     */
    private static String nameIsUnique(String alias) {
        return """
                NOT EXISTS (
                    SELECT 1 FROM player twin
                    WHERE twin.season_id = %s.season_id
                      AND LOWER(twin.web_name) = LOWER(%s.web_name)
                      AND twin.id <> %s.id
                )""".formatted(alias, alias, alias);
    }

    private static String positionFilter(int elementType, String alias) {
        return switch (elementType) {
            case ATTACKERS -> "AND " + alias + ".element_type IN (3, 4)";
            case 0 -> "";
            default -> "AND " + alias + ".element_type = " + elementType;
        };
    }

    /**
     * How many players matched the filters at all, ranked or not, so the reply can state its own
     * coverage. Reported rather than swallowed: a promoted side's defence and a summer signing
     * are exactly the picks a user asks about, and silently omitting them reads as "no such
     * option".
     *
     * <p>The count is the pool; the shortfall is inferred by subtraction rather than by a second
     * query reproducing the pool's reasons. An earlier version did reproduce them and got it
     * wrong — it counted only players with no per-fixture rows at all, so anyone under the
     * minutes floor, and anyone with an ambiguous name, vanished from both the list and the
     * count. A subtraction cannot drift from the query it describes.
     */
    private int poolSize(SeasonContext season, int elementType, double maxOwnership) {
        Integer pool = jdbc.queryForObject("""
                SELECT count(*) FROM player p
                JOIN team t ON t.id = p.team_id AND t.season_id = p.season_id
                WHERE p.season_id = ?
                  AND p.status IN ('a', 'd')
                  AND p.selected_by_percent <= ?
                  %s
                """.formatted(positionFilter(elementType, "p")),
                Integer.class, season.seasonId(), maxOwnership);
        return pool == null ? 0 : pool;
    }

    /**
     * Whether the expected columns were ever populated for this season. {@code expected_goals}
     * and {@code expected_assists} are {@code NOT NULL DEFAULT 0} like {@code
     * expected_goals_conceded}, so an unfilled season reads as a league in which nobody creates
     * anything: every xPts/90 collapses to the appearance constant, the whole pool ties, and the
     * order silently becomes the fixture and ownership tiebreak while the footer still calls it a
     * ranking on underlying numbers. A single non-zero anywhere is enough to rule that out.
     */
    private static boolean noUnderlyingSignal(List<Map<String, Object>> rows) {
        return rows.stream().noneMatch(r ->
                numeric(r.get("xg90")) > 0 || numeric(r.get("xa90")) > 0);
    }

    private String formatPicks(String heading, List<Map<String, Object>> rows, SeasonContext season,
                               MetricSeasons metric, int fixtureLimit, int elementType,
                               double maxOwnership, boolean ownershipTieBreak) {
        Integer startGw = startGameweek(season.seasonId());
        Map<Integer, Double> fdrByTeam = avgFdrByTeam(season.seasonId(), startGw, fixtureLimit);
        double realisedWeight = realisedWeight(metric.playedGameweeks());
        List<Map<String, Object>> ranked = rank(rows, fdrByTeam, ownershipTieBreak, realisedWeight);

        StringBuilder out = new StringBuilder();
        out.append(heading).append(" (").append(metric.played().code()).append("):\n");
        String qual = season.currentSeason() ? "" : " (" + season.code() + ")";
        for (Map<String, Object> r : ranked.subList(0, Math.min(MAX_PICKS, ranked.size()))) {
            int teamId = ((Number) r.get("team_id")).intValue();
            int position = ((Number) r.get("element_type")).intValue();
            Double avgFdr = fdrByTeam.get(teamId);
            out.append(String.format(Locale.ROOT,
                    "  %s %s (%s) £%sm%s | Own %s%%%s | Score %s | xPts/90 %s (%s) | Pts/90 %s"
                            + " | Mins %d (%d mt)%s | Avg FDR %s%s\n",
                    positionLabel(position),
                    r.get("web_name"), r.get("team_short"),
                    fixed(((Number) r.get("now_cost")).intValue() / 10.0, 1), qual,
                    r.get("selected_by_percent"), qual,
                    fixed(score(r, realisedWeight), 2),
                    fixed(expectedPointsPer90(r), 2),
                    components(r, position),
                    fixed(pointsPer90(r), 2),
                    Math.round(numeric(r.get("mins"))), Math.round(numeric(r.get("matches"))),
                    blendSegment(r, metric),
                    avgFdr == null ? "n/a" : fixed(avgFdr, 1),
                    availability(r)));
        }
        if (fdrByTeam.isEmpty()) {
            out.append("\n(No upcoming fixtures found — FDR is n/a and the ranking falls back to"
                    + " the score alone. Fixtures for this season may not be scheduled yet.)\n");
        }
        out.append(footer(metric, fixtureLimit, ownershipTieBreak, realisedWeight));

        int pool = poolSize(season, elementType, maxOwnership);
        if (pool > rows.size()) {
            // Phrased as a coverage figure rather than a count of absentees. With no filters a
            // bare count is most of the game's fringe players, and "250 further players are not
            // listed" reads as 250 picks being withheld.
            out.append("Ranked from ").append(rows.size()).append(" of the ").append(pool)
                    .append(" players matching these filters; the rest have no comparable ")
                    .append(metric.played().code())
                    .append(" record — new to the league, too few minutes to judge, or a name")
                    .append(" that is not unique in its season. If the user asks about someone")
                    .append(" absent, that is why: they are unjudged, not judged and rejected.\n");
        }
        if (noUnderlyingSignal(ranked)) {
            out.append("WARNING: every player here has xG/90 and xA/90 of 0.00. That is a column")
                    .append(" that was never populated for ").append(metric.played().code())
                    .append(", not a league of players who create nothing — the ranking has")
                    .append(" fallen back to fixtures and ownership. Tell the user the underlying")
                    .append(" numbers are unavailable rather than presenting this as form.\n");
        }
        return out.toString();
    }

    /**
     * Which seasons a pick's numbers come from and at what weight. Printed per pick because the
     * weight is per player: this project attributes every number, and one banner cannot describe
     * a mix that differs on every row.
     */
    private static String blendSegment(Map<String, Object> row, MetricSeasons metric) {
        if (!metric.blending()) {
            return "";
        }
        String cur = metric.played().code();
        String prev = metric.previousCode();
        int pct = (int) Math.round(numeric(row.get("blend_weight")) * 100);
        int matches = (int) numeric(row.get("blend_matches"));
        if (!Boolean.TRUE.equals(row.get("blend_has_previous"))) {
            return " | " + cur + " only — no " + prev + " record (" + matches + " mt this season)";
        }
        if (pct >= 100) {
            return " | " + cur + " only (" + matches + " mt this season)";
        }
        return " | blend " + pct + "% " + cur + ", " + (100 - pct) + "% " + prev
                + " (" + matches + " mt this season)";
    }

    /** The parts of xPts/90, so the model can say *why* a player ranks where they do. */
    private static String components(Map<String, Object> row, int position) {
        StringBuilder parts = new StringBuilder("xG/90 " + fixed(numeric(row.get("xg90")), 2)
                + ", xA/90 " + fixed(numeric(row.get("xa90")), 2));
        if (ExpectedPoints.cleanSheetPoints(position) > 0) {
            Object xgc = row.get("xgc_per_match");
            parts.append(ExpectedPoints.hasCleanSheetSignal(xgc)
                    ? ", CS %s%% at xGC %s".formatted(
                            fixed(cleanSheetProbability(numeric(xgc)) * 100, 0),
                            fixed(numeric(xgc), 2))
                    : ", CS n/a");
        }
        // A forward has no clean-sheet value but does have a threshold, so this cannot sit
        // behind the branch above.
        if (ExpectedPoints.defconThreshold(position) > 0) {
            parts.append(ExpectedPoints.hasDefconSignal(row, position)
                    ? ", DC %s%% at %s actions".formatted(
                            fixed(ExpectedPoints.defconProbability(row, position) * 100, 0),
                            fixed(numeric(row.get("defcon_avg")), 1))
                    : ", DC n/a");
        }
        return parts.toString();
    }

    private String footer(MetricSeasons metric, int fixtureLimit, boolean ownershipTieBreak,
                          double realisedWeight) {
        String span = metric.blending()
                ? "each player's last " + ExpectedPoints.WINDOW_MATCHES + " matches in "
                        + metric.played().code() + ", blended with all of " + metric.previousCode()
                : metric.played().currentSeason()
                        ? "each player's last " + ExpectedPoints.WINDOW_MATCHES + " matches in "
                                + metric.played().code()
                        : "all of " + metric.played().code();
        StringBuilder out = new StringBuilder("\nRanked by Score over "
                + span + ". Score blends xPts/90 —"
                + " expected FPL points per 90, built from xG, xA and the clean-sheet probability"
                + " implied by expected goals conceded, each valued at that position's own points"
                + " — with Pts/90, the points actually scored. Realised points carry "
                + Math.round(realisedWeight * 100) + "% of the weight here"
                + (metric.played().currentSeason()
                        ? ", rising towards " + Math.round(MAX_REALISED_WEIGHT * 100)
                          + "% by gameweek " + FULL_WEIGHT_GAMEWEEK + " as the sample grows"
                        : " — deliberately low: these are a previous season's returns, and"
                          + " penalties, set-piece duty and a player's role all change between"
                          + " seasons, so what he scored then is weaker evidence than how he"
                          + " created chances")
                + ". Then average FDR of the next " + fixtureLimit + " fixtures, then "
                + (ownershipTieBreak ? "ownership" : "total points") + ".\n");
        out.append("This is why both kinds of defender can appear: an attacking full-back earns"
                + " through xG and xA, a defender at a mean side through the clean-sheet term."
                + " Read the bracket to see which, and say so — they are not interchangeable"
                + " picks.\n");
        if (metric.blending()) {
            String cur = metric.played().code();
            String prev = metric.previousCode();
            out.append("The weight on ").append(cur).append(" rises with each player's own")
                    .append(" matches of an hour or more this season — 1: 10%, 2: 30%, 3: 60%,")
                    .append(" 4: 80%, 5-7: 90%, 8+: 100% — and each pick shows its own. Below")
                    .append(" 50% the judgement leans on ").append(prev)
                    .append(" and you should say so. A player marked \"no ").append(prev)
                    .append(" record\" is ranked on ").append(cur)
                    .append(" alone; treat him with the caution his match count deserves.\n");
        } else if (!metric.played().currentSeason()) {
            out.append("This season is not under way in the data yet, so these are ")
                    .append(metric.played().code())
                    .append("'s numbers over the whole season. Attribute them to it and never")
                    .append(" call them current form.\n");
        }
        out.append("The most recent gameweek in this data is ").append(metric.latestGameweek())
                .append(" of ").append(metric.played().code())
                .append(". If that is well behind the gameweek being played, the statistics sync")
                .append(" has stalled and these numbers are older than they look — say so.\n");
        out.append("xPts/90 covers goals, assists, clean sheets and defensive contribution. DC is")
                .append(" his estimated chance of reaching the defensive-contribution threshold")
                .append(" in a match — ").append(ExpectedPoints.DEFCON_THRESHOLD[2])
                .append(" defensive actions for a defender, ")
                .append(ExpectedPoints.DEFCON_THRESHOLD[3])
                .append(" for a midfielder or forward — modelled from his average actions over")
                .append(" matches he played an hour of, not counted from how often he has")
                .append(" managed it before. It reads n/a below ")
                .append(ExpectedPoints.MIN_DEFCON_MATCHES).append(" such matches. Save points and penalty saves are still not in it, so")
                .append(" goalkeepers are understated; Pts/90 is where their real return shows")
                .append(" up. Do not call xPts/90 a complete projection.\n")
                .append("Unavailable players (injured/suspended) are excluded.\n");
        return out.toString();
    }

    /**
     * How much a player's actual return counts against the expected model. Nothing at the start
     * of a season, where a return is one match; half by the midpoint, where it is twenty.
     */
    static double realisedWeight(int playedGameweeks) {
        double progress = Math.min(1.0, (double) playedGameweeks / FULL_WEIGHT_GAMEWEEK);
        return MAX_REALISED_WEIGHT * progress;
    }

    /**
     * Locale-pinned decimal formatting. {@code String.formatted} resolves the default locale, so
     * on a machine set to a comma-decimal language the model is handed "Score 6,83" — a number it
     * can read as a list or as thousands. The FDR column was already pinned; nothing else was.
     */
    private static String fixed(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    /** Poisson: the chance a side with this expected concession lets in none. */
    static double cleanSheetProbability(double expectedGoalsConceded) {
        return ExpectedPoints.cleanSheetProbability(expectedGoalsConceded);
    }

    /**
     * FPL's own scoring applied to expected numbers. The formula lives in
     * {@link ExpectedPoints} because the squad builder ranks on the same one; a copy here would
     * be a copy that drifts.
     */
    static double expectedPointsPer90(Map<String, Object> row) {
        return ExpectedPoints.perNinety(row);
    }

    static double pointsPer90(Map<String, Object> row) {
        return numeric(row.get("pts90"));
    }

    /** What the list is ordered on: the expected model, weighted against what actually happened. */
    static double score(Map<String, Object> row, double realisedWeight) {
        return expectedPointsPer90(row) * (1 - realisedWeight)
                + pointsPer90(row) * realisedWeight;
    }

    /**
     * Orders the candidate pool on the composite the tool descriptions promise.
     *
     * <p>Done in Java rather than SQL because the FDR is not a column: it is an aggregate over
     * each team's next N fixtures, resolved once per team in {@link #avgFdrByTeam}.
     */
    static List<Map<String, Object>> rank(List<Map<String, Object>> rows,
                                          Map<Integer, Double> fdrByTeam,
                                          boolean ownershipTieBreak,
                                          double realisedWeight) {
        Comparator<Map<String, Object>> byScore = Comparator
                .comparingDouble((Map<String, Object> r) -> score(r, realisedWeight)).reversed();
        Comparator<Map<String, Object>> byFdr = Comparator.comparingDouble(r -> {
            Double fdr = fdrByTeam.get(((Number) r.get("team_id")).intValue());
            return fdr == null ? UNKNOWN_FDR : fdr;
        });
        Comparator<Map<String, Object>> tieBreak = ownershipTieBreak
                ? Comparator.comparingDouble(r -> numeric(r.get("selected_by_percent")))
                : Comparator.comparingDouble((Map<String, Object> r) -> numeric(r.get("total_points"))).reversed();

        List<Map<String, Object>> sorted = new ArrayList<>(rows);
        sorted.sort(byScore.thenComparing(byFdr).thenComparing(tieBreak));
        return sorted;
    }

    /** Available players carry no marker; doubtful ones carry their news so the model can relay it. */
    private static String availability(Map<String, Object> r) {
        if (!"d".equals(String.valueOf(r.get("status")))) {
            return "";
        }
        Object news = r.get("news");
        String detail = (news == null || news.toString().isBlank()) ? "" : ": " + news;
        return " | DOUBTFUL" + detail;
    }

    /**
     * Average FDR of each team's next N fixtures, for every team at once. One query for the whole
     * candidate pool — a pool of 60 players shares at most 20 clubs, and asking per player made
     * the same request up to ten times.
     *
     * @return team id -> average FDR; a team with no upcoming fixtures is simply absent
     */
    private Map<Integer, Double> avgFdrByTeam(int seasonId, Integer startGw, int fixtureLimit) {
        Map<Integer, Double> byTeam = new HashMap<>();
        if (startGw == null) {
            return byTeam;
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT team_id, AVG(fdr) AS avg_fdr
                    FROM (
                        SELECT team_id, fdr,
                               ROW_NUMBER() OVER (PARTITION BY team_id ORDER BY gameweek, kickoff_time) AS rn
                        FROM (
                            SELECT team_h AS team_id, team_h_difficulty AS fdr, gameweek, kickoff_time
                            FROM fixture
                            WHERE season_id = ? AND gameweek >= ? AND finished = false
                            UNION ALL
                            SELECT team_a, team_a_difficulty, gameweek, kickoff_time
                            FROM fixture
                            WHERE season_id = ? AND gameweek >= ? AND finished = false
                        ) both_sides
                    ) ranked
                    WHERE rn <= ?
                    GROUP BY team_id
                    """, seasonId, startGw, seasonId, startGw, fixtureLimit);
            for (Map<String, Object> r : rows) {
                Object avg = r.get("avg_fdr");
                if (avg != null) {
                    byTeam.put(((Number) r.get("team_id")).intValue(), ((Number) avg).doubleValue());
                }
            }
        } catch (DataAccessException e) {
            log.warn("Could not resolve upcoming FDR for season {}; picks will show n/a", seasonId, e);
        }
        return byTeam;
    }

    /** The gameweek to start counting from: current, else next, else GW1 — same fallback as FixtureTools. */
    private Integer startGameweek(int seasonId) {
        try {
            return jdbc.queryForObject("""
                    SELECT COALESCE(
                        (SELECT id FROM gameweek WHERE season_id = ? AND is_current = true ORDER BY id LIMIT 1),
                        (SELECT id FROM gameweek WHERE season_id = ? AND is_next = true ORDER BY id LIMIT 1),
                        (SELECT MIN(id) FROM gameweek WHERE season_id = ?))
                    """, Integer.class, seasonId, seasonId, seasonId);
        } catch (DataAccessException e) {
            return null;
        }
    }

    /** Delegates to {@link ExpectedPoints#numeric}, which this and the squad builder now share. */
    private static double numeric(Object value) {
        return ExpectedPoints.numeric(value);
    }

    /**
     * @return 1–4 for a valid position, 0 for "any", or {@code null} for input that names no
     *         position at all — silently widening a typo to "any" would answer a question the
     *         user did not ask
     */
    static Integer parsePosition(String position) {
        if (position == null || position.isBlank() || position.equalsIgnoreCase("any")) {
            return 0;
        }
        return switch (position.trim().toLowerCase(Locale.ROOT)) {
            case "1", "gk", "gkp", "goalkeeper" -> 1;
            case "2", "def", "defender" -> 2;
            case "3", "mid", "midfielder" -> 3;
            case "4", "fwd", "forward" -> 4;
            default -> null;
        };
    }

    private static String positionLabel(int elementType) {
        return switch (elementType) {
            case 1 -> "GK";
            case 2 -> "DEF";
            case 3 -> "MID";
            case 4 -> "FWD";
            default -> "?";
        };
    }
}
