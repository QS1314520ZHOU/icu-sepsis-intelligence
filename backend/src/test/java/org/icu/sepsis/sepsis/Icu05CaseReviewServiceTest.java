package org.icu.sepsis.sepsis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.icu.sepsis.api.*;
import org.icu.sepsis.application.Store;
import org.icu.sepsis.domain.Domain.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class Icu05CaseReviewServiceTest {
  static final Instant NOW=Instant.parse("2026-11-06T01:00:00Z");
  MongoTemplate db; Store store; Icu05Service icu05; Icu05CaseReviewService service; Patient p; ApplicationEventPublisher events;
  Map<String,Object> automatic;
  @BeforeEach void setup() {
    db=mock(MongoTemplate.class); store=spy(new Store(db,"ICU",new ObjectMapper().findAndRegisterModules()));
    icu05=mock(Icu05Service.class); events=mock(ApplicationEventPublisher.class);
    service=new Icu05CaseReviewService(store,icu05,Clock.fixed(NOW,ZoneOffset.UTC),events);
    p=new Patient(); p.id="p"; p.encounterId="e"; p.department="ICU";
    when(db.findById("p",Patient.class)).thenReturn(p);
    when(db.insert(any(Icu05CaseReview.class))).thenAnswer(i -> { Icu05CaseReview r=i.getArgument(0);r.version=0L;return r; });
    automatic=new LinkedHashMap<>(); automatic.put("available",true); automatic.put("ruleVersion","test-rule");
    automatic.put("t0",NOW.minusSeconds(3600)); automatic.put("asOf",NOW);
    automatic.put("pool",new LinkedHashMap<>(Map.of("eligible",true))); automatic.put("status","septic_shock");
    automatic.put("windows",List.of(Map.of("key","1h","finish",true,"state","met","items",List.of(Map.of("code","A1","value",true,"sourceId","lab1")))));
    when(icu05.evaluate(p,NOW)).thenAnswer(i -> service.decorate(p,automatic));
  }
  Requests.Icu05Exclude request() {
    Requests.Icu05Exclude r=new Requests.Icu05Exclude();r.reasonCode="data_error";r.reasonText="合成病例记录核对";
    r.expectedFingerprint=(String)service.decorate(p,automatic).get("automaticFingerprint");return r;
  }
  Icu05CaseReview record() {
    Icu05CaseReview r=new Icu05CaseReview();r.id="review";r.patientId="p";r.encounterId="e";r.department="ICU";
    r.ruleVersion="test-rule";r.t0=(Instant)automatic.get("t0");r.version=0L;r.activeKey="active";return r;
  }
  @SuppressWarnings("unchecked") @Test void exclusionPreservesAutomaticSnapshotAndRevokeRestoresCurrentAutomaticResult() {
    Icu05CaseReview created=service.exclude("p",request(),"create-key","doctor");
    assertEquals(true,created.autoValue);assertFalse(created.manualValue);
    assertEquals(automatic.get("windows"),created.automaticSnapshot.get("windows"));assertEquals("doctor",created.operator);
    when(db.findOne(any(Query.class),eq(Icu05CaseReview.class))).thenReturn(created);
    Map<String,Object> applied=service.decorate(p,automatic);
    assertEquals(false,((Map<?,?>)applied.get("pool")).get("eligible"));
    assertEquals(true,((Map<?,?>)automatic.get("pool")).get("eligible"));
    assertEquals(true,((Map<?,?>)applied.get("automaticPool")).get("eligible"));
    Map<?,?> view=(Map<?,?>)((Map<?,?>)applied.get("caseReview")).get("active");
    assertFalse(view.containsKey("automaticSnapshot")); assertEquals(created.id,view.get("id"));
    when(db.findById(created.id,Icu05CaseReview.class)).thenReturn(created);
    Requests.Icu05Revoke revoke=new Requests.Icu05Revoke();revoke.expectedVersion=0L;revoke.reasonText="合成病例复核恢复";
    service.revoke("p",created.id,revoke,"revoke-key","quality");
    assertEquals(NOW,created.revokedAt);assertEquals("quality",created.revokedBy);assertNull(created.activeKey);
    when(db.findOne(any(Query.class),eq(Icu05CaseReview.class))).thenReturn(null);
    automatic.put("status","unknown"); ((Map<String,Object>)automatic.get("pool")).put("eligible",null);
    Map<String,Object> restored=service.decorate(p,automatic);
    assertNull(((Map<?,?>)restored.get("pool")).get("eligible"));assertEquals("unknown",restored.get("status"));
    verify(db,never()).remove(any(Query.class),eq(Icu05CaseReview.class));
    verify(store).record(eq(created),eq("icu05_case_review"),eq("excluded"),eq("doctor"),anyString(),anyMap(),eq(NOW));
    verify(store).record(eq(created),eq("icu05_case_review"),eq("revoked"),eq("quality"),anyString(),anyMap(),eq(NOW));
    verify(events,times(2)).publishEvent(any(Icu05CaseReviewService.Changed.class));
  }
  @Test void changedEvidenceRequiresRefreshBeforeWriting() {
    Requests.Icu05Exclude r=request();automatic.put("status","unknown");
    assertThrows(ApiException.class,()->service.exclude("p",r,"key-stale","doctor"));
    verify(db,never()).insert(any(Icu05CaseReview.class));
  }
  @Test void repeatCreateWithSameKeyReplaysWithoutNewMutation() {
    Icu05CaseReview review=record();Command replay=new Command();replay.entityId=review.id;
    doReturn(replay).when(store).replay(eq("doctor"),eq("same-key"),anyString());
    when(db.findById(review.id,Icu05CaseReview.class)).thenReturn(review);
    assertSame(review,service.exclude("p",request(),"same-key","doctor"));
    verify(db,never()).insert(any(Icu05CaseReview.class));verifyNoInteractions(events);
  }
  @Test void preventsDuplicateActiveExclusionAndInvalidReasons() {
    Requests.Icu05Exclude r=request();when(db.findOne(any(Query.class),eq(Icu05CaseReview.class))).thenReturn(record());
    assertThrows(ApiException.class,()->service.exclude("p",r,"key-duplicate","doctor"));
    r.reasonCode="arbitrary";assertThrows(ApiException.class,()->service.exclude("p",r,"key-invalid","doctor"));
    verify(db,never()).insert(any(Icu05CaseReview.class));
  }
  @Test void refusesCrossEncounterRevokeAndStaleVersion() {
    Icu05CaseReview r=record();when(db.findById(r.id,Icu05CaseReview.class)).thenReturn(r);
    Requests.Icu05Revoke request=new Requests.Icu05Revoke();request.expectedVersion=1L;request.reasonText="test";
    assertThrows(ApiException.class,()->service.revoke("p",r.id,request,"revoke-old","doctor"));
    r.encounterId="another-encounter";
    assertThrows(ApiException.class,()->service.revoke("p",r.id,request,"revoke-cross","doctor"));
    verify(db,never()).save(any(Icu05CaseReview.class));
  }
  @Test void changedRuleOrT0DoesNotApplyOldExclusion() {
    Icu05CaseReview r=record();when(db.findOne(any(Query.class),eq(Icu05CaseReview.class))).thenReturn(r);
    automatic.put("ruleVersion","new-rule");Map<String,Object> changed=service.decorate(p,automatic);
    assertEquals(true,((Map<?,?>)changed.get("caseReview")).get("requiresReview"));assertEquals(true,((Map<?,?>)changed.get("pool")).get("eligible"));
    automatic.put("ruleVersion","test-rule");automatic.put("t0",NOW);changed=service.decorate(p,automatic);
    assertEquals(false,((Map<?,?>)changed.get("caseReview")).get("applied"));
  }
  @Test void fingerprintIgnoresClockCapsButNotSourceChanges() {
    String first=(String)service.decorate(p,automatic).get("automaticFingerprint");
    automatic.put("asOf",NOW.plusSeconds(1));
    assertEquals(first,service.decorate(p,automatic).get("automaticFingerprint"));
    automatic.put("lactateRecords",List.of(Map.of("value",4,"sourceId","new-lab")));
    assertNotEquals(first,service.decorate(p,automatic).get("automaticFingerprint"));
  }
  @Test void unknownAutomaticEligibilityIsStoredAsUnknown() {
    automatic.put("status","unknown");Icu05CaseReview review=service.exclude("p",request(),"unknown-key","doctor");
    assertNull(review.autoValue);assertEquals("unknown",review.automaticSnapshot.get("status"));
  }
}
