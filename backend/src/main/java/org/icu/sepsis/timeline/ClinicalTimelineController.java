package org.icu.sepsis.timeline;
import java.time.Instant;
import java.util.*;
import javax.validation.Valid;
import javax.validation.constraints.*;
import org.icu.sepsis.api.*;
import org.icu.sepsis.application.Store;
import org.icu.sepsis.ai.AiAssistService;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
@RestController @RequestMapping("/api/v1") @Validated
public class ClinicalTimelineController {
 private final Store store;private final ClinicalTimelineService timeline;private final AiAssistService ai;
 public ClinicalTimelineController(Store store,ClinicalTimelineService timeline,AiAssistService ai){this.store=store;this.timeline=timeline;this.ai=ai;}
 @GetMapping("/patients/{id}/clinical-timeline")
 public Envelope<?> read(@PathVariable String id,@RequestParam(defaultValue="24") @Min(1) @Max(720) int hours,@RequestParam(required=false) @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) Instant end,@RequestParam(defaultValue="retrospective") @Pattern(regexp="retrospective|known_at") String mode){return Envelope.ok(timeline.read(store.patient(id),hours,end,mode).view());}
 public static class Request {
  @Min(1) @Max(720) public int hours=24;public Instant end,start;
  @NotNull @Pattern(regexp="retrospective|known_at") public String mode="retrospective";
  @NotEmpty @Size(max=16) public List<@NotBlank @Pattern(regexp="[A-Z0-9_]{1,40}") String> codes=new ArrayList<>();
  @Size(max=100) public List<@Size(max=200) String> medicationNames;
  public boolean includeMedication=true;
 }
 @PostMapping("/patients/{id}/ai/timeline") @PreAuthorize("hasAnyRole('DOCTOR','NURSE')")
 public Envelope<?> analyze(@PathVariable String id,@Valid @RequestBody Request request,Authentication auth){var patient=store.patient(id);var packet=timeline.read(patient,request.hours,request.end,request.mode);if(request.start!=null){if(request.start.isBefore(packet.asOf.minus(java.time.Duration.ofHours(request.hours)))||!request.start.isBefore(packet.asOf))throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,"TIMELINE_RANGE_INVALID","所选开始时点无效。");if(request.start.isAfter(packet.from)){packet.from=request.start;packet.observations.removeIf(o->o.eventTime.isBefore(request.start));packet.medications.removeIf(e->e.time.isBefore(request.start));}}if(request.medicationNames!=null)packet.medications.removeIf(e->!request.medicationNames.contains(e.name));return Envelope.ok(ai.timelineBrief(patient,ClinicalTimelineService.facts(packet,request.codes,request.includeMedication),packet.from,packet.asOf,request.mode,request.codes,auth.getName()));}
}
