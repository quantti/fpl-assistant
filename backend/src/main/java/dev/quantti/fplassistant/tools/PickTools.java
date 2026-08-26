package dev.quantti.fplassistant.tools;

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
 * <p>Not modelled, because shared does not carry the columns: defensive-contribution points, save
 * points and penalty saves. Goalkeepers and ball-winning defenders are therefore understated,
 * which the reply says.
 *
 * <p>The metrics come from whichever season has a real sample — the season being played once it
 * has {@link #MIN_SAMPLE_GAMEWEEKS} completed gameweeks, and the previous one until then, which
 * is the same rule the system prompt states for early-season judgements. When the metric season
 * is not the season being played, players are bridged across it by name: {@code player.id} is
 * FPL's element id and is reassigned every season, and shared keeps no stable player code. Names
 * that are not unique in either season are dropped rather than guessed — a wrong metric attached
 * to a recommendation is the failure this class exists to prevent.
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

    /** Matches of underlying data read per player — a season's worth of recent evidence. */
    private static final int WINDOW_MATCHES = 10;

    /**
     * Minutes a player must have inside that window to be ranked at all. Two full matches: below
     * that, a per-90 figure is arithmetic on noise, and a cameo with one big chance outranks a
     * regular starter.
     */
    private static final int MIN_WINDOW_MINUTES = 180;

    /**
     * Completed gameweeks the season being played needs before its own numbers are used. Under
     * this, the previous season is the evidence — the system prompt's early-season rule, applied
     * where the ordering is actually decided rather than left for the model to remember.
     */
    private static final int MIN_SAMPLE_GAMEWEEKS = 5;

    /** Sinks players whose fixtures are unknown below any player with a real FDR. */
    private static final double UNKNOWN_FDR = Double.MAX_VALUE;

    /** FPL scoring, indexed by element_type (1=GK, 2=DEF, 3=MID, 4=FWD). */
    private static final double[] GOAL_POINTS = {0, 6, 6, 5, 4};
    private static final double[] CLEAN_SHEET_POINTS = {0, 4, 4, 1, 0};
    private static final double ASSIST_POINTS = 3;
    private static final double APPEARANCE_POINTS = 2;

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

    /** Which season the underlying numbers were read from, and why it is not the resolved one. */
    private record MetricSeason(int seasonId, String code, boolean isResolvedSeason,
                                int playedGameweeks) {}

    @Tool(description = """
            Suggest differential FPL picks — players with low ownership (≤ maxOwnership
            percent) whose underlying numbers and upcoming fixtures are favourable. Filters by
            position (1=GK, 2=DEF, 3=MID, 4=FWD, or "any"). Returns up to 10 players ranked by
            underlying performance — expected goal involvements per 90 for midfielders and
            forwards, expected goals conceded per match for goalkeepers and defenders — then by
            the average difficulty of their next N fixtures, then by ownership. NOT ranked by
            points or form: a player's recent points haul is a result, not a reason, and the
            reply says which season the underlying numbers cover. Players who are injured,
            suspended or otherwise unavailable are excluded; doubtful players are included and
            flagged. Use for differential/pick questions and combine with strategy docs. The
            reply begins with the season the numbers come from.
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
            MetricSeason metric = metricSeason(season);

            List<Map<String, Object>> rows = candidates(season, metric, elementType, maxOwnership);
            if (rows.isEmpty()) {
                return season.provenance() + "\nNo differentials match those filters in "
                        + season.code() + " with a comparable underlying record"
                        + " (players who are injured or suspended are excluded, and so are"
                        + " players with under " + MIN_WINDOW_MINUTES + " minutes of "
                        + metric.code() + " football to judge them on).";
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
            players ranked by expected goal involvements (xG + xA) per 90 minutes, then by the
            average difficulty of their next N fixtures, then by total points, each shown with
            ownership (so the user can see template vs differential captains). NOT ranked by
            points or form: a big gameweek is a result, not a reason, and the reply says which
            season the underlying numbers cover. Players who are injured, suspended or otherwise
            unavailable are excluded; doubtful players are included and flagged. Use for
            captaincy questions and combine with strategy docs. The reply begins with the season
            the numbers come from.
            """)
    public String getCaptainCandidates(int nextN) {
        try {
            SeasonContext season = seasonResolver.resolve();
            int fixtureLimit = Math.max(1, Math.min(nextN, MAX_FIXTURES));
            MetricSeason metric = metricSeason(season);

            List<Map<String, Object>> rows = candidates(season, metric, ATTACKERS, 100.0);
            if (rows.isEmpty()) {
                return season.provenance() + "\nNo captain candidates in " + season.code()
                        + " have a comparable underlying record yet (players who are injured or"
                        + " suspended are excluded, and so are players with under "
                        + MIN_WINDOW_MINUTES + " minutes of " + metric.code()
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
     * Picks the season the underlying numbers come from: the resolved one once it holds a real
     * sample, the previous one until then. A one-match sample is noise whichever tool reads it.
     */
    private MetricSeason metricSeason(SeasonContext season) {
        int played = completedGameweeks(season.seasonId());
        if (played >= MIN_SAMPLE_GAMEWEEKS) {
            return new MetricSeason(season.seasonId(), season.code(), true, played);
        }
        Optional<SeasonContext> previous = seasonResolver.previousSeason(season);
        if (previous.isPresent() && completedGameweeks(previous.get().seasonId()) > 0) {
            SeasonContext prev = previous.get();
            return new MetricSeason(prev.seasonId(), prev.code(), false, played);
        }
        // Nothing better exists. Rank on the thin sample rather than refusing — the footer says
        // how thin it is, which is the honest version of answering at all.
        return new MetricSeason(season.seasonId(), season.code(), true, played);
    }

    private int completedGameweeks(int seasonId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(DISTINCT gameweek) FROM player_fixture_stats WHERE season_id = ?",
                Integer.class, seasonId);
        return count == null ? 0 : count;
    }

    /**
     * The candidate pool, already carrying each player's underlying numbers.
     *
     * <p>The whole qualifying pool comes back and is ranked in Java rather than cut in SQL. The
     * score is FPL's scoring applied to expected numbers, and writing it twice — once in SQL to
     * choose the pool, once in Java to order it — is how the two drift apart. A few hundred rows
     * is not worth that risk.
     */
    private List<Map<String, Object>> candidates(SeasonContext season, MetricSeason metric,
                                                 int elementType, double maxOwnership) {
        // Two literal alternatives chosen in code, never user input: same season joins on the
        // element id, a different season has to go through the name.
        String metricKey = metric.isResolvedSeason() ? "mp.id::text" : "LOWER(mp.web_name)";
        String poolKey = metric.isResolvedSeason() ? "pool.id::text" : "LOWER(pool.web_name)";

        return jdbc.queryForList("""
                WITH pool AS (
                    SELECT p.id, p.web_name, p.element_type, p.now_cost, p.selected_by_percent,
                           p.form, p.total_points, p.team_id, p.status, p.news,
                           t.short_name AS team_short
                    FROM player p
                    JOIN team t ON t.id = p.team_id AND t.season_id = p.season_id
                    WHERE p.season_id = ?
                      AND p.status IN ('a', 'd')
                      AND p.selected_by_percent <= ?
                      %s
                ),
                recent AS (
                    SELECT pfs.player_id, pfs.minutes, pfs.total_points,
                           pfs.expected_goals AS xg, pfs.expected_assists AS xa,
                           pfs.expected_goals_conceded AS xgc,
                           ROW_NUMBER() OVER (PARTITION BY pfs.player_id
                                              ORDER BY pfs.gameweek DESC) AS rn
                    FROM player_fixture_stats pfs
                    WHERE pfs.season_id = ?
                ),
                windowed AS (
                    SELECT player_id,
                           SUM(minutes) AS mins,
                           count(*) AS matches,
                           SUM(xg) * 90 / NULLIF(SUM(minutes), 0) AS xg90,
                           SUM(xa) * 90 / NULLIF(SUM(minutes), 0) AS xa90,
                           SUM(total_points) * 90.0 / NULLIF(SUM(minutes), 0) AS pts90,
                           -- Only matches the player was on for an hour count towards the clean
                           -- sheet: below that FPL awards none, and a cameo would otherwise
                           -- inherit the whole match's expected concession.
                           SUM(xgc) FILTER (WHERE minutes >= 60)
                               / NULLIF(count(*) FILTER (WHERE minutes >= 60), 0) AS xgc_per_match
                    FROM recent
                    WHERE rn <= %d
                    GROUP BY player_id
                    HAVING SUM(minutes) >= %d
                ),
                metrics AS (
                    SELECT %s AS k, min(w.mins) AS mins, min(w.matches) AS matches,
                           min(w.xg90) AS xg90, min(w.xa90) AS xa90, min(w.pts90) AS pts90,
                           min(w.xgc_per_match) AS xgc_per_match
                    FROM windowed w
                    JOIN player mp ON mp.id = w.player_id AND mp.season_id = ?
                    GROUP BY 1
                    HAVING count(*) = 1
                )
                SELECT pool.*, m.mins, m.matches, m.xg90, m.xa90, m.pts90, m.xgc_per_match
                FROM pool
                JOIN metrics m ON m.k = %s
                """.formatted(positionFilter(elementType, "p"), WINDOW_MATCHES,
                        MIN_WINDOW_MINUTES, metricKey, poolKey),
                season.seasonId(), maxOwnership, metric.seasonId(), metric.seasonId());
    }

    private static String positionFilter(int elementType, String alias) {
        return switch (elementType) {
            case ATTACKERS -> "AND " + alias + ".element_type IN (3, 4)";
            case 0 -> "";
            default -> "AND " + alias + ".element_type = " + elementType;
        };
    }

    /**
     * How many otherwise-eligible players had to be left out for want of a comparable record.
     * Reported rather than swallowed: a promoted side's defence and a summer signing are exactly
     * the picks a user asks about, and silently omitting them reads as "no such option".
     */
    private int withoutEvidence(SeasonContext season, MetricSeason metric, int elementType,
                                double maxOwnership) {
        String metricKey = metric.isResolvedSeason() ? "mp.id::text" : "LOWER(mp.web_name)";
        String poolKey = metric.isResolvedSeason() ? "p.id::text" : "LOWER(p.web_name)";

        Integer count = jdbc.queryForObject("""
                WITH metrics AS (
                    SELECT %s AS k
                    FROM player_fixture_stats pfs
                    JOIN player mp ON mp.id = pfs.player_id AND mp.season_id = pfs.season_id
                    WHERE pfs.season_id = ?
                    GROUP BY 1
                )
                SELECT count(*) FROM player p
                WHERE p.season_id = ?
                  AND p.status IN ('a', 'd')
                  AND p.selected_by_percent <= ?
                  %s
                  AND %s NOT IN (SELECT k FROM metrics)
                """.formatted(metricKey, positionFilter(elementType, "p"), poolKey),
                Integer.class, metric.seasonId(), season.seasonId(), maxOwnership);
        return count == null ? 0 : count;
    }

    private String formatPicks(String heading, List<Map<String, Object>> rows, SeasonContext season,
                               MetricSeason metric, int fixtureLimit, int elementType,
                               double maxOwnership, boolean ownershipTieBreak) {
        Integer startGw = startGameweek(season.seasonId());
        Map<Integer, Double> fdrByTeam = avgFdrByTeam(season.seasonId(), startGw, fixtureLimit);
        double realisedWeight = realisedWeight(metric.playedGameweeks());
        List<Map<String, Object>> ranked = rank(rows, fdrByTeam, ownershipTieBreak, realisedWeight);

        StringBuilder out = new StringBuilder(heading).append(" (").append(season.code()).append("):\n");
        String qual = season.currentSeason() ? "" : " (" + season.code() + ")";
        for (Map<String, Object> r : ranked.subList(0, Math.min(MAX_PICKS, ranked.size()))) {
            int teamId = ((Number) r.get("team_id")).intValue();
            int position = ((Number) r.get("element_type")).intValue();
            Double avgFdr = fdrByTeam.get(teamId);
            out.append("  %s %s (%s) £%.1fm%s | Own %s%%%s | Score %.2f | xPts/90 %.2f (%s) | Pts/90 %.2f | Mins %s (%s mt) | Avg FDR %s%s\n"
                    .formatted(
                            positionLabel(position),
                            r.get("web_name"), r.get("team_short"),
                            ((Number) r.get("now_cost")).intValue() / 10.0, qual,
                            r.get("selected_by_percent"), qual,
                            score(r, realisedWeight),
                            expectedPointsPer90(r),
                            components(r, position),
                            pointsPer90(r),
                            r.get("mins"), r.get("matches"),
                            avgFdr == null ? "n/a" : String.format(Locale.ROOT, "%.1f", avgFdr),
                            availability(r)));
        }
        if (fdrByTeam.isEmpty()) {
            out.append("\n(No upcoming fixtures found — FDR is n/a and the ranking falls back to"
                    + " the score alone. Fixtures for this season may not be scheduled yet.)\n");
        }
        out.append(footer(metric, fixtureLimit, ownershipTieBreak, realisedWeight));

        int missing = withoutEvidence(season, metric, elementType, maxOwnership);
        if (missing > 0) {
            out.append(missing).append(" further player(s) matching these filters are not listed:")
                    .append(" no comparable ").append(metric.code()).append(" record — new to the")
                    .append(" league, or a name that is not unique across the two seasons. Say so")
                    .append(" if the user asks about someone who is missing; do not report them")
                    .append(" as a poor pick.\n");
        }
        return out.toString();
    }

    /** The parts of xPts/90, so the model can say *why* a player ranks where they do. */
    private static String components(Map<String, Object> row, int position) {
        String attacking = "xG/90 %.2f, xA/90 %.2f".formatted(
                numeric(row.get("xg90")), numeric(row.get("xa90")));
        if (CLEAN_SHEET_POINTS[position] == 0) {
            return attacking;
        }
        Object xgc = row.get("xgc_per_match");
        return xgc == null
                ? attacking + ", CS n/a"
                : attacking + ", CS %.0f%% at xGC %.2f".formatted(
                        cleanSheetProbability(numeric(xgc)) * 100, numeric(xgc));
    }

    private String footer(MetricSeason metric, int fixtureLimit, boolean ownershipTieBreak,
                          double realisedWeight) {
        StringBuilder out = new StringBuilder("\nRanked by Score over each player's last "
                + WINDOW_MATCHES + " matches in " + metric.code() + ". Score blends xPts/90 —"
                + " expected FPL points per 90, built from xG, xA and the clean-sheet probability"
                + " implied by expected goals conceded, each valued at that position's own points"
                + " — with Pts/90, the points actually scored. Realised points carry "
                + Math.round(realisedWeight * 100) + "% of the weight here, rising towards "
                + Math.round(MAX_REALISED_WEIGHT * 100) + "% by gameweek " + FULL_WEIGHT_GAMEWEEK
                + ". Then average FDR of the next " + fixtureLimit + " fixtures, then "
                + (ownershipTieBreak ? "ownership" : "total points") + ".\n");
        out.append("This is why both kinds of defender can appear: an attacking full-back earns"
                + " through xG and xA, a defender at a mean side through the clean-sheet term."
                + " Read the bracket to see which, and say so — they are not interchangeable"
                + " picks.\n");
        if (!metric.isResolvedSeason()) {
            out.append("The underlying numbers are ").append(metric.code())
                    .append("'s because this season has ").append(metric.playedGameweeks())
                    .append(" completed gameweek(s) — too few to judge anyone on. Attribute them")
                    .append(" to ").append(metric.code())
                    .append(" in your answer and never call them this season's form.\n");
        } else if (metric.playedGameweeks() < MIN_SAMPLE_GAMEWEEKS) {
            out.append("Only ").append(metric.playedGameweeks())
                    .append(" completed gameweek(s) of data exist and no earlier season is")
                    .append(" available, so this is a thin sample. Say so.\n");
        }
        out.append("Defensive-contribution points, save points and penalty saves are not in the")
                .append(" data and so are not in xPts/90: goalkeepers and ball-winning defenders")
                .append(" are understated, and Pts/90 is where their real return shows up.\n")
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

    /** Poisson: the chance a side with this expected concession lets in none. */
    static double cleanSheetProbability(double expectedGoalsConceded) {
        return Math.exp(-expectedGoalsConceded);
    }

    /**
     * FPL's own scoring applied to expected numbers, which is what lets one figure compare a
     * forward with a goalkeeper and an attacking full-back with a stopper. A goal is worth 6 to a
     * defender and 4 to a forward; a clean sheet 4 to a defender, 1 to a midfielder, nothing to a
     * forward. Appearance points are a constant and do not affect the order — they are here so
     * the number reads as points rather than as an index.
     */
    static double expectedPointsPer90(Map<String, Object> row) {
        int position = ((Number) row.get("element_type")).intValue();
        double points = APPEARANCE_POINTS
                + numeric(row.get("xg90")) * GOAL_POINTS[position]
                + numeric(row.get("xa90")) * ASSIST_POINTS;
        Object xgc = row.get("xgc_per_match");
        if (xgc != null && CLEAN_SHEET_POINTS[position] > 0) {
            points += cleanSheetProbability(numeric(xgc)) * CLEAN_SHEET_POINTS[position];
        }
        return points;
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

    private static double numeric(Object value) {
        return value == null ? 0.0 : ((Number) value).doubleValue();
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
