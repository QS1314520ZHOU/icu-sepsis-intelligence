import test from 'node:test';
import assert from 'node:assert/strict';
import { computeSofa } from '../src/sofa.ts';
import type { Observation } from '../src/api';

const now = Date.parse('2026-10-30T12:00:00+08:00');

function o(patch: Partial<Observation>): Observation {
  return {
    id: 'x',
    parameterCode: 'HR',
    value: 80,
    unit: 'bpm',
    text: null,
    eventTime: new Date(now - 3600000).toISOString(),
    ingestTime: null,
    sourceSystem: 'SmartCare.bedside',
    sourceRecordId: '1',
    qualityStatus: 'valid',
    clinicalUse: 'review',
    ...patch,
  } as Observation;
}

test('missing components stay null and total requires 4+ scores', () => {
  const r = computeSofa([], now, 'classic');
  assert.equal(r.total, null);
  assert.equal(r.scored, 0);
  assert.ok(r.components.every((c) => c.score === null));
});

test('classic SOFA bands from worst values', () => {
  const items = [
    o({ parameterCode: 'PF_RATIO', value: 250, unit: 'mmHg' }),
    o({ parameterCode: 'PLT', value: 80, unit: '10^9/L' }),
    o({ parameterCode: 'TBIL', value: 50, unit: 'μmol/L' }),
    o({ parameterCode: 'MAP_IBP', value: 65, unit: 'mmHg', measurementChannel: 'IBP' }),
    o({ parameterCode: 'GCS', value: 10, unit: 'score' }),
    o({ parameterCode: 'CREA', value: 200, unit: 'μmol/L' }),
  ];
  const r = computeSofa(items, now, 'classic');
  const by = Object.fromEntries(r.components.map((c) => [c.key, c.score]));
  assert.equal(by.resp, 2); // 200≤P/F<300
  assert.equal(by.coag, 2); // 50≤plt<100
  assert.equal(by.liver, 2); // 33≤tbil<102
  assert.equal(by.cardio, 1); // MAP<70
  assert.equal(by.cns, 2); // 10≤gcs<13
  assert.equal(by.renal, 2); // 170≤crea<300
  assert.equal(r.total, 11); // 2+2+2+1+2+2
  assert.equal(r.scored, 6);
});

test('pressor without dose uses min band 2 and flags', () => {
  const items = [
    o({ parameterCode: 'MAP_IBP', value: 80, unit: 'mmHg' }),
    o({ parameterCode: 'VASOPRESSOR_ACTIVE', value: 1, unit: 'boolean' }),
    o({ parameterCode: 'GCS', value: 15, unit: 'score' }),
    o({ parameterCode: 'PLT', value: 200, unit: '10^9/L' }),
    o({ parameterCode: 'CREA', value: 80, unit: 'μmol/L' }),
    o({ parameterCode: 'PF_RATIO', value: 400, unit: 'mmHg' }),
  ];
  const r = computeSofa(items, now, 'classic');
  const cardio = r.components.find((c) => c.key === 'cardio')!;
  assert.equal(cardio.score, 2);
  assert.ok(cardio.flags.includes('vaso_dose_unknown'));
});

test('manual override scores missing liver', () => {
  const items = [
    o({ parameterCode: 'PF_RATIO', value: 400, unit: 'mmHg' }),
    o({ parameterCode: 'PLT', value: 200, unit: '10^9/L' }),
    o({ parameterCode: 'MAP_IBP', value: 80, unit: 'mmHg' }),
    o({ parameterCode: 'GCS', value: 15, unit: 'score' }),
    o({ parameterCode: 'CREA', value: 80, unit: 'μmol/L' }),
  ];
  const auto = computeSofa(items, now, 'classic');
  assert.equal(auto.components.find((c) => c.key === 'liver')!.score, null);
  const manual = computeSofa(items, now, 'classic', { liver: 150 });
  const liver = manual.components.find((c) => c.key === 'liver')!;
  assert.equal(liver.score, 3); // 102≤150<204
  assert.equal(liver.source, 'manual');
  assert.equal(manual.total, 3); // other components score 0; liver 3
  assert.equal(manual.scored, 6);
});

test('sofa2 uses different bands', () => {
  const items = [
    o({ parameterCode: 'PF_RATIO', value: 250, unit: 'mmHg' }),
    o({ parameterCode: 'PLT', value: 90, unit: '10^9/L' }),
    o({ parameterCode: 'TBIL', value: 50, unit: 'μmol/L' }),
    o({ parameterCode: 'MAP_IBP', value: 80, unit: 'mmHg' }),
    o({ parameterCode: 'GCS', value: 10, unit: 'score' }),
    o({ parameterCode: 'CREA', value: 200, unit: 'μmol/L' }),
  ];
  const r = computeSofa(items, now, 'sofa2');
  const by = Object.fromEntries(r.components.map((c) => [c.key, c.score]));
  assert.equal(by.resp, 1); // 225≤P/F<300 → 1 (JAMA 2025)
  assert.equal(by.coag, 2); // 80≤plt<100
  assert.equal(by.cns, 2); // 9≤gcs<13
  assert.equal(by.renal, 2); // 2.0≤mg/dl<3.5
});

test('respiratory 3–4 bands are capped at 2 without ventilation data (classic and SOFA-2, auto and manual)', () => {
  const items = [o({ parameterCode: 'PF_RATIO', value: 80, unit: 'mmHg' })];
  for (const kind of ['classic', 'sofa2'] as const) {
    const resp = computeSofa(items, now, kind).components.find((c) => c.key === 'resp')!;
    assert.equal(resp.score, 2, kind);
    assert.ok(resp.flags.includes('resp_support_unknown'));
    const manual = computeSofa([], now, kind, { resp: 60 }).components.find((c) => c.key === 'resp')!;
    assert.equal(manual.score, 2, kind + ' manual');
  }
});

test('SOFA-2 cut-offs are inclusive (JAMA 2025)', () => {
  const items = [
    o({ parameterCode: 'PF_RATIO', value: 300, unit: 'mmHg' }),
    o({ parameterCode: 'PLT', value: 150, unit: '10^9/L' }),
    o({ parameterCode: 'TBIL', value: 1.2 * 17.104, unit: 'μmol/L' }),
    o({ parameterCode: 'CREA', value: 2.0 * 88.4, unit: 'μmol/L' }),
  ];
  const by = Object.fromEntries(computeSofa(items, now, 'sofa2').components.map((c) => [c.key, c.score]));
  assert.equal(by.resp, 1);
  assert.equal(by.coag, 1);
  assert.equal(by.liver, 0);
  assert.equal(by.renal, 1);
});

test('partial SOFA is not reported as the total', () => {
  const items = [
    o({ parameterCode: 'PLT', value: 40, unit: '10^9/L' }),
    o({ parameterCode: 'GCS', value: 8, unit: 'score' }),
    o({ parameterCode: 'MAP_IBP', value: 60, unit: 'mmHg' }),
    o({ parameterCode: 'CREA', value: 200, unit: 'μmol/L' }),
  ];
  const r = computeSofa(items, now, 'classic');
  assert.equal(r.scored, 4);
  assert.equal(r.total, null);
  assert.equal(r.partial, 3 + 3 + 1 + 2);
});

test('blank-unit creatinine that looks like mg/dL is not read as μmol/L', () => {
  const r = computeSofa([o({ parameterCode: 'CREA', value: 2.4, unit: '' })], now, 'classic');
  assert.equal(r.components.find((c) => c.key === 'renal')!.score, null);
  const ok = computeSofa([o({ parameterCode: 'CREA', value: 240, unit: '' })], now, 'classic');
  assert.equal(ok.components.find((c) => c.key === 'renal')!.score, 2);
});
