package org.icu.sepsis.sepsis;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.icu.sepsis.config.RuleConfig;
import org.icu.sepsis.domain.ClinicalRules;
import org.icu.sepsis.domain.Domain.*;
import org.icu.sepsis.integration.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Read-only clinical evidence review, schema v2. No fabricated diagnosis/model/Bundle attainment. */
@Service
public class SepsisAssessmentService {
  private final ClinicalDataProvider data;
  private final ClinicalRules validation;
  private final RuleConfig config;
  private final ObjectProvider<SmartCareSourceReader> reader;
  private final String mode;
  private final ObjectProvider<org.icu.sepsis.ai.AiProperties> ai;
  public SepsisAssessmentService(ClinicalDataProvider data, ClinicalRules validation, RuleConfig config,
      ObjectProvider<SmartCareSourceReader> reader, @Value("${app.source.mode:demo}") String mode,
      ObjectProvider<org.icu.sepsis.ai.AiProperties> ai) {
    this.data=data; this.validation=validation; this.config=config; this.reader=reader; this.mode=mode; this.ai=ai;
  }
  public Map<String, Object> assess(Patient p, Instant asOf) {
    Instant now=asOf == null ? Instant.now() : asOf;
    List<Observation> rows;
    try { rows=data.observations(p.id,p.encounterId,now.minus(Duration.ofHours(24)),now); }
    catch (RuntimeException e) { return review(p,now,List.of(),false,"临床数据暂不可用，请刷新或联系接口维护人员。"); }
    return review(p,now,rows,true,"");
  }
  private Map<String,Object> review(Patient patient,Instant now,List<Observation> rows,boolean available,String message) {
    Map<String,Observation> latest=new LinkedHashMap<>();
    // Same-time numeric conflicts are excluded; do not pick whichever database row arrives first.
    Map<String,List<Observation>> groups=rows.stream().filter(o->validation.valid(o,now))
        .filter(o->o.value!=null).collect(Collectors.groupingBy(o->o.parameterCode+"|"+o.eventTime));
    Map<String,Instant> conflictAt=new HashMap<>();
    for (List<Observation> g:groups.values()) {
      Observation o=g.get(0);
      if (g.stream().map(x->x.value).distinct().count()!=1) { conflictAt.merge(o.parameterCode,o.eventTime,(a,b)->a.isAfter(b)?a:b); continue; }
      latest.merge(o.parameterCode,o,(a,b)->a.eventTime.isAfter(b.eventTime)?a:b);
    }
    // Never fall back silently to an older value when the newest time point is in conflict.
    List<String> conflicts=new ArrayList<>();
    for (Map.Entry<String,Instant> c:conflictAt.entrySet()) {
      Observation cur=latest.get(c.getKey());
      if (cur==null||!cur.eventTime.isAfter(c.getValue())) { latest.remove(c.getKey()); conflicts.add(c.getKey()); }
    }
    Collections.sort(conflicts);
    boolean channelPressure = latest.keySet().stream().anyMatch(c -> c.matches("(MAP|SBP|DBP)_(IBP|NIBP)"));
    // No site-approved preferred channel: never silently use a generic pressure alongside explicit channels.
    if (channelPressure) { latest.remove("MAP"); latest.remove("SBP"); }
    List<String> sourceWarnings = new ArrayList<>();
    if (channelPressure) sourceWarnings.add("有创与无创血压分通道展示，尚未确认院内择优规则；不自动合成为单一血压输入，qSOFA 血压项待核对。");
    if (!conflicts.isEmpty()) sourceWarnings.add("最新时点存在同刻不一致的记录（"+String.join("、",conflicts)+"），未采用旧值替代，请在原始记录中核对。");
    if ("smartcare".equals(mode)) sourceWarnings.add("源记录入库时间未确认，不能据此进行历史时点回放；乳酸报告的采样时间、结果状态及本次住院关联未确认，候选报告仅供原始记录核对。");
    // Missing = no usable value for screening. Channel MAP/SBP counts as present; a report-only
    // lactate (context_only) is not "absent" — it is unconfirmed and must not be listed as 缺.
    boolean hasMap = latest.containsKey("MAP") || latest.keySet().stream().anyMatch(c -> c.startsWith("MAP_"));
    boolean hasSbp = latest.containsKey("SBP") || latest.keySet().stream().anyMatch(c -> c.startsWith("SBP_"));
    boolean hasLactateValid = latest.containsKey("LACTATE");
    boolean hasLactateReport = rows.stream().anyMatch(o -> ClinicalRules.lactateReport(o, now));
    List<String> missing = new ArrayList<>();
    if (!hasMap) missing.add("MAP");
    if (!hasSbp) missing.add("SBP");
    if (!latest.containsKey("RR")) missing.add("RR");
    if (!latest.containsKey("GCS")) missing.add("GCS");
    if (!hasLactateValid && !hasLactateReport) missing.add("LACTATE");
    // Display-level gaps the overview tiles also show as empty (not all feed qSOFA).
    for (String code : List.of("HR", "TEMP", "SPO2")) {
      boolean present =
          latest.containsKey(code)
              || rows.stream()
                  .anyMatch(
                      o ->
                          code.equals(o.parameterCode)
                              && o.value != null
                              && !"context_only".equals(o.clinicalUse));
      if (!present) missing.add(code);
    }
    if (hasLactateReport && !hasLactateValid) missing.add("LACTATE_UNCONFIRMED");
    List<Map<String,Object>> findings=new ArrayList<>();
    abnormal(findings,latest,"MAP","平均动脉压",config.getMapLow(),true,"低于当前核对阈值");
    abnormal(findings,latest,"SBP","收缩压",config.getSbpLow(),true,"低于当前核对阈值");
    abnormal(findings,latest,"RR","呼吸频率",22,false,"达到 qSOFA 呼吸频率项阈值");
    abnormal(findings,latest,"GCS","意识评分",15,true,"需结合镇静和基础神经状态核对");
    abnormal(findings,latest,"LACTATE","乳酸",config.getLactateHigh(),false,"乳酸升高，需结合灌注等临床情况核对");
    for (String channel : List.of("IBP", "NIBP")) {
      String suffix = "IBP".equals(channel) ? "（有创）" : "（无创）";
      abnormal(findings,latest,"MAP_"+channel,"平均动脉压"+suffix,config.getMapLow(),true,"该通道低于核对阈值；未自动择优");
      abnormal(findings,latest,"SBP_"+channel,"收缩压"+suffix,config.getSbpLow(),true,"该通道低于核对阈值；未自动择优");
    }
    String status=!available?"unavailable":!findings.isEmpty()?"review_required":!missing.isEmpty()?"insufficient_data":"not_triggered";
    String label=!available?"数据暂不可用":!findings.isEmpty()?"存在待复核异常":!missing.isEmpty()?"数据不全，不能完整评估":"本次核对未见阈值异常";
    Map<String,Object> out=new LinkedHashMap<>();
    out.put("schemaVersion",2); out.put("patientId",patient.id); out.put("encounterId",patient.encounterId);
    out.put("asOf",now); out.put("engine",SepsisClinicalRules.ENGINE_VERSION); out.put("ruleSource",SepsisClinicalRules.RULE_SOURCE);
    out.put("dataMode",mode); out.put("simulated",patient.simulated); out.put("available",available); out.put("message",message);
    out.put("screeningStatus",status); out.put("screeningLabel",label); out.put("missingFeatures",missing); out.put("findings",findings);
    out.put("sourceWarnings",sourceWarnings);
    out.put("pressureSelectionRequired",channelPressure);
    out.put("conflictingFeatures",conflicts);
    out.put("pressureEvidence",latest.values().stream().filter(o -> o.parameterCode.matches("(MAP|SBP|DBP)_(IBP|NIBP)"))
        .sorted(Comparator.comparing(o -> o.parameterCode)).map(this::evidence).collect(Collectors.toList()));
    out.put("contextEvidence",rows.stream().filter(o -> "context_only".equals(o.clinicalUse))
        .sorted(Comparator.comparing((Observation o) -> o.eventTime,Comparator.nullsLast(Comparator.reverseOrder())))
        .limit(20).map(this::evidence).collect(Collectors.toList()));
    out.put("qsofa",SepsisClinicalRules.qSofa(value(latest,"SBP"),value(latest,"RR"),value(latest,"GCS")));
    out.put("qsofaNote","仅使用新鲜且有效的最近记录；未核验报告和未选定血压通道不参与计算；缺项不计零，qSOFA 不能单独诊断或排除脓毒症。");
    out.put("vasopressorActive",latest.containsKey("VASOPRESSOR_ACTIVE")?value(latest,"VASOPRESSOR_ACTIVE")==1:null);
    String diagnosis="";
    if(available) {
      diagnosis=rows.stream().filter(o->"DIAGNOSIS".equals(o.parameterCode)&&validation.valid(o,now))
          .map(o->o.text==null?"":o.text).collect(Collectors.joining("；"));
      SmartCareSourceReader src=reader.getIfAvailable();
      if(src!=null) try { diagnosis=src.diagnosisText(patient.id); } catch(RuntimeException ignored) { /* never fabricate infection */ }
    }
    out.put("infectionClue",SepsisClinicalRules.infectionText(diagnosis)?"诊断文本含感染线索，需核对时效及临床依据":"未取得可确认的感染证据");
    out.put("latestEvidence",latest.values().stream().map(this::evidence).collect(Collectors.toList()));
    Observation lac=latest.get("LACTATE");
    out.put("checklist",List.of(
      BundleModels.item("LACTATE","乳酸结果",lac==null?"unknown":"recorded",lac==null?"未取得新鲜有效结果":"已有结果不等于复苏目标达成",lac==null?null:lac.eventTime),
      BundleModels.item("BLOOD_CULTURE","血培养采样","unknown","尚无经过映射核验的实际采样证据；医嘱不代表已采样",null),
      BundleModels.item("ANTIBIOTIC","抗菌药实际执行","unknown","待接入实际给药事件；医嘱和关键词命中不代表已给药",null),
      BundleModels.item("FLUID","液体量与适用性","unknown","需体重、实际输注量及个体化适用性，暂不自动判定达标",null),
      BundleModels.item("REASSESS","容量 / 灌注再评估","unknown","需明确的再评估记录，不自动标为完成",null)));
    out.put("taskNote","本页为只读证据核对，未建立院内批准的 T0 与治疗时限，不自动生成治疗任务。");
    out.put("model",Map.of("configured",false,"label","未配置预测模型","message","不生成风险分或风险百分比"));
    org.icu.sepsis.ai.AiProperties aiProps=ai==null?null:ai.getIfAvailable();
    boolean aiOn=aiProps!=null&&aiProps.chatConfigured();
    out.put("ai",Map.of("configured",aiOn,"label",aiOn?"AI 辅助已配置（按需生成，需医生核对）":"未配置 AI 辅助"));
    return out;
  }
  private Double value(Map<String,Observation> m,String code) { return m.containsKey(code)?m.get(code).value:null; }
  private Map<String,Object> evidence(Observation o) {
    Map<String,Object> m=new LinkedHashMap<>();m.put("code",o.parameterCode);m.put("value",o.value);m.put("unit",o.unit);
    m.put("sourceParameterCode",o.sourceParameterCode);m.put("measurementChannel",o.measurementChannel);
    m.put("timeBasis",o.timeBasis);m.put("reportedAt",o.reportedAt);m.put("sampledAt",o.sampledAt);
    m.put("encounterLinkStatus",o.encounterLinkStatus);m.put("clinicalUse",o.clinicalUse);
    m.put("ingestTimeBasis",o.ingestTimeBasis);m.put("sourceResultStatus",o.sourceResultStatus);m.put("qualityNote",o.qualityNote);
    m.put("eventTime",o.eventTime);m.put("sourceSystem",o.sourceSystem);m.put("sourceRecordId",o.sourceRecordId);return m;
  }
  private void abnormal(List<Map<String,Object>> out,Map<String,Observation> m,String code,String label,double threshold,boolean below,String reason) {
    Observation o=m.get(code);if(o==null||!(below?o.value<threshold:o.value>=threshold))return;
    Map<String,Object> f=evidence(o);f.put("label",label);f.put("reason",reason);out.add(f);
  }
}
