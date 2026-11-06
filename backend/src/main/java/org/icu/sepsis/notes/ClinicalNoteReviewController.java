package org.icu.sepsis.notes;
import org.icu.sepsis.api.Envelope;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
@RestController @RequestMapping("/api/v1/patients/{patientId}/clinical-notes") @PreAuthorize("hasRole('DOCTOR')")
public class ClinicalNoteReviewController {
 private final ClinicalNoteReviewService review;
 public ClinicalNoteReviewController(ClinicalNoteReviewService review){this.review=review;}
 @PostMapping("/review-draft") public Envelope<?> draft(@PathVariable String patientId,@javax.validation.Valid @RequestBody ClinicalNoteReviewService.Request request,Authentication actor){return Envelope.ok(review.draft(patientId,request,actor.getName()));}
}
