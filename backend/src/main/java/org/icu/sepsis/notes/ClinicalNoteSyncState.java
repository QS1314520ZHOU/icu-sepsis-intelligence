package org.icu.sepsis.notes;
import java.time.Instant;
import org.icu.sepsis.domain.Domain.Base;
import org.springframework.data.mongodb.core.mapping.Document;
@Document("sepsis_clinical_note_sync_state")
public class ClinicalNoteSyncState extends Base {
  public java.util.Map<String,Object> parseDiagnostics=java.util.Map.of();
  public String status;public Boolean complete;public boolean legacyBodyEscapingNormalized;
  public Instant lastAttemptAt,lastSuccessfulAt,lastCompleteAt;
  public int received,stored,unchanged,rejected;
}
