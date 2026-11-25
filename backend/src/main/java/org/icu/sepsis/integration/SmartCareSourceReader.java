package org.icu.sepsis.integration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.icu.sepsis.api.ApiException;
import org.icu.sepsis.domain.Domain.Observation;
import org.icu.sepsis.domain.Domain.Patient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Read-only access to hospital SmartCare patient + bedside data and DataCenter labs.
 *
 * <p>pid linkage: SmartCare {@code patient._id} hex → {@code bedside.pid} / {@code score.pid};
 * HIS patient number {@code patient.hisPid} → DataCenter {@code VI_ICU_EXAM_ITEM.hisPid}.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "app.source.mode",
    havingValue = "smartcare")
public class SmartCareSourceReader {
  private static final Pattern OBJECT_ID = Pattern.compile("^[a-fA-F0-9]{24}$");
  private static final Pattern LACTATE_ITEM =
      Pattern.compile("^(LAC\\d*|乳酸)$", Pattern.CASE_INSENSITIVE);

  private static final Map<String, String> BEDSIDE_TO_PARAMETER =
      Map.ofEntries(
          Map.entry("param_HR", "HR"),
          Map.entry("param_Yishi", "AVPU"),
          Map.entry("param_nibp_m", "MAP_NIBP"),
          Map.entry("param_nibp_s", "SBP_NIBP"),
          Map.entry("param_nibp_d", "DBP_NIBP"),
          Map.entry("param_resp", "RR"),
          Map.entry("param_spo2", "SPO2"),
          Map.entry("param_T", "TEMP"),
          Map.entry("param_ibp_m", "MAP_IBP"),
          Map.entry("param_ibp_s", "SBP_IBP"),
          Map.entry("param_ibp_d", "DBP_IBP"),
          Map.entry("param_score_gcs_obs", "GCS"),
          Map.entry("param_score_rass_obs", "RASS"),
          Map.entry("param_FiO2", "FIO2"),
          Map.entry("param_vent_pause_PEEP_TOT", "PEEP_TOTAL"),
          Map.entry("param_vent_peep", "PEEP_SET"),
          Map.entry("param_vent_measure_peep", "PEEP_MEASURED"),
          Map.entry("param_体重", "WEIGHT"),
          Map.entry("param_niaoLiang", "URINE_OUTPUT"),
          Map.entry("param_urine", "URINE_OUTPUT"));

  private static final Map<String, String> PARAMETER_UNITS =
      Map.ofEntries(
          Map.entry("HR", "bpm"),
          Map.entry("MAP", "mmHg"),
          Map.entry("SBP", "mmHg"),
          Map.entry("DBP", "mmHg"),
          Map.entry("RR", "/min"),
          Map.entry("SPO2", "%"),
          Map.entry("TEMP", "°C"),
          Map.entry("LACTATE", "mmol/L"),
          Map.entry("GCS", "score"),
          Map.entry("PF_RATIO", "mmHg"),
          Map.entry("PLT", "10^9/L"),
          Map.entry("TBIL", "μmol/L"),
          Map.entry("CREA", "μmol/L"),
          Map.entry("URINE_OUTPUT", "ml"));

  /** DataCenter itemCode → parameter. Primary codes verified in field-mapping report. */
  private static final Map<String, String> LAB_ITEM_TO_PARAMETER =
      Map.ofEntries(
          Map.entry("PLT", "PLT"),
          Map.entry("platelet", "PLT"),
          Map.entry("TBIL", "TBIL"),
          Map.entry("sCr", "CREA"),
          Map.entry("CREA", "CREA"),
          Map.entry("Cr", "CREA"));

  private final MongoTemplate smartcare;
  private final MongoTemplate dataCenter;

  public SmartCareSourceReader(
      @Qualifier("smartcareMongoTemplate") MongoTemplate smartcare,
      @Qualifier("dataCenterMongoTemplate") MongoTemplate dataCenter) {
    this.smartcare = smartcare;
    this.dataCenter = dataCenter;
  }

  public boolean isReadable() {
    try {
      smartcare.getDb().runCommand(new Document("ping", 1));
      dataCenter.getDb().runCommand(new Document("ping", 1));
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  public Patient loadPatient(String hostPatientId) {
    return mapPatient(requireDocument(hostPatientId));
  }

  public Patient loadPatientForDept(String hostPatientId,String deptCode) {
    Document d=requireDocument(hostPatientId);
    if(!Objects.equals(d.getString("deptCode"),deptCode)&&!Objects.equals(d.getString("deptCode2"),deptCode))
      throw ApiException.notFound();
    Patient p=mapPatient(d);p.department=deptCode;return p;
  }

  public String hisPidOf(String hostPatientId) {
    Document doc =
        smartcare
            .getCollection("patient")
            .find(new Document("_id", new ObjectId(requireId(hostPatientId))))
            .projection(new Document("hisPid", 1).append("mrn", 1))
            .first();
    if (doc == null) return null;
    String hisPid = doc.getString("hisPid");
    return hisPid == null || hisPid.isBlank() ? null : hisPid;
  }

  /** ICU-08 reference-project denominator path: first 24h P/F mean, diagnosis gate, route and PEEP/flow. */
  public Map<String,Object> icu08AdmissionEvidence(String hostPatientId,String deptCode) {
    var out=new LinkedHashMap<String,Object>();out.put("source","icu-quality-dashboard ICU-08 (2026-10)");out.put("windowHours",24);out.put("status","unknown");
    try {
      Document patient=requireDocument(hostPatientId);if(!Objects.equals(patient.getString("deptCode"),deptCode)&&!Objects.equals(patient.getString("deptCode2"),deptCode)){out.put("reason","患者不在当前科室");return out;}
      Instant admit=toInstant(patient.get("icuAdmissionTime"));if(admit==null)admit=toInstant(patient.get("admissionTime"));if(admit==null){out.put("reason","缺少入科时间");return out;}
      String mrn=patient.getString("mrn");if(mrn==null||mrn.isBlank()){out.put("reason","缺少血气关联键");return out;}
      Instant end=admit.plus(java.time.Duration.ofHours(24));String diagnosis=diagnosisTextForIcu08(patient);var diagHits=new ArrayList<String>();for(String kw:List.of("ARDS","急性呼吸窘迫综合征","急性呼吸窘迫","肺损伤","重症肺炎","创伤性湿肺"))if(diagnosis.toLowerCase(java.util.Locale.ROOT).contains(kw.toLowerCase(java.util.Locale.ROOT))&&!diagHits.contains(kw))diagHits.add(kw);
      out.put("admissionTime",admit);out.put("diagnosisMatched",!diagHits.isEmpty());out.put("diagnosisEvidence",diagHits);
      var matches=new ArrayList<Map.Entry<Instant,Double>>();var pidValues=List.of(new ObjectId(hostPatientId),hostPatientId);
      Document elem=new Document("code",new Document("$in",List.of("param_bg_P/Fratio","param_bg_OI","param_bg_po2","param_bg_FiO2"))).append("valid","valid");
      Document query=new Document("mrn",mrn).append("eventExe.pid",new Document("$in",pidValues)).append("eventExe.startTime",new Document("$gte",Date.from(admit)).append("$lte",Date.from(end))).append("bedsides",new Document("$elemMatch",elem));
      for(Document bga:smartcare.getCollection("bGATemp").find(query).projection(new Document("_id",1).append("eventExe.startTime",1).append("bedsides",1)).limit(2000)){
        Document event=bga.get("eventExe",Document.class);Instant time=event==null?null:toInstant(event.get("startTime"));Double pf=pfRatio(bga.getList("bedsides",Document.class,List.of()));if(time!=null&&pf!=null&&pf>0)matches.add(Map.entry(time,pf));
      }
      matches.sort(Map.Entry.comparingByKey());out.put("pfSampleCount",matches.size());if(!matches.isEmpty()){double avg=matches.stream().mapToDouble(Map.Entry::getValue).average().orElse(Double.NaN);out.put("pfAverage",Math.round(avg*10d)/10d);out.put("pfFirstTime",matches.get(0).getKey());out.put("pfLastTime",matches.get(matches.size()-1).getKey());}
      Document pid=new Document("$in",pidValues);Document win=new Document("$gte",Date.from(admit)).append("$lte",Date.from(end));
      Document routeDoc=smartcare.getCollection("bedside").find(new Document("pid",pid).append("code","param_XiYangTuJing").append("valid",true).append("time",win)).projection(new Document("strVal",1).append("time",1)).sort(new Document("time",-1)).first();
      String route=routeDoc==null?"":Objects.toString(routeDoc.get("strVal"),"");String arm=icu08Arm(route);out.put("supportPath",arm);out.put("supportTime",routeDoc==null?null:toInstant(routeDoc.get("time")));
      Double peep=null,flow=null;Document peepDoc=null,flowDoc=null;
      if("invasive".equals(arm)||"noninvasive".equals(arm)){peepDoc=smartcare.getCollection("bedside").find(new Document("pid",pid).append("code","param_vent_peep").append("valid",true).append("time",win)).projection(new Document("strVal",1).append("time",1)).sort(new Document("time",-1)).first();if(peepDoc!=null)peep=parseDouble(stripUnit(Objects.toString(peepDoc.get("strVal"),"")));}
      if("hfnc".equals(arm)){flowDoc=smartcare.getCollection("bedside").find(new Document("pid",pid).append("code","param_吸氧流速").append("valid",true).append("time",win)).projection(new Document("strVal",1).append("time",1)).sort(new Document("time",-1)).first();if(flowDoc!=null)flow=parseDouble(stripUnit(Objects.toString(flowDoc.get("strVal"),"")));}
      out.put("peep",peep);out.put("peepTime",peepDoc==null?null:toInstant(peepDoc.get("time")));out.put("flow",flow);out.put("flowTime",flowDoc==null?null:toInstant(flowDoc.get("time")));
      if(matches.isEmpty()){out.put("reason","入科后 24 小时无有效 P/F");return out;}if("unknown".equals(arm)){out.put("reason","呼吸支持途径缺失或无法归类");return out;}
      double avg=((Number)out.get("pfAverage")).doubleValue();boolean candidate=("invasive".equals(arm)&&peep!=null&&peep>=5&&avg<150)||("noninvasive".equals(arm)&&peep!=null&&peep>=5&&avg<=200)||("hfnc".equals(arm)&&flow!=null&&flow>=30&&avg<=200);
      boolean complete="none".equals(arm)||(("invasive".equals(arm)||"noninvasive".equals(arm))&&peep!=null)||("hfnc".equals(arm)&&flow!=null);
      out.put("armLabel","invasive".equals(arm)?"有创":"noninvasive".equals(arm)?"无创":"hfnc".equals(arm)?"高流量":"普通氧疗");
      if(!Boolean.TRUE.equals(out.get("diagnosisMatched"))){out.put("status",diagnosis.isBlank()?"unknown":"not_in_cohort");out.put("reason",diagnosis.isBlank()?"诊断文本未取得，无法通过 ICU-08 诊断过滤":"未命中 ICU-08 诊断关键词");return out;}
      out.put("status",candidate?"candidate":complete?"no_observed_trigger":"unknown");out.put("reason",candidate?"符合 ICU-08 入科 24 小时生理路径，待复核":"入科 24 小时生理路径未触发或关键设置缺失");return out;
    }catch(RuntimeException e){out.put("reason","ICU-08 来源读取失败");return out;}
  }

  /** Official monthly aggregate and patient detail cache, kept distinct from current-stay candidates. */
  public Map<String,Object> icu08OfficialAudit(String deptCode,String period) {
    if(period==null||!period.matches("\\d{4}-(0[1-9]|1[0-2])"))
      throw new org.icu.sepsis.api.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,"INVALID_PERIOD","统计月份格式应为 YYYY-MM");
    Document summary=smartcare.findOne(Query.query(Criteria.where("dept_code").is(deptCode).and("indicator").is("ICU-08").and("period").is(period)),Document.class,"icu_monthly_summary");
    if(summary==null)return Map.of("status","no_summary","period",period,"detailStatus","unavailable");
    Document den=smartcare.findOne(Query.query(Criteria.where("dept_code").is(deptCode).and("code").is("ICU-08").and("part").is("denominator").and("period").is(period)),Document.class,"icu_indicator_detail_cache");
    Document num=smartcare.findOne(Query.query(Criteria.where("dept_code").is(deptCode).and("code").is("ICU-08").and("part").is("numerator").and("period").is(period)),Document.class,"icu_indicator_detail_cache");
    int denominator=number(summary.get("denominator")), numerator=number(summary.get("numerator"));
    List<Map<String,Object>> denRows=officialPatients(den), numRows=officialPatients(num);
    boolean detailsPresent=den!=null&&num!=null;
    String detailStatus;
    if(denominator<0||numerator<0||numerator>denominator)detailStatus="inconsistent";
    else if(denominator==0&&numerator==0)detailStatus="empty_cohort";
    else if(!detailsPresent||denRows.size()!=denominator||numRows.size()!=numerator)detailStatus="missing";
    else if(!uniquePatientIds(denRows)||!uniquePatientIds(numRows)||!numeratorSubset(denRows,numRows))detailStatus="inconsistent";
    else detailStatus="available";
    var out=new LinkedHashMap<String,Object>();out.put("status","available");out.put("period",period);out.put("denominator",denominator);out.put("numerator",numerator);out.put("rate",summary.get("value"));out.put("dataAvailable",summary.get("data_available"));out.put("updatedAt",summary.get("updated_at"));out.put("detailStatus",detailStatus);out.put("detailUpdatedAt",den==null?null:den.get("updated_at"));out.put("denominatorPatients",denRows);out.put("numeratorPatients",numRows);return out;
  }

  /** Read-only re-execution of the reference ICU-08 month cohort; results are explicitly not cache details. */
  public Map<String,Object> icu08MonthlyRecompute(String deptCode,String period) {
    if(period==null||!period.matches("\\d{4}-(0[1-9]|1[0-2])"))
      throw new org.icu.sepsis.api.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,"INVALID_PERIOD","统计月份格式应为 YYYY-MM");
    java.time.LocalDate firstDay=java.time.LocalDate.parse(period+"-01");
    Instant monthStart=firstDay.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    Instant monthEnd=firstDay.plusMonths(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().minusMillis(1);
    Document cohortQuery=new Document("deptCode",deptCode).append("status",new Document("$ne","invalid"))
        .append("icuAdmissionTime",new Document("$lte",Date.from(monthEnd)))
        .append("$or",List.of(new Document("icuDischargeTime",new Document("$gte",Date.from(monthStart))),new Document("icuDischargeTime",null),new Document("icuDischargeTime",new Document("$exists",false))));
    var denominator=new ArrayList<Map<String,Object>>();var numerator=new ArrayList<Map<String,Object>>();var pathCounts=new java.util.TreeMap<String,Integer>();int sourceErrors=0;
    for(Document patient:smartcare.getCollection("patient").find(cohortQuery).projection(new Document("_id",1).append("name",1).append("showBed",1).append("hisBed",1).append("icuAdmissionTime",1).append("icuDischargeTime",1)).sort(new Document("showBed",1).append("hisBed",1).append("_id",1))){
      String id=patient.getObjectId("_id").toHexString();Map<String,Object> evidence;
      try{evidence=icu08AdmissionEvidence(id,deptCode);}catch(RuntimeException failure){sourceErrors++;continue;}
      String status=String.valueOf(evidence.get("status"));pathCounts.merge(status,1,Integer::sum);if(!"candidate".equals(status))continue;
      var row=new LinkedHashMap<String,Object>();String bed=patient.getString("showBed");if(bed==null||bed.isBlank())bed=patient.getString("hisBed");row.put("name",patient.getString("name"));row.put("bed_no",bed);row.put("admit_time",patient.get("icuAdmissionTime"));row.put("pfAverage",evidence.get("pfAverage"));row.put("pfSampleCount",evidence.get("pfSampleCount"));row.put("armLabel",evidence.get("armLabel"));row.put("peep",evidence.get("peep"));row.put("flow",evidence.get("flow"));row.put("diagnosisEvidence",evidence.get("diagnosisEvidence"));denominator.add(row);
      Instant admit=toInstant(patient.get("icuAdmissionTime"));Instant discharge=toInstant(patient.get("icuDischargeTime"));Instant proneFrom=monthStart.isAfter(admit)?monthStart:admit;Instant firstPf=toInstant(evidence.get("pfFirstTime"));if(firstPf!=null&&firstPf.isAfter(proneFrom))proneFrom=firstPf;Instant proneThrough=discharge!=null&&discharge.isBefore(monthEnd)?discharge:monthEnd;
      if(!proneFrom.isAfter(proneThrough)){
        var pidValues=List.of(new ObjectId(id),id);Document proneQuery=new Document("pid",new Document("$in",pidValues)).append("code","param_TiWei").append("valid",true).append("strVal",new Document("$regex","俯卧位")).append("time",new Document("$gte",Date.from(proneFrom)).append("$lte",Date.from(proneThrough)));
        Document prone=smartcare.getCollection("bedside").find(proneQuery).projection(new Document("time",1).append("_id",1)).sort(new Document("time",1)).limit(1).first();
        if(prone!=null){var numRow=new LinkedHashMap<String,Object>(row);numRow.put("firstProneTime",prone.get("time"));Object sourceId=prone.get("_id");if(sourceId instanceof ObjectId)numRow.put("sourceRecordedAt",((ObjectId)sourceId).getDate());numerator.add(numRow);}
      }
    }
    Map<String,Object> official=icu08OfficialAudit(deptCode,period);Object officialDen=official.get("denominator"),officialNum=official.get("numerator");boolean denCountMatch=officialDen instanceof Number&&((Number)officialDen).intValue()==denominator.size();boolean numCountMatch=officialNum instanceof Number&&((Number)officialNum).intValue()==numerator.size();
    var out=new LinkedHashMap<String,Object>();out.put("status","available");out.put("period",period);out.put("basis","icu-quality-dashboard ICU-08 reference rules re-executed on SmartCare; not official cached patient details");out.put("cohortCount",pathCounts.values().stream().mapToInt(Integer::intValue).sum());out.put("pathCounts",pathCounts);out.put("recomputedDenominator",denominator.size());out.put("recomputedNumerator",numerator.size());out.put("officialDenominator",officialDen);out.put("officialNumerator",officialNum);out.put("denominatorCountMatchesOfficial",denCountMatch);out.put("numeratorCountMatchesOfficial",numCountMatch);out.put("countsMatchOfficial",denCountMatch&&numCountMatch&&sourceErrors==0);out.put("sourceErrors",sourceErrors);out.put("denominatorPatients",denominator);out.put("numeratorPatients",numerator);return out;
  }
  private static int number(Object value){return value instanceof Number?((Number)value).intValue():0;}
  private static List<Map<String,Object>> officialPatients(Document detail){
    if(detail==null||!(detail.get("patients") instanceof List))return List.of();var rows=new ArrayList<Map<String,Object>>();
    for(Object raw:(List<?>)detail.get("patients")){if(!(raw instanceof Document))continue;Document p=(Document)raw;var row=new LinkedHashMap<String,Object>();
      for(String key:List.of("patient_id","pid_ref","name","bed_no","dept","admit_time","value","excluded","basis"))if(p.containsKey(key))row.put(key,p.get(key));rows.add(row);
    }return rows;
  }
  private static boolean uniquePatientIds(List<Map<String,Object>> rows){var ids=new java.util.HashSet<String>();for(var row:rows){Object id=row.containsKey("patient_id")?row.get("patient_id"):row.get("pid_ref");if(id==null||String.valueOf(id).isBlank()||!ids.add(String.valueOf(id).trim()))return false;}return true;}
  private static boolean numeratorSubset(List<Map<String,Object>> denominator,List<Map<String,Object>> numerator){var ids=new java.util.HashSet<String>();for(var row:denominator){Object id=row.containsKey("patient_id")?row.get("patient_id"):row.get("pid_ref");if(id!=null)ids.add(String.valueOf(id).trim());}for(var row:numerator){Object id=row.containsKey("patient_id")?row.get("patient_id"):row.get("pid_ref");if(id==null||!ids.contains(String.valueOf(id).trim()))return false;}return true;}

  private static String diagnosisTextForIcu08(Document d){var out=new StringBuilder();for(String key:List.of("clinicalDiagnosis","diagnosis","admissionDiagnosis","dischargedDiagnosis")){Object v=d.get(key);if(v instanceof String)appendText(out,(String)v);else if(v instanceof List<?>)for(Object x:(List<?>)v)appendText(out,String.valueOf(x));}Object history=d.get("diagnosisHistoryList");if(history instanceof List<?>)for(Object x:(List<?>)history){if(x instanceof Document){Document item=(Document)x;appendText(out,Objects.toString(item.get("diagnosis"),Objects.toString(item.get("name"),"")));}else if(x!=null)appendText(out,String.valueOf(x));}return out.toString();}
  private static Double pfRatio(List<Document> points){Double pf=null,oi=null,po2=null,fio2=null;for(Document p:points){if(!"valid".equals(p.get("valid")))continue;String code=p.getString("code");if("param_bg_P/Fratio".equals(code)){pf=bloodGasNumber(p);if(pf!=null)return pf;}else if("param_bg_OI".equals(code))oi=parseDouble(p.get("strVal"));else if("param_bg_po2".equals(code))po2=bloodGasNumber(p);else if("param_bg_FiO2".equals(code))fio2=parseDouble(p.get("strVal"));}if(oi!=null)return oi;if(po2!=null&&fio2!=null&&fio2>0){double frac=fio2>1?fio2/100:fio2;return frac>0?po2/frac:null;}return null;}
  private static String stripUnit(String raw){return raw.replace("cmH2O","").replace("cmH₂O","").replace("cmh2o","").replace("L/min","").replace("l/min","").trim();}
  private static String icu08Arm(String route){if(route==null||route.isBlank())return "unknown";Map<String,String> modes=Map.ofEntries(Map.entry("管辅","invasive"),Map.entry("切辅","invasive"),Map.entry("有创","invasive"),Map.entry("无创呼吸机","noninvasive"),Map.entry("无创高频","noninvasive"),Map.entry("无创","noninvasive"),Map.entry("高流量","hfnc"),Map.entry("管高","hfnc"),Map.entry("切高","hfnc"),Map.entry("管氧","invasive"),Map.entry("切氧","invasive"),Map.entry("管文","invasive"),Map.entry("切文","invasive"),Map.entry("T管","invasive"),Map.entry("带管自主","invasive"),Map.entry("高频","invasive"),Map.entry("无","none"),Map.entry("自主呼吸","none"),Map.entry("鼻导管","none"),Map.entry("鼻塞","none"),Map.entry("面罩","none"),Map.entry("鼻罩","none"),Map.entry("箱氧","none"),Map.entry("储氧面罩","none"),Map.entry("储氧","none"),Map.entry("面罩吸氧","none"),Map.entry("鼻氧","none"),Map.entry("鼻管辅","none"),Map.entry("低流量","none"),Map.entry("低流量用氧","none"),Map.entry("低流量箱氧","none"),Map.entry("未吸氧","none"),Map.entry("拒绝","none"),Map.entry("拒绝吸氧","none"),Map.entry("暂停吸氧","none"),Map.entry("文丘里","none"),Map.entry("球囊","none"),Map.entry("呼吸球囊","none"),Map.entry("球囊通气","none"),Map.entry("简易呼吸器","none"),Map.entry("家用呼吸机","none"),Map.entry("家用","none"),Map.entry("鼻","none"));String normalized=route.replace('、',',').replace('，',',').replace('/',',').replace('+',',').replace(' ',',').replaceAll(",+",",");int tier=0,recognized=0;String best="none";for(String part:normalized.split(",")){String token=part.trim();if(token.isEmpty())continue;try{Double.parseDouble(token);continue;}catch(NumberFormatException ignored){}String mode=modes.get(token);if(mode==null)continue;recognized++;int current="invasive".equals(mode)?3:"noninvasive".equals(mode)?2:"hfnc".equals(mode)?1:0;if(current>tier){tier=current;best=mode;}}return recognized==0?"unknown":best;}

  private Document requireDocument(String hostPatientId) {
    Query q = Query.query(Criteria.where("_id").is(new ObjectId(requireId(hostPatientId))));
    q.fields().include("name").include("hisPid").include("mrn").include("hospitalTime")
        .include("deptCode").include("deptCode2").include("dept").include("showBed")
        .include("birthday").include("hisBed").include("icuAdmissionTime").include("admissionTime")
        .include("clinicalDiagnosis").include("diagnosis").include("admissionDiagnosis")
        .include("dischargedDiagnosis").include("diagnosisHistoryList");
    Document doc = smartcare.findOne(q, Document.class, "patient");
    if (doc == null) throw ApiException.notFound();
    return doc;
  }

  private static String requireId(String hostPatientId) {
    if (hostPatientId == null || !OBJECT_ID.matcher(hostPatientId).matches())
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATIENT_ID", "患者 id 需为 24 位十六进制");
    return hostPatientId;
  }

  public List<Observation> observations(
      String hostPatientId, String encounterId, String department, Instant from, Instant asOf) {
    Document patient = requireDocument(hostPatientId);
    if (!encounterIdOf(patient).equals(encounterId))
      throw ApiException.conflict("住院上下文已变化，请重新选择患者。");
    Instant admission = toInstant(patient.get("icuAdmissionTime"));
    if (admission == null) admission = toInstant(patient.get("admissionTime"));
    if (admission == null)
      throw new ApiException(HttpStatus.CONFLICT, "ENCOUNTER_BOUNDARY_UNCONFIRMED", "入科边界未确认，暂停跨时间窗取数。");
    Instant boundedFrom = from.isAfter(admission) ? from : admission;
    if (boundedFrom.isAfter(asOf)) return List.of();
    List<Observation> out = new ArrayList<>();
    out.addAll(bedsideObservations(hostPatientId, encounterId, department, boundedFrom, asOf));
    out.addAll(bloodGasObservations(patient, hostPatientId, encounterId, department, boundedFrom, asOf));
    out.addAll(lactateObservations(patient, hostPatientId, department, boundedFrom, asOf));
    out.addAll(scoreObservations(hostPatientId, encounterId, department, boundedFrom, asOf));
    out.addAll(labObservations(patient, hostPatientId, encounterId, department, boundedFrom, asOf));
    return out;
  }

  private List<Observation> bedsideObservations(
      String hostPatientId, String encounterId, String department, Instant from, Instant asOf) {
    List<String> codes = new ArrayList<>(BEDSIDE_TO_PARAMETER.keySet());
    Query q =
        Query.query(
                Criteria.where("pid")
                    .is(hostPatientId)
                    .and("code")
                    .in(codes)
                    .and("valid")
                    .is(true)
                    .and("time")
                    .gte(Date.from(from))
                    .lte(Date.from(asOf)))
            .with(Sort.by(Sort.Direction.DESC, "time"))
            .limit(4000);
    q.fields().include("code").include("strVal").include("time").include("valid");
    List<Observation> rows = new ArrayList<>();
    for (Document d : smartcare.find(q, Document.class, "bedside")) {
      String code = d.getString("code");
      String parameter = BEDSIDE_TO_PARAMETER.get(code);
      if (parameter == null) continue;
      Double value = parseDouble(d.get("strVal"));
      if (value == null && "GCS".equals(parameter)) value = parseGcs(d.get("strVal"));
      if (value == null && !java.util.Set.of("AVPU","HR","RR","TEMP","SBP","SBP_IBP","SBP_NIBP","URINE_OUTPUT","RASS","FIO2","WEIGHT","PEEP_SET","PEEP_MEASURED","PEEP_TOTAL").contains(parameter)) continue;
      Instant event = toInstant(d.get("time"));
      if (event == null) continue;
      Observation o = observation(hostPatientId, encounterId, department, parameter, value,
          unitFor(parameter), event, null,
          "SmartCare.bedside", String.valueOf(d.get("_id")));
      o.sourceParameterCode = code;
      if(java.util.Set.of("FIO2","PEEP_SET","PEEP_MEASURED","PEEP_TOTAL","WEIGHT").contains(parameter)) {
        Document definition=parameterDefinition(code);
        o.unit=dictionaryUnit(definition);
        o.unitBasis=o.unit==null?"unknown":"parameter_dictionary";
        if(o.unit==null){o.clinicalUse="context_only";o.qualityStatus="unverified";o.qualityNote="观察项已取得，单位待核对";}
      }
      if("RASS".equals(parameter)){o.unit="score";if(value==null||value< -5||value>4||value!=Math.rint(value)){o.qualityStatus="invalid";o.qualityNote="RASS超出有效整数范围";}}
      if(value==null&&!"AVPU".equals(parameter)){o.qualityStatus="invalid";o.text=d.getString("strVal");o.qualityNote="最新来源值不是可用于计分的精确数字";}
      if("AVPU".equals(parameter)){o.value=null;o.text=d.getString("strVal");o.unit="AVPU";}
      o.measurementChannel = code.startsWith("param_nibp_") ? "NIBP" : code.startsWith("param_ibp_") ? "IBP" : null;
      rows.add(o);
    }
    return rows;
  }

  /** Lactate and P/F from SmartCare.bGATemp bedsides (mrn link). Observed time, review use when encounter matches. */
  private List<Observation> bloodGasObservations(
      Document patient,
      String hostPatientId,
      String encounterId,
      String department,
      Instant from,
      Instant asOf) {
    String mrn = patient.getString("mrn");
    if (mrn == null || mrn.isBlank()) return List.of();
    List<Observation> rows = new ArrayList<>();
    try {
      Document match = new Document("code",new Document("$in",List.of("param_bg_P/Fratio","param_bg_Lac"))).append("valid","valid").append("time",new Document("$gte",Date.from(from)).append("$lte",Date.from(asOf)));
      Query query=new org.springframework.data.mongodb.core.query.BasicQuery(new Document("mrn",mrn).append("bedsides",new Document("$elemMatch",match))).limit(200);
      query.fields().include("bedsides");
      for(Document d:smartcare.find(query,Document.class,"bGATemp")) {
        Object raw = d.get("bedsides");
        if (!(raw instanceof List)) continue;
        for (Object o : (List<?>) raw) {
          if (!(o instanceof Document)) continue;
          Document b = (Document) o;
          String code=b.getString("code");boolean lactate="param_bg_Lac".equals(code);
          if (!lactate&&!"param_bg_P/Fratio".equals(code)) continue;
          if (!"valid".equals(b.get("valid"))) continue;
          Instant t = toInstant(b.get("time"));
          Double v = bloodGasNumber(b);
          if (t == null || v == null || !Double.isFinite(v) || t.isBefore(from)||t.isAfter(asOf)) continue;
          Observation obs =
              observation(
                  hostPatientId,
                  encounterId,
                  department,
                  lactate?"LACTATE":"PF_RATIO",
                  v,
                  lactate?bloodGasUnit(b,code):PARAMETER_UNITS.get("PF_RATIO"),
                  t,
                  null,
                  "SmartCare.bGATemp",
                  String.valueOf(d.get("_id")));
          obs.id += ":"+code+":"+t;
          obs.sourceParameterCode = code;
          obs.rawNumericValue=parseDouble(b.get("fVal"));obs.reportedNumericText=b.get("strVal") instanceof String?(String)b.get("strVal"):null;obs.unitBasis=b.get("unit")!=null?"source_record":"parameter_dictionary";
          Document definition=parameterDefinition(code);Object precision=definition==null?null:definition.get("floatCount");if(precision instanceof Number&&((Number)precision).intValue()>=0&&((Number)precision).intValue()<=6)obs.displayPrecision=((Number)precision).intValue();
          if(lactate&&(obs.unit==null||!"mmol/l".equalsIgnoreCase(obs.unit.trim()))){obs.clinicalUse="context_only";obs.qualityStatus="unverified";obs.qualityNote="血气乳酸已取得，但单位尚未确认，不参与当前判断。";}
          obs.timeBasis = "observed";
          rows.add(obs);
        }
      }
    } catch (RuntimeException ex) {
      // leave empty; missing P/F is shown as missing, not fabricated
    }
    return rows;
  }

  /**
   * SOFA lab factors from DataCenter.VI_ICU_EXAM_ITEM (PLT / TBIL / creatinine).
   * Linked only by hisPid + authTime (report time). Same unconfirmed policy as lactate reports.
   */
  private List<Observation> labObservations(
      Document patient,
      String hostPatientId,
      String encounterId,
      String department,
      Instant from,
      Instant asOf) {
    String hisPid = patient.getString("hisPid");
    if (hisPid == null || hisPid.isBlank()) return List.of();
    List<Observation> rows = new ArrayList<>();
    try {
      Query q =
          Query.query(
                  Criteria.where("hisPid")
                      .is(hisPid)
                      .and("itemCode")
                      .in(LAB_ITEM_TO_PARAMETER.keySet())
                      .and("authTime")
                      .gte(Date.from(from))
                      .lte(Date.from(asOf)))
              .with(Sort.by(Sort.Direction.DESC, "authTime"))
              .limit(400);
      q.fields()
          .include("itemCode")
          .include("result")
          .include("unit")
          .include("authTime")
          .include("resultStatus");
      for (Document d : dataCenter.find(q, Document.class, "VI_ICU_EXAM_ITEM")) {
        String itemCode = d.getString("itemCode");
        String parameter = itemCode == null ? null : LAB_ITEM_TO_PARAMETER.get(itemCode);
        if (parameter == null) continue;
        Double value = parseDouble(d.get("result"));
        if (value == null) continue;
        Instant event = toInstant(d.get("authTime"));
        if (event == null) continue;
        Observation o =
            observation(
                hostPatientId,
                encounterId,
                department,
                parameter,
                value,
                d.getString("unit"),
                event,
                null,
                "DataCenter.VI_ICU_EXAM_ITEM",
                String.valueOf(d.get("_id")));
        o.sourceParameterCode = itemCode;
        o.timeBasis = "reported";
        o.reportedAt = event;
        o.encounterLinkStatus = "unconfirmed";
        o.clinicalUse = "context_only";
        o.qualityStatus = "unverified";
        o.sourceResultStatus =
            d.get("resultStatus") == null ? null : String.valueOf(d.get("resultStatus"));
        o.qualityNote =
            "仅按 HIS 患者键找到的检验报告；本次住院关联与采样时间未确认，器官功能栏仅作核对参考。";
        rows.add(o);
      }
    } catch (RuntimeException ex) {
      // fail soft: show missing rather than fail whole clinical-data
    }
    return rows;
  }

  private static Double parseGcs(Object raw) {
    return Icu05SourceReader.gcsValue(raw);
  }

  /** Prefer exact code, then strip _IBP/_NIBP channel suffix for shared base units. */
  private static String unitFor(String parameter) {
    String u = PARAMETER_UNITS.get(parameter);
    if (u != null) return u;
    String base = parameter.replaceFirst("_(IBP|NIBP)$", "");
    return PARAMETER_UNITS.get(base);
  }

  private List<Observation> lactateObservations(
      Document patient, String hostPatientId, String department, Instant from, Instant asOf) {
    String hisPid = patient.getString("hisPid");
    if (hisPid == null || hisPid.isBlank()) return List.of();
    Query q =
        Query.query(
                Criteria.where("hisPid")
                    .is(hisPid)
                    .and("itemCode")
                    .regex("(?i)^LAC\\d*$")
                    .and("authTime")
                    .gte(Date.from(from))
                    .lte(Date.from(asOf)))
            .with(Sort.by(Sort.Direction.DESC, "authTime"))
            .limit(400);
    q.fields().include("itemCode").include("result").include("unit").include("authTime")
        .include("resultStatus").include("almFlag").include("seriousFlag");
    List<Observation> rows = new ArrayList<>();
    for (Document d : dataCenter.find(q, Document.class, "VI_ICU_EXAM_ITEM")) {
      String itemCode = d.getString("itemCode");
      if (itemCode == null || !LACTATE_ITEM.matcher(itemCode).matches()) continue;
      Double value = parseDouble(d.get("result"));
      if (value == null) continue;
      Instant event = toInstant(d.get("authTime"));
      if (event == null) continue;
      String unit = d.getString("unit");
      // Unknown unit stays unknown; never manufacture mmol/L.
      Observation o = observation(hostPatientId, null, department, "LACTATE", value, unit,
          event, null, "DataCenter.VI_ICU_EXAM_ITEM", String.valueOf(d.get("_id")));
      o.sourceParameterCode = itemCode;
      o.timeBasis = "reported";
      o.reportedAt = event;
      o.encounterLinkStatus = "unconfirmed";
      o.clinicalUse = "context_only";
      o.qualityStatus = "unverified";
      o.sourceResultStatus = d.get("resultStatus") == null ? null : String.valueOf(d.get("resultStatus"));
      o.qualityNote = "仅按 HIS 患者键找到的报告；本次住院关联、采样时间及结果有效状态未确认，不用于当前判断或治疗时效计算。";
      rows.add(o);
    }
    return rows;
  }

  private List<Observation> scoreObservations(
      String hostPatientId, String encounterId, String department, Instant from, Instant asOf) {
    Query q =
        Query.query(
                Criteria.where("pid")
                    .is(hostPatientId)
                    .and("scoreType")
                    .in("gcsScore", "sofa", "apacheII")
                    .and("valid")
                    .is(true)
                    .and("time")
                    .gte(Date.from(from))
                    .lte(Date.from(asOf)))
            .with(Sort.by(Sort.Direction.DESC, "time"))
            .limit(200);
    q.fields().include("scoreType").include("total").include("time").include("valid");
    List<Observation> rows = new ArrayList<>();
    for (Document d : smartcare.find(q, Document.class, "score")) {
      String type = d.getString("scoreType");
      String parameter;
      if ("gcsScore".equals(type)) parameter = "GCS";
      else if ("sofa".equals(type)) parameter = "SOFA";
      else if ("apacheII".equals(type)) parameter = "APACHEII";
      else continue;
      Double total = d.get("total") instanceof Number ? ((Number) d.get("total")).doubleValue() : null;
      if (total == null) continue;
      Instant event = toInstant(d.get("time"));
      if (event == null) continue;
      rows.add(
          observation(
              hostPatientId,
              encounterId,
              department,
              parameter,
              total,
              "score",
              event,
              null,
              "SmartCare.score",
              String.valueOf(d.get("_id"))));
    }
    return rows;
  }

  /** Of the given host patient ids, those still 在科 (status=admitted) in SmartCare. Invalid ids are ignored. */
  public java.util.Set<String> admittedAmong(java.util.Collection<String> hostPatientIds) {
    List<ObjectId> ids = new ArrayList<>();
    for (String id : hostPatientIds) if (id != null && OBJECT_ID.matcher(id).matches()) ids.add(new ObjectId(id));
    java.util.Set<String> out = new java.util.HashSet<>();
    if (ids.isEmpty()) return out;
    for (Document d : smartcare.getCollection("patient")
        .find(new Document("_id", new Document("$in", ids)).append("status", "admitted"))
        .projection(new Document("_id", 1)))
      out.add(d.getObjectId("_id").toHexString());
    return out;
  }

  public List<Map<String, Object>> listByDept(String deptCode, int limit) {
    return listByDept(deptCode, 0, Math.max(1, Math.min(limit, 100)), "").items;
  }

  public static final class PatientPage {
    public final List<Map<String,Object>> items;
    public final long total;
    PatientPage(List<Map<String,Object>> items,long total){this.items=items;this.total=total;}
  }

  /** Read-only department roster, paged from the live source so batch screening sees every bed. */
  public PatientPage listByDept(String deptCode,int page,int size,String search) {
    int boundedSize=Math.max(1,Math.min(size,100));
    int boundedPage=Math.max(0,Math.min(page,100000));
    Criteria scope=new Criteria().orOperator(Criteria.where("deptCode").is(deptCode),Criteria.where("deptCode2").is(deptCode));
    Criteria query=new Criteria().andOperator(Criteria.where("status").is("admitted"),scope);
    if(search!=null&&!search.isBlank())query=new Criteria().andOperator(query,new Criteria().orOperator(
        Criteria.where("name").regex(Pattern.quote(search),"i"),Criteria.where("showBed").regex(Pattern.quote(search),"i"),Criteria.where("hisBed").regex(Pattern.quote(search),"i")));
    long total=smartcare.getCollection("patient").countDocuments(query.getCriteriaObject());
    List<Map<String, Object>> out = new ArrayList<>();
    for (Document d :
        smartcare
            .getCollection("patient")
            .find(query.getCriteriaObject())
            .sort(new Document("showBed",1).append("hisBed",1).append("_id",1))
            .skip(boundedPage*boundedSize).limit(boundedSize)) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", d.getObjectId("_id").toHexString());
      row.put("label", d.getString("name"));
      row.put("bed", bedOf(d));
      row.put("admittedAt", toInstant(d.get("icuAdmissionTime")));
      row.put("simulated",false);
      row.put("department",deptCode);
      out.add(row);
    }
    return new PatientPage(out,total);
  }

  // ---------- clinical events for sepsis / bundle ----------

  public String diagnosisText(String hostPatientId) {
    Document d =
        smartcare
            .getCollection("patient")
            .find(new Document("_id", new ObjectId(requireId(hostPatientId))))
            .projection(
                new Document("clinicalDiagnosis", 1)
                    .append("admissionDiagnosis", 1)
                    .append("clinicalDiagnosisCodeList", 1))
            .first();
    if (d == null) return "";
    StringBuilder sb = new StringBuilder();
    appendText(sb, d.getString("clinicalDiagnosis"));
    appendText(sb, d.getString("admissionDiagnosis"));
    Object codes = d.get("clinicalDiagnosisCodeList");
    if (codes instanceof List) {
      for (Object o : (List) codes) appendText(sb, String.valueOf(o));
    }
    return sb.toString();
  }

  private static void appendText(StringBuilder sb, String s) {
    if (s == null || s.isBlank()) return;
    if (sb.length() > 0) sb.append('|');
    sb.append(s.trim());
  }

  /** Legacy APIs fail explicitly until execution and encounter mappings are approved. Never return fake zero/empty attainment. */
  @Deprecated
  public Map<String, Object> vitalsWindow(String hostPatientId, Instant from, Instant asOf) {
    throw unverifiedExecution();
  }
  public static final class EventHit {
    public final Instant time;
    public final String name;
    public final Double amountMl;
    public EventHit(Instant time, String name, Double amountMl) { this.time=time; this.name=name; this.amountMl=amountMl; }
    public Instant time() { return time; }
  }
  @Deprecated
  public Optional<EventHit> findDrugEvent(String hostPatientId, Instant from, Instant asOf, List<String> keywords) {
    throw unverifiedExecution();
  }
  @Deprecated
  public Optional<EventHit> findOrderEvent(String hisPid, Instant from, Instant asOf, List<String> keywords) {
    throw unverifiedExecution();
  }
  @Deprecated
  public double fluidVolumeMl(String hostPatientId, Instant from, Instant asOf) {
    throw unverifiedExecution();
  }
  private static ApiException unverifiedExecution() {
    return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SOURCE_EXECUTION_UNVERIFIED",
        "执行时间、状态及住院归属尚未核验；不自动推导给药、输液或治疗达标。");
  }

  private Observation observation(
      String hostPatientId,
      String encounterId,
      String department,
      String parameter,
      Double value,
      String unit,
      Instant eventTime,
      Instant ingestTime,
      String sourceSystem,
      String sourceRecordId) {
    Observation o = new Observation();
    o.id = sourceSystem + ":" + sourceRecordId;
    o.patientId = hostPatientId;
    o.encounterId = encounterId;
    o.department = department;
    o.simulated = false;
    o.parameterCode = parameter;
    o.value = value;
    o.unit = unit;
    o.eventTime = eventTime;
    o.ingestTime = ingestTime;
    o.sourceSystem = sourceSystem;
    o.sourceRecordId = sourceRecordId;
    o.qualityStatus = "valid";
    o.createdAt = null;
    o.timeBasis = "observed";
    o.ingestTimeBasis = "unknown";
    o.encounterLinkStatus = "source_patient_record";
    o.clinicalUse = "review";
    return o;
  }

  private static Patient mapPatient(Document d) {
    Patient p = new Patient();
    p.id = d.getObjectId("_id").toHexString();
    p.patientId = p.id;
    p.label = d.getString("name");
    Instant birth=toInstant(d.get("birthday"));if(birth!=null)p.birthDate=birth.atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate();
    p.bed = bedOf(d);
    p.department = firstNonBlank(d.getString("deptCode"), d.getString("deptCode2"), d.getString("dept"), "UNKNOWN");
    p.simulated = false;
    Instant admitted = toInstant(d.get("icuAdmissionTime"));
    if (admitted == null) admitted = toInstant(d.get("admissionTime"));
    p.admittedAt = admitted;
    p.encounterId = encounterIdOf(d);
    p.createdAt = admitted != null ? admitted : Instant.now();
    return p;
  }

  /** Stable encounter key for one ICU stay; host patient id remains the select-patient key. */
  public static String encounterIdOf(Document d) {
    String mrn = firstNonBlank(d.getString("mrn"), d.getString("hisPid"), d.getString("hospitalTime"));
    Instant admitted = toInstant(d.get("icuAdmissionTime"));
    if (admitted == null) admitted = toInstant(d.get("admissionTime"));
    String stamp = admitted != null ? Long.toString(admitted.toEpochMilli()) : "unknown";
    return mrn + "|" + stamp;
  }

  public static String bedOf(Document d) {
    return firstNonBlank(d.getString("showBed"), d.getString("hisBed"), "");
  }

  private static String firstNonBlank(String... values) {
    if (values == null) return "";
    for (String v : values) if (v != null && !v.isBlank()) return v;
    return "";
  }

  private static Instant toInstant(Object raw) {
    if (raw == null) return null;
    if (raw instanceof Date) return ((Date) raw).toInstant();
    if (raw instanceof Instant) return (Instant) raw;
    return null;
  }

  private static Double parseDouble(Object raw) {
    if (raw == null) return null;
    if (raw instanceof Number) return ((Number) raw).doubleValue();
    String s = String.valueOf(raw).trim();
    if (s.isEmpty()) return null;
    try {
      double value = Double.parseDouble(s);
      return Double.isFinite(value) ? value : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }
  private static class GasDefinition {final java.time.Instant at=java.time.Instant.now();final Document value;GasDefinition(Document value){this.value=value;}}
  private final java.util.Map<String,GasDefinition> gasDefinitions=new java.util.concurrent.ConcurrentHashMap<>();
  private Document parameterDefinition(String code){GasDefinition cached=gasDefinitions.get(code);if(cached!=null&&cached.at.plusSeconds(300).isAfter(java.time.Instant.now()))return cached.value;try{Query q=Query.query(org.springframework.data.mongodb.core.query.Criteria.where("code").is(code).and("valid").is(true));q.fields().include("unit").include("unitCode").include("floatCount");Document value=smartcare.findOne(q,Document.class,"configParam");gasDefinitions.put(code,new GasDefinition(value));return value;}catch(RuntimeException ignored){gasDefinitions.put(code,new GasDefinition(null));return null;}}
  private static String dictionaryUnit(Document definition){if(definition==null)return null;Object explicit=definition.get("unit");if(explicit instanceof String&&!((String)explicit).isBlank())return (String)explicit;return "unit_cmH2O".equals(definition.get("unitCode"))?"cmH2O":null;}
  public String bloodGasUnit(Document point,String code){Object own=point.get("unit");if(own instanceof String&&!((String)own).isBlank())return (String)own;Document definition=parameterDefinition(code);return definition!=null&&definition.get("unit") instanceof String?(String)definition.get("unit"):null;}
  public static Double bloodGasNumber(Document point){Double numeric=parseDouble(point.get("fVal")),text=parseDouble(point.get("strVal"));Object raw=point.get("strVal");if(raw instanceof String&&!((String)raw).isBlank()&&text==null)return null;if(numeric==null)return text;if(text==null)return numeric;return Float.compare(numeric.floatValue(),text.floatValue())==0?text:null;}
}
