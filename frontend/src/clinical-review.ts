/** Clinical review schema v2. Unknown is never translated to normal/low risk. */
export interface Evidence {
  code: string; label?: string; value: number; unit: string; eventTime: string;
  sourceSystem: string; sourceRecordId: string; reason?: string;
  sourceParameterCode?: string; measurementChannel?: string; timeBasis?: string;
  encounterLinkStatus?: string; clinicalUse?: string; qualityNote?: string;
  reportedAt?: string | null; sampledAt?: string | null;
}
export interface ReviewItem {
  code: string; name: string; status: 'recorded' | 'unknown'; note: string; eventTime: string | null;
}
export interface ClinicalReview {
  schemaVersion: 2; patientId: string; encounterId: string; asOf: string;
  available: boolean; message: string; simulated: boolean;
  screeningStatus: string; screeningLabel: string; missingFeatures: string[];
  findings: Evidence[]; latestEvidence: Evidence[]; qsofa: number | null; qsofaNote: string;
  vasopressorActive: boolean | null; infectionClue: string;
  sourceWarnings?: string[]; pressureSelectionRequired?: boolean;
  pressureEvidence?: Evidence[]; contextEvidence?: Evidence[];
  checklist: ReviewItem[]; taskNote: string;
  model: { configured: boolean; label: string; message: string };
  ai: { configured: boolean; label: string };
}
const names: Record<string,string> = {MAP:'平均动脉压',SBP:'收缩压',RR:'呼吸频率',GCS:'意识评分',LACTATE:'乳酸',
  MAP_IBP:'平均动脉压（有创）',MAP_NIBP:'平均动脉压（无创）',SBP_IBP:'收缩压（有创）',SBP_NIBP:'收缩压（无创）',DBP_IBP:'舒张压（有创）',DBP_NIBP:'舒张压（无创）',
  PF_RATIO:'氧合指数 P/F',PLT:'血小板',TBIL:'总胆红素',CREA:'肌酐',URINE_OUTPUT:'尿量'};
export function featureLabel(code: string): string { return names[code] || code; }
export function yesNoUnknown(value: boolean | null | undefined): string {
  return value === true ? '有记录' : value === false ? '记录为无' : '未取得有效记录';
}
export function reviewState(data: ClinicalReview | null): string {
  return !data?.available ? 'unavailable' : data.screeningStatus;
}
export function reviewTitle(data: ClinicalReview | null): string {
  return !data?.available ? '当前无法评估' : data.screeningLabel;
}
export function matchesPatient(data: Pick<ClinicalReview,'patientId'|'encounterId'>, id: string, encounter: string): boolean {
  return data.patientId === id && (!encounter || data.encounterId === encounter);
}
