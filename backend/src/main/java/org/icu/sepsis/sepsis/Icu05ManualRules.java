package org.icu.sepsis.sepsis;
import java.time.Instant;
import java.util.*;

/** Applies independently audited L2/L3 values to a copy, then derives results; never mutates automatic evidence. */
public final class Icu05ManualRules {
  private Icu05ManualRules() {}
  public static final Set<String> GATE=Set.of("S1","S2","S3","S4","I1","I2","I3","K1","K2");
  public static final Set<String> WINDOW=Set.of("A1","B1","B2","B3","C1","C2","C3");
  public static final List<String> ORGANS=List.of("respiratory","brain","hemostasis","liver","kidney","cardiovascular");
  @SuppressWarnings("unchecked") static Object copy(Object value) {
    if(value instanceof Map) { Map<String,Object> out=new LinkedHashMap<>();((Map<String,Object>)value).forEach((k,v)->out.put(k,copy(v)));return out; }
    if(value instanceof List) { List<Object> out=new ArrayList<>();for(Object v:(List<?>)value)out.add(copy(v));return out; }return value;
  }
  @SuppressWarnings("unchecked") static List<Map<String,Object>> list(Object v) {return v instanceof List?(List<Map<String,Object>>)v:List.of();}
  static Map<String,Object> item(List<Map<String,Object>> items,String code) {return items.stream().filter(i->code.equals(i.get("code"))).findFirst().orElse(null);}
  static Boolean value(Map<String,Object> i) { return i==null?null:(Boolean)i.get("value"); }
  static void applyItem(Map<String,Object> i,Icu05Override r) {
    if(i==null) return;
    i.put("autoValue",i.get("value"));i.put("autoReason",i.get("reason"));i.put("value",r.manualValue);
    i.put("reason",null);i.put("manual",true);i.put("overrideId",r.id);i.put("reviewedBy",r.operator);i.put("reviewedAt",r.createdAt);
  }
  static Icu05Override find(List<Icu05Override> records,String window,String target) {
    return records.stream().filter(r->"judgement".equals(r.scope)&&window.equals(r.windowKey)&&target.equals(r.targetId)).findFirst().orElse(null);
  }
  static void apply(Map<String,Object> i,List<Icu05Override> records,String window,String target) {
    Icu05Override r=find(records,window,target);if(r!=null)applyItem(i,r);
  }
  static boolean matches(Icu05Override r,Map<String,Object> automatic) {
    return Boolean.TRUE.equals(automatic.get("available"))&&r.revokedAt==null&&Objects.equals(r.ruleVersion,automatic.get("ruleVersion"))&&Objects.equals(r.t0,automatic.get("t0"));
  }
  @SuppressWarnings("unchecked") public static Map<String,Object> evaluate(Map<String,Object> automatic,List<Icu05Override> records) {
    Map<String,Object> out=(Map<String,Object>)copy(automatic);
    List<Icu05Override> active=new ArrayList<>();List<Map<String,Object>> metadata=new ArrayList<>();
    for(Icu05Override r:records) {
      boolean allowed=matches(r,automatic);
      if("K1".equals(r.targetId)) {
        Map<String,Object> g=(Map<String,Object>)out.get("gate");
        Map<String,Object> k=g==null?null:item(list(g.get("shock")),"K1");
        allowed=allowed&&k!=null&&Boolean.TRUE.equals(k.get("windowConfirmed"));
      }
      Map<String,Object> v=Icu05OverrideService.view(r);v.put("applied",allowed);metadata.add(v);if(allowed)active.add(r);
    }
    if(active.stream().anyMatch(r->"judgement".equals(r.scope))&&out.get("gate") instanceof Map) {
      Map<String,Object> g=(Map<String,Object>)out.get("gate");
      for(String group:List.of("organ","infection","shock"))for(Map<String,Object> i:list(g.get(group)))apply(i,active,"gate",(String)i.get("code"));
      Boolean organ=Icu05Rules.anyTrue(list(g.get("organ"))), infection=Icu05Rules.anyTrue(list(g.get("infection")));
      Boolean shock=Icu05Rules.all(value(item(list(g.get("shock")),"K1")),value(item(list(g.get("shock")),"K2")));
      String status,label,reason;
      if(Boolean.FALSE.equals(organ)){status="not_met";label="未见器官功能障碍";reason="NO_ORGAN_DYSFUNCTION";}
      else if(Boolean.FALSE.equals(shock)){status="not_met";label="未达本地质控候选条件";reason="NOT_SEPTIC_SHOCK";}
      else if(Boolean.TRUE.equals(shock)&&Boolean.TRUE.equals(organ)&&Boolean.TRUE.equals(infection)){status="septic_shock";label="符合本地质控候选条件";reason=null;}
      else if(Boolean.TRUE.equals(shock)&&!Boolean.TRUE.equals(infection)){status="suspected_review";label="疑似，待复核感染证据";reason="NO_INFECTION_EVIDENCE";}
      else {status="unknown";label="证据不全，无法判定";reason="INSUFFICIENT_EVIDENCE";}
      g.put("hasOrganDysfunction",organ);g.put("hasInfection",infection);g.put("isSepticShock",shock);g.put("suspectedWarning",Icu05Rules.anyTrue(list(g.get("shock"))));
      g.put("status",status);g.put("statusLabel",label);g.put("reason",reason);out.put("status",status);out.put("statusLabel",label);
      out.put("applicable",Set.of("septic_shock","suspected_review").contains(status));
      for(Map<String,Object> w:list(out.get("windows")))if(active.stream().anyMatch(r->"judgement".equals(r.scope)&&Objects.equals(r.windowKey,w.get("key"))))recomputeWindow(w,active);
    }
    out.put("experimentalSofa",sofa(active));
    out.put("automaticResult",copy(automatic));out.put("overrides",metadata);out.put("manualRuleVersion","ICU05-manual-v1");return out;
  }
  static void recomputeWindow(Map<String,Object> w,List<Icu05Override> active) {
    String key=(String)w.get("key");List<Map<String,Object>> items=list(w.get("items"));
    for(String code:WINDOW)if(!"B3".equals(code))apply(item(items,code),active,key,code);
    Map<String,Object> b1=item(items,"B1"),b2=item(items,"B2"),b3=item(items,"B3");
    if(b3!=null) {
      Object originalValue=b3.get("value"),originalReason=b3.get("reason");
      Boolean v;String reason;
      if(Boolean.FALSE.equals(value(b1))||Boolean.FALSE.equals(value(b2))){v=false;reason=Boolean.FALSE.equals(value(b1))?"AB_MISSING":"BC_MISSING";}
      else if(value(b1)==null||value(b2)==null||b1.get("time")==null||b2.get("time")==null){v=null;reason=value(b1)==null||b1.get("time")==null?"AB_MISSING":"BC_MISSING";}
      else {v=((Instant)b1.get("time")).isAfter((Instant)b2.get("time"));reason=v?null:"BC_AFTER_AB";}
      b3.put("value",v);b3.put("reason",reason);apply(b3,active,key,"B3");
      if(Boolean.TRUE.equals(b3.get("manual"))){b3.put("autoValue",originalValue);b3.put("autoReason",originalReason);}
    }
    Boolean step1=value(item(items,"A1")),step2=value(b3),c1=value(item(items,"C1")),c2=value(item(items,"C2"));
    boolean triggered=Boolean.TRUE.equals(c1)||Boolean.TRUE.equals(c2);
    Boolean step3=triggered?value(item(items,"C3")):c1==null||c2==null?null:Boolean.TRUE;
    Boolean finish=Icu05Rules.all(Icu05Rules.all(step1,step2),step3);List<String> reasons=new ArrayList<>();
    if(Boolean.FALSE.equals(step1))reasons.add("A1_NOT_MET");
    if(!Boolean.TRUE.equals(step2))reasons.add(b3==null||b3.get("reason")==null?"B3_REVIEW_NOT_MET":(String)b3.get("reason"));
    if(Boolean.FALSE.equals(step3))reasons.add("FLUID_INSUFFICIENT");if(Boolean.TRUE.equals(c1))reasons.add("MAP_NOT_MET");
    String state;
    if(Boolean.TRUE.equals(finish))state="met";
    else if(!Boolean.TRUE.equals(w.get("closed"))){state="in_progress";finish=null;reasons.add("WINDOW_OPEN");}
    else state=Boolean.FALSE.equals(finish)?"not_met":"unknown";
    w.put("step1",step1);w.put("step2",step2);w.put("step3",step3);w.put("finish",finish);w.put("state",state);
    w.put("finishPath",triggered?"triggered":"not_triggered");w.put("reasons",reasons);
  }
  static Map<String,Object> sofa(List<Icu05Override> records) {
    Map<String,Object> versions=new LinkedHashMap<>();
    for(String variant:List.of("classic","sofa2")) {
      List<Map<String,Object>> components=new ArrayList<>();int sum=0,count=0;
      for(String organ:ORGANS) {
        Icu05Override r=records.stream().filter(o->"sofa_component".equals(o.scope)&&variant.equals(o.windowKey)&&organ.equals(o.targetId)).findFirst().orElse(null);
        Integer score=r==null?null:((Number)r.manualValue).intValue();Map<String,Object> c=new LinkedHashMap<>();
        c.put("key",organ);c.put("autoScore",null);c.put("score",score);c.put("manual",r!=null);c.put("overrideId",r==null?null:r.id);
        c.put("reason",r==null?"自动分项评分尚未接入本复核接口":r.reasonText);components.add(c);if(score!=null){sum+=score;count++;}
      }
      Map<String,Object> v=new LinkedHashMap<>();v.put("components",components);v.put("scored",count);v.put("partial",count==0?null:sum);
      v.put("total",count==6?sum:null);v.put("experimental",true);v.put("note","人工分项复核，自动分项评分未接入；缺项不补零，仅辅助展示，不参与 ICU-05 分子分母");versions.put(variant,v);
    }return versions;
  }
}
