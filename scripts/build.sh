#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/check-java15.sh
(cd frontend && npm ci && npm test && npm run build)
mkdir -p backend/src/main/resources/static release
# This directory contains generated Angular assets only; source is under frontend/src.
find backend/src/main/resources/static -type f -delete
cp -R frontend/dist/workbench/browser/. backend/src/main/resources/static/
mvn -B -f backend/pom.xml package
cp backend/target/sepsis-intelligence-0.3.0.jar release/
echo 'Built release/sepsis-intelligence-0.3.0.jar; Mongo integration tests require RUN_MONGO_TESTS=true.'
