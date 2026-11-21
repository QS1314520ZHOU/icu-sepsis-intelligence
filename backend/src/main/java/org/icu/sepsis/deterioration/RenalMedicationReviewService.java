package org.icu.sepsis.deterioration;
import java.time.*;import java.util.*;import java.util.stream.Collectors;
import org.icu.sepsis.domain.Domain.*;import org.icu.sepsis.integration.*;import org.icu.sepsis.sepsis.Icu05Rules;import org.springframework.beans.factory.ObjectProvider;import org.springframework.stereotype.Service;
@Service public class RenalMedicationReviewService {
 private final ObjectProvider<Icu05SourceReader> sources;private final DeteriorationService risk;
 public RenalMedicationReviewService(ObjectProvider<Icu05SourceReader> sources,DeteriorationService risk){this.sources=sources;this.risk=risk;}
 static final String[][] CATALOG={
 {"NSAIDs","布洛芬","双氯芬酸","酮咯酸","塞来昔布","依托考昔","洛索洛芬","ibuprofen","diclofenac","ketorolac","celecoxib","etoricoxib"},
 {"氨基糖苷类","阿米卡星","庆大霉素","妥布霉素","链霉素","amikacin","gentamicin","tobramycin","streptomycin"},
 {"ACE抑制剂","卡托普利","依那普利","贝那普利","培哚普利","雷米普利","captopril","enalapril","benazepril","perindopril","ramipril"},
 {"ARB","氯沙坦","缬沙坦","厄贝沙坦","替米沙坦","坎地沙坦","losartan","valsartan","irbesartan","telmisartan","candesartan"},
 {"利尿剂","呋塞米","托拉塞米","布美他尼","氢氯噻嗪","螺内酯","furosemide","torasemide","bumetanide","hydrochlorothiazide","spironolactone"}};
 static String group(String name){if(name==null)return null;String lower=name.toLowerCase(Locale.ROOT);for(var group:CATALOG)for(int i=1;i<group.length;i++){String term=group[i];if(term.matches("[a-z]+")?java.util.regex.Pattern.compile("(?<![a-z])"+term+"(?![a-z])").matcher(lower).find():name.contains(term))return group[0];}return null;}
 public Map<String,Object> read(Patient p,Instant now){
  Instant from=now.minus(Duration.ofDays(7));if(p.admittedAt!=null&&p.admittedAt.isAfter(from))from=p.admittedAt;
  Icu05Rules.Evidence drugs=new Icu05Rules.Evidence();String status="unavailable";
  try{var source=sources.getIfAvailable();if(source!=null){drugs=source.timelineDrugs(p.id,p.encounterId,p.department,from,now);status=drugs.failed.isEmpty()?"available":"incomplete";}}catch(RuntimeException failed){status="failed";}
  Map<String,Object> context;try{context=renalContext(risk.read(p,now));}catch(RuntimeException failed){context=Map.of();}
  return evaluate(p,from,now,drugs,status,context);
 }
 private static Map<String,Object> renalContext(Map<String,Object> risk){
  var context=new LinkedHashMap<String,Object>();
  if(risk.get("aki") instanceof Map){var a=(Map<?,?>)risk.get("aki");var renal=new LinkedHashMap<String,Object>();for(String field:List.of("detail","latest","combined","urine"))renal.put(field,a.get(field));context.put("renal",renal);}
  var rrts=new ArrayList<Object>();
  if(risk.get("specialty") instanceof Map){Object modules=((Map<?,?>)risk.get("specialty")).get("modules");
   if(modules instanceof List)for(Object module:(List<?>)modules){var m=(Map<?,?>)module;
    if("aki".equals(m.get("key"))&&m.get("evidence") instanceof List)for(Object raw:(List<?>)m.get("evidence")){var fact=(Map<?,?>)raw;if("rrtForAki".equals(fact.get("field"))||"chronicDialysis".equals(fact.get("field")))rrts.add(fact);}
   }
  }
  context.put("rrtEvidence",rrts);return context;
 }
 public static Map<String,Object> evaluate(Patient p,Instant from,Instant now,Icu05Rules.Evidence evidence,String status,Map<String,Object> context){var rows=new ArrayList<Map<String,Object>>();var unclassified=new ArrayList<Map<String,Object>>();var seen=new HashSet<String>();for(var drug:evidence.drugs){var events=new ArrayList<Icu05Rules.Action>();if(drug.start!=null&&!drug.start.isBefore(from)&&!drug.start.isAfter(now))events.add(new Icu05Rules.Action(drug.start,"执行起始记录"));for(var action:drug.actions)if(action.time!=null&&!action.time.isBefore(from)&&!action.time.isAfter(now))events.add(action);if(events.isEmpty())continue;events.sort(Comparator.comparing(a->a.time));for(var item:drug.items){if(item.name==null||item.name.isBlank()||!seen.add(drug.sourceId+"|"+item.name))continue;var last=events.get(events.size()-1);var row=new LinkedHashMap<String,Object>();row.put("name",item.name);row.put("sourceRecordId",drug.sourceId);row.put("start",drug.start);row.put("lastActionTime",last.time);row.put("lastAction",actionLabel(last.action));String group=group(item.name);row.put("group",group);row.put("review","核对适应证、容量/灌注、肾功能趋势及RRT；由医生/药师评估，不据此自动停药或调量");(group==null?unclassified:rows).add(row);}}
 var out=new LinkedHashMap<String,Object>();out.put("schemaVersion",1);out.put("patientId",p.id);out.put("encounterId",p.encounterId);out.put("asOf",now);out.put("from",from);out.put("sourceStatus",status);out.put("medications",rows.stream().limit(100).collect(Collectors.toList()));out.put("unclassified",unclassified.stream().limit(100).collect(Collectors.toList()));out.put("truncated",rows.size()>100||unclassified.size()>100);out.put("context",context);out.put("checklist",List.of("核对肌酐稳定基线、变化与逐小时尿量","核对当前体重、容量/灌注和电解质","核对是否RRT、方式及实际用药剂量/间隔","联系药师核对适应证、药物浓度监测和个体化方案"));out.put("ruleVersion","NICE-NG148-medication-review-v1");out.put("sourceUrl","https://www.nice.org.uk/guidance/ng148/chapter/Recommendations");out.put("limitations","近7天执行/操作线索，不能据此确定当前持续用药；有限通用名词典，不匹配不代表安全。剂量、药物浓度、完整RRT方案未接入，不计算推荐剂量。");return out;}
 static String actionLabel(String action){if(action==null)return "操作类型待核对";switch(action.toLowerCase(Locale.ROOT)){case "stop":case "停止":return "停止记录";case "pause":case "暂停":return "暂停记录";case "cancel":case "取消":return "取消记录";case "resume":case "recovery":case "恢复":return "恢复记录";case "执行起始记录":return action;default:return "操作类型待核对";}}
}
