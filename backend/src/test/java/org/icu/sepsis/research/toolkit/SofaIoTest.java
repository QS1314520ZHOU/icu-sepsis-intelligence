package org.icu.sepsis.research.toolkit;

import static org.icu.sepsis.research.toolkit.Data.m;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SofaIoTest {
  @TempDir Path tmp;

  static Map<String, Integer> comps(int resp, int coag, int liver, int cardio, int cns, int renal) {
    Map<String, Integer> c = new LinkedHashMap<>();
    c.put("resp", resp); c.put("coag", coag); c.put("liver", liver); c.put("cardio", cardio); c.put("cns", cns); c.put("renal", renal);
    return c;
  }

  @Test void missingComponentsStayNull() {
    Sofa.Score s = Sofa.classic(Map.of());
    assertTrue(s.components.values().stream().allMatch(Objects::isNull));
    assertNull(Sofa.total(s.components));
    assertNull(Sofa.total(s.components, 4));
  }

  @Test void classicBands() {
    Sofa.Score s = Sofa.classic(m("pfMin", 250, "pltMin", 40, "tbilMaxUmol", 110, "mapMin", 60, "gcsMin", 9, "creaMaxUmol", 320, "vasoAgents", List.of()));
    assertEquals(comps(2, 3, 3, 1, 3, 3), s.components);
    assertEquals(15, Sofa.total(s.components));
    assertTrue(s.flags.isEmpty());
  }

  @Test void flagsAndCaps() {
    Sofa.Score s = Sofa.classic(m("pfMin", 80, "vasoAgents", List.of("ne")));
    assertEquals(2, s.components.get("resp"));
    assertEquals(3, s.components.get("cardio"));
    assertEquals(Set.of("resp_support_unknown", "vaso_dose_unknown"), s.flags);
  }

  @Test void sofa2Bands() {
    Sofa.Score s = Sofa.sofa2(m("pfMin", 200, "pltMin", 70, "tbilMaxUmol", 3.1 * 17.104, "mapMin", 72, "gcsMin", 9, "creaMaxUmol", 2.1 * 88.4,
        "vasoAgents", List.of("ne", "dobutamine")));
    assertEquals(comps(2, 3, 2, 3, 2, 2), s.components);
    assertEquals(2, Sofa.sofa2(m("vasoAgents", List.of("ne"))).components.get("cardio"));
  }

  private Path write(String... rows) throws Exception {
    Files.writeString(tmp.resolve("cohort.jsonl"), String.join("\n", rows), StandardCharsets.UTF_8);
    return tmp;
  }

  @Test void rejectsIdentifyingKeys() throws Exception {
    Path d = write("{\"schema\":\"icu-research-cohort/1\",\"stayKey\":\"x\",\"outcome\":{\"mrn\":\"123\"}}");
    assertThrows(IllegalArgumentException.class, () -> Data.load(d));
  }

  @Test void rejectsWrongSchema() throws Exception {
    Path d = write("{\"schema\":\"other/1\",\"stayKey\":\"x\"}");
    assertThrows(IllegalArgumentException.class, () -> Data.load(d));
  }

  @Test void getPaths() {
    Map<String, Object> r = m("a", m("b", m("c", 1), "n", null));
    assertEquals(1, Data.get(r, "a.b.c"));
    assertEquals(5, Data.get(r, "a.n", 5));
    assertNull(Data.get(r, "a.x.y"));
  }
}
