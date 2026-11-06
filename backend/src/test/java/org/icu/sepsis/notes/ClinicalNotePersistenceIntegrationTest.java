package org.icu.sepsis.notes;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;import com.mongodb.client.MongoClients;
import java.time.*;import java.util.*;
import org.icu.sepsis.application.Store;import org.icu.sepsis.config.DatabaseSetup;import org.icu.sepsis.domain.Domain.Patient;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;import org.springframework.boot.DefaultApplicationArguments;import org.springframework.data.mongodb.core.MongoTemplate;import org.springframework.data.mongodb.core.query.*;
/** Only a dedicated loopback synthetic database. Run the methods separately around a server restart. */
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named="RUN_NOTE_MONGO_TESTS",matches="true")
class ClinicalNotePersistenceIntegrationTest {
 static final String URI="mongodb://127.0.0.1:27037/?serverSelectionTimeoutMS=5000", DATABASE="icu_sepsis_note_persistence_v3_test";
 static final String PATIENT="synthetic-note-persistence-patient";
 static ClinicalNoteSource.Batch batch(String body){var b=new ClinicalNoteSource.Batch();b.patientId=PATIENT;b.encounterId="synthetic-stay";b.department="ICU";b.source="synthetic.BLBCJL";b.bindingConfirmed=true;b.complete=true;
  var n=new ClinicalNoteSource.Note();n.patientId=PATIENT;n.encounterId=b.encounterId;n.department="ICU";n.bindingConfirmed=true;n.body=body;n.documentType="合成日常病程";n.candidateReference="synthetic-document";b.notes.add(n);return b;}
 @Test @org.junit.jupiter.api.Order(1) void storeEncryptedSnapshots()throws Exception{try(var client=MongoClients.create(URI)){
  var db=new MongoTemplate(client,DATABASE);new DatabaseSetup(db,false,"ICU").run(new DefaultApplicationArguments(new String[0]));
  Patient p=db.findById(PATIENT,Patient.class);if(p==null){p=new Patient();p.id=PATIENT;p.patientId=p.id;p.encounterId="synthetic-stay";p.department="ICU";p.simulated=true;p.createdAt=Instant.now();db.insert(p);}
  var props=ClinicalNoteCryptoTest.properties();var json=new ObjectMapper().findAndRegisterModules();var crypto=new ClinicalNoteCrypto(props,json);ClinicalNoteSource source=mock(ClinicalNoteSource.class);ObjectProvider<ClinicalNoteSource> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(source);
  when(source.fetch(p)).thenReturn(batch("合成快照版本一"));var service=new ClinicalNoteService(new Store(db,"ICU",json),Clock.systemUTC(),props,crypto,provider,json);
  service.syncPatient(p);long before=db.count(Query.query(Criteria.where("patientId").is(PATIENT)),ClinicalNoteSnapshot.class);service.syncPatient(p);assertEquals(before,db.count(Query.query(Criteria.where("patientId").is(PATIENT)),ClinicalNoteSnapshot.class));
  when(source.fetch(p)).thenReturn(batch("合成快照版本二"));service.syncPatient(p);assertEquals(2,db.count(Query.query(Criteria.where("patientId").is(PATIENT)),ClinicalNoteSnapshot.class));
  for(var raw:db.getCollection("sepsis_clinical_note_snapshot").find()){assertFalse(raw.toJson().contains("合成快照版本"));assertNotNull(raw.get("encryptedBody"));}
  assertEquals(0,db.getCollection("sepsis_ai_interaction").countDocuments());
  boolean historyIndex=false;var indexes=db.getCollection("sepsis_clinical_note_snapshot").listIndexes();for(var index:indexes){assertFalse(index.containsKey("expireAfterSeconds"));var keys=index.get("key",org.bson.Document.class);if(keys.containsKey("syncedAt")&&keys.containsKey("_id")&&keys.containsKey("patientId")&&keys.containsKey("encounterId"))historyIndex=true;}assertTrue(historyIndex);
 }}
 @Test @org.junit.jupiter.api.Order(2) void recoverSnapshotsAfterDatabaseRestartAndRestoreCiphertextBackup(){try(var client=MongoClients.create(URI)){
  var db=new MongoTemplate(client,DATABASE);assertEquals(2,db.count(Query.query(Criteria.where("patientId").is(PATIENT)),ClinicalNoteSnapshot.class));
  var props=ClinicalNoteCryptoTest.properties();props.syncEnabled=false;var json=new ObjectMapper().findAndRegisterModules();var crypto=new ClinicalNoteCrypto(props,json);ObjectProvider<ClinicalNoteSource> provider=mock(ObjectProvider.class);
  var service=new ClinicalNoteService(new Store(db,"ICU",json),Clock.systemUTC(),props,crypto,provider,json);var snapshots=db.find(Query.query(Criteria.where("patientId").is(PATIENT)),ClinicalNoteSnapshot.class);Set<String> bodies=new HashSet<>();
  for(var snapshot:snapshots)bodies.add(String.valueOf(service.read(PATIENT,snapshot.id,"synthetic-doctor").get("body")));assertEquals(Set.of("合成快照版本一","合成快照版本二"),bodies);verifyNoInteractions(provider);
  // A document-level encrypted backup copy, not a substitute for an operational full-database backup procedure.
  var source=db.getCollection("sepsis_clinical_note_snapshot").find(new org.bson.Document("patientId",PATIENT)).first();assertNotNull(source);var backup=db.getCollection("sepsis_clinical_note_restore_test");backup.replaceOne(new org.bson.Document("_id",source.get("_id")),source,new com.mongodb.client.model.ReplaceOptions().upsert(true));
  var restored=db.findById(source.get("_id"),ClinicalNoteSnapshot.class,"sepsis_clinical_note_restore_test");assertNotNull(restored);assertTrue(crypto.decrypt(restored).contains("合成快照版本"));
 }}
 @Test @org.junit.jupiter.api.Order(3) void legacyBodyIsReadableButItsUnverifiedTimesCannotEnterEvidence()throws Exception{try(var client=MongoClients.create(URI)){
  var db=new MongoTemplate(client,DATABASE);var p=db.findById("synthetic-legacy-patient",Patient.class);if(p==null){p=new Patient();p.id="synthetic-legacy-patient";p.patientId=p.id;p.encounterId="synthetic-legacy-stay";p.department="ICU";p.simulated=true;db.insert(p);}
  var n=db.findById("synthetic-legacy-snapshot",ClinicalNoteSnapshot.class);if(n==null){n=new ClinicalNoteSnapshot();n.id="synthetic-legacy-snapshot";n.patientId=p.id;n.encounterId=p.encounterId;n.department=p.department;n.versionKey="synthetic-legacy-version";n.keyId="synthetic-v1";n.availableAt=Instant.EPOCH;n.syncedAt=Instant.EPOCH;
   byte[] nonce=new byte[12];var cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,new javax.crypto.spec.SecretKeySpec(new byte[32],"AES"),new javax.crypto.spec.GCMParameterSpec(128,nonce));cipher.updateAAD(ClinicalNoteCrypto.aad(n));
   n.encryptedBody=Base64.getEncoder().encodeToString(cipher.doFinal("{\"body\":\"合成旧格式兼容正文\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));n.nonce=Base64.getEncoder().encodeToString(nonce);db.insert(n);}
  var props=ClinicalNoteCryptoTest.properties();props.syncEnabled=false;var json=new ObjectMapper().findAndRegisterModules();var crypto=new ClinicalNoteCrypto(props,json);ObjectProvider<ClinicalNoteSource> provider=mock(ObjectProvider.class);var store=new Store(db,"ICU",json);
  var notes=new ClinicalNoteService(store,Clock.systemUTC(),props,crypto,provider,json);assertEquals("合成旧格式兼容正文",notes.read(p.id,n.id,"synthetic-doctor").get("body"));
  var packet=new ClinicalNoteEvidenceService(store,notes,crypto,Clock.systemUTC(),json).prepare(p.id,null,20,"synthetic-doctor");assertEquals(0,packet.get("ready"));assertEquals(1,packet.get("unknown"));assertFalse(packet.toString().contains("合成旧格式兼容正文"));
 }}

 @Test @org.junit.jupiter.api.Order(4) void verifiedTimeMetadataAndExactExcerptSurviveMongoRoundTrip()throws Exception{try(var client=MongoClients.create(URI)){
  var db=new MongoTemplate(client,DATABASE);String id="synthetic-evidence-patient";var patient=db.findById(id,Patient.class);if(patient==null){patient=new Patient();patient.id=id;patient.patientId=id;patient.encounterId="synthetic-evidence-stay";patient.department="ICU";patient.simulated=true;db.insert(patient);}
  Instant at=Instant.parse("2026-11-06T04:00:00Z");Clock clock=Clock.fixed(at,ZoneOffset.UTC);var json=new ObjectMapper().findAndRegisterModules();var props=ClinicalNoteCryptoTest.properties();var crypto=new ClinicalNoteCrypto(props,json);var store=new Store(db,"ICU",json);
  ClinicalNoteSource adapter=mock(ClinicalNoteSource.class);ObjectProvider<ClinicalNoteSource> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(adapter);var notes=new ClinicalNoteService(store,clock,props,crypto,provider,json);
  for(int version=1;version<=2;version++){var batch=batch(version==1?"合成证据一":"合成证据二");batch.patientId=id;batch.encounterId=patient.encounterId;var note=batch.notes.get(0);note.patientId=id;note.encounterId=patient.encounterId;note.timeMeaning="signed";note.documentedAt=at.minusSeconds(10800);note.availableAt=at.minusSeconds(version==1?7200:3600).plusNanos(900000);note.availableTimeConfirmed=true;note.documentTypeConfirmed=true;note.identityConfirmed=true;note.versionOrderConfirmed=true;
   when(adapter.fetch(patient)).thenReturn(batch);notes.syncPatient(patient);}
  var evidence=new ClinicalNoteEvidenceService(store,notes,crypto,clock,json);var packet=evidence.prepare(id,at,20,"synthetic-doctor");assertEquals(1,packet.get("ready"));assertEquals(1,packet.get("excluded"));
  Map<?,?> selected=((List<Map<?,?>>)packet.get("items")).stream().filter(row->Boolean.TRUE.equals(row.get("eligible"))).findFirst().orElseThrow();
  assertEquals(at.minusSeconds(3600).plusMillis(1),selected.get("availableAt"));
  var excerpt=evidence.excerpt(id,(String)selected.get("snapshotId"),at,0,4,(String)packet.get("packetFingerprint"),"synthetic-doctor");assertEquals("合成证据",excerpt.get("excerpt"));assertEquals(false,excerpt.get("modelInvoked"));
 }}

}
