package org.icu.sepsis.integration;

import java.time.Instant;
import java.util.List;
import org.icu.sepsis.domain.Domain.Observation;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "app.source.mode",
    havingValue = "demo",
    matchIfMissing = true)
public class MongoClinicalDataProvider implements ClinicalDataProvider {
  private final MongoTemplate db;

  public MongoClinicalDataProvider(MongoTemplate d) {
    db = d;
  }

  public List<Observation> observations(String p, String e, Instant f, Instant a) {
    return db.find(
        Query.query(
                Criteria.where("patientId")
                    .is(p)
                    .and("encounterId")
                    .is(e)
                    .and("eventTime")
                    .gte(f)
                    .lte(a)
                    .and("ingestTime")
                    .lte(a))
            .with(Sort.by(Sort.Direction.DESC, "eventTime"))
            .limit(2000),
        Observation.class);
  }
}
