/* Local compiled-frontend regression with SYNTHETIC API responses. Never connects to hospital data. */
const fs=require('fs'),path=require('path'),http=require('http'),assert=require('node:assert/strict');
const {chromium}=require('playwright');
const R=path.resolve(__dirname,'..'), out=path.join(R,'docs/screenshots');
const root=path.join(R,'frontend/dist/workbench/browser');
const A='aaaaaaaaaaaaaaaaaaaaaaaa',B='bbbbbbbbbbbbbbbbbbbbbbbb',C='cccccccccccccccccccccccc';
const now=Date.now(), stamp=new Date(now).toISOString();
let origin='',role='ROLE_DOCTOR',mode='normal',icu05Mode='off',aiMode='off',slowId='',slowUsed=false;const clinicalRequests=[],posts=[];
const patients={};for(const [id,name,bed] of [[A,'模拟患者 A','01'],[B,'模拟患者 B','02'],[C,'模拟患者 C','03']])patients[id]={id,label:name,bed,encounterId:'audit-stay-'+bed,department:'DEMO',simulated:true,admittedAt:new Date(now-86400000).toISOString()};
function rawObs(id){if(id===C)return[];let rows=[];if(id===B)[6,6,8].forEach((v,i)=>{const t=new Date(now-120000-(2-i)*6*3600000).toISOString();rows.push({id:'B-SOFA-'+i,patientId:id,parameterCode:'SOFA',value:v,unit:'score',eventTime:t,ingestTime:t,sourceSystem:'SYNTHETIC.fixture',sourceRecordId:'SOFA-'+i,qualityStatus:'valid'});});const specs=[['HR',id===A?80:120,'bpm'],['MAP',id===A?78:58,'mmHg'],['RR',id===A?18:28,'/min'],['SPO2',id===A?98:93,'%'],['TEMP',id===A?36.8:38.6,'°C'],['LACTATE',id===A?1.1:3.5,'mmol/L'],['SBP',id===A?120:85,'mmHg'],['GCS',id===A?15:14,'score']];for(const[c,end,u]of specs){const count=c==='LACTATE'?6:24;for(let i=0;i<count;i++){let value=end;if(!['GCS','SBP'].includes(c)){const drift={HR:18,MAP:-12,RR:5,SPO2:-2,TEMP:0.7,LACTATE:1.0}[c]||0;value=end-drift*(1-i/(count-1));if(c!=='LACTATE')value+=Math.sin(i*0.9)*(c==='HR'?2:c==='TEMP'?.08:.5);value=+value.toFixed(c==='LACTATE'||c==='TEMP'?1:0);}const t=new Date(now-60000-(count-1-i)*12*3600000/(count-1)).toISOString();rows.push({id:id+'-'+c+'-'+i,patientId:id,parameterCode:c,value,unit:u,eventTime:t,ingestTime:t,sourceSystem:'SYNTHETIC.fixture',sourceRecordId:c+'-'+i,qualityStatus:'valid'});}}return rows;}
function obs(id){
 const rows=rawObs(id);if(mode!=='source')return rows;
 return rows.flatMap(o=>{
   const meta={...o,ingestTime:null,ingestTimeBasis:'unknown',clinicalUse:'review',encounterLinkStatus:'source_patient_record',timeBasis:'observed'};
   if(['MAP','SBP'].includes(o.parameterCode))return ['IBP','NIBP'].map(channel=>({...meta,id:o.id+'-'+channel,parameterCode:o.parameterCode+'_'+channel,measurementChannel:channel,sourceParameterCode:'param_'+channel.toLowerCase()+(o.parameterCode==='MAP'?'_m':'_s'),value:o.value+(channel==='NIBP'?18:0)}));
   if(o.parameterCode==='LACTATE')return[{...meta,timeBasis:'reported',reportedAt:o.eventTime,sampledAt:null,clinicalUse:'context_only',qualityStatus:'unverified',encounterLinkStatus:'unconfirmed',qualityNote:'仅 HIS 患者键关联；本次住院归属、采样时间和结果状态未确认。'}];
   return[meta];
 });
}
function sourceAssessment(id){
 const data=assessment(id),rows=obs(id),latest=Object.values(rows.reduce((r,o)=>(r[o.parameterCode]=o,r),{})).map(o=>({...o,code:o.parameterCode}));
 data.qsofa=null;data.missingFeatures=['MAP','SBP','LACTATE'];data.pressureSelectionRequired=true;
 data.pressureEvidence=latest.filter(o=>/^(MAP|SBP)_(IBP|NIBP)$/.test(o.code));
 data.contextEvidence=latest.filter(o=>o.clinicalUse==='context_only');
 data.findings=latest.filter(o=>['MAP_IBP','SBP_IBP','RR','GCS'].includes(o.code)).map(o=>({...o,label:{MAP_IBP:'平均动脉压（有创）',SBP_IBP:'收缩压（有创）',RR:'呼吸频率',GCS:'意识评分'}[o.code],reason:'合成测试记录，需核对来源与临床上下文'}));
 data.sourceWarnings=['有创与无创血压分通道展示，尚未确认院内择优规则；qSOFA 血压项待核对。','乳酸候选报告的本次住院关联、采样时间和结果状态未确认，不纳入当前判断。'];
 data.checklist.forEach(it=>{it.status='unknown';it.eventTime=null;if(it.code==='LACTATE')it.note='未取得新鲜有效结果；候选报告不纳入当前判断';});return data;
}
function icu05(id){
 const base={schemaVersion:1,patientId:id,encounterId:patients[id].encounterId,simulated:true,asOf:stamp,ruleVersion:'ICU-05 v3',ruleSource:'科室主任 ICU-05 感染性休克 Bundle 判定规则（需求文档 v3）'};
 if(icu05Mode==='off')return{...base,available:false,message:'当前数据源未接入',status:'unavailable',statusLabel:'未接入',windows:[]};
 const T=now-4*3600000,at=m=>new Date(T+m*60000).toISOString(),it=(code,label,value,m,detail='',reason=null)=>({code,label,value,time:m===null?null:at(m),detail,reason});
 const shock=id===B;
 const gate={organ:[it('S1','氧合指数 <300',null,null,'','DATA_MISSING'),it('S2','GCS <13',false,20,'最低 14 分'),it('S3','MAP <65',shock,10,shock?'最低 58 mmHg（有创）':'最低 76 mmHg（无创）'),it('S4','血管活性药',shock,35,shock?'去甲肾上腺素注射液':'')],
  infection:[it('I1','诊断含感染',true,null,'肺炎'),it('I2','抗菌药执行',true,40,'注射用美罗培南'),it('I3','病原学送检',true,50,'血培养')],
  shock:[it('K1','首次乳酸',shock,20,shock?'4.6 mmol/L':'1.4 mmol/L'),it('K2','需要升压药',shock,35,shock?'去甲肾上腺素注射液':'')],
  hasOrganDysfunction:shock?true:null,hasInfection:true,isSepticShock:shock,mapMin:shock?58:76,rule:'休克 = 需要升压药，或 乳酸≥2 且 MAP<65',
  status:shock?'septic_shock':'not_met',statusLabel:shock?'符合感染性休克判定':'未达休克判定',reason:shock?null:'NOT_SEPTIC_SHOCK'};
 const w=(h,state,items,reasons)=>({key:h+'h',hours:h,deadline:new Date(T+h*3600000).toISOString(),closed:state!=='in_progress',finish:state==='met'?true:state==='not_met'?false:null,state,finishPath:'triggered',reasons,items});
 const common=[it('A1','乳酸测定',true,20,'4.6 mmol/L'),it('B2','血培养',true,50,'血培养(需氧+厌氧)'),it('C1','MAP <65（触发）',true,10,'最低 58（有创）'),it('C2','乳酸 ≥4（触发）',true,null,'最高 4.6')];
 const windows=[w(1,'not_met',[common[0],it('B1','抗菌药物执行',true,40,'注射用美罗培南'),common[1],it('B3','先培养后抗菌',false,null,'抗菌药早于或同时于血培养','BC_AFTER_AB'),common[2],common[3],it('C3','1h 内开始补液',true,30,'2 条晶体/胶体执行')],['BC_AFTER_AB','MAP_NOT_MET']),
  w(3,'not_met',[common[0],it('B1','抗菌药物执行',true,40,'注射用美罗培南'),common[1],it('B3','先培养后抗菌',false,null,'首剂抗菌药早于或同时于首次血培养','BC_AFTER_AB'),common[2],common[3],it('C3','补液 ≥1500 ml',true,30,'累计 1600 ml')],['BC_AFTER_AB','MAP_NOT_MET']),
  w(6,'in_progress',[common[0],it('A2','复测乳酸（1h 后）',null,null,'','LACTATE_RECHECK_MISSING'),it('B1','抗菌药物执行',true,40,'注射用美罗培南'),common[1],it('B3','先培养后抗菌',false,null,'首剂抗菌药早于或同时于首次血培养','BC_AFTER_AB'),common[2],common[3],it('C3','补液 ≥1500 ml',true,30,'累计 1600 ml')],['BC_AFTER_AB','WINDOW_OPEN'])];
 return{...base,available:true,message:'',status:gate.status,statusLabel:gate.statusLabel,applicable:shock,t0:at(0),t0OrderName:'一级护理',t0Source:'入科后首条医嘱 orderTime（DataCenter.VI_ICU_ZYYZ）',
  pool:{eligible:true,icuWithin24h:true,admissionType:'入院',rule:'入院 24h 内进 ICU，且入科类型为 入院 / 手术入院 / 外院'},gate,windows,
  limitations:['乳酸、氧合指数取 SmartCare 血气（bGATemp），按病案号关联，动/静脉标本未区分。','血培养取医嘱审核时间（reviewTime），不等于实际采血时间。']};
}
function aiBrief(id,kind){
 const facts=[{id:'E1',category:'核对',text:'规则核对状态：存在待复核异常（只读证据核对，非诊断）',time:null},{id:'E2',category:'观测',text:'平均动脉压 58 mmHg，记录时间 2 分钟前，来源 SYNTHETIC',time:stamp},{id:'E3',category:'ICU-05 1h',text:'1h B3 先培养后抗菌：否，抗菌药早于或同时于血培养',time:null}];
 const knowledge=[{ref:'K1',id:'ssc26-7',docId:'ssc-2026',title:'SSC 2026 成人脓毒症与感染性休克国际指南',source:'Crit Care Med 2026;54(4):725-812',version:'2026',section:'筛查与早期处理 · 第7条',strength:'强推荐，低质量证据',text:'推荐尽快留取血培养，理想情况下在使用抗菌药物之前完成。'},{ref:'K2',id:'icu05-windows',docId:'icu05-rules',title:'ICU-05 感染性休克 Bundle 判定规则（科室主任 v3）',source:'本科室需求文档 v3',version:'v3',section:'1h/3h/6h 时窗',strength:'本院质控口径',text:'B3 要求抗菌药晚于血培养。'}];
 const ok=aiMode==='ok';
 return{interactionId:'ai-'+id+'-'+kind,patientId:id,encounterId:patients[id].encounterId,kind,status:ok?'ok':aiMode==='unavailable'?'unavailable':'disabled',message:ok?'已过滤 1 条缺少有效依据、数值不符或含具体剂量的内容':aiMode==='unavailable'?'AI 服务暂不可用（timeout），规则判定不受影响。':'AI 辅助未配置，仅列出本次核对依据与相关知识条目。',
  model:ok?'synthetic-fast':null,modelRole:ok?'fast':null,fallbackUsed:false,latencyMs:ok?2300:null,generatedAt:stamp,promptVersion:'ai-brief-v1',
  summary:ok?[{text:'1h 时窗先培养后抗菌未满足：抗菌药早于或同时于血培养',refs:['E3','K2']},{text:'过期引用不应显示',refs:['K9']}]:[],
  gaps:ok?[{text:'血培养时间来自医嘱审核时间，需核对实际采样时间',refs:['E3']}]:[],
  suggestions:ok?[{text:'建议核对血培养实际留取时间与首剂抗菌药执行时间',refs:['K1','E3'],urgency:'high'}]:[],
  uncertainties:ok?[{text:'MAP 通道择优规则未确认',refs:[]}]:[],facts,knowledge,droppedCount:ok?1:0,disclaimer:'AI 生成，仅供核对参考，须由医生结合原始记录判断；不构成诊断或医嘱。',clinicalUseAllowed:false};
}
function assessment(id){const rows=obs(id),missing=id===C?['MAP','SBP','RR','GCS','LACTATE']:[];const latest=Object.values(rows.reduce((r,o)=>(r[o.parameterCode]=o,r),{})).map(o=>({...o,code:o.parameterCode}));const finds=id===B?latest.filter(o=>['MAP','RR','LACTATE','GCS'].includes(o.code)).map(o=>({...o,label:{MAP:'平均动脉压',RR:'呼吸频率',LACTATE:'乳酸',GCS:'意识评分'}[o.code],reason:o.code==='GCS'?'需结合镇静与基础神经状态核对':'达到本次核对规则阈值'})):[];return{schemaVersion:2,patientId:id,encounterId:patients[id].encounterId,asOf:stamp,available:mode!=='unavailable',message:mode==='unavailable'?'临床数据暂不可用，请刷新或联系接口维护人员。':'',simulated:true,screeningStatus:mode==='unavailable'?'unavailable':id===B?'review_required':id===C?'insufficient_data':'not_triggered',screeningLabel:id===B?'存在待复核异常':id===C?'数据不全，不能完整评估':'本次核对未见阈值异常',missingFeatures:missing,findings:finds,latestEvidence:latest,qsofa:id===C||mode==='unavailable'?null:id===B?3:0,vasopressorActive:null,infectionClue:id===C?'未取得可确认的感染证据':'诊断文本含感染线索，需核对时效及临床依据',checklist:[['LACTATE','乳酸结果','已有结果不等于复苏目标达成'],['BLOOD_CULTURE','血培养采样','尚无经过映射核验的实际采样证据；医嘱不代表已采样'],['ANTIBIOTIC','抗菌药实际执行','待接入实际给药事件；医嘱和关键词命中不代表已给药'],['FLUID','液体量与适用性','需体重、实际输注量及个体化适用性，暂不自动判定达标'],['REASSESS','容量 / 灌注再评估','需明确的再评估记录，不自动标为完成']].map(([code,name,note])=>({code,name,note,status:code==='LACTATE'&&id!==C?'recorded':'unknown',eventTime:code==='LACTATE'&&id!==C?stamp:null})),taskNote:'本页为只读证据核对，未建立院内批准的 T0 与治疗时限，不自动生成治疗任务。',model:{configured:false,label:'未配置预测模型',message:'不生成风险分或风险百分比'},ai:{configured:aiMode!=='off',label:aiMode!=='off'?'AI 辅助已配置（按需生成，需医生核对）':'未配置 AI 辅助'}};}
function hostHTML(){return `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>临床布局回归 · 模拟宿主</title><style>*{box-sizing:border-box}body{margin:0;font:14px/1.6 Arial,'Microsoft YaHei',sans-serif;color:#293e50;background:#f3f5f7}header{height:52px;display:flex;align-items:center;justify-content:space-between;padding:0 20px;background:#3567d6;color:white}header strong{font-size:18px}header span{font-size:14px}aside{position:absolute;top:52px;left:0;bottom:0;width:200px;border-right:1px solid #d5dde5;background:#fff;padding:18px 10px}aside h2{font-size:16px;margin:0 8px 4px}aside p{margin:0 8px 18px;color:#5a6a7b}aside button{display:flex;align-items:center;gap:12px;width:100%;min-height:66px;padding:12px;border:0;background:white;text-align:left;border-radius:4px;font:inherit;color:#314e66;margin:6px 0}aside button.active{background:#e9f0f8}aside b{padding:4px 7px;background:#edf3f9;color:#315f86}iframe{position:absolute;top:52px;left:200px;width:calc(100% - 200px);height:calc(100vh - 52px);border:0;background:#f3f5f7}@media(max-width:750px){aside{width:72px;padding:10px 4px}aside h2{font-size:14px;margin:0;text-align:center}aside p,aside span{display:none}aside button{justify-content:center;padding:10px 4px}iframe{left:72px;width:calc(100% - 72px)}header{padding:0 12px}header strong{font-size:16px}header span{font-size:12px}}</style><header><strong>ICU 宿主工作站</strong><span>集成布局验证 · 全部为模拟数据</span></header><aside><h2>宿主患者列表</h2><p>患者仅在宿主切换</p>${Object.values(patients).map(p=>`<button data-id="${p.id}" onclick="choose('${p.id}')"><b>${p.bed}</b><span>${p.label}</span></button>`).join('')}</aside><iframe title="患者临床工作区" src="${origin.replace('127.0.0.1','localhost')}/embed?parentOrigin=${encodeURIComponent(origin)}"></iframe><script>let current='${B}';window.choose=id=>{current=id;document.querySelectorAll('aside button').forEach(b=>b.classList.toggle('active',b.dataset.id===id));document.querySelector('iframe').contentWindow.postMessage({type:'SmartCare',patient:id?{id,mrn:'演示住院记录',gender:'Male'}:null},'${origin.replace('127.0.0.1','localhost')}')};window.statusMsgs=[];addEventListener('message',e=>{if(e.source!==document.querySelector('iframe').contentWindow)return;if(e.data?.type==='SmartCareReady')choose(current);if(e.data?.type==='SmartCareSepsisStatus')statusMsgs.push(e.data)});</script></html>`;}
const server=http.createServer((req,res)=>{if(req.url==='/review-host'){res.setHeader('Content-Type','text/html; charset=utf-8');return res.end(hostHTML());}let p=path.join(root,req.url.split('?')[0]);if(!fs.existsSync(p)||fs.statSync(p).isDirectory())p=path.join(root,'index.html');res.setHeader('Content-Type',p.endsWith('.js')?'text/javascript':p.endsWith('.css')?'text/css':'text/html');res.end(fs.readFileSync(p));});
const tests=[];let browser,page,f;
async function test(name,fn){try{await fn();tests.push({name,passed:true});console.log('PASS '+name)}catch(e){tests.push({name,passed:false,error:e.message});throw e;}}
async function ready(id){await f.locator('.icu-patient-left .name').filter({hasText:patients[id].label}).waitFor();await f.locator('.sx-head:not([data-level=unknown]) h1, .sx-head h1:text("关键数据不全"), .sx-head h1:text("数据暂不可用")').first().waitFor();await page.waitForTimeout(150);}
async function refresh(){await f.getByRole('button',{name:'刷新数据',exact:true}).click();await page.waitForTimeout(100);await f.locator('.sx-head button[aria-label=刷新数据]:not([disabled])').waitFor();await page.waitForTimeout(100);}
(async()=>{fs.mkdirSync(out,{recursive:true});await new Promise(r=>server.listen(0,'0.0.0.0',r));origin='http://127.0.0.1:'+server.address().port;browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_EXECUTABLE||'/usr/local/bin/chromium'});page=await browser.newPage({viewport:{width:1680,height:1180},timezoneId:'Asia/Shanghai'});let errors=[];page.on('pageerror',e=>errors.push(e.message));
await page.route('**/embed-api/**',async r=>{let data={};if(r.request().url().includes('/config'))data={allowedParentOrigins:[origin]};if(r.request().url().includes('host-context')){const id=r.request().postDataJSON().patientId;if(id===slowId&&!slowUsed){slowUsed=true;await new Promise(r=>setTimeout(r,700));}data={...patients[id],accessToken:'fixture-'+id,expiresAt:new Date(now+600000).toISOString(),username:'host',roles:[role]};}await r.fulfill({json:{code:'OK',data}});});
await page.route('**/api/v1/**',async r=>{if(r.request().method()==='POST')posts.push({url:r.request().url(),body:r.request().postDataJSON()});const u=new URL(r.request().url()),token=r.request().headers()['authorization']||'',id=[A,B,C].find(id=>token.includes(id))||B;let data=patients[id];if(u.pathname.endsWith('/sepsis-assessment'))data=mode==='source'?sourceAssessment(id):assessment(id);else if(u.pathname.endsWith('/icu05-bundle')){if(icu05Mode==='error')return r.fulfill({status:503,json:{message:'模拟 ICU-05 读取失败'}});data=icu05(id);}else if(u.pathname.endsWith('/ai/brief')){if(aiMode==='error')return r.fulfill({status:500,json:{message:'模拟 AI 接口异常',code:'INTERNAL'}});data=aiBrief(id,r.request().postDataJSON().kind);}else if(u.pathname.endsWith('/feedback'))data={interactionId:'x',count:1};else if(u.pathname.endsWith('/clinical-data')){clinicalRequests.push({id,hours:Number(u.searchParams.get('hours'))});if(mode==='unavailable')return r.fulfill({status:503,json:{message:'模拟数据源不可用',code:'SOURCE_UNAVAILABLE'}});data={items:obs(id),asOf:stamp};}else if(u.pathname.endsWith('/risk-assessments'))data={items:[],total:0,page:0,size:20};else if(u.pathname.endsWith('/alerts')){if(mode==='partial')return r.fulfill({status:503,json:{message:'模拟预警服务读取失败'}});data={items:id===B?[{id:'alert-B',patientId:id,encounterId:patients[id].encounterId,simulated:true,version:0,status:'pending',severity:'high',kind:'infection_abnormal',source:'bedside',trigger:'诊断文本含感染线索，且存在异常：呼吸频率 26',reason:'诊断文本含感染线索，且存在异常：呼吸频率 26',patientLabel:'模拟患者B',bed:'02',lastSeenAt:stamp,ruleVersion:'screen-alert-v1',createdAt:stamp}]:[],total:id===B?1:0,page:0,size:20};}else if(u.pathname.endsWith('/bundles/tasks'))data={items:[],total:0,page:0,size:20};await r.fulfill({json:{code:'OK',data}});});
await page.goto(origin+'/review-host');f=page.frameLocator('iframe');
const tab=name=>f.locator('nav.icu-tabs').getByRole('button',{name,exact:false});
await test('overview puts the most urgent values first and keeps curves folded',async()=>{await ready(B);
 assert.equal(await f.locator('nav.icu-tabs button').count(),3);
 assert.equal(await f.locator('.sx-head').getAttribute('data-level'),'critical');
 assert.match(await f.locator('.sx-head h1').innerText(),/^MAP 58 偏低/);
 assert.match(await f.locator('.sx-head h1').innerText(),/乳酸 3.5 偏高/);
 assert.equal(await f.locator('.sx-tile[data-level=critical]').filter({hasText:'平均动脉压'}).count(),1);
 assert.match(await f.locator('.sx-tile').filter({hasText:'平均动脉压'}).innerText(),/升压药无记录/);
 assert.ok(await f.locator('.sx-tile').count()>=7);
 assert.equal(await f.locator('svg[role=img]').count(),0);
 assert.equal(await page.locator('aside').count(),1);assert.equal(await f.locator('.risk-score').count(),0);
 assert.match(await f.locator('.sx-pending.alert').innerText(),/1 条预警待处理/);
 assert.equal(await f.locator('.sx-pending').count(),1,'zero task count is hidden');
});
await page.screenshot({path:path.join(out,'60-v5-overview.png')});
await test('bundle and organ panels never invent completion',async()=>{
 assert.equal(await f.locator('.sx-bundle tbody tr').count(),6);
 assert.match(await f.locator('.sx-bundle tr').filter({hasText:'乳酸测定'}).innerText(),/已有结果 3.5/);
 assert.match(await f.locator('.sx-bundle tr').filter({hasText:'血培养'}).innerText(),/采样记录未接入/);
 assert.equal(await f.locator('.sx-bundle tr[data-state=warn]').filter({hasText:'血管活性药'}).count(),1);
 assert.match(await f.locator('.sx-bundle').innerText(),/时间零点\s*未确认/);
 assert.equal(await f.locator('.sx-bundle').getByText('已完成').count(),0);
 assert.match(await f.locator('.sx-organ').innerText(),/SOFA\s*8\s*24h \+2/);
 assert.match(await f.locator('.sx-organ tr').filter({hasText:'凝血'}).innerText(),/凝血\s*血小板\s*—/);
 assert.equal(await f.locator('.sx-organ tr[data-level=critical]').filter({hasText:'循环'}).count(),1);
});
await test('host receives a per-patient status badge message',async()=>{
 await page.waitForFunction(()=>statusMsgs.some(m=>m.patientId==='bbbbbbbbbbbbbbbbbbbbbbbb'));
 const m=(await page.evaluate(()=>statusMsgs)).filter(m=>m.patientId===B).at(-1);
 assert.equal(m.level,'critical');assert.equal(m.pendingAlerts,1);assert.equal(m.pendingTasks,0);assert.equal(m.version,1);
 assert.match(await tab('预警处理').innerText(),/1/);
});
await test('tile opens source evidence in place',async()=>{await f.locator('.sx-tile').filter({hasText:'乳酸'}).click();await f.locator('.review-evidence-detail').waitFor();assert.match(await f.locator('.review-evidence-detail').innerText(),/SYNTHETIC.fixture/);await f.locator('.review-evidence-detail').getByRole('button',{name:'关闭'}).click();});
await test('vital trends are available on demand',async()=>{await f.locator('.sx-fold > summary').click();await f.locator('.sx-fold svg[role=img]').first().waitFor();assert.equal(await f.locator('.sx-fold svg[role=img]').count(),6);await page.screenshot({path:path.join(out,'61-v5-overview-trends.png')});await f.locator('.sx-fold > summary').click();});
await test('pending alert button opens quick-reason review',async()=>{await f.locator('.sx-pending.alert').click();await f.getByRole('heading',{name:'预警处理',exact:true}).waitFor();
 await f.getByRole('heading',{name:'执行任务',exact:true}).waitFor();await f.getByText('暂无待处理任务',{exact:true}).waitFor();await page.screenshot({path:path.join(out,'67-v5-alerts-tasks.png')});
 await f.getByRole('button',{name:'处置',exact:true}).click();await f.getByRole('dialog').waitFor();
 const submit=f.getByRole('button',{name:'确认提交',exact:true});assert.equal(await submit.isDisabled(),true);
 await f.getByRole('button',{name:'其他',exact:true}).click();assert.equal(await submit.isDisabled(),true,'其他 requires a note');
 await f.getByRole('button',{name:'误报：监测干扰',exact:true}).click();assert.equal(await submit.isDisabled(),false);
 await page.screenshot({path:path.join(out,'62-v5-review-dialog.png')});
 assert.match(await submit.evaluate(e=>getComputedStyle(e).backgroundColor),/56, 105, 232/);
});
await test('unsaved review rejects patient switching',async()=>{await page.evaluate(id=>choose(id),C);await page.waitForTimeout(100);assert.match(await f.locator('.icu-patient-left .name').innerText(),/模拟患者 B/);assert.match(await f.locator('.icu-error').innerText(),/完成或取消/);});
await test('quick reason is saved as the review reason',async()=>{await f.locator('.dialog textarea').fill('导联脱落');await f.getByRole('button',{name:'确认提交',exact:true}).click();await f.getByRole('dialog').waitFor({state:'detached'});
 const p=posts.find(p=>p.url.includes('/alerts/alert-B/review'));assert.equal(p.body.reason,'误报：监测干扰；导联脱落');await tab('概览').click();await ready(B);});
await test('rapid patient selections keep the newest result',async()=>{slowId=A;slowUsed=false;await page.evaluate(id=>choose(id),A);await page.waitForTimeout(40);await page.evaluate(id=>choose(id),C);await ready(C);await page.waitForTimeout(800);assert.match(await f.locator('.icu-patient-left .name').innerText(),/模拟患者 C/);});
await test('missing data stays unknown and is not shown as normal',async()=>{await f.locator('.sx-head h1').filter({hasText:'关键数据不全'}).waitFor();
 assert.equal(await f.locator('.sx-head').getAttribute('data-level'),'unknown');assert.match(await f.locator('.sx-head p').innerText(),/乳酸/);
 assert.match(await f.locator('.sx-chip').first().innerText(),/无法计算/);assert.equal(await f.locator('svg[role=img]').count(),0);
 assert.equal(await f.locator('.sx-tile[data-level=normal]').count(),0);assert.equal(await f.getByText('正常',{exact:true}).count(),0);});
await page.screenshot({path:path.join(out,'63-v5-no-data.png')});
await test('empty overview offers working history lookup',async()=>{
 await f.getByRole('button',{name:'查看历史记录',exact:true}).click();
 await f.getByRole('button',{name:'7 天',exact:true}).waitFor();await page.waitForTimeout(150);assert.ok(clinicalRequests.some(q=>q.id===C && q.hours===168));
 await f.getByRole('button',{name:'30 天',exact:true}).click();await page.waitForTimeout(200);assert.ok(clinicalRequests.some(q=>q.id===C && q.hours===720));
 assert.equal(await f.locator('svg[role=img]').count(),0);
 await f.getByRole('button',{name:'查看记录单',exact:true}).click();
 assert.match(await f.locator('.work-empty').innerText(),/所选范围暂无记录/);
 await tab('概览').click();await ready(C);
});
await test('non-parent message cannot select a patient',async()=>{await f.locator('body').evaluate((_,id)=>window.postMessage({type:'SmartCare',patient:{id}},location.origin),A);await page.waitForTimeout(80);assert.match(await f.locator('.icu-patient-left .name').innerText(),/模拟患者 C/);});
await test('parent clear removes all previous patient content',async()=>{await page.evaluate(()=>choose(''));await f.getByText('等待患者',{exact:true}).waitFor();assert.equal(await f.locator('.icu-patient-bar').count(),0);});
await page.evaluate(id=>choose(id),B);await ready(B);
await test('partial work-queue failure is not shown as zero',async()=>{mode='partial';await refresh();await f.getByText('预警读取失败',{exact:true}).waitFor();assert.equal(await f.locator('.sx-pending.alert').count(),0);assert.equal(await f.getByText('0 条预警待处理').count(),0);
 const m=(await page.evaluate(()=>statusMsgs)).filter(m=>m.patientId===B).at(-1);assert.equal(m.pendingAlerts,null);});
await test('unavailable source is explicitly reported without low risk',async()=>{mode='unavailable';await refresh();assert.match(await f.locator('.review-state').innerText(),/临床数据暂不可用/);assert.equal(await f.locator('.sx-tile').count(),0);assert.equal(await f.locator('.sx-bundle').count(),0);assert.equal(await f.getByText('极低',{exact:true}).count(),0);assert.match(await f.locator('.sx-head h1').innerText(),/数据暂不可用/);});
mode='normal';await refresh();
await test('narrow iframe keeps readable content without body overflow',async()=>{await page.setViewportSize({width:462,height:1000});await page.waitForTimeout(400);const d=await f.locator('body').evaluate(e=>({w:e.clientWidth,s:e.scrollWidth}));assert.ok(d.s<=d.w+1,JSON.stringify(d));assert.ok(await f.locator('.sx-bundle').isVisible());});
await page.screenshot({path:path.join(out,'64-v5-mobile.png')});
await page.setViewportSize({width:1680,height:1180});mode='source';await refresh();
await test('separate pressure channels both shown, unconfirmed lactate stays out of prompts',async()=>{
 assert.equal(await f.locator('.sx-tile').filter({hasText:'平均动脉压 · 有创'}).count(),1);
 const nibp=f.locator('.sx-tile').filter({hasText:'平均动脉压 · 无创'});assert.equal(await nibp.count(),1);assert.equal(await nibp.locator('em').count(),0,'normal NIBP has no direction word');
 const lac=f.locator('.sx-tile').filter({hasText:'乳酸'});assert.equal(await lac.getAttribute('data-level'),'none');assert.match(await lac.innerText(),/归属未确认/);
 assert.doesNotMatch(await f.locator('.sx-head h1').innerText(),/乳酸/);
 assert.match(await f.locator('.sx-bundle tr').filter({hasText:'乳酸测定'}).innerText(),/归属未确认/);
 assert.match(await f.locator('.sx-chip').first().innerText(),/无法计算/);
 await f.locator('.sx-limits > summary').click();assert.match(await f.locator('.sx-limits').innerText(),/择优规则/);assert.match(await f.locator('.sx-limits').innerText(),/未确认归属的报告/);
});
await page.screenshot({path:path.join(out,'65-v5-pressure-source.png')});
await test('records open as a time-column flowsheet with flagged cells',async()=>{
 await tab('记录与依据').click();await f.locator('.sx-sheet').waitFor();
 const cols=await f.locator('.sx-sheet thead th').count();assert.ok(cols>=2&&cols<=13,String(cols));
 assert.ok(await f.locator('.sx-sheet td[data-level=critical]').count()>0);
 const lacRow=f.locator('.sx-sheet tbody tr').filter({hasText:'乳酸'});assert.ok(await lacRow.locator('td.cand').count()>0);
 await page.screenshot({path:path.join(out,'66-v5-flowsheet.png')});
 await lacRow.locator('td.cand button').first().click();assert.match(await f.locator('.record-source-drawer').innerText(),/本次住院关联未确认/);
 await f.locator('.record-source-drawer').getByRole('button',{name:'关闭'}).click();
});
await test('flowsheet range changes bucket size and paging',async()=>{
 await f.getByRole('combobox',{name:'记录时间范围'}).selectOption({label:'近7天'});await f.locator('.sx-sheet').waitFor();
 assert.match(await f.locator('.record-meta').first().innerText(),/每 4 小时/);
 await f.getByRole('combobox',{name:'记录时间范围'}).selectOption({label:'近12小时'});await f.locator('.sx-sheet').waitFor();
});
await test('detail list filter and range apply to rows',async()=>{
 await f.getByRole('button',{name:'明细',exact:true}).click();
 await f.getByRole('combobox',{name:'筛选指标'}).selectOption('HR');
 assert.equal(await f.locator('tbody tr').count(),23);
 await f.getByRole('combobox',{name:'记录时间范围'}).selectOption({label:'近24小时'});
 await f.locator('tbody tr').nth(23).waitFor();assert.equal(await f.locator('tbody tr').count(),24);
 await f.getByRole('combobox',{name:'筛选指标'}).selectOption('LACTATE');assert.match(await f.locator('tbody').innerText(),/归属未确认/);
});
await test('trend chart mode is inside records tab',async()=>{await f.getByRole('button',{name:'趋势图',exact:true}).click();await f.locator('svg[role=img]').first().waitFor();assert.equal(await f.locator('.trend-title').filter({hasText:'平均动脉压（有创）'}).count(),1);for(const text of await f.locator('.trend-title').allTextContents())assert.ok(!text.includes('>'));});
await test('nurse role sees bundle across the full width',async()=>{mode='normal';role='ROLE_NURSE';await page.evaluate(id=>choose(id),A);await ready(A);await f.locator('.sx-overview.nurse-first').waitFor();
 const b=await f.locator('.sx-bundle').boundingBox(),o=await f.locator('.sx-organ').boundingBox();assert.ok(b.width>o.width*1.5&&b.y<o.y,JSON.stringify({b,o}));
 assert.equal(await f.locator('.sx-head').getAttribute('data-level'),'normal');assert.match(await f.locator('.sx-head h1').innerText(),/主要指标未超出提示阈值/);role='ROLE_DOCTOR';});
await test('ICU-05 card shows director rule windows with tri-state cells',async()=>{icu05Mode='shock';await page.evaluate(id=>choose(id),B);await ready(B);await refresh();
 const card=f.locator('.i5');await card.waitFor();
 assert.match(await card.locator('.i5-status').innerText(),/符合感染性休克判定/);
 assert.match(await card.innerText(),/T0\s*\d\d-\d\d \d\d:\d\d/);
 const heads=card.locator('.i5-table thead th');assert.equal(await heads.count(),4);
 assert.match(await heads.nth(1).innerText(),/1h\s*未达标/);assert.match(await heads.nth(2).innerText(),/3h\s*未达标/);assert.match(await heads.nth(2).innerText(),/抗菌药早于血培养/);assert.match(await heads.nth(3).innerText(),/6h\s*剩/);
 const b3=card.locator('tbody tr').filter({hasText:'先培养后抗菌'});assert.equal(await b3.locator('td[data-tone=fail]').count(),3,'a later repeat dose cannot turn antibiotic-before-culture into met');assert.match(await b3.locator('td').first().getAttribute('title'),/抗菌药早于血培养/);
 const recheck=card.locator('tbody tr').filter({hasText:'复测乳酸'});assert.equal(await recheck.locator('td[data-tone=wait]').count(),1,'open window shows 待, not ✗');
 assert.equal(await recheck.locator('td[data-tone=none]').count(),2,'1h/3h have no recheck item');
 assert.equal(await card.locator('tbody tr').filter({hasText:'MAP<65 触发'}).locator('td[data-tone=flag]').count(),3);
 assert.equal(await f.locator('.sx-bundle[aria-label="1小时集束化"]').count(),0,'legacy checklist hidden when ICU-05 available');
 assert.match(await f.locator('.sx-head .i5-head').innerText(),/ICU-05 符合感染性休克判定/);
 await page.waitForFunction(()=>statusMsgs.some(m=>/ICU-05/.test(m.summary||'')));
 await page.screenshot({path:path.join(out,'68-v6-icu05-bundle.png')});
 await card.locator('.i5-limits > summary').click();assert.match(await card.locator('.i5-limits').innerText(),/reviewTime/);
 await card.screenshot({path:path.join(out,'69-v6-icu05-card.png')});
});
await test('AI assist is on-demand and every shown statement links to evidence',async()=>{aiMode='ok';await refresh();const card=f.locator('.sx-ai');await card.waitFor();
 assert.equal(posts.filter(p=>p.url.includes('/ai/brief')).length,0,'never generated automatically');
 assert.match(await card.innerText(),/生成内容需医生核对/);
 await card.getByRole('button',{name:'ICU-05 缺项分析'}).click();await card.locator('.ai-body').waitFor();
 assert.deepEqual(posts.filter(p=>p.url.includes('/ai/brief')).map(p=>p.body.kind),['icu05']);
 const text=await card.innerText();assert.match(text,/先培养后抗菌未满足/);assert.ok(!text.includes('过期引用不应显示'),'unresolved reference hidden');
 assert.match(text,/AI 生成，仅供核对参考/);assert.match(text,/已过滤 1 条/);
 assert.equal(await card.locator('.ai-urgency').filter({hasText:'优先'}).count(),1);
 await card.locator('.ai-ref',{hasText:'K1'}).first().click();assert.match(await card.locator('.ai-ref-detail').innerText(),/强推荐，低质量证据/);
 await card.locator('.ai-ref',{hasText:'E3'}).first().click();assert.match(await card.locator('.ai-ref-detail').innerText(),/抗菌药早于或同时于血培养/);
 for(const c of await card.evaluate(e=>[...e.querySelectorAll('*')].map(n=>getComputedStyle(n).backgroundImage).filter(b=>b&&b!=='none')))assert.fail('decorative gradient/background image in AI card: '+c);
 await card.screenshot({path:path.join(out,'70-v8-ai-assist-card.png')});
 await card.getByRole('button',{name:'不采纳'}).click();const submit=card.getByRole('button',{name:'提交'});assert.equal(await submit.isDisabled(),true,'reason required');
 await card.getByRole('button',{name:'引用依据不对应'}).click();await submit.click();await card.locator('.ai-sent').waitFor();
 const fb=posts.find(p=>p.url.includes('/feedback'));assert.equal(fb.body.verdict,'reject');assert.equal(fb.body.reason,'引用依据不对应');
 assert.equal(await f.locator('.i5').count(),1,'rule card unaffected');
});
await test('AI unavailable keeps rule cards and shows sources only',async()=>{aiMode='unavailable';await refresh();const card=f.locator('.sx-ai');await card.waitFor();
 await card.getByRole('button',{name:'病情摘要'}).click();await card.locator('.ai-status').waitFor();
 assert.match(await card.locator('.ai-status').innerText(),/暂不可用/);assert.equal(await card.locator('.ai-body').count(),0);
 assert.equal(await card.locator('.ai-sources').getAttribute('open'),'');assert.match(await card.locator('.ai-kb').innerText(),/第7条/);
 aiMode='error';await card.getByRole('button',{name:'病情摘要'}).click();await card.locator('.review-inline-error').waitFor();assert.match(await card.innerText(),/AI 辅助请求失败/);
 assert.equal(await f.locator('.i5').count(),1);aiMode='off';
});
await test('ICU-05 not-met gate folds windows as reference only',async()=>{await page.evaluate(id=>choose(id),A);await ready(A);await f.locator('.i5').waitFor();
 assert.match(await f.locator('.i5-status').innerText(),/未达休克判定/);assert.equal(await f.locator('.i5-na').count(),1);assert.equal(await f.locator('.i5-na').getAttribute('open'),null);});
await test('ICU-05 read failure falls back to checklist, never to not-met',async()=>{icu05Mode='error';await refresh();await f.locator('.sx-bundle[aria-label="1小时集束化"]').waitFor();assert.equal(await f.locator('.i5').count(),0);assert.equal(await f.getByText('未达标').count(),0);icu05Mode='off';});
await test('no browser runtime errors',async()=>assert.deepEqual(errors,[]));
fs.writeFileSync(path.join(R,'docs/host-ui-v6-browser-results.json'),JSON.stringify({scope:'compiled frontend with synthetic API responses; not hospital integration',tests,errors},null,2));
await browser.close();server.close();process.exit(0);
})().catch(async e=>{console.error(e.stack);fs.writeFileSync(path.join(R,'docs/host-ui-v6-browser-results.json'),JSON.stringify({scope:'compiled frontend with synthetic API responses',tests},null,2));if(browser)await browser.close();server.close();process.exit(1)});
