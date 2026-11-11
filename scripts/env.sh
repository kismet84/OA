#!/usr/bin/env bash
# Source from repository scripts; never writes outside the repository.
OA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -d "$OA_ROOT/.tools/jdk-21.0.13+9" ]; then export JAVA_HOME="$OA_ROOT/.tools/jdk-21.0.13+9"; fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$OA_ROOT/.tools/apache-maven-3.9.9/bin:$OA_ROOT/.tools/system/usr/lib/postgresql/17/bin:$OA_ROOT/.tools/system/usr/bin:$PATH"
export LD_LIBRARY_PATH="$OA_ROOT/.tools/system/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export npm_config_cache="$OA_ROOT/.tools/npm-cache"
export PLAYWRIGHT_BROWSERS_PATH="$OA_ROOT/.tools/browsers"

export TMPDIR="$OA_ROOT/.runtime/tmp"
mkdir -p "$TMPDIR"
