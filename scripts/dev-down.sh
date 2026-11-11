#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$OA_ROOT"
for name in backend admin; do
  file=".runtime/$name.pid"
  if [ -f "$file" ]; then
    pid="$(cat "$file")"
    # Kill only a process whose command references this project's service.
    process_cwd="$(readlink "/proc/$pid/cwd" 2>/dev/null || true)"
    if [[ "$process_cwd" = "$OA_ROOT" || "$process_cwd" = "$OA_ROOT/admin" ]] && ps -p "$pid" -o args= | rg -q 'oa-1.0.0.jar|oa-app.jar|npm.*dev'; then
      if [ "$(ps -p "$pid" -o pgid= | tr -d ' ')" = "$pid" ]; then
        kill -TERM -- "-$pid" || true
      else
        kill "$pid" || true
      fi
    fi
    rm -f "$file"
  fi
done
if [ -f .runtime/redis.pid ]; then redis-cli -p 56379 shutdown || true; fi
if [ -f .runtime/postgres/PG_VERSION ]; then pg_ctl -D .runtime/postgres stop -m fast || true; fi
