"""Seed a LOCAL test MongoDB with synthetic SmartCare / DataCenter / business records built from synth rows.

Dev/e2e only: refuses any host other than 127.0.0.1/localhost. Writes truth.json (expected export values keyed by
stayKey) so verify_export.py can check the Java exporter field by field. No real patient data is involved.
"""
import argparse
import hashlib
import json
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from icu_research import synth  # noqa: E402

try:
    from bson import ObjectId
    from pymongo import MongoClient
except ImportError:  # pragma: no cover
    sys.exit("需要 pymongo（仅测试用）：pip install pymongo")

BASE = datetime(2025, 1, 3, 0, 0, tzinfo=timezone.utc)


def stay_key(salt, enc):
    return hashlib.sha256(f"{salt}|{enc}".encode("utf-8")).hexdigest()[:20]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--uri", default="mongodb://127.0.0.1:27117")
    ap.add_argument("--smartcare-db", default="SmartCareE2E")
    ap.add_argument("--datacenter-db", default="DataCenterE2E")
    ap.add_argument("--business-db", default="icu_sepsis_e2e")
    ap.add_argument("--n", type=int, default=500)
    ap.add_argument("--seed", type=int, default=5)
    ap.add_argument("--salt", required=True)
    ap.add_argument("--truth", required=True)
    a = ap.parse_args()
    if not any(h in a.uri for h in ("127.0.0.1", "localhost")):
        sys.exit("只允许写本机测试库")
    cli = MongoClient(a.uri, serverSelectionTimeoutMS=3000)
    for db in (a.smartcare_db, a.datacenter_db, a.business_db):
        cli.drop_database(db)
    sc, dc, biz = cli[a.smartcare_db], cli[a.datacenter_db], cli[a.business_db]
    sc.configDrug.insert_many([{"name": "去甲肾上腺素注射液", "classification": "血管活性药"},
                               {"name": "注射用美罗培南", "classification": "抗菌药物"}])
    rows, alerts = synth.make(a.n, seed=a.seed)
    alerts_by_key = {}
    for al in alerts:
        alerts_by_key.setdefault(al["stayKey"], []).append(al)
    P, ZY, BG, BS, SC, DE, EX, AL, HI = [], [], [], [], [], [], [], [], []
    truth = {}
    for i, r in enumerate(rows):
        oid = ObjectId()
        pid = str(oid)
        mrn, his = f"E2E{i:05d}", f"HIS{i:05d}"
        icu = BASE + timedelta(hours=i * 8760 // a.n)
        icu = icu.replace(microsecond=0)
        enc = f"{mrn}|{int(icu.timestamp() * 1000)}"
        key = stay_key(a.salt, enc)
        t0 = icu + timedelta(minutes=r["icuToT0Min"]) if r["t0Available"] else None
        o = r["outcome"]
        out_t = (t0 + timedelta(minutes=o["icuOutMinFromT0"])) if t0 and o.get("icuOutMinFromT0") is not None else icu + timedelta(hours=o["icuLosHours"])
        P.append({"_id": oid, "name": f"测试患者{i}", "mrn": mrn, "hisPid": his, "gender": r["sex"], "age": r["ageYears"],
                  "weight": r["weightKg"], "status": "out", "icuAdmissionTime": icu, "admissionTime": icu - timedelta(hours=r["hospitalToIcuHours"]),
                  "admissionType": r["admissionType"], "dischargedType": o["dischargedType"], "icuDischargeTime": out_t,
                  "clinicalDiagnosis": "肺炎|脓毒症" if r["icu05"]["status"] in ("septic_shock", "suspected_review") else "术后"})
        exp = {"ageYears": float(r["ageYears"]), "sex": r["sex"], "t0Available": r["t0Available"], "death": o["death"]}
        truth[key] = exp
        if not t0:
            continue
        exp["icuOutMinFromT0"] = o["icuOutMinFromT0"]
        at = lambda m: t0 + timedelta(minutes=m)  # noqa: E731
        ZY.append({"pid": his, "orderName": "一级护理", "orderTime": t0})
        bc = max(1, r["timing"]["bloodCultureMin"])
        ZY.append({"pid": his, "orderName": "血培养(需氧+厌氧)", "orderTime": at(bc - 1), "reviewTime": at(bc)})
        exp["bloodCultureMin"] = bc
        tr, tm, w = r["traj"], r["timing"], r["worst24"]
        for h in range(24):
            if tr["map"][h] is not None:
                BS.append({"pid": pid, "code": "param_ibp_m", "valid": True, "time": at(h * 60 + 20), "strVal": str(int(tr["map"][h]))})
            if tr["hr"][h] is not None:
                BS.append({"pid": pid, "code": "param_HR", "valid": True, "time": at(h * 60 + 25), "strVal": str(tr["hr"][h])})
        exp["map"], exp["hr"] = tr["map"], tr["hr"]
        lac = [{"code": "param_bg_Lac", "valid": "valid", "time": at(-20), "fVal": tm["lactateInitial"]}]
        for h, v in enumerate(tr["lactate"]):
            if v is not None:
                lac.append({"code": "param_bg_Lac", "valid": "valid", "time": at(h * 60 + 30), "fVal": v})
        lac.append({"code": "param_bg_P/Fratio", "valid": "valid", "time": at(60), "fVal": round(w["pfMin"], 1)})
        BG.append({"mrn": mrn, "bedsides": lac})
        exp["lactateInitial"], exp["lactate"], exp["pfMin"] = tm["lactateInitial"], tr["lactate"], round(w["pfMin"], 1)
        if w["gcsMin"] is not None:
            SC.append({"pid": pid, "scoreType": "gcsScore", "valid": True, "time": at(60), "total": w["gcsMin"]})
        exp["gcsMin"] = w["gcsMin"]
        SC.append({"pid": pid, "scoreType": "apacheII", "valid": True, "time": icu + timedelta(minutes=60), "total": r["scores"]["apache2First24"],
                   "apacheII": {"calDead": {"score": r["scores"]["apache2CalDead"]}}})
        exp["apache2First24"], exp["apache2CalDead"] = float(r["scores"]["apache2First24"]), r["scores"]["apache2CalDead"]

        def drug(minute, items, actions):
            DE.append({"pid": pid, "startTime": at(minute), "drugList": items,
                       "drugActionList": [{"time": at(m), "action": act} for m, act in actions]})
        if tm["abxFirstMin"] is not None:
            drug(tm["abxFirstMin"], [{"name": "注射用美罗培南", "liquidAmount": 100, "liquidUnit": "ml"}], [(tm["abxFirstMin"], "start")])
        exp["abxFirstMin"] = tm["abxFirstMin"] if tm["abxFirstMin"] is not None and tm["abxFirstMin"] <= 24 * 60 else None
        if tm["vasoFirstMin"] is not None:
            v = tm["vasoFirstMin"]
            items = [{"name": "去甲肾上腺素注射液", "liquidAmount": 50, "liquidUnit": "ml"}]
            if "vasopressin" in w["vasoAgents"]:
                items.append({"name": "垂体后叶素注射液", "liquidAmount": 50, "liquidUnit": "ml"})
            drug(v, items, [(v, "start"), (24 * 60 + 30, "调速")])
        exp["vasoFirstMin"], exp["vaso"], exp["vasoAgents"] = tm["vasoFirstMin"], tr["vaso"], sorted(w["vasoAgents"])
        f1 = tm["fluidMl1h"]
        drug(10, [{"name": "0.9%氯化钠注射液", "liquidAmount": f1, "liquidUnit": "ml"}], [(10, "start")])
        rest = round(tm["fluidMl6h"] * 2 / 3) - f1  # fluid3 total = fluid6h * 2/3 in synth
        if rest > 0:
            drug(100, [{"name": "乳酸林格注射液", "liquidAmount": rest, "liquidUnit": "ml"}], [(100, "start")])
        if tm["fluidIncomplete3h"]:
            drug(120, [{"name": "羟乙基淀粉注射液", "liquidAmount": None, "liquidUnit": "ml"}], [(120, "start")])
        exp["fluidMl3h"] = None if tm["fluidIncomplete3h"] and f1 + max(rest, 0) == 0 else float(f1 + max(rest, 0))
        exp["fluidIncomplete3h"] = tm["fluidIncomplete3h"]
        EX.append({"hisPid": his, "itemCode": "PLT", "result": str(round(w["pltMin"], 1)), "unit": "10^9/L", "authTime": at(120)})
        EX.append({"hisPid": his, "itemCode": "TBIL", "result": str(round(w["tbilMaxUmol"] / 17.104, 2)), "unit": "mg/dL", "authTime": at(120)})
        EX.append({"hisPid": his, "itemCode": "sCr", "result": str(round(w["creaMaxUmol"], 1)), "unit": "μmol/L", "authTime": at(120)})
        exp["pltMin"], exp["tbilMaxUmol"], exp["creaMaxUmol"] = round(w["pltMin"], 1), round(w["tbilMaxUmol"], 1), round(w["creaMaxUmol"], 1)
        for al in alerts_by_key.get(r["stayKey"], []):
            aid = ObjectId()
            created = at(al["minFromT0"])
            doc = {"_id": aid, "encounterId": enc, "patientId": pid, "createdAt": created, "status": al["status"], "severity": al["severity"],
                   "simulated": False}
            for rv in al["reviews"]:
                doc["closedAt"] = created + timedelta(minutes=rv["min"])
                HI.append({"entityType": "alert", "entityId": str(aid), "action": rv["action"], "reason": rv["reason"],
                           "actor": "nurse-" + rv["actorKey"], "createdAt": created + timedelta(minutes=rv["min"])})
            AL.append(doc)
        if al_sim := (i % 50 == 0):
            AL.append({"_id": ObjectId(), "encounterId": enc, "createdAt": at(5), "status": "open", "severity": "high", "simulated": al_sim})
    sc.patient.insert_many(P)
    for coll, docs in ((dc.VI_ICU_ZYYZ, ZY), (sc.bGATemp, BG), (sc.bedside, BS), (sc.score, SC), (sc.drugExe, DE),
                       (dc.VI_ICU_EXAM_ITEM, EX), (biz.sepsis_alert_event, AL), (biz.sepsis_state_history, HI)):
        if docs:
            coll.insert_many(docs)
    Path(a.truth).write_text(json.dumps({"stays": truth, "alerts": sum(1 for d in AL if not d["simulated"]),
                                         "identifiers": [p["mrn"] for p in P[:50]] + [p["hisPid"] for p in P[:50]] + [str(p["_id"]) for p in P[:50]]},
                                        ensure_ascii=False, default=str), encoding="utf-8")
    print(f"seeded {len(P)} stays, {len(BS)} bedside, {len(DE)} drugExe, {len(AL)} alerts")


if __name__ == "__main__":
    main()
