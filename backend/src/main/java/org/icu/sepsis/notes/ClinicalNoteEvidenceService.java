package org.icu.sepsis.notes;

import java.time.*;import java.util.*;
import org.icu.sepsis.api.ApiException;import org.icu.sepsis.application.Store;
import org.springframework.stereotype.Service;import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Sort;import org.springframework.data.mongodb.core.query.Query;

@Service
public class ClinicalNoteEvidenceService {
  private final Store store;private final ClinicalNoteService notes;private final ClinicalNoteCrypto crypto;private final Clock clock;private final com.fasterxml.jackson.databind.ObjectMapper json;
  public ClinicalNoteEvidenceService(Store store,ClinicalNoteService notes,ClinicalNoteCrypto crypto,Clock clock,com.fasterxml.jackson.databind.ObjectMapper json){this.store=store;this.notes=notes;this.crypto=crypto;this.clock=clock;this.json=json;}
  public Map<String,Object> prepare(String patientId,Instant requestedAsOf,int limit,String actor){
    var patient=notes.authorizedPatient(patientId);Instant now=clock.instant(),asOf=requestedAsOf==null?now:requestedAsOf;
    if(asOf.isAfter(now))throw new ApiException(HttpStatus.BAD_REQUEST,"FUTURE_REVIEW_TIME","复核时点不能晚于当前时间");
    int cap=Math.max(1,Math.min(1000,limit));
    Query directory=Query.query(ClinicalNoteService.scope(patient)).with(Sort.by(Sort.Direction.DESC,"syncedAt","id")).limit(cap+1);
    directory.fields().exclude("encryptedBody").exclude("nonce").exclude("keyId").exclude("fingerprintKeyId");
    List<ClinicalNoteSnapshot> found=store.db.find(directory,ClinicalNoteSnapshot.class);
    boolean truncated=found.size()>cap;List<ClinicalNoteSnapshot> selected=new ArrayList<>();
    for(ClinicalNoteSnapshot note:found.subList(0,Math.min(cap,found.size())))selected.add(ClinicalNoteSnapshot.metadataOnly(note));
    Set<String> verified=new HashSet<>();Map<String,Integer> positions=new HashMap<>();
    if(!truncated)for(int index=0;index<selected.size();index++){var note=selected.get(index);if(Integer.valueOf(2).equals(note.cryptoVersion)||Integer.valueOf(3).equals(note.cryptoVersion))positions.put(note.id,index);}
    if(!positions.isEmpty())try(var cursor=store.db.stream(Query.query(ClinicalNoteService.scope(patient).and("_id").in(positions.keySet())).cursorBatchSize(16),ClinicalNoteSnapshot.class)){
      while(cursor.hasNext()){
        var note=cursor.next();Integer position=positions.get(note.id);
        if(position==null||!Objects.equals(patient.id,note.patientId)||!Objects.equals(patient.encounterId,note.encounterId)||!Objects.equals(patient.department,note.department))continue;
        selected.set(position,ClinicalNoteSnapshot.metadataOnly(note));
        try{var payload=json.readTree(crypto.decrypt(note));if(payload!=null&&payload.isObject()&&payload.path("body").isTextual()&&!payload.path("body").asText().isBlank())verified.add(note.id);}
        catch(Exception ignored){/* Do not log plaintext or exception messages containing source data. */}
      }
    }
    Map<String,Object> out=ClinicalNoteEvidenceRules.evaluate(selected,verified,asOf);
    // A capped newest-first scan cannot safely select an old-time version from an incomplete local set.
    if(truncated){for(Object raw:(List<?>)out.get("items")){Map<String,Object> row=(Map<String,Object>)raw;row.remove("ref");row.put("eligible",null);row.put("state","unknown");row.put("reason","LOCAL_SCAN_TRUNCATED");row.put("message","本地快照检查达到上限，不能确认该时点的完整版本集合");}
      out.put("ready",0);out.put("excluded",0);out.put("unknown",selected.size());}
    out.put("patientId",patient.id);out.put("encounterId",patient.encounterId);out.put("truncated",truncated);out.put("limit",cap);
    var state=notes.state(patient);out.put("currentSourceStatus",state==null?"not_synced":state.status);out.put("currentSourceComplete",state==null?null:state.complete);
    out.put("historicalCoverageConfirmed",false);
    Map<String,String> versions=new HashMap<>();for(ClinicalNoteSnapshot note:selected)versions.put(note.id,note.versionKey);
    List<Object> readyMapping=new ArrayList<>();for(Object raw:(List<?>)out.get("items")){Map<?,?> row=(Map<?,?>)raw;if(Boolean.TRUE.equals(row.get("eligible")))readyMapping.add(List.of(row.get("snapshotId"),row.get("ref"),versions.get(String.valueOf(row.get("snapshotId")))));}
    out.put("packetFingerprint",store.fingerprint("note-evidence/"+patient.department+"/"+patient.id+"/"+patient.encounterId,List.of(asOf.toString(),readyMapping)));
    store.recordSystem(patient,patient,"clinical_note_access","prepare_evidence",actor,"医生主动核对病程证据时间与版本；未调用模型",now);return out;
  }
  public Map<String,Object> excerpt(String patientId,String snapshotId,Instant asOf,int start,int end,String expectedFingerprint,String actor){
    if(asOf==null||start<0||end<=start||end-start>1000)throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_EXCERPT_RANGE","请选择不超过限制的连续原文片段");
    Map<String,Object> packet=prepare(patientId,asOf,1000,actor);return excerptFromPacket(patientId,snapshotId,asOf,start,end,expectedFingerprint,actor,packet);
  }
  Map<String,Object> excerptFromPacket(String patientId,String snapshotId,Instant asOf,int start,int end,String expectedFingerprint,String actor,Map<String,Object> packet){
    if(snapshotId==null||asOf==null||start<0||end<=start||end-start>1000)throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_EXCERPT_RANGE","请选择不超过限制的连续原文片段");
    if(!Objects.equals(expectedFingerprint,packet.get("packetFingerprint")))throw ApiException.conflict("证据集合已经变化，请重新核对后选择片段");Map<?,?> selected=null;
    for(Object raw:(List<?>)packet.get("items")){Map<?,?> row=(Map<?,?>)raw;if(snapshotId.equals(row.get("snapshotId")))selected=row;}
    if(selected==null||!Boolean.TRUE.equals(selected.get("eligible")))throw ApiException.conflict("该快照未通过此时点的时间或版本核对，暂不建立证据片段");
    Map<String,Object> original=notes.read(patientId,snapshotId,actor);if(!(original.get("body") instanceof String))throw ApiException.conflict("快照正文不可用于引用，请重新核对");String body=(String)original.get("body");
    if(end>body.length()||splitSurrogate(body,start)||splitSurrogate(body,end))throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_EXCERPT_RANGE","所选原文范围无效，请重新选择完整文字");
    var patient=notes.authorizedPatient(patientId);Map<String,Object> out=new LinkedHashMap<>();
    out.put("patientId",patient.id);out.put("encounterId",patient.encounterId);out.put("snapshotId",snapshotId);out.put("packetFingerprint",packet.get("packetFingerprint"));out.put("ref",selected.get("ref"));out.put("asOf",asOf);
    out.put("start",start);out.put("end",end);out.put("excerpt",body.substring(start,end));out.put("documentType",selected.get("documentType"));
    out.put("availableAt",selected.get("availableAt"));out.put("syncedAt",selected.get("syncedAt"));out.put("modelInvoked",false);
    out.put("usage","仅在本地核对原文片段，未发送模型；片段引用不等于医学结论，也不表示已经去除所有身份信息。");
    store.recordSystem(patient,patient,"clinical_note_access","evidence_excerpt",actor,"医生核对时间限定的原文片段；片段正文不进入审计",clock.instant());return out;
  }
  static boolean splitSurrogate(String text,int at){return at>0&&at<text.length()&&Character.isHighSurrogate(text.charAt(at-1))&&Character.isLowSurrogate(text.charAt(at));}

}
