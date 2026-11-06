package org.icu.sepsis.alerting;

import java.time.*;
import java.util.*;
import org.icu.sepsis.application.Store;
import org.icu.sepsis.domain.Domain.*;
import org.icu.sepsis.sepsis.Icu05Service;
import org.icu.sepsis.sepsis.SepsisAssessmentService;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Turns bedside screening / ICU-05 gate results into reviewable alerts for real (and simulated) patients.
 * Two kinds: 疑似休克 (critical) and 感染 + 异常 (high). One open alert per patient/encounter/kind; a rejected
 * alert suppresses the same kind for a configurable period. Never a diagnosis — the doctor reviews every alert.
 */
@Service
public class ScreeningAlertService {
  public static final String SHOCK = "suspected_shock", INFECTION = "infection_abnormal";
  public static final String RULE_VERSION = "screen-alert-v2";
  static final Set<String> OPEN = Set.of("pending", "acknowledged", "deferred", "confirmed");
  private static final Logger log = LoggerFactory.getLogger(ScreeningAlertService.class);

  /** One screening hit. */
  public static final class Hit {
    public final String kind, severity, reason, source;
    public String evidenceFingerprint;
    Hit(String kind, String severity, String reason, String source) {
      this.kind = kind; this.severity = severity; this.reason = reason; this.source = source;
    }
    public String toString() { return kind + ":" + severity + ":" + reason; }
  }

  private final Store store;
  private final SepsisAssessmentService sepsis;
  private final Icu05Service icu05;
  private final Clock clock;
  private final boolean enabled;
  private final int scanDays, rejectSuppressHours, closeSuppressMinutes;
  @Value("${app.alerts.auto-ai-explanation:false}") boolean autoAiExplanation = false;

  /** Local review changes invalidate the next scheduled evaluation; no hospital event feed is assumed. */
  @org.springframework.context.event.EventListener
  public void sourceChanged(org.icu.sepsis.integration.SourceChangeDispatcher.Change event) { lastFingerprint.remove(event.patientId); lastFullEval.remove(event.patientId); }

  @org.springframework.transaction.event.TransactionalEventListener
  public void reviewChanged(org.icu.sepsis.sepsis.Icu05CaseReviewService.Changed event) {
    lastFingerprint.remove(event.patientId); lastFullEval.remove(event.patientId);
  }

  private org.springframework.beans.factory.ObjectProvider<org.icu.sepsis.ai.AiAssistService> ai;
  private org.springframework.beans.factory.ObjectProvider<org.icu.sepsis.integration.SmartCareSourceReader> source;
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setSource(org.springframework.beans.factory.ObjectProvider<org.icu.sepsis.integration.SmartCareSourceReader> source) { this.source = source; }

  /** 只扫 SmartCare 仍在科（status=admitted）的病人；出科病人不再反复计算。 */
  @Value("${app.alerts.in-ward-only:true}") boolean inWardOnly = true;
  /** 单轮最多评估人数，防止一次扫描拖垮源库。 */
  @Value("${app.alerts.max-patients:200}") int maxPatients = 200;
  /** ICU-05 门控只对入科不超过 N 天的病人计算（T0 在入科早期）；床旁筛查不受限。 */
  @Value("${app.alerts.icu05-scan-days:7}") int icu05ScanDays = 7;
  /** 病人无新观测时跳过（同一病人上轮已评估且数据指纹未变）。 */
  @Value("${app.alerts.skip-unchanged:true}") boolean skipUnchanged = true;
  /** 即使数据未变，也至少每 N 分钟完整评估一次（抑制期到期、时窗推进、执行单/医嘱变化）。 */
  @Value("${app.alerts.full-eval-minutes:60}") int fullEvalMinutes = 60;
  private final Map<String, String> lastFingerprint = new java.util.concurrent.ConcurrentHashMap<>();
  private final Map<String, Instant> lastFullEval = new java.util.concurrent.ConcurrentHashMap<>();
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setAi(org.springframework.beans.factory.ObjectProvider<org.icu.sepsis.ai.AiAssistService> ai) { this.ai = ai; }

  public ScreeningAlertService(Store store, SepsisAssessmentService sepsis, Icu05Service icu05, Clock clock,
      @Value("${app.alerts.auto:true}") boolean enabled,
      @Value("${app.alerts.scan-days:30}") int scanDays,
      @Value("${app.alerts.reject-suppress-hours:6}") int rejectSuppressHours,
      @Value("${app.alerts.close-suppress-minutes:60}") int closeSuppressMinutes) {
    this.store = store; this.sepsis = sepsis; this.icu05 = icu05; this.clock = clock; this.enabled = enabled;
    this.scanDays = scanDays; this.rejectSuppressHours = rejectSuppressHours; this.closeSuppressMinutes = closeSuppressMinutes;
  }

  // ---------------------------------------------------------------- classification (pure, unit-tested)

  /** Bedside evidence review (schema v2): vasopressor / lactate≥2 + MAP<65 → shock; infection clue + finding → high. */
  @SuppressWarnings("unchecked")
  public static List<Hit> fromReview(Map<String, Object> review) {
    List<Hit> out = new ArrayList<>();
    if (review == null || !Boolean.TRUE.equals(review.get("available"))) return out;
    List<Map<String, Object>> findings = (List<Map<String, Object>>) review.getOrDefault("findings", List.of());
    Map<String, Object> lac = null, map = null;
    for (Map<String, Object> f : findings) {
      String code = String.valueOf(f.get("code"));
      if ("LACTATE".equals(code)) lac = f;
      if (code.equals("MAP") || code.startsWith("MAP_")) {
        if (map == null || num(f.get("value")) < num(map.get("value"))
            || num(f.get("value")) == num(map.get("value")) && code.compareTo(String.valueOf(map.get("code"))) < 0) map = f;
      }
    }
    boolean vaso = Boolean.TRUE.equals(review.get("vasopressorActive"));
    if (vaso || (lac != null && map != null)) {
      List<String> why = new ArrayList<>();
      if (vaso) why.add("正在使用升压药");
      if (lac != null && map != null) why.add("乳酸 " + fmt(lac.get("value")) + " mmol/L 且 " + map.get("label") + " " + fmt(map.get("value")) + " mmHg");
      out.add(new Hit(SHOCK, "critical", "床旁筛查疑似休克：" + String.join("；", why) + "（本地候选须 K1 且 K2；K1 或 K2 仅疑似预警）", "bedside"));
    }
    String clue = String.valueOf(review.getOrDefault("infectionClue", ""));
    if (clue.contains("含感染") && !findings.isEmpty()) {
      List<String> items = new ArrayList<>();
      for (Map<String, Object> f : findings) items.add(f.get("label") + " " + fmt(f.get("value")));
      Collections.sort(items);
      out.add(new Hit(INFECTION, "high", "诊断文本含感染线索，且存在异常：" + String.join("、", items), "bedside"));
    }
    for (Hit h : out) h.evidenceFingerprint = signature(RULE_VERSION+"|"+h.source+"|"+h.reason, reviewEvidence(review,h.kind));
    return out;
  }

  /** ICU-05 gate: septic_shock / suspected_review → shock; infection + organ dysfunction → high. */
  @SuppressWarnings("unchecked")
  public static List<Hit> fromIcu05(Map<String, Object> bundle) {
    List<Hit> out = new ArrayList<>();
    if (bundle == null || !Boolean.TRUE.equals(bundle.get("available"))) return out;
    Object g = bundle.get("gate");
    if (!(g instanceof Map)) return out;
    Map<String, Object> gate = (Map<String, Object>) g;
    String status = String.valueOf(gate.get("status"));
    if ("septic_shock".equals(status) || "suspected_review".equals(status) || Boolean.TRUE.equals(gate.get("suspectedWarning"))) {
      String extra = gate.get("mapMin") == null ? "" : "；门控期最低 MAP " + fmt(gate.get("mapMin")) + " mmHg";
      out.add(new Hit(SHOCK, "critical", "ICU-05 门控：" + gate.get("statusLabel") + extra + "（本地质控候选须 K1 且 K2；K1 或 K2 仅疑似预警，需人工复核）", "icu05"));
    } else if (Boolean.TRUE.equals(gate.get("hasInfection")) && Boolean.TRUE.equals(gate.get("hasOrganDysfunction"))) {
      out.add(new Hit(INFECTION, "high", "ICU-05 门控：有感染证据且存在器官功能障碍，未达本地质控候选条件", "icu05"));
    }
    List<Map<String,Object>> evidence = new ArrayList<>();
    for (String group : List.of("organ","infection","shock")) {
      Object values=gate.get(group); if(values instanceof List) for(Object raw:(List<?>)values) if(raw instanceof Map) evidence.add(select((Map<?,?>)raw));
    }
    for(Hit h:out) h.evidenceFingerprint=signature(RULE_VERSION+"|"+h.source+"|"+h.reason,evidence);
    return out;
  }

  static Map<String,Object> select(Map<?,?> value) {
    Map<String,Object> selected=new TreeMap<>();
    for(String key:List.of("code","value","number","unit","time","eventTime","sourceId","sourceRecordId","reason","windowConfirmed"))
      if(value.containsKey(key)) selected.put(key,value.get(key));
    return selected;
  }
  static List<Map<String,Object>> reviewEvidence(Map<String,Object> review,String kind) {
    List<Map<String,Object>> selected=new ArrayList<>();Object raw=review.get("findings");
    if(raw instanceof List)for(Object item:(List<?>)raw)if(item instanceof Map){Map<?,?> map=(Map<?,?>)item;String code=String.valueOf(map.get("code"));
      if(!SHOCK.equals(kind)||"LACTATE".equals(code)||code.startsWith("MAP"))selected.add(select(map));}
    return selected;
  }
  static String canonical(Object value) {
    if(value instanceof Map){TreeMap<String,String> sorted=new TreeMap<>();((Map<?,?>)value).forEach((k,v)->sorted.put(String.valueOf(k),canonical(v)));return sorted.toString();}
    if(value instanceof Collection){List<String> sorted=new ArrayList<>();for(Object item:(Collection<?>)value)sorted.add(canonical(item));Collections.sort(sorted);return sorted.toString();}
    String text=Objects.toString(value,"null");return text.length()+":"+text;
  }
  static String signature(String reason,Object evidence) {
    try {return Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256")
        .digest((reason+"|"+canonical(evidence)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
  }
  static boolean changed(Alert alert,Hit hit) {
    return alert.evidenceFingerprint!=null && hit.evidenceFingerprint!=null && !alert.evidenceFingerprint.equals(hit.evidenceFingerprint);
  }

  // ---------------------------------------------------------------- persistence

  /** Called after the panel loads bedside review; never throws. */
  public void onReview(Patient p, Map<String, Object> review) { raise(p, fromReview(review)); }

  /** Called after the panel loads ICU-05; never throws. */
  public void onIcu05(Patient p, Map<String, Object> bundle) { raise(p, fromIcu05(bundle)); }

  /** Background sweep of recent admissions so alerts appear without anyone opening the panel. */
  @Scheduled(initialDelayString = "${app.alerts.initial-delay-ms:120000}", fixedDelayString = "${app.alerts.scan-ms:600000}")
  public void scan() {
    if (!enabled) return;
    long started = System.nanoTime();
    Instant now = clock.instant();
    Query q = Query.query(Criteria.where("admittedAt").gte(now.minus(Duration.ofDays(scanDays))))
        .with(Sort.by(Sort.Direction.DESC, "admittedAt")).limit(500);
    List<Patient> candidates = store.db.find(q, Patient.class);
    List<Patient> ward = filterInWard(candidates);
    if (ward.size() > maxPatients) ward = ward.subList(0, maxPatients);
    int n = 0, evaluated = 0, skipped = 0;
    Set<String> alive = new HashSet<>();
    for (Patient p : ward) {
      alive.add(p.id);
      List<Hit> hits = new ArrayList<>();
      Map<String, Object> review = null;
      try { review = sepsis.assess(p, now); hits.addAll(fromReview(review)); } catch (RuntimeException e) { log.debug("screen review {}", p.id, e); }
      String fp = fingerprint(review);
      Instant full = lastFullEval.get(p.id);
      boolean fresh = full != null && full.isAfter(now.minus(Duration.ofMinutes(fullEvalMinutes)));
      if (skipUnchanged && fresh && fp != null && fp.equals(lastFingerprint.get(p.id))) { skipped++; continue; }
      boolean icu05Due = p.admittedAt == null || p.admittedAt.isAfter(now.minus(Duration.ofDays(icu05ScanDays)));
      if (icu05Due) try { hits.addAll(fromIcu05(icu05.evaluate(p, now))); } catch (RuntimeException e) { log.debug("screen icu05 {}", p.id, e); }
      evaluated++;
      n += raise(p, hits);
      if (fp != null) lastFingerprint.put(p.id, fp);
      lastFullEval.put(p.id, now);
    }
    lastFingerprint.keySet().retainAll(alive);
    lastFullEval.keySet().retainAll(alive);
    long ms = (System.nanoTime() - started) / 1_000_000;
    if (n > 0 || ms > 30_000)
      log.info("screening sweep: candidates={} inWard={} evaluated={} unchanged={} newAlerts={} took={}ms", candidates.size(), ward.size(), evaluated, skipped, n, ms);
    else log.debug("screening sweep: candidates={} inWard={} evaluated={} unchanged={} took={}ms", candidates.size(), ward.size(), evaluated, skipped, ms);
  }

  /** SmartCare 模式下只保留仍在科的病人；读取失败时退回全部候选（宁可多扫，不漏扫）。 */
  List<Patient> filterInWard(List<Patient> candidates) {
    org.icu.sepsis.integration.SmartCareSourceReader r = source == null ? null : source.getIfAvailable();
    if (!inWardOnly || r == null || candidates.isEmpty()) return new ArrayList<>(candidates);
    try {
      List<String> ids = new ArrayList<>();
      for (Patient p : candidates) ids.add(p.id);
      Set<String> admitted = r.admittedAmong(ids);
      List<Patient> out = new ArrayList<>();
      for (Patient p : candidates) if (admitted.contains(p.id)) out.add(p);
      return out;
    } catch (RuntimeException e) {
      log.warn("in-ward filter failed, scanning all candidates: {}", e.toString());
      return new ArrayList<>(candidates);
    }
  }

  /** 床旁证据的指纹：各指标最新记录时间+数值、升压药/感染线索。无新数据 → 指纹不变 → 本轮跳过 ICU-05 与预警写入。 */
  @SuppressWarnings("unchecked")
  static String fingerprint(Map<String, Object> review) {
    if (review == null || !Boolean.TRUE.equals(review.get("available"))) return null;
    StringBuilder b = new StringBuilder();
    for (Map<String, Object> e : (List<Map<String, Object>>) review.getOrDefault("latestEvidence", List.of()))
      b.append(e.get("code")).append('@').append(e.get("eventTime")).append('=').append(e.get("value")).append(';');
    b.append("|v=").append(review.get("vasopressorActive")).append("|i=").append(review.get("infectionClue"))
        .append("|m=").append(review.get("missingFeatures"));
    return b.toString();
  }

  /** Returns the number of new alerts. */
  synchronized int raise(Patient p, List<Hit> hits) {
    if (!enabled || p == null || hits.isEmpty()) return 0;
    int created = 0;
    try {
      Map<String, Hit> byKind = new LinkedHashMap<>();
      for (Hit h : hits) byKind.merge(h.kind, h, (a, b) -> a.reason.length() >= b.reason.length() ? a : b);
      // A shock alert supersedes the weaker infection+abnormality alert for the same screen.
      if (byKind.containsKey(SHOCK)) byKind.remove(INFECTION);
      Instant now = clock.instant();
      for (Hit h : byKind.values()) {
        Criteria same = Criteria.where("patientId").is(p.id).and("encounterId").is(p.encounterId).and("kind").is(h.kind);
        Alert open = store.db.findOne(Query.query(Criteria.where("status").in(OPEN).andOperator(same)), Alert.class);
        if (open != null) {
          boolean changed=changed(open,h);
          open.lastSeenAt = now;
          if(changed){open.needsReReview=true;open.evidenceChangedAt=now;open.latestTrigger=h.reason;open.evidenceFingerprint=h.evidenceFingerprint;}
          // Legacy rows establish a baseline without claiming new evidence.
          if(open.evidenceFingerprint==null)open.evidenceFingerprint=h.evidenceFingerprint;
          store.db.save(open);
          if(changed)store.recordSystem(open,p,"alert","evidence_changed","system","触发证据变化，请重新复核；原处置意见保留",now);
          continue;
        }
        Alert last = store.db.findOne(Query.query(same).with(Sort.by(Sort.Direction.DESC, "createdAt")), Alert.class);
        if (last != null && last.closedAt != null) {
          Duration hold = "rejected".equals(last.status) ? Duration.ofHours(rejectSuppressHours) : Duration.ofMinutes(closeSuppressMinutes);
          if (last.closedAt.plus(hold).isAfter(now) && !changed(last,h)) continue;
        }
        Alert a = new Alert();
        store.init(a, p, now);
        a.kind = h.kind; a.source = h.source; a.severity = h.severity; a.status = "pending";
        a.ruleVersion = RULE_VERSION; a.reason = h.reason; a.trigger = h.reason; a.lastSeenAt = now;
        a.patientLabel = p.label; a.bed = p.bed; a.evidenceFingerprint=h.evidenceFingerprint;
        a.latestTrigger=h.reason; a.reviewChainId=last==null?a.id:(last.reviewChainId==null?last.id:last.reviewChainId);
        if(last!=null){a.previousAlertId=last.id;a.needsReReview=changed(last,h);if(a.needsReReview)a.evidenceChangedAt=now;}
        store.db.insert(a);
        store.recordSystem(a, p, "alert", "created", "system", h.reason, now);
        created++;
        // AI explanation is on demand by default; explicit configuration enables background generation.
        try {
          org.icu.sepsis.ai.AiAssistService svc = ai == null ? null : ai.getIfAvailable();
          if (autoAiExplanation && svc != null) svc.explainAlertAsync(a);
        } catch (RuntimeException ignored) { }
      }
    } catch (RuntimeException e) {
      log.warn("screening alert for {} failed: {}", p.id, e.toString());
    }
    return created;
  }

  private static double num(Object o) { return o instanceof Number ? ((Number) o).doubleValue() : Double.MAX_VALUE; }
  private static String fmt(Object o) {
    if (!(o instanceof Number)) return String.valueOf(o);
    double v = ((Number) o).doubleValue();
    return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 10) / 10.0);
  }
}
