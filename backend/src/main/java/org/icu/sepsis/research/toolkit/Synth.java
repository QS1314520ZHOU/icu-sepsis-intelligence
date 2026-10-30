package org.icu.sepsis.research.toolkit;

import static org.icu.sepsis.research.toolkit.Data.m;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/**
 * Synthetic export generator (schema icu-research-cohort/1 + icu-research-alert/1) with planted effects, used only to
 * test the analysis code. Planted effects (scenario "signal"): antibiotic &gt;3h after T0 raises death odds (OR ≈ e ≈ 2.7
 * vs ≤1h); three trajectory phenotypes (stable / vasoplegic / tachycardic) with different mortality; MAP falls during the
 * 4 hours before vasopressor start; SOFA components and APACHE II track death. Scenario "null" removes the antibiotic
 * and MAP-drop effects. Nothing here is patient data.
 */
public final class Synth {
  static final String[] REASONS = {"已处理：已开始抗菌药", "已知晓，继续观察", "误报：监测干扰", "误报：数据错误", "已处理：已补液"};

  private Synth() {}

  /** Generated cohort + alerts. */
  public static final class Synthetic {
    public final List<Map<String, Object>> cohort, alerts;
    Synthetic(List<Map<String, Object>> c, List<Map<String, Object>> a) { cohort = c; alerts = a; }
  }

  static double sig(double x) { return 1 / (1 + Math.exp(-x)); }
  static double clip(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
  static double r1(double v) { return Data.round(v, 1); }
  static Map<String, Object> item(Object value, String reason, Integer minute) { return m("value", value, "reason", reason, "min", minute); }

  static String sha20(String s) {
    try {
      byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder b = new StringBuilder();
      for (byte x : h) b.append(String.format("%02x", x));
      return b.substring(0, 20);
    } catch (Exception e) { throw new IllegalStateException(e); }
  }

  public static Synthetic make(int n, long seed, String scenario) {
    Rng rng = new Rng(seed), rngDt = new Rng(seed + 7919); // separate stream: discharge type does not shift other draws
    boolean signal = "signal".equals(scenario);
    List<Map<String, Object>> cohort = new ArrayList<>(), alerts = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      String key = sha20("synthetic|" + seed + "|" + i);
      String month = String.format("2025-%02d", 1 + i * 12 / n);
      double age = clip(rng.normal(64, 14), 18, 95);
      boolean t0 = rng.random() < 0.93;
      double u = rng.random();
      String status = u < 0.7 ? "septic_shock" : u < 0.8 ? "suspected_review" : u < 0.95 ? "not_met" : "unknown";
      Map<String, Object> row = m("schema", Data.COHORT_SCHEMA, "stayKey", key, "synthetic", true, "admitMonth", month,
          "sex", rng.random() < 0.6 ? "男" : "女", "ageYears", (int) Math.round(age), "weightKg", r1(rng.normal(65, 12)),
          "admissionType", rng.random() < 0.6 ? "急诊" : "病房", "hospitalToIcuHours", r1(rng.exponential(20)), "t0Available", t0,
          "icuToT0Min", t0 ? rng.integers(0, 600) : null);
      if (!t0) {
        row.put("outcome", m("dischargedType", "转出", "death", false, "deathQc", false, "icuLosHours", 48.0, "icuOutMinFromT0", null));
        row.put("icu05", m("status", "no_t0", "poolEligible", null, "windows", new LinkedHashMap<>()));
        row.put("quality", m("failedSources", new ArrayList<>()));
        cohort.add(row);
        continue;
      }
      int pheno = rng.choice(new double[] {0.45, 0.30, 0.25}); // 0 stable, 1 vasoplegic, 2 tachycardic
      double baseMap = new double[] {78, 60, 68}[pheno] + rng.normal(0, 4);
      double baseHr = new double[] {88, 105, 128}[pheno] + rng.normal(0, 6);
      double lac0 = clip(rng.lognormal(Math.log(new double[] {2.0, 5.5, 3.2}[pheno]), 0.3), 0.5, 18);
      double apache = clip(rng.normal(new double[] {14, 24, 19}[pheno], 5), 2, 50);
      Double vstart; // vasopressor onset (hours from T0); vasoplegic start early
      if (pheno == 1) vstart = rng.uniform(0, 3);
      else if (rng.random() < (pheno == 2 ? 0.45 : 0.3)) vstart = rng.uniform(3, 22);
      else vstart = null;
      Double abx = rng.random() < 0.95 ? rng.lognormal(Math.log(80), 0.8) : null;
      List<Object> maps = new ArrayList<>(), hrs = new ArrayList<>(), lacs = new ArrayList<>(), vaso = new ArrayList<>();
      for (int h = 0; h < 24; h++) {
        double mm = baseMap + rng.normal(0, 3);
        if (vstart != null && signal && h < vstart) mm -= Math.max(0.0, 4 - (vstart - h)) * 4.5; // drift down in the 4h before onset
        if (vstart != null && h >= vstart) mm = Math.max(mm, 66 + rng.normal(0, 3));
        if (vstart != null && !signal && h < vstart) mm -= rng.normal(0, 2);
        maps.add(rng.random() > 0.04 ? (double) Math.round(mm) : null);
        hrs.add(rng.random() > 0.04 ? r1(baseHr + rng.normal(0, 5)) : null);
        double lv = lac0 * Math.pow(pheno != 1 ? 0.93 : 0.98, h);
        lacs.add(h == 2 || h == 6 || h == 12 || h == 18 ? r1(lv) : null);
        vaso.add(vstart != null && h + 1 > vstart ? 1 : 0);
      }
      Integer vmin = vstart == null ? null : (int) (vstart * 60);
      double a0 = abx == null ? 0 : abx;
      double lp = -2.0 + 0.03 * (age - 64) + 0.07 * (apache - 18) + 0.12 * (lac0 - 3) + new double[] {0, 0.6, 0.3}[pheno]
          + (signal ? (a0 > 60 && a0 <= 180 ? 0.5 : a0 > 180 ? 1.0 : 0) : 0);
      boolean death = rng.random() < sig(lp);
      String dtype = death ? "死亡" : rngDt.random() < 0.06 + 0.25 * sig(lp) ? "非医嘱离院" : "转出";
      double los = clip(rng.lognormal(Math.log(96), 0.6), 4, 900);
      int outMin = (int) (los * 60);
      if (death && rng.random() < 0.08) outMin = (int) rng.uniform(30, 170);
      double fluid3 = Math.max(0, rng.normal(1500, 600));
      boolean incomplete = rng.random() < 0.1;
      double sev = lp;
      List<String> agents = new ArrayList<>();
      double pfMin = clip(rng.normal(300 - 25 * sev, 70), 50, 500);
      Double gcsMin = rng.random() > 0.1 ? clip(Math.rint(rng.normal(13 - 0.8 * Math.max(sev, 0), 2)), 3, 15) : null;
      double mapMin = maps.stream().filter(Objects::nonNull).mapToDouble(v -> (Double) v).min().orElse(Double.NaN);
      double pltMin = clip(rng.normal(170 - 25 * sev, 60), 5, 500);
      double tbil = clip(rng.lognormal(Math.log(18 + 6 * Math.max(sev, 0)), 0.5), 3, 400);
      double crea = clip(rng.lognormal(Math.log(95 + 25 * Math.max(sev, 0)), 0.4), 30, 900);
      if (vstart != null) { agents.add("ne"); if (pheno == 1 && rng.random() < 0.4) agents.add("vasopressin"); }
      Map<String, Object> w = m("pfMin", pfMin, "gcsMin", gcsMin, "mapMin", mapMin, "pltMin", pltMin, "tbilMaxUmol", tbil, "creaMaxUmol", crea,
          "vasoAgents", agents);
      Map<String, Object> win = new LinkedHashMap<>();
      for (int hours : new int[] {1, 3, 6}) {
        Map<String, Object> a1 = item(true, null, -30), b1 = item(rng.random() < 0.85, null, 20);
        Map<String, Object> b2 = item(abx == null ? null : abx <= hours * 60, abx != null ? null : "DRUG_MISSING", abx == null ? null : (int) (double) abx);
        boolean b3v = Boolean.TRUE.equals(b1.get("value")) && Boolean.TRUE.equals(b2.get("value")) && rng.random() < 0.9;
        Map<String, Object> b3 = item(b3v, null, null);
        Map<String, Object> c3 = item(incomplete ? null : hours >= 3 ? fluid3 >= 1500 : true, incomplete ? "FLUID_AMOUNT_INCOMPLETE" : null, null);
        Map<String, Object> items = m("A1", a1, "B1", b1, "B2", b2, "B3", b3, "C3", c3);
        boolean allTrue = true, anyFalse = false;
        for (Object it : items.values()) {
          Object v = Data.map(it).get("value");
          allTrue &= Boolean.TRUE.equals(v);
          anyFalse |= Boolean.FALSE.equals(v);
        }
        String state = allTrue ? "met" : anyFalse ? "not_met" : "unknown";
        win.put(hours + "h", m("state", state, "finish", state.equals("met") ? Boolean.TRUE : state.equals("not_met") ? Boolean.FALSE : null, "items", items));
      }
      double map6 = maps.subList(0, 6).stream().filter(Objects::nonNull).mapToDouble(v -> (Double) v).min().orElse(Double.NaN);
      row.put("outcome", m("dischargedType", dtype, "death", death, "deathQc", death || dtype.equals("非医嘱离院"), "icuLosHours", r1(los),
          "icuOutMinFromT0", outMin));
      row.put("icu05", m("status", status, "poolEligible", status.equals("septic_shock"), "isSepticShock", status.equals("septic_shock"),
          "hasInfection", status.equals("septic_shock"), "hasOrgan", true, "gate", new LinkedHashMap<>(), "windows", win));
      row.put("timing", m("abxFirstMin", abx == null ? null : (int) (double) abx, "vasoFirstMin", vmin, "bloodCultureMin", (int) rng.normal(20, 30),
          "fluidMl1h", Math.round(fluid3 / 3), "fluidMl3h", incomplete ? null : Math.round(fluid3), "fluidMl6h", Math.round(fluid3 * 1.5),
          "fluidMl24h", Math.round(fluid3 * 2.5), "fluidIncomplete1h", false, "fluidIncomplete3h", incomplete, "fluidIncomplete6h", incomplete,
          "fluidIncomplete24h", incomplete, "lactateInitial", r1(lac0), "lactateInitialMin", -20, "lactateMax6h", r1(lac0),
          "lactateLast6h", lacs.get(6), "lactateLast6hMin", 360, "mapMin6h", Double.isNaN(map6) ? null : map6));
      row.put("traj", m("map", maps, "hr", hrs, "lactate", lacs, "vaso", vaso));
      row.put("worst24", w);
      row.put("scores", m("sofaMax24", null, "apache2First24", (int) Math.round(apache), "apache2CalDead", Data.round(sig(-2.6 + 0.09 * apache), 3)));
      row.put("quality", m("failedSources", rng.random() < 0.02 ? new ArrayList<>(List.of("DataCenter.VI_ICU_EXAM_ITEM")) : new ArrayList<>()));
      cohort.add(row);
      if ((status.equals("septic_shock") || status.equals("suspected_review")) && rng.random() < 0.8) {
        int amin = (int) rng.uniform(-60, 90), hour = rng.integers(0, 24);
        List<Map<String, Object>> revs = new ArrayList<>();
        if (rng.random() < 0.85) {
          boolean night = !(hour >= 8 && hour < 20);
          String reason = REASONS[rng.choice(new double[] {0.4, 0.3, 0.15, 0.05, 0.1})];
          revs.add(m("action", "close", "reason", reason, "min", (int) rng.lognormal(Math.log(night ? 25 : 12), 0.6), "actorKey", "a" + rng.integers(0, 12)));
        }
        Object rm = revs.isEmpty() ? null : revs.get(0).get("min");
        alerts.add(m("schema", Data.ALERT_SCHEMA, "stayKey", key, "minFromT0", amin, "hourOfDay", hour, "weekday", rng.integers(1, 8),
            "severity", "high", "status", revs.isEmpty() ? "open" : "closed", "ackMin", rm, "closeMin", rm, "reviews", revs));
      }
    }
    return new Synthetic(cohort, alerts);
  }

  public static Path write(Path dir, int n, long seed, String scenario) throws IOException {
    Files.createDirectories(dir);
    Synthetic s = make(n, seed, scenario);
    writeJsonl(dir.resolve("cohort.jsonl"), s.cohort);
    writeJsonl(dir.resolve("alerts.jsonl"), s.alerts);
    Files.writeString(dir.resolve("manifest.json"), Data.PRETTY.writeValueAsString(m("schema", Data.COHORT_SCHEMA, "synthetic", true, "scenario", scenario,
        "stays", s.cohort.size(), "alerts", s.alerts.size(), "seed", seed)), StandardCharsets.UTF_8);
    return dir;
  }

  static void writeJsonl(Path p, List<Map<String, Object>> rows) throws IOException {
    try (BufferedWriter w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
      for (Map<String, Object> r : rows) { w.write(Data.JSON.writeValueAsString(r)); w.write('\n'); }
    }
  }
}
