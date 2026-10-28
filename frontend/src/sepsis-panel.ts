import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output, OnChanges, OnDestroy, inject } from '@angular/core';
import { Api, Alert, Page, Task, Observation, errorMessage } from './api';
import { ClinicalTrends } from './clinical-trends';
import { ClinicalReview, Evidence, featureLabel, yesNoUnknown, matchesPatient, reviewState, reviewTitle } from './clinical-review';

@Component({
  selector: 'sepsis-panel', standalone: true, imports: [CommonModule, ClinicalTrends],
  template: `<section class="clinical-review" aria-label="患者临床概览" [attr.aria-busy]="loading">
    <header class="review-toolbar">
      <div><h1>临床概览</h1><p>近期观测与待办核对，不代替临床判断</p></div>
      <div class="review-refresh"><span *ngIf="updatedAt">读取于 {{ updatedAt | date:'MM-dd HH:mm:ss' }}</span>
        <button (click)="load()" [disabled]="loading">{{ loading ? '读取中…' : '刷新数据' }}</button></div>
    </header>
    <p class="review-simulation" *ngIf="data?.simulated">模拟患者 · 以下记录仅用于软件验证</p>
    <div class="review-state" role="status" *ngIf="error || (data && !data.available)">
      <strong>当前无法评估</strong><span>{{ error || data?.message || '临床数据未取得，请刷新重试。' }}</span>
      <span>未取得数据不代表正常。</span>
    </div>
    <p class="review-loading" *ngIf="loading && !data">正在读取当前患者的观测与工作记录…</p>
    <section class="review-summary" *ngIf="!loading || data" aria-label="当前患者状态">
      <div class="review-summary-main" [attr.data-state]="state(data)"><span>观测核对</span><strong>{{ title(data) }}</strong>
        <small>{{ !data?.available ? '数据不足时不显示风险等级或分数' : data!.missingFeatures.length ? '缺失或过期记录不按正常值计入' : '需结合病史、用药和床旁情况复核' }}</small></div>
      <button class="review-stat" (click)="openEvidence.emit()"><span>关键数据缺项</span>
        <strong>{{ data?.available ? data!.missingFeatures.length : '—' }}<small *ngIf="data?.available"> 项</small></strong><small>查看缺失 / 过期记录 ›</small></button>
      <button class="review-stat" (click)="openAlerts.emit()"><span>待复核预警</span><strong>{{ alertsError ? '—' : (alertTotal ?? '—') }}<small *ngIf="!alertsError"> 条</small></strong><small>{{ alertsError ? '读取失败，可进入重试' : '进入预警处置 ›' }}</small></button>
      <button class="review-stat" (click)="openTasks.emit()"><span>待审核任务</span><strong>{{ tasksError ? '—' : (taskTotal ?? '—') }}<small *ngIf="!tasksError"> 项</small></strong><small>{{ tasksError ? '读取失败，可进入重试' : '查看执行与审核 ›' }}</small></button>
    </section>

    <div class="review-columns">
      <section class="review-trends" aria-label="观测趋势">
        <p class="review-inline-error" role="alert" *ngIf="observationsError">{{ observationsError }} <button (click)="load()">重试</button></p>
        <clinical-trends [items]="observations" [asOf]="asOf" [embedded]="true" [availableHours]="24" />
        <button class="review-empty-records" *ngIf="!loading && !observations.length" (click)="openEvidence.emit()">核对原始记录</button>
      </section>
      <aside class="review-side" aria-label="需核对事项">
        <section class="review-card">
          <div class="review-card-title"><h2>需核对的异常</h2><button (click)="openEvidence.emit()">全部证据</button></div>
          <p class="review-help">使用近期有效记录；不将不同时间的历史极值拼成当前状态。</p>
          <ul class="review-findings" *ngIf="data?.available && data!.findings.length">
            <li *ngFor="let f of data!.findings"><div><strong>{{ f.label }}</strong><b>{{ f.value }} <small>{{ f.unit }}</small></b></div>
              <p>{{ f.reason }}</p><button class="review-evidence-link" (click)="selected = f">{{ f.eventTime | date:'MM-dd HH:mm' }} · 查看来源 ›</button></li>
          </ul>
          <p class="review-empty" *ngIf="!data?.available">数据尚不可用于核对，不作正常判断。</p>
          <p class="review-empty" *ngIf="data?.available && !data!.findings.length">有效记录中暂未见本次规则的阈值异常，不代表已排除疾病。</p>
          <div class="review-missing" *ngIf="data?.available && data!.missingFeatures.length"><strong>缺失 / 过期</strong><span>{{ missingText }}</span></div>
          <dl class="review-facts" *ngIf="data?.available"><dt>qSOFA</dt><dd>{{ data!.qsofa ?? '无法计算' }}<span *ngIf="data!.qsofa !== null"> / 3</span> · 非诊断</dd><dt>升压药状态</dt><dd>{{ triState(data!.vasopressorActive) }}</dd></dl>
          <p class="review-help" *ngIf="data?.available">{{ data!.infectionClue }}</p>
        </section>
        <section class="review-card">
          <div class="review-card-title"><h2>当前待办</h2></div>
          <div class="review-next-row"><div><strong>预警复核</strong><p>{{ alertsError ? '暂未取得预警记录' : alertTotal ? alertTotal + ' 条待复核' : '暂无待复核记录' }}</p></div><button (click)="openAlerts.emit()">查看</button></div>
          <div class="review-next-row"><div><strong>执行与审核</strong><p>{{ tasksError ? '暂未取得任务记录' : taskTotal ? taskTotal + ' 项待审核' : '暂无待审核记录' }}</p></div><button (click)="openTasks.emit()">查看</button></div>
          <p class="review-help">这里显示已落库的工作记录；空列表不表示患者无风险，也不代表治疗已完成。</p>
        </section>
        <section class="review-service-status"><span>预测模型：{{ data?.model?.label || '未配置预测模型' }}</span><span>摘要服务：{{ data?.ai?.label || '未配置摘要服务' }}</span><p>未配置时不生成概率或摘要，不影响查看原始记录。</p></section>
      </aside>
    </div>

    <section class="review-card review-checklist" *ngIf="data?.available">
      <div class="review-card-title"><div><h2>诊疗记录核对</h2><p class="review-help">核对是否有记录，不自动判断治疗达标或替代医嘱。</p></div><button (click)="openTasks.emit()">执行与审核记录</button></div>
      <div class="review-table-wrap"><table><thead><tr><th scope="col">核对项目</th><th scope="col">记录状态</th><th scope="col">事件时间</th><th scope="col">依据 / 待补充内容</th></tr></thead>
        <tbody><tr *ngFor="let it of data!.checklist"><th scope="row">{{ it.name }}</th><td data-label="记录状态"><span class="review-record-state" [class.has-record]="it.status === 'recorded'">{{ it.status === 'recorded' ? '已有结果' : '待核对' }}</span></td><td data-label="事件时间">{{ it.eventTime ? (it.eventTime | date:'MM-dd HH:mm') : '—' }}</td><td data-label="核对内容">{{ it.note }}</td></tr></tbody></table></div>
      <p class="review-help review-table-note">{{ data!.taskNote }}</p>
    </section>
    <section class="review-evidence-detail" *ngIf="selected" aria-label="所选异常的来源证据">
      <div class="review-card-title"><h2>{{ selected.label }} · 来源证据</h2><button (click)="selected = null">关闭</button></div>
      <dl><dt>观测值</dt><dd>{{ selected.value }} {{ selected.unit }}</dd><dt>事件时间</dt><dd>{{ selected.eventTime | date:'yyyy-MM-dd HH:mm:ss' }}</dd><dt>来源系统</dt><dd>{{ selected.sourceSystem || '未标注' }}</dd><dt>来源记录</dt><dd>{{ selected.sourceRecordId || '未标注' }}</dd></dl>
    </section>
  </section>`,
})
export class SepsisPanel implements OnChanges, OnDestroy {
  @Input() patientId = '';
  @Input() encounterId = '';
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
