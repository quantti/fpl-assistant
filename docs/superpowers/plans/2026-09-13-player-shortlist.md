# Personal player shortlist

**PROGRESS:** Task 5/6 done — shortlist page, dedicated header navigation and deep-link routing are complete.

## Approved scope

One account-owned persistent Shortlist. Every recognized player mention in assistant chat, including tables, offers a touch/keyboard add/remove toggle. Membership highlights the name using theme colors and a filled star; all occurrences share state. Tapping again removes. Basic page: name, club, position, remove; no metrics, notes, fixtures or model access. Dedicated My team and Shortlist(count) header buttons; move My team out of the menu. Persist across browsers through authenticated backend API.

Work exclusively in `/home/vanka/dev/fpl-assistant-shortlist`, branch `feature/player-shortlist`. Do not alter another worktree or reset another agent's database. Owner merges the reviewed PR; never push master or deploy manually.

## Design

Use application-owned recognition against validated stored daily-capture identities, not model-generated IDs or fuzzy guesses. Keys include season and element ID. Exact whole-name aliases, longest match first; ambiguous aliases remain plain. Match rendered Markdown text including tables and inline formatting; never nest controls in links. Unknown mentions remain plain rather than saving the wrong player.

Bind each new assistant answer to an immutable identity catalogue taken from a coherent stored capture. Deduplicate persisted catalogue contents, store a nullable catalogue reference on our own message table, send catalogue metadata through SSE and return it with history. No shortlist content enters model history. Existing messages without identity evidence remain plain: never reinterpret old names against a different season's roster. Catalogue failure must not break chat.

Authenticated GET/PUT/DELETE `/api/shortlist` contract uses JWT identity only. PUT validates season/player against the stored catalogue and derives display fields server-side. Idempotent desired-state operations; unique account/season/player key. Store display snapshot so removal and listing survive capture changes. Retain old-season entries with a season label where necessary. No new chat-time external APIs.

One account-scoped frontend provider owns membership, loading/error states and per-player pending mutation. Pessimistic updates with stale-request guards; shared highlighting and count. Visible focus, non-color selection indicator, sufficient touch areas. No new browser-test framework.

## Sequential subagent units

1. Identity catalogue: validate stored capture, immutable persistence, deterministic aliases, configuration and seeded tests. Verify season ID reuse, ambiguity, malformed/empty captures and stable catalogue reuse.
2. Shortlist schema/store/API: JWT-only ownership, authoritative display fields, idempotent writes and no-store responses. Verify isolation, malformed references, duplicates, deletion and season-scoped keys.
3. Chat/history metadata: bind catalogue once per turn and persist with completed answer; SSE and history transport. Preserve detached generation, single model subscription, event ordering, turn ownership and text-only model history. Verify lifecycle and historical binding.
4. Frontend shared state and player mentions: transport metadata through streaming/history, render all recognized occurrences with accessible toggles. Verify Markdown, ambiguity, repeated mentions, errors and stale requests.
5. Shortlist page and header: basic list, loading/error/empty states, dedicated header buttons, route/deep link forwarding. Verify navigation, count, mobile layout and shared removal.
6. Full verification and review: run isolated seeded backend suite, frontend tests/build and available browser checks; document actual outcomes in STATUS. Commit, push feature branch using normal hook, open PR to master, review and apply fixes as new commits.

Update the single progress line once each unit completes. Each worker returns control before the next dependent unit.

## Unit 1 outcome

Three application-owned tables persist catalogue headers, season-scoped player identity snapshots
and unambiguous aliases. A season-and-content fingerprint reuses an unchanged identity catalogue
across daily captures; volatile price, availability and capture-time fields do not create duplicate
catalogues. Changed identities create a new catalogue and never update an old one.

Aliases come only from FPL's stored `web_name` and `full_name`. Matching keys are case-insensitive
with collapsed whitespace, output order is longest first with deterministic tie-breaks, and an alias
owned by more than one player is omitted. A capture is rejected as a whole when empty, when required
identity is missing or invalid, when one club id has conflicting names, or when its rows have mixed
capture timestamps. No hard-coded player count is imposed: identity validity does not depend on the
number of registered players, and the daily writer already makes a successful response atomic.

Focused verification used the ignored `backend/.env.test`, reserved to the dedicated
`fpl-shortlist-test-db` container on port `55438`; no shared test container or Supabase database was
touched. `PlayerCatalogueStoreTest`: 5 tests, 0 failures/errors/skips.

## Unit 2 outcome

`fpl_player_shortlist` stores one display snapshot per account, season and element ID, with a
foreign key back to the immutable catalogue player that authorized the write. `PUT /api/shortlist`
accepts only `catalogueId`, `season` and `elementId`; it rejects any mismatch and derives every
returned/stored display field from `PlayerCatalogueStore`. Repeated PUTs update the same desired
membership, while repeated DELETEs are successful no-ops. GET and DELETE need no live capture, so
old-season entries remain listable and removable after capture data changes.

All three operations derive ownership only from the verified JWT subject and return
`Cache-Control: no-store`. The test profile pins the table to `fpl_player_shortlist_test`; seeded
tests clean only their own prefixed seasons and fixed account IDs and cover schema re-entry,
authoritative validation, malformed references, duplicate writes, account isolation,
season-scoped element IDs, capture-independent listing and deletion.

Focused verification used the same dedicated `fpl-shortlist-test-db` container on port `55438`:
`ShortlistApiTest` 6 tests and `PlayerCatalogueStoreTest` 5 tests, 0 failures/errors/skips.

## Unit 3 outcome

Each accepted chat turn now selects the latest valid identity catalogue exactly once. Its metadata
is emitted before answer tokens as `event: catalogue`; a completed assistant row stores that same
catalogue's nullable UUID. Selection or lookup failure is logged and omitted, so the answer and
legacy history remain plain. The detached model subscription, completion-only persistence,
terminal error/done ordering and token accounting paths are unchanged. Catalogue data never enters
`recentTurns`, `HistoryBudget` or Spring AI messages.

Frontend transport handoff: after `conversation` and optional `truncated`, a successful binding
adds one `catalogue` event before the first unnamed token event. Its JSON data is
`{ catalogueId, season, players: [{ elementId, webName, fullName, teamShort, teamName, elementType,
aliases }] }`. No event means plain chat. `GET /api/conversations/{id}/messages` adds the identical
shape as optional `catalogue` on only bound messages; the property is absent for user, legacy and
degraded messages. Token JSON strings plus `error` and `done` are unchanged.

Focused verification used only `fpl-shortlist-test-db` on port `55438`. The combined run covered
`ChatCatalogueMetadataTest` (3), `ChatControllerTest` (16), `ChatSseLifecycleTest` (13),
`ConversationStoreTest` (5), `UserSchemaTest` (6), plus prior `PlayerCatalogueStoreTest` (5) and
`ShortlistApiTest` (6): 54 tests, 0 failures/errors/skips.

## Unit 4 outcome

The frontend now carries optional catalogue metadata from the named SSE event into both the live
assistant turn and its completed message, and preserves the same optional property when loading
history. `ChatRequest` remains only `{ conversationId, message }`. Catalogue callbacks use the
existing owning-turn guard, so a replaced or aborted stream cannot attach stale identity evidence
to another transcript.

An account-keyed `ShortlistProvider` loads `GET /api/shortlist` once and owns membership, count,
loading/error and per-player mutation state. PUT and DELETE use the backend's exact desired-state
JSON contracts and update pessimistically. Duplicate mutations are suppressed; provider cleanup
aborts both the initial load and account-owned mutations, while request tokens reject stale
settlements. Mutation/load failures preserve membership and are announced in a visible alert.

Assistant Markdown recognition uses only that answer's immutable aliases. It removes ambiguous
aliases, matches longest aliases first with Unicode letter/number boundaries and collapsed
whitespace, and handles repeated mentions, GFM table cells and names split across inline formatting.
Existing links are hard barriers, and generated controls are identified by application-only AST
metadata rather than model-authored URLs, so no interactive control is nested in or forged by a
link. Every occurrence is a native keyboard button with `aria-pressed`, an action label, visible
focus, coarse-pointer touch sizing, theme-pitch selected colour and outlined/filled stars; all
occurrences read the same provider membership.

Frontend verification: `npm test` — 10 files, 92 tests, all passing. `npm run build` passed with a
non-secret test-only `VITE_SUPABASE_ANON_KEY` value, including contrast checks, TypeScript and the
production Vite build; only the existing chunk-size advisory was emitted. Review findings applied:
mutation abort on account change, cross-format boundary matching, recursive link/code barriers,
unforgeable mention markers and visible error feedback.

Unit 5 handoff: `useShortlist()` exposes `players`, `count`, `loading`, `error`, season-scoped
`isSelected`/`isPending` and `toggle`; `ShortlistProvider` already wraps the signed-in shell and is
keyed by account. Build the page/header against that shared state without adding a second fetch or
moving shortlist content into chat/model requests.

## Unit 5 outcome

The signed-in shell now routes `/shortlist` to a minimal account-owned page backed only by the
existing `ShortlistProvider`. It shows loading, load-failure and empty states, then name, club,
position and a pending-aware Remove action. Removal uses the same season-scoped provider mutation
as chat mentions, so the page, header count and every occurrence in chat update together without a
second fetch. A failed mutation keeps the loaded list visible while the provider's existing alert
reports the failure.

My team and `Shortlist (count)` are dedicated header buttons; My team no longer appears in the
overflow menu. The header can wrap onto a second row rather than clip either button at 320px, while
the desktop header retains its minimum height. Changing pages keeps the signed-in `ChatView`
mounted, preserving its conversation and streaming state. `/shortlist` is recognized by the client
router and covered by the existing Spring SPA forwarding boundary, while `/api/**`, assets and
actuator behavior remain unchanged.

Verification used the existing frontend unit harness and the dedicated `fpl-shortlist-test-db`
container on port 55438 only. `npm test`: 14 files, 99 tests passed. `npm run build` passed contrast,
TypeScript and the production Vite build with only the existing chunk-size advisory. Focused
`SpaForwardingTest`: 8 tests passed, including the new `/shortlist` deep link.

## Bounded pre-review findings (2026-09-13)

Five findings were fixed before the full Unit 6 review. Streaming answers no longer receive catalogue
metadata in the rendered message, so a provisional token such as `Son` cannot be mutated before it
finishes as `Sonny`; the immutable catalogue is attached only when the completed answer is committed.
An initial shortlist GET failure now leaves the count explicitly unknown, preserves the visible error,
disables mutations and offers a guarded retry; only the newest load may settle, and successful retry
is the only path that re-enables mutations.

Conversation history now returns `{ messages, catalogues }`: each message has only an optional
`catalogueId`, while each catalogue payload appears once in the keyed map. The frontend resolves that
shape and still accepts the legacy array with optional inline catalogue metadata; legacy rows without
a reference remain plain, failed catalogue reads omit the reference, and the existing owner-only 404
boundary is unchanged. Mention recognition now includes inline-code names in prose and GFM tables,
preserves the rendered `<code>` child inside the application button and still treats authored links as
hard barriers. Every shortlist row now labels its season (`2026/27 season`) so mixed and retained
historical entries are distinguishable.

Verification used only the dedicated container configured on port 55438. Focused backend history tests
(`ChatCatalogueMetadataTest`, `ConversationControllerTest`, `ConversationStoreTest`) passed: 13 tests,
0 failures/errors/skips. Full frontend `npm test` passed: 14 files and 103 tests. The production frontend
build passed contrast checks, TypeScript and Vite with a non-secret test-only publishable-key value; only
the existing chunk-size advisory remained. A scoped re-review of these five fixes returned clean.

## Verification environment

Use a dedicated disposable test container and port, not the shared container or Supabase. Read `docs/DEVELOPMENT.md` and `scripts/test-db.sh` first. Configure ignored worktree env files for that container only. Never print secrets. Hook resets must target this same isolated container. Report unavailable checks honestly.

## Tracking and delivery

Linear is authoritative. Use a verified existing or newly created ticket only if tracker access is available; do not invent an identifier or create a GitHub Issue. PR body at most eight lines. No implementation changes to the main worktree.
