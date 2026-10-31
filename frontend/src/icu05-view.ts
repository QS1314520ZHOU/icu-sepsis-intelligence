/** ICU-05 septic-shock bundle timeline (department director rule set). Tri-state: true / false / null. */
export interface Icu05Item {
  code: string; label: string; value: boolean | null; time: string | null; detail: string; reason: string | null;
  /** Optional display threshold (not shown in body text; used in tooltip only). */
  threshold?: string;
}
export interface Icu05Window {
  key: string; hours: number; deadline: string; closed: boolean; finish: boolean | null;
  state: 'met' | 'not_met' | 'unknown' | 'in_progress'; finishPath: string; reasons: string[]; items: Icu05Item[];
}
export interface Icu05Gate {
  organ: Icu05Item[]; infection: Icu05Item[]; shock: Icu05Item[]; hasOrganDysfunction: boolean | null; hasInfection: boolean | null;
  isSepticShock: boolean | null; mapMin: number | null; status: string; statusLabel: string; reason: string | null; rule: string;
}
export interface Icu05CultureOrder {
  name: string | null;
  orderTime: string | null;
  reviewTime: string | null;
  sourceId?: string;
}
export interface Icu05Result {
  schemaVersion: number; patientId: string; encounterId: string; available: boolean; message: string; asOf: string;
  ruleVersion: string; ruleSource: string; status: string; statusLabel: string; applicable?: boolean;
  t0: string | null; t0OrderName: string | null; t0Source: string | null;
  cultureOrders?: Icu05CultureOrder[];
  pool?: { eligible: boolean | null; icuWithin24h: boolean | null; admissionType: string | null; rule: string };
  gate: Icu05Gate | null; windows: Icu05Window[]; limitations?: string[];
  lactateSeries?: { time: string; value: number; minutesFromT0: number; period?: string }[];
}

export const icu05Reason: Record<string, string> = {
  DATA_MISSING_LAC: '未取到乳酸', DATA_MISSING: '未取到记录', AB_MISSING: '未见抗菌药执行', BC_MISSING: '未见血培养', BC_AFTER_AB: '抗菌药早于血培养',
  FLUID_MISSING: '未见补液执行', FLUID_INSUFFICIENT: '补液量不足', FLUID_AMOUNT_INCOMPLETE: '部分补液记录无量', LACTATE_RECHECK_MISSING: '未复测乳酸',
  SOURCE_ERROR: '来源读取失败', MAP_MISSING: '未取到 MAP', WINDOW_OPEN: '时窗未结束', VASO_STATUS_UNKNOWN: 'T0 前用药是否持续未知',
  MAP_NOT_MET: 'MAP<65', A1_NOT_MET: '乳酸未测',
  NO_ORGAN_DYSFUNCTION: '未见器官功能障碍证据', NOT_SEPTIC_SHOCK: '未达休克判定', NO_INFECTION_EVIDENCE: '未取得感染证据', INSUFFICIENT_EVIDENCE: '证据不全',
};

/** Bedside (doctor) projection of the ICU-05 result: what is done, what is still due, and when. Pure; unit-tested. */
export type ActionState = 'done' | 'fail' | 'todo' | 'unknown';
export interface BedsideAction {
  code: string; name: string; state: ActionState; time: string | null; rel: string; detail: string; warn: string; deadline: string | null; window: string;
}
const ACTIONS: { code: string; name: string }[] = [
  { code: 'A1', name: '乳酸测定' }, { code: 'B2', name: '血培养' }, { code: 'B1', name: '抗菌药物' }, { code: 'C3', name: '补液' }, { code: 'A2', name: '复测乳酸' },
];
/** "+0:40" relative to T0; "T0 前 0:15" when earlier. */
export function relT0(time: string | null, t0: string | null): string {
  if (!time || !t0) return '';
  const m = Math.round((Date.parse(time) - Date.parse(t0)) / 60000);
  if (!Number.isFinite(m)) return '';
  const a = Math.abs(m), s = Math.floor(a / 60) + ':' + String(a % 60).padStart(2, '0');
  return m < 0 ? 'T0 前 ' + s : '+' + s;
}
/** "3h40m" / "25m"; negative values clamp to 0. */
export function fmtLeft(minutes: number): string {
  const m = Math.max(0, Math.round(minutes));
  return m >= 60 ? Math.floor(m / 60) + 'h' + (m % 60 ? String(m % 60).padStart(2, '0') + 'm' : '') : m + 'm';
}
export function elapsedSinceT0(d: Icu05Result): string {
  if (!d.t0) return '';
  const m = (Date.parse(d.asOf) - Date.parse(d.t0)) / 60000;
  return Number.isFinite(m) && m >= 0 ? fmtLeft(m) : '';
}
function find(w: Icu05Window, code: string) { return w.items.find(i => i.code === code); }

export function bedsideActions(d: Icu05Result): BedsideAction[] {
  if (!d.t0 || !d.windows?.length) return [];
  const out: BedsideAction[] = [];
  for (const a of ACTIONS) {
    const hits = d.windows.map(w => ({ w, i: find(w, a.code) })).filter(x => x.i) as { w: Icu05Window; i: Icu05Item }[];
    if (!hits.length) continue;
    // Fluids accumulate: the widest window speaks. Others: the first window where it is done.
    const done = a.code === 'C3' ? [...hits].reverse().find(x => x.i.value === true) : hits.find(x => x.i.value === true);
    const open = hits.find(x => x.i.value !== true && !x.w.closed);
    const failed = [...hits].reverse().find(x => x.i.value === false && x.w.closed);
    const last = hits[hits.length - 1];
    let state: ActionState, pick: { w: Icu05Window; i: Icu05Item };
    if (a.code === 'C3' && open && open.i.value !== true && !done) { state = 'todo'; pick = open; }
    else if (done) { state = 'done'; pick = done; }
    else if (open) { state = 'todo'; pick = open; }
    else if (failed) { state = 'fail'; pick = failed; }
    else { state = 'unknown'; pick = last; }
    const reason = pick.i.reason ? icu05Reason[pick.i.reason] || '' : '';
    let warn = '';
    if (a.code === 'B1') {
      const b3 = hits.map(x => find(x.w, 'B3')).find(Boolean) || d.windows.map(w => find(w, 'B3')).find(Boolean);
      if (b3?.value === false) warn = '早于血培养';
    }
    out.push({ code: a.code, name: a.name, state, time: state === 'done' ? pick.i.time : null, rel: state === 'done' ? relT0(pick.i.time, d.t0) : '',
      detail: (pick.i.detail || '').replace(/（T0 前开始.*$/, '').trim() || (state === 'fail' || state === 'unknown' ? reason : ''),
      warn, deadline: state === 'todo' ? pick.w.deadline : null, window: pick.w.key });
  }
  return out;
}
/** Earliest still-open item and the minutes left until its window closes. */
export function nextDue(d: Icu05Result): { name: string; window: string; deadline: string; minutesLeft: number } | null {
  const asOf = Date.parse(d.asOf);
  const todo = bedsideActions(d).filter(a => a.state === 'todo' && a.deadline).sort((a, b) => Date.parse(a.deadline!) - Date.parse(b.deadline!))[0];
  if (!todo) return null;
  return { name: todo.name, window: todo.window, deadline: todo.deadline!, minutesLeft: Math.round((Date.parse(todo.deadline!) - asOf) / 60000) };
}
export function windowChips(d: Icu05Result): { key: string; state: string; label: string }[] {
  const asOf = Date.parse(d.asOf);
  return (d.windows || []).map(w => ({ key: w.key, state: w.state,
    label: w.state === 'met' ? '达标' : w.state === 'not_met' ? '未达标' : w.state === 'in_progress' ? '剩 ' + fmtLeft((Date.parse(w.deadline) - asOf) / 60000) : '无法判定' }));
}
/** Lactate clearance (%) from the first value to a later one: (first - later) / first. */
export function lactateClearance(first: number | null, later: number | null): number | null {
  if (first == null || later == null || !(first > 0)) return null;
  return Math.round(((first - later) / first) * 100);
}
/** One-line diagnostic basis from the ICU-05 gate: only satisfied items, with measured values. */
export function verdictBasis(d: Icu05Result): string[] {
  const g = d.gate; if (!g) return [];
  const out: string[] = [];
  const k1 = g.shock.find(i => i.code === 'K1'), s3 = g.organ.find(i => i.code === 'S3'), s4 = g.organ.find(i => i.code === 'S4') || g.shock.find(i => i.code === 'K2');
  if (k1?.value && k1.detail) out.push('首次乳酸 ' + k1.detail.replace(/ ?mmol\/L/, ''));
  if (s3?.value && s3.detail) out.push('MAP ' + s3.detail.replace(/^最低 /, '').replace(/ ?mmHg/, ''));
  if (s4?.value) out.push((s4.detail || '升压药').replace(/（T0 前开始.*$/, '').replace(/注射液$/, '').trim());
  const inf = g.infection.filter(i => i.value).map(i => i.detail).filter(Boolean);
  if (inf.length) out.push('感染：' + inf.join(' / '));
  return out;
}
