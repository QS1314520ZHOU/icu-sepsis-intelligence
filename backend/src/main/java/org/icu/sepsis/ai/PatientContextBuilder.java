package org.icu.sepsis.ai;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Turns the rule-engine outputs into numbered, de-identified facts (E1..En). Only clinical values, times and rule
 * states leave this class: never names, MRN, bed, patient/encounter ids or source record ids.
 */
@Component
public class PatientContextBuilder {
  public static class Fact {
    public String id, category, text;
    public Instant time;
    public Map<String, Object> view() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", id); m.put("category", category); m.put("text", text); m.put("time", time);
      return m;
    }
  }

  static final Map<String, String> NAMES = Map.ofEntries(
      Map.entry("MAP", "平均动脉压"), Map.entry("MAP_IBP", "平均动脉压（有创）"), Map.entry("MAP_NIBP", "平均动脉压（无创）"),
      Map.entry("SBP", "收缩压"), Map.entry("SBP_IBP", "收缩压（有创）"), Map.entry("SBP_NIBP", "收缩压（无创）"),
      Map.entry("DBP_IBP", "舒张压（有创）"), Map.entry("DBP_NIBP", "舒张压（无创）"), Map.entry("RR", "呼吸频率"),
      Map.entry("GCS", "GCS"), Map.entry("LACTATE", "乳酸"), Map.entry("HR", "心率"), Map.entry("TEMP", "体温"),
      Map.entry("SPO2", "SpO2"), Map.entry("SOFA", "SOFA（宿主评分）"), Map.entry("VASOPRESSOR_ACTIVE", "升压药在用标记"));
  static final Map<String, String> REASONS = Map.ofEntries(
      Map.entry("DATA_MISSING_LAC", "未取到乳酸"), Map.entry("DATA_MISSING", "未取到记录"), Map.entry("AB_MISSING", "未见抗菌药执行"),
      Map.entry("BC_MISSING", "未见血培养"), Map.entry("BC_AFTER_AB", "抗菌药早于或同时于血培养"), Map.entry("FLUID_MISSING", "未见补液执行"),
      Map.entry("FLUID_INSUFFICIENT", "补液量不足"), Map.entry("FLUID_AMOUNT_INCOMPLETE", "部分补液记录无量"),
      Map.entry("LACTATE_RECHECK_MISSING", "未复测乳酸"), Map.entry("SOURCE_ERROR", "来源读取失败"), Map.entry("MAP_MISSING", "未取到 MAP"),
      Map.entry("WINDOW_OPEN", "时窗未结束"), Map.entry("VASO_STATUS_UNKNOWN", "T0 前升压药是否持续未知"), Map.entry("MAP_NOT_MET", "MAP<70"),
      Map.entry("A1_NOT_MET", "乳酸未测"), Map.entry("NO_ORGAN_DYSFUNCTION", "未见器官功能障碍证据"), Map.entry("NOT_SEPTIC_SHOCK", "未达休克判定"),
      Map.entry("NO_INFECTION_EVIDENCE", "未取得感染证据"), Map.entry("INSUFFICIENT_EVIDENCE", "证据不全"));
  static final Map<String, String> STATES = Map.of("met", "达标", "not_met", "未达标", "unknown", "无法判定", "in_progress", "进行中");

  private final DateTimeFormatter fmt;
  public PatientContextBuilder(AiProperties p) { fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(p.zone); }

  public List<Fact> build(Map<String, Object> review, Map<String, Object> icu05, Instant now) {
    return build(review, icu05, now, true);
  }

  /** windowDetail=false keeps only the per-window states (病情摘要); true adds every ICU-05 item (缺项分析). */
  @SuppressWarnings("unchecked")
  public List<Fact> build(Map<String, Object> review, Map<String, Object> icu05, Instant now, boolean windowDetail) {
    List<Fact> out = new ArrayList<>();
    if (review != null) {
      if (!Boolean.TRUE.equals(review.get("available"))) add(out, "状态", "床旁数据暂不可用：" + str(review.get("message")), null);
      else {
        add(out, "核对", "规则核对状态：" + str(review.get("screeningLabel")) + "（只读证据核对，非诊断）", null);
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> e : list(review.get("latestEvidence"))) {
          String code = str(e.get("code"));
          seen.add(code);
          if ("VASOPRESSOR_ACTIVE".equals(code)) continue;
          Instant t = (Instant) e.get("eventTime");
          StringBuilder s = new StringBuilder(NAMES.getOrDefault(code, code)).append(' ').append(num(e.get("value")));
          if (e.get("unit") != null && !str(e.get("unit")).isEmpty()) s.append(' ').append(e.get("unit"));
          s.append("，").append("reported".equals(e.get("timeBasis")) ? "报告时间 " : "记录时间 ").append(time(t, now));
          if (e.get("sourceSystem") != null) s.append("，来源 ").append(e.get("sourceSystem"));
          if (!str(e.get("qualityNote")).isEmpty()) s.append("；").append(clip(str(e.get("qualityNote")), 60));
          add(out, "观测", s.toString(), t);
        }
        Object q = review.get("qsofa");
        add(out, "评分", q == null ? "qSOFA 无法计算（缺项或血压通道未选定）" : "qSOFA " + q + "/3（仅用新鲜有效记录，缺项不计零）", null);
        Object v = review.get("vasopressorActive");
        add(out, "用药", v == null ? "升压药在用状态：无记录" : Boolean.TRUE.equals(v) ? "升压药在用状态：在用" : "升压药在用状态：记录为未用", null);
        add(out, "感染", "感染线索：" + str(review.get("infectionClue")), null);
        List<String> missing = new ArrayList<>((List<String>) review.getOrDefault("missingFeatures", List.of()));
        Set<String> all = new HashSet<>(seen);
        all.addAll(missing);
        // The generic MAP/SBP entry is redundant once a channel-specific variant is listed or present.
        missing.removeIf(c -> ("MAP".equals(c) || "SBP".equals(c)) && all.stream().anyMatch(x -> x.startsWith(c + "_")));
        if (!missing.isEmpty())
          add(out, "缺项", "近 24h 无有效记录：" + missing.stream().map(c -> NAMES.getOrDefault(c, c)).collect(Collectors.joining("、")), null);
        for (Object w : list2(review.get("sourceWarnings"))) add(out, "数据限制", clip(str(w), 90), null);
        int ctx = 0;
        for (Map<String, Object> e : list(review.get("contextEvidence"))) {
          if (ctx++ >= 3) break;
          add(out, "未确认报告", NAMES.getOrDefault(str(e.get("code")), str(e.get("code"))) + " " + num(e.get("value")) + " " + str(e.get("unit"))
              + "，报告 " + time((Instant) e.get("eventTime"), now) + "（住院归属未确认，不参与判断）", (Instant) e.get("eventTime"));
        }
      }
    }
    if (icu05 != null) {
      if (!Boolean.TRUE.equals(icu05.get("available"))) add(out, "ICU-05", "ICU-05 未判定：" + str(icu05.get("message")), null);
      else {
        Instant t0 = (Instant) icu05.get("t0");
        add(out, "ICU-05", "ICU-05 状态：" + str(icu05.get("statusLabel")) + (t0 == null ? "（未取得 T0）" : "，T0 " + time(t0, now) + "（入科后首条医嘱）"), t0);
        Map<String, Object> pool = (Map<String, Object>) icu05.get("pool");
        if (pool != null && windowDetail) add(out, "ICU-05", "质控分母：" + tri(pool.get("eligible"), "符合", "不符合"), null);
        Map<String, Object> gate = (Map<String, Object>) icu05.get("gate");
        if (gate != null && windowDetail)
          for (String part : List.of("shock", "organ", "infection"))
            for (Map<String, Object> i : list(gate.get(part))) add(out, "ICU-05 门控", item(i, now), (Instant) i.get("time"));
        for (Map<String, Object> w : list(icu05.get("windows"))) {
          String key = str(w.get("key"));
          List<String> reasons = (List<String>) w.getOrDefault("reasons", List.of());
          add(out, "ICU-05 " + key, key + " 时窗：" + STATES.getOrDefault(str(w.get("state")), str(w.get("state")))
              + "，截止 " + time((Instant) w.get("deadline"), now)
              + (reasons.isEmpty() ? "" : "，原因 " + reasons.stream().map(r -> REASONS.getOrDefault(r, r)).collect(Collectors.joining("、"))),
              (Instant) w.get("deadline"));
          if (windowDetail)
            for (Map<String, Object> i : list(w.get("items"))) add(out, "ICU-05 " + key, key + " " + item(i, now), (Instant) i.get("time"));
        }
      }
    }
    return out;
  }

  private String item(Map<String, Object> i, Instant now) {
    String detail = str(i.get("detail"));
    StringBuilder s = new StringBuilder(str(i.get("code"))).append(' ').append(str(i.get("label"))).append('：')
        .append(tri(i.get("value"), "是", "否"));
    if (!detail.isEmpty()) s.append("，").append(clip(detail, 60));
    if (i.get("time") != null) s.append("，").append(time((Instant) i.get("time"), now));
    if (i.get("reason") != null) {
      String label = REASONS.getOrDefault(str(i.get("reason")), str(i.get("reason")));
      if (!detail.contains(label)) s.append("（").append(label).append("）");
    }
    return s.toString();
  }

  private void add(List<Fact> out, String cat, String text, Instant t) {
    Fact f = new Fact(); f.id = "E" + (out.size() + 1); f.category = cat; f.text = scrub(text); f.time = t; out.add(f);
  }
  /** Defensive: long digit runs (MRN / phone / ID numbers) never go to the model, even inside free text. */
  static String scrub(String s) { return s.replaceAll("\\d{6,}", "[已隐去]"); }
  private String time(Instant t, Instant now) {
    if (t == null) return "时间未知";
    long min = Duration.between(t, now).toMinutes();
    String rel = min >= 0 ? (min < 120 ? min + " 分钟前" : (min / 60) + " 小时前")
        : (-min < 120 ? -min + " 分钟后" : (-min / 60) + " 小时后");
    return fmt.format(t) + "（" + rel + "）";
  }
  private static String tri(Object v, String yes, String no) { return Boolean.TRUE.equals(v) ? yes : Boolean.FALSE.equals(v) ? no : "无法判定"; }
  private static String num(Object v) {
    if (!(v instanceof Number)) return v == null ? "—" : v.toString();
    double d = ((Number) v).doubleValue();
    return Math.abs(d - Math.rint(d)) < 1e-9 ? String.valueOf((long) Math.rint(d)) : String.format(Locale.ROOT, "%.1f", d);
  }
  private static String str(Object o) { return o == null ? "" : o.toString(); }
  private static String clip(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }
  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> list(Object o) { return o instanceof List ? (List<Map<String, Object>>) o : List.of(); }
  private static List<?> list2(Object o) { return o instanceof List ? (List<?>) o : List.of(); }
}
