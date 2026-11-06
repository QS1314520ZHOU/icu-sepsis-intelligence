package org.icu.sepsis.ai;
import static org.junit.jupiter.api.Assertions.*;import java.util.*;import org.junit.jupiter.api.Test;
class CitationEvidenceGuardTest {
  @Test void acceptsSupportedMeasurementWithAliasAndNumericFormatting(){assertNull(CitationEvidenceGuard.check("MAP 58.0 mmHg，需核对",List.of("平均动脉压（有创）58 mmHg")));}
  @Test void numbersInOtherEvidenceDoNotValidateUncitedClaim(){assertNotNull(CitationEvidenceGuard.check("乳酸 4.6 mmol/L",List.of("平均动脉压 58 mmHg")));}
  @Test void numbersCannotBeSwappedAcrossSubjectsEvenWhenBothCited(){assertNotNull(CitationEvidenceGuard.check("乳酸 58 mmol/L",List.of("平均动脉压 58 mmHg","乳酸 4.6 mmol/L")));}
  @Test void unitsCannotBeSwappedOrInvented(){assertNotNull(CitationEvidenceGuard.check("MAP 58 kPa",List.of("MAP 58 mmHg")));assertNotNull(CitationEvidenceGuard.check("乳酸 4.6 mmol/L",List.of("乳酸 4.6，单位未确认")));}
  @Test void smallClinicalValuesRequireSupportAndRefsAreNotMeasurements(){assertNotNull(CitationEvidenceGuard.check("乳酸 2 mmol/L",List.of("乳酸无法判定")));assertNull(CitationEvidenceGuard.check("核对原始记录 E2",List.of("原始记录待核对")));}
  @Test void referencesDoNotMakeUnsupportedStatementsValid(){assertNotNull(CitationEvidenceGuard.check("心率 58 次/分",List.of("MAP 58 mmHg")));assertNull(CitationEvidenceGuard.check("血培养时间待核对",List.of("血培养时间待核对")));}

  @Test void trendValuesRemainBoundToMetricAndCountsAreNotClinicalValues(){String trend="乳酸 近 24h 5 次：首次 2.1（11-06 08:00）→ 最近 4.6 mmol/L（11-06 10:00），最低 2.1，最高 4.6";
    assertNull(CitationEvidenceGuard.check("乳酸 4.6 mmol/L",List.of(trend)));
    assertNotNull(CitationEvidenceGuard.check("乳酸 24 mmol/L",List.of(trend)));
    assertNotNull(CitationEvidenceGuard.check("MAP 4.6 mmHg",List.of(trend)));
  }

  @Test void trendEndpointsCannotBorrowAnotherMetricsNumber(){assertNotNull(CitationEvidenceGuard.check("MAP 由 4.6 降至 58 mmHg",List.of("MAP 58 mmHg","乳酸 4.6 mmol/L")));}
  @Test void trendCannotReverseTimeOrDirection(){String trend="乳酸 近 24h 2 次：首次 2.1 → 最近 4.6 mmol/L";assertNull(CitationEvidenceGuard.check("乳酸 由 2.1 升至 4.6 mmol/L",List.of(trend)));assertNotNull(CitationEvidenceGuard.check("乳酸 由 4.6 降至 2.1 mmol/L",List.of(trend)));assertNotNull(CitationEvidenceGuard.check("乳酸 由 2.1 降至 4.6 mmol/L",List.of(trend)));}
}
