package org.icu.sepsis.notes;
import javax.validation.constraints.*;import org.icu.sepsis.api.Envelope;
import org.springframework.web.bind.annotation.*;import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;import org.springframework.security.core.Authentication;
@RestController @RequestMapping("/api/v1/patients/{patientId}/clinical-notes") @Validated
@PreAuthorize("hasAnyRole('DOCTOR','NURSE','QUALITY')")
public class ClinicalNoteController {
  private final ClinicalNoteService notes;public ClinicalNoteController(ClinicalNoteService notes){this.notes=notes;}
  @GetMapping public Envelope<?> list(@PathVariable String patientId,@RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size,Authentication actor){return Envelope.ok(notes.list(patientId,page,size,actor.getName()));}
  @GetMapping("/{id}") public Envelope<?> read(@PathVariable String patientId,@PathVariable String id,Authentication actor){return Envelope.ok(notes.read(patientId,id,actor.getName()));}
  @PostMapping("/sync") @PreAuthorize("hasRole('DOCTOR')") public Envelope<?> sync(@PathVariable String patientId,Authentication actor){return Envelope.ok(notes.sync(patientId,actor.getName()));}
}
