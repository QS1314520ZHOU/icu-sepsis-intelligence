package org.icu.sepsis.quality;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.icu.sepsis.application.Store;
import org.icu.sepsis.domain.Domain.Patient;
import org.icu.sepsis.sepsis.Icu05Service;
import org.icu.sepsis.sepsis.Icu05Rules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Service;

/**
 * Department ICU-05 board for the director: 1h/3h/6h completion rates, the four "septic-shock capability" process
 * sub-indicators, and the live list of cases that still have an open window or a failed window.
 * Read-only; every patient is re-evaluated with the same rules as the bedside card (cached briefly).
 * Unknown ("—") results are reported separately and never counted as not met.
 */
@Service
public class Icu05BoardService {
  private static final Logger log = LoggerFactory.getLogger(Icu05BoardService.class);
  static final Duration CACHE = Duration.ofMinutes(5);
  private final Store store;
  private final Icu05Service icu05;
  private final Clock clock;
  private final Map<String, Cached> cache = new ConcurrentHashMap<>();
  static final class Cached { final Instant at; final Map<String, Object> result; Cached(Instant a, Map<String, Object> r) { at = a; result = r; } }

  @org.springframework.beans.factory.annotation.Autowired
  public Icu05BoardService(Store store, Icu05Service icu05) { this(store, icu05, Clock.systemUTC()); }
  Icu05BoardService(Store store, Icu05Service icu05, Clock clock) { this.store = store; this.icu05 = icu05; this.clock = clock; }

  public Map<String, Object> board(int days) {
    int d = Math.max(1, Math.min(31, days));
    Instant now = clock.instant(), from = now.minus(Duration.ofDays(d));
    Query q = Query.query(store.departmentScope().and("admittedAt").gte(from).lte(now))
        .with(Sort.by(Sort.Direction.DESC, "admittedAt", "id"));
    List<Patient> ps = store.db.find(q, Patient.class);
    List<Case> cases = new ArrayList<>();
    int failed = 0;
    for (Patient p : ps) {
      try { cases.add(new Case(p, cached(p, now))); }
      catch (RuntimeException e) { failed++; log.debug("icu05 board {}", p.id, e); }
    }
    Map<String, Object> out = aggregate(cases, now);
    out.put("from", from); out.put("to", now); out.put("days", d); out.put("readFailed", failed);
    out.put("truncated", false); out.put("matched", ps.size());
    out.put("complete", failed == 0 && ((Number)out.get("sourceIncomplete")).intValue() == 0);
    out.put("scope", "按入科时间、当前宿主科室权限范围筛选；不代表医院源库已完整同步");
    return out;
  }

  @org.springframework.context.event.EventListener
  public void sourceChanged(org.icu.sepsis.integration.SourceChangeDispatcher.Change event) { cache.remove(event.patientId + ":" + event.encounterId + ":" + Icu05Rules.RULE_VERSION); }

  @org.springframework.transaction.event.TransactionalEventListener
  public void reviewChanged(org.icu.sepsis.sepsis.Icu05CaseReviewService.Changed event) {
    cache.remove(event.patientId + ":" + event.encounterId + ":" + Icu05Rules.RULE_VERSION);
  }

  private Map<String, Object> cached(Patient p, Instant now) {
    Cached c = cache.get(p.id + ":" + p.encounterId + ":" + Icu05Rules.RULE_VERSION);
    if (c != null && Duration.between(c.at, now).compareTo(CACHE) < 0) return c.result;
    Map<String, Object> r = icu05.evaluate(p, now);
    cache.put(p.id + ":" + p.encounterId + ":" + Icu05Rules.RULE_VERSION, new Cached(now, r));
    if (cache.size() > 2000) cache.clear();
    return r;
  }

  static final class Case { final Patient p; final Map<String, Object> r; Case(Patient p, Map<String, Object> r) { this.p = p; this.r = r; } }

  static final String[] WINDOWS = {"1h", "3h", "6h"};

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> windows(Map<String, Object> r) {
    Object w = r.get("windows");
    return w instanceof List ? (List<Map<String, Object>>) w : List.of();
  }
  @SuppressWarnings("unchecked")
  static Map<String, Object> item(Map<String, Object> window, String code) {
    Object items = window.get("items");
    if (!(items instanceof List)) return null;
    for (Object o : (List<Object>) items) if (o instanceof Map && code.equals(((Map<String, Object>) o).get("code"))) return (Map<String, Object>) o;
    return null;
  }
  static Map<String, Object> window(Map<String, Object> r, String key) {
    for (Map<String, Object> w : windows(r)) if (key.equals(w.get("key"))) return w;
    return null;
  }
  @SuppressWarnings("unchecked")
  static Boolean eligible(Map<String, Object> r) {
    Object pool = r.get("pool");
    return pool instanceof Map ? (Boolean) ((Map<String, Object>) pool).get("eligible") : null;
  }
  static Instant instant(Object o) {
    if (o instanceof Instant) return (Instant) o;
    if (o instanceof String && !((String) o).isBlank()) try { return Instant.parse((String) o); } catch (DateTimeException e) { return null; }
    return null;
  }

  /** Pure aggregation (unit-tested). */
  static Map<String, Object> aggregate(List<Case> cases, Instant now) {
    int screened = 0, unavailable = 0, shock = 0, suspected = 0, inPool = 0, excluded = 0, poolUnknown = 0;
    Map<String, int[]> win = new LinkedHashMap<>(); // met, not_met, unknown, in_progress
    for (String k : WINDOWS) win.put(k, new int[4]);
    // abx within 1h, culture before abx, lactate within 1h: true, false, unknown
    int[] abx = new int[3], cult = new int[3], lac = new int[3];
    int reviewEligible=0, reviewed=0, caseExcluded=0, judgementReviewed=0, sofaReviewed=0, staleReview=0, classificationUnknown=0, sourceIncomplete=0;
    Instant oldestEvaluation=null;
    Map<String,Map<String,Set<String>>> reasonCases=new LinkedHashMap<>();
    for(String key:WINDOWS)reasonCases.put(key,new TreeMap<>());
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Case c : cases) {
      Map<String, Object> r = c.r;
      screened++;
      if (!Boolean.TRUE.equals(r.get("available"))) { unavailable++; continue; }
      reviewEligible++;
      if (r.get("failedSources") instanceof Collection && !((Collection<?>)r.get("failedSources")).isEmpty()) sourceIncomplete++;
      Instant evaluated = instant(r.get("asOf"));
      if (evaluated != null && (oldestEvaluation == null || evaluated.isBefore(oldestEvaluation))) oldestEvaluation = evaluated;
      Map<String,Object> caseReview = r.get("caseReview") instanceof Map ? (Map<String,Object>)r.get("caseReview") : Map.of();
      boolean caseApplied=Boolean.TRUE.equals(caseReview.get("applied")), judgement=false, sofa=false;
      boolean stale=Boolean.TRUE.equals(caseReview.get("requiresReview"));
      Object overrides=r.get("overrides");
      if (overrides instanceof List) for(Object value:(List<?>)overrides) if(value instanceof Map) {
        Map<?,?> record=(Map<?,?>)value;
        if(Boolean.TRUE.equals(record.get("applied"))) {
          judgement |= "judgement".equals(record.get("scope")); sofa |= "sofa_component".equals(record.get("scope"));
        } else if(record.get("revokedAt")==null) stale=true;
      }
      if(caseApplied)caseExcluded++; if(judgement)judgementReviewed++; if(sofa)sofaReviewed++;
      if(caseApplied||judgement||sofa)reviewed++; if(stale)staleReview++;
      String status = String.valueOf(r.get("status"));
      if("unknown".equals(status))classificationUnknown++;
      boolean isShock = "septic_shock".equals(status), isSuspected = "suspected_review".equals(status);
      if (isSuspected) suspected++;
      if (!isShock && !isSuspected) continue;
      Boolean el = eligible(r);
      if (isShock) {
        shock++;
        if (Boolean.TRUE.equals(el)) inPool++; else if (Boolean.FALSE.equals(el)) excluded++; else poolUnknown++;
      }
      boolean counted = isShock && Boolean.TRUE.equals(el);
      if (counted) {
        for (String k : WINDOWS) {
          Map<String, Object> w = window(r, k);
          int[] a = win.get(k);
          if(w!=null&&"not_met".equals(w.get("state"))&&w.get("reasons") instanceof List)for(Object reason:(List<?>)w.get("reasons")){String code=String.valueOf(reason);reasonCases.get(k).computeIfAbsent(code,x->new HashSet<>()).add(c.p.id+"|"+c.p.encounterId); }
          String s = w == null ? "unknown" : String.valueOf(w.get("state"));
          switch (s) { case "met" -> a[0]++; case "not_met" -> a[1]++; case "in_progress" -> a[3]++; default -> a[2]++; }
        }
        Map<String, Object> w1 = window(r, "1h");
        tally(abx, w1 == null ? null : item(w1, "B1"));
        tally(lac, w1 == null ? null : item(w1, "A1"));
        Map<String, Object> wb = w1 != null && item(w1, "B3") != null ? w1 : window(r, "3h");
        tally(cult, wb == null ? null : item(wb, "B3"));
      }
      rows.add(row(c, r, status, el, counted, now));
    }
    rows.sort(Comparator.comparingInt((Map<String, Object> m) -> (int) m.get("rank"))
        .thenComparing(m -> String.valueOf(m.getOrDefault("nextDeadline", "~"))));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("asOf", now); out.put("ruleVersion", Icu05Rules.RULE_VERSION);
    out.put("ruleSource", "ICU-05 本地质控候选规则；可判定病例达标率，非正式上报率");
    Map<String,Object> reviews=new LinkedHashMap<>();
    reviews.put("denominator",reviewEligible); reviews.put("reviewedCases",reviewed);
    reviews.put("caseExclusions",caseExcluded); reviews.put("judgementCases",judgementReviewed); reviews.put("sofaCases",sofaReviewed);
    reviews.put("requiresReview",staleReview); reviews.put("rate",reviewEligible==0?null:(double)reviewed/reviewEligible);
    reviews.put("rule","分母为本次统计成功取得可用结果的病例；分子为至少一条生效人工覆盖的病例，同一病例去重。各层可重叠；未覆盖不等于未复核。");
    out.put("sourceIncomplete",sourceIncomplete); out.put("manualReviews",reviews); out.put("classificationUnknown",classificationUnknown);
    out.put("oldestEvaluatedAt",oldestEvaluation); out.put("maxCacheAgeSeconds",CACHE.toSeconds());
    out.put("updateMode","按请求重算，最长缓存 5 分钟；未接入可靠来源事件，不保证实时。");
    out.put("screened", screened); out.put("unavailable", unavailable);
    out.put("septicShock", shock); out.put("suspected", suspected);
    out.put("denominator", inPool); out.put("excluded", excluded); out.put("poolUnknown", poolUnknown);
    List<Map<String, Object>> ws = new ArrayList<>();
    for (String k : WINDOWS) {
      int[] a = win.get(k);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("key", k); m.put("met", a[0]); m.put("notMet", a[1]); m.put("unknown", a[2]); m.put("inProgress", a[3]);
      m.put("rate", a[0] + a[1] == 0 ? null : (double) a[0] / (a[0] + a[1]));
      ws.add(m);
    }
    out.put("windows", ws);
    List<Map<String,Object>> breakdown=new ArrayList<>();
    for(String key:WINDOWS)for(var entry:reasonCases.get(key).entrySet())breakdown.add(Map.of("window",key,"code",entry.getKey(),"label",REASON.getOrDefault(entry.getKey(),"原因代码待核对"),"cases",entry.getValue().size()));
    out.put("failureReasons",breakdown);
    out.put("failureReasonDefinition","仅纳入分母且窗口未达标病例，按窗口/原因/患者住院去重；同一病例可有多个原因，各原因数不可相加作为未达标分母。未知及进行中不计为未达标原因。");
    out.put("indicators", List.of(
        indicator("abx1h", "1h 内给予抗菌药物", abx, "1h 时窗 B1：T0 后 1 小时内有抗菌药执行记录"),
        indicator("cultureBeforeAbx", "抗菌药前留取血培养", cult, "B3：窗口内最新血培养审核时间早于最新抗菌药执行时间"),
        indicator("lactate1h", "1h 内监测乳酸", lac, "1h 时窗 A1：T0 后 1 小时内有乳酸结果"),
        unavailableIndicator("oxygenMetabolism", "氧代谢监测", "ScvO₂ / 静脉-动脉 CO₂ 差值数据源未接入")));
    out.put("cases", rows);
    out.put("limitations", List.of(
        "按当前时刻用床旁同一规则重算，非上报口径的最终结果；“—”无法判定单列，不计为未完成。",
        "分母：符合本地质控候选条件且入院 24h 内进 ICU（不含 ICU 期间新发）；纳入条件无法确认的病例单列。",
        "统计范围按入科时间；结果缓存 5 分钟。"));
    return out;
  }

  static void tally(int[] a, Map<String, Object> it) {
    Object v = it == null ? null : it.get("value");
    if (Boolean.TRUE.equals(v)) a[0]++; else if (Boolean.FALSE.equals(v)) a[1]++; else a[2]++;
  }
  static Map<String, Object> indicator(String key, String label, int[] a, String rule) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("key", key); m.put("label", label); m.put("available", true);
    m.put("yes", a[0]); m.put("no", a[1]); m.put("unknown", a[2]);
    m.put("rate", a[0] + a[1] == 0 ? null : (double) a[0] / (a[0] + a[1])); m.put("rule", rule);
    return m;
  }
  static Map<String, Object> unavailableIndicator(String key, String label, String why) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("key", key); m.put("label", label); m.put("available", false); m.put("rate", null); m.put("rule", why);
    return m;
  }

  static final Map<String, String> TODO = Map.of("A1", "乳酸测定", "A2", "复测乳酸", "B1", "抗菌药物", "B2", "血培养", "C3", "补液");
  static final Map<String, String> REASON = Map.ofEntries(
      Map.entry("AB_MISSING", "未见抗菌药执行"), Map.entry("BC_MISSING", "未见血培养"), Map.entry("BC_AFTER_AB", "抗菌药早于血培养"),
      Map.entry("FLUID_MISSING", "未见补液"), Map.entry("FLUID_INSUFFICIENT", "补液量不足"), Map.entry("LACTATE_RECHECK_MISSING", "未复测乳酸"),
      Map.entry("DATA_MISSING_LAC", "未取到乳酸"), Map.entry("A1_NOT_MET", "乳酸未测"));

  static Map<String, Object> row(Case c, Map<String, Object> r, String status, Boolean el, boolean counted, Instant now) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("patientId", c.p.id); m.put("encounterId", c.p.encounterId); m.put("label", c.p.label); m.put("bed", c.p.bed);
    m.put("simulated", c.p.simulated);
    m.put("status", status); m.put("statusLabel", r.get("statusLabel")); m.put("t0", r.get("t0")); m.put("eligible", el); m.put("counted", counted);
    List<Map<String, Object>> ws = new ArrayList<>();
    String next = null; Instant nextAt = null; Set<String> fails = new LinkedHashSet<>();
    boolean open = false, failed = false;
    for (Map<String, Object> w : windows(r)) {
      Map<String, Object> x = new LinkedHashMap<>();
      x.put("key", w.get("key")); x.put("state", w.get("state")); x.put("deadline", w.get("deadline"));
      ws.add(x);
      String state = String.valueOf(w.get("state"));
      if ("in_progress".equals(state)) {
        open = true;
        Instant dl = instant(w.get("deadline"));
        for (String code : List.of("A1", "B2", "B1", "C3", "A2")) {
          Map<String, Object> it = item(w, code);
          if (it != null && it.get("value") == null && (nextAt == null || (dl != null && dl.isBefore(nextAt)))) {
            next = TODO.get(code); nextAt = dl;
            break;
          }
        }
      }
      if ("not_met".equals(state)) {
        failed = true;
        Object rs = w.get("reasons");
        if (rs instanceof List) for (Object o : (List<?>) rs) { String s = REASON.get(String.valueOf(o)); if (s != null) fails.add(s); }
      }
    }
    m.put("windows", ws);
    m.put("next", next); m.put("nextDeadline", nextAt);
    m.put("minutesLeft", nextAt == null ? null : Duration.between(now, nextAt).toMinutes());
    m.put("failReasons", new ArrayList<>(fails));
    // 0: open window with a pending item, 1: suspected (needs review), 2: failed, 3: open nothing pending, 4: rest
    m.put("rank", next != null ? 0 : "suspected_review".equals(status) ? 1 : failed ? 2 : open ? 3 : 4);
    return m;
  }
}
