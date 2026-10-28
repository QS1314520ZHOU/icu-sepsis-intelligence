# 临床工作台修订 v2：实施与验收

## 交付范围

基础提交：`e051ac24b2414bb6bbc9971adb8308c587782ac6`。本修订是实际 Java / Angular 源码修改，不是图片原型。构建产物仍采用原项目 `0.3.0` 文件名；v2 指本次临床概览和评估响应契约，不是临床产品认证版本。未推送远程 GitHub。

### 布局与操作

- 宿主负责全局导航和患者选择。正式入口仍为 `/embed?parentOrigin=<宿主精确origin>`；iframe 内不再套顶层导航、第二份患者列表或登录页。
- 页签：临床概览、观测趋势、原始记录、预警处置、执行与审核。
- 首屏显示当前异常/缺项/待办，六类观测曲线及其事件时间、近期异常证据来源；下方是记录核对表。无数据时显示紧凑的缺项摘要，不留六块空白大图。
- 曲线只连接有效观测，保留缺测断线，不插值或预测；纵轴独立，不能跨图比较斜率。窄容器逐列显示，不隐藏临床证据侧栏。
- 异常入口可查看原始记录来源；待办链接到已有复核与任务界面。现有提交表单保留，没有凭空增加已完成医嘱或治疗执行证据。

### 数据与安全语义

- 评估改用统一 ClinicalDataProvider，支持 demo 与 SmartCare 只读源。仅使用经过单位、时效、质量、入库时间检查的近期有效值，同刻冲突排除；不把跨时间历史极值拼成当前状态。
- 缺项时 qSOFA 为 null；数据源失败为 unavailable，不再输出 0 分、极低风险或自动休克诊断。感染文本只作为待核对线索，否定与历史描述不作为当前确诊。
- 移除未经验证的加权风险分和自动 1h/3h/6h Bundle 达标。乳酸记录只证明有记录，不能证明治疗达标；没有实际执行映射的采样、抗菌药、液体和复评项目均保留待核对。
- 修复患者范围查询重复 patientId 条件造成的服务错误，并实际应用源库查询排序/条数限制。
- 患者/住院响应校验、最新请求优先、草稿切换保护和父窗口精确 origin/source 检查保留。没有新增第二套登录、SSO 或用户认证；用户选择的宿主医生模式未改变。
- 消息来源校验不等同于可信身份认证。真实宿主仍须正确配置精确 origin、iframe 安全策略，并配合切换回执/草稿拒绝状态；不能据此宣称完成了生产授权体系。

## 接口变更（须前后端一起升级）

`GET /patients/{patientId}/sepsis-assessment` 响应为 `schemaVersion: 2`，主要字段：patientId、encounterId、available、screeningStatus、screeningLabel、missingFeatures、findings、qsofa、latestEvidence、checklist、model、ai。

状态包括 unavailable / review_required / insufficient_data / not_triggered。旧 riskScore、自动 syndrome / septicShockS3、自动治疗 Bundle 结果已移除。未知布尔值保留 null。旧前端不可直接使用新响应；若宿主其他模块依赖旧响应，也需同步修改。原始观测、预警和任务服务沿用现有入口。

## AI 与临床边界

本轮未接入大模型或预测模型，不提供虚构的风险概率、SHAP 解释、诊断摘要或模型指标。界面如实显示“未配置预测模型/摘要服务”。后续可另行配置院内摘要服务，但不能用通用大模型替代已验证的预测模型，也不能自动开立医嘱。

本次仍为软件验证版本。真实源字段、检验单位、采样/结果时间、医嘱与实际给药、升压药持续状态和完整 SOFA 需要院方数据字典及临床验证。新概览是只读核对，不代表已完成真实患者诊疗写入闭环。

## 验收结果与范围

- Java 后端：76 项通过，0 失败、0 错误、0 跳过；包括本地 Mongo 副本集集成测试、新契约 HTTP 测试及 12 项临床语义测试。
- 前端单元测试：36 项通过；Angular 生产构建成功。
- 浏览器：14 个场景通过，详见 `clinical-workbench-browser-results.json`；覆盖曲线、来源、待核对表、预警入口、未保存草稿、快速切换、缺数据、非父窗口消息、清空患者、部分接口失败、源故障、窄容器、键盘导航和脚本错误检查。
- 浏览器范围是“编译后的前端 + 人工模拟 API + 演示宿主”；不是医院真实接口或临床有效性测试。截图 40–43 均使用模拟患者。
- 运行包冒烟：实际启动最终 jar，连接专用本地模拟 Mongo 库，3 名模拟患者均返回 schema v2；缺数据为 insufficient_data，异常为 review_required；jar 所服务的前端与最终构建一致。详见 `clinical-workbench-runtime-smoke.json`。此项仍不是医院联调。
- 目视复核：桌面正常、紧凑缺项、源故障、窄容器四张截图已检查；窄容器页签允许在导航条内横向滚动。

## 构建、运行和复现

需要 Java 15、Maven、Node/npm 和本地 MongoDB 副本集；沿用项目脚本。不要把医院源库作为演示库。

```bash
# 在原仓库基础提交上应用补丁；先保存自己的未提交改动
# 若不是该基础提交，先在独立分支检查冲突
git switch -c clinical-workbench-v2 e051ac24b2414bb6bbc9971adb8308c587782ac6
git am /path/to/clinical-workbench-v2.patch

# 完整构建（JAVA_HOME 应指向 Java 15，mvn 应在 PATH）
bash scripts/build.sh

# 首次运行生成本机随机演示配置；按需配置本地 Mongo URI
bash scripts/generate-demo-env.sh
# 编辑 .runtime/demo.env，保持 CLINICAL_SOURCE_MODE=demo
bash scripts/start-demo.sh
```

生成的随机凭据仅留在本机 `.runtime/demo.env`，不在本交付包中。`host-demo.html` 的登录仅为原有宿主演示机制；正式子应用不新增登录。

测试：

```bash
(cd frontend && npm ci && npm test && npm run build)
# 集成测试使用测试代码指定的本地 Mongo 副本集（默认27027），不连接医院源库
RUN_MONGO_TESTS=true CLINICAL_SOURCE_MODE=demo mvn -f backend/pom.xml test
(cd tests && npm ci && npm run test:clinical)
```

浏览器测试需 Playwright 可用及 Chromium；本次使用系统 `/usr/local/bin/chromium`，其他机器可设置 `CHROME_EXECUTABLE`。交付含编译后的运行 jar 与源码；生产部署前须完成真实宿主/接口/权限、异常路径与临床安全验收。
