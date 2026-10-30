package org.icu.sepsis;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.icu.sepsis.config.RuleConfig;
import org.icu.sepsis.domain.*;
import org.icu.sepsis.domain.Domain.*;
import org.icu.sepsis.sepsis.*;
import org.junit.jupiter.api.*;

/** Regression tests for the 2026-10 code review (channel hypotension, negation, conflicts, lactate reports). */
class ReviewFixesTest {
  final Instant t = Instant.parse("2026-10-28T02:00:00Z");
  ClinicalRules rules;

  @BeforeEach
  void setup() {
    RuleConfig c = new RuleConfig();
    c.setVersion("test");
    rules = new ClinicalRules(c);
  }

  Observation o(String code, Double v, String unit) {
    Observation o = new Observation();
    o.parameterCode = code; o.value = v; o.unit = unit; o.eventTime = t; o.ingestTime = t; o.qualityStatus = "valid";
    return o;
  }

  @Test
  void channelMapHypotensionTriggersInstability() {
    Assessment a = rules.assess(List.of(o("MAP_IBP", 58., "mmHg"), o("MAP_NIBP", 72., "mmHg")), t);
    assertEquals("hemodynamic_instability", a.screeningStatus);
    assertTrue(a.conflictingEvidence.contains("PRESSURE_CHANNEL_SELECTION_REQUIRED"));
  }

  @Test
  void channelSbpHypotensionTriggersInstability() {
    assertEquals("hemodynamic_instability", rules.assess(List.of(o("SBP_NIBP", 82., "mmHg")), t).screeningStatus);
  }

  @Test
  void normalChannelsDoNotTrigger() {
    assertNotEquals("hemodynamic_instability",
        rules.assess(List.of(o("MAP_IBP", 75., "mmHg"), o("SBP_IBP", 120., "mmHg")), t).screeningStatus);
  }

  @Test
  void sameTimeConflictAtNewestPointIsNotReplacedByOlderValue() {
    Observation older = o("MAP", 58., "mmHg"); older.eventTime = t.minusSeconds(600); older.ingestTime = older.eventTime;
    Assessment a = rules.assess(List.of(older, o("MAP", 80., "mmHg"), o("MAP", 60., "mmHg")), t);
    assertTrue(a.conflictingEvidence.contains("SAME_TIME_CONFLICT:MAP"));
    assertNotEquals("hemodynamic_instability", a.screeningStatus);
  }

  @Test
  void staleOrFutureLactateIsMissingButUnconfirmedReportIsNot() {
    Observation stale = o("LACTATE", 3., "mmol/L"); stale.eventTime = t.minus(Duration.ofHours(30));
    assertTrue(rules.assess(List.of(stale), t).missingFeatures.contains("LACTATE"));
    Observation report = o("LACTATE", 3., "mmol/L"); report.clinicalUse = "context_only";
    assertFalse(rules.assess(List.of(report), t).missingFeatures.contains("LACTATE"));
    Observation future = o("LACTATE", 3., "mmol/L"); future.clinicalUse = "context_only"; future.eventTime = t.plusSeconds(60);
    assertTrue(rules.assess(List.of(future), t).missingFeatures.contains("LACTATE"));
  }

  String verdict(String text) {
    Observation x = o("DIAGNOSIS", null, null); x.text = text;
    return rules.infection(List.of(x));
  }

  @Test
  void negatedSpecificInfectionIsNotSupported() {
    assertEquals("not_supported", verdict("排除肺炎"));
    assertEquals("not_supported", verdict("排除脓毒症"));
    assertEquals("not_supported", verdict("无感染征象"));
    assertEquals("not_supported", verdict("无菌性脑膜炎"));
    assertEquals("supported", verdict("重症肺炎"));
    assertEquals("supported", verdict("无明显诱因出现肺炎"));
    assertEquals("possible", verdict("肺部感染"));
  }

  @Test
  void sharedTextReaderIsConsistent() {
    for (String negative : List.of("排除感染", "非感染性发热", "无感染证据", "既往肺炎", "肺炎已排除", "无菌性脑膜炎")) {
      assertFalse(SepsisClinicalRules.infectionText(negative), negative);
      assertFalse(Icu05Rules.infectionDiagnosis(negative), negative);
    }
    for (String positive : List.of("肺部感染", "感染性休克", "不能排除感染", "脓毒症|肺炎", "感染性休克，排除心源性休克")) {
      assertTrue(SepsisClinicalRules.infectionText(positive), positive);
      assertTrue(Icu05Rules.infectionDiagnosis(positive), positive);
    }
  }
}
