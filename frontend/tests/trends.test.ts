import test from 'node:test';
import assert from 'node:assert/strict';
import { measures, validPoint, pointsFor, segments, stale } from '../src/trends.ts';
import type { Observation } from '../src/api.ts';
const end = Date.parse('2026-10-28T03:00:00Z');
const m = measures.find((x) => x.code === 'MAP')!;
function o(age = 0, value: number | null = 65, patch: Partial<Observation> = {}): Observation {
  return {
    id: String(age),
    parameterCode: 'MAP',
    value,
    unit: 'mmHg',
    text: null,
    eventTime: new Date(end - age * 60000).toISOString(),
    ingestTime: new Date(end - age * 60000).toISOString(),
    sourceSystem: 'MOCK_ONLY',
    sourceRecordId: String(age),
    qualityStatus: 'valid',
    ...patch,
  };
}
test('units never silently converted or mixed', () =>
  assert.equal(validPoint(o(0, 8, { unit: 'kPa' }), m, end), false));
test('zero is not used for missing observation', () =>
  assert.equal(validPoint(o(0, null), m, end), false));
test('invalid quality excluded', () =>
  assert.equal(validPoint(o(0, 65, { qualityStatus: 'invalid' }), m, end), false));
test('future and not yet ingested points excluded', () => {
  assert.equal(validPoint(o(-1), m, end), false);
  assert.equal(
    validPoint(o(1, 65, { ingestTime: new Date(end + 60000).toISOString() }), m, end),
    false,
  );
});
test('historical freshness does not erase stored past curve', () =>
  assert.equal(pointsFor([o(240)], m, end - 12 * 3600000, end).length, 1));
test('current old reading explicitly marked stale', () =>
  assert.equal(stale(o(240), m, end), true));
test('time window is applied and points sorted chronologically', () =>
  assert.deepEqual(
    pointsFor([o(1), o(40), o(5)], m, end - 10 * 60000, end).map((x) => x.id),
    ['5', '1'],
  ));
test('long gap produces separate line segments', () =>
  assert.equal(segments(pointsFor([o(100), o(85), o(10)], m, 0, end), 30).length, 2));
test('sparse single reading remains one point, not a fabricated curve', () =>
  assert.equal(segments([o()], 30)[0].length, 1));
test('same-time conflicting values are not averaged or chosen silently', () =>
  assert.equal(pointsFor([o(0, 65), o(0, 75, { id: 'other' })], m, 0, end).length, 0));
test('same-time identical values deduplicated for plotting only', () =>
  assert.equal(pointsFor([o(), o(0, 65, { id: 'other' })], m, 0, end).length, 1));
test('SpO2 and temperature keep separate units and plausible structural ranges', () => {
  assert.equal(validPoint(o(0, 98, { parameterCode: 'SPO2', unit: '%' }), measures[3], end), true);
  assert.equal(
    validPoint(o(0, 180, { parameterCode: 'TEMP', unit: '°C' }), measures[4], end),
    false,
  );
});
