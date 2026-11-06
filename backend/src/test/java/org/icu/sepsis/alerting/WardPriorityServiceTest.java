package org.icu.sepsis.alerting;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.*;import java.util.*;
import org.junit.jupiter.api.Test;
import org.icu.sepsis.domain.Domain.Alert;
import org.icu.sepsis.application.Store;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import com.fasterxml.jackson.databind.ObjectMapper;
class WardPriorityServiceTest {
  static Alert alert(String id,String patient,String severity){Alert a=new Alert();a.id=id;a.patientId=patient;a.encounterId="e"+patient;a.severity=severity;a.status="pending";a.createdAt=Instant.parse("2026-11-01T00:00:00Z");a.trigger="合成证据";return a;}
  @Test void groupsAllAlertsBeforeRankingAndOldCriticalIsNotLost(){List<Alert> alerts=new ArrayList<>();
    for(int i=0;i<40;i++)alerts.add(alert("new"+i,"same","high"));Alert old=alert("old","critical-patient","critical");old.createdAt=Instant.EPOCH;alerts.add(old);
    var result=WardPriorityService.aggregate(alerts);assertEquals(2,result.size());assertEquals("critical-patient",result.get(0).patientId);assertEquals(40,result.get(1).alertCount);assertEquals(1,result.get(1).reasons.size());}
  @Test void unknownSeverityIsInsufficientAndSeparateEncountersAreNotMerged(){Alert a=alert("a","p",null),b=alert("b","p","low");b.encounterId="other";
    var result=WardPriorityService.aggregate(List.of(a,b));assertEquals(2,result.size());assertEquals("持续观察",result.get(0).category);assertEquals("信息不足",result.get(1).category);}
  @Test void reReviewComesFirstWithinSeverityAndInputOrderDoesNotMatter(){Alert a=alert("a","a","high"),b=alert("b","b","high");b.needsReReview=true;
    assertEquals("b",WardPriorityService.aggregate(List.of(a,b)).get(0).patientId);assertEquals("b",WardPriorityService.aggregate(List.of(b,a)).get(0).patientId);
    a.status="rejected";assertEquals(1,WardPriorityService.aggregate(List.of(a,b)).size());}
  @Test void queryUsesWholeDepartmentAndHasNoLimit(){MongoTemplate db=mock(MongoTemplate.class);when(db.find(any(Query.class),eq(Alert.class))).thenReturn(List.of());
    new WardPriorityService(new Store(db,"ICU",new ObjectMapper()),Clock.systemUTC()).snapshot();var query=org.mockito.ArgumentCaptor.forClass(Query.class);verify(db).find(query.capture(),eq(Alert.class));
    assertEquals(0,query.getValue().getLimit());assertEquals("ICU",query.getValue().getQueryObject().get("department"));}
}
