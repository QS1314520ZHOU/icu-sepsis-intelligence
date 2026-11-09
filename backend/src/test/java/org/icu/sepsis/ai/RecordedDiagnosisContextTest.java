package org.icu.sepsis.ai;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;import java.util.Map;
import org.junit.jupiter.api.Test;
class RecordedDiagnosisContextTest {
 @Test void sourceDiagnosisRemainsAvailableWhenVitalsFailAndKeepsQualifiers(){
  var builder=new PatientContextBuilder(new AiProperties(false,"","","/v1/chat/completions","/v1/embeddings","","","",1000,1000,1000,800,2,0.2,true,false,"",false,"","Asia/Shanghai"));
  var facts=builder.build(Map.of("available",false,"message","观测暂不可用","recordedDiagnosis",Map.of("text","考虑脓毒性休克；既往感染性休克待核对","source","合成临床诊断")),null,Instant.now());
  var fact=facts.stream().filter(f->"源诊断记录".equals(f.category)).findFirst().orElseThrow();
  assertTrue(fact.text.contains("考虑脓毒性休克；既往感染性休克待核对"));
  assertTrue(fact.text.contains("时间未确认"));assertTrue(fact.text.contains("不替代"));assertNull(fact.time);
 }
 @Test void lactateFactsContainLatestPeakAndChronologicalSequence(){var builder=new PatientContextBuilder(new AiProperties(false,"","","/v1/chat/completions","/v1/embeddings","","","",1000,1000,1000,800,2,0.2,true,false,"",false,"","Asia/Shanghai"));var now=Instant.parse("2026-11-08T08:00:00Z");var rows=new java.util.ArrayList<org.icu.sepsis.domain.Domain.Observation>();for(int i=0;i<3;i++){var o=new org.icu.sepsis.domain.Domain.Observation();o.parameterCode="LACTATE";o.value=new double[]{5.8,1.7,3.1}[i];o.unit="mmol/L";o.qualityStatus="valid";o.eventTime=now.minusSeconds((3-i)*3600);rows.add(o);}var facts=new java.util.ArrayList<PatientContextBuilder.Fact>();builder.trends(facts,rows,now);var sequence=facts.stream().filter(f->"乳酸时序".equals(f.category)).findFirst().orElseThrow().text;assertTrue(sequence.indexOf("5.8")<sequence.indexOf("1.7"));assertTrue(sequence.indexOf("1.7")<sequence.indexOf("3.1"));assertTrue(facts.stream().anyMatch(f->f.text.contains("最近 3.1")&&f.text.contains("最高 5.8")));}
}
