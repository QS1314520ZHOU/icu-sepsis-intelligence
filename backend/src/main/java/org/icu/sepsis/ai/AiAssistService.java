package org.icu.sepsis.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.regex.*;
import java.util.stream.Collectors;
import org.icu.sepsis.ai.KnowledgeBase.Hit;
import org.icu.sepsis.ai.PatientContextBuilder.Fact;
import org.icu.sepsis.api.ApiException;
import org.icu.sepsis.application.Store;
import org.icu.sepsis.domain.Domain.Patient;
import org.icu.sepsis.sepsis.Icu05Service;
import org.icu.sepsis.sepsis.SepsisAssessmentService;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Retrieval-augmented explanation of the rule-engine output. The model never decides status; it can only restate
 * numbered facts / knowledge items, and any statement without a valid reference is discarded before display.
 */
@Service
public class AiAssistService {
  public static final String PROMPT_VERSION = "ai-brief-v1";
  public static final String DISCLAIMER = "AI 生成，仅供核对参考，须由医生结合原始记录判断；不构成诊断或医嘱。";
  static final int MAX_KNOWLEDGE = 8;
  static final Pattern REF = Pattern.compile("^[EK]\\d{1,3}$");
  static final Pattern DOSE = Pattern.compile(
      "\\d+(\\.\\d+)?\\s*(mg|μg|µg|ug|mcg|g|iu|u|单位|毫克|微克|克)(\\s*/\\s*(kg|min|h|小时|分钟?|d|天))*(?![a-z])",
      Pattern.CASE_INSENSITIVE);
  static final Pattern NUMBER = Pattern.compile("\\d+(\\.\\d+)?");
  static final List<String> SECTIONS = List.of("summary", "gaps", "suggestions", "uncertainties");
  /** Fact keyword → retrieval query; keeps retrieval focused instead of dumping every fact into BM25. */
  static final Map<String, String> TOPICS = new LinkedHashMap<>();
  static {
    TOPICS.put("乳酸", "乳酸 测定 复测 指导复苏");
    TOPICS.put("抗菌药", "抗菌药物 1小时 立即给予");
    TOPICS.put("血培养", "血培养 抗菌药物之前 留取");
    TOPICS.put("补液", "补液 晶体液 30 mL/kg 低灌注");
    TOPICS.put("平均动脉压", "MAP 目标 65 升压药");
    TOPICS.put("MAP", "MAP 目标 65 升压药");
    TOPICS.put("升压药", "去甲肾上腺素 一线 升压药");
    TOPICS.put("qSOFA", "qSOFA 筛查 单一工具");
    TOPICS.put("未取得可确认的感染证据", "感染 鉴别诊断 非感染病因");
    TOPICS.put("通道", "有创 无创 血压监测 通道");
    TOPICS.put("报告时间", "乳酸 报告时间 检验 时间");
  }

  private final AiProperties props;
  private final LlmClient llm;
  private final KnowledgeBase kb;
  private final PatientContextBuilder context;
  private final SepsisAssessmentService sepsis;
  private final Icu05Service icu05;
  private final Store store;
  private final ObjectMapper json;
  private final Semaphore slots;

  public AiAssistService(AiProperties props, LlmClient llm, KnowledgeBase kb, PatientContextBuilder context,
      SepsisAssessmentService sepsis, Icu05Service icu05, Store store, ObjectMapper json) {
    this.props = props; this.llm = llm; this.kb = kb; this.context = context; this.sepsis = sepsis; this.icu05 = icu05;
    this.store = store; this.json = json; this.slots = new Semaphore(props.maxConcurrent);
  }

  public Map<String, Object> status() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("enabled", props.enabled);
    m.put("configured", props.chatConfigured());
    m.put("label", props.chatConfigured() ? "AI 辅助已配置" : props.enabled ? "AI 已开启但未配置模型地址" : "AI 辅助未开启");
    String host = null;
    try { host = props.baseUrl.isEmpty() ? null : URI.create(props.baseUrl).getHost(); } catch (RuntimeException ignored) { }
    m.put("gatewayHost", host);
    m.put("apiKeyConfigured", !props.apiKey.isEmpty());
    Map<String, Object> models = new LinkedHashMap<>();
    models.put("fast", blank(props.fastModel)); models.put("reasoning", blank(props.reasoningModel)); models.put("embedding", blank(props.embeddingModel));
    m.put("models", models);
    m.put("promptVersion", PROMPT_VERSION);
    Map<String, Object> k = new LinkedHashMap<>();
    k.put("chunks", kb.size()); k.put("documents", kb.documents());
    k.put("retrieval", "ready".equals(kb.vectorStatus()) ? "bm25+embedding" : "bm25"); k.put("embeddingStatus", kb.vectorStatus());
    m.put("knowledge", k);
    m.put("clinicalUseAllowed", false);
    m.put("disclaimer", DISCLAIMER);
    return m;
  }

  public List<Map<String, Object>> searchKnowledge(String q, int k) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Hit h : kb.search(q, Math.max(1, Math.min(k, 20)))) {
      Map<String, Object> v = h.chunk.view(); v.put("score", Math.round(h.score * 1000) / 1000.0); out.add(v);
    }
    return out;
  }

  public Map<String, Object> brief(Patient p, String kind, String actor) {
    if (!"summary".equals(kind) && !"icu05".equals(kind))
      throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_KIND", "kind 仅支持 summary / icu05");
    Instant now = Instant.now();
    Map<String, Object> review = sepsis.assess(p, now);
    Map<String, Object> bundle;
    try {
      bundle = icu05.evaluate(p, now);
    } catch (RuntimeException e) {
      // The explanation must never fail because one source is unreadable; the gap is stated as a fact instead.
      bundle = Map.of("available", false, "message", "ICU-05 来源读取失败，本次不纳入");
    }
    List<Fact> facts = context.build(review, bundle, now, "icu05".equals(kind));
    List<KnowledgeBase.Chunk> chunks = retrieve(kind, facts);
    Map<String, String> kref = new LinkedHashMap<>();
    for (int i = 0; i < chunks.size(); i++) kref.put("K" + (i + 1), chunks.get(i).id);

    AiInteraction rec = new AiInteraction();
    store.init(rec, p, now);
    rec.kind = kind; rec.actor = actor; rec.traceId = org.slf4j.MDC.get("traceId");
    rec.factIds = facts.stream().map(f -> f.id).collect(Collectors.toList());
    for (int i = 0; i < chunks.size(); i++) {
      KnowledgeBase.Chunk c = chunks.get(i);
      Map<String, Object> v = new LinkedHashMap<>();
      v.put("ref", "K" + (i + 1)); v.put("id", c.id); v.put("docId", c.docId); v.put("version", c.version);
      rec.knowledge.add(v);
    }
    List<LlmClient.Message> messages = messages(kind, facts, chunks);
    rec.promptHash = sha(messages.stream().map(m -> m.role + ":" + m.content).collect(Collectors.joining("\n")));
    if (props.storePrompt) rec.prompt = messages.stream().map(m -> Map.of("role", m.role, "content", m.content)).collect(Collectors.toList());

    Map<String, Object> result = null;
    List<Map<String, Object>> keptDropped = new ArrayList<>();
    if (!props.chatConfigured()) {
      rec.status = "disabled";
    } else if (!slots.tryAcquire()) {
      rec.status = "busy";
    } else {
      try {
        boolean first = true;
        for (String[] m : props.modelOrder("icu05".equals(kind) ? "reasoning" : "fast")) {
          Map<String, Object> attempt = new LinkedHashMap<>();
          attempt.put("model", m[1]); attempt.put("role", m[0]);
          rec.attempts.add(attempt);
          try {
            LlmClient.Reply r = call(m[1], messages, Integer.parseInt(m[2]));
            attempt.put("latencyMs", r.latencyMs); attempt.put("finishReason", r.finishReason);
            rec.rawResponse = clip(r.content, 20000);
            List<Map<String, Object>> dropped = new ArrayList<>();
            Map<String, Object> parsed = validate(r.content, facts, chunks, kref, dropped);
            for (Map<String, Object> d : dropped) {
              Map<String, Object> x = new LinkedHashMap<>(d); x.put("model", m[1]); rec.dropped.add(x);
            }
            if (parsed == null) {
              attempt.put("outcome", "invalid_output"); rec.status = "invalid_output"; rec.errorKind = "invalid_output"; first = false;
              continue;
            }
            attempt.put("outcome", "ok");
            keptDropped = dropped;
            result = parsed; rec.status = "ok"; rec.errorKind = null; rec.model = r.model; rec.modelRole = m[0]; rec.fallbackUsed = !first;
            rec.latencyMs = r.latencyMs; rec.promptTokens = r.promptTokens; rec.completionTokens = r.completionTokens;
            break;
          } catch (LlmClient.LlmException e) {
            attempt.put("outcome", e.kind);
            if (!"invalid_output".equals(rec.status)) { rec.status = "unavailable"; rec.errorKind = e.kind; }
            first = false;
          }
        }
        if (rec.status == null) { rec.status = "unavailable"; rec.errorKind = "no_model"; }
      } finally {
        slots.release();
      }
    }
    rec.result = result;
    store.db.insert(rec);

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("interactionId", rec.id); out.put("patientId", p.id); out.put("encounterId", p.encounterId);
    out.put("kind", kind); out.put("status", rec.status); out.put("message", message(rec, keptDropped.size()));
    out.put("model", rec.model); out.put("modelRole", rec.modelRole); out.put("fallbackUsed", rec.fallbackUsed);
    out.put("latencyMs", rec.latencyMs); out.put("generatedAt", now); out.put("promptVersion", PROMPT_VERSION);
    for (String section : SECTIONS) out.put(section, result == null ? List.of() : result.get(section));
    out.put("facts", facts.stream().map(Fact::view).collect(Collectors.toList()));
    List<Map<String, Object>> kv = new ArrayList<>();
    for (int i = 0; i < chunks.size(); i++) { Map<String, Object> v = chunks.get(i).view(); v.put("ref", "K" + (i + 1)); kv.add(v); }
    out.put("knowledge", kv);
    out.put("droppedCount", keptDropped.size());
    out.put("disclaimer", DISCLAIMER);
    out.put("clinicalUseAllowed", false);
    return out;
  }

  private LlmClient.Reply call(String model, List<LlmClient.Message> messages, int timeout) {
    try {
      return llm.chat(model, messages, timeout, props.jsonMode);
    } catch (LlmClient.LlmException e) {
      // Some OpenAI-compatible servers reject response_format; retry once in plain mode.
      if (props.jsonMode && "http_400".equals(e.kind)) return llm.chat(model, messages, timeout, false);
      throw e;
    }
  }

  private static String message(AiInteraction r, int dropped) {
    switch (r.status) {
      case "ok": return dropped == 0 ? "" : "已过滤 " + dropped + " 条缺少有效依据、数值不符或含具体剂量的内容";
      case "disabled": return "AI 辅助未配置，仅列出本次核对依据与相关知识条目。";
      case "busy": return "AI 服务繁忙，请稍后重试。";
      case "invalid_output": return "模型输出未通过格式与引用校验，未展示。";
      default: return "AI 服务暂不可用（" + (r.errorKind == null ? "unknown" : r.errorKind) + "），规则判定不受影响。";
    }
  }

  List<KnowledgeBase.Chunk> retrieve(String kind, List<Fact> facts) {
    LinkedHashMap<String, KnowledgeBase.Chunk> pick = new LinkedHashMap<>();
    if ("icu05".equals(kind))
      for (String id : List.of("icu05-windows", "icu05-tristate")) kb.get(id).ifPresent(c -> pick.put(c.id, c));
    // Only facts that describe a problem (abnormal, missing, unmet, unknown) drive retrieval.
    String problems = facts.stream()
        .filter(f -> "观测".equals(f.category) || f.text.contains("否") || f.text.contains("无法") || f.text.contains("未") || f.text.contains("缺"))
        .map(f -> f.text).collect(Collectors.joining(" "));
    List<String> queries = new ArrayList<>();
    for (Map.Entry<String, String> t : TOPICS.entrySet())
      if (problems.contains(t.getKey()) && !queries.contains(t.getValue())) queries.add(t.getValue());
    queries.add("icu05".equals(kind) ? "感染性休克 Bundle 时窗 无法判定" : "脓毒症 感染性休克 定义 乳酸 MAP");
    for (String q : queries) {
      for (Hit h : kb.search(q, 2)) {
        if (pick.size() >= MAX_KNOWLEDGE) break;
        pick.putIfAbsent(h.chunk.id, h.chunk);
      }
      if (pick.size() >= MAX_KNOWLEDGE) break;
    }
    return new ArrayList<>(pick.values());
  }

  List<LlmClient.Message> messages(String kind, List<Fact> facts, List<KnowledgeBase.Chunk> chunks) {
    String task = "icu05".equals(kind)
        ? "任务：解释本例 ICU-05 感染性休克 Bundle 的判定结果。逐个时窗说明哪些项目已满足、哪些未满足或无法判定及其原因，指出需要人工核对的原始记录，必要时引用指南说明该项目的意义。不得改写规则引擎给出的判定。"
        : "任务：为当班医生/护士整理当前病情要点：异常指标及其时间、ICU-05 判定状态、数据缺口，以及可考虑核对或评估的事项（引用指南）。";
    String system = String.join("\n",
        "你是 ICU 床旁临床信息整理助手，读者是执业医生和护士。只能依据用户消息中的【患者事实 E#】和【知识条目 K#】作答。",
        "规则：",
        "1. summary、gaps、suggestions 的每一条都必须在 refs 中引用至少一个给出的 E# 或 K# 编号；不得编造编号。",
        "2. 不做诊断结论，不下达医嘱，不写具体药物名称的剂量或速度；建议用“建议核对 / 可考虑评估 / 可与上级医师讨论”等措辞。",
        "3. 数据缺失、冲突或时间不明时写“无法判断”，放入 gaps 或 uncertainties；不得推测或补全数值。",
        "4. 规则判定（ICU-05 状态、时窗结果、qSOFA）以事实为准，不重新计算，不改写结论。“无法判定”不等于“未完成”。",
        "5. 引用数值时必须与事实原文一致。",
        "6. 简洁：summary ≤4 条，gaps ≤5 条，suggestions ≤4 条，uncertainties ≤3 条，每条不超过 60 个汉字。",
        "7. 只输出一个 JSON 对象，不要输出其它文字：",
        "{\"summary\":[{\"text\":\"\",\"refs\":[\"E1\"]}],\"gaps\":[{\"text\":\"\",\"refs\":[\"E2\"]}],\"suggestions\":[{\"text\":\"\",\"refs\":[\"K1\",\"E3\"],\"urgency\":\"high|medium|low\"}],\"uncertainties\":[{\"text\":\"\",\"refs\":[]}]}");
    StringBuilder u = new StringBuilder(task).append("\n\n【患者事实】（已去标识化，时间为本地时间）\n");
    for (Fact f : facts) u.append(f.id).append(" [").append(f.category).append("] ").append(f.text).append('\n');
    u.append("\n【知识条目】\n");
    for (int i = 0; i < chunks.size(); i++) {
      KnowledgeBase.Chunk c = chunks.get(i);
      u.append('K').append(i + 1).append(" 《").append(c.title).append("》").append(c.section)
          .append(c.strength.isEmpty() ? "" : "（" + c.strength + "）").append("：").append(clip(c.text, 320)).append('\n');
    }
    u.append("\n请输出 JSON。");
    return List.of(new LlmClient.Message("system", system), new LlmClient.Message("user", u.toString()));
  }

  /** Returns null when the output is unusable. Individual items without valid refs / with doses are dropped. */
  Map<String, Object> validate(String content, List<Fact> facts, List<KnowledgeBase.Chunk> chunks, Map<String, String> kref,
      List<Map<String, Object>> dropped) {
    JsonNode root = parseJson(content);
    if (root == null || !root.isObject()) return null;
    Set<String> refs = new HashSet<>(kref.keySet());
    facts.forEach(f -> refs.add(f.id));
    StringBuilder corpus = new StringBuilder();
    facts.forEach(f -> corpus.append(f.text).append(' '));
    chunks.forEach(c -> corpus.append(c.text).append(' ').append(c.section).append(' '));
    Set<String> known = new HashSet<>();
    Matcher nm = NUMBER.matcher(corpus);
    while (nm.find()) known.add(normalize(nm.group()));
    Map<String, Object> out = new LinkedHashMap<>();
    int kept = 0;
    for (String section : SECTIONS) {
      List<Map<String, Object>> items = new ArrayList<>();
      JsonNode arr = root.path(section);
      if (arr.isArray()) for (JsonNode n : arr) {
        String text = n.isTextual() ? n.asText() : n.path("text").asText("");
        text = text.replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) continue;
        List<String> r = new ArrayList<>();
        n.path("refs").forEach(x -> {
          String v = x.asText("").trim().replaceAll("[\\[\\]]", "").toUpperCase(Locale.ROOT);
          if (REF.matcher(v).matches() && refs.contains(v) && !r.contains(v)) r.add(v);
        });
        String reason = null;
        if (!"uncertainties".equals(section) && r.isEmpty()) reason = "无有效引用";
        else if (DOSE.matcher(text).find()) reason = "含具体剂量";
        else { String bad = unknownNumber(text, known); if (bad != null) reason = "数值 " + bad + " 不在依据中"; }
        if (reason != null) { dropped.add(Map.of("section", section, "text", clip(text, 200), "reason", reason)); continue; }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("text", clip(text, 160)); item.put("refs", r);
        if ("suggestions".equals(section)) {
          String urg = n.path("urgency").asText("medium").toLowerCase(Locale.ROOT);
          item.put("urgency", Set.of("high", "medium", "low").contains(urg) ? urg : "medium");
        }
        items.add(item);
        if (items.size() >= 6) break;
      }
      out.put(section, items);
      if (!"uncertainties".equals(section)) kept += items.size();
    }
    return kept == 0 ? null : out;
  }

  static String unknownNumber(String text, Set<String> known) {
    // E#/K# references are not values; small integer counts (≤10) are tolerated.
    String t = text.replaceAll("[EK]\\d{1,3}", " ");
    Matcher m = NUMBER.matcher(t);
    while (m.find()) {
      String v = normalize(m.group());
      double d = Double.parseDouble(v);
      if (d <= 10 && !v.contains(".")) continue;
      if (!known.contains(v)) return m.group();
    }
    return null;
  }
  private static String normalize(String n) {
    if (!n.contains(".")) return n.replaceFirst("^0+(?=\\d)", "");
    String s = n.replaceFirst("0+$", "").replaceFirst("\\.$", "");
    return s.replaceFirst("^0+(?=\\d)", "");
  }

  JsonNode parseJson(String content) {
    if (content == null) return null;
    String s = content.trim().replaceAll("(?s)^```(?:json)?\\s*", "").replaceAll("(?s)\\s*```$", "");
    int a = s.indexOf('{'), b = s.lastIndexOf('}');
    if (a < 0 || b <= a) return null;
    try { return json.readTree(s.substring(a, b + 1)); } catch (Exception e) { return null; }
  }

  public Map<String, Object> feedback(String id, String verdict, String reason, String edited, String actor) {
    if (!Set.of("accept", "reject", "edit").contains(verdict))
      throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_VERDICT", "verdict 仅支持 accept / reject / edit");
    if (("reject".equals(verdict) || "edit".equals(verdict)) && (reason == null || reason.isBlank()))
      throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "驳回或修改需填写原因");
    AiInteraction rec = store.get(id, AiInteraction.class);
    Map<String, Object> f = new LinkedHashMap<>();
    f.put("verdict", verdict); f.put("reason", reason == null ? "" : clip(reason.trim(), 500));
    f.put("editedText", edited == null ? null : clip(edited.trim(), 2000)); f.put("actor", actor); f.put("at", Instant.now());
    store.db.updateFirst(Query.query(Criteria.where("_id").is(rec.id)), new Update().push("feedback", f), AiInteraction.class);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("interactionId", rec.id); out.put("feedback", f); out.put("count", rec.feedback.size() + 1);
    return out;
  }

  private static String blank(String s) { return s == null || s.isEmpty() ? null : s; }
  static String clip(String s, int n) { return s == null ? null : s.length() <= n ? s : s.substring(0, n) + "…"; }
  static String sha(String s) {
    try {
      byte[] b = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder();
      for (byte v : b) out.append(String.format("%02x", v & 255));
      return out.toString();
    } catch (Exception e) { throw new IllegalStateException(e); }
  }
}
