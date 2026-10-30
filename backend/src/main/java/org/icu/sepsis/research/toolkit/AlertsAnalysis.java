package org.icu.sepsis.research.toolkit;

import static org.icu.sepsis.research.toolkit.Data.*;

import java.util.*;

/** Direction 2: alert handling — burden, false-alarm share, time to review, shift differences, lead time. */
public final class AlertsAnalysis {
  static final List<String> CATEGORIES = List.of("已处理", "已知晓，继续观察", "误报：监测干扰", "误报：数据错误", "其他");

  private AlertsAnalysis() {}

  static String category(Object reason) {
    if (!truthy(reason)) return "未填写";
    String s = String.valueOf(reason);
    for (String c : CATEGORIES) if (s.startsWith(c)) return c;
    return "其他";
  }

  private static boolean isDay(Map<String, Object> a) { double h = num(a.get("hourOfDay")); return h >= 8 && h < 20; }

  private static Double[] reviewTime(List<Map<String, Object>> group) {
    List<Double> out = new ArrayList<>();
    for (Map<String, Object> a : group) {
      Double best = null;
      for (Object h : list(a.get("reviews"))) { Double v = num(map(h).get("min")); if (v != null && (best == null || v < best)) best = v; }
      if (best != null) out.add(best);
    }
    return Stats.medianIqr(out);
  }

  public static Map<String, Object> run(List<Map<String, Object>> alerts, List<Map<String, Object>> cohort) {
    if (alerts.isEmpty()) return Result.of("no_data", "无预警记录（系统上线运行后才会积累）", null);
    int n = alerts.size();
    List<Map<String, Object>> reviewed = new ArrayList<>(), firstReview = new ArrayList<>(), day = new ArrayList<>(), night = new ArrayList<>();
    for (Map<String, Object> a : alerts) {
      if (truthy(a.get("reviews"))) {
        reviewed.add(a);
        Map<String, Object> first = null;
        double fm = 0;
        for (Object h : list(a.get("reviews"))) {
          Double v = num(map(h).get("min"));
          double key = v == null ? 1e12 : v;
          if (first == null || key < fm) { first = map(h); fm = key; }
        }
        firstReview.add(first);
      }
      if (a.get("hourOfDay") != null) (isDay(a) ? day : night).add(a);
    }
    Map<String, Integer> cats = new LinkedHashMap<>();
    List<Double> tReview = new ArrayList<>();
    for (Map<String, Object> h : firstReview) {
      inc(cats, category(h.get("reason")));
      Double v = num(h.get("min"));
      if (v != null) tReview.add(v);
    }
    int falseAlarm = cats.getOrDefault("误报：监测干扰", 0) + cats.getOrDefault("误报：数据错误", 0);
    double bedDays = 0;
    Map<String, Map<String, Object>> keys = new HashMap<>();
    for (Map<String, Object> r : cohort) {
      Double los = num(r, "outcome.icuLosHours");
      bedDays += los == null ? 0 : los;
      keys.put(String.valueOf(r.get("stayKey")), r);
    }
    bedDays /= 24.0;
    List<Double> lead = new ArrayList<>();
    Set<String> actors = new HashSet<>();
    for (Map<String, Object> a : alerts) {
      Map<String, Object> r = keys.get(String.valueOf(a.get("stayKey")));
      Double abx = r == null ? null : num(r, "timing.abxFirstMin"), at = num(a.get("minFromT0"));
      if (r != null && at != null && abx != null && abx >= at) lead.add(abx - at);
      for (Object h : list(a.get("reviews"))) if (truthy(map(h).get("actorKey"))) actors.add(String.valueOf(map(h).get("actorKey")));
    }
    Double[] fa = Stats.wilson(falseAlarm, firstReview.size()), med = Stats.medianIqr(tReview);
    List<String> lines = new ArrayList<>();
    lines.add(f("预警 %d 条，已处置 %d 条（%s）。", n, reviewed.size(), Result.ci(Stats.wilson(reviewed.size(), n))));
    lines.add("误报占已处置的 " + Result.ci(fa) + "。");
    if (med[0] != null) lines.add(f("首次处置用时中位数 %.0f 分钟（IQR %.0f–%.0f）。", med[0], med[1], med[2]));
    if (bedDays > 0) lines.add(f("每 100 ICU 床日 %.1f 条预警（床日取导出病例的 ICU 住院时长合计，近似）。", n / bedDays * 100));
    String status = firstReview.size() >= 30 ? "ok" : "insufficient";
    return Result.of(status, lines, m("alerts", n, "reviewed", reviewed.size(), "categories", cats, "falseAlarm", fa, "reviewMinutes", med,
        "dayReview", reviewTime(day), "nightReview", reviewTime(night), "dayCount", day.size(), "nightCount", night.size(),
        "alertsPer100BedDays", bedDays > 0 ? n / bedDays * 100 : null, "alertToAntibioticMin", Stats.medianIqr(lead),
        "linkedToAntibiotic", lead.size(), "reviewers", actors.size()));
  }
}
