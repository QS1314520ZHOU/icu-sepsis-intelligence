package org.icu.sepsis.deterioration;
import static org.junit.jupiter.api.Assertions.*;import java.util.*;import org.junit.jupiter.api.Test;
class RenalReviewTest {
 @Test void eitherPositivePathRetainsCandidate(){for(String cr:List.of("unknown","not_triggered","candidate"))assertEquals("candidate",RenalReview.combine(Map.of("status",cr),Map.of("status","candidate")).get("status"));assertEquals("candidate",RenalReview.combine(Map.of("status","candidate"),Map.of("status","unknown")).get("status"));}
 @Test void absentPathIsNotNegative(){assertEquals("unknown",RenalReview.combine(Map.of("status","not_triggered"),Map.of("status","unknown")).get("status"));}
 @Test void priorCandidateDoesNotDisappearAfterCurrentValuesImprove(){assertEquals("prior_candidate",RenalReview.combine(Map.of("status","not_triggered","historicalCandidate",true),Map.of("status","not_triggered")).get("status"));}
 @Test void twoUntriggeredPathsStillDoNotExcludeDiagnosis(){var r=RenalReview.combine(Map.of("status","not_triggered"),Map.of("status","not_triggered"));assertEquals("no_observed_trigger",r.get("status"));assertEquals(false,r.get("diagnosisConfirmed"));assertTrue(r.get("detail").toString().contains("不等于排除"));}
}
