# KAR-30 — Per-fixture clean sheets from club xG/xGC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**PROGRESS:** Task 7/7 done.

Issue: [KAR-30](https://linear.app/quantti/issue/KAR-30). Branch: `kar-30-club-clean-sheet-projection`
(never `master`; push the branch and open a PR — see AGENTS.md).

**Goal:** Replace the season-average, opponent-blind clean-sheet term in every ranking (`exp(-xGC)`
where xGC is the player's own average over their window) with a per-fixture projection built from
both sides of each upcoming fixture — the defending club's expected concession rate, the opponent's
expected scoring rate, and the venue — so that a run of fixtures yields an expected clean-sheet
count rather than one probability plus a separate difficulty number.

**Architecture:** Club-level attack/defence rates are one more read inside the existing coherent
`RankingMetrics` snapshot (same `REPEATABLE_READ` transaction, same season sources, same
cross-season ramp via `SeasonBlend.weight`), held in a new pure class `ClubRates`. `ExpectedPoints`
splits the clean-sheet term out of the fixture-independent components and takes a projected goals-
against figure per fixture. `PickTools` and `HorizonScorer` already iterate the club's fixtures in
the shared `HorizonFixtures` window; they now compute the clean-sheet term per fixture there. FDR
stays a tie-break in the pick lists; in the squad objective its multiplier narrows to the attacking
term, because the opponent is now modelled inside the clean-sheet term and would otherwise be
counted twice.

**Tech Stack:** Java 25, Spring Boot 4.1, Spring AI `@Tool`, `JdbcTemplate` against the local
PostgreSQL test container, JUnit 5 + AssertJ. Python 3 standard library for the offline
measurement (Task 0), on public vaastav CSVs only.

**Spec:** there is no separate design document. The specification is the Linear issue text plus
the **Final decisions** section below, which resolves the three questions the issue leaves open
(recency weighting, window length, FDR beside or inside). Background the executor should read
first: `docs/STATUS.md` sections "Pick tools rank on expected points, not on points scored
(2026-08-26)", "Cross-season blending: one ramp replaces the GW5 switch (2026-08-28)" and
"Gameweek-horizon ranking implementation outcome (KAR-35, 2026-09-09)", and the AGENTS.md
conventions on `RankingMetrics`, `HorizonFixtures`, `SeasonBlend` and `ExpectedPoints`.

## Global Constraints

- Tests run against the disposable container only: `cd backend && set -a; source .env.test; set +a`
  before any `./gradlew` command. Never against Supabase. Seed everything a test asserts on.
- All ranking reads stay inside the one `RankingMetrics.load` snapshot. No per-player SQL, no second
  schedule, no new network call. The current-season club read uses the schedule capture's fixture
  table (`schedules.fixtureTable()`, season column `season`) exactly as `loadHistory` does; the
  previous-season and completed-season reads use tapas `fixture` (`season_id`).
- The cross-season ramp is written once: club rates use `SeasonBlend.weight(n)` with `n` = the
  club's completed current-season fixtures. Do not write a ramp in SQL, and do not add a table.
- Team expected goals per fixture-side are `SUM(expected_goals)` over the club's players; team
  expected goals conceded are `MAX(expected_goals_conceded)` — FPL stamps the team figure on every
  player, so a sum multiplies it by squad size (see `ExpectedStatsTools` class javadoc). A side
  with `MAX(expected_goals_conceded) = 0` is unpopulated, not a perfect defence, and is excluded —
  the same rule `ExpectedPoints.hasCleanSheetSignal` already applies.
- Club identity follows the player convention: the capture's `team_id` directly when the capture
  and the metric season are the same season; otherwise a **unique, case-insensitive `short_name`
  bridge** between the capture's `team_short` and that season's `team.short_name`. A club that
  bridges nowhere has no rate. No `team.code` path, no name heuristics.
- Unknown is never zero: a fixture whose projection cannot be made prints `CS n/a` for that fixture
  and marks the row partial; it never falls back to the player's season-average xGC. A known
  window with no fixtures stays a real zero.
- Provenance does not change shape. Club rates come from `player_fixture_stats` in the metric
  season(s) and are covered by the existing metric-season line and blend sentence; add no fourth
  `Data source:` line.
- `PickTools.rank`, `score`, `horizonScore` and `realisedWeight` keep their signatures and their
  arithmetic; `expected_per_fixture` becomes the horizon *average* of the per-fixture expectation,
  which leaves `horizonScore = score × fixtureCount` exactly equal to the summed per-fixture blend.
- Minimal output rules: locale-pinned numbers via the existing `fixed()`, no new provenance
  wording outside the strings this plan names.

## Final decisions

1. **Model.** For a fixture where club D defends against opponent O:

   ```text
   lambda = defence(D) × attack(O) / meanXg × venue(O)
   P(clean sheet | 60+) = exp(-lambda)
   clean-sheet points for the fixture = CLEAN_SHEET_POINTS[position] × P(60+) × exp(-lambda)
   ```

   where `attack(C)` is club C's mean team xG per fixture, `defence(C)` its mean team xGC per
   fixture, `meanXg` the league mean team xG per fixture-side, and `venue(O)` the league home
   factor when O is at home (D away) or the league away factor when O is away (D at home). With
   both clubs average this reduces to the league's average home or away concession, which is the
   sanity check Task 1 pins.
2. **Window and recency.** Club rates use the club's *whole* completed current season, blended
   with the club's whole previous season on the shared `SeasonBlend` ramp keyed on the club's own
   completed current-season fixtures. No last-10 club window: at five home matches a per-venue
   club rate is noise, so venue is one league-wide factor pair, not a per-club split. Task 0
   measures whole-season versus last-10 and reports; it does not tune.
3. **Floors.** A club rate needs at least `MIN_CLUB_FIXTURES = 2` fixture-sides across both seasons
   (mirrors the player's 180-minute floor at two matches). League constants need at least
   `MIN_LEAGUE_SIDES = 40` fixture-sides pooled over both seasons; pooling is deliberate — the
   league mean is not a club's property and is not ramped. Below either floor the projection is
   unavailable, never zero.
4. **FDR.** Pick lists: unchanged, FDR is a tie-break. Squad objective: `fdrAdjust` multiplies the
   attacking term only; appearance, DefCon and the opponent-modelled clean sheet are not
   FDR-scaled. Neutral-3 labelling stays, reworded "on the attacking term".
5. **Printing.** Each pick's `CS` component becomes the horizon total with the per-fixture list:
   `CS 1.93 over 2 fixtures (vs K07 h 61% at xGA 0.49; vs K12 a 38% at xGA 0.97)`; a fixture with
   no projection prints `n/a` in that list and the row carries the existing partial label. The
   footer names the model. `expected/fixture` is the horizon average.
6. **Measurement gate (Task 0).** If the opponent model is worse than the club season-average
   baseline on Brier score over the whole held-out season, stop after Task 0 and report to the
   owner: the issue's premise would be false and the rest of the plan is theirs to confirm.

## File structure

| File | Responsibility |
|---|---|
| Create `backend/src/main/java/fi/karivanttinen/fplassistant/tools/ClubRates.java` | Pure: per-club season sides → blended attack/defence rates, league constants, `projectedGoalsAgainst`. |
| Modify `tools/RankingMetrics.java` | Load club sides for the current/completed and previous seasons inside the snapshot; bridge by short name; `Snapshot` gains `clubRates`. |
| Modify `tools/ExpectedPoints.java` | `perFixture(row, exposure, OptionalDouble projectedGoalsAgainst)`; clean sheet from the projection, `null` when unavailable. |
| Modify `tools/PickTools.java` | Per-fixture clean sheet inside the horizon total; `CS` printing; footer and both tool descriptions. |
| Modify `squad/HorizonScorer.java`, `squad/SquadTools.java`, `squad/SquadFormatter.java` | Rates into the squad projection; FDR on attack only; blend note and labels. |
| Modify `backend/src/main/resources/prompts/system-prompt.st`, `AGENTS.md`, `docs/STATUS.md` | Rules and record. |
| Tests | `ClubRatesTest` (new), `ExpectedPointsTest`, `RankingMetricsIntegrationTest`, `PickToolsRankingTest`, `PickToolsIntegrationTest`, `HorizonScorerTest`, `SquadFormatterTest`, `SquadToolsIntegrationTest`. |

---

### Task 0: Measure the opponent model against the season-average baseline

Read-only. Produces a table for this file and `docs/STATUS.md`; changes no code. Public vaastav
CSVs only, in the session scratchpad; nothing from them is written to the database or the repo.

**Which seasons.** Today is 2026-09-13; 2026-27 has four completed gameweeks, so it cannot be a
held-out season. Use 2024-25 as "previous" and 2025-26 as "this season", the same stand-in pair
the blending plan used.

**Files:**
- Create (scratchpad): `$SP/cs_measure.py`
- Modify: this plan (the `## Outcome — Task 0` section), `**PROGRESS:**` line

- [ ] **Step 1: Fetch the per-gameweek rows and the team id maps for both seasons**

```bash
SP=/tmp/claude-1000/-home-vanka-dev-fpl-assistant/b3ca9de6-ea58-45d3-b2bd-7392af483fda/scratchpad
for s in 2024-25 2025-26; do
  curl -sL --max-time 90 -o "$SP/gw$s.csv" \
    "https://raw.githubusercontent.com/vaastav/Fantasy-Premier-League/master/data/$s/gws/merged_gw.csv"
  curl -sL --max-time 60 -o "$SP/teams$s.csv" \
    "https://raw.githubusercontent.com/vaastav/Fantasy-Premier-League/master/data/$s/teams.csv"
  wc -l "$SP/gw$s.csv" "$SP/teams$s.csv"
done
```

Expected: two `gw*.csv` files of tens of thousands of lines with columns including `team`,
`opponent_team`, `was_home`, `fixture`, `minutes`, `expected_goals`, `expected_goals_conceded`,
`team_h_score`, `team_a_score`, `GW`; two `teams*.csv` with `id`, `name`.

- [ ] **Step 2: Write and run the measurement**

```python
# $SP/cs_measure.py — Brier and log-loss of P(clean sheet) per fixture-side, 2025-26 GW3-38,
# predicted only from rows dated before that gameweek. Standard library only.
import csv, math, sys
from collections import defaultdict

SP = sys.argv[1]
RAMP = [0, 0.10, 0.30, 0.60, 0.80, 0.90, 0.90, 0.90, 1.0]
MIN_CLUB, MIN_LEAGUE = 2, 40

def w(n): return RAMP[min(n, 8)]

def teams(season):
    with open(f"{SP}/teams{season}.csv", encoding="utf-8") as f:
        return {int(r["id"]): r["name"] for r in csv.DictReader(f)}

def sides(season):
    """(gw, fixture, club) -> dict(xg, xgc, home, opp, cs)."""
    names = teams(season)
    out = {}
    with open(f"{SP}/gw{season}.csv", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            if not r["team_h_score"] or not r["team_a_score"]:
                continue                                   # a row for a match not yet played
            key = (int(r["GW"]), int(r["fixture"]), r["team"])
            s = out.setdefault(key, {"xg": 0.0, "xgc": 0.0, "home": r["was_home"] == "True",
                                     "opp": names[int(r["opponent_team"])], "cs": None})
            s["xg"] += float(r["expected_goals"])
            s["xgc"] = max(s["xgc"], float(r["expected_goals_conceded"]))
            conceded = int(r["team_a_score"]) if s["home"] else int(r["team_h_score"])
            s["cs"] = 1 if conceded == 0 else 0
    return {k: v for k, v in out.items() if v["xgc"] > 0}

prev, cur = sides("2024-25"), sides("2025-26")

def club_totals(rows):
    t = defaultdict(lambda: {"n": 0, "xg": 0.0, "xgc": 0.0, "hn": 0, "hxg": 0.0})
    for (gw, fx, club), s in rows.items():
        c = t[club]; c["n"] += 1; c["xg"] += s["xg"]; c["xgc"] += s["xgc"]
        if s["home"]: c["hn"] += 1; c["hxg"] += s["xg"]
    return t

def rates(cur_rows, prev_rows, last=None):
    """Blended per-club attack/defence and pooled league constants, or None below the floors."""
    if last is not None:                      # last-N club window on the current season only
        by_club = defaultdict(list)
        for (gw, fx, club), s in sorted(cur_rows.items()):
            by_club[club].append(((gw, fx, club), s))
        cur_rows = {k: s for lst in by_club.values() for k, s in lst[-last:]}
    ct, pt = club_totals(cur_rows), club_totals(prev_rows)
    league = {"n": 0, "xg": 0.0, "hn": 0, "hxg": 0.0}
    for t in (ct, pt):
        for c in t.values():
            league["n"] += c["n"]; league["xg"] += c["xg"]
            league["hn"] += c["hn"]; league["hxg"] += c["hxg"]
    if league["n"] < MIN_LEAGUE: return None
    mu = league["xg"] / league["n"]
    home = (league["hxg"] / league["hn"]) / mu
    away = ((league["xg"] - league["hxg"]) / (league["n"] - league["hn"])) / mu
    clubs = {}
    for club in set(ct) | set(pt):
        c, p = ct.get(club), pt.get(club)
        n_cur = c["n"] if c else 0
        if n_cur + (p["n"] if p else 0) < MIN_CLUB: continue
        wt = 1.0 if p is None else (0.0 if c is None else w(n_cur))
        att = (wt * c["xg"] / c["n"] if c else 0) + ((1 - wt) * p["xg"] / p["n"] if p else 0)
        dfc = (wt * c["xgc"] / c["n"] if c else 0) + ((1 - wt) * p["xgc"] / p["n"] if p else 0)
        clubs[club] = (att, dfc)
    return clubs, mu, home, away

def opponent_model(r, club, opp, at_home):
    if r is None or club not in r[0] or opp not in r[0]: return None
    clubs, mu, home, away = r
    lam = clubs[club][1] * clubs[opp][0] / mu * (away if at_home else home)
    return math.exp(-lam)

def baseline(cur_rows, prev_rows, club):
    """The shipped model at club grain: exp(-mean xGC), ramped like the code ramps a player."""
    ct, pt = club_totals(cur_rows), club_totals(prev_rows)
    c, p = ct.get(club), pt.get(club)
    if (c["n"] if c else 0) + (p["n"] if p else 0) < MIN_CLUB: return None
    wt = 1.0 if p is None else (0.0 if c is None else w(c["n"]))
    xgc = (wt * c["xgc"] / c["n"] if c else 0) + ((1 - wt) * p["xgc"] / p["n"] if p else 0)
    return math.exp(-xgc)

variants = {"baseline": None, "opponent": None, "opponent-no-prev": None, "opponent-last10": None}
score = {v: {"brier": 0.0, "ll": 0.0, "n": 0} for v in variants}
bands = {(3, 10): {v: [0.0, 0] for v in variants}, (11, 20): {v: [0.0, 0] for v in variants},
         (21, 38): {v: [0.0, 0] for v in variants}}
for gw in range(3, 39):
    seen = {k: s for k, s in cur.items() if k[0] < gw}
    r_full = rates(seen, prev); r_noprev = rates(seen, {}); r_last = rates(seen, prev, last=10)
    for (g, fx, club), s in cur.items():
        if g != gw: continue
        preds = {"baseline": baseline(seen, prev, club),
                 "opponent": opponent_model(r_full, club, s["opp"], s["home"]),
                 "opponent-no-prev": opponent_model(r_noprev, club, s["opp"], s["home"]),
                 "opponent-last10": opponent_model(r_last, club, s["opp"], s["home"])}
        if any(p is None for p in preds.values()): continue   # score every variant on one population
        for v, p in preds.items():
            p = min(max(p, 1e-6), 1 - 1e-6)
            score[v]["brier"] += (p - s["cs"]) ** 2
            score[v]["ll"] += -(s["cs"] * math.log(p) + (1 - s["cs"]) * math.log(1 - p))
            score[v]["n"] += 1
            for (lo, hi), b in bands.items():
                if lo <= gw <= hi: b[v][0] += (p - s["cs"]) ** 2; b[v][1] += 1

print("variant            Brier    log-loss   n")
for v, t in score.items():
    print(f"{v:18} {t['brier']/t['n']:.4f}   {t['ll']/t['n']:.4f}   {t['n']}")
print("\nBrier by gameweek band")
for (lo, hi), b in bands.items():
    print(f"GW{lo}-{hi}: " + ", ".join(f"{v} {s[0]/s[1]:.4f}" for v, s in b.items() if s[1]))
```

Run: `python3 "$SP/cs_measure.py" "$SP"`

Expected shape: `opponent` at or below `baseline` on both Brier and log-loss overall, with the
largest gain in GW3-10 where the baseline's own-season sample is thinnest; `opponent-no-prev`
worse than `opponent` early and converging by GW21-38; `opponent-last10` not clearly better than
`opponent`. The finding to report is any band where `opponent` is worse than `baseline`.

- [ ] **Step 3: Record the table under `## Outcome — Task 0` at the end of this file** — the
four-variant table, the band table, the population size, and one sentence on each of the two
questions decided above (whole-season vs last-10; previous season's contribution). Update
`**PROGRESS:**` to `Task 1/7 done`.

- [ ] **Step 4: Apply the gate.** If `opponent` has a higher Brier than `baseline` over the whole
population, stop here, write the finding in `docs/STATUS.md` under a new heading
`## Club clean-sheet projection: measurement (KAR-30, <date>)`, commit, and hand the decision to
the owner. Otherwise commit and continue.

```bash
git checkout -b kar-30-club-clean-sheet-projection
git add docs/superpowers/plans/2026-09-13-kar-30-club-clean-sheet-projection.md
git commit -m "docs: measure the club clean-sheet projection against the season average"
```

---

### Task 1: `ClubRates` — blended club rates and the per-fixture projection

**Files:**
- Create: `backend/src/main/java/fi/karivanttinen/fplassistant/tools/ClubRates.java`
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/tools/ClubRatesTest.java`

**Interfaces:**
- Consumes: `SeasonBlend.weight(int)`.
- Produces (later tasks rely on these exact names):
  - `ClubRates.SeasonSide(int fixtures, double xgFor, double xgAgainst, int homeFixtures, double homeXgFor)`
  - `ClubRates.ClubRate(double attack, double defence, int fixtures, double weight, int fixturesThisSeason, boolean hasPrevious)`
  - `ClubRates.League(double meanXg, double homeFactor, double awayFactor, int sides)`
  - `ClubRates.Rates(Map<Integer, ClubRate> clubs, Optional<League> league)` and `ClubRates.NONE`
  - `static Rates blend(Map<Integer, SeasonSide> current, Map<Integer, SeasonSide> previous)` — both keyed by the **capture's** team id
  - `static OptionalDouble projectedGoalsAgainst(Rates rates, int defendingClub, int opponent, boolean defendingAtHome)`
  - constants `MIN_CLUB_FIXTURES = 2`, `MIN_LEAGUE_SIDES = 40`

- [ ] **Step 1: Write the failing tests**

```java
package fi.karivanttinen.fplassistant.tools;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The club-level half of the clean-sheet term: what a defence is expected to concede against a
 * named opponent at a named venue, from both clubs' expected-goal rates and the league's venue
 * effect. Pure arithmetic; the reads that feed it live in {@code RankingMetrics}.
 */
class ClubRatesTest {

    private static final int HOME_CLUB = 11;
    private static final int AWAY_CLUB = 12;

    /** A club that has played {@code fixtures} sides at the given per-fixture rates. */
    private static ClubRates.SeasonSide side(int fixtures, double xgPerFixture,
                                             double xgcPerFixture, int homeFixtures,
                                             double homeXgPerFixture) {
        return new ClubRates.SeasonSide(fixtures, xgPerFixture * fixtures,
                xgcPerFixture * fixtures, homeFixtures, homeXgPerFixture * homeFixtures);
    }

    /**
     * Twenty average clubs, 20 sides each, home sides scoring 1.6 and away sides 1.2: league
     * mean 1.4, home factor 1.6/1.4, away factor 1.2/1.4.
     */
    private static Map<Integer, ClubRates.SeasonSide> averageLeague() {
        Map<Integer, ClubRates.SeasonSide> clubs = new HashMap<>();
        for (int club = 1; club <= 20; club++) {
            clubs.put(club, side(20, 1.4, 1.4, 10, 1.6));
        }
        return clubs;
    }

    @Test
    void anAverageDefenceAgainstAnAverageAttackProjectsTheLeagueVenueMean() {
        ClubRates.Rates rates = ClubRates.blend(averageLeague(), Map.of());

        OptionalDouble atHome = ClubRates.projectedGoalsAgainst(rates, HOME_CLUB, AWAY_CLUB, true);
        OptionalDouble away = ClubRates.projectedGoalsAgainst(rates, AWAY_CLUB, HOME_CLUB, false);

        assertThat(atHome).hasValueCloseTo(1.2, within(1e-9));   // the away side's mean
        assertThat(away).hasValueCloseTo(1.6, within(1e-9));     // the home side's mean
        assertThat(rates.league().orElseThrow().homeFactor()
                + rates.league().orElseThrow().awayFactor()).isCloseTo(2.0, within(1e-9));
    }

    @Test
    void aStrongerAttackRaisesTheProjectionAndAMeanerDefenceLowersIt() {
        Map<Integer, ClubRates.SeasonSide> clubs = averageLeague();
        clubs.put(AWAY_CLUB, side(20, 2.1, 1.4, 10, 2.4));          // attack 1.5× the mean
        clubs.put(HOME_CLUB, side(20, 1.4, 0.7, 10, 1.6));          // defence half the mean
        ClubRates.Rates rates = ClubRates.blend(clubs, Map.of());

        assertThat(ClubRates.projectedGoalsAgainst(rates, HOME_CLUB, AWAY_CLUB, true))
                .hasValueCloseTo(0.7 * 2.1 / rates.league().orElseThrow().meanXg()
                        * rates.league().orElseThrow().awayFactor(), within(1e-9));
        assertThat(ClubRates.projectedGoalsAgainst(rates, HOME_CLUB, AWAY_CLUB, true).getAsDouble())
                .isLessThan(ClubRates.projectedGoalsAgainst(rates, 3, AWAY_CLUB, true).getAsDouble());
    }

    @Test
    void aClubBelowTwoSidesAcrossBothSeasonsHasNoRate() {
        Map<Integer, ClubRates.SeasonSide> clubs = averageLeague();
        clubs.put(HOME_CLUB, side(1, 1.4, 1.4, 1, 1.4));
        ClubRates.Rates rates = ClubRates.blend(clubs, Map.of());

        assertThat(rates.clubs()).doesNotContainKey(HOME_CLUB);
        assertThat(ClubRates.projectedGoalsAgainst(rates, HOME_CLUB, AWAY_CLUB, true)).isEmpty();
        assertThat(ClubRates.projectedGoalsAgainst(rates, AWAY_CLUB, HOME_CLUB, false))
                .as("an unrated opponent is as blocking as an unrated defence").isEmpty();
    }

    @Test
    void thePreviousSeasonCarriesAClubWithNoCurrentSidesAndTheSharedRampTakesOver() {
        Map<Integer, ClubRates.SeasonSide> previous = averageLeague();
        previous.put(HOME_CLUB, side(38, 1.0, 2.0, 19, 1.1));
        Map<Integer, ClubRates.SeasonSide> current = new HashMap<>();
        current.put(HOME_CLUB, side(2, 2.0, 1.0, 1, 2.0));

        ClubRates.ClubRate zeroCurrent = ClubRates.blend(Map.of(), previous).clubs().get(HOME_CLUB);
        ClubRates.ClubRate twoCurrent = ClubRates.blend(current, previous).clubs().get(HOME_CLUB);

        assertThat(zeroCurrent.weight()).isZero();
        assertThat(zeroCurrent.attack()).isCloseTo(1.0, within(1e-9));
        assertThat(zeroCurrent.hasPrevious()).isTrue();
        assertThat(twoCurrent.weight()).isEqualTo(SeasonBlend.weight(2));
        assertThat(twoCurrent.attack()).isCloseTo(0.30 * 2.0 + 0.70 * 1.0, within(1e-9));
        assertThat(twoCurrent.defence()).isCloseTo(0.30 * 1.0 + 0.70 * 2.0, within(1e-9));
        assertThat(twoCurrent.fixturesThisSeason()).isEqualTo(2);
    }

    @Test
    void aClubWithNoPreviousRecordIsRatedOnThisSeasonAloneOnceItHasTwoSides() {
        Map<Integer, ClubRates.SeasonSide> previous = averageLeague();
        previous.remove(HOME_CLUB);
        Map<Integer, ClubRates.SeasonSide> current = new HashMap<>();
        current.put(HOME_CLUB, side(2, 0.9, 1.9, 1, 0.9));

        ClubRates.ClubRate promoted = ClubRates.blend(current, previous).clubs().get(HOME_CLUB);

        assertThat(promoted.hasPrevious()).isFalse();
        assertThat(promoted.weight()).as("the table's value, reported not silently 100%")
                .isEqualTo(SeasonBlend.weight(2));
        assertThat(promoted.attack()).isCloseTo(0.9, within(1e-9));
        assertThat(promoted.defence()).isCloseTo(1.9, within(1e-9));
    }

    @Test
    void fewerThanFortySidesLeaveNoLeagueConstantsAndSoNoProjection() {
        Map<Integer, ClubRates.SeasonSide> clubs = new HashMap<>();
        clubs.put(HOME_CLUB, side(10, 1.4, 1.4, 5, 1.6));
        clubs.put(AWAY_CLUB, side(10, 1.4, 1.4, 5, 1.6));
        ClubRates.Rates rates = ClubRates.blend(clubs, Map.of());

        assertThat(rates.league()).isEmpty();
        assertThat(rates.clubs()).containsKeys(HOME_CLUB, AWAY_CLUB);
        assertThat(ClubRates.projectedGoalsAgainst(rates, HOME_CLUB, AWAY_CLUB, true)).isEmpty();
    }

    @Test
    void leagueConstantsPoolBothSeasonsWithoutARamp() {
        Map<Integer, ClubRates.SeasonSide> current = new HashMap<>();
        current.put(HOME_CLUB, side(2, 3.0, 1.0, 2, 3.0));
        ClubRates.Rates rates = ClubRates.blend(current, averageLeague());

        // 400 sides at 1.4 plus 2 sides at 3.0.
        assertThat(rates.league().orElseThrow().sides()).isEqualTo(402);
        assertThat(rates.league().orElseThrow().meanXg())
                .isCloseTo((400 * 1.4 + 2 * 3.0) / 402, within(1e-9));
    }

    @Test
    void noneProjectsNothing() {
        assertThat(ClubRates.NONE.clubs()).isEmpty();
        assertThat(ClubRates.projectedGoalsAgainst(ClubRates.NONE, 1, 2, true)).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && set -a; source .env.test; set +a; ./gradlew test --tests '*ClubRatesTest'`
Expected: compilation failure, `ClubRates` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package fi.karivanttinen.fplassistant.tools;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Club-level expected-goal rates and the per-fixture concession they project.
 *
 * <p>The clean-sheet term used to be {@code exp(-xGC)} on the <em>player's</em> average expected
 * concession over his window — backward-looking and opponent-blind, with fixture difficulty
 * applied beside it as an average FDR. This class supplies the other half of the fixture: the
 * defending club's concession rate, the opponent's scoring rate and the venue, combined the way a
 * Poisson goals model does, so a defender's clean-sheet chance differs against a side scoring
 * 2.2 a game and one scoring 0.9 (KAR-30).
 *
 * <p>Rates are per club and per fixture-side. A side's expected goals are the sum over its
 * players; its expected goals conceded are the team figure FPL stamps on every player, read once
 * with {@code MAX} — see {@code ExpectedStatsTools}. The current and previous seasons blend on the
 * one shared ramp, {@link SeasonBlend#weight}, keyed on the club's own completed fixtures this
 * season. League constants are pooled over both seasons and not ramped: the league mean and the
 * home/away effect are not a club's property.
 *
 * <p>Nothing here is a calibration claim. Below the floors the projection is absent rather than
 * estimated, and callers print n/a and label the row partial.
 */
public final class ClubRates {

    /** Fixture-sides a club must have, across both seasons, before it carries a rate. */
    public static final int MIN_CLUB_FIXTURES = 2;

    /** Fixture-sides the league must have, pooled over both seasons, before venue and mean exist. */
    public static final int MIN_LEAGUE_SIDES = 40;

    /** One club's completed sides in one season, as summed by the read. */
    public record SeasonSide(int fixtures, double xgFor, double xgAgainst,
                             int homeFixtures, double homeXgFor) {
        public SeasonSide {
            if (fixtures < 0 || homeFixtures < 0 || homeFixtures > fixtures) {
                throw new IllegalArgumentException("invalid club season side");
            }
        }
    }

    /**
     * @param attack mean team xG per fixture-side, blended
     * @param defence mean team xGC per fixture-side, blended
     * @param fixtures sides across both seasons, the evidence count
     * @param weight the ramp's value for this club, reported even when it could not apply
     * @param fixturesThisSeason completed current-season sides, the ramp's input
     * @param hasPrevious whether a previous-season record blended in
     */
    public record ClubRate(double attack, double defence, int fixtures, double weight,
                           int fixturesThisSeason, boolean hasPrevious) {
    }

    /** League mean team xG per side and the venue factors around it; home + away = 2. */
    public record League(double meanXg, double homeFactor, double awayFactor, int sides) {
    }

    /** Keyed by the capture's team id; a club absent here has no usable rate. */
    public record Rates(Map<Integer, ClubRate> clubs, Optional<League> league) {
        public Rates {
            clubs = Map.copyOf(clubs);
            league = java.util.Objects.requireNonNull(league);
        }
    }

    public static final Rates NONE = new Rates(Map.of(), Optional.empty());

    private ClubRates() {
    }

    /**
     * @param current this season's sides per capture team id, empty when none were read
     * @param previous last season's sides per capture team id, empty when not blending
     */
    public static Rates blend(Map<Integer, SeasonSide> current, Map<Integer, SeasonSide> previous) {
        Map<Integer, ClubRate> clubs = new HashMap<>();
        Set<Integer> ids = new HashSet<>(current.keySet());
        ids.addAll(previous.keySet());
        for (int club : ids) {
            SeasonSide cur = current.get(club);
            SeasonSide prev = previous.get(club);
            int curFixtures = cur == null ? 0 : cur.fixtures();
            int prevFixtures = prev == null ? 0 : prev.fixtures();
            if (curFixtures + prevFixtures < MIN_CLUB_FIXTURES) {
                continue;
            }
            double tableWeight = SeasonBlend.weight(curFixtures);
            double w = prev == null || prevFixtures == 0 ? 1.0
                    : cur == null || curFixtures == 0 ? 0.0 : tableWeight;
            double attack = mix(rate(cur, true), rate(prev, true), w);
            double defence = mix(rate(cur, false), rate(prev, false), w);
            clubs.put(club, new ClubRate(attack, defence, curFixtures + prevFixtures,
                    tableWeight, curFixtures, prev != null && prevFixtures > 0));
        }
        return new Rates(clubs, league(current, previous));
    }

    /**
     * The chance-of-conceding input for one fixture: what {@code defendingClub} is expected to
     * let in against {@code opponent}, at home or away. Empty when either club or the league
     * has no rate; never zero.
     */
    public static OptionalDouble projectedGoalsAgainst(Rates rates, int defendingClub,
                                                       int opponent, boolean defendingAtHome) {
        if (rates.league().isEmpty()) {
            return OptionalDouble.empty();
        }
        ClubRate defence = rates.clubs().get(defendingClub);
        ClubRate attack = rates.clubs().get(opponent);
        League league = rates.league().orElseThrow();
        if (defence == null || attack == null || league.meanXg() <= 0) {
            return OptionalDouble.empty();
        }
        double venue = defendingAtHome ? league.awayFactor() : league.homeFactor();
        return OptionalDouble.of(defence.defence() * attack.attack() / league.meanXg() * venue);
    }

    private static Optional<League> league(Map<Integer, SeasonSide> current,
                                           Map<Integer, SeasonSide> previous) {
        int sides = 0;
        int homeSides = 0;
        double xg = 0;
        double homeXg = 0;
        for (Map<Integer, SeasonSide> season : java.util.List.of(current, previous)) {
            for (SeasonSide side : season.values()) {
                sides += side.fixtures();
                homeSides += side.homeFixtures();
                xg += side.xgFor();
                homeXg += side.homeXgFor();
            }
        }
        int awaySides = sides - homeSides;
        if (sides < MIN_LEAGUE_SIDES || homeSides == 0 || awaySides == 0 || xg <= 0) {
            return Optional.empty();
        }
        double meanXg = xg / sides;
        return Optional.of(new League(meanXg, (homeXg / homeSides) / meanXg,
                ((xg - homeXg) / awaySides) / meanXg, sides));
    }

    private static double rate(SeasonSide side, boolean attack) {
        if (side == null || side.fixtures() == 0) {
            return 0;
        }
        return (attack ? side.xgFor() : side.xgAgainst()) / side.fixtures();
    }

    private static double mix(double current, double previous, double weight) {
        return weight * current + (1 - weight) * previous;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests '*ClubRatesTest'`
Expected: 8 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/tools/ClubRates.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/tools/ClubRatesTest.java
git commit -m "Add club attack/defence rates and per-fixture concession projection"
```

---

### Task 2: Load club sides inside the `RankingMetrics` snapshot

**Files:**
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/tools/RankingMetrics.java` (the
  `Snapshot` record, `loadInside`, plus new private methods `loadClubSides`, `bridgeClubs`)
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/tools/RankingMetricsIntegrationTest.java`
- Modify (compile only): every `new RankingMetrics.Snapshot(` call site — `grep -rn` found one in
  `PickToolsCrossSeasonTest.java:113`; append `ClubRates.NONE` there.

**Interfaces:**
- Consumes: `ClubRates.SeasonSide`, `ClubRates.blend`, `ClubRates.NONE` from Task 1.
- Produces: `RankingMetrics.Snapshot(Instant asOf, DailyPriceSource.Capture capture, MetricSeasons metricSeasons, ScheduleSource.View schedule, List<Candidate> candidates, ClubRates.Rates clubRates)` — `clubRates` never null.

- [ ] **Step 1: Write the failing integration tests**

Add to `RankingMetricsIntegrationTest`. The class already has `seedDaily`, `finishFixtures`,
`fixtureIdsFor`, `finishFixture`, `seedPfs`, `load`, `loadWithPrevious`, `candidate` and the
`FixedClock`; the new helpers below go beside them.

```java
    /** Ghost element ids for one row per club side; never captured, so never candidates. */
    private static final int GHOST_BASE = 9_900_000;

    /**
     * Finishes {@code gameweeks} rounds of the seeded schedule and gives every club one row per
     * side at the same rates, so the league floor is met and every club is average.
     */
    private void seedAverageLeague(int gameweeks, double xg, double xgc) {
        for (ScheduleSource.FixtureRow row : RankingSeed.fixtures(CAPTURED_AT, FIRST_DEADLINE)) {
            if (row.gameweek() == null || row.gameweek() > gameweeks) {
                continue;
            }
            Instant kickoff = Instant.parse("2026-08-01T12:00:00Z").plus(row.gameweek(), ChronoUnit.DAYS);
            finishFixture(row.id(), kickoff);
            seedSide(row.id(), row.homeTeamId(), xg, xgc);
            seedSide(row.id(), row.awayTeamId(), xg, xgc);
        }
    }

    private void seedSide(int fixtureId, int club, double xg, double xgc) {
        int ghost = GHOST_BASE + club * 1000 + fixtureId % 1000;
        seedPfs(ghost, fixtureId, 90, club, club, null);
        jdbc.update("UPDATE player_fixture_stats SET expected_goals = ?, expected_goals_conceded = ?"
                        + " WHERE season_id = ? AND player_id = ? AND fixture_id = ?",
                xg, xgc, SEASON_ID, ghost, fixtureId);
    }

    /**
     * The club's fixture in a named round. {@code fixtureIdsFor} lists rounds interleaved
     * (GW1, GW20, GW2, GW21, ...), so its first two entries are not the two rounds
     * {@code seedAverageLeague} finished.
     */
    private static int fixtureFor(int club, int gameweek) {
        return RankingSeed.fixtureFor(RankingSeed.fixtures(CAPTURED_AT, FIRST_DEADLINE),
                club, gameweek);
    }

    @Test
    void clubSidesSumTheirPlayersAttackAndReadTheTeamConcessionOnce() {
        int club = RankingSeed.team(1);
        seedAverageLeague(2, 1.40, 1.40);
        List<Integer> fixtures = List.of(fixtureFor(club, 1), fixtureFor(club, 2));
        // A second player of the same club in the same two fixtures: xG adds, xGC does not.
        seedDaily(PLAYER, "Second", club, 2, "a", CAPTURE_DATE, CAPTURED_AT);
        for (int fixture : fixtures) {
            seedPfs(PLAYER, fixture, 90, club, club, null);
            jdbc.update("UPDATE player_fixture_stats SET expected_goals = 0.60,"
                            + " expected_goals_conceded = 1.40"
                            + " WHERE season_id = ? AND player_id = ? AND fixture_id = ?",
                    SEASON_ID, PLAYER, fixture);
        }

        ClubRates.Rates rates = load(CAPTURE_DATE).clubRates();
        ClubRates.ClubRate rate = rates.clubs().get(club);

        assertThat(rate.fixturesThisSeason()).isEqualTo(2);
        assertThat(rate.attack()).isCloseTo(2.00, within(1e-9));        // 1.40 ghost + 0.60
        assertThat(rate.defence()).isCloseTo(1.40, within(1e-9));       // MAX, not 2.80
        assertThat(rates.league()).isPresent();
        assertThat(rates.league().orElseThrow().sides()).isEqualTo(40);
    }

    @Test
    void clubSidesStopAtTheSnapshotInstantAndAtUnpopulatedConcession() {
        int club = RankingSeed.team(1);
        seedAverageLeague(3, 1.40, 1.40);
        List<Integer> fixtures = List.of(fixtureFor(club, 1), fixtureFor(club, 2),
                fixtureFor(club, 3));
        // Third fixture finished but dated after asOf: not this snapshot's evidence.
        finishFixture(fixtures.get(2), AS_OF.plus(1, ChronoUnit.DAYS));
        // Second fixture's team concession never written: the side is unpopulated, not perfect.
        jdbc.update("UPDATE player_fixture_stats SET expected_goals_conceded = 0"
                        + " WHERE season_id = ? AND fixture_id = ? AND was_home = ?",
                SEASON_ID, fixtures.get(1), fixtureInvolvesAsHome(fixtures.get(1), club));

        ClubRates.ClubRate rate = load(CAPTURE_DATE).clubRates().clubs().get(club);

        assertThat(rate).isNull();   // one usable side is under the two-side floor
    }

    private boolean fixtureInvolvesAsHome(int fixtureId, int club) {
        Integer home = jdbc.queryForObject("SELECT team_h FROM " + fixtureTable
                + " WHERE season = ? AND id = ?", Integer.class, SEASON, fixtureId);
        return home != null && home == club;
    }

    @Test
    void previousSeasonClubSidesBridgeByShortNameAndRampOnTheClubsOwnFixtures() {
        int club = RankingSeed.team(1);
        seedAverageLeague(2, 1.40, 1.40);
        seedDaily(PLAYER, "Bridged", club, 2, "a", CAPTURE_DATE, CAPTURED_AT);
        // The capture stamps every club 'K31'; give last season one club with that short name
        // and 38 sides, plus a spare with another name that must not attach.
        jdbc.update("""
                INSERT INTO team (id, season_id, code, name, short_name, strength) VALUES
                    (9861, ?, 9861, 'Prior Same Name', 'K31', 3),
                    (9862, ?, 9862, 'Prior Other', 'PRO', 3)
                """, PREVIOUS_SEASON_ID, PREVIOUS_SEASON_ID);
        for (int index = 0; index < 38; index++) {
            int fixture = 986_100 + index;
            boolean home = index % 2 == 0;
            Instant kickoff = Instant.parse("2025-08-01T12:00:00Z").plus(index, ChronoUnit.DAYS);
            jdbc.update("""
                    INSERT INTO fixture (id, season_id, gameweek, code, team_h, team_a,
                                         kickoff_time, started, finished)
                    VALUES (?, ?, ?, ?, ?, ?, ?, true, true)
                    """, fixture, PREVIOUS_SEASON_ID, index + 1, fixture,
                    home ? 9861 : 9862, home ? 9862 : 9861, Timestamp.from(kickoff));
            jdbc.update("""
                    INSERT INTO player_fixture_stats (
                        fixture_id, player_id, season_id, gameweek, player_team_id,
                        opponent_team_id, was_home, kickoff_time, minutes, total_points,
                        expected_goals, expected_assists, expected_goals_conceded, value, starts)
                    VALUES (?, ?, ?, ?, 9861, 9862, ?, ?, 90, 4, 1.00, 0.10, 2.00, 50, 1)
                    """, fixture, GHOST_BASE + 1, PREVIOUS_SEASON_ID, index + 1, home,
                    Timestamp.from(kickoff));
        }

        ClubRates.ClubRate rate = loadWithPrevious().clubRates().clubs().get(club);

        assertThat(rate.hasPrevious()).isTrue();
        assertThat(rate.fixturesThisSeason()).isEqualTo(2);
        assertThat(rate.weight()).isEqualTo(SeasonBlend.weight(2));
        assertThat(rate.attack()).isCloseTo(0.30 * 1.40 + 0.70 * 1.00, within(1e-9));
        assertThat(rate.defence()).isCloseTo(0.30 * 1.40 + 0.70 * 2.00, within(1e-9));
        // No other club has a captured player in this test, so nothing else bridges: club 2
        // stays on this season alone, which is what an unbridged club must do.
        assertThat(loadWithPrevious().clubRates().clubs().get(RankingSeed.team(2)).hasPrevious())
                .isFalse();
    }
```

The bridge in the third test is unique on both sides: `seedDaily` stamps `team_short = 'K31'`
and only club 1 has a capture row, while last season exactly one team carries `'K31'`. Cleanup
needs no change: every ghost row carries `SEASON_ID` or `PREVIOUS_SEASON_ID`, which the existing
season-keyed deletes already remove, and teams 9861/9862 carry `PREVIOUS_SEASON_ID`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests '*RankingMetricsIntegrationTest'`
Expected: compilation failure — `Snapshot.clubRates()` does not exist.

- [ ] **Step 3: Extend `Snapshot` and load the sides**

In `RankingMetrics`:

```java
    public record Snapshot(Instant asOf, DailyPriceSource.Capture capture,
                           MetricSeasons metricSeasons, ScheduleSource.View schedule,
                           List<Candidate> candidates, ClubRates.Rates clubRates) {
        public Snapshot {
            candidates = List.copyOf(candidates);
            clubRates = Objects.requireNonNull(clubRates);
        }
    }
```

In `loadInside`, after `previousMetrics` is built and before the candidate loop:

```java
        // Club sides for the same seasons the player metrics came from, keyed by capture team id.
        Map<Integer, ClubRates.SeasonSide> currentSides;
        if (metric.played().currentSeason()) {
            currentSides = currentSourceFailure(capture, metric.played(), schedule, asOf) != null
                    ? Map.of()
                    : loadClubSides(new CanonicalSource(schedules.fixtureTable(), "season",
                            schedule.snapshot().orElseThrow().season()),
                            metric.played().seasonId(), asOf);
        } else {
            currentSides = bridgeClubs(loadClubSides(new CanonicalSource("fixture", "season_id",
                    metric.played().seasonId()), metric.played().seasonId(), null),
                    metric.played().seasonId(), capturePlayers);
        }
        Map<Integer, ClubRates.SeasonSide> previousSides = Map.of();
        if (metric.previous().isPresent()) {
            SeasonContext previous = metric.previous().orElseThrow();
            previousSides = bridgeClubs(loadClubSides(new CanonicalSource("fixture", "season_id",
                    previous.seasonId()), previous.seasonId(), null), previous.seasonId(),
                    capturePlayers);
        }
        ClubRates.Rates clubRates = ClubRates.blend(currentSides, previousSides);
```

and pass `clubRates` as the last argument of `new Snapshot(...)`.

The read (one query per season, keyed by that season's team id):

```java
    /**
     * One row per club: its completed fixture-sides in this season with the team's expected goals
     * (summed over its players) and expected goals conceded (the team figure, read once with
     * MAX). Sides are attributed by the canonical fixture, never {@code player_team_id}, and a
     * side whose concession was never written is left out rather than read as a shut-out.
     */
    private Map<Integer, ClubRates.SeasonSide> loadClubSides(CanonicalSource source, int seasonId,
                                                              Instant asOf) {
        String cutoff = asOf == null ? "" : " AND f.kickoff_time <= ?";
        String sql = """
                WITH sides AS (
                    SELECT f.id AS fixture_id, p.was_home,
                           CASE WHEN p.was_home THEN f.team_h ELSE f.team_a END AS club,
                           SUM(p.expected_goals) AS xg_for,
                           MAX(p.expected_goals_conceded) AS xg_against
                    FROM player_fixture_stats p
                    JOIN %s f ON f.id = p.fixture_id AND f.%s = ?
                    WHERE p.season_id = ?
                      AND COALESCE(f.finished, false) AND f.kickoff_time IS NOT NULL%s
                      AND p.opponent_team_id = CASE WHEN p.was_home THEN f.team_a ELSE f.team_h END
                      AND p.minutes BETWEEN 0 AND 90
                    GROUP BY f.id, p.was_home, CASE WHEN p.was_home THEN f.team_h ELSE f.team_a END
                )
                SELECT club, count(*) AS fixtures, SUM(xg_for) AS xg_for,
                       SUM(xg_against) AS xg_against,
                       count(*) FILTER (WHERE was_home) AS home_fixtures,
                       COALESCE(SUM(xg_for) FILTER (WHERE was_home), 0) AS home_xg_for
                FROM sides WHERE xg_against > 0 GROUP BY club
                """.formatted(source.table(), source.seasonColumn(), cutoff);
        List<Object> args = new ArrayList<>();
        args.add(source.seasonValue());
        args.add(seasonId);
        if (asOf != null) {
            args.add(Timestamp.from(asOf));
        }
        Map<Integer, ClubRates.SeasonSide> sides = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(sql, args.toArray())) {
            sides.put(integer(row.get("club")), new ClubRates.SeasonSide(
                    integer(row.get("fixtures")), number(row.get("xg_for")),
                    number(row.get("xg_against")), integer(row.get("home_fixtures")),
                    number(row.get("home_xg_for"))));
        }
        return sides;
    }

    /**
     * Re-keys a season's club sides by the capture's team id through a unique, case-insensitive
     * short-name match — the club counterpart of {@link #uniqueBridge}. FPL renumbers clubs every
     * season, so an id is not an identity across seasons; a short name that is not unique on
     * either side attaches nothing rather than guessing.
     */
    private Map<Integer, ClubRates.SeasonSide> bridgeClubs(Map<Integer, ClubRates.SeasonSide> bySeasonId,
                                                            int seasonId,
                                                            List<CapturePlayer> capturePlayers) {
        if (bySeasonId.isEmpty()) {
            return Map.of();
        }
        Map<String, List<Integer>> seasonByName = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT id, short_name FROM team WHERE season_id = ?", seasonId)) {
            seasonByName.computeIfAbsent(key(String.valueOf(row.get("short_name"))),
                    ignored -> new ArrayList<>()).add(integer(row.get("id")));
        }
        Map<String, Set<Integer>> captureByName = new HashMap<>();
        for (CapturePlayer player : capturePlayers) {
            if (player.teamId() != null && player.teamShort() != null) {
                captureByName.computeIfAbsent(key(player.teamShort()), ignored -> new HashSet<>())
                        .add(player.teamId());
            }
        }
        Map<Integer, ClubRates.SeasonSide> bridged = new HashMap<>();
        captureByName.forEach((name, captureIds) -> {
            List<Integer> seasonIds = seasonByName.getOrDefault(name, List.of());
            if (captureIds.size() == 1 && seasonIds.size() == 1) {
                ClubRates.SeasonSide side = bySeasonId.get(seasonIds.getFirst());
                if (side != null) {
                    bridged.put(captureIds.iterator().next(), side);
                }
            }
        });
        return bridged;
    }
```

Fix the `PickToolsCrossSeasonTest` constructor call by appending `ClubRates.NONE`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests '*RankingMetricsIntegrationTest' --tests '*PickToolsCrossSeasonTest'`
Expected: all pass, including the three new tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/tools/RankingMetrics.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/tools/RankingMetricsIntegrationTest.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/tools/PickToolsCrossSeasonTest.java
git commit -m "Read club expected-goal sides inside the ranking snapshot"
```

---

### Task 3: `ExpectedPoints.perFixture` takes the projected concession

**Files:**
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/tools/ExpectedPoints.java` (`perFixture`)
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/tools/ExpectedPointsTest.java`
- Modify (compile only, temporarily pass `OptionalDouble.empty()`): `PickTools.java:436`,
  `HorizonScorer.java` (the one `perFixture` call), `HorizonScorerTest.java` (`perFixture` helper
  and the `partialDefcon` assertion at line 85), `RankingMetricsIntegrationTest.java:444`,
  `ExpectedPointsTest.java` (existing `perFixture` calls at lines 153–218).

**Interfaces:**
- Produces: `public static Optional<PerFixture> perFixture(Map<String, Object> row, SeasonBlend.Exposure exposure, OptionalDouble projectedGoalsAgainst)`. `PerFixture` keeps its shape: `cleanSheet` is `null` when the projection is absent for a position that earns clean-sheet points, `0.0` when the position earns none or `sixtyRate == 0`, else `cleanSheetPoints × sixtyRate × exp(-λ)`. The two-argument overload is removed. `xgc_per_match` is no longer read by `perFixture` (it still feeds the legacy `perNinety`).

- [ ] **Step 1: Write the failing tests**

Add to `ExpectedPointsTest`:

```java
    private static SeasonBlend.Exposure fullMinutes() {
        return new SeasonBlend.Exposure(10, 10, 10, 900, 1, 1, 0, 90, 4);
    }

    @Test
    void theCleanSheetTermIsPoissonOnTheProjectedGoalsAgainstNotOnTheSeasonAverage() {
        Map<String, Object> defender = row(2, 0, 0, 0.30);   // a season average that must not matter

        ExpectedPoints.PerFixture fixture = ExpectedPoints.perFixture(defender, fullMinutes(),
                OptionalDouble.of(1.25)).orElseThrow();

        assertThat(fixture.cleanSheet()).isCloseTo(4 * Math.exp(-1.25), within(1e-9));
        assertThat(fixture.points()).isCloseTo(fixture.appearance() + fixture.cleanSheet(), within(1e-9));
    }

    @Test
    void anUnavailableProjectionLeavesTheCleanSheetUnknownRatherThanZeroOrAverage() {
        Map<String, Object> defender = row(2, 0, 0, 0.30);

        ExpectedPoints.PerFixture fixture = ExpectedPoints.perFixture(defender, fullMinutes(),
                OptionalDouble.empty()).orElseThrow();

        assertThat(fixture.cleanSheet()).isNull();
        assertThat(fixture.points()).isCloseTo(fixture.appearance(), within(1e-9));
    }

    @Test
    void aForwardKeepsAZeroCleanSheetTermWhateverTheProjection() {
        ExpectedPoints.PerFixture fixture = ExpectedPoints.perFixture(row(4, 0, 0, null),
                fullMinutes(), OptionalDouble.of(0.10)).orElseThrow();

        assertThat(fixture.cleanSheet()).isEqualTo(0.0);
    }

    @Test
    void theCleanSheetIsConditionalOnReachingSixtyMinutes() {
        SeasonBlend.Exposure cameo = new SeasonBlend.Exposure(10, 10, 0, 200, 1, 0, 1, 20, 1);

        ExpectedPoints.PerFixture fixture = ExpectedPoints.perFixture(row(2, 0, 0, null), cameo,
                OptionalDouble.of(0.50)).orElseThrow();

        assertThat(fixture.cleanSheet()).isEqualTo(0.0);
    }
```

Add `import java.util.OptionalDouble;` to the test.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests '*ExpectedPointsTest'`
Expected: compilation failure — no three-argument `perFixture`.

- [ ] **Step 3: Rewrite `perFixture`**

```java
    /**
     * Expected components over one fixture at the supplied observed exposure rates.
     *
     * <p>The clean-sheet term is the fixture's: {@code exp(-λ)} on the concession
     * {@link ClubRates#projectedGoalsAgainst} projects for this club against this opponent at
     * this venue, conditional on reaching sixty minutes. When no projection exists the term is
     * absent — {@code null}, printed n/a and labelled partial — never the player's season
     * average and never a certain clean sheet. Appearance, attack and DefCon do not depend on the
     * fixture and are identical across a club's window.
     */
    public static Optional<PerFixture> perFixture(Map<String, Object> row,
                                                   SeasonBlend.Exposure exposure,
                                                   OptionalDouble projectedGoalsAgainst) {
        if (exposure == null) {
            return Optional.empty();
        }
        int position = ((Number) row.get("element_type")).intValue();
        double appearance = exposure.appearanceRate() + exposure.sixtyRate();
        double attack = (goalPoints(position) * numeric(row.get("xg90"))
                + ASSIST_POINTS * numeric(row.get("xa90"))) * exposure.expectedMinutes() / 90;

        Double cleanSheet = 0.0;
        if (cleanSheetPoints(position) > 0 && exposure.sixtyRate() > 0) {
            cleanSheet = projectedGoalsAgainst.isPresent()
                    ? cleanSheetPoints(position) * exposure.sixtyRate()
                            * cleanSheetProbability(projectedGoalsAgainst.getAsDouble())
                    : null;
        }

        DefconFixture defcon = defconPerFixture(row, position, exposure);
        double points = appearance + attack + (cleanSheet == null ? 0 : cleanSheet)
                + defcon.points();
        return Optional.of(new PerFixture(points, appearance, attack, cleanSheet,
                defcon.points(), defcon.partial()));
    }
```

Add `import java.util.OptionalDouble;`. Update the class javadoc sentence "The xGC clean-sheet
term remains a season-average conditional approximation" to "The clean-sheet term is per fixture,
from `ClubRates`". Update every existing caller to pass `OptionalDouble.empty()` for now (Tasks 4
and 5 replace those with real projections):
- `PickTools.candidates` (line ~436)
- `HorizonScorer.projection`
- `HorizonScorerTest.perFixture` helper and line 85
- `RankingMetricsIntegrationTest` line ~444
- existing `ExpectedPointsTest` calls

- [ ] **Step 4: Run the focused suites**

Run: `./gradlew test --tests '*ExpectedPointsTest' --tests '*HorizonScorerTest' --tests '*RankingMetricsIntegrationTest' --tests '*PickTools*'`
Expected: `ExpectedPointsTest` green including the four new tests. `PickToolsIntegrationTest`
`outputUsesDailyPriceAndLabelsPartialExpectedComponents` still passes (partial from another
row). Any assertion that pinned the old `season-average conditional at xGC` wording fails here
and is fixed in Task 4 — note which ones.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/tools/ExpectedPoints.java \
        backend/src/main/java/fi/karivanttinen/fplassistant/tools/PickTools.java \
        backend/src/main/java/fi/karivanttinen/fplassistant/squad/HorizonScorer.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/tools/ExpectedPointsTest.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/squad/HorizonScorerTest.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/tools/RankingMetricsIntegrationTest.java
git commit -m "Take the clean-sheet term per fixture from a projected concession"
```

---

### Task 4: Pick tools total the per-fixture clean sheet over the horizon

**Files:**
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/tools/PickTools.java` —
  `candidates`, `components`, `footer`, both `@Tool` descriptions, class javadoc.
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/tools/PickToolsIntegrationTest.java`,
  `backend/src/test/java/fi/karivanttinen/fplassistant/tools/PickToolsRankingTest.java`.

**Interfaces:**
- Consumes: `snapshot.clubRates()` (Task 2), `ExpectedPoints.perFixture(row, exposure, OptionalDouble)` (Task 3), `HorizonFixtures.Fixture.opponentTeamId()/home()`.
- Produces on each pick row: `expected_per_fixture` = horizon average of per-fixture points;
  `fixture_projections` = `List<PickTools.FixtureProjection>` where
  `record FixtureProjection(HorizonFixtures.Fixture fixture, ExpectedPoints.PerFixture components, OptionalDouble goalsAgainst)`;
  `projection` = the first fixture's `PerFixture` (appearance, attack, DefCon are fixture-independent).
  `rank`, `score`, `horizonScore`, `realisedWeight` unchanged.

- [ ] **Step 1: Write the failing tests**

In `PickToolsIntegrationTest`, add a league seed so every club has a rate, and two tests. Ghost
ids stay inside `FIRST_ID..LAST_ID` so the existing `cleanUp` removes them.

```java
    /** One ghost row per club side for gameweeks 1-3: an average league every club belongs to. */
    private static final int GHOST_FIRST = 13_050;

    private void seedAverageLeague(double xg, double xgc) {
        for (ScheduleSource.FixtureRow row : RankingSeed.fixtures(CAPTURED_AT, FIRST_DEADLINE)) {
            if (row.gameweek() > 3) {
                continue;
            }
            seedGhostSide(row, row.homeTeamId(), xg, xgc);
            seedGhostSide(row, row.awayTeamId(), xg, xgc);
        }
    }

    private void seedGhostSide(ScheduleSource.FixtureRow row, int club, double xg, double xgc) {
        int ghost = GHOST_FIRST + (club - RankingSeed.team(1));
        boolean home = row.homeTeamId() == club;
        jdbc.update("""
                INSERT INTO player_fixture_stats (
                    fixture_id, player_id, season_id, gameweek, player_team_id,
                    opponent_team_id, was_home, kickoff_time, minutes, total_points,
                    expected_goals, expected_assists, expected_goals_conceded, value, starts)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 90, 2, ?, 0, ?, 45, 1)
                """, row.id(), ghost, SEASON_ID, row.gameweek(), club,
                home ? row.awayTeamId() : row.homeTeamId(), home,
                Timestamp.from(row.kickoff()), xg, xgc);
    }
```

Call `seedAverageLeague(1.20, 1.20);` at the end of `seed()`, after the players. Then:

```java
    @Test
    void eachPickListsItsCleanSheetChancePerFixtureWithOpponentAndVenue() {
        String defender = lineFor(tools.getDifferentialPicks("DEF", 10, 2), "Defender");

        // Clubs 10-20 have no captured player here, so an opponent may print as "team <id>";
        // in production every club has one and prints its short name.
        assertThat(defender).contains("CS ").contains(" over 2 fixtures (vs ")
                .containsPattern("vs (K\\d\\d|team \\d+) [ha] \\d+% at xGA \\d+\\.\\d\\d")
                .doesNotContain("season-average").doesNotContain("at xGC");
    }

    @Test
    void aStrongerOpponentLowersTheCleanSheetTermForThatFixtureOnly() {
        int club = RankingSeed.team(3);                                      // the Defender's club
        int firstFixture = fixtureFor(club, 4);                              // GW4, the first editable round
        int opponent = jdbc.queryForObject("SELECT CASE WHEN team_h = ? THEN team_a ELSE team_h END"
                + " FROM " + scheduleSource.fixtureTable() + " WHERE season = ? AND id = ?",
                Integer.class, club, SEASON, firstFixture);
        String before = lineFor(tools.getDifferentialPicks("DEF", 10, 2), "Defender");

        // Triple the opponent's attack in every side it has played.
        jdbc.update("UPDATE player_fixture_stats SET expected_goals = expected_goals * 3"
                + " WHERE season_id = ? AND player_id = ?", SEASON_ID,
                GHOST_FIRST + (opponent - RankingSeed.team(1)));
        String after = lineFor(tools.getDifferentialPicks("DEF", 10, 2), "Defender");

        assertThat(horizonScore(after)).isLessThan(horizonScore(before));
        assertThat(after).contains("at xGA ");
    }

    private static double horizonScore(String line) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("horizon Score (\\d+\\.\\d\\d)").matcher(line);
        assertThat(m.find()).as("horizon Score in: " + line).isTrue();
        return Double.parseDouble(m.group(1));
    }
```

Also update `returnsOnlyEligibleDifferentialsWithPerFixtureEvidence` to add
`.contains("projected per fixture")` (the footer sentence below).

In `PickToolsRankingTest`, add a pure test that the printed per-fixture average and the horizon
total agree with a per-fixture sum — `horizonScore` must remain `score × count`:

```java
    @Test
    void theHorizonScoreIsThePerFixtureAverageTimesTheCountSoASummedCleanSheetTotalsExactly() {
        // Two fixtures worth 3.0 and 5.0 expected (different clean-sheet projections) average 4.0.
        Map<String, Object> row = pick("Avg", 1, 1, 4.0);
        assertThat(PickTools.horizonScore(row, windows(window(1, 3, 3)), 0.0))
                .isCloseTo(3.0 + 5.0, within(1e-9));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests '*PickToolsIntegrationTest' --tests '*PickToolsRankingTest'`
Expected: the two new integration tests fail (no `xGA` in the line; `horizonScore` equal before
and after). The ranking test passes already — it pins the invariant Step 3 must keep.

- [ ] **Step 3: Compute the clean sheet per fixture in `candidates` and print it**

In `PickTools`, add the record and replace the projection block inside `candidates`:

```java
    /** One counted fixture's expected components, with the concession the clean sheet rests on. */
    record FixtureProjection(HorizonFixtures.Fixture fixture,
                             ExpectedPoints.PerFixture components,
                             OptionalDouble goalsAgainst) {
    }
```

```java
            List<FixtureProjection> projections = new ArrayList<>(club.count());
            double expectedHorizon = 0;
            for (HorizonFixtures.Fixture fixture : club.fixtures()) {
                OptionalDouble goalsAgainst = ClubRates.projectedGoalsAgainst(
                        snapshot.clubRates(), player.teamId(), fixture.opponentTeamId(),
                        fixture.home());
                ExpectedPoints.PerFixture components = ExpectedPoints.perFixture(
                        row, candidate.blended().exposure(), goalsAgainst).orElseThrow();
                projections.add(new FixtureProjection(fixture, components, goalsAgainst));
                expectedHorizon += components.points();
            }
            row.put("expected_per_fixture", expectedHorizon / club.count());
            row.put("points_per_fixture", candidate.blended().exposure().pointsPerFixture());
            row.put("current_window_minutes", candidate.current().window().totalMinutes());
            row.put("projection", projections.getFirst().components());
            row.put("fixture_projections", List.copyOf(projections));
            row.put("ranking_candidate", candidate);
            rows.add(row);
```

(`club.count() > 0` is guaranteed: the `count() == 0` branch above `continue`s.) Add
`import java.util.OptionalDouble;`.

Replace `components(row)`:

```java
    private static String components(Map<String, Object> row) {
        ExpectedPoints.PerFixture projection =
                (ExpectedPoints.PerFixture) row.get("projection");
        return "appearance " + fixed(projection.appearance(), 2)
                + ", attack " + fixed(projection.attack(), 2)
                + ", " + cleanSheets(row)
                + ", DC " + fixed(projection.defensiveContribution(), 2)
                + (projection.partialDefcon() ? " (partial: a minutes class lacks two known rows)" : "");
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
            String opponent = "vs " + shortNameOf(row, leg.fixture()) + (leg.fixture().home() ? " h" : " a");
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
```

`shortNameOf(row, fixture)` needs opponent short names. Carry them on the row: in `candidates`,
before the loop over players, build `Map<Integer, String> shortNames` from
`snapshot.candidates()` (`capture().teamId()` → `capture().teamShort()`, first non-null wins) and
`row.put("club_short_names", shortNames)`; `shortNameOf` reads that map and falls back to
`"team " + fixture.opponentTeamId()` when the id has no captured player.

The row is *partial* when any leg lacks a projection. `components()`' existing partial label is
DefCon's; the clean-sheet partial is inside `cleanSheets()`. Nothing else in the row changes.

Footer: replace the sentence
`" clean sheets use P(60+) and a season-average conditional exp(-xGC) approximation.\n"` with

```java
                + " clean sheets are projected per fixture: exp(-xGA) where xGA is the club's"
                + " expected concession rate times the opponent's expected scoring rate over the"
                + " league mean, adjusted for venue, conditional on P(60+). Club rates blend both"
                + " seasons on the shared ramp keyed on the club's own completed fixtures; a"
                + " fixture with no club projection is n/a and its row is partial.\n"
```

Tool descriptions (both): replace `60+-conditional clean sheets` with
`60+-conditional clean sheets projected per fixture from club xG/xGC rates and venue`.

Class javadoc: replace "clean sheets are conditional on reaching 60" with "clean sheets are
projected per fixture from both clubs' expected-goal rates and the venue (KAR-30), conditional on
reaching 60".

- [ ] **Step 4: Run the pick suites**

Run: `./gradlew test --tests '*PickTools*'`
Expected: all green. Two things the average-league seed can disturb, and how to settle each:
- If `outputUsesDailyPriceAndLabelsPartialExpectedComponents` fails on `"partial"`, the seed
  removed every partial row — change that assertion to `.contains("CS ").contains("DC ")` and
  add a test that deletes one opponent's ghost rows (`DELETE FROM player_fixture_stats WHERE
  season_id = ? AND player_id = ?` with that opponent's ghost id) and asserts
  `"(partial: no club projection for 1 fixture)"` on the affected pick line.
- The KAR-35 ordering tests (`aDoubleGameweekOutranksACloseSingleButNotAFarBetterOne`,
  `theAverageFdrCoversEveryFixtureOfADoubleGameweekWindow`) rank midfielders, who now gain
  about `exp(-1.2) ≈ 0.30` clean-sheet points per fixture at an average club. If either flips,
  widen the seeded xG gap between `NEAR_MID`/`FAR_MID` and `DOUBLE_MID` until the intended
  order holds again; do not touch the model. Record the new margins in the test's javadoc.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/tools/PickTools.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/tools/PickToolsIntegrationTest.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/tools/PickToolsRankingTest.java
git commit -m "Total the per-fixture clean-sheet projection in the pick horizon Score"
```

---

### Task 5: Squad objective — projected clean sheet per fixture, FDR on attack only

**Files:**
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/squad/HorizonScorer.java`,
  `backend/src/main/java/fi/karivanttinen/fplassistant/squad/SquadTools.java` (lines ~189–199 and the blend note ~221–229, the `@Tool` description ~92–96),
  `backend/src/main/java/fi/karivanttinen/fplassistant/squad/SquadFormatter.java` (line ~277 wording).
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/squad/HorizonScorerTest.java`,
  `HorizonScorerIntegrationTest.java` (line 96), `SquadFormatterTest.java` (line ~437),
  `SquadToolsIntegrationTest.java`.

**Interfaces:**
- Produces: `HorizonScorer.projection(SquadCandidate candidate, HorizonFixtures.ClubWindow window, ClubRates.Rates rates)` and `HorizonScorer.score(candidate, window, rates)`. `Projection` record unchanged. Per counted fixture:
  `appearance + defensiveContribution + cleanSheet(λ_f) + attack × fdrAdjust(FDR_f or NEUTRAL_FDR)`; `partial` when any leg's clean sheet is `null` or DefCon is partial.

- [ ] **Step 1: Write the failing tests**

In `HorizonScorerTest`, add a rates helper and rewrite the FDR arithmetic tests:

```java
    private static final int OPPONENT = 1;   // window() fixtures are all "home vs club 1"

    /** Forty average sides so the league floor is met; CLUB and OPPONENT at the given rates. */
    private static ClubRates.Rates rates(double clubDefence, double opponentAttack) {
        Map<Integer, ClubRates.SeasonSide> sides = new HashMap<>();
        for (int id = 100; id < 120; id++) {
            sides.put(id, new ClubRates.SeasonSide(2, 2.8, 2.8, 1, 1.4));
        }
        sides.put(CLUB, new ClubRates.SeasonSide(2, 2.8, 2 * clubDefence, 1, 1.4));
        sides.put(OPPONENT, new ClubRates.SeasonSide(2, 2 * opponentAttack, 2.8, 1, 1.4));
        return ClubRates.blend(sides, Map.of());
    }

    private static double perFixture(SquadCandidate candidate) {
        return ExpectedPoints.perFixture(candidate.metrics(), candidate.exposure(),
                OptionalDouble.empty()).orElseThrow().points();
    }

    @Test
    void fdrScalesTheAttackingTermOnlyAndTheCleanSheetFollowsTheOpponentModel() {
        SquadCandidate player = fourPointsPerFixture();          // FWD: 2 appearance + 2 attack

        HorizonScorer.Projection projection = HorizonScorer.projection(player, window(2, 4),
                ClubRates.NONE);

        // Appearance is not fixture-scaled; only the two attacking points are.
        assertThat(projection.optimizerPoints()).isCloseTo(
                2 * 2.0 + 2.0 * (HorizonScorer.fdrAdjust(2) + HorizonScorer.fdrAdjust(4)),
                within(1e-9));
    }

    @Test
    void aMeanerDefenceOrAWeakerOpponentRaisesTheProjectedCleanSheet() {
        SquadCandidate stopper = defender();

        double leaky = HorizonScorer.score(stopper, window(3), rates(2.0, 1.4));
        double mean = HorizonScorer.score(stopper, window(3), rates(0.7, 1.4));
        double weakOpponent = HorizonScorer.score(stopper, window(3), rates(1.4, 0.7));
        double strongOpponent = HorizonScorer.score(stopper, window(3), rates(1.4, 2.5));

        assertThat(mean).isGreaterThan(leaky);
        assertThat(weakOpponent).isGreaterThan(strongOpponent);
    }

    @Test
    void aFixtureWithoutAClubProjectionIsPartialAndContributesNoCleanSheet() {
        SquadCandidate stopper = defender();

        HorizonScorer.Projection projection = HorizonScorer.projection(stopper, window(3),
                ClubRates.NONE);

        assertThat(projection.partial()).isTrue();
        assertThat(projection.optimizerPoints()).isCloseTo(2.0, within(1e-9));   // appearance only
    }

    /** A defender with no attacking or DefCon record: appearance plus whatever the clean sheet is. */
    private static SquadCandidate defender() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("element_type", 2);
        metrics.put("xg90", BigDecimal.ZERO);
        metrics.put("xa90", BigDecimal.ZERO);
        metrics.put("defcon_avg", BigDecimal.ZERO);
        metrics.put("defcon_matches", 2);
        SeasonBlend.Exposure exposure = new SeasonBlend.Exposure(10, 10, 10, 900, 1, 1, 0, 90, 3);
        return new SquadCandidate(4, "Stopper", "Stopper Player", "Ashfield Rovers", "ASH", 2,
                45, 5.0, "a", null, CLUB, 90, metrics, SquadCandidate.Tier.RANKABLE, null,
                exposure, MinutesModel.Eligibility.ELIGIBLE);
    }
```

Then update the existing tests to the new arithmetic (all take `ClubRates.NONE` as the third
argument):
- `aTwoFixtureWindowSumsBothFdrAdjustedPerFixtureValues`: expected becomes
  `2 * (components.appearance() + components.defensiveContribution()) + components.attack() * (fdrAdjust(2) + fdrAdjust(4))`
  where `components = ExpectedPoints.perFixture(player.metrics(), player.exposure(), OptionalDouble.empty()).orElseThrow()`.
- `aDoubleGameweekLegWithNoPublishedFdrKeepsTheCountAndGoesNeutralAlone`: expected
  `2 * 2.0 + 2.0 * (fdrAdjust(2) + fdrAdjust(3))` = 8.2.
- `fourPointsAcrossAnFdrTwoAndAnFdrFourFixtureIsEightPoints`: still 8.0 (`4 + 2 × 2.0`); keep.
- `anOutOfRangeDifficultyIsTreatedAsUnpublishedRatherThanScored`: still 4.0; keep.
- `sharedPerFixtureComponentsAreNotScaledByMinutesTwice` and
  `eachFixtureContributesOnceToTheExistingCoarseHorizon`: unchanged in value (FDR 3 → factor 1).
- The "easy > hard" test: unchanged.

`HorizonScorerIntegrationTest` line 96: pass `ClubRates.NONE`. `SquadFormatterTest` line ~437:
expected string becomes `"FDR n/a for 1 fixture, neutral 3 assumed on the attacking term"`.

In `SquadToolsIntegrationTest` add:

```java
    @Test
    void theReplyExplainsTheProjectedCleanSheetAndItsClubRates() {
        assertThat(tools.buildSquad("", "", "", 100.0, 5, ""))
                .contains("Clean sheets are projected per fixture")
                .contains("club expected-goal rates");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests '*HorizonScorer*' --tests '*SquadFormatterTest' --tests '*SquadToolsIntegrationTest'`
Expected: compilation failure on the three-argument `projection`/`score`.

- [ ] **Step 3: Implement**

`HorizonScorer.projection`:

```java
    public static Projection projection(SquadCandidate candidate,
                                        HorizonFixtures.ClubWindow window,
                                        ClubRates.Rates rates) {
        if (candidate.tier() == SquadCandidate.Tier.BODY || candidate.exposure() == null) {
            return UNSCOREABLE;
        }
        if (!window.known()) {
            return ExpectedPoints.perFixture(candidate.metrics(), candidate.exposure(),
                    OptionalDouble.empty()).isEmpty()
                    ? UNSCOREABLE
                    : new Projection(0, false, false, 0, 0, window.unknownReason());
        }
        double total = 0;
        int neutralFdrLegs = 0;
        boolean partial = false;
        for (HorizonFixtures.Fixture fixture : window.fixtures()) {
            OptionalDouble goalsAgainst = ClubRates.projectedGoalsAgainst(rates,
                    candidate.teamId(), fixture.opponentTeamId(), fixture.home());
            Optional<ExpectedPoints.PerFixture> perFixture = ExpectedPoints.perFixture(
                    candidate.metrics(), candidate.exposure(), goalsAgainst);
            if (perFixture.isEmpty()) {
                return UNSCOREABLE;
            }
            ExpectedPoints.PerFixture components = perFixture.orElseThrow();
            boolean rated = fixture.hasValidDifficulty();
            if (!rated) {
                neutralFdrLegs++;
            }
            total += components.appearance() + components.defensiveContribution()
                    + (components.cleanSheet() == null ? 0 : components.cleanSheet())
                    + components.attack() * fdrAdjust(rated ? fixture.difficulty() : NEUTRAL_FDR);
            partial |= components.cleanSheet() == null || components.partialDefcon();
        }
        return new Projection(total, true, partial, window.count(), neutralFdrLegs, null);
    }

    public static double score(SquadCandidate candidate, HorizonFixtures.ClubWindow window,
                               ClubRates.Rates rates) {
        return projection(candidate, window, rates).optimizerPoints();
    }
```

Update the class javadoc: replace the last paragraph ("The fixture adjustment is coarse … Issue
#47 replaces it") with: "FDR scales the attacking term only. The clean sheet is projected per
fixture from both clubs' expected-goal rates and the venue (`ClubRates`, KAR-30), so scaling it
by FDR as well would count the opponent twice; appearance and DefCon are not fixture-scaled."
Add imports `java.util.OptionalDouble` and `fi.karivanttinen.fplassistant.tools.ClubRates`.

`SquadTools`: at line ~196 pass `loaded.snapshot().clubRates()`; extend `blendNote` — after the
existing `blendNote += " " + scheduleNote;` add:

```java
            blendNote += " Clean sheets are projected per fixture from club expected-goal rates:"
                    + " the club's expected concession times the opponent's expected scoring"
                    + " rate over the league mean, adjusted for venue, conditional on P(60+)."
                    + (metric.blending()
                            ? " Club rates blend " + metric.played().code() + " with "
                                    + metric.previousCode() + " on the same ramp keyed on the"
                                    + " club's completed fixtures."
                            : "")
                    + " FDR scales the attacking term only; a fixture with no club projection"
                    + " contributes no clean sheet and its row is partial.";
```

Tool description: replace `60+-conditional clean sheets and short/long DefCon, with coarse
captured-schedule FDR, summed over` with `60+-conditional clean sheets projected per fixture
from club xG/xGC rates and venue, short/long DefCon, and coarse captured-schedule FDR on the
attacking term only, summed over`.

`SquadFormatter` line ~279: `.append(", neutral ").append(HorizonScorer.NEUTRAL_FDR).append(" assumed on the attacking term)")`.

- [ ] **Step 4: Run the squad suites**

Run: `./gradlew test --tests '*Squad*' --tests '*HorizonScorer*' --tests '*Feasibility*'`
Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/squad \
        backend/src/test/java/fi/karivanttinen/fplassistant/squad
git commit -m "Project the squad clean sheet per fixture and keep FDR on attack only"
```

---

### Task 6: Prompt, conventions, record, full suite, pull request

**Files:**
- Modify: `backend/src/main/resources/prompts/system-prompt.st` (lines ~284–290),
  `AGENTS.md` (the `Expected ranking points are per fixture` paragraph, line ~135, and the
  `SeasonBlend` paragraph), `docs/STATUS.md` (new section), this plan (`PROGRESS`, outcome).

- [ ] **Step 1: System prompt**

In the `getDifferentialPicks / getCaptainCandidates` bullet replace `make clean sheets
conditional on 60+,` with:

```text
  project clean sheets per fixture — the club's expected concession against that opponent at
  that venue, from both clubs' xG/xGC rates, conditional on 60+ — so a stopper's `CS` term
  already carries the fixture and you should not discount it again for a hard opponent,
```

and after the "Average FDR … never a multiplier" sentence add:

```text
  Each pick's `CS` bracket lists every fixture in the window with the opponent, venue, clean-sheet
  chance and projected `xGA`; a fixture shown `n/a` had no club projection and the row is partial.
  When comparing two defenders, quote those per-fixture chances rather than a season average.
```

- [ ] **Step 2: AGENTS.md**

Replace the sentence `\`ExpectedPoints.perFixture\` uses \`P(play)+P(60+)\`, scales attack by
expected minutes, makes clean sheets conditional on 60+, and gives DefCon separate short/long
exposure.` with:

```text
`ExpectedPoints.perFixture` uses `P(play)+P(60+)`, scales attack by expected minutes, takes the
clean sheet **per fixture** as `exp(-xGA)` on the concession `ClubRates.projectedGoalsAgainst`
projects for that club against that opponent at that venue (KAR-30) — conditional on 60+, `null`
and labelled partial when no club projection exists, never the player's season-average xGC — and
gives DefCon separate short/long exposure. Club rates are read inside the same `RankingMetrics`
snapshot: team xG is `SUM` over the club's players per side, team xGC is `MAX` (FPL stamps the
team figure on every player), an unpopulated side (`xGC = 0`) is excluded, clubs bridge across
seasons by unique `short_name` the way players bridge by `web_name`, and the league mean and
home/away factors pool both seasons without a ramp.
```

Replace `\`HorizonScorer\` scores the squad over the same fixture population with the same
expected components and its existing coarse FDR objective — expected only, no realised blend and
no outer minutes multiplier.` with:

```text
`HorizonScorer` scores the squad over the same fixture population with the same expected
components, expected only — no realised blend, no outer minutes multiplier — and FDR scales the
attacking term only: the clean sheet already carries the opponent, so an FDR multiplier on it
would count the fixture twice. Appearance and DefCon are not fixture-scaled.
```

In the `SeasonBlend` paragraph add one sentence after `Do not reintroduce a single-season
switch, and do not write the ramp a second time in SQL.`:

```text
Club attack/defence rates (`ClubRates`) ride the same table, keyed on the club's own completed
current-season fixtures; league constants are pooled, not ramped.
```

- [ ] **Step 3: Run the full suite**

```bash
cd backend && set -a; source .env.test; set +a
./gradlew cleanTest test
```

Expected: `BUILD SUCCESSFUL`, 0 failures, 0 errors, 0 skipped. Record the count.

- [ ] **Step 4: `docs/STATUS.md`**

Append a section `## Per-fixture clean sheets from club xG/xGC (KAR-30, <date>)` with, in this
order: the defect (season-average, opponent-blind xGC beside a separate FDR average); the model
in the `lambda = …` form from Final decisions; the three decisions (whole-season club rates on
the shared ramp, pooled league constants, FDR on the squad attack term only) and why; the Task 0
table and what it said; the printing change (`CS … over N fixtures (vs …)`); what was verified
(the full-suite count, the local container only); honest limits — the attack term still has no
opponent model beyond coarse FDR in the squad objective and none in the pick lists, `xGA` is
FPL's own expected data not a third-party model, no calibration claim, and the projection is
Poisson without a low-scoring correction. Note that the KAR-35 outcome section's "KAR-30's
opponent-specific clean-sheet model is not implemented" sentence is now superseded (do not edit
the old section; STATUS is a journal).

- [ ] **Step 5: Plan bookkeeping, commit, push, PR**

Set `**PROGRESS:** Task 7/7 done.` and add `## Outcome` with the suite count and anything that
deviated from this plan.

```bash
git add backend/src/main/resources/prompts/system-prompt.st AGENTS.md docs/STATUS.md \
        docs/superpowers/plans/2026-09-13-kar-30-club-clean-sheet-projection.md
git commit -m "Document the per-fixture clean-sheet projection"
git push -u origin kar-30-club-clean-sheet-projection
gh pr create --base master --title "Project clean sheets per fixture from club xG/xGC" --body "$(cat <<'EOF'
The clean-sheet term was the player's season-average xGC, opponent-blind, with FDR averaged beside it. It is now projected per fixture from the defending club's xGC rate, the opponent's xG rate and venue, inside the same ranking snapshot and on the shared cross-season ramp; the squad objective keeps FDR on the attacking term only.
Measured offline on 2024-25 → 2025-26 (see the plan's Task 0 outcome); verified with the full suite on the local container.
Plan: docs/superpowers/plans/2026-09-13-kar-30-club-clean-sheet-projection.md
Closes KAR-30

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01B5qkH9GwWN9VvN4XsvbaRR
EOF
)"
```

Then review the PR (`superpowers:requesting-code-review`), apply fixes as new commits on the
branch, and stop — the owner merges.

---

## Self-review

- **Coverage of the issue.** Defence side (club xGC), opponent side (club xG), venue (league
  factors), `exp(-λ)` per fixture summed across the next N: Tasks 1, 3, 4, 5. The three open
  questions: decided in Final decisions, measured in Task 0. Inputs "already exist"
  (`player_fixture_stats` per-fixture xG/xGC with `was_home`, `opponent_team_id`): Task 2 reads
  them by canonical fixture side, not `player_team_id`.
- **Placeholders.** None: every code step is complete; the one deliberately conditional
  instruction (Task 4 Step 4's partial-assertion fallback) names the exact edit.
- **Type consistency.** `ClubRates.Rates`, `ClubRates.NONE`, `projectedGoalsAgainst(rates, defendingClub, opponent, defendingAtHome)`,
  `ExpectedPoints.perFixture(row, exposure, OptionalDouble)`, `HorizonScorer.projection(candidate, window, rates)`,
  `Snapshot.clubRates()`, `PickTools.FixtureProjection` — used with the same names and argument
  order in every task.
- **Conventions.** No new provenance line; unknown ≠ zero preserved on every path; one ramp;
  one snapshot; tests seed their own rows; nothing pushed to `master`.

## Outcome — Task 0

Measured offline on public vaastav CSVs (2024-25 as "previous", 2025-26 as "this season", the
held-out pair — 2026-27 has only four completed gameweeks today and cannot serve as the held-out
season). Backtest over 2025-26 GW3-38, rates computed only from rows dated before the predicted
gameweek. Population: 720 scored fixture-sides (20 clubs x 36 gameweeks, every gameweek fully
populated — no silent skips beyond the brief's own floors).

```
variant            Brier    log-loss   n
baseline           0.1839   0.5522   720
opponent           0.1833   0.5484   720
opponent-no-prev   0.1823   0.5468   720
opponent-last10    0.1860   0.5563   720

Brier by gameweek band
GW3-10: baseline 0.1806, opponent 0.1820, opponent-no-prev 0.1792, opponent-last10 0.1820
GW11-20: baseline 0.1895, opponent 0.1918, opponent-no-prev 0.1907, opponent-last10 0.1941
GW21-38: baseline 0.1822, opponent 0.1791, opponent-no-prev 0.1790, opponent-last10 0.1832
```

`opponent` beats `baseline` on Brier overall (0.1833 vs 0.1839) and on log-loss overall (0.5484 vs
0.5522) — the gate passes. The shape differs from what the brief anticipated, though: `opponent`
is *worse* than `baseline` in GW3-10 (0.1820 vs 0.1806) and GW11-20 (0.1918 vs 0.1895), and only
pulls ahead in GW21-38 (0.1791 vs 0.1822), the opposite band from where the largest gain was
expected.

- **Whole-season vs last-10:** whole-season club rates (`opponent`, 0.1833) beat the last-10-fixture
  window (`opponent-last10`, 0.1860) overall and in every band — the last-10 window is not clearly
  better as anticipated, it is uniformly worse, most likely because 10 fixtures per club is too
  thin a window for a Poisson rate estimate at this population size.
- **Previous season's contribution:** `opponent-no-prev` (0.1823) scores *better* overall than
  `opponent` (0.1833), and at or better than it in all three bands, not just early — the blend
  with 2024-25 rates does not earn its keep in this measurement; dropping the previous season did
  not hurt (as expected going into GW21-38) but also did not converge from behind the way the
  brief's "worse early, converging late" expectation predicted.

No script adaptation was needed: both seasons' CSVs (`gw2024-25.csv`, `gw2025-26.csv`,
`teams2024-25.csv`, `teams2025-26.csv`) carry the exact columns the script reads
(`team`, `opponent_team`, `was_home`, `fixture`, `minutes`, `expected_goals`,
`expected_goals_conceded`, `team_h_score`, `team_a_score`, `GW`) with the expected shapes
(`team` a club name, `opponent_team` an integer id, `was_home` "True"/"False", scores present on
every row since both seasons are fully completed). The only header differences between seasons are
columns the script does not use (2024-25's `mng_*` columns; 2025-26's added defensive-contribution
columns).

## Outcome

All seven tasks implemented, reviewed and committed on `kar-30-club-clean-sheet-projection`.
Full `./gradlew cleanTest test` on the local disposable container: 899 tests, 0 failures, 0 errors,
0 skipped.

Deviations from the plan as written:

- **Task 0.** The gate passed by 0.0006 Brier over 720 sides, and `opponent` was worse than
  `baseline` in GW3-10 and GW11-20, ahead only in GW21-38 — the opposite band from the one the
  plan expected. `opponent-no-prev` (this season only) scored better than the blend in every band.
  Ruling: ship as specified (whole-season rates on the shared ramp), report the no-prev result to
  the owner in `docs/STATUS.md` as an open question rather than tuning here.
- **Task 2.** Two `RankingMetrics.Snapshot` construction sites needed the new `clubRates()`
  component, not the one the plan named.
- **Task 3.** One existing test was given an equivalent λ rather than `OptionalDouble.empty()`,
  keeping its original assertion meaningful.
- **Task 4.** The tool descriptions took a comma the plan's wording did not have.
- **Task 5.** After review the FDR test was rewritten against an asymmetric `window(2)` and renamed
  `fdrScalesTheAttackingTermOnly`, so it fails if FDR ever scales a non-attacking term.
- **Final review.** Two fixes in one wave: `ClubRates.league` became public and the league pool is
  now taken from each season's raw, unbridged sides, so a relegated club still shapes `meanXg` as
  it did in the Task 0 measurement; and the pick side's two summations (the Score's
  `expected_per_fixture` and the printed `CS ... over N fixtures`) are pinned numerically against
  each other and against the printed legs, replacing a comment that described arithmetic no test
  performed.
