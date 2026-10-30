package org.icu.sepsis.ai;

import java.util.*;
import org.icu.sepsis.domain.Domain.Base;
import org.springframework.data.mongodb.core.mapping.Document;

/** Audit record of one model call; prompts hold only the de-identified facts. Lives in the icu_sepsis_* DB. */
@Document("sepsis_ai_interaction")
public class AiInteraction extends Base {
  public String alertId, question, lastFinishReason;
  public java.time.Instant generatedAt;
  public int droppedKept;
  public List<Map<String, Object>> facts;
  public List<Map<String, Object>> knowledgeFull = new ArrayList<>();
  public String kind, status, errorKind, model, modelRole, actor, traceId, promptHash, rawResponse;
  public boolean fallbackUsed;
  public Long latencyMs;
  public Integer promptTokens, completionTokens;
  public List<Map<String, Object>> attempts = new ArrayList<>();
  public List<Map<String, String>> prompt;
  public Map<String, Object> result;
  public List<String> factIds = new ArrayList<>();
  public List<Map<String, Object>> knowledge = new ArrayList<>();
  public List<Map<String, Object>> dropped = new ArrayList<>();
  public List<Map<String, Object>> feedback = new ArrayList<>();
}
