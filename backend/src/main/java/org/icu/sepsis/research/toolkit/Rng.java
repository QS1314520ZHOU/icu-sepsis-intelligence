package org.icu.sepsis.research.toolkit;

import java.util.Random;

/** Seeded random draws used by the synthetic generator and resampling (not numpy-identical streams). */
public final class Rng {
  private final Random r;

  public Rng(long seed) { r = new Random(seed * 0x9E3779B97F4A7C15L + 0x2545F4914F6CDD1DL); }

  public double random() { return r.nextDouble(); }
  public double normal(double mu, double sd) { return mu + sd * r.nextGaussian(); }
  public double uniform(double a, double b) { return a + (b - a) * r.nextDouble(); }
  public double exponential(double scale) { return -scale * Math.log(1 - r.nextDouble()); }
  public double lognormal(double mu, double sigma) { return Math.exp(mu + sigma * r.nextGaussian()); }
  /** Integer in [lo, hi). */
  public int integers(int lo, int hi) { return lo + r.nextInt(hi - lo); }

  /** Index drawn with probabilities p. */
  public int choice(double[] p) {
    double u = r.nextDouble(), c = 0;
    for (int i = 0; i < p.length; i++) { c += p[i]; if (u < c) return i; }
    return p.length - 1;
  }

  public int[] choiceReplace(int n, int size) {
    int[] out = new int[size];
    for (int i = 0; i < size; i++) out[i] = r.nextInt(n);
    return out;
  }

  public int[] choiceNoReplace(int n, int size) {
    int[] a = new int[n];
    for (int i = 0; i < n; i++) a[i] = i;
    for (int i = 0; i < size; i++) { int j = i + r.nextInt(n - i); int t = a[i]; a[i] = a[j]; a[j] = t; }
    return java.util.Arrays.copyOf(a, size);
  }
}
