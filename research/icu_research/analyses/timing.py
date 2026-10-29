"""Direction 4: antibiotic timing / early fluids vs death, with a 3h landmark against immortal-time bias.

Observational association only (confounding by indication remains); E-values quantify robustness.
"""
import numpy as np

from . import result
from ..io import get
from ..stats import Z, e_value, logistic, odds_ratio, wilson

LANDMARK_MIN = 180
EPV = 10


def _covariates(r):
    age = get(r, "ageYears")
    sev = get(r, "scores.apache2First24")
    lac = get(r, "timing.lactateInitial")
    mp = get(r, "timing.mapMin6h")
    vaso = get(r, "timing.vasoFirstMin")
    if None in (age, sev, lac, mp):
        return None
    return [age / 10.0, sev / 5.0, min(lac, 15.0), mp / 10.0, 1.0 if vaso is not None and vaso <= LANDMARK_MIN else 0.0]


COV_NAMES = ["年龄(每10岁)", "APACHE II(每5分)", "初始乳酸", "MAP最低(每10)", "3h内升压药"]


def cohort_at_landmark(cohort):
    out = []
    for r in cohort:
        if get(r, "icu05.status") != "septic_shock" or get(r, "outcome.death") is None:
            continue
        leave = get(r, "outcome.icuOutMinFromT0")
        if leave is not None and leave <= LANDMARK_MIN:
            continue  # died or left before the landmark: excluded to avoid immortal-time bias
        out.append(r)
    return out


def _fit(rows, exposure_fn, names):
    X, y = [], []
    for r in rows:
        e = exposure_fn(r)
        c = _covariates(r)
        if e is None or c is None:
            continue
        X.append([1.0] + e + c)
        y.append(int(get(r, "outcome.death")))
    k = len(names) + len(COV_NAMES)
    ev = min(sum(y), len(y) - sum(y))
    if len(y) == 0 or ev < EPV * k:
        return {"status": "insufficient", "n": len(y), "events": sum(y), "needEvents": EPV * k}
    fit = logistic(np.array(X), np.array(y))
    if not fit["converged"]:
        return {"status": "insufficient", "n": len(y), "events": sum(y), "reason": "模型未收敛/分离"}
    terms = {}
    for i, nm in enumerate(names + COV_NAMES, start=1):
        o = odds_ratio(fit["beta"][i], fit["se"][i])
        terms[nm] = {"or": o[0], "lo": o[1], "hi": o[2], "eValue": e_value(o[0], sum(y) / len(y) > 0.15)}
    return {"status": "ok", "n": len(y), "events": sum(y), "terms": terms}


def abx_category(r):
    t = get(r, "timing.abxFirstMin")
    if t is None or t > 24 * 60:
        return "未用或>24h"
    return "≤1h" if t <= 60 else "1–3h" if t <= 180 else ">3h"


def run(cohort):
    rows = cohort_at_landmark(cohort)
    if not rows:
        return result("no_data", "无符合条件的感染性休克病例（需结局与 T0）")
    crude = {}
    for cat in ("≤1h", "1–3h", ">3h", "未用或>24h"):
        g = [r for r in rows if abx_category(r) == cat]
        crude[cat] = {"n": len(g), "death": wilson(sum(int(get(r, "outcome.death")) for r in g), len(g))}

    def abx_dummies(r):
        c = abx_category(r)
        return None if c == "未用或>24h" else [1.0 if c == "1–3h" else 0.0, 1.0 if c == ">3h" else 0.0]

    def abx_hours(r):
        t = get(r, "timing.abxFirstMin")
        return None if t is None or t > 24 * 60 else [max(t, 0) / 60.0]

    def fluid(r):
        trig = (get(r, "timing.mapMin6h") is not None and get(r, "timing.mapMin6h") < 70) or (get(r, "timing.lactateMax6h") or 0) >= 4
        ml = get(r, "timing.fluidMl3h")
        if not trig or get(r, "timing.fluidIncomplete3h") or ml is None:
            return None
        return [1.0 if ml >= 1500 else 0.0]
    models = {"abxCategory": _fit(rows, abx_dummies, ["抗菌药 1–3h（对比≤1h）", "抗菌药 >3h（对比≤1h）"]),
              "abxPerHour": _fit(rows, abx_hours, ["抗菌药每延迟 1 小时"]),
              "fluid3h": _fit(rows, fluid, ["3h 补液≥1500ml（触发病例）"])}
    lines = [f"3h 存活且仍在 ICU 的感染性休克病例 {len(rows)} 例（地标法，排除 3h 内死亡或转出）。",
             "粗死亡率：" + "；".join("%s %d 例 %s" % (k, v["n"], "—" if v["death"][0] is None else "%.1f%%" % (v["death"][0] * 100)) for k, v in crude.items()) + "。"]
    for key, m in models.items():
        if m["status"] != "ok":
            lines.append(f"{TITLE[key]}：样本不足（{m.get('events', 0)} 个结局，需 ≥{m.get('needEvents', '—')}），不估计。")
            continue
        for nm, t in m["terms"].items():
            if nm in COV_NAMES:
                continue
            lines.append(f"{nm}：调整 OR {t['or']:.2f}（{t['lo']:.2f}–{t['hi']:.2f}），E 值 {t['eValue']:.2f}。")
    lines.append("调整变量：年龄、APACHE II、初始乳酸、MAP 最低值、3h 内升压药；观察性关联，不能推断因果。")
    status = "ok" if any(m["status"] == "ok" for m in models.values()) else "insufficient"
    return result(status, lines, landmarkCohort=len(rows), crude=crude, models=models, z=Z)


TITLE = {"abxCategory": "抗菌药时机分组", "abxPerHour": "抗菌药每小时延迟", "fluid3h": "3h 补液≥1500ml"}
