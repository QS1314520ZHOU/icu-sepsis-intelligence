package org.icu.sepsis.prediction;

import java.util.List;
import org.icu.sepsis.domain.Domain.*;

public interface RiskModelProvider {
  String status();

  List<Prediction> predict(List<Observation> evidence);
}
