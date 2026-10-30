# 16 · AI 辅助解释（院内模型网关 + 指南检索）v8

> 状态：开发验证版。只用模拟网关和合成数据测过，**没有接真实模型做过验证，也没有做过临床验证**。默认关闭；不开启时工作台行为与 v7 完全一致。

## 1. 做什么 / 不做什么

| 做 | 不做 |
|---|---|
| 医生/护士点按钮后，按需生成「病情摘要」或「ICU-05 缺项分析」 | 不自动运行，不推送，不进预警 |
| 把规则引擎已给出的结果整理成要点、数据缺口、可考虑核对、不确定四栏 | 不改写 ICU-05 / qSOFA / 核对状态；判定仍由规则引擎给出 |
| 每条内容带 E#（本次患者事实）或 K#（知识条目）引用，可点开核对 | 不下诊断，不出医嘱，不写药物剂量 |
| 记录每次调用的审计记录和医生反馈（有用 / 不采纳 + 原因） | 不写 SmartCare / DataCenter；只写 `icu_sepsis_*` 库的 `sepsis_ai_interaction` |

## 2. 流程

1. `SepsisAssessmentService` + `Icu05Service` 计算当前结果（与页面同源）。
2. `PatientContextBuilder` 转成编号事实 E1..En：只含数值、时间、规则状态；**不含姓名、病历号、床号、patientId/encounterId、sourceRecordId**；6 位以上数字一律替换为 `[已隐去]`。病情摘要只带各时窗状态，缺项分析带全部 ICU-05 子项。
3. `KnowledgeBase` 按问题事实（异常/缺失/未达标/无法判定）选主题检索，最多 8 条：BM25（中文二元组 + 英文词），配了向量模型时后台构建向量并做 RRF 融合；向量失败自动退回 BM25，不阻塞请求，10 分钟后重试。
4. 调用院内 OpenAI 兼容网关：缺项分析优先推理模型，摘要优先快速模型，失败自动切另一个；网关不支持 `response_format` 时（HTTP 400）退回普通模式重试一次；`<think>` 内容丢弃。
5. 输出校验（逐条）：
   - refs 必须是本次给出的 E#/K#，否则丢弃（“不确定”一栏可无引用）；
   - 含剂量（mg、μg/kg/min、单位…）丢弃；
   - 出现 >10 的数或小数，必须在事实/知识原文中出现过，否则丢弃；
   - 一条都没留下 → `invalid_output`，不展示。
6. 前端只展示引用全部可解析的条目；页脚固定显示“AI 生成，仅供核对参考，须由医生结合原始记录判断；不构成诊断或医嘱。”

状态：`ok` / `disabled`（未配置，仍返回事实和知识条目供人工查看）/ `busy`（超过并发上限）/ `unavailable`（超时、不可达、HTTP 错误）/ `invalid_output`。任何状态都不影响规则卡片。

## 3. 配置（环境变量，均不入库）

| 变量 | 默认 | 说明 |
|---|---|---|
| `AI_ENABLED` | false | 总开关 |
| `AI_BASE_URL` | — | 例：`https://192.168.242.50` |
| `AI_API_KEY` | — | Bearer 密钥；只从环境变量读取，状态接口不返回 |
| `AI_MODEL_FAST` / `AI_MODEL_REASONING` | — | 例：`deepseek v4 flash 0731` / `qwen 3.8 next`（哪个是推理模型请以 probe 结果为准） |
| `AI_MODEL_EMBEDDING` | — | 可选；不配则只用 BM25 |
| `AI_CHAT_PATH` / `AI_EMBEDDINGS_PATH` | `/v1/chat/completions` / `/v1/embeddings` | |
| `AI_FAST_TIMEOUT_MS` / `AI_REASONING_TIMEOUT_MS` / `AI_CONNECT_TIMEOUT_MS` | 25000 / 90000 / 3000 | |
| `AI_MAX_TOKENS` / `AI_TEMPERATURE` / `AI_MAX_CONCURRENT` | 1500 / 0.2 / 4 | |
| `AI_JSON_MODE` | true | |
| `AI_TLS_CA_FILE` | — | 内网自签证书推荐：指定 CA PEM |
| `AI_TLS_INSECURE` | false | 仅隔离测试用；会关闭证书和主机名校验（JVM 全局） |
| `AI_STORE_PROMPT` | true | 审计里保存去标识化 prompt |
| `AI_KNOWLEDGE_DIR` | — | 外部知识 JSON 目录；同 docId 覆盖内置文档（用于科室审批后的 SOP 更新） |

## 4. 内置知识库（73 条，`backend/src/main/resources/knowledge/`）

| 文档 | 条数 | 来源 |
|---|---|---|
| `ssc-2026` | 59 | Prescott HC, et al. SSC International Guidelines 2026. Crit Care Med 2026;54(4):725-812. doi:10.1097/CCM.0000000000007075 |
| `sepsis-3` | 3 | Singer M, et al. JAMA 2016;315(8):801-810 |
| `sofa-2` | 1 | Ranzani OT, et al. JAMA 2025（本系统未实现 SOFA-2 计算） |
| `icu05-rules` | 5 | 科室主任 ICU-05 需求文档 v3 |
| `system-data` | 5 | 本系统数据口径（乳酸时间、血培养时间、执行记录、血压通道、未接入字段） |

中文为人工摘要，不是原文译本，**上线前需要科室医生逐条审核**。不含 SSC 2021。

## 5. API

| 方法 | 路径 | 角色 |
|---|---|---|
| GET | `/api/v1/ai/status` | 登录用户 |
| POST | `/api/v1/patients/{id}/ai/brief`，body `{"kind":"summary"\|"icu05"}` | DOCTOR / NURSE |
| POST | `/api/v1/ai/interactions/{id}/feedback`，body `{"verdict":"accept"\|"reject"\|"edit","reason":"…"}`（reject/edit 必填原因） | DOCTOR / NURSE |
| GET | `/api/v1/ai/interactions?page=&size=` | DOCTOR / QUALITY |
| GET | `/api/v1/knowledge/search?q=&k=` | 登录用户 |

## 6. 院内评测与试点步骤（由本地 AI 代理在内网执行）

1. 网关探测（不涉及患者数据）：
   `AI_BASE_URL=… AI_API_KEY=… AI_MODEL_FAST='deepseek v4 flash 0731' AI_MODEL_REASONING='qwen 3.8 next' python3 scripts/ai-eval.py probe --insecure`
   看：两个模型是否可用、延迟中位数、JSON 模式是否支持、是否带 `<think>`/`reasoning_content`、向量维度。
2. 用上一步结果配置环境变量（CA 文件优先于 `--insecure`），启动应用。
3. 批量生成并统计（患者 id 输出为哈希）：
   `APP_PASSWORD=… python3 scripts/ai-eval.py brief --patients 20 --keep-text --out ai-eval.jsonl`
   看：状态分布、延迟、过滤条数、与规则时窗状态的矛盾条数（应为 0）。
4. 由 2 名医生对 `--keep-text` 输出逐条标注（准确 / 引用不对应 / 遗漏 / 越界），结合页面反馈记录，再决定是否扩大试点。

## 7. 已知限制

- 只用模拟网关测过：校验逻辑、回退、超时、繁忙、JSON 模式回退、向量失败回退。真实模型的输出质量、延迟和稳定性都还没测。
- 数值校验只能拦截“凭空出现的数”，拦不住引用正确但解读错误的句子，所以仍需医生核对。
- 知识库是人工摘要，覆盖 SSC 2026 的 59 条推荐，不是全文。
- 体重、去甲肾上腺素剂量、培养结果、感染部位未接入，模型无法判断 30 mL/kg 或剂量递增问题（已作为知识条目说明）。
- 之前截图里出现过网关密钥，请先轮换后再配置。
