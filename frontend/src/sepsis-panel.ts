import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output, OnChanges, OnDestroy, inject } from '@angular/core';
import { Api, Alert, Page, Task, Observation, errorMessage } from './api';
import { UiIcon } from './ui-icon';
import { ClinicalTrends } from './clinical-trends';
import { ClinicalReview, Evidence, featureLabel, yesNoUnknown, matchesPatient, reviewState, reviewTitle } from './clinical-review';

@Component({
  selector: 'sepsis-panel', standalone: true, imports: [CommonModule, ClinicalTrends, UiIcon],
  template: `<section class="clinical-review" aria-label="患者临床概览" [attr.aria-busy]="loading">
    <header class="review-toolbar">
      <h1><ui-icon name="activity"/>临床概览 <span class="sample-badge" *ngIf="data?.simulated">模拟数据</span></h1>
      <div class="review-refresh"><span *ngIf="updatedAt"><ui-icon name="clock"/>{{ updatedAt | date:'MM-dd HH:mm' }}</span>
        <button (click)="load()" [disabled]="loading"><ui-icon name="refresh"/>{{ loading ? '读取中…' : '刷新数据' }}</button></div>
    </header>
    <div class="review-state" role="alert" *ngIf="error || (data && !data.available)"><ui-icon name="info"/><strong>当前无法评估</strong><span>{{ error || data?.message || '数据未取得，请重试' }}</span></div>
    <div class="review-loading" *ngIf="loading && !data" role="status"><span class="spinner"></span>正在读取当前患者…</div>
    <section class="review-summary" *ngIf="!loading || data" aria-label="当前患者状态">
      <div class="review-summary-main" [attr.data-state]="state(data)"><span class="status-symbol"><ui-icon name="activity"/></span><div><span class="status-caption">当前核对</span><strong>{{ title(data) }}</strong><small>仅供核对，不代表诊断</small></div></div>
      <button class="review-stat" (click)="openEvidence.emit()"><ui-icon name="file"/><span>缺项 / 待确认<strong>{{ data?.available ? data!.missingFeatures.length : '—' }}<small> 项</small></strong></span><ui-icon name="arrow"/></button>
      <button class="review-stat" (click)="openAlerts.emit()"><ui-icon name="bell"/><span>待复核预警<strong>{{ alertsError ? '—' : (alertTotal ?? '—') }}<small> 条</small></strong></span><ui-icon name="arrow"/></button>
      <button class="review-stat" (click)="openTasks.emit()"><ui-icon name="clipboard"/><span>待审核任务<strong>{{ tasksError ? '—' : (taskTotal ?? '—') }}<small> 项</small></strong></span><ui-icon name="arrow"/></button>
    </section>
    <div class="review-columns">
      <section class="review-trends" aria-label="观测趋势">
        <p class="review-inline-error" role="alert" *ngIf="observationsError"><ui-icon name="info"/>{{ observationsError }} <button (click)="load()">重试</button></p>
        <clinical-trends [items]="observations" [asOf]="asOf" [embedded]="true" [availableHours]="24" [compact]="true" [busy]="loading" (openRecords)="openEvidence.emit()" (openHistory)="openTrends.emit()"/>
    <section class="review-card review-checklist" *ngIf="data?.available"><div class="review-card-title"><h2><ui-icon name="file"/>诊疗记录核对 <span class="neutral-tag">非达标判定</span></h2><button (click)="openTasks.emit()">执行与审核记录 <ui-icon name="arrow"/></button></div>
      <div class="review-table-wrap"><table><thead><tr><th>核对项目</th><th>记录状态</th><th>记录时间</th><th>核对依据</th></tr></thead><tbody><tr *ngFor="let it of data!.checklist"><th scope="row">{{ it.name }}</th><td data-label="记录状态"><span class="review-record-state" [class.has-record]="it.status==='recorded'"><i></i>{{ it.status==='recorded' ? '已有结果' : '待核对' }}</span></td><td data-label="记录时间">{{ it.eventTime ? (it.eventTime | date:'MM-dd HH:mm') : '—' }}</td><td data-label="核对依据">{{ it.note }}</td></tr></tbody></table></div>
    </section>
      </section>
      <aside class="review-side" aria-label="需核对事项">
        <section class="review-card review-inbox"><div class="review-card-title"><h2><ui-icon name="clipboard"/>待处理</h2><span class="subtle-text">当前患者</span></div>
          <div class="review-next-row"><ui-icon name="bell"/><div><strong>预警复核</strong><p>{{ alertsError ? '读取失败，请重试' : alertTotal ? alertTotal + ' 条待复核' : '暂无待复核记录' }}</p></div><button (click)="openAlerts.emit()">查看</button></div>
          <div class="review-next-row"><ui-icon name="clipboard"/><div><strong>执行与审核</strong><p>{{ tasksError ? '读取失败，请重试' : taskTotal ? taskTotal + ' 项待审核' : '暂无待审核记录' }}</p></div><button (click)="openTasks.emit()">查看</button></div>
        </section>
        <section class="review-card review-abnormal"><div class="review-card-title"><h2><ui-icon name="activity"/>异常核对</h2><button (click)="openEvidence.emit()">全部证据</button></div>
          <ul class="review-findings" *ngIf="data?.available && data!.findings.length"><li *ngFor="let f of data!.findings"><div><strong>{{ f.label }}</strong><b>{{ f.value }} <small>{{ f.unit }}</small></b></div><button class="review-evidence-link" (click)="selected=f">{{ f.eventTime | date:'MM-dd HH:mm' }} · 查看来源 <ui-icon name="arrow"/></button><details class="inline-rule"><summary>核对依据</summary><p>{{ f.reason }}</p></details></li></ul>
          <div class="review-empty" *ngIf="!data?.available || !data!.findings.length"><ui-icon name="empty"/><strong>{{ data?.available ? '暂无可核对的异常' : '尚未取得有效数据' }}</strong><span>缺项不能排除风险</span></div>
          <div class="review-missing" *ngIf="data?.available && data!.missingFeatures.length"><strong>缺项 / 待确认</strong><span>{{ missingText }}</span></div>
          <dl class="review-facts" *ngIf="data?.available"><dt>qSOFA</dt><dd>{{ data!.qsofa ?? '无法计算' }}<span *ngIf="data!.qsofa !== null"> / 3</span><small>仅筛查</small></dd><dt>升压药</dt><dd>{{ triState(data!.vasopressorActive) }}</dd></dl>
          <details class="review-pressure" *ngIf="data?.pressureEvidence?.length"><summary>血压通道待选 <span>{{ data!.pressureEvidence!.length }}</span></summary><button *ngFor="let e of data!.pressureEvidence" (click)="selected=e">{{ featureName(e.code) }}：{{ e.value }} {{ e.unit }} · {{ e.eventTime | date:'HH:mm' }}</button></details>
        </section>
      </aside>
    </div>
    <section class="review-card review-context" *ngIf="data?.contextEvidence?.length" aria-label="住院关联未确认的候选报告"><div class="review-card-title"><h2><ui-icon name="file"/>候选检验报告 <span class="caution-tag">未确认住院归属</span></h2><button (click)="openEvidence.emit()">原始记录</button></div><p class="context-warning">不参与当前判断 · 以下是报告时间，采样时间未知</p>
      <div class="review-table-wrap"><table><thead><tr><th>项目</th><th>报告值</th><th>报告 / 审核时间</th><th>来源</th></tr></thead><tbody><tr *ngFor="let e of data!.contextEvidence"><td>{{ featureName(e.code) }}</td><td>{{ e.value }} {{ e.unit || '单位未知' }}</td><td>{{ e.eventTime | date:'MM-dd HH:mm' }}</td><td><button (click)="selected=e">查看来源</button></td></tr></tbody></table></div>
    </section>
    <details class="review-source-warnings" aria-label="数据说明"><summary><ui-icon name="info"/>数据说明 <span *ngIf="data?.sourceWarnings?.length">{{ data!.sourceWarnings!.length }} 项限制</span><span class="rule-summary" *ngIf="data?.pressureSelectionRequired">血压通道待选</span></summary><div class="data-notes"><p *ngFor="let warning of data?.sourceWarnings">{{ warning }}</p><p *ngIf="data?.infectionClue">{{ data!.infectionClue }}</p><p>{{ data?.taskNote || '只读核对，不自动开立医嘱。' }}</p><p>空列表不代表无风险或治疗完成。各图纵轴独立，缺测不补值。</p><p>预测模型：{{ data?.model?.label || '未配置' }}；摘要服务：{{ data?.ai?.label || '未配置' }}。</p></div></details>
    <section class="review-evidence-detail" *ngIf="selected" aria-label="所选异常的来源证据"><div class="review-card-title"><h2><ui-icon name="file"/>{{ selected.label || featureName(selected.code) }}</h2><button (click)="selected=null">关闭</button></div><div class="evidence-reading">{{ selected.value }}<small>{{ selected.unit || '单位未知' }}</small></div><dl><dt>{{ selected.timeBasis==='reported' ? '报告 / 审核时间' : '观测事件时间' }}</dt><dd>{{ selected.eventTime | date:'yyyy-MM-dd HH:mm:ss' }}</dd><dt>来源系统</dt><dd>{{ selected.sourceSystem || '未标注' }}</dd><dt>来源记录</dt><dd>{{ selected.sourceRecordId || '未标注' }}</dd><dt *ngIf="selected.sourceParameterCode">源指标</dt><dd *ngIf="selected.sourceParameterCode">{{ selected.sourceParameterCode }}</dd></dl><p class="context-warning" *ngIf="selected.qualityNote">{{ selected.qualityNote }}</p></section>
  </section>`,

})
export class SepsisPanel implements OnChanges, OnDestroy {
  @Input() patientId = '';
  @Input() encounterId = '';
  @Output() openTrends = new EventEmitter<void>();
  @Output() openEvidence = new EventEmitter<void>();
  @Output() openAlerts = new EventEmitter<void>();
  @Output() openTasks = new EventEmitter<void>();
  api = inject(Api);
  data: ClinicalReview | null = null;
  observations: Observation[] = [];
  alertTotal: number | null = null;
  taskTotal: number | null = null;
  loading = false; error = ''; observationsError = ''; alertsError = false; tasksError = false;
  asOf = ''; updatedAt = ''; selected: Evidence | null = null;
  private generation = 0;
  featureName = featureLabel;
  state = reviewState; title = reviewTitle; triState = yesNoUnknown;
  get missingText() { return this.data?.missingFeatures.map(featureLabel).join('、') || ''; }
  ngOnChanges() { this.data=null; this.observations=[]; this.selected=null; void this.load(); }
  ngOnDestroy() { ++this.generation; }
  async load() {
    const generation=++this.generation; if(!this.patientId) return;
    this.loading=true; this.error=''; this.observationsError=''; this.selected=null;
    // Clear stale counts/data before refresh. A failed request must never look like a zero count.
    this.data=null; this.observations=[]; this.alertTotal=null; this.taskTotal=null;
    const base='/patients/'+encodeURIComponent(this.patientId);
    const results=await Promise.allSettled([
      this.api.get<ClinicalReview>(base+'/sepsis-assessment'),
      this.api.get<{items: Observation[]; asOf: string}>(base+'/clinical-data?hours=24'),
      this.api.get<Page<Alert>>('/alerts?status=pending&size=1'),
      this.api.get<Page<Task>>('/bundles/tasks?metric=pending&patientId='+encodeURIComponent(this.patientId)+'&size=1'),
    ] as const);
    if(generation!==this.generation)return;
    const [review, observations, alerts, tasks]=results;
    if(review.status==='fulfilled' && review.value.schemaVersion===2 && matchesPatient(review.value,this.patientId,this.encounterId)) this.data=review.value;
    else this.error=review.status==='rejected'?errorMessage(review.reason):'患者上下文或接口版本不一致，已停止展示评估。';
    if(observations.status==='fulfilled'){this.observations=observations.value.items;this.asOf=observations.value.asOf;}
    else {this.observationsError='观测数据读取失败：'+errorMessage(observations.reason);this.asOf=new Date().toISOString();}
    this.alertsError=alerts.status==='rejected'; this.tasksError=tasks.status==='rejected';
    if(alerts.status==='fulfilled')this.alertTotal=alerts.value.total;
    if(tasks.status==='fulfilled')this.taskTotal=tasks.value.total;
    this.updatedAt=new Date().toISOString();this.loading=false;
  }
}
