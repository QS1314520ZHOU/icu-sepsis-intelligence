#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .runtime
[[ ! -e .runtime/demo.env ]] || { echo '.runtime/demo.env already exists; preserved'; exit 0; }
umask 077
python3 - <<'PYENV'
import secrets
from pathlib import Path
p=Path('.runtime/demo.env')
p.write_text('DEMO_MODE=true\nCLINICAL_SOURCE_MODE=demo\nMONGODB_URI=mongodb://127.0.0.1:27027/icu_sepsis_demo?replicaSet=rs0\n'+''.join(role+'_PASSWORD='+secrets.token_urlsafe(24)+'\n' for role in ['DOCTOR','NURSE','QUALITY']))
p.chmod(0o600)
PYENV
echo 'Created local random role passwords in .runtime/demo.env (not printed).'
