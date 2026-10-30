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
}
