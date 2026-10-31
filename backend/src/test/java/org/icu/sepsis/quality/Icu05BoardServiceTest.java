package org.icu.sepsis.quality;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.icu.sepsis.domain.Domain.Patient;
import org.junit.jupiter.api.Test;

class Icu05BoardServiceTest {
  static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");
  static Map<String, Object> it(String code, Boolean v) { Map<String, Object> m = new LinkedHashMap<>(); m.put("code", code); m.put("value", v); return m; }
  static Map<String, Object> w(String key, String state, Instant deadline, List<String> reasons, Map<String, Object>... items) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("key", key); m.put("state", state); m.put("deadline", deadline); m.put("reasons", reasons); m.put("items", List.of(items)); return m;
  }
  static Icu05BoardService.Case c(String id, String status, Boolean eligible, Map<String, Object>... windows) {
    Patient p = new Patient(); p.id = id; p.encounterId = "e" + id; p.label = "患者" + id; p.bed = id;
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("available", true); r.put("status", status); r.put("statusLabel", status); r.put("t0", NOW.minusSeconds(3600 * 4));
    r.put("pool", Map.of("eligible", eligible == null ? "x" : eligible)); if (eligible == null) r.put("pool", new HashMap<>());
    r.put("windows", List.of(windows));
    return new Icu05BoardService.Case(p, r);
  }

  @Test void ratesExcludeUnknownAndIneligible() {
    List<Icu05BoardService.Case> cs = List.of(
        c("1", "septic_shock", true,
            w("1h", "met", NOW.minusSeconds(3 * 3600), List.of(), it("A1", true), it("B1", true), it("B3", true)),
            w("3h", "not_met", NOW.minusSeconds(3600), List.of("FLUID_INSUFFICIENT"), it("B3", true)),
            w("6h", "in_progress", NOW.plusSeconds(7200), List.of("WINDOW_OPEN"), it("A1", true), it("A2", null))),
        c("2", "septic_shock", true,
            w("1h", "not_met", NOW.minusSeconds(3 * 3600), List.of("BC_AFTER_AB"), it("A1", null), it("B1", true), it("B3", false)),
            w("3h", "unknown", NOW.minusSeconds(3600), List.of()),
            w("6h", "met", NOW.minusSeconds(60), List.of())),
        c("3", "septic_shock", false, w("1h", "met", NOW, List.of())),
        c("4", "suspected_review", null),
        c("5", "not_met", true));
    Map<String, Object> out = Icu05BoardService.aggregate(cs, NOW);
    assertEquals(5, out.get("screened"));
    assertEquals(3, out.get("septicShock"));
    assertEquals(2, out.get("denominator"));
    assertEquals(1, out.get("excluded"));
    assertEquals(1, out.get("suspected"));
    @SuppressWarnings("unchecked") List<Map<String, Object>> ws = (List<Map<String, Object>>) out.get("windows");
    assertEquals(0.5, ws.get(0).get("rate"));
    assertEquals(0.0, ws.get(1).get("rate"), "unknown is not counted as not met");
    assertEquals(1, ws.get(1).get("unknown"));
    assertEquals(1.0, ws.get(2).get("rate"));
    assertEquals(1, ws.get(2).get("inProgress"));
    @SuppressWarnings("unchecked") List<Map<String, Object>> ind = (List<Map<String, Object>>) out.get("indicators");
    assertEquals(1.0, ind.get(0).get("rate"));
    assertEquals(0.5, ind.get(1).get("rate"));
    assertEquals(1, ind.get(2).get("unknown"));
    assertEquals(false, ind.get(3).get("available"));
    @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("cases");
    assertEquals(4, rows.size(), "non-shock cases are not listed");
    assertEquals("1", rows.get(0).get("patientId"), "open window with a pending item comes first");
    assertEquals("复测乳酸", rows.get(0).get("next"));
    assertEquals(120L, rows.get(0).get("minutesLeft"));
    assertEquals("4", rows.get(1).get("patientId"), "suspected cases need review next");
    assertTrue(((List<?>) rows.get(2).get("failReasons")).contains("抗菌药早于血培养"));
  }
}
