# ICU Sepsis Intelligence · 0.3.0 开发验证版

**这是可构建、可运行、带真实 MongoDB 持久化和 Angular 交互的可视化软件工作台，不是临床完成版，不可用于患者诊疗。**

最终技术补充优先：Java 15 / Spring Boot 2.7.18 / MongoDB / Angular 20.3.34。默认演示模式使用明确标记的模拟数据；真实源模式需要配置医院只读数据源。真实模型未接入。没有风险百分比、训练指标或临床效果承诺。

## 病程同步配置与诊断修复（2026-11-07，当前版本）

统一JAR内与私有配置的正文转义兼容缺省值，页面显示实际开关、解析版本和安全错误位置。原附件175份离线解析通过；后端391通过/3跳过，前端93、浏览器25场景通过。现场最新响应尚未验证，详见 [更新命令与复测步骤](docs/46-note-sync-effective-config-and-safe-diagnostics.md)。

## 组合趋势显示密度（2026-11-07）

默认紧凑，支持三种预设、曲线/用药行高、时间网格和原始点显示，记住本机偏好。前端89项、浏览器24场景通过，不影响数据或AI分析范围。见 [密度设置与验证](docs/45-customizable-timeline-density.md)。

## 临床工作区与组合趋势（2026-11-07，当前版本）

概览、组合趋势、病程与依据、Bundle复核、预警处理、科室质控分区。组合趋势支持指标/用药选择、共享时间轴、7天查看、拖选放大和所选范围AI；原始事件和可得性限制分开处理。后端384通过/3跳过、前端85、浏览器23个新工作区场景、既有源DTO回放17项通过。详见 [实现、验证及现场事项](docs/44-clinical-workspaces-and-combined-timeline.md)。仅呈现用药执行/操作事件，剂量持续曲线与真实院内联测仍需后续确认；K1继续待确认。

## 紧凑监测首屏（2026-11-07）

概览移除大横幅与独立体征大卡片，流程默认折叠为单行，体征改为连续监测栏。前端79项、合成浏览器54场景通过。见 [首屏整理记录](docs/43-compact-monitoring-overview.md)。

## 宿主蓝白主题（2026-11-07）

按用户参考截图统一为蓝白主题，替代上一版深蓝及蓝绿色。前端79项、合成浏览器54场景通过，详见 [主题调整记录](docs/42-host-blue-white-theme.md)。

## 临床工作台布局修订（2026-11-07）

修复护理视图历史 Bundle 右侧空白，宽屏措施卡片化；AI 改为全宽区域，顶部新增趋势与 AI 快捷入口。前端79项、合成浏览器54场景通过。见 [布局设计与验证](docs/41-clinical-workbench-product-layout.md)。组合时间轴与分时段 AI 仍为后续设计，尚未交付。

## 流程与病程响应修复（2026-11-07）

新增当前重点与四阶段导航，MEWS 改显示分数/待补全，来源显示观察项，内部状态使用中文。修复本地上下文不存生日后的成人范围核对；新增严格限制在正文内的旧接口转义兼容，附件 175 份文书通过离线解析。后端 370 通过/3 跳过、前端 79、浏览器 53、源结果回放 12 项通过。详见 [本批记录与更新方式](docs/40-clinical-flow-chinese-and-note-format.md)。需同步更新 JAR 与现场 YAML；尚未现场联通验证。

## 现场 YAML 审核（2026-11-07）

已将用户所贴完整配置与代码逐项核对，补齐 8 项新增配置；备份并同步旧环境文件的 11 项冲突，33 项离线配置检查通过。原有口令和病程密钥保持不变。见 [审核记录](docs/39-private-yaml-audit.md)。

## 医疗工作台界面（2026-11-07）

已统一蓝绿医疗配色、控件、指标卡片与 AI 工具布局，适配宽屏及内嵌窄屏。前端 75 项、合成浏览器 51 场景、源结果回放 12 项通过；不改变取值与临床规则。见 [界面改动与验证](docs/38-medical-workbench-ui.md)。

## bedside MEWS 与 AI 早期筛查（2026-11-07）

已从 bedside 五项观察重新计算成人 MEWS，不读现成总分。体温独立允许 4h，其余四项 30min 内且跨度 ≤15min；缺项不补零。有创、无创分别计分，AI 按需解释分项、时间和感染核对要点。详见 [实现、配置与验证](docs/37-bedside-mews-timing-and-ai-screening.md)。最终后端 361 通过/3 跳过，前端 75 通过，合成浏览器 50 场景及源结果页面回放 12 项通过。候选策略尚需现场验证，K1 窗口不变。

## 本地规则、病程长期快照与复核流程（2026-11-06）

本次只读源检查、乳酸精度及逐页结果见 [MongoDB 核对与验证](docs/36-readonly-source-audit-precision-and-note-review.md)。

先前逐项开发状态见 [逐项完成清单与验证](docs/30-source-changes-trend-integrity-and-status.md)，病程能力说明见 [长期保存](docs/27-long-term-clinical-note-snapshots.md)、[证据时点与原文引用](docs/28-clinical-note-evidence-preparation.md)、[本地复核草稿与反馈统计](docs/29-local-review-report-and-feedback.md)。

当前包含本地规则修订、三层人工覆盖/撤销审计、科室统计与基础查看队列、长期加密病程快照、时点版本核对、医生选片段草稿、确定性统计报告及反馈。**K1 窗口未确认，继续“无法判定/乳酸取值窗口待确认”**；未改 ICU-05 分子分母。已实现可配置病程 HTTP 适配器和医生主动的有限病程片段 AI 复核，尚未完成现场真实接口联调及完整病程综合复核；风险模型未训练，有限文字检查不等于医学语义核验。

最近本地验证：后端 348 项（345 通过、3 跳过），前端 75 通过，合成 API 浏览器 47 场景通过，构建成功。另按用户指定地址完成 100 名在科病例只读源检查、5 例去标识结果的 12 项页面回放；不是已部署系统的完整联调或临床验收。后续功能分支依赖隐私 PR #20，未自动合并到主分支。

## 自动打包 · AI 缓存 · 巡检优化 · 护士视图 v11（最新）

见 [docs/19-v11-build-cache-scan-nurse.md](docs/19-v11-build-cache-scan-nurse.md)：GitHub Actions 自动测试并打包（Releases → latest 下载 jar + SHA256SUMS），AI 结果在数据未变时复用 30 分钟，预警巡检只扫在科且数据有变化的患者，护士视图只保留执行相关内容。

## 床旁医生视图与科室质控看板 v10

概览页按"判断 → 处置 → 背景"重排：结论 + T0 计时 + 依据、可直接确认/排除预警；Bundle 默认床旁视图（下一步与剩余时间、相对 T0 的措施时间），质控矩阵可切换；乳酸清除率。新增"科室质控"页签：ICU-05 1h/3h/6h 完成率、救治过程指标、需要关注的病例。详见 [v10 说明](docs/18-clinical-layers-v10.md)。

## 自动预警与 AI 辅助 v9

床旁筛查 / ICU-05 判定"疑似休克"或"感染 + 异常"时自动生成待处理预警（后台巡检、去重、驳回抑制，本患者/全科室）；AI 新增趋势解读、交班摘要（SBAR）、病程记录草稿、家属沟通要点、自由提问、预警解读与科室优先级。详见 [v9 说明](docs/17-alerts-and-ai-v9.md)。

## 宿主风格界面修订 v4

已重做五个嵌入页的蓝白视觉与信息层级：数值、曲线与待办优先；无数据改为紧凑状态和实际历史查询；技术说明可展开。详见 [v4 修改与应用说明](docs/11-host-clinical-ui-v4.md)。本轮不改源库、身份协议或后端诊疗逻辑，AI 仍未配置。前端 43 项、合成浏览器 23 场景通过；后端常规测试 54 项通过，2 个 Mongo 集成测试跳过。本轮没有再次进行医院现场联调。

## 真实源适配修订 v3（历史交付）

详见 [真实源适配修订说明](docs/10-source-adapter-v3.md)。有创/无创血压分通道；乳酸报告时间不作采样时间，本次住院归属未确认的报告仅供核对；未核验给药/输液推导显式禁用。新增字段贯通 API 与界面，需前后端同时升级。未修改用户源库或新增身份验证；AI 仍未配置。

## 临床工作台修订 v2（历史交付）

基于 `e051ac24b2414bb6bbc9971adb8308c587782ac6` 直接修改源码，详见 [实施与验收说明](docs/09-clinical-workbench-v2.md)。

- iframe 内只有当前患者核对条和五个局部页签，不增加第二份患者列表或登录界面；宿主医生模式保留，**本次没有实现新的身份认证/SSO**。
- 首屏改为临床概览：观测曲线、近期异常证据、缺项和待办同屏；原始记录及来源可核对。
- 移除缺数据时的“0分/极低风险”和未经验证的自动休克分类、治疗 Bundle 达标判定；未知保留未知。
- `/patients/{id}/sepsis-assessment` 改为 `schemaVersion: 2`，前后端须一起部署。模型预测与 AI 摘要仍未配置，不生成假概率或假摘要。
- 验收：后端 76 项、前端 36 项、模拟接口下浏览器 14 个场景通过；尚未完成真实医院宿主/源库联调和临床验证。

## 0.3 iframe 嵌入入口（历史说明）
- 正式嵌入路由 `/embed?parentOrigin=...`：不显示独立顶栏、侧栏、患者列表或登录表单；只保留当前患者核对条和四个局部页签。
- **真实宿主协议（2026-10）**：兼容科室概览 v3 `SmartCareReady` / `SmartCare` / `select-patient` / `SmartCareNavigate`；前端见 `frontend/src/smartcare-protocol.ts`，后端绑定 `POST /embed-api/host-context`。
- **真实源库只读接入**：`CLINICAL_SOURCE_MODE=smartcare` 时，患者与生命体征/乳酸等从 `192.168.31.185:27017` 的 SmartCare / DataCenter 读取；业务写入仍强制落在独立 `icu_sepsis_*` 库，禁止写医院源库。
- 启动后打开 `http://127.0.0.1:8080/host-demo.html` 查看唯一宿主患者列表 + 跨来源 iframe 的完整模拟联调。登录只在宿主演示页发生；账号和随机密码仍见本机 .runtime/demo.env，不会通过消息转交。
- 严格校验 parent source / origin / channel；后端验证45秒一次性票据，返回10分钟患者+住院范围令牌。防重放、切换撤销、角色和越权都有回归测试。
- 正在写入或有未保存复核草稿时拒绝切换；宿主在应用成功 ACK 后才改变选中态；旧请求不能覆盖新患者。
- 必读 `docs/08-iframe-integration.md`：消息契约、CSP/代理设置、示例和**生产SSO尚未接入**的限制。演示发行器只在DEMO_MODE=true启用。
- 默认仍保留独立路由以供开发；实际宿主挂载请使用 `/embed`，不是再套一层 `/patients` 页面。

## 既有 0.2 可视化功能
- 患者观察表：后端校验后的 HR / MAP / 乳酸、事件时间、最近筛查和趋势入口；缺失 / 过期保持空值。
- 患者工作台：首屏筛查摘要；HR、MAP、RR、SpO2、体温、乳酸六类观测曲线；6 / 12 / 24 小时切换；点选核查事件时间、入库时间和原始来源。
- 曲线缺测断线、单位和质量校验、同刻冲突不绘制；各图独立纵轴，不做插值、预测或正常值填补。
- 质控：适用任务执行数量条形图 + 同口径明细表；分母外任务单列，零分母不画完成率。
- 手机：曲线卡片单列、横向表格内滚动、患者列表保留右侧证据入口。
- 新模拟库首次启动才生成历史模拟观测，不改写已存在的观测；旧演示库不会自动出现新曲线。需新建专属 *_demo 库。

## 交付入口
- `docs/01-source-audit.md`：两个固定 commit 的首轮源码审查及迁移决策。
- `docs/02-product-and-architecture.md`：产品、结构、状态机及范围。
- `docs/03-database-and-mapping.md`：实际集合、索引、关联、源系统只读映射草案。
- `docs/04-model-and-clinical-safety.md`：模型、规则、Bundle 与临床验证门控。
- `docs/05-verification.md`：实际构建、测试和已知问题。
- `docs/08-iframe-integration.md`：iframe布局、postMessage、CSP、票据与宿主职责。
- `docs/06-production-integration.md`：真实系统接入前提、权限和部署约束。
- `docs/openapi.json`：从运行中的应用导出的实际 OpenAPI。
- `docs/screenshots/`：实际浏览器验收截图，不是静态原型。
- `backend/`、`frontend/`、`tests/`、`scripts/`：源码与可复现命令。
- `release/sepsis-intelligence-0.3.0.jar`（完整交付包中）：已嵌入 Angular 静态资源的可执行 jar。

## 打包（前端已内置进 jar）

`cd backend && mvn clean package -DskipTests` 会自动执行 `frontend` 下的 `npm install` 与 `ng build`，并把 `frontend/dist/workbench/browser` 打进 jar 的 `BOOT-INF/classes/static/`，部署时只需上传这一个 jar。打包机需安装 Node 18+；已有前端产物时可加 `-Dfrontend.skip=true` 跳过 npm 步骤。`backend/src/main/resources/static/` 不再手工维护。

## 运行要求
- **JDK 15**，本次使用 Zulu 15.0.10；Maven 3.9.9；Node 24；npm 11。
- **MongoDB 副本集**（本次测试 7.0.14，单节点 rs0）。事务是必要条件，不支持以 standalone 静默降级。
- 仅在开发隔离环境运行。JDK 15、Spring Boot 2.7 已处于老旧技术栈；必须在生产前评估安全维护方案，不能因为本次可运行就忽略维护风险。

## 最短启动流程（已有 MongoDB 副本集）
```bash
# 先设置为实际 JDK 15 安装路径
export JAVA_HOME=/path/to/jdk15
export PATH="$JAVA_HOME/bin:$PATH"
# 新项目目录内
bash scripts/generate-demo-env.sh
# .runtime/demo.env 中默认仅连接本机 icu_sepsis_demo 数据库
# 需要时编辑本地环境文件，不要提交凭据
bash scripts/start-demo.sh
```
打开 `http://127.0.0.1:8080`。账号为 `doctor`、`nurse`、`quality`，各自随机密码保存在本机 `.runtime/demo.env` 中。不是固定默认密码；交付包不包含本次运行密码。使用密码管理器或本地查看环境文件获取，不要把真实凭据发到聊天。

有 `release/` 中的 jar 时无需重新构建。只有源码时：
```bash
bash scripts/build.sh
```
开发前端可独立运行：`cd frontend && npm ci && npm start`（仅绑定127.0.0.1，/api代理到8080）。

## 本机 MongoDB 副本集示例
必须使用全新、专属测试目录，不能指向现有医院数据库目录。
```bash
mkdir -p .runtime/mongo
mongod --dbpath "$PWD/.runtime/mongo" --port 27027 --bind_ip 127.0.0.1   --replSet rs0 --logpath "$PWD/.runtime/mongo.log" --fork
mongosh --port 27027 scripts/init-replica.js
```
此示例不启用网络认证，只允许本机隔离开发。不可用于公网、医院生产网络。Java启动自动创建新业务集合索引，不修改SmartCare/DataCenter。启动对数据库名有保护，必须为独立 `icu_sepsis_*` 数据库，演示时还必须以 `_demo` 或 `_test` 结尾。

## 测试
```bash
# Java单元测试；未设置RUN_MONGO_TESTS时集成测试明确跳过
mvn -f backend/pom.xml test
# 真实Mongo集成测试：只使用本机27027的icu_sepsis_test专用库
RUN_MONGO_TESTS=true mvn -f backend/pom.xml test
cd frontend && npm test && cd ..
# 浏览器验收：只允许模拟环境；首次需安装tests依赖与Chromium
cd tests && npm ci && npx playwright install chromium && cd ..
E2E_ALLOW_SIMULATION=true node tests/e2e.cjs
E2E_ALLOW_SIMULATION=true node tests/visual-e2e.cjs
E2E_ALLOW_SIMULATION=true node tests/embed-e2e.cjs
```
浏览器验收会向**模拟演示库**新增评估和任务，不会删除数据。重复执行前若已有未结束任务，部分定位需要选择新预警；建议使用独立演示库并固定演示数据，不能指向真实业务环境。

## 演示步骤
1. 医生登录；正常患者执行筛查，不触发预警；缺失患者明确无法评估。
2. 恶化患者执行规则筛查，查看证据、原始记录、模型不可用和缺失项。
3. 在预警中心填写复核原因并确认，生成“临床复评”模拟任务。
4. 护士登记实际事件时间、来源系统、来源记录ID和原因；不获得诊断确认权限。
5. 医生审核适用性及目标，查看独立状态历史。
6. 质控查看实际分母/分子，卡片携带同一统计时间窗下钻任务列表。
7. 模型页面明确没有真实推理、验证、AUROC或校准结果。

演示观测按首次启动时间生成；超过新鲜度会如实显示过期。重新验收请使用全新专属 `*_demo` 库，不要清理现有业务库。

## 不可省略的限制
未接入现有 ICU 宿主、真实临床数据、SSO、真实训练模型；未实现完整SOFA、正式1h/3h/6h治疗Bundle、可靠异步扫描/重试、完整临床参数与长时序服务端聚合、模型验证计算与生产级运维。首版仅闭环核心软件路径，详见验证清单。本次沿用已提供的演示界面蓝白风格；未获得真实医院宿主的源码、组件库或正式视觉规范。

- v5 床旁工作台（概览 / 预警处理 / 记录与依据、记录单、集束化、宿主角标消息）：`docs/12-bedside-workbench-v5.md`
- [ICU-05 感染性休克 Bundle 时间轴](docs/13-icu05-septic-shock-bundle.md)
- [科研工具包（去标识导出 + 六个研究方向分析）](docs/14-research-toolkit.md)
- [本地字段映射核对报告](docs/15-field-mapping-local.md)
- [AI 辅助解释（院内模型网关 + 指南检索，默认关闭）](docs/16-ai-assist-rag.md)

K1 待确认窗口的离线对照工具、导入契约及合成验证见 [候选窗口对照](docs/31-k1-candidate-window-comparison.md)。不接入正式分子分母，不连接医院数据库。
