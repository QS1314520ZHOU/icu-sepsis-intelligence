import { CommonModule } from '@angular/common';
import { Component, Input, OnChanges, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, errorMessage } from './api';
import { UiIcon } from './ui-icon';
import { AiBriefView } from './ai-brief-view';
import { AiBrief, AiKind, acceptsBrief, copyText, patientKinds } from './ai-assist';

interface Turn { q: string; brief: AiBrief | null; error: string; busy: boolean; }

/** On-demand AI tools for the current patient. Never runs automatically; every statement links to its evidence. */
@Component({
  selector: 'ai-assist-card', standalone: true, imports: [CommonModule, FormsModule, UiIcon, AiBriefView],
  template: `<section class="review-card sx-ai" aria-label="AI 辅助">
    <div class="review-card-title">
      <h2><ui-icon name="file"/>AI 辅助</h2>
      <span class="ai-label">{{ configured ? '生成内容需医生核对' : 'AI 未配置' }}</span>
      <span class="ai-actions">
        <ng-container *ngFor="let k of kinds"><button type="button" *ngIf="!k.needsIcu05 || icu05Available" [class.on]="brief?.kind === k.kind" (click)="run(k.kind)" [disabled]="!!busy" [title]="k.hint">{{ busy === k.kind ? '生成中…' : k.label }}</button></ng-container>
      </span>
    </div>
    <p class="ai-idle" *ngIf="!brief && !busy && !error && !turns.length">{{ configured ? '按需生成，仅依据本页规则结果、近 24 小时记录与指南条目；不会自动运行。也可以在下方直接提问。' : '未接入院内模型时，可查看本次核对依据与相关指南条目。' }}</p>
    <p class="review-inline-error" role="alert" *ngIf="error"><ui-icon name="info"/>{{ error }}</p>
    <ai-brief-view [brief]="brief" />
    <div class="ai-chat" *ngIf="configured">
      <div class="ai-turn" *ngFor="let t of turns">
        <p class="ai-q"><b>问</b>{{ t.q }}</p>
        <p class="ai-status" *ngIf="t.busy">思考中…</p>
        <p class="review-inline-error" *ngIf="t.error">{{ t.error }}</p>
        <ai-brief-view *ngIf="t.brief" [brief]="t.brief" [single]="true" />
      </div>
      <div class="ai-suggest" *ngIf="!turns.length"><button type="button" *ngFor="let s of starters" (click)="question = s; ask()">{{ s }}</button></div>
      <form class="ai-ask" (ngSubmit)="ask()">
        <input name="q" [(ngModel)]="question" maxlength="300" placeholder="针对当前患者提问，如：乳酸为什么还没复测？" aria-label="向 AI 提问" [disabled]="asking"/>
        <button class="primary" [disabled]="asking || !question.trim()">{{ asking ? '…' : '提问' }}</button>
        <button type="button" class="text-button" *ngIf="turns.length" (click)="turns = []">清空</button>
      </form>
    </div>
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
  error = '';
  readonly kinds = patientKinds;
  readonly starters = ['抗菌药和血培养的先后顺序？', '休克判定依据是什么？', '还缺哪些检查或记录？', '近 24 小时最需要警惕的变化？'];
  turns: Turn[] = []; question = ''; asking = false;
  private generation = 0;
  private lastKey = '';
  ngOnChanges() {
    const key = this.patientId + '|' + this.encounterId;
    if (key === this.lastKey) return;
    this.lastKey = key; ++this.generation; this.brief = null; this.busy = null; this.error = ''; this.turns = []; this.question = '';
  }
  async run(kind: AiKind) {
    const generation = ++this.generation;
    this.busy = kind; this.error = '';
    try {
      const b = await this.api.post<AiBrief>('/patients/' + encodeURIComponent(this.patientId) + '/ai/brief', { kind });
      if (generation !== this.generation) return;
      if (!acceptsBrief(b, this.patientId, this.encounterId)) { this.brief = null; this.error = '患者上下文不一致，已丢弃本次结果。'; return; }
      this.brief = b;
    } catch (e) {
      if (generation === this.generation) { this.brief = null; this.error = 'AI 辅助请求失败：' + errorMessage(e); }
    } finally {
      if (generation === this.generation) this.busy = null;
    }
  }
  async ask() {
    const q = this.question.trim();
    if (!q || this.asking) return;
    const generation = this.generation;
    const history = this.turns.filter(t => t.brief).slice(-4).map(t => ({ q: t.q, a: copyText(t.brief!).slice(0, 400) }));
    const turn: Turn = { q, brief: null, error: '', busy: true };
    this.turns = [...this.turns, turn]; this.question = ''; this.asking = true;
    try {
      const b = await this.api.post<AiBrief>('/patients/' + encodeURIComponent(this.patientId) + '/ai/ask', { question: q, history });
      if (generation !== this.generation) return;
      if (!acceptsBrief(b, this.patientId, this.encounterId)) turn.error = '患者上下文不一致，已丢弃本次结果。';
      else turn.brief = b;
    } catch (e) {
      turn.error = '提问失败：' + errorMessage(e);
    } finally {
      turn.busy = false; this.asking = false; this.turns = [...this.turns];
    }
  }
}
