package org.icu.sepsis.research.toolkit;

import static org.icu.sepsis.research.toolkit.Data.*;

import java.util.*;

/** Direction 3: SOFA vs SOFA-2 (and APACHE II) discrimination and calibration for ICU death. */
public final class Scores {
  static final int MIN_EVENTS = 20;
  static final Map<String, String> FLAG = Map.of("resp_support_unknown", "未导出呼吸支持，呼吸分项封顶 2 分",
      "vaso_dose_unknown", "未导出升压药剂量，心血管按最低剂量档");
  static final Map<String, String> LABEL = Map.of("sofa2", "SOFA-2", "sofa", "SOFA（重算）", "sofa2_partial", "SOFA-2（≥4 分项）",
      "sofaSource", "SOFA（系统评分表）", "apache2", "APACHE II");

  private Scores() {}

  private static int[] ys(List<Map<String, Object>> sub, String k) { return sub.stream().mapToInt(d -> ((Number) d.get(k)).intValue()).toArray(); }
  private static double[] xs(List<Map<String, Object>> sub, String k) { return sub.stream().mapToDouble(d -> num(d.get(k))).toArray(); }

  public static Map<String, Object> run(List<Map<String, Object>> cohort) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Map<String, Object> r : cohort) if (truthy(r.get("t0Available")) && get(r, "outcome.death") != null) rows.add(r);
    if (rows.isEmpty()) return Result.of("no_data", "无可用结局（dischargedType 缺失）", null);
    List<Map<String, Object>> data = new ArrayList<>();
    Map<String, Integer> flags = new LinkedHashMap<>(), missing = new LinkedHashMap<>();
    for (Map<String, Object> r : rows) {
      Map<String, Object> w = map(r.get("worst24"));
      Sofa.Score c1 = Sofa.classic(w), c2 = Sofa.sofa2(w);
      for (String f : c2.flags) inc(flags, f);
      c2.components.forEach((k, v) -> { if (v == null) inc(missing, k); });
      Object qc = get(r, "outcome.deathQc");
      data.add(m("y", num(get(r, "outcome.death")).intValue(), "sofa", Sofa.total(c1.components), "sofa2", Sofa.total(c2.components),
          "sofa2_partial", Sofa.total(c2.components, 4), "sofaSource", num(r, "scores.sofaMax24"), "apache2", num(r, "scores.apache2First24"),
          "calDead", num(r, "scores.apache2CalDead"), "yQc", qc == null ? null : num(qc).intValue()));
    }
    int deaths = data.stream().mapToInt(d -> (Integer) d.get("y")).sum();
    Map<String, Object> miss = new LinkedHashMap<>();
    for (String k : Sofa.COMPONENTS) miss.put(k, missing.getOrDefault(k, 0) / (double) rows.size());
    Map<String, Object> auroc = new LinkedHashMap<>(), compare = new LinkedHashMap<>();
    Map<String, Object> out = m("n", rows.size(), "deaths", deaths, "componentMissing", miss, "flags", flags, "auroc", auroc, "compare", compare);
    int qcDeaths = data.stream().mapToInt(d -> d.get("yQc") == null ? 0 : (Integer) d.get("yQc")).sum();
    out.put("deathsQc", qcDeaths);
    List<String> lines = new ArrayList<>();
    lines.add(f("有结局病例 %d 例，死亡 %d 例（科研口径，仅“死亡”；质控口径另含非医嘱离院，共 %d 例）。", rows.size(), deaths, qcDeaths));
    boolean ok = false;
    for (String name : List.of("sofa2", "sofa", "sofa2_partial", "sofaSource", "apache2")) {
      List<Map<String, Object>> sub = new ArrayList<>();
      for (Map<String, Object> d : data) if (d.get(name) != null) sub.add(d);
      int ev = Arrays.stream(ys(sub, "y")).sum();
      if (ev < MIN_EVENTS || sub.size() - ev < MIN_EVENTS) {
        auroc.put(name, m("n", sub.size(), "events", ev, "status", "insufficient"));
        continue;
      }
      Double[] a = Stats.aurocCi(ys(sub, "y"), xs(sub, name));
      auroc.put(name, m("n", sub.size(), "events", ev, "auc", a));
      ok = true;
      lines.add(f("%s AUROC %.3f（%.3f–%.3f），n=%d。", LABEL.get(name), a[0], a[1], a[2], sub.size()));
    }
    for (String other : List.of("sofa", "sofaSource", "apache2")) {
      List<Map<String, Object>> sub = new ArrayList<>();
      for (Map<String, Object> d : data) if (d.get("sofa2") != null && d.get(other) != null) sub.add(d);
      int ev = Arrays.stream(ys(sub, "y")).sum();
      if (ev >= MIN_EVENTS && sub.size() - ev >= MIN_EVENTS) {
        double[] c = Stats.delongCompare(ys(sub, "y"), xs(sub, "sofa2"), xs(sub, other));
        if (c != null) {
          compare.put("sofa2_vs_" + other, m("n", sub.size(), "auc1", c[0], "auc2", c[1], "diff", c[2], "p", c[3]));
          lines.add(f("SOFA-2 对比 %s：差值 %+.3f，DeLong P=%s（同一批 %d 例）。", LABEL.get(other), c[2], g(c[3], 3), sub.size()));
        }
      }
    }
    // SMR is a quality-control indicator: uses the QC death definition (死亡 + 非医嘱离院).
    List<Map<String, Object>> sub = new ArrayList<>();
    for (Map<String, Object> d : data) if (d.get("calDead") != null && d.get("yQc") != null) sub.add(d);
    int ev = Arrays.stream(ys(sub, "yQc")).sum();
    if (ev >= MIN_EVENTS && sub.size() - ev >= MIN_EVENTS) {
      double[] y = Arrays.stream(ys(sub, "yQc")).asDoubleStream().toArray(), p = xs(sub, "calDead");
      Map<String, Object> cal = Stats.calibration(y, p);
      double exp = Arrays.stream(p).sum();
      Double smr = exp != 0 ? ev / exp : null;
      cal.put("smr", smr);
      cal.put("smrCI", smr != null && smr != 0 ? tuple(smr * Math.exp(-1.96 / Math.sqrt(ev)), smr * Math.exp(1.96 / Math.sqrt(ev))) : null);
      out.put("apacheCalibration", cal);
      if (smr != null)
        lines.add(f("APACHE II 预计病死率（质控口径：死亡+非医嘱离院 %d 例）：SMR %.2f，校准斜率 %.2f，Brier %.3f。", ev, smr, cal.get("slope"), cal.get("brier")));
    }
    if (!flags.isEmpty()) {
      StringJoiner j = new StringJoiner("；");
      flags.forEach((k, v) -> { if (FLAG.containsKey(k)) j.add(FLAG.get(k) + " " + v + " 例"); });
      lines.add("限制：" + j + "。");
    }
    return Result.of(ok ? "ok" : "insufficient", lines, out);
  }
}
