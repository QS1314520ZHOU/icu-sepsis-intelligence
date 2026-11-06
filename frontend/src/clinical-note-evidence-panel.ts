import { CommonModule } from '@angular/common';
import { Component, Input, Output, EventEmitter, OnChanges, OnDestroy, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, errorMessage } from './api';
interface EvidenceRow {snapshotId:string;ref?:string;documentType:string;documentedAt:string|null;availableAt:string|null;syncedAt:string|null;state:'ready'|'unknown'|'excluded';message:string;reason:string;}
interface EvidencePacket {timePolicy?:string;packetFingerprint:string;patientId:string;encounterId:string;asOf:string;ruleVersion:string;items:EvidenceRow[];ready:number;unknown:number;excluded:number;truncated:boolean;currentSourceStatus:string;currentSourceComplete:boolean|null;scope:string;limitations:string[];}
@Component({selector:'clinical-note-evidence-panel',standalone:true,imports:[CommonModule,FormsModule],template:`<section aria-label="病程证据时间核对"><h3>病程证据时间核对</h3>
 <p>由医生主动核对已有快照的时间和版本；不调用模型，不生成感染结论。</p>
 <label>复核时点（留空为服务器当前时间）<input type="datetime-local" aria-label="病程复核时点" [(ngModel)]="asOf"/></label>
 <button type="button" (click)="prepare()" [disabled]="busy">{{ busy ? '核对中…' : '按此时点核对病程证据' }}</button>
 <p *ngIf="error" role="alert" class="review-inline-error">{{ error }}</p><ng-container *ngIf="data as d"><p>复核时点 {{ d.asOf | date:'yyyy-MM-dd HH:mm:ss' }} · 可核对 {{ d.ready }} · 时间或版本待核对 {{ d.unknown }} · 时点外或旧版本 {{ d.excluded }}</p>
 <p *ngIf="d.currentSourceComplete !== true" class="review-inline-error">当前来源读取不完整或完整性未确认；不能据此判断历史时点的来源完整。</p>
 <p *ngIf="d.truncated" role="status">本地快照检查达到上限，本次全部保持待核对。</p>
 <div class="review-table-wrap"><table *ngIf="d.items.length"><thead><tr><th>本次引用</th><th>文书类型</th><th>来源可用时间上界</th><th>本地采集时间上界</th><th>核对结果</th><th>原文</th></tr></thead>
 <tbody><tr *ngFor="let r of d.items"><td>{{ r.ref || '不进入可用集合' }}</td><td>{{ r.documentType }}</td><td>{{ r.availableAt ? (r.availableAt | date:'MM-dd HH:mm:ss') : '未确认' }}</td><td>{{ r.syncedAt ? (r.syncedAt | date:'MM-dd HH:mm:ss') : '未确认' }}</td><td>{{ r.message }}</td><td><button type="button" (click)="openSnapshot.emit({id:r.snapshotId,asOf:d.asOf,ready:r.state === 'ready',fingerprint:d.packetFingerprint})">核对本条原文</button></td></tr></tbody></table></div>
 <p *ngIf="!d.items.length">本地未取得可检查快照，不能解释为无感染。</p><p *ngIf="d.timePolicy">{{ d.timePolicy }}</p><p>{{ d.scope }}</p><details><summary>核对规则与限制</summary><p>{{ d.ruleVersion }}</p><p *ngFor="let limit of d.limitations">{{ limit }}</p></details></ng-container></section>`})
export class ClinicalNoteEvidencePanel implements OnChanges,OnDestroy {
 @Input() patientId='';@Input() encounterId='';@Output() openSnapshot=new EventEmitter<{id:string;asOf:string;ready:boolean;fingerprint:string}>();@Output() reset=new EventEmitter<void>();api=inject(Api);asOf='';data:EvidencePacket|null=null;busy=false;error='';private generation=0;
 ngOnChanges(){++this.generation;this.asOf='';this.data=null;this.busy=false;this.error='';}ngOnDestroy(){++this.generation;}
 async prepare(){this.reset.emit();const generation=++this.generation;this.busy=true;this.error='';this.data=null;
  try {const at=this.asOf?new Date(this.asOf).toISOString():null;const result=await this.api.get<EvidencePacket>('/patients/'+encodeURIComponent(this.patientId)+'/clinical-notes/review-evidence'+(at?'?asOf='+encodeURIComponent(at):''));
   if(generation!==this.generation)return;if(result.patientId!==this.patientId||this.encounterId&&result.encounterId!==this.encounterId){this.error='患者住院上下文不一致，已丢弃核对结果。';return;}this.data=result;
  }catch(e){if(generation===this.generation)this.error='病程证据核对暂不可用：'+errorMessage(e);}finally{if(generation===this.generation)this.busy=false;}}
}
