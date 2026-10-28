package org.icu.sepsis.integration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

  private static final Map<String, String> BEDSIDE_TO_PARAMETER = Map.of(
      "param_HR", "HR",
      "param_nibp_m", "MAP_NIBP",
      "param_nibp_s", "SBP_NIBP",
      "param_nibp_d", "DBP_NIBP",
      "param_resp", "RR",
      "param_spo2", "SPO2",
      "param_T", "TEMP",
      "param_ibp_m", "MAP_IBP",
      "param_ibp_s", "SBP_IBP",
      "param_ibp_d", "DBP_IBP");

  private static final Map<String, String> PARAMETER_UNITS = Map.of(
      "HR", "bpm",
      "MAP", "mmHg",
      "SBP", "mmHg",
      "DBP", "mmHg",
      "RR", "/min",
      "SPO2", "%",
      "TEMP", "°C",
      "LACTATE", "mmol/L",
      "GCS", "score");

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

  private Document requireDocument(String hostPatientId) {
    Query q = Query.query(Criteria.where("_id").is(new ObjectId(requireId(hostPatientId))));
    q.fields().include("name").include("hisPid").include("mrn").include("hospitalTime")
        .include("deptCode").include("deptCode2").include("dept").include("showBed")
        .include("hisBed").include("icuAdmissionTime").include("admissionTime");
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
    out.addAll(lactateObservations(patient, hostPatientId, department, boundedFrom, asOf));
    out.addAll(scoreObservations(hostPatientId, encounterId, department, boundedFrom, asOf));
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
      if (value == null) continue;
      Instant event = toInstant(d.get("time"));
      if (event == null) continue;
      Observation o = observation(hostPatientId, encounterId, department, parameter, value,
          PARAMETER_UNITS.get(parameter.split("_")[0]), event, null,
          "SmartCare.bedside", String.valueOf(d.get("_id")));
      o.sourceParameterCode = code;
      o.measurementChannel = code.startsWith("param_nibp_") ? "NIBP" : code.startsWith("param_ibp_") ? "IBP" : null;
      rows.add(o);
    }
    return rows;
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

  public List<Map<String, Object>> listByDept(String deptCode, int limit) {
    int size = Math.max(1, Math.min(limit, 100));
    List<Map<String, Object>> out = new ArrayList<>();
    for (Document d :
        smartcare
            .getCollection("patient")
            .find(
                new Document("status", "admitted")
                    .append(
                        "$or",
                        List.of(
                            new Document("deptCode", deptCode),
                            new Document("deptCode2", deptCode))))
            .limit(size)) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", d.getObjectId("_id").toHexString());
      row.put("label", d.getString("name"));
      row.put("bed", bedOf(d));
      row.put("hisPid", d.getString("hisPid"));
      row.put("mrn", d.getString("mrn"));
      row.put("dept", d.getString("dept"));
      row.put("deptCode", firstNonBlank(d.getString("deptCode"), d.getString("deptCode2")));
      row.put("gender", d.getString("gender"));
      row.put("admittedAt", toInstant(d.get("icuAdmissionTime")));
      out.add(row);
    }
    return out;
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
}
