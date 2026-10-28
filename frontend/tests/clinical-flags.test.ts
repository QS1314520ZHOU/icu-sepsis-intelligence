import test from 'node:test';
import assert from 'node:assert/strict';
import { ago, direction, level } from '../src/clinical-flags.ts';
import { sendSepsisStatus } from '../src/smartcare-protocol.ts';

test('missing values are never flagged as normal', () => {
  assert.equal(level('MAP', null), 'none');
  assert.equal(level('LACTATE', undefined), 'none');
  assert.equal(level('MAP_IBP', Number.NaN), 'none');
});
test('pressure channels share the MAP thresholds and direction words', () => {
  assert.equal(level('MAP_IBP', 58), 'critical');
  assert.equal(level('MAP_NIBP', 68), 'abnormal');
  assert.equal(level('MAP', 75), 'normal');
  assert.equal(direction('MAP_IBP', 58), '偏低');
  assert.equal(direction('MAP_NIBP', 76), '', 'normal value has no direction word');
});
test('lactate, respiration, GCS and SBP boundaries', () => {
  assert.equal(level('LACTATE', 1.9), 'normal');
  assert.equal(level('LACTATE', 2), 'abnormal');
  assert.equal(level('LACTATE', 4), 'critical');
  assert.equal(level('RR', 22), 'abnormal');
  assert.equal(level('RR', 30), 'critical');
  assert.equal(level('GCS', 15), 'normal');
  assert.equal(level('GCS', 14), 'abnormal');
  assert.equal(level('GCS', 8), 'critical');
  assert.equal(level('SBP', 100), 'abnormal');
  assert.equal(level('SBP', 101), 'normal');
  assert.equal(direction('RR', 28), '偏快');
});
test('elapsed time is human readable and empty for invalid times', () => {
  const now = Date.parse('2026-01-01T12:00:00Z');
  assert.equal(ago('2026-01-01T11:52:00Z', now), '8 分钟前');
  assert.equal(ago('2026-01-01T09:30:00Z', now), '2.5 小时前');
  assert.equal(ago('2025-12-29T12:00:00Z', now), '3 天前');
  assert.equal(ago(null, now), '');
});
test('status message refuses wildcard origin and outside-iframe use', () => {
  const s = { patientId: 'p', level: 'critical', summary: 'MAP 58 偏低', pendingAlerts: null, pendingTasks: 0 };
  const sent: unknown[] = [];
  const parent = { postMessage: (m: unknown, o: string) => sent.push([m, o]) };
  (globalThis as any).window = { parent };
  assert.equal(sendSepsisStatus(s, '*'), false);
  assert.equal(sendSepsisStatus(s, 'https://host.example'), true);
  const [m, o] = sent[0] as [Record<string, unknown>, string];
  assert.equal(o, 'https://host.example');
  assert.equal(m.type, 'SmartCareSepsisStatus');
  assert.equal(m.pendingAlerts, null, 'unknown count stays null');
  (globalThis as any).window = { parent: undefined };
  (globalThis as any).window.parent = (globalThis as any).window;
  assert.equal(sendSepsisStatus(s, 'https://host.example'), false);
  delete (globalThis as any).window;
});
