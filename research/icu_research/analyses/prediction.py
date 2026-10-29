"""Direction 6: retrospective prediction of vasopressor start within the next 6 hours (hourly landmarks).

Temporal split by admission month; L2 logistic regression; compared with a MAP<65 rule.
Report-only. Not deployed: a silent run (prospective, no alerts) is required before any clinical use.
"""
import numpy as np

from . import result
from ..io import get
from ..stats import auprc, auroc, calibration, logistic, predict, standardize

HORIZON_H = 6
LANDMARKS = range(1, 19)
NAMES = ["时点", "MAP最近", "MAP近3h最低", "MAP近6h斜率", "心率最近", "心率近3h最高", "乳酸最近", "乳酸缺失", "年龄", "年龄缺失"]


def _last(vals, h, look):
    for i in range(h - 1, max(-1, h - 1 - look), -1):
        if vals[i] is not None:
            return vals[i]
    return None


def landmarks(r):
    tr = r.get("traj") or {}
    mp, hr, lac, vaso = tr.get("map") or [], tr.get("hr") or [], tr.get("lactate") or [], tr.get("vaso") or []
    if len(mp) < 24:
        return []
    first = get(r, "timing.vasoFirstMin")
    age = get(r, "ageYears")
    out = []
    for h in LANDMARKS:
        if any(v is None or v == 1 for v in vaso[:h]):
            break  # running or unknown pressor status before the landmark: not at risk
        if first is not None and first <= h * 60:
            break
        m_last = _last(mp, h, 3)
        if m_last is None:
            continue
        window = [v for v in mp[max(0, h - 3):h] if v is not None]
        pts = [(i, mp[i]) for i in range(max(0, h - 6), h) if mp[i] is not None]
        slope = float(np.polyfit([p[0] for p in pts], [p[1] for p in pts], 1)[0]) if len(pts) >= 3 else 0.0
        h_last = _last(hr, h, 3)
        h_max = max([v for v in hr[max(0, h - 3):h] if v is not None], default=None)
        l_last = _last(lac, h, 6)
        if l_last is None and get(r, "timing.lactateInitialMin") is not None and get(r, "timing.lactateInitialMin") <= h * 60:
            l_last = get(r, "timing.lactateInitial")
        y = 1 if first is not None and h * 60 < first <= (h + HORIZON_H) * 60 else 0
        out.append({"h": h, "y": y, "x": [h, m_last, min(window), slope, h_last, h_max, l_last, 1.0 if l_last is None else 0.0,
                                          age, 1.0 if age is None else 0.0]})
    return out


def run(cohort, seed=11, boots=200, train_frac=0.7):
    stays = [r for r in cohort if r.get("t0Available") and r.get("traj")]
    stays.sort(key=lambda r: (r.get("admitMonth") or "", r["stayKey"]))
    cut = int(len(stays) * train_frac)
    parts = {"train": stays[:cut], "test": stays[cut:]}
    lm = {k: [(i, l) for i, r in enumerate(v) for l in landmarks(r)] for k, v in parts.items()}
    # events = stays whose pressor onset falls inside a landmark horizon (not landmark rows, which repeat per stay)
    ev = {k: len({i for i, l in v if l["y"]}) for k, v in lm.items()}
    if ev["train"] < 30 or ev["test"] < 15:
        return result("insufficient" if stays else "no_data",
                      [f"训练集 {ev['train']} 例、测试集 {ev['test']} 例有升压药启动，少于 30/15，不训练模型。"], events=ev,
                      landmarks={k: len(v) for k, v in lm.items()})

    def matrix(rows, med=None):
        X = np.array([[np.nan if v is None else v for v in l["x"]] for _, l in rows], float)
        med = np.nanmedian(X, 0) if med is None else med
        return np.where(np.isnan(X), med, X), med
    Xtr, med = matrix(lm["train"])
    Xte, _ = matrix(lm["test"], med)
    ytr = np.array([l["y"] for _, l in lm["train"]])
    yte = np.array([l["y"] for _, l in lm["test"]])
    Ztr, mu, sd = standardize(Xtr)
    Zte, _, _ = standardize(Xte, mu, sd)
    fit = logistic(np.column_stack([np.ones(len(Ztr)), Ztr]), ytr, l2=1.0)
    ptr = predict(np.column_stack([np.ones(len(Ztr)), Ztr]), fit["beta"])
    pte = predict(np.column_stack([np.ones(len(Zte)), Zte]), fit["beta"])
    order = np.sort(ptr[ytr == 1])
    thr = float(order[int(0.2 * len(order))])  # ~80% sensitivity on the training set
    flag = pte >= thr
    rule = Xte[:, 1] < 65
    stay_ids = np.array([i for i, _ in lm["test"]])
    rng = np.random.default_rng(seed)
    uniq = np.unique(stay_ids)
    bs = []
    for _ in range(boots):
        pick = rng.choice(uniq, len(uniq), replace=True)
        idx = np.concatenate([np.where(stay_ids == s)[0] for s in pick])
        a = auroc(yte[idx], pte[idx])
        if a is not None:
            bs.append(a)
    auc = auroc(yte, pte)
    lead = []
    for s in uniq:
        idx = np.where(stay_ids == s)[0]
        if yte[idx].any():
            first = get(parts["test"][s], "timing.vasoFirstMin")
            fl = [lm["test"][i][1]["h"] for i in idx if flag[i]]
            if fl and first is not None:
                lead.append(first / 60.0 - min(fl))
    cal = calibration(yte, pte)
    res = {"events": ev, "landmarks": {k: len(v) for k, v in lm.items()}, "prevalenceTest": float(yte.mean()),
           "auroc": auc, "aurocCI": [float(np.percentile(bs, 2.5)), float(np.percentile(bs, 97.5))] if bs else None,
           "auprc": auprc(yte, pte), "calibration": cal, "threshold": thr,
           "sensitivity": float(flag[yte == 1].mean()), "ppv": float(yte[flag].mean()) if flag.any() else None,
           "alertsPerPatientDay": float(flag.sum() / len(uniq) / (len(flag) / len(uniq)) * 24) if len(uniq) else None,
           "leadHoursMedian": float(np.median(lead)) if lead else None,
           "ruleMAP65": {"auroc": auroc(yte, rule.astype(float)), "sensitivity": float(rule[yte == 1].mean()),
                         "ppv": float(yte[rule].mean()) if rule.any() else None},
           "coefficients": dict(zip(["截距"] + NAMES, np.round(fit["beta"], 3).tolist()))}
    lines = [f"按入科月份时间切分：训练 {len(parts['train'])} 例、测试 {len(parts['test'])} 例；测试集每小时时点 {len(yte)} 个（升压药启动 {ev['test']} 例），6h 内升压药启动比例 {yte.mean() * 100:.1f}%。",
             f"测试集 AUROC {auc:.3f}" + (f"（按病例自助 95%CI {res['aurocCI'][0]:.3f}–{res['aurocCI'][1]:.3f}）" if bs else "") +
             f"，AUPRC {res['auprc']:.3f}，校准斜率 {cal['slope']:.2f}。",
             f"按训练集 80% 敏感度设阈：测试敏感度 {res['sensitivity'] * 100:.0f}%，阳性预测值 {('—' if res['ppv'] is None else '%.0f%%' % (res['ppv'] * 100))}，"
             f"约每患者每日 {res['alertsPerPatientDay']:.1f} 次提示；提前量中位数 {('—' if res['leadHoursMedian'] is None else '%.1f h' % res['leadHoursMedian'])}。",
             f"对照规则 MAP<65：敏感度 {res['ruleMAP65']['sensitivity'] * 100:.0f}%，阳性预测值 {('—' if res['ruleMAP65']['ppv'] is None else '%.0f%%' % (res['ruleMAP65']['ppv'] * 100))}。",
             "仅为回顾性结果；上线前需静默运行（只计算不提醒）并前瞻评估。"]
    return result("ok", lines, **res)
