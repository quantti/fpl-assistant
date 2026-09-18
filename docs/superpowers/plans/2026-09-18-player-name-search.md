# Player Name Search Implementation Plan (KAR-57)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. One task per
> agent, sequentially — the tasks share a working tree (AGENTS.md "Task execution").

**PROGRESS:** Task 5/5 done — push, PR and production check pending

**Goal:** A player is found whether the user types accents or not, a first name or not, a club or
not — and once found, the assistant answers the question that was asked.

**Architecture:** One `PlayerNameSearch` component builds the `WHERE`/`ORDER BY` fragment every
player lookup uses: accent-folded with Postgres `unaccent`, tokenised on whitespace, club fields
allowed as a narrowing token. The three SQL sites that match a player name today call it instead
of hand-writing `LOWER(col) LIKE ?`. The prompt and the not-found tool text are changed so the
model stops treating an accent or an added club as "a different spelling", retries once on the
surname, and returns to the original question.

**Tech stack:** Spring Boot 4.1 / Java 25 / `JdbcTemplate`, PostgreSQL `unaccent` contrib
extension (present in `pgvector/pgvector:pg17`, the image both `scripts/test-db.sh` and CI use;
available on Supabase).

**Spec:** Linear KAR-57 — evidence, root causes and the owner's decision (option A, `unaccent`,
2026-09-18) are recorded there. No separate spec document.

## Evidence (production chat, 2026-09-18)

| User wrote | Result | Cause |
|---|---|---|
| `Tarik Muharemovic` | not found | `lower('Tarik Muharemović') LIKE '%tarik muharemovic%'` is false — `ć` ≠ `c` |
| `Hall Newcastle` | not found | whole string matched against `web_name`/`full_name`; club is not in the haystack |
| `Leeds` (answering "which club?") | still not found | the club the prompt tells the model to ask for cannot be used by any lookup |
| `Muharemović` (pasted) | found, reply was only "he is listed as Muharemović" | nothing tells the model to go back to the comparison |

Reproduced locally: `docker exec fpl-test-db psql -U postgres -Atc "SELECT lower('Muharemović') LIKE '%muharemovic%'"` → `f`.

## Global constraints

- Branch `fix/kar-57-player-name-search` (already created from `origin/master`). Never push to
  `master`; PR only. Commit/PR style per AGENTS.md "Commits and pull requests".
- **`unaccent` must never be able to fail startup.** Production has gone down three times on a
  startup statement (2026-08-19, 2026-09-16). `CREATE EXTENSION` and the probe are wrapped; on any
  `DataAccessException` the search degrades to plain `lower()` — today's behaviour — and logs at
  ERROR. No session `SET`, ever (AGENTS.md, pooler rule).
- No tapas table is altered. `unaccent` is an extension, not a schema change to a shared table,
  and the owner approved it for the shared database on 2026-09-18.
- Matching stays substring-based and the Konsa→Konaté protection stays: folding removes accents,
  it never maps one letter sequence onto a similar-looking one. `konsa` must still not match
  `Konaté`.
- Out of scope, do not touch: `RankingMetrics`' and `ExpectedStatsTools.previousSeasonPlayerRows`'
  cross-season `web_name` bridge (both sides are FPL's own spelling — nothing to fold),
  `SquadTools.fold` (pins are matched in Java and already fold), team-name lookups
  (`FixtureTools`, `FixtureDifficultyTools`, `AvailabilityTools`/`SetPieceTools` team queries,
  `HistoricalTeamResolver` — no club has an accent), the frontend player linker.
- Tests seed their own rows; nothing depends on production content. One build/test unit at a
  time, memory rules per `docs/DEVELOPMENT.md`.

## File structure

| File | Responsibility |
|---|---|
| Create `backend/src/main/java/fi/karivanttinen/fplassistant/tools/PlayerNameSearch.java` | Ensure/probe `unaccent`; tokenise; build the SQL fragment + args |
| Create `backend/src/test/java/.../tools/PlayerNameSearchTest.java` | Pure unit tests: tokens, fragment shape, fallback |
| Create `backend/src/test/java/.../tools/PlayerNameSearchIntegrationTest.java` | The four production phrases against `fpl_daily_price_test` |
| Modify `tools/DailyPriceSource.java` (`matchPlayers`) | Use the fragment (serves `PlayerTools`, `PriceTools`, `AvailabilityTools`) |
| Modify `tools/PlayerTools.java` (`seasonRows`, not-found text, `@Tool` description) | Use the fragment; new guidance |
| Modify `tools/ExpectedStatsTools.java` (`getPlayerExpectedStats` lookup) | Use the fragment; join `team` |
| Modify `src/main/resources/prompts/system-prompt.st` ("Player names are the user's") | New lookup rules |
| Create `backend/src/test/java/.../chat/PlayerNamePromptContractTest.java` | Pins the prompt phrases |
| Modify `AGENTS.md`, `docs/STATUS.md` | Convention + status entry |

---

### Task 1: `PlayerNameSearch`

**Files:** create `tools/PlayerNameSearch.java`, `tools/PlayerNameSearchTest.java`.

**Produces (later tasks rely on exactly this):**

```java
@Component
public class PlayerNameSearch {
    public PlayerNameSearch(JdbcTemplate jdbc)            // ensures + probes unaccent
    PlayerNameSearch(boolean unaccent)                    // package-private, for tests
    static List<String> tokens(String query)
    Fragment fragment(String query, String webNameExpr, String nameExpr, String clubExpr)
    record Fragment(String where, List<Object> whereArgs, String exactFirst, List<Object> orderArgs) {}
}
```

- `where` is a parenthesised boolean SQL expression; `exactFirst` is a comma-separated `ORDER BY`
  prefix ending without a trailing comma. Args are in the textual order of their `?`s.
- `webNameExpr`/`nameExpr`/`clubExpr` are SQL expressions supplied by the caller from constants,
  never from user input.

**Step 1 — failing unit test** (`PlayerNameSearchTest`):

```java
class PlayerNameSearchTest {

    @Test
    void tokensSplitOnWhitespaceAndDropPunctuationAndBlanks() {
        assertThat(PlayerNameSearch.tokens("  Hall,  Newcastle ")).containsExactly("Hall", "Newcastle");
        assertThat(PlayerNameSearch.tokens("   ")).isEmpty();
        assertThat(PlayerNameSearch.tokens(null)).isEmpty();
    }

    @Test
    void tokensAreCappedSoAPastedParagraphCannotBuildAnUnboundedStatement() {
        assertThat(PlayerNameSearch.tokens("a b c d e f g h")).hasSize(PlayerNameSearch.MAX_TOKENS);
    }

    @Test
    void everyTokenMustHitNameOrClubAndAtLeastOneMustHitTheName() {
        var f = new PlayerNameSearch(true).fragment("Hall Newcastle", "web_name", "N", "C");
        assertThat(f.where()).isEqualTo(
                "((unaccent(lower(N)) LIKE unaccent(lower(?)) OR unaccent(lower(C)) LIKE unaccent(lower(?)))"
                + " AND (unaccent(lower(N)) LIKE unaccent(lower(?)) OR unaccent(lower(C)) LIKE unaccent(lower(?)))"
                + " AND (unaccent(lower(N)) LIKE unaccent(lower(?)) OR unaccent(lower(N)) LIKE unaccent(lower(?))))");
        assertThat(f.whereArgs()).containsExactly(
                "%Hall%", "%Hall%", "%Newcastle%", "%Newcastle%", "%Hall%", "%Newcastle%");
    }

    @Test
    void exactWebNameRanksFirstThenAWebNameEqualToAnyToken() {
        var f = new PlayerNameSearch(true).fragment("Hall Newcastle", "web_name", "N", "C");
        assertThat(f.exactFirst()).isEqualTo(
                "(unaccent(lower(web_name)) = unaccent(lower(?))) DESC,"
                + " (unaccent(lower(web_name)) = unaccent(lower(?))"
                + " OR unaccent(lower(web_name)) = unaccent(lower(?))) DESC");
        assertThat(f.orderArgs()).containsExactly("Hall Newcastle", "Hall", "Newcastle");
    }

    @Test
    void likeMetacharactersInATokenAreEscaped() {
        var f = new PlayerNameSearch(true).fragment("100%", "web_name", "N", "C");
        assertThat(f.whereArgs()).contains("%100\\%%");
    }

    @Test
    void aBlankQueryMatchesNothingRatherThanEverything() {
        var f = new PlayerNameSearch(true).fragment(" ", "web_name", "N", "C");
        assertThat(f.where()).isEqualTo("(1 = 0)");
        assertThat(f.whereArgs()).isEmpty();
        assertThat(f.exactFirst()).isEqualTo("(1 = 0) DESC");
        assertThat(f.orderArgs()).isEmpty();
    }

    @Test
    void withoutUnaccentTheFragmentDegradesToPlainLower() {
        var f = new PlayerNameSearch(false).fragment("Hall", "web_name", "N", "C");
        assertThat(f.where()).doesNotContain("unaccent").contains("lower(N) LIKE lower(?)");
    }

    @Test
    void aDatabaseThatRefusesTheExtensionDoesNotFailConstruction() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doThrow(new DataAccessResourceFailureException("read-only")).when(jdbc).execute(anyString());
        var f = new PlayerNameSearch(jdbc).fragment("Hall", "web_name", "N", "C");
        assertThat(f.where()).doesNotContain("unaccent");
    }
}
```

**Step 2 — run, expect compile failure:**
`cd backend && ./gradlew test --tests '*PlayerNameSearchTest'`

**Step 3 — implement:**

```java
package fi.karivanttinen.fplassistant.tools;

/**
 * The one place a player name typed by a user becomes SQL (KAR-57).
 *
 * <p>Three things the hand-written {@code LOWER(col) LIKE ?} lookups could not do: ignore accents
 * ("Muharemovic" is how Muharemović is typed on every keyboard that matters), match a name given
 * in more than one word against more than one column, and use the club — which is the very thing
 * the assistant asks for when a name is ambiguous. Every token must hit the name or the club; at
 * least one must hit the name, so "Newcastle" alone is not a player.
 *
 * <p>Folding removes accents and case. It never maps a name onto a similar-looking one: "konsa"
 * still does not find Konaté.
 *
 * <p>{@code unaccent} is ensured here rather than in a schema class because nothing else needs
 * it, and it is <b>probed, not assumed</b>: a startup statement that throws has taken production
 * down before. If the extension cannot be created or called the search degrades to plain
 * {@code lower()} — the pre-KAR-57 behaviour — and says so at ERROR.
 */
@Component
public class PlayerNameSearch {

    private static final Logger log = LoggerFactory.getLogger(PlayerNameSearch.class);

    static final int MAX_TOKENS = 6;

    private final boolean unaccent;

    @Autowired
    public PlayerNameSearch(JdbcTemplate jdbc) {
        this(probe(jdbc));
    }

    PlayerNameSearch(boolean unaccent) {
        this.unaccent = unaccent;
    }

    private static boolean probe(JdbcTemplate jdbc) {
        try {
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS unaccent");
            String folded = jdbc.queryForObject("SELECT unaccent('ć')", String.class);
            boolean ok = "c".equals(folded);
            if (ok) {
                log.info("Player name search: accent folding ON");
            } else {
                log.error("Player name search: unaccent('ć') returned '{}', accent folding OFF", folded);
            }
            return ok;
        } catch (DataAccessException e) {
            log.error("Player name search: unaccent unavailable, accent folding OFF", e);
            return false;
        }
    }

    static List<String> tokens(String query) {
        if (query == null) {
            return List.of();
        }
        return Arrays.stream(query.split("[\\s,]+"))
                .filter(t -> !t.isBlank())
                .limit(MAX_TOKENS)
                .toList();
    }

    Fragment fragment(String query, String webNameExpr, String nameExpr, String clubExpr) {
        List<String> tokens = tokens(query);
        if (tokens.isEmpty()) {
            return new Fragment("(1 = 0)", List.of(), "(1 = 0) DESC", List.of());
        }
        String name = fold(nameExpr);
        String club = fold(clubExpr);
        String web = fold(webNameExpr);
        String param = fold("?");

        List<String> all = new ArrayList<>();
        List<Object> whereArgs = new ArrayList<>();
        for (String token : tokens) {
            all.add("(" + name + " LIKE " + param + " OR " + club + " LIKE " + param + ")");
            whereArgs.add(pattern(token));
            whereArgs.add(pattern(token));
        }
        all.add("(" + tokens.stream().map(t -> name + " LIKE " + param)
                .collect(Collectors.joining(" OR ")) + ")");
        tokens.forEach(t -> whereArgs.add(pattern(t)));

        String exactFirst = "(" + web + " = " + param + ") DESC, ("
                + tokens.stream().map(t -> web + " = " + param).collect(Collectors.joining(" OR "))
                + ") DESC";
        List<Object> orderArgs = new ArrayList<>();
        orderArgs.add(query.trim());
        orderArgs.addAll(tokens);

        return new Fragment("(" + String.join(" AND ", all) + ")", List.copyOf(whereArgs),
                exactFirst, List.copyOf(orderArgs));
    }

    private String fold(String expr) {
        return unaccent ? "unaccent(lower(" + expr + "))" : "lower(" + expr + ")";
    }

    private static String pattern(String token) {
        return "%" + LikePatterns.escape(token) + "%";
    }

    record Fragment(String where, List<Object> whereArgs, String exactFirst, List<Object> orderArgs) {
    }
}
```

**Step 4 — run, expect PASS.** Adjust the expected strings in the test only if the implementation
above is what produced the difference (spacing), never the semantics.

**Step 5 — commit:** `Add accent-folding, tokenised player name search (KAR-57)`

---

### Task 2: Daily-capture lookups (`DailyPriceSource.matchPlayers`)

Serves `getPlayerStats` (current block), `getPriceHistory`, `getPlayerAvailability`.

**Files:** modify `tools/DailyPriceSource.java`; create `tools/PlayerNameSearchIntegrationTest.java`.

**Consumes:** `PlayerNameSearch.fragment(...)` from Task 1.

**Step 1 — failing integration test.** Same isolation pattern as `PriceToolsIntegrationTest`
(`@SpringBootTest`, `@ActiveProfiles("test")`, assert the table ends `_test`, delete by season in
`@BeforeEach`/`@AfterEach`, `FictitiousTestData.assertSafeSeason/assertSafeCode`). Season
`"test-ns"`, date `LocalDate.of(2026, 9, 18)`, autowire `DailyPriceSource`.

Seed (element_id, web_name, full_name, team_short, team_name, now_cost):

```
9201  Muharemović   Tarik Muharemović   LEE  Leeds      45
9202  Hall          Lewis Hall          NEW  Newcastle  55
9203  Hallgrimsson  Jon Hallgrimsson    NEW  Newcastle  60   -- pricier: must NOT outrank Hall
9204  Hall          Grant Hall          BUR  Burnley    40
9205  Konaté        Ibrahima Konaté     LIV  Liverpool  60
9206  Ødegaard      Martin Ødegaard     ARS  Arsenal    80
```

```java
private List<String> names(String query) {
    return source.matchPlayers(query, DAY).stream()
            .map(r -> r.get("web_name") + "/" + r.get("team_short")).toList();
}

@Test void unaccentedSurnameFindsTheAccentedPlayer() {
    assertThat(names("Muharemovic")).containsExactly("Muharemović/LEE");
}
@Test void unaccentedFullNameFindsTheAccentedPlayer() {
    assertThat(names("Tarik Muharemovic")).containsExactly("Muharemović/LEE");
}
@Test void aClubNarrowsAnAmbiguousSurnameAndExactWebNameBeatsPrice() {
    assertThat(names("Hall Newcastle")).containsExactly("Hall/NEW", "Hallgrimsson/NEW");
}
@Test void aClubShortNameNarrowsToo() {
    assertThat(names("hall NEW").getFirst()).isEqualTo("Hall/NEW");
}
@Test void strokedLettersFoldAsWell() {
    assertThat(names("Odegaard")).containsExactly("Ødegaard/ARS");
}
@Test void aClubAloneIsNotAPlayer() {
    assertThat(names("Newcastle")).isEmpty();
}
@Test void foldingNeverTurnsOneNameIntoASimilarOne() {
    assertThat(names("konsa")).isEmpty();          // Konaté is seeded; Konsa is not
}
```

**Step 2 — run, expect the accent/club tests to FAIL, `konsa` to pass:**
`./gradlew test --tests '*PlayerNameSearchIntegrationTest'`

**Step 3 — implement.** Add `PlayerNameSearch names` as a third constructor parameter of
`DailyPriceSource` and replace `matchPlayers`' body. Keep the javadoc, add one sentence pointing
at `PlayerNameSearch`.

```java
private static final String WEB_NAME = "web_name";
private static final String NAME = "web_name || ' ' || COALESCE(full_name, '')";
private static final String CLUB = "COALESCE(team_name, '') || ' ' || team_short";

List<Map<String, Object>> matchPlayers(String playerName, LocalDate date) {
    PlayerNameSearch.Fragment match = names.fragment(playerName, WEB_NAME, NAME, CLUB);
    List<Object> args = new ArrayList<>();
    args.add(date);
    args.addAll(match.whereArgs());
    args.addAll(match.orderArgs());
    args.add(MAX_NAME_MATCHES);
    return jdbc.queryForList("""
            SELECT element_id, web_name, full_name, team_short, element_type,
                   now_cost, cost_change_event, cost_change_start,
                   selected_by_percent, status, news, season, price_date
            FROM %s
            WHERE price_date = ?
              AND %s
            ORDER BY %s, now_cost DESC
            LIMIT ?
            """.formatted(table, match.where(), match.exactFirst()), args.toArray());
}
```

Fix every `new DailyPriceSource(` in tests (`grep -rn "new DailyPriceSource(" backend/src/test`)
by passing an autowired `PlayerNameSearch`. Remove the now-unused `Locale`/`LikePatterns` imports
from `DailyPriceSource` only if this change orphaned them.

**Step 4 — run the new test plus the three dependants, expect PASS:**
`./gradlew test --tests '*PlayerNameSearch*' --tests '*PriceTools*' --tests '*AvailabilityTools*' --tests '*DailyPriceSource*'`

**Step 5 — commit:** `Route daily-capture player lookups through PlayerNameSearch`

---

### Task 3: tapas `player` lookups (`PlayerTools.seasonRows`, `ExpectedStatsTools`)

**Files:** modify `tools/PlayerTools.java:225-253`, `tools/ExpectedStatsTools.java:83-93`; extend
`PlayerToolsTest`, `ExpectedStatsToolsIntegrationTest`.

**Consumes:** `PlayerNameSearch.fragment(...)`.

**Step 1 — failing tests.** Both classes seed through `TapasSeed`; add an accented player the way
`PlayerToolsTest.flipToCurrentSeason` inserts Okafor, into `TapasSeed.POPULATED_SEASON_ID` (9026)
with a fresh id (9311), team `TapasSeed.TEAM_ASHFIELD` ('Ashfield Rovers'/'ASH'), and delete it by
id in `@AfterEach`:

```java
jdbc.update("""
        INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                            element_type, now_cost, status, total_points, minutes,
                            goals_scored, assists, form, selected_by_percent)
        VALUES (9311, ?, ?, 'Tarik', 'Muharemović', 'Muharemović', 2, 45, 'a', 20, 360,
                0, 0, 3.0, 1.5)
        ON CONFLICT (id, season_id) DO NOTHING
        """, TapasSeed.POPULATED_SEASON_ID, TapasSeed.TEAM_ASHFIELD);
```

`PlayerToolsTest`:

```java
@Test void unaccentedFullNameFindsTheAccentedPlayersSeasonStats() {
    String result = playerTools.getPlayerStats("Tarik Muharemovic");
    assertThat(result).contains("Muharemović").doesNotContain("No player matched");
}
@Test void aClubAfterTheNameStillFindsThePlayer() {
    String result = playerTools.getPlayerStats("Muharemovic Ashfield");
    assertThat(result).contains("Muharemović").doesNotContain("No player matched");
}
```

`ExpectedStatsToolsIntegrationTest`: the same two phrases through
`tools.getPlayerExpectedStats(name, 5)`, asserting `doesNotContain("Player not found")`.

**Step 2 — run, expect FAIL:** `./gradlew test --tests '*PlayerToolsTest' --tests '*ExpectedStatsToolsIntegrationTest'`

**Step 3 — implement.** Add `PlayerNameSearch names` to both tools' constructors (keep any
convenience constructor working by delegating; update the manual `new PlayerTools(` /
`new ExpectedStatsTools(` sites in the three test classes).

`PlayerTools.seasonRows` — **argument order follows the text of the statement: the window's
`ORDER BY` comes before the `WHERE`**:

```java
PlayerNameSearch.Fragment match = names.fragment(playerName, "p.web_name",
        "p.web_name || ' ' || p.first_name || ' ' || p.second_name",
        "t.name || ' ' || t.short_name");
List<Object> args = new ArrayList<>(match.orderArgs());
args.add(seasonId);
args.add(second);
args.addAll(match.whereArgs());
// ... ROW_NUMBER() OVER (PARTITION BY p.season_id ORDER BY %s, p.total_points DESC) AS rn
// ... WHERE p.season_id IN (?, ?) AND %s
//     .formatted(match.exactFirst(), match.where()), args.toArray()
```

`ExpectedStatsTools.getPlayerExpectedStats` lookup — add the team join it lacks:

```java
PlayerNameSearch.Fragment match = names.fragment(playerName, "p.web_name",
        "p.web_name || ' ' || p.first_name || ' ' || p.second_name",
        "t.name || ' ' || t.short_name");
// SELECT p.id, p.web_name FROM player p
// JOIN team t ON t.id = p.team_id AND t.season_id = p.season_id
// WHERE p.season_id = ? AND %s
// ORDER BY %s, p.total_points DESC LIMIT ?
// args: seasonId, whereArgs..., orderArgs..., MAX_NAME_MATCHES
```

Do not touch `previousSeasonPlayerRows` (out of scope, see constraints).

**Step 4 — run, expect PASS:**
`./gradlew test --tests '*PlayerTools*' --tests '*ExpectedStatsTools*'`

**Step 5 — commit:** `Route season-stat player lookups through PlayerNameSearch`

---

### Task 4: Prompt and tool guidance

**Files:** modify `resources/prompts/system-prompt.st` (section "Player names are the user's, not
yours"), `PlayerTools.java` (not-found text ~line 80, `@Tool` description), the `@Tool`
descriptions of `getPlayerExpectedStats`, `getPriceHistory`, `getPlayerAvailability`; create
`chat/PlayerNamePromptContractTest.java`.

**Step 1 — failing contract test** (shape copied from `WildcardCoveragePromptContractTest.prompt()`):

```java
@Test
void promptAllowsAccentsAndClubsRetriesOnceAndReturnsToTheQuestion() throws IOException {
    assertThat(prompt()).contains(
            "\"konsa\" is Konsa, not Konaté",
            "ignores accents and case",
            "pass the club along with the name",
            "is the player they meant",
            "try once more with the surname alone",
            "go back and answer the question they originally asked");
}
```

**Step 2 — run, expect FAIL:** `./gradlew test --tests '*PlayerNamePromptContractTest'`

**Step 3 — replace the section's first three paragraphs** (keep the last paragraph, "A player who
is missing from this season's data…", unchanged):

```
## Player names are the user's, not yours

Look every player up with the name the user wrote, in its base form (the user may inflect it:
"Hallia", "Newcastlesta"). When the user gave a club, pass the club along with the name ("Hall
Newcastle"): the lookup uses a club to narrow a name, and it ignores accents and case, so
"Muharemovic" finds Muharemović. A match that differs from what the user typed only by accents,
case, an added first name or the club is the player they meant — answer, do not ask.

Never correct a name into one that merely looks or sounds similar: "konsa" is Konsa, not Konaté.
An unfamiliar or lowercase name is not a typo to be fixed — it is the name of the player being
asked about, and most of the league is unfamiliar. A lookup that returns a player whose name is
genuinely not what the user wrote does not answer their question: say which player the data
matched and ask whether that is who they meant, before building an answer on it.

If the name matches nothing, try once more with the surname alone, keeping the club if there is
one. If that finds a player, say who it matched and confirm before building on it. If it finds
nothing either, say so, say what you searched for, and ask for the club or a fuller spelling.
Never quietly answer about a different player: an answer about the wrong player is worse than no
answer, because nothing in it looks wrong.

Once the player is identified — by a lookup or by the user's reply — go back and answer the
question they originally asked. Telling the user what name a player is listed under is not an
answer to a comparison.
```

`PlayerTools` not-found text becomes:

```java
return season.provenance() + "\nNo player matched \"" + playerName + "\"."
        + " The lookup already ignores accents and accepts a club after the name. You may"
        + " retry once with the surname alone. Do not search for a similar-looking name or"
        + " answer about a different player: otherwise tell the user this name was not found"
        + " and ask for the club or a fuller spelling.";
```

Add to each of the four `@Tool` descriptions, after the sentence that introduces the name
parameter: `The name may be a surname, a full name, or a name followed by the club ("Hall
Newcastle"); accents are optional.` Update any existing test that asserts on the old not-found
sentence (`grep -rn "fuller spelling" backend/src/test`).

**Step 4 — run, expect PASS:** `./gradlew test --tests '*PromptContract*' --tests '*PlayerToolsTest'`

**Step 5 — commit:** `Let the assistant use clubs, retry a surname and finish the question`

---

### Task 5: Docs, full suite, PR, production check

1. `AGENTS.md`, Conventions — add one bullet after "Tools query Supabase via `JdbcTemplate`…":

   > **A player name typed by a user becomes SQL in one place, `PlayerNameSearch` (KAR-57).**
   > Accent-folded through `unaccent`, tokenised, club fields may narrow but never match alone,
   > exact folded `web_name` first. Do not hand-write `LOWER(web_name) LIKE ?` in a new tool. The
   > extension is probed at startup and the search degrades to plain `lower()` if it is missing —
   > a startup statement must never be able to take the app down. The cross-season `web_name`
   > bridges are not name *searches* and stay exact.

2. `docs/STATUS.md` — dated entry: the production evidence table above, root causes, the fix, the
   `unaccent` decision and its fallback, and the verification line from step 5 below.
3. Update `**PROGRESS:**` in this file.
4. Verify memory headroom (`docs/DEVELOPMENT.md`), then the full suite: `cd backend && ./gradlew test`.
   Expected: green. Commit `Document the player name search (KAR-57)`, push the branch (the
   pre-push hook reruns the suite — do not bypass), open the PR against `master`
   (≤ 8 lines, `Closes KAR-57`), review it with `/code-review`.
5. **After the owner merges and the deploy is green:**
   - `fly logs -a <app> | grep "Player name search"` → must read `accent folding ON`. If it reads
     OFF, the owner enables `unaccent` in the Supabase dashboard (Database → Extensions) and
     restarts the machine; nothing else changes.
   - In production chat: `Tarik Muharemovic vs Hall Newcastlesta wild cardilla, kumpi parempi` →
     both players resolved on the first turn and compared; the reply no longer links a player it
     says it cannot find (ticket item 4 — if it still does, file a separate frontend ticket).

## Self-review

- Every row of the evidence table has a test: accent (T2/T3), full name (T2/T3), club (T2/T3),
  "answer the original question" (T4 contract). Startup safety: T1 fallback tests.
- New failure modes considered: a club alone matching a whole squad (T2 `aClubAloneIsNotAPlayer`);
  a pricier substring outranking the exact name (T2 `Hallgrimsson`); unbounded tokens (T1 cap);
  `%`/`_` in a token (T1 escape); positional-argument order in the windowed query (called out in T3).
- Names used across tasks: `PlayerNameSearch.fragment(query, webNameExpr, nameExpr, clubExpr)`,
  `Fragment(where, whereArgs, exactFirst, orderArgs)`, `MAX_TOKENS` — identical in T1–T3.
