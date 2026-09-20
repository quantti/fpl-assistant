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
import java.util.OptionalDouble;

import dev.quantti.fplassistant.schedule.HorizonFixtures;
import dev.quantti.fplassistant.schedule.ScheduleFreshnessCheck;
import dev.quantti.fplassistant.schedule.ScheduleSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Pick-advice tools. {@link #getDifferentialPicks} and {@link #getCaptainCandidates} rank
 * eligible players on full-fixture expected FPL points, totalled over every fixture the club
 * plays in the next N calendar gameweeks, then on the average fixture difficulty of that same
 * window and ownership. Observed minutes and realised points never change the ranking; role
 * eligibility is a separate current-season evidence rule.
 *
 * <p><b>The window is calendar gameweeks, not a club's next N fixtures.</b> Taking the next N
 * fixtures silently drops the last gameweek the caller asked about whenever a club doubles inside
 * the window, and averaging the difficulty of the extra match gives it no advantage at all: a
 * second fixture is a second chance to score, so it belongs in the total. {@link HorizonFixtures}
 * derives that window once per call from the snapshot's own schedule, and a club with no known
 * window — or a known window with nothing in it — is excluded and counted rather than ranked on
 * an invented zero.
 *
 * <p><b>Three sources, three provenance lines</b> — the shape {@code SquadTools} and the my-team
 * tools already use. The candidate pool (identity, club, price, ownership, availability, news)
 * comes from {@code fpl_daily_price} at its latest capture and carries
 * {@link DailyPriceSource#provenance}; the metrics come from {@code player_fixture_stats} and
 * carry {@link SeasonContext#provenance()}; the fixture population every Score is totalled over
 * comes from the FPL schedule capture and carries
 * {@link dev.quantti.fplassistant.schedule.ScheduleSource#provenance}. The third line is a
 * <em>source</em> line, not a second season banner: historical metrics stay labelled historical
 * even when prices and future fixtures are the season being played. The weekly shared
 * {@code player} table — measured 85
 * days stale — supplies neither (KAR-32). Metrics attach by element id while the capture and the
 * resolved season are the same season, and by unique {@code web_name} when they are not: during
 * the changeover the capture already carries the new season under new element ids while the
 * resolver still names the completed one, and FPL renumbers every player in between, so the id
 * would name a different player. When nothing has been captured the tools refuse rather than
 * fall back to the weekly table — a pool built from stale prices is the bug, not a degraded
 * answer.
 *
 * <p>Under the three source lines every reply that read the metrics carries
 * {@link DataAge#METRIC_REFRESH} (KAR-9): the core-stat sync time of {@code player_fixture_stats}
 * is unknowable, and a factual recent-minutes note dates an observation rather than a metric
 * refresh. It is fixed text — no query, no clock read and no row-write timestamp stands in for
 * that time.
 *
 * <p><b>They used to rank on {@code form}, and that was the defect.</b> FPL's {@code form} is
 * average points per match over the last 30 days, so in GW2 it is GW1's scoreline and nothing
 * else: a defender who scored once led the list ahead of every defender at a side that concedes
 * nothing, and the model relayed the ordering as though it were a case. A points haul is a
 * result, not a reason.
 *
 * <p>The scale is FPL's own scoring, which makes one number fair across positions and across the
 * two kinds of defender. The projection assumes a full fixture: its two appearance points are
 * fixed, attack uses xG/xA ability, clean sheets are projected per fixture from both clubs'
 * expected-goal rates and venue (KAR-30), and DefCon uses the player's action-rate estimate. A
 * goal is worth 6 to a defender and 4 to a forward; a clean sheet is worth 4 to a defender, 1 to
 * a midfielder and nothing to a forward.
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
 * <p>Primary lists require today's {@code a} flag and a complete same-season current-club window.
 * A recent under-60 appearance is a factual note from the latest five complete observations only:
 * it neither excludes a player nor changes expected points. Doubtful, stale, partial and
 * inconsistent evidence is excluded rather than weakened into a probability claim. Passing this
 * historical check never guarantees the next lineup.
 */
@Component
public class PickTools implements FplTool {

    private static final Logger log = LoggerFactory.getLogger(PickTools.class);
    private static final int MAX_PICKS = 10;

    /** The longest horizon a caller can ask for, in calendar gameweeks. */
    private static final int MAX_GAMEWEEKS = 5;

    // Window constants live on ExpectedPoints, and the cross-season ramp on SeasonBlend — both
    // shared with CandidatePool
    // so the two tools cannot rank the same player two different ways in one conversation by
    // drifting apart on what "recent form" means.

    /** Sinks players whose window has no usable average difficulty below any player with one. */
    private static final double UNKNOWN_FDR = Double.MAX_VALUE;

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
            Eligibility requires a fresh available ('a') daily capture with valid position/club,
            plus coherent same-season identity and a complete current-club evidence window. The
            window includes every expected completed fixture, latest ten at most; known zeros
            count. Only a verified different-club observation fences the spell; otherwise expected
            fixtures start at season start. Doubtful, stale, missing, pending or inconsistent data
            causes abstention, and last-season history cannot certify a current role. An under-60
            appearance in the latest five complete observations is shown as a factual note only;
            it neither excludes a player nor changes the score. This history does not guarantee
            the next start.
            Ranking totals full-fixture expected FPL points over every fixture the club plays in
            the next nextN calendar gameweeks (clamped to 1-5, counted from the next editable
            deadline in that same schedule). It uses xG/xA ability, fixture-specific clean-sheet
            probability and DefCon; it does not use realised points, observed minutes, 60+ rates
            or expected minutes. A double gameweek adds a fixture, a blank removes one. A pick
            also needs a known horizon holding at least one fixture; clubs blanked across the
            window or with an unresolved schedule are excluded and counted, never ranked at zero.
            Ties break on the average FDR of those same fixtures, then ownership. Ability rates
            can blend seasons on the shared ramp; current-role eligibility never does. NOT ranked
            by form. The reply
            attributes three sources — the daily price capture, the metric season and the FPL
            schedule capture — and shows the editable gameweek range, any truncation at the end of
            the season, window evidence, exclusions and partial components. The metric season line
            covers the metrics only; price and fixtures come from the other two. Use for
            pick/differential questions with strategy guidance; do not fill an empty list with
            ineligible names. If whole-club comparison evidence is unavailable, the eligible-only
            rows are labelled RESTRICTED COMPARISON POPULATION before the rows; they are not a
            league-wide best list and cannot be used to assemble a squad.
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
                // No schedule was read for a reply that ranks nothing, so it carries no third
                // source line: naming a capture this answer never consulted would be a claim.
                return provenanceFor(capture, season, LocalDate.now(UkTime.zone()), null)
                        + "\nUnrecognised position \"" + position
                        + "\". Use 1/GK, 2/DEF, 3/MID, 4/FWD, or \"any\".";
            }
            int gameweekLimit = Math.max(1, Math.min(nextN, MAX_GAMEWEEKS));
            MetricSeasons metric = seasonResolver.metricSeasons(season);

            RankingMetrics.Snapshot snapshot = rankingMetrics.load(capture, metric);
            HorizonFixtures.Result horizon = horizon(snapshot, capture, gameweekLimit);
            String provenance = provenanceFor(capture, season,
                    LocalDate.ofInstant(snapshot.asOf(), UkTime.zone()), scheduleSource(snapshot))
                    + "\n" + snapshot.metricRefresh();
            Selection selection = candidates(snapshot, horizon, elementType, maxOwnership);
            if (selection.rows().isEmpty()) {
                return provenance + "\n" + scheduleEvidence(snapshot, horizon, selection) + "\n"
                        + ComparisonCoverageFormatter.restricted(snapshot.comparisonCoverage(),
                                snapshot.asOf()) + "\n"
                        + noQualifyingPlayers("differentials", capture.season(), selection, horizon);
            }

            return provenance + "\n\n"
                    + formatPicks("Differential picks", selection, snapshot, horizon, metric);
        } catch (DataAccessException e) {
            log.error("getDifferentialPicks(\"{}\", {}, {}) failed", position, maxOwnership, nextN, e);
            return "Pick data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    @Tool(description = """
            Suggest up to 10 FPL captain candidates from midfielders and forwards. Candidates need
            a fresh available ('a') daily capture with valid position/club, plus coherent
            same-season identity and a complete current-club evidence window. The window includes
            every expected completed fixture, latest ten at most; known zeros count. Only a verified
            different-club observation fences the spell; otherwise expected fixtures start at season
            start. Doubtful, stale, missing, pending or inconsistent data causes abstention, and
            last-season evidence cannot certify today's role. An under-60 appearance in the latest
            five complete observations is shown as a factual note only; it neither excludes a
            player nor changes the score. This history does not guarantee the next start.
            Ranking totals full-fixture expected FPL points over every fixture the club plays in
            the next nextN calendar gameweeks (clamped to 1-5, counted from the next editable
            deadline in that same schedule). It uses xG/xA ability, fixture-specific clean-sheet
            probability and DefCon; it does not use realised points, observed minutes, 60+ rates
            or expected minutes. A double gameweek adds a fixture, a blank removes one. A
            candidate also needs a known horizon holding at least one fixture; clubs blanked
            across the window or with an unresolved schedule are excluded and counted, never
            ranked at zero. Ties break on the average FDR of those same fixtures, then ownership.
            Ability rates may blend seasons; current eligibility never does. NOT ranked by form.
            The reply attributes
            three sources — the daily price capture, the metric season and the FPL schedule
            capture — and shows ownership, the editable gameweek range, any truncation at the end
            of the season, window evidence, exclusions and partial components. The metric season
            line covers the metrics only; price and fixtures come from the other two. Use with
            captaincy strategy; never add an ineligible fallback when the qualifying list is empty.
            If whole-club comparison evidence is unavailable, the eligible-only rows are labelled
            RESTRICTED COMPARISON POPULATION before the rows; they are not league-wide best picks.
            """)
    public String getCaptainCandidates(int nextN) {
        try {
            SeasonContext season = seasonResolver.resolve();
            Optional<DailyPriceSource.Capture> captured = daily.latestCapture();
            if (captured.isEmpty()) {
                return NO_CAPTURE;
            }
            DailyPriceSource.Capture capture = captured.get();
            int gameweekLimit = Math.max(1, Math.min(nextN, MAX_GAMEWEEKS));
            MetricSeasons metric = seasonResolver.metricSeasons(season);

            RankingMetrics.Snapshot snapshot = rankingMetrics.load(capture, metric);
            HorizonFixtures.Result horizon = horizon(snapshot, capture, gameweekLimit);
            String provenance = provenanceFor(capture, season,
                    LocalDate.ofInstant(snapshot.asOf(), UkTime.zone()), scheduleSource(snapshot))
                    + "\n" + snapshot.metricRefresh();
            Selection selection = candidates(snapshot, horizon, ATTACKERS, 100.0);
            if (selection.rows().isEmpty()) {
                return provenance + "\n" + scheduleEvidence(snapshot, horizon, selection) + "\n"
                        + ComparisonCoverageFormatter.restricted(snapshot.comparisonCoverage(),
                                snapshot.asOf()) + "\n"
                        + noQualifyingPlayers("captain candidates", capture.season(), selection,
                                horizon);
            }

            return provenance + "\n\n"
                    + formatPicks("Captain candidates", selection, snapshot, horizon, metric);
        } catch (DataAccessException e) {
            log.error("getCaptainCandidates({}) failed", nextN, e);
            return "Pick data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    /** Position filter meaning "midfielders and forwards" — the only positions worth captaining. */
    private static final int ATTACKERS = -1;

    /**
     * @param knownZeroHorizon players whose club is known to play nothing in the window — a real
     *                         blank, counted apart from player-evidence exclusions because the
     *                         evidence is the schedule's, not the player's
     * @param unknownHorizon   players whose club window could not be established at all
     */
    private record Selection(List<Map<String, Object>> rows,
                             Map<MinutesModel.Eligibility, Integer> exclusions,
                             int knownZeroHorizon, int unknownHorizon,
                             int matchingCapturePlayers) {
    }

    /**
     * The one horizon derivation per tool call, over the snapshot's own schedule and instant.
     * The capture's season governs, not the resolved metric season: across the changeover the
     * schedule and the candidate pool are the season being played while the metrics are still
     * last season's, and a horizon read against the wrong season names other clubs entirely.
     */
    private static HorizonFixtures.Result horizon(RankingMetrics.Snapshot snapshot,
                                                  DailyPriceSource.Capture capture,
                                                  int gameweekLimit) {
        return HorizonFixtures.derive(snapshot.schedule(), snapshot.asOf(), capture.season(),
                gameweekLimit);
    }

    /**
     * The three source lines — price, metrics, schedule — and, during the changeover, when the
     * capture already carries the new season while the resolver still names the completed one, a
     * clarification telling the model which line governs which numbers. Without it the season
     * line's "do not call this current" instruction reads as covering the whole reply, and
     * today's price and next month's fixtures get hedged as last season's. The age is judged on
     * the UK calendar, like every other daily-capture tool: FPL's day turns at 00:00 UK, not at
     * the container's midnight.
     *
     * @param scheduleSource the schedule capture's own {@code Data source:} line, or {@code null}
     *        for a reply that read no schedule at all
     */
    private static String provenanceFor(DailyPriceSource.Capture capture, SeasonContext season,
                                        LocalDate today, String scheduleSource) {
        StringBuilder provenance = new StringBuilder(
                DailyPriceSource.provenance(capture.season(), capture.date(), today))
                .append('\n').append(season.provenance());
        if (scheduleSource != null) {
            provenance.append('\n').append(scheduleSource);
        }
        if (!capture.season().equals(season.code())) {
            provenance.append("\nThe season line above describes the metrics only: price,"
                    + " ownership and availability on every pick are today's, from the capture,"
                    + " and may be quoted as current.");
            if (scheduleSource != null) {
                provenance.append(" The schedule line, like the price line, describes the season"
                        + " being played: the fixtures every Score is totalled over come from"
                        + " that capture, never from the metric season.");
            }
        }
        return provenance.toString();
    }

    /**
     * The schedule capture's own {@code Data source:} line — the third source, on the capture's
     * own season rather than the resolved one, for the reason {@code ScheduleSource} documents.
     * Before the first capture the line says so rather than being left out: a missing source is
     * itself the finding, and silence would read as though the fixtures were never in question.
     */
    private static String scheduleSource(RankingMetrics.Snapshot snapshot) {
        return snapshot.schedule().snapshot()
                .map(selected -> ScheduleSource.provenance(selected.season(),
                        selected.capturedAt(), snapshot.asOf()))
                .orElse("Data source: no FPL gameweek and fixture capture exists, so this reply"
                        + " has no schedule to count fixtures in.");
    }

    /**
     * What the schedule actually supported, beside the source line that names it: the exact
     * capture instant, the instant it was judged at, the editable window it produced and the
     * players the schedule alone removed. The two exclusion counts are the schedule's, not
     * player-evidence exclusions, and are repeated here so the evidence block stands on its own.
     */
    private static String scheduleEvidence(RankingMetrics.Snapshot snapshot,
                                           HorizonFixtures.Result horizon, Selection selection) {
        return captureEvidence(snapshot) + " " + horizonEvidence(horizon, selection);
    }

    private static String captureEvidence(RankingMetrics.Snapshot snapshot) {
        if (snapshot.schedule().snapshot().isEmpty()) {
            return "Schedule evidence: no FPL schedule capture is available, judged as of "
                    + snapshot.asOf() + ".";
        }
        var selected = snapshot.schedule().snapshot().orElseThrow();
        LocalDate capturedDay = LocalDate.ofInstant(selected.capturedAt(), UkTime.zone());
        boolean fresh = ScheduleFreshnessCheck.isFresh(capturedDay, snapshot.asOf());
        if (snapshot.schedule().completeness().complete() && fresh) {
            return "Schedule evidence: complete FPL schedule capture at " + selected.capturedAt()
                    + ", judged as of " + snapshot.asOf() + ".";
        }
        return "Schedule evidence: FPL schedule capture at " + selected.capturedAt()
                + ", judged as of " + snapshot.asOf() + "; structure "
                + snapshot.schedule().completeness().reason()
                + (fresh ? ", fresh for this snapshot." : ", not fresh for this snapshot.");
    }

    private static String horizonEvidence(HorizonFixtures.Result horizon, Selection selection) {
        String window = horizon.status() == HorizonFixtures.Status.KNOWN
                ? "Editable window " + gameweekRange(horizon) + " ("
                        + horizon.actualGameweeks() + " of the " + horizon.requestedGameweeks()
                        + " calendar gameweeks asked for"
                        + (horizon.truncated() ? ", truncated by the end of the season)." : ").")
                : "No editable window (" + reasonText(horizon) + ").";
        return window + " Removed by the schedule alone: " + selection.knownZeroHorizon()
                + " with no fixture in the window, " + selection.unknownHorizon()
                + " whose club window could not be read.";
    }

    private static String reasonText(HorizonFixtures.Result horizon) {
        return horizon.reason().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** One counted fixture's expected components, with the concession the clean sheet rests on. */
    record FixtureProjection(HorizonFixtures.Fixture fixture,
                             ExpectedPoints.PerFixture components,
                             OptionalDouble goalsAgainst) {
    }

    /** Consumer filters are applied only after RankingMetrics has seen the whole capture. */
    private static Selection candidates(RankingMetrics.Snapshot snapshot,
                                        HorizonFixtures.Result horizon,
                                        int elementType, double maxOwnership) {
        Map<Integer, String> shortNames = clubShortNames(snapshot);
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<MinutesModel.Eligibility, Integer> exclusions =
                new EnumMap<>(MinutesModel.Eligibility.class);
        int matching = 0;
        int knownZeroHorizon = 0;
        int unknownHorizon = 0;
        for (RankingMetrics.Candidate candidate : snapshot.candidates()) {
            RankingMetrics.CapturePlayer player = candidate.capture();
            if (!matchesFilter(player, elementType, maxOwnership)) {
                continue;
            }
            matching++;
            if (candidate.eligibility() != MinutesModel.Eligibility.ELIGIBLE
                    || candidate.blended() == null) {
                MinutesModel.Eligibility reason = candidate.eligibility()
                        == MinutesModel.Eligibility.ELIGIBLE
                                ? MinutesModel.Eligibility.UNKNOWN_CURRENT_EVIDENCE
                                : candidate.eligibility();
                exclusions.merge(reason, 1, Integer::sum);
                continue;
            }
            // Eligibility already guarantees a valid captured club id.
            HorizonFixtures.ClubWindow club = horizon.club(player.teamId());
            if (!club.known()) {
                unknownHorizon++;
                continue;
            }
            if (club.count() == 0) {
                knownZeroHorizon++;
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
            row.put("club_short_names", shortNames);
            if (ExpectedPoints.expectedPerFixture(row, OptionalDouble.empty()).isEmpty()) {
                exclusions.merge(MinutesModel.Eligibility.UNKNOWN_CURRENT_EVIDENCE, 1,
                        Integer::sum);
                continue;
            }
            List<FixtureProjection> projections = new ArrayList<>(club.count());
            double expectedHorizon = 0;
            for (HorizonFixtures.Fixture fixture : club.fixtures()) {
                OptionalDouble goalsAgainst = ClubRates.projectedGoalsAgainst(
                        snapshot.clubRates(), player.teamId(), fixture.opponentTeamId(),
                        fixture.home());
                ExpectedPoints.PerFixture components = ExpectedPoints.expectedPerFixture(row,
                        goalsAgainst).orElseThrow();
                projections.add(new FixtureProjection(fixture, components, goalsAgainst));
                expectedHorizon += components.points();
            }
            row.put("expected_per_fixture", expectedHorizon / club.count());
            row.put("projection", projections.getFirst().components());
            row.put("fixture_projections", List.copyOf(projections));
            row.put("ranking_candidate", candidate);
            rows.add(row);
        }
        return new Selection(List.copyOf(rows), Map.copyOf(exclusions),
                knownZeroHorizon, unknownHorizon, matching);
    }

    /**
     * Opponent short names for the per-fixture list, from the capture itself — the only same-season
     * naming this reply already trusts. A club with no captured player is named by its id rather
     * than left out: an unnamed opponent is still a fixture the Score counted.
     */
    private static Map<Integer, String> clubShortNames(RankingMetrics.Snapshot snapshot) {
        Map<Integer, String> names = new HashMap<>();
        for (RankingMetrics.Candidate candidate : snapshot.candidates()) {
            RankingMetrics.CapturePlayer player = candidate.capture();
            if (player.teamId() != null && player.teamShort() != null) {
                names.putIfAbsent(player.teamId(), player.teamShort());
            }
        }
        return Map.copyOf(names);
    }

    @SuppressWarnings("unchecked")
    private static String shortNameOf(Map<String, Object> row, HorizonFixtures.Fixture fixture) {
        Map<Integer, String> names =
                (Map<Integer, String>) row.getOrDefault("club_short_names", Map.of());
        return names.getOrDefault(fixture.opponentTeamId(), "team " + fixture.opponentTeamId());
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

    private static String noQualifyingPlayers(String kind, String season, Selection selection,
                                              HorizonFixtures.Result horizon) {
        String reply = "No " + kind + " qualify in " + season
                + " under the availability, evidence and editable-gameweek requirements. Checked "
                + selection.matchingCapturePlayers() + " matching capture players. Exclusions: "
                + exclusionCounts(selection) + ".";
        if (horizon.status() != HorizonFixtures.Status.KNOWN) {
            reply += " " + horizonProblem(horizon);
        }
        return reply + " Missing or stale evidence is unjudged, not evidence of poor football"
                + " ability; use the player stats and availability tools to discuss a named"
                + " risky player.";
    }

    /** Why no player has a horizon, said plainly — never as a forecast of zero points. */
    private static String horizonProblem(HorizonFixtures.Result horizon) {
        String reason = horizon.status() == HorizonFixtures.Status.NO_EDITABLE_GAMEWEEK
                ? "No editable gameweek remains in the captured schedule"
                : "The captured schedule cannot supply a gameweek horizon ("
                        + reasonText(horizon) + ")";
        return reason + ", so every candidate's fixture count is unknown rather than zero."
                + " Say so instead of ranking anyone; no older schedule is substituted.";
    }

    private static String exclusionCounts(Selection selection) {
        Map<MinutesModel.Eligibility, Integer> counts = selection.exclusions();
        return "availability risk " + counts.getOrDefault(
                MinutesModel.Eligibility.AVAILABILITY_RISK, 0)
                + ", unknown current evidence " + counts.getOrDefault(
                        MinutesModel.Eligibility.UNKNOWN_CURRENT_EVIDENCE, 0)
                + ", no fixtures in the horizon " + selection.knownZeroHorizon()
                + ", unknown horizon " + selection.unknownHorizon();
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
        return !rows.isEmpty() && rows.stream().allMatch(r ->
                r.get("xg90") instanceof Number && r.get("xa90") instanceof Number
                        && numeric(r.get("xg90")) == 0 && numeric(r.get("xa90")) == 0);
    }

    private String formatPicks(String heading, Selection selection,
                               RankingMetrics.Snapshot snapshot, HorizonFixtures.Result horizon,
                               MetricSeasons metric) {
        List<Map<String, Object>> rows = selection.rows();
        Map<Integer, HorizonFixtures.ClubWindow> windows = horizon.clubs();
        List<Map<String, Object>> ranked = rank(rows, windows);
        String range = gameweekRange(horizon);

        StringBuilder out = new StringBuilder();
        out.append(heading).append(" (").append(metric.played().code()).append("):\n")
                .append(scheduleEvidence(snapshot, horizon, selection)).append('\n')
                .append(ComparisonCoverageFormatter.restricted(snapshot.comparisonCoverage(),
                        snapshot.asOf())).append('\n');
        for (Map<String, Object> row : ranked.subList(0, Math.min(MAX_PICKS, ranked.size()))) {
            int position = ((Number) row.get("element_type")).intValue();
            OptionalDouble avgFdr = averageDifficulty(row, windows);
            RankingMetrics.Candidate candidate =
                    (RankingMetrics.Candidate) row.get("ranking_candidate");
            out.append(String.format(Locale.ROOT,
                    "  %s %s (%s) £%sm | Own %s%% | expected points/fixture %s"
                            + " | fixtures %d in %s | horizon expected points %s | %s%s%s"
                            + " | Avg FDR %s\n",
                    positionLabel(position), row.get("web_name"), row.get("team_short"),
                    fixed(((Number) row.get("now_cost")).intValue() / 10.0, 1),
                    row.get("selected_by_percent"), fixed(score(row), 2),
                    fixtureCount(row, windows), range, fixed(horizonScore(row, windows), 2),
                    components(row), blendSegment(row, metric), minutesNote(row, candidate, snapshot),
                    avgFdr.isPresent() ? fixed(avgFdr.getAsDouble(), 1) : "n/a"));
        }
        out.append(footer(metric, horizon));
        out.append("Eligible after filters: ").append(rows.size()).append(" of ")
                .append(selection.matchingCapturePlayers()).append(". Exclusions: ")
                .append(exclusionCounts(selection)).append(".\n");
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
            return " | " + cur + " only — no " + prev + " record (" + matches
                    + " fixtures this season)";
        }
        if (pct >= 100) {
            return " | " + cur + " only (" + matches + " fixtures this season)";
        }
        return " | blend " + pct + "% " + cur + ", " + (100 - pct) + "% " + prev
                + " (" + matches + " fixtures this season)";
    }

    private static String components(Map<String, Object> row) {
        ExpectedPoints.PerFixture projection =
                (ExpectedPoints.PerFixture) row.get("projection");
        return "appearance " + fixed(projection.appearance(), 2)
                + ", attack " + (projection.attack() == null
                        ? "n/a (partial: xG/xA unavailable)" : fixed(projection.attack(), 2))
                + ", " + cleanSheets(row)
                + ", DC " + fixed(projection.defensiveContribution(), 2)
                + (projection.partialDefcon() ? " (partial: DefCon evidence lacks two known rows)" : "");
    }

    /**
     * The clean-sheet term totalled over the window, then each fixture's chance beside the
     * opponent, venue and projected concession it came from. A fixture with no club projection
     * says n/a in that list; the total covers the fixtures that have one and the row is partial.
     */
    @SuppressWarnings("unchecked")
    private static String cleanSheets(Map<String, Object> row) {
        List<FixtureProjection> fixtures = (List<FixtureProjection>) row.get("fixture_projections");
        int position = ((Number) row.get("element_type")).intValue();
        if (ExpectedPoints.cleanSheetPoints(position) == 0) {
            return "CS 0.00 (none at this position)";
        }
        double total = 0;
        int unavailable = 0;
        List<String> legs = new ArrayList<>(fixtures.size());
        for (FixtureProjection leg : fixtures) {
            String opponent = "vs " + shortNameOf(row, leg.fixture())
                    + (leg.fixture().home() ? " h" : " a");
            if (leg.components().cleanSheet() == null || leg.goalsAgainst().isEmpty()) {
                unavailable++;
                legs.add(opponent + " n/a");
                continue;
            }
            total += leg.components().cleanSheet();
            legs.add(opponent + " " + Math.round(100 * ExpectedPoints.cleanSheetProbability(
                    leg.goalsAgainst().getAsDouble())) + "% at xGA "
                    + fixed(leg.goalsAgainst().getAsDouble(), 2));
        }
        String head = unavailable == fixtures.size()
                ? "CS n/a"
                : "CS " + fixed(total, 2) + " over " + (fixtures.size() - unavailable) + " fixture"
                        + (fixtures.size() - unavailable == 1 ? "" : "s");
        return head + " (" + String.join("; ", legs) + ")"
                + (unavailable > 0 && unavailable < fixtures.size()
                        ? " (partial: no club projection for " + unavailable + " fixture"
                                + (unavailable == 1 ? "" : "s") + ")" : "");
    }

    /** Factual disclosure only: recent low minutes neither alter the score nor exclude a player. */
    private static String minutesNote(Map<String, Object> row, RankingMetrics.Candidate candidate,
                                      RankingMetrics.Snapshot snapshot) {
        RankingMetrics.MinutesWindow window = candidate.current().window();
        if (window == null) {
            return "";
        }
        Map<Integer, ScheduleSource.FixtureRow> fixtures = new HashMap<>();
        snapshot.schedule().fixtures().forEach(fixture -> fixtures.put(fixture.id(), fixture));
        Map<Integer, String> clubNames = clubShortNames(snapshot);
        List<String> notices = new ArrayList<>();
        for (RankingMetrics.FixtureOutcome outcome : window.recentFixtures()) {
            if (outcome.minutes() == null || outcome.minutes() >= 60) {
                continue;
            }
            String opponent = outcome.opponentShort();
            if (opponent == null || opponent.isBlank()) {
                ScheduleSource.FixtureRow fixture = fixtures.get(outcome.fixtureId());
                opponent = "fixture " + outcome.fixtureId();
                if (fixture != null) {
                    int team = ((Number) row.get("team_id")).intValue();
                    int opponentId = fixture.homeTeamId() == team
                            ? fixture.awayTeamId() : fixture.homeTeamId();
                    opponent = clubNames.getOrDefault(opponentId, "team " + opponentId);
                }
            }
            LocalDate date = LocalDate.ofInstant(outcome.kickoff(), UkTime.zone());
            notices.add(outcome.minutes() + " minutes vs " + opponent + " on " + date);
        }
        return notices.isEmpty() ? "" : " | minutes note: " + String.join("; ", notices);
    }

    private String footer(MetricSeasons metric, HorizonFixtures.Result horizon) {
        String range = gameweekRange(horizon);
        StringBuilder out = new StringBuilder("\nRanked by expected FPL points: each player's"
                + " full-fixture expected score totalled over every fixture their club plays in"
                + " the next " + horizon.requestedGameweeks() + " calendar gameweeks, " + range
                + ".");
        if (horizon.truncated()) {
            out.append(" Only ").append(range).append(" remain before the season ends, so the ")
                    .append(horizon.requestedGameweeks())
                    .append(" gameweeks requested are truncated to ")
                    .append(horizon.actualGameweeks()).append('.');
        }
        out.append(" A double gameweek adds a fixture rather than displacing a later one, and a"
                + " blank removes one. Ties break on the average FDR of those same fixtures"
                + " (unknown last), then ownership, then element id; FDR is a tie-break, never"
                + " a multiplier.\n");
        out.append("Expected points use full-fixture xG/xA ability, fixture-specific clean-sheet"
                + " probability and DefCon. Observed minutes, 60+ history and realised points"
                + " do not alter the ranking. A recent under-60 appearance is a factual note on"
                + " that player's row only. A fixture with no club projection is n/a and its row"
                + " is partial.\n");
        if (metric.blending()) {
            out.append("Ability rates follow the one player-specific ramp: 1 complete current"
                    + " fixture 10%, 2: 30%, 3: 60%, 4: 80%, 5-7: 90%, 8+: 100%.\n");
        }
        out.append("Only fresh available players with a complete current-club window qualify."
                + " Missing/stale rows abstain rather than borrowing last season as current"
                + " evidence. A pick also needs a known horizon holding at least one fixture: a"
                + " club blanked across the whole window, or one whose schedule is unresolved,"
                + " is counted in the exclusions below rather than ranked at zero.\n")
                .append("Save and penalty-save points remain omitted, so goalkeepers are"
                        + " understated. The latest metric gameweek is ")
                .append(metric.latestGameweek()).append(" of ").append(metric.played().code())
                .append(".\n");
        return out.toString();
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

    /** Recommendation order is expected points only, without observed minutes or realised points. */
    static double score(Map<String, Object> row) {
        return numeric(row.get("expected_per_fixture"));
    }

    /**
     * What the whole horizon is worth: expected points per fixture times the number of fixtures the
     * club actually plays in the window. A double gameweek is an extra opportunity, so it lifts a
     * lesser player past a close single — 3 twice beats 5 once — without promoting him over a far
     * better one, since 3 twice still loses to 8 once.
     */
    static double horizonScore(Map<String, Object> row,
                               Map<Integer, HorizonFixtures.ClubWindow> windows) {
        return score(row) * fixtureCount(row, windows);
    }

    /**
     * Orders the candidate pool on the expected-points total the tool descriptions promise.
     *
     * <p>Done in Java because the horizon is an aggregate over the shared captured schedule
     * rather than a player column. Every comparison is on unrounded doubles; the element id
     * closes the order so two otherwise identical picks always come back the same way round.
     */
    static List<Map<String, Object>> rank(List<Map<String, Object>> rows,
                                          Map<Integer, HorizonFixtures.ClubWindow> windows) {
        Comparator<Map<String, Object>> byHorizon = Comparator.comparingDouble(
                (Map<String, Object> r) -> horizonScore(r, windows)).reversed();
        Comparator<Map<String, Object>> byFdr = Comparator.comparingDouble(
                r -> averageDifficulty(r, windows).orElse(UNKNOWN_FDR));
        Comparator<Map<String, Object>> tieBreak =
                Comparator.comparingDouble(r -> numeric(r.get("selected_by_percent")));
        Comparator<Map<String, Object>> byElementId =
                Comparator.comparingDouble(r -> numeric(r.get("id")));

        List<Map<String, Object>> sorted = new ArrayList<>(rows);
        sorted.sort(byHorizon.thenComparing(byFdr).thenComparing(tieBreak)
                .thenComparing(byElementId));
        return sorted;
    }

    /** Zero for a club the window cannot count, so a stray row can never outrank a real one. */
    private static int fixtureCount(Map<String, Object> row,
                                    Map<Integer, HorizonFixtures.ClubWindow> windows) {
        HorizonFixtures.ClubWindow club = clubWindow(row, windows);
        return club != null && club.known() ? club.count() : 0;
    }

    /** Empty unless every counted fixture of the window carries a valid FDR. */
    private static OptionalDouble averageDifficulty(
            Map<String, Object> row, Map<Integer, HorizonFixtures.ClubWindow> windows) {
        HorizonFixtures.ClubWindow club = clubWindow(row, windows);
        return club == null ? OptionalDouble.empty() : club.averageDifficulty();
    }

    private static HorizonFixtures.ClubWindow clubWindow(
            Map<String, Object> row, Map<Integer, HorizonFixtures.ClubWindow> windows) {
        return windows.get(((Number) row.get("team_id")).intValue());
    }

    /** The editable window as the reply names it, for example {@code GW4–8}. */
    private static String gameweekRange(HorizonFixtures.Result horizon) {
        return "GW" + horizon.startGameweek() + "–" + horizon.endGameweek();
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
