package org.icu.sepsis.api;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
  public final HttpStatus status;
  public final String code;

  public ApiException(HttpStatus s, String c, String m) {
    super(m);
    status = s;
    code = c;
  }

  public static ApiException notFound() {
    return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "记录不存在");
  }

  public static ApiException conflict(String m) {
    return new ApiException(HttpStatus.CONFLICT, "STATE_CONFLICT", m);
  }
}
