package org.icu.sepsis.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class LlmClientParseTest {
  static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void contentVariants() throws Exception {
    assertEquals("a", LlmClient.extractContent(JSON.readTree("{\"choices\":[{\"message\":{\"content\":\"a\"}}]}")));
    assertEquals("ab", LlmClient.extractContent(JSON.readTree("{\"choices\":[{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"a\"},{\"text\":\"b\"}]}}]}")));
    assertEquals("t", LlmClient.extractContent(JSON.readTree("{\"choices\":[{\"text\":\"t\"}]}")));
    assertEquals("x", LlmClient.extractContent(JSON.readTree("{\"content\":[{\"type\":\"text\",\"text\":\"x\"}]}")));
    assertNull(LlmClient.extractContent(JSON.readTree("{\"choices\":[{\"message\":{\"content\":null,\"reasoning_content\":\"...\"},\"finish_reason\":\"length\"}]}")));
  }

  @Test
  void sseStreamIsReassembled() throws Exception {
    LlmClient c = new LlmClient(null, JSON);
    String raw = "data: {\"model\":\"q\",\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n"
        + "data: {\"choices\":[{\"delta\":{\"content\":\"好\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n";
    var n = c.fromSse(raw);
    assertEquals("你好", LlmClient.extractContent(n));
    assertEquals("stop", n.path("choices").path(0).path("finish_reason").asText());
    assertNull(c.fromSse("<html>502</html>"));
  }

  @Test
  void sourceLabelsHideTableNames() {
    assertEquals("观察项", SourceLabels.of("SmartCare.bedside", "MAP"));
    assertEquals("出入量", SourceLabels.of("SmartCare.bedside", "URINE_OUTPUT"));
    assertEquals("执行用药", SourceLabels.of("SmartCare.drugExe", null));
    assertEquals("重症系统记录", SourceLabels.of("SmartCare.somethingNew", null));
  }
}
