/** AI-assisted explanation (RAG) contract. Pure helpers only, so they can be unit-tested without Angular. */
export type AiKind = 'summary' | 'icu05';
export type AiStatus = 'ok' | 'disabled' | 'busy' | 'unavailable' | 'invalid_output';
export interface AiItem { text: string; refs: string[]; urgency?: 'high' | 'medium' | 'low'; }
export interface AiFact { id: string; category: string; text: string; time: string | null; }
export interface AiKnowledge { ref: string; id: string; docId: string; title: string; source: string; version: string; section: string; strength: string; text: string; url?: string | null; }
export interface AiBrief {
  interactionId: string; patientId: string; encounterId: string; kind: AiKind; status: AiStatus; message: string;
  model: string | null; modelRole: string | null; fallbackUsed: boolean; latencyMs: number | null; generatedAt: string;
  summary: AiItem[]; gaps: AiItem[]; suggestions: AiItem[]; uncertainties: AiItem[];
  facts: AiFact[]; knowledge: AiKnowledge[]; droppedCount: number; disclaimer: string; clinicalUseAllowed: false;
}
export interface RefTarget { ref: string; kind: 'fact' | 'knowledge'; title: string; body: string; meta: string; }
export type SectionKey = 'summary' | 'gaps' | 'suggestions' | 'uncertainties';

export const sectionTitles: { key: SectionKey; title: string }[] = [
  { key: 'summary', title: '要点' }, { key: 'gaps', title: '数据缺口' }, { key: 'suggestions', title: '可考虑核对' }, { key: 'uncertainties', title: '不确定' },
];
export const kindLabel: Record<AiKind, string> = { summary: '病情摘要', icu05: 'ICU-05 缺项分析' };
export const urgencyLabel: Record<string, string> = { high: '优先', medium: '', low: '' };

/** Resolve a reference chip; unknown references resolve to null and are not rendered. */
export function refTarget(ref: string, b: Pick<AiBrief, 'facts' | 'knowledge'>): RefTarget | null {
  if (/^E\d+$/.test(ref)) {
    const f = b.facts.find(x => x.id === ref);
    return f ? { ref, kind: 'fact', title: f.category, body: f.text, meta: '本次核对的患者事实（规则引擎输出）' } : null;
  }
  if (/^K\d+$/.test(ref)) {
    const k = b.knowledge.find(x => x.ref === ref);
    return k ? { ref, kind: 'knowledge', title: k.title + ' · ' + k.section, body: k.text, meta: [k.strength, k.source].filter(Boolean).join('；') } : null;
  }
  return null;
}

/** Only items whose every reference resolves are shown; the backend already validates, this guards stale data. */
export function visibleItems(items: AiItem[] | undefined, b: Pick<AiBrief, 'facts' | 'knowledge'>, allowNoRef = false): AiItem[] {
  return (items || []).filter(i => !!i.text && (allowNoRef || i.refs.length > 0) && i.refs.every(r => refTarget(r, b) !== null));
}

export function statusNote(b: Pick<AiBrief, 'status' | 'message'> | null): string {
  if (!b) return '';
  if (b.status === 'ok') return b.message || '';
  return b.message || '本次未生成 AI 内容。';
}

export function hasContent(b: AiBrief | null): boolean {
  return !!b && b.status === 'ok' && sectionTitles.some(s => visibleItems(b[s.key], b, s.key === 'uncertainties').length > 0);
}

export function acceptsBrief(b: Pick<AiBrief, 'patientId' | 'encounterId'> | null, id: string, encounter: string): boolean {
  return !!b && b.patientId === id && (!encounter || b.encounterId === encounter);
}

export const rejectReasons = ['内容与原始记录不符', '引用依据不对应', '遗漏关键问题', '表述不当或越界', '对当前处置无帮助'];
