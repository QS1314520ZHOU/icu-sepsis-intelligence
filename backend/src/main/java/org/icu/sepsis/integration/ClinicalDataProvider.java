package org.icu.sepsis.integration;

import java.time.Instant;
import java.util.List;
import org.icu.sepsis.domain.Domain.Observation;

public interface ClinicalDataProvider {
  List<Observation> observations(String patientId, String encounterId, Instant from, Instant asOf);
}
