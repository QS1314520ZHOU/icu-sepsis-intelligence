import { CommonModule } from '@angular/common';
import { Component, inject, OnInit } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api, errorMessage, Quality } from '../api';
import { percentage } from '../presentation';
@Component({
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `<div class="pagehead">
      <div>
        <p class="eyebrow">QUALITY & TRACEABILITY</p>
        <h1>质控分析</h1>
        <p class="muted">最近 24 小时 · 按任务触发时间归属 · 演示任务口径</p>
      </div>
      <button (click)="load()">刷新统计</button>
    </div>
    <p class="error" *ngIf="error">{{ error }}</p>
    <ng-container *ngIf="q && !error"
      ><div class="metrics">
        <a
          class="metric"
          routerLink="/bundles"
          [queryParams]="{ metric: 'den', from: from, to: to }"
          ><small>已审核适用任务</small><strong>{{ q.denominator }}</strong
          ><span>统计分母 →</span></a
        ><a
          class="metric"
          routerLink="/bundles"
          [queryParams]="{ metric: 'onTime', from: from, to: to }"
          ><small>按时完成率</small><strong>{{ percent(q.onTimeRate) }}</strong
          ><span>{{ q.onTime }} 项按时完成 →</span></a
        ><a
          class="metric"
          routerLink="/bundles"
          [queryParams]="{ metric: 'late', from: from, to: to }"
          ><small>超时完成</small><strong>{{ q.late }}</strong
          ><span>不计入按时完成 →</span></a
        ><a
          class="metric"
          routerLink="/bundles"
          [queryParams]="{ metric: 'pending', from: from, to: to }"
          ><small>待临床审核</small><strong>{{ q.pendingReview }}</strong
          ><span>暂不进入分母 →</span></a
        >
      </div>
      <div class="workspace quality-visuals">
        <section class="card">
          <p class="eyebrow">EXECUTION DISTRIBUTION</p>
          <h2>适用任务执行分布</h2>
          <p class="muted small">
            同一 24h 窗口 · 单位：项 · 五类互斥，相加等于分母 {{ q.denominator }}
          </p>
          <div
            *ngIf="q.denominator; else noDen"
            class="bar-chart"
            role="img"
            [attr.aria-label]="
              '适用任务 ' +
              q.denominator +
              ' 项，按时完成 ' +
              q.onTime +
              ' 项，超时完成 ' +
              q.late +
              ' 项。完整数值见右侧统计表。'
            "
          >
            <div *ngFor="let b of bars" class="bar-row">
              <span>{{ b.label }}</span>
              <div class="bar-track">
                <div [style.width.%]="(b.value / max) * 100" [style.background]="b.color"></div>
              </div>
              <strong>{{ b.value }}</strong>
            </div>
            <div class="bar-axis">
              <span>0</span><span>{{ max }} 项</span>
            </div>
          </div>
          <ng-template #noDen
            ><div class="empty">
              暂无已审核适用任务<br /><small>分母为 0，不绘制完成率图</small>
            </div></ng-template
          >
          <div class="split-count">
            <div>
              <strong>{{ q.pendingReview }}</strong
              ><span>待临床审核 · 分母外</span>
            </div>
            <div>
              <strong>{{ q.excluded }}</strong
              ><span>不适用 / 禁忌 · 分母外</span>
            </div>
          </div>
        </section>
        <section class="card">
          <p class="eyebrow">RECONCILED COUNTS</p>
          <h2>统计明细</h2>
          <div class="tablewrap">
            <table class="quality-table">
              <thead>
                <tr>
                  <th>执行状态</th>
                  <th>任务数</th>
                  <th>占适用分母</th>
                </tr>
              </thead>
              <tbody>
                <tr *ngFor="let b of bars">
                  <td>
                    <span class="color-dot" [style.background]="b.color"></span
                    ><a
                      *ngIf="b.metric; else plain"
                      routerLink="/bundles"
                      [queryParams]="{ metric: b.metric, from: from, to: to }"
                      >{{ b.label }} →</a
                    ><ng-template #plain>{{ b.label }}</ng-template>
                  </td>
                  <td class="numeric">{{ b.value }}</td>
                  <td>{{ q.denominator ? percent(b.value / q.denominator) : '—' }}</td>
                </tr>
                <tr class="table-total">
                  <td>已审核适用合计</td>
                  <td>{{ q.denominator }}</td>
                  <td>{{ q.denominator ? '100%' : '—' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p class="muted small">
            可点击“按时完成 / 超时完成”查看同窗口任务，避免图表与明细口径不一致。
          </p>
        </section>
      </div>
      <section class="card">
        <h2>指标可追溯口径</h2>
        <p>{{ q.definition }}</p>
        <dl>
          <dt>全部任务</dt>
          <dd>{{ q.totalTasks }}</dd>
          <dt>未完成的适用任务</dt>
          <dd>{{ q.notCompleted }}</dd>
          <dt>数据缺失</dt>
          <dd>{{ q.dataMissing }}</dd>
          <dt>触发前完成</dt>
          <dd>{{ q.completedBefore }}</dd>
          <dt>经审核不适用 / 禁忌</dt>
          <dd>{{ q.excluded }}</dd>
        </dl>
        <a routerLink="/bundles" class="button">查看任务明细 →</a>
        <p class="muted small">统计卡片将携带相同时间窗与分子/分母口径进入任务明细。</p>
      </section></ng-container
    >`,
})
export class QualityPage implements OnInit {
  api = inject(Api);
  from = '';
  to = '';
  q: Quality | null = null;
  error = '';
  get bars() {
    const q = this.q!;
    return [
      { label: '按时完成', value: q.onTime, color: '#157a85', metric: 'onTime' },
      { label: '超时完成', value: q.late, color: '#c58732', metric: 'late' },
      { label: '未完成', value: q.notCompleted, color: '#587eae', metric: '' },
      { label: '执行信息缺失', value: q.dataMissing, color: '#b65e62', metric: '' },
      { label: '触发前完成', value: q.completedBefore, color: '#9a87b8', metric: '' },
    ];
  }
  get max() {
    return Math.max(1, ...this.bars.map((b) => b.value));
  }
  percent = percentage;
  ngOnInit() {
    void this.load();
  }
  async load() {
    this.error = '';
    try {
      const to = new Date();
      const from = new Date(to.getTime() - 86400000);
      this.from = from.toISOString();
      this.to = to.toISOString();
      this.q = await this.api.get<Quality>(
        '/quality/overview?from=' +
          encodeURIComponent(from.toISOString()) +
          '&to=' +
          encodeURIComponent(to.toISOString()),
      );
    } catch (e) {
      this.error = errorMessage(e);
    }
  }
}
