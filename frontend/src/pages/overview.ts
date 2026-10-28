import { CommonModule } from '@angular/common';
import { Component, inject, OnInit } from '@angular/core';
import { RouterLink } from '@angular/router';
import { text } from '../presentation';
import { Api, errorMessage, Page, Patient, Alert, Task } from '../api';
@Component({
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `<div class="pagehead">
      <div>
        <p class="eyebrow">SEPSIS INTELLIGENCE</p>
        <h1>脓毒症风险总览</h1>
        <p class="muted">优先处理待复核预警与任务。所有统计来自当前授权科室后端。</p>
      </div>
      <button (click)="load()">刷新总览</button>
    </div>
    <div class="notice">
      开发验证环境 · 全部为模拟患者 · 当前没有真实模型预测 · 不可用于临床决策
    </div>
    <p class="error" *ngIf="error">{{ error }}</p>
    <ng-container *ngIf="o && !error"
      ><div class="metrics">
        <a class="metric" routerLink="/patients"
          ><small>当前患者</small><strong>{{ o.patientCount }}</strong
          ><span>查看授权患者 →</span></a
        ><a class="metric" routerLink="/alerts" [queryParams]="{ status: 'pending' }"
          ><small>待审核预警</small><strong>{{ o.pendingAlerts }}</strong
          ><span>进入临床复核 →</span></a
        ><a class="metric" routerLink="/bundles" [queryParams]="{ metric: 'open' }"
          ><small>待记录执行证据</small><strong>{{ o.openTasks }}</strong
          ><span>进入任务工作台 →</span></a
        >
        <div class="metric">
          <small>24h 数据不全评估</small><strong>{{ o.incompleteAssessments24h }}</strong
          ><span>评估次数，不等于患者人数</span>
        </div>
      </div>
      <div class="workspace clinical-board">
        <section class="card">
          <div class="section-head">
            <div>
              <p class="eyebrow">BEDSIDE SNAPSHOT</p>
              <h2>患者观察表</h2>
            </div>
            <a routerLink="/patients">全部患者 →</a>
          </div>
          <p class="muted small">
            当前页 {{ patients.length }} / {{ patientTotal }} 位 · 非实时监护 · 缺失不按正常值填补
          </p>
          <div class="tablewrap">
            <table>
              <thead>
                <tr>
                  <th>床位 / 患者</th>
                  <th>心率<br />bpm</th>
                  <th>平均动脉压<br />mmHg</th>
                  <th>乳酸<br />mmol/L</th>
                  <th>最近筛查 · 非诊断</th>
                  <th>证据</th>
                </tr>
              </thead>
              <tbody>
                <tr *ngFor="let p of patients">
                  <td>
                    <span class="bed">{{ p.bed }}</span
                    ><strong class="patient-label">{{ p.label }}</strong>
                  </td>
                  <td *ngFor="let code of ['HR', 'MAP', 'LACTATE']">
                    <strong class="numeric">{{ p.vitals?.[code]?.value ?? '—' }}</strong
                    ><small>{{
                      p.vitals?.[code]
                        ? (p.vitals?.[code]?.eventTime | date: 'HH:mm')
                        : '缺失 / 过期'
                    }}</small>
                  </td>
                  <td>
                    <span
                      class="tag"
                      [class.danger]="
                        p.latestAssessment?.screeningStatus === 'hemodynamic_instability'
                      "
                      >{{
                        p.latestAssessment ? text(p.latestAssessment.screeningStatus) : '尚未筛查'
                      }}</span
                    >
                  </td>
                  <td><a class="button" [routerLink]="['/patients', p.id]">趋势 →</a></td>
                </tr>
              </tbody>
            </table>
            <div class="empty" *ngIf="!patients.length">当前没有授权患者</div>
          </div>
          <p class="muted small">
            数值下方为事件时间。所有当前快照均需通过单位、范围、质量与新鲜度校验。
          </p>
        </section>
        <section class="card work-queue">
          <p class="eyebrow">NEXT ACTION</p>
          <h2>待处理工作队列</h2>
          <div class="queue-heading">
            <strong>01 · 预警复核</strong
            ><a routerLink="/alerts" [queryParams]="{ status: 'pending' }">查看全部 →</a>
          </div>
          <a class="queue-item" routerLink="/alerts" *ngFor="let a of alerts"
            ><span class="tag danger">{{ text(a.severity) }}</span
            ><strong>{{ patientName(a.patientId) }}</strong
            ><small>{{ a.createdAt | date: 'MM-dd HH:mm' }} · 待医生复核</small></a
          >
          <div class="queue-empty" *ngIf="!alerts.length">
            暂无待复核预警<span>不代表患者无风险</span>
          </div>
          <div class="queue-heading">
            <strong>02 · 执行记录</strong
            ><a routerLink="/bundles" [queryParams]="{ metric: 'open' }">查看全部 →</a>
          </div>
          <a class="queue-item" routerLink="/bundles" *ngFor="let t of tasks"
            ><strong>{{ t.itemName }}</strong
            ><small>{{ patientName(t.patientId) }} · 截止 {{ t.dueAt | date: 'HH:mm' }}</small></a
          >
          <div class="queue-empty" *ngIf="!tasks.length">
            暂无待记录任务<span>已记录不等于临床目标达成</span>
          </div>
          <p class="muted small">每类最多显示 3 条；数量以顶部汇总为准。规则和任务均为模拟演示。</p>
        </section>
      </div>
      <div class="safety-strip">
        <strong>模型未启用</strong
        ><span>不显示未来风险百分比 · 筛查不等于诊断 · 不自动下达医嘱</span
        ><a routerLink="/models">核查运行状态 →</a>
      </div></ng-container
    >`,
})
export class Overview implements OnInit {
  api = inject(Api);
  o: {
    patientCount: number;
    pendingAlerts: number;
    openTasks: number;
    incompleteAssessments24h: number;
    updatedAt: string;
  } | null = null;
  error = '';
  text = text;
  patients: Patient[] = [];
  patientTotal = 0;
  alerts: Alert[] = [];
  tasks: Task[] = [];
  patientName(id: string) {
    return this.patients.find((p) => p.id === id)?.label || id;
  }
  ngOnInit() {
    void this.load();
  }
  async load() {
    this.error = '';
    try {
      const [o, p, a, t] = await Promise.all([
        this.api.get<NonNullable<Overview['o']>>('/overview'),
        this.api.get<Page<Patient>>('/patients?size=20'),
        this.api.get<Page<Alert>>('/alerts?status=pending&size=3'),
        this.api.get<Page<Task>>('/bundles/tasks?metric=open&size=3'),
      ]);
      this.o = o;
      this.patients = p.items;
      this.patientTotal = p.total;
      this.alerts = a.items;
      this.tasks = t.items;
    } catch (e) {
      this.error = errorMessage(e);
    }
  }
}
