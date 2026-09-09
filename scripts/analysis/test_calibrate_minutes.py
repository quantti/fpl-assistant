"""Synthetic, offline regression tests for the historical experiment, not production data."""

import csv
from dataclasses import replace
from datetime import datetime, timedelta, timezone
import io
import json
import math
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import calibrate_minutes as m

PRIOR = {"sixty": .5, "play": .75, "mean_minutes": 60}


def season(code, minutes, missing=(), double=False):
    start = datetime(int(code[:4]), 8, 1, 15, tzinfo=timezone.utc)
    fixtures = {i: m.Fixture(i, i, start + timedelta(days=7 * (i - 1)), 1, 2)
                for i in range(1, len(minutes) + 1)}
    if double:
        fixtures[4] = replace(fixtures[4], gw=3, kickoff=fixtures[3].kickoff + timedelta(days=3))
    rows = {(1, i): m.Row(1, "Seed Defender", "DEF", 1, i, mins, int(mins >= 60))
            for i, mins in enumerate(minutes, 1) if i not in missing}
    return m.Season(code, fixtures, rows, {1: list(rows.values())},
                    {1: list(fixtures.values()), 2: list(fixtures.values())}, {})


def csv_body(rows):
    stream = io.StringIO()
    writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
    writer.writeheader()
    writer.writerows(rows)
    return stream.getvalue().encode()


def source_rows():
    fixture = {"id": "1", "event": "1", "finished": "True",
               "kickoff_time": "2023-08-01T15:00:00Z", "team_h": "1", "team_a": "2"}
    players = [{"element": str(i), "name": f"Seed {i}", "position": "DEF", "fixture": "1",
                "GW": "1", "kickoff_time": fixture["kickoff_time"], "was_home": str(i <= 11),
                "opponent_team": "2" if i <= 11 else "1", "minutes": "90", "starts": "1"}
               for i in range(1, 23)]
    return fixture, players


class MinutesCalibrationTest(unittest.TestCase):
    def setUp(self):
        self.ramp, _ = m.season_ramp()
        self.previous = season("2022-23", [90] * 12)

    def test_official_boundary_and_zero_minutes_are_in_denominator(self):
        rows = season("2023-24", [0, 1, 59, 60, 90]).players[1]
        r = m.rates(rows, PRIOR)
        self.assertEqual(r["n"], 5)
        self.assertEqual(r["sixty"], 2 / 5)
        self.assertEqual(r["play"], 4 / 5)
        self.assertAlmostEqual(r["smoothed"], 3 / 7)
        self.assertLessEqual(r["smoothed"], r["smoothed_play"])
        self.assertLessEqual(60 * r["smoothed"], r["smoothed_minutes"])
        self.assertLessEqual(r["smoothed_minutes"], 90 * r["smoothed_play"])

    def test_equal_mean_does_not_imply_equal_sixty_reliability(self):
        regular = m.rates(season("2023-24", [60, 60]).players[1], PRIOR)
        alternating = m.rates(season("2023-24", [90, 30]).players[1], PRIOR)
        self.assertEqual(regular["mean_minutes"], alternating["mean_minutes"])
        self.assertEqual(regular["sixty"], 1)
        self.assertEqual(alternating["sixty"], .5)
        self.assertEqual(regular["play"] + regular["sixty"] + 4 * regular["sixty"] * .4, 3.6)
        self.assertEqual(alternating["play"] + alternating["sixty"] + 4 * alternating["sixty"] * .4, 2.3)

    def test_missing_historical_row_abstains_instead_of_looking_further_back(self):
        s = season("2023-24", [90] * 12, missing=(10,))
        cutoff = s.fixtures[12].kickoff
        available = [r for r in s.players[1] if r.fixture < 12]
        self.assertIsNone(m.current_window(s, available, cutoff))

    def test_missing_last_row_is_not_hidden_by_last_observed_gameweek(self):
        s = season("2023-24", [90] * 5, missing=(4,))
        samples, audit = m.predictions(s, self.previous, PRIOR, self.ramp)
        self.assertFalse(any(s["fixture"] == 5 for s in samples))
        self.assertGreater(audit["incomplete_current_window"], 0)

    def test_unknown_target_remains_a_prediction_not_an_imputed_zero(self):
        s = season("2023-24", [90] * 3, missing=(3,))
        samples, _ = m.predictions(s, self.previous, PRIOR, self.ramp)
        sample = next(s for s in samples if s["fixture"] == 3)
        self.assertIsNone(sample["y"])
        result = m.summary([sample], "all_comparable", None)
        self.assertEqual(result["admitted"], 1)
        self.assertEqual(result["unknown"], 1)
        self.assertEqual(result["observed"], 0)
        self.assertIsNone(result["risk"])
        self.assertEqual(result["worst_case_risk"], 1)

    def test_both_double_gameweek_legs_use_the_same_frozen_history(self):
        s = season("2023-24", [90, 90, 0, 60], double=True)
        samples, _ = m.predictions(s, self.previous, PRIOR, self.ramp)
        double = [s for s in samples if s["double"]]
        self.assertEqual(len(double), 2)
        self.assertEqual(double[0]["signals"], double[1]["signals"])
        self.assertEqual(double[0]["cutoff"], double[1]["cutoff"])
        self.assertEqual([s["history_n"] for s in double], [2, 2])
        self.assertEqual([s["y"] for s in double], [0, 1])
        report = m.dgws(double, "all_comparable", None)
        self.assertEqual(report["both_sixty"], 0)
        self.assertEqual(report["at_least_one_sixty"], 1)

    def test_future_minutes_change_the_label_not_the_features_or_ramp_input(self):
        a = season("2023-24", [90, 60, 90, 90])
        b = season("2023-24", [90, 60, 20, 0])
        pa, _ = m.predictions(a, self.previous, PRIOR, self.ramp)
        pb, _ = m.predictions(b, self.previous, PRIOR, self.ramp)
        before = next(s for s in pa if s["fixture"] == 3)
        after = next(s for s in pb if s["fixture"] == 3)
        self.assertEqual(before["signals"], after["signals"])
        self.assertEqual(before["qualifying_to_date"], 2)
        self.assertEqual(after["qualifying_to_date"], 2)
        self.assertNotEqual(before["y"], after["y"])

    def test_publication_lag_excludes_a_recent_fixture(self):
        s = season("2023-24", [90, 90, 90])
        cutoff = s.fixtures[2].kickoff + timedelta(hours=26)
        self.assertEqual(len(m.current_window(s, s.players[1][:2], cutoff)), 1)
        cutoff += timedelta(hours=1)
        self.assertEqual(len(m.current_window(s, s.players[1][:2], cutoff)), 2)

    def test_no_current_registration_evidence_means_no_opening_fixture_prediction(self):
        s = season("2023-24", [90] * 4)
        samples, _ = m.predictions(s, self.previous, PRIOR, self.ramp)
        self.assertNotIn(1, {s["fixture"] for s in samples})
        self.assertNotIn(2, {s["fixture"] for s in samples})

    def test_previous_incomplete_season_is_not_silently_used_as_a_complete_denominator(self):
        s = season("2022-23", [90] * 5, missing=(5,))
        self.assertIsNone(m.complete_previous(s, 1))

    def test_club_change_starts_a_new_current_window_and_invalidates_a_whole_prior(self):
        s = season("2023-24", [90, 90, 70, 70, 90])
        for key, row in list(s.rows.items()):
            if row.fixture >= 3:
                s.rows[key] = replace(row, team=2)
        s.players[1] = list(s.rows.values())
        window = m.current_window(s, s.players[1][:4], s.fixtures[5].kickoff)
        self.assertEqual([r.fixture for r in window], [3, 4])
        self.assertIsNone(m.complete_previous(s, 1))
        samples, _ = m.predictions(s, self.previous, PRIOR, self.ramp)
        self.assertIsNone(next(r for r in samples if r["fixture"] == 3)["y"])
        self.assertFalse(any(r["fixture"] == 4 for r in samples))

    def test_future_namesake_does_not_break_an_earlier_name_bridge(self):
        s = season("2023-24", [90] * 5)
        namesake = m.Row(2, "SEED DEFENDER", "MID", 1, 4, 0, 0)
        s.rows[2, 4] = namesake
        s.players[2] = [namesake]
        samples, _ = m.predictions(s, self.previous, PRIOR, self.ramp)
        earlier = next(r for r in samples if r["player"] == 1 and r["fixture"] == 3)
        later = next(r for r in samples if r["player"] == 1 and r["fixture"] == 5)
        self.assertTrue(earlier["has_previous"])
        self.assertFalse(later["has_previous"])

    def test_component_diagnostic_uses_the_official_appearance_boundary(self):
        samples = []
        for minutes in (0, 1, 59, 60, 90):
            play, sixty = int(minutes > 0), int(minutes >= 60)
            signals = {"mean_minutes": minutes, "smoothed_minutes": minutes,
                       "smoothed": sixty, "smoothed_play": play, "attack90": 0, "xgc": None}
            samples.append({"minutes": minutes, "y": sixty, "component_points": play + sixty,
                            "signals": signals})
        result = m.scoring_comparison(samples)
        self.assertEqual(result["components"]["appearance_rmse"], 0)
        self.assertEqual(result["components"]["restricted_points_rmse"], 0)
        self.assertGreater(result["linear_minutes"]["appearance_rmse"], 0)

    def test_component_diagnostic_scales_clean_sheets_by_sixty_not_mean_minutes(self):
        sample = {"minutes": 60, "y": 1, "component_points": 2,
                  "signals": {"mean_minutes": 60, "smoothed_minutes": 60,
                              "smoothed": 1, "smoothed_play": 1, "attack90": 0, "xgc": math.log(2)}}
        regular = m.scoring_comparison([sample])
        sample["signals"]["smoothed"] = .5
        alternating = m.scoring_comparison([sample])
        self.assertEqual(regular["linear_minutes"], alternating["linear_minutes"])
        self.assertAlmostEqual(regular["components"]["restricted_points_rmse"], 2)
        self.assertAlmostEqual(alternating["components"]["restricted_points_rmse"], .5)

    def test_artifact_mismatches_are_rejected_before_loading_holdout_data(self):
        _, ramp_sha = m.season_ramp()
        frozen = {"protocol": m.PROTOCOL, "season_blend_sha256": ramp_sha,
                  "script_sha256": m.sha256(Path(m.__file__).read_bytes())}
        for field in frozen:
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                output = Path(directory)
                (output / "kar-31-selection.json").write_text(json.dumps({**frozen, field: "changed"}))
                with patch.object(m, "source") as read_source:
                    with self.assertRaisesRegex(ValueError, "changed after selection"):
                        m.run_validate(output, output)
                    read_source.assert_not_called()

    def test_rows_are_keyed_to_fixture_side_not_a_final_team_name(self):
        fixture, players = source_rows()
        loaded = m.load_season("test", csv_body([fixture]), csv_body(players))
        self.assertEqual(loaded.rows[1, 1].team, 1)
        self.assertEqual(loaded.rows[22, 1].team, 2)
        self.assertEqual(loaded.audit["unknown_starts_fixtures"], 0)
        self.assertEqual(loaded.rows[1, 1].starts, 1)

    def test_missing_start_column_population_is_unknown_not_non_starts(self):
        fixture, players = source_rows()
        for p in players:
            p["starts"] = "0"
        loaded = m.load_season("test", csv_body([fixture]), csv_body(players))
        self.assertEqual(loaded.audit["unknown_starts_fixtures"], 1)
        self.assertIsNone(loaded.rows[1, 1].starts)
        self.assertIsNone(m.rates(loaded.players[1], PRIOR)["starts"])
        self.assertEqual(m.blend(.9, None, .1), .9)

    def test_identical_duplicates_are_removed_but_conflicts_fail_loudly(self):
        fixture, players = source_rows()
        loaded = m.load_season("test", csv_body([fixture]), csv_body(players + [players[0]]))
        self.assertEqual(len(loaded.rows), 22)
        self.assertEqual(loaded.audit["exact_duplicates_removed"], 1)
        conflict = {**players[0], "minutes": "60"}
        with self.assertRaisesRegex(ValueError, "conflicting duplicate"):
            m.load_season("test", csv_body([fixture]), csv_body(players + [conflict]))

    def test_fractional_minutes_and_mismatched_fixture_chronology_are_rejected(self):
        fixture, players = source_rows()
        players[0]["minutes"] = "59.9"
        with self.assertRaisesRegex(ValueError, "Not an integer"):
            m.load_season("test", csv_body([fixture]), csv_body(players))
        players[0]["minutes"] = "60"
        players[0]["GW"] = "2"
        with self.assertRaisesRegex(ValueError, "inconsistent chronology"):
            m.load_season("test", csv_body([fixture]), csv_body(players))

    def test_no_risk_constraint_relaxation_when_every_gate_fails(self):
        candidate = {"observed": 1000, "players": 50, "gameweeks": 30, "coverage": .5,
                     "upper_risk": .11, "variant": "sixty", "cutoff": .9}
        self.assertIsNone(m.choose([candidate], .1))
        self.assertEqual(m.choose([candidate], .15), candidate)

    def test_recent_zeroes_cannot_be_hidden_by_the_previous_season_ramp(self):
        s = season("2023-24", [0, 0, 0])
        samples, _ = m.predictions(s, self.previous, PRIOR, self.ramp)
        sample = next(r for r in samples if r["fixture"] == 3)
        self.assertEqual(sample["qualifying_to_date"], 0)
        self.assertEqual(sample["signals"]["mean_minutes"], 90)
        self.assertEqual(sample["signals"]["sixty"], 1)
        self.assertFalse(sample["recent"])
        self.assertFalse(m.admitted(sample, "smoothed_recent", .5))

    def test_recent_veto_is_abstention_not_a_zero_probability(self):
        sample = {"signals": {"smoothed_recent": .95}, "recent": False}
        self.assertFalse(m.admitted(sample, "smoothed_recent", .9))
        self.assertEqual(sample["signals"]["smoothed_recent"], .95)

    def test_empty_selected_probability_population_is_reported_without_a_fake_score(self):
        result = m.calibration([], "smoothed", PRIOR)
        self.assertEqual(result["n"], 0)
        self.assertIsNone(result["brier"])

    def test_zero_failures_does_not_produce_a_zero_upper_risk_bound(self):
        samples = [{"gw": 1 + i // 10, "cutoff": f"2024-09-{1 + i // 10:02d}", "y": 1}
                   for i in range(200)]
        first = m.upper_risk(samples, samples)
        self.assertGreater(first, 0)
        self.assertEqual(first, m.upper_risk(samples, samples))


if __name__ == "__main__":
    unittest.main()
