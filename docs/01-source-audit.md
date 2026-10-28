# 参考源码审查（首轮）
审查日期：2026-10-28。只读克隆，不连接仓库配置中的任何医院地址。

## 固定版本与范围
- icu-alert-system：master，798b3ef6aaa7aad23355e26d9ed11bada3bc2343。
- icu-quality-dashboard：master，3d3ceb56777367a55603181df6acf6a52434f459。
- 完整受版本管理的目录清单随包提供。已检查关键源码、依赖、配置及相关测试源码；未声称运行参考项目全部测试。
- 两项目后端为 Python/FastAPI，前端为 Vue 3/ECharts；不是 Java/Angular 宿主工程。无法由此核实现有 ICU 的 Spring Boot、Angular、认证、页签或生产 MongoDB 版本。

## 逐项迁移清单
|能力|源码位置（相对各仓库）|经源码确认|迁移决策|
|---|---|---|---|
|感染证据|alert: backend/app/alert_engine/syndrome_sepsis.py:91–281|支持/可能/不支持/未知四态；明确否定与不确定性分开|复用四态与来源思想；独立 Java 保守文本规则；复杂实验室证据整合待临床审核|
|时序特征|alert: backend/app/alert_engine/temporal_risk_scanner.py:25–177|顺序 hr,map,spo2,rr,temp，float32 [1,T,5]；默认回看12h、5min栅格，T=145；元特征[age,female,icu_days,vent,sofa,lactate]，shape[1,6]|保留审查契约，不直接沿用缺失策略|
|缺失处理|同上 _align_series_to_grid|先前向携带；前段缺失填固定中位数，SOFA/乳酸也有默认值；缺少逐特征缺失与新鲜度张量|不迁移为临床有效输入；新代码缺失保留 unknown，不补正常值|
|模型加载|alert: backend/app/services/temporal_model_runtime.py|ONNX / Torch加载；无权重、加载失败可转启发式；推理失败/非法输出也有回退|不直接移植；首版模型明确 unavailable，规则独立运行|
|模型输出|同上 _parse_raw_output|可按数组游标解释总体、器官、时间窗输出；宽松猜测输入维度|禁止猜测模型契约；接入前要求签名、完整哈希、目标/标签、特征清单|
|来源标注|alert: backend/app/services/prediction_contract.py|当前版本已区分trained_model/rule_estimate/unavailable/unknown；含显示和LLM保护，不能声称旧项目完全未区分|迁移来源隔离；加强临床验证/校准门控|
|扫描持久化|alert: backend/app/alert_engine/scanner_temporal_risk.py|默认900s扫描，4/12/24h输出与来源字段持久化|新版本暂为手工触发；可靠批量调度待做|
|休克筛查|alert: backend/app/alert_engine/syndrome_sepsis.py:282–385；sepsis_scanner.py|前者区分血流动力学异常与休克筛查，容量与其他原因保持unknown；scanner还保留其他路径|重写单一安全边界；不诊断、不自动医嘱|
|SOFA|alert: backend/app/clinical_core/scoring/calculators/sofa.py、sofa2.py、sofa_router.py；quality: scoring/sofa_core.py、missing_policy.py|存在多个版本及缺失策略|不混用、不从缺项推算总分；首版SOFA未实现，明确待补|
|Bundle四维|alert: backend/app/alert_engine/syndrome_sepsis.py:561–977、1448–1844|适用性、执行、目标、审核独立；完成率有分母守恒|复用四维与审计设计，Java事务重写|
|重复预警|alert: backend/app/alert_engine/base.py:_is_suppressed（约3358行）|按患者/规则时间窗及每小时数量抑制|新代码以科室+患者+住院+规则+严重程度+配置化30分钟桶的活跃键抑制，升级不被旧低级别遮蔽；临床阈值待审批|
|病例桥接|alert: backend/app/alert_engine/sepsis_scanner.py:_bridge_*；tests/disease_center|有病例证据与并发/幂等测试源码|保留可追溯关联思想；不声称完整迁移疾病中心|
|1h/3h/6h|quality: icu-quality-backend/config/bundle_rules.py；scoring/bundle_engine.py|明确写明1h/3h/6h；非猜测“136h”；不同窗口与医院变体存在|记录其真实含义，但不把地方实现直接当通用生产指南|
|危险休克口径|quality: config/bundle_rules.py:septic_shock_confirmed|配置名s3，代码return k2 or (k1 and hypotension)，即升压药可单独使其为真|不得迁移为临床诊断；其他原因及复苏充分性需临床判断|
|质控分母|quality: summary.py:_compute_icu05；tests/test_denominator_logic.py|合格分母、排除与分子子集；该测试部分为模拟函数而非直接生产函数调用|新代码直接测实际统计服务；首版任务级指标，不冒充完整国家质控口径|
|数据适配|alert: backend/config.yaml；quality: db.py、scoring/data_adapter.py|SmartCare.patient._id关联bedside.pid/drugExe.pid/deviceBind.pid；hisPid跨DataCenter；设备绑定需时间有效性|仅输出字段映射草案，不接生产；只读适配待真实契约|
|UI|各frontend/package.json，quality src/components|Vue3，ECharts5；有BundleDetail、OverridePanel等|不复制Vue到Angular，暂独立模块；宿主集成待提供工程|

## 模型证据结论
本次git克隆内未发现.onnx/.pt/.pth权重。scripts内有CQL sepsis、心脏骤停校准、知识预训练、呼吸预测训练等脚本，不足以证明本时序模型的训练目标、标签质量、患者外/时间外验证、校准及部署权重来源。加载测试使用FakeSession只能验证软件分支，不能验证临床有效性。不能断言外部环境绝无模型；只能认定本次所取源码未提供可核验的完整临床模型包。

## 待深入审查
未完成全仓安全审计、所有业务调用图、性能压测、全部参考测试执行。参考配置可能包含环境相关信息，交付包不复制其凭据或完整配置。所有生产环境能力仍待确认。
