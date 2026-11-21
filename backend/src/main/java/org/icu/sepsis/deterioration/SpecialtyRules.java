package org.icu.sepsis.deterioration;
import java.time.*;import java.util.*;
/** Explicit clinician/source evidence only. Results are screening references, not diagnoses. */
public final class SpecialtyRules {
 public static final Map<String,String[]> FIELDS=new LinkedHashMap<>();
 static {
  FIELDS.put("aki",new String[]{"adult|成人适用性|bool","baseline|已确认稳定基线肌酐|μmol/L","baselineStable|基线稳定性已确认|bool","acute7d|肌酐急性变化在7天内|bool","currentCreatinine|当前肌酐|μmol/L","rise48h|48小时肌酐升高量|μmol/L","rrtForAki|因本次AKI启动RRT|bool","chronicDialysis|既往维持透析|bool"});
  FIELDS.put("ards",new String[]{"adult|成人适用性|bool","acuteWeek|一周内急性起病|bool","bilateral|影像双侧异常|bool","nonCardiac|非心衰或容量超负荷充分解释|bool","support|呼吸支持方式|support","peep|PEEP|cmH2O","flow|HFNO流量|L/min","pf|P/F|mmHg","sf|配对S/F|ratio","sfValid|SpO2不超过97%且信号有效|bool","measurementValid|氧合测量稳定条件已核对|bool"});
  FIELDS.put("dic",new String[]{"adult|成人适用性|bool","underlying|存在可引起DIC的基础疾病|bool","platelets|血小板|10^9/L","ptProlongation|PT较对照延长（非INR）|s","dDimerFold|D二聚体/本实验室正常上限|fold","fibrinogen|纤维蛋白原|g/L"});
  FIELDS.put("liver",new String[]{"adult|成人适用性|bool","acuteInjury|急性肝损伤已确认|bool","inr|INR|ratio","hepaticEncephalopathy|肝性脑病已确认（非仅GCS）|bool","within26Weeks|病程不超过26周|bool","noCirrhosis|无肝硬化已确认|bool"});
  FIELDS.put("delirium",new String[]{"adult|成人适用性|bool","rass|RASS|score","camPerformed|已按CAM-ICU规范评估|bool","feature1|急性改变或波动|bool","feature2|注意力障碍|bool","feature3|意识水平改变|bool","feature4|思维紊乱|bool"});
 }
 public static List<Map<String,Object>> schema(){var out=new ArrayList<Map<String,Object>>();FIELDS.forEach((key,fields)->{var fs=new ArrayList<Map<String,String>>();for(String f:fields){var a=f.split("\\|");fs.add(Map.of("key",a[0],"label",a[1],"unit",a[2]));}out.add(Map.of("key",key,"label",label(key),"fields",fs));});return out;}
 public static String label(String key){return Map.of("aki","AKI分期参考","ards","ARDS筛查","dic","ISTH 2025显性DIC评分参考","liver","急性肝衰竭筛查","delirium","CAM-ICU谵妄筛查").get(key);}
 public static Map<String,Object> evaluate(String module,Map<String,Object> v){
  var out=new LinkedHashMap<String,Object>();out.put("key",module);out.put("label",label(module));out.put("status","unknown");out.put("score",null);out.put("stage",null);out.put("diagnosisConfirmed",false);out.put("ruleVersion",Map.of("aki","KDIGO-2012","ards","global-ARDS-2024","dic","ISTH-overt-DIC-2025","liver","ALF-clinical-context-v1","delirium","CAM-ICU-2016").get(module));out.put("missing",new ArrayList<String>());
  String[] fields=FIELDS.get(module);if(fields==null)throw new IllegalArgumentException("Unknown specialty");
  var missing=(List<String>)out.get("missing");for(String f:fields){var a=f.split("\\|");Object value=v.get(a[0]);boolean valid=a[2].equals("bool")?value instanceof Boolean:a[2].equals("support")?Set.of("invasive","niv","hfno").contains(value==null?"":value):num(v,a[0])!=null&&(!Set.of("rass","rise48h").contains(a[0])?(Set.of("pf","baseline","currentCreatinine").contains(a[0])?num(v,a[0])>0:num(v,a[0])>=0):true);if(!valid){missing.add(a[1]);v=new LinkedHashMap<>(v);v.remove(a[0]);}} if(module.equals("ards")){if("hfno".equals(v.get("support")))missing.remove("PEEP");else missing.remove("HFNO流量");if(num(v,"pf")!=null){missing.remove("配对S/F");missing.remove("SpO2不超过97%且信号有效");}else if(num(v,"sf")!=null&&yes(v,"sfValid"))missing.remove("P/F");}
  if(Boolean.FALSE.equals(v.get("adult")))return result(out,"not_assessable","不适用本组成人规则");
  if(!Boolean.TRUE.equals(v.get("adult")))return result(out,"unknown","成人适用性未确认，不套用成人规则");
  if(module.equals("aki")){
   if(Boolean.TRUE.equals(v.get("chronicDialysis")))return result(out,"not_assessable","维持透析背景，不能套用本次AKI分期");
   if(Boolean.TRUE.equals(v.get("rrtForAki"))&&Boolean.FALSE.equals(v.get("chronicDialysis"))){out.put("stage",3);return result(out,"candidate","本次AKI启动RRT的3期参考；需临床核对病因及适用性");}
   Double current=num(v,"currentCreatinine"),base=num(v,"baseline"),rise=num(v,"rise48h");boolean absolute=rise!=null&&rise>=26.5;
   if(current==null||!Boolean.FALSE.equals(v.get("chronicDialysis")))return result(out,"unknown","当前肌酐或既往透析背景未确认");
   boolean relative=base!=null&&base>0&&Boolean.TRUE.equals(v.get("baselineStable"))&&Boolean.TRUE.equals(v.get("acute7d"));
   if(!relative&&!absolute)return result(out,"unknown","稳定基线/急性时间关系不足，不能由七日最低值直接分期");
   double ratio=relative?current/base:0;int stage=ratio>=3||(current>=353.6&&(absolute||relative&&ratio>=1.5))?3:ratio>=2?2:ratio>=1.5||absolute?1:0;
   out.put("stage",stage==0?null:stage);return result(out,stage>0?"candidate":"no_observed_trigger","肌酐路径最低分期参考；需与尿量取较高参考值，未知RRT和尿量不能当作0期");
  }
  if(module.equals("delirium")){
   Double rass=num(v,"rass");if(rass==null||rass< -5||rass>4||rass!=Math.rint(rass))return result(out,"unknown","RASS无效或缺失");
   if(rass< -3)return result(out,"not_assessable","深镇静/昏迷背景，CAM-ICU不可评估，不能标为阴性");
   if(!Boolean.TRUE.equals(v.get("camPerformed")))return result(out,"unknown","未确认规范CAM-ICU床旁评估");
   if(!missing.isEmpty())return result(out,"unknown","CAM-ICU要素尚未完整");
   boolean positive=yes(v,"feature1")&&yes(v,"feature2")&&(yes(v,"feature3")||yes(v,"feature4"));return result(out,positive?"candidate":"no_observed_trigger",positive?"CAM-ICU筛查阳性参考，需临床复核":"本次完整CAM-ICU未触发；不是持续排除谵妄");
  }
  if(!missing.isEmpty())return result(out,"unknown","所需临床或检验要素未完整，不补值计分");
  if(module.equals("dic")){
   if(!yes(v,"underlying"))return result(out,"not_assessable","未确认DIC相关基础疾病，不解释为DIC阴性");
   double plt=num(v,"platelets"),pt=num(v,"ptProlongation"),dd=num(v,"dDimerFold"),fib=num(v,"fibrinogen");
   int score=(plt<50?2:plt<100?1:0)+(pt>=6?2:pt>=3?1:0)+(dd>7?3:dd>3?2:0)+(fib<1?1:0);out.put("score",score);
   return result(out,score>=5?"candidate":"no_observed_trigger","ISTH 2025显性DIC评分参考；低分不能排除早期DIC，需鉴别肝病等原因");
  }
  if(module.equals("liver")){
   boolean positive=yes(v,"acuteInjury")&&num(v,"inr")>=1.5&&yes(v,"hepaticEncephalopathy")&&yes(v,"within26Weeks")&&yes(v,"noCirrhosis");
   return result(out,positive?"candidate":"no_observed_trigger",positive?"符合急性肝衰竭复核线索，需立即临床评估":"本组条件未触发；不排除肝损伤或慢加急性肝衰竭");
  }
  boolean common=yes(v,"acuteWeek")&&yes(v,"bilateral")&&yes(v,"nonCardiac");String support=String.valueOf(v.get("support"));
  boolean assisted=support.equals("hfno")?num(v,"flow")>=30:(support.equals("invasive")||support.equals("niv"))&&num(v,"peep")>=5;
  if(!yes(v,"measurementValid"))return result(out,"unknown","需核对氧合测量稳定状态与信号质量");boolean usePf=num(v,"pf")!=null;double ratio=usePf?num(v,"pf"):num(v,"sf");if(common&&assisted&&ratio<=(usePf?300:315)){if(support.equals("invasive"))out.put("stage",ratio<=(usePf?100:148)?"重度参考":ratio<=(usePf?200:235)?"中度参考":"轻度参考");return result(out,"candidate","成人ARDS全球定义氧合路径候选；诊断和病因仍需临床确认");}
  return result(out,"no_observed_trigger","本次氧合路径未触发；不覆盖资源受限分支，不能排除其他呼吸衰竭");
 }
 private static boolean yes(Map<String,Object> v,String k){return Boolean.TRUE.equals(v.get(k));}
 private static Double num(Map<String,Object> v,String k){Object n=v.get(k);return n instanceof Number&&Double.isFinite(((Number)n).doubleValue())?((Number)n).doubleValue():null;}
 private static Map<String,Object> result(Map<String,Object> out,String status,String detail){out.put("status",status);out.put("detail",detail);return out;}
}
