package org.icu.sepsis.research.toolkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.BiFunction;

/** Runs all six analyses on an export directory and writes results.json + report.md (Chinese). */
public final class Report {
  public static final String VERSION = "1.0.0";
  static final List<String[]> SECTIONS = List.of(
      new String[] {"audit", "一、ICU-05 集束化达标与数据可判定性"},
      new String[] {"alerts", "二、预警处置与误报"},
      new String[] {"scores", "三、SOFA 与 SOFA-2 预后区分度"},
      new String[] {"timing", "四、抗菌药时机/早期补液与死亡（观察性）"},
      new String[] {"phenotypes", "五、感染性休克 0–24h 轨迹分型"},
      new String[] {"prediction", "六、6 小时内升压药启动预测（回顾性）"});
  static final Map<String, BiFunction<List<Map<String, Object>>, List<Map<String, Object>>, Map<String, Object>>> RUN = Map.of(
      "audit", (c, a) -> Audit.run(c), "alerts", (c, a) -> AlertsAnalysis.run(a, c), "scores", (c, a) -> Scores.run(c),
      "timing", (c, a) -> Timing.run(c), "phenotypes", (c, a) -> Phenotypes.run(c), "prediction", (c, a) -> Prediction.run(c));
  static final Map<String, String> STATUS = Map.of("ok", "完成", "insufficient", "样本不足", "no_data", "无数据", "error", "出错");

  static final String SILENT_RUN = "## 静默运行方案（方向六上线前必须完成）\n\n"
      + "1. 模型只在后台按小时计算，**不向床旁显示、不发提醒**，持续 ≥3 个月。\n"
      + "2. 每周导出一次，计算前瞻 AUROC、敏感度、阳性预测值、每患者日提示次数与提前量，与本报告回顾性结果对比。\n"
      + "3. 预设停止标准：前瞻 AUROC 下限 < 0.70，或每患者日提示 > 6 次，或校准斜率超出 0.7–1.3。\n"
      + "4. 由 ICU 主任与质控组评审后再决定是否进入床旁试运行；任何阶段均不替代临床判断。\n";

  private Report() {}

  /** NaN / Inf → null; arrays → lists. */
  static Object clean(Object o) {
    if (o instanceof Map) {
      Map<String, Object> out = new LinkedHashMap<>();
      ((Map<?, ?>) o).forEach((k, v) -> out.put(String.valueOf(k), clean(v)));
      return out;
    }
    if (o instanceof Collection) { List<Object> out = new ArrayList<>(); for (Object v : (Collection<?>) o) out.add(clean(v)); return out; }
    if (o instanceof Object[]) return clean(Arrays.asList((Object[]) o));
    if (o instanceof double[]) { List<Object> out = new ArrayList<>(); for (double v : (double[]) o) out.add(clean(v)); return out; }
    if (o instanceof int[]) { List<Object> out = new ArrayList<>(); for (int v : (int[]) o) out.add(v); return out; }
    if (o instanceof Double || o instanceof Float) { double f = ((Number) o).doubleValue(); return Double.isNaN(f) || Double.isInfinite(f) ? null : f; }
    return o;
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> run(Path exportDir, Path outDir) throws IOException {
    Data.Export ex = Data.load(exportDir);
    boolean synthetic = Data.truthy(ex.manifest.get("synthetic"));
    for (int i = 0; i < Math.min(50, ex.cohort.size()) && !synthetic; i++) synthetic = Data.truthy(ex.cohort.get(i).get("synthetic"));
    Map<String, Object> sections = new LinkedHashMap<>();
    Map<String, Object> results = Data.m("version", VERSION,
        "generatedAt", OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")),
        "synthetic", synthetic, "manifest", ex.manifest, "stays", ex.cohort.size(), "alerts", ex.alerts.size(), "sections", sections);
    for (String[] s : SECTIONS) {
      Map<String, Object> res;
      try {
        res = RUN.get(s[0]).apply(ex.cohort, ex.alerts);
      } catch (RuntimeException e) { // one failing analysis must not hide the others
        res = Result.of("error", e.getClass().getSimpleName() + ": " + e.getMessage(), null);
      }
      sections.put(s[0], res);
    }
    results = (Map<String, Object>) clean(results);
    if (outDir != null) {
      Files.createDirectories(outDir);
      Files.writeString(outDir.resolve("results.json"), Data.PRETTY.writeValueAsString(results), StandardCharsets.UTF_8);
      Files.writeString(outDir.resolve("report.md"), markdown(results), StandardCharsets.UTF_8);
    }
    return results;
  }

  static String orDash(Object v) { return v == null ? "—" : String.valueOf(v); }

  public static String markdown(Map<String, Object> res) {
    Map<String, Object> m = Data.map(res.get("manifest")), sections = Data.map(res.get("sections"));
    List<String> lines = new ArrayList<>(List.of("# ICU 脓毒症研究分析报告", ""));
    if (Data.truthy(res.get("synthetic"))) lines.addAll(List.of("> **合成数据**：本报告由模拟数据生成，仅用于验证分析代码，结果不代表任何医院或患者。", ""));
    lines.add("- 导出范围：" + orDash(m.get("from")) + " 至 " + orDash(m.get("to")) + "；病例 " + res.get("stays") + " 例；预警 " + res.get("alerts") + " 条");
    lines.add("- 规则版本：" + orDash(m.get("ruleVersion")) + "；分析工具 " + res.get("version") + "；生成时间 " + res.get("generatedAt"));
    lines.addAll(List.of("- 数据已去标识（无姓名、病案号、源记录 id；时间为相对 T0 的分钟）。", "", "| 方向 | 状态 |", "|---|---|"));
    for (String[] s : SECTIONS) {
      String st = String.valueOf(Data.map(sections.get(s[0])).get("status"));
      lines.add("| " + s[1] + " | " + STATUS.getOrDefault(st, st) + " |");
    }
    lines.add("");
    for (String[] s : SECTIONS) {
      Map<String, Object> sec = Data.map(sections.get(s[0]));
      String st = String.valueOf(sec.get("status"));
      lines.addAll(List.of("## " + s[1], "", "状态：" + STATUS.getOrDefault(st, st), ""));
      for (Object x : Data.list(sec.get("summary"))) lines.add("- " + x);
      lines.add("");
    }
    lines.addAll(List.of(SILENT_RUN, "## 解读限制", "",
        "- 死亡口径：科研分析只计“死亡”；非医嘱离院仅在质控指标（APACHE II 标化病死率）中计为死亡。",
        "- 全部为回顾性、单中心、观察性分析；关联不等于因果，E 值仅提示未测混杂需多强才能解释结果。",
        "- 未导出呼吸支持、升压药剂量与尿量：SOFA 呼吸分项封顶 2 分、心血管按最低剂量档、肾脏仅用肌酐。",
        "- 字段映射需先用本院真实库核对（见 docs/14-research-toolkit.md 的核对清单），核对前结果不可引用。",
        "- 样本不足时各方向直接报告“样本不足”，不输出不稳定的估计。", ""));
    return String.join("\n", lines);
  }

  /** True when any section errored. */
  public static boolean anyError(Map<String, Object> results) {
    return Data.map(results.get("sections")).values().stream().anyMatch(v -> "error".equals(Data.map(v).get("status")));
  }
}
