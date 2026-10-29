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
    Drug d = new Drug(); d.start = at(minutes); d.items.add(new DrugItem(name, ml));
    for (String a : actions) d.actions.add(new Action(at(minutes + 30), a));
    return d;
  }

  /** Confirmed septic shock with every element on time. */
  static Evidence full() {
    Evidence e = new Evidence();
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
    assertEquals("septic_shock", r.get("status"), "vasopressor alone confirms shock (rule s3)");
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
}
