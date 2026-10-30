import { HttpClient, HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { uuid4 } from './uuid';
export interface Envelope<T> {
  code: string;
  message: string;
  data: T;
  traceId: string;
  timestamp: string;
}
export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}
export interface Patient {
  vitals?: Record<string, Observation>;
  latestAssessment?: Assessment | null;
  summaryLimitReached?: boolean;
  id: string;
  label: string;
  bed: string;
  encounterId: string;
  simulated: boolean;
  department: string;
  admittedAt: string;
}
export interface Base {
  id: string;
  version: number;
  patientId: string;
  encounterId: string;
  simulated: boolean;
  createdAt: string;
}
export interface Prediction {
  horizonHours: number;
  predictionSource: string;
  riskValueType: string | null;
  riskValue: number | null;
  modelStatus: string;
}
export interface Observation {
  id: string;
  parameterCode: string;
  value: number | null;
  unit: string | null;
  text: string | null;
  eventTime: string;
  ingestTime: string | null;
  sourceParameterCode?: string; measurementChannel?: string;
  timeBasis?: string; ingestTimeBasis?: string; encounterLinkStatus?: string; clinicalUse?: string;
  reportedAt?: string | null; sampledAt?: string | null; sourceResultStatus?: string | null; qualityNote?: string;
  sourceSystem: string;
  sourceRecordId: string;
  qualityStatus: string;
}
export interface Assessment extends Base {
  assessmentTime: string;
  infectionVerdict: string;
  screeningStatus: string;
  shockScreeningStatus: string;
  dataQualityStatus: string;
  missingFeatures: string[];
  ruleScore: number | null;
  sofaStatus: string;
  evidence: Observation[];
  predictions: Prediction[];
  ruleVersion: string;
}
export interface Alert extends Base {
  status: string;
  severity: string;
  assessmentId: string;
  reason: string;
  acknowledgedAt: string | null;
  ruleVersion: string;
  /** Screening alerts: suspected_shock | infection_abnormal; source bedside | icu05. */
  kind?: string | null;
  source?: string | null;
  trigger?: string | null;
  patientLabel?: string | null;
  bed?: string | null;
  lastSeenAt?: string | null;
}
export interface Task extends Base {
  itemName: string;
  applicability: string;
  executionStatus: string;
  targetStatus: string;
  clinicalReviewStatus: string;
  dueAt: string;
  completedAt: string | null;
  evidenceId: string | null;
  approvalStatus: string;
}
export interface History {
  action: string;
  actor: string;
  reason: string;
  createdAt: string;
  before: Record<string, unknown>;
  after: Record<string, unknown>;
}
export interface Quality {
  totalTasks: number;
  denominator: number;
  onTime: number;
  late: number;
  excluded: number;
  pendingReview: number;
  onTimeRate: number | null;
  definition: string;
  notCompleted: number;
  dataMissing: number;
  completedBefore: number;
}
export interface Model {
  modelStatus: string;
  predictionSource: string;
  localValidationStatus: string;
  realModelRunning: boolean;
  clinicalUseAllowed: boolean;
  reason: string;
}
@Injectable({ providedIn: 'root' })
export class Api {
  private http = inject(HttpClient);
  scheme: 'Basic' | 'Bearer' = 'Basic';
  token = '';
  user = '';
  roles: string[] = [];
  async login(user: string, password: string) {
    this.scheme = 'Basic';
    this.token = btoa(user + ':' + password);
    try {
      const m = await this.get<{ username: string; roles: string[] }>('/me');
      this.user = m.username;
      this.roles = m.roles;
    } catch (e) {
      this.logout();
      throw e;
    }
  }
  logout() {
    this.token = '';
    this.user = '';
    this.roles = [];
  }
  has(role: string) {
    return this.roles.includes('ROLE_' + role);
  }
  async get<T>(path: string): Promise<T> {
    return (await firstValueFrom(this.http.get<Envelope<T>>('/api/v1' + path))).data;
  }
  async post<T>(path: string, body: unknown, key = uuid4()): Promise<T> {
    return (
      await firstValueFrom(
        this.http.post<Envelope<T>>('/api/v1' + path, body, {
          headers: { 'Idempotency-Key': key },
        }),
      )
    ).data;
  }
}
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const api = inject(Api);
  return next(
    req.url.startsWith('/api/v1') && api.token
      ? req.clone({ setHeaders: { Authorization: api.scheme + ' ' + api.token } })
      : req,
  );
};
export function errorMessage(e: unknown): string {
  if (e instanceof HttpErrorResponse) {
    if (e.status === 0) return '无法连接服务，请检查后端是否启动。';
    return `${e.error?.message || '请求失败'} · ${e.error?.code || e.status} · trace ${e.error?.traceId || '—'}`;
  }
  return '操作未完成，请刷新并检查数据版本。';
}
