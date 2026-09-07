# Gemini 3.8 Flash Provider Switch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (or
> subagent-driven-development) to implement this plan task-by-task.

**Goal:** Make `SPRING_AI_PROVIDER=google-genai` a working third chat provider running
`gemini-3.8-flash` with thinking on, with token accounting that survives Gemini's usage
semantics — then trial it against Terra before any production switch.

**Architecture:** Same shape as the existing two providers: one starter dependency, one
`application.yml` block gated by `spring.ai.model.chat`, a third branch in `AiConfig`, and a
provider-aware usage normalisation in `ChatService`. No tool, prompt, streaming or RAG code
changes — all of that is provider-agnostic Spring AI. The switch itself is an env-var flip,
done only after the trial and only by the owner.

**Tech Stack:** Spring AI 2.0.0 `spring-ai-starter-model-google-genai` (in the 2.0.0 BOM),
Gemini Developer API (`GEMINI_API_KEY`), JUnit 5 + Mockito against the disposable test
container.

**Spec:** `docs/LLM-PRICING.md` — the 2026-09-03 re-check (PR #67) and its "The trial, before
any decision" section. This plan implements the trial's engineering half and the switch it
gates. **Ticket: KAR-47.**

**PROGRESS:** Task 2/5 done

## Global Constraints

- NEVER push to `master`; branch + PR, pre-push hook runs the suite (`AGENTS.md`).
- Tests never run against Supabase; the suite uses `scripts/test-db.sh` + `.env.test`.
- Embeddings stay on OpenAI: `spring.ai.model.embedding: openai` and `OPENAI_API_KEY` remain
  mandatory whatever the chat provider (`docs/DEVELOPMENT.md` "LLM provider").
- `fpl_usage_event`: NULL means "provider reported nothing", never zero; `total_tokens` is a
  generated column summing the four token columns, so every billable token must land in one of
  them (`UserSchema.java`).
- No new tables, no schema changes — thinking tokens are billed as output and are stored inside
  `output_tokens`.
- Surgical diffs: the `openai`/`anthropic` yml blocks and their comments stay untouched.

## Facts pinned against Spring AI 2.0.0 source (v2.0.0 tag, read 2026-09-03)

These drive the design; re-verify only if the Spring AI version changes.

1. `GoogleGenAiChatModel` detects a function call with `anyMatch` over the candidate's parts —
   a candidate mixing thinking/text parts with a function call IS treated as a tool call. The
   `allMatch` bug (spring-ai #5466, fix PR #5468 still open) is in the 1.1.x line, not 2.0.0.
   Streaming behaviour is unverified — the trial's first check.
2. `getDefaultUsage(...)` returns `GoogleGenAiUsage` by default (`includeExtendedUsageMetadata`
   defaults true) on BOTH the call and stream paths.
3. `GoogleGenAiUsage.getCacheReadInputTokens()` returns `cachedContentTokenCount` — the
   existing `usage.getCacheReadInputTokens()` read in `ChatService.capture` works unchanged.
4. `GoogleGenAiUsage.getCompletionTokens()` is `candidatesTokenCount` ONLY;
   `thoughtsTokenCount` sits in its own getter and is NOT added to completion. Left alone,
   every thinking token — billed as output, the most expensive line — vanishes from
   `fpl_usage_event`. Task 2 fixes this.
5. Gemini counts `cachedContentTokenCount` inside `promptTokenCount` (subset), like OpenAI and
   unlike Anthropic — the uncached remainder is `prompt − cacheReads`.
6. Gate value: `spring.ai.model.chat=google-genai`. Key property:
   `spring.ai.google.genai.api-key`. Thinking: `spring.ai.google.genai.chat.thinking-level`
   (`LOW`/`MEDIUM`/`HIGH`; mutually exclusive with `thinking-budget`). Output cap:
   `spring.ai.google.genai.chat.max-output-tokens`.

---

### Task 1: google-genai on the classpath, third branch in AiConfig

**Files:**
- Modify: `backend/build.gradle` (dependency list, after the two model starters)
- Modify: `backend/src/main/resources/application.yml` (`spring.ai` block)
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/config/AiConfig.java`
- Test: existing `backend/src/test/java/fi/karivanttinen/fplassistant/config/AiConfigTest.java`
  (context-boot test; no changes — it proves the classpath addition breaks nothing under the
  `anthropic` test gate)

**Interfaces:**
- Produces: a `ChatClient` bean backed by `GoogleGenAiChatModel` when
  `fpl.ai.provider=google-genai`; Task 2 and the trial depend on it.

**Step 1: dependency**

In `backend/build.gradle`, after
`implementation 'org.springframework.ai:spring-ai-starter-model-openai'`:

```groovy
    implementation 'org.springframework.ai:spring-ai-starter-model-google-genai'
```

No version — the 2.0.0 BOM manages it.

**Step 2: yml block**

In `application.yml` under `spring.ai`, beside the `openai:` block (comment included — every
other block explains its gotcha):

```yaml
    google:
      genai:
        api-key: ${GEMINI_API_KEY:}
        chat:
          # Env-swappable because the trial compares depths and point releases without a
          # rebuild — docs/LLM-PRICING.md, "The trial, before any decision", step 2.
          model: ${GEMINI_MODEL:gemini-3.8-flash}
          max-output-tokens: ${FPL_MAX_TOKENS:1500}
          # Applies to the visible answer; thinking tokens are budgeted separately by the
          # level below and land on the bill as output tokens all the same.
          # LOW to start: the cost model in docs/LLM-PRICING.md assumes ~1,500 thinking
          # tokens/turn, and the trial raises this only if answers need it.
          thinking-level: ${GEMINI_THINKING_LEVEL:LOW}
```

The existing gate `spring.ai.model.chat: ${SPRING_AI_PROVIDER}` needs no change — the value
`google-genai` activates exactly this autoconfiguration and leaves the other two (and their
blank keys) inert, same mechanism as today. Update the gate's comment to name three providers
instead of two.

**Step 3: AiConfig third branch**

Replace the two-way selection in `AiConfig.chatClient` (`AiConfig.java:20-34`):

```java
    @Bean
    public ChatClient chatClient(
            @Value("${fpl.ai.provider:anthropic}") String provider,
            ObjectProvider<AnthropicChatModel> anthropicModel,
            ObjectProvider<OpenAiChatModel> openAiModel,
            ObjectProvider<GoogleGenAiChatModel> googleModel,
            @Value("classpath:prompts/system-prompt.st") Resource systemPrompt
    ) throws IOException {
        String prompt = systemPrompt.getContentAsString(StandardCharsets.UTF_8);
        ChatModel model = switch (provider) {
            case "openai" -> openAiModel.getIfAvailable();
            case "google-genai" -> googleModel.getIfAvailable();
            default -> anthropicModel.getIfAvailable();
        };
        if (model == null) {
            throw new IllegalStateException(
                "No ChatModel for provider '" + provider + "'. Set SPRING_AI_PROVIDER to "
                + "'anthropic', 'openai' or 'google-genai' and provide the matching API key.");
        }
```

Import: `org.springframework.ai.google.genai.GoogleGenAiChatModel`. The builder call below the
selection stays as it is.

**Step 4: run the suite**

`cd /home/vanka/dev/fpl-assistant/backend && set -a && source .env.test && set +a && ./gradlew test`
Expected: green. The suite runs under `SPRING_AI_PROVIDER=anthropic` (CI) or `openai` (some
local `.env.test` files) — either way the google-genai gate is closed, so this proves the new
starter on the classpath does not disturb context startup when unselected — `AiConfigTest` and
every `@SpringBootTest` are the assertion.

**Step 5: commit**

```bash
git add backend/build.gradle backend/src/main/resources/application.yml \
    backend/src/main/java/fi/karivanttinen/fplassistant/config/AiConfig.java
git commit -m "feat: google-genai as a third chat provider"
```

---

### Task 2: Gemini token accounting in ChatService

**Files:**
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/chat/ChatService.java`
  (constructor ~line 46, `capture` ~lines 115-156)
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/chat/ChatServiceTest.java`

**Interfaces:**
- Consumes: `GoogleGenAiUsage` from Task 1's classpath
  (`org.springframework.ai.google.genai.metadata.GoogleGenAiUsage`).
- Produces: `TokenUsage` rows where, on the Gemini path, `inputTokens` is the uncached
  remainder and `outputTokens` includes thinking tokens. `UsageService` and the quota math
  consume these unchanged.

**Step 1: write the failing tests**

In `ChatServiceTest`, a helper that builds a Gemini usage chunk through the Google SDK's
builder (the same route production metadata takes):

```java
    /** A usage chunk as the google-genai path emits it: GoogleGenAiUsage, not DefaultUsage. */
    private static ChatResponse geminiUsage(Integer prompt, Integer candidates,
                                            Integer thoughts, Integer cached) {
        var metadata = com.google.genai.types.GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount(prompt)
                .candidatesTokenCount(candidates)
                .thoughtsTokenCount(thoughts)
                .cachedContentTokenCount(cached)
                .totalTokenCount(prompt + candidates + (thoughts == null ? 0 : thoughts))
                .build();
        return new ChatResponse(List.of(),
                ChatResponseMetadata.builder()
                        .usage(org.springframework.ai.google.genai.metadata.GoogleGenAiUsage
                                .from(metadata))
                        .build());
    }
```

(If the builder refuses a null field, drop the call for that field instead of passing null —
adjust the helper, not the assertions.)

Three tests:

```java
    /**
     * Gemini reports the prompt with cache reads counted inside it (like OpenAI) and thinking
     * tokens beside the completion, not in it. Stored: the uncached remainder, and an output
     * that includes the thoughts — they are billed as output, and total_tokens is a generated
     * sum of the four columns, so a thought not stored here is a thought not billed anywhere.
     */
    @Test
    void geminiThoughtsLandInOutputAndCachedInsideThePromptIsUnfolded() {
        provider = "google-genai";
        ChatService service = service(List.of(), Flux.just(
                response("text"),
                geminiUsage(7550, 700, 1500, 4450)));

        List<TokenUsage> reported = new java.util.ArrayList<>();
        StepVerifier.create(service.stream(List.of(new ChatTurn(ChatTurn.USER, "hi")), USER, reported::add))
                .expectNext("text")
                .verifyComplete();

        assertThat(reported.getLast())
                .as("7550 prompt less 4450 cached is 3100 uncached; 700 answer plus 1500 thoughts is 2200 out")
                .isEqualTo(new TokenUsage(null, 3100, 4450, null, 2200));
    }

    @Test
    void geminiWithoutThoughtsKeepsThePlainCompletionCount() {
        provider = "google-genai";
        ChatService service = service(List.of(), Flux.just(
                response("text"),
                geminiUsage(1000, 700, null, null)));

        List<TokenUsage> reported = new java.util.ArrayList<>();
        StepVerifier.create(service.stream(List.of(new ChatTurn(ChatTurn.USER, "hi")), USER, reported::add))
                .expectNext("text")
                .verifyComplete();

        assertThat(reported.getLast()).isEqualTo(new TokenUsage(null, 1000, null, null, 700));
    }

    /** A cache miss is a measurement: zero reads must be stored as zero, not null. */
    @Test
    void geminiCacheMissIsRecordedAsZeroNotNull() {
        provider = "google-genai";
        ChatService service = service(List.of(), Flux.just(
                geminiUsage(1000, 700, 1500, 0)));

        List<TokenUsage> reported = new java.util.ArrayList<>();
        StepVerifier.create(service.stream(List.of(new ChatTurn(ChatTurn.USER, "hi")), USER, reported::add))
                .verifyComplete();

        assertThat(reported.getLast()).isEqualTo(new TokenUsage(null, 1000, 0, null, 2200));
    }
```

**Step 2: run them, expect failure**

`./gradlew test --tests 'fi.karivanttinen.fplassistant.chat.ChatServiceTest'`
Expected: the three new tests FAIL — output misses the thoughts (700, not 2200) and the
cached-inside-prompt unfold does not happen under provider `google-genai`
(input 7550, not 3100).

**Step 3: implement**

In `ChatService`:

- Constructor (`ChatService.java:46`): Gemini counts the cached prompt the way OpenAI does —
  inside the prompt total (pinned fact 5). Extend the existing comment with one Gemini
  sentence and change the flag to:

```java
        this.cachedCountedInsideInput = "openai".equalsIgnoreCase(provider)
                || "google-genai".equalsIgnoreCase(provider);
```

- `capture(...)`: after `completion` is read, fold the thoughts in (pinned fact 4):

```java
        Integer completion = usage == null ? null : usage.getCompletionTokens();
        // Gemini reports thinking tokens beside the completion, not in it, and bills them as
        // output. Verified against GoogleGenAiUsage in Spring AI 2.0.0: getCompletionTokens()
        // is candidatesTokenCount alone. total_tokens is a generated sum of the four stored
        // columns, so a thought left out here is never billed anywhere.
        if (usage instanceof GoogleGenAiUsage gemini && gemini.getThoughtsTokenCount() != null) {
            completion = (completion == null ? 0 : completion) + gemini.getThoughtsTokenCount();
        }
```

Import `org.springframework.ai.google.genai.metadata.GoogleGenAiUsage`. Nothing else in
`capture` changes: cache reads already arrive through `usage.getCacheReadInputTokens()`
(pinned fact 3), cache writes stay null (Gemini has no write charge to report), and the
existing `unreported`/`richer` logic is untouched.

**Step 4: run the suite**

`./gradlew test --tests 'fi.karivanttinen.fplassistant.chat.ChatServiceTest'` — the three new
tests pass, the OpenAI and Anthropic normalisation tests still pass. Then the full suite.

**Step 5: commit**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/chat/ChatService.java \
    backend/src/test/java/fi/karivanttinen/fplassistant/chat/ChatServiceTest.java
git commit -m "feat: count Gemini thinking tokens and cached prompt correctly"
```

---

### Task 3: env plumbing and docs

**Files:**
- Modify: `backend/.env.supabase.example` (after the `OPENAI_API_KEY=` line)
- Modify: `backend/.env.test.example` (after the `OPENAI_API_KEY=` line)
- Modify: `docs/DEVELOPMENT.md` ("LLM provider" section)
- Modify: `AGENTS.md` (Architecture bullet "LLM provider")

**Interfaces:** none — documentation and templates only.

**Step 1: env templates**

Both example files, one line each (the test one with the same "not used" honesty the
Anthropic line has):

`.env.supabase.example`:
```
GEMINI_API_KEY=
```

`.env.test.example`:
```
GEMINI_API_KEY=not-used-no-test-calls-a-chat-model
```

**Step 2: DEVELOPMENT.md**

In "LLM provider", change `(`anthropic` or `openai`)` to
`(`anthropic`, `openai` or `google-genai`)` and append one sentence to the first paragraph:

```
`google-genai` runs Gemini through the Developer API (`GEMINI_API_KEY`); thinking depth is
`GEMINI_THINKING_LEVEL` (`LOW` default).
```

The `OPENAI_API_KEY is mandatory` paragraph stays word for word — it is the rule this task
must not weaken.

**Step 3: AGENTS.md**

The Architecture bullet `**LLM provider:** swappable via `SPRING_AI_PROVIDER` env var
(`anthropic` default, or `openai`)` gains `google-genai` in the parenthesis. No other AGENTS.md
change.

**Step 4: commit**

```bash
git add backend/.env.supabase.example backend/.env.test.example docs/DEVELOPMENT.md AGENTS.md
git commit -m "docs: wire GEMINI_API_KEY and the google-genai provider option"
```

Then push the branch and open the PR (`Closes` the Linear ticket if one is filed): Tasks 1–3
are the whole code change; Tasks 4–5 run against this branch and produce no code.

---

### Task 4: the trial — local, before any decision

Owner-run with the agent watching; nothing here is CI-able, and nothing here touches
production config. Run the app locally: `backend/.env.supabase` plus
`SPRING_AI_PROVIDER=google-genai` and a real `GEMINI_API_KEY` (AI Studio). Chat turns write
real rows to `fpl_conversation`/`fpl_usage_event` under the owner's account — same as the
Phase 5a measurement, acceptable and dated.

**Checks, in order — the first two are go/no-go:**

1. **Thinking + tools in one request, streaming.** Ask a question that forces a tool call
   ("who should I captain this week?"). Pass: a streamed answer citing tool data with the
   provenance banner. Fail: no tool call, a hang, or an error — pinned fact 1 covers the
   non-streaming path only, and this is the untested half. If it fails here, stop: file the
   upstream issue, record in STATUS, Grok 4.6 resumes first place per `docs/LLM-PRICING.md`.
2. **Token accounting against the provider's own numbers.** After ~10 varied turns:

```sql
SELECT count(*) AS turns, avg(input_tokens) AS uncached, avg(cached_tokens) AS cache_reads,
       avg(output_tokens) AS output_incl_thoughts
FROM fpl_usage_event WHERE event_type = 'chat' AND model LIKE 'gemini%';
```

   Compare against the AI Studio usage console for the same key and day. Pass: our
   `total_tokens` within a few percent of Google's billed figure. Fail: a systematic gap —
   most likely thoughts double- or un-counted; fix in `ChatService`, re-run.
3. **Cache reads.** `cached_tokens` should become non-zero on consecutive turns (implicit
   caching; the ~4.5k-token static prefix is below the 32,768 explicit-cache floor, so
   implicit is the only mechanism). If it stays zero, the $0.011/message estimate rises to
   ~$0.014 — still under Terra; record the measured number in `docs/LLM-PRICING.md`.
4. **Thinking-token volume.** `output_incl_thoughts − ~700` replaces the 1,500 assumption in
   `docs/LLM-PRICING.md`; try `GEMINI_THINKING_LEVEL=MEDIUM`/`HIGH` on a few weak-Terra
   questions to see what depth the answers actually need.
5. **Quality.** The 15–20 questions where Terra's answer was weak (owner collects), asked
   verbatim, answers compared side by side. This is the owner's judgement, not a metric.

Record all measurements and the verdict in `docs/LLM-PRICING.md` (replacing the stated
assumptions) and a short entry in `docs/STATUS.md`. **Decision gate: the owner picks switch,
stay, or trial Grok next. No production change in this task.**

---

### Task 5: production switch — only on the owner's go from Task 4

1. Merge the PR (owner). Then:

```bash
fly secrets set GEMINI_API_KEY=<key> SPRING_AI_PROVIDER=google-genai
```

   One command, one restart — `fly secrets set` redeploys. `OPENAI_API_KEY` stays set:
   embeddings. `ANTHROPIC_API_KEY` stays set and inert, same as today.

2. **Production checks:**
   - A chat question forcing a tool call answers correctly with the provenance banner.
   - A strategy question retrieves RAG chunks (proves embeddings still flow through OpenAI).
   - `SELECT model, provider FROM fpl_usage_event ORDER BY created_at DESC LIMIT 3;` on
     Supabase shows `gemini-3.8-flash` / `google-genai` with all four token columns populated.

3. **Rollback** is the same one-liner with the old values:
   `fly secrets set SPRING_AI_PROVIDER=openai` (the key can stay).

4. `docs/STATUS.md` entry: date, measured per-message cost from the first production day,
   and the promo-end reminder — **2026-12-31 the Gemini price doubles to Grok 4.6's level;
   re-read `docs/LLM-PRICING.md` before January.**
