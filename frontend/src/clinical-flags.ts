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
  // Display-only SOFA-style emphasis; not a diagnostic score.
  PF_RATIO: { critLow: 100, low: 300, lowWord: '偏低', highWord: '' },
  PLT: { critLow: 20, low: 100, lowWord: '偏低', highWord: '' },
  TBIL: { critHigh: 120, high: 34, lowWord: '', highWord: '偏高' },
  CREA: { critHigh: 353, high: 177, lowWord: '', highWord: '偏高' },
};
/** Plain-language reference for the evidence drawer (display thresholds + the rule thresholds that drive alerts). */
const refs: Record<string, string> = {
  MAP: '<65 mmHg 为休克判定阈值（需要升压药，或 乳酸≥2 且 MAP<65）；65–70 提示偏低',
  SBP: '≤100 mmHg 为 qSOFA 阳性项；<90 显著偏低',
  LACTATE: '≥2 mmol/L 升高（休克判定），≥4 mmol/L 显著升高（需补液评估）',
  RR: '≥22 次/分 为 qSOFA 阳性项；≥30 或 <8 显著异常',
  GCS: '<15 为 qSOFA 阳性项（需排除镇静影响）；≤8 显著下降',
  HR: '>100 次/分 偏快，≥130 显著偏快；<50 偏慢',
  TEMP: '>38 ℃ 发热，≥39.5 ℃ 高热；<36 ℃ 偏低',
  SPO2: '<92% 偏低，<88% 显著偏低',
  PF_RATIO: '<300 提示氧合受损，<100 严重',
  PLT: '<100 ×10⁹/L 偏低，<20 严重',
  TBIL: '≥34 μmol/L 偏高，≥120 显著',
  CREA: '≥177 μmol/L 偏高，≥353 显著',
};
export function reference(code: string): string { return refs[baseCode(code)] || ''; }
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
