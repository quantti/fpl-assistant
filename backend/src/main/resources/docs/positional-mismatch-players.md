---
title: Positional Mismatch — Players Who Score Above Their Listed Position
kind: timely
valid_from: 2026-08-26
valid_until: 2026-12-31
---

# Positional Mismatch — Players Who Score Above Their Listed Position

FPL pays by the position it has a player listed as, not by the position they play. When those
disagree, the difference is free value, and it is one of the largest and most durable edges in the
game.

A defender who plays as a wing-back or a winger is scored as a defender: **6 points a goal, 3 an
assist, and 4 more every clean sheet**, while producing chances like a midfielder. The same output
from a midfielder pays 5 and 3 with 1 for the clean sheet; from a forward, 4 and 3 with nothing.
So identical attacking numbers are worth most to whoever is listed furthest back — and a defender
who attacks collects on *both* sides of the game at once.

This is why an attacking full-back at a mid-table side can outscore a stopper at a title-winning
one, and why "he has a hard fixture" is a weaker argument against that kind of player than against
a pure stopper. A stopper's return is mostly the clean sheet, so a hard fixture removes most of it.
An attacking defender still takes corners, still gets into the box, and still plays 90 minutes at
Stamford Bridge.

## How to spot one in the data

The pick tools print `xG/90`, `xA/90` and the clean-sheet term separately for exactly this reason.
Compare a player's attacking numbers against the median for their **listed** position, measured
over 2025/26 for everyone with 900+ minutes:

| Listed position | Median xGI/90 | 75th percentile |
|---|---|---|
| GK | 0.00 | 0.00 |
| DEF | 0.11 | 0.15 |
| MID | 0.26 | 0.36 |
| FWD | 0.43 | 0.55 |

A defender above 0.26 is producing like a median midfielder while being paid like a defender. That
is the test. Add set-piece duty on top — a defender on corners or direct free kicks is generating
his own chances, and that duty is checkable live with `getSetPieceTakers`.

## The list, as of 26 August 2026

Attacking output from 2025/26 (10-match window, 900+ minutes), set-piece duty from the daily
capture. **Read these as last season's roles.** A summer move or a new manager can end one, and the
first weeks of a season are when they change.

| Player | Club | Listed | Price | xGI/90 | xG/90 | xA/90 | Set pieces |
|---|---|---|---|---|---|---|---|
| De Cuyper | BHA | DEF | £4.6m | 0.37 | 0.11 | 0.26 | corners #2, direct FK #4 |
| O'Reilly | MCI | DEF | £6.5m | 0.30 | 0.21 | 0.09 | — |
| Muñoz | CRY | DEF | £5.5m | 0.24 | 0.09 | 0.15 | — |
| Aït-Nouri | MCI | DEF | £5.5m | 0.24 | 0.05 | 0.19 | — |
| Calafiori | ARS | DEF | £5.5m | 0.22 | 0.18 | 0.04 | — |
| Maatsen | AVL | DEF | £4.5m | 0.21 | 0.07 | 0.14 | — |
| James | CHE | DEF | £5.5m | 0.20 | 0.05 | 0.15 | corners #1, direct FK #1 |
| Robertson | TOT | DEF | £4.5m | 0.19 | 0.09 | 0.11 | corners #3 |
| Gvardiol | MCI | DEF | £5.5m | 0.19 | 0.15 | 0.05 | — |
| Frimpong | LIV | DEF | £5.5m | 0.18 | 0.04 | 0.14 | — |
| Gusto | CHE | DEF | £5.0m | 0.18 | 0.06 | 0.12 | — |

**De Cuyper** is the clearest case in the game right now: 0.26 xA/90 is midfielder output, he is on
Brighton's corners and fourth on direct free kicks, and he costs £4.6m as a defender. A hard
fixture reduces his clean-sheet share; it does not touch the rest. Judging him only on his side's
expected goals conceded — the way a stopper should be judged — understates him badly.

**Reece James** is the set-piece case rather than the open-play one: first on both corners and
direct free kicks for Chelsea, so his chance creation is partly independent of how the match goes.
His risk is minutes, not role.

### Midfielders producing like forwards

The same logic, one step smaller: a midfielder scores 5 a goal against a forward's 4, and keeps the
1-point clean sheet. Over 2025/26 these midfielders posted forward-level xG/90 — Brooks (BOU) 0.44,
Wilson (LEE) 0.55, Sarr (CRY) 0.45, Palmer (CHE) 0.49, Schade (BRE) 0.40, Martinelli (ARS) 0.35.
Where one of them plays as the furthest-forward attacker, they are a forward on a midfielder's
scoring table.

## Maintaining this document

There is no API field for "the position he actually plays", so this list is judgement over data and
has to be revisited. Review it:

- after the first three gameweeks of a season, when new roles have settled;
- after a managerial change at any club named above;
- in January, after the transfer window.

The measurable half — attacking output against the positional median — is re-derivable from
`player_fixture_stats` at any time. The unmeasurable half is whether the player is still being
picked in that role. Where the two disagree, trust the current season's minutes and the eye test
over this table, and say plainly that this document is dated.
