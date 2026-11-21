import test from 'node:test';
import assert from 'node:assert/strict';
import {localEvidenceTime} from '../src/specialty-evidence-time.ts';
test('editing an evidence time preserves seconds and milliseconds on round trip', () => {
  for (const instant of ['2026-11-20T08:15:43.527Z', '2026-11-20T23:59:59.999Z']) {
    assert.equal(new Date(localEvidenceTime(instant)).toISOString(), instant);
  }
});
test('missing or malformed evidence times stay empty rather than defaulting to now', () => {
  assert.equal(localEvidenceTime(), '');
  assert.equal(localEvidenceTime('invalid'), '');
});
