package org.icu.sepsis.sepsis;

import java.time.*;
import java.util.*;

/** Offline candidate comparison; never feeds the clinical gate or quality denominator. */
public final class K1WindowComparison {
 private K1WindowComparison(){}
 public static final String VERSION="k1-window-candidates-20261106-v1";
 public static class Lactate {
  public String recordId,source,timeMeaning,unit;public Double value;public Instant time,availableAt;
  public boolean availabilityConfirmed,valid;
 }
 public static class CaseInput {
  public String caseKey;public Instant t0,hospitalAdmission;public Boolean sourceComplete;public boolean sourceError;
  public List<Lactate> lactates=new ArrayList<>();
 }
 public static Map<String,Object> policy(){return Map.of("version",VERSION,"confirmed",false,"qualityUse",false,"selection","maximum","thresholdMmolL",2,"wide","T0−24h～T0+6h；不早于本次入院时间","narrow","T0−2h～T0+1h；不早于本次入院时间","scope","仅候选 K1 对照，不等于休克确诊；仍需 K2、感染证据及人工复核");}
 public static Map<String,Object> evaluate(CaseInput c,int before,int after,int horizon){
  Instant asOf=c.t0==null?null:c.t0.plus(Duration.ofHours(horizon));Instant from=c.t0==null?null:c.t0.minus(Duration.ofHours(before));
  if(from!=null&&c.hospitalAdmission!=null&&from.isBefore(c.hospitalAdmission))from=c.hospitalAdmission;
  Instant to=c.t0==null?null:c.t0.plus(Duration.ofHours(after));
  Map<String,Object> out=new LinkedHashMap<>();out.put("windowVersion",VERSION+(before==24?"/wide":"/narrow"));out.put("windowConfirmed",false);out.put("qualityUse",false);out.put("from",from);out.put("to",to);out.put("asOf",asOf);out.put("windowClosed",to!=null&&!asOf.isBefore(to));out.put("value",null);out.put("selected",null);
  String reason=null;Lactate selected=null;boolean uncertain=false;int future=0,unknown=0;
  if(c.t0==null||c.hospitalAdmission==null||c.hospitalAdmission.isAfter(c.t0))reason="CONTEXT_UNKNOWN";
  else if(c.sourceError)reason="SOURCE_ERROR";
  else if(to.isBefore(from))reason="WINDOW_UNAVAILABLE";
  else {
   List<Lactate> rows=c.lactates==null?List.of():c.lactates;
   Map<String,Lactate> seen=new HashMap<>();
   for(Lactate p:rows){if(p==null||p.time==null){uncertain=true;unknown++;continue;}
    if(p.time.isBefore(from)||p.time.isAfter(to))continue;
    if(p.time.isAfter(asOf)){future++;continue;}
    if(!p.availabilityConfirmed||p.availableAt==null){uncertain=true;unknown++;continue;}
    if(p.availableAt.isAfter(asOf)){future++;continue;}
    if(p.availableAt.isBefore(p.time)||!p.valid||p.value==null||!Double.isFinite(p.value)||p.value<0||p.unit==null||!"mmol/l".equals(p.unit.trim().toLowerCase(Locale.ROOT))||p.source==null||p.source.isBlank()||p.recordId==null||p.recordId.isBlank()||p.timeMeaning==null||p.timeMeaning.isBlank()){uncertain=true;unknown++;continue;}
    String identity=p.source+"|"+p.recordId;Lactate prior=seen.putIfAbsent(identity,p);
    if(prior!=null&&(!Objects.equals(prior.value,p.value)||!Objects.equals(prior.time,p.time)||!Objects.equals(prior.availableAt,p.availableAt))){uncertain=true;unknown++;}
    if(selected==null||p.value>selected.value||p.value.equals(selected.value)&&(p.time.isBefore(selected.time)||p.time.equals(selected.time)&&(p.source+":"+p.recordId).compareTo(selected.source+":"+selected.recordId)<0))selected=p;
   }
   if(selected!=null){Map<String,Object> provenance=new LinkedHashMap<>();provenance.put("value",selected.value);provenance.put("unit","mmol/L");provenance.put("time",selected.time);provenance.put("availableAt",selected.availableAt);provenance.put("timeMeaning",selected.timeMeaning);provenance.put("source",selected.source);provenance.put("recordId",selected.recordId);out.put("selected",provenance);}
   if(!Boolean.TRUE.equals(c.sourceComplete)||uncertain)reason="DATA_INCOMPLETE";
   else if(selected==null)reason="NO_VALID_DATA";
   else if(selected.value>=2){out.put("value",true);reason="CANDIDATE_MET";}
   else if(asOf.isBefore(to))reason="WINDOW_OPEN";
   else {out.put("value",false);reason="CANDIDATE_NOT_MET";}
  }
  out.put("reason",reason);out.put("futureExcluded",future);out.put("unknownRecords",unknown);out.put("maximumComplete",Boolean.TRUE.equals(c.sourceComplete)&&!uncertain&&!c.sourceError&&reason!=null&&!reason.equals("CONTEXT_UNKNOWN"));return out;
 }
 public static Map<String,Object> compare(List<CaseInput> cases,List<Integer> horizons){
  if(cases==null||cases.size()>10000||horizons==null||horizons.isEmpty()||horizons.stream().anyMatch(h->h==null||!Set.of(1,3,6).contains(h)))throw new IllegalArgumentException("仅支持 1、3、6 小时评估，最多 10000 个病例住院");
  Set<String> keys=new HashSet<>();for(var c:cases)if(c==null||c.caseKey==null||c.caseKey.isBlank()||!keys.add(c.caseKey)||c.lactates!=null&&c.lactates.size()>10000)throw new IllegalArgumentException("病例键缺失、重复或记录超限");
  List<Map<String,Object>> assessments=new ArrayList<>();for(int horizon:new TreeSet<>(horizons)){
   List<Map<String,Object>> rows=new ArrayList<>(),added=new ArrayList<>(),reduced=new ArrayList<>(),uncertainChanges=new ArrayList<>();Map<String,Integer> transitions=new TreeMap<>();
   for(var c:cases){var wide=evaluate(c,24,6,horizon);var narrow=evaluate(c,2,1,horizon);Object w=wide.get("value"),n=narrow.get("value");String transition=state(n)+"→"+state(w);transitions.merge(transition,1,Integer::sum);
    Map<String,Object> row=new LinkedHashMap<>();row.put("caseKey",c.caseKey);row.put("t0",c.t0);row.put("hospitalAdmission",c.hospitalAdmission);row.put("narrow",narrow);row.put("wide",wide);row.put("transitionNarrowToWide",transition);rows.add(row);
    if(Boolean.TRUE.equals(w)&&Boolean.FALSE.equals(n))added.add(row);else if(Boolean.FALSE.equals(w)&&Boolean.TRUE.equals(n))reduced.add(row);else if(!Objects.equals(w,n))uncertainChanges.add(row);
   }
   assessments.add(Map.of("hoursAfterT0",horizon,"cases",rows,"added",added,"reduced",reduced,"uncertainChanges",uncertainChanges,"transitions",transitions));
  }
  return Map.of("policy",policy(),"caseCount",cases.size(),"assessments",assessments,"scope","窄→宽：仅 false→true 为新增，true→false 为减少；未知转换单列。相同数据下宽窗包含窄窗，闭窗完整数据通常不应减少；须核对异常。合成数据只验证程序，不是临床验证。");
 }
 static String state(Object value){return value==null?"无法判定":Boolean.TRUE.equals(value)?"成立":"不成立";}
}
