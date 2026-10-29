import json
import tempfile
import unittest
from pathlib import Path

from icu_research import io, sofa


class SofaTest(unittest.TestCase):
    def test_missing_components_stay_none(self):
        c, _ = sofa.classic({})
        self.assertTrue(all(v is None for v in c.values()))
        self.assertIsNone(sofa.total(c))
        self.assertIsNone(sofa.total(c, 4))

    def test_classic_bands(self):
        w = {"pfMin": 250, "pltMin": 40, "tbilMaxUmol": 110, "mapMin": 60, "gcsMin": 9, "creaMaxUmol": 320, "vasoAgents": []}
        c, f = sofa.classic(w)
        self.assertEqual(c, {"resp": 2, "coag": 3, "liver": 3, "cardio": 1, "cns": 3, "renal": 3})
        self.assertEqual(sofa.total(c), 15)
        self.assertEqual(f, set())

    def test_flags_and_caps(self):
        c, f = sofa.classic({"pfMin": 80, "vasoAgents": ["ne"]})
        self.assertEqual(c["resp"], 2)
        self.assertEqual(c["cardio"], 3)
        self.assertEqual(f, {"resp_support_unknown", "vaso_dose_unknown"})

    def test_sofa2_bands(self):
        w = {"pfMin": 200, "pltMin": 70, "tbilMaxUmol": 3.1 * 17.104, "mapMin": 72, "gcsMin": 9, "creaMaxUmol": 2.1 * 88.4,
             "vasoAgents": ["ne", "dobutamine"]}
        c, _ = sofa.sofa2(w)
        self.assertEqual(c, {"resp": 2, "coag": 3, "liver": 2, "cardio": 3, "cns": 2, "renal": 2})
        c, _ = sofa.sofa2({"vasoAgents": ["ne"]})
        self.assertEqual(c["cardio"], 2)


class IoTest(unittest.TestCase):
    def _write(self, rows):
        d = Path(tempfile.mkdtemp())
        (d / "cohort.jsonl").write_text("\n".join(json.dumps(r) for r in rows), encoding="utf-8")
        return d

    def test_rejects_identifying_keys(self):
        d = self._write([{"schema": io.COHORT_SCHEMA, "stayKey": "x", "outcome": {"mrn": "123"}}])
        with self.assertRaises(ValueError):
            io.load(d)

    def test_rejects_wrong_schema(self):
        d = self._write([{"schema": "other/1", "stayKey": "x"}])
        with self.assertRaises(ValueError):
            io.load(d)

    def test_get_paths(self):
        r = {"a": {"b": {"c": 1}, "n": None}}
        self.assertEqual(io.get(r, "a.b.c"), 1)
        self.assertEqual(io.get(r, "a.n", 5), 5)
        self.assertIsNone(io.get(r, "a.x.y"))


if __name__ == "__main__":
    unittest.main()
