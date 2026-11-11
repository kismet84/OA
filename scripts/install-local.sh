#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$OA_ROOT"
mkdir -p .tools/system .runtime
# Debian 13 x86_64: extract packages locally instead of installing system services.
if [ "$(uname -m)" != x86_64 ] || ! rg -q 'VERSION_ID="13"' /etc/os-release; then
  echo '自动安装脚本适用于 Debian 13 x86_64；其他系统请按 README 安装依赖。'; exit 1
fi
if ! command -v java >/dev/null || ! java -version 2>&1 | head -1 | rg -q '"21\.'; then
  curl -fL --retry 3 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz' -o .tools/jdk.tar.gz
  tar -xzf .tools/jdk.tar.gz -C .tools
fi
if [ ! -x .tools/apache-maven-3.9.9/bin/mvn ]; then
  curl -fL --retry 3 https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.tar.gz -o .tools/maven.tar.gz
  tar -xzf .tools/maven.tar.gz -C .tools
fi
python3 - <<'PY'
import urllib.request,subprocess,pathlib
packages = {
 'postgresql.deb': ('p/postgresql-17/postgresql-17_17.11-0+deb13u1_amd64.deb','.tools/system/usr/lib/postgresql/17/bin/postgres'),
 'redis-tools.deb': ('r/redis/redis-tools_8.0.2-3+deb13u3_amd64.deb','.tools/system/usr/bin/redis-cli'),
 'redis-server.deb': ('r/redis/redis-server_8.0.2-3+deb13u3_amd64.deb','.tools/system/usr/bin/redis-server'),
 'liblzf.deb': ('libl/liblzf/liblzf1_3.6-4+b4_amd64.deb','.tools/system/usr/lib/x86_64-linux-gnu/liblzf.so.1')
}
for name,(url,target) in packages.items():
 if pathlib.Path(target).exists(): continue
 dest='.tools/'+name
 urllib.request.urlretrieve('https://deb.debian.org/debian/pool/main/'+url,dest)
 subprocess.run(['dpkg-deb','-x',dest,'.tools/system'],check=True)
PY
source scripts/env.sh
java -version
mvn --version
postgres --version
redis-server --version
node --version
echo '依赖已准备好。运行 bash scripts/dev-up.sh 启动。'
