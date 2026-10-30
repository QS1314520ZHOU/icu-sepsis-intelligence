package org.icu.sepsis.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Curated guideline / local-rule snippets. Lexical BM25 over Chinese character bigrams + ASCII words, with an
 * optional embedding re-rank (reciprocal-rank fusion) when the hospital gateway exposes an embedding model.
 * Embeddings are built in the background and never block a clinical request.
 */
@Component
public class KnowledgeBase {
  private static final Logger log = LoggerFactory.getLogger(KnowledgeBase.class);
  static final long RETRY_MS = 10 * 60 * 1000L;

  public static class Chunk {
    public String id, docId, title, source, version, url, section, strength, text;
    public List<String> tags = new ArrayList<>();
    transient List<String> terms;
    public Map<String, Object> view() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", id); m.put("docId", docId); m.put("title", title); m.put("source", source); m.put("version", version);
      m.put("url", url); m.put("section", section); m.put("strength", strength); m.put("tags", tags); m.put("text", text);
      return m;
    }
  }
  public static class Hit {
    public final Chunk chunk; public final double score;
    Hit(Chunk c, double s) { chunk = c; score = s; }
  }

  private final List<Chunk> chunks = new ArrayList<>();
  private final List<Map<String, Object>> docs = new ArrayList<>();
  private final Map<String, Integer> df = new HashMap<>();
  private double avgLen;
  private final AiProperties props;
  private final LlmClient llm;
  private volatile Map<String, float[]> vectors;
  private volatile String vectorStatus = "not_configured";
  private volatile long lastAttempt;

  public KnowledgeBase(AiProperties props, LlmClient llm, ObjectMapper json) {
    this.props = props; this.llm = llm;
    try {
      for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:knowledge/*.json"))
        try (InputStream in = r.getInputStream()) { load(json.readTree(in), "classpath:" + r.getFilename()); }
      if (!props.knowledgeDir.isEmpty()) {
        Path dir = Path.of(props.knowledgeDir);
        if (Files.isDirectory(dir)) try (Stream<Path> s = Files.list(dir)) {
          for (Path f : s.filter(x -> x.toString().endsWith(".json")).sorted().collect(Collectors.toList()))
            try (InputStream in = Files.newInputStream(f)) { load(json.readTree(in), f.getFileName().toString()); }
        }
        else log.warn("app.ai.knowledge-dir is not a directory; only built-in knowledge is used");
      }
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Knowledge base failed to load", e);
    }
    index();
    if (props.embeddingConfigured()) vectorStatus = "pending";
  }

  private void load(JsonNode d, String origin) {
    String docId = d.path("docId").asText("");
    if (docId.isEmpty() || !d.path("chunks").isArray()) throw new IllegalStateException("Invalid knowledge file " + origin);
    // An external file may replace a built-in document with the same docId (e.g. an approved local SOP update).
    chunks.removeIf(c -> c.docId.equals(docId));
    docs.removeIf(m -> docId.equals(m.get("docId")));
    Set<String> ids = chunks.stream().map(c -> c.id).collect(Collectors.toSet());
    int n = 0;
    for (JsonNode j : d.get("chunks")) {
      Chunk c = new Chunk();
      c.id = j.path("id").asText(""); c.text = j.path("text").asText("");
      if (c.id.isEmpty() || c.text.isEmpty() || !ids.add(c.id))
        throw new IllegalStateException("Invalid or duplicate chunk in " + origin + ": " + c.id);
      c.docId = docId; c.title = d.path("title").asText(docId); c.source = d.path("source").asText("");
      c.version = d.path("version").asText(""); c.url = d.path("url").asText(null);
      c.section = j.path("section").asText(""); c.strength = j.path("strength").asText("");
      j.path("tags").forEach(t -> c.tags.add(t.asText()));
      chunks.add(c); n++;
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("docId", docId); m.put("title", d.path("title").asText(docId)); m.put("version", d.path("version").asText(""));
    m.put("source", d.path("source").asText("")); m.put("url", d.path("url").asText(null)); m.put("note", d.path("note").asText(""));
    m.put("chunks", n); m.put("origin", origin);
    docs.add(m);
  }

  private void index() {
    df.clear();
    long total = 0;
    for (Chunk c : chunks) {
      // Tags are counted twice: they are curated keywords and should dominate incidental text matches.
      c.terms = terms(c.title + " " + c.section + " " + String.join(" ", c.tags) + " " + c.text + " " + String.join(" ", c.tags));
      total += c.terms.size();
      for (String t : new HashSet<>(c.terms)) df.merge(t, 1, Integer::sum);
    }
    avgLen = chunks.isEmpty() ? 1 : (double) total / chunks.size();
  }

  /** Chinese: overlapping character bigrams (+ single chars for 1-char runs); ASCII: lower-cased words/numbers. */
  static List<String> terms(String s) {
    List<String> out = new ArrayList<>();
    if (s == null) return out;
    String text = s.toLowerCase(Locale.ROOT).replace('₂', '2');
    StringBuilder han = new StringBuilder(), word = new StringBuilder();
    for (int i = 0; i <= text.length(); i++) {
      char ch = i < text.length() ? text.charAt(i) : ' ';
      boolean isHan = Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN;
      boolean isWord = !isHan && (Character.isLetterOrDigit(ch) || ch == '.');
      if (!isHan && han.length() > 0) { flushHan(han, out); han.setLength(0); }
      if (!isWord && word.length() > 0) {
        String w = word.toString().replaceAll("^\\.+|\\.+$", "");
        if (!w.isEmpty()) out.add(w);
        word.setLength(0);
      }
      if (isHan) han.append(ch); else if (isWord) word.append(ch);
    }
    return out;
  }
  private static void flushHan(StringBuilder h, List<String> out) {
    if (h.length() == 1) { out.add(h.toString()); return; }
    for (int i = 0; i + 1 < h.length(); i++) out.add(h.substring(i, i + 2));
  }

  public List<Hit> search(String query, int k) {
    List<Hit> lexical = bm25(query);
    Map<String, float[]> vec = vectors;
    if (vec == null || vec.isEmpty()) {
      maybeBuildInBackground();
      return lexical.stream().limit(k).collect(Collectors.toList());
    }
    List<Hit> semantic;
    try {
      float[] q = llm.embed(List.of(query), Math.min(props.fastTimeoutMs, 5000)).get(0);
      semantic = chunks.stream().filter(c -> vec.containsKey(c.id)).map(c -> new Hit(c, cosine(q, vec.get(c.id))))
          .sorted(Comparator.comparingDouble((Hit h) -> -h.score)).limit(30).collect(Collectors.toList());
    } catch (RuntimeException e) {
      return lexical.stream().limit(k).collect(Collectors.toList());
    }
    Map<String, Double> rrf = new LinkedHashMap<>();
    for (int i = 0; i < lexical.size() && i < 30; i++) rrf.merge(lexical.get(i).chunk.id, 1.0 / (60 + i), Double::sum);
    for (int i = 0; i < semantic.size(); i++) rrf.merge(semantic.get(i).chunk.id, 1.0 / (60 + i), Double::sum);
    Map<String, Chunk> byId = chunks.stream().collect(Collectors.toMap(c -> c.id, c -> c));
    return rrf.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(k)
        .map(e -> new Hit(byId.get(e.getKey()), e.getValue())).collect(Collectors.toList());
  }

  List<Hit> bm25(String query) {
    List<String> q = terms(query).stream().distinct().collect(Collectors.toList());
    double k1 = 1.2, b = 0.75, n = chunks.size();
    List<Hit> hits = new ArrayList<>();
    for (Chunk c : chunks) {
      Map<String, Long> tf = c.terms.stream().collect(Collectors.groupingBy(t -> t, Collectors.counting()));
      double score = 0;
      for (String t : q) {
        Long f = tf.get(t);
        if (f == null) continue;
        double idf = Math.log(1 + (n - df.get(t) + 0.5) / (df.get(t) + 0.5));
        score += idf * f * (k1 + 1) / (f + k1 * (1 - b + b * c.terms.size() / avgLen));
      }
      if (score > 0) hits.add(new Hit(c, score));
    }
    hits.sort(Comparator.comparingDouble((Hit h) -> -h.score));
    return hits;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void warmUp() {
    maybeBuildInBackground();
  }

  private void maybeBuildInBackground() {
    if (!props.embeddingConfigured() || vectors != null || "building".equals(vectorStatus)) return;
    if ("failed".equals(vectorStatus) && System.currentTimeMillis() - lastAttempt < RETRY_MS) return;
    Thread t = new Thread(this::buildVectors, "kb-embedding");
    t.setDaemon(true);
    t.start();
  }

  /** Embeds every chunk once; on failure lexical retrieval keeps working and a retry is allowed after 10 minutes. */
  synchronized void buildVectors() {
    if (!props.embeddingConfigured() || vectors != null) return;
    vectorStatus = "building";
    lastAttempt = System.currentTimeMillis();
    try {
      Map<String, float[]> m = new HashMap<>();
      for (int i = 0; i < chunks.size(); i += 16) {
        List<Chunk> batch = chunks.subList(i, Math.min(chunks.size(), i + 16));
        List<float[]> v = llm.embed(batch.stream().map(c -> c.title + " " + c.section + "：" + c.text).collect(Collectors.toList()),
            props.reasoningTimeoutMs);
        for (int j = 0; j < batch.size(); j++) m.put(batch.get(j).id, v.get(j));
      }
      vectors = m;
      vectorStatus = "ready";
      log.info("Knowledge embeddings ready: {} chunks", m.size());
    } catch (RuntimeException e) {
      vectorStatus = "failed";
      log.warn("Knowledge embedding unavailable, lexical retrieval only (retry in 10 min): {}", e.getMessage());
    }
  }

  static double cosine(float[] a, float[] b) {
    if (a.length != b.length) return 0;
    double d = 0, na = 0, nb = 0;
    for (int i = 0; i < a.length; i++) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
    return na == 0 || nb == 0 ? 0 : d / Math.sqrt(na * nb);
  }

  public Optional<Chunk> get(String id) { return chunks.stream().filter(c -> c.id.equals(id)).findFirst(); }
  public int size() { return chunks.size(); }
  public List<Map<String, Object>> documents() { return Collections.unmodifiableList(docs); }
  public String vectorStatus() { return vectorStatus; }
}
