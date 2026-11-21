package org.icu.sepsis.deterioration;
import java.time.Instant;import org.icu.sepsis.api.Envelope;import org.icu.sepsis.application.Store;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1") public class DeteriorationController{
 private final Store store;private final DeteriorationService service;private final ReviewedLabEvidenceService reviews;
 public DeteriorationController(Store store,DeteriorationService service,ReviewedLabEvidenceService reviews){this.store=store;this.service=service;this.reviews=reviews;}
 @GetMapping("/patients/{id}/deterioration")public Envelope<?> read(@PathVariable String id){return Envelope.ok(service.read(store.patient(id),Instant.now()));}
 @PostMapping("/patients/{id}/deterioration/lab-reviews")
 @org.springframework.security.access.prepost.PreAuthorize("hasRole('DOCTOR')")
 public Envelope<?> review(@PathVariable String id,@javax.validation.Valid @RequestBody ReviewedLabEvidenceService.Request request,org.springframework.security.core.Authentication actor){return Envelope.ok(reviews.record(store.patient(id),request,actor.getName(),Instant.now()));}
 @org.springframework.beans.factory.annotation.Autowired private SpecialtyEvidenceService specialty;
 @PostMapping("/patients/{id}/deterioration/specialty/{module}")
 @org.springframework.security.access.prepost.PreAuthorize("hasRole('DOCTOR')")
 public Envelope<?> specialty(@PathVariable String id,@PathVariable String module,@javax.validation.Valid @RequestBody SpecialtyEvidenceService.Request request,org.springframework.security.core.Authentication actor){var r=specialty.record(store.patient(id),module,request,actor.getName(),Instant.now());return Envelope.ok(java.util.Map.of("id",r.id,"module",r.module,"withdrawn",r.withdrawn));}

 @org.springframework.beans.factory.annotation.Autowired private DataQualityService quality;
 @org.springframework.beans.factory.annotation.Autowired private RenalMedicationReviewService medicationReview;
 @GetMapping("/patients/{id}/data-quality") public Envelope<?> quality(@PathVariable String id){return Envelope.ok(quality.read(store.patient(id),Instant.now()));}
 @GetMapping("/patients/{id}/renal-medication-review") @org.springframework.security.access.prepost.PreAuthorize("hasRole('DOCTOR')") public Envelope<?> medications(@PathVariable String id){return Envelope.ok(medicationReview.read(store.patient(id),Instant.now()));}
}
