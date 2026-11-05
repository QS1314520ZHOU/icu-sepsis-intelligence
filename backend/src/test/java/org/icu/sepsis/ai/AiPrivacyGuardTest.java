package org.icu.sepsis.ai;

import static org.junit.jupiter.api.Assertions.*;
import org.icu.sepsis.api.ApiException;
import org.icu.sepsis.domain.Domain.Patient;
import org.junit.jupiter.api.Test;

class AiPrivacyGuardTest {
  @Test void blocksKnownPatientAndCommonIdentifiers() {
    Patient p = new Patient(); p.label = "张三"; p.bed = "12床"; p.id = "AB12";
    for (String text : new String[]{"张三情况如何", "12床的状态", "住院号123", "姓名：王五", "联系方式 test@example.com", "AB12"})
      assertThrows(ApiException.class, () -> AiPrivacyGuard.check(text, p), text);
    assertDoesNotThrow(() -> AiPrivacyGuard.check("乳酸4.6，MAP 65 mmHg，是否需要查看？", p));
  }
}
