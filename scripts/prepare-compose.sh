#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$OA_ROOT"
# Only creates missing files; never overwrites configured secrets or certificates.
if [ ! -f .env ]; then
  (umask 077; python3 - <<'PY'
import secrets
with open('.env','x') as f:
 for key in ['DB_PASSWORD','REDIS_PASSWORD','BOOTSTRAP_PASSWORD']: f.write(key+'='+secrets.token_urlsafe(30)+'\n')
 f.write('CORS_ORIGINS=https://localhost\n')
PY
  )
fi
mkdir -p .runtime/certs
if [ ! -f .runtime/certs/privkey.pem ]; then
  (umask 077; openssl req -x509 -nodes -newkey rsa:3072 -keyout .runtime/certs/privkey.pem -out .runtime/certs/fullchain.pem -days 30 -subj '/CN=localhost' -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1')
fi
echo '本机 HTTPS 配置已准备。管理员初始密码见 .env；生产部署请替换域名及证书。'
