# v11：自动打包 · AI 结果缓存 · 预警巡检优化 · 护士视图

## 1. GitHub Actions 自动打包（`.github/workflows/build.yml`）
- 每次推送 / PR：`npm ci && npm test`（前端）→ `mvn package`（后端测试 + 打包，前端自动内置）。
- 产物：`sepsis-intelligence-0.3.0.jar`、`SHA256SUMS.txt`、`BUILD-INFO.txt`（提交号、构建时间）。
- main 分支：更新滚动预发布 **latest**（Releases 页固定地址）；打 `v*` 标签生成正式 Release。
- 部署：下载 jar + SHA256SUMS.txt → 上传服务器 → `sha256sum -c SHA256SUMS.txt` 校验后再启动。

## 2. AI 结果缓存（`app.ai.cache-minutes`，默认 30）
- 同一病人、同一任务、输入事实与知识片段都未变（忽略“N 分钟前”这类相对时间）时，在有效期内直接复用上次**成功**结果，不再调用网关。
- 界面显示“数据未变化，复用 HH:mm 的结果 · 重新生成”；点“重新生成”或预警页“重新生成”跳过缓存。
- 设为 0 关闭。缓存读取失败时照常生成。

## 3. 自动预警巡检（`app.alerts.*`）
| 配置 | 默认 | 作用 |
|---|---|---|
| `in-ward-only` | true | 只巡检 SmartCare 仍在科（`status=admitted`）的患者；读取失败时退回全量（宁可多扫） |
| `max-patients` | 200 | 单轮最多评估人数 |
| `icu05-scan-days` | 7 | ICU-05 门控只算入科 ≤7 天的患者；床旁筛查不受限 |
| `skip-unchanged` | true | 床旁数据指纹（各指标最新时间+数值、升压药、感染线索、缺项）未变则跳过本轮 ICU-05 与写库 |
| `full-eval-minutes` | 60 | 即使未变也至少每 60 分钟完整评估一次（抑制期到期、时窗推进、执行单变化） |
| `scan-ms` | 600000 | 巡检间隔不变（10 分钟） |

巡检耗时 >30s 或产生新预警时，日志输出 `screening sweep: candidates=… inWard=… evaluated=… unchanged=… took=…ms`。

## 4. 护士视图
- 概览只保留：结论标题、关键指标、Bundle 床旁待办（倒计时 + 已做/待做）、预警与任务入口、趋势（折叠）。
- 隐藏：确认/排除、SOFA/器官功能、AI 辅助、数据口径说明、ICU-05 质控矩阵、科室质控标签。
- 标签栏右侧“医生视图 / 护士视图”切换；按宿主登录账号分别记住。未手动选择时，宿主账号含“护士/护理”自动用护士视图。
- 仅改变显示内容，**不改变权限**（权限映射见优化清单第 1 项，待做）。

截图：`docs/screenshots/76-v11-nurse-view.png`
