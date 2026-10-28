package org.icu.sepsis.integration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
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
      "param_nibp_m", "MAP",
      "param_nibp_s", "SBP",
      "param_nibp_d", "DBP",
      "param_resp", "RR",
      "param_spo2", "SPO2",
      "param_T", "TEMP",
      "param_ibp_m", "MAP",
      "param_ibp_s", "SBP",
      "param_ibp_d", "DBP");

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
    return hisPid == null || hisPid.isBlank() ? doc.getString("mrn") : hisPid;
  }

  private Document requireDocument(String hostPatientId) {
    Document doc =
        smartcare
            .getCollection("patient")
            .find(new Document("_id", new ObjectId(requireId(hostPatientId))))
            .first();
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
    List<Observation> out = new ArrayList<>();
    out.addAll(bedsideObservations(hostPatientId, encounterId, department, from, asOf));
    out.addAll(lactateObservations(hostPatientId, encounterId, department, from, asOf));
    out.addAll(scoreObservations(hostPatientId, encounterId, department, from, asOf));
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
    List<Observation> rows = new ArrayList<>();
    for (Document d : smartcare.getCollection("bedside").find(q.getQueryObject())) {
      String code = d.getString("code");
      String parameter = BEDSIDE_TO_PARAMETER.get(code);
      if (parameter == null) continue;
      Double value = parseDouble(d.get("strVal"));
      if (value == null) continue;
      Instant event = toInstant(d.get("time"));
      if (event == null) continue;
      rows.add(
          observation(
              hostPatientId,
              encounterId,
              department,
              parameter,
              value,
              PARAMETER_UNITS.get(parameter),
              event,
              event,
              "SmartCare.bedside",
              String.valueOf(d.get("_id"))));
    }
    return rows;
  }

  private List<Observation> lactateObservations(
      String hostPatientId, String encounterId, String department, Instant from, Instant asOf) {
    Document patient =
        smartcare
            .getCollection("patient")
            .find(new Document("_id", new ObjectId(hostPatientId)))
            .projection(new Document("hisPid", 1))
            .first();
    if (patient == null) return List.of();
    String hisPid = patient.getString("hisPid");
    if (hisPid == null || hisPid.isBlank()) return List.of();
    Query q =
        Query.query(
                Criteria.where("hisPid")
                    .is(hisPid)
                    .and("itemCode")
                    .regex("(?i)LAC\\d*")
                    .and("authTime")
                    .gte(Date.from(from))
                    .lte(Date.from(asOf)))
            .with(Sort.by(Sort.Direction.DESC, "authTime"))
            .limit(400);
    List<Observation> rows = new ArrayList<>();
    for (Document d : dataCenter.getCollection("VI_ICU_EXAM_ITEM").find(q.getQueryObject())) {
      String itemCode = d.getString("itemCode");
      if (itemCode == null || !LACTATE_ITEM.matcher(itemCode).matches()) continue;
      Double value = parseDouble(d.get("result"));
      if (value == null) continue;
      Instant event = toInstant(d.get("authTime"));
      if (event == null) continue;
      String unit = d.getString("unit");
      if (unit == null || unit.isBlank()) unit = "mmol/L";
      rows.add(
          observation(
              hostPatientId,
              encounterId,
              department,
              "LACTATE",
              value,
              unit,
              event,
              event,
              "DataCenter.VI_ICU_EXAM_ITEM",
              String.valueOf(d.get("_id"))));
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
    List<Observation> rows = new ArrayList<>();
    for (Document d : smartcare.getCollection("score").find(q.getQueryObject())) {
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
              event,
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
                    .append("clinicalDiagnosisCodeList", 1)
                    .append("diagnosisHistoryList", 1))
            .first();
    if (d == null) return "";
    StringBuilder sb = new StringBuilder();
    appendText(sb, d.getString("clinicalDiagnosis"));
    appendText(sb, d.getString("admissionDiagnosis"));
    Object codes = d.get("clinicalDiagnosisCodeList");
    if (codes instanceof List) {
      for (Object o : (List) codes) appendText(sb, String.valueOf(o));
    }
    Object hist = d.get("diagnosisHistoryList");
    if (hist instanceof List) {
      for (Object o : (List) hist) {
        if (o instanceof Document) {
          Document hd = (Document) o;
          appendText(sb, hd.getString("diagnosis"));
          appendText(sb, hd.getString("name"));
        }
      }
    }
    return sb.toString();
  }

  private static void appendText(StringBuilder sb, String s) {
    if (s == null || s.isBlank()) return;
    if (sb.length() > 0) sb.append('|');
    sb.append(s.trim());
  }

  /** Window stats for MAP/SBP/RR/GCS/LACTATE. */
  public Map<String, Object> vitalsWindow(String hostPatientId, Instant from, Instant asOf) {
    Map<String, Double> mins = new HashMap<>();
    Map<String, Double> maxs = new HashMap<>();
    Map<String, Double> latest = new HashMap<>();
    Map<String, Instant> latestAt = new HashMap<>();
    for (Observation o : observations(hostPatientId, "", "", from, asOf)) {
      if (o.value == null || o.eventTime == null) continue;
      String c = o.parameterCode;
      if (c == null) continue;
      mins.merge(c, o.value, Math::min);
      maxs.merge(c, o.value, Math::max);
      Instant prev = latestAt.get(c);
      if (prev == null || o.eventTime.isAfter(prev)) {
        latest.put(c, o.value);
        latestAt.put(c, o.eventTime);
      }
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("mapMin", mins.get("MAP"));
    out.put("mapMax", maxs.get("MAP"));
    out.put("sbpMin", mins.get("SBP"));
    out.put("rrMax", maxs.get("RR"));
    out.put("lactateMax", maxs.get("LACTATE"));
    out.put("gcsMin", mins.get("GCS"));
    out.put("sofaMax", maxs.get("SOFA"));
    out.put("hrMax", maxs.get("HR"));
    out.put("tempMax", maxs.get("TEMP"));
    out.put("latest", latest);
    return out;
  }

  public static final class EventHit {
    public final Instant time;
    public final String name;
    public final Double amountMl;
    public EventHit(Instant time, String name, Double amountMl) {
      this.time = time;
      this.name = name;
      this.amountMl = amountMl;
    }
    public Instant time() {
      return time;
    }
  }

  public Optional<EventHit> findDrugEvent(String hostPatientId, Instant from, Instant asOf, List<String> keywords) {
    Instant best = null;
    String bestName = null;
    for (Document d :
        smartcare
            .getCollection("drugExe")
            .find(
                new Document("pid", hostPatientId)
                    .append("createdTime", new Document("$gte", Date.from(from)).append("$lte", Date.from(asOf))))
            .limit(500)) {
      String names = drugNames(d);
      if (!org.icu.sepsis.sepsis.SepsisClinicalRules.anyKeyword(names, keywords)) continue;
      Instant t = toInstant(d.get("createdTime"));
      if (t == null) continue;
      if (best == null || t.isBefore(best)) {
        best = t;
        bestName = names;
      }
    }
    if (best == null) return Optional.empty();
    return Optional.of(new EventHit(best, bestName, null));
  }

  public Optional<EventHit> findOrderEvent(String hisPid, Instant from, Instant asOf, List<String> keywords) {
    if (hisPid == null || hisPid.isBlank()) return Optional.empty();
    Instant best = null;
    String bestName = null;
    for (Document d :
        dataCenter
            .getCollection("VI_ICU_ZYYZ")
            .find(
                new Document("pid", hisPid)
                    .append("orderTime", new Document("$gte", Date.from(from)).append("$lte", Date.from(asOf))))
            .limit(800)) {
      String name = firstNonBlank(d.getString("orderName"), "");
      if (!org.icu.sepsis.sepsis.SepsisClinicalRules.anyKeyword(name, keywords)) continue;
      String status = firstNonBlank(d.getString("status"), "");
      if (status.startsWith("停") || "已停止".equals(status)) continue;
      Instant t = toInstant(d.get("orderTime"));
      if (t == null) continue;
      if (best == null || t.isBefore(best)) {
        best = t;
        bestName = name;
      }
    }
    if (best == null) return Optional.empty();
    return Optional.of(new EventHit(best, bestName, null));
  }

  public double fluidVolumeMl(String hostPatientId, Instant from, Instant asOf) {
    double total = 0;
    for (Document d :
        smartcare
            .getCollection("drugExe")
            .find(
                new Document("pid", hostPatientId)
                    .append("createdTime", new Document("$gte", Date.from(from)).append("$lte", Date.from(asOf))))
            .limit(800)) {
      String names = drugNames(d);
      if (!org.icu.sepsis.sepsis.SepsisClinicalRules.anyKeyword(
              names, org.icu.sepsis.sepsis.SepsisClinicalRules.CRYSTALLOID)
          && !org.icu.sepsis.sepsis.SepsisClinicalRules.anyKeyword(
              names, org.icu.sepsis.sepsis.SepsisClinicalRules.COLLOID)) continue;
      Object li = d.get("liquidAmount");
      if (li instanceof Number) total += ((Number) li).doubleValue();
      Object drugs = d.get("drugList");
      if (drugs instanceof List) {
        for (Object o : (List) drugs) {
          if (o instanceof Document && ((Document) o).get("liquidAmount") instanceof Number)
            total += ((Number) ((Document) o).get("liquidAmount")).doubleValue();
        }
      }
    }
    return total;
  }

  private static String drugNames(Document d) {
    StringBuilder sb = new StringBuilder();
    Object list = d.get("drugList");
    if (list instanceof List) {
      for (Object o : (List) list) {
        if (o instanceof Document) {
          String n = ((Document) o).getString("name");
          if (n != null) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(n);
          }
        }
      }
    }
    return sb.toString();
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
    o.createdAt = ingestTime;
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
    int cut = 0;
    while (cut < s.length()
        && (Character.isDigit(s.charAt(cut))
            || s.charAt(cut) == '.'
            || s.charAt(cut) == '-'
            || s.charAt(cut) == '+')) cut++;
    if (cut == 0) return null;
    try {
      return Double.parseDouble(s.substring(0, cut));
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
