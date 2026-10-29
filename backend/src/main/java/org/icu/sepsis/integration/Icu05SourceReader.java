package org.icu.sepsis.integration;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.icu.sepsis.api.ApiException;
import org.icu.sepsis.sepsis.Icu05Rules;
import org.icu.sepsis.sepsis.Icu05Rules.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Read-only evidence collection for ICU-05 (director rule set). Only find() is used; nothing is written
 * to SmartCare / DataCenter. Each source failure is recorded so the affected items become null, never false.
 *
 * <p>Linkage (same as icu-quality-dashboard): SmartCare patient._id hex → bedside/score/drugExe.pid;
 * patient.mrn → bGATemp.mrn; patient.hisPid → DataCenter.VI_ICU_ZYYZ.pid.
 */
@Component
@ConditionalOnProperty(name = "app.source.mode", havingValue = "smartcare")
public class Icu05SourceReader {
  private static final Pattern OBJECT_ID = Pattern.compile("^[a-fA-F0-9]{24}$");
  private static final Duration CACHE_TTL = Duration.ofMinutes(30);
  private final MongoTemplate smartcare;
  private final MongoTemplate dataCenter;
  private final SmartCareSourceReader patients;
  private final List<String> bloodCultureKeywords;
  private final String cultureTimeField;
  private volatile Set<String> vasoDict = null, abxDict = null;
  private volatile Instant dictLoaded = Instant.EPOCH;

  public Icu05SourceReader(
      @Qualifier("smartcareMongoTemplate") MongoTemplate smartcare,
      @Qualifier("dataCenterMongoTemplate") MongoTemplate dataCenter,
      SmartCareSourceReader patients,
      @Value("${app.icu05.blood-culture-keywords:血培养}") String bloodCultureKeywords,
      @Value("${app.icu05.culture-time-field:reviewTime}") String cultureTimeField) {
    this.smartcare = smartcare;
    this.dataCenter = dataCenter;
    this.patients = patients;
    List<String> kw = new ArrayList<>();
    for (String k : bloodCultureKeywords.split("[,，|]")) if (!k.isBlank()) kw.add(k.trim());
    this.bloodCultureKeywords = kw.isEmpty() ? List.of("血培养") : List.copyOf(kw);
    this.cultureTimeField = "orderTime".equals(cultureTimeField) ? "orderTime" : "reviewTime";
  }

  public Evidence collect(String hostPatientId, String encounterId, Instant asOf) {
    return collect(hostPatientId, encounterId, asOf, Duration.ofHours(6), false);
  }

  /** @param after how far after T0 to read (bedside ICU-05 uses 6h; research export uses 24h+). */
  public Evidence collect(String hostPatientId, String encounterId, Instant asOf, Duration after, boolean withHeartRate) {
    if (hostPatientId == null || !OBJECT_ID.matcher(hostPatientId).matches())
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATIENT_ID", "患者 id 需为 24 位十六进制");
    Document p = smartcare.getCollection("patient").find(new Document("_id", new ObjectId(hostPatientId)))
        .projection(new Document("mrn", 1).append("hisPid", 1).append("hospitalTime", 1).append("icuAdmissionTime", 1)
            .append("admissionTime", 1).append("admissionType", 1)).first();
    if (p == null) throw ApiException.notFound();
    if (encounterId != null && !SmartCareSourceReader.encounterIdOf(p).equals(encounterId))
      throw ApiException.conflict("住院上下文已变化，请重新选择患者。");
    Evidence e = new Evidence();
    e.asOf = asOf;
    e.bloodCultureKeywords = bloodCultureKeywords;
    e.cultureTimeField = cultureTimeField;
    e.icuAdmission = toInstant(p.get("icuAdmissionTime"));
    e.hospitalAdmission = toInstant(p.get("admissionTime"));
    e.admissionType = p.getString("admissionType");
    String mrn = p.getString("mrn"), hisPid = p.getString("hisPid");
    try { e.diagnosisText = patients.diagnosisText(hostPatientId); } catch (RuntimeException ex) { e.failed.add("diagnosis"); }
    // ---- T0 = first VI_ICU_ZYYZ order after ICU admission ----
    if (e.icuAdmission == null || blank(hisPid)) return e;
    try {
      Document first = dataCenter.getCollection("VI_ICU_ZYYZ")
          .find(new Document("pid", hisPid).append("orderTime", new Document("$gte", Date.from(e.icuAdmission)).append("$lte", Date.from(asOf))))
          .projection(new Document("orderTime", 1).append("orderName", 1)).sort(new Document("orderTime", 1)).limit(1).first();
      if (first != null) { e.t0 = toInstant(first.get("orderTime")); e.t0OrderName = first.getString("orderName"); }
    } catch (RuntimeException ex) { e.failed.add("VI_ICU_ZYYZ"); return e; }
    if (e.t0 == null) return e;
    Instant from = e.t0.minus(Duration.ofHours(24)), to = e.t0.plus(after);
    if (to.isAfter(asOf)) to = asOf;
    Date dFrom = Date.from(from), dTo = Date.from(to);
    loadDictionaries(e);
    readBloodGas(e, mrn, hisPid, dFrom, dTo);
    readGcs(e, hostPatientId, dFrom, dTo);
    readMap(e, hostPatientId, dFrom, dTo);
    if (withHeartRate) readHeartRate(e, hostPatientId, dFrom, dTo);
    readDrugs(e, hostPatientId, dFrom, dTo);
    readCultures(e, hisPid, dFrom, dTo);
    return e;
  }

  private void readBloodGas(Evidence e, String mrn, String hisPid, Date from, Date to) {
    // Primary: SmartCare.bGATemp by mrn (director rule).
    if (!blank(mrn)) {
      try {
        Set<String> seen = new HashSet<>();
        Document match =
            new Document("code", new Document("$in", List.of("param_bg_Lac", "param_bg_P/Fratio")))
                .append("valid", "valid")
                .append("time", new Document("$gte", from).append("$lte", to));
        for (Document d :
            smartcare
                .getCollection("bGATemp")
                .find(new Document("mrn", mrn).append("bedsides", new Document("$elemMatch", match)))
                .projection(new Document("bedsides", 1))
                .limit(500)) {
          Object raw = d.get("bedsides");
          if (!(raw instanceof List)) continue;
          for (Object o : (List<?>) raw) {
            if (!(o instanceof Document)) continue;
            Document b = (Document) o;
            String code = b.getString("code");
            if (!"valid".equals(b.get("valid"))) continue;
            Instant t = toInstant(b.get("time"));
            Double v = num(b.get("fVal"));
            if (v == null) v = num(b.get("strVal"));
            if (t == null || v == null || t.isBefore(from.toInstant()) || t.isAfter(to.toInstant()))
              continue;
            String key = code + "|" + t + "|" + v;
            if (!seen.add(key)) continue;
            Point pt = new Point(t, v, null, "SmartCare.bGATemp:" + d.get("_id"));
            if ("param_bg_Lac".equals(code)) e.lactate.add(pt);
            else if ("param_bg_P/Fratio".equals(code)) e.pf.add(pt);
          }
        }
      } catch (RuntimeException ex) {
        e.failed.add("bGATemp");
      }
    }
    // Secondary lactate only: DataCenter EXAM_ITEM by hisPid, and only when bGATemp gave no lactate
    // (authTime = report time, later than sampling, so the two are not mixed). itemCode LAC/LAC1 only (no LDH).
    if (!blank(hisPid) && e.lactate.isEmpty()) {
      try {
        for (Document d :
            dataCenter
                .getCollection("VI_ICU_EXAM_ITEM")
                .find(
                    new Document("hisPid", hisPid)
                        .append("itemCode", new Document("$regex", "^(LAC|LAC[0-9]+)$"))
                        .append("authTime", new Document("$gte", from).append("$lte", to)))
                .projection(
                    new Document("itemCode", 1)
                        .append("result", 1)
                        .append("unit", 1)
                        .append("authTime", 1)
                        .append("_id", 1))
                .limit(400)) {
          Double v = num(d.get("result"));
          Instant t = toInstant(d.get("authTime"));
          if (v == null || t == null) continue;
          boolean dup =
              e.lactate.stream()
                  .anyMatch(
                      p ->
                          Math.abs(p.value - v) < 1e-9
                              && Math.abs(Duration.between(p.time, t).toSeconds()) < 60);
          if (dup) continue;
          e.lactate.add(
              new Point(t, v, "LAB_REPORT_TIME", "DataCenter.VI_ICU_EXAM_ITEM:" + d.get("_id")));
        }
      } catch (RuntimeException ex) {
        e.failed.add("VI_ICU_EXAM_ITEM.lactate");
      }
    }
  }

  private void readGcs(Evidence e, String pid, Date from, Date to) {
    try {
      for (Document d : smartcare.getCollection("score").find(new Document("pid", pid).append("scoreType", "gcsScore").append("valid", true)
          .append("time", new Document("$gte", from).append("$lte", to))).projection(new Document("total", 1).append("time", 1)).limit(300)) {
        Double v = num(d.get("total")); Instant t = toInstant(d.get("time"));
        if (v != null && t != null) e.gcs.add(new Point(t, v, null, "SmartCare.score:" + d.get("_id")));
      }
      if (!e.gcs.isEmpty()) return;
      // Fallback: bedside GCS observation, numeric or E?V?M? text.
      for (Document d : smartcare.getCollection("bedside").find(new Document("pid", pid).append("code", "param_score_gcs_obs").append("valid", true)
          .append("time", new Document("$gte", from).append("$lte", to))).projection(new Document("strVal", 1).append("time", 1)).limit(300)) {
        Double v = gcsValue(d.get("strVal")); Instant t = toInstant(d.get("time"));
        if (v != null && t != null) e.gcs.add(new Point(t, v, null, "SmartCare.bedside:" + d.get("_id")));
      }
    } catch (RuntimeException ex) { e.failed.add("score"); }
  }

  static Double gcsValue(Object raw) {
    if (raw == null) return null;
    String s = String.valueOf(raw).trim();
    Double n = num(s);
    if (n != null) return n;
    java.util.regex.Matcher m = Pattern.compile("(?i)E\\s*(\\d)\\s*V\\s*(\\d|T)\\s*M\\s*(\\d)").matcher(s);
    if (!m.find() || "T".equalsIgnoreCase(m.group(2))) return null;
    return (double) (Integer.parseInt(m.group(1)) + Integer.parseInt(m.group(2)) + Integer.parseInt(m.group(3)));
  }

  private void readMap(Evidence e, String pid, Date from, Date to) {
    try {
      for (Document d : smartcare.getCollection("bedside").find(new Document("pid", pid).append("code", new Document("$in", List.of("param_nibp_m", "param_ibp_m")))
          .append("valid", true).append("time", new Document("$gte", from).append("$lte", to))).projection(new Document("code", 1).append("strVal", 1).append("time", 1)).sort(new Document("time", 1)).limit(20000)) {
        Double v = num(d.get("strVal")); Instant t = toInstant(d.get("time"));
        if (v == null || t == null || v <= 0) continue;
        e.map.add(new Point(t, v, "param_ibp_m".equals(d.getString("code")) ? "IBP" : "NIBP", "SmartCare.bedside:" + d.get("_id")));
      }
    } catch (RuntimeException ex) { e.failed.add("bedside"); }
  }

  private void readHeartRate(Evidence e, String pid, Date from, Date to) {
    try {
      for (Document d : smartcare.getCollection("bedside").find(new Document("pid", pid).append("code", "param_HR").append("valid", true)
          .append("time", new Document("$gte", from).append("$lte", to))).projection(new Document("strVal", 1).append("time", 1))
          .sort(new Document("time", 1)).limit(20000)) {
        Double v = num(d.get("strVal")); Instant t = toInstant(d.get("time"));
        if (v != null && t != null && v > 0) e.hr.add(new Point(t, v, null, "SmartCare.bedside:" + d.get("_id")));
      }
    } catch (RuntimeException ex) { e.failed.add("bedside"); }
  }

  private void readDrugs(Evidence e, String pid, Date from, Date to) {
    try {
      Document q = new Document("pid", new Document("$in", List.of(pid, new ObjectId(pid)))).append("startTime", new Document("$gte", from).append("$lte", to));
      for (Document d : smartcare.getCollection("drugExe").find(q)
          .projection(new Document("startTime", 1).append("drugList", 1).append("drugActionList", 1)).limit(2000)) {
        Drug drug = new Drug();
        drug.start = toInstant(d.get("startTime"));
        drug.sourceId = "SmartCare.drugExe:" + d.get("_id");
        if (drug.start == null) continue;
        Object list = d.get("drugList");
        // 父级 liquidAmountUnit 作默认容量单位（本院常见 ml）；子项 liquidUnit 缺省时视为已知 ml
        String parentUnit = d.getString("liquidAmountUnit");
        boolean parentUnitMl = parentUnit == null || parentUnit.isBlank() || "ml".equalsIgnoreCase(parentUnit.trim());
        if (list instanceof List) for (Object o : (List<?>) list) {
          if (!(o instanceof Document)) continue;
          Document it = (Document) o;
          DrugItem item = new DrugItem(it.getString("name"), num(it.get("liquidAmount")));
          Object unit = it.get("liquidUnit");
          if (unit == null && !parentUnitMl) item.liquidUnitKnown = false;
          else if (unit != null && !String.valueOf(unit).isBlank() && !String.valueOf(unit).trim().equalsIgnoreCase("ml"))
            item.liquidUnitKnown = false;
          drug.items.add(item);
        }
        Object acts = d.get("drugActionList");
        if (acts instanceof List) for (Object o : (List<?>) acts) {
          if (!(o instanceof Document)) continue;
          Document a = (Document) o;
          Object act = a.get("action") != null ? a.get("action") : a.get("type");
          drug.actions.add(new Action(toInstant(a.get("time")), act == null ? null : String.valueOf(act)));
        }
        e.drugs.add(drug);
      }
    } catch (RuntimeException ex) { e.failed.add("drugExe"); }
  }

  private void readCultures(Evidence e, String hisPid, Date from, Date to) {
    try {
      for (Document d : dataCenter.getCollection("VI_ICU_ZYYZ").find(new Document("pid", hisPid)
          .append("orderName", new Document("$regex", Icu05Rules.CULTURE_REGEX)).append("orderTime", new Document("$gte", from).append("$lte", to)))
          .projection(new Document("orderName", 1).append("orderTime", 1).append("reviewTime", 1)).limit(500)) {
        e.cultureOrders.add(new Order(d.getString("orderName"), toInstant(d.get("orderTime")), toInstant(d.get("reviewTime")), "DataCenter.VI_ICU_ZYYZ:" + d.get("_id")));
      }
    } catch (RuntimeException ex) { e.failed.add("VI_ICU_ZYYZ"); }
  }

  private void loadDictionaries(Evidence e) {
    if (vasoDict == null || Instant.now().isAfter(dictLoaded.plus(CACHE_TTL))) {
      try {
        Set<String> vaso = new HashSet<>(), abx = new HashSet<>();
        for (Document d : smartcare.getCollection("configDrug").find(new Document("classification", new Document("$regex", "血管活性|抗菌|抗生素|抗感染")))
            .projection(new Document("name", 1).append("classification", 1)).limit(5000)) {
          String name = d.getString("name"), cls = String.valueOf(d.get("classification"));
          if (blank(name)) continue;
          String n = name.trim().toLowerCase(Locale.ROOT);
          if (cls.contains("血管活性")) vaso.add(n); else abx.add(n);
        }
        vasoDict = vaso; abxDict = abx; dictLoaded = Instant.now();
      } catch (RuntimeException ex) {
        // Dictionary is supplementary; keyword matching still applies.
        if (vasoDict == null) { vasoDict = Set.of(); abxDict = Set.of(); }
      }
    }
    e.vasoDictionary = vasoDict; e.antibioticDictionary = abxDict;
  }

  private static boolean blank(String s) { return s == null || s.isBlank(); }
  private static Instant toInstant(Object raw) {
    if (raw instanceof Date) return ((Date) raw).toInstant();
    if (raw instanceof Instant) return (Instant) raw;
    return null;
  }
  static Double num(Object raw) {
    if (raw == null) return null;
    if (raw instanceof Number) { double v = ((Number) raw).doubleValue(); return Double.isFinite(v) ? v : null; }
    String s = String.valueOf(raw).trim();
    if (s.isEmpty()) return null;
    try { double v = Double.parseDouble(s); return Double.isFinite(v) ? v : null; } catch (NumberFormatException ex) { return null; }
  }
}
