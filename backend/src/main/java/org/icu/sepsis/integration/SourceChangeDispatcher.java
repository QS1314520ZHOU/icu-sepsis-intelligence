package org.icu.sepsis.integration;

import java.time.*;
import java.util.*;
import org.icu.sepsis.application.Store;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/** Internal adapter contract, deliberately not an HTTP endpoint or an assumed hospital event feed. */
@Service
public class SourceChangeDispatcher {
 public static final Set<String> TYPES=Set.of("orders","medication_execution","fluid_execution","laboratory","vital_signs","clinical_notes");
 public static final class Change {
  public final String patientId,encounterId,department,type,eventId,source;public final Instant sourceTime,receivedAt;
  public Change(String patientId,String encounterId,String department,String type,String eventId,String source,Instant sourceTime,Instant receivedAt){this.patientId=patientId;this.encounterId=encounterId;this.department=department;this.type=type;this.eventId=eventId;this.source=source;this.sourceTime=sourceTime;this.receivedAt=receivedAt;}
 }
 private final Store store;private final ApplicationEventPublisher publisher;private final Clock clock;
 private final Map<List<String>,Change> delivered=new LinkedHashMap<>();
 public SourceChangeDispatcher(Store store,ApplicationEventPublisher publisher,Clock clock){this.store=store;this.publisher=publisher;this.clock=clock;}
 public synchronized String dispatch(Change change){
  if(change==null||change.source==null||change.source.isBlank()||change.source.length()>128||change.eventId==null||change.eventId.isBlank()||change.eventId.length()>128||!TYPES.contains(Objects.toString(change.type,""))||change.receivedAt==null||change.receivedAt.isAfter(clock.instant())||change.sourceTime!=null&&change.sourceTime.isAfter(change.receivedAt))throw new IllegalArgumentException("来源变更通知缺少有效上下文或时间");
  // Authorisation is checked again even for duplicate notifications. No source payload is trusted as a patient binding.
  var patient=store.patient(change.patientId);
  if(!Objects.equals(patient.encounterId,change.encounterId)||!Objects.equals(patient.department,change.department))throw new IllegalArgumentException("来源变更通知的患者住院归属不一致");
  List<String> key=List.of(change.department,change.patientId,change.encounterId,change.source,change.eventId);Change prior=delivered.get(key);
  if(prior!=null){if(!Objects.equals(prior.type,change.type)||!Objects.equals(prior.sourceTime,change.sourceTime))throw new IllegalArgumentException("同一来源事件标识的内容冲突");return "duplicate";}
  // Late or unordered notifications still invalidate; they never overwrite records or advance a clinical event time.
  publisher.publishEvent(change);delivered.put(key,change);
  while(delivered.size()>1024)delivered.remove(delivered.keySet().iterator().next());return "invalidated";
 }
}
