import test from 'node:test';
import assert from 'node:assert/strict';
import { bedsideActions, nextDue, relT0, fmtLeft, windowChips, lactateClearance, verdictBasis, elapsedSinceT0 } from '../src/icu05-view.ts';
import type { Icu05Result } from '../src/icu05-view.ts';

const T0 = Date.parse('2026-10-31T08:00:00Z'), at = (m: number) => new Date(T0 + m * 60000).toISOString();
const it = (code: string, value: boolean | null, m: number | null, detail = '', reason: string | null = null) => ({ code, label: code, value, time: m === null ? null : at(m), detail, reason });
const w = (h: number, state: any, items: any[]) => ({ key: h + 'h', hours: h, deadline: at(h * 60), closed: state !== 'in_progress', finish: null, state, finishPath: '', reasons: [], items });
function fixture(): Icu05Result {
  return { schemaVersion: 1, patientId: 'p', encounterId: 'e', available: true, message: '', asOf: at(220), ruleVersion: 'v3', ruleSource: '', status: 'septic_shock', statusLabel: '符合感染性休克判定',
    t0: at(0), t0OrderName: null, t0Source: null,
    gate: { organ: [it('S3', true, 10, '最低 58 mmHg（有创）'), it('S4', true, 35, '去甲肾上腺素注射液')], infection: [it('I1', true, null, '肺炎'), it('I2', true, 40, '美罗培南'), it('I3', false, null)],
      shock: [it('K1', true, 20, '4.6 mmol/L')], hasOrganDysfunction: true, hasInfection: true, isSepticShock: true, mapMin: 58, status: 'septic_shock', statusLabel: '', reason: null, rule: '' },
    windows: [
      w(1, 'not_met', [it('A1', true, 20, '4.6 mmol/L'), it('B1', true, 40, '美罗培南'), it('B2', true, 50), it('B3', false, null, '', 'BC_AFTER_AB'), it('C3', true, 30, '2 条执行')]),
      w(3, 'not_met', [it('A1', true, 20), it('B1', true, 40), it('B2', true, 50), it('B3', false, null), it('C3', true, 30, '累计 1600 ml')]),
      w(6, 'in_progress', [it('A1', true, 20), it('A2', null, null, '', 'LACTATE_RECHECK_MISSING'), it('B1', true, 40), it('C3', true, 30, '累计 1600 ml')]),
    ] };
}
test('relative T0 and duration formatting', () => {
  assert.equal(relT0(at(40), at(0)), '+0:40');
  assert.equal(relT0(at(-15), at(0)), 'T0 前 0:15');
  assert.equal(fmtLeft(140), '2h20m'); assert.equal(fmtLeft(120), '2h'); assert.equal(fmtLeft(-5), '0m');
  assert.equal(elapsedSinceT0(fixture()), '3h40m');
});
test('bedside actions: done with time from T0, antibiotics flagged before culture, recheck still due', () => {
  const a = bedsideActions(fixture());
  assert.deepEqual(a.map(x => x.code), ['A1', 'B2', 'B1', 'C3', 'A2']);
  assert.equal(a[0].state, 'done'); assert.equal(a[0].rel, '+0:20');
  assert.equal(a[2].warn, '早于血培养');
  assert.equal(a[3].detail, '累计 1600 ml', 'widest window speaks for fluids');
  assert.equal(a[4].state, 'todo'); assert.equal(a[4].deadline, at(360));
  const n = nextDue(fixture())!; assert.equal(n.name, '复测乳酸'); assert.equal(n.minutesLeft, 140);
});
test('closed windows without a result are unknown, not failed', () => {
  const d = fixture(); d.windows[2] = { ...d.windows[2], closed: true, state: 'unknown' };
  const a2 = bedsideActions(d).find(x => x.code === 'A2')!;
  assert.equal(a2.state, 'unknown'); assert.equal(a2.detail, '未复测乳酸');
  assert.equal(nextDue(d), null);
});
test('window chips, clearance and verdict basis', () => {
  assert.deepEqual(windowChips(fixture()).map(c => c.label), ['未达标', '未达标', '剩 2h20m']);
  assert.equal(lactateClearance(4.6, 3.5), 24); assert.equal(lactateClearance(null, 3), null); assert.equal(lactateClearance(0, 1), null);
  assert.deepEqual(verdictBasis(fixture()), ['首次乳酸 4.6', 'MAP 58（有创）', '去甲肾上腺素', '感染：肺炎 / 美罗培南']);
});
