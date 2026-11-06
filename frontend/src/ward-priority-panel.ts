import { CommonModule } from '@angular/common';
import { Component, inject, OnInit } from '@angular/core';
import { Api, errorMessage } from './api';
interface QueueCase {patientId: string; encounterId: string; label: string | null; bed: string | null; category: string; alertCount: number; needsReReview: boolean; latestSeenAt: string | null; reasons: string[];}
interface Queue {asOf: string; ruleVersion: string; patients: number; matchedAlerts: number; scope: string; rule: string; limitations: string[]; cases: QueueCase[];}
@Component({selector:'ward-priority-panel',standalone:true,imports:[CommonModule],template:
  `<section aria-label="科室规则复核队列"><h2>系统复核队列</h2><button type="button" (click)="load()" [disabled]="busy">刷新队列</button>
  <p role="status" *ngIf="busy">正在汇总科室未结束预警…</p><p role="alert" class="review-inline-error" *ngIf="error">{{ error }}</p>
  <ng-container *ngIf="data as q"><p>{{ q.scope }} · {{ q.patients }} 个患者住院 / {{ q.matchedAlerts }} 条预警 · 更新 {{ q.asOf | date:'MM-dd HH:mm:ss' }}</p>
  <div class="review-table-wrap"><table *ngIf="q.cases.length"><thead><tr><th>顺序</th><th>患者</th><th>复核层级</th><th>依据</th></tr></thead>
  <tbody><tr *ngFor="let c of q.cases.slice(page*20,(page+1)*20); let i=index"><td>{{ page*20+i+1 }}</td><td>{{ c.bed || '床号未提供' }} · {{ c.label || '姓名未提供' }}</td>
  <td>{{ c.category }}<p *ngIf="c.needsReReview">证据变化，需重新复核</p></td><td><p *ngFor="let reason of c.reasons">{{ reason }}</p><small>最近仍触发：{{ c.latestSeenAt ? (c.latestSeenAt | date:'MM-dd HH:mm') : '时间未知' }} · {{ c.alertCount }} 条预警</small></td></tr></tbody></table></div>
  <p *ngIf="!q.cases.length">当前范围没有未结束预警；不能据此判断临床正常。</p>
  <div *ngIf="q.cases.length>20"><button type="button" [disabled]="page===0" (click)="page=page-1">上一页</button>第 {{ page+1 }} 页<button type="button" [disabled]="(page+1)*20>=q.cases.length" (click)="page=page+1">下一页</button></div>
  <details><summary>排序依据与范围</summary><p>{{ q.ruleVersion }} · {{ q.rule }}</p><p *ngFor="let limit of q.limitations">{{ limit }}</p></details></ng-container></section>`})
export class WardPriorityPanel implements OnInit {
  api=inject(Api);data:Queue|null=null;busy=false;error='';page=0;
  ngOnInit(){void this.load();}
  async load(){this.busy=true;this.error='';this.page=0;try{this.data=await this.api.get<Queue>('/quality/ward-priority');}
    catch(e){this.data=null;this.error='科室队列读取失败：'+errorMessage(e);}finally{this.busy=false;}}
}
