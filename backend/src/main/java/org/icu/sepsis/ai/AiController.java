package org.icu.sepsis.ai;

import java.util.Map;
import javax.validation.Valid;
import javax.validation.constraints.*;
import org.icu.sepsis.api.Envelope;
import org.icu.sepsis.application.QueryService;
import org.icu.sepsis.application.Store;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** AI-assisted explanation endpoints. Read-only with respect to clinical data; writes only the audit collection. */
@RestController
@RequestMapping("/api/v1")
@Validated
public class AiController {
  public static class BriefRequest {
    @NotBlank @Pattern(regexp = "summary|icu05") public String kind = "summary";
  }
  public static class FeedbackRequest {
    @NotBlank @Pattern(regexp = "accept|reject|edit") public String verdict;
    @Size(max = 500) public String reason;
    @Size(max = 2000) public String editedText;
  }

  private final AiAssistService ai;
  private final Store s;
  private final QueryService q;
  public AiController(AiAssistService ai, Store s, QueryService q) { this.ai = ai; this.s = s; this.q = q; }

  @GetMapping("/ai/status")
  public Envelope<?> status() { return Envelope.ok(ai.status()); }

  @PostMapping("/patients/{id}/ai/brief")
  @PreAuthorize("hasAnyRole('DOCTOR','NURSE')")
  public Envelope<?> brief(@PathVariable String id, @Valid @RequestBody BriefRequest r, Authentication a) {
    return Envelope.ok(ai.brief(s.patient(id), r.kind, a.getName()));
  }

  @PostMapping("/ai/interactions/{id}/feedback")
  @PreAuthorize("hasAnyRole('DOCTOR','NURSE')")
  public Envelope<?> feedback(@PathVariable String id, @Valid @RequestBody FeedbackRequest r, Authentication a) {
    return Envelope.ok(ai.feedback(id, r.verdict, r.reason, r.editedText, a.getName()));
  }

  /** Audit / evaluation export for the quality team (department scope, newest first). */
  @GetMapping("/ai/interactions")
  @PreAuthorize("hasAnyRole('DOCTOR','QUALITY')")
  public Envelope<?> interactions(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return Envelope.ok(q.page(s.scope(), AiInteraction.class, page, size, AiController::view));
  }

  @GetMapping("/knowledge/search")
  public Envelope<?> search(@RequestParam @NotBlank @Size(max = 200) String q,
      @RequestParam(defaultValue = "5") @Min(1) @Max(20) int k) {
    return Envelope.ok(Map.of("query", q, "items", ai.searchKnowledge(q, k)));
  }

  static Map<String, Object> view(AiInteraction i) {
    Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("id", i.id); m.put("patientId", i.patientId); m.put("encounterId", i.encounterId); m.put("createdAt", i.createdAt);
    m.put("kind", i.kind); m.put("status", i.status); m.put("errorKind", i.errorKind); m.put("model", i.model); m.put("modelRole", i.modelRole);
    m.put("fallbackUsed", i.fallbackUsed); m.put("latencyMs", i.latencyMs); m.put("promptTokens", i.promptTokens); m.put("completionTokens", i.completionTokens);
    m.put("actor", i.actor); m.put("attempts", i.attempts); m.put("knowledge", i.knowledge); m.put("result", i.result);
    m.put("dropped", i.dropped); m.put("feedback", i.feedback); m.put("promptHash", i.promptHash);
    return m;
  }
}
