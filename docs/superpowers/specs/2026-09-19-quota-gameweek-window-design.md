# Quota window: one gameweek (KAR-61)

Design for replacing the rolling seven-day message quota with a window that begins and ends at
FPL deadlines.

## The problem

`UsageService` counts a user's chat turns over `created_at > now() - interval '7 days'`. Nothing
resets: each message frees up individually, seven days after it was sent. The UI's "12 of 20
messages used this week" therefore describes a window with no beginning and no end, and a user
who has not opened the app since yesterday still finds yesterday's messages charged against them.

The allowance should belong to a round of the game. The period between two deadlines is the
period a user's questions are about, and it is the unit FPL itself plans in.

## The window

One rule, evaluated against the deadlines of **every captured season** in `fpl_gameweek` read as a
single timeline ordered by `deadline_time`:

```
prev = the last deadline at or before asOf
next = the first deadline strictly after asOf
window = [prev, next)
```

Strictly after, matching `HorizonFixtures.derive`: at the deadline instant the round is already
locked, so that instant opens the new window rather than closing it late.

The window's **kind** follows from its two ends:

| `prev` | `next` | same season | kind | limit |
|---|---|---|---|---|
| present | present | yes | `GAMEWEEK` | 20 |
| anything else | | | `OFF_SEASON` | 100 |

`OFF_SEASON` therefore covers three shapes without needing a branch for each: the gap between one
season's GW38 deadline and the next season's GW1 deadline, the period before any deadline has ever
been captured, and the period after the last captured deadline when the new season's schedule has
not been published yet. Its ends may be unbounded — `prev` null means "count everything before
`next`", `next` null means "count everything after `prev`" — and it is **one window for the whole
break**: it does not reset inside itself.

```
… GW37 ──┬── GW38 ──────────── off-season ──────────── GW1 ──┬── GW2 …
 season N │                          season N+1               │
       20 msg                      100 msg                 20 msg
```

Reading every captured season rather than only the latest is what makes the changeover fall out of
the same rule. `ScheduleSyncService.deleteAllExcept` scopes its delete to the season being written
(`WHERE season = ? AND NOT (id = ANY(?))`), so earlier seasons' rows are retained and the timeline
is there to read. Were the window read from the latest season alone, the preseason of season N+1
would have no `prev` at all and would count the whole of season N against a user.

## Freshness is deliberately not required

This is the design's one surprising rule, and it reverses what a reader would expect from the
ranking side, so it is stated here rather than discovered later.

`HorizonFixtures` refuses a stale capture, because a fixture that has been moved makes a horizon
wrong. The schedule sync legitimately refuses to write across the summer — a finished season, then
the July guard — so between May and August the capture is always stale. Requiring freshness here
would mean the off-season window, the one this ticket exists to give 100 messages, could never
occur: every off-season request would fall back to the rolling seven days instead.

Deadlines also do not change retroactively. A stale capture that has missed a rescheduled deadline
moves a quota boundary by hours; it does not make the boundary meaningless the way a moved fixture
makes a horizon meaningless. **The window is resolved without a freshness check, on purpose. Do not
"fix" it onto `ScheduleFreshnessCheck` the way `HorizonFixtures` uses it.**

What *is* required is that the latest season's events are a coherent set: a single `captured_at`
and ids exactly 1..38. A half-written season would put a boundary in the wrong place, and unlike
staleness that is not a small error. Older seasons are not checked — the only way a missing old
deadline can matter is by moving an off-season window's start earlier, which lengthens a window
without changing its limit, and no request can fall inside a completed season anyway.

The 380-fixture half of `ScheduleSource.assess` is irrelevant to a quota. The event-side checks are
extracted into `assessEvents`, which `assess` then calls, so "the events are a clean 1..38" keeps
one definition rather than two.

## Fallback

When the window cannot be resolved — no capture at all, the latest season's events are not a clean
1..38, or the read throws `DataAccessException` — the quota falls back to **exactly today's rule**:
the rolling seven days at the gameweek limit, computed by the code that does it today, including
the `OFFSET (used - limit)` helper in `nextFreeAt`.

(The ticket said that helper disappears. It does not: keeping the fallback as the unchanged,
already-tested path is what makes it trustworthy, and the alternative — a fixed window with no
reset instant — would leave a blocked user with no `Retry-After` at all.)

Not fail-open: a broken capture must not uncap spend. Not fail-closed: one missing capture must not
block every user. The response says the window is a fallback, because the UI must not name a
deadline it cannot see.

## Limits

```yaml
fpl:
  quota:
    free-messages-per-gameweek: ${QUOTA_FREE_MESSAGES_PER_GAMEWEEK:20}
    free-messages-off-season: ${QUOTA_FREE_MESSAGES_OFF_SEASON:100}
```

`fpl.quota.free-messages-per-week` / `QUOTA_FREE_MESSAGES_PER_WEEK` are renamed, not kept as
aliases: the old name would be a lie about the window. If `QUOTA_FREE_MESSAGES_PER_WEEK` is set as
a Fly secret it must be re-set under the new name during the cutover — otherwise the limit silently
reverts to the property's default. Check with `fly secrets list` before deploying.

`fpl_user_profile.weekly_message_limit` keeps its name. It is a per-user override that applies to
whichever window is in force, and renaming it buys a migration and nothing else. The existing
sentinels are unchanged: a negative value means unlimited, `0` means suspended, and **an override
replaces the window's own limit whatever the window's kind** — an account overridden to 50 gets 50
in a gameweek window and 50 in the off-season.

## Components

### `QuotaWindow` (new, `user/`)

```java
public record QuotaWindow(Kind kind, Instant from, Instant until, int limit,
                          Integer gameweek, String season) {
    public enum Kind { GAMEWEEK, OFF_SEASON, FALLBACK }
}
```

`from`/`until` are nullable and mean unbounded. `gameweek` is `next`'s id — the round the window is
the run-up to — and is present for `GAMEWEEK` only. A `FALLBACK` window carries neither end nor a
gameweek nor a season: its bounds live in the rolling-window SQL it delegates to, and duplicating
them here would invite someone to read them instead.

A `QuotaWindows` component resolves it: reads the deadline list, derives the window for a given
`asOf`. The deadline list is cached app-wide for five minutes; the **window is always computed per
call** from a fresh `asOf`, so a deadline passing is never hidden by the cache. The read is 38–76
rows, but it runs inside the advisory-locked transaction in `claimChatTurn`, which is reason enough
not to repeat it per request.

### `ScheduleSource` (extended)

```java
public record Deadline(String season, int gameweek, Instant deadline) {}
public List<Deadline> deadlines();   // all seasons, ORDER BY deadline_time
```

Ordered by `deadline_time`, not by id: across two seasons the id order is not the time order.
`EventRow` is not reused because it carries no season, which is exactly the field the changeover
test needs.

### `UsageService`

`quotaOf` resolves the window first, then counts:

```sql
SELECT count(*) FROM fpl_usage_event
WHERE user_id = ? AND event_type = 'chat' AND created_at >= ? AND created_at < ?
```

with either bound dropped when it is null. `Kind.FALLBACK` takes today's path instead, unchanged.

`Quota` gains the window and one field:

```java
public record Quota(Plan plan, int used, Integer limit,
                    Instant nextFreeAt, Instant resetsAt, QuotaWindow window)
```

Two timestamps, because they answer two questions and only coincide sometimes:

- `resetsAt` — when the count returns to zero. The window's end. Null for `FALLBACK` (nothing
  resets, which is the complaint that started this) and for an unbounded `OFF_SEASON`.
- `nextFreeAt` — when the caller may send again, present only when exhausted. Equal to `resetsAt`
  in a fixed window; the aging instant in the fallback. This is what `Retry-After` is computed
  from, exactly as today.

### `UsageController`

`UsageResponse` gains a nested `window` object and `resetsAt`, keeping `NON_NULL`:

```json
{ "plan": "FREE", "remainingPercent": 40, "unlimited": false, "used": 12, "limit": 20,
  "window": { "kind": "GAMEWEEK", "gameweek": 13, "season": "2025/26" },
  "resetsAt": "2026-09-19T10:30:00Z" }
```

`remainingPercent`, the suspended-account silence and the free/paid split are untouched.

### `QuotaExceededException`

Its three messages stop saying "this week's messages":

- suspended → "This account cannot send messages." (unchanged)
- `GAMEWEEK` → "You have used all 20 of your messages for gameweek 13."
- `OFF_SEASON` → "You have used all 100 of your off-season messages."
- `FALLBACK` → "You have used all 20 of this week's messages."

### Frontend

`UsagePage` names the **deadline**, never "this gameweek". The vocabulary is already taken: during
the window `[GW12 deadline, GW13 deadline)` the matches being played are GW12's, and
`GameweekTools` calls GW12 "CURRENT", while the window is the run-up to GW13. A page that said
"this gameweek" would be read two ways. A deadline is one instant and cannot be.

| kind | lede | note |
|---|---|---|
| `GAMEWEEK` | "of your messages for gameweek 13 remaining" | "12 of 20 messages used. Resets at the gameweek 13 deadline on …" |
| `OFF_SEASON` | "of your off-season messages remaining" | "12 of 100 messages used. Resets when the new season's first deadline is published." (or names it when `resetsAt` is known) |
| `FALLBACK` | "of this week's messages remaining" | "12 of 20 messages used in the last seven days." |

`types.ts` gains the `window` shape and `resetsAt`. The `aria-label` follows the lede.

## Cutover

No data migration. On deploy mid-gameweek the count restarts from the last deadline, so most users'
usage drops; nobody is charged for anything they were not charged for before.

## Testing

Backend, all seeding their own `fpl_gameweek` rows in the `_test` tables:

- `QuotaWindowTest` — mid-season; exactly at a deadline (the new window opens); before any captured
  deadline; after the latest season's last deadline; the changeover gap between two seasons;
  incomplete 1..38 → fallback; no capture → fallback; **a stale capture still yields `GAMEWEEK`**,
  pinning the deliberate rule above.
- `UsageServiceTest` — counting bounded at both ends; a message just before `from` is not counted;
  a per-user override replaces the window's limit in both kinds; the unlimited sentinel; the
  fallback path reproduces today's behaviour.
- `UsageControllerTest` — the response for each kind, including the absent fields.
- `ChatControllerTest` — `Retry-After` points at the window's end in a gameweek window.

Frontend:

- `UsagePage.test.ts` (new — the component has no test today) — the three wordings, the blocked
  state, the loading and error states.
- `chatTransport.test.ts` — unchanged behaviour, updated body text.
