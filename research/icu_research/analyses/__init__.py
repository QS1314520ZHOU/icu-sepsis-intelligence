"""Six research analyses. Each returns {"status", "summary", "data"}; status is ok / insufficient / no_data."""


def result(status, summary, **data):
    return {"status": status, "summary": summary, "data": data}


def pct(p):
    return "—" if p is None else f"{p * 100:.1f}%"


def ci(t):
    p, lo, hi = t
    return "—" if p is None else f"{p * 100:.1f}%（95%CI {lo * 100:.1f}–{hi * 100:.1f}%）"
