# Language-model cost and the reasoning gap — worked out 2026-08-29

Every price here was read from the provider's own page on 2026-08-29 unless a source says
otherwise; the third-party figures are marked. Prices move, promotions end, and the per-message
numbers rest on one explicit assumption, so re-read the assumptions before reusing a total.

## The finding that reframes the question

**Production runs GPT-5.6 Terra with reasoning switched off**, and has since the chat endpoint
was built (`application.yml`: `reasoning-effort: none`). The reason is OpenAI's, not ours: the
whole GPT-5.6 family answers `400` on `/v1/chat/completions` when function tools are combined
with any reasoning effort, including the default. Reasoning plus tools requires the Responses
API. Spring AI 2.0.1 (2026-08-21) speaks Chat Completions only; Responses API support is
spring-ai issue #2962, open as of 2026-08-26 with no date.

Two consequences. Benchmark tables overstate what we run — Terra's published score is measured
with reasoning on. And paying more for Sol buys nothing here, because Sol has the same
restriction. The uneven answer quality the owner has noticed has a structural candidate
explanation before any model is blamed.

## List prices, USD per million tokens

| Model | Input | Cache read | Cache write | Output | Notes |
|---|---|---|---|---|---|
| GPT-5.6 Terra (current) | 2 | 0.20 | 2.50 (1.25×) | 12 | long-context tier above ~272k doubles input |
| GPT-5.6 Sol, promotional to at least 2026-11-21 | 4 | 0.40 | 5 | 20 | |
| GPT-5.6 Sol, list | 5 | 0.50 | 6.25 | 30 | |
| GPT-5.6 Luna | 0.20 | 0.02 | 0.25 | 1.20 | |
| Claude Sonnet 5 | 2 | 0.20 | 2.50 (5 min) / 4 (1 h) | 10 | $2/$10 made permanent; tokenizer ~30% more tokens per text than GPT |
| Claude Opus 5 | 5 | 0.50 | 6.25 / 10 | 25 | |
| Claude Fable 5 | 10 | 1 | 12.50 / 20 | 50 | |
| Grok 4.6 | 2 | 0.50 | — | 6 | doubles above 200k context; reasoning low–xhigh, default high |
| Grok 4.3 | 1.25 | 0.20 | — | 2.50 | |
| Gemini 3.5 Flash | 1.50 | 0.15 | storage $1/M/h (explicit) | 9 | implicit caching automatic, ~90% off, no storage fee |
| DeepSeek V4 Pro, direct | 0.66 off-peak / 1.32 peak | 0.022 / 0.044 | — | 1.98 / 3.96 | peak 01–04 and 06–10 UTC weekdays; servers in China |
| DeepSeek V4 Flash, direct | 0.22 / 0.44 | 0.007 / 0.014 | — | 0.66 / 1.32 | |
| DeepSeek V4 Pro on Azure Foundry (EU regions) | 1.74 | unknown | — | 3.48 | third-party report, verify |
| Mistral Medium 3.5 | 1.50 | "up to 90% off" | — | 7.50 | third-party report |
| Magistral Medium (reasoning) | 2 | | | 5 | third-party report |

Batch and flex tiers are 50% at OpenAI, Anthropic and Google; irrelevant for a synchronous chat.

### Caching mechanics that the table hides

- **OpenAI** caches automatically: prefix of at least 1,024 tokens, 30-minute lifetime on
  GPT-5.6, tools count toward the prefix. **GPT-5.6 charges cache writes at 1.25× input.**
  `docs/STATUS.md` recorded on Phase 5a's measurement that OpenAI neither charges nor reports
  writes; the pricing page now says otherwise, and the write count is reported only on the
  Responses API (`input_tokens_details.cache_write_tokens`). So this application pays for writes
  it cannot see, and `fpl_usage_event.total_tokens` undercounts by that much — roughly 4,500
  tokens per cold start, about $0.011.
- **Anthropic** caches on explicit `cache_control` (or the newer top-level automatic flag).
  Write 1.25× for a 5-minute cache, 2× for an hour, read 0.1×. **Not configured in this code**:
  Spring AI 2.0.0 has `AnthropicCacheOptions` (strategies `SYSTEM_AND_TOOLS`,
  `CONVERSATION_HISTORY`, TTL 5 min or 1 h) and `AiConfig` sets none of it, so switching
  `SPRING_AI_PROVIDER=anthropic` as-is pays full input for the 19.6 kB system prompt and 19 tool
  schemas on every turn.
- **Claude 4.7 and later tokenize ~30% more tokens for the same text** (Anthropic's pricing
  page). Sonnet's $2 is effectively about $2.60 against Terra's $2.
- **Reasoning tokens are output tokens everywhere.** Turning reasoning on multiplies the output
  line, which is why output price decides the comparison below.
- **Grok** discounts cache reads 75%, not 90%. **Gemini** implicit caching needs no code.

## Measured usage, and the one assumption

Measured on Terra (Phase 5a verification, `docs/STATUS.md`): every turn reads 4,300–4,600 tokens
from cache and 2,400–3,800 uncached. Output was not recorded; back-calculated from the measured
€0.0146 per message it is about 700 tokens. Exact figures:

```sql
SELECT avg(input_tokens) AS uncached, avg(cached_tokens) AS cache_reads,
       avg(output_tokens) AS output, count(*) AS turns
FROM fpl_usage_event WHERE event_type = 'chat' AND model = 'gpt-5.6-terra';
```

Profile used below: **3,100 uncached input, 4,450 cache reads, 700 answer tokens.** For the
reasoning-on rows, **1,500 thinking tokens per turn is assumed** — a guess for a medium-effort
tool-calling turn, and the largest uncertainty in every number that follows. The first real trial
replaces it: thinking tokens land in `output_tokens`.

## Cost per message

Reasoning off, today's profile:

| Model | $/message | × Terra | 30 accounts × 20/week, $/month |
|---|---|---|---|
| Terra (production today) | 0.016 | 1 | 40 |
| Sol promotional | 0.028 | 1.8 | 73 |
| Sol list | 0.039 | 2.5 | 100 |
| Sonnet 5, cache on, no thinking | 0.018 | 1.1 | 47 |
| Sonnet 5, cache **off** (the code as it stands) | 0.030 | 1.9 | 78 |
| Opus 5, cache on, no thinking | 0.045 | 2.9 | 120 |

Reasoning on, 1,500 thinking tokens assumed:

| Model | $/message | × Terra today |
|---|---|---|
| DeepSeek V4 Pro direct, off-peak | 0.007 | 0.4 |
| DeepSeek V4 Pro direct, peak | 0.013 | 0.8 |
| **Grok 4.6** | **0.022** | 1.4 |
| DeepSeek V4 Pro, Azure EU (no cache assumed) | 0.021 | 1.3 |
| Gemini 3.5 Flash | 0.025 | 1.6 |
| Terra, if the Responses API were available | 0.034 | 2.1 |
| Sonnet 5, cache on, thinking | 0.038 | 2.4 |
| Sol promotional | 0.058 | 3.7 |
| Opus 5, cache on, thinking | 0.06–0.10 | 4–6 |

Worked example, Grok 4.6: 3,100 × $2 + 4,450 × $0.50 + 2,200 × $6, all per million =
$0.0062 + $0.0022 + $0.0132 = $0.0216. Sonnet 5 applies the 30% tokenizer factor to every
line first: 4,000 × $2 + 5,800 × $0.20 + 2,900 × $10 = $0.008 + $0.0012 + $0.029 = $0.038.

The month column assumes every account maxes the free tier (2,600 messages); it is a ceiling.
At 5,000 subscribers multiply by about 170.

## Quality, Artificial Analysis Intelligence Index v4.1.1 (2026-08-29)

| Model | Index |
|---|---|
| Claude Opus 5 | 63.0 |
| Grok 4.6 | 60.9 |
| GPT-5.6 Sol | 58.9 |
| Claude Sonnet 5 | 55.3 |
| GPT-5.6 Terra (reasoning on — not what we run) | 55.0 |
| DeepSeek V4 Pro | 53.2 |
| DeepSeek V4 Flash | 51.8 |
| Gemini 3.5 Flash | 50.2 |
| Grok 4.3 | 37.6 |
| Mistral Medium 3.5 | 30.4 |

Benchmarks are a screen, not a verdict: this product is tool calls over tabular football data
and an explanation, which no index measures directly.

## Paths to reasoning

1. **Terra with reasoning** — needs the Responses API. Wait for spring-ai #2962, or write a
   `ChatModel` on OpenAI's official Java SDK (tool loop, streaming, usage), or put a translating
   proxy in front. Not recommended: the work is real, and Terra with reasoning would cost more
   per message than Grok 4.6 anyway because of its $12 output.
2. **Switch provider within Spring AI.** Every module below is already in the 2.0.0 BOM.

| Model | How | Data location | Verdict |
|---|---|---|---|
| **Grok 4.6** | OpenAI module, `spring.ai.openai.chat.base-url` → `https://eu-west-1.api.x.ai`, `reasoning_effort` accepted with tools on Chat Completions | EU endpoint | **first to trial**: highest index in Terra's price band, cheapest output, configuration only |
| **Claude Sonnet 5** | `spring-ai-anthropic`, thinking via `ThinkingConfigParam`; enable `AnthropicCacheOptions` first | global (US-only at 1.1×) | second to trial; known path, ~2.4× today |
| Gemini 3.5 Flash | `spring-ai-google-genai`, `thinking-level`, Developer API key | Google; EU only through Vertex | index below Terra — does not fix quality |
| DeepSeek V4 Pro direct | `spring-ai-deepseek` (the OpenAI-compatible route loses `reasoningContent`, spring-ai #6375) | China | no for EU users' chat content |
| DeepSeek V4 Pro, Azure Foundry EU | Azure endpoint | EU regions | Grok's price, lower index — dropped |
| Mistral Medium 3.5 | `spring-ai-mistral-ai` | EU | not in this class |

Caveats on Grok: cache discount 75%; xAI's production rate limits and reliability are unknown to
us; `reasoning_effort` defaults to `high`, which may produce more thinking tokens than assumed;
and the choice of vendor is also a brand question, which is the owner's.

## The trial, before any decision

1. Collect 15–20 questions where Terra's answer was weak.
2. Make `application.yml` configurable: model name and `reasoning-effort` as environment
   variables, `spring.ai.openai.chat.base-url` for xAI (embeddings keep OpenAI — `chat.base-url`
   does not touch them), Anthropic cache options on.
3. Run Grok 4.6 and Sonnet 5 locally through `SPRING_AI_PROVIDER`; compare answers.
4. Read `fpl_usage_event` for real output tokens per turn — that replaces the 1,500 assumption.
5. Check per provider whether cache reads are counted inside the prompt total, the open question
   in `docs/STATUS.md` ("Token accounting per turn"): if so, `total_tokens` double-counts.

## A better model for paying customers

Sensible in principle, three conditions first. There is no paid tier yet (Stripe is KAR-28). Do
not buy quality before knowing where it is missing — the reasoning gap is the cheaper
explanation. And a per-tier model is easy within one provider (a per-request model option) and
awkward across providers: `spring.ai.model.chat` activates one provider's autoconfiguration, so
Terra for free and Opus for paid would mean both keys, both autoconfigurations and dismantling
the gate. Sonnet for free and Opus for paid, or Grok 4.3 and Grok 4.6, is the shape that fits.

## Sources

- [Anthropic pricing](https://platform.claude.com/docs/en/about-claude/pricing) — prices, cache multipliers, tokenizer note
- [OpenAI pricing](https://developers.openai.com/api/docs/pricing) · [OpenAI prompt caching](https://developers.openai.com/api/docs/guides/prompt-caching)
- [GPT-5.6 tools + reasoning refused on Chat Completions — litellm #33221](https://github.com/BerriAI/litellm/issues/33221) · [OpenAI community thread](https://community.openai.com/t/gpt-5-6-chat-completion-reasoning-effort-bug-behavior-change/1386454)
- [spring-ai #2962 Responses API](https://github.com/spring-projects/spring-ai/issues/2962) · [Spring AI 2.0 GA](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)
- [xAI models and pricing](https://docs.x.ai/docs/models) · [Grok 4.6](https://docs.x.ai/developers/grok-4-6) · [Grok 4.6 on Bedrock](https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-xai-grok-4-6.html)
- [Gemini pricing](https://ai.google.dev/gemini-api/docs/pricing) · [Gemini caching](https://ai.google.dev/gemini-api/docs/interactions/caching) · [Spring AI Google GenAI](https://docs.spring.io/spring-ai/reference/api/chat/google-genai-chat.html)
- [DeepSeek pricing](https://api-docs.deepseek.com/quick_start/pricing) · [DeepSeek V4 on Foundry](https://learn.microsoft.com/en-us/answers/a/12843688)
- [Mistral pricing](https://mistral.ai/pricing) (model rates from third-party pages)
- [AA Intelligence Index leaderboard](https://benchlm.ai/benchmarks/artificialanalysis) · [DataCamp: Terra vs Sonnet 5](https://www.datacamp.com/blog/gpt-5-6-terra-vs-claude-sonnet-5) · [llm-stats: Opus 5 vs Sol](https://llm-stats.com/models/compare/claude-opus-5-vs-gpt-5.6-sol)
