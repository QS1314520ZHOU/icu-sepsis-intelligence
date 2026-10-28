package org.icu.sepsis.embed;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import javax.servlet.*;
import javax.servlet.http.*;
import org.icu.sepsis.api.Envelope;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class EmbedBearerFilter extends OncePerRequestFilter {
  private final EmbedAccess access;
  private final HostGrantRegistry hostGrants;
  private final ObjectMapper json;

  public EmbedBearerFilter(EmbedAccess a, HostGrantRegistry h, ObjectMapper j) {
    access = a;
    hostGrants = h;
    json = j;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String auth = req.getHeader("Authorization");
    if (auth != null && auth.startsWith("Bearer ")) {
      String token = auth.substring(7);
      EmbedAccess.Grant grant = access.lookup(token);
      if (grant == null) grant = hostGrants.lookup(token);
      if (grant == null) {
        res.setStatus(401);
        res.setContentType("application/json;charset=UTF-8");
        json.writeValue(
            res.getOutputStream(), new Envelope<>("EMBED_TOKEN_EXPIRED", "嵌入授权失效，请宿主重新授权", null));
        return;
      }
      UsernamePasswordAuthenticationToken a =
          new UsernamePasswordAuthenticationToken(grant.username, null, grant.authorities);
      a.setDetails(grant);
      SecurityContextHolder.getContext().setAuthentication(a);
    }
    chain.doFilter(req, res);
  }
}
