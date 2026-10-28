package org.icu.sepsis.integration;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Read-only extras for the research cohort export: stay list, demographics/outcome fields,
 * score totals and SOFA labs. find() only; never writes SmartCare / DataCenter.
 */
@Component
@ConditionalOnProperty(name = "app.source.mode", havingValue = "smartcare")
public class ResearchSourceReader {
  public static final Map<String, List<String>> LAB_CODES = Map.of(
      "PLT", List.of("PLT"), "TBIL", List.of("TBIL"), "CREA", List.of("CREA", "sCr", "Cr"));
  private final MongoTemplate smartcare, dataCenter;

  public ResearchSourceReader(@Qualifier("smartcareMongoTemplate") MongoTemplate smartcare,
      @Qualifier("dataCenterMongoTemplate") MongoTemplate dataCenter) {
    this.smartcare = smartcare; this.dataCenter = dataCenter;
  }

  /** ICU stays admitted in [from, to); status=invalid excluded. Returns patient documents (no clinical content). */
  public List<Document> stays(Instant from, Instant to, int limit) {
    List<Document> out = new ArrayList<>();
    for (Document d : smartcare.getCollection("patient").find(new Document("icuAdmissionTime",
            new Document("$gte", Date.from(from)).append("$lt", Date.from(to))).append("status", new Document("$ne", "invalid")))
        .projection(new Document("_id", 1).append("mrn", 1).append("hisPid", 1).append("hospitalTime", 1)
            .append("icuAdmissionTime", 1).append("admissionTime", 1).append("icuDischargeTime", 1).append("dischargeTime", 1)
            .append("dischargedType", 1).append("admissionType", 1).append("gender", 1).append("age", 1).append("birthday", 1)
            .append("weight", 1).append("deptCode", 1))
        .sort(new Document("icuAdmissionTime", 1)).limit(Math.max(1, limit))) out.add(d);
    return out;
  }

  public static final class Value {
    public final Instant time; public final double value; public final String unit, code;
    public Value(Instant time, double value, String unit, String code) { this.time = time; this.value = value; this.unit = unit; this.code = code; }
  }

  /** SOFA labs (PLT / TBIL / CREA) by hisPid, report time in [from, to]. Report time ≠ sampling time. */
  public Map<String, List<Value>> labs(String hisPid, Instant from, Instant to, Set<String> failed) {
    Map<String, List<Value>> out = new LinkedHashMap<>();
    for (String k : LAB_CODES.keySet()) out.put(k, new ArrayList<>());
    if (hisPid == null || hisPid.isBlank()) { failed.add("VI_ICU_EXAM_ITEM"); return out; }
    List<String> codes = new ArrayList<>();
    LAB_CODES.values().forEach(codes::addAll);
    try {
      for (Document d : dataCenter.getCollection("VI_ICU_EXAM_ITEM").find(new Document("hisPid", hisPid).append("itemCode", new Document("$in", codes))
          .append("authTime", new Document("$gte", Date.from(from)).append("$lte", Date.from(to))))
          .projection(new Document("itemCode", 1).append("result", 1).append("unit", 1).append("authTime", 1)).limit(2000)) {
        Double v = Icu05SourceReader.num(d.get("result"));
        Object t = d.get("authTime");
        if (v == null || !(t instanceof Date)) continue;
        String code = d.getString("itemCode");
        for (Map.Entry<String, List<String>> e : LAB_CODES.entrySet())
          if (e.getValue().contains(code)) out.get(e.getKey()).add(new Value(((Date) t).toInstant(), v, d.getString("unit"), code));
      }
    } catch (RuntimeException ex) { failed.add("VI_ICU_EXAM_ITEM"); }
    return out;
  }

  /** score totals (sofa / apacheII) with APACHE II predicted mortality when present. */
  public List<Document> scores(String pid, Instant from, Instant to, Set<String> failed) {
    List<Document> out = new ArrayList<>();
    try {
      for (Document d : smartcare.getCollection("score").find(new Document("pid", pid).append("scoreType", new Document("$in", List.of("sofa", "apacheII")))
          .append("valid", true).append("time", new Document("$gte", Date.from(from)).append("$lte", Date.from(to))))
          .projection(new Document("scoreType", 1).append("total", 1).append("time", 1).append("apacheII.calDead.score", 1)).limit(500)) out.add(d);
    } catch (RuntimeException ex) { failed.add("score"); }
    return out;
  }

  public static Duration hours(long h) { return Duration.ofHours(h); }
  public static ObjectId oid(String hex) { return new ObjectId(hex); }
}
