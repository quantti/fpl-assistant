# KAR-49: Free Hit Continuing-Squad Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**PROGRESS:** Task 3/4 done

**Goal:** When a Free Hit is the active chip, `getMyTeam` shows the continuing squad (the team
that returns after the gameweek) beside the one-week Free Hit squad, and the system prompt bases
forward-looking advice on the continuing squad.

**Architecture:** Two layers, per the ticket. (1) `MyTeamService.myTeam()` fetches the previous
gameweek's picks when `activeChip == "freehit"` — transfers made in the FH window belong to the
FH team only, so the previous GW's picks are exactly the team that comes back — and renders a
second, labelled squad block through the same enrichment path. (2) The system prompt instructs
the model to base transfers / next-GW captaincy / chip planning on the continuing block. No
schema, DB, or frontend changes.

**Tech Stack:** Spring Boot 4.1, Java 25 (sealed interfaces, record patterns, switch with `when`
guards and `case null`), JUnit 5 + AssertJ, `com.sun.net.httpserver.HttpServer` stub (existing
pattern in `MyTeamServiceTest`).

**Spec:** Linear KAR-49 — "getMyTeam shows the Free Hit squad as the continuing team"
(<https://linear.app/quantti/issue/KAR-49>). Condensed here because executors may lack Linear
access:

> The FH squad reverts after the gameweek, so forward-looking recommendations built on it are
> wrong — and `/entry/{id}/event/{gw}/picks/` serves the FH picks until the *next* deadline
> passes, so the window is a whole week. `FplEntryClient` already parses `active_chip` into
> `LockedSquad.activeChip` and the card prints "Active chip: free hit"; nothing acts on it.
> Fix: (1) when the latest locked squad has `activeChip == "freehit"`, also fetch
> `/entry/{id}/event/{gw-1}/picks/` and render a second block labelled as the continuing team,
> FH block labelled one-week-only, same enrichment path for both. Edge cases: FH in GW1 (no
> previous picks — say so); 404 on the previous GW (FH block alone, with a warning).
> (2) System prompt: forward-looking advice must be based on the continuing block whenever a
> Free Hit is active; the FH squad is only evidence about the gameweek being played. Wildcard
> persists; bench boost / triple captain don't change the fifteen — no handling needed.
> Out of scope, note in the PR: `getMiniLeagueOpponents` diffs an opponent's FH squad the same
> way.

## Global Constraints

- **NEVER push to `master`** — it deploys. Work on branch `kar-49-free-hit-continuing-squad`,
  push that, open a PR. Never `git push --no-verify`, never re-point `core.hooksPath`.
- **Commit this plan file onto the branch before Task 1.** It is untracked when execution
  starts, and a fresh git worktree does not carry untracked files — copy it into the worktree
  first if it is missing there. Later tasks update its `**PROGRESS:**` line and Task 4 commits
  the final state.
- Tests run against the local disposable container: `scripts/test-db.sh up` once, then
  `./gradlew test` (full suite) or `./gradlew test --tests "*MyTeamServiceTest"` from
  `backend/`. Tests must never run against Supabase.
- Progress tracking: update the single `**PROGRESS:**` line above once per task. No `- [ ]`
  checkboxes anywhere.
- Commit subjects: one line, imperative, ~60 chars; body only when the *why* is not in the diff.
  End every commit message with:
  `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>` and
  `Claude-Session: https://claude.ai/code/session_01Ak3jRTBkmrgAC9YGxJomF1`
- Surgical diffs: touch only what a task names. Match existing style (this codebase writes
  full-sentence javadoc explaining *why*; keep that voice).
- Money is in 0.1m units everywhere (`bank: 5` renders `GBP 0.5m`).

## File map

- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/myteam/MyTeamService.java` —
  new `Continuing` sealed interface, `Result.Squad` gains a field, `myTeam()` fetches the
  previous GW under a FH, `renderSquad()` renders two blocks (Tasks 1-2).
- Modify: `backend/src/test/java/fi/karivanttinen/fplassistant/myteam/MyTeamServiceTest.java` —
  stub grows an `activeChip`/previous-picks knob; new tests (Tasks 1-2).
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/myteam/MyTeamTools.java` — one
  sentence in the `@Tool` description (Task 3).
- Modify: `backend/src/main/resources/prompts/system-prompt.st` — one paragraph in
  "## The user's own team" (Task 3).
- Modify: `docs/STATUS.md` — short entry (Task 4).

---

### Task 1: Continuing-squad happy path in `MyTeamService`

**Files:**
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/myteam/MyTeamService.java`
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/myteam/MyTeamServiceTest.java`

**Interfaces:**
- Consumes: `MyTeamService.lockedSquad(int entryId, int event, FplBudget budget)` (exists;
  cached per `entryId@event`, one budget unit per miss), `SquadEnrichment.forElements(Set<Integer>)`,
  `FplBootstrap.Snapshot.element(int)`.
- Produces (Task 2 and 3 rely on these exact names):
  - `MyTeamService.Continuing` sealed interface with `Continuing.Squad(int event,
    FplEntryClient.LockedSquad locked, List<SquadPlayer> players)` (Task 2 adds
    `Continuing.Unknown(String reason)`).
  - `Result.Squad` with new component `Continuing continuing` inserted between `enrichment`
    and `fetchedAt` — `null` when no Free Hit is active.
  - Renderer strings the prompt paragraph refers to: `FREE HIT squad for Gameweek N`,
    `Continuing squad (as locked at the Gameweek N deadline)`.

**Step 1: Extend the test stub.** In `MyTeamServiceTest`:

Add fields next to `chipsPlayed` (line ~60):

```java
private String activeChip = null;
/** Body for the previous gameweek's picks; null makes the stub answer 404. */
private String previousPicks = null;
```

In `startStub()`, insert a previous-gameweek branch *before* the current-event picks branch
(order matters only for readability; the paths cannot collide):

```java
} else if (currentEvent > 1 && path.contains("/event/" + (currentEvent - 1) + "/picks/")) {
    if (previousPicks == null) {
        respond(exchange, 404, "{}");
    } else {
        respond(exchange, 200, previousPicks);
    }
} else if (path.contains("/event/" + currentEvent + "/picks/")) {
    respond(exchange, 200, picks());
}
```

(The second branch replaces the existing `respond(exchange, 200, PICKS);` line.)

Replace the `PICKS` constant with a method + template so the active chip is settable:

```java
private String picks() {
    return PICKS.formatted(activeChip == null ? "null" : "\"" + activeChip + "\"");
}

private static final String PICKS = """
        {"active_chip": %s,
         "entry_history": {"bank": 12, "value": 1003, "points": 55},
         "picks": [
           {"element": 302, "position": 1, "multiplier": 2, "is_captain": true,
            "is_vice_captain": false, "element_type": 3},
           {"element": 17, "position": 12, "multiplier": 0, "is_captain": false,
            "is_vice_captain": true, "element_type": 1}]}
        """;
```

Add the previous-gameweek payload (a visibly different squad — Wood instead of Salah — so the
test can tell the blocks apart):

```java
private static final String PREVIOUS_PICKS = """
        {"active_chip": null,
         "entry_history": {"bank": 5, "value": 995, "points": 48},
         "picks": [
           {"element": 44, "position": 1, "multiplier": 2, "is_captain": true,
            "is_vice_captain": false, "element_type": 4},
           {"element": 17, "position": 12, "multiplier": 0, "is_captain": false,
            "is_vice_captain": true, "element_type": 1}]}
        """;
```

Add element 44 to the `BOOTSTRAP` constant's `elements` array (after Sels):

```java
   {"id": 44, "web_name": "Wood", "team": 4, "element_type": 4, "now_cost": 71,
    "form": "5.0", "total_points": 18, "points_per_game": "6.0",
    "selected_by_percent": "25.0", "status": "a", "news": ""}],
```

**Step 2: Write the failing test.**

```java
/**
 * A Free Hit squad plays one gameweek and reverts, and FPL serves it as "the" squad all
 * week — so the card must show the team that comes back, and say which is which.
 */
@Test
void aFreeHitCardShowsTheContinuingSquadBesideIt() {
    currentEvent = 4;
    activeChip = "freehit";
    previousPicks = PREVIOUS_PICKS;
    storeTeamId(ENTRY);
    MyTeamService service = service();

    String card = service.render(service.myTeam(USER, budget()));

    assertThat(card)
            .contains("FREE HIT squad for Gameweek 4")
            .contains("reverts to the continuing squad")
            .contains("Continuing squad (as locked at the Gameweek 3 deadline)")
            .contains("apply to THIS squad")
            .contains("Bank then was GBP 0.5m");
    assertThat(card).containsSubsequence(
            "FREE HIT squad", "Salah", "Continuing squad", "Wood (NFO, FWD)");
}
```

Also add one line to the existing `theCardNamesTheGameweekItDescribesAndTheMoneyItFound`
assertion chain, guarding the no-chip card against a stray block:

```java
                .doesNotContain("Continuing squad")
```

**Step 3: Run to verify the new test fails.**

Run: `cd backend && ./gradlew test --tests "*MyTeamServiceTest"`
Expected: `aFreeHitCardShowsTheContinuingSquadBesideIt` FAILS (card lacks "FREE HIT squad");
all pre-existing tests PASS. (Needs `scripts/test-db.sh up` first.)

**Step 4: Implement.** In `MyTeamService.java`:

Add the sealed interface after the `SquadPlayer` record (line ~78):

```java
/**
 * The team that comes back once a Free Hit gameweek is over. Present only while one is
 * active. The previous deadline's picks are exactly the returning fifteen: transfers made
 * inside the Free Hit window belong to the Free Hit team alone.
 */
public sealed interface Continuing {
    record Squad(int event, LockedSquad locked, List<SquadPlayer> players)
            implements Continuing {
    }
}
```

Extend `Result.Squad` with `Continuing continuing` between `enrichment` and `fetchedAt`:

```java
record Squad(EntrySummary entry, int event, Optional<Instant> deadline, LockedSquad locked,
             List<SquadPlayer> players, int chipEvent, List<String> playableChips,
             List<String> chipsOpeningLater, Integer chipsOpenAt, boolean chipsKnown,
             SquadEnrichment.Enrichment enrichment, Continuing continuing,
             Instant fetchedAt) implements Result {
}
```

The record documents its non-obvious components with `@param`; add to that javadoc:

```java
 * @param continuing the squad that returns after a Free Hit gameweek; null when no Free
 *        Hit is active
```

In `myTeam()`, replace the element-id collection and player-building section (currently lines
186-194) with a fetch of the previous squad, a shared enrichment call, and a helper:

```java
// A Free Hit squad reverts at the next deadline, so the previous gameweek's picks — the
// team that comes back — are fetched and rendered beside it. KAR-49.
LockedSquad previousSquad = null;
if ("freehit".equals(locked.activeChip()) && event > 1) {
    if (lockedSquad(entryId, event - 1, budget)
            instanceof FplEntryClient.Result.Ok<Optional<LockedSquad>>(Optional<LockedSquad> squad)
            && squad.isPresent()) {
        previousSquad = squad.get();
    }
}

Set<Integer> elementIds = new LinkedHashSet<>();
locked.picks().forEach(pick -> elementIds.add(pick.element()));
if (previousSquad != null) {
    previousSquad.picks().forEach(pick -> elementIds.add(pick.element()));
}
SquadEnrichment.Enrichment depth = enrichment.forElements(elementIds);

List<SquadPlayer> players = squadPlayers(locked, snapshot.get(), depth);
Continuing continuing = previousSquad == null ? null
        : new Continuing.Squad(event - 1, previousSquad,
                squadPlayers(previousSquad, snapshot.get(), depth));
```

(Task 2 replaces the silent `null` on a failed fetch with a labelled `Continuing.Unknown`;
in this task a missing previous squad just renders the old single-block card.)

Pass `continuing` in the `Result.Squad` construction:

```java
return new Result.Squad(summary, event, snapshot.get().deadline(event), locked, players,
        chipEvent, chips.playable(), chips.openingLater(), chips.opensAt(), chips.known(),
        depth, continuing, Instant.now());
```

Add the helper (near the bottom, above the rendering section):

```java
private static List<SquadPlayer> squadPlayers(LockedSquad squad, FplBootstrap.Snapshot snapshot,
                                              SquadEnrichment.Enrichment depth) {
    List<SquadPlayer> players = new ArrayList<>();
    for (Pick pick : squad.picks().stream()
            .sorted(Comparator.comparingInt(Pick::position)).toList()) {
        players.add(new SquadPlayer(pick, snapshot.element(pick.element()).orElse(null),
                depth.of(pick.element())));
    }
    return players;
}
```

In `renderSquad()`, change the header line to name a Free Hit squad as what it is:

```java
boolean freeHit = squad.continuing() != null;
out.append(freeHit ? "FREE HIT squad for Gameweek " : "Squad as locked for Gameweek ")
        .append(squad.event());
```

and directly after the `fetchedAt` sentence (the first `.\n`), add:

```java
if (freeHit) {
    out.append("This squad plays Gameweek ").append(squad.event())
            .append(" only - it reverts to the continuing squad below at the next deadline.\n");
}
```

Extract the Starting XI / Bench / Flagged section (currently lines 331-345) into a helper used
by both blocks — the existing `flagged` computation moves inside it unchanged:

```java
private static void appendPicks(StringBuilder out, List<SquadPlayer> players) {
    out.append("Starting XI\n");
    players.stream().filter(player -> player.pick().starting())
            .forEach(player -> out.append(line(player)));
    out.append("Bench\n");
    players.stream().filter(player -> !player.pick().starting())
            .forEach(player -> out.append(line(player)));
    String flagged = players.stream()
            .filter(player -> player.element() != null && player.element().availability() != null)
            .map(player -> player.element().webName() + " (" + player.element().teamShort()
                    + ") - " + player.element().availability())
            .reduce((a, b) -> a + "; " + b).orElse(null);
    if (flagged != null) {
        out.append("Flagged: ").append(flagged).append("\n");
    }
}
```

`renderSquad()` ends with the main block plus, when present, the continuing block:

```java
appendPicks(out, squad.players());

if (squad.continuing() instanceof Continuing.Squad(
        int event, LockedSquad previous, List<SquadPlayer> players)) {
    out.append("\nContinuing squad (as locked at the Gameweek ").append(event)
            .append(" deadline) - the team that returns after Gameweek ").append(squad.event())
            .append(". Transfers and all forward-looking advice apply to THIS squad, not the")
            .append(" Free Hit one. Captain and bench order are Gameweek ").append(event)
            .append(" choices; only the fifteen players carry over. Bank then was ")
            .append(money(previous.bank()))
            .append(" (approximate - the Free Hit's transfers are undone when it reverts).\n");
    appendPicks(out, players);
}
return out.toString();
```

**Step 5: Run the tests.**

Run: `cd backend && ./gradlew test --tests "*MyTeamServiceTest"`
Expected: all PASS, including every pre-existing test (the caching, chips and money tests must
not have moved).

**Step 6: Commit.**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/myteam/MyTeamService.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/myteam/MyTeamServiceTest.java
git commit -m "feat: show the continuing squad beside an active Free Hit"
```

---

### Task 2: Edge cases — Free Hit in GW1, previous picks unfetchable

**Files:**
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/myteam/MyTeamService.java`
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/myteam/MyTeamServiceTest.java`

**Interfaces:**
- Consumes: `Continuing` sealed interface and the `myTeam()`/`renderSquad()` shape from Task 1.
- Produces: `Continuing.Unknown(String reason)` — a Free Hit is active but the returning squad
  could not be shown; `renderSquad()` renders the reason in a warning sentence containing
  `returning squad could not be shown` (the prompt paragraph in Task 3 leans on the card
  saying this).

**Step 1: Write the failing tests.**

```java
/** FPL down or GW-1 picks missing must not silently render the FH squad as the real team. */
@Test
void aFreeHitWhosePreviousPicksAre404GetsAWarningNotAContinuingBlock() {
    currentEvent = 4;
    activeChip = "freehit";
    previousPicks = null;   // the stub answers 404 for GW3
    storeTeamId(ENTRY);
    MyTeamService service = service();

    String card = service.render(service.myTeam(USER, budget()));

    assertThat(card)
            .contains("FREE HIT squad for Gameweek 4")
            .contains("returning squad could not be shown")
            .contains("no picks for Gameweek 3")
            .doesNotContain("Continuing squad (as locked");
}

/** There is no gameweek zero: a GW1 Free Hit must say so, not fetch. */
@Test
void aFreeHitInGameweekOneSaysThereIsNoEarlierSquad() {
    currentEvent = 1;
    activeChip = "freehit";
    storeTeamId(ENTRY);
    MyTeamService service = service();

    String card = service.render(service.myTeam(USER, budget()));

    assertThat(card)
            .contains("FREE HIT squad for Gameweek 1")
            .contains("no earlier squad");
    assertThat(requests.keySet())
            .as("no gameweek-zero fetch is attempted")
            .noneMatch(path -> path.contains("/event/0/picks/"));
}
```

**Step 2: Run to verify both fail.**

Run: `cd backend && ./gradlew test --tests "*MyTeamServiceTest"`
Expected: both new tests FAIL — with Task 1's code a missing previous squad leaves
`continuing` null, so the card renders no "FREE HIT" header and no warning.

**Step 3: Implement.** Add the second `Continuing` variant:

```java
/** A Free Hit is active but the returning squad could not be shown; the reason is rendered. */
record Unknown(String reason) implements Continuing {
}
```

Replace Task 1's previous-squad fetch in `myTeam()` so every Free Hit produces a `Continuing`
— a squad when the fetch worked, a labelled reason when it did not:

```java
// A Free Hit squad reverts at the next deadline, so the previous gameweek's picks — the
// team that comes back — are fetched and rendered beside it. KAR-49.
Continuing continuing = null;
LockedSquad previousSquad = null;
if ("freehit".equals(locked.activeChip())) {
    if (event <= 1) {
        continuing = new Continuing.Unknown(
                "a Free Hit in Gameweek 1 has no earlier squad to return to");
    } else {
        switch (lockedSquad(entryId, event - 1, budget)) {
            case FplEntryClient.Result.Ok<Optional<LockedSquad>>(Optional<LockedSquad> squad)
                    when squad.isPresent() -> previousSquad = squad.get();
            case FplEntryClient.Result.Ok<Optional<LockedSquad>> ignored ->
                    continuing = new Continuing.Unknown(
                            "FPL publishes no picks for Gameweek " + (event - 1));
            case FplEntryClient.Result.NotFound<Optional<LockedSquad>> ignored ->
                    continuing = new Continuing.Unknown(
                            "FPL publishes no picks for Gameweek " + (event - 1));
            case FplEntryClient.Result.Unavailable<Optional<LockedSquad>> ignored ->
                    continuing = new Continuing.Unknown(
                            "FPL could not be reached for it just now");
        }
    }
}
```

(The `NotFound` arm is required by the sealed switch even though `picks()` maps 404 to an
empty `Ok` — both mean the same thing here.)

The element-id union, `squadPlayers` calls and `Result.Squad` construction from Task 1 stay;
only the `continuing` assignment changes:

```java
if (previousSquad != null) {
    continuing = new Continuing.Squad(event - 1, previousSquad,
            squadPlayers(previousSquad, snapshot.get(), depth));
}
```

In `renderSquad()`, turn the Task 1 `instanceof` into a switch over both variants:

```java
switch (squad.continuing()) {
    case null -> { }
    case Continuing.Squad(int event, LockedSquad previous, List<SquadPlayer> players) -> {
        out.append("\nContinuing squad (as locked at the Gameweek ").append(event)
                .append(" deadline) - the team that returns after Gameweek ")
                .append(squad.event())
                .append(". Transfers and all forward-looking advice apply to THIS squad, not")
                .append(" the Free Hit one. Captain and bench order are Gameweek ").append(event)
                .append(" choices; only the fifteen players carry over. Bank then was ")
                .append(money(previous.bank()))
                .append(" (approximate - the Free Hit's transfers are undone when it")
                .append(" reverts).\n");
        appendPicks(out, players);
    }
    case Continuing.Unknown(String reason) ->
            out.append("\nA Free Hit is active, so this squad reverts at the next deadline,")
                    .append(" but the returning squad could not be shown: ").append(reason)
                    .append(". Base transfers and forward-looking advice on the squad that")
                    .append(" returns, and say its exact makeup could not be verified.\n");
}
return out.toString();
```

**Step 4: Run the tests.**

Run: `cd backend && ./gradlew test --tests "*MyTeamServiceTest"`
Expected: all PASS.

**Step 5: Commit.**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/myteam/MyTeamService.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/myteam/MyTeamServiceTest.java
git commit -m "feat: label a Free Hit whose returning squad is unknown"
```

---

### Task 3: Instruction layer — system prompt and tool description

**Files:**
- Modify: `backend/src/main/resources/prompts/system-prompt.st` (the
  "## The user's own team" section, after the "Two limits are built into the data" paragraph,
  currently ending line 138)
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/myteam/MyTeamTools.java`
  (the `@Tool` description, lines 31-39)

**Interfaces:**
- Consumes: the card wording from Tasks 1-2 — `FREE HIT squad`, the continuing block, the
  `returning squad could not be shown` warning. The prompt must describe what the card
  actually says, so if executor renamed anything there, mirror it here.

No unit test covers prompt content (none exists for the prompt today; do not invent a
string-matching test for prose). Verification is the full suite still passing plus reading the
rendered paragraph in place.

**Step 1: Add the prompt paragraph.** Insert after the paragraph ending
"…never present a transfer plan as exactly affordable to the penny." and before the
mini-league paragraph:

```
When the card says the active chip is a free hit, the squad playing this gameweek is
temporary: it reverts at the next deadline, and the card shows the continuing squad beside
it. Base every forward-looking answer — transfers, next gameweek's captaincy, chip planning
— on the continuing-squad block; the Free Hit squad is evidence only about the gameweek
being played. If the card says the returning squad could not be shown, keep forward-looking
advice conditional and say why. A wildcard squad persists, and bench boost or triple captain
do not change the fifteen — none of those need this handling.
```

**Step 2: Extend the tool description.** In `MyTeamTools.java`, add one sentence to the
`@Tool` description text block, after "…Takes no arguments - the account is known from the
session.":

```
When a Free Hit is active the reply carries two squads - the one-gameweek Free Hit squad
and the continuing squad it reverts to - each labelled as which it is.
```

**Step 3: Run the full suite.**

Run: `cd backend && ./gradlew test`
Expected: PASS. Note what this does and does not verify: it guards the `MyTeamTools` compile,
but `AiConfig` reads the prompt file as a plain string (`getContentAsString`,
`AiConfig.java:26-28`) and no test parses its content — verify the paragraph by reading the
edited section in place. Keep `{` and `}` out of the added text (the file stays named `.st`
and Spring AI may treat system text as a template).

**Step 4: Commit.**

```bash
git add backend/src/main/resources/prompts/system-prompt.st \
        backend/src/main/java/fi/karivanttinen/fplassistant/myteam/MyTeamTools.java
git commit -m "feat: ground forward-looking advice on the continuing squad"
```

---

### Task 4: STATUS entry, branch push, PR

**Files:**
- Modify: `docs/STATUS.md` — **append** a new section at the very END of the file.

> Corrected during execution: an earlier draft of this task said "add a dated entry at the top
> of the log" in "3-6 lines". Both were wrong. `docs/STATUS.md` is a ~3400-line append-only log
> whose opening lines are a Phase 1 header block; entries are appended at the end as
> `## <what changed> (KAR-n, YYYY-MM-DD)` sections of roughly 15-30 lines of prose. See
> `## Pick tools read the daily capture (KAR-32, 2026-09-02)` and
> `## Gameweeks and fixtures captured from FPL (KAR-10, 2026-09-02)` for the register.

**Step 1: Write the STATUS entry.** Append at the end of the file under a heading of the house
form `## <what changed> (KAR-49, 2026-09-08)`, in prose matching the surrounding entries:

- what the change does now — `getMyTeam` renders the Free Hit squad labelled one-week-only
  beside a "Continuing squad" block built from the previous deadline's picks (exactly the
  returning fifteen, because transfers made inside the Free Hit window belong to the Free Hit
  team alone), and the system prompt bases transfers, next-gameweek captaincy and chip planning
  on the continuing block;
- the decisions worth recording — why the previous gameweek's picks are the right source; why
  the unfetchable cases (Free Hit in GW1, a 404, FPL unreachable) render a labelled warning
  rather than falling back silently; why wildcard, bench boost and triple captain need no such
  handling;
- the follow-up left open — `getMiniLeagueOpponents` diffs an opponent's Free Hit squad with the
  same blind spot, out of scope here and noted in the KAR-49 ticket.

**Step 2: Commit.**

```bash
git add docs/STATUS.md docs/superpowers/plans/2026-09-08-kar49-free-hit-continuing-squad.md
git commit -m "docs: STATUS entry for KAR-49 continuing-squad fix"
```

**Step 3: Push the branch and open the PR.** The pre-push hook runs the suite; the test
container must be up.

```bash
git push -u origin kar-49-free-hit-continuing-squad
```

PR via `gh pr create`, title `Show the continuing squad beside an active Free Hit`, body at
most 8 lines, no headings:

```
A Free Hit squad reverts after its gameweek, but getMyTeam rendered it as the
continuing team all week, so forward-looking advice was built on fifteen players
the manager does not keep. The card now fetches the previous deadline's picks —
exactly the returning squad — labels both blocks, and the system prompt grounds
transfers/captaincy/chip planning on the continuing block; GW1 and unfetchable
picks get a labelled warning. Verified: new MyTeamServiceTest cases + full suite.
Plan: docs/superpowers/plans/2026-09-08-kar49-free-hit-continuing-squad.md
Note: getMiniLeagueOpponents keeps this blind spot for opponents. Closes KAR-49.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

**Step 4: Verify.** `gh pr checks --watch` until the PR's test run is green. Do not merge —
the owner merges.

---

## Self-review notes (already applied)

- Spec coverage: FH detection ✔ (Task 1-2), previous-GW fetch through the same cache/budget ✔,
  same enrichment path (single `forElements` over the union) ✔, both blocks labelled ✔, GW1
  edge ✔, 404/unreachable edge ✔, system-prompt layer ✔, wildcard explicitly excluded ✔,
  mini-league blind spot noted in PR ✔.
- `Result.Squad` has exactly one construction site (`MyTeamService.java:210`), so the added
  component breaks no other caller; verified by grep before writing this plan.
- The stub's previous-GW branch is guarded with `currentEvent > 1` so GW1 tests cannot match a
  `/event/0/picks/` route that should never be requested.
- Budget: the extra picks call costs 1 of 15 per turn (`fpl.myteam.max-requests-per-turn`),
  and lands in the same `squadCache` keyed `entryId@event`, shared with the mini-league path.
