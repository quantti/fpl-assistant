# KAR-52 — keep aborted chat turns from clearing the next turn

**PROGRESS:** Implementation Task 3/3 done.

Issue: [KAR-52](https://linear.app/quantti/issue/KAR-52). Checked in Linear on 2026-09-12:
Backlog, Medium, related to KAR-8, no blocking relations. Baseline: `master 13be2ae`.
Implementation and frontend verification completed on the dedicated feature branch; no browser or
backend check was run.

## Goal and selection

After conversation replacement or unmount, an old turn must not change the active transcript,
conversation identity, streaming text, single-flight state or completion notification. A new turn
must remain the only active send even if an aborted promise settles late or two sends happen
before React rerenders.

Selected after KAR-36: a bounded, user-visible bug explicitly left open by merged KAR-8 / PR #79.
The old local KAR-8 plan was stale; current master marks it 4/4 complete and STATUS names this race
as out of scope. Do not reopen KAR-8 or repeat its transport extraction. Independent of KAR-36 and
KAR-48; recommended order is an execution convenience, not a blocking dependency.

Frontend-only: preserve `useChat`'s public API, existing messages/notices, HTTP/SSE contract and
401/404/429 handling. No parser hardening, timeout, automatic retry, backend cancellation change,
conversation-fetch refactor or polling.

## Evidence and decisions

- `frontend/src/hooks/useChat.ts` guards sends with captured React `isStreaming` state, while
  `abortRef.current` is assigned synchronously but never cleared on completion/replacement.
- `replaceMessages` aborts A and immediately resets the view. B can start before A settles;
  A's unconditional `finally` then clears B's streaming state and invokes `onTurnFinished`.
- The post-await abort guard added by KAR-8 protects outcome messages, not `finally`. The
  `onConversation`, `onToken` and `onTruncated` callbacks also need turn ownership checks.
- The optimistic append uses captured `messages`; replace-and-send in one batch can restore an
  old transcript. A functional update is necessary to keep the replacement authoritative.
- `ChatView.tsx` supplies `useConversations.refresh` as `onTurnFinished`. This refreshes sidebar
  titles/order, not a guaranteed-success event. Notify once when the still-owning turn settles,
  including failure/unauthorized outcomes. Suppress notifications from replaced/unmounted turns,
  including replacement with no subsequent send. Immediate abort is not evidence that the server
  has persisted its detached answer; do not add a refresh or poll pretending otherwise.
- Controller identity is enough: use `abortRef.current === controller` as the single ownership
  token, not an additional turn counter or a state-management abstraction.
- Tests currently use Node-only Vitest and `src/**/*.test.ts`. Add a mounted-hook regression in a
  single jsdom test file. Proposed dev dependency: `jsdom@^27`, compatible with the documented
  Node 24 toolchain. Use existing React 19.2 and `react-dom/client`, not deprecated react-test-renderer
  or a new hooks-testing library. Preserve Node as the default for all existing tests.

## Task 1 — reproduce the race with the real mounted hook

Files: proposed `frontend/src/hooks/useChat.test.ts`, `frontend/package.json` and
`frontend/package-lock.json`.

Use `// @vitest-environment jsdom` in this `.test.ts` file. A file-local component created with
`React.createElement` exposes the latest hook return; mount with `createRoot` and use React's `act`
for rendering, callbacks, promise settlement and unmount. Set/restore `IS_REACT_ACT_ENVIRONMENT`
and clean up roots/mocks between tests. Do not simulate hooks with mocked `useState`/`useRef`.

Before partially importing `chatTransport`, add a hoisted mock of `../auth/apiFetch`, following
`chatTransport.test.ts`; otherwise the real import initializes `supabaseClient` and requires an
auth key before the hook mounts. Retain transport constants and replace only `sendChatTurn` with
controlled deferred promises. Record request, signal and callbacks per call. Crucially, aborting
A must not settle A's promise automatically; the test owns the delay. No sleeps or real network.

Pin these regressions before changing the hook:

1. Start A, replace the transcript and rerender options for conversation B, start B and emit B's
   text. Settle A while B is pending. B's text and `isStreaming` stay intact, no finish callback
   fires for A, and a third send is refused. Complete B: append once, release state and notify once.
2. Call the same captured `sendMessage` twice in one `act`: one transport call and one optimistic
   user message, even before a React rerender can update the state closure.
3. Replace transcript and send in one batch: retain the replacement messages, not the old render's
   messages. Rerender options separately when changing conversation id; do not assert that a stale
   captured callback somehow receives new props.
4. Deliver A's late token, conversation and truncation callbacks; settle A as completed, failed or
   gone. None may alter B's state/messages/identity or notify completion for A.
5. Replace without sending again, and unmount while pending: abort the signal, then settle/call
   back late; there must be no completion/conversation callback from the detached turn.

Verify intended assertions fail against the existing hook, not because the harness cannot mount.

**Task 1 outcome:** 8 mounted-hook regressions added; 8/8 fail on the intended ownership assertions. TypeScript passes with no harness or syntax errors.

## Task 2 — give all asynchronous effects one owner

File: `frontend/src/hooks/useChat.ts`; extend the mounted tests.

- Reject sends while `abortRef.current` is occupied. Install the new controller synchronously
  before state updates and transport invocation. Keep `isStreaming` as presentation state only.
- Use a functional optimistic-message append; remove only dependencies made obsolete by this
  change. Preserve the hook's options and return type.
- Guard metadata/token callbacks and post-await outcome handling with controller identity and a
  non-aborted signal. This includes truncation notices, 404 identity clearing and successful/error
  message appends; a stale callback cannot revive a replaced conversation.
- In `finally`, only the owning controller may clear its ref, clear streaming state and invoke
  `onTurnFinished`. Use a conditional block, not `return` inside `finally` that hides exceptions.
- Replacement and effect cleanup detach the ref before aborting the old controller. Replacement
  keeps its immediate view reset; unmount performs no state updates. Late A cannot clear B's ref.
- Clarify the callback comment with the owning-settlement semantics above. Preserve server-side
  detached generation: cancelling a browser read is not a promise that generation stopped.

Add normal-path tests for active conversation/truncation callbacks; success including empty content;
failed outcomes preserving the exact transport message; silent unauthorized/aborted outcomes; and
`gone` clearing conversation identity and displaying `GONE`. The active turn must release ownership
and permit a subsequent send after ordinary settlement, with exactly one finish notification.
Exercise effect cleanup under React StrictMode as well as explicit unmount.

Verify all ownership regressions pass; `chatTransport.test.ts` must pass without transport changes.

**Task 2 outcome:** 14/14 mounted-hook tests pass: the 8 ownership regressions plus 6
normal-outcome and StrictMode cases. The focused hook and unchanged transport suites pass 40/40;
TypeScript passes.

## Task 3 — validate and document the bounded fix

Files: `docs/STATUS.md`, `docs/DEVELOPMENT.md`, this plan.

Run the complete frontend suite and production build. Record the deterministic A/B ordering,
single-flight guarantee and callback semantics in STATUS with actual results. Amend DEVELOPMENT's
Node-only testing description to mention the isolated mounted-hook test. No unrelated cleanup.

A manual browser switch-conversation check may supplement the mounted tests but must not replace
them or require production chat/paid model calls. State explicitly whether any browser check was
actually performed. No backend suite is needed to establish the frontend race; the normal pre-push
hook still runs the repository's backend gate when the branch is pushed.

**Task 3 outcome:** `npm ci` completed with 223 packages installed and no vulnerabilities. The
complete frontend suite passed: 6 files, 73 tests. `npm run build` passed the contrast gate,
TypeScript project build and Vite production build using the existing local publishable key. No
manual browser check was performed; the mounted jsdom regressions are the UI-level verification.
The backend suite was intentionally not run in this task.

## Verification commands — executed through Task 3

From `frontend/`, using Node 24.19.0:

```bash
# Task 1: add the test-only DOM environment and record its lockfile.
npm install --save-dev jsdom@^27
npm test -- src/hooks/useChat.test.ts
# Repeat after Task 2, then verify from the lockfile.
npm test -- src/hooks/useChat.test.ts src/hooks/chatTransport.test.ts
npm ci
npm test
npm run build
```

Tests require no credentials, database, backend or network. Build requires the documented local
`VITE_SUPABASE_ANON_KEY`. Acceptance: tests pass without React act warnings, existing transport
classifications remain intact, and contrast/typecheck/Vite build pass.

## Remaining handoff

Commit the verified implementation, push the feature branch, open a PR targeting `master` with
`Closes KAR-52`, and review the PR. Owner merges; no direct master push or deployment.
