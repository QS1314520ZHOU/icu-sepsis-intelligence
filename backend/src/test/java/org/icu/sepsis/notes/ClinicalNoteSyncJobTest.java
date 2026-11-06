package org.icu.sepsis.notes;
import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;
import java.util.*;import org.junit.jupiter.api.Test;import org.springframework.beans.factory.ObjectProvider;import org.springframework.data.mongodb.core.MongoTemplate;import org.springframework.data.mongodb.core.query.Query;
import org.icu.sepsis.domain.Domain.Patient;import org.icu.sepsis.application.Store;import com.fasterxml.jackson.databind.ObjectMapper;
class ClinicalNoteSyncJobTest {
 @Test void periodicSyncDoesNotDependOnOpeningPatientPageAndRotatesCappedRoster(){var db=mock(MongoTemplate.class);var notes=mock(ClinicalNoteService.class);var source=mock(ClinicalNoteSource.class);ObjectProvider<ClinicalNoteSource> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(source);
  var props=ClinicalNoteCryptoTest.properties();props.maxSyncPatients=1;when(source.activePatientIds("ICU")).thenReturn(Set.of("1","2","3"));List<Patient> patients=new ArrayList<>();for(String id:List.of("3","2","1")){Patient p=new Patient();p.id=id;p.encounterId="e"+id;p.department="ICU";patients.add(p);}when(db.find(any(Query.class),eq(Patient.class))).thenReturn(patients);
  var job=new ClinicalNoteSyncJob(new Store(db,"ICU",new ObjectMapper()),notes,props,new ClinicalNoteCrypto(props,new ObjectMapper()),provider);job.scan();job.scan();job.scan();for(Patient p:patients)verify(notes).syncPatient(p);
  props.syncEnabled=false;job.scan();verifyNoMoreInteractions(notes);
 }
}
