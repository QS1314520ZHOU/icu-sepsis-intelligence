# v7 科研工具包：去标识导出 + 六个研究方向

> 状态：代码与合成数据验证完成；**未连接本院真实库运行**，字段映射需先核对；无任何临床验证。

## 1. 组成

| 部分 | 位置 | 作用 |
|---|---|---|
| 导出器（Java，离线） | `backend/.../research/ResearchExportRunner.java`、`ResearchRow.java`、`integration/ResearchSourceReader.java` | 只读 SmartCare / DataCenter / 本应用业务库，每个 ICU 住院一行 JSON |
| 分析包（Python） | `research/icu_research/` | 六个方向的统计分析，生成 `results.json` 与中文 `report.md` |
| 端到端校验 | `scripts/research-e2e.sh`、`research/e2e/` | 本机合成 Mongo → 导出 → 逐字段比对 → 报告 |

## 2. 导出（只读）

```bash
java -jar sepsis-intelligence-0.3.0.jar --spring.main.web-application-type=none \
  --app.source.mode=smartcare \
  --app.research.export.enabled=true \
  --app.research.export.from=2024-01-01 --app.research.export.to=2025-01-01 \
  --app.research.export.salt='<至少12位随机串，不入库、不外传>' \
  --app.research.export.out=/data/research-export
```

- 源库连接沿用 `SMARTCARE_MONGO_URI` / `DATACENTER_MONGO_URI`；业务库（预警处置）沿用 `MONGODB_URI`。导出不写任何库。
- 非 Web 模式不启动接口与登录（`SecurityConfig` 仅 Web 模式加载），跑完自动退出；退出码 0 成功、2 失败。
- 可选：`death-types`（默认 `死亡,非医嘱离院`，按 `patient.dischargedType` 判定 ICU 死亡）、`limit`、`after-hours`（默认 T0 后 30h）。

### 去标识

- 不导出姓名、病案号、hisPid、患者/源记录 id、绝对时间；`stayKey` = SHA-256(盐|住院标识) 前 20 位；操作人同样加盐哈希。
- 时间全部为相对 T0 的分钟；仅保留入科月份、预警发生的小时与星期（用于班次分析）。
- Python 读入时再次拦截 `name/mrn/hisPid/patientId/sourceRecordId/_id` 等字段。
- 盐值丢失后无法回连病例；需回连时由信息科按院内流程保管盐值。

### 每行内容（schema `icu-research-cohort/1`）

人口学（性别、年龄、体重、入科方式、院内到 ICU 小时数）；结局（离科类型、死亡、ICU 时长、T0 到离科分钟）；ICU-05 判定（门槛各项、1h/3h/6h 各项三态与原因码）；
治疗时机（首剂抗菌药、首个升压药、血培养、1/3/6/24h 补液量及“量不完整”标记、乳酸、MAP 最低）；0–23h 逐小时轨迹（MAP 最低、心率均值、乳酸、升压药 1/0/未知）；
T0−6h～T0+24h 最差值（P/F、GCS、MAP、PLT、TBIL、Cr、升压药种类）；系统评分（SOFA 最高、首个 APACHE II 与预计病死率）；读取失败的源。

单位：TBIL/Cr 仅接受 mg/dL（换算）与 μmol/L，其他单位置空不猜测。升压药开始后若无停止/后续操作记录，该小时记为“未知”，不记 0。

## 3. 分析

```bash
cd research && python3 -m icu_research report --export /data/research-export --out /data/research-report
```

| 方向 | 方法 | 最低样本 |
|---|---|---|
| 1 ICU-05 达标与可判定性 | 各窗口达标率、可判定率（Wilson CI）、各项无法判定原因、瓶颈项、月度趋势 | 有确诊病例即可 |
| 2 预警处置 | 处置率、误报占比、首次处置用时（日/夜班）、每 100 床日预警数、预警到抗菌药间隔 | 已处置 ≥30 条 |
| 3 SOFA vs SOFA-2 | 由最差值重算两版 SOFA；AUROC（DeLong CI 与配对比较）；APACHE II 预计病死率 SMR 与校准 | 死亡与存活各 ≥20 |
| 4 抗菌药时机/补液 | 3h 地标（排除 3h 内死亡/转出）+ 多因素 logistic（年龄、APACHE II、乳酸、MAP、3h 内升压药）；E 值 | 每变量 10 个结局 |
| 5 轨迹分型 | 8 个 0–24h 特征标准化后 k-means（k=2–5，轮廓系数选 k），自助法 ARI 稳定性 | ≥50 例 |
| 6 升压药启动预测 | 逐小时地标，预测 6h 内启动；按入科月份时间切分 70/30；L2 logistic；按病例自助 CI；对照 MAP<65 | 训练 ≥30、测试 ≥15 例启动 |

已知限制：未导出呼吸支持、升压药剂量、尿量/CRRT → SOFA 呼吸封顶 2 分、心血管按最低剂量档、肾脏只看肌酐（报告中标明受影响例数）。
检验报告时间（authTime）不等于采样时间。方向 4 为观察性关联，存在指征混杂；方向 6 只是回顾性结果。

### 方向 6 上线前：静默运行

后台按小时计算但不显示、不提醒 ≥3 个月；每周评估前瞻 AUROC、敏感度、PPV、每患者日提示数、提前量；
预设停止标准（AUROC 下限 <0.70、每患者日 >6 次、校准斜率超出 0.7–1.3）；经 ICU 主任与质控组评审后才考虑床旁试运行。

## 4. 首次真实运行前的核对清单

1. `patient.dischargedType` 的实际取值，确认哪些代表 ICU 内死亡，必要时改 `death-types`。
2. `VI_ICU_EXAM_ITEM` 中 PLT/TBIL/肌酐的 `itemCode` 与单位（当前 `PLT`、`TBIL`、`CREA/sCr/Cr`）。
3. `score` 中 `sofa`、`apacheII` 的 `scoreType` 与 `apacheII.calDead.score` 是否为 0–1 概率。
4. `bedside` 心率编码 `param_HR`；`drugExe.drugActionList` 停止动作的实际取值。
5. 先导出 1 个月、抽 20 例与病历人工比对，再扩大范围。

## 5. 验证（全部为合成数据）

- Python 单元测试 26 项：统计函数对照已知值；SOFA 分档；拒绝含标识字段；各方向在植入效应数据上找回效应（抗菌药 >3h OR≈2.7 的 CI 覆盖真值、零效应场景 CI 跨 1；3 个分型；预测在有信号时优于无信号和 MAP<65 规则），小样本返回“样本不足”。
- Java `ResearchRowTest` 8 项：无标识、加盐键、相对时间、补液不完整、升压药 1/0/未知、单位换算与未知单位置空。
- 端到端：本机 MongoDB 写入 500 例合成 SmartCare/DataCenter/业务记录 → 打包 jar 导出 → 与真值逐字段比对 0 差异（含 24h 轨迹）、标识未泄露、模拟预警被跳过 → 六个方向报告均完成。
  `PYTHONPATH=<pymongo> JAR=release/sepsis-intelligence-0.3.0.jar scripts/research-e2e.sh`
