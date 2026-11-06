package org.icu.sepsis.notes;
import java.util.*;import org.icu.sepsis.application.Store;import org.icu.sepsis.domain.Domain.Patient;
import org.springframework.beans.factory.ObjectProvider;import org.springframework.scheduling.annotation.Scheduled;import org.springframework.stereotype.Component;
import org.springframework.data.mongodb.core.query.*;
@Component
public class ClinicalNoteSyncJob {
  private final Store store;private final ClinicalNoteService notes;private final ClinicalNoteProperties props;private final ClinicalNoteCrypto crypto;private final ObjectProvider<ClinicalNoteSource> source;
  private int offset;private volatile String lastStatus="not_started";private volatile java.time.Instant lastAttemptAt,lastSuccessfulAt;private volatile int processed;
  public Map<String,Object> status(){Map<String,Object> out=new LinkedHashMap<>();out.put("status",props.syncEnabled?lastStatus:"disabled");out.put("lastAttemptAt",lastAttemptAt);out.put("lastSuccessfulAt",lastSuccessfulAt);out.put("processed",processed);return out;}
  public ClinicalNoteSyncJob(Store store,ClinicalNoteService notes,ClinicalNoteProperties props,ClinicalNoteCrypto crypto,ObjectProvider<ClinicalNoteSource> source){this.store=store;this.notes=notes;this.props=props;this.crypto=crypto;this.source=source;}
  @Scheduled(initialDelayString="${app.clinical-notes.initial-delay-ms:180000}",fixedDelayString="${app.clinical-notes.scan-ms:900000}")
  public synchronized void scan(){if(!props.syncEnabled){lastStatus="disabled";return;}if(!crypto.ready()){lastStatus="key_unavailable";return;}ClinicalNoteSource adapter=source.getIfAvailable();if(adapter==null){lastStatus="not_configured";return;}lastAttemptAt=java.time.Instant.now();processed=0;
    try{Set<String> active=adapter.activePatientIds(store.department);if(active==null){lastStatus="read_failed";return;}if(active.isEmpty()){lastStatus="no_active_patients";lastSuccessfulAt=java.time.Instant.now();return;}
      List<Patient> patients=store.db.find(Query.query(Criteria.where("department").is(store.department).and("_id").in(active)),Patient.class);
      patients.sort(Comparator.comparing(p->p.id));if(patients.isEmpty()){lastStatus="binding_unconfirmed";return;}int count=Math.min(patients.size(),Math.max(1,props.maxSyncPatients));
      boolean incomplete=false;for(int i=0;i<count;i++){ClinicalNoteSyncState state=notes.syncPatient(patients.get((offset+i)%patients.size()));processed++;if(state!=null&&!Set.of("synced","no_records").contains(state.status))incomplete=true;}offset=(offset+count)%patients.size();lastStatus=incomplete?"partial":count<patients.size()?"scan_limited":"synced";lastSuccessfulAt=java.time.Instant.now();
    }catch(RuntimeException ignored){lastStatus="read_failed";/* Never log hospital credentials, responses or note text; independent clinical rules remain available. */}}
}
