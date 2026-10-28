"""Synthetic export generator (schema icu-research-cohort/1 + icu-research-alert/1) with planted effects.

Used only to test the analysis code. Planted effects (scenario="signal"):
  * antibiotic >3h after T0 raises death odds (OR ~ 2.7 vs <=1h), adjusted for the same covariates
  * three trajectory phenotypes (stable / vasoplegic / tachycardic) with different mortality
  * MAP falls during the 4 hours before vasopressor start (predictable onset)
  * SOFA components and APACHE II track death
scenario="null" removes the abx and MAP-drop effects (death independent of abx; onset independent of MAP).
Nothing here is patient data.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

import numpy as np

from .io import ALERT_SCHEMA, COHORT_SCHEMA

REASONS = ["已处理：已开始抗菌药", "已知晓，继续观察", "误报：监测干扰", "误报：数据错误", "已处理：已补液"]


def _sig(x):
    return 1 / (1 + np.exp(-x))


def _item(value, reason=None, minute=None):
    return {"value": value, "reason": reason, "min": minute}


def make(n=1200, seed=1, scenario="signal"):
    rng = np.random.default_rng(seed)
    signal = scenario == "signal"
    cohort, alerts = [], []
    for i in range(n):
        key = hashlib.sha256(f"synthetic|{seed}|{i}".encode()).hexdigest()[:20]
        month = f"2025-{1 + i * 12 // n:02d}"
        age = float(np.clip(rng.normal(64, 14), 18, 95))
        t0 = rng.random() < 0.93
        u = rng.random()
        status = "septic_shock" if u < 0.7 else "suspected_review" if u < 0.8 else "not_met" if u < 0.95 else "unknown"
        row = {"schema": COHORT_SCHEMA, "stayKey": key, "synthetic": True, "admitMonth": month,
               "sex": "男" if rng.random() < 0.6 else "女", "ageYears": round(age), "weightKg": round(float(rng.normal(65, 12)), 1),
               "admissionType": "急诊" if rng.random() < 0.6 else "病房", "hospitalToIcuHours": round(float(rng.exponential(20)), 1),
               "t0Available": bool(t0), "icuToT0Min": int(rng.integers(0, 600)) if t0 else None}
        if not t0:
            row.update({"outcome": {"dischargedType": "转出", "death": False, "icuLosHours": 48.0, "icuOutMinFromT0": None},
                        "icu05": {"status": "no_t0", "poolEligible": None, "windows": {}}, "quality": {"failedSources": []}})
            cohort.append(row)
            continue
        pheno = int(rng.choice(3, p=[0.45, 0.30, 0.25]))  # 0 stable, 1 vasoplegic, 2 tachycardic
        base_map = [78, 60, 68][pheno] + rng.normal(0, 4)
        base_hr = [88, 105, 128][pheno] + rng.normal(0, 6)
        lac0 = float(np.clip(rng.lognormal(np.log([2.0, 5.5, 3.2][pheno]), 0.3), 0.5, 18))
        apache = float(np.clip(rng.normal([14, 24, 19][pheno], 5), 2, 50))
        # vasopressor onset (hours from T0); vasoplegic start early
        if pheno == 1:
            vstart = float(rng.uniform(0, 3))
        elif rng.random() < (0.45 if pheno == 2 else 0.3):
            vstart = float(rng.uniform(3, 22))
        else:
            vstart = None
        abx = float(rng.lognormal(np.log(80), 0.8)) if rng.random() < 0.95 else None
        maps, hrs, lacs, vaso = [], [], [], []
        for h in range(24):
            m = base_map + rng.normal(0, 3)
            if vstart is not None and signal and h < vstart:
                m -= max(0.0, 4 - (vstart - h)) * 4.5  # drift down in the 4h before onset
            if vstart is not None and h >= vstart:
                m = max(m, 66 + rng.normal(0, 3))
            if vstart is not None and not signal and h < vstart:
                m -= rng.normal(0, 2)
            maps.append(round(float(m), 0) if rng.random() > 0.04 else None)
            hrs.append(round(float(base_hr + rng.normal(0, 5)), 1) if rng.random() > 0.04 else None)
            lv = lac0 * (0.93 if pheno != 1 else 0.98) ** h
            lacs.append(round(float(lv), 1) if h in (2, 6, 12, 18) else None)
            vaso.append(1 if vstart is not None and h + 1 > vstart else 0)
        vmin = None if vstart is None else int(vstart * 60)
        abx_h = 0 if abx is None else abx / 60
        lp = (-2.0 + 0.03 * (age - 64) + 0.07 * (apache - 18) + 0.12 * (lac0 - 3) + [0, 0.6, 0.3][pheno]
              + ((0.5 if 60 < (abx or 0) <= 180 else 1.0 if (abx or 0) > 180 else 0) if signal else 0))
        death = bool(rng.random() < _sig(lp))
        los = float(np.clip(rng.lognormal(np.log(96), 0.6), 4, 900))
        out_min = int(los * 60)
        if death and rng.random() < 0.08:
            out_min = int(rng.uniform(30, 170))
        fluid3 = float(max(0, rng.normal(1500, 600)))
        incomplete = rng.random() < 0.1
        sev = lp
        w = {"pfMin": float(np.clip(rng.normal(300 - 25 * sev, 70), 50, 500)),
             "gcsMin": float(np.clip(round(rng.normal(13 - 0.8 * max(sev, 0), 2)), 3, 15)) if rng.random() > 0.1 else None,
             "mapMin": float(min(v for v in maps if v is not None)),
             "pltMin": float(np.clip(rng.normal(170 - 25 * sev, 60), 5, 500)),
             "tbilMaxUmol": float(np.clip(rng.lognormal(np.log(18 + 6 * max(sev, 0)), 0.5), 3, 400)),
             "creaMaxUmol": float(np.clip(rng.lognormal(np.log(95 + 25 * max(sev, 0)), 0.4), 30, 900)),
             "vasoAgents": (["ne"] + (["vasopressin"] if pheno == 1 and rng.random() < 0.4 else [])) if vstart is not None else []}
        win = {}
        for key_h, hours in (("1h", 1), ("3h", 3), ("6h", 6)):
            a1 = _item(True, None, -30)
            b1 = _item(rng.random() < 0.85, None, 20)
            b2 = _item(None if abx is None else abx <= hours * 60, None if abx is not None else "DRUG_MISSING", None if abx is None else int(abx))
            b3 = _item(b1["value"] and bool(b2["value"]) and rng.random() < 0.9)
            c3 = _item(None if incomplete else fluid3 >= 1500 if hours >= 3 else True, "FLUID_AMOUNT_INCOMPLETE" if incomplete else None)
            items = {"A1": a1, "B1": b1, "B2": b2, "B3": b3, "C3": c3}
            vals = [it["value"] for it in items.values()]
            state = "met" if all(v is True for v in vals) else "not_met" if any(v is False for v in vals) else "unknown"
            win[key_h] = {"state": state, "finish": {"met": True, "not_met": False}.get(state), "items": items}
        row.update({
            "outcome": {"dischargedType": "死亡" if death else "转出", "death": death, "icuLosHours": round(los, 1), "icuOutMinFromT0": out_min},
            "icu05": {"status": status, "poolEligible": status == "septic_shock", "isSepticShock": status == "septic_shock",
                      "hasInfection": status == "septic_shock", "hasOrgan": True, "gate": {}, "windows": win},
            "timing": {"abxFirstMin": None if abx is None else int(abx), "vasoFirstMin": vmin, "bloodCultureMin": int(rng.normal(20, 30)),
                       "fluidMl1h": round(fluid3 / 3), "fluidMl3h": None if incomplete else round(fluid3), "fluidMl6h": round(fluid3 * 1.5),
                       "fluidMl24h": round(fluid3 * 2.5), "fluidIncomplete1h": False, "fluidIncomplete3h": incomplete,
                       "fluidIncomplete6h": incomplete, "fluidIncomplete24h": incomplete,
                       "lactateInitial": round(lac0, 1), "lactateInitialMin": -20, "lactateMax6h": round(lac0, 1),
                       "lactateLast6h": lacs[6], "lactateLast6hMin": 360, "mapMin6h": min(v for v in maps[:6] if v is not None)},
            "traj": {"map": maps, "hr": hrs, "lactate": lacs, "vaso": vaso},
            "worst24": w,
            "scores": {"sofaMax24": None, "apache2First24": round(apache), "apache2CalDead": round(float(_sig(-2.6 + 0.09 * apache)), 3)},
            "quality": {"failedSources": ["DataCenter.VI_ICU_EXAM_ITEM"] if rng.random() < 0.02 else []}})
        cohort.append(row)
        if status in ("septic_shock", "suspected_review") and rng.random() < 0.8:
            amin = int(rng.uniform(-60, 90))
            hour = int(rng.integers(0, 24))
            revs = []
            if rng.random() < 0.85:
                night = not (8 <= hour < 20)
                revs.append({"action": "close", "reason": str(rng.choice(REASONS, p=[0.4, 0.3, 0.15, 0.05, 0.1])),
                             "min": int(rng.lognormal(np.log(25 if night else 12), 0.6)), "actorKey": f"a{int(rng.integers(0, 12))}"})
            alerts.append({"schema": ALERT_SCHEMA, "stayKey": key, "minFromT0": amin, "hourOfDay": hour, "weekday": int(rng.integers(1, 8)),
                           "severity": "high", "status": "closed" if revs else "open", "ackMin": revs[0]["min"] if revs else None,
                           "closeMin": revs[0]["min"] if revs else None, "reviews": revs})
    return cohort, alerts


def write(out_dir, n=1200, seed=1, scenario="signal"):
    d = Path(out_dir)
    d.mkdir(parents=True, exist_ok=True)
    cohort, alerts = make(n, seed, scenario)
    for name, rows in (("cohort.jsonl", cohort), ("alerts.jsonl", alerts)):
        with (d / name).open("w", encoding="utf-8") as f:
            for r in rows:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
    (d / "manifest.json").write_text(json.dumps({"schema": COHORT_SCHEMA, "synthetic": True, "scenario": scenario,
                                                  "stays": len(cohort), "alerts": len(alerts), "seed": seed}, ensure_ascii=False, indent=2), encoding="utf-8")
    return d
