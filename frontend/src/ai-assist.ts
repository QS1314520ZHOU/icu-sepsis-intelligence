/** AI-assisted explanation (RAG) contract. Pure helpers only, so they can be unit-tested without Angular. */
export type AiKind = 'summary' | 'icu05' | 'trend' | 'handover' | 'note' | 'family' | 'alert' | 'ask' | 'ward';
export type AiStatus = 'ok' | 'disabled' | 'busy' | 'unavailable' | 'invalid_output';
export interface AiItem { text: string; refs: string[]; urgency?: 'high' | 'medium' | 'low'; }
export interface AiFact { id: string; category: string; text: string; time: string | null; }
export interface AiKnowledge { ref: string; id: string; docId: string; title: string; source: string; version: string; section: string; strength: string; text: string; url?: string | null; }
export interface AiBrief {
  interactionId: string; patientId: string; encounterId: string; kind: AiKind; status: AiStatus; message: string;
  model: string | null; modelRole: string | null; fallbackUsed: boolean; latencyMs: number | null; generatedAt: string;
  summary: AiItem[]; gaps: AiItem[]; suggestions: AiItem[]; uncertainties: AiItem[];
  facts: AiFact[]; knowledge: AiKnowledge[]; droppedCount: number; disclaimer: string; clinicalUseAllowed: false;
  /** v2: per-kind output layout; absent on v1 responses (falls back to sectionTitles). */
  layout?: AiSection[]; label?: string; alertId?: string | null; question?: string;
  /** Ward brief only: pseudonym (P1…) → bed/name, resolved in the browser, never sent to the model. */
  patients?: Record<string, string>;
  [section: string]: unknown;
}
export interface AiSection { key: string; title: string; allowNoRef: boolean; urgency?: boolean; }
export interface RefTarget { ref: string; kind: 'fact' | 'knowledge'; title: string; body: string; meta: string; }
export type SectionKey = string;

export const sectionTitles: { key: SectionKey; title: string }[] = [
  { key: 'summary', title: '要点' }, { key: 'gaps', title: '数据缺口' }, { key: 'suggestions', title: '可考虑核对' }, { key: 'uncertainties', title: '不确定' },
];
export const kindLabel: Record<AiKind, string> = {
  summary: '病情摘要', icu05: 'ICU-05 缺项分析', trend: '趋势解读', handover: '交班摘要', note: '病程记录草稿', family: '家属沟通要点',
  alert: '预警解读', ask: '提问', ward: '科室优先级',
};
/** Buttons on the patient card, in display order. */
export const patientKinds: { kind: AiKind; label: string; hint: string; needsIcu05?: boolean }[] = [
  { kind: 'summary', label: '病情摘要', hint: '异常、数据缺口与可考虑核对的事项' },
  { kind: 'trend', label: '趋势解读', hint: '近 24 小时指标变化与需警惕的趋势' },
  { kind: 'handover', label: '交班摘要', hint: 'SBAR 结构，可直接复制交班' },
  { kind: 'note', label: '病程记录草稿', hint: '客观病程记录，医生修改后使用' },
  { kind: 'family', label: '家属沟通要点', hint: '通俗语言的沟通提纲' },
  { kind: 'icu05', label: 'ICU-05 缺项分析', hint: '逐时窗解释 Bundle 判定', needsIcu05: true },
];
/** Kinds whose output is meant to be pasted elsewhere. */
export const copyKinds = new Set<string>(['handover', 'note', 'family', 'summary', 'trend', 'ward']);
export const urgencyLabel: Record<string, string> = { high: '优先', medium: '', low: '' };

export function layoutOf(b: Pick<AiBrief, 'layout'> | null): AiSection[] {
  if (b?.layout?.length) return b.layout;
  return sectionTitles.map(s => ({ key: s.key, title: s.title, allowNoRef: s.key === 'uncertainties', urgency: s.key === 'suggestions' }));
}
export function sectionItems(b: AiBrief | null, s: AiSection): AiItem[] {
  return b ? visibleItems(b[s.key] as AiItem[] | undefined, b, s.allowNoRef) : [];
}
/** Ward briefs: replace P# pseudonyms with the bed / name the browser already knows. */
export function resolvePatients(text: string, patients?: Record<string, string>): string {
  if (!patients) return text;
  return text.replace(/\bP(\d{1,2})\b/g, (m) => patients[m] ? patients[m] + '（' + m + '）' : m);
}
/** Plain text for the clipboard: section titles + items, references removed. */
export function copyText(b: AiBrief): string {
  const out: string[] = [];
  for (const s of layoutOf(b)) {
    const items = sectionItems(b, s);
    if (!items.length) continue;
    out.push('【' + s.title + '】');
    items.forEach((i, n) => out.push((n + 1) + '. ' + resolvePatients(i.text, b.patients)));
  }
  return out.join('\n');
}

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
  return !!b && b.status === 'ok' && layoutOf(b).some(s => sectionItems(b, s).length > 0);
}

export function acceptsBrief(b: Pick<AiBrief, 'patientId' | 'encounterId'> | null, id: string, encounter: string): boolean {
  if (b && b.patientId === null && id === '') return true;
  return !!b && b.patientId === id && (!encounter || b.encounterId === encounter);
}

export const rejectReasons = ['内容与原始记录不符', '引用依据不对应', '遗漏关键问题', '表述不当或越界', '对当前处置无帮助'];
