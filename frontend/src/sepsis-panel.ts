import { CommonModule } from '@angular/common';
import { Component, Input, OnChanges, inject } from '@angular/core';
import { Api, errorMessage } from './api';

interface Factor {
  code: string;
  label: string;
  weight: number;
  why: string;
}
interface BundleItem {
  code: string;
  name: string;
  done: boolean | null;
  at?: string | null;
  evidence?: string;
}
interface BundleWindow {
  window: string;
  finish: boolean | null;
  items: BundleItem[];
  reasons: string[];
}
interface Subtype {
  code: string;
  label: string;
  basis: string;
}
interface SepsisAssessment {
  available?: boolean;
  message?: string;
  engine?: string;
  ruleSource?: string;
  syndrome?: string;
  syndromeLabel?: string;
  shock?: {
    confirmed: boolean;
    rule: string;
    k1LactateGe2: boolean;
    k2Vasopressor: boolean;
    mapMin?: number;
    lactateMax?: number;
    qsofa?: number;
    infectionEvidence?: boolean;
  };
  risk?: {
    score: number;
    level: string;
    levelLabel: string;
    factors: Factor[];
    modelStatus?: string;
    note?: string;
  };
  bundle?: {
    t0?: string;
    '1h'?: BundleWindow;
    '3h'?: BundleWindow;
    '6h'?: BundleWindow;
  };
  subtypes?: Subtype[];
  ai?: {
    method: string;
    notDiagnosis: boolean;
    promptNote: string;
    nextActions: string[];
  };
}

@Component({
  selector: 'sepsis-panel',
  standalone: true,
  imports: [CommonModule],
  template: ` <section class="sepsis-panel" *ngIf="data as d">
    <header class="sepsis-head" [attr.data-level]="d.risk?.level || 'minimal'">
      <div class="risk-box">
        <div class="risk-score">{{ d.risk?.score ?? '—' }}</div>
        <div>
          <div class="risk-level">{{ d.risk?.levelLabel || '—' }}</div>
          <div class="risk-sub">{{ d.syndromeLabel || d.syndrome || '—' }}</div>
        </div>
      </div>
      <div class="shock-box" [class.on]="d.shock?.confirmed">
        <div class="shock-title">{{ d.shock?.confirmed ? '脓毒性休克' : '休克未确认' }}</div>
        <div class="shock-rule">{{ d.shock?.rule }}</div>
        <div class="shock-flags">
          <span>qSOFA {{ d.shock?.qsofa ?? '—' }}</span>
          <span>MAP最低 {{ d.shock?.mapMin ?? '—' }}</span>
          <span>乳酸最高 {{ d.shock?.lactateMax ?? '—' }}</span>
          <span>升压药 {{ d.shock?.k2Vasopressor ? '有' : '无' }}</span>
          <span>感染证据 {{ d.shock?.infectionEvidence ? '有' : '无' }}</span>
        </div>
      </div>
    </header>

    <div class="sepsis-grid">
      <article class="card">
        <h3>风险因子</h3>
        <ul class="factor-list">
          <li *ngFor="let f of d.risk?.factors || []">
            <span class="fw">+{{ f.weight }}</span>
            <div>
              <strong>{{ f.label }}</strong>
              <small>{{ f.why }}</small>
            </div>
          </li>
          <li *ngIf="!d.risk?.factors?.length" class="muted">暂无加权因子</li>
        </ul>
        <ul class="action-list">
          <li *ngFor="let a of d.ai?.nextActions || []">{{ a }}</li>
        </ul>
      </article>

      <article class="card">
        <h3>1h / 3h / 6h Bundle</h3>
        <p class="muted small">锚点 T0：{{ d.bundle?.t0 | date: 'MM-dd HH:mm' }}</p>
        <div class="bundle-cols">
          <div *ngFor="let w of windows(d.bundle)" class="bundle-col" [attr.data-finish]="finishClass(w.finish)">
            <div class="bundle-title">
              {{ w.window }} ·
              {{ w.finish === true ? '达标' : w.finish === false ? '未达标' : '待定' }}
            </div>
            <div class="bundle-item" *ngFor="let it of w.items || []">
              <span class="dot" [attr.data-done]="dot(it.done)"></span>
              <div>
                <div>{{ it.name }}</div>
              </div>
            </div>
            <div class="bundle-reasons" *ngIf="w.reasons?.length && showReasons(w)">
              {{ reasonText(w) }}
            </div>
          </div>
        </div>
      </article>
    </div>

    <article class="card subtypes" *ngIf="(d.subtypes || []).length">
      <h3>感染源 / 循环</h3>
      <div class="subtype-chips">
        <div class="chip" *ngFor="let s of d.subtypes || []">
          <strong>{{ s.label }}</strong>
          <small>{{ s.basis }}</small>
        </div>
      </div>
    </article>
  </section>
  <p class="icu-error" *ngIf="error">{{ error }}</p>`,
})
export class SepsisPanel implements OnChanges {
  @Input() patientId = '';
  api = inject(Api);
  data: SepsisAssessment | null = null;
  error = '';

  ngOnChanges() {
    void this.load();
  }

  async load() {
    this.error = '';
    this.data = null;
    if (!this.patientId) return;
    try {
      this.data = await this.api.get<SepsisAssessment>(
        '/patients/' + encodeURIComponent(this.patientId) + '/sepsis-assessment',
      );
    } catch (e) {
      this.error = errorMessage(e);
    }
  }

  windows(bundle?: SepsisAssessment['bundle']): BundleWindow[] {
    if (!bundle) return [];
    return [bundle['1h'], bundle['3h'], bundle['6h']].filter(Boolean) as BundleWindow[];
  }

  finishClass(v?: boolean | null): string {
    return v === true ? 'ok' : v === false ? 'bad' : 'unknown';
  }

  dot(v?: boolean | null): string {
    return v === true ? 'ok' : v === false ? 'bad' : 'unknown';
  }

  showReasons(w: BundleWindow): boolean {
    if (!w.reasons?.length) return false;
    return !w.reasons.every((r) => r === 'DATA_MISSING' || r === 'A1_NOT_MET' || r.endsWith('_MISSING'));
  }

  reasonText(w: BundleWindow): string {
    const map: Record<string, string> = {
      FLUID_INSUFFICIENT: '液体量不足 1500ml',
      MAP_TRIGGERED: 'MAP 触发复苏',
      LAC_RECHECK_MISSING: '需复测乳酸',
      VASO_NOT_STARTED: '升压药未启动',
      REASSESS_MISSING: '需容量再评估',
      ABX_BEFORE_CULTURE: '抗菌药早于培养',
    };
    const parts = (w.reasons || [])
      .map((r) => map[r] || r)
      .filter((t) => t && !/^[A-Z0-9_]+$/.test(t));
    return parts.join(' · ');
  }
}
