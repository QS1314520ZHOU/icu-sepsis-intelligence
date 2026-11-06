package org.icu.sepsis.notes;

import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.icu.sepsis.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Local evidence organisation only. No model client, clinical classification or free-text persistence. */
@Service
public class ClinicalNoteReviewService {
  public static final String VERSION="local-note-review-v1";
  public static final List<String> CATEGORIES=List.of("infection","organ","alternative","conflict","check");
  public static final Map<String,String> LABELS=Map.of("infection","支持或提示感染的待核对片段","organ","器官功能及基线待核对片段","alternative","其他可能原因待核对片段","conflict","相反证据、冲突及缺失","check","医生待核对事项");
  private final ClinicalNoteEvidenceService evidence;
  public ClinicalNoteReviewService(ClinicalNoteEvidenceService evidence){this.evidence=evidence;}
  public static class Selection {
    @javax.validation.constraints.NotBlank @javax.validation.constraints.Size(max=128) public String snapshotId;
    @javax.validation.constraints.Pattern(regexp="infection|organ|alternative|conflict|check") @javax.validation.constraints.NotNull public String category;
    @javax.validation.constraints.NotNull @javax.validation.constraints.Min(0) public Integer start;
    @javax.validation.constraints.NotNull @javax.validation.constraints.Min(1) public Integer end;
  }
  public static class Request {
    @javax.validation.constraints.NotNull public Instant asOf;
    @javax.validation.constraints.NotBlank @javax.validation.constraints.Size(max=128) public String expectedFingerprint;
    @javax.validation.Valid @javax.validation.constraints.NotNull @javax.validation.constraints.Size(min=1,max=12) public List<@javax.validation.constraints.NotNull Selection> selections;
  }
  public Map<String,Object> draft(String patientId,Request request,String actor){
    if(request==null||request.asOf==null||request.selections==null||request.selections.isEmpty()||request.selections.size()>12)throw bad();
    for(var item:request.selections)if(item==null||!CATEGORIES.contains(Objects.toString(item.category,""))||item.start==null||item.end==null)throw bad();
    Map<String,Object> packet=evidence.prepare(patientId,request.asOf,1000,actor);
    if(!Objects.equals(request.expectedFingerprint,packet.get("packetFingerprint")))throw ApiException.conflict("证据集合已变化，请重新选择片段");
    List<Map<String,Object>> items=new ArrayList<>();Set<String> seen=new HashSet<>();
    for(var item:request.selections){String key=item.snapshotId+":"+item.start+":"+item.end+":"+item.category;if(!seen.add(key))continue;
      Map<String,Object> quote=evidence.excerptFromPacket(patientId,item.snapshotId,request.asOf,item.start,item.end,request.expectedFingerprint,actor,packet);
      Map<String,Object> row=new LinkedHashMap<>(quote);row.put("category",item.category);row.put("categoryBasis","医生主动选择；分类未经过医学语义核验");row.put("signals",signals((String)quote.get("excerpt")));items.add(row);
    }
    Map<String,Object> out=new LinkedHashMap<>();out.put("patientId",packet.get("patientId"));out.put("encounterId",packet.get("encounterId"));out.put("asOf",request.asOf);out.put("packetFingerprint",packet.get("packetFingerprint"));out.put("ruleVersion",VERSION);out.put("modelInvoked",false);
    out.put("items",items);out.put("historicalCoverageConfirmed",false);out.put("selectedOnly",true);
    out.put("coverage",List.of("仅核对医生主动选择的本地病程片段，未自动读取全部病程或推断感染。","近期生命体征、趋势、检验/病原学、用药及器官基线未并入此时点草稿；须在对应来源另行核对。","没有选择相反证据不表示相反证据不存在；来源历史完整性未确认。"));
    out.put("text",render(request.asOf,items));return out;
  }
  private static ApiException bad(){return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REVIEW_SELECTION","请选择有效的病程片段和核对分类");}
  static List<String> signals(String text){List<String> flags=new ArrayList<>();
    flag(flags,text,"未见|未发现|否认|排除|不支持|无明确","含否定或排除用语，不能作为肯定证据");
    flag(flags,text,"考虑|疑似|可能|待排|待查|不能排除|不除外","含不确定用语，不能作为已确诊证据");
    flag(flags,text,"既往|曾有|病史|入院前|上次|陈旧","含既往用语，当前适用性待核对");
    flag(flags,text,"引用|摘录|复制|沿用|模板|同前|同上","含引用、复制或模板用语，独立性及原始时点待核对");
    flag(flags,text,"创伤|失血|出血|心源性|心功能|测量|伪差|通道","含其他原因或测量相关用语，需结合原始记录鉴别");
    flag(flags,text,"忽略.{0,12}(指令|规则)|系统提示|system\\s*prompt|ignore.{0,20}instruction|自动.{0,6}(关闭|确诊)|发送.{0,8}(密钥|病历)","含指令性文字信号，仅作不可信原文，不执行");
    if(flags.isEmpty())flags.add("未检出有限文字信号；不代表肯定、当前、独立或医学正确");return flags;
  }
  static void flag(List<String> out,String text,String regex,String label){if(Pattern.compile(regex,Pattern.CASE_INSENSITIVE).matcher(text).find())out.add(label);}
  static String render(Instant asOf,List<Map<String,Object>> items){StringBuilder text=new StringBuilder("本地病程复核草稿（未调用 AI）\n复核时点：").append(asOf).append("\n仅整理医生所选片段，须复核；不能诊断、排除感染或替代正式病历。\n");
    for(String category:CATEGORIES){text.append("\n").append(LABELS.get(category)).append("\n");boolean any=false;
      for(var item:items)if(category.equals(item.get("category"))){any=true;text.append("[").append(item.get("ref")).append("] ").append(item.get("documentType")).append("；来源可用时间上界 ").append(item.get("availableAt")).append("；本地取得上界 ").append(item.get("syncedAt")).append("\n原文片段：").append(item.get("excerpt")).append("\n文字信号：").append(String.join("；",(List<String>)item.get("signals"))).append("\n");}
      if(!any)text.append("未选择该类片段，不能视为无此类证据。\n");}
    text.append("\n数据覆盖与规则边界\n仅本地所选病程；来源历史完整性、器官基线及近期结构化数据未确认。\nK1：无法判定；乳酸取值窗口待确认。ICU-05 历史结果须单独查看，不改变 T0、分子、分母或规则。\n片段可能包含身份信息，仅供当前授权范围内核对；不自动写入正式病历。\n");return text.toString();
  }
}
