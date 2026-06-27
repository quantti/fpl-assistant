# FPL Assistant — Design Spec

**Date:** 2026-06-27
**Status:** Approved

---

## Overview

A general-purpose Fantasy Premier League chatbot that answers questions about players, fixtures, strategy, and game rules. Users interact via a standalone web chat UI. The LLM is backed by three knowledge layers: static rules in the system prompt, strategy docs via RAG, and live/historical FPL data via tool calls against Supabase.

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

**Stack:** Java 21, Spring Boot 3.x, Spring AI, Gradle

### Spring AI configuration

- `ChatClient` with system prompt template loaded from `resources/prompts/system-prompt.st`
- `VectorStore` (pgvector) backed by new `fpl_embeddings` table in Supabase — isolated from tapas schema
- Configurable LLM provider via `spring.ai.provider` env var: `anthropic` (Claude) or `openai` (GPT-4o)
- Streaming via `ChatClient.stream()` → SSE

### Conversation model

Stateless: client sends full message history (`List<Message>`) with each request. No server-side session storage. No auth required.

### Tools (`@Tool` annotated methods)

All tools query Supabase via JDBC. No external HTTP calls at chat time.

| Tool | Description | Source table |
|---|---|---|
| `getPlayersByForm` | Top players by form/xG last N GWs | `player_fixture_stats`, `player` |
| `getFixtures` | Upcoming fixtures + FDR for a team or GW | `fixture`, `team` |
| `getPlayerStats` | Season stats for a named player | `player`, `player_gw_stats` |
| `getLeagueOwnership` | Global ownership % for a player | `player` (selected_by_percent) |
| `getGameweekInfo` | Current/next GW deadline, avg score | `gameweek` |
| `getTeamForm` | Last N results for a team, home/away split | `pl_match_result` (synced) |
| `getHeadToHead` | Historical results between two teams | `pl_match_result` (synced) |
| `getTeamFixtureDifficulty` | FDR trend across seasons for a team | `pl_match_result`, `team` |

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

**Stack:** Vite, React 18, TypeScript, Mantine v7, CSS Modules

### Pages

Single page app, single route `/`. No router needed.

### Components

```
App
└── ChatView
    ├── MessageThread (scrollable)
    │   ├── UserMessage
    │   └── AssistantMessage (markdown rendered, streaming cursor)
    ├── MessageInput (textarea + send button)
    └── StatusBar (model name, connection status)
```

### State

```typescript
type Message = { role: 'user' | 'assistant'; content: string }
const [messages, setMessages] = useState<Message[]>([])
const [streaming, setStreaming] = useState(false)
```

No external state library. `useRef` for auto-scroll. SSE via `EventSource` or `fetch` with `ReadableStream`.

### Streaming

`POST /chat` returns `text/event-stream`. Each SSE event is a token chunk. Frontend appends chunks to last assistant message as they arrive. On `[DONE]` event, set `streaming = false`.

### Deployment

Vercel (or Fly.io static, TBD). New standalone project, not part of tapas repo.

---

## Project structure

```
fpl-assistant/
├── backend/
│   ├── src/main/java/fi/karivanttinen/fplassistant/
│   │   ├── chat/
│   │   │   ├── ChatController.java      # POST /chat SSE endpoint
│   │   │   └── ChatService.java         # Spring AI orchestration
│   │   ├── tools/
│   │   │   ├── PlayerTools.java         # getPlayersByForm, getPlayerStats, etc.
│   │   │   ├── FixtureTools.java        # getFixtures, getTeamForm, getHeadToHead
│   │   │   └── GameweekTools.java       # getGameweekInfo, getLeagueOwnership
│   │   ├── rag/
│   │   │   ├── VectorStoreConfig.java   # pgvector setup
│   │   │   └── IngestionRunner.java     # CLI doc ingestion
│   │   ├── sync/
│   │   │   ├── VaastavImporter.java     # One-time CSV backfill
│   │   │   └── FootballDataSyncJob.java # Weekly scheduled sync
│   │   └── config/
│   │       ├── AiConfig.java            # ChatClient, VectorStore beans
│   │       └── WebConfig.java           # CORS
│   ├── src/main/resources/
│   │   ├── prompts/system-prompt.st
│   │   └── docs/                        # Strategy markdown files
│   ├── build.gradle
│   └── fly.toml
├── frontend/
│   ├── src/
│   │   ├── components/
│   │   │   ├── ChatView.tsx
│   │   │   ├── MessageThread.tsx
│   │   │   ├── AssistantMessage.tsx
│   │   │   └── MessageInput.tsx
│   │   ├── hooks/
│   │   │   └── useChat.ts              # SSE streaming logic
│   │   └── App.tsx
│   ├── package.json
│   └── vite.config.ts
└── docs/
    └── superpowers/specs/
        └── 2026-06-27-fpl-assistant-design.md
```

---

## Deployment

| Service | Platform | Notes |
|---|---|---|
| Backend (Spring Boot) | Fly.io | ~2-3s cold start on free tier, acceptable for chat |
| Frontend (React) | Vercel | New standalone project, separate from tapas |
| Database | Supabase | Shared with tapas, new tables: `fpl_embeddings`, `pl_match_result` |

**Fly.io config:** `fly.toml` with `SPRING_AI_PROVIDER`, `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `DATABASE_URL`, `FOOTBALL_DATA_API_KEY` secrets.

---

## Build phases

### Phase 1 — Core chatbot (no RAG, limited tools)
- Spring Boot with `getPlayerStats`, `getFixtures`, `getGameweekInfo` tools
- System prompt with FPL rules
- React frontend with streaming chat UI
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
