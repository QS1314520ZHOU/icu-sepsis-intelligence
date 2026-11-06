package org.icu.sepsis.sepsis;

import javax.validation.Valid;
import javax.validation.constraints.Pattern;
import org.icu.sepsis.api.*;
import org.icu.sepsis.application.Store;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/patients/{patientId}/icu05-case-reviews")
@Validated
public class Icu05CaseReviewController {
  private final Store store; private final Icu05CaseReviewService service;
  public Icu05CaseReviewController(Store store,Icu05CaseReviewService service) { this.store=store; this.service=service; }
  @GetMapping
  @PreAuthorize("hasAnyRole('DOCTOR','QUALITY')")
  public Envelope<?> history(@PathVariable String patientId) { return Envelope.ok(service.history(store.patient(patientId))); }
  @PostMapping
  @PreAuthorize("hasAnyRole('DOCTOR','QUALITY')")
  public Envelope<?> exclude(@PathVariable String patientId,@Valid @RequestBody Requests.Icu05Exclude request,
      @RequestHeader("Idempotency-Key") @Pattern(regexp="[A-Za-z0-9_-]{8,100}") String key,Authentication actor) {
    return Envelope.ok(service.exclude(patientId,request,key,actor.getName()));
  }
  @PostMapping("/{id}/revoke")
  @PreAuthorize("hasAnyRole('DOCTOR','QUALITY')")
  public Envelope<?> revoke(@PathVariable String patientId,@PathVariable String id,@Valid @RequestBody Requests.Icu05Revoke request,
      @RequestHeader("Idempotency-Key") @Pattern(regexp="[A-Za-z0-9_-]{8,100}") String key,Authentication actor) {
    return Envelope.ok(service.revoke(patientId,id,request,key,actor.getName()));
  }
}
