#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/check-java15.sh
(cd frontend && npm ci && npm test)
mkdir -p release
# mvn package 会自动 npm install + ng build，并把 frontend/dist 打进 jar 的 static/。
mvn -B -f backend/pom.xml package
cp backend/target/sepsis-intelligence-0.3.0.jar release/
echo 'Built release/sepsis-intelligence-0.3.0.jar; Mongo integration tests require RUN_MONGO_TESTS=true.'
