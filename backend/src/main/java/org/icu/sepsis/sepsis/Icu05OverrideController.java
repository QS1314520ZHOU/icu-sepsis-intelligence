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
@RequestMapping("/api/v1/patients/{patientId}/icu05-overrides")
@PreAuthorize("hasAnyRole('DOCTOR','QUALITY')")
@Validated
public class Icu05OverrideController {
  private final Store store;private final Icu05OverrideService service;
  public Icu05OverrideController(Store s,Icu05OverrideService service){store=s;this.service=service;}
  @GetMapping public Envelope<?> history(@PathVariable String patientId){return Envelope.ok(service.history(store.patient(patientId)));}
  @PostMapping public Envelope<?> create(@PathVariable String patientId,@Valid @RequestBody Requests.Icu05OverrideRequest request,
      @RequestHeader("Idempotency-Key") @Pattern(regexp="[A-Za-z0-9_-]{8,100}") String key,Authentication actor){return Envelope.ok(service.create(patientId,request,key,actor.getName()));}
  @PostMapping("/{id}/revoke") public Envelope<?> revoke(@PathVariable String patientId,@PathVariable String id,@Valid @RequestBody Requests.Icu05Revoke request,
      @RequestHeader("Idempotency-Key") @Pattern(regexp="[A-Za-z0-9_-]{8,100}") String key,Authentication actor){return Envelope.ok(service.revoke(patientId,id,request,key,actor.getName()));}
}
