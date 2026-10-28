"""Direction 1: ICU-05 compliance and how often items cannot be determined."""
from collections import Counter, defaultdict

from . import ci, result
from ..io import get
from ..stats import wilson

ITEMS = ("A1", "A2", "B1", "B2", "B3", "C1", "C2", "C3")
APPLICABLE = {"septic_shock", "suspected_review"}


def run(cohort):
    n = len(cohort)
    if n == 0:
        return result("no_data", "无导出病例")
    with_t0 = [r for r in cohort if r.get("t0Available")]
    status = Counter(get(r, "icu05.status", "unknown") for r in cohort)
    pool = Counter(str(get(r, "icu05.poolEligible")) for r in with_t0)
    app = [r for r in with_t0 if get(r, "icu05.status") in APPLICABLE]
    confirmed = [r for r in app if get(r, "icu05.status") == "septic_shock"]
    windows = {}
    for key in ("1h", "3h", "6h"):
        states = Counter(get(r, f"icu05.windows.{key}.state", "unknown") for r in confirmed)
        met, nm = states.get("met", 0), states.get("not_met", 0)
        det = met + nm
        items = {}
        for code in ITEMS:
            vals = Counter()
            reasons = Counter()
            for r in confirmed:
                it = get(r, f"icu05.windows.{key}.items.{code}")
                if not it:
                    continue
                v = it.get("value")
                vals["true" if v is True else "false" if v is False else "null"] += 1
                if it.get("reason"):
                    reasons[it["reason"]] += 1
            if sum(vals.values()):
                tot = sum(vals.values())
                items[code] = {"true": vals["true"], "false": vals["false"], "null": vals["null"],
                               "nullRate": vals["null"] / tot, "reasons": dict(reasons.most_common())}
        bottleneck = Counter()
        for r in confirmed:
            if get(r, f"icu05.windows.{key}.state") == "not_met":
                for code in ("A1", "B3", "C3"):
                    it = get(r, f"icu05.windows.{key}.items.{code}") or {}
                    if it.get("value") is False:
                        bottleneck[code] += 1
        windows[key] = {"states": dict(states), "compliance": wilson(met, det), "determinableRate": wilson(det, len(confirmed)),
                        "items": items, "bottleneck": dict(bottleneck)}
    months = defaultdict(lambda: [0, 0])
    for r in confirmed:
        st = get(r, "icu05.windows.3h.state")
        if st in ("met", "not_met"):
            m = months[r.get("admitMonth") or "unknown"]
            m[1] += 1
            m[0] += st == "met"
    trend = {k: {"met": v[0], "n": v[1], "rate": wilson(v[0], v[1])[0]} for k, v in sorted(months.items())}
    failures = Counter(s for r in cohort for s in get(r, "quality.failedSources", []))
    lines = [f"导出 {n} 例，取得 T0 {len(with_t0)} 例；判定为感染性休克 {len(confirmed)} 例，疑似待复核 {len(app) - len(confirmed)} 例。"]
    for key, w in windows.items():
        lines.append(f"{key}：可判定 {ci(w['determinableRate'])}；可判定病例中达标 {ci(w['compliance'])}。")
    worst = sorted(((c, d["nullRate"]) for c, d in windows["3h"]["items"].items()), key=lambda x: -x[1])[:3]
    if worst:
        lines.append("3h 无法判定比例最高的项：" + "、".join(f"{c} {v * 100:.0f}%" for c, v in worst) + "。")
    return result("ok" if confirmed else "insufficient", lines, stays=n, staysWithT0=len(with_t0), statusCounts=dict(status),
                  pool=dict(pool), confirmed=len(confirmed), suspected=len(app) - len(confirmed), windows=windows,
                  monthly3h=trend, sourceFailures=dict(failures))
