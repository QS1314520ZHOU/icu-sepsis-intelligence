package org.icu.sepsis.sepsis;

import java.time.Instant;
import java.util.Map;
import org.icu.sepsis.domain.Domain.Base;
import org.springframework.data.mongodb.core.mapping.Document;

/** Case exclusion only; automatic evidence is immutable and revocation is retained. */
@Document("sepsis_icu05_case_review")
public class Icu05CaseReview extends Base {
  public String ruleVersion, activeKey, reasonCode, reasonText, operator, revokedBy, revokeReason;
  public Instant t0, revokedAt;
  public Boolean autoValue;
  public boolean manualValue = false;
  public Map<String,Object> automaticSnapshot;
}
