package org.icu.sepsis.domain;

import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.icu.sepsis.config.RuleConfig;
import org.icu.sepsis.domain.Domain.*;
import org.springframework.stereotype.Component;

@Component
public class ClinicalRules {
  private final RuleConfig cfg;

  public ClinicalRules(RuleConfig c) {
    cfg = c;
  }

  private static final Pattern UNCERTAIN =
      Pattern.compile(
          "不能排除感染|尚不能排除感染|难以排除感染|无法排除感染|感染待排|感染不除外|感染可能|感染[？?]|cannot.exclude.infection",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern NEG =
      Pattern.compile(
          "排除感染|非感染性|无感染证据|无感染迹象|not.infected|non-infectious", Pattern.CASE_INSENSITIVE);
  /** Strong wording: a non-negated hit makes the clause "supported"; any other infection word is "possible". */
  private static final List<String> STRONG =
      List.of("感染已确诊", "确诊感染", "肺炎", "菌血症", "脓毒", "pneumonia", "bacteremia", "sepsis", "septic");

  public String infection(List<Observation> es) {
    boolean pos = false, neg = false, maybe = false;
    for (Observation o : es) {
      if (!"DIAGNOSIS".equals(o.parameterCode) || o.text == null) continue;
      for (String clause : InfectionText.clauses(o.text)) {
        if (UNCERTAIN.matcher(clause).find()) { maybe = true; continue; }
        switch (InfectionText.classify(clause, org.icu.sepsis.sepsis.SepsisClinicalRules.INFECTION_KEYWORDS)) {
          case UNCERTAIN: maybe = true; break;
          case NEGATED: neg = true; break;
          case POSITIVE:
            if (InfectionText.classify(clause, STRONG) == InfectionText.Clause.POSITIVE) pos = true; else maybe = true;
            break;
          default:
            if (NEG.matcher(clause).find()) neg = true;
        }
      }
    }
    if (pos && (neg || maybe)) return "possible";
    if (pos) return "supported";
    if (maybe) return "possible";
    if (neg) return "not_supported";
    return "unknown";
  }

  public boolean valid(Observation o, Instant t) {
    if ("context_only".equals(o.clinicalUse) || "unconfirmed".equals(o.encounterLinkStatus)) return false;
    boolean sourceIngestUnknown = o.ingestTime == null && "unknown".equals(o.ingestTimeBasis)
        && "review".equals(o.clinicalUse);
    if (o.eventTime == null
        || (o.ingestTime == null && !sourceIngestUnknown)
        || o.eventTime.isAfter(t)
        || (o.ingestTime != null && o.ingestTime.isAfter(t))
        || !"valid".equals(o.qualityStatus)) return false;
    long age = Duration.between(o.eventTime, t).getSeconds();
    int max =
        Set.of("LACTATE", "DIAGNOSIS").contains(o.parameterCode)
            ? cfg.getLabFreshMinutes()
            : cfg.getFreshMinutes();
    if (age > max * 60L) return false;
    if ("DIAGNOSIS".equals(o.parameterCode)) return o.text != null;
    if (o.value == null || !Double.isFinite(o.value)) return false;
    switch (o.parameterCode) {
      case "HR":
        return "bpm".equals(o.unit) && o.value >= 10 && o.value <= 300;
      case "RR":
        return "/min".equals(o.unit) && o.value >= 1 && o.value <= 100;
      case "TEMP":
        return ("°C".equals(o.unit) || "C".equals(o.unit) || "℃".equals(o.unit))
            && o.value >= 30
            && o.value <= 45;
      case "SPO2":
        return ("%".equals(o.unit) || o.unit == null || o.unit.isBlank())
            && o.value >= 0
            && o.value <= 100;
      case "MAP":
      case "SBP":
      case "MAP_IBP":
      case "MAP_NIBP":
      case "SBP_IBP":
      case "SBP_NIBP":
      case "DBP_IBP":
      case "DBP_NIBP":
        return "mmHg".equals(o.unit) && o.value >= 10 && o.value <= 300;
      case "LACTATE":
        return "mmol/L".equals(o.unit) && o.value >= 0 && o.value <= 40;
      case "GCS":
        return "score".equals(o.unit)
            && o.value >= 3
            && o.value <= 15
            && o.value == Math.floor(o.value);
      case "VASOPRESSOR_ACTIVE":
        return "boolean".equals(o.unit) && (o.value == 0 || o.value == 1);
      default:
        return false;
    }
  }

  public Assessment assess(List<Observation> input, Instant now) {
    Assessment a = new Assessment();
    a.createdAt = now;
    a.assessmentTime = now;
    a.ruleVersion = cfg.getVersion();
    Map<String, Observation> latest = new HashMap<>();
    Map<String, Instant> conflictAt = new HashMap<>();
    for (Observation o : input)
      if (valid(o, now)) {
        if ("DIAGNOSIS".equals(o.parameterCode)) {
          a.evidence.add(o);
          continue;
        }
        Observation cur = latest.get(o.parameterCode);
        if (cur != null && cur.eventTime.equals(o.eventTime) && !Objects.equals(cur.value, o.value))
          conflictAt.merge(o.parameterCode, o.eventTime, (x, y) -> x.isAfter(y) ? x : y);
        latest.merge(o.parameterCode, o, (x, y) -> y.eventTime.isAfter(x.eventTime) ? y : x);
      }
    // A same-time numeric conflict at the newest time is not resolved by silently using an older value.
    for (Map.Entry<String, Instant> c : conflictAt.entrySet()) {
      Observation cur = latest.get(c.getKey());
      if (cur != null && !cur.eventTime.isAfter(c.getValue())) {
        latest.remove(c.getKey());
        a.conflictingEvidence.add("SAME_TIME_CONFLICT:" + c.getKey());
      }
    }
    a.evidence.addAll(latest.values());
    // Channel pressures (IBP/NIBP) are not merged into one value, but each channel still counts for instability.
    Double channelMapMin = minOf(latest, "MAP_IBP", "MAP_NIBP"), channelSbpMin = minOf(latest, "SBP_IBP", "SBP_NIBP");
    if (latest.keySet().stream().anyMatch(c -> c.matches("(MAP|SBP|DBP)_(IBP|NIBP)"))) {
      latest.remove("MAP"); latest.remove("SBP");
      a.conflictingEvidence.add("PRESSURE_CHANNEL_SELECTION_REQUIRED");
    }
    a.infectionVerdict = infection(a.evidence);
    boolean hasMap = latest.containsKey("MAP") || latest.keySet().stream().anyMatch(c -> c.startsWith("MAP_"));
    boolean hasSbp = latest.containsKey("SBP") || latest.keySet().stream().anyMatch(c -> c.startsWith("SBP_"));
    // A report whose encounter/sampling is unconfirmed is "unconfirmed", not absent; stale, invalid or future rows are absent.
    boolean hasLactate = latest.containsKey("LACTATE") || input.stream().anyMatch(o -> lactateReport(o, now));
    if (!hasMap) a.missingFeatures.add("MAP");
    if (!hasSbp) a.missingFeatures.add("SBP");
    if (!latest.containsKey("RR")) a.missingFeatures.add("RR");
    if (!latest.containsKey("GCS")) a.missingFeatures.add("GCS");
    if (!hasLactate) a.missingFeatures.add("LACTATE");
    if ("unknown".equals(a.infectionVerdict)) a.missingFeatures.add("INFECTION_EVIDENCE");
    a.dataQualityStatus = a.missingFeatures.isEmpty() ? "sufficient_for_demo_screen" : "incomplete";
    Double map = value(latest, "MAP"),
        sbp = value(latest, "SBP"),
        lac = value(latest, "LACTATE"),
        rr = value(latest, "RR"),
        gcs = value(latest, "GCS"),
        vaso = value(latest, "VASOPRESSOR_ACTIVE");
    if (sbp != null && rr != null && gcs != null)
      a.ruleScore = (sbp <= 100 ? 1 : 0) + (rr >= 22 ? 1 : 0) + (gcs < 15 ? 1 : 0);
    boolean unstable =
        (map != null && map < cfg.getMapLow()) || (sbp != null && sbp < cfg.getSbpLow())
            || (channelMapMin != null && channelMapMin < cfg.getMapLow())
            || (channelSbpMin != null && channelSbpMin < cfg.getSbpLow());
    boolean infection = Set.of("supported", "possible").contains(a.infectionVerdict);
    a.shockScreeningStatus =
        infection && vaso != null && vaso == 1 && lac != null && lac >= cfg.getLactateHigh()
            ? "suspected_requires_review"
            : (vaso == null || lac == null || "unknown".equals(a.infectionVerdict)
                ? "unable_to_assess"
                : "not_triggered");
    a.screeningStatus =
        unstable
            ? "hemodynamic_instability"
            : (infection && lac != null && lac >= cfg.getLactateHigh()
                ? "infection_with_abnormality"
                : (a.missingFeatures.isEmpty() ? "not_triggered" : "unable_to_assess"));
    return a;
  }

  /** Lactate report kept only for attribution review: unconfirmed encounter/context, but not in the future. */
  public static boolean lactateReport(Observation o, Instant now) {
    return "LACTATE".equals(o.parameterCode) && o.value != null && Double.isFinite(o.value)
        && ("context_only".equals(o.clinicalUse) || "unconfirmed".equals(o.encounterLinkStatus))
        && (o.eventTime == null || !o.eventTime.isAfter(now));
  }

  private static Double minOf(Map<String, Observation> m, String... codes) {
    Double out = null;
    for (String c : codes) {
      Observation o = m.get(c);
      if (o != null && o.value != null && (out == null || o.value < out)) out = o.value;
    }
    return out;
  }

  private Double value(Map<String, Observation> m, String c) {
    return m.containsKey(c) ? m.get(c).value : null;
  }

  public String executionStatus(Instant event, Instant t0, Instant due) {
    if (event.isBefore(t0)) return "completed_before";
    return event.isAfter(due) ? "met_late" : "met";
  }

  public boolean eligible(Task t) {
    return "confirmed".equals(t.clinicalReviewStatus)
        && Set.of("required", "conditional", "individualized").contains(t.applicability);
  }
}
