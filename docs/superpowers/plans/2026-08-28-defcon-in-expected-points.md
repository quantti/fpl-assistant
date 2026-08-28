# Defensive Contribution in xPts/90 — Implementation Plan

> **For agentic workers:** use `superpowers:subagent-driven-development` or
> `superpowers:executing-plans` to run this task-by-task. Progress is tracked by the single
> `**PROGRESS:**` line below, per `AGENTS.md` — do **not** add per-step checkboxes.

**PROGRESS:** Task 6/6 done

> **The task bodies below describe a model that was replaced before merge.** Tasks 2–5 specify a
> hit rate (`defconHitRate`, `defcon_hits_def`/`defcon_hits_mid`); what shipped is a
> negative-binomial probability read from the mean (`defconProbability`, `defcon_avg`). See
> "Outcome — the model changed after review" for why. The tasks are kept as written because the
> plan is a record of what was planned, not of what exists — `docs/STATUS.md` is the latter.

**Goal:** Put defensive-contribution points into `ExpectedPoints.perNinety`, so the pick tools and
the squad builder stop understating ball-winning defenders and holding midfielders.

**Architecture:** The data is already in the database — `player_fixture_stats` carries
`defensive_contribution` per player per fixture for 2026-27 (live sync) and 2025-26 (backfilled).
Defcon pays 2 points at a **per-match threshold**, so the useful figure is the share of matches a
player crossed it in, not his average: a defender steady on 9.8 actions scores nothing, one
averaging 9.5 with a wide spread scores often. Both metric queries (`PickTools.metricRows`,
`CandidatePool`) already aggregate a fixed window into one row per player; this adds a hit count
and a match count to that aggregate, and `ExpectedPoints` turns them into points. The term is
dropped, never estimated, when the columns are unpopulated — which is what keeps every existing
test expectation unchanged.

**Tech Stack:** Java 25, Spring Boot 4.1, `JdbcTemplate` against Supabase PostgreSQL, JUnit 5 +
AssertJ against a disposable container (`scripts/test-db.sh`).

**Requirements source:** Linear [KAR-29](https://linear.app/quantti/issue/KAR-29) (migrated from
GitHub #46). There is no separate spec document; the issue and this plan's "Design decisions"
section are it. Background: `docs/STATUS.md` — "pick tools, 2026-08-26" and the defcon capture
entries.

## Outcome — Task 1 (2026-08-28)

No Postgres client is installed on this machine (`psql` absent, no `psycopg2`/`pg8000`), so the
column was verified from the two sources that bracket it instead, which answer the gate question
outright.

**The column holds actions, not awarded points.** `DefensiveStatsSyncService` writes
`asInt(row.get("defensive_contribution"))` from the `element-summary` payload with no
transformation, and the live 2026-27 API has Egan on **21** after one gameweek. No points figure
can exceed 2. The vaastav 2025-26 fixture in `src/test/resources` shows the same shape (values of
18-21), so the backfilled season agrees.

**Goalkeepers are excluded.** All 20 keepers with 60+ minutes read exactly 0.

**Base rates, live 2026-27 after GW1** (players with 60+ minutes):

| Position | n | avg actions | max | threshold | hit rate |
| --- | --- | --- | --- | --- | --- |
| GK | 20 | 0.00 | 0 | — | — |
| DEF | 85 | 7.19 | 21 | 10 | 22.4% |
| MID | 86 | 7.15 | 16 | 12 | 14.0% |
| FWD | 19 | 3.68 | 8 | 12 | 0.0% |

These corroborate `docs/STATUS.md` exactly: 22.4% of 85 is 19 defenders, 14.0% of 86 is 12
midfielders, which is what the 2026-08-26 production run reported. Read a defender's `DC` figure
against ~22% and a midfielder's against ~14%.

## Outcome — execution (2026-08-28)

Done on branch `defcon-expected-points`, four commits, 569 tests green against a freshly reset
container. Three deviations from the plan as written, all deliberate:

1. **Tasks 3 and 4 were committed together.** The plan ended Task 3 with a commit while its own
   test was still red, waiting on Task 4's rendering. Committing a red suite is what the pre-push
   hook exists to prevent, so the SQL and the rendering landed as one commit.
2. **Task 1 was answered without a database client.** Neither `psql` nor a Python driver is
   installed here. The gate question was settled from the two sources that bracket the column
   instead — see the Task 1 outcome above — which is stronger than the planned query on the point
   that mattered, and weaker only on the base rates, which came from the live API instead.
3. **A test the plan did not ask for was added.** Nothing pinned that a *forward* gets a DC
   component, which is the exact bug the `components()` restructure exists to prevent — a forward
   has no clean-sheet value but does have a threshold. Also pinned the rendered form
   (`DC 50% at 8.0 actions`) rather than just the rate.

## Outcome — the model changed after review (2026-08-28)

The plan's central design decision was wrong and was replaced before merge.

It specified a hit rate, `hits / qualifying matches`, and argued that a mean cannot express a
per-match threshold. The owner rejected that: what a ranking needs is how often a player *will*
cross the threshold, and the historical average is the input to that rather than a rival to it.
The argument in "Design decisions" attacked a mean used linearly, which nobody proposed.

Replaced with a negative-binomial tail read from the player's mean actions per match. Measured on
2025-26 (60+ minute matches): within-player variance is 1.34x the mean (so Poisson is wrong and
the negative binomial right), and predicting the rest of a player's season from his first few
matches the distribution's error is 28% lower than the hit rate's at 2 matches, 24% at 3, 20% at
5 and 7% at 10. The constant in code is 1.6, above the measured figure on purpose — see
`DEFCON_DISPERSION`'s javadoc. A first measurement of 1.60 came from data without minutes and
was inflated by cameos; the second review caught that.

Two consequences for the plan as written:

- `defcon_hits_def` and `defcon_hits_mid` are gone. The query carries `defcon_matches` and
  `defcon_avg` only, which also removes the two interpolated `%d` thresholds and with them the
  argument-order hazard the review flagged.
- The `MIN_DEFCON_MATCHES` debate, which took most of the review, was a symptom of the weaker
  estimator rather than a question worth that much attention.

## Global Constraints

- **The capture half is done. This plan writes no sync code and touches no sync class.**
  `DefensiveStatsSchema`, `DefensiveStatsSyncService`, `DefensiveStatsBackfillService` and
  `DefensiveStatsRunner` are finished and must not be modified.
- **`player_fixture_stats` is tapas-owned.** This plan only ever reads it. The four defcon columns
  are the documented exception in `AGENTS.md` and are already added; do not add more.
- **The columns are nullable with no default.** `NULL` means "never fetched" and must stay
  distinct from `0`, which means "did nothing". Any SQL that treats `NULL` as `0` is wrong.
- **`ExpectedPoints` is shared** by `PickTools` and the squad builder's `CandidatePool`. Both must
  supply the new keys, or the two tools rank the same player two different ways in one
  conversation — the exact failure the class was extracted to prevent.
- **Provenance conventions are unchanged.** The pick tools keep `SeasonContext.provenance()`; the
  squad builder keeps its two-line form. Do not touch either.
- Commit style per `AGENTS.md`: one imperative subject line, ~60 chars, body only when the *why*
  is not obvious.
- Never push to `master`. Branch, then pull request.
- **The scoring rules below are the 2026/27 ones, verified 2026-08-28.** Thresholds and points are
  unchanged from 2025/26 — `~/dev/tapas-fpl-app/backend/FPL_RULES.md` (updated 2026-08-13 for the
  season transition) lists defensive contribution under "Unchanged from 2025/26", and the live
  `bootstrap-static` agrees: after GW1, 0 of the 20 goalkeepers who played have any defensive
  contribution, while defenders top out at 21 and forwards at 8. What *did* change for 2026/27 is
  when the numbers become final — see "Provisional until the day after" below.

## Design decisions

**Hit rate, not average.** `defcon_hits / defcon_matches × 2`. The issue's own argument: a mean
cannot say how often a player crossed a per-match threshold. The distribution is in the database,
so read it directly.

**Counted over matches of 60 minutes or more**, the same discipline the clean-sheet term already
uses. Defensive actions arrive with the minutes; a 20-minute cameo counted as a match the player
"failed to contribute in" would drag a nailed starter's rate down for being substituted.

**A floor of 2 such matches**, matching the 180-minute (two-match) floor the window already admits
a player on. No component is held to a stricter standard than the row itself. Below it the term
reads `DC n/a` and the player is understated, which the reply says.

**Rejected: shrinking the rate towards a prior.** A Beta prior (or a Poisson tail from the
player's average actions) would soften a 2-for-2 record. It adds a constant nobody can defend
without a measurement, or eight lines of distribution maths, to fix a case the two-match floor
already bounds — and `xG/90` from the same two matches is no less noisy and is used as-is.

**Rejected: reading `defensive_contribution_per_90` from `bootstrap-static`.** A season mean,
which is the figure this design exists to avoid, and it cannot be backfilled.

**No double counting with `Pts/90`.** Realised points already contain defcon points a player
earned. That is the same relationship goals have — `xG` on the expected side, actual goals inside
`total_points` on the realised side — and the Score blends the two at a weight, it does not add
them. No change to the blend.

**Goalkeepers get no term.** FPL awards them no defensive contribution; the 2026-08-26 production
run read 0 for every keeper, and the live 2026/27 API still reads 0 for all 20 who have played.
Save points and penalty saves remain unmodelled, and every disclosure must keep saying so.

**Provisional until the day after — new for 2026/27.** Gameweek scores now become final at 09:00
UK on the day after the gameweek's last match, rather than an hour after the final whistle, and
FPL states the reason is to let post-match Opta review feed into BPS *and defensive contribution
points*. So a `defensive_contribution` read before that hour can still move.

A hit rate is more exposed to this than an average would be: a review that lifts a defender from
9 actions to 10 flips a whole match from miss to hit, moving his rate by a tenth of the window.

No code is needed for it, and this is why: `DefensiveStatsSyncService` walks the entire `history`
list for every player on each run and UPDATEs every match row, so the 03:00 run on the day after
lockdown rewrites any provisional figure with the reviewed one. The exposure is bounded to the
roughly 27 hours between a gameweek's last whistle and the next night's sync, and it heals itself.
Say so in the STATUS entry (Task 6); do not build a re-fetch, and do not exclude the newest
gameweek from the window to dodge it.

**The 2026/27 BPS changes are not this plan's business.** Clearances, blocks and interceptions now
earn 1 BPS per 3 rather than per 2, and being tackled is no longer -1. Those move *bonus* points,
which arrive inside `total_points` and therefore inside `Pts/90` — the realised half of the Score,
which needs no change. They do not touch the 2 points a threshold pays.

## File Structure

| File | Responsibility | Change |
| --- | --- | --- |
| `backend/src/main/java/fi/karivanttinen/fplassistant/tools/ExpectedPoints.java` | The scoring formula, shared by both tools | Add thresholds, points, floor, `defconHitRate`, `defconPerNinety`, `hasDefconSignal`; call it from `perNinety` |
| `backend/src/main/java/fi/karivanttinen/fplassistant/tools/PickTools.java` | Pick ranking + rendering | Add four columns to the metric query; render the DC component; rewrite the footer disclosure |
| `backend/src/main/java/fi/karivanttinen/fplassistant/squad/CandidatePool.java` | Squad-builder candidate metrics | Add the same four columns and carry them into the metrics map |
| `backend/src/main/java/fi/karivanttinen/fplassistant/squad/SquadFormatter.java` | Squad reply text | Rewrite the disclosure line |
| `backend/src/test/java/.../tools/ExpectedPointsTest.java` | The formula, unit | New defcon cases |
| `backend/src/test/java/.../tools/PickToolsIntegrationTest.java` | The query end to end | New case: seeded defcon reaches the reply |
| `backend/src/test/java/.../squad/CandidatePoolIntegrationTest.java` | The squad query end to end | New case: the metrics map carries the keys |
| `docs/STATUS.md` | The narrative | New entry |

---

### Task 1: Confirm what `defensive_contribution` holds

The whole model rests on the column being a **count of defensive actions**, not the 0-or-2 points
already awarded.

Two sources already say actions, and both were checked on 2026-08-28: `docs/STATUS.md` reports
starters averaging 7.2, and the live `bootstrap-static` has Egan on 21 after one gameweek, which
no points figure could be. Confirm it against the database anyway — that is where the query in
Task 3 will read from, and a column can hold something different from the payload it was filled
from. **This task writes no code and gates the rest of the plan.**

**Files:** none — a read-only query against production, plus a note recorded in the plan outcome.

1. Load the production environment and open a session:

```bash
cd /home/vanka/dev/fpl-assistant/backend
set -a; source .env.supabase; set +a
```

2. Run this against the backfilled 2025-26 season, which has a full season of rows:

```sql
SELECT p.element_type,
       count(*)                                                   AS rows,
       round(avg(pfs.defensive_contribution), 2)                   AS avg_value,
       max(pfs.defensive_contribution)                             AS max_value,
       count(*) FILTER (WHERE pfs.defensive_contribution IN (0, 2)) AS zero_or_two
FROM player_fixture_stats pfs
JOIN player p ON p.id = pfs.player_id AND p.season_id = pfs.season_id
JOIN season s ON s.id = pfs.season_id
WHERE s.name = '2025-26'
  AND pfs.minutes >= 60
  AND pfs.defensive_contribution IS NOT NULL
GROUP BY p.element_type
ORDER BY p.element_type;
```

3. Read the result:

- **Actions (expected):** `max_value` well above 2 (teens for defenders), `avg_value` around 7 for
  element types 2 and 3, `zero_or_two` a small minority. Proceed to Task 2 unchanged.
- **Points (the model breaks):** `max_value` of exactly 2 and `zero_or_two` equal to `rows`. Stop.
  The hit rate is then `count(*) FILTER (WHERE defensive_contribution = 2) / count(*)` and the
  thresholds in Task 2 are not needed at all. Record the finding and re-plan Tasks 2-3 before
  writing anything.

4. Confirm goalkeepers are still excluded, which is what makes `DEFCON_THRESHOLD[1] = 0` right
   rather than merely convenient:

```sql
SELECT count(*) FILTER (WHERE pfs.defensive_contribution > 0) AS keepers_with_actions
FROM player_fixture_stats pfs
JOIN player p ON p.id = pfs.player_id AND p.season_id = pfs.season_id
JOIN season s ON s.id = pfs.season_id
WHERE s.name = '2025-26' AND p.element_type = 1 AND pfs.defensive_contribution IS NOT NULL;
```

Expected: `0`. Anything else means FPL awards keepers defensive contribution after all, and
`DEFCON_THRESHOLD[1]` needs a real value before Task 2.

5. Also record the per-position hit rate, which is the sanity check every later assertion is read
   against:

```sql
SELECT p.element_type,
       round(100.0 * count(*) FILTER (
           WHERE pfs.defensive_contribution >= CASE WHEN p.element_type = 2 THEN 10 ELSE 12 END
       ) / count(*), 1) AS hit_rate_percent
FROM player_fixture_stats pfs
JOIN player p ON p.id = pfs.player_id AND p.season_id = pfs.season_id
JOIN season s ON s.id = pfs.season_id
WHERE s.name = '2025-26' AND pfs.minutes >= 60 AND pfs.defensive_contribution IS NOT NULL
GROUP BY p.element_type ORDER BY p.element_type;
```

6. Write all three results into this file under a `## Outcome — Task 1` heading, and update
   `**PROGRESS:**` to `Task 1/6 done`. No commit is required for a read-only task; if the
   `Outcome` note is added, commit it:

```bash
git add docs/superpowers/plans/2026-08-28-defcon-in-expected-points.md
git commit -m "docs: record what the defensive-contribution column holds"
```

---

### Task 2: The formula

**Files:**
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/tools/ExpectedPoints.java`
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/tools/ExpectedPointsTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `public static final int[] DEFCON_THRESHOLD` — indexed by `element_type`
  - `public static final double DEFCON_POINTS`
  - `public static final int MIN_DEFCON_MATCHES`
  - `public static int defconThreshold(int position)`
  - `public static double defconHitRate(Map<String, Object> row, int position)`
  - `public static double defconPerNinety(Map<String, Object> row, int position)`
  - `public static boolean hasDefconSignal(Map<String, Object> row, int position)`
  - Row keys read: `defcon_matches`, `defcon_hits_def`, `defcon_hits_mid`, `defcon_avg`

Nothing supplies those keys yet, so after this task `perNinety` behaves exactly as before on every
existing row. That is the point: the suite must stay green with no expectation edited.

**Step 1: Write the failing tests.** Append to `ExpectedPointsTest.java`:

```java
    private static Map<String, Object> defensiveRow(int elementType, int matches, int hits) {
        Map<String, Object> row = row(elementType, 0, 0, null);
        row.put("defcon_matches", matches);
        row.put("defcon_hits_def", hits);
        row.put("defcon_hits_mid", hits);
        row.put("defcon_avg", BigDecimal.valueOf(9.0));
        return row;
    }

    @Test
    void clearingTheThresholdHalfTheTimeIsWorthHalfThePoints() {
        double half = ExpectedPoints.perNinety(defensiveRow(2, 10, 5));
        double never = ExpectedPoints.perNinety(defensiveRow(2, 10, 0));

        assertThat(half - never).isCloseTo(0.5 * ExpectedPoints.DEFCON_POINTS, within(1e-9));
    }

    @Test
    void aMidfielderIsJudgedOnAHigherThresholdThanADefender() {
        assertThat(ExpectedPoints.defconThreshold(2)).isEqualTo(10);
        assertThat(ExpectedPoints.defconThreshold(3)).isEqualTo(12);
        assertThat(ExpectedPoints.defconThreshold(4)).isEqualTo(12);
    }

    @Test
    void aGoalkeeperEarnsNoDefensiveContribution() {
        // FPL awards it to outfield players only — the 2026-08-26 production run read 0 for
        // every keeper. A keeper with hits must still score nothing from this term.
        assertThat(ExpectedPoints.perNinety(defensiveRow(1, 10, 10)))
                .isEqualTo(ExpectedPoints.perNinety(defensiveRow(1, 10, 0)));
    }

    @Test
    void aRecordShorterThanTheWindowAdmitsIsNotReadAsARate() {
        // One match at 100% is not a certainty worth a flat two points, and it would top every
        // list. The floor is the two matches the minutes floor already admits a player on.
        Map<String, Object> oneForOne = defensiveRow(2, 1, 1);

        assertThat(ExpectedPoints.hasDefconSignal(oneForOne, 2)).isFalse();
        assertThat(ExpectedPoints.perNinety(oneForOne))
                .isEqualTo(ExpectedPoints.APPEARANCE_POINTS);
    }

    @Test
    void aRowWithoutTheDefensiveColumnsScoresExactlyAsItDidBefore() {
        // Every row seeded before these columns existed reads NULL. The term must vanish rather
        // than estimate, or every existing expectation in the suite moves under it.
        Map<String, Object> untouched = row(2, 0.4, 0.2, 0.9);

        assertThat(ExpectedPoints.hasDefconSignal(untouched, 2)).isFalse();
        assertThat(ExpectedPoints.perNinety(untouched)).isCloseTo(
                ExpectedPoints.APPEARANCE_POINTS
                        + 0.4 * ExpectedPoints.goalPoints(2)
                        + 0.2 * ExpectedPoints.ASSIST_POINTS
                        + ExpectedPoints.cleanSheetProbability(0.9)
                                * ExpectedPoints.cleanSheetPoints(2),
                within(1e-9));
    }
```

**Step 2: Run them and watch them fail.**

```bash
cd /home/vanka/dev/fpl-assistant/backend
set -a; source .env.test; set +a
./gradlew test --tests "*ExpectedPointsTest"
```

Expected: compile failure — `cannot find symbol: DEFCON_POINTS`.

**Step 3: Add the constants** to `ExpectedPoints`, below `MIN_SAMPLE_GAMEWEEKS`:

```java
    /**
     * Defensive actions a player must reach in a match to be awarded {@link #DEFCON_POINTS},
     * indexed by {@code element_type}: 1=GK, 2=DEF, 3=MID, 4=FWD.
     *
     * <p>Ten for a defender — clearances, blocks, interceptions and tackles — and twelve for a
     * midfielder or forward, the same plus recoveries. Zero for a goalkeeper: FPL awards them
     * none.
     *
     * <p>These are rules of the game held here, not data read from anywhere:
     * {@code bootstrap-static} does not publish
     * {@code element_types[].defensive_contribution_threshold}.
     */
    public static final int[] DEFCON_THRESHOLD = {0, 0, 10, 12, 12};

    /** What clearing the threshold pays, once, in the match it is cleared in. */
    public static final double DEFCON_POINTS = 2;

    /**
     * Matches of 60 minutes or more the hit rate must rest on before it is read at all.
     *
     * <p>Two, which is the same sample {@link #MIN_WINDOW_MINUTES} already admits a player to the
     * ranking on: no single component is held to a stricter standard than the row itself. Below
     * it the term is dropped rather than estimated, and callers print {@code DC n/a}.
     */
    public static final int MIN_DEFCON_MATCHES = 2;
```

**Step 4: Add the methods**, below `cleanSheetPoints`:

```java
    /**
     * Expected defensive-contribution points per qualifying match, from how often the player
     * has actually crossed the threshold rather than from his average.
     *
     * <p>Per match rather than per 90, like the clean-sheet term beside it: both are the chance
     * of a per-match event, counted over matches of an hour or more, and both are added to a
     * per-90 figure on the understanding that a player who qualifies has played most of the 90.
     *
     * <p>The average cannot answer this. The points land at a per-match threshold, so a defender
     * steady on 9.8 actions scores nothing while one averaging 9.5 with a wide spread scores
     * often. The hit rate reads the distribution the mean hides.
     */
    public static double defconPerNinety(Map<String, Object> row, int position) {
        return defconHitRate(row, position) * DEFCON_POINTS;
    }

    /**
     * The share of the player's qualifying matches in which he cleared his position's threshold,
     * or zero when there is no readable record. Counted over matches of an hour or more — the
     * same discipline the clean sheet uses, because the actions arrive with the minutes and a
     * cameo would otherwise read as a match he failed to contribute in.
     */
    public static double defconHitRate(Map<String, Object> row, int position) {
        if (!hasDefconSignal(row, position)) {
            return 0;
        }
        double matches = numeric(row.get("defcon_matches"));
        double hits = numeric(row.get(position == 2 ? "defcon_hits_def" : "defcon_hits_mid"));
        return hits / matches;
    }

    /**
     * Whether this row carries a defensive-contribution record worth reading: a position FPL
     * awards the points to, and enough qualifying matches to make a rate mean something. The
     * columns are nullable with no default, so an unfetched season reads as no matches — which
     * must drop the term, not estimate it at zero actions.
     */
    public static boolean hasDefconSignal(Map<String, Object> row, int position) {
        return defconThreshold(position) > 0
                && numeric(row.get("defcon_matches")) >= MIN_DEFCON_MATCHES;
    }

    /** Bounds-guarded like {@link #goalPoints}: an unknown element type earns no term. */
    public static int defconThreshold(int position) {
        return position >= 1 && position < DEFCON_THRESHOLD.length
                ? DEFCON_THRESHOLD[position] : 0;
    }
```

**Step 5: Call it from `perNinety`.** Replace the body's tail so the method reads:

```java
    public static double perNinety(Map<String, Object> row) {
        int position = ((Number) row.get("element_type")).intValue();
        double points = APPEARANCE_POINTS
                + numeric(row.get("xg90")) * goalPoints(position)
                + numeric(row.get("xa90")) * ASSIST_POINTS;
        Object xgc = row.get("xgc_per_match");
        if (hasCleanSheetSignal(xgc) && cleanSheetPoints(position) > 0) {
            points += cleanSheetProbability(numeric(xgc)) * cleanSheetPoints(position);
        }
        return points + defconPerNinety(row, position);
    }
```

**Step 6: Update the class javadoc.** Replace the "Not modelled" paragraph:

```java
 * <p>Not modelled: save points and penalty saves, so goalkeepers are understated and callers say
 * so. Defensive-contribution points are modelled — see {@link #defconPerNinety} — from the
 * per-match rows {@code player_fixture_stats} carries; a row without them simply loses the term.
```

**Step 7: Run the new tests and then the whole suite.**

```bash
./gradlew test --tests "*ExpectedPointsTest"
./gradlew test
```

Expected: both green, and **no existing expectation edited to get there.** If any other test moved,
a row is reaching `perNinety` with defcon keys it should not have — find that, do not adjust the
assertion.

**Step 8: Commit.**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/tools/ExpectedPoints.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/tools/ExpectedPointsTest.java
git commit -m "feat(picks): score defensive contribution as a per-match hit rate"
```

Update `**PROGRESS:**` to `Task 2/6 done`.

---

### Task 3: Feed the formula from the pick query

**Files:**
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/tools/PickTools.java` (the
  `metricRows` query, around lines 257-317)
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/tools/PickToolsIntegrationTest.java`

**Interfaces:**
- Consumes: `ExpectedPoints.DEFCON_THRESHOLD`, `hasDefconSignal`, `defconHitRate` from Task 2.
- Produces: rows carrying `defcon_matches`, `defcon_hits_def`, `defcon_hits_mid`, `defcon_avg`.

**Step 1: Write the failing test.** Append to `PickToolsIntegrationTest.java`. Ferreira (9205) is
a defender with two seeded 90-minute fixtures, 9102 and 9104; Kaminski (9206) is the goalkeeper
control. The class autowires `PickTools tools` and `JdbcTemplate jdbc`.

The two cases seed by `UPDATE` on rows `TapasSeed` owns, and `TapasSeed.ensure` inserts with
`ON CONFLICT DO NOTHING`, so a value written here survives into every later test and every later
run of the long-lived container — including the squad-builder tests that read the same rows.
The class has no teardown today; add one, and run it before as well as after so a crashed earlier
run cannot leave the columns dirty:

```java
    @BeforeEach
    @AfterEach
    void clearDefensiveContribution() {
        jdbc.update("UPDATE player_fixture_stats SET defensive_contribution = NULL"
                + " WHERE season_id = 9026 AND player_id IN (9205, 9206)");
    }
```

(JUnit allows both annotations on one method. If the class's existing `@BeforeEach` ordering
matters, put the reset inside it instead — after `TapasSeed.ensure(jdbc)`.)

```java
    @Test
    void aDefenderWhoClearsTheDefensiveThresholdIsScoredForIt() {
        // Two qualifying matches, one of them over the ten-action threshold: a 50% hit rate,
        // worth half of DEFCON_POINTS. Seeded here rather than in TapasSeed so no other case's
        // expected points move under it.
        jdbc.update("UPDATE player_fixture_stats SET defensive_contribution = 12"
                + " WHERE player_id = 9205 AND fixture_id = 9102 AND season_id = 9026");
        jdbc.update("UPDATE player_fixture_stats SET defensive_contribution = 4"
                + " WHERE player_id = 9205 AND fixture_id = 9104 AND season_id = 9026");

        String reply = tools.getDifferentialPicks("DEF", 100, 3);

        assertThat(reply).contains("Ferreira");
        assertThat(reply).contains("DC 50%");
        assertThat(reply).doesNotContain("defensive-contribution points are not in it");
    }

    @Test
    void aGoalkeeperIsNotGivenADefensiveContributionComponent() {
        jdbc.update("UPDATE player_fixture_stats SET defensive_contribution = 15"
                + " WHERE player_id = 9206 AND season_id = 9026");

        String reply = tools.getDifferentialPicks("GK", 100, 3);

        assertThat(reply).contains("Kaminski");
        // ", DC " is the component separator. A bare "DC " would also match the footer's
        // explanation of what DC means once Task 4 writes it.
        assertThat(reply).doesNotContain(", DC ");
    }
```

**Step 2: Run it and watch it fail.**

```bash
cd /home/vanka/dev/fpl-assistant/backend
set -a; source .env.test; set +a
./gradlew test --tests "*PickToolsIntegrationTest"
```

Expected: FAIL — the reply contains neither `DC 50%` nor a DC component at all.

**Step 3: Carry the column into the `recent` CTE.** In `metricRows`, add one line to its select
list, after `pfs.expected_goals_conceded AS xgc,`:

```sql
                           pfs.defensive_contribution,
```

**Step 4: Aggregate it in the `windowed` CTE.** Put a comma on the end of the existing
`/ NULLIF(count(*) FILTER (WHERE minutes >= 60), 0) AS xgc_per_match` line, then add these four
expressions before `FROM recent`:

```sql
                           -- Defensive contribution pays at a threshold per match, so what
                           -- matters is how often the player crossed it, not his average. Only
                           -- matches he was on for an hour count, the same discipline the clean
                           -- sheet uses: the actions arrive with the minutes.
                           count(*) FILTER (WHERE minutes >= 60
                               AND defensive_contribution IS NOT NULL) AS defcon_matches,
                           count(*) FILTER (WHERE minutes >= 60
                               AND defensive_contribution >= %d) AS defcon_hits_def,
                           count(*) FILTER (WHERE minutes >= 60
                               AND defensive_contribution >= %d) AS defcon_hits_mid,
                           AVG(defensive_contribution) FILTER (WHERE minutes >= 60)
                               AS defcon_avg
```

Both thresholds are counted because the position lives on `player`, not on
`player_fixture_stats`; picking between them in Java keeps the rules of the game in one place
instead of duplicating 10 and 12 into SQL text.

**Step 5: Carry them through the `metrics` CTE and the outer select.** In `metrics`, extend the
select list:

```sql
                           min(w.xgc_per_match) AS xgc_per_match,
                           min(w.defcon_matches) AS defcon_matches,
                           min(w.defcon_hits_def) AS defcon_hits_def,
                           min(w.defcon_hits_mid) AS defcon_hits_mid,
                           min(w.defcon_avg) AS defcon_avg
```

and the final select:

```sql
                SELECT pool.*, m.mins, m.matches, m.xg90, m.xa90, m.pts90, m.xgc_per_match,
                       m.defcon_matches, m.defcon_hits_def, m.defcon_hits_mid, m.defcon_avg
```

**Step 6: Fix the format arguments.** The two new `%d` placeholders sit before `WHERE rn <= %d`,
so they must be inserted in that position in the argument list. The call becomes:

```java
                """.formatted(positionFilter(elementType, "p"),
                        ExpectedPoints.DEFCON_THRESHOLD[2], ExpectedPoints.DEFCON_THRESHOLD[3],
                        windowLimit(metric), ExpectedPoints.MIN_WINDOW_MINUTES,
                        metricKey, metricUnique, poolKey, poolUnique),
                season.seasonId(), maxOwnership, metric.seasonId(), metric.seasonId());
```

Getting this order wrong produces a query that still compiles and silently ranks on the wrong
number, so read it back against the template once before running.

**Step 7: Run the test to see the query half land.**

```bash
./gradlew test --tests "*PickToolsIntegrationTest"
```

Expected: still FAIL, but now on the `DC 50%` assertion only — the number is in the row, nothing
prints it yet. Task 4 closes that. If it fails on the *first* assertion instead, Ferreira has
dropped out of the pool and the query is wrong.

**Step 8: Commit.**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/tools/PickTools.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/tools/PickToolsIntegrationTest.java
git commit -m "feat(picks): read the defensive-contribution hit rate into the metric query"
```

Update `**PROGRESS:**` to `Task 3/6 done`.

---

### Task 4: Say it in the reply

**Files:**
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/tools/PickTools.java` — the
  `components` method (~line 443), the `footer` disclosure (~line 501), the class javadoc (~line
  47) and both `@Tool` descriptions.
- Test: the two cases from Task 3 turn green here.

**Interfaces:**
- Consumes: `ExpectedPoints.hasDefconSignal`, `defconHitRate`, `defconThreshold`,
  `DEFCON_THRESHOLD`, `MIN_DEFCON_MATCHES`; row key `defcon_avg`.
- Produces: nothing later tasks depend on.

**Step 1: Rewrite `components`.** The existing version returns early when a position has no
clean-sheet value, which would skip the DC component for forwards — they have no clean sheet but
do have a threshold. Replace the whole method:

```java
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
        if (ExpectedPoints.defconThreshold(position) > 0) {
            parts.append(ExpectedPoints.hasDefconSignal(row, position)
                    ? ", DC %s%% at %s actions".formatted(
                            fixed(ExpectedPoints.defconHitRate(row, position) * 100, 0),
                            fixed(numeric(row.get("defcon_avg")), 1))
                    : ", DC n/a");
        }
        return parts.toString();
    }
```

**Step 2: Rewrite the footer disclosure.** Replace the `out.append("xPts/90 covers goals, ...")`
block with:

```java
        out.append("xPts/90 covers goals, assists, clean sheets and defensive contribution. DC is")
                .append(" the share of matches the player cleared his threshold in — ")
                .append(ExpectedPoints.DEFCON_THRESHOLD[2])
                .append(" defensive actions for a defender, ")
                .append(ExpectedPoints.DEFCON_THRESHOLD[3])
                .append(" for a midfielder or forward — counted over matches he played an hour")
                .append(" of, and it reads n/a below ").append(ExpectedPoints.MIN_DEFCON_MATCHES)
                .append(" such matches. Save points and penalty saves are still not in it, so")
                .append(" goalkeepers are understated; Pts/90 is where their real return shows")
                .append(" up. Do not call xPts/90 a complete projection.\n")
                .append("Unavailable players (injured/suspended) are excluded.\n");
```

**Step 3: Update the class javadoc.** Replace the "Not modelled" paragraph (~line 47) with:

```java
 * <p>Defensive contribution is modelled as a hit rate: the share of a player's hour-or-more
 * matches in which he reached his position's action threshold, times the two points it pays. The
 * average cannot stand in for it — the points land at a per-match threshold, so a defender steady
 * on 9.8 actions scores nothing and one averaging 9.5 with a wide spread scores often.
 *
 * <p>Still not modelled: save points and penalty saves. {@code player_fixture_stats} carries
 * {@code saves} and {@code penalties_saved}, so leaving them out is a choice, not a gap.
 * Goalkeepers are understated, which the reply says.
```

**Step 4: Update both `@Tool` descriptions.** In each, the phrase listing what the Score is built
from must name defensive contribution. In `getDifferentialPicks` and `getCaptainCandidates`,
change `plus the clean-sheet probability implied by expected goals conceded` to `plus the
clean-sheet probability implied by expected goals conceded and the rate at which the player
reaches the defensive-contribution threshold`.

**Step 5: Update the one existing test that pins the old disclosure.**
`PickToolsIntegrationTest.theReplySaysWhichPointsTheExpectedModelDoesNotCover` asserts the
sentence this task rewrites. This is the single deliberate expectation change in the plan — the
reply is now telling a different truth. Replace its first assertion:

```java
        assertThat(result).contains("Save points and penalty saves are still not in it");
```

and keep the other two (`Do not call xPts/90 a complete projection`, `doesNotContain("are not in
the data")`) as they are. Do not touch any other assertion; if another one moves, that is a
defect in the query, not a number to update.

**Step 6: Run the pick tests, then the whole suite.**

```bash
./gradlew test --tests "*PickTools*"
./gradlew test
```

Expected: both Task 3 cases green, whole suite green.

**Step 7: Commit.**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/tools/PickTools.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/tools/PickToolsIntegrationTest.java
git commit -m "feat(picks): show the defensive-contribution component and stop disowning it"
```

Update `**PROGRESS:**` to `Task 4/6 done`.

---

### Task 5: The squad builder reads the same number

`ExpectedPoints` is shared. Left undone, `CandidatePool` supplies no defcon keys, so the squad
builder silently values the same defender lower than the pick tools do in the same conversation.

**Files:**
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/squad/CandidatePool.java` (query
  around lines 125-175 and the metrics map around line 198)
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/squad/SquadFormatter.java` (~line
  130)
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/squad/CandidatePoolIntegrationTest.java`

**Interfaces:**
- Consumes: the same four row keys and `ExpectedPoints` API as Tasks 2-4.
- Produces: nothing later tasks depend on.

**Step 1: Write the failing test.** The pool is built from the daily-price `_test` table, which
this class seeds itself and scopes every read to `TEST_ELEMENT_IDS` — so Ferreira has to be put in
it first. Three edits to `CandidatePoolIntegrationTest.java`:

Add a constant beside `KAMINSKI`, and widen the scoped list and the delete's `IN (...)`, which is
written with a fixed number of placeholders:

```java
    /** Tapas' Ferreira: a defender with two 90-minute rows, so a defcon hit rate can be read. */
    private static final int FERREIRA = 9205;

    private static final List<Integer> TEST_ELEMENT_IDS =
            List.of(RANKABLE, BODY, INJURED, DOUBTFUL_NO_RECORD, OKAFOR, KAMINSKI, FERREIRA);
```

```java
    private void deleteTestRows() {
        jdbc.update("DELETE FROM " + table + " WHERE season = ?"
                        + " AND element_id IN (?, ?, ?, ?, ?, ?, ?)",
                season, RANKABLE, BODY, INJURED, DOUBTFUL_NO_RECORD, OKAFOR, KAMINSKI, FERREIRA);
        jdbc.update("UPDATE player_fixture_stats SET defensive_contribution = NULL"
                + " WHERE season_id = 9026 AND player_id = ?", FERREIRA);
    }
```

The second statement resets the tapas row this case writes to, for the same reason Task 3 gives:
`TapasSeed` never overwrites, so the value would otherwise outlive the test. `deleteTestRows` is
already called from both `@BeforeEach` and `@AfterEach`.

Add one line to `refuseProductionAndSeed`, after the `KAMINSKI` insert:

```java
        insert(FERREIRA, "Ferreira", 45, 2, 8.0, "a", null);
```

Then the case itself:

```java
    @Test
    void theCandidateMetricsCarryTheDefensiveContributionRecord() {
        // The squad builder and the pick tools share ExpectedPoints. If the pool does not supply
        // these keys, the same defender is worth less here than in a picks list, in the same
        // conversation.
        jdbc.update("UPDATE player_fixture_stats SET defensive_contribution = 12"
                + " WHERE player_id = ? AND fixture_id = 9102 AND season_id = 9026", FERREIRA);
        jdbc.update("UPDATE player_fixture_stats SET defensive_contribution = 4"
                + " WHERE player_id = ? AND fixture_id = 9104 AND season_id = 9026", FERREIRA);

        SquadCandidate ferreira = load().stream()
                .filter(c -> c.elementId() == FERREIRA)
                .findFirst()
                .orElseThrow();

        assertThat(ExpectedPoints.hasDefconSignal(ferreira.metrics(), 2)).isTrue();
        assertThat(ExpectedPoints.defconHitRate(ferreira.metrics(), 2))
                .isCloseTo(0.5, within(1e-9));
    }
```

`load()` is the class's existing helper. Import
`fi.karivanttinen.fplassistant.tools.ExpectedPoints` and
`static org.assertj.core.api.Assertions.within`.

**Step 2: Run it and watch it fail.**

```bash
./gradlew test --tests "*CandidatePoolIntegrationTest"
```

Expected: FAIL on `hasDefconSignal` — the map has no `defcon_matches` key.

**Step 3: Edit the query.** Four edits, all inside the text block in `CandidatePool.load`.

Add the column to the `recent` CTE, after `pfs.expected_goals_conceded AS xgc,`:

```sql
                           pfs.defensive_contribution,
```

Put a comma on the end of the existing `... AS xgc_per_match` line in `windowed`, then add before
`FROM recent`:

```sql
                           count(*) FILTER (WHERE minutes >= 60
                               AND defensive_contribution IS NOT NULL) AS defcon_matches,
                           count(*) FILTER (WHERE minutes >= 60
                               AND defensive_contribution >= %d) AS defcon_hits_def,
                           count(*) FILTER (WHERE minutes >= 60
                               AND defensive_contribution >= %d) AS defcon_hits_mid,
                           AVG(defensive_contribution) FILTER (WHERE minutes >= 60)
                               AS defcon_avg
```

Extend the `metrics` select list, replacing its `min(w.xgc_per_match) AS xgc_per_match` line:

```sql
                           min(w.xgc_per_match) AS xgc_per_match,
                           min(w.defcon_matches) AS defcon_matches,
                           min(w.defcon_hits_def) AS defcon_hits_def,
                           min(w.defcon_hits_mid) AS defcon_hits_mid,
                           min(w.defcon_avg) AS defcon_avg
```

Extend the final select:

```sql
                SELECT pool.*, m.mins, m.fixtures, m.xg90, m.xa90, m.pts90, m.xgc_per_match,
                       m.defcon_matches, m.defcon_hits_def, m.defcon_hits_mid, m.defcon_avg
```

The two new `%d` placeholders sit after `%s dailyTable` and before `WHERE rn <= %d`, so the
argument list becomes:

```java
                """.formatted(dailyTable,
                        ExpectedPoints.DEFCON_THRESHOLD[2], ExpectedPoints.DEFCON_THRESHOLD[3],
                        window.windowLimit(), ExpectedPoints.MIN_WINDOW_MINUTES,
                        metricKey, metricUnique, poolKey, poolUnique),
                capture.season(), capture.date(), window.seasonId(), window.seasonId(),
                window.seasonId());
```

Note the `LEFT JOIN metrics` here, unlike `PickTools`' inner join: a candidate with no metric row
keeps his place in the pool with null metrics, and `hasDefconSignal` must return false for him
rather than throw. The Task 2 guard already does, because `numeric(null)` is 0.

**Step 4: Carry them into the metrics map**, beside the existing four:

```java
            metrics.put("defcon_matches", row.get("defcon_matches"));
            metrics.put("defcon_hits_def", row.get("defcon_hits_def"));
            metrics.put("defcon_hits_mid", row.get("defcon_hits_mid"));
            metrics.put("defcon_avg", row.get("defcon_avg"));
```

Update the `SquadCandidate` javadoc that lists the expected keys (`xgc_per_match`, `pts90`, …) to
name the new ones.

**Step 5: Rewrite the `SquadFormatter` disclosure** (~line 130):

```java
        out.append("\nSave points and penalty saves are not in these numbers, so goalkeepers are"
                + " understated. Defensive contribution is included, as the share of matches a"
                + " player reaches his position's action threshold in.\n");
```

**Step 6: Run the squad tests, then the whole suite.**

```bash
./gradlew test --tests "*Squad*" --tests "*CandidatePool*"
./gradlew test
```

**Step 7: Commit.**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/squad/ \
        backend/src/test/java/fi/karivanttinen/fplassistant/squad/CandidatePoolIntegrationTest.java
git commit -m "feat(squad): score defensive contribution in the candidate pool too"
```

Update `**PROGRESS:**` to `Task 5/6 done`.

---

### Task 6: Record it and open the pull request

**Files:**
- Modify: `docs/STATUS.md`
- Modify: this plan — add an outcome block

**Step 1: Add a `docs/STATUS.md` entry** in the file's existing style, dated, covering: that
defcon is now in `xPts/90` as a hit rate over hour-plus matches; the two thresholds and where they
come from (rules held, not data read — FPL does not publish them); why a hit rate and not the
average; the two-match floor and what `DC n/a` means; that save points and penalty saves are still
out; and that both the pick tools and the squad builder read it, through the one shared formula.
Record the 2026/27 lockdown change too — that defcon is provisional until 09:00 UK the day after a
gameweek's last match, that the nightly sync's full-history rewrite heals it within a day, and
that a hit rate feels a threshold revision more than an average would. Include whatever Task 1
measured — the per-position base rates are the figure a reader will want
to sanity-check a reply against.

**Step 2: Run the full suite one last time from a known-clean database.**

```bash
cd /home/vanka/dev/fpl-assistant
scripts/test-db.sh reset
cd backend && set -a; source .env.test; set +a
./gradlew cleanTest test
```

Expected: green. A `reset` first because schema-ordering defects only appear against an empty
database (KAR-36).

**Step 3: Commit and open the pull request.**

```bash
git add docs/STATUS.md docs/superpowers/plans/2026-08-28-defcon-in-expected-points.md
git commit -m "docs: record defensive contribution entering xPts/90"
git push -u origin HEAD
gh pr create --title "Score defensive contribution in xPts/90" --body "$(cat <<'BODY'
Defensive-contribution points now reach xPts/90, as the share of hour-plus matches in which a
player cleared his position's action threshold (10 for defenders, 12 for midfielders and
forwards) times the two points it pays. A per-match threshold cannot be read off an average, so
the hit rate reads the distribution the mean hides. The term is dropped, not estimated, when the
columns are unpopulated.

Both the pick tools and the squad builder read it through the shared ExpectedPoints, and both
replies now describe it instead of disowning it. Save points and penalty saves are still out.

Verified: full suite green against a freshly reset test container.

Plan: docs/superpowers/plans/2026-08-28-defcon-in-expected-points.md
Closes KAR-29.
BODY
)"
```

**Step 4:** Set KAR-29 to In Review, and update `**PROGRESS:**` to `Task 6/6 done`.

---

## Notes for whoever executes this

- **Task 1 is a real gate.** If the column turns out to hold awarded points rather than actions,
  Tasks 2 and 3 change shape. Do not skip it because `docs/STATUS.md` implies an answer.
- **Tasks 2, 3 and 5 must leave the suite green with no expectation edited.** Every row seeded
  before these columns existed reads `NULL`, so the term vanishes. A moved expectation somewhere
  unrelated is a bug in the guard, not a number to update. Task 4 changes exactly one assertion,
  named in its Step 5, because the reply's disclosure sentence is what that task rewrites.
- **Both integration classes reset the defcon columns they write, before and after.** The test
  container is long-lived and `TapasSeed` never overwrites, so a seeded value that is not cleared
  becomes part of every other test's data.
- **Do not add the defcon columns to `TapasSeed`.** Seed them per-test, scoped by element id, so
  one case's data cannot move another case's expected points.
- **Read the `.formatted(...)` argument order back against the template** in Tasks 3 and 5. A
  misordered `%d` compiles and ranks on the wrong number.
