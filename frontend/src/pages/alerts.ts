import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output, inject, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Alert, Api, errorMessage, History, Page } from '../api';
import { text } from '../presentation';
@Component({
  selector: 'patient-alerts',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  template: `<div class="pagehead">
      <div>
        <p class="eyebrow">ALERT REVIEW CENTER</p>
        <h1>预警处置中心</h1>
        <p class="muted">确认表示复核筛查结果，不代表自动确诊。</p>
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
      <div class="empty" *ngIf="!alerts.length && !busy && !error">当前筛选下没有预警</div>
      <article class="alertrow" *ngFor="let a of alerts">
        <div>
          <span class="tag" [class.danger]="a.severity === 'critical'">{{ text(a.severity) }}</span
          ><strong>{{ a.patientId }}</strong
          ><small>{{ a.createdAt | date: 'MM-dd HH:mm:ss' }} · {{ a.ruleVersion }}</small>
        </div>
        <div>
          <strong>{{ text(a.status) }}</strong>
          <p>{{ a.reason }}</p>
          <a
            [routerLink]="embedded ? null : ['/patients', a.patientId]"
            (click)="embedded && openEvidence.emit()"
            (keydown.enter)="embedded && openEvidence.emit()"
            [attr.tabindex]="embedded ? 0 : null"
            [attr.role]="embedded ? 'button' : null"
            >核查患者证据</a
          >
        </div>
        <div class="actions">
          <button (click)="showHistory(a)">历史</button
          ><button
            *ngIf="
              api.has('DOCTOR') &&
              ['pending', 'acknowledged', 'deferred', 'confirmed'].includes(a.status)
            "
            class="primary"
            (click)="selected = a; reason = ''; action = 'confirm'"
          >
            处置
          </button>
        </div>
      </article>
      <footer class="pager">
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
        <h2 id="review-title">预警复核 · {{ selected.patientId }}</h2>
        <form (ngSubmit)="submit()">
          <label
            >处置动作<select name="action" [(ngModel)]="action">
              <option *ngIf="selected.status !== 'confirmed'" value="confirm">
                确认筛查并创建演示复评任务
              </option>
              <option *ngIf="selected.status !== 'confirmed'" value="reject">驳回</option>
              <option *ngIf="['pending', 'acknowledged'].includes(selected.status)" value="defer">
                暂缓（需人工复评）
              </option>
              <option *ngIf="selected.status !== 'confirmed'" value="acknowledge">确认接收</option>
              <option *ngIf="selected.status === 'confirmed'" value="close">关闭预警</option>
            </select></label
          ><label
            >临床复核原因<textarea
              name="reason"
              [(ngModel)]="reason"
              required
              maxlength="1000"
            ></textarea>
          </label>
          <p class="muted small">记录操作者、时间与前后状态。版本冲突时请刷新后重试。</p>
          <div class="actions">
            <button type="button" (click)="selected = null" [disabled]="busy">取消</button
            ><button class="primary" [disabled]="busy || !reason.trim()">
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
  @Output() openEvidence = new EventEmitter<void>();
  api = inject(Api);
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
  ngOnInit() {
    this.status = this.route.snapshot.queryParamMap.get('status') || '';
    void this.load();
  }
  async load() {
    this.error = '';
    this.busy = true;
    try {
      const p = await this.api.get<Page<Alert>>(
        '/alerts?status=' + this.status + '&page=' + this.page,
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
    if (!this.selected || !this.reason.trim()) return;
    this.busy = true;
    this.error = '';
    try {
      await this.api.post('/alerts/' + this.selected.id + '/review', {
        action: this.selected.status === 'confirmed' ? 'close' : this.action,
        reason: this.reason,
        expectedVersion: this.selected.version,
      });
      this.selected = null;
      await this.load();
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
