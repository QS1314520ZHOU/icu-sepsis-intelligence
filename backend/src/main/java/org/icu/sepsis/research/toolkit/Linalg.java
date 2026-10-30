package org.icu.sepsis.research.toolkit;

/** Small dense linear algebra (Gaussian elimination with partial pivoting). */
final class Linalg {
  private Linalg() {}

  /** Solves A x = b; a singular system falls back to a tiny ridge (minimum-change step). */
  static double[] solve(double[][] a, double[] b) {
    double[] x = trySolve(a, b);
    if (x != null) return x;
    int k = b.length;
    double[][] r = new double[k][];
    double scale = 0;
    for (int i = 0; i < k; i++) scale = Math.max(scale, Math.abs(a[i][i]));
    for (int i = 0; i < k; i++) { r[i] = a[i].clone(); r[i][i] += 1e-10 * Math.max(scale, 1); }
    x = trySolve(r, b);
    return x != null ? x : new double[k];
  }

  private static double[] trySolve(double[][] a0, double[] b0) {
    int n = b0.length;
    double[][] a = new double[n][];
    for (int i = 0; i < n; i++) a[i] = a0[i].clone();
    double[] b = b0.clone();
    for (int c = 0; c < n; c++) {
      int p = c;
      for (int r = c + 1; r < n; r++) if (Math.abs(a[r][c]) > Math.abs(a[p][c])) p = r;
      if (Math.abs(a[p][c]) < 1e-300) return null;
      double[] t = a[c]; a[c] = a[p]; a[p] = t;
      double tb = b[c]; b[c] = b[p]; b[p] = tb;
      for (int r = c + 1; r < n; r++) {
        double f = a[r][c] / a[c][c];
        if (f == 0) continue;
        for (int j = c; j < n; j++) a[r][j] -= f * a[c][j];
        b[r] -= f * b[c];
      }
    }
    double[] x = new double[n];
    for (int r = n - 1; r >= 0; r--) {
      double s = b[r];
      for (int j = r + 1; j < n; j++) s -= a[r][j] * x[j];
      x[r] = s / a[r][r];
    }
    for (double v : x) if (Double.isNaN(v) || Double.isInfinite(v)) return null;
    return x;
  }

  /** Gauss-Jordan inverse; null when singular. */
  static double[][] inverse(double[][] a0) {
    int n = a0.length;
    double[][] a = new double[n][2 * n];
    for (int i = 0; i < n; i++) { System.arraycopy(a0[i], 0, a[i], 0, n); a[i][n + i] = 1; }
    for (int c = 0; c < n; c++) {
      int p = c;
      for (int r = c + 1; r < n; r++) if (Math.abs(a[r][c]) > Math.abs(a[p][c])) p = r;
      if (Math.abs(a[p][c]) < 1e-300) return null;
      double[] t = a[c]; a[c] = a[p]; a[p] = t;
      double piv = a[c][c];
      for (int j = 0; j < 2 * n; j++) a[c][j] /= piv;
      for (int r = 0; r < n; r++) {
        if (r == c || a[r][c] == 0) continue;
        double f = a[r][c];
        for (int j = 0; j < 2 * n; j++) a[r][j] -= f * a[c][j];
      }
    }
    double[][] inv = new double[n][n];
    for (int i = 0; i < n; i++) System.arraycopy(a[i], n, inv[i], 0, n);
    return inv;
  }
}
