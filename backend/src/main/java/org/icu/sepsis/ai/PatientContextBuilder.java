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
      Map.entry("SPO2", "SpO2"), Map.entry("SOFA", "SOFA（宿主评分）"), Map.entry("VASOPRESSOR_ACTIVE", "升压药在用标记"),
      Map.entry("LACTATE_UNCONFIRMED", "乳酸（有报告但住院归属未确认）"), Map.entry("INFECTION_EVIDENCE", "感染证据"),
      Map.entry("PF_RATIO", "氧合指数 P/F"), Map.entry("PLT", "血小板"), Map.entry("TBIL", "总胆红素"), Map.entry("CREA", "肌酐"),
      Map.entry("URINE_OUTPUT", "尿量"));
  static final Map<String, String> REASONS = Map.ofEntries(
      Map.entry("DATA_MISSING_LAC", "未取到乳酸"), Map.entry("DATA_MISSING", "未取到记录"), Map.entry("AB_MISSING", "未见抗菌药执行"),
      Map.entry("BC_MISSING", "未见血培养"), Map.entry("BC_AFTER_AB", "抗菌药早于或同时于血培养"), Map.entry("FLUID_MISSING", "未见补液执行"),
      Map.entry("FLUID_INSUFFICIENT", "补液量不足"), Map.entry("FLUID_AMOUNT_INCOMPLETE", "部分补液记录无量"),
      Map.entry("LACTATE_RECHECK_MISSING", "未复测乳酸"), Map.entry("SOURCE_ERROR", "来源读取失败或不完整"), Map.entry("K1_WINDOW_UNCONFIRMED", "乳酸取值窗口待确认"), Map.entry("MAP_MISSING", "未取到 MAP"),
      Map.entry("WINDOW_OPEN", "时窗未结束"), Map.entry("VASO_STATUS_UNKNOWN", "T0 前升压药是否持续未知"), Map.entry("MAP_NOT_MET", "MAP≤65"),
      Map.entry("A1_NOT_MET", "乳酸未测"), Map.entry("NO_ORGAN_DYSFUNCTION", "未见器官功能障碍证据"), Map.entry("NOT_SEPTIC_SHOCK", "未达本地质控候选条件"),
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
      Object sourceDiagnosis=review.get("recordedDiagnosis");
      if(sourceDiagnosis instanceof Map){Map<?,?> recorded=(Map<?,?>)sourceDiagnosis;add(out,"源诊断记录",str(recorded.get("text"))+"；来源："+str(recorded.get("source"))+"。原文限定语必须保留；时间未确认，不代表当前确诊，不替代感染证据、评分或 ICU-05 条件。",null);}
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
          if (e.get("sourceSystem") != null) s.append("，来源 ").append(SourceLabels.of(e.get("sourceSystem"), code));
          if (!str(e.get("qualityNote")).isEmpty()) s.append("；").append(clip(str(e.get("qualityNote")), 60));
          add(out, "观测", s.toString(), t);
        }
        Object early=review.get("earlyWarning");if(early instanceof Map){Map<?,?> ew=(Map<?,?>)early;add(out,"早期筛查","bedside 重新计算 MEWS："+ew.get("label")+"；"+ew.get("freshMinutes")+" 分钟内，最大跨度 "+ew.get("maxSkewMinutes")+" 分钟（仅针对除体温外四项）；体温单独允许 "+ew.get("temperatureFreshMinutes")+" 分钟内最近值，沿用体温不能称为当前实测；候选复核阈值 ≥"+ew.get("reviewThreshold")+"，规则 "+ew.get("ruleVersion")+"。缺项不补零，不替代感染证据与床旁复评。",null);Object cs=ew.get("channels");if(cs instanceof List)for(Object c:(List<?>)cs)if(c instanceof Map){Map<?,?> channel=(Map<?,?>)c;add(out,"早期筛查",channel.get("label")+" MEWS 总分 "+Objects.toString(channel.get("total"),"无法判定")+"；全部记录时间跨度秒 "+channel.get("spanSeconds")+"，除体温外四项跨度秒 "+channel.get("coreSpanSeconds")+"；缺口 "+channel.get("reasons"),null);Object components=channel.get("components");if(components instanceof List)for(Object part:(List<?>)components)if(part instanceof Map){Map<?,?> item=(Map<?,?>)part;Object event=item.get("eventTime");add(out,"MEWS 分项",channel.get("label")+" "+item.get("label")+"，分项得分 "+Objects.toString(item.get("points"),"未知")+"，状态 "+org.icu.sepsis.sepsis.MewsScreening.stateLabel(String.valueOf(item.get("state")))+"，数值 "+Objects.toString(item.get("value"),"未提供")+"，记录时间 "+(event instanceof Instant?DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(fmt.getZone()).format((Instant)event):"未知")+"；距评估时点秒 "+item.get("ageSeconds")+"；是否沿用旧体温 "+(Boolean.TRUE.equals(item.get("carriedForward"))?"是":"否")+"；只有 ready 分项可计分，不能补零或把部分合计当完整总分",event instanceof Instant?(Instant)event:null);}}}
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
          add(out, "缺项", "近 24h 无可参与判断的有效记录（不等于未取得报告）：" + missing.stream().map(c -> NAMES.getOrDefault(c, c)).collect(Collectors.joining("、")), null);
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

  static final List<String> TREND_CODES = List.of("MAP_IBP", "MAP_NIBP", "MAP", "SBP_IBP", "SBP_NIBP", "HR", "RR", "SPO2", "TEMP", "GCS",
      "LACTATE", "PF_RATIO", "CREA", "PLT", "TBIL");
  static final Map<String, String> TREND_NAMES = Map.of("PF_RATIO", "氧合指数 P/F", "CREA", "肌酐", "PLT", "血小板", "TBIL", "总胆红素",
      "URINE_OUTPUT", "尿量");

  /** Appends one 【趋势】 fact per parameter: count, first→latest, min/max with time, last-6h mean vs earlier mean; no unverified urine sums. */
  public void trends(List<Fact> out, List<org.icu.sepsis.domain.Domain.Observation> rows, Instant now) {
    Map<String, List<org.icu.sepsis.domain.Domain.Observation>> by = new LinkedHashMap<>();
    for (org.icu.sepsis.domain.Domain.Observation o : rows) {
      if (o == null || o.value == null || o.eventTime == null || o.eventTime.isAfter(now) || o.eventTime.isBefore(now.minus(Duration.ofHours(24))) || !Double.isFinite(o.value) || o.parameterCode == null) continue;
      if ("context_only".equals(o.clinicalUse) || "unconfirmed".equals(o.encounterLinkStatus) || (o.qualityStatus != null && !"valid".equals(o.qualityStatus))) continue;
      if(o.ingestTime!=null&&o.ingestTime.isAfter(now))continue;
      by.computeIfAbsent(o.parameterCode, k -> new ArrayList<>()).add(o);
    }
    int n = 0;
    for (String code : TREND_CODES) {
      List<org.icu.sepsis.domain.Domain.Observation> l = by.get(code);
      if (l == null || l.size() < 2) continue;
      Set<String> units=new HashSet<>();for(var point:l)units.add(Objects.toString(point.unit, "").trim());
      if(units.size()!=1||units.contains("")){add(out,"数据限制",NAMES.getOrDefault(code,code)+" 趋势单位缺失或不一致，未合并计算",null);continue;}
      Map<Instant,Double> atTime=new HashMap<>();boolean conflict=false;for(var point:l){Double prior=atTime.putIfAbsent(point.eventTime,point.value);if(prior!=null&&Double.compare(prior,point.value)!=0)conflict=true;}
      if(conflict){add(out,"数据限制",NAMES.getOrDefault(code,code)+" 同时点存在不同数值，未选择或合并趋势",null);continue;}
      List<org.icu.sepsis.domain.Domain.Observation> unique=new ArrayList<>();Set<Instant> seenTime=new HashSet<>();for(var point:l)if(seenTime.add(point.eventTime))unique.add(point);l=unique;if(l.size()<2)continue;
      l.sort(Comparator.comparing(o -> o.eventTime));
      org.icu.sepsis.domain.Domain.Observation first = l.get(0), last = l.get(l.size() - 1), lo = first, hi = first;
      double recent = 0, earlier = 0; int nr = 0, ne = 0;
      Instant cut = now.minus(Duration.ofHours(6));
      for (org.icu.sepsis.domain.Domain.Observation o : l) {
        if (o.value < lo.value) lo = o;
        if (o.value > hi.value) hi = o;
        if (o.eventTime.isBefore(cut)) { earlier += o.value; ne++; } else { recent += o.value; nr++; }
      }
      String name = NAMES.getOrDefault(code, TREND_NAMES.getOrDefault(code, code));
      String unit = last.unit == null ? "" : " " + last.unit;
      StringBuilder s = new StringBuilder(name).append(" 近 24h ").append(l.size()).append(" 次：首次 ").append(num(first.value))
          .append("（").append(fmt.format(first.eventTime)).append("）→ 最近 ").append(num(last.value)).append(unit)
          .append("（").append(fmt.format(last.eventTime)).append("），最低 ").append(num(lo.value)).append("（").append(fmt.format(lo.eventTime))
          .append("），最高 ").append(num(hi.value)).append("（").append(fmt.format(hi.eventTime)).append("）");
      if (nr > 0 && ne > 0) s.append("；近 6h 均值 ").append(num(recent / nr)).append("，此前均值 ").append(num(earlier / ne));
      add(out, "趋势", s.toString(), last.eventTime);
      if("LACTATE".equals(code)){
        List<String> series=new ArrayList<>();for(var point:l.subList(Math.max(0,l.size()-24),l.size()))series.add(fmt.format(point.eventTime)+"="+num(point.value)+unit);
        add(out,"乳酸时序","近24h有效乳酸按记录时间排序："+String.join("；",series)+(l.size()>24?"；仅列最近24个时点，前序未逐点提供，峰值见趋势摘要":"")+"。最近值反映末次测量，最高值反映此窗口峰值，两者均非单独确诊依据；记录时间不自动视为采样时间或复苏后时点。",last.eventTime);
      }
      if (++n >= 12) break;
    }
    List<org.icu.sepsis.domain.Domain.Observation> urine = by.get("URINE_OUTPUT");
    if (urine != null && !urine.isEmpty()) {
      add(out, "数据限制", "取得尿量记录，但记录间隔、累计/增量含义及单位尚未确认，不自动累加为 6h/24h 尿量", null);
    }
    if (n == 0 && urine == null) add(out, "趋势", "近 24 小时可用于趋势的重复记录不足（每项至少 2 次）", null);
  }

  void add(List<Fact> out, String cat, String text, Instant t) {
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
