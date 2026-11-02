package org.icu.sepsis.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.*;
import java.time.Duration;
import java.util.*;
import javax.net.ssl.*;
import org.springframework.stereotype.Component;

/** Minimal OpenAI-compatible client (chat + embeddings) for the hospital-intranet model gateway. */
@Component
public class LlmClient {
  public static class Message {
    public String role, content;
    public Message() {}
    public Message(String role, String content) { this.role = role; this.content = content; }
  }
  public static class Reply {
    public String content, model;
    public long latencyMs;
    public Integer promptTokens, completionTokens;
    public String finishReason;
  }
  public static class LlmException extends RuntimeException {
    public final String kind;
    public LlmException(String kind, String message) { super(message); this.kind = kind; }
  }

  private final AiProperties p;
  private final ObjectMapper json;
  private volatile HttpClient http;

  public LlmClient(AiProperties p, ObjectMapper json) { this.p = p; this.json = json; }

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LlmClient.class);

  /** Qwen3 等混合思考模型：true 时请求 chat_template_kwargs.enable_thinking=false（vLLM 支持），避免思考耗尽 max_tokens。 */
  @org.springframework.beans.factory.annotation.Value("${app.ai.disable-thinking:false}")
  boolean disableThinking;

  private HttpClient http() {
    if (http == null) synchronized (this) {
      if (http == null) {
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(p.connectTimeoutMs))
            .version(HttpClient.Version.HTTP_1_1);
        SSLContext ssl = sslContext();
        if (ssl != null) b.sslContext(ssl);
        http = b.build();
      }
    }
    return http;
  }

  /** Intranet gateways often use a self-signed certificate: prefer pinning its CA file over trusting everything. */
  private SSLContext sslContext() {
    try {
      if (!p.tlsCaFile.isEmpty()) {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        try (InputStream in = Files.newInputStream(Path.of(p.tlsCaFile))) {
          int i = 0;
          for (java.security.cert.Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in))
            ks.setCertificateEntry("ai-ca-" + i++, c);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
      }
      if (p.tlsInsecure) {
        // Only for an isolated intranet test; hostname checks for IP-only certificates are a JVM-wide switch.
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        TrustManager[] all = {new X509TrustManager() {
          public void checkClientTrusted(X509Certificate[] c, String a) {}
          public void checkServerTrusted(X509Certificate[] c, String a) {}
          public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, all, new java.security.SecureRandom());
        return ctx;
      }
      return null;
    } catch (Exception e) {
      throw new IllegalStateException("AI TLS configuration invalid: " + e.getClass().getSimpleName(), e);
    }
  }

  public Reply chat(String model, List<Message> messages, int timeoutMs, boolean jsonMode) {
    return chat(model, messages, timeoutMs, jsonMode, p.maxTokens);
  }

  public Reply chat(String model, List<Message> messages, int timeoutMs, boolean jsonMode, int maxTokens) {
    ObjectNode body = json.createObjectNode();
    body.put("model", model);
    ArrayNode arr = body.putArray("messages");
    for (Message m : messages) arr.addObject().put("role", m.role).put("content", m.content);
    body.put("temperature", p.temperature);
    body.put("max_tokens", Math.max(1, maxTokens));
    body.put("stream", false);
    if (jsonMode) body.putObject("response_format").put("type", "json_object");
    if (disableThinking) body.putObject("chat_template_kwargs").put("enable_thinking", false);
    long start = System.nanoTime();
    JsonNode res = post(p.chatPath, body, timeoutMs);
    Reply r = new Reply();
    r.latencyMs = (System.nanoTime() - start) / 1_000_000;
    JsonNode root = res.has("choices") || !res.path("data").has("choices") ? res : res.path("data");
    JsonNode choice = root.path("choices").path(0);
    String text = extractContent(root);
    if (text == null) {
      String finish = choice.path("finish_reason").asText("");
      boolean thought = choice.path("message").hasNonNull("reasoning_content") || choice.path("message").hasNonNull("reasoning");
      log.warn("AI chat response without content (model={}, finish={}): {}", model, finish, AiAssistService.clip(res.toString(), 400));
      if (thought && "length".equals(finish))
        throw new LlmException("bad_response", "模型思考过程耗尽 max_tokens 未输出正文；可调大 AI_MAX_TOKENS 或设 AI_DISABLE_THINKING=true");
      throw new LlmException("bad_response", "模型返回缺少 content" + (finish.isEmpty() ? "" : "（finish_reason=" + finish + "）"));
    }
    r.content = stripThinking(text);
    r.finishReason = choice.path("finish_reason").asText(null);
    r.model = res.path("model").asText(model);
    JsonNode u = res.path("usage");
    if (u.has("prompt_tokens")) r.promptTokens = u.get("prompt_tokens").asInt();
    if (u.has("completion_tokens")) r.completionTokens = u.get("completion_tokens").asInt();
    return r;
  }

  public List<float[]> embed(List<String> texts, int timeoutMs) {
    ObjectNode body = json.createObjectNode();
    body.put("model", p.embeddingModel);
    ArrayNode in = body.putArray("input");
    texts.forEach(in::add);
    JsonNode res = post(p.embeddingsPath, body, timeoutMs);
    JsonNode data = res.path("data");
    if (!data.isArray() || data.size() != texts.size()) throw new LlmException("bad_response", "向量接口返回条数不符");
    float[][] out = new float[texts.size()][];
    for (JsonNode d : data) {
      int idx = d.path("index").asInt(-1);
      JsonNode e = d.path("embedding");
      if (idx < 0 || idx >= out.length || !e.isArray()) throw new LlmException("bad_response", "向量接口返回格式错误");
      float[] v = new float[e.size()];
      for (int i = 0; i < v.length; i++) v[i] = (float) e.get(i).asDouble();
      out[idx] = v;
    }
    return Arrays.asList(out);
  }

  private JsonNode post(String path, JsonNode body, int timeoutMs) {
    HttpRequest req;
    try {
      HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(p.baseUrl + path)).timeout(Duration.ofMillis(timeoutMs))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      if (!p.apiKey.isEmpty()) b.header("Authorization", "Bearer " + p.apiKey);
      req = b.build();
    } catch (Exception e) {
      throw new LlmException("config", "AI 服务地址无效");
    }
    HttpResponse<String> res = null;
    for (int attempt = 0; res == null; attempt++) {
      try {
        res = http().send(req, HttpResponse.BodyHandlers.ofString());
      } catch (HttpTimeoutException e) {
        throw new LlmException("timeout", "AI 服务响应超时");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new LlmException("interrupted", "AI 请求被中断");
      } catch (IOException e) {
        // A stale keep-alive connection closed by the gateway is retried once; connect failures are not.
        if (attempt == 0 && !(e instanceof java.net.ConnectException)) continue;
        throw new LlmException("unreachable", "AI 服务不可达：" + e.getClass().getSimpleName()
            + (e.getMessage() == null ? "" : " " + AiAssistService.clip(e.getMessage(), 120)));
      } catch (RuntimeException e) {
        throw new LlmException("unreachable", "AI 服务不可达：" + e.getClass().getSimpleName());
      }
    }
    if (res.statusCode() / 100 != 2)
      throw new LlmException("http_" + res.statusCode(), "AI 服务返回 HTTP " + res.statusCode());
    String raw = res.body() == null ? "" : res.body();
    try {
      return json.readTree(raw);
    } catch (Exception e) {
      JsonNode sse = fromSse(raw);
      if (sse != null) return sse;
      log.warn("AI gateway returned non-JSON from {} (HTTP {}): {}", path, res.statusCode(), AiAssistService.clip(raw, 400));
      throw new LlmException("bad_response", "AI 服务返回非 JSON：" + AiAssistService.clip(raw.replaceAll("\\s+", " "), 80));
    }
  }

  /** OpenAI 兼容的几种正文形态：message.content 字符串 / 分段数组、text、Anthropic content[]。 */
  static String extractContent(JsonNode root) {
    JsonNode choice = root.path("choices").path(0);
    JsonNode c = choice.path("message").path("content");
    if (c.isTextual()) return c.asText();
    if (c.isArray()) {
      StringBuilder b = new StringBuilder();
      for (JsonNode part : c) if (part.path("text").isTextual()) b.append(part.path("text").asText());
      if (b.length() > 0) return b.toString();
    }
    if (choice.path("text").isTextual()) return choice.path("text").asText();
    if (choice.path("delta").path("content").isTextual()) return choice.path("delta").path("content").asText();
    JsonNode a = root.path("content");
    if (a.isArray() && a.size() > 0 && a.path(0).path("text").isTextual()) return a.path(0).path("text").asText();
    if (root.path("output_text").isTextual()) return root.path("output_text").asText();
    return null;
  }

  /** 网关强制流式（text/event-stream）时，把 data: 分片拼回一个普通响应。 */
  JsonNode fromSse(String raw) {
    if (!raw.contains("data:")) return null;
    StringBuilder text = new StringBuilder();
    String finish = null, model = null;
    boolean any = false;
    for (String line : raw.split("\\r?\\n")) {
      line = line.trim();
      if (!line.startsWith("data:")) continue;
      String d = line.substring(5).trim();
      if (d.isEmpty() || "[DONE]".equals(d)) continue;
      try {
        JsonNode n = json.readTree(d);
        JsonNode ch = n.path("choices").path(0);
        JsonNode piece = ch.path("delta").path("content");
        if (!piece.isTextual()) piece = ch.path("message").path("content");
        if (piece.isTextual()) { text.append(piece.asText()); any = true; }
        if (ch.hasNonNull("finish_reason")) finish = ch.path("finish_reason").asText();
        if (n.hasNonNull("model")) model = n.path("model").asText();
      } catch (Exception ignored) { }
    }
    if (!any) return null;
    ObjectNode out = json.createObjectNode();
    if (model != null) out.put("model", model);
    ObjectNode ch = out.putArray("choices").addObject();
    ch.putObject("message").put("role", "assistant").put("content", text.toString());
    if (finish != null) ch.put("finish_reason", finish);
    return out;
  }

  /** Reasoning models may inline their chain of thought; it is never shown or parsed. */
  static String stripThinking(String s) {
    if (s == null) return "";
    String out = s.replaceAll("(?s)<think>.*?</think>", "");
    int open = out.indexOf("<think>");
    if (open >= 0) out = out.substring(0, open);
    return out.trim();
  }
}
