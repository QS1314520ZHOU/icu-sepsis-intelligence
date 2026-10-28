package org.icu.sepsis.application;

import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.icu.sepsis.api.*;
import org.icu.sepsis.domain.Domain.*;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Service;

@Service
public class QueryService {
  private final Store s;

  private final org.icu.sepsis.domain.ClinicalRules rules;

  public QueryService(Store st, org.icu.sepsis.domain.ClinicalRules clinicalRules) {
    s = st;
    rules = clinicalRules;
  }

  public <T> Map<String, Object> page(
      Criteria c, Class<T> type, int page, int size, Function<T, Map<String, Object>> view) {
    long count = s.db.count(Query.query(c), type);
    List<T> rows =
        s.db.find(
            Query.query(c)
                .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .skip((long) page * size)
                .limit(size),
            type);
    return Views.map(
        "items", rows.stream().map(view).toArray(), "total", count, "page", page, "size", size);
  }

  public Criteria scope() {
    return s.scope();
  }

  public Criteria tasks(String metric, Instant from, Instant to) {
    Criteria c = s.scope();
    if (from != null || to != null) {
      if (from == null
          || to == null
          || !from.isBefore(to)
          || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0)
        throw new ApiException(
            org.springframework.http.HttpStatus.BAD_REQUEST,
            "INVALID_PERIOD",
            "任务筛选须提供完整且不超过31天的时间窗");
      c.and("triggerTime").gte(from).lt(to);
    }
    if (metric != null && !metric.isBlank())
      switch (metric) {
        case "den":
          c.and("clinicalReviewStatus")
              .is("confirmed")
              .and("applicability")
              .in("required", "conditional", "individualized");
          break;
        case "onTime":
          c.and("clinicalReviewStatus")
              .is("confirmed")
              .and("applicability")
              .in("required", "conditional", "individualized")
              .and("executionStatus")
              .is("met");
          break;
        case "late":
          c.and("clinicalReviewStatus")
              .is("confirmed")
              .and("applicability")
              .in("required", "conditional", "individualized")
              .and("executionStatus")
              .is("met_late");
          break;
        case "pending":
          c.and("clinicalReviewStatus").ne("confirmed");
          break;
        case "open":
          c.and("evidenceId")
              .is(null)
              .and("applicability")
              .nin("not_applicable", "contraindicated");
          break;
        default:
          throw new ApiException(
              org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_METRIC", "未知任务筛选口径");
      }
    return c;
  }

  public Map<String, Object> overview() {
    Instant from = Instant.now().minus(Duration.ofHours(24));
    return Views.map(
        "patientCount",
        s.db.count(Query.query(s.scope()), Patient.class),
        "pendingAlerts",
        s.db.count(Query.query(s.scope().and("status").is("pending")), Alert.class),
        "openTasks",
        s.db.count(Query.query(tasks("open", null, null)), Task.class),
        "incompleteAssessments24h",
        s.db.count(
            Query.query(
                s.scope().and("createdAt").gte(from).and("dataQualityStatus").is("incomplete")),
            Assessment.class),
        "updatedAt",
        Instant.now(),
        "simulated",
        true);
  }

  public Map<String, Object> patients(String search, int p, int z) {
    Criteria c = s.scope();
    if (search != null && !search.isBlank())
      c.andOperator(
          new Criteria()
              .orOperator(
                  Criteria.where("label").regex(java.util.regex.Pattern.quote(search), "i"),
                  Criteria.where("bed").regex(java.util.regex.Pattern.quote(search), "i")));
    return page(c, Patient.class, p, z, this::patientWorkspace);
  }

  private Map<String, Object> patientWorkspace(Patient p) {
    Instant now = Instant.now();
    Map<String, Object> v = Views.patient(p);
    Map<String, Object> vitals = new LinkedHashMap<>();
    // Bounded per-patient summaries. Current readings must pass the screening freshness contract.
    List<Observation> observations =
        s.db.find(
            Query.query(
                    s.scope()
                        .and("patientId")
                        .is(p.id)
                        .and("encounterId")
                        .is(p.encounterId)
                        .and("eventTime")
                        .gte(now.minus(Duration.ofHours(6)))
                        .lte(now)
                        .and("ingestTime")
                        .lte(now)
                        .and("parameterCode")
                        .in("HR", "MAP", "LACTATE"))
                .with(Sort.by(Sort.Direction.DESC, "eventTime"))
                .limit(1000),
            Observation.class);
    for (Observation o : observations)
      if (rules.valid(o, now) && !vitals.containsKey(o.parameterCode))
        vitals.put(o.parameterCode, Views.observation(o));
    Assessment last =
        s.db.findOne(
            Query.query(s.scope().and("patientId").is(p.id).and("encounterId").is(p.encounterId))
                .with(Sort.by(Sort.Direction.DESC, "createdAt")),
            Assessment.class);
    v.put("vitals", vitals);
    v.put("latestAssessment", last == null ? null : Views.assessment(last));
    v.put("summaryAt", now);
    v.put("summaryLimitReached", observations.size() >= 1000);
    return v;
  }

  @org.springframework.transaction.annotation.Transactional(readOnly = true)
  public Map<String, Object> quality(Instant from, Instant to) {
    if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0)
      throw new ApiException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "INVALID_PERIOD",
          "统计时间窗须大于0且不超过31天；按任务触发时间归属");
    long total = count(from, to, null, null);
    long denom = count(from, to, "den", null);
    long on = count(from, to, "den", "met"),
        late = count(from, to, "den", "met_late"),
        before = count(from, to, "den", "completed_before"),
        missing = count(from, to, "den", "data_missing");
    long excluded = count(from, to, "excluded", null);
    long pending = total - denom - excluded;
    return Views.map(
        "from",
        from,
        "to",
        to,
        "timeAnchor",
        "triggerTime",
        "metricVersion",
        "demo-task-compliance-v1",
        "totalTasks",
        total,
        "denominator",
        denom,
        "onTime",
        on,
        "late",
        late,
        "completedBefore",
        before,
        "dataMissing",
        missing,
        "notCompleted",
        denom - on - late - before - missing,
        "excluded",
        excluded,
        "pendingReview",
        pending,
        "onTimeRate",
        denom == 0 ? null : (double) on / denom,
        "lateRate",
        denom == 0 ? null : (double) late / denom,
        "definition",
        "仅已审核且适用任务进入分母；超时不计按时；补录按事件时间；审核待定与不适用/禁忌不入分母；触发前完成单列，不计按时。此为演示任务口径，非正式医院质控。模型效果指标未计算。");
  }

  private long count(Instant f, Instant t, String category, String execution) {
    Criteria c = s.scope().and("triggerTime").gte(f).lt(t);
    if ("den".equals(category))
      c.and("clinicalReviewStatus")
          .is("confirmed")
          .and("applicability")
          .in("required", "conditional", "individualized");
    if ("excluded".equals(category))
      c.and("clinicalReviewStatus")
          .is("confirmed")
          .and("applicability")
          .in("not_applicable", "contraindicated");
    if (execution != null) c.and("executionStatus").is(execution);
    return s.db.count(Query.query(c), Task.class);
  }
}
