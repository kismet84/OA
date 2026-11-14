#!/usr/bin/env python3
"""Audit release signing without keys or package construction. Run after sourcing env.sh."""
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
variables = ('OA_ANDROID_KEYSTORE_PATH', 'OA_ANDROID_STORE_PASSWORD',
             'OA_ANDROID_KEY_PASSWORD', 'OA_ANDROID_KEY_ALIAS')
env = {key: value for key, value in os.environ.items() if key not in variables}
source = (root / 'mobile/android/app/build.gradle.kts').read_text()
assert 'signingConfig = signingConfigs.getByName("release")' in source
assert 'signingConfig = signingConfigs.getByName("debug")' not in source
assert 'manifestPlaceholders["oaCleartext"] = "false"' in source
print('通过：release 明文禁用、显式正式签名、无 debug 回退', flush=True)
paths = ['audit.keystore', 'mobile/audit.jks', 'mobile/android/audit.p12',
         'mobile/android/key.properties', 'mobile/signing.properties', 'secrets/audit']
ignored = subprocess.run(['git', 'check-ignore', '--stdin'], cwd=root, input='\n'.join(paths)+'\n',
                         text=True, capture_output=True, check=True)
assert ignored.stdout.splitlines() == paths
tracked = subprocess.check_output(['git', 'ls-files'], cwd=root, text=True).splitlines()
assert not any(Path(name).suffix.lower() in ('.keystore', '.jks', '.p12', '.pfx')
               or Path(name).name in ('key.properties', 'signing.properties')
               or 'secrets' in Path(name).parts for name in tracked)
print('通过：签名文件与本机 secrets 已忽略，git 无此类密钥文件', flush=True)
for task, expect_success in [(':app:validateSigningDebug', True), (':app:verifyReleaseSigning', False)]:
    result = subprocess.run(['./gradlew', task, '--dry-run', '--console=plain'],
                            cwd=root / 'mobile/android', env=env, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
    if expect_success:
        assert result.returncode == 0, result.stdout
        print('通过：普通 debug 开发配置不要求正式签名凭据', flush=True)
    else:
        assert result.returncode != 0, 'Missing signing credentials unexpectedly accepted'
        assert 'Release signing requires external signing credentials' in result.stdout, result.stdout
        assert all(name in result.stdout for name in variables)
        print('通过：release 校验 dry-run 缺机外凭据明确失败（预期拒绝）', flush=True)
print('4 组签名审计通过；未生成密钥，未构建安装包', flush=True)
