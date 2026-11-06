package org.icu.sepsis.ai;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;import java.util.*;import org.junit.jupiter.api.Test;
import org.icu.sepsis.domain.Domain.Observation;
class PatientContextTrendTest {
 final Instant now=Instant.parse("2026-11-06T01:00:00Z");final PatientContextBuilder builder=new PatientContextBuilder(KnowledgeBaseTest.props(""));
 Observation point(double value,String unit,int hours){var p=new Observation();p.parameterCode="LACTATE";p.value=value;p.unit=unit;p.eventTime=now.minusSeconds(hours*3600L);p.qualityStatus="valid";return p;}
 List<PatientContextBuilder.Fact> trends(Observation... rows){List<PatientContextBuilder.Fact> out=new ArrayList<>();builder.trends(out,List.of(rows),now);return out;}
 String text(List<PatientContextBuilder.Fact> facts){return facts.stream().map(f->f.text).reduce("",(a,b)->a+"\n"+b);}
 @Test void unitsAndConflictingTimesCannotBeMerged(){assertTrue(text(trends(point(2,"mmol/L",2),point(4,"mg/dL",1))).contains("单位缺失或不一致"));assertTrue(text(trends(point(2,"mmol/L",1),point(4,"mmol/L",1))).contains("同时点"));}
 @Test void outOfRangeLateAndNonFiniteValuesCannotEnterTrend(){var late=point(99,"mmol/L",1);late.ingestTime=now.plusSeconds(1);String text=text(trends(point(2,"mmol/L",2),point(4,"mmol/L",1),late,point(88,"mmol/L",25),point(Double.NaN,"mmol/L",1)));assertTrue(text.contains("2 次"));assertFalse(text.contains("99"));assertFalse(text.contains("88"));assertFalse(text.contains("NaN"));}
 @Test void duplicatesCannotInflateCountsOrInventUrineTotals(){var a=point(2,"mmol/L",2);var b=point(4,"mmol/L",1);assertTrue(text(trends(a,a,b)).contains("2 次"));a.parameterCode="URINE_OUTPUT";assertTrue(text(trends(a)).contains("不自动累加"));}
}
