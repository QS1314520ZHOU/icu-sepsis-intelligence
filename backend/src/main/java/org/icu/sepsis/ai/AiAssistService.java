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
import org.icu.sepsis.domain.Domain.Alert;
import org.icu.sepsis.domain.Domain.Patient;
import java.time.Duration;
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
  public static final String PROMPT_VERSION = "ai-brief-v2";
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
  private final org.icu.sepsis.integration.ClinicalDataProvider data;

  public AiAssistService(AiProperties props, LlmClient llm, KnowledgeBase kb, PatientContextBuilder context,
      SepsisAssessmentService sepsis, Icu05Service icu05, Store store, ObjectMapper json) {
    this(props, llm, kb, context, sepsis, icu05, store, json, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public AiAssistService(AiProperties props, LlmClient llm, KnowledgeBase kb, PatientContextBuilder context,
      SepsisAssessmentService sepsis, Icu05Service icu05, Store store, ObjectMapper json,
      org.icu.sepsis.integration.ClinicalDataProvider data) {
    this.data = data;
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

  // ------------------------------------------------------------------ kinds

  /** One output section: key, title, max items, whether refs are required, whether items carry urgency. */
  public static final class Section {
    public final String key, title; public final int max; public final boolean needRef, urgency;
    Section(String key, String title, int max, boolean needRef, boolean urgency) {
      this.key = key; this.title = title; this.max = max; this.needRef = needRef; this.urgency = urgency;
    }
    Map<String, Object> view() { return Map.of("key", key, "title", title, "allowNoRef", !needRef, "urgency", urgency); }
  }
  /** A generation kind: prompt task, output layout, preferred model role and fact detail. */
  public static final class Spec {
    public final String kind, label, role, task; public final List<Section> sections; public final boolean icu05Detail, trends;
    Spec(String kind, String label, String role, boolean icu05Detail, boolean trends, String task, Section... s) {
      this.kind = kind; this.label = label; this.role = role; this.icu05Detail = icu05Detail; this.trends = trends; this.task = task;
      this.sections = List.of(s);
    }
  }
  static Section s(String k, String t, int max) { return new Section(k, t, max, true, false); }
  static Section u(String k, String t, int max) { return new Section(k, t, max, true, true); }
  static final Section UNCERTAIN = new Section("uncertainties", "不确定", 3, false, false);
  public static final Map<String, Spec> SPECS = new LinkedHashMap<>();
  static void spec(Spec x) { SPECS.put(x.kind, x); }
  static {
    spec(new Spec("summary", "病情摘要", "fast", false, false,
        "任务：为当班医生/护士整理当前病情要点：异常指标及其时间、ICU-05 判定状态、数据缺口，以及可考虑核对或评估的事项（引用指南）。",
        s("summary", "要点", 4), s("gaps", "数据缺口", 5), u("suggestions", "可考虑核对", 4), UNCERTAIN));
    spec(new Spec("icu05", "ICU-05 缺项分析", "reasoning", true, false,
        "任务：解释本例 ICU-05 感染性休克 Bundle 的判定结果。逐个时窗说明哪些项目已满足、哪些未满足或无法判定及其原因，指出需要人工核对的原始记录，必要时引用指南说明该项目的意义。不得改写规则引擎给出的判定。",
        s("summary", "要点", 4), s("gaps", "数据缺口", 5), u("suggestions", "可考虑核对", 4), UNCERTAIN));
    spec(new Spec("trend", "趋势解读", "fast", false, true,
        "任务：依据【趋势】类事实，说明近 24 小时各指标的变化方向与幅度（首次→最近、最低/最高、近 6 小时与之前相比），指出持续恶化或改善的指标及其与 MAP<65、乳酸≥2 等阈值的关系，提示需要警惕的变化。只描述已有数据，不推测未提供的指标。",
        s("changes", "显著变化", 5), u("concerns", "需警惕", 4), UNCERTAIN));
    spec(new Spec("handover", "交班摘要", "fast", false, true,
        "任务：按 SBAR 结构写一份供医护交班的摘要。S：当前最主要的问题与异常；B：与本次脓毒症/休克筛查相关的背景（感染线索、升压药、ICU-05 状态）；A：对趋势和数据缺口的客观评估；R：接班者需要重点关注和核对的事项。语句简短，可直接口头交接。",
        s("situation", "S 现状", 3), s("background", "B 背景", 3), s("assessment", "A 评估", 4), u("recommendation", "R 交班关注", 4), UNCERTAIN));
    spec(new Spec("note", "病程记录草稿", "fast", false, true,
        "任务：起草一段 ICU 病程记录的客观部分，供医生修改后使用。病情记录：按时间描述关键生命体征、乳酸、升压药与 ICU-05 执行情况；分析：客观说明与筛查规则的关系（不下诊断）；拟核对/评估：列出需要补充或复核的事项。使用病历书面语，不写具体剂量。",
        s("record", "病情记录", 5), s("analysis", "分析", 4), u("plan", "拟核对 / 评估", 4), UNCERTAIN));
    spec(new Spec("family", "家属沟通要点", "fast", false, true,
        "任务：为医生准备与家属沟通的要点。使用通俗易懂的语言，避免英文缩写和专业术语（必要时用一句话解释）；说明目前情况、医护正在关注什么、接下来可能需要的检查或处理方向；不承诺预后，不给出概率，不做诊断结论。",
        s("current", "目前情况", 3), s("focus", "正在关注", 3), s("notes", "沟通提示", 3), UNCERTAIN));
    spec(new Spec("alert", "预警解读", "fast", false, true,
        "任务：解释这条自动预警。说明触发的具体依据（引用对应事实）；列出需要在原始记录中核对的内容（如血压通道、乳酸采样时间、升压药是否在用、感染依据是否成立）以排除误报；给出可考虑的下一步评估方向（引用指南）。",
        s("why", "触发依据", 3), s("verify", "需核对（排除误报）", 4), u("next", "可考虑的下一步", 4), UNCERTAIN));
    spec(new Spec("ask", "提问", "fast", true, true,
        "任务：回答医生关于当前患者的问题。只依据给出的事实和知识条目；事实中没有的信息直接说明“当前数据中没有”，并指出可在哪类原始记录中核对。",
        s("answer", "回答", 6), UNCERTAIN));
    spec(new Spec("ward", "科室优先级", "fast", false, false,
        "任务：根据全科室当前未结束的自动预警，给出建议优先查看的患者顺序（用 P# 编号指代患者）及理由；归纳多名患者共同存在的问题（如乳酸未复测、预警长时间未处理）。不做诊断。",
        u("priority", "建议优先查看", 6), s("pattern", "共性问题", 3), UNCERTAIN));
  }
  static final Set<String> RESERVED = Set.of("interactionId", "patientId", "encounterId", "kind", "label", "status", "message", "model",
      "modelRole", "fallbackUsed", "latencyMs", "generatedAt", "promptVersion", "alertId", "layout", "facts", "knowledge", "droppedCount",
      "disclaimer", "clinicalUseAllowed", "patients", "question");
  /** Kinds a client may request through /patients/{id}/ai/brief. */
  public static final Set<String> PATIENT_KINDS = Set.of("summary", "icu05", "trend", "handover", "note", "family");

  public Map<String, Object> brief(Patient p, String kind, String actor) {
    if (!PATIENT_KINDS.contains(kind))
      throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_KIND", "kind 仅支持 " + String.join(" / ", PATIENT_KINDS));
    return generate(p, SPECS.get(kind), actor, List.of(), null, null);
  }

  /** Free question about the current patient; history = earlier turns [{q,a}] (≤4), answers are re-validated. */
  public Map<String, Object> ask(Patient p, String question, List<Map<String, String>> history, String actor) {
    String q = question == null ? "" : question.replaceAll("\\s+", " ").trim();
    if (q.isEmpty() || q.length() > 300) throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_QUESTION", "问题不能为空且不超过 300 字");
    StringBuilder extra = new StringBuilder();
    List<Map<String, String>> h = history == null ? List.of() : history;
    for (Map<String, String> t : h.subList(Math.max(0, h.size() - 4), h.size()))
      extra.append("此前提问：").append(clip(PatientContextBuilder.scrub(String.valueOf(t.getOrDefault("q", ""))), 200))
          .append("\n此前回答：").append(clip(PatientContextBuilder.scrub(String.valueOf(t.getOrDefault("a", ""))), 400)).append('\n');
    extra.append("【本次问题】").append(PatientContextBuilder.scrub(q));
    Map<String, Object> out = generate(p, SPECS.get("ask"), actor, List.of(), extra.toString(), null);
    out.put("question", q);
    return out;
  }

  /** Explanation of one alert (also run in the background right after a screening alert is created). */
  public Map<String, Object> explainAlert(Alert a, String actor) {
    Patient p = store.db.findById(a.patientId, Patient.class);
    if (p == null) throw ApiException.notFound();
    String trig = a.trigger != null ? a.trigger : a.reason;
    List<String[]> pre = new ArrayList<>(); pre.add(new String[] {"预警", ("critical".equals(a.severity) ? "紧急" : "优先") + "预警：" + trig});
    return generate(p, SPECS.get("alert"), actor, pre, null, a.id);
  }

  public void explainAlertAsync(Alert a) {
    if (!props.chatConfigured()) return;
    background.submit(() -> {
      try { explainAlert(a, "system"); } catch (RuntimeException e) { /* alert stays usable without AI */ }
    });
  }

  /** Latest stored explanation for an alert, or null. */
  public Map<String, Object> alertExplanation(String alertId) {
    AiInteraction rec = store.db.findOne(Query.query(Criteria.where("alertId").is(alertId))
        .with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")), AiInteraction.class);
    return rec == null || rec.facts == null ? null : response(rec, SPECS.get("alert"));
  }

  /** Ward-level prioritisation from open alerts. Patients are pseudonymised P1..Pn; the map back stays server→client. */
  public Map<String, Object> ward(List<Alert> open, Patient anchor, String actor) {
    List<String[]> pre = new ArrayList<>();
    Map<String, String> tags = new LinkedHashMap<>(), names = new LinkedHashMap<>();
    Instant now = Instant.now();
    for (Alert a : open) {
      String key = a.patientId + "|" + a.encounterId;
      String tag = tags.computeIfAbsent(key, k -> "P" + (tags.size() + 1));
      names.putIfAbsent(tag, ((a.bed == null ? "" : a.bed + "床 ") + (a.patientLabel == null ? "" : a.patientLabel)).trim());
      long h = Duration.between(a.createdAt, now).toHours();
      pre.add(new String[] {"预警 " + tag, tag + " " + ("critical".equals(a.severity) ? "紧急" : "优先") + "：" + (a.trigger != null ? a.trigger : a.reason)
          + "；状态 " + STATUS_CN.getOrDefault(a.status, a.status) + "；已持续约 " + h + " 小时"});
    }
    if (pre.isEmpty()) pre.add(new String[] {"预警", "当前科室没有未结束的自动预警"});
    Map<String, Object> out = generate(anchor, SPECS.get("ward"), actor, pre, null, null, false);
    out.put("patients", names);
    out.put("patientId", null); out.put("encounterId", null);
    return out;
  }
  static final Map<String, String> STATUS_CN = Map.of("pending", "待审核", "acknowledged", "已接收", "deferred", "暂缓", "confirmed", "已确认");

  private final java.util.concurrent.ExecutorService background = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "ai-alert-explain"); t.setDaemon(true); return t;
  });

  Map<String, Object> generate(Patient p, Spec spec, String actor, List<String[]> pre, String extra, String alertId) {
    return generate(p, spec, actor, pre, extra, alertId, true);
  }

  Map<String, Object> generate(Patient p, Spec spec, String actor, List<String[]> pre, String extra, String alertId, boolean patientFacts) {
    Instant now = Instant.now();
    List<Fact> facts = new ArrayList<>();
    for (String[] f : pre) context.add(facts, f[0], f[1], null);
    if (patientFacts) {
      Map<String, Object> review = sepsis.assess(p, now);
      Map<String, Object> bundle;
      try {
        bundle = icu05.evaluate(p, now);
      } catch (RuntimeException e) {
        // The explanation must never fail because one source is unreadable; the gap is stated as a fact instead.
        bundle = Map.of("available", false, "message", "ICU-05 来源读取失败，本次不纳入");
      }
      facts.addAll(renumber(context.build(review, bundle, now, spec.icu05Detail), facts.size()));
      if (spec.trends && data != null) {
        try {
          context.trends(facts, data.observations(p.id, p.encounterId, now.minus(Duration.ofHours(24)), now), now);
        } catch (RuntimeException e) {
          context.add(facts, "趋势", "近 24 小时趋势数据读取失败，本次不纳入", null);
        }
      }
    }
    List<KnowledgeBase.Chunk> chunks = retrieve(spec.kind, facts, extra);
    Map<String, String> kref = new LinkedHashMap<>();
    for (int i = 0; i < chunks.size(); i++) kref.put("K" + (i + 1), chunks.get(i).id);

    AiInteraction rec = new AiInteraction();
    store.init(rec, p, now);
    rec.kind = spec.kind; rec.actor = actor; rec.traceId = org.slf4j.MDC.get("traceId"); rec.alertId = alertId;
    rec.factIds = facts.stream().map(f -> f.id).collect(Collectors.toList());
    rec.facts = facts.stream().map(Fact::view).collect(Collectors.toList());
    rec.question = extra;
    for (int i = 0; i < chunks.size(); i++) {
      KnowledgeBase.Chunk c = chunks.get(i);
      Map<String, Object> v = new LinkedHashMap<>();
      v.put("ref", "K" + (i + 1)); v.put("id", c.id); v.put("docId", c.docId); v.put("version", c.version);
      rec.knowledge.add(v);
      Map<String, Object> full = c.view(); full.put("ref", "K" + (i + 1)); rec.knowledgeFull.add(full);
    }
    List<LlmClient.Message> messages = messages(spec, facts, chunks, extra);
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
        for (String[] m : props.modelOrder(spec.role)) {
          Map<String, Object> attempt = new LinkedHashMap<>();
          attempt.put("model", m[1]); attempt.put("role", m[0]);
          rec.attempts.add(attempt);
          try {
            LlmClient.Reply r = call(m[1], messages, Integer.parseInt(m[2]), tokens(spec));
            attempt.put("latencyMs", r.latencyMs); attempt.put("finishReason", r.finishReason);
            rec.lastFinishReason = r.finishReason;
            rec.rawResponse = clip(r.content, 20000);
            List<Map<String, Object>> dropped = new ArrayList<>();
            Map<String, Object> parsed = validate(r.content, spec, facts, chunks, kref, dropped);
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
    rec.droppedKept = keptDropped.size();
    rec.generatedAt = now;
    store.db.insert(rec);
    return response(rec, spec);
  }

  private List<Fact> renumber(List<Fact> facts, int offset) {
    if (offset == 0) return facts;
    for (Fact f : facts) f.id = "E" + (Integer.parseInt(f.id.substring(1)) + offset);
    return facts;
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> response(AiInteraction rec, Spec spec) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("interactionId", rec.id); out.put("patientId", rec.patientId); out.put("encounterId", rec.encounterId);
    out.put("kind", rec.kind); out.put("label", spec.label); out.put("status", rec.status); out.put("message", message(rec, rec.droppedKept));
    out.put("model", rec.model); out.put("modelRole", rec.modelRole); out.put("fallbackUsed", rec.fallbackUsed);
    out.put("latencyMs", rec.latencyMs); out.put("generatedAt", rec.generatedAt != null ? rec.generatedAt : rec.createdAt); out.put("promptVersion", PROMPT_VERSION);
    out.put("alertId", rec.alertId);
    out.put("layout", spec.sections.stream().map(Section::view).collect(Collectors.toList()));
    for (String legacy : SECTIONS) out.put(legacy, List.of());
    for (Section sec : spec.sections) {
      if (RESERVED.contains(sec.key)) throw new IllegalStateException("section key collides with response field: " + sec.key);
      out.put(sec.key, rec.result == null ? List.of() : rec.result.getOrDefault(sec.key, List.of()));
    }
    out.put("facts", rec.facts);
    out.put("knowledge", rec.knowledgeFull);
    out.put("droppedCount", rec.droppedKept);
    out.put("disclaimer", DISCLAIMER);
    out.put("clinicalUseAllowed", false);
    return out;
  }

  /** Long narrative kinds need more output room; reasoning models also spend tokens before the JSON. */
  int tokens(Spec spec) {
    int base = props.maxTokens;
    switch (spec.kind) {
      case "note": case "handover": case "icu05": case "ward": return Math.max(base, 3000);
      default: return Math.max(base, 2000);
    }
  }

  private LlmClient.Reply call(String model, List<LlmClient.Message> messages, int timeout, int maxTokens) {
    try {
      return llm.chat(model, messages, timeout, props.jsonMode, maxTokens);
    } catch (LlmClient.LlmException e) {
      // Some OpenAI-compatible servers reject response_format; retry once in plain mode.
      if (props.jsonMode && "http_400".equals(e.kind)) return llm.chat(model, messages, timeout, false, maxTokens);
      throw e;
    }
  }

  private static String message(AiInteraction r, int dropped) {
    switch (r.status) {
      case "ok": return dropped == 0 ? "" : "已过滤 " + dropped + " 条缺少有效依据、数值不符或含具体剂量的内容";
      case "disabled": return "AI 辅助未配置，仅列出本次核对依据与相关知识条目。";
      case "busy": return "AI 服务繁忙，请稍后重试。";
      case "invalid_output": {
        if ("length".equals(r.lastFinishReason)) return "模型输出过长被截断，未展示；可重试或调大 AI_MAX_TOKENS。";
        Map<String, Integer> why = new LinkedHashMap<>();
        for (Map<String, Object> d : r.dropped) why.merge(String.valueOf(d.get("reason")).replaceAll("数值 .* 不在依据中", "数值不在依据中"), 1, Integer::sum);
        if (why.isEmpty()) return "模型输出不是有效 JSON，未展示；可重试。";
        return "模型输出未通过引用校验（" + why.entrySet().stream().map(e -> e.getKey() + " " + e.getValue() + " 条").collect(Collectors.joining("，")) + "），未展示；可重试。";
      }
      default: return "AI 服务暂不可用（" + (r.errorKind == null ? "unknown" : r.errorKind) + "），规则判定不受影响。";
    }
  }

  List<KnowledgeBase.Chunk> retrieve(String kind, List<Fact> facts) { return retrieve(kind, facts, null); }

  List<KnowledgeBase.Chunk> retrieve(String kind, List<Fact> facts, String extra) {
    LinkedHashMap<String, KnowledgeBase.Chunk> pick = new LinkedHashMap<>();
    if ("icu05".equals(kind))
      for (String id : List.of("icu05-windows", "icu05-tristate")) kb.get(id).ifPresent(c -> pick.put(c.id, c));
    // Only facts that describe a problem (abnormal, missing, unmet, unknown) drive retrieval.
    String problems = facts.stream()
        .filter(f -> "观测".equals(f.category) || f.text.contains("否") || f.text.contains("无法") || f.text.contains("未") || f.text.contains("缺"))
        .map(f -> f.text).collect(Collectors.joining(" ")) + (extra == null ? "" : " " + extra);
    List<String> queries = new ArrayList<>();
    for (Map.Entry<String, String> t : TOPICS.entrySet())
      if (problems.contains(t.getKey()) && !queries.contains(t.getValue())) queries.add(t.getValue());
    if (extra != null && !extra.isBlank()) queries.add(0, clip(extra.substring(Math.max(0, extra.lastIndexOf("【本次问题】"))), 120));
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
    return messages(SPECS.get(kind), facts, chunks, null);
  }

  List<LlmClient.Message> messages(Spec spec, List<Fact> facts, List<KnowledgeBase.Chunk> chunks, String extra) {
    StringBuilder shape = new StringBuilder("{");
    StringBuilder limits = new StringBuilder();
    for (int i = 0; i < spec.sections.size(); i++) {
      Section sec = spec.sections.get(i);
      if (i > 0) { shape.append(','); limits.append('，'); }
      shape.append('"').append(sec.key).append("\":[{\"text\":\"\",\"refs\":[").append(sec.needRef ? "\"E1\"" : "").append(']')
          .append(sec.urgency ? ",\"urgency\":\"high|medium|low\"" : "").append("}]");
      limits.append(sec.key).append("（").append(sec.title).append("）≤").append(sec.max).append(" 条");
    }
    shape.append('}');
    String needRef = spec.sections.stream().filter(x -> x.needRef).map(x -> x.key).collect(Collectors.joining("、"));
    String system = String.join("\n",
        "你是 ICU 床旁临床信息整理助手，读者是执业医生和护士。只能依据用户消息中的【患者事实 E#】和【知识条目 K#】作答。",
        "规则：",
        "1. " + needRef + " 的每一条都必须在 refs 中引用至少一个给出的 E# 或 K# 编号；不得编造编号。",
        "2. 不做诊断结论，不下达医嘱，不写具体药物名称的剂量或速度；建议用“建议核对 / 可考虑评估 / 可与上级医师讨论”等措辞。",
        "3. 数据缺失、冲突或时间不明时写“无法判断”，放入 uncertainties；不得推测或补全数值。",
        "4. 规则判定（ICU-05 状态、时窗结果、qSOFA）以事实为准，不重新计算，不改写结论。“无法判定”不等于“未完成”。",
        "5. 引用数值时必须与事实原文一致。",
        "6. 简洁：" + limits + "，每条不超过 " + ("note".equals(spec.kind) ? 90 : 60) + " 个汉字。",
        "7. 只输出一个 JSON 对象，不要输出其它文字：",
        shape.toString());
    StringBuilder u = new StringBuilder(spec.task).append("\n\n【患者事实】（已去标识化，时间为本地时间）\n");
    for (Fact f : facts) u.append(f.id).append(" [").append(f.category).append("] ").append(f.text).append('\n');
    u.append("\n【知识条目】\n");
    for (int i = 0; i < chunks.size(); i++) {
      KnowledgeBase.Chunk c = chunks.get(i);
      u.append('K').append(i + 1).append(" 《").append(c.title).append("》").append(c.section)
          .append(c.strength.isEmpty() ? "" : "（" + c.strength + "）").append("：").append(clip(c.text, 320)).append('\n');
    }
    if (extra != null && !extra.isBlank()) u.append("\n").append(extra).append('\n');
    u.append("\n请输出 JSON。");
    return List.of(new LlmClient.Message("system", system), new LlmClient.Message("user", u.toString()));
  }

  /** Returns null when the output is unusable. Individual items without valid refs / with doses are dropped. */
  Map<String, Object> validate(String content, List<Fact> facts, List<KnowledgeBase.Chunk> chunks, Map<String, String> kref,
      List<Map<String, Object>> dropped) {
    return validate(content, SPECS.get("summary"), facts, chunks, kref, dropped);
  }

  Map<String, Object> validate(String content, Spec spec, List<Fact> facts, List<KnowledgeBase.Chunk> chunks, Map<String, String> kref,
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
    for (Section sec : spec.sections) {
      String section = sec.key;
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
        if (sec.needRef && r.isEmpty()) reason = "无有效引用";
        else if (DOSE.matcher(text).find()) reason = "含具体剂量";
        else { String bad = unknownNumber(text, known); if (bad != null) reason = "数值 " + bad + " 不在依据中"; }
        if (reason != null) { dropped.add(Map.of("section", section, "text", clip(text, 200), "reason", reason)); continue; }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("text", clip(text, 200)); item.put("refs", r);
        if (sec.urgency) {
          String urg = n.path("urgency").asText("medium").toLowerCase(Locale.ROOT);
          item.put("urgency", Set.of("high", "medium", "low").contains(urg) ? urg : "medium");
        }
        items.add(item);
        if (items.size() >= Math.max(sec.max, 6)) break;
      }
      out.put(section, items);
      if (sec.needRef) kept += items.size();
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
    try { return json.readTree(s.substring(a, b + 1)); } catch (Exception e) { return repair(s.substring(a)); }
  }

  /** Truncated output (max_tokens hit): keep every complete item and close the open brackets. */
  JsonNode repair(String s) {
    Deque<Character> stack = new ArrayDeque<>();
    boolean str = false, esc = false;
    int cut = -1; String closers = null;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (str) { if (esc) esc = false; else if (c == '\\') esc = true; else if (c == '"') str = false; continue; }
      if (c == '"') str = true;
      else if (c == '{' || c == '[') stack.push(c);
      else if (c == '}' || c == ']') {
        if (stack.isEmpty()) break;
        stack.pop();
        StringBuilder close = new StringBuilder();
        for (char o : stack) close.append(o == '{' ? '}' : ']');
        cut = i + 1; closers = close.toString();
        if (stack.isEmpty()) break;
      }
    }
    if (cut < 0) return null;
    try { return json.readTree(s.substring(0, cut) + closers); } catch (Exception e) { return null; }
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
