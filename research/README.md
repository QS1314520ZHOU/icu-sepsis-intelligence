# icu_research — 去标识科研分析

只读取后端导出的 JSONL（`cohort.jsonl` / `alerts.jsonl` / `manifest.json`），不连接任何医院数据库。依赖仅 numpy。

```bash
cd research
python3 -m icu_research report --export ../research-export --out ../research-report   # 生成 results.json + report.md
python3 -m icu_research synth --out /tmp/synth                                         # 合成数据（仅测试）
python3 -m unittest discover -s tests                                                  # 单元测试
```

六个方向：`analyses/audit.py`（ICU-05 达标与可判定性）、`alerts.py`（预警处置/误报）、`scores.py`（SOFA vs SOFA-2）、
`timing.py`（抗菌药时机/补液，3h 地标 + E 值）、`phenotypes.py`（0–24h 轨迹分型）、`prediction.py`（6h 升压药启动预测，仅回顾性）。

样本不足时直接返回“样本不足”。端到端校验见 `../scripts/research-e2e.sh` 与 `docs/14-research-toolkit.md`。
