package org.icu.sepsis.notes;
import java.util.*;import org.bson.Document;import org.bson.types.ObjectId;
import org.icu.sepsis.application.Store;import org.icu.sepsis.domain.Domain.Patient;import org.icu.sepsis.integration.SmartCareSourceReader;
import org.springframework.stereotype.Component;import org.springframework.beans.factory.annotation.Qualifier;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.data.mongodb.core.MongoTemplate;import org.springframework.data.mongodb.core.query.*;
@Component @ConditionalOnProperty(name="app.clinical-notes.blbcjl.enabled",havingValue="true")
public class BlbcjlNoteSource implements ClinicalNoteSource {
 private final MongoTemplate sourceDb;private final Store store;private final BlbcjlProperties config;private final ClinicalNoteProperties limits;private final BlbcjlClient client;
 @org.springframework.beans.factory.annotation.Autowired
 public BlbcjlNoteSource(@Qualifier("smartcareMongoTemplate") org.springframework.beans.factory.ObjectProvider<MongoTemplate> provider,Store store,BlbcjlProperties config,ClinicalNoteProperties limits){this(provider.getIfAvailable(),store,config,limits);}
 public BlbcjlNoteSource(MongoTemplate sourceDb,Store store,BlbcjlProperties config,ClinicalNoteProperties limits){this.sourceDb=sourceDb;this.store=store;this.config=config;this.limits=limits;this.client=new BlbcjlClient(config);}
 @Override public Map<String,Object> diagnostics(){return Map.of("parserVersion",BlbcjlProtocol.VERSION,"legacyBodyEscapingEnabled",config.allowLegacyBodyEscaping);}
 @Override public Batch fetch(Patient patient) throws Exception {
  if(sourceDb==null)throw new BlbcjlClient.Failure("interface_config_missing");
  if(patient.id==null||!ObjectId.isValid(patient.id))throw new IllegalArgumentException("宿主患者标识不可用");
  Query query=Query.query(Criteria.where("_id").is(new ObjectId(patient.id)));query.fields().include("mrn").include("icuAdmissionTime").include("admissionTime").include("deptCode").include("deptCode2").include("dept");
  Document row=sourceDb.findOne(query,Document.class,"patient");if(row==null||!Objects.equals(patient.encounterId,SmartCareSourceReader.encounterIdOf(row)))throw new BlbcjlProtocol.Failure("interface_binding_mismatch");
  String department="UNKNOWN";for(String field:List.of("deptCode","deptCode2","dept")){Object value=row.get(field);if(value instanceof String&&!((String)value).isBlank()){department=(String)value;break;}}
  if(!Objects.equals(patient.department,department))throw new BlbcjlProtocol.Failure("interface_binding_mismatch");String mrn=row.getString("mrn");if(mrn==null||mrn.isBlank())throw new IllegalArgumentException("本次住院 mrn 未取得");
  return BlbcjlProtocol.parse(client.fetch(mrn),mrn,config.bqdm,patient,Math.max(1,Math.min(10000,limits.maxDocuments)),Math.max(1,limits.maxBodyCharacters),config.allowLegacyBodyEscaping);
 }
 @Override public Set<String> activePatientIds(String department){
  if(sourceDb==null)return Set.of();
  var local=store.db.find(Query.query(Criteria.where("department").is(department)),Patient.class);List<ObjectId> ids=new ArrayList<>();for(var p:local)if(p.id!=null&&ObjectId.isValid(p.id))ids.add(new ObjectId(p.id));if(ids.isEmpty())return Set.of();
  Query query=Query.query(Criteria.where("_id").in(ids).and("status").is("admitted"));query.fields().include("_id");Set<String> result=new HashSet<>();for(Document row:sourceDb.find(query,Document.class,"patient"))result.add(row.getObjectId("_id").toHexString());return result;
 }
}
