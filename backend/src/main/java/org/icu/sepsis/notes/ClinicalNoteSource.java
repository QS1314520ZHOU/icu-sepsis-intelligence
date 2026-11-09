package org.icu.sepsis.notes;
import java.time.Instant;import java.util.*;
import org.icu.sepsis.domain.Domain.Patient;
/** Internal contract for a verified hospital adapter. No adapter/network request is enabled by default. */
public interface ClinicalNoteSource {
  final class Note {
    public String patientId, encounterId, department; public boolean bindingConfirmed;
    public String candidateReference, documentType, body, timeMeaning="unknown";
    public Instant documentedAt, availableAt;
    /** Explicitly confirmed source availability, independent of document creation/signature time. */
    public boolean availableTimeConfirmed, documentTypeConfirmed, versionOrderConfirmed;
    public boolean identityConfirmed;
  }
  final class Batch {
    public String patientId, encounterId, department, source;
    public boolean bindingConfirmed; public Boolean complete;public boolean legacyBodyEscapingNormalized;
    public List<Note> notes=new ArrayList<>();
  }
  Batch fetch(Patient patient) throws Exception;
  /** Safe runtime configuration only; never endpoint, credentials, request identifiers or response text. */
  default Map<String,Object> diagnostics(){return Map.of();}
  /** Provider must return currently in-ward host IDs, never infer discharge from missing note text. */
  default Set<String> activePatientIds(String department){return Set.of();}
}
