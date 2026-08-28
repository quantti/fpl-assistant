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
 * <p>Not modelled: defensive-contribution points, save points and penalty saves. Only the first
 * of those is missing from the data — {@code player_fixture_stats} carries {@code saves} and
 * {@code penalties_saved}, and leaving them out is a choice, not a gap. Goalkeepers and
 * ball-winning defenders are understated either way, which the reply says.
 *
 * <p>The metrics come from whichever season has a real sample — the season being played once it
 * has {@link ExpectedPoints#MIN_SAMPLE_GAMEWEEKS} completed gameweeks, and the previous one until
 * then, which is the same rule the system prompt states for early-season judgements. When the
 * metric season is not the season being played, players are bridged across it by name:
 * {@code player.id} is FPL's element id and is reassigned every season, and tapas keeps no stable
 * player code. Names that are not unique in either season are dropped rather than guessed — a
 * wrong metric attached to a recommendation is the failure this class exists to prevent.
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

    // Window and sample-size constants (WINDOW_MATCHES, WHOLE_SEASON, MIN_WINDOW_MINUTES,
    // MIN_SAMPLE_GAMEWEEKS) live on ExpectedPoints now, shared with CandidatePool so the two
    // tools cannot rank the same player two different ways in one conversation by drifting apart
    // on what "recent form" means.

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

    /**
     * Which season the underlying numbers were read from.
     *
     * <p>Two flags, because two different questions were being answered by one and getting the
     * second wrong. {@code isResolvedSeason} decides the SQL join key — the same season joins on
     * the element id, a different one has to go through the name. {@code beingPlayed} decides the
     * window: "the last 10 matches" means recent form only for a season in progress, and the
     * resolver returns a *completed* season for the whole changeover window, which put the window
     * back on the run-in it was written to avoid.
     */
    private record MetricSeason(int seasonId, String code, boolean isResolvedSeason,
                                boolean beingPlayed, int playedGameweeks, int latestGameweek,
                                String provenance) {}

    @Tool(description = """
            Suggest differential FPL picks — players with low ownership (≤ maxOwnership
            percent) whose underlying numbers and upcoming fixtures are favourable. Filters by
            position (1=GK, 2=DEF, 3=MID, 4=FWD, or "any"). Returns up to 10 players ranked by a
            Score: expected FPL points per 90 (xG and xA at that position's own goal and assist
            values, plus the clean-sheet probability implied by expected goals conceded), blended
            with the points actually scored per 90 at a weight that rises as the season runs.
            Then the average difficulty of their next N fixtures, then ownership. NOT ranked by
            form: a recent points haul is a result, not a reason. Each pick shows the parts behind
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
            MetricSeason metric = metricSeason(season);

            List<Map<String, Object>> rows = candidates(season, metric, elementType, maxOwnership);
            if (rows.isEmpty()) {
                return season.provenance() + "\nNo differentials match those filters in "
                        + season.code() + " with a comparable underlying record"
                        + " (players who are injured or suspended are excluded, and so are"
                        + " players with under " + ExpectedPoints.MIN_WINDOW_MINUTES + " minutes of "
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
            players ranked by a Score: expected FPL points per 90 from xG and xA at that
            position's own values, blended with the points actually scored per 90 at a weight
            that rises as the season runs. Then the average difficulty of their next N fixtures,
            then total points, each shown with ownership (so the user can see template vs
            differential captains). NOT ranked by form: a big gameweek is a result, not a reason,
            and the reply says which season the numbers cover. Players who are injured, suspended or otherwise
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
                        + ExpectedPoints.MIN_WINDOW_MINUTES + " minutes of " + metric.code()
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
        int played = seasonResolver.completedGameweeks(season.seasonId());
        Optional<SeasonContext> better = seasonResolver.betterMetricSeason(season, played,
                ExpectedPoints.MIN_SAMPLE_GAMEWEEKS);
        if (better.isPresent()) {
            // A previous season is never the one being played, whatever the resolver says. It
            // carries its own provenance line: the banner above names the season being played,
            // and every number below this point is not from it. AGENTS.md requires each block to
            // be attributed, and false is right here — none of the season being played's own
            // numbers accompany these.
            SeasonContext prev = better.get();
            return new MetricSeason(prev.seasonId(), prev.code(), false, false, played,
                    latestGameweek(prev.seasonId()), prev.previousSeasonProvenance(false));
        }
        // Nothing better exists. Rank on the thin sample rather than refusing — the footer says
        // how thin it is, which is the honest version of answering at all.
        return new MetricSeason(season.seasonId(), season.code(), true, season.currentSeason(),
                played, latestGameweek(season.seasonId()), null);
    }

    /**
     * The newest gameweek the underlying data actually reaches. Printed for the reason
     * {@code getTeamForm} prints its most recent match date: a stalled tapas sync otherwise
     * ranks on weeks-old numbers with nothing in the output to show it.
     */
    private int latestGameweek(int seasonId) {
        Integer gw = jdbc.queryForObject(
                "SELECT COALESCE(max(gameweek), 0) FROM player_fixture_stats WHERE season_id = ?",
                Integer.class, seasonId);
        return gw == null ? 0 : gw;
    }

    /** How many matches back the metric window reaches. See {@link ExpectedPoints#WINDOW_MATCHES}. */
    private static int windowLimit(MetricSeason metric) {
        return metric.beingPlayed() ? ExpectedPoints.WINDOW_MATCHES : ExpectedPoints.WHOLE_SEASON;
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
        // Only the name bridge can attach one player's record to another. Joining on the element
        // id inside one season is exact, and demanding unique names there would throw away both
        // of any two players who happen to share one.
        String metricUnique = metric.isResolvedSeason() ? "" : "WHERE " + nameIsUnique("mp");
        String poolUnique = metric.isResolvedSeason() ? "" : "WHERE " + nameIsUnique("pool");

        return jdbc.queryForList("""
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
                    SELECT pfs.player_id, pfs.minutes, pfs.total_points,
                           pfs.expected_goals AS xg, pfs.expected_assists AS xa,
                           pfs.expected_goals_conceded AS xgc,
                           -- fixture_id breaks the tie: a double gameweek gives one player two
                           -- rows with the same gameweek, and an unordered pair straddling the
                           -- window edge makes the same call return different numbers.
                           ROW_NUMBER() OVER (PARTITION BY pfs.player_id
                                              ORDER BY pfs.gameweek DESC, pfs.fixture_id DESC) AS rn
                    FROM player_fixture_stats pfs
                    WHERE pfs.season_id = ?
                ),
                windowed AS (
                    SELECT player_id,
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
                    %s
                    GROUP BY 1
                    HAVING count(*) = 1
                )
                SELECT pool.*, m.mins, m.matches, m.xg90, m.xa90, m.pts90, m.xgc_per_match
                FROM pool
                JOIN metrics m ON m.k = %s
                %s
                """.formatted(positionFilter(elementType, "p"), windowLimit(metric),
                        ExpectedPoints.MIN_WINDOW_MINUTES, metricKey, metricUnique, poolKey, poolUnique),
                season.seasonId(), maxOwnership, metric.seasonId(), metric.seasonId());
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
                               MetricSeason metric, int fixtureLimit, int elementType,
                               double maxOwnership, boolean ownershipTieBreak) {
        Integer startGw = startGameweek(season.seasonId());
        Map<Integer, Double> fdrByTeam = avgFdrByTeam(season.seasonId(), startGw, fixtureLimit);
        double realisedWeight = realisedWeight(metric.playedGameweeks());
        List<Map<String, Object>> ranked = rank(rows, fdrByTeam, ownershipTieBreak, realisedWeight);

        StringBuilder out = new StringBuilder();
        if (metric.provenance() != null) {
            out.append(metric.provenance()).append("\n\n");
        }
        out.append(heading).append(" (").append(metric.code()).append("):\n");
        String qual = season.currentSeason() ? "" : " (" + season.code() + ")";
        for (Map<String, Object> r : ranked.subList(0, Math.min(MAX_PICKS, ranked.size()))) {
            int teamId = ((Number) r.get("team_id")).intValue();
            int position = ((Number) r.get("element_type")).intValue();
            Double avgFdr = fdrByTeam.get(teamId);
            out.append(String.format(Locale.ROOT,
                    "  %s %s (%s) £%sm%s | Own %s%%%s | Score %s | xPts/90 %s (%s) | Pts/90 %s"
                            + " | Mins %s (%s mt) | Avg FDR %s%s\n",
                    positionLabel(position),
                    r.get("web_name"), r.get("team_short"),
                    fixed(((Number) r.get("now_cost")).intValue() / 10.0, 1), qual,
                    r.get("selected_by_percent"), qual,
                    fixed(score(r, realisedWeight), 2),
                    fixed(expectedPointsPer90(r), 2),
                    components(r, position),
                    fixed(pointsPer90(r), 2),
                    r.get("mins"), r.get("matches"),
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
                    .append(metric.code())
                    .append(" record — new to the league, too few minutes to judge, or a name")
                    .append(" that is not unique in its season. If the user asks about someone")
                    .append(" absent, that is why: they are unjudged, not judged and rejected.\n");
        }
        if (noUnderlyingSignal(ranked)) {
            out.append("WARNING: every player here has xG/90 and xA/90 of 0.00. That is a column")
                    .append(" that was never populated for ").append(metric.code())
                    .append(", not a league of players who create nothing — the ranking has")
                    .append(" fallen back to fixtures and ownership. Tell the user the underlying")
                    .append(" numbers are unavailable rather than presenting this as form.\n");
        }
        return out.toString();
    }

    /** The parts of xPts/90, so the model can say *why* a player ranks where they do. */
    private static String components(Map<String, Object> row, int position) {
        String attacking = "xG/90 " + fixed(numeric(row.get("xg90")), 2)
                + ", xA/90 " + fixed(numeric(row.get("xa90")), 2);
        if (ExpectedPoints.cleanSheetPoints(position) == 0) {
            return attacking;
        }
        Object xgc = row.get("xgc_per_match");
        return ExpectedPoints.hasCleanSheetSignal(xgc)
                ? attacking + ", CS %s%% at xGC %s".formatted(
                        fixed(cleanSheetProbability(numeric(xgc)) * 100, 0), fixed(numeric(xgc), 2))
                : attacking + ", CS n/a";
    }

    private String footer(MetricSeason metric, int fixtureLimit, boolean ownershipTieBreak,
                          double realisedWeight) {
        String span = metric.beingPlayed()
                ? "each player's last " + ExpectedPoints.WINDOW_MATCHES + " matches in " + metric.code()
                : "all of " + metric.code();
        StringBuilder out = new StringBuilder("\nRanked by Score over "
                + span + ". Score blends xPts/90 —"
                + " expected FPL points per 90, built from xG, xA and the clean-sheet probability"
                + " implied by expected goals conceded, each valued at that position's own points"
                + " — with Pts/90, the points actually scored. Realised points carry "
                + Math.round(realisedWeight * 100) + "% of the weight here"
                + (metric.beingPlayed()
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
        if (!metric.isResolvedSeason()) {
            out.append("The underlying numbers are ").append(metric.code())
                    .append("'s because this season has ").append(metric.playedGameweeks())
                    .append(" completed gameweek(s) — too few to judge anyone on. Attribute them")
                    .append(" to ").append(metric.code())
                    .append(" in your answer and never call them this season's form.\n");
        } else if (!metric.beingPlayed()) {
            out.append("This season is not under way in the data yet, so these are ")
                    .append(metric.code())
                    .append("'s numbers over the whole season. Attribute them to it and never")
                    .append(" call them current form.\n");
        } else if (metric.playedGameweeks() < ExpectedPoints.MIN_SAMPLE_GAMEWEEKS) {
            out.append("Only ").append(metric.playedGameweeks())
                    .append(" completed gameweek(s) of data exist and no earlier season is")
                    .append(" available, so this is a thin sample. Say so.\n");
        }
        out.append("The most recent gameweek in this data is ").append(metric.latestGameweek())
                .append(" of ").append(metric.code())
                .append(". If that is well behind the gameweek being played, the statistics sync")
                .append(" has stalled and these numbers are older than they look — say so.\n");
        out.append("xPts/90 covers goals, assists and clean sheets only. Save points, penalty")
                .append(" saves and defensive-contribution points are not in it, so goalkeepers")
                .append(" and ball-winning defenders are understated; Pts/90 is where their real")
                .append(" return shows up. Do not call xPts/90 a complete projection.\n")
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
