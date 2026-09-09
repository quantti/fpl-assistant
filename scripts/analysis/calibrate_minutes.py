#!/usr/bin/env python3
"""Offline KAR-31 experiment. Public CSVs only; never connects to the application/database.

Run select first, then validate against its saved artifact. See the frozen protocol in
 docs/analysis/2026-09-09-kar-31-minutes-calibration.md. Standard library only.
"""

import argparse
import csv
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import hashlib
import io
import json
import math
from pathlib import Path
import random
import re
import statistics
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
REVISION = "9779cdbc0c07f6c900c2d0c181ddf6bb9c800f88"
VARIANTS = ("mean_minutes", "starts", "sixty", "smoothed", "smoothed_recent")
PROTOCOL = {
    "source_revision": REVISION,
    "prior_season": "2022-23", "grid_season": "2023-24",
    "selection_season": "2024-25", "holdout_season": "2025-26",
    "window": 10, "minimum_current_observations": 2, "minimum_minutes": 180,
    "pseudo_fixtures": 2, "recent_veto_fixtures": 2,
    "publication_hours_after_kickoff": 27, "cutoff_minutes_before_gw": 90,
    "quantiles": [0.50, 0.75, 0.90, 0.95, 0.99, 1.0],
    "risk_budgets": [0.05, 0.10, 0.15], "primary_risk_budget": 0.10,
    "minimum_admitted": 200, "minimum_players": 20, "minimum_gameweeks": 20,
    "minimum_coverage": 0.05, "bootstrap_block": 4,
    "bootstrap_draws": 2000, "seed": 31060,
}


@dataclass(frozen=True)
class Fixture:
    id: int
    gw: int
    kickoff: datetime
    home: int
    away: int


@dataclass(frozen=True)
class Row:
    player: int
    name: str
    position: str
    team: int
    fixture: int
    minutes: int
    starts: int | None
    xg: float = 0
    xa: float = 0
    xgc: float = 0
    goals: int = 0
    assists: int = 0
    clean_sheets: int = 0


@dataclass
class Season:
    code: str
    fixtures: dict
    rows: dict
    players: dict
    teams: dict
    audit: dict


def integer(value):
    # No silent truncation of malformed minutes, ids or starts.
    number = float(value)
    if not math.isfinite(number) or not number.is_integer():
        raise ValueError(f"Not an integer: {value!r}")
    return int(number)


def boolean(value):
    if value.lower() not in ("true", "false"):
        raise ValueError(f"Not a boolean: {value!r}")
    return value.lower() == "true"


def timestamp(value):
    result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if result.tzinfo is None:
        raise ValueError("Source timestamp lacks a timezone")
    return result.astimezone(timezone.utc)


def sha256(body):
    return hashlib.sha256(body).hexdigest()


def download(cache, season, kind):
    relative = "gws/merged_gw.csv" if kind == "merged" else "fixtures.csv"
    # Revision in the directory prevents a different snapshot from being reused silently.
    path = cache / REVISION / f"{season}-{kind}.csv"
    if not path.exists():
        url = f"https://raw.githubusercontent.com/vaastav/Fantasy-Premier-League/{REVISION}/data/{season}/{relative}"
        with urllib.request.urlopen(url, timeout=90) as response:
            body = response.read()
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(body)
    return path.read_bytes()


def load_season(code, fixture_body, player_body):
    fixtures = {}
    unfinished = 0
    for source in csv.DictReader(io.StringIO(fixture_body.decode("utf-8-sig"))):
        if not boolean(source["finished"]):
            unfinished += 1
            continue
        fixture = Fixture(integer(source["id"]), integer(source["event"]),
                          timestamp(source["kickoff_time"]), integer(source["team_h"]),
                          integer(source["team_a"]))
        if fixture.id in fixtures:
            raise ValueError(f"{code}: duplicate fixture {fixture.id}")
        fixtures[fixture.id] = fixture

    rows, originals = {}, {}
    duplicates = managers = 0
    for source in csv.DictReader(io.StringIO(player_body.decode("utf-8-sig"))):
        if source["position"] == "AM":
            managers += 1
            continue
        fixture_id = integer(source["fixture"])
        if fixture_id not in fixtures:
            raise ValueError(f"{code}: player row for unknown/unfinished fixture {fixture_id}")
        fixture = fixtures[fixture_id]
        if (timestamp(source["kickoff_time"]) != fixture.kickoff
                or integer(source["GW"]) != fixture.gw):
            raise ValueError(f"{code}: inconsistent chronology for fixture {fixture_id}")
        home = boolean(source["was_home"])
        team, opponent = (fixture.home, fixture.away) if home else (fixture.away, fixture.home)
        if integer(source["opponent_team"]) != opponent:
            raise ValueError(f"{code}: inconsistent opponent for fixture {fixture_id}")
        minutes, starts = integer(source["minutes"]), integer(source["starts"])
        if not 0 <= minutes <= 90 or starts not in (0, 1):
            raise ValueError(f"{code}: invalid minutes/starts in fixture {fixture_id}")
        row = Row(integer(source["element"]), source["name"], source["position"], team,
                  fixture_id, minutes, starts, float(source.get("expected_goals", 0)),
                  float(source.get("expected_assists", 0)),
                  float(source.get("expected_goals_conceded", 0)),
                  integer(source.get("goals_scored", 0)), integer(source.get("assists", 0)),
                  integer(source.get("clean_sheets", 0)))
        key = row.player, row.fixture
        if key in rows:
            if originals[key] != source:
                raise ValueError(f"{code}: conflicting duplicate player/fixture {key}")
            duplicates += 1
            continue
        rows[key], originals[key] = row, source

    # An all-zero default starts column is missing information, not 22 players benched.
    team_starts = Counter()
    for row in rows.values():
        team_starts[row.fixture, row.team] += row.starts
    unknown_starts = {f.id for f in fixtures.values()
                      if team_starts[f.id, f.home] != 11 or team_starts[f.id, f.away] != 11}
    players, teams = defaultdict(list), defaultdict(list)
    for key, row in list(rows.items()):
        if row.fixture in unknown_starts:
            row = Row(**{**row.__dict__, "starts": None})
            rows[key] = row
        players[row.player].append(row)
    for player_rows in players.values():
        player_rows.sort(key=lambda r: (fixtures[r.fixture].kickoff, r.fixture))
    for fixture in fixtures.values():
        teams[fixture.home].append(fixture)
        teams[fixture.away].append(fixture)
    for club in teams.values():
        club.sort(key=lambda f: (f.kickoff, f.id))
    audit = {
        "fixture_sha256": sha256(fixture_body), "player_sha256": sha256(player_body),
        "finished_fixtures": len(fixtures), "unfinished_fixtures": unfinished,
        "unique_rows": len(rows), "players": len(players), "exact_duplicates_removed": duplicates,
        "manager_rows_removed": managers, "explicit_zero_rows": sum(r.minutes == 0 for r in rows.values()),
        "unknown_starts_fixtures": len(unknown_starts),
        "fixtures_without_rows": len(set(fixtures) - {r.fixture for r in rows.values()}),
    }
    return Season(code, fixtures, rows, dict(players), dict(teams), audit)


def source(cache, code):
    season = load_season(code, download(cache, code, "fixtures"), download(cache, code, "merged"))
    if season.audit["finished_fixtures"] != 380 or season.audit["fixtures_without_rows"]:
        raise ValueError(f"Incomplete completed season: {code}: {season.audit}")
    return season


def season_ramp():
    path = ROOT / "backend/src/main/java/fi/karivanttinen/fplassistant/tools/SeasonBlend.java"
    body = path.read_text()
    values = re.search(r"WEIGHT_BY_MATCHES\s*=\s*\{([^}]+)}", body).group(1)
    return [float(v.strip()) for v in values.split(",")], sha256(body.encode())


def complete_previous(season, player):
    rows = season.players[player]
    # An intra-season move lacks a reliable registration boundary in the archive.
    # Do not fabricate the missing club-membership dates from the eventual roster.
    if len({r.team for r in rows}) != 1:
        return None
    first = season.fixtures[rows[0].fixture].kickoff
    expected = [f.id for f in season.teams[rows[0].team] if f.kickoff >= first]
    if set(expected) != {r.fixture for r in rows}:
        return None
    return rows


def current_window(season, available, cutoff):
    last = available[-1]
    segment = []
    for row in reversed(available):
        if row.team != last.team:
            break
        segment.append(row)
    first = season.fixtures[segment[-1].fixture].kickoff
    lag = timedelta(hours=PROTOCOL["publication_hours_after_kickoff"])
    fixtures = [f for f in season.teams[last.team]
                if f.kickoff >= first and f.kickoff + lag <= cutoff][-PROTOCOL["window"]:]
    by_fixture = {r.fixture: r for r in segment}
    if any(f.id not in by_fixture for f in fixtures):
        return None
    return [by_fixture[f.id] for f in fixtures]


def rates(rows, prior):
    if not rows:
        return None
    n = len(rows)
    minutes = sum(r.minutes for r in rows)
    sixty = sum(r.minutes >= 60 for r in rows)
    play = sum(r.minutes > 0 for r in rows)
    alpha = PROTOCOL["pseudo_fixtures"]
    qualifiers = [r for r in rows if r.minutes >= 60]
    return {
        "n": n, "minutes": minutes, "mean_minutes": minutes / n,
        "starts": sum(r.starts for r in rows) / n if all(r.starts is not None for r in rows) else None,
        "sixty": sixty / n, "play": play / n,
        "smoothed": (sixty + alpha * prior["sixty"]) / (n + alpha),
        "smoothed_play": (play + alpha * prior["play"]) / (n + alpha),
        "smoothed_minutes": (minutes + alpha * prior["mean_minutes"]) / (n + alpha),
        "attack90": (6 * sum(r.xg for r in rows) + 3 * sum(r.xa for r in rows)) * 90 / minutes if minutes else None,
        "xgc": statistics.mean(r.xgc for r in qualifiers) if qualifiers else None,
    }


def blend(a, b, weight):
    if a is None:
        return b
    if b is None:
        return a
    return weight * a + (1 - weight) * b


def prior_from(season):
    rows = [r for r in season.rows.values() if r.position == "DEF"]
    return {"sixty": statistics.mean(r.minutes >= 60 for r in rows),
            "play": statistics.mean(r.minutes > 0 for r in rows),
            "mean_minutes": statistics.mean(r.minutes for r in rows), "observations": len(rows)}


def predictions(season, previous, prior, ramp):
    previous_names = defaultdict(list)
    for player, rows in previous.players.items():
        previous_names[rows[-1].name.casefold()].append(player)
    previous_rates = {p: rates(complete_previous(previous, p), prior) for p in previous.players}
    gameweeks = defaultdict(list)
    for fixture in season.fixtures.values():
        gameweeks[fixture.gw].append(fixture)
    cutoffs = {gw: min(f.kickoff for f in fs) - timedelta(minutes=PROTOCOL["cutoff_minutes_before_gw"])
               for gw, fs in gameweeks.items()}
    samples, audit = [], Counter()
    lag = timedelta(hours=PROTOCOL["publication_hours_after_kickoff"])
    for gw in sorted(gameweeks, key=cutoffs.get):
        cutoff = cutoffs[gw]
        available = {p: [r for r in rows if season.fixtures[r.fixture].kickoff + lag <= cutoff]
                     for p, rows in season.players.items()}
        available = {p: rs for p, rs in available.items() if rs}
        known_names = Counter(rs[-1].name.casefold() for rs in available.values())
        for player, history in available.items():
            last = history[-1]
            if last.position != "DEF":
                continue
            targets = [f for f in gameweeks[gw] if last.team in (f.home, f.away)]
            audit["known_defender_opportunities"] += len(targets)
            if not targets:
                continue
            window = current_window(season, history, cutoff)
            if window is None:
                audit["incomplete_current_window"] += len(targets)
                continue
            if len(window) < PROTOCOL["minimum_current_observations"]:
                audit["short_current_history"] += len(targets)
                continue
            name = last.name.casefold()
            prev_ids = previous_names.get(name, [])
            prev = previous_rates[prev_ids[0]] if len(prev_ids) == 1 and known_names[name] == 1 else None
            cur = rates(window, prior)
            if cur["minutes"] + (prev["minutes"] if prev else 0) < PROTOCOL["minimum_minutes"]:
                audit["below_minutes_floor"] += len(targets)
                continue
            qualifies = sum(r.minutes >= 60 for r in history)
            weight = ramp[min(qualifies, len(ramp) - 1)] if prev else 1.0
            signals = {key: blend(cur[key], prev[key] if prev else None, weight)
                       for key in ("mean_minutes", "starts", "sixty", "smoothed", "play",
                                   "smoothed_play", "smoothed_minutes", "attack90", "xgc")}
            signals["smoothed_recent"] = signals["smoothed"]
            veto = all(r.minutes >= 60 for r in window[-PROTOCOL["recent_veto_fixtures"]:])
            audit["comparable_opportunities"] += len(targets)
            for target in targets:
                # Outcome lookup occurs AFTER eligibility, features, club and target are fixed.
                outcome = season.rows.get((player, target.id))
                if outcome is not None and outcome.team != last.team:
                    outcome = None
                audit["known_outcomes" if outcome else "unknown_outcomes"] += 1
                samples.append({"player": player, "gw": gw, "fixture": target.id,
                                "cutoff": cutoff.isoformat(), "signals": signals.copy(), "recent": veto,
                                "history_n": len(window), "qualifying_to_date": qualifies,
                                "has_previous": prev is not None, "double": len(targets) > 1,
                                "minutes": outcome.minutes if outcome else None,
                                "y": int(outcome.minutes >= 60) if outcome else None,
                                "component_points": (int(outcome.minutes > 0) + int(outcome.minutes >= 60)
                                    + 6 * outcome.goals + 3 * outcome.assists
                                    + 4 * int(outcome.minutes >= 60 and outcome.clean_sheets > 0)) if outcome else None})
    return samples, dict(audit)


def quantile(values, q):
    values = sorted(values)
    return values[round(q * (len(values) - 1))]


def grid(samples):
    result = {}
    for variant in VARIANTS:
        key = "smoothed" if variant == "smoothed_recent" else variant
        values = [s["signals"][key] for s in samples if s["signals"][key] is not None]
        result[variant] = sorted({quantile(values, q) for q in PROTOCOL["quantiles"]})
    return result


def admitted(sample, variant, cutoff):
    if variant == "all_comparable":
        return True
    value = sample["signals"][variant]
    return (value is not None and value >= cutoff
            and (variant != "smoothed_recent" or sample["recent"]))


def wilson_upper(failures, n):
    if not n:
        return None
    z = 1.959963984540054
    p = failures / n
    return (p + z * z / (2 * n) + z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n))) / (1 + z * z / n)


def upper_risk(selected, all_samples):
    if not selected:
        return None
    # Unknown outcomes are failures ONLY in this pessimistic selection bound, not imputed labels.
    gw_counts = defaultdict(lambda: [0, 0])
    for s in selected:
        gw_counts[s["gw"]][0] += 1
        gw_counts[s["gw"]][1] += s["y"] != 1
    dates = {s["gw"]: s["cutoff"] for s in all_samples}
    gameweeks = sorted(dates, key=dates.get)
    rng = random.Random(PROTOCOL["seed"])
    risks = []
    for _ in range(PROTOCOL["bootstrap_draws"]):
        sampled = []
        while len(sampled) < len(gameweeks):
            start = rng.randrange(len(gameweeks))
            sampled.extend(gameweeks[(start + j) % len(gameweeks)] for j in range(PROTOCOL["bootstrap_block"]))
        totals = [gw_counts[g] for g in sampled[:len(gameweeks)]]
        n = sum(t[0] for t in totals)
        if n:
            risks.append(sum(t[1] for t in totals) / n)
    n = len(selected)
    failures = sum(s["y"] != 1 for s in selected)
    return max(quantile(risks, 0.975), wilson_upper(failures, n))


def summary(samples, variant, cutoff, intervals=True):
    selected = [s for s in samples if admitted(s, variant, cutoff)]
    observed = [s for s in selected if s["y"] is not None]
    errors = sum(s["y"] == 0 for s in observed)
    successes = sum(s["y"] == 1 for s in samples)
    weeks = {s["gw"] for s in samples}
    per_week = Counter(s["gw"] for s in selected)
    players_per_week = Counter(gw for _, gw in {(s["player"], s["gw"]) for s in selected})
    unknown = len(selected) - len(observed)
    return {
        "variant": variant, "cutoff": cutoff, "comparable": len(samples),
        "admitted": len(selected), "observed": len(observed), "unknown": unknown,
        "sub60": errors, "zero_minutes": sum(s["minutes"] == 0 for s in observed),
        "short_minutes": sum(0 < s["minutes"] < 60 for s in observed),
        "risk": errors / len(observed) if observed else None,
        "precision": 1 - errors / len(observed) if observed else None,
        "worst_case_risk": (errors + unknown) / len(selected) if selected else None,
        "upper_risk": upper_risk(selected, samples) if intervals else None,
        "coverage": len(selected) / len(samples) if samples else 0,
        "recall": sum(s["y"] == 1 for s in selected) / successes if successes else None,
        "players": len({s["player"] for s in selected}), "gameweeks": len(per_week),
        "player_gameweeks": len({(s["player"], s["gw"]) for s in selected}),
        "player_gameweek_coverage": len({(s["player"], s["gw"]) for s in selected}) / len({(s["player"], s["gw"]) for s in samples}) if samples else 0,
        "admitted_fixtures_per_gw_min_median_max": [min((per_week[g] for g in weeks), default=0),
            statistics.median([per_week[g] for g in weeks]) if weeks else 0,
            max((per_week[g] for g in weeks), default=0)],
        "admitted_players_per_gw_min_median_max": [min((players_per_week[g] for g in weeks), default=0),
            statistics.median([players_per_week[g] for g in weeks]) if weeks else 0,
            max((players_per_week[g] for g in weeks), default=0)],
    }


def choose(candidates, budget):
    supported = [r for r in candidates if r["observed"] >= PROTOCOL["minimum_admitted"]
                 and r["players"] >= PROTOCOL["minimum_players"]
                 and r["gameweeks"] >= PROTOCOL["minimum_gameweeks"]
                 and r["coverage"] >= PROTOCOL["minimum_coverage"]
                 and r["upper_risk"] <= budget]
    if not supported:
        return None
    return min(supported, key=lambda r: (-r["coverage"], r["variant"] == "smoothed_recent",
                                        VARIANTS.index(r["variant"]), r["cutoff"]))


def calibration(samples, key, prior):
    known = [s for s in samples if s["y"] is not None]
    bins = []
    edges = [(0, .5), (.5, .6), (.6, .7), (.7, .8), (.8, .9), (.9, 1.0000001)]
    for low, high in edges:
        bucket = [s for s in known if low <= s["signals"][key] < high]
        bins.append({"range": [low, min(high, 1)], "n": len(bucket),
                     "predicted": statistics.mean(s["signals"][key] for s in bucket) if bucket else None,
                     "observed": statistics.mean(s["y"] for s in bucket) if bucket else None})
    return {"n": len(known), "brier": statistics.mean((s["signals"][key] - s["y"]) ** 2 for s in known) if known else None,
            "prior_brier": statistics.mean((prior["sixty"] - s["y"]) ** 2 for s in known) if known else None, "bins": bins}


def scoring_comparison(samples):
    # Fixed diagnostic, NOT used to select the gate. Only appearance/goals/assists/CS;
    # excludes realised-points blending, FDR, saves, bonus, deductions and DefCon.
    errors = defaultdict(list)
    appearance_errors = defaultdict(list)
    for sample in samples:
        if sample["y"] is None:
            continue
        s = sample["signals"]
        attack = s["attack90"] or 0
        cs = 4 * math.exp(-s["xgc"]) if s["xgc"] is not None and s["xgc"] > 0 else 0
        appearance = s["smoothed_play"] + s["smoothed"]
        predictions = {"unscaled90": 2 + attack + cs,
                       "linear_minutes": (2 + attack + cs) * s["mean_minutes"] / 90,
                       "components": appearance + attack * s["smoothed_minutes"] / 90 + cs * s["smoothed"]}
        appearances = {"unscaled90": 2, "linear_minutes": 2 * s["mean_minutes"] / 90,
                       "components": appearance}
        actual_appearance = int(sample["minutes"] > 0) + sample["y"]
        for model, prediction in predictions.items():
            errors[model].append((prediction - sample["component_points"]) ** 2)
            appearance_errors[model].append((appearances[model] - actual_appearance) ** 2)
    return {m: {"n": len(e), "restricted_points_rmse": math.sqrt(statistics.mean(e)),
                "appearance_rmse": math.sqrt(statistics.mean(appearance_errors[m]))}
            for m, e in errors.items()}


def dgws(samples, variant, cutoff):
    groups = defaultdict(list)
    for s in samples:
        if s["double"] and admitted(s, variant, cutoff):
            groups[s["player"], s["gw"]].append(s)
    complete = [ss for ss in groups.values() if len(ss) == 2 and all(s["y"] is not None for s in ss)]
    return {"admitted_player_gameweeks": len(groups), "two_known_outcomes": len(complete),
            "both_sixty": sum(all(s["y"] for s in ss) for ss in complete),
            "at_least_one_sixty": sum(any(s["y"] for s in ss) for ss in complete)}


def selected_diagnostics(samples, selection, prior):
    variant, cutoff = selection["variant"], selection["cutoff"]
    subsets = {"sgw": [s for s in samples if not s["double"]],
               "dgw": [s for s in samples if s["double"]],
               "short_history": [s for s in samples if s["history_n"] < PROTOCOL["window"]],
               "full_history": [s for s in samples if s["history_n"] == PROTOCOL["window"]],
               "early_gw_1_10": [s for s in samples if s["gw"] <= 10],
               "middle_gw_11_25": [s for s in samples if 11 <= s["gw"] <= 25],
               "late_gw_26_38": [s for s in samples if s["gw"] >= 26]}
    return {"subgroups": {k: summary(v, variant, cutoff, intervals=False) for k, v in subsets.items()},
            "double_gameweeks": dgws(samples, variant, cutoff),
            "selected_calibration": {key: calibration([s for s in samples if admitted(s, variant, cutoff)], key, prior)
                                     for key in ("sixty", "smoothed")}}


def save(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, sort_keys=True, allow_nan=False) + "\n")


def run_select(cache, output):
    seasons = {code: source(cache, code) for code in ("2022-23", "2023-24", "2024-25")}
    prior = prior_from(seasons["2022-23"])
    ramp, ramp_sha = season_ramp()
    training, training_audit = predictions(seasons["2023-24"], seasons["2022-23"], prior, ramp)
    cutoffs = grid(training)
    development, development_audit = predictions(seasons["2024-25"], seasons["2023-24"], prior, ramp)
    candidates = [summary(development, v, cutoff) for v in VARIANTS for cutoff in cutoffs[v]]
    selections = {str(b): choose(candidates, b) for b in PROTOCOL["risk_budgets"]}
    result = {"protocol": PROTOCOL, "script_sha256": sha256(Path(__file__).read_bytes()),
              "season_blend_sha256": ramp_sha, "prior": prior, "cutoff_grid": cutoffs,
              "sources": {s: data.audit for s, data in seasons.items()},
              "training_audit": training_audit, "development_audit": development_audit,
              "development_candidates": candidates, "selections": selections,
              "development_baselines": [summary(development, "all_comparable", None), summary(development, "mean_minutes", 60)],
              "training_calibration": {k: calibration(training, k, prior) for k in ("sixty", "smoothed")},
              "development_calibration": {k: calibration(development, k, prior) for k in ("sixty", "smoothed")}}
    path = output / "kar-31-selection.json"
    save(path, result)
    print(f"Selection frozen: {path}; SHA-256 {sha256(path.read_bytes())}")
    for budget, selection in selections.items():
        print(budget, json.dumps(selection))


def run_validate(cache, output):
    selection_path = output / "kar-31-selection.json"
    selection_bytes = selection_path.read_bytes()
    frozen = json.loads(selection_bytes)
    ramp, ramp_sha = season_ramp()
    if (frozen["protocol"] != PROTOCOL or frozen["season_blend_sha256"] != ramp_sha
            or frozen["script_sha256"] != sha256(Path(__file__).read_bytes())):
        raise ValueError("Protocol, script or SeasonBlend changed after selection; do not retune against holdout")
    previous, holdout = source(cache, "2024-25"), source(cache, "2025-26")
    if previous.audit != frozen["sources"]["2024-25"]:
        raise ValueError("Selection source changed")
    samples, audit = predictions(holdout, previous, frozen["prior"], ramp)
    results = {}
    for budget, selected in frozen["selections"].items():
        if selected is None:
            results[budget] = None
            continue
        result = summary(samples, selected["variant"], selected["cutoff"])
        result["holdout_within_risk_budget"] = result["upper_risk"] is not None and result["upper_risk"] <= float(budget)
        result["diagnostics"] = selected_diagnostics(samples, selected, frozen["prior"])
        results[budget] = result
    report = {"protocol": PROTOCOL, "selection_sha256": sha256(selection_bytes),
              "source": holdout.audit, "holdout_audit": audit, "results": results,
              "baselines": [summary(samples, "all_comparable", None), summary(samples, "mean_minutes", 60)],
              # Diagnostic only: never reselect a policy from these holdout results.
              "frozen_candidate_diagnostics": [summary(samples, r["variant"], r["cutoff"])
                                               for r in frozen["development_candidates"]],
              "calibration": {k: calibration(samples, k, frozen["prior"]) for k in ("sixty", "smoothed")},
              "restricted_scoring_comparison": scoring_comparison(samples)}
    save(output / "kar-31-holdout.json", report)
    for budget, result in results.items():
        print(budget, json.dumps({k: v for k, v in result.items() if k != "diagnostics"}) if result else "no supported gate")
    print("Holdout audit:", json.dumps(audit))
    print("Scoring diagnostic:", json.dumps(report["restricted_scoring_comparison"]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("select", "validate"))
    parser.add_argument("--cache", type=Path, default=Path("/tmp/kar31-minutes"))
    parser.add_argument("--output", type=Path, default=ROOT / "docs/analysis/kar-31")
    args = parser.parse_args()
    (run_select if args.phase == "select" else run_validate)(args.cache, args.output)


if __name__ == "__main__":
    main()
