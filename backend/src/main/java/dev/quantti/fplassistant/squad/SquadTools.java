package dev.quantti.fplassistant.squad;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import dev.quantti.fplassistant.tools.ComparisonCoverageFormatter;
import dev.quantti.fplassistant.tools.DailyPriceSource;
import dev.quantti.fplassistant.schedule.HorizonFixtures;
import dev.quantti.fplassistant.schedule.ScheduleFreshnessCheck;
import dev.quantti.fplassistant.schedule.ScheduleSource;
import dev.quantti.fplassistant.tools.MetricSeasons;
import dev.quantti.fplassistant.tools.FplTool;
import dev.quantti.fplassistant.tools.RankingMetrics;
import dev.quantti.fplassistant.tools.SeasonContext;
import dev.quantti.fplassistant.tools.SeasonResolver;
import dev.quantti.fplassistant.tools.UkTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Builds a whole FPL squad under stated constraints.
 *
 * <p><b>Why this is a solver and not a prompt.</b> Picking fifteen players inside a budget, at
 * fixed position quotas, at most three per club, around a set of named players, is a constrained
 * optimisation problem. Asked to do it in prose the model invented players who do not exist,
 * claimed a club constraint it had not met, and produced a total that did not match its own
 * table. None of those are prompt failures; they are the wrong tool for the job.
 *
 * <p><b>Provenance separates four sources.</b> Prices, clubs, ownership and availability come from the
 * daily capture; player metrics come from {@code player_fixture_stats}, which may be a season behind;
 * the fixtures every projection is summed over come from the FPL schedule capture. One banner
 * cannot describe those sources or Understat's separate team xG/xGA capture. A price or fixture attributed to the wrong season is the
 * error the provenance convention exists to prevent. The schedule line is a <em>source</em> line,
 * not a second season banner: historical metrics stay labelled historical even when prices and
 * future fixtures are the season being played. This is a documented exception, like
 * {@code MyTeamTools}.
 */
@Component
public class SquadTools implements FplTool {

    private static final Logger log = LoggerFactory.getLogger(SquadTools.class);

    private final CandidatePool pool;
    private final SeasonResolver seasonResolver;

    public SquadTools(CandidatePool pool, SeasonResolver seasonResolver) {
        this.pool = pool;
        this.seasonResolver = seasonResolver;
    }

    @Tool(description = """
            Build a complete 15-player FPL squad under a budget, mustInclude, mustExclude,
            clubQuota and optional formation. Returns fifteen real players, a computed total,
            legal starting XI, ordered bench, captain and vice-captain — or a factual refusal.
            Use for wildcard, free hit, pre-season, "build me a team", and any whole-squad
            constraint. Do NOT assemble a squad yourself: this enforces 2 GK, 5 DEF, 5 MID,
            3 FWD, at most 3 per club, budget and legal-XI rules.
            clubQuota accepts "Arsenal:3", "Arsenal:>=2" or "Arsenal:<=2". Player lists are
            comma-separated. budget is millions, default 100.0; for a wildcard use squad value
            plus bank from getMyTeam. nextN defaults to 5. formation may force "3-4-3".
            Every XI player needs a fresh available ('a') daily capture with valid position/club,
            coherent same-season identity and a complete current-club evidence window. Minutes do
            not affect a player's score or XI eligibility; an under-60 appearance in the latest
            five complete observations is a factual pick-list note only. Only a verified
            different-club observation fences the spell; otherwise expected fixtures start at
            season start. Doubtful, stale, missing, pending or inconsistent data causes abstention,
            and last-season history cannot certify the current role. Every starter must also play
            for a club with at least one fixture in the editable window of the next nextN calendar
            gameweeks, counted from the next deadline in the captured schedule. A club blanked
            across that window, or one whose schedule is unresolved, can be bought for the bench
            only; its rows print a real zero and an n/a respectively, never each other. A general
            squad is refused as COMPARISON_DATA_INCOMPLETE when a relevant elapsed unfinished
            fixture or a completed club side without usable verified core rows removes a whole
            club from comparison. Filters, pins and constraints cannot hide or waive that refusal.
            An ineligible mustInclude may be bought only on the bench. This history is not a
            future-start guarantee, and a pin, budget or relaxed template/premium default cannot
            waive it. Ranking uses full-fixture expected points: xG/xA ability, fixture-specific
            clean-sheet probability, DefCon and coarse captured-schedule FDR on the attacking term
            only, summed over the club's fixtures in that same window — a double gameweek adds one,
            a blank removes one. It does not use realised points, observed minutes, 60+ rates or
            expected minutes. A fixture with no published FDR is scored at a neutral 3 and labelled.
            Unknown projections have zero optimiser weight but are printed n/a/unscored, never as
            a zero forecast. NOT ranked on form. The reply attributes daily prices, the FPL player
            metric season, FPL schedule and Understat team xG/xGA with separate capture/sample dates.
            The metric season line covers FPL player metrics only. Relay a refusal as written.
            """)
    public String buildSquad(String mustInclude, String mustExclude, String clubQuota,
                             double budget, int nextN, String formation) {
        try {
            SquadConstraints constraints;
            try {
                constraints = ConstraintParser.parse(mustInclude, mustExclude, clubQuota,
                        budget, nextN, formation);
            } catch (ConstraintParser.MalformedConstraintException e) {
                return e.getMessage();
            }

            SeasonContext season = seasonResolver.resolve();
            // The capture's own season, not the resolver's: during the changeover the resolver
            // still falls back to last season (shared has not synced the new one), while the
            // daily capture already carries today's prices for it. Filtering on the resolver's
            // season here would match nothing at all — see AGENTS.md and CandidatePool.Capture.
            CandidatePool.Capture capture = pool.latestCapture().orElse(null);
            if (capture == null) {
                return "No player prices have been captured yet, so I cannot build a squad."
                        + " Tell the user the daily price capture has not run.";
            }
            MetricSeasons metric = seasonResolver.metricSeasons(season);
            CandidatePool.Pool loaded = pool.load(capture, metric);
            List<SquadCandidate> candidates = loaded.candidates();
            // One derivation for the whole call, shared by scoring, the XI gate and the reply —
            // the same call PickTools makes, so the two tools cannot disagree about which
            // fixtures a club plays. The capture's own season governs, not the resolver's. It is
            // derived here, before the first refusal, because every reply that names a source
            // also names the window that source produced.
            HorizonFixtures.Result horizon = HorizonFixtures.derive(loaded.snapshot().schedule(),
                    loaded.snapshot().asOf(), capture.season(), constraints.horizon());
            Set<Integer> playableClubs = playableClubs(horizon);
            LocalDate snapshotDay = LocalDate.ofInstant(
                    loaded.snapshot().asOf(), UkTime.zone());
            String priceProvenance = DailyPriceSource.provenance(
                    capture.season(), capture.date(), snapshotDay);
            String metricProvenance = season.provenance();
            String scheduleProvenance = scheduleProvenance(loaded.snapshot(), capture, season);
            String scheduleNote = scheduleEvidence(loaded.snapshot(), horizon);
            String evidenceHeader = priceProvenance + "\n" + metricProvenance
                    + "\n" + scheduleProvenance + "\n" + loaded.snapshot().metricEvidence()
                    + "\n" + scheduleNote;
            if (horizon.status() == HorizonFixtures.Status.KNOWN
                    && loaded.snapshot().comparisonCoverage().assessed()
                    && !loaded.snapshot().comparisonCoverage().complete()) {
                return evidenceHeader + "\n" + ComparisonCoverageFormatter.refusal(
                        loaded.snapshot().comparisonCoverage(), loaded.snapshot().asOf());
            }
            if (candidates.isEmpty()) {
                return evidenceHeader + "\nNo players are available to pick from today's capture.";
            }

            List<SquadConstraints.ClubQuota> resolvedQuotas = new ArrayList<>();
            for (SquadConstraints.ClubQuota quota : constraints.clubQuotas()) {
                ClubResolution resolved = resolveClub(quota, candidates);
                if (resolved.refusal() != null) {
                    return resolved.refusal();
                }
                resolvedQuotas.add(resolved.quota());
            }
            constraints = new SquadConstraints(constraints.mustInclude(), constraints.mustExclude(),
                    resolvedQuotas, constraints.budget(), constraints.horizon(),
                    constraints.formation());

            List<SquadCandidate> pinned = new ArrayList<>();
            for (String name : constraints.mustInclude()) {
                Resolution resolved = resolve(name, candidates);
                if (resolved.refusal() != null) {
                    return resolved.refusal();
                }
                pinned.add(resolved.player());
            }

            // Run through the same resolve() as mustInclude, for the same reason: an unmatched or
            // ambiguous exclusion refuses rather than silently becoming a no-op, and matching the
            // squad against a real element id (not a display string) is what lets the constraint
            // receipt tell the truth about whether the exclusion actually applied.
            List<SquadCandidate> excludedPlayers = new ArrayList<>();
            for (String name : constraints.mustExclude()) {
                Resolution resolved = resolve(name, candidates);
                if (resolved.refusal() != null) {
                    return resolved.refusal();
                }
                excludedPlayers.add(resolved.player());
            }

            Optional<String> refusal = Feasibility.check(candidates, constraints, pinned,
                    excludedPlayers, playableClubs, horizon);
            if (refusal.isPresent()) {
                return evidenceHeader + "\n" + refusal.get();
            }

            Map<Integer, Double> scores = new HashMap<>();
            Map<Integer, HorizonScorer.Projection> projections = new HashMap<>();
            // Projection and role evidence share one captured FPL schedule. An unavailable
            // projection weighs zero in the optimiser; the reply prints n/a, and a club that is
            // known to play nothing prints a real zero over the named range instead.
            boolean anyCandidateWasScoreable = false;
            for (SquadCandidate candidate : candidates) {
                HorizonScorer.Projection projection =
                        HorizonScorer.projection(candidate, horizon.club(candidate.teamId()),
                                loaded.snapshot().clubRates());
                projections.put(candidate.elementId(), projection);
                scores.put(candidate.elementId(), projection.optimizerPoints());
                if (projection.available()) {
                    anyCandidateWasScoreable = true;
                }
            }

            Optional<SolvedSquad> solved = SquadSolver.solveWithDefaults(
                    candidates, scores, constraints, pinned, excludedPlayers, playableClubs);
            if (solved.isEmpty()) {
                return evidenceHeader + "\nThose constraints cannot all be met while every starter"
                        + " plays for a club with at least one fixture in the horizon. Pins can"
                        + " occupy the bench but cannot waive that XI rule; review the formation,"
                        + " exclusions, club quotas and budget together.";
            }

            int excludedForNoRecord = (int) candidates.stream()
                    .filter(c -> c.tier() == SquadCandidate.Tier.BODY).count();

            // The metrics line names the season every candidate belongs to; what last season
            // contributes, and how much, is the sentence under it. A second "COMPLETED PREVIOUS"
            // banner would claim every number below it is last season's, and under blending none
            // purely is.
            String blendNote = metric.blending()
                    ? "Underlying ability blends " + metric.played().code() + " with "
                            + metric.previousCode() + " per player on the shared 1:10%,"
                            + " 2:30%, 3:60%, 4:80%, 5-7:90%, 8+:100% fixture-evidence ramp."
                    : "Underlying ability uses the current metric season only.";
            blendNote += " " + scheduleNote;
            blendNote += " " + ComparisonCoverageFormatter.success(
                    loaded.snapshot().comparisonCoverage());
            blendNote += " Clean sheets are projected per fixture from club expected-goal rates:"
                    + " the club's expected concession times the opponent's expected scoring"
                    + " rate over the league mean, adjusted for venue."
                    + " Club rates use the separately attributed Understat seasons, with the same"
                    + " ramp keyed on each club's own completed Understat fixtures."
                    + " FDR scales the attacking term only; a fixture with no club projection"
                    + " contributes no clean sheet and its row is partial.";

            return SquadFormatter.format(solved.get(), constraints,
                    priceProvenance, metricProvenance, scheduleProvenance,
                    loaded.snapshot().metricEvidence(), excludedForNoRecord, pinned, excludedPlayers, projections,
                    !anyCandidateWasScoreable, blendNote, horizon);
        } catch (DataAccessException e) {
            log.error("buildSquad failed", e);
            return "Squad data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    /**
     * The clubs a starter can be picked from: a known window holding at least one countable
     * fixture. Empty whenever the horizon itself is not known, which is why no entry path is
     * allowed to default it — "all clubs" would assert a schedule nobody could read.
     */
    private static Set<Integer> playableClubs(HorizonFixtures.Result horizon) {
        return horizon.clubs().values().stream()
                .filter(window -> window.known() && window.count() >= 1)
                .map(HorizonFixtures.ClubWindow::teamId)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The schedule capture's own {@code Data source:} line — the third source, on the capture's
     * own season rather than the resolved one, for the reason {@code ScheduleSource} documents.
     * During the changeover it is followed by the clarification the pick tools carry: without it
     * the metric line's "COMPLETED PREVIOUS season" reads as covering the price and the fixtures
     * printed under it. Before the first capture the line says so rather than being left out.
     */
    private static String scheduleProvenance(RankingMetrics.Snapshot snapshot,
                                             CandidatePool.Capture capture, SeasonContext season) {
        var captured = snapshot.schedule().snapshot();
        StringBuilder line = new StringBuilder(captured
                .map(selected -> ScheduleSource.provenance(selected.season(),
                        selected.capturedAt(), snapshot.asOf()))
                .orElse("Data source: no FPL gameweek and fixture capture exists, so this reply"
                        + " has no schedule to count fixtures in."));
        if (!capture.season().equals(season.code())) {
            line.append("\nThe season line above describes the metrics only: price, ownership and"
                    + " availability are today's, from the daily capture, and may be quoted as"
                    + " current.");
            if (captured.isPresent()) {
                line.append(" The schedule line, like the price line, describes the season being"
                        + " played: the fixtures every projection is summed over come from that"
                        + " capture, never from the metric season.");
            }
        }
        return line.toString();
    }

    /**
     * What the schedule actually supported, beside the source line that names it: the exact
     * capture instant, the instant it was judged at, and how many captured clubs the window
     * leaves startable. The {@code Horizon:} line below states the requested and actual span.
     */
    private static String scheduleEvidence(RankingMetrics.Snapshot snapshot,
                                           HorizonFixtures.Result horizon) {
        return captureEvidence(snapshot) + " " + clubEvidence(horizon);
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
            return "Schedule evidence: complete FPL capture at " + selected.capturedAt()
                    + ", judged as of " + snapshot.asOf() + ".";
        }
        return "Schedule evidence: FPL capture at " + selected.capturedAt() + ", judged as of "
                + snapshot.asOf() + "; structure " + snapshot.schedule().completeness().reason()
                + (fresh ? ", fresh for this snapshot." : ", not fresh for this snapshot.");
    }

    /** Clubs counted three ways, because a blank and an unreadable window are not the same. */
    private static String clubEvidence(HorizonFixtures.Result horizon) {
        if (horizon.status() != HorizonFixtures.Status.KNOWN) {
            return "No editable window ("
                    + SquadFormatter.horizonReason(horizon.reason())
                    + "), so no club has a countable fixture and none is a zero.";
        }
        long playable = horizon.clubs().values().stream()
                .filter(window -> window.known() && window.count() >= 1).count();
        long blank = horizon.clubs().values().stream()
                .filter(window -> window.known() && window.count() == 0).count();
        long unreadable = horizon.clubs().values().stream()
                .filter(window -> !window.known()).count();
        return playable + " of " + horizon.clubs().size() + " captured clubs play at least once in "
                + SquadFormatter.gameweekRange(horizon) + " (blank across it: " + blank
                + "; window unreadable: " + unreadable + ").";
    }

    private record Resolution(SquadCandidate player, String refusal) {
    }

    private record ClubResolution(SquadConstraints.ClubQuota quota, String refusal) {
    }

    /**
     * A club quota's name resolved to the pool's own canonical team id, matched against both
     * {@code team_name} and {@code team_short} — a manager writes "Manchester City" or
     * "Man City" interchangeably, and the daily capture's {@code club()} is FPL's own form
     * ("Man City", "Spurs", "Nott'm Forest"), not necessarily the one written. Every later
     * comparison against a candidate uses this id, never the display string.
     */
    private static ClubResolution resolveClub(SquadConstraints.ClubQuota quota,
                                              List<SquadCandidate> candidates) {
        String wanted = fold(quota.club());
        Optional<SquadCandidate> match = candidates.stream()
                .filter(c -> fold(c.club()).equals(wanted) || fold(c.clubShort()).equals(wanted))
                .findFirst();
        if (match.isEmpty()) {
            return new ClubResolution(null, "I could not find a club called \"" + quota.club()
                    + "\" among today's pool. Check the spelling.");
        }
        return new ClubResolution(quota.withTeamId(match.get().teamId()), null);
    }

    /**
     * A name to exactly one player, or a refusal.
     *
     * <p><b>Ambiguity is never guessed.</b> Putting the wrong player in a squad the user then
     * enters is worse than asking them which one they meant. Accents are stripped on both sides
     * because a model writes "Joao Pedro" for "João Pedro" — the original conversation failed on
     * exactly that.
     */
    private static Resolution resolve(String name, List<SquadCandidate> candidates) {
        String wanted = fold(name);
        List<SquadCandidate> exact = candidates.stream()
                .filter(c -> fold(c.webName()).equals(wanted))
                .toList();
        List<SquadCandidate> matches = exact.isEmpty()
                ? candidates.stream()
                        .filter(c -> fold(c.webName()).contains(wanted)
                                || fold(c.fullName()).contains(wanted))
                        .toList()
                : exact;

        if (matches.isEmpty()) {
            return new Resolution(null, ("I could not find a player called \"" + name
                    + "\" among those available today. Check the spelling, or they may be"
                    + " injured, suspended or not in the game."));
        }
        if (matches.size() > 1) {
            StringBuilder options = new StringBuilder();
            for (SquadCandidate match : matches.stream().limit(6).toList()) {
                options.append("\n  ").append(match.webName()).append(" (")
                        .append(match.club()).append(", £")
                        .append(Feasibility.money(match.nowCost())).append(')');
            }
            return new Resolution(null, "\"" + name + "\" matches more than one player, and I will"
                    + " not guess which you meant:" + options + "\nName one of them exactly.");
        }
        return new Resolution(matches.getFirst(), null);
    }

    /** Lowercased and stripped of diacritics, so "Joao" finds "João". */
    private static String fold(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase(Locale.ROOT);
    }
}
