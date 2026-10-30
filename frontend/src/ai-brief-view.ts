import { CommonModule } from '@angular/common';
import { Component, Input, OnChanges, inject } from '@angular/core';
import { Api, errorMessage } from './api';
import { AiBrief, AiItem, AiSection, RefTarget, copyKinds, copyText, hasContent, kindLabel, layoutOf, refTarget, rejectReasons, resolvePatients, sectionItems, statusNote, urgencyLabel } from './ai-assist';

/** Renders one validated AI result: sections, evidence chips, sources, copy and feedback. */
@Component({
  selector: 'ai-brief-view', standalone: true, imports: [CommonModule],
  template: `<ng-container *ngIf="brief as b">
    <p class="ai-status" *ngIf="note" [attr.data-status]="b.status">{{ note }}</p>
    <div class="ai-body" [class.single]="single" *ngIf="content">
      <ng-container *ngFor="let s of sections">
        <div class="ai-section" *ngIf="items(s).length">
          <h3>{{ s.title }}</h3>
          <ul><li *ngFor="let i of items(s)" [attr.data-urgency]="i.urgency || null">
            <span class="ai-urgency" *ngIf="i.urgency && urgency[i.urgency]">{{ urgency[i.urgency] }}</span>{{ show(i.text) }}
            <button type="button" class="ai-ref" *ngFor="let r of i.refs" [class.on]="open?.ref === r" (click)="toggle(r)" [title]="tip(r)">{{ r }}</button>
          </li></ul>
        </div>
      </ng-container>
    </div>
    <div class="ai-ref-detail" *ngIf="open as o" role="region" [attr.aria-label]="'依据 ' + o.ref">
      <div><b>{{ o.ref }}</b> {{ o.title }}<button type="button" class="text-button" (click)="open = null">关闭</button></div>
      <p>{{ show(o.body) }}</p><small>{{ o.meta }}</small>
    </div>
    <details class="ai-sources" [open]="!content">
      <summary>依据 {{ b.facts.length }} 条事实 · {{ b.knowledge.length }} 条知识</summary>
      <ol class="ai-facts" *ngIf="!content && b.facts.length"><li *ngFor="let f of b.facts"><b>{{ f.id }}</b> {{ show(f.text) }}</li></ol>
      <ol class="ai-kb"><li *ngFor="let k of b.knowledge"><b>{{ k.ref }}</b> {{ k.title }} · {{ k.section }}<em *ngIf="k.strength">{{ k.strength }}</em><p>{{ k.text }}</p></li></ol>
    </details>
    <footer class="ai-foot" *ngIf="content">
      <span>{{ b.label || kind[b.kind] }} · {{ b.model || '' }}<ng-container *ngIf="b.fallbackUsed">（备用模型）</ng-container><ng-container *ngIf="b.latencyMs !== null"> · {{ (b.latencyMs / 1000) | number:'1.0-1' }}s</ng-container> · {{ b.generatedAt | date:'HH:mm' }}</span>
      <button type="button" class="ai-copy" *ngIf="copyable" (click)="copy()">{{ copied ? '已复制' : '复制文本' }}</button>
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
    <p class="review-inline-error" role="alert" *ngIf="error">{{ error }}</p>
  </ng-container>`,
})
export class AiBriefView implements OnChanges {
  @Input() brief: AiBrief | null = null;
  /** One-column layout (alerts list, chat answers). */
  @Input() single = false;
  api = inject(Api);
  sections: AiSection[] = []; content = false; note = ''; copyable = false;
  open: RefTarget | null = null;
  rejecting = false; reason = ''; sending = false; sent = false; copied = false; error = '';
  readonly kind = kindLabel; readonly urgency = urgencyLabel; readonly reasons = rejectReasons;
  ngOnChanges() {
    const b = this.brief;
    this.sections = layoutOf(b); this.content = hasContent(b); this.note = statusNote(b);
    this.copyable = !!b && copyKinds.has(b.kind);
    this.open = null; this.rejecting = false; this.reason = ''; this.sent = false; this.sending = false; this.copied = false; this.error = '';
  }
  items(s: AiSection): AiItem[] { return sectionItems(this.brief, s); }
  show(t: string) { return resolvePatients(t, this.brief?.patients); }
  tip(ref: string) { const t = this.brief && refTarget(ref, this.brief); return t ? this.show(t.body) : ''; }
  toggle(ref: string) { this.open = this.open?.ref === ref ? null : this.brief && refTarget(ref, this.brief); }
  async copy() {
    if (!this.brief) return;
    const text = copyText(this.brief);
    try { await navigator.clipboard.writeText(text); }
    catch {
      const ta = document.createElement('textarea'); ta.value = text; document.body.appendChild(ta); ta.select();
      try { document.execCommand('copy'); } finally { ta.remove(); }
    }
    this.copied = true; setTimeout(() => (this.copied = false), 2000);
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
