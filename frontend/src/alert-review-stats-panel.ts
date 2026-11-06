import { CommonModule } from '@angular/common';
import { Component, inject, OnInit } from '@angular/core';
import { Api, errorMessage } from './api';
interface Stats {days:number;records:number;patients:number;reviewChains:number;withRecordedFeedback:number;awaitingFeedback:number;needsReReview:number;historicalUnknown:number;confirmedFalsePositive:number;rejectedOnly:number;confirmedScreen:number;feedbackDenominator:number;feedbackRate:number|null;scope:string;rule:string;limitations:string[];}
@Component({selector:'alert-review-stats',standalone:true,imports:[CommonModule],template:`<details><summary>预警复核统计</summary>
  <button type="button" (click)="load()" [disabled]="busy">刷新复核统计</button><p role="status" *ngIf="busy">正在汇总复核记录…</p><p role="alert" *ngIf="error">{{ error }}</p>
  <ng-container *ngIf="data as d"><p>近 {{ d.days }} 天 · {{ d.patients }} 个患者住院 · {{ d.reviewChains }} 条去重复核链（{{ d.records }} 条预警记录）</p>
  <p>有记录的反馈 {{ d.withRecordedFeedback }}/{{ d.feedbackDenominator }} 条 · 反馈率 {{ d.feedbackRate === null ? '无法判定' : (d.feedbackRate | percent:'1.0-0') }} · 待反馈 {{ d.awaitingFeedback }} · 需重新复核 {{ d.needsReReview }} · 历史反馈不明 {{ d.historicalUnknown }}</p>
  <p>经复核确认误报 {{ d.confirmedFalsePositive }} · 仅驳回 {{ d.rejectedOnly }} · 确认筛查 {{ d.confirmedScreen }}</p>
  <p>{{ d.scope }}</p><p>{{ d.rule }}</p><p *ngFor="let limit of d.limitations">{{ limit }}</p></ng-container></details>`})
export class AlertReviewStatsPanel implements OnInit {api=inject(Api);data:Stats|null=null;busy=false;error='';ngOnInit(){void this.load();}
  async load(){this.busy=true;this.error='';try{this.data=await this.api.get<Stats>('/quality/alert-reviews?days=7');}catch(e){this.data=null;this.error='复核统计读取失败：'+errorMessage(e);}finally{this.busy=false;}}
}
