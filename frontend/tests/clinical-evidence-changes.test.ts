import test from 'node:test';
import assert from 'node:assert/strict';
import {ClinicalEvidenceChanges} from '../src/clinical-evidence-changes.ts';
const directory = () => ({patientId:'synthetic',encounterId:'stay',total:1,status:'synced',complete:true,lastSuccessfulAt:'2026-11-20T08:00:00Z',lastCompleteAt:null});
test('new note revisions invalidate a specific stay; unchanged listings and paging do not', () => {
  const changes=new ClinicalEvidenceChanges();let events=0;changes.subscribe(()=>events++);
  changes.observeNotes(directory());const first=changes.revision('synthetic','stay');
  changes.observeNotes({...directory(),page:2} as ReturnType<typeof directory>);
  assert.equal(events,1);assert.equal(changes.revision('synthetic','stay'),first);
  changes.observeNotes({...directory(),total:2});assert.equal(events,2);assert.ok(changes.revision('synthetic','stay')>first);
  assert.equal(changes.revision('synthetic','other-stay'),0);assert.equal(changes.revision('other-patient','stay'),0);
});
test('source limitations change the evidence revision even when saved snapshot count is unchanged',()=>{
  const changes=new ClinicalEvidenceChanges();changes.observeNotes(directory());const first=changes.revision('synthetic','stay');
  changes.observeNotes({...directory(),status:'read_failed',complete:false});assert.ok(changes.revision('synthetic','stay')>first);
});
test('destroyed subscribers stop receiving changes and invalid contexts do not emit',()=>{
  const changes=new ClinicalEvidenceChanges();let events=0;const unsubscribe=changes.subscribe(()=>events++);
  changes.notify('','stay');changes.notify('synthetic','');assert.equal(events,0);
  unsubscribe();changes.notify('synthetic','stay');assert.equal(events,0);
});
test('bounded revisions do not reuse an evicted revision when a patient is seen again',()=>{
  const changes=new ClinicalEvidenceChanges();changes.observeNotes(directory());const first=changes.revision('synthetic','stay');
  for(let i=0;i<100;i++)changes.notify('synthetic-'+i,'stay');assert.equal(changes.revision('synthetic','stay'),0);
  changes.observeNotes(directory());assert.ok(changes.revision('synthetic','stay')>first);
});
