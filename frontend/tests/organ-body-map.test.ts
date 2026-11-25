import test from 'node:test';
import assert from 'node:assert/strict';
import {BODY_MAP_REGIONS,BODY_MAP_SILHOUETTE,organMapVisualState} from '../src/organ-body-map.ts';

test('reference body map provides separate anatomical paths for all six systems',()=>{
  assert.match(BODY_MAP_SILHOUETTE,/M104\.265/);
  assert.deepEqual(Object.keys(BODY_MAP_REGIONS).sort(),['circulatory','coagulation','hepatic','neurologic','renal','respiratory'].sort());
  for(const region of Object.values(BODY_MAP_REGIONS)){
    assert.ok(region.hotspots.length>0);
    assert.ok(region.outlines.length>0);
    assert.ok(region.outlines.every(path=>path.length>15));
  }
});

test('missing and unknown evidence stays neutral on the body map',()=>{
  assert.equal(organMapVisualState(undefined),'unknown');
  assert.equal(organMapVisualState('unknown'),'unknown');
  assert.equal(organMapVisualState('review_required'),'review_required');
  assert.equal(organMapVisualState('no_observed_trigger'),'no_observed_trigger');
});

test('liver is mirrored to the patient right in the front view and sits above the kidneys',()=>{
  const liver=BODY_MAP_REGIONS.hepatic;
  assert.equal(liver.transform,'translate(260 86.45) scale(-1 0.65)');
  assert.ok(liver.hotspots[0].cx<130,'patient right is screen-left in the front view');
  assert.ok(liver.hotspots[0].cy< BODY_MAP_REGIONS.renal.hotspots[0].cy);
});
