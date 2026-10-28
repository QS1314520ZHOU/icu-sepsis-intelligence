import math
import unittest

import numpy as np

from icu_research import stats


class StatsTest(unittest.TestCase):
    def test_wilson_known_value(self):
        p, lo, hi = stats.wilson(8, 10)
        self.assertAlmostEqual(p, 0.8)
        self.assertAlmostEqual(lo, 0.4902, places=3)
        self.assertAlmostEqual(hi, 0.9433, places=3)
        self.assertEqual(stats.wilson(0, 0), (None, None, None))

    def test_auroc_with_ties_and_mann_whitney(self):
        y = [0, 0, 1, 1, 0, 1]
        s = [0.1, 0.4, 0.35, 0.8, 0.4, 0.4]
        # brute force pair count with ties = 0.5
        pos = [b for a, b in zip(y, s) if a]
        neg = [b for a, b in zip(y, s) if not a]
        brute = sum(1.0 if p > n else 0.5 if p == n else 0.0 for p in pos for n in neg) / (len(pos) * len(neg))
        self.assertAlmostEqual(stats.auroc(y, s), brute)
        self.assertIsNone(stats.auroc([1, 1], [0.2, 0.3]))

    def test_delong_ci_covers_and_compare_symmetric(self):
        rng = np.random.default_rng(0)
        y = rng.integers(0, 2, 400)
        s1 = y + rng.normal(0, 1, 400)
        s2 = y + rng.normal(0, 2, 400)
        a, lo, hi = stats.auroc_ci(y, s1)
        self.assertTrue(lo < a < hi)
        c = stats.delong_compare(y, s1, s2)
        c2 = stats.delong_compare(y, s2, s1)
        self.assertAlmostEqual(c[2], -c2[2])
        self.assertAlmostEqual(c[3], c2[3])
        self.assertLess(c[3], 0.05)

    def test_logistic_recovers_coefficients(self):
        rng = np.random.default_rng(1)
        x = rng.normal(0, 1, 5000)
        y = (rng.random(5000) < 1 / (1 + np.exp(-(-0.5 + 1.2 * x)))).astype(int)
        fit = stats.logistic(np.column_stack([np.ones(5000), x]), y)
        self.assertTrue(fit["converged"])
        self.assertAlmostEqual(fit["beta"][1], 1.2, delta=0.12)
        self.assertAlmostEqual(fit["beta"][0], -0.5, delta=0.1)
        self.assertGreater(fit["se"][1], 0)

    def test_calibration_perfect_model(self):
        rng = np.random.default_rng(2)
        p = rng.uniform(0.05, 0.6, 6000)
        y = (rng.random(6000) < p).astype(int)
        cal = stats.calibration(y, p)
        self.assertAlmostEqual(cal["slope"], 1.0, delta=0.15)
        self.assertAlmostEqual(cal["intercept"], 0.0, delta=0.1)

    def test_e_value(self):
        self.assertAlmostEqual(stats.e_value(2.0, common_outcome=False), 2 + math.sqrt(2))
        self.assertAlmostEqual(stats.e_value(0.5, common_outcome=False), stats.e_value(2.0, common_outcome=False))
        self.assertAlmostEqual(stats.e_value(1.0), 1.0)

    def test_kmeans_ari_silhouette(self):
        rng = np.random.default_rng(3)
        X = np.vstack([rng.normal(c, 0.3, (60, 2)) for c in (0, 4, 8)])
        truth = np.repeat([0, 1, 2], 60)
        lab, cen, _ = stats.kmeans(X, 3, seed=0)
        self.assertAlmostEqual(stats.ari(truth, lab), 1.0)
        self.assertGreater(stats.silhouette(X, lab), 0.7)
        self.assertAlmostEqual(stats.ari([0, 0, 1, 1], [1, 1, 0, 0]), 1.0)

    def test_median_iqr(self):
        self.assertEqual(stats.median_iqr([1, 2, 3, 4, 5]), (3.0, 2.0, 4.0))
        self.assertEqual(stats.median_iqr([]), (None, None, None))


if __name__ == "__main__":
    unittest.main()
