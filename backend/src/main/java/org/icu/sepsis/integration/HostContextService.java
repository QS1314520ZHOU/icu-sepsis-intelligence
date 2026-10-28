package org.icu.sepsis.integration;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.icu.sepsis.api.ApiException;
import org.icu.sepsis.api.Views;
import org.icu.sepsis.application.Store;
import org.icu.sepsis.domain.Domain.Patient;
import org.icu.sepsis.embed.HostGrantRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Materializes a SmartCare host patient into the dedicated business database and issues a
 * short-lived embed grant for that patient only.
 *
 * <p>Writes never touch SmartCare/DataCenter. Host token/account are context hints only and are
 * not persisted.
 */
@Service
public class HostContextService {
  private final Store store;
  private final HostGrantRegistry grants;
  private final ObjectProvider<SmartCareSourceReader> reader;
  private final boolean smartcareMode;
  private final String defaultDepartment;

  public HostContextService(
      Store store,
      HostGrantRegistry grants,
      ObjectProvider<SmartCareSourceReader> reader,
      @Value("${app.source.mode:demo}") String mode,
      @Value("${app.department}") String department) {
    this.store = store;
    this.grants = grants;
    this.reader = reader;
    this.smartcareMode = "smartcare".equalsIgnoreCase(mode);
    this.defaultDepartment = department;
  }

  public Map<String, Object> apply(String parentOrigin, String hostPatientId) {
    SmartCareSourceReader source = requireSource();
    Patient loaded = source.loadPatient(hostPatientId);
    Patient local = upsertLocal(loaded);
    Map<String, Object> session = grants.issue(parentOrigin, local);
    Map<String, Object> payload = new LinkedHashMap<>(Views.patient(local));
    payload.put("hisPid", source.hisPidOf(hostPatientId));
    payload.put("sourceMode", "smartcare");
    payload.put("accessToken", session.get("accessToken"));
    payload.put("expiresAt", session.get("expiresAt"));
    payload.put("username", session.get("username"));
    payload.put("roles", session.get("roles"));
    return payload;
  }

  private SmartCareSourceReader requireSource() {
    SmartCareSourceReader source = reader.getIfAvailable();
    if (source == null || !smartcareMode || !source.isReadable())
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "SMARTCARE_SOURCE_UNAVAILABLE",
          "未启用 SmartCare 只读数据源或连接失败");
    return source;
  }

  /** Idempotent upsert that tolerates concurrent SmartCareReady retries (no optimistic 409). */
  private Patient upsertLocal(Patient loaded) {
    java.util.Date admitted =
        loaded.admittedAt == null ? null : java.util.Date.from(loaded.admittedAt);
    java.util.Date created =
        loaded.createdAt == null ? new java.util.Date() : java.util.Date.from(loaded.createdAt);
    String department =
        loaded.department == null || loaded.department.isBlank()
            ? defaultDepartment
            : loaded.department;
    org.bson.Document doc =
        new org.bson.Document("_id", new org.bson.types.ObjectId(loaded.id))
            .append("patientId", loaded.patientId)
            .append("encounterId", loaded.encounterId)
            .append("department", department)
            .append("label", loaded.label)
            .append("bed", loaded.bed)
            .append("admittedAt", admitted)
            .append("createdAt", created)
            .append("simulated", false)
            .append("_class", "org.icu.sepsis.domain.Domain$Patient");
    store.db
        .getCollection("sepsis_patient_context")
        .replaceOne(
            new org.bson.Document("_id", new org.bson.types.ObjectId(loaded.id)),
            doc,
            new com.mongodb.client.model.ReplaceOptions().upsert(true));
    Patient p = store.db.findById(loaded.id, Patient.class);
    if (p == null) throw new IllegalStateException("patient upsert failed");
    return p;
  }
}
