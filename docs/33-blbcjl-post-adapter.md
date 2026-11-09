# BLBCJL POST 适配：开发与内网配置说明

2026-11-06。承接 docs/31、docs/32。本轮只读取用户附件结构并使用合成数据测试；没有调用真实接口或查询真实医院数据库。附件及原始请求/响应未复制进仓库、测试、打包目录或应用日志。

## 已确认并实现

用户确认：POST JSON；BLH 取当前 SmartCare patient.mrn，每次住院唯一；BQDM 使用用户指定的固定值，不从 patient 科室字段推导。接口地址、accesskey、固定 BQDM 和过滤参数均为服务端配置，不在代码内硬编码，也不向浏览器暴露。

读取前从只读 SmartCare patient 重新取得 mrn，核对宿主 patient ID、本次住院键及当前科室。当前 mrn 与原住院上下文不符时拒绝；不取 hisPid 代替 BLH。来源实际科室与请求里的固定 BQDM 是不同用途：前者用于授权上下文核验，后者按用户指定值发给接口。

请求结构 method=BLBCJL，data 包含 BQDM、BLH、MBKMC、WJJGMC；accesskey 在服务端请求 JSON 中，不记录请求正文。MBKMC/WJJGMC 为可配置过滤值，应按现场接口和附件用途填写；不将非空过滤请求宣称为全量病程。

响应 code=0（字符串或整数）且 data 数组才解析。逐文书要求 BLH 和固定 BQDM 与本次请求完全一致；缺正文、归属不符或超限整批拒绝，不把混入文书保存在当前患者。保留 WBNR 原文、MBKMC 类型显示及候选 BLXH；HZXM 不单独进入元数据。WBNR 自身可能含身份信息，按原病程长期加密快照和访问授权处理，不代表原文已脱敏。

附件观察到 175 个 WBNR 字段，未见独立可信时间字段；不等于线上总量上限或所有病例文书数。BLXH 唯一作用域及修订排序、类型语义、来源完整性和时间尚未确认：对应确认标记保持 false，complete=null；正文日期不提升为文书/结果可用时间，不能自动进入冻结证据或病程 AI。

## 传输与失败处理

- POST、UTF-8 JSON 请求；响应须 200 且 application/json 或 +json。默认 UTF-8；显式非 UTF-8 charset 拒绝，待实际响应头核实后适配。
- 不跟随跳转、不自动重试；连接超时默认 5 秒，读取空闲超时默认 15 秒（不是整个下载绝对总时限）。响应字节默认最多 2 MiB，可配置但硬上限 20 MiB；文书数/正文字符沿用既有上限。
- 严格 JSON，拒绝重复键和尾随内容；复制附件含未转义原始换行，不据此放宽线上解析。不把复制文本的格式问题当作线上服务已验证问题。
- HTTP/格式/归属错误只返回固定错误类别/说明，不附 URI、key 或病程正文；既有快照保留，不影响规则、预警及看板。
- 默认关闭同步和适配；只读源尚未配置时不因新增适配强制阻塞应用启动。
- 定期同步沿用现有任务，只扫描项目侧已知患者上下文，并在 SmartCare 再核实 admitted 状态。不能宣称覆盖未建立上下文的所有患者，或保证出科前最后文书；真实来源事件和最终同步仍未接入。

## 内网 application.yml 配置

保留内网已有数据库、宿主、科室、角色及 AI 设置。将项目新模板的 app.clinical-notes 下 blbcjl 段合并到已有配置；不要另写重复 app 键，不要直接覆盖原文件。

```yaml
app:
  clinical-notes:
    sync-enabled: false
    blbcjl:
      enabled: false
      endpoint: ${CLINICAL_NOTES_BLBCJL_ENDPOINT:}
      access-key: ${CLINICAL_NOTES_BLBCJL_ACCESS_KEY:}
      bqdm: ${CLINICAL_NOTES_BLBCJL_BQDM:}
      mbkmc: ${CLINICAL_NOTES_BLBCJL_MBKMC:}
      wjjgmc: ${CLINICAL_NOTES_BLBCJL_WJJGMC:}
      connect-timeout-ms: 5000
      read-timeout-ms: 15000
      max-response-bytes: 2097152
```

endpoint/access-key/bqdm 使用附件中的对应配置，BQDM 不从患者自动计算；mbkmc/wjjgmc 按请求用途配置。本交付没有夹带实际凭证或固定科室值。

先配置并妥善保管长期快照的 CLINICAL_NOTES_KEYS_JSON、CLINICAL_NOTES_ACTIVE_KEY_ID、CLINICAL_NOTES_FINGERPRINT_KEY_ID（详见 docs/27），有历史快照时保留原密钥与密钥 ID，不重新生成替换旧密钥。随后在内网确认配置，才将 app.clinical-notes.blbcjl.enabled 和 sync-enabled 都改为 true（或设置相应环境变量）。任一保持 false 就不会由本适配执行同步；缺密钥时既有服务在发起来源读取前降级为密钥不可用。

启动仍使用既有 application.yml：

    java -jar sepsis-intelligence-0.3.0.jar "--spring.config.additional-location=file:./application.yml"

医生进入“记录与依据→病程记录与长期快照”，点击“同步病程来源”。核对类型、数量、住院归属、正文读取、重复同步与错误降级。同步成功但时间/完整性未知应如实显示，不等于已完成历史证据验证。未运行真实院内联调，不提供虚构成功截图。

## 本轮验证

最终 mvn -f backend/pom.xml '-Dfrontend.skip=true' package -q：335 项，332 通过、3 条条件式 Mongo 集成检查跳过，0 失败/错误。本轮没有重新跑真实本机 Mongo 检查；不混用上一批次结果。

新增 9 项测试仅使用合成数据或 127.0.0.1 临时 HTTP 服务：精确 POST 格式、固定 BQDM/住院 BLH、无姓名请求字段、响应字符串/数值成功码、归属/正文/失败码、严格解析、超限、完整性未知、跳转不跟随、非 JSON 拒绝、住院和科室变更拒绝。没有请求附件内地址。

本轮无前端代码变更，沿用已通过的上一批 69 项前端与 45 个合成浏览器场景；不将其描述为重新运行。JAR 重新打包并保留此前 UI/模型标识/血气乳酸修复。git diff --check 通过。检查待提交文件未含附件 accesskey 或原住院标识。

主要新增：BlbcjlProperties、BlbcjlProtocol、BlbcjlClient、BlbcjlNoteSource 与三份测试；application.yml 新增可配置段。未修改医院数据、未写正式病历、未发病程给模型；K1 窗口保持待确认。

## 状态与回退

这是已实现、可在内网配置联调的接口适配，不是已接通并验证。尚待核实：真实 HTTP 响应头/编码/JSON、过滤字段含义、分页/截断、类型和 BLXH 版本语义、可信时间、院内授权及完整备份恢复。病程 AI 综合复核仍未启用。

关闭上述两个开关可停止同步；保留已有加密快照、版本及密钥。当前全部本地修改尚未提交/推送，本包包含自 ace25bb 后的本地 K1、UI 和接口适配修订。
