package org.icu.sepsis.research.toolkit;

import java.util.*;

/** Dependency-free statistics used by the analyses. */
public final class Stats {
  public static final double Z = 1.959963984540054;

  private Stats() {}

  public static double normCdf(double x) { return 0.5 * (1.0 + erf(x / Math.sqrt(2.0))); }

  /** erf with ~1e-15 accuracy (series for small |x|, continued fraction otherwise). */
  public static double erf(double x) {
    if (x < 0) return -erf(-x);
    if (x < 2.5) {
      double sum = x, term = x, x2 = x * x;
      for (int n = 1; n < 200; n++) {
        term *= -x2 / n;
        double add = term / (2 * n + 1);
        sum += add;
        if (Math.abs(add) < 1e-17 * Math.abs(sum)) break;
      }
      return 2 / Math.sqrt(Math.PI) * sum;
    }
    if (x > 6) return 1.0;
    // erfc continued fraction (Lentz)
    double tiny = 1e-300, f = x, c = x, d = 0;
    for (int n = 1; n < 300; n++) {
      double an = n / 2.0;
      d = x + an * d; d = Math.abs(d) < tiny ? tiny : d; d = 1 / d;
      c = x + an / c; c = Math.abs(c) < tiny ? tiny : c;
      double delta = c * d;
      f *= delta;
      if (Math.abs(delta - 1) < 1e-16) break;
    }
    return 1 - Math.exp(-x * x) / Math.sqrt(Math.PI) / f;
  }

  /** Wilson score interval. Returns [p, lo, hi]; all null when n == 0. */
  public static Double[] wilson(int k, int n) {
    if (n <= 0) return new Double[] {null, null, null};
    double z = Z, p = (double) k / n, d = 1 + z * z / n, c = (p + z * z / (2.0 * n)) / d;
    double h = z * Math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / d;
    return new Double[] {p, Math.max(0.0, c - h), Math.min(1.0, c + h)};
  }

  /** numpy-style linear percentile on a sorted copy. */
  public static double percentile(double[] a, double q) {
    double[] s = a.clone();
    Arrays.sort(s);
    return percentileSorted(s, q);
  }

  static double percentileSorted(double[] s, double q) {
    double pos = q / 100.0 * (s.length - 1);
    int lo = (int) Math.floor(pos), hi = Math.min(lo + 1, s.length - 1);
    return s[lo] + (s[hi] - s[lo]) * (pos - lo);
  }

  public static double median(double[] a) { return percentile(a, 50); }

  /** [median, q1, q3]; nulls when empty. Null / NaN values are dropped. */
  public static Double[] medianIqr(Collection<? extends Number> x) {
    double[] a = x.stream().filter(Objects::nonNull).mapToDouble(Number::doubleValue).filter(v -> !Double.isNaN(v)).toArray();
    if (a.length == 0) return new Double[] {null, null, null};
    Arrays.sort(a);
    return new Double[] {percentileSorted(a, 50), percentileSorted(a, 25), percentileSorted(a, 75)};
  }

  static double[] midrank(double[] x) {
    int n = x.length;
    Integer[] order = new Integer[n];
    for (int i = 0; i < n; i++) order[i] = i;
    Arrays.sort(order, Comparator.comparingDouble(i -> x[i]));
    double[] out = new double[n];
    int i = 0;
    while (i < n) {
      int j = i;
      while (j < n && x[order[j]] == x[order[i]]) j++;
      double r = 0.5 * (i + j - 1) + 1;
      for (int t = i; t < j; t++) out[order[t]] = r;
      i = j;
    }
    return out;
  }

  public static Double auroc(int[] y, double[] s) {
    int m = 0;
    for (int v : y) m += v;
    int n = y.length - m;
    if (m == 0 || n == 0) return null;
    double[] r = midrank(s);
    double sum = 0;
    for (int i = 0; i < y.length; i++) if (y[i] == 1) sum += r[i];
    return (sum - m * (m + 1) / 2.0) / ((double) m * n);
  }

  /** Fast DeLong (Sun &amp; Xu 2014). Returns {aucs, cov} or null when fewer than 2 per class. */
  static double[][][] delong(int[] y, double[]... scores) {
    int m = 0;
    for (int v : y) m += v;
    int n = y.length - m;
    if (m < 2 || n < 2) return null;
    int k = scores.length;
    double[] aucs = new double[k];
    double[][] v10 = new double[k][m], v01 = new double[k][n];
    for (int i = 0; i < k; i++) {
      double[] x = new double[m], z = new double[n], all = new double[m + n];
      int a = 0, b = 0;
      for (int j = 0; j < y.length; j++) if (y[j] == 1) x[a++] = scores[i][j]; else z[b++] = scores[i][j];
      System.arraycopy(x, 0, all, 0, m);
      System.arraycopy(z, 0, all, m, n);
      double[] tx = midrank(x), tz = midrank(z), tall = midrank(all);
      double s = 0;
      for (int j = 0; j < m; j++) s += tall[j];
      aucs[i] = (s - m * (m + 1) / 2.0) / ((double) m * n);
      for (int j = 0; j < m; j++) v10[i][j] = (tall[j] - tx[j]) / n;
      for (int j = 0; j < n; j++) v01[i][j] = 1.0 - (tall[m + j] - tz[j]) / m;
    }
    double[][] c10 = cov(v10), c01 = cov(v01), cov = new double[k][k];
    for (int i = 0; i < k; i++) for (int j = 0; j < k; j++) cov[i][j] = c10[i][j] / m + c01[i][j] / n;
    return new double[][][] {{aucs}, cov};
  }

  /** Row-variable covariance with ddof = 1. */
  static double[][] cov(double[][] v) {
    int k = v.length, n = v[0].length;
    double[] mean = new double[k];
    for (int i = 0; i < k; i++) { for (double x : v[i]) mean[i] += x; mean[i] /= n; }
    double[][] c = new double[k][k];
    for (int i = 0; i < k; i++) for (int j = i; j < k; j++) {
      double s = 0;
      for (int t = 0; t < n; t++) s += (v[i][t] - mean[i]) * (v[j][t] - mean[j]);
      c[i][j] = c[j][i] = s / (n - 1);
    }
    return c;
  }

  public static Double[] aurocCi(int[] y, double[] s) {
    double[][][] d = delong(y, s);
    if (d == null) return new Double[] {null, null, null};
    double a = d[0][0][0], se = Math.sqrt(Math.max(d[1][0][0], 0.0));
    return new Double[] {a, Math.max(0.0, a - Z * se), Math.min(1.0, a + Z * se)};
  }

  /** Two correlated AUROCs. Returns {auc1, auc2, diff, p} or null. */
  public static double[] delongCompare(int[] y, double[] s1, double[] s2) {
    double[][][] d = delong(y, s1, s2);
    if (d == null) return null;
    double[][] c = d[1];
    double var = c[0][0] + c[1][1] - 2 * c[0][1], diff = d[0][0][0] - d[0][0][1];
    double p = var <= 0 ? 1.0 : 2 * (1 - normCdf(Math.abs(diff) / Math.sqrt(var)));
    return new double[] {d[0][0][0], d[0][0][1], diff, p};
  }

  /** Average precision; ties broken conservatively (negatives first). */
  public static Double auprc(int[] y, double[] s) {
    int pos = 0;
    for (int v : y) pos += v;
    if (pos == 0) return null;
    Integer[] order = new Integer[y.length];
    for (int i = 0; i < y.length; i++) order[i] = i;
    Arrays.sort(order, (a, b) -> s[a] != s[b] ? Double.compare(s[b], s[a]) : Integer.compare(y[a], y[b]));
    double tp = 0, sum = 0;
    for (int i = 0; i < order.length; i++) {
      int yy = y[order[i]];
      tp += yy;
      if (yy == 1) sum += tp / (i + 1);
    }
    return sum / pos;
  }

  public static double brier(double[] y, double[] p) {
    double s = 0;
    for (int i = 0; i < y.length; i++) s += (p[i] - y[i]) * (p[i] - y[i]);
    return s / y.length;
  }

  /** Logistic fit result. */
  public static final class Fit {
    public final double[] beta, se;
    public final boolean converged;
    Fit(double[] beta, double[] se, boolean converged) { this.beta = beta; this.se = se; this.converged = converged; }
  }

  public static Fit logistic(double[][] x, double[] y) { return logistic(x, y, 0.0, null); }

  /** IRLS logistic regression; L2 penalty not applied to column 0; SE from the penalised information. */
  public static Fit logistic(double[][] x, double[] y, double l2, double[] offset) {
    int n = x.length, k = x[0].length;
    double[] beta = new double[k], pen = new double[k];
    Arrays.fill(pen, l2);
    pen[0] = 0;
    boolean converged = false;
    for (int it = 0; it < 100; it++) {
      double[] p = probs(x, beta, offset);
      double[] grad = new double[k];
      double[][] h = new double[k][k];
      for (int i = 0; i < n; i++) {
        double w = Math.max(p[i] * (1 - p[i]), 1e-10), r = y[i] - p[i];
        for (int a = 0; a < k; a++) {
          grad[a] += x[i][a] * r;
          double wa = w * x[i][a];
          for (int b = a; b < k; b++) h[a][b] += wa * x[i][b];
        }
      }
      for (int a = 0; a < k; a++) { grad[a] -= pen[a] * beta[a]; h[a][a] += pen[a]; for (int b = 0; b < a; b++) h[a][b] = h[b][a]; }
      double[] step = Linalg.solve(h, grad);
      double mx = 0;
      for (int a = 0; a < k; a++) { beta[a] += step[a]; mx = Math.max(mx, Math.abs(step[a])); }
      if (mx < 1e-8) { converged = true; break; }
    }
    double[] p = probs(x, beta, offset);
    double[][] h = new double[k][k];
    for (int i = 0; i < n; i++) {
      double w = Math.max(p[i] * (1 - p[i]), 1e-10);
      for (int a = 0; a < k; a++) for (int b = 0; b < k; b++) h[a][b] += w * x[i][a] * x[i][b];
    }
    for (int a = 0; a < k; a++) h[a][a] += pen[a];
    double[] se = new double[k];
    double[][] inv = Linalg.inverse(h);
    for (int a = 0; a < k; a++) se[a] = inv == null ? Double.NaN : Math.sqrt(Math.max(inv[a][a], 0));
    boolean separated = false;
    for (double b : beta) separated |= Math.abs(b) > 15;
    return new Fit(beta, se, converged && !separated);
  }

  private static double[] probs(double[][] x, double[] beta, double[] off) {
    double[] p = new double[x.length];
    for (int i = 0; i < x.length; i++) {
      double eta = off == null ? 0 : off[i];
      for (int a = 0; a < beta.length; a++) eta += x[i][a] * beta[a];
      eta = Math.max(-30, Math.min(30, eta));
      p[i] = 1 / (1 + Math.exp(-eta));
    }
    return p;
  }

  public static double[] predict(double[][] x, double[] beta) { return probs(x, beta, null); }

  /** Calibration-in-the-large (intercept with logit offset) and calibration slope. */
  public static Map<String, Object> calibration(double[] y, double[] p0) {
    int n = y.length;
    double[] p = new double[n], lp = new double[n];
    double[][] one = new double[n][1], two = new double[n][2];
    double obs = 0, exp = 0;
    for (int i = 0; i < n; i++) {
      p[i] = Math.min(1 - 1e-6, Math.max(1e-6, p0[i]));
      lp[i] = Math.log(p[i] / (1 - p[i]));
      one[i][0] = 1; two[i][0] = 1; two[i][1] = lp[i];
      obs += y[i]; exp += p[i];
    }
    Fit ci = logistic(one, y, 0, lp), sl = logistic(two, y);
    return Data.m("intercept", ci.beta[0], "slope", sl.beta[1], "brier", brier(y, p), "observed", obs / n, "expected", exp / n);
  }

  public static double[] oddsRatio(double beta, double se) {
    return new double[] {Math.exp(beta), Math.exp(beta - Z * se), Math.exp(beta + Z * se)};
  }

  /** E-value (VanderWeele &amp; Ding 2017). Common outcomes use RR ≈ sqrt(OR). */
  public static Double eValue(Double or, boolean commonOutcome) {
    if (or == null || or <= 0) return null;
    double rr = commonOutcome ? Math.sqrt(or) : or;
    if (rr < 1) rr = 1 / rr;
    return rr + Math.sqrt(rr * (rr - 1));
  }

  /** Column standardisation (population SD, 0 → 1). Returns {Z, mean, sd} where mean/sd are single rows. */
  public static double[][][] standardize(double[][] x, double[] mean, double[] sd) {
    int n = x.length, d = x[0].length;
    if (mean == null) {
      mean = new double[d];
      for (double[] r : x) for (int j = 0; j < d; j++) mean[j] += r[j];
      for (int j = 0; j < d; j++) mean[j] /= n;
    }
    if (sd == null) {
      sd = new double[d];
      for (double[] r : x) for (int j = 0; j < d; j++) sd[j] += (r[j] - mean[j]) * (r[j] - mean[j]);
      for (int j = 0; j < d; j++) sd[j] = Math.sqrt(sd[j] / n);
    }
    sd = sd.clone();
    for (int j = 0; j < d; j++) if (sd[j] == 0) sd[j] = 1.0;
    double[][] z = new double[n][d];
    for (int i = 0; i < n; i++) for (int j = 0; j < d; j++) z[i][j] = (x[i][j] - mean[j]) / sd[j];
    return new double[][][] {z, {mean}, {sd}};
  }

  /** k-means result. */
  public static final class KMeans {
    public final int[] labels;
    public final double[][] centroids;
    public final double inertia;
    KMeans(int[] labels, double[][] centroids, double inertia) { this.labels = labels; this.centroids = centroids; this.inertia = inertia; }
  }

  public static KMeans kmeans(double[][] x, int k, long seed) { return kmeans(x, k, seed, 10, 300); }

  /** k-means++ with restarts. */
  public static KMeans kmeans(double[][] x, int k, long seed, int nInit, int maxIter) {
    Rng rng = new Rng(seed);
    int n = x.length, d = x[0].length;
    KMeans best = null;
    for (int init = 0; init < nInit; init++) {
      double[][] c = new double[k][];
      c[0] = x[rng.integers(0, n)].clone();
      double[] d2 = new double[n];
      for (int i = 0; i < n; i++) d2[i] = dist2(x[i], c[0]);
      for (int j = 1; j < k; j++) {
        double tot = 0;
        for (double v : d2) tot += v;
        double[] prob = new double[n];
        for (int i = 0; i < n; i++) prob[i] = tot > 0 ? d2[i] / tot : 1.0 / n;
        c[j] = x[rng.choice(prob)].clone();
        for (int i = 0; i < n; i++) d2[i] = Math.min(d2[i], dist2(x[i], c[j]));
      }
      int[] lab = assign(x, c);
      for (int it = 0; it < maxIter; it++) {
        double[][] nc = new double[k][d];
        int[] cnt = new int[k];
        for (int i = 0; i < n; i++) { cnt[lab[i]]++; for (int t = 0; t < d; t++) nc[lab[i]][t] += x[i][t]; }
        boolean close = true;
        for (int j = 0; j < k; j++) {
          if (cnt[j] == 0) nc[j] = c[j].clone();
          else for (int t = 0; t < d; t++) nc[j][t] /= cnt[j];
          for (int t = 0; t < d; t++) close &= Math.abs(nc[j][t] - c[j][t]) <= 1e-8 + 1e-5 * Math.abs(c[j][t]);
        }
        if (close) break;
        c = nc;
        lab = assign(x, c);
      }
      double inertia = 0;
      for (int i = 0; i < n; i++) inertia += dist2(x[i], c[lab[i]]);
      if (best == null || inertia < best.inertia) best = new KMeans(lab, c, inertia);
    }
    return best;
  }

  static double dist2(double[] a, double[] b) {
    double s = 0;
    for (int i = 0; i < a.length; i++) s += (a[i] - b[i]) * (a[i] - b[i]);
    return s;
  }

  public static int[] assign(double[][] x, double[][] c) {
    int[] lab = new int[x.length];
    for (int i = 0; i < x.length; i++) {
      double bd = Double.POSITIVE_INFINITY;
      for (int j = 0; j < c.length; j++) { double v = dist2(x[i], c[j]); if (v < bd) { bd = v; lab[i] = j; } }
    }
    return lab;
  }

  public static Double silhouette(double[][] x, int[] labels, long seed) { return silhouette(x, labels, 2000, seed); }

  public static Double silhouette(double[][] x, int[] labels, int maxN, long seed) {
    if (Arrays.stream(labels).distinct().count() < 2) return null;
    if (x.length > maxN) {
      int[] idx = new Rng(seed).choiceNoReplace(x.length, maxN);
      double[][] xs = new double[maxN][];
      int[] ls = new int[maxN];
      for (int i = 0; i < maxN; i++) { xs[i] = x[idx[i]]; ls[i] = labels[idx[i]]; }
      x = xs; labels = ls;
    }
    int n = x.length, kmax = Arrays.stream(labels).max().getAsInt() + 1;
    int[] cnt = new int[kmax];
    for (int l : labels) cnt[l]++;
    double tot = 0;
    for (int i = 0; i < n; i++) {
      double[] sum = new double[kmax];
      for (int j = 0; j < n; j++) if (j != i) sum[labels[j]] += Math.sqrt(dist2(x[i], x[j]));
      int own = labels[i];
      double a = cnt[own] > 1 ? sum[own] / (cnt[own] - 1) : 0.0, b = Double.POSITIVE_INFINITY;
      for (int c = 0; c < kmax; c++) if (c != own && cnt[c] > 0) b = Math.min(b, sum[c] / cnt[c]);
      double mx = Math.max(a, b);
      tot += mx == 0 ? 0.0 : (b - a) / mx;
    }
    return tot / n;
  }

  /** Adjusted Rand index. */
  public static double ari(int[] a, int[] b) {
    Map<Integer, Integer> ia = new TreeMap<>(), ib = new TreeMap<>();
    for (int v : a) ia.putIfAbsent(v, ia.size());
    for (int v : b) ib.putIfAbsent(v, ib.size());
    double[][] t = new double[ia.size()][ib.size()];
    for (int i = 0; i < a.length; i++) t[ia.get(a[i])][ib.get(b[i])]++;
    double sij = 0, sa = 0, sb = 0;
    double[] col = new double[ib.size()];
    for (double[] row : t) {
      double rs = 0;
      for (int j = 0; j < row.length; j++) { sij += comb(row[j]); rs += row[j]; col[j] += row[j]; }
      sa += comb(rs);
    }
    for (double c : col) sb += comb(c);
    double tot = comb(a.length), exp = tot != 0 ? sa * sb / tot : 0, mx = (sa + sb) / 2;
    return mx == exp ? 1.0 : (sij - exp) / (mx - exp);
  }

  private static double comb(double x) { return x * (x - 1) / 2; }

  /** Least-squares slope (numpy.polyfit degree 1). */
  public static double slope(double[] x, double[] y) {
    double mx = 0, my = 0;
    for (int i = 0; i < x.length; i++) { mx += x[i]; my += y[i]; }
    mx /= x.length; my /= y.length;
    double sxy = 0, sxx = 0;
    for (int i = 0; i < x.length; i++) { sxy += (x[i] - mx) * (y[i] - my); sxx += (x[i] - mx) * (x[i] - mx); }
    return sxx == 0 ? 0.0 : sxy / sxx;
  }

  /** Column medians ignoring NaN (all-NaN column → 0). */
  public static double[] nanMedian(double[][] x) {
    int d = x[0].length;
    double[] med = new double[d];
    for (int j = 0; j < d; j++) {
      final int jj = j;
      double[] col = Arrays.stream(x).mapToDouble(r -> r[jj]).filter(v -> !Double.isNaN(v)).toArray();
      med[j] = col.length == 0 ? 0.0 : median(col);
    }
    return med;
  }
}
