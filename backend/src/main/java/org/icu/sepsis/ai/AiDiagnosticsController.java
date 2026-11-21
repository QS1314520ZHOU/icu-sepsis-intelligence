package org.icu.sepsis.ai;
import java.time.*;import java.util.*;import org.icu.sepsis.api.Envelope;import org.springframework.web.bind.annotation.*;import org.springframework.security.access.prepost.PreAuthorize;
/** Server-local synthetic check, no patient data and no automatic clinical traffic. */
@RestController @RequestMapping("/api/v1/ai") public class AiDiagnosticsController {
 private final AiProperties props;private final LlmClient llm;private final KnowledgeBase kb;
 public AiDiagnosticsController(AiProperties p,LlmClient l,KnowledgeBase kb){props=p;llm=l;this.kb=kb;}
 @PostMapping("/diagnostics") @PreAuthorize("hasRole('DOCTOR')")
 public Envelope<?> check(){var checks=new ArrayList<Map<String,Object>>();if(!props.chatConfigured())checks.add(Map.of("kind","chat","status","not_configured"));for(var model:props.chatConfigured()?props.modelOrder("reasoning"):List.<String[]>of()){var c=new LinkedHashMap<String,Object>();c.put("kind",model[0]);c.put("model",model[1]);try{var r=llm.chat(model[1],List.of(new LlmClient.Message("system","仅输出简体中文测试响应，不使用任何患者资料。"),new LlmClient.Message("user","这是无患者数据的服务连通检查，请回答：测试成功。")),Math.min(15000,Integer.parseInt(model[2])),false,128,false);c.put("status",r.content==null||r.content.isBlank()?"empty_response":"passed");}catch(LlmClient.LlmException e){c.put("status","failed");c.put("errorKind",e.kind);}catch(RuntimeException e){c.put("status","failed");c.put("errorKind",probeError(e));}checks.add(c);}
 var embedding=new LinkedHashMap<String,Object>();embedding.put("kind","embedding");embedding.put("model",props.embeddingModel);if(props.embeddingConfigured())try{var v=llm.embed(List.of("无患者数据的指南检索连通测试"),10000);embedding.put("status","passed");embedding.put("dimensions",v.get(0).length);}catch(LlmClient.LlmException e){embedding.put("status","failed");embedding.put("errorKind",e.kind);}catch(RuntimeException e){embedding.put("status","failed");embedding.put("errorKind",probeError(e));}else embedding.put("status","not_configured");checks.add(embedding);
 return Envelope.ok(Map.of("asOf",Instant.now(),"checks",checks,"patientDataSent",false,"clinicalValidation",false,"indexStatus",kb.vectorStatus(),"cacheStatus",kb.vectorCacheStatus()));}
 private static String probeError(RuntimeException e){return e instanceof IllegalArgumentException||e instanceof IllegalStateException?"configuration_error":"probe_error";}
}
