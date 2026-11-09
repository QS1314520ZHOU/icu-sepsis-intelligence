package org.icu.sepsis.notes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;import java.util.*;
import org.icu.sepsis.application.Store;import org.icu.sepsis.api.ApiException;import org.icu.sepsis.domain.Domain.Patient;
import org.springframework.beans.factory.ObjectProvider;import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;import org.springframework.stereotype.Service;
@Service
public class ClinicalNoteService {
  private final Store store;private final Clock clock;private final ClinicalNoteProperties props;private final ClinicalNoteCrypto crypto;
  private ObjectProvider<ClinicalNoteSyncJob> background;
  @org.springframework.beans.factory.annotation.Autowired void setBackground(ObjectProvider<ClinicalNoteSyncJob> background){this.background=background;}
  private final ObjectProvider<ClinicalNoteSource> source;private final ObjectMapper json;
  public ClinicalNoteService(Store store,Clock clock,ClinicalNoteProperties props,ClinicalNoteCrypto crypto,ObjectProvider<ClinicalNoteSource> source,ObjectMapper json){
    this.store=store;this.clock=clock;this.props=props;this.crypto=crypto;this.source=source;this.json=json;}
  Patient authorizedPatient(String id){Patient p=store.patient(id);var grant=org.icu.sepsis.embed.EmbedAccess.current();
    if(grant!=null&&grant.department!=null&&!grant.department.equals(p.department))throw new ApiException(HttpStatus.FORBIDDEN,"PATIENT_SCOPE_DENIED","当前授权不包含此科室病程快照");return p;}
  static Criteria scope(Patient p){return Criteria.where("patientId").is(p.id).and("encounterId").is(p.encounterId).and("department").is(p.department);}
  ClinicalNoteSyncState state(Patient p){return store.db.findOne(Query.query(scope(p)),ClinicalNoteSyncState.class);}
  public Map<String,Object> list(String patientId,int page,int size,String actor){Patient p=authorizedPatient(patientId);ClinicalNoteSyncState state=state(p);
    if(state==null&&props.syncEnabled)state=syncPatient(p);
    long total=store.db.count(Query.query(scope(p)),ClinicalNoteSnapshot.class);
    Query directory=Query.query(scope(p)).with(Sort.by(Sort.Direction.DESC,"syncedAt","id")).skip((long)page*size).limit(size);
    directory.fields().exclude("encryptedBody").exclude("nonce").exclude("keyId").exclude("fingerprintKeyId");
    List<ClinicalNoteSnapshot> notes=store.db.find(directory,ClinicalNoteSnapshot.class);
    Map<String,Object> out=new LinkedHashMap<>();out.put("patientId",p.id);out.put("encounterId",p.encounterId);out.put("total",total);out.put("items",notes.stream().map(ClinicalNoteService::view).collect(java.util.stream.Collectors.toList()));
    out.put("adapterDiagnostics",source.getIfAvailable()==null?Map.of():source.getIfAvailable().diagnostics());out.put("parseDiagnostics",state==null?Map.of():state.parseDiagnostics);out.put("syncEnabled",props.syncEnabled);out.put("adapterConfigured",source.getIfAvailable()!=null);out.put("snapshotKeysReady",crypto.ready());
    out.put("canSync",props.syncEnabled&&crypto.ready()&&source.getIfAvailable()!=null);out.put("page",page);out.put("size",size);out.put("status",state==null?props.syncEnabled?"not_configured":"disabled":state.status);
    out.put("legacyBodyEscapingNormalized",state!=null&&state.legacyBodyEscapingNormalized);out.put("complete",state==null?null:state.complete);out.put("lastAttemptAt",state==null?null:state.lastAttemptAt);
    out.put("lastSuccessfulAt",state==null?null:state.lastSuccessfulAt);out.put("lastCompleteAt",state==null?null:state.lastCompleteAt);
    ClinicalNoteSyncJob job=background==null?null:background.getIfAvailable();out.put("backgroundSync",job==null?Map.of("status","not_started"):job.status());
    out.put("retention","长期受控保存；未配置自动到期删除，确切年限和归档按院方制度确定。");
    out.put("scope","历史病程快照，非正式病历；最后同步后可能仍有新文书，数量不代表源接口完整性。正文日期未被用作文书时间。");
    out.put("aiUse","此处查看原文不调用模型；病程片段 AI 复核请在概览 AI 区主动调用。");
    store.recordSystem(p,p,"clinical_note_access","list",actor,"授权范围内查看病程快照目录",clock.instant());return out;
  }
  public Map<String,Object> read(String patientId,String id,String actor){Patient p=authorizedPatient(patientId);ClinicalNoteSnapshot note=store.get(id,ClinicalNoteSnapshot.class);
    if(!Objects.equals(note.patientId,p.id)||!Objects.equals(note.encounterId,p.encounterId)||!Objects.equals(note.department,p.department))throw ApiException.notFound();
    store.recordSystem(note,p,"clinical_note_access","read_attempt",actor,"请求读取授权病程快照",clock.instant());
    String plaintext;try{plaintext=crypto.decrypt(note);}catch(IllegalStateException ex){store.recordSystem(note,p,"clinical_note_access","read_unavailable",actor,"病程快照密钥或完整性校验不可用",clock.instant());throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"NOTE_KEY_UNAVAILABLE","快照解密暂不可用，请核对历史密钥配置");}
    try{var payload=json.readTree(plaintext);if(payload==null||!payload.isObject()||!payload.path("body").isTextual()||payload.path("body").asText().isBlank())throw new IllegalArgumentException();Map<String,Object> out=view(note);out.put("body",payload.path("body").asText(""));out.put("legacyBodyEscapingNormalized",payload.path("legacyBodyEscapingNormalized").asBoolean(false));out.put("candidateReference",payload.path("candidateReference").asText(""));
      store.recordSystem(note,p,"clinical_note_access","read",actor,"授权范围内读取历史病程快照",clock.instant());return out;
    }catch(ApiException ex){throw ex;}catch(Exception ex){store.recordSystem(note,p,"clinical_note_access","read_unavailable",actor,"快照正文结构校验不可用",clock.instant());throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"NOTE_CONTENT_UNAVAILABLE","快照正文暂不可用");}
  }
  public Map<String,Object> sync(String patientId,String actor){Patient p=authorizedPatient(patientId);syncPatient(p);return list(patientId,0,20,actor);}
  public synchronized ClinicalNoteSyncState syncPatient(Patient p){ClinicalNoteSyncState state=state(p);Instant now=clock.instant();
    if(state==null){state=new ClinicalNoteSyncState();store.init(state,p,now);}state.lastAttemptAt=now;state.complete=null;state.legacyBodyEscapingNormalized=false;state.parseDiagnostics=Map.of();
    if(!props.syncEnabled){state.status="disabled";store.db.save(state);return state;}
    ClinicalNoteSource adapter=source.getIfAvailable();if(adapter==null){state.status="not_configured";store.db.save(state);return state;}
    if(!crypto.ready()){state.status="key_unavailable";store.db.save(state);return state;}
    try{ClinicalNoteSource.Batch batch=adapter.fetch(p);
      if(batch==null||!batch.bindingConfirmed||!Objects.equals(batch.patientId,p.id)||!Objects.equals(batch.encounterId,p.encounterId)||!Objects.equals(batch.department,p.department)){
        state.status="binding_unconfirmed";state.complete=false;store.db.save(state);return state;}
      state.legacyBodyEscapingNormalized=batch.legacyBodyEscapingNormalized;
      if(batch.source==null||!batch.source.matches("[A-Za-z0-9_.-]{1,80}")||batch.notes==null)throw new IllegalArgumentException();
      state.received=batch.notes.size();state.stored=0;state.unchanged=0;state.rejected=0;
      int cap=Math.max(1,Math.min(10000,props.maxDocuments)),limit=Math.min(cap,batch.notes.size());
      for(int index=0;index<limit;index++){
        ClinicalNoteSource.Note input=batch.notes.get(index);
        if(input==null||!input.bindingConfirmed||!Objects.equals(input.patientId,p.id)||!Objects.equals(input.encounterId,p.encounterId)||!Objects.equals(input.department,p.department)||input.body==null||input.body.isBlank()||input.body.length()>props.maxBodyCharacters){state.rejected++;continue;}
        String type=input.documentType==null?"文书类型未确认":input.documentType;
        if(type.length()>200||input.candidateReference!=null&&input.candidateReference.length()>256){state.rejected++;continue;}
        String context=json.writeValueAsString(List.of(p.department,p.id,p.encounterId,batch.source));
        String candidate=Objects.toString(input.candidateReference,"");String documentKey=crypto.fingerprint(context+json.writeValueAsString(List.of(type,candidate)));
        String versionKey=crypto.fingerprint(context+json.writeValueAsString(Arrays.asList("envelope-v3",type,candidate,input.body,input.documentedAt==null?null:input.documentedAt.toString(),input.availableAt==null?null:input.availableAt.toString(),input.timeMeaning,input.identityConfirmed,input.availableTimeConfirmed,input.documentTypeConfirmed,input.versionOrderConfirmed)));
        if(store.db.findOne(Query.query(scope(p).and("versionKey").is(versionKey)),ClinicalNoteSnapshot.class)!=null){state.unchanged++;continue;}
        Instant receivedAt=clock.instant(),observedAt=upperMillis(receivedAt);
        ClinicalNoteSnapshot note=new ClinicalNoteSnapshot();store.init(note,p,observedAt);note.documentKey=documentKey;note.versionKey=versionKey;note.source=batch.source;note.documentType=type;
        note.documentedAt=input.documentedAt==null?null:upperMillis(input.documentedAt);note.availableAt=input.availableAt==null?null:upperMillis(input.availableAt);note.syncedAt=observedAt;note.timePrecision="millisecond_upper_bound";note.timeMeaning=Set.of("recorded","signed","reported","unknown").contains(Objects.toString(input.timeMeaning,""))?input.timeMeaning:"unknown";
        if("unknown".equals(note.timeMeaning))note.documentedAt=null;
        note.availableTimeConfirmed=input.availableTimeConfirmed&&input.availableAt!=null;
        if(!note.availableTimeConfirmed)note.availableAt=null;
        note.timeConflict=note.availableTimeConfirmed&&input.availableAt!=null&&(input.availableAt.isAfter(receivedAt)||note.documentedAt!=null&&input.documentedAt.isAfter(input.availableAt));
        note.identityConfirmed=input.identityConfirmed&&!candidate.isBlank();note.versionOrderConfirmed=input.versionOrderConfirmed;
        note.documentTypeConfirmed=input.documentTypeConfirmed&&input.documentType!=null&&!input.documentType.isBlank();
        crypto.encrypt(note,json.writeValueAsString(Map.of("body",input.body,"legacyBodyEscapingNormalized",batch.legacyBodyEscapingNormalized,"candidateReference",candidate,"sourceDocumentedAt",Objects.toString(input.documentedAt,""),"sourceAvailableAt",Objects.toString(input.availableAt,""),"localObservedAt",receivedAt.toString())));
        try{store.db.insert(note);state.stored++;store.recordSystem(note,p,"clinical_note_snapshot","stored","system","病程来源快照受控保存；不属于正式病历",clock.instant());}
        catch(DuplicateKeyException ex){state.unchanged++;}
      }
      state.complete=state.rejected>0||batch.notes.size()>cap?Boolean.FALSE:batch.complete;state.status=Boolean.TRUE.equals(state.complete)?batch.notes.isEmpty()?"no_records":"synced":Boolean.FALSE.equals(state.complete)?"partial":"completeness_unknown";
      state.lastSuccessfulAt=upperMillis(clock.instant());if(Boolean.TRUE.equals(state.complete))state.lastCompleteAt=state.lastSuccessfulAt;
    }catch(Exception ex){if(ex instanceof BlbcjlProtocol.Failure)state.parseDiagnostics=((BlbcjlProtocol.Failure)ex).diagnostics;if(ex instanceof InterruptedException)Thread.currentThread().interrupt();state.status=ex instanceof BlbcjlClient.Failure?((BlbcjlClient.Failure)ex).code:ex instanceof BlbcjlProtocol.Failure?((BlbcjlProtocol.Failure)ex).code:ex instanceof java.net.SocketTimeoutException||ex instanceof java.net.http.HttpTimeoutException?"timeout":"read_failed";state.complete=false;}
    store.db.save(state);return state;
  }
  static Instant upperMillis(Instant time){Instant floor=Instant.ofEpochMilli(time.toEpochMilli());return floor.equals(time)?floor:floor.plusMillis(1);}
  public static Map<String,Object> view(ClinicalNoteSnapshot n){Map<String,Object> out=new LinkedHashMap<>();out.put("id",n.id);out.put("documentKey",n.documentKey);out.put("source",n.source);out.put("documentType",n.documentType);
    out.put("documentedAt",n.documentedAt);out.put("availableAt",n.availableAt);out.put("timeMeaning",n.timeMeaning);out.put("syncedAt",n.syncedAt);out.put("identityConfirmed",n.identityConfirmed);out.put("availableTimeConfirmed",n.availableTimeConfirmed);out.put("documentTypeConfirmed",n.documentTypeConfirmed);out.put("timePrecision",n.timePrecision);out.put("timeConflict",n.timeConflict);out.put("metadataProtection",Integer.valueOf(2).equals(n.cryptoVersion)||Integer.valueOf(3).equals(n.cryptoVersion)?"authenticated_on_read":"legacy_unverified");
    out.put("label","历史病程快照");return out;}
}
