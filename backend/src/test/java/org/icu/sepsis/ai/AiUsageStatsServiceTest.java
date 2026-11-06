package org.icu.sepsis.ai;
import static org.junit.jupiter.api.Assertions.*;import java.time.*;import java.util.*;import org.junit.jupiter.api.Test;
class AiUsageStatsServiceTest {
 final Instant now=Instant.parse("2026-11-06T01:00:00Z");
 AiInteraction record(String id){var r=new AiInteraction();r.id=id;r.patientId="p";r.encounterId="e";r.status="ok";r.createdAt=now.minusSeconds(100);return r;}
 @Test void latestFeedbackDeduplicatesGenerationsWithoutFreeTextOrZeroFilling(){var r=record("a");r.feedback=List.of(Map.of("at",now.minusSeconds(5),"verdict","accept"),Map.of("at",now,"verdict","edit","reviewSeconds",30,"durationBasis","self_reported","editedText","private"));var other=record("b");var out=AiUsageStatsService.aggregate(List.of(r,r,other),now);assertEquals(2,out.get("successful"));assertEquals(1,out.get("edited"));assertEquals(0,out.get("accepted"));assertEquals(1,out.get("noFeedback"));assertEquals(30L,out.get("selfReportedSeconds"));assertEquals(1,out.get("patients"));assertFalse(out.toString().contains("private"));}
 @Test void futureAndAmbiguousFeedbackCannotBecomeAcceptance(){var a=record("a");a.feedback=List.of(Map.of("at",now.plusSeconds(1),"verdict","accept"));var b=record("b");b.feedback=List.of(Map.of("at",now,"verdict","accept"),Map.of("at",now,"verdict","reject"));var out=AiUsageStatsService.aggregate(List.of(a,b),now);assertEquals(0,out.get("withFeedback"));assertEquals(1,out.get("unknownFeedback"));assertNull(out.get("meanSelfReportedSeconds"));}
}
