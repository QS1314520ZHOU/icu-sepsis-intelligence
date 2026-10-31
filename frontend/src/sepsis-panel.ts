import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output, OnChanges, OnDestroy, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, Alert, Page, Task, Observation, errorMessage } from './api';
import { UiIcon } from './ui-icon';
import { ClinicalTrends } from './clinical-trends';
import { ClinicalReview, Evidence, featureLabel, matchesPatient, sourceLabel } from './clinical-review';
import { Level, ago, baseCode, direction, hoursSince, level, levelRank, reference } from './clinical-flags';
import { Measure, measures, pointsFor } from './trends';
import { Icu05Card, Icu05Result } from './icu05-card';
import { AiAssistCard } from './ai-assist-card';
import { computeSofa, SofaKind, SofaManualInput, SofaResult, CompKey } from './sofa';

export interface SepsisStatus {
  patientId: string; level: Level | 'unknown'; summary: string;
  pendingAlerts: number | null; pendingTasks: number | null;
}
interface Tile {
  code: string; label: string; unit: string; value: number | null; time: string | null; level: Level; word: string;
  delta: number | null; spark: string; sub: string; subTip?: string; evidence: Evidence | null; candidate: Evidence | null;
}
interface BundleRow { key: string; name: string; state: 'done' | 'warn' | 'none' | 'info'; text: string; time: string | null; hint: string; tip: string; }
interface OrganRow { system: string; item: string; value: string; level: Level; note: string; evidence: Evidence | null; }

const shortName: Record<string, string> = { MAP: 'MAP', MAP_IBP: 'MAP(有创)', MAP_NIBP: 'MAP(无创)', LACTATE: '乳酸', RR: '呼吸', GCS: 'GCS', HR: '心率', TEMP: '体温', SPO2: 'SpO₂', SBP: '收缩压', SBP_IBP: '收缩压(有创)', SBP_NIBP: '收缩压(无创)' };
const tileName: Record<string, string> = { MAP: '平均动脉压', MAP_IBP: '平均动脉压 · 有创', MAP_NIBP: '平均动脉压 · 无创', LACTATE: '乳酸', RR: '呼吸频率', GCS: 'GCS', HR: '心率', TEMP: '体温', SPO2: 'SpO₂' };
/** The trend fold remembers the clinician's last choice (folded by default) instead of re-collapsing on every patient. */
const TRENDS_KEY = 'icu-sepsis.trendsOpen';
function trendsPreferred(): boolean {
  try { return typeof localStorage !== 'undefined' && localStorage.getItem(TRENDS_KEY) === '1'; } catch { return false; }
}
function rememberTrends(open: boolean) {
  try { localStorage.setItem(TRENDS_KEY, open ? '1' : '0'); } catch { /* storage may be blocked inside the host iframe */ }
}
/** Same exclusions as the backend validity check that matter for display: invalid quality and future time. */
function usable(o: Observation, asOf: number): boolean {
  if (o.value == null || !Number.isFinite(o.value)) return false;
  if (o.qualityStatus && o.qualityStatus !== 'valid') return false;
  const t = Date.parse(o.eventTime);
  return Number.isFinite(t) && t <= asOf;
}
/** Additionally confirmed for this encounter and not a context-only report. */
function confirmed(o: Observation): boolean {
  return o.clinicalUse !== 'context_only' && o.encounterLinkStatus !== 'unconfirmed';
}
const units: Record<string, string> = { MAP: 'mmHg', SBP: 'mmHg', LACTATE: 'mmol/L', RR: '次/分', GCS: '分', HR: '次/分', TEMP: '°C', SPO2: '%' };

@Component({
  selector: 'sepsis-panel', standalone: true, imports: [CommonModule, FormsModule, ClinicalTrends, UiIcon, Icu05Card, AiAssistCard],
  template: `<section class="clinical-review sx-overview" aria-label="患者脓毒症概览" [attr.aria-busy]="loading" [class.nurse-first]="nurseFirst">
    <header class="sx-head" [attr.data-level]="headLevel">
      <div class="sx-head-main">
        <span class="sx-level-mark" aria-hidden="true"></span>
        <div><h1>{{ headline }}</h1><p *ngIf="headDetail">{{ headDetail }}</p></div>
      </div>
      <div class="sx-head-side">
        <span class="sx-chip" *ngIf="data?.available" [title]="data!.qsofaNote || ''">qSOFA <b>{{ data!.qsofa ?? '—' }}</b><small *ngIf="data!.qsofa !== null">/3</small><small *ngIf="data!.qsofa === null">无法计算</small></span>
        <button class="sx-pending alert" *ngIf="alertTotal" (click)="openAlerts.emit()"><ui-icon name="bell"/>{{ alertTotal }} 条预警待处理</button>
        <button class="sx-pending" *ngIf="taskTotal" (click)="openTasks.emit()"><ui-icon name="clipboard"/>{{ taskTotal }} 项任务待执行</button>
        <span class="sx-chip warn" *ngIf="alertsError" role="status">预警读取失败</span>
        <span class="sx-chip warn" *ngIf="tasksError" role="status">任务读取失败</span>
        <span class="sample-badge" *ngIf="data?.simulated">模拟数据</span>
        <button class="sx-refresh" (click)="load()" [disabled]="loading" [class.spin]="loading" aria-label="刷新数据" [title]="loading ? '读取中…' : (updatedAt ? '更新于 ' + (updatedAt | date:'HH:mm') + '，点击刷新' : '刷新')"><ui-icon name="refresh"/><span *ngIf="updatedAt">{{ updatedAt | date:'HH:mm' }}</span></button>
      </div>
    </header>
    <div class="review-state" role="alert" *ngIf="error || (data && !data.available)"><ui-icon name="info"/><strong>数据暂不可用</strong><span>{{ error || data?.message || '请刷新重试' }}</span></div>
    <div class="review-loading" *ngIf="loading && !data" role="status"><span class="spinner"></span>正在读取当前患者…</div>
    <p class="review-inline-error" role="alert" *ngIf="observationsError"><ui-icon name="info"/>{{ observationsError }} <button (click)="load()">重试</button></p>

    <section class="sx-vitals" *ngIf="data?.available && hasAnyValue" aria-label="关键指标">
      <button class="sx-tile" *ngFor="let t of tiles" [title]="t.time ? '记录于 ' + (t.time | date:'MM-dd HH:mm') + ' · ' + ago(t.time) : ''" [attr.data-level]="t.level" [class.candidate]="!t.evidence && t.candidate" (click)="selected = t.evidence || t.candidate" [disabled]="!t.evidence && !t.candidate" [attr.aria-label]="t.label + ' ' + (t.value ?? '无记录')">
        <span class="sx-tile-name">{{ t.label }}<em *ngIf="t.word">{{ t.word }}</em><small class="sx-ok" *ngIf="!t.word && t.level === 'normal'">正常</small></span>
        <span class="sx-tile-value"><b>{{ t.value ?? '—' }}</b><small>{{ t.unit }}</small><i class="sx-delta" *ngIf="t.delta !== null && t.delta !== 0">{{ t.delta > 0 ? '↑' : '↓' }}{{ abs(t.delta) }}</i></span>
        <svg class="sx-spark" *ngIf="t.spark" viewBox="0 0 100 24" preserveAspectRatio="none" aria-hidden="true"><path [attr.d]="t.spark"/></svg>
        <span class="sx-tile-foot" *ngIf="!t.time || stale(t.time)" [class.stale]="t.time"><ng-container *ngIf="t.time">{{ t.time | date:'HH:mm' }} · {{ ago(t.time) }}</ng-container><ng-container *ngIf="!t.time && t.candidate">报告 {{ t.candidate.value }} · 归属未确认</ng-container><ng-container *ngIf="!t.time && !t.candidate">24h 内无有效记录</ng-container></span>
        <span class="sx-tile-sub" *ngIf="t.sub" [title]="t.subTip || t.sub">{{ t.sub }}</span>
      </button>
    </section>
    <div class="sx-empty-bar" *ngIf="data?.available && !hasAnyValue">
      <ui-icon name="empty"/><span>近 24 小时未取得有效观测</span>
      <button class="primary" (click)="openTrends.emit()">查看历史记录</button><button (click)="openEvidence.emit()">记录单</button>
    </div>

    <div class="sx-grid" *ngIf="data?.available">
      <icu05-card *ngIf="icu05?.available" [data]="icu05!"/>
      <section class="review-card sx-bundle" aria-label="1小时集束化" *ngIf="!icu05?.available">
        <div class="review-card-title"><h2><ui-icon name="clipboard"/>1 小时集束化</h2><span class="sx-chip" title="院内尚未确定脓毒症时间零点规则，不计算倒计时">时间零点 <b>未确认</b></span></div>
        <table class="sx-table"><tbody>
          <tr *ngFor="let b of bundle" [attr.data-state]="b.state" [title]="b.tip">
            <th scope="row"><i class="sx-dot"></i>{{ b.name }}</th>
            <td class="sx-b-text">{{ b.text }}</td>
            <td class="sx-b-time" [title]="b.time ? ago(b.time) : ''">{{ b.time ? (b.time | date:'HH:mm') : '' }}</td>
            <td class="sx-b-hint"><span *ngIf="b.hint">{{ b.hint }}</span></td>
          </tr>
        </tbody></table>
      </section>
      <section class="review-card sx-organ" aria-label="器官功能">
        <div class="review-card-title">
          <h2><ui-icon name="activity"/>器官功能</h2>
          <span class="sx-sofa-chips">
            <button type="button" class="sx-chip sofa-chip" [class.on]="sofaOpen" (click)="sofaOpen = !sofaOpen"
              [title]="sofaClassic?.note || ''">
              SOFA <b>{{ sofaClassic?.total ?? sofaClassic?.partial ?? '—' }}</b>
              <small *ngIf="sofaClassic && sofaClassic.total !== null">6/6 项</small>
              <small *ngIf="sofaClassic && sofaClassic.total === null && sofaClassic.partial !== null">部分 {{ sofaClassic.scored }}/6</small>
              <small *ngIf="sofaClassic && sofaClassic.partial === null">缺项</small>
            </button>
            <button type="button" class="sx-chip sofa-chip" [class.on]="sofaOpen && sofaKind === 'sofa2'"
              (click)="toggleSofaKind('sofa2')">
              SOFA-2 <b>{{ sofa2?.total ?? sofa2?.partial ?? '—' }}</b>
              <small *ngIf="sofa2 && sofa2.total === null && sofa2.partial !== null">部分 {{ sofa2.scored }}/6</small>
            </button>
            <span class="sx-chip" *ngIf="sofa" title="宿主系统已录入的 SOFA 评分，与上方自动试算相互独立">宿主 SOFA <b>{{ sofa.value }}</b>
              <small *ngIf="sofaDelta !== null">24h {{ sofaDelta >= 0 ? '+' : '' }}{{ sofaDelta }}</small>
            </span>
            <span class="sx-chip" *ngIf="!sofa">宿主未评分</span>
          </span>
        </div>
        <div class="sofa-panel" *ngIf="sofaOpen && sofaView">
          <div class="sofa-panel-head">
            <div class="segmented sofa-tabs">
              <button type="button" [class.selected]="sofaKind === 'classic'" (click)="toggleSofaKind('classic')">经典 SOFA</button>
              <button type="button" [class.selected]="sofaKind === 'sofa2'" (click)="toggleSofaKind('sofa2')">SOFA-2</button>
            </div>
            <span class="sofa-total" *ngIf="sofaView.total !== null">合计 <b>{{ sofaView.total }}</b></span>
            <span class="sofa-total" *ngIf="sofaView.total === null">部分合计 <b>{{ sofaView.partial ?? '—' }}</b>
              <small>（{{ sofaView.scored }}/6 项；缺项未计，总分可能被低估）</small>
            </span>
          </div>
          <p class="sofa-note">{{ sofaView.note }}</p>
          <ul class="sofa-comps">
            <li *ngFor="let c of sofaView.components" [attr.data-score]="c.score" [class.missing]="c.score === null">
              <div class="sofa-comp-row">
                <span class="sofa-comp-name">{{ c.label }}</span>
                <span class="sofa-comp-score">{{ c.score ?? '—' }}</span>
                <span class="sofa-comp-val" *ngIf="c.value !== null">{{ c.value }} {{ c.unit }}</span>
                <span class="sofa-comp-val muted" *ngIf="c.value === null">无数据</span>
                <button type="button" class="text-button sofa-edit-btn" (click)="toggleSofaEdit(c.key)">
                  {{ sofaEditKey === c.key ? '收起' : c.score === null ? '补齐' : '改值' }}
                </button>
              </div>
              <div class="sofa-comp-meta">
                <span>{{ c.inputLabel }}</span>
                <span *ngIf="c.source === 'manual'" class="tag">手动</span>
                <span *ngIf="c.flags.includes('vaso_dose_unknown')">剂量未接入</span>
                <span *ngIf="c.flags.includes('resp_support_unknown')">通气信息缺</span>
                <span class="muted">{{ c.sourceNote }}</span>
              </div>
              <div class="sofa-edit-box" *ngIf="sofaEditKey === c.key">
                <label>
                  输入 {{ c.inputLabel }}（{{ manualUnit(c) }}）
                  <input type="number" step="any" [(ngModel)]="sofaManual[c.key]" name="sofa-{{ c.key }}" />
                </label>
                <div class="sofa-edit-actions">
                  <button type="button" class="primary" (click)="applySofaManual()">试算</button>
                  <button type="button" (click)="clearSofaManual(c.key)">清除</button>
                </div>
                <p class="sofa-manual-hint">仅本地试算，不写入源库、不改变规则判定。</p>
              </div>
            </li>
          </ul>
          <p class="sofa-flags" *ngIf="sofaView.flags.length">
            <span *ngFor="let f of sofaView.flags" class="tag">{{ sofaFlagLabel(f) }}</span>
          </p>
        </div>
        <table class="sx-table"><tbody>
          <tr *ngFor="let o of organs" [attr.data-level]="o.level">
            <th scope="row">{{ o.system }}</th><td class="sx-o-item">{{ o.item }}</td>
            <td class="sx-o-value"><button class="text-button" *ngIf="o.evidence" (click)="selected = o.evidence">{{ o.value }}</button><span *ngIf="!o.evidence">{{ o.value }}</span></td>
            <td class="sx-o-note">{{ o.note }}</td>
          </tr>
          <tr class="sx-infection" [attr.data-level]="infectionHit ? 'abnormal' : 'none'"><th scope="row">感染</th><td class="sx-o-item">诊断 / 培养</td><td class="sx-o-value" colspan="2">{{ infectionHit ? '诊断含感染相关描述' : '未取得感染证据' }}<small> · {{ cultureNote }}</small></td></tr>
        </tbody></table>
      </section>
      <ai-assist-card *ngIf="canAssist" [patientId]="patientId" [encounterId]="encounterId" [configured]="!!data?.ai?.configured" [icu05Available]="!!icu05?.available"/>
    </div>

    <details class="review-card sx-fold" *ngIf="data?.available" [open]="showTrends" (toggle)="onTrendsToggle($any($event.target).open)">
      <summary><ui-icon name="chart"/>生命体征趋势</summary>
      <clinical-trends *ngIf="showTrends" [items]="observations" [asOf]="asOf" [embedded]="true" [availableHours]="24" [compact]="true" [busy]="loading" (openRecords)="openEvidence.emit()" (openHistory)="openTrends.emit()"/>
    </details>
    <details class="sx-limits" *ngIf="limits.length" aria-label="数据限制"><summary><ui-icon name="info"/>数据限制 {{ limits.length }} 项</summary><ul><li *ngFor="let l of limits">{{ l }}</li></ul>
      <div class="review-table-wrap" *ngIf="data?.contextEvidence?.length"><table><thead><tr><th>未确认归属的报告</th><th>报告值</th><th>报告时间</th><th></th></tr></thead><tbody><tr *ngFor="let e of data!.contextEvidence"><td>{{ featureName(e.code) }}</td><td>{{ e.value }} {{ e.unit || '' }}</td><td>{{ e.eventTime | date:'MM-dd HH:mm' }}</td><td><button class="text-button" (click)="selected=e">来源</button></td></tr></tbody></table></div>
    </details>
    <section class="review-evidence-detail" *ngIf="selected" aria-label="指标详情"><div class="review-card-title"><h2><ui-icon name="file"/>{{ selected.label || featureName(selected.code) }}</h2><button (click)="selected=null">关闭</button></div><div class="evidence-reading">{{ selected.value }}<small>{{ selected.unit || '单位未知' }}</small><span class="ev-word" *ngIf="evWord(selected) as w" [attr.data-level]="evLevel(selected)">{{ w }}</span></div><dl><dt>{{ selected.timeBasis==='reported' ? '报告时间' : '记录时间' }}</dt><dd>{{ selected.eventTime | date:'MM-dd HH:mm' }}<span class="muted"> · {{ evAgo(selected) }}</span></dd><dt>来源</dt><dd>{{ sourceName(selected.sourceSystem) }}<ng-container *ngIf="channelName(selected) as c"> · {{ c }}</ng-container></dd><ng-container *ngIf="evRef(selected) as r"><dt>参考</dt><dd>{{ r }}</dd></ng-container></dl><ng-container *ngIf="evStats(selected) as st"><p class="ev-stats">近 24 小时 {{ st.n }} 次 · 最低 <b>{{ st.min }}</b>（{{ st.minAt | date:'HH:mm' }}）· 最高 <b>{{ st.max }}</b>（{{ st.maxAt | date:'HH:mm' }}）<ng-container *ngIf="st.n > 1"> · 首次 {{ st.first }} → 最近 {{ st.last }}</ng-container></p><table class="ev-recent"><thead><tr><th>时间</th><th>数值</th></tr></thead><tbody><tr *ngFor="let o of st.recent"><td>{{ o.eventTime | date:'MM-dd HH:mm' }}</td><td [attr.data-level]="evLevel(o)">{{ o.value }}</td></tr></tbody></table></ng-container><p class="context-warning" *ngIf="selected.qualityNote">{{ selected.qualityNote }}</p><details class="ev-tech" *ngIf="selected.sourceRecordId || selected.sourceParameterCode"><summary>追溯信息（供信息科核对原始记录）</summary><p *ngIf="selected.sourceRecordId">记录号 {{ selected.sourceRecordId }}</p><p *ngIf="selected.sourceParameterCode">源字段 {{ selected.sourceParameterCode }}</p></details></section>
  </section>`,
})
export class SepsisPanel implements OnChanges, OnDestroy {
  @Input() patientId = '';
  @Input() encounterId = '';
  @Output() openTrends = new EventEmitter<void>();
  @Output() openEvidence = new EventEmitter<void>();
  @Output() openAlerts = new EventEmitter<void>();
  @Output() openTasks = new EventEmitter<void>();
  @Output() status = new EventEmitter<SepsisStatus>();
  api = inject(Api);
  get canAssist() { return this.api.has('DOCTOR') || this.api.has('NURSE'); }
  data: ClinicalReview | null = null;
  icu05: Icu05Result | null = null;
  observations: Observation[] = [];
  alertTotal: number | null = null;
  taskTotal: number | null = null;
  loading = false; error = ''; observationsError = ''; alertsError = false; tasksError = false;
  asOf = ''; updatedAt = ''; selected: Evidence | null = null; showTrends = trendsPreferred();
  tiles: Tile[] = []; bundle: BundleRow[] = []; organs: OrganRow[] = []; limits: string[] = [];
  cultureNote = '微生物培养结果源未接入';
  sofa: Observation | null = null; sofaDelta: number | null = null;
  sofaOpen = false;
  sofaKind: SofaKind = 'classic';
  sofaClassic: SofaResult | null = null;
  sofa2: SofaResult | null = null;
  sofaEditKey: CompKey | null = null;
  sofaManual: SofaManualInput = {};
  get sofaView(): SofaResult | null {
    return this.sofaKind === 'sofa2' ? this.sofa2 : this.sofaClassic;
  }
  headline = ''; headDetail = ''; headLevel: Level | 'unknown' = 'unknown';
  private generation = 0;
  featureName = featureLabel;
  evRef(e: Evidence) { return reference(e.code); }
  evLevel(e: { code?: string; parameterCode?: string; value: number | null }) { return level((e.code || (e as Observation).parameterCode || '') as string, e.value); }
  evWord(e: Evidence) { const d = direction(e.code, e.value); return d || (level(e.code, e.value) === 'normal' && reference(e.code) ? '正常' : ''); }
  evAgo(e: Evidence) { return ago(e.eventTime, Date.now()); }
  channelName(e: Evidence) { const c = e.measurementChannel || (/_IBP$/.test(e.code) ? 'IBP' : /_NIBP$/.test(e.code) ? 'NIBP' : ''); return c === 'IBP' ? '有创测量' : c === 'NIBP' ? '无创测量' : ''; }
  /** 24h summary of the same parameter from the already-loaded observations (no extra request). */
  evStats(e: Evidence) {
    const asOf = Date.now(), from = asOf - 24 * 3600000;
    const pts = this.observations.filter(o => o.parameterCode === e.code && o.value != null && o.clinicalUse !== 'context_only'
      && (!o.qualityStatus || o.qualityStatus === 'valid') && Date.parse(o.eventTime) >= from && Date.parse(o.eventTime) <= asOf)
      .sort((a, b) => Date.parse(a.eventTime) - Date.parse(b.eventTime));
    if (!pts.length) return null;
    let lo = pts[0], hi = pts[0];
    for (const o of pts) { if (o.value! < lo.value!) lo = o; if (o.value! > hi.value!) hi = o; }
    return { n: pts.length, min: lo.value, minAt: lo.eventTime, max: hi.value, maxAt: hi.eventTime, first: pts[0].value, last: pts.at(-1)!.value,
      recent: pts.slice(-8).reverse() };
  }
  sourceName = sourceLabel;
  abs = Math.abs;
  get nurseFirst() { return this.api.has('NURSE') && !this.api.has('DOCTOR'); }
  get hasAnyValue() { return this.tiles.some(t => t.value !== null); }
  get infectionHit() { return !!this.data?.infectionClue && this.data.infectionClue.includes('含感染'); }
  onTrendsToggle(open: boolean) { this.showTrends = open; rememberTrends(open); }
  ago(t: string | null) { return ago(t, Date.parse(this.asOf) || Date.now()); }
  ngOnChanges() { this.data = null; this.icu05 = null; this.observations = []; this.selected = null; this.showTrends = trendsPreferred(); this.derive(); void this.load(); }
  ngOnDestroy() { ++this.generation; }
  async load() {
    const generation = ++this.generation; if (!this.patientId) return;
    this.loading = true; this.error = ''; this.observationsError = ''; this.selected = null;
    // Clear stale counts/data before refresh. A failed request must never look like a zero count.
    this.data = null; this.icu05 = null; this.observations = []; this.alertTotal = null; this.taskTotal = null; this.derive();
    const base = '/patients/' + encodeURIComponent(this.patientId);
    const results = await Promise.allSettled([
      this.api.get<ClinicalReview>(base + '/sepsis-assessment'),
      this.api.get<{ items: Observation[]; asOf: string }>(base + '/clinical-data?hours=24'),
      this.api.get<Page<Alert>>('/alerts?status=pending&size=1'),
      this.api.get<Page<Task>>('/bundles/tasks?metric=pending&patientId=' + encodeURIComponent(this.patientId) + '&size=1'),
      this.api.get<Icu05Result>(base + '/icu05-bundle'),
    ] as const);
    if (generation !== this.generation) return;
    const [review, observations, alerts, tasks, icu05] = results;
    // ICU-05 is optional: a failed or mismatched response falls back to the plain checklist, never to "not met".
    this.icu05 = icu05.status === 'fulfilled' && icu05.value?.schemaVersion === 1 && matchesPatient(icu05.value, this.patientId, this.encounterId) ? icu05.value : null;
    if (review.status === 'fulfilled' && review.value.schemaVersion === 2 && matchesPatient(review.value, this.patientId, this.encounterId)) this.data = review.value;
    else this.error = review.status === 'rejected' ? errorMessage(review.reason) : '患者上下文或接口版本不一致，已停止展示。';
    if (observations.status === 'fulfilled') { this.observations = observations.value.items; this.asOf = observations.value.asOf; }
    else { this.observationsError = '观测数据读取失败：' + errorMessage(observations.reason); this.asOf = this.data?.asOf || new Date().toISOString(); }
    this.alertsError = alerts.status === 'rejected'; this.tasksError = tasks.status === 'rejected';
    if (alerts.status === 'fulfilled') this.alertTotal = alerts.value.total;
    if (tasks.status === 'fulfilled') this.taskTotal = tasks.value.total;
    this.updatedAt = new Date().toISOString(); this.loading = false;
    this.derive();
    const shock = this.icu05?.status === 'septic_shock';
    this.status.emit({ patientId: this.patientId, level: shock ? 'critical' : this.headLevel, summary: shock ? 'ICU-05 ' + this.icu05!.statusLabel + ' · ' + this.headline : this.headline, pendingAlerts: this.alertTotal, pendingTasks: this.taskTotal });
  }

  /** All derived view state is computed once per load, not in template getters. */
  private derive() {
    const d = this.data, asOf = Date.parse(this.asOf) || Date.now();
    if (!d || !d.available) {
      this.tiles = []; this.bundle = []; this.organs = []; this.limits = d?.sourceWarnings || []; this.sofa = null; this.sofaDelta = null;
      this.sofaClassic = null; this.sofa2 = null; this.sofaEditKey = null; this.sofaManual = {};
      this.headLevel = 'unknown'; this.headline = d || this.error ? '数据暂不可用' : '正在读取'; this.headDetail = d || this.error ? '不能据此判断病情' : ''; return;
    }
    const latest = new Map<string, Evidence>();
    for (const e of d.latestEvidence || []) if (e.clinicalUse !== 'context_only' && e.encounterLinkStatus !== 'unconfirmed') latest.set(e.code, e);
    const candidates = new Map<string, Evidence>();
    for (const e of d.contextEvidence || []) if (!candidates.has(e.code)) candidates.set(e.code, e);
    const channels = ['IBP', 'NIBP'].filter(c => latest.has('MAP_' + c) || latest.has('SBP_' + c) || this.observations.some(o => o.parameterCode === 'MAP_' + c));
    const pressure = channels.length ? channels.map(c => ['MAP_' + c, 'SBP_' + c]) : [['MAP', 'SBP']];
    const codes = [...pressure.map(p => p[0]), 'LACTATE', 'RR', 'GCS', 'HR', 'TEMP', 'SPO2'];
    this.tiles = codes.map(code => this.tile(code, latest, candidates, asOf, pressure.find(p => p[0] === code)?.[1]));
    const flagged = this.tiles.filter(t => t.level === 'critical' || t.level === 'abnormal').sort((a, b) => levelRank[a.level] - levelRank[b.level]);
    if (flagged.length) {
      this.headLevel = flagged[0].level;
      this.headline = flagged.slice(0, 3).map(t => `${shortName[t.code] || t.label} ${t.value} ${t.word}`.trim()).join(' · ');
    } else if (d.missingFeatures.length) { this.headLevel = 'unknown'; this.headline = '关键数据不全'; }
    else { this.headLevel = 'normal'; this.headline = '主要指标未超出提示阈值'; }
    const missing = d.missingFeatures.map((code) => {
      if (code === 'LACTATE_UNCONFIRMED') return '乳酸（未确认）';
      if (code === 'INFECTION_EVIDENCE') return '感染证据';
      return featureLabel(code);
    });
    this.headDetail = missing.length ? '缺：' + missing.join('、') : flagged.length > 3 ? '另有 ' + (flagged.length - 3) + ' 项异常' : '';
    this.bundle = this.bundleRows(d, latest, candidates, asOf);
    this.organs = this.organRows(d, latest);
    const sofa = this.series('SOFA', 'score', 0, 24, asOf);
    this.sofa = sofa.at(-1) || null;
    this.sofaDelta = sofa.length > 1 ? (sofa.at(-1)!.value! - sofa[0].value!) : null;
    this.recomputeSofa();
    this.limits = [...(d.sourceWarnings || []), ...(d.contextEvidence?.length ? ['未确认住院归属的报告仅列出，不参与判断。'] : []),
      d.ai?.configured ? '阈值着色仅供显示；AI 需医生核对。' : '阈值着色仅供显示。'];
  }

  private recomputeSofa() {
    const asOf = Date.parse(this.asOf) || Date.now();
    this.sofaClassic = computeSofa(this.observations, asOf, 'classic', this.sofaManual);
    this.sofa2 = computeSofa(this.observations, asOf, 'sofa2', this.sofaManual);
  }

  toggleSofaKind(kind: SofaKind) {
    this.sofaKind = kind;
    this.sofaOpen = true;
  }
  toggleSofaEdit(key: CompKey) {
    this.sofaEditKey = this.sofaEditKey === key ? null : key;
  }
  applySofaManual() {
    this.recomputeSofa();
  }
  clearSofaManual(key: CompKey) {
    delete this.sofaManual[key];
    this.recomputeSofa();
  }
  manualUnit(c: { key: CompKey; unit: string }): string {
    if (c.key === 'liver' || c.key === 'renal') return 'μmol/L';
    if (c.key === 'coag') return '10^9/L';
    if (c.key === 'resp') return 'mmHg (P/F)';
    if (c.key === 'cns') return '分 (GCS)';
    if (c.key === 'cardio') return 'mmHg (MAP)';
    return c.unit || '数值';
  }
  sofaFlagLabel(f: string): string {
    const m: Record<string, string> = {
      vaso_dose_unknown: '升压药剂量未接入',
      resp_support_unknown: '机械通气信息缺失',
      urine_not_used: '尿量未纳入',
    };
    return m[f] || f;
  }

  private tile(code: string, latest: Map<string, Evidence>, candidates: Map<string, Evidence>, asOf: number, sbpCode?: string): Tile {
    let e = latest.get(code) || null;
    // Assessment latestEvidence can lag or filter codes; fall back to the newest clinical-data point so vitals with real readings still show.
    if (!e) {
      const obs = this.latestObs(code);
      if (obs && obs.value != null && obs.clinicalUse !== 'context_only' && obs.encounterLinkStatus !== 'unconfirmed') {
        e = {
          code: obs.parameterCode,
          value: obs.value,
          unit: obs.unit || '',
          eventTime: obs.eventTime,
          sourceSystem: obs.sourceSystem,
          sourceRecordId: obs.sourceRecordId,
          sourceParameterCode: obs.sourceParameterCode,
          clinicalUse: obs.clinicalUse,
          encounterLinkStatus: obs.encounterLinkStatus,
          timeBasis: obs.timeBasis,
        };
      }
    }
    const base = baseCode(code);
    const unit = units[base] || e?.unit || '';
    const pts = this.series(code, e?.unit || measures.find(m => m.code === base)?.unit || '', -1e9, 1e9, asOf, 12);
    const value = e ? e.value : null;
    const prev = pts.length > 1 ? pts[pts.length - 2].value : null;
    let sub = '';
    if (sbpCode) { const s = latest.get(sbpCode) || this.evidenceFromObs(sbpCode); sub = '收缩压 ' + (s ? s.value : '—'); }
    let lacLabel = '';
    let subTip = '';
    if (base === 'MAP') { const v = this.vasoText(); subTip = sub + ' · ' + v; sub += (sub ? ' · ' : '') + this.vasoShort(v); }
    // 13: the ICU-05 initial lactate can differ from the latest one; label both.
    if (code === 'LACTATE' && this.icu05?.gate) {
      const k1 = this.icu05.gate.shock.find(i => i.code === 'K1');
      if (k1?.detail) { sub = '首次 ' + k1.detail + (k1.time ? '（' + new Date(k1.time).toTimeString().slice(0, 5) + '）' : ''); lacLabel = '乳酸 · 最近'; }
    }
    const delta = value !== null && prev !== null && e && pts.at(-1)?.eventTime === e.eventTime ? Math.round((value - prev) * 10) / 10 : null;
    return { code, label: lacLabel || tileName[code] || featureLabel(code), unit, value, time: e?.eventTime || null, level: level(code, value), word: value === null ? '' : direction(code, value),
      delta, spark: this.sparkPath(pts), sub, subTip, evidence: e, candidate: e ? null : candidates.get(code) || null };
  }

  /**
   * One vasopressor statement for the whole screen: bedside VASOPRESSOR_ACTIVE and the ICU-05 execution sheet (drugExe)
   * are different sources, so a disagreement is shown instead of two contradictory sentences.
   */
  /** Tile-sized vasopressor wording; the full sentence (drug, source) stays in the tooltip and organ card. */
  vasoShort(full: string): string {
    if (full.startsWith('升压药 执行单有记录')) return full.includes('床旁记录为未用') ? '升压药记录不一致' : '升压药有记录';
    return full.replace(/ · .*$/, '');
  }
  stale(t: string) { const a = Date.parse(this.asOf) || Date.now(); return a - Date.parse(t) > 60 * 60000; }
  vasoText(): string {
    const bedside = this.data?.vasopressorActive;
    const s4 = this.icu05?.available ? this.icu05.gate?.organ.find(i => i.code === 'S4') : undefined;
    const drug = (s4?.detail || '').replace(/（T0 前开始.*$/, '').trim();
    if (s4?.value === true) return '升压药 执行单有记录' + (drug ? '（' + drug + '）' : '') + (bedside === false ? ' · 床旁记录为未用' : '');
    if (bedside === true) return '升压药在用' + (s4?.value === false ? ' · 执行单门控期内未见' : '');
    if (bedside === false || s4?.value === false) return '升压药未用';
    return '升压药无记录';
  }

  private evidenceFromObs(code: string): Evidence | null {
    const obs = this.latestObs(code, true);
    if (!obs || obs.value == null) return null;
    return {
      code: obs.parameterCode,
      value: obs.value,
      unit: obs.unit || '',
      eventTime: obs.eventTime,
      sourceSystem: obs.sourceSystem,
      sourceRecordId: obs.sourceRecordId,
      sourceParameterCode: obs.sourceParameterCode,
      clinicalUse: obs.clinicalUse,
      encounterLinkStatus: obs.encounterLinkStatus,
      timeBasis: obs.timeBasis,
    };
  }
  private series(code: string, unit: string, min: number, max: number, asOf: number, hours = 24): Observation[] {
    if (!unit) return [];
    const m: Measure = { code, label: code, unit, color: '', min, max, gapMinutes: 150 };
    return pointsFor(this.observations, m, asOf - hours * 3600000, asOf);
  }
  private sparkPath(pts: Observation[]): string {
    if (pts.length < 2) return '';
    const t0 = Date.parse(pts[0].eventTime), t1 = Date.parse(pts.at(-1)!.eventTime) || t0 + 1;
    const vs = pts.map(p => p.value!), lo = Math.min(...vs), hi = Math.max(...vs), span = hi - lo || 1;
    return pts.map((p, i) => (i ? 'L' : 'M') + ((Date.parse(p.eventTime) - t0) / (t1 - t0 || 1) * 100).toFixed(1) + ',' + (21 - (p.value! - lo) / span * 18).toFixed(1)).join(' ');
  }
  private bundleRows(d: ClinicalReview, latest: Map<string, Evidence>, candidates: Map<string, Evidence>, asOf: number): BundleRow[] {
    const note = (code: string) => d.checklist?.find(c => c.code === code)?.note || '';
    const lac = latest.get('LACTATE'), lacCand = candidates.get('LACTATE');
    let lacRow: BundleRow;
    if (lac) {
      const h = hoursSince(lac.eventTime, asOf) ?? 0, high = lac.value >= 2;
      lacRow = { key: 'LACTATE', name: '乳酸测定', state: high && h >= 2 ? 'warn' : 'done', text: `已有结果 ${lac.value} mmol/L`, time: lac.eventTime,
        hint: high ? (h >= 2 ? '≥2，需复测' : '≥2，2–4 小时复测') : '', tip: note('LACTATE') };
    } else if (lacCand) {
      lacRow = { key: 'LACTATE', name: '乳酸测定', state: 'info', text: `仅有报告 ${lacCand.value} mmol/L，归属未确认`, time: null, hint: '采样时间未知', tip: lacCand.qualityNote || '' };
    } else lacRow = { key: 'LACTATE', name: '乳酸测定', state: 'none', text: '未取得结果', time: null, hint: '', tip: note('LACTATE') };
    const mapLow = this.tiles.some(t => baseCode(t.code) === 'MAP' && t.value !== null && t.value < 65);
    const vaso = d.vasopressorActive;
    return [
      lacRow,
      { key: 'BLOOD_CULTURE', name: '血培养', state: 'none', text: '采样记录未接入', time: null, hint: '抗菌药前留取', tip: note('BLOOD_CULTURE') },
      { key: 'ANTIBIOTIC', name: '广谱抗菌药', state: 'none', text: '给药记录未接入', time: null, hint: '', tip: note('ANTIBIOTIC') },
      { key: 'FLUID', name: '晶体液 30 ml/kg', state: 'none', text: '输液量 / 体重未接入', time: null, hint: mapLow || (lac && lac.value >= 4) ? '低血压或乳酸≥4 时适用' : '', tip: note('FLUID') },
      { key: 'VASOPRESSOR', name: '血管活性药', state: vaso === true ? 'done' : mapLow ? 'warn' : 'none', text: vaso === true ? '有在用记录' : vaso === false ? '记录为未用' : '未取得记录', time: null, hint: mapLow ? 'MAP<65' : '', tip: '仅反映源系统记录，不判断剂量或达标；休克口径：需要升压药，或乳酸≥2 且 MAP<65' },
      { key: 'REASSESS', name: '容量 / 灌注再评估', state: 'none', text: '未取得记录', time: null, hint: '', tip: note('REASSESS') },
    ];
  }
  /** Latest observation for a code from clinical-data (includes lab reports). */
  private latestObs(code: string, confirmedOnly = false): Observation | null {
    let best: Observation | null = null;
    const asOf = Date.parse(this.asOf) || Date.now();
    for (const o of this.observations) {
      if (o.parameterCode !== code || !usable(o, asOf) || (confirmedOnly && !confirmed(o))) continue;
      if (!best || Date.parse(o.eventTime) > Date.parse(best.eventTime)) best = o;
    }
    return best;
  }

  /**
   * Sum urine increments in the loaded 24h window.
   * Source param_niaoLiang is a period total in ml (nursing interval), not a running rate.
   * Units other than ml are ignored for the sum (never invent conversions).
   */
  private urineTotal24h(): { total: number; count: number; latest: Observation | null } {
    let total = 0;
    let count = 0;
    let latest: Observation | null = null;
    const asOf = Date.parse(this.asOf) || Date.now();
    const seen = new Set<string>();
    for (const o of this.observations) {
      if (o.parameterCode !== 'URINE_OUTPUT' || !usable(o, asOf) || !confirmed(o)) continue;
      if (Date.parse(o.eventTime) < asOf - 24 * 3600000) continue;
      // The same nursing record can arrive twice (re-sync); count it once.
      const key = (o.sourceRecordId || '') + '|' + o.eventTime + '|' + o.value;
      if (seen.has(key)) continue;
      seen.add(key);
      const unit = (o.unit || 'ml').trim().toLowerCase();
      if (unit && unit !== 'ml' && unit !== '毫升') continue;
      total += o.value!;
      count += 1;
      if (!latest || Date.parse(o.eventTime) > Date.parse(latest.eventTime)) latest = o;
    }
    // Round to 1 decimal to avoid float noise from summing.
    return { total: Math.round(total * 10) / 10, count, latest };
  }

  private organRows(d: ClinicalReview, latest: Map<string, Evidence>): OrganRow[] {
    const maps = this.tiles.filter(t => baseCode(t.code) === 'MAP' && t.value !== null);
    const worstMap = maps.sort((a, b) => levelRank[a.level] - levelRank[b.level])[0];
    const gcsEv = latest.get('GCS');
    const gcsObs = this.latestObs('GCS', true);
    const gcsValue = gcsEv?.value ?? gcsObs?.value ?? null;
    let gcsEvid: Evidence | null = gcsEv || null;
    if (!gcsEvid && gcsObs && gcsObs.value != null) {
      gcsEvid = {
        code: 'GCS',
        value: gcsObs.value,
        unit: gcsObs.unit || 'score',
        eventTime: gcsObs.eventTime,
        sourceSystem: gcsObs.sourceSystem,
        sourceRecordId: gcsObs.sourceRecordId,
        sourceParameterCode: gcsObs.sourceParameterCode,
      };
    }
    const rr = latest.get('RR'), spo2 = latest.get('SPO2');
    const vaso = this.vasoText();

    const pf = this.latestObs('PF_RATIO');
    const plt = this.latestObs('PLT');
    const tbil = this.latestObs('TBIL');
    const crea = this.latestObs('CREA');
    // 尿量：源 param_niaoLiang 为区间增量（ml），按近 24h 求和；不把最新一条 0 当作总量。
    const urineAgg = this.urineTotal24h();
    const pfNote =
      '参考：' +
      (rr ? '呼吸 ' + rr.value : '呼吸 —') +
      ' · ' +
      (spo2 ? 'SpO₂ ' + spo2.value + '%' : 'SpO₂ —');

    const organ = (
      system: string,
      item: string,
      obs: Observation | null,
      fallback: string,
      note: string
    ): OrganRow => {
      if (!obs || obs.value == null)
        return { system, item, value: fallback, level: 'none', note, evidence: null };
      const lv = level(obs.parameterCode, obs.value);
      const ev: Evidence = {
        code: obs.parameterCode,
        value: obs.value as number,
        unit: obs.unit || '',
        eventTime: obs.eventTime,
        sourceSystem: obs.sourceSystem,
        sourceRecordId: obs.sourceRecordId,
        sourceParameterCode: obs.sourceParameterCode,
        encounterLinkStatus: obs.encounterLinkStatus,
        clinicalUse: obs.clinicalUse,
        qualityNote: obs.qualityNote,
        timeBasis: obs.timeBasis,
      };
      return {
        system,
        item,
        value: String(obs.value) + (obs.unit ? ' ' + obs.unit : ''),
        level: lv,
        note: (obs.clinicalUse === 'context_only' ? '报告 · 归属未确认 · ' : '') + note,
        evidence: ev,
      };
    };

    const cultureOrders = this.icu05?.cultureOrders || [];
    const latestCulture = cultureOrders[0];
    const cultureNote = latestCulture
      ? '送检医嘱：' + (latestCulture.name || '培养') + (latestCulture.orderTime ? ' · ' + this.ago(latestCulture.orderTime) : '') + '；培养报告源未接入'
      : this.icu05?.available
        ? '窗口内未见培养送检医嘱；培养报告源未接入'
        : '微生物培养报告源未接入';
    this.cultureNote = cultureNote;

    const renalNote = urineAgg.count > 0
      ? `近 24h 尿量累计（${urineAgg.count} 条区间记录求和）· 源间隔约数小时，非逐小时曲线`
      : '近 24h 无尿量记录（不代表无尿）';
    const urineText = urineAgg.count > 0
      ? `尿量 24h 累计 ${urineAgg.total} ml`
      : '';
    const renalValue = crea && crea.value != null
      ? String(crea.value) + (crea.unit ? ' ' + crea.unit : '') + (urineText ? ' · ' + urineText : '')
      : urineText || '—';
    const renalRow: OrganRow = crea && crea.value != null
      ? organ('肾脏', '肌酐 / 尿量', crea, renalValue, renalNote)
      : urineAgg.count > 0 && urineAgg.latest
        ? {
            system: '肾脏',
            item: '肌酐 / 尿量',
            value: renalValue,
            level: 'none',
            note: renalNote,
            evidence: {
              code: 'URINE_OUTPUT',
              value: urineAgg.total,
              unit: 'ml/24h',
              eventTime: urineAgg.latest.eventTime,
              sourceSystem: urineAgg.latest.sourceSystem,
              sourceRecordId: urineAgg.latest.sourceRecordId,
              sourceParameterCode: urineAgg.latest.sourceParameterCode,
              clinicalUse: urineAgg.latest.clinicalUse,
              qualityNote:
                '近 24h 内 ' + urineAgg.count + ' 条 param_niaoLiang 区间增量求和；证据时间取最近一条记录时间。' +
                (urineAgg.latest.qualityNote || ''),
              timeBasis: urineAgg.latest.timeBasis,
            },
          }
        : { system: '肾脏', item: '肌酐 / 尿量', value: renalValue, level: 'none', note: renalNote, evidence: null };

    return [
      {
        system: '循环',
        item: 'MAP / 升压药',
        value: maps.length
          ? maps.map(m => (shortName[m.code] || 'MAP') + ' ' + m.value).join('，')
          : '—',
        level: worstMap?.level || 'none',
        note: vaso,
        evidence: worstMap?.evidence || null,
      },
      {
        system: '中枢神经',
        item: 'GCS',
        value: gcsValue != null ? gcsValue + ' 分' : '—',
        level: level('GCS', gcsValue),
        note: gcsValue != null ? '需结合镇静状态' : '未取得',
        evidence: gcsEvid,
      },
      organ('呼吸', 'PaO₂/FiO₂', pf, '—', pfNote),
      organ('凝血', '血小板', plt, '—', ''),
      organ('肝脏', '胆红素', tbil, '—', ''),
      renalRow,
    ];
  }
}
