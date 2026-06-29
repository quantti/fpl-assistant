# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

General-purpose Fantasy Premier League (FPL) chatbot. Web chat UI backed by an LLM with three knowledge layers: static FPL rules in the system prompt, strategy docs via RAG (pgvector), and live/historical stats via tool calls against Supabase.

Auth (Supabase JWT) + Stripe billing are deferred to the last phase. Build the chatbot first.

## Architecture

- **Backend:** Spring Boot 4.1, Java 25, Spring AI 2.0.0, Gradle. Serves both REST API and the built frontend as static resources from a single Fly.io app.
- **Frontend:** Vite 8.1 + React 18 + TypeScript + Mantine v9 + CSS Modules. No Tailwind.
- **DB:** Supabase PostgreSQL — shared with the `tapas-fpl-app` project (`~/dev/tapas-fpl-app`). FPL stats (players, fixtures, gw stats, ownership, xG/xA) already live there. New tables added by this project: `fpl_embeddings`, `pl_match_result`, `conversation`, `message`, `user_subscription`.
- **LLM provider:** swappable via `SPRING_AI_PROVIDER` env var (`anthropic` default, or `openai`).
- **Chat transport:** `POST /api/chat` returns `text/event-stream` (SSE). Frontend reads via `fetch` + `ReadableStream`.

## Key references

- Design spec: `docs/superpowers/specs/2026-06-27-fpl-assistant-design.md`
- Phase 1 plan: `docs/superpowers/plans/2026-06-27-phase1-core-chatbot.md`
- Source DB schema (tapas): `~/dev/tapas-fpl-app/backend/DB.md`
- FPL game rules: `~/dev/tapas-fpl-app/backend/FPL_RULES.md`

## Conventions

- All DB queries filter by current season: `WHERE season_id = (SELECT id FROM season WHERE is_current = true)`.
- Do NOT alter existing tapas tables. Only add new tables.
- Player prices stored as integers ×10 (`now_cost=75` = £7.5m). `element_type`: 1=GK, 2=DEF, 3=MID, 4=FWD.
- External APIs (vaastav CSVs, football-data.org) are hit only at sync time, never at chat time — everything queries Supabase.
- Tools query Supabase via `JdbcTemplate`, annotated with Spring AI `@Tool`.

## Progress tracking

Plans use a single `**PROGRESS:**` line (e.g. `Task 3/14 done`), updated once per task. Do NOT use per-step `- [ ]` checkboxes — they waste tokens on every flip.

## Deployment

Single Fly.io app (region `mad`) serving API + frontend. Frontend built in a Docker multi-stage build, copied into Spring Boot `static/`. Secrets via `fly secrets set`. No CI yet — deploy manually with `fly deploy` for now.
