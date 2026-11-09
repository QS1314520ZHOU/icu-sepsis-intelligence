package org.icu.sepsis.timeline;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.icu.sepsis.domain.Domain.*;
import org.icu.sepsis.integration.*;
import org.icu.sepsis.sepsis.Icu05Rules;
import org.icu.sepsis.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;

/** Scoped read-only timeline. Never changes clinical rules or infers continuous drug administration. */
@Service
public class ClinicalTimelineService {
  public static final int EVENT_LIMIT = 4000;
  private static final java.time.format.DateTimeFormatter CLOCK=java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));
  private static String time(Instant at){return at==null?"时间未知":CLOCK.format(at)+"（北京时间）";}
  private final ClinicalDataProvider data;
  private final ObjectProvider<Icu05SourceReader> sources;
  public ClinicalTimelineService(ClinicalDataProvider data,ObjectProvider<Icu05SourceReader> sources){this.data=data;this.sources=sources;}
  public static class Event {
    public String id,name,action,sourceSystem="SmartCare.drugExe",sourceRecordId;
    public Instant time;
    public boolean availabilityKnown=false;
  }
  public static class Packet {
    public String patientId,encounterId,mode,medicationStatus="unavailable";
    public Instant from,asOf;
    public List<Observation> observations=new ArrayList<>();
    public List<Event> medications=new ArrayList<>();
    public List<String> warnings=new ArrayList<>();
    public Map<String,Object> view(){return Views.map("patientId",patientId,"encounterId",encounterId,"mode",mode,"from",from,"asOf",asOf,"items",observations.stream().map(Views::observation).collect(Collectors.toList()),"medications",medications,"medicationStatus",medicationStatus,"warnings",warnings,"limit",2000);}
  }
  public Packet read(Patient p,int hours,Instant end,String mode){
    Instant now=Instant.now();
    if(hours<1||hours>720||mode==null||end!=null&&end.isAfter(now)||!Set.of("retrospective","known_at").contains(mode))
      throw new ApiException(HttpStatus.BAD_REQUEST,"TIMELINE_RANGE_INVALID","请选择过去30天以内的时间范围与有效查看模式。");
    Packet out=new Packet();out.patientId=p.id;out.encounterId=p.encounterId;out.mode=mode;out.asOf=end==null?now:end;out.from=out.asOf.minus(Duration.ofHours(hours));
    if(p.admittedAt!=null&&p.admittedAt.isAfter(out.from))out.from=p.admittedAt;
    if(!out.from.isBefore(out.asOf)){out.warnings.add("所选时点早于本次住院，未读取记录。");return out;}
    try{
      List<Observation> raw=data.observations(p.id,p.encounterId,out.from,out.asOf);
      if(raw.size()>=2000)out.warnings.add("观察项达到读取上限，所选时段可能不完整。");
      int excluded=0,scopeExcluded=0;
      for(Observation o:raw){
        if(!Objects.equals(p.id,o.patientId)||!Objects.equals(p.encounterId,o.encounterId)||!Objects.equals(p.department,o.department)){scopeExcluded++;continue;}
        if(o.eventTime==null||o.eventTime.isBefore(out.from)||o.eventTime.isAfter(out.asOf))continue;
        if(o.ingestTime!=null&&o.ingestTime.isAfter(out.asOf))continue;
        if("known_at".equals(mode)&&(o.ingestTime==null||!"valid".equals(o.qualityStatus)||"context_only".equals(o.clinicalUse)||"unconfirmed".equals(o.encounterLinkStatus))){excluded++;continue;}
        out.observations.add(o);
      }
      if(scopeExcluded>0)out.warnings.add("部分记录的患者、住院或科室上下文不符，已排除，不能作为当前证据。");
      if(excluded>0)out.warnings.add("已排除 "+excluded+" 条当时可得性或归属/有效性未确认的观察项。");
      if(out.observations.stream().anyMatch(o->o.ingestTime==null))out.warnings.add("部分源入库时间未知，仅供回顾查看，不能证明当时已可获得。");
    }catch(RuntimeException ex){out.warnings.add("观察项读取失败，不能据此认定该时段无记录。");}
    if("known_at".equals(mode)){out.warnings.add("仅按记录可得时间筛选，不重建数据库历史版本或撤回记录，不保证历史完整性。");out.medicationStatus="availability_unknown";out.warnings.add("用药来源可得时间未知，已从截至当时模式排除。");return out;}
    Icu05SourceReader source=sources.getIfAvailable();
    if(source==null){out.warnings.add("当前数据源未提供用药执行时间轴。");return out;}
    try{
      Icu05Rules.Evidence evidence=source.timelineDrugs(p.id,p.encounterId,p.department,out.from,out.asOf);
      out.medications=events(evidence.drugs,out.from,out.asOf);
      out.medicationStatus=evidence.failed.contains("drugExe")?"incomplete":"available";
      if(!evidence.failed.isEmpty())out.warnings.add("用药记录读取失败或达到来源上限，当前事件不代表完整执行历史。");
      if(out.medications.size()>=EVENT_LIMIT){out.medicationStatus="incomplete";out.warnings.add("用药事件达到显示上限。");}
      out.warnings.add("用药仅列执行起始与操作事件，不推断持续时长或剂量；回看窗前最多追溯30天，不能证明完整。");
    }catch(RuntimeException ex){out.medicationStatus="failed";out.warnings.add("用药执行读取失败，请核对来源。");}
    return out;
  }
  public static List<Event> events(List<Icu05Rules.Drug> drugs,Instant from,Instant end){
    List<Event> result=new ArrayList<>();Set<String> seen=new HashSet<>();
    for(var drug:drugs){
      String name=drug.items.stream().map(i->i.name).filter(n->n!=null&&!n.isBlank()).distinct().collect(Collectors.joining("＋"));
      if(name.isBlank())name="未命名用药记录";
      addEvent(result,seen,drug.sourceId,name,"执行起始记录",drug.start,from,end);
      for(var action:drug.actions){String a=action.action==null?"":action.action.toLowerCase(Locale.ROOT);String label;
        if(Set.of("stop","停止","停用").contains(a))label="停止";
        else if(Set.of("pause","暂停").contains(a))label="暂停";
        else if(Set.of("recovery","resume","恢复","继续").contains(a))label="恢复";
        else if(Set.of("cancel","取消").contains(a))label="取消";
        else label="操作记录（类型待核对）";
        addEvent(result,seen,drug.sourceId,name,label,action.time,from,end);
      }
    }
    result.sort(Comparator.comparing(e->e.time));return result.size()>EVENT_LIMIT?new ArrayList<>(result.subList(0,EVENT_LIMIT)):result;
  }
  private static void addEvent(List<Event> list,Set<String> seen,String source,String name,String action,Instant t,Instant from,Instant end){
    if(t==null||t.isBefore(from)||t.isAfter(end))return;
    String key=source+"|"+t+"|"+action;if(!seen.add(key))return;
    Event e=new Event();e.id=key;e.sourceRecordId=source;e.name=name;e.action=action;e.time=t;list.add(e);
  }
  private static String metric(String code){
    var names=Map.ofEntries(Map.entry("HR","心率"),Map.entry("RR","呼吸频率"),Map.entry("SPO2","血氧饱和度"),Map.entry("TEMP","体温"),Map.entry("GCS","意识评分 GCS"),Map.entry("LACTATE","乳酸"),Map.entry("PLT","血小板"),Map.entry("TBIL","总胆红素"),Map.entry("CREA","肌酐"),Map.entry("PF_RATIO","氧合指数 P/F"));
    if(names.containsKey(code))return names.get(code);
    var match=java.util.regex.Pattern.compile("^(MAP|SBP|DBP)(?:_(IBP|NIBP))?$").matcher(code);
    if(match.matches())return Map.of("MAP","平均动脉压","SBP","收缩压","DBP","舒张压").get(match.group(1))+("IBP".equals(match.group(2))?"（有创）":"NIBP".equals(match.group(2))?"（无创）":"");
    return code;
  }
  private static String number(Observation o){int precision=o.displayPrecision==null?6:Math.max(0,Math.min(6,o.displayPrecision));return java.math.BigDecimal.valueOf(o.value).setScale(precision,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();}

  private static boolean plotValue(Observation o){
    String unit=Map.of("HR","bpm","RR","/min","SPO2","%","TEMP","°C","GCS","score","LACTATE","mmol/L").get(o.parameterCode);
    if(o.parameterCode.matches("^(MAP|SBP|DBP)(_(IBP|NIBP))?$"))unit="mmHg";
    if(unit!=null&&!unit.equals(o.unit))return false;
    double low=-1e6,high=1e6;
    switch(o.parameterCode){case "HR":low=10;high=300;break;case "RR":low=1;high=100;break;case "SPO2":low=0;high=100;break;case "TEMP":low=25;high=45;break;case "GCS":low=3;high=15;break;case "LACTATE":low=0;high=40;break;default:if(o.parameterCode.matches("^(MAP|SBP|DBP)(_(IBP|NIBP))?$")){low=10;high=300;}}
    return o.value!=null&&Double.isFinite(o.value)&&o.value>=low&&o.value<=high;
  }

  public static List<String[]> facts(Packet packet,List<String> codes,boolean medication){
    List<String[]> facts=new ArrayList<>();
    facts.add(new String[]{"分析范围",time(packet.from)+" 至 "+time(packet.asOf)+"；模式："+("known_at".equals(packet.mode)?"截至当时，排除可得性未知记录":"回顾性查看；源可得时间未知的数据不能证明当时已知")+"。仅解释所选记录的时间关联，不认定因果或疗效，不生成诊断、医嘱；不引用当前时点的规则状态替代历史。"});
    for(String warning:packet.warnings)facts.add(new String[]{"数据限制",warning});
    for(String code:new LinkedHashSet<>(codes)){
      String label=metric(code);
      List<Observation> rows=packet.observations.stream().filter(o->code.equals(o.parameterCode)&&o.value!=null&&Double.isFinite(o.value)).sorted(Comparator.comparing(o->o.eventTime)).collect(Collectors.toList());
      Set<String> units=rows.stream().map(o->Objects.toString(o.unit,"")).collect(Collectors.toSet());
      if(rows.isEmpty()){facts.add(new String[]{"缺项",label+" 在所选范围无可用数值，不能判定正常。"});continue;}
      if(units.size()!=1||units.contains("")){facts.add(new String[]{"缺项",label+" 单位混合或未知，不计算汇总。"});continue;}
      List<Observation> confirmed=rows.stream().filter(o->"valid".equals(o.qualityStatus)&&!"context_only".equals(o.clinicalUse)&&!"unconfirmed".equals(o.encounterLinkStatus)&&plotValue(o)).collect(Collectors.toList());
      if(confirmed.size()!=rows.size())facts.add(new String[]{"归属限制",label+" 有 "+(rows.size()-confirmed.size())+" 条候选/无效记录，未用于数值汇总。"});
      var unconflicted=confirmed.stream().filter(o->confirmed.stream().noneMatch(q->o.eventTime.equals(q.eventTime)&&Double.compare(o.value,q.value)!=0)).collect(Collectors.toList());
      if(unconflicted.size()!=confirmed.size())facts.add(new String[]{"冲突限制",label+" 同一时点有冲突记录，相关数值未用于汇总。"});
      if(unconflicted.isEmpty())continue;
      final var usable=unconflicted;
      var low=usable.stream().min(Comparator.comparingDouble(o->o.value)).get();var high=usable.stream().max(Comparator.comparingDouble(o->o.value)).get();var first=usable.get(0);var last=usable.get(usable.size()-1);
      facts.add(new String[]{"所选趋势",label+"，单位 "+first.unit+"；"+usable.size()+" 条；首次 "+number(first)+" @ "+time(first.eventTime)+"；末次 "+number(last)+" @ "+time(last.eventTime)+"；最低 "+number(low)+" @ "+time(low.eventTime)+"；最高 "+number(high)+" @ "+time(high.eventTime)+"。仅为有记录点，不能推断未观测时段。"});
    }
    if(medication){facts.add(new String[]{"用药范围","用药读取状态："+Map.of("available","可读取执行事件","unavailable","来源未提供","failed","读取失败","incomplete","读取不完整","availability_unknown","当时可得性未知，已排除").getOrDefault(packet.medicationStatus,"状态待核对")+"；总事件 "+packet.medications.size()+" 条；以下最多列80条，不代表完整历史。"});for(Event e:packet.medications.stream().limit(80).collect(Collectors.toList()))facts.add(new String[]{"用药事件",e.name+"；"+e.action+" @ "+time(e.time)+"；来源 用药执行记录"+"；记录可得时间未知，不推断剂量或持续时长。"});}
    return facts;
  }
}
