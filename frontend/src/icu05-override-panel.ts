import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output, OnChanges, OnDestroy, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, errorMessage } from './api';
import { Icu05Result, Icu05OverrideRecord } from './icu05-view';
import { uuid4 } from './uuid';
@Component({selector:'icu05-override-panel',standalone:true,imports:[CommonModule,FormsModule],template:
`<details *ngIf="data.overrides" class="i5-overrides">
  <summary>判定项与 SOFA 人工复核</summary>
  <p>自动结果与人工结果分别保留。判定项覆盖将重算对应依赖；SOFA 分项仅辅助展示，不参与质控分子分母。</p>
  <button type="button" *ngIf="canWrite && !mode" [disabled]="api.icu05ReviewDirty || api.icu05ReviewBusy || !data.t0" (click)="start()">新增判定项或 SOFA 覆盖</button>
  <ul><li *ngFor="let r of data.overrides">
    {{ r.windowKey }} · {{ label(r.targetId) }} · 自动 {{ valueText(r.autoValue) }} → 人工 {{ valueText(r.manualValue) }}
    · {{ r.applied ? '已应用' : '需重新复核，未应用' }} · {{ r.operator }} · {{ r.createdAt | date:'MM-dd HH:mm:ss' }} · {{ r.reasonText }}
    <button type="button" *ngIf="canWrite" [disabled]="!!mode || api.icu05ReviewDirty || api.icu05ReviewBusy" (click)="start(r)">撤销此项覆盖</button>
  </li></ul>
  <form *ngIf="mode" (ngSubmit)="save()">
    <ng-container *ngIf="mode === 'create'">
      <label>覆盖层级<select name="scope" [(ngModel)]="scope" (ngModelChange)="resetScope()" [disabled]="busy"><option value="judgement">判定项</option><option value="sofa_component">SOFA 分项（实验性）</option></select></label>
      <label>窗口或版本<select name="window" [(ngModel)]="windowKey" (ngModelChange)="resetTarget()" [disabled]="busy"><option *ngFor="let w of windows" [value]="w">{{ windowLabel(w) }}</option></select></label>
      <label>覆盖对象<select name="target" [(ngModel)]="targetId" [disabled]="busy"><option *ngFor="let t of targets" [value]="t">{{ label(t) }}</option></select></label>
      <label *ngIf="scope === 'judgement'">人工结果<select name="judgement" [(ngModel)]="judgementValue" [disabled]="busy"><option [ngValue]="true">成立</option><option [ngValue]="false">不成立</option></select></label>
      <label *ngIf="scope === 'sofa_component'">人工器官分数（0–4）<input name="score" type="number" min="0" max="4" step="1" required [(ngModel)]="componentScore" [disabled]="busy"/></label>
      <p *ngIf="k1Blocked" role="status">乳酸取值窗口待确认；K1 保持无法判定，暂不允许人工覆盖。</p>
      <label>原因<select name="reasonCode" [(ngModel)]="reasonCode" [disabled]="busy"><option *ngFor="let r of reasons" [value]="r.code">{{ r.label }}</option></select></label>
    </ng-container>
    <p *ngIf="mode === 'revoke'">撤销 {{ selected?.windowKey }} · {{ label(selected?.targetId || '') }}；保留记录，按当前自动值和其余有效覆盖重新计算。</p>
    <label>复核说明<textarea name="reason" required maxlength="1000" [(ngModel)]="reason" [disabled]="busy"></textarea></label>
    <button type="button" (click)="cancel()" [disabled]="busy">取消覆盖复核</button><button type="submit" [disabled]="!valid || busy">{{ busy ? '正在保存…' : '保存覆盖复核' }}</button>
  </form>
  <ng-container *ngIf="data.experimentalSofa as sofa">
    <div *ngFor="let variant of ['classic','sofa2']">
      <h4>{{ windowLabel(variant) }} · 人工分项复核（实验性）</h4>
      <p>{{ sofaVersion(variant)?.note }}</p>
      <p>已评估 {{ sofaVersion(variant)?.scored }}/6 项 · 总分 {{ sofaVersion(variant)?.total ?? '无法判定' }} · 部分合计 {{ sofaVersion(variant)?.partial ?? '—' }}</p>
      <ul><li *ngFor="let c of sofaVersion(variant)?.components">{{ label(c.key) }}：{{ c.score ?? '无法判定' }} · {{ c.manual ? '人工值' : '自动值缺失' }}</li></ul>
    </div>
  </ng-container>
  <button type="button" *ngIf="canWrite" [disabled]="busy" (click)="loadHistory()">查看判定项复核历史</button>
  <ul *ngIf="history"><li *ngFor="let r of history">{{ r.windowKey }} · {{ label(r.targetId) }} · 自动 {{ valueText(r.autoValue) }} → 人工 {{ valueText(r.manualValue) }} · {{ r.operator }} · {{ r.reasonText }}
    <span *ngIf="r.revokedAt"> · 已撤销 {{ r.revokedAt | date:'MM-dd HH:mm:ss' }} · {{ r.revokedBy }} · {{ r.revokeReason }}</span></li></ul>
  <p role="alert" class="review-inline-error" *ngIf="error">{{ error }}</p>
</details>`})
export class Icu05OverridePanel implements OnChanges,OnDestroy {
  @Input({required:true}) data!: Icu05Result;
  @Output() changed=new EventEmitter<Icu05Result>();
  api=inject(Api); mode:'create'|'revoke'|null=null; scope:'judgement'|'sofa_component'='judgement'; windowKey='gate';targetId='S1';
  judgementValue=false;componentScore:number|null=null;reasonCode='other';reason='';busy=false;error='';history:Icu05OverrideRecord[]|null=null;
  selected:Icu05OverrideRecord|null=null;private identity='';private draftIdentity='';private fingerprint='';private key='';private disposed=false;
  get canWrite(){return this.api.has('DOCTOR')||this.api.has('QUALITY');}
  get windows(){return this.scope==='judgement'?['gate','1h','3h','6h']:['classic','sofa2'];}
  get targets(){return this.scope==='sofa_component'?['respiratory','brain','hemostasis','liver','kidney','cardiovascular']:this.windowKey==='gate'?['S1','S2','S3','S4','I1','I2','I3','K1','K2']:['A1','B1','B2','B3','C1','C2','C3'];}
  get reasons(){return (this.scope==='sofa_component'?[['chronic_baseline','慢性基线异常'],['clinical_mismatch','与临床不符'],['data_error','数据错误'],['ceiling','评分上限'],['other','其他']]:[['not_septic_shock','非脓毒性休克'],['outside_treated','外院已治疗'],['dnr','放弃积极治疗'],['t0_wrong','T0 锚点错误'],['data_error','数据错误'],['other','其他']]).map(([code,label])=>({code,label}));}
  get k1Blocked(){return this.scope==='judgement'&&this.targetId==='K1'&&!this.data.gate?.shock.find(i=>i.code==='K1')?.windowConfirmed;}
  get valid(){return this.reason.trim().length>0&&(this.mode==='revoke'||!this.k1Blocked&&(this.scope==='judgement'||this.componentScore!==null&&Number.isInteger(this.componentScore)&&this.componentScore>=0&&this.componentScore<=4));}
  windowLabel(w:string){return ({gate:'门控',classic:'经典 SOFA',sofa2:'SOFA-2'} as Record<string,string>)[w]||w;}
  label(t:string){return ({S1:'氧合指数',S2:'GCS',S3:'MAP',S4:'血管活性药',I1:'感染诊断证据',I2:'抗感染执行',I3:'病原学送检',K1:'乳酸最高值',K2:'宽口径血管活性药',A1:'乳酸测定',B1:'抗菌药执行',B2:'血培养',B3:'先培养后抗菌',C1:'MAP 异常触发',C2:'乳酸异常触发',C3:'补液',respiratory:'呼吸',brain:'神经',hemostasis:'凝血',liver:'肝脏',kidney:'肾脏',cardiovascular:'循环'} as Record<string,string>)[t]||t;}
  valueText(v:boolean|number|null){return v===null?'无法判定':v===true?'成立':v===false?'不成立':String(v);}
  sofaVersion(v:string){return v==='classic'?this.data.experimentalSofa?.classic:this.data.experimentalSofa?.sofa2;}
  resetScope(){this.windowKey=this.windows[0];this.reasonCode='other';this.componentScore=null;this.resetTarget();}
  resetTarget(){this.targetId=this.targets[0];}
  start(record?:Icu05OverrideRecord){
    if(this.api.icu05ReviewBusy||this.api.icu05ReviewDirty||!this.canWrite)return;
    this.selected=record?{...record}:null;this.mode=record?'revoke':'create';this.reason='';this.error='';this.key=uuid4();
    this.fingerprint=this.data.automaticFingerprint||'';this.draftIdentity=this.identity;this.api.icu05ReviewDirty=true;
  }
  cancel(){if(this.busy)return;this.mode=null;this.api.icu05ReviewDirty=false;}
  ngOnChanges(){const id=this.data.patientId+':'+this.data.encounterId;if(this.identity&&id!==this.identity){if(this.mode)this.api.icu05ReviewDirty=false;this.mode=null;this.reason='';this.history=null;this.error='';}this.identity=id;}
  ngOnDestroy(){this.disposed=true;if(this.mode)this.api.icu05ReviewDirty=false;}
  async save(){
    if(!this.mode||!this.valid||this.busy||this.draftIdentity!==this.identity)return;
    const id=this.identity,patient=this.data.patientId,path='/patients/'+encodeURIComponent(patient)+'/icu05-overrides';
    const payload=this.mode==='revoke'?{expectedVersion:this.selected!.version,reasonText:this.reason}:{scope:this.scope,windowKey:this.windowKey,targetId:this.targetId,expectedFingerprint:this.fingerprint,judgementValue:this.scope==='judgement'?this.judgementValue:null,componentScore:this.scope==='sofa_component'?this.componentScore:null,reasonCode:this.reasonCode,reasonText:this.reason};
    const url=path+(this.mode==='revoke'?'/'+encodeURIComponent(this.selected!.id)+'/revoke':'');this.busy=true;this.api.icu05ReviewBusy=true;this.error='';
    try{await this.api.post(url,payload,this.key);if(this.disposed||id!==this.identity)return;this.mode=null;this.api.icu05ReviewDirty=false;this.history=null;
      const data=await this.api.get<Icu05Result>('/patients/'+encodeURIComponent(patient)+'/icu05-bundle');if(!this.disposed&&id===this.identity)this.changed.emit(data);
    }catch(error){if(!this.disposed&&id===this.identity)this.error=errorMessage(error);}finally{this.busy=false;this.api.icu05ReviewBusy=false;}
  }
  async loadHistory(){const id=this.identity;try{const rows=await this.api.get<Icu05OverrideRecord[]>('/patients/'+encodeURIComponent(this.data.patientId)+'/icu05-overrides');if(!this.disposed&&id===this.identity)this.history=rows;}catch(error){if(!this.disposed&&id===this.identity)this.error=errorMessage(error);}}
}
