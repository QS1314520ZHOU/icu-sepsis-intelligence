import { CommonModule } from '@angular/common';
import { Component, Input, OnChanges, OnDestroy, inject } from '@angular/core';
import { Api, errorMessage } from './api';
import { FormsModule } from '@angular/forms';
import { uuid4 } from './uuid';
import { Icu05OverridePanel } from './icu05-override-panel';
import { UiIcon } from './ui-icon';

import { Icu05Item, Icu05Result, Icu05Window, icu05Reason, bedsideActions, nextDue, windowChips, Icu05CaseReview, BedsideAction, fmtLeft, elapsedSinceT0 } from './icu05-view';
export type { Icu05Item, Icu05Result, Icu05Window, Icu05Gate, Icu05CultureOrder } from './icu05-view';
export { icu05Reason } from './icu05-view';
const TRIGGERS = new Set(['C1', 'C2']);
const ROWS: { code: string; name: string }[] = [
  { code: 'A1', name: '乳酸测定' }, { code: 'A2', name: '复测乳酸' }, { code: 'B2', name: '血培养' }, { code: 'B1', name: '抗菌药物' },
  { code: 'B3', name: '先培养后抗菌' }, { code: 'C1', name: 'MAP≤65 触发' }, { code: 'C2', name: '乳酸≥4 触发' }, { code: 'C3', name: '补液' },
];
interface Cell { mark: string; tone: 'done' | 'fail' | 'flag' | 'quiet' | 'wait' | 'none'; time: string | null; tip: string; }
interface Row { code: string; name: string; cells: (Cell | null)[]; }

@Component({
  selector: 'icu05-card', standalone: true, imports: [CommonModule, FormsModule, UiIcon, Icu05OverridePanel],
  template: `<section class="review-card sx-bundle i5" aria-label="感染性休克集束化">
    <div class="review-card-title">
      <h2><ui-icon name="clipboard"/>感染性休克 Bundle</h2>
      <span class="sx-chip i5-status" *ngIf="mode === 'quality' || !applicable" [attr.data-tone]="statusTone" [title]="gateTip">{{ data.gate?.statusLabel || data.statusLabel }}</span>
      <span class="sx-chip i5-t0" *ngIf="data.t0" [title]="'T0：' + (data.t0Source || '') + (data.t0OrderName ? '，' + data.t0OrderName : '')">T0 <b>{{ data.t0 | date:'MM-dd HH:mm' }}</b><small *ngIf="elapsed">已 {{ elapsed }}</small></span>
      <div class="segmented i5-view" role="group" aria-label="Bundle 视图" *ngIf="data.windows.length && !api.nurseView">
        <button type="button" [class.selected]="mode === 'bedside'" (click)="view = 'bedside'" title="按措施列出已做、待做和剩余时间">床旁</button>
        <button type="button" [class.selected]="mode === 'quality'" (click)="view = 'quality'" title="ICU-05 质控口径：1h/3h/6h 时窗逐项判定">质控</button>
      </div>
    </div>
    <p class="i5-msg" role="status" *ngIf="k1WindowPending">乳酸取值窗口待确认；K1 无法判定。</p>
    <ng-container *ngIf="mode === 'bedside' && data.windows.length">
      <div class="i5-next" *ngIf="next && applicable" [attr.data-urgent]="next.minutesLeft <= 30 ? 'y' : null">
        <span class="i5-next-k">下一步</span><b>{{ next.name }}</b><span class="i5-next-t">{{ next.window }} 时窗 · 剩 <strong>{{ left(next.minutesLeft) }}</strong>（截止 {{ next.deadline | date:'HH:mm' }}）</span>
      </div>
      <p class="i5-next quiet" *ngIf="!next && applicable">各时窗均已结束</p>
      <ng-container *ngIf="applicable"><ng-container *ngTemplateOutlet="acts"/></ng-container>
      <details class="i5-na-b" *ngIf="!applicable"><summary>未达本地质控候选条件，措施记录仅供参考</summary><ng-container *ngTemplateOutlet="acts"/></details>
      <ng-template #acts>
      <ul class="i5-acts">
        <li *ngFor="let a of actions" [attr.data-state]="a.state" [title]="a.time ? (a.time | date:'MM-dd HH:mm') : ''">
          <i aria-hidden="true">{{ mark[a.state] }}</i><span class="i5-a-name">{{ a.name }}</span>
          <span class="i5-a-rel">{{ a.state === 'todo' ? '待做' : a.state === 'unknown' ? '无法判定' : a.state === 'fail' ? '未完成' : a.rel }}</span>
          <span class="i5-a-detail">{{ a.detail }}</span><em class="i5-a-warn" *ngIf="a.warn">{{ a.warn }}</em>
        </li>
      </ul>
      </ng-template>
      <p class="i5-wins" *ngIf="applicable"><span *ngFor="let c of chips" [attr.data-state]="c.state">{{ c.key }} <b>{{ c.label }}</b></span><small class="i5-wins-why" *ngIf="failWhy">未达标：{{ failWhy }}</small></p>
    </ng-container>
    <ng-container *ngIf="mode === 'quality' || !data.windows.length">
    <p class="i5-msg" *ngIf="!data.t0">{{ data.message || '未取得 T0，不做 Bundle 判定' }}</p>
    <div class="i5-gate" *ngIf="data.gate as g">
      <span><em>质控候选</em><ng-container *ngFor="let i of g.shock"><i [attr.data-v]="v(i.value)" [title]="tip(i)">{{ gateText(i) }}</i></ng-container></span>
      <span><em>感染</em><i *ngFor="let i of g.infection" [attr.data-v]="v(i.value)" [title]="tip(i)">{{ infName[i.code] }} {{ sym(i.value) }}</i></span>
      <span><em>器官</em><i *ngFor="let i of g.organ" [attr.data-v]="v(i.value)" [title]="tip(i)">{{ organText(i) }}</i></span>
    </div>
    <ng-container *ngIf="data.windows.length">
      <details class="i5-na" *ngIf="!applicable" ><summary>未达纳入条件，时窗结果仅供参考</summary><ng-container *ngTemplateOutlet="grid"/></details>
      <ng-container *ngIf="applicable"><ng-container *ngTemplateOutlet="grid"/></ng-container>
    </ng-container>
    <ng-template #grid>
      <table class="sx-table i5-table"><thead><tr><th scope="col"></th>
        <th scope="col" *ngFor="let w of data.windows" [attr.data-state]="w.state" [title]="'截止 ' + (w.deadline | date:'MM-dd HH:mm') + (windowTip(w) ? ' · ' + windowTip(w) : '')">
          <span class="i5-wkey">{{ w.key }}</span><span class="i5-wstate">{{ stateLabel(w) }}</span>          <small class="i5-why" *ngIf="w.state === 'not_met' || w.state === 'unknown'">{{ windowTip(w) }}</small>
        </th></tr></thead>
        <tbody><tr *ngFor="let r of rows"><th scope="row">{{ r.name }}</th>
          <td *ngFor="let c of r.cells" [attr.data-tone]="c?.tone || 'none'" [title]="c?.tip || ''" [attr.aria-label]="r.name + ' ' + (c ? toneLabel[c.tone] + ' ' + (c.tip || '') : '无')"><ng-container *ngIf="c"><b>{{ c.mark }}</b></ng-container></td>
        </tr></tbody>
      </table>
      <p class="i5-legend" aria-label="图例" title="各时窗均自 T0 起独立判定；悬停单元格查看依据与时间"><span data-tone="done"><b>✓</b>完成</span><span data-tone="fail"><b>×</b>未完成</span><span data-tone="wait"><b>待</b>时窗未结束</span><span data-tone="none"><b>—</b>无法判定</span><span data-tone="flag"><b>异常</b>触发项成立</span></p>
    </ng-template>
    </ng-container>
    @defer (on idle) { <icu05-override-panel [data]="data" (changed)="acceptOverrides($event)"/> }
    @placeholder { <p>正在加载复核工具…</p> }
    <section class="i5-case-review" *ngIf="data.caseReview">
      <h3>病例人工复核</h3>
      <p>{{ data.caseReview.message }}</p>
      <p *ngIf="data.caseReview.active as r">{{ r.operator }} · {{ r.createdAt | date:'yyyy-MM-dd HH:mm:ss' }} · {{ r.reasonText }}
        · 复核时自动分母资格 {{ r.autoValue === true ? '成立' : r.autoValue === false ? '不成立' : '无法判定' }}</p>
      <ng-container *ngIf="api.has('DOCTOR') || api.has('QUALITY')">
        <button type="button" *ngIf="!reviewMode && !data.caseReview.active" (click)="startReview('exclude')" [disabled]="reviewBusy || !data.t0">人工排除此病例</button>
        <button type="button" *ngIf="!reviewMode && data.caseReview.active" (click)="startReview('revoke')" [disabled]="reviewBusy">撤销病例排除</button>
        <form *ngIf="reviewMode" (ngSubmit)="submitReview()">
          <p>{{ reviewMode === 'exclude' ? '排除后，此病例不计入本地质控分子和分母；自动结果仍保留。' : '撤销后按当前自动结果重新计算；不强行纳入未知病例。' }}</p>
          <label *ngIf="reviewMode === 'exclude'">排除原因
            <select name="reasonCode" [(ngModel)]="reviewReasonCode" [disabled]="reviewBusy">
              <option value="not_septic_shock">非脓毒性休克</option><option value="outside_treated">外院已治疗</option>
              <option value="dnr">放弃积极治疗</option><option value="t0_wrong">T0 锚点错误</option><option value="data_error">数据错误</option><option value="other">其他</option>
            </select></label>
          <label>复核说明<textarea name="reviewReason" [(ngModel)]="reviewReason" maxlength="1000" required [disabled]="reviewBusy"></textarea></label>
          <button type="button" (click)="cancelReview()" [disabled]="reviewBusy">取消</button>
          <button type="submit" [disabled]="reviewBusy || !reviewReason.trim()">{{ reviewBusy ? '正在保存…' : '确认并记录' }}</button>
        </form>
        <button type="button" (click)="loadReviewHistory()" [disabled]="reviewBusy">查看复核记录</button>
        <ul *ngIf="reviewHistory"><li *ngFor="let r of reviewHistory">{{ r.createdAt | date:'MM-dd HH:mm:ss' }} · {{ r.operator }} · {{ r.reasonText }}
          <span *ngIf="r.revokedAt"> · 已撤销 {{ r.revokedAt | date:'MM-dd HH:mm:ss' }} · {{ r.revokedBy }} · {{ r.revokeReason }}</span>
          · 复核时自动分母资格 {{ r.autoValue === true ? '成立' : r.autoValue === false ? '不成立' : '无法判定' }} · 人工排除 · 规则 {{ r.ruleVersion }}</li></ul>
      </ng-container>
      <p class="review-inline-error" role="alert" *ngIf="reviewError">{{ reviewError }}</p>
    </section>
    <details class="sx-limits i5-limits" *ngIf="data.limitations?.length"><summary><ui-icon name="info"/>判定规则与数据来源</summary>
      <ul><li>规则版本：{{ data.ruleVersion }}</li><li>{{ data.ruleSource }}；{{ data.gate?.rule || '' }}。“—”表示无法判定，不按未完成计。</li><li *ngIf="data.pool">质控分母：{{ data.pool.rule }}（本例：{{ data.pool.eligible === true ? '符合' : data.pool.eligible === false ? '不符合' : '无法确认' }}）</li><li *ngFor="let l of data.limitations">{{ l }}</li></ul>
      <div *ngIf="data.gate as g">
        <h3>质控候选乳酸依据</h3>
        <ng-container *ngFor="let i of g.shock"><div *ngIf="i.code === 'K1'">
          <p>{{ i.label }}：{{ sym(i.value) }}<span *ngIf="i.manual">（人工覆盖，自动值 {{ i.autoValue === true ? '成立' : i.autoValue === false ? '不成立' : '无法判定' }}）</span> · {{ i.detail || '无可判定选中值' }}
            <span *ngIf="i.reason"> · {{ reasonText(i.reason) }}</span></p>
          <p *ngIf="i.time">{{ i.timeMeaning || '记录时间（类型未确认）' }}：{{ i.time | date:'yyyy-MM-dd HH:mm:ss' }} · 来源：{{ i.sourceId || '未提供' }}</p>
          <p *ngIf="i.windowConfirmed">窗口：{{ i.windowFrom | date:'yyyy-MM-dd HH:mm:ss' }} 至 {{ i.windowTo | date:'yyyy-MM-dd HH:mm:ss' }}
            · 本次评估至 {{ i.evaluatedTo | date:'yyyy-MM-dd HH:mm:ss' }} · 依据：{{ i.windowSource }}</p>
          <p>{{ i.scope }}</p>
        </div></ng-container>
      </div>
      <details *ngIf="data.lactateRecords?.length">
        <summary>已读取的乳酸原始记录（尚非已确认的 K1 窗口）</summary>
        <ul><li *ngFor="let r of data.lactateRecords">
          原始值：{{ r.rawValue ?? '未提供' }} · 原始单位：{{ r.rawUnit || '未提供' }}
          <strong *ngIf="r.unitConfirmed === false">单位未确认，未参与计算</strong>
          · {{ r.timeMeaning }}：{{ r.time | date:'yyyy-MM-dd HH:mm:ss' }} · 来源：{{ r.sourceId || '未提供' }}
        </li></ul>
      </details>
      <div *ngFor="let w of data.windows">
        <h3>{{ w.key }} 自动判定依据</h3>
        <ul><li *ngFor="let i of w.items">{{ i.label }}：{{ sym(i.value) }} · {{ i.detail || '无可用记录' }}
          <span *ngIf="i.time"> · {{ i.timeMeaning || '记录时间' }} {{ i.time | date:'MM-dd HH:mm:ss' }}</span>
          <span *ngIf="i.selection === 'latest'"> · 窗口内最新记录</span>
          <span *ngIf="i.earliestTime"> · 首条 {{ i.earliestTime | date:'MM-dd HH:mm:ss' }}</span>
          <span *ngIf="i.sourceId"> · 来源 {{ i.sourceId }}</span>
        </li></ul>
      </div>
    </details>
  </section>`,
})
export class Icu05Card implements OnChanges, OnDestroy {
  @Input({ required: true }) data!: Icu05Result;
  rows: Row[] = [];
  reviewMode: 'exclude' | 'revoke' | null = null;
  reviewReason = ''; reviewReasonCode = 'other'; reviewBusy = false; reviewError = '';
  reviewHistory: Icu05CaseReview[] | null = null;
  private reviewActive: Icu05CaseReview | null = null;
  private reviewKey = ''; private reviewFingerprint = ''; private reviewIdentity = ''; private disposed = false;
  acceptOverrides(data: Icu05Result) { this.data=data; this.ngOnChanges(); }
  private identity() { return this.data.patientId + ':' + this.data.encounterId; }
  startReview(mode: 'exclude' | 'revoke') {
    if(this.api.icu05ReviewBusy || this.api.icu05ReviewDirty) return;
    this.reviewActive=this.data.caseReview?.active ? {...this.data.caseReview.active} : null;
    this.reviewMode=mode; this.reviewReason=''; this.reviewError=''; this.reviewKey=uuid4();
    this.reviewFingerprint=this.data.automaticFingerprint || ''; this.reviewIdentity=this.identity(); this.api.icu05ReviewDirty=true;
  }
  cancelReview() { if(this.reviewBusy) return; this.reviewMode=null; this.reviewReason=''; this.api.icu05ReviewDirty=false; }
  ngOnDestroy() { this.disposed=true; this.api.icu05ReviewDirty=false; }
  async submitReview() {
    if(!this.reviewMode || this.reviewBusy || !this.reviewReason.trim() || this.reviewIdentity!==this.identity()) return;
    const identity=this.identity(), patientId=this.data.patientId, mode=this.reviewMode, active=this.reviewActive;
    const path='/patients/'+encodeURIComponent(patientId)+'/icu05-case-reviews';
    const payload=mode==='exclude'?{expectedFingerprint:this.reviewFingerprint,reasonCode:this.reviewReasonCode,reasonText:this.reviewReason}:
      {expectedVersion:active?.version,reasonText:this.reviewReason};
    this.reviewBusy=true; this.api.icu05ReviewBusy=true; this.reviewError='';
    try {
      await this.api.post(path+(mode==='revoke'?'/'+encodeURIComponent(active!.id)+'/revoke':''),payload,this.reviewKey);
      if(this.disposed || identity!==this.identity()) return;
      this.reviewMode=null; this.reviewReason=''; this.api.icu05ReviewDirty=false; this.reviewHistory=null;
      const data=await this.api.get<Icu05Result>('/patients/'+encodeURIComponent(patientId)+'/icu05-bundle');
      if(!this.disposed && identity===this.identity()) { this.data=data; this.ngOnChanges(); }
    } catch(error) { if(!this.disposed && identity===this.identity()) this.reviewError=errorMessage(error); }
    finally { this.reviewBusy=false; this.api.icu05ReviewBusy=false; }
  }
  async loadReviewHistory() {
    const identity=this.identity();
    try { const history=await this.api.get<Icu05CaseReview[]>('/patients/'+encodeURIComponent(this.data.patientId)+'/icu05-case-reviews');
      if(!this.disposed && identity===this.identity()) this.reviewHistory=history;
    } catch(error) { if(!this.disposed && identity===this.identity()) this.reviewError=errorMessage(error); }
  }
  get k1WindowPending(): boolean { return this.data.gate?.shock.some(i => i.code === 'K1' && i.reason === 'K1_WINDOW_UNCONFIRMED') ?? false; }
  api = inject(Api);
  /** 护士视图固定床旁待办，不出现质控矩阵。 */
  get mode(): 'bedside' | 'quality' { return this.api.nurseView ? 'bedside' : this.view; }
  view: 'bedside' | 'quality' = this.api.has('QUALITY') && !this.api.has('DOCTOR') ? 'quality' : 'bedside';
  actions: BedsideAction[] = []; next: ReturnType<typeof nextDue> = null; chips: { key: string; state: string; label: string }[] = []; elapsed = ''; failWhy = '';
  readonly mark: Record<string, string> = { done: '✓', fail: '×', todo: '○', unknown: '—' };
  left = fmtLeft;
  statusTone = 'none'; gateTip = ''; applicable = false;
  readonly toneLabel: Record<string, string> = { done: '完成', fail: '未完成', flag: '触发', quiet: '未触发', wait: '时窗未结束', none: '无法判定' };
  readonly infName: Record<string, string> = { I1: '诊断', I2: '抗菌药', I3: '送检' };
  readonly organName: Record<string, string> = { S1: '氧合', S2: 'GCS', S3: 'MAP', S4: '血管活性药' };
  ngOnChanges() {
    const d = this.data;
    if(this.reviewIdentity && this.reviewIdentity!==this.identity()) {
      this.reviewMode=null; this.reviewReason=''; this.reviewHistory=null; this.reviewError=''; this.api.icu05ReviewDirty=false;
    }
    this.applicable = d.applicable === true || d.status === 'septic_shock' || d.status === 'suspected_review';
    this.statusTone = d.status === 'septic_shock' ? 'critical' : d.status === 'suspected_review' ? 'abnormal' : 'none';
    this.gateTip = d.gate?.reason ? icu05Reason[d.gate.reason] || d.gate.reason : '';
    this.rows = ROWS.map(r => ({ code: r.code, name: r.name, cells: d.windows.map(w => this.cell(w, r.code)) }))
      .filter(r => r.cells.some(c => c));
    this.actions = bedsideActions(d); this.next = nextDue(d); this.chips = windowChips(d); this.elapsed = elapsedSinceT0(d);
    this.failWhy = [...new Set(d.windows.filter(w => w.state === 'not_met').flatMap(w => w.reasons).filter(r => r !== 'MAP_NOT_MET').map(r => icu05Reason[r] || r))].join('、');
  }
  private cell(w: Icu05Window, code: string): Cell | null {
    const i = w.items.find(x => x.code === code);
    if (!i) return null;
    const trigger = TRIGGERS.has(code);
    const at = i.time ? new Date(i.time).toTimeString().slice(0, 5) : '';
    const tip = [i.label, i.detail, at ? '时间 ' + at : '', i.reason ? icu05Reason[i.reason] || i.reason : ''].filter(Boolean).join(' · ');
    // 补液：格内写出具体量；仅未达标打红 ×，达标写量 + ✓，量不全不打 ×
    if (code === 'C3') {
      const vol = (i.detail || '').trim();
      if (i.value === true) return { mark: vol || '✓', tone: 'done', time: i.time, tip };
      if (i.value === false) return { mark: (vol ? vol + ' ' : '') + '×', tone: 'fail', time: i.time, tip };
      if (vol) return { mark: vol, tone: i.reason === 'FLUID_AMOUNT_INCOMPLETE' ? 'flag' : 'quiet', time: i.time, tip };
      if (!w.closed) return { mark: '待', tone: 'wait', time: null, tip: tip + ' · 时窗未结束' };
      return { mark: '—', tone: 'none', time: null, tip };
    }
    if (i.value === true) return trigger ? { mark: '异常', tone: 'flag', time: i.time, tip } : { mark: '✓', tone: 'done', time: i.time, tip };
    if (i.value === false) return trigger ? { mark: '未触发', tone: 'quiet', time: i.time, tip } : { mark: '×', tone: 'fail', time: i.time, tip };
    if (!w.closed && !trigger) return { mark: '待', tone: 'wait', time: null, tip: tip + ' · 时窗未结束' };
    return { mark: '—', tone: 'none', time: null, tip };
  }
  stateLabel(w: Icu05Window) {
    if (w.state === 'met') return '达标';
    if (w.state === 'not_met') return '未达标';
    if (w.state === 'in_progress') { const m = Math.max(0, Math.round((Date.parse(w.deadline) - Date.parse(this.data.asOf)) / 60000)); return '剩 ' + (m >= 60 ? Math.floor(m / 60) + 'h' + (m % 60 ? m % 60 + 'm' : '') : m + 'm'); }
    return '无法判定';
  }
  windowTip(w: Icu05Window) { return w.reasons.filter(r => r !== 'MAP_NOT_MET').map(r => icu05Reason[r] || r).join('、'); }
  reasonText(reason: string) { return icu05Reason[reason] || reason; }
  tip(i: Icu05Item) {
    return [i.label, i.threshold ? '阈值 ' + i.threshold : '', i.detail, i.reason ? icu05Reason[i.reason] || i.reason : '']
      .filter(Boolean)
      .join(' · ');
  }
  /** Shock gate: show measured value only; colour comes from data-v (meets / not / unknown). */
  gateText(i: Icu05Item): string {
    if (i.detail) return i.label + ' ' + this.short(i.detail);
    if (i.label === '需要升压药' || i.code === 'K2') return i.label + ' ' + this.sym(i.value);
    return i.label + ' ' + this.sym(i.value);
  }
  /** Organ row: keep full vasopressor drug name; other items may shorten. */
  organText(i: Icu05Item): string {
    const name = this.organName[i.code] || i.label;
    if (!i.detail) return name + ' ' + this.sym(i.value);
    // 升压药：写全药名（去掉 T0 前开始的长注释，保留药名本身）
    if (i.code === 'S4' || i.code === 'K2') {
      return name + ' ' + i.detail.replace(/（T0 前开始.*$/, '').trim();
    }
    return name + ' ' + this.short(i.detail);
  }
  v(x: boolean | null) { return x === true ? 'y' : x === false ? 'n' : 'u'; }
  sym(x: boolean | null) { return x === true ? '✓' : x === false ? '×' : '—'; }
  short(s: string) { return s.replace(/^最低 /, '').replace(/（T0 前开始.*$/, '').slice(0, 18); }
}
