import type {Observation} from './api';
import type {Measure} from './trends';
function segments(points:Observation[],gap:number){const out:Observation[][]=[];for(const p of points){const last=out.at(-1);if(!last||Date.parse(p.eventTime)-Date.parse(last.at(-1)!.eventTime)>gap*60000)out.push([p]);else last.push(p);}return out;}
function pointsFor(rows:Observation[],m:Measure,from:number,to:number){const a=rows.filter(o=>o.parameterCode===m.code&&o.unit===m.unit&&o.qualityStatus==='valid'&&o.clinicalUse!=='context_only'&&o.encounterLinkStatus!=='unconfirmed'&&o.value!==null&&Number.isFinite(o.value)&&o.value>=m.min&&o.value<=m.max&&Date.parse(o.eventTime)>=from&&Date.parse(o.eventTime)<=to&&(!o.ingestTime||Date.parse(o.ingestTime)<=to)).sort((a,b)=>Date.parse(a.eventTime)-Date.parse(b.eventTime));return a.filter((p,i)=>!a.some(q=>q.eventTime===p.eventTime&&q.value!==p.value)&&a.findIndex(q=>q.eventTime===p.eventTime)===i);}
export interface MedicationEvent{id:string;name:string;action:string;time:string;sourceSystem:string;sourceRecordId:string;availabilityKnown:boolean;}
export interface TimelinePacket{patientId:string;encounterId:string;mode:string;from:string;asOf:string;items:Observation[];medications:MedicationEvent[];medicationStatus:string;warnings:string[];limit:number;}
export interface Track{code:string;label:string;unit:string;kind:'vital'|'lab';color:string;points:Observation[];candidates:Observation[];segments:Observation[][];low:number;high:number;excluded:number;}
const labCodes=new Set(['PLT','TBIL','CREA','LACTATE','PLATELET','PLATELETS','BILIRUBIN','CREATININE','PF_RATIO','WBC','CRP','PCT','PH','PAO2','PACO2']);
const units:Record<string,string>={HR:'bpm',RR:'/min',SPO2:'%',TEMP:'°C',GCS:'score',LACTATE:'mmol/L'};
const ranges:Record<string,[number,number]>={HR:[10,300],RR:[1,100],SPO2:[0,100],TEMP:[25,45],GCS:[3,15],LACTATE:[0,40]};
export const presets:Record<string,string[]>={circulation:['MAP_IBP','MAP_NIBP','HR','LACTATE'],respiratory:['RR','SPO2','PF_RATIO'],infection:['TEMP','HR','LACTATE','WBC','CRP','PCT']};
function pressureName(code:string){const p=/^(MAP|SBP|DBP)(?:_(IBP|NIBP))?$/.exec(code);return p?({MAP:'平均动脉压',SBP:'收缩压',DBP:'舒张压'} as Record<string,string>)[p[1]]+(p[2]==='IBP'?'（有创）':p[2]==='NIBP'?'（无创）':''):'';}
export const metricName=(code:string)=>({HR:'心率',RR:'呼吸频率',TEMP:'体温',SPO2:'血氧饱和度',GCS:'意识评分',LACTATE:'乳酸',PF_RATIO:'氧合指数 P/F',PLT:'血小板',TBIL:'总胆红素',CREA:'肌酐',PLATELET:'血小板',PLATELETS:'血小板',BILIRUBIN:'胆红素',CREATININE:'肌酐',WBC:'白细胞',CRP:'C反应蛋白',PCT:'降钙素原',PH:'pH',PAO2:'氧分压',PACO2:'二氧化碳分压'} as Record<string,string>)[code]||pressureName(code)||code;
/** Rendering reduction only. Retains the first/last and both extrema of every occupied bin. */
export function envelope(points:Observation[],from:number,to:number,bins=240):Observation[]{
 if(points.length<=bins*2)return points;
 const buckets=new Map<number,Observation[]>();for(const p of points){const b=Math.min(bins-1,Math.max(0,Math.floor((Date.parse(p.eventTime)-from)/(to-from)*bins)));const a=buckets.get(b)||[];a.push(p);buckets.set(b,a);}
 const keep=new Set<Observation>();for(const rows of buckets.values()){keep.add(rows[0]);keep.add(rows.at(-1)!);keep.add(rows.reduce((a,b)=>a.value!<b.value!?a:b));keep.add(rows.reduce((a,b)=>a.value!>b.value!?a:b));}return points.filter(p=>keep.has(p));
}
export function buildTrack(code:string,rows:Observation[],from:number,to:number):Track{
 const selected=rows.filter(o=>o.parameterCode===code),knownUnits=[...new Set(selected.map(o=>o.unit).filter(Boolean))];
 const unit=units[code]||(code.startsWith('MAP')||code.startsWith('SBP')||code.startsWith('DBP')?'mmHg':knownUnits.length===1?knownUnits[0]!:'');
 const [min,max]=ranges[code]||(code.match(/^(MAP|SBP|DBP)/)?[10,300]:[-1e6,1e6]);
 const m:Measure={code,label:metricName(code),unit,color:'#456bcc',min,max,gapMinutes:code==='TEMP'?270:150};
 const points=unit?pointsFor(rows,m,from,to):[];
 const candidates=unit?selected.filter(o=>(o.clinicalUse==='context_only'||o.encounterLinkStatus==='unconfirmed')&&o.unit===unit&&o.value!=null&&Number.isFinite(o.value)&&o.value>=min&&o.value<=max&&Date.parse(o.eventTime)>=from&&Date.parse(o.eventTime)<=to&&(!o.ingestTime||Date.parse(o.ingestTime)<=to)):[];
 const all=[...points,...candidates],values=all.map(o=>o.value!);const lo=values.length?Math.min(...values):0,hi=values.length?Math.max(...values):1,pad=Math.max((hi-lo)*.15,code==='TEMP'||code==='LACTATE'?.3:1);
 return{code,label:m.label,unit,kind:labCodes.has(code)?'lab':'vital',color:code==='LACTATE'?'#9c4760':code==='TEMP'?'#a66b25':'#456bcc',points,candidates,segments:labCodes.has(code)?[]:segments(points,m.gapMinutes).map(s=>envelope(s,from,to)),low:lo-pad,high:hi+pad,excluded:selected.length-all.length};
}
export function nearest(points:Observation[],at:number){return points.reduce<Observation|undefined>((a,b)=>!a||Math.abs(Date.parse(b.eventTime)-at)<Math.abs(Date.parse(a.eventTime)-at)?b:a,undefined);}
