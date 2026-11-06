package org.icu.sepsis.notes;
import java.time.Instant;
import org.icu.sepsis.domain.Domain.Base;
import org.springframework.data.mongodb.core.mapping.Document;
@Document("sepsis_clinical_note_sync_state")
public class ClinicalNoteSyncState extends Base {
  public String status;public Boolean complete;
  public Instant lastAttemptAt,lastSuccessfulAt,lastCompleteAt;
  public int received,stored,unchanged,rejected;
}
