import type {Track,MedicationEvent} from './timeline-model';
export function interventionComparison(tracks:Track[],event:MedicationEvent,hours:number,from:number,to:number){
 const anchor=Date.parse(event.time),window=hours*3600000;if(!Number.isFinite(anchor)||anchor<from||anchor>to||![1,3,6,12,24].includes(hours))return [];
 return tracks.map(track=>{const before=track.points.filter(p=>Date.parse(p.eventTime)>=Math.max(from,anchor-window)&&Date.parse(p.eventTime)<anchor);const after=track.points.filter(p=>Date.parse(p.eventTime)>=anchor&&Date.parse(p.eventTime)<=Math.min(to,anchor+window));const b=before.at(-1)||null,a=after[0]||null;return {code:track.code,label:track.label,unit:track.unit,before:b,after:a,beforeCount:before.length,afterCount:after.length,delta:b&&a&&b.unit===a.unit?Math.round((a.value!-b.value!)*1000)/1000:null,beforeComplete:anchor-window>=from,afterComplete:anchor+window<=to};});
}
