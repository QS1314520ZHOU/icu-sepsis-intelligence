import { CommonModule } from '@angular/common';
import { Component, Input, OnChanges } from '@angular/core';
import { UiIcon } from './ui-icon';

/** ICU-05 septic-shock bundle timeline (department director rule set). Tri-state: true / false / null. */
export interface Icu05Item { code: string; label: string; value: boolean | null; time: string | null; detail: string; reason: string | null; }
export interface Icu05Window {
  key: string; hours: number; deadline: string; closed: boolean; finish: boolean | null;
  state: 'met' | 'not_met' | 'unknown' | 'in_progress'; finishPath: string; reasons: string[]; items: Icu05Item[];
}
export interface Icu05Gate {
  organ: Icu05Item[]; infection: Icu05Item[]; shock: Icu05Item[]; hasOrganDysfunction: boolean | null; hasInfection: boolean | null;
  isSepticShock: boolean | null; mapMin: number | null; status: string; statusLabel: string; reason: string | null; rule: string;
}
export interface Icu05Result {
  schemaVersion: number; patientId: string; encounterId: string; available: boolean; message: string; asOf: string;
  ruleVersion: string; ruleSource: string; status: string; statusLabel: string; applicable?: boolean;
  t0: string | null; t0OrderName: string | null; t0Source: string | null;
  pool?: { eligible: boolean | null; icuWithin24h: boolean | null; admissionType: string | null; rule: string };
  gate: Icu05Gate | null; windows: Icu05Window[]; limitations?: string[];
}

export const icu05Reason: Record<string, string> = {
  DATA_MISSING_LAC: '未取到乳酸', DATA_MISSING: '未取到记录', AB_MISSING: '未见抗菌药执行', BC_MISSING: '未见血培养', BC_AFTER_AB: '抗菌药早于血培养',
  FLUID_MISSING: '未见补液执行', FLUID_INSUFFICIENT: '补液量不足', FLUID_AMOUNT_INCOMPLETE: '部分补液记录无量', LACTATE_RECHECK_MISSING: '未复测乳酸',
  SOURCE_ERROR: '来源读取失败', MAP_MISSING: '未取到 MAP', WINDOW_OPEN: '时窗未结束', VASO_STATUS_UNKNOWN: 'T0 前用药是否持续未知',
  MAP_NOT_MET: 'MAP<70', A1_NOT_MET: '乳酸未测',
  NO_ORGAN_DYSFUNCTION: '未见器官功能障碍证据', NOT_SEPTIC_SHOCK: '未达休克判定', NO_INFECTION_EVIDENCE: '未取得感染证据', INSUFFICIENT_EVIDENCE: '证据不全',
};
const TRIGGERS = new Set(['C1', 'C2']);
const ROWS: { code: string; name: string }[] = [
  { code: 'A1', name: '乳酸测定' }, { code: 'A2', name: '复测乳酸' }, { code: 'B2', name: '血培养' }, { code: 'B1', name: '抗菌药物' },
  { code: 'B3', name: '先培养后抗菌' }, { code: 'C1', name: 'MAP<70 触发' }, { code: 'C2', name: '乳酸≥4 触发' }, { code: 'C3', name: '补液' },
];
interface Cell { mark: string; tone: 'done' | 'fail' | 'flag' | 'quiet' | 'wait' | 'none'; time: string | null; tip: string; }
interface Row { code: string; name: string; cells: (Cell | null)[]; }

@Component({
  selector: 'icu05-card', standalone: true, imports: [CommonModule, UiIcon],
  template: `<section class="review-card sx-bundle i5" aria-label="感染性休克集束化">
    <div class="review-card-title">
      <h2><ui-icon name="clipboard"/>感染性休克 Bundle</h2>
      <span class="sx-chip i5-status" [attr.data-tone]="statusTone" [title]="gateTip">{{ data.gate?.statusLabel || data.statusLabel }}</span>
      <span class="sx-chip" *ngIf="data.t0" [title]="'T0：' + (data.t0Source || '') + (data.t0OrderName ? '，' + data.t0OrderName : '')">T0 <b>{{ data.t0 | date:'MM-dd HH:mm' }}</b></span>
    </div>
    <p class="i5-msg" *ngIf="!data.t0">{{ data.message || '未取得 T0，不做 Bundle 判定' }}</p>
    <div class="i5-gate" *ngIf="data.gate as g">
      <span><em>休克</em><ng-container *ngFor="let i of g.shock; let last = last"><i [attr.data-v]="v(i.value)" [title]="tip(i)">{{ i.label }} {{ i.detail ? short(i.detail) : sym(i.value) }}</i></ng-container></span>
      <span><em>感染</em><i *ngFor="let i of g.infection" [attr.data-v]="v(i.value)" [title]="tip(i)">{{ infName[i.code] }} {{ sym(i.value) }}</i></span>
      <span><em>器官</em><i *ngFor="let i of g.organ" [attr.data-v]="v(i.value)" [title]="tip(i)">{{ organName[i.code] }} {{ i.detail ? short(i.detail) : sym(i.value) }}</i></span>
    </div>
    <ng-container *ngIf="data.windows.length">
      <details class="i5-na" *ngIf="!applicable" ><summary>未达纳入条件，时窗结果仅供参考</summary><ng-container *ngTemplateOutlet="grid"/></details>
      <ng-container *ngIf="applicable"><ng-container *ngTemplateOutlet="grid"/></ng-container>
    </ng-container>
    <ng-template #grid>
      <table class="sx-table i5-table"><thead><tr><th scope="col"></th>
        <th scope="col" *ngFor="let w of data.windows" [attr.data-state]="w.state" [title]="windowTip(w)">
          <span class="i5-wkey">{{ w.key }}</span><span class="i5-wstate">{{ stateLabel(w) }}</span><small>截止 {{ w.deadline | date:'HH:mm' }}</small>
        </th></tr></thead>
        <tbody><tr *ngFor="let r of rows"><th scope="row">{{ r.name }}</th>
          <td *ngFor="let c of r.cells" [attr.data-tone]="c?.tone || 'none'" [title]="c?.tip || ''"><ng-container *ngIf="c"><b>{{ c.mark }}</b><small *ngIf="c.time">{{ c.time | date:'HH:mm' }}</small></ng-container></td>
        </tr></tbody>
      </table>
    </ng-template>
    <details class="sx-limits i5-limits" *ngIf="data.limitations?.length"><summary><ui-icon name="info"/>判定规则与数据来源</summary>
      <ul><li>{{ data.ruleSource }}；{{ data.gate?.rule || '' }}。“—”表示无法判定，不按未完成计。</li><li *ngIf="data.pool">质控分母：{{ data.pool.rule }}（本例：{{ data.pool.eligible === true ? '符合' : data.pool.eligible === false ? '不符合' : '无法确认' }}）</li><li *ngFor="let l of data.limitations">{{ l }}</li></ul>
    </details>
  </section>`,
})
export class Icu05Card implements OnChanges {
  @Input({ required: true }) data!: Icu05Result;
  rows: Row[] = [];
  statusTone = 'none'; gateTip = ''; applicable = false;
  readonly infName: Record<string, string> = { I1: '诊断', I2: '抗菌药', I3: '送检' };
  readonly organName: Record<string, string> = { S1: '氧合', S2: 'GCS', S3: 'MAP', S4: '升压药' };
  ngOnChanges() {
    const d = this.data;
    this.applicable = d.applicable === true || d.status === 'septic_shock' || d.status === 'suspected_review';
    this.statusTone = d.status === 'septic_shock' ? 'critical' : d.status === 'suspected_review' ? 'abnormal' : 'none';
    this.gateTip = d.gate?.reason ? icu05Reason[d.gate.reason] || d.gate.reason : '';
    this.rows = ROWS.map(r => ({ code: r.code, name: r.name, cells: d.windows.map(w => this.cell(w, r.code)) }))
      .filter(r => r.cells.some(c => c));
  }
  private cell(w: Icu05Window, code: string): Cell | null {
    const i = w.items.find(x => x.code === code);
    if (!i) return null;
    const trigger = TRIGGERS.has(code);
    const tip = [i.label, i.detail, i.reason ? icu05Reason[i.reason] || i.reason : ''].filter(Boolean).join(' · ');
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
  tip(i: Icu05Item) { return [i.label, i.detail, i.reason ? icu05Reason[i.reason] || i.reason : ''].filter(Boolean).join(' · '); }
  v(x: boolean | null) { return x === true ? 'y' : x === false ? 'n' : 'u'; }
  sym(x: boolean | null) { return x === true ? '✓' : x === false ? '×' : '—'; }
  short(s: string) { return s.replace(/^最低 /, '').replace(/（T0 前开始.*$/, '').slice(0, 14); }
}
