"""Compare the Java export against truth.json from seed_synthetic_mongo.py, then run the report."""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from icu_research import report  # noqa: E402
from icu_research.io import get, load  # noqa: E402


def close(a, b, tol):
    if a is None or b is None:
        return a is None and b is None
    return abs(float(a) - float(b)) <= tol


def main(export_dir, truth_path, out_dir):
    truth = json.loads(Path(truth_path).read_text(encoding="utf-8"))
    raw = "".join(p.read_text(encoding="utf-8") for p in Path(export_dir).glob("*.jsonl"))
    errors = []
    for ident in truth["identifiers"]:
        if ident in raw:
            errors.append(f"identifier leaked: {ident}")
    if "测试患者" in raw:
        errors.append("patient name leaked")
    cohort, alerts, manifest = load(Path(export_dir))  # also enforces schema + forbidden keys
    by_key = {r["stayKey"]: r for r in cohort}
    stays = truth["stays"]
    if set(by_key) != set(stays):
        errors.append(f"stay keys differ: export {len(by_key)} vs truth {len(stays)}; missing {len(set(stays) - set(by_key))}")
    checked = 0
    for key, exp in stays.items():
        r = by_key.get(key)
        if r is None:
            continue
        checked += 1

        def chk(name, got, want, tol=0.0):
            if not close(got, want, tol) if isinstance(want, (int, float)) or want is None else got != want:
                errors.append(f"{key} {name}: got {got!r} want {want!r}")
        chk("ageYears", r.get("ageYears"), exp["ageYears"])
        chk("sex", r.get("sex"), exp["sex"])
        chk("t0Available", r.get("t0Available"), exp["t0Available"])
        chk("death", get(r, "outcome.death"), exp["death"])
        if not exp["t0Available"]:
            continue
        chk("icuOutMinFromT0", get(r, "outcome.icuOutMinFromT0"), exp["icuOutMinFromT0"], 1)
        for f in ("abxFirstMin", "vasoFirstMin", "bloodCultureMin"):
            chk(f, get(r, "timing." + f), exp[f])
        chk("lactateInitial", get(r, "timing.lactateInitial"), exp["lactateInitial"], 1e-9)
        chk("fluidMl3h", get(r, "timing.fluidMl3h"), exp["fluidMl3h"], 0.11)
        chk("fluidIncomplete3h", get(r, "timing.fluidIncomplete3h"), exp["fluidIncomplete3h"])
        tr = r.get("traj") or {}
        for h in range(24):
            chk(f"map[{h}]", tr["map"][h], exp["map"][h], 1e-9)
            chk(f"hr[{h}]", tr["hr"][h], exp["hr"][h], 0.051)
            chk(f"lactate[{h}]", tr["lactate"][h], exp["lactate"][h], 1e-9)
            chk(f"vaso[{h}]", tr["vaso"][h], exp["vaso"][h])
        for f, tol in (("pfMin", 1e-9), ("gcsMin", 1e-9), ("pltMin", 1e-9), ("tbilMaxUmol", 0.2), ("creaMaxUmol", 1e-9)):
            chk(f, get(r, "worst24." + f), exp[f], tol)
        chk("vasoAgents", sorted(get(r, "worst24.vasoAgents", [])), exp["vasoAgents"])
        chk("apache2First24", get(r, "scores.apache2First24"), exp["apache2First24"])
        chk("apache2CalDead", get(r, "scores.apache2CalDead"), exp["apache2CalDead"], 1e-9)
    if len(alerts) != truth["alerts"]:
        errors.append(f"alerts: got {len(alerts)} want {truth['alerts']} (simulated must be skipped)")
    if any(a.get("minFromT0") is None for a in alerts):
        errors.append("alert without minFromT0")
    res = report.run(export_dir, out_dir)
    statuses = {k: v["status"] for k, v in res["sections"].items()}
    if "error" in statuses.values():
        errors.append(f"report error: {statuses}")
    shock = sum(1 for r in cohort if get(r, "icu05.status") == "septic_shock")
    print(json.dumps({"staysChecked": checked, "alerts": len(alerts), "septicShock": shock, "manifest": {k: manifest.get(k) for k in ("stays", "staysWithoutT0", "staysFailed", "sourceFailures")},
                      "report": statuses, "errors": len(errors)}, ensure_ascii=False))
    for e in errors[:30]:
        print("ERROR", e)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:4]))
