import test from 'node:test';
import assert from 'node:assert/strict';
import { acceptsBrief, hasContent, refTarget, statusNote, visibleItems, type AiBrief } from '../src/ai-assist.ts';

const base: AiBrief = {
  interactionId: 'ai-1', patientId: 'p1', encounterId: 'e1', kind: 'summary', status: 'ok', message: '', model: 'm', modelRole: 'fast',
  fallbackUsed: false, latencyMs: 1200, generatedAt: '2026-05-01T00:00:00Z',
  summary: [{ text: 'MAP 58', refs: ['E1'] }, { text: '编号不存在', refs: ['E9'] }], gaps: [], suggestions: [{ text: '核对血培养', refs: ['K1'], urgency: 'high' }],
  uncertainties: [{ text: '采样时间未确认', refs: [] }],
  facts: [{ id: 'E1', category: '观测', text: '平均动脉压（有创） 58 mmHg', time: null }],
  knowledge: [{ ref: 'K1', id: 'ssc26-7', docId: 'ssc-2026', title: 'SSC 2026', source: 'CCM 2026', version: '2026', section: '第7条', strength: '强推荐', text: '先培养后抗菌' }],
  droppedCount: 0, disclaimer: 'AI 生成', clinicalUseAllowed: false,
};

test('reference chips resolve to facts and knowledge only', () => {
  assert.equal(refTarget('E1', base)?.kind, 'fact');
  assert.equal(refTarget('K1', base)?.body, '先培养后抗菌');
  assert.equal(refTarget('E9', base), null);
  assert.equal(refTarget('X1', base), null);
});
test('items with unresolved refs are hidden', () => {
  assert.deepEqual(visibleItems(base.summary, base).map(i => i.text), ['MAP 58']);
  assert.equal(visibleItems(base.uncertainties, base).length, 0);
  assert.equal(visibleItems(base.uncertainties, base, true).length, 1);
});
test('non-ok status never counts as content', () => {
  assert.equal(hasContent(base), true);
  assert.equal(hasContent({ ...base, status: 'unavailable' }), false);
  assert.equal(hasContent({ ...base, summary: [], suggestions: [], uncertainties: [] }), false);
  assert.equal(statusNote({ status: 'unavailable', message: '' }), '本次未生成 AI 内容。');
  assert.equal(statusNote({ status: 'ok', message: '' }), '');
});
test('brief for another patient or encounter is rejected', () => {
  assert.equal(acceptsBrief(base, 'p1', 'e1'), true);
  assert.equal(acceptsBrief(base, 'p2', 'e1'), false);
  assert.equal(acceptsBrief(base, 'p1', 'e2'), false);
  assert.equal(acceptsBrief(null, 'p1', ''), false);
});
