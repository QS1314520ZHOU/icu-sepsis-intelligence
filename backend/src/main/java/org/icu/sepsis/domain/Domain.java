package org.icu.sepsis.domain;

import java.time.Instant;
import java.util.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

public final class Domain {
  private Domain() {}

  public static class Base {
    @Id public String id;
    @Version public Long version;
    public String patientId, encounterId, department;
    public Instant createdAt;
    public boolean simulated;
  }

  @Document("sepsis_patient_context")
  public static class Patient extends Base {
    public String label, bed;
    public Instant admittedAt;
  }

  @Document("sepsis_clinical_observation")
  public static class Observation extends Base {
    public String parameterCode, unit, sourceSystem, sourceRecordId, qualityStatus, text;
    public Double value;
    public Instant eventTime, ingestTime;
  }

  @Document("sepsis_risk_assessment")
  public static class Assessment extends Base {
    public String ruleVersion,
        infectionVerdict,
        screeningStatus,
        shockScreeningStatus,
        dataQualityStatus,
        commandKey;
    public Instant assessmentTime;
    public Integer ruleScore;
    public List<String> missingFeatures = new ArrayList<>(),
        conflictingEvidence = new ArrayList<>();
    public List<Observation> evidence = new ArrayList<>();
    public List<Prediction> predictions = new ArrayList<>();
    public String sofaStatus = "not_implemented",
        volumeAdequacy = "unknown",
        otherCausesExcluded = "unknown";
  }

  public static class Prediction {
    public int horizonHours;
    public String modelName, modelVersion, featureVersion, calibrationVersion;
    public String riskLevel = "unknown";
    public String predictionSource = "unavailable",
        riskValueType = null,
        modelStatus = "weight_missing",
        localValidationStatus = "not_validated",
        targetEvent = "sepsis_related_deterioration_UNAPPROVED",
        reason = "No verified model package or approved target definition";
    public Double riskValue = null;
  }

  @Document("sepsis_alert_event")
  public static class Alert extends Base {
    public String assessmentId, status, ruleVersion, severity, activeKey, reason;
    public Instant acknowledgedAt, closedAt;
  }

  @Document("sepsis_bundle_task")
  public static class Task extends Base {
    public String alertId,
        itemCode,
        itemName,
        bundleVersion,
        approvalStatus,
        applicability = "review_pending",
        executionStatus = "not_started",
        targetStatus = "not_assessed",
        clinicalReviewStatus = "pending",
        evidenceId,
        reason;
    public Instant triggerTime, dueAt, completedAt;
  }

  @Document("sepsis_bundle_execution")
  public static class Execution extends Base {
    public String taskId, sourceRecordId, sourceSystem, actor;
    public Instant eventTime, ingestTime;
  }

  @Document("sepsis_state_history")
  public static class History extends Base {
    public String entityType, entityId, action, actor, reason;
    public Map<String, Object> before, after;
  }

  @Document("sepsis_audit_log")
  public static class Audit extends Base {
    public String entityType, entityId, action, actor, reason, traceId;
  }

  @Document("sepsis_command")
  public static class Command {
    @Id public String id;
    public String fingerprint, entityId, entityType;
    public Instant createdAt;
  }
}
