import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output, inject, OnInit } from '@angular/core';
import { UiIcon } from '../ui-icon';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Alert, Api, errorMessage, History, Page } from '../api';
import { text } from '../presentation';
@Component({
  selector: 'patient-alerts',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, UiIcon],
  template: `<header class="embedded-section-head" *ngIf="embedded"><h1><ui-icon name="bell"/>预警处理</h1><div class="al-scope" role="group" aria-label="预警范围"><button type="button" [class.on]="scope === 'patient'" [attr.aria-pressed]="scope === 'patient'" (click)="setScope('patient')">本患者</button><button type="button" [class.on]="scope === 'department'" [attr.aria-pressed]="scope === 'department'" (click)="setScope('department')">全科室</button></div><button (click)="load()" [disabled]="busy"><ui-icon name="refresh"/>刷新</button></header><div class="pagehead">
      <div>
        <p class="eyebrow">ALERT REVIEW CENTER</p>
        <h1>预警处置中心</h1>
        <p class="muted">床旁筛查或 ICU-05 门控判定为“疑似休克”或“感染 + 异常”时自动生成；确认表示复核筛查结果，不代表确诊。</p>
      </div>
      <button (click)="load()" [disabled]="busy">刷新</button>
    </div>
    <section class="card">
      <div class="toolbar">
        <label
          >状态筛选<select [(ngModel)]="status" (change)="page = 0; load()">
            <option value="">全部状态</option>
            <option value="pending">待审核</option>
            <option value="confirmed">已确认</option>
            <option value="deferred">暂缓</option>
            <option value="closed">已关闭</option>
            <option value="rejected">已驳回</option>
          </select></label
        ><span class="muted">共 {{ total }} 条</span>
      </div>
      <p class="error" role="alert" *ngIf="error">{{ error }}</p>
      <div class="work-empty" *ngIf="!alerts.length && !busy && !error"><ui-icon name="bell"/><strong>{{ status === 'pending' ? '暂无待处理预警' : '当前筛选下没有预警' }}</strong><small class="muted">疑似休克（需要升压药，或 乳酸≥2 且 MAP&lt;65）或 感染线索 + 异常指标时自动生成</small><button *ngIf="embedded" (click)="openEvidence.emit()">查看记录 <ui-icon name="arrow"/></button></div>
      <article class="alertrow" *ngFor="let a of alerts">
        <div>
          <span class="tag" [class.danger]="a.severity === 'critical'">{{ kindLabel(a) }}</span
          ><strong>{{ who(a) }}</strong
          ><small>{{ a.createdAt | date: 'MM-dd HH:mm' }}<ng-container *ngIf="a.lastSeenAt && a.lastSeenAt !== a.createdAt"> · 最近仍触发 {{ a.lastSeenAt | date: 'MM-dd HH:mm' }}</ng-container> · {{ sourceLabel(a) }}</small>
        </div>
        <div>
          <strong>{{ text(a.status) }}</strong>
          <p class="al-trigger" *ngIf="a.trigger">{{ a.trigger }}</p>
          <p *ngIf="!a.trigger || a.reason !== a.trigger">{{ a.trigger ? '处理说明：' : '' }}{{ a.reason }}</p>
          <a
            [routerLink]="embedded ? null : ['/patients', a.patientId]"
            (click)="embedded && openEvidence.emit()"
            (keydown.enter)="embedded && openEvidence.emit()"
            [attr.tabindex]="embedded ? 0 : null"
            [attr.role]="embedded ? 'button' : null"
            >查看依据</a
          >
        </div>
        <div class="actions">
          <button *ngIf="mine(a)" (click)="showHistory(a)">历史</button
          ><button
            *ngIf="
              api.has('DOCTOR') && mine(a) &&
              ['pending', 'acknowledged', 'deferred', 'confirmed'].includes(a.status)
            "
            class="primary"
            (click)="selected = a; reason = ''; quick = ''; action = 'confirm'"
          >
            处置
          </button>
        </div>
      </article>
      <footer class="pager" *ngIf="total>0">
        <button (click)="page = page - 1; load()" [disabled]="!page || busy">上一页</button
        ><span>第 {{ page + 1 }} 页</span
        ><button (click)="page = page + 1; load()" [disabled]="(page + 1) * 20 >= total || busy">
          下一页
        </button>
      </footer>
    </section>
    <section class="card" *ngIf="records.length">
      <h2>预警历史</h2>
      <p *ngFor="let h of records">
        {{ h.createdAt | date: 'MM-dd HH:mm:ss' }} · {{ h.actor }} · {{ h.action }} · {{ h.reason }}
      </p>
    </section>
    <div class="overlay" *ngIf="selected">
      <section class="dialog" role="dialog" aria-modal="true" aria-labelledby="review-title">
        <h2 id="review-title">处理预警<span *ngIf="!embedded"> · {{ selected.patientId }}</span></h2>
        <form (ngSubmit)="submit()">
          <label
            >处置动作<select name="action" [(ngModel)]="action">
              <option *ngIf="selected.status !== 'confirmed'" value="confirm">
                {{ selected.simulated ? '确认筛查并创建演示复评任务' : '确认筛查（已复核）' }}
              </option>
              <option *ngIf="selected.status !== 'confirmed'" value="reject">驳回</option>
              <option *ngIf="['pending', 'acknowledged'].includes(selected.status)" value="defer">
                暂缓（需人工复评）
              </option>
              <option *ngIf="selected.status !== 'confirmed'" value="acknowledge">确认接收</option>
              <option *ngIf="selected.status === 'confirmed'" value="close">关闭预警</option>
            </select></label
          ><fieldset class="quick-reasons"><legend>处理原因</legend><button type="button" *ngFor="let q of quickReasons" [class.selected]="quick === q" [attr.aria-pressed]="quick === q" (click)="quick = q">{{ q }}</button></fieldset><label
            >{{ quick === '其他' ? '原因说明（必填）' : '补充说明（选填）' }}<textarea
              name="reason"
              [(ngModel)]="reason"
              maxlength="900"
              rows="2"
            ></textarea>
          </label>
          <p class="muted small">保存操作者、时间和前后状态。</p>
          <div class="actions">
            <button type="button" (click)="selected = null" [disabled]="busy">取消</button
            ><button class="primary" [disabled]="busy || !finalReason">
              {{ busy ? '保存中…' : '确认提交' }}
            </button>
          </div>
        </form>
        <p class="error" *ngIf="error">{{ error }}</p>
      </section>
    </div>`,
})
export class Alerts implements OnInit {
  @Input() embedded = false;
  /** Current patient in the embedded workspace; other patients' alerts are listed read-only. */
  @Input() patientId: string | null = null;
  scope: 'patient' | 'department' = 'patient';
  @Output() openEvidence = new EventEmitter<void>();
  @Output() changed = new EventEmitter<void>();
  api = inject(Api);
  quickReasons = ['已处理', '已知晓，继续观察', '误报：监测干扰', '误报：数据错误', '其他'];
  quick = '';
  /** Quick choice plus optional note; "其他" requires a note. */
  get finalReason() {
    const note = this.reason.trim();
    if (!this.quick) return note;
    if (this.quick === '其他') return note ? '其他：' + note : '';
    return note ? this.quick + '；' + note : this.quick;
  }
  route = inject(ActivatedRoute);
  alerts: Alert[] = [];
  records: History[] = [];
  selected: Alert | null = null;
  status = '';
  action = 'confirm';
  reason = '';
  error = '';
  busy = false;
  page = 0;
  total = 0;
  text = text;
  setScope(s: 'patient' | 'department') {
    if (this.scope === s) return;
    this.scope = s;
    this.page = 0;
    this.records = [];
    void this.load();
  }
  mine(a: Alert) {
    return !this.embedded || !this.patientId || a.patientId === this.patientId;
  }
  who(a: Alert) {
    if (this.embedded && this.mine(a) && this.scope === 'patient') return '当前患者';
    const name = [a.bed ? a.bed + '床' : '', a.patientLabel || ''].filter(Boolean).join(' ');
    return (name || a.patientId) + (this.embedded && this.mine(a) ? '（当前）' : '');
  }
  kindLabel(a: Alert) {
    if (a.kind === 'suspected_shock') return '疑似休克';
    if (a.kind === 'infection_abnormal') return '感染 + 异常';
    return text(a.severity);
  }
  sourceLabel(a: Alert) {
    if (a.source === 'icu05') return 'ICU-05 门控';
    if (a.source === 'bedside') return '床旁筛查';
    return a.ruleVersion;
  }
  ngOnInit() {
    this.status = this.route.snapshot.queryParamMap.get('status') || (this.embedded ? 'pending' : '');
    void this.load();
  }
  async load() {
    this.error = '';
    this.busy = true;
    try {
      const p = await this.api.get<Page<Alert>>(
        '/alerts?status=' + this.status + '&page=' + this.page + (this.scope === 'department' ? '&scope=department' : ''),
      );
      this.alerts = p.items;
      this.total = p.total;
    } catch (e) {
      this.error = errorMessage(e);
    } finally {
      this.busy = false;
    }
  }
  async submit() {
    const reason = this.finalReason;
    if (!this.selected || !reason) return;
    this.busy = true;
    this.error = '';
    try {
      await this.api.post('/alerts/' + this.selected.id + '/review', {
        action: this.selected.status === 'confirmed' ? 'close' : this.action,
        reason,
        expectedVersion: this.selected.version,
      });
      this.selected = null;
      await this.load();
      this.changed.emit();
    } catch (e) {
      this.error = errorMessage(e);
    } finally {
      this.busy = false;
    }
  }
  async showHistory(a: Alert) {
    try {
      this.records = (await this.api.get<Page<History>>('/alerts/' + a.id + '/history')).items;
    } catch (e) {
      this.error = errorMessage(e);
    }
  }
}
