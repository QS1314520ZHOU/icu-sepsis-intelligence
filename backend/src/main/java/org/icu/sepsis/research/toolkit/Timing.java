package org.icu.sepsis.research.toolkit;

import static org.icu.sepsis.research.toolkit.Data.*;

import java.util.*;
import java.util.function.Function;

/**
 * Direction 4: antibiotic timing / early fluids vs death, with a 3h landmark against immortal-time bias.
 * Observational association only (confounding by indication remains); E-values quantify robustness.
 */
public final class Timing {
  static final int LANDMARK_MIN = 180, EPV = 10;
  static final List<String> COV_NAMES = List.of("年龄(每10岁)", "APACHE II(每5分)", "初始乳酸", "MAP最低(每10)", "3h内升压药");
  static final Map<String, String> TITLE = Map.of("abxCategory", "抗菌药时机分组", "abxPerHour", "抗菌药每小时延迟", "fluid3h", "3h 补液≥1500ml");
  static final String ABX_GT3 = "抗菌药 >3h（对比≤1h）";

  private Timing() {}

  static double[] covariates(Map<String, Object> r) {
    Double age = num(r, "ageYears"), sev = num(r, "scores.apache2First24"), lac = num(r, "timing.lactateInitial"), mp = num(r, "timing.mapMin6h"),
        vaso = num(r, "timing.vasoFirstMin");
    if (age == null || sev == null || lac == null || mp == null) return null;
    return new double[] {age / 10.0, sev / 5.0, Math.min(lac, 15.0), mp / 10.0, vaso != null && vaso <= LANDMARK_MIN ? 1.0 : 0.0};
  }

  static List<Map<String, Object>> cohortAtLandmark(List<Map<String, Object>> cohort) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> r : cohort) {
      if (!"septic_shock".equals(str(r, "icu05.status")) || get(r, "outcome.death") == null) continue;
      Double leave = num(r, "outcome.icuOutMinFromT0");
      if (leave != null && leave <= LANDMARK_MIN) continue; // died or left before the landmark: excluded to avoid immortal-time bias
      out.add(r);
    }
    return out;
  }

  static Map<String, Object> fit(List<Map<String, Object>> rows, Function<Map<String, Object>, double[]> exposure, List<String> names) {
    List<double[]> x = new ArrayList<>();
    List<Double> y = new ArrayList<>();
    for (Map<String, Object> r : rows) {
      double[] e = exposure.apply(r), c = covariates(r);
      if (e == null || c == null) continue;
      double[] row = new double[1 + e.length + c.length];
      row[0] = 1.0;
      System.arraycopy(e, 0, row, 1, e.length);
      System.arraycopy(c, 0, row, 1 + e.length, c.length);
      x.add(row);
      y.add(num(get(r, "outcome.death")));
    }
    int k = names.size() + COV_NAMES.size(), events = (int) y.stream().mapToDouble(Double::doubleValue).sum();
    int ev = Math.min(events, y.size() - events);
    if (y.isEmpty() || ev < EPV * k) return m("status", "insufficient", "n", y.size(), "events", events, "needEvents", EPV * k);
    Stats.Fit fit = Stats.logistic(x.toArray(new double[0][]), y.stream().mapToDouble(Double::doubleValue).toArray());
    if (!fit.converged) return m("status", "insufficient", "n", y.size(), "events", events, "reason", "模型未收敛/分离");
    Map<String, Object> terms = new LinkedHashMap<>();
    List<String> all = new ArrayList<>(names);
    all.addAll(COV_NAMES);
    for (int i = 1; i <= all.size(); i++) {
      double[] o = Stats.oddsRatio(fit.beta[i], fit.se[i]);
      terms.put(all.get(i - 1), m("or", o[0], "lo", o[1], "hi", o[2], "eValue", Stats.eValue(o[0], (double) events / y.size() > 0.15)));
    }
    return m("status", "ok", "n", y.size(), "events", events, "terms", terms);
  }

  static String abxCategory(Map<String, Object> r) {
    Double t = num(r, "timing.abxFirstMin");
    if (t == null || t > 24 * 60) return "未用或>24h";
    return t <= 60 ? "≤1h" : t <= 180 ? "1–3h" : ">3h";
  }

  public static Map<String, Object> run(List<Map<String, Object>> cohort) {
    List<Map<String, Object>> rows = cohortAtLandmark(cohort);
    if (rows.isEmpty()) return Result.of("no_data", "无符合条件的感染性休克病例（需结局与 T0）", null);
    Map<String, Object> crude = new LinkedHashMap<>();
    for (String cat : List.of("≤1h", "1–3h", ">3h", "未用或>24h")) {
      int n = 0, d = 0;
      for (Map<String, Object> r : rows) if (abxCategory(r).equals(cat)) { n++; d += num(get(r, "outcome.death")).intValue(); }
      crude.put(cat, m("n", n, "death", Stats.wilson(d, n)));
    }
    Function<Map<String, Object>, double[]> abxDummies = r -> {
      String c = abxCategory(r);
      return c.equals("未用或>24h") ? null : new double[] {c.equals("1–3h") ? 1.0 : 0.0, c.equals(">3h") ? 1.0 : 0.0};
    };
    Function<Map<String, Object>, double[]> abxHours = r -> {
      Double t = num(r, "timing.abxFirstMin");
      return t == null || t > 24 * 60 ? null : new double[] {Math.max(t, 0) / 60.0};
    };
    Function<Map<String, Object>, double[]> fluid = r -> {
      Double mp = num(r, "timing.mapMin6h"), lmax = num(r, "timing.lactateMax6h"), ml = num(r, "timing.fluidMl3h");
      boolean trig = (mp != null && mp < org.icu.sepsis.sepsis.Icu05Rules.MAP_LOW) || (lmax == null ? 0 : lmax) >= 4;
      if (!trig || truthy(get(r, "timing.fluidIncomplete3h")) || ml == null) return null;
      return new double[] {ml >= 1500 ? 1.0 : 0.0};
    };
    Map<String, Object> models = new LinkedHashMap<>();
    models.put("abxCategory", fit(rows, abxDummies, List.of("抗菌药 1–3h（对比≤1h）", ABX_GT3)));
    models.put("abxPerHour", fit(rows, abxHours, List.of("抗菌药每延迟 1 小时")));
    models.put("fluid3h", fit(rows, fluid, List.of("3h 补液≥1500ml（触发病例）")));
    List<String> lines = new ArrayList<>();
    lines.add(f("3h 存活且仍在 ICU 的感染性休克病例 %d 例（地标法，排除 3h 内死亡或转出）。", rows.size()));
    StringJoiner cj = new StringJoiner("；");
    crude.forEach((k, v) -> {
      Map<String, Object> mv = map(v);
      Double p = ((Double[]) mv.get("death"))[0];
      cj.add(f("%s %d 例 %s", k, mv.get("n"), p == null ? "—" : f("%.1f%%", p * 100)));
    });
    lines.add("粗死亡率：" + cj + "。");
    boolean any = false;
    for (Map.Entry<String, Object> e : models.entrySet()) {
      Map<String, Object> mo = map(e.getValue());
      if (!"ok".equals(mo.get("status"))) {
        lines.add(f("%s：样本不足（%s 个结局，需 ≥%s），不估计。", TITLE.get(e.getKey()), mo.getOrDefault("events", 0), mo.getOrDefault("needEvents", "—")));
        continue;
      }
      any = true;
      for (Map.Entry<String, Object> t : map(mo.get("terms")).entrySet()) {
        if (COV_NAMES.contains(t.getKey())) continue;
        Map<String, Object> tv = map(t.getValue());
        lines.add(f("%s：调整 OR %.2f（%.2f–%.2f），E 值 %.2f。", t.getKey(), tv.get("or"), tv.get("lo"), tv.get("hi"), tv.get("eValue")));
      }
    }
    lines.add("调整变量：年龄、APACHE II、初始乳酸、MAP 最低值、3h 内升压药；观察性关联，不能推断因果。");
    return Result.of(any ? "ok" : "insufficient", lines, m("landmarkCohort", rows.size(), "crude", crude, "models", models, "z", Stats.Z));
  }
}
