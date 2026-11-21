package org.icu.sepsis.deterioration;
import java.util.*;
/** Combines independent evidence paths without treating a missing path as a negative. */
final class RenalReview {
 static Map<String,Object> combine(Map<String,Object> creatinine,Map<String,Object> urine){
  String cr=String.valueOf(creatinine.get("status")),u=String.valueOf(urine.get("status"));
  String status="candidate".equals(cr)||"candidate".equals(u)?"candidate":Boolean.TRUE.equals(creatinine.get("historicalCandidate"))?"prior_candidate":"not_triggered".equals(cr)&&"not_triggered".equals(u)?"no_observed_trigger":"unknown";
  String detail="candidate".equals(status)?"肌酐或尿量存在候选异常，建议联合复核":"prior_candidate".equals(status)?"此前肌酐候选仍需复核，末次下降不能确认恢复":"no_observed_trigger".equals(status)?"当前两个已评估路径未触发；不等于排除AKI":"至少一个路径证据不足，不能合并判为未触发";
  var out=new LinkedHashMap<String,Object>();out.put("status",status);out.put("detail",detail);out.put("creatinineStatus",cr);out.put("urineStatus",u);out.put("diagnosisConfirmed",false);return out;
 }
}
