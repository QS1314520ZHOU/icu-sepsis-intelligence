import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, inject, OnInit, OnDestroy, ViewChild } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Alert, Api, Envelope, Page, Patient, Task, errorMessage } from './api';
import { UiIcon } from './ui-icon';
import { Detail } from './pages/detail';
import { Alerts } from './pages/alerts';
import { Bundles } from './pages/bundles';
import { SepsisPanel, SepsisStatus } from './sepsis-panel';
import { namespace, trustedMessage } from './embed-protocol';
import { uuid4 } from './uuid';
import {
  extractSmartCareContext,
  isIframe,
  resolveParentOrigin,
  sendSmartCareReady,
  sendSepsisStatus,
  type SmartCarePatient,
} from './smartcare-protocol';

interface Session {
  accessToken: string;
  expiresAt: string;
  username: string;
  roles: string[];
  patientId: string;
  encounterId: string;
  department: string;
}

interface HostPatientSession extends Session {
  hisPid?: string | null;
}

@Component({
  standalone: true,
  imports: [CommonModule, Detail, Alerts, Bundles, SepsisPanel, UiIcon],
  template: ` <main class="icu-embed">
    <!-- 等待宿主 -->
    <section class="icu-embed-wait" *ngIf="!patient && !loading">
      <div class="wait-box">
        <div class="wait-title">等待患者</div>
        <p class="wait-sub">请在宿主系统左侧选择患者</p>
        <p class="wait-err" *ngIf="error">{{ error }}</p>
      </div>
    </section>

    <!-- 切换患者中：半透明遮罩，不清空已有界面结构 -->
    <section class="icu-embed-wait loading" *ngIf="!patient && loading">
      <div class="wait-box">
        <div class="spinner"></div>
        <div class="wait-title">加载患者…</div>
      </div>
    </section>

    <ng-container *ngIf="patient as p">
      <div class="icu-patient-context"><header class="icu-patient-bar">
        <div class="icu-patient-left">
          <span class="bed-badge">{{ p.bed || '--' }}</span>
          <span class="name">{{ p.label }}</span>
          <span class="meta">{{ mrn }}</span>
          <span class="meta" *ngIf="gender">{{ gender }}</span>
          <span class="icu-context-kind">{{ p.simulated ? '模拟患者' : '当前患者' }}</span>
        </div>
      </header>

      <nav class="icu-tabs" aria-label="当前患者工作区">
        <button
          *ngFor="let t of tabs"
          [class.active]="view === t.key"
          [attr.aria-current]="view === t.key ? 'page' : null"
          (click)="recordsPanel='evidence'; trendInitialHours=12; setView(t.key)"
        >
          <ui-icon [name]="tabIcon(t.key)"/>{{ t.label }}<span class="icu-tab-badge" *ngIf="t.key === 'alerts' && pendingCount">{{ pendingCount }}</span>
        </button>
      </nav></div>

      <p class="icu-error" role="alert" *ngIf="error">{{ error }}</p>

      <section class="icu-body">
        <sepsis-panel *ngIf="view === 'risk'" [patientId]="p.id" [encounterId]="p.encounterId" (status)="onStatus($event)"
          (openTrends)="openTrendHistory()" (openEvidence)="openRecords()" (openAlerts)="setView('alerts')" (openTasks)="openTasksView()" />
        <patient-detail
          *ngIf="view === 'records'"
          [patientId]="p.id"
          [embedded]="true" [initialHours]="trendInitialHours"
          [panel]="recordsPanel"
          (openAlerts)="setView('alerts')"
        />
        <ng-container *ngIf="view === 'alerts'">
          <patient-alerts [embedded]="true" [patientId]="p.id" (openEvidence)="openRecords()" (changed)="refreshStatus()" />
          <div id="icu-tasks"><patient-bundles [embedded]="true" (openEvidence)="openRecords()" (changed)="refreshStatus()" /></div>
        </ng-container>
      </section>
    </ng-container>

    <div class="icu-loading-mask" *ngIf="patient && loading" aria-hidden="true">
      <div class="spinner"></div>
    </div>
  </main>`,
})
export class EmbedWorkspace implements OnInit, OnDestroy {
  trendInitialHours=12;
  recordsPanel: 'trends' | 'evidence' = 'evidence';
  pendingCount = 0;
  openTrendHistory(){this.trendInitialHours=168;this.recordsPanel='trends';this.setView('records');}
  openRecords(){this.trendInitialHours=12;this.recordsPanel='evidence';this.setView('records');}
  openTasksView(){this.setView('alerts');setTimeout(()=>document.getElementById('icu-tasks')?.scrollIntoView({block:'start'}),60);}
  tabIcon(key:string) { return ({risk:'activity',records:'file',alerts:'bell'} as Record<string,string>)[key] || 'file'; }
  /** Status for the host's own patient list/badge. Counts that failed to load are sent as null, never 0. */
  onStatus(s: SepsisStatus) {
    if (!this.patient || s.patientId !== this.patient.id) return;
    this.lastStatus = s;
    this.pendingCount = (s.pendingAlerts || 0) + (s.pendingTasks || 0);
    this.postStatus();
  }
  async refreshStatus() {
    const p = this.patient; if (!p) return; const generation = this.generation;
    const [a, t] = await Promise.allSettled([
      this.api.get<Page<Alert>>('/alerts?status=pending&size=1'),
      this.api.get<Page<Task>>('/bundles/tasks?metric=pending&patientId=' + encodeURIComponent(p.id) + '&size=1'),
    ]);
    if (generation !== this.generation || this.patient?.id !== p.id) return;
    this.onStatus({ ...(this.lastStatus || { patientId: p.id, level: 'unknown', summary: '' }), patientId: p.id,
      pendingAlerts: a.status === 'fulfilled' ? a.value.total : null, pendingTasks: t.status === 'fulfilled' ? t.value.total : null });
  }
  private lastStatus: SepsisStatus | null = null;
  private postStatus() {
    const s = this.lastStatus, origin = this.appliedOrigin;
    if (!s || !origin || !isIframe()) return;
    if (this.protocol === 'smartcare') sendSepsisStatus(s, origin);
    else this.send(origin, 'STATUS', undefined, s);
  }
  private protocol: 'smartcare' | 'secure' = 'smartcare';
  api = inject(Api);
  http = inject(HttpClient);
  @ViewChild(Detail) detail?: Detail;
  @ViewChild(Alerts) alerts?: Alerts;
  @ViewChild(Bundles) bundles?: Bundles;
  channel = uuid4();
  origins: string[] = [];
  patient: Patient | null = null;
  hostPatientId = '';
  hostGender = '';
  view = 'risk';
  loading = false;
  error = '';
  private generation = 0;
  private timer?: ReturnType<typeof setTimeout>;
  private appliedOrigin = '';
  private parentOrigin = resolveParentOrigin();
  private handshakeTimer?: ReturnType<typeof setInterval>;
  private handshakeTried = 0;
  private applying = false;
  tabs = [
    { key: 'risk', label: '概览' },
    { key: 'alerts', label: '预警处理' },
    { key: 'records', label: '记录与依据' },
  ];
  private listener = (event: MessageEvent) => void this.receive(event);

  get mrn(): string {
    return this.hostMrn || '';
  }
  get gender(): string {
    return this.hostGender || '';
  }
  private hostMrn = '';

  ngOnInit() {
    this.api.logout();
    void this.start();
  }

  async start() {
    try {
      const c = (
        await firstValueFrom(
          this.http.get<Envelope<{ allowedParentOrigins: string[] }>>('/embed-api/config'),
        )
      ).data;
      this.origins = c.allowedParentOrigins || [];
    } catch {
      this.origins = [];
    }
    if (!isIframe()) {
      this.error = '请从宿主系统打开本模块。';
      return;
    }
    window.addEventListener('message', this.listener);
    this.startSmartCareHandshake();
  }

  private startSmartCareHandshake() {
    const tryOnce = () => {
      if (this.patient) {
        this.stopHandshake();
        return;
      }
      if (this.parentOrigin !== '*' && (!this.origins.length || this.origins.includes(this.parentOrigin))) {
        sendSmartCareReady(this.parentOrigin);
        // Both adapters use the same iframe; preserve the included demo-host handshake.
        if (this.origins.includes(this.parentOrigin)) this.send(this.parentOrigin, 'READY');
      } else { this.error = '请配置明确的宿主来源 parentOrigin 后重新打开。'; }
      this.handshakeTried += 1;
      if (this.handshakeTried >= 5) this.stopHandshake();
    };
    tryOnce();
    this.handshakeTimer = setInterval(tryOnce, 2500);
  }

  private stopHandshake() {
    if (this.handshakeTimer) {
      clearInterval(this.handshakeTimer);
      this.handshakeTimer = undefined;
    }
  }

  private send(origin: string, type: string, requestId?: string, payload: unknown = {}) {
    window.parent.postMessage(
      { namespace, version: 1, type, channel: this.channel, requestId, payload },
      origin,
    );
  }

  private revoke() {
    if (this.api.scheme === 'Bearer' && this.api.token) {
      void firstValueFrom(
        this.http.post(
          '/embed-api/revoke',
          {},
          { headers: { Authorization: 'Bearer ' + this.api.token } },
        ),
      ).catch(() => {});
    }
  }

  private dirty() {
    return !!this.alerts?.selected || !!this.bundles?.selected;
  }

  private busy() {
    return !!this.detail?.busy || !!this.alerts?.busy || !!this.bundles?.busy;
  }

  setView(v: string) {
    if (this.dirty() || this.busy()) {
      this.error = '请先完成或取消当前操作。';
      return;
    }
    this.error = '';
    this.view = v;
  }

  async receive(event: MessageEvent) {
    const smartCare = extractSmartCareContext(event);
    if (smartCare) {
      // Message routing only, not a second login: only the designated parent may select a patient.
      if (event.source !== window.parent || event.origin !== this.parentOrigin ||
          (this.origins.length > 0 && !this.origins.includes(event.origin))) return;
      await this.applySmartCare(event, smartCare);
      return;
    }
    const m = trustedMessage(event, window.parent, this.origins, this.channel);
    if (!m) return;
    await this.applySecureContext(event, m);
  }

  private async applySmartCare(event: MessageEvent, msg: { patient?: SmartCarePatient | null }) {
    const hostPatient = msg.patient || {};
    const patientId = String(hostPatient.id || '').trim();
    if (!patientId) {
      if (this.dirty() || this.busy()) { this.error = '请先完成或取消当前操作，再清空患者。'; return; }
      ++this.generation; this.revoke(); this.api.logout(); this.patient = null;
      this.hostMrn = ''; this.hostGender = ''; this.loading = false; this.applying = false;
      clearTimeout(this.timer); return;
    }
    if (!/^[a-fA-F0-9]{24}$/.test(patientId)) { this.error='宿主患者标识格式无效，未切换患者。'; return; }
    if (this.dirty() || this.busy()) {
      this.error = '请先完成或取消当前操作，再切换患者。';
      return;
    }
    // The newest selection wins. A new parent message invalidates any slower pending response.
    this.applying = true;
    const generation = ++this.generation;
    const samePatient = this.patient?.id === patientId;
    if (!samePatient) {
      this.patient = null;
      this.revoke();
      this.api.logout();
    }
    this.error = '';
    this.loading = true;
    clearTimeout(this.timer);
    this.appliedOrigin = event.origin;
    this.hostMrn = String(hostPatient.mrn || '').trim();
    this.hostGender = String(hostPatient.genderStr || hostPatient.gender || '').trim();
    if (this.hostGender === 'Male' || this.hostGender === 'M' || this.hostGender === '1')
      this.hostGender = '男';
    if (this.hostGender === 'Female' || this.hostGender === 'F' || this.hostGender === '2')
      this.hostGender = '女';
    try {
      const s = (
        await firstValueFrom(
          this.http.post<Envelope<HostPatientSession>>(
            '/embed-api/host-context?parentOrigin=' + encodeURIComponent(event.origin),
            { patientId },
          ),
        )
      ).data;
      if (generation !== this.generation) return;
      this.api.scheme = 'Bearer';
      this.api.token = s.accessToken;
      this.api.user = s.username;
      this.api.roles = s.roles;
      const p = await this.api.get<Patient>('/patients/' + encodeURIComponent(s.patientId));
      if (generation !== this.generation) return;
      if (p.id !== patientId || p.encounterId !== s.encounterId) throw new Error('患者上下文不一致');
      this.patient = p; this.protocol = 'smartcare';
      if (!samePatient) { this.lastStatus = null; this.pendingCount = 0; }
      this.view = samePatient ? this.view : 'risk';
      this.stopHandshake();
      this.timer = setTimeout(
        () => {
          ++this.generation;
          this.patient = null;
          this.api.logout();
          this.error = '患者上下文已过期，请在宿主重新选择患者。';
          this.startSmartCareHandshake();
        },
        Math.max(0, Date.parse(s.expiresAt) - Date.now()),
      );
    } catch (e) {
      if (generation === this.generation) {
        this.api.logout();
        this.patient = null;
        this.error = errorMessage(e);
      }
    } finally {
      if (generation === this.generation) { this.loading = false; this.applying = false; }
    }
  }

  private async applySecureContext(
    event: MessageEvent,
    m: Awaited<ReturnType<typeof trustedMessage>>,
  ) {
    if (!m) return;
    if (this.dirty() || this.busy()) {
      this.send(event.origin, 'CONTEXT_REJECTED', m.requestId, { reason: 'UNSAVED_OR_BUSY' });
      return;
    }
    const generation = ++this.generation;
    this.patient = null;
    this.revoke();
    this.api.logout();
    this.error = '';
    this.loading = false;
    clearTimeout(this.timer);
    if (m.type === 'CLEAR_CONTEXT') {
      this.send(event.origin, 'CONTEXT_CLEARED', m.requestId);
      return;
    }
    this.loading = true;
    try {
      const body = { code: m.payload!.code, parentOrigin: event.origin, channel: this.channel };
      const s = (
        await firstValueFrom(this.http.post<Envelope<Session>>('/embed-api/exchange', body))
      ).data;
      if (generation !== this.generation) return;
      this.api.scheme = 'Bearer';
      this.api.token = s.accessToken;
      this.api.user = s.username;
      this.api.roles = s.roles;
      const p = await this.api.get<Patient>('/patients/' + encodeURIComponent(s.patientId));
      if (generation !== this.generation) return;
      if (p.id !== m.payload!.patientId || p.encounterId !== m.payload!.encounterId) throw new Error('患者上下文不一致');
      this.patient = p; this.protocol = 'secure'; this.lastStatus = null; this.pendingCount = 0;
      this.hostMrn = ''; this.hostGender = '';
      this.view = 'risk';
      this.timer = setTimeout(() => {
        ++this.generation; this.revoke(); this.patient = null; this.api.logout();
        this.error = '患者上下文已过期，请在宿主重新选择患者。';
        this.send(event.origin, 'AUTH_REQUIRED');
      }, Math.max(0, Date.parse(s.expiresAt) - Date.now()));
      this.appliedOrigin = event.origin;
      this.parentOrigin = event.origin;
      this.send(event.origin, 'CONTEXT_APPLIED', m.requestId, {
        patientId: p.id,
        encounterId: p.encounterId,
      });
    } catch (e) {
      if (generation === this.generation) {
        this.api.logout();
        this.patient = null;
        this.error = errorMessage(e);
        this.send(event.origin, 'CONTEXT_REJECTED', m.requestId, {
          reason: 'AUTH_OR_CONTEXT_INVALID',
        });
      }
    } finally {
      if (generation === this.generation) this.loading = false;
    }
  }

  ngOnDestroy() {
    ++this.generation;
    clearTimeout(this.timer);
    this.stopHandshake();
    window.removeEventListener('message', this.listener);
    this.revoke();
    this.api.logout();
  }
}
