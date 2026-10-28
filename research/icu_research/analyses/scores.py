"""Direction 3: SOFA vs SOFA-2 (and APACHE II) discrimination and calibration for ICU death."""
import math
from collections import Counter

from . import result
from .. import sofa
from ..io import get
from ..stats import auroc_ci, calibration, delong_compare

MIN_EVENTS = 20


def run(cohort):
    rows = [r for r in cohort if r.get("t0Available") and get(r, "outcome.death") is not None]
    if not rows:
        return result("no_data", "无可用结局（dischargedType 缺失）")
    data, flags, missing = [], Counter(), Counter()
    for r in rows:
        w = r.get("worst24") or {}
        c1, f1 = sofa.classic(w)
        c2, f2 = sofa.sofa2(w)
        flags.update(f2)
        for k, v in c2.items():
            if v is None:
                missing[k] += 1
        data.append({"y": int(get(r, "outcome.death")), "sofa": sofa.total(c1), "sofa2": sofa.total(c2),
                     "sofa2_partial": sofa.total(c2, 4), "sofaSource": get(r, "scores.sofaMax24"),
                     "apache2": get(r, "scores.apache2First24"), "calDead": get(r, "scores.apache2CalDead")})
    out = {"n": len(rows), "deaths": sum(d["y"] for d in data), "componentMissing": {k: missing[k] / len(rows) for k in sofa.COMPONENTS},
           "flags": dict(flags), "auroc": {}, "compare": {}}
    lines = [f"有结局病例 {len(rows)} 例，死亡 {out['deaths']} 例。"]
    ok = False
    for name in ("sofa2", "sofa", "sofa2_partial", "sofaSource", "apache2"):
        sub = [d for d in data if d[name] is not None]
        ev = sum(d["y"] for d in sub)
        if ev < MIN_EVENTS or len(sub) - ev < MIN_EVENTS:
            out["auroc"][name] = {"n": len(sub), "events": ev, "status": "insufficient"}
            continue
        a = auroc_ci([d["y"] for d in sub], [d[name] for d in sub])
        out["auroc"][name] = {"n": len(sub), "events": ev, "auc": a}
        ok = True
        lines.append(f"{label(name)} AUROC {a[0]:.3f}（{a[1]:.3f}–{a[2]:.3f}），n={len(sub)}。")
    for other in ("sofa", "sofaSource", "apache2"):
        sub = [d for d in data if d["sofa2"] is not None and d[other] is not None]
        ev = sum(d["y"] for d in sub)
        if ev >= MIN_EVENTS and len(sub) - ev >= MIN_EVENTS:
            c = delong_compare([d["y"] for d in sub], [d["sofa2"] for d in sub], [d[other] for d in sub])
            if c:
                out["compare"]["sofa2_vs_" + other] = {"n": len(sub), "auc1": c[0], "auc2": c[1], "diff": c[2], "p": c[3]}
                lines.append(f"SOFA-2 对比 {label(other)}：差值 {c[2]:+.3f}，DeLong P={c[3]:.3g}（同一批 {len(sub)} 例）。")
    sub = [d for d in data if d["calDead"] is not None]
    ev = sum(d["y"] for d in sub)
    if ev >= MIN_EVENTS and len(sub) - ev >= MIN_EVENTS:
        cal = calibration([d["y"] for d in sub], [d["calDead"] for d in sub])
        exp = sum(d["calDead"] for d in sub)
        smr = ev / exp if exp else None
        cal["smr"] = smr
        cal["smrCI"] = (smr * math.exp(-1.96 / math.sqrt(ev)), smr * math.exp(1.96 / math.sqrt(ev))) if smr else None
        out["apacheCalibration"] = cal
        lines.append(f"APACHE II 预计病死率：SMR {smr:.2f}，校准斜率 {cal['slope']:.2f}，Brier {cal['brier']:.3f}。")
    if flags:
        lines.append("限制：" + "；".join(f"{FLAG[k]} {v} 例" for k, v in flags.items() if k in FLAG) + "。")
    return result("ok" if ok else "insufficient", lines, **out)


FLAG = {"resp_support_unknown": "未导出呼吸支持，呼吸分项封顶 2 分", "vaso_dose_unknown": "未导出升压药剂量，心血管按最低剂量档"}


def label(name):
    return {"sofa2": "SOFA-2", "sofa": "SOFA（重算）", "sofa2_partial": "SOFA-2（≥4 分项）", "sofaSource": "SOFA（系统评分表）", "apache2": "APACHE II"}[name]
