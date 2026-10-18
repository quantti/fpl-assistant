# Direct team xG source check — 2026-10-18

Read-only research requested by the owner. No sync, database write or application change.

## Primary payload checks

Fetched FPL's public endpoints directly:
- https://fantasy.premierleague.com/api/fixtures/ — 380 fixtures, 60 finished in the returned
  capture. Neither fixture fields nor any stats identifier contains team xG/xGA. Stats identifiers
  include goals, assists, BPS, cards, saves and defensive contribution.
- https://fantasy.premierleague.com/api/bootstrap-static/ — teams contain identity/strength
  ratings, not observed team xG. Expected-stat fields belong to elements (players).
- https://fantasy.premierleague.com/api/event/6/live/ — player expected statistics, no team totals.
  This is a gameweek feed; it is not in general a single-fixture feed during a double gameweek.

Understat supplies direct home/away match totals in its public league-data response:
- https://understat.com/getLeagueData/EPL/2026
- https://understat.com/getLeagueData/EPL/2025

Requests returned JSON after standard gzip decoding. `dates[]` contains `id`, `datetime`, home/away
club identities, `isResult` and `xG.h`/`xG.a`. For one side, xGA is the opposing side's xG from the
same match record; no player aggregation is needed.

Observed coverage: 2026/27 had 65 unique completed matches across 20 clubs, all 65 with finite,
nonnegative home and away xG. 2025/26 had 380 unique completed matches across 20 clubs, all with
both xG values. These are one-time observations, not an availability guarantee. Future fixtures
must not be treated as observed zero-xG matches.

Example: Understat match 31244, Newcastle–Aston Villa, datetime 2026-10-17 16:30:00,
home xG 0.920183 and away xG 2.0822. The response datetime has no explicit timezone; a sync
would need an explicit validated date/fixture-matching policy, not an assumed timezone.

## Correction to the earlier xGC explanation

FPL player xGC is not invariably a repeated full-match team figure. The GW6 response has Haaland
79 minutes / xGC 0.87, Donnarumma 90 / 0.94, and Gvardiol 24 / 0.07. A full-match player can
supply a full-match value, but MAX over arbitrary player exposures is not by itself proof of a
full-match total. This check does not modify the project's existing MAX convention.

## Consequences and remaining checks

A direct team feed can remove player-roster completeness as a prerequisite for the club model,
while player tools retain their separate FPL evidence requirements. Understat is a different
provider/model: do not silently mix it with FPL xG/xGC or relabel it FPL data. It is not currently
one of this application's sync sources.

Before adopting: check permitted automated/commercial use and endpoint stability; club/season/
fixture identity, timestamps, corrections, missing matches, historical alignment and model effects.
No provider terms/SLA, sustained availability or production integration was verified. Do not start
an importer/cutover or deepen the old compatibility repair based on this research alone.
