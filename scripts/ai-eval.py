#!/usr/bin/env python3
"""AI-assist evaluation harness (stdlib only). Run on the hospital intranet, never in CI.

  probe : talk to the OpenAI-compatible gateway directly (models, latency, JSON mode, <think>, embeddings)
  brief : call the running workbench /ai/brief for N patients and score the validated output

Secrets come from environment variables only:
  AI_BASE_URL, AI_API_KEY, AI_MODEL_FAST, AI_MODEL_REASONING, AI_MODEL_EMBEDDING   (probe)
  APP_URL (default http://127.0.0.1:8080), APP_USER (default doctor), APP_PASSWORD (brief)
Outputs contain no names / MRN / beds; patient ids are replaced by short hashes.
"""
import argparse, base64, hashlib, json, os, re, ssl, statistics, sys, time, urllib.error, urllib.request


def ctx(insecure):
    if insecure:
        c = ssl.create_default_context(); c.check_hostname = False; c.verify_mode = ssl.CERT_NONE; return c
    ca = os.environ.get("AI_TLS_CA_FILE")
    return ssl.create_default_context(cafile=ca) if ca else ssl.create_default_context()


def call(url, body=None, headers=None, timeout=120, context=None, method=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method or ("POST" if data else "GET"),
                                 headers={"Content-Type": "application/json", **(headers or {})})
    t = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=context) as r:
            raw = r.read().decode("utf-8", "replace"); status = r.status
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace"); status = e.code
    except Exception as e:  # noqa: BLE001 - report every transport failure the same way
        return {"status": None, "error": type(e).__name__ + ": " + str(e)[:200], "ms": int((time.time() - t) * 1000)}
    ms = int((time.time() - t) * 1000)
    try:
        return {"status": status, "json": json.loads(raw), "ms": ms}
    except ValueError:
        return {"status": status, "text": raw[:500], "ms": ms}


def probe(a):
    base = os.environ.get("AI_BASE_URL", "").rstrip("/")
    if not base:
        sys.exit("AI_BASE_URL not set")
    chat = base + os.environ.get("AI_CHAT_PATH", "/v1/chat/completions")
    auth = {"Authorization": "Bearer " + os.environ["AI_API_KEY"]} if os.environ.get("AI_API_KEY") else {}
    c = ctx(a.insecure)
    report = {"gateway": re.sub(r"//[^@/]*@", "//", base), "models": None, "chat": [], "embedding": None}
    m = call(base + "/v1/models", headers=auth, context=c, timeout=20)
    if m.get("json"):
        report["models"] = [{k: v for k, v in x.items() if k in ("id", "max_model_len", "owned_by", "context_length")}
                            for x in m["json"].get("data", [])]
    else:
        report["models"] = {"status": m.get("status"), "error": m.get("error") or m.get("text")}
    prompt = [{"role": "system", "content": "只输出 JSON。"},
              {"role": "user", "content": "事实 E1：MAP 58 mmHg。输出 {\"summary\":[{\"text\":\"...\",\"refs\":[\"E1\"]}]}，text 不超过 20 字。"}]
    for role in ("FAST", "REASONING"):
        model = os.environ.get("AI_MODEL_" + role)
        if not model:
            continue
        for json_mode in (True, False):
            body = {"model": model, "messages": prompt, "temperature": 0.2, "max_tokens": 400, "stream": False}
            if json_mode:
                body["response_format"] = {"type": "json_object"}
            runs = []
            for _ in range(a.repeat):
                r = call(chat, body, auth, timeout=a.timeout, context=c)
                msg = ((r.get("json") or {}).get("choices") or [{}])[0].get("message", {})
                content = msg.get("content") or ""
                runs.append({"status": r.get("status"), "ms": r["ms"], "error": r.get("error") or (r.get("text") if r.get("status") != 200 else None),
                             "thinkTag": "<think>" in content, "reasoningField": bool(msg.get("reasoning_content")),
                             "jsonParsable": _parsable(content), "usage": (r.get("json") or {}).get("usage")})
            ok = [x["ms"] for x in runs if x["status"] == 200]
            report["chat"].append({"role": role.lower(), "model": model, "jsonMode": json_mode, "runs": runs,
                                   "medianMs": statistics.median(ok) if ok else None})
    emb = os.environ.get("AI_MODEL_EMBEDDING")
    if emb:
        r = call(base + os.environ.get("AI_EMBEDDINGS_PATH", "/v1/embeddings"), {"model": emb, "input": ["感染性休克 血培养", "去甲肾上腺素 一线"]},
                 auth, timeout=a.timeout, context=c)
        d = (r.get("json") or {}).get("data") or []
        report["embedding"] = {"model": emb, "status": r.get("status"), "ms": r["ms"], "dims": len(d[0]["embedding"]) if d else None,
                               "error": r.get("error") or (r.get("text") if r.get("status") != 200 else None)}
    print(json.dumps(report, ensure_ascii=False, indent=1))


def _parsable(s):
    s = re.sub(r"(?s)<think>.*?</think>", "", s or "").strip()
    s = re.sub(r"^```(?:json)?|```$", "", s).strip()
    i, j = s.find("{"), s.rfind("}")
    try:
        json.loads(s[i:j + 1]); return i >= 0
    except ValueError:
        return False


STATE = re.compile(r"(\d)h 时窗：(达标|未达标|无法判定|进行中)")


def contradictions(b):
    """Flag model statements whose window state wording disagrees with the rule engine fact."""
    states = {}
    for f in b.get("facts", []):
        m = STATE.search(f["text"])
        if m:
            states[m.group(1) + "h"] = m.group(2)
    out = []
    for sec in ("summary", "gaps", "suggestions"):
        for it in b.get(sec, []):
            t = it["text"]
            for key, st in states.items():
                if key not in t:
                    continue
                said = "未达标" if "未达标" in t else "达标" if "达标" in t else None
                if said and said != st:
                    out.append({"section": sec, "window": key, "engine": st, "text": t})
    return out


def brief(a):
    app = os.environ.get("APP_URL", "http://127.0.0.1:8080").rstrip("/")
    pw = os.environ.get("APP_PASSWORD") or sys.exit("APP_PASSWORD not set")
    auth = {"Authorization": "Basic " + base64.b64encode((os.environ.get("APP_USER", "doctor") + ":" + pw).encode()).decode()}
    st = call(app + "/api/v1/ai/status", headers=auth, timeout=20)
    print("status:", json.dumps((st.get("json") or {}).get("data"), ensure_ascii=False))
    pts = call(app + "/api/v1/patients?size=%d" % a.patients, headers=auth, timeout=60)
    items = ((pts.get("json") or {}).get("data") or {}).get("items") or []
    rows = []
    for p in items:
        h = hashlib.sha256(p["id"].encode()).hexdigest()[:8]
        for kind in a.kinds.split(","):
            r = call(app + "/api/v1/patients/%s/ai/brief" % p["id"], {"kind": kind},
                     {**auth, "Idempotency-Key": "eval-" + h + kind + str(int(time.time()))}, timeout=a.timeout + 30)
            d = (r.get("json") or {}).get("data") or {}
            refs = [x for s in ("summary", "gaps", "suggestions") for i in d.get(s, []) for x in i.get("refs", [])]
            rows.append({"patient": h, "kind": kind, "http": r.get("status"), "status": d.get("status"), "model": d.get("model"),
                         "fallback": d.get("fallbackUsed"), "latencyMs": d.get("latencyMs"), "roundTripMs": r["ms"],
                         "items": {s: len(d.get(s, [])) for s in ("summary", "gaps", "suggestions", "uncertainties")},
                         "dropped": d.get("droppedCount"), "facts": len(d.get("facts", [])), "knowledge": [k["id"] for k in d.get("knowledge", [])],
                         "factRefShare": round(sum(1 for x in refs if x.startswith("E")) / len(refs), 2) if refs else None,
                         "contradictions": contradictions(d), "interactionId": d.get("interactionId"),
                         "output": {s: [i["text"] for i in d.get(s, [])] for s in ("summary", "gaps", "suggestions", "uncertainties")} if a.keep_text else None})
            print(json.dumps({k: v for k, v in rows[-1].items() if k != "output"}, ensure_ascii=False))
    with open(a.out, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    ok = [r for r in rows if r["status"] == "ok"]
    lat = [r["latencyMs"] for r in ok if r["latencyMs"]]
    print(json.dumps({"calls": len(rows), "byStatus": {s: sum(1 for r in rows if r["status"] == s) for s in {r["status"] for r in rows}},
                      "medianLatencyMs": statistics.median(lat) if lat else None, "maxLatencyMs": max(lat) if lat else None,
                      "fallbackUsed": sum(1 for r in ok if r["fallback"]), "droppedTotal": sum(r["dropped"] or 0 for r in rows),
                      "contradictions": sum(len(r["contradictions"]) for r in rows), "out": a.out}, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p1 = sub.add_parser("probe"); p1.add_argument("--repeat", type=int, default=3); p1.add_argument("--timeout", type=int, default=120)
    p1.add_argument("--insecure", action="store_true", help="skip TLS verification (isolated intranet test only)")
    p2 = sub.add_parser("brief"); p2.add_argument("--patients", type=int, default=10); p2.add_argument("--kinds", default="summary,icu05")
    p2.add_argument("--timeout", type=int, default=90); p2.add_argument("--out", default="ai-eval.jsonl")
    p2.add_argument("--keep-text", action="store_true", help="store generated text for clinician review (still de-identified)")
    a = ap.parse_args()
    probe(a) if a.cmd == "probe" else brief(a)
