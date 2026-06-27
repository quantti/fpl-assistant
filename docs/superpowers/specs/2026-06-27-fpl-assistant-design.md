# FPL Assistant — Design Spec

**Date:** 2026-06-27
**Status:** Approved

---

## Overview

A general-purpose Fantasy Premier League chatbot that answers questions about players, fixtures, strategy, and game rules. Users interact via a standalone web chat UI. The LLM is backed by three knowledge layers: static rules in the system prompt,
strategy docs via RAG, and live/historical FPL data via tool calls against Supabase.

---

## Architecture

```
[React Frontend]  ──SSE streaming──►  [Spring Boot — Fly.io]
  Vite + TS                                      │
  React + Mantine                     ┌──────────┼──────────┐
  CSS Modules                         ▼          ▼          ▼
                               System Prompt  pgvector   @Tool methods
                               (FPL rules)    RAG        (SQL queries)
                                                │              │
                                                └──────────────┘
                                                      │
                                            [Supabase PostgreSQL]
                                            (shared with tapas DB)
```

### Data flow per query

1. User sends message (client includes full conversation history)
2. Spring Boot builds prompt: system prompt + RAG retrieval on user query
3. Spring AI sends to LLM with available tools
4. LLM calls 0–N tools (SQL queries against Supabase)
5. Spring AI executes tools, appends results, re-queries LLM
6. LLM streams final response
7. Frontend renders tokens as SSE stream arrives

---

## Backend — Spring Boot

**Stack:** Java 25 (LTS), Spring Boot 4.1.x, Spring AI 1.1.7, Gradle

> Spring AI 2.0 (milestone) ships MCP in core — upgrade when it goes GA.

### Spring AI configuration

- `ChatClient` with system prompt template loaded from `resources/prompts/system-prompt.st`
- `VectorStore` (pgvector) backed by new `fpl_embeddings` table in Supabase — isolated from tapas schema
- Configurable LLM provider via `spring.ai.provider` env var: `anthropic` (Claude) or `openai` (GPT-4o)
- Streaming via `ChatClient.stream()` → SSE

### Auth & billing

- **Auth:** Supabase JWT. Every request to `/chat` requires `Authorization: Bearer <jwt>`. Spring Boot validates JWT signature against Supabase JWT secret.
- **Billing:** Stripe subscriptions. Active subscription required to use chat. Spring Boot checks `user_subscription.status = 'active'` before serving each request.
- **Stripe webhook** (`POST /webhooks/stripe`): handles `customer.subscription.created/updated/deleted` → updates `user_subscription` table.

### Conversation model

Server-side per-user history. Client sends only the new message; backend loads conversation history from DB, appends, queries LLM, persists response.

```sql
CREATE TABLE conversation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE message (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    role VARCHAR(10) NOT NULL,   -- 'user' | 'assistant'
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
```

RLS enforced: users can only read/write their own conversations and messages.

```sql
CREATE TABLE user_subscription (
    user_id UUID PRIMARY KEY REFERENCES auth.users(id),
    stripe_customer_id VARCHAR(255) NOT NULL,
    stripe_subscription_id VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'inactive',  -- 'active' | 'inactive' | 'canceled'
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
```

### Tools (`@Tool` annotated methods)

All tools query Supabase via JDBC. No external HTTP calls at chat time.

| Tool                       | Description                                | Source table                     |
| -------------------------- | ------------------------------------------ | -------------------------------- |
| `getPlayersByForm`         | Top players by form/xG last N GWs          | `player_fixture_stats`, `player` |
| `getFixtures`              | Upcoming fixtures + FDR for a team or GW   | `fixture`, `team`                |
| `getPlayerStats`           | Season stats for a named player            | `player`, `player_gw_stats`      |
| `getLeagueOwnership`       | Global ownership % for a player            | `player` (selected_by_percent)   |
| `getGameweekInfo`          | Current/next GW deadline, avg score        | `gameweek`                       |
| `getTeamForm`              | Last N results for a team, home/away split | `pl_match_result` (synced)       |
| `getHeadToHead`            | Historical results between two teams       | `pl_match_result` (synced)       |
| `getTeamFixtureDifficulty` | FDR trend across seasons for a team        | `pl_match_result`, `team`        |

### System prompt

Located at `resources/prompts/system-prompt.st`. Contains:

- FPL 2025/26 rules (condensed from FPL_RULES.md)
- Persona: helpful FPL expert, concise, data-driven
- Instruction to use tools before answering stat questions
- Instruction to cite sources (tool names used)

### RAG knowledge base

Markdown files under `resources/docs/`:

- `strategy-captaincy.md`
- `strategy-transfers.md`
- `strategy-chips.md`
- `strategy-differentials.md`
- `player-archetypes.md`
- (add more over time)

Ingested via `IngestionRunner` (Spring Boot `ApplicationRunner` with `--ingest` flag). Chunks at 500 tokens, 50-token overlap. Embedding model: always `text-embedding-3-small` (OpenAI) regardless of chat LLM provider — consistent vectors, lower cost. Stored in `fpl_embeddings` table (pgvector).

At query time: top-3 chunks retrieved by cosine similarity, injected into prompt as `[CONTEXT]` block before user message.

### Data sync — historical fixture/form data

New table: `pl_match_result` in Supabase.

```sql
CREATE TABLE pl_match_result (
    id SERIAL PRIMARY KEY,
    season VARCHAR(10) NOT NULL,        -- '2024-25'
    gameweek INTEGER,
    home_team_id INTEGER NOT NULL,
    away_team_id INTEGER NOT NULL,
    home_goals INTEGER NOT NULL,
    away_goals INTEGER NOT NULL,
    kickoff_time TIMESTAMPTZ,
    source VARCHAR(20) NOT NULL         -- 'vaastav' | 'football-data'
);
CREATE INDEX idx_pmr_season ON pl_match_result(season);
CREATE INDEX idx_pmr_home ON pl_match_result(home_team_id, season);
CREATE INDEX idx_pmr_away ON pl_match_result(away_team_id, season);
```

**Backfill (one-time):** Parse vaastav/Fantasy-Premier-League CSV files for seasons 2016/17–2024/25. Java CLI task or Python script. No rate limits. Vaastav CSVs use team names → mapped to FPL team IDs via a hardcoded name→id lookup table (20 PL teams, stable within a season).

**Ongoing sync:** Weekly scheduled task hits football-data.org API (free tier, 10 req/min — fine for ~38 requests/season). football-data.org team IDs → FPL team IDs via same name lookup table.

---

## Frontend — Vite + React + Mantine

**Stack:** Vite 8.1, React 18, TypeScript, Mantine v9, CSS Modules

### Pages

React Router. Routes:

| Route | View | Guard |
|---|---|---|
| `/login` | Login (Supabase Auth UI) | — |
| `/subscribe` | Stripe checkout / plan selection | Auth required |
| `/chat` | Chat view | Auth + active subscription required |

Unauthenticated → redirect to `/login`. Authenticated but no subscription → redirect to `/subscribe`.

### Components

```
App (Router)
├── LoginView         (Supabase Auth UI embed)
├── SubscribeView     (plan cards + Stripe Checkout redirect)
└── ChatView          (protected)
    ├── MessageThread (scrollable)
    │   ├── UserMessage
    │   └── AssistantMessage (markdown rendered, streaming cursor)
    ├── MessageInput (textarea + send button)
    └── StatusBar (model name, subscription tier)
```

### Auth state

Supabase JS client manages session. `useSession()` hook exposes JWT; attached as `Authorization` header on all API calls.

### State

```typescript
type Message = { role: "user" | "assistant"; content: string };
```

No external state library. Messages loaded from server on mount (current conversation). `useRef` for auto-scroll. SSE via `fetch` with `ReadableStream`.

### Streaming

`POST /chat` returns `text/event-stream`. Each SSE event is a token chunk. Frontend appends chunks to last assistant message as they arrive. On `[DONE]` event, set `streaming = false`.

### Deployment

Fly.io static hosting. Frontend built with `vite build`, served via nginx sidecar or Spring Boot static resources. New standalone project.

---

## Project structure

```
fpl-assistant/
├── backend/
│   ├── src/main/java/fi/karivanttinen/fplassistant/
│   │   ├── chat/
│   │   │   ├── ChatController.java      # POST /chat SSE, GET /conversations
│   │   │   └── ChatService.java         # Spring AI orchestration
│   │   ├── auth/
│   │   │   └── JwtFilter.java           # Validate Supabase JWT on every request
│   │   ├── billing/
│   │   │   ├── StripeWebhookController.java  # POST /webhooks/stripe
│   │   │   └── SubscriptionService.java      # Check active subscription
│   │   ├── tools/
│   │   │   ├── PlayerTools.java
│   │   │   ├── FixtureTools.java
│   │   │   └── GameweekTools.java
│   │   ├── rag/
│   │   │   ├── VectorStoreConfig.java
│   │   │   └── IngestionRunner.java
│   │   ├── sync/
│   │   │   ├── VaastavImporter.java
│   │   │   └── FootballDataSyncJob.java
│   │   └── config/
│   │       ├── AiConfig.java
│   │       └── WebConfig.java
│   ├── src/main/resources/
│   │   ├── prompts/system-prompt.st
│   │   └── docs/
│   ├── build.gradle
│   └── fly.toml
├── frontend/
│   ├── src/
│   │   ├── views/
│   │   │   ├── LoginView.tsx
│   │   │   ├── SubscribeView.tsx
│   │   │   └── ChatView.tsx
│   │   ├── components/
│   │   │   ├── MessageThread.tsx
│   │   │   ├── AssistantMessage.tsx
│   │   │   └── MessageInput.tsx
│   │   ├── hooks/
│   │   │   ├── useChat.ts
│   │   │   └── useSession.ts
│   │   └── App.tsx
│   ├── package.json
│   └── vite.config.ts
└── docs/
    └── superpowers/specs/
        └── 2026-06-27-fpl-assistant-design.md
```

---

## Deployment

| Service               | Platform | Notes                                                              |
| --------------------- | -------- | ------------------------------------------------------------------ |
| Backend (Spring Boot) | Fly.io   | ~2-3s cold start on free tier, acceptable for chat                 |
| Frontend (React)      | Fly.io (static) | New standalone project, served as static files from Fly.io  |
| Database              | Supabase | Shared with tapas, new tables: `fpl_embeddings`, `pl_match_result`, `conversation`, `message`, `user_subscription` |

**Fly.io secrets:** `SPRING_AI_PROVIDER`, `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `DATABASE_URL`, `SUPABASE_JWT_SECRET`, `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `FOOTBALL_DATA_API_KEY`.

---

## Build phases

### Phase 1 — Auth + billing + core chatbot

- Supabase auth (login/signup)
- Stripe subscription gating
- Spring Boot with `getPlayerStats`, `getFixtures`, `getGameweekInfo` tools
- System prompt with FPL rules
- React frontend: login → subscribe → chat flow
- Server-side conversation history
- Deployed end-to-end

### Phase 2 — RAG

- Strategy markdown docs
- pgvector ingestion pipeline
- RAG retrieval injected into prompt

### Phase 3 — Historical data

- `pl_match_result` table
- Vaastav CSV backfill
- football-data.org weekly sync
- `getTeamForm`, `getHeadToHead`, `getTeamFixtureDifficulty` tools

### Phase 4 — Enrichment

- More tools (price trends, captain differentials)
- Understat xG per team
- More strategy docs
