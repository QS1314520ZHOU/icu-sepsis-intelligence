package org.icu.sepsis.deterioration;

import com.mongodb.client.MongoCollection;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/** Read-only imaging report context from DataCenter.VI_ICU_REPORT (HIS pid keyed). */
@Component
@ConditionalOnProperty(name="app.source.mode",havingValue="smartcare")
public class ArdsReportReader {
  private final MongoTemplate dataCenter,smartcare;
  private volatile ReportCache reportsCache;
  private static final class ReportCache {final Instant loadedAt;final Map<String,List<Map<String,Object>>> byPid;ReportCache(Instant t,Map<String,List<Map<String,Object>>> p){loadedAt=t;byPid=p;}}
  public ArdsReportReader(@Qualifier("dataCenterMongoTemplate") MongoTemplate dataCenter,@Qualifier("smartcareMongoTemplate") MongoTemplate smartcare){this.dataCenter=dataCenter;this.smartcare=smartcare;}

  public Map<String,Object> recent(String hisPid,Instant now){
    if(hisPid==null||hisPid.isBlank())return Map.of("status","unavailable","reason","缺少HIS患者编号","reports",List.of());
    try{
      ReportCache cache=reportsCache;if(cache==null||cache.loadedAt.isBefore(now.minus(Duration.ofMinutes(5))))synchronized(this){cache=reportsCache;if(cache==null||cache.loadedAt.isBefore(now.minus(Duration.ofMinutes(5))))reportsCache=cache=loadReports(now);}
      List<Map<String,Object>> result=cache.byPid.getOrDefault(hisPid,List.of());
      return Map.of("status",result.isEmpty()?"no_recent_report":"available","windowDays",7,"reports",result,"limitation","报告关键词仅作影像线索，须医生确认双侧阴影及非心源性解释");
    }catch(RuntimeException e){return Map.of("status","unavailable","reason","检查报告源暂不可用","reports",List.of());}
  }
  private ReportCache loadReports(Instant now){
    Instant from=now.minus(Duration.ofDays(7));var grouped=new HashMap<String,List<Map<String,Object>>>();var seenByPid=new HashMap<String,Set<String>>();
    var query=new Document("reportTime",new Document("$gte",Date.from(from)).append("$lte",Date.from(now))).append("$or",List.of(new Document("examName",new Document("$regex","胸部|肺").append("$options","i")),new Document("title",new Document("$regex","胸部|肺").append("$options","i")),new Document("bodyParts",new Document("$regex","胸部|肺").append("$options","i"))));
    MongoCollection<Document> c=dataCenter.getCollection("VI_ICU_REPORT");
    for(Document d:c.find(query).projection(new Document("_id",0).append("pid",1).append("examName",1).append("title",1).append("bodyParts",1).append("reportDesc",1).append("conclusion",1).append("examTime",1).append("reportTime",1)).sort(new Document("reportTime",-1)).limit(10000)){
      String pid=d.getString("pid");if(pid==null||pid.isBlank())continue;
      var row=new LinkedHashMap<String,Object>();row.put("examName",first(d,"examName","title"));row.put("bodyParts",d.getString("bodyParts"));row.put("reportDesc",clip(d.getString("reportDesc"),300));row.put("conclusion",clip(d.getString("conclusion"),500));row.put("examTime",d.get("examTime"));row.put("reportTime",d.get("reportTime"));
      String text=(Objects.toString(d.getString("reportDesc"),"")+" "+Objects.toString(d.getString("conclusion"),"")).toLowerCase(Locale.ROOT);row.put("bilateralImagingHint",text.matches("(?s).*(双肺|双侧肺|两肺).*(浸润|渗出|磨玻璃|实变|炎症|阴影|病灶).*"));
      String fingerprint=Objects.toString(row.get("examName"),"")+"|"+Objects.toString(row.get("examTime"),"")+"|"+Objects.toString(row.get("reportTime"),"")+"|"+Objects.toString(row.get("conclusion"),"")+"|"+Objects.toString(row.get("reportDesc"),"");
      if(!seenByPid.computeIfAbsent(pid,k->new HashSet<>()).add(fingerprint))continue;
      var rows=grouped.computeIfAbsent(pid,k->new ArrayList<>());if(rows.size()<20)rows.add(row);
    }
    return new ReportCache(now,grouped);
  }
  private static String clip(String value,int limit){if(value==null)return "";return value.length()<=limit?value:value.substring(0,limit)+"…";}
  private static String first(Document d,String a,String b){String x=d.getString(a);return x==null?d.getString(b):x;}

  /** Existing ICU-08 prone-position quality records; kept separate from ARDS eligibility. */
  public Map<String,Object> proneSessions(String pid,Instant now){
    if(pid==null||pid.isBlank())return Map.of("status","unavailable","reason","缺少SmartCare患者编号","sessions",List.of());
    try{
      var sessions=new ArrayList<Map<String,Object>>();
      for(Document d:smartcare.getCollection("prone_session").find(new Document("pid",pid).append("startTime",new Document("$lte",Date.from(now))))
          .projection(new Document("_id",0).append("belongDate",1).append("startTime",1).append("endTime",1).append("durationHours",1).append("isDurationMet",1).append("isIndicationMet",1).append("isEffective",1).append("isAbnormal",1).append("abnormalReasons",1).append("prePFRatio",1).append("postPFRatio",1))
          .sort(new Document("startTime",-1)).limit(30)){
        var row=new LinkedHashMap<String,Object>();for(String k:List.of("belongDate","startTime","endTime","durationHours","isDurationMet","isIndicationMet","isEffective","isAbnormal","abnormalReasons","prePFRatio","postPFRatio"))if(d.containsKey(k))row.put(k,d.get(k));sessions.add(row);
      }
      return Map.of("status","available","sessions",sessions,"source","SmartCare.prone_session","limitation","俯卧位记录用于ICU-08治疗过程复核，不单独确定ARDS分母/确诊");
    }catch(RuntimeException e){return Map.of("status","unavailable","reason","俯卧位质控记录源暂不可用","sessions",List.of());}
  }
}
