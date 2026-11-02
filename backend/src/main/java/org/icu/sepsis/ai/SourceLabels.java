package org.icu.sepsis.ai;

import java.util.Map;

/** 临床界面与 AI 事实里使用的来源名称：与宿主菜单一致（观察项 / 出入量 / 执行用药…），不出现库表名。 */
public final class SourceLabels {
  private SourceLabels() {}

  private static final Map<String, String> NAMES = Map.ofEntries(
      Map.entry("SmartCare.bedside", "观察项"),
      Map.entry("SmartCare.score", "评分"),
      Map.entry("SmartCare.bGATemp", "血气"),
      Map.entry("SmartCare.drugExe", "执行用药"),
      Map.entry("SmartCare.patient", "患者信息"),
      Map.entry("SmartCare.configDrug", "药品字典"),
      Map.entry("DataCenter.VI_ICU_EXAM_ITEM", "检验报告"),
      Map.entry("DataCenter.VI_ICU_EXAM", "检验申请"),
      Map.entry("DataCenter.VI_ICU_ZYYZ", "医嘱"),
      Map.entry("MOCK_ONLY", "模拟数据"),
      Map.entry("MOCK_MANUAL", "模拟录入"),
      Map.entry("SYNTHETIC", "模拟数据"));

  public static String of(Object sourceSystem, Object code) {
    if ("URINE_OUTPUT".equals(code)) return "出入量";
    if (sourceSystem == null || sourceSystem.toString().isBlank()) return "未标注";
    String s = sourceSystem.toString();
    String n = NAMES.get(s);
    if (n != null) return n;
    if (s.startsWith("SmartCare.")) return "重症系统记录";
    if (s.startsWith("DataCenter.")) return "数据中心记录";
    return s;
  }
}
