package org.icu.sepsis;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import java.time.Instant;
import java.util.*;
import org.icu.sepsis.embed.EmbedAccess;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "app.demo=true",
      "app.source.mode=demo",
      "app.doctor-password=test-doctor-123456789",
      "app.nurse-password=test-nurse-123456789",
      "app.quality-password=test-quality-123456789",
      "spring.data.mongodb.uri=mongodb://127.0.0.1:27027/icu_sepsis_test?replicaSet=rs0"
    })
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "RUN_MONGO_TESTS", matches = "true")
class EmbedIntegrationTest {
  @Autowired MockMvc http;
  @Autowired ObjectMapper json;
  @Autowired EmbedAccess access;

  String channel() {
    return UUID.randomUUID().toString();
  }

  String issue(String channel, String patient) throws Exception {
    return json.readTree(
            http.perform(
                    post("/api/v1/embed-codes")
                        .with(user("doctor").roles("DOCTOR"))
                        .contentType("application/json")
                        .content(
                            json.writeValueAsString(
                                Map.of(
                                    "parentOrigin",
                                    "http://localhost:8080",
                                    "channel",
                                    channel,
                                    "patientId",
                                    patient,
                                    "encounterId",
                                    patient + "-enc-001"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .path("data")
        .path("code")
        .asText();
  }

  String body(String code, String channel) throws Exception {
    return json.writeValueAsString(
        Map.of("parentOrigin", "http://localhost:8080", "channel", channel, "code", code));
  }

  String token(String c, String p) throws Exception {
    String code = issue(c, p);
    return json.readTree(
            http.perform(
                    post("/embed-api/exchange")
                        .contentType("application/json")
                        .content(body(code, c)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .path("data")
        .path("accessToken")
        .asText();
  }

  @Test
  void ticketRequiresAuthenticatedHost() throws Exception {
    http.perform(post("/api/v1/embed-codes").contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void codeIsSingleUseAndBoundToChannel() throws Exception {
    String c = channel(), code = issue(c, "demo-normal"), body = body(code, c);
    http.perform(post("/embed-api/exchange").contentType("application/json").content(body))
        .andExpect(status().isOk());
    http.perform(post("/embed-api/exchange").contentType("application/json").content(body))
        .andExpect(status().isUnauthorized());
    String bad = issue(c, "demo-normal");
    http.perform(
            post("/embed-api/exchange")
                .contentType("application/json")
                .content(body(bad, channel())))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void scopedTokenCannotReadAnotherPatientOrListOtherPatients() throws Exception {
    String t = token(channel(), "demo-normal");
    http.perform(get("/api/v1/patients/demo-normal").header("Authorization", "Bearer " + t))
        .andExpect(status().isOk());
    http.perform(get("/api/v1/patients/demo-deteriorating").header("Authorization", "Bearer " + t))
        .andExpect(status().isForbidden());
    http.perform(
            get("/api/v1/patients").param("search", "模拟患者").header("Authorization", "Bearer " + t))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.items[0].id").value("demo-normal"));
  }

  @Test
  void newContextRevokesOldTokenInSameChannel() throws Exception {
    String c = channel(), old = token(c, "demo-normal"), next = token(c, "demo-deteriorating");
    http.perform(get("/api/v1/me").header("Authorization", "Bearer " + old))
        .andExpect(status().isUnauthorized());
    http.perform(
            get("/api/v1/patients/demo-deteriorating").header("Authorization", "Bearer " + next))
        .andExpect(status().isOk());
  }

  @Test
  void encounterMismatchAndUnlistedOriginAreRejected() throws Exception {
    for (String origin : List.of("http://localhost:8080", "https://evil.example.test")) {
      http.perform(
              post("/api/v1/embed-codes")
                  .with(user("doctor").roles("DOCTOR"))
                  .contentType("application/json")
                  .content(
                      json.writeValueAsString(
                          Map.of(
                              "parentOrigin",
                              origin,
                              "channel",
                              channel(),
                              "patientId",
                              "demo-normal",
                              "encounterId",
                              origin.contains("evil")
                                  ? "demo-normal-enc-001"
                                  : "wrong-encounter"))))
          .andExpect(status().is(origin.contains("evil") ? 400 : 409));
    }
  }

  @Test
  void expiredOrRevokedTokenFailsClosed() throws Exception {
    String t = token(channel(), "demo-normal");
    access.lookup(t).expiresAt = Instant.now().minusSeconds(1);
    http.perform(get("/api/v1/me").header("Authorization", "Bearer " + t))
        .andExpect(status().isUnauthorized());
    String next = token(channel(), "demo-normal");
    http.perform(post("/embed-api/revoke").header("Authorization", "Bearer " + next))
        .andExpect(status().isOk());
    http.perform(get("/api/v1/me").header("Authorization", "Bearer " + next))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void scopedNurseCannotElevateByEmbedding() throws Exception {
    String c = channel();
    String code =
        json.readTree(
                http.perform(
                        post("/api/v1/embed-codes")
                            .with(user("nurse").roles("NURSE"))
                            .contentType("application/json")
                            .content(
                                json.writeValueAsString(
                                    Map.of(
                                        "parentOrigin",
                                        "http://localhost:8080",
                                        "channel",
                                        c,
                                        "patientId",
                                        "demo-normal",
                                        "encounterId",
                                        "demo-normal-enc-001"))))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .path("data")
            .path("code")
            .asText();
    String t =
        json.readTree(
                http.perform(
                        post("/embed-api/exchange")
                            .contentType("application/json")
                            .content(body(code, c)))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .path("data")
            .path("accessToken")
            .asText();
    http.perform(
            post("/api/v1/patients/demo-normal/risk-assessments")
                .header("Authorization", "Bearer " + t)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content("{\"asOf\":\"" + Instant.now() + "\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void embeddingUsesExplicitCspRatherThanGlobalFrameDeny() throws Exception {
    http.perform(get("/embed"))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("X-Frame-Options"))
        .andExpect(
            header()
                .string(
                    "Content-Security-Policy",
                    org.hamcrest.Matchers.containsString(
                        "frame-ancestors 'self' http://127.0.0.1:8080 http://localhost:8080")));
  }
}
