package org.icu.sepsis.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import org.icu.sepsis.api.ApiException;
import org.icu.sepsis.application.Store;
import org.icu.sepsis.config.RuleConfig;
import org.icu.sepsis.domain.ClinicalRules;
import org.icu.sepsis.domain.Domain.*;
import org.icu.sepsis.integration.*;
import org.icu.sepsis.sepsis.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Mock OpenAI-compatible gateway; no real model or hospital network involved. */
class AiAssistServiceTest {
  static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
  HttpServer server;
  final List<JsonNode> requests = new CopyOnWriteArrayList<>();
  final List<String> auth = new CopyOnWriteArrayList<>();
  /** model name -> handler(request) returning [status, body]; a null result simulates a hang. */
  final Map<String, Function<JsonNode, Object[]>> models = new ConcurrentHashMap<>();
  volatile Function<JsonNode, Object[]> embeddings = r -> new Object[] {404, "{}"};
  final MongoTemplate db = mock(MongoTemplate.class);
  final Store store = new Store(db, "TEST", JSON);
  final ClinicalDataProvider provider = mock(ClinicalDataProvider.class);
  final Icu05Service icu05 = mock(Icu05Service.class);
  Instant now;

  @BeforeEach void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext("/v1/chat/completions", ex -> {
      JsonNode req = JSON.readTree(ex.getRequestBody());
      requests.add(req); auth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
      Object[] r = models.getOrDefault(req.path("model").asText(), q -> new Object[] {404, "{}"}).apply(req);
      if (r == null) { try { Thread.sleep(3000); } catch (InterruptedException ignored) { } r = new Object[] {200, "{}"}; }
      byte[] b = ((String) r[1]).getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders((Integer) r[0], b.length); ex.getResponseBody().write(b); ex.close();
    });
    server.createContext("/v1/embeddings", ex -> {
      JsonNode req = JSON.readTree(ex.getRequestBody());
      Object[] r = embeddings.apply(req);
      byte[] b = ((String) r[1]).getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders((Integer) r[0], b.length); ex.getResponseBody().write(b); ex.close();
    });
    server.start();
    now = Instant.now();
    Patient p = patient();
    when(db.findById(eq(p.id), eq(Patient.class))).thenReturn(p);
    Observation map = obs("MAP_IBP", 58, "mmHg", now.minus(Duration.ofMinutes(12)));
    Observation lac = obs("LACTATE", 4.6, "mmol/L", now.minus(Duration.ofMinutes(30)));
    when(provider.observations(anyString(), anyString(), any(), any())).thenReturn(List.of(map, lac));
    when(icu05.evaluate(any(), any())).thenAnswer(inv -> icu05Result(inv.getArgument(1)));
  }
  @AfterEach void stop() { server.stop(0); }

  String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
  static AiProperties props(boolean enabled, String url, String fast, String reasoning, String embedding, int timeoutMs, int maxConcurrent) {
    return new AiProperties(enabled, url, "test-key-not-real", "/v1/chat/completions", "/v1/embeddings", fast, reasoning, embedding,
        1000, timeoutMs, timeoutMs, 800, maxConcurrent, 0.2, true, false, "", true, "", "Asia/Shanghai");
  }
  AiAssistService service(AiProperties p) {
    LlmClient llm = new LlmClient(p, JSON);
    KnowledgeBase kb = new KnowledgeBase(p, llm, JSON);
    RuleConfig cfg = new RuleConfig();
    @SuppressWarnings("unchecked") ObjectProvider<SmartCareSourceReader> reader = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked") ObjectProvider<AiProperties> ap = mock(ObjectProvider.class);
    SepsisAssessmentService sepsis = new SepsisAssessmentService(provider, new ClinicalRules(cfg), cfg, reader, "demo", ap);
    return new AiAssistService(p, llm, kb, new PatientContextBuilder(p), sepsis, icu05, store, JSON);
  }
  static Patient patient() {
    Patient p = new Patient(); p.id = "pat-7781234567"; p.patientId = p.id; p.encounterId = "enc-99887766";
    p.department = "TEST"; p.label = "张三"; p.bed = "12床"; p.simulated = true; return p;
  }
  static Observation obs(String code, double v, String unit, Instant t) {
    Observation o = new Observation(); o.parameterCode = code; o.value = v; o.unit = unit; o.eventTime = t; o.ingestTime = t;
    o.qualityStatus = "valid"; o.sourceSystem = "SmartCare"; o.sourceRecordId = "rec-55512345678";
    o.measurementChannel = code.endsWith("IBP") ? "IBP" : null;
    return o;
  }
  static Map<String, Object> icu05Result(Instant asOf) {
    Icu05Rules.Evidence e = new Icu05Rules.Evidence();
    Instant t0 = asOf.minus(Duration.ofMinutes(90));
    e.asOf = asOf; e.t0 = t0; e.t0OrderName = "一级护理"; e.icuAdmission = t0.minusSeconds(600); e.hospitalAdmission = t0.minusSeconds(7200);
    e.admissionType = "入院"; e.diagnosisText = "肺部感染";
    e.lactate.add(new Icu05Rules.Point(t0.plusSeconds(1200), 4.6, null, "l1"));
    e.map.add(new Icu05Rules.Point(t0.plusSeconds(600), 58, "IBP", "m1"));
    Icu05Rules.Drug d = new Icu05Rules.Drug(); d.start = t0.plusSeconds(2100); d.items.add(new Icu05Rules.DrugItem("*去甲肾上腺素注射液", 50.0));
    d.actions.add(new Icu05Rules.Action(t0.plusSeconds(2200), "start")); e.drugs.add(d);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("schemaVersion", 1); m.put("available", true); m.put("message", "");
    m.putAll(Icu05Rules.evaluate(e));
    return m;
  }
  static Object[] ok(String content) {
    try {
      Map<String, Object> body = Map.of("model", "served-model",
          "choices", List.of(Map.of("message", Map.of("role", "assistant", "content", content), "finish_reason", "stop")),
          "usage", Map.of("prompt_tokens", 900, "completion_tokens", 120));
      return new Object[] {200, JSON.writeValueAsString(body)};
    } catch (Exception e) { throw new IllegalStateException(e); }
  }
  static final String GOOD = "<think>内部推理不应展示</think>{\"summary\":[{\"text\":\"有创 MAP 58 mmHg，低于 65 目标\",\"refs\":[\"E2\",\"K3\"]},"
      + "{\"text\":\"凭空编号\",\"refs\":[\"E99\"]},{\"text\":\"心率 140 次/分\",\"refs\":[\"E2\"]}],"
      + "\"gaps\":[{\"text\":\"未见血培养与抗菌药执行记录\",\"refs\":[\"E1\"]}],"
      + "\"suggestions\":[{\"text\":\"建议核对去甲肾上腺素 0.1 μg/kg/min\",\"refs\":[\"E2\"],\"urgency\":\"high\"},"
      + "{\"text\":\"建议核对血培养是否已在抗菌药前留取\",\"refs\":[\"K1\"],\"urgency\":\"urgent!!\"}],\"uncertainties\":[{\"text\":\"乳酸采样时间未确认\",\"refs\":[]}]}";

  @Test void validatedAnswerKeepsOnlyReferencedSafeItems() {
    models.put("fast-m", r -> ok(GOOD));
    Map<String, Object> r = service(props(true, url(), "fast-m", "reason-m", "", 2000, 4)).brief(patient(), "summary", "doctor");
    assertEquals("ok", r.get("status"));
    List<?> summary = (List<?>) r.get("summary");
    assertEquals(1, summary.size(), "invalid ref E99 and invented heart rate 140 are dropped");
    assertTrue(summary.get(0).toString().contains("58"));
    List<?> sug = (List<?>) r.get("suggestions");
    assertEquals(1, sug.size(), "dose instruction dropped");
    assertEquals("medium", ((Map<?, ?>) sug.get(0)).get("urgency"));
    assertEquals(3, r.get("droppedCount"));
    assertEquals(1, ((List<?>) r.get("uncertainties")).size(), "uncertainties may have no refs");
    assertFalse(r.toString().contains("内部推理"));
    assertEquals("Bearer test-key-not-real", auth.get(0));
    JsonNode req = requests.get(0);
    assertEquals("json_object", req.path("response_format").path("type").asText());
    assertEquals(false, r.get("fallbackUsed"));
    assertEquals("pat-7781234567", r.get("patientId"));
    verify(db).insert(any(AiInteraction.class));
  }

  @Test void promptIsDeIdentified() {
    models.put("fast-m", r -> ok(GOOD));
    service(props(true, url(), "fast-m", "", "", 2000, 4)).brief(patient(), "icu05", "doctor");
    String prompt = requests.get(0).path("messages").toString();
    for (String secret : List.of("张三", "12床", "pat-7781234567", "enc-99887766", "rec-55512345678", "7781234567", "55512345678"))
      assertFalse(prompt.contains(secret), secret);
    assertTrue(prompt.contains("E1"));
    assertTrue(prompt.contains("K1"));
    assertTrue(prompt.contains("ICU-05"));
    assertTrue(prompt.contains("1h 时窗"));
  }

  @Test void summaryKindKeepsOnlyWindowStates() {
    Map<String, Object> s = service(props(false, url(), "", "", "", 2000, 4)).brief(patient(), "summary", "doctor");
    Map<String, Object> d = service(props(false, url(), "", "", "", 2000, 4)).brief(patient(), "icu05", "doctor");
    assertTrue(((List<?>) s.get("facts")).size() < ((List<?>) d.get("facts")).size());
    assertFalse(s.get("facts").toString().contains("ICU-05 门控"));
  }

  @Test void fallsBackToSecondModelOnServerError() {
    models.put("reason-m", r -> new Object[] {500, "{\"error\":\"boom\"}"});
    models.put("fast-m", r -> ok(GOOD));
    Map<String, Object> r = service(props(true, url(), "fast-m", "reason-m", "", 2000, 4)).brief(patient(), "icu05", "doctor");
    assertEquals("ok", r.get("status"));
    assertEquals(true, r.get("fallbackUsed"));
    assertEquals("fast", r.get("modelRole"));
    assertEquals(List.of("reason-m", "fast-m"), requests.stream().map(q -> q.path("model").asText()).collect(java.util.stream.Collectors.toList()));
  }

  @Test void timeoutBecomesUnavailableNotAnError() {
    models.put("fast-m", r -> null);
    Map<String, Object> r = service(props(true, url(), "fast-m", "", "", 300, 4)).brief(patient(), "summary", "doctor");
    assertEquals("unavailable", r.get("status"));
    assertTrue(r.get("message").toString().contains("timeout"));
    assertTrue(((List<?>) r.get("summary")).isEmpty());
    assertFalse(((List<?>) r.get("facts")).isEmpty(), "facts still shown for manual review");
  }

  @Test void invalidJsonFromAllModelsIsNotShown() {
    models.put("fast-m", r -> ok("抱歉，我无法输出 JSON"));
    models.put("reason-m", r -> ok("{\"summary\":[{\"text\":\"没有引用\"}]}"));
    Map<String, Object> r = service(props(true, url(), "fast-m", "reason-m", "", 2000, 4)).brief(patient(), "summary", "doctor");
    assertEquals("invalid_output", r.get("status"));
    assertEquals(2, requests.size());
  }

  @Test void retriesWithoutJsonModeWhenGatewayRejectsIt() {
    models.put("fast-m", r -> r.has("response_format") ? new Object[] {400, "{\"error\":\"response_format unsupported\"}"} : ok(GOOD));
    Map<String, Object> r = service(props(true, url(), "fast-m", "", "", 2000, 4)).brief(patient(), "summary", "doctor");
    assertEquals("ok", r.get("status"));
    assertEquals(2, requests.size());
  }

  @Test void disabledStillReturnsFactsAndKnowledgeWithoutCallingModel() {
    Map<String, Object> r = service(props(false, url(), "fast-m", "", "", 2000, 4)).brief(patient(), "icu05", "nurse");
    assertEquals("disabled", r.get("status"));
    assertTrue(requests.isEmpty());
    List<?> k = (List<?>) r.get("knowledge");
    assertFalse(k.isEmpty());
    assertTrue(k.size() <= AiAssistService.MAX_KNOWLEDGE);
    assertEquals("icu05-windows", ((Map<?, ?>) k.get(0)).get("id"));
    assertEquals("K1", ((Map<?, ?>) k.get(0)).get("ref"));
    assertEquals(false, r.get("clinicalUseAllowed"));
  }

  @Test void busyWhenConcurrencyExhausted() throws Exception {
    CountDownLatch inside = new CountDownLatch(1), release = new CountDownLatch(1);
    models.put("fast-m", r -> { inside.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { } return ok(GOOD); });
    AiAssistService svc = service(props(true, url(), "fast-m", "", "", 5000, 1));
    Future<Map<String, Object>> first = Executors.newSingleThreadExecutor().submit(() -> svc.brief(patient(), "summary", "doctor"));
    assertTrue(inside.await(5, TimeUnit.SECONDS));
    assertEquals("busy", svc.brief(patient(), "summary", "doctor").get("status"));
    release.countDown();
    assertEquals("ok", first.get(5, TimeUnit.SECONDS).get("status"));
  }

  @Test void feedbackRequiresReasonForRejection() {
    AiAssistService svc = service(props(false, url(), "", "", "", 2000, 4));
    AiInteraction rec = new AiInteraction(); rec.id = "ai-1"; rec.patientId = "pat-7781234567"; rec.encounterId = "enc-99887766"; rec.department = "TEST";
    when(db.findById(eq("ai-1"), eq(AiInteraction.class))).thenReturn(rec);
    ApiException e = assertThrows(ApiException.class, () -> svc.feedback("ai-1", "reject", " ", null, "doctor"));
    assertEquals("REASON_REQUIRED", e.code);
    Map<String, Object> ok = svc.feedback("ai-1", "accept", null, null, "doctor");
    assertEquals(1, ok.get("count"));
    verify(db).updateFirst(any(), any(), eq(AiInteraction.class));
    AiInteraction other = new AiInteraction(); other.id = "ai-2"; other.department = "OTHER";
    when(db.findById(eq("ai-2"), eq(AiInteraction.class))).thenReturn(other);
    assertThrows(ApiException.class, () -> svc.feedback("ai-2", "accept", null, null, "doctor"));
  }

  @Test void icu05SourceFailureIsStatedNotThrown() {
    doThrow(new IllegalStateException("source down")).when(icu05).evaluate(any(), any());
    Map<String, Object> r = service(props(false, url(), "", "", "", 2000, 4)).brief(patient(), "icu05", "doctor");
    assertEquals("disabled", r.get("status"));
    assertTrue(r.get("facts").toString().contains("ICU-05 来源读取失败"));
  }

  @Test void rejectsUnknownKind() {
    assertThrows(ApiException.class, () -> service(props(false, url(), "", "", "", 2000, 4)).brief(patient(), "orders", "doctor"));
  }

  @Test void statusNeverExposesKey() throws Exception {
    Map<String, Object> s = service(props(true, url(), "fast-m", "reason-m", "", 2000, 4)).status();
    assertEquals(true, s.get("configured"));
    assertEquals("127.0.0.1", s.get("gatewayHost"));
    assertFalse(JSON.writeValueAsString(s).contains("test-key-not-real"));
  }

  @Test void embeddingHybridAndLexicalFallback() {
    embeddings = r -> {
      List<Map<String, Object>> data = new ArrayList<>();
      int i = 0;
      for (JsonNode t : r.path("input")) {
        String s = t.asText();
        data.add(Map.of("index", i++, "embedding", List.of(s.contains("血培养") ? 1.0 : 0.0, s.contains("去甲肾上腺素") ? 1.0 : 0.0, 0.1)));
      }
      try { return new Object[] {200, JSON.writeValueAsString(Map.of("data", data))}; } catch (Exception e) { throw new IllegalStateException(e); }
    };
    AiProperties p = props(true, url(), "fast-m", "", "emb-m", 2000, 4);
    KnowledgeBase kb = new KnowledgeBase(p, new LlmClient(p, JSON), JSON);
    assertEquals("pending", kb.vectorStatus());
    kb.buildVectors();
    assertEquals("ready", kb.vectorStatus());
    assertTrue(kb.search("血培养 何时留取", 5).stream().anyMatch(h -> h.chunk.id.equals("ssc26-7")));
    embeddings = r -> new Object[] {500, "{}"};
    AiProperties p2 = props(true, url(), "fast-m", "", "emb-m", 2000, 4);
    KnowledgeBase kb2 = new KnowledgeBase(p2, new LlmClient(p2, JSON), JSON);
    kb2.buildVectors();
    assertEquals("failed", kb2.vectorStatus());
    assertFalse(kb2.search("去甲肾上腺素 一线", 3).isEmpty(), "lexical retrieval keeps working");
  }
}
