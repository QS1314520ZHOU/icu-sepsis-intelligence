import test from 'node:test';
import assert from 'node:assert/strict';
import { measuresFor, measures, pointsFor, validPoint, evidenceNote, timeLabel, label } from '../src/trends.ts';
import type { Observation } from '../src/api.ts';
const now=Date.parse('2026-10-28T08:00:00Z');
function o(code:string,value=65,patch:Partial<Observation>={}):Observation{return{id:code+value,parameterCode:code,value,unit:'mmHg',text:null,eventTime:new Date(now).toISOString(),ingestTime:null,ingestTimeBasis:'unknown',clinicalUse:'review',sourceSystem:'SYNTHETIC',sourceRecordId:'fixture',qualityStatus:'valid',...patch};}
test('pressure channels have separate plots and no generic merged MAP',()=>{const ms=measuresFor([o('MAP_IBP'),o('MAP_NIBP')]);assert.equal(ms.length,7);assert.equal(ms.filter(m=>m.code==='MAP').length,0);assert.ok(ms.some(m=>m.label.includes('有创')));assert.ok(ms.some(m=>m.label.includes('无创')));});
test('simultaneous different-channel values do not remove one another',()=>{const rows=[o('MAP_IBP',58),o('MAP_NIBP',76)];for(const m of measuresFor(rows).filter(m=>m.code.startsWith('MAP_')))assert.equal(pointsFor(rows,m,0,now).length,1);});
test('conflicts are still excluded within the same pressure channel',()=>{const rows=[o('MAP_IBP',58),o('MAP_IBP',68),o('MAP_NIBP',76)];const ms=measuresFor(rows);assert.equal(pointsFor(rows,ms.find(m=>m.code==='MAP_IBP')!,0,now).length,0);assert.equal(pointsFor(rows,ms.find(m=>m.code==='MAP_NIBP')!,0,now).length,1);});
test('context-only reports do not become a current-patient lactate curve',()=>{const lac=o('LACTATE',4.2,{unit:'mmol/L',clinicalUse:'context_only',encounterLinkStatus:'unconfirmed'});assert.equal(validPoint(lac,measures.find(m=>m.code==='LACTATE')!,now),false);});
test('unknown source ingestion is explicit not manufactured',()=>{const m=measures.find(m=>m.code==='MAP')!;assert.equal(validPoint(o('MAP'),m,now),true);assert.equal(validPoint(o('MAP',65,{ingestTimeBasis:undefined}),m,now),false);assert.equal(validPoint(o('MAP',65,{ingestTime:new Date(now+60000).toISOString()}),m,now),false);});
test('reported time and unconfirmed stay have visible labels',()=>{const note=evidenceNote(o('LACTATE',4.2,{timeBasis:'reported',encounterLinkStatus:'unconfirmed',clinicalUse:'context_only'}));assert.match(note,/采样时间未知/);assert.match(note,/本次住院关联未确认/);assert.match(timeLabel('reported'),/报告/);assert.equal(label('SBP_NIBP'),'收缩压（无创）');});
test('legacy demo plots remain unchanged',()=>assert.equal(measuresFor([o('MAP')]),measures));
