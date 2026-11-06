package org.icu.sepsis.notes;
import static org.junit.jupiter.api.Assertions.*;import java.time.*;import java.util.*;import org.junit.jupiter.api.Test;
class ClinicalNoteEvidenceRulesTest {
 static final Instant NOW=Instant.parse("2026-11-06T03:00:00Z");
 static ClinicalNoteSnapshot note(String id){var n=new ClinicalNoteSnapshot();n.id=id;n.patientId="p";n.encounterId="e";n.department="ICU";n.source="synthetic";n.documentKey="document-"+id;n.versionKey="version-"+id;n.cryptoVersion=3;n.timePrecision="millisecond_upper_bound";n.identityConfirmed=true;n.versionOrderConfirmed=true;n.documentTypeConfirmed=true;n.availableTimeConfirmed=true;n.documentType="日常病程";n.timeMeaning="signed";n.documentedAt=NOW.minusSeconds(7200);n.availableAt=NOW.minusSeconds(3600);n.syncedAt=NOW.minusSeconds(1800);return n;}
 static Map<String,Object> result(ClinicalNoteSnapshot... notes){return ClinicalNoteEvidenceRules.evaluate(List.of(notes),new HashSet<>(Arrays.asList(notes).stream().map(n->n.id).collect(java.util.stream.Collectors.toList())),NOW);}
 static Map<?,?> row(Map<String,Object> result,String id){return ((List<Map<?,?>>)result.get("items")).stream().filter(r->id.equals(r.get("snapshotId"))).findFirst().orElseThrow();}
 @Test void availabilityIsSeparateFromUnknownDocumentTime(){var n=note("a");n.documentedAt=null;n.timeMeaning="unknown";assertEquals(true,row(result(n),"a").get("eligible"));n.availableTimeConfirmed=false;assertNull(row(result(n),"a").get("eligible"));}
 @Test void futureSourceAndLateLocalBackfillAreExcludedButBoundaryIsInclusive(){ClinicalNoteSnapshot a=note("a"),b=note("b"),c=note("c");a.availableAt=NOW.plusSeconds(1);a.syncedAt=NOW.plusSeconds(2);b.syncedAt=NOW.plusSeconds(1);c.availableAt=NOW;c.syncedAt=NOW;
  var out=result(a,b,c);assertEquals("SOURCE_FUTURE",row(out,"a").get("reason"));assertEquals("LOCAL_FUTURE",row(out,"b").get("reason"));assertEquals(true,row(out,"c").get("eligible"));}
 @Test void newestAvailableVersionIsChosenAtReviewTimeWithoutFutureRevisionLeak(){ClinicalNoteSnapshot old=note("old"),newer=note("new"),future=note("future");newer.documentKey=old.documentKey;future.documentKey=old.documentKey;newer.availableAt=NOW.minusSeconds(60);newer.syncedAt=NOW;future.availableAt=NOW.plusSeconds(60);future.syncedAt=NOW.plusSeconds(120);
  var out=result(old,newer,future);assertEquals("OLDER_VERSION",row(out,"old").get("reason"));assertEquals("N1",row(out,"new").get("ref"));assertEquals("SOURCE_FUTURE",row(out,"future").get("reason"));}
 @Test void equalAvailabilityWithDifferentVersionsIsUnknownAndOtherDocumentsRetainCounterEvidence(){ClinicalNoteSnapshot a=note("a"),b=note("b"),counter=note("counter");b.documentKey=a.documentKey;
  var out=result(a,b,counter);assertEquals("VERSION_CONFLICT",row(out,"a").get("reason"));assertNull(row(out,"b").get("eligible"));assertEquals(true,row(out,"counter").get("eligible"));assertEquals(1,out.get("ready"));}
 @Test void unverifiedMetadataCannotSupplyTimesOrPretendFalseClinicalCriteria(){var legacy=note("legacy");legacy.cryptoVersion=null;var bad=note("bad");
  var out=ClinicalNoteEvidenceRules.evaluate(List.of(legacy,bad),Set.of(),NOW);assertEquals(2,out.get("unknown"));assertEquals(0,out.get("excluded"));assertNull(row(out,"bad").get("availableAt"));assertNull(row(out,"legacy").get("eligible"));assertEquals(false,out.get("modelInvoked"));assertNull(out.get("clinicalConclusion"));}
 @Test void typeIdentityAndImpossibleTimesRequireReview(){ClinicalNoteSnapshot a=note("a"),b=note("b"),c=note("c");a.documentTypeConfirmed=false;b.identityConfirmed=false;c.documentedAt=NOW;
  var out=result(a,b,c);assertEquals("TYPE_UNKNOWN",row(out,"a").get("reason"));assertEquals("IDENTITY_UNKNOWN",row(out,"b").get("reason"));assertEquals("TIME_CONFLICT",row(out,"c").get("reason"));}

 @Test void uncertainVersionOrderingNeverUsesIngestionOrderAsRevisionOrder(){ClinicalNoteSnapshot a=note("a"),b=note("b");b.documentKey=a.documentKey;b.availableAt=NOW.minusSeconds(10);b.syncedAt=NOW;b.versionOrderConfirmed=false;var out=result(a,b);assertEquals(0,out.get("ready"));assertEquals(2,out.get("unknown"));assertEquals("VERSION_ORDER_UNKNOWN",row(out,"a").get("reason"));}

 @Test void protectedButOldPrecisionMetadataStillRemainsUnknown(){var n=note("v2");n.cryptoVersion=2;n.timePrecision=null;var out=result(n);assertEquals("TIME_PRECISION_UNKNOWN",row(out,"v2").get("reason"));assertNull(row(out,"v2").get("availableAt"));assertNull(row(out,"v2").get("eligible"));}
}
