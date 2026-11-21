package org.icu.sepsis.deterioration;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.icu.sepsis.domain.Domain.Observation;

/** Context for review, not a substitute for ARDS, delirium or sedation diagnoses. */
final class OrganContext {
  static List<Observation> valid(List<Observation> rows,String code,Instant now,Duration age) {
    return rows.stream().filter(o->code.equals(o.parameterCode)&&o.value!=null&&Double.isFinite(o.value)
        &&"valid".equals(o.qualityStatus)&&!"context_only".equals(o.clinicalUse)&&!"unconfirmed".equals(o.encounterLinkStatus)
        &&o.eventTime!=null&&!o.eventTime.isAfter(now)&&!o.eventTime.isBefore(now.minus(age))
        &&(o.ingestTime==null||!o.ingestTime.isAfter(now))).sorted(Comparator.comparing(o->o.eventTime)).collect(Collectors.toList());
  }
  static Observation latest(List<Observation> rows,String code,Instant now,Duration age) {
    var valid=valid(rows,code,now,age);if(valid.isEmpty())return null;
    var last=valid.get(valid.size()-1);
    if(rows.stream().anyMatch(o->code.equals(o.parameterCode)&&o.eventTime!=null&&o.eventTime.isAfter(last.eventTime)
        &&!o.eventTime.isAfter(now)&&(o.ingestTime==null||!o.ingestTime.isAfter(now))))return null;
    return valid.stream().anyMatch(o->o.eventTime.equals(last.eventTime)&&(!o.value.equals(last.value)||!Objects.equals(o.unit,last.unit)))?null:last;
  }
  static Map<String,Object> point(Observation o) {
    var m=new LinkedHashMap<String,Object>();m.put("code",o.parameterCode);m.put("value",o.value);m.put("unit",o.unit);m.put("time",o.eventTime);return m;
  }
  static Map<String,Object> respiratory(List<Observation> rows,Instant now) {
    var out=new LinkedHashMap<String,Object>();var points=new ArrayList<Map<String,Object>>();
    var spo=latest(rows,"SPO2",now,Duration.ofMinutes(30));var fio=latest(rows,"FIO2",now,Duration.ofMinutes(60));
    for(String code:List.of("RR","SPO2","FIO2","PEEP_SET","PEEP_MEASURED","PEEP_TOTAL","PF_RATIO")) {
      var value=latest(rows,code,now,Duration.ofMinutes(code.equals("PF_RATIO")?1440:60));if(value!=null)points.add(point(value));
    }
    var context=new ArrayList<Map<String,Object>>();
    for(String code:List.of("FIO2","PEEP_SET","PEEP_MEASURED","PEEP_TOTAL"))if(points.stream().noneMatch(p->code.equals(p.get("code")))) {
      var recent=rows.stream().filter(o->code.equals(o.parameterCode)&&o.value!=null&&Double.isFinite(o.value)&&o.eventTime!=null
          &&!o.eventTime.isAfter(now)&&!o.eventTime.isBefore(now.minus(Duration.ofHours(1)))
          &&(o.ingestTime==null||!o.ingestTime.isAfter(now))).sorted(Comparator.comparing(o->o.eventTime)).collect(Collectors.toList());
      if(!recent.isEmpty()){var last=recent.get(recent.size()-1);if(recent.stream().noneMatch(o->o.eventTime.equals(last.eventTime)&&(!o.value.equals(last.value)||!Objects.equals(o.unit,last.unit))))context.add(point(last));}
    }
    out.put("unconfirmedPoints",context);
    Double sf=null;
    if(spo!=null&&fio!=null&&"%".equals(spo.unit)&&"%".equals(fio.unit)&&spo.value>=50&&spo.value<=97&&fio.value>=21&&fio.value<=100
        &&Duration.between(spo.eventTime,fio.eventTime).abs().compareTo(Duration.ofMinutes(15))<=0) sf=spo.value/(fio.value/100);
    out.put("points",points);out.put("sfRatio",sf==null?null:Math.round(sf*10)/10d);
    out.put("sfStatus",sf==null?"unavailable":"paired_observations");
    out.put("detail",sf==null?"氧浓度、血氧需单位明确且时间配对；高饱和度不外推S/F":"S/F仅为配对观察值，非ARDS诊断或分期");
    out.put("missing",List.of("呼吸支持方式及PEEP单位需核对","影像、起病时间与心源性原因需临床确认"));
    out.put("diagnosisStatus","not_assessed");return out;
  }
  static Map<String,Object> neurologic(List<Observation> rows,Instant now) {
    var out=new LinkedHashMap<String,Object>();var rass=latest(rows,"RASS",now,Duration.ofHours(4));
    if(rass!=null&&(rass.value< -5||rass.value>4||rass.value!=Math.rint(rass.value)))rass=null;
    out.put("rass",rass==null?null:point(rass));out.put("sedationStatus",rass==null?"unknown":rass.value<0?"sedation_context":"record_available");
    out.put("detail",rass==null?"未取得近期有效RASS，GCS需结合镇静、插管及基线核对":rass.value<0?"存在负值RASS，意识下降需结合镇静背景复核，不能直接归因感染":"已有RASS上下文，仍需核对镇静用药、插管及基线；不自动排除谵妄");
    return out;
  }
}
