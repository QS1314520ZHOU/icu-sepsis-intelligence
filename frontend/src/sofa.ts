/**
 * Classic SOFA (Vincent 1996) and SOFA-2 (JAMA 2025) from bedside/lab observations.
 * Thresholds aligned with icu-quality-dashboard scoring/sofa_rules.py + research toolkit Sofa.java.
 *
 * Rules:
 * - Missing component → score null (never 0).
 * - Worst value in lookback window; unit must match expected.
 * - Vasopressor dose unknown → minimal pressor band + flag (not a fabricated dose).
 * - Manual overrides are display-only trial calculations; not persisted, not a diagnosis.
 */
import type { Observation } from './api';

export type SofaKind = 'classic' | 'sofa2';
export type CompKey = 'resp' | 'coag' | 'liver' | 'cardio' | 'cns' | 'renal';

export interface SofaComponent {
  key: CompKey;
  label: string;
  /** null = missing / not determinable */
  score: number | null;
  /** Worst input value used (or manual override) */
  value: number | null;
  unit: string;
  inputLabel: string;
  source: 'auto' | 'manual' | 'none';
  sourceNote: string;
  eventTime: string | null;
  flags: string[];
  /** Editable when missing or user wants to trial a value */
  editable: boolean;
}

export interface SofaResult {
  kind: SofaKind;
  total: number | null;
  /** number of components with a score */
  scored: number;
  components: SofaComponent[];
  flags: string[];
  note: string;
}

export interface SofaManualInput {
  resp?: number | null;
  coag?: number | null;
  liver?: number | null;
  cardio?: number | null;
  cns?: number | null;
  renal?: number | null;
}

const COMP_LABEL: Record<CompKey, string> = {
  resp: '呼吸',
  coag: '凝血',
  liver: '肝脏',
  cardio: '心血管',
  cns: '中枢神经',
  renal: '肾脏',
};

function worst(
  items: Observation[],
  codes: string[],
  asOf: number,
  lookbackH: number,
  pick: 'min' | 'max',
): Observation | null {
  const from = asOf - lookbackH * 3600000;
  let best: Observation | null = null;
  for (const o of items) {
    if (!codes.includes(o.parameterCode)) continue;
    if (o.value == null) continue;
    if (o.clinicalUse === 'context_only' || o.encounterLinkStatus === 'unconfirmed') continue;
    if (o.qualityStatus && o.qualityStatus !== 'valid') continue;
    const t = Date.parse(o.eventTime);
    if (!Number.isFinite(t) || t < from || t > asOf) continue;
    if (!best) { best = o; continue; }
    const bv = best.value!;
    if (pick === 'min' ? o.value! < bv : o.value! > bv) best = o;
  }
  return best;
}

function bandClassicResp(pf: number): number {
  // Vent support unknown → cap at 2 (same as research Sofa.classic)
  if (pf < 100) return 2;
  if (pf < 200) return 3 > 2 ? 2 : 3; // cap
  if (pf < 300) return 2;
  if (pf < 400) return 1;
  return 0;
}

function bandClassicRespFull(pf: number): { score: number; capped: boolean } {
  if (pf < 100) return { score: 4, capped: true };
  if (pf < 200) return { score: 3, capped: true };
  if (pf < 300) return { score: 2, capped: false };
  if (pf < 400) return { score: 1, capped: false };
  return { score: 0, capped: false };
}

function bandClassicCoag(plt: number): number {
  if (plt < 20) return 4;
  if (plt < 50) return 3;
  if (plt < 100) return 2;
  if (plt < 150) return 1;
  return 0;
}

function bandClassicLiverTbilUmol(tb: number): number {
  if (tb >= 204) return 4;
  if (tb >= 102) return 3;
  if (tb >= 33) return 2;
  if (tb >= 20) return 1;
  return 0;
}

function bandClassicCardio(map: number | null, pressor: boolean): { score: number | null; flags: string[] } {
  const flags: string[] = [];
  if (pressor) {
    flags.push('vaso_dose_unknown');
    // dose unknown → at least 2 (quality-dashboard rule 9 / research toolkit)
    return { score: 2, flags };
  }
  if (map == null) return { score: null, flags };
  return { score: map < 70 ? 1 : 0, flags };
}

function bandClassicCns(gcs: number): number {
  if (gcs >= 15) return 0;
  if (gcs >= 13) return 1;
  if (gcs >= 10) return 2;
  if (gcs >= 6) return 3;
  return 4;
}

function bandClassicRenal(creaUmol: number): number {
  if (creaUmol >= 440) return 4;
  if (creaUmol >= 300) return 3;
  if (creaUmol >= 170) return 2;
  if (creaUmol >= 110) return 1;
  return 0;
}

function bandSofa2Resp(pf: number): { score: number; capped: boolean } {
  if (pf < 75) return { score: 4, capped: true };
  if (pf < 150) return { score: 3, capped: true };
  if (pf < 225) return { score: 2, capped: false };
  if (pf < 300) return { score: 1, capped: false };
  return { score: 0, capped: false };
}

function bandSofa2Coag(plt: number): number {
  if (plt < 50) return 4;
  if (plt < 80) return 3;
  if (plt < 100) return 2;
  if (plt < 150) return 1;
  return 0;
}

function bandSofa2LiverTbilUmol(tb: number): number {
  const mg = tb / 17.104;
  if (mg >= 12) return 4;
  if (mg >= 6) return 3;
  if (mg >= 3) return 2;
  if (mg >= 1.2) return 1;
  return 0;
}

function bandSofa2Cns(gcs: number): number {
  if (gcs >= 15) return 0;
  if (gcs >= 13) return 1;
  if (gcs >= 9) return 2;
  if (gcs >= 6) return 3;
  return 4;
}

function bandSofa2Renal(creaUmol: number): number {
  const mg = creaUmol / 88.4;
  if (mg >= 3.5) return 3;
  if (mg >= 2.0) return 2;
  if (mg >= 1.2) return 1;
  return 0;
}

function unitOk(o: Observation | null, allowed: string[]): boolean {
  if (!o) return false;
  const u = (o.unit || '').trim();
  return allowed.some((a) => u === a);
}

function autoComponent(
  key: CompKey,
  score: number | null,
  o: Observation | null,
  inputLabel: string,
  unit: string,
  value: number | null,
  flags: string[],
  note: string,
): SofaComponent {
  return {
    key,
    label: COMP_LABEL[key],
    score,
    value,
    unit: value != null ? unit : '',
    inputLabel,
    source: score != null ? 'auto' : 'none',
    sourceNote: note,
    eventTime: o?.eventTime || null,
    flags,
    editable: score == null,
  };
}

function applyManual(c: SofaComponent, manual: SofaManualInput, kind: SofaKind): SofaComponent {
  const m = manual[c.key];
  if (m == null || !Number.isFinite(m)) return c;
  const v = m;
  let score: number | null = null;
  const flags = [...c.flags];
  if (c.key === 'resp') {
    const r = kind === 'classic' ? bandClassicRespFull(v) : bandSofa2Resp(v);
    score = r.score;
    if (r.capped) flags.push('resp_support_unknown');
  } else if (c.key === 'coag') {
    score = kind === 'classic' ? bandClassicCoag(v) : bandSofa2Coag(v);
  } else if (c.key === 'liver') {
    score = kind === 'classic' ? bandClassicLiverTbilUmol(v) : bandSofa2LiverTbilUmol(v);
  } else if (c.key === 'cns') {
    score = kind === 'classic' ? bandClassicCns(v) : bandSofa2Cns(v);
  } else if (c.key === 'renal') {
    score = kind === 'classic' ? bandClassicRenal(v) : bandSofa2Renal(v);
  } else if (c.key === 'cardio') {
    // Manual cardio: user enters MAP if no pressor; pressor band stays auto
    if (c.sourceNote.includes('升压药') || c.flags.includes('vaso_dose_unknown')) {
      score = c.score;
    } else {
      score = v < 70 ? 1 : 0;
    }
  }
  return {
    ...c,
    score,
    value: v,
    source: 'manual',
    sourceNote: '手动试算（未写入源库）',
    eventTime: null,
    flags,
    editable: true,
  };
}

export function computeSofa(
  items: Observation[],
  asOf: number,
  kind: SofaKind,
  manual: SofaManualInput = {},
): SofaResult {
  const flags = new Set<string>();
  const comps: SofaComponent[] = [];

  // Respiratory — P/F
  const pf = worst(items, ['PF_RATIO'], asOf, 24, 'min');
  if (pf && unitOk(pf, ['mmHg', ''])) {
    const r = kind === 'classic' ? bandClassicRespFull(pf.value!) : bandSofa2Resp(pf.value!);
    if (r.capped) flags.add('resp_support_unknown');
    comps.push(
      autoComponent(
        'resp',
        r.score,
        pf,
        'P/F',
        'mmHg',
        pf.value!,
        r.capped ? ['resp_support_unknown'] : [],
        r.capped ? '缺机械通气信息，3–4 分封顶为 2' : '床旁血气 P/F 最差值',
      ),
    );
  } else {
    comps.push(autoComponent('resp', null, null, 'P/F', 'mmHg', null, [], '近 24h 无有效 P/F'));
  }

  // Coagulation — PLT (10^9/L)
  const plt = worst(items, ['PLT'], asOf, 24, 'min');
  if (plt && unitOk(plt, ['10^9/L', '10^9/L ', '×10^9/L', ''])) {
    const s = kind === 'classic' ? bandClassicCoag(plt.value!) : bandSofa2Coag(plt.value!);
    comps.push(autoComponent('coag', s, plt, '血小板', plt.unit || '10^9/L', plt.value!, [], '近 24h 最低'));
  } else {
    comps.push(autoComponent('coag', null, null, '血小板', '10^9/L', null, [], '近 24h 无有效血小板'));
  }

  // Liver — TBIL μmol/L (classic) / converted for sofa2
  const tbil = worst(items, ['TBIL'], asOf, 24, 'max');
  if (tbil && unitOk(tbil, ['μmol/L', 'umol/L', 'µmol/L', ''])) {
    const s = kind === 'classic' ? bandClassicLiverTbilUmol(tbil.value!) : bandSofa2LiverTbilUmol(tbil.value!);
    comps.push(
      autoComponent('liver', s, tbil, '总胆红素', tbil.unit || 'μmol/L', tbil.value!, [], '近 24h 最高'),
    );
  } else {
    comps.push(autoComponent('liver', null, null, '总胆红素', 'μmol/L', null, [], '近 24h 无有效胆红素'));
  }

  // Cardio — MAP channels + vasopressor flag
  const maps = (['MAP_IBP', 'MAP_NIBP', 'MAP'] as string[])
    .map((c) => worst(items, [c], asOf, 24, 'min'))
    .filter((x): x is Observation => !!x);
  const mapMin = maps.length
    ? maps.reduce((a, b) => (a.value! <= b.value! ? a : b))
    : null;
  const vaso = worst(items, ['VASOPRESSOR_ACTIVE'], asOf, 24, 'max');
  const pressor = !!vaso && vaso.value === 1;
  const cardioBand = bandClassicCardio(mapMin?.value ?? null, pressor);
  if (pressor) flags.add('vaso_dose_unknown');
  const cardioScore =
    mapMin == null && !pressor
      ? null
      : cardioBand.score;
  comps.push(
    autoComponent(
      'cardio',
      cardioScore,
      mapMin,
      pressor ? '升压药 + MAP' : 'MAP',
      mapMin?.unit || 'mmHg',
      mapMin?.value ?? null,
      cardioBand.flags,
      pressor
        ? '升压药在用；剂量未接入，按最低档 ≥2'
        : mapMin
          ? '24h 最低 MAP' + (mapMin.measurementChannel ? '（' + mapMin.measurementChannel + '）' : '')
          : '无有效 MAP',
    ),
  );

  // CNS — GCS
  const gcs = worst(items, ['GCS'], asOf, 24, 'min');
  if (gcs && unitOk(gcs, ['score', ''])) {
    const s = kind === 'classic' ? bandClassicCns(gcs.value!) : bandSofa2Cns(gcs.value!);
    comps.push(autoComponent('cns', s, gcs, 'GCS', '分', gcs.value!, [], '近 24h 最低'));
  } else {
    comps.push(autoComponent('cns', null, null, 'GCS', '分', null, [], '近 24h 无有效 GCS'));
  }

  // Renal — creatinine μmol/L (urine rate not used: weight/interval incomplete)
  const crea = worst(items, ['CREA'], asOf, 24, 'max');
  if (crea && unitOk(crea, ['μmol/L', 'umol/L', 'µmol/L', ''])) {
    const s = kind === 'classic' ? bandClassicRenal(crea.value!) : bandSofa2Renal(crea.value!);
    comps.push(
      autoComponent(
        'renal',
        s,
        crea,
        '肌酐',
        crea.unit || 'μmol/L',
        crea.value!,
        ['urine_not_used'],
        '近 24h 最高肌酐；尿量 mL/kg/h 未纳入（体重/间隔不足）',
      ),
    );
  } else {
    comps.push(autoComponent('renal', null, null, '肌酐', 'μmol/L', null, [], '近 24h 无有效肌酐'));
  }

  const withManual = comps.map((c) => applyManual(c, manual, kind));
  let sum = 0;
  let count = 0;
  for (const c of withManual) {
    if (c.score != null) {
      sum += c.score;
      count++;
    }
    for (const f of c.flags) flags.add(f);
  }

  const note =
    kind === 'classic'
      ? '经典 SOFA（Vincent 1996）自动试算；缺项不计 0；非诊断、非宿主正式评分。'
      : 'SOFA-2（JAMA 2025）自动试算；缺项不计 0；非诊断、非宿主正式评分。';

  return {
    kind,
    total: count >= 4 ? sum : null, // require ≥4 components like research partial rule
    scored: count,
    components: withManual,
    flags: [...flags],
    note,
  };
}

export function sofaComponentLabel(key: CompKey): string {
  return COMP_LABEL[key];
}
