package org.icu.sepsis.sepsis;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.icu.sepsis.sepsis.Icu05Rules.*;
import org.junit.jupiter.api.Test;

class Icu05RulesTest {
  static final Instant T0 = Instant.parse("2024-03-01T02:00:00Z");
  static Instant at(int minutes) { return T0.plus(Duration.ofMinutes(minutes)); }
  static Drug drug(int minutes, String name, Double ml, String... actions) {
    Drug d = new Drug(); d.start = at(minutes); d.items.add(new DrugItem(name, ml)); d.items.get(0).actualInfusedMl = ml;
    for (String a : actions) d.actions.add(new Action(at(minutes + 30), a));
    return d;
  }

  /** Confirmed septic shock with every element on time. */
  static Evidence full() {
    Evidence e = new Evidence(); e.k1Selection = "maximum"; e.k1WindowStart = at(-24 * 60); e.k1WindowEnd = at(360); e.k1WindowSource = "synthetic-test-window"; e.vasoDictionary.add("去甲肾上腺素注射液"); e.vasoDictionary.add("去甲肾上腺素");
    e.asOf = at(24 * 60); e.t0 = T0; e.t0OrderName = "一级护理";
    e.icuAdmission = at(-30); e.hospitalAdmission = at(-300); e.admissionType = "入院";
    e.diagnosisText = "肺炎|脓毒症";
    e.lactate.add(new Point(at(20), 4.6, null, "l1"));
    e.lactate.add(new Point(at(240), 2.1, null, "l2"));
    e.map.add(new Point(at(10), 58, "IBP", "m1"));
    e.map.add(new Point(at(200), 72, "NIBP", "m2"));
    e.gcs.add(new Point(at(5), 14, null, "g1"));
    e.cultureOrders.add(new Order("血培养(需氧+厌氧)", at(5), at(15), "o1"));
    e.drugs.add(drug(40, "注射用美罗培南", 100.0, "start"));
    e.drugs.add(drug(30, "0.9%氯化钠注射液", 500.0, "start"));
    e.drugs.add(drug(90, "乳酸林格注射液", 1000.0, "start"));
    e.drugs.add(drug(35, "*去甲肾上腺素注射液", 50.0, "start"));
    return e;
  }
  @SuppressWarnings("unchecked") static Map<String, Object> win(Map<String, Object> r, String key) {
    return ((List<Map<String, Object>>) r.get("windows")).stream().filter(w -> key.equals(w.get("key"))).findFirst().orElseThrow();
  }
  @SuppressWarnings("unchecked") static Map<String, Object> item(Map<String, Object> w, String code) {
    return ((List<Map<String, Object>>) w.get("items")).stream().filter(i -> code.equals(i.get("code"))).findFirst().orElseThrow();
  }
  @SuppressWarnings("unchecked") static Map<String, Object> gate(Map<String, Object> r) { return (Map<String, Object>) r.get("gate"); }

  @Test void confirmedShockAllWindowsMet() {
    Map<String, Object> r = Icu05Rules.evaluate(full());
    assertEquals("septic_shock", r.get("status"));
    assertEquals(true, gate(r).get("isSepticShock"));
    for (String k : List.of("1h", "3h", "6h")) assertEquals("met", win(r, k).get("state"), k);
    assertEquals(true, item(win(r, "1h"), "B3").get("value"));
    assertEquals("triggered", win(r, "1h").get("finishPath"));
    assertEquals(true, item(win(r, "6h"), "A2").get("value"));
    assertEquals(true, ((Map<?, ?>) r.get("pool")).get("eligible"));
  }

  @Test void antibioticBeforeCultureFailsStep2() {
    Evidence e = full();
    e.cultureOrders.set(0, new Order("血培养", at(5), at(50), "o1"));
    Map<String, Object> w = win(Icu05Rules.evaluate(e), "1h");
    assertEquals(false, item(w, "B3").get("value"));
    assertEquals("BC_AFTER_AB", item(w, "B3").get("reason"));
    assertEquals(false, w.get("finish"));
    assertEquals("not_met", w.get("state"));
  }

  @Test void missingLactateIsUnknownNotFailed() {
    Evidence e = full(); e.lactate.clear();
    Map<String, Object> r = Icu05Rules.evaluate(e);
    Map<String, Object> w = win(r, "1h");
    assertNull(item(w, "A1").get("value"));
    assertEquals("DATA_MISSING_LAC", item(w, "A1").get("reason"));
    assertNull(w.get("finish"));
    assertEquals("unknown", w.get("state"));
    assertEquals("unknown", r.get("status"), "missing lactate cannot confirm candidate");
  }

  @Test void openWindowIsInProgress() {
    Evidence e = full(); e.asOf = at(25);
    Map<String, Object> w = win(Icu05Rules.evaluate(e), "1h");
    assertEquals("in_progress", w.get("state"));
    assertNull(w.get("finish"));
    assertEquals(false, w.get("closed"));
  }

  @Test void preT0VasopressorRequiresActionEvidence() {
    Evidence e = full(); e.drugs.removeIf(d -> d.items.get(0).name.contains("肾上腺素"));
    e.map.clear(); e.map.add(new Point(at(10), 75, "NIBP", "m"));
    Drug noActions = drug(-120, "去甲肾上腺素", 50.0); e.drugs.add(noActions);
    Map<String, Object> r = Icu05Rules.evaluate(e);
    assertNull(gate(r).get("isSepticShock"), "pre-T0 pressor without action list is unknown");
    noActions.actions.add(new Action(at(-60), "stop"));
    assertEquals(false, gate(Icu05Rules.evaluate(e)).get("isSepticShock"));
    noActions.actions.clear(); noActions.actions.add(new Action(at(-60), "start"));
    assertEquals(true, gate(Icu05Rules.evaluate(e)).get("isSepticShock"));
  }

  @Test void shockWithoutInfectionIsSuspected() {
    Evidence e = full(); e.diagnosisText = "多发伤"; e.cultureOrders.clear();
    e.drugs.removeIf(d -> d.items.get(0).name.contains("美罗培南"));
    Map<String, Object> r = Icu05Rules.evaluate(e);
    assertEquals("suspected_review", r.get("status"));
    assertEquals(true, r.get("applicable"));
  }

  @Test void sourceFailureNeverBecomesFalse() {
    Evidence e = full(); e.drugs.clear(); e.failed.add("drugExe");
    Map<String, Object> r = Icu05Rules.evaluate(e);
    Map<String, Object> w = win(r, "1h");
    assertNull(item(w, "B1").get("value"));
    assertEquals("SOURCE_ERROR", item(w, "B1").get("reason"));
    assertNull(item(w, "C3").get("value"));
    assertNotEquals(false, w.get("finish"));
  }

  @Test void fluidWithoutVolumeIsUnknown() {
    Evidence e = full(); e.drugs.removeIf(d -> d.items.get(0).name.contains("林格"));
    e.drugs.add(drug(100, "复方氯化钠注射液", null, "start"));
    Map<String, Object> w = win(Icu05Rules.evaluate(e), "3h");
    assertNull(item(w, "C3").get("value"));
    assertEquals("FLUID_AMOUNT_INCOMPLETE", item(w, "C3").get("reason"));
  }

  @Test void noT0StopsJudgement() {
    Evidence e = full(); e.t0 = null;
    Map<String, Object> r = Icu05Rules.evaluate(e);
    assertEquals("no_t0", r.get("status"));
    assertTrue(((List<?>) r.get("windows")).isEmpty());
  }

  @Test void classification() {
    assertTrue(Icu05Rules.isVasopressor("★重酒石酸去甲肾上腺素注射液 2mg", Set.of()));
    assertFalse(Icu05Rules.isVasopressor("10%氯化钾注射液", Set.of("10%氯化钾注射液")));
    assertTrue(Icu05Rules.isVasopressor("某升压药", Set.of("某升压药")));
    assertNull(Icu05Rules.fluidType("10%氯化钠注射液"));
    assertEquals("colloid", Icu05Rules.fluidType("人血白蛋白"));
    assertFalse(Icu05Rules.isAntibiotic("头孢皮试剂", Set.of()));
    assertTrue(Icu05Rules.isAntibiotic("注射用头孢哌酮舒巴坦", Set.of()));
  }

  @Test void vasoActive_pauseThenRecoveryStillOn() {
    Drug d = new Drug();
    d.start = at(0);
    d.items.add(new DrugItem("*去甲肾上腺素注射液", 1.0));
    d.actions.add(new Action(at(10), "start"));
    d.actions.add(new Action(at(60), "pause"));
    d.actions.add(new Action(at(90), "recovery"));
    d.actions.add(new Action(at(200), "stop"));
    assertEquals(Boolean.TRUE, Icu05Rules.vasopressorActiveAt(d, at(120)));
    assertEquals(Boolean.FALSE, Icu05Rules.vasopressorActiveAt(d, at(70)));
    assertEquals(Boolean.FALSE, Icu05Rules.vasopressorActiveAt(d, at(250)));
  }

  @Test void vasoActive_pauseWithoutRecoveryOff() {
    Drug d = new Drug();
    d.start = at(0);
    d.items.add(new DrugItem("去甲肾上腺素", 1.0));
    d.actions.add(new Action(at(5), "start"));
    d.actions.add(new Action(at(40), "pause"));
    assertEquals(Boolean.FALSE, Icu05Rules.vasopressorActiveAt(d, at(100)));
  }

  @Test void vasoActive_startOnlyUnknownIfNoActions() {
    Drug d = new Drug();
    d.start = at(-30);
    d.items.add(new DrugItem("去甲肾上腺素", 1.0));
    assertNull(Icu05Rules.vasopressorActiveAt(d, at(10)));
    d.actions.add(new Action(at(-30), "start"));
    assertEquals(Boolean.TRUE, Icu05Rules.vasopressorActiveAt(d, at(10)));
  }

  @Test void pauseThenRecoveryCountsAsShockVasopressor() {
    Evidence e = full();
    e.drugs.removeIf(d -> d.items.get(0).name.contains("去甲"));
    Drug v = new Drug();
    v.start = at(20);
    v.items.add(new DrugItem("*去甲肾上腺素注射液", 50.0));
    v.actions.add(new Action(at(20), "start"));
    v.actions.add(new Action(at(40), "pause"));
    v.actions.add(new Action(at(50), "recovery"));
    e.drugs.add(v);
    Map<String, Object> r = Icu05Rules.evaluate(e);
    assertEquals(true, gate(r).get("isSepticShock"));
  }

  @Test void vasoStartedAfterT0AndStoppedInsideWindowStillCounts() {
    Evidence e = full();
    e.drugs.removeIf(d -> d.items.get(0).name.contains("去甲"));
    e.drugs.add(drug(35, "去甲肾上腺素注射液", 50.0, "start", "stop")); // stop at +65 min, before window end
    assertEquals(true, gate(Icu05Rules.evaluate(e)).get("isSepticShock"));
  }

  @Test void vasoStartedAfterT0WithoutActionsStillCounts() {
    Evidence e = full();
    e.drugs.removeIf(d -> d.items.get(0).name.contains("去甲"));
    e.drugs.add(drug(35, "去甲肾上腺素注射液", 50.0));
    assertEquals(true, gate(Icu05Rules.evaluate(e)).get("isSepticShock"));
  }

  @Test void vasoRunningDuringWindowSemantics() {
    Drug d = new Drug(); d.start = at(-120); d.items.add(new DrugItem("去甲肾上腺素", 50.0));
    d.actions.add(new Action(at(-120), "start"));
    assertNull(Icu05Rules.vasopressorRunningDuring(d, at(0), at(60)));          // never stopped, silent since: unknown
    assertEquals(true, Icu05Rules.vasopressorRunningDuring(d, at(0), at(60), false)); // gate keeps director behaviour
    d.actions.add(new Action(at(-60), "pause"));
    assertEquals(false, Icu05Rules.vasopressorRunningDuring(d, at(0), at(60)));
    d.actions.add(new Action(at(30), "recovery"));
    assertEquals(true, Icu05Rules.vasopressorRunningDuring(d, at(0), at(60)));  // resumed inside the window
    d.actions.add(new Action(at(90), "stop"));
    assertEquals(true, Icu05Rules.vasopressorRunningDuring(d, at(60), at(120)));
    assertEquals(false, Icu05Rules.vasopressorRunningDuring(d, at(120), at(180)));
    assertEquals(false, Icu05Rules.vasopressorRunningDuring(d, at(-200), at(-130)));
  }

  // ---- 2026-10 review regressions ----
  @Test void latestRecordsDetermineV3Ordering() {
    Evidence e = full();
    e.cultureOrders.set(0, new Order("血培养", at(5), at(50), "o1")); // culture reviewed at +50, first abx at +40
    e.drugs.add(drug(150, "注射用美罗培南", 100.0, "start"));        // repeat dose after the culture
    Map<String, Object> r = Icu05Rules.evaluate(e);
    for (String k : List.of("1h", "3h", "6h")) {
      assertEquals(!"1h".equals(k), item(win(r, k), "B3").get("value"), k);
      assertEquals("1h".equals(k) ? "not_met" : "met", win(r, k).get("state"), k);
    }
  }

  @Test void antibioticBeforeT0IsOutsideBundleWindow() {
    Evidence e = full();
    e.drugs.add(drug(-60, "头孢曲松钠", 100.0, "start"));
    assertEquals(true, item(win(Icu05Rules.evaluate(e), "1h"), "B3").get("value"));
  }

  @Test void inotropesAreNotVasopressors() {
    for (String n : List.of("多巴酚丁胺注射液", "盐酸异丙肾上腺素注射液", "米力农注射液", "左西孟旦注射液"))
      assertFalse(Icu05Rules.isVasopressor(n, Set.of(n)), n);
    assertTrue(Icu05Rules.isVasopressor("盐酸肾上腺素注射液", Set.of()));
    assertTrue(Icu05Rules.isVasopressor("盐酸多巴胺注射液", Set.of()));
    Evidence e = full(); e.drugs.removeIf(d -> d.items.get(0).name.contains("肾上腺素"));
    e.drugs.add(drug(35, "多巴酚丁胺注射液", 50.0, "start"));
    e.map.clear(); e.map.add(new Point(at(10), 75, "NIBP", "m"));
    assertEquals(false, gate(Icu05Rules.evaluate(e)).get("isSepticShock"));
  }

  @Test void shockGateUsesMap65() {
    Evidence e = full(); e.drugs.removeIf(d -> d.items.get(0).name.contains("肾上腺素"));
    e.map.clear(); e.map.add(new Point(at(10), 67, "NIBP", "m"));
    assertEquals(false, gate(Icu05Rules.evaluate(e)).get("isSepticShock"), "MAP 67 is not hypotension");
    e.map.clear(); e.map.add(new Point(at(10), 64, "NIBP", "m"));
    assertEquals(false, gate(Icu05Rules.evaluate(e)).get("isSepticShock"), "MAP cannot replace K2");
  }

  @Test void allK1K2CombinationsPreserveUnknown() {
    for (Boolean a : new Boolean[] {true, false, null}) for (Boolean b : new Boolean[] {true, false, null}) {
      Boolean expected; if (Boolean.FALSE.equals(a) || Boolean.FALSE.equals(b)) expected = false; else if (a == null || b == null) expected = null; else expected = true;
      assertEquals(expected, Icu05Rules.all(a,b));
    }
  }
  @Test void unconfirmedK1NeverEntersDenominator() {
    Evidence e = full(); e.k1Selection = "unconfirmed";
    Map<String,Object> g = gate(Icu05Rules.evaluate(e));
    assertNull(g.get("isSepticShock")); assertEquals(true,g.get("suspectedWarning"));
  }
  @Test void k1MaximumIsTracedWithInclusiveBoundsAndIgnoresInvalidOrFutureRecords() {
    Evidence e = full(); e.lactate.clear();
    e.k1WindowStart = at(-120); e.k1WindowEnd = at(60); e.asOf = at(40);
    e.lactate.add(new Point(at(-121),99,null,"before"));
    e.lactate.add(new Point(at(-120),1.9,null,"start"));
    e.lactate.add(new Point(at(15),2.0,null,"selected"));
    e.lactate.add(new Point(at(20),1.5,null,"latest"));
    e.lactate.add(new Point(at(25),Double.NaN,null,"nan"));
    e.lactate.add(new Point(at(30),Double.POSITIVE_INFINITY,null,"infinity"));
    e.lactate.add(new Point(at(35),-3,null,"negative"));
    e.lactate.add(new Point(at(41),80,null,"future"));
    e.lactate.add(new Point(at(61),90,null,"after"));
    Map<String,Object> k = Icu05Rules.k1(e);
    assertEquals(true,k.get("value")); assertEquals(2.0,k.get("number"));
    assertEquals(at(15),k.get("time")); assertEquals("selected",k.get("sourceId"));
    assertEquals(at(-120),k.get("windowFrom")); assertEquals(at(60),k.get("windowTo"));
    assertEquals(at(40),k.get("evaluatedTo"));
    e.asOf = at(60);
    assertEquals("future",Icu05Rules.k1(e).get("sourceId"),"record becomes eligible once its time is reached");
    e.lactate.removeIf(p -> "future".equals(p.sourceId));
    e.lactate.add(new Point(at(60),3,null,"end"));
    assertEquals("end",Icu05Rules.k1(e).get("sourceId"));
  }
  @Test void k1BelowThresholdEmptyAndFailedSourcesRemainDistinct() {
    Evidence e = full(); e.lactate.clear();
    assertNull(Icu05Rules.k1(e).get("value"));
    e.lactate.add(new Point(at(20),1.9,null,"low"));
    assertEquals(false,Icu05Rules.k1(e).get("value"));
    e.failed.add("bGATemp");
    assertNull(Icu05Rules.k1(e).get("number"));
    assertEquals("SOURCE_ERROR",Icu05Rules.k1(e).get("reason"));
  }
  @Test void unconfirmedWindowCannotSilentlyUseGeneralGate() {
    Evidence e = full(); e.k1WindowStart = null; e.k1WindowEnd = null; e.k1WindowSource = null;
    assertEquals("maximum",e.k1Selection);
    assertNull(Icu05Rules.k1(e).get("value"));
    assertEquals("K1_WINDOW_UNCONFIRMED",Icu05Rules.k1(e).get("reason"));
    assertNull(gate(Icu05Rules.evaluate(e)).get("isSepticShock"));
    e.k1WindowStart = at(-120); e.k1WindowEnd = at(60);
    assertNull(Icu05Rules.k1(e).get("value"),"bounds without rule provenance are unconfirmed");
    e.k1WindowSource = "synthetic-test"; e.k1WindowEnd = at(-121);
    assertNull(Icu05Rules.k1(e).get("value"),"reversed window is not usable");
  }
  @Test void unresolvedWindowPreservesRawRecordsAndTimeMeaning() {
    Evidence e = full(); e.k1WindowStart = null; e.k1WindowEnd = null; e.k1WindowSource = null;
    e.lactate.clear();
    Point report = new Point(at(-300),2.4,"LAB_REPORT_TIME","lab-record");
    report.rawValue = "2.40"; report.rawUnit = "mmol/L"; report.timeMeaning = "结果时间（authTime，非采样时间）";
    e.lactate.add(report);
    e.lactate.add(new Point(e.asOf.plusSeconds(1),5,null,"future"));
    Map<String,Object> r = Icu05Rules.evaluate(e);
    assertNull(Icu05Rules.k1(e).get("value"));
    @SuppressWarnings("unchecked") List<Map<String,Object>> records = (List<Map<String,Object>>) r.get("lactateRecords");
    assertEquals(1,records.size());
    assertEquals("2.40",records.get(0).get("rawValue")); assertEquals("mmol/L",records.get(0).get("rawUnit"));
    assertEquals(at(-300),records.get(0).get("time")); assertEquals("lab-record",records.get(0).get("sourceId"));
    assertEquals(report.timeMeaning,records.get(0).get("timeMeaning"));
  }
  @Test void tiedMaximumSelectionDoesNotDependOnInputOrder() {
    Evidence e = full(); e.lactate.clear();
    e.lactate.add(new Point(at(30),3,null,"later"));
    e.lactate.add(new Point(at(20),3,null,"b"));
    e.lactate.add(new Point(at(20),3,null,"a"));
    assertEquals("a",Icu05Rules.k1(e).get("sourceId"));
    Collections.reverse(e.lactate);
    assertEquals("a",Icu05Rules.k1(e).get("sourceId"));
  }
  @Test void wideDictionaryAndStrictListAreIndependent() {
    assertTrue(Icu05Rules.isVasoWide("左西孟旦", Set.of("左西孟旦")));
    assertFalse(Icu05Rules.isVasoStrict("左西孟旦"));
    assertTrue(Icu05Rules.isVasoStrict("多巴酚丁胺"));
    assertFalse(Icu05Rules.isVasoWide("多巴酚丁胺", Set.of()));
    Evidence e = full(); e.failed.add("vasoDictionary");
    assertNull(gate(Icu05Rules.evaluate(e)).get("isSepticShock"));
  }
  @Test void bagCapacityCannotProveActualInfusedVolume() {
    Evidence e = full();
    for (Drug d : e.drugs) for (DrugItem i : d.items) i.actualInfusedMl = null;
    assertNull(item(win(Icu05Rules.evaluate(e),"3h"),"C3").get("value"));
  }
  @Test void latestCultureIsTracedAndSameTimeDoesNotPassOrdering() {
    Evidence e = full(); e.cultureOrders.add(new Order("血培养",at(25),at(40),"latest"));
    Map<String,Object> w = win(Icu05Rules.evaluate(e),"1h");
    assertEquals("latest",item(w,"B2").get("sourceId"));
    assertEquals(at(15),item(w,"B2").get("earliestTime"));
    assertEquals(false,item(w,"B3").get("value"));
  }
  @Test void localMapBoundaryAndMissing() {
    for (double value : new double[] {64.9, 65.0, 65.1}) {
      Evidence e = full(); e.map.clear(); e.map.add(new Point(at(10), value, "IBP", "boundary"));
      assertEquals(value <= 65, item(win(Icu05Rules.evaluate(e), "1h"), "C1").get("value"));
    }
    Evidence e = full(); e.map.clear();
    assertNull(item(win(Icu05Rules.evaluate(e), "1h"), "C1").get("value"));
    e.map.add(new Point(at(10), 65, "IBP", "failed")); e.failed.add("bedside");
    assertNull(item(win(Icu05Rules.evaluate(e), "1h"), "C1").get("value"));
  }
  @Test void carrierSalineIsNotResuscitationFluid() {
    Evidence e = full(); e.drugs.removeIf(d -> fluidName(d));
    Drug carrier = new Drug(); carrier.start = at(20);
    carrier.items.add(new DrugItem("0.9%氯化钠注射液", 1600.0)); carrier.items.add(new DrugItem("注射用美罗培南", null));
    e.drugs.add(carrier);
    e.drugs.add(drug(25, "0.9%氯化钠注射液", 50.0, "start")); // flush
    Map<String, Object> w = win(Icu05Rules.evaluate(e), "3h");
    assertEquals(false, item(w, "C3").get("value"));
  }

  static boolean fluidName(Drug d) { return Icu05Rules.fluidType(d.items.get(0).name) != null; }

  @Test void antibioticStemsDoNotHitChemotherapy() {
    for (String n : List.of("多西他赛注射液", "替加氟胶囊", "克拉屈滨注射液", "米诺地尔片")) assertFalse(Icu05Rules.isAntibiotic(n, Set.of()), n);
    for (String n : List.of("盐酸多西环素片", "注射用替加环素", "克拉霉素缓释片", "盐酸米诺环素胶囊", "左氧氟沙星注射液", "注射用哌拉西林他唑巴坦"))
      assertTrue(Icu05Rules.isAntibiotic(n, Set.of()), n);
  }

  @Test void unconfirmedLactateUnitIsRetainedButNeverCalculated() {
    Evidence e = full(); e.lactate.clear();
    Point p = new Point(at(20), 40, null, "unit-unknown");
    p.rawValue = "40"; p.rawUnit = "mg/dL"; p.lactateUnitKnown = false; e.lactate.add(p);
    Map<String,Object> result = Icu05Rules.evaluate(e);
    assertNull(((List<Map<String,Object>>) gate(result).get("shock")).stream().filter(i->"K1".equals(i.get("code"))).findFirst().orElseThrow().get("value"));
    assertNull(item(win(result, "1h"), "A1").get("value"));
    Map<?,?> raw = ((List<Map<?,?>>) result.get("lactateRecords")).get(0);
    assertEquals("40", raw.get("rawValue")); assertEquals("mg/dL",raw.get("rawUnit"));
    assertEquals(false,raw.get("unitConfirmed"));
  }
  @Test void unrecognisedDrugActionRequiresNewExplicitStartOrRecovery() {
    Drug d = drug(-120,"去甲肾上腺素",50.0);
    d.actions.add(new Action(at(-60), "unknown-host-action"));
    assertNull(Icu05Rules.vasopressorActiveAt(d,T0));
    d.actions.add(new Action(at(-30),"add"));
    assertNull(Icu05Rules.vasopressorActiveAt(d,T0));
    d.actions.add(new Action(at(-10),"start"));
    assertEquals(true,Icu05Rules.vasopressorActiveAt(d,T0));
  }

  @Test void truncatedFallbackLactateSourceCannotConfirmK1OrWindowItems() {
    Evidence e=full();e.failed.add("VI_ICU_EXAM_ITEM.lactate");e.truncatedSources.add("VI_ICU_EXAM_ITEM.lactate");
    Map<String,Object> result=Icu05Rules.evaluate(e);
    Map<?,?> k1=((List<Map<String,Object>>)gate(result).get("shock")).stream().filter(i->"K1".equals(i.get("code"))).findFirst().orElseThrow();
    assertNull(k1.get("value")); assertEquals("SOURCE_ERROR",k1.get("reason"));
    assertNull(item(win(result,"1h"),"A1").get("value")); assertNull(item(win(result,"6h"),"A2").get("value"));
    assertEquals(Set.of("VI_ICU_EXAM_ITEM.lactate"),result.get("truncatedSources"));
  }
}
