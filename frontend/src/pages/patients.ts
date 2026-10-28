import { CommonModule } from '@angular/common';
import { Component, inject, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { text } from '../presentation';
import { Api, errorMessage, Page, Patient } from '../api';
@Component({
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  template: `<div class="pagehead">
      <div>
        <p class="eyebrow">PATIENT WORKLIST</p>
        <h1>患者风险列表</h1>
        <p class="muted">先核查证据，再进行临床复核。规则筛查不等于诊断。</p>
      </div>
      <button (click)="load()" [disabled]="busy">刷新列表</button>
    </div>
    <div class="notice">
      模拟数据环境 · 当前没有可核验预测模型，不显示 4h／12h／24h 风险百分比。
    </div>
    <section class="card">
      <p class="muted small">
        当前有效观测快照（非实时监护） · 点击“查看证据”查看多指标曲线、缺测与来源。
      </p>
      <form class="toolbar" (ngSubmit)="page = 0; load()">
        <label class="search-label"
          >患者或床位<input
            [(ngModel)]="search"
            name="search"
            placeholder="搜索模拟患者、床位" /></label
        ><button class="primary" [disabled]="busy">查询</button
        ><span class="muted" *ngIf="!error">共 {{ total }} 位 · {{ api.user }}</span>
      </form>
      <p class="error" role="alert" *ngIf="error">{{ error }}</p>
      <p *ngIf="busy" aria-live="polite">正在加载患者…</p>
      <div class="tablewrap" *ngIf="!busy && !error">
        <p class="tablehint">左右滑动查看指标 · 右侧可直接进入证据 →</p>
        <table class="patient-worklist">
          <thead>
            <tr>
              <th>床位</th>
              <th>患者</th>
              <th>心率 <small>bpm</small></th>
              <th>平均动脉压 <small>mmHg</small></th>
              <th>乳酸 <small>mmol/L</small></th>
              <th>最近筛查 · 非诊断</th>
              <th>工作台</th>
            </tr>
          </thead>
          <tbody>
            <tr *ngFor="let p of patients">
              <td>
                <span class="bed">{{ p.bed }}</span>
              </td>
              <td>
                <strong>{{ p.label }}</strong
                ><small>{{ p.id }}</small>
              </td>
              <td *ngFor="let code of ['HR', 'MAP', 'LACTATE']">
                <strong class="numeric">{{ p.vitals?.[code]?.value ?? '—' }}</strong
                ><small>{{
                  p.vitals?.[code] ? (p.vitals?.[code]?.eventTime | date: 'HH:mm') : '缺失 / 过期'
                }}</small>
              </td>
              <td>
                <span
                  class="tag"
                  [class.danger]="p.latestAssessment?.screeningStatus === 'hemodynamic_instability'"
                  >{{
                    p.latestAssessment ? text(p.latestAssessment.screeningStatus) : '尚未筛查'
                  }}</span
                ><small>{{ p.latestAssessment?.assessmentTime | date: 'MM-dd HH:mm' }} · 模拟</small
                ><small *ngIf="p.summaryLimitReached">摘要达到读取上限，需核查原始记录</small>
              </td>
              <td><a class="button" [routerLink]="['/patients', p.id]">查看证据 →</a></td>
            </tr>
          </tbody>
        </table>
        <div class="empty" *ngIf="patients.length === 0">未找到符合条件的患者</div>
      </div>
      <footer class="pager" *ngIf="!error">
        <button (click)="page = page - 1; load()" [disabled]="page === 0 || busy">上一页</button
        ><span>第 {{ page + 1 }} 页</span
        ><button (click)="page = page + 1; load()" [disabled]="(page + 1) * 20 >= total || busy">
          下一页
        </button>
      </footer>
    </section>`,
})
export class Patients implements OnInit {
  api = inject(Api);
  text = text;
  patients: Patient[] = [];
  total = 0;
  search = '';
  page = 0;
  busy = false;
  error = '';
  ngOnInit() {
    void this.load();
  }
  async load() {
    this.busy = true;
    this.error = '';
    try {
      const p = await this.api.get<Page<Patient>>(
        '/patients?search=' + encodeURIComponent(this.search) + '&page=' + this.page,
      );
      this.patients = p.items;
      this.total = p.total;
    } catch (e) {
      this.error = errorMessage(e);
    } finally {
      this.busy = false;
    }
  }
}
