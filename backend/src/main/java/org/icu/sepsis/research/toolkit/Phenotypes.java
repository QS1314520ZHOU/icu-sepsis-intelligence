package org.icu.sepsis.research.toolkit;

import static org.icu.sepsis.research.toolkit.Data.*;

import java.util.*;

/** Direction 5: 0–24h trajectory phenotypes of septic shock (k-means on summary features, k by silhouette). */
public final class Phenotypes {
  static final List<String> FEATURES = List.of("MAP均值", "MAP最低", "MAP斜率", "心率均值", "心率最高", "乳酸最高", "乳酸下降比例", "升压药小时数");
  static final int MIN_N = 50, MIN_HOURS = 6;

  private Phenotypes() {}

  static Double[] features(Map<String, Object> r) {
    Map<String, Object> tr = map(r.get("traj"));
    List<Object> mp = list(tr.get("map"));
    List<Double> hs = new ArrayList<>(), vs = new ArrayList<>(), hr = new ArrayList<>(), lac = new ArrayList<>();
    for (int h = 0; h < mp.size(); h++) { Double v = num(mp.get(h)); if (v != null) { hs.add((double) h); vs.add(v); } }
    for (Object v : list(tr.get("hr"))) if (num(v) != null) hr.add(num(v));
    if (vs.size() < MIN_HOURS || hr.size() < MIN_HOURS) return null;
    double[] hsa = hs.stream().mapToDouble(Double::doubleValue).toArray(), vsa = vs.stream().mapToDouble(Double::doubleValue).toArray();
    double slope = new HashSet<>(hs).size() > 1 ? Stats.slope(hsa, vsa) : 0.0;
    for (Object v : list(tr.get("lactate"))) if (num(v) != null) lac.add(num(v));
    Double init = num(r, "timing.lactateInitial"), last = num(r, "timing.lactateLast6h");
    List<Double> lacAll = new ArrayList<>(lac);
    if (init != null) lacAll.add(init);
    Double lmax = lacAll.isEmpty() ? null : Collections.max(lacAll);
    Double clear = init != null && init != 0 && last != null && init > 0 ? (init - last) / init : null;
    double vasoHours = 0;
    for (Object v : list(tr.get("vaso"))) if (num(v) != null && num(v) == 1) vasoHours++;
    double mean = Arrays.stream(vsa).average().orElse(0), min = Arrays.stream(vsa).min().orElse(0);
    double hrMean = hr.stream().mapToDouble(Double::doubleValue).average().orElse(0), hrMax = Collections.max(hr);
    return new Double[] {mean, min, slope, hrMean, hrMax, lmax, clear, vasoHours};
  }

  public static Map<String, Object> run(List<Map<String, Object>> cohort) { return run(cohort, 7, 2, 5, 20); }

  public static Map<String, Object> run(List<Map<String, Object>> cohort, long seed, int kMin, int kMax, int boots) {
    List<Map<String, Object>> rows = new ArrayList<>();
    List<Double[]> feats = new ArrayList<>();
    for (Map<String, Object> r : cohort) {
      if (!"septic_shock".equals(str(r, "icu05.status"))) continue;
      Double[] f = features(r);
      if (f != null) { rows.add(r); feats.add(f); }
    }
    if (rows.size() < MIN_N)
      return Result.of(rows.isEmpty() ? "no_data" : "insufficient", f("轨迹完整的感染性休克病例 %d 例，少于 %d 例，不做分型。", rows.size(), MIN_N), m("n", rows.size()));
    int n = rows.size(), d = FEATURES.size();
    double[][] fm = new double[n][d];
    for (int i = 0; i < n; i++) for (int j = 0; j < d; j++) fm[i][j] = feats.get(i)[j] == null ? Double.NaN : feats.get(i)[j];
    double[] miss = new double[d];
    for (double[] row : fm) for (int j = 0; j < d; j++) if (Double.isNaN(row[j])) miss[j] += 1.0 / n;
    double[] med = Stats.nanMedian(fm);
    for (double[] row : fm) for (int j = 0; j < d; j++) if (Double.isNaN(row[j])) row[j] = med[j];
    double[][] x = Stats.standardize(fm, null, null)[0];
    Map<String, Object> scores = new LinkedHashMap<>();
    Double bestS = null;
    int bestK = 0;
    Stats.KMeans best = null;
    for (int k = kMin; k <= kMax; k++) {
      Stats.KMeans km = Stats.kmeans(x, k, seed);
      int[] sizes = new int[k];
      for (int l : km.labels) sizes[l]++;
      if (Arrays.stream(sizes).min().getAsInt() < 0.05 * n) { scores.put(String.valueOf(k), null); continue; }
      Double s = Stats.silhouette(x, km.labels, seed);
      scores.put(String.valueOf(k), s);
      if (s != null && (bestS == null || s > bestS)) { bestS = s; bestK = k; best = km; }
    }
    if (best == null) return Result.of("insufficient", "各 k 下均有过小的类，不做分型。", m("n", n, "silhouette", scores));
    int k = bestK;
    int[] lab = best.labels;
    Rng rng = new Rng(seed);
    double[] stab = new double[boots];
    for (int b = 0; b < boots; b++) {
      int[] idx = rng.choiceReplace(n, n);
      double[][] xb = new double[n][];
      for (int i = 0; i < n; i++) xb[i] = x[idx[i]];
      Stats.KMeans km = Stats.kmeans(xb, k, rng.integers(0, 1 << 30), 3, 300);
      stab[b] = Stats.ari(lab, Stats.assign(x, km.centroids));
    }
    double stabMed = boots > 0 ? Stats.median(stab) : Double.NaN;
    List<Map<String, Object>> profiles = new ArrayList<>();
    for (int j = 0; j < k; j++) {
      int cnt = 0, deaths = 0, known = 0;
      double[] sum = new double[d];
      for (int i = 0; i < n; i++) {
        if (lab[i] != j) continue;
        cnt++;
        for (int t = 0; t < d; t++) sum[t] += fm[i][t];
        Double dd = num(rows.get(i), "outcome.death");
        if (dd != null) { known++; deaths += dd.intValue(); }
      }
      Map<String, Object> means = new LinkedHashMap<>();
      for (int t = 0; t < d; t++) means.put(FEATURES.get(t), round(sum[t] / cnt, 2));
      profiles.add(m("cluster", j, "n", cnt, "means", means, "death", Stats.wilson(deaths, known)));
    }
    profiles.sort((a, b) -> Double.compare((Double) map(b.get("means")).get("升压药小时数"), (Double) map(a.get("means")).get("升压药小时数")));
    List<String> lines = new ArrayList<>();
    lines.add(f("感染性休克 %d 例按 0–24h 轨迹分为 %d 型（轮廓系数 %.2f；自助法稳定性 ARI 中位数 %.2f）。", n, k, bestS, stabMed));
    for (int i = 0; i < profiles.size(); i++) {
      Map<String, Object> p = profiles.get(i), mm = map(p.get("means"));
      Double dr = ((Double[]) p.get("death"))[0];
      lines.add(f("型 %d（%d 例）：MAP 均值 %.0f，乳酸最高 %.1f，升压药 %.1f 小时，死亡率 %s。", i + 1, p.get("n"), mm.get("MAP均值"), mm.get("乳酸最高"),
          mm.get("升压药小时数"), dr == null ? "—" : f("%.1f%%", dr * 100)));
    }
    if (stabMed < 0.6) lines.add("稳定性 ARI < 0.6，分型不稳定，不宜用于临床解释。");
    Map<String, Object> missing = new LinkedHashMap<>();
    for (int j = 0; j < d; j++) missing.put(FEATURES.get(j), round(miss[j], 3));
    List<Integer> labels = new ArrayList<>();
    for (int l : lab) labels.add(l);
    return Result.of("ok", lines, m("n", n, "k", k, "silhouette", scores, "stabilityARI", stabMed, "profiles", profiles, "featureMissing", missing,
        "labels", labels));
  }
}
