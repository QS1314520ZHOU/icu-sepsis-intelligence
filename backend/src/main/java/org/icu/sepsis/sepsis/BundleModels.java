package org.icu.sepsis.sepsis;

import java.time.Instant;
import java.util.*;

/** SSC 1h / 3h / 6h Bundle checklist. Items are true / false / null(missing). */
public final class BundleModels {
  private BundleModels() {}

  public static final class BundleItem {
    public final String code, name, evidence;
    public final Boolean done;
    public final Instant at;
    public BundleItem(String code, String name, Boolean done, Instant at, String evidence) {
      this.code = code;
      this.name = name;
      this.done = done;
      this.at = at;
      this.evidence = evidence;
    }
  }

  public static final class BundleWindow {
    public final String window;
    public final Boolean finish;
    public final List<BundleItem> items;
    public final List<String> reasons;
    public BundleWindow(String window, Boolean finish, List<BundleItem> items, List<String> reasons) {
      this.window = window;
      this.finish = finish;
      this.items = items;
      this.reasons = reasons;
    }
  }

  public static BundleWindow oneHour(
      Boolean lactateMeasured,
      Instant lactateAt,
      Boolean cultureDone,
      Instant cultureAt,
      Boolean antibioticDone,
      Instant antibioticAt) {
    List<BundleItem> items = new ArrayList<>();
    items.add(new BundleItem("A1_LACTATE", "测定血乳酸", lactateMeasured, lactateAt, lactateMeasured == null ? "未查到乳酸" : "DataCenter LAC"));
    items.add(new BundleItem("B2_CULTURE", "病原学培养(血/痰/尿任一)", cultureDone, cultureAt, "培养医嘱/执行"));
    items.add(new BundleItem("B1_ABX", "广谱抗菌药物", antibioticDone, antibioticAt, "执行/医嘱抗菌药物"));
    Boolean finish = and(lactateMeasured, cultureDone, antibioticDone);
    List<String> reasons = new ArrayList<>();
    if (finish == null) reasons.add("DATA_MISSING");
    if (Boolean.FALSE.equals(lactateMeasured)) reasons.add("A1_NOT_MET");
    if (Boolean.FALSE.equals(cultureDone)) reasons.add("CULTURE_MISSING");
    if (Boolean.FALSE.equals(antibioticDone)) reasons.add("ABX_MISSING");
    if (Boolean.TRUE.equals(cultureDone) && Boolean.TRUE.equals(antibioticDone)
        && cultureAt != null && antibioticAt != null && antibioticAt.isBefore(cultureAt))
      reasons.add("ABX_BEFORE_CULTURE");
    return new BundleWindow("1h", finish, items, reasons);
  }

  public static BundleWindow threeHour(
      Boolean mapTrigger,
      Boolean lactateTrigger,
      Double fluidMl,
      Boolean lactateRechecked,
      Instant lactateRecheckAt,
      Instant t0) {
    List<BundleItem> items = new ArrayList<>();
    items.add(new BundleItem("C1_MAP", "MAP<70 触发液体复苏", mapTrigger, t0, mapTrigger == null ? "MAP缺失" : "MAP最低"));
    items.add(new BundleItem("C2_LAC", "乳酸≥4 触发液体复苏", lactateTrigger, t0, lactateTrigger == null ? "乳酸缺失" : "初始乳酸"));
    boolean triggered = Boolean.TRUE.equals(mapTrigger) || Boolean.TRUE.equals(lactateTrigger);
    Boolean fluidOk = fluidMl == null ? null : fluidMl >= 1500;
    items.add(new BundleItem("C3_FLUID", "3h 晶体/胶体≥1500ml", triggered ? fluidOk : Boolean.TRUE, null, fluidMl == null ? "液体量未知" : fmtMl(fluidMl)));
    items.add(new BundleItem("LAC_RECHECK", "复测乳酸", lactateRechecked, lactateRecheckAt, "T0 后乳酸复测"));
    Boolean step3;
    if (!triggered) step3 = (mapTrigger == null || lactateTrigger == null) ? null : Boolean.TRUE;
    else step3 = fluidOk;
    Boolean finish = and(Boolean.TRUE, step3, lactateRechecked == null ? null : (lactateRechecked || !triggered ? Boolean.TRUE : Boolean.FALSE));
    // 3h classic: fluids if MAP<70 or lac≥4; recheck lactate if initial ≥4. Soften: finish if fluid path ok and recheck when indicated.
    if (triggered && Boolean.TRUE.equals(fluidOk) && (lactateTrigger == null || !Boolean.TRUE.equals(lactateTrigger) || Boolean.TRUE.equals(lactateRechecked)))
      finish = Boolean.TRUE;
    if (triggered && Boolean.FALSE.equals(fluidOk)) finish = Boolean.FALSE;
    List<String> reasons = new ArrayList<>();
    if (Boolean.FALSE.equals(fluidOk) && triggered) reasons.add("FLUID_INSUFFICIENT");
    if (Boolean.TRUE.equals(mapTrigger)) reasons.add("MAP_TRIGGERED");
    if (Boolean.TRUE.equals(lactateTrigger) && !Boolean.TRUE.equals(lactateRechecked)) reasons.add("LAC_RECHECK_MISSING");
    return new BundleWindow("3h", finish, items, reasons);
  }

  public static BundleWindow sixHour(
      Boolean vasoIndicated,
      Boolean vasoStarted,
      Boolean fluidReassessed,
      Boolean lactateRechecked) {
    List<BundleItem> items = new ArrayList<>();
    items.add(new BundleItem("VASSO", "必要时启动/调整升压药", vasoIndicated == null ? null : (!vasoIndicated || vasoStarted), null, "MAP/乳酸评估"));
    items.add(new BundleItem("REASSESS", "液体反应性/容量再评估", fluidReassessed, null, "6h 再评估"));
    items.add(new BundleItem("LAC6", "必要时复测乳酸", lactateRechecked, null, "灌注再评估"));
    Boolean finish = and(
        vasoIndicated == null ? null : (!vasoIndicated || vasoStarted),
        fluidReassessed,
        lactateRechecked);
    List<String> reasons = new ArrayList<>();
    if (finish == null) reasons.add("DATA_MISSING");
    if (Boolean.FALSE.equals(vasoStarted) && Boolean.TRUE.equals(vasoIndicated)) reasons.add("VASO_NOT_STARTED");
    if (Boolean.FALSE.equals(fluidReassessed)) reasons.add("REASSESS_MISSING");
    return new BundleWindow("6h", finish, items, reasons);
  }

  private static Boolean and(Boolean... xs) {
    boolean hasNull = false;
    for (Boolean x : xs) {
      if (x == null) hasNull = true;
      else if (!x) return Boolean.FALSE;
    }
    return hasNull ? null : Boolean.TRUE;
  }

  private static String fmtMl(double ml) {
    return String.format(Locale.ROOT, "%.0f ml", ml);
  }
}
