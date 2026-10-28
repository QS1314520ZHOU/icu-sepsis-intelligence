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
  private static final Pattern POS =
      Pattern.compile("感染已确诊|确诊感染|肺炎|菌血症|脓毒|pneumonia|bacteremia", Pattern.CASE_INSENSITIVE);

  public String infection(List<Observation> es) {
    boolean pos = false, neg = false, maybe = false;
    for (Observation o : es) {
      if (!"DIAGNOSIS".equals(o.parameterCode) || o.text == null) continue;
      for (String clause : o.text.split("[，,。；;\n]")) {
        if (UNCERTAIN.matcher(clause).find()) maybe = true;
        else if (NEG.matcher(clause).find()) neg = true;
        else if (POS.matcher(clause).find()) pos = true;
        else if (clause.contains("感染")) maybe = true;
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
    for (Observation o : input)
      if (valid(o, now)) {
        if ("DIAGNOSIS".equals(o.parameterCode)) {
          a.evidence.add(o);
        } else latest.merge(o.parameterCode, o, (x, y) -> x.eventTime.isAfter(y.eventTime) ? x : y);
      }
    a.evidence.addAll(latest.values());
    if (latest.keySet().stream().anyMatch(c -> c.matches("(MAP|SBP|DBP)_(IBP|NIBP)"))) {
      latest.remove("MAP"); latest.remove("SBP");
      a.conflictingEvidence.add("PRESSURE_CHANNEL_SELECTION_REQUIRED");
    }
    a.infectionVerdict = infection(a.evidence);
    for (String c : List.of("MAP", "SBP", "RR", "GCS", "LACTATE", "VASOPRESSOR_ACTIVE"))
      if (!latest.containsKey(c)) a.missingFeatures.add(c);
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
        (map != null && map < cfg.getMapLow()) || (sbp != null && sbp < cfg.getSbpLow());
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
