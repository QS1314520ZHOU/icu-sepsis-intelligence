package org.icu.sepsis.quality;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.icu.sepsis.domain.Domain.Patient;
import org.junit.jupiter.api.Test;

class Icu05BoardServiceTest {
  static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");
  static Map<String, Object> it(String code, Boolean v) { Map<String, Object> m = new LinkedHashMap<>(); m.put("code", code); m.put("value", v); return m; }
  static Map<String, Object> w(String key, String state, Instant deadline, List<String> reasons, Map<String, Object>... items) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("key", key); m.put("state", state); m.put("deadline", deadline); m.put("reasons", reasons); m.put("items", List.of(items)); return m;
  }
  static Icu05BoardService.Case c(String id, String status, Boolean eligible, Map<String, Object>... windows) {
    Patient p = new Patient(); p.id = id; p.encounterId = "e" + id; p.label = "患者" + id; p.bed = id;
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("available", true); r.put("status", status); r.put("statusLabel", status); r.put("t0", NOW.minusSeconds(3600 * 4));
    r.put("pool", Map.of("eligible", eligible == null ? "x" : eligible)); if (eligible == null) r.put("pool", new HashMap<>());
    r.put("windows", List.of(windows));
    return new Icu05BoardService.Case(p, r);
  }

  @Test void ratesExcludeUnknownAndIneligible() {
    List<Icu05BoardService.Case> cs = List.of(
        c("1", "septic_shock", true,
            w("1h", "met", NOW.minusSeconds(3 * 3600), List.of(), it("A1", true), it("B1", true), it("B3", true)),
            w("3h", "not_met", NOW.minusSeconds(3600), List.of("FLUID_INSUFFICIENT"), it("B3", true)),
            w("6h", "in_progress", NOW.plusSeconds(7200), List.of("WINDOW_OPEN"), it("A1", true), it("A2", null))),
        c("2", "septic_shock", true,
            w("1h", "not_met", NOW.minusSeconds(3 * 3600), List.of("BC_AFTER_AB"), it("A1", null), it("B1", true), it("B3", false)),
            w("3h", "unknown", NOW.minusSeconds(3600), List.of()),
            w("6h", "met", NOW.minusSeconds(60), List.of())),
        c("3", "septic_shock", false, w("1h", "met", NOW, List.of())),
        c("4", "suspected_review", null),
        c("5", "not_met", true));
    Map<String, Object> out = Icu05BoardService.aggregate(cs, NOW);
    assertEquals(5, out.get("screened"));
    assertEquals(3, out.get("septicShock"));
    assertEquals(2, out.get("denominator"));
    assertEquals(1, out.get("excluded"));
    assertEquals(1, out.get("suspected"));
    @SuppressWarnings("unchecked") List<Map<String, Object>> ws = (List<Map<String, Object>>) out.get("windows");
    assertEquals(0.5, ws.get(0).get("rate"));
    assertEquals(0.0, ws.get(1).get("rate"), "unknown is not counted as not met");
    assertEquals(1, ws.get(1).get("unknown"));
    assertEquals(1.0, ws.get(2).get("rate"));
    assertEquals(1, ws.get(2).get("inProgress"));
    @SuppressWarnings("unchecked") List<Map<String, Object>> ind = (List<Map<String, Object>>) out.get("indicators");
    assertEquals(1.0, ind.get(0).get("rate"));
    assertEquals(0.5, ind.get(1).get("rate"));
    assertEquals(1, ind.get(2).get("unknown"));
    assertEquals(false, ind.get(3).get("available"));
    @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("cases");
    assertEquals(4, rows.size(), "non-shock cases are not listed");
    assertEquals("1", rows.get(0).get("patientId"), "open window with a pending item comes first");
    assertEquals("复测乳酸", rows.get(0).get("next"));
    assertEquals(120L, rows.get(0).get("minutesLeft"));
    assertEquals("4", rows.get(1).get("patientId"), "suspected cases need review next");
    assertTrue(((List<?>) rows.get(2).get("failReasons")).contains("抗菌药早于血培养"));
  }

  /** Spring must be able to pick the public constructor (regression: "No default constructor found" at startup). */
  @Test void unknownK1CannotChangeNumeratorDenominatorOrBecomeNotMet() {
    Icu05BoardService.Case confirmed = c("confirmed","septic_shock",true,w("1h","met",NOW,List.of()));
    Map<String,Object> baseline = Icu05BoardService.aggregate(List.of(confirmed),NOW);
    org.icu.sepsis.sepsis.Icu05Rules.Evidence e = new org.icu.sepsis.sepsis.Icu05Rules.Evidence();
    e.t0 = NOW.minusSeconds(7200); e.asOf = NOW;
    e.icuAdmission = e.t0.minusSeconds(60); e.hospitalAdmission = e.t0.minusSeconds(120);
    e.admissionType = "入院"; e.diagnosisText = "肺炎";
    e.vasoDictionary.add("synthetic-vaso");
    org.icu.sepsis.sepsis.Icu05Rules.Drug drug = new org.icu.sepsis.sepsis.Icu05Rules.Drug();
    drug.start = e.t0.plusSeconds(60); drug.items.add(new org.icu.sepsis.sepsis.Icu05Rules.DrugItem("synthetic-vaso",null));
    e.drugs.add(drug);
    e.lactate.add(new org.icu.sepsis.sepsis.Icu05Rules.Point(e.t0,4,null,"synthetic-lab"));
    Map<String,Object> unknown = org.icu.sepsis.sepsis.Icu05Rules.evaluate(e); unknown.put("available",true);
    assertEquals("unknown",unknown.get("status"));
    @SuppressWarnings("unchecked") Map<String,Object> gate = (Map<String,Object>)unknown.get("gate");
    @SuppressWarnings("unchecked") List<Map<String,Object>> shock = (List<Map<String,Object>>)gate.get("shock");
    assertNull(shock.get(0).get("value"));
    Patient p = new Patient(); p.id="unknown"; p.encounterId="unknown-encounter";
    Map<String,Object> result = Icu05BoardService.aggregate(List.of(confirmed,new Icu05BoardService.Case(p,unknown)),NOW);
    assertEquals(baseline.get("denominator"),result.get("denominator"));
    assertEquals(baseline.get("windows"),result.get("windows"));
    assertEquals(baseline.get("excluded"),result.get("excluded"));
  }
  @Test void boardEvaluatesAllMatchedCasesAndReportsReadFailure() {
    org.springframework.data.mongodb.core.MongoTemplate db = org.mockito.Mockito.mock(org.springframework.data.mongodb.core.MongoTemplate.class);
    org.icu.sepsis.application.Store store = new org.icu.sepsis.application.Store(db,"ICU",new com.fasterxml.jackson.databind.ObjectMapper());
    org.icu.sepsis.sepsis.Icu05Service service = org.mockito.Mockito.mock(org.icu.sepsis.sepsis.Icu05Service.class);
    List<Patient> ps = new ArrayList<>();
    for (int i=0;i<301;i++) { Patient p = new Patient(); p.id="p"+i; p.encounterId="e"+i; ps.add(p); }
    org.mockito.Mockito.when(db.find(org.mockito.ArgumentMatchers.any(org.springframework.data.mongodb.core.query.Query.class),org.mockito.ArgumentMatchers.eq(Patient.class))).thenReturn(ps);
    org.mockito.Mockito.when(service.evaluate(org.mockito.ArgumentMatchers.any(Patient.class),org.mockito.ArgumentMatchers.eq(NOW))).thenReturn(Map.of("available",false));
    org.mockito.Mockito.when(service.evaluate(ps.get(300),NOW)).thenThrow(new IllegalStateException("synthetic failure"));
    Map<String,Object> r = new Icu05BoardService(store,service,Clock.fixed(NOW,ZoneOffset.UTC)).board(30);
    assertEquals(301,r.get("matched")); assertEquals(300,r.get("screened"));
    assertEquals(false,r.get("truncated")); assertEquals(false,r.get("complete")); assertEquals(1,r.get("readFailed"));
    org.mockito.ArgumentCaptor<org.springframework.data.mongodb.core.query.Query> query = org.mockito.ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Query.class);
    org.mockito.Mockito.verify(db).find(query.capture(),org.mockito.ArgumentMatchers.eq(Patient.class));
    assertEquals(0,query.getValue().getLimit(),"query must not silently cap cases");
    assertEquals("ICU",query.getValue().getQueryObject().get("department"));
  }
  @Test void springCanInstantiate() {
    try (org.springframework.context.annotation.AnnotationConfigApplicationContext ctx = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
      ctx.registerBean(org.icu.sepsis.application.Store.class, () -> org.mockito.Mockito.mock(org.icu.sepsis.application.Store.class));
      ctx.registerBean(org.icu.sepsis.sepsis.Icu05Service.class, () -> org.mockito.Mockito.mock(org.icu.sepsis.sepsis.Icu05Service.class));
      ctx.register(Icu05BoardService.class);
      ctx.refresh();
      assertNotNull(ctx.getBean(Icu05BoardService.class));
    }
  }

  @Test void reviewCoverageCountsCasesOnceAndKeepsClinicalStatisticsUnchanged() {
    Icu05BoardService.Case a=c("a","septic_shock",true,w("1h","met",NOW,List.of()));
    Icu05BoardService.Case b=c("b","unknown",null);
    Map<String,Object> before=Icu05BoardService.aggregate(List.of(a,b),NOW);
    a.r.put("caseReview",Map.of("applied",true));
    a.r.put("overrides",List.of(Map.of("scope","judgement","applied",true),Map.of("scope","judgement","applied",true),Map.of("scope","sofa_component","applied",true)));
    b.r.put("overrides",List.of(Map.of("scope","judgement","applied",false)));
    b.r.put("failedSources",List.of("bedside"));
    Map<String,Object> after=Icu05BoardService.aggregate(List.of(a,b),NOW);
    Map<?,?> coverage=(Map<?,?>)after.get("manualReviews");
    assertEquals(2,coverage.get("denominator")); assertEquals(1,coverage.get("reviewedCases"));
    assertEquals(0.5,coverage.get("rate")); assertEquals(1,coverage.get("judgementCases"));
    assertEquals(1,coverage.get("caseExclusions")); assertEquals(1,coverage.get("sofaCases"));
    assertEquals(1,after.get("sourceIncomplete")); assertEquals(1,coverage.get("requiresReview")); assertEquals(1,after.get("classificationUnknown"));
    assertEquals(before.get("denominator"),after.get("denominator")); assertEquals(before.get("windows"),after.get("windows"));
  }
 @Test void failureReasonsCountEachPatientOnceAndExcludeUnknownWindows(){var failed=c("one","septic_shock",true,w("1h","not_met",NOW,List.of("AB_MISSING","AB_MISSING")));var unknown=c("two","septic_shock",true,w("1h","unknown",NOW,List.of("AB_MISSING")));var out=Icu05BoardService.aggregate(List.of(failed,unknown),NOW);var reasons=(List<Map<String,Object>>)out.get("failureReasons");assertEquals(1,reasons.size());assertEquals(1,reasons.get(0).get("cases"));assertEquals("1h",reasons.get(0).get("window"));}
}
