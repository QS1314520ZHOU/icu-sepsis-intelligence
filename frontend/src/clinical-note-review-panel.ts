import { CommonModule } from '@angular/common';
import { Component, Input, OnChanges, OnDestroy, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, errorMessage } from './api';
export interface VerifiedNoteQuote {patientId:string;encounterId:string;snapshotId:string;packetFingerprint:string;asOf:string;ref:string;excerpt:string;start:number;end:number;}
interface Selection extends VerifiedNoteQuote {category:string;}
interface ReviewDraft {patientId:string;encounterId:string;asOf:string;packetFingerprint:string;modelInvoked:boolean;items:(Selection&{signals:string[]})[];coverage:string[];text:string;}
@Component({selector:'clinical-note-review-panel',standalone:true,imports:[CommonModule,FormsModule],template:`<section aria-label="本地病程综合复核草稿"><h3>本地病程综合复核草稿</h3>
<p>AI 综合复核尚未启用。此处只整理医生所选片段及有限文字信号，不推断医学结论；不发送模型、不保存草稿或改写规则。</p>
<ng-container *ngIf="quote"><label>所选片段分类<select aria-label="病程片段分类" [(ngModel)]="category"><option *ngFor="let c of categories" [value]="c.key">{{c.label}}</option></select></label><button type="button" (click)="add()" [disabled]="busy || selections.length>=12">加入复核草稿</button></ng-container>
<p *ngIf="selections.length">已选 {{selections.length}} 个片段；同一时点与证据集合，最多 12 个。</p>
<ol><li *ngFor="let s of selections;let i=index">{{label(s.category)}} · {{s.ref}} · {{s.excerpt}} <button type="button" (click)="remove(i)" [disabled]="busy">移除片段</button></li></ol>
<button type="button" (click)="build()" [disabled]="!selections.length || busy">{{busy?'核对中…':'生成本地复核草稿'}}</button><button type="button" (click)="clear()" *ngIf="selections.length" [disabled]="busy">清空所选片段</button>
<p *ngIf="error" role="alert">{{error}}</p><ng-container *ngIf="draft"><p *ngFor="let c of draft.coverage">{{c}}</p>
<details><summary>片段文字信号及受控来源</summary><div *ngFor="let item of draft.items"><p>{{item.ref}}：{{item.excerpt}}</p><p *ngFor="let signal of item.signals">{{signal}}</p></div></details>
<label>可编辑本地草稿<textarea aria-label="本地病程复核草稿" rows="18" [(ngModel)]="edited" (ngModelChange)="copied=false" style="width:100%"></textarea></label>
<p>生成于 {{generatedAt | date:'yyyy-MM-dd HH:mm:ss'}}；复核时点 {{draft.asOf | date:'yyyy-MM-dd HH:mm:ss'}}。页面草稿不会随病情自动更新，复制前须重新核对；修改后的文字未经过系统证据核验。</p>
<button type="button" (click)="copy()">{{copied?'已复制本地草稿':'复制本地复核草稿'}}</button><p>仅复制到本机剪贴板，不自动写入正式病历。片段可能含身份信息，请在授权范围内使用。</p></ng-container></section>`})
export class ClinicalNoteReviewPanel implements OnChanges,OnDestroy {
 @Input() patientId='';@Input() encounterId='';@Input() quote:VerifiedNoteQuote|null=null;api=inject(Api);category='check';selections:Selection[]=[];draft:ReviewDraft|null=null;edited='';busy=false;error='';copied=false;generatedAt='';private generation=0;
 categories=[{key:'infection',label:'支持或提示感染的待核对片段'},{key:'organ',label:'器官功能及基线待核对片段'},{key:'alternative',label:'其他可能原因待核对片段'},{key:'conflict',label:'相反证据、冲突及缺失'},{key:'check',label:'医生待核对事项'}];
 label(key:string){return this.categories.find(c=>c.key===key)?.label||'待核对';}
 ngOnChanges(changes:import('@angular/core').SimpleChanges){if(changes['patientId']||changes['encounterId'])this.clear();else if(changes['quote']&&this.quote&&this.selections.length&&(this.quote.packetFingerprint!==this.selections[0].packetFingerprint||this.quote.asOf!==this.selections[0].asOf))this.clear();}
 ngOnDestroy(){++this.generation;this.selections=[];this.draft=null;this.edited='';}
 clear(){++this.generation;this.selections=[];this.draft=null;this.edited='';this.busy=false;this.error='';this.copied=false;this.generatedAt='';}
 invalidate(){++this.generation;this.draft=null;this.edited='';this.error='';this.copied=false;}
 add(){const q=this.quote;if(!q||q.patientId!==this.patientId||q.encounterId!==this.encounterId)return;
  if(this.selections.some(s=>s.snapshotId===q.snapshotId&&s.start===q.start&&s.end===q.end&&s.category===this.category))return;
  this.invalidate();this.selections=[...this.selections,{...q,category:this.category}];}
 remove(index:number){this.invalidate();this.selections=this.selections.filter((_,i)=>i!==index);}
 async build(){if(!this.selections.length)return;const selection=this.selections.map(s=>({...s})),first=selection[0],generation=++this.generation;this.busy=true;this.error='';this.draft=null;this.edited='';this.copied=false;
  try{const result=await this.api.post<ReviewDraft>('/patients/'+encodeURIComponent(this.patientId)+'/clinical-notes/review-draft',{asOf:first.asOf,expectedFingerprint:first.packetFingerprint,selections:selection.map(s=>({snapshotId:s.snapshotId,start:s.start,end:s.end,category:s.category}))});
   if(generation!==this.generation)return;
   if(result.patientId!==this.patientId||result.encounterId!==this.encounterId||Date.parse(result.asOf)!==Date.parse(first.asOf)||result.packetFingerprint!==first.packetFingerprint||result.modelInvoked!==false||!Array.isArray(result.items)||result.items.length!==selection.length||result.items.some(r=>!selection.some(s=>r.snapshotId===s.snapshotId&&r.start===s.start&&r.end===s.end&&r.category===s.category&&r.excerpt===s.excerpt&&r.ref===s.ref))){this.error='复核草稿来源或上下文不一致，已丢弃。';return;}
   this.draft=result;this.edited=result.text;this.generatedAt=new Date().toISOString();
  }catch(e){if(generation===this.generation)this.error='本地草稿暂不可用：'+errorMessage(e);}finally{if(generation===this.generation)this.busy=false;}}
 async copy(){const generation=this.generation;try{await navigator.clipboard.writeText(this.edited);if(generation===this.generation)this.copied=true;}catch{if(generation===this.generation)this.error='复制失败，可在文本框中手动选择复制。';}}
}
