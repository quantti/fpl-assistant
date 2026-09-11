# KAR-37 — squad builder: deferred minors

**PROGRESS:** Task 4/4 done.

Issue: [KAR-37](https://linear.app/quantti/issue/KAR-37/squad-builder-deferred-minors-carried-past-the-whole-branch-review).
Checked in Linear on 2026-09-11: Todo, Medium (3). Written 2026-08-27 against the original
squad-builder branch (PR #54). Since then KAR-31 (`03a4ee9`, 2026-09-09) replaced the pool's
per-player SQL with `RankingMetrics`, KAR-35 (`b1793e1`, 2026-09-09) replaced the fixture window
with `HorizonFixtures`/`ScheduleSource`, and KAR-45/KAR-9 touched the same files. Most of the
ticket describes code that no longer exists.

Baseline: `origin/master` at `9de1def`. Worktree `~/dev/fpl-assistant-kar37`, branch
`kar-37-squad-minors`. Paths below are relative to `backend/src/` unless stated otherwise.

## Goal and scope

Close the ticket. Fix the three items that still exist, on their own sites, with no adjacent
cleanup; record that the other six were removed by later work or are deliberately left alone.
No behaviour change to selection, scoring, constraints or provenance.

## Triage

Every item was checked against `9de1def` on 2026-09-11.

| # | Item | Current status | Decision |
| --- | --- | --- | --- |
| 1 | `ConstraintParserTest.readsAllThreeComparators` feeds sorted input | Still present: `test/.../squad/ConstraintParserTest.java:31` parses `"Arsenal:>=2, Chelsea:<=1, Spurs:=3"`; the sort is `ConstraintParser.java:95` | **Fix** — one-line input change, expectations unchanged. |
| 2 | `HorizonScorerIntegrationTest` comment claims `idx_season_current` enforces one current season | Already fixed by KAR-31/KAR-35: the class is now a pure unit test over `ScheduleSource.View` with no DB, no season row and no such comment. The index name appears only in `test/resources/db/tapas-schema.sql:444` | **No action.** |
| 3 | `metric_team_id` assumes no mid-window club transfer | Removed by `03a4ee9` (KAR-31). `RankingMetrics` reconstructs club membership from season-scoped fixture sides (`RankingMetrics.java:30-32`) and `MinutesModel` fences the current-club spell (`WindowReason.MULTIPLE_CLUBS`, `LATEST_CLUB_MISMATCH`), so the approximation no longer exists in this form | **No action** — superseded, and it was spec-sanctioned anyway. |
| 4 | `fixtureDifficulties` undercounts between last kickoff and tapas flipping `is_current`; empty map at horizon 1 | Removed by `b1793e1` (KAR-35). The window is derived once from deadlines in `HorizonFixtures.derive`, unknown and zero are distinct, and `SquadTools` no longer reads tapas fixtures for the horizon | **No action** — superseded. |
| 5 | `Feasibility.POSITION_QUOTAS` / `POSITION_NAMES` are public mutable arrays | Still present: `main/.../squad/Feasibility.java:27-29`. Readers: `Feasibility` (14 index sites), `SquadSolver.java:261-263`, `test/.../SquadSolverRulesTest.java:25,107-108` | **Fix** — `List.of`, the ticket's own suggestion; smallest diff, no accessor API. |
| 6 | `SquadTools` calls `seasonResolver.previousSeason(season)` a second time | Removed by `006dce9` (season blend, 2026-08-28). `SquadTools.java:123` makes one `seasonResolver.metricSeasons(season)` call and `previousSeason` has no caller in the `squad` package | **No action** — superseded. |
| 7 | `club_fixtures` correlated scalar subquery per player | Removed by `03a4ee9` (KAR-31); `RankingMetrics` loads one snapshot per call with set-based aggregates | **No action** — superseded, and "no action" per the ticket regardless. |
| 8 | Position labels 2–3 chars + two spaces, names do not align | Still present: `main/.../squad/SquadFormatter.java:201-202` appends `POSITION_ABBR[...]` then `"  "`. No test pins the spacing | **Fix** — pad the label to width 3, one space after; test first. |
| 9 | Shared element-id ranges across `squad` tests bite only under parallel execution | Still present (`9201` is used by both `CandidatePoolIntegrationTest` and `CandidatePoolChangeoverTest`) but `build.gradle` sets no `maxParallelForks`/`forkEvery` | **No action** — per the ticket, only when parallel execution is turned on. |

Net: three small fixes (items 1, 5, 8), all in the `squad` package, no SQL, no provenance text.

## Test prelude

Every verification command below assumes this, run once per shell from the worktree root
(`scripts/test-db.sh up` is already done and `backend/.env.test` targets `localhost:55432`):

```bash
cd ~/dev/fpl-assistant-kar37/backend
source "$HOME/.sdkman/bin/sdkman-init.sh"
set -a; source .env.test; set +a
```

## Task 1 — `ConstraintParserTest` exercises the sort (item 1)

File: `test/java/fi/karivanttinen/fplassistant/squad/ConstraintParserTest.java:31`.

Change the parsed string only; the two `containsExactly` expectations stay as they are:

```java
var quotas = parse("Spurs:=3, Arsenal:>=2, Chelsea:<=1").clubQuotas();
```

The test passes before and after (the sort is correct); what changes is that it now fails if
`.sorted(...)` at `ConstraintParser.java:95` is removed. Optional one-off check: comment that
line out, watch the test go red, restore it.

Verify:

```bash
./gradlew test --tests "*ConstraintParserTest"
```

## Task 2 — `Feasibility` quotas and names become `List.of` (item 5)

Files: `main/.../squad/Feasibility.java`, `main/.../squad/SquadSolver.java`,
`test/.../squad/SquadSolverRulesTest.java`.

`Feasibility.java:27-29`:

```java
public static final List<Integer> POSITION_QUOTAS = List.of(0, 2, 5, 5, 3);
public static final List<String> POSITION_NAMES =
        List.of("", "goalkeeper", "defender", "midfielder", "forward");
```

Then mechanically, in the three files: `POSITION_QUOTAS[i]` → `POSITION_QUOTAS.get(i)`,
`POSITION_NAMES[i]` → `POSITION_NAMES.get(i)`, `POSITION_QUOTAS.length` →
`POSITION_QUOTAS.size()`. Sites: `Feasibility.java` 163, 166, 169, 172, 175, 178, 199, 206,
208, 261, 264, 267, 284, 304; `SquadSolver.java` 261, 263; `SquadSolverRulesTest.java` 25
(`private static final List<Integer> QUOTAS = Feasibility.POSITION_QUOTAS;`), 107, 108.
`java.util.List` is already imported in all three. Nothing else in `src/` references either
constant (checked with grep). Do not touch `SQUAD_SIZE`/`MAX_PER_CLUB`.

Verify (compiles and the rules/feasibility tests are unchanged):

```bash
./gradlew test --tests "*FeasibilityTest" --tests "*SquadSolver*"
```

## Task 3 — position labels align the name column (item 8)

Files: `main/.../squad/SquadFormatter.java:200-202`,
`test/.../squad/SquadFormatterTest.java`.

TDD — write the test first and see it fail. Add to `SquadFormatterTest` (the fixture's
`Name1` is the GK in the XI and `Name3` an Arsenal DEF):

```java
@Test
void positionLabelsPadSoNamesStartInOneColumn() {
    String output = format(solved(), constraints());

    assertThat(output).contains("  GK  Name1 (").contains("  DEF Name3 (");
}
```

It fails today on `"  DEF Name3 ("` (the current output is `DEF  Name3`). Then in
`SquadFormatter.line`:

```java
line.append(String.format(Locale.ROOT, "%-3s ", POSITION_ABBR[player.elementType()]))
        .append(player.webName())
```

`Locale` is already imported. The GK line is byte-for-byte what it was (`GK` + two spaces);
the three-letter labels lose one space. Bench lines keep their `1. ` prefix — that is
substitution order, not the label.

Verify:

```bash
./gradlew test --tests "*SquadFormatterTest"
```

## Task 4 — full suite, STATUS entry, close out

1. Full suite, must be green:

   ```bash
   ./gradlew cleanTest test
   ```

2. Append to `docs/STATUS.md`, after the KAR-9 entry, in the same register (short, what changed,
   what was verified, what was not):

   ```markdown
   ## Squad-builder minors triaged and closed (KAR-37, 2026-09-11)

   Plan: `docs/superpowers/plans/2026-09-11-kar-37-squad-minors.md`. Of the nine items the
   ticket carried from PR #54, six no longer exist: KAR-31 (`03a4ee9`) removed `metric_team_id`,
   `club_fixtures`, the duplicate `previousSeason` call and the `idx_season_current` comment, and
   KAR-35 (`b1793e1`) removed `fixtureDifficulties`. Fixed the three that remained:
   `readsAllThreeComparators` now feeds unsorted input, `Feasibility.POSITION_QUOTAS` and
   `POSITION_NAMES` are `List.of`, and the squad card pads position labels so names share a
   column. The shared element-id ranges stay as they are — `build.gradle` still runs tests
   serially, as the ticket asked.

   Verified: disposable DB, full `./gradlew cleanTest test` green (N tests). No behaviour change
   to selection, scoring or provenance; no live chat run.
   ```

   Fill in `N` from the run.

3. Set this file's `**PROGRESS:**` to `Task 4/4 done`.

4. Commit on `kar-37-squad-minors`, push, open a PR against `master` per `AGENTS.md`
   (subject ~60 chars, body ≤ 8 lines, `Closes KAR-37`), review it. Never push to `master`.

5. In Linear, comment on KAR-37 with the triage table's decision column (items 2, 3, 4, 6, 7
   superseded by KAR-31/KAR-35; item 9 left until parallel execution) and move it to Done once
   the PR is merged.
