package org.icu.sepsis.alerting;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.icu.sepsis.application.Store;
import org.icu.sepsis.domain.Domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class ScreeningAlertServiceTest {
  static Map<String, Object> f(String code, String label, double v) {
    Map<String, Object> m = new LinkedHashMap<>(); m.put("code", code); m.put("label", label); m.put("value", v); return m;
  }
  static Map<String, Object> review(Boolean vaso, String clue, Map<String, Object>... findings) {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("available", true); r.put("vasopressorActive", vaso); r.put("infectionClue", clue); r.put("findings", List.of(findings));
    return r;
  }
  static final String CLUE = "诊断文本含感染线索，需核对时效及临床依据", NO = "未取得可确认的感染证据";

  @Test void lactateAndLowMapIsShock() {
    List<ScreeningAlertService.Hit> h = ScreeningAlertService.fromReview(review(null, NO,
        f("LACTATE", "乳酸", 3.1), f("MAP_IBP", "平均动脉压（有创）", 58), f("MAP_NIBP", "平均动脉压（无创）", 62)));
    assertEquals(1, h.size());
    assertEquals("critical", h.get(0).severity);
    assertTrue(h.get(0).reason.contains("乳酸 3.1") && h.get(0).reason.contains("58 mmHg"), h.get(0).reason);
  }
  @Test void vasopressorAloneIsShock() {
    assertEquals(ScreeningAlertService.SHOCK, ScreeningAlertService.fromReview(review(true, NO)).get(0).kind);
  }
  @Test void lactateWithoutHypotensionIsNotShock() {
    assertTrue(ScreeningAlertService.fromReview(review(false, NO, f("LACTATE", "乳酸", 3.1))).isEmpty());
  }
  @Test void infectionPlusAbnormality() {
    List<ScreeningAlertService.Hit> h = ScreeningAlertService.fromReview(review(false, CLUE, f("RR", "呼吸频率", 26)));
    assertEquals(ScreeningAlertService.INFECTION, h.get(0).kind);
    assertEquals("high", h.get(0).severity);
    assertTrue(ScreeningAlertService.fromReview(review(false, CLUE)).isEmpty(), "infection without abnormality");
  }
  @Test void unavailableReviewNeverAlerts() {
    Map<String, Object> r = review(true, CLUE, f("RR", "呼吸频率", 26)); r.put("available", false);
    assertTrue(ScreeningAlertService.fromReview(r).isEmpty());
  }
  @Test void icu05Gate() {
    Map<String, Object> gate = new LinkedHashMap<>(); gate.put("status", "suspected_review"); gate.put("statusLabel", "疑似，待复核感染证据"); gate.put("mapMin", 61.0);
    Map<String, Object> b = new LinkedHashMap<>(); b.put("available", true); b.put("gate", gate);
    assertEquals("critical", ScreeningAlertService.fromIcu05(b).get(0).severity);
    gate.put("status", "not_met"); gate.put("hasInfection", true); gate.put("hasOrganDysfunction", true);
    assertEquals(ScreeningAlertService.INFECTION, ScreeningAlertService.fromIcu05(b).get(0).kind);
    gate.put("hasOrganDysfunction", null);
    assertTrue(ScreeningAlertService.fromIcu05(b).isEmpty());
  }

  // ---- persistence / dedupe
  final MongoTemplate db = mock(MongoTemplate.class);
  final Store store = new Store(db, "ICU", new ObjectMapper());
  final Instant now = Instant.parse("2026-10-30T08:00:00Z");
  final ScreeningAlertService svc = new ScreeningAlertService(store, null, null, Clock.fixed(now, ZoneOffset.UTC), true, 30, 6, 60);
  Patient patient() { Patient p = new Patient(); p.id = "p1"; p.encounterId = "e1"; p.department = "ICU"; p.label = "张三"; p.bed = "03"; return p; }
  List<ScreeningAlertService.Hit> shock() { return ScreeningAlertService.fromReview(review(true, CLUE, f("RR", "呼吸频率", 26))); }

  @Test void createsOneAlertAndShockSupersedesInfection() {
    assertEquals(1, svc.raise(patient(), shock()));
    verify(db).insert(argThat((Object o) -> o instanceof Alert && "critical".equals(((Alert) o).severity)
        && "pending".equals(((Alert) o).status) && "03".equals(((Alert) o).bed)));
  }
  @Test void openAlertIsNotDuplicated() {
    Alert open = new Alert(); open.status = "pending";
    when(db.findOne(any(Query.class), eq(Alert.class))).thenReturn(open);
    assertEquals(0, svc.raise(patient(), shock()));
    verify(db, never()).insert(any(Alert.class));
    assertEquals(now, open.lastSeenAt);
  }
  @Test void recentlyRejectedIsSuppressedThenReraised() {
    Alert last = new Alert(); last.status = "rejected"; last.closedAt = now.minus(Duration.ofHours(2));
    when(db.findOne(any(Query.class), eq(Alert.class))).thenReturn(null, last);
    assertEquals(0, svc.raise(patient(), shock()));
    last.closedAt = now.minus(Duration.ofHours(7));
    when(db.findOne(any(Query.class), eq(Alert.class))).thenReturn(null, last);
    assertEquals(1, svc.raise(patient(), shock()));
  }
}
