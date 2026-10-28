package org.icu.sepsis.api;

import java.util.*;
import org.icu.sepsis.domain.Domain.*;

public final class Views {
  private Views() {}

  public static Map<String, Object> map(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
    return m;
  }

  public static Map<String, Object> base(Base b) {
    return map(
        "id",
        b.id,
        "version",
        b.version,
        "patientId",
        b.patientId,
        "encounterId",
        b.encounterId,
        "createdAt",
        b.createdAt,
        "simulated",
        b.simulated);
  }

  public static Map<String, Object> patient(Patient p) {
    Map<String, Object> v = base(p);
    v.putAll(
        map(
            "label",
            p.label,
            "bed",
            p.bed,
            "department",
            p.department,
            "admittedAt",
            p.admittedAt));
    return v;
  }

  public static Map<String, Object> observation(Observation o) {
    return map(
        "id",
        o.id,
        "parameterCode",
        o.parameterCode,
        "value",
        o.value,
        "text",
        o.text,
        "unit",
        o.unit,
        "eventTime",
        o.eventTime,
        "ingestTime",
        o.ingestTime,
        "sourceSystem",
        o.sourceSystem,
        "sourceRecordId",
        o.sourceRecordId,
        "qualityStatus",
        o.qualityStatus,
        "simulated",
        o.simulated);
  }

  public static Map<String, Object> prediction(Assessment a, Prediction p) {
    return map(
        "predictionId",
        a.id + "-h" + p.horizonHours,
        "patientId",
        a.patientId,
        "encounterId",
        a.encounterId,
        "predictionTime",
        a.assessmentTime,
        "modelName",
        p.modelName,
        "modelVersion",
        p.modelVersion,
        "featureVersion",
        p.featureVersion,
        "calibrationVersion",
        p.calibrationVersion,
        "riskLevel",
        p.riskLevel,
        "dataQualityStatus",
        a.dataQualityStatus,
        "missingFeatures",
        a.missingFeatures,
        "horizonHours",
        p.horizonHours,
        "predictionSource",
        p.predictionSource,
        "riskValueType",
        p.riskValueType,
        "riskValue",
        p.riskValue,
        "modelStatus",
        p.modelStatus,
        "localValidationStatus",
        p.localValidationStatus,
        "targetEvent",
        p.targetEvent,
        "reason",
        p.reason);
  }

  public static Map<String, Object> assessment(Assessment a) {
    Map<String, Object> v = base(a);
    v.putAll(
        map(
            "assessmentTime",
            a.assessmentTime,
            "ruleVersion",
            a.ruleVersion,
            "infectionVerdict",
            a.infectionVerdict,
            "screeningStatus",
            a.screeningStatus,
            "shockScreeningStatus",
            a.shockScreeningStatus,
            "dataQualityStatus",
            a.dataQualityStatus,
            "ruleScore",
            a.ruleScore,
            "ruleScoreName",
            "qSOFA筛查分数（0–3，非概率）",
            "missingFeatures",
            a.missingFeatures,
            "evidence",
            a.evidence.stream().map(Views::observation).toArray(),
            "predictions",
            a.predictions.stream().map(p -> prediction(a, p)).toArray(),
            "sofaStatus",
            a.sofaStatus,
            "volumeAdequacy",
            a.volumeAdequacy,
            "otherCausesExcluded",
            a.otherCausesExcluded));
    return v;
  }

  public static Map<String, Object> alert(Alert a) {
    Map<String, Object> v = base(a);
    v.putAll(
        map(
            "status",
            a.status,
            "severity",
            a.severity,
            "assessmentId",
            a.assessmentId,
            "ruleVersion",
            a.ruleVersion,
            "reason",
            a.reason,
            "acknowledgedAt",
            a.acknowledgedAt,
            "closedAt",
            a.closedAt));
    return v;
  }

  public static Map<String, Object> task(Task t) {
    Map<String, Object> v = base(t);
    v.putAll(
        map(
            "alertId",
            t.alertId,
            "itemCode",
            t.itemCode,
            "itemName",
            t.itemName,
            "bundleVersion",
            t.bundleVersion,
            "approvalStatus",
            t.approvalStatus,
            "applicability",
            t.applicability,
            "executionStatus",
            t.executionStatus,
            "targetStatus",
            t.targetStatus,
            "clinicalReviewStatus",
            t.clinicalReviewStatus,
            "triggerTime",
            t.triggerTime,
            "dueAt",
            t.dueAt,
            "completedAt",
            t.completedAt,
            "evidenceId",
            t.evidenceId,
            "reason",
            t.reason));
    return v;
  }

  public static Map<String, Object> history(History h) {
    return map(
        "id",
        h.id,
        "entityId",
        h.entityId,
        "action",
        h.action,
        "actor",
        h.actor,
        "reason",
        h.reason,
        "createdAt",
        h.createdAt,
        "before",
        h.before,
        "after",
        h.after);
  }
}
