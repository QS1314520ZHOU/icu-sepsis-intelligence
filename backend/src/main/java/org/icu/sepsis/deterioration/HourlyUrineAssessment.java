package org.icu.sepsis.deterioration;
import java.time.*;import java.util.*;import java.util.stream.Collectors;
import org.icu.sepsis.domain.Domain.Observation;
/** Hospital-confirmed hourly volumes. Hour labels are preserved; no inferred sampling start time. */
final class HourlyUrineAssessment {
 static Map<String,Object> evaluate(List<Observation> rows,Instant now,boolean readable){
  var out=new LinkedHashMap<String,Object>();out.put("status","unknown");out.put("stageReference",null);
  out.put("basis","院内确认param_niaoLiang每条为一小时尿量；按连续小时记录核对，不补零、不推断真实区间起点");
  var windows=new ArrayList<Map<String,Object>>();out.put("windows",windows);
  var urine=rows.stream().filter(o->"URINE_OUTPUT".equals(o.parameterCode)&&"param_niaoLiang".equals(o.sourceParameterCode)&&o.eventTime!=null&&!o.eventTime.isAfter(now)&&(o.ingestTime==null||!o.ingestTime.isAfter(now))&&!o.eventTime.isBefore(now.minus(Duration.ofHours(25)))).collect(Collectors.toList());
  if(!readable||urine.isEmpty()){out.put("detail",readable?"未取得逐小时尿量":"尿量来源读取失败");return out;}
  var last=urine.stream().map(o->o.eventTime).max(Comparator.naturalOrder()).orElseThrow();out.put("lastHourLabel",last);
  if(last.isBefore(now.minus(Duration.ofHours(1)))){out.put("detail","末条逐小时尿量超过1小时，仅供历史核对");return out;}
  int stage=0;boolean sixComplete=false;
  for(int hours:new int[]{6,12,24}){
   var w=new LinkedHashMap<String,Object>();w.put("hours",hours);w.put("status","unknown");w.put("firstHourLabel",last.minus(Duration.ofHours(hours-1)));w.put("lastHourLabel",last);windows.add(w);
   if(urine.stream().anyMatch(o->!o.eventTime.isBefore(last.minus(Duration.ofHours(hours-1)))&&Duration.between(o.eventTime,last).toMillis()%3600000!=0)){w.put("coveredHours",0);w.put("reason","窗口内存在未对齐小时记录，未挑选或合并");continue;}
   double total=0;int covered=0;String gap="";
   for(int i=0;i<hours;i++){Instant label=last.minus(Duration.ofHours(i));var points=urine.stream().filter(o->o.eventTime.equals(label)).collect(Collectors.toList());
    if(points.isEmpty()){gap="缺小时记录，不补零";continue;}
    var point=points.get(0);boolean bad=points.stream().anyMatch(o->o.value==null||!Double.isFinite(o.value)||o.value<0||!"valid".equals(o.qualityStatus)||"context_only".equals(o.clinicalUse)||"unconfirmed".equals(o.encounterLinkStatus)||!("ml".equalsIgnoreCase(o.unit)||"mL".equals(o.unit))||!Objects.equals(o.value,point.value));
    if(bad){gap="小时记录无效、单位不明或同刻冲突";continue;}total+=point.value;covered++;
   }
   w.put("coveredHours",covered);if(!Double.isFinite(total)){w.put("reason","总量超出有效数值范围");continue;}if(covered!=hours){w.put("reason",gap);continue;}
   // A later weight must not be backfilled across an earlier urine window.
   Instant first=last.minus(Duration.ofHours(hours-1));var weight=OrganContext.latest(rows,"WEIGHT",first,Duration.ofDays(7));
   if(weight==null||!"kg".equals(weight.unit)||weight.value<=0){w.put("reason","窗口开始时缺少有效kg体重，不能用后测体重回填");continue;}
   double rate=total/(weight.value*hours);int reference=hours==24&&rate<.3?3:hours==12&&total==0?3:hours==12&&rate<.5?2:hours==6&&rate<.5?1:0;
   stage=Math.max(stage,reference);if(hours==6)sixComplete=true;
   w.put("status",reference>0?"candidate":"not_triggered");w.put("totalMl",total);w.put("mlKgHour",rate);w.put("weightKg",weight.value);w.put("weightTime",weight.eventTime);w.put("stageReference",reference==0?null:reference);
  }
  out.put("status",stage>0?"candidate":sixComplete?"not_triggered":"unknown");out.put("stageReference",stage==0?null:stage);
  out.put("detail",stage>0?"连续小时尿量达到KDIGO尿量候选分层条件，需核对导尿、梗阻、液体与临床背景；非完整AKI分期":sixComplete?"完整六小时尿量窗口未触发；较长窗口与肌酐仍需联合复核，不能排除AKI":"连续小时覆盖或体重不足，未作尿量分层");return out;
 }
}
