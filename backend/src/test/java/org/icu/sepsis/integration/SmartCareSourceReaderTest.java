package org.icu.sepsis.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.icu.sepsis.api.ApiException;
import org.icu.sepsis.api.Views;
import org.icu.sepsis.config.RuleConfig;
import org.icu.sepsis.domain.ClinicalRules;
import org.icu.sepsis.domain.Domain.Observation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/** Synthetic documents only. No connection or writes to SmartCare / DataCenter. */
class SmartCareSourceReaderTest {
  final MongoTemplate sc=mock(MongoTemplate.class), dc=mock(MongoTemplate.class);
  final SmartCareSourceReader reader=new SmartCareSourceReader(sc,dc);
  final Instant now=Instant.parse("2026-10-28T08:00:00Z");
  final String id="aaaaaaaaaaaaaaaaaaaaaaaa";
  Document patient;
  @BeforeEach void setup(){
    patient=new Document("_id",new ObjectId(id)).append("mrn","SYNTHETIC_STAY").append("hisPid","SYNTHETIC_HIS")
        .append("icuAdmissionTime",Date.from(now.minusSeconds(3600)));
    when(sc.findOne(any(Query.class),eq(Document.class),eq("patient"))).thenAnswer(a->patient);
    when(sc.find(any(Query.class),eq(Document.class),anyString())).thenReturn(List.of());
    when(dc.find(any(Query.class),eq(Document.class),anyString())).thenReturn(List.of());
  }
  List<Observation> rows(){return reader.observations(id,SmartCareSourceReader.encounterIdOf(patient),"SYNTHETIC_DEPT",now.minusSeconds(7200),now);}
  Document bedside(String code,String value){return new Document("_id",new ObjectId()).append("code",code).append("strVal",value).append("valid",true).append("time",Date.from(now.minusSeconds(60)));}
  Document lab(String result,String unit){return new Document("_id",new ObjectId()).append("itemCode","LAC1").append("result",result).append("unit",unit).append("authTime",Date.from(now.minusSeconds(60)));}
  void beds(Document... documents){when(sc.find(any(Query.class),eq(Document.class),eq("bedside"))).thenReturn(List.of(documents));}
  void labs(Document... documents){when(dc.find(any(Query.class),eq(Document.class),eq("VI_ICU_EXAM_ITEM"))).thenReturn(List.of(documents));}
  @Test void pressureChannelsNeverCollapse(){beds(bedside("param_ibp_m","58"),bedside("param_nibp_m","76"));List<Observation> os=rows();assertEquals(2,os.size());assertEquals(Set.of("MAP_IBP","MAP_NIBP"),new HashSet<>(List.of(os.get(0).parameterCode,os.get(1).parameterCode)));assertEquals("IBP",os.get(0).measurementChannel);assertEquals("NIBP",os.get(1).measurementChannel);assertEquals("mmHg",os.get(0).unit);assertNotNull(os.get(0).id);}
  @Test void sourceIngestionIsNotFabricatedFromEvent(){beds(bedside("param_HR","90"));Observation o=rows().get(0);assertNull(o.ingestTime);assertNull(o.createdAt);assertEquals("unknown",o.ingestTimeBasis);assertTrue(new ClinicalRules(new RuleConfig()).valid(o,now));}
  @Test void reportIsNotSamplingOrVerifiedEncounter(){labs(lab("4.2","mmol/L"));Observation o=rows().get(0);assertEquals("reported",o.timeBasis);assertEquals(o.eventTime,o.reportedAt);assertNull(o.sampledAt);assertNull(o.encounterId);assertEquals("unconfirmed",o.encounterLinkStatus);assertEquals("context_only",o.clinicalUse);assertEquals("unverified",o.qualityStatus);assertNull(o.sourceResultStatus);assertFalse(new ClinicalRules(new RuleConfig()).valid(o,now));}
  @Test void labUnitNeverDefaultsToMmol(){labs(lab("4.2",null));assertNull(rows().get(0).unit);}
  @Test void abnormalFlagDoesNotBecomeValidResult(){labs(lab("4.2","mmol/L").append("almFlag","N").append("seriousFlag","0").append("resultStatus","unmapped"));Observation o=rows().get(0);assertEquals("unverified",o.qualityStatus);assertEquals("unmapped",o.sourceResultStatus);}
  @Test void censoredOrSuffixedValuesDoNotBecomeExactNumbers(){labs(lab("4.2 mmol/L","mmol/L"),lab(">4.2","mmol/L"),lab("NaN","mmol/L"));assertTrue(rows().isEmpty());}
  @Test void staleEncounterRejectsBeforeClinicalQueries(){ApiException e=assertThrows(ApiException.class,()->reader.observations(id,"OLD_STAY","D",now.minusSeconds(60),now));assertEquals("STATE_CONFLICT",e.code);verifyNoInteractions(dc);verify(sc,never()).find(any(Query.class),eq(Document.class),anyString());}
  @Test void unknownAdmissionDoesNotInventAWindow(){patient.remove("icuAdmissionTime");ApiException e=assertThrows(ApiException.class,()->rows());assertEquals("ENCOUNTER_BOUNDARY_UNCONFIRMED",e.code);verifyNoInteractions(dc);}
  @Test void bedsideWindowClampsToCurrentAdmissionAndHasProjection(){rows();ArgumentCaptor<Query> q=ArgumentCaptor.forClass(Query.class);verify(sc).find(q.capture(),eq(Document.class),eq("bedside"));Document time=(Document)q.getValue().getQueryObject().get("time");assertEquals(patient.get("icuAdmissionTime"),time.get("$gte"));assertEquals(4000,q.getValue().getLimit());assertTrue(q.getValue().getFieldsObject().containsKey("strVal"));assertFalse(q.getValue().getFieldsObject().containsKey("name"));}
  @Test void reportLookupIsBoundedAndDoesNotPretendUniqueEncounter(){rows();ArgumentCaptor<Query> q=ArgumentCaptor.forClass(Query.class);verify(dc,times(2)).find(q.capture(),eq(Document.class),eq("VI_ICU_EXAM_ITEM"));for(Query x:q.getAllValues()){assertEquals("SYNTHETIC_HIS",x.getQueryObject().get("hisPid"));assertEquals(400,x.getLimit());assertTrue(x.getFieldsObject().containsKey("resultStatus"));}}
  @Test void absentHisKeyDoesNotFallbackToMrn(){patient.remove("hisPid");assertTrue(rows().isEmpty());verifyNoInteractions(dc);}
  @Test void medicationAndFluidHelpersFailExplicitlyRatherThanManufactureCompletion(){assertEquals("SOURCE_EXECUTION_UNVERIFIED",assertThrows(ApiException.class,()->reader.findDrugEvent(id,now,now,List.of("test"))).code);assertThrows(ApiException.class,()->reader.findOrderEvent("H",now,now,List.of("test")));assertThrows(ApiException.class,()->reader.fluidVolumeMl(id,now,now));verifyNoInteractions(sc,dc);}
  @Test void sourceMetadataSurvivesApiView(){labs(lab("4.2","mmol/L"));Map<String,Object> v=Views.observation(rows().get(0));assertEquals("reported",v.get("timeBasis"));assertEquals("unconfirmed",v.get("encounterLinkStatus"));assertEquals("context_only",v.get("clinicalUse"));assertTrue(v.containsKey("sampledAt"));assertNull(v.get("sampledAt"));}
 Document gas(String code,double value,String unit,Instant at){return new Document("code",code).append("fVal",value).append("unit",unit).append("valid","valid").append("time",Date.from(at));}
 void gases(Document... points){when(sc.find(any(Query.class),eq(Document.class),eq("bGATemp"))).thenReturn(List.of(new Document("_id",new ObjectId()).append("bedsides",List.of(points))));}
 @Test void bloodGasLactateIsReadWithPfAndOriginalSource(){gases(gas("param_bg_Lac",5.8,"mmol/L",now.minusSeconds(60)),gas("param_bg_P/Fratio",173,"mmHg",now.minusSeconds(60)));var values=rows();assertEquals(2,values.size());assertNotEquals(values.get(0).id,values.get(1).id);var lactate=values.stream().filter(o->"LACTATE".equals(o.parameterCode)).findFirst().orElseThrow();assertEquals(5.8,lactate.value);assertEquals("SmartCare.bGATemp",lactate.sourceSystem);assertEquals("param_bg_Lac",lactate.sourceParameterCode);assertEquals("mmol/L",lactate.unit);assertTrue(new ClinicalRules(new RuleConfig()).valid(lactate,now));}
 @Test void historicalAndFutureBloodGasCannotBeMixedIntoCurrentValues(){gases(gas("param_bg_Lac",5.8,"mmol/L",now.minusSeconds(40*86400)),gas("param_bg_Lac",3,"mmol/L",now.plusSeconds(60)));assertTrue(rows().isEmpty());}
 @Test void missingBloodGasUnitIsVisibleButDoesNotBecomeConfirmedNormal(){gases(gas("param_bg_Lac",5.8,null,now.minusSeconds(60)));var lactate=rows().get(0);assertEquals(5.8,lactate.value);assertNull(lactate.unit);assertEquals("context_only",lactate.clinicalUse);assertTrue(lactate.qualityNote.contains("单位"));assertFalse(new ClinicalRules(new RuleConfig()).valid(lactate,now));}
 @Test void hospitalParameterDefinitionProvidesMissingGasUnitAndPrecision(){when(sc.findOne(any(Query.class),eq(Document.class),eq("configParam"))).thenReturn(new Document("unit","mmol/L").append("floatCount",1));gases(gas("param_bg_Lac",0.8999999761581421,null,now.minusSeconds(60)).append("strVal","0.9"));var lactate=rows().get(0);assertEquals(0.9,lactate.value);assertEquals(0.8999999761581421,lactate.rawNumericValue);assertEquals("0.9",lactate.reportedNumericText);assertEquals("mmol/L",lactate.unit);assertEquals("parameter_dictionary",lactate.unitBasis);assertEquals(1,lactate.displayPrecision);assertTrue(new ClinicalRules(new RuleConfig()).valid(lactate,now));assertEquals(0.8999999761581421,Views.observation(lactate).get("rawNumericValue"));}
 @Test void contradictoryAndCensoredGasValuesAreNotMadeIntoExactReadings(){gases(gas("param_bg_Lac",2.1,"mmol/L",now.minusSeconds(60)).append("strVal","2.2"),gas("param_bg_Lac",2.1,"mmol/L",now.minusSeconds(60)).append("strVal",">2.1"));assertTrue(rows().isEmpty());}
 @Test void consciousnessIsReadAsOriginalTextAndNeverInferredFromSedation(){beds(bedside("param_Yishi","镇静"));var r=rows();assertEquals(1,r.size());assertEquals("AVPU",r.get(0).parameterCode);assertEquals("镇静",r.get(0).text);assertNull(r.get(0).value);assertEquals("SmartCare.bedside",r.get(0).sourceSystem);}
 @Test void birthDateSupportsAdultScopeWithoutLeakingThroughPatientJson(){patient.put("birthday",Date.from(Instant.parse("1980-01-01T00:00:00Z")));var p=reader.loadPatient(id);assertEquals(LocalDate.of(1980,1,1),p.birthDate);assertFalse(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().valueToTree(p).has("birthDate"));assertFalse(Views.patient(p).containsKey("birthDate"));}
 @Test void nonNumericLatestVitalIsKeptInvalidRatherThanSilentlyUsingAnOlderReading(){beds(bedside("param_T",">38.5"));var row=rows().get(0);assertNull(row.value);assertEquals("invalid",row.qualityStatus);assertEquals(">38.5",row.text);}
}
