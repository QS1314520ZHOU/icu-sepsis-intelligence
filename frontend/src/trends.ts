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
  { code: 'HR', label: '心率', unit: 'bpm', color: '#3567d6', min: 10, max: 300, gapMinutes: VITAL_GAP_MINUTES },
  {
    code: 'MAP',
    label: '平均动脉压',
    unit: 'mmHg',
    color: '#157a85',
    min: 10,
    max: 300,
    gapMinutes: VITAL_GAP_MINUTES,
  },
  {
    code: 'RR',
    label: '呼吸频率',
    unit: '/min',
    color: '#8b61bb',
    min: 1,
    max: 100,
    gapMinutes: VITAL_GAP_MINUTES,
  },
  {
    code: 'SPO2',
    label: '血氧饱和度',
    unit: '%',
    color: '#157a85',
    min: 0,
    max: 100,
    gapMinutes: VITAL_GAP_MINUTES,
  },
  {
    code: 'TEMP',
    label: '体温',
    unit: '°C',
    color: '#b56e25',
    min: 25,
    max: 45,
    gapMinutes: VITAL_GAP_MINUTES,
  },
  {
    code: 'LACTATE',
    label: '乳酸',
    unit: 'mmol/L',
    color: '#b64c57',
    min: 0,
    max: 40,
    gapMinutes: 1440,
  },
];
export function validPoint(o: Observation, m: Measure, asOf: number): boolean {
  const t = Date.parse(o.eventTime),
    ingest = Date.parse(o.ingestTime);
  return (
    o.parameterCode === m.code &&
    o.qualityStatus === 'valid' &&
    o.unit === m.unit &&
    o.value !== null &&
    Number.isFinite(o.value) &&
    o.value >= m.min &&
    o.value <= m.max &&
    Number.isFinite(t) &&
    Number.isFinite(ingest) &&
    t <= asOf &&
    ingest <= asOf
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
    .sort((a, b) => Date.parse(a.eventTime) - Date.parse(b.eventTime) || a.id.localeCompare(b.id));
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
export function label(code: string): string {
  return (
    measures.find((m) => m.code === code)?.label ||
    (
      {
        SBP: '收缩压',
        GCS: '意识评分',
        VASOPRESSOR_ACTIVE: '血管活性药使用',
        DIAGNOSIS: '感染相关记录',
      } as Record<string, string>
    )[code] ||
    code
  );
}
