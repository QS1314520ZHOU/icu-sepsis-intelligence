#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/check-java15.sh
[[ -f .runtime/demo.env ]] || bash scripts/generate-demo-env.sh
set -a; source .runtime/demo.env; set +a
mkdir -p .runtime
jar=release/sepsis-intelligence-0.3.0.jar
[[ -f "$jar" ]] || jar=backend/target/sepsis-intelligence-0.3.0.jar
[[ -f "$jar" ]] || { echo 'Run bash scripts/build.sh first'; exit 1; }
if [[ -f .runtime/demo.pid ]] && kill -0 "$(cat .runtime/demo.pid)" 2>/dev/null; then
  echo 'A demo started by this script is still running; stop it before restarting.'; exit 1
fi
# Version-addressed runtime copies are never overwritten while a JVM may read them.
hash="$(sha256sum "$jar" | cut -d' ' -f1)"
runtime=".runtime/sepsis-$hash.jar"
[[ -f "$runtime" ]] || cp "$jar" "$runtime"
echo $$ > .runtime/demo.pid
exec "$JAVA_HOME/bin/java" -jar "$runtime"
