"""Small, dependency-free statistics used by the analyses (numpy only)."""
from __future__ import annotations

import math
from typing import Optional, Sequence

import numpy as np

Z = 1.959963984540054


def norm_cdf(x: float) -> float:
    return 0.5 * (1.0 + math.erf(x / math.sqrt(2.0)))


def wilson(k: int, n: int, z: float = Z):
    """Wilson score interval for a proportion. Returns (p, lo, hi); None values when n == 0."""
    if n <= 0:
        return None, None, None
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return p, max(0.0, c - h), min(1.0, c + h)


def median_iqr(x: Sequence[float]):
    a = np.asarray([v for v in x if v is not None and not (isinstance(v, float) and math.isnan(v))], dtype=float)
    if a.size == 0:
        return None, None, None
    q1, med, q3 = np.percentile(a, [25, 50, 75])
    return float(med), float(q1), float(q3)


def _midrank(x: np.ndarray) -> np.ndarray:
    order = np.argsort(x, kind="mergesort")
    xs = x[order]
    n = len(x)
    t = np.zeros(n)
    i = 0
    while i < n:
        j = i
        while j < n and xs[j] == xs[i]:
            j += 1
        t[i:j] = 0.5 * (i + j - 1) + 1
        i = j
    out = np.empty(n)
    out[order] = t
    return out


def auroc(y: Sequence[int], s: Sequence[float]) -> Optional[float]:
    y = np.asarray(y, dtype=int)
    s = np.asarray(s, dtype=float)
    m, n = int(y.sum()), int((1 - y).sum())
    if m == 0 or n == 0:
        return None
    r = _midrank(s)
    return float((r[y == 1].sum() - m * (m + 1) / 2) / (m * n))


def delong(y: Sequence[int], *scores: Sequence[float]):
    """Fast DeLong (Sun & Xu 2014). Returns (aucs, covariance matrix)."""
    y = np.asarray(y, dtype=int)
    pos, neg = y == 1, y == 0
    m, n = int(pos.sum()), int(neg.sum())
    if m < 2 or n < 2:
        return None, None
    k = len(scores)
    aucs, v10, v01 = np.zeros(k), np.zeros((k, m)), np.zeros((k, n))
    for i, s in enumerate(scores):
        s = np.asarray(s, dtype=float)
        x, z = s[pos], s[neg]
        tx, tz, tall = _midrank(x), _midrank(z), _midrank(np.concatenate([x, z]))
        aucs[i] = (tall[:m].sum() - m * (m + 1) / 2) / (m * n)
        v10[i] = (tall[:m] - tx) / n
        v01[i] = 1.0 - (tall[m:] - tz) / m
    cov = np.atleast_2d(np.cov(v10)) / m + np.atleast_2d(np.cov(v01)) / n
    return aucs, cov


def auroc_ci(y, s):
    aucs, cov = delong(y, s)
    if aucs is None:
        return None, None, None
    a, se = float(aucs[0]), math.sqrt(max(float(cov[0, 0]), 0.0))
    return a, max(0.0, a - Z * se), min(1.0, a + Z * se)


def delong_compare(y, s1, s2):
    """Two correlated AUROCs on the same patients. Returns (auc1, auc2, diff, p)."""
    aucs, cov = delong(y, s1, s2)
    if aucs is None:
        return None
    var = cov[0, 0] + cov[1, 1] - 2 * cov[0, 1]
    d = float(aucs[0] - aucs[1])
    p = 1.0 if var <= 0 else 2 * (1 - norm_cdf(abs(d) / math.sqrt(var)))
    return float(aucs[0]), float(aucs[1]), d, p


def auprc(y, s) -> Optional[float]:
    """Average precision (step-wise, ties broken conservatively)."""
    y = np.asarray(y, dtype=int)
    s = np.asarray(s, dtype=float)
    if y.sum() == 0:
        return None
    order = np.lexsort((y, -s))  # ties: negatives first
    yy = y[order]
    tp = np.cumsum(yy)
    prec = tp / np.arange(1, len(yy) + 1)
    return float((prec * yy).sum() / yy.sum())


def brier(y, p) -> float:
    y, p = np.asarray(y, float), np.asarray(p, float)
    return float(np.mean((p - y) ** 2))


def logistic(X, y, l2: float = 0.0, offset=None, max_iter: int = 100, tol: float = 1e-8):
    """IRLS logistic regression. X must include an intercept column if wanted.

    Returns dict(beta, se, converged). L2 penalty is not applied to column 0 (intercept).
    Standard errors come from the (penalised) observed information.
    """
    X = np.asarray(X, float)
    y = np.asarray(y, float)
    n, k = X.shape
    off = np.zeros(n) if offset is None else np.asarray(offset, float)
    beta = np.zeros(k)
    pen = np.full(k, l2)
    pen[0] = 0.0
    converged = False
    for _ in range(max_iter):
        eta = np.clip(X @ beta + off, -30, 30)
        p = 1 / (1 + np.exp(-eta))
        w = np.clip(p * (1 - p), 1e-10, None)
        grad = X.T @ (y - p) - pen * beta
        H = (X * w[:, None]).T @ X + np.diag(pen)
        try:
            step = np.linalg.solve(H, grad)
        except np.linalg.LinAlgError:
            step = np.linalg.lstsq(H, grad, rcond=None)[0]
        beta = beta + step
        if np.max(np.abs(step)) < tol:
            converged = True
            break
    eta = np.clip(X @ beta + off, -30, 30)
    p = 1 / (1 + np.exp(-eta))
    H = (X * np.clip(p * (1 - p), 1e-10, None)[:, None]).T @ X + np.diag(pen)
    try:
        se = np.sqrt(np.clip(np.diag(np.linalg.inv(H)), 0, None))
    except np.linalg.LinAlgError:
        se = np.full(k, np.nan)
    separated = bool(np.any(np.abs(beta) > 15))
    return {"beta": beta, "se": se, "converged": converged and not separated}


def predict(X, beta, offset=None):
    eta = np.asarray(X, float) @ beta + (0 if offset is None else np.asarray(offset, float))
    return 1 / (1 + np.exp(-np.clip(eta, -30, 30)))


def calibration(y, p):
    """Calibration-in-the-large (intercept with logit offset) and calibration slope."""
    y = np.asarray(y, float)
    p = np.clip(np.asarray(p, float), 1e-6, 1 - 1e-6)
    lp = np.log(p / (1 - p))
    one = np.ones((len(y), 1))
    ci = logistic(one, y, offset=lp)
    sl = logistic(np.column_stack([one, lp]), y)
    return {"intercept": float(ci["beta"][0]), "slope": float(sl["beta"][1]), "brier": brier(y, p),
            "observed": float(y.mean()), "expected": float(p.mean())}


def odds_ratio(beta: float, se: float):
    return math.exp(beta), math.exp(beta - Z * se), math.exp(beta + Z * se)


def e_value(or_: float, common_outcome: bool = True) -> Optional[float]:
    """E-value (VanderWeele & Ding 2017). Common outcomes use RR ≈ sqrt(OR)."""
    if or_ is None or or_ <= 0:
        return None
    rr = math.sqrt(or_) if common_outcome else or_
    if rr < 1:
        rr = 1 / rr
    return rr + math.sqrt(rr * (rr - 1))


def standardize(X, mean=None, sd=None):
    X = np.asarray(X, float)
    mean = X.mean(axis=0) if mean is None else mean
    sd = X.std(axis=0) if sd is None else sd
    sd = np.where(sd == 0, 1.0, sd)
    return (X - mean) / sd, mean, sd


def kmeans(X, k: int, seed: int = 0, n_init: int = 10, max_iter: int = 300):
    """k-means++ with several restarts. Returns (labels, centroids, inertia)."""
    X = np.asarray(X, float)
    rng = np.random.default_rng(seed)
    best = None
    for _ in range(n_init):
        c = [X[rng.integers(len(X))]]
        for _j in range(1, k):
            d2 = np.min(((X[:, None, :] - np.array(c)[None]) ** 2).sum(-1), axis=1)
            prob = d2 / d2.sum() if d2.sum() > 0 else np.full(len(X), 1 / len(X))
            c.append(X[rng.choice(len(X), p=prob)])
        c = np.array(c)
        for _it in range(max_iter):
            lab = np.argmin(((X[:, None, :] - c[None]) ** 2).sum(-1), axis=1)
            nc = np.array([X[lab == j].mean(0) if np.any(lab == j) else c[j] for j in range(k)])
            if np.allclose(nc, c):
                break
            c = nc
        inertia = float(((X - c[lab]) ** 2).sum())
        if best is None or inertia < best[2]:
            best = (lab, c, inertia)
    return best


def assign(X, centroids):
    return np.argmin(((np.asarray(X, float)[:, None, :] - centroids[None]) ** 2).sum(-1), axis=1)


def silhouette(X, labels, max_n: int = 2000, seed: int = 0) -> Optional[float]:
    X = np.asarray(X, float)
    labels = np.asarray(labels)
    if len(set(labels.tolist())) < 2:
        return None
    if len(X) > max_n:
        idx = np.random.default_rng(seed).choice(len(X), max_n, replace=False)
        X, labels = X[idx], labels[idx]
    D = np.sqrt(((X[:, None, :] - X[None]) ** 2).sum(-1))
    s = np.zeros(len(X))
    for i in range(len(X)):
        same = labels == labels[i]
        same[i] = False
        a = D[i, same].mean() if same.any() else 0.0
        b = min(D[i, labels == j].mean() for j in set(labels.tolist()) if j != labels[i])
        s[i] = 0.0 if max(a, b) == 0 else (b - a) / max(a, b)
    return float(s.mean())


def ari(a, b) -> float:
    """Adjusted Rand index."""
    a, b = np.asarray(a), np.asarray(b)
    ua, ia = np.unique(a, return_inverse=True)
    ub, ib = np.unique(b, return_inverse=True)
    t = np.zeros((len(ua), len(ub)))
    np.add.at(t, (ia, ib), 1)
    comb = lambda x: x * (x - 1) / 2
    sij, sa, sb = comb(t).sum(), comb(t.sum(1)).sum(), comb(t.sum(0)).sum()
    tot = comb(len(a))
    exp = sa * sb / tot if tot else 0
    mx = (sa + sb) / 2
    return 1.0 if mx == exp else float((sij - exp) / (mx - exp))
