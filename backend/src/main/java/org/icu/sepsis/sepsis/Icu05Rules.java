package org.icu.sepsis.sepsis;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * ICU-05 septic-shock bundle judgement, ported from the department director's rule set
 * (icu-quality-dashboard "ICU-05 感染性休克 Bundle 自动判定 · 需求文档 v3" + scoring/bundle_engine.py).
 *
 * <p>Every judgement item is tri-state: TRUE / FALSE / null. null means "not determinable from the
 * available records" and is never folded into FALSE. Pure functions only; no I/O.
 */
public final class Icu05Rules {
  private Icu05Rules() {}

  public static final String RULE_VERSION = "ICU-05 v3";
  public static final String RULE_SOURCE = "科室主任 ICU-05 感染性休克 Bundle 判定规则（需求文档 v3）";
  static final Duration GATE_BEFORE = Duration.ofHours(24);
  static final Duration GATE_AFTER = Duration.ofHours(6);
  static final double FLUID_THRESHOLD_ML = 1500;

  // ---------------- input model ----------------
  public static final class Point {
    public Instant time; public double value; public String channel; public String sourceId;
    public Point() {}
    public Point(Instant time, double value, String channel, String sourceId) { this.time=time; this.value=value; this.channel=channel; this.sourceId=sourceId; }
  }
  public static final class Action {
    public Instant time; public String action;
    public Action() {}
    public Action(Instant time, String action) { this.time=time; this.action=action; }
  }
  public static final class DrugItem {
    public String name; public Double liquidMl; public boolean liquidUnitKnown = true;
    public DrugItem() {}
    public DrugItem(String name, Double liquidMl) { this.name=name; this.liquidMl=liquidMl; }
  }
  public static final class Drug {
    public Instant start; public List<DrugItem> items = new ArrayList<>(); public List<Action> actions = new ArrayList<>(); public String sourceId;
  }
  public static final class Order {
    public String name; public Instant orderTime; public Instant reviewTime; public String sourceId;
    public Order() {}
    public Order(String name, Instant orderTime, Instant reviewTime, String sourceId) { this.name=name; this.orderTime=orderTime; this.reviewTime=reviewTime; this.sourceId=sourceId; }
  }
  public static final class Evidence {
    public Instant asOf;
    public Instant t0; public String t0OrderName;
    public Instant icuAdmission; public Instant hospitalAdmission; public String admissionType;
    public String diagnosisText;
    public List<Point> lactate = new ArrayList<>(), pf = new ArrayList<>(), gcs = new ArrayList<>(), map = new ArrayList<>(), hr = new ArrayList<>();
    public List<Drug> drugs = new ArrayList<>();
    public List<Order> cultureOrders = new ArrayList<>();
    public Set<String> vasoDictionary = new HashSet<>(), antibioticDictionary = new HashSet<>();
    /** Source names whose read failed: bGATemp, score, bedside, drugExe, VI_ICU_ZYYZ, diagnosis. */
    public Set<String> failed = new HashSet<>();
    public List<String> bloodCultureKeywords = List.of("血培养");
    /** reviewTime (director doc) or orderTime. */
    public String cultureTimeField = "reviewTime";
  }

  // ---------------- dictionaries ----------------
  static final List<String> VASO_KEYWORDS = List.of("去甲肾上腺素", "肾上腺素", "多巴胺", "多巴酚丁胺", "间羟胺", "阿拉明",
      "垂体后叶", "血管加压素", "特利加压素", "去氧肾上腺素", "苯肾上腺素", "异丙肾上腺素", "米力农", "左西孟旦",
      "norepinephrine", "epinephrine", "dopamine", "dobutamine", "vasopressin", "phenylephrine", "milrinone", "isoproterenol", "metaraminol", "terlipressin", "levosimendan");
  /** Known configDrug misclassifications as 血管活性 (audit of the dashboard). */
  static final List<String> VASO_DICTIONARY_EXCLUDE = List.of("氯化钾", "氯化钠", "胺碘酮");
  static final List<String> ANTIBIOTIC_KEYWORDS = List.of("头孢", "培南", "青霉", "万古", "阿奇", "左氧", "莫西", "甲硝唑", "奥硝唑", "利奈",
      "替考", "磷霉素", "美罗", "亚胺", "厄他", "哌酮", "舒巴坦", "氨苄", "哌拉", "阿莫", "克拉", "多西", "米诺", "替加", "达托", "夫西",
      "磺胺", "呋喃", "吡哌", "诺氟", "环丙", "氧氟", "氟康唑", "伏立康唑", "卡泊芬净", "米卡芬净", "多粘菌素", "黏菌素", "阿米卡星", "庆大霉素", "克林霉素");
  static final List<String> NON_ANTIBIOTIC = List.of("肝素", "低分子", "华法林", "阿司匹林", "氯吡格雷", "维生素", "氯化钾", "胰岛素", "碳酸氢钠",
      "呋塞米", "甘露醇", "地塞米松", "甲泼尼龙", "丙泊酚", "咪达唑仑", "芬太尼", "右美托咪定", "吗啡", "布洛芬", "对乙酰氨基酚", "皮试");
  static final List<String> INFECTION_DIAG = List.of("脓毒", "败血", "感染性休克", "感染", "肺炎", "腹膜炎", "脑膜炎", "蜂窝织炎", "脓肿", "化脓",
      "尿路感染", "胆管炎", "septic", "sepsis", "infection");
  /** No bare 氯化钠 (hits 10% 浓钠) and no bare 醋酸 (hits 醋酸泼尼松). */
  static final List<String> CRYSTALLOID = List.of("生理盐水", "0.9%氯化钠", "normal saline", "乳酸林格", "林格", "lactated ringer", "醋酸林格", "plasmalyte", "复方氯化钠");
  static final List<String> COLLOID = List.of("白蛋白", "albumin", "羟乙基淀粉", "voluven", "明胶", "gelatin", "右旋糖酐", "dextran");
  public static final String CULTURE_REGEX = "血培养|痰培养|尿培养|细菌培养|真菌培养|分泌物培养|引流液培养|胸水培养|腹水培养|脑脊液培养|导管培养";
  public static final Set<String> STOP_ACTIONS = Set.of("stop", "pause", "cancel", "停止", "暂停", "取消", "停用");
  /** Confirmed resume after pause (hospital drugActionList uses recovery). */
  public static final Set<String> RESUME_ACTIONS = Set.of("recovery", "resume", "恢复", "继续");

  /**
   * Whether a vasopressor administration is running at time {@code at}, using the action sequence
   * (start / pause / recovery / stop) rather than first-stop-wins.
   *
   * <p>Returns TRUE / FALSE / null (null = cannot tell: started earlier without action records).
   */
  public static Boolean vasopressorActiveAt(Drug d, Instant at) {
    if (d == null || d.start == null || at == null) return null;
    if (d.start.isAfter(at)) return Boolean.FALSE;
    if (d.actions == null || d.actions.isEmpty()) return null;
    List<Action> acts = new ArrayList<>(d.actions);
    acts.sort(Comparator.comparing(a -> a.time == null ? d.start : a.time));
    boolean on = true;
    for (Action a : acts) {
      Instant t = a.time == null ? d.start : a.time;
      if (t.isAfter(at)) break;
      String raw = a.action == null ? "" : a.action.trim().toLowerCase(Locale.ROOT);
      if ("stop".equals(raw) || "cancel".equals(raw) || "停止".equals(raw) || "取消".equals(raw) || "停用".equals(raw))
        on = false;
      else if ("pause".equals(raw) || "暂停".equals(raw)) on = false;
      else if (RESUME_ACTIONS.contains(raw)) on = true;
      // start / add / minus / quickAdd keep state
    }
    return on;
  }

  /**
   * Whether the infusion ran at any moment in [a, b): started inside the window, running at a by the
   * start/pause/recovery/stop sequence, or resumed inside the window. null = cannot tell (started before a
   * with no action records, or no stop ever recorded and no action since a — the pump may have ended silently).
   */
  public static Boolean vasopressorRunningDuring(Drug d, Instant a, Instant b) {
    return vasopressorRunningDuring(d, a, b, true);
  }

  /** @param silentEndUnknown false = director gate: started, never stopped -> still running (as before). */
  public static Boolean vasopressorRunningDuring(Drug d, Instant a, Instant b, boolean silentEndUnknown) {
    if (d == null || d.start == null || a == null || b == null) return null;
    if (!d.start.isBefore(b)) return Boolean.FALSE;
    if (!d.start.isBefore(a)) return Boolean.TRUE;
    if (d.actions == null || d.actions.isEmpty()) return null;
    Boolean atA = vasopressorActiveAt(d, a);
    boolean resumedInside = false, anyStop = false, actionSinceA = false;
    for (Action ac : d.actions) {
      if (ac.time == null) continue;
      String raw = ac.action == null ? "" : ac.action.trim().toLowerCase(Locale.ROOT);
      if (STOP_ACTIONS.contains(raw)) anyStop = true;
      if (!ac.time.isBefore(a)) actionSinceA = true;
      if (!ac.time.isBefore(a) && ac.time.isBefore(b) && RESUME_ACTIONS.contains(raw)) resumedInside = true;
    }
    if (resumedInside) return Boolean.TRUE;
    if (Boolean.FALSE.equals(atA)) return Boolean.FALSE;
    if (silentEndUnknown && Boolean.TRUE.equals(atA) && !anyStop && !actionSinceA) return null;
    return atA;
  }

  static String norm(String name) {
    if (name == null) return "";
    return name.replaceAll("^[\\s*★☆#※\\-·•]+", "").trim().toLowerCase(Locale.ROOT);
  }
  static boolean any(String text, List<String> keywords) {
    String t = norm(text);
    for (String k : keywords) if (t.contains(k.toLowerCase(Locale.ROOT))) return true;
    return false;
  }
  public static boolean isVasopressor(String name, Set<String> dictionary) {
    String n = norm(name);
    if (n.isEmpty()) return false;
    if (any(n, VASO_KEYWORDS)) return true;
    return dictionary != null && dictionary.contains(n) && !any(n, VASO_DICTIONARY_EXCLUDE);
  }
  public static boolean isAntibiotic(String name, Set<String> dictionary) {
    String n = norm(name);
    if (n.isEmpty() || any(n, NON_ANTIBIOTIC)) return false;
    return any(n, ANTIBIOTIC_KEYWORDS) || (dictionary != null && dictionary.contains(n));
  }
  public static String fluidType(String name) {
    if (any(name, CRYSTALLOID)) return "crystalloid";
    if (any(name, COLLOID)) return "colloid";
    return null;
  }
  public static boolean infectionDiagnosis(String text) { return text != null && !text.isBlank() && any(text, INFECTION_DIAG); }

  // ---------------- evaluation ----------------
  public static Map<String, Object> evaluate(Evidence e) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("ruleVersion", RULE_VERSION);
    out.put("ruleSource", RULE_SOURCE);
    out.put("asOf", e.asOf);
    out.put("t0", e.t0);
    out.put("t0Source", e.t0 == null ? null : "入科后首条医嘱 orderTime（DataCenter.VI_ICU_ZYYZ）");
    out.put("t0OrderName", e.t0OrderName);
    out.put("pool", pool(e));
    out.put("limitations", limitations(e));
    if (e.t0 == null) {
      out.put("status", "no_t0");
      out.put("statusLabel", "未取得 T0");
      out.put("message", "入科后未找到首条医嘱时间，无法确定 T0，不做 Bundle 判定。");
      out.put("gate", null);
      out.put("windows", List.of());
      out.put("lactateSeries", List.of());
      return out;
    }
    Instant t0 = e.t0;
    Instant floor = e.hospitalAdmission != null ? e.hospitalAdmission : e.icuAdmission;
    Instant gateFrom = t0.minus(GATE_BEFORE);
    if (floor != null && floor.isAfter(gateFrom) && !floor.isAfter(t0)) gateFrom = floor;
    Instant gateTo = min(t0.plus(GATE_AFTER), e.asOf);

    Map<String, Object> gate = new LinkedHashMap<>();
    gate.put("from", gateFrom); gate.put("to", gateTo);
    // ---- organ dysfunction S1-S4 ----
    Map<String, Object> s1 = minItem("S1", "氧合指数 <300", e.pf, gateFrom, gateTo, e.failed.contains("bGATemp"), v -> v < 300, "");
    Map<String, Object> s2 = minItem("S2", "GCS <13", e.gcs, gateFrom, gateTo, e.failed.contains("score"), v -> v < 13, "分");
    Map<String, Object> s3 = minItem("S3", "MAP <70", e.map, gateFrom, gateTo, e.failed.contains("bedside"), v -> v < 70, "mmHg");
    Map<String, Object> s4 = vasopressor("S4", "血管活性药", e, gateFrom, gateTo);
    List<Map<String, Object>> organ = List.of(s1, s2, s3, s4);
    Boolean hasOrgan = anyTrue(organ);
    // ---- infection evidence I1-I3 ----
    Map<String, Object> i1 = item("I1", "诊断含感染", e.failed.contains("diagnosis") ? null : infectionDiagnosis(e.diagnosisText), null,
        infectionDiagnosis(e.diagnosisText) ? clip(e.diagnosisText, 60) : "", e.failed.contains("diagnosis") ? "SOURCE_ERROR" : null);
    Map<String, Object> i2 = antibioticGate(e, gateFrom, gateTo);
    Map<String, Object> i3 = cultureGate(e, gateFrom, gateTo);
    List<Map<String, Object>> infection = List.of(i1, i2, i3);
    Boolean hasInfection = anyTrue(infection);
    // ---- shock K1 / K2 ----
    Point lacInitial = initialLactate(e.lactate, t0, e.asOf);
    Boolean k1v = e.failed.contains("bGATemp") ? null : lacInitial == null ? null : lacInitial.value >= 2;
    Map<String, Object> k1 = item("K1", "乳酸 ≥2", k1v, lacInitial == null ? null : lacInitial.time,
        lacInitial == null ? "" : fmt(lacInitial.value) + " mmol/L", lacInitial == null ? "DATA_MISSING_LAC" : null);
    Map<String, Object> k2 = new LinkedHashMap<>(s4); k2.put("code", "K2"); k2.put("label", "需要升压药");
    Boolean k2v = (Boolean) s4.get("value");
    Double mapMin = (Double) s3.get("number");
    Boolean hypotension = mapMin == null ? null : mapMin < 70;
    // SHOCK_RULE = s3: K2 OR (K1 AND MAP<70)
    Boolean shock;
    if (Boolean.TRUE.equals(k2v) || (Boolean.TRUE.equals(k1v) && Boolean.TRUE.equals(hypotension))) shock = true;
    else if (Boolean.FALSE.equals(k2v) && (Boolean.FALSE.equals(k1v) || Boolean.FALSE.equals(hypotension))) shock = false;
    else shock = null;

    String status, label, reason;
    if (Boolean.FALSE.equals(hasOrgan)) { status = "not_met"; label = "未见器官功能障碍"; reason = "NO_ORGAN_DYSFUNCTION"; }
    else if (Boolean.FALSE.equals(shock)) { status = "not_met"; label = "未达休克判定"; reason = "NOT_SEPTIC_SHOCK"; }
    else if (Boolean.TRUE.equals(shock) && Boolean.TRUE.equals(hasOrgan) && Boolean.TRUE.equals(hasInfection)) { status = "septic_shock"; label = "符合感染性休克判定"; reason = null; }
    else if (Boolean.TRUE.equals(shock) && !Boolean.TRUE.equals(hasInfection)) { status = "suspected_review"; label = "疑似，待复核感染证据"; reason = "NO_INFECTION_EVIDENCE"; }
    else { status = "unknown"; label = "证据不全，无法判定"; reason = "INSUFFICIENT_EVIDENCE"; }
    gate.put("organ", organ); gate.put("hasOrganDysfunction", hasOrgan);
    gate.put("infection", infection); gate.put("hasInfection", hasInfection);
    gate.put("shock", List.of(k1, k2)); gate.put("mapMin", mapMin); gate.put("isSepticShock", shock);
    gate.put("rule", "休克 = 需要升压药，或 乳酸≥2 且 MAP<70");
    gate.put("status", status); gate.put("statusLabel", label); gate.put("reason", reason);
    out.put("gate", gate);
    out.put("status", status);
    out.put("statusLabel", label);
    out.put("applicable", "septic_shock".equals(status) || "suspected_review".equals(status));
    List<Map<String, Object>> windows = new ArrayList<>();
    for (int h : new int[] {1, 3, 6}) windows.add(window(e, h));
    out.put("windows", windows);
    out.put("lactateSeries", lactateSeries(e.lactate, t0, e.asOf));
    return out;
  }

  static Map<String, Object> pool(Evidence e) {
    Map<String, Object> m = new LinkedHashMap<>();
    Boolean within24 = e.icuAdmission == null || e.hospitalAdmission == null ? null
        : Duration.between(e.hospitalAdmission, e.icuAdmission).compareTo(Duration.ofHours(24)) < 0;
    Boolean typeOk = e.admissionType == null || e.admissionType.isBlank() ? null
        : e.admissionType.contains("入院") || e.admissionType.contains("外院");
    m.put("icuWithin24h", within24);
    m.put("admissionType", e.admissionType);
    m.put("admissionTypeEligible", typeOk);
    m.put("eligible", Boolean.FALSE.equals(within24) || Boolean.FALSE.equals(typeOk) ? Boolean.FALSE
        : Boolean.TRUE.equals(within24) && Boolean.TRUE.equals(typeOk) ? Boolean.TRUE : null);
    m.put("rule", "入院 24h 内进 ICU，且入科类型为 入院 / 手术入院 / 外院");
    return m;
  }

  static Map<String, Object> window(Evidence e, int hours) {
    Instant t0 = e.t0, end = t0.plus(Duration.ofHours(hours)), limit = min(end, e.asOf);
    boolean closed = !e.asOf.isBefore(end);
    boolean lacFail = e.failed.contains("bGATemp"), drugFail = e.failed.contains("drugExe"), orderFail = e.failed.contains("VI_ICU_ZYYZ"), mapFail = e.failed.contains("bedside");
    List<Point> lac = within(e.lactate, t0, limit);
    // A1 lactate measured (earliest in window, record max)
    Point first = lac.isEmpty() ? null : lac.get(0);
    Double lacMax = lac.stream().map(p -> p.value).max(Double::compare).orElse(null);
    Boolean a1 = lacFail ? null : first == null ? null : true;
    Map<String, Object> a1m = item("A1", "乳酸测定", a1, first == null ? null : first.time,
        first == null ? "" : fmt(first.value) + " mmol/L" + (lac.size() > 1 ? "，最高 " + fmt(lacMax) : ""), a1 == null ? (lacFail ? "SOURCE_ERROR" : "DATA_MISSING_LAC") : null);
    // B1 antibiotic execution start (latest in window)
    Map.Entry<Instant, String> abx = drugFail ? null : latestDrug(e, t0, limit, n -> isAntibiotic(n, e.antibioticDictionary));
    Boolean b1 = abx == null ? null : true;
    Map<String, Object> b1m = item("B1", "抗菌药物执行", b1, abx == null ? null : abx.getKey(), abx == null ? "" : abx.getValue(), b1 == null ? (drugFail ? "SOURCE_ERROR" : "AB_MISSING") : null);
    // B2 blood culture order time (latest in window)
    Order bc = orderFail ? null : latestCulture(e, t0, limit);
    Boolean b2 = bc == null ? null : true;
    Map<String, Object> b2m = item("B2", "血培养", b2, bc == null ? null : cultureTime(e, bc), bc == null ? "" : bc.name, b2 == null ? (orderFail ? "SOURCE_ERROR" : "BC_MISSING") : null);
    // B3 = B1 & B2 & antibiotic later than culture
    Boolean b3; String b3reason;
    if (b1 == null) { b3 = null; b3reason = "AB_MISSING"; }
    else if (b2 == null) { b3 = null; b3reason = "BC_MISSING"; }
    else if (abx.getKey().isAfter(cultureTime(e, bc))) { b3 = true; b3reason = null; }
    else { b3 = false; b3reason = "BC_AFTER_AB"; }
    Map<String, Object> b3m = item("B3", "先培养后抗菌", b3, null, b3reason == null ? "抗菌药晚于血培养" : "BC_AFTER_AB".equals(b3reason) ? "抗菌药早于或同时于血培养" : "", b3reason);
    // C1 MAP<70 trigger, C2 lactate>=4 trigger (opposite semantics: TRUE = abnormal)
    List<Point> maps = within(e.map, t0, limit);
    Point mapLow = maps.stream().min(Comparator.comparingDouble(p -> p.value)).orElse(null);
    Boolean c1 = mapFail ? null : mapLow == null ? null : mapLow.value < 70;
    Map<String, Object> c1m = item("C1", "MAP <70（触发）", c1, mapLow == null ? null : mapLow.time,
        mapLow == null ? "" : "最低 " + fmt(mapLow.value) + (mapLow.channel == null ? "" : "（" + ("IBP".equals(mapLow.channel) ? "有创" : "无创") + "）"), c1 == null ? "MAP_MISSING" : null);
    Boolean c2 = lacFail ? null : lacMax == null ? null : lacMax >= 4;
    Map<String, Object> c2m = item("C2", "乳酸 ≥4（触发）", c2, null, lacMax == null ? "" : "最高 " + fmt(lacMax), c2 == null ? "DATA_MISSING_LAC" : null);
    // C3 fluids
    Map<String, Object> c3m = fluid(e, hours, t0, limit, drugFail);
    Boolean c3 = (Boolean) c3m.get("value");
    // finish decision tree
    Boolean step1 = a1, step2 = b3, step3;
    boolean triggered = Boolean.TRUE.equals(c1) || Boolean.TRUE.equals(c2);
    if (triggered) step3 = c3; else if (c1 == null || c2 == null) step3 = null; else step3 = true;
    Boolean finish;
    if (Boolean.TRUE.equals(step1) && Boolean.TRUE.equals(step2) && Boolean.TRUE.equals(step3)) finish = true;
    else if (Boolean.FALSE.equals(step1) || Boolean.FALSE.equals(step2) || Boolean.FALSE.equals(step3)) finish = false;
    else finish = null;
    List<String> reasons = new ArrayList<>();
    if (Boolean.FALSE.equals(step1)) reasons.add("A1_NOT_MET");
    if (!Boolean.TRUE.equals(step2)) reasons.add(b3reason == null ? "AB_MISSING" : b3reason);
    if (Boolean.FALSE.equals(step3)) reasons.add("FLUID_INSUFFICIENT");
    if (Boolean.TRUE.equals(c1)) reasons.add("MAP_NOT_MET");
    Map<String, Object> recheck = null;
    if (hours == 6) {
      List<Point> re = within(e.lactate, t0.plus(Duration.ofHours(1)), limit);
      Point last = re.isEmpty() ? null : re.get(re.size() - 1);
      Boolean rv;
      if (lacFail) rv = null; else if (last != null) rv = Boolean.TRUE; else rv = closed ? Boolean.FALSE : null;
      recheck = item("A2", "复测乳酸（1h 后）", rv, last == null ? null : last.time, last == null ? "" : fmt(last.value) + " mmol/L", last == null ? "LACTATE_RECHECK_MISSING" : null);
      if (Boolean.TRUE.equals(finish) && last == null) { finish = null; reasons.add("LACTATE_RECHECK_MISSING"); }
    }
    String state;
    if (Boolean.TRUE.equals(finish)) state = "met";
    else if (!closed) { state = "in_progress"; finish = null; reasons.add("WINDOW_OPEN"); }
    else state = Boolean.FALSE.equals(finish) ? "not_met" : "unknown";
    Map<String, Object> w = new LinkedHashMap<>();
    w.put("key", hours + "h"); w.put("hours", hours); w.put("deadline", end); w.put("closed", closed);
    w.put("finish", finish); w.put("state", state);
    w.put("step1", step1); w.put("step2", step2); w.put("step3", step3);
    w.put("finishPath", triggered ? "triggered" : "not_triggered");
    w.put("reasons", reasons);
    List<Map<String, Object>> items = new ArrayList<>(List.of(a1m, b1m, b2m, b3m, c1m, c2m, c3m));
    if (recheck != null) items.add(1, recheck);
    w.put("items", items);
    return w;
  }

  static Map<String, Object> fluid(Evidence e, int hours, Instant t0, Instant limit, boolean drugFail) {
    if (drugFail) return item("C3", hours == 1 ? "1h 内开始补液" : "补液 ≥1500 ml", null, null, "", "SOURCE_ERROR");
    double sum = 0; boolean incomplete = false; Instant firstTime = null; int count = 0;
    for (Drug d : e.drugs) {
      if (d.start == null || d.start.isBefore(t0) || d.start.isAfter(limit)) continue;
      for (DrugItem it : d.items) {
        if (fluidType(it.name) == null) continue;
        count++;
        if (firstTime == null || d.start.isBefore(firstTime)) firstTime = d.start;
        if (it.liquidMl == null || !it.liquidUnitKnown) incomplete = true; else sum += it.liquidMl;
      }
    }
    if (hours == 1) {
      Boolean v = count > 0;
      return item("C3", "1h 内开始补液", v, firstTime, count > 0 ? count + " 条晶体/胶体执行" : "", v ? null : "FLUID_MISSING");
    }
    Boolean v = sum >= FLUID_THRESHOLD_ML ? Boolean.TRUE : incomplete ? null : Boolean.FALSE;
    String detail = count == 0 ? "" : "累计 " + fmt(sum) + " ml" + (incomplete ? "（部分记录无量）" : "");
    return item("C3", "补液 ≥1500 ml", v, firstTime, detail, v == null ? "FLUID_AMOUNT_INCOMPLETE" : v ? null : "FLUID_INSUFFICIENT");
  }

  static Map<String, Object> vasopressor(String code, String label, Evidence e, Instant from, Instant to) {
    if (e.failed.contains("drugExe")) return item(code, label, null, null, "", "SOURCE_ERROR");
    Instant t0 = e.t0;
    boolean unknown = false;
    String unknownName = null;
    Instant unknownTime = null;
    List<Drug> sorted =
        e.drugs.stream()
            .filter(d -> d.start != null)
            .sorted(Comparator.comparing(d -> d.start))
            .collect(Collectors.toList());
    for (Drug d : sorted) {
      if (d.start.isBefore(from) || d.start.isAfter(to)) continue;
      for (DrugItem it : d.items) {
        if (!isVasopressor(it.name, e.vasoDictionary)) continue;
        // Started after T0 inside the window: any use counts (director rule). Started at/before T0: must run after T0.
        Boolean active = d.start.isAfter(t0) ? Boolean.TRUE : vasopressorRunningDuring(d, t0, to, false);
        if (active == null) {
          unknown = true;
          if (unknownName == null) {
            unknownName = it.name;
            unknownTime = d.start;
          }
          continue;
        }
        if (!active) continue;
        String note = d.start.isAfter(t0) ? it.name : it.name + "（T0 前开始，按执行动作 T0 后仍在用）";
        return item(code, label, true, d.start, note, null);
      }
    }
    if (unknown)
      return item(
          code,
          label,
          null,
          unknownTime,
          unknownName + "（T0 前开始，执行动作不足以判断是否仍在用）",
          "VASO_STATUS_UNKNOWN");
    return item(code, label, false, null, "", null);
  }

  static Map<String, Object> antibioticGate(Evidence e, Instant from, Instant to) {
    if (e.failed.contains("drugExe")) return item("I2", "抗菌药执行", null, null, "", "SOURCE_ERROR");
    Map.Entry<Instant, String> hit = firstDrug(e, from, to, n -> isAntibiotic(n, e.antibioticDictionary));
    return item("I2", "抗菌药执行", hit != null, hit == null ? null : hit.getKey(), hit == null ? "" : hit.getValue(), null);
  }

  static Map<String, Object> cultureGate(Evidence e, Instant from, Instant to) {
    if (e.failed.contains("VI_ICU_ZYYZ")) return item("I3", "病原学送检", null, null, "", "SOURCE_ERROR");
    Order hit = e.cultureOrders.stream().filter(o -> o.orderTime != null && !o.orderTime.isBefore(from) && !o.orderTime.isAfter(to)
        && o.name != null && o.name.matches(".*(" + CULTURE_REGEX + ").*")).min(Comparator.comparing(o -> o.orderTime)).orElse(null);
    return item("I3", "病原学送检", hit != null, hit == null ? null : hit.orderTime, hit == null ? "" : hit.name, null);
  }

  static Order latestCulture(Evidence e, Instant from, Instant to) {
    return e.cultureOrders.stream().filter(o -> o.name != null && e.bloodCultureKeywords.stream().anyMatch(o.name::contains))
        .filter(o -> { Instant t = cultureTime(e, o); return t != null && !t.isBefore(from) && !t.isAfter(to); })
        .max(Comparator.comparing(o -> cultureTime(e, o))).orElse(null);
  }
  static Instant cultureTime(Evidence e, Order o) { return "orderTime".equals(e.cultureTimeField) ? o.orderTime : o.reviewTime; }

  static Map.Entry<Instant, String> latestDrug(Evidence e, Instant from, Instant to, Predicate<String> match) {
    Map.Entry<Instant, String> best = null;
    for (Drug d : e.drugs) {
      if (d.start == null || d.start.isBefore(from) || d.start.isAfter(to)) continue;
      for (DrugItem it : d.items) if (match.test(it.name) && (best == null || d.start.isAfter(best.getKey()))) best = Map.entry(d.start, it.name);
    }
    return best;
  }
  static Map.Entry<Instant, String> firstDrug(Evidence e, Instant from, Instant to, Predicate<String> match) {
    Map.Entry<Instant, String> best = null;
    for (Drug d : e.drugs) {
      if (d.start == null || d.start.isBefore(from) || d.start.isAfter(to)) continue;
      for (DrugItem it : d.items) if (match.test(it.name) && (best == null || d.start.isBefore(best.getKey()))) best = Map.entry(d.start, it.name);
    }
    return best;
  }

  /** Initial lactate: last value at or before T0+1h (from T0-2h); else first in [T0, T0+6h]. */
  static Point initialLactate(List<Point> lactate, Instant t0, Instant asOf) {
    Instant cap = min(t0.plus(Duration.ofHours(1)), asOf);
    List<Point> early = within(lactate, t0.minus(Duration.ofHours(2)), cap);
    if (!early.isEmpty()) return early.get(early.size() - 1);
    List<Point> later = within(lactate, t0, min(t0.plus(GATE_AFTER), asOf));
    return later.isEmpty() ? null : later.get(0);
  }

  static List<Map<String, Object>> lactateSeries(List<Point> lactate, Instant t0, Instant asOf) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Point p : within(lactate, t0.minus(Duration.ofHours(2)), min(t0.plus(GATE_AFTER), asOf))) {
      Map<String, Object> m = new LinkedHashMap<>();
      long min = Duration.between(t0, p.time).toMinutes();
      m.put("time", p.time); m.put("value", p.value); m.put("minutesFromT0", min);
      m.put("period", min <= 60 ? "首次" : min <= 180 ? "1–3h" : "3–6h"); m.put("sourceId", p.sourceId);
      out.add(m);
    }
    return out;
  }

  static List<String> limitations(Evidence e) {
    List<String> l = new ArrayList<>();
    l.add("乳酸、氧合指数取 SmartCare 血气（bGATemp），按病案号关联，动/静脉标本未区分。");
    l.add("抗菌药、补液、升压药取执行单开始时间（drugExe.startTime），药名按关键词与药品字典识别。");
    l.add("血培养取医嘱" + ("orderTime".equals(e.cultureTimeField) ? "开立时间" : "审核时间（reviewTime）") + "，不等于实际采血时间。");
    l.add("补液只计晶体/胶体，未按体重 30 ml/kg 换算。");
    l.add("人工覆盖与感染部位确认未接入；结果为质控规则自动判定，不是诊断。");
    if (!e.failed.isEmpty()) l.add("读取失败的来源：" + String.join("、", new TreeSet<>(e.failed)) + "；相关项显示为“—”，不按未完成处理。");
    return l;
  }

  // ---------------- helpers ----------------
  static Map<String, Object> minItem(String code, String label, List<Point> pts, Instant from, Instant to, boolean failed,
      Predicate<Double> hit, String unit) {
    if (failed) return item(code, label, null, null, "", "SOURCE_ERROR");
    Point low = within(pts, from, to).stream().min(Comparator.comparingDouble(p -> p.value)).orElse(null);
    if (low == null) return item(code, label, null, null, "", "DATA_MISSING");
    String ch = low.channel == null ? "" : "（" + ("IBP".equals(low.channel) ? "有创" : "无创") + "）";
    Map<String, Object> m = item(code, label, hit.test(low.value), low.time, "最低 " + fmt(low.value) + (unit.isEmpty() ? "" : " " + unit) + ch, null);
    m.put("number", low.value);
    return m;
  }
  static Map<String, Object> item(String code, String label, Boolean value, Instant time, String detail, String reason) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("code", code); m.put("label", label); m.put("value", value); m.put("time", time);
    m.put("detail", detail == null ? "" : detail); m.put("reason", reason);
    return m;
  }
  static Boolean anyTrue(List<Map<String, Object>> items) {
    boolean unknown = false;
    for (Map<String, Object> m : items) { Object v = m.get("value"); if (Boolean.TRUE.equals(v)) return true; if (v == null) unknown = true; }
    return unknown ? null : false;
  }
  static List<Point> within(List<Point> pts, Instant from, Instant to) {
    if (pts == null) return List.of();
    return pts.stream().filter(p -> p.time != null && !p.time.isBefore(from) && !p.time.isAfter(to))
        .sorted(Comparator.comparing(p -> p.time)).collect(Collectors.toList());
  }
  static Instant min(Instant a, Instant b) { return b == null || a.isBefore(b) ? a : b; }
  static String fmt(Double v) {
    if (v == null) return "—";
    return Math.abs(v - Math.rint(v)) < 1e-9 ? String.valueOf((long) Math.rint(v)) : String.format(Locale.ROOT, "%.1f", v);
  }
  static String clip(String s, int n) { return s == null ? "" : s.length() <= n ? s : s.substring(0, n) + "…"; }
}
