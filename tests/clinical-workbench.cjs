/* Local compiled-frontend regression with SYNTHETIC API responses. Never connects to hospital data. */
const fs=require('fs'),path=require('path'),http=require('http'),assert=require('node:assert/strict');
const {chromium}=require('playwright');
const R=path.resolve(__dirname,'..'), out=path.join(R,'docs/screenshots');
const root=path.join(R,'frontend/dist/workbench/browser');
const A='aaaaaaaaaaaaaaaaaaaaaaaa',B='bbbbbbbbbbbbbbbbbbbbbbbb',C='cccccccccccccccccccccccc';
const now=Date.now(), stamp=new Date(now).toISOString();
let origin='',role='ROLE_DOCTOR',mode='normal',slowId='',slowUsed=false;const clinicalRequests=[],posts=[];
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
function assessment(id){const rows=obs(id),missing=id===C?['MAP','SBP','RR','GCS','LACTATE']:[];const latest=Object.values(rows.reduce((r,o)=>(r[o.parameterCode]=o,r),{})).map(o=>({...o,code:o.parameterCode}));const finds=id===B?latest.filter(o=>['MAP','RR','LACTATE','GCS'].includes(o.code)).map(o=>({...o,label:{MAP:'平均动脉压',RR:'呼吸频率',LACTATE:'乳酸',GCS:'意识评分'}[o.code],reason:o.code==='GCS'?'需结合镇静与基础神经状态核对':'达到本次核对规则阈值'})):[];return{schemaVersion:2,patientId:id,encounterId:patients[id].encounterId,asOf:stamp,available:mode!=='unavailable',message:mode==='unavailable'?'临床数据暂不可用，请刷新或联系接口维护人员。':'',simulated:true,screeningStatus:mode==='unavailable'?'unavailable':id===B?'review_required':id===C?'insufficient_data':'not_triggered',screeningLabel:id===B?'存在待复核异常':id===C?'数据不全，不能完整评估':'本次核对未见阈值异常',missingFeatures:missing,findings:finds,latestEvidence:latest,qsofa:id===C||mode==='unavailable'?null:id===B?3:0,vasopressorActive:null,infectionClue:id===C?'未取得可确认的感染证据':'诊断文本含感染线索，需核对时效及临床依据',checklist:[['LACTATE','乳酸结果','已有结果不等于复苏目标达成'],['BLOOD_CULTURE','血培养采样','尚无经过映射核验的实际采样证据；医嘱不代表已采样'],['ANTIBIOTIC','抗菌药实际执行','待接入实际给药事件；医嘱和关键词命中不代表已给药'],['FLUID','液体量与适用性','需体重、实际输注量及个体化适用性，暂不自动判定达标'],['REASSESS','容量 / 灌注再评估','需明确的再评估记录，不自动标为完成']].map(([code,name,note])=>({code,name,note,status:code==='LACTATE'&&id!==C?'recorded':'unknown',eventTime:code==='LACTATE'&&id!==C?stamp:null})),taskNote:'本页为只读证据核对，未建立院内批准的 T0 与治疗时限，不自动生成治疗任务。',model:{configured:false,label:'未配置预测模型',message:'不生成风险分或风险百分比'},ai:{configured:false,label:'未配置摘要服务'}};}
function hostHTML(){return `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>临床布局回归 · 模拟宿主</title><style>*{box-sizing:border-box}body{margin:0;font:14px/1.6 Arial,'Microsoft YaHei',sans-serif;color:#293e50;background:#f3f5f7}header{height:52px;display:flex;align-items:center;justify-content:space-between;padding:0 20px;background:#3567d6;color:white}header strong{font-size:18px}header span{font-size:14px}aside{position:absolute;top:52px;left:0;bottom:0;width:200px;border-right:1px solid #d5dde5;background:#fff;padding:18px 10px}aside h2{font-size:16px;margin:0 8px 4px}aside p{margin:0 8px 18px;color:#5a6a7b}aside button{display:flex;align-items:center;gap:12px;width:100%;min-height:66px;padding:12px;border:0;background:white;text-align:left;border-radius:4px;font:inherit;color:#314e66;margin:6px 0}aside button.active{background:#e9f0f8}aside b{padding:4px 7px;background:#edf3f9;color:#315f86}iframe{position:absolute;top:52px;left:200px;width:calc(100% - 200px);height:calc(100vh - 52px);border:0;background:#f3f5f7}@media(max-width:750px){aside{width:72px;padding:10px 4px}aside h2{font-size:14px;margin:0;text-align:center}aside p,aside span{display:none}aside button{justify-content:center;padding:10px 4px}iframe{left:72px;width:calc(100% - 72px)}header{padding:0 12px}header strong{font-size:16px}header span{font-size:12px}}</style><header><strong>ICU 宿主工作站</strong><span>集成布局验证 · 全部为模拟数据</span></header><aside><h2>宿主患者列表</h2><p>患者仅在宿主切换</p>${Object.values(patients).map(p=>`<button data-id="${p.id}" onclick="choose('${p.id}')"><b>${p.bed}</b><span>${p.label}</span></button>`).join('')}</aside><iframe title="患者临床工作区" src="${origin.replace('127.0.0.1','localhost')}/embed?parentOrigin=${encodeURIComponent(origin)}"></iframe><script>let current='${B}';window.choose=id=>{current=id;document.querySelectorAll('aside button').forEach(b=>b.classList.toggle('active',b.dataset.id===id));document.querySelector('iframe').contentWindow.postMessage({type:'SmartCare',patient:id?{id,mrn:'演示住院记录',gender:'Male'}:null},'${origin.replace('127.0.0.1','localhost')}')};window.statusMsgs=[];addEventListener('message',e=>{if(e.source!==document.querySelector('iframe').contentWindow)return;if(e.data?.type==='SmartCareReady')choose(current);if(e.data?.type==='SmartCareSepsisStatus')statusMsgs.push(e.data)});</script></html>`;}
const server=http.createServer((req,res)=>{if(req.url==='/review-host'){res.setHeader('Content-Type','text/html; charset=utf-8');return res.end(hostHTML());}let p=path.join(root,req.url.split('?')[0]);if(!fs.existsSync(p)||fs.statSync(p).isDirectory())p=path.join(root,'index.html');res.setHeader('Content-Type',p.endsWith('.js')?'text/javascript':p.endsWith('.css')?'text/css':'text/html');res.end(fs.readFileSync(p));});
const tests=[];let browser,page,f;
async function test(name,fn){try{await fn();tests.push({name,passed:true});console.log('PASS '+name)}catch(e){tests.push({name,passed:false,error:e.message});throw e;}}
async function ready(id){await f.locator('.icu-patient-left .name').filter({hasText:patients[id].label}).waitFor();await f.locator('.sx-head:not([data-level=unknown]) h1, .sx-head h1:text("关键数据不全"), .sx-head h1:text("数据暂不可用")').first().waitFor();await page.waitForTimeout(150);}
async function refresh(){await f.getByRole('button',{name:'刷新数据',exact:true}).click();await page.waitForTimeout(100);await f.locator('.sx-head button[aria-label=刷新数据]:not([disabled])').waitFor();await page.waitForTimeout(100);}
(async()=>{fs.mkdirSync(out,{recursive:true});await new Promise(r=>server.listen(0,'0.0.0.0',r));origin='http://127.0.0.1:'+server.address().port;browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_EXECUTABLE||'/usr/local/bin/chromium'});page=await browser.newPage({viewport:{width:1680,height:1180},timezoneId:'Asia/Shanghai'});let errors=[];page.on('pageerror',e=>errors.push(e.message));
await page.route('**/embed-api/**',async r=>{let data={};if(r.request().url().includes('/config'))data={allowedParentOrigins:[origin]};if(r.request().url().includes('host-context')){const id=r.request().postDataJSON().patientId;if(id===slowId&&!slowUsed){slowUsed=true;await new Promise(r=>setTimeout(r,700));}data={...patients[id],accessToken:'fixture-'+id,expiresAt:new Date(now+600000).toISOString(),username:'host',roles:[role]};}await r.fulfill({json:{code:'OK',data}});});
await page.route('**/api/v1/**',async r=>{if(r.request().method()==='POST')posts.push({url:r.request().url(),body:r.request().postDataJSON()});const u=new URL(r.request().url()),token=r.request().headers()['authorization']||'',id=[A,B,C].find(id=>token.includes(id))||B;let data=patients[id];if(u.pathname.endsWith('/sepsis-assessment'))data=mode==='source'?sourceAssessment(id):assessment(id);else if(u.pathname.endsWith('/clinical-data')){clinicalRequests.push({id,hours:Number(u.searchParams.get('hours'))});if(mode==='unavailable')return r.fulfill({status:503,json:{message:'模拟数据源不可用',code:'SOURCE_UNAVAILABLE'}});data={items:obs(id),asOf:stamp};}else if(u.pathname.endsWith('/risk-assessments'))data={items:[],total:0,page:0,size:20};else if(u.pathname.endsWith('/alerts')){if(mode==='partial')return r.fulfill({status:503,json:{message:'模拟预警服务读取失败'}});data={items:id===B?[{id:'alert-B',patientId:id,encounterId:patients[id].encounterId,simulated:true,version:0,status:'pending',severity:'high',reason:'模拟记录：待复核当前观测',ruleVersion:'fixture',createdAt:stamp}]:[],total:id===B?1:0,page:0,size:20};}else if(u.pathname.endsWith('/bundles/tasks'))data={items:[],total:0,page:0,size:20};await r.fulfill({json:{code:'OK',data}});});
await page.goto(origin+'/review-host');f=page.frameLocator('iframe');
const tab=name=>f.locator('nav.icu-tabs').getByRole('button',{name,exact:false});
await test('overview puts the most urgent values first and keeps curves folded',async()=>{await ready(B);
 assert.equal(await f.locator('nav.icu-tabs button').count(),3);
 assert.equal(await f.locator('.sx-head').getAttribute('data-level'),'critical');
 assert.match(await f.locator('.sx-head h1').innerText(),/^MAP 58 偏低/);
 assert.match(await f.locator('.sx-head h1').innerText(),/乳酸 3.5 偏高/);
 assert.equal(await f.locator('.sx-tile[data-level=critical]').filter({hasText:'平均动脉压'}).count(),1);
 assert.match(await f.locator('.sx-tile').filter({hasText:'平均动脉压'}).innerText(),/升压药 无记录/);
 assert.ok(await f.locator('.sx-tile').count()>=7);
 assert.equal(await f.locator('svg[role=img]').count(),0);
 assert.equal(await page.locator('aside').count(),1);assert.equal(await f.locator('.risk-score').count(),0);
 assert.match(await f.locator('.sx-pending.danger').innerText(),/1 条预警待处理/);
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
 assert.match(await f.locator('.sx-organ tr').filter({hasText:'凝血'}).innerText(),/未接入/);
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
await test('pending alert button opens quick-reason review',async()=>{await f.locator('.sx-pending.danger').click();await f.getByRole('heading',{name:'预警处理',exact:true}).waitFor();
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
await test('partial work-queue failure is not shown as zero',async()=>{mode='partial';await refresh();await f.getByText('预警读取失败',{exact:true}).waitFor();assert.equal(await f.locator('.sx-pending.danger').count(),0);assert.equal(await f.getByText('0 条预警待处理').count(),0);
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
await test('no browser runtime errors',async()=>assert.deepEqual(errors,[]));
fs.writeFileSync(path.join(R,'docs/host-ui-v5-browser-results.json'),JSON.stringify({scope:'compiled frontend with synthetic API responses; not hospital integration',tests,errors},null,2));
await browser.close();server.close();process.exit(0);
})().catch(async e=>{console.error(e.stack);fs.writeFileSync(path.join(R,'docs/host-ui-v5-browser-results.json'),JSON.stringify({scope:'compiled frontend with synthetic API responses',tests},null,2));if(browser)await browser.close();server.close();process.exit(1)});
