package org.icu.sepsis.sepsis;

import java.util.*;
import java.util.regex.Pattern;

/** Observable abnormalities only. No automatic diagnosis, weighted risk or probability. */
public final class SepsisClinicalRules {
  private SepsisClinicalRules() {}
  public static final String ENGINE_VERSION = "clinical-review-v2";
  public static final String RULE_SOURCE = "观测核对规则；非诊断，非预测模型";
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
      "去甲肾上腺素", "肾上腺素", "多巴胺", "血管加压素", "垂体后叶", "苯肾上腺素",
      "去氧肾上腺素", "间羟胺", "阿拉明", "特利加压素", "norepinephrine", "epinephrine",
      "dopamine", "vasopressin", "phenylephrine");

  public static final List<String> CULTURE_KEYWORDS = List.of("痰培养", "尿培养", "血培养", "分泌物培养", "细菌培养");

  public static final List<String> CRYSTALLOID = List.of(
      "生理盐水", "0.9%氯化钠", "乳酸林格", "林格", "醋酸林格", "复方氯化钠", "醋酸钠林格");

  public static final List<String> COLLOID = List.of("白蛋白", "羟乙基淀粉", "明胶", "右旋糖酐");


  /** A text clue is not confirmed infection. Negation and historical text are not positive facts. */
  public static boolean infectionText(String text) {
    return org.icu.sepsis.domain.InfectionText.hasClue(text, INFECTION_KEYWORDS);
  }
  public static boolean anyKeyword(String text, List<String> keywords) {
    if (text == null || text.isBlank()) return false;
    String t = text.toLowerCase(Locale.ROOT);
    return keywords.stream().anyMatch(k -> t.contains(k.toLowerCase(Locale.ROOT)));
  }
  /** All three recent inputs are required. This score alone is not a sepsis screen/diagnosis. */
  public static Integer qSofa(Double sbp, Double rr, Double gcs) {
    if (sbp == null || rr == null || gcs == null) return null;
    return (sbp <= 100 ? 1 : 0) + (rr >= 22 ? 1 : 0) + (gcs < 15 ? 1 : 0);
  }
}
