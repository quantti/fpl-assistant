package dev.quantti.fplassistant.squad;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import dev.quantti.fplassistant.tools.DailyPriceSource;
import dev.quantti.fplassistant.tools.FplTool;
import dev.quantti.fplassistant.tools.SeasonContext;
import dev.quantti.fplassistant.tools.SeasonResolver;
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
 * <p><b>Provenance is two lines.</b> Prices, clubs, ownership and availability come from the
 * daily capture; metrics come from {@code player_fixture_stats}, which may be a season behind.
 * One banner cannot describe both, and a price attributed to the wrong season is the error the
 * provenance convention exists to prevent. This is a documented exception, like
 * {@code MyTeamTools}.
 */
@Component
public class SquadTools implements FplTool {

    private static final Logger log = LoggerFactory.getLogger(SquadTools.class);

    private final CandidatePool pool;
    private final HorizonScorer scorer;
    private final SeasonResolver seasonResolver;

    public SquadTools(CandidatePool pool, HorizonScorer scorer, SeasonResolver seasonResolver) {
        this.pool = pool;
        this.scorer = scorer;
        this.seasonResolver = seasonResolver;
    }

    @Tool(description = """
            Build a complete 15-player FPL squad under constraints: a budget, named players who
            must be in it, named players to avoid, and how many players to take from particular
            clubs. Returns fifteen real players with a computed total, a starting XI and
            formation, the bench in substitution order, and a captain and vice-captain — or a
            refusal naming the constraint that cannot be met. Use this for wildcard, free hit,
            pre-season and "build me a team" questions, and whenever the user states a squad
            constraint such as "three Arsenal players". Do NOT assemble a squad yourself: this
            tool enforces FPL's rules (15 players as 2 GK, 5 DEF, 5 MID, 3 FWD; at most 3 per
            club; the budget) which prose cannot.
            clubQuota is written "Arsenal:3" for exactly three, "Arsenal:>=2" for at least two,
            "Arsenal:<=2" for at most two. mustInclude and mustExclude are comma-separated player
            names. budget is in millions and defaults to 100.0 — for a wildcard, read the
            manager's squad value plus bank from getMyTeam and pass that instead. nextN is how
            many gameweeks ahead to score, default 5. formation forces one such as "3-4-3";
            leave it empty to let the solver choose the best legal shape.
            Players ranked on expected FPL points per fixture — expected goals and assists at
            their position's own values, the clean-sheet chance implied by expected goals
            conceded, scaled by the minutes they actually play — summed over the next N fixtures.
            NOT ranked on form: a points haul is a result, not a reason. The reply states which
            season the numbers come from and which day the prices were captured.
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
            // still falls back to last season (tapas has not synced the new one), while the
            // daily capture already carries today's prices for it. Filtering on the resolver's
            // season here would match nothing at all — see AGENTS.md and CandidatePool.Capture.
            CandidatePool.Capture capture = pool.latestCapture().orElse(null);
            if (capture == null) {
                return "No player prices have been captured yet, so I cannot build a squad."
                        + " Tell the user the daily price capture has not run.";
            }
            CandidatePool.MetricWindow window = pool.metricWindow(season);
            List<SquadCandidate> candidates = pool.load(capture, window);
            if (candidates.isEmpty()) {
                return "No players are available to pick from today's capture.";
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

            Optional<String> refusal =
                    Feasibility.check(candidates, constraints, pinned, excludedPlayers);
            if (refusal.isPresent()) {
                return refusal.get();
            }

            Map<Integer, List<Integer>> fixtures =
                    scorer.fixtureDifficulties(season.seasonId(), constraints.horizon());
            Map<Integer, Double> scores = new HashMap<>();
            Map<Integer, Double> minutes = new HashMap<>();
            for (SquadCandidate candidate : candidates) {
                scores.put(candidate.elementId(), HorizonScorer.score(candidate,
                        fixtures.getOrDefault(candidate.teamId(), List.of())));
                minutes.put(candidate.elementId(), HorizonScorer.expectedMinutes(candidate));
            }

            Optional<SolvedSquad> solved = SquadSolver.solveWithDefaults(
                    candidates, scores, constraints, pinned, excludedPlayers, minutes);
            if (solved.isEmpty()) {
                return "Those constraints cannot all be met at once, and I could not narrow it to"
                        + " a single one. Try loosening the club quotas or the budget.";
            }

            int excludedForNoRecord = (int) candidates.stream()
                    .filter(c -> c.tier() == SquadCandidate.Tier.BODY).count();

            // previousSeasonProvenance must be called on the previous season's own context, not
            // on the resolved one: when isResolvedSeason() is false, "season" is the current,
            // too-thin season, and naming it as the "COMPLETED PREVIOUS" one is exactly the
            // misattribution this provenance discipline exists to prevent.
            String metricProvenance = window.isResolvedSeason()
                    ? season.provenance()
                    : seasonResolver.previousSeason(season)
                            .map(prev -> prev.previousSeasonProvenance(false))
                            .orElseGet(season::provenance);

            return SquadFormatter.format(solved.get(), constraints,
                    DailyPriceSource.provenance(capture.season(), capture.date(), LocalDate.now()),
                    metricProvenance,
                    excludedForNoRecord, pinned, excludedPlayers);
        } catch (DataAccessException e) {
            log.error("buildSquad failed", e);
            return "Squad data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
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
