package org.icu.sepsis.research.toolkit;

import static org.icu.sepsis.research.toolkit.Data.*;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.bson.types.ObjectId;

/**
 * Seeds a LOCAL test MongoDB with synthetic SmartCare / DataCenter / business records built from {@link Synth} rows.
 * Dev/e2e only: refuses any host other than 127.0.0.1/localhost. Writes truth.json (expected export values keyed by
 * stayKey) so {@link E2eVerify} can check the exporter field by field. No real patient data is involved.
 */
public final class E2eSeed {
  static final Instant BASE = Instant.parse("2025-01-03T00:00:00Z");

  private E2eSeed() {}

  static String stayKey(String salt, String enc) { return Synth.sha20(salt + "|" + enc); }

  static Date at(Instant t0, double minutes) { return Date.from(t0.plusMillis(Math.round(minutes * 60_000))); }

  static double r(double v, int places) { return Data.round(v, places); }

  public static int main(Map<String, String> a) throws Exception {
    String uri = a.getOrDefault("uri", "mongodb://127.0.0.1:27117");
    String scDb = a.getOrDefault("smartcare-db", "SmartCareE2E"), dcDb = a.getOrDefault("datacenter-db", "DataCenterE2E"),
        bizDb = a.getOrDefault("business-db", "icu_sepsis_e2e");
    int n = Integer.parseInt(a.getOrDefault("n", "500"));
    long seed = Long.parseLong(a.getOrDefault("seed", "5"));
    String salt = a.get("salt"), truthPath = a.get("truth");
    if (salt == null || truthPath == null) { System.err.println("需要 --salt 与 --truth"); return 2; }
    String host = new ConnectionString(uri).getHosts().toString();
    if (!new ConnectionString(uri).getHosts().stream().allMatch(h -> h.startsWith("127.0.0.1") || h.startsWith("localhost"))) {
      System.err.println("只允许写本机测试库: " + host);
      return 2;
    }
    MongoClientSettings settings = MongoClientSettings.builder().applyConnectionString(new ConnectionString(uri))
        .applyToClusterSettings(b -> b.serverSelectionTimeout(3000, TimeUnit.MILLISECONDS)).build();
    try (MongoClient cli = MongoClients.create(settings)) {
      for (String db : List.of(scDb, dcDb, bizDb)) cli.getDatabase(db).drop();
      MongoDatabase sc = cli.getDatabase(scDb), dc = cli.getDatabase(dcDb), biz = cli.getDatabase(bizDb);
      sc.getCollection("configDrug").insertMany(List.of(new Document("name", "去甲肾上腺素注射液").append("classification", "血管活性药"),
          new Document("name", "注射用美罗培南").append("classification", "抗菌药物")));
      Synth.Synthetic s = Synth.make(n, seed, "signal");
      Map<String, List<Map<String, Object>>> alertsByKey = new HashMap<>();
      for (Map<String, Object> al : s.alerts) alertsByKey.computeIfAbsent(String.valueOf(al.get("stayKey")), k -> new ArrayList<>()).add(al);
      List<Document> P = new ArrayList<>(), ZY = new ArrayList<>(), BG = new ArrayList<>(), BS = new ArrayList<>(), SC = new ArrayList<>(),
          DE = new ArrayList<>(), EX = new ArrayList<>(), AL = new ArrayList<>(), HI = new ArrayList<>();
      Map<String, Object> truth = new LinkedHashMap<>();
      int realAlerts = 0;
      for (int i = 0; i < s.cohort.size(); i++) {
        Map<String, Object> row = s.cohort.get(i);
        ObjectId oid = new ObjectId();
        String pid = oid.toHexString(), mrn = String.format("E2E%05d", i), his = String.format("HIS%05d", i);
        Instant icu = BASE.plus(Duration.ofHours((long) i * 8760 / n));
        String enc = mrn + "|" + icu.toEpochMilli();
        String key = stayKey(salt, enc);
        boolean hasT0 = truthy(row.get("t0Available"));
        Instant t0 = hasT0 ? icu.plus(Duration.ofMinutes(num(row, "icuToT0Min").longValue())) : null;
        Map<String, Object> o = map(row.get("outcome"));
        Double outFromT0 = num(o.get("icuOutMinFromT0"));
        Date outT = t0 != null && outFromT0 != null ? at(t0, outFromT0) : Date.from(icu.plusMillis(Math.round(num(o.get("icuLosHours")) * 3_600_000)));
        String st = str(row, "icu05.status");
        P.add(new Document("_id", oid).append("name", "测试患者" + i).append("mrn", mrn).append("hisPid", his).append("gender", row.get("sex"))
            .append("age", row.get("ageYears")).append("weight", row.get("weightKg")).append("status", "out").append("icuAdmissionTime", Date.from(icu))
            .append("admissionTime", Date.from(icu.minusMillis(Math.round(num(row, "hospitalToIcuHours") * 3_600_000))))
            .append("admissionType", row.get("admissionType")).append("dischargedType", o.get("dischargedType")).append("icuDischargeTime", outT)
            .append("clinicalDiagnosis", "septic_shock".equals(st) || "suspected_review".equals(st) ? "肺炎|脓毒症" : "术后"));
        Map<String, Object> exp = m("ageYears", num(row, "ageYears"), "sex", row.get("sex"), "t0Available", hasT0, "death", o.get("death"),
            "deathQc", o.get("deathQc"));
        truth.put(key, exp);
        if (t0 == null) continue;
        exp.put("icuOutMinFromT0", o.get("icuOutMinFromT0"));
        Map<String, Object> tr = map(row.get("traj")), tm = map(row.get("timing")), w = map(row.get("worst24"));
        ZY.add(new Document("pid", his).append("orderName", "一级护理").append("orderTime", Date.from(t0)));
        int bc = Math.max(1, num(tm.get("bloodCultureMin")).intValue());
        ZY.add(new Document("pid", his).append("orderName", "血培养(需氧+厌氧)").append("orderTime", at(t0, bc - 1)).append("reviewTime", at(t0, bc)));
        exp.put("bloodCultureMin", bc);
        List<Object> mapL = list(tr.get("map")), hrL = list(tr.get("hr")), lacL = list(tr.get("lactate"));
        for (int h = 0; h < 24; h++) {
          if (mapL.get(h) != null)
            BS.add(new Document("pid", pid).append("code", "param_ibp_m").append("valid", true).append("time", at(t0, h * 60 + 20))
                .append("strVal", String.valueOf(num(mapL.get(h)).intValue())));
          if (hrL.get(h) != null)
            BS.add(new Document("pid", pid).append("code", "param_HR").append("valid", true).append("time", at(t0, h * 60 + 25))
                .append("strVal", String.valueOf(num(hrL.get(h)))));
        }
        exp.put("map", mapL);
        exp.put("hr", hrL);
        List<Document> lac = new ArrayList<>();
        lac.add(new Document("code", "param_bg_Lac").append("valid", "valid").append("time", at(t0, -20)).append("fVal", tm.get("lactateInitial")));
        for (int h = 0; h < lacL.size(); h++)
          if (lacL.get(h) != null)
            lac.add(new Document("code", "param_bg_Lac").append("valid", "valid").append("time", at(t0, h * 60 + 30)).append("fVal", lacL.get(h)));
        double pf = r(num(w.get("pfMin")), 1);
        lac.add(new Document("code", "param_bg_P/Fratio").append("valid", "valid").append("time", at(t0, 60)).append("fVal", pf));
        if (i % 5 == 1) { // lactate only in DataCenter (fallback path); P/F stays in bGATemp
          for (Document b : lac.subList(0, lac.size() - 1))
            EX.add(new Document("hisPid", his).append("itemCode", "LAC").append("result", String.valueOf(b.get("fVal"))).append("unit", "mmol/L")
                .append("authTime", b.get("time")));
          EX.add(new Document("hisPid", his).append("itemCode", "LDH").append("result", "300").append("unit", "U/L").append("authTime", at(t0, 30)));
          lac = new ArrayList<>(lac.subList(lac.size() - 1, lac.size()));
        }
        BG.add(new Document("mrn", mrn).append("bedsides", lac));
        exp.put("lactateInitial", tm.get("lactateInitial"));
        exp.put("lactate", lacL);
        exp.put("pfMin", pf);
        if (w.get("gcsMin") != null)
          SC.add(new Document("pid", pid).append("scoreType", "gcsScore").append("valid", true).append("time", at(t0, 60)).append("total", w.get("gcsMin")));
        exp.put("gcsMin", w.get("gcsMin"));
        Map<String, Object> scores = map(row.get("scores"));
        SC.add(new Document("pid", pid).append("scoreType", "apacheII").append("valid", true).append("time", Date.from(icu.plusSeconds(3600)))
            .append("total", scores.get("apache2First24")).append("apacheII", new Document("calDead", new Document("score", scores.get("apache2CalDead")))));
        exp.put("apache2First24", num(scores.get("apache2First24")));
        exp.put("apache2CalDead", scores.get("apache2CalDead"));
        Double abx = num(tm.get("abxFirstMin")), vfirst = num(tm.get("vasoFirstMin"));
        if (abx != null) DE.add(drug(pid, t0, abx, List.of(drugItem("注射用美罗培南", 100)), act(abx, "start")));
        exp.put("abxFirstMin", abx != null && abx <= 24 * 60 ? tm.get("abxFirstMin") : null);
        List<Object> agents = list(w.get("vasoAgents"));
        if (vfirst != null) {
          List<Document> items = new ArrayList<>(List.of(drugItem("去甲肾上腺素注射液", 50)));
          if (agents.contains("vasopressin")) items.add(drugItem("垂体后叶素注射液", 50));
          List<Object[]> acts = new ArrayList<>();
          acts.add(new Object[] {vfirst, "start"});
          if (i % 3 == 0) { // pause and resume inside the same hour: still running that hour
            acts.add(new Object[] {vfirst + 5, "pause"});
            acts.add(new Object[] {vfirst + 15, "recovery"});
          }
          acts.add(new Object[] {24.0 * 60 + 30, "调速"});
          DE.add(drug(pid, t0, vfirst, items, acts));
        }
        List<Object> sortedAgents = new ArrayList<>(agents);
        sortedAgents.sort(Comparator.comparing(String::valueOf));
        exp.put("vasoFirstMin", tm.get("vasoFirstMin"));
        exp.put("vaso", tr.get("vaso"));
        exp.put("vasoAgents", sortedAgents);
        long f1 = num(tm.get("fluidMl1h")).longValue();
        DE.add(drug(pid, t0, 10, List.of(drugItem("0.9%氯化钠注射液", f1)), act(10.0, "start")));
        long rest = Math.round(num(tm.get("fluidMl6h")) * 2 / 3) - f1; // fluid3 total = fluid6h * 2/3 in synth
        if (rest > 0) DE.add(drug(pid, t0, 100, List.of(drugItem("乳酸林格注射液", rest)), act(100.0, "start")));
        boolean inc3 = truthy(tm.get("fluidIncomplete3h"));
        if (inc3) DE.add(drug(pid, t0, 120, List.of(drugItem("羟乙基淀粉注射液", null)), act(120.0, "start")));
        exp.put("fluidMl3h", inc3 && f1 + Math.max(rest, 0) == 0 ? null : (double) (f1 + Math.max(rest, 0)));
        exp.put("fluidIncomplete3h", inc3);
        double plt = num(w.get("pltMin")), tbil = num(w.get("tbilMaxUmol")), crea = num(w.get("creaMaxUmol"));
        EX.add(new Document("hisPid", his).append("itemCode", "PLT").append("result", String.valueOf(r(plt, 1))).append("unit", "10^9/L").append("authTime", at(t0, 120)));
        Document tb = new Document("hisPid", his).append("itemCode", "TBIL").append("result", String.valueOf(r(tbil / 17.104, 2))).append("unit", "mg/dL")
            .append("authTime", at(t0, 120));
        if (i % 4 == 0) tb.append("result", String.valueOf(r(tbil, 1))).append("unit", "micromol/L"); // same value reported in micromol/L
        EX.add(tb);
        EX.add(new Document("hisPid", his).append("itemCode", "sCr").append("result", String.valueOf(r(crea, 1))).append("unit", "μmol/L").append("authTime", at(t0, 120)));
        exp.put("pltMin", r(plt, 1));
        exp.put("tbilMaxUmol", r(tbil, 1));
        exp.put("creaMaxUmol", r(crea, 1));
        for (Map<String, Object> al : alertsByKey.getOrDefault(String.valueOf(row.get("stayKey")), List.of())) {
          ObjectId aid = new ObjectId();
          Date created = at(t0, num(al.get("minFromT0")));
          Document doc = new Document("_id", aid).append("encounterId", enc).append("patientId", pid).append("createdAt", created)
              .append("status", al.get("status")).append("severity", al.get("severity")).append("simulated", false);
          for (Object rv0 : list(al.get("reviews"))) {
            Map<String, Object> rv = map(rv0);
            Date when = new Date(created.getTime() + num(rv.get("min")).longValue() * 60_000);
            doc.append("closedAt", when);
            HI.add(new Document("entityType", "alert").append("entityId", aid.toHexString()).append("action", rv.get("action")).append("reason", rv.get("reason"))
                .append("actor", "nurse-" + rv.get("actorKey")).append("createdAt", when));
          }
          AL.add(doc);
          realAlerts++;
        }
        if (i % 50 == 0)
          AL.add(new Document("_id", new ObjectId()).append("encounterId", enc).append("createdAt", at(t0, 5)).append("status", "open").append("severity", "high")
              .append("simulated", true));
      }
      sc.getCollection("patient").insertMany(P);
      Object[][] colls = {{dc, "VI_ICU_ZYYZ", ZY}, {sc, "bGATemp", BG}, {sc, "bedside", BS}, {sc, "score", SC}, {sc, "drugExe", DE},
          {dc, "VI_ICU_EXAM_ITEM", EX}, {biz, "sepsis_alert_event", AL}, {biz, "sepsis_state_history", HI}};
      for (Object[] c : colls) {
        @SuppressWarnings("unchecked") List<Document> docs = (List<Document>) c[2];
        if (!docs.isEmpty()) ((MongoDatabase) c[0]).getCollection((String) c[1]).insertMany(docs);
      }
      List<String> idents = new ArrayList<>();
      for (Document p : P.subList(0, Math.min(50, P.size()))) idents.add(p.getString("mrn"));
      for (Document p : P.subList(0, Math.min(50, P.size()))) idents.add(p.getString("hisPid"));
      for (Document p : P.subList(0, Math.min(50, P.size()))) idents.add(p.getObjectId("_id").toHexString());
      Files.writeString(Path.of(truthPath), JSON.writeValueAsString(m("stays", truth, "alerts", realAlerts, "identifiers", idents)), StandardCharsets.UTF_8);
      System.out.printf("seeded %d stays, %d bedside, %d drugExe, %d alerts%n", P.size(), BS.size(), DE.size(), AL.size());
    }
    return 0;
  }

  static List<Object[]> act(double minute, String action) {
    List<Object[]> l = new ArrayList<>();
    l.add(new Object[] {minute, action});
    return l;
  }

  static Document drugItem(String name, Object amount) { return new Document("name", name).append("liquidAmount", amount).append("liquidUnit", "ml"); }

  static Document drug(String pid, Instant t0, double minute, List<Document> items, List<Object[]> actions) {
    List<Document> acts = new ArrayList<>();
    for (Object[] a : actions) acts.add(new Document("time", at(t0, ((Number) a[0]).doubleValue())).append("action", a[1]));
    return new Document("pid", pid).append("startTime", at(t0, minute)).append("drugList", items).append("drugActionList", acts);
  }
}
