# Cross-Season Weighted Blending Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Progress is
> tracked by the single `**PROGRESS:**` line below, per `AGENTS.md` — do **not** add per-step
> checkboxes.

**PROGRESS:** Task 0/7 done — Task 0 is the measurement spike and does not count; Tasks 1–7 do.

**Goal:** Replace the binary previous-season/this-season switch in every ranking metric with a
per-player weighted blend of both seasons, on the owner's ramp, and make `ExpectedStatsTools`
fill from last season with attribution.

**Architecture:** One new class, `tools/SeasonBlend`, holds the ramp and merges two per-season
metric rows into the one row `ExpectedPoints.perNinety` already reads. The two metric queries
(`PickTools.candidates`, `CandidatePool.load`) return both seasons' aggregates side by side —
the season being played joined on element id, the previous season through the existing name
bridge — and hand them to `SeasonBlend`. `SeasonResolver.betterMetricSeason` and
`ExpectedPoints.MIN_SAMPLE_GAMEWEEKS` go away. `ExpectedPoints`, `HorizonScorer` and
`SquadSolver` do not change.

**Tech Stack:** Java 25, Spring Boot 4.1, `JdbcTemplate` on PostgreSQL, JUnit 5 + AssertJ
against the disposable container (`scripts/test-db.sh`).

**Spec:** `docs/superpowers/specs/2026-08-28-cross-season-blending-design.md`. Tickets KAR-38
(parent), KAR-11, KAR-39, KAR-40.

## Global Constraints

- **The ramp is the owner's: `{0, 0.10, 0.30, 0.60, 0.80, 0.90, 0.90, 0.90, 1.0}` by the player's
  qualifying matches this season, 8+ → 100%.** Task 0 measures it; nothing in this plan tunes it.
- **A qualifying match is 60 minutes or more.**
- **Blending applies only when the resolved season is the one being played.** Otherwise the
  resolved season is read whole, as today.
- **No previous-season record → this season alone once combined minutes ≥ `MIN_WINDOW_MINUTES`,
  flagged in the output.**
- **`ExpectedPoints`, `HorizonScorer`, `SquadSolver`, `Feasibility` do not change.** If a task
  seems to need it, the task is wrong.
- **The name bridge is the existing one:** `LOWER(web_name)`, unique in both seasons, `HAVING
  count(*) = 1`. No new key.
- **Provenance:** the pick tools keep the single `SeasonContext.provenance()` banner and name the
  previous season per pick and in the footer. The squad builder keeps its two lines and adds the
  blend sentence under the second. `ExpectedStatsTools` gets a second `Data source:` line only
  when previous-season rows are shown. `AGENTS.md` records all three (Task 7).
- **Tests seed their own rows, scoped by id, and clean up before and after.** `TapasSeed` never
  overwrites; the container is long-lived.
- Commits per `AGENTS.md`: one imperative subject, body only for a non-obvious *why*. Never push
  to `master`; branch is `cross-season-blending`, then a pull request.

## File Structure

| File | Responsibility | Change |
| --- | --- | --- |
| `backend/src/main/java/.../tools/SeasonBlend.java` | The ramp and the merge of two seasons' rows | **create** |
| `backend/src/main/java/.../tools/MetricSeasons.java` | Which seasons feed the metrics: the resolved one, the previous one if blending, gameweek counts | **create** (replaces `PickTools.MetricSeason` and `CandidatePool.MetricWindow`) |
| `backend/src/main/java/.../tools/SeasonResolver.java` | Season lookups | add `metricSeasons(SeasonContext)` and `latestGameweek(int)`; remove `betterMetricSeason` |
| `backend/src/main/java/.../tools/ExpectedPoints.java` | Scoring formula | remove `MIN_SAMPLE_GAMEWEEKS` only |
| `backend/src/main/java/.../tools/PickTools.java` | Pick ranking | two-season query, blend, per-pick weight, footer |
| `backend/src/main/java/.../squad/CandidatePool.java` | Squad candidate metrics | two-season query, blend |
| `backend/src/main/java/.../squad/SquadTools.java` | Squad tool entry | provenance wiring |
| `backend/src/main/java/.../squad/SquadFormatter.java` | Squad reply | blend sentence, per-line weight |
| `backend/src/main/java/.../tools/ExpectedStatsTools.java` | Per-fixture xG display | fill from previous season with attribution |
| `backend/src/main/resources/prompts/system-prompt.st` | Model instructions | rewrite the early-season section |
| `backend/src/test/java/.../tools/SeasonBlendTest.java` | Ramp and merge, unit | **create** |
| `backend/src/test/java/.../tools/PickToolsCrossSeasonTest.java` | Pick blend end to end | extend |
| `backend/src/test/java/.../squad/CandidatePoolCrossSeasonTest.java` | Squad blend end to end | extend |
| `backend/src/test/java/.../squad/CandidatePoolMetricWindowTest.java` | Tested the switch | **delete** |
| `backend/src/test/java/.../tools/ExpectedStatsToolsCrossSeasonTest.java` | Fill with attribution | **create** |
| `AGENTS.md`, `docs/STATUS.md` | Conventions, narrative | update |

Every task ends with the full suite green: `./gradlew test` from `backend/` with `.env.test`
loaded (`set -a; source .env.test; set +a`).

---

### Task 0: Measure what the ramp buys

Read-only. Produces numbers for `docs/STATUS.md`; changes nothing in code. The ramp is the
owner's, so a bad number here is information, not a reason to retune — but a step that is
clearly off is worth reporting before it ships.

**Which seasons, and why.** Today is 2026-08-28. The season being played is 2026-27, with one
completed gameweek, and the tool blends it with 2025-26. A measurement of the ramp needs a
"this season" whose remainder is already known, so it cannot be run on 2026-27 yet. It stands in
the two most recent *completed* seasons instead — 2024-25 as "last season", 2025-26 as "this
season" — from vaastav's public per-gameweek archive, downloaded once into the scratchpad.
**Nothing from 2024-25 is written to the database or checked into the repository**; the project
holds player statistics for 2025-26 and 2026-27 only, and that stays so. The same script is
run again on the real pair (2025-26 → 2026-27) once 2026-27 has ten completed gameweeks; Task 7
files that follow-up.

**Files:** a throwaway script in the scratchpad; the outcome recorded in this file.

**Step 1: Fetch vaastav's per-player, per-gameweek rows for both seasons.**

```bash
SP=/tmp/claude-1000/-home-vanka-dev-fpl-assistant/238aeabb-df74-4826-9e8c-bdd5ec51edd4/scratchpad
for s in 2024-25 2025-26; do
  curl -sL --max-time 90 -o "$SP/gw$s.csv" \
    "https://raw.githubusercontent.com/vaastav/Fantasy-Premier-League/master/data/$s/gws/merged_gw.csv"
  wc -l "$SP/gw$s.csv"
done
```

Expected: both files, tens of thousands of lines. Both carry `name`, `element`, `position`,
`minutes`, `expected_goals`, `expected_assists`, `expected_goals_conceded`, `GW`.

**Step 2: Run the measurement.** Bridge players across seasons by `name`, exactly as the
code will (unique in both seasons). For each player with a full 2024-25 and at least 12
qualifying 2025-26 matches, predict the rest of his 2025-26 `xg90` from his first `m` matches,
three ways, and score the error.

```python
# $SP/ramp_measure.py
import csv, math, sys
from collections import defaultdict

SP = sys.argv[1]
RAMP = [0, 0.10, 0.30, 0.60, 0.80, 0.90, 0.90, 0.90, 1.0]
def w(n): return RAMP[min(n, 8)]

def load(season):
    rows = defaultdict(list)          # name -> [(gw, minutes, xg, xa, xgc)]
    with open(f"{SP}/gw{season}.csv", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            rows[r["name"]].append((int(r["GW"]), int(r["minutes"]),
                                    float(r["expected_goals"]), float(r["expected_assists"]),
                                    float(r["expected_goals_conceded"])))
    return rows

def rate(matches, idx):               # per-90 over the given matches; None below 180 minutes
    mins = sum(m[1] for m in matches)
    return None if mins < 180 else sum(m[idx] for m in matches) * 90 / mins

prev, cur = load("2024-25"), load("2025-26")
names = [n for n in cur if n in prev]
print(f"bridged by name: {len(names)} players")

for label, idx in (("xG/90", 2), ("xA/90", 3)):
    print(f"\n{label}: RMSE of predicting rest-of-2025-26 from first m matches (60+ min)")
    print("  m   ramp   this-only  last-only   n")
    for m in range(1, 9):
        e_ramp = e_cur = e_prev = 0.0; n = 0
        for name in names:
            last = rate(prev[name], idx)
            q = sorted(x for x in cur[name] if x[1] >= 60)
            if last is None or len(q) < m + 8: continue
            head, tail = q[:m], q[m:]
            actual = rate(tail, idx)
            this = rate(head, idx)
            if actual is None: continue
            this_v = this if this is not None else last
            ramp = w(m) * this_v + (1 - w(m)) * last
            e_ramp += (ramp - actual) ** 2; e_cur += (this_v - actual) ** 2
            e_prev += (last - actual) ** 2; n += 1
        if n:
            print(f"  {m}   {math.sqrt(e_ramp/n):.4f}  {math.sqrt(e_cur/n):.4f}     "
                  f"{math.sqrt(e_prev/n):.4f}    {n}")
```

Run: `python3 $SP/ramp_measure.py $SP`

Expected shape: `ramp` at or below both baselines at every `m`; `this-only` worst at small `m`
and best by `m = 8`; `last-only` the reverse. A row where `ramp` is clearly worse than the better
baseline is the finding to report.

**Step 3: Record the table under `## Outcome — Task 0` in this file** and update
`**PROGRESS:**` to `Task 1/7 done`.

```bash
git add docs/superpowers/plans/2026-08-28-cross-season-blending.md
git commit -m "docs: measure the cross-season ramp against both single-season baselines"
```

---

### Task 1: `SeasonBlend` and `MetricSeasons`

**Files:**
- Create: `backend/src/main/java/fi/karivanttinen/fplassistant/tools/SeasonBlend.java`
- Create: `backend/src/main/java/fi/karivanttinen/fplassistant/tools/MetricSeasons.java`
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/tools/SeasonBlendTest.java`

**Interfaces:**
- Consumes: `ExpectedPoints.numeric`, `ExpectedPoints.MIN_WINDOW_MINUTES`.
- Produces:
  - `SeasonBlend.WEIGHT_BY_MATCHES`, `SeasonBlend.FULL_WEIGHT_MATCHES`
  - `static double SeasonBlend.weight(int qualifyingMatchesThisSeason)`
  - `record SeasonBlend.Blended(Map<String,Object> metrics, double weight, int matchesThisSeason, boolean hasPrevious)`
  - `static Blended SeasonBlend.blend(Map<String,Object> current, Map<String,Object> previous)` — either may be null; returns null below the minutes floor
  - `static Map<String,Object> SeasonBlend.side(Map<String,Object> row, String prefix)` — splits a `cur_`/`prev_` prefixed query row into one side's map, or null when that side has no `mins`
  - `SeasonBlend.CURRENT_PREFIX = "cur_"`, `SeasonBlend.PREVIOUS_PREFIX = "prev_"`
  - Output map keys: `xg90`, `xa90`, `pts90`, `xgc_per_match`, `defcon_avg`, `mins`, `matches`,
    `fixtures` (when supplied), `defcon_matches`, `blend_weight`, `blend_matches`,
    `blend_has_previous`
  - `record MetricSeasons(SeasonContext played, Optional<SeasonContext> previous, int playedGameweeks, int latestGameweek)` with `int windowLimit()` and `int previousSeasonId()` (−1 when empty)

**Step 1: Write the failing tests.**

```java
package fi.karivanttinen.fplassistant.tools;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The ramp and the merge, on their own. They live apart from the two tools that use them for
 * the reason {@link ExpectedPoints} does: a copy in each is a guarantee they diverge.
 */
class SeasonBlendTest {

    private static Map<String, Object> season(int mins, int matches60, double xg90, double xa90,
                                              Double xgc, Double defconAvg, int defconMatches) {
        Map<String, Object> row = new HashMap<>();
        row.put("mins", mins);
        row.put("matches", matches60);
        row.put("matches60", matches60);
        row.put("xg90", BigDecimal.valueOf(xg90));
        row.put("xa90", BigDecimal.valueOf(xa90));
        row.put("pts90", BigDecimal.valueOf(4.0));
        row.put("xgc_per_match", xgc == null ? null : BigDecimal.valueOf(xgc));
        row.put("defcon_avg", defconAvg == null ? null : BigDecimal.valueOf(defconAvg));
        row.put("defcon_matches", defconMatches);
        return row;
    }

    @Test
    void theRampIsTheOwnersTableAndSaturatesAtEight() {
        assertThat(SeasonBlend.weight(0)).isEqualTo(0.0);
        assertThat(SeasonBlend.weight(1)).isEqualTo(0.10);
        assertThat(SeasonBlend.weight(2)).isEqualTo(0.30);
        assertThat(SeasonBlend.weight(3)).isEqualTo(0.60);
        assertThat(SeasonBlend.weight(4)).isEqualTo(0.80);
        assertThat(SeasonBlend.weight(5)).isEqualTo(0.90);
        assertThat(SeasonBlend.weight(7)).isEqualTo(0.90);
        assertThat(SeasonBlend.weight(8)).isEqualTo(1.0);
        assertThat(SeasonBlend.weight(30)).isEqualTo(1.0);
        assertThat(SeasonBlend.weight(-1)).isEqualTo(0.0);
    }

    @Test
    void twoMatchesThisSeasonWeighsThisSeasonAtThirtyPercent() {
        Map<String, Object> cur = season(180, 2, 0.60, 0.20, 1.0, 9.0, 2);
        Map<String, Object> prev = season(2700, 30, 0.20, 0.10, 1.5, 6.0, 30);

        SeasonBlend.Blended b = SeasonBlend.blend(cur, prev);

        assertThat(b.weight()).isEqualTo(0.30);
        assertThat(b.matchesThisSeason()).isEqualTo(2);
        assertThat(b.hasPrevious()).isTrue();
        assertThat(ExpectedPoints.numeric(b.metrics().get("xg90")))
                .isCloseTo(0.3 * 0.60 + 0.7 * 0.20, within(1e-9));
        assertThat(ExpectedPoints.numeric(b.metrics().get("xgc_per_match")))
                .isCloseTo(0.3 * 1.0 + 0.7 * 1.5, within(1e-9));
        assertThat(ExpectedPoints.numeric(b.metrics().get("defcon_avg")))
                .isCloseTo(0.3 * 9.0 + 0.7 * 6.0, within(1e-9));
        // Evidence counts add: the floors read the combined record.
        assertThat(b.metrics().get("mins")).isEqualTo(2880.0);
        assertThat(b.metrics().get("defcon_matches")).isEqualTo(32.0);
        assertThat(b.metrics().get("blend_weight")).isEqualTo(0.30);
        assertThat(b.metrics().get("blend_matches")).isEqualTo(2);
        assertThat(b.metrics().get("blend_has_previous")).isEqualTo(true);
    }

    @Test
    void noMatchesThisSeasonIsLastSeasonAlone() {
        Map<String, Object> cur = season(45, 0, 2.0, 2.0, null, null, 0);   // one cameo
        Map<String, Object> prev = season(2700, 30, 0.20, 0.10, 1.5, 6.0, 30);

        SeasonBlend.Blended b = SeasonBlend.blend(cur, prev);

        assertThat(b.weight()).isEqualTo(0.0);
        assertThat(ExpectedPoints.numeric(b.metrics().get("xg90"))).isCloseTo(0.20, within(1e-9));
        assertThat(ExpectedPoints.numeric(b.metrics().get("xgc_per_match"))).isCloseTo(1.5, within(1e-9));
    }

    @Test
    void aMissingCurrentSideIsLastSeasonAlone() {
        SeasonBlend.Blended b = SeasonBlend.blend(null, season(2700, 30, 0.20, 0.10, 1.5, 6.0, 30));

        assertThat(b.weight()).isEqualTo(0.0);
        assertThat(b.matchesThisSeason()).isZero();
        assertThat(ExpectedPoints.numeric(b.metrics().get("xg90"))).isCloseTo(0.20, within(1e-9));
    }

    @Test
    void noPreviousRecordIsThisSeasonAloneButStillReportsTheRampWeight() {
        // The owner's rule: with nothing to ramp from, this season carries everything once the
        // minutes floor is cleared — and the reply says so, which needs hasPrevious=false and the
        // table weight the ramp would have given.
        SeasonBlend.Blended b = SeasonBlend.blend(season(180, 2, 0.60, 0.20, 1.0, 9.0, 2), null);

        assertThat(b.hasPrevious()).isFalse();
        assertThat(b.weight()).isEqualTo(0.30);
        assertThat(ExpectedPoints.numeric(b.metrics().get("xg90"))).isCloseTo(0.60, within(1e-9));
    }

    @Test
    void aKeyPresentOnOneSideOnlyIsTakenFromThatSide() {
        // xGC needs a 60-minute match; this season's two matches were both under an hour.
        Map<String, Object> cur = season(100, 0, 0.60, 0.20, null, null, 0);
        Map<String, Object> prev = season(2700, 30, 0.20, 0.10, 1.5, 6.0, 30);

        SeasonBlend.Blended b = SeasonBlend.blend(cur, prev);

        assertThat(ExpectedPoints.numeric(b.metrics().get("xgc_per_match"))).isCloseTo(1.5, within(1e-9));
    }

    @Test
    void theMinutesFloorIsOnTheCombinedRecord() {
        assertThat(SeasonBlend.blend(season(100, 1, 0.5, 0.5, 1.0, 8.0, 1), null)).isNull();
        assertThat(SeasonBlend.blend(season(100, 1, 0.5, 0.5, 1.0, 8.0, 1),
                season(100, 1, 0.5, 0.5, 1.0, 8.0, 1))).isNotNull();
        assertThat(SeasonBlend.blend(null, null)).isNull();
    }

    @Test
    void sideSplitsAPrefixedQueryRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("web_name", "Ferreira");
        row.put("cur_mins", 180);
        row.put("cur_xg90", BigDecimal.valueOf(0.4));
        row.put("prev_mins", null);
        row.put("prev_xg90", null);

        Map<String, Object> cur = SeasonBlend.side(row, SeasonBlend.CURRENT_PREFIX);
        assertThat(cur).containsEntry("mins", 180).containsEntry("xg90", BigDecimal.valueOf(0.4))
                .doesNotContainKey("web_name");
        assertThat(SeasonBlend.side(row, SeasonBlend.PREVIOUS_PREFIX)).isNull();
    }
}
```

**Step 2: Run them; expect a compile failure** (`cannot find symbol: SeasonBlend`).

```bash
./gradlew test --tests "*SeasonBlendTest"
```

**Step 3: Create `SeasonBlend.java`.**

```java
package fi.karivanttinen.fplassistant.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges a player's metrics from the season being played with his metrics from the previous
 * one, weighted by how much of this season he has actually played.
 *
 * <p>This replaces a switch. Until 2026-08-28 every ranking read <em>one</em> season: the
 * previous one until the season being played had five completed gameweeks, then that season
 * alone. Binary and league-wide, so in GW4 a player's own four matches counted for nothing and
 * in GW5 last season counted for nothing — and a player who had missed the opening rounds was
 * judged from GW5 on however little he had.
 *
 * <p>The ramp is the owner's (KAR-38), keyed on the <b>player's</b> qualifying matches — 60
 * minutes or more, the same bar the clean-sheet and defensive-contribution terms use — not on
 * the league's gameweeks. {@link #WEIGHT_BY_MATCHES} is the table; Task 0 of the plan measured
 * it against both single-season baselines rather than tuning it.
 *
 * <p>Rates blend; evidence counts add. {@code xg90} at two matches is 30% this season and 70%
 * last, but {@code mins} is both seasons' minutes together, because the floors that read it
 * ({@link ExpectedPoints#MIN_WINDOW_MINUTES}, {@link ExpectedPoints#MIN_DEFCON_MATCHES}) ask how
 * much evidence exists and evidence from either season counts.
 *
 * <p>A player with no previous record is ranked on this season alone once the floor is cleared,
 * and {@link Blended#hasPrevious()} is false so the reply can say so. His {@link Blended#weight()}
 * is still the table's value — the honest description is "30%, and here is why it could not be
 * applied", not a silent 100%.
 */
public final class SeasonBlend {

    /** Weight on the season being played, indexed by the player's qualifying matches in it. */
    public static final double[] WEIGHT_BY_MATCHES = {0, 0.10, 0.30, 0.60, 0.80, 0.90, 0.90, 0.90, 1.0};

    /** Matches at and beyond which the season being played carries everything. */
    public static final int FULL_WEIGHT_MATCHES = WEIGHT_BY_MATCHES.length - 1;

    public static final String CURRENT_PREFIX = "cur_";
    public static final String PREVIOUS_PREFIX = "prev_";

    /** Per-90 and per-match figures: weighted. */
    static final List<String> RATE_KEYS =
            List.of("xg90", "xa90", "pts90", "xgc_per_match", "defcon_avg");

    /** Evidence counts: added. {@code fixtures} exists only on the squad builder's rows. */
    static final List<String> COUNT_KEYS = List.of("mins", "matches", "fixtures", "defcon_matches");

    /** The ramp's input, read from the current side only. */
    static final String MATCHES_THIS_SEASON = "matches60";

    public record Blended(Map<String, Object> metrics, double weight, int matchesThisSeason,
                          boolean hasPrevious) {
    }

    private SeasonBlend() {
    }

    public static double weight(int qualifyingMatchesThisSeason) {
        int n = Math.max(0, Math.min(qualifyingMatchesThisSeason, FULL_WEIGHT_MATCHES));
        return WEIGHT_BY_MATCHES[n];
    }

    /**
     * @param current this season's aggregate for the player, or null when he has no row in it
     * @param previous last season's aggregate, or null when he has none there
     * @return the merged record, or null when the two together are under the minutes floor
     */
    public static Blended blend(Map<String, Object> current, Map<String, Object> previous) {
        if (current == null && previous == null) {
            return null;
        }
        int matches = current == null ? 0
                : (int) ExpectedPoints.numeric(current.get(MATCHES_THIS_SEASON));
        double tableWeight = weight(matches);
        double w = previous == null ? 1.0 : tableWeight;

        Map<String, Object> out = new HashMap<>();
        for (String key : RATE_KEYS) {
            Object v = rate(current, previous, key, w);
            if (v != null) {
                out.put(key, v);
            }
        }
        for (String key : COUNT_KEYS) {
            Object v = count(current, previous, key);
            if (v != null) {
                out.put(key, v);
            }
        }
        if (ExpectedPoints.numeric(out.get("mins")) < ExpectedPoints.MIN_WINDOW_MINUTES) {
            return null;
        }
        out.put("blend_weight", tableWeight);
        out.put("blend_matches", matches);
        out.put("blend_has_previous", previous != null);
        return new Blended(out, tableWeight, matches, previous != null);
    }

    /**
     * One side of a query row that carries both seasons under {@code cur_}/{@code prev_}
     * prefixes, or null when that side has no {@code mins} — the query's LEFT JOINs leave every
     * column of an absent season null.
     */
    public static Map<String, Object> side(Map<String, Object> row, String prefix) {
        if (row.get(prefix + "mins") == null) {
            return null;
        }
        Map<String, Object> side = new HashMap<>();
        for (String key : RATE_KEYS) {
            side.put(key, row.get(prefix + key));
        }
        for (String key : COUNT_KEYS) {
            side.put(key, row.get(prefix + key));
        }
        side.put(MATCHES_THIS_SEASON, row.get(prefix + MATCHES_THIS_SEASON));
        return side;
    }

    private static Object rate(Map<String, Object> current, Map<String, Object> previous,
                               String key, double w) {
        Object a = current == null ? null : current.get(key);
        Object b = previous == null ? null : previous.get(key);
        if (a instanceof Number x && b instanceof Number y) {
            return w * x.doubleValue() + (1 - w) * y.doubleValue();
        }
        return a instanceof Number ? a : b;
    }

    private static Object count(Map<String, Object> current, Map<String, Object> previous,
                                String key) {
        Object a = current == null ? null : current.get(key);
        Object b = previous == null ? null : previous.get(key);
        if (a == null && b == null) {
            return null;
        }
        return ExpectedPoints.numeric(a) + ExpectedPoints.numeric(b);
    }
}
```

Note `count` returns a `double` (`2880.0`), which the tests above expect. `PickTools` prints
`mins` and `matches` through `fixed(...)`-free `%s` today; Task 3 formats them as integers.

**Step 4: Create `MetricSeasons.java`.**

```java
package fi.karivanttinen.fplassistant.tools;

import java.util.Optional;

/**
 * Which seasons feed a ranking's metrics.
 *
 * <p>{@code played} is the resolved season and the one every pool row belongs to. {@code
 * previous} is present only when {@code played} is the season currently being played — that is
 * when a blend is meaningful. During the changeover the resolver names a completed season, and
 * blending it with the one before would rank on two finished seasons; then {@code previous} is
 * empty and {@code played} is read whole.
 *
 * <p>Replaces {@code PickTools.MetricSeason} and {@code CandidatePool.MetricWindow}, which each
 * carried a "which single season" decision that no longer exists.
 */
public record MetricSeasons(SeasonContext played, Optional<SeasonContext> previous,
                            int playedGameweeks, int latestGameweek) {

    /** Recent form for a season in progress, the whole season for a finished one. */
    public int windowLimit() {
        return played.currentSeason() ? ExpectedPoints.WINDOW_MATCHES : ExpectedPoints.WHOLE_SEASON;
    }

    /** A season id no row can carry, so a query's {@code IN (?, ?)} matches nothing extra. */
    public int previousSeasonId() {
        return previous.map(SeasonContext::seasonId).orElse(-1);
    }

    public boolean blending() {
        return previous.isPresent();
    }
}
```

**Step 5: Run the tests; expect green.**

```bash
./gradlew test --tests "*SeasonBlendTest"
```

**Step 6: Commit.**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/tools/SeasonBlend.java \
        backend/src/main/java/fi/karivanttinen/fplassistant/tools/MetricSeasons.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/tools/SeasonBlendTest.java
git commit -m "feat(metrics): blend both seasons on a per-player ramp"
```

Update `**PROGRESS:**` to `Task 2/7 done`.

---

### Task 2: `SeasonResolver.metricSeasons`

**Files:**
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/tools/SeasonResolver.java`

**Interfaces:**
- Consumes: `MetricSeasons` from Task 1; existing `previousSeason`, `completedGameweeks`.
- Produces: `public MetricSeasons metricSeasons(SeasonContext season)`,
  `public int latestGameweek(int seasonId)`.

`betterMetricSeason` stays for now — `PickTools` and `CandidatePool` still call it until Tasks 3
and 4 — and is deleted in Task 5.

**Step 1: Add the two methods** below `completedGameweeks`:

```java
    /**
     * The seasons a ranking's metrics come from: the resolved season, and the one before it when
     * the resolved season is being played. See {@link MetricSeasons}.
     */
    public MetricSeasons metricSeasons(SeasonContext season) {
        Optional<SeasonContext> previous = season.currentSeason()
                ? previousSeason(season) : Optional.empty();
        return new MetricSeasons(season, previous, completedGameweeks(season.seasonId()),
                latestGameweek(season.seasonId()));
    }

    /**
     * The newest gameweek the underlying data actually reaches. Printed so a stalled tapas sync
     * shows as visibly old data rather than ranking silently on weeks-old numbers.
     */
    public int latestGameweek(int seasonId) {
        Integer gw = jdbc.queryForObject(
                "SELECT COALESCE(max(gameweek), 0) FROM player_fixture_stats WHERE season_id = ?",
                Integer.class, seasonId);
        return gw == null ? 0 : gw;
    }
```

**Step 2: Compile and run the resolver's tests.**

```bash
./gradlew test --tests "*SeasonResolver*"
```

**Step 3: Commit.**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/tools/SeasonResolver.java
git commit -m "feat(metrics): resolve both metric seasons in one place"
```

Update `**PROGRESS:**` to `Task 3/7 done`.

---

### Task 3: `PickTools` ranks on the blend

**Files:**
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/tools/PickTools.java`
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/tools/PickToolsCrossSeasonTest.java`
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/tools/PickToolsIntegrationTest.java`
  (wording assertions only)

**Interfaces:**
- Consumes: `SeasonBlend`, `MetricSeasons`, `SeasonResolver.metricSeasons`.
- Produces: pick lines carrying a blend segment; the footer's new first sentence.

**Step 1: Extract the new-season seed into a shared helper, then write the failing test.**
`PickToolsCrossSeasonTest.beginTheNewSeason()` inserts the new season's gameweek 101, team 9001
and players 9401–9405 (Ferreira is 9403 there and 9205 in `TapasSeed`'s populated season), plus
last season's second Rask. Task 6 needs exactly the same seed for `ExpectedStatsTools`, so move
it rather than copy it a third time. Create
`backend/src/test/java/fi/karivanttinen/fplassistant/testsupport/NewSeasonSeed.java`:

```java
package fi.karivanttinen.fplassistant.testsupport;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The season being played, as it looks in the first week: a gameweek, two clubs, a handful of
 * players with new element ids for last season's names, and — new in the blending work — two
 * played fixtures so a player can have matches this season. Nothing else, so the early-season
 * path is exactly what the tools meet in August.
 *
 * <p>Shared by the pick-tools and expected-stats cross-season tests. {@code CandidatePool}'s
 * cross-season test seeds its own, smaller version under different ids (gameweek 111, players
 * 9421-9422) and is left alone.
 *
 * <p>Ids are scoped so {@link #end} can remove exactly these rows: the current season is
 * tapas-owned and shared with {@link TapasSeed}, and a blanket delete on {@code season_id}
 * would wipe another class's rows.
 */
public final class NewSeasonSeed {

    public static final int GAMEWEEK = 101;
    public static final int ASHFIELD = 9001;
    public static final int NORTHMOOR_CITY = 9002;
    public static final int FERREIRA = 9403;
    public static final int FIXTURE_HOME = 9111;
    public static final int FIXTURE_AWAY = 9112;

    private NewSeasonSeed() {
    }

    public static void begin(JdbcTemplate jdbc) {
        jdbc.execute("""
                INSERT INTO gameweek (id, season_id, name, deadline_time, finished, data_checked,
                                      is_current, is_next) VALUES
                    (101, 9027, 'Gameweek 1', TIMESTAMPTZ '2026-08-21 17:30:00+00', true, true,
                     true, false)
                ON CONFLICT (id, season_id) DO NOTHING
                """);
        jdbc.execute("""
                INSERT INTO team (id, season_id, code, name, short_name, strength) VALUES
                    (9001, 9027, 9001, 'Ashfield Rovers', 'ASH', 4),
                    (9002, 9027, 9002, 'Northmoor City', 'NMC', 3)
                ON CONFLICT (id, season_id) DO NOTHING
                """);
        jdbc.execute("""
                INSERT INTO fixture (id, season_id, gameweek, code, team_h, team_a,
                                     team_h_score, team_a_score, team_h_difficulty,
                                     team_a_difficulty, kickoff_time, started, finished) VALUES
                    (9111, 9027, 101, 9111, 9001, 9002, 1, 1, 2, 3,
                        TIMESTAMPTZ '2026-08-15 14:00:00+00', true, true),
                    (9112, 9027, 101, 9112, 9002, 9001, 0, 0, 3, 2,
                        TIMESTAMPTZ '2026-08-16 14:00:00+00', true, true)
                ON CONFLICT (id, season_id) DO NOTHING
                """);
        jdbc.execute("""
                INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                                    element_type, now_cost, status, total_points, minutes,
                                    goals_scored, assists, form, selected_by_percent) VALUES
                    (9401, 9027, 9001, 'Emeka', 'Okafor',   'Okafor',   3, 80, 'a', 12, 90, 1, 0, 6.0,  4.0),
                    (9402, 9027, 9001, 'Diego', 'Vasquez',  'Vasquez',  4, 96, 'a', 15, 90, 2, 0, 7.5,  6.0),
                    (9403, 9027, 9001, 'Tiago', 'Ferreira', 'Ferreira', 2, 45, 'a',  6, 90, 0, 0, 3.0,  5.0),
                    -- Shares a web_name with a player who exists in last season's data too, so
                    -- the name bridge has two candidates and no way to tell them apart.
                    (9404, 9027, 9001, 'Nils',  'Rask',     'Rask',     3, 55, 'a',  4, 90, 0, 0, 2.0,  3.0),
                    (9405, 9027, 9001, 'Otto',  'Rask',     'Rask',     2, 45, 'a',  3, 90, 0, 0, 1.5,  2.0)
                ON CONFLICT (id, season_id) DO NOTHING
                """);
        jdbc.execute("""
                INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                                    element_type, now_cost, status, total_points, minutes,
                                    goals_scored, assists, form, selected_by_percent) VALUES
                    (9210, 9026, 9001, 'Nils', 'Rask', 'Rask', 3, 55, 'a', 30, 270, 1, 1, 4.0, 3.0)
                ON CONFLICT (id, season_id) DO NOTHING
                """);
    }

    /** Ferreira's two full matches this season: 0.25 xG in each, so 0.25 xG/90. */
    public static void giveFerreiraTwoMatches(JdbcTemplate jdbc) {
        jdbc.execute("""
                INSERT INTO player_fixture_stats (fixture_id, player_id, season_id, gameweek,
                        player_team_id, opponent_team_id, was_home, kickoff_time, minutes,
                        total_points, goals_scored, assists, expected_goals, expected_assists,
                        expected_goal_involvements, clean_sheets, goals_conceded,
                        expected_goals_conceded, value) VALUES
                    (9111, 9403, 9027, 101, 9001, 9002, true,  TIMESTAMPTZ '2026-08-15 14:00:00+00', 90, 2, 0, 0, 0.25, 0.00, 0.25, 0, 1, 1.20, 45),
                    (9112, 9403, 9027, 101, 9001, 9002, false, TIMESTAMPTZ '2026-08-16 14:00:00+00', 90, 6, 0, 0, 0.25, 0.00, 0.25, 1, 0, 0.80, 45)
                ON CONFLICT (fixture_id, player_id, season_id) DO NOTHING
                """);
    }

    public static void end(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM player_fixture_stats WHERE season_id = 9027 AND fixture_id IN (9111, 9112)");
        jdbc.update("DELETE FROM fixture WHERE season_id = 9027 AND id IN (9111, 9112)");
        jdbc.update("DELETE FROM player WHERE season_id = 9027 AND id BETWEEN 9401 AND 9410");
        jdbc.update("DELETE FROM player WHERE season_id = 9026 AND id = 9210");
        jdbc.update("DELETE FROM gameweek WHERE season_id = 9027 AND id = 101");
        jdbc.update("DELETE FROM team WHERE season_id = 9027 AND id IN (9001, 9002)");
    }
}
```

Then in `PickToolsCrossSeasonTest`: delete its private `beginTheNewSeason()` and the body of
`endTheNewSeason()`, and call `NewSeasonSeed.begin(jdbc)` from `seed()` and
`NewSeasonSeed.end(jdbc)` from `endTheNewSeason()`. Run the class once; it must still pass —
the seed is the same rows plus one team and two fixtures nobody reads yet.

Now the cases:

```java
    /**
     * Two full matches this season is 30% on the owner's ramp. Ferreira's xG/90 last season is
     * 0.05 (0.05 + 0.05 over 180 minutes in TapasSeed); two matches at 0.25 here make 0.25 this
     * season; the blend is 0.3 × 0.25 + 0.7 × 0.05 = 0.11.
     */
    @Test
    void twoMatchesThisSeasonBlendAtThirtyPercentAndTheLineSaysSo() {
        NewSeasonSeed.giveFerreiraTwoMatches(jdbc);

        String result = tools.getDifferentialPicks("DEF", 100, 3);

        assertThat(result).contains("Ferreira");
        assertThat(result).contains("xG/90 0.11");
        assertThat(result).contains("blend 30% " + TapasSeed.CURRENT_SEASON_CODE + ", 70% "
                + TapasSeed.POPULATED_SEASON_CODE + " (2 mt this season)");
        assertThat(result).contains("Ranked by Score over each player's last 10 matches in "
                + TapasSeed.CURRENT_SEASON_CODE + ", blended with all of "
                + TapasSeed.POPULATED_SEASON_CODE);
    }

    @Test
    void aPlayerWithNoMatchesThisSeasonIsRankedOnLastSeasonAndTheLineSaysZero() {
        String result = tools.getDifferentialPicks("DEF", 100, 3);

        assertThat(result).contains("Ferreira");
        assertThat(result).contains("xG/90 0.05");
        assertThat(result).contains("blend 0% " + TapasSeed.CURRENT_SEASON_CODE + ", 100% "
                + TapasSeed.POPULATED_SEASON_CODE + " (0 mt this season)");
    }
```

**Step 2: Run; expect failure** on the blend segment (and, until Step 3, possibly on the
name-bridge path — the class's existing cases still pass because `w = 0` reproduces the old
fallback).

```bash
./gradlew test --tests "*PickToolsCrossSeasonTest"
```

**Step 3: Replace `MetricSeason` and `metricSeason()`.** Delete the private
`MetricSeason` record and the `metricSeason(SeasonContext)` and `latestGameweek(int)` methods.
Every use of `MetricSeason metric` becomes `MetricSeasons metric`, obtained as
`seasonResolver.metricSeasons(season)`. The two call sites in `getDifferentialPicks` and
`getCaptainCandidates` change from `MetricSeason metric = metricSeason(season);` to
`MetricSeasons metric = seasonResolver.metricSeasons(season);`.

Field reads map as:
- `metric.code()` → `metric.played().code()`
- `metric.beingPlayed()` → `metric.played().currentSeason()`
- `metric.seasonId()` → `metric.played().seasonId()`
- `metric.playedGameweeks()`, `metric.latestGameweek()` → unchanged
- `metric.isResolvedSeason()` → always true now; delete the branches that test it
- `metric.provenance()` → gone; delete the `if (metric.provenance() != null)` block in
  `formatPicks`

`windowLimit(MetricSeason)` → delete; use `metric.windowLimit()`.

**Step 4: Rewrite `candidates(...)`.**

```java
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
                           -- The ramp's input: every qualifying match this season, not just the
                           -- windowed ones. A player with 12 has reached 100% even though only
                           -- the last 10 feed his rates.
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
                           -- chance of crossing the threshold out of it, and the match count
                           -- says how much to believe the mean.
                           count(*) FILTER (WHERE minutes >= 60
                               AND defensive_contribution IS NOT NULL) AS defcon_matches,
                           AVG(defensive_contribution) FILTER (WHERE minutes >= 60)
                               AS defcon_avg
                    FROM recent
                    -- Recent form for the season being played, the whole of the previous one.
                    WHERE rn <= CASE WHEN season_id = ? THEN %d ELSE %d END
                    GROUP BY season_id, player_id
                ),
                prev AS (
                    -- Last season's row attached by name: element ids are reassigned every
                    -- summer and tapas keeps no stable code. A name that is not unique in that
                    -- season is dropped rather than guessed.
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
                       pv.matches60 AS prev_matches60, pv.mins AS prev_mins, pv.matches AS prev_matches,
                       pv.xg90 AS prev_xg90, pv.xa90 AS prev_xa90, pv.pts90 AS prev_pts90,
                       pv.xgc_per_match AS prev_xgc_per_match,
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
```

Placeholders in order: `%s` position filter, `%d` window limit, `%d` whole season, `%s`
`nameIsUnique("mp")`, `%s` `nameIsUnique("pool")`. Positional arguments: pool season, ownership,
the two `IN` ids, the `CASE` id, the `prev` season id. Read both back against the template.

Delete the now-unused `metricKey`/`poolKey`/`metricUnique`/`poolUnique` locals and the comment
above them.

**Step 5: The pick line.** In `formatPicks`, change the format string and arguments:

```java
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
```

and add:

```java
    /**
     * Which season each pick's numbers come from and at what weight. Per pick, because the
     * weight is per player: provenance in this project attributes every number, and one line
     * cannot describe a mix that differs on every row.
     */
    private static String blendSegment(Map<String, Object> row, MetricSeasons metric) {
        if (!metric.blending()) {
            return "";
        }
        String cur = metric.played().code();
        String prev = metric.previous().orElseThrow().code();
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
```

**Step 6: The footer.** Replace the `span` computation and the two attribution branches:

```java
        String span = metric.blending()
                ? "each player's last " + ExpectedPoints.WINDOW_MATCHES + " matches in "
                        + metric.played().code() + ", blended with all of "
                        + metric.previous().orElseThrow().code()
                : metric.played().currentSeason()
                        ? "each player's last " + ExpectedPoints.WINDOW_MATCHES + " matches in "
                                + metric.played().code()
                        : "all of " + metric.played().code();
```

Keep the realised-points sentence, but its condition is now `metric.played().currentSeason()`
rather than `metric.beingPlayed()`. Delete the `if (!metric.isResolvedSeason())` branch and the
`else if (metric.playedGameweeks() < ExpectedPoints.MIN_SAMPLE_GAMEWEEKS)` branch; keep the
`!currentSeason()` "not under way in the data yet" branch. Then, immediately after the "both
kinds of defender" sentence, add:

```java
        if (metric.blending()) {
            String cur = metric.played().code();
            String prev = metric.previous().orElseThrow().code();
            out.append("The weight on ").append(cur).append(" rises with each player's own")
                    .append(" matches of an hour or more this season — 1: 10%, 2: 30%, 3: 60%,")
                    .append(" 4: 80%, 5–7: 90%, 8+: 100% — and each pick shows its own. Below")
                    .append(" 50% the judgement leans on ").append(prev)
                    .append(" and you should say so. A player marked \"no ").append(prev)
                    .append(" record\" is ranked on ").append(cur)
                    .append(" alone; treat him with the caution his match count deserves.\n");
        }
```

The "most recent gameweek" sentence reads `metric.played().code()`.

**Step 7: Update the `@Tool` descriptions.** In both, replace `The reply says which season
the numbers cover.` (differentials) / `the reply says which season the numbers cover` (captains)
with: `Numbers blend this season with last on a per-player weight that rises with his matches
this season; each pick shows its weight.`

**Step 8: Rewrite the three existing cases whose wording the footer no longer carries.** In
`PickToolsCrossSeasonTest`, exactly these; the other two cases in the class pass unchanged.

`theMetricsCarryTheirOwnProvenanceLineAndNotJustTheBanner` asserted a second `Data source:`
line, "The underlying numbers are …" and "never call them this season's form". All three
sentences are gone by design (spec §3). Replace the whole method:

```java
    /**
     * The previous season is named on every pick and in the footer, not on a second banner — a
     * second banner would claim every number below it is last season's, and none purely is.
     */
    @Test
    void theFooterAndEachPickNameThePreviousSeasonAndItsWeight() {
        String result = tools.getDifferentialPicks("any", 100, 3);

        assertThat(result).contains("Data source: season " + TapasSeed.CURRENT_SEASON_CODE);
        assertThat(result).doesNotContain("Data source: season " + TapasSeed.POPULATED_SEASON_CODE);
        assertThat(result).contains("The weight on " + TapasSeed.CURRENT_SEASON_CODE + " rises");
        assertThat(result).contains("blend 0% " + TapasSeed.CURRENT_SEASON_CODE + ", 100% "
                + TapasSeed.POPULATED_SEASON_CODE + " (0 mt this season)");
    }
```

`aFinishedSeasonIsReadWholeRatherThanByItsRunIn` asserted `"Ranked by Score over all of "` and
`doesNotContain("last 10 matches")`. The new sentence contains "last 10 matches" (for the season
being played) *and* "all of" (for the previous one), so the second assertion inverts. Replace the
two assertions with:

```java
        assertThat(result).contains("last 10 matches in " + TapasSeed.CURRENT_SEASON_CODE
                + ", blended with all of " + TapasSeed.POPULATED_SEASON_CODE);
```

`lastSeasonsRealisedPointsAreWeightedLowForTheStatedReason` asserted "deliberately low",
"change between seasons" and `doesNotContain("as the sample grows")`. The realised weight is now
keyed on the season being played's completed gameweeks — 0 here, so 0% — and the "rising towards"
clause prints because that season is the one being played. Replace the three assertions with:

```java
        assertThat(result).contains("Realised points carry 0% of the weight here, rising towards");
```

and rename the method `realisedPointsCarryNothingUntilThisSeasonHasCompletedGameweeks`.

**Step 9: Run the pick tests, then the whole suite.**

```bash
./gradlew test --tests "*PickTools*"
./gradlew test
```

Expected: green. `CandidatePoolMetricWindowTest` still passes — `betterMetricSeason` still
exists until Task 5.

**Step 10: Commit.**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/tools/PickTools.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/tools/PickToolsCrossSeasonTest.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/tools/PickToolsIntegrationTest.java
git commit -m "feat(picks): rank on both seasons blended per player"
```

Update `**PROGRESS:**` to `Task 4/7 done`.

---

### Task 4: `CandidatePool` and the squad reply

**Files:**
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/squad/CandidatePool.java`
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/squad/SquadTools.java`
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/squad/SquadFormatter.java`
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/squad/SquadCandidate.java` (javadoc)
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/squad/CandidatePoolCrossSeasonTest.java`

**Interfaces:**
- Consumes: `SeasonBlend`, `MetricSeasons`, `SeasonResolver.metricSeasons`.
- Produces: `CandidatePool.load(Capture, MetricSeasons)`; `SquadFormatter.format(...)` gains a
  trailing `String blendNote` parameter (null when not blending).

**Step 1: Write the failing test.** `CandidatePoolCrossSeasonTest` seeds its own, smaller new
season — gameweek **111**, team 9001, and `BRIDGED = 9421`, a current-season "Okafor" whose
last-season counterpart is `TapasSeed`'s Okafor (9201: three 90-minute matches, xG 0.55 + 0.45
+ 0.25 = 1.25 over 270 minutes, so 0.4167 xG/90). It does not seed Ferreira and uses different
ids from `NewSeasonSeed`, so it is extended in place. In its `beginTheNewSeason()`, add after the
team insert:

```java
        jdbc.execute("""
                INSERT INTO team (id, season_id, code, name, short_name, strength) VALUES
                    (9002, 9027, 9002, 'Northmoor City', 'NMC', 3)
                ON CONFLICT (id, season_id) DO NOTHING
                """);
        jdbc.execute("""
                INSERT INTO fixture (id, season_id, gameweek, code, team_h, team_a,
                                     team_h_score, team_a_score, team_h_difficulty,
                                     team_a_difficulty, kickoff_time, started, finished) VALUES
                    (9121, 9027, 111, 9121, 9001, 9002, 1, 1, 2, 3,
                        TIMESTAMPTZ '2026-08-15 14:00:00+00', true, true),
                    (9122, 9027, 111, 9122, 9002, 9001, 0, 0, 3, 2,
                        TIMESTAMPTZ '2026-08-16 14:00:00+00', true, true)
                ON CONFLICT (id, season_id) DO NOTHING
                """);
```

and in `cleanUp()`, before the existing deletes:

```java
        jdbc.update("DELETE FROM player_fixture_stats WHERE season_id = ? AND fixture_id IN (9121, 9122)", CURRENT);
        jdbc.update("DELETE FROM fixture WHERE season_id = ? AND id IN (9121, 9122)", CURRENT);
        jdbc.update("DELETE FROM team WHERE season_id = ? AND id = 9002", CURRENT);
```

(Fixture ids 9121/9122 rather than `NewSeasonSeed`'s 9111/9112, so the two classes never touch
each other's rows.) Then the case:

```java
    @Test
    void twoMatchesThisSeasonBlendAtThirtyPercentInTheMetricsMap() {
        jdbc.execute("""
                INSERT INTO player_fixture_stats (fixture_id, player_id, season_id, gameweek,
                        player_team_id, opponent_team_id, was_home, kickoff_time, minutes,
                        total_points, goals_scored, assists, expected_goals, expected_assists,
                        expected_goal_involvements, clean_sheets, goals_conceded,
                        expected_goals_conceded, value) VALUES
                    (9121, 9421, 9027, 111, 9001, 9002, true,  TIMESTAMPTZ '2026-08-15 14:00:00+00', 90, 2, 0, 0, 0.25, 0.00, 0.25, 0, 1, 1.20, 80),
                    (9122, 9421, 9027, 111, 9001, 9002, false, TIMESTAMPTZ '2026-08-16 14:00:00+00', 90, 6, 0, 0, 0.25, 0.00, 0.25, 1, 0, 0.80, 80)
                ON CONFLICT (fixture_id, player_id, season_id) DO NOTHING
                """);

        SquadCandidate okafor = load().stream()
                .filter(c -> c.elementId() == BRIDGED).findFirst().orElseThrow();

        assertThat(okafor.tier()).isEqualTo(SquadCandidate.Tier.RANKABLE);
        assertThat(okafor.metrics().get("blend_weight")).isEqualTo(0.30);
        assertThat(ExpectedPoints.numeric(okafor.metrics().get("xg90")))
                .isCloseTo(0.3 * 0.25 + 0.7 * (1.25 * 90 / 270), within(1e-9));
        // Both seasons' minutes feed expected minutes per fixture: 180 now, 270 last season.
        assertThat(okafor.minutesInWindow()).isEqualTo(450.0);
    }
```

Import `fi.karivanttinen.fplassistant.tools.ExpectedPoints` and `static ...Assertions.within`.
The class's `load()` helper calls `pool.load(capture, pool.metricWindow(resolved))` today; Step 3
changes it to `pool.load(capture, seasonResolver.metricSeasons(resolved))`.

**Every caller of the old shapes, so none is missed when Step 3 renames them:**

| file | line | change |
| --- | --- | --- |
| `squad/SquadTools.java` | 95–96 | `MetricSeasons metric = seasonResolver.metricSeasons(season); … pool.load(capture, metric)` |
| `squad/SquadTools.java` | 181 | `SquadFormatter.format(…, blendNote)` (Step 6) |
| `test/…/squad/CandidatePoolIntegrationTest.java` | 208, 273 | `pool.metricWindow(x)` → `seasonResolver.metricSeasons(x)` (the class already autowires `seasonResolver`) |
| `test/…/squad/CandidatePoolCrossSeasonTest.java` | 176 | same |
| `test/…/squad/SquadFormatterTest.java` | 70, 282, 295 | append `null` as the new last argument to `SquadFormatter.format(…)` |
| `test/…/squad/SquadToolsCrossSeasonTest.java` | 234–245 | rewrite `theProvenanceLineNamesThePreviousSeasonNotTheThinCurrentOne` — see Step 6 |
| `test/…/squad/CandidatePoolMetricWindowTest.java` | whole file | delete (Step 8) |

**Step 2: Run; expect failure** (`metricWindow` still returns a single season; no
`blend_weight` key).

**Step 3: Replace `MetricWindow` and `metricWindow()`.** Delete the `MetricWindow` record
and `metricWindow(SeasonContext)`. `load` takes `MetricSeasons`:

```java
    public List<SquadCandidate> load(Capture capture, MetricSeasons metric) {
```

`SquadTools` obtains it with `seasonResolver.metricSeasons(resolved)` where it called
`pool.metricWindow(resolved)`.

**Step 4: Rewrite the query in `load`.** Same three changes as `PickTools`, on this class's
shape. The `pool` CTE is unchanged. `recent` gains `pfs.season_id`, the two-season `IN`, the
`(season_id, player_id)` partition and the `matches60` window count. `windowed` gains
`season_id` in its select and group-by, `min(matches60) AS matches60`, and the `CASE` window
limit; it **loses** its `HAVING SUM(minutes) >= %d`. `club_fixtures` selects `w.season_id` alongside
`w.player_id` and filters `f.season_id = w.season_id` instead of a bound parameter, so it holds
one row per (season, player). The `metrics` CTE becomes `prev`, keyed by name with the unique
guard, selecting `min(cf.fixtures) AS fixtures` alongside the others. The final select LEFT JOINs
`windowed c` on `c.season_id = ? AND c.player_id = pool.element_id` (bound to the played season
id — the daily table carries a season *code*, not an id), then `club_fixtures cc` on
`cc.season_id = c.season_id AND cc.player_id = c.player_id`, and `prev pv` on
`pv.k = LOWER(pool.web_name) AND pool.name_count = 1`. It projects every metric under `cur_` and
`prev_`, with `cc.fixtures AS cur_fixtures` and `pv.fixtures AS prev_fixtures`.

Bound arguments in order: `capture.season(), capture.date()` (pool), `played, previousSeasonId`
(`recent` IN), `played` (CASE), `previousSeasonId` (`prev` WHERE), `played` (the `c` join).
Format placeholders: `dailyTable`, `metric.windowLimit()`, `ExpectedPoints.WHOLE_SEASON`,
`nameIsUnique("mp")`.

**Step 5: Blend in the row loop.**

```java
        List<SquadCandidate> candidates = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            SeasonBlend.Blended blended = SeasonBlend.blend(
                    SeasonBlend.side(row, SeasonBlend.CURRENT_PREFIX),
                    SeasonBlend.side(row, SeasonBlend.PREVIOUS_PREFIX));
            String status = (String) row.get("status");
            if (blended == null && !"a".equals(status)) {
                continue;
            }
            candidates.add(toCandidate(row, blended));
        }
        return candidates;
```

and `toCandidate`:

```java
    private static SquadCandidate toCandidate(Map<String, Object> row, SeasonBlend.Blended blended) {
        boolean rankable = blended != null;
        Map<String, Object> metrics = new HashMap<>();
        if (rankable) {
            metrics.putAll(blended.metrics());
            metrics.put("element_type", row.get("element_type"));
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
                rankable ? asDouble(metrics.get("mins")) : 0,
                rankable ? asInt(metrics.get("fixtures")) : 0,
                metrics,
                rankable ? SquadCandidate.Tier.RANKABLE : SquadCandidate.Tier.BODY);
    }
```

Update `SquadCandidate`'s javadoc key list to name `blend_weight`, `blend_matches` and
`blend_has_previous` as carried for the reply and ignored by `ExpectedPoints`.

**Step 6: `SquadTools` provenance.** Replace the `metricProvenance` block with:

```java
            String metricProvenance = season.provenance();
            String blendNote = metric.blending()
                    ? "Underlying numbers blend " + metric.played().code() + " with "
                            + metric.previous().orElseThrow().code() + " per player: the weight"
                            + " on " + metric.played().code() + " rises with his own matches of"
                            + " an hour or more this season — 1: 10%, 2: 30%, 3: 60%, 4: 80%,"
                            + " 5–7: 90%, 8+: 100% — and each player below shows his where it"
                            + " is under 100%. \"no " + metric.previous().orElseThrow().code()
                            + " record\" means he is judged on " + metric.played().code()
                            + " alone."
                    : null;
```

and pass `blendNote` as the last argument of `SquadFormatter.format`. Delete the comment above
the old block; its reasoning no longer applies.

`SquadToolsCrossSeasonTest.theProvenanceLineNamesThePreviousSeasonNotTheThinCurrentOne` pinned
that old block: it expects the metrics line to name the previous season as `COMPLETED PREVIOUS`.
Under blending the metrics line is the season being played's own banner and the previous season
is named in the blend sentence, so replace the method:

```java
    /**
     * The metrics line names the season being played — it is the season every candidate
     * belongs to — and the sentence under it says what last season contributes and how. The
     * current, thin season must still never be called a completed one.
     */
    @Test
    void theMetricsLineNamesThisSeasonAndTheBlendSentenceNamesLast() {
        String output = tools.buildSquad("", "", "", 100.0, 5, "");

        assertThat(output).contains("Starting XI");
        assertThat(output).contains("Data source: season " + TapasSeed.CURRENT_SEASON_CODE
                + " (the season currently being played)");
        assertThat(output).contains("Underlying numbers blend " + TapasSeed.CURRENT_SEASON_CODE
                + " with " + TapasSeed.POPULATED_SEASON_CODE);
        assertThat(output).doesNotContain("Data source: season " + TapasSeed.CURRENT_SEASON_CODE
                + " — a COMPLETED PREVIOUS season");
    }
```

**Step 7: `SquadFormatter`.** Add `String blendNote` as the final parameter of `format`;
after the two provenance lines, `if (blendNote != null) out.append(blendNote).append("\n");`
before the blank line. In `line(...)`, after the xPts figure for a rankable player:

```java
            Object hasPrevious = player.metrics().get("blend_has_previous");
            if (hasPrevious != null) {
                int pct = (int) Math.round(ExpectedPoints.numeric(player.metrics().get("blend_weight")) * 100);
                if (!Boolean.TRUE.equals(hasPrevious)) {
                    line.append(" (this season only — no previous record)");
                } else if (pct < 100) {
                    line.append(" (this season ").append(pct).append("%)");
                }
            }
```

`metrics()` carries no `blend_*` keys when not blending, so nothing prints then.

**Step 8: Run the squad tests, then the suite.**

```bash
./gradlew test --tests "*Squad*" --tests "*CandidatePool*"
./gradlew test
```

`CandidatePoolMetricWindowTest` fails to compile now (`metricWindow` is gone). Delete it in this
task — its subject, the switch, no longer exists — and note in the commit body that Task 5
removes the resolver method it tested.

**Step 9: Commit.**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/squad/ \
        backend/src/test/java/fi/karivanttinen/fplassistant/squad/
git commit -m "feat(squad): rank candidates on both seasons blended per player"
```

Update `**PROGRESS:**` to `Task 5/7 done`.

---

### Task 5: Remove the switch

**Files:**
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/tools/SeasonResolver.java`
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/tools/ExpectedPoints.java`

**Step 1:** Delete `SeasonResolver.betterMetricSeason` and its javadoc. Delete
`ExpectedPoints.MIN_SAMPLE_GAMEWEEKS` and its javadoc. In `PickTools`' class javadoc, replace the
paragraph beginning "The metrics come from whichever season has a real sample" with:

```java
 * <p>The metrics blend the season being played with the previous one, per player, on the ramp
 * {@link SeasonBlend} holds: a player's own matches this season decide how much of his record is
 * this season's. Across the season boundary players are bridged by name — {@code player.id} is
 * FPL's element id and is reassigned every season, and tapas keeps no stable player code. Names
 * that are not unique in either season lose the previous-season side rather than being guessed
 * — a wrong record attached to a recommendation is the failure this class exists to prevent.
```

Remove the comment block in `PickTools` that says the window constants "live on ExpectedPoints
now" if it names `MIN_SAMPLE_GAMEWEEKS`.

**Step 2:**

```bash
grep -rn "betterMetricSeason\|MIN_SAMPLE_GAMEWEEKS\|MetricWindow\|MetricSeason\b" backend/src
```

Expected: no output.

**Step 3: Run the suite; commit.**

```bash
./gradlew test
git add backend/src/main/java/fi/karivanttinen/fplassistant/tools/
git commit -m "refactor(metrics): drop the single-season switch the blend replaces"
```

Update `**PROGRESS:**` to `Task 6/7 done`.

---

### Task 6: `ExpectedStatsTools` fills from last season

**Files:**
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/tools/ExpectedStatsTools.java`
- Create: `backend/src/test/java/fi/karivanttinen/fplassistant/tools/ExpectedStatsToolsCrossSeasonTest.java`

**Interfaces:**
- Consumes: `SeasonResolver.previousSeason`, `SeasonContext.previousSeasonProvenance(true)`.
  The uniqueness rule is applied in Java here (`ids.size() != 1`), not through
  `PickTools.nameIsUnique`; nothing moves.
- Produces: rows tagged with `season_code`; summary line counts per season; second provenance
  line when any previous-season row is shown.

**Step 1: Write the failing test.** The class seeds through the helper Task 3 extracted:

```java
    @Autowired
    ExpectedStatsTools tools;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        TapasSeed.ensure(jdbc);
        NewSeasonSeed.begin(jdbc);
        NewSeasonSeed.giveFerreiraTwoMatches(jdbc);
    }

    @AfterEach
    void endTheNewSeason() {
        NewSeasonSeed.end(jdbc);
    }
```

and the cases:

```java
    @Test
    void fewerCurrentFixturesThanAskedForAreFilledFromLastSeasonAndLabelled() {
        String result = tools.getPlayerExpectedStats("Ferreira", 5);

        assertThat(result).contains("Data source: season " + TapasSeed.CURRENT_SEASON_CODE
                + " (the season currently being played)");
        assertThat(result).contains("Data source: season " + TapasSeed.POPULATED_SEASON_CODE
                + " — a COMPLETED PREVIOUS season");
        assertThat(result).contains("last 4 fixtures: 2 from " + TapasSeed.CURRENT_SEASON_CODE
                + ", 2 from " + TapasSeed.POPULATED_SEASON_CODE);
        assertThat(result).contains("  " + TapasSeed.CURRENT_SEASON_CODE + " GW101");
        assertThat(result).contains("  " + TapasSeed.POPULATED_SEASON_CODE + " GW2");
    }

    @Test
    void enoughCurrentFixturesMeansNoFillAndNoSecondProvenanceLine() {
        String result = tools.getPlayerExpectedStats("Ferreira", 2);

        assertThat(result).doesNotContain("COMPLETED PREVIOUS");
        assertThat(result).contains("last 2 fixtures:");
        assertThat(result).doesNotContain("from " + TapasSeed.POPULATED_SEASON_CODE);
    }
```

(Ferreira has two rows in the populated season, fixtures 9102 and 9104, so N=5 yields 2 + 2.)

**Step 2: Run; expect failure.**

**Step 3: Implement the fill in `getPlayerExpectedStats`.** After `rows` is fetched, tag
each with the season and fill:

```java
            for (Map<String, Object> r : rows) {
                r.put("season_code", season.code());
            }
            int fromPrevious = 0;
            Optional<SeasonContext> previous = Optional.empty();
            if (season.currentSeason() && rows.size() < limit) {
                previous = seasonResolver.previousSeason(season);
                if (previous.isPresent()) {
                    List<Map<String, Object>> older = previousSeasonPlayerRows(
                            previous.get(), webName, limit - rows.size());
                    fromPrevious = older.size();
                    rows.addAll(older);
                }
            }
```

with:

```java
    /**
     * Last season's fixtures for the same name, only when that name is unique there — the same
     * bridge the ranking tools use, for the same reason: element ids are reassigned every
     * summer and a guessed match attaches another player's matches.
     */
    private List<Map<String, Object>> previousSeasonPlayerRows(SeasonContext previous,
                                                               String webName, int limit) {
        List<Integer> ids = jdbc.queryForList("""
                SELECT id FROM player WHERE season_id = ? AND LOWER(web_name) = LOWER(?)
                """, Integer.class, previous.seasonId(), webName);
        if (ids.size() != 1) {
            return List.of();
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT pfs.gameweek, pfs.was_home, pfs.minutes,
                       pfs.goals_scored, pfs.assists,
                       pfs.expected_goals, pfs.expected_assists, pfs.expected_goal_involvements,
                       opp.short_name AS opponent
                FROM player_fixture_stats pfs
                LEFT JOIN team opp ON opp.id = pfs.opponent_team_id AND opp.season_id = pfs.season_id
                WHERE pfs.season_id = ? AND pfs.player_id = ?
                ORDER BY pfs.kickoff_time DESC NULLS LAST
                LIMIT ?
                """, previous.seasonId(), ids.getFirst(), limit);
        for (Map<String, Object> r : rows) {
            r.put("season_code", previous.code());
        }
        return rows;
    }
```

The return becomes:

```java
            String header = season.provenance()
                    + (fromPrevious > 0 ? "\n" + previous.orElseThrow().previousSeasonProvenance(true) : "");
            return header + "\n\n" + formatPlayer(webName, rows, season.code(), fromPrevious) + ambiguity;
```

`formatPlayer` gains `(String currentCode, int fromPrevious)`: its heading becomes
`"Expected stats for %s (last %d fixtures%s):\n"` where the suffix is
`": %d from %s, %d from %s"` when `fromPrevious > 0` (previous code read from the last row's
`season_code`) and empty otherwise; each row line is prefixed with `r.get("season_code") + " "`.
The totals line is unchanged in shape; its label becomes "Totals over the fixtures shown".

**Step 4: The same for `getTeamExpectedGoals`**, with `previousSeasonTeamRows(previous,
name, limit)` resolving `SELECT id FROM team WHERE season_id = ? AND LOWER(name) = LOWER(?)`,
and `formatTeam` gaining the same two parameters and the same row prefix. Add a team test in the
same class: the new season's team 9001 (Ashfield) has two seeded fixtures; ask N=4 and expect
`2 from` each.

**Step 5: Update both `@Tool` descriptions.** Replace `in the current season` with `this
season, filled from last season when this season has fewer than N — every row names its season`.

**Step 6: Run the tests, then the suite; commit.**

```bash
./gradlew test --tests "*ExpectedStats*"
./gradlew test
git add backend/src/main/java/fi/karivanttinen/fplassistant/tools/ \
        backend/src/test/java/fi/karivanttinen/fplassistant/tools/ExpectedStatsToolsCrossSeasonTest.java
git commit -m "feat(xg): fill a short current-season window from last season, labelled"
```

Update `**PROGRESS:**` to `Task 7/7 done` after Task 7.

---

### Task 7: Prompt, conventions, narrative, pull request

**Files:**
- Modify: `backend/src/main/resources/prompts/system-prompt.st`
- Modify: `AGENTS.md`
- Modify: `docs/STATUS.md`

**Step 1: Rewrite the prompt section** "Early in the season: last season is the evidence"
to:

```
## Early in the season: the tools already weigh last season in
getDifferentialPicks, getCaptainCandidates and buildSquad blend this season's numbers with last
season's per player, on a weight that rises with the player's own matches of an hour or more
this season (1: 10%, 2: 30%, 3: 60%, 4: 80%, 5–7: 90%, 8+: 100%). Each pick shows its weight.
Read it and phrase your confidence from it:
- Under 50%: the judgement leans on last season — say so ("this leans on his 2025/26 numbers").
- 90% and above: this season's numbers; last season is a footnote.
- "no previous record": he is judged on this season alone, and the match count beside it is
  the whole sample. One to four matches are never the basis of a firm recommendation.
Tools that do not blend still need you to weigh by eye: getPlayerStats shows both seasons under
their own Data source lines, and getPlayerExpectedStats labels every fixture row with its
season. There, check the minutes before treating a number as form, and lean on last season
until a player has a real sample this season.
```

and delete the "Never guess which gameweek it is … apply the rule to" paragraph that follows it,
which existed only to derive GW5. Keep the "Attribute every number to its season" sentence.

**Step 2: `AGENTS.md`.** In the stats-tools provenance bullet, after the sentence about the
dual-season player card, add: "The two ranking tools (`getDifferentialPicks`,
`getCaptainCandidates`) blend both seasons per player and carry one banner: the previous season
is named per pick and in the footer, not on a second `Data source:` line, because a second banner
would claim every number below it is last season's and none purely is." In the `buildSquad`
bullet, add that the metrics line is followed by the blend sentence. Add `ExpectedStatsTools` to
the list of documented two-line cases: "second line only when previous-season rows are shown,
and those rows are labelled individually."

**Step 3: `docs/STATUS.md`** entry: the switch and why it was wrong; the ramp and that it is
the owner's; the decisions in the spec's "Decisions made in this design"; Task 0's table; the
provenance reasoning; the no-previous-record rule; that `ExpectedPoints` and `HorizonScorer`
did not change and why that matters; KAR-38/11/39/40 closed.

**Step 4: Full suite from a reset container, then push and PR.**

```bash
scripts/test-db.sh reset
cd backend && set -a; source .env.test; set +a
./gradlew cleanTest test
cd .. && git add -A && git commit -m "docs: record cross-season blending and update the prompt"
git push -u origin cross-season-blending
gh pr create --title "Blend both seasons per player in every ranking metric" --body "$(cat <<'BODY'
Every ranking metric read one season, switching from last to this at five completed gameweeks
league-wide. Now each player's rates blend both on the owner's ramp — his own matches of an hour
or more this season decide the weight (1: 10% … 8+: 100%) — and each pick shows it. A player
with no previous record is ranked on this season alone and says so. ExpectedStatsTools fills a
short window from last season with every row labelled.

One new class holds the ramp; ExpectedPoints and HorizonScorer are untouched. Measured against
both single-season baselines on the two most recent completed seasons, 2024-25 → 2025-26, as a
stand-in until 2026-27 has enough rounds to measure the real pair (see STATUS).

Verified: full suite green against a freshly reset container.
Plan: docs/superpowers/plans/2026-08-28-cross-season-blending.md
Closes KAR-38, KAR-11, KAR-39, KAR-40.
BODY
)"
```

Set KAR-38 and its children to In Review. File one follow-up in Linear: *"Re-measure the
cross-season ramp on 2025-26 → 2026-27 after GW10"*, pointing at Task 0's script and table, so
the stand-in measurement is replaced by the real one. Update `**PROGRESS:**` to `Task 7/7 done`.

---

## Notes for whoever executes this

- **The suite must stay green after every task.** Tasks 3 and 4 each leave the other tool on
  the old switch; that is fine, the switch is removed only in Task 5 once nothing calls it.
- **Read the `.formatted(...)` and positional arguments back against the template** in Tasks 3
  and 4. A misordered id compiles and blends the wrong season.
- **Test seeds are scoped by id and cleaned before and after.** The cross-season classes share
  `TapasSeed`'s populated season and the new season's ids 9401–9410; the fixture ids 9111/9112
  and team 9002 are new to this plan and must be deleted in every class that inserts them.
- **The blend is not tuned here.** If Task 0 shows a step that is clearly off, report it in the
  plan outcome and in the pull request; do not change `WEIGHT_BY_MATCHES`.
