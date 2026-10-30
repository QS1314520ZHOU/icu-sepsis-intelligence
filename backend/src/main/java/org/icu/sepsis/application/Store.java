package org.icu.sepsis.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.icu.sepsis.api.*;
import org.icu.sepsis.domain.Domain.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class Store {
  public final MongoTemplate db;
  public final String department;
  private final ObjectMapper json;

  public Store(MongoTemplate d, @Value("${app.department}") String dept, ObjectMapper j) {
    db = d;
    department = dept;
    json = j;
  }

  public Criteria scope() {
    org.icu.sepsis.embed.EmbedAccess.Grant g = org.icu.sepsis.embed.EmbedAccess.current();
    if (g != null) {
      if (g.patientId != null && g.encounterId != null)
        // Keep patient/encounter scope nested: downstream summaries also append patientId.
        return new Criteria().orOperator(Criteria.where("patientId").is(g.patientId)
            .and("encounterId").is(g.encounterId)
            .and("department").is(g.department != null ? g.department : department));
      return Criteria.where("department").is(g.department != null ? g.department : department);
    }
    return Criteria.where("department").is(department);
  }

  /** Department-wide criteria, ignoring a patient-level embed grant (ward list views). */
  public Criteria departmentScope() {
    org.icu.sepsis.embed.EmbedAccess.Grant g = org.icu.sepsis.embed.EmbedAccess.current();
    return Criteria.where("department").is(g != null && g.department != null ? g.department : department);
  }

  public Patient patient(String id) {
    Patient p = db.findById(id, Patient.class);
    if (p == null) throw ApiException.notFound();
    authorize(p);
    return p;
  }

  public void authorize(Base b) {
    org.icu.sepsis.embed.EmbedAccess.Grant g = org.icu.sepsis.embed.EmbedAccess.current();
    if (g != null) {
      if (g.patientId != null
          && (!g.patientId.equals(b.patientId) || !g.encounterId.equals(b.encounterId)))
        throw new ApiException(HttpStatus.FORBIDDEN, "PATIENT_SCOPE_DENIED", "嵌入授权不包含此患者住院上下文");
      return;
    }
    if (!department.equals(b.department))
      throw new ApiException(HttpStatus.FORBIDDEN, "PATIENT_SCOPE_DENIED", "患者不在授权科室范围");
  }

  public <T extends Base> T get(String id, Class<T> type) {
    T t = db.findById(id, type);
    if (t == null) throw ApiException.notFound();
    authorize(t);
    return t;
  }

  public void sameVersion(Base t, Long v) {
    if (!Objects.equals(t.version, v)) throw ApiException.conflict("数据版本已变化，请刷新");
  }

  public void init(Base b, Patient p, Instant t) {
    b.id = UUID.randomUUID().toString();
    b.patientId = p.id;
    b.encounterId = p.encounterId;
    b.department = p.department;
    b.simulated = p.simulated;
    b.createdAt = t;
  }

  public String fingerprint(String path, Object payload) {
    try {
      return HexFormatCompat.sha(json.writeValueAsString(List.of(path, payload)));
    } catch (Exception e) {
      throw new IllegalArgumentException("Cannot encode request", e);
    }
  }

  public Command replay(String actor, String key, String fp) {
    Command c = db.findById(commandId(actor, key), Command.class);
    if (c != null && !fp.equals(c.fingerprint)) throw ApiException.conflict("幂等键已被不同请求使用");
    return c;
  }

  public void command(
      String actor, String key, String fp, String entity, String type, Instant now) {
    Command c = new Command();
    c.id = commandId(actor, key);
    c.fingerprint = fp;
    c.entityId = entity;
    c.entityType = type;
    c.createdAt = now;
    db.insert(c);
  }

  private String commandId(String actor, String key) {
    return HexFormatCompat.sha(actor + ":" + key);
  }

  public Map<String, Object> state(Base b) {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("version", b.version);
    if (b instanceof Alert) {
      Alert a = (Alert) b;
      r.put("status", a.status);
      r.put("reason", a.reason);
    }
    if (b instanceof Task) {
      Task t = (Task) b;
      r.put("applicability", t.applicability);
      r.put("executionStatus", t.executionStatus);
      r.put("targetStatus", t.targetStatus);
      r.put("clinicalReviewStatus", t.clinicalReviewStatus);
      r.put("completedAt", t.completedAt);
    }
    return r;
  }

  public void record(
      Base b,
      String type,
      String action,
      String actor,
      String reason,
      Map<String, Object> before,
      Instant now) {
    History h = new History();
    Patient p = patient(b.patientId);
    init(h, p, now);
    h.entityType = type;
    h.entityId = b.id;
    h.action = action;
    h.actor = actor;
    h.reason = reason;
    h.before = before;
    h.after = state(b);
    db.insert(h);
    Audit a = new Audit();
    init(a, p, now);
    a.entityType = type;
    a.entityId = b.id;
    a.action = action;
    a.actor = actor;
    a.reason = reason;
    a.traceId = org.slf4j.MDC.get("traceId");
    db.insert(a);
  }

  /** History + audit for system-initiated writes (background sweep has no request grant). */
  public void recordSystem(Base b, Patient p, String type, String action, String actor, String reason, Instant now) {
    History h = new History();
    init(h, p, now);
    h.entityType = type; h.entityId = b.id; h.action = action; h.actor = actor; h.reason = reason;
    h.before = Map.of(); h.after = state(b);
    db.insert(h);
    Audit a = new Audit();
    init(a, p, now);
    a.entityType = type; a.entityId = b.id; a.action = action; a.actor = actor; a.reason = reason;
    a.traceId = org.slf4j.MDC.get("traceId");
    db.insert(a);
  }

  private static class HexFormatCompat {
    static String sha(String input) {
      try {
        byte[] b =
            MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder s = new StringBuilder();
        for (byte v : b) s.append(String.format("%02x", v & 255));
        return s.toString();
      } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
