package org.icu.sepsis.alerting;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.icu.sepsis.application.Store;
import org.icu.sepsis.domain.Domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class ScreeningAlertServiceTest {
  static Map<String, Object> f(String code, String label, double v) {
    Map<String, Object> m = new LinkedHashMap<>(); m.put("code", code); m.put("label", label); m.put("value", v); return m;
  }
  static Map<String, Object> review(Boolean vaso, String clue, Map<String, Object>... findings) {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("available", true); r.put("vasopressorActive", vaso); r.put("infectionClue", clue); r.put("findings", List.of(findings));
    return r;
  }
  static final String CLUE = "诊断文本含感染线索，需核对时效及临床依据", NO = "未取得可确认的感染证据";

  @Test void lactateAndLowMapIsShock() {
    List<ScreeningAlertService.Hit> h = ScreeningAlertService.fromReview(review(null, NO,
        f("LACTATE", "乳酸", 3.1), f("MAP_IBP", "平均动脉压（有创）", 58), f("MAP_NIBP", "平均动脉压（无创）", 62)));
    assertEquals(1, h.size());
    assertEquals("critical", h.get(0).severity);
    assertTrue(h.get(0).reason.contains("乳酸 3.1") && h.get(0).reason.contains("58 mmHg"), h.get(0).reason);
  }
  @Test void vasopressorAloneIsShock() {
    assertEquals(ScreeningAlertService.SHOCK, ScreeningAlertService.fromReview(review(true, NO)).get(0).kind);
  }
  @Test void lactateWithoutHypotensionIsNotShock() {
    assertTrue(ScreeningAlertService.fromReview(review(false, NO, f("LACTATE", "乳酸", 3.1))).isEmpty());
  }
  @Test void infectionPlusAbnormality() {
    List<ScreeningAlertService.Hit> h = ScreeningAlertService.fromReview(review(false, CLUE, f("RR", "呼吸频率", 26)));
    assertEquals(ScreeningAlertService.INFECTION, h.get(0).kind);
    assertEquals("high", h.get(0).severity);
    assertTrue(ScreeningAlertService.fromReview(review(false, CLUE)).isEmpty(), "infection without abnormality");
  }
  @Test void unavailableReviewNeverAlerts() {
    Map<String, Object> r = review(true, CLUE, f("RR", "呼吸频率", 26)); r.put("available", false);
    assertTrue(ScreeningAlertService.fromReview(r).isEmpty());
  }
  @Test void icu05Gate() {
    Map<String, Object> gate = new LinkedHashMap<>(); gate.put("status", "suspected_review"); gate.put("statusLabel", "疑似，待复核感染证据"); gate.put("mapMin", 61.0);
    Map<String, Object> b = new LinkedHashMap<>(); b.put("available", true); b.put("gate", gate);
    assertEquals("critical", ScreeningAlertService.fromIcu05(b).get(0).severity);
    gate.put("status", "not_met"); gate.put("hasInfection", true); gate.put("hasOrganDysfunction", true);
    assertEquals(ScreeningAlertService.INFECTION, ScreeningAlertService.fromIcu05(b).get(0).kind);
    gate.put("hasOrganDysfunction", null);
    assertTrue(ScreeningAlertService.fromIcu05(b).isEmpty());
  }

  // ---- persistence / dedupe
  final MongoTemplate db = mock(MongoTemplate.class);
  final Store store = new Store(db, "ICU", new ObjectMapper());
  final Instant now = Instant.parse("2026-10-30T08:00:00Z");
  final ScreeningAlertService svc = new ScreeningAlertService(store, null, null, Clock.fixed(now, ZoneOffset.UTC), true, 30, 6, 60);
  Patient patient() { Patient p = new Patient(); p.id = "p1"; p.encounterId = "e1"; p.department = "ICU"; p.label = "张三"; p.bed = "03"; return p; }
  List<ScreeningAlertService.Hit> shock() { return ScreeningAlertService.fromReview(review(true, CLUE, f("RR", "呼吸频率", 26))); }

  @Test void createsOneAlertAndShockSupersedesInfection() {
    assertEquals(1, svc.raise(patient(), shock()));
    verify(db).insert(argThat((Object o) -> o instanceof Alert && "critical".equals(((Alert) o).severity)
        && "pending".equals(((Alert) o).status) && "03".equals(((Alert) o).bed)));
  }
  @Test void openAlertIsNotDuplicated() {
    Alert open = new Alert(); open.status = "pending";
    when(db.findOne(any(Query.class), eq(Alert.class))).thenReturn(open);
    assertEquals(0, svc.raise(patient(), shock()));
    verify(db, never()).insert(any(Alert.class));
    assertEquals(now, open.lastSeenAt);
  }
  @Test void recentlyRejectedIsSuppressedThenReraised() {
    Alert last = new Alert(); last.status = "rejected"; last.closedAt = now.minus(Duration.ofHours(2));
    when(db.findOne(any(Query.class), eq(Alert.class))).thenReturn(null, last);
    assertEquals(0, svc.raise(patient(), shock()));
    last.closedAt = now.minus(Duration.ofHours(7));
    when(db.findOne(any(Query.class), eq(Alert.class))).thenReturn(null, last);
    assertEquals(1, svc.raise(patient(), shock()));
  }

  // ---- sweep scope / incremental
  @SuppressWarnings("unchecked")
  @Test void sweepOnlyInWardAndSkipsUnchanged() {
    org.icu.sepsis.sepsis.SepsisAssessmentService sepsis = mock(org.icu.sepsis.sepsis.SepsisAssessmentService.class);
    org.icu.sepsis.sepsis.Icu05Service icu05 = mock(org.icu.sepsis.sepsis.Icu05Service.class);
    org.icu.sepsis.integration.SmartCareSourceReader reader = mock(org.icu.sepsis.integration.SmartCareSourceReader.class);
    Patient a = patient(); a.admittedAt = now.minus(Duration.ofDays(1));
    Patient gone = patient(); gone.id = "p2"; gone.admittedAt = now.minus(Duration.ofDays(2));
    when(db.find(any(Query.class), eq(Patient.class))).thenReturn(List.of(a, gone));
    when(reader.admittedAmong(anyCollection())).thenReturn(Set.of("p1"));
    Map<String, Object> r = review(null, NO);
    r.put("latestEvidence", List.of(Map.of("code", "MAP", "eventTime", now, "value", 70)));
    when(sepsis.assess(any(), any())).thenReturn(r);
    when(icu05.evaluate(any(), any())).thenReturn(Map.of("available", false));
    ScreeningAlertService s = new ScreeningAlertService(store, sepsis, icu05, Clock.fixed(now, ZoneOffset.UTC), true, 30, 6, 60);
    org.springframework.beans.factory.ObjectProvider<org.icu.sepsis.integration.SmartCareSourceReader> prov = mock(org.springframework.beans.factory.ObjectProvider.class);
    when(prov.getIfAvailable()).thenReturn(reader);
    s.setSource(prov);
    s.scan();
    s.scan();
    verify(sepsis, times(2)).assess(eq(a), any());
    verify(sepsis, never()).assess(eq(gone), any());
    verify(icu05, times(1)).evaluate(any(), any()); // second sweep: data unchanged within full-eval window
    s.reviewChanged(new org.icu.sepsis.sepsis.Icu05CaseReviewService.Changed(a));
    s.scan();
    verify(icu05, times(2)).evaluate(any(), any());
  }

  @Test void inWardFilterFailureScansAll() {
    org.icu.sepsis.integration.SmartCareSourceReader reader = mock(org.icu.sepsis.integration.SmartCareSourceReader.class);
    when(reader.admittedAmong(anyCollection())).thenThrow(new RuntimeException("down"));
    @SuppressWarnings("unchecked")
    org.springframework.beans.factory.ObjectProvider<org.icu.sepsis.integration.SmartCareSourceReader> prov = mock(org.springframework.beans.factory.ObjectProvider.class);
    when(prov.getIfAvailable()).thenReturn(reader);
    svc.setSource(prov);
    assertEquals(1, svc.filterInWard(List.of(patient())).size());
  }

  @Test void unknownK1MayWarnIndependentlyWithoutClaimingCandidateOrDiagnosis() {
    Map<String,Object> gate=new HashMap<>();gate.put("status","unknown");gate.put("statusLabel","乳酸取值窗口待确认");gate.put("suspectedWarning",true);
    List<ScreeningAlertService.Hit> hits=ScreeningAlertService.fromIcu05(Map.of("available",true,"gate",gate));
    assertEquals(1,hits.size());assertTrue(hits.get(0).reason.contains("K1 且 K2"));
    assertTrue(hits.get(0).reason.contains("需人工复核")); assertFalse(hits.get(0).reason.contains("MAP<65"));
    assertEquals("unknown",gate.get("status"));
  }
  @Test @SuppressWarnings("unchecked") void aiExplanationIsOnDemandUnlessExplicitlyEnabled() {
    org.icu.sepsis.ai.AiAssistService ai=mock(org.icu.sepsis.ai.AiAssistService.class);
    org.springframework.beans.factory.ObjectProvider<org.icu.sepsis.ai.AiAssistService> provider=mock(org.springframework.beans.factory.ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(ai); svc.setAi(provider);
    assertEquals(1,svc.raise(patient(),shock()));verify(ai,never()).explainAlertAsync(any());
    svc.autoAiExplanation=true;assertEquals(1,svc.raise(patient(),shock()));verify(ai).explainAlertAsync(any());
  }

  @Test void sameEvidenceNeverReopensReviewButChangedEvidencePreservesReviewerReason() {
    Alert open=new Alert();open.id="open";open.status="confirmed";open.reason="既往人工处置意见";open.evidenceFingerprint=shock().get(0).evidenceFingerprint;
    when(db.findOne(any(Query.class),eq(Alert.class))).thenReturn(open);
    svc.raise(patient(),shock());assertFalse(open.needsReReview);
    List<ScreeningAlertService.Hit> changed=ScreeningAlertService.fromReview(review(true,CLUE,f("LACTATE","乳酸",4),f("MAP","平均动脉压",60)));
    svc.raise(patient(),changed);assertTrue(open.needsReReview);assertEquals("既往人工处置意见",open.reason);assertEquals(now,open.evidenceChangedAt);
    verify(db).insert(argThat((Object o)->o instanceof History&&"evidence_changed".equals(((History)o).action)));
    svc.raise(patient(),changed);verify(db,times(1)).insert(argThat((Object o)->o instanceof History&&"evidence_changed".equals(((History)o).action)));
  }
  @Test void newEvidenceBypassesRejectionHoldAndLinksOriginalWithoutChangingIt() {
    Alert old=new Alert();old.id="old";old.status="rejected";old.reviewChainId="root";old.closedAt=now.minusSeconds(60);old.evidenceFingerprint="older";
    when(db.findOne(any(Query.class),eq(Alert.class))).thenReturn(null,old);
    assertEquals(1,svc.raise(patient(),shock()));assertEquals("rejected",old.status);
    verify(db).insert(argThat((Object o)->o instanceof Alert&&"old".equals(((Alert)o).previousAlertId)&&"root".equals(((Alert)o).reviewChainId)&&((Alert)o).needsReReview));
  }
  @Test void evidenceOrderAndRefreshTimeDoNotTriggerReReview() {
    Map<String,Object> a=review(true,CLUE,f("MAP","MAP",60),f("LACTATE","乳酸",3));
    Map<String,Object> b=review(true,CLUE,f("MAP","MAP",60),f("LACTATE","乳酸",3));
    a.put("asOf",now);b.put("asOf",now.plusSeconds(60));
    assertEquals(ScreeningAlertService.fromReview(a).get(0).evidenceFingerprint,ScreeningAlertService.fromReview(b).get(0).evidenceFingerprint);
    assertEquals(ScreeningAlertService.signature("same",List.of(Map.of("code","MAP"),Map.of("code","LACTATE"))),ScreeningAlertService.signature("same",List.of(Map.of("code","LACTATE"),Map.of("code","MAP"))));
  }
 @Test void mewsTriggersReviewWithoutRequiringInfectionAndFingerprintTracksActualEvidence(){var r=review(false,NO);Map<String,Object> channel=new LinkedHashMap<>(Map.of("triggered",true,"label","无创血压","total",5,"components",List.of(Map.of("sourceRecordId","synthetic-a","eventTime","time-a"))));var ew=new LinkedHashMap<String,Object>(Map.of("triggered",true,"channels",List.of(channel),"ruleVersion","synthetic","freshMinutes",30,"maxSkewMinutes",15,"reviewThreshold",5,"population","adult"));r.put("earlyWarning",ew);var hits=ScreeningAlertService.fromReview(r);assertEquals(1,hits.size());assertEquals(ScreeningAlertService.EARLY_WARNING,hits.get(0).kind);assertFalse(hits.get(0).reason.contains("确诊感染"));String before=ScreeningAlertService.fingerprint(r), evidence=hits.get(0).evidenceFingerprint;ew.put("asOf","changed-evaluation-time-only");channel.put("components",List.of(Map.of("sourceRecordId","synthetic-a","eventTime","time-a","ageSeconds",100,"carriedForward",true)));assertEquals(before,ScreeningAlertService.fingerprint(r));assertEquals(evidence,ScreeningAlertService.fromReview(r).get(0).evidenceFingerprint);channel.put("components",List.of(Map.of("sourceRecordId","synthetic-b","eventTime","time-b")));assertNotEquals(before,ScreeningAlertService.fingerprint(r));assertNotEquals(evidence,ScreeningAlertService.fromReview(r).get(0).evidenceFingerprint);ew.put("triggered",null);assertTrue(ScreeningAlertService.fromReview(r).isEmpty());}
}
