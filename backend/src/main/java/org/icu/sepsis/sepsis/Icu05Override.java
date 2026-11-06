package org.icu.sepsis.sepsis;
import java.time.Instant;
import java.util.Map;
import org.icu.sepsis.domain.Domain.Base;
import org.springframework.data.mongodb.core.mapping.Document;
@Document("sepsis_icu05_override")
public class Icu05Override extends Base {
  public String scope, targetId, windowKey, ruleVersion, activeKey, reasonCode, reasonText, operator, revokedBy, revokeReason;
  public Instant t0, revokedAt;
  public Object autoValue, manualValue;
  public Map<String,Object> automaticSnapshot;
}
