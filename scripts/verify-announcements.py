#!/usr/bin/env python3
"""Real HTTP/PostgreSQL/Redis acceptance. Isolated users; no database cleanup or push."""
import datetime as dt
import json
import os
import pathlib
import secrets
import urllib.request
import urllib.error
from zoneinfo import ZoneInfo

BASE = os.environ.get('OA_API', 'http://127.0.0.1:8080/api')
ROOT = pathlib.Path(__file__).resolve().parents[1]
prefix = 'msg' + secrets.token_hex(4)
checks = []
INITIAL = 'Message@2026Initial'
PASSWORD = 'Message@2026Changed'

def call(path, method='GET', data=None, token=None, expect=200, headers=None):
    h = dict(headers or {})
    if token: h['Authorization'] = 'Bearer ' + token
    if data is not None and not isinstance(data, bytes):
        data = json.dumps(data).encode(); h['Content-Type'] = 'application/json'
    try:
        with urllib.request.urlopen(urllib.request.Request(BASE+path, data=data, headers=h, method=method), timeout=30) as r:
            status, raw = r.status, r.read()
    except urllib.error.HTTPError as e: status, raw = e.code, e.read()
    assert status == expect, f'{method} {path}: {status} != {expect}: {raw[:700]}'
    try: return json.loads(raw)
    except (ValueError, UnicodeDecodeError): return raw

def check(s):
    checks.append(s); print('通过：'+s, flush=True)

at = call('/auth/login','POST',{'login':'admin','password':os.environ.get('OA_ADMIN_PASSWORD','Admin@2026Verified')})['token']
root = call('/admin/departments',token=at)[0]['id']
def dept(name, parent): return call('/admin/departments','POST',{'name':prefix+name,'parentId':parent},at)['id']
parent = dept('部门',root); child = dept('下级',parent); outside = dept('其他',root)
def create(suffix, department, roles=None, manager=None, enabled=True):
    body = dict(employeeNo=prefix+suffix,name='消息验收'+suffix,roles=roles or ['EMPLOYEE'],departmentId=department,managerId=manager,enabled=enabled,password=INITIAL)
    uid=call('/admin/users','POST',body,at)['id']
    if not enabled: return uid, None, body
    token=call('/auth/login','POST',{'login':body['employeeNo'],'password':INITIAL})['token']
    call('/auth/password','POST',dict(oldPassword=INITIAL,newPassword=PASSWORD),token)
    token=call('/auth/login','POST',{'login':body['employeeNo'],'password':PASSWORD})['token']
    return uid,token,body
manager,mt,_=create('manager',parent,['EMPLOYEE','SUPERVISOR'])
# employee_no must be ASCII.
uid,et,ub=create('employee',parent,manager=manager)
cc,ct,cb=create('cc',child,manager=manager)
rogue,rt,_=create('other',outside,manager=manager)
disabled,_,_=create('disabled',child,enabled=False)
def upload(token=at):
    boundary='oa'+secrets.token_hex(8)
    raw=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="announcement.pdf"\r\nContent-Type: application/pdf\r\n\r\n%PDF-1.4\nacceptance\r\n--{boundary}--\r\n').encode()
    return call('/attachments','POST',raw,token,headers={'Content-Type':'multipart/form-data; boundary='+boundary})['id']
f1,f2=upload(),upload()
pub={'title':'公告 '+prefix,'body':'公告正文\n第二行','departmentId':parent,'attachmentIds':[f1,f2]}
call('/announcements','POST',pub,et,expect=403)
call('/announcements','POST',pub,expect=401)
call('/announcements','POST',{**pub,'title':' '},at,expect=400)
call('/announcements','POST',{**pub,'departmentId':999999999},at,expect=400)
call('/announcements','POST',{**pub,'attachmentIds':[f1,f1]},at,expect=400)
foreign=upload(et)
call('/announcements','POST',{**pub,'attachmentIds':[foreign]},at,expect=400)
aid=call('/announcements','POST',pub,at)['id']
path='/announcements/'+str(aid)
receipt=call(path+'/receipts',token=at)
assert {p['user_id'] for p in receipt['unread']}=={manager,uid,cc}
assert receipt['readCount']==0 and receipt['unreadCount']==3
call(path,token=rt,expect=403); call(path+'/read','POST',token=rt,expect=403)
call(path+'/receipts',token=et,expect=403)
call('/announcements?management=true',token=et,expect=403)
for file in [f1,f2]:
    call('/attachments/'+file,expect=401)
    call('/attachments/'+file,token=rt,expect=403)
    assert isinstance(call('/attachments/'+file,token=ct),bytes)
check('仅管理员发布；部门快照含下级、不含其他部门及停用账号；多附件鉴权与越权拒绝')
new,nt,nb=create('new',child,manager=manager)
# Move the existing recipient out and new employee into scope: snapshot stays fixed.
call('/admin/users/'+str(cc),'PUT',{**cb,'departmentId':outside,'password':None},at)
ct=call('/auth/login','POST',dict(login=cb['employeeNo'],password=PASSWORD))['token']
call(path,token=nt,expect=403)
assert call('/messages/summary',token=ct)['announcements']==1
assert len(call(path,token=ct)['attachments'])==2
first=call(path+'/receipts',token=at)['read'][0]
assert first['user_id']==cc and first['department_name']==prefix+'下级'
call(path+'/read','POST',token=ct)
assert call(path+'/receipts',token=at)['read'][0]['read_at']==first['read_at']
assert call('/messages/summary',token=ct)['announcements']==0
call(path+'/read','POST',token=et)
assert call(path+'/receipts',token=at)['readCount']==2
check('调部门/新入职不改变快照；打开详情与显式已读均记回执、重复已读保留首次时间')
allid=call('/announcements','POST',{**pub,'departmentId':None,'attachmentIds':[]},at)['id']
allreceipts=call('/announcements/'+str(allid)+'/receipts',token=at)
ids={p['user_id'] for p in allreceipts['unread']}
assert {uid,cc,rogue,new}.issubset(ids) and disabled not in ids
call(path+'/withdraw','POST',token=et,expect=403)
call(path+'/withdraw','POST',token=at)
call(path,token=ct,expect=403);call(path+'/read','POST',token=ct,expect=403)
for file in [f1,f2]: call('/attachments/'+file,token=ct,expect=403)
assert aid not in {x['id'] for x in call('/messages?category=announcements',token=ct)}
assert call(path+'/receipts',token=at)['readCount']==2
assert call(path,token=at)['withdrawn_at']
check('全员发布覆盖其他部门、排除停用；撤回隐藏正文/附件、保留管理员回执')

future=dt.datetime.now(ZoneInfo('Asia/Shanghai')).date()+dt.timedelta(days=5)
if future.month==12 and future.day>20: future=dt.date(future.year+1,1,5)
leave=next(t['id'] for t in call('/leave-types',token=et) if t['code']=='PERSONAL')
request_ids=[]
for n,kind in enumerate(['LEAVE','TRAVEL','OUTING','EXPENSE']):
    file=upload(et)  # Each application claims its own newly uploaded attachment.
    day=(future+dt.timedelta(days=n*2)).isoformat()
    body=dict(kind=kind,typeId=leave if kind=='LEAVE' else None,startAt=day+'T09:00:00',endAt=day+'T13:00:00',reason=prefix+kind,attachmentId=file,ccUserIds=[cc])
    if kind=='EXPENSE': body.update(amount=10,items=[dict(description='交通',amount=10)],attachmentIds=[file])
    if n==0:
        call('/requests','POST',{**body,'ccUserIds':[disabled]},et,expect=400)
        call('/requests','POST',{**body,'ccUserIds':[999999999]},et,expect=400)
        call('/requests','POST',{**body,'ccUserIds':[uid]},et,expect=400)
    token=at if n==3 else et
    if n==3:
        # Admin proxy submission uses their own uploaded receipts.
        proxyfile=upload(at); body.update(userId=uid,attachmentId=None,attachmentIds=[proxyfile])
    rid=call('/requests','POST',body,token)['id']; request_ids.append(rid)
    detail=call('/requests/'+str(rid),token=ct)
    assert [p['id'] for p in detail['cc']]==[cc]
    assert isinstance(call('/attachments/'+(proxyfile if n==3 else file),token=ct),bytes)
    call('/requests/'+str(rid),token=rt,expect=403)
    call('/requests/'+str(rid)+'/decision','POST',dict(approve=True),ct,expect=403)
    call('/requests/'+str(rid)+'/cancel','POST',token=ct,expect=403)
    call('/requests/'+str(rid)+'/payment','POST',dict(status='PAID'),ct,expect=403)
    call('/requests/'+str(rid)+'/decision','POST',dict(approve=n!=1,comment='验收'),mt)
assert {r['id'] for r in call('/messages?category=initiated',token=et)}==set(request_ids)
assert {r['id'] for r in call('/messages?category=cc',token=ct)}==set(request_ids)
assert call('/messages?category=cc',token=rt)==[]
assert call('/messages?category=pending',token=mt)==[]
assert call('/messages/summary',token=mt)['pending']==0
# create one current pending and check the current-step inbox and count
nextday=(future+dt.timedelta(days=12)).isoformat()
rid=call('/requests','POST',dict(kind='OUTING',startAt=nextday+'T09:00:00',endAt=nextday+'T13:00:00',reason='待办验收'),et)['id']
assert [r['id'] for r in call('/messages?category=pending',token=mt)]==[rid]
assert call('/messages/summary',token=mt)['pending']==1
assert call('/messages?category=pending',token=ct)==[]
check('四类申请支持抄送和管理员代提交；抄送可看详情附件但不可审批/撤回/支付；分类与当前审批待办一致')
notifications=call('/messages?category=notifications',token=ct)
assert len(notifications)==8 and all(n['request_id'] in request_ids for n in notifications)
assert call('/messages/summary',token=ct)['notifications']==8
nid=notifications[0]['id']
call('/notifications/'+str(nid)+'/read','POST',token=rt,expect=403)
call('/notifications/'+str(nid)+'/read','POST',token=ct)
assert call('/messages/summary',token=ct)['notifications']==7
assert any(n['id']==nid and n['read_at'] for n in call('/notifications',token=ct))
call('/messages?category=bad',token=ct,expect=400)
call('/messages/summary',expect=401)
assert call('/messages?category=cc&offset=200',token=ct)==[]
audit=call('/admin/audit',token=at)
assert any(x['action']=='PUBLISH_ANNOUNCEMENT' and x['target']==str(aid) for x in audit)
assert any(x['action']=='WITHDRAW_ANNOUNCEMENT' and x['target']==str(aid) for x in audit)
check('提交及终审通知抄送人；系统通知旧接口兼容、未读数准确、分页/无效类别/未登录/审计验证')
result=dict(prefix=prefix,checks=checks,employeeLogin=ub['employeeNo'],ccLogin=cb['employeeNo'],password=PASSWORD,announcementId=allid,requestIds=request_ids)
(ROOT/'.runtime/announcement-verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
print(f'公告消息验收通过：{len(checks)} 组')
