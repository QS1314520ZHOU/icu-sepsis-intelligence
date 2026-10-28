"""Classic SOFA (Vincent 1996) and SOFA-2 (JAMA 2025;334(23):2090-2103) from exported worst values.

Thresholds mirror icu-quality-dashboard scoring/sofa_rules.py. Missing components stay None (never 0).
Known limits of the export, flagged per row:
  * respiratory support is not exported -> respiratory 3/4 capped at 2 (flag resp_support_unknown)
  * vasopressor dose is not exported -> minimal dose band (flag vaso_dose_unknown)
  * urine output / RRT not exported -> kidney uses creatinine only
"""
from __future__ import annotations

from typing import Dict, Optional, Tuple

COMPONENTS = ("resp", "coag", "liver", "cardio", "cns", "renal")


def _band(v: Optional[float], cuts, scores_hi_first: bool) -> Optional[int]:
    """cuts: list of (threshold, score). scores_hi_first=True means lower values are worse."""
    if v is None:
        return None
    for thr, sc in cuts:
        if (v < thr) if scores_hi_first else (v >= thr):
            return sc
    return 0


def classic(w: Dict) -> Tuple[Dict[str, Optional[int]], set]:
    flags = set()
    pf = w.get("pfMin")
    resp = None
    if pf is not None:
        resp = 4 if pf < 100 else 3 if pf < 200 else 2 if pf < 300 else 1 if pf < 400 else 0
        if resp > 2:
            resp, _ = 2, flags.add("resp_support_unknown")
    plt = w.get("pltMin")
    coag = None if plt is None else 4 if plt < 20 else 3 if plt < 50 else 2 if plt < 100 else 1 if plt < 150 else 0
    tb = w.get("tbilMaxUmol")
    liver = None if tb is None else 4 if tb >= 204 else 3 if tb >= 102 else 2 if tb >= 33 else 1 if tb >= 20 else 0
    agents = w.get("vasoAgents") or []
    mp = w.get("mapMin")
    if agents:
        cardio = 3 if any(a in ("ne", "epi") for a in agents) else 2
        flags.add("vaso_dose_unknown")
    else:
        cardio = None if mp is None else (1 if mp < 70 else 0)
    g = w.get("gcsMin")
    cns = None if g is None else 0 if g >= 15 else 1 if g >= 13 else 2 if g >= 10 else 3 if g >= 6 else 4
    cr = w.get("creaMaxUmol")
    renal = None if cr is None else 4 if cr >= 440 else 3 if cr >= 300 else 2 if cr >= 170 else 1 if cr >= 110 else 0
    return {"resp": resp, "coag": coag, "liver": liver, "cardio": cardio, "cns": cns, "renal": renal}, flags


def sofa2(w: Dict) -> Tuple[Dict[str, Optional[int]], set]:
    flags = set()
    pf = w.get("pfMin")
    resp = None
    if pf is not None:
        resp = 4 if pf < 75 else 3 if pf < 150 else 2 if pf < 225 else 1 if pf < 300 else 0
        if resp > 2:
            resp, _ = 2, flags.add("resp_support_unknown")
    plt = w.get("pltMin")
    coag = None if plt is None else 4 if plt < 50 else 3 if plt < 80 else 2 if plt < 100 else 1 if plt < 150 else 0
    tb = w.get("tbilMaxUmol")
    tbm = None if tb is None else tb / 17.104
    liver = None if tbm is None else 4 if tbm >= 12 else 3 if tbm >= 6 else 2 if tbm >= 3 else 1 if tbm >= 1.2 else 0
    agents = set(w.get("vasoAgents") or [])
    mp = w.get("mapMin")
    if agents:
        flags.add("vaso_dose_unknown")
        # NE/epi combined with another vasopressor/inotrope scores at least 3 even at low dose.
        cardio = 3 if (agents & {"ne", "epi"}) and (agents - {"ne", "epi"}) else 2
    else:
        cardio = None if mp is None else (1 if mp < 70 else 0)
    g = w.get("gcsMin")
    cns = None if g is None else 0 if g >= 15 else 1 if g >= 13 else 2 if g >= 9 else 3 if g >= 6 else 4
    cr = w.get("creaMaxUmol")
    crm = None if cr is None else cr / 88.4
    renal = None if crm is None else 3 if crm >= 3.5 else 2 if crm >= 2.0 else 1 if crm >= 1.2 else 0
    return {"resp": resp, "coag": coag, "liver": liver, "cardio": cardio, "cns": cns, "renal": renal}, flags


def total(components: Dict[str, Optional[int]], min_components: int = 6) -> Optional[int]:
    vals = [v for v in components.values() if v is not None]
    return sum(vals) if len(vals) >= min_components else None
