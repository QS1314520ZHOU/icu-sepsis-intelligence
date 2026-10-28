import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output, OnChanges, SimpleChanges } from '@angular/core';
import { UiIcon } from './ui-icon';
import type { Observation } from './api';
import { measuresFor, pointsFor, segments, stale, timeLabel, evidenceNote, Measure } from './trends';

interface Plot {
  m: Measure;
  points: Observation[];
  paths: string[];
  low: number;
  high: number;
  last?: Observation;
  stale: boolean;
  excluded: number;
}

@Component({
  selector: 'clinical-trends',
  standalone: true,
  imports: [CommonModule, UiIcon],
  template: ` <section class="card trend-section">
    <div class="section-head">
      <div>
        <h2><ui-icon name="chart"/>观测趋势 <span class="neutral-tag">观测值</span><span class="caution-tag" *ngIf="hours > 24">含历史记录</span></h2>
      </div>
      <div class="segmented" role="group" aria-label="趋势时间范围">
        <button
          *ngFor="let h of hourOptions"
          [class.selected]="hours === h"
          [attr.aria-pressed]="hours === h"
          (click)="setHours(h)"
        >
          {{ rangeLabel(h) }}
        </button>
      </div>
    </div>
    <div class="chart-caption"><span><ui-icon name="clock"/>{{ from | date:'MM-dd HH:mm' }} — {{ end | date:'MM-dd HH:mm' }}</span><span class="report-chip" *ngIf="contextCount">{{ contextCount }} 条候选报告不参与绘图</span><button class="text-button" *ngIf="embedded" (click)="openRecords.emit()">原始记录 <ui-icon name="arrow"/></button></div>
    <div class="read-limit" *ngIf="items.length >= 2000"><ui-icon name="info"/>读取量较大，当前结果可能截断；不代表所选时段完整数据。</div>
    <div class="chart-loading" *ngIf="busy" role="status"><span class="spinner"></span>正在读取所选时段…</div>
    <div class="trend-empty-summary" *ngIf="!hasPlottable && !busy">
      <div class="missing-metrics"><div *ngFor="let p of plots"><span>{{ p.m.label }}</span><strong>—<small>{{ p.m.unit }}</small></strong><em>无可用记录</em></div></div>
      <div class="empty-chart-action"><ui-icon name="empty"/><div><strong>近 {{ rangeLabel(hours) }}暂无可绘制记录</strong><p>缺项不代表正常，可扩大范围查找已有记录</p></div><div class="empty-chart-buttons"><button class="primary" (click)="expandRange()">{{ compact ? '查看历史记录' : hours < 168 ? '查看近 7 天' : hours < 720 ? '查看近 30 天' : '查看原始记录' }} <ui-icon name="arrow"/></button><button (click)="openRecords.emit()">核对原始记录</button></div></div>
    </div>
    <div class="trend-grid" *ngIf="hasPlottable && !busy">
      <article class="trend-tile" *ngFor="let p of plots">
        <div class="trend-title">
          <div>
            <span>{{ p.m.label }}</span>
            <small *ngIf="!embedded">{{ p.m.code }} · {{ p.m.unit }}</small>
          </div>
          <span class="tag" *ngIf="!p.last">无数据</span>
          <span class="tag warning" *ngIf="p.last && p.stale">过期</span>
        </div>
        <div class="trend-reading" >
          <strong>{{ p.last?.value ?? '—' }}</strong><small>{{ p.m.unit }}</small>
          <span *ngIf="p.last">{{ p.last.eventTime | date: 'MM-dd HH:mm' }}</span>
        </div>
        <svg
          *ngIf="p.points.length"
          viewBox="0 0 320 158"
          role="img"
          [attr.aria-label]="p.m.label + ' ' + p.points.length + ' 次观测'"
        >
          <g *ngFor="let tick of [0, 1, 2]">
            <line
              x1="36"
              x2="310"
              [attr.y1]="20 + tick * 48"
              [attr.y2]="20 + tick * 48"
              stroke="#e5ebf2"
              stroke-dasharray="3 4"
            />
            <text x="29" [attr.y]="24 + tick * 48" text-anchor="end" fill="#7b8794" font-size="12">
              {{ p.high - ((p.high - p.low) * tick) / 2 | number: '1.0-1' }}
            </text>
          </g>
          <path
            *ngFor="let path of p.paths"
            [attr.d]="path"
            fill="none"
            [attr.stroke]="p.m.color"
            stroke-width="2"
            stroke-linejoin="round"
            stroke-linecap="round"
          />
          <circle
            *ngFor="let o of p.points"
            [attr.cx]="x(o)"
            [attr.cy]="y(o, p)"
            r="3"
            [attr.fill]="p.m.color"
            tabindex="0"
            role="button"
            (click)="choose(o)"
            (keydown.enter)="choose(o)"
          >
            <title>
              {{ o.value }} {{ o.unit }} · {{ o.eventTime | date: 'MM-dd HH:mm' }}
              {{ o.sourceSystem }}
            </title>
          </circle>
          <text x="36" y="145" fill="#7b8794" font-size="12">{{ from | date: 'MM-dd HH:mm' }}</text>
          <text x="310" y="145" text-anchor="end" fill="#7b8794" font-size="12">
            {{ end | date: 'MM-dd HH:mm' }}
          </text>
        </svg>
        <div *ngIf="!p.points.length" class="chart-empty">该时间窗无观测</div>
        <div class="trend-foot">
          <span>{{ p.points.length }} 次观测<span *ngIf="p.excluded"> · {{ p.excluded }} 条未绘制</span></span>
          <button *ngIf="p.last" (click)="choose(p.last)">来源 <ui-icon name="arrow"/></button>
        </div>
      </article>
    </div>
    <div class="point-evidence" *ngIf="selected" role="status">
      <div class="section-head">
        <strong>{{ selected.parameterCode }}</strong
        ><button (click)="selected = null">关闭</button>
      </div>
      <dl>
        <dt>数值</dt>
        <dd>{{ selected.value }} {{ selected.unit }}</dd>
        <dt>{{ timeLabel(selected.timeBasis) }}</dt>
        <dd>{{ selected.eventTime | date: 'MM-dd HH:mm' }}</dd>
        <dt>来源</dt>
        <dd>{{ selected.sourceSystem }}</dd>
        <dt>记录 ID</dt><dd>{{ selected.sourceRecordId }}</dd>
        <dt *ngIf="selected.sourceParameterCode">源指标</dt><dd *ngIf="selected.sourceParameterCode">{{ selected.sourceParameterCode }}</dd>
        <dt *ngIf="evidenceNote(selected)">证据限制</dt><dd *ngIf="evidenceNote(selected)">{{ evidenceNote(selected) }}</dd>
      </dl>
    </div>
    <details class="trend-method-note"><summary><ui-icon name="info"/>图表说明</summary><p>有创 / 无创分图，不自动择优；住院归属未确认的报告不绘制。纵轴独立，勿跨图比较斜率；不插值，缺测断线。点选记录可查看时间与来源。</p></details>
  </section>`,
})
export class ClinicalTrends implements OnChanges {
  @Input() items: Observation[] = [];
  @Input() asOf = '';
  @Input() embedded = false;
  @Input() availableHours = 168;
  @Input() windowHours = 12;
  @Input() compact = false;
  @Input() busy = false;
  @Output() requestRange = new EventEmitter<number>();
  @Output() openHistory = new EventEmitter<void>();
  @Output() openRecords = new EventEmitter<void>();
  rangeLabel(h:number) { return h >= 72 ? (h / 24) + ' 天' : h + ' 小时'; }
  get contextCount() { return this.items.filter(o => o.clinicalUse === 'context_only').length; }
  expandRange() { if(this.compact) this.openHistory.emit(); else if(this.hours < 168) this.setHours(168); else if(this.hours < 720) this.setHours(720); else this.openRecords.emit(); }
  timeLabel = timeLabel; evidenceNote = evidenceNote;
  hours = 12;
  hourOptions = [6, 12, 24, 72, 168];
  end = Date.now();
  from = this.end - this.hours * 3600000;
  plots: Plot[] = [];
  selected: Observation | null = null;
  get hasPlottable() { return this.plots.some(p => p.points.length > 0); }
  ngOnChanges(changes: SimpleChanges) {
    this.end = Date.parse(this.asOf) || Date.now();
    if (changes['windowHours']) this.hours = this.windowHours;
    this.hourOptions = this.compact ? [6,12,24] : [6,12,24,72,168,720];
    this.refresh();
  }
  setHours(h: number) {
    this.selected = null;
    if (h > this.availableHours) { this.requestRange.emit(h); return; }
    this.hours = h;
    this.refresh();
  }
  choose(o: Observation) {
    this.selected = o;
  }
  x(o: Observation) {
    return 36 + ((Date.parse(o.eventTime) - this.from) / (this.end - this.from)) * 274;
  }
  y(o: Observation, p: Plot) {
    return 116 - ((o.value! - p.low) / (p.high - p.low)) * 96;
  }
  refresh() {
    this.from = this.end - this.hours * 3600000;
    this.plots = measuresFor(this.items).map((m) => {
      const points = pointsFor(this.items, m, this.from, this.end),
        values = points.map((o) => o.value!);
      const min = values.length ? Math.min(...values) : 0,
        max = values.length ? Math.max(...values) : 1,
        pad = Math.max((max - min) * 0.18, m.code === 'TEMP' || m.code === 'LACTATE' ? 0.3 : 2);
      const p: Plot = {
        m,
        points,
        low: Math.max(m.min, min - pad),
        high: Math.min(m.max, max + pad),
        paths: [],
        last: points.at(-1),
        stale: stale(points.at(-1), m, this.end),
        excluded:
          this.items.filter(
            (o) =>
              o.parameterCode === m.code &&
              Date.parse(o.eventTime) >= this.from &&
              Date.parse(o.eventTime) <= this.end,
          ).length - points.length,
      };
      if (p.high === p.low) p.high = p.low + 1;
      p.paths = segments(points, m.gapMinutes).map((group) =>
        group
          .map((o, i) => (i ? 'L' : 'M') + this.x(o).toFixed(2) + ',' + this.y(o, p).toFixed(2))
          .join(' '),
      );
      return p;
    });
  }
}
