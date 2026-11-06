package org.icu.sepsis.notes;

import java.time.Instant;
import java.util.*;

/** Time and version checks only. No infection inference, clinical threshold or AI decision. */
public final class ClinicalNoteEvidenceRules {
  public static final String VERSION="note-evidence-time-v1";
  private ClinicalNoteEvidenceRules() {}
  public static final Map<String,String> REASONS=Map.ofEntries(
      Map.entry("READY","满足本地时间和版本核对条件，仍需医生核对正文含义"),
      Map.entry("UNAVAILABLE","解密或内容完整性校验不可用"),
      Map.entry("LEGACY_METADATA","旧快照的时间元数据未受完整性保护，暂不用于历史时点证据"),
      Map.entry("TIME_PRECISION_UNKNOWN","快照时间精度保证未确认，暂不用于精确历史时点核对"),
      Map.entry("AVAILABILITY_UNKNOWN","来源可用时间未确认"),
      Map.entry("SYNC_TIME_UNKNOWN","本地保存时间未确认"),
      Map.entry("SOURCE_FUTURE","复核时点之后才在来源中可用"),
      Map.entry("LOCAL_FUTURE","复核时点之后才保存到本地"),
      Map.entry("TIME_CONFLICT","文书、来源可用和本地保存时间顺序冲突，需核对时间含义"),
      Map.entry("TYPE_UNKNOWN","文书类型尚未确认"),
      Map.entry("IDENTITY_UNKNOWN","文书标识或版本关系尚未确认"),
      Map.entry("VERSION_ORDER_UNKNOWN","同文书存在多个可用版本，但版本顺序语义未确认"),
      Map.entry("VERSION_CONFLICT","相同可用时间有不同版本，无法自动选取"),
      Map.entry("OLDER_VERSION","复核时点内已有可用的后续版本；原版本保留供核对"));

  public static Map<String,Object> evaluate(List<ClinicalNoteSnapshot> snapshots,Set<String> verified,Instant asOf) {
    List<Map<String,Object>> rows=new ArrayList<>();Map<String,List<ClinicalNoteSnapshot>> families=new TreeMap<>();
    Map<String,Map<String,Object>> byId=new HashMap<>();
    for(ClinicalNoteSnapshot n:snapshots){
      String reason;
      if(n.cryptoVersion==null||n.cryptoVersion<2)reason="LEGACY_METADATA";
      else if(!verified.contains(n.id))reason="UNAVAILABLE";
      else if(!Integer.valueOf(3).equals(n.cryptoVersion)||!"millisecond_upper_bound".equals(n.timePrecision))reason="TIME_PRECISION_UNKNOWN";
      else if(n.syncedAt==null)reason="SYNC_TIME_UNKNOWN";
      else if(!n.availableTimeConfirmed||n.availableAt==null)reason="AVAILABILITY_UNKNOWN";
      else if(n.timeConflict||n.documentedAt!=null&&n.documentedAt.isAfter(n.availableAt)||n.availableAt.isAfter(n.syncedAt))reason="TIME_CONFLICT";
      else if(n.availableAt.isAfter(asOf))reason="SOURCE_FUTURE";
      else if(n.syncedAt.isAfter(asOf))reason="LOCAL_FUTURE";
      else if(!n.documentTypeConfirmed)reason="TYPE_UNKNOWN";
      else if(!n.identityConfirmed||n.documentKey==null||n.documentKey.isBlank()||n.versionKey==null||n.versionKey.isBlank())reason="IDENTITY_UNKNOWN";
      else reason="READY";
      boolean authenticated=n.cryptoVersion!=null&&n.cryptoVersion>=2&&n.cryptoVersion<=3&&verified.contains(n.id);
      boolean boundedTime=authenticated&&Integer.valueOf(3).equals(n.cryptoVersion)&&"millisecond_upper_bound".equals(n.timePrecision);
      Map<String,Object> row=new LinkedHashMap<>();row.put("snapshotId",n.id);row.put("documentType",authenticated?n.documentType:"文书元数据待核对");
      row.put("documentedAt",authenticated?n.documentedAt:null);row.put("availableAt",boundedTime?n.availableAt:null);row.put("syncedAt",boundedTime?n.syncedAt:null);
      row.put("timeMeaning",authenticated?n.timeMeaning:"unknown");mark(row,reason);rows.add(row);byId.put(n.id,row);
      if("READY".equals(reason))families.computeIfAbsent(n.documentKey,k->new ArrayList<>()).add(n);
    }
    for(List<ClinicalNoteSnapshot> family:families.values()) {
      if(family.size()>1&&family.stream().anyMatch(n->!n.versionOrderConfirmed)){for(ClinicalNoteSnapshot n:family)mark(byId.get(n.id),"VERSION_ORDER_UNKNOWN");continue;}
      Instant latest=family.stream().map(n->n.availableAt).max(Instant::compareTo).orElseThrow();
      List<ClinicalNoteSnapshot> newest=new ArrayList<>();for(ClinicalNoteSnapshot n:family)if(n.availableAt.equals(latest))newest.add(n);else mark(byId.get(n.id),"OLDER_VERSION");
      if(newest.size()>1)for(ClinicalNoteSnapshot n:newest)mark(byId.get(n.id),"VERSION_CONFLICT");
    }
    rows.sort(Comparator.comparing((Map<String,Object> row)->(Instant)row.get("availableAt"),Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(row->String.valueOf(row.get("snapshotId"))));
    int ready=0,unknown=0,excluded=0;for(Map<String,Object> row:rows){if(Boolean.TRUE.equals(row.get("eligible")))row.put("ref","N"+(++ready));else if(row.get("eligible")==null)unknown++;else excluded++;}
    Map<String,Object> out=new LinkedHashMap<>();out.put("ruleVersion",VERSION);out.put("asOf",asOf);out.put("items",rows);
    out.put("ready",ready);out.put("unknown",unknown);out.put("excluded",excluded);out.put("inspected",rows.size());
    out.put("modelInvoked",false);out.put("clinicalConclusion",null);
    out.put("timePolicy","来源可用及本地采集时间使用不早于原时刻的毫秒上界；本地时间为取得文书后的采集记录，不是数据库事务提交时刻。");
    out.put("scope","本地病程证据时间核对，不是感染判断或 AI 综合复核结论；当前同步状态不能证明历史时点来源完整。");
    out.put("limitations",List.of("来源可用时间和本地保存时间均须不晚于复核时点；文书时间不能代替可用时间。","未知、旧快照元数据、版本冲突独立列出，不当作不满足临床条件。","只在已确认的文书标识关系内比较版本；不同文书的相反证据保留供医生查看。","本接口不向模型发送正文，不改变 ICU-05、预警或正式病历。"));return out;
  }
  static void mark(Map<String,Object> row,String reason){row.put("reason",reason);row.put("message",REASONS.get(reason));
    boolean excluded=Set.of("SOURCE_FUTURE","LOCAL_FUTURE","OLDER_VERSION").contains(reason);
    row.put("eligible","READY".equals(reason)?Boolean.TRUE:excluded?Boolean.FALSE:null);row.put("state","READY".equals(reason)?"ready":excluded?"excluded":"unknown");}
}
