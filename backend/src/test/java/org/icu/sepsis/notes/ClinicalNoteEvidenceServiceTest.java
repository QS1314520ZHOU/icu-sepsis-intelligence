package org.icu.sepsis.notes;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;
import java.time.*;import java.util.*;import com.fasterxml.jackson.databind.ObjectMapper;import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;import org.springframework.data.mongodb.core.query.Query;
import org.icu.sepsis.application.Store;import org.icu.sepsis.domain.Domain.*;import org.icu.sepsis.api.ApiException;
class ClinicalNoteEvidenceServiceTest {
 final MongoTemplate db=mock(MongoTemplate.class);final ObjectMapper json=new ObjectMapper().findAndRegisterModules();final Store store=new Store(db,"ICU",json);
 List<ClinicalNoteSnapshot> fixtureRows=List.of();
 final ClinicalNoteService notes=mock(ClinicalNoteService.class);final ClinicalNoteCrypto crypto=new ClinicalNoteCrypto(ClinicalNoteCryptoTest.properties(),json);
 final Patient p=new Patient();final Instant now=ClinicalNoteEvidenceRulesTest.NOW;
 final ClinicalNoteEvidenceService evidence=new ClinicalNoteEvidenceService(store,notes,crypto,Clock.fixed(ClinicalNoteEvidenceRulesTest.NOW,ZoneOffset.UTC),json);
 ClinicalNoteEvidenceServiceTest(){p.id="p";p.encounterId="e";p.department="ICU";when(notes.authorizedPatient("p")).thenReturn(p);
  when(db.find(any(Query.class),eq(ClinicalNoteSnapshot.class))).thenAnswer(i->fixtureRows);
  when(db.stream(any(Query.class),eq(ClinicalNoteSnapshot.class))).thenAnswer(i->{var values=fixtureRows.iterator();return new org.springframework.data.util.CloseableIterator<ClinicalNoteSnapshot>(){public boolean hasNext(){return values.hasNext();}public ClinicalNoteSnapshot next(){return values.next();}public void close(){}};});
 }
 void rows(ClinicalNoteSnapshot... notes){fixtureRows=List.of(notes);}
 String token(){return (String)evidence.prepare("p",now,1000,"doctor").get("packetFingerprint");}
 ClinicalNoteSnapshot encrypted(String id){var n=ClinicalNoteEvidenceRulesTest.note(id);crypto.encrypt(n,"{\"body\":\"不应泄露正文\"}");return n;}
 @Test void prepareVerifiesCipherMetadataAndDoesNotReturnBodyOrContactSource(){ClinicalNoteSnapshot good=encrypted("good"),bad=encrypted("bad");bad.availableAt=now.minusSeconds(10);rows(good,bad);
  var out=evidence.prepare("p",now,10,"doctor");assertEquals(1,out.get("ready"));assertEquals(1,out.get("unknown"));assertFalse(out.toString().contains("不应泄露正文"));assertFalse(out.toString().contains("encryptedBody"));verify(notes,never()).syncPatient(any());
  var query=org.mockito.ArgumentCaptor.forClass(Query.class);verify(db).find(query.capture(),eq(ClinicalNoteSnapshot.class));assertEquals("p",query.getValue().getQueryObject().get("patientId"));assertEquals("e",query.getValue().getQueryObject().get("encounterId"));}
 @Test void cappedScanRemainsUnknownRatherThanSelectingFromPartialHistory(){rows(encrypted("a"),encrypted("b"));
  var out=evidence.prepare("p",now,1,"doctor");assertEquals(true,out.get("truncated"));assertEquals(0,out.get("ready"));assertEquals(1,out.get("unknown"));assertEquals("LOCAL_SCAN_TRUNCATED",ClinicalNoteEvidenceRulesTest.row(out,"a").get("reason"));}
 @Test void futureReviewTimeAndInvalidEncryptedPayloadDoNotProduceEvidence(){assertThrows(ApiException.class,()->evidence.prepare("p",now.plusSeconds(1),10,"doctor"));verifyNoInteractions(db);
  var note=ClinicalNoteEvidenceRulesTest.note("a");crypto.encrypt(note,"not JSON");rows(note);assertEquals(0,evidence.prepare("p",now,10,"doctor").get("ready"));}

 @Test void sourceExcerptIsExactBoundedAndLocalOnly(){var n=encrypted("a");rows(n);when(notes.read("p","a","doctor")).thenReturn(Map.of("body","合成原文：需核对，不能据此确诊"));
  var out=evidence.excerpt("p","a",now,0,4,token(),"doctor");assertEquals("合成原文",out.get("excerpt"));assertEquals("N1",out.get("ref"));assertEquals(false,out.get("modelInvoked"));
  assertThrows(ApiException.class,()->evidence.excerpt("p","a",now,0,1001,"irrelevant","doctor"));assertThrows(ApiException.class,()->evidence.excerpt("p","a",now,0,99,token(),"doctor"));}
 @Test void futureUnknownOrCorruptSnapshotCannotBecomeQuotedEvidence(){var n=encrypted("a");n.availableAt=now.plusSeconds(1);rows(n);
  assertThrows(ApiException.class,()->evidence.excerpt("p","a",now,0,1,token(),"doctor"));verify(notes,never()).read(anyString(),anyString(),anyString());}
 @Test void excerptCannotCutUtf16SurrogatePair(){var n=encrypted("a");rows(n);when(notes.read("p","a","doctor")).thenReturn(Map.of("body","A😀B"));
  assertThrows(ApiException.class,()->evidence.excerpt("p","a",now,0,2,token(),"doctor"));assertEquals("😀",evidence.excerpt("p","a",now,1,3,token(),"doctor").get("excerpt"));}

 @Test void stalePacketCannotSilentlyChangeReferenceMapping(){rows(encrypted("a"));String previous=token();rows(encrypted("a"),encrypted("b"));
  assertThrows(ApiException.class,()->evidence.excerpt("p","a",now,0,1,previous,"doctor"));verify(notes,never()).read(anyString(),anyString(),anyString());}

 @Test void streamingFailureClosesCursorAndCannotReturnNormalEmptyPacket(){rows(encrypted("a"));org.springframework.data.util.CloseableIterator<ClinicalNoteSnapshot> cursor=mock(org.springframework.data.util.CloseableIterator.class);when(cursor.hasNext()).thenThrow(new IllegalStateException("synthetic cursor failure"));when(db.stream(any(Query.class),eq(ClinicalNoteSnapshot.class))).thenReturn(cursor);
  assertThrows(IllegalStateException.class,()->evidence.prepare("p",now,10,"doctor"));verify(cursor).close();}
}
