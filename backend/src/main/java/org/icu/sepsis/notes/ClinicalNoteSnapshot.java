package org.icu.sepsis.notes;
import java.time.Instant;
import org.icu.sepsis.domain.Domain.Base;
import org.springframework.data.mongodb.core.mapping.Document;
@Document("sepsis_clinical_note_snapshot")
public class ClinicalNoteSnapshot extends Base {
  public String versionKey, documentKey, source, documentType, timeMeaning;
  public String encryptedBody, nonce, keyId, fingerprintKeyId;
  public Instant documentedAt, availableAt, syncedAt;
  public String timePrecision;
  public boolean timeConflict;
  public boolean identityConfirmed, availableTimeConfirmed, documentTypeConfirmed, versionOrderConfirmed;
  /** null/1 = legacy body protection; 2 = metadata protection; 3 = conservative time bounds and metadata protection. */
  public Integer cryptoVersion;
  // No expiry/TTL field. Source snapshots are separate from AI prompt/result audit persistence.
  /** Retain authenticated metadata without retaining large encrypted bodies in a review packet. */
  static ClinicalNoteSnapshot metadataOnly(ClinicalNoteSnapshot n){var m=new ClinicalNoteSnapshot();m.id=n.id;m.patientId=n.patientId;m.encounterId=n.encounterId;m.department=n.department;m.version=n.version;
    m.versionKey=n.versionKey;m.documentKey=n.documentKey;m.source=n.source;m.documentType=n.documentType;m.timeMeaning=n.timeMeaning;
    m.documentedAt=n.documentedAt;m.availableAt=n.availableAt;m.syncedAt=n.syncedAt;m.identityConfirmed=n.identityConfirmed;m.availableTimeConfirmed=n.availableTimeConfirmed;
    m.documentTypeConfirmed=n.documentTypeConfirmed;m.versionOrderConfirmed=n.versionOrderConfirmed;m.cryptoVersion=n.cryptoVersion;m.timePrecision=n.timePrecision;m.timeConflict=n.timeConflict;return m;}

}
