package org.icu.sepsis.api;

import java.time.*;
import java.util.*;
import javax.validation.Valid;
import javax.validation.constraints.*;
import org.icu.sepsis.application.*;
import org.icu.sepsis.domain.Domain.*;
import org.icu.sepsis.integration.ClinicalDataProvider;
import org.icu.sepsis.prediction.RiskModelProvider;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Validated
public class ApiController {
  private final Store s;
  private final QueryService q;
  private final AssessmentService assessments;
  private final WorkflowService workflow;
  private final ClinicalDataProvider data;
  private final RiskModelProvider model;
  private final org.icu.sepsis.sepsis.SepsisAssessmentService sepsis;

  public ApiController(
      Store st,
      QueryService qs,
      AssessmentService a,
      WorkflowService w,
      ClinicalDataProvider d,
      RiskModelProvider m,
      org.icu.sepsis.sepsis.SepsisAssessmentService sepsisSvc) {
    s = st;
    q = qs;
    assessments = a;
    workflow = w;
    data = d;
    model = m;
    sepsis = sepsisSvc;
  }

  @GetMapping("/me")
  public Envelope<?> me(Authentication a) {
    return Envelope.ok(
        Views.map(
            "username",
            a.getName(),
            "roles",
            a.getAuthorities().stream().map(Object::toString).toArray(),
            "department",
            s.department));
  }

  @GetMapping("/patients")
  public Envelope<?> patients(
      @RequestParam(defaultValue = "") @Size(max = 80) String search,
      @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return Envelope.ok(q.patients(search, page, size));
  }

  @GetMapping("/patients/{id}")
  public Envelope<?> patient(@PathVariable String id) {
    return Envelope.ok(Views.patient(s.patient(id)));
  }

  @GetMapping({"/patients/{id}/clinical-data", "/patients/{id}/trends"})
  public Envelope<?> clinical(
      @PathVariable String id, @RequestParam(defaultValue = "24") @Min(1) @Max(720) int hours) {
    Patient p = s.patient(id);
    Instant now = Instant.now();
    Duration window = Duration.ofHours(hours);
    return Envelope.ok(
        Views.map(
            "items",
            data.observations(p.id, p.encounterId, now.minus(window), now).stream()
                .map(Views::observation)
                .toArray(),
            "windowHours",
            hours,
            "asOf",
            now,
            "windowStart",
            now.minus(window),
            "limit",
            2000,
            "note",
            "受限时间窗，最多2000条；未来与尚未入库的数据不返回。"));
  }

  @PostMapping("/patients/{id}/risk-assessments")
  @PreAuthorize("hasRole('DOCTOR')")
  public Envelope<?> assess(
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,100}") String key,
      @Valid @RequestBody Requests.Assess r,
      Authentication a) {
    return Envelope.ok(Views.assessment(assessments.assess(id, r, key, a.getName())));
  }

  @GetMapping("/patients/{id}/risk-assessments")
  public Envelope<?> assessments(
      @PathVariable String id,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    Patient p = s.patient(id);
    Criteria c = s.scope();
    if (org.icu.sepsis.embed.EmbedAccess.current() == null) {
      c.and("patientId").is(p.id).and("encounterId").is(p.encounterId);
    }
    return Envelope.ok(q.page(c, Assessment.class, page, size, Views::assessment));
  }

  /** Real-time sepsis / septic-shock auto classification + 1h3h6h Bundle + explainable AI risk. */
  @GetMapping("/patients/{id}/sepsis-assessment")
  public Envelope<?> sepsisAssessment(@PathVariable String id) {
    Patient p = s.patient(id);
    return Envelope.ok(sepsis.assess(p, Instant.now()));
  }

  @GetMapping("/risk-assessments/{id}")
  public Envelope<?> assessment(@PathVariable String id) {
    return Envelope.ok(Views.assessment(s.get(id, Assessment.class)));
  }

  @GetMapping("/alerts")
  public Envelope<?> alerts(
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    Criteria c = q.scope();
    if (status != null && !status.isBlank()) c.and("status").is(status);
    return Envelope.ok(q.page(c, Alert.class, page, size, Views::alert));
  }

  @GetMapping("/alerts/{id}")
  public Envelope<?> alert(@PathVariable String id) {
    return Envelope.ok(Views.alert(s.get(id, Alert.class)));
  }

  @PostMapping("/alerts/{id}/review")
  @PreAuthorize("hasRole('DOCTOR')")
  public Envelope<?> review(
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,100}") String key,
      @Valid @RequestBody Requests.Review r,
      Authentication a) {
    return Envelope.ok(Views.alert(workflow.reviewAlert(id, r, key, a.getName())));
  }

  @GetMapping("/bundles/tasks")
  public Envelope<?> tasks(
      @RequestParam(required = false) String patientId,
      @RequestParam(required = false) String metric,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant to,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    Criteria c = q.tasks(metric, from, to);
    if (patientId != null) {
      s.patient(patientId);
      c.and("patientId").is(patientId);
    }
    return Envelope.ok(q.page(c, Task.class, page, size, Views::task));
  }

  @GetMapping("/bundles/tasks/{id}")
  public Envelope<?> task(@PathVariable String id) {
    return Envelope.ok(Views.task(s.get(id, Task.class)));
  }

  @PostMapping("/bundles/tasks/{id}/execution-records")
  @PreAuthorize("hasAnyRole('DOCTOR','NURSE')")
  public Envelope<?> execute(
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,100}") String key,
      @Valid @RequestBody Requests.Execute r,
      Authentication a) {
    return Envelope.ok(Views.task(workflow.execute(id, r, key, a.getName())));
  }

  @PostMapping("/bundles/tasks/{id}/review")
  @PreAuthorize("hasRole('DOCTOR')")
  public Envelope<?> taskReview(
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,100}") String key,
      @Valid @RequestBody Requests.TaskReview r,
      Authentication a) {
    return Envelope.ok(Views.task(workflow.reviewTask(id, r, key, a.getName())));
  }

  @GetMapping("/bundles/tasks/{id}/history")
  public Envelope<?> taskHistory(
      @PathVariable String id, @RequestParam(defaultValue = "0") @Min(0) int page) {
    s.get(id, Task.class);
    return Envelope.ok(
        q.page(
            q.scope().and("entityId").is(id).and("entityType").is("task"),
            History.class,
            page,
            50,
            Views::history));
  }

  @GetMapping("/alerts/{id}/history")
  public Envelope<?> alertHistory(
      @PathVariable String id, @RequestParam(defaultValue = "0") @Min(0) int page) {
    s.get(id, Alert.class);
    return Envelope.ok(
        q.page(
            q.scope().and("entityId").is(id).and("entityType").is("alert"),
            History.class,
            page,
            50,
            Views::history));
  }

  @GetMapping({"/quality/overview", "/quality/bundle-compliance"})
  @PreAuthorize("hasAnyRole('DOCTOR','QUALITY')")
  public Envelope<?> quality(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
    return Envelope.ok(q.quality(from, to));
  }

  @GetMapping("/overview")
  public Envelope<?> overview() {
    return Envelope.ok(q.overview());
  }

  @GetMapping("/models/status")
  public Envelope<?> models() {
    return Envelope.ok(
        Views.map(
            "modelStatus",
            model.status(),
            "predictionSource",
            "unavailable",
            "riskValue",
            null,
            "localValidationStatus",
            "not_validated",
            "realModelRunning",
            false,
            "clinicalUseAllowed",
            false,
            "reason",
            "未接入可核验权重、特征契约、标签与校准验证；不输出风险百分比"));
  }

  @GetMapping("/system/health")
  public Envelope<?> health() {
    s.db.executeCommand("{ping:1}");
    return Envelope.ok(
        Views.map(
            "database", "reachable", "clinicalUseAllowed", false, "serverTime", Instant.now()));
  }
}
