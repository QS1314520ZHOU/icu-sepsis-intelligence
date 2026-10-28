package org.icu.sepsis.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.icu.sepsis.api.Envelope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class SecurityConfig {
  @Bean
  UserDetailsService users(
      @Value("${app.doctor-password}") String d,
      @Value("${app.nurse-password}") String n,
      @Value("${app.quality-password}") String q) {
    if (d.length() < 16 || n.length() < 16 || q.length() < 16)
      throw new IllegalStateException("Set three distinct passwords with at least 16 characters");
    if (d.equals(n) || d.equals(q) || n.equals(q))
      throw new IllegalStateException("Role passwords must differ");
    BCryptPasswordEncoder enc = new BCryptPasswordEncoder();
    return new InMemoryUserDetailsManager(
        User.withUsername("doctor").password("{bcrypt}" + enc.encode(d)).roles("DOCTOR").build(),
        User.withUsername("nurse").password("{bcrypt}" + enc.encode(n)).roles("NURSE").build(),
        User.withUsername("quality").password("{bcrypt}" + enc.encode(q)).roles("QUALITY").build());
  }

  @Bean
  SecurityFilterChain filter(
      HttpSecurity h,
      ObjectMapper mapper,
      org.icu.sepsis.embed.EmbedAccess embed,
      org.icu.sepsis.embed.HostGrantRegistry hostGrants)
      throws Exception {
    h.headers().frameOptions().disable().contentSecurityPolicy(embed.framePolicy());
    h.addFilterBefore(
        new org.icu.sepsis.embed.EmbedBearerFilter(embed, hostGrants, mapper),
        org.springframework.security.web.authentication.www.BasicAuthenticationFilter.class);
    h.csrf()
        .disable()
        .sessionManagement()
        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
        .and()
        .authorizeRequests()
        .antMatchers("/api/**", "/v3/**", "/swagger-ui/**", "/api-docs/**", "/api-docs")
        .authenticated()
        .anyRequest()
        .permitAll()
        .and()
        .httpBasic();
    h.exceptionHandling()
        .authenticationEntryPoint(
            (req, res, e) -> {
              res.setStatus(401);
              res.setContentType("application/json;charset=UTF-8");
              mapper.writeValue(res.getOutputStream(), new Envelope<>("UNAUTHORIZED", "请登录", null));
            })
        .accessDeniedHandler(
            (req, res, e) -> {
              res.setStatus(403);
              res.setContentType("application/json;charset=UTF-8");
              mapper.writeValue(res.getOutputStream(), new Envelope<>("FORBIDDEN", "无权限", null));
            });
    return h.build();
  }
}
