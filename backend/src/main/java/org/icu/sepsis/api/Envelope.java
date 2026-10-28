package org.icu.sepsis.api;

import java.time.Instant;
import org.slf4j.MDC;

public class Envelope<T> {
  public String code, message, traceId;
  public T data;
  public Instant timestamp;

  public Envelope(String c, String m, T d) {
    code = c;
    message = m;
    data = d;
    traceId = MDC.get("traceId");
    timestamp = Instant.now();
  }

  public static <T> Envelope<T> ok(T d) {
    return new Envelope<>("OK", "成功", d);
  }
}
