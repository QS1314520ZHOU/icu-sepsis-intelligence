import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output, inject, OnInit } from '@angular/core';
import { UiIcon } from '../ui-icon';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Api, errorMessage, History, Page, Task } from '../api';
import { text } from '../presentation';
@Component({
  selector: 'patient-bundles',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, UiIcon],
  template: `<header class="embedded-section-head sub" *ngIf="embedded"><h2><ui-icon name="clipboard"/>执行任务</h2><button (click)="load()" [disabled]="busy"><ui-icon name="refresh"/>刷新</button></header><div class="pagehead">
      <div>
        <p class="eyebrow">BUNDLE WORKFLOW</p>
        <h1>Bundle 任务工作台</h1>
        <p class="muted">适用性、执行、目标与临床审核分开记录。</p>
      </div>
      <button (click)="load()" [disabled]="busy">刷新</button>
    </div>
    <details class="workflow-rules"><summary><ui-icon name="info"/>任务规则</summary><p>当前只启用“临床复评”模拟任务。60分钟是软件演示参数，不是已批准的治疗 Bundle 时限。</p></details>
    <p class="error" role="alert" *ngIf="error">{{ error }}</p>
    <section class="card">
      <div class="work-empty" *ngIf="!tasks.length && !busy && !error"><ui-icon name="clipboard"/><strong>暂无待处理任务</strong><span>确认预警后建立的复评任务显示在这里</span></div>
      <article class="taskrow" *ngFor="let t of tasks">
        <div class="tasktitle">
          <strong>{{ t.itemName }}</strong
          ><a
            [routerLink]="embedded ? null : ['/patients', t.patientId]"
            (click)="embedded && openEvidence.emit()"
            (keydown.enter)="embedded && openEvidence.emit()"
            [attr.tabindex]="embedded ? 0 : null"
            [attr.role]="embedded ? 'button' : null"
            >{{ embedded ? '查看依据' : t.patientId }} →</a
          ><span class="muted">截止 {{ t.dueAt | date: 'MM-dd HH:mm:ss' }}</span>
        </div>
        <div class="states">
          <div>
            <small>适用性</small><strong>{{ text(t.applicability) }}</strong>
          </div>
          <div>
            <small>执行</small><strong>{{ text(t.executionStatus) }}</strong>
          </div>
          <div>
            <small>目标</small><strong>{{ text(t.targetStatus) }}</strong>
          </div>
          <div>
            <small>临床审核</small><strong>{{ text(t.clinicalReviewStatus) }}</strong>
          </div>
        </div>
        <div class="actions">
          <button (click)="showHistory(t)">状态历史</button
          ><button
            *ngIf="
              (api.has('DOCTOR') || api.has('NURSE')) &&
              !t.evidenceId &&
              !['not_applicable', 'contraindicated'].includes(t.applicability)
            "
            (click)="open(t, 'execute')"
          >
            登记执行证据</button
          ><button class="primary" *ngIf="api.has('DOCTOR')" (click)="open(t, 'review')">
            审核适用性与结果
          </button>
        </div>
      </article>
      <footer class="pager" *ngIf="total>0">
        <button (click)="page = page - 1; load()" [disabled]="!page || busy">上一页</button
        ><span>第 {{ page + 1 }} 页 · 共 {{ total }} 条</span
        ><button (click)="page = page + 1; load()" [disabled]="(page + 1) * 20 >= total || busy">
          下一页
        </button>
      </footer>
    </section>
    <section class="card" *ngIf="records.length">
      <h2>任务历史</h2>
      <p *ngFor="let h of records">
        {{ h.createdAt | date: 'MM-dd HH:mm:ss' }} · {{ h.actor }} · {{ h.action }} · {{ h.reason }}
      </p>
    </section>
    <div class="overlay" *ngIf="selected">
      <section class="dialog" role="dialog" aria-modal="true" aria-labelledby="task-title">
        <h2 id="task-title">{{ mode === 'execute' ? '登记执行证据' : '临床审核' }}</h2>
        <form (ngSubmit)="submit()">
          <ng-container *ngIf="mode === 'execute'"
            ><label
              >实际执行时间<input
                name="eventTime"
                type="datetime-local"
                step="1"
                [(ngModel)]="eventTime"
                required /></label
            ><label
              >原始执行记录 ID<input
                name="sourceRecordId"
                [(ngModel)]="sourceRecordId"
                required
                maxlength="128" /></label
            ><label
              >来源系统<input
                name="sourceSystem"
                [(ngModel)]="sourceSystem"
                required
                maxlength="80" /></label></ng-container
          ><ng-container *ngIf="mode === 'review'"
            ><label
              >适用性<select name="applicability" [(ngModel)]="applicability">
                <option value="required">适用</option>
                <option value="conditional">有条件适用（本次条件已确认）</option>
                <option value="individualized">个体化适用（需说明依据）</option>
                <option value="not_applicable">不适用</option>
                <option value="contraindicated">禁忌</option>
                <option value="review_pending">待进一步确认</option>
              </select></label
            ><label
              >目标状态<select name="targetStatus" [(ngModel)]="targetStatus">
                <option value="not_assessed">尚未评估</option>
                <option value="unknown">信息不足</option>
                <option value="met">已达目标</option>
                <option value="not_met">未达目标</option>
              </select></label
            ></ng-container
          ><label
            >证据说明 / 审核原因<textarea
              name="reason"
              [(ngModel)]="reason"
              required
              maxlength="1000"
            ></textarea>
          </label>
          <div class="actions">
            <button type="button" (click)="selected = null" [disabled]="busy">取消</button
            ><button class="primary" [disabled]="busy || !reason.trim()">
              {{ busy ? '保存中…' : '保存记录' }}
            </button>
          </div>
        </form>
        <p class="error" *ngIf="error">{{ error }}</p>
      </section>
    </div>`,
})
export class Bundles implements OnInit {
  @Input() embedded = false;
  @Output() openEvidence = new EventEmitter<void>();
  @Output() changed = new EventEmitter<void>();
  api = inject(Api);
  route = inject(ActivatedRoute);
  filter = new URLSearchParams();
  tasks: Task[] = [];
  records: History[] = [];
  selected: Task | null = null;
  mode = 'execute';
  applicability = 'required';
  targetStatus = 'not_assessed';
  reason = '';
  eventTime = '';
  sourceRecordId = '';
  sourceSystem = 'MOCK_MANUAL';
  busy = false;
  error = '';
  page = 0;
  total = 0;
  text = text;
  ngOnInit() {
    for (const k of ['metric', 'from', 'to']) {
      const v = this.route.snapshot.queryParamMap.get(k);
      if (v) this.filter.set(k, v);
    }
    void this.load();
  }
  async load() {
    this.error = '';
    this.busy = true;
    try {
      const p = await this.api.get<Page<Task>>(
        '/bundles/tasks?page=' + this.page + '&' + this.filter.toString(),
      );
      this.tasks = p.items;
      this.total = p.total;
    } catch (e) {
      this.error = errorMessage(e);
    } finally {
      this.busy = false;
    }
  }
  open(t: Task, mode: string) {
    this.selected = t;
    this.mode = mode;
    this.reason = '';
    this.applicability = ['review_pending'].includes(t.applicability)
      ? 'required'
      : t.applicability;
    this.targetStatus = t.targetStatus;
    const d = new Date();
    this.eventTime = new Date(d.getTime() - d.getTimezoneOffset() * 60000)
      .toISOString()
      .slice(0, 19);
    this.sourceRecordId = '';
  }
  async submit() {
    if (!this.selected || !this.reason.trim()) return;
    this.busy = true;
    this.error = '';
    try {
      const b =
        this.mode === 'execute'
          ? {
              eventTime: new Date(this.eventTime).toISOString(),
              sourceRecordId: this.sourceRecordId,
              sourceSystem: this.sourceSystem,
              reason: this.reason,
              expectedVersion: this.selected.version,
            }
          : {
              action: 'confirm',
              applicability: this.applicability,
              targetStatus: this.targetStatus,
              reason: this.reason,
              expectedVersion: this.selected.version,
            };
      await this.api.post(
        '/bundles/tasks/' +
          this.selected.id +
          (this.mode === 'execute' ? '/execution-records' : '/review'),
        b,
      );
      this.selected = null;
      await this.load();
      this.changed.emit();
    } catch (e) {
      this.error = errorMessage(e);
    } finally {
      this.busy = false;
    }
  }
  async showHistory(t: Task) {
    try {
      this.records = (
        await this.api.get<Page<History>>('/bundles/tasks/' + t.id + '/history')
      ).items;
    } catch (e) {
      this.error = errorMessage(e);
    }
  }
}
