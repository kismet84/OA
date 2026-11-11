#!/usr/bin/env bash
# Local production-profile + actual HTTPS reverse proxy smoke test, no Docker daemon needed.
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$OA_ROOT"
mkdir -p .runtime/nginx .runtime/certs
if [ ! -x .tools/system/usr/sbin/nginx ]; then
  curl -fL --retry 3 https://deb.debian.org/debian/pool/main/n/nginx/nginx_1.26.3-3+deb13u10_amd64.deb -o .tools/nginx.deb
  dpkg-deb -x .tools/nginx.deb .tools/system
fi
if [ ! -f .runtime/certs/privkey.pem ]; then
  (umask 077; openssl req -x509 -nodes -newkey rsa:3072 -keyout .runtime/certs/privkey.pem -out .runtime/certs/fullchain.pem -days 30 -subj '/CN=localhost' -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1') > .runtime/certificate.log 2>&1
fi
cp backend/target/oa-1.0.0.jar .runtime/oa-prod.jar
SPRING_PROFILES_ACTIVE=prod CORS_ORIGINS=https://localhost:8443 SERVER_PORT=8081 TRUSTED_PROXIES=127.0.0.1 UPLOAD_DIR="$OA_ROOT/.runtime/uploads" setsid java -jar .runtime/oa-prod.jar > .runtime/production.log 2>&1 &
prod_pid=$!
nginx_binary="$OA_ROOT/.tools/system/usr/sbin/nginx"
nginx_config="$OA_ROOT/.runtime/nginx/nginx.conf"
cleanup() {
  if [ -f .runtime/nginx/nginx.pid ]; then "$nginx_binary" -p "$OA_ROOT/.runtime/nginx/" -c "$nginx_config" -s quit -e "$OA_ROOT/.runtime/nginx/error.log" || true; fi
  kill "$prod_pid" 2>/dev/null || true
}
trap cleanup EXIT
for i in {1..60}; do
  if curl -fsS http://127.0.0.1:8081/api/health >/dev/null 2>&1; then break; fi
  if [ "$i" = 60 ]; then tail -50 .runtime/production.log; exit 1; fi
  sleep 1
done
python3 - <<'PY'
from pathlib import Path
root=Path.cwd()
# Reuse the shipped server block; adapt only addresses and local file locations.
s=(root/'deploy/nginx/oa.conf').read_text().replace('listen 443 ssl;', 'listen 127.0.0.1:8443 ssl;').replace('/etc/nginx/certs',str(root/'.runtime/certs')).replace('/usr/share/nginx/html',str(root/'admin/dist')).replace('http://backend:8080','http://127.0.0.1:8081')
(root/'.runtime/nginx/server.conf').write_text(s)
(root/'.runtime/nginx/nginx.conf').write_text(f'''pid {root}/.runtime/nginx/nginx.pid;
error_log {root}/.runtime/nginx/error.log;
events {{ worker_connections 256; }}
http {{
 access_log {root}/.runtime/nginx/access.log;
 client_body_temp_path {root}/.runtime/nginx/body;
 proxy_temp_path {root}/.runtime/nginx/proxy;
 fastcgi_temp_path {root}/.runtime/nginx/fastcgi;
 uwsgi_temp_path {root}/.runtime/nginx/uwsgi;
 scgi_temp_path {root}/.runtime/nginx/scgi;
 types {{ text/html html; text/css css; application/javascript js; image/png png; image/svg+xml svg; }}
 include {root}/.runtime/nginx/server.conf;
}}
''')
PY
"$nginx_binary" -p "$OA_ROOT/.runtime/nginx/" -c "$nginx_config" -t -e "$OA_ROOT/.runtime/nginx/error.log"
"$nginx_binary" -p "$OA_ROOT/.runtime/nginx/" -c "$nginx_config" -e "$OA_ROOT/.runtime/nginx/error.log"
python3 - <<'PY'
import json,os,pathlib,ssl,urllib.request,urllib.error
root=pathlib.Path.cwd();ctx=ssl.create_default_context(cafile=str(root/'.runtime/certs/fullchain.pem'));base='https://localhost:8443'
def req(path,data=None,token=None):
 headers={'Content-Type':'application/json'}
 if token:headers['Authorization']='Bearer '+token
 return urllib.request.urlopen(urllib.request.Request(base+path,data=None if data is None else json.dumps(data).encode(),headers=headers),context=ctx)
response=req('/');assert '公司 OA' in response.read().decode();assert response.headers.get('Strict-Transport-Security')
assert json.load(req('/api/health'))['status']=='正常'
login=json.load(req('/api/auth/login',{'login':'admin','password':os.environ.get('OA_ADMIN_PASSWORD','Admin@2026Verified')}));token=login['token']
me=json.load(req('/api/auth/me',token=token));assert me['development'] is False
try:req('/api/admin/dev/punch',{'userId':1,'exitIp':'127.0.0.1','punch':{'kind':'NORMAL','latitude':31,'longitude':121,'accuracy':20}},token)
except urllib.error.HTTPError as e:assert e.code==404,e.read()
else:raise AssertionError('正式环境不应有模拟接口')
req('/api/auth/logout',{},token)
(root/'.runtime/production-smoke.json').write_text(json.dumps({'https':True,'certificateVerified':True,'health':True,'development':False,'simulatorStatus':404},indent=2))
print('通过：真实 Nginx HTTPS、证书验证、静态页面、反代、生产登录、生产模拟接口 404')
PY
OA_WEB=https://localhost:8443 OA_PROD_WEB=https://localhost:8443 OA_LOCAL_TLS=true npm --prefix admin run test:e2e -- --grep '正式环境|员工网页'
