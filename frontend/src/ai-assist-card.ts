import { CommonModule } from '@angular/common';
import { Component, Input, OnChanges, inject } from '@angular/core';
import { Api, errorMessage } from './api';
import { UiIcon } from './ui-icon';
import { AiBrief, AiItem, AiKind, RefTarget, SectionKey, acceptsBrief, hasContent, kindLabel, refTarget, rejectReasons, sectionTitles, statusNote, urgencyLabel, visibleItems } from './ai-assist';

/** On-demand explanation of the rule output. Never runs automatically; every statement links to its evidence. */
@Component({
  selector: 'ai-assist-card', standalone: true, imports: [CommonModule, UiIcon],
  template: `<section class="review-card sx-ai" aria-label="AI 辅助解释">
    <div class="review-card-title">
      <h2><ui-icon name="file"/>辅助解释</h2>
      <span class="ai-label">{{ configured ? '生成内容需医生核对' : 'AI 未配置' }}</span>
      <span class="ai-actions">
        <button type="button" (click)="run('summary')" [disabled]="!!busy">{{ busy === 'summary' ? '生成中…' : '病情摘要' }}</button>
        <button type="button" *ngIf="icu05Available" (click)="run('icu05')" [disabled]="!!busy">{{ busy === 'icu05' ? '生成中…' : 'ICU-05 缺项分析' }}</button>
      </span>
    </div>
    <p class="ai-idle" *ngIf="!brief && !busy && !error">{{ configured ? '按需生成，仅依据本页规则结果与指南条目；不会自动运行。' : '未接入院内模型时，可查看本次核对依据与相关指南条目。' }}</p>
    <p class="review-inline-error" role="alert" *ngIf="error"><ui-icon name="info"/>{{ error }}</p>
    <ng-container *ngIf="brief as b">
      <p class="ai-status" *ngIf="note" [attr.data-status]="b.status">{{ note }}</p>
      <div class="ai-body" *ngIf="content">
        <ng-container *ngFor="let s of sections">
          <div class="ai-section" *ngIf="items(s.key).length">
            <h3>{{ s.title }}</h3>
            <ul><li *ngFor="let i of items(s.key)" [attr.data-urgency]="i.urgency || null">
              <span class="ai-urgency" *ngIf="i.urgency && urgency[i.urgency]">{{ urgency[i.urgency] }}</span>{{ i.text }}
              <button type="button" class="ai-ref" *ngFor="let r of i.refs" [class.on]="open?.ref === r" (click)="toggle(r)" [title]="tip(r)">{{ r }}</button>
            </li></ul>
          </div>
        </ng-container>
      </div>
      <div class="ai-ref-detail" *ngIf="open as o" role="region" [attr.aria-label]="'依据 ' + o.ref">
        <div><b>{{ o.ref }}</b> {{ o.title }}<button type="button" class="text-button" (click)="open = null">关闭</button></div>
        <p>{{ o.body }}</p><small>{{ o.meta }}</small>
      </div>
      <details class="ai-sources" [open]="!content">
        <summary>依据 {{ b.facts.length }} 条事实 · {{ b.knowledge.length }} 条知识</summary>
        <ol class="ai-kb"><li *ngFor="let k of b.knowledge"><b>{{ k.ref }}</b> {{ k.title }} · {{ k.section }}<em *ngIf="k.strength">{{ k.strength }}</em><p>{{ k.text }}</p></li></ol>
      </details>
      <footer class="ai-foot" *ngIf="content">
        <span>{{ kind[b.kind] }} · {{ b.model || '' }}<ng-container *ngIf="b.fallbackUsed">（备用模型）</ng-container><ng-container *ngIf="b.latencyMs !== null"> · {{ (b.latencyMs / 1000) | number:'1.0-1' }}s</ng-container> · {{ b.generatedAt | date:'HH:mm' }}</span>
        <span class="ai-disclaimer">{{ b.disclaimer }}</span>
        <span class="ai-feedback" *ngIf="!sent">
          <button type="button" (click)="send('accept')" [disabled]="sending">有用</button>
          <button type="button" (click)="rejecting = !rejecting" [disabled]="sending">不采纳</button>
        </span>
        <span class="ai-sent" *ngIf="sent">已记录反馈</span>
      </footer>
      <div class="ai-reject" *ngIf="rejecting && !sent">
        <button type="button" *ngFor="let r of reasons" [class.selected]="reason === r" (click)="reason = r">{{ r }}</button>
        <button type="button" class="primary" [disabled]="!reason || sending" (click)="send('reject')">提交</button>
      </div>
    </ng-container>
  </section>`,
})
export class AiAssistCard implements OnChanges {
  @Input({ required: true }) patientId = '';
  @Input() encounterId = '';
  @Input() configured = false;
  @Input() icu05Available = false;
  api = inject(Api);
  brief: AiBrief | null = null;
  busy: AiKind | null = null;
  error = ''; note = ''; content = false;
  open: RefTarget | null = null;
  rejecting = false; reason = ''; sending = false; sent = false;
  readonly sections = sectionTitles; readonly kind = kindLabel; readonly urgency = urgencyLabel; readonly reasons = rejectReasons;
  private generation = 0;
  private lastKey = '';
  ngOnChanges() {
    const key = this.patientId + '|' + this.encounterId;
    if (key === this.lastKey) return;
    this.lastKey = key; ++this.generation; this.reset(); this.brief = null; this.busy = null; this.error = '';
  }
  private reset() { this.open = null; this.rejecting = false; this.reason = ''; this.sent = false; this.sending = false; }
  items(key: SectionKey): AiItem[] {
    return this.brief ? visibleItems(this.brief[key], this.brief, key === 'uncertainties') : [];
  }
  tip(ref: string) { const t = this.brief && refTarget(ref, this.brief); return t ? t.body : ''; }
  toggle(ref: string) { this.open = this.open?.ref === ref ? null : this.brief && refTarget(ref, this.brief); }
  async run(kind: AiKind) {
    const generation = ++this.generation;
    this.busy = kind; this.error = ''; this.reset();
    try {
      const b = await this.api.post<AiBrief>('/patients/' + encodeURIComponent(this.patientId) + '/ai/brief', { kind });
      if (generation !== this.generation) return;
      if (!acceptsBrief(b, this.patientId, this.encounterId)) { this.brief = null; this.error = '患者上下文不一致，已丢弃本次结果。'; return; }
      this.brief = b; this.content = hasContent(b); this.note = statusNote(b);
    } catch (e) {
      if (generation === this.generation) { this.brief = null; this.error = 'AI 辅助请求失败：' + errorMessage(e); }
    } finally {
      if (generation === this.generation) this.busy = null;
    }
  }
  async send(verdict: 'accept' | 'reject') {
    if (!this.brief) return;
    this.sending = true;
    try {
      await this.api.post('/ai/interactions/' + encodeURIComponent(this.brief.interactionId) + '/feedback', { verdict, reason: verdict === 'reject' ? this.reason : '' });
      this.sent = true; this.rejecting = false;
    } catch (e) { this.error = '反馈未保存：' + errorMessage(e); }
    finally { this.sending = false; }
  }
}
