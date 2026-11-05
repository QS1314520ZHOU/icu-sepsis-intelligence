package org.icu.sepsis.ai;

import java.util.regex.Pattern;
import org.icu.sepsis.api.ApiException;
import org.icu.sepsis.domain.Domain.Patient;
import org.springframework.http.HttpStatus;

/** Conservative screen, not a guarantee of complete de-identification. */
final class AiPrivacyGuard {
  private static final Pattern IDENTIFIER = Pattern.compile(
      "(?:住院号|病案号|病历号|身份证号|门诊号|患者编号|床号|联系电话|手机号)\\s*[:：#]?\\s*[A-Za-z0-9-]+"
      + "|(?:姓名|患者姓名|病人姓名)\\s*[:：]\\s*[\\p{IsHan}A-Za-z·]{2,}"
      + "|(?<!\\d)\\d{1,3}\\s*床|(?<!\\d)\\d{6,}(?!\\d)"
      + "|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", Pattern.CASE_INSENSITIVE);

  static void check(String text, Patient patient) {
    if (text == null || text.isBlank()) return;
    if (IDENTIFIER.matcher(text).find() || matches(text, patient))
      throw new ApiException(HttpStatus.BAD_REQUEST, "AI_SENSITIVE_INPUT", "内容可能包含患者身份信息，请移除后重试。");
  }

  private static boolean matches(String text, Patient p) {
    if (p == null) return false;
    for (String s : new String[] {p.label, p.bed, p.id, p.patientId, p.encounterId}) {
      if (s != null && s.trim().length() >= 2 && !s.equals("患者") && text.contains(s.trim())) return true;
    }
    return false;
  }
  private AiPrivacyGuard() {}
}
