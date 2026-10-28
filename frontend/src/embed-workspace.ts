import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, inject, OnInit, OnDestroy, ViewChild } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Api, Envelope, Patient, errorMessage } from './api';
import { UiIcon } from './ui-icon';
import { Detail } from './pages/detail';
import { Alerts } from './pages/alerts';
import { Bundles } from './pages/bundles';
import { SepsisPanel } from './sepsis-panel';
import { namespace, trustedMessage } from './embed-protocol';
import { uuid4 } from './uuid';
import {
  extractSmartCareContext,
  isIframe,
  resolveParentOrigin,
  sendSmartCareReady,
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
          (click)="trendInitialHours=12; setView(t.key)"
        >
          <ui-icon [name]="tabIcon(t.key)"/>{{ t.label }}
        </button>
      </nav></div>

      <p class="icu-error" role="alert" *ngIf="error">{{ error }}</p>

      <section class="icu-body">
        <sepsis-panel *ngIf="view === 'risk'" [patientId]="p.id" [encounterId]="p.encounterId"
          (openTrends)="openTrendHistory()" (openEvidence)="setView('evidence')" (openAlerts)="setView('alerts')" (openTasks)="setView('tasks')" />
        <patient-detail
          *ngIf="view === 'trends' || view === 'evidence'"
          [patientId]="p.id"
          [embedded]="true" [initialHours]="trendInitialHours" (openRecords)="setView('evidence')"
          [panel]="view === 'trends' ? 'trends' : 'evidence'"
          (openAlerts)="setView('alerts')"
        />
        <patient-alerts
          *ngIf="view === 'alerts'"
          [embedded]="true"
          (openEvidence)="setView('evidence')"
        />
        <patient-bundles
          *ngIf="view === 'tasks'"
          [embedded]="true"
          (openEvidence)="setView('evidence')"
        />
      </section>
    </ng-container>

    <div class="icu-loading-mask" *ngIf="patient && loading" aria-hidden="true">
      <div class="spinner"></div>
    </div>
  </main>`,
})
export class EmbedWorkspace implements OnInit, OnDestroy {
  trendInitialHours=12;
  openTrendHistory(){this.trendInitialHours=168;this.setView('trends');}
  tabIcon(key:string) { return ({risk:'activity',trends:'chart',evidence:'file',alerts:'bell',tasks:'clipboard'} as Record<string,string>)[key] || 'file'; }
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
    { key: 'risk', label: '临床概览' },
    { key: 'trends', label: '观测趋势' },
    { key: 'evidence', label: '原始记录' },
    { key: 'alerts', label: '预警处置' },
    { key: 'tasks', label: '执行与审核' },
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
      this.patient = p;
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
      this.patient = p;
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
