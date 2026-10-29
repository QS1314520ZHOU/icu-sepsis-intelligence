import json
import tempfile
import unittest
from pathlib import Path

from icu_research import report, synth
from icu_research.analyses import alerts, audit, phenotypes, prediction, scores, timing


class _Data:
    cache = {}

    @classmethod
    def get(cls, scenario):
        if scenario not in cls.cache:
            cls.cache[scenario] = synth.make(1200, seed=1, scenario=scenario)
        return cls.cache[scenario]


class AnalysesTest(unittest.TestCase):
    def test_audit(self):
        c, _ = _Data.get("signal")
        r = audit.run(c)
        self.assertEqual(r["status"], "ok")
        w = r["data"]["windows"]["3h"]
        self.assertGreater(w["items"]["C3"]["nullRate"], 0.05)  # planted 10% incomplete fluid
        self.assertEqual(audit.run([])["status"], "no_data")

    def test_alerts(self):
        c, a = _Data.get("signal")
        r = alerts.run(a, c)
        self.assertEqual(r["status"], "ok")
        self.assertLess(r["data"]["dayReview"][0], r["data"]["nightReview"][0])  # planted slower night review
        self.assertEqual(alerts.run([], c)["status"], "no_data")

    def test_scores(self):
        c, _ = _Data.get("signal")
        r = scores.run(c)
        self.assertEqual(r["status"], "ok")
        self.assertGreater(r["data"]["auroc"]["sofa2"]["auc"][0], 0.55)
        self.assertEqual(scores.run(c[:30])["status"], "insufficient")

    def test_timing_recovers_planted_effect(self):
        c, _ = _Data.get("signal")
        r = timing.run(c)
        t = r["data"]["models"]["abxCategory"]["terms"]["抗菌药 >3h（对比≤1h）"]
        self.assertGreater(t["lo"], 1.0)
        self.assertTrue(t["lo"] < 2.72 < t["hi"])  # planted OR e^1

    def test_timing_null_scenario(self):
        c, _ = _Data.get("null")
        t = timing.run(c)["data"]["models"]["abxCategory"]["terms"]["抗菌药 >3h（对比≤1h）"]
        self.assertTrue(t["lo"] < 1.0 < t["hi"])

    def test_timing_small_sample_refuses(self):
        c, _ = _Data.get("signal")
        r = timing.run(c[:80])
        self.assertEqual(r["status"], "insufficient")

    def test_phenotypes_recover_three(self):
        c, _ = _Data.get("signal")
        r = phenotypes.run(c)
        self.assertEqual(r["status"], "ok")
        self.assertEqual(r["data"]["k"], 3)
        self.assertGreater(r["data"]["stabilityARI"], 0.8)
        deaths = [p["death"][0] for p in r["data"]["profiles"]]
        self.assertEqual(deaths[0], max(deaths))  # most vasopressor hours = vasoplegic = highest planted risk
        self.assertEqual(phenotypes.run(c[:40])["status"], "insufficient")

    def test_prediction_signal_beats_null_and_rule(self):
        sig = prediction.run(_Data.get("signal")[0], boots=50)
        nul = prediction.run(_Data.get("null")[0], boots=50)
        self.assertEqual(sig["status"], "ok")
        self.assertGreater(sig["data"]["auroc"], nul["data"]["auroc"] + 0.03)
        self.assertGreater(sig["data"]["sensitivity"], sig["data"]["ruleMAP65"]["sensitivity"])
        self.assertEqual(prediction.run(_Data.get("signal")[0][:50])["status"], "insufficient")

    def test_landmarks_stop_at_unknown_pressor_state(self):
        r = {"traj": {"map": [70] * 24, "hr": [90] * 24, "lactate": [None] * 24, "vaso": [0, 0, None] + [0] * 21},
             "timing": {"vasoFirstMin": None}, "ageYears": 60}
        self.assertEqual([l["h"] for l in prediction.landmarks(r)], [1, 2])

    def test_report_end_to_end(self):
        d = Path(tempfile.mkdtemp())
        synth.write(d / "exp", 1200, 2, "signal")
        res = report.run(d / "exp", d / "out")
        self.assertTrue(res["synthetic"])
        self.assertTrue(all(s["status"] == "ok" for s in res["sections"].values()), {k: v["status"] for k, v in res["sections"].items()})
        md = (d / "out" / "report.md").read_text(encoding="utf-8")
        self.assertIn("合成数据", md)
        self.assertIn("静默运行", md)
        json.loads((d / "out" / "results.json").read_text(encoding="utf-8"))

    def test_report_empty_export(self):
        d = Path(tempfile.mkdtemp())
        (d / "cohort.jsonl").write_text("", encoding="utf-8")
        res = report.run(d)
        self.assertTrue(all(s["status"] == "no_data" for s in res["sections"].values()))


if __name__ == "__main__":
    unittest.main()
