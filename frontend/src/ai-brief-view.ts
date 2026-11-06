import { FormsModule } from '@angular/forms';
import { CommonModule } from '@angular/common';
import { Component, Input, OnChanges, OnDestroy, SimpleChanges, inject } from '@angular/core';
import { Api, errorMessage } from './api';
import { draftCopyText, isDraftOutdated, AiBrief, AiItem, AiSection, RefTarget, copyKinds, copyText, hasContent, kindLabel, layoutOf, refTarget, rejectReasons, resolvePatients, sectionItems, statusNote, urgencyLabel } from './ai-assist';

/** Renders one validated AI result: sections, evidence chips, sources, copy and feedback. */
@Component({
  selector: 'ai-brief-view', standalone: true, imports: [CommonModule, FormsModule],
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
      <ol class="ai-kb"><li *ngFor="let k of b.knowledge"><b>{{ k.ref }}</b> {{ k.title }} · {{ k.section }} · 版本 {{ k.version || '未提供' }}<em *ngIf="k.strength">{{ k.strength }}</em><p>{{ k.text }}</p><small>{{ k.source }}<ng-container *ngIf="k.applicableScope"> · {{ k.applicableScope }}</ng-container></small></li></ol>
    </details>
    <p *ngIf="content && (b.kind === 'handover' || b.kind === 'note')" class="ai-status">AI 草稿 · 生成于 {{ b.generatedAt | date:'MM-dd HH:mm:ss' }} · 请逐条核对来源与时间。</p>
    <p *ngIf="outdated && content" class="review-inline-error" role="status">页面数据已刷新，草稿可能过期，请重新核对或生成。</p>
    <div *ngIf="editing" class="ai-draft-editor"><label>人工编辑草稿<textarea aria-label="人工编辑交班或病程草稿" [(ngModel)]="draft" maxlength="10000" rows="10"></textarea></label>
      <p>仅保留在当前页面；原 AI 稿与依据继续展示，人工文字需重新核对引用。</p><button type="button" (click)="finishEdit()" [disabled]="!draft.trim()">完成本地编辑</button><button type="button" (click)="cancelEdit()">取消本次编辑</button></div>
    <footer class="ai-foot" *ngIf="content">
      <span>{{ b.label || kind[b.kind] }} · {{ b.model || '' }}<ng-container *ngIf="b.fallbackUsed">（备用模型）</ng-container><ng-container *ngIf="b.latencyMs !== null"> · {{ (b.latencyMs / 1000) | number:'1.0-1' }}s</ng-container> · {{ b.generatedAt | date:'HH:mm' }}</span>
      <button type="button" *ngIf="(b.kind === 'handover' || b.kind === 'note') && !editing" (click)="startEdit()">编辑草稿</button>
      <button type="button" *ngIf="edited !== null && !editing" (click)="restoreDraft()">恢复 AI 原稿</button>
      <span *ngIf="edited !== null && !editing">当前复制文本已人工修改</span>
      <button type="button" class="ai-copy" *ngIf="copyable" (click)="copy()">{{ copied ? '已复制' : '复制文本' }}</button>
      <span class="ai-disclaimer">{{ b.disclaimer }}</span>
      <span class="ai-feedback" *ngIf="!sent">
        <label>人工核对耗时（秒，可选）<input type="number" min="0" max="14400" step="1" aria-label="人工核对耗时秒" [(ngModel)]="reviewSeconds"/></label>
        <button type="button" *ngIf="edited !== null && !editing" (click)="send('edit')" [disabled]="sending">记录修改反馈</button>
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
export class AiBriefView implements OnChanges, OnDestroy {
  @Input() dataAsOf: string | null = null;
  editing = false; draft = ''; edited: string | null = null; private readonly draftKey = Symbol('ai-draft');
  get outdated() { return isDraftOutdated(this.brief?.generatedAt, this.dataAsOf); }
  startEdit() { if (!this.brief) return; this.draft=this.edited ?? copyText(this.brief); this.editing=true; this.api.aiDrafts.add(this.draftKey); }
  finishEdit() { if(!this.draft.trim()) return; this.edited=this.draft.trim(); this.editing=false; this.api.aiDrafts.delete(this.draftKey); }
  cancelEdit() { this.editing=false; this.draft=''; this.api.aiDrafts.delete(this.draftKey); }
  restoreDraft() { this.edited=null; this.cancelEdit(); }
  ngOnDestroy() { this.api.aiDrafts.delete(this.draftKey); }
  @Input() brief: AiBrief | null = null;
  /** One-column layout (alerts list, chat answers). */
  @Input() single = false;
  api = inject(Api);
  sections: AiSection[] = []; content = false; note = ''; copyable = false;
  open: RefTarget | null = null;
  reviewSeconds: number | null = null;
  rejecting = false; reason = ''; sending = false; sent = false; copied = false; error = '';
  readonly kind = kindLabel; readonly urgency = urgencyLabel; readonly reasons = rejectReasons;
  ngOnChanges(changes: SimpleChanges) {
    if (!changes['brief']) return;
    this.cancelEdit(); this.edited=null; this.reviewSeconds=null;
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
    const text = draftCopyText(this.brief, this.editing ? this.draft : this.edited);
    try { await navigator.clipboard.writeText(text); }
    catch {
      const ta = document.createElement('textarea'); ta.value = text; document.body.appendChild(ta); ta.select();
      try { if (!document.execCommand('copy')) { this.error='复制失败，请手动选择草稿文本复制。'; return; } } finally { ta.remove(); }
    }
    this.copied = true; setTimeout(() => (this.copied = false), 2000);
  }
  async send(verdict: 'accept' | 'reject' | 'edit') {
    if (!this.brief) return;
    if(this.reviewSeconds!==null&&(!Number.isInteger(this.reviewSeconds)||this.reviewSeconds<0||this.reviewSeconds>14400)){this.error="请填写 0 至 14400 的整数秒，或留空。";return;}
    const interactionId=this.brief.interactionId;
    this.sending = true;
    try {
      await this.api.post('/ai/interactions/' + encodeURIComponent(this.brief.interactionId) + '/feedback', { verdict, reason: verdict === 'reject' ? this.reason : verdict === 'edit' ? '人工修改本地草稿' : '', reviewSeconds:this.reviewSeconds });
      if(this.brief?.interactionId===interactionId){this.sent = true; this.rejecting = false;}
    } catch (e) { if(this.brief?.interactionId===interactionId)this.error = '反馈未保存：' + errorMessage(e); }
    finally { if(this.brief?.interactionId===interactionId)this.sending = false; }
  }
}
