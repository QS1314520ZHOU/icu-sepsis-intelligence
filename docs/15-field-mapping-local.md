# 字段映射本地核对报告（fix/field-mapping-local）

> 数据对接核对，**不是临床验证**。SmartCare / DataCenter 全程只读；业务测试库使用独立 `icu_sepsis_*`。报告不含患者姓名、病案号、hisPid、身份证或完整 `_id`。

## 1. 字段核对表

| 字段 | 代码假设 | 实际情况（20 例近月入科） | 结论 | 依据 |
|------|----------|---------------------------|------|------|
| patient.mrn | string | 20/20 有，string | 正确 | find projection mrn |
| patient.hisPid | string | 20/20 有，string；ZYYZ.pid==hisPid | 正确 | VI_ICU_ZYYZ 1 条对照 |
| patient.icuAdmissionTime | Date | 20/20 Date | 正确 | 类型检查 |
| patient.admissionTime | Date | 20/20 Date | 正确 | 同上 |
| patient.icuDischargeTime | Date\|null | 在科者常为空；出科为 Date | 正确 | 1/20 有值 |
| patient.dischargedType | string | 本批在科全空；全库含 死亡/非医嘱离院/医嘱离院/转出 等 | 正确 | 全库 group |
| patient.gender | string | 20/20 string | 正确 | |
| patient.birthday | Date | 20/20 Date | 正确 | ResearchRow.age 可用 |
| patient.weight | 数值或字符串 | 4/20 有值且为 **string** | 已处理 | ResearchRow.num 解析字符串 |
| patient.clinical/admissionDiagnosis | string | 20/20 | 正确 | |
| ZYYZ.pid | =hisPid | 对照成立 | 正确 | |
| ZYYZ.orderTime/reviewTime | Date | Date | 正确 | |
| T0=入科后第一条医嘱 | 配置约定 | 20/20 可取到；首条多为护理/记账/转科，**均非血培养** | **待确认**（规则问题，未改阈值） | sample_first_orders |
| 血培养医嘱含“血培养” | 关键字 | 默认 keyword 存在；首条医嘱不含属预期 | 正确 | Icu05Rules.CULTURE_REGEX |
| bGATemp 按 mrn | mrn 关联 | 10 例中 3 例有关联文档；codes 含 param_bg_Lac | 部分可关联 | mrn_join_ok=3 |
| bGATemp valid | `valid="valid"` 字符串 | 样本为 `"valid"` | 正确 | |
| bGATemp 数值 | fVal 优先 | fVal 与 strVal 均有 | 正确 | Icu05SourceReader |
| bGATemp P/F | param_bg_P/Fratio | 全库存在 94267 条 | 正确 | |
| bedside.pid | string | string | 正确 | |
| bedside 编码 | ibp/nibp_m、HR、gcs_obs | 均存在 | 正确 | |
| bedside.strVal | 字符串数值 | string | 正确 | |
| bedside.valid | boolean true | boolean | 正确 | |
| score.scoreType | gcsScore/sofa/apacheII | 均存在（量级符合） | 正确 | |
| apacheII.calDead.score | 概率 0–1 | 样本 ≈0.4885 | **正确（0–1）** | ResearchRow 判 0–1 |
| drugExe.pid | string 或 ObjectId | string | 正确 | |
| drugExe.startTime | Date | 294/300 有值 | 正确 | |
| drugList[].liquidAmount | number | 有 | 正确 | |
| liquidUnit | 子项字段 | 子项常无 liquidUnit；**父级 liquidAmountUnit=ml** | **已修复** | 父级单位回退 |
| drugActionList.action | start/stop/pause… | 见 stop/start/minus/add/pause/quickAdd/**recovery** | stop/pause 已覆盖；recovery 未当停止 | STOP_ACTIONS |
| EXAM_ITEM PLT | unit | **10^9/L**，result string | 正确（Research 不强制换算 PLT） | |
| EXAM_ITEM TBIL | μmol/L | 实际 **micromol/L 与 umol/L** | **已修复** umol() | 4388+7530 条 |
| EXAM_ITEM 肌酐 | CREA/sCr/Cr | **主码 sCr unit umol/L**；CREA 0；Cr 0 | **已修复** 优先 sCr | count 查询 |
| configDrug.classification | 血管活性/抗菌 | 实际为 **血管活性 / 抗生素** | 正确（regex 含抗生素） | group by classification |

### dischargedType 与死亡（代码默认）
- 死亡集合默认：`死亡,非医嘱离院`（ResearchExportRunner 配置）。
- 全库还见：转出、医嘱离院、医嘱转院、医嘱转社区、出院、其他。**未把“医嘱离院”算作死亡**——需科室确认是否应排除。

### T0 医嘱（需科室确认）
近月 20 例 T0 首条医嘱样例类型：转科、防跌倒护理、记出入量、心电监测费、葡萄糖注射液等。  
“入 ICU 后第一条医嘱”可复现，但**临床锚点是否应改为“首条治疗性医嘱/感染相关医嘱”**属规则问题，本次未改。

---

## 2. 页面/API 比对（10 例策略）

| 方法 | 结果 |
|------|------|
| 20 例字段投影 + 类型/存在率 | 已执行 |
| 2 例 host-context + sepsis-assessment + clinical-data（720h）API | 已执行：IBP/NIBP 分通道、乳酸 unconfirmed、insufficient_data 与源陈旧一致 |
| 10 例宿主页面逐项（曲线时间点、SOFA 分项、ICU-05 卡片原始时间） | **未完整执行**（护士账号难以稳定打开全部菜单页；未做宿主原页同屏） |
| 不一致项 | 未发现“页面显示正常值但源库无数据”的伪成功；缺数显示 insufficient_data |

说明：近 7 天 bedside 窗口为空时页面/API 显示数据不全，与源 `time` 陈旧一致，属正确行为而非映射错误。

---

## 3. 本次修改

| Commit | 说明 |
|--------|------|
| `378e16e` | 合并 origin/main（ICU-05 + research v7）到本地主线，解 UI 冲突 |
| `d94f4b3` | TBIL `micromol/L` 单位识别；肌酐优先 `sCr`；drugExe 父级 `liquidAmountUnit` 回退；单测 |

测试：
- `ResearchRowUnitTest` 通过（micromol/umol/mg/dL/未知单位、字符串体重）
- `Icu05RulesTest` / `ResearchRowTest` 通过

未改 ICU-05/SOFA 临床阈值。

---

## 4. 待科室确认

1. **T0**：是否坚持“入科后第一条医嘱”，还是改为治疗性/抗感染医嘱？  
2. **死亡结局**：`非医嘱离院` 是否必须算死亡？`医嘱离院` 是否永远非死亡？  
3. **血培养 B2 时间**：`reviewTime` vs `orderTime`（当前默认 reviewTime）。  
4. **bGATemp 覆盖率**：仅约 3/10 近月患者有 mrn 关联乳酸；其余乳酸是否只在检验表？  
5. **停药动作**：库中 `recovery` 是否表示恢复输注（当前不计入 stop）。

---

## 5. 声明

本报告为**数据对接与字段映射核对**，用于发现编码/单位/关联问题；**不是**临床验证、诊断能力评价或疗效评价。
