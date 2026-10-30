package org.icu.sepsis;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.icu.sepsis.config.RuleConfig;
import org.icu.sepsis.domain.*;
import org.icu.sepsis.domain.Domain.*;
import org.icu.sepsis.prediction.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class ClinicalRulesTest {
  ClinicalRules rules;
  Instant t = Instant.parse("2026-10-28T02:00:00Z");

  @BeforeEach
  void setup() {
    RuleConfig c = new RuleConfig();
    c.setVersion("test-v1");
    rules = new ClinicalRules(c);
  }

  Observation o(String code, Double val, String unit) {
    Observation o = new Observation();
    o.parameterCode = code;
    o.value = val;
    o.unit = unit;
    o.eventTime = t;
    o.ingestTime = t;
    o.qualityStatus = "valid";
    return o;
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "排除感染|not_supported",
        "不能排除感染|possible",
        "尚不能排除感染|possible",
        "感染待排|possible",
        "感染已确诊|supported",
        "无相关记录|unknown",
        "非感染性|not_supported",
        "肺炎，排除感染|possible",
        "感染？|possible",
        "感染已确诊，感染待排|possible"
      })
  void infection(String text, String expected) {
    Observation x = o("DIAGNOSIS", null, null);
    x.text = text;
    assertEquals(expected, rules.infection(List.of(x)));
  }

  @Test
  void emptyIsUnknown() {
    Assessment a = rules.assess(List.of(), t);
    assertNull(a.ruleScore);
    assertEquals("unknown", a.infectionVerdict);
    assertEquals("unable_to_assess", a.screeningStatus);
    assertEquals(6, a.missingFeatures.size()); // MAP SBP RR GCS LACTATE INFECTION_EVIDENCE
  }

  @Test
  void lowMapIsNotShockDiagnosis() {
    Assessment a = rules.assess(List.of(o("MAP", 58., "mmHg")), t);
    assertEquals("hemodynamic_instability", a.screeningStatus);
    assertEquals("unable_to_assess", a.shockScreeningStatus);
    assertEquals("unknown", a.otherCausesExcluded);
  }

  @Test
  void mapBoundary() {
    assertEquals(
        "unable_to_assess", rules.assess(List.of(o("MAP", 65., "mmHg")), t).screeningStatus);
    assertEquals(
        "hemodynamic_instability",
        rules.assess(List.of(o("MAP", 64.9, "mmHg")), t).screeningStatus);
  }

  @Test
  void vasopressorAloneIsNotShock() {
    Assessment a = rules.assess(List.of(o("VASOPRESSOR_ACTIVE", 1., "boolean")), t);
    assertEquals("unable_to_assess", a.shockScreeningStatus);
  }

  @Test
  void futureNeverIncluded() {
    Observation x = o("MAP", 55., "mmHg");
    x.eventTime = t.plusSeconds(1);
    assertFalse(rules.valid(x, t));
    x.eventTime = t;
    x.ingestTime = t.plusSeconds(1);
    assertFalse(rules.valid(x, t));
  }

  @Test
  void staleBoundary() {
    Observation x = o("MAP", 55., "mmHg");
    x.eventTime = t.minusSeconds(7200);
    assertTrue(rules.valid(x, t));
    x.eventTime = t.minusSeconds(7201);
    assertFalse(rules.valid(x, t));
  }

  @Test
  void invalidUnit() {
    assertFalse(rules.valid(o("MAP", 55., "kPa"), t));
  }

  @Test
  void invalidValues() {
    assertFalse(rules.valid(o("MAP", Double.NaN, "mmHg"), t));
    assertFalse(rules.valid(o("MAP", -1., "mmHg"), t));
    assertFalse(rules.valid(o("GCS", 14.5, "score"), t));
  }

  @Test
  void missingSofaNeverNormal() {
    Assessment a = rules.assess(List.of(), t);
    assertEquals("not_implemented", a.sofaStatus);
  }

  @Test
  void qsofaSeparateFromProbability() {
    Assessment a =
        rules.assess(
            List.of(o("SBP", 100., "mmHg"), o("RR", 22., "/min"), o("GCS", 14., "score")), t);
    assertEquals(3, a.ruleScore);
    assertTrue(a.predictions.isEmpty());
  }

  @Test
  void modelMissingCannotYieldProbability() {
    for (Prediction p : new UnavailableRiskModelProvider().predict(List.of())) {
      assertEquals("unavailable", p.predictionSource);
      assertNull(p.riskValue);
      assertNull(p.riskValueType);
      assertEquals("weight_missing", p.modelStatus);
    }
  }

  @Test
  void completionBoundaryAndSupplement() {
    Instant due = t.plusSeconds(3600);
    assertEquals("met", rules.executionStatus(due, t, due));
    assertEquals("met_late", rules.executionStatus(due.plusSeconds(1), t, due));
    assertEquals("completed_before", rules.executionStatus(t.minusSeconds(1), t, due));
  }

  @Test
  void reviewPendingExcluded() {
    Task task = new Task();
    task.applicability = "required";
    assertFalse(rules.eligible(task));
    task.clinicalReviewStatus = "confirmed";
    assertTrue(rules.eligible(task));
    task.applicability = "not_applicable";
    assertFalse(rules.eligible(task));
  }
}
