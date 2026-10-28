# ICU Sepsis Intelligence · 0.3.0 开发验证版

**这是可构建、可运行、带真实 MongoDB 持久化和 Angular 交互的可视化软件工作台，不是临床完成版，不可用于患者诊疗。**

最终技术补充优先：Java 15 / Spring Boot 2.7.18 / MongoDB / Angular 20.3.34。默认演示模式使用明确标记的模拟数据；真实源模式需要配置医院只读数据源。真实模型未接入。没有风险百分比、训练指标或临床效果承诺。

## 宿主风格界面修订 v4（最新）

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
