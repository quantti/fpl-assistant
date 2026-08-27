# Deterministic Squad Builder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `buildSquad` tool that returns a legal 15-player FPL squad satisfying stated constraints, or a refusal naming the constraint that binds — never a placeholder, never a broken rule.

**Architecture:** A candidate pool is read from `fpl_daily_price` (prices, clubs, availability) joined to `player_fixture_stats` (metrics). Each candidate is scored as expected FPL points summed over their next N fixtures. An ojAlgo integer program picks the 15 and the starting XI under the game's rules, the user's constraints and three structural defaults. Infeasibility is diagnosed before the solve, where the message can be precise.

**Tech Stack:** Java 25, Spring Boot 4.1, Spring AI 2.0.0 (`@Tool`), `JdbcTemplate`, ojAlgo 57.1.1, JUnit 5 + AssertJ.

**Spec:** `docs/superpowers/specs/2026-08-27-squad-builder-design.md`

**PROGRESS:** Task 10/10 done — implemented, reviewed, and through one whole-branch fix wave.

## Global Constraints

- **Never push to `master`.** Push a branch, open a PR. Never `--no-verify`, never re-point `core.hooksPath`.
- **Tests never run against Supabase.** The suite uses the disposable container (`scripts/test-db.sh`). Anything reading tapas-owned tables seeds via `TapasSeed`; daily-price rows go in the `fpl_daily_price_test` table and are deleted by the test that wrote them.
- **No `@Tool` method takes a parameter identifying an account.** `buildSquad` needs none.
- **Two provenance lines, not one.** Prices/ownership/availability via `DailyPriceSource`; metrics via `SeasonContext.provenance()`. Record this exception in `AGENTS.md` (Task 10).
- **Rules of the game are never relaxed** (squad size, position quotas, 3-per-club, budget, XI subset, XI size, formation). Structural defaults relax in order 13 → 12 → 14. User constraints never relax silently — they refuse.
- **Formation, settled:** exactly 1 GK in the XI, DEF ≥ 3, FWD ≥ 1, XI = 11. `MID ∈ [2,5]` follows. **5-2-3 is legal**; `FPL_RULES.md`'s enumerated list is incomplete and must not be used to derive anything.
- **Every decimal is pinned to `Locale.ROOT`.** A comma decimal separator reads as a thousands separator.
- **Constants, exact values:**

| Name | Value |
|---|---|
| `HORIZON_DEFAULT` | 5 |
| `BUDGET_DEFAULT` | 1000 (£100.0m in ×10 integers) |
| `PREMIUM_THRESHOLD` | 100 |
| `TEMPLATE_OWNERSHIP` | 20.0 |
| `TEMPLATE_MIN_COUNT` | 6 |
| `XI_MIN_EXPECTED_MINUTES` | 60 |
| `BENCH_WEIGHT` | 0.1 |
| `FDR_SWING` | 0.1 |
| `MIN_WINDOW_MINUTES` | 180 (reused) |
| `MIN_SAMPLE_GAMEWEEKS` | 5 (reused) |
| `WINDOW_MATCHES` | 10 (reused) |
| `WHOLE_SEASON` | 60 (reused) |
| `SQUAD_SIZE` | 15 |
| `XI_SIZE` | 11 |
| `POSITION_QUOTAS` | `{0, 2, 5, 5, 3}` indexed by `element_type` |
| `MAX_PER_CLUB` | 3 |

## File Structure

**Created:**

| File | Responsibility |
|---|---|
| `tools/ExpectedPoints.java` | FPL scoring applied to expected numbers. Extracted from `PickTools` so one formula exists in one place. |
| `squad/SquadConstraints.java` | The parsed request: pins, exclusions, club quotas, budget, horizon, forced formation. |
| `squad/ConstraintParser.java` | Strings from the model → `SquadConstraints`. Owns the comparator syntax. |
| `squad/SquadCandidate.java` | One pool row: identity, club, price, position, ownership, minutes, metrics, tier. |
| `squad/CandidatePool.java` | The SQL. Two tiers, cross-season name bridge, availability filter. |
| `squad/HorizonScorer.java` | Expected minutes, FDR adjustment, score summed over the horizon. |
| `squad/Feasibility.java` | Pre-flight checks producing named refusals. |
| `squad/SquadSolver.java` | The ojAlgo model: rules, user constraints, structural defaults, relaxation. |
| `squad/SolvedSquad.java` | The result: the 15, the XI, bench order, captain, vice-captain, cost, relaxations. |
| `squad/SquadFormatter.java` | The output contract. |
| `squad/SquadTools.java` | The `@Tool` method. Wires the above. |

**Modified:** `backend/build.gradle`, `tools/PickTools.java`, `resources/prompts/system-prompt.st`, `AGENTS.md`.

---

### Task 1: Extract the shared scoring into `ExpectedPoints`

`PickTools` owns the expected-points formula. The squad builder needs the same formula. Copying it guarantees the two drift apart, so it moves to one class first, before anything depends on it.

**Files:**
- Create: `backend/src/main/java/fi/karivanttinen/fplassistant/tools/ExpectedPoints.java`
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/tools/PickTools.java`
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/tools/ExpectedPointsTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `ExpectedPoints.perNinety(Map<String,Object> row)` → `double`; `ExpectedPoints.cleanSheetProbability(double xgc)` → `double`; `ExpectedPoints.goalPoints(int elementType)` → `double`; `ExpectedPoints.cleanSheetPoints(int elementType)` → `double`; `ExpectedPoints.numeric(Object)` → `double`. All `public static`. Constants `GOAL_POINTS`, `CLEAN_SHEET_POINTS`, `ASSIST_POINTS`, `APPEARANCE_POINTS` are `public static final`.

- [ ] **Step 1: Write the failing test**

Create `ExpectedPointsTest.java`:

```java
package fi.karivanttinen.fplassistant.tools;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The scoring formula, on its own. It lives apart from {@link PickTools} because two tools now
 * rank on it and a copy in each is a guarantee they diverge.
 */
class ExpectedPointsTest {

    private static Map<String, Object> row(int elementType, double xg90, double xa90, Double xgc) {
        Map<String, Object> row = new HashMap<>();
        row.put("element_type", elementType);
        row.put("xg90", BigDecimal.valueOf(xg90));
        row.put("xa90", BigDecimal.valueOf(xa90));
        row.put("xgc_per_match", xgc == null ? null : BigDecimal.valueOf(xgc));
        return row;
    }

    @Test
    void aGoalIsWorthMoreToADefenderThanToAForward() {
        double defender = ExpectedPoints.perNinety(row(2, 0.5, 0, null));
        double forward = ExpectedPoints.perNinety(row(4, 0.5, 0, null));

        assertThat(defender).isGreaterThan(forward);
        assertThat(defender - forward).isCloseTo(0.5 * (6 - 4), within(1e-9));
    }

    @Test
    void aCleanSheetIsWorthNothingToAForward() {
        double withCleanSheetChance = ExpectedPoints.perNinety(row(4, 0, 0, 0.5));
        double without = ExpectedPoints.perNinety(row(4, 0, 0, null));

        assertThat(withCleanSheetChance).isEqualTo(without);
    }

    @Test
    void anUnrecordedExpectedConcessionIsNotAGuaranteedCleanSheet() {
        // The column is NOT NULL DEFAULT 0, so an unpopulated season reads as 0.00 — and
        // exp(-0) is 100%, which would put every defender in that season top of every list.
        double zero = ExpectedPoints.perNinety(row(2, 0, 0, 0.0));
        double absent = ExpectedPoints.perNinety(row(2, 0, 0, null));

        assertThat(zero).isEqualTo(absent);
        assertThat(zero).isEqualTo(ExpectedPoints.APPEARANCE_POINTS);
    }

    @Test
    void anOutOfRangePositionDegradesRatherThanThrowing() {
        // FPL added managers as a fifth element_type and the column has no CHECK constraint.
        assertThat(ExpectedPoints.goalPoints(5)).isZero();
        assertThat(ExpectedPoints.cleanSheetPoints(0)).isZero();
        assertThat(ExpectedPoints.perNinety(row(5, 1.0, 1.0, 0.5)))
                .isEqualTo(ExpectedPoints.APPEARANCE_POINTS + 1.0 * ExpectedPoints.ASSIST_POINTS);
    }

    @Test
    void cleanSheetProbabilityIsPoisson() {
        assertThat(ExpectedPoints.cleanSheetProbability(0.5))
                .isCloseTo(Math.exp(-0.5), within(1e-9));
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && set -a && source .env.test && set +a && ./gradlew test --tests '*ExpectedPointsTest*'`
Expected: FAIL — `ExpectedPoints` does not exist (compilation error).

- [ ] **Step 3: Create `ExpectedPoints`**

```java
package fi.karivanttinen.fplassistant.tools;

import java.util.Map;

/**
 * FPL's own scoring applied to expected numbers — the one figure that compares a forward with a
 * goalkeeper, and an attacking full-back with a stopper.
 *
 * <p>A goal is worth 6 to a defender and 4 to a forward; a clean sheet 4 to a defender, 1 to a
 * midfielder and nothing to a forward. Scoring on that scale is what lets the two kinds of
 * defender compete on their own strengths instead of being ranked against each other's.
 *
 * <p>Extracted from {@code PickTools} when a second tool needed the same formula. Ranking and
 * squad building must agree about what a player is worth; two copies would not.
 *
 * <p>Not modelled: defensive-contribution points (issue #46), save points and penalty saves.
 * Goalkeepers and ball-winning defenders are understated, which callers say in their output.
 */
public final class ExpectedPoints {

    /** Indexed by {@code element_type}: 1=GK, 2=DEF, 3=MID, 4=FWD. */
    public static final double[] GOAL_POINTS = {0, 6, 6, 5, 4};
    public static final double[] CLEAN_SHEET_POINTS = {0, 4, 4, 1, 0};
    public static final double ASSIST_POINTS = 3;
    public static final double APPEARANCE_POINTS = 2;

    private ExpectedPoints() {
    }

    /**
     * Expected FPL points per 90 minutes. Appearance points are a constant and do not affect the
     * order — they are here so the number reads as points rather than as an index.
     */
    public static double perNinety(Map<String, Object> row) {
        int position = ((Number) row.get("element_type")).intValue();
        double points = APPEARANCE_POINTS
                + numeric(row.get("xg90")) * goalPoints(position)
                + numeric(row.get("xa90")) * ASSIST_POINTS;
        Object xgc = row.get("xgc_per_match");
        if (hasCleanSheetSignal(xgc) && cleanSheetPoints(position) > 0) {
            points += cleanSheetProbability(numeric(xgc)) * cleanSheetPoints(position);
        }
        return points;
    }

    /** Poisson: the chance a side with this expected concession lets in none. */
    public static double cleanSheetProbability(double expectedGoalsConceded) {
        return Math.exp(-expectedGoalsConceded);
    }

    /**
     * Bounds-guarded: the column has no CHECK constraint and FPL has since added managers as a
     * fifth type, so an out-of-range value must degrade rather than throw out of a tool call.
     */
    public static double goalPoints(int position) {
        return position >= 1 && position < GOAL_POINTS.length ? GOAL_POINTS[position] : 0;
    }

    public static double cleanSheetPoints(int position) {
        return position >= 1 && position < CLEAN_SHEET_POINTS.length
                ? CLEAN_SHEET_POINTS[position] : 0;
    }

    public static double numeric(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    /**
     * Whether a captured expected-concession figure can be believed. The column is
     * {@code NOT NULL DEFAULT 0}, so an unpopulated season reads as 0.00 — and {@code exp(-0)} is
     * a 100% clean sheet. No real side has an expected concession of exactly zero across a match,
     * so zero means nobody wrote it.
     */
    private static boolean hasCleanSheetSignal(Object xgc) {
        return xgc != null && numeric(xgc) > 0;
    }
}
```

- [ ] **Step 4: Run the new test — it passes**

Run: `cd backend && set -a && source .env.test && set +a && ./gradlew test --tests '*ExpectedPointsTest*'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Point `PickTools` at it**

In `PickTools.java`, delete the private members `GOAL_POINTS`, `CLEAN_SHEET_POINTS`, `ASSIST_POINTS`, `APPEARANCE_POINTS`, `goalPoints`, `cleanSheetPoints`, `cleanSheetProbability`, `hasCleanSheetSignal`, and the body of `expectedPointsPer90`. Replace `expectedPointsPer90` with a delegation:

```java
    /**
     * FPL's own scoring applied to expected numbers. The formula lives in
     * {@link ExpectedPoints} because the squad builder ranks on the same one; a copy here would
     * be a copy that drifts.
     */
    static double expectedPointsPer90(Map<String, Object> row) {
        return ExpectedPoints.perNinety(row);
    }
```

Leave `numeric(Object)` on `PickTools` where other methods still call it — or delete it and call `ExpectedPoints.numeric` throughout, whichever leaves no unused member. Do not touch anything else in the file.

- [ ] **Step 6: Run the whole suite — nothing else changed**

Run: `cd backend && set -a && source .env.test && set +a && ./gradlew cleanTest test`
Expected: PASS. Baseline is 429 tests on master; expect 434.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/tools/ExpectedPoints.java \
        backend/src/main/java/fi/karivanttinen/fplassistant/tools/PickTools.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/tools/ExpectedPointsTest.java
git commit -m "refactor(tools): lift the expected-points formula out of PickTools"
```

---

### Task 2: Add ojAlgo and pin its API with a smoke test

The plan's later tasks assume an ojAlgo API written from recollection. This task proves it on Java 25 in five minutes rather than discovering it wrong inside Task 7.

**Files:**
- Modify: `backend/build.gradle`
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/squad/OjAlgoSmokeTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: confirmation that `ExpressionsBasedModel`, `Variable.binary()`, `Variable.weight(double)`, `Expression.set(Variable, double)`, `Expression.level/upper/lower`, `model.maximise()`, `Optimisation.State.OPTIMAL` and `Optimisation.State.INFEASIBLE` behave as Task 7 assumes.

- [ ] **Step 1: Add the dependency**

In `backend/build.gradle`, in the `dependencies` block, after the `commons-csv` line:

```groovy
    // Integer programming for the squad builder: picking 15 players under a budget, position
    // quotas, a three-per-club cap and the user's own constraints is a knapsack with side
    // constraints, not something to hand-roll. MIT, pure Java, and every dependency it declares
    // is test-scoped, so nothing joins the runtime classpath but ojAlgo itself.
    implementation 'org.ojalgo:ojalgo:57.1.1'
```

- [ ] **Step 2: Write the smoke test**

```java
package fi.karivanttinen.fplassistant.squad;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.ojalgo.optimisation.ExpressionsBasedModel;
import org.ojalgo.optimisation.Optimisation;
import org.ojalgo.optimisation.Variable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the ojAlgo API the solver is built on, and proves it runs on this JDK.
 *
 * <p>It exists because every later assumption about the library — how a binary variable is
 * declared, how a constraint row is written, what an infeasible model returns — is cheaper to
 * falsify here than inside a half-finished solver.
 */
class OjAlgoSmokeTest {

    @Test
    void picksTheBestTwoOfThreeUnderABudget() {
        ExpressionsBasedModel model = new ExpressionsBasedModel();
        double[] value = {5.0, 3.0, 1.0};
        int[] cost = {6, 5, 4};

        List<Variable> vars = new ArrayList<>();
        for (int i = 0; i < value.length; i++) {
            vars.add(model.newVariable("p" + i).binary().weight(value[i]));
        }

        var size = model.newExpression("size").level(2);
        var budget = model.newExpression("budget").upper(11);
        for (int i = 0; i < vars.size(); i++) {
            size.set(vars.get(i), 1);
            budget.set(vars.get(i), cost[i]);
        }

        Optimisation.Result result = model.maximise();

        assertThat(result.getState()).isEqualTo(Optimisation.State.OPTIMAL);
        // The two best cost 11 exactly, which the budget allows.
        assertThat(result.doubleValue(0)).isEqualTo(1.0);
        assertThat(result.doubleValue(1)).isEqualTo(1.0);
        assertThat(result.doubleValue(2)).isEqualTo(0.0);
    }

    @Test
    void reportsInfeasibleRatherThanReturningSomethingWrong() {
        ExpressionsBasedModel model = new ExpressionsBasedModel();
        Variable only = model.newVariable("only").binary().weight(1.0);
        model.newExpression("two").level(2).set(only, 1);

        Optimisation.Result result = model.maximise();

        assertThat(result.getState().isFeasible()).isFalse();
    }
}
```

- [ ] **Step 3: Run it**

Run: `cd backend && set -a && source .env.test && set +a && ./gradlew test --tests '*OjAlgoSmokeTest*'`
Expected: PASS, 2 tests.

**If it fails to compile,** the API differs from what this plan assumes. Fix the smoke test against the real API first, then carry the corrected forms into Tasks 7 and 8 — do not work around it by changing the assertions' meaning.

- [ ] **Step 4: Commit**

```bash
git add backend/build.gradle \
        backend/src/test/java/fi/karivanttinen/fplassistant/squad/OjAlgoSmokeTest.java
git commit -m "build: add ojAlgo for the squad builder's integer program"
```

---

### Task 3: `SquadConstraints` and `ConstraintParser`

The comparator syntax is the feature's promise made writable. Parsing is pure and gets pure tests.

**Files:**
- Create: `backend/src/main/java/fi/karivanttinen/fplassistant/squad/SquadConstraints.java`
- Create: `backend/src/main/java/fi/karivanttinen/fplassistant/squad/ConstraintParser.java`
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/squad/ConstraintParserTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum SquadConstraints.Comparator { EXACTLY, AT_LEAST, AT_MOST }`
  - `record SquadConstraints.ClubQuota(String club, Comparator comparator, int count)`
  - `record SquadConstraints(List<String> mustInclude, List<String> mustExclude, List<ClubQuota> clubQuotas, int budget, int horizon, String formation)` — `budget` in ×10 integers, `formation` nullable.
  - `ConstraintParser.parse(String mustInclude, String mustExclude, String clubQuota, double budget, int nextN, String formation)` → `SquadConstraints`, throwing `ConstraintParser.MalformedConstraintException` (extends `RuntimeException`) with a message naming the offending fragment.

- [ ] **Step 1: Write the failing test**

```java
package fi.karivanttinen.fplassistant.squad;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The constraint syntax the model writes and the tool promises to honour.
 *
 * <p>A bare number means <b>exactly</b>. That is the common request — "all three from Arsenal" —
 * and it is the reading that fails loudly rather than quietly when it cannot be met.
 */
class ConstraintParserTest {

    private static SquadConstraints parse(String clubQuota) {
        return ConstraintParser.parse("", "", clubQuota, 100.0, 5, "");
    }

    @Test
    void aBareNumberMeansExactly() {
        var quota = parse("Arsenal:3").clubQuotas().getFirst();

        assertThat(quota.club()).isEqualTo("Arsenal");
        assertThat(quota.comparator()).isEqualTo(SquadConstraints.Comparator.EXACTLY);
        assertThat(quota.count()).isEqualTo(3);
    }

    @Test
    void readsAllThreeComparators() {
        var quotas = parse("Arsenal:>=2, Chelsea:<=1, Spurs:=3").clubQuotas();

        assertThat(quotas).extracting(SquadConstraints.ClubQuota::comparator)
                .containsExactly(SquadConstraints.Comparator.AT_LEAST,
                        SquadConstraints.Comparator.AT_MOST,
                        SquadConstraints.Comparator.EXACTLY);
        assertThat(quotas).extracting(SquadConstraints.ClubQuota::club)
                .containsExactly("Arsenal", "Chelsea", "Spurs");
    }

    @Test
    void zeroIsAClubLevelExclusion() {
        var quota = parse("Man Utd:0").clubQuotas().getFirst();

        assertThat(quota.comparator()).isEqualTo(SquadConstraints.Comparator.EXACTLY);
        assertThat(quota.count()).isZero();
    }

    @Test
    void budgetBecomesTenthsOfAMillion() {
        assertThat(ConstraintParser.parse("", "", "", 100.0, 5, "").budget()).isEqualTo(1000);
        assertThat(ConstraintParser.parse("", "", "", 99.4, 5, "").budget()).isEqualTo(994);
    }

    @Test
    void namesAreSplitAndTrimmedAndEmptyEntriesDropped() {
        var constraints = ConstraintParser.parse(" Haaland , Gabriel ,, ", "Salah", "", 100.0, 5, "");

        assertThat(constraints.mustInclude()).containsExactly("Haaland", "Gabriel");
        assertThat(constraints.mustExclude()).containsExactly("Salah");
    }

    @Test
    void anAbsentHorizonFallsBackToTheDefault() {
        assertThat(ConstraintParser.parse("", "", "", 100.0, 0, "").horizon()).isEqualTo(5);
        assertThat(ConstraintParser.parse("", "", "", 100.0, 3, "").horizon()).isEqualTo(3);
    }

    @Test
    void aMalformedQuotaNamesTheFragmentRatherThanFailingVaguely() {
        assertThatThrownBy(() -> parse("Arsenal:three"))
                .isInstanceOf(ConstraintParser.MalformedConstraintException.class)
                .hasMessageContaining("Arsenal:three");

        assertThatThrownBy(() -> parse("Arsenal"))
                .isInstanceOf(ConstraintParser.MalformedConstraintException.class)
                .hasMessageContaining("Arsenal");
    }

    @Test
    void aNegativeQuotaIsRejected() {
        assertThatThrownBy(() -> parse("Arsenal:-1"))
                .isInstanceOf(ConstraintParser.MalformedConstraintException.class);
    }
}
```

Note the second test's `containsExactly` asserts the parser's own ordering. Sort quotas by club name so the order is stable regardless of how the model wrote them — a reply that reorders itself between two identical calls reads as a different answer. The input is written `Arsenal, Chelsea, Spurs` and comes back in that order, so the comparators follow it: `AT_LEAST` (Arsenal), `AT_MOST` (Chelsea), `EXACTLY` (Spurs).

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && set -a && source .env.test && set +a && ./gradlew test --tests '*ConstraintParserTest*'`
Expected: FAIL — classes do not exist.

- [ ] **Step 3: Create `SquadConstraints`**

```java
package fi.karivanttinen.fplassistant.squad;

import java.util.List;

/**
 * A parsed squad request. Every field is already validated for shape; whether it can be
 * satisfied is {@code Feasibility}'s question, not this record's.
 *
 * @param budget in tenths of a million, as everywhere else in this project
 * @param formation a forced formation such as "3-4-3", or null to let the solver choose
 */
public record SquadConstraints(
        List<String> mustInclude,
        List<String> mustExclude,
        List<ClubQuota> clubQuotas,
        int budget,
        int horizon,
        String formation
) {

    public enum Comparator { EXACTLY, AT_LEAST, AT_MOST }

    /**
     * How many players the user wants from one club. The comparator matters: an exact quota is
     * the most common source of infeasibility, and forcing "at least two" through an equality
     * manufactures refusals nobody asked for.
     */
    public record ClubQuota(String club, Comparator comparator, int count) {
    }

    /** Whether the budget is the from-scratch £100.0m, which is the only exactly-priced case. */
    public boolean isFromScratchBudget() {
        return budget == ConstraintParser.BUDGET_DEFAULT;
    }
}
```

- [ ] **Step 4: Create `ConstraintParser`**

```java
package fi.karivanttinen.fplassistant.squad;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Turns the strings a language model writes into constraints a solver can hold.
 *
 * <p>Club quotas carry a comparator because all three kinds get asked for: exactly three from
 * one club, at least two from another, at most one from a third. A bare number means exactly.
 *
 * <p>Anything malformed throws rather than being silently dropped. A constraint the user stated
 * and the tool ignored is the failure this whole feature exists to prevent.
 */
public final class ConstraintParser {

    public static final int BUDGET_DEFAULT = 1000;
    public static final int HORIZON_DEFAULT = 5;

    /** A club quota fragment: name, colon, optional comparator, digits. */
    private static final java.util.regex.Pattern QUOTA =
            java.util.regex.Pattern.compile("^(.+?):(>=|<=|=)?(\\d+)$");

    private ConstraintParser() {
    }

    public static class MalformedConstraintException extends RuntimeException {
        public MalformedConstraintException(String message) {
            super(message);
        }
    }

    public static SquadConstraints parse(String mustInclude, String mustExclude, String clubQuota,
                                         double budget, int nextN, String formation) {
        return new SquadConstraints(
                names(mustInclude),
                names(mustExclude),
                quotas(clubQuota),
                budget <= 0 ? BUDGET_DEFAULT : (int) Math.round(budget * 10),
                nextN <= 0 ? HORIZON_DEFAULT : nextN,
                formation == null || formation.isBlank() ? null : formation.trim());
    }

    private static List<String> names(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .toList();
    }

    /**
     * Sorted by club name so the order is stable however the model wrote them — an output that
     * reorders itself between two identical calls reads as a different answer.
     */
    private static List<SquadConstraints.ClubQuota> quotas(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(fragment -> !fragment.isEmpty())
                .map(ConstraintParser::quota)
                .sorted(Comparator.comparing(q -> q.club().toLowerCase(Locale.ROOT)))
                .toList();
    }

    private static SquadConstraints.ClubQuota quota(String fragment) {
        var matcher = QUOTA.matcher(fragment);
        if (!matcher.matches()) {
            throw new MalformedConstraintException(
                    "Could not read the club quota \"" + fragment + "\". Write it as"
                            + " \"Arsenal:3\", \"Arsenal:>=2\" or \"Arsenal:<=2\".");
        }
        String club = matcher.group(1).trim();
        if (club.isEmpty()) {
            throw new MalformedConstraintException(
                    "The club quota \"" + fragment + "\" names no club.");
        }
        SquadConstraints.Comparator comparator = switch (matcher.group(2) == null ? "=" : matcher.group(2)) {
            case ">=" -> SquadConstraints.Comparator.AT_LEAST;
            case "<=" -> SquadConstraints.Comparator.AT_MOST;
            default -> SquadConstraints.Comparator.EXACTLY;
        };
        return new SquadConstraints.ClubQuota(club, comparator, Integer.parseInt(matcher.group(3)));
    }
}
```

A leading `-` never matches `(\d+)`, so `Arsenal:-1` falls through to the malformed branch, which is what the negative-quota test asserts.

- [ ] **Step 5: Run the test — it passes**

Run: `cd backend && set -a && source .env.test && set +a && ./gradlew test --tests '*ConstraintParserTest*'`
Expected: PASS, 8 tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/squad/SquadConstraints.java \
        backend/src/main/java/fi/karivanttinen/fplassistant/squad/ConstraintParser.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/squad/ConstraintParserTest.java
git commit -m "feat(squad): parse club quotas with a comparator"
```

---

### Task 4: `SquadCandidate` and `CandidatePool`

The pool comes from `fpl_daily_price`, not from the tapas `player` table. That is what keeps issue #50 from recurring and what makes the tool work across the season changeover.

**Files:**
- Create: `backend/src/main/java/fi/karivanttinen/fplassistant/squad/SquadCandidate.java`
- Create: `backend/src/main/java/fi/karivanttinen/fplassistant/squad/CandidatePool.java`
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/squad/CandidatePoolIntegrationTest.java`

**Interfaces:**
- Consumes: `SeasonContext`, `SeasonResolver`, `DailyPriceSource` (Task 4 adds a package-visible accessor if needed — see Step 3).
- Produces:
  - `enum SquadCandidate.Tier { RANKABLE, BODY }`
  - `record SquadCandidate(int elementId, String webName, String fullName, String club, String clubShort, int elementType, int nowCost, double ownership, String status, String news, int teamId, double minutesInWindow, int teamFixturesInWindow, Map<String,Object> metrics, Tier tier)`
  - `CandidatePool.load(SeasonContext season, MetricWindow window, LocalDate captureDate)` → `List<SquadCandidate>`
  - `record CandidatePool.MetricWindow(int seasonId, String code, boolean isResolvedSeason, boolean beingPlayed, int windowLimit)`
  - `CandidatePool.metricWindow(SeasonContext)` → `MetricWindow`
  - `CandidatePool.latestCaptureDate()` → `LocalDate` (null when the daily table is empty)

- [ ] **Step 1: Write the failing integration test**

```java
package fi.karivanttinen.fplassistant.squad;

import java.time.LocalDate;
import java.util.List;

import fi.karivanttinen.fplassistant.testsupport.FictitiousTestData;
import fi.karivanttinen.fplassistant.testsupport.TapasSeed;
import fi.karivanttinen.fplassistant.tools.SeasonResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The candidate pool, against the isolated daily-price table.
 *
 * <p>Two tiers, and the difference between them is the whole point: a player with a real record
 * can be judged and started; a player without one is a body worth nothing, admitted only so the
 * cheapest legal bench slot stays reachable.
 */
@SpringBootTest
@ActiveProfiles("test")
class CandidatePoolIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 27);

    private static final int RANKABLE = 9501;
    private static final int BODY = 9502;
    private static final int INJURED = 9503;
    private static final int DOUBTFUL_NO_RECORD = 9504;

    /**
     * The season the pool actually filters on, read at runtime rather than hardcoded.
     *
     * <p>{@code CandidatePool.load} filters the daily table on
     * {@code seasonResolver.resolve().code()}, and the resolver returns whichever season holds
     * data — during the changeover that is the <b>previous</b> season, not the one on the
     * calendar. Seeding under a constant would leave every test looking at an empty pool.
     */
    String season;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CandidatePool pool;

    @Autowired
    SeasonResolver seasonResolver;

    @Value("${fpl.daily-price.table-name}")
    String table;

    @BeforeEach
    void refuseProductionAndSeed() {
        TapasSeed.ensure(jdbc);
        season = seasonResolver.resolve().code();
        FictitiousTestData.assertSafeSeason(season);
        assertThat(table).as("pool tests must use the isolated table").endsWith("_test");
        deleteTestRows();

        insert(RANKABLE, "Rankabletest", 55, 2, 30.0, "a", null);
        insert(BODY, "Bodytest", 40, 2, 0.2, "a", null);
        insert(INJURED, "Injuredtest", 45, 2, 1.0, "i", "Hamstring");
        insert(DOUBTFUL_NO_RECORD, "Doubtfultest", 40, 2, 0.1, "d", "Knock");
    }

    @AfterEach
    void cleanUp() {
        deleteTestRows();
    }

    private void deleteTestRows() {
        jdbc.update("DELETE FROM " + table + " WHERE season = ?", season);
    }

    private void insert(int elementId, String webName, int nowCost, int elementType,
                        double ownership, String status, String news) {
        jdbc.update("INSERT INTO " + table + " (season, price_date, element_id, web_name,"
                        + " full_name, team_id, team_short, team_name, element_type, now_cost,"
                        + " cost_change_event, cost_change_start, selected_by_percent, status, news)"
                        + " VALUES (?, ?, ?, ?, ?, 9001, 'ASH', 'Ashfield Rovers', ?, ?, 0, 0, ?, ?, ?)",
                season, TODAY, elementId, webName, webName + " Fictitious",
                elementType, nowCost, ownership, status, news);
    }

    @Test
    void anInjuredPlayerIsNotACandidateAtAll() {
        List<SquadCandidate> candidates = load();

        assertThat(candidates).extracting(SquadCandidate::elementId).doesNotContain(INJURED);
    }

    @Test
    void aDoubtfulPlayerWithNoRecordIsNotEvenABody() {
        // A body is worth buying only when it is at least a fit one.
        List<SquadCandidate> candidates = load();

        assertThat(candidates).extracting(SquadCandidate::elementId)
                .doesNotContain(DOUBTFUL_NO_RECORD);
    }

    @Test
    void aPlayerWithNoMinutesRecordIsAdmittedAsABody() {
        List<SquadCandidate> candidates = load();

        SquadCandidate body = candidates.stream()
                .filter(c -> c.elementId() == BODY).findFirst().orElseThrow();

        assertThat(body.tier()).isEqualTo(SquadCandidate.Tier.BODY);
        assertThat(body.nowCost()).isEqualTo(40);
    }

    @Test
    void carriesTheClubAndPriceFromTheDailyCaptureRatherThanFromTapas() {
        // The tapas player table is synced weekly and was measured 85 days stale. Nothing here
        // may read a price or an availability flag from it.
        SquadCandidate any = load().getFirst();

        assertThat(any.club()).isEqualTo("Ashfield Rovers");
        assertThat(any.clubShort()).isEqualTo("ASH");
    }

    @Test
    void theCaptureDateIsTheLatestOneNotToday() {
        // A missed 02:00 run must degrade to yesterday's capture, clearly labelled, rather than
        // returning nothing.
        assertThat(pool.latestCaptureDate()).isNotNull();
    }

    private List<SquadCandidate> load() {
        var season = seasonResolver.resolve();
        return pool.load(season, pool.metricWindow(season), pool.latestCaptureDate())
                .stream()
                .filter(c -> c.elementId() >= 9501 && c.elementId() <= 9504)
                .toList();
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && set -a && source .env.test && set +a && ./gradlew test --tests '*CandidatePoolIntegrationTest*'`
Expected: FAIL — `CandidatePool` does not exist.

- [ ] **Step 3: Create `SquadCandidate`**

```java
package fi.karivanttinen.fplassistant.squad;

import java.util.Map;

/**
 * One player the solver may choose, with everything needed to price him, place him and score
 * him.
 *
 * <p>Identity, club, price, ownership and availability come from {@code fpl_daily_price} — the
 * daily capture from FPL's own API. Metrics come from {@code player_fixture_stats}. Nothing here
 * is read from the tapas {@code player} table, which is synced weekly and goes stale.
 *
 * @param metrics the raw metric row ({@code element_type}, {@code xg90}, {@code xa90},
 *        {@code xgc_per_match}, {@code pts90}) as {@code ExpectedPoints.perNinety} expects it;
 *        empty for a {@link Tier#BODY}
 * @param minutesInWindow the player's minutes across the metric window
 * @param teamFixturesInWindow his club's fixtures across that window — the denominator that
 *        turns minutes into a rotation-aware expectation
 */
public record SquadCandidate(
        int elementId,
        String webName,
        String fullName,
        String club,
        String clubShort,
        int elementType,
        int nowCost,
        double ownership,
        String status,
        String news,
        int teamId,
        double minutesInWindow,
        int teamFixturesInWindow,
        Map<String, Object> metrics,
        Tier tier
) {

    /**
     * Whether this player can be judged or only bought.
     *
     * <p>{@link #RANKABLE} has a real minutes record, so his expected points mean something and
     * he may start. {@link #BODY} does not: he is asserted to be worth nothing, is labelled a
     * body in the output, and can never be in the starting XI. The distinction is what keeps the
     * cheapest bench slot reachable without the solver claiming a non-player is good.
     */
    public enum Tier { RANKABLE, BODY }

    public boolean isDoubtful() {
        return "d".equals(status);
    }
}
```

- [ ] **Step 4: Create `CandidatePool`**

```java
package fi.karivanttinen.fplassistant.squad;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import fi.karivanttinen.fplassistant.tools.SeasonContext;
import fi.karivanttinen.fplassistant.tools.SeasonResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Loads every player the solver may pick.
 *
 * <p><b>The pool is the daily capture, not the tapas squad table.</b> {@code fpl_daily_price}
 * already carries identity, club, position, price, ownership and availability, captured from
 * FPL's own API every morning. Reading them from tapas instead is issue #50, and would also
 * return nothing at all during the season changeover, when tapas has not synced the new season.
 *
 * <p>Metrics come from {@code player_fixture_stats}, which is tapas-owned and therefore may be
 * a season behind. When it is, players are bridged by lowercased {@code web_name} and any name
 * belonging to two players in either season is dropped rather than guessed — the same rule
 * {@code PickTools} uses, for the same reason.
 */
@Component
public class CandidatePool {

    /** Matches read per player when the metric season is the one being played: recent form. */
    static final int WINDOW_MATCHES = 10;

    /** Every gameweek of a finished season, which is read whole rather than by its run-in. */
    static final int WHOLE_SEASON = 60;

    /** Minutes needed inside the window to be judged at all. Two full matches. */
    static final int MIN_WINDOW_MINUTES = 180;

    /** Completed gameweeks the season being played needs before its own numbers are used. */
    static final int MIN_SAMPLE_GAMEWEEKS = 5;

    private final JdbcTemplate jdbc;
    private final SeasonResolver seasonResolver;
    private final String dailyTable;

    public CandidatePool(JdbcTemplate jdbc, SeasonResolver seasonResolver,
                         @Value("${fpl.daily-price.table-name}") String dailyTable) {
        this.jdbc = jdbc;
        this.seasonResolver = seasonResolver;
        this.dailyTable = dailyTable;
    }

    /** Which season the metrics come from, and how far back to read it. */
    public record MetricWindow(int seasonId, String code, boolean isResolvedSeason,
                               boolean beingPlayed, int windowLimit) {
    }

    public LocalDate latestCaptureDate() {
        return jdbc.queryForObject(
                "SELECT MAX(price_date) FROM " + dailyTable, LocalDate.class);
    }

    /**
     * The season being played once it holds a real sample, the previous one until then — the
     * same early-season rule the system prompt states, applied where the ordering is decided.
     */
    public MetricWindow metricWindow(SeasonContext season) {
        int played = completedGameweeks(season.seasonId());
        if (played >= MIN_SAMPLE_GAMEWEEKS) {
            return new MetricWindow(season.seasonId(), season.code(), true,
                    season.currentSeason(),
                    season.currentSeason() ? WINDOW_MATCHES : WHOLE_SEASON);
        }
        Optional<SeasonContext> previous = seasonResolver.previousSeason(season);
        return previous
                .map(p -> new MetricWindow(p.seasonId(), p.code(), false, false, WHOLE_SEASON))
                .orElseGet(() -> new MetricWindow(season.seasonId(), season.code(), true,
                        season.currentSeason(), WHOLE_SEASON));
    }

    private int completedGameweeks(int seasonId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM gameweek g
                WHERE g.season_id = ? AND g.finished = true
                  AND EXISTS (SELECT 1 FROM player_fixture_stats pfs
                              WHERE pfs.season_id = g.season_id AND pfs.gameweek = g.id)
                """, Integer.class, seasonId);
        return count == null ? 0 : count;
    }

    /**
     * Everyone available on the capture date, each tagged with the tier his record earns.
     *
     * <p>Availability is read from the capture, never from tapas: {@code a} is fit, {@code d} is
     * doubtful. A doubtful player with a record is admitted and flagged; a doubtful player
     * without one is not admitted at all, because a body is worth buying only when it is a fit
     * one.
     */
    public List<SquadCandidate> load(SeasonContext season, MetricWindow window,
                                     LocalDate captureDate) {
        if (captureDate == null) {
            return List.of();
        }
        String metricKey = window.isResolvedSeason() ? "mp.id::text" : "LOWER(mp.web_name)";
        String poolKey = window.isResolvedSeason() ? "d.element_id::text" : "LOWER(d.web_name)";
        String metricUnique = window.isResolvedSeason() ? "" : "WHERE " + nameIsUnique("mp");

        List<Map<String, Object>> rows = jdbc.queryForList("""
                WITH pool AS (
                    SELECT element_id, web_name, full_name, team_id, team_short, team_name,
                           element_type, now_cost, selected_by_percent, status, news
                    FROM %s
                    WHERE season = ? AND price_date = ? AND status IN ('a', 'd')
                ),
                recent AS (
                    SELECT pfs.player_id, pfs.player_team_id, pfs.minutes, pfs.total_points,
                           pfs.expected_goals AS xg, pfs.expected_assists AS xa,
                           pfs.expected_goals_conceded AS xgc,
                           ROW_NUMBER() OVER (PARTITION BY pfs.player_id
                                              ORDER BY pfs.gameweek DESC, pfs.fixture_id DESC) AS rn
                    FROM player_fixture_stats pfs
                    WHERE pfs.season_id = ?
                ),
                windowed AS (
                    SELECT player_id,
                           MIN(player_team_id) AS metric_team_id,
                           SUM(minutes) AS mins,
                           count(*) AS fixtures,
                           SUM(xg) * 90 / NULLIF(SUM(minutes), 0) AS xg90,
                           SUM(xa) * 90 / NULLIF(SUM(minutes), 0) AS xa90,
                           SUM(total_points) * 90.0 / NULLIF(SUM(minutes), 0) AS pts90,
                           SUM(xgc) FILTER (WHERE minutes >= 60)
                               / NULLIF(count(*) FILTER (WHERE minutes >= 60), 0) AS xgc_per_match
                    FROM recent
                    WHERE rn <= %d
                    GROUP BY player_id
                    HAVING SUM(minutes) >= %d
                ),
                metrics AS (
                    SELECT %s AS k, min(w.mins) AS mins, min(w.fixtures) AS fixtures,
                           min(w.xg90) AS xg90, min(w.xa90) AS xa90, min(w.pts90) AS pts90,
                           min(w.xgc_per_match) AS xgc_per_match
                    FROM windowed w
                    JOIN player mp ON mp.id = w.player_id AND mp.season_id = ?
                    %s
                    GROUP BY 1
                    HAVING count(*) = 1
                )
                SELECT pool.*, m.mins, m.fixtures, m.xg90, m.xa90, m.pts90, m.xgc_per_match
                FROM pool
                LEFT JOIN metrics m ON m.k = %s
                """.formatted(dailyTable, window.windowLimit(), MIN_WINDOW_MINUTES,
                        metricKey, metricUnique, poolKey)
                        .replace("d.element_id", "pool.element_id")
                        .replace("d.web_name", "pool.web_name"),
                season.code(), captureDate, window.seasonId(), window.seasonId());

        List<SquadCandidate> candidates = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            boolean rankable = row.get("mins") != null;
            String status = (String) row.get("status");
            if (!rankable && !"a".equals(status)) {
                continue;
            }
            candidates.add(toCandidate(row, rankable));
        }
        return candidates;
    }

    private static SquadCandidate toCandidate(Map<String, Object> row, boolean rankable) {
        Map<String, Object> metrics = new HashMap<>();
        if (rankable) {
            metrics.put("element_type", row.get("element_type"));
            metrics.put("xg90", row.get("xg90"));
            metrics.put("xa90", row.get("xa90"));
            metrics.put("pts90", row.get("pts90"));
            metrics.put("xgc_per_match", row.get("xgc_per_match"));
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
                asDouble(row.get("mins")),
                asInt(row.get("fixtures")),
                metrics,
                rankable ? SquadCandidate.Tier.RANKABLE : SquadCandidate.Tier.BODY);
    }

    private static int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    /** A name belonging to exactly one player in that row's own season. */
    private static String nameIsUnique(String alias) {
        return """
                NOT EXISTS (
                    SELECT 1 FROM player twin
                    WHERE twin.season_id = %s.season_id
                      AND LOWER(twin.web_name) = LOWER(%s.web_name)
                      AND twin.id <> %s.id
                )""".formatted(alias, alias, alias);
    }
}
```

The two `.replace(...)` calls exist because the pool CTE is aliased `pool` while `poolKey` is written against `d`. If that reads awkwardly to you, rename the key builder to emit `pool.` directly and delete both replaces — it is the same result and the cleaner one.

- [ ] **Step 5: Run the test — it passes**

Run: `cd backend && set -a && source .env.test && set +a && ./gradlew test --tests '*CandidatePoolIntegrationTest*'`
Expected: PASS, 5 tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/squad/SquadCandidate.java \
        backend/src/main/java/fi/karivanttinen/fplassistant/squad/CandidatePool.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/squad/CandidatePoolIntegrationTest.java
git commit -m "feat(squad): load the candidate pool from the daily price capture"
```

---

### Task 5: `HorizonScorer` — expected minutes, fixtures, score

This is where a per-90 figure becomes a per-fixture one, which is what stops the solver buying cheap players who do not play.

**Files:**
- Create: `backend/src/main/java/fi/karivanttinen/fplassistant/squad/HorizonScorer.java`
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/squad/HorizonScorerTest.java`

**Interfaces:**
- Consumes: `SquadCandidate`, `ExpectedPoints`.
- Produces:
  - `HorizonScorer.expectedMinutes(SquadCandidate)` → `double`
  - `HorizonScorer.fdrAdjust(int fdr)` → `double`
  - `HorizonScorer.score(SquadCandidate candidate, List<Integer> fixtureDifficulties)` → `double`
  - `HorizonScorer.fixtureDifficulties(int seasonId, int horizon)` → `Map<Integer, List<Integer>>` keyed by `team_id` (Spring bean method, needs `JdbcTemplate`)
  - Constants `XI_MIN_EXPECTED_MINUTES = 60`, `FDR_SWING = 0.1`.

- [ ] **Step 1: Write the failing test**

```java
package fi.karivanttinen.fplassistant.squad;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Scoring over a horizon rather than per 90.
 *
 * <p>The minutes ratio is the guard the whole feature depends on: without it the optimiser buys
 * the cheapest players whose per-90 numbers look adequate, which is the placeholder bug wearing
 * a real name.
 */
class HorizonScorerTest {

    private static SquadCandidate candidate(double minutes, int teamFixtures, double xg90) {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("element_type", 3);
        metrics.put("xg90", BigDecimal.valueOf(xg90));
        metrics.put("xa90", BigDecimal.ZERO);
        metrics.put("pts90", BigDecimal.ZERO);
        metrics.put("xgc_per_match", null);
        return new SquadCandidate(1, "Test", "Test Player", "Ashfield Rovers", "ASH", 3, 60,
                10.0, "a", null, 9001, minutes, teamFixtures, metrics,
                SquadCandidate.Tier.RANKABLE);
    }

    @Test
    void aNailedStarterKeepsNearlyHisWholePerNinetyValue() {
        double minutes = HorizonScorer.expectedMinutes(candidate(855, 10, 0.5));

        assertThat(minutes).isCloseTo(85.5, within(1e-9));
    }

    @Test
    void aRotatedPlayerKeepsAboutHalf() {
        double minutes = HorizonScorer.expectedMinutes(candidate(450, 10, 0.5));

        assertThat(minutes).isCloseTo(45.0, within(1e-9));
    }

    @Test
    void aBodyIsWorthNothingHoweverGoodHisRecordLooks() {
        SquadCandidate body = new SquadCandidate(2, "Body", "Body Player", "Ashfield Rovers",
                "ASH", 2, 40, 0.1, "a", null, 9001, 0, 0, Map.of(),
                SquadCandidate.Tier.BODY);

        assertThat(HorizonScorer.score(body, List.of(2, 2, 2))).isZero();
    }

    @Test
    void anEasyFixtureIsWorthMoreThanAHardOne() {
        SquadCandidate player = candidate(900, 10, 0.5);

        double easy = HorizonScorer.score(player, List.of(1));
        double hard = HorizonScorer.score(player, List.of(5));

        assertThat(easy).isGreaterThan(hard);
        assertThat(HorizonScorer.fdrAdjust(3)).isEqualTo(1.0);
        assertThat(HorizonScorer.fdrAdjust(1)).isCloseTo(1.2, within(1e-9));
        assertThat(HorizonScorer.fdrAdjust(5)).isCloseTo(0.8, within(1e-9));
    }

    @Test
    void aDoubleGameweekCountsTwiceBecauseItIsTwoFixtures() {
        SquadCandidate player = candidate(900, 10, 0.5);

        double single = HorizonScorer.score(player, List.of(3));
        double doubled = HorizonScorer.score(player, List.of(3, 3));

        assertThat(doubled).isCloseTo(single * 2, within(1e-9));
    }

    @Test
    void aBlankGameweekIsSimplyNoFixtureAndScoresNothing() {
        assertThat(HorizonScorer.score(candidate(900, 10, 0.5), List.of())).isZero();
    }

    @Test
    void aPlayerWhoseTeamPlayedNoFixturesScoresNothingRatherThanDividingByZero() {
        assertThat(HorizonScorer.expectedMinutes(candidate(300, 0, 0.5))).isZero();
    }

    @Test
    void minutesAreCappedAtNinetySoAnExtraTimeArtefactCannotInflateAScore() {
        // Team fixture counts come from the player's own rows; a mismatch must not produce a
        // player worth more than a full match.
        assertThat(HorizonScorer.expectedMinutes(candidate(1000, 10, 0.5))).isEqualTo(90.0);
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && set -a && source .env.test && set +a && ./gradlew test --tests '*HorizonScorerTest*'`
Expected: FAIL — `HorizonScorer` does not exist.

- [ ] **Step 3: Create `HorizonScorer`**

```java
package fi.karivanttinen.fplassistant.squad;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.karivanttinen.fplassistant.tools.ExpectedPoints;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Turns a per-90 expectation into what a player is worth over the next N fixtures.
 *
 * <p><b>Expected minutes are the guard the squad builder depends on.</b> Maximising per-90
 * points under a budget buys cheap players whose rate looks adequate and whose minutes do not
 * exist — the same failure as a placeholder, wearing a real name. Minutes divided by the club's
 * fixtures over the same window penalises rotation by construction.
 *
 * <p>The horizon is <b>summed, not averaged</b>. A double gameweek is literally two fixtures and
 * comes out right without special handling; a blank is no fixture and comes out as nothing.
 *
 * <p>The fixture adjustment is coarse: one multiplier on the whole score, where a fixture should
 * move the clean-sheet term more than the attacking terms. Issue #47 replaces it with a team
 * xG/xGC projection.
 */
@Component
public class HorizonScorer {

    /** Expected minutes per club fixture below which a player may not start. */
    public static final double XI_MIN_EXPECTED_MINUTES = 60;

    /** Score multiplier per FDR step away from a neutral 3. */
    public static final double FDR_SWING = 0.1;

    private static final int NEUTRAL_FDR = 3;

    private final JdbcTemplate jdbc;

    public HorizonScorer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Minutes the player can be expected to play in one fixture. Capped at a full match: the
     * fixture count comes from the player's own rows, and a mismatch must not make anyone worth
     * more than ninety minutes.
     */
    public static double expectedMinutes(SquadCandidate candidate) {
        if (candidate.teamFixturesInWindow() <= 0) {
            return 0;
        }
        return Math.min(90.0, candidate.minutesInWindow() / candidate.teamFixturesInWindow());
    }

    public static double fdrAdjust(int fdr) {
        return 1 + (NEUTRAL_FDR - fdr) * FDR_SWING;
    }

    /**
     * Expected FPL points across the given fixtures. A body scores nothing by definition — he is
     * bought for his price, and asserting anything else about him is what the tier exists to
     * prevent.
     */
    public static double score(SquadCandidate candidate, List<Integer> fixtureDifficulties) {
        if (candidate.tier() == SquadCandidate.Tier.BODY || fixtureDifficulties.isEmpty()) {
            return 0;
        }
        double perFixture = ExpectedPoints.perNinety(candidate.metrics())
                * expectedMinutes(candidate) / 90.0;
        double total = 0;
        for (int fdr : fixtureDifficulties) {
            total += perFixture * fdrAdjust(fdr);
        }
        return total;
    }

    /**
     * Each club's next {@code horizon} gameweeks of fixture difficulty, in kickoff order.
     *
     * <p>A club with two fixtures in one gameweek contributes two entries, which is what makes a
     * double gameweek score twice. Resolved once for every club rather than once per player: a
     * pool of six hundred players shares at most twenty clubs.
     */
    public Map<Integer, List<Integer>> fixtureDifficulties(int seasonId, int horizon) {
        Integer from = jdbc.queryForObject("""
                SELECT COALESCE(
                    (SELECT id FROM gameweek WHERE season_id = ? AND is_current = true ORDER BY id LIMIT 1),
                    (SELECT id FROM gameweek WHERE season_id = ? AND is_next = true ORDER BY id LIMIT 1),
                    (SELECT MIN(id) FROM gameweek WHERE season_id = ?))
                """, Integer.class, seasonId, seasonId, seasonId);
        if (from == null) {
            return Map.of();
        }

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT team_id, fdr FROM (
                    SELECT team_id, fdr,
                           ROW_NUMBER() OVER (PARTITION BY team_id
                                              ORDER BY gameweek, kickoff_time, fixture_id) AS rn
                    FROM (
                        SELECT id AS fixture_id, team_h AS team_id, team_h_difficulty AS fdr,
                               gameweek, kickoff_time
                        FROM fixture WHERE season_id = ? AND gameweek >= ?
                        UNION ALL
                        SELECT id, team_a, team_a_difficulty, gameweek, kickoff_time
                        FROM fixture WHERE season_id = ? AND gameweek >= ?
                    ) f
                ) ranked
                WHERE rn <= ?
                ORDER BY team_id, rn
                """, seasonId, from, seasonId, from, horizon);

        Map<Integer, List<Integer>> byTeam = new HashMap<>();
        for (Map<String, Object> row : rows) {
            int teamId = ((Number) row.get("team_id")).intValue();
            Object fdr = row.get("fdr");
            byTeam.computeIfAbsent(teamId, key -> new ArrayList<>())
                    .add(fdr instanceof Number number ? number.intValue() : NEUTRAL_FDR);
        }
        return byTeam;
    }
}
```

- [ ] **Step 4: Run the test — it passes**

Run: `cd backend && set -a && source .env.test && set +a && ./gradlew test --tests '*HorizonScorerTest*'`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/squad/HorizonScorer.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/squad/HorizonScorerTest.java
git commit -m "feat(squad): score expected points over a fixture horizon"
```

---

### Task 6: `Feasibility` — refuse before solving, with a reason

ojAlgo returns a state, not an explanation. Naming the binding constraint is our job, and it is done before the solve where the message can be precise.

**Files:**
- Create: `backend/src/main/java/fi/karivanttinen/fplassistant/squad/Feasibility.java`
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/squad/FeasibilityTest.java`

**Interfaces:**
- Consumes: `SquadCandidate`, `SquadConstraints`.
- Produces: `Feasibility.check(List<SquadCandidate> pool, SquadConstraints constraints, List<SquadCandidate> pinned)` → `Optional<String>` — the refusal, or empty when the pre-flight passes. Constants `SQUAD_SIZE = 15`, `MAX_PER_CLUB = 3`, `POSITION_QUOTAS = {0, 2, 5, 5, 3}`, `POSITION_NAMES = {"", "goalkeeper", "defender", "midfielder", "forward"}`.

- [ ] **Step 1: Write the failing test**

```java
package fi.karivanttinen.fplassistant.squad;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every way a request can be impossible, and the sentence each one earns.
 *
 * <p>"No solution" is not an answer a user can act on. Each check here exists so the refusal
 * names the constraint that binds and, where money is involved, the amount.
 */
class FeasibilityTest {

    private static SquadCandidate player(int id, String name, String club, int type, int cost) {
        return new SquadCandidate(id, name, name + " Fictitious", club, club.substring(0, 3),
                type, cost, 5.0, "a", null, club.hashCode(), 900, 10,
                Map.of("element_type", type, "xg90", 0.0, "xa90", 0.0, "pts90", 0.0),
                SquadCandidate.Tier.RANKABLE);
    }

    /** A pool wide enough that nothing fails for lack of players unless a test means it to. */
    private static List<SquadCandidate> deepPool() {
        List<SquadCandidate> pool = new ArrayList<>();
        int id = 1;
        String[] clubs = {"Ashfield", "Barnwell", "Coleridge", "Dunmore", "Eastvale",
                "Fallowfield", "Granton", "Harewood"};
        for (String club : clubs) {
            for (int type = 1; type <= 4; type++) {
                for (int n = 0; n < 3; n++) {
                    pool.add(player(id++, club + type + n, club, type, 40 + n));
                }
            }
        }
        return pool;
    }

    private static SquadConstraints constraints(List<SquadConstraints.ClubQuota> quotas,
                                                int budget) {
        return new SquadConstraints(List.of(), List.of(), quotas, budget, 5, null);
    }

    @Test
    void passesAPlainRequest() {
        Optional<String> refusal = Feasibility.check(deepPool(),
                constraints(List.of(), 1000), List.of());

        assertThat(refusal).isEmpty();
    }

    @Test
    void refusesAQuotaAboveTheGamesOwnThreePerClubLimit() {
        Optional<String> refusal = Feasibility.check(deepPool(), constraints(List.of(
                new SquadConstraints.ClubQuota("Ashfield", SquadConstraints.Comparator.EXACTLY, 4)),
                1000), List.of());

        assertThat(refusal).isPresent();
        assertThat(refusal.get()).contains("Ashfield").contains("3");
    }

    @Test
    void refusesContradictoryQuotasForOneClub() {
        Optional<String> refusal = Feasibility.check(deepPool(), constraints(List.of(
                new SquadConstraints.ClubQuota("Ashfield", SquadConstraints.Comparator.AT_LEAST, 3),
                new SquadConstraints.ClubQuota("Ashfield", SquadConstraints.Comparator.AT_MOST, 2)),
                1000), List.of());

        assertThat(refusal).isPresent();
        assertThat(refusal.get()).contains("Ashfield");
    }

    @Test
    void refusesWhenClubMinimumsCannotFitInFifteenPlaces() {
        List<SquadConstraints.ClubQuota> quotas = new ArrayList<>();
        String[] clubs = {"Ashfield", "Barnwell", "Coleridge", "Dunmore", "Eastvale", "Fallowfield"};
        for (String club : clubs) {
            quotas.add(new SquadConstraints.ClubQuota(club,
                    SquadConstraints.Comparator.EXACTLY, 3));
        }
        // Six clubs at exactly three is eighteen players.
        Optional<String> refusal = Feasibility.check(deepPool(), constraints(quotas, 1000),
                List.of());

        assertThat(refusal).isPresent();
        assertThat(refusal.get()).contains("18").contains("15");
    }

    @Test
    void refusesWhenPinsExceedTheClubLimit() {
        List<SquadCandidate> pinned = List.of(
                player(901, "One", "Ashfield", 2, 50),
                player(902, "Two", "Ashfield", 3, 50),
                player(903, "Three", "Ashfield", 3, 50),
                player(904, "Four", "Ashfield", 4, 50));

        Optional<String> refusal = Feasibility.check(deepPool(), constraints(List.of(), 1000),
                pinned);

        assertThat(refusal).isPresent();
        assertThat(refusal.get()).contains("Ashfield").contains("4");
    }

    @Test
    void refusesWhenPinsExceedAPositionQuota() {
        List<SquadCandidate> pinned = List.of(
                player(901, "Keeper1", "Ashfield", 1, 45),
                player(902, "Keeper2", "Barnwell", 1, 45),
                player(903, "Keeper3", "Coleridge", 1, 45));

        Optional<String> refusal = Feasibility.check(deepPool(), constraints(List.of(), 1000),
                pinned);

        assertThat(refusal).isPresent();
        assertThat(refusal.get()).contains("goalkeeper").contains("2");
    }

    @Test
    void refusesWhenThePinsAndTheCheapestCompletionExceedTheBudgetAndSaysByHowMuch() {
        List<SquadCandidate> pinned = List.of(
                player(901, "Rich1", "Ashfield", 4, 150),
                player(902, "Rich2", "Barnwell", 4, 150),
                player(903, "Rich3", "Coleridge", 4, 150));

        // Twelve more at £4.0m is 480; the pins are 450; 930 fits 1000 but not 800.
        Optional<String> refusal = Feasibility.check(deepPool(), constraints(List.of(), 800),
                pinned);

        assertThat(refusal).isPresent();
        assertThat(refusal.get()).contains("£");
    }

    @Test
    void countsTheActualPoolRatherThanAssumingAClubIsDeep() {
        List<SquadCandidate> thin = new ArrayList<>(deepPool());
        thin.removeIf(c -> c.club().equals("Ashfield") && c.elementId() % 2 == 0);
        long remaining = thin.stream().filter(c -> c.club().equals("Ashfield")).count();

        Optional<String> refusal = Feasibility.check(thin, constraints(List.of(
                new SquadConstraints.ClubQuota("Ashfield",
                        SquadConstraints.Comparator.EXACTLY, 3)), 1000), List.of());

        // The thinned club still has players; this asserts the check reads the pool, not a
        // constant. With six or more left it must pass.
        assertThat(remaining).isGreaterThanOrEqualTo(3);
        assertThat(refusal).isEmpty();
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && set -a && source .env.test && set +a && ./gradlew test --tests '*FeasibilityTest*'`
Expected: FAIL — `Feasibility` does not exist.

- [ ] **Step 3: Create `Feasibility`**

```java
package fi.karivanttinen.fplassistant.squad;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Everything checked before the integer program runs.
 *
 * <p>ojAlgo answers "infeasible" without saying which row caused it, and "no solution" is not
 * something a user can act on. These checks catch what people actually ask for that cannot be
 * had, and each returns a sentence naming the constraint — and, where money is involved, the
 * amount by which it fails.
 */
public final class Feasibility {

    public static final int SQUAD_SIZE = 15;
    public static final int MAX_PER_CLUB = 3;
    public static final int[] POSITION_QUOTAS = {0, 2, 5, 5, 3};
    public static final String[] POSITION_NAMES =
            {"", "goalkeeper", "defender", "midfielder", "forward"};

    private Feasibility() {
    }

    public static Optional<String> check(List<SquadCandidate> pool, SquadConstraints constraints,
                                         List<SquadCandidate> pinned) {
        return firstOf(
                () -> quotasWithinTheGamesLimit(constraints),
                () -> quotasAgreeWithEachOther(constraints),
                () -> quotaMinimumsFit(constraints),
                () -> pinsWithinTheClubLimit(pinned),
                () -> pinsWithinPositionQuotas(pinned),
                () -> clubsHaveEnoughPlayers(pool, constraints),
                () -> poolCanFillEveryPosition(pool),
                () -> budgetCoversThePinsAndTheCheapestCompletion(pool, constraints, pinned));
    }

    @SafeVarargs
    private static Optional<String> firstOf(java.util.function.Supplier<Optional<String>>... checks) {
        for (var check : checks) {
            Optional<String> refusal = check.get();
            if (refusal.isPresent()) {
                return refusal;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> quotasWithinTheGamesLimit(SquadConstraints constraints) {
        for (var quota : constraints.clubQuotas()) {
            boolean isFloor = quota.comparator() != SquadConstraints.Comparator.AT_MOST;
            if (isFloor && quota.count() > MAX_PER_CLUB) {
                return Optional.of(("You asked for %d players from %s, but FPL allows at most %d"
                        + " from any one club. Lower that quota and I can build the squad.")
                        .formatted(quota.count(), quota.club(), MAX_PER_CLUB));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> quotasAgreeWithEachOther(SquadConstraints constraints) {
        Map<String, Integer> floors = new HashMap<>();
        Map<String, Integer> ceilings = new HashMap<>();
        for (var quota : constraints.clubQuotas()) {
            String key = quota.club().toLowerCase(Locale.ROOT);
            switch (quota.comparator()) {
                case EXACTLY -> {
                    floors.merge(key, quota.count(), Math::max);
                    ceilings.merge(key, quota.count(), Math::min);
                }
                case AT_LEAST -> floors.merge(key, quota.count(), Math::max);
                case AT_MOST -> ceilings.merge(key, quota.count(), Math::min);
            }
        }
        for (var entry : floors.entrySet()) {
            Integer ceiling = ceilings.get(entry.getKey());
            if (ceiling != null && ceiling < entry.getValue()) {
                return Optional.of(("The quotas for %s contradict each other: at least %d and at"
                        + " most %d cannot both hold.")
                        .formatted(displayName(constraints, entry.getKey()),
                                entry.getValue(), ceiling));
            }
        }
        return Optional.empty();
    }

    private static String displayName(SquadConstraints constraints, String lowercased) {
        return constraints.clubQuotas().stream()
                .filter(q -> q.club().toLowerCase(Locale.ROOT).equals(lowercased))
                .map(SquadConstraints.ClubQuota::club)
                .findFirst().orElse(lowercased);
    }

    private static Optional<String> quotaMinimumsFit(SquadConstraints constraints) {
        Map<String, Integer> floors = new HashMap<>();
        for (var quota : constraints.clubQuotas()) {
            if (quota.comparator() != SquadConstraints.Comparator.AT_MOST) {
                floors.merge(quota.club().toLowerCase(Locale.ROOT), quota.count(), Math::max);
            }
        }
        int total = floors.values().stream().mapToInt(Integer::intValue).sum();
        if (total > SQUAD_SIZE) {
            return Optional.of(("Those club quotas require %d players and a squad holds %d."
                    + " Drop one of them and I can build the squad.")
                    .formatted(total, SQUAD_SIZE));
        }
        return Optional.empty();
    }

    private static Optional<String> pinsWithinTheClubLimit(List<SquadCandidate> pinned) {
        Map<String, Integer> byClub = new HashMap<>();
        for (SquadCandidate player : pinned) {
            byClub.merge(player.club(), 1, Integer::sum);
        }
        for (var entry : byClub.entrySet()) {
            if (entry.getValue() > MAX_PER_CLUB) {
                return Optional.of(("You named %d players from %s. FPL allows at most %d from any"
                        + " one club, so that squad cannot exist.")
                        .formatted(entry.getValue(), entry.getKey(), MAX_PER_CLUB));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> pinsWithinPositionQuotas(List<SquadCandidate> pinned) {
        int[] counts = new int[POSITION_QUOTAS.length];
        for (SquadCandidate player : pinned) {
            if (player.elementType() >= 1 && player.elementType() < counts.length) {
                counts[player.elementType()]++;
            }
        }
        for (int type = 1; type < counts.length; type++) {
            if (counts[type] > POSITION_QUOTAS[type]) {
                return Optional.of(("You named %d %ss and a squad holds %d.")
                        .formatted(counts[type], POSITION_NAMES[type], POSITION_QUOTAS[type]));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> clubsHaveEnoughPlayers(List<SquadCandidate> pool,
                                                          SquadConstraints constraints) {
        for (var quota : constraints.clubQuotas()) {
            if (quota.comparator() == SquadConstraints.Comparator.AT_MOST) {
                continue;
            }
            long available = pool.stream()
                    .filter(c -> c.club().equalsIgnoreCase(quota.club()))
                    .count();
            if (available < quota.count()) {
                return Optional.of(("You asked for %d players from %s, but only %d are available"
                        + " and fit to pick right now.")
                        .formatted(quota.count(), quota.club(), available));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> poolCanFillEveryPosition(List<SquadCandidate> pool) {
        for (int type = 1; type < POSITION_QUOTAS.length; type++) {
            final int position = type;
            long available = pool.stream().filter(c -> c.elementType() == position).count();
            if (available < POSITION_QUOTAS[type]) {
                return Optional.of(("There are only %d %ss available and a squad needs %d."
                        + " The player data may not have been captured yet.")
                        .formatted(available, POSITION_NAMES[type], POSITION_QUOTAS[type]));
            }
        }
        return Optional.empty();
    }

    /**
     * The one refusal that must carry a number. "You cannot afford this" is useless; "you are
     * £3.5m short" tells the user which pin to drop.
     */
    private static Optional<String> budgetCoversThePinsAndTheCheapestCompletion(
            List<SquadCandidate> pool, SquadConstraints constraints, List<SquadCandidate> pinned) {
        int spent = pinned.stream().mapToInt(SquadCandidate::nowCost).sum();

        int[] stillNeeded = POSITION_QUOTAS.clone();
        for (SquadCandidate player : pinned) {
            if (player.elementType() >= 1 && player.elementType() < stillNeeded.length) {
                stillNeeded[player.elementType()]--;
            }
        }

        List<Integer> pinnedIds = pinned.stream().map(SquadCandidate::elementId).toList();
        int cheapest = 0;
        for (int type = 1; type < stillNeeded.length; type++) {
            final int position = type;
            List<Integer> costs = new ArrayList<>(pool.stream()
                    .filter(c -> c.elementType() == position)
                    .filter(c -> !pinnedIds.contains(c.elementId()))
                    .map(SquadCandidate::nowCost)
                    .sorted()
                    .toList());
            for (int n = 0; n < stillNeeded[type] && n < costs.size(); n++) {
                cheapest += costs.get(n);
            }
        }

        int total = spent + cheapest;
        if (total > constraints.budget()) {
            return Optional.of(("Those players cost £%s and the cheapest legal squad around them"
                    + " comes to £%s, which is £%s over the £%s available. Drop or downgrade one"
                    + " of them and I can build the squad.")
                    .formatted(money(spent), money(total), money(total - constraints.budget()),
                            money(constraints.budget())));
        }
        return Optional.empty();
    }

    /** Tenths of a million as pounds. Pinned to ROOT: a comma decimal reads as thousands. */
    static String money(int tenths) {
        return String.format(Locale.ROOT, "%.1fm", tenths / 10.0);
    }
}
```

- [ ] **Step 4: Run the test — it passes**

Run: `cd backend && set -a && source .env.test && set +a && ./gradlew test --tests '*FeasibilityTest*'`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/squad/Feasibility.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/squad/FeasibilityTest.java
git commit -m "feat(squad): name the constraint that binds before solving"
```

---

### Task 7: `SquadSolver` and `SolvedSquad` — the rules of the game

The solver arrives in two tasks. This one holds only the rules FPL itself enforces, so the invariant test that guards the whole feature exists before any user constraint can complicate it.

**Files:**
- Create: `backend/src/main/java/fi/karivanttinen/fplassistant/squad/SolvedSquad.java`
- Create: `backend/src/main/java/fi/karivanttinen/fplassistant/squad/SquadSolver.java`
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/squad/SquadSolverRulesTest.java`

**Interfaces:**
- Consumes: `SquadCandidate`, `SquadConstraints`, `HorizonScorer`, ojAlgo.
- Produces:
  - `record SolvedSquad(List<SquadCandidate> squad, List<SquadCandidate> startingXi, List<SquadCandidate> bench, SquadCandidate captain, SquadCandidate viceCaptain, int totalCost, String formation, List<String> relaxed)`
  - `SquadSolver.solve(List<SquadCandidate> pool, Map<Integer,Double> scores, SquadConstraints constraints, List<SquadCandidate> pinned)` → `Optional<SolvedSquad>` — empty when the model is infeasible. `scores` is keyed by `elementId`.
  - Constants `XI_SIZE = 11`, `BENCH_WEIGHT = 0.1`, `MIN_DEF_IN_XI = 3`, `MIN_FWD_IN_XI = 1`, `GK_IN_XI = 1`.

- [ ] **Step 1: Write the failing invariant test**

```java
package fi.karivanttinen.fplassistant.squad;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The promise of the whole feature: a result satisfies every rule of the game, or there is no
 * result. There is no third outcome.
 *
 * <p>The randomised test is the important one. Any single hand-written case can be satisfied by
 * accident; a hundred random pools cannot.
 */
class SquadSolverRulesTest {

    private static final int[] QUOTAS = Feasibility.POSITION_QUOTAS;

    private static SquadCandidate player(int id, String club, int type, int cost) {
        return new SquadCandidate(id, "P" + id, "Player " + id, club, club.substring(0, 3),
                type, cost, 5.0, "a", null, club.hashCode(), 900, 10,
                Map.of("element_type", type), SquadCandidate.Tier.RANKABLE);
    }

    /** Twelve clubs, four positions, four price points — always solvable inside £100.0m. */
    private static List<SquadCandidate> pool(Random random) {
        List<SquadCandidate> pool = new ArrayList<>();
        int id = 1;
        for (int club = 0; club < 12; club++) {
            String name = "Club%02d".formatted(club);
            for (int type = 1; type <= 4; type++) {
                for (int n = 0; n < 4; n++) {
                    pool.add(player(id++, name, type, 40 + random.nextInt(60)));
                }
            }
        }
        return pool;
    }

    private static Map<Integer, Double> scores(List<SquadCandidate> pool, Random random) {
        Map<Integer, Double> scores = new HashMap<>();
        for (SquadCandidate c : pool) {
            scores.put(c.elementId(), random.nextDouble() * 30);
        }
        return scores;
    }

    private static SquadConstraints plain() {
        return new SquadConstraints(List.of(), List.of(), List.of(), 1000, 5, null);
    }

    @RepeatedTest(20)
    void everyResultObeysEveryRuleOfTheGame() {
        Random random = new Random();
        List<SquadCandidate> pool = pool(random);

        SolvedSquad solved = SquadSolver.solve(pool, scores(pool, random), plain(), List.of())
                .orElseThrow(() -> new AssertionError("a full pool inside budget must solve"));

        assertEveryRule(solved);
    }

    static void assertEveryRule(SolvedSquad solved) {
        assertThat(solved.squad()).hasSize(Feasibility.SQUAD_SIZE);
        assertThat(solved.squad()).extracting(SquadCandidate::elementId).doesNotHaveDuplicates();

        for (int type = 1; type <= 4; type++) {
            final int position = type;
            assertThat(solved.squad().stream().filter(c -> c.elementType() == position).count())
                    .as("squad %ss", Feasibility.POSITION_NAMES[type])
                    .isEqualTo(QUOTAS[type]);
        }

        Map<String, Long> byClub = new HashMap<>();
        for (SquadCandidate c : solved.squad()) {
            byClub.merge(c.club(), 1L, Long::sum);
        }
        assertThat(byClub.values()).allMatch(count -> count <= Feasibility.MAX_PER_CLUB);

        assertThat(solved.startingXi()).hasSize(SquadSolver.XI_SIZE);
        assertThat(solved.squad()).containsAll(solved.startingXi());
        assertThat(solved.bench()).hasSize(Feasibility.SQUAD_SIZE - SquadSolver.XI_SIZE);

        assertThat(count(solved.startingXi(), 1)).as("goalkeepers in the XI").isEqualTo(1);
        assertThat(count(solved.startingXi(), 2)).as("defenders in the XI")
                .isBetween(3L, 5L);
        assertThat(count(solved.startingXi(), 3)).as("midfielders in the XI")
                .isBetween(2L, 5L);
        assertThat(count(solved.startingXi(), 4)).as("forwards in the XI")
                .isBetween(1L, 3L);

        assertThat(count(solved.bench(), 1)).as("the second keeper is always benched")
                .isEqualTo(1);

        assertThat(solved.totalCost())
                .isEqualTo(solved.squad().stream().mapToInt(SquadCandidate::nowCost).sum());

        assertThat(solved.startingXi()).contains(solved.captain());
        assertThat(solved.startingXi()).contains(solved.viceCaptain());
        assertThat(solved.captain()).isNotEqualTo(solved.viceCaptain());
    }

    private static long count(List<SquadCandidate> players, int elementType) {
        return players.stream().filter(c -> c.elementType() == elementType).count();
    }

    @Test
    void aBodyIsNeverInTheStartingEleven() {
        Random random = new Random(7);
        List<SquadCandidate> pool = new ArrayList<>(pool(random));
        // A free body at every position, worth nothing but priced at the floor.
        int id = 9000;
        for (int type = 1; type <= 4; type++) {
            pool.add(new SquadCandidate(id++, "Body" + type, "Body " + type, "Bodytown", "BOD",
                    type, 1, 0.1, "a", null, 999, 0, 0, Map.of(),
                    SquadCandidate.Tier.BODY));
        }
        Map<Integer, Double> scores = scores(pool, random);
        pool.stream().filter(c -> c.tier() == SquadCandidate.Tier.BODY)
                .forEach(c -> scores.put(c.elementId(), 0.0));

        SolvedSquad solved = SquadSolver.solve(pool, scores, plain(), List.of()).orElseThrow();

        assertThat(solved.startingXi()).noneMatch(c -> c.tier() == SquadCandidate.Tier.BODY);
        assertEveryRule(solved);
    }

    @Test
    void reachesFiveTwoThreeWhenThatIsWhereThePointsAre() {
        // 5-2-3 is legal. FPL_RULES.md's enumerated formation list omits it and must not be
        // used to derive anything.
        Random random = new Random(11);
        List<SquadCandidate> pool = pool(random);
        Map<Integer, Double> scores = new HashMap<>();
        for (SquadCandidate c : pool) {
            scores.put(c.elementId(), switch (c.elementType()) {
                case 2, 4 -> 20.0;
                default -> 1.0;
            });
        }

        SolvedSquad solved = SquadSolver.solve(pool, scores, plain(), List.of()).orElseThrow();

        assertThat(count(solved.startingXi(), 2)).isEqualTo(5);
        assertThat(count(solved.startingXi(), 3)).isEqualTo(2);
        assertThat(count(solved.startingXi(), 4)).isEqualTo(3);
        assertThat(solved.formation()).isEqualTo("5-2-3");
    }

    @Test
    void returnsNothingRatherThanSomethingIllegalWhenTheBudgetCannotBeMet() {
        Random random = new Random(3);
        List<SquadCandidate> pool = pool(random);
        SquadConstraints broke = new SquadConstraints(List.of(), List.of(), List.of(), 100, 5, null);

        assertThat(SquadSolver.solve(pool, scores(pool, random), broke, List.of())).isEmpty();
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && set -a && source .env.test && set +a && ./gradlew test --tests '*SquadSolverRulesTest*'`
Expected: FAIL — `SquadSolver` does not exist.

- [ ] **Step 3: Create `SolvedSquad`**

```java
package fi.karivanttinen.fplassistant.squad;

import java.util.List;

/**
 * A squad the solver proved legal.
 *
 * @param bench in auto-substitution priority order: the reserve goalkeeper first, then the three
 *        outfield substitutes best-scoring first. Bench order is an output with meaning — FPL
 *        substitutes in this order and only when the resulting formation stays legal.
 * @param relaxed the structural defaults that had to give way, in the order they gave way, or
 *        empty. Rules of the game never appear here; they are never relaxed.
 */
public record SolvedSquad(
        List<SquadCandidate> squad,
        List<SquadCandidate> startingXi,
        List<SquadCandidate> bench,
        SquadCandidate captain,
        SquadCandidate viceCaptain,
        int totalCost,
        String formation,
        List<String> relaxed
) {
}
```

- [ ] **Step 4: Create `SquadSolver` with the rules of the game only**

```java
package fi.karivanttinen.fplassistant.squad;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.ojalgo.optimisation.ExpressionsBasedModel;
import org.ojalgo.optimisation.Optimisation;
import org.ojalgo.optimisation.Variable;

/**
 * Picks the squad and the starting XI as one integer program.
 *
 * <p>Two binary variables per candidate: {@code s} for membership of the fifteen, {@code x} for
 * membership of the starting XI. Writing the constraints as literal rows is the point of using a
 * solver at all — "three players from Arsenal" becomes an equality nothing can violate, rather
 * than a preference a language model may or may not honour.
 *
 * <p>The bench carries weight {@link #BENCH_WEIGHT} rather than zero. At zero the solver would
 * fill it with arbitrary cheap names, since they are all equally worthless to it; at a small
 * weight it prefers the ones who actually play, which is what auto-substitution needs.
 */
public final class SquadSolver {

    public static final int XI_SIZE = 11;
    public static final double BENCH_WEIGHT = 0.1;
    public static final int GK_IN_XI = 1;
    public static final int MIN_DEF_IN_XI = 3;
    public static final int MIN_FWD_IN_XI = 1;

    private SquadSolver() {
    }

    public static Optional<SolvedSquad> solve(List<SquadCandidate> pool,
                                              Map<Integer, Double> scores,
                                              SquadConstraints constraints,
                                              List<SquadCandidate> pinned) {
        return solve(pool, scores, constraints, pinned, List.of());
    }

    /**
     * @param relaxed the structural defaults already given up, recorded on the result. Task 8
     *        drives this; with the rules of the game alone it is always empty.
     */
    static Optional<SolvedSquad> solve(List<SquadCandidate> pool, Map<Integer, Double> scores,
                                       SquadConstraints constraints, List<SquadCandidate> pinned,
                                       List<String> relaxed) {
        if (pool.isEmpty()) {
            return Optional.empty();
        }
        ExpressionsBasedModel model = new ExpressionsBasedModel();
        List<Variable> inSquad = new ArrayList<>();
        List<Variable> inXi = new ArrayList<>();

        for (SquadCandidate candidate : pool) {
            double score = scores.getOrDefault(candidate.elementId(), 0.0);
            // The objective reads: a starter is worth his score, a bench player a tenth of it.
            inSquad.add(model.newVariable("s" + candidate.elementId()).binary()
                    .weight(score * BENCH_WEIGHT));
            inXi.add(model.newVariable("x" + candidate.elementId()).binary()
                    .weight(score * (1 - BENCH_WEIGHT)));
        }

        rulesOfTheGame(model, pool, inSquad, inXi, constraints);

        Optimisation.Result result = model.maximise();
        if (!result.getState().isFeasible()) {
            return Optional.empty();
        }
        return Optional.of(assemble(pool, scores, inSquad, inXi, result, relaxed));
    }

    /**
     * The rules FPL itself enforces. These are never relaxed — not for a user constraint, not to
     * rescue an infeasible model. A squad that breaks one of them cannot be entered.
     */
    private static void rulesOfTheGame(ExpressionsBasedModel model, List<SquadCandidate> pool,
                                       List<Variable> inSquad, List<Variable> inXi,
                                       SquadConstraints constraints) {
        var squadSize = model.newExpression("squad-size").level(Feasibility.SQUAD_SIZE);
        var xiSize = model.newExpression("xi-size").level(XI_SIZE);
        var budget = model.newExpression("budget").upper(constraints.budget());

        Map<Integer, org.ojalgo.optimisation.Expression> positionQuota = new HashMap<>();
        for (int type = 1; type < Feasibility.POSITION_QUOTAS.length; type++) {
            positionQuota.put(type, model.newExpression("position-" + type)
                    .level(Feasibility.POSITION_QUOTAS[type]));
        }

        var gkInXi = model.newExpression("xi-gk").level(GK_IN_XI);
        var defInXi = model.newExpression("xi-def").lower(MIN_DEF_IN_XI);
        var fwdInXi = model.newExpression("xi-fwd").lower(MIN_FWD_IN_XI);

        Map<String, org.ojalgo.optimisation.Expression> clubLimit = new HashMap<>();

        for (int i = 0; i < pool.size(); i++) {
            SquadCandidate candidate = pool.get(i);
            Variable s = inSquad.get(i);
            Variable x = inXi.get(i);

            squadSize.set(s, 1);
            xiSize.set(x, 1);
            budget.set(s, candidate.nowCost());

            var quota = positionQuota.get(candidate.elementType());
            if (quota != null) {
                quota.set(s, 1);
            }

            clubLimit.computeIfAbsent(candidate.club().toLowerCase(java.util.Locale.ROOT),
                    key -> model.newExpression("club-" + key).upper(Feasibility.MAX_PER_CLUB))
                    .set(s, 1);

            switch (candidate.elementType()) {
                case 1 -> gkInXi.set(x, 1);
                case 2 -> defInXi.set(x, 1);
                case 4 -> fwdInXi.set(x, 1);
                default -> { }
            }

            // The XI is a subset of the squad: x - s <= 0.
            model.newExpression("xi-subset-" + candidate.elementId()).upper(0)
                    .set(x, 1).set(s, -1);

            // A body is bought, never started. He is asserted to be worth nothing, and starting
            // him would be a claim about him that nothing supports.
            if (candidate.tier() == SquadCandidate.Tier.BODY) {
                x.level(0);
            }
        }
    }

    private static SolvedSquad assemble(List<SquadCandidate> pool, Map<Integer, Double> scores,
                                        List<Variable> inSquad, List<Variable> inXi,
                                        Optimisation.Result result, List<String> relaxed) {
        List<SquadCandidate> squad = new ArrayList<>();
        List<SquadCandidate> startingXi = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) {
            if (chosen(result, inSquad.get(i))) {
                squad.add(pool.get(i));
            }
            if (chosen(result, inXi.get(i))) {
                startingXi.add(pool.get(i));
            }
        }

        Comparator<SquadCandidate> byScore = Comparator
                .comparingDouble((SquadCandidate c) -> scores.getOrDefault(c.elementId(), 0.0))
                .reversed();
        startingXi.sort(Comparator.comparingInt(SquadCandidate::elementType).thenComparing(byScore));

        List<SquadCandidate> bench = new ArrayList<>(squad);
        bench.removeAll(startingXi);
        // The reserve keeper first — he can only ever replace the keeper — then the outfield
        // substitutes in the order FPL should try them.
        bench.sort(Comparator.comparingInt((SquadCandidate c) -> c.elementType() == 1 ? 0 : 1)
                .thenComparing(byScore));

        List<SquadCandidate> byMerit = new ArrayList<>(startingXi);
        byMerit.sort(byScore);
        SquadCandidate captain = byMerit.getFirst();
        // A different club where one is available: a postponed fixture otherwise takes both, and
        // then no player is doubled at all.
        SquadCandidate viceCaptain = byMerit.stream()
                .skip(1)
                .filter(c -> !c.club().equalsIgnoreCase(captain.club()))
                .findFirst()
                .orElse(byMerit.get(1));

        int totalCost = squad.stream().mapToInt(SquadCandidate::nowCost).sum();

        return new SolvedSquad(squad, startingXi, bench, captain, viceCaptain, totalCost,
                formation(startingXi), relaxed);
    }

    private static boolean chosen(Optimisation.Result result, Variable variable) {
        return result.doubleValue(variable.getIndex().index) > 0.5;
    }

    static String formation(List<SquadCandidate> startingXi) {
        long def = startingXi.stream().filter(c -> c.elementType() == 2).count();
        long mid = startingXi.stream().filter(c -> c.elementType() == 3).count();
        long fwd = startingXi.stream().filter(c -> c.elementType() == 4).count();
        return "%d-%d-%d".formatted(def, mid, fwd);
    }
}
```

The `chosen` helper uses `variable.getIndex().index`. If the smoke test in Task 2 showed a different way to read a variable's value out of a `Result`, use that instead — the smoke test is the authority on this API, not this plan.

- [ ] **Step 5: Run the test — it passes**

Run: `cd backend && set -a && source .env.test && set +a && ./gradlew test --tests '*SquadSolverRulesTest*'`
Expected: PASS, 23 tests (20 repetitions plus 3).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/squad/SolvedSquad.java \
        backend/src/main/java/fi/karivanttinen/fplassistant/squad/SquadSolver.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/squad/SquadSolverRulesTest.java
git commit -m "feat(squad): solve for a legal fifteen and a legal eleven"
```

---

### Task 8: User constraints and structural defaults

Now the rows the user asked for, and the three defaults that stop an unconstrained optimum from being a bad squad.

**Files:**
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/squad/SquadSolver.java`
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/squad/SquadSolverConstraintsTest.java`

**Interfaces:**
- Consumes: everything from Task 7.
- Produces: `SquadSolver.solveWithDefaults(List<SquadCandidate> pool, Map<Integer,Double> scores, SquadConstraints constraints, List<SquadCandidate> pinned, Map<Integer,Double> expectedMinutes)` → `Optional<SolvedSquad>`; constants `PREMIUM_THRESHOLD = 100`, `TEMPLATE_OWNERSHIP = 20.0`, `TEMPLATE_MIN_COUNT = 6`, `MIN_PREMIUMS = 1`, `MAX_PREMIUMS = 3`; relaxation labels `"template minimum"`, `"premium range"`, `"starters must play"`.

- [ ] **Step 1: Write the failing test**

```java
package fi.karivanttinen.fplassistant.squad;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The constraints a user states, and the defaults that apply when they state none.
 *
 * <p>The first group is the feature's promise: "three players from Manchester City" must produce
 * three players from Manchester City. The second exists because an unconstrained optimum is a
 * bad squad — points per million favour mid-price players, so the solver buys fifteen of them,
 * no premium, and the squad has neither a ceiling nor a captain.
 */
class SquadSolverConstraintsTest {

    private static SquadCandidate player(int id, String club, int type, int cost,
                                         double ownership) {
        return new SquadCandidate(id, "P" + id, "Player " + id, club, club.substring(0, 3),
                type, cost, ownership, "a", null, club.hashCode(), 900, 10,
                Map.of("element_type", type), SquadCandidate.Tier.RANKABLE);
    }

    private static List<SquadCandidate> pool() {
        List<SquadCandidate> pool = new ArrayList<>();
        int id = 1;
        for (int club = 0; club < 12; club++) {
            String name = "Club%02d".formatted(club);
            for (int type = 1; type <= 4; type++) {
                for (int n = 0; n < 4; n++) {
                    // One premium and one heavily owned player per club per position.
                    pool.add(player(id++, name, type, n == 0 ? 110 : 45 + n * 5,
                            n == 1 ? 40.0 : 2.0));
                }
            }
        }
        return pool;
    }

    private static Map<Integer, Double> flatScores(List<SquadCandidate> pool) {
        Map<Integer, Double> scores = new HashMap<>();
        for (SquadCandidate c : pool) {
            scores.put(c.elementId(), 10.0 + c.elementId() % 7);
        }
        return scores;
    }

    private static Map<Integer, Double> allNailed(List<SquadCandidate> pool) {
        Map<Integer, Double> minutes = new HashMap<>();
        for (SquadCandidate c : pool) {
            minutes.put(c.elementId(), 88.0);
        }
        return minutes;
    }

    private static SquadConstraints with(List<SquadConstraints.ClubQuota> quotas) {
        return new SquadConstraints(List.of(), List.of(), quotas, 1000, 5, null);
    }

    @Test
    void exactlyThreeMeansExactlyThree() {
        List<SquadCandidate> pool = pool();

        SolvedSquad solved = SquadSolver.solveWithDefaults(pool, flatScores(pool), with(List.of(
                        new SquadConstraints.ClubQuota("Club00",
                                SquadConstraints.Comparator.EXACTLY, 3))),
                List.of(), allNailed(pool)).orElseThrow();

        assertThat(solved.squad()).filteredOn(c -> c.club().equals("Club00")).hasSize(3);
        SquadSolverRulesTest.assertEveryRule(solved);
    }

    @Test
    void atLeastTwoIsAFloorAndNotACeiling() {
        List<SquadCandidate> pool = pool();

        SolvedSquad solved = SquadSolver.solveWithDefaults(pool, flatScores(pool), with(List.of(
                        new SquadConstraints.ClubQuota("Club01",
                                SquadConstraints.Comparator.AT_LEAST, 2))),
                List.of(), allNailed(pool)).orElseThrow();

        assertThat(solved.squad()).filteredOn(c -> c.club().equals("Club01"))
                .hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void atMostOneIsHonoured() {
        List<SquadCandidate> pool = pool();

        SolvedSquad solved = SquadSolver.solveWithDefaults(pool, flatScores(pool), with(List.of(
                        new SquadConstraints.ClubQuota("Club02",
                                SquadConstraints.Comparator.AT_MOST, 1))),
                List.of(), allNailed(pool)).orElseThrow();

        assertThat(solved.squad()).filteredOn(c -> c.club().equals("Club02"))
                .hasSizeLessThanOrEqualTo(1);
    }

    @Test
    void aPinnedPlayerIsAlwaysInTheSquad() {
        List<SquadCandidate> pool = pool();
        SquadCandidate pin = pool.stream().filter(c -> c.elementType() == 4).findFirst()
                .orElseThrow();

        SolvedSquad solved = SquadSolver.solveWithDefaults(pool, flatScores(pool), with(List.of()),
                List.of(pin), allNailed(pool)).orElseThrow();

        assertThat(solved.squad()).contains(pin);
        SquadSolverRulesTest.assertEveryRule(solved);
    }

    @Test
    void anExcludedPlayerIsNeverInTheSquad() {
        List<SquadCandidate> pool = pool();
        SquadCandidate unwanted = pool.getFirst();
        var constraints = new SquadConstraints(List.of(), List.of(unwanted.webName()), List.of(),
                1000, 5, null);

        SolvedSquad solved = SquadSolver.solveWithDefaults(pool, flatScores(pool), constraints,
                List.of(), allNailed(pool)).orElseThrow();

        assertThat(solved.squad()).doesNotContain(unwanted);
    }

    @Test
    void theSquadCarriesAtLeastOnePremiumByDefault() {
        List<SquadCandidate> pool = pool();

        SolvedSquad solved = SquadSolver.solveWithDefaults(pool, flatScores(pool), with(List.of()),
                List.of(), allNailed(pool)).orElseThrow();

        assertThat(solved.squad())
                .filteredOn(c -> c.nowCost() >= SquadSolver.PREMIUM_THRESHOLD)
                .hasSizeBetween(SquadSolver.MIN_PREMIUMS, SquadSolver.MAX_PREMIUMS);
    }

    @Test
    void theSquadCarriesSixTemplatePlayersByDefault() {
        List<SquadCandidate> pool = pool();

        SolvedSquad solved = SquadSolver.solveWithDefaults(pool, flatScores(pool), with(List.of()),
                List.of(), allNailed(pool)).orElseThrow();

        assertThat(solved.squad())
                .filteredOn(c -> c.ownership() >= SquadSolver.TEMPLATE_OWNERSHIP)
                .hasSizeGreaterThanOrEqualTo(SquadSolver.TEMPLATE_MIN_COUNT);
    }

    @Test
    void aPlayerWhoDoesNotPlayIsNeverInTheStartingEleven() {
        List<SquadCandidate> pool = pool();
        Map<Integer, Double> minutes = allNailed(pool);
        SquadCandidate benchwarmer = pool.get(5);
        minutes.put(benchwarmer.elementId(), 20.0);
        Map<Integer, Double> scores = flatScores(pool);
        scores.put(benchwarmer.elementId(), 1000.0);

        SolvedSquad solved = SquadSolver.solveWithDefaults(pool, scores, with(List.of()),
                List.of(), minutes).orElseThrow();

        assertThat(solved.startingXi()).doesNotContain(benchwarmer);
    }

    @Test
    void aBenchPlayerHasNoMinutesFloorAtAll() {
        // Cheapness competes against playing time on the bench, and sometimes cheapness wins.
        // That is deliberate: the four-million body is a real and correct pick.
        List<SquadCandidate> pool = new ArrayList<>(pool());
        SquadCandidate cheapNonPlayer = new SquadCandidate(9999, "Fodder", "Fodder Player",
                "Bodytown", "BOD", 2, 40, 0.1, "a", null, 999, 0, 0, Map.of(),
                SquadCandidate.Tier.BODY);
        pool.add(cheapNonPlayer);
        Map<Integer, Double> minutes = allNailed(pool);
        minutes.put(9999, 0.0);
        Map<Integer, Double> scores = flatScores(pool);
        scores.put(9999, 0.0);

        // 720 is chosen, not arbitrary: fifteen real players in this pool cost 750, so the body
        // is the only way to a legal squad; the cheapest squad containing it costs 701, so one
        // exists. Below 701 the model is infeasible and above 750 the body is never needed —
        // this test would then prove nothing.
        var tight = new SquadConstraints(List.of(), List.of(), List.of(), 720, 5, null);
        SolvedSquad solved = SquadSolver.solveWithDefaults(pool, scores, tight, List.of(), minutes)
                .orElseThrow();

        SquadSolverRulesTest.assertEveryRule(solved);
        assertThat(solved.squad()).contains(cheapNonPlayer);
        assertThat(solved.startingXi()).doesNotContain(cheapNonPlayer);
    }

    @Test
    void aStructuralDefaultGivesWayBeforeTheUsersOwnConstraintDoes() {
        // Nine players from three named clubs, none of them premium and none template-owned:
        // the defaults cannot all hold, and the user's quotas must survive anyway.
        List<SquadCandidate> pool = new ArrayList<>();
        int id = 1;
        for (int club = 0; club < 12; club++) {
            String name = "Club%02d".formatted(club);
            boolean named = club < 3;
            for (int type = 1; type <= 4; type++) {
                for (int n = 0; n < 4; n++) {
                    pool.add(player(id++, name, type, named ? 45 : (n == 0 ? 110 : 50),
                            named ? 1.0 : (n == 1 ? 40.0 : 2.0)));
                }
            }
        }
        var quotas = List.of(
                new SquadConstraints.ClubQuota("Club00", SquadConstraints.Comparator.EXACTLY, 3),
                new SquadConstraints.ClubQuota("Club01", SquadConstraints.Comparator.EXACTLY, 3),
                new SquadConstraints.ClubQuota("Club02", SquadConstraints.Comparator.EXACTLY, 3));

        SolvedSquad solved = SquadSolver.solveWithDefaults(pool, flatScores(pool), with(quotas),
                List.of(), allNailed(pool)).orElseThrow();

        for (String club : List.of("Club00", "Club01", "Club02")) {
            assertThat(solved.squad()).filteredOn(c -> c.club().equals(club)).hasSize(3);
        }
        SquadSolverRulesTest.assertEveryRule(solved);
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && set -a && source .env.test && set +a && ./gradlew test --tests '*SquadSolverConstraintsTest*'`
Expected: FAIL — `solveWithDefaults` does not exist.

- [ ] **Step 3: Add the constraints and the relaxation ladder to `SquadSolver`**

Add these constants beside the existing ones:

```java
    /** £10.0m, in the ×10 integers used everywhere. */
    public static final int PREMIUM_THRESHOLD = 100;
    public static final int MIN_PREMIUMS = 1;
    public static final int MAX_PREMIUMS = 3;
    public static final double TEMPLATE_OWNERSHIP = 20.0;
    public static final int TEMPLATE_MIN_COUNT = 6;

    static final String RELAXED_TEMPLATE = "template minimum";
    static final String RELAXED_PREMIUM = "premium range";
    static final String RELAXED_STARTERS_PLAY = "starters must play";
```

Add the entry point and the ladder:

```java
    /**
     * Solves with the three structural defaults applied, dropping them one at a time when they
     * cannot all hold.
     *
     * <p><b>User constraints never give way; structural defaults do.</b> The user asked for their
     * constraints and did not ask for the defaults, so a request that cannot satisfy both keeps
     * the request. The order is deliberate: the template minimum protects rank and is the
     * softest, the premium range shapes the squad, and the rule that starters must actually play
     * is given up last because a non-player in the XI is the failure the minutes model exists to
     * prevent.
     *
     * @param expectedMinutes per club fixture, keyed by element id, from {@code HorizonScorer}
     */
    public static Optional<SolvedSquad> solveWithDefaults(List<SquadCandidate> pool,
                                                          Map<Integer, Double> scores,
                                                          SquadConstraints constraints,
                                                          List<SquadCandidate> pinned,
                                                          Map<Integer, Double> expectedMinutes) {
        List<String> ladder = List.of(RELAXED_TEMPLATE, RELAXED_PREMIUM, RELAXED_STARTERS_PLAY);
        List<String> given = new ArrayList<>();

        for (int step = 0; step <= ladder.size(); step++) {
            Optional<SolvedSquad> solved = attempt(pool, scores, constraints, pinned,
                    expectedMinutes, List.copyOf(given));
            if (solved.isPresent()) {
                return solved;
            }
            if (step < ladder.size()) {
                given.add(ladder.get(step));
            }
        }
        return Optional.empty();
    }

    private static Optional<SolvedSquad> attempt(List<SquadCandidate> pool,
                                                 Map<Integer, Double> scores,
                                                 SquadConstraints constraints,
                                                 List<SquadCandidate> pinned,
                                                 Map<Integer, Double> expectedMinutes,
                                                 List<String> relaxed) {
        if (pool.isEmpty()) {
            return Optional.empty();
        }
        ExpressionsBasedModel model = new ExpressionsBasedModel();
        List<Variable> inSquad = new ArrayList<>();
        List<Variable> inXi = new ArrayList<>();
        for (SquadCandidate candidate : pool) {
            double score = scores.getOrDefault(candidate.elementId(), 0.0);
            inSquad.add(model.newVariable("s" + candidate.elementId()).binary()
                    .weight(score * BENCH_WEIGHT));
            inXi.add(model.newVariable("x" + candidate.elementId()).binary()
                    .weight(score * (1 - BENCH_WEIGHT)));
        }

        rulesOfTheGame(model, pool, inSquad, inXi, constraints);
        userConstraints(model, pool, inSquad, constraints, pinned);
        structuralDefaults(model, pool, inSquad, inXi, expectedMinutes, relaxed);

        Optimisation.Result result = model.maximise();
        if (!result.getState().isFeasible()) {
            return Optional.empty();
        }
        return Optional.of(assemble(pool, scores, inSquad, inXi, result, relaxed));
    }

    /**
     * The rows the user asked for. Row 10 — the club quota — is the point of the whole feature:
     * "three City players" is an equality the solver cannot violate, not a preference.
     */
    private static void userConstraints(ExpressionsBasedModel model, List<SquadCandidate> pool,
                                        List<Variable> inSquad, SquadConstraints constraints,
                                        List<SquadCandidate> pinned) {
        List<Integer> pinnedIds = pinned.stream().map(SquadCandidate::elementId).toList();
        List<String> excluded = constraints.mustExclude().stream()
                .map(name -> name.toLowerCase(java.util.Locale.ROOT)).toList();

        for (int i = 0; i < pool.size(); i++) {
            SquadCandidate candidate = pool.get(i);
            if (pinnedIds.contains(candidate.elementId())) {
                inSquad.get(i).level(1);
            } else if (excluded.contains(candidate.webName().toLowerCase(java.util.Locale.ROOT))) {
                inSquad.get(i).level(0);
            }
        }

        int quotaIndex = 0;
        for (var quota : constraints.clubQuotas()) {
            var row = model.newExpression("quota-" + quotaIndex++);
            switch (quota.comparator()) {
                case EXACTLY -> row.level(quota.count());
                case AT_LEAST -> row.lower(quota.count());
                case AT_MOST -> row.upper(quota.count());
            }
            for (int i = 0; i < pool.size(); i++) {
                if (pool.get(i).club().equalsIgnoreCase(quota.club())) {
                    row.set(inSquad.get(i), 1);
                }
            }
        }
    }

    /**
     * The three defaults read out of the strategy corpus, applied only where the user has not
     * spoken. Each is skipped once its label appears in {@code relaxed}.
     *
     * <p>Ownership appears here and nowhere in the scoring. In the score it would be a claim
     * about a player's quality, which is the claim the expected-points model exists to replace.
     * As a constraint it is structural risk management against the field, and the corpus states
     * it plainly: pure template finishes mid-rank, pure contrarian finishes bottom.
     */
    private static void structuralDefaults(ExpressionsBasedModel model, List<SquadCandidate> pool,
                                           List<Variable> inSquad, List<Variable> inXi,
                                           Map<Integer, Double> expectedMinutes,
                                           List<String> relaxed) {
        if (!relaxed.contains(RELAXED_PREMIUM)) {
            var premiums = model.newExpression("premiums")
                    .lower(MIN_PREMIUMS).upper(MAX_PREMIUMS);
            for (int i = 0; i < pool.size(); i++) {
                if (pool.get(i).nowCost() >= PREMIUM_THRESHOLD) {
                    premiums.set(inSquad.get(i), 1);
                }
            }
        }

        if (!relaxed.contains(RELAXED_TEMPLATE)) {
            var template = model.newExpression("template").lower(TEMPLATE_MIN_COUNT);
            for (int i = 0; i < pool.size(); i++) {
                if (pool.get(i).ownership() >= TEMPLATE_OWNERSHIP) {
                    template.set(inSquad.get(i), 1);
                }
            }
        }

        if (!relaxed.contains(RELAXED_STARTERS_PLAY)) {
            // The floor is on the XI, never on the fifteen. The bench carries no minutes
            // requirement at all: there, cheapness competes against playing time through the
            // objective's bench weight, and the cheapest body is sometimes the right pick.
            for (int i = 0; i < pool.size(); i++) {
                double minutes = expectedMinutes.getOrDefault(pool.get(i).elementId(), 0.0);
                if (minutes < HorizonScorer.XI_MIN_EXPECTED_MINUTES) {
                    inXi.get(i).level(0);
                }
            }
        }
    }
```

- [ ] **Step 4: Run the test — it passes**

Run: `cd backend && set -a && source .env.test && set +a && ./gradlew test --tests '*SquadSolverConstraintsTest*'`
Expected: PASS, 10 tests.

- [ ] **Step 5: Run the rules test too — Task 7's guarantees still hold**

Run: `cd backend && set -a && source .env.test && set +a && ./gradlew test --tests '*SquadSolver*'`
Expected: PASS, 33 tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/squad/SquadSolver.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/squad/SquadSolverConstraintsTest.java
git commit -m "feat(squad): honour club quotas and apply the structural defaults"
```

---

### Task 9: `SquadFormatter` — the output contract

The constraint receipt is the part that makes a misreading visible instead of silent.

**Files:**
- Create: `backend/src/main/java/fi/karivanttinen/fplassistant/squad/SquadFormatter.java`
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/squad/SquadFormatterTest.java`

**Interfaces:**
- Consumes: `SolvedSquad`, `SquadConstraints`, `SquadCandidate`.
- Produces: `SquadFormatter.format(SolvedSquad solved, SquadConstraints constraints, String priceProvenance, String metricProvenance, int excludedForNoRecord)` → `String`.

- [ ] **Step 1: Write the failing test**

```java
package fi.karivanttinen.fplassistant.squad;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reply's contract.
 *
 * <p>The constraint receipt is the important part: a user who asked for three Arsenal players
 * must be able to see, without counting, that they got three. The failure this whole feature
 * replaces was a reply that claimed a constraint it had not met.
 */
class SquadFormatterTest {

    private static SquadCandidate player(int id, String club, int type, int cost) {
        return new SquadCandidate(id, "Name" + id, "Full Name " + id, club, club.substring(0, 3),
                type, cost, 10.0, "a", null, club.hashCode(), 900, 10,
                Map.of("element_type", type), SquadCandidate.Tier.RANKABLE);
    }

    private static SolvedSquad solved() {
        List<SquadCandidate> squad = List.of(
                player(1, "Arsenal", 1, 50), player(2, "Brentford", 1, 40),
                player(3, "Arsenal", 2, 60), player(4, "Arsenal", 2, 55),
                player(5, "Chelsea", 2, 50), player(6, "Everton", 2, 45),
                player(7, "Fulham", 2, 40),
                player(8, "Chelsea", 3, 90), player(9, "Chelsea", 3, 75),
                player(10, "Liverpool", 3, 70), player(11, "Newcastle", 3, 60),
                player(12, "Everton", 3, 45),
                player(13, "Man City", 4, 150), player(14, "Brighton", 4, 80),
                player(15, "Fulham", 4, 45));
        // 1 GK, 4 DEF, 4 MID, 2 FWD
        List<SquadCandidate> startingXi = List.of(squad.get(0), squad.get(2), squad.get(3),
                squad.get(4), squad.get(5), squad.get(7), squad.get(8), squad.get(9),
                squad.get(10), squad.get(12), squad.get(13));
        List<SquadCandidate> bench = List.of(squad.get(1), squad.get(11), squad.get(6),
                squad.get(14));
        // 955 is the sum of the fifteen prices above. The formatter prints what it is given
        // rather than summing, but a fixture whose total disagrees with its own squad is a
        // fixture nobody can reason about.
        return new SolvedSquad(squad, startingXi, bench, squad.get(12), squad.get(7),
                955, "4-4-2", List.of());
    }

    private static SquadConstraints constraints() {
        return new SquadConstraints(List.of("Name13"), List.of(), List.of(
                new SquadConstraints.ClubQuota("Arsenal", SquadConstraints.Comparator.EXACTLY, 3),
                new SquadConstraints.ClubQuota("Chelsea", SquadConstraints.Comparator.AT_LEAST, 2)),
                1000, 5, null);
    }

    private static String format(SolvedSquad solved, SquadConstraints constraints) {
        return SquadFormatter.format(solved, constraints, "PRICE PROVENANCE",
                "METRIC PROVENANCE", 12);
    }

    @Test
    void carriesBothProvenanceLines() {
        String output = format(solved(), constraints());

        assertThat(output).contains("PRICE PROVENANCE").contains("METRIC PROVENANCE");
    }

    @Test
    void showsEveryConstraintBesideWhatWasAchieved() {
        String output = format(solved(), constraints());

        assertThat(output).contains("Arsenal: 3/3");
        assertThat(output).contains("Chelsea: 3/at least 2");
        assertThat(output).contains("Name13");
    }

    @Test
    void printsTheComputedTotalRatherThanAnEstimate() {
        String output = format(solved(), constraints());

        assertThat(output).contains("Total £95.5m");
        // "£4.5m" alone would match two player prices in this fixture; the money left is only
        // proved by the phrase that reports it.
        assertThat(output).contains("£4.5m left");
    }

    @Test
    void namesTheFormationAndTheBenchOrder() {
        String output = format(solved(), constraints());

        assertThat(output).contains("4-4-2");
        assertThat(output).contains("Bench");
        assertThat(output.indexOf("Name2")).isGreaterThan(output.indexOf("Bench"));
    }

    @Test
    void namesTheCaptainAndTheViceCaptain() {
        String output = format(solved(), constraints());

        assertThat(output).contains("Captain").contains("Vice-captain");
    }

    @Test
    void saysHowManyPlayersWereSetAsideAndThatTheyCanBePinned() {
        String output = format(solved(), constraints());

        assertThat(output).contains("12");
        assertThat(output).containsIgnoringCase("name them");
    }

    @Test
    void warnsThatOwnedPlayersArePricedAsPurchasesWhenTheBudgetIsNotTheFromScratchHundred() {
        var wildcard = new SquadConstraints(List.of(), List.of(), List.of(), 1017, 5, null);

        String output = format(solved(), wildcard);

        assertThat(output).containsIgnoringCase("selling price");
    }

    @Test
    void saysNothingAboutSellingPricesForAFromScratchBuild() {
        String output = format(solved(), constraints());

        assertThat(output).doesNotContainIgnoringCase("selling price");
    }

    @Test
    void saysWhichStructuralDefaultGaveWay() {
        SolvedSquad relaxed = new SolvedSquad(solved().squad(), solved().startingXi(),
                solved().bench(), solved().captain(), solved().viceCaptain(), 955, "4-4-2",
                List.of(SquadSolver.RELAXED_TEMPLATE));

        String output = format(relaxed, constraints());

        assertThat(output).contains(SquadSolver.RELAXED_TEMPLATE);
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && set -a && source .env.test && set +a && ./gradlew test --tests '*SquadFormatterTest*'`
Expected: FAIL — `SquadFormatter` does not exist.

- [ ] **Step 3: Create `SquadFormatter`**

```java
package fi.karivanttinen.fplassistant.squad;

import java.util.List;
import java.util.Locale;

/**
 * Renders a solved squad.
 *
 * <p><b>The constraint receipt is the point.</b> The failure this feature replaces was a reply
 * that stated a constraint it had not met — "Arsenal: Gabriel, Calafiori, £4.5m defender" — and
 * a user cannot be expected to audit fifteen rows by hand. Every constraint is printed beside
 * what was actually achieved.
 *
 * <p>Every decimal is pinned to {@link Locale#ROOT}: under a Finnish default locale a comma
 * decimal separator reads as a thousands separator, and £8,0m looks like eighty million.
 */
public final class SquadFormatter {

    private SquadFormatter() {
    }

    public static String format(SolvedSquad solved, SquadConstraints constraints,
                                String priceProvenance, String metricProvenance,
                                int excludedForNoRecord) {
        StringBuilder out = new StringBuilder();
        out.append(priceProvenance).append('\n').append(metricProvenance).append("\n\n");

        out.append("Constraints\n");
        for (String name : constraints.mustInclude()) {
            out.append("  ").append(name).append(": included ✓\n");
        }
        for (String name : constraints.mustExclude()) {
            out.append("  ").append(name).append(": excluded ✓\n");
        }
        for (var quota : constraints.clubQuotas()) {
            long achieved = solved.squad().stream()
                    .filter(c -> c.club().equalsIgnoreCase(quota.club()))
                    .count();
            out.append("  ").append(quota.club()).append(": ").append(achieved).append('/')
                    .append(describe(quota)).append(" ✓\n");
        }
        out.append("  Budget: £").append(money(solved.totalCost())).append(" of £")
                .append(money(constraints.budget())).append(" ✓\n");
        if (!solved.relaxed().isEmpty()) {
            out.append("  Relaxed to fit your request: ")
                    .append(String.join(", ", solved.relaxed())).append('\n');
        }
        out.append('\n');

        out.append("Starting XI (").append(solved.formation()).append(")\n");
        for (SquadCandidate player : solved.startingXi()) {
            out.append("  ").append(line(player, solved)).append('\n');
        }

        out.append("\nBench, in substitution order\n");
        int order = 1;
        for (SquadCandidate player : solved.bench()) {
            String prefix = player.elementType() == 1 ? "  GK " : "  " + order++ + ". ";
            out.append(prefix).append(line(player, solved)).append('\n');
        }

        out.append("\nCaptain: ").append(solved.captain().webName())
                .append(" (").append(solved.captain().clubShort()).append(")\n");
        out.append("Vice-captain: ").append(solved.viceCaptain().webName())
                .append(" (").append(solved.viceCaptain().clubShort()).append(")\n");

        out.append("\nTotal £").append(money(solved.totalCost())).append(", £")
                .append(money(constraints.budget() - solved.totalCost())).append(" left.\n");

        if (!constraints.isFromScratchBudget()) {
            out.append("This budget is not the from-scratch £100.0m, so it looks like a wildcard:"
                    + " every player here is priced as a fresh purchase, because FPL does not"
                    + " publish a manager's selling price. Selling returns the purchase price plus"
                    + " half the profit, so a squad you already part-own costs less than this in"
                    + " reality — the figure is conservative, never optimistic.\n");
        }

        if (excludedForNoRecord > 0) {
            out.append(excludedForNoRecord)
                    .append(" players were left out of the ranking because they have too little"
                            + " playing history to judge — new signings, and players back from"
                            + " long injuries. Name them and I will build around them anyway.\n");
        }

        out.append("Defensive-contribution points, save points and penalty saves are not in these"
                + " numbers, so goalkeepers and ball-winning defenders are understated.\n");

        return out.toString();
    }

    private static String describe(SquadConstraints.ClubQuota quota) {
        return switch (quota.comparator()) {
            case EXACTLY -> String.valueOf(quota.count());
            case AT_LEAST -> "at least " + quota.count();
            case AT_MOST -> "at most " + quota.count();
        };
    }

    private static String line(SquadCandidate player, SolvedSquad solved) {
        StringBuilder line = new StringBuilder();
        line.append(Feasibility.POSITION_NAMES[player.elementType()].substring(0, 3).toUpperCase(Locale.ROOT))
                .append("  ").append(player.webName())
                .append(" (").append(player.clubShort()).append(") £")
                .append(money(player.nowCost()));
        if (player.tier() == SquadCandidate.Tier.BODY) {
            line.append(" — bench body, no playing record, worth nothing in this model");
        }
        if (player.isDoubtful()) {
            line.append(" — DOUBTFUL: ").append(player.news());
        }
        return line.toString();
    }

    static String money(int tenths) {
        return String.format(Locale.ROOT, "%.1fm", tenths / 10.0);
    }
}
```

- [ ] **Step 4: Run the test — it passes**

Run: `cd backend && set -a && source .env.test && set +a && ./gradlew test --tests '*SquadFormatterTest*'`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/squad/SquadFormatter.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/squad/SquadFormatterTest.java
git commit -m "feat(squad): print every constraint beside what was achieved"
```

---

### Task 10: `SquadTools`, name resolution, prompt routing, `AGENTS.md`

The tool itself, and the acceptance test that reproduces the request this whole feature exists because of.

**Files:**
- Create: `backend/src/main/java/fi/karivanttinen/fplassistant/squad/SquadTools.java`
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/tools/DailyPriceSource.java` — widen `provenance(String, LocalDate, LocalDate)` from package-private to `public`, one word, no behaviour change
- Modify: `backend/src/main/resources/prompts/system-prompt.st`
- Modify: `AGENTS.md`
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/squad/SquadToolsIntegrationTest.java`

**Interfaces:**
- Consumes: everything above.
- Produces: `SquadTools.buildSquad(String mustInclude, String mustExclude, String clubQuota, double budget, int nextN, String formation)` → `String`. Implements `FplTool`, so `ChatService` registers it automatically.

- [ ] **Step 1: Write the failing integration test**

```java
package fi.karivanttinen.fplassistant.squad;

import java.time.LocalDate;

import fi.karivanttinen.fplassistant.testsupport.FictitiousTestData;
import fi.karivanttinen.fplassistant.testsupport.TapasSeed;
import fi.karivanttinen.fplassistant.tools.SeasonResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tool end to end, and the case it was built for.
 *
 * <p>The acceptance test reproduces the request that produced two answers containing players
 * who do not exist, a club constraint claimed but not met, and a budget total that did not match
 * its own table. It must now produce a real squad or a precise refusal — never a placeholder.
 */
@SpringBootTest
@ActiveProfiles("test")
class SquadToolsIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 27);
    private static final int FIRST_ID = 9600;

    /**
     * The season the tool actually filters on, read at runtime rather than hardcoded — see the
     * same field on {@code CandidatePoolIntegrationTest}. The resolver returns whichever season
     * holds data, which during the changeover is not the one on the calendar.
     */
    String season;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SquadTools tools;

    @Autowired
    SeasonResolver seasonResolver;

    @Value("${fpl.daily-price.table-name}")
    String table;

    @BeforeEach
    void refuseProductionAndSeedALeague() {
        TapasSeed.ensure(jdbc);
        season = seasonResolver.resolve().code();
        FictitiousTestData.assertSafeSeason(season);
        assertThat(table).as("squad tests must use the isolated table").endsWith("_test");
        deleteTestRows();

        // Twelve clubs, four of each position, priced so a legal squad exists inside £100.0m.
        int id = FIRST_ID;
        String[] clubs = {"Ashfield Rovers", "Barnwell Town", "Coleridge City", "Dunmore Athletic",
                "Eastvale United", "Fallowfield FC", "Granton Park", "Harewood Albion",
                "Ilkeston Vale", "Jarrow Wanderers", "Kelvedon Town", "Langford City"};
        for (int c = 0; c < clubs.length; c++) {
            for (int type = 1; type <= 4; type++) {
                for (int n = 0; n < 4; n++) {
                    insert(id, "P%d".formatted(id), clubs[c], "C%02d".formatted(c), type,
                            n == 0 ? 105 : 40 + n * 5, n == 1 ? 35.0 : 1.5);
                    id++;
                }
            }
        }
    }

    @AfterEach
    void cleanUp() {
        deleteTestRows();
    }

    private void deleteTestRows() {
        jdbc.update("DELETE FROM " + table + " WHERE season = ?", season);
    }

    private void insert(int elementId, String webName, String club, String clubShort,
                        int elementType, int nowCost, double ownership) {
        jdbc.update("INSERT INTO " + table + " (season, price_date, element_id, web_name,"
                        + " full_name, team_id, team_short, team_name, element_type, now_cost,"
                        + " cost_change_event, cost_change_start, selected_by_percent, status, news)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, 'a', NULL)",
                season, TODAY, elementId, webName, webName + " Fictitious",
                club.hashCode() & 0x7fff, clubShort, club, elementType, nowCost, ownership);
    }

    @Test
    void aPlainRequestReturnsFifteenRealPlayersAndAComputedTotal() {
        String output = tools.buildSquad("", "", "", 100.0, 5, "");

        assertThat(output).doesNotContainIgnoringCase("placeholder");
        assertThat(output).contains("Starting XI");
        assertThat(output).contains("Bench");
        assertThat(output).contains("Captain");
    }

    @Test
    void threeFromEachOfThreeClubsIsHonouredExactly() {
        String output = tools.buildSquad("", "",
                "Ashfield Rovers:3, Barnwell Town:3, Coleridge City:3", 100.0, 5, "");

        assertThat(output).contains("Ashfield Rovers: 3/3");
        assertThat(output).contains("Barnwell Town: 3/3");
        assertThat(output).contains("Coleridge City: 3/3");
    }

    @Test
    void anImpossibleClubQuotaIsRefusedByNameRatherThanFudged() {
        String output = tools.buildSquad("", "", "Ashfield Rovers:4", 100.0, 5, "");

        assertThat(output).contains("Ashfield Rovers");
        assertThat(output).contains("3");
        assertThat(output).doesNotContain("Starting XI");
    }

    @Test
    void anUnknownNameIsRefusedRatherThanQuietlyDropped() {
        String output = tools.buildSquad("Nobodyatall", "", "", 100.0, 5, "");

        assertThat(output).containsIgnoringCase("Nobodyatall");
        assertThat(output).doesNotContain("Starting XI");
    }

    @Test
    void anAmbiguousNameListsTheMatchesAndStops() {
        insert(9800, "Ambiguoustest", "Ashfield Rovers", "C00", 3, 60, 2.0);
        insert(9801, "Ambiguoustest", "Barnwell Town", "C01", 3, 65, 2.0);

        String output = tools.buildSquad("Ambiguoustest", "", "", 100.0, 5, "");

        assertThat(output).containsIgnoringCase("Ambiguoustest");
        assertThat(output).doesNotContain("Starting XI");
    }

    @Test
    void aNameWrittenWithoutItsAccentStillResolves() {
        insert(9810, "Jõao", "Ashfield Rovers", "C00", 4, 76, 5.0);

        String output = tools.buildSquad("Joao", "", "", 100.0, 5, "");

        assertThat(output).contains("Starting XI");
        assertThat(output).contains("Jõao");
    }

    @Test
    void aMalformedQuotaIsExplainedRatherThanIgnored() {
        String output = tools.buildSquad("", "", "Ashfield Rovers:three", 100.0, 5, "");

        assertThat(output).contains("Ashfield Rovers:three");
        assertThat(output).doesNotContain("Starting XI");
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && set -a && source .env.test && set +a && ./gradlew test --tests '*SquadToolsIntegrationTest*'`
Expected: FAIL — `SquadTools` does not exist.

- [ ] **Step 3: Create `SquadTools`**

```java
package fi.karivanttinen.fplassistant.squad;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import fi.karivanttinen.fplassistant.tools.DailyPriceSource;
import fi.karivanttinen.fplassistant.tools.FplTool;
import fi.karivanttinen.fplassistant.tools.SeasonContext;
import fi.karivanttinen.fplassistant.tools.SeasonResolver;
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
            LocalDate captured = pool.latestCaptureDate();
            if (captured == null) {
                return "No player prices have been captured yet, so I cannot build a squad."
                        + " Tell the user the daily price capture has not run.";
            }
            CandidatePool.MetricWindow window = pool.metricWindow(season);
            List<SquadCandidate> candidates = pool.load(season, window, captured);
            if (candidates.isEmpty()) {
                return "No players are available to pick from today's capture.";
            }

            List<SquadCandidate> pinned = new ArrayList<>();
            for (String name : constraints.mustInclude()) {
                Resolution resolved = resolve(name, candidates);
                if (resolved.refusal() != null) {
                    return resolved.refusal();
                }
                pinned.add(resolved.player());
            }

            Optional<String> refusal = Feasibility.check(candidates, constraints, pinned);
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
                    candidates, scores, constraints, pinned, minutes);
            if (solved.isEmpty()) {
                return "Those constraints cannot all be met at once, and I could not narrow it to"
                        + " a single one. Try loosening the club quotas or the budget.";
            }

            int excluded = (int) candidates.stream()
                    .filter(c -> c.tier() == SquadCandidate.Tier.BODY).count();

            return SquadFormatter.format(solved.get(), constraints,
                    DailyPriceSource.provenance(season.code(), captured, LocalDate.now()),
                    window.isResolvedSeason() ? season.provenance()
                            : season.previousSeasonProvenance(false),
                    excluded);
        } catch (DataAccessException e) {
            log.error("buildSquad failed", e);
            return "Squad data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    private record Resolution(SquadCandidate player, String refusal) {
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
                        .append(SquadFormatter.money(match.nowCost())).append(')');
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
```

`DailyPriceSource.provenance(String, LocalDate, LocalDate)` is currently package-private in `fi.karivanttinen.fplassistant.tools`. Widen it to `public` — one word, no behaviour change — so this package can use it. Do not copy the method.

- [ ] **Step 4: Run the test — it passes**

Run: `cd backend && set -a && source .env.test && set +a && ./gradlew test --tests '*SquadToolsIntegrationTest*'`
Expected: PASS, 7 tests.

- [ ] **Step 5: Route the model to it**

In `backend/src/main/resources/prompts/system-prompt.st`, find the section that routes pick questions to `getDifferentialPicks` and `getCaptainCandidates`, and add beside it:

```
- Building a whole squad — a wildcard, a free hit, a pre-season team, "build me a team", or
  any request that states a squad constraint such as "three Arsenal players" — call
  buildSquad. Never assemble a squad yourself. FPL's rules (15 players as 2 GK, 5 DEF, 5 MID,
  3 FWD, at most 3 from one club, inside the budget) cannot be satisfied reliably in prose,
  and a squad with a made-up player or a broken club limit cannot be entered into the game.
  If buildSquad refuses, relay its reason as written — it names the constraint that binds.
  Never fill a squad slot with a description like "a cheap Arsenal defender": if you do not
  have a name from a tool, you do not have a player.
```

- [ ] **Step 6: Record the provenance exception in `AGENTS.md`**

In the Conventions section, beside the existing `MyTeamTools` and daily-capture exceptions, add:

```markdown
- **`SquadTools.buildSquad` is a fourth documented two-line-provenance exception**, alongside
  the my-team tools. Its candidate pool — identity, club, price, ownership, availability —
  comes from `fpl_daily_price` and carries `DailyPriceSource.provenance()`; its metrics come
  from `player_fixture_stats` and carry `SeasonContext.provenance()`. The two sources have
  different freshness and, during the changeover, different seasons, so a single banner would
  attribute one of them wrongly. Building the pool from the daily capture rather than the
  tapas `player` table is deliberate and is what keeps issue #50 from recurring here: it also
  means the tool works while tapas has not synced the season being played. Do not fold this
  into the single-banner convention.
```

- [ ] **Step 7: Run the whole suite**

Run: `cd backend && set -a && source .env.test && set +a && ./gradlew cleanTest test`
Expected: PASS. Roughly 429 + 82 = 511 tests.

- [ ] **Step 8: Commit and open the pull request**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/squad/SquadTools.java \
        backend/src/main/java/fi/karivanttinen/fplassistant/tools/DailyPriceSource.java \
        backend/src/main/resources/prompts/system-prompt.st \
        backend/src/test/java/fi/karivanttinen/fplassistant/squad/SquadToolsIntegrationTest.java \
        AGENTS.md
git commit -m "feat(squad): build a whole squad with a solver instead of prose"
git push -u origin squad-builder
gh pr create --title "Deterministic squad builder" --body "$(cat <<'EOF'
Asked for a wildcard squad with three players from each of three clubs, the model produced
placeholders, broke the club limit while claiming to meet it, and miscounted its own budget.
Squad selection is constrained optimisation, not prose.

buildSquad loads a candidate pool from the daily price capture, scores each player on expected
points per fixture summed over the next N gameweeks, and solves for the fifteen and the eleven
with an ojAlgo integer program. Impossible requests are refused by name.

Verified: 511 tests, including a randomised invariant test asserting every FPL rule holds on
every result, and an acceptance test reproducing the original request.

Plan: docs/superpowers/plans/2026-08-27-squad-builder.md
EOF
)"
```

---

## Self-Review

**Spec coverage.** Scoring (Task 5), two-tier pool and daily-price sourcing (Task 4), two provenance lines (Tasks 9–10), the ILP rows 1–7 (Task 7), rows 8–11 and 12–14 with the relaxation ladder (Task 8), pre-flight infeasibility (Task 6), club quota comparators (Tasks 3, 8), name resolution with diacritic folding (Task 10), the output contract including the conservative-budget warning (Task 9), and every testing bullet including the acceptance test and the 5-2-3 case. The selling-price formula is deliberately absent: the spec scopes it to issue #53 because nothing can feed it yet, and code with no inputs is code with no tests.

**Two things the executor should expect to correct.**

1. **The ojAlgo API is written from recollection.** Task 2 exists to falsify it in five minutes. If `Variable.getIndex().index`, `Expression.level/lower/upper`, or `model.maximise()` differ, fix the smoke test against the real API and carry the corrected forms into Tasks 7 and 8.
2. **`CandidatePool`'s SQL has two `.replace(...)` calls** papering over an alias mismatch between the CTE and the key builder. Renaming the key builder to emit `pool.` directly is cleaner and produces the same query; do that if it reads badly.

**Not yet real, and the plan says so where it matters:** defensive contribution is absent from the scoring (#46), the FDR multiplier is one coefficient on the whole score rather than a per-fixture clean-sheet projection (#47), and players with no minutes history can only enter as bench bodies or by being named.
