package org.icu.sepsis.research.toolkit;

import static org.icu.sepsis.research.toolkit.Data.*;

import java.util.*;

/**
 * Direction 6: retrospective prediction of vasopressor start within the next 6 hours (hourly landmarks).
 * Temporal split by admission month; L2 logistic regression; compared with a MAP&lt;65 rule.
 * Report-only. Not deployed: a silent run (prospective, no alerts) is required before any clinical use.
 */
public final class Prediction {
  static final int HORIZON_H = 6, FIRST_LANDMARK = 1, LAST_LANDMARK = 18;
  static final List<String> NAMES = List.of("时点", "MAP最近", "MAP近3h最低", "MAP近6h斜率", "心率最近", "心率近3h最高", "乳酸最近", "乳酸缺失", "年龄", "年龄缺失");

  private Prediction() {}

  /** One hourly landmark: hour, label, features (null = missing). */
  public static final class Landmark {
    public final int h, y;
    public final Double[] x;
    Landmark(int h, int y, Double[] x) { this.h = h; this.y = y; this.x = x; }
  }

  private static Double at(List<Object> v, int i) { return i >= 0 && i < v.size() ? num(v.get(i)) : null; }

  private static Double last(List<Object> vals, int h, int look) {
    for (int i = h - 1; i > Math.max(-1, h - 1 - look); i--) { Double v = at(vals, i); if (v != null) return v; }
    return null;
  }

  public static List<Landmark> landmarks(Map<String, Object> r) {
    Map<String, Object> tr = map(r.get("traj"));
    List<Object> mp = list(tr.get("map")), hr = list(tr.get("hr")), lac = list(tr.get("lactate")), vaso = list(tr.get("vaso"));
    List<Landmark> out = new ArrayList<>();
    if (mp.size() < 24) return out;
    Double first = num(r, "timing.vasoFirstMin"), age = num(r, "ageYears"), lacInitMin = num(r, "timing.lactateInitialMin");
    outer:
    for (int h = FIRST_LANDMARK; h <= LAST_LANDMARK; h++) {
      for (int i = 0; i < Math.min(h, vaso.size()); i++) {
        Double v = num(vaso.get(i));
        if (v == null || v == 1) break outer; // running or unknown pressor status before the landmark: not at risk
      }
      if (first != null && first <= h * 60) break;
      Double mLast = last(mp, h, 3);
      if (mLast == null) continue;
      double wmin = Double.POSITIVE_INFINITY;
      for (int i = Math.max(0, h - 3); i < h; i++) { Double v = at(mp, i); if (v != null) wmin = Math.min(wmin, v); }
      List<double[]> pts = new ArrayList<>();
      for (int i = Math.max(0, h - 6); i < h; i++) { Double v = at(mp, i); if (v != null) pts.add(new double[] {i, v}); }
      // Fewer than 3 points: slope is missing (median-imputed later), not "flat".
      Double slope = pts.size() >= 3 ? Stats.slope(pts.stream().mapToDouble(p -> p[0]).toArray(), pts.stream().mapToDouble(p -> p[1]).toArray()) : null;
      Double hLast = last(hr, h, 3), hMax = null;
      for (int i = Math.max(0, h - 3); i < h; i++) { Double v = at(hr, i); if (v != null && (hMax == null || v > hMax)) hMax = v; }
      Double lLast = last(lac, h, 6);
      if (lLast == null && lacInitMin != null && lacInitMin <= h * 60) lLast = num(r, "timing.lactateInitial");
      int y = first != null && h * 60 < first && first <= (h + HORIZON_H) * 60 ? 1 : 0;
      out.add(new Landmark(h, y, new Double[] {(double) h, mLast, wmin, slope, hLast, hMax, lLast, lLast == null ? 1.0 : 0.0, age, age == null ? 1.0 : 0.0}));
    }
    return out;
  }

  private static double[][] matrix(List<Landmark> rows, double[] med) {
    double[][] x = new double[rows.size()][NAMES.size()];
    for (int i = 0; i < rows.size(); i++) for (int j = 0; j < NAMES.size(); j++) x[i][j] = rows.get(i).x[j] == null ? Double.NaN : rows.get(i).x[j];
    for (double[] row : x) for (int j = 0; j < row.length; j++) if (Double.isNaN(row[j])) row[j] = med[j];
    return x;
  }

  private static double[][] withIntercept(double[][] z) {
    double[][] o = new double[z.length][];
    for (int i = 0; i < z.length; i++) { o[i] = new double[z[i].length + 1]; o[i][0] = 1; System.arraycopy(z[i], 0, o[i], 1, z[i].length); }
    return o;
  }

  /** "2024-3" and "2024-03" sort the same; unknown months first. */
  static String monthKey(Object v) {
    if (!truthy(v)) return "";
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{4})\\D?(\\d{1,2})").matcher(String.valueOf(v).trim());
    return m.find() ? m.group(1) + "-" + (m.group(2).length() == 1 ? "0" : "") + m.group(2) : String.valueOf(v);
  }

  public static Map<String, Object> run(List<Map<String, Object>> cohort) { return run(cohort, 11, 200, 0.7); }

  public static Map<String, Object> run(List<Map<String, Object>> cohort, int boots) { return run(cohort, 11, boots, 0.7); }

  public static Map<String, Object> run(List<Map<String, Object>> cohort, long seed, int boots, double trainFrac) {
    List<Map<String, Object>> stays = new ArrayList<>();
    for (Map<String, Object> r : cohort) if (truthy(r.get("t0Available")) && truthy(r.get("traj"))) stays.add(r);
    stays.sort(Comparator.comparing((Map<String, Object> r) -> monthKey(r.get("admitMonth")))
        .thenComparing(r -> String.valueOf(r.get("stayKey"))));
    int cut = (int) (stays.size() * trainFrac);
    List<Map<String, Object>> train = stays.subList(0, cut), test = stays.subList(cut, stays.size());
    List<Landmark> ltr = new ArrayList<>(), lte = new ArrayList<>();
    List<Integer> idTr = new ArrayList<>(), idTe = new ArrayList<>();
    Set<Integer> evTr = new HashSet<>(), evTe = new HashSet<>();
    for (int i = 0; i < train.size(); i++) for (Landmark l : landmarks(train.get(i))) { ltr.add(l); idTr.add(i); if (l.y == 1) evTr.add(i); }
    for (int i = 0; i < test.size(); i++) for (Landmark l : landmarks(test.get(i))) { lte.add(l); idTe.add(i); if (l.y == 1) evTe.add(i); }
    Map<String, Object> ev = m("train", evTr.size(), "test", evTe.size()), lmCounts = m("train", ltr.size(), "test", lte.size());
    if (evTr.size() < 30 || evTe.size() < 15)
      return Result.of(stays.isEmpty() ? "no_data" : "insufficient", f("训练集 %d 例、测试集 %d 例有升压药启动，少于 30/15，不训练模型。", evTr.size(), evTe.size()),
          m("events", ev, "landmarks", lmCounts));
    double[][] rawTr = new double[ltr.size()][NAMES.size()];
    for (int i = 0; i < ltr.size(); i++) for (int j = 0; j < NAMES.size(); j++) rawTr[i][j] = ltr.get(i).x[j] == null ? Double.NaN : ltr.get(i).x[j];
    double[] med = Stats.nanMedian(rawTr);
    double[][] xtr = matrix(ltr, med), xte = matrix(lte, med);
    double[] ytr = ltr.stream().mapToDouble(l -> l.y).toArray(), yte = lte.stream().mapToDouble(l -> l.y).toArray();
    int[] yteI = lte.stream().mapToInt(l -> l.y).toArray();
    double[][][] st = Stats.standardize(xtr, null, null);
    double[][] ztr = withIntercept(st[0]), zte = withIntercept(Stats.standardize(xte, st[1][0], st[2][0])[0]);
    Stats.Fit fit = Stats.logistic(ztr, ytr, 1.0, null);
    double[] ptr = Stats.predict(ztr, fit.beta), pte = Stats.predict(zte, fit.beta);
    double[] posTr = java.util.stream.IntStream.range(0, ptr.length).filter(i -> ytr[i] == 1).mapToDouble(i -> ptr[i]).sorted().toArray();
    double thr = posTr[(int) (0.2 * posTr.length)]; // ~80% sensitivity on the training set
    int nte = pte.length;
    boolean[] flag = new boolean[nte], rule = new boolean[nte];
    double[] ruleScore = new double[nte];
    int flagged = 0, flaggedPos = 0, pos = 0, rulePos = 0, ruleN = 0;
    for (int i = 0; i < nte; i++) {
      flag[i] = pte[i] >= thr;
      rule[i] = xte[i][1] < 65;
      ruleScore[i] = rule[i] ? 1 : 0;
      pos += yteI[i];
      if (flag[i]) { flagged++; flaggedPos += yteI[i]; }
      if (rule[i]) { ruleN++; rulePos += yteI[i]; }
    }
    int sensFlag = 0, sensRule = 0;
    for (int i = 0; i < nte; i++) if (yteI[i] == 1) { if (flag[i]) sensFlag++; if (rule[i]) sensRule++; }
    TreeMap<Integer, List<Integer>> byStay = new TreeMap<>();
    for (int i = 0; i < nte; i++) byStay.computeIfAbsent(idTe.get(i), s -> new ArrayList<>()).add(i);
    Integer[] uniq = byStay.keySet().toArray(new Integer[0]);
    Rng rng = new Rng(seed);
    List<Double> bs = new ArrayList<>();
    for (int b = 0; b < boots; b++) {
      List<Integer> idx = new ArrayList<>();
      for (int pick : rng.choiceReplace(uniq.length, uniq.length)) idx.addAll(byStay.get(uniq[pick]));
      int[] yb = idx.stream().mapToInt(i -> yteI[i]).toArray();
      double[] pb = idx.stream().mapToDouble(i -> pte[i]).toArray();
      Double a = Stats.auroc(yb, pb);
      if (a != null) bs.add(a);
    }
    Double auc = Stats.auroc(yteI, pte);
    List<Double> lead = new ArrayList<>();
    for (Map.Entry<Integer, List<Integer>> e : byStay.entrySet()) {
      boolean anyPos = e.getValue().stream().anyMatch(i -> yteI[i] == 1);
      if (!anyPos) continue;
      Double firstMin = num(test.get(e.getKey()), "timing.vasoFirstMin");
      OptionalInt fl = e.getValue().stream().filter(i -> flag[i]).mapToInt(i -> lte.get(i).h).min();
      if (fl.isPresent() && firstMin != null) lead.add(firstMin / 60.0 - fl.getAsInt());
    }
    Map<String, Object> cal = Stats.calibration(yte, pte);
    double[] bsa = bs.stream().mapToDouble(Double::doubleValue).toArray();
    List<Object> aucCi = bs.isEmpty() ? null : tuple(Stats.percentile(bsa, 2.5), Stats.percentile(bsa, 97.5));
    double prevalence = (double) pos / nte, sensitivity = (double) sensFlag / pos;
    Double ppv = flagged > 0 ? (double) flaggedPos / flagged : null;
    Double perDay = uniq.length > 0 ? (double) flagged / uniq.length / ((double) nte / uniq.length) * 24 : null;
    Double leadMed = lead.isEmpty() ? null : Stats.median(lead.stream().mapToDouble(Double::doubleValue).toArray());
    Double ruleSens = (double) sensRule / pos, rulePpv = ruleN > 0 ? (double) rulePos / ruleN : null;
    Double auprc = Stats.auprc(yteI, pte);
    Map<String, Object> coef = new LinkedHashMap<>();
    coef.put("截距", round(fit.beta[0], 3));
    for (int j = 0; j < NAMES.size(); j++) coef.put(NAMES.get(j), round(fit.beta[j + 1], 3));
    Map<String, Object> res = m("events", ev, "landmarks", lmCounts, "prevalenceTest", prevalence, "auroc", auc, "aurocCI", aucCi, "auprc", auprc,
        "calibration", cal, "threshold", thr, "sensitivity", sensitivity, "ppv", ppv, "alertsPerPatientDay", perDay, "leadHoursMedian", leadMed,
        "ruleMAP65", m("auroc", Stats.auroc(yteI, ruleScore), "sensitivity", ruleSens, "ppv", rulePpv), "coefficients", coef);
    List<String> lines = new ArrayList<>();
    lines.add(f("按入科月份时间切分：训练 %d 例、测试 %d 例；测试集每小时时点 %d 个（升压药启动 %d 例），6h 内升压药启动比例 %.1f%%。", train.size(), test.size(), nte,
        evTe.size(), prevalence * 100));
    lines.add(f("测试集 AUROC %.3f", auc) + (aucCi != null ? f("（按病例自助 95%%CI %.3f–%.3f）", aucCi.get(0), aucCi.get(1)) : "")
        + f("，AUPRC %.3f，校准斜率 %.2f。", auprc, cal.get("slope")));
    lines.add(f("按训练集 80%% 敏感度设阈：测试敏感度 %.0f%%，阳性预测值 %s，约每患者每日 %.1f 次提示；提前量中位数 %s。", sensitivity * 100,
        ppv == null ? "—" : f("%.0f%%", ppv * 100), perDay, leadMed == null ? "—" : f("%.1f h", leadMed)));
    lines.add(f("对照规则 MAP<65：敏感度 %.0f%%，阳性预测值 %s。", ruleSens * 100, rulePpv == null ? "—" : f("%.0f%%", rulePpv * 100)));
    lines.add("仅为回顾性结果；上线前需静默运行（只计算不提醒）并前瞻评估。");
    return Result.of("ok", lines, res);
  }
}
