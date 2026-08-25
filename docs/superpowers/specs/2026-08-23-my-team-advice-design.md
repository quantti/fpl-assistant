# FPL Assistant — My-Team-Aware Advice Design Spec

**Status:** Draft — awaiting review.

**Relationship to existing work:** Builds on Phase 5a's `fpl_user_profile.fpl_team_id`
(optional, validated against FPL). Adds a second documented exception to the
"external APIs only at sync time" rule: user-specific FPL data cannot be pre-synced
because it is unique per user and per gameweek.

---

## Idea

Today the assistant answers "how can I improve my team?" with generic advice, because
it cannot see the user's actual squad. The system prompt explicitly tells the model to
give general advice unless it can see the squad.

This feature closes that gap. When a user has stored an FPL team id, the assistant can
fetch the user's current squad, bank balance, team value, and player-level stats, then
give personalized transfer, captain, chip, and structure recommendations.

## What already exists

- `fpl_user_profile.fpl_team_id` — stored and validated against FPL's entry endpoint
  (`FplEntryLookup`) when the user sets it.
- Existing stats tools: `getPlayerStats`, `getPlayerExpectedStats`, `getPriceHistory`,
  `getPlayerAvailability`, `getFixtureDifficulty`, `getUpcomingFixtures`.
- Existing advice-oriented tools: `getDifferentialPicks`, `getCaptainCandidates`.
- `SeasonResolver` / `GameweekTools` for resolving the current season and gameweek.

## What this adds

A single new tool, `getMyTeam()`, that returns everything the model needs to reason
about the user's squad:

- **Entry summary:** team name, overall rank, total points, current gameweek, bank
  balance, team value, total value (value + bank).
- **Squad:** starting XI and bench, with player identity, position, captain/vice
  status, purchase/selling price, and key stats.
- **Per-player enrichment:** form, total points, points per game, selected-by %,
  upcoming fixture difficulty (next 5 average), availability/news, and today's price.
- **Chip state:** active chip for the current gameweek (if any) and chips still
  available.

## Data sources

| Source | Endpoint / table | Why |
|---|---|---|
| Stored team id | `fpl_user_profile.fpl_team_id` | The user's own FPL entry id. |
| Entry summary | `GET /api/entry/{entry_id}/` | Bank, value, rank, current event. Fetched at chat time; user-specific. |
| Current picks | `GET /api/entry/{entry_id}/event/{event_id}/picks/` | Squad, captain, vice, selling prices, active chip, entry_history (GW bank/value/points). Fetched at chat time. |
| Player metadata & stats | Existing tapas tables + `fpl_daily_price` | Season-long stats, form, price, availability, fixtures. Already in Supabase. |

## API surface

| Route / tool | Change |
|---|---|
| `GET /api/profile` | Unchanged — still returns the stored `fplTeamId`. |
| `PUT /api/profile/fpl-team-id` | Unchanged — still validates and stores the id. |
| `MyTeamTools.getMyTeam()` | New `@Tool`. Returns the user's squad and entry snapshot. No mutation. |

## Tool contract

```json
{
  "entry": {
    "id": 1234567,
    "name": "Team Name",
    "playerName": "First Last",
    "overallRank": 420000,
    "totalPoints": 185,
    "currentEvent": 5,
    "bank": 12,
    "teamValue": 1003,
    "totalValue": 1015,
    "activeChip": null,
    "chipsAvailable": ["wildcard", "freehit", "bench_boost", "triple_captain"]
  },
  "squad": [
    {
      "elementId": 302,
      "webName": "Salah",
      "teamShortName": "LIV",
      "elementType": "MID",
      "position": 1,
      "positionLabel": "starting",
      "isCaptain": true,
      "isViceCaptain": false,
      "multiplier": 2,
      "purchasePrice": 125,
      "sellingPrice": 126,
      "nowCost": 126,
      "totalPoints": 45,
      "form": "8.5",
      "pointsPerGame": "9.0",
      "selectedByPercent": "42.1",
      "availability": null,
      "nextFiveFdr": 3.2,
      "transfersInEvent": 120000,
      "transfersOutEvent": 5000
    }
  ]
}
```

Prices and bank are in 0.1m units (`12` = £1.2m, `125` = £12.5m), consistent with the
existing FPL data model.

## What the model is told

A new section in `system-prompt.st`:

> If the user asks about improving their team, transfers, captain choice, chip use, or
> squad structure, and they have stored an FPL team id, call `getMyTeam()` first. Compare
> their squad to the available tools (`getPlayerStats`, `getDifferentialPicks`,
> `getCaptainCandidates`, `getFixtureDifficulty`, `getPlayerExpectedStats`) and give
> advice that factors in what they already own, their bank, and upcoming fixtures. If no
> team id is stored, tell them how to set it in their profile instead of giving generic
> advice that ignores their squad.
>
> If the user asks about improving their position in a specific mini-league, first call
> `getMyMiniLeagues()` to list the leagues. Once the user names one (or if they already
> named it), call `getMiniLeagueOpponents(leagueId, focus)` to see the teams you need:
> `focus="nearby"` for opponents ranked close to the user (default), `focus="top10"` or
> `focus="top50"` to analyse the league leaders, or `focus="entry:12345"` for one specific
> team. Compare their squads to the user's own team and suggest moves that gain ground in
> that league. Do not fetch mini-league data unless the user brings up a mini-league.

Also remove or update the existing sentence "If you can see the user's squad, factor in
what they already own; otherwise give general advice — ask about their team only when it
would change the answer." because the model can now see the squad conditionally.

## Mini-league context

The user may want advice shaped by their standing in a specific classic mini-league
rather than by overall rank. Two additional tools support this without changing the core
`getMyTeam` flow.

### Data sources

| Source | Endpoint | Why |
|---|---|---|
| User's mini-leagues | `GET /api/entry/{entry_id}/` | The `leagues.classic` array lists every classic league the user belongs to, with current rank and league size. |
| League standings | `GET /api/leagues-classic/{league_id}/standings/` | Rankings and total/event points for every member; supports pagination. |
| Opponent squads | `GET /api/entry/{entry_id}/event/{event_id}/picks/` | Reuses the same picks client as `getMyTeam`. Fetched only for the opponents the user wants to analyse. |

### Tool contract

`getMyMiniLeagues()` returns the user's classic leagues:

```json
{
  "miniLeagues": [
    {
      "id": 12345,
      "name": "Office League",
      "shortName": "OFF",
      "rank": 5,
      "lastRank": 6,
      "totalEntries": 20,
      "totalPoints": 185
    }
  ]
}
```

`getMiniLeagueOpponents(leagueId, focus)` returns a configurable slice of the league:

```json
{
  "league": { "id": 12345, "name": "Office League" },
  "userRank": 5,
  "focus": "top10",
  "opponents": [
    {
      "rank": 1,
      "entryId": 7654321,
      "entryName": "League Leader",
      "playerName": "...",
      "totalPoints": 210,
      "eventPoints": 55,
      "rankChange": 0,
      "squad": [ { ...SquadPlayer... } ]
    }
  ]
}
```

The `focus` string is interpreted by the service:
- `"nearby"` (default) — opponents ranked close to the user, e.g. ±5 positions.
- `"topN"` — the top N teams in the league, e.g. `"top10"` or `"top50"`.
- `"entry:ENTRY_ID"` — one specific team by its FPL entry id.

### Scope limits

- Only **classic** mini-leagues are supported in this phase. Head-to-head leagues behave
  differently and are out of scope.
- The service fetches only the standings pages and opponent pick requests needed to
  satisfy the `focus`. A `top10` request needs one standings page; a `nearby` request needs
  the page containing the user plus adjacent entries.
- Squads are fetched from the same current-gameweek picks endpoint as `getMyTeam`, so the
  same season/gameweek resolution and caching apply.
- The `focus` is intentionally a single flexible string rather than multiple separate tools,
  so the prompt does not have to branch into many special cases.

## Security and rate

- The FPL calls are per-user and happen only when the model invokes the tool.
- A short in-memory cache (e.g., Caffeine, 5 minutes, keyed by user id) avoids repeated
  FPL requests during a single conversation.
- The existing `FplEntryLookup` distinguishes "team not found" from "FPL unavailable";
  the new fetch keeps the same distinction so a user is not told to fix a team id when
  FPL is down.

## Out of scope

- Live gameweek points (the `event/{event_id}/live/` endpoint). The squad is judged on
  season-long stats and fixtures; live points can be added later without changing the
  tool shape.
- Persisting picks to a new table. Chat-time fetch + cache is enough for advice.
- Frontend changes. The team id is already settable in profile; a dedicated "my team"
  page is a later UX improvement.
- Automatic triggering. The model decides when to call the tool based on the prompt.

## What this hands to later phases

- Stripe billing is unaffected; unlimited users get the same tool.
- A future "my team" page can reuse `MyTeamService`.
- Live-points support later adds one more FPL call and one numeric field per pick.
