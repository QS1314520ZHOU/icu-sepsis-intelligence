# 2026-11-07 application.yml 完整离线审核

## 审核对象与结论

核对项目根目录私有 application.yml、用户本轮粘贴的完整配置、程序内置模板及实际 Java 配置绑定代码。采用严格 YAML 解析、Spring 属性解析、真实配置类注入和 MEWS/RuleConfig 绑定；没有启动完整后端，没有连接数据库或调用医院接口。

最终完成 33 项离线检查，全部通过：无重复键、无公开模板漏项、无发现未使用的 app 配置键。当前进程没有发现这些配置对应的环境变量覆盖。这里确认的是本机文件和绑定结果，不是现场服务器进程已经加载的配置。

## 用户粘贴文件的差异与修正

所贴文件已有连接、AI 模型、病程接口及密钥等设置，与本机现有配置一致；差异是缺少 8 个后续新增键：

| 配置键 | 补充值 | 影响 |
| --- | --- | --- |
| app.ai.note-review-enabled | true | 缺少时源码默认 false，会关闭病程片段 AI 复核入口 |
| app.screening.mews.enabled | true | bedside MEWS 开关 |
| app.screening.mews.fresh-minutes | 30 | 除体温外四项新鲜度 |
| app.screening.mews.temperature-fresh-minutes | 240 | 用户明确的体温 4h 周期 |
| app.screening.mews.max-skew-minutes | 15 | 除体温外四项最大时间跨度 |
| app.screening.mews.review-threshold | 5 | 本地候选复评阈值，非诊断阈值 |
| app.icu05.k1-selection | maximum | 显式记录最高值选取方式，不启用未确认的窗口 |
| app.clinical-notes.initial-delay-ms | 180000 | 显式记录原有启动后 3min 的病程扫描延迟 |

MEWS 五项在最新程序中本就有默认值；补入 YAML 是为了可审阅和可维护，不是改变当前评分。K1 选值与窗口保持分开，未新增正式窗口开关或放宽分子分母。

逐项比较后确认：用户所贴文件已有的所有配置值保持一致，包括数据库连接、账号口令、AI 密钥和病程快照密钥；没有重生成或轮换密钥。补充了 Basic 默认科室与 iframe 多科室来源的说明。

## 发现并修复的旧环境文件冲突

.runtime/intranet.env 原有 11 项与当前私有 YAML 不一致：业务 MongoDB URI、宿主 origin、AI 网关根地址、对话路径、向量路径、网关密钥、快速模型、推理模型、向量模型、生成 token 上限、store-prompt。

快速和推理模型仍为旧 qwen3-14b。已在忽略目录中备份原文件，再把上述 11 项同步为已确认 YAML 的值；其余字段不变。复核后该文件现有变量与 YAML 对应项不再冲突。旧文件的备份不要重新导入。

Spring 不自动读取 .env 文件；已进入服务器进程环境的变量仍会覆盖 YAML。本机当前未发现覆盖，不代表现场系统或服务没有旧变量。现场若仍显示旧模型，应检查实际启动环境与 JAR 版本。

## 各模块审核

- HTTP：监听地址和端口可正确绑定；错误信息不自动回显。
- MongoDB：业务库属于独立 icu_sepsis_* 命名空间，SmartCare / DataCenter 使用独立源配置，副本集配置一致。未把生产 DGXMED 改成近期只读检查的测试地址。
- 源认证：两个源各自配置账号与认证库。auth-source 为空时按各自 URI 数据库名处理；源账号是否真正只有只读权限，需要现场 DBA 核验，不能靠 YAML 宣称已验证。
- 嵌入：宿主为精确 origin，未允许任意 frame ancestor；HTTP 内网宿主开关与现场配置相符。
- 多科室：app.department=DEMO-ICU 保留为 Basic 本地默认范围，不伪造一个正式科室。iframe 以宿主患者 ID 交换上下文，并以源库患者实际科室核验；病程扫描已开启已知多科室模式。Basic 方式如需查看正式科室，需另配置其默认范围。
- 本地角色：三个密码满足程序要求的长度且互不相同。
- AI：快速/推理均为用户指定 Qwen3.8-Next；向量模型和医院自定义路径已对应。fast=off、reasoning=on 思考模式有效；旧统一关闭开关=false。HTTP 场景不需要跳过 TLS 验证。store-prompt=false，不等于关闭独立病程快照保存。
- 病程：同步、适配器和多科室开关一致；endpoint、访问密钥和读取上限可解析；BQDM 保留用户指定空值，不从患者科室推导；BLH 在代码里仍从当前 patient.mrn 取得，方法仍是 POST。
- 密钥：JSON 格式、Base64 32 字节材料、活动/指纹 key ID 和不同用途材料均通过检查；实际历史快照解密仍依赖现场保留对应旧密钥。本次没有读取历史正文。
- 预警：后台间隔 600000ms，即 10min；病程扫描 900000ms，即 15min，启动延迟 3min。页面按请求计算与后台扫描不是同一个节奏，不能据这些配置称为秒级实时推送。未擅自提高巡检频率或服务器负载。
- MEWS：体温 240min，其余四项 30min、跨度 15min、候选阈值 5；与用户最新要求一致。下方 app.rules 的通用新鲜度并不替代 MEWS 的独立时间策略。
- 演示审批标记：app.demo=false 与 rules.approval=DRAFT_DEMO_ONLY 不是同一用途。保留旧受限演示写入 API 的审批保护，不改成“已临床批准”。

## 交付和边界

审核后的完整 application.yml 已提供，与本轮最新医疗界面 JAR 配合使用。完整配置含现场凭据及既有快照密钥，不放入 Git。保留现场不同的历史解密密钥，不要以其他电脑的新密钥覆盖。

从文件所在目录启动最新 JAR，或明确指定 spring.config.additional-location 加载该文件。配置文件不要求附带验证 JSON；没有把数据库读取结果或患者数据放入配置交付物。

尚未现场验证：数据库账号权限与索引权限、医院模型实际响应、病程 HTTP 返回、旧快照实际解密、宿主权限和服务器实际环境变量。这些不能由离线 YAML 审核替代。本次没有部署或推送。
