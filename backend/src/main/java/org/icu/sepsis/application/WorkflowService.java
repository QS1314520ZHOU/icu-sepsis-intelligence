package org.icu.sepsis.application;

import java.time.*;
import java.util.*;
import org.icu.sepsis.api.*;
import org.icu.sepsis.config.RuleConfig;
import org.icu.sepsis.domain.*;
import org.icu.sepsis.domain.Domain.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowService {
  private final Store store;
  private final Clock clock;
  private final RuleConfig cfg;
  private final ClinicalRules rules;

  public WorkflowService(Store s, Clock c, RuleConfig r, ClinicalRules cr) {
    store = s;
    clock = c;
    cfg = r;
    rules = cr;
  }

  @Transactional
  public Alert reviewAlert(String id, Requests.Review req, String key, String actor) {
    Alert a = store.get(id, Alert.class);
    String fp = store.fingerprint("alert/" + id, req);
    Command replay = store.replay(actor, key, fp);
    if (replay != null) return a;
    store.sameVersion(a, req.expectedVersion);
    Map<String, Object> before = store.state(a);
    Instant now = clock.instant();
    if ("closed".equals(a.status) || "rejected".equals(a.status))
      throw ApiException.conflict("已结束预警不能继续流转");
    switch (req.action) {
      case "acknowledge":
        if (!Set.of("pending", "deferred").contains(a.status))
          throw ApiException.conflict("当前状态不能确认接收");
        a.status = "acknowledged";
        if (a.acknowledgedAt == null) a.acknowledgedAt = now;
        break;
      case "confirm":
        if (!Set.of("pending", "acknowledged", "deferred").contains(a.status))
          throw ApiException.conflict("当前状态不能确认筛查");
        a.status = "confirmed";
        if (a.acknowledgedAt == null) a.acknowledgedAt = now;
        // Draft demo task only for simulated patients; real patients: confirmation is recorded on the alert itself.
        if (store.patient(a.patientId).simulated) createTask(a, actor, req.reason, now);
        break;
      case "reject":
        if (!Set.of("pending", "acknowledged", "deferred").contains(a.status))
          throw ApiException.conflict("当前状态不能驳回");
        a.status = "rejected";
        a.activeKey = null;
        a.closedAt = now;
        if (a.acknowledgedAt == null) a.acknowledgedAt = now;
        break;
      case "defer":
        if (!Set.of("pending", "acknowledged").contains(a.status))
          throw ApiException.conflict("当前状态不能暂缓");
        a.status = "deferred";
        if (a.acknowledgedAt == null) a.acknowledgedAt = now;
        break;
      case "close":
        if (!"confirmed".equals(a.status)) throw ApiException.conflict("仅已确认预警可关闭");
        a.status = "closed";
        a.closedAt = now;
        a.activeKey = null;
        break;
      default:
        throw new ApiException(
            org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_ACTION", "不支持的预警动作");
    }
    a.reason = req.reason;
    store.db.save(a);
    store.record(a, "alert", req.action, actor, req.reason, before, now);
    store.command(actor, key, fp, a.id, "alert", now);
    return a;
  }

  private void createTask(Alert a, String actor, String reason, Instant now) {
    Patient p = store.patient(a.patientId);
    if (!p.simulated) throw ApiException.conflict("草案Bundle禁止用于真实患者");
    Task t = new Task();
    store.init(t, p, now);
    t.alertId = a.id;
    t.itemCode = "CLINICIAN_REASSESSMENT_DEMO";
    t.itemName = "临床复评记录（模拟演示，非指南治疗时限）";
    t.bundleVersion = cfg.getVersion();
    t.approvalStatus = "DRAFT_DEMO_ONLY";
    t.triggerTime = now;
    t.dueAt = now.plus(Duration.ofMinutes(cfg.getReviewDeadlineMinutes()));
    t.reason = reason;
    store.db.insert(t);
    store.record(t, "task", "created", actor, "医生确认筛查后创建演示复评待审核任务", Map.of(), now);
  }

  @Transactional
  public Task execute(String id, Requests.Execute req, String key, String actor) {
    Task t = store.get(id, Task.class);
    String fp = store.fingerprint("execute/" + id, req);
    if (store.replay(actor, key, fp) != null) return t;
    store.sameVersion(t, req.expectedVersion);
    if (Set.of("not_applicable", "contraindicated").contains(t.applicability)
        || "cancelled".equals(t.executionStatus)) throw ApiException.conflict("不适用、禁忌或取消任务不可登记执行");
    if (t.evidenceId != null) throw ApiException.conflict("已有执行记录；纠正流程尚未开放，不能覆盖原记录");
    Instant now = clock.instant();
    Patient p = store.patient(t.patientId);
    if (req.eventTime.isAfter(now) || req.eventTime.isBefore(p.admittedAt))
      throw new ApiException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "INVALID_EVENT_TIME",
          "执行时间不得在未来或早于本次住院");
    Map<String, Object> before = store.state(t);
    Execution e = new Execution();
    store.init(e, p, now);
    e.taskId = t.id;
    e.eventTime = req.eventTime;
    e.ingestTime = now;
    e.sourceRecordId = req.sourceRecordId;
    e.sourceSystem = req.sourceSystem;
    e.actor = actor;
    store.db.insert(e);
    t.evidenceId = e.id;
    t.completedAt = e.eventTime;
    t.executionStatus = rules.executionStatus(e.eventTime, t.triggerTime, t.dueAt);
    t.clinicalReviewStatus = "pending";
    t.reason = req.reason;
    store.db.save(t);
    store.record(t, "task", "execution_recorded", actor, req.reason, before, now);
    store.command(actor, key, fp, t.id, "task", now);
    return t;
  }

  @Transactional
  public Task reviewTask(String id, Requests.TaskReview req, String key, String actor) {
    Task t = store.get(id, Task.class);
    String fp = store.fingerprint("task-review/" + id, req);
    if (store.replay(actor, key, fp) != null) return t;
    store.sameVersion(t, req.expectedVersion);
    if (!"confirm".equals(req.action)
        || !Set.of(
                "required",
                "conditional",
                "individualized",
                "not_applicable",
                "contraindicated",
                "review_pending")
            .contains(req.applicability)
        || !Set.of("met", "not_met", "unknown", "not_assessed").contains(req.targetStatus))
      throw new ApiException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_TASK_REVIEW", "不支持的审核状态");
    if ("met".equals(req.targetStatus) && t.evidenceId == null)
      throw ApiException.conflict("缺乏执行证据不能确认目标达成");
    Instant now = clock.instant();
    Map<String, Object> before = store.state(t);
    t.applicability = req.applicability;
    t.targetStatus = req.targetStatus;
    t.clinicalReviewStatus = "review_pending".equals(t.applicability) ? "pending" : "confirmed";
    t.reason = req.reason;
    store.db.save(t);
    store.record(t, "task", "reviewed", actor, req.reason, before, now);
    store.command(actor, key, fp, t.id, "task", now);
    return t;
  }
}
