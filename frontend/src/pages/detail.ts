import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output, inject, OnInit } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Api, Assessment, errorMessage, Observation, Page, Patient } from '../api';
import { ClinicalTrends } from '../clinical-trends';
import { label } from '../trends';
import { riskText, text } from '../presentation';
@Component({
  selector: 'patient-detail',
  standalone: true,
  imports: [CommonModule, RouterLink, ClinicalTrends],
  template: `<a *ngIf="!embedded" class="back" routerLink="/patients">← 患者列表</a>
    <div class="pagehead" *ngIf="!embedded">
      <div>
        <p class="eyebrow">PATIENT EVIDENCE WORKSPACE</p>
        <h1>
          {{ patient?.label || '患者详情' }}
          <span class="tag" *ngIf="patient">{{ patient.bed }} 床 · 模拟</span>
        </h1>
        <p class="muted">{{ patient?.encounterId }} · 时间显示为本机时区，API 使用 UTC</p>
      </div>
      <button class="primary" *ngIf="api.has('DOCTOR')" (click)="assess()" [disabled]="busy">
        {{ busy ? '评估处理中…' : '执行规则筛查' }}
      </button>
    </div>
    <div class="notice" *ngIf="!embedded">仅模拟演示 · 不能代替临床诊断 · 不自动开立医嘱 · 模型不可用</div>
    <p class="error" role="alert" *ngIf="error">{{ error }}</p>
    <section
      class="assessment-ribbon"
      *ngIf="!error && history[0] as a"
      aria-label="最近一次筛查摘要"
    >
      <div>
        <small>最近筛查 · 非诊断</small
        ><strong [class.attention-text]="a.screeningStatus === 'hemodynamic_instability'">{{
          text(a.screeningStatus)
        }}</strong>
      </div>
      <div>
        <small>qSOFA · 非概率</small
        ><strong
          >{{ a.ruleScore ?? '无法计算' }} <span *ngIf="a.ruleScore !== null">/ 3</span></strong
        >
      </div>
      <div>
        <small>筛查时关键数据</small
        ><strong>{{
          a.missingFeatures.length
            ? a.missingFeatures.length + ' 项缺失 / 过期 / 无效'
            : '满足演示筛查输入'
        }}</strong>
      </div>
      <div>
        <small>{{ a.assessmentTime | date: 'MM-dd HH:mm:ss' }}</small
        ><a
          class="button"
          [routerLink]="embedded ? null : '/alerts'"
          (click)="embedded && openAlerts.emit()"
          (keydown.enter)="embedded && openAlerts.emit()"
          [attr.tabindex]="embedded ? 0 : null"
          [attr.role]="embedded ? 'button' : null"
          >进入预警复核 →</a
        >
      </div>
    </section>
    <clinical-trends
      *ngIf="!error && patient && (!embedded || panel === 'trends')"
      [items]="observations"
      [asOf]="asOf"
      [embedded]="embedded"
    />
    <div class="workspace" *ngIf="!error && (!embedded || panel === 'evidence')">
      <section class="card">
        <div class="section-head">
          <h2>临床数据 <span class="muted small" *ngIf="!embedded">最近窗口</span></h2>
          <button (click)="showAll = !showAll">{{ showAll ? '仅看最新' : '原始记录' }}</button>
        </div>
        <p class="muted small" *ngIf="!embedded">
          {{ observations.length }} 条后端原始记录 · 默认按指标显示最新记录；展开后按时间倒序。
        </p>
        <div class="tablewrap">
          <table>
            <thead>
              <tr>
                <th>数据项</th>
                <th>值 / 记录</th>
                <th>事件时间</th>
                <th>来源</th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let o of tableRows">
                <td>
                  {{ label(o.parameterCode) }}<small>{{ o.sourceParameterCode || o.parameterCode }}</small><small class="missing" *ngIf="o.clinicalUse === 'context_only'">仅供核对 · 住院关联 / 结果状态未确认</small>
                </td>
                <td>{{ o.text || o.value }} {{ o.unit }}</td>
                <td>{{ o.eventTime | date: 'MM-dd HH:mm' }}<small>{{ o.timeBasis === 'reported' ? '报告 / 审核；采样未知' : '观测时间' }}</small></td>
                <td>
                  {{ o.sourceSystem }}<small>{{ o.sourceRecordId }}</small>
                </td>
              </tr>
            </tbody>
          </table>
          <div class="empty" *ngIf="!observations.length">没有可用观测数据，不按正常值填补</div>
        </div>
      </section>
      <section class="card">
        <h2>
          {{
            assessment && history[0] && assessment.id !== history[0].id
              ? '所选历史筛查'
              : '最近一次筛查'
          }}
        </h2>
        <button
          *ngIf="assessment && history[0] && assessment.id !== history[0].id"
          (click)="assessment = history[0]"
        >
          回到最近一次
        </button>
        <ng-container *ngIf="assessment; else noAssessment"
          ><p class="muted small">
            {{ assessment.assessmentTime | date: 'yyyy-MM-dd HH:mm:ss' }} ·
            {{ assessment.ruleVersion }}
          </p>
          <dl>
            <dt>感染证据</dt>
            <dd>{{ text(assessment.infectionVerdict) }}</dd>
            <dt>当前筛查</dt>
            <dd>{{ text(assessment.screeningStatus) }}</dd>
            <dt>休克筛查</dt>
            <dd>{{ text(assessment.shockScreeningStatus) }}</dd>
            <dt>qSOFA 规则分数</dt>
            <dd>{{ assessment.ruleScore ?? '无法计算' }} / 3（非概率）</dd>
            <dt>完整 SOFA</dt>
            <dd>未实现，不将缺失项视为正常</dd>
          </dl>
          <div class="missing" *ngIf="assessment.missingFeatures.length">
            缺失 / 过期 / 无效：{{ assessment.missingFeatures.join('、') }}
          </div>
          <div class="prediction" *ngFor="let p of assessment.predictions">
            <strong>未来 {{ p.horizonHours }} 小时</strong
            ><span>{{ riskText(p.predictionSource, p.riskValue) }}</span>
          </div>
          <a
            [routerLink]="embedded ? null : '/alerts'"
            (click)="embedded && openAlerts.emit()"
            (keydown.enter)="embedded && openAlerts.emit()"
            [attr.tabindex]="embedded ? 0 : null"
            [attr.role]="embedded ? 'button' : null"
            class="button"
            >进入预警复核 →</a
          ></ng-container
        ><ng-template #noAssessment
          ><div class="empty">
            尚未执行评估。缺乏数据或模型时，系统不会强行产生概率。
          </div></ng-template
        >
      </section>
    </div>
    <section class="card" *ngIf="!embedded || panel === 'evidence'">
      <h2>历次风险评估</h2>
      <p class="muted small">查看评估时间、来源及缺失项，证据快照存储于后端。</p>
      <button class="historyitem" *ngFor="let a of history" (click)="assessment = a">
        {{ a.assessmentTime | date: 'MM-dd HH:mm:ss' }} · {{ text(a.screeningStatus) }} ·
        {{ text(a.infectionVerdict) }}
      </button>
      <div class="empty" *ngIf="!history.length">暂无历史评估</div>
    </section>`,
})
export class Detail implements OnInit {
  @Input() embedded = false;
  @Input() patientId = '';
  @Input() panel: 'trends' | 'evidence' = 'trends';
  @Output() openAlerts = new EventEmitter<void>();
  api = inject(Api);
  route = inject(ActivatedRoute);
  id = '';
  patient: Patient | null = null;
  observations: Observation[] = [];
  assessment: Assessment | null = null;
  history: Assessment[] = [];
  asOf = '';
  showAll = false;
  label = label;
  get tableRows() {
    const sorted = [...this.observations].sort(
      (a, b) => Date.parse(b.eventTime) - Date.parse(a.eventTime),
    );
    return this.showAll
      ? sorted
      : sorted.filter((o, i, a) => a.findIndex((x) => x.parameterCode === o.parameterCode) === i);
  }
  busy = false;
  error = '';
  text = text;
  riskText = riskText;
  ngOnInit() {
    this.id = this.patientId || this.route.snapshot.paramMap.get('id') || '';
    void this.load();
  }
  async load() {
    this.error = '';
    try {
      const [p, o, a] = await Promise.all([
        this.api.get<Patient>('/patients/' + this.id),
        this.api.get<{ items: Observation[]; asOf: string }>(
          '/patients/' + this.id + '/clinical-data?hours=' + (this.embedded ? 168 : 24),
        ),
        this.api.get<Page<Assessment>>('/patients/' + this.id + '/risk-assessments'),
      ]);
      this.patient = p;
      this.observations = o.items;
      this.asOf = o.asOf;
      this.history = a.items;
      this.assessment = a.items[0] || null;
    } catch (e) {
      this.error = errorMessage(e);
    }
  }
  async assess() {
    this.busy = true;
    this.error = '';
    try {
      await this.api.post('/patients/' + this.id + '/risk-assessments', {
        asOf: new Date().toISOString(),
      });
      await this.load();
    } catch (e) {
      this.error = errorMessage(e);
    } finally {
      this.busy = false;
    }
  }
}
