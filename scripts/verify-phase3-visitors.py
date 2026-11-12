#!/usr/bin/env python3
"""访客真实HTTP验收；只在开发库添加独立测试数据，复用token避免反复登录。"""
import concurrent.futures
import json
import os
import pathlib
import secrets
import subprocess
import urllib.error
import urllib.request
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = os.environ.get('OA_API', 'http://127.0.0.1:8080/api')

def call(path, method='GET', data=None, token=None, expect=200, raw=False):
    headers = {'Authorization': 'Bearer '+token} if token else {}
    if data is not None:
        data = json.dumps(data).encode()
        headers['Content-Type'] = 'application/json'
    try:
        with urllib.request.urlopen(urllib.request.Request(BASE+path, data=data, headers=headers, method=method), timeout=40) as r:
            status, body = r.status, r.read()
    except urllib.error.HTTPError as e:
        status, body = e.code, e.read()
    assert status == expect, (path, status, expect, body[:500])
    return body if raw else json.loads(body)

def sql(q):
    return subprocess.check_output(['psql', '-h', '127.0.0.1', '-p', '55432', '-U', 'oa', '-d', 'oa', '-tAc', q], env=dict(os.environ, PGPASSWORD='oa-local-only'), text=True).strip()

at = call('/auth/login','POST',dict(login='admin',password=os.environ.get('OA_ADMIN_PASSWORD','Admin@2026Verified')))['token']
prefix = 'visitor'+secrets.token_hex(4)
dep = call('/admin/departments','POST',dict(name=prefix),at)['id']
def user(suffix, manager=None, roles=None):
    login = prefix+suffix
    uid = call('/admin/users','POST',dict(employeeNo=login,name='访客验收'+suffix,departmentId=dep,enabled=True,managerId=manager,roles=roles or ['EMPLOYEE'],password='Visitor@2026Initial'),at)['id']
    first = call('/auth/login','POST',dict(login=login,password='Visitor@2026Initial'))['token']
    call('/auth/password','POST',dict(oldPassword='Visitor@2026Initial',newPassword='Visitor@2026Changed'),first)
    return dict(id=uid,login=login,token=call('/auth/login','POST',dict(login=login,password='Visitor@2026Changed'))['token'])
manager=user('manager',roles=['EMPLOYEE','SUPERVISOR'])
owner=user('owner',manager['id'])
other=user('other')
ut,mt,ot = [x['token'] for x in [owner,manager,other]]
now=datetime.now(ZoneInfo('Asia/Shanghai')).replace(tzinfo=None, microsecond=0)
body=dict(name=prefix,phone='13812341234',company=prefix,purpose='专项来访验收',people=2,companions='随行甲',startAt=now.isoformat(),endAt=(now+timedelta(hours=2)).isoformat())
def create(t=ut, **patch): return call('/visitors','POST',body|patch,t)
def act(i,a,t=at,expect=200): return call(f'/visitors/{i}/{a}','POST',token=t,expect=expect)
def pending(t): return call('/messages/summary',token=t)['pending']
def query(t=ut,q=''): return call('/visitors?keyword='+prefix+q,token=t)
for path,method,data in [('/visitors','GET',None),('/visitors','POST',body),('/visitors/summary','GET',None),('/visitors/hosts','GET',None),('/visitors/export','GET',None),('/visitors/check-in','POST',dict(code='000000')),('/visitors/temporary','POST',body),('/visitors/1/cancel','POST',None),('/visitors/1/check-out','POST',None)]: call(path,method,data,expect=401)
base={t:pending(t) for t in [ut,mt,ot,at]}
before=call('/workbench/summary?from=2032-03-01&to=2032-03-31',token=ut)
r=create(); i=r['id']
assert len(r['code'])==6 and r['code'].isdigit() and r['phone']==body['phone']
call(f'/visitors/{i}',token=ot,expect=403)
call('/visitors?hostId='+str(owner['id']),token=ot,expect=403)
assert query(ot)==[]
assert query()[0]['phone']=='138****1234'
assert call(f'/visitors/{i}',token=mt)['phone']=='138****1234'
assert call(f'/visitors/{i}',token=at)['phone']==body['phone']
assert query(mt)[0]['id']==i
for t in [ut,mt,ot]:
    call('/visitors/export',token=t,expect=403)
    call('/visitors/hosts',token=t,expect=403)
    call('/visitors/temporary','POST',body|dict(hostId=owner['id']),t,403)
    act(i,'check-in',t,403);act(i,'check-out',t,403)
create(ut,hostId=other['id']) if False else call('/visitors','POST',body|dict(hostId=other['id']),ut,403)
for q in ['&limit=201','&offset=-1','&status=BAD','&from=2032-02-01&to=2032-01-01']:
    call('/visitors?keyword='+prefix+q,token=ut,expect=400)
for patch in [dict(people=0),dict(phone='bad'),dict(endAt=body['startAt'])]:call('/visitors','POST',body|patch,ut,400)
print('通过：未登录401、可见范围与越权403、列表脱敏和详情权限、参数400、仅ADMIN前台与导出',flush=True)
act(i,'check-out',expect=400)
assert call('/visitors/check-in','POST',dict(code=r['code']),at)['status']=='ARRIVED'
act(i,'check-in',expect=400)
call('/visitors/check-in','POST',dict(code=r['code']),at,400)
notices=call('/messages?category=notifications',token=ut)
assert sum(prefix in n['message'] for n in notices)==1
assert sql(f'SELECT count(*) FROM notifications WHERE visitor_id={i}')=='1'
assert act(i,'check-out')['status']=='LEFT'
act(i,'check-out',expect=400)
c=create();act(c['id'],'cancel',mt,403);assert act(c['id'],'cancel',ut)['status']=='CANCELLED';act(c['id'],'check-in',expect=400)
e=create();sql(f"UPDATE visitors SET start_at=localtimestamp-interval '3 days',end_at=localtimestamp-interval '2 days' WHERE id={e['id']}")
assert call('/visitors/'+str(e['id']),token=ut)['status']=='EXPIRED'
assert any(x['id']==e['id'] for x in query(q='&status=EXPIRED'))
act(e['id'],'check-in',expect=400);act(e['id'],'cancel',ut,400)
print('通过：凭码签到签离、非法流转400、取消、动态过期、同一来访签到通知仅一次',flush=True)
temp=call('/visitors/temporary','POST',body|dict(hostId=owner['id']),at)
assert temp['status']=='ARRIVED' and temp['checked_in_at'] and temp['temporary']
assert call('/visitors/summary',token=ut)['present']==2
assert act(temp['id'],'check-out')['status']=='LEFT'
# Concurrency exercises the database uniqueness slot and cross-request allocation lock.
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
    records=list(pool.map(lambda _:create(),range(16)))
assert len({x['code'] for x in records})==16
assert sql('SELECT count(*) FROM (SELECT active_code FROM visitors WHERE active_code IS NOT NULL GROUP BY active_code HAVING count(*)>1) x')=='0'
assert sql(f"SELECT count(*) FROM visitors WHERE id IN ({i},{c['id']},{e['id']},{temp['id']}) AND active_code IS NOT NULL")=='0'
# Force a valid slot to use a historical code: inactive records must not occupy it.
slot=records[0]['id'];sql(f"UPDATE visitors SET code='{e['code']}',active_code='{e['code']}' WHERE id={slot}")
assert call('/visitors/check-in','POST',dict(code=e['code']),at)['id']==slot
act(slot,'check-out')
csv=call('/visitors/export?keyword='+prefix,token=at,raw=True)
assert csv.startswith(b'\xef\xbb\xbf') and body['phone'].encode() in csv
assert sql("SELECT count(*) FROM audit_logs WHERE action='EXPORT_VISITORS'")!='0'
for x in records[1:]:act(x['id'],'cancel',ut)
assert {t:pending(t) for t in base}==base
assert call('/workbench/summary?from=2032-03-01&to=2032-03-31',token=ut)==before
assert all(x.get('item_type')!='VISITOR' for x in call('/messages?category=pending',token=mt))
print('通过：临时登记、当前在场人数、16笔并发码唯一、过期码可复用、BOM导出审计、原待办及六类审批汇总不变',flush=True)
(ROOT/'.runtime/visitors-http-result.json').write_text(json.dumps(dict(prefix=prefix,owner=owner,adminToken=at,checks=3),ensure_ascii=False,indent=2))
os.chmod(ROOT/'.runtime/visitors-http-result.json',0o600)
print('访客HTTP验收全部通过',flush=True)
