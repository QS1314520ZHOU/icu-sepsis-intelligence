package org.icu.sepsis.research.toolkit;

import static org.icu.sepsis.research.toolkit.Data.*;

import java.util.*;

/** Direction 1: ICU-05 compliance and how often items cannot be determined. */
public final class Audit {
  static final List<String> ITEMS = List.of("A1", "A2", "B1", "B2", "B3", "C1", "C2", "C3");
  static final Set<String> APPLICABLE = Set.of("septic_shock", "suspected_review");

  private Audit() {}

  public static Map<String, Object> run(List<Map<String, Object>> cohort) {
    int n = cohort.size();
    if (n == 0) return Result.of("no_data", "无导出病例", null);
    List<Map<String, Object>> withT0 = new ArrayList<>(), app = new ArrayList<>(), confirmed = new ArrayList<>();
    Map<String, Integer> status = new LinkedHashMap<>(), pool = new LinkedHashMap<>();
    for (Map<String, Object> r : cohort) {
      inc(status, pyStr(get(r, "icu05.status", "unknown")));
      if (!truthy(r.get("t0Available"))) continue;
      withT0.add(r);
      inc(pool, pyStr(get(r, "icu05.poolEligible")));
      String st = str(r, "icu05.status");
      if (st != null && APPLICABLE.contains(st)) { app.add(r); if (st.equals("septic_shock")) confirmed.add(r); }
    }
    Map<String, Object> windows = new LinkedHashMap<>();
    Map<String, Double[]> det = new LinkedHashMap<>(), comp = new LinkedHashMap<>();
    Map<String, Double> nullRate3h = new LinkedHashMap<>();
    for (String key : List.of("1h", "3h", "6h")) {
      Map<String, Integer> states = new LinkedHashMap<>();
      for (Map<String, Object> r : confirmed) inc(states, pyStr(get(r, "icu05.windows." + key + ".state", "unknown")));
      int met = states.getOrDefault("met", 0), nm = states.getOrDefault("not_met", 0), d = met + nm;
      Map<String, Object> items = new LinkedHashMap<>();
      for (String code : ITEMS) {
        int t = 0, fa = 0, nu = 0;
        Map<String, Integer> reasons = new LinkedHashMap<>();
        for (Map<String, Object> r : confirmed) {
          Object it = get(r, "icu05.windows." + key + ".items." + code);
          if (!truthy(it)) continue;
          Map<String, Object> im = map(it);
          Object v = im.get("value");
          if (Boolean.TRUE.equals(v)) t++; else if (Boolean.FALSE.equals(v)) fa++; else nu++;
          if (truthy(im.get("reason"))) inc(reasons, String.valueOf(im.get("reason")));
        }
        int tot = t + fa + nu;
        if (tot > 0) {
          items.put(code, m("true", t, "false", fa, "null", nu, "nullRate", (double) nu / tot, "reasons", mostCommon(reasons)));
          if (key.equals("3h")) nullRate3h.put(code, (double) nu / tot);
        }
      }
      Map<String, Integer> bottleneck = new LinkedHashMap<>();
      for (Map<String, Object> r : confirmed) {
        if (!"not_met".equals(str(r, "icu05.windows." + key + ".state"))) continue;
        for (String code : List.of("A1", "B3", "C3"))
          if (Boolean.FALSE.equals(map(get(r, "icu05.windows." + key + ".items." + code)).get("value"))) inc(bottleneck, code);
      }
      Double[] compliance = Stats.wilson(met, d), determinable = Stats.wilson(d, confirmed.size());
      comp.put(key, compliance); det.put(key, determinable);
      windows.put(key, m("states", states, "compliance", compliance, "determinableRate", determinable, "items", items, "bottleneck", bottleneck));
    }
    Map<String, int[]> months = new TreeMap<>();
    for (Map<String, Object> r : confirmed) {
      String st = str(r, "icu05.windows.3h.state");
      if (!"met".equals(st) && !"not_met".equals(st)) continue;
      Object mo = r.get("admitMonth");
      int[] v = months.computeIfAbsent(truthy(mo) ? String.valueOf(mo) : "unknown", k -> new int[2]);
      v[1]++;
      if (st.equals("met")) v[0]++;
    }
    Map<String, Object> trend = new LinkedHashMap<>();
    months.forEach((k, v) -> trend.put(k, m("met", v[0], "n", v[1], "rate", Stats.wilson(v[0], v[1])[0])));
    Map<String, Integer> failures = new LinkedHashMap<>();
    for (Map<String, Object> r : cohort) for (Object s : list(get(r, "quality.failedSources", List.of()))) inc(failures, String.valueOf(s));
    List<String> lines = new ArrayList<>();
    lines.add(f("导出 %d 例，取得 T0 %d 例；判定为感染性休克 %d 例，疑似待复核 %d 例。", n, withT0.size(), confirmed.size(), app.size() - confirmed.size()));
    for (String key : windows.keySet())
      lines.add(key + "：可判定 " + Result.ci(det.get(key)) + "；可判定病例中达标 " + Result.ci(comp.get(key)) + "。");
    List<Map.Entry<String, Double>> worst = new ArrayList<>(nullRate3h.entrySet());
    worst.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
    if (!worst.isEmpty()) {
      StringJoiner j = new StringJoiner("、");
      for (Map.Entry<String, Double> e : worst.subList(0, Math.min(3, worst.size()))) j.add(e.getKey() + " " + f("%.0f%%", e.getValue() * 100));
      lines.add("3h 无法判定比例最高的项：" + j + "。");
    }
    return Result.of(confirmed.isEmpty() ? "insufficient" : "ok", lines, m("stays", n, "staysWithT0", withT0.size(), "statusCounts", status,
        "pool", pool, "confirmed", confirmed.size(), "suspected", app.size() - confirmed.size(), "windows", windows, "monthly3h", trend,
        "sourceFailures", failures));
  }
}
