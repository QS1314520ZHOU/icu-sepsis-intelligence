"""Run all six analyses on an export directory and write results.json + report.md (Chinese)."""
from __future__ import annotations

import json
import math
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

from . import __version__
from .analyses import alerts, audit, phenotypes, prediction, scores, timing
from .io import load

SECTIONS = [
    ("audit", "一、ICU-05 集束化达标与数据可判定性", lambda c, a: audit.run(c)),
    ("alerts", "二、预警处置与误报", lambda c, a: alerts.run(a, c)),
    ("scores", "三、SOFA 与 SOFA-2 预后区分度", lambda c, a: scores.run(c)),
    ("timing", "四、抗菌药时机/早期补液与死亡（观察性）", lambda c, a: timing.run(c)),
    ("phenotypes", "五、感染性休克 0–24h 轨迹分型", lambda c, a: phenotypes.run(c)),
    ("prediction", "六、6 小时内升压药启动预测（回顾性）", lambda c, a: prediction.run(c)),
]
STATUS = {"ok": "完成", "insufficient": "样本不足", "no_data": "无数据", "error": "出错"}

SILENT_RUN = """## 静默运行方案（方向六上线前必须完成）

1. 模型只在后台按小时计算，**不向床旁显示、不发提醒**，持续 ≥3 个月。
2. 每周导出一次，计算前瞻 AUROC、敏感度、阳性预测值、每患者日提示次数与提前量，与本报告回顾性结果对比。
3. 预设停止标准：前瞻 AUROC 下限 < 0.70，或每患者日提示 > 6 次，或校准斜率超出 0.7–1.3。
4. 由 ICU 主任与质控组评审后再决定是否进入床旁试运行；任何阶段均不替代临床判断。
"""


def _clean(o):
    if isinstance(o, dict):
        return {str(k): _clean(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)):
        return [_clean(v) for v in o]
    if isinstance(o, (np.integer,)):
        return int(o)
    if isinstance(o, (np.floating, float)):
        f = float(o)
        return None if math.isnan(f) or math.isinf(f) else f
    if isinstance(o, np.ndarray):
        return _clean(o.tolist())
    if isinstance(o, np.bool_):
        return bool(o)
    return o


def run(export_dir, out_dir=None):
    cohort, alert_rows, manifest = load(Path(export_dir))
    synthetic = bool(manifest.get("synthetic")) or any(r.get("synthetic") for r in cohort[:50])
    results = {"version": __version__, "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
               "synthetic": synthetic, "manifest": manifest, "stays": len(cohort), "alerts": len(alert_rows), "sections": {}}
    for key, _, fn in SECTIONS:
        try:
            res = fn(cohort, alert_rows)
        except Exception as ex:  # one failing analysis must not hide the others
            res = {"status": "error", "summary": [f"{type(ex).__name__}: {ex}"], "data": {}}
        if isinstance(res.get("summary"), str):
            res["summary"] = [res["summary"]]
        results["sections"][key] = res
    results = _clean(results)
    if out_dir:
        d = Path(out_dir)
        d.mkdir(parents=True, exist_ok=True)
        (d / "results.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
        (d / "report.md").write_text(markdown(results), encoding="utf-8")
    return results


def markdown(res):
    m = res.get("manifest") or {}
    lines = ["# ICU 脓毒症研究分析报告", ""]
    if res.get("synthetic"):
        lines += ["> **合成数据**：本报告由模拟数据生成，仅用于验证分析代码，结果不代表任何医院或患者。", ""]
    lines += [f"- 导出范围：{m.get('from', '—')} 至 {m.get('to', '—')}；病例 {res['stays']} 例；预警 {res['alerts']} 条",
              f"- 规则版本：{m.get('ruleVersion', '—')}；分析工具 {res['version']}；生成时间 {res['generatedAt']}",
              "- 数据已去标识（无姓名、病案号、源记录 id；时间为相对 T0 的分钟）。", "",
              "| 方向 | 状态 |", "|---|---|"]
    for key, title, _ in SECTIONS:
        lines.append(f"| {title} | {STATUS.get(res['sections'][key]['status'], res['sections'][key]['status'])} |")
    lines.append("")
    for key, title, _ in SECTIONS:
        s = res["sections"][key]
        lines += [f"## {title}", "", f"状态：{STATUS.get(s['status'], s['status'])}", ""]
        lines += [f"- {x}" for x in s.get("summary") or []]
        lines.append("")
    lines += [SILENT_RUN, "## 解读限制", "",
              "- 全部为回顾性、单中心、观察性分析；关联不等于因果，E 值仅提示未测混杂需多强才能解释结果。",
              "- 未导出呼吸支持、升压药剂量与尿量：SOFA 呼吸分项封顶 2 分、心血管按最低剂量档、肾脏仅用肌酐。",
              "- 字段映射需先用本院真实库核对（见 docs/14-research-toolkit.md 的核对清单），核对前结果不可引用。",
              "- 样本不足时各方向直接报告“样本不足”，不输出不稳定的估计。", ""]
    return "\n".join(lines)
