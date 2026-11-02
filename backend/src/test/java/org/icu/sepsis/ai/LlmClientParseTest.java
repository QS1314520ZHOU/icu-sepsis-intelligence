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

  /** 院内 vLLM 0.29 实际报文：content 为字符串，另带 reasoning 字段。 */
  @Test
  void vllmReasoningReplyParses() throws Exception {
    String body = "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"model\":\"Qwen3.8-Next\",\"choices\":[{\"index\":0,"
        + "\"message\":{\"role\":\"assistant\",\"content\":\"\\n\\n你好！\",\"refusal\":null,\"reasoning\":\"用户打招呼\"},"
        + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":53,\"completion_tokens\":39}}";
    assertEquals("你好！", LlmClient.stripThinking(LlmClient.extractContent(JSON.readTree(body))).trim());
  }

  /** 思考耗尽 max_tokens（content 为空 + reasoning）时自动关闭思考重试一次。 */
  @Test
  void thinkingExhaustedRetriesWithoutThinking() throws Exception {
    java.util.List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
    com.sun.net.httpserver.HttpServer srv = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    srv.createContext("/v1/chat/completions", ex -> {
      String req = new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      seen.add(req);
      String out = req.contains("enable_thinking")
          ? "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"},\"finish_reason\":\"stop\"}]}"
          : "{\"choices\":[{\"message\":{\"content\":null,\"reasoning\":\"很长的思考\"},\"finish_reason\":\"length\"}]}";
      byte[] b = out.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
    });
    srv.start();
    try {
      AiProperties p = AiAssistServiceTest.props(true, "http://127.0.0.1:" + srv.getAddress().getPort(), "m", "", "", 3000, 2);
      LlmClient c = new LlmClient(p, JSON);
      LlmClient.Reply r = c.chat("m", java.util.List.of(new LlmClient.Message("user", "hi")), 3000, true, 100);
      assertEquals("{\"ok\":true}", r.content);
      assertEquals(2, seen.size());
      assertFalse(seen.get(0).contains("enable_thinking"));
    } finally { srv.stop(0); }
  }

  @Test
  void sourceLabelsHideTableNames() {
    assertEquals("观察项", SourceLabels.of("SmartCare.bedside", "MAP"));
    assertEquals("出入量", SourceLabels.of("SmartCare.bedside", "URINE_OUTPUT"));
    assertEquals("执行用药", SourceLabels.of("SmartCare.drugExe", null));
    assertEquals("重症系统记录", SourceLabels.of("SmartCare.somethingNew", null));
  }
}
