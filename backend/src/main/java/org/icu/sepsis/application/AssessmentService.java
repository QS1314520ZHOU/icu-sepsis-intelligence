package org.icu.sepsis.application;

import java.time.*;
import java.util.*;
import org.icu.sepsis.api.*;
import org.icu.sepsis.config.RuleConfig;
import org.icu.sepsis.domain.*;
import org.icu.sepsis.domain.Domain.*;
import org.icu.sepsis.integration.ClinicalDataProvider;
import org.icu.sepsis.prediction.RiskModelProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssessmentService {
  private final Store store;
  private final ClinicalDataProvider data;
  private final ClinicalRules rules;
  private final RiskModelProvider model;
  private final RuleConfig config;
  private final Clock clock;
  private final boolean demo;

  public AssessmentService(
      Store s,
      ClinicalDataProvider d,
      ClinicalRules r,
      RiskModelProvider m,
      RuleConfig c,
      Clock k,
      @Value("${app.demo}") boolean dem) {
    store = s;
    data = d;
    rules = r;
    model = m;
    config = c;
    clock = k;
    demo = dem;
  }

  @Transactional
  public Assessment assess(String pid, Requests.Assess req, String key, String actor) {
    Patient p = store.patient(pid);
    Instant now = clock.instant();
    String fp = store.fingerprint("assess/" + pid, req);
    Command prior = store.replay(actor, key, fp);
    if (prior != null) return store.get(prior.entityId, Assessment.class);
    if (!demo || !p.simulated || !"DRAFT_DEMO_ONLY".equals(config.getApproval()))
      throw new ApiException(
          org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
          "CLINICAL_APPROVAL_REQUIRED",
          "当前只支持明确标记的模拟数据与演示规则，不能用于真实患者");
    if (req.asOf.isAfter(now)
        || req.asOf.isBefore(now.minus(Duration.ofDays(7)))
        || req.asOf.isBefore(p.admittedAt))
      throw new ApiException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "INVALID_T0",
          "T0必须在本次住院内且不超过最近7天，不能位于未来");
    Assessment a =
        rules.assess(
            data.observations(p.id, p.encounterId, req.asOf.minus(Duration.ofHours(24)), req.asOf),
            req.asOf);
    store.init(a, p, now);
    a.commandKey = key;
    a.predictions = model.predict(a.evidence);
    store.db.insert(a);
    boolean shock = "suspected_requires_review".equals(a.shockScreeningStatus);
    boolean positive =
        shock
            || Set.of("hemodynamic_instability", "infection_with_abnormality")
                .contains(a.screeningStatus);
    if (positive) {
      String sev = shock ? "critical" : "high";
      String active =
          p.department
              + ":"
              + p.id
              + ":"
              + p.encounterId
              + ":"
              + config.getVersion()
              + ":"
              + sev
              + ":"
              + (now.getEpochSecond() / (60L * config.getSuppressionMinutes()));
      Alert existing =
          store.db.findOne(Query.query(Criteria.where("activeKey").is(active)), Alert.class);
      if (existing == null) {
        Alert alert = new Alert();
        store.init(alert, p, now);
        alert.assessmentId = a.id;
        alert.ruleVersion = config.getVersion();
        alert.severity = sev;
        alert.activeKey = active;
        alert.status = "pending";
        alert.reason = "演示筛查触发；非临床诊断";
        store.db.insert(alert);
        store.record(alert, "alert", "created", actor, alert.reason, Map.of(), now);
      } else {
        store.record(
            existing,
            "alert",
            "repeat_suppressed",
            actor,
            "相同住院/规则/严重程度已有活跃预警，新评估单独保留",
            store.state(existing),
            now);
      }
    }
    store.record(a, "assessment", "created", actor, "模拟数据评估；模型不可用", Map.of(), now);
    store.command(actor, key, fp, a.id, "assessment", now);
    return a;
  }
}
