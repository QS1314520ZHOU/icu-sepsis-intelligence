#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .runtime
[[ ! -e .runtime/demo.env ]] || { echo '.runtime/demo.env already exists; preserved'; exit 0; }
umask 077
token() { head -c 24 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n'; }
{
  printf 'DEMO_MODE=true\nCLINICAL_SOURCE_MODE=demo\nMONGODB_URI=mongodb://127.0.0.1:27027/icu_sepsis_demo?replicaSet=rs0\n'
  for role in DOCTOR NURSE QUALITY; do printf '%s_PASSWORD=%s\n' "$role" "$(token)"; done
} > .runtime/demo.env
chmod 600 .runtime/demo.env
echo 'Created local random role passwords in .runtime/demo.env (not printed).'
