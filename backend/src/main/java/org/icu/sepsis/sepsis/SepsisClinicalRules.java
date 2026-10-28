package org.icu.sepsis.sepsis;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Sepsis-3 / SSC / ICU-05 clinical rules.
 *
 * <p>Sources: Singer et al. JAMA 2016 (Sepsis-3); Evans et al. Intensive Care Med 2021 (SSC);
 * hospital ICU-05 SHOCK_RULE=s3 (vasopressor OR lactate≥2 with MAP&lt;70).
 *
 * <p>This is an explainable rule engine, not a validated risk model. Every factor returns
 * value + evidence so clinicians can audit it.
 */
public final class SepsisClinicalRules {
  private SepsisClinicalRules() {}

  public static final String ENGINE_VERSION = "sepsis-rules-2026.10";
  public static final String RULE_SOURCE =
      "Sepsis-3 Singer2016 · SSC2021 · ICU-05 shock=s3";

  public static final List<String> INFECTION_KEYWORDS = List.of(
      "脓毒", "败血", "感染性休克", "感染", "肺炎", "腹膜炎", "脑膜炎", "蜂窝织炎", "脓肿",
      "化脓", "尿路感染", "泌尿系感染", "胆管炎", "胆道感染", "导管相关", "菌血症", "sepsis",
      "septic", "infection", "pneumonia", "peritonitis", "bacteremia");

  public static final List<String> ANTIBIOTIC_KEYWORDS = List.of(
      "美罗培南", "亚胺培南", "万古霉素", "替考拉宁", "哌拉西林", "头孢吡肟", "头孢他啶",
      "头孢曲松", "头孢噻肟", "左氧氟沙星", "莫西沙星", "氟康唑", "伏立康唑", "多黏菌素",
      "克林霉素", "甲硝唑", "阿米卡星", "庆大霉素", "meropenem", "imipenem", "vancomycin",
      "piperacillin", "cefepime", "ceftazidime", "ceftriaxone", "levofloxacin", "antibiotic");

  public static final List<String> VASO_KEYWORDS = List.of(
      "去甲肾上腺素", "肾上腺素", "多巴胺", "多巴酚丁胺", "血管加压素", "垂体后叶", "苯肾上腺素",
      "去氧肾上腺素", "间羟胺", "阿拉明", "特利加压素", "米力农", "norepinephrine", "epinephrine",
      "dopamine", "dobutamine", "vasopressin", "phenylephrine");

  public static final List<String> CULTURE_KEYWORDS = List.of("痰培养", "尿培养", "血培养", "分泌物培养", "细菌培养");

  public static final List<String> CRYSTALLOID = List.of(
      "生理盐水", "0.9%氯化钠", "乳酸林格", "林格", "醋酸林格", "复方氯化钠", "醋酸钠林格");

  public static final List<String> COLLOID = List.of("白蛋白", "羟乙基淀粉", "明胶", "右旋糖酐");

  public static final class Fact {
    public final String code, label, detail;
    public final Boolean value;
    public Fact(String code, String label, Boolean value, String detail) {
      this.code = code;
      this.label = label;
      this.value = value;
      this.detail = detail;
    }
  }

  public static boolean infectionText(String diagnosis) {
    if (diagnosis == null || diagnosis.isBlank()) return false;
    String t = diagnosis.toLowerCase(Locale.ROOT);
    for (String k : INFECTION_KEYWORDS) if (t.contains(k.toLowerCase(Locale.ROOT))) return true;
    return false;
  }

  public static boolean anyKeyword(String text, List<String> keywords) {
    if (text == null || text.isBlank()) return false;
    String t = text.toLowerCase(Locale.ROOT);
    for (String k : keywords) if (t.contains(k.toLowerCase(Locale.ROOT))) return true;
    return false;
  }

  /** qSOFA: SBP≤100, RR≥22, altered mentation (GCS<15). */
  public static int qSofa(Double sbp, Double rr, Double gcs) {
    int s = 0;
    if (sbp != null && sbp <= 100) s++;
    if (rr != null && rr >= 22) s++;
    if (gcs != null && gcs < 15) s++;
    return s;
  }

  /**
   * Organ dysfunction proxies when full SOFA unavailable.
   * Returns true/false/null (unknown).
   */
  public static Boolean organDysfunction(Map<String, Double> vitals, Double sofa, Double gcs, boolean vaso) {
    if (vaso) return true;
    if (sofa != null) return sofa >= 2;
    Boolean hypotension = mapLow(vitals);
    Boolean altered = gcs != null && gcs < 15;
    Boolean resp = vitals.get("RR") != null && vitals.get("RR") >= 22;
    Boolean lac = vitals.get("LACTATE") != null && vitals.get("LACTATE") >= 2;
    if (hypotension == null && altered && !resp && !lac) return null;
    if (Boolean.TRUE.equals(hypotension) || Boolean.TRUE.equals(altered) || Boolean.TRUE.equals(lac))
      return true;
    if (hypotension != null || altered || resp || lac) return false;
    return null;
  }

  public static Boolean mapLow(Map<String, Double> vitals) {
    Double map = vitals.get("MAP");
    if (map == null) return null;
    return map < 70;
  }

  /** Sepsis-3 septic shock proxy (ICU-05 s3): vasopressor OR (lactate≥2 AND MAP&lt;70). */
  public static boolean septicShockS3(boolean vasopressor, Double lactateMax, Double mapMin) {
    boolean k1 = lactateMax != null && lactateMax >= 2;
    boolean hypotension = mapMin != null && mapMin < 70;
    return vasopressor || (k1 && hypotension);
  }

  /** Clinical syndrome tier. */
  public static String syndrome(
      boolean infection, Boolean organDysfunction, boolean shock, int qsofa, boolean dataComplete) {
    if (shock) return "septic_shock";
    if (infection && Boolean.TRUE.equals(organDysfunction)) return "sepsis";
    if (infection && qsofa >= 2) return "sepsis_suspect_qsofa";
    if (infection) return "infection_at_risk";
    if (Boolean.TRUE.equals(organDysfunction)) return "noninfectious_organ_failure";
    if (!dataComplete) return "insufficient_data";
    return "not_triggered";
  }

  /**
   * Multi-factor AI risk score 0–100 with explainable factors.
   * Not a calibrated mortality model — clinical triage aid only.
   */
  public static Map<String, Object> riskScore(
      String syndrome,
      int qsofa,
      Double mapMin,
      Double lactateMax,
      Double gcs,
      Double sofa,
      boolean vasopressor,
      boolean infection,
      boolean bundle1hDone,
      boolean bundle3hDone) {
    List<Map<String, Object>> factors = new ArrayList<>();
    double score = 0;

    if ("septic_shock".equals(syndrome)) {
      score += 35;
      factors.add(factor("SHOCK", "脓毒性休克(Sepsis-3/ICU-05)", 35, "升压药或乳酸≥2+MAP<70"));
    } else if ("sepsis".equals(syndrome)) {
      score += 22;
      factors.add(factor("SEPSIS", "感染+器官功能障碍", 22, "符合 Sepsis-3 脓毒症代理判定"));
    } else if ("sepsis_suspect_qsofa".equals(syndrome)) {
      score += 14;
      factors.add(factor("QSOFA+", "感染 + qSOFA≥2", 14, "床旁筛查提示，不等于确诊"));
    } else if ("infection_at_risk".equals(syndrome)) {
      score += 8;
      factors.add(factor("INF", "存在感染证据", 8, "诊断关键词/抗感染治疗"));
    }

    if (qsofa >= 2) {
      double w = qsofa == 3 ? 12 : 8;
      score += w;
      factors.add(factor("QSOFA", "qSOFA=" + qsofa, w, "SBP/RR/意识"));
    }
    if (mapMin != null && mapMin < 70) {
      score += 12;
      factors.add(factor("MAP", "MAP最低 " + fmt(mapMin) + " mmHg", 12, "MAP<70"));
    }
    if (lactateMax != null && lactateMax >= 4) {
      score += 15;
      factors.add(factor("LAC4", "乳酸最高 " + fmt(lactateMax), 15, "乳酸≥4 高危低灌注"));
    } else if (lactateMax != null && lactateMax >= 2) {
      score += 10;
      factors.add(factor("LAC2", "乳酸最高 " + fmt(lactateMax), 10, "乳酸≥2"));
    }
    if (gcs != null && gcs < 9) {
      score += 10;
      factors.add(factor("GCS", "GCS " + fmt(gcs), 10, "意识障碍"));
    } else if (gcs != null && gcs < 13) {
      score += 6;
      factors.add(factor("GCS", "GCS " + fmt(gcs), 6, "意识轻度受损"));
    }
    if (sofa != null && sofa >= 6) {
      score += 10;
      factors.add(factor("SOFA", "SOFA " + fmt(sofa), 10, "器官障碍较重"));
    } else if (sofa != null && sofa >= 2) {
      score += 5;
      factors.add(factor("SOFA", "SOFA " + fmt(sofa), 5, "器官障碍"));
    }
    if (vasopressor) {
      score += 10;
      factors.add(factor("VASO", "使用血管活性药", 10, "循环支持"));
    }
    if (!bundle1hDone && ("sepsis".equals(syndrome) || "septic_shock".equals(syndrome) || "sepsis_suspect_qsofa".equals(syndrome))) {
      score += 8;
      factors.add(factor("B1H", "1h Bundle 未完成", 8, "乳酸/培养/抗菌药物"));
    }
    if (!bundle3hDone && "septic_shock".equals(syndrome)) {
      score += 5;
      factors.add(factor("B3H", "3h Bundle 未完成", 5, "液体/复测乳酸"));
    }

    score = Math.min(100, Math.round(score));
    String level;
    if (score >= 70) level = "critical";
    else if (score >= 45) level = "high";
    else if (score >= 25) level = "moderate";
    else if (score >= 10) level = "low";
    else level = "minimal";

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("score", (int) score);
    out.put("level", level);
    out.put("levelLabel", levelLabel(level));
    out.put("factors", factors);
    out.put("modelStatus", "rule_triage_v1_not_calibrated");
    out.put("note", "多因素可解释规则分，非经校准的死亡率/进展概率模型；需医师复核。");
    return out;
  }

  public static List<Map<String, Object>> subtypes(String diagnosis, Map<String, Double> vitals, boolean vasopressor) {
    List<Map<String, Object>> out = new ArrayList<>();
    String d = diagnosis == null ? "" : diagnosis;

    // Primary infection source: only the strongest diagnosis keyword hit, with evidence.
    String source = null;
    String evidence = null;
    if (containsAny(d, List.of("肺炎", "vap", "肺部感染", "呼吸机相关"), null)) {
      source = "呼吸源性";
      evidence = matched(d, List.of("肺炎", "vap", "肺部感染", "呼吸机相关"));
    } else if (containsAny(d, List.of("腹膜炎", "腹腔感染", "胆管炎", "胆囊炎", "急性胰腺炎"), null)) {
      source = "腹腔/胆道源性";
      evidence = matched(d, List.of("腹膜炎", "腹腔感染", "胆管炎", "胆囊炎", "急性胰腺炎"));
    } else if (containsAny(d, List.of("尿路感染", "泌尿系感染", "肾盂肾炎"), null)) {
      source = "泌尿源性";
      evidence = matched(d, List.of("尿路感染", "泌尿系感染", "肾盂肾炎"));
    } else if (containsAny(d, List.of("菌血症", "败血症", "血流感染", "感染性心内膜炎"), null)) {
      source = "血流感染";
      evidence = matched(d, List.of("菌血症", "败血症", "血流感染", "感染性心内膜炎"));
    } else if (containsAny(d, List.of("软组织感染", "蜂窝织炎", "坏疽性筋膜炎"), null)) {
      source = "皮肤软组织源性";
      evidence = matched(d, List.of("软组织感染", "蜂窝织炎", "坏疽性筋膜炎"));
    } else if (SepsisClinicalRules.infectionText(d)) {
      source = "感染（部位未明）";
      evidence = "诊断含感染相关词，但无明确解剖部位";
    } else {
      source = "无明确感染源证据";
      evidence = "诊断未命中感染部位关键词";
    }
    Map<String, Object> src = new LinkedHashMap<>();
    src.put("code", "source");
    src.put("label", source);
    src.put("basis", evidence);
    out.add(src);

    Double lac = vitals.get("LACTATE");
    String circulatory;
    String circEvidence;
    if (vasopressor) {
      circulatory = "血管麻痹倾向";
      circEvidence = "窗口内有升压药/血管活性药执行";
    } else if (lac != null && lac >= 4) {
      circulatory = "高乳酸低灌注倾向";
      circEvidence = "乳酸最高 " + fmt(lac) + " mmol/L";
    } else {
      circulatory = "无升压药且乳酸未达高危";
      circEvidence =
          "升压药无 · 乳酸" + (lac == null ? "缺失" : fmt(lac));
    }
    Map<String, Object> circ = new LinkedHashMap<>();
    circ.put("code", "circulatory");
    circ.put("label", circulatory);
    circ.put("basis", circEvidence);
    out.add(circ);
    return out;
  }

  private static boolean containsAny(String text, List<String> keys, Object unused) {
    if (text == null || text.isBlank()) return false;
    String t = text.toLowerCase(Locale.ROOT);
    for (String k : keys) if (t.contains(k.toLowerCase(Locale.ROOT))) return true;
    return false;
  }

  private static String matched(String text, List<String> keys) {
    if (text == null) return "";
    String t = text.toLowerCase(Locale.ROOT);
    for (String k : keys) {
      if (t.contains(k.toLowerCase(Locale.ROOT))) {
        // return a short window around first match
        int i = t.indexOf(k.toLowerCase(Locale.ROOT));
        int a = Math.max(0, i - 12);
        int b = Math.min(text.length(), i + k.length() + 20);
        return text.substring(a, b);
      }
    }
    return "";
  }

  private static Map<String, Object> factor(String code, String label, double weight, String why) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("code", code);
    m.put("label", label);
    m.put("weight", weight);
    m.put("why", why);
    return m;
  }

  private static String levelLabel(String level) {
    return switch (level) {
      case "critical" -> "危重";
      case "high" -> "高危";
      case "moderate" -> "中危";
      case "low" -> "低危";
      default -> "极低";
    };
  }

  private static String fmt(Double v) {
    if (v == null) return "—";
    return v == Math.floor(v) ? String.valueOf(v.intValue()) : String.format(Locale.ROOT, "%.1f", v);
  }
}
