package org.icu.sepsis.sepsis;

import java.time.Instant;
import java.util.*;

/** Evidence timing, never inferred treatment appropriateness or clinical target attainment. */
public final class BundleModels {
  private BundleModels() {}
  public static String eventStatus(Instant event, Instant t0, Instant due) {
    if (event == null || t0 == null || due == null || due.isBefore(t0)) return "unknown";
    if (event.isBefore(t0)) return "before_anchor";
    return event.isAfter(due) ? "recorded_late" : "recorded_in_window";
  }
  public static Map<String, Object> item(String code, String name, String status, String note, Instant at) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("code", code); m.put("name", name); m.put("status", status); m.put("note", note); m.put("eventTime", at);
    return m;
  }
}
