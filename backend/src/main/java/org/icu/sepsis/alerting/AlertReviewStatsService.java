package org.icu.sepsis.alerting;
import java.time.*;
import java.util.*;
import org.icu.sepsis.application.Store;
import org.icu.sepsis.domain.Domain.Alert;
import org.springframework.stereotype.Service;
import org.springframework.data.mongodb.core.query.*;
@Service
public class AlertReviewStatsService {
  private final Store store;private final Clock clock;
  public AlertReviewStatsService(Store store,Clock clock){this.store=store;this.clock=clock;}
  public Map<String,Object> report(int days){int count=Math.max(1,Math.min(31,days));Instant to=clock.instant(),from=to.minus(Duration.ofDays(count));
    List<Alert> alerts=store.db.find(Query.query(store.departmentScope().and("createdAt").gte(from).lte(to)),Alert.class);
    Map<String,Object> out=aggregate(alerts);out.put("from",from);out.put("to",to);out.put("days",count);return out;}
  public static Map<String,Object> aggregate(List<Alert> alerts){Map<String,Alert> chains=new HashMap<>();Set<String> patients=new HashSet<>();int missing=0;
    Comparator<Alert> latest=Comparator.comparing((Alert a)->a.createdAt,Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(a->Objects.toString(a.id,""));
    for(Alert a:alerts){String patient=Objects.toString(a.patientId,"unknown:"+a.id)+"|"+Objects.toString(a.encounterId,"unknown:"+a.id);patients.add(patient);
      String root=a.reviewChainId!=null?a.reviewChainId:a.id!=null?a.id:"missing:"+(missing++);
      chains.merge(patient+"|"+root,a,(x,y)->latest.compare(x,y)>=0?x:y);}
    int feedback=0,pending=0,reReview=0,legacy=0,falsePositive=0,rejected=0,confirmed=0;Set<String> reviewedPatients=new HashSet<>();
    for(Alert a:chains.values()){
      if(a.needsReReview){reReview++;continue;}
      if(a.reviewedAt!=null){feedback++;reviewedPatients.add(a.patientId+"|"+a.encounterId);}
      else if(!"pending".equals(a.status)){legacy++;continue;}else pending++;
      if("confirmed_false_positive".equals(a.reviewOutcome))falsePositive++;
      else if("rejected_only".equals(a.reviewOutcome))rejected++;
      else if("confirmed_screen".equals(a.reviewOutcome))confirmed++;
    }
    Map<String,Object> out=new LinkedHashMap<>();out.put("ruleVersion","alert-review-coverage-v1");out.put("records",alerts.size());out.put("patients",patients.size());
    out.put("reviewChains",chains.size());out.put("withRecordedFeedback",feedback);out.put("awaitingFeedback",pending);out.put("needsReReview",reReview);
    out.put("historicalUnknown",legacy);out.put("confirmedFalsePositive",falsePositive);out.put("rejectedOnly",rejected);out.put("confirmedScreen",confirmed);
    out.put("patientsWithFeedback",reviewedPatients.size());int denominator=chains.size()-legacy;out.put("feedbackDenominator",denominator);
    out.put("feedbackRate",denominator==0?null:(double)feedback/denominator);out.put("leadTimeAvailable",false);out.put("leadTimeMinutes",null);
    out.put("scope","按预警创建时间筛选、患者住院及软件复核链去重；同链取最新记录。不是临床事件发生率或误报率。");
    out.put("rule","反馈率分子为最新记录有明确复核时间且无需重新复核的链；分母为全部链扣除历史反馈不明链。接收/暂缓也属于反馈，不等于确认筛查。旧驳回不推断为确认误报；各结果仅取明确字段。");
    out.put("limitations",List.of("跨统计范围的旧链只统计范围内记录，未恢复临床事件起止时间。","没有可靠临床事件时间，暂不计算提前量。","此统计不能证明 AI 改善临床结局；AI 采纳和人工操作耗时仍需独立统计。"));return out;}
}
