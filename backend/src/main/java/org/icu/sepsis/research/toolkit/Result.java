package org.icu.sepsis.research.toolkit;

import java.util.*;

/** Analysis result {status, summary, data}; status is ok / insufficient / no_data / error. */
final class Result {
  private Result() {}

  static Map<String, Object> of(String status, List<String> summary, Map<String, Object> data) {
    return Data.m("status", status, "summary", summary, "data", data == null ? new LinkedHashMap<>() : data);
  }

  static Map<String, Object> of(String status, String summary, Map<String, Object> data) { return of(status, List.of(summary), data); }

  static String pct(Double p) { return p == null ? "—" : Data.f("%.1f%%", p * 100); }

  static String ci(Double[] t) {
    return t[0] == null ? "—" : Data.f("%.1f%%（95%%CI %.1f–%.1f%%）", t[0] * 100, t[1] * 100, t[2] * 100);
  }
}
