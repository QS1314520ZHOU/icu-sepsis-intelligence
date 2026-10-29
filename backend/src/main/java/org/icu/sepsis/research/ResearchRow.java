package org.icu.sepsis.research;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.icu.sepsis.integration.ResearchSourceReader.Value;
import org.icu.sepsis.sepsis.Icu05Rules;
import org.icu.sepsis.sepsis.Icu05Rules.*;

/**
 * One de-identified research row per ICU stay. No names, record numbers or source ids;
 * the stay key is a salted SHA-256 of the encounter id, and times are minutes relative to T0.
 */
public final class ResearchRow {
  private ResearchRow() {}
  public static final String SCHEMA = "icu-research-cohort/1";
  public static final int TRAJ_HOURS = 24;
  static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

  public static String stayKey(String salt, String encounterId) {
    try {
      byte[] h = MessageDigest.getInstance("SHA-256").digest((salt + "|" + encounterId).getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 10; i++) sb.append(String.format("%02x", h[i]));
      return sb.toString();
    } catch (Exception ex) { throw new IllegalStateException(ex); }
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> build(Document patient, String encounterId, Evidence e, Map<String, Object> icu05,
      Map<String, List<Value>> labs, List<Document> scores, String salt, Set<String> deathTypes) {
    return build(patient, encounterId, e, icu05, labs, scores, salt, deathTypes, deathTypes);
  }

  /**
   * @param deathTypes   research/clinical outcome (default 死亡 only)
   * @param qcDeathTypes quality-control outcome (default 死亡 + 非医嘱离院, per department rule: only QC counts 非医嘱离院)
   */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> build(Document patient, String encounterId, Evidence e, Map<String, Object> icu05,
      Map<String, List<Value>> labs, List<Document> scores, String salt, Set<String> deathTypes, Set<String> qcDeathTypes) {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("schema", SCHEMA);
    r.put("stayKey", stayKey(salt, encounterId));
    Instant icu = e.icuAdmission, t0 = e.t0;
    r.put("admitMonth", icu == null ? null : YearMonth.from(icu.atZone(ZONE)).toString());
    r.put("sex", patient.getString("gender"));
    r.put("ageYears", age(patient, icu));
    Double w = num(patient.get("weight"));
    r.put("weightKg", w != null && w >= 20 && w <= 300 ? w : null);
    r.put("admissionType", e.admissionType);
    r.put("hospitalToIcuHours", e.hospitalAdmission == null || icu == null ? null : round(Duration.between(e.hospitalAdmission, icu).toMinutes() / 60.0));
    r.put("t0Available", t0 != null);
    r.put("icuToT0Min", t0 == null || icu == null ? null : Duration.between(icu, t0).toMinutes());
    // ---- outcome ----
    Map<String, Object> o = new LinkedHashMap<>();
    String dt = patient.getString("dischargedType");
    Instant icuOut = inst(patient.get("icuDischargeTime"));
    o.put("dischargedType", dt);
    o.put("death", dt == null || dt.isBlank() ? null : deathTypes.contains(dt.trim()));
    o.put("deathQc", dt == null || dt.isBlank() ? null : qcDeathTypes.contains(dt.trim()));
    o.put("icuLosHours", icu == null || icuOut == null ? null : round(Duration.between(icu, icuOut).toMinutes() / 60.0));
    o.put("icuOutMinFromT0", t0 == null || icuOut == null ? null : Duration.between(t0, icuOut).toMinutes());
    r.put("outcome", o);
    // ---- ICU-05 ----
    Map<String, Object> i5 = new LinkedHashMap<>();
    i5.put("status", icu05.get("status"));
    Map<String, Object> gate = (Map<String, Object>) icu05.get("gate");
    Map<String, Object> pool = (Map<String, Object>) icu05.get("pool");
    i5.put("poolEligible", pool == null ? null : pool.get("eligible"));
    if (gate != null) {
      i5.put("isSepticShock", gate.get("isSepticShock")); i5.put("hasInfection", gate.get("hasInfection")); i5.put("hasOrgan", gate.get("hasOrganDysfunction"));
      Map<String, Object> g = new LinkedHashMap<>();
      for (String k : List.of("organ", "infection", "shock"))
        for (Map<String, Object> it : (List<Map<String, Object>>) gate.get(k)) g.put((String) it.get("code"), it.get("value"));
      i5.put("gate", g);
    }
    Map<String, Object> ws = new LinkedHashMap<>();
    for (Map<String, Object> w0 : (List<Map<String, Object>>) icu05.getOrDefault("windows", List.of())) {
      Map<String, Object> wm = new LinkedHashMap<>();
      wm.put("state", w0.get("state")); wm.put("finish", w0.get("finish"));
      Map<String, Object> items = new LinkedHashMap<>();
      for (Map<String, Object> it : (List<Map<String, Object>>) w0.get("items")) {
        Map<String, Object> im = new LinkedHashMap<>();
        im.put("value", it.get("value")); im.put("reason", it.get("reason"));
        Instant t = (Instant) it.get("time");
        im.put("min", t == null || t0 == null ? null : Duration.between(t0, t).toMinutes());
        items.put((String) it.get("code"), im);
      }
      wm.put("items", items);
      ws.put((String) w0.get("key"), wm);
    }
    i5.put("windows", ws);
    r.put("icu05", i5);
    if (t0 == null) { r.put("quality", quality(e)); return r; }
    // ---- treatment timing (minutes from T0) ----
    Map<String, Object> tm = new LinkedHashMap<>();
    Instant lo = t0.minus(Duration.ofHours(24)), hi = t0.plus(Duration.ofHours(24));
    tm.put("abxFirstMin", rel(t0, firstDrug(e, lo, hi, n -> Icu05Rules.isAntibiotic(n, e.antibioticDictionary))));
    tm.put("vasoFirstMin", rel(t0, firstDrug(e, lo, hi, n -> Icu05Rules.isVasopressor(n, e.vasoDictionary))));
    Instant bc = null;
    for (Order od : e.cultureOrders) {
      if (od.name == null || e.bloodCultureKeywords.stream().noneMatch(od.name::contains)) continue;
      Instant t = "orderTime".equals(e.cultureTimeField) ? od.orderTime : od.reviewTime;
      if (t != null && !t.isBefore(lo) && (bc == null || t.isBefore(bc))) bc = t;
    }
    tm.put("bloodCultureMin", rel(t0, bc));
    for (int h : new int[] {1, 3, 6, 24}) {
      double[] f = fluid(e, t0, t0.plus(Duration.ofHours(h)));
      tm.put("fluidMl" + h + "h", f[1] > 0 && f[0] == 0 ? null : round(f[0]));
      tm.put("fluidIncomplete" + h + "h", f[1] > 0);
    }
    List<Point> lac = within(e.lactate, t0.minus(Duration.ofHours(2)), t0.plus(Duration.ofHours(6)));
    Point first = lac.stream().filter(p -> !p.time.isAfter(t0.plus(Duration.ofHours(1)))).reduce((a, b) -> b).orElse(lac.isEmpty() ? null : lac.get(0));
    tm.put("lactateInitial", first == null ? null : first.value);
    tm.put("lactateInitialMin", first == null ? null : rel(t0, first.time));
    tm.put("lactateMax6h", lac.stream().map(p -> p.value).max(Double::compare).orElse(null));
    Point last = lac.isEmpty() ? null : lac.get(lac.size() - 1);
    tm.put("lactateLast6h", last == null || last == first ? null : last.value);
    tm.put("lactateLast6hMin", last == null || last == first ? null : rel(t0, last.time));
    tm.put("mapMin6h", within(e.map, t0, t0.plus(Duration.ofHours(6))).stream().map(p -> p.value).min(Double::compare).orElse(null));
    r.put("timing", tm);
    // ---- hourly trajectory 0..23h after T0 ----
    Map<String, Object> tr = new LinkedHashMap<>();
    List<Double> mapH = new ArrayList<>(), hrH = new ArrayList<>(), lacH = new ArrayList<>();
    List<Integer> vasoH = new ArrayList<>();
    for (int h = 0; h < TRAJ_HOURS; h++) {
      Instant a = t0.plus(Duration.ofHours(h)), b = a.plus(Duration.ofHours(1)).minusMillis(1);
      mapH.add(within(e.map, a, b).stream().map(p -> p.value).min(Double::compare).orElse(null));
      List<Point> hr = within(e.hr, a, b);
      hrH.add(hr.isEmpty() ? null : round(hr.stream().mapToDouble(p -> p.value).average().orElse(0)));
      List<Point> l = within(e.lactate, a, b);
      lacH.add(l.isEmpty() ? null : l.get(l.size() - 1).value);
      vasoH.add(vasoState(e, a, b.plusMillis(1)));
    }
    tr.put("map", mapH); tr.put("hr", hrH); tr.put("lactate", lacH); tr.put("vaso", vasoH);
    r.put("traj", tr);
    // ---- worst values for SOFA (T0-6h .. T0+24h) ----
    Instant sLo = t0.minus(Duration.ofHours(6)), sHi = t0.plus(Duration.ofHours(24));
    Map<String, Object> wv = new LinkedHashMap<>();
    wv.put("pfMin", within(e.pf, sLo, sHi).stream().map(p -> p.value).min(Double::compare).orElse(null));
    wv.put("gcsMin", within(e.gcs, sLo, sHi).stream().map(p -> p.value).min(Double::compare).orElse(null));
    wv.put("mapMin", within(e.map, sLo, sHi).stream().map(p -> p.value).min(Double::compare).orElse(null));
    wv.put("pltMin", labs.getOrDefault("PLT", List.of()).stream().filter(v -> in(v.time, sLo, sHi)).map(v -> v.value).min(Double::compare).orElse(null));
    wv.put("tbilMaxUmol", labs.getOrDefault("TBIL", List.of()).stream().filter(v -> in(v.time, sLo, sHi)).map(v -> umol(v, 17.104)).filter(Objects::nonNull).max(Double::compare).orElse(null));
    wv.put("creaMaxUmol", labs.getOrDefault("CREA", List.of()).stream().filter(v -> in(v.time, sLo, sHi)).map(v -> umol(v, 88.4)).filter(Objects::nonNull).max(Double::compare).orElse(null));
    Set<String> agents = new TreeSet<>();
    for (Drug d : e.drugs) if (in(d.start, sLo, sHi)) for (DrugItem it : d.items) if (Icu05Rules.isVasopressor(it.name, e.vasoDictionary)) agents.add(agent(it.name));
    wv.put("vasoAgents", new ArrayList<>(agents));
    r.put("worst24", wv);
    // ---- source scores ----
    Map<String, Object> sc = new LinkedHashMap<>();
    Double sofa = null, apache = null, calDead = null; Instant apacheTime = null;
    for (Document d : scores) {
      Instant t = inst(d.get("time")); Double total = num(d.get("total"));
      if (t == null || total == null) continue;
      if ("sofa".equals(d.getString("scoreType")) && in(t, sLo, sHi)) sofa = sofa == null ? total : Math.max(sofa, total);
      if ("apacheII".equals(d.getString("scoreType")) && icu != null && in(t, icu, icu.plus(Duration.ofHours(24))) && (apacheTime == null || t.isBefore(apacheTime))) {
        apacheTime = t; apache = total;
        Object a = d.get("apacheII");
        Double cd = null;
        if (a instanceof Document && ((Document) a).get("calDead") instanceof Document) cd = num(((Document) ((Document) a).get("calDead")).get("score"));
        calDead = cd != null && cd >= 0 && cd <= 1 ? cd : null;
      }
    }
    sc.put("sofaMax24", sofa); sc.put("apache2First24", apache); sc.put("apache2CalDead", calDead);
    r.put("scores", sc);
    r.put("quality", quality(e));
    return r;
  }

  static String agent(String name) {
    String n = name == null ? "" : name;
    if (n.contains("去甲肾上腺素") || n.toLowerCase(Locale.ROOT).contains("norepinephrine")) return "ne";
    if (n.contains("多巴酚丁胺") || n.toLowerCase(Locale.ROOT).contains("dobutamine")) return "dobutamine";
    if (n.contains("多巴胺") || n.toLowerCase(Locale.ROOT).contains("dopamine")) return "dopamine";
    if (n.contains("异丙肾上腺素") || n.contains("去氧肾上腺素") || n.contains("苯肾上腺素")) return "other";
    if (n.contains("肾上腺素") || n.toLowerCase(Locale.ROOT).contains("epinephrine")) return "epi";
    if (n.contains("加压素") || n.contains("垂体后叶")) return "vasopressin";
    return "other";
  }

  /** 1 = running, 0 = not running, null = cannot tell (started earlier without action records). */
  static Integer vasoState(Evidence e, Instant a, Instant b) {
    boolean unknown = false;
    for (Drug d : e.drugs) {
      if (d.start == null || !d.start.isBefore(b) || d.start.isBefore(e.t0.minus(Duration.ofHours(24)))) continue;
      if (d.items.stream().noneMatch(it -> Icu05Rules.isVasopressor(it.name, e.vasoDictionary))) continue;
      Boolean on = Icu05Rules.vasopressorRunningDuring(d, a, b);
      if (Boolean.TRUE.equals(on)) return 1;
      if (on == null) unknown = true;
    }
    return unknown ? null : 0;
  }

  static double[] fluid(Evidence e, Instant from, Instant to) {
    double sum = 0, incomplete = 0;
    for (Drug d : e.drugs) {
      if (!in(d.start, from, to)) continue;
      for (DrugItem it : d.items) {
        if (Icu05Rules.fluidType(it.name) == null) continue;
        if (it.liquidMl == null || !it.liquidUnitKnown) incomplete++; else sum += it.liquidMl;
      }
    }
    return new double[] {sum, incomplete};
  }

  static Instant firstDrug(Evidence e, Instant lo, Instant hi, java.util.function.Predicate<String> m) {
    Instant best = null;
    for (Drug d : e.drugs) if (in(d.start, lo, hi) && d.items.stream().anyMatch(it -> m.test(it.name)) && (best == null || d.start.isBefore(best))) best = d.start;
    return best;
  }

  static Double umol(Value v, double factor) {
    String u = v.unit == null ? "" : v.unit.toLowerCase(Locale.ROOT).replace("μ", "u").replace("µ", "u");
    if (u.contains("mg/dl")) return round(v.value * factor);
    // 院内 TBIL 实际 unit 可能是 micromol/L 或 umol/L；两者都是 μmol/L 量级
    if (u.contains("umol") || u.contains("micromol") || u.contains("微摩尔") || u.contains("μmol")) return v.value;
    return null; // unknown unit is not guessed
  }

  static Object age(Document p, Instant at) {
    Double a = num(p.get("age"));
    if (a != null && a >= 0 && a < 120) return a;
    Instant b = inst(p.get("birthday"));
    if (b != null && at != null) return (double) Period.between(b.atZone(ZONE).toLocalDate(), at.atZone(ZONE).toLocalDate()).getYears();
    return null;
  }
  static Map<String, Object> quality(Evidence e) { return Map.of("failedSources", new ArrayList<>(new TreeSet<>(e.failed))); }
  static List<Point> within(List<Point> pts, Instant a, Instant b) {
    List<Point> out = new ArrayList<>();
    for (Point p : pts) if (in(p.time, a, b)) out.add(p);
    out.sort(Comparator.comparing(p -> p.time));
    return out;
  }
  static boolean in(Instant t, Instant a, Instant b) { return t != null && !t.isBefore(a) && !t.isAfter(b); }
  static Long rel(Instant t0, Instant t) { return t == null ? null : Duration.between(t0, t).toMinutes(); }
  static Double round(double v) { return Math.round(v * 10) / 10.0; }
  static Instant inst(Object o) { return o instanceof Date ? ((Date) o).toInstant() : o instanceof Instant ? (Instant) o : null; }
  static Double num(Object o) {
    if (o instanceof Number) return ((Number) o).doubleValue();
    if (o == null) return null;
    try { double v = Double.parseDouble(String.valueOf(o).trim()); return Double.isFinite(v) ? v : null; } catch (NumberFormatException ex) { return null; }
  }
}
