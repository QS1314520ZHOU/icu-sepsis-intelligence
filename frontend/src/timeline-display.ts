/** Display preferences only: never includes patient identifiers, records or clinical sampling rules. */
export interface TimelineDisplay {rowHeight:number;medicationHeight:number;grid:'auto'|'sparse'|'detailed';points:'auto'|'all';}
export type DensityPreset='compact'|'standard'|'spacious';
export const DISPLAY_KEY='icu-sepsis.timeline-display.v1';
const presets:Record<DensityPreset,TimelineDisplay>={compact:{rowHeight:64,medicationHeight:36,grid:'auto',points:'auto'},standard:{rowHeight:94,medicationHeight:50,grid:'auto',points:'auto'},spacious:{rowHeight:132,medicationHeight:66,grid:'auto',points:'auto'}};
export function displayPreset(name:DensityPreset):TimelineDisplay{return {...presets[name]};}
export function normalizeDisplay(raw:unknown):TimelineDisplay{const d=displayPreset('compact');if(!raw||typeof raw!=='object')return d;const r=raw as Record<string,unknown>;const size=(v:unknown,low:number,high:number,fallback:number)=>typeof v==='number'&&Number.isFinite(v)?Math.min(high,Math.max(low,Math.round(v/2)*2)):fallback;return{rowHeight:size(r['rowHeight'],56,160,d.rowHeight),medicationHeight:size(r['medicationHeight'],32,80,d.medicationHeight),grid:r['grid']==='sparse'||r['grid']==='detailed'?r['grid']:'auto',points:r['points']==='all'?'all':'auto'};}
export function decodeDisplay(raw:string|null):TimelineDisplay{try{return normalizeDisplay(raw?JSON.parse(raw):null);}catch{return displayPreset('compact');}}
export function encodeDisplay(raw:TimelineDisplay){return JSON.stringify(normalizeDisplay(raw));}
export function densityName(d:TimelineDisplay){for(const key of Object.keys(presets) as DensityPreset[])if(JSON.stringify(d)===JSON.stringify(presets[key]))return{compact:'紧凑',standard:'标准',spacious:'宽松'}[key];return '自定义';}
