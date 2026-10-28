import test from 'node:test';
import assert from 'node:assert/strict';
import { riskText, percentage, text } from '../src/presentation.ts';
test('missing model never a percentage', () =>
  assert.equal(riskText('unavailable', null), '无法评估 · 模型不可用'));
test('rule score explicitly non probability', () =>
  assert.match(riskText('rule_estimate', 2), /非概率/));
test('unvalidated trained source not shown as probability', () =>
  assert.match(riskText('trained_model', 0.8), /不显示概率/));
test('zero denominator is not zero completion', () =>
  assert.equal(percentage(null), '—（无适用分母）'));
test('real zero is shown as zero', () => assert.equal(percentage(0), '0.0%'));
test('clinical statuses visible as text', () => assert.equal(text('met_late'), '超时完成'));
