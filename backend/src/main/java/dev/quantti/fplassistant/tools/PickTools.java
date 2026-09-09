package dev.quantti.fplassistant.tools;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import dev.quantti.fplassistant.schedule.ScheduleFreshnessCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Pick-advice tools. {@link #getDifferentialPicks} and {@link #getCaptainCandidates} rank
 * eligible players on expected FPL points per fixture, blended with realised points per fixture,
 * then on average fixture difficulty from the same captured schedule, then ownership or observed
 * minutes. Role eligibility is a separate hard current-season evidence rule, not a score.
 *
 * <p><b>Two sources, two provenance lines</b> — the shape {@code SquadTools} and the my-team
 * tools already use. The candidate pool (identity, club, price, ownership, availability, news)
 * comes from {@code fpl_daily_price} at its latest capture and carries
 * {@link DailyPriceSource#provenance}; the metrics come from {@code player_fixture_stats} and
 * carry {@link SeasonContext#provenance()}. The weekly shared {@code player} table — measured 85
 * days stale — supplies neither (KAR-32). Metrics attach by element id while the capture and the
 * resolved season are the same season, and by unique {@code web_name} when they are not: during
 * the changeover the capture already carries the new season under new element ids while the
 * resolver still names the completed one, and FPL renumbers every player in between, so the id
 * would name a different player. When nothing has been captured the tools refuse rather than
 * fall back to the weekly table — a pool built from stale prices is the bug, not a degraded
 * answer.
 *
 * <p><b>They used to rank on {@code form}, and that was the defect.</b> FPL's {@code form} is
 * average points per match over the last 30 days, so in GW2 it is GW1's scoreline and nothing
 * else: a defender who scored once led the list ahead of every defender at a side that concedes
 * nothing, and the model relayed the ordering as though it were a case. A points haul is a
 * result, not a reason.
 *
 * <p>The scale is FPL's own scoring, which makes one number fair across positions and across the
 * two kinds of defender. Per-fixture appearance points are {@code P(play) + P(60+)}, attack is
 * scaled by expected minutes, clean sheets are conditional on reaching 60, and DefCon uses
 * separate short- and long-appearance exposure. A goal is worth 6 to a defender and 4 to a
 * forward; a clean sheet is worth 4 to a defender, 1 to a midfielder and nothing to a forward.
 *
 * <p><b>Realised points per fixture are blended in, weighted by how far the season has run.</b>
 * Underlying numbers are the better guide to what happens next, but actual return is evidence too.
 * The weight rises from nothing in the opening weeks to half by the season's midpoint
 * ({@link #MAX_REALISED_WEIGHT} at {@link #FULL_WEIGHT_GAMEWEEK}).
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
 * FPL's element id and is reassigned every season, and shared keeps no stable player code. A name
 * that is not unique in either season loses the previous-season side rather than being guessed —
 * a wrong metric attached to a recommendation is the failure this class exists to prevent.
 *
 * <p>Primary lists require today's {@code a} flag and a complete same-season current-club window:
 * at least 180 observed minutes and 60+ in every expected completed fixture, up to the latest ten.
 * Doubtful, stale, partial and inconsistent evidence is excluded rather than weakened into a
 * probability claim. One short appearance remains in the latest-ten window for a deliberately
 * conservative recovery wait. Passing this historical rule never guarantees the next lineup.
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

    private final SeasonResolver seasonResolver;
    private final DailyPriceSource daily;
    private final RankingMetrics rankingMetrics;

    public PickTools(SeasonResolver seasonResolver, DailyPriceSource daily,
                     RankingMetrics rankingMetrics) {
        this.seasonResolver = seasonResolver;
        this.daily = daily;
        this.rankingMetrics = rankingMetrics;
    }

    /** Same sentence as SquadTools: a pool from the weekly table is the failure KAR-32 is about. */
    private static final String NO_CAPTURE = "No player prices have been captured yet, so I cannot"
            + " rank picks. Tell the user the daily price capture has not run.";

    @Tool(description = """
            Suggest differential FPL picks at or below maxOwnership, optionally filtered by
            position (1=GK, 2=DEF, 3=MID, 4=FWD, or "any"). Returns up to 10 eligible players.
            Eligibility is the consistent observed 60+ history rule. The selected daily capture
            must be fresh and say available ('a'), with valid position/club; it, the current metric
            season and the fresh complete schedule must be the same season with coherent identity.
            The complete current-club window includes every expected completed fixture, latest ten
            at most, with at least 180 current-season minutes and 60+ in every fixture. Known zeros
            count. Only a verified different-club observation fences the spell; otherwise expected
            fixtures start at season start. Doubtful, stale, missing, pending or inconsistent data
            causes abstention; last-season history cannot certify a current role. One sub-60 fixture
            blocks until ten later completed club fixtures in that spell move it out. A short
            complete window can qualify after clearing the floor, but is labelled as a small
            sample. This history does not guarantee the next start.
            Ranking uses expected FPL points per fixture — appearance and 60+ chances, attack
            scaled by expected minutes, 60+-conditional clean sheets and short/long DefCon —
            blended with realised points per fixture, then FDR from that same schedule, then
            ownership. Ability and usable exposure rates can blend seasons on the shared ramp;
            raw current-role eligibility never does. NOT ranked by form. The reply shows source
            dates, window evidence, exclusions and partial components. Use for pick/differential
            questions with strategy guidance; do not fill an empty list with ineligible names.
            """)
    public String getDifferentialPicks(String position, double maxOwnership, int nextN) {
        try {
            SeasonContext season = seasonResolver.resolve();
            Optional<DailyPriceSource.Capture> captured = daily.latestCapture();
            if (captured.isEmpty()) {
                return NO_CAPTURE;
            }
            DailyPriceSource.Capture capture = captured.get();
            Integer elementType = parsePosition(position);
            if (elementType == null) {
                return provenanceFor(capture, season, LocalDate.now(UkTime.zone()))
                        + "\nUnrecognised position \"" + position
                        + "\". Use 1/GK, 2/DEF, 3/MID, 4/FWD, or \"any\".";
            }
            int fixtureLimit = Math.max(1, Math.min(nextN, MAX_FIXTURES));
            MetricSeasons metric = seasonResolver.metricSeasons(season);

            RankingMetrics.Snapshot snapshot = rankingMetrics.load(capture, metric);
            String provenance = provenanceFor(capture, season,
                    LocalDate.ofInstant(snapshot.asOf(), UkTime.zone()));
            Selection selection = candidates(snapshot, elementType, maxOwnership);
            if (selection.rows().isEmpty()) {
                return provenance + "\n" + scheduleEvidence(snapshot) + "\n"
                        + noQualifyingPlayers("differentials", capture.season(), selection);
            }

            return provenance + "\n\n"
                    + formatPicks("Differential picks", selection, snapshot, metric,
                            fixtureLimit, true);
        } catch (DataAccessException e) {
            log.error("getDifferentialPicks(\"{}\", {}, {}) failed", position, maxOwnership, nextN, e);
            return "Pick data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    @Tool(description = """
            Suggest up to 10 FPL captain candidates from midfielders and forwards. Primary
            candidates must pass the consistent observed 60+ history rule. The selected daily
            capture must be fresh and say available ('a'), with valid position/club; it, the current
            metric season and fresh complete schedule must be the same season with coherent
            identity. The complete current-club window includes every expected completed fixture,
            latest ten at most, with at least 180 current-season minutes and 60+ in every fixture.
            Known zeros count. Only a verified different-club observation fences the spell;
            otherwise expected fixtures start at season start. Doubtful, stale, missing, pending or
            inconsistent data causes abstention; last-season minutes cannot certify today's role.
            One sub-60 fixture blocks until ten later completed club fixtures in that spell move it
            out. A short complete window can qualify after clearing the floor, but is labelled as
            a small sample. This history does not guarantee the next lineup.
            Ranking uses expected FPL points per fixture — appearance and 60+ chances, attack
            scaled by expected minutes, 60+-conditional clean sheets and short/long DefCon —
            blended with realised points per fixture, then FDR from the same schedule, then
            observed minutes. Ability and usable exposure rates may blend seasons; raw current
            eligibility never does. NOT ranked by form. The reply shows ownership, source dates,
            window evidence, exclusions and partial components. Use with captaincy strategy;
            never add an ineligible fallback when the qualifying list is empty.
            """)
    public String getCaptainCandidates(int nextN) {
        try {
            SeasonContext season = seasonResolver.resolve();
            Optional<DailyPriceSource.Capture> captured = daily.latestCapture();
            if (captured.isEmpty()) {
                return NO_CAPTURE;
            }
            DailyPriceSource.Capture capture = captured.get();
            int fixtureLimit = Math.max(1, Math.min(nextN, MAX_FIXTURES));
            MetricSeasons metric = seasonResolver.metricSeasons(season);

            RankingMetrics.Snapshot snapshot = rankingMetrics.load(capture, metric);
            String provenance = provenanceFor(capture, season,
                    LocalDate.ofInstant(snapshot.asOf(), UkTime.zone()));
            Selection selection = candidates(snapshot, ATTACKERS, 100.0);
            if (selection.rows().isEmpty()) {
                return provenance + "\n" + scheduleEvidence(snapshot) + "\n"
                        + noQualifyingPlayers("captain candidates", capture.season(), selection);
            }

            return provenance + "\n\n"
                    + formatPicks("Captain candidates", selection, snapshot, metric,
                            fixtureLimit, false);
        } catch (DataAccessException e) {
            log.error("getCaptainCandidates({}) failed", nextN, e);
            return "Pick data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    /** Position filter meaning "midfielders and forwards" — the only positions worth captaining. */
    private static final int ATTACKERS = -1;

    private record Selection(List<Map<String, Object>> rows,
                             Map<MinutesModel.Eligibility, Integer> exclusions,
                             int matchingCapturePlayers) {
    }

    /**
     * The two provenance lines, and — during the changeover, when the capture already carries the
     * new season while the resolver still names the completed one — a third line telling the model
     * which line governs which numbers. Without it the season line's "do not call this current"
     * instruction reads as covering the whole reply, and today's price from the capture gets
     * hedged as last season's. The age is judged on the UK calendar, like every other
     * daily-capture tool: FPL's day turns at 00:00 UK, not at the container's midnight.
     */
    private static String provenanceFor(DailyPriceSource.Capture capture, SeasonContext season,
                                        LocalDate today) {
        String provenance = DailyPriceSource.provenance(capture.season(), capture.date(), today)
                + "\n" + season.provenance();
        if (!capture.season().equals(season.code())) {
            provenance += "\nThe season line above describes the metrics only: price, ownership"
                    + " and availability on every pick are today's, from the capture, and may be"
                    + " quoted as current.";
        }
        return provenance;
    }

    private static String scheduleEvidence(RankingMetrics.Snapshot snapshot) {
        if (snapshot.schedule().snapshot().isEmpty()) {
            return "Schedule evidence: no FPL schedule capture is available.";
        }
        var selected = snapshot.schedule().snapshot().orElseThrow();
        LocalDate capturedDay = LocalDate.ofInstant(selected.capturedAt(), UkTime.zone());
        boolean fresh = ScheduleFreshnessCheck.isFresh(capturedDay, snapshot.asOf());
        if (snapshot.schedule().completeness().complete() && fresh) {
            return "Schedule evidence: complete FPL schedule capture at "
                    + selected.capturedAt() + ".";
        }
        return "Schedule evidence: FPL schedule capture at " + selected.capturedAt()
                + "; structure " + snapshot.schedule().completeness().reason()
                + (fresh ? ", fresh for this snapshot." : ", not fresh for this snapshot.");
    }

    /** Consumer filters are applied only after RankingMetrics has seen the whole capture. */
    private static Selection candidates(RankingMetrics.Snapshot snapshot,
                                        int elementType, double maxOwnership) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<MinutesModel.Eligibility, Integer> exclusions =
                new EnumMap<>(MinutesModel.Eligibility.class);
        int matching = 0;
        for (RankingMetrics.Candidate candidate : snapshot.candidates()) {
            RankingMetrics.CapturePlayer player = candidate.capture();
            if (!matchesFilter(player, elementType, maxOwnership)) {
                continue;
            }
            matching++;
            if (candidate.eligibility() != MinutesModel.Eligibility.ELIGIBLE
                    || candidate.blended() == null || candidate.blended().exposure() == null) {
                MinutesModel.Eligibility reason = candidate.eligibility()
                        == MinutesModel.Eligibility.ELIGIBLE
                                ? MinutesModel.Eligibility.UNKNOWN_CURRENT_EVIDENCE
                                : candidate.eligibility();
                exclusions.merge(reason, 1, Integer::sum);
                continue;
            }
            Map<String, Object> row = new HashMap<>();
            row.put("id", player.elementId());
            row.put("web_name", player.webName());
            row.put("element_type", player.position());
            row.put("now_cost", player.nowCost());
            row.put("selected_by_percent", player.ownership());
            row.put("team_id", player.teamId());
            row.put("team_short", player.teamShort());
            row.put("status", player.status());
            row.put("news", player.news());
            row.putAll(candidate.blended().metrics());
            ExpectedPoints.PerFixture projection = ExpectedPoints.perFixture(
                    row, candidate.blended().exposure()).orElseThrow();
            row.put("expected_per_fixture", projection.points());
            row.put("points_per_fixture", candidate.blended().exposure().pointsPerFixture());
            row.put("current_window_minutes", candidate.current().window().totalMinutes());
            row.put("projection", projection);
            row.put("ranking_candidate", candidate);
            rows.add(row);
        }
        return new Selection(List.copyOf(rows), Map.copyOf(exclusions), matching);
    }

    private static boolean matchesFilter(RankingMetrics.CapturePlayer player,
                                         int elementType, double maxOwnership) {
        if (player.position() == null || player.position() < 1 || player.position() > 4
                || player.ownership() == null || player.ownership() > maxOwnership) {
            return false;
        }
        return elementType == 0 || (elementType == ATTACKERS
                ? player.position() == 3 || player.position() == 4
                : player.position() == elementType);
    }

    private static String noQualifyingPlayers(String kind, String season, Selection selection) {
        return "No " + kind + " qualify in " + season
                + " under the consistent observed 60+ history rule. Checked "
                + selection.matchingCapturePlayers() + " matching capture players. Exclusions: "
                + exclusionCounts(selection.exclusions()) + ". Missing or stale evidence is"
                + " unjudged, not evidence of poor football ability; use the player stats and"
                + " availability tools to discuss a named risky player.";
    }

    private static String exclusionCounts(Map<MinutesModel.Eligibility, Integer> counts) {
        return "availability risk " + counts.getOrDefault(
                MinutesModel.Eligibility.AVAILABILITY_RISK, 0)
                + ", observed minutes risk " + counts.getOrDefault(
                        MinutesModel.Eligibility.MINUTES_RISK, 0)
                + ", insufficient current evidence " + counts.getOrDefault(
                        MinutesModel.Eligibility.INSUFFICIENT_CURRENT_EVIDENCE, 0)
                + ", unknown current evidence " + counts.getOrDefault(
                        MinutesModel.Eligibility.UNKNOWN_CURRENT_EVIDENCE, 0);
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

    private String formatPicks(String heading, Selection selection,
                               RankingMetrics.Snapshot snapshot, MetricSeasons metric,
                               int fixtureLimit, boolean ownershipTieBreak) {
        List<Map<String, Object>> rows = selection.rows();
        Map<Integer, Double> fdrByTeam = avgFdrByTeam(snapshot, fixtureLimit);
        double realisedWeight = realisedWeight(metric.playedGameweeks());
        List<Map<String, Object>> ranked = rank(rows, fdrByTeam, ownershipTieBreak, realisedWeight);

        StringBuilder out = new StringBuilder();
        out.append(heading).append(" (").append(metric.played().code()).append("):\n")
                .append(scheduleEvidence(snapshot)).append('\n');
        for (Map<String, Object> row : ranked.subList(0, Math.min(MAX_PICKS, ranked.size()))) {
            int teamId = ((Number) row.get("team_id")).intValue();
            int position = ((Number) row.get("element_type")).intValue();
            Double avgFdr = fdrByTeam.get(teamId);
            RankingMetrics.Candidate candidate =
                    (RankingMetrics.Candidate) row.get("ranking_candidate");
            RankingMetrics.MinutesWindow window = candidate.current().window();
            out.append(String.format(Locale.ROOT,
                    "  %s %s (%s) £%sm | Own %s%% | Score/fixture %s | expected/fixture %s"
                            + " (%s) | realised points/fixture %s | observed current-club 60+ %d/%d,"
                            + " %d mins, %s%s%s | Avg FDR %s\n",
                    positionLabel(position), row.get("web_name"), row.get("team_short"),
                    fixed(((Number) row.get("now_cost")).intValue() / 10.0, 1),
                    row.get("selected_by_percent"), fixed(score(row, realisedWeight), 2),
                    fixed(numeric(row.get("expected_per_fixture")), 2), components(row),
                    fixed(numeric(row.get("points_per_fixture")), 2),
                    window.sixtyPlus(), window.expected(), window.totalMinutes(), dateRange(window),
                    window.expected() < ExpectedPoints.WINDOW_MATCHES
                            ? " (short window: " + window.expected() + " fixtures)" : "",
                    blendSegment(row, metric) + exposureSegment(candidate, metric),
                    avgFdr == null ? "n/a" : fixed(avgFdr, 1)));
        }
        if (fdrByTeam.isEmpty()) {
            out.append("\n(No unfinished fixtures with FDR were found in the verified schedule;"
                    + " FDR is n/a and the ranking falls back to the per-fixture score.)\n");
        }
        out.append(footer(metric, fixtureLimit, ownershipTieBreak, realisedWeight));
        out.append("Eligible after filters: ").append(rows.size()).append(" of ")
                .append(selection.matchingCapturePlayers()).append(". Exclusions: ")
                .append(exclusionCounts(selection.exclusions())).append(".\n");
        if (noUnderlyingSignal(ranked)) {
            out.append("WARNING: every player here has xG/90 and xA/90 of 0.00. Treat the")
                    .append(" underlying attacking columns as unavailable rather than as form.\n");
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

    private static String components(Map<String, Object> row) {
        ExpectedPoints.PerFixture projection =
                (ExpectedPoints.PerFixture) row.get("projection");
        return "appearance " + fixed(projection.appearance(), 2)
                + ", attack " + fixed(projection.attack(), 2)
                + (projection.cleanSheet() == null
                        ? ", CS n/a"
                        : ", CS " + fixed(projection.cleanSheet(), 2)
                                + " (season-average conditional at xGC "
                                + fixed(numeric(row.get("xgc_per_match")), 2) + ")")
                + ", DC " + fixed(projection.defensiveContribution(), 2)
                + (projection.partialDefcon() ? " (partial: a minutes class lacks two known rows)" : "");
    }

    private static String exposureSegment(RankingMetrics.Candidate candidate,
                                          MetricSeasons metric) {
        if (!metric.blending()) {
            return "";
        }
        if (candidate.previous() == null || candidate.previous().exposure() == null) {
            return candidate.previous() != null && !candidate.previous().ability().isEmpty()
                    ? " | prior exposure unavailable; prior ability still blends" : "";
        }
        int pct = (int) Math.round(candidate.blended().weight() * 100);
        return " | exposure blend " + pct + "% " + metric.played().code() + ", "
                + (100 - pct) + "% " + metric.previousCode();
    }

    private static String dateRange(RankingMetrics.MinutesWindow window) {
        if (window.firstKickoff() == null || window.lastKickoff() == null) {
            return "no completed fixture dates";
        }
        LocalDate first = LocalDate.ofInstant(window.firstKickoff(), UkTime.zone());
        LocalDate last = LocalDate.ofInstant(window.lastKickoff(), UkTime.zone());
        return first.equals(last) ? first.toString() : first + " to " + last;
    }

    private String footer(MetricSeasons metric, int fixtureLimit, boolean ownershipTieBreak,
                          double realisedWeight) {
        StringBuilder out = new StringBuilder("\nRanked by a per-fixture Score: expected components"
                + " carry " + Math.round((1 - realisedWeight) * 100)
                + "% and observed points per comparable club fixture carry "
                + Math.round(realisedWeight * 100) + "%. The realised weight keeps its existing"
                + " gameweek ramp and reaches at most " + Math.round(MAX_REALISED_WEIGHT * 100)
                + "%. Appearance uses P(play)+P(60+); attack scales by observed expected minutes;"
                + " clean sheets use P(60+) and a season-average conditional exp(-xGC)"
                + " approximation. Then average FDR of the next " + fixtureLimit
                + " unfinished fixtures, then "
                + (ownershipTieBreak ? "ownership" : "current-window minutes") + ". This score"
                + " is per fixture and does not by itself reward a double gameweek.\n");
        if (metric.blending()) {
            out.append("Ability and usable exposure rates follow the one player-specific ramp:"
                    + " 1 qualifying current-season match 10%, 2: 30%, 3: 60%, 4: 80%,"
                    + " 5-7: 90%, 8+: 100%. Raw current-club 60+ counts printed on each row are"
                    + " observed frequencies, not blended probabilities.\n");
        }
        out.append("Only fresh available players with a complete current-club window, at least " )
                .append(ExpectedPoints.MIN_WINDOW_MINUTES)
                .append(" current-season minutes and 60+ in every fixture qualify. A short")
                .append(" complete window is labelled; this consistent observed 60+ history is")
                .append(" not a guarantee of the next start. Missing/stale rows abstain rather")
                .append(" than borrowing last season as current-role proof.\n")
                .append("DefCon has no 60-minute eligibility rule: short and long appearance")
                .append(" action means contribute separately when each has at least ")
                .append(ExpectedPoints.MIN_DEFCON_MATCHES)
                .append(" known observations. Save and penalty-save points remain omitted, so")
                .append(" goalkeepers are understated. The latest metric gameweek is ")
                .append(metric.latestGameweek()).append(" of ").append(metric.played().code())
                .append(".\n");
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

    static double pointsPerFixture(Map<String, Object> row) {
        return numeric(row.get("points_per_fixture"));
    }

    /** Both sides are per fixture; appearance points already sit inside observed total points. */
    static double score(Map<String, Object> row, double realisedWeight) {
        return numeric(row.get("expected_per_fixture")) * (1 - realisedWeight)
                + pointsPerFixture(row) * realisedWeight;
    }

    /**
     * Orders the candidate pool on the composite the tool descriptions promise.
     *
     * <p>Done in Java because FDR is an aggregate over the shared captured schedule rather than
     * a player column.
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
                : Comparator.comparingDouble((Map<String, Object> r) -> numeric(
                        r.containsKey("current_window_minutes")
                                ? r.get("current_window_minutes") : r.get("mins"))).reversed();

        List<Map<String, Object>> sorted = new ArrayList<>(rows);
        sorted.sort(byScore.thenComparing(byFdr).thenComparing(tieBreak));
        return sorted;
    }

    private record TeamFdr(Integer gameweek, java.time.Instant kickoff, int fixtureId,
                           Integer difficulty) {
    }

    /** The existing next-N FDR tie-break, now read from the same coherent schedule snapshot. */
    private static Map<Integer, Double> avgFdrByTeam(RankingMetrics.Snapshot snapshot,
                                                     int fixtureLimit) {
        Map<Integer, List<TeamFdr>> fixtures = new HashMap<>();
        for (var fixture : snapshot.schedule().fixtures()) {
            if (fixture.finished()) {
                continue;
            }
            fixtures.computeIfAbsent(fixture.homeTeamId(), ignored -> new ArrayList<>())
                    .add(new TeamFdr(fixture.gameweek(), fixture.kickoff(), fixture.id(),
                            fixture.homeDifficulty()));
            fixtures.computeIfAbsent(fixture.awayTeamId(), ignored -> new ArrayList<>())
                    .add(new TeamFdr(fixture.gameweek(), fixture.kickoff(), fixture.id(),
                            fixture.awayDifficulty()));
        }
        Comparator<TeamFdr> order = Comparator
                .comparing(TeamFdr::gameweek, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TeamFdr::kickoff, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(TeamFdr::fixtureId);
        Map<Integer, Double> averages = new HashMap<>();
        fixtures.forEach((team, rows) -> {
            List<Integer> selected = rows.stream().sorted(order).limit(fixtureLimit)
                    .map(TeamFdr::difficulty).filter(java.util.Objects::nonNull).toList();
            if (!selected.isEmpty()) {
                averages.put(team, selected.stream().mapToInt(Integer::intValue).average().orElseThrow());
            }
        });
        return averages;
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
