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
public class Icu05CaseReviewService {
  private final Store store;
  private final Icu05Service icu05;
  private final Clock clock;
  private final ApplicationEventPublisher events;
  public static class Changed {
    public final String patientId, encounterId;
    public Changed(Patient p) { patientId=p.id; encounterId=p.encounterId; }
  }
  public Icu05CaseReviewService(Store store, Icu05Service icu05, Clock clock, ApplicationEventPublisher events) {
    this.store=store; this.icu05=icu05; this.clock=clock; this.events=events;
  }
  private Query scope(Patient p) {
    return Query.query(Criteria.where("patientId").is(p.id).and("encounterId").is(p.encounterId).and("department").is(p.department));
  }
  private Icu05CaseReview active(Patient p) {
    Query q=scope(p); q.addCriteria(Criteria.where("revokedAt").is(null));
    return store.db.findOne(q,Icu05CaseReview.class);
  }
  public List<Icu05CaseReview> history(Patient p) {
    return store.db.find(scope(p).with(Sort.by(Sort.Direction.DESC,"createdAt")),Icu05CaseReview.class);
  }
  /** Ignore time-varying observation caps, retaining every value/source/state for stale-write detection. */
  static Object stable(Object value) {
    if (value instanceof Map) {
      Map<String,Object> result=new TreeMap<>();
      ((Map<?,?>)value).forEach((k,v) -> { if (!Set.of("asOf","to","evaluatedTo").contains(String.valueOf(k))) result.put(String.valueOf(k),stable(v)); });
      return result;
    }
    if (value instanceof List) {
      List<Object> result=new ArrayList<>(); for(Object v:(List<?>)value) result.add(stable(v)); return result;
    }
    return value;
  }
  @SuppressWarnings("unchecked")
  public Map<String,Object> decorate(Patient p,Map<String,Object> automatic) {
    Map<String,Object> result=new LinkedHashMap<>(automatic);
    Map<String,Object> pool = automatic.get("pool") instanceof Map ? (Map<String,Object>)automatic.get("pool") : Map.of();
    result.put("automaticPool",new LinkedHashMap<>(pool));
    result.put("automaticFingerprint",store.fingerprint("icu05-automatic",stable(automatic)));
    Icu05CaseReview review=active(p);
    Map<String,Object> info=new LinkedHashMap<>();
    boolean applies=review!=null && matches(review,automatic);
    info.put("active",review == null ? null : view(review)); info.put("applied",applies); info.put("requiresReview",review!=null&&!applies);
    info.put("message",review==null ? "未进行病例人工排除" : applies ? "人工排除已生效，自动判定保留" : "规则版本或 T0 已变化，原排除未应用，请重新复核");
    result.put("caseReview",info);
    if(applies) { Map<String,Object> effective=new LinkedHashMap<>(pool); effective.put("eligible",false); result.put("pool",effective); }
    return result;
  }
  /** Bedside metadata only; the full automatic snapshot stays in the restricted review history. */
  static Map<String,Object> view(Icu05CaseReview r) {
    Map<String,Object> out=new LinkedHashMap<>();
    out.put("id",r.id); out.put("version",r.version); out.put("ruleVersion",r.ruleVersion); out.put("t0",r.t0);
    out.put("autoValue",r.autoValue); out.put("manualValue",r.manualValue);
    out.put("reasonCode",r.reasonCode); out.put("reasonText",r.reasonText); out.put("operator",r.operator); out.put("createdAt",r.createdAt);
    out.put("revokedAt",r.revokedAt); out.put("revokedBy",r.revokedBy); out.put("revokeReason",r.revokeReason);
    return out;
  }
  static boolean matches(Icu05CaseReview r,Map<String,Object> automatic) {
    return Boolean.TRUE.equals(automatic.get("available")) && Objects.equals(r.ruleVersion,automatic.get("ruleVersion")) && Objects.equals(r.t0,automatic.get("t0"));
  }
  @Transactional
  public Icu05CaseReview exclude(String patientId,Requests.Icu05Exclude request,String key,String actor) {
    Patient p=store.patient(patientId);
    String fp=store.fingerprint("icu05-exclude/"+p.id+"/"+p.encounterId,request);
    Command previous=store.replay(actor,key,fp);
    if(previous!=null) return scoped(p,previous.entityId);
    if(!Set.of("not_septic_shock","outside_treated","dnr","t0_wrong","data_error","other").contains(request.reasonCode))
      throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REASON","请选择有效的 ICU-05 排除原因");
    Map<String,Object> current=icu05.evaluate(p,clock.instant());
    if(!Boolean.TRUE.equals(current.get("available")) || current.get("t0")==null) throw ApiException.conflict("缺少可复核的自动结果或 T0，不能创建病例排除");
    if(!Objects.equals(current.get("automaticFingerprint"),request.expectedFingerprint)) throw ApiException.conflict("自动证据已变化，请刷新后重新复核");
    if(active(p)!=null) throw ApiException.conflict("已有有效病例排除，请先撤销后再修改");
    Icu05CaseReview r=new Icu05CaseReview(); Instant now=clock.instant(); store.init(r,p,now);
    r.activeKey=store.fingerprint("icu05-active",List.of(p.id,p.encounterId));
    r.ruleVersion=(String)current.get("ruleVersion"); r.t0=(Instant)current.get("t0");
    r.reasonCode=request.reasonCode; r.reasonText=request.reasonText.trim(); r.operator=actor;
    r.automaticSnapshot=new LinkedHashMap<>(current);
    r.automaticSnapshot.remove("caseReview"); r.automaticSnapshot.remove("automaticFingerprint"); r.automaticSnapshot.remove("automaticPool");
    Map<?,?> pool=(Map<?,?>)current.get("automaticPool");
    Boolean eligible=pool==null?null:(Boolean)pool.get("eligible");
    r.autoValue=Boolean.FALSE.equals(eligible)||"not_met".equals(current.get("status"))?Boolean.FALSE:
        Boolean.TRUE.equals(eligible)&&"septic_shock".equals(current.get("status"))?Boolean.TRUE:null;
    store.db.insert(r); store.command(actor,key,fp,r.id,"icu05_case_review",now);
    store.record(r,"icu05_case_review","excluded",actor,r.reasonText,Map.of(),now);
    events.publishEvent(new Changed(p)); return r;
  }
  private Icu05CaseReview scoped(Patient p,String id) {
    Icu05CaseReview r=store.get(id,Icu05CaseReview.class);
    if(!Objects.equals(r.patientId,p.id)||!Objects.equals(r.encounterId,p.encounterId)||!Objects.equals(r.department,p.department)) throw ApiException.notFound();
    return r;
  }
  @Transactional
  public Icu05CaseReview revoke(String patientId,String id,Requests.Icu05Revoke request,String key,String actor) {
    Patient p=store.patient(patientId); Icu05CaseReview r=scoped(p,id);
    String fp=store.fingerprint("icu05-revoke/"+p.id+"/"+id,request);
    if(store.replay(actor,key,fp)!=null) return r;
    store.sameVersion(r,request.expectedVersion);
    if(r.revokedAt!=null) throw ApiException.conflict("该病例排除已撤销，请刷新");
    Map<String,Object> before=store.state(r); Instant now=clock.instant();
    r.revokedAt=now; r.revokedBy=actor; r.revokeReason=request.reasonText.trim(); r.activeKey=null;
    store.db.save(r); store.command(actor,key,fp,r.id,"icu05_case_review",now);
    store.record(r,"icu05_case_review","revoked",actor,r.revokeReason,before,now);
    events.publishEvent(new Changed(p)); return r;
  }
}
