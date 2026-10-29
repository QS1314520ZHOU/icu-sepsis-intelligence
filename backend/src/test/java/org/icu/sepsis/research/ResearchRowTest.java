package org.icu.sepsis.research;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.icu.sepsis.integration.ResearchSourceReader.Value;
import org.icu.sepsis.sepsis.Icu05Rules;
import org.icu.sepsis.sepsis.Icu05Rules.*;
import org.junit.jupiter.api.Test;

class ResearchRowTest {
  static final Instant T0 = Instant.parse("2024-03-01T02:00:00Z");
  static final Set<String> DEATH = Set.of("死亡"), QC = Set.of("死亡", "非医嘱离院");
  static Instant at(int min) { return T0.plus(Duration.ofMinutes(min)); }
  static Drug drug(int min, String name, Double ml, Object... actions) {
    Drug d = new Drug(); d.start = at(min); d.items.add(new DrugItem(name, ml));
    for (int i = 0; i + 1 < actions.length; i += 2) d.actions.add(new Action(at((Integer) actions[i]), (String) actions[i + 1]));
    return d;
  }
  static Evidence evidence() {
    Evidence e = new Evidence();
    e.asOf = at(48 * 60); e.t0 = T0; e.t0OrderName = "一级护理";
    e.icuAdmission = at(-30); e.hospitalAdmission = at(-30 - 600); e.admissionType = "入院"; e.diagnosisText = "肺炎|脓毒症";
    e.lactate.add(new Point(at(20), 4.6, null, "l1")); e.lactate.add(new Point(at(240), 2.1, null, "l2"));
    e.map.add(new Point(at(10), 58, "IBP", "m1")); e.map.add(new Point(at(70), 63, "IBP", "m2")); e.map.add(new Point(at(200), 72, "NIBP", "m3"));
    e.hr.add(new Point(at(10), 120, null, "h1")); e.hr.add(new Point(at(40), 110, null, "h2"));
    e.gcs.add(new Point(at(5), 14, null, "g1"));
    e.pf.add(new Point(at(60), 180, null, "p1"));
    e.cultureOrders.add(new Order("血培养(需氧+厌氧)", at(5), at(15), "o1"));
    e.drugs.add(drug(40, "注射用美罗培南", 100.0, 40, "start"));
    e.drugs.add(drug(30, "0.9%氯化钠注射液", 500.0, 30, "start"));
    e.drugs.add(drug(90, "乳酸林格注射液", 1000.0, 90, "start"));
    e.drugs.add(drug(150, "羟乙基淀粉", null, 150, "start"));            // amount unknown -> 3h incomplete
    e.drugs.add(drug(35, "*去甲肾上腺素注射液", 50.0, 35, "start", 300, "stop")); // runs 0:35 - 5:00
    return e;
  }
  static Document patient() {
    return new Document("name", "张三").append("mrn", "A123").append("hisPid", "H9").append("gender", "男").append("age", 67)
        .append("weight", 70).append("dischargedType", "死亡").append("icuDischargeTime", Date.from(at(3 * 24 * 60)));
  }
  static Map<String, List<Value>> labs() {
    Map<String, List<Value>> m = new LinkedHashMap<>();
    m.put("PLT", List.of(new Value(at(60), 85, "10^9/L", "PLT"), new Value(at(-600), 20, "10^9/L", "PLT")));
    m.put("TBIL", List.of(new Value(at(60), 2.0, "mg/dL", "TBIL")));
    m.put("CREA", List.of(new Value(at(60), 150, "μmol/L", "CREA"), new Value(at(70), 9, "mmol?", "CREA")));
    return m;
  }
  static List<Document> scores() {
    return List.of(new Document("scoreType", "apacheII").append("time", Date.from(at(60))).append("total", 22)
            .append("apacheII", new Document("calDead", new Document("score", 0.42))),
        new Document("scoreType", "apacheII").append("time", Date.from(at(600))).append("total", 30),
        new Document("scoreType", "sofa").append("time", Date.from(at(120))).append("total", 9),
        new Document("scoreType", "sofa").append("time", Date.from(at(900))).append("total", 11));
  }
  static Map<String, Object> row() {
    Evidence e = evidence();
    return ResearchRow.build(patient(), "ENC-1", e, Icu05Rules.evaluate(e), labs(), scores(), "test-salt-123456", DEATH, QC);
  }
  @SuppressWarnings("unchecked") static Map<String, Object> m(Map<String, Object> r, String k) { return (Map<String, Object>) r.get(k); }

  @Test void noIdentifiersAndSaltedKey() throws Exception {
    Map<String, Object> r = row();
    String json = new ObjectMapper().writeValueAsString(r);
    for (String bad : List.of("张三", "A123", "H9", "ENC-1", "\"mrn\"", "\"name\"", "\"hisPid\"", "sourceId", "2024-03-01T")) assertFalse(json.contains(bad), bad);
    assertEquals(20, ((String) r.get("stayKey")).length());
    assertEquals(ResearchRow.stayKey("test-salt-123456", "ENC-1"), r.get("stayKey"));
    assertNotEquals(ResearchRow.stayKey("other-salt-000000", "ENC-1"), r.get("stayKey"));
    assertEquals("2024-03", r.get("admitMonth"));
    assertEquals(ResearchRow.SCHEMA, r.get("schema"));
  }

  @Test void demographicsOutcomeRelativeTimes() {
    Map<String, Object> r = row();
    assertEquals(67.0, r.get("ageYears"));
    assertEquals(10.0, r.get("hospitalToIcuHours"));
    assertEquals(30L, r.get("icuToT0Min"));
    Map<String, Object> o = m(r, "outcome");
    assertEquals(true, o.get("death"));
    assertEquals(72.5, o.get("icuLosHours"));
    assertEquals(3L * 24 * 60, o.get("icuOutMinFromT0"));
    Map<String, Object> t = m(r, "timing");
    assertEquals(40L, t.get("abxFirstMin"));
    assertEquals(35L, t.get("vasoFirstMin"));
    assertEquals(15L, t.get("bloodCultureMin"));
    assertEquals(4.6, t.get("lactateInitial")); assertEquals(20L, t.get("lactateInitialMin"));
    assertEquals(2.1, t.get("lactateLast6h")); assertEquals(58.0, t.get("mapMin6h"));
    assertEquals("septic_shock", m(r, "icu05").get("status"));
  }

  @Test void fluidTotalsFlagIncompleteAmounts() {
    Map<String, Object> t = m(row(), "timing");
    assertEquals(500.0, t.get("fluidMl1h")); assertEquals(false, t.get("fluidIncomplete1h"));
    assertEquals(1500.0, t.get("fluidMl3h")); assertEquals(true, t.get("fluidIncomplete3h"));
  }

  @Test @SuppressWarnings("unchecked") void hourlyTrajectoryAndVasoState() {
    Map<String, Object> tr = m(row(), "traj");
    List<Double> map = (List<Double>) tr.get("map");
    List<Integer> vaso = (List<Integer>) tr.get("vaso");
    assertEquals(24, map.size());
    assertEquals(58.0, map.get(0)); assertEquals(63.0, map.get(1)); assertNull(map.get(2)); assertEquals(72.0, map.get(3));
    assertEquals(115.0, ((List<Double>) tr.get("hr")).get(0));
    assertEquals(List.of(1, 1, 1, 1, 1, 0), vaso.subList(0, 6)); // started 0:35 (hour 0), stopped 5:00
    assertEquals(0, vaso.get(23));
  }

  @Test @SuppressWarnings("unchecked") void vasoWithoutStopOrActionsIsUnknownNotZero() {
    Evidence e = evidence();
    e.drugs.removeIf(d -> d.items.get(0).name.contains("去甲"));
    e.drugs.add(drug(35, "去甲肾上腺素", 50.0)); // no actions at all
    Map<String, Object> r = ResearchRow.build(patient(), "ENC-1", e, Icu05Rules.evaluate(e), labs(), scores(), "test-salt-123456", DEATH, QC);
    List<Integer> vaso = (List<Integer>) m(r, "traj").get("vaso");
    assertEquals(1, vaso.get(0));
    assertNull(vaso.get(1));
  }

  @Test void worstValuesConvertUnitsAndIgnoreUnknown() {
    Map<String, Object> w = m(row(), "worst24");
    assertEquals(85.0, w.get("pltMin"));          // value 6h+ before T0 ignored
    assertEquals(34.2, w.get("tbilMaxUmol"));     // 2.0 mg/dL * 17.104
    assertEquals(150.0, w.get("creaMaxUmol"));    // unknown unit dropped, not guessed
    assertEquals(180.0, w.get("pfMin")); assertEquals(14.0, w.get("gcsMin"));
    assertEquals(List.of("ne"), w.get("vasoAgents"));
    Map<String, Object> s = m(row(), "scores");
    assertEquals(22.0, s.get("apache2First24")); assertEquals(0.42, s.get("apache2CalDead")); assertEquals(11.0, s.get("sofaMax24"));
  }

  @Test void agentMapping() {
    assertEquals("ne", ResearchRow.agent("重酒石酸去甲肾上腺素"));
    assertEquals("epi", ResearchRow.agent("盐酸肾上腺素"));
    assertEquals("other", ResearchRow.agent("异丙肾上腺素"));
    assertEquals("dobutamine", ResearchRow.agent("多巴酚丁胺"));
    assertEquals("dopamine", ResearchRow.agent("多巴胺"));
    assertEquals("vasopressin", ResearchRow.agent("垂体后叶素"));
  }

  @Test void noT0RowCarriesNoTiming() {
    Evidence e = evidence(); e.t0 = null;
    Map<String, Object> r = ResearchRow.build(patient(), "ENC-2", e, Icu05Rules.evaluate(e), labs(), scores(), "test-salt-123456", DEATH, QC);
    assertEquals(false, r.get("t0Available"));
    assertFalse(r.containsKey("timing")); assertFalse(r.containsKey("traj"));
    assertTrue(r.containsKey("outcome"));
  }

  @Test @SuppressWarnings("unchecked") void pauseRecoveryHourlyState() {
    Evidence e = evidence();
    e.drugs.removeIf(d -> d.items.get(0).name.contains("去甲"));
    e.drugs.add(drug(35, "去甲肾上腺素", 50.0, 35, "start", 130, "pause", 250, "recovery", 400, "stop"));
    Map<String, Object> r = ResearchRow.build(patient(), "ENC-1", e, Icu05Rules.evaluate(e), labs(), scores(), "test-salt-123456", DEATH, QC);
    // hours: 0 start, 1 on, 2 paused at 2:10 (ran part of hour), 3 off, 4 resumed 4:10, 5 on, 6 stopped 6:40, 7 off
    assertEquals(List.of(1, 1, 1, 0, 1, 1, 1, 0), ((List<Integer>) m(r, "traj").get("vaso")).subList(0, 8));
  }

  @Test void selfDischargeCountsAsDeathOnlyForQualityControl() {
    Evidence e = evidence();
    Document p = patient().append("dischargedType", "非医嘱离院");
    Map<String, Object> o = m(ResearchRow.build(p, "ENC-1", e, Icu05Rules.evaluate(e), labs(), scores(), "test-salt-123456", DEATH, QC), "outcome");
    assertEquals(false, o.get("death"));
    assertEquals(true, o.get("deathQc"));
    Map<String, Object> o2 = m(row(), "outcome");
    assertEquals(true, o2.get("death")); assertEquals(true, o2.get("deathQc"));
  }
}
