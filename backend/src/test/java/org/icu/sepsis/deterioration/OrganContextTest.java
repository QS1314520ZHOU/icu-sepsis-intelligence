package org.icu.sepsis.deterioration;
import static org.junit.jupiter.api.Assertions.*;import java.time.*;import java.util.*;import org.icu.sepsis.domain.Domain.Observation;import org.junit.jupiter.api.Test;
class OrganContextTest {
 final Instant now=Instant.parse("2026-11-19T08:00:00Z");
 Observation row(String code,double value,String unit,int minutes){var o=new Observation();o.parameterCode=code;o.value=value;o.unit=unit;o.qualityStatus="valid";o.eventTime=now.minusSeconds(minutes*60);return o;}
 @Test void sfRequiresPairedExplicitUnitsAndDoesNotDiagnoseArds(){var r=OrganContext.respiratory(List.of(row("SPO2",94,"%",10),row("FIO2",50,"%",15)),now);assertEquals(188d,r.get("sfRatio"));assertEquals("not_assessed",r.get("diagnosisStatus"));}
 @Test void highSaturationSkewUnitsAndConflictPreventSf(){for(List<Observation> rows:List.of(List.of(row("SPO2",99,"%",10),row("FIO2",50,"%",10)),List.of(row("SPO2",94,"%",2),row("FIO2",50,"%",30)),List.of(row("SPO2",94,"%",10),row("FIO2",.5,"fraction",10)),List.of(row("SPO2",94,"%",10),row("FIO2",50,"%",10),row("FIO2",80,"%",10))))assertNull(OrganContext.respiratory(rows,now).get("sfRatio"));}
 @Test void negativeRassProvidesContextWithoutAttributingCause(){var r=OrganContext.neurologic(List.of(row("RASS",-3,"score",10)),now);assertEquals("sedation_context",r.get("sedationStatus"));assertTrue(r.get("detail").toString().contains("不能直接归因感染"));assertEquals("unknown",OrganContext.neurologic(List.of(row("RASS",-3,"score",300)),now).get("sedationStatus"));}
 @Test void newerInvalidValueCannotSilentlyReuseOlderValidValue(){var invalid=row("RASS",99,"score",1);invalid.qualityStatus="invalid";assertNull(OrganContext.latest(List.of(row("RASS",0,"score",10),invalid),"RASS",now,Duration.ofHours(4)));}
}
