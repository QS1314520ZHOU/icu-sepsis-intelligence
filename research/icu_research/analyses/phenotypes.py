"""Direction 5: 0–24h trajectory phenotypes of septic shock (k-means on summary features, k by silhouette)."""
import numpy as np

from . import result
from ..io import get
from ..stats import ari, assign, kmeans, silhouette, standardize, wilson

FEATURES = ["MAP均值", "MAP最低", "MAP斜率", "心率均值", "心率最高", "乳酸最高", "乳酸下降比例", "升压药小时数"]
MIN_N = 50
MIN_HOURS = 6


def features(r):
    tr = r.get("traj") or {}
    mp = [(h, v) for h, v in enumerate(tr.get("map") or []) if v is not None]
    hr = [v for v in (tr.get("hr") or []) if v is not None]
    if len(mp) < MIN_HOURS or len(hr) < MIN_HOURS:
        return None
    hs, vs = np.array([h for h, _ in mp], float), np.array([v for _, v in mp], float)
    slope = float(np.polyfit(hs, vs, 1)[0]) if len(set(hs.tolist())) > 1 else 0.0
    lac = [v for v in (tr.get("lactate") or []) if v is not None]
    init, last = get(r, "timing.lactateInitial"), get(r, "timing.lactateLast6h")
    lmax = max(lac + ([init] if init is not None else [])) if (lac or init is not None) else None
    clear = (init - last) / init if init and last is not None and init > 0 else None
    vaso = tr.get("vaso") or []
    return [float(vs.mean()), float(vs.min()), slope, float(np.mean(hr)), float(np.max(hr)), lmax, clear,
            float(sum(1 for v in vaso if v == 1))]


def run(cohort, seed=7, k_range=range(2, 6), boots=20):
    rows, feats = [], []
    for r in cohort:
        if get(r, "icu05.status") != "septic_shock":
            continue
        f = features(r)
        if f is not None:
            rows.append(r)
            feats.append(f)
    if len(rows) < MIN_N:
        return result("insufficient" if rows else "no_data", [f"轨迹完整的感染性休克病例 {len(rows)} 例，少于 {MIN_N} 例，不做分型。"], n=len(rows))
    F = np.array([[np.nan if v is None else v for v in f] for f in feats], float)
    miss = np.isnan(F).mean(0)
    med = np.nanmedian(F, 0)
    F = np.where(np.isnan(F), med, F)
    X, mu, sd = standardize(F)
    best = None
    scores = {}
    for k in k_range:
        lab, cen, _ = kmeans(X, k, seed=seed)
        sizes = np.bincount(lab, minlength=k)
        if sizes.min() < 0.05 * len(X):
            scores[k] = None
            continue
        s = silhouette(X, lab, seed=seed)
        scores[k] = s
        if s is not None and (best is None or s > best[0]):
            best = (s, k, lab, cen)
    if best is None:
        return result("insufficient", ["各 k 下均有过小的类，不做分型。"], n=len(rows), silhouette=scores)
    s, k, lab, cen = best
    rng = np.random.default_rng(seed)
    stab = []
    for _ in range(boots):
        idx = rng.choice(len(X), len(X), replace=True)
        _, c2, _ = kmeans(X[idx], k, seed=int(rng.integers(1 << 30)), n_init=3)
        stab.append(ari(lab, assign(X, c2)))
    profiles = []
    for j in range(k):
        g = lab == j
        deaths = [get(r, "outcome.death") for r, m in zip(rows, g) if m and get(r, "outcome.death") is not None]
        profiles.append({"cluster": j, "n": int(g.sum()), "means": dict(zip(FEATURES, F[g].mean(0).round(2).tolist())),
                         "death": wilson(sum(int(d) for d in deaths), len(deaths))})
    profiles.sort(key=lambda p: -p["means"]["升压药小时数"])
    lines = [f"感染性休克 {len(rows)} 例按 0–24h 轨迹分为 {k} 型（轮廓系数 {s:.2f}；自助法稳定性 ARI 中位数 {np.median(stab):.2f}）。"]
    for i, p in enumerate(profiles, 1):
        m = p["means"]
        d = p["death"][0]
        lines.append(f"型 {i}（{p['n']} 例）：MAP 均值 {m['MAP均值']:.0f}，乳酸最高 {m['乳酸最高']:.1f}，升压药 {m['升压药小时数']:.1f} 小时，"
                     f"死亡率 {'—' if d is None else '%.1f%%' % (d * 100)}。")
    if np.median(stab) < 0.6:
        lines.append("稳定性 ARI < 0.6，分型不稳定，不宜用于临床解释。")
    return result("ok", lines, n=len(rows), k=k, silhouette=scores, stabilityARI=float(np.median(stab)), profiles=profiles,
                  featureMissing=dict(zip(FEATURES, miss.round(3).tolist())), labels=lab.tolist())
