package org.icu.sepsis.quality;
import java.util.*;
import org.icu.sepsis.ai.AiUsageStatsService;
import org.icu.sepsis.api.Envelope;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
@RestController @RequestMapping("/api/v1/quality") @PreAuthorize("hasAnyRole('DOCTOR','QUALITY')")
public class QualityReportController {
 private final Icu05BoardService board;private final AiUsageStatsService usage;
 public QualityReportController(Icu05BoardService board,AiUsageStatsService usage){this.board=board;this.usage=usage;}
 @GetMapping("/ai-usage") public Envelope<?> usage(@RequestParam(defaultValue="7") int days){return Envelope.ok(usage.report(days));}
 @GetMapping("/icu05-report") public Envelope<?> report(@RequestParam(defaultValue="7") int days){return Envelope.ok(render(board.board(days)));}
 static Map<String,Object> render(Map<String,Object> data){StringBuilder text=new StringBuilder("ICU-05 本地质控统计报告（确定性统计，非正式上报）\n");
  text.append("统计范围：").append(data.get("from")).append(" 至 ").append(data.get("to")).append("\n规则版本：").append(data.get("ruleVersion")).append("\n");
  text.append("筛查病例：").append(data.get("screened")).append("；纳入分母：").append(data.get("denominator")).append("；候选条件未知：").append(data.get("classificationUnknown")).append("；纳入未知：").append(data.get("poolUnknown")).append("\n");
  if(data.get("windows") instanceof List)for(Object raw:(List<?>)data.get("windows")){Map<?,?> row=(Map<?,?>)raw;text.append(row.get("key")).append("：达标 ").append(row.get("met")).append("，未达标 ").append(row.get("notMet")).append("，无法判定 ").append(row.get("unknown")).append("，进行中 ").append(row.get("inProgress")).append("；可判定病例达标率 ").append(row.get("rate")==null?"无法判定":String.format(Locale.ROOT,"%.1f%%",((Number)row.get("rate")).doubleValue()*100)).append("\n");}
  if(data.get("failureReasons") instanceof List){text.append("未达标原因（病例可重叠）\n");for(Object raw:(List<?>)data.get("failureReasons")){Map<?,?> reason=(Map<?,?>)raw;text.append(reason.get("window")).append("：").append(reason.get("label")).append(" [").append(reason.get("code")).append("] ").append(reason.get("cases")).append(" 例\n");}text.append(data.get("failureReasonDefinition")).append("\n");}
  text.append("完整性：").append(Boolean.TRUE.equals(data.get("complete"))?"本次本地读取未发现失败；不保证医院源完整":"未确认或存在读取缺口").append("；病例读取失败 ").append(data.get("readFailed")).append("；来源不完整 ").append(data.get("sourceIncomplete")).append("\n");
  text.append("K1 无法判定：乳酸取值窗口待确认。未知不计为不满足。\n分母为本地质控候选且符合纳入条件的病例；窗口率为达标/(达标+未达标)，未知和进行中单列。\n");
  text.append(data.get("scope")).append("\n").append(data.get("updateMode")).append("\n仅汇总统计，不含患者姓名或文书正文，不修改 T0、人工覆盖或分子分母。\n");
  Map<String,Object> out=new LinkedHashMap<>();out.put("text",text.toString());out.put("asOf",data.get("asOf"));out.put("days",data.get("days"));out.put("modelInvoked",false);out.put("ruleVersion",data.get("ruleVersion"));return out;
 }
}
