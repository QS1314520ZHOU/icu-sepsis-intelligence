package org.icu.sepsis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.icu.sepsis.config.RuleConfig;
import org.icu.sepsis.domain.ClinicalRules;
import org.icu.sepsis.domain.Domain.*;
import org.icu.sepsis.integration.*;
import org.icu.sepsis.sepsis.*;
import org.springframework.beans.factory.ObjectProvider;

class ClinicalReviewTest {
  final Instant now=Instant.parse("2026-10-28T08:00:00Z");
  final ClinicalDataProvider provider=mock(ClinicalDataProvider.class);
  @SuppressWarnings("unchecked") final ObjectProvider<SmartCareSourceReader> reader=mock(ObjectProvider.class);
  final RuleConfig cfg=new RuleConfig();
  final SepsisAssessmentService service=new SepsisAssessmentService(provider,new ClinicalRules(cfg),cfg,reader,"demo",mock(ObjectProvider.class));
  Patient patient(){Patient p=new Patient();p.id="aaaaaaaaaaaaaaaaaaaaaaaa";p.encounterId="stay-1";p.simulated=true;p.department="TEST";return p;}
  Observation obs(String code,double value,String unit,Instant at){Observation o=new Observation();o.parameterCode=code;o.value=value;o.unit=unit;o.eventTime=at;o.ingestTime=at;o.qualityStatus="valid";o.sourceSystem="TEST";o.sourceRecordId="e-"+code;return o;}
  Map<String,Object> run(List<Observation> rows){when(provider.observations(anyString(),anyString(),any(),any())).thenReturn(rows);return service.assess(patient(),now);}
  @Test void unavailableNeverBecomesLowRisk(){when(provider.observations(anyString(),anyString(),any(),any())).thenThrow(new IllegalStateException());Map<String,Object> r=service.assess(patient(),now);assertEquals(false,r.get("available"));assertEquals("unavailable",r.get("screeningStatus"));assertNull(r.get("qsofa"));assertFalse(r.containsKey("risk"));}
  @Test void emptySourceIsInsufficientRatherThanZero(){Map<String,Object> r=run(List.of());assertEquals("insufficient_data",r.get("screeningStatus"));assertNull(r.get("qsofa"));assertEquals(8,((List<?>)r.get("missingFeatures")).size());}
  @Test void pressorRecordDoesNotConfirmSepticShock(){Map<String,Object> r=run(List.of(obs("VASOPRESSOR_ACTIVE",1,"boolean",now)));assertEquals(true,r.get("vasopressorActive"));assertFalse(r.containsKey("shock"));assertEquals("insufficient_data",r.get("screeningStatus"));}
  @Test void latestFreshEvidenceNotHistoricalExtrema(){Map<String,Object> r=run(List.of(obs("MAP",40,"mmHg",now.minus(Duration.ofHours(72))),obs("MAP",78,"mmHg",now.minusSeconds(60))));assertTrue(((List<?>)r.get("findings")).isEmpty());}
  @Test void mapsPressureAndLactateWithoutKeyLoss(){Map<String,Object> r=run(List.of(obs("MAP",58,"mmHg",now),obs("LACTATE",3.5,"mmol/L",now)));assertEquals("review_required",r.get("screeningStatus"));assertEquals(2,((List<?>)r.get("findings")).size());}
  @Test void allQsofaInputsRequired(){assertNull(SepsisClinicalRules.qSofa(null,null,null));assertNull(SepsisClinicalRules.qSofa(85.0,28.0,null));assertEquals(3,SepsisClinicalRules.qSofa(85.0,28.0,14.0));}
  @Test void invalidUnitsNeverDriveClassification(){Map<String,Object> r=run(List.of(obs("LACTATE",30,"mg/dL",now)));assertTrue(((List<?>)r.get("findings")).isEmpty());}
  @Test void futureIngestDoesNotLeakIntoReview(){Observation o=obs("MAP",58,"mmHg",now);o.ingestTime=now.plusSeconds(1);assertTrue(((List<?>)run(List.of(o)).get("findings")).isEmpty());}
  @Test void conflictingSameTimeReadingsDoNotPickRandomValue(){Map<String,Object> r=run(List.of(obs("MAP",58,"mmHg",now),obs("MAP",78,"mmHg",now)));assertTrue(((List<?>)r.get("findings")).isEmpty());assertTrue(((List<?>)r.get("missingFeatures")).contains("MAP"));}
  @Test void negativeAndHistoricalDiagnosisAreNotPositiveClues(){assertFalse(SepsisClinicalRules.infectionText("排除肺炎，无感染证据"));assertFalse(SepsisClinicalRules.infectionText("既往肺炎"));assertTrue(SepsisClinicalRules.infectionText("肺炎待排"));assertTrue(SepsisClinicalRules.infectionText("不能排除感染"));}
  @Test void eventTimingRequiresAnchorAndDeadline(){assertEquals("unknown",BundleModels.eventStatus(now,null,now.plusSeconds(3600)));assertEquals("recorded_late",BundleModels.eventStatus(now.plusSeconds(3601),now,now.plusSeconds(3600)));assertEquals("before_anchor",BundleModels.eventStatus(now.minusSeconds(1),now,now.plusSeconds(3600)));assertEquals("recorded_in_window",BundleModels.eventStatus(now.plusSeconds(3600),now,now.plusSeconds(3600)));}
  @Test void noHardCodedCompletedReassessmentOrTreatmentTargets(){Map<String,Object> r=run(List.of(obs("LACTATE",1.5,"mmol/L",now)));String checklist=r.get("checklist").toString();assertTrue(checklist.contains("REASSESS"));assertFalse(checklist.contains("status=completed"));assertFalse(checklist.contains("1500"));assertFalse(r.containsKey("bundle"));}

  @Test void channelsRetainIndependentFindingsAndDoNotSelectQsofaPressure(){
    Map<String,Object> r=run(List.of(obs("MAP_IBP",58,"mmHg",now),obs("MAP_NIBP",76,"mmHg",now),obs("SBP_NIBP",85,"mmHg",now),obs("RR",28,"/min",now),obs("GCS",14,"score",now)));
    assertEquals(true,r.get("pressureSelectionRequired"));assertEquals(3,((List<?>)r.get("pressureEvidence")).size());assertNull(r.get("qsofa"));assertFalse(((List<?>)r.get("missingFeatures")).contains("SBP"),"channel SBP is present, only unselected");
    assertTrue(r.get("findings").toString().contains("MAP_IBP"));assertFalse(r.get("findings").toString().contains("code=MAP_NIBP"));
  }
  @Test void genericPressureCannotOverrideExplicitChannel(){
    Map<String,Object> r=run(List.of(obs("SBP",120,"mmHg",now),obs("SBP_IBP",80,"mmHg",now),obs("RR",28,"/min",now),obs("GCS",14,"score",now)));
    assertNull(r.get("qsofa"));assertFalse(r.get("latestEvidence").toString().contains("code=SBP,"));
  }
  @Test void sameChannelConflictDoesNotEraseOtherChannel(){
    Map<String,Object> r=run(List.of(obs("MAP_IBP",58,"mmHg",now),obs("MAP_IBP",68,"mmHg",now),obs("MAP_NIBP",76,"mmHg",now)));
    assertEquals(1,((List<?>)r.get("pressureEvidence")).size());assertTrue(r.get("pressureEvidence").toString().contains("MAP_NIBP"));
  }
  @Test void candidateReportIsContextOnlyNotFreshConfirmedLactate(){
    Observation lac=obs("LACTATE",4.2,"mmol/L",now);lac.clinicalUse="context_only";lac.encounterLinkStatus="unconfirmed";lac.timeBasis="reported";lac.reportedAt=now;
    Map<String,Object> r=run(List.of(lac));assertTrue(((List<?>)r.get("findings")).isEmpty());assertEquals(1,((List<?>)r.get("contextEvidence")).size());assertTrue(((List<?>)r.get("missingFeatures")).contains("LACTATE_UNCONFIRMED"));assertFalse(((List<?>)r.get("missingFeatures")).contains("LACTATE"));assertFalse(r.get("checklist").toString().contains("status=recorded"));
  }
  @Test void newestSameTimeConflictIsReportedNotReplacedByOlderValue(){
    Map<String,Object> r=run(List.of(obs("MAP",58,"mmHg",now.minusSeconds(600)),obs("MAP",80,"mmHg",now),obs("MAP",62,"mmHg",now)));
    assertTrue(((List<?>)r.get("findings")).isEmpty());assertEquals(List.of("MAP"),r.get("conflictingFeatures"));assertTrue(r.get("sourceWarnings").toString().contains("同刻不一致"));
  }
  @Test void staleLactateIsMissingNotUnconfirmed(){
    Map<String,Object> r=run(List.of(obs("LACTATE",3.1,"mmol/L",now.minus(Duration.ofHours(20)))));
    assertTrue(((List<?>)r.get("missingFeatures")).contains("LACTATE"));assertFalse(((List<?>)r.get("missingFeatures")).contains("LACTATE_UNCONFIRMED"));
  }
 @Test void adultScopeIsReloadedFromMatchingSourceWithoutPersistingDob(){var p=patient();var loaded=patient();loaded.birthDate=LocalDate.of(1980,1,1);var source=mock(SmartCareSourceReader.class);when(reader.getIfAvailable()).thenReturn(source);when(source.loadPatient(p.id)).thenReturn(loaded);var svc=new SepsisAssessmentService(provider,new ClinicalRules(cfg),cfg,reader,"smartcare",mock(ObjectProvider.class));var result=svc.assess(p,now);assertEquals("adult",((Map<?,?>)result.get("earlyWarning")).get("population"));assertNull(p.birthDate);}
 @Test void ageCannotBeBorrowedFromDifferentStayOrUnavailableSource(){for(boolean mismatch:List.of(true,false)){var p=patient();var source=mock(SmartCareSourceReader.class);when(reader.getIfAvailable()).thenReturn(source);var loaded=patient();loaded.encounterId="other-stay";loaded.birthDate=LocalDate.of(1980,1,1);if(mismatch)when(source.loadPatient(p.id)).thenReturn(loaded);else when(source.loadPatient(p.id)).thenThrow(new IllegalStateException("private-source-detail"));var svc=new SepsisAssessmentService(provider,new ClinicalRules(cfg),cfg,reader,"smartcare",mock(ObjectProvider.class));var result=svc.assess(p,now);assertEquals("unknown",((Map<?,?>)result.get("earlyWarning")).get("population"));assertNull(p.birthDate);}}
  @Test void sourceDiagnosisSurvivesUnavailableVitalsWithoutChangingScreening(){
    var source=mock(SmartCareSourceReader.class);when(reader.getIfAvailable()).thenReturn(source);
    when(source.loadPatient(patient().id)).thenReturn(patient());
    when(source.diagnosisText(patient().id)).thenReturn("考虑脓毒性休克；既往感染性休克，当前待排");
    when(provider.observations(anyString(),anyString(),any(),any())).thenThrow(new IllegalStateException());
    var result=service.assess(patient(),now);
    assertEquals("unavailable",result.get("screeningStatus"));
    var recorded=(Map<?,?>)result.get("recordedDiagnosis");
    assertEquals("考虑脓毒性休克；既往感染性休克，当前待排",recorded.get("text"));
    assertEquals(false,recorded.get("timeConfirmed"));assertFalse(result.containsKey("bundle"));
  }
  @Test void diagnosisFromDifferentStayIsNeverBorrowed(){
    var source=mock(SmartCareSourceReader.class);when(reader.getIfAvailable()).thenReturn(source);
    var other=patient();other.encounterId="other";when(source.loadPatient(patient().id)).thenReturn(other);
    assertFalse(run(List.of()).containsKey("recordedDiagnosis"));verify(source,never()).diagnosisText(anyString());
  }
  @Test void currentWarningContractUsesExistingAlertClassifierAndNotDisplayColors(){
    var isolated=run(List.of(obs("GCS",2,"score",now)));assertTrue(((List<?>)isolated.get("currentWarnings")).isEmpty());
    var shock=run(List.of(obs("LACTATE",3.5,"mmol/L",now),obs("MAP",58,"mmHg",now)));
    var warnings=(List<?>)shock.get("currentWarnings");assertEquals(1,warnings.size());assertEquals("suspected_shock",((Map<?,?>)warnings.get(0)).get("kind"));
  }
  @Test void validCurrentLactateIsNotBlanketMarkedUnconfirmed(){var svc=new SepsisAssessmentService(provider,new ClinicalRules(cfg),cfg,reader,"smartcare",mock(ObjectProvider.class));when(provider.observations(anyString(),anyString(),any(),any())).thenReturn(List.of(obs("LACTATE",2.3,"mmol/L",now)));var result=svc.assess(patient(),now);assertTrue(result.get("latestEvidence").toString().contains("LACTATE"));assertFalse(result.get("sourceWarnings").toString().contains("本次住院归属待核对"));}
  @Test void reportRestrictionDoesNotInvalidateConfirmedBloodGasLactate(){var report=obs("LACTATE",3.1,"mmol/L",now);report.encounterLinkStatus="unconfirmed";report.clinicalUse="context_only";var result=run(List.of(obs("LACTATE",2.3,"mmol/L",now),report));assertTrue(result.get("sourceWarnings").toString().contains("不代表已关联的血气乳酸无效"));assertTrue(result.get("latestEvidence").toString().contains("value=2.3"));}
}
