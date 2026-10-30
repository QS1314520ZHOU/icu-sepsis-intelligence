#!/usr/bin/env bash
# End-to-end check of the research export on a LOCAL synthetic MongoDB (never the hospital DB).
# Needs only: running mongod (MONGO_URI, default mongodb://127.0.0.1:27117), Java 15+, built jar. No Python.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MONGO_URI="${MONGO_URI:-mongodb://127.0.0.1:27117}"
JAR="${JAR:-$(ls "$ROOT"/release/sepsis-intelligence-*.jar | head -1)}"
WORK="${WORK:-$(mktemp -d)}"
N="${N:-500}"
mkdir -p "$WORK"
SALT="e2e-salt-$(date +%s)-local"
java -jar "$JAR" research-e2e-seed --uri "$MONGO_URI" --n "$N" --salt "$SALT" --truth "$WORK/truth.json"
java -jar "$JAR" --spring.main.web-application-type=none \
  --app.source.mode=smartcare --app.demo=false \
  --spring.data.mongodb.uri="$MONGO_URI/icu_sepsis_e2e" \
  --app.source.smartcare.uri="$MONGO_URI/SmartCareE2E" --app.source.datacenter.uri="$MONGO_URI/DataCenterE2E" \
  --app.research.export.enabled=true --app.research.export.from=2025-01-01 --app.research.export.to=2026-01-10 \
  --app.research.export.salt="$SALT" --app.research.export.out="$WORK/export" > "$WORK/export.log" 2>&1 || { tail -40 "$WORK/export.log"; exit 1; }
java -jar "$JAR" research-e2e-verify "$WORK/export" "$WORK/truth.json" "$WORK/report"
echo "report: $WORK/report/report.md"
