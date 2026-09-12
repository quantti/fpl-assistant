# KAR-48 — sum reported token usage across tool-call rounds

**PROGRESS:** Task 7/7 done.

Issue: [KAR-48](https://linear.app/quantti/issue/KAR-48). Checked in Linear on 2026-09-12:
Backlog, Medium, related to KAR-47, no blocking relations. Baseline: `master 13be2ae`.
Planning/source inspection only; no implementation, tests or live provider calls performed.

## Goal and selection

One `fpl_usage_event` per user turn must contain the sum of reported usage from every model
invocation/tool round, not the biggest single round. Repeated cumulative chunks within a round
must count once. Publish running aggregates as usage arrives so later failure does not erase
already reported expenditure.

Third selected ticket: a cross-provider accounting bug, discovered in KAR-47 / merged PR #70,
with more integration surface than KAR-36 or KAR-52. Neither ticket blocks it. KAR-47's provider
trial/switch remains separate: do not switch providers or make the owner's trial a prerequisite
for deterministic tests. Historical `docs/TODO.md` entries are not authoritative ticket statuses.

Preserve quota claims/refunds, one row per user turn, current provider normalization, detached
streaming, JWT-derived tool identity and a shared per-turn `FplBudget`. No DB schema change,
pricing feature, historical backfill, frontend work or framework/SDK upgrade.

## Current application evidence

- `backend/src/main/java/fi/karivanttinen/fplassistant/chat/ChatService.java` captures usage after
  `.chatResponse()` and runs `richer()` across the entire turn. That loses both request boundaries
  and any tool-call responses filtered by the outer advisor.
- Keep coherent whole-snapshot selection within one round. Fieldwise maxima can put an OpenAI
  prompt measured before cache details beside a later cache count and double-count that cache.
- OpenAI/Gemini prompt counts include cache reads; stored input is the uncached remainder.
  Anthropic reports cache beside uncached input. Gemini thoughts are added to output exactly once.
  Preserve null/zero conventions: explicit cache zero is measured; absent prompt/output figures
  must not become fabricated zero measurements through Spring AI's `EmptyUsage`.
- `ChatController` keeps the latest callback and writes it on completion/error. `UsageService`
  updates the existing event; its database-generated total sums the four token columns. Keep
  those interfaces and one quota claim per user turn.
- `AiConfig.chatClient` uses the selected model and default tool-advisor setup. No accounting
  configuration is needed there if the per-turn advisor below is registered from `ChatService`.
- Existing `ChatServiceTest` mocks the final client stream. Retain its history/normalization
  intent, but that mock alone cannot prove that real tool-loop requests are counted.
- DEVELOPMENT's claim that Spring AI accumulates a whole turn, and ChatService's fieldwise-maxima
  Javadoc, are stale. Correct those directly affected descriptions during implementation.

## Verified Spring AI 2.0.0 boundary and chosen design

Source inspected from Maven Central's version-matched source archives on 2026-09-12, not from
unversioned documentation. Source paths below are inside their named artifacts:

- [spring-ai-client-chat sources](https://repo.maven.apache.org/maven2/org/springframework/ai/spring-ai-client-chat/2.0.0/spring-ai-client-chat-2.0.0-sources.jar):
  `org/springframework/ai/chat/client/advisor/api/StreamAdvisor.java` exposes
  `adviseStream(ChatClientRequest, StreamAdvisorChain)`, returning `Flux<ChatClientResponse>`.
- `advisor/ToolCallingAdvisor.java`: `DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE + 300`.
  `internalStream` calls `streamAdvisorChain.copy(this).nextStream(processedRequest)` each round;
  `handleToolCallRecursion` repeats that path. `streamWithToolCallResponses` aggregates/filters
  responses outside that invocation boundary.
- `advisor/DefaultAroundAdvisorChain.java` sorts ascending and copies the original advisor-list
  suffix after the tool advisor. Copies reuse advisor objects, not cloned instances.
  `advisor/ChatModelStreamAdvisor.java` is terminal, at `Ordered.LOWEST_PRECEDENCE`.
- `DefaultChatClient.java` appends `.advisors(instance)` instances and auto-registers the tool
  advisor. Therefore a turn-owned `StreamAdvisor` at `ToolCallingAdvisor.DEFAULT_ORDER + 1`
  runs around every model invocation, before tool-call filtering, under the current configuration.
- [spring-ai-model sources](https://repo.maven.apache.org/maven2/org/springframework/ai/spring-ai-model/2.0.0/spring-ai-model-2.0.0-sources.jar):
  `org/springframework/ai/chat/model/MessageAggregator.java` forwards original chunks while
  computing a separate aggregate callback; it does not append another aggregate usage chunk.
  `org/springframework/ai/support/UsageCalculator.java` returns current usage unchanged when
  the previous response is null.

**Chosen implementation:** proposed `chat/TurnUsageAdvisor.java`, implementing only `StreamAdvisor`,
created per subscribed user turn and registered with `.advisors(instance)` in `ChatService`.
Keep one turn accumulator in that instance and a fresh request snapshot per invocation subscription.
No collector in tool parameters, `ToolContext`, provider prompt or globally shared bean. No model
wrapper, custom tool-loop replacement, response-id heuristics or count-drop boundary detection.

Construct the advisor, request and `FplBudget` inside an outer `Flux.defer` so repeat subscriptions
cannot reuse a consumed advisor chain or prior turn state. The normal controller still subscribes
once; this does not introduce retries. Keep the same user/budget for all rounds of that subscription.

## Provider source limits — do not overclaim

Version-matched artifacts use the same Maven URL pattern as above for `spring-ai-anthropic`,
`spring-ai-openai` and `spring-ai-google-genai`.

- `org/springframework/ai/anthropic/AnthropicChatModel.java`: `stream` calls
  `internalStream(requestPrompt, null)`; normal streaming never accumulates a prior round.
  `message_start` retains model/id/input but emits nothing. `message_delta` emits usage, including
  cache fields from that delta; cache fields present only in `message_start` are not carried over.
  Thus Anthropic's per-request behavior is now source-verified, but cache completeness for actual
  provider event sequences is not established by this planning inspection.
- `org/springframework/ai/openai/OpenAiChatModel.java`: streaming passes current usage and null
  previous response to the cumulative helper. Cached prompt tokens map to cache reads.
- `org/springframework/ai/google/genai/GoogleGenAiChatModel.java`: streaming also starts with null
  previous response. `metadata/GoogleGenAiUsage.java` exposes thoughts separately from candidates.
  Its `RetryUtils` wrapper is on synchronous generation, not the inspected streaming path.

This fixes sums of **Spring AI-reported model-invocation usage**, not billing for every HTTP attempt.
Provider SDK retries sit below the advisor; unreported billed attempts cannot be reconstructed.
Likewise, failure before Anthropic emits usage may leave spent tokens unknown. Never infer counts
from text or fabricate a zero. If provider fixtures establish cache data lost by the adapter,
record that limitation and a separate follow-up rather than silently expanding KAR-48 into an SDK
patch. No live cache experiment or provider-console access is required to implement this scope.

## Task 1 — pin a real client/tool-loop reproduction

Proposed test: `backend/src/test/java/fi/karivanttinen/fplassistant/chat/TurnUsageAdvisorTest.java`;
use a real `ChatClient`, a deterministic stub `ChatModel` with tool-capable options and a tiny local
tool callback. Existing JUnit/Mockito/Reactor tooling is sufficient; no external chat calls.

Script two tool-call rounds followed by a final answer. Assert three model invocations, unchanged
user-visible text, and exact final normalized columns. Include a smaller or equal-total later
round so maximum-across-turn and count-drop heuristics cannot pass. Repeat snapshots within a
round and include metadata-only chunks. At least one test must fail against current `ChatService`
because it loses round usage, not because the mock bypasses the actual tool advisor.

Confirm the actual chain order and that tool-call usage is observed before filtering. Also pin a
single request with no tool call and a return-direct tool result (no invented follow-up request).
This characterizes the chosen source-verified API at runtime; if the resolved dependency/config
contradicts it, stop and revise the plan rather than forcing the old assumption.

**Task 1 outcome (2026-09-12):** Added `TurnUsageAdvisorTest` with a deterministic local
`ChatModel`, real `ChatClient`, real tool execution and a test-only no-op probe at
`ToolCallingAdvisor.DEFAULT_ORDER + 1`. Runtime behavior matches the inspected 2.0.0 sources: the
probe sees three distinct invocations and both tool-call responses before outer filtering; two
ordinary tools recurse to a final answer, no-tools makes one request, and a return-direct tool makes
one request with no invented follow-up. Repeated snapshots and metadata-only chunks are pinned, as
is unchanged emitted text. The no-tools, return-direct and existing `ChatServiceTest` harness tests
are green. The two-tools-plus-final accounting assertion is intentionally red against current
production code: expected normalized turn usage is `input=140, cached=70, cache-write=7,
output=28`, while `ChatService` reports only the richest round (`60, 40, 5, 10`). No database or
provider was contacted.

## Task 2 — implement coherent per-round snapshots and running sums

Files: `chat/ChatService.java`; proposed `chat/TurnUsageAdvisor.java` and, only if necessary for
focused testing, a small `chat/TurnUsageAccumulator.java`. Keep `TokenUsage`'s external shape.

Move/reuse normalization and richest-snapshot logic, rather than copying divergent provider rules.
Each invocation subscription owns a distinct slot, regardless of response id or counts. Incoming
measurements replace that slot's selected whole snapshot; publish the sum of the currently selected
slots immediately. Do not sum incoming cumulative chunks, add the same request again at completion,
or depend on a successful terminal callback. Leave a field null if no request reported it; sum
reported contributions otherwise. Carry model metadata without turning it into measured usage.
Retain the selected-model-per-turn assumption; no routing feature is introduced.

Guard concurrent updates/publication so a late callback cannot overwrite a newer aggregate; do not
call the consumer from inside a retryable atomic-update function. Allocate all mutable state inside
the subscribed turn. State need hold counts/model only, never prompt text or tool arguments.

Register the advisor at the verified order. Remove only the now-redundant outer usage capture;
keep text extraction/filtering, history preparation and account/budget tool context intact.

Verify Task 1 turns green. Pin equal-total richer replacement (cache details arrive later), repeated
snapshots, trailing empty/model-only metadata, measured cache zero, unknown fields and isolated
concurrent turns. A later round failing before usage retains previous rounds; failing after partial
usage retains its last reported snapshot. Existing normalization regressions must stay green.

**Task 2 outcome (2026-09-12):** Added one turn-owned `TurnUsageAdvisor` at
`ToolCallingAdvisor.DEFAULT_ORDER + 1`. `ChatService` now constructs that advisor and the
`FplBudget` inside `Flux.defer`, registers the advisor instance on the request, and retains only its
outer text extraction. Every invocation subscription gets one slot; each slot keeps the richest
whole normalized chunk and the turn publishes a serial-safe running sum across slots immediately.
Null counts, explicit cache zero, model-only metadata, OpenAI/Gemini cache subtraction and Gemini
thought tokens retain their prior meanings. The real-client two-tool reproduction now reports
`140/70/7/28`; no-tool and return-direct behavior remain unchanged. Tests also pin independent
repeated subscriptions and concurrent turns, the same user/budget across tool rounds with no
accounting state in tool context, equal-total cache-detail replacement, and failures before and
after reported usage. Existing mocked `ChatServiceTest` responses now pass through the actual
captured advisor rather than bypassing accounting. Focused pure run:
`./gradlew cleanTest test --tests '*TurnUsageAdvisorTest' --tests '*ChatServiceTest'` — 24 tests green; no
database or provider contacted. Provider SDK fixtures remain Tasks 3–5.

## Task 3 — verify Anthropic mapping through a mocked provider stream

Proposed `chat/AnthropicRoundTripUsageTest.java`. Use the real Spring AI Anthropic model/client/tool
loop with the SDK boundary mocked; construct provider-shaped events, not only final ChatResponses.
Inspect the resolved SDK types when writing fixtures rather than inventing constructors.

Cover message-start/input, text/tool deltas and message-delta/output across tool rounds. Verify
reported cache reads/writes, explicit cache misses, repeated cumulative deltas and model-only
metadata normalize and sum correctly. Include message-start-only cache fields to pin the adapter
limitation explicitly, and failure before/after a usage-bearing delta. Expected totals must be
written per fixture; missing adapter output is unknown, not a successful zero-cache measurement.

Verify no live API calls and exact round count. Record what the fixture proves versus what remains
unknown about live cache placement; do not fix the framework adapter in this task.

**Task 3 outcome (2026-09-12):** Added `AnthropicRoundTripUsageTest` against the real Spring AI
2.0.0 `AnthropicChatModel`, real `ChatClient` advisor/tool loop and real anthropic-java-core 2.40.1
event types, with only the sync/async SDK clients and raw streaming response boundary mocked. The
two-request fixture executes one tool exactly once, reuses the same message id across rounds, emits
a repeated cumulative final delta, and reports exact normalized totals `input=24, cached=7,
cache-write=2, output=7` from per-round fixtures `11/7/2/3 + 13/0/0/4`; explicit zero cache fields
remain measured misses. Separate fixtures pin the adapter limit: start-only cache read/write values
are dropped while its input and the delta's output survive (`17/null/null/3`), so those missing cache
columns are unknown rather than zero. Failure before a usage-bearing `message_delta` reports no
usage; failure after one retains `23/8/3/6`. Injected SDK mocks make every request deterministic and
preclude a live HTTP call; exact SDK request counts are asserted. These fixtures prove the resolved
adapter's behavior for the constructed provider-shaped sequences, not where Anthropic's live stream
will place cache fields; no framework/SDK or production accounting code changed. Focused run:
`./gradlew cleanTest test --tests '*AnthropicRoundTripUsageTest' --tests
'*TurnUsageAdvisorTest' --tests '*ChatServiceTest'` — 28 tests green; no database used.

## Task 4 — verify OpenAI mapping through a mocked provider stream

Proposed `chat/OpenAiRoundTripUsageTest.java`. Real provider model/client/tool loop, mocked SDK.
Script tool calls followed by a final completion, repeated/metadata-only usage and usage reported
at the end of a request. Include late prompt-cache details, an uncached request followed by a cached
one, and a later smaller request. Assert uncached input plus cache plus output equals the sum of
reported per-request totals; cache is not counted twice. Missing/reused response ids must not alter
request identity. Verify partial-error behavior and no live API calls.

**Task 4 outcome (2026-09-12):** Added `OpenAiRoundTripUsageTest` against the real Spring AI
2.0.0 `OpenAiChatModel`, real `ChatClient` advisor/tool loop and real openai-java-core 4.39.1
chunk/usage types, with only the sync/async SDK clients and resolved streaming boundary mocked.
The three-request fixture executes two tools then returns the final answer; reused and blank response
ids do not merge rounds. It covers metadata-only chunks, usage on end chunks, repeated final usage,
an explicit uncached round, cache details arriving after an otherwise complete usage snapshot, and
a smaller final request. Exact normalized totals are `input=140, cached=70, cache-write=unknown,
output=18`; normalized input plus cache plus output is 228, exactly the provider-reported
`108 + 96 + 24`, so cache reads are not counted twice. Missing cache details remain unknown rather
than becoming a measured miss. Separate fixtures prove a later request failing before usage keeps
the completed tool round (`20/10/null/5`), while failure after partial usage adds that request's
last mapped snapshot (`30/7/null/6` across both rounds). The SDK mocks make every request
deterministic and preclude live HTTP; exact SDK request and tool-call counts are asserted. No
production bug was exposed and production code was unchanged. Focused run:
`./gradlew cleanTest test --tests '*OpenAiRoundTripUsageTest' --tests
'*AnthropicRoundTripUsageTest' --tests '*TurnUsageAdvisorTest' --tests '*ChatServiceTest'` — 31
tests green; no database used.

## Task 5 — verify Gemini mapping through a mocked provider stream

Proposed `chat/GeminiRoundTripUsageTest.java`. Real Google GenAI model/client/tool loop, mocked SDK.
Cover tool rounds, cached prompt, candidates plus thoughts, repeated usage snapshots and a later
request with missing usage. Assert thoughts enter output once per selected round snapshot, cache
is subtracted from prompt once, and unknown data stays unknown. Verify failure after a reported
round preserves expenditure and no live API calls occur. No model/version switch or trial.

**Task 5 outcome (2026-09-12):** Added `GeminiRoundTripUsageTest` against the real Spring AI
2.0.0 `GoogleGenAiChatModel`, real `ChatClient` advisor/tool loop and real google-genai 1.58.0
response/usage types, with only the SDK `Models.generateContentStream` boundary and returned
`ResponseStream` mocked. The three-request fixture executes two tools and returns a final answer:
round one reports prompt/cached/candidates/thoughts `100/40/5/3` twice, round two reports an
explicit cache miss `80/0/4/2`, and the final request reports no usage. Exact normalized reported
totals are `input=140, cached=40, cache-write=unknown, output=14`; cache is subtracted from prompt
once, candidates and thoughts are added once per selected round snapshot, and the missing final
usage neither invents zero expenditure nor erases the completed rounds. A separate partial-stream
fixture repeats `50/12/6/4` before failing and retains exactly `38/12/unknown/10`. The injected SDK
mock makes every request deterministic and precludes live HTTP; exact SDK request and tool-call
counts are asserted. No provider/model switch, live trial or production-code change was made.
Focused pure run: `./gradlew cleanTest test --tests '*GeminiRoundTripUsageTest' --tests
'*OpenAiRoundTripUsageTest' --tests '*AnthropicRoundTripUsageTest' --tests
'*TurnUsageAdvisorTest' --tests '*ChatServiceTest'` — 33 tests green; no database used.

## Task 6 — verify persistence, quota and lifecycle contracts

Files: `chat/ChatControllerTest.java`, `user/UsageServiceTest.java` (under the existing Java test
package root), existing `chat/ChatSseLifecycleTest.java` as regression coverage.

Feed multiple running aggregate callbacks during a turn and assert one event/claim, final aggregate
columns and the generated total. Cover successful completion, later-round failure with measured
usage (charge retained, safe error/done, no partial answer stored), and model-only failure (refund).
Retain detached successful persistence after client disconnect and database work off provider
threads. Usage must survive conversation deletion as before.

In the real-loop tests, assert every tool round receives the same authenticated user and budget,
concurrent turns use separate state, and accounting objects never enter provider payloads, tool
schemas or SSE. Do not replace the known-good KAR-42 security-header fix or redesign the controller.

**Task 6 outcome (2026-09-12):** Extended the existing controller and usage-service lifecycle
regressions rather than adding a parallel harness. Three running aggregate callbacks on success
leave one quota claim and one usage row carrying only the final `1500/1200/800/250` snapshot
(`total_tokens=3750`); repeated `recordTokens` updates likewise leave one claim and the generated
final total. A later-round failure after a partial answer retains the final measured
`30000/0/0/900` charge (`total_tokens=30900`), emits the safe error and terminal done events, and
stores no partial assistant message. The existing model-only failure still refunds, conversation
deletion still leaves usage standing, detached browser completion still persists and charges once,
and JDBC persistence/accounting still runs off the provider thread. The real tool loop continues
to prove one authenticated user and one `FplBudget` across rounds plus isolated concurrent-turn
accounting; added assertions keep accounting state out of provider-visible messages and tool schemas,
while the controller success regression keeps model/token accounting out of SSE. Focused isolated-DB
run on `fpl-kar48-test-db` at port 55448: 79 tests green (33 usage/provider tests plus 46
controller, usage-service, AI-config and SSE lifecycle tests), 0 failures; no live chat/provider
request and no production-code change in this task.

## Task 7 — integrated verification and documentation

Files: `docs/DEVELOPMENT.md` token tracking, `docs/STATUS.md`, this plan and directly affected
accounting comments/tests. State the boundary/version, per-round coherent snapshots summed per turn,
partial-reported totals and adapter limitations. Explicitly supersede the old cumulative-across-turn
claim. Do not rewrite historical usage rows: lost rounds cannot be recovered from stored maxima.

Run focused tests and the full disposable-database suite. Use deterministic fixtures for all three
providers, not a single configured live provider as the oracle. Provider-console comparisons may
supplement future owner trials; they cannot replace the request-boundary tests.

**Task 7 outcome (2026-09-12):** DEVELOPMENT and directly affected accounting comments now state
the Spring AI 2.0.0 request boundary, coherent per-invocation snapshot selection and per-turn sum.
They also state the limits: historical maximum-only rows cannot be repaired; Anthropic cache fields
that appear only in `message_start` are lost by the adapter and remain unknown; SDK retries below
the advisor count only if mapped usage is emitted, so this is not guaranteed HTTP-attempt billing.
STATUS records the provider fixture versions, exact totals and verification evidence. The focused
selection below passed 79 tests (0 failures/errors/skips) in 18 seconds, including `AiConfigTest`.
The full `./gradlew cleanTest test` passed 862 tests (0 failures/errors/skips) in 1 minute 18
seconds. Both ran against only `fpl-kar48-test-db` at localhost:55448; the full suite's existing
embedding tests ran, but no live chat-model request, Supabase access, browser test or historical
backfill ran. The dedicated container was left running for subsequent push work.

## Verification commands — run for Task 7

From repository root, with `backend/.env.test` verified as
`jdbc:postgresql://localhost:55448/postgres`:

```bash
TEST_DB_PORT=55448 TEST_DB_CONTAINER=fpl-kar48-test-db scripts/test-db.sh up
(
  set -e
  source "$HOME/.sdkman/bin/sdkman-init.sh"
  cd backend
  set -a; source .env.test; set +a
  ./gradlew cleanTest test --tests '*TurnUsage*' --tests '*ChatServiceTest' \
    --tests '*AnthropicRoundTripUsageTest' --tests '*OpenAiRoundTripUsageTest' \
    --tests '*GeminiRoundTripUsageTest' --tests '*AiConfigTest' \
    --tests '*ChatControllerTest' --tests '*ChatSseLifecycleTest' --tests '*UsageServiceTest'
  ./gradlew cleanTest test
)
```

## Implementation handoff

Create a dedicated `kar-48-round-trip-token-accounting` branch from current master. Each numbered
task is one bounded unit; verify and return control before the next, including between provider
tasks. Update the single progress line once per completed task and record actual outcomes/limits.
Commit verified implementation, push only its feature branch, open a PR targeting `master` with
`Closes KAR-48`, then review the PR. Owner merges. No direct master push or deployment.
