# FPL Assistant — Deterministic Squad Builder Design Spec

**Status:** Designed 2026-08-27. Not implemented.

**Relationship to existing work:** Builds on the expected-points model introduced in
`PickTools` (PR #48). That model ranks *individual* players; nothing in the project composes
a *set*. This spec adds the missing half: a constrained optimiser that selects a legal
15-player squad, and a tool that can promise a constraint rather than attempt one.

---

## Idea

Asked for a wildcard squad with three Arsenal, three Chelsea and three Manchester City
players including Haaland, Gabriel and João Pedro, the assistant produced two answers in a
row that were not squads. The defects are provable from the answers alone, without consulting
the database:

1. **Three of fifteen slots were not players.** "4.0m maalivahti", "4.5m pelaava
   keskikenttäpelaaja", "Arsenal-puolustaja, halvin käytettävissä oleva". A squad with a
   placeholder is not a squad.
2. **The club constraint was broken while being claimed satisfied.** The answer stated
   "Arsenal: Gabriel, Calafiori, £4.5m puolustaja" — two real players and a wish.
3. **The arithmetic was wrong.** The answer's own table sums to £98.7m; the prose claimed
   £99.7–99.9m.
4. **One player carried two prices in one conversation.** Foden at £9.5m, then £7.0m.
5. **The answer contradicted its own table mid-reply** and the "corrected" version still did
   not satisfy the constraint.
6. **Club attributions were asserted with no source**, because no tool was called to check
   them.

The root cause is that three different jobs were merged into prose generation: **constraint
satisfaction** (combinatorial), **arithmetic** (exact), and **player facts** (lookup). The
model is unreliable at all three when improvising, and today no tool performs any of them.

This spec makes squad construction deterministic. The model gathers intent; a solver produces
the squad; the model explains it.

## What already exists

- `PickTools` — expected FPL points per 90 from `player_fixture_stats`, blended with realised
  points at a weight that rises through the season. Ranks individuals.
- `fpl_daily_price` — daily capture from FPL's own API: price, position, club, ownership,
  availability, set-piece order. Fresh, and independent of tapas.
- `SeasonResolver` / `SeasonContext` — season scope and provenance.
- `DailyPriceSource` — reader and provenance line for the daily capture.
- `NameMatch` / `LikePatterns` — name resolution helpers.
- `MyTeamTools.getMyTeam` — the user's squad, bank and team value, when their FPL team id is
  stored.
- Strategy corpus (`strategy-team-building.md`, `strategy-preseason-team-building.md`) — the
  written account of what a good squad structure looks like.

## Scope

A new package, `fi.karivanttinen.fplassistant.squad`, and one `@Tool` method, `buildSquad`,
that builds a 15-player squad from scratch under stated constraints — the wildcard and
pre-season case.

The shared scoring formula is lifted out of `PickTools` into `ExpectedPoints` so it exists in
one place. `PickTools` keeps its behaviour; only the location of the arithmetic changes.

### Out of scope

- Defensive-contribution points in the expected-points model (issue #46).
- Per-fixture clean-sheet projection from team xG/xGC instead of FDR (issue #47).
- Players with no minutes history — returning from injury, new signings, promoted clubs.
  They are excluded from scored selection here; a curated source for them is its own ticket.
- Transfer planning, hit arithmetic, chip timing.
- Per-gameweek starting XI optimisation (see "One XI over the horizon" below).

---

## Scoring

Expected points per 90 becomes **expected points per fixture**:

```
xPtsPerFixture(p, f) = xPts90(p) × expMinutes(p) / 90 × fdrAdjust(f)
expMinutes(p)        = minutes in window / the player's team's fixtures in window
```

`expMinutes` comes straight from `player_fixture_stats` rows and penalises rotation by
construction: a player at 95% of available minutes keeps nearly his full per-90 value, one at
55% keeps a little over half. This is what stops the solver buying cheap players who do not
play — the same failure as a placeholder, wearing a real name.

`player_fixture_stats.starts` is available as a secondary signal and is recorded here as
future work; the minutes ratio alone is the v1 model.

A player's value over the horizon is the **sum over his next N fixtures**, not the average:

```
score(p) = Σ  xPtsPerFixture(p, f)
         f ∈ next N gameweeks
```

Summing is what makes a double gameweek correct without special handling — it is literally
two rows — and a blank gameweek correct as zero rows. An average would hide both.

`fdrAdjust(f) = 1 + (3 − fdr) × FDR_SWING`, `FDR_SWING = 0.1`. FDR 3 is neutral, FDR 1 gives
×1.2, FDR 5 gives ×0.8. This is coarse: it applies one multiplier to the whole score, when a
fixture should move the clean-sheet term more than the attacking terms. Issue #47 replaces it
with a team xG/xGC projection. The constant is named, not buried in SQL.

### One XI over the horizon

The solver picks **one starting XI for the whole horizon** — the one maximising the sum
above. It does not re-optimise the XI per gameweek.

Per-gameweek XI selection would reward squads whose fixture runs complement each other, which
is a real effect. It also multiplies the model's variables by N and produces output that is
hard to explain ("this player is here only for GW4"). It is a second-order gain and is
deliberately left out of v1.

### Bench weight

The objective gives bench places a small weight ε rather than zero:

```
maximise  Σ score(p) × ( x_p + ε × (s_p − x_p) )      ε = BENCH_WEIGHT = 0.1
```

At zero the solver would fill the bench with arbitrary £4.0m names, since they are all
equally worthless to it. At ε it prefers the ones who actually play — which is what auto-subs
need — while still trading a playing bench player away when the money buys more in the XI.

---

## Data sources

**The candidate pool comes entirely from `fpl_daily_price`.** That table already carries
everything the pool needs: `element_id`, `web_name`, `full_name`, `team_id`, `team_name`,
`team_short`, `element_type`, `now_cost`, `selected_by_percent`, `status`, `news`.

Two consequences:

1. **Issue #50 cannot recur here.** This tool never reads a stale price or a stale injury
   flag, because it does not read them from tapas at all.
2. **It works across the season changeover.** Tapas has not synced 2026-27; the pool is not
   empty regardless.

Only the **metrics** come from `player_fixture_stats`: xG/90, xA/90, xGC, minutes, starts.
When the metric season is not the season being played — early season, under
`MIN_SAMPLE_GAMEWEEKS` completed gameweeks — players are bridged by lowercased `web_name`
with the uniqueness guard already used in `PickTools`: a name belonging to two players in
either season is dropped rather than guessed. A player who changed clubs carries his previous
club's minutes; that is the best signal available and the output says so.

Fixture difficulty comes from `fixture.team_h_difficulty` / `team_a_difficulty`.

### Provenance is two lines, not one

Like `MyTeamTools`, this tool reports from two sources with different freshness:

- prices, ownership and availability — `DailyPriceSource.provenance()` (season + capture date)
- metrics — `SeasonContext.provenance()` (season + whether it is being played)

A single banner cannot describe both, and a price attributed to the wrong season is exactly
the error the provenance convention exists to prevent. **This is a documented exception to the
single-banner rule, in the same class as `MyTeamTools`, and must be recorded in `AGENTS.md`
when implemented.**

### Two-tier pool

| Tier | Admission | Score | Eligible for |
|---|---|---|---|
| **A — rankable** | `status` in (`a`,`d`) and ≥ `MIN_WINDOW_MINUTES` in the metric window | real | XI and bench |
| **B — bodies** | `status = 'a'`, insufficient minutes history | **0** | **bench only** (`x_p = 0`) |

Tier B exists because the cheapest legal bench filler is frequently a player with no record —
and a squad that cannot reach £4.0m fodder is over-constrained in a way real managers are not.

The admission rules differ on purpose. A doubtful player (`d`) with a record can be judged and
flagged, so tier A admits him. A doubtful player with no record offers neither points nor
certainty of availability, so tier B does not — a body is worth buying only when it is at
least a fit one.

Tier B is a refinement of the rule that players without minutes history are not auto-selected,
not a reversal of it. The concern was that the optimiser would buy a real player who never
plays and present him authoritatively. A Tier B player is asserted to be worth **nothing**, is
labelled a body in the output, and can never start. An explicit pin still overrides the tier.

---

## The ILP model

Solved with **ojAlgo 57.1.1** (MIT, pure Java, every declared dependency `test`-scoped — so
nothing is added to the runtime classpath, last released 2026-08-19) via
`ExpressionsBasedModel`. Two binary variables per candidate: `s_p` (in the 15), `x_p` (in the
starting XI).

### Rules of the game — never relaxed

| # | Rule | Row |
|---|---|---|
| 1 | Squad size | `Σ s_p = 15` |
| 2 | Position quotas | `Σ s_p = 2 / 5 / 5 / 3` by `element_type` 1/2/3/4 |
| 3 | Club limit | `Σ_{club c} s_p ≤ 3` for every club |
| 4 | Budget | `Σ price_p · s_p ≤ budget` (integers ×10) |
| 5 | XI ⊆ squad | `x_p ≤ s_p` |
| 6 | XI size | `Σ x_p = 11` |
| 7 | Formation | `Σ_{GK} x = 1`, `Σ_{DEF} x ≥ 3`, `Σ_{FWD} x ≥ 1` |

Rule 7 encodes the **rule**, not an enumerated formation list — see "To verify" below.
`MID ∈ [2,5]` follows from it rather than being stated.

The goalkeeper row is an equality (`= 1`) where `FPL_RULES.md` words the rule as "at least 1".
Every formation FPL accepts starts exactly one goalkeeper, and a second would occupy an
outfield place the game does not offer. The equality is therefore what FPL enforces rather
than what the sentence says — and it goes on the verification list below with the rest of the
formation question.

Captain and vice-captain are both mandatory and both must be in the XI. They are chosen after
the solve, by score; the vice-captain is taken from a different club than the captain where
that costs nothing, because a postponed fixture otherwise removes both.

The bench is one goalkeeper and three outfield players in auto-substitution priority order.
Bench order is an output with meaning, not a list.

### Budget is not always £100.0m

£100.0m is correct for a squad built from nothing. A manager playing a wildcard has **team
value + bank**, which may be £101.7m or £98.4m — and that is the case the original
conversation was about. `budget` is a parameter defaulting to 100.0; when the user's FPL team
id is stored, the model is instructed to read the real figure from `getMyTeam` and pass it.
The output states which budget was used.

### User constraints — outrank structural defaults

| # | Constraint | Row |
|---|---|---|
| 8 | Pinned players | `s_p = 1` |
| 9 | Excluded players | `s_p = 0` |
| 10 | Club quota | `Σ_{club c} s_p = q_c`, `≥ q_c` or `≤ q_c` |
| 11 | Forced formation (optional) | equalities on rule 7's sums |

Row 10 is the point of the whole feature. "Three City players" is not a preference to be
interpreted; it is an equality the solver cannot violate.

A club quota carries a comparator, because all three kinds get asked for:

| Written | Meaning | Row |
|---|---|---|
| `Arsenal:3` | exactly three | `Σ = 3` |
| `Arsenal:>=2` | at least two | `Σ ≥ 2` |
| `Arsenal:<=2` | at most two | `Σ ≤ 2` |
| `Arsenal:0` | none | `Σ = 0` |

A bare number means exactly, because that is the common request — "all three from Arsenal" —
and it is the reading that fails loudly rather than quietly. `=3` is accepted as an explicit
synonym. `Arsenal:0` costs nothing to support and gives a club-level exclusion for free.

Every quota is still bounded above by rule 3. An exact or minimum quota above 3 breaks the
game's own limit and is refused at pre-flight rather than silently clipped, as is a pair of
quotas for one club that contradict each other (`Arsenal:>=3, Arsenal:<=2`).

Making minimums expressible matters beyond convenience: an exact quota is the most common
source of infeasibility, and a manager who says "at least two Arsenal players" usually means
it. Forcing that intent through an equality manufactures refusals the user never asked for.

### Structural defaults — relaxed before user constraints

An unconstrained optimum is a poor squad. Points per million favour mid-price players, so the
solver buys fifteen £6–8m players, no premium, and the squad has neither a ceiling nor a
captain. It is optimal and useless.

| # | Default | Row | Source |
|---|---|---|---|
| 12 | 1–3 premiums (≥ £10.0m) | `1 ≤ Σ_{cost≥100} s_p ≤ 3` | `strategy-team-building.md`, "Premium allocation" |
| 13 | ≥ 6 of 15 at ownership ≥ 20% | `Σ_{owned≥20} s_p ≥ 6` | same, "Template vs differentials" |
| 14 | No non-players in the XI | `x_p = 0` where `expMinutes < XI_MIN_EXPECTED_MINUTES` | same, "avoid dead slots" |

Three defaults, not thirteen. Each is a named constant, each is echoed in the output, each is
overridable.

**Row 14 constrains the XI, not the fifteen.** The bench carries no minutes floor: cheapness
competes against playing time through ε, so the solver takes a playing £4.5m over a
non-playing £4.5m but takes a non-playing £4.0m when the £0.5m returns more in the XI than
that bench player ever would. The output reports bench expected minutes so the trade is
visible.

**On ownership as a constraint.** Ownership is deliberately absent from `score` — putting it
there would be a claim about a player's quality, and rebuilding `PickTools` away from exactly
that claim is why the expected-points model exists. Row 13 is not scoring. It is structural
risk management against the field, and the corpus states it plainly: *"Pure template finishes
mid-rank; pure contrarian finishes bottom."* No player's point estimate moves.

**Precedence.** Rules 1–7 are never relaxed. If user constraints (8–11) make the structural
defaults (12–14) infeasible, the defaults are relaxed in order 13 → 12 → 14 and the output
names what was relaxed. User constraints are never relaxed silently; they produce a refusal.

---

## Infeasibility

ojAlgo returns a solver state, not an irreducible infeasible subset. Naming the binding
constraint is therefore our job, and it is done **before the solve**, where the message can be
precise:

- a pin set exceeds the three-per-club limit
- a pin set exceeds a position quota
- club minimums sum above 15, or any exact or minimum quota exceeds 3
- two quotas for one club contradict each other
- a club quota cannot be met from the available players at that club
- an exact or minimum club quota collides with a maximum on the same club, or with the pins
  already placed at that club
- pinned prices plus the cheapest legal completion exceed the budget — reported with the
  shortfall in pounds
- a pinned name is unavailable (`status` not in `a`/`d`)

If the pre-flight passes and the solver still reports infeasible, constraint groups are
relaxed one at a time and re-solved; the group whose relaxation restores feasibility is named.

**The tool never returns a partial squad.** Fifteen real players, or a refusal that says why.

---

## Tool surface

```java
buildSquad(String mustInclude, String mustExclude, String clubQuota,
           double budget, int nextN, String formation)
```

No parameter identifies an account (`AGENTS.md`). Defaults: `budget` 100.0, `nextN` 5,
`formation` chosen by the solver, the three string parameters empty.

`clubQuota` is `"Arsenal:3,Chelsea:>=2,Spurs:<=1"` — see the comparator table above.
`mustInclude` and `mustExclude` are comma-separated names.

### Name resolution is a requirement, not a detail

The original conversation failed on exactly this: *"FPL-haku ei tunnistanut kirjoitusasua
'Joao Pedro'"*. Names resolve against `fpl_daily_price`'s `web_name` and `full_name` on the
latest capture date, with diacritics normalised on both sides so `Joao Pedro` finds
`João Pedro`.

**An ambiguous name is a refusal, not a guess.** The tool lists the matches and stops. An
unmatched name likewise. Club names resolve against both `team_name` and `team_short`.

### Output contract

Every reply carries:

- both provenance lines
- **a constraint receipt** — every parsed constraint beside its achieved value
  (`Arsenal: 3/3 ✓`), so a misreading is visible instead of silent
- fifteen real players: name, club, price, expected points over the horizon
- the computed total cost and the money remaining
- the XI, its formation, and the bench in substitution order
- captain and vice-captain
- how many players were excluded for insufficient minutes history, with a note that any of
  them can be pinned
- which structural defaults, if any, were relaxed

---

## Testing

The central test is not a unit test but an **invariant test**: across a hundred randomised
constraint sets, the result either satisfies *every* rule of the game or is a refusal. There
is no third outcome. That is the whole promise of the feature.

Also:

- the ILP model against a small synthetic pool: each of rules 1–7 violated in turn, and the
  solver rejects
- pre-flight checks: every infeasibility type yields its own named reason, never a generic
  "no solution"
- name resolution: `Joao Pedro` → `João Pedro`; an ambiguous name refuses and lists matches;
  an invented name refuses
- club quota comparators: `Arsenal:3`, `Arsenal:>=2`, `Arsenal:<=2`, `Arsenal:=3` and
  `Arsenal:0` each produce their own row; `Arsenal:4` and `Arsenal:>=3, Arsenal:<=2` are
  refused at pre-flight with their own reasons
- formation rule against the enumerated list — the test fails if they disagree, which is how
  the 5-2-3 discrepancy below gets settled rather than assumed
- structural-default relaxation order, and that rules 1–7 are never relaxed
- **an acceptance test reproducing the original request**: 3 ARS + 3 CHE + 3 MCI with Haaland,
  Gabriel and João Pedro under £100.0m returns either a valid fifteen or a precise refusal —
  never a placeholder
- integration against the seeded disposable test database (`TapasSeed` plus `fpl_daily_price`
  rows), never against Supabase

---

## To verify before implementation

**`FPL_RULES.md` contradicts itself on legal formations.** It states the rule as "at least 1
GK, at least 3 DEF, at least 1 FWD", lists valid formations as 3-4-3, 3-5-2, 4-3-3, 4-4-2,
4-5-1, 5-3-2, 5-4-1 — which omits **5-2-3**, legal under that rule — and gives a third
variant in the auto-substitution section ("min 1 GK, 3 DEF, 1 FWD").

Two questions need settling against FPL's own rules page:

1. Is **5-2-3** legal? The stated rule says yes; the enumerated list says no.
2. Is exactly one goalkeeper required, or merely at least one?

The model encodes the stated rule with a goalkeeper equality. The enumerated list becomes a
test. If FPL's rules page says otherwise, the rule row changes and the test catches it. This
is not settled by guessing.

## Constants

| Name | Value | Meaning |
|---|---|---|
| `HORIZON_DEFAULT` | 5 | gameweeks scored ahead |
| `BUDGET_DEFAULT` | 1000 | £100.0m, ×10 integers |
| `PREMIUM_THRESHOLD` | 100 | £10.0m |
| `TEMPLATE_OWNERSHIP` | 20.0 | percent owned to count as template |
| `TEMPLATE_MIN_COUNT` | 6 | of fifteen |
| `XI_MIN_EXPECTED_MINUTES` | 60 | per team fixture, to be eligible to start |
| `BENCH_WEIGHT` | 0.1 | ε on bench places in the objective |
| `FDR_SWING` | 0.1 | score multiplier per FDR step from 3 |

`MIN_WINDOW_MINUTES` (180), `MIN_SAMPLE_GAMEWEEKS` (5), `WINDOW_MATCHES` (10) and
`WHOLE_SEASON` (60) are reused from `PickTools` via `ExpectedPoints`.

## Related issues

- #46 — defensive contribution into `xPts/90`; raises defender and midfielder scores here too
- #47 — fixture-aware clean sheets; replaces `fdrAdjust`
- #49 — minutes and rotation weighting; partly answered by `expMinutes`, and `PickTools`
  should adopt it
- #50 — `PickTools` reads price and injury flags from the stale tapas `player` table; this
  spec avoids the same mistake by sourcing the pool from `fpl_daily_price`
- #26 — early-season blending across seasons
