package org.icu.sepsis.alerting;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;import java.util.*;import org.junit.jupiter.api.Test;
import org.icu.sepsis.domain.Domain.Alert;
class AlertReviewStatsServiceTest {
  @Test void chainsDeduplicateAndNewEvidenceDoesNotRetainOldFeedbackAsCurrent(){Alert old=WardPriorityServiceTest.alert("old","p","high");old.reviewChainId="root";old.reviewedAt=old.createdAt;old.reviewOutcome="confirmed_false_positive";old.status="rejected";
    Alert fresh=WardPriorityServiceTest.alert("new","p","high");fresh.reviewChainId="root";fresh.createdAt=old.createdAt.plusSeconds(60);fresh.needsReReview=true;
    var out=AlertReviewStatsService.aggregate(List.of(old,fresh));assertEquals(1,out.get("reviewChains"));assertEquals(1,out.get("patients"));assertEquals(0,out.get("withRecordedFeedback"));assertEquals(0,out.get("confirmedFalsePositive"));assertEquals(1,out.get("needsReReview"));assertEquals(0.0,out.get("feedbackRate"));}
  @Test void legacyRejectionUnknownAndExplicitFalsePositiveAreDistinct(){Alert legacy=WardPriorityServiceTest.alert("legacy","a","high");legacy.status="rejected";
    Alert rejected=WardPriorityServiceTest.alert("rejected","b","high");rejected.status="rejected";rejected.reviewedAt=rejected.createdAt;rejected.reviewOutcome="rejected_only";
    Alert fp=WardPriorityServiceTest.alert("fp","c","high");fp.status="rejected";fp.reviewedAt=fp.createdAt;fp.reviewOutcome="confirmed_false_positive";
    Alert pending=WardPriorityServiceTest.alert("pending","d","high");var out=AlertReviewStatsService.aggregate(List.of(legacy,rejected,fp,pending));
    assertEquals(1,out.get("historicalUnknown"));assertEquals(1,out.get("confirmedFalsePositive"));assertEquals(1,out.get("rejectedOnly"));assertEquals(3,out.get("feedbackDenominator"));assertEquals(2.0/3,out.get("feedbackRate"));assertEquals(false,out.get("leadTimeAvailable"));assertNull(out.get("leadTimeMinutes"));}
  @Test void emptyDoesNotInventZeroRate(){assertNull(AlertReviewStatsService.aggregate(List.of()).get("feedbackRate"));}
}
