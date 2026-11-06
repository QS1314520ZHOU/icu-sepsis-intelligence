# 科室队列、预警复核与草稿校验（2026-11-06）

本批接续 docs/25，随后按用户新增的“病程长期保存版”交接继续，见 docs/27。以下为已实现的软件行为，不代表临床能力已经验证。

## 已实现

1. 预警处置增加 false_positive（经复核确认误报）、reconfirm（再次确认）。reject 仅表示驳回；原因中出现“误报”不会自动变成确认误报。保留原 status 字段兼容，新增 reviewOutcome、reviewedBy、reviewedAt，并保存状态历史；真实患者仍不创建治疗任务，重复复核也不重复创建演示任务。
2. 保存触发证据指纹，刷新时间和证据排列变化不构成新证据。同类预警有新的触发记录/数值/规则依据时，未结束记录保留原处置原因、标记需重新复核；已结束记录保留，新的待复核记录关联原复核链，不静默修改旧结论。旧记录无指纹时只建立基线，不假称已经出现新证据。并非完整医院事件去重或临床事件识别。
3. AI 解读使用最新触发依据；早于证据变更时间的旧解读不再返回为当前可用结果。刷新预警列表清除页面旧解读。
4. 新增 WardPriorityService、WardPriorityController 和 /api/v1/quality/ward-priority。查询全部当前宿主科室范围未结束预警，先按患者及住院合并，再按既有严重度和复核状态稳定排序；不存在“先截最新30条再排序”。页面明细分页，完整汇总不依赖 AI。
5. 四类文字为优先查看、需要关注、持续观察、信息不足。此批为既有预警工作队列，尚未整合尿量、完整器官功能、趋势等全部临床排序输入；无预警不代表正常，未新增临床阈值或预测概率。
6. AI 科室入口改为解释系统队列，最多将排定后的前20个患者住院交给模型说明，完整队列独立保留。提示词禁止重新排序；列表顺序完全由代码决定，不依赖模型输出。AI 不可用不影响队列。
7. 新增 /api/v1/quality/alert-reviews。按患者住院及软件复核链去重，同链取范围内最新记录。单列明确确认误报、仅驳回、待反馈、需重新复核和历史反馈不明；反馈率分母为全部链减历史反馈不明，分子为最新记录有明确复核时间且无需重新复核的链。接收/暂缓属于反馈，不等于确认筛查。没有可靠临床事件时间，提前量不可用；不是临床误报率或 AI 效果统计。
8. 新增 CitationEvidenceGuard：每条数值只核对本条实际引用；常见指标的对象、数值、单位须对应同一测量，趋势端点不能借用其他指标数字；不再无条件放过小于等于10的临床数值。保留具体剂量拦截、未知引用拦截；过滤不支持的内容。此为保守的有限模式检查，尚非完整语义或时序核验，不能保证医学正确。修正了以前合成 AI 回复中引用无关事实却通过的测试，并将结构化事实排序固定，避免编号因遍历顺序变化。
9. SBAR/病程 AI 草稿可本地编辑、复制并恢复原稿；复制带 AI 草稿、生成时间与人工修改标记。数据页面刷新后提示草稿可能过期；编辑中阻止宿主患者切换，取消/完成后解除，切换后清除。人工文字不会自动继承原引用校验，也不自动发给模型或写正式病历。病程原文的长期快照与这些 AI 草稿分开，见 docs/27。
10. 科室队列、统计、病程目录及部分独立路由按需加载；未通过调高预算掩盖包体积增加。

## 主要文件

- backend/.../alerting：WardPriorityService、WardPriorityController、AlertReviewStatsService、ScreeningAlertService。
- backend/.../application：WorkflowService、Store；domain/Domain、api/Views。
- backend/.../ai：CitationEvidenceGuard、AiAssistService、AiController、PatientContextBuilder。
- frontend/src：ward-priority-panel、alert-review-stats-panel、pages/alerts、ai-brief-view、ai-assist-card、ai-assist、api、embed-workspace、sepsis-panel 与路由。
- 新增规则队列、复核工作流、统计、权限、引用检查测试，并扩展合成浏览器回归。

最终联合验证和剩余优先级统一记录在 docs/27，避免将本批中间测试数字当作最终结果。
