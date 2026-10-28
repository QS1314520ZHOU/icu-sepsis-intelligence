package org.icu.sepsis.integration;

import java.time.Instant;
import java.util.List;
import org.icu.sepsis.domain.Domain.Observation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Live reads from hospital SmartCare/DataCenter when {@code app.source.mode=smartcare}. */
@Component
@ConditionalOnProperty(name = "app.source.mode", havingValue = "smartcare")
public class SmartCareClinicalDataProvider implements ClinicalDataProvider {
  private final SmartCareSourceReader reader;

  public SmartCareClinicalDataProvider(SmartCareSourceReader r) {
    reader = r;
  }

  @Override
  public List<Observation> observations(
      String patientId, String encounterId, Instant from, Instant asOf) {
    String department = resolveDepartment(patientId);
    return reader.observations(patientId, encounterId, department, from, asOf);
  }

  private String resolveDepartment(String patientId) {
    try {
      return reader.loadPatient(patientId).department;
    } catch (Exception e) {
      return "";
    }
  }
}
