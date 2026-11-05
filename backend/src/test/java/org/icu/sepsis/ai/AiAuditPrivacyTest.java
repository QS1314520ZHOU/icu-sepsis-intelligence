package org.icu.sepsis.ai;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiAuditPrivacyTest {
  @Test void metadataCopyDoesNotPersistFreeTextOrClinicalFacts() {
    AiInteraction r = new AiInteraction();
    r.id = "test"; r.status = "ok"; r.question = "患者信息"; r.rawResponse = "原始回复";
    r.facts = List.of(Map.of("text", "病人事实")); r.prompt = List.of(Map.of("content", "提示词"));
    r.result = Map.of("answer", "回复"); r.dropped = List.of(Map.of("text", "未采纳回复"));
    r.feedback = List.of(Map.of("reason", "修改原因")); r.cacheKey = "cache"; r.promptHash = "hash";
    AiInteraction a = AiAssistService.metadataOnly(r);
    assertEquals("test", a.id); assertEquals("ok", a.status);
    assertNull(a.question); assertNull(a.rawResponse); assertNull(a.facts); assertNull(a.prompt); assertNull(a.result);
    assertTrue(a.dropped.isEmpty()); assertTrue(a.feedback.isEmpty()); assertNull(a.cacheKey); assertNull(a.promptHash);
    assertNotNull(r.result, "metadata copy must not change the immediate response");
  }
}
