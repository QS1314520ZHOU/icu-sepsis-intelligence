import type {AiBrief} from './ai-assist';
export interface DecisionChanges {unavailableReason?:string;first:boolean;previousAt:string|null;currentAt:string;addedFacts:string[];removedFacts:string[];addedAssessment:string[];removedAssessment:string[];}
const text=(value:string)=>value.replace(/\s+/g,' ').trim();
function difference(now:string[],before:string[]){const previous=new Set(before.map(text));return [...new Set(now.map(text))].filter(t=>t&&!previous.has(t));}
function assessment(brief:AiBrief){const b=brief as unknown as Record<string,unknown>;return ['assessment','support','against','priorities','gaps','summary','uncertainties'].flatMap(k=>Array.isArray(b[k])?(b[k] as {text:string}[]).map(i=>i.text):[]);}
function facts(brief:AiBrief){return brief.facts.map(f=>f.category+'：'+f.text+(f.time?' · '+f.time:''));}
export function compareDecisions(before:AiBrief|null,now:AiBrief):DecisionChanges{
 if(now.status!=='ok'||before&&(before.patientId!==now.patientId||before.encounterId!==now.encounterId||before.kind!==now.kind||before.status!=='ok'))throw new Error('Decision comparison context mismatch');
 return {first:!before,previousAt:before?.generatedAt||null,currentAt:now.generatedAt,addedFacts:before?difference(facts(now),facts(before)):[],removedFacts:before?difference(facts(before),facts(now)):[],addedAssessment:before?difference(assessment(now),assessment(before)):[],removedAssessment:before?difference(assessment(before),assessment(now)):[]};
}
export class DecisionHistory {
 private entries=new Map<string,{brief:AiBrief;changes:DecisionChanges}>();
 record(scope:string,brief:AiBrief){if(brief.status!=='ok')return null;const prior=this.entries.get(scope);if(prior&&(!Number.isFinite(Date.parse(brief.generatedAt))||Date.parse(brief.generatedAt)<Date.parse(prior.brief.generatedAt)))return {first:false,previousAt:prior.brief.generatedAt,currentAt:brief.generatedAt,addedFacts:[],removedFacts:[],addedAssessment:[],removedAssessment:[],unavailableReason:'本次返回较早缓存或时间未确认，未进行倒序对比'};if(prior?.brief.interactionId===brief.interactionId)return prior.changes;const changes=compareDecisions(prior?.brief||null,brief);if(this.entries.size>=20&&!this.entries.has(scope))this.entries.delete(this.entries.keys().next().value!);this.entries.set(scope,{brief,changes});return changes;}
 get(scope:string){return this.entries.get(scope)?.changes||null;}
}
