---
title: Positional Mismatch — Defenders Who Play Further Forward
kind: timely
valid_from: 2026-08-26
valid_until: 2026-12-31
---

# Positional Mismatch — Defenders Who Play Further Forward

FPL pays by the position it has a player listed as, not the position they play. When those
disagree, the difference is free value, and it is one of the largest and most durable edges in the
game.

A defender who plays as a winger or a midfielder is still scored as a defender: **6 points a goal,
3 an assist, and 4 more every clean sheet**, while producing chances from an attacking role. The
same output from a midfielder pays 5 and 3 with 1 for the clean sheet; from a forward, 4 and 3 with
nothing. Identical attacking numbers are therefore worth most to whoever is listed furthest back —
and a defender who plays forward collects on both sides of the game at once.

This also changes how fixture difficulty should be read. A stopper's return is mostly the clean
sheet, so a hard fixture removes most of it. A defender playing further forward keeps his attacking
role whoever the opponent is, and often the set-piece duty with it. **A hard fixture is a much
weaker argument against this kind of player**, and dismissing one on fixture difficulty alone is a
mistake.

## Who is on this list, and how they get here

**Membership is a judgement about where a player actually plays, and that is known by watching
matches.** The owner maintains this list and decides who is on it, drawing on watching the games,
on community writing and reporting, and on their own observation. What does not add a name is the
assistant inferring one from statistics.

The reason is that no available number identifies a role. Attacking output correlates with playing
forward, but it conflates three different players: a wing-back, a centre-back who scores headers
from corners, and a penalty taker. Screening 2025/26 by expected goal involvements per 90 put three
centre-backs among its top fourteen defenders and missed Reece James entirely — the measurement
answers a different question from the one this document asks, so a name must come from someone who
has seen the player play.

## The list

| Player | Club | Listed as | Actually plays |
|---|---|---|---|
| De Cuyper | Brighton | DEF | Winger |
| Reece James | Chelsea | DEF | Midfield |

Both also carry set-piece duty, which compounds the edge — check the live order with
`getSetPieceTakers` rather than trusting any list, including this one.

## Using this document

For a player **on** the list: say that they are listed as a defender but play further forward, that
this is why their attacking numbers read high for the position, and that fixture difficulty argues
less against them than against a stopper.

For a player **not** on the list: do not infer a role. The pick tools print `xG/90` and `xA/90` for
every candidate, and `getSetPieceTakers` gives their set-piece duty — those are statements about
output and duty, and they are legitimate evidence on their own terms. "He produces well for a
defender" is supportable from that. "He plays as a winger" is not, and must not be said unless he
appears above.

## Maintaining it

Roles change, and the first weeks of a season are when they change most. Ask the owner to review
this list after the first three gameweeks, after a managerial change at any club named here, and in
January after the transfer window. An entry that can no longer be confirmed by watching should come
off, not be left to age.
