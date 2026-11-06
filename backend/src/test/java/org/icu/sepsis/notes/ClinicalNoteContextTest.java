package org.icu.sepsis.notes;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;
import org.junit.jupiter.api.Test;import org.springframework.context.annotation.*;import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.data.mongodb.core.MongoTemplate;import org.springframework.data.mongodb.core.query.Query;import java.time.Clock;import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;import org.icu.sepsis.application.Store;import org.icu.sepsis.domain.Domain.Patient;
class ClinicalNoteContextTest {
 @Configuration static class Properties {@Bean static PropertySourcesPlaceholderConfigurer placeholders(){return new PropertySourcesPlaceholderConfigurer();}}
 @Test void defaultDisabledFeatureStartsWithNoKeyOrAdapterAndNoServiceCycle(){try(var context=new AnnotationConfigApplicationContext()){
   var db=mock(MongoTemplate.class);Patient patient=new Patient();patient.id="p";patient.patientId="p";patient.department="ICU";patient.encounterId="e";
   when(db.findById("p",Patient.class)).thenReturn(patient);when(db.find(any(Query.class),eq(ClinicalNoteSnapshot.class))).thenReturn(List.of());
   context.registerBean(Store.class,()->new Store(db,"ICU",new ObjectMapper()));context.registerBean(Clock.class,Clock::systemUTC);context.registerBean(ObjectMapper.class,()->new ObjectMapper());
   context.register(Properties.class,ClinicalNoteProperties.class,ClinicalNoteCrypto.class,ClinicalNoteService.class,ClinicalNoteSyncJob.class);context.refresh();
   assertFalse(context.getBean(ClinicalNoteCrypto.class).ready());var result=context.getBean(ClinicalNoteService.class).list("p",0,20,"doctor");
   assertEquals("disabled",result.get("status"));assertEquals("disabled",((Map<?,?>)result.get("backgroundSync")).get("status"));
 }}

 @Test void configuredKeyRingResolvesWithoutLeakingOrRequiringAdapter(){try(var context=new AnnotationConfigApplicationContext()){
   var sample=ClinicalNoteCryptoTest.properties();context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("synthetic",Map.of("app.clinical-notes.keys-json",sample.keysJson,"app.clinical-notes.active-key-id",sample.activeKeyId,"app.clinical-notes.fingerprint-key-id",sample.fingerprintKeyId)));
   context.registerBean(ObjectMapper.class,()->new ObjectMapper());context.register(Properties.class,ClinicalNoteProperties.class,ClinicalNoteCrypto.class);context.refresh();assertTrue(context.getBean(ClinicalNoteCrypto.class).ready());
 }}
}
