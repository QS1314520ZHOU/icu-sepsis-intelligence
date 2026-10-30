package org.icu.sepsis.research.toolkit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class StatsTest {
  @Test void wilsonKnownValue() {
    Double[] w = Stats.wilson(8, 10);
    assertEquals(0.8, w[0], 1e-12);
    assertEquals(0.4902, w[1], 5e-4);
    assertEquals(0.9433, w[2], 5e-4);
    assertNull(Stats.wilson(0, 0)[0]);
  }

  @Test void aurocWithTiesMatchesMannWhitney() {
    int[] y = {0, 0, 1, 1, 0, 1};
    double[] s = {0.1, 0.4, 0.35, 0.8, 0.4, 0.4};
    double brute = 0;
    int pairs = 0;
    for (int i = 0; i < y.length; i++) for (int j = 0; j < y.length; j++) if (y[i] == 1 && y[j] == 0) {
      brute += s[i] > s[j] ? 1 : s[i] == s[j] ? 0.5 : 0;
      pairs++;
    }
    assertEquals(brute / pairs, Stats.auroc(y, s), 1e-12);
    assertNull(Stats.auroc(new int[] {1, 1}, new double[] {0.2, 0.3}));
  }

  @Test void delongCiCoversAndCompareIsSymmetric() {
    int significant = 0;
    for (int seed = 0; seed < 20; seed++) { // true AUROC difference ≈ 0.12; DeLong should detect it in most samples
      Rng rng = new Rng(seed);
      int n = 400;
      int[] y = new int[n];
      double[] s1 = new double[n], s2 = new double[n];
      for (int i = 0; i < n; i++) { y[i] = rng.integers(0, 2); s1[i] = y[i] + rng.normal(0, 1); s2[i] = y[i] + rng.normal(0, 2); }
      Double[] a = Stats.aurocCi(y, s1);
      assertTrue(a[1] < a[0] && a[0] < a[2]);
      double[] c = Stats.delongCompare(y, s1, s2), c2 = Stats.delongCompare(y, s2, s1);
      assertEquals(c[2], -c2[2], 1e-12);
      assertEquals(c[3], c2[3], 1e-12);
      if (c[3] < 0.05) significant++;
    }
    assertTrue(significant >= 16, "significant in " + significant + "/20");
  }

  @Test void logisticRecoversCoefficients() {
    Rng rng = new Rng(1);
    int n = 5000;
    double[][] x = new double[n][2];
    double[] y = new double[n];
    for (int i = 0; i < n; i++) {
      x[i][0] = 1; x[i][1] = rng.normal(0, 1);
      y[i] = rng.random() < 1 / (1 + Math.exp(-(-0.5 + 1.2 * x[i][1]))) ? 1 : 0;
    }
    Stats.Fit f = Stats.logistic(x, y);
    assertTrue(f.converged);
    assertEquals(1.2, f.beta[1], 0.12);
    assertEquals(-0.5, f.beta[0], 0.1);
    assertTrue(f.se[1] > 0);
  }

  @Test void calibrationOfPerfectModel() {
    Rng rng = new Rng(2);
    int n = 6000;
    double[] p = new double[n], y = new double[n];
    for (int i = 0; i < n; i++) { p[i] = rng.uniform(0.05, 0.6); y[i] = rng.random() < p[i] ? 1 : 0; }
    var cal = Stats.calibration(y, p);
    assertEquals(1.0, (Double) cal.get("slope"), 0.15);
    assertEquals(0.0, (Double) cal.get("intercept"), 0.1);
  }

  @Test void eValue() {
    assertEquals(2 + Math.sqrt(2), Stats.eValue(2.0, false), 1e-12);
    assertEquals(Stats.eValue(2.0, false), Stats.eValue(0.5, false), 1e-12);
    assertEquals(1.0, Stats.eValue(1.0, true), 1e-12);
  }

  @Test void kmeansAriSilhouette() {
    Rng rng = new Rng(3);
    double[][] x = new double[180][2];
    int[] truth = new int[180];
    for (int c = 0; c < 3; c++) for (int i = 0; i < 60; i++) {
      x[c * 60 + i] = new double[] {rng.normal(c * 4, 0.3), rng.normal(c * 4, 0.3)};
      truth[c * 60 + i] = c;
    }
    Stats.KMeans km = Stats.kmeans(x, 3, 0);
    assertEquals(1.0, Stats.ari(truth, km.labels), 1e-12);
    assertTrue(Stats.silhouette(x, km.labels, 0) > 0.7);
    assertEquals(1.0, Stats.ari(new int[] {0, 0, 1, 1}, new int[] {1, 1, 0, 0}), 1e-12);
  }

  @Test void medianIqr() {
    assertArrayEquals(new Double[] {3.0, 2.0, 4.0}, Stats.medianIqr(List.of(1, 2, 3, 4, 5)));
    assertArrayEquals(new Double[] {null, null, null}, Stats.medianIqr(List.of()));
  }

  @Test void erfAndFormatting() {
    assertEquals(0.975, Stats.normCdf(Stats.Z), 1e-12);
    assertEquals(0.8427007929497149, Stats.erf(1.0), 1e-14);
    assertEquals(0.9999779095030014, Stats.erf(3.0), 1e-14);
    assertEquals("0.05", Data.g(0.05, 3));
    assertEquals("0.123", Data.g(0.12345, 3));
    assertEquals("1.23e-05", Data.g(1.2345e-5, 3));
    assertEquals("1", Data.g(1.0, 3));
  }
}
