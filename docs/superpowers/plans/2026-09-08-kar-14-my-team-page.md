# KAR-14: A read-only My team page

**PROGRESS:** Task 5/5 done — implementation reviewed against the plan, findings fixed and regression verification complete.

**Ticket:** [KAR-14](https://linear.app/quantti/issue/KAR-14/a-my-team-page-in-the-frontend)
— Todo, **Low (4)**, checked in Linear on 2026-09-08. Kari had nine non-archived Todo tickets;
this was the only one with an assigned priority, the other eight being No priority (0).
No priority is not Urgent: it sorts after assigned priorities. No tie, so no random draw was
needed. Backlog and started items were not candidates. No comments or blocking relations.

**Goal:** Let a signed-in user see their own squad, bank and chip availability without sending
a chat message or spending their message allowance. Reuse `MyTeamService` unchanged.

**Basis:** `master` at `1928356`, `docs/STATUS.md` (my-team advice and both 2026-09-08 Free Hit
entries), `docs/DEVELOPMENT.md`, and
`docs/superpowers/specs/2026-08-23-my-team-advice-design.md`. Current code and STATUS override
that older spec's price-source table: player identity, price and flags come from the service's
cached FPL bootstrap, not from `fpl_daily_price` or tapas.

## Decisions and scope

1. Add `/my-team` to the existing app menu and small router. Use a readable list, not a pitch
   graphic: Starting XI and Bench, FPL order, player name, club, position, captain/vice, market
   price and availability. Keep the existing shell, sidebar, theme and mobile scroll layout.
2. Add authenticated `GET /api/my-team`, returning purpose-built JSON rather than the chat
   tool's rendered prose or a raw serialization of its service records. The account comes only
   from the JWT; the stored FPL entry is resolved by `MyTeamService.myTeam`.
3. Display team name, bank and recorded squad value for the primary squad, with its snapshot
   gameweek/restoration context. Show the chip used for the locked gameweek and chip availability
   for the service's `chipEvent` (next deadline). Do not infer chip windows or remaining chips in
   TypeScript. Unknown is not none.
4. **Owner decision, 2026-09-08:** the continuing squad is the primary view during a Free Hit,
   not two equally prominent teams. Show a small "Free Hit · GW N" notice and an optional,
   initially collapsed view of the temporary squad. Never substitute its fifteen or funds for
   an unavailable continuing team. The chat tool's two-block rendering remains unchanged.
5. No new database tables, FPL client, cache, polling, router/test framework, LLM calls, prompts,
   mini-league views, transfers, live points or statistical dashboard. Forecast/outcome and
   Free Hit-versus-continuing comparisons are deferred to
   [KAR-50](https://linear.app/quantti/issue/KAR-50/review-free-hit-points-against-forecasts-and-the-continuing-squad),
   not a blocker or placeholder UI here. Omit form, ownership, xG/xA and FDR from this first page.
   The service may still calculate enrichment internally; bypassing that is a separate service
   change, not necessary for this ticket.

### Free Hit presentation

Keep the normal page structure. Above the continuing squad, a compact notice and disclosure:

```text
My team
Free Hit · GW 5 — Showing your continuing squad
▸ View Free Hit squad

Continuing squad — restored at the GW 5 deadline
Bank … · Recorded squad value …
Starting XI / Bench (GW 4 choices)
…
```

Use native `details`/`summary`, closed by default. Opening it shows the temporary GW 5 squad
and its own funds inline; it neither replaces the primary squad nor makes another API call,
because the response already carries both. No modal, equal-weight tabs or pitch comparison.
The Free Hit label is tied to the named gameweek, not an unqualified "active now" claim: these
picks remain published after restoration and may still be scoring or already be finished.

When the continuing squad is unknown, replace the primary squad/funds area with an explicit
unavailable message and reason. The optional Free Hit disclosure remains available and closed;
never expand it automatically as a fallback. Without a Free Hit, no notice/disclosure appears.

### Labels are part of correctness

- This is the squad published for a **named locked gameweek**, not a view of unpublished
  transfers. Keep the post-deadline-transfer limitation visible even on a successful page.
- Bank and squad value are from the displayed squad's own picks snapshot: during a Free Hit,
  the primary funds come from `Continuing.Squad.locked`, never `Result.Squad.locked`. Player
  market prices are from the cached bootstrap and can describe a different moment. Do not
  recompute the recorded squad value by summing current market prices. Keep all monetary
  values in integer tenths until display.
  Selling prices and free-transfer counts are unavailable; do not imply exact transfer funds.
- `Result.Squad.fetchedAt` is set when the result is assembled, even on cache hits. Expose it as
  `loadedAt` and label it "Loaded", not "FPL updated". State that FPL responses/player details
  may be cached and an older player snapshot can survive a refresh failure. True upstream
  capture timestamps are not exposed today; do not invent them or promise a hard freshness cap.
- A continuing squad is restored **at the Free Hit gameweek's deadline**, from the previous
  gameweek's picks. Its displayed bank is the restored estimate, before any subsequent
  transfers. Its captain and bench ordering are previous-gameweek choices, not a recommended
  next-gameweek lineup; label those subsections with the source gameweek.
- Only FPL-sourced identity/funds/chip data is rendered here. No tapas statistics are attributed
  to this snapshot, and the chat tool's separate statistical provenance remains unchanged.
  If statistics are added later, they need their own `SeasonContext` attribution and season gate.

## Task 1 — Define and test the page response

**Create**, under `backend/src/main/java/fi/karivanttinen/fplassistant/myteam/`:
`MyTeamResponse.java`, with explicit nested page DTOs and a mapping from `MyTeamService.Result`.
**Create** corresponding `myteam/MyTeamResponseTest.java` under the test source root.

Use an explicit `status` discriminator. Pin these mappings before wiring HTTP:

| Service result | HTTP (Task 2) | JSON status | Page behavior |
|---|---|---|---|
| `Squad` | 200 | `ready` | Squad, funds, chips and snapshot caveats |
| `NoTeamId` | 200 | `no_team_id` | Explain setup; button to Settings |
| `TeamNotFound` | 200 | `team_not_found` | Stored id needs checking; button to Settings |
| `NoSquadYet` | 200 | `no_squad_yet` | No published squad yet; explain deadline limitation |
| `Unavailable` | 503 | `unavailable` | FPL temporarily unavailable; Retry |

The three non-ready 200s describe the caller's configured resource, not a malformed request.
Do not turn an FPL outage into a team-id validation error or expose upstream exception text.

The ready payload contains only what the page renders:

- Entry id/name; locked gameweek; nullable ISO deadline; `loadedAt`.
- Locked squad: bank/value in tenths, active chip, ordered player rows. Rows carry element id,
  pick position, captain/vice, position type, display name, club, nullable market price and
  availability. Use `Element.availability()` rather than reimplementing FPL flag meanings.
- Chips: `known`, target gameweek, playable list, opening-later list and nullable opening GW.
- Continuing state: `not_applicable`, `available` (source gameweek, bank/value in tenths, player
  rows), or `unknown` (the service's reason). Unknown continuing funds are absent, never zero.

Map a missing bootstrap element to an explicit `Element <id>` fallback using the pick's type;
do not lose the pick, captain flags or ordering. Omit manager legal name, league memberships,
raw enrichment and unused player statistics rather than serializing `EntrySummary` wholesale.

**Verify:** Pure tests cover all result variants, ordinary/Free Hit/unknown-continuing squads,
known-empty versus unknown chips, later chip windows, a missing element and missing deadline.
Pin integer money (`75` stays `75`), distinct Free Hit/restored bank and value, and absence of
unneeded personal data. Existing service construction/rendering and its tests stay untouched.

## Task 2 — Expose the authenticated, bounded endpoint

**Create:** `myteam/MyTeamController.java` and `myteam/MyTeamControllerTest.java`.
**Modify:** `config/RateLimitFilter.java`, its test, and `config/SpaForwardingTest.java`.

- Follow `UserProfileController`: `@AuthenticationPrincipal Jwt` → `TokenSubject.userId(jwt)`
  → `myTeam.myTeam(userId, new FplBudget(allowance))` → the Task 1 DTO. Read `allowance` from
  existing `fpl.myteam.max-requests-per-turn`; never keep the mutable budget on a singleton.
- No account/team path or query parameters. Never call the `@Tool` wrapper, chat service,
  usage accounting or LLM. Keep all existing service caches and FPL timeouts.
- Return the statuses above and ensure authenticated responses have `Cache-Control: no-store`.
  Browser/shared response caching must not retain one account's squad. This does not disable
  the existing server-side entry/squad caches.
- Add `/api/my-team` to the existing shared 20/minute per-IP rate limit: reading this page
  spends outbound FPL requests (chip history is not cached), even when the squad is cached.
  Do not add a separate limiter or change the allowance for existing endpoints.
- `SecurityConfig` already secures `/api/**`, and `SpaForwardingController` already forwards
  `/my-team`. Add regression assertions, not new permits or forwarding rules.

**Verify:** MockMvc with local signed JWTs and a mocked `MyTeamService` proves missing/invalid
JWT rejection, invalid UUID-sub rejection, each result's actual JSON/status/header, and that
users A/B select only their own UUID. Supplying another team/user id in query parameters must
not alter that identity. Assert each call receives a distinct budget with the configured
allowance. Prove the new path shares the rate-limit bucket and `/my-team` forwards to
`index.html` while `/api/my-team` never does. Isolate rate-limit test IPs/counters so the suite's
requests cannot accidentally exhaust one another's quota.

## Task 3 — Add the authenticated page loader

**Create**, under `frontend/src/components/MyTeamPage/`: `myTeam.ts` and `myTeam.test.ts`.
Keep page response types and its small request/result helper together; no generic data layer.

Use `apiFetch('/api/my-team', { signal })`, preserving its existing token refresh and one-time
401 retry. A terminal 401 belongs to the sign-in flow, not a "team not found" message.
Handle 503, 429 (the filter returns plain text), other HTTP failures, invalid JSON and a failed
network request distinctly enough to offer an honest retry without displaying raw server text.
Unknown/invalid response status must fail visibly, never look like an empty squad.

The page will load on mount and on explicit Retry, not poll or bypass server caches. During a
load disable duplicate retries. Pass an `AbortSignal` through the helper, check it after awaits,
and return no state to publish after abort; Task 4 also guards its state updates. Navigating
away/unmounting aborts the request. Returning from Settings starts a new load, so changing the
stored entry cannot leave the previous team's page cached in the browser.

**Verify:** Existing Node-only Vitest runs `*.test.ts`; mock `apiFetch` so tests need no Supabase
key. Test all response states, 401, plain-text 429, 503, unexpected status/body, network failure,
and controlled late completions after abort, including delayed JSON decoding. Test £ formatting
and chip/continuing display decisions where they are extracted for use by the actual page.
Pin primary-squad selection: ordinary locked squad, known continuing squad with its own funds,
or unavailable — never the Free Hit squad when continuing is unknown. Do not claim these tests
mount a React effect or exercise browser navigation.

## Task 4 — Render and integrate My team

**Create:** `MyTeamPage.tsx`, `MyTeamPage.module.css`, and a small `SquadSection.tsx` in the
same directory. The squad section is reused by the locked and continuing blocks.
**Modify:** `frontend/src/router.ts`, `components/AppMenu/AppMenu.tsx`,
`components/ChatView/ChatView.tsx`, and the team-id description in
`components/SettingsPage/SettingsPage.tsx`. **Review amendment:** also modify `frontend/src/App.tsx`
and add `frontend/src/App.test.ts` to key the signed-in shell by `session.user.id`. A cross-tab
account replacement need not pass through a signed-out state; the key clears the previous account's
page/disclosure and aborts its load while preserving the shell on a same-user token refresh.

- Add `/my-team` to the route union/path whitelist and a "My team" menu item; render the page
  beside Usage/Settings. Keep `ChatView` mounted so changing pages does not reset conversations
  or an in-flight chat. The composer remains restricted to `/`.
- Wire the Task 3 loader from an effect with a fresh `AbortController`, cleanup and guarded
  post-await updates. Local page state only: no localStorage or cross-account page cache.
- Render the decided list-based content and all setup/empty/error/partial states. Unknown chips
  do not hide a known primary squad. Unknown continuing picks produce a primary-area warning,
  not a Free Hit fallback; its temporary squad remains behind the optional disclosure. Unknown
  player prices/details render as unavailable, not zeros. No guessed completion of a short list.
- During a Free Hit, show the continuing squad and its own bank/value as the main content, with
  the source-GW captain/bench disclaimer. Put the small gameweek-labelled notice/disclosure
  above it as described earlier. Disclosure contents use the temporary squad's own labels and
  funds, are closed on page mount, and are never persisted across navigation or accounts.
- Reuse existing Mantine controls and CSS tokens for light/dark themes. One `h1`, semantic
  sections/lists, labelled captain/vice indicators, keyboard-operable Retry/Settings buttons,
  announced loading/error states and visible focus. No information conveyed by color alone.
- Follow `UsagePage`'s flex/`overflow-y: auto` content area, not another viewport-height wrapper
  or a wide table. Keep the existing keyboard-aware app shell unchanged.
- Replace Settings' stale "Nothing uses it yet" description with its My team/chat purpose.

**Verify:** `npm test` and `npm run build`. Browser checks (not covered by Node tests): menu,
`/my-team` reload/deep link, Back/Forward, Settings→save/change/remove→My team, route exit during
load, sign-out while loading, and return to the unchanged chat. Exercise normal, Free Hit and
partial/error responses with deterministic network fixtures where needed. Verify the Free Hit
disclosure is initially closed, opens/closes with keyboard and pointer without a network call,
and never overwrites the primary funds; unknown continuing picks must not auto-expand it.
Check narrow mobile width, portrait/landscape, long names/news, scrolling, dark mode and keyboard
navigation. Record what was actually checked; device tests must not be claimed from static
rendering.

## Task 5 — Regression verification and handoff

Run tests against the disposable database only:

```bash
scripts/test-db.sh up
cd backend
source "$HOME/.sdkman/bin/sdkman-init.sh"
set -a; source .env.test; set +a
./gradlew cleanTest test --tests '*MyTeam*' --tests '*MiniLeague*' \
  --tests '*RateLimitFilterTest' --tests '*SpaForwardingTest'
./gradlew cleanTest test
cd ../frontend
npm test
npm run build
```

Controller/service tests use local JWT fixtures, mocks and test-owned rows; never real FPL as
an oracle or production Supabase. The full suite still needs its configured OpenAI embedding
key. A frontend build needs the existing `.env.local`. Inspect actual results, not UP-TO-DATE.
No production sync, ingestion, deploy or user-data lookup is needed for this implementation.

After implementation, add the endpoint and its reuse of the existing on-demand FPL exception
to `docs/DEVELOPMENT.md` and the relevant `AGENTS.md` convention; append the outcome, Free Hit
semantics, cache-time caveat and actual test/browser results to `docs/STATUS.md`. Update the
single progress line once per completed task. Review for accidental changes to service/chip
logic, identity handling, statistical provenance, schema, chat transport or quotas.

Work on a branch based on current `origin/master`, carrying this plan. If asked to submit the
implementation, push through the normal hook and open a short PR linking the plan and closing
KAR-14. The owner merges; never push to master or deploy manually. Planning alone does not
change the ticket's status or mark it complete.

**Planning verification:** Read the live Todo list, ticket, relations and comments, backend
service/HTTP/auth/test contracts and relevant frontend files. A read-only scout checked the
frontend integration and existing test constraints. An independent read-only review found no
actionable defects in the initial plan; the owner subsequently changed the Free Hit presentation
to continuing-first with an optional disclosure, recorded above. Created the requested KAR-50
follow-up in Backlog (Low), related to KAR-14, KAR-15 and KAR-49; KAR-14's status is unchanged.
Browser behavior remains unverified until implementation. No application code, tests or
dependencies were changed; no tests, production DB queries, commits, pushes or deployments
were performed during planning.

## Implementation and review outcome — 2026-09-08

All five tasks are implemented without changing `MyTeamService`, its caches, chip logic, the
schema, prompts, statistical provenance or chat transport. The controller tests use a signed-JWT
`@WebMvcTest` slice with the real security configuration and a mocked service; a full extra Spring
context exhausted the disposable database's connection limit and was unnecessary for this HTTP
contract. The budget test uses a non-default allowance so a hardcoded 15 cannot pass it.

The plan-conformance review fixed cross-tab account replacement retaining the outgoing user's
state (the `App.tsx` amendment above), explicit list semantics for Safari on unstyled squad lists,
and partial-state wording: unknown chips no longer claim an unknown continuing squad is known,
and the post-deadline transfer limitation is visible on every ready page, including Free Hits.
The new shell-key regression failed before the account fix; the deterministic browser fixture
also reproduced the contradictory partial-state message before it was removed.

Verification after fixes: full backend `cleanTest test` 683/683; frontend Vitest 33/33; production
build and contrast gate passed. Headless Chromium with local API/session fixtures covered deep
links, Back/Forward, Settings save/change/remove, chat preservation, normal/Free Hit/partial/error
states, retries, route/sign-out aborts, cross-tab A→B replacement both after loading and during a
pending request, same-user token refresh without a reset, disclosure keyboard/pointer controls,
dark mode and 390×844 / 844×390 scrolling with long content. The explicit list role was inspected;
Safari/VoiceOver and physical iOS/Android device behavior remain manual checks, not claims from
Chromium. No new test framework or production data lookup was needed.
