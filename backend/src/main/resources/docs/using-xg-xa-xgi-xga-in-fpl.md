---
title: Using xG, xA, xGI and xGA in FPL
kind: reference
valid_from: 2026-08-21
---

# Using xG, xA, xGI and xGA in FPL

Reference document on FPL's expected-stats columns — expected goals (xG), expected assists (xA), expected goal involvements (xGI), and expected goals against (xGA) — what each measures, when each matters by position, and how to use them without over-interpreting small samples.

These are FPL's own expected-stats figures (derived from the same data provider FPL uses for its live scoring), not a third-party model. They appear per fixture and aggregated per season.

## What each stat measures

- **xG (expected goals)** — the likelihood a given shot becomes a goal, summed over a player's shots. Measures chance quality, not just shot count.
- **xA (expected assists)** — the likelihood a pass leads to a goal, summed over a player's passes. Measures chance creation.
- **xGI (expected goal involvements)** — xG + xA combined. A single number for a player's total attacking involvement.
- **xGA (expected goals against)** — the xG a team concedes over a match. FPL records it against each individual player for the period they were on the pitch, so a player who played the full match carries the team's whole figure. Measures defensive pressure faced.

## Which stat matters for which position

- **Forwards and midfielders**: xG, xA and xGI are the core signals. A forward on a high xG who is underconverting (fewer goals than xG predicts) may be due a positive regression; one overconverting (more goals than xG) may be running hot and due to cool.
- **Defenders and goalkeepers**: xGA is the relevant defensive signal. A defence conceding a low xGA is being asked to defend little or defending well; a high xGA means the team is under sustained pressure and clean-sheet odds worsen. xG/xA are less relevant for defenders' own output unless they are attacking full-backs or set-piece threats.

## Over- and under-performance

Compare actual goals/assists to xG/xA over a window:

- **Underperforming** (actual < expected): the underlying chances are there but the finishing has been poor. Often a buy signal if the player is still getting chances — the numbers suggest the output should follow.
- **Overperforming** (actual > expected): the output exceeds the chance quality. This can be a sell signal if you believe regression is coming, though elite finishers can sustain some overperformance.

## Sample-size caveats

- A single gameweek's xG is nearly meaningless — one big chance can dominate.
- Use a window of at least 4–6 fixtures before reading a trend. A 10-fixture window is more reliable but slower to reflect changes in role or form.
- xG/xA do not capture role changes, penalties, or set-piece duties directly — cross-check with minutes and fixture context.

## Team-level vs player-level

- **Team xG** shows whether a team's attack is creating chances regardless of whether goals have gone in. A team with high xG but low goals is often a good attacking target (the chances are there).
- **Player xG** shows whether an individual is getting the chances. A player can be in a good team (high team xG) but personally low xG if they are not the one taking the chances — so always check the player, not just the team.

## Other advanced stats

xG, xA, xGI and xGA are the columns available today. Other advanced metrics (e.g. shot-creating actions, xG buildup, pressure regains) are not currently stored; if a reliable source for them is added later, the same over/under-performance and sample-size discipline applies.

## Summary

Use xG and xA for attackers, xGI for total involvement, and xGA for defenders and keepers. Compare to actuals over a 4–6 fixture window, watch for regression, and never read a single gameweek in isolation.
