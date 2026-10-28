import { CommonModule } from '@angular/common';
import { Component, Input, OnChanges } from '@angular/core';
import type { Observation } from './api';
import { measures, pointsFor, segments, stale, Measure } from './trends';

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
  imports: [CommonModule],
  template: ` <section class="card trend-section">
    <div class="section-head">
      <div>
        <p class="eyebrow" *ngIf="!embedded">OBSERVED VITALS</p>
        <h2>生命体征</h2>
      </div>
      <div class="segmented" role="group" aria-label="趋势时间范围">
        <button
          *ngFor="let h of hourOptions"
          [class.selected]="hours === h"
          [attr.aria-pressed]="hours === h"
          (click)="setHours(h)"
        >
          {{ h }}h
        </button>
      </div>
    </div>
    <div class="chart-caption" *ngIf="!embedded">
      <span class="legend-line"></span>实际存储的观测 · 非预测曲线
      <span>{{ from | date: 'MM-dd HH:mm' }} — {{ end | date: 'MM-dd HH:mm' }}</span>
    </div>
    <div class="missing" *ngIf="items.length >= 2000">已达读取上限，趋势可能不完整。</div>
    <div class="trend-grid">
      <article class="trend-tile" *ngFor="let p of plots">
        <div class="trend-title">
          <div>
            <span>{{ p.m.label }}</span
            ><small *ngIf="!embedded">{{ p.m.code }} · {{ p.m.unit }}</small>
          </div>
          <span class="tag" *ngIf="!p.last">无数据</span>
          <span class="tag warning" *ngIf="p.last && p.stale">过期</span>
        </div>
        <div class="trend-reading" [style.color]="p.m.color">
          {{ p.last?.value ?? '—' }}<small>{{ p.m.unit }}</small>
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
            <text x="29" [attr.y]="24 + tick * 48" text-anchor="end" fill="#7b8794" font-size="10">
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
          <text x="36" y="145" fill="#7b8794" font-size="10">{{ from | date: 'MM-dd HH:mm' }}</text>
          <text x="310" y="145" text-anchor="end" fill="#7b8794" font-size="10">
            {{ end | date: 'MM-dd HH:mm' }}
          </text>
        </svg>
        <div *ngIf="!p.points.length" class="chart-empty">该时间窗无观测</div>
        <div class="trend-foot" *ngIf="!embedded">
          <span>{{ p.points.length }} 次观测</span>
          <button *ngIf="p.last" (click)="choose(p.last)">核查数据点</button>
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
        <dt>事件时间</dt>
        <dd>{{ selected.eventTime | date: 'MM-dd HH:mm' }}</dd>
        <dt>来源</dt>
        <dd>{{ selected.sourceSystem }}</dd>
        <dt *ngIf="!embedded">记录 ID</dt>
        <dd *ngIf="!embedded">{{ selected.sourceRecordId }}</dd>
      </dl>
    </div>
    <p class="muted small" *ngIf="!embedded">
      纵轴独立缩放，直线连接相邻观测，不补值。单位无效或同刻冲突不绘制。
    </p>
  </section>`,
})
export class ClinicalTrends implements OnChanges {
  @Input() items: Observation[] = [];
  @Input() asOf = '';
  @Input() embedded = false;
  hours = 72;
  hourOptions = [6, 12, 24, 72, 168];
  end = Date.now();
  from = this.end - this.hours * 3600000;
  plots: Plot[] = [];
  selected: Observation | null = null;
  ngOnChanges() {
    this.end = Date.parse(this.asOf) || Date.now();
    this.hours = this.embedded ? 72 : 12;
    this.hourOptions = this.embedded ? [6, 12, 24, 72, 168] : [6, 12, 24];
    this.refresh();
  }
  setHours(h: number) {
    this.hours = h;
    this.selected = null;
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
    this.plots = measures.map((m) => {
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
