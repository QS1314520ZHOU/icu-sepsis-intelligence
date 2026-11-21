package org.icu.sepsis.deterioration;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;import java.util.*;
import com.mongodb.client.MongoClients;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.icu.sepsis.application.Store;
import org.icu.sepsis.domain.Domain.*;
import org.icu.sepsis.integration.ClinicalDataProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Uses its own newly created synthetic database. SmartCare and DataCenter are never written. */
@EnabledIfEnvironmentVariable(named="LOCAL_MONGO_TESTS",matches="1")
class LocalMongoLabReviewTest {
 @Test void realPersistenceRetainsConfirmationWithdrawalAndSameMillisecondOrder() throws Exception {
  String database="icu_sepsis_devtest_"+UUID.randomUUID().toString().replace("-","");
  try(var client=MongoClients.create("mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=3000")) {
   boolean ownsDatabase=false;
   try {
    assertFalse(client.listDatabaseNames().into(new ArrayList<>()).contains(database));ownsDatabase=true;
    var db=new MongoTemplate(client,database);var store=new Store(db,"TEST",new ObjectMapper().registerModule(new JavaTimeModule()));
    Instant now=Instant.parse("2026-11-19T08:00:00Z");var p=new Patient();p.id="synthetic-p";p.encounterId="synthetic-stay";p.department="TEST";p.admittedAt=now.minus(Duration.ofDays(2));
    var lab=new Observation();lab.patientId=p.id;lab.encounterId=p.encounterId;lab.department=p.department;
    lab.parameterCode="CREA";lab.value=140d;lab.unit="μmol/L";lab.sourceSystem="synthetic";lab.sourceRecordId="synthetic-report";
    lab.eventTime=now.minusSeconds(1800);lab.qualityStatus="unverified";lab.clinicalUse="context_only";lab.encounterLinkStatus="unconfirmed";
    ClinicalDataProvider data=(id,stay,from,to)->List.of(lab);var service=new ReviewedLabEvidenceService(store,data);
    var req=new ReviewedLabEvidenceService.Request();req.encounterId=p.encounterId;req.sourceRecordId=lab.sourceRecordId;req.parameterCode="CREA";
    req.fingerprint=service.fingerprint(lab);req.accepted=true;req.observedAt=now.minusSeconds(3600);req.reason="Synthetic confirmation";
    var first=service.record(p,req,"synthetic-doctor",now);assertEquals(1,first.sequence);
    assertEquals("physician_confirmed",service.apply(p,List.of(lab),now).get(0).encounterLinkStatus);
    assertThrows(org.icu.sepsis.api.ApiException.class,()->service.record(p,req,"stale-doctor",now));req.expectedId=first.id;req.accepted=false;req.reason="Synthetic withdrawal";var second=service.record(p,req,"synthetic-doctor",now);assertEquals(2,second.sequence);
    assertEquals("context_only",service.apply(p,List.of(lab),now).get(0).clinicalUse);
    assertEquals("withdrawn",service.pending(p,List.of(lab),now).get(0).get("reviewState"));
    assertEquals(2,db.findAll(ReviewedLabEvidenceService.Review.class).size());
    req.expectedId=second.id;req.accepted=true;var third=service.record(p,req,"synthetic-doctor",now);
    db.updateMulti(new org.springframework.data.mongodb.core.query.Query(),new org.springframework.data.mongodb.core.query.Update().unset("headId"),ReviewedLabEvidenceService.Sequence.class);
    req.expectedId=third.id;var migrated=service.record(p,req,"synthetic-doctor",now);assertEquals(4,migrated.sequence);assertEquals(migrated.id,service.pending(p,List.of(lab),now).get(0).get("reviewId"));
    req.expectedId=migrated.id;var start=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
    try{java.util.concurrent.Callable<Boolean> attempt=()->{start.await();try{service.record(p,req,"concurrent-doctor",now);return true;}catch(org.icu.sepsis.api.ApiException conflict){return false;}};var left=pool.submit(attempt);var right=pool.submit(attempt);start.countDown();int successes=(left.get(5,java.util.concurrent.TimeUnit.SECONDS)?1:0)+(right.get(5,java.util.concurrent.TimeUnit.SECONDS)?1:0);assertEquals(1,successes);assertEquals(5,db.findAll(ReviewedLabEvidenceService.Review.class).size());}finally{pool.shutdownNow();}
    lab.value=160d;
    assertEquals("source_changed",service.pending(p,List.of(lab),now).get(0).get("reviewState"));
    assertEquals("context_only",service.apply(p,List.of(lab),now).get(0).clinicalUse);
    System.out.println("LOCAL_SYNTHETIC_REVIEW: persisted confirmation, withdrawal and source-change invalidation; same-millisecond ordering verified");
   }finally{if(ownsDatabase&&database.matches("icu_sepsis_devtest_[a-f0-9]{32}"))client.getDatabase(database).drop();}
  }
 }
}
