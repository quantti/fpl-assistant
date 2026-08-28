# Cross-season weighted blending — design

**Tickets:** KAR-38 (parent) with KAR-11 (pick tools), KAR-39 (squad builder), KAR-40
(`ExpectedStatsTools`).
**Status:** design, awaiting owner review. No code.
**Follows:** the defensive-contribution work in
`docs/superpowers/plans/2026-08-28-defcon-in-expected-points.md`, whose review produced these
tickets.

## The problem

Every ranking metric in the project comes from **one** season. `SeasonResolver.betterMetricSeason`
hands a caller the previous season until the season being played has `MIN_SAMPLE_GAMEWEEKS` (5)
completed gameweeks, then the season being played and nothing else. The switch is binary and
league-wide:

- In GW4 a player's own four matches count for nothing, however many minutes he has.
- In GW5 last season counts for nothing, and a four-match sample is a ranking.
- A player who has missed the first six rounds is judged, from GW5, on whatever he has — which
  may be one match — because the switch counts the league's gameweeks, not his.

`ExpectedStatsTools` is worse: it never consults the switch at all and answers from the resolved
season alone, so it shows a two-fixture sample as a season's evidence the day the resolver flips.

## What the owner decided (2026-08-28)

1. **Blend both seasons on a ramp, per player.** Weight on the season being played:

   | player's qualifying matches this season | weight on this season |
   |---|---|
   | 0 | 0% |
   | 1 | 10% |
   | 2 | 30% |
   | 3 | 60% |
   | 4 | 80% |
   | 5–7 | 90% |
   | 8+ | 100% |

2. **Counted on the player's own matches**, not on gameweeks played. A player who sat out three of
   the first four rounds does not get 80% on a single appearance.
3. **A player with no previous-season record is ranked on this season alone** once he clears the
   existing 180-minute floor, and the reply marks him as such. The ramp does not apply to him;
   there is nothing to ramp from.
4. **`ExpectedStatsTools` fills from last season rather than blending.** It shows fixtures as rows,
   and a row cannot be weighted. This season's rows first; when fewer than N exist, the remainder
   from last season, every row carrying its season.
5. **The blend lives in Java, in one class**, so the ramp is written once and unit-tested.

## Decisions made in this design

These were not put to the owner. Each is stated so it can be overruled.

- **A qualifying match is 60 minutes or more.** The clean-sheet and defensive-contribution terms
  already count matches that way, for the same reason: the underlying figures arrive with the
  minutes, and a 15-minute cameo is not a sample of anything.
- **The ramp counts all of the player's qualifying matches this season, not the windowed ones.**
  The rates are read over the last `WINDOW_MATCHES` (10); the ramp asks how much evidence exists,
  and a player with 12 matches has reached 100% even though only 10 feed the rate.
- **Blending applies only when the resolved season is the one being played.** During the
  changeover the resolver names a completed season; blending it with the one before would rank on
  two finished seasons. That case keeps today's behaviour: the resolved season, read whole.
- **A player who changed clubs keeps his previous club's numbers in the blend**, including
  `xGC`, which describes the old defence. The squad-builder spec already accepts this ("a player
  who changed clubs carries his previous club's minutes; that is the best signal available"), and
  the ramp pushes the old club out within a few matches.
- **The realised-points weight (`MAX_REALISED_WEIGHT`) is unchanged** and stays keyed on the
  season being played's completed gameweeks. It answers a different question — how much the
  points a player actually scored count against the expected model — and the two weightings
  stack: the ramp decides which season's rates feed `xPts/90`, the realised weight decides how
  much `Pts/90` counts beside it. `Pts/90` itself is blended like any other rate.
- **`SeasonResolver.betterMetricSeason` and `ExpectedPoints.MIN_SAMPLE_GAMEWEEKS` are removed.**
  They are the switch this replaces. `completedGameweeks` stays; the realised weight and the
  footer's "thin sample" note read it.
- **The weight is printed per pick.** Provenance in this project attributes every number, and the
  weight varies per player, so a single line cannot describe it. Each pick carries its own.

## Scope

**In:**

- `SeasonBlend` — new class in `tools/`, beside `ExpectedPoints`.
- `PickTools` — two-season metric query, blended ranking, per-pick weight, footer (KAR-11).
- `CandidatePool` + `SquadFormatter` — same for the squad builder (KAR-39).
- `ExpectedStatsTools` — fill from last season with attribution (KAR-40).
- `SeasonResolver` — remove `betterMetricSeason`; `previousSeason` and `completedGameweeks` stay.
- The system prompt's "Early in the season" section — it describes the switch that no longer
  exists and must describe the ramp instead.
- `AGENTS.md` — the pick-tools and squad-builder provenance bullets describe the metric block.
- Tests, `docs/STATUS.md`.

**Out:**

- `PlayerTools.getPlayerStats`. It already shows both seasons side by side under their own
  provenance lines and does not blend. That is deliberate and stays.
- Any change to the ramp's numbers. They are the owner's, and Task 0 of the plan measures them
  rather than tunes them.
- Shrinkage or a prior for the no-previous-season case. Decision 3 above.
- A stable player key across seasons. Neither tapas' `player` nor `fpl_daily_price` carries FPL's
  `code`; the name bridge stays, with its existing uniqueness guard.

## Design

### 1. `SeasonBlend`

```java
public final class SeasonBlend {

    /** Weight on the season being played, by the player's qualifying matches in it. */
    public static final double[] WEIGHT_BY_MATCHES = {0, 0.10, 0.30, 0.60, 0.80, 0.90, 0.90, 0.90, 1.0};

    /** Matches at and beyond which the season being played carries everything. */
    public static final int FULL_WEIGHT_MATCHES = 8;

    public record Blended(Map<String, Object> metrics, double weight, int matchesThisSeason,
                          boolean hasPrevious) {}

    public static double weight(int qualifyingMatchesThisSeason);

    /** Either row may be null. Returns null when neither side clears MIN_WINDOW_MINUTES combined. */
    public static Blended blend(Map<String, Object> current, Map<String, Object> previous);
}
```

`weight(n)` indexes the table, clamping at `FULL_WEIGHT_MATCHES`.

`blend` reads the same keys the metric queries already produce for one season — `mins`,
`matches`, `xg90`, `xa90`, `pts90`, `xgc_per_match`, `defcon_matches`, `defcon_avg`, and the squad
builder's `fixtures` — plus one new one, `matches60`, the player's count of 60-minute-or-more
matches across the whole season, which the ramp needs and the query must now supply. It returns a map with the same keys `ExpectedPoints.perNinety` reads today, so
`ExpectedPoints` and `HorizonScorer` do not change.

Per key:

| key | rule |
|---|---|
| `xg90`, `xa90`, `pts90`, `xgc_per_match`, `defcon_avg` | `w·cur + (1−w)·prev` when both sides carry a non-null value; the one side that does when only one does; null when neither. |
| `mins`, `matches`, `fixtures`, `defcon_matches` | `cur + prev`. These are evidence counts; the floors (`MIN_WINDOW_MINUTES`, `MIN_DEFCON_MATCHES`) read them, and evidence from either season counts. `fixtures` exists only in the squad builder's rows; a key absent on both sides is left out. |
| `matches60` | not blended: it is the ramp's input, read from `current` alone (the player's qualifying matches this season, whole season, not windowed) and reported as `blend_matches`. |
| `blend_weight`, `blend_matches`, `blend_has_previous` | new keys carrying `w`, `cur.matches60` and whether `previous` was non-null. Read by the renderers, ignored by `ExpectedPoints`. |

`w` is one number per player, from `cur.matches60` (0 when `current` is null). The
no-previous-season rule falls out of it: with `previous` null, every rate takes the current value
alone and `blend_has_previous` is false, which the renderer turns into the flag the owner asked
for. `w` itself is still reported from the table, so a new signing with two matches reads
"30% — no previous-season record", which is the honest description: the 30% is what the ramp
would give him, and the reply says why it could not.

`blend` returns null when the combined `mins` is under `MIN_WINDOW_MINUTES`. That is the one place
the rankable/body decision is made, for both consumers.

### 2. The two-season query

Both `PickTools.candidates` and `CandidatePool.load` run one query whose `recent` and
`windowed` CTEs take **two** season ids instead of one. The shape they already have carries over
with three changes:

- `recent` filters `pfs.season_id IN (?, ?)` and partitions its `ROW_NUMBER` by
  `(season_id, player_id)`.
- `windowed` limits rows per season by a `CASE`: `WINDOW_MATCHES` for the season being played,
  `WHOLE_SEASON` for the previous one. It groups by `(season_id, player_id)` and adds
  `count(*) FILTER (WHERE minutes >= 60) AS matches60`.
- `windowed` drops its `HAVING SUM(minutes) >= MIN_WINDOW_MINUTES`. The floor moves to
  `SeasonBlend.blend`, because it is now a floor on the *combined* record.

The two seasons attach to the pool row by different keys, as today, but now both at once:

- **season being played** → `pool.id = w.player_id` (element id; exact within one season).
- **previous season** → `LOWER(pool.web_name) = LOWER(mp.web_name)` through the `player` table of
  that season, with the existing `nameIsUnique` guard on both sides and `HAVING count(*) = 1`.
  Unchanged from the bridge that runs today whenever the metric season is not the pool's season.

So the query returns each pool row with two sets of metric columns, prefixed `cur_` and `prev_`,
either set null when that season holds nothing for him. Java splits them into two maps and hands
them to `SeasonBlend.blend`.

The pool itself is unchanged: `PickTools` reads `player` for the resolved season, `CandidatePool`
reads the daily capture.

**When the resolved season is not the one being played**, the previous branch is simply not
joined (`prev_*` all null, `matches60` from the resolved season) and the window is
`WHOLE_SEASON`. `blend` then returns the resolved season's rates at `w = 1`, and the renderers
print no weight — there is no blend to describe. This is today's changeover behaviour,
preserved without a separate code path.

### 3. `PickTools`

`MetricSeason` becomes `MetricSeasons(SeasonContext played, Optional<SeasonContext> previous,
int playedGameweeks, int latestGameweek)`. `metricSeason()` no longer consults
`betterMetricSeason`; it resolves `previousSeason(season)` when `season.currentSeason()` and
leaves it empty otherwise.

Each pick line gains a blend segment after the minutes:

```
DEF Ferreira (NMU) £4.5m | Own 8.0% | Score 4.85 | xPts/90 4.90 (…) | Pts/90 4.00
    | Mins 1620 (18 mt) | blend 30% 2026-27, 70% 2025-26 (2 mt this season) | Avg FDR 3.5
```

- `Mins` and `mt` are the combined figures the rates rest on.
- The blend segment names both seasons and the weight, and how many qualifying matches this
  season produced it. At `w = 1` with a previous record it reads `2026-27 only (9 mt this season)`.
- With no previous record: `2026-27 only — no 2025-26 record (2 mt this season)`.
- When the resolved season is not the one being played, the segment is omitted, as today.

The footer's first sentence changes from "Ranked by Score over each player's last 10 matches in
{season}" to name both seasons and state the ramp once:

> Ranked by Score over each player's last 10 matches in 2026-27, blended with all of 2025-26.
> The weight on 2026-27 rises with the player's own matches of an hour or more this season —
> 1: 10%, 2: 30%, 3: 60%, 4: 80%, 5–7: 90%, 8+: 100% — and each pick shows its own. A player with
> no 2025-26 record is ranked on 2026-27 alone and says so; treat him with the caution his match
> count deserves.

The realised-points sentence is unchanged. The existing "thin sample" note (fires below
`MIN_SAMPLE_GAMEWEEKS` played gameweeks) is replaced: the blend line already says how thin each
player's sample is, so the league-wide note is redundant and its constant goes.

Provenance: the banner is still `SeasonContext.provenance()` for the resolved season. The
previous season is named in the footer sentence above and on every pick line, which satisfies the
attribution rule without a second `Data source:` line — a second banner would claim every number
below it is last season's, and none of them purely is. `AGENTS.md`'s pick-tools bullet records
this.

### 4. `CandidatePool` and `SquadFormatter`

`MetricWindow` becomes the same `MetricSeasons` shape (or `CandidatePool` reuses the one from
`PickTools`, moved to `tools/`). `load` runs the two-season query and calls `SeasonBlend.blend`
per pool row; `rankable` is `blended != null`, which is the `MIN_WINDOW_MINUTES` floor on the
combined record. The metrics map stored on `SquadCandidate` is `blended.metrics()`, so
`HorizonScorer` reads exactly what it reads today, plus the three `blend_*` keys it ignores.

`SquadFormatter` adds one line under the metrics provenance line, naming both seasons and the
ramp in the same words as the pick footer, and appends the weight to each starter's line where it
is below 100%: `— 4.12 xPts (2026-27 30%)`. A player with no previous record: `(2026-27 only —
no 2025-26 record)`. The two-line provenance convention for this tool is unchanged: the
candidate pool's line is `DailyPriceSource.provenance()`, the metrics line is
`SeasonContext.provenance()`, and the blend sentence sits under the second.

### 5. `ExpectedStatsTools`

Both tools keep their query for the resolved season. When the resolved season is the one being
played, fewer than N rows come back, and `previousSeason` exists, they fetch the remainder from
it:

- `getPlayerExpectedStats` resolves the player in the previous season by `web_name`, only if
  that name is unique there (same `nameIsUnique` rule); otherwise no fill, and the reply says the
  name was ambiguous last season.
- `getTeamExpectedGoals` resolves the team in the previous season by `name`.

Each row is rendered with its season code as a prefix (`2025-26 GW36 (H) vs BHA …`), the rows are
in kickoff order newest first across both seasons, and the summary line reads `Expected stats for
Ferreira (last 10 fixtures: 2 from 2026-27, 8 from 2025-26)`. The aggregate figures below it are
over all rows shown, labelled as such. When any previous-season row is included, the reply
carries a second provenance line, `previousSeason.previousSeasonProvenance(true)`, under the
banner — the same two-line form `getPlayerStats` already uses when it shows last season beside
this one.

This is the one tool in the design that gets a second `Data source:` line, and it is correct
here: its rows are individually attributed, so the line describes a labelled block rather than
claiming the whole reply.

### 6. The system prompt

The "Early in the season: last season is the evidence" section currently instructs the model to
apply a GW5 rule by hand. Under this design the ranking tools apply the ramp themselves and print
the weight, so the section is rewritten to:

- say that the pick tools and the squad builder already blend and show the weight per player;
- tell the model to read the weight and phrase its confidence from it — "leans on last season"
  below 50%, "this season's numbers" at 90% and above;
- keep the rule for tools that do *not* blend (`getPlayerStats` shows both; `getPlayerExpectedStats`
  labels rows) — there the model still weighs by eye, and the existing minutes-check advice
  stands;
- drop the instruction to derive the gameweek from `getGameweekInfo` for this purpose. The weight
  on the card is per player and already correct; the gameweek is a worse proxy.

### 7. Failure modes and edge cases

| case | behaviour |
|---|---|
| Season being played has no `player_fixture_stats` at all (first days) | every `cur_*` is null, `w = 0`, ranking is last season's — exactly today's changeover result, but the reply now says "0 mt this season" per pick instead of switching wholesale. |
| Player in the pool, no record either season | `blend` returns null; `PickTools` drops him and the coverage line counts him, `CandidatePool` marks him `BODY`. As today. |
| Name ambiguous in the previous season | previous branch fails its `HAVING count(*) = 1`; he is ranked on this season alone with the no-previous flag. Today he is dropped outright when the metric season is last season; this is strictly better. |
| Player reclassified between seasons (DEF → MID) | scored at this season's position (`pool.element_type`); last season's rates are the same events at a different price. Accepted. |
| Previous season exists but is thinner than the current one (a fresh install with one backfilled gameweek) | the ramp still weights by the player's current matches; a thin previous season just contributes a noisy prior. `betterMetricSeason` used to guard this by comparing gameweek counts; the guard is dropped because the ramp already limits last season's influence to `1 − w`, and the case does not arise on the production database. |
| `WINDOW_MATCHES` window straddles a double gameweek | unchanged: `fixture_id` tie-break in the window ordering already handles it. |

### 8. Testing

- **`SeasonBlendTest` (unit):** the ramp table at every step and beyond it; blend of two full rows
  at 30%; one side null per key; combined-minutes floor; `blend_has_previous`; the no-previous
  row reproduces the current-season rates exactly.
- **`PickToolsCrossSeasonTest`:** extend the seeded new season with `player_fixture_stats` rows so
  a player has two 60+ minute matches; assert the pick line shows `blend 30%` and that his
  `xG/90` equals the hand-computed blend of the seeded values. Add: a player with one 45-minute
  match reads `0 mt this season` and last season's rates. The existing cases that assert the
  wholesale fallback keep passing at `w = 0` (no current rows), with their wording assertions
  updated where the footer sentence changed.
- **`CandidatePoolCrossSeasonTest`:** the same two-match seed; assert the candidate's metrics map
  carries `blend_weight = 0.30` and `xg90` equal to the hand-computed blend; the name-ambiguous
  case now reads as ranked-on-this-season rather than `BODY` when this season has a record.
- **`ExpectedStatsToolsIntegrationTest`:** a player with two current-season fixtures and eight
  previous-season ones, N=5: three rows carry the previous season's code, the summary counts
  `2 from … 3 from …`, the second provenance line is present. N=2: no previous rows, no second
  line.
- **`SeasonResolverTest`:** `betterMetricSeason` cases removed.
- **Measurement (plan Task 0, not a test).** The tool blends 2025-26 with 2026-27. Checking the
  ramp needs two *completed* seasons — a "last season" and a "this season" whose remainder is
  known — and on 2026-08-28 the season being played has one round, so that pair does not yet
  exist among the seasons the tool uses. The measurement therefore stands in the two most recent
  completed seasons, 2024-25 and 2025-26, fetched once from vaastav's public archive and never
  written to the database: predict the rest of a player's 2025-26 `xG/90` and `xA/90` from (all
  of 2024-25, first m matches of 2025-26) with the ramp, against this-season-only and
  last-season-only, for m = 1…8. Reported in `docs/STATUS.md`. The ramp is the owner's and is not
  tuned by this; the measurement says how much it buys and whether any step is clearly off.
  **Repeat it on the real pair, 2025-26 → 2026-27, once 2026-27 has ten completed gameweeks** —
  filed as a follow-up ticket in Task 7.

### 9. Documentation

- `docs/STATUS.md`: an entry recording the switch's removal, the ramp, the decisions above, the
  measurement, and the provenance reasoning in §3.
- `AGENTS.md`: the stats-tools provenance bullet gains the sentence that the pick tools' metric
  block names both seasons and prints a per-pick weight; the squad-builder bullet likewise. The
  `ExpectedStatsTools` two-line case is listed with the other documented two-line exceptions.
- The tool descriptions in `@Tool(...)` say the ranking blends the two seasons and shows the
  weight.

## Open question for the owner

None blocking. One worth a glance: at `w = 0.9` (5–7 matches) last season still carries 10% for
three rounds after a player has had five full matches. That is the owner's table and is kept as
given; if it was meant as a typo for "5+: 100%", say so and the table shrinks by a row.
