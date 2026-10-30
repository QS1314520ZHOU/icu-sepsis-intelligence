package org.icu.sepsis.research.toolkit;

import static org.icu.sepsis.research.toolkit.Data.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Analyses on synthetic data with planted effects (no patient data). */
class AnalysesTest {
  private static final Map<String, Synth.Synthetic> CACHE = new HashMap<>();
  @TempDir Path tmp;

  /** Seed 2: Monte Carlo draw; across seeds 1–30 the >3h OR CI covered the planted e^1 in 28/30 (checked during porting). */
  static synchronized Synth.Synthetic data(String scenario) { return CACHE.computeIfAbsent(scenario, s -> Synth.make(1200, 2, s)); }

  static Map<String, Object> d(Map<String, Object> res) { return map(res.get("data")); }

  @Test void audit() {
    Map<String, Object> r = Audit.run(data("signal").cohort);
    assertEquals("ok", r.get("status"));
    Map<String, Object> c3 = map(get(d(r), "windows.3h.items.C3"));
    assertTrue((Double) c3.get("nullRate") > 0.05); // planted 10% incomplete fluid
    assertEquals("no_data", Audit.run(List.of()).get("status"));
  }

  @Test void alerts() {
    Synth.Synthetic s = data("signal");
    Map<String, Object> r = AlertsAnalysis.run(s.alerts, s.cohort);
    assertEquals("ok", r.get("status"));
    assertTrue(((Double[]) d(r).get("dayReview"))[0] < ((Double[]) d(r).get("nightReview"))[0]); // planted slower night review
    assertEquals("no_data", AlertsAnalysis.run(List.of(), s.cohort).get("status"));
  }

  @Test void scores() {
    List<Map<String, Object>> c = data("signal").cohort;
    Map<String, Object> r = Scores.run(c);
    assertEquals("ok", r.get("status"));
    assertTrue(((Double[]) get(d(r), "auroc.sofa2.auc"))[0] > 0.55);
    assertEquals("insufficient", Scores.run(c.subList(0, 30)).get("status"));
    assertTrue((Integer) d(r).get("deathsQc") > (Integer) d(r).get("deaths")); // QC adds 非医嘱离院
    assertTrue(c.stream().anyMatch(x -> "非医嘱离院".equals(get(x, "outcome.dischargedType")) && Boolean.FALSE.equals(get(x, "outcome.death"))
        && Boolean.TRUE.equals(get(x, "outcome.deathQc"))));
  }

  private static Map<String, Object> abxTerm(Map<String, Object> res) {
    return map(map(get(d(res), "models.abxCategory.terms")).get(Timing.ABX_GT3));
  }

  @Test void timingRecoversPlantedEffect() {
    Map<String, Object> t = abxTerm(Timing.run(data("signal").cohort));
    assertTrue((Double) t.get("lo") > 1.0);
    assertTrue((Double) t.get("lo") < 2.72 && 2.72 < (Double) t.get("hi")); // planted OR e^1
  }

  @Test void timingNullScenario() {
    Map<String, Object> t = abxTerm(Timing.run(data("null").cohort));
    assertTrue((Double) t.get("lo") < 1.0 && 1.0 < (Double) t.get("hi"));
  }

  @Test void timingSmallSampleRefuses() {
    assertEquals("insufficient", Timing.run(data("signal").cohort.subList(0, 80)).get("status"));
  }

  @Test @SuppressWarnings("unchecked") void phenotypesRecoverThree() {
    List<Map<String, Object>> c = data("signal").cohort;
    Map<String, Object> r = Phenotypes.run(c);
    assertEquals("ok", r.get("status"));
    assertEquals(3, d(r).get("k"));
    assertTrue((Double) d(r).get("stabilityARI") > 0.8);
    List<Map<String, Object>> profiles = (List<Map<String, Object>>) d(r).get("profiles");
    double first = ((Double[]) profiles.get(0).get("death"))[0];
    for (Map<String, Object> p : profiles) assertTrue(first >= ((Double[]) p.get("death"))[0]); // most vasopressor hours = highest planted risk
    assertEquals("insufficient", Phenotypes.run(c.subList(0, 40)).get("status"));
  }

  @Test @SuppressWarnings("unchecked") void predictionSignalBeatsNullAndRule() {
    Map<String, Object> sig = Prediction.run(data("signal").cohort, 50), nul = Prediction.run(data("null").cohort, 50);
    assertEquals("ok", sig.get("status"));
    assertTrue((Double) d(sig).get("auroc") > (Double) d(nul).get("auroc") + 0.03);
    assertTrue((Double) d(sig).get("sensitivity") > (Double) map(d(sig).get("ruleMAP65")).get("sensitivity"));
    assertEquals("insufficient", Prediction.run(data("signal").cohort.subList(0, 50)).get("status"));
  }

  @Test void landmarksStopAtUnknownPressorState() {
    List<Object> vaso = new ArrayList<>(Arrays.asList(0, 0, null));
    vaso.addAll(Collections.nCopies(21, 0));
    Map<String, Object> r = m("traj", m("map", Collections.nCopies(24, 70), "hr", Collections.nCopies(24, 90), "lactate", Arrays.asList(new Object[24]),
        "vaso", vaso), "timing", m("vasoFirstMin", null), "ageYears", 60);
    List<Integer> hs = new ArrayList<>();
    for (Prediction.Landmark l : Prediction.landmarks(r)) hs.add(l.h);
    assertEquals(List.of(1, 2), hs);
  }

  @Test void reportEndToEnd() throws Exception {
    Synth.write(tmp.resolve("exp"), 1200, 3, "signal");
    Map<String, Object> res = Report.run(tmp.resolve("exp"), tmp.resolve("out"));
    assertEquals(true, res.get("synthetic"));
    Map<String, Object> st = new LinkedHashMap<>();
    map(res.get("sections")).forEach((k, v) -> st.put(k, map(v).get("status")));
    assertTrue(st.values().stream().allMatch("ok"::equals), st.toString());
    String md = Files.readString(tmp.resolve("out/report.md"), StandardCharsets.UTF_8);
    assertTrue(md.contains("合成数据"));
    assertTrue(md.contains("静默运行"));
    assertFalse(md.contains("\uFFFD"));
    parse(Files.readString(tmp.resolve("out/results.json"), StandardCharsets.UTF_8));
  }

  @Test void reportEmptyExport() throws Exception {
    Files.writeString(tmp.resolve("cohort.jsonl"), "", StandardCharsets.UTF_8);
    Map<String, Object> res = Report.run(tmp, null);
    assertTrue(map(res.get("sections")).values().stream().allMatch(v -> "no_data".equals(map(v).get("status"))));
  }

  @Test void cliSynthAndReport() {
    assertEquals(0, ResearchCli.run(new String[] {"research-synth", "--out", tmp.resolve("e").toString(), "--n", "300", "--seed", "3"}));
    assertEquals(0, ResearchCli.run(new String[] {"research-report", "--export", tmp.resolve("e").toString(), "--out", tmp.resolve("r").toString()}));
    assertTrue(Files.exists(tmp.resolve("r/report.md")));
    assertEquals(2, ResearchCli.run(new String[] {"research-report"}));
  }
}
