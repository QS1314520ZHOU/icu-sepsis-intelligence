package org.icu.sepsis.notes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public class ClinicalNoteProperties {
  @Value("${app.clinical-notes.sync-enabled:false}") public boolean syncEnabled=false;
  @Value("${app.clinical-notes.keys-json:}") public String keysJson="{}";
  @Value("${app.clinical-notes.active-key-id:}") public String activeKeyId="";
  @Value("${app.clinical-notes.fingerprint-key-id:}") public String fingerprintKeyId="";
  @Value("${app.clinical-notes.max-documents:1000}") public int maxDocuments=1000;
  @Value("${app.clinical-notes.max-body-characters:100000}") public int maxBodyCharacters=100000;
  @Value("${app.clinical-notes.max-sync-patients:100}") public int maxSyncPatients=100;
}
