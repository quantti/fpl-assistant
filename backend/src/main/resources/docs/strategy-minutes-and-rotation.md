---
title: FPL Minutes Facts and the 60-Minute Rule
kind: reference
valid_from: 2026-09-20
---

# FPL Minutes Facts and the 60-Minute Rule

Reference for explaining FPL's scoring boundary without turning observed playing time into a
recommendation rule. The ranking tools order players only by full-fixture expected FPL points.
They do not reduce a score, break a tie, exclude a player or restrict a generated XI because of
minutes.

## What the official 60-minute boundary means

The official FPL boundary is **at least 60 minutes**, excluding stoppage time.

| Minutes in one fixture | Appearance points | GK/DEF clean-sheet eligibility |
| --- | --- | --- |
| 0 | 0 | No |
| 1–59 | 1 | No |
| 60+ | 2 | Yes, if no goal was conceded while on the pitch, subject to the red-card exception |

- Reaching 60 earns the second appearance point and can unlock clean-sheet points: four for a
  goalkeeper or defender and one for a midfielder. A forward receives none.
- A player substituted after reaching 60 keeps an earned clean sheet if the team later concedes.
  A player sent off remains affected by later team concessions.
- Defensive contribution is separate: defenders need 10 CBIT actions and midfielders/forwards 12
  CBIRT actions for two points. It has no 60-minute eligibility rule.

These are scoring facts, not a request to calculate a personal appearance probability from a
small historical sample.

## The ranking-tools policy

Primary pick lists and generated-squad XIs require fresh availability and complete current-season,
current-club evidence. That protects against stale captures, mismatched identity or club,
pending/missing records and contradictory data. It is not a minutes threshold.

When the latest **five complete observed fixtures** contain an appearance below 60 minutes, a pick
row may carry a neutral fact such as: “Tielemans recorded 45 minutes vs Everton on 20 September.”

- The note may include every under-60 observation in those five fixtures, including a known
  zero-minute appearance.
- It must not be described as a minutes risk, rotation penalty, eligibility failure, projection
  adjustment or a reason one player outranks another.
- Do not infer why the player had fewer minutes. The evidence does not distinguish tactics,
  fitness, a red card or a tactical substitution.
- Do not invent an expected-minutes value, a start probability or a 60-minute probability.

## How to explain a recommendation

Explain a recommendation with the displayed expected FPL-points components: xG/xA ability,
fixture-specific clean-sheet probability, defensive contribution, the club's editable fixture
population and FDR only where the tool uses it. Realised points and observed minutes do not alter
the order.

Availability news remains relevant: do not recommend a player marked doubtful, injured, suspended
or otherwise unavailable as though they were fit. A fit status still does not guarantee a start.
If a low-minutes fact is shown, relay it plainly beside the expected-points case rather than
rewriting the rank or claiming certainty about the next lineup.

## Season changes and incomplete data

Previous-season ability can inform a blended expected-points estimate, but it cannot certify a
current role. A missing or incoherent current-season record is unjudged rather than evidence of
poor football. Do not fill a list with an unavailable or unjudged player merely because an old
season was strong.

## Sources and related guidance

- [Official FPL rules](https://fantasy.premierleague.com/en/help/rules), checked 20 September
  2026: appearance points, 60-minute clean-sheet eligibility, substitutions and defensive
  contributions.
- Related corpus guidance: `strategy-defence-and-clean-sheets.md`,
  `strategy-differentials.md`, `captain-selection-and-triple-captain.md` and
  `strategy-deadline-and-team-news.md`.
