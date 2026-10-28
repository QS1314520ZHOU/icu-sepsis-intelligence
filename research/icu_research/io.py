"""Load the backend's JSONL export (schema icu-research-cohort/1, icu-research-alert/1)."""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, List

COHORT_SCHEMA = "icu-research-cohort/1"
ALERT_SCHEMA = "icu-research-alert/1"
FORBIDDEN_KEYS = {"name", "mrn", "hisPid", "patientId", "sourceRecordId", "_id"}


def read_jsonl(path: Path, schema: str) -> List[Dict[str, Any]]:
    rows = []
    p = Path(path)
    if not p.exists():
        return rows
    with p.open(encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            r = json.loads(line)
            if r.get("schema") != schema:
                raise ValueError(f"{p.name}:{n} schema {r.get('schema')!r} != {schema!r}")
            leaked = FORBIDDEN_KEYS & _keys(r)
            if leaked:
                raise ValueError(f"{p.name}:{n} contains identifying keys {sorted(leaked)}")
            rows.append(r)
    return rows


def _keys(obj) -> set:
    out = set()
    if isinstance(obj, dict):
        for k, v in obj.items():
            out.add(k)
            out |= _keys(v)
    elif isinstance(obj, list):
        for v in obj:
            out |= _keys(v)
    return out


def load(export_dir: Path):
    d = Path(export_dir)
    cohort = read_jsonl(d / "cohort.jsonl", COHORT_SCHEMA)
    alerts = read_jsonl(d / "alerts.jsonl", ALERT_SCHEMA)
    manifest = json.loads((d / "manifest.json").read_text(encoding="utf-8")) if (d / "manifest.json").exists() else {}
    return cohort, alerts, manifest


def get(row: Dict[str, Any], path: str, default=None):
    cur: Any = row
    for part in path.split("."):
        if not isinstance(cur, dict) or part not in cur:
            return default
        cur = cur[part]
    return default if cur is None else cur
