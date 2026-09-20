# KAR-53 — Conversation selection loading

**PROGRESS:** Task 3/4 done.

Ticket: https://linear.app/quantti/issue/KAR-53/show-a-loading-state-while-switching-conversation-in-the-sidebar

## Goal and decisions

Selecting a saved conversation must acknowledge the click immediately, hide the old transcript
while loading, and prevent sending into the old conversation during the switch. The most recent
selection owns both the displayed messages and the conversation id used by the next send.

Keep the current marker on the last successfully loaded conversation until the new one lands.
The target row gets a separate visible `Loading…` label and `aria-busy`; the thread gets a
`role="status"` loading message. Leave other rows and New conversation usable. No delayed spinner,
new dependencies, backend changes or production actions.

The hook currently changes `currentId` before ChatView's stale-selection guard can intervene.
Protect identity in the hook as well as message replacement in ChatView. Logically invalidate
old history requests rather than adding transport cancellation or timeout infrastructure.

At the start of selection, use the existing `replaceMessages([])` to detach any outgoing chat
stream immediately. Its late conversation metadata must not reclaim the active id. This cancels
the browser's read, not necessarily server-side generation.

A failed selection keeps the previous id, as before, and shows the existing error text:
`That conversation could not be opened. It may have been removed.` A subsequent send continues
the previous conversation; this ticket does not redesign recovery. New conversation instead
clears both identity and pending selection, and stale history cannot resurrect them.

## Execution constraint

The owner requires **one subagent at a time, always**, because resources are limited. Recorded
in `AGENTS.md`. Each task returns to the orchestrator before the next begins; no nested/parallel
agents, and no builds or test suites alongside subagent work. A temporary project-local agent
profile uses the authenticated provider because the installed generic profiles are unavailable;
it is not part of the deliverable and is removed before committing.

## Task 1 — Hook ownership and failure tests

Files: `frontend/src/hooks/useConversations.ts` and its mounted `.test.ts`.

Expose `pendingId`, set it before awaiting, and guard `currentId` and pending cleanup with a
request generation. Normalize successful payloads before committing identity. HTTP, network and
payload failures return null and release pending for the owning selection. Preserve array and
catalogue-envelope history support. A 404 still refreshes the list but a slow refresh must not
hold the selection loading state. `startNew()` invalidates outstanding selections.

Verification: deferred-fetch mounted-hook tests for immediate pending, successful/empty history,
both race completion orders, stale failures, latest failure followed by stale success, New
conversation during loading, HTTP/network/JSON/payload errors, and existing catalogue behavior.
Run only this test file with one Vitest worker.

Outcome: `pendingId` and generation ownership implemented; id commits only after normalization.
Tests-first run: 25 new failures with the two catalogue tests still passing. Targeted run after
implementation: 27/27 passed; targeted ESLint and Biome passed. The 404 list refresh is detached
from the selection result. No `useChat` or `adopt` change was needed.

## Task 2 — Loading UI and integration tests

Files: `ChatView.tsx`, `ConversationSidebar.tsx` and its CSS, new `ChatView.test.ts`.

Detach outgoing streaming at click, render the loading status instead of messages, disable
`MessageInput` while either streaming or selecting, and pass the pending id to the sidebar.
Reuse existing design tokens and layout; make loading text visible even beside a long title.
Preserve ChatView's latest-selection guard and existing error wording.

Verification: mount the real selection/chat hooks, sidebar and composer under jsdom. Use deferred
mocked `apiFetch`, not mocked selection behavior. Assert loading on click, old transcript hidden,
disabled textarea/send, recovery on success/failure, newest selection including next POST id,
New conversation invalidation, and a late outgoing SSE event not reclaiming selection. No new
harness dependency. Run the new file and existing `useChat.test.ts` sequentially, one worker.

Outcome: actual composer/sidebar/thread integration covered by 8 new tests, all failing before
the UI change and passing after it. Existing `useChat` regressions: 15/15 passed. Targeted ESLint
and Biome passed. Loading uses the existing centered empty-state layout; the sidebar truncates
only its title, reserving space for the loading marker. Real-browser layout remains unverified.

## Task 3 — Verification and documentation

Run frontend tests with one worker, lint, formatting check, TypeScript and contrast checks
sequentially; production build if the local public-key configuration is available. No local
backend suite: this host has Node but no JDK or Docker; the complete suite runs on the PR.
Document outcomes in `docs/STATUS.md` and this plan. Keep changes scoped; do not grow the lint
baseline or reformat unrelated files. Review the complete diff before publication.

Outcome: all 189 frontend tests in 22 files passed with `--maxWorkers=1`. Full frontend lint,
format check, `tsc -b`, contrast checks and `git diff --check` passed sequentially. No lint baseline
change. Production build awaits CI's public-key configuration; none is configured locally.
Backend verification also awaits CI. No live-browser or production check was performed.

## Task 4 — Publish and review

Keep temporary agent profiles out of commits and remove them after final review. Commit on
`fix/kar-53-conversation-loading`, push with the pre-push guard installed, and open a PR targeting
`master` with `Closes KAR-53`. Review the actual PR with one read-only subagent after publication;
fix findings in new commits and re-review. Await CI, leave merging to the owner, and move the
Linear issue to In Review with the PR link. No direct master push or deployment.

Outcome so far: published [PR #121](https://github.com/quantti/fpl-assistant/pull/121), reviewed
by one read-only subagent. No functional blockers. Its accessibility suggestion was implemented
as a separate bounded unit: the status region stays mounted and its text changes; visual loading
copy is excluded from duplicate screen-reader output. Updated regression failed before the fix,
then all 8 ChatView tests and the full 189-test frontend suite passed, as did static checks.
The initial PR CI run (`35477448224`) passed the backend suite and production frontend build.
The accessibility follow-up still needs published re-review and its final CI run.
