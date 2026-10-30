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
  public static final String RULE_VERSION = "screen-alert-v1";
  static final Set<String> OPEN = Set.of("pending", "acknowledged", "deferred", "confirmed");
  private static final Logger log = LoggerFactory.getLogger(ScreeningAlertService.class);

  /** One screening hit. */
  public static final class Hit {
    public final String kind, severity, reason, source;
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

  private org.springframework.beans.factory.ObjectProvider<org.icu.sepsis.ai.AiAssistService> ai;
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
        if (map == null || num(f.get("value")) < num(map.get("value"))) map = f;
      }
    }
    boolean vaso = Boolean.TRUE.equals(review.get("vasopressorActive"));
    if (vaso || (lac != null && map != null)) {
      List<String> why = new ArrayList<>();
      if (vaso) why.add("正在使用升压药");
      if (lac != null && map != null) why.add("乳酸 " + fmt(lac.get("value")) + " mmol/L 且 " + map.get("label") + " " + fmt(map.get("value")) + " mmHg");
      out.add(new Hit(SHOCK, "critical", "床旁筛查疑似休克：" + String.join("；", why) + "（规则：需要升压药，或 乳酸≥2 且 MAP<65）", "bedside"));
    }
    String clue = String.valueOf(review.getOrDefault("infectionClue", ""));
    if (clue.contains("含感染") && !findings.isEmpty()) {
      List<String> items = new ArrayList<>();
      for (Map<String, Object> f : findings) items.add(f.get("label") + " " + fmt(f.get("value")));
      out.add(new Hit(INFECTION, "high", "诊断文本含感染线索，且存在异常：" + String.join("、", items), "bedside"));
    }
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
    if ("septic_shock".equals(status) || "suspected_review".equals(status)) {
      String extra = gate.get("mapMin") == null ? "" : "；门控期最低 MAP " + fmt(gate.get("mapMin")) + " mmHg";
      out.add(new Hit(SHOCK, "critical", "ICU-05 门控：" + gate.get("statusLabel") + extra + "（规则：需要升压药，或 乳酸≥2 且 MAP<65）", "icu05"));
    } else if (Boolean.TRUE.equals(gate.get("hasInfection")) && Boolean.TRUE.equals(gate.get("hasOrganDysfunction"))) {
      out.add(new Hit(INFECTION, "high", "ICU-05 门控：有感染证据且存在器官功能障碍，未达休克判定", "icu05"));
    }
    return out;
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
    Instant now = clock.instant();
    Query q = Query.query(Criteria.where("admittedAt").gte(now.minus(Duration.ofDays(scanDays))))
        .with(Sort.by(Sort.Direction.DESC, "admittedAt")).limit(500);
    int n = 0;
    for (Patient p : store.db.find(q, Patient.class)) {
      List<Hit> hits = new ArrayList<>();
      try { hits.addAll(fromReview(sepsis.assess(p, now))); } catch (RuntimeException e) { log.debug("screen review {}", p.id, e); }
      try { hits.addAll(fromIcu05(icu05.evaluate(p, now))); } catch (RuntimeException e) { log.debug("screen icu05 {}", p.id, e); }
      n += raise(p, hits);
    }
    if (n > 0) log.info("screening sweep raised {} alert(s)", n);
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
          open.lastSeenAt = now;
          store.db.save(open);
          continue;
        }
        Alert last = store.db.findOne(Query.query(same).with(Sort.by(Sort.Direction.DESC, "createdAt")), Alert.class);
        if (last != null && last.closedAt != null) {
          Duration hold = "rejected".equals(last.status) ? Duration.ofHours(rejectSuppressHours) : Duration.ofMinutes(closeSuppressMinutes);
          if (last.closedAt.plus(hold).isAfter(now)) continue;
        }
        Alert a = new Alert();
        store.init(a, p, now);
        a.kind = h.kind; a.source = h.source; a.severity = h.severity; a.status = "pending";
        a.ruleVersion = RULE_VERSION; a.reason = h.reason; a.trigger = h.reason; a.lastSeenAt = now;
        a.patientLabel = p.label; a.bed = p.bed;
        store.db.insert(a);
        store.recordSystem(a, p, "alert", "created", "system", h.reason, now);
        created++;
        // Background AI explanation (no-op when no model is configured); never blocks or fails the alert.
        try {
          org.icu.sepsis.ai.AiAssistService svc = ai == null ? null : ai.getIfAvailable();
          if (svc != null) svc.explainAlertAsync(a);
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
