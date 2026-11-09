# K1 待确认候选窗口：离线对照工具与验证

日期：2026-11-06。承接 docs/30；依据用户本轮明确口径。没有真实历史病例或已授权只读源，用户已明确不连接医院数据库；本轮只实现对照工具并使用合成数据验证。

## 口径与正式边界

- 宽候选：闭区间 [T0−24h,T0+6h]，起点不得早于本次入院。
- 窄候选：闭区间 [T0−2h,T0+1h]，为可比对照同样不早于本次入院。
- 二者均以当时可用、有效且单位已明确为 mmol/L 的乳酸最高值为选中值，≥2 为候选 K1 成立；并列最高值按时间、来源/记录键稳定选择。
- 新版本 k1-window-candidates-20261106-v1，两个窗口分别带 /wide、/narrow。均 confirmed=false、qualityUse=false。当前 ICU-05 返回候选口径元数据，页面显示候选窗口说明；不填写现有正式 k1WindowStart/End/Source，正式 K1 仍“无法判定/乳酸取值窗口待确认”。不改正式规则版本、T0、分子分母或人工覆盖规则。
- 默认评估时点 T0+6h，可选 T0+1h、T0+3h。记录时间与已确认可用时间都必须不晚于各评估时点。窗口未结束且只有低值为 WINDOW_OPEN/未知；有异常且数据完整时可显示“候选成立”，windowClosed=false 仍明确保留，并非最终最大值或确诊。闭窗且取得有效低值、来源完整才显示候选不成立。
- 无有效数据、来源异常或完整性未确认、窗口内未知可用时间/单位/无效记录、同来源记录键值或时点冲突均保持未知。缺本次入院时间/T0、入院晚于 T0 不自行推断。单独统计未来排除、未知记录；失败不能当作无异常。
- 乳酸是候选条件，仍需结合 K2、感染/器官证据及人工复核，不能作为感染性休克确诊。

## 工具与导入契约

新增 K1WindowComparison（纯计算）、K1ComparisonFile（本地 JSON 导入输出），接入既有离线 CLI。命令在 Spring/数据库连接建立前执行，不连接网络或医院数据库：

```text
java -jar backend/target/sepsis-intelligence-0.3.0.jar k1-compare --input INPUT.json --out NEW_OUTPUT.json
```

可用 mvn -f backend/pom.xml -Dfrontend.skip=true -DskipTests package 打包。输出文件须不存在，不覆盖输入或旧报告；允许创建输出父目录。导入限制 20 MiB、10000 个病例住院、每病例最多 10000 条记录；未知字段、重复 JSON 字段和尾随 JSON 拒绝，解析失败不输出正文。默认无 horizons 时只运行 [6]。

输入形状（以下字段示例须全部使用合成或经授权脱敏数据）：

```json
{
  "datasetKind": "synthetic",
  "horizons": [1, 3, 6],
  "cases": [{
    "caseKey": "SYNTH-001",
    "t0": "2026-11-06T00:00:00Z",
    "hospitalAdmission": "2026-11-05T00:00:00Z",
    "sourceComplete": true,
    "sourceError": false,
    "lactates": [{
      "recordId": "SYNTH-RESULT-1",
      "source": "synthetic.fixture",
      "timeMeaning": "采样时间",
      "time": "2026-11-06T00:00:00Z",
      "availableAt": "2026-11-06T00:30:00Z",
      "availabilityConfirmed": true,
      "value": 2.0,
      "unit": "mmol/L",
      "valid": true
    }]
  }]
}
```

未来可设置 datasetKind=authorized_deidentified_history 导入经授权脱敏的历史病例。这个标签只是数据声明，不代表程序替医院授予访问权或完成脱敏。caseKey 必须是一病例一次住院的脱敏键；recordId/source 也须按院方要求转换，仅在院内保留真实键映射。不可把姓名、病案号或自由正文塞入这些字段。

availabilityConfirmed=true 需由数据提供方核实 availableAt 是该评估场景真正可使用结果的可靠时间；不能仅凭采样时间猜结果已可用。回顾本系统时须考虑迟到/本地到达时间，必要时取更晚的实际可用时间。timeMeaning 应明确采样或结果时间类型；二者不可混称。sourceComplete 必须按导出覆盖范围及评估时点核实，不能为减少未知一律设 true。当前结果只证明所提供输入下的逻辑，不自动证明历史信息完整或消除未来信息泄漏。

输出每病例每时点两套完整结果：窗口起止、评估时点、是否闭窗、三态值、原因、选中乳酸数值/单位/记录时间/可用时间/来源/记录键/时间含义/窗口版本。没有直接可用的真实病历文件时，不生成“真实对照完成”报告。

## 差异口径

方向固定为窄窗口→宽窗口：

- added：false→true，明确新增候选。
- reduced：true→false，明确减少候选。
- uncertainChanges：涉及未知且两者不同的转换，不能把未知当 false 计入新增/减少。
- transitions：全部 3×3 转换计数；cases 保留所有病例明细，便于看共同成立/不成立或共同未知。

两个窗口同取最大值、同入院下限、同评估时点及输入时，宽窗口包含窄窗口；闭窗完整数据通常不应出现 true→false。reduced 列表保留供临床复核，不伪造减少病例。窗口不同导致数据质量/完整性限制时，true→unknown 独立列出。

## 合成运行结果（非真实病例）

实际将项目打包后运行 java -jar ... k1-compare，输入 6 个合成病例，输出三个时点：

| 时点 | 明确新增 | 明确减少 | 涉及未知的不同结果 |
| --- | --- | --- | --- |
| T0+1h | 1 | 0 | 2 |
| T0+3h | 1 | 0 | 2 |
| T0+6h | 1 | 0 | 0 |

合成新增键 SYNTH-WIDE-ADDED：宽窗选中 3 mmol/L，记录时间 2026-11-05T12:00:00Z（北京时间 11-05 20:00），来源 synthetic.fixture/r1；窄窗选中 1 mmol/L，时间 2026-11-06T00:00:00Z（北京时间 11-06 08:00），来源 synthetic.fixture/r2。未来才可用的另一合成结果在 T0+1/+3h 不参与，至 T0+6h 才进入比较。来源错误、无数据保持未知。本结果不支持确认宽窗口、临床敏感度/误报率或正式质控有效性。

## 测试与文件

- 后端 RUN_NOTE_MONGO_TESTS=true，mvn -f backend/pom.xml '-Dfrontend.skip=true' test -q：326 项，324 通过，2 原有 Workflow/Embed 集成项跳过，0 失败/错误；包含 4 项本机独立 Mongo 合成持久化检查。
- 新增 9 项对照/文件测试：宽窄差异、阈值/端点、入院裁剪、未来可用结果、开窗低值、来源异常/缺失/未知、重复冲突、未知转换、重复病例、时点限制、文件往返与不覆盖、重复 JSON 与尾随内容拒绝。
- 前端 npm --prefix frontend test：69 通过；build 成功，初始包 647.00 kB，未超 650 kB 警告预算。
- 编译后前端+Edge+合成 API：45 场景通过，无浏览器错误；正式 K1 窗口待确认的原有回归继续通过。
- 离线 jar 实际运行成功；git diff --check 通过。日志/汇总在忽略的 work/tests-31-full.log、validation-31.json、build-31.log、browser-31.log。
- 代码：backend/src/main/java/org/icu/sepsis/sepsis/K1WindowComparison.java、K1ComparisonFile.java；research/toolkit/ResearchCli.java；Icu05Rules.java 仅新增候选政策元数据；frontend/src/icu05-card.ts 仅补候选说明；两份新增测试。

## 后续与回退

等待经授权脱敏的真实导出，核实 availableAt、sourceComplete、来源/单位和时间含义，再用相同工具运行真实历史对照，交临床复核。候选口径在科室确认前不接入正式质控；真实对照结果也不能自行当作批准。此前 docs/30 的医院接口、模型、事件及研究前置保持未确认。本批未推送、合并、部署或写真实病历。可撤去候选说明和离线入口回退，不改现有正式未知状态，不删除病程快照及密钥。
