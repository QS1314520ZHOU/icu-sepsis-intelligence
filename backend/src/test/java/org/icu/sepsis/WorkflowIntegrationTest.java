package org.icu.sepsis;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.*;
import java.util.*;
import org.icu.sepsis.api.*;
import org.icu.sepsis.application.*;
import org.icu.sepsis.domain.Domain.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "app.demo=true",
      "app.source.mode=demo",
      "app.doctor-password=test-doctor-123456789",
      "app.nurse-password=test-nurse-123456789",
      "app.quality-password=test-quality-123456789",
      "spring.data.mongodb.uri=mongodb://127.0.0.1:27027/icu_sepsis_test?replicaSet=rs0"
    })
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "RUN_MONGO_TESTS", matches = "true")
class WorkflowIntegrationTest {
  @Autowired MongoTemplate db;
  @Autowired Store store;
  @Autowired AssessmentService svc;
  @Autowired WorkflowService flow;
  @Autowired QueryService query;
  @Autowired MockMvc http;

  @Test
  void clinicalReviewKeepsMissingUnknownWithoutFakeRisk() throws Exception {
    http.perform(get("/api/v1/patients/demo-insufficient/sepsis-assessment").with(user("doctor").roles("DOCTOR")))
      .andExpect(status().isOk()).andExpect(jsonPath("$.data.schemaVersion").value(2))
      .andExpect(jsonPath("$.data.qsofa").isEmpty()).andExpect(jsonPath("$.data.risk").doesNotExist())
      .andExpect(jsonPath("$.data.model.configured").value(false))
      .andExpect(jsonPath("$.data.screeningStatus").value("insufficient_data"));
  }

  @Test
  void actualBasicAuthenticationSucceeds() throws Exception {
    http.perform(get("/api/v1/me").with(httpBasic("doctor", "test-doctor-123456789")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value("doctor"));
  }

  @Test
  void wrongPasswordReturns401() throws Exception {
    http.perform(get("/api/v1/me").with(httpBasic("doctor", "wrong-password")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void exactMetricDrilldownReconciles() {
    Task t = task();
    t = flow.execute(t.id, ex(t, Instant.now()), key(), "nurse");
    flow.reviewTask(t.id, tr(t, "required"), key(), "doctor");
    Instant f = Instant.now().minusSeconds(86400), to = Instant.now().plusSeconds(1);
    long numerator = db.count(Query.query(query.tasks("onTime", f, to)), Task.class);
    long denominator = db.count(Query.query(query.tasks("den", f, to)), Task.class);
    assertEquals(query.quality(f, to).get("onTime"), numerator);
    assertEquals(query.quality(f, to).get("denominator"), denominator);
  }

  @Test
  void realPatientCannotUseDraftRules() {
    Patient p = db.findById("demo-normal", Patient.class);
    p.simulated = false;
    db.save(p);
    try {
      assertThrows(ApiException.class, () -> svc.assess(p.id, req(), key(), "doctor"));
    } finally {
      p.simulated = true;
      db.save(p);
    }
  }

  @Test
  void workspaceSummaryKeepsValidatedUnitsAndSource() throws Exception {
    http.perform(
            get("/api/v1/patients").param("search", "模拟患者 B").with(user("doctor").roles("DOCTOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].vitals.MAP.value").value(58.0))
        .andExpect(jsonPath("$.data.items[0].vitals.MAP.unit").value("mmHg"))
        .andExpect(jsonPath("$.data.items[0].vitals.MAP.sourceRecordId").isNotEmpty());
  }

  @Test
  void missingWorkspaceSummaryDoesNotFabricateVitals() throws Exception {
    http.perform(
            get("/api/v1/patients").param("search", "模拟患者 C").with(user("doctor").roles("DOCTOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].vitals.MAP").doesNotExist())
        .andExpect(jsonPath("$.data.items[0].latestAssessment").isEmpty());
  }

  @Test
  void clinicalWindowReturnsAsOfAndBoundForPlotting() throws Exception {
    http.perform(
            get("/api/v1/patients/demo-deteriorating/trends").with(user("doctor").roles("DOCTOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.asOf").isNotEmpty())
        .andExpect(jsonPath("$.data.windowStart").isNotEmpty())
        .andExpect(jsonPath("$.data.limit").value(2000))
        .andExpect(jsonPath("$.data.items").isArray());
  }

  @BeforeEach
  void cleanup() {
    assertTrue(db.getDb().getName().endsWith("_test"));
    for (Class<?> type :
        List.of(
            Assessment.class,
            Alert.class,
            Task.class,
            Execution.class,
            History.class,
            Audit.class,
            Command.class)) db.remove(new Query(), type);
  }

  Requests.Assess req() {
    Requests.Assess x = new Requests.Assess();
    x.asOf = Instant.now();
    return x;
  }

  String key() {
    return UUID.randomUUID().toString();
  }

  Alert alert() {
    svc.assess("demo-deteriorating", req(), key(), "doctor");
    return db.findOne(new Query(), Alert.class);
  }

  Requests.Review review(Alert a, String action) {
    Requests.Review r = new Requests.Review();
    r.action = action;
    r.reason = "软件测试复核原因";
    r.expectedVersion = a.version;
    return r;
  }

  Task task() {
    Alert a = alert();
    flow.reviewAlert(a.id, review(a, "confirm"), key(), "doctor");
    return db.findOne(new Query(), Task.class);
  }

  Requests.TaskReview tr(Task t, String applicability) {
    Requests.TaskReview r = new Requests.TaskReview();
    r.action = "confirm";
    r.reason = "适用性测试审核";
    r.expectedVersion = t.version;
    r.applicability = applicability;
    r.targetStatus = "not_assessed";
    return r;
  }

  Requests.Execute ex(Task t, Instant when) {
    Requests.Execute e = new Requests.Execute();
    e.eventTime = when;
    e.reason = "补录模拟执行证据";
    e.sourceRecordId = key();
    e.sourceSystem = "MOCK_TEST";
    e.expectedVersion = t.version;
    return e;
  }

  Map<String, Object> metric() {
    return query.quality(Instant.now().minus(Duration.ofDays(1)), Instant.now().plusSeconds(1));
  }

  @Test
  void normalNoAlert() {
    Assessment a = svc.assess("demo-normal", req(), key(), "doctor");
    assertEquals("not_triggered", a.screeningStatus);
    assertEquals(0, db.count(new Query(), Alert.class));
    assertNull(a.predictions.get(0).riskValue);
  }

  @Test
  void infectionAndDeteriorationProduceEvidence() {
    Assessment a = svc.assess("demo-deteriorating", req(), key(), "doctor");
    assertEquals("supported", a.infectionVerdict);
    assertEquals("suspected_requires_review", a.shockScreeningStatus);
    assertFalse(a.evidence.isEmpty());
    assertEquals(1, db.count(new Query(), Alert.class));
  }

  @Test
  void insufficientNeverNormal() {
    Assessment a = svc.assess("demo-insufficient", req(), key(), "doctor");
    assertNull(a.ruleScore);
    assertEquals("unable_to_assess", a.screeningStatus);
    assertNull(a.predictions.get(2).riskValue);
  }

  @Test
  void assessmentIdempotent() {
    Requests.Assess r = req();
    String k = key();
    Assessment a = svc.assess("demo-normal", r, k, "doctor");
    assertEquals(a.id, svc.assess("demo-normal", r, k, "doctor").id);
    assertEquals(1, db.count(new Query(), Assessment.class));
  }

  @Test
  void keyReuseDifferentPayloadRejected() {
    String k = key();
    svc.assess("demo-normal", req(), k, "doctor");
    assertThrows(ApiException.class, () -> svc.assess("demo-insufficient", req(), k, "doctor"));
  }

  @Test
  void repeatedAlertSuppressedButAssessmentKept() {
    svc.assess("demo-deteriorating", req(), key(), "doctor");
    svc.assess("demo-deteriorating", req(), key(), "doctor");
    assertEquals(1, db.count(new Query(), Alert.class));
    assertEquals(2, db.count(new Query(), Assessment.class));
    assertEquals(
        1, db.count(Query.query(Criteria.where("action").is("repeat_suppressed")), History.class));
  }

  @Test
  void confirmCreatesTaskAndAudit() {
    Alert a = alert();
    String k = key();
    Requests.Review r = review(a, "confirm");
    flow.reviewAlert(a.id, r, k, "doctor");
    flow.reviewAlert(a.id, r, k, "doctor");
    assertEquals(1, db.count(new Query(), Task.class));
    assertTrue(db.count(Query.query(Criteria.where("action").is("confirm")), Audit.class) > 0);
  }

  @Test
  void versionConflictRollsBack() {
    Alert a = alert();
    Requests.Review r = review(a, "confirm");
    r.expectedVersion = 999L;
    assertThrows(ApiException.class, () -> flow.reviewAlert(a.id, r, key(), "doctor"));
    assertEquals(0, db.count(new Query(), Task.class));
    assertEquals("pending", store.get(a.id, Alert.class).status);
  }

  @Test
  void rejectionCannotConfirm() {
    Alert a = alert();
    a = flow.reviewAlert(a.id, review(a, "reject"), key(), "doctor");
    Alert b = a;
    assertThrows(
        ApiException.class, () -> flow.reviewAlert(b.id, review(b, "confirm"), key(), "doctor"));
    assertEquals(0, db.count(new Query(), Task.class));
  }

  @Test
  void pendingReviewNotDenominator() {
    task();
    assertEquals(0L, metric().get("denominator"));
    assertNull(metric().get("onTimeRate"));
  }

  @Test
  void onTimeOnlyAfterReview() {
    Task t = task();
    t = flow.execute(t.id, ex(t, Instant.now()), key(), "nurse");
    assertEquals("met", t.executionStatus);
    assertEquals(0L, metric().get("denominator"));
    flow.reviewTask(t.id, tr(t, "required"), key(), "doctor");
    assertEquals(1L, metric().get("onTime"));
    assertEquals(1., metric().get("onTimeRate"));
  }

  @Test
  void lateNotOnTime() {
    Task t = task();
    t.triggerTime = Instant.now().minusSeconds(7200);
    t.dueAt = Instant.now().minusSeconds(3600);
    db.save(t);
    t = flow.execute(t.id, ex(t, Instant.now().minusSeconds(60)), key(), "nurse");
    assertEquals("met_late", t.executionStatus);
    flow.reviewTask(t.id, tr(t, "required"), key(), "doctor");
    assertEquals(1L, metric().get("late"));
    assertEquals(0L, metric().get("onTime"));
  }

  @Test
  void exceptionExcluded() {
    Task t = task();
    flow.reviewTask(t.id, tr(t, "not_applicable"), key(), "doctor");
    assertEquals(0L, metric().get("denominator"));
    assertEquals(1L, metric().get("excluded"));
  }

  @Test
  void futureExecutionRejected() {
    Task t = task();
    assertThrows(
        ApiException.class,
        () -> flow.execute(t.id, ex(t, Instant.now().plusSeconds(600)), key(), "nurse"));
    assertEquals(0, db.count(new Query(), Execution.class));
  }

  @Test
  void duplicateExecutionDoesNotOverwrite() {
    Task t = task();
    Requests.Execute e = ex(t, Instant.now());
    String k = key();
    Task updated = flow.execute(t.id, e, k, "nurse");
    flow.execute(t.id, e, k, "nurse");
    assertEquals(1, db.count(new Query(), Execution.class));
    assertThrows(
        ApiException.class,
        () -> flow.execute(updated.id, ex(updated, Instant.now()), key(), "nurse"));
  }

  @Test
  void otherDepartmentDenied() {
    Patient p = new Patient();
    p.id = "test-outside";
    p.department = "OTHER";
    db.save(p);
    try {
      assertThrows(ApiException.class, () -> store.patient(p.id));
    } finally {
      db.remove(Query.query(Criteria.where("_id").is(p.id)), Patient.class);
    }
  }

  @Test
  void nurseCannotConfirmAlert() throws Exception {
    http.perform(
            post("/api/v1/alerts/unknown/review")
                .with(user("nurse").roles("NURSE"))
                .header("Idempotency-Key", key())
                .contentType("application/json")
                .content("{\"action\":\"confirm\",\"reason\":\"test\",\"expectedVersion\":0}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void anonymousDeniedWithEnvelope() throws Exception {
    http.perform(get("/api/v1/patients"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
        .andExpect(header().exists("X-Trace-Id"));
  }

  @Test
  void nurseCannotQuality() throws Exception {
    http.perform(
            get("/api/v1/quality/overview")
                .with(user("nurse").roles("NURSE"))
                .param("from", Instant.now().minusSeconds(3600).toString())
                .param("to", Instant.now().toString()))
        .andExpect(status().isForbidden());
  }

  @Test
  void invalidPaginationIs400() throws Exception {
    http.perform(get("/api/v1/patients?size=101").with(user("doctor").roles("DOCTOR")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void missingIdempotencyIs400() throws Exception {
    http.perform(
            post("/api/v1/patients/demo-normal/risk-assessments")
                .with(user("doctor").roles("DOCTOR"))
                .contentType("application/json")
                .content("{\"asOf\":\"2026-10-28T02:00:00Z\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void modelStatusTruthful() throws Exception {
    http.perform(get("/api/v1/models/status").with(user("quality").roles("QUALITY")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.realModelRunning").value(false))
        .andExpect(jsonPath("$.data.riskValue").isEmpty());
  }

  @Test
  void historyAvailableScoped() throws Exception {
    Task t = task();
    http.perform(
            get("/api/v1/bundles/tasks/" + t.id + "/history").with(user("doctor").roles("DOCTOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1));
  }
}
