package org.icu.sepsis.embed;

import java.util.List;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import org.icu.sepsis.api.Envelope;
import org.icu.sepsis.api.Views;
import org.icu.sepsis.integration.HostContextService;
import org.icu.sepsis.integration.SmartCareSourceReader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

/**
 * Host postMessage bridge endpoint.
 *
 * <p>Matches the SmartCare host protocol used by 科室概览 / 质控看板: after {@code SmartCareReady},
 * the iframe receives {@code SmartCare} with the current patient, then calls this endpoint to bind
 * the patient into the sepsis workspace.
 */
@RestController
public class HostContextController {
  public static class HostContextRequest {
    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "^[a-fA-F0-9]{24}$")
    public String patientId;

    @Size(max = 200)
    public String account;

    @Size(max = 400)
    public String token;
  }

  private final HostContextService service;
  private final ObjectProvider<SmartCareSourceReader> reader;
  private final String mode;

  public HostContextController(
      HostContextService s,
      ObjectProvider<SmartCareSourceReader> reader,
      @Value("${app.source.mode:demo}") String mode) {
    service = s;
    this.reader = reader;
    this.mode = mode;
  }

  @PostMapping("/embed-api/host-context")
  public Envelope<?> apply(
      @Valid @RequestBody HostContextRequest r,
      @RequestHeader(value = "Origin", required = false) String origin,
      @RequestParam(value = "parentOrigin", required = false) String parentOrigin) {
    String resolved = origin != null && !origin.isBlank() ? origin : parentOrigin;
    if (resolved == null || resolved.isBlank()) resolved = "http://unknown-host";
    return Envelope.ok(service.apply(resolved, r.patientId));
  }

  @GetMapping("/embed-api/source-status")
  public Envelope<?> sourceStatus() {
    SmartCareSourceReader source = reader.getIfAvailable();
    boolean enabled = "smartcare".equalsIgnoreCase(mode);
    return Envelope.ok(
        Views.map(
            "mode",
            mode,
            "enabled",
            enabled,
            "reachable",
            source != null && source.isReadable(),
            "protocol",
            List.of("SmartCareReady", "SmartCare", "select-patient", "SmartCareNavigate")));
  }
}
