package org.icu.sepsis.research.toolkit;

import java.util.*;

/**
 * Classic SOFA (Vincent 1996) and SOFA-2 (JAMA 2025;334(23):2090-2103) from exported worst values.
 * Missing components stay null (never 0). Export limits are flagged per row: respiratory support not exported
 * (resp capped at 2, flag resp_support_unknown); vasopressor dose not exported (minimal band, flag vaso_dose_unknown);
 * urine output / RRT not exported (kidney uses creatinine only).
 */
public final class Sofa {
  public static final List<String> COMPONENTS = List.of("resp", "coag", "liver", "cardio", "cns", "renal");

  private Sofa() {}

  /** Component scores plus flags. */
  public static final class Score {
    public final Map<String, Integer> components;
    public final Set<String> flags;
    Score(Map<String, Integer> c, Set<String> f) { components = c; flags = f; }
  }

  private static Map<String, Integer> comps(Integer resp, Integer coag, Integer liver, Integer cardio, Integer cns, Integer renal) {
    Map<String, Integer> m = new LinkedHashMap<>();
    m.put("resp", resp); m.put("coag", coag); m.put("liver", liver); m.put("cardio", cardio); m.put("cns", cns); m.put("renal", renal);
    return m;
  }

  private static Set<String> agents(Map<String, Object> w) {
    Set<String> out = new LinkedHashSet<>();
    for (Object a : Data.list(w.get("vasoAgents"))) if (a != null) out.add(String.valueOf(a));
    return out;
  }

  public static Score classic(Map<String, Object> w) {
    Set<String> flags = new TreeSet<>();
    Double pf = Data.num(w.get("pfMin"));
    Integer resp = null;
    if (pf != null) {
      resp = pf < 100 ? 4 : pf < 200 ? 3 : pf < 300 ? 2 : pf < 400 ? 1 : 0;
      if (resp > 2) { resp = 2; flags.add("resp_support_unknown"); }
    }
    Double plt = Data.num(w.get("pltMin"));
    Integer coag = plt == null ? null : plt < 20 ? 4 : plt < 50 ? 3 : plt < 100 ? 2 : plt < 150 ? 1 : 0;
    Double tb = Data.num(w.get("tbilMaxUmol"));
    Integer liver = tb == null ? null : tb >= 204 ? 4 : tb >= 102 ? 3 : tb >= 33 ? 2 : tb >= 20 ? 1 : 0;
    Set<String> ag = agents(w);
    Double mp = Data.num(w.get("mapMin"));
    Integer cardio;
    if (!ag.isEmpty()) {
      cardio = ag.contains("ne") || ag.contains("epi") ? 3 : 2;
      flags.add("vaso_dose_unknown");
    } else cardio = mp == null ? null : mp < 70 ? 1 : 0;
    Double g = Data.num(w.get("gcsMin"));
    Integer cns = g == null ? null : g >= 15 ? 0 : g >= 13 ? 1 : g >= 10 ? 2 : g >= 6 ? 3 : 4;
    Double cr = Data.num(w.get("creaMaxUmol"));
    Integer renal = cr == null ? null : cr >= 440 ? 4 : cr >= 300 ? 3 : cr >= 170 ? 2 : cr >= 110 ? 1 : 0;
    return new Score(comps(resp, coag, liver, cardio, cns, renal), flags);
  }

  public static Score sofa2(Map<String, Object> w) {
    Set<String> flags = new TreeSet<>();
    Double pf = Data.num(w.get("pfMin"));
    Integer resp = null;
    if (pf != null) {
      // SOFA-2 cut-offs are inclusive (≤300 / ≤225 / ≤150 / ≤75), JAMA 2025 Table 2.
      resp = pf <= 75 ? 4 : pf <= 150 ? 3 : pf <= 225 ? 2 : pf <= 300 ? 1 : 0;
      if (resp > 2) { resp = 2; flags.add("resp_support_unknown"); }
    }
    Double plt = Data.num(w.get("pltMin"));
    Integer coag = plt == null ? null : plt <= 50 ? 4 : plt <= 80 ? 3 : plt <= 100 ? 2 : plt <= 150 ? 1 : 0;
    Double tb = Data.num(w.get("tbilMaxUmol"));
    Double tbm = tb == null ? null : tb / 17.104;
    Integer liver = tbm == null ? null : tbm > 12 ? 4 : tbm > 6 ? 3 : tbm > 3 ? 2 : tbm > 1.2 ? 1 : 0;
    Set<String> ag = agents(w);
    Double mp = Data.num(w.get("mapMin"));
    Integer cardio;
    if (!ag.isEmpty()) {
      flags.add("vaso_dose_unknown");
      // NE/epi combined with another vasopressor/inotrope scores at least 3 even at low dose.
      boolean neEpi = ag.contains("ne") || ag.contains("epi");
      Set<String> other = new HashSet<>(ag);
      other.removeAll(Set.of("ne", "epi"));
      cardio = neEpi && !other.isEmpty() ? 3 : 2;
    } else cardio = mp == null ? null : mp < 70 ? 1 : 0;
    Double g = Data.num(w.get("gcsMin"));
    Integer cns = g == null ? null : g >= 15 ? 0 : g >= 13 ? 1 : g >= 9 ? 2 : g >= 6 ? 3 : 4;
    Double cr = Data.num(w.get("creaMaxUmol"));
    Double crm = cr == null ? null : cr / 88.4;
    Integer renal = crm == null ? null : crm > 3.5 ? 3 : crm > 2.0 ? 2 : crm > 1.2 ? 1 : 0;
    return new Score(comps(resp, coag, liver, cardio, cns, renal), flags);
  }

  public static Integer total(Map<String, Integer> c) { return total(c, 6); }

  public static Integer total(Map<String, Integer> c, int minComponents) {
    int n = 0, s = 0;
    for (Integer v : c.values()) if (v != null) { n++; s += v; }
    return n >= minComponents ? s : null;
  }
}
