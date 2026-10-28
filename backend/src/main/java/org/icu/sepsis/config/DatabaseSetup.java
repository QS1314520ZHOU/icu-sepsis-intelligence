package org.icu.sepsis.config;

import java.time.*;
import java.util.*;
import org.icu.sepsis.domain.Domain.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.*;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

@Component
public class DatabaseSetup implements ApplicationRunner {
  private final MongoTemplate db;
  private final boolean demo;
  private final String dept;

  public DatabaseSetup(
      MongoTemplate d, @Value("${app.demo}") boolean dem, @Value("${app.department}") String dep) {
    db = d;
    demo = dem;
    dept = dep;
  }

  public void run(ApplicationArguments args) {
    String name = db.getDb().getName();
    if (!name.startsWith("icu_sepsis_"))
      throw new IllegalStateException(
          "New business collections must live in a dedicated icu_sepsis_* database, never"
              + " SmartCare/DataCenter");
    if (demo && !name.endsWith("_demo") && !name.endsWith("_test"))
      throw new IllegalStateException(
          "Demo seeding requires a dedicated *_demo or *_test database");
    for (Class<?> t :
        List.of(
            Patient.class,
            Observation.class,
            Assessment.class,
            Alert.class,
            Task.class,
            Execution.class,
            History.class,
            Audit.class,
            Command.class)) {
      if (!db.collectionExists(t)) db.createCollection(t);
      db.indexOps(t)
          .ensureIndex(
              new Index()
                  .on("department", Sort.Direction.ASC)
                  .on("createdAt", Sort.Direction.DESC));
    }
    db.indexOps(Assessment.class)
        .ensureIndex(
            new Index()
                .on("patientId", Sort.Direction.ASC)
                .on("encounterId", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.DESC));
    db.indexOps(Observation.class)
        .ensureIndex(
            new Index()
                .on("patientId", Sort.Direction.ASC)
                .on("encounterId", Sort.Direction.ASC)
                .on("eventTime", Sort.Direction.DESC));
    db.indexOps(Observation.class)
        .ensureIndex(
            new Index()
                .on("sourceSystem", Sort.Direction.ASC)
                .on("sourceRecordId", Sort.Direction.ASC)
                .unique());
    db.indexOps(Alert.class)
        .ensureIndex(
            new Index()
                .on("activeKey", Sort.Direction.ASC)
                .unique()
                .partial(PartialIndexFilter.of(Criteria.where("activeKey").type(2))));
    db.indexOps(Alert.class)
        .ensureIndex(
            new Index()
                .on("department", Sort.Direction.ASC)
                .on("status", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.DESC));
    db.indexOps(Task.class)
        .ensureIndex(
            new Index()
                .on("alertId", Sort.Direction.ASC)
                .on("itemCode", Sort.Direction.ASC)
                .unique());
    db.indexOps(Task.class)
        .ensureIndex(
            new Index().on("department", Sort.Direction.ASC).on("dueAt", Sort.Direction.ASC));
    db.indexOps(Task.class)
        .ensureIndex(
            new Index().on("department", Sort.Direction.ASC).on("triggerTime", Sort.Direction.ASC));
    db.indexOps(Execution.class)
        .ensureIndex(
            new Index()
                .on("taskId", Sort.Direction.ASC)
                .on("sourceSystem", Sort.Direction.ASC)
                .on("sourceRecordId", Sort.Direction.ASC)
                .unique());
    db.indexOps(History.class)
        .ensureIndex(
            new Index().on("entityId", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC));
    if (demo && db.findById("demo-normal", Patient.class) == null) {
      seed("demo-normal", "模拟患者 A", "01", false, false);
      seed("demo-deteriorating", "模拟患者 B", "02", true, false);
      seed("demo-insufficient", "模拟患者 C", "03", false, true);
    }
  }

  private void seed(String id, String label, String bed, boolean ill, boolean missing) {
    Instant now = Instant.now();
    Patient p = new Patient();
    p.id = id;
    p.patientId = id;
    p.encounterId = id + "-enc-001";
    p.department = dept;
    p.label = label;
    p.bed = bed;
    p.simulated = true;
    p.createdAt = now;
    p.admittedAt = now.minus(Duration.ofDays(1));
    db.insert(p);
    if (missing) return;
    String[] codes = {"MAP", "SBP", "RR", "GCS", "LACTATE", "VASOPRESSOR_ACTIVE", "HR"};
    String[] units = {"mmHg", "mmHg", "/min", "score", "mmol/L", "boolean", "bpm"};
    double[] vals =
        ill
            ? new double[] {58, 85, 28, 14, 3.5, 1, 120}
            : new double[] {78, 120, 18, 15, 1.1, 0, 80};
    for (int i = 0; i < codes.length; i++) {
      Observation o = obs(p, now);
      o.parameterCode = codes[i];
      o.unit = units[i];
      o.value = vals[i];
      db.insert(o);
    }
    // Synthetic trajectories are seeded only in an explicitly isolated demo database.
    // They are not forecasts, training examples, or measured clinical outcomes.
    String[] trendCodes = {"HR", "MAP", "SBP", "RR", "SPO2", "TEMP", "LACTATE"};
    String[] trendUnits = {"bpm", "mmHg", "mmHg", "/min", "%", "°C", "mmol/L"};
    double[] start =
        ill
            ? new double[] {86, 80, 118, 19, 98, 37.1, 1.2}
            : new double[] {80, 78, 120, 18, 98, 36.8, 1.1};
    double[] end =
        ill
            ? new double[] {120, 58, 85, 28, 93, 38.6, 3.5}
            : new double[] {80, 78, 120, 18, 98, 36.8, 1.1};
    for (int step = 0; step <= 96; step++) {
      Instant event = now.minusSeconds(600 + (96 - step) * 900L);
      if (event.isBefore(p.admittedAt)) continue;
      double progress = step / 96.0;
      for (int k = 0; k < trendCodes.length; k++) {
        // Deliberately absent samples demonstrate discontinuities without filling.
        if (ill && ((k == 1 || k == 2) && step >= 65 && step <= 69)) continue;
        if (ill && k == 4 && step >= 80 && step <= 83) continue;
        if (k == 6 && step % 8 != 0) continue;
        // The latest existing vital/lab samples above remain the source of truth.
        if (step == 96 && k != 4 && k != 5) continue;
        Observation point = obs(p, now);
        point.eventTime = event;
        point.ingestTime = event.plusSeconds(60);
        point.parameterCode = trendCodes[k];
        point.unit = trendUnits[k];
        double wave = step == 96 ? 0 : Math.sin(step * .62) * (k == 5 || k == 6 ? .12 : 1.6);
        point.value = Math.round((start[k] + (end[k] - start[k]) * progress + wave) * 10) / 10.0;
        db.insert(point);
      }
    }
    if (ill) {
      Observation invalid = obs(p, now);
      invalid.parameterCode = "MAP";
      invalid.unit = "kPa";
      invalid.value = 8.0;
      invalid.eventTime = now.minusSeconds(13500);
      invalid.ingestTime = invalid.eventTime.plusSeconds(60);
      db.insert(invalid); // visible in the source table, excluded from mmHg curve
    }
    Observation dx = obs(p, now);
    dx.parameterCode = "DIAGNOSIS";
    dx.text = ill ? "感染已确诊（模拟记录，仅验证软件）" : "排除感染（模拟记录，仅验证软件）";
    db.insert(dx);
  }

  private Observation obs(Patient p, Instant now) {
    Observation o = new Observation();
    o.id = UUID.randomUUID().toString();
    o.patientId = p.id;
    o.encounterId = p.encounterId;
    o.department = p.department;
    o.simulated = true;
    o.createdAt = now;
    o.eventTime = now.minusSeconds(600);
    o.ingestTime = now.minusSeconds(120);
    o.sourceSystem = "MOCK_ONLY";
    o.sourceRecordId = o.id;
    o.qualityStatus = "valid";
    return o;
  }
}
