import type { Observation } from './api';
export interface Measure {
  code: string;
  label: string;
  unit: string;
  color: string;
  min: number;
  max: number;
  gapMinutes: number;
}
// ICU 默认约 1h 录一次生命体征；断线阈值按「超过 2 个录入周期」判定，避免正常整点采集被切断。
export const VITAL_GAP_MINUTES = 150;
export const measures: Measure[] = [
  { code: 'HR', label: '心率', unit: 'bpm', color: '#3869e8', min: 10, max: 300, gapMinutes: VITAL_GAP_MINUTES },
  {
    code: 'MAP',
    label: '平均动脉压',
    unit: 'mmHg',
    color: '#d4380d',
    min: 10,
    max: 300,
    gapMinutes: VITAL_GAP_MINUTES,
  },
  {
    code: 'RR',
    label: '呼吸频率',
    unit: '/min',
    color: '#722ed1',
    min: 1,
    max: 100,
    gapMinutes: VITAL_GAP_MINUTES,
  },
  {
    code: 'SPO2',
    label: '血氧饱和度',
    unit: '%',
    color: '#2f54eb',
    min: 0,
    max: 100,
    gapMinutes: VITAL_GAP_MINUTES,
  },
  {
    code: 'TEMP',
    label: '体温',
    unit: '°C',
    color: '#d46b08',
    min: 25,
    max: 45,
    gapMinutes: VITAL_GAP_MINUTES,
  },
  {
    code: 'LACTATE',
    label: '乳酸',
    unit: 'mmol/L',
    color: '#cf1322',
    min: 0,
    max: 40,
    gapMinutes: 1440,
  },
];
export function validPoint(o: Observation, m: Measure, asOf: number): boolean {
  const t = Date.parse(o.eventTime),
    ingest = o.ingestTime ? Date.parse(o.ingestTime) : NaN;
  const sourceIngestUnknown = o.ingestTime === null && o.ingestTimeBasis === 'unknown' && o.clinicalUse === 'review';
  return (
    o.clinicalUse !== 'context_only' && o.encounterLinkStatus !== 'unconfirmed' &&
    o.parameterCode === m.code &&
    o.qualityStatus === 'valid' &&
    o.unit === m.unit &&
    o.value !== null &&
    Number.isFinite(o.value) &&
    o.value >= m.min &&
    o.value <= m.max &&
    Number.isFinite(t) &&
    (Number.isFinite(ingest) || sourceIngestUnknown) &&
    t <= asOf &&
    (sourceIngestUnknown || ingest <= asOf)
  );
}
export function pointsFor(
  items: Observation[],
  m: Measure,
  from: number,
  to: number,
): Observation[] {
  const points = items
    .filter((o) => validPoint(o, m, to) && Date.parse(o.eventTime) >= from)
    .sort((a, b) => Date.parse(a.eventTime) - Date.parse(b.eventTime) || (a.id || a.sourceRecordId || '').localeCompare(b.id || b.sourceRecordId || ''));
  // Conflicting simultaneous values must not create a vertical artefact or be silently averaged.
  return points
    .filter((o) => !points.some((x) => x.eventTime === o.eventTime && x.value !== o.value))
    .filter((o, i, a) => i === 0 || o.eventTime !== a[i - 1].eventTime);
}
export function segments(points: Observation[], gapMinutes: number): Observation[][] {
  const out: Observation[][] = [];
  for (const o of points) {
    const last = out[out.length - 1];
    if (
      !last ||
      Date.parse(o.eventTime) - Date.parse(last[last.length - 1].eventTime) > gapMinutes * 60000
    )
      out.push([o]);
    else last.push(o);
  }
  return out;
}
export function latest(
  items: Observation[],
  m: Measure,
  asOf = Date.now(),
): Observation | undefined {
  return pointsFor(items, m, 0, asOf).at(-1);
}
export function stale(o: Observation | undefined, m: Measure, asOf: number): boolean {
  return !o || asOf - Date.parse(o.eventTime) > (m.code === 'LACTATE' ? 360 : 120) * 60000;
}
/** Separate channels are independent plots; generic legacy MAP is never joined to either. */
export function measuresFor(items: Observation[]): Measure[] {
  const pressure = ['MAP_IBP','MAP_NIBP'].filter(code => items.some(o => o.parameterCode === code));
  if (!pressure.length) return measures;
  const base = measures.find(m => m.code === 'MAP')!;
  return measures.flatMap(m => m.code === 'MAP' ? pressure.map(code => ({...base,code,
    label: code === 'MAP_IBP' ? '平均动脉压（有创）' : '平均动脉压（无创）',
    color: code === 'MAP_IBP' ? '#d4380d' : '#c41d7f'})) : [m]);
}
export function timeLabel(basis?: string): string { return basis === 'reported' ? '报告 / 审核时间' : '观测事件时间'; }
export function evidenceNote(o: Observation): string {
  const notes: string[] = [];
  if (o.encounterLinkStatus === 'unconfirmed') notes.push('本次住院关联未确认');
  if (o.timeBasis === 'reported') notes.push('采样时间未知，仅报告时间');
  if (o.clinicalUse === 'context_only') notes.push('仅列出，不参与提示和判断');
  if (o.ingestTimeBasis === 'unknown') notes.push('源入库时间未知');
  return notes.join('；');
}
export function label(code: string): string {
  const channel = /^(MAP|SBP|DBP)_(IBP|NIBP)$/.exec(code);
  if(channel) return ({MAP:'平均动脉压',SBP:'收缩压',DBP:'舒张压'} as Record<string,string>)[channel[1]] + (channel[2] === 'IBP' ? '（有创）' : '（无创）');
  return (
    measures.find((m) => m.code === code)?.label ||
    (
      {
        SBP: '收缩压',
        GCS: '意识评分',
        VASOPRESSOR_ACTIVE: '血管活性药使用',
        DBP: '舒张压',
        SOFA: 'SOFA 评分',
        APACHEII: 'APACHE II',
        DIAGNOSIS: '感染相关记录',
      } as Record<string, string>
    )[code] ||
    code
  );
}
