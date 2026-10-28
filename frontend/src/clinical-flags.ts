/**
 * Display-only emphasis thresholds for the bedside overview. They colour values so that the most
 * urgent reading is seen first; they do not diagnose, score or change any backend judgement.
 */
export type Level = 'critical' | 'abnormal' | 'normal' | 'none';
interface Rule { critLow?: number; critHigh?: number; low?: number; high?: number; lowWord: string; highWord: string; }
const rules: Record<string, Rule> = {
  MAP: { critLow: 65, low: 70, lowWord: '偏低', highWord: '偏高' },
  SBP: { critLow: 90, low: 100.0001, lowWord: '偏低', highWord: '偏高' },
  LACTATE: { critHigh: 4, high: 2, lowWord: '偏低', highWord: '偏高' },
  RR: { critLow: 8, critHigh: 30, high: 22, lowWord: '偏慢', highWord: '偏快' },
  GCS: { critLow: 8.0001, low: 15, lowWord: '下降', highWord: '' },
  HR: { critLow: 40, critHigh: 130, low: 50, high: 100.0001, lowWord: '偏慢', highWord: '偏快' },
  TEMP: { critLow: 35, critHigh: 39.5, low: 36, high: 38.0001, lowWord: '偏低', highWord: '偏高' },
  SPO2: { critLow: 88, low: 92, lowWord: '偏低', highWord: '' },
};
/** MAP_IBP / SBP_NIBP etc. share the base rule of their measurement. */
export function baseCode(code: string): string { return code.replace(/_(IBP|NIBP)$/, ''); }
export function level(code: string, value: number | null | undefined): Level {
  const r = rules[baseCode(code)];
  if (value == null || !Number.isFinite(value)) return 'none';
  if (!r) return 'normal';
  if ((r.critLow !== undefined && value < r.critLow) || (r.critHigh !== undefined && value >= r.critHigh)) return 'critical';
  if ((r.low !== undefined && value < r.low) || (r.high !== undefined && value >= r.high)) return 'abnormal';
  return 'normal';
}
export function direction(code: string, value: number): string {
  const r = rules[baseCode(code)];
  if (!r) return '';
  const l = level(code, value);
  if (l !== 'critical' && l !== 'abnormal') return '';
  const lowSide = (r.critLow !== undefined && value < r.critLow) || (r.low !== undefined && value < r.low);
  return lowSide ? r.lowWord : r.highWord;
}
export const levelRank: Record<Level, number> = { critical: 0, abnormal: 1, normal: 2, none: 3 };
/** Short human readable elapsed time, e.g. 8 分钟前 / 2.5 小时前 / 3 天前. */
export function ago(time: string | null | undefined, asOf: number): string {
  const t = time ? Date.parse(time) : NaN;
  if (!Number.isFinite(t) || !Number.isFinite(asOf)) return '';
  const min = Math.max(0, Math.round((asOf - t) / 60000));
  if (min < 1) return '刚刚';
  if (min < 60) return min + ' 分钟前';
  const h = min / 60;
  if (h < 48) return (h < 10 ? Math.round(h * 10) / 10 : Math.round(h)) + ' 小时前';
  return Math.round(h / 24) + ' 天前';
}
export function hoursSince(time: string | null | undefined, asOf: number): number | null {
  const t = time ? Date.parse(time) : NaN;
  return Number.isFinite(t) && Number.isFinite(asOf) ? (asOf - t) / 3600000 : null;
}
