import { CommonModule } from '@angular/common';
import { Component, Input, OnInit, inject } from '@angular/core';
import { Api, errorMessage } from './api';
import { UiIcon } from './ui-icon';
import { fmtLeft } from './icu05-view';

export interface BoardWindow { key: string; met: number; notMet: number; unknown: number; inProgress: number; rate: number | null; }
export interface BoardIndicator { key: string; label: string; available: boolean; yes?: number; no?: number; unknown?: number; rate: number | null; rule: string; }
export interface BoardCase {
  patientId: string; encounterId: string; label: string | null; bed: string | null; simulated?: boolean; status: string; statusLabel: string; t0: string | null;
  eligible: boolean | null; counted: boolean; windows: { key: string; state: string; deadline: string }[]; next: string | null; nextDeadline: string | null;
  minutesLeft: number | null; failReasons: string[];
}
export interface Icu05Board {
  asOf: string; from: string; to: string; days: number; ruleSource: string; screened: number; unavailable: number; septicShock: number; suspected: number;
  denominator: number; excluded: number; poolUnknown: number; windows: BoardWindow[]; indicators: BoardIndicator[]; cases: BoardCase[]; limitations: string[];
  readFailed?: number; truncated?: boolean;
}

/** Director view: department ICU-05 completion rates, process sub-indicators and the cases that need attention now. Read-only. */
@Component({
  selector: 'ward-board', standalone: true, imports: [CommonModule, UiIcon],
  template: `<section class="wb" aria-label="科室质控">
    <div class="embedded-section-head wb-head">
      <h1><ui-icon name="chart"/>感染性休克 Bundle · 科室质控</h1>
      <div class="segmented" role="group" aria-label="统计范围">
        <button type="button" *ngFor="let d of [7, 30]" [class.selected]="days === d" (click)="days = d; load()">近 {{ d }} 天</button>
      </div>
      <button type="button" class="sx-refresh" (click)="load()" [disabled]="busy" [class.spin]="busy" aria-label="刷新质控"><ui-icon name="refresh"/><span *ngIf="b">{{ b.asOf | date:'HH:mm' }}</span></button>
    </div>
    <p class="review-inline-error" role="alert" *ngIf="error"><ui-icon name="info"/>{{ error }}</p>
    <div class="review-loading" *ngIf="busy && !b" role="status"><span class="spinner"></span>正在按 ICU-05 规则重算科室病例…</div>
    <ng-container *ngIf="b">
      <p class="wb-pool">入科 {{ b.screened }} 例 · 感染性休克 <b>{{ b.septicShock }}</b> 例（纳入分母 <b>{{ b.denominator }}</b><ng-container *ngIf="b.excluded"> · 不符合纳入 {{ b.excluded }}</ng-container><ng-container *ngIf="b.poolUnknown"> · 纳入待确认 {{ b.poolUnknown }}</ng-container>）<ng-container *ngIf="b.suspected"> · 疑似待复核 <b>{{ b.suspected }}</b> 例</ng-container><ng-container *ngIf="b.unavailable"> · 数据未接入 {{ b.unavailable }}</ng-container></p>
      <div class="wb-kpis">
        <div class="wb-kpi" *ngFor="let w of b.windows" [attr.data-tone]="tone(w.rate)">
          <span class="wb-kpi-k">{{ w.key }} 完成率</span>
          <strong>{{ pct(w.rate) }}</strong>
          <small>{{ w.met }}/{{ w.met + w.notMet }} 例<ng-container *ngIf="w.inProgress"> · 进行中 {{ w.inProgress }}</ng-container><ng-container *ngIf="w.unknown"> · 无法判定 {{ w.unknown }}</ng-container></small>
        </div>
      </div>
      <div class="review-card wb-ind">
        <div class="review-card-title"><h2>救治过程指标</h2><span class="ai-label">感染性休克救治能力指数 · 过程子指标</span></div>
        <div class="wb-ind-grid">
          <div *ngFor="let i of b.indicators" [title]="i.rule" [class.off]="!i.available">
            <span>{{ i.label }}</span>
            <strong>{{ i.available ? pct(i.rate) : '未接入' }}</strong>
            <small *ngIf="i.available">{{ i.yes }}/{{ (i.yes || 0) + (i.no || 0) }} 例<ng-container *ngIf="i.unknown"> · 无法判定 {{ i.unknown }}</ng-container></small>
            <small *ngIf="!i.available">{{ i.rule }}</small>
          </div>
        </div>
      </div>
      <div class="review-card wb-cases">
        <div class="review-card-title"><h2>需要关注的病例</h2><span class="ai-label">进行中按剩余时间排序</span></div>
        <div class="work-empty" *ngIf="!b.cases.length"><span>统计范围内无感染性休克或疑似病例</span></div>
        <div class="review-table-wrap" *ngIf="b.cases.length"><table class="wb-table">
          <thead><tr><th>床号</th><th>患者</th><th>判定</th><th>T0</th><th *ngFor="let k of keys">{{ k }}</th><th>下一步 / 问题</th></tr></thead>
          <tbody><tr *ngFor="let c of b.cases" [class.current]="c.patientId === currentPatientId" [attr.data-status]="c.status">
            <td class="wb-bed">{{ c.bed || '—' }}</td>
            <td>{{ c.label || '—' }}<small *ngIf="c.patientId === currentPatientId">当前患者</small></td>
            <td><span class="wb-status" [attr.data-status]="c.status">{{ c.status === 'septic_shock' ? '感染性休克' : '疑似待复核' }}</span><small *ngIf="c.status === 'septic_shock' && !c.counted">{{ c.eligible === false ? '不计入分母' : '纳入待确认' }}</small></td>
            <td class="wb-t0">{{ c.t0 ? (c.t0 | date:'MM-dd HH:mm') : '—' }}</td>
            <td *ngFor="let k of keys" class="wb-w"><ng-container *ngIf="win(c, k) as w"><i [attr.data-state]="w.state" [title]="'截止 ' + (w.deadline | date:'MM-dd HH:mm')">{{ stateText(w.state) }}</i></ng-container></td>
            <td class="wb-next">
              <span class="wb-due" *ngIf="c.next" [attr.data-urgent]="(c.minutesLeft ?? 999) <= 30 ? 'y' : null">{{ c.next }} · 剩 {{ left(c.minutesLeft || 0) }}</span>
              <span class="wb-fail" *ngIf="c.failReasons.length">{{ c.failReasons.join('、') }}</span>
              <span class="muted" *ngIf="!c.next && !c.failReasons.length">{{ c.status === 'suspected_review' ? '待医生复核感染证据' : '—' }}</span>
            </td>
          </tr></tbody>
        </table></div>
      </div>
      <details class="sx-limits"><summary><ui-icon name="info"/>统计口径</summary><ul><li>{{ b.ruleSource }}</li><li *ngFor="let l of b.limitations">{{ l }}</li><li *ngIf="b.readFailed">{{ b.readFailed }} 例读取失败，未计入。</li><li *ngIf="b.truncated">病例数超过 300，仅统计最近 300 例。</li></ul></details>
    </ng-container>
  </section>`,
})
export class WardBoard implements OnInit {
  @Input() currentPatientId = '';
  api = inject(Api);
  b: Icu05Board | null = null; busy = false; error = ''; days = 7;
  readonly keys = ['1h', '3h', '6h'];
  left = fmtLeft;
  ngOnInit() { void this.load(); }
  async load() {
    this.busy = true; this.error = '';
    try { this.b = await this.api.get<Icu05Board>('/quality/icu05-board?days=' + this.days); }
    catch (e) { this.error = '质控统计读取失败：' + errorMessage(e); }
    finally { this.busy = false; }
  }
  pct(r: number | null) { return r === null ? '—' : Math.round(r * 100) + '%'; }
  tone(r: number | null) { return r === null ? 'none' : r >= 0.8 ? 'ok' : r >= 0.5 ? 'warn' : 'bad'; }
  win(c: BoardCase, k: string) { return c.windows.find(w => w.key === k) || null; }
  stateText(s: string) { return s === 'met' ? '✓' : s === 'not_met' ? '×' : s === 'in_progress' ? '进行中' : '—'; }
}
