package org.icu.sepsis.prediction;

import java.util.*;
import org.icu.sepsis.domain.Domain.*;
import org.springframework.stereotype.Component;

@Component
public class UnavailableRiskModelProvider implements RiskModelProvider {
  public String status() {
    return "weight_missing";
  }

  public List<Prediction> predict(List<Observation> evidence) {
    List<Prediction> r = new ArrayList<>();
    for (int h : new int[] {4, 12, 24}) {
      Prediction p = new Prediction();
      p.horizonHours = h;
      r.add(p);
    }
    return r;
  }
}
