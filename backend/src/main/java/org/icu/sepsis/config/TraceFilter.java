package org.icu.sepsis.config;

import java.io.IOException;
import java.util.UUID;
import javax.servlet.*;
import javax.servlet.http.*;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(-200)
public class TraceFilter extends OncePerRequestFilter {
  protected void doFilterInternal(HttpServletRequest r, HttpServletResponse s, FilterChain c)
      throws ServletException, IOException {
    String id = UUID.randomUUID().toString();
    MDC.put("traceId", id);
    s.setHeader("X-Trace-Id", id);
    s.setHeader("Cache-Control", "no-store");
    try {
      c.doFilter(r, s);
    } finally {
      MDC.remove("traceId");
    }
  }
}
