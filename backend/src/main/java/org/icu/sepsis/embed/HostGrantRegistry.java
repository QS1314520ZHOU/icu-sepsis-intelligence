package org.icu.sepsis.embed;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.icu.sepsis.api.ApiException;
import org.icu.sepsis.api.Views;
import org.icu.sepsis.domain.Domain.Patient;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

/** Host SmartCare patient-context grants for iframe embed (LAN / production host protocol). */
@Component
public class HostGrantRegistry {
  private final Map<String, EmbedAccess.Grant> tokens = new ConcurrentHashMap<>();
  private final java.security.SecureRandom random = new java.security.SecureRandom();

  public synchronized Map<String, Object> issue(String parentOrigin, Patient patient) {
    if (parentOrigin == null || parentOrigin.isBlank())
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARENT_ORIGIN", "缺少宿主来源");
    if (patient == null || patient.id == null)
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATIENT", "患者上下文无效");
    purge();
    if (tokens.size() >= 500)
      throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "HOST_GRANT_CAPACITY", "宿主授权容量已满");
    EmbedAccess.Grant g = new EmbedAccess.Grant();
    g.username = "host";
    g.authorities = new ArrayList<>(List.of(new SimpleGrantedAuthority("ROLE_DOCTOR")));
    g.patientId = patient.id;
    g.encounterId = patient.encounterId;
    g.department = patient.department;
    g.parentOrigin = parentOrigin;
    g.channel = key();
    g.expiresAt = Instant.now().plusSeconds(600);
    String token = key();
    tokens.put(token, g);
    return Views.map(
        "accessToken",
        token,
        "expiresAt",
        g.expiresAt,
        "username",
        g.username,
        "roles",
        g.authorities.stream().map(Object::toString).toArray(),
        "patientId",
        g.patientId,
        "encounterId",
        g.encounterId,
        "department",
        g.department);
  }

  public EmbedAccess.Grant lookup(String token) {
    if (token == null) return null;
    EmbedAccess.Grant g = tokens.get(token);
    if (g != null && !g.expiresAt.isAfter(Instant.now())) {
      tokens.remove(token);
      return null;
    }
    return g;
  }

  public void revoke(EmbedAccess.Grant grant) {
    if (grant != null) tokens.entrySet().removeIf(e -> e.getValue() == grant);
  }

  private void purge() {
    Instant now = Instant.now();
    tokens.entrySet().removeIf(e -> !e.getValue().expiresAt.isAfter(now));
  }

  private String key() {
    byte[] b = new byte[32];
    random.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }
}
