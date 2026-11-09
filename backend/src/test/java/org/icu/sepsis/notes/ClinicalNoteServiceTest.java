package org.icu.sepsis.notes;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;import java.time.*;import java.util.*;import java.util.concurrent.atomic.AtomicReference;
import org.icu.sepsis.application.Store;import org.icu.sepsis.api.ApiException;import org.icu.sepsis.domain.Domain.*;
import org.junit.jupiter.api.*;import org.springframework.beans.factory.ObjectProvider;import org.springframework.data.mongodb.core.MongoTemplate;import org.springframework.data.mongodb.core.query.Query;
class ClinicalNoteServiceTest {
  final MongoTemplate db=mock(MongoTemplate.class);final ObjectMapper json=new ObjectMapper().findAndRegisterModules();final Store store=new Store(db,"ICU",json);
  final ClinicalNoteProperties props=ClinicalNoteCryptoTest.properties();final ClinicalNoteCrypto crypto=new ClinicalNoteCrypto(props,json);
  final ClinicalNoteSource source=mock(ClinicalNoteSource.class);final ObjectProvider<ClinicalNoteSource> provider=mock(ObjectProvider.class);
  final Patient patient=new Patient();final List<ClinicalNoteSnapshot> saved=new ArrayList<>();final AtomicReference<ClinicalNoteSyncState> syncState=new AtomicReference<>();
  final Instant now=Instant.parse("2026-11-06T02:00:00Z");ClinicalNoteService service;
  @BeforeEach void setup()throws Exception{patient.id="p";patient.patientId="p";patient.encounterId="e";patient.department="ICU";when(db.findById("p",Patient.class)).thenReturn(patient);when(provider.getIfAvailable()).thenReturn(source);
    when(db.findOne(any(Query.class),eq(ClinicalNoteSyncState.class))).thenAnswer(i->syncState.get());
    when(db.findOne(any(Query.class),eq(ClinicalNoteSnapshot.class))).thenAnswer(i->{String key=((Query)i.getArgument(0)).getQueryObject().getString("versionKey");return saved.stream().filter(n->n.versionKey.equals(key)).findFirst().orElse(null);});
    when(db.insert(any(ClinicalNoteSnapshot.class))).thenAnswer(i->{ClinicalNoteSnapshot n=i.getArgument(0);saved.add(n);return n;});
    when(db.save(any(ClinicalNoteSyncState.class))).thenAnswer(i->{ClinicalNoteSyncState s=i.getArgument(0);syncState.set(s);return s;});
    when(db.find(any(Query.class),eq(ClinicalNoteSnapshot.class))).thenAnswer(i->new ArrayList<>(saved));when(db.count(any(Query.class),eq(ClinicalNoteSnapshot.class))).thenAnswer(i->(long)saved.size());
    service=new ClinicalNoteService(store,Clock.fixed(now,ZoneOffset.UTC),props,crypto,provider,json);when(source.fetch(patient)).thenReturn(batch("合成日常病程：正文日期 2020-01-01 不是已确认文书时间"));}
  ClinicalNoteSource.Batch batch(String body){var b=new ClinicalNoteSource.Batch();b.patientId="p";b.encounterId="e";b.department="ICU";b.source="synthetic.BLBCJL";b.bindingConfirmed=true;b.complete=true;var n=new ClinicalNoteSource.Note();n.patientId="p";n.encounterId="e";n.department="ICU";n.bindingConfirmed=true;n.body=body;n.candidateReference="candidate-1";n.documentType="日常病程";b.notes.add(n);return b;}
  @Test void storesCiphertextInDatabaseAndKeepsIndependentRevisions()throws Exception{service.syncPatient(patient);service.syncPatient(patient);assertEquals(1,saved.size());assertEquals(1,syncState.get().unchanged);
    when(source.fetch(patient)).thenReturn(batch("合成修订正文"));service.syncPatient(patient);assertEquals(2,saved.size());assertEquals(saved.get(0).documentKey,saved.get(1).documentKey);assertFalse(saved.get(1).identityConfirmed);
    assertFalse(json.writeValueAsString(saved).contains("合成修订正文"));assertNull(saved.get(0).documentedAt);assertNull(saved.get(0).availableAt);assertTrue(crypto.decrypt(saved.get(0)).contains("2020-01-01"));
    var audits=org.mockito.ArgumentCaptor.forClass(Object.class);verify(db,atLeastOnce()).insert(audits.capture());for(Object value:audits.getAllValues())if(value instanceof Audit||value instanceof History)assertFalse(json.writeValueAsString(value).contains("合成日常病程"));}
  @Test void wrongEncounterOrUnconfirmedBindingNeverStoresNotes()throws Exception{var batch=batch("不应归档");batch.encounterId="other";when(source.fetch(patient)).thenReturn(batch);assertEquals("binding_unconfirmed",service.syncPatient(patient).status);assertTrue(saved.isEmpty());
    batch.encounterId="e";batch.bindingConfirmed=false;service.syncPatient(patient);assertTrue(saved.isEmpty());}
  @Test void sourceTimeoutPreservesPriorSnapshotAndLastSuccess()throws Exception{service.syncPatient(patient);when(source.fetch(patient)).thenThrow(new java.net.SocketTimeoutException("do-not-log-synthetic-credential"));
    var state=service.syncPatient(patient);assertEquals("timeout",state.status);assertEquals(now,state.lastSuccessfulAt);assertFalse(state.complete);assertEquals(1,saved.size());}
  @Test void truncationAndUnknownCompletenessNeverPretendFullCoverage()throws Exception{var batch=batch("a");batch.notes.addAll(batch("b").notes);props.maxDocuments=1;when(source.fetch(patient)).thenReturn(batch);assertFalse(service.syncPatient(patient).complete);
    props.maxDocuments=100;batch.complete=null;assertNull(service.syncPatient(patient).complete);assertEquals("completeness_unknown",syncState.get().status);}
  @Test void storedSnapshotRemainsReadableWithoutSourceOrProcessCache()throws Exception{service.syncPatient(patient);var stored=saved.get(0);when(db.findById(stored.id,ClinicalNoteSnapshot.class)).thenReturn(stored);props.syncEnabled=false;
    var restored=new ClinicalNoteService(store,Clock.fixed(now.plus(Duration.ofDays(5000)),ZoneOffset.UTC),props,new ClinicalNoteCrypto(props,json),provider,json);
    assertTrue(restored.read("p",stored.id,"doctor").get("body").toString().contains("合成日常病程"));assertEquals(1L,restored.list("p",0,20,"doctor").get("total"));verify(source,times(1)).fetch(patient);}
  @Test void readRechecksCurrentPatientEncounterAndNeverExposesCipherFields()throws Exception{service.syncPatient(patient);var note=saved.get(0);when(db.findById(note.id,ClinicalNoteSnapshot.class)).thenReturn(note);
    var result=service.read("p",note.id,"doctor");assertFalse(result.containsKey("encryptedBody"));assertFalse(result.containsKey("nonce"));note.encounterId="different";assertThrows(ApiException.class,()->service.read("p",note.id,"doctor"));}
  @Test void disabledOrMissingKeyNeverCallsSource()throws Exception{props.syncEnabled=false;assertEquals("disabled",service.syncPatient(patient).status);props.syncEnabled=true;props.activeKeyId="missing";
    assertEquals("key_unavailable",service.syncPatient(patient).status);verifyNoInteractions(source);assertTrue(saved.isEmpty());}

  @AfterEach void clearAuthentication(){org.springframework.security.core.context.SecurityContextHolder.clearContext();}
  @Test void changedDepartmentGrantCannotReadPreviouslyAuthorizedSnapshot()throws Exception{service.syncPatient(patient);var note=saved.get(0);when(db.findById(note.id,ClinicalNoteSnapshot.class)).thenReturn(note);
    var grant=new org.icu.sepsis.embed.EmbedAccess.Grant();grant.patientId="p";grant.encounterId="e";grant.department="OTHER";
    var auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("doctor","",java.util.List.of());auth.setDetails(grant);org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    assertThrows(ApiException.class,()->service.read("p",note.id,"doctor"));
  }

  @Test void mixedPatientDocumentsAreRejectedIndividually()throws Exception{var batch=batch("正确归属正文");var wrong=batch("其他住院正文").notes.get(0);wrong.encounterId="other";batch.notes.add(wrong);when(source.fetch(patient)).thenReturn(batch);
    var state=service.syncPatient(patient);assertEquals(1,saved.size());assertEquals(1,state.rejected);assertFalse(state.complete);assertFalse(crypto.decrypt(saved.get(0)).contains("其他住院正文"));}
  @Test void verifiedEmptyResponseDiffersFromFailedOrUnconfirmedResponse()throws Exception{var batch=batch("unused");batch.notes.clear();when(source.fetch(patient)).thenReturn(batch);assertEquals("no_records",service.syncPatient(patient).status);assertTrue(saved.isEmpty());}

  @Test void confirmedAvailabilityIsRetainedEvenWithoutDocumentTime()throws Exception{var b=batch("合成正文");b.notes.get(0).availableTimeConfirmed=true;b.notes.get(0).availableAt=now.minusSeconds(60);when(source.fetch(patient)).thenReturn(b);service.syncPatient(patient);assertEquals(now.minusSeconds(60),saved.get(0).availableAt);assertNull(saved.get(0).documentedAt);assertEquals(3,saved.get(0).cryptoVersion);}
  @Test void failedReadIsAuditedWithoutPlaintext()throws Exception{service.syncPatient(patient);var n=saved.get(0);when(db.findById(n.id,ClinicalNoteSnapshot.class)).thenReturn(n);n.encryptedBody="invalid";assertThrows(ApiException.class,()->service.read("p",n.id,"doctor"));verify(db).insert(argThat((Object o)->o instanceof History&&"read_unavailable".equals(((History)o).action)&&!((History)o).reason.contains("合成日常病程")));}

  @Test void localCollectionTimeIsAfterFetchAndSourceSubMillisecondTimeRoundsUp()throws Exception{
    Clock advancing=mock(Clock.class);var clockValue=new AtomicReference<>(now);when(advancing.instant()).thenAnswer(i->clockValue.get());
    var batch=batch("精度合成正文");var input=batch.notes.get(0);input.availableAt=now.plusNanos(900000);input.availableTimeConfirmed=true;input.identityConfirmed=true;input.documentTypeConfirmed=true;input.versionOrderConfirmed=true;
    when(source.fetch(patient)).thenAnswer(i->{clockValue.set(now.plusSeconds(10));return batch;});
    var service=new ClinicalNoteService(store,advancing,props,crypto,provider,json);service.syncPatient(patient);var n=saved.get(0);
    assertEquals(now.plusSeconds(10),n.syncedAt);assertEquals(now.plusMillis(1),n.availableAt);assertTrue(crypto.decrypt(n).contains(input.availableAt.toString()));
    var packet=ClinicalNoteEvidenceRules.evaluate(List.of(n),Set.of(n.id),now.plusNanos(500000));assertEquals("SOURCE_FUTURE",ClinicalNoteEvidenceRulesTest.row(packet,n.id).get("reason"));
  }
 @Test void noteFailureExposesOnlySafeCategoryAndConnectionReadiness()throws Exception{service.syncPatient(patient);doThrow(new BlbcjlClient.Failure("interface_http_error")).when(source).fetch(patient);assertEquals("interface_http_error",service.syncPatient(patient).status);var directory=service.list("p",0,20,"doctor");assertEquals(true,directory.get("syncEnabled"));assertEquals(true,directory.get("adapterConfigured"));assertEquals(true,directory.get("snapshotKeysReady"));assertEquals(1L,directory.get("total"));assertFalse(directory.containsKey("endpoint"));assertFalse(directory.containsKey("keysJson"));doThrow(new BlbcjlProtocol.Failure("interface_response_invalid")).when(source).fetch(patient);assertEquals("interface_response_invalid",service.syncPatient(patient).status);}
 @Test void compatibilityProvenanceIsAuthenticatedWithTheSavedBody()throws Exception{var batch=batch("compatibility-case");batch.legacyBodyEscapingNormalized=true;when(source.fetch(patient)).thenReturn(batch);var state=service.syncPatient(patient);assertTrue(state.legacyBodyEscapingNormalized);var savedNote=saved.get(0);var payload=json.readTree(crypto.decrypt(savedNote));assertTrue(payload.path("legacyBodyEscapingNormalized").asBoolean());assertEquals(batch.notes.get(0).body,payload.path("body").asText());}
 @Test void failedParseStoresOnlySafeDiagnosticsAndReturnsEffectiveAdapterMode()throws Exception{
  when(source.diagnostics()).thenReturn(Map.of("parserVersion",BlbcjlProtocol.VERSION,"legacyBodyEscapingEnabled",true));
  byte[] raw="{\"code\":0,\"data\":[{\"WBNR\":\"SYNTHETIC-PRIVATE\nBODY\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  var failure=assertThrows(BlbcjlProtocol.Failure.class,()->BlbcjlProtocol.parse(raw,"SYNTHETIC","",patient,1000,10000,false));
  doThrow(failure).when(source).fetch(patient);var state=service.syncPatient(patient);assertEquals("interface_response_invalid",state.status);assertEquals("unescaped_control",state.parseDiagnostics.get("kind"));assertFalse(state.parseDiagnostics.toString().contains("SYNTHETIC-PRIVATE"));assertTrue(saved.isEmpty());
  var list=service.list("p",0,20,"doctor");assertEquals(state.parseDiagnostics,list.get("parseDiagnostics"));assertEquals(true,((Map<?,?>)list.get("adapterDiagnostics")).get("legacyBodyEscapingEnabled"));
  doReturn(batch("合成正文")).when(source).fetch(patient);assertEquals(Map.of(),service.syncPatient(patient).parseDiagnostics);
 }

}
