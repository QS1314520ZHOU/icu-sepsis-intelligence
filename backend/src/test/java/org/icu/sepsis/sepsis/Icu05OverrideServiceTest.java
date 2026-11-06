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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
class Icu05OverrideServiceTest {
  MongoTemplate db;Store store;Icu05Service engine;Icu05OverrideService service;Patient p;Map<String,Object> current,automatic;
  final Instant now=Icu05RulesTest.at(1440);
  @BeforeEach void setup(){
    db=mock(MongoTemplate.class);store=spy(new Store(db,"ICU",new ObjectMapper().findAndRegisterModules()));engine=mock(Icu05Service.class);
    service=new Icu05OverrideService(store,engine,Clock.fixed(now,ZoneOffset.UTC),mock(ApplicationEventPublisher.class));
    p=new Patient();p.id="p";p.encounterId="e";p.department="ICU";when(db.findById("p",Patient.class)).thenReturn(p);
    automatic=Icu05Rules.evaluate(Icu05RulesTest.full());automatic.put("available",true);
    current=new LinkedHashMap<>(automatic);current.put("automaticResult",automatic);current.put("automaticFingerprint","fingerprint");
    when(engine.evaluate(p,now)).thenReturn(current);
    when(db.insert(any(Icu05Override.class))).thenAnswer(i->{Icu05Override r=i.getArgument(0);r.version=0L;return r;});
  }
  Requests.Icu05OverrideRequest request(){Requests.Icu05OverrideRequest r=new Requests.Icu05OverrideRequest();r.scope="judgement";r.windowKey="1h";r.targetId="A1";r.judgementValue=false;r.expectedFingerprint="fingerprint";r.reasonCode="data_error";r.reasonText="合成判定项复核";return r;}
  @Test void savesOriginalAndManualValuesThenRevokesWithoutDeletingHistory(){
    Icu05Override r=service.create("p",request(),"create-key","doctor");assertEquals(true,r.autoValue);assertEquals(false,r.manualValue);assertEquals(automatic,r.automaticSnapshot);
    when(db.findById(r.id,Icu05Override.class)).thenReturn(r);Requests.Icu05Revoke rev=new Requests.Icu05Revoke();rev.expectedVersion=0L;rev.reasonText="合成撤销";
    service.revoke("p",r.id,rev,"revoke-key","quality");assertEquals(now,r.revokedAt);assertNull(r.activeKey);assertEquals("quality",r.revokedBy);
    verify(store).record(eq(r),eq("icu05_override"),eq("created"),eq("doctor"),anyString(),anyMap(),eq(now));
    verify(store).record(eq(r),eq("icu05_override"),eq("revoked"),eq("quality"),anyString(),anyMap(),eq(now));
    verify(db,never()).remove(any(Query.class),eq(Icu05Override.class));
  }
  @Test void rejectsK1PendingWindowEvenWhenManualValueIsTrue(){
    Requests.Icu05OverrideRequest r=request();r.windowKey="gate";r.targetId="K1";r.judgementValue=true;
    Icu05OverrideService.target(automatic,"gate","K1").put("windowConfirmed",false);
    ApiException e=assertThrows(ApiException.class,()->service.create("p",r,"k1-key-test","doctor"));assertTrue(e.getMessage().contains("窗口待确认"));verify(db,never()).insert(any(Icu05Override.class));
  }
  @Test void validatesLayerValuesAndRejectsProvisionalItem(){
    Requests.Icu05OverrideRequest r=request();r.targetId="A2";assertThrows(ApiException.class,()->Icu05OverrideService.validate(r));
    r.scope="sofa_component";r.windowKey="classic";r.targetId="respiratory";r.judgementValue=null;r.componentScore=5;
    assertThrows(ApiException.class,()->Icu05OverrideService.validate(r));r.componentScore=0;r.reasonCode="chronic_baseline";assertDoesNotThrow(()->Icu05OverrideService.validate(r));
    Icu05Override record=service.create("p",r,"sofa-key","doctor");assertNull(record.autoValue);assertEquals(0,record.manualValue);
  }
  @Test void refusesStaleEvidenceDuplicateActiveAndCrossEncounterRevocation(){
    Requests.Icu05OverrideRequest r=request();r.expectedFingerprint="old";assertThrows(ApiException.class,()->service.create("p",r,"old-key","doctor"));
    r.expectedFingerprint="fingerprint";when(db.findOne(any(Query.class),eq(Icu05Override.class))).thenReturn(new Icu05Override());
    assertThrows(ApiException.class,()->service.create("p",r,"duplicate-key","doctor"));
    Icu05Override other=new Icu05Override();other.id="r";other.patientId="p";other.encounterId="other";other.department="ICU";
    when(db.findById("r",Icu05Override.class)).thenReturn(other);Requests.Icu05Revoke rev=new Requests.Icu05Revoke();rev.expectedVersion=0L;rev.reasonText="synthetic";
    assertThrows(ApiException.class,()->service.revoke("p","r",rev,"cross-key","doctor"));verify(db,never()).save(any(Icu05Override.class));
  }
  @Test void repeatedCommandReturnsSameRecordWithoutNewMutation(){
    Icu05Override stored=new Icu05Override();stored.id="r";stored.patientId="p";stored.encounterId="e";stored.department="ICU";
    Command replay=new Command();replay.entityId="r";doReturn(replay).when(store).replay(eq("doctor"),eq("same-key"),anyString());when(db.findById("r",Icu05Override.class)).thenReturn(stored);
    assertSame(stored,service.create("p",request(),"same-key","doctor"));verify(db,never()).insert(any(Icu05Override.class));
  }
}
