import { CommonModule } from '@angular/common';
import { Component, inject, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { text } from '../presentation';
import { Api, errorMessage, Page, Patient } from '../api';
@Component({
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  template: `<div class="pagehead">
      <div>
        <h1>患者风险列表</h1>
      </div>
      <div class="page-actions"><button type="button" class="help-button" [attr.aria-expanded]="helpOpen" aria-haspopup="dialog" (click)="helpOpen=true">说明</button><button type="button" (click)="load()" [disabled]="busy">刷新列表</button></div>
    </div>
    <div class="help-backdrop" *ngIf="helpOpen" (click)="helpOpen=false" (keydown.escape)="helpOpen=false">
      <section class="help-panel" role="dialog" aria-modal="true" aria-labelledby="help-title" (click)="$event.stopPropagation()">
        <header><h2 id="help-title">筛查说明</h2><button type="button" aria-label="关闭说明" (click)="helpOpen=false">关闭</button></header>
        <div class="help-content">
          <section><h3>ICU-08 候选</h3><p>参考质控项目口径：入科后 24 小时 P/F 均值，结合诊断关键词、氧疗途径及 PEEP/流量。当前实现分别核算有创（PEEP≥5、P/F均值&lt;150）、无创（PEEP≥5、P/F均值≤200）和高流量（流量≥30、P/F均值≤200）路径。</p><p>这是按来源规则生成的俯卧位分母候选，临床适用性仍需复核；不等于 ARDS 确诊或月度完成率。</p></section>
          <section><h3>检查报告</h3><p>读取 DataCenter 的 VI_ICU_REPORT，按 HIS 患者编号关联，展示近 7 天胸部影像。文本关键词只提示可能的双侧肺部异常；不会自动确认影像条件。打开临床依据复核后，系统可带入报告来源和时间，判断仍由医生完成。</p></section>
          <section><h3>俯卧位记录</h3><p>读取 SmartCare 的 prone_session，详情显示最近最多 30 条记录。记录用于核对治疗经过；当前没有按 ICU-08 统计周期生成全科分子、分母或完成率。</p></section>
          <section><h3>AI 摘要</h3><p>决策摘要会带入专项结果、最多 3 份近 7 天影像摘要，以及俯卧位记录状态。报告文本会标成待核对线索，不会当成医生确认结论。</p></section>
          <section><h3>状态</h3><ul><li><b>需复核：</b>已有异常证据。</li><li><b>未触发：</b>现有观测没有触发规则，不代表排除风险。</li><li><b>资料不足：</b>缺少可用观测，无法评估。</li><li><b>读取失败：</b>数据源请求未成功，与资料不足分开统计。</li></ul></section>
          <section><h3>其他器官风险</h3><ul><li><b>呼吸：</b>呼吸频率、SpO₂、P/F及呼吸支持；ARDS 需结合起病、影像和病因复核。</li><li><b>循环：</b>血压、心率和乳酸异常提示血流动力学风险，不单独等同休克。</li><li><b>肾脏：</b>肌酐变化和逐小时尿量用于 AKI 候选筛查；基线、尿量覆盖和透析背景会影响分期。</li><li><b>凝血：</b>血小板异常是复核线索；DIC 还需基础疾病、PT、D-二聚体和纤维蛋白原等证据。</li><li><b>肝脏：</b>胆红素异常提示肝功能复核；急性肝衰竭需确认 INR、肝性脑病、病程及肝硬化背景。</li><li><b>神经：</b>GCS/RASS 描述意识和镇静背景；谵妄需规范 CAM-ICU 评估，深镇静时不可评估不代表阴性。</li></ul><p>器官图显示证据状态，不是器官衰竭诊断。点开器官可查看观测及缺项。</p></section>
          <section><h3>后续完善</h3><ol><li>核实 ICU-08 正式统计周期、排除条件和俯卧位计入规则。</li><li>补齐当期分母与分子患者明细，并和本页候选名单逐人对账。</li><li>给影像与氧合证据加时间配对、来源追溯和复核状态。</li><li>把对账结果接入科室汇总和 AI 摘要，保留原始依据。</li></ol></section>
        </div>
      </section>
    </div>
    <div class="notice" *ngIf="patients.length">{{ onlySimulated ? '模拟数据' : '包含临床数据' }} · 未启用预测模型</div>
    <section class="card">
      <div class="batch-summary" *ngIf="!search">{{ scanBusy ? '正在筛查 ' + organChecked + ' / ' + scanTotal : '已筛查 ' + organChecked }} · ICU-08候选 {{ icu08Hits.length }} · 器官异常 {{ organHits.length }} · 未触发 {{ organNoTrigger }}<span *ngIf="organUnknown"> · 资料不足 {{ organUnknown }}</span><span *ngIf="organFailed"> · 读取失败 {{ organFailed }}</span></div>
      <form class="toolbar" (ngSubmit)="page = 0; load()">
        <label class="search-label"
          >患者 / 床位<input
            [(ngModel)]="search"
            name="search"
            placeholder="输入姓名或床位" /></label
        ><button class="primary" [disabled]="busy">查询</button
        ><span class="muted" *ngIf="!error">共 {{ total }} 位 · {{ api.user }}</span>
      </form>
      <p class="error" role="alert" *ngIf="error">{{ error }}</p>
      <p *ngIf="busy" aria-live="polite">正在加载患者…</p>
      <div class="tablewrap" *ngIf="!busy && !error">
        <table class="patient-worklist">
          <thead>
            <tr>
              <th>床位</th>
              <th>患者</th>
              <th>心率 <small>bpm</small></th>
              <th>平均动脉压 <small>mmHg</small></th>
              <th>乳酸 <small>mmol/L</small></th>
              <th>筛查</th>
              <th>工作台</th>
            </tr>
          </thead>
          <tbody>
            <tr *ngFor="let p of patients">
              <td>
                <span class="bed">{{ p.bed }}</span>
              </td>
              <td>
                <strong>{{ p.label }}</strong
                >
              </td>
              <td *ngFor="let code of ['HR', 'MAP', 'LACTATE']">
                <strong class="numeric">{{ p.vitals?.[code]?.value ?? '—' }}</strong
                ><small>{{
                  p.vitals?.[code] ? (p.vitals?.[code]?.eventTime | date: 'HH:mm') : (p.vitalsReadStatus === 'unavailable' ? '读取失败' : '缺失 / 过期')
                }}</small>
              </td>
              <td>
                <span
                  class="tag"
                  [class.danger]="p.latestAssessment?.screeningStatus === 'hemodynamic_instability'"
                  >{{
                    p.latestAssessment ? text(p.latestAssessment.screeningStatus) : '尚未筛查'
                  }}</span
                ><small *ngIf="p.latestAssessment">{{ p.latestAssessment.assessmentTime | date: 'MM-dd HH:mm' }} · {{ p.simulated ? '模拟' : '临床' }}</small
                ><small *ngIf="p.summaryLimitReached">摘要达到读取上限，需核查原始记录</small>
              </td>
              <td><a class="button" [routerLink]="['/patients', p.id]">打开</a></td>
            </tr>
          </tbody>
        </table>
        <div class="empty" *ngIf="patients.length === 0">未找到患者</div>
      </div>
      <div class="organ-hits" *ngIf="!search && icu08Hits.length"><h2>ICU-08 俯卧位候选</h2><a *ngFor="let hit of icu08Hits" [routerLink]="['/patients', hit.id]"><b>{{hit.bed}} · {{hit.label}}</b><span>{{hit.severity}}<ng-container *ngIf="hit.pfAverage !== null"> · P/F24h {{hit.pfAverage}}</ng-container><ng-container *ngIf="hit.peep !== null"> · PEEP {{hit.peep}}</ng-container></span></a></div>
      <div class="organ-hits" *ngIf="!search && organHits.length"><h2>其他器官异常待复核</h2><a *ngFor="let hit of organHits" [routerLink]="['/patients', hit.id]"><b>{{hit.bed}} · {{hit.label}}</b><span>{{hit.organs}}</span></a></div>
      <footer class="pager" *ngIf="!error">
        <button (click)="page = page - 1; load()" [disabled]="page === 0 || busy">上一页</button
        ><span>第 {{ page + 1 }} 页</span
        ><button (click)="page = page + 1; load()" [disabled]="(page + 1) * 20 >= total || busy">
          下一页
        </button>
      </footer>
    </section>`,
  styles:[`.page-actions{display:flex;gap:8px;align-items:center}.help-button{background:#edf3f5;border-color:#c8d5d8;color:#25464e}.help-backdrop{position:fixed;inset:0;z-index:1000;background:rgba(17,31,36,.38);display:flex;justify-content:flex-end}.help-panel{width:min(560px,100%);height:100%;overflow:auto;background:#f8faf9;color:#293b40;box-shadow:-12px 0 36px rgba(12,31,36,.18);padding:22px 26px}.help-panel>header{display:flex;align-items:center;justify-content:space-between;position:sticky;top:-22px;margin:-22px -26px 0;padding:18px 26px;background:#f8faf9;border-bottom:1px solid #dce4e4;z-index:1}.help-panel h2{font-size:20px;margin:0;font-weight:650}.help-content{padding:6px 0 28px}.help-content section{padding:14px 0;border-bottom:1px solid #dfe6e5}.help-content h3{font-size:14px;margin:0 0 7px;color:#25434a}.help-content p,.help-content li{font-size:13px;line-height:1.65;color:#536267}.help-content p{margin:5px 0}.help-content ul,.help-content ol{padding-left:22px;margin:6px 0}.batch-summary{display:flex;gap:6px;align-items:center;padding:9px 12px;margin:0 0 12px;background:#edf3f5;border-left:3px solid #397080;color:#24434d;font-size:13px;font-variant-numeric:tabular-nums}.organ-hits{margin-top:16px;border-top:1px solid #d8e0e2;padding-top:10px}.organ-hits h2{margin:0 0 8px;font-size:14px;font-weight:600;color:#263b42}.organ-hits a{display:grid;grid-template-columns:minmax(140px,220px) 1fr;gap:12px;padding:8px 10px;border-bottom:1px solid #e6ebec;color:#35474c;text-decoration:none}.organ-hits a:hover{background:#f5f8f8}.organ-hits a:focus-visible{outline:2px solid #397080;outline-offset:1px}.organ-hits a b{font-weight:600}.organ-hits a span{color:#6b777a}@media(max-width:600px){.organ-hits a{grid-template-columns:1fr;gap:3px}.help-panel{padding:16px 18px}.help-panel>header{top:-16px;margin:-16px -18px 0;padding:14px 18px}}`],
})
export class Patients implements OnInit {
  api = inject(Api);
  text = text;
  patients: Patient[] = [];
  total = 0;
  search = '';
  page = 0;
  busy = false;
  error = '';
  helpOpen=false;
  organChecked=0;scanTotal=0;organFailed=0;organNoTrigger=0;scanBusy=false;private scanGeneration=0;
  organHits:{id:string;bed:string;label:string;organs:string}[]=[];
  icu08Hits:{id:string;bed:string;label:string;severity:string;pfAverage:number|null;peep:number|null}[]=[];
  organUnknown=0;
  get onlySimulated(){return this.patients.every(p=>p.simulated);}
  ngOnInit() {
    void this.load();
  }
  async load() {
    if(this.search||this.page!==0){this.scanGeneration++;this.scanBusy=false;}
    this.busy = true;
    this.error = '';
    try {
      const p = await this.api.get<Page<Patient>>(
        '/patients?search=' + encodeURIComponent(this.search) + '&page=' + this.page,
      );
      this.patients = p.items;
      this.total = p.total;
      if(!this.search && this.page===0) void this.scanAll();
    } catch (e) {
      this.error = errorMessage(e);
    } finally {
      this.busy = false;
    }
  }
  private async scanAll(){
    const generation=++this.scanGeneration;this.scanBusy=true;this.organChecked=0;this.scanTotal=0;this.organFailed=0;this.organUnknown=0;this.organNoTrigger=0;this.organHits=[];this.icu08Hits=[];
    try{
      const first=await this.api.get<Page<Patient>>('/patients?page=0&size=100');
      const all=first.items.slice();
      for(let i=1;i<Math.ceil(first.total/100);i++){const next=await this.api.get<Page<Patient>>('/patients?page='+i+'&size=100');all.push(...next.items);}
      if(generation!==this.scanGeneration)return;this.scanTotal=all.length;
      let cursor=0;
      const worker=async()=>{while(cursor<all.length&&generation===this.scanGeneration){const patient=all[cursor++];try{const data=await this.api.get<any>('/patients/'+encodeURIComponent(patient.id)+'/deterioration');if(generation!==this.scanGeneration)return;if(!data?.organs)this.organFailed++;else{const ards=data.specialty?.modules?.find((m:any)=>m.key==='ards');if(ards?.icu08Severity)this.icu08Hits.push({id:patient.id,bed:patient.bed,label:patient.label,severity:ards.icu08Severity,pfAverage:ards.icu08?.pfAverage??null,peep:ards.icu08?.peep??null});if(data.organs.some((o:any)=>o.status==='review_required'))this.organHits.push({id:patient.id,bed:patient.bed,label:patient.label,organs:data.organs.filter((o:any)=>o.status==='review_required').map((o:any)=>o.label).join('、')});else if(data.organs.some((o:any)=>o.status==='unknown'))this.organUnknown++;else if(data.organs.every((o:any)=>o.status==='no_observed_trigger'))this.organNoTrigger++;}}catch{if(generation!==this.scanGeneration)return;this.organFailed++;}this.organChecked++;}};
      await Promise.all(Array.from({length:Math.min(4,all.length)},worker));
    }catch{if(generation===this.scanGeneration)this.organFailed++;}
    finally{if(generation===this.scanGeneration)this.scanBusy=false;}
  }
}
