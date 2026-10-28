package org.icu.sepsis.sepsis;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.icu.sepsis.domain.Domain.Patient;
import org.icu.sepsis.integration.SmartCareSourceReader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Real-time sepsis / septic-shock assessment for host patients.
 * Read-only clinical classification from SmartCare + DataCenter.
 */
@Service
public class SepsisAssessmentService {
  private final ObjectProvider<SmartCareSourceReader> reader;
  private final boolean smartcareMode;

  public SepsisAssessmentService(
      ObjectProvider<SmartCareSourceReader> reader, @Value("${app.source.mode:demo}") String mode) {
    this.reader = reader;
    this.smartcareMode = "smartcare".equalsIgnoreCase(mode);
  }

  public Map<String, Object> assess(Patient p, Instant asOf) {
    Instant now = asOf == null ? Instant.now() : asOf;
    Instant windowStart = now.minus(Duration.ofHours(24));
    Instant t0Candidate = p.admittedAt != null ? p.admittedAt : windowStart;
    // Prefer earliest infection-related anchor in last 72h, else ICU admission within window
    Instant from72 = now.minus(Duration.ofHours(72));

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("patientId", p.id);
    payload.put("asOf", now);
    payload.put("engine", SepsisClinicalRules.ENGINE_VERSION);
    payload.put("ruleSource", SepsisClinicalRules.RULE_SOURCE);
    payload.put("dataMode", smartcareMode ? "smartcare" : "demo");

    SmartCareSourceReader src = reader.getIfAvailable();
    if (src == null || !smartcareMode || !src.isReadable()) {
      payload.put("available", false);
      payload.put("message", "未连接 SmartCare 只读源，无法自动判定");
      payload.put("syndrome", "insufficient_data");
      payload.put("risk", Map.of("score", 0, "level", "minimal", "levelLabel", "极低", "factors", List.of()));
      payload.put("bundle", Map.of());
      payload.put("subtypes", List.of());
      return payload;
    }

    String diagnosis = src.diagnosisText(p.id);
    Map<String, Object> vitals = src.vitalsWindow(p.id, from72, now);
    Double mapMin = (Double) vitals.get("mapMin");
    Double lactateMax = (Double) vitals.get("lactateMax");
    Double gcsMin = (Double) vitals.get("gcsMin");
    Double sofaMax = (Double) vitals.get("sofaMax");
    Double rrMax = (Double) vitals.get("rrMax");
    Double sbpMin = (Double) vitals.get("sbpMin");

    boolean vaso = src
            .findDrugEvent(p.id, from72, now, SepsisClinicalRules.VASO_KEYWORDS)
            .isPresent()
        || src
            .findOrderEvent(
                src.hisPidOf(p.id), from72, now, SepsisClinicalRules.VASO_KEYWORDS)
            .isPresent();

    boolean infectionDiag = SepsisClinicalRules.infectionText(diagnosis);
    boolean abx =
        src
            .findDrugEvent(p.id, from72, now, SepsisClinicalRules.ANTIBIOTIC_KEYWORDS)
            .isPresent()
        || src
            .findOrderEvent(
                src.hisPidOf(p.id), from72, now, SepsisClinicalRules.ANTIBIOTIC_KEYWORDS)
            .isPresent();
    boolean culture =
        src
            .findOrderEvent(
                src.hisPidOf(p.id), from72, now, SepsisClinicalRules.CULTURE_KEYWORDS)
            .isPresent();
    boolean infection = infectionDiag || abx || culture;

    int qsofa = SepsisClinicalRules.qSofa(sbpMin, rrMax, gcsMin);
    Boolean organ = SepsisClinicalRules.organDysfunction(vitalsMap(vitals), sofaMax, gcsMin, vaso);
    boolean shock = SepsisClinicalRules.septicShockS3(vaso, lactateMax, mapMin);
    boolean dataComplete = mapMin != null || lactateMax != null || vaso || sofaMax != null || gcsMin != null;
    String syndrome = SepsisClinicalRules.syndrome(infection, organ, shock, qsofa, dataComplete);

    // Bundle clocks: anchor = first antibiotic/culture/order if any, else admission
    Instant t0Raw = firstPresent(
            src.findDrugEvent(p.id, from72, now, SepsisClinicalRules.ANTIBIOTIC_KEYWORDS).map(new java.util.function.Function<SmartCareSourceReader.EventHit, Instant>() {
              @Override public Instant apply(SmartCareSourceReader.EventHit e) { return e.time(); }
            }),
            src.findOrderEvent(src.hisPidOf(p.id), from72, now, SepsisClinicalRules.ANTIBIOTIC_KEYWORDS)
                .map(new java.util.function.Function<SmartCareSourceReader.EventHit, Instant>() {
                  @Override public Instant apply(SmartCareSourceReader.EventHit e) { return e.time(); }
                }),
            src.findOrderEvent(src.hisPidOf(p.id), from72, now, SepsisClinicalRules.CULTURE_KEYWORDS)
                .map(new java.util.function.Function<SmartCareSourceReader.EventHit, Instant>() {
                  @Override public Instant apply(SmartCareSourceReader.EventHit e) { return e.time(); }
                }));
    Instant t0 = t0Raw != null ? t0Raw : t0Candidate;

    Instant lactateFirst = null;
    Instant lactateRecheck = null;
    List<org.icu.sepsis.domain.Domain.Observation> lacObs =
        new ArrayList<>(src.observations(p.id, "", "", from72, now));
    lacObs.removeIf(o -> !"LACTATE".equals(o.parameterCode));
    lacObs.sort(Comparator.comparing(o -> o.eventTime));
    if (!lacObs.isEmpty()) {
      lactateFirst = lacObs.get(0).eventTime;
      if (lacObs.size() > 1) lactateRecheck = lacObs.get(lacObs.size() - 1).eventTime;
    }

    var abxHit =
        src.findDrugEvent(p.id, t0.minus(Duration.ofHours(2)), t0.plus(Duration.ofHours(6)), SepsisClinicalRules.ANTIBIOTIC_KEYWORDS);
    if (abxHit.isEmpty())
      abxHit =
          src.findOrderEvent(
              src.hisPidOf(p.id), t0.minus(Duration.ofHours(2)), t0.plus(Duration.ofHours(6)),
              SepsisClinicalRules.ANTIBIOTIC_KEYWORDS);
    var cultureHit =
        src.findOrderEvent(
            src.hisPidOf(p.id), t0.minus(Duration.ofHours(2)), t0.plus(Duration.ofHours(3)),
            SepsisClinicalRules.CULTURE_KEYWORDS);
    double fluidMl = src.fluidVolumeMl(p.id, t0, t0.plus(Duration.ofHours(3)));
    Double mapMinBundle = mapMin;
    Double lacMaxBundle = lactateMax;

    java.util.function.Function<SmartCareSourceReader.EventHit, Instant> timeFn =
        new java.util.function.Function<SmartCareSourceReader.EventHit, Instant>() {
          @Override
          public Instant apply(SmartCareSourceReader.EventHit e) {
            return e.time();
          }
        };
    BundleModels.BundleWindow b1 =
        BundleModels.oneHour(
            lactateFirst != null,
            lactateFirst,
            cultureHit.isPresent(),
            cultureHit.map(timeFn).orElse(null),
            abxHit.isPresent(),
            abxHit.map(timeFn).orElse(null));
    BundleModels.BundleWindow b3 =
        BundleModels.threeHour(
            mapMinBundle != null && mapMinBundle < 70,
            lacMaxBundle != null && lacMaxBundle >= 4,
            fluidMl > 0 ? fluidMl : null,
            lactateRecheck != null,
            lactateRecheck,
            t0);
    BundleModels.BundleWindow b6 =
        BundleModels.sixHour(
            shock,
            vaso,
            true,
            lactateRecheck != null);

    Map<String, Object> risk =
        SepsisClinicalRules.riskScore(
            syndrome,
            qsofa,
            mapMin,
            lactateMax,
            gcsMin,
            sofaMax,
            vaso,
            infection,
            Boolean.TRUE.equals(b1.finish),
            Boolean.TRUE.equals(b3.finish));

    Map<String, Object> shockBlock = new LinkedHashMap<>();
    shockBlock.put("confirmed", shock);
    shockBlock.put("rule", "s3: 升压药 或 (乳酸≥2 且 MAP<70)");
    shockBlock.put("k1LactateGe2", lactateMax != null && lactateMax >= 2);
    shockBlock.put("k2Vasopressor", vaso);
    shockBlock.put("mapMin", mapMin);
    shockBlock.put("lactateMax", lactateMax);
    shockBlock.put("qsofa", qsofa);
    shockBlock.put("organDysfunction", organ);
    shockBlock.put("infectionEvidence", infection);
    shockBlock.put("infectionFromDiagnosis", infectionDiag);
    shockBlock.put("antibiotic", abx);
    shockBlock.put("culture", culture);

    payload.put("available", true);
    payload.put("syndrome", syndrome);
    payload.put("syndromeLabel", syndromeLabel(syndrome));
    payload.put("shock", shockBlock);
    payload.put("risk", risk);
    payload.put(
        "bundle",
        Map.of(
            "t0", t0,
            "1h", b1,
            "3h", b3,
            "6h", b6));
    payload.put("subtypes", SepsisClinicalRules.subtypes(diagnosis, vitalsMap(vitals), vaso));
    payload.put("vitals", vitals);
    payload.put("diagnosisSnippet", diagnosis.length() > 300 ? diagnosis.substring(0, 300) : diagnosis);
    payload.put(
        "ai",
        Map.of(
            "method", "explainable_multifactor_rules",
            "notDiagnosis", true,
            "promptNote",
            "可解释多因素规则分；非经外部验证的深度学习概率模型。结合 SSC/Sepsis-3 与院内 ICU-05 口径。",
            "nextActions", nextActions(syndrome, shock, Boolean.TRUE.equals(b1.finish), Boolean.TRUE.equals(b3.finish))));
    return payload;
  }

  private static List<String> nextActions(String syndrome, boolean shock, boolean b1, boolean b3) {
    List<String> a = new ArrayList<>();
    if (shock) {
      a.add("按脓毒性休克路径：升压药滴定 MAP≥65，评估乳酸清除");
    } else if ("sepsis".equals(syndrome)) {
      a.add("确认感染源与培养已送，1h/3h Bundle 计时");
    } else if ("sepsis_suspect_qsofa".equals(syndrome)) {
      a.add("qSOFA 阳性：尽快复评灌注与乳酸，勿仅凭 qSOFA 确诊");
    }
    if (!b1) a.add("补齐 1h：乳酸、血/痰/尿培养、广谱抗菌药物");
    if (!b3) a.add("评估 3h：MAP<70 或乳酸≥4 时晶体/胶体复苏并复测乳酸");
    if (a.isEmpty()) a.add("继续监测；Bundle 项若数据不全请人工确认");
    return a;
  }

  private static String syndromeLabel(String s) {
    if ("septic_shock".equals(s)) return "脓毒性休克";
    if ("sepsis".equals(s)) return "脓毒症（感染+器官障碍）";
    if ("sepsis_suspect_qsofa".equals(s)) return "疑似脓毒症（qSOFA）";
    if ("infection_at_risk".equals(s)) return "感染证据·待评估器官障碍";
    if ("noninfectious_organ_failure".equals(s)) return "非感染性器官功能障碍";
    if ("insufficient_data".equals(s)) return "数据不足";
    return "未触发";
  }

  private static Map<String, Double> vitalsMap(Map<String, Object> vitals) {
    Map<String, Double> m = new HashMap<>();
    for (Map.Entry<String, Object> e : vitals.entrySet()) {
      if (e.getValue() instanceof Double) m.put(e.getKey(), (Double) e.getValue());
    }
    return m;
  }

  @SafeVarargs
  private static Instant firstPresent(Optional<Instant>... opts) {
    for (Optional<Instant> o : opts) if (o.isPresent()) return o.get();
    return null;
  }
}
