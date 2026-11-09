import type {ClinicalReview} from './clinical-review';
import type {Icu05Result} from './icu05-view';
export type ClinicalStep='screening'|'review'|'evidence'|'bundle';
export interface PathStep {key:ClinicalStep;title:string;status:string;tone:'warn'|'info'|'muted';}
export function clinicalPath(review:ClinicalReview|null,bundle:Icu05Result|null,pending:number|null){
 const ews=review?.earlyWarning,warning=!!(ews?.triggered||review?.findings.length||(pending??0)>0);
 const pendingK1=bundle?.gate?.shock.some(i=>i.code==='K1'&&i.reason==='K1_WINDOW_UNCONFIRMED')??false;
 const steps:PathStep[]=[
  {key:'screening',title:'早期筛查',status:!review?.available?'数据待获取':ews?.population==='not_adult'?'成人工具不适用':ews?.triggered?'达到复核阈值':ews?.status==='not_triggered'?'未达复核阈值':'观察项待核对',tone:ews?.triggered?'warn':'info'},
  {key:'review',title:'床旁复评',status:warning?'需医生复评':'复评记录待核对',tone:warning?'warn':'muted'},
  {key:'evidence',title:'感染 / 器官核对',status:review?.infectionClue.includes('含感染')?'已有线索，需临床核对':'感染证据待核对',tone:'info'},
  {key:'bundle',title:'Bundle 执行核对',status:pendingK1?'K1 窗口待确认':!bundle?.available?'措施记录待获取':!bundle.t0?'T0 待确认':Date.parse(bundle.asOf)-Date.parse(bundle.t0)>6*3600000?'查看历史措施':'核对已执行与待办',tone:'muted'}
 ];
 const focus:ClinicalStep=warning?'review':ews?.status==='unknown'||!ews?'screening':'evidence';
 return {steps,focus,headline:warning?'先完成床旁复评':focus==='screening'?'先核对缺失观察项':'核对感染与器官功能证据',action:warning?((pending??0)>0?'处理预警':'核对复评依据'):focus==='screening'?'查看观察项':'查看临床依据'};
}
