package org.icu.sepsis.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.icu.sepsis.integration.Icu05SourceReader;
import org.icu.sepsis.integration.ResearchSourceReader;
import org.icu.sepsis.integration.SmartCareSourceReader;
import org.icu.sepsis.sepsis.Icu05Rules;
import org.icu.sepsis.sepsis.Icu05Rules.Evidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Offline, read-only research cohort export. Enabled only with app.research.export.enabled=true
 * (run with --spring.main.web-application-type=none). Writes JSONL files to a local directory;
 * never writes to SmartCare / DataCenter and never exports names, record numbers or source ids.
 */
@Component
@ConditionalOnProperty(name = "app.research.export.enabled", havingValue = "true")
public class ResearchExportRunner implements CommandLineRunner {
  private static final Logger log = LoggerFactory.getLogger(ResearchExportRunner.class);
  private final Icu05SourceReader icu05;
  private final ResearchSourceReader extra;
  private final MongoTemplate business;
  private final ConfigurableApplicationContext ctx;
  private final String from, to, out, salt, deathTypes;
  private final int limit, afterHours;

  public ResearchExportRunner(Icu05SourceReader icu05, ResearchSourceReader extra, MongoTemplate business, ConfigurableApplicationContext ctx,
      @Value("${app.research.export.from:}") String from, @Value("${app.research.export.to:}") String to,
      @Value("${app.research.export.out:research-export}") String out, @Value("${app.research.export.salt:}") String salt,
      @Value("${app.research.export.death-types:死亡,非医嘱离院}") String deathTypes,
      @Value("${app.research.export.limit:100000}") int limit, @Value("${app.research.export.after-hours:30}") int afterHours) {
    this.icu05 = icu05; this.extra = extra; this.business = business; this.ctx = ctx;
    this.from = from; this.to = to; this.out = out; this.salt = salt; this.deathTypes = deathTypes; this.limit = limit; this.afterHours = afterHours;
  }

  static ObjectMapper mapper() {
    return new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  @Override public void run(String... args) throws Exception {
    int code = 0;
    try { export(); } catch (Exception e) { log.error("research export failed: {}", e.getMessage()); code = 2; }
    int c = code;
    System.exit(org.springframework.boot.SpringApplication.exit(ctx, () -> c));
  }

  public Map<String, Object> export() throws Exception {
    if (salt == null || salt.length() < 12) throw new IllegalArgumentException("app.research.export.salt 至少 12 位，且不要提交到代码库");
    if (from.isBlank() || to.isBlank()) throw new IllegalArgumentException("需要 app.research.export.from / to（yyyy-MM-dd）");
    ZoneId zone = ZoneId.of("Asia/Shanghai");
    Instant a = LocalDate.parse(from).atStartOfDay(zone).toInstant(), b = LocalDate.parse(to).atStartOfDay(zone).toInstant();
    Set<String> death = new HashSet<>();
    for (String s : deathTypes.split("[,，]")) if (!s.isBlank()) death.add(s.trim());
    Path dir = Paths.get(out);
    Files.createDirectories(dir);
    ObjectMapper m = mapper();
    Instant asOf = Instant.now();
    int rows = 0, noT0 = 0, failed = 0;
    Map<String, Integer> sourceFailures = new TreeMap<>();
    Map<String, Instant> t0ByKey = new HashMap<>();
    try (BufferedWriter w = Files.newBufferedWriter(dir.resolve("cohort.jsonl"), StandardCharsets.UTF_8)) {
      for (Document p : extra.stays(a, b, limit)) {
        String id = p.getObjectId("_id").toHexString(), enc = SmartCareSourceReader.encounterIdOf(p);
        try {
          Evidence e = icu05.collect(id, null, asOf, Duration.ofHours(afterHours), true);
          Map<String, Object> result = Icu05Rules.evaluate(e);
          Set<String> f = new HashSet<>(e.failed);
          Instant t0 = e.t0;
          Map<String, List<ResearchSourceReader.Value>> labs = t0 == null ? emptyLabs()
              : extra.labs(p.getString("hisPid"), t0.minus(Duration.ofHours(6)), t0.plus(Duration.ofHours(24)), f);
          List<Document> scores = e.icuAdmission == null ? List.of()
              : extra.scores(id, e.icuAdmission.minus(Duration.ofHours(6)), (t0 == null ? e.icuAdmission : t0).plus(Duration.ofHours(30)), f);
          e.failed.addAll(f);
          Map<String, Object> row = ResearchRow.build(p, enc, e, result, labs, scores, salt, death);
          if (t0 != null) t0ByKey.put((String) row.get("stayKey"), t0);
          w.write(m.writeValueAsString(row)); w.newLine();
          rows++;
          if (t0 == null) noT0++;
          for (String s : e.failed) sourceFailures.merge(s, 1, Integer::sum);
        } catch (RuntimeException ex) { failed++; }
      }
    }
    int alerts = exportAlerts(dir, m, a, b, t0ByKey);
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("schema", ResearchRow.SCHEMA); manifest.put("exportedAt", asOf.toString()); manifest.put("from", from); manifest.put("to", to);
    manifest.put("stays", rows); manifest.put("staysWithoutT0", noT0); manifest.put("staysFailed", failed); manifest.put("alerts", alerts);
    manifest.put("sourceFailures", sourceFailures); manifest.put("ruleVersion", Icu05Rules.RULE_VERSION);
    manifest.put("note", "只读导出；不含姓名、病案号、源记录 id；stayKey 为加盐哈希。");
    Files.writeString(dir.resolve("manifest.json"), m.writerWithDefaultPrettyPrinter().writeValueAsString(manifest), StandardCharsets.UTF_8);
    log.info("research export: {} stays, {} without T0, {} failed, {} alerts", rows, noT0, failed, alerts);
    return manifest;
  }

  private static Map<String, List<ResearchSourceReader.Value>> emptyLabs() {
    Map<String, List<ResearchSourceReader.Value>> m = new LinkedHashMap<>();
    for (String k : ResearchSourceReader.LAB_CODES.keySet()) m.put(k, new ArrayList<>());
    return m;
  }

  /** Alert handling from the app's own business DB (icu_sepsis_*): times, status and review reasons. */
  int exportAlerts(Path dir, ObjectMapper m, Instant a, Instant b, Map<String, Instant> t0ByKey) throws Exception {
    int n = 0;
    Map<String, List<Document>> history = new HashMap<>();
    for (Document h : business.getCollection("sepsis_state_history").find(new Document("entityType", "alert")
        .append("createdAt", new Document("$gte", Date.from(a)))))
      history.computeIfAbsent(h.getString("entityId"), k -> new ArrayList<>()).add(h);
    try (BufferedWriter w = Files.newBufferedWriter(dir.resolve("alerts.jsonl"), StandardCharsets.UTF_8)) {
      for (Document d : business.getCollection("sepsis_alert_event").find(new Document("createdAt",
          new Document("$gte", Date.from(a)).append("$lt", Date.from(b)))).projection(new Document("_id", 1).append("encounterId", 1)
          .append("createdAt", 1).append("acknowledgedAt", 1).append("closedAt", 1).append("status", 1).append("severity", 1).append("simulated", 1))) {
        if (Boolean.TRUE.equals(d.getBoolean("simulated"))) continue;
        Map<String, Object> r = new LinkedHashMap<>();
        Instant created = inst(d.get("createdAt"));
        r.put("schema", "icu-research-alert/1");
        String key = d.getString("encounterId") == null ? null : ResearchRow.stayKey(salt, d.getString("encounterId"));
        r.put("stayKey", key);
        // No absolute timestamps: position relative to the stay's T0, plus hour of day / weekday for shift analysis.
        r.put("minFromT0", key == null || created == null ? null : minutes(t0ByKey.get(key), created));
        r.put("weekday", created == null ? null : created.atZone(ZoneId.of("Asia/Shanghai")).getDayOfWeek().getValue());
        r.put("hourOfDay", created == null ? null : created.atZone(ZoneId.of("Asia/Shanghai")).getHour());
        r.put("severity", d.getString("severity")); r.put("status", d.getString("status"));
        r.put("ackMin", minutes(created, inst(d.get("acknowledgedAt"))));
        r.put("closeMin", minutes(created, inst(d.get("closedAt"))));
        List<Map<String, Object>> reviews = new ArrayList<>();
        for (Document h : history.getOrDefault(String.valueOf(d.get("_id")), List.of())) {
          Map<String, Object> hv = new LinkedHashMap<>();
          hv.put("action", h.getString("action")); hv.put("reason", h.getString("reason"));
          hv.put("min", minutes(created, inst(h.get("createdAt"))));
          hv.put("actorKey", h.getString("actor") == null ? null : ResearchRow.stayKey(salt, "actor|" + h.getString("actor")));
          reviews.add(hv);
        }
        r.put("reviews", reviews);
        w.write(m.writeValueAsString(r)); w.newLine(); n++;
      }
    }
    return n;
  }
  static Long minutes(Instant a, Instant b) { return a == null || b == null ? null : Duration.between(a, b).toMinutes(); }
  static Instant inst(Object o) { return o instanceof Date ? ((Date) o).toInstant() : null; }
}
