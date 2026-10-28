# 真实源适配修订 v3（只读边界与证据语义）

## 基线与适用范围

本次直接修改 Java / Angular 源码，基于上一轮本地临床工作台提交 `1f577d3`；不是针对当前远程主线自动合并的承诺。用户报告本机 `b0bb759` 上有大量未提交改动，不能直接覆盖目录或重复应用 v2 补丁。交付含增量补丁、完整参考源码与重新构建的 JAR，未推送 GitHub。

依据是用户提供的两阶段脱敏 MongoDB 探查报告，本环境未连接用户电脑或医院 MongoDB，未访问/更改其源库、索引、账号或 Mongo 拓扑。报告中尚未确认的状态字典、时区和住院语义未被猜测为已确认。

## 已实现

1. **血压分通道。** bedside 原始码映射到 MAP/SBP/DBP_IBP 与 MAP/SBP/DBP_NIBP；保留 sourceParameterCode、measurementChannel、来源 ID。不同通道不当作重复或冲突；同通道同时间冲突仍排除。MAP 曲线按通道独立绘制，异常也独立列出。没有批准的择优规则时，不合成为唯一 MAP/SBP，不自动给 qSOFA 血压项赋值；这不是把缺项按零计算。
2. **住院上下文保护。** 读取前核对请求 encounterId 与当前源患者文档派生的上下文；变化时拒绝。时间窗下界不早于该文档的入科时间；入科时间未知则显式拒绝。此措施不证明每个 `_id` 永不复用，也不等同于原生 HIS 住院关联验证。
3. **跨库候选报告隔离。** hisPid 不再回退使用 mrn。按 HIS 患者键找到的乳酸记录不标为已确认本次住院，encounterId 留空、encounterLinkStatus=unconfirmed、clinicalUse=context_only。它们在“候选报告”/原始记录中明确展示，不进入当前判定、当前患者趋势或治疗完成核对。没有按未验证的 mrn 字段伪造精确住院关联。
4. **时间不再混用。** authTime 标为 reportedAt / timeBasis=reported，采样时间为空；源入库时间未知则 ingestTime 为空且 ingestTimeBasis=unknown，不复制事件时间冒充入库时间。有效床旁记录可作为当前读取的观测核对，但不能据此证明历史时点当时已可见。
5. **检验状态不猜测。** resultStatus 原样保留，缺失保持未知；almFlag/seriousFlag 不代表有效性。源乳酸质量为 unverified，不自动认为正常/达标。缺失单位不默认补 mmol/L；带单位后缀、比较符或非有限结果不解析成精确数值。
6. **关闭未核验执行推导。** 原 findDrugEvent / findOrderEvent / fluidVolumeMl 及跨时点聚合入口无现行生产调用；保留签名但显式抛 SOURCE_EXECUTION_UNVERIFIED，避免以后误用为空/零或已完成。不将 createdTime/orderTime/startTime 互相回退冒充执行时间，也不以药名或 liquidAmount 自动确认治疗。没有删除数据库数据。
7. **前后端标记贯通。** Domain、Views API、评估响应、曲线、来源明细和原始记录均带必要限制字段。真实源模式首屏明确提示通道待选、源入库时间未知及乳酸关联待核对。历史诊断列表不再被拼入当前诊断线索。

源读取增加字段白名单投影与既有时间窗/条数限制。没有新增索引，也没有宣称已解决 explain 的 BSON 解码问题或证实查询命中最优索引。

## 接口变化

schemaVersion 仍为 2，新增可选字段 sourceWarnings、pressureSelectionRequired、pressureEvidence、contextEvidence。Observation 新增来源通道、时间含义、关联状态、结果状态和使用限制；ingestTime 对未确认的真实源记录可为 null。真实源血压 parameterCode 不再合并为 MAP/SBP；旧消费方需要同步升级。demo 历史 MAP/SBP 数据保持原路径。

必须成对部署前后端。不能只替换后台并继续使用旧曲线前端，否则新通道可能不显示。

## 验证

- 后端完整回归 93 项通过（含专用沙箱 Mongo 副本集集成测试，0 跳过）。新增 13 项源适配测试与 4 项评估测试，均为合成文档，不连接医院库。
- 前端单元测试 43 项通过，Angular 生产构建通过。
- 编译后前端 + 合成 API 浏览器回归 16 个场景通过，含双通道独立展示、候选报告不绘图/不算完成、原始记录时间标签。截图 44/45 已目视核对，均为模拟数据。
- 最终运行 JAR 冒烟：3 名本地模拟患者的新元数据字段与评估响应正确返回，服务的前端与最终构建一致；见 `source-adapter-v3-runtime-smoke.json`。此项不代表真实源已联调。
- 完整测试最初发现沙箱旧 demo 观测因时间推移过期；仅重置专用 `127.0.0.1:27027/icu_sepsis_test` 后重新播种，93 项通过。未降低时效阈值或放宽断言，未重置任何用户库。复现集成测试应使用专用新鲜测试夹具，不能对临床源库运行测试。

## 应用到用户本地工作区

先保存并审查当前改动，特别是移除凭据文件；不要执行 reset --hard，不要直接覆盖。让本地 Agent 在独立分支执行：

```bash
# 先检查当前 v2 工作区与增量补丁是否兼容；不会修改文件
git apply --check /path/to/source-adapter-v3.patch
# 仅检查通过后再应用；有冲突需逐项合并，禁止强行覆盖
git apply /path/to/source-adapter-v3.patch
```

补丁基于 `1f577d3` 的状态；在该精确基线上亦可 git am。测试文件和文档包含在补丁中；构建生成的静态资源不包含在补丁，须重新构建前后端。完整 ZIP 与运行 JAR 已含匹配资源。

构建/测试沿用 `scripts/build.sh`、`frontend npm test` 和 `tests npm run test:clinical`；Java 15、Maven、Node/npm 为前提。本次没有新增登录/SSO，也未接入 AI 服务。用户业务 Mongo 是 standalone 的问题未被自动改动；在启用事务写入前，应另行准备独立业务副本集，不改临床源实例、不靠关闭事务掩盖一致性问题。

## 仍待本机联调

真实源字段/单位核对、宿主时间逐字段对照、HIS 原生住院键、血压择优政策、LIS 撤销/更正/有效状态、给药执行状态机、源查询计划和业务副本集写入验收。代码可进入本机联调，不等于已具备临床上线资格。
