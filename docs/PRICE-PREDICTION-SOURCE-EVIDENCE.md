# KAR-76 — official price source evidence

Inspected on 18 October 2026, around 12:58–13:01 UTC. This is implementation evidence,
not a RAG strategy reference, a stable upstream API contract or production sync verification.
Only public HTTP GETs were made. No database, corpus or runtime configuration changed.

## Sources and reproducibility

- Official page: `https://fantasy.premierleague.com/en/price-changes`.
- Its frontend bundle: `https://fantasy.premierleague.com/assets/index-FewrWFrL.js`;
  SHA-256 `384a5ce268fffb57769e0f93f41da8df1cfec5261a3ec6e0b1f18ec638761383`.
- `https://fantasy.premierleague.com/api/bootstrap-static/`;
  response SHA-256 `54cea5576860738f3306814a21fe4d6fcb8b58f7ae38570f2b1d9746c5cca636`.
- `https://fantasy.premierleague.com/api/stats/price-changes/`;
  response SHA-256 `62a4644c812405ae66e5b6992093bffcaa532924c406f452f1b963adb681f819`.

Full responses were inspected locally, not added as permanent full-player fixtures. Future
tests should use small synthetic fixtures reproducing these shapes, never live API or player
values as their oracle. Bundle symbols below identify this inspected build only.

## Forecast and current price

The public bootstrap contained 667 elements, with these fields:

| Field | Evidence / first-delivery interpretation |
| --- | --- |
| `now_cost` | Current market price in integer tenths of a million; independent of forecast usability. |
| `price_change_percent` | Signed progress towards a threshold, displayed as a percentage, not probability. |
| `price_change_projections` | Array of `{offset, projected_percent, likelihood}`; every inspected array had offsets `[0,1,2]`. |
| `price_change_calibrating` | Boolean; the status chip shows calibration instead of a forecast label. |
| `price_change_locked_until` | Nullable timestamp; the chip prioritises this over calibration and likelihood. |
| `price_change_hourly_rate` | Present, but no references in this bundle; units/meaning not established. Do not use it. |

`OB` reads `price_change_projections[0].likelihood`. `AB` maps its result to the following
labels after the lock/calibration checks:

| Code | Official displayed label |
| --- | --- |
| `5` | Very likely to rise |
| `4` | Likely to rise |
| `-4` | Likely to drop |
| `-5` | Very likely to drop |
| Other | Unlikely to change |

All integers from -5 through 5 were observed. Our supported neutral set is therefore -3
through 3; an unknown future code, missing projection or malformed number must be
UNSUPPORTED/UNAVAILABLE, not copied into the frontend's permissive default label.
Neither signed progress nor likelihood code is a numerical probability. Values beyond
100 or below -100 are legitimate progress, not invalid percentages.

First delivery uses only the first projection, and requires its `offset` to be zero.
The frontend displays that projection beside a countdown to the first source deadline.
It does not use the `offset` property or display the later projections here. The full
offset-to-deadline contract for offsets 1/2 is consequently not independently verified:
do not expose multi-night forecasts or shift a later projection into first place.

A future valid lock means LOCKED, with the exact source expiry displayed. A past lock
still present in a response is ambiguous, not evidence of an unlocked refreshed forecast:
use UNAVAILABLE until a suitable response removes it. Malformed lock data is unavailable
locally. A lock takes precedence over calibration, as on the official page.

## Target, source age and season

Bootstrap supplied:

```json
{
  "game_config": {
    "settings": {
      "static_content_url": "https://fantasy.premierleague.com/gcs/plfpl-prod-static-content/plfpl-production/2026_27/",
      "price_change_deadlines": [
        "2026-10-18T23:00:00Z",
        "2026-10-19T23:00:00Z",
        "2026-10-20T23:00:00Z"
      ]
    },
    "status": {"price_change_last_updated": "2026-10-18T12:50:13.579657Z"}
  }
}
```

`SSe` uses the first deadline for “Next Price Changes Happen in”. Preserve that instant;
do not derive tonight from fetch time or scan forward past an expired first deadline.
At or after the target the captured forecast expires. The `timezone: UTC` setting is not
evidence for replacing UK-midnight price rules with midnight UTC. This October target is
00:00 BST on 19 October, but source targets, not a guessed clock rule, define forecasts.

`xSe` displays the source timestamp as “Last updated” and says updates are every 15 minutes.
That is the predictor's update time, not proof of completed overnight price processing.
Bootstrap's cache headers permit stale responses (`max-age=300`,
`stale-while-revalidate=3600`, `stale-if-error=3600`); its observed `Age` was 458 seconds.
Keep fetched-at and source-updated-at separately. A repeated successful fetch cannot
make an old source timestamp fresh. Apply the proposed 45-minute usability ceiling to
both ages, and the evening 20-minute warning to either; source time missing, malformed
or in the future makes the forecast unavailable, not the market-price capture invalid.
There is no evidence here of an overnight run-completed flag.

The static-content URL explicitly identifies `2026_27`; event 1 was 21 August 2026 and
event 38 was 30 May 2027. First delivery requires an exact recognised official URL season
marker, consecutive-year suffix and consistent 38-event season dates, matching the
configured playing season. Missing/ambiguous/mismatched season evidence rejects capture.
Do not infer a season from the local date, element IDs or SeasonResolver's fallback.

For polling, a later, future first deadline in a source update at/after the previous
target is a usable **forecast-cycle rollover** signal. It is not proof that any particular
price changed, that all prices were processed, or that the history endpoint is complete.
Use it to return to 30-minute polling. A restarted worker without a prior-cycle marker
cannot confirm rollover; the proposed 02:00 Europe/London fallback remains nonblocking.

## Actual recent changes

The frontend calls `stats/price-changes/` through its normal API helper. A public GET
returned HTTP 200 and an array of 111 rows. Example shape, with illustrative values:

```json
{"element": 53, "team": 2, "event": 7,
 "changed_at": "2026-10-17T23:00:08.326581Z", "cost_before": 59, "cost_after": 60}
```

`H2` calculates `cost_after - cost_before`; the history view sorts by `changed_at`,
formats that date, and joins the element identity from bootstrap. The observed range was
8 October 2026 23:00:08 UTC through 17 October 2026 23:00:08 UTC. The body contained no
season, coverage metadata, pagination or all-nights-completed indicator. A ten-day sample
does not establish a guaranteed retention window. No row means “not in the captured
recent-change feed”, never “the price did not change”.

The response contained Manzambi's 59→60 change at 17 October 23:00:08 UTC and Wissa's
63→62 change at 16 October 23:00:08 UTC. These are dated inspection examples only, not
validation of the original feedback's undated “tonight/last night”, and not static strategy
facts. In UK local time those changes were on 18 and 17 October respectively.

Add a bounded independent recent-change snapshot, with its own capture time and the
validated companion bootstrap season/identity mapping. Never relabel it a complete
archive or backfill canonical daily rows. An unknown element or inconsistent season
quarantines the history snapshot; a history failure must not prevent a valid forecast
refresh. A historical team need not equal the player's current team after a transfer.
History data has no predictor refresh timestamp: attribute the feed read and each
`changed_at`, not bootstrap's predictor timestamp. No production HTTP at chat time.

## Consequences for the remaining units

1. Parser/evidence tests cover the verified next-target mapping and source age, unknown
   codes, lock/calibration precedence, unsupported later offsets, season validation,
   history records, and a history failure independent of forecast validity.
2. Storage keeps forecast metadata/player rows and an independently published bounded
   recent-change snapshot in new `fpl_` tables. Only latest snapshots, no polling archive.
3. One gated worker obtains bootstrap then recent changes sequentially, at most two
   endpoint requests per due cycle before bounded retry policy. Each publication has its
   own success time and errors; neither alters daily capture or ranking evidence.
4. Tools distinguish official recent events, daily observations and upcoming forecasts.
   Extend history routing to recent events without inventing overnight events across gaps.
5. Keep the owner's 30/10-minute cadence despite the upstream UI's 15-minute refresh
   notice. The 02:00 fallback and 20/45-minute thresholds remain explicit proposals.

This bounded inspection verifies observed shapes and frontend display semantics, not
empirical prediction accuracy, future API stability or an overnight rollover experiment.
