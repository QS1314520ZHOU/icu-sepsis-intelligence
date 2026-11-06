package org.icu.sepsis.ai;
import java.time.*;
import java.util.*;
import org.icu.sepsis.application.Store;
import org.springframework.stereotype.Service;
import org.springframework.data.mongodb.core.query.Query;
@Service
public class AiUsageStatsService {
 private final Store store;private final Clock clock;
 public AiUsageStatsService(Store store,Clock clock){this.store=store;this.clock=clock;}
 public Map<String,Object> report(int days){int count=Math.max(1,Math.min(31,days));Instant to=clock.instant(),from=to.minus(Duration.ofDays(count));
  Query query=Query.query(store.departmentScope().and("createdAt").gte(from).lte(to));
  query.fields().include("id").include("patientId").include("encounterId").include("status").include("createdAt").include("feedback.verdict").include("feedback.at").include("feedback.reviewSeconds").include("feedback.durationBasis");
  Map<String,Object> out=aggregate(store.db.find(query,AiInteraction.class),to);out.put("from",from);out.put("to",to);out.put("days",count);return out;
 }
 static Instant instant(Object raw){try{return raw instanceof Instant?(Instant)raw:raw instanceof Date?((Date)raw).toInstant():raw instanceof String?Instant.parse((String)raw):null;}catch(DateTimeException e){return null;}}
 public static Map<String,Object> aggregate(List<AiInteraction> records,Instant asOf){
  Map<String,AiInteraction> unique=new LinkedHashMap<>();int unknownId=0;for(var record:records){if(record.id==null){unknownId++;continue;}unique.putIfAbsent(record.id,record);}
  int ok=0,unavailable=0,accept=0,edit=0,reject=0,unknown=0,noFeedback=0,timingCount=0,feedbackEvents=0;long seconds=0;Set<String> patients=new HashSet<>(),reviewed=new HashSet<>();
  for(var record:unique.values()){
   if(record.patientId!=null&&record.encounterId!=null&&!"ward".equals(record.patientId))patients.add(record.patientId+"|"+record.encounterId);
   if(!"ok".equals(record.status)){unavailable++;continue;}ok++;
   Map<String,Object> latest=null;Instant latestAt=null;boolean ambiguous=false;boolean invalid=false;
   if(record.feedback!=null)for(var feedback:record.feedback){Instant at=instant(feedback.get("at"));if(at==null||record.createdAt!=null&&at.isBefore(record.createdAt)){invalid=true;continue;}if(at.isAfter(asOf))continue;feedbackEvents++;
    if(latestAt==null||at.isAfter(latestAt)){latest=feedback;latestAt=at;ambiguous=false;}
    else if(at.equals(latestAt)&&!Objects.equals(latest.get("verdict"),feedback.get("verdict")))ambiguous=true;
   }
   if(latest==null){if(invalid)unknown++;else noFeedback++;continue;}
   if(ambiguous||!Set.of("accept","edit","reject").contains(Objects.toString(latest.get("verdict"),""))){unknown++;continue;}
   String verdict=(String)latest.get("verdict");if("accept".equals(verdict))accept++;else if("edit".equals(verdict))edit++;else reject++;
   if(record.patientId!=null&&record.encounterId!=null&&!"ward".equals(record.patientId))reviewed.add(record.patientId+"|"+record.encounterId);
   Object duration=latest.get("reviewSeconds");if("self_reported".equals(latest.get("durationBasis"))&&duration instanceof Number){double value=((Number)duration).doubleValue();if(Double.isFinite(value)&&value>=0&&value<=14400&&value==Math.rint(value)){timingCount++;seconds+=(long)value;}}
  }
  int feedback=accept+edit+reject;Map<String,Object> out=new LinkedHashMap<>();out.put("ruleVersion","ai-usage-v1");out.put("interactions",unique.size());out.put("missingId",unknownId);out.put("successful",ok);out.put("unavailable",unavailable);out.put("patients",patients.size());out.put("patientsWithFeedback",reviewed.size());out.put("feedbackEvents",feedbackEvents);out.put("withFeedback",feedback);out.put("accepted",accept);out.put("edited",edit);out.put("rejected",reject);out.put("unknownFeedback",unknown);out.put("noFeedback",noFeedback);out.put("feedbackRate",ok==0?null:(double)feedback/ok);out.put("acceptedAmongFeedback",feedback==0?null:(double)accept/feedback);out.put("timedFeedback",timingCount);out.put("selfReportedSeconds",seconds);out.put("meanSelfReportedSeconds",timingCount==0?null:(double)seconds/timingCount);out.put("clinicalLeadTimeMinutes",null);
  out.put("scope","按 AI 生成记录创建时间筛选，生成记录 ID 去重、每次生成取统计时点前最后一条有效反馈；重复生成分别计数，不冒充临床事件去重。");
  out.put("rule","反馈率分母为成功生成次数，分子为明确采纳、修改或不采纳的次数；未知和无反馈单列。采纳只表示用户反馈，不代表已执行或临床有效。");
  out.put("limitations",List.of("人工耗时仅统计主动填报的最后一次反馈，不以模型延迟或页面停留时间代替工作量；未填报不补零。","患者数按患者及住院去重，科室整体生成不计入患者数；科室生成仍计入生成次数。","没有可靠临床事件时间，无法计算提前量或因果改善。"));return out;
 }
}
