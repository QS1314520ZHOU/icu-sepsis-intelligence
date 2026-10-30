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
    long start = System.nanoTime();
    JsonNode res = post(p.chatPath, body, timeoutMs);
    Reply r = new Reply();
    r.latencyMs = (System.nanoTime() - start) / 1_000_000;
    JsonNode choice = res.path("choices").path(0);
    JsonNode content = choice.path("message").path("content");
    if (content.isMissingNode() || content.isNull()) throw new LlmException("bad_response", "模型返回缺少 content");
    r.content = stripThinking(content.asText());
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
    try {
      return json.readTree(res.body());
    } catch (Exception e) {
      throw new LlmException("bad_response", "AI 服务返回非 JSON");
    }
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
