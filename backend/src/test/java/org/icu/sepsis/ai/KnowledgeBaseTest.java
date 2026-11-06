package org.icu.sepsis.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeBaseTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static AiProperties props(String dir) {
    return new AiProperties(false, "", "", "/v1/chat/completions", "/v1/embeddings", "", "", "", 1000, 1000, 1000, 800, 2, 0.2,
        true, false, "", true, dir, "Asia/Shanghai");
  }
  static KnowledgeBase kb(String dir) { AiProperties p = props(dir); return new KnowledgeBase(p, new LlmClient(p, JSON), JSON); }
  static List<String> top(KnowledgeBase kb, String q, int k) { return kb.search(q, k).stream().map(h -> h.chunk.id).collect(Collectors.toList()); }

  @Test void builtInKnowledgeLoadsWithProvenance() {
    KnowledgeBase kb = kb("");
    assertEquals(73, kb.size());
    for (Map<String, Object> d : kb.documents()) {
      assertFalse(String.valueOf(d.get("source")).isBlank(), d.toString());
      assertFalse(String.valueOf(d.get("version")).isBlank(), d.toString());
    }
    assertTrue(kb.documents().stream().anyMatch(d -> "ssc-2026".equals(d.get("docId"))));
    assertTrue(kb.documents().stream().noneMatch(d -> String.valueOf(d.get("title")).contains("2021")), "outdated SSC 2021 must not be bundled");
  }

  @Test void retrievesExpectedStatements() {
    KnowledgeBase kb = kb("");
    assertTrue(top(kb, "血培养 抗菌药物 之前 留取", 3).contains("ssc26-7"));
    assertTrue(top(kb, "去甲肾上腺素 一线 升压药", 3).contains("ssc26-53"));
    assertTrue(top(kb, "qSOFA 筛查", 3).contains("ssc26-4"));
    assertTrue(top(kb, "感染性休克 抗菌药物 1小时", 3).contains("ssc26-16"));
    assertTrue(top(kb, "30 mL/kg 晶体液", 3).contains("ssc26-10"));
    assertTrue(top(kb, "T0 首条医嘱", 3).contains("icu05-t0"));
    assertTrue(top(kb, "血糖 胰岛素", 2).contains("ssc26-92"));
  }

  @Test void tokenizerUsesBigramsAndWords() {
    List<String> t = KnowledgeBase.terms("乳酸 MAP 65 去甲肾上腺素 SpO₂");
    assertTrue(t.containsAll(List.of("乳酸", "map", "65", "去甲", "甲肾", "腺素", "spo2")), t.toString());
  }

  @Test void externalDirectoryOverridesByDocId(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("local.json"), "{\"docId\":\"icu05-rules\",\"title\":\"ICU-05 本院修订\",\"source\":\"科室审批 2026-05\",\"version\":\"v4\","
        + "\"chunks\":[{\"id\":\"icu05-local-1\",\"section\":\"T0\",\"strength\":\"本院质控口径\",\"tags\":[\"T0\"],\"text\":\"本院修订版 T0 说明。\"}]}");
    KnowledgeBase kb = kb(dir.toString());
    assertTrue(kb.get("icu05-local-1").isPresent());
    assertFalse(kb.get("icu05-t0").isPresent());
    assertEquals("v4", kb.documents().stream().filter(d -> "icu05-rules".equals(d.get("docId"))).findFirst().orElseThrow().get("version"));
  }

  @Test void rejectsDuplicateChunkIds(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("dup.json"), "{\"docId\":\"x\",\"chunks\":[{\"id\":\"ssc26-7\",\"text\":\"重复\"}]}");
    assertThrows(IllegalStateException.class, () -> kb(dir.toString()));
  }

  static void writeVersion(Path dir,String filename,String version,String status,String text) throws Exception {
    Map<String,Object> doc=new LinkedHashMap<>();doc.put("docId","test-sop");doc.put("version",version);doc.put("status",status);
    doc.put("category","department");doc.put("source","合成规范");doc.put("chunks",List.of(Map.of("id","test-sop-1","text",text)));
    Files.writeString(dir.resolve(filename),JSON.writeValueAsString(doc));
  }
  @Test void historyRetainsTextAndStableRevisionAfterReplacement(@TempDir Path dir) throws Exception {
    writeVersion(dir,"a.json","v1","active","旧版本合成规范");writeVersion(dir,"b.json","v2","active","新版本合成规范");
    KnowledgeBase kb=kb(dir.toString());List<Map<String,Object>> history=kb.history("test-sop");
    assertEquals(2,history.size());assertEquals(false,history.get(0).get("current"));assertEquals(true,history.get(1).get("current"));
    Map<?,?> old=(Map<?,?>)((List<?>)history.get(0).get("items")).get(0);
    assertEquals("旧版本合成规范",old.get("text"));assertNotEquals(old.get("revisionId"),kb.get("test-sop-1").orElseThrow().revisionId);
    assertEquals("新版本合成规范",kb.get("test-sop-1").orElseThrow().text);
    assertEquals("",kb.get("test-sop-1").orElseThrow().evidenceQuality,"never invent evidence quality");
  }
  @Test void draftDoesNotReplaceActiveRetrieval(@TempDir Path dir) throws Exception {
    writeVersion(dir,"a.json","v1","active","当前规范");writeVersion(dir,"b.json","v2","draft","待审核内容");
    KnowledgeBase kb=kb(dir.toString());assertEquals("当前规范",kb.get("test-sop-1").orElseThrow().text);
    assertEquals(false,kb.history("test-sop").get(1).get("current"));
    assertTrue(kb.search("待审核内容",20).stream().noneMatch(h->"待审核内容".equals(h.chunk.text)));
  }
  @Test void changingContentWithoutVersionIncrementIsRejected(@TempDir Path dir) throws Exception {
    writeVersion(dir,"a.json","v1","active","原文");writeVersion(dir,"b.json","v1","active","悄悄改变");
    assertThrows(IllegalStateException.class,()->kb(dir.toString()));
  }
  @Test void bundledLocalRulesKeepHistoricalVersionsAndPendingK1() {
    KnowledgeBase kb=kb("");assertEquals(2,kb.history("icu05-rules").size());
    String gate=kb.get("icu05-gate").orElseThrow().text;
    assertTrue(gate.contains("乳酸取值窗口待确认"));assertTrue(gate.contains("K1 且 K2"));assertTrue(gate.contains("MAP≤65"));
    assertFalse(gate.contains("MAP<65"));assertTrue(kb.get("icu05-windows").orElseThrow().text.contains("最新"));
  }

  @Test void futureEffectiveVersionIsArchivedWithoutReplacingCurrent(@TempDir Path dir) throws Exception {
    writeVersion(dir,"a.json","v1","active","当前规范");writeVersion(dir,"b.json","v2","active","未来规范");
    var future=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(Files.readString(dir.resolve("b.json")));
    future.put("effectiveFrom","2999-01-01");Files.writeString(dir.resolve("b.json"),JSON.writeValueAsString(future));
    KnowledgeBase kb=kb(dir.toString());assertEquals("当前规范",kb.get("test-sop-1").orElseThrow().text);
    assertEquals(false,kb.history("test-sop").get(1).get("current"));
    assertThrows(UnsupportedOperationException.class,()->((Map) ((List<?>)kb.history("test-sop").get(0).get("items")).get(0)).put("text","篡改"));
  }
}
