# FPL Assistant — Phase 1: Core Chatbot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.
>
> **Progress tracking:** Do NOT use per-step checkboxes. Track via the single `**PROGRESS:**` line below — update only that one line after each task completes (e.g. `Task 3/14 done`). One small edit per task, not per step.

**PROGRESS:** Task 4/14 done

> **Before following any step below, read `docs/STATUS.md`.** Several steps are stale: the
> Gradle version, the Testcontainers dependencies, the `.env.test` credentials, the system prompt's
> season and scoring facts, and — affecting every remaining tool — the `is_current` season filter,
> which matches no rows at all and is replaced by `SeasonResolver`.

**Goal:** Build and deploy a working FPL chatbot with streaming chat UI, 3 DB-backed tools, and FPL rules in the system prompt — no auth, open access.

**Architecture:** Spring Boot 4.1 backend serves both the REST API and built frontend static files from a single Fly.io app. Spring AI 2.0 ChatClient with `@Tool` methods queries the existing Supabase PostgreSQL DB (tapas schema). React frontend streams responses via `fetch` + `ReadableStream`.

**Tech Stack:** Java 25, Spring Boot 4.1.x, Spring AI 2.0.0, Gradle (Groovy DSL), Vite 8.1, React 18, TypeScript, Mantine v9, CSS Modules, Supabase PostgreSQL (existing), Fly.io

## Global Constraints

- Java toolchain: 25 (LTS)
- Spring Boot: 4.1.x
- Spring AI: 2.0.0
- Mantine: v9 (not v7 or v8)
- Vite: 8.x
- No auth on any endpoint (Phase 1)
- All DB queries filter by `season_id = (SELECT id FROM season WHERE is_current = true)`
- DB tables live in existing Supabase project — do not alter existing tapas tables
- LLM provider configurable via `SPRING_AI_PROVIDER` env var: `anthropic` (default) or `openai`
- Frontend served from Spring Boot static resources (`src/main/resources/static/`)
- SSE via `POST /api/chat`, not GET (message history in request body)

---

## File Map

```
fpl-assistant/
├── backend/
│   ├── build.gradle
│   ├── settings.gradle
│   ├── fly.toml
│   ├── Dockerfile
│   └── src/
│       ├── main/
│       │   ├── java/fi/karivanttinen/fplassistant/
│       │   │   ├── FplAssistantApplication.java
│       │   │   ├── config/
│       │   │   │   ├── AiConfig.java            # ChatClient bean, provider selection
│       │   │   │   └── WebConfig.java           # CORS
│       │   │   ├── chat/
│       │   │   │   ├── ChatController.java      # POST /api/chat → SSE
│       │   │   │   ├── ChatService.java         # Spring AI orchestration
│       │   │   │   └── ChatRequest.java         # Request DTO
│       │   │   └── tools/
│       │   │       ├── GameweekTools.java       # getGameweekInfo()
│       │   │       ├── PlayerTools.java         # getPlayerStats(playerName)
│       │   │       └── FixtureTools.java        # getUpcomingFixtures(teamName, nextN)
│       │   └── resources/
│       │       ├── application.yml
│       │       └── prompts/
│       │           └── system-prompt.st
│       └── test/
│           └── java/fi/karivanttinen/fplassistant/
│               ├── tools/
│               │   ├── GameweekToolsTest.java
│               │   ├── PlayerToolsTest.java
│               │   └── FixtureToolsTest.java
│               └── chat/
│                   ├── ChatServiceTest.java
│                   └── ChatControllerTest.java
├── frontend/
│   ├── package.json
│   ├── vite.config.ts
│   ├── tsconfig.json
│   ├── index.html
│   └── src/
│       ├── main.tsx
│       ├── App.tsx
│       ├── types.ts
│       ├── hooks/
│       │   └── useChat.ts
│       └── components/
│           ├── ChatView/
│           │   ├── ChatView.tsx
│           │   └── ChatView.module.css
│           ├── MessageThread/
│           │   ├── MessageThread.tsx
│           │   └── MessageThread.module.css
│           ├── MessageInput/
│           │   ├── MessageInput.tsx
│           │   └── MessageInput.module.css
│           └── AssistantMessage/
│               ├── AssistantMessage.tsx
│               └── AssistantMessage.module.css
└── docs/
    └── superpowers/
        ├── specs/2026-06-27-fpl-assistant-design.md
        └── plans/2026-06-27-phase1-core-chatbot.md
```

---

### Task 1: Spring Boot project scaffold

**Files:**
- Create: `backend/settings.gradle`
- Create: `backend/build.gradle`
- Create: `backend/src/main/java/fi/karivanttinen/fplassistant/FplAssistantApplication.java`
- Create: `backend/src/main/resources/application.yml`

**Interfaces:**
- Produces: runnable Spring Boot app skeleton, Gradle build with all dependencies

**Step 1: Create `backend/settings.gradle`**

```groovy
rootProject.name = 'fpl-assistant'
```

**Step 2: Create `backend/build.gradle`**

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '4.1.0'
    id 'io.spring.dependency-management' version '1.1.7'
}

group = 'fi.karivanttinen'
version = '0.0.1-SNAPSHOT'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    maven { url 'https://repo.spring.io/milestone' }
}

dependencyManagement {
    imports {
        mavenBom "org.springframework.ai:spring-ai-bom:2.0.0"
    }
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-jdbc'
    implementation 'org.postgresql:postgresql'
    implementation 'org.springframework.ai:spring-ai-starter-model-anthropic'
    implementation 'org.springframework.ai:spring-ai-starter-model-openai'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.boot:spring-boot-testcontainers'
    testImplementation 'org.testcontainers:postgresql'
    // WebTestClient (MockMvcWebTestClient) for streaming SSE controller tests
    testImplementation 'org.springframework:spring-webflux'
    testImplementation 'io.projectreactor:reactor-test'
}
```

**Step 3: Create `FplAssistantApplication.java`**

```java
package fi.karivanttinen.fplassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FplAssistantApplication {
    public static void main(String[] args) {
        SpringApplication.run(FplAssistantApplication.class, args);
    }
}
```

**Step 4: Create `backend/src/main/resources/application.yml`**

```yaml
spring:
  datasource:
    url: ${DATABASE_URL}
    username: ${DB_USER}
    password: ${DB_PASSWORD}
    driver-class-name: org.postgresql.Driver
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY:}
      chat:
        options:
          model: claude-sonnet-4-6
    openai:
      api-key: ${OPENAI_API_KEY:}
      chat:
        options:
          model: gpt-4o

fpl:
  ai:
    provider: ${SPRING_AI_PROVIDER:anthropic}

server:
  port: 8080
```

**Step 5: Generate the Gradle wrapper**

Requires a system Gradle 9.x (`gradle --version` must show 9.0+; Java 25 + Spring Boot 4 need it). Install via SDKMAN if missing: `sdk install gradle 9.1`.

```bash
cd backend
gradle wrapper --gradle-version 9.1
```

Expected: creates `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.properties` + jar. Commit these — all later tasks use `./gradlew`.

**Step 6: Verify the build compiles**

```bash
cd backend
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

**Step 7: Commit**

```bash
git add backend/
git commit -m "feat: scaffold Spring Boot project with Spring AI 2.0 dependencies"
```

---

### Task 2: Database connection test

**Files:**
- Modify: `backend/src/main/resources/application.yml` (add local dev datasource)
- Create: `backend/src/test/java/fi/karivanttinen/fplassistant/DbConnectionTest.java`

**Interfaces:**
- Consumes: `JdbcTemplate` auto-configured by Spring Boot
- Produces: verified live DB connection; other tasks can trust `JdbcTemplate` works

**Step 1: Write the DB connection test**

```java
package fi.karivanttinen.fplassistant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DbConnectionTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void canQueryCurrentSeason() {
        Integer seasonId = jdbc.queryForObject(
            "SELECT id FROM season WHERE is_current = true", Integer.class);
        assertThat(seasonId).isNotNull();
    }
}
```

**Step 2: Create `backend/.env.test` (gitignored) with your Supabase credentials**

Credentials are split out so the JDBC URL stays free of `?user=...&password=...` (the `&` breaks shell env loading). Use the Supabase pooler host (port 6543) or direct (5432). SSL required.

```
DATABASE_URL=jdbc:postgresql://<host>:6543/postgres?sslmode=require
DB_USER=postgres
DB_PASSWORD=<your-db-password>
ANTHROPIC_API_KEY=sk-ant-...
SPRING_AI_PROVIDER=anthropic
```

Add to `.gitignore`:
```
backend/.env.test
backend/.env
```

**Step 3: Run the test (load env safely first)**

`set -a; source` exports every var without word-splitting on `&`. Do NOT use `export $(cat ... | xargs)` — it backgrounds on `&` and mangles URLs.

```bash
cd backend
set -a; source .env.test; set +a
./gradlew test --tests "fi.karivanttinen.fplassistant.DbConnectionTest"
```

Expected: `BUILD SUCCESSFUL`, 1 test passed

**Step 4: Commit**

```bash
git add backend/
git commit -m "test: verify Supabase DB connection"
```

---

### Task 3: System prompt + AI config

**Files:**
- Create: `backend/src/main/resources/prompts/system-prompt.st`
- Create: `backend/src/main/java/fi/karivanttinen/fplassistant/config/AiConfig.java`

**Interfaces:**
- Produces: `ChatClient` Spring bean; `ChatService` (Task 6) injects this

**Step 1: Create `system-prompt.st`**

```
You are an expert Fantasy Premier League (FPL) assistant for the 2025/26 season.

## Your role
Answer questions about FPL players, fixtures, strategy, and game rules.
Always use tools to fetch live data before answering stat questions.
Be concise and data-driven. Cite which tool you used.

## FPL 2025/26 Key Rules
- Squad: 15 players (2 GK, 5 DEF, 5 MID, 3 FWD), £100m budget, max 3 from one club
- Starting XI: min 1 GK, 3 DEF, 1 FWD. Valid formations: 3-4-3, 3-5-2, 4-3-3, 4-4-2, 4-5-1, 5-3-2, 5-4-1
- Captain scores double; vice-captain doubles only if captain plays 0 minutes
- Free transfers: 1 per GW, bank up to 5. Extra transfers cost -4 pts each
- TWO chip sets this season (GW1-19 and GW20-38): wildcard, free hit, triple captain, bench boost
- Chips reset at GW20 — unused first-half chips are lost
- Free Hit: unlimited transfers for one GW, squad reverts after. Cannot use in both GW19 and GW20
- Wildcard: all transfers free; you keep banked FTs; no +1 FT that GW
- AFCON: FTs topped up to 5 at GW16

## Scoring
- Goals: GK/DEF 6pts, MID 5pts, FWD 4pts
- Assists: 3pts
- Clean sheets (60+ min): GK/DEF 4pts, MID 1pt
- Play time: 1-59 min = 1pt, 60+ min = 2pts
- Bonus: 3/2/1 pts to top BPS scorers per match
- Defensive contributions (NEW): DEF 10+ CBIT = 2pts; MID/FWD 12+ CBIRT = 2pts
- Negatives: yellow -1, red -3, own goal -2, missed pen -2

## Position codes
element_type: 1=GK, 2=DEF, 3=MID, 4=FWD

## Price format
Prices stored as integers × 10: now_cost=75 means £7.5m
```

**Step 2: Create `AiConfig.java`**

```java
package fi.karivanttinen.fplassistant.config;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration
public class AiConfig {

    @Bean
    public ChatClient chatClient(
            @Value("${fpl.ai.provider:anthropic}") String provider,
            ObjectProvider<AnthropicChatModel> anthropicModel,
            ObjectProvider<OpenAiChatModel> openAiModel,
            @Value("classpath:prompts/system-prompt.st") Resource systemPrompt
    ) throws IOException {
        String prompt = systemPrompt.getContentAsString(StandardCharsets.UTF_8);
        ChatModel model = "openai".equals(provider)
                ? openAiModel.getIfAvailable()
                : anthropicModel.getIfAvailable();
        if (model == null) {
            throw new IllegalStateException(
                "No ChatModel for provider '" + provider + "'. "
                + "Set SPRING_AI_PROVIDER to 'anthropic' or 'openai' and provide the matching API key.");
        }
        return ChatClient.builder(model)
                .defaultSystem(prompt)
                .build();
    }
}
```

**Step 3: Write a smoke test for ChatClient bean creation**

```java
package fi.karivanttinen.fplassistant.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AiConfigTest {

    @Autowired
    ChatClient chatClient;

    @Test
    void chatClientBeanCreated() {
        assertThat(chatClient).isNotNull();
    }
}
```

**Step 4: Run test**

```bash
cd backend
set -a; source .env.test; set +a
./gradlew test --tests "fi.karivanttinen.fplassistant.config.AiConfigTest"
```

Expected: `BUILD SUCCESSFUL`, 1 test passed

**Step 5: Commit**

```bash
git add backend/
git commit -m "feat: add Spring AI ChatClient config and FPL system prompt"
```

---

### Task 4: GameweekTools

**Files:**
- Create: `backend/src/main/java/fi/karivanttinen/fplassistant/tools/GameweekTools.java`
- Create: `backend/src/test/java/fi/karivanttinen/fplassistant/tools/GameweekToolsTest.java`

**Interfaces:**
- Consumes: `JdbcTemplate` bean
- Produces: `GameweekTools` Spring bean with `getGameweekInfo()` method; `ChatService` injects this

**Step 1: Write the failing test**

```java
package fi.karivanttinen.fplassistant.tools;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class GameweekToolsTest {

    @Autowired
    GameweekTools gameweekTools;

    @Test
    void getGameweekInfoReturnsCurrentGwData() {
        String result = gameweekTools.getGameweekInfo();
        assertThat(result).isNotBlank();
        assertThat(result).containsAnyOf("current", "next", "Gameweek");
    }
}
```

**Step 2: Run to verify failure**

```bash
set -a; source .env.test; set +a
./gradlew test --tests "fi.karivanttinen.fplassistant.tools.GameweekToolsTest"
```

Expected: FAIL — `GameweekTools` not found

**Step 3: Implement `GameweekTools.java`**

```java
package fi.karivanttinen.fplassistant.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class GameweekTools {

    private final JdbcTemplate jdbc;

    public GameweekTools(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Tool(description = """
            Get current and next gameweek information: deadline, average score,
            highest score, most captained player ID, most transferred in player ID.
            """)
    public String getGameweekInfo() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, name, deadline_time, finished, is_current, is_next,
                       average_entry_score, highest_score,
                       most_captained, most_transferred_in, most_selected
                FROM gameweek
                WHERE season_id = (SELECT id FROM season WHERE is_current = true)
                  AND (is_current = true OR is_next = true)
                ORDER BY id
                """);
        if (rows.isEmpty()) {
            return "No current or next gameweek data available.";
        }
        return rows.stream()
                .map(r -> formatGameweek(r))
                .collect(Collectors.joining("\n---\n"));
    }

    private String formatGameweek(Map<String, Object> r) {
        return """
                %s (id=%s)
                Status: %s
                Deadline: %s
                Average score: %s | Highest: %s
                Most captained player_id: %s | Most transferred in: %s
                """.formatted(
                r.get("name"), r.get("id"),
                Boolean.TRUE.equals(r.get("is_current")) ? "CURRENT" : "NEXT",
                r.get("deadline_time"),
                r.get("average_entry_score"), r.get("highest_score"),
                r.get("most_captained"), r.get("most_transferred_in")
        );
    }
}
```

**Step 4: Run test to verify it passes**

```bash
./gradlew test --tests "fi.karivanttinen.fplassistant.tools.GameweekToolsTest"
```

Expected: `BUILD SUCCESSFUL`, 1 test passed

**Step 5: Commit**

```bash
git add backend/
git commit -m "feat: add GameweekTools with getGameweekInfo()"
```

---

### Task 5: PlayerTools

**Files:**
- Create: `backend/src/main/java/fi/karivanttinen/fplassistant/tools/PlayerTools.java`
- Create: `backend/src/test/java/fi/karivanttinen/fplassistant/tools/PlayerToolsTest.java`

**Interfaces:**
- Consumes: `JdbcTemplate` bean
- Produces: `PlayerTools` Spring bean with `getPlayerStats(String playerName)` method

**Step 1: Write the failing test**

```java
package fi.karivanttinen.fplassistant.tools;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PlayerToolsTest {

    @Autowired
    PlayerTools playerTools;

    @Test
    void getPlayerStatsByWebName() {
        String result = playerTools.getPlayerStats("Salah");
        assertThat(result).isNotBlank();
        assertThat(result).containsIgnoringCase("salah");
    }

    @Test
    void getPlayerStatsNoMatchReturnsNotFound() {
        String result = playerTools.getPlayerStats("zzznomatch999");
        assertThat(result).containsIgnoringCase("not found");
    }
}
```

**Step 2: Run to verify failure**

```bash
./gradlew test --tests "fi.karivanttinen.fplassistant.tools.PlayerToolsTest"
```

Expected: FAIL

**Step 3: Implement `PlayerTools.java`**

```java
package fi.karivanttinen.fplassistant.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class PlayerTools {

    private final JdbcTemplate jdbc;

    public PlayerTools(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Tool(description = """
            Search for an FPL player by name (web name or surname) and return their
            current season stats: position, team, price, total points, form, goals,
            assists, clean sheets, xG, xA, ownership %, and availability status.
            Returns up to 5 matching players.
            """)
    public String getPlayerStats(String playerName) {
        String pattern = "%" + playerName.toLowerCase() + "%";
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.web_name, p.first_name, p.second_name,
                       t.name as team, t.short_name,
                       p.element_type, p.now_cost, p.total_points, p.form,
                       p.goals_scored, p.assists, p.clean_sheets,
                       p.minutes, p.expected_goals, p.expected_assists,
                       p.selected_by_percent, p.status, p.news
                FROM player p
                JOIN team t ON t.id = p.team_id AND t.season_id = p.season_id
                WHERE p.season_id = (SELECT id FROM season WHERE is_current = true)
                  AND (LOWER(p.web_name) LIKE ?
                       OR LOWER(p.second_name) LIKE ?
                       OR LOWER(p.first_name) LIKE ?)
                ORDER BY p.total_points DESC
                LIMIT 5
                """, pattern, pattern, pattern);

        if (rows.isEmpty()) {
            return "Player not found: " + playerName;
        }

        return rows.stream().map(this::formatPlayer).collect(Collectors.joining("\n---\n"));
    }

    private String formatPlayer(Map<String, Object> r) {
        String position = switch (((Number) r.get("element_type")).intValue()) {
            case 1 -> "GK";
            case 2 -> "DEF";
            case 3 -> "MID";
            case 4 -> "FWD";
            default -> "?";
        };
        double price = ((Number) r.get("now_cost")).doubleValue() / 10.0;
        String status = "a".equals(r.get("status")) ? "Available" : "Unavailable (" + r.get("news") + ")";

        return """
                %s %s (%s) [%s]
                Price: £%.1fm | Points: %s | Form: %s
                Goals: %s | Assists: %s | CS: %s | Minutes: %s
                xG: %s | xA: %s | Ownership: %s%%
                Status: %s
                """.formatted(
                position, r.get("web_name"), r.get("short_name"), r.get("team"),
                price, r.get("total_points"), r.get("form"),
                r.get("goals_scored"), r.get("assists"), r.get("clean_sheets"), r.get("minutes"),
                r.get("expected_goals"), r.get("expected_assists"), r.get("selected_by_percent"),
                status
        );
    }
}
```

**Step 4: Run test**

```bash
./gradlew test --tests "fi.karivanttinen.fplassistant.tools.PlayerToolsTest"
```

Expected: `BUILD SUCCESSFUL`, 2 tests passed

**Step 5: Commit**

```bash
git add backend/
git commit -m "feat: add PlayerTools with getPlayerStats()"
```

---

### Task 6: FixtureTools

**Files:**
- Create: `backend/src/main/java/fi/karivanttinen/fplassistant/tools/FixtureTools.java`
- Create: `backend/src/test/java/fi/karivanttinen/fplassistant/tools/FixtureToolsTest.java`

**Interfaces:**
- Consumes: `JdbcTemplate` bean
- Produces: `FixtureTools` Spring bean with `getUpcomingFixtures(String teamName, int nextN)` method

**Step 1: Write the failing test**

```java
package fi.karivanttinen.fplassistant.tools;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FixtureToolsTest {

    @Autowired
    FixtureTools fixtureTools;

    @Test
    void getUpcomingFixturesForKnownTeam() {
        String result = fixtureTools.getUpcomingFixtures("Arsenal", 5);
        assertThat(result).isNotBlank();
        assertThat(result).containsIgnoringCase("ars");
    }

    @Test
    void getUpcomingFixturesUnknownTeamReturnsNotFound() {
        String result = fixtureTools.getUpcomingFixtures("ZZZUnknownFC", 5);
        assertThat(result).containsIgnoringCase("not found");
    }
}
```

**Step 2: Run to verify failure**

```bash
./gradlew test --tests "fi.karivanttinen.fplassistant.tools.FixtureToolsTest"
```

Expected: FAIL

**Step 3: Implement `FixtureTools.java`**

```java
package fi.karivanttinen.fplassistant.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class FixtureTools {

    private final JdbcTemplate jdbc;

    public FixtureTools(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Tool(description = """
            Get upcoming fixtures for a Premier League team by name or short name
            (e.g. "Arsenal", "ARS", "Manchester City", "MCI").
            Returns fixture difficulty rating (FDR 1-5, lower = easier), kickoff time,
            and home/away status. nextN controls how many future gameweeks to include (max 10).
            """)
    public String getUpcomingFixtures(String teamName, int nextN) {
        int limit = Math.min(nextN, 10);
        String pattern = "%" + teamName.toLowerCase() + "%";

        // First find the team ID
        List<Map<String, Object>> teams = jdbc.queryForList("""
                SELECT id, name, short_name FROM team
                WHERE season_id = (SELECT id FROM season WHERE is_current = true)
                  AND (LOWER(name) LIKE ? OR LOWER(short_name) = LOWER(?))
                LIMIT 1
                """, pattern, teamName);

        if (teams.isEmpty()) {
            return "Team not found: " + teamName;
        }

        int teamId = ((Number) teams.getFirst().get("id")).intValue();
        String resolvedName = (String) teams.getFirst().get("name");
        String resolvedShort = (String) teams.getFirst().get("short_name");

        List<Map<String, Object>> fixtures = jdbc.queryForList("""
                SELECT f.gameweek,
                       th.short_name as home_short, th.name as home_name,
                       ta.short_name as away_short, ta.name as away_name,
                       f.team_h_difficulty, f.team_a_difficulty,
                       f.kickoff_time, f.finished,
                       f.team_h_score, f.team_a_score,
                       f.team_h = ? as is_home
                FROM fixture f
                JOIN team th ON th.id = f.team_h AND th.season_id = f.season_id
                JOIN team ta ON ta.id = f.team_a AND ta.season_id = f.season_id
                WHERE f.season_id = (SELECT id FROM season WHERE is_current = true)
                  AND (f.team_h = ? OR f.team_a = ?)
                  AND f.gameweek >= (
                      SELECT id FROM gameweek
                      WHERE is_current = true
                        AND season_id = (SELECT id FROM season WHERE is_current = true)
                  )
                ORDER BY f.gameweek
                LIMIT ?
                """, teamId, teamId, teamId, limit);

        if (fixtures.isEmpty()) {
            return "No upcoming fixtures found for " + resolvedName;
        }

        String header = "Upcoming fixtures for %s (%s):\n".formatted(resolvedName, resolvedShort);
        String rows = fixtures.stream().map(r -> formatFixture(r, teamId)).collect(Collectors.joining("\n"));
        return header + rows;
    }

    private String formatFixture(Map<String, Object> r, int teamId) {
        boolean isHome = Boolean.TRUE.equals(r.get("is_home"));
        String opponent = isHome ? (String) r.get("away_short") : (String) r.get("home_short");
        int fdr = isHome
                ? ((Number) r.get("team_h_difficulty")).intValue()
                : ((Number) r.get("team_a_difficulty")).intValue();
        String venue = isHome ? "(H)" : "(A)";
        String score = r.get("team_h_score") != null
                ? " [%s-%s]".formatted(r.get("team_h_score"), r.get("team_a_score"))
                : "";

        return "GW%s: vs %s %s | FDR: %d | %s%s".formatted(
                r.get("gameweek"), opponent, venue, fdr, r.get("kickoff_time"), score);
    }
}
```

**Step 4: Run test**

```bash
./gradlew test --tests "fi.karivanttinen.fplassistant.tools.FixtureToolsTest"
```

Expected: `BUILD SUCCESSFUL`, 2 tests passed

**Step 5: Commit**

```bash
git add backend/
git commit -m "feat: add FixtureTools with getUpcomingFixtures()"
```

---

### Task 7: ChatService + request DTO

**Files:**
- Create: `backend/src/main/java/fi/karivanttinen/fplassistant/chat/ChatRequest.java`
- Create: `backend/src/main/java/fi/karivanttinen/fplassistant/chat/ChatService.java`
- Create: `backend/src/test/java/fi/karivanttinen/fplassistant/chat/ChatServiceTest.java`

**Interfaces:**
- Consumes: `ChatClient` bean (Task 3), `GameweekTools` (Task 4), `PlayerTools` (Task 5), `FixtureTools` (Task 6)
- Produces: `ChatService` bean with `stream(ChatRequest)` returning `Flux<String>`; `ChatController` (Task 8) uses this

**Step 1: Create `ChatRequest.java`**

```java
package fi.karivanttinen.fplassistant.chat;

import java.util.List;

public record ChatRequest(List<MessageDto> messages) {

    public record MessageDto(String role, String content) {}
}
```

**Step 2: Write the failing test for ChatService**

```java
package fi.karivanttinen.fplassistant.chat;

import fi.karivanttinen.fplassistant.tools.FixtureTools;
import fi.karivanttinen.fplassistant.tools.GameweekTools;
import fi.karivanttinen.fplassistant.tools.PlayerTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    ChatClient chatClient;

    @Mock
    ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    ChatClient.StreamResponseSpec streamSpec;

    @Mock
    GameweekTools gameweekTools;

    @Mock
    PlayerTools playerTools;

    @Mock
    FixtureTools fixtureTools;

    @InjectMocks
    ChatService chatService;

    @Test
    void streamReturnsFluxFromChatClient() {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.messages(any())).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.just("Hello", " FPL", " fan"));

        ChatRequest request = new ChatRequest(
            List.of(new ChatRequest.MessageDto("user", "Who should I captain?")));

        Flux<String> result = chatService.stream(request);

        StepVerifier.create(result)
            .expectNext("Hello", " FPL", " fan")
            .verifyComplete();
    }
}
```

**Step 3: Run to verify failure**

```bash
./gradlew test --tests "fi.karivanttinen.fplassistant.chat.ChatServiceTest"
```

Expected: FAIL

**Step 4: Implement `ChatService.java`**

```java
package fi.karivanttinen.fplassistant.chat;

import fi.karivanttinen.fplassistant.tools.FixtureTools;
import fi.karivanttinen.fplassistant.tools.GameweekTools;
import fi.karivanttinen.fplassistant.tools.PlayerTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

@Service
public class ChatService {

    private final ChatClient chatClient;
    private final GameweekTools gameweekTools;
    private final PlayerTools playerTools;
    private final FixtureTools fixtureTools;

    public ChatService(ChatClient chatClient, GameweekTools gameweekTools,
                       PlayerTools playerTools, FixtureTools fixtureTools) {
        this.chatClient = chatClient;
        this.gameweekTools = gameweekTools;
        this.playerTools = playerTools;
        this.fixtureTools = fixtureTools;
    }

    public Flux<String> stream(ChatRequest request) {
        List<Message> history = request.messages().stream()
                .map(m -> (Message) switch (m.role()) {
                    case "user" -> new UserMessage(m.content());
                    default -> new AssistantMessage(m.content());
                })
                .toList();

        return chatClient.prompt()
                .messages(history)
                .tools(gameweekTools, playerTools, fixtureTools)
                .stream()
                .content();
    }
}
```

**Step 5: Run test**

```bash
./gradlew test --tests "fi.karivanttinen.fplassistant.chat.ChatServiceTest"
```

Expected: `BUILD SUCCESSFUL`, 1 test passed

**Step 6: Commit**

```bash
git add backend/
git commit -m "feat: add ChatService streaming with all three tools"
```

---

### Task 8: ChatController + CORS

**Files:**
- Create: `backend/src/main/java/fi/karivanttinen/fplassistant/chat/ChatController.java`
- Create: `backend/src/main/java/fi/karivanttinen/fplassistant/config/WebConfig.java`
- Create: `backend/src/test/java/fi/karivanttinen/fplassistant/chat/ChatControllerTest.java`

**Interfaces:**
- Consumes: `ChatService.stream(ChatRequest)` returning `Flux<String>`
- Produces: `POST /api/chat` endpoint returning `text/event-stream`

**Step 1: Write the failing controller test**

Plain `MockMvc` cannot assert a streaming `Flux` body — async returns before content is written. Use `MockMvcWebTestClient`, which drives the async dispatch and collects the SSE stream.

```java
package fi.karivanttinen.fplassistant.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.client.MockMvcWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ChatService chatService;

    WebTestClient client;

    @BeforeEach
    void setup() {
        client = MockMvcWebTestClient.bindTo(mockMvc).build();
    }

    @Test
    void postChatStreamsTokens() {
        when(chatService.stream(any())).thenReturn(Flux.just("Hello", " world"));

        List<String> tokens = client.post().uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"messages":[{"role":"user","content":"test"}]}
                        """)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .block();

        org.assertj.core.api.Assertions.assertThat(tokens).containsExactly("Hello", " world");
    }
}
```

**Step 2: Run to verify failure**

```bash
./gradlew test --tests "fi.karivanttinen.fplassistant.chat.ChatControllerTest"
```

Expected: FAIL

**Step 3: Implement `ChatController.java`**

```java
package fi.karivanttinen.fplassistant.chat;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody ChatRequest request) {
        return chatService.stream(request);
    }
}
```

**Step 4: Create `WebConfig.java`**

```java
package fi.karivanttinen.fplassistant.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${cors.allowed-origins:http://localhost:5173}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.split(","))
                .allowedMethods("POST", "GET", "OPTIONS")
                .allowedHeaders("*");
    }
}
```

Add to `application.yml`:
```yaml
cors:
  allowed-origins: ${CORS_ORIGINS:http://localhost:5173}
```

**Step 5: Run all backend tests**

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`, all tests pass

**Step 6: Smoke test the running server**

```bash
set -a; source .env.test; set +a
./gradlew bootRun &
sleep 5
curl -N -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"What gameweek is it?"}]}'
```

Expected: streaming SSE tokens in terminal

```bash
kill %1  # stop bootRun
```

**Step 7: Commit**

```bash
git add backend/
git commit -m "feat: add ChatController SSE endpoint and CORS config"
```

---

### Task 9: Frontend scaffold

**Files:**
- Create: `frontend/package.json`
- Create: `frontend/vite.config.ts`
- Create: `frontend/tsconfig.json`
- Create: `frontend/index.html`
- Create: `frontend/src/main.tsx`
- Create: `frontend/src/App.tsx`
- Create: `frontend/src/types.ts`

**Interfaces:**
- Produces: running Vite dev server at `http://localhost:5173` with Mantine provider

**Step 1: Create `frontend/package.json`**

```json
{
  "name": "fpl-assistant-frontend",
  "version": "0.1.0",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "preview": "vite preview"
  },
  "dependencies": {
    "@mantine/core": "^9.0.0",
    "@mantine/hooks": "^9.0.0",
    "react": "^18.3.1",
    "react-dom": "^18.3.1",
    "react-markdown": "^9.0.0"
  },
  "devDependencies": {
    "@types/react": "^18.3.12",
    "@types/react-dom": "^18.3.1",
    "@vitejs/plugin-react": "^4.3.4",
    "typescript": "^5.6.3",
    "vite": "^8.1.0"
  }
}
```

**Step 2: Create `frontend/vite.config.ts`**

```typescript
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
```

**Step 3: Create `frontend/tsconfig.json`**

```json
{
  "compilerOptions": {
    "target": "ES2020",
    "useDefineForClassFields": true,
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "skipLibCheck": true,
    "moduleResolution": "bundler",
    "allowImportingTsExtensions": true,
    "isolatedModules": true,
    "moduleDetection": "force",
    "noEmit": true,
    "jsx": "react-jsx",
    "strict": true
  },
  "include": ["src"]
}
```

**Step 4: Create `frontend/index.html`**

```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>FPL Assistant</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

**Step 5: Create `frontend/src/types.ts`**

```typescript
export interface Message {
  role: 'user' | 'assistant'
  content: string
}

export interface ChatRequest {
  messages: Message[]
}
```

**Step 6: Create `frontend/src/main.tsx`**

```typescript
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import '@mantine/core/styles.css'
import App from './App'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <MantineProvider defaultColorScheme="auto">
      <App />
    </MantineProvider>
  </StrictMode>,
)
```

**Step 7: Create `frontend/src/App.tsx`**

```typescript
import ChatView from './components/ChatView/ChatView'

export default function App() {
  return <ChatView />
}
```

**Step 8: Install dependencies and verify dev server starts**

```bash
cd frontend
npm install
npm run dev
```

Expected: dev server at `http://localhost:5173`, no errors in terminal. Open in browser — blank page is fine for now.

**Step 9: Commit**

```bash
cd ..
git add frontend/
git commit -m "feat: scaffold Vite + React + Mantine v9 frontend"
```

---

### Task 10: MessageInput component

**Files:**
- Create: `frontend/src/components/MessageInput/MessageInput.tsx`
- Create: `frontend/src/components/MessageInput/MessageInput.module.css`

**Interfaces:**
- Consumes: `onSubmit: (message: string) => void`, `disabled: boolean`
- Produces: `MessageInput` component; `ChatView` (Task 12) uses this

**Step 1: Create `MessageInput.module.css`**

```css
.wrapper {
  display: flex;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid var(--mantine-color-default-border);
  background: var(--mantine-color-body);
}

.textarea {
  flex: 1;
}
```

**Step 2: Create `MessageInput.tsx`**

```typescript
import { useState, KeyboardEvent } from 'react'
import { Textarea, ActionIcon } from '@mantine/core'
import classes from './MessageInput.module.css'

interface Props {
  onSubmit: (message: string) => void
  disabled: boolean
}

export default function MessageInput({ onSubmit, disabled }: Props) {
  const [value, setValue] = useState('')

  function handleSubmit() {
    const trimmed = value.trim()
    if (!trimmed || disabled) return
    onSubmit(trimmed)
    setValue('')
  }

  function handleKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSubmit()
    }
  }

  return (
    <div className={classes.wrapper}>
      <Textarea
        className={classes.textarea}
        placeholder="Ask about FPL players, fixtures, strategy..."
        value={value}
        onChange={e => setValue(e.currentTarget.value)}
        onKeyDown={handleKeyDown}
        disabled={disabled}
        autosize
        minRows={1}
        maxRows={4}
      />
      <ActionIcon
        size="lg"
        variant="filled"
        onClick={handleSubmit}
        disabled={disabled || !value.trim()}
        aria-label="Send"
      >
        ↑
      </ActionIcon>
    </div>
  )
}
```

**Step 3: Verify component renders**

In `App.tsx`, temporarily render:
```typescript
import MessageInput from './components/MessageInput/MessageInput'
export default function App() {
  return <MessageInput onSubmit={console.log} disabled={false} />
}
```

Run `npm run dev` and confirm textarea + button appear, submit works.

**Step 4: Revert `App.tsx` to original**

```typescript
import ChatView from './components/ChatView/ChatView'
export default function App() {
  return <ChatView />
}
```

**Step 5: Commit**

```bash
git add frontend/
git commit -m "feat: add MessageInput component"
```

---

### Task 11: Message display components

**Files:**
- Create: `frontend/src/components/AssistantMessage/AssistantMessage.tsx`
- Create: `frontend/src/components/AssistantMessage/AssistantMessage.module.css`
- Create: `frontend/src/components/MessageThread/MessageThread.tsx`
- Create: `frontend/src/components/MessageThread/MessageThread.module.css`

**Interfaces:**
- Consumes: `Message[]` from `types.ts`, `streamingContent: string | null`
- Produces: `MessageThread` component; `ChatView` (Task 12) uses this

**Step 1: Create `AssistantMessage.module.css`**

```css
.bubble {
  background: light-dark(var(--mantine-color-gray-1), var(--mantine-color-dark-6));
  border-radius: 8px;
  padding: 12px 16px;
  max-width: 85%;
  align-self: flex-start;
  line-height: 1.6;
}

.bubble p {
  margin: 0 0 8px 0;
}

.bubble p:last-child {
  margin-bottom: 0;
}

.cursor::after {
  content: '▋';
  animation: blink 1s step-end infinite;
}

@keyframes blink {
  50% { opacity: 0; }
}
```

**Step 2: Create `AssistantMessage.tsx`**

```typescript
import Markdown from 'react-markdown'
import classes from './AssistantMessage.module.css'

interface Props {
  content: string
  isStreaming?: boolean
}

export default function AssistantMessage({ content, isStreaming }: Props) {
  return (
    <div className={`${classes.bubble} ${isStreaming ? classes.cursor : ''}`}>
      <Markdown>{content}</Markdown>
    </div>
  )
}
```

**Step 3: Create `MessageThread.module.css`**

```css
.thread {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 16px;
}

.userBubble {
  background: var(--mantine-color-blue-6);
  color: white;
  border-radius: 8px;
  padding: 10px 14px;
  max-width: 75%;
  align-self: flex-end;
  word-break: break-word;
}
```

**Step 4: Create `MessageThread.tsx`**

```typescript
import { useEffect, useRef } from 'react'
import { Message } from '../../types'
import AssistantMessage from '../AssistantMessage/AssistantMessage'
import classes from './MessageThread.module.css'

interface Props {
  messages: Message[]
  streamingContent: string | null
}

export default function MessageThread({ messages, streamingContent }: Props) {
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, streamingContent])

  return (
    <div className={classes.thread}>
      {messages.map((msg, i) =>
        msg.role === 'user' ? (
          <div key={i} className={classes.userBubble}>{msg.content}</div>
        ) : (
          <AssistantMessage key={i} content={msg.content} />
        )
      )}
      {streamingContent !== null && (
        <AssistantMessage content={streamingContent} isStreaming />
      )}
      <div ref={bottomRef} />
    </div>
  )
}
```

**Step 5: Commit**

```bash
git add frontend/
git commit -m "feat: add MessageThread and AssistantMessage components with markdown"
```

---

### Task 12: useChat hook (SSE streaming)

**Files:**
- Create: `frontend/src/hooks/useChat.ts`

**Interfaces:**
- Produces: `useChat()` hook returning `{ messages, streamingContent, isStreaming, sendMessage }`; `ChatView` uses this

**Step 1: Create `frontend/src/hooks/useChat.ts`**

```typescript
import { useState, useCallback } from 'react'
import { Message } from '../types'

interface UseChatReturn {
  messages: Message[]
  streamingContent: string | null
  isStreaming: boolean
  sendMessage: (content: string) => Promise<void>
}

export function useChat(): UseChatReturn {
  const [messages, setMessages] = useState<Message[]>([])
  const [streamingContent, setStreamingContent] = useState<string | null>(null)
  const [isStreaming, setIsStreaming] = useState(false)

  const sendMessage = useCallback(async (content: string) => {
    const userMessage: Message = { role: 'user', content }
    const nextMessages = [...messages, userMessage]
    setMessages(nextMessages)
    setStreamingContent('')
    setIsStreaming(true)

    try {
      const response = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ messages: nextMessages }),
      })

      if (!response.ok || !response.body) {
        throw new Error(`Request failed: ${response.status}`)
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let accumulated = ''
      let buffer = ''

      // SSE: events separated by blank line. Within one event, multiple
      // `data:` lines rejoin with `\n` (a token containing newlines arrives
      // split across lines). Buffer across reads so events split mid-chunk
      // aren't lost.
      const flushEvent = (event: string) => {
        const dataLines = event
          .split('\n')
          .filter(l => l.startsWith('data:'))
          .map(l => l.slice(5))
        if (dataLines.length === 0) return
        accumulated += dataLines.join('\n')
        setStreamingContent(accumulated)
      }

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        let sep: number
        while ((sep = buffer.indexOf('\n\n')) !== -1) {
          flushEvent(buffer.slice(0, sep))
          buffer = buffer.slice(sep + 2)
        }
      }
      if (buffer.trim()) flushEvent(buffer)

      setMessages(prev => [...prev, { role: 'assistant', content: accumulated }])
    } catch (err) {
      setMessages(prev => [
        ...prev,
        { role: 'assistant', content: 'Sorry, something went wrong. Please try again.' },
      ])
    } finally {
      setStreamingContent(null)
      setIsStreaming(false)
    }
  }, [messages])

  return { messages, streamingContent, isStreaming, sendMessage }
}
```

**Step 2: Commit**

```bash
git add frontend/
git commit -m "feat: add useChat hook with SSE streaming"
```

---

### Task 13: ChatView assembly

**Files:**
- Create: `frontend/src/components/ChatView/ChatView.tsx`
- Create: `frontend/src/components/ChatView/ChatView.module.css`

**Interfaces:**
- Consumes: `useChat()` hook (Task 12), `MessageThread` (Task 11), `MessageInput` (Task 10)
- Produces: complete chat UI, wired end-to-end

**Step 1: Create `ChatView.module.css`**

```css
.root {
  display: flex;
  flex-direction: column;
  height: 100dvh;
  max-width: 800px;
  margin: 0 auto;
}

.header {
  padding: 16px;
  border-bottom: 1px solid var(--mantine-color-default-border);
  font-weight: 600;
  font-size: 1.1rem;
}

.empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--mantine-color-dimmed);
  font-size: 0.95rem;
  text-align: center;
  padding: 32px;
}
```

**Step 2: Create `ChatView.tsx`**

```typescript
import { useChat } from '../../hooks/useChat'
import MessageThread from '../MessageThread/MessageThread'
import MessageInput from '../MessageInput/MessageInput'
import classes from './ChatView.module.css'

export default function ChatView() {
  const { messages, streamingContent, isStreaming, sendMessage } = useChat()

  return (
    <div className={classes.root}>
      <div className={classes.header}>⚽ FPL Assistant</div>
      {messages.length === 0 && streamingContent === null ? (
        <div className={classes.empty}>
          Ask me anything about Fantasy Premier League — players, fixtures, strategy, chips, transfers.
        </div>
      ) : (
        <MessageThread messages={messages} streamingContent={streamingContent} />
      )}
      <MessageInput onSubmit={sendMessage} disabled={isStreaming} />
    </div>
  )
}
```

**Step 3: Manual end-to-end test**

Start backend:
```bash
cd backend
set -a; source .env.test; set +a
./gradlew bootRun &
```

Start frontend dev server:
```bash
cd frontend
npm run dev
```

Open `http://localhost:5173`. Send these messages and verify:
1. "What gameweek is it?" — should call `getGameweekInfo` and return current GW info
2. "Show me Salah's stats" — should call `getPlayerStats("Salah")` and return stats
3. "What are Arsenal's next 5 fixtures?" — should call `getUpcomingFixtures("Arsenal", 5)`

Verify tokens stream in real-time (text appears progressively).

**Step 4: Build frontend production bundle**

```bash
cd frontend
npm run build
```

Expected: `dist/` created with `index.html` and assets. No TypeScript errors.

**Step 5: Commit**

```bash
cd ..
git add frontend/
git commit -m "feat: assemble ChatView, complete frontend end-to-end"
```

---

### Task 14: Dockerfile + Fly.io deployment

**Files:**
- Create: `Dockerfile` (repo root — multi-stage build)
- Create: `backend/fly.toml`
- Modify: `backend/build.gradle` (add processResources task to include frontend build)

**Interfaces:**
- Consumes: built frontend (`frontend/dist/`) copied into Spring Boot static resources
- Produces: single Fly.io app serving both API and frontend

**Step 1: Create `Dockerfile` at repo root**

```dockerfile
# Stage 1: Build frontend
FROM node:22-alpine AS frontend-build
WORKDIR /app/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# Stage 2: Build Spring Boot (includes frontend static files)
FROM eclipse-temurin:25-jdk AS backend-build
WORKDIR /app
COPY backend/ ./
COPY --from=frontend-build /app/frontend/dist ./src/main/resources/static/
RUN ./gradlew bootJar -x test --no-daemon

# Stage 3: Runtime
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=backend-build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**Step 2: Create `backend/fly.toml`**

```toml
app = "fpl-assistant"
primary_region = "mad"

[build]
  dockerfile = "../Dockerfile"
  dockerfile_path = "../Dockerfile"

[[vm]]
  memory = "512mb"
  cpus = 1

[http_service]
  internal_port = 8080
  force_https = true

  [[http_service.checks]]
    grace_period = "10s"
    interval = "15s"
    method = "GET"
    path = "/actuator/health"
    timeout = "5s"
```

Add Spring Boot Actuator for health checks. In `build.gradle`:
```groovy
implementation 'org.springframework.boot:spring-boot-starter-actuator'
```

In `application.yml`:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health
```

**Step 3: Build Docker image locally to verify**

```bash
docker build -t fpl-assistant:local .
docker run --rm -p 8080:8080 \
  -e DATABASE_URL="jdbc:postgresql://<host>:6543/postgres?sslmode=require" \
  -e DB_USER="postgres" \
  -e DB_PASSWORD="<your-db-password>" \
  -e ANTHROPIC_API_KEY="sk-ant-..." \
  -e SPRING_AI_PROVIDER="anthropic" \
  fpl-assistant:local
```

Open `http://localhost:8080` — should serve the React app. Test the chat. Kill with `Ctrl+C`.

**Step 4: Create Fly.io app and set secrets**

```bash
cd backend
fly launch --no-deploy --name fpl-assistant --region mad
fly secrets set DATABASE_URL="jdbc:postgresql://<host>:6543/postgres?sslmode=require"
fly secrets set DB_USER="postgres"
fly secrets set DB_PASSWORD="<your-db-password>"
fly secrets set ANTHROPIC_API_KEY="sk-ant-..."
fly secrets set OPENAI_API_KEY="sk-..."
fly secrets set SPRING_AI_PROVIDER="anthropic"
fly secrets set CORS_ORIGINS="https://fpl-assistant.fly.dev"
```

**Step 5: Deploy**

```bash
fly deploy --dockerfile ../Dockerfile
```

Expected: deploy succeeds. Visit `https://fpl-assistant.fly.dev` — chat UI loads, questions answered.

**Step 6: Commit**

```bash
cd ..
git add Dockerfile backend/fly.toml backend/build.gradle backend/src/
git commit -m "feat: add Dockerfile and Fly.io deployment config"
```

---

## Self-Review

### Spec coverage check

| Spec requirement | Task |
|---|---|
| Spring Boot 4.1.x, Java 25, Spring AI 2.0 | Task 1 |
| System prompt with FPL rules | Task 3 |
| `getGameweekInfo` tool | Task 4 |
| `getPlayerStats` tool | Task 5 |
| `getUpcomingFixtures` tool | Task 6 |
| ChatClient with configurable LLM provider | Task 3, 7 |
| SSE streaming via `POST /api/chat` | Task 8 |
| Vite 8.1, React 18, Mantine v9 | Task 9 |
| SSE via `fetch` + ReadableStream | Task 12 |
| Stateless conversation (client sends history) | Task 7 |
| CORS config | Task 8 |
| Single Fly.io app (backend + frontend) | Task 14 |
| Season filter: `WHERE season_id = (SELECT id FROM season WHERE is_current = true)` | Tasks 4, 5, 6 |

All Phase 1 spec requirements covered. Phase 2-5 requirements are out of scope for this plan.

### Placeholder scan

No TBD, TODO, or incomplete sections found.

### Type consistency

- `Message` (types.ts): `{ role: 'user' | 'assistant', content: string }` — used consistently in `useChat`, `MessageThread`, `ChatRequest`
- `ChatRequest` (Java): `record ChatRequest(List<MessageDto> messages)` / `record MessageDto(String role, String content)` — matches frontend `ChatRequest`
- `ChatService.stream(ChatRequest)` → `Flux<String>` — used in `ChatController` and tested in `ChatServiceTest`
- `FixtureTools.getUpcomingFixtures(String teamName, int nextN)` — consistent across implementation and test
- `PlayerTools.getPlayerStats(String playerName)` — consistent across implementation and test
- `GameweekTools.getGameweekInfo()` — consistent across implementation and test
