# KAR-8 — actionable chat error messages

**PROGRESS:** Plan ready (revised 2026-09-10 against master 7721754); implementation Task 4/4 done.

Issue: [KAR-8](https://linear.app/quantti/issue/KAR-8/chat-error-messages-are-uninformative) —
In Progress, High, Bug, related to KAR-42 (merged, PR #77), not blocked. Branch
`kar-8-chat-errors`, worktree `/home/vanka/dev/fpl-assistant-kar8`.

## Goal and scope

The user must be able to tell apart, from the message alone: (a) the chat service is
unreachable or answering 5xx, (b) the answer's generation failed (model or tool), (c) the answer
stream was interrupted — each with what to do next. `429` keeps its current shape and is the
model to copy. No retry button, no automatic retry, no new dependency, no structured error
payload, no change to the SSE wire contract, no per-tool/per-provider diagnosis (the backend has
no such evidence: tools return failures as ordinary text, and `ChatService.stream` supplies text
and usage only).

## Source findings (verified 2026-09-10 on this branch)

- `frontend/src/hooks/useChat.ts:43-44,162-195`: three constants. `INTERRUPTED` already covers
  clean EOF without `done`; `FAILED` covers a rejected fetch, every non-2xx other than
  401/404/429, a missing body, and a rejected `reader.read()`. A rejected read after a complete
  `event: error` shows `FAILED`, hiding the error. Line 181 dispatches an undelimited trailing
  buffer, so a stream cut inside its final `event: done` frame can read as success.
- `frontend/vite.config.ts`: dev proxy target is `FPL_API_PROXY || http://localhost:8080`;
  Vite's proxy middleware answers a refused connection with **502** `text/plain`. In
  production the app is reached through Fly's proxy, which answers 502/503 when nothing is up.
  A bare `fetch` rejection (`TypeError`) is the no-proxy / offline case.
- `frontend/src/auth/apiFetch.ts`: one refresh + one retry on 401, then local sign-out. Not a
  refactor target; `useChat` must keep returning silently on 401.
- `backend/.../chat/ChatController.java:129-148`: wire contract is `event: conversation` (JSON
  string), optional `event: truncated` (integer), unnamed token events with JSON-string data,
  optional `event: error` with plain-text data (line 135, "The assistant hit a temporary
  problem. Please try again."), then `event: done` with empty data. `onErrorResume` on the
  replayed sink is the only place generation failures become an event. KAR-42 changed none of
  this (it set `HeaderWriterFilter.setShouldWriteHeadersEagerly(true)` in `SecurityConfig` and
  added tests/diagnostics).
- Failed answers are not persisted; successful ones are, even after the browser disconnects
  (`answerOutlivingTheClient`). So an interrupted stream's answer may exist under Conversations.
- Tests: `ChatControllerTest` (SpringBootTest + MockMvc; HTTP cases share the per-IP 20/min limit,
  direct `controller.chat(...)` calls do not) and `ChatSseLifecycleTest` (raw wire assertions
  over MockMvc and Tomcat; asserts event names `conversation, message, error, done` but not the
  error text). Frontend: `vitest.config.ts` is `environment: 'node'`, `src/**/*.test.ts`; modules
  are mocked with `vi.mock` (see `components/MyTeamPage/myTeam.test.ts` mocking `apiFetch`).
  Node 24 provides `Response`, `ReadableStream`, `TextEncoder/Decoder` globally.

## Global constraints

Message strings (exact; one module exports them, the hook never inlines copy):

| Constant | Trigger | Text |
| --- | --- | --- |
| `UNREACHABLE` | `apiFetch` rejects and the signal is not aborted | `Could not reach the chat service. Check your connection — or, if you are running the app locally, that the backend is up — then try again.` |
| `UNAVAILABLE(status)` | HTTP 500–599 | `The chat service is temporarily unavailable (HTTP {status}). Try again in a minute.` |
| `REJECTED(status)` | any other non-2xx except 401/404/429 | `The chat request was rejected (HTTP {status}). Reload the page and try again.` |
| `GENERATION_FAILED` | complete `event: error` frame, whatever its data | `The assistant could not finish this answer — its AI service or a data lookup failed. The answer was not saved. Try again in a moment.` |
| `INTERRUPTED` | 2xx without a body, EOF or rejected read before a complete `done`, or `done` only as an undelimited trailing fragment | `The connection dropped before the answer finished. Wait a moment and check Conversations — the answer may have been saved — before sending again.` |
| `GONE` | 404 | unchanged |
| 429 | unchanged: body reason (or `Too many messages for now.`) + `waitFor(Retry-After)` | unchanged |

Backend `event: error` data becomes the same `GENERATION_FAILED` text (constant
`ChatController.GENERATION_FAILED`, package-private for tests). The frontend still ignores the
payload and shows its own constant, so a curl user and a browser user read the same sentence.

Must not change: SSE event names/order/encoding; `event: done` semantics; `Message`/`ChatRequest`
types; `useChat`'s return shape; 401 silence; 404 forgetting the conversation id; 429 formatting;
`apiFetch`; usage refund/retain rules; failed answers never persisted; no exception text,
provider response or HTTP body reaches the client (429 body is the documented exception).

Precedence: a complete `event: error` wins over a later EOF/read rejection. An intentional abort
(unmount, `replaceMessages`) produces no message. Partial text is never committed as an answer.

Out of scope, noted for the record: a fetch with no timeout; a stale turn's `finally` clearing a
newer turn's `isStreaming` after `replaceMessages` (pre-existing, not an error-copy issue);
malformed token frames (still skipped, as today); a split CRLF across chunks (only a CRLF-rewriting
proxy could trigger it; normalising the buffer rather than the chunk in Task 2 removes it for
free, no test required).

## Task 1 — backend: generation failures carry the new text and nothing else

Files: `backend/src/main/java/fi/karivanttinen/fplassistant/chat/ChatController.java`,
`backend/src/test/java/fi/karivanttinen/fplassistant/chat/ChatControllerTest.java`,
`backend/src/test/java/fi/karivanttinen/fplassistant/chat/ChatSseLifecycleTest.java`.

Change: add `static final String GENERATION_FAILED = "<text above>"` and use it in the
`onErrorResume` at line 133-136. Nothing else in the controller changes.

Tests (write first, see them fail on the old text):
- `ChatControllerTest.aGenerationFailureTellsTheUserWhatToDoWithoutNamingTheCause` — direct
  `controller.chat(...)` (no HTTP, avoids the rate limit); `chatService.stream` returns
  `Flux.concat(Flux.just("partial"), Flux.error(new IllegalStateException("sk-sentinel-provider-detail")))`;
  `StepVerifier` expects `conversation`, a token, then an `error` event whose `data()`
  `isEqualTo(ChatController.GENERATION_FAILED)` and `doesNotContain("sk-sentinel")`, then `done`,
  then complete.
- `ChatSseLifecycleTest.assertContract`, `MID_STREAM_FAILURE` branch: add
  `assertThat(result.body().split("\n")).contains("data:" + ChatController.GENERATION_FAILED)` —
  the raw wire line, over both transports, so the text survives serialisation as one `data:` line.

Verify:
```bash
cd /home/vanka/dev/fpl-assistant-kar8 && scripts/test-db.sh up && cd backend \
  && source "$HOME/.sdkman/bin/sdkman-init.sh" && set -a && source .env.test && set +a \
  && ./gradlew cleanTest test --tests '*ChatControllerTest' --tests '*ChatSseLifecycleTest'
```

## Task 2 — frontend: extract the HTTP/SSE transport and pin its classification

Files (new): `frontend/src/hooks/chatTransport.ts`, `frontend/src/hooks/chatTransport.test.ts`.
Untouched here: `useChat.ts` (Task 3).

Design, kept small — no generic SSE framework:
```ts
export type ChatOutcome =
  | { kind: 'completed'; content: string }
  | { kind: 'failed'; message: string }   // isError message to append
  | { kind: 'gone' } | { kind: 'unauthorized' } | { kind: 'aborted' }
export interface ChatEvents {
  onConversation(id: string): void
  onTruncated(dropped: number): void   // NaN/≤0 allowed; the hook picks the wording
  onToken(accumulated: string): void
}
export async function sendChatTurn(request: ChatRequest, signal: AbortSignal, events: ChatEvents): Promise<ChatOutcome>
```
Move from `useChat.ts`: `waitFor`/`plural`, the 401/404/429 branches, the reader loop and
`flushEvent`. Export the message constants/functions from this module (`GONE` too). Rules:
`apiFetch` rejection → `aborted` if `signal.aborted`, else `failed(UNREACHABLE)`; 5xx →
`failed(UNAVAILABLE(status))`; other non-2xx → `failed(REJECTED(status))`; 2xx without `body` →
`failed(INTERRUPTED)`. Reader loop: append chunk, normalise `\r\n` on the buffer, split complete
frames on `\n\n` only — drop any trailing undelimited fragment at EOF. A complete `error` frame
sets a flag; a complete `done` frame returns `completed` immediately (do not wait for EOF) unless
the error flag is set, in which case `failed(GENERATION_FAILED)`. EOF or a rejected read: if
`signal.aborted` → `aborted`; else if error flag → `failed(GENERATION_FAILED)`; else
`failed(INTERRUPTED)`. Malformed token JSON is skipped (unchanged). Cancel/release the reader in a
`finally`; a cancel that throws is swallowed.

Tests (`vi.mock('../auth/apiFetch')`; helpers `sse(...frames)` building a `Response` over a
`ReadableStream` of encoded chunks, and `chunks(...)` to control boundaries). Name and assert:
- `rejects with UNREACHABLE when the fetch itself fails` — mock rejects `TypeError`; outcome
  `failed`, message `=== UNREACHABLE`.
- `maps 500, 502 and 503 to UNAVAILABLE with the status` — three cases; message
  `=== UNAVAILABLE(status)` and contains `HTTP 502` etc.
- `maps 400 and 418 to REJECTED with the status`.
- `returns unauthorized on 401 without reading a body`; `returns gone on 404`.
- `formats 429 from the body and Retry-After` — body `Weekly limit reached.` + `Retry-After: 90`
  → `Weekly limit reached. Try again in 2 minutes.`; empty body, no header → `Too many messages
  for now.` (exact). Body handling itself is unchanged from today; assert only these two cases.
- `treats a 2xx without a body as INTERRUPTED`.
- `completes on done with the accumulated tokens` — frames `conversation`, two tokens
  (`" hel"`, `"lo\n"`), `done`; outcome `completed` with `content === ' hello\n'`,
  `onConversation` called once with the id, `onToken` called with `' hel'` then `' hello\n'`.
- `completes with empty content on done after zero tokens`.
- `reports truncated as metadata, not text` — `onTruncated(3)`, content excludes it.
- `reassembles frames split across chunks and inside multibyte characters` — split one token
  `"€"` between its UTF-8 bytes and split `\n\n` across chunks; content `=== '€'`.
- `ignores comments and unknown named events` — `: ping` and `event: heartbeat` frames do not
  reach `onToken` or content.
- `returns GENERATION_FAILED on a complete error frame, even when done follows` — partial
  token then `error` then `done`; outcome `failed(GENERATION_FAILED)`; `onToken` was called
  (streaming text is the hook's to clear).
- `keeps GENERATION_FAILED when the read fails after the error frame` — `error` frame then the
  stream errors; outcome is `GENERATION_FAILED`, not `INTERRUPTED`.
- `returns INTERRUPTED on EOF without done`; `returns INTERRUPTED when the read rejects`.
- `does not accept an undelimited trailing done` — stream ends with `event: done\ndata:` and no
  blank line → `INTERRUPTED`.
- `returns completed without waiting for EOF` — a stream that emits `done` and never closes
  resolves (use a `ReadableStream` whose `pull` never enqueues after `done`).
- `returns aborted when the signal fires before the response` and
  `... during the read` — no partial content leaks into any outcome.

Verify (first run in this worktree needs `npm ci`; `npm test` needs no env file):
```bash
cd /home/vanka/dev/fpl-assistant-kar8/frontend && npm ci && npm test
```

## Task 3 — frontend: wire `useChat` to the transport

Files: `frontend/src/hooks/useChat.ts`.

Replace the body of `sendMessage` after the optimistic user append with one
`await sendChatTurn({ conversationId, message: content }, controller.signal, { onConversation,
onTruncated: dropped => setMessages(prev => [...prev, notice(dropped)]), onToken:
setStreamingContent })`, then switch on `outcome.kind`: `completed` → append assistant message;
`failed` → append `{ role: 'assistant', content: outcome.message, isError: true }`; `gone` →
`onConversation(null)` and append `GONE` as error; `unauthorized`/`aborted` → nothing. Keep the
`finally` exactly as it is (`setStreamingContent(null)`, `setIsStreaming(false)`,
`onTurnFinished()`), the single-flight guard, the unmount abort effect and `replaceMessages`.
Delete `waitFor`, `plural`, the constants and `flushEvent` from the hook; nothing else moves.
Public API, `Message`, `ChatView`, `MessageThread`, `AssistantMessage` are unchanged.

There is no DOM test environment; this task is verified by type-check, the Task 2 suite, and
the browser check in Task 4.

Verify (`.env.local` is not in the worktree; copy it for the build only):
```bash
cd /home/vanka/dev/fpl-assistant-kar8/frontend \
  && cp -n /home/vanka/dev/fpl-assistant/frontend/.env.local . \
  && npm test && npm run build
```

## Task 4 — acceptance, docs, commit and PR

1. Full backend suite and frontend build (commands in Tasks 1–3; full suite:
   `./gradlew cleanTest test` with `.env.test` loaded — it needs the real `OPENAI_API_KEY`
   already in that file).
2. Dev-proxy check without a browser (the agent has none): start `npm run dev` with the backend
   **down** and `curl -si -X POST localhost:5173/api/chat -H 'Content-Type: application/json'
   -d '{"message":"x"}'`; record the status the Vite proxy answers (expected 502 →
   `UNAVAILABLE(502)`). Stop the dev server afterwards. The interactive browser cases (dev server
   stopped mid-page → `UNREACHABLE`; backend killed mid-answer → `INTERRUPTED`) are left to the
   owner and STATUS says so. `GENERATION_FAILED` is covered by Task 1's wire assertion. No paid
   model calls, never production.
3. `docs/STATUS.md`: append after the KAR-42 entry, heading
   `## Chat errors name their cause (KAR-8, 2026-09-10)`, ≤ 15 lines: the five message
   triggers, the transport extraction (`chatTransport.ts`, hook unchanged in API), the
   undelimited-`done` fix, what was browser-checked and what was not, and the out-of-scope list
   from Global constraints.
4. Update this file's `**PROGRESS:**` line and commit the plan file and STATUS on
   `kar-8-chat-errors` (subject ~60 chars). Do not push; the controller pushes the branch, opens
   the PR (≤ 8-line body ending `Closes KAR-8`) and runs the PR review. Tasks 1–3 each commit
   their own work. Never push `master`, never `--no-verify`.
