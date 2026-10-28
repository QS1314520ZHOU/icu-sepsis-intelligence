export const SMARTCARE_MESSAGE = {
  ready: 'SmartCareReady',
  context: 'SmartCare',
  selectPatient: 'select-patient',
  navigate: 'SmartCareNavigate',
} as const;

export const DEFAULT_MODULE_ID = 'doctor-diagnosis-patient-review';

export interface SmartCarePatient {
  id?: string;
  name?: string;
  gender?: string;
  genderStr?: string;
  dept?: string;
  deptCode?: string;
  deptCode2?: string;
  showBed?: string;
  hisBed?: string;
  mrn?: string;
  hisPid?: string;
  hospitalTime?: string;
}

export interface SmartCareContextMessage {
  type: 'SmartCare';
  account?: unknown;
  patient?: SmartCarePatient | null;
  token?: unknown;
}

export function isIframe(): boolean {
  try {
    return window.parent !== window;
  } catch {
    return false;
  }
}

/** Resolve targetOrigin for parent.postMessage. Prefer explicit parentOrigin query, then referrer, else '*'. */
export function resolveParentOrigin(): string {
  try {
    const hint = new URLSearchParams(location.search).get('parentOrigin');
    if (hint) return hint;
  } catch {
    /* ignore */
  }
  try {
    const ref = document.referrer;
    if (ref) return new URL(ref).origin;
  } catch {
    /* ignore */
  }
  return '*';
}

export function sendSmartCareReady(targetOrigin = resolveParentOrigin()): boolean {
  if (!isIframe()) return false;
  window.parent.postMessage({ type: SMARTCARE_MESSAGE.ready }, targetOrigin);
  return true;
}

export function sendSelectPatient(
  patientId: string,
  moduleId?: string,
  targetOrigin = resolveParentOrigin(),
): boolean {
  if (!isIframe() || !patientId) return false;
  const payload: Record<string, unknown> = {
    type: SMARTCARE_MESSAGE.selectPatient,
    patientId,
  };
  if (moduleId) payload.moduleId = moduleId;
  window.parent.postMessage(payload, targetOrigin);
  return true;
}

export function sendNavigate(
  moduleId: string = DEFAULT_MODULE_ID,
  targetOrigin = resolveParentOrigin(),
): boolean {
  if (!isIframe() || !moduleId) return false;
  window.parent.postMessage({ type: SMARTCARE_MESSAGE.navigate, moduleId }, targetOrigin);
  return true;
}

/** Recommended click flow: select patient first, then open 病情概览. */
export function openPatientReview(patientId: string, targetOrigin = resolveParentOrigin()): void {
  sendSelectPatient(patientId, undefined, targetOrigin);
  sendNavigate(DEFAULT_MODULE_ID, targetOrigin);
}

export function extractSmartCareContext(
  event: MessageEvent,
): SmartCareContextMessage | null {
  const data = event?.data;
  if (!data || typeof data !== 'object') return null;
  const msg = data as SmartCareContextMessage;
  if (msg.type !== SMARTCARE_MESSAGE.context) return null;
  return msg;
}

export function patientIdOf(patient?: SmartCarePatient | null): string {
  return String(patient?.id ?? '').trim();
}

export function deptCodeOf(patient?: SmartCarePatient | null): string {
  return String(patient?.deptCode || patient?.deptCode2 || '').trim();
}

export function bedOf(patient?: SmartCarePatient | null): string {
  return String(patient?.showBed || patient?.hisBed || '').trim();
}

export function genderLabelOf(patient?: SmartCarePatient | null): string {
  const raw = String(patient?.genderStr || patient?.gender || '').trim();
  if (!raw) return '';
  if (raw === 'Male' || raw === 'M' || raw === '1' || raw === '男') return '男';
  if (raw === 'Female' || raw === 'F' || raw === '2' || raw === '女') return '女';
  return raw;
}
