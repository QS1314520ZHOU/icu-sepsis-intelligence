package org.icu.sepsis.ai;

import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Hospital LLM settings. Everything comes from environment variables; nothing secret is committed.
 * Disabled by default: the workbench must work identically when the model service is absent.
 */
@Component
public class AiProperties {
  public final boolean enabled;
  public final String baseUrl, apiKey, chatPath, embeddingsPath;
  public final String fastModel, reasoningModel, embeddingModel;
  public final int connectTimeoutMs, fastTimeoutMs, reasoningTimeoutMs, maxTokens, maxConcurrent;
  public final double temperature;
  public final boolean jsonMode, tlsInsecure, storePrompt;
  public final String tlsCaFile, knowledgeDir;
  public final ZoneId zone;

  public AiProperties(
      @Value("${app.ai.enabled:false}") boolean enabled,
      @Value("${app.ai.base-url:}") String baseUrl,
      @Value("${app.ai.api-key:}") String apiKey,
      @Value("${app.ai.chat-path:/v1/chat/completions}") String chatPath,
      @Value("${app.ai.embeddings-path:/v1/embeddings}") String embeddingsPath,
      @Value("${app.ai.models.fast:}") String fastModel,
      @Value("${app.ai.models.reasoning:}") String reasoningModel,
      @Value("${app.ai.models.embedding:}") String embeddingModel,
      @Value("${app.ai.connect-timeout-ms:3000}") int connectTimeoutMs,
      @Value("${app.ai.fast-timeout-ms:25000}") int fastTimeoutMs,
      @Value("${app.ai.reasoning-timeout-ms:90000}") int reasoningTimeoutMs,
      @Value("${app.ai.max-tokens:1500}") int maxTokens,
      @Value("${app.ai.max-concurrent:4}") int maxConcurrent,
      @Value("${app.ai.temperature:0.2}") double temperature,
      @Value("${app.ai.json-mode:true}") boolean jsonMode,
      @Value("${app.ai.tls-insecure:false}") boolean tlsInsecure,
      @Value("${app.ai.tls-ca-file:}") String tlsCaFile,
      @Value("${app.ai.store-prompt:true}") boolean storePrompt,
      @Value("${app.ai.knowledge-dir:}") String knowledgeDir,
      @Value("${app.ai.time-zone:Asia/Shanghai}") String zone) {
    this.enabled = enabled;
    this.baseUrl = trimSlash(baseUrl);
    this.apiKey = apiKey == null ? "" : apiKey.trim();
    this.chatPath = chatPath;
    this.embeddingsPath = embeddingsPath;
    this.fastModel = fastModel == null ? "" : fastModel.trim();
    this.reasoningModel = reasoningModel == null ? "" : reasoningModel.trim();
    this.embeddingModel = embeddingModel == null ? "" : embeddingModel.trim();
    this.connectTimeoutMs = connectTimeoutMs;
    this.fastTimeoutMs = fastTimeoutMs;
    this.reasoningTimeoutMs = reasoningTimeoutMs;
    this.maxTokens = maxTokens;
    this.maxConcurrent = Math.max(1, maxConcurrent);
    this.temperature = temperature;
    this.jsonMode = jsonMode;
    this.tlsInsecure = tlsInsecure;
    this.tlsCaFile = tlsCaFile == null ? "" : tlsCaFile.trim();
    this.storePrompt = storePrompt;
    this.knowledgeDir = knowledgeDir == null ? "" : knowledgeDir.trim();
    this.zone = ZoneId.of(zone == null || zone.isBlank() ? "Asia/Shanghai" : zone.trim());
  }

  /** Configured = switched on and at least one chat model plus an endpoint. */
  public boolean chatConfigured() {
    return enabled && !baseUrl.isEmpty() && !(fastModel.isEmpty() && reasoningModel.isEmpty());
  }

  public boolean embeddingConfigured() {
    return chatConfigured() && !embeddingModel.isEmpty();
  }

  /** Primary model first, then the other one as fallback; blanks removed, duplicates collapsed. */
  public java.util.List<String[]> modelOrder(String preferredRole) {
    java.util.List<String[]> out = new java.util.ArrayList<>();
    String[] fast = {"fast", fastModel, String.valueOf(fastTimeoutMs)};
    String[] reasoning = {"reasoning", reasoningModel, String.valueOf(reasoningTimeoutMs)};
    String[][] order = "reasoning".equals(preferredRole) ? new String[][] {reasoning, fast} : new String[][] {fast, reasoning};
    for (String[] m : order)
      if (!m[1].isEmpty() && out.stream().noneMatch(o -> o[1].equals(m[1]))) out.add(m);
    return out;
  }

  private static String trimSlash(String s) {
    if (s == null) return "";
    s = s.trim();
    while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
    return s;
  }
}
