package org.icu.sepsis.alerting;
import org.icu.sepsis.api.Envelope;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
@RestController
@RequestMapping("/api/v1/quality")
@PreAuthorize("hasAnyRole('DOCTOR','NURSE','QUALITY')")
public class WardPriorityController {
  private final WardPriorityService service; private final AlertReviewStatsService stats;
  public WardPriorityController(WardPriorityService service,AlertReviewStatsService stats){this.service=service;this.stats=stats;}
  @GetMapping("/alert-reviews")
  @PreAuthorize("hasAnyRole('DOCTOR','QUALITY')")
  public Envelope<?> reviews(@RequestParam(defaultValue="7") int days){return Envelope.ok(stats.report(days));}
  @GetMapping("/ward-priority") public Envelope<?> priority(){return Envelope.ok(service.snapshot());}
}
