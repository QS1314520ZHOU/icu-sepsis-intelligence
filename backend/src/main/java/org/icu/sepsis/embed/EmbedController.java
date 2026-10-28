package org.icu.sepsis.embed;

import javax.validation.Valid;
import javax.validation.constraints.*;
import org.icu.sepsis.api.*;
import org.icu.sepsis.application.Store;
import org.icu.sepsis.domain.Domain.Patient;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class EmbedController {
  private final EmbedAccess access;
  private final Store store;

  public EmbedController(EmbedAccess a, Store s) {
    access = a;
    store = s;
  }

  public static class Issue {
    @NotBlank
    @Size(max = 200)
    public String parentOrigin;

    @NotBlank
    @Size(max = 100)
    public String channel;

    @NotBlank
    @Size(max = 128)
    public String patientId;

    @NotBlank
    @Size(max = 128)
    public String encounterId;
  }

  public static class Exchange {
    @NotBlank
    @Size(max = 100)
    public String code;

    @NotBlank
    @Size(max = 200)
    public String parentOrigin;

    @NotBlank
    @Size(max = 100)
    public String channel;
  }

  @GetMapping("/embed-api/config")
  public Envelope<?> config() {
    return Envelope.ok(
        Views.map(
            "allowedParentOrigins",
            access.origins,
            "protocolVersion",
            1,
            "demoIssuer",
            access.demo));
  }

  @PostMapping("/api/v1/embed-codes")
  public Envelope<?> issue(@Valid @RequestBody Issue r, Authentication a) {
    Patient p = store.patient(r.patientId);
    if (!p.encounterId.equals(r.encounterId))
      throw new ApiException(HttpStatus.CONFLICT, "ENCOUNTER_MISMATCH", "住院上下文不匹配");
    return Envelope.ok(
        Views.map(
            "code",
            access.issue(a, r.parentOrigin, r.channel, p.id, p.encounterId, p.department),
            "expiresInSeconds",
            45));
  }

  @PostMapping("/embed-api/revoke")
  public Envelope<?> revoke() {
    access.revoke(EmbedAccess.current());
    return Envelope.ok(Views.map("revoked", true));
  }

  @PostMapping("/embed-api/exchange")
  public Envelope<?> exchange(@Valid @RequestBody Exchange r) {
    return Envelope.ok(access.exchange(r.code, r.parentOrigin, r.channel));
  }
}
