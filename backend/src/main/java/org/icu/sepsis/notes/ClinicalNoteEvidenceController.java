package org.icu.sepsis.notes;
import java.time.Instant;import javax.validation.constraints.*;
import org.icu.sepsis.api.Envelope;import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;import org.springframework.security.core.Authentication;
@RestController @RequestMapping("/api/v1/patients/{patientId}/clinical-notes") @Validated
@PreAuthorize("hasRole('DOCTOR')")
public class ClinicalNoteEvidenceController {
  public static class ExcerptRequest {
    @NotBlank @Size(max=128) public String snapshotId, expectedFingerprint;
    @NotNull public Instant asOf;
    @NotNull @Min(0) public Integer start;
    @NotNull @Min(1) public Integer end;
  }
  private final ClinicalNoteEvidenceService evidence;
  public ClinicalNoteEvidenceController(ClinicalNoteEvidenceService evidence){this.evidence=evidence;}
  @GetMapping("/review-evidence") public Envelope<?> prepare(@PathVariable String patientId,
      @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant asOf,
      @RequestParam(defaultValue="200") @Min(1) @Max(1000) int limit,Authentication actor){return Envelope.ok(evidence.prepare(patientId,asOf,limit,actor.getName()));}
  @PostMapping("/review-evidence/excerpt") public Envelope<?> excerpt(@PathVariable String patientId,@javax.validation.Valid @RequestBody ExcerptRequest request,Authentication actor){
    return Envelope.ok(evidence.excerpt(patientId,request.snapshotId,request.asOf,request.start,request.end,request.expectedFingerprint,actor.getName()));
  }

}
