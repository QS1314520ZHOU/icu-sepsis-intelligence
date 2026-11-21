package org.icu.sepsis.deterioration;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import javax.validation.constraints.*;
import org.icu.sepsis.api.ApiException;
import org.icu.sepsis.application.Store;
import org.icu.sepsis.domain.Domain.*;
import org.icu.sepsis.integration.ClinicalDataProvider;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.stereotype.Service;

/** Physician adjudication is a separate business record; source laboratory records are never modified. */
@Service
public class ReviewedLabEvidenceService {
  private static final Set<String> LABS=Set.of("CREA","PLT","TBIL");
  private final Store store;
  private final ClinicalDataProvider data;
  public ReviewedLabEvidenceService(Store store, ClinicalDataProvider data) {this.store=store;this.data=data;}

  @Document("sepsis_deterioration_lab_review")
  public static class Review extends Base {
    public long sequence;
    public String sourceRecordId, sourceSystem, parameterCode, fingerprint, actor, reason;
    public Instant observedAt;
    public Double sourceValue;
    public String sourceUnit;
    public Instant sourceReportedAt;
    public boolean accepted;
  }
  public static class Request {
    @NotBlank public String encounterId;
    @NotBlank @Size(max=240) public String sourceRecordId;
    @NotBlank @Pattern(regexp="CREA|PLT|TBIL") public String parameterCode;
    @NotBlank @Size(max=128) public String fingerprint;
    @NotBlank @Size(max=500) public String reason;
    @Size(max=128) public String expectedId;
    public Instant observedAt;
    public boolean accepted;
  }
  @Document("sepsis_deterioration_review_sequence")
  public static class Sequence {
    @org.springframework.data.annotation.Id public String id;
    public long sequence;
    public String headId;
  }
  public String fingerprint(Observation o) {
    return store.fingerprint("deterioration-lab-source",Arrays.asList(o.sourceSystem,o.sourceRecordId,
        o.parameterCode,o.value,o.unit,o.eventTime,o.sourceResultStatus,o.qualityStatus,o.encounterLinkStatus));
  }
  private Map<String,Review> reviews(Patient p,Instant asOf) {
    var q=Query.query(Criteria.where("patientId").is(p.id).and("encounterId").is(p.encounterId)
        .and("department").is(p.department).and("createdAt").lte(asOf))
        .with(Sort.by(Sort.Direction.DESC,"sequence","createdAt","id"));
    var out=new LinkedHashMap<String,Review>();
    for(var r:store.db.find(q,Review.class)) out.putIfAbsent(r.sourceSystem+"|"+r.sourceRecordId+"|"+r.parameterCode,r);
    return out;
  }
  private String key(Observation o) {return o.sourceSystem+"|"+o.sourceRecordId+"|"+o.parameterCode;}
  private static boolean scoped(Observation o,Patient p) {
    return Objects.equals(o.patientId,p.id)&&Objects.equals(o.encounterId,p.encounterId)&&Objects.equals(o.department,p.department);
  }
  public Review record(Patient p,Request req,String actor,Instant now) {
    if(!Objects.equals(req.encounterId,p.encounterId)) throw ApiException.conflict("住院上下文已变化，请刷新后复核");
    var hits=data.observations(p.id,p.encounterId,now.minus(Duration.ofDays(7)),now).stream()
        .filter(o->scoped(o,p)&&LABS.contains(o.parameterCode)&&Objects.equals(o.sourceRecordId,req.sourceRecordId)
            &&Objects.equals(o.parameterCode,req.parameterCode)&&"context_only".equals(o.clinicalUse)).collect(Collectors.toList());
    if(hits.size()!=1) throw ApiException.conflict("来源不存在或引用不唯一，不能保存复核");
    var o=hits.get(0);
    if(!fingerprint(o).equals(req.fingerprint)) throw ApiException.conflict("检验来源已变化，请重新核对");
    if(req.accepted&&(req.observedAt==null||req.observedAt.isAfter(now)||req.observedAt.isBefore(now.minus(Duration.ofDays(7)))
        ||p.admittedAt==null||req.observedAt.isBefore(p.admittedAt)||o.value==null||!Double.isFinite(o.value)||o.value<0))
      throw ApiException.conflict("需确认本次住院的真实观察时间及有效数值");
    var previous=reviews(p,now).get(key(o));
    if(!Objects.equals(req.expectedId,previous==null?null:previous.id))throw ApiException.conflict("检验复核已被更新，请刷新后重新核对");
    var r=new Review();store.init(r,p,now);r.sourceRecordId=o.sourceRecordId;r.sourceSystem=o.sourceSystem;
    String sequenceKey=store.fingerprint("lab-review-sequence",List.of(p.id,p.encounterId,p.department,o.sourceSystem,o.sourceRecordId,o.parameterCode));
    var existing=store.db.findById(sequenceKey,Sequence.class);
    String oldHead=existing==null?null:existing.headId;
    if(oldHead!=null&&!Objects.equals(oldHead,previous==null?null:previous.id))throw ApiException.conflict("检验复核正在更新，请刷新后重试");
    var criteria=Criteria.where("_id").is(sequenceKey);
    if(oldHead==null)criteria.and("headId").exists(false);else criteria.and("headId").is(oldHead);
    Sequence head;
    try{head=store.db.findAndModify(Query.query(criteria),new Update().inc("sequence",1).set("headId",r.id),
        FindAndModifyOptions.options().upsert(true).returnNew(true),Sequence.class);}
    catch(org.springframework.dao.DuplicateKeyException changed){throw ApiException.conflict("检验复核已被其他人更新，请刷新后重试");}
    if(head==null)throw ApiException.conflict("复核顺序分配失败，请重试");r.sequence=head.sequence;
    r.parameterCode=o.parameterCode;r.fingerprint=req.fingerprint;r.observedAt=req.observedAt;
    r.sourceValue=o.value;r.sourceUnit=o.unit;r.sourceReportedAt=o.reportedAt!=null?o.reportedAt:o.eventTime;
    r.accepted=req.accepted;r.actor=actor;r.reason=req.reason.trim();
    try{store.db.insert(r);}catch(RuntimeException failed){var rollback=oldHead==null?new Update().unset("headId"):new Update().set("headId",oldHead);store.db.updateFirst(Query.query(Criteria.where("_id").is(sequenceKey).and("headId").is(r.id)),rollback,Sequence.class);throw failed;}return r;
  }
  public static class Resolution {
    public final List<Observation> observations;public final List<Map<String,Object>> evidence;
    private Resolution(List<Observation> observations,List<Map<String,Object>> evidence){this.observations=observations;this.evidence=evidence;}
  }
  public Resolution resolve(Patient p,List<Observation> rows,Instant asOf){var index=reviews(p,asOf);return new Resolution(apply(p,rows,asOf,index),pending(p,rows,asOf,index));}
  private boolean applicable(Patient p,Observation o,Review r,Instant asOf){return scoped(o,p)&&LABS.contains(o.parameterCode)&&"context_only".equals(o.clinicalUse)&&r!=null&&r.createdAt!=null&&!r.createdAt.isAfter(asOf)&&r.accepted&&Objects.equals(r.fingerprint,fingerprint(o))&&r.observedAt!=null&&!r.observedAt.isAfter(asOf)&&p.admittedAt!=null&&!r.observedAt.isBefore(p.admittedAt);}
  public List<Observation> apply(Patient p,List<Observation> rows,Instant asOf) {return apply(p,rows,asOf,reviews(p,asOf));}
  private List<Observation> apply(Patient p,List<Observation> rows,Instant asOf,Map<String,Review> index) {
    var out=new ArrayList<Observation>();
    for(var o:rows) {
      var r=index.get(key(o));
      if(applicable(p,o,r,asOf)) {
        var copy=new Observation();BeanUtils.copyProperties(o,copy);
        copy.eventTime=r.observedAt;copy.timeBasis="physician_confirmed";copy.encounterLinkStatus="physician_confirmed";
        copy.clinicalUse="review";copy.qualityStatus="valid";
        copy.qualityNote="医生已复核本次住院归属及观察时间；非稳定基线确认，原始来源未修改";
        copy.ingestTime=r.createdAt;out.add(copy);
      } else out.add(o);
    }
    return out;
  }
  public List<Map<String,Object>> pending(Patient p,List<Observation> rows,Instant asOf) {return pending(p,rows,asOf,reviews(p,asOf));}
  private List<Map<String,Object>> pending(Patient p,List<Observation> rows,Instant asOf,Map<String,Review> index) {
    var out=new ArrayList<Map<String,Object>>();
    for(var o:rows) if(scoped(o,p)&&LABS.contains(o.parameterCode)&&"context_only".equals(o.clinicalUse)) {
      var r=index.get(key(o));boolean active=applicable(p,o,r,asOf);
      var item=new LinkedHashMap<String,Object>();item.put("sourceRecordId",o.sourceRecordId);item.put("parameterCode",o.parameterCode);
      item.put("value",o.value);item.put("unit",o.unit);item.put("reportedAt",o.reportedAt!=null?o.reportedAt:o.eventTime);
      item.put("reviewId",r==null?null:r.id);item.put("fingerprint",fingerprint(o));item.put("reviewed",active);item.put("observedAt",active?r.observedAt:null);
      item.put("reviewer",r==null?null:r.actor);item.put("reviewedAt",r==null?null:r.createdAt);item.put("reviewReason",r==null?null:r.reason);
      item.put("reviewState",r==null?"not_reviewed":!r.fingerprint.equals(fingerprint(o))?"source_changed":!r.accepted?"withdrawn":active?"confirmed":"context_unavailable");
      out.add(item);
    }
    return out;
  }
}
