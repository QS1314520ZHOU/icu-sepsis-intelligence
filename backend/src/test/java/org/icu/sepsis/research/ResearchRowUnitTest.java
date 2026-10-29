package org.icu.sepsis.research;

import static org.junit.jupiter.api.Assertions.*;

import org.icu.sepsis.integration.ResearchSourceReader.Value;
import org.junit.jupiter.api.Test;

class ResearchRowUnitTest {
  @Test
  void umolAcceptsMicromolAndUmolAndRejectsUnknown() {
    Value micro = new Value(null, 17.1, "micromol/L", "TBIL");
    Value umolU = new Value(null, 17.1, "umol/L", "TBIL");
    Value greek = new Value(null, 17.1, "μmol/L", "TBIL");
    Value mgdl = new Value(null, 1.0, "mg/dL", "TBIL");
    Value plt = new Value(null, 1.0, "10^9/L", "PLT");
    assertEquals(17.1, ResearchRow.umol(micro, 17.104), 0.001);
    assertEquals(17.1, ResearchRow.umol(umolU, 17.104), 0.001);
    assertEquals(17.1, ResearchRow.umol(greek, 17.104), 0.001);
    assertEquals(17.1, ResearchRow.umol(mgdl, 17.104), 0.01);
    assertNull(ResearchRow.umol(plt, 17.104));
  }

  @Test
  void weightAcceptsNumericString() {
    assertEquals(62.5, ResearchRow.num("62.5"), 0.001);
  }
}
