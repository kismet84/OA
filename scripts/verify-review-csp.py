#!/usr/bin/env python3
"""Assert the shipped Nginx CSP; optionally verify a real local TLS response."""
import pathlib, re, ssl, sys, urllib.request
root=pathlib.Path(__file__).resolve().parents[1]
config=(root/'deploy/nginx/oa.conf').read_text()
policy=re.search(r'add_header Content-Security-Policy "([^"]+)" always;',config).group(1)
def check(value):
    directives={part.split()[0]:part.split()[1:] for part in value.split(';') if part.strip()}
    assert set(directives['img-src'])=={"'self'",'data:','blob:'}
    assert set(directives['frame-src'])=={"'self'",'blob:'}
    assert directives['object-src']==["'none'"]
    assert directives['script-src']==["'self'"]
    assert directives['frame-ancestors']==["'none'"]
    assert 'unsafe-eval' not in value and 'https:' not in value and '*' not in value
    # Actual consumers use img/iframe/download links, no worker/object permissions needed.
    for filename in ['Documents.vue','Contracts.vue','api.ts']:
        source=(root/'admin/src'/filename).read_text()
        assert not re.search(r'new\s+(?:Shared)?Worker|<object|<embed',source)
check(policy)
print('通过：发布配置 CSP img/frame blob、data 图片、object 禁止、script 不放宽')
if len(sys.argv)>1:
    with urllib.request.urlopen(sys.argv[1],context=ssl._create_unverified_context()) as r:
        check(r.headers['Content-Security-Policy'])
    print('通过：真实 Nginx TLS 响应头 CSP 与预览要求一致')
