package org.icu.sepsis.alerting;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import org.icu.sepsis.application.*;
import org.icu.sepsis.api.*;
import org.icu.sepsis.domain.Domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

class AlertReviewWorkflowTest {
  final MongoTemplate db=mock(MongoTemplate.class);
  final Store store=new Store(db,"ICU",new ObjectMapper());
  final Instant now=Instant.parse("2026-11-06T02:00:00Z");
  final WorkflowService flow=new WorkflowService(store,Clock.fixed(now,ZoneOffset.UTC),null,null);
  Alert alert(){Alert a=new Alert();a.id="alert";a.patientId="patient";a.encounterId="encounter";a.department="ICU";a.version=0L;a.status="pending";a.needsReReview=true;
    when(db.findById("alert",Alert.class)).thenReturn(a);Patient p=new Patient();p.id=a.patientId;p.department="ICU";when(db.findById("patient",Patient.class)).thenReturn(p);return a;}
  Requests.Review request(String action){Requests.Review r=new Requests.Review();r.action=action;r.reason="合成复核依据";r.expectedVersion=0L;return r;}
  @Test void rejectionDoesNotConfirmFalsePositiveEvenWhenReasonSaysSo(){Alert a=alert();Requests.Review r=request("reject");r.reason="误报：原因文字不能替代明确复核动作";
    flow.reviewAlert(a.id,r,"reject-key","doctor");assertEquals("rejected_only",a.reviewOutcome);assertEquals("rejected",a.status);assertFalse(a.needsReReview);assertEquals(now,a.reviewedAt);assertEquals("doctor",a.reviewedBy);}
  @Test void explicitFalsePositiveHasAuditedOutcomeAndNoRealTreatmentTask(){Alert a=alert();flow.reviewAlert(a.id,request("false_positive"),"false-positive-key","doctor");
    assertEquals("confirmed_false_positive",a.reviewOutcome);assertEquals(now,a.closedAt);verify(db,never()).insert(any(Task.class));
    verify(db).insert(argThat((Object o)->o instanceof History && "false_positive".equals(((History)o).action) && "confirmed_false_positive".equals(((History)o).after.get("reviewOutcome"))));}
  @Test void confirmedAlertCanBeRecheckedWithoutCreatingAnotherTask(){Alert a=alert();a.status="confirmed";a.simulated=true;
    flow.reviewAlert(a.id,request("reconfirm"),"reconfirm-key","doctor");assertEquals("confirmed",a.status);assertEquals("confirmed_screen",a.reviewOutcome);assertFalse(a.needsReReview);verify(db,never()).insert(any(Task.class));}
  @Test void closedAlertRemainsImmutableAndVersionConflictIsRejected(){Alert a=alert();a.status="rejected";
    assertThrows(ApiException.class,()->flow.reviewAlert(a.id,request("false_positive"),"closed-key","doctor"));
    a.status="pending";a.version=1L;assertThrows(ApiException.class,()->flow.reviewAlert(a.id,request("reject"),"stale-key","doctor"));verify(db,never()).save(any(Alert.class));}
}
