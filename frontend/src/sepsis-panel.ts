import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output, OnChanges, OnDestroy, inject } from '@angular/core';
import { Api, Alert, Page, Task, Observation, errorMessage } from './api';
import { UiIcon } from './ui-icon';
import { ClinicalTrends } from './clinical-trends';
import { ClinicalReview, Evidence, featureLabel, matchesPatient } from './clinical-review';
import { Level, ago, baseCode, direction, hoursSince, level, levelRank } from './clinical-flags';
import { Measure, measures, pointsFor } from './trends';

export interface SepsisStatus {
  patientId: string; level: Level | 'unknown'; summary: string;
  pendingAlerts: number | null; pendingTasks: number | null;
}
interface Tile {
  code: string; label: string; unit: string; value: number | null; time: string | null; level: Level; word: string;
  delta: number | null; spark: string; sub: string; evidence: Evidence | null; candidate: Evidence | null;
}
interface BundleRow { key: string; name: string; state: 'done' | 'warn' | 'none' | 'info'; text: string; time: string | null; hint: string; tip: string; }
interface OrganRow { system: string; item: string; value: string; level: Level; note: string; evidence: Evidence | null; }

const shortName: Record<string, string> = { MAP: 'MAP', MAP_IBP: 'MAP(有创)', MAP_NIBP: 'MAP(无创)', LACTATE: '乳酸', RR: '呼吸', GCS: 'GCS', HR: '心率', TEMP: '体温', SPO2: 'SpO₂', SBP: '收缩压', SBP_IBP: '收缩压(有创)', SBP_NIBP: '收缩压(无创)' };
const tileName: Record<string, string> = { MAP: '平均动脉压', MAP_IBP: '平均动脉压 · 有创', MAP_NIBP: '平均动脉压 · 无创', LACTATE: '乳酸', RR: '呼吸频率', GCS: 'GCS', HR: '心率', TEMP: '体温', SPO2: 'SpO₂' };
const units: Record<string, string> = { MAP: 'mmHg', SBP: 'mmHg', LACTATE: 'mmol/L', RR: '次/分', GCS: '分', HR: '次/分', TEMP: '°C', SPO2: '%' };

@Component({
  selector: 'sepsis-panel', standalone: true, imports: [CommonModule, ClinicalTrends, UiIcon],
  template: `<section class="clinical-review sx-overview" aria-label="患者脓毒症概览" [attr.aria-busy]="loading" [class.nurse-first]="nurseFirst">
    <header class="sx-head" [attr.data-level]="headLevel">
      <div class="sx-head-main">
        <span class="sx-level-mark" aria-hidden="true"></span>
        <div><h1>{{ headline }}</h1><p *ngIf="headDetail">{{ headDetail }}</p></div>
      </div>
      <div class="sx-head-side">
        <span class="sx-chip" *ngIf="data?.available" [title]="data!.qsofaNote || ''">qSOFA <b>{{ data!.qsofa ?? '—' }}</b><small *ngIf="data!.qsofa !== null">/3</small><small *ngIf="data!.qsofa === null">无法计算</small></span>
        <button class="sx-pending danger" *ngIf="alertTotal" (click)="openAlerts.emit()"><ui-icon name="bell"/>{{ alertTotal }} 条预警待处理</button>
        <button class="sx-pending" *ngIf="taskTotal" (click)="openTasks.emit()"><ui-icon name="clipboard"/>{{ taskTotal }} 项任务待执行</button>
        <span class="sx-chip warn" *ngIf="alertsError" role="status">预警读取失败</span>
        <span class="sx-chip warn" *ngIf="tasksError" role="status">任务读取失败</span>
        <span class="sample-badge" *ngIf="data?.simulated">模拟数据</span>
        <span class="sx-time" *ngIf="updatedAt"><ui-icon name="clock"/>{{ updatedAt | date:'HH:mm' }}</span>
        <button (click)="load()" [disabled]="loading" aria-label="刷新数据"><ui-icon name="refresh"/>{{ loading ? '读取中…' : '刷新' }}</button>
      </div>
    </header>
    <div class="review-state" role="alert" *ngIf="error || (data && !data.available)"><ui-icon name="info"/><strong>数据暂不可用</strong><span>{{ error || data?.message || '请刷新重试' }}</span></div>
    <div class="review-loading" *ngIf="loading && !data" role="status"><span class="spinner"></span>正在读取当前患者…</div>
    <p class="review-inline-error" role="alert" *ngIf="observationsError"><ui-icon name="info"/>{{ observationsError }} <button (click)="load()">重试</button></p>

    <section class="sx-vitals" *ngIf="data?.available && hasAnyValue" aria-label="关键指标">
      <button class="sx-tile" *ngFor="let t of tiles" [attr.data-level]="t.level" [class.candidate]="!t.evidence && t.candidate" (click)="selected = t.evidence || t.candidate" [disabled]="!t.evidence && !t.candidate" [attr.aria-label]="t.label + ' ' + (t.value ?? '无记录')">
        <span class="sx-tile-name">{{ t.label }}<em *ngIf="t.word">{{ t.word }}</em></span>
        <span class="sx-tile-value"><b>{{ t.value ?? '—' }}</b><small>{{ t.unit }}</small><i class="sx-delta" *ngIf="t.delta !== null && t.delta !== 0">{{ t.delta > 0 ? '↑' : '↓' }}{{ abs(t.delta) }}</i></span>
        <svg class="sx-spark" *ngIf="t.spark" viewBox="0 0 100 24" preserveAspectRatio="none" aria-hidden="true"><path [attr.d]="t.spark"/></svg>
        <span class="sx-tile-foot"><ng-container *ngIf="t.time">{{ t.time | date:'HH:mm' }} · {{ ago(t.time) }}</ng-container><ng-container *ngIf="!t.time && t.candidate">报告 {{ t.candidate.value }} · 归属未确认</ng-container><ng-container *ngIf="!t.time && !t.candidate">24h 内无有效记录</ng-container></span>
        <span class="sx-tile-sub" *ngIf="t.sub">{{ t.sub }}</span>
      </button>
    </section>
    <div class="sx-empty-bar" *ngIf="data?.available && !hasAnyValue">
      <ui-icon name="empty"/><span>近 24 小时未取得有效观测，缺项不代表正常</span>
      <button class="primary" (click)="openTrends.emit()">查看历史记录</button><button (click)="openEvidence.emit()">记录单</button>
    </div>

    <div class="sx-grid" *ngIf="data?.available">
      <section class="review-card sx-bundle" aria-label="1小时集束化">
        <div class="review-card-title"><h2><ui-icon name="clipboard"/>1 小时集束化</h2><span class="sx-chip" title="院内尚未确定脓毒症时间零点规则，不计算倒计时">时间零点 <b>未确认</b></span></div>
        <table class="sx-table"><tbody>
          <tr *ngFor="let b of bundle" [attr.data-state]="b.state" [title]="b.tip">
            <th scope="row"><i class="sx-dot"></i>{{ b.name }}</th>
            <td class="sx-b-text">{{ b.text }}</td>
            <td class="sx-b-time">{{ b.time ? (b.time | date:'HH:mm') + ' · ' + ago(b.time) : '' }}</td>
            <td class="sx-b-hint"><span *ngIf="b.hint">{{ b.hint }}</span></td>
          </tr>
        </tbody></table>
      </section>
      <section class="review-card sx-organ" aria-label="器官功能">
        <div class="review-card-title"><h2><ui-icon name="activity"/>器官功能</h2><span class="sx-chip" *ngIf="sofa" [attr.data-level]="sofaDelta !== null && sofaDelta >= 2 ? 'critical' : null">SOFA <b>{{ sofa.value }}</b><small *ngIf="sofaDelta !== null">24h {{ sofaDelta >= 0 ? '+' : '' }}{{ sofaDelta }}</small><small>{{ sofa.eventTime | date:'HH:mm' }}</small></span><span class="sx-chip" *ngIf="!sofa">SOFA <small>宿主未评分</small></span></div>
        <table class="sx-table"><tbody>
          <tr *ngFor="let o of organs" [attr.data-level]="o.level">
            <th scope="row">{{ o.system }}</th><td class="sx-o-item">{{ o.item }}</td>
            <td class="sx-o-value"><button class="text-button" *ngIf="o.evidence" (click)="selected = o.evidence">{{ o.value }}</button><span *ngIf="!o.evidence">{{ o.value }}</span></td>
            <td class="sx-o-note">{{ o.note }}</td>
          </tr>
          <tr class="sx-infection" [attr.data-level]="infectionHit ? 'abnormal' : 'none'"><th scope="row">感染</th><td class="sx-o-item">诊断 / 培养</td><td class="sx-o-value" colspan="2">{{ infectionHit ? '诊断含感染相关描述' : '未取得感染证据' }}<small> · 培养结果未接入</small></td></tr>
        </tbody></table>
      </section>
    </div>

    <details class="review-card sx-fold" *ngIf="data?.available" (toggle)="showTrends = $any($event.target).open">
      <summary><ui-icon name="chart"/>生命体征趋势<small>与宿主床旁记录同源</small></summary>
      <clinical-trends *ngIf="showTrends" [items]="observations" [asOf]="asOf" [embedded]="true" [availableHours]="24" [compact]="true" [busy]="loading" (openRecords)="openEvidence.emit()" (openHistory)="openTrends.emit()"/>
    </details>
    <details class="sx-limits" *ngIf="limits.length" aria-label="数据限制"><summary><ui-icon name="info"/>数据限制 {{ limits.length }} 项</summary><ul><li *ngFor="let l of limits">{{ l }}</li></ul>
      <div class="review-table-wrap" *ngIf="data?.contextEvidence?.length"><table><thead><tr><th>未确认归属的报告</th><th>报告值</th><th>报告时间</th><th></th></tr></thead><tbody><tr *ngFor="let e of data!.contextEvidence"><td>{{ featureName(e.code) }}</td><td>{{ e.value }} {{ e.unit || '' }}</td><td>{{ e.eventTime | date:'MM-dd HH:mm' }}</td><td><button class="text-button" (click)="selected=e">来源</button></td></tr></tbody></table></div>
    </details>
    <section class="review-evidence-detail" *ngIf="selected" aria-label="来源记录"><div class="review-card-title"><h2><ui-icon name="file"/>{{ selected.label || featureName(selected.code) }}</h2><button (click)="selected=null">关闭</button></div><div class="evidence-reading">{{ selected.value }}<small>{{ selected.unit || '单位未知' }}</small></div><dl><dt>{{ selected.timeBasis==='reported' ? '报告 / 审核时间' : '观测时间' }}</dt><dd>{{ selected.eventTime | date:'yyyy-MM-dd HH:mm:ss' }}</dd><dt>来源系统</dt><dd>{{ selected.sourceSystem || '未标注' }}</dd><dt>来源记录</dt><dd>{{ selected.sourceRecordId || '未标注' }}</dd><dt *ngIf="selected.sourceParameterCode">源指标</dt><dd *ngIf="selected.sourceParameterCode">{{ selected.sourceParameterCode }}</dd></dl><p class="context-warning" *ngIf="selected.qualityNote">{{ selected.qualityNote }}</p></section>
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
  data: ClinicalReview | null = null;
  observations: Observation[] = [];
  alertTotal: number | null = null;
  taskTotal: number | null = null;
  loading = false; error = ''; observationsError = ''; alertsError = false; tasksError = false;
  asOf = ''; updatedAt = ''; selected: Evidence | null = null; showTrends = false;
  tiles: Tile[] = []; bundle: BundleRow[] = []; organs: OrganRow[] = []; limits: string[] = [];
  sofa: Observation | null = null; sofaDelta: number | null = null;
  headline = ''; headDetail = ''; headLevel: Level | 'unknown' = 'unknown';
  private generation = 0;
  featureName = featureLabel;
  abs = Math.abs;
  get nurseFirst() { return this.api.has('NURSE') && !this.api.has('DOCTOR'); }
  get hasAnyValue() { return this.tiles.some(t => t.value !== null); }
  get infectionHit() { return !!this.data?.infectionClue && this.data.infectionClue.includes('含感染'); }
  ago(t: string | null) { return ago(t, Date.parse(this.asOf) || Date.now()); }
  ngOnChanges() { this.data = null; this.observations = []; this.selected = null; this.showTrends = false; this.derive(); void this.load(); }
  ngOnDestroy() { ++this.generation; }
  async load() {
    const generation = ++this.generation; if (!this.patientId) return;
    this.loading = true; this.error = ''; this.observationsError = ''; this.selected = null;
    // Clear stale counts/data before refresh. A failed request must never look like a zero count.
    this.data = null; this.observations = []; this.alertTotal = null; this.taskTotal = null; this.derive();
    const base = '/patients/' + encodeURIComponent(this.patientId);
    const results = await Promise.allSettled([
      this.api.get<ClinicalReview>(base + '/sepsis-assessment'),
      this.api.get<{ items: Observation[]; asOf: string }>(base + '/clinical-data?hours=24'),
      this.api.get<Page<Alert>>('/alerts?status=pending&size=1'),
      this.api.get<Page<Task>>('/bundles/tasks?metric=pending&patientId=' + encodeURIComponent(this.patientId) + '&size=1'),
    ] as const);
    if (generation !== this.generation) return;
    const [review, observations, alerts, tasks] = results;
    if (review.status === 'fulfilled' && review.value.schemaVersion === 2 && matchesPatient(review.value, this.patientId, this.encounterId)) this.data = review.value;
    else this.error = review.status === 'rejected' ? errorMessage(review.reason) : '患者上下文或接口版本不一致，已停止展示。';
    if (observations.status === 'fulfilled') { this.observations = observations.value.items; this.asOf = observations.value.asOf; }
    else { this.observationsError = '观测数据读取失败：' + errorMessage(observations.reason); this.asOf = this.data?.asOf || new Date().toISOString(); }
    this.alertsError = alerts.status === 'rejected'; this.tasksError = tasks.status === 'rejected';
    if (alerts.status === 'fulfilled') this.alertTotal = alerts.value.total;
    if (tasks.status === 'fulfilled') this.taskTotal = tasks.value.total;
    this.updatedAt = new Date().toISOString(); this.loading = false;
    this.derive();
    this.status.emit({ patientId: this.patientId, level: this.headLevel, summary: this.headline, pendingAlerts: this.alertTotal, pendingTasks: this.taskTotal });
  }

  /** All derived view state is computed once per load, not in template getters. */
  private derive() {
    const d = this.data, asOf = Date.parse(this.asOf) || Date.now();
    if (!d || !d.available) {
      this.tiles = []; this.bundle = []; this.organs = []; this.limits = d?.sourceWarnings || []; this.sofa = null; this.sofaDelta = null;
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
      this.headline = flagged.slice(0, 4).map(t => `${shortName[t.code] || t.label} ${t.value} ${t.word}`.trim()).join(' · ');
    } else if (d.missingFeatures.length) { this.headLevel = 'unknown'; this.headline = '关键数据不全'; }
    else { this.headLevel = 'normal'; this.headline = '主要指标未超出提示阈值'; }
    const missing = d.missingFeatures.map(featureLabel);
    this.headDetail = missing.length ? '缺：' + missing.join('、') + '（缺项不代表正常）' : flagged.length > 4 ? '另有 ' + (flagged.length - 4) + ' 项异常' : '';
    this.bundle = this.bundleRows(d, latest, candidates, asOf);
    this.organs = this.organRows(d, latest);
    const sofa = this.series('SOFA', 'score', 0, 24, asOf);
    this.sofa = sofa.at(-1) || null;
    this.sofaDelta = sofa.length > 1 ? (sofa.at(-1)!.value! - sofa[0].value!) : null;
    this.limits = [...(d.sourceWarnings || []), ...(d.contextEvidence?.length ? ['未确认住院归属的检验报告仅列出，不参与提示和集束化判断。'] : []),
      '提示颜色为显示阈值，不构成诊断；预测模型与摘要服务未配置。'];
  }
  private tile(code: string, latest: Map<string, Evidence>, candidates: Map<string, Evidence>, asOf: number, sbpCode?: string): Tile {
    const e = latest.get(code) || null, base = baseCode(code);
    const unit = units[base] || e?.unit || '';
    const pts = this.series(code, e?.unit || measures.find(m => m.code === base)?.unit || '', -1e9, 1e9, asOf, 12);
    const value = e ? e.value : null;
    const prev = pts.length > 1 ? pts[pts.length - 2].value : null;
    let sub = '';
    if (sbpCode) { const s = latest.get(sbpCode); sub = '收缩压 ' + (s ? s.value : '—'); }
    if (base === 'MAP') sub += (sub ? ' · ' : '') + '升压药 ' + (this.data?.vasopressorActive === true ? '在用' : this.data?.vasopressorActive === false ? '未用' : '无记录');
    const delta = value !== null && prev !== null && e && pts.at(-1)?.eventTime === e.eventTime ? Math.round((value - prev) * 10) / 10 : null;
    return { code, label: tileName[code] || featureLabel(code), unit, value, time: e?.eventTime || null, level: level(code, value), word: value === null ? '' : direction(code, value),
      delta, spark: this.sparkPath(pts), sub, evidence: e, candidate: e ? null : candidates.get(code) || null };
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
      { key: 'VASOPRESSOR', name: '血管活性药', state: vaso === true ? 'done' : mapLow ? 'warn' : 'none', text: vaso === true ? '有在用记录' : vaso === false ? '记录为未用' : '未取得记录', time: null, hint: mapLow ? 'MAP<65' : '', tip: '仅反映源系统记录，不判断剂量或达标' },
      { key: 'REASSESS', name: '容量 / 灌注再评估', state: 'none', text: '未取得记录', time: null, hint: '', tip: note('REASSESS') },
    ];
  }
  private organRows(d: ClinicalReview, latest: Map<string, Evidence>): OrganRow[] {
    const maps = this.tiles.filter(t => baseCode(t.code) === 'MAP' && t.value !== null);
    const worstMap = maps.sort((a, b) => levelRank[a.level] - levelRank[b.level])[0];
    const gcs = latest.get('GCS'), rr = latest.get('RR'), spo2 = latest.get('SPO2');
    const vaso = d.vasopressorActive === true ? '升压药在用' : d.vasopressorActive === false ? '升压药未用' : '升压药无记录';
    return [
      { system: '循环', item: 'MAP / 升压药', value: maps.length ? maps.map(m => (shortName[m.code] || 'MAP') + ' ' + m.value).join('，') : '—', level: worstMap?.level || 'none', note: vaso, evidence: worstMap?.evidence || null },
      { system: '中枢神经', item: 'GCS', value: gcs ? gcs.value + ' 分' : '—', level: level('GCS', gcs?.value), note: gcs ? '需结合镇静状态' : '未取得', evidence: gcs || null },
      { system: '呼吸', item: 'PaO₂/FiO₂', value: '未接入', level: 'none', note: '参考：' + (rr ? '呼吸 ' + rr.value : '呼吸 —') + ' · ' + (spo2 ? 'SpO₂ ' + spo2.value + '%' : 'SpO₂ —'), evidence: null },
      { system: '凝血', item: '血小板', value: '未接入', level: 'none', note: '', evidence: null },
      { system: '肝脏', item: '胆红素', value: '未接入', level: 'none', note: '', evidence: null },
      { system: '肾脏', item: '肌酐 / 尿量', value: '未接入', level: 'none', note: '', evidence: null },
    ];
  }
}
