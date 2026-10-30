package org.icu.sepsis.research.toolkit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Loading of the de-identified JSONL export plus small helpers for loosely typed JSON rows. */
public final class Data {
  public static final String COHORT_SCHEMA = "icu-research-cohort/1";
  public static final String ALERT_SCHEMA = "icu-research-alert/1";
  public static final Set<String> FORBIDDEN_KEYS = Set.of("name", "mrn", "hisPid", "patientId", "sourceRecordId", "_id");
  static final ObjectMapper JSON = new ObjectMapper();
  static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
  private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};

  private Data() {}

  /** One loaded export directory. */
  public static final class Export {
    public final List<Map<String, Object>> cohort, alerts;
    public final Map<String, Object> manifest;
    Export(List<Map<String, Object>> cohort, List<Map<String, Object>> alerts, Map<String, Object> manifest) {
      this.cohort = cohort; this.alerts = alerts; this.manifest = manifest;
    }
  }

  public static Map<String, Object> parse(String json) throws IOException { return JSON.readValue(json, MAP); }

  public static List<Map<String, Object>> readJsonl(Path p, String schema) throws IOException {
    List<Map<String, Object>> rows = new ArrayList<>();
    if (!Files.exists(p)) return rows;
    try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
      String line;
      int n = 0;
      while ((line = r.readLine()) != null) {
        n++;
        line = line.strip();
        if (line.isEmpty()) continue;
        Map<String, Object> row = parse(line);
        if (!schema.equals(row.get("schema")))
          throw new IllegalArgumentException(p.getFileName() + ":" + n + " schema " + row.get("schema") + " != " + schema);
        Set<String> leaked = new TreeSet<>();
        collectKeys(row, leaked);
        leaked.retainAll(FORBIDDEN_KEYS);
        if (!leaked.isEmpty()) throw new IllegalArgumentException(p.getFileName() + ":" + n + " contains identifying keys " + leaked);
        rows.add(row);
      }
    }
    return rows;
  }

  private static void collectKeys(Object o, Set<String> out) {
    if (o instanceof Map) {
      for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) { out.add(String.valueOf(e.getKey())); collectKeys(e.getValue(), out); }
    } else if (o instanceof List) {
      for (Object v : (List<?>) o) collectKeys(v, out);
    }
  }

  public static Export load(Path dir) throws IOException {
    Path mf = dir.resolve("manifest.json");
    Map<String, Object> manifest = Files.exists(mf) ? parse(Files.readString(mf, StandardCharsets.UTF_8)) : new LinkedHashMap<>();
    return new Export(readJsonl(dir.resolve("cohort.jsonl"), COHORT_SCHEMA), readJsonl(dir.resolve("alerts.jsonl"), ALERT_SCHEMA), manifest);
  }

  // ---- loose access ----

  /** Dotted path lookup; missing keys or null values give {@code def}. */
  public static Object get(Object row, String path, Object def) {
    Object cur = row;
    for (String part : path.split("\\.")) {
      if (!(cur instanceof Map) || !((Map<?, ?>) cur).containsKey(part)) return def;
      cur = ((Map<?, ?>) cur).get(part);
    }
    return cur == null ? def : cur;
  }

  public static Object get(Object row, String path) { return get(row, path, null); }

  public static Double num(Object v) {
    if (v instanceof Boolean) return (Boolean) v ? 1.0 : 0.0;
    return v instanceof Number ? ((Number) v).doubleValue() : null;
  }

  public static Double num(Object row, String path) { return num(get(row, path)); }

  public static String str(Object row, String path) { Object v = get(row, path); return v == null ? null : String.valueOf(v); }

  /** Python truthiness. */
  public static boolean truthy(Object v) {
    if (v == null) return false;
    if (v instanceof Boolean) return (Boolean) v;
    if (v instanceof Number) return ((Number) v).doubleValue() != 0;
    if (v instanceof String) return !((String) v).isEmpty();
    if (v instanceof Collection) return !((Collection<?>) v).isEmpty();
    if (v instanceof Map) return !((Map<?, ?>) v).isEmpty();
    return true;
  }

  /** Python str() for counter keys. */
  public static String pyStr(Object v) {
    if (v == null) return "None";
    if (v instanceof Boolean) return (Boolean) v ? "True" : "False";
    return String.valueOf(v);
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> map(Object v) { return v instanceof Map ? (Map<String, Object>) v : Collections.emptyMap(); }

  @SuppressWarnings("unchecked")
  public static List<Object> list(Object v) { return v instanceof List ? (List<Object>) v : Collections.emptyList(); }

  /** Ordered map literal that allows null values. */
  public static Map<String, Object> m(Object... kv) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], kv[i + 1]);
    return out;
  }

  public static List<Object> tuple(Object... v) { return new ArrayList<>(Arrays.asList(v)); }

  // ---- counters ----

  public static void inc(Map<String, Integer> c, String k) { c.merge(k, 1, Integer::sum); }

  /** Counter.most_common(): count desc, ties keep insertion order. */
  public static Map<String, Integer> mostCommon(Map<String, Integer> c) {
    List<Map.Entry<String, Integer>> es = new ArrayList<>(c.entrySet());
    es.sort((a, b) -> b.getValue() - a.getValue());
    Map<String, Integer> out = new LinkedHashMap<>();
    for (Map.Entry<String, Integer> e : es) out.put(e.getKey(), e.getValue());
    return out;
  }

  // ---- formatting ----

  public static String f(String fmt, Object... a) { return String.format(Locale.ROOT, fmt, a); }

  /** Python format spec ".{digits}g". */
  public static String g(double x, int digits) {
    if (Double.isNaN(x)) return "nan";
    if (x == 0) return "0";
    int exp = (int) Math.floor(Math.log10(Math.abs(x)));
    BigDecimal bd = new BigDecimal(x).round(new MathContext(digits));
    exp = bd.precision() - bd.scale() - 1;
    if (exp < -4 || exp >= digits) {
      BigDecimal mant = bd.movePointLeft(exp).stripTrailingZeros();
      return mant.toPlainString() + (exp < 0 ? "e-" : "e+") + String.format(Locale.ROOT, "%02d", Math.abs(exp));
    }
    return bd.stripTrailingZeros().toPlainString();
  }

  public static double round(double x, int places) {
    if (Double.isNaN(x) || Double.isInfinite(x)) return x;
    return new BigDecimal(Double.toString(x)).setScale(places, java.math.RoundingMode.HALF_EVEN).doubleValue();
  }
}
