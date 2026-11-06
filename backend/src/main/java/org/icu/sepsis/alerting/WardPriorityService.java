package org.icu.sepsis.alerting;

import java.time.*;
import java.util.*;
import org.icu.sepsis.application.Store;
import org.icu.sepsis.domain.Domain.Alert;
import org.springframework.stereotype.Service;
import org.springframework.data.mongodb.core.query.*;

/** Versioned work-queue ordering from existing alert severity; no new clinical threshold or model score. */
@Service
public class WardPriorityService {
  public static final String VERSION="ward-review-queue-v1";
  public static final Set<String> OPEN=Set.of("pending","acknowledged","deferred","confirmed");
  private final Store store; private final Clock clock;
  public WardPriorityService(Store store,Clock clock){this.store=store;this.clock=clock;}
  public List<Alert> openAlerts(){return store.db.find(Query.query(store.departmentScope().and("status").in(OPEN)),Alert.class);}
  static int rank(Alert a){if("critical".equals(a.severity))return 0;if(a.needsReReview||"high".equals(a.severity))return 1;
    if(Set.of("medium","low","info").contains(Objects.toString(a.severity,"")))return 2;return 3;}
  public static final class Group {
    public String patientId,encounterId,label,bed,category; public int rank=3,alertCount; public boolean needsReReview;
    public Instant latestSeenAt; public final List<String> reasons=new ArrayList<>();
    public Map<String,Object> view(){Map<String,Object> out=new LinkedHashMap<>();out.put("patientId",patientId);out.put("encounterId",encounterId);
      out.put("label",label);out.put("bed",bed);out.put("category",category);out.put("rank",rank);out.put("alertCount",alertCount);
      out.put("needsReReview",needsReReview);out.put("latestSeenAt",latestSeenAt);out.put("reasons",reasons);return out;}
  }
  public static List<Group> aggregate(List<Alert> alerts){
    Map<String,Group> groups=new TreeMap<>();
    for(Alert a:alerts){if(!OPEN.contains(Objects.toString(a.status,"")))continue;
      String key=Objects.toString(a.patientId,"unknown:"+a.id)+"|"+Objects.toString(a.encounterId,"unknown:"+a.id);
      Group g=groups.computeIfAbsent(key,k->new Group());g.patientId=a.patientId;g.encounterId=a.encounterId;
      if(g.label==null)g.label=a.patientLabel;if(g.bed==null)g.bed=a.bed;
      g.rank=Math.min(g.rank,rank(a));g.alertCount++;g.needsReReview|=a.needsReReview;
      Instant seen=a.lastSeenAt!=null?a.lastSeenAt:a.createdAt;if(seen!=null&&(g.latestSeenAt==null||seen.isAfter(g.latestSeenAt)))g.latestSeenAt=seen;
      String reason=a.latestTrigger!=null?a.latestTrigger:a.trigger!=null?a.trigger:a.reason;
      if(reason!=null&&!g.reasons.contains(reason))g.reasons.add(reason);
    }
    List<Group> out=new ArrayList<>(groups.values());for(Group g:out){g.category=List.of("优先查看","需要关注","持续观察","信息不足").get(g.rank);Collections.sort(g.reasons);}
    out.sort(Comparator.comparingInt((Group g)->g.rank).thenComparing(g->!g.needsReReview)
      .thenComparing(g->Objects.toString(g.bed,"~")).thenComparing(g->Objects.toString(g.patientId,"~")).thenComparing(g->Objects.toString(g.encounterId,"~")));
    return out;
  }
  public Map<String,Object> snapshot(){List<Alert> alerts=openAlerts();List<Group> groups=aggregate(alerts);Map<String,Object> out=new LinkedHashMap<>();
    out.put("asOf",clock.instant());out.put("ruleVersion",VERSION);out.put("matchedAlerts",alerts.size());out.put("patients",groups.size());
    out.put("cases",groups.stream().map(Group::view).collect(java.util.stream.Collectors.toList()));
    out.put("scope","当前宿主科室范围的全部未结束预警，按患者和住院聚合；没有预警不代表临床正常。");
    out.put("rule","先按既有预警严重度（critical/high/其他已知/未知）排序，同层需重新复核者优先，再按床号及患者住院稳定排序；不是临床风险评分。");
    out.put("limitations",List.of("本批只使用已有预警与复核状态；未纳入完整器官功能、尿量和趋势等优先级输入，不代表全科临床分级。","最近仍触发时间不是数据采样时间；新鲜度需核对原始记录。","AI 只解释系统已排定的顺序；AI 不可用不影响列表。"));return out;}
}
