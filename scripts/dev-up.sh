#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$OA_ROOT"
mkdir -p .runtime/postgres .runtime/redis .runtime/uploads .runtime/socket
chmod 700 .runtime/postgres
if [ ! -f .runtime/postgres/PG_VERSION ]; then
  printf '%s\n' 'oa-local-only' > .runtime/pg-password
  chmod 600 .runtime/pg-password
  initdb -D .runtime/postgres -L "$OA_ROOT/.tools/system/usr/share/postgresql/17" -U oa --pwfile=.runtime/pg-password --auth-host=scram-sha-256 --auth-local=scram-sha-256 --encoding=UTF8 --no-locale > .runtime/initdb.log
fi
if ! pg_ctl -D .runtime/postgres status >/dev/null 2>&1; then
  pg_ctl -D .runtime/postgres -l .runtime/postgres.log -o "-h 127.0.0.1 -p 55432 -k $OA_ROOT/.runtime/socket" start
fi
if ! redis-cli -p 56379 ping >/dev/null 2>&1; then
  redis-server --bind 127.0.0.1 --port 56379 --dir "$OA_ROOT/.runtime/redis" --appendonly yes --daemonize yes --pidfile "$OA_ROOT/.runtime/redis.pid" --logfile "$OA_ROOT/.runtime/redis.log"
fi
if ! PGPASSWORD=oa-local-only psql -h 127.0.0.1 -p 55432 -U oa -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='oa'" | rg -q 1; then
  PGPASSWORD=oa-local-only createdb -h 127.0.0.1 -p 55432 -U oa oa
fi
mvn -f backend/pom.xml -Dmaven.repo.local="$OA_ROOT/.tools/m2" package -q
npm --prefix admin ci --no-audit --no-fund
npm --prefix admin run build
# Rebuild succeeded: restart our tracked backend to load new code.
if [ -f .runtime/backend.pid ]; then
  old_pid="$(cat .runtime/backend.pid)"
  if [ "$(readlink "/proc/$old_pid/cwd" 2>/dev/null || true)" = "$OA_ROOT" ] && ps -p "$old_pid" -o args= | rg -q 'java -jar (backend/target/oa-1.0.0.jar|.runtime/oa-app.jar)'; then
    kill "$old_pid"
    for i in {1..20}; do if ! kill -0 "$old_pid" 2>/dev/null; then break; fi; sleep 0.2; done
  fi
fi
if ! curl -fsS http://127.0.0.1:8080/api/health >/dev/null 2>&1; then
  cp backend/target/oa-1.0.0.jar .runtime/oa-app.jar
  UPLOAD_DIR="$OA_ROOT/.runtime/uploads" SPRING_PROFILES_ACTIVE=dev setsid java -jar .runtime/oa-app.jar > .runtime/backend.log 2>&1 &
  echo $! > .runtime/backend.pid
fi
for i in {1..60}; do
  if curl -fsS http://127.0.0.1:8080/api/health > .runtime/health.json 2>/dev/null; then break; fi
  if [ "$i" = 60 ]; then tail -60 .runtime/backend.log; exit 1; fi
  sleep 1
done
if ! curl -fsS http://127.0.0.1:5173 >/dev/null 2>&1; then
  setsid npm --prefix admin run dev -- --port 5173 --strictPort > .runtime/admin.log 2>&1 &
  echo $! > .runtime/admin.pid
fi
for i in {1..30}; do
  if curl -fsS http://127.0.0.1:5173 >/dev/null 2>&1; then break; fi
  if [ "$i" = 30 ]; then tail -40 .runtime/admin.log; exit 1; fi
  sleep 1
done
echo '后端：http://127.0.0.1:8080/api/health'
echo '管理后台：http://127.0.0.1:5173（首次启动 admin / Admin@2026Dev，必须改密）'
