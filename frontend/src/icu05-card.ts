import { CommonModule } from '@angular/common';
import { Component, Input, OnChanges, inject } from '@angular/core';
import { Api } from './api';
import { UiIcon } from './ui-icon';

import { Icu05Item, Icu05Result, Icu05Window, icu05Reason, bedsideActions, nextDue, windowChips, BedsideAction, fmtLeft, elapsedSinceT0 } from './icu05-view';
export type { Icu05Item, Icu05Result, Icu05Window, Icu05Gate, Icu05CultureOrder } from './icu05-view';
export { icu05Reason } from './icu05-view';
const TRIGGERS = new Set(['C1', 'C2']);
const ROWS: { code: string; name: string }[] = [
  { code: 'A1', name: '乳酸测定' }, { code: 'A2', name: '复测乳酸' }, { code: 'B2', name: '血培养' }, { code: 'B1', name: '抗菌药物' },
  { code: 'B3', name: '先培养后抗菌' }, { code: 'C1', name: 'MAP<65 触发' }, { code: 'C2', name: '乳酸≥4 触发' }, { code: 'C3', name: '补液' },
];
interface Cell { mark: string; tone: 'done' | 'fail' | 'flag' | 'quiet' | 'wait' | 'none'; time: string | null; tip: string; }
interface Row { code: string; name: string; cells: (Cell | null)[]; }

@Component({
  selector: 'icu05-card', standalone: true, imports: [CommonModule, UiIcon],
  template: `<section class="review-card sx-bundle i5" aria-label="感染性休克集束化">
    <div class="review-card-title">
      <h2><ui-icon name="clipboard"/>感染性休克 Bundle</h2>
      <span class="sx-chip i5-status" *ngIf="view === 'quality' || !applicable" [attr.data-tone]="statusTone" [title]="gateTip">{{ data.gate?.statusLabel || data.statusLabel }}</span>
      <span class="sx-chip i5-t0" *ngIf="data.t0" [title]="'T0：' + (data.t0Source || '') + (data.t0OrderName ? '，' + data.t0OrderName : '')">T0 <b>{{ data.t0 | date:'MM-dd HH:mm' }}</b><small *ngIf="elapsed">已 {{ elapsed }}</small></span>
      <div class="segmented i5-view" role="group" aria-label="Bundle 视图" *ngIf="data.windows.length">
        <button type="button" [class.selected]="view === 'bedside'" (click)="view = 'bedside'" title="按措施列出已做、待做和剩余时间">床旁</button>
        <button type="button" [class.selected]="view === 'quality'" (click)="view = 'quality'" title="ICU-05 质控口径：1h/3h/6h 时窗逐项判定">质控</button>
      </div>
    </div>
    <ng-container *ngIf="view === 'bedside' && data.windows.length">
      <div class="i5-next" *ngIf="next && applicable" [attr.data-urgent]="next.minutesLeft <= 30 ? 'y' : null">
        <span class="i5-next-k">下一步</span><b>{{ next.name }}</b><span class="i5-next-t">{{ next.window }} 时窗 · 剩 <strong>{{ left(next.minutesLeft) }}</strong>（截止 {{ next.deadline | date:'HH:mm' }}）</span>
      </div>
      <p class="i5-next quiet" *ngIf="!next && applicable">各时窗均已结束</p>
      <ng-container *ngIf="applicable"><ng-container *ngTemplateOutlet="acts"/></ng-container>
      <details class="i5-na-b" *ngIf="!applicable"><summary>未达感染性休克判定，措施记录仅供参考</summary><ng-container *ngTemplateOutlet="acts"/></details>
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
    <ng-container *ngIf="view === 'quality' || !data.windows.length">
    <p class="i5-msg" *ngIf="!data.t0">{{ data.message || '未取得 T0，不做 Bundle 判定' }}</p>
    <div class="i5-gate" *ngIf="data.gate as g">
      <span><em>休克</em><ng-container *ngFor="let i of g.shock"><i [attr.data-v]="v(i.value)" [title]="tip(i)">{{ gateText(i) }}</i></ng-container></span>
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
    <details class="sx-limits i5-limits" *ngIf="data.limitations?.length"><summary><ui-icon name="info"/>判定规则与数据来源</summary>
      <ul><li>{{ data.ruleSource }}；{{ data.gate?.rule || '' }}。“—”表示无法判定，不按未完成计。</li><li *ngIf="data.pool">质控分母：{{ data.pool.rule }}（本例：{{ data.pool.eligible === true ? '符合' : data.pool.eligible === false ? '不符合' : '无法确认' }}）</li><li *ngFor="let l of data.limitations">{{ l }}</li></ul>
    </details>
  </section>`,
})
export class Icu05Card implements OnChanges {
  @Input({ required: true }) data!: Icu05Result;
  rows: Row[] = [];
  api = inject(Api);
  view: 'bedside' | 'quality' = this.api.has('QUALITY') && !this.api.has('DOCTOR') ? 'quality' : 'bedside';
  actions: BedsideAction[] = []; next: ReturnType<typeof nextDue> = null; chips: { key: string; state: string; label: string }[] = []; elapsed = ''; failWhy = '';
  readonly mark: Record<string, string> = { done: '✓', fail: '×', todo: '○', unknown: '—' };
  left = fmtLeft;
  statusTone = 'none'; gateTip = ''; applicable = false;
  readonly toneLabel: Record<string, string> = { done: '完成', fail: '未完成', flag: '触发', quiet: '未触发', wait: '时窗未结束', none: '无法判定' };
  readonly infName: Record<string, string> = { I1: '诊断', I2: '抗菌药', I3: '送检' };
  readonly organName: Record<string, string> = { S1: '氧合', S2: 'GCS', S3: 'MAP', S4: '升压药' };
  ngOnChanges() {
    const d = this.data;
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
