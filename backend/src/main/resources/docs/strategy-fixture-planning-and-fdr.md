---
title: FPL Fixture Planning and FDR
kind: reference
valid_from: 2026-08-21
---

# FPL Fixture Planning and FDR

Reference document on using fixtures to plan an FPL season: what the fixture difficulty rating (FDR) is, how to read a fixture ticker, how to buy into favourable runs and plan exits, and how this pairs with the assistant's fixture tools. Includes 2026/27 opening-run examples from the community's and FFScout's fixture analysis.

## What FDR is

- FDR is FPL's fixture difficulty rating for each match, on a 1–5 scale (5 = hardest). It reflects the opponent's strength, not the fixture's history or the team's current form.
- A fixture ticker lays every club's upcoming fixtures side by side, colour-coded by FDR. Advanced tickers add two more columns: **clean-sheet percentage** (defensive outlook) and **projected goals** (attacking outlook), plus a "relative" mode that accounts for the team's own strength rather than just the opposition's.
- FDR is a planning aid, not a prediction: an easy run tells you when a team's assets are worth owning, not how many points they will score.

## Why fixture planning wins

- Medium-term thinking beats weekly reactions: managers who know the next 4–6 Gameweeks ahead buy into teams before their good runs and sell before the hard ones — "jump on, jump off" — instead of chasing the previous week's hauls.
- The method (per FFScout's fixture-run analysis): identify favourable runs of at least four matches (with at most one bad fixture inside), then decide how many assets to hold from that club during the window.
- Some players are "fixture-proof" — Haaland is the canonical example — and should not be sold for fixtures. Fixture planning is about the marginal slots: the second and third assets from a club, and the budget picks bought specifically for a run.

## Planning horizons

- **Opening runs (GW1–6)**: the season's first six Gameweeks set the initial template. Buy into the teams with easy starts; the community and FFScout's ticker analysis for 2026/27 point at Manchester United (Hull and Ipswich to open, then a favourable run through to GW8), Everton (Palace, Bournemouth, Ipswich and Hull among the first six — top of the early ticker), and the usual defensive favourites.
- **Weeks 7–16**: look for the next wave of runs — e.g. Bournemouth's GW9–16 stretch (with the trip to Arsenal as the one bad fixture, home games against Coventry and Hull on either side), and Sunderland's decent opening 2–3 fixtures.
- **Second half (GW17+)**: longer runs appear once the season's shape is known — Chelsea's good sequence from GW17 is FFScout's example. Blank and double gameweeks dominate the late-season calendar and deserve their own planning (see the roadmap note below).
- **Fulham's GW6–8**: the Cottagers face all three promoted clubs in that window — the classic "buy for a run" opportunity even though their opening fixtures are poor.

## Buying into runs

- The play: identify the run, pick the club's best-value asset (ideally with set-piece or penalty duty, not just the flashiest name), and time the buy before the run starts — ideally one transfer before the first easy fixture, so you ride the whole window.
- United's opening run in 2026/27 is the template example: Bruno Fernandes (7.6 points per start under Carrick, penalties, set pieces) plus Mbeumo, Cunha and Sesko (£7.0m) as the early attention names. Note the congestion risk that comes with the good fixtures: United's first Champions League match falls between Gameweeks 3 and 4, with the trip to Everton and the Manchester derby on either side.
- Arsenal host Coventry in GW1 but face tricky matches in GW2–3 — a common pattern is to take the GW1 fixture and the defence, not the attack, during the awkward middle.
- Pair fixture runs with the assistant's `getFixtureDifficulty` tool (the FDR trend for a team's next N fixtures) and `getUpcomingFixtures` when advising: the tools give the numbers, this framework gives the method.

## Planning exits

- Every player bought for a fixture run needs a planned exit: know the Gameweek when the run ends, who the one-transfer replacement is, and whether the player is a hold (fixture-proof, elite, cheap enabler) or a sell.
- Avoid "no booked transfers" at the deadline: pre-season and early-season information is cheap, and a single injury can invalidate a two-transfer plan. The first wildcard window (roughly GW6–9) is the natural restructure point for fixture-driven teams.
- Do not chase: a team's good run already happened is the worst time to buy. FDR is forward-looking; use it that way.

## Pitfalls

- FDR measures opponent strength only: an "easy" fixture against a struggling promoted side can still be a loss for a team in poor form, and vice versa. Combine FDR with form and expected stats before advising.
- Promoted clubs complicate early-season FDR: their ratings are estimates, and newly-promoted sides often start better than their rating implies (home crowds, no fear) before regressing.
- European commitments distort fixture planning for the big clubs — rotation and postponed fixtures arrive with congested schedules. When a club has midweek European ties, treat their attacking assets' minutes as a risk even in a good FDR window.
- Blank and double Gameweeks are the biggest fixture-planning event of the second half and are covered by their own dedicated strategy document; this document covers the run-based planning that applies all season.
