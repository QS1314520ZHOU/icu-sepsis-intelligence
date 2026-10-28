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
  @Test void reportLookupIsBoundedAndDoesNotPretendUniqueEncounter(){rows();ArgumentCaptor<Query> q=ArgumentCaptor.forClass(Query.class);verify(dc).find(q.capture(),eq(Document.class),eq("VI_ICU_EXAM_ITEM"));assertEquals("SYNTHETIC_HIS",q.getValue().getQueryObject().get("hisPid"));assertEquals(400,q.getValue().getLimit());assertTrue(q.getValue().getFieldsObject().containsKey("resultStatus"));}
  @Test void absentHisKeyDoesNotFallbackToMrn(){patient.remove("hisPid");assertTrue(rows().isEmpty());verifyNoInteractions(dc);}
  @Test void medicationAndFluidHelpersFailExplicitlyRatherThanManufactureCompletion(){assertEquals("SOURCE_EXECUTION_UNVERIFIED",assertThrows(ApiException.class,()->reader.findDrugEvent(id,now,now,List.of("test"))).code);assertThrows(ApiException.class,()->reader.findOrderEvent("H",now,now,List.of("test")));assertThrows(ApiException.class,()->reader.fluidVolumeMl(id,now,now));verifyNoInteractions(sc,dc);}
  @Test void sourceMetadataSurvivesApiView(){labs(lab("4.2","mmol/L"));Map<String,Object> v=Views.observation(rows().get(0));assertEquals("reported",v.get("timeBasis"));assertEquals("unconfirmed",v.get("encounterLinkStatus"));assertEquals("context_only",v.get("clinicalUse"));assertTrue(v.containsKey("sampledAt"));assertNull(v.get("sampledAt"));}
}
