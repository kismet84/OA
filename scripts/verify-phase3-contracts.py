#!/usr/bin/env python3
"""合同真实 HTTP 验收；创建独立测试数据，保留已有记录。"""
import base64
import json
import os
import pathlib
import secrets
import urllib.error
import urllib.request

BASE = os.environ.get('OA_API', 'http://127.0.0.1:8080/api')
ROOT = pathlib.Path(__file__).resolve().parents[1]
checks = []

def call(path, method='GET', data=None, token=None, expect=200):
    headers = {'Authorization': 'Bearer '+token} if token else {}
    if data is not None:
        data = json.dumps(data).encode()
        headers['Content-Type'] = 'application/json'
    try:
        with urllib.request.urlopen(urllib.request.Request(BASE+path, data=data, headers=headers, method=method), timeout=40) as r:
            status, raw = r.status, r.read()
    except urllib.error.HTTPError as e:
        status, raw = e.code, e.read()
    assert status == expect, (method, path, status, expect, raw[:500])
    return json.loads(raw)

def check(message):
    checks.append(message)
    print('通过：'+message, flush=True)

at = call('/auth/login', 'POST', dict(login='admin', password=os.environ.get('OA_ADMIN_PASSWORD', 'Admin@2026Verified')))['token']
prefix = 'contract'+secrets.token_hex(4)
dep = call('/admin/departments', 'POST', dict(name=prefix), at)['id']

def user(suffix, roles=None, manager=None):
    body = dict(employeeNo=prefix+suffix, name='合同验收'+suffix, departmentId=dep, enabled=True, roles=roles or ['EMPLOYEE'], managerId=manager, password='Asset@2026Initial')
    uid = call('/admin/users', 'POST', body, at)['id']
    t = call('/auth/login', 'POST', dict(login=body['employeeNo'], password=body['password']))['token']
    call('/auth/password', 'POST', dict(oldPassword=body['password'], newPassword='Asset@2026Changed'), t)
    t = call('/auth/login', 'POST', dict(login=body['employeeNo'], password='Asset@2026Changed'))['token']
    return dict(id=uid, token=t, body=body, login=body['employeeNo'])

head = user('head', ['EMPLOYEE', 'DEPT_HEAD'])
manager = user('manager', ['EMPLOYEE', 'SUPERVISOR'])
call('/admin/departments/'+str(dep),'PUT',dict(name=prefix,headId=head['id']),at)
owner = user('owner', manager=manager['id'])
other = user('other')
finance = user('finance', ['EMPLOYEE', 'FINANCE'])
ut, mt, ot, ft = (u['token'] for u in [owner, manager, other, finance])

from datetime import datetime, timedelta
from zoneinfo import ZoneInfo
import subprocess

def sql(q):
    env = dict(os.environ, PGPASSWORD='oa-local-only')
    return subprocess.check_output(['psql','-h','127.0.0.1','-p','55432','-U','oa','-d','oa','-tAc',q],env=env,text=True).strip()
def raw(path, token, method='GET', data=None):
    headers={'Authorization':'Bearer '+token}
    if data is not None: headers['Content-Type']='multipart/form-data; boundary=contractboundary'
    with urllib.request.urlopen(urllib.request.Request(BASE+path,headers=headers,data=data,method=method)) as r: return r.read()
def upload():
    data=b'--contractboundary\r\nContent-Disposition: form-data; name="file"; filename="sealed.pdf"\r\nContent-Type: application/pdf\r\n\r\n%PDF-1.4\ncontract scan\r\n--contractboundary--\r\n'
    return json.loads(raw('/attachments',ut,'POST',data))['id']
today=datetime.now(ZoneInfo('Asia/Shanghai')).date()
body=dict(name='合同专项验收',type='SERVICE',counterparty=prefix,amount=50000,signedDate=str(today),effectiveDate=str(today),expiryDate=None)
def create(**patch): return call('/contracts','POST',body|patch,ut)['id']
def detail(i,t=ut): return call('/contracts/'+str(i),token=t)
def act(i,a,t=ut,data=None,expect=200): return call(f'/contracts/{i}/{a}','POST',data,t,expect)
def approve(i,t): return act(i,'approve',t,dict(approved=True))
def pending(t): return call('/messages/summary',token=t)['pending']
for path,method,data in [('/contracts','GET',None),('/contracts','POST',body),('/contracts/1/approve','POST',{}),('/contracts/export','GET',None),('/contracts/summary','GET',None)]: call(path,method,data,expect=401)
r=create(code=prefix)
call('/contracts','POST',body|dict(code=prefix),ut,409)
call(f'/contracts/{r}',token=ot,expect=403)
assert not call('/contracts?counterparty='+prefix,token=ot)
call('/contracts/export',token=ut,expect=403)
call('/contracts?limit=201',token=ut,expect=400)
check('未登录401、越权403且不泄露列表、编号重复409、分页上限和导出权限')
call('/requests','POST',dict(kind='OUTING',startAt='2032-03-01T09:00:00',endAt='2032-03-01T18:00:00',reason='合同并存审批'),ut)
call('/tasks','POST',dict(title=prefix+'并存任务',assigneeIds=[manager['id']]),ut)
base=pending(mt)
assert base==2
before=call('/workbench/summary?from=2032-03-01&to=2032-03-31',token=at)
act(r,'submit'); assert pending(mt)==base+1
items=call('/messages?category=pending',token=mt)
assert {x['item_type'] for x in items}=={'REQUEST','TASK','CONTRACT'}
call(f'/contracts/{r}','PUT',body|dict(amount=1),ut,409)
act(r,'approve',ot,dict(approved=True),403)
assert len(detail(r)['steps'])==2
approve(r,mt);assert pending(mt)==base
approve(r,ft);assert detail(r)['status']=='TO_ARCHIVE'
f=upload();act(r,'archive',data=dict(attachmentIds=[f]));assert detail(r)['status']=='ACTIVE'
assert raw(f'/contracts/{r}/files/{f}',mt).startswith(b'%PDF')
call(f'/contracts/{r}/files/{f}',token=ot,expect=403)
call('/attachments/'+f,token=ot,expect=403)
assert raw('/contracts/export?counterparty='+prefix,at).startswith(b'\xef\xbb\xbf')
check('50000不加签、直属审批与终审、审批中锁定、归档与附件权限、BOM导出、原待办仍在')
h=create(amount=50000.01);act(h,'submit');assert len(detail(h)['steps'])==3
approve(h,mt);assert detail(h,head['token'])['canApprove'];approve(h,head['token']);approve(h,ft)
call('/admin/departments/'+str(dep),'PUT',dict(name=prefix,headId=head['id']),at)
# Same supervisor and department head must appear only once.
same=user('same',manager=head['id'])
x=call('/contracts','POST',body|dict(amount=60000),same['token'])['id']
ut_saved=ut
ut=same['token']
# Helpers with defaults use original owner token, so this branch supplies tokens explicitly.
act(x,'submit',ut);assert len(detail(x,ut)['steps'])==2
act(x,'approve',head['token'],dict(approved=False,note=''),400)
act(x,'approve',head['token'],dict(approved=False,note='补全履约说明'));assert detail(x,ut)['status']=='REJECTED'
call(f'/contracts/{x}','PUT',body|dict(performance='补全说明'),ut);act(x,'submit',ut);act(x,'withdraw',ut);assert detail(x,ut)['status']=='WITHDRAWN'
call(f'/contracts/{x}','PUT',body,ut);act(x,'submit',ut);approve(x,head['token']);approve(x,at)
ut=ut_saved
check('超过阈值走部门负责人、上级与负责人相同去重、驳回必填、驳回和撤回可修改再提交')
# Use SQL only to advance the fixture expiry date; all business operations above use real HTTP.
sql(f"UPDATE contracts SET expiry_date=localtimestamp::date+5 WHERE id={r}")
for _ in range(2): call('/messages/summary',token=ut)
assert sql(f"SELECT count(*) FROM contract_reminders WHERE contract_id={r}")=='2'
notices=call('/messages?category=notifications',token=ut)
assert len([n for n in notices if n.get('contract_id')==r and '到期提醒' in n['message']])==2
sql(f"UPDATE contracts SET expiry_date=localtimestamp::date-1 WHERE id={r}")
assert detail(r)['status']=='EXPIRED'
assert any(z['id']==r for z in call('/contracts?status=EXPIRED',token=ut))
act(r,'terminate',data=dict(reason=''),expect=400);act(r,'terminate',data=dict(reason='履约结束'));assert detail(r)['status']=='TERMINATED'
assert call('/workbench/summary?from=2032-03-01&to=2032-03-31',token=at)==before
check('北京时间到期计算、30/7提醒各一次、终止原因必填、六类工作台汇总不变')
# Attachments cannot be reclassified across business modules and bypass original permissions.
png=base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a2ioAAAAASUVORK5CYII=')
def admin_file():
    data=b'--contractboundary\r\nContent-Disposition: form-data; name="file"; filename="isolation.png"\r\nContent-Type: image/png\r\n\r\n'+png+b'\r\n--contractboundary--\r\n'
    return json.loads(raw('/attachments',at,'POST',data))['id']
cf=admin_file()
call('/contracts','POST',body|dict(attachmentIds=[cf]),at)
publication=dict(title=prefix+'附件隔离',category='OTHER',departmentId=dep,allowDownload=False,attachmentIds=[cf])
call('/documents','POST',publication,at,400)
df=admin_file()
call('/documents','POST',publication|dict(attachmentIds=[df]),at)
call('/contracts','POST',body|dict(attachmentIds=[df]),at,400)
shared=upload()
call('/requests','POST',dict(kind='OUTING',startAt='2033-03-01T09:00:00',endAt='2033-03-01T18:00:00',reason='附件隔离',attachmentId=shared),ut)
call('/contracts','POST',body|dict(attachmentIds=[shared]),ut,400)
check('合同与文档、审批附件不可交叉绑定，保留原业务下载授权')
draft=create();call(f'/contracts/{draft}','DELETE',token=ut)
assert int(sql("SELECT count(*) FROM audit_logs WHERE action IN ('EXPORT_CONTRACTS','DOWNLOAD_CONTRACT_FILE')"))>=2
(ROOT/'.runtime/contracts-http-result.json').write_text(json.dumps(dict(prefix=prefix,checks=checks),ensure_ascii=False,indent=2))
print('合同 HTTP 验收全部通过',flush=True)
