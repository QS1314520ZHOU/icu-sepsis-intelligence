# ICU-05 感染性休克 Bundle 时间轴（v6）

规则来源：`icu-quality-dashboard` 中科室主任的《ICU-05 感染性休克 Bundle 自动判定 · 需求文档 v3》及 `scoring/bundle_engine.py`。
本项目只读取源库，不写 SmartCare / DataCenter；结果为质控规则自动判定，不是诊断，未做临床验证。

## 接口
`GET /api/v1/patients/{id}/icu05-bundle`（与 sepsis-assessment 相同的嵌入患者授权）。
演示数据源或读取失败时 `available=false`，前端退回原“1 小时集束化”核对清单，不显示“未达标”。

## 取数（只读）
| 项 | 来源 | 说明 |
|---|---|---|
| T0 | DataCenter.VI_ICU_ZYYZ（pid=hisPid） | 入科后第一条医嘱 orderTime |
| 乳酸、P/F | SmartCare.bGATemp（mrn） | bedsides code=param_bg_Lac / param_bg_P/Fratio，valid=valid，fVal |
| GCS | SmartCare.score gcsScore total；无则 bedside param_score_gcs_obs（E?V?M?） | 窗口最低值 |
| MAP | SmartCare.bedside param_ibp_m + param_nibp_m | 混合取最低，标注通道 |
| 升压药 / 抗菌药 / 补液 | SmartCare.drugExe（pid） | startTime、drugList.name/liquidAmount、drugActionList |
| 药品字典 | SmartCare.configDrug classification | 血管活性（排除氯化钾、氯化钠、胺碘酮误分类）、抗菌类 |
| 血培养 / 送检 | DataCenter.VI_ICU_ZYYZ orderName | B2 用 reviewTime；I3 用 orderTime |

## 判定（三态：是 / 否 / —；— 不按否计）
- 门控窗口 T0-24h ~ T0+6h（不早于入院时间）。器官 S1 P/F<300、S2 GCS<13、S3 MAP<70、S4 升压药；感染 I1 诊断关键词、I2 抗菌药执行、I3 病原学送检。
- 休克（SHOCK_RULE=s3）：需要升压药，或 乳酸≥2 且 MAP<70。T0 前开始的升压药须有执行动作且未见 stop/pause/cancel；无动作记录 = 未知。
- 休克成立但无感染证据 → “疑似，待复核感染证据”。
- 1h / 3h / 6h 独立：A1 乳酸测定；B1 抗菌药执行（窗口内最晚）；B2 血培养（窗口内最晚）；B3 = B1、B2 均有且抗菌药晚于血培养；C1 MAP<70、C2 乳酸≥4 为触发项；C3 1h 有晶体/胶体执行，3h/6h 累计≥1500 ml。第三步仅在触发时要求 C3。6h 另需 1h 后复测乳酸，缺则结果为“—”。
- 床旁实时补充：时窗未到截止时间显示“剩 xx”，未完成项显示“待”，不判未达标。

## 可配置
`app.icu05.blood-culture-keywords`（默认 `血培养`；看板后来放宽为 `痰培养,尿培养,血培养`）
`app.icu05.culture-time-field`（默认 `reviewTime`，可改 `orderTime`）

## 尚未接入
三层人工覆盖、感染部位落库、NE 剂量（μg/kg/min）、体重 30 ml/kg、SOFA-2。
