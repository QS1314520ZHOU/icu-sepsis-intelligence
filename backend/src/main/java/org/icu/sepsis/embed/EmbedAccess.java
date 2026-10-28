package org.icu.sepsis.embed;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.icu.sepsis.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Isolated demo issuer. Replace with a reviewed host-SSO exchange before production. */
@Component
public class EmbedAccess {
  public static class Grant {
    public String username, patientId, encounterId, department, parentOrigin, channel;
    public Collection<? extends GrantedAuthority> authorities;
    public Instant expiresAt;
  }

  private static class Ticket {
    Grant grant;
    Instant expiresAt;
  }

  private final Map<String, Ticket> codes = new ConcurrentHashMap<>();
  private final Map<String, Grant> tokens = new ConcurrentHashMap<>();
  private final SecureRandom random = new SecureRandom();
  public final Set<String> origins = new LinkedHashSet<>();
  public final boolean demo;
  public final boolean allowAnyFrameAncestor;
  public final boolean allowInsecureLanOrigins;

  public EmbedAccess(
      @Value("${app.demo}") boolean d,
      @Value("${app.embed-parent-origins:}") String configured,
      @Value("${app.embed.allow-any-frame-ancestor:false}") boolean anyFrame,
      @Value("${app.embed.allow-insecure-lan-origins:true}") boolean insecureLan) {
    demo = d;
    allowAnyFrameAncestor = anyFrame;
    allowInsecureLanOrigins = insecureLan;
    String list =
        configured.isBlank() && demo ? "http://127.0.0.1:8080,http://localhost:8080" : configured;
    for (String item : list.split(","))
      if (!item.isBlank()) {
        String origin = item.trim();
        URI u = URI.create(origin);
        boolean local =
            (demo || allowInsecureLanOrigins)
                && Set.of("127.0.0.1", "localhost").contains(u.getHost())
                && "http".equals(u.getScheme());
        boolean lanHttp = allowInsecureLanOrigins && isPrivateHttpOrigin(u);
        if (u.getHost() == null
            || u.getUserInfo() != null
            || u.getRawQuery() != null
            || u.getRawFragment() != null
            || !origin.equals(u.getScheme() + "://" + u.getRawAuthority())
            || !("https".equals(u.getScheme()) || local || lanHttp))
          throw new IllegalArgumentException(
              "Embed origins must be exact HTTPS origins; loopback/LAN HTTP is only for controlled demos");
        origins.add(origin);
      }
  }

  public String framePolicy() {
    StringBuilder b = new StringBuilder("frame-ancestors 'self'");
    for (String origin : origins) b.append(' ').append(origin);
    if (origins.isEmpty() || allowAnyFrameAncestor) {
      // LAN SmartCare hosts often load the iframe from HTTP private addresses.
      // frame-ancestors cannot express CIDR; operators must set exact origins for production.
      if (allowAnyFrameAncestor) return "frame-ancestors *";
    }
    return b.toString();
  }

  private String randomKey() {
    byte[] b = new byte[32];
    random.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  private void purge() {
    Instant now = Instant.now();
    codes.entrySet().removeIf(e -> !e.getValue().expiresAt.isAfter(now));
    tokens.entrySet().removeIf(e -> !e.getValue().expiresAt.isAfter(now));
  }

  public synchronized String issue(
      Authentication a,
      String origin,
      String channel,
      String patient,
      String encounter,
      String department) {
    if (!demo)
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE, "HOST_SSO_NOT_CONNECTED", "正式宿主 SSO 票据适配未接入；演示签发器不在生产启用");
    if (current() != null || a == null || !a.isAuthenticated())
      throw new ApiException(HttpStatus.FORBIDDEN, "ISSUER_DENIED", "嵌入令牌不可再次签发宿主票据");
    if (!origins.contains(origin) || channel == null || !channel.matches("[A-Za-z0-9_-]{16,100}"))
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EMBED_CONTEXT", "宿主来源或通道无效");
    purge();
    if (codes.size() + tokens.size() >= 1000)
      throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "EMBED_CAPACITY", "演示票据容量已满");
    Grant g = new Grant();
    g.username = a.getName();
    g.authorities = new ArrayList<>(a.getAuthorities());
    g.patientId = patient;
    g.encounterId = encounter;
    g.department = department;
    g.parentOrigin = origin;
    g.channel = channel;
    Ticket t = new Ticket();
    t.grant = g;
    t.expiresAt = Instant.now().plusSeconds(45);
    String code = randomKey();
    codes.put(code, t);
    return code;
  }

  public synchronized Map<String, Object> exchange(String code, String origin, String channel) {
    purge();
    Ticket t = code == null ? null : codes.remove(code);
    if (t == null
        || !t.expiresAt.isAfter(Instant.now())
        || !t.grant.parentOrigin.equals(origin)
        || !t.grant.channel.equals(channel))
      throw new ApiException(HttpStatus.UNAUTHORIZED, "EMBED_TICKET_INVALID", "票据无效、过期、已使用或与通道不匹配");
    Grant g = t.grant;
    g.expiresAt = Instant.now().plusSeconds(600);
    tokens
        .entrySet()
        .removeIf(
            e -> e.getValue().parentOrigin.equals(origin) && e.getValue().channel.equals(channel));
    String token = randomKey();
    tokens.put(token, g);
    return org.icu.sepsis.api.Views.map(
        "accessToken",
        token,
        "expiresAt",
        g.expiresAt,
        "username",
        g.username,
        "roles",
        g.authorities.stream().map(GrantedAuthority::getAuthority).toArray(),
        "patientId",
        g.patientId,
        "encounterId",
        g.encounterId,
        "department",
        g.department);
  }

  public Grant lookup(String token) {
    Grant g = tokens.get(token);
    if (g != null && !g.expiresAt.isAfter(Instant.now())) {
      tokens.remove(token);
      return null;
    }
    return g;
  }

  public void revoke(Grant grant) {
    if (grant != null) tokens.entrySet().removeIf(e -> e.getValue() == grant);
  }

  public static Grant current() {
    Authentication a = SecurityContextHolder.getContext().getAuthentication();
    return a != null && a.getDetails() instanceof Grant ? (Grant) a.getDetails() : null;
  }

  private static boolean isPrivateHttpOrigin(URI u) {
    if (u.getHost() == null || !"http".equals(u.getScheme())) return false;
    String host = u.getHost();
    if (host.startsWith("192.168.") || host.startsWith("10.")) return true;
    if (host.startsWith("172.")) {
      String[] parts = host.split("\\.");
      if (parts.length == 4) {
        try {
          int second = Integer.parseInt(parts[1]);
          return second >= 16 && second <= 31;
        } catch (NumberFormatException e) {
          return false;
        }
      }
    }
    return false;
  }
}
