package org.icu.sepsis.research.toolkit;

import static org.icu.sepsis.research.toolkit.Data.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Compares the exporter output against truth.json from {@link E2eSeed}, then runs the report. */
public final class E2eVerify {
  private E2eVerify() {}

  static boolean close(Object a, Object b, double tol) {
    if (a == null || b == null) return a == null && b == null;
    Double x = num(a), y = num(b);
    return x != null && y != null && Math.abs(x - y) <= tol;
  }

  public static int main(Path exportDir, Path truthPath, Path outDir) throws Exception {
    Map<String, Object> truth = parse(Files.readString(truthPath, StandardCharsets.UTF_8));
    StringBuilder raw = new StringBuilder();
    try (DirectoryStream<Path> ds = Files.newDirectoryStream(exportDir, "*.jsonl")) {
      for (Path p : ds) raw.append(Files.readString(p, StandardCharsets.UTF_8));
    }
    List<String> errors = new ArrayList<>();
    for (Object ident : list(truth.get("identifiers"))) if (raw.indexOf(String.valueOf(ident)) >= 0) errors.add("identifier leaked: " + ident);
    if (raw.indexOf("测试患者") >= 0) errors.add("patient name leaked");
    Data.Export ex = load(exportDir); // also enforces schema + forbidden keys
    Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
    for (Map<String, Object> r : ex.cohort) byKey.put(String.valueOf(r.get("stayKey")), r);
    Map<String, Object> stays = map(truth.get("stays"));
    if (!byKey.keySet().equals(stays.keySet())) {
      Set<String> miss = new HashSet<>(stays.keySet());
      miss.removeAll(byKey.keySet());
      errors.add(f("stay keys differ: export %d vs truth %d; missing %d", byKey.size(), stays.size(), miss.size()));
    }
    int checked = 0;
    for (Map.Entry<String, Object> e : stays.entrySet()) {
      String key = e.getKey();
      Map<String, Object> exp = map(e.getValue()), r = byKey.get(key);
      if (r == null) continue;
      checked++;
      Checker c = new Checker(key, errors);
      c.chk("ageYears", r.get("ageYears"), exp.get("ageYears"), 0);
      c.chk("sex", r.get("sex"), exp.get("sex"), 0);
      c.chk("t0Available", r.get("t0Available"), exp.get("t0Available"), 0);
      c.chk("death", get(r, "outcome.death"), exp.get("death"), 0);
      c.chk("deathQc", get(r, "outcome.deathQc"), exp.get("deathQc"), 0);
      if (!truthy(exp.get("t0Available"))) continue;
      c.chk("icuOutMinFromT0", get(r, "outcome.icuOutMinFromT0"), exp.get("icuOutMinFromT0"), 1);
      for (String fld : List.of("abxFirstMin", "vasoFirstMin", "bloodCultureMin")) c.chk(fld, get(r, "timing." + fld), exp.get(fld), 0);
      c.chk("lactateInitial", get(r, "timing.lactateInitial"), exp.get("lactateInitial"), 1e-9);
      c.chk("fluidMl3h", get(r, "timing.fluidMl3h"), exp.get("fluidMl3h"), 0.11);
      c.chk("fluidIncomplete3h", get(r, "timing.fluidIncomplete3h"), exp.get("fluidIncomplete3h"), 0);
      Map<String, Object> tr = map(r.get("traj"));
      for (int h = 0; h < 24; h++) {
        c.chk("map[" + h + "]", idx(tr.get("map"), h), idx(exp.get("map"), h), 1e-9);
        c.chk("hr[" + h + "]", idx(tr.get("hr"), h), idx(exp.get("hr"), h), 0.051);
        c.chk("lactate[" + h + "]", idx(tr.get("lactate"), h), idx(exp.get("lactate"), h), 1e-9);
        c.chk("vaso[" + h + "]", idx(tr.get("vaso"), h), idx(exp.get("vaso"), h), 0);
      }
      String[][] worst = {{"pfMin", "1e-9"}, {"gcsMin", "1e-9"}, {"pltMin", "1e-9"}, {"tbilMaxUmol", "0.2"}, {"creaMaxUmol", "1e-9"}};
      for (String[] wf : worst) c.chk(wf[0], get(r, "worst24." + wf[0]), exp.get(wf[0]), Double.parseDouble(wf[1]));
      List<Object> agents = new ArrayList<>(list(get(r, "worst24.vasoAgents", List.of())));
      agents.sort(Comparator.comparing(String::valueOf));
      c.chk("vasoAgents", agents, exp.get("vasoAgents"), 0);
      c.chk("apache2First24", get(r, "scores.apache2First24"), exp.get("apache2First24"), 0);
      c.chk("apache2CalDead", get(r, "scores.apache2CalDead"), exp.get("apache2CalDead"), 1e-9);
    }
    int wantAlerts = num(truth.get("alerts")).intValue();
    if (ex.alerts.size() != wantAlerts) errors.add(f("alerts: got %d want %d (simulated must be skipped)", ex.alerts.size(), wantAlerts));
    if (ex.alerts.stream().anyMatch(a -> a.get("minFromT0") == null)) errors.add("alert without minFromT0");
    Map<String, Object> res = Report.run(exportDir, outDir);
    Map<String, Object> statuses = new LinkedHashMap<>();
    map(res.get("sections")).forEach((k, v) -> statuses.put(k, map(v).get("status")));
    if (statuses.containsValue("error")) errors.add("report error: " + statuses);
    long shock = ex.cohort.stream().filter(r -> "septic_shock".equals(str(r, "icu05.status"))).count();
    Map<String, Object> mf = new LinkedHashMap<>();
    for (String k : List.of("stays", "staysWithoutT0", "staysFailed", "sourceFailures")) mf.put(k, ex.manifest.get(k));
    System.out.println(JSON.writeValueAsString(m("staysChecked", checked, "alerts", ex.alerts.size(), "septicShock", shock, "manifest", mf,
        "report", statuses, "errors", errors.size())));
    for (String err : errors.subList(0, Math.min(30, errors.size()))) System.out.println("ERROR " + err);
    return errors.isEmpty() ? 0 : 1;
  }

  private static Object idx(Object l, int i) { List<Object> v = list(l); return i < v.size() ? v.get(i) : null; }

  private static final class Checker {
    final String key;
    final List<String> errors;
    Checker(String key, List<String> errors) { this.key = key; this.errors = errors; }

    void chk(String name, Object got, Object want, double tol) {
      boolean bad = want == null || want instanceof Number || want instanceof Boolean ? !close(got, want, tol) : !Objects.equals(got, want);
      if (bad) errors.add(key + " " + name + ": got " + got + " want " + want);
    }
  }
}
