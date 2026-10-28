import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output, inject, OnInit, OnDestroy } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { UiIcon } from '../ui-icon';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Api, Assessment, errorMessage, Observation, Page, Patient } from '../api';
import { ClinicalTrends } from '../clinical-trends';
import { label, evidenceNote, timeLabel } from '../trends';
import { riskText, text } from '../presentation';
import { Level, level } from '../clinical-flags';
interface SheetCol { key: string; start: number; dayStart: boolean; }
interface SheetCell { record: Observation; count: number; conflict: boolean; candidate: boolean; text: string; level: Level; }
interface SheetRow { code: string; label: string; unit: string; cells: Record<string, SheetCell>; }
const sheetOrder = ['MAP_IBP','SBP_IBP','DBP_IBP','MAP_NIBP','SBP_NIBP','DBP_NIBP','MAP','SBP','DBP','HR','RR','SPO2','TEMP','GCS','LACTATE','SOFA','APACHEII'];
@Component({
  selector: 'patient-detail',
  standalone: true,
  imports: [CommonModule, RouterLink, ClinicalTrends, FormsModule, UiIcon],
  template: `<a *ngIf="!embedded" class="back" routerLink="/patients">← 患者列表</a>
    <div class="pagehead" *ngIf="!embedded">
      <div>
        <p class="eyebrow">PATIENT EVIDENCE WORKSPACE</p>
        <h1>
          {{ patient?.label || '患者详情' }}
          <span class="tag" *ngIf="patient">{{ patient.bed }} 床 · 模拟</span>
        </h1>
        <p class="muted">{{ patient?.encounterId }} · 时间显示为本机时区，API 使用 UTC</p>
      </div>
      <button class="primary" *ngIf="api.has('DOCTOR')" (click)="assess()" [disabled]="busy">
        {{ busy ? '评估处理中…' : '执行规则筛查' }}
      </button>
    </div>
    <div class="notice" *ngIf="!embedded">仅模拟演示 · 不能代替临床诊断 · 不自动开立医嘱 · 模型不可用</div>
    <p class="error" role="alert" *ngIf="error">{{ error }}</p>
    <section
      class="assessment-ribbon"
      *ngIf="!embedded && !error && history[0] as a"
      aria-label="最近一次筛查摘要"
    >
      <div>
        <small>最近筛查 · 非诊断</small
        ><strong [class.attention-text]="a.screeningStatus === 'hemodynamic_instability'">{{
          text(a.screeningStatus)
        }}</strong>
      </div>
      <div>
        <small>qSOFA · 非概率</small
        ><strong
          >{{ a.ruleScore ?? '无法计算' }} <span *ngIf="a.ruleScore !== null">/ 3</span></strong
        >
      </div>
      <div>
        <small>筛查时关键数据</small
        ><strong>{{
          a.missingFeatures.length
            ? a.missingFeatures.length + ' 项缺失 / 过期 / 无效'
            : '满足演示筛查输入'
        }}</strong>
      </div>
      <div>
        <small>{{ a.assessmentTime | date: 'MM-dd HH:mm:ss' }}</small
        ><a
          class="button"
          [routerLink]="embedded ? null : '/alerts'"
          (click)="embedded && openAlerts.emit()"
          (keydown.enter)="embedded && openAlerts.emit()"
          [attr.tabindex]="embedded ? 0 : null"
          [attr.role]="embedded ? 'button' : null"
          >进入预警复核 →</a
        >
      </div>
    </section>
    <header class="sx-records-bar" *ngIf="embedded">
      <h1><ui-icon name="file"/>记录与依据</h1>
      <div class="segmented" role="group" aria-label="记录视图"><button *ngFor="let v of modes" [class.selected]="mode === v.key" [attr.aria-pressed]="mode === v.key" (click)="setMode(v.key)">{{ v.label }}</button></div>
      <div class="record-actions"><select aria-label="记录时间范围" [(ngModel)]="selectedHours" (change)="loadRange(+selectedHours)"><option [ngValue]="12">近12小时</option><option [ngValue]="24">近24小时</option><option [ngValue]="168">近7天</option><option [ngValue]="720">近30天</option></select><select *ngIf="mode !== 'chart'" aria-label="筛选指标" [(ngModel)]="recordCode" (change)="recordPage=0; rebuildSheet()"><option value="">全部指标</option><option *ngFor="let code of recordCodes" [value]="code">{{ label(code) }}</option></select><button (click)="load()"><ui-icon name="refresh"/>刷新</button></div>
    </header>
    <clinical-trends
      *ngIf="!error && patient && (!embedded || mode === 'chart')"
      [items]="observations"
      [asOf]="asOf"
      [embedded]="embedded"
      [availableHours]="loadedHours" [windowHours]="selectedHours" [busy]="loadingRecords"
      (requestRange)="loadRange($event)" (openRecords)="embedded ? setMode('sheet') : openRecords.emit()"
    />
    <div class="workspace records-workspace" [class.embedded-records]="embedded" *ngIf="!error && (!embedded || mode !== 'chart')">
      <section class="card">
        <div class="sx-sheet-wrap" *ngIf="embedded && mode === 'sheet'">
          <div class="record-meta"><span><ui-icon name="clock"/>{{ asOf | date:'MM-dd HH:mm' }} 读取 · {{ bucketLabel }}一列，取该时段最后一次记录</span><span class="sx-legend"><i class="crit"></i>危急<i class="abn"></i>异常<i class="cand"></i>归属未确认</span></div>
          <p class="error" role="alert" *ngIf="recordsError">{{ recordsError }}</p>
          <div class="read-limit" *ngIf="observations.length>=2000"><ui-icon name="info"/>当前读取可能截断，不代表所选时段完整记录。</div>
          <div class="chart-loading" *ngIf="loadingRecords" role="status"><span class="spinner"></span>正在读取记录…</div>
          <div class="tablewrap sx-sheet-scroll" *ngIf="sheetCols.length && !loadingRecords">
            <table class="sx-sheet" aria-label="护理记录单式数据表">
              <thead><tr><th scope="col" class="sx-sheet-name">项目</th><th scope="col" *ngFor="let c of pagedCols" [class.sx-day-start]="c.dayStart"><small *ngIf="c.dayStart || bucketMs < 86400000">{{ c.start | date:'MM-dd' }}</small>{{ bucketMs >= 86400000 ? '' : (c.start | date:'HH:mm') }}</th></tr></thead>
              <tbody><tr *ngFor="let r of sheetRows"><th scope="row" class="sx-sheet-name">{{ r.label }}<small>{{ r.unit }}</small></th>
                <td *ngFor="let c of pagedCols" [attr.data-level]="r.cells[c.key]?.level" [class.cand]="r.cells[c.key]?.candidate" [class.conflict]="r.cells[c.key]?.conflict" [class.sx-day-start]="c.dayStart">
                  <button *ngIf="r.cells[c.key] as cell" class="sx-cell" (click)="selectedRecord = cell.record" [title]="cell.count + ' 条记录 · 最后 ' + (cell.record.eventTime | date:'MM-dd HH:mm') + (cell.conflict ? ' · 同时刻数值不一致' : '') + (cell.candidate ? ' · 住院归属未确认' : '')">{{ cell.conflict ? '冲突' : cell.text }}<sup *ngIf="cell.count > 1">{{ cell.count }}</sup></button>
                </td></tr></tbody>
            </table>
          </div>
          <footer class="pager" *ngIf="sheetCols.length > colsPerPage"><button [disabled]="sheetPage===0" (click)="sheetPage=sheetPage-1">← 较新</button><span>{{ sheetPage+1 }} / {{ sheetPageCount }}</span><button [disabled]="sheetPage+1>=sheetPageCount" (click)="sheetPage=sheetPage+1">较早 →</button></footer>
          <div class="work-empty" *ngIf="!sheetCols.length && !recordsError && !loadingRecords"><ui-icon name="empty"/><strong>所选范围暂无记录</strong><span>可扩大范围查找历史数据</span><button class="primary" (click)="loadRange(720)">查看近30天</button></div>
        </div>
        <ng-container *ngIf="!embedded || mode === 'list'">
        <div class="section-head">
          <h2><ui-icon name="file"/>临床数据 <span class="neutral-tag">{{ windowRows.length }} 条已读取</span></h2>
          <div class="record-actions"><select *ngIf="!embedded" aria-label="记录时间范围" [(ngModel)]="selectedHours" (change)="loadRange(+selectedHours)"><option [ngValue]="12">近12小时</option><option [ngValue]="24">近24小时</option><option [ngValue]="168">近7天</option><option [ngValue]="720">近30天</option></select><button (click)="showAll=!showAll; recordPage=0">{{ showAll ? '仅看最新' : '原始记录' }}</button><button *ngIf="!embedded" (click)="load()"><ui-icon name="refresh"/>刷新</button></div>
        </div>
        <p class="muted small" *ngIf="!embedded">
          {{ observations.length }} 条后端原始记录 · 默认按指标显示最新记录；展开后按时间倒序。
        </p>
        <div class="record-meta"><span><ui-icon name="clock"/>{{ asOf | date:'MM-dd HH:mm' }} 读取 <span class="caution-tag" *ngIf="selectedHours>24">含历史记录</span></span><label *ngIf="!embedded">指标<select aria-label="筛选指标" [(ngModel)]="recordCode" (change)="recordPage=0"><option value="">全部指标</option><option *ngFor="let code of recordCodes" [value]="code">{{ label(code) }}</option></select></label></div>
        <p class="error" role="alert" *ngIf="recordsError">{{ recordsError }}</p>
        <div class="read-limit" *ngIf="observations.length>=2000"><ui-icon name="info"/>当前读取可能截断，不代表所选时段完整记录。</div>
        <div class="chart-loading" *ngIf="loadingRecords" role="status"><span class="spinner"></span>正在读取记录…</div>
        <div class="tablewrap">
          <table>
            <thead>
              <tr>
                <th>数据项</th>
                <th>值 / 记录</th>
                <th>事件时间</th>
                <th>来源</th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let o of tableRows">
                <td>
                  {{ label(o.parameterCode) }}<small>{{ o.sourceParameterCode || o.parameterCode }}</small><small class="missing" *ngIf="o.clinicalUse === 'context_only'">归属未确认 · 不参与判断</small>
                </td>
                <td>{{ o.text || (o.value ?? '—') }} {{ o.unit }}</td>
                <td>{{ o.eventTime | date: 'MM-dd HH:mm' }}<small>{{ o.timeBasis === 'reported' ? '报告 / 审核；采样未知' : '观测时间' }}</small></td>
                <td>
                  {{ o.sourceSystem }}<button class="source-link" (click)="selectedRecord=o">查看来源</button>
                </td>
              </tr>
            </tbody>
          </table>
          <div class="work-empty" *ngIf="!filteredRows.length && !recordsError && !loadingRecords"><ui-icon name="empty"/><strong>所选范围暂无记录</strong><span>可扩大范围查找历史数据</span><button class="primary" (click)="loadRange(720)">查看近30天</button></div>
        </div><footer class="pager" *ngIf="showAll && filteredRows.length>30"><span>已读取 {{ filteredRows.length }} 条</span><button [disabled]="recordPage===0" (click)="recordPage=recordPage-1">上一页</button><span>{{ recordPage+1 }} / {{ pageCount }}</span><button [disabled]="recordPage+1>=pageCount" (click)="recordPage=recordPage+1">下一页</button></footer>
        </ng-container>
      </section>
      <section class="card" *ngIf="!embedded">
        <h2>
          {{
            assessment && history[0] && assessment.id !== history[0].id
              ? '所选历史筛查'
              : '最近一次筛查'
          }}
        </h2>
        <button
          *ngIf="assessment && history[0] && assessment.id !== history[0].id"
          (click)="assessment = history[0]"
        >
          回到最近一次
        </button>
        <ng-container *ngIf="assessment; else noAssessment"
          ><p class="muted small">
            {{ assessment.assessmentTime | date: 'yyyy-MM-dd HH:mm:ss' }} ·
            {{ assessment.ruleVersion }}
          </p>
          <dl>
            <dt>感染证据</dt>
            <dd>{{ text(assessment.infectionVerdict) }}</dd>
            <dt>当前筛查</dt>
            <dd>{{ text(assessment.screeningStatus) }}</dd>
            <dt>休克筛查</dt>
            <dd>{{ text(assessment.shockScreeningStatus) }}</dd>
            <dt>qSOFA 规则分数</dt>
            <dd>{{ assessment.ruleScore ?? '无法计算' }} / 3（非概率）</dd>
            <dt>完整 SOFA</dt>
            <dd>未实现，不将缺失项视为正常</dd>
          </dl>
          <div class="missing" *ngIf="assessment.missingFeatures.length">
            缺失 / 过期 / 无效：{{ assessment.missingFeatures.join('、') }}
          </div>
          <div class="prediction" *ngFor="let p of assessment.predictions">
            <strong>未来 {{ p.horizonHours }} 小时</strong
            ><span>{{ riskText(p.predictionSource, p.riskValue) }}</span>
          </div>
          <a
            [routerLink]="embedded ? null : '/alerts'"
            (click)="embedded && openAlerts.emit()"
            (keydown.enter)="embedded && openAlerts.emit()"
            [attr.tabindex]="embedded ? 0 : null"
            [attr.role]="embedded ? 'button' : null"
            class="button"
            >进入预警复核 →</a
          ></ng-container
        ><ng-template #noAssessment
          ><div class="empty">
            尚未执行评估。缺乏数据或模型时，系统不会强行产生概率。
          </div></ng-template
        >
      </section>
    </div>
    <details class="assessment-history" *ngIf="!embedded || mode === 'list'"><summary><ui-icon name="clock"/>历次评估记录 <span>{{ history.length }}</span></summary>
      <section class="card"><h2>历次风险评估</h2>
      <p class="muted small">查看评估时间、来源及缺失项，证据快照存储于后端。</p>
      <button class="historyitem" *ngFor="let a of history" (click)="assessment = a">
        {{ a.assessmentTime | date: 'MM-dd HH:mm:ss' }} · {{ text(a.screeningStatus) }} ·
        {{ text(a.infectionVerdict) }}
      </button>
      <div class="empty" *ngIf="!history.length">暂无历史评估</div>
    </section></details>
    <section class="record-source-drawer" *ngIf="selectedRecord"><div class="section-head"><h2><ui-icon name="file"/>{{ label(selectedRecord.parameterCode) }}</h2><button (click)="selectedRecord=null">关闭</button></div><div class="evidence-reading">{{ selectedRecord.value ?? '—' }}<small>{{ selectedRecord.unit }}</small></div><dl><dt>{{ timeLabel(selectedRecord.timeBasis) }}</dt><dd>{{ selectedRecord.eventTime | date:'yyyy-MM-dd HH:mm:ss' }}</dd><dt>来源系统</dt><dd>{{ selectedRecord.sourceSystem }}</dd><dt>来源记录</dt><dd>{{ selectedRecord.sourceRecordId }}</dd><dt>源指标</dt><dd>{{ selectedRecord.sourceParameterCode || selectedRecord.parameterCode }}</dd></dl><p class="context-warning" *ngIf="evidenceNote(selectedRecord)">{{ evidenceNote(selectedRecord) }}</p></section>`,
})
export class Detail implements OnInit, OnDestroy {
  @Input() embedded = false;
  @Input() patientId = '';
  @Input() panel: 'trends' | 'evidence' = 'trends';
  @Input() initialHours = 12;
  @Output() openRecords = new EventEmitter<void>();
  @Output() openAlerts = new EventEmitter<void>();
  api = inject(Api);
  route = inject(ActivatedRoute);
  id = '';
  patient: Patient | null = null;
  observations: Observation[] = [];
  assessment: Assessment | null = null;
  history: Assessment[] = [];
  asOf = '';
  showAll = false;
  label = label;
  recordPage = 0; recordCode = ''; selectedHours=12; loadedHours=24;
  loadingRecords=false; recordsError=''; selectedRecord:Observation|null=null;
  private generation=0;
  evidenceNote=evidenceNote; timeLabel=timeLabel;
  get windowRows() {
    const end=Date.parse(this.asOf), start=end-this.selectedHours*3600000;
    return this.observations.filter(o=>Number.isFinite(end) && Date.parse(o.eventTime)>=start && Date.parse(o.eventTime)<=end);
  }
  get recordCodes() { return [...new Set(this.windowRows.map(o=>o.parameterCode))]; }
  get filteredRows() {
    const sorted=[...this.windowRows].filter(o=>!this.recordCode || o.parameterCode===this.recordCode).sort((a,b)=>Date.parse(b.eventTime)-Date.parse(a.eventTime));
    return this.showAll ? sorted : sorted.filter((o,i,a)=>a.findIndex(x=>x.parameterCode===o.parameterCode)===i);
  }
  get tableRows() { return this.showAll ? this.filteredRows.slice(this.recordPage*30,this.recordPage*30+30) : this.filteredRows; }
  get pageCount() { return Math.ceil(this.filteredRows.length/30); }
  loadRange(h:number) { this.selectedHours=h;this.recordPage=0;this.sheetPage=0;this.loadedHours=Math.max(24,h);void this.load(); }
  modes: {key: 'sheet'|'chart'|'list'; label: string}[] = [{key:'sheet',label:'记录单'},{key:'chart',label:'趋势图'},{key:'list',label:'明细'}];
  mode: 'sheet'|'chart'|'list' = 'sheet';
  setMode(m: 'sheet'|'chart'|'list') { this.mode=m; this.selectedRecord=null; if(m==='list') this.showAll=true; }
  colsPerPage = 12; sheetPage = 0; bucketMs = 3600000;
  sheetCols: SheetCol[] = []; sheetRows: SheetRow[] = [];
  get pagedCols() { return this.sheetCols.slice(this.sheetPage*this.colsPerPage, this.sheetPage*this.colsPerPage+this.colsPerPage); }
  get sheetPageCount() { return Math.ceil(this.sheetCols.length/this.colsPerPage); }
  get bucketLabel() { return this.bucketMs>=86400000 ? '每天' : this.bucketMs>=4*3600000 ? '每 4 小时' : '每小时'; }
  /** Flowsheet: rows are parameters, columns are local time buckets (newest first). Never fills gaps. */
  rebuildSheet() {
    this.bucketMs = this.selectedHours<=24 ? 3600000 : this.selectedHours<=168 ? 4*3600000 : 86400000;
    const b=this.bucketMs, rows=this.windowRows.filter(o=>!this.recordCode || o.parameterCode===this.recordCode);
    const start=(t:number)=>{const tz=-new Date(t).getTimezoneOffset()*60000;return Math.floor((t+tz)/b)*b-tz;};
    const cols=new Map<number,SheetCol>(), byCode=new Map<string,Observation[]>();
    for(const o of rows){const t=Date.parse(o.eventTime);if(!Number.isFinite(t))continue;const k=start(t);if(!cols.has(k))cols.set(k,{key:String(k),start:k,dayStart:false});(byCode.get(o.parameterCode)||byCode.set(o.parameterCode,[]).get(o.parameterCode)!).push(o);}
    this.sheetCols=[...cols.values()].sort((a,c)=>c.start-a.start);
    this.sheetCols.forEach((c,i,a)=>c.dayStart=i===0||new Date(c.start).toDateString()!==new Date(a[i-1].start).toDateString());
    const order=sheetOrder;
    this.sheetRows=[...byCode.keys()].sort((a,c)=>(order.indexOf(a)<0?99:order.indexOf(a))-(order.indexOf(c)<0?99:order.indexOf(c))||a.localeCompare(c)).map(code=>{
      const cells:Record<string,SheetCell>={};const list=byCode.get(code)!;
      const groups=new Map<number,Observation[]>();for(const o of list){const k=start(Date.parse(o.eventTime));(groups.get(k)||groups.set(k,[]).get(k)!).push(o);}
      for(const [k,g] of groups){const last=g.reduce((x,y)=>Date.parse(y.eventTime)>Date.parse(x.eventTime)?y:x);const same=g.filter(o=>o.eventTime===last.eventTime);
        const conflict=new Set(same.map(o=>o.value??o.text)).size>1;const candidate=g.every(o=>o.clinicalUse==='context_only'||o.encounterLinkStatus==='unconfirmed');
        cells[String(k)]={record:last,count:g.length,conflict,candidate,text:String(last.text||(last.value??'—')),level:candidate?'none':level(code,last.value)};}
      return {code,label:label(code),unit:list.find(o=>o.unit)?.unit||'',cells};
    });
    if(this.sheetPage>=this.sheetPageCount)this.sheetPage=0;
  }
  ngOnDestroy(){++this.generation;}
  busy = false;
  error = '';
  text = text;
  riskText = riskText;
  ngOnInit() {
    this.id = this.patientId || this.route.snapshot.paramMap.get('id') || '';
    this.selectedHours=this.initialHours;this.loadedHours=Math.max(24,this.initialHours);
    this.mode=this.panel==='trends'?'chart':'sheet';
    void this.load();
  }
  async load() {
    const generation=++this.generation;
    this.error='';this.recordsError='';this.loadingRecords=true;this.observations=[];this.selectedRecord=null;this.sheetCols=[];this.sheetRows=[];
    const [p,o,a]=await Promise.allSettled([
      this.api.get<Patient>('/patients/'+this.id),
      this.api.get<{items:Observation[];asOf:string}>('/patients/'+this.id+'/clinical-data?hours='+this.loadedHours),
      this.api.get<Page<Assessment>>('/patients/'+this.id+'/risk-assessments')
    ]);
    if(generation!==this.generation)return;
    if(p.status==='fulfilled')this.patient=p.value;else this.error=errorMessage(p.reason);
    if(o.status==='fulfilled'){this.observations=o.value.items;this.asOf=o.value.asOf;}else this.recordsError=errorMessage(o.reason);
    this.rebuildSheet();
    if(a.status==='fulfilled'){this.history=a.value.items;this.assessment=a.value.items[0] || null;}else {this.history=[];this.assessment=null;}
    this.loadingRecords=false;
  }
  async assess() {
    this.busy = true;
    this.error = '';
    try {
      await this.api.post('/patients/' + this.id + '/risk-assessments', {
        asOf: new Date().toISOString(),
      });
      await this.load();
    } catch (e) {
      this.error = errorMessage(e);
    } finally {
      this.busy = false;
    }
  }
}
