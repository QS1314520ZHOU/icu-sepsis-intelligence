package org.icu.sepsis.sepsis;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class Icu05ManualRulesTest {
  Map<String,Object> automatic(){Map<String,Object> a=Icu05Rules.evaluate(Icu05RulesTest.full());a.put("available",true);return a;}
  Icu05Override override(String scope,String window,String target,Object value){Icu05Override r=new Icu05Override();r.id=window+target;r.scope=scope;r.windowKey=window;r.targetId=target;r.manualValue=value;r.t0=Icu05RulesTest.T0;r.ruleVersion=Icu05Rules.RULE_VERSION;r.reasonText="synthetic";return r;}
  @Test void a1OverrideRecomputesFinishWithoutChangingAutomaticResultOrOtherWindows(){
    Map<String,Object> auto=automatic();Map<String,Object> out=Icu05ManualRules.evaluate(auto,List.of(override("judgement","1h","A1",false)));
    assertEquals("not_met",Icu05RulesTest.win(out,"1h").get("state"));assertEquals("met",Icu05RulesTest.win(auto,"1h").get("state"));
    assertEquals(Icu05RulesTest.win(auto,"3h"),Icu05RulesTest.win(out,"3h"));assertEquals(auto,out.get("automaticResult"));
    assertEquals(true,Icu05RulesTest.item(Icu05RulesTest.win(out,"1h"),"A1").get("autoValue"));
  }
  @Test void b1AndB2OverridesFeedDerivedB3WithoutInventingTimes(){
    Map<String,Object> auto=automatic();Map<String,Object> out=Icu05ManualRules.evaluate(auto,List.of(override("judgement","1h","B1",false)));
    assertEquals(false,Icu05RulesTest.item(Icu05RulesTest.win(out,"1h"),"B3").get("value"));
    auto=automatic();Map<String,Object> b1=Icu05RulesTest.item(Icu05RulesTest.win(auto,"1h"),"B1");b1.put("value",null);b1.put("time",null);
    out=Icu05ManualRules.evaluate(auto,List.of(override("judgement","1h","B1",true)));
    assertNull(Icu05RulesTest.item(Icu05RulesTest.win(out,"1h"),"B1").get("time"));assertNull(Icu05RulesTest.item(Icu05RulesTest.win(out,"1h"),"B3").get("value"));
    out=Icu05ManualRules.evaluate(auto,List.of(override("judgement","1h","B1",true),override("judgement","1h","B3",true)));
    assertEquals("met",Icu05RulesTest.win(out,"1h").get("state"));
  }
  @Test void gateRecomputesAndUnknownWindowCannotBeBypassedByK1Override(){
    Map<String,Object> auto=automatic();Map<String,Object> out=Icu05ManualRules.evaluate(auto,List.of(override("judgement","gate","K2",false)));
    assertEquals("not_met",out.get("status"));assertEquals("septic_shock",auto.get("status"));
    Icu05Rules.Evidence e=Icu05RulesTest.full();e.k1WindowStart=null;e.k1WindowEnd=null;
    auto=Icu05Rules.evaluate(e);auto.put("available",true);out=Icu05ManualRules.evaluate(auto,List.of(override("judgement","gate","K1",true)));
    assertNull(Icu05RulesTest.gate(out).get("isSepticShock"));assertEquals(false,Icu05ManualRules.list(out.get("overrides")).get(0).get("applied"));
  }
  @Test void triggerOverridesReevaluateFluidBranch(){
    Map<String,Object> out=Icu05ManualRules.evaluate(automatic(),List.of(override("judgement","3h","C3",false)));
    assertEquals("not_met",Icu05RulesTest.win(out,"3h").get("state"));
    out=Icu05ManualRules.evaluate(automatic(),List.of(override("judgement","3h","C3",false),override("judgement","3h","C1",false),override("judgement","3h","C2",false)));
    assertEquals("met",Icu05RulesTest.win(out,"3h").get("state"));assertEquals("not_triggered",Icu05RulesTest.win(out,"3h").get("finishPath"));
  }
  @SuppressWarnings("unchecked") @Test void sofaTotalsAreVariantSpecificAndNeverChangeBundleOrCandidate(){
    Map<String,Object> auto=automatic();List<Icu05Override> records=new ArrayList<>();
    records.add(override("sofa_component","classic","respiratory",0));
    Map<String,Object> out=Icu05ManualRules.evaluate(auto,records);Map<String,Object> versions=(Map<String,Object>)out.get("experimentalSofa");
    assertNull(((Map<?,?>)versions.get("classic")).get("total"));assertEquals(0,((Map<?,?>)versions.get("classic")).get("partial"));
    for(String organ:Icu05ManualRules.ORGANS)if(!"respiratory".equals(organ))records.add(override("sofa_component","classic",organ,2));
    out=Icu05ManualRules.evaluate(auto,records);versions=(Map<String,Object>)out.get("experimentalSofa");
    assertEquals(10,((Map<?,?>)versions.get("classic")).get("total"));assertNull(((Map<?,?>)versions.get("sofa2")).get("total"));
    assertEquals(auto.get("gate"),out.get("gate"));assertEquals(auto.get("windows"),out.get("windows"));assertEquals(auto.get("pool"),out.get("pool"));
  }
  @Test void staleAndRevokedOverridesAreIgnoredAndRemovalRestoresAutomatic(){
    Icu05Override r=override("judgement","1h","A1",false);r.ruleVersion="old";
    Map<String,Object> auto=automatic();assertEquals(auto.get("windows"),Icu05ManualRules.evaluate(auto,List.of(r)).get("windows"));
    r.ruleVersion=Icu05Rules.RULE_VERSION;r.revokedAt=Icu05RulesTest.at(1);
    assertEquals(auto.get("windows"),Icu05ManualRules.evaluate(auto,List.of(r)).get("windows"));
    assertEquals(auto.get("windows"),Icu05ManualRules.evaluate(auto,List.of()).get("windows"));
  }
}
