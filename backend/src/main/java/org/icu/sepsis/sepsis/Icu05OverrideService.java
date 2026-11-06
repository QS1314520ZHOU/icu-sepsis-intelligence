package org.icu.sepsis.sepsis;
import java.time.*;
import java.util.*;
import org.icu.sepsis.api.*;
import org.icu.sepsis.application.Store;
import org.icu.sepsis.domain.Domain.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Icu05OverrideService {
  private final Store store;private final Icu05Service icu05;private final Clock clock;private final ApplicationEventPublisher events;
  public Icu05OverrideService(Store s,Icu05Service i,Clock c,ApplicationEventPublisher e){store=s;icu05=i;clock=c;events=e;}
  private Query scope(Patient p){return Query.query(Criteria.where("patientId").is(p.id).and("encounterId").is(p.encounterId).and("department").is(p.department));}
  public List<Icu05Override> history(Patient p){return store.db.find(scope(p).with(Sort.by(Sort.Direction.DESC,"createdAt")),Icu05Override.class);}
  public Map<String,Object> decorate(Patient p,Map<String,Object> automatic){
    Query q=scope(p);q.addCriteria(Criteria.where("revokedAt").is(null));
    return Icu05ManualRules.evaluate(automatic,store.db.find(q,Icu05Override.class));
  }
  public static Map<String,Object> view(Icu05Override r){
    Map<String,Object> v=new LinkedHashMap<>();v.put("id",r.id);v.put("version",r.version);v.put("scope",r.scope);v.put("targetId",r.targetId);
    v.put("windowKey",r.windowKey);v.put("ruleVersion",r.ruleVersion);v.put("t0",r.t0);v.put("autoValue",r.autoValue);v.put("manualValue",r.manualValue);
    v.put("reasonCode",r.reasonCode);v.put("reasonText",r.reasonText);v.put("operator",r.operator);v.put("createdAt",r.createdAt);
    v.put("revokedAt",r.revokedAt);v.put("revokedBy",r.revokedBy);v.put("revokeReason",r.revokeReason);return v;
  }
  @SuppressWarnings("unchecked") static Map<String,Object> target(Map<String,Object> automatic,String window,String target){
    if("gate".equals(window)){
      Map<String,Object> g=(Map<String,Object>)automatic.get("gate");if(g==null)return null;
      for(String group:List.of("organ","infection","shock"))for(Map<String,Object> i:Icu05ManualRules.list(g.get(group)))if(target.equals(i.get("code")))return i;
    }else for(Map<String,Object> w:Icu05ManualRules.list(automatic.get("windows")))if(window.equals(w.get("key")))for(Map<String,Object> i:Icu05ManualRules.list(w.get("items")))if(target.equals(i.get("code")))return i;
    return null;
  }
  static void validate(Requests.Icu05OverrideRequest r){
    boolean judgement="judgement".equals(r.scope);
    boolean sofa="sofa_component".equals(r.scope);
    boolean targetOk=judgement&&("gate".equals(r.windowKey)?Icu05ManualRules.GATE.contains(r.targetId):Set.of("1h","3h","6h").contains(r.windowKey)&&Icu05ManualRules.WINDOW.contains(r.targetId))
        ||sofa&&Set.of("classic","sofa2").contains(r.windowKey)&&Icu05ManualRules.ORGANS.contains(r.targetId);
    if(!targetOk||judgement&&(r.judgementValue==null||r.componentScore!=null)||sofa&&(r.componentScore==null||r.componentScore<0||r.componentScore>4||r.judgementValue!=null))
      throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_OVERRIDE","覆盖层级、对象、窗口或值不正确");
    Set<String> reasons=sofa?Set.of("chronic_baseline","clinical_mismatch","data_error","ceiling","other"):Set.of("not_septic_shock","outside_treated","dnr","t0_wrong","data_error","other");
    if(!reasons.contains(r.reasonCode))throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REASON","请选择该覆盖层级对应的原因");
  }
  @Transactional @SuppressWarnings("unchecked")
  public Icu05Override create(String patientId,Requests.Icu05OverrideRequest request,String key,String actor){
    Patient p=store.patient(patientId);String fp=store.fingerprint("icu05-override/"+p.id+"/"+p.encounterId,request);
    Command replay=store.replay(actor,key,fp);if(replay!=null)return scoped(p,replay.entityId);validate(request);
    Map<String,Object> current=icu05.evaluate(p,clock.instant());
    if(!Boolean.TRUE.equals(current.get("available"))||current.get("t0")==null)throw ApiException.conflict("缺少可复核的自动结果或 T0");
    if(!Objects.equals(request.expectedFingerprint,current.get("automaticFingerprint")))throw ApiException.conflict("证据或复核记录已变化，请刷新后重新复核");
    Map<String,Object> automatic=(Map<String,Object>)current.get("automaticResult");if(automatic==null)throw ApiException.conflict("缺少原始自动快照，请刷新");
    Map<String,Object> item="judgement".equals(request.scope)?target(automatic,request.windowKey,request.targetId):null;
    if("judgement".equals(request.scope)&&item==null)throw ApiException.conflict("当前自动结果没有该判定项");
    if("K1".equals(request.targetId)&&!Boolean.TRUE.equals(item.get("windowConfirmed")))throw ApiException.conflict("乳酸取值窗口待确认，K1 保持无法判定，暂不允许人工覆盖");
    String activeKey=store.fingerprint("icu05-override-active",List.of(p.id,p.encounterId,request.scope,request.windowKey,request.targetId));
    if(store.db.findOne(Query.query(Criteria.where("activeKey").is(activeKey)),Icu05Override.class)!=null)throw ApiException.conflict("该项已有有效覆盖，请先撤销");
    Icu05Override r=new Icu05Override();Instant now=clock.instant();store.init(r,p,now);r.activeKey=activeKey;
    r.scope=request.scope;r.targetId=request.targetId;r.windowKey=request.windowKey;r.ruleVersion=(String)current.get("ruleVersion");r.t0=(Instant)current.get("t0");
    r.autoValue=item==null?null:item.get("value");r.manualValue="judgement".equals(r.scope)?request.judgementValue:request.componentScore;
    r.automaticSnapshot=new LinkedHashMap<>(automatic);r.reasonCode=request.reasonCode;r.reasonText=request.reasonText.trim();r.operator=actor;
    store.db.insert(r);store.command(actor,key,fp,r.id,"icu05_override",now);store.record(r,"icu05_override","created",actor,r.reasonText,Map.of(),now);
    events.publishEvent(new Icu05CaseReviewService.Changed(p));return r;
  }
  private Icu05Override scoped(Patient p,String id){
    Icu05Override r=store.get(id,Icu05Override.class);
    if(!Objects.equals(r.patientId,p.id)||!Objects.equals(r.encounterId,p.encounterId)||!Objects.equals(r.department,p.department))throw ApiException.notFound();return r;
  }
  @Transactional public Icu05Override revoke(String patientId,String id,Requests.Icu05Revoke request,String key,String actor){
    Patient p=store.patient(patientId);Icu05Override r=scoped(p,id);String fp=store.fingerprint("icu05-override-revoke/"+p.id+"/"+id,request);
    if(store.replay(actor,key,fp)!=null)return r;store.sameVersion(r,request.expectedVersion);if(r.revokedAt!=null)throw ApiException.conflict("覆盖已撤销");
    Map<String,Object> before=store.state(r);Instant now=clock.instant();r.revokedAt=now;r.revokedBy=actor;r.revokeReason=request.reasonText.trim();r.activeKey=null;
    store.db.save(r);store.command(actor,key,fp,r.id,"icu05_override",now);store.record(r,"icu05_override","revoked",actor,r.revokeReason,before,now);
    events.publishEvent(new Icu05CaseReviewService.Changed(p));return r;
  }
}
