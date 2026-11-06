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

  public static final String RULE_VERSION = "ICU-05 v3-local-k1-max-20261106";
  public static final String RULE_SOURCE = "科室主任 ICU-05 感染性休克 Bundle 判定规则（需求文档 v3）";
  static final Duration GATE_BEFORE = Duration.ofHours(24);
  static final Duration GATE_AFTER = Duration.ofHours(6);
  static final double FLUID_THRESHOLD_ML = 1500;
  /** Hypotension threshold for the shock gate, organ item and C1 trigger (local S3/C1 decision: MAP<=65). */
  public static final double MAP_LOW = 65;
  /** Lactate threshold for the shock gate (K1). */
  public static final double LACTATE_HIGH = 2;
  /** A crystalloid/colloid bag smaller than this is a flush or carrier, not resuscitation fluid. */
  static final double FLUID_MIN_BAG_ML = 100;

  // ---------------- input model ----------------
  public static final class Point {
    public Instant time; public double value; public String channel; public String sourceId;
    public Object rawValue; public String rawUnit; public boolean lactateUnitKnown = true;
    public String timeMeaning = "记录时间（采样或结果时间类型未确认）";
    public Point() {}
    public Point(Instant time, double value, String channel, String sourceId) { this.time=time; this.value=value; this.channel=channel; this.sourceId=sourceId; }
  }
  public static final class Action {
    public Instant time; public String action;
    public Action() {}
    public Action(Instant time, String action) { this.time=time; this.action=action; }
  }
  public static final class DrugItem {
    public String name; public Double actualInfusedMl; public Double liquidMl; public boolean liquidUnitKnown = true;
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
    public String k1Selection = "maximum";
    /** K1 window must be supplied with a verified rule source; never inherit the general gate implicitly. */
    public Instant k1WindowStart, k1WindowEnd;
    public String k1WindowSource;
    public Instant asOf;
    public Instant t0; public String t0OrderName; public String t0SourceId;
    public Instant icuAdmission; public Instant hospitalAdmission; public String admissionType;
    public String diagnosisText;
    public List<Point> lactate = new ArrayList<>(), pf = new ArrayList<>(), gcs = new ArrayList<>(), map = new ArrayList<>(), hr = new ArrayList<>();
    public List<Drug> drugs = new ArrayList<>();
    public List<Order> cultureOrders = new ArrayList<>();
    public Set<String> vasoDictionary = new HashSet<>(), antibioticDictionary = new HashSet<>();
    /** Source names whose read failed: bGATemp, score, bedside, drugExe, VI_ICU_ZYYZ, diagnosis. */
    public Set<String> failed = new HashSet<>();
    public Set<String> truncatedSources = new HashSet<>();
    public List<String> bloodCultureKeywords = List.of("血培养");
    /** reviewTime (director doc) or orderTime. */
    public String cultureTimeField = "reviewTime";
  }

  // ---------------- dictionaries ----------------
  static final List<String> VASO_KEYWORDS = List.of("去甲肾上腺素", "肾上腺素", "多巴胺", "间羟胺", "阿拉明",
      "垂体后叶", "血管加压素", "特利加压素", "去氧肾上腺素", "苯肾上腺素",
      "norepinephrine", "epinephrine", "dopamine", "vasopressin", "phenylephrine", "metaraminol", "terlipressin");
  /** Inotropes / inodilators are not vasopressors: they never make "需要升压药" true. Checked before keywords. */
  static final List<String> INOTROPES = List.of("多巴酚丁胺", "异丙肾上腺素", "米力农", "左西孟旦", "dobutamine", "isoproterenol", "isoprenaline", "milrinone", "levosimendan");
  /** Known configDrug misclassifications as 血管活性 (audit of the dashboard). */
  static final List<String> VASO_DICTIONARY_EXCLUDE = List.of("氯化钾", "氯化钠", "胺碘酮");
  /** Full names or class suffixes only: short stems such as 多西/替加/克拉/米诺 also hit chemotherapy and other drugs. */
  static final List<String> ANTIBIOTIC_KEYWORDS = List.of("头孢", "培南", "青霉素", "西林", "沙星", "环素", "万古霉素", "去甲万古", "阿奇霉素",
      "红霉素", "克拉霉素", "罗红霉素", "甲硝唑", "奥硝唑", "替硝唑", "利奈唑胺", "替考拉宁", "磷霉素", "舒巴坦", "他唑巴坦", "阿维巴坦", "氨曲南",
      "达托霉素", "夫西地酸", "磺胺甲", "复方磺胺", "呋喃妥因", "呋喃唑酮", "吡哌酸", "氟康唑", "伏立康唑", "泊沙康唑", "艾沙康唑", "两性霉素",
      "卡泊芬净", "米卡芬净", "阿尼芬净", "多粘菌素", "多黏菌素", "黏菌素", "阿米卡星", "庆大霉素", "依替米星", "妥布霉素", "克林霉素", "林可霉素");
  static final List<String> NON_ANTIBIOTIC = List.of("肝素", "低分子", "华法林", "阿司匹林", "氯吡格雷", "维生素", "氯化钾", "胰岛素", "碳酸氢钠",
      "呋塞米", "甘露醇", "地塞米松", "甲泼尼龙", "丙泊酚", "咪达唑仑", "芬太尼", "右美托咪定", "吗啡", "布洛芬", "对乙酰氨基酚", "皮试",
      "多西他赛", "替加氟", "克拉屈滨", "米诺地尔", "柔红霉素", "多柔比星", "表柔比星", "博来霉素", "丝裂霉素", "放线菌素", "滴眼液", "眼膏", "软膏");
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
    Boolean on = true;
    for (Action a : acts) {
      Instant t = a.time == null ? d.start : a.time;
      if (t.isAfter(at)) break;
      String raw = a.action == null ? "" : a.action.trim().toLowerCase(Locale.ROOT);
      if ("stop".equals(raw) || "cancel".equals(raw) || "停止".equals(raw) || "取消".equals(raw) || "停用".equals(raw))
        on = false;
      else if ("pause".equals(raw) || "暂停".equals(raw)) on = false;
      else if (RESUME_ACTIONS.contains(raw) || Set.of("start", "开始").contains(raw)) on = true;
      else if (!Set.of("start","开始","add","minus","quickadd").contains(raw)) on = null;
      // Known dose-change actions keep state; unrecognised actions make it unknown.
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
    if (n.isEmpty() || any(n, INOTROPES)) return false;
    if (any(n, VASO_KEYWORDS)) return true;
    return dictionary != null && dictionary.contains(n) && !any(n, VASO_DICTIONARY_EXCLUDE);
  }
  public static boolean isVasoWide(String name, Set<String> dictionary) {
    return dictionary != null && dictionary.stream().anyMatch(n -> norm(n).equals(norm(name))) && !norm(name).isEmpty();
  }
  public static boolean isVasoStrict(String name) {
    return any(name, List.of("去甲肾上腺素", "肾上腺素", "多巴胺", "多巴酚丁胺", "血管加压素", "去氧肾上腺素", "苯肾上腺素", "米力农", "异丙肾上腺素",
      "norepinephrine", "epinephrine", "dopamine", "dobutamine", "vasopressin", "phenylephrine", "milrinone", "isoproterenol"));
  }
  static Boolean all(Boolean a, Boolean b) {
    if (Boolean.FALSE.equals(a) || Boolean.FALSE.equals(b)) return false;
    return a == null || b == null ? null : true;
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
  /** Shared negation-aware reading: "排除感染" / "非感染性发热" / "既往肺炎" are not infection evidence. */
  public static boolean infectionDiagnosis(String text) { return org.icu.sepsis.domain.InfectionText.hasClue(text, INFECTION_DIAG); }

  // ---------------- evaluation ----------------
  public static Map<String, Object> evaluate(Evidence e) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("ruleVersion", RULE_VERSION);
    out.put("ruleSource", RULE_SOURCE);
    out.put("t0SourceId", e.t0SourceId); out.put("t0TimeMeaning", "入科后首条医嘱开立时间");
    out.put("asOf", e.asOf);
    out.put("t0", e.t0);
    out.put("t0Source", e.t0 == null ? null : "入科后首条医嘱开立时间");
    out.put("t0OrderName", e.t0OrderName);
    out.put("pool", pool(e));
    out.put("lactateRecords", lactateRecords(e));
    out.put("truncatedSources", new TreeSet<>(e.truncatedSources));
    out.put("failedSources", new TreeSet<>(e.failed));
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
    Instant gateFrom = gateFrom(e);
    Instant gateTo = min(t0.plus(GATE_AFTER), e.asOf);

    Map<String, Object> gate = new LinkedHashMap<>();
    gate.put("from", gateFrom); gate.put("to", gateTo);
    // ---- organ dysfunction S1-S4 ----
    Map<String, Object> s1 = minItem("S1", "氧合指数 <300", e.pf, gateFrom, gateTo, e.failed.contains("bGATemp"), v -> v < 300, "");
    Map<String, Object> s2 = minItem("S2", "GCS <13", e.gcs, gateFrom, gateTo, e.failed.contains("score"), v -> v < 13, "分");
    Map<String, Object> s3 = minItem("S3", "MAP ≤65", e.map, gateFrom, gateTo, e.failed.contains("bedside"), v -> v <= MAP_LOW, "mmHg");
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
    Map<String, Object> k1 = k1(e);
    Boolean k1v = (Boolean) k1.get("value");
    Map<String, Object> k2 = new LinkedHashMap<>(s4); k2.put("code", "K2"); k2.put("label", "血管活性药执行（宽口径）");
    Boolean k2v = (Boolean) s4.get("value");
    Double mapMin = (Double) s3.get("number");
    Boolean shock = all(k1v, k2v);
    gate.put("suspectedWarning", anyTrue(List.of(k1, k2)));
    gate.put("suspectedRule", "K1 或 K2，仅疑似预警，不计入质控分母");

    String status, label, reason;
    if (Boolean.FALSE.equals(hasOrgan)) { status = "not_met"; label = "未见器官功能障碍"; reason = "NO_ORGAN_DYSFUNCTION"; }
    else if (Boolean.FALSE.equals(shock)) { status = "not_met"; label = "未达本地质控候选条件"; reason = "NOT_SEPTIC_SHOCK"; }
    else if (Boolean.TRUE.equals(shock) && Boolean.TRUE.equals(hasOrgan) && Boolean.TRUE.equals(hasInfection)) { status = "septic_shock"; label = "符合本地质控候选条件"; reason = null; }
    else if (Boolean.TRUE.equals(shock) && !Boolean.TRUE.equals(hasInfection)) { status = "suspected_review"; label = "疑似，待复核感染证据"; reason = "NO_INFECTION_EVIDENCE"; }
    else { status = "unknown"; label = "证据不全，无法判定"; reason = "INSUFFICIENT_EVIDENCE"; }
    gate.put("organ", organ); gate.put("hasOrganDysfunction", hasOrgan);
    gate.put("infection", infection); gate.put("hasInfection", hasInfection);
    gate.put("shock", List.of(k1, k2)); gate.put("mapMin", mapMin); gate.put("isSepticShock", shock);
    gate.put("rule", "本地质控候选 = K1（乳酸≥2）且 K2（宽口径血管活性药执行）");
    gate.put("status", status); gate.put("statusLabel", label); gate.put("reason", reason);
    out.put("gate", gate);
    out.put("status", status);
    out.put("statusLabel", label);
    out.put("applicable", "septic_shock".equals(status) || "suspected_review".equals(status));

    // Culture order timeline for UI (orders only; microbiology result reports not in source).
    List<Map<String, Object>> cultureList = new ArrayList<>();
    e.cultureOrders.stream()
        .filter(o -> o.orderTime != null)
        .sorted(Comparator.comparing((Order o) -> o.orderTime).reversed())
        .limit(8)
        .forEach(
            o -> {
              Map<String, Object> c = new LinkedHashMap<>();
              c.put("name", o.name);
              c.put("orderTime", o.orderTime);
              c.put("reviewTime", o.reviewTime);
              c.put("sourceId", o.sourceId);
              cultureList.add(c);
            });
    out.put("cultureOrders", cultureList);
    List<Map<String, Object>> windows = new ArrayList<>();
    for (int h : new int[] {1, 3, 6}) windows.add(window(e, h));
    out.put("windows", windows);
    out.put("lactateSeries", lactateSeries(e.lactate, t0, e.asOf));
    return out;
  }

  /** Local candidate screening only: maximum valid normalized lactate in an explicitly verified window. */
  static Map<String, Object> k1(Evidence e) {
    boolean selectionConfirmed = "maximum".equals(e.k1Selection);
    boolean windowConfirmed = e.k1WindowStart != null && e.k1WindowEnd != null
        && !e.k1WindowEnd.isBefore(e.k1WindowStart)
        && e.k1WindowSource != null && !e.k1WindowSource.isBlank();
    Instant limit = windowConfirmed && e.asOf != null ? min(e.k1WindowEnd, e.asOf) : null;
    String reason = !selectionConfirmed ? "K1_SELECTION_UNCONFIRMED"
        : !windowConfirmed ? "K1_WINDOW_UNCONFIRMED"
        : lactateFailed(e) ? "SOURCE_ERROR"
        : limit == null || limit.isBefore(e.k1WindowStart) ? "K1_WINDOW_NOT_AVAILABLE" : null;
    Point selected = null;
    if (reason == null) {
      // Values are normalized to mmol/L by the input contract. No missing/invalid value is replaced by zero.
      selected = within(validLactate(e.lactate), e.k1WindowStart, limit).stream()
          .filter(p -> Double.isFinite(p.value) && p.value >= 0)
          .min(Comparator.comparingDouble((Point p) -> -p.value).thenComparing(p -> p.time)
              .thenComparing(p -> Objects.toString(p.sourceId, ""))).orElse(null);
      if (selected == null) reason = "DATA_MISSING_LAC";
    }
    Map<String, Object> k1 = item("K1", "质控候选乳酸最高值", selected == null ? null : selected.value >= LACTATE_HIGH,
        selected == null ? null : selected.time, selected == null ? "" : fmt(selected.value) + " mmol/L", reason);
    k1.put("threshold", "≥2 mmol/L"); k1.put("selection", e.k1Selection);
    k1.put("number", selected == null ? null : selected.value); k1.put("unit", "mmol/L");
    k1.put("sourceId", selected == null ? null : selected.sourceId);
    k1.put("timeMeaning", selected == null ? null : selected.timeMeaning);
    k1.put("windowConfirmed", windowConfirmed); k1.put("windowFrom", e.k1WindowStart);
    k1.put("windowTo", e.k1WindowEnd); k1.put("evaluatedTo", limit); k1.put("windowSource", e.k1WindowSource);
    k1.put("scope", "仅本地质控候选筛查；需结合 K2、感染证据及人工复核，不等同临床确诊");
    return k1;
  }

  /** Collected records for review; independent of the unresolved K1 calculation window. */
  static List<Map<String,Object>> lactateRecords(Evidence e) {
    List<Map<String,Object>> records = new ArrayList<>();
    for (Point p : e.lactate) {
      if (p.time == null || e.asOf == null || p.time.isAfter(e.asOf)) continue;
      Map<String,Object> record = new LinkedHashMap<>();
      record.put("rawValue", p.rawValue != null ? p.rawValue : Double.isFinite(p.value) ? p.value : null);
      record.put("rawUnit", p.rawUnit); record.put("unitConfirmed", p.lactateUnitKnown);
      record.put("value", Double.isFinite(p.value) ? p.value : null);
      record.put("time", p.time); record.put("timeMeaning", p.timeMeaning);
      record.put("sourceId", p.sourceId);
      records.add(record);
    }
    records.sort(Comparator.comparing(r -> (Instant) r.get("time")));
    return records;
  }

  /** T0-24h, but not earlier than hospital (or ICU) admission; not the K1 window. */
  static Instant gateFrom(Evidence e) {
    Instant floor = e.hospitalAdmission != null ? e.hospitalAdmission : e.icuAdmission;
    Instant from = e.t0.minus(GATE_BEFORE);
    if (floor != null && floor.isAfter(from) && !floor.isAfter(e.t0)) from = floor;
    return from;
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
    boolean lacFail = lactateFailed(e), drugFail = e.failed.contains("drugExe"), orderFail = e.failed.contains("VI_ICU_ZYYZ"), mapFail = e.failed.contains("bedside");
    List<Point> lac = within(validLactate(e.lactate), t0, limit);
    // A1 lactate measured (earliest in window, record max)
    Point first = lac.isEmpty() ? null : lac.get(0);
    Double lacMax = lac.stream().map(p -> p.value).max(Double::compare).orElse(null);
    Boolean a1 = lacFail ? null : first == null ? null : true;
    Map<String, Object> a1m = item("A1", "乳酸测定", a1, first == null ? null : first.time,
        first == null ? "" : fmt(first.value) + " mmol/L" + (lac.size() > 1 ? "，最高 " + fmt(lacMax) : ""), a1 == null ? (lacFail ? "SOURCE_ERROR" : "DATA_MISSING_LAC") : null);
    a1m.put("sourceId", first == null ? null : first.sourceId);
    a1m.put("maximum", lacMax);
    a1m.put("timeMeaning", first == null ? null : first.timeMeaning);
    // V3 B1: latest execution start in the window
    Map.Entry<Instant, String> abx = drugFail ? null : latestDrug(e, t0, limit, n -> isAntibiotic(n, e.antibioticDictionary));
    Boolean b1 = abx == null ? null : true;
    Map<String, Object> b1m = item("B1", "抗菌药物执行", b1, abx == null ? null : abx.getKey(), abx == null ? "" : abx.getValue(), b1 == null ? (drugFail ? "SOURCE_ERROR" : "AB_MISSING") : null);
    // V3 B2: latest culture timestamp in the window
    Order bc = orderFail ? null : latestCulture(e, t0, limit);
    Boolean b2 = bc == null ? null : true;
    Map<String, Object> b2m = item("B2", "血培养", b2, bc == null ? null : cultureTime(e, bc), bc == null ? "" : bc.name, b2 == null ? (orderFail ? "SOURCE_ERROR" : "BC_MISSING") : null);
    // V3 B3 compares the selected B1 and B2 in this same window.
    Boolean b3; String b3reason;
    Map.Entry<Instant, String> abxFirst = abx;
    Order bcFirst = bc;
    if (b1 == null) { b3 = null; b3reason = "AB_MISSING"; }
    else if (b2 == null) { b3 = null; b3reason = "BC_MISSING"; }
    else if (abxFirst.getKey().isAfter(cultureTime(e, bcFirst))) { b3 = true; b3reason = null; }
    else { b3 = false; b3reason = "BC_AFTER_AB"; }
    String b3detail = b3reason == null ? "所选抗菌药晚于所选血培养"
        : "BC_AFTER_AB".equals(b3reason) ? "所选抗菌药 " + hhmm(abxFirst.getKey()) + " 早于或同时于所选血培养 " + hhmm(cultureTime(e, bcFirst)) : "";
    Map<String, Object> b3m = item("B3", "先培养后抗菌", b3, null, b3detail, b3reason);
    b1m.put("sourceId", abx == null ? null : e.drugs.stream().filter(d -> abx.getKey().equals(d.start) && d.items.stream().anyMatch(i -> abx.getValue().equals(i.name))).map(d -> d.sourceId).filter(Objects::nonNull).findFirst().orElse(null));
    b1m.put("selection", "latest"); b2m.put("selection", "latest");
    Map.Entry<Instant, String> earliestAbx = drugFail ? null : firstDrug(e, t0, limit, n -> isAntibiotic(n, e.antibioticDictionary));
    Order earliestBc = orderFail ? null : firstCulture(e, t0, limit);
    b1m.put("earliestTime", earliestAbx == null ? null : earliestAbx.getKey());
    b2m.put("earliestTime", earliestBc == null ? null : cultureTime(e, earliestBc));
    b2m.put("sourceId", bc == null ? null : bc.sourceId);
    b2m.put("timeMeaning", "orderTime".equals(e.cultureTimeField) ? "开立时间" : "审核时间");
    b3m.put("selection", "latest_in_window");
    b3m.put("antibioticTime", abx == null ? null : abx.getKey());
    b3m.put("cultureTime", bc == null ? null : cultureTime(e, bc));
    // C1 MAP<=65 trigger, C2 lactate>=4 trigger (opposite semantics: TRUE = abnormal)
    List<Point> maps = within(e.map, t0, limit);
    Point mapLow = maps.stream().min(Comparator.comparingDouble(p -> p.value)).orElse(null);
    Boolean c1 = mapFail ? null : mapLow == null ? null : mapLow.value <= MAP_LOW;
    Map<String, Object> c1m = item("C1", "MAP ≤65（触发）", c1, mapLow == null ? null : mapLow.time,
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
      List<Point> re = within(validLactate(e.lactate), t0.plus(Duration.ofHours(1)), limit);
      Point last = re.isEmpty() ? null : re.get(re.size() - 1);
      Boolean rv;
      if (lacFail) rv = null; else if (last != null) rv = Boolean.TRUE; else rv = closed ? Boolean.FALSE : null;
      recheck = item("A2", "复测乳酸（暂定项，1h 后）", rv, last == null ? null : last.time, last == null ? "" : fmt(last.value) + " mmol/L", last == null ? "LACTATE_RECHECK_MISSING" : null);
      recheck.put("provisional", true);
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
      // Saline/glucose used as the carrier of another drug (e.g. 0.9%氯化钠 100ml + 美罗培南) is not resuscitation fluid.
      if (d.items.stream().anyMatch(it -> it.name != null && !it.name.isBlank() && fluidType(it.name) == null)) continue;
      for (DrugItem it : d.items) {
        if (fluidType(it.name) == null) continue;
        if (it.liquidMl != null && it.liquidUnitKnown && it.liquidMl < FLUID_MIN_BAG_ML) continue;
        count++;
        if (firstTime == null || d.start.isBefore(firstTime)) firstTime = d.start;
        if (it.actualInfusedMl == null || !it.liquidUnitKnown || it.actualInfusedMl < 0) incomplete = true; else sum += it.actualInfusedMl;
      }
    }
    if (hours == 1) {
      Boolean v = count > 0;
      String detail =
          count == 0
              ? ""
              : (sum > 0 ? fmt(sum) + " ml" : count + " 条晶体/胶体执行");
      return item("C3", "1h 内开始补液", v, firstTime, detail, v ? null : "FLUID_MISSING");
    }
    Boolean v = sum >= FLUID_THRESHOLD_ML ? Boolean.TRUE : incomplete ? null : Boolean.FALSE;
    String detail = count == 0 ? "" : "累计 " + fmt(sum) + " ml" + (incomplete ? "（部分记录无量）" : "");
    return item("C3", "补液 ≥1500 ml", v, firstTime, detail, v == null ? "FLUID_AMOUNT_INCOMPLETE" : v ? null : "FLUID_INSUFFICIENT");
  }

  static Map<String, Object> vasopressor(String code, String label, Evidence e, Instant from, Instant to) {
    if (e.failed.contains("drugExe") || e.failed.contains("vasoDictionary")) return item(code, label, null, null, "", "SOURCE_ERROR");
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
        if (!isVasoWide(it.name, e.vasoDictionary)) continue;
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
        Map<String, Object> result = item(code, label, true, d.start, note, null);
        result.put("sourceId", d.sourceId); result.put("drugName", it.name);
        result.put("classification", "VASO_WIDE"); result.put("strict", isVasoStrict(it.name));
        result.put("executionState", "有执行"); return result;
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
        .filter(o -> cultureTime(e,o) != null && !cultureTime(e,o).isBefore(from) && !cultureTime(e,o).isAfter(to))
        .max(Comparator.comparing(o -> cultureTime(e,o))).orElse(null);
  }
  static Map.Entry<Instant, String> latestDrug(Evidence e, Instant from, Instant to, Predicate<String> match) {
    Map.Entry<Instant,String> best = null;
    for (Drug d : e.drugs) {
      if (d.start == null || d.start.isBefore(from) || d.start.isAfter(to)) continue;
      for (DrugItem it : d.items) if (match.test(it.name) && (best == null || d.start.isAfter(best.getKey()))) best = Map.entry(d.start,it.name);
    }
    return best;
  }
  static Order firstCulture(Evidence e, Instant from, Instant to) {
    return e.cultureOrders.stream().filter(o -> o.name != null && e.bloodCultureKeywords.stream().anyMatch(o.name::contains))
        .filter(o -> { Instant t = cultureTime(e, o); return t != null && !t.isBefore(from) && !t.isAfter(to); })
        .min(Comparator.comparing(o -> cultureTime(e, o))).orElse(null);
  }
  static Instant cultureTime(Evidence e, Order o) { return "orderTime".equals(e.cultureTimeField) ? o.orderTime : o.reviewTime; }

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
    List<Point> early = within(validLactate(lactate), t0.minus(Duration.ofHours(2)), cap);
    if (!early.isEmpty()) return early.get(early.size() - 1);
    List<Point> later = within(validLactate(lactate), t0, min(t0.plus(GATE_AFTER), asOf));
    return later.isEmpty() ? null : later.get(0);
  }

  static List<Map<String, Object>> lactateSeries(List<Point> lactate, Instant t0, Instant asOf) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Point p : within(validLactate(lactate), t0.minus(Duration.ofHours(2)), min(t0.plus(GATE_AFTER), asOf))) {
      Map<String, Object> m = new LinkedHashMap<>();
      long min = Duration.between(t0, p.time).toMinutes();
      m.put("time", p.time); m.put("value", p.value); m.put("minutesFromT0", min);
      m.put("period", min <= 60 ? "首次" : min <= 180 ? "1–3h" : "3–6h"); m.put("sourceId", p.sourceId);
      out.add(m);
    }
    return out;
  }

  private static final Map<String, String> FAILED_NAMES = Map.of(
      "VI_ICU_EXAM_ITEM", "检验报告", "VI_ICU_EXAM_ITEM.lactate", "检验乳酸", "VI_ICU_ZYYZ", "医嘱",
      "bGATemp", "血气", "bedside", "观察项", "diagnosis", "诊断", "drugExe", "执行用药", "score", "评分");

  static String failedName(String key) { return FAILED_NAMES.getOrDefault(key, key); }

  static List<String> limitations(Evidence e) {
    List<String> l = new ArrayList<>();
    l.add("K1 暂按确认窗口内有效乳酸最高值判定 ≥2 mmol/L；窗口起止尚未确认时保持无法判定。仅用于本地质控候选筛查，需结合 K2、感染证据与人工复核，不等同临床确诊。");
    l.add("乳酸、氧合指数取血气记录，按病案号关联，动/静脉标本未区分。");
    l.add("抗菌药、补液、升压药取执行用药的开始时间，药名按关键词与药品字典识别。");
    l.add("血培养取医嘱" + ("orderTime".equals(e.cultureTimeField) ? "开立时间" : "审核时间") + "，不等于实际采血时间。");
    l.add("补液只计单独输注的晶体/胶体（作为其他药物溶媒、或单袋 <100 ml 的不计），仅累计单位已确认的实际输入量；整袋容量不视为实际输入量；未按体重 30 ml/kg 换算。");
    l.add("先培养后抗菌：比较首剂抗菌药与首次血培养，均自门控起点（T0 前 24h，不早于入院）起查找；多巴酚丁胺、米力农等强心药不计为升压药。");
    l.add("自动判定与人工覆盖分别保留；感染部位确认未接入；质控候选结果不是临床诊断。");
    if (!e.truncatedSources.isEmpty()) l.add("来源读取达到上限并已截断：" + String.join("、",new TreeSet<>(e.truncatedSources)) + "；相关自动判定保持无法判定。");
    if (!e.failed.isEmpty()) l.add("读取失败或不完整的来源：" + String.join("、", new TreeSet<>(e.failed.stream().map(Icu05Rules::failedName).collect(java.util.stream.Collectors.toList()))) + "；相关项显示为“—”，不按未完成处理。");
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
    m.put("number", low.value); m.put("sourceId", low.sourceId);
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
  static boolean lactateFailed(Evidence e) {
    return e.failed.contains("bGATemp") || e.failed.contains("VI_ICU_EXAM_ITEM.lactate");
  }
  static List<Point> validLactate(List<Point> points) {
    if(points==null)return List.of();
    return points.stream().filter(p->p!=null&&p.lactateUnitKnown&&Double.isFinite(p.value)&&p.value>=0).collect(Collectors.toList());
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
  static String hhmm(Instant t) {
    return t == null ? "—" : java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.of("Asia/Shanghai")).format(t);
  }
  static String clip(String s, int n) { return s == null ? "" : s.length() <= n ? s : s.substring(0, n) + "…"; }
}
