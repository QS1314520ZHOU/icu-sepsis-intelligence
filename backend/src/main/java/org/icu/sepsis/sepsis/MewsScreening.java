package org.icu.sepsis.sepsis;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.icu.sepsis.domain.Domain.*;

/** Adult five-variable MEWS, recomputed only from bedside observations. No recorded score input. */
public final class MewsScreening {
 private MewsScreening(){}
 public static final String VERSION="bedside-mews-5var-2016-time-v2-temp4h";
 public static final String SOURCE="https://pmc.ncbi.nlm.nih.gov/articles/PMC4748572/";
 private static final Map<String,String> LABELS=Map.of("SBP","收缩压","HR","心率","RR","呼吸频率","TEMP","体温","AVPU","意识反应");
 public static Integer avpu(String text){if(text==null)return null;switch(text.trim().toUpperCase(Locale.ROOT)){case "A":case "ALERT":case "清醒":return 0;case "V":case "对声音有反应":case "对声音刺激有反应":return 1;case "P":case "对疼痛有反应":case "对疼痛刺激有反应":return 2;case "U":case "UNRESPONSIVE":case "无反应":case "对疼痛无反应":return 3;default:return null;}}
 public static Integer points(String code,Double value){if(value==null||!Double.isFinite(value))return null;double v=value;if(!"TEMP".equals(code)&&v!=Math.floor(v))return null;switch(code){case "SBP":return v<1||v>300?null:v<=70?3:v<=80?2:v<=100?1:v<200?0:2;case "HR":return v<1||v>300?null:v<=40?2:v<=50?1:v<=100?0:v<=110?1:v<=129?2:3;case "RR":return v<0||v>100?null:v<9?2:v<=14?0:v<=20?1:v<=29?2:3;case "TEMP":return v<20||v>45?null:v<=35?2:v<38.5?0:2;default:return null;}}
 public static Map<String,Object> evaluate(Patient p,List<Observation> input,Instant now,MewsProperties cfg){
  Map<String,Object> out=new LinkedHashMap<>();out.put("tool","MEWS");out.put("ruleVersion",VERSION);out.put("ruleSource",SOURCE);out.put("asOf",now);out.put("freshMinutes",cfg.getFreshMinutes());out.put("maxSkewMinutes",cfg.getMaxSkewMinutes());out.put("temperatureFreshMinutes",cfg.getTemperatureFreshMinutes());out.put("reviewThreshold",cfg.getReviewThreshold());out.put("policyStatus","LOCAL_REVIEW_DRAFT");out.put("historicalReplaySupported",false);
  String population=p.birthDate==null?"unknown":p.birthDate.isAfter(now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate())?"unknown":Period.between(p.birthDate,now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()).getYears()>=18?"adult":"not_adult";out.put("population",population);
  List<Observation> rows=input.stream().filter(o->"SmartCare.bedside".equals(o.sourceSystem))
   .filter(o->Objects.equals(p.id,o.patientId)&&Objects.equals(p.encounterId,o.encounterId)&&Objects.equals(p.department,o.department))
   .filter(o->o.eventTime!=null&&!o.eventTime.isAfter(now)&&(o.ingestTime==null||!o.ingestTime.isAfter(now)))
   .filter(o->p.admittedAt!=null&&!o.eventTime.isBefore(p.admittedAt)).collect(Collectors.toList());
  List<String> channels=new ArrayList<>();for(String c:List.of("SBP_IBP","SBP_NIBP"))if(rows.stream().anyMatch(o->c.equals(o.parameterCode)))channels.add(c);if(channels.isEmpty())channels.add("SBP");
  List<Map<String,Object>> results=new ArrayList<>();for(String channel:channels)results.add(channel(rows,channel,now,cfg,population,cfg.isEnabled()));out.put("channels",results);
  boolean triggered=results.stream().anyMatch(r->Boolean.TRUE.equals(r.get("triggered")));boolean allKnown=results.stream().allMatch(r->r.get("triggered") instanceof Boolean);
  out.put("triggered",triggered?Boolean.TRUE:allKnown?Boolean.FALSE:null);out.put("status",!cfg.isEnabled()?"disabled":triggered?"triggered":allKnown?"not_triggered":"unknown");
  out.put("label",!cfg.isEnabled()?"MEWS 未启用":triggered?"MEWS 达到复核阈值":allKnown?"MEWS 未达复核阈值":"MEWS 无法完整判定");
  out.put("note","只用 bedside 观察项；体温按独立更新周期取最近有效值，其余四项要求同一近时段，沿用体温不代表当前实测；缺项不计零。有创/无创分别计算，阳性提示生理恶化并核对感染，不能确诊或排除脓毒症。时间配置为本地候选策略；源入库时间未知，不作可靠历史回放。ICU、镇静及特殊人群需人工复核。");return out;
 }
 public static String stateLabel(String state){switch(state){case "ready":return "有效";case "missing":return "缺少记录";case "stale":return "记录过期";case "conflict":return "同刻记录冲突";case "invalid":return "记录有效性未确认";case "unmapped":return "数值或意识反应无法准确映射";default:return state;}}
 private static Map<String,Object> channel(List<Observation> rows,String channel,Instant now,MewsProperties cfg,String population,boolean enabled){
  List<Map<String,Object>> components=new ArrayList<>();List<Instant> times=new ArrayList<>(),coreTimes=new ArrayList<>();List<String> reasons=new ArrayList<>();int partial=0;boolean complete=true;
  for(String code:List.of("SBP","HR","RR","TEMP","AVPU")){
   String actual="SBP".equals(code)?channel:code;List<Observation> candidates=rows.stream().filter(o->actual.equals(o.parameterCode)).sorted(Comparator.comparing((Observation o)->o.eventTime).reversed().thenComparing(o->Objects.toString(o.sourceRecordId,""))).collect(Collectors.toList());
   Map<String,Object> item=new LinkedHashMap<>();item.put("code",code);item.put("label",LABELS.get(code));item.put("points",null);item.put("state","missing");
   if(!candidates.isEmpty()){
    Observation o=candidates.get(0);List<Observation> same=candidates.stream().filter(v->v.eventTime.equals(o.eventTime)).collect(Collectors.toList());item.put("value",o.value);item.put("text",o.text);item.put("unit",o.unit);item.put("eventTime",o.eventTime);item.put("sourceSystem",o.sourceSystem);item.put("sourceRecordId",o.sourceRecordId);item.put("sourceParameterCode",o.sourceParameterCode);item.put("ingestTimeBasis",o.ingestTimeBasis);times.add(o.eventTime);if(!"TEMP".equals(code))coreTimes.add(o.eventTime);long ageSeconds=Duration.between(o.eventTime,now).getSeconds();item.put("ageSeconds",ageSeconds);item.put("freshMinutes","TEMP".equals(code)?cfg.getTemperatureFreshMinutes():cfg.getFreshMinutes());
    boolean conflict=same.stream().map(v->Objects.toString(v.value,"null")+"|"+Objects.toString(v.text,"")+"|"+Objects.toString(v.unit,"")+"|"+Objects.toString(v.qualityStatus,"")).distinct().count()>1;
    Integer score="AVPU".equals(code)?avpu(o.text):points(code,o.value);boolean units="AVPU".equals(code)?"AVPU".equals(o.unit):"SBP".equals(code)?"mmHg".equals(o.unit):"HR".equals(code)?"bpm".equals(o.unit):"RR".equals(code)?"/min".equals(o.unit):Set.of("°C","C","℃").contains(Objects.toString(o.unit,""));
    String state=conflict?"conflict":o.eventTime.isBefore(now.minusSeconds(("TEMP".equals(code)?cfg.getTemperatureFreshMinutes():cfg.getFreshMinutes())*60L))?"stale":!"valid".equals(o.qualityStatus)||"context_only".equals(o.clinicalUse)||"unconfirmed".equals(o.encounterLinkStatus)?"invalid":score==null||!units?"unmapped":"ready";item.put("state",state);item.put("carriedForward","TEMP".equals(code)&&"ready".equals(state)&&ageSeconds>cfg.getFreshMinutes()*60L);if("ready".equals(state)){item.put("points",score);partial+=score;}
   }
   if(!"ready".equals(item.get("state"))){complete=false;reasons.add(LABELS.get(code)+"："+stateLabel(String.valueOf(item.get("state"))));}components.add(item);
  }
  Instant oldest=times.stream().min(Comparator.naturalOrder()).orElse(null),newest=times.stream().max(Comparator.naturalOrder()).orElse(null);Long skew=oldest==null?null:Duration.between(oldest,newest).getSeconds();Long coreSkew=coreTimes.isEmpty()?null:Duration.between(Collections.min(coreTimes),Collections.max(coreTimes)).getSeconds();if(coreSkew!=null&&coreSkew>cfg.getMaxSkewMinutes()*60L){complete=false;reasons.add("除体温外四项时间跨度超过 "+cfg.getMaxSkewMinutes()+" 分钟");}
  if(!"adult".equals(population)){complete=false;reasons.add("not_adult".equals(population)?"成人评分不适用于未成年患者":"成人适用性未确认");}if(!enabled){complete=false;reasons.add("本地评分未启用");}
  Map<String,Object> out=new LinkedHashMap<>();out.put("channel",channel);out.put("label","SBP_IBP".equals(channel)?"有创血压":"SBP_NIBP".equals(channel)?"无创血压":"单一血压");out.put("total",complete?partial:null);out.put("partialScore",partial);out.put("complete",complete);out.put("triggered",complete?partial>=cfg.getReviewThreshold():null);out.put("components",components);out.put("oldestAt",oldest);out.put("newestAt",newest);out.put("spanSeconds",skew);out.put("coreSpanSeconds",coreSkew);out.put("reasons",reasons);return out;
 }
}
