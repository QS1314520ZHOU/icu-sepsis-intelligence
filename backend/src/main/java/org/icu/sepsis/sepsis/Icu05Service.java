package org.icu.sepsis.sepsis;

import java.time.Instant;
import java.util.*;
import org.icu.sepsis.api.ApiException;
import org.icu.sepsis.domain.Domain.Patient;
import org.icu.sepsis.integration.Icu05SourceReader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** ICU-05 septic-shock bundle timeline (read-only, director rule set). */
@Service
public class Icu05Service {
  private final ObjectProvider<Icu05SourceReader> reader;
  public Icu05Service(ObjectProvider<Icu05SourceReader> reader) { this.reader = reader; }

  public Map<String, Object> evaluate(Patient p, Instant asOf) {
    Icu05SourceReader r = reader.getIfAvailable();
    if (r == null) return unavailable(p, asOf, "当前数据源未接入医嘱、执行单与血气记录，不做 ICU-05 判定。");
    Map<String, Object> out;
    try {
      out = Icu05Rules.evaluate(r.collect(p.id, p.encounterId, asOf));
    } catch (ApiException e) {
      throw e;
    } catch (RuntimeException e) {
      return unavailable(p, asOf, "ICU-05 数据读取失败，请刷新或联系接口维护人员。");
    }
    Map<String, Object> m = header(p, asOf, true, "");
    m.putAll(out);
    return m;
  }

  private Map<String, Object> header(Patient p, Instant asOf, boolean available, String message) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("schemaVersion", 1); m.put("patientId", p.id); m.put("encounterId", p.encounterId);
    m.put("simulated", p.simulated); m.put("available", available); m.put("message", message); m.put("asOf", asOf);
    return m;
  }
  private Map<String, Object> unavailable(Patient p, Instant asOf, String message) {
    Map<String, Object> m = header(p, asOf, false, message);
    m.put("ruleVersion", Icu05Rules.RULE_VERSION); m.put("ruleSource", Icu05Rules.RULE_SOURCE);
    m.put("status", "unavailable"); m.put("statusLabel", "未接入"); m.put("windows", List.of());
    return m;
  }
}
