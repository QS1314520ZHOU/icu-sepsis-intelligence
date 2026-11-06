import { ClinicalNoteReviewPanel, VerifiedNoteQuote } from './clinical-note-review-panel';
import { ClinicalNoteEvidencePanel } from './clinical-note-evidence-panel';
import { CommonModule } from '@angular/common';
import { Component, Input, OnChanges, OnDestroy, inject } from '@angular/core';
import { Api, errorMessage } from './api';
interface Note {timeConflict?:boolean;id:string;documentType:string;documentKey:string;source:string;documentedAt:string|null;availableAt:string|null;timeMeaning:string;syncedAt:string;identityConfirmed:boolean;body?:string;candidateReference?:string;}
interface Notes {backgroundSync?:{status:string;lastAttemptAt?:string|null;lastSuccessfulAt?:string|null};patientId:string;encounterId:string;status:string;complete:boolean|null;total:number;items:Note[];page:number;size:number;lastSuccessfulAt:string|null;lastCompleteAt:string|null;canSync:boolean;retention:string;scope:string;aiUse:string;}
@Component({selector:'clinical-notes-panel',standalone:true,imports:[CommonModule,ClinicalNoteEvidencePanel,ClinicalNoteReviewPanel],template:`<details class="clinical-notes" (toggle)="toggle($event)"><summary>病程记录与长期快照</summary>
 <p>来源文书仅供授权核对；与当前生命体征、历史 ICU-05 质控分别查看。</p>
 <button type="button" (click)="load()" [disabled]="busy">刷新快照目录</button><button type="button" *ngIf="data?.canSync && api.has('DOCTOR')" (click)="sync()" [disabled]="busy">同步病程来源</button>
 <p *ngIf="busy" role="status">正在读取病程快照…</p><p *ngIf="error" role="alert" class="review-inline-error">{{ error }}</p>
 <ng-container *ngIf="data as d"><p>同步状态：{{ status(d.status) }} · {{ d.total }} 份已保存版本 · 完整性 {{ d.complete === true ? '本次来源声明完整' : d.complete === false ? '读取不完整' : '未确认' }}</p>
 <p *ngIf="d.backgroundSync as bg">后台扫描：{{ status(bg.status) }}<ng-container *ngIf="bg.lastAttemptAt"> · 最近尝试 {{ bg.lastAttemptAt | date:'MM-dd HH:mm' }}</ng-container></p>
 <p>最后成功同步：{{ d.lastSuccessfulAt ? (d.lastSuccessfulAt | date:'yyyy-MM-dd HH:mm:ss') : '尚无成功同步' }}；最后完整同步：{{ d.lastCompleteAt ? (d.lastCompleteAt | date:'yyyy-MM-dd HH:mm:ss') : '尚未确认' }}</p>
 <p *ngIf="!d.total">尚无已保存快照；不能据此判断无感染或病程正常。</p>
 <div class="review-table-wrap" *ngIf="d.items.length"><table><thead><tr><th>文书类型</th><th>文书时间（需核对来源）</th><th>采集/归档参考时间</th><th>标识及版本</th><th>原文</th></tr></thead>
 <tbody><tr *ngFor="let n of d.items"><td>{{ n.documentType }}</td><td>{{ n.documentedAt ? (n.documentedAt | date:'yyyy-MM-dd HH:mm') : '文书时间未确认' }}<small>{{ time(n.timeMeaning) }}</small><small *ngIf="n.timeConflict">时间顺序冲突，需核对</small></td><td>{{ n.syncedAt | date:'yyyy-MM-dd HH:mm' }}</td><td>{{ n.identityConfirmed ? '来源标识关系已确认' : '候选标识唯一性或版本关系未确认' }}</td><td><button type="button" (click)="read(n)" [disabled]="busy">查看历史原文</button></td></tr></tbody></table></div>
 <div *ngIf="d.total>d.size"><button type="button" (click)="page=page-1;load()" [disabled]="page===0 || busy">上一页</button>第 {{ page+1 }} 页<button type="button" (click)="page=page+1;load()" [disabled]="(page+1)*d.size>=d.total || busy">下一页</button></div>
 <button type="button" *ngIf="api.has('DOCTOR') && !api.nurseView" (click)="evidenceOpen=true">核对病程证据时点</button>
 @if (evidenceOpen && api.has('DOCTOR') && !api.nurseView) { @defer (on immediate) { <clinical-note-evidence-panel [patientId]="patientId" [encounterId]="encounterId" (openSnapshot)="readSnapshot($event)" (reset)="clearEvidenceSelection()"/> } }
 <clinical-note-review-panel *ngIf="evidenceOpen && api.has('DOCTOR') && !api.nurseView" [patientId]="patientId" [encounterId]="encounterId" [quote]="excerpt"/>
 <p>{{ d.scope }}</p><p>{{ d.retention }}</p><p>{{ d.aiUse }}</p></ng-container>
 <section *ngIf="selected as n" aria-label="历史病程快照正文"><h3>历史病程快照 · {{ n.documentType }}</h3><button type="button" (click)="selected=null">关闭原文</button>
 <p>{{ n.documentedAt ? (n.documentedAt | date:'yyyy-MM-dd HH:mm') : '文书时间未确认，正文日期未作为时间依据' }} · 来源 {{ n.source }} · 采集/归档于 {{ n.syncedAt | date:'yyyy-MM-dd HH:mm' }}</p>
 <pre tabindex="0" (mouseup)="selectExcerpt($event)" (keyup)="selectExcerpt($event)" style="white-space:pre-wrap;overflow-wrap:anywhere">{{ n.body }}</pre>
 <div *ngIf="reviewContext && api.has('DOCTOR') && !api.nurseView"><p>此原文已关联复核时点 {{ reviewContext.asOf | date:'yyyy-MM-dd HH:mm:ss' }}；可选择连续文字并核对引用。</p>
 <button type="button" *ngIf="range" (click)="verifyExcerpt()" [disabled]="busy || range.end-range.start>1000">核对所选片段</button><p *ngIf="range && range.end-range.start>1000">片段过长，请缩短选择。</p>
 <blockquote *ngIf="excerpt"><strong>{{ excerpt.ref }} · 本地核对片段</strong><p>{{ excerpt.excerpt }}</p><small>未发送模型；证据含义仍需医生复核。</small></blockquote></div><details *ngIf="n.candidateReference"><summary>授权追溯信息</summary>候选文书号 {{ n.candidateReference }}（唯一性须核对医院接口）</details></section>
 </details>`})
export class ClinicalNotesPanel implements OnChanges,OnDestroy {
 @Input() patientId='';@Input() encounterId='';api=inject(Api);data:Notes|null=null;selected:Note|null=null;page=0;busy=false;error='';private generation=0;evidenceOpen=false;
 reviewContext:{id:string;asOf:string;fingerprint:string}|null=null;range:{start:number;end:number}|null=null;excerpt:VerifiedNoteQuote|null=null;
 ngOnChanges(){++this.generation;this.data=null;this.selected=null;this.reviewContext=null;this.range=null;this.excerpt=null;this.evidenceOpen=false;this.page=0;this.error='';this.busy=false;}
 ngOnDestroy(){++this.generation;this.selected=null;}
 toggle(event:Event){if((event.target as HTMLDetailsElement).open&&!this.data&&!this.busy)void this.load();}
 status(code:string){return ({scan_limited:'本轮分批扫描，后续继续补偿',not_started:'尚未开始',no_active_patients:'本次未取得在科患者',no_records:'已完成本次同步，未返回文书',disabled:'来源同步未启用',not_configured:'医院接口适配尚未配置',key_unavailable:'快照密钥暂不可用',binding_unconfirmed:'患者或本次住院归属未确认',synced:'已同步',partial:'部分记录读取或保存不完整',completeness_unknown:'已保存，来源完整性未确认',timeout:'来源读取超时，旧快照保留',read_failed:'来源读取失败，旧快照保留'} as Record<string,string>)[code]||'状态待核对';}
 time(code:string){return ({recorded:'记录时间',signed:'签署时间',reported:'报告时间（不代表已确认可用时间）',unknown:'时间含义未确认'} as Record<string,string>)[code]||'时间含义未确认';}
 async load(){const generation=++this.generation;this.busy=true;this.error='';try{const data=await this.api.get<Notes>('/patients/'+encodeURIComponent(this.patientId)+'/clinical-notes?page='+this.page+'&size=20');
   if(generation!==this.generation)return;if(data.patientId!==this.patientId||this.encounterId&&data.encounterId!==this.encounterId){this.data=null;this.selected=null;this.error='患者住院上下文不一致，已丢弃快照结果。';return;}this.data=data;}
   catch(e){if(generation===this.generation){this.data=null;this.error='病程快照读取失败：'+errorMessage(e);}}finally{if(generation===this.generation)this.busy=false;}}
 async sync(){const generation=++this.generation;this.busy=true;this.error='';try{const result=await this.api.post<Notes>('/patients/'+encodeURIComponent(this.patientId)+'/clinical-notes/sync',{});
   if(generation===this.generation&&result.patientId===this.patientId&&(!this.encounterId||result.encounterId===this.encounterId)){this.data=result;this.selected=null;this.page=0;}}
   catch(e){if(generation===this.generation)this.error='来源同步失败，已有快照仍可核对：'+errorMessage(e);}finally{if(generation===this.generation)this.busy=false;}}
 async readSnapshot(context:{id:string;asOf:string;ready:boolean;fingerprint:string}){await this.read({id:context.id} as Note,context.ready?context:null);}
 async read(note:Note,context:{id:string;asOf:string;fingerprint:string}|null=null){const generation=++this.generation;this.busy=true;this.error='';this.selected=null;this.reviewContext=null;this.range=null;this.excerpt=null;try{const body=await this.api.get<Note>('/patients/'+encodeURIComponent(this.patientId)+'/clinical-notes/'+encodeURIComponent(note.id));if(generation===this.generation){if(body.id!==note.id){this.error='快照引用不一致，已丢弃原文。';return;}this.selected=body;this.reviewContext=context;}}
   catch(e){if(generation===this.generation)this.error='原文读取失败：'+errorMessage(e);}finally{if(generation===this.generation)this.busy=false;}}
 clearEvidenceSelection(){this.reviewContext=null;this.range=null;this.excerpt=null;}
 selectExcerpt(event:Event){if(!this.reviewContext)return;const node=event.currentTarget as HTMLElement;const selection=window.getSelection();this.range=null;this.excerpt=null;
  if(!selection||!selection.rangeCount)return;const selected=selection.getRangeAt(0);if(!node.contains(selected.startContainer)||!node.contains(selected.endContainer)||selected.collapsed)return;
  const prefix=selected.cloneRange();prefix.selectNodeContents(node);prefix.setEnd(selected.startContainer,selected.startOffset);const start=prefix.toString().length;this.range={start,end:start+selected.toString().length};}
 async verifyExcerpt(){if(!this.reviewContext||!this.range||!this.selected)return;const generation=++this.generation;const context=this.reviewContext,range=this.range,selectedBody=this.selected.body || '';this.busy=true;this.error='';this.excerpt=null;
  try{const response=await this.api.post<{patientId:string;encounterId:string;snapshotId:string;asOf:string;ref:string;excerpt:string;start:number;end:number;packetFingerprint:string}>('/patients/'+encodeURIComponent(this.patientId)+'/clinical-notes/review-evidence/excerpt',{snapshotId:context.id,asOf:context.asOf,start:range.start,end:range.end,expectedFingerprint:context.fingerprint});
   if(generation!==this.generation)return;if(response.patientId!==this.patientId||this.encounterId&&response.encounterId!==this.encounterId||response.snapshotId!==context.id||response.packetFingerprint!==context.fingerprint||Date.parse(response.asOf)!==Date.parse(context.asOf)||response.start!==range.start||response.end!==range.end||response.excerpt!==selectedBody.slice(range.start,range.end)){this.error='片段引用上下文不一致，已丢弃结果。';return;}this.excerpt=response;
  }catch(e){if(generation===this.generation)this.error='片段核对失败：'+errorMessage(e);}finally{if(generation===this.generation)this.busy=false;}}

}
