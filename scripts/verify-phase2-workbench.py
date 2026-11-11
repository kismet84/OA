#!/usr/bin/env python3
"""工作台真实 HTTP 验收；仅创建独立测试组织和合成业务记录，不修改既有数据。"""
import datetime as dt
import base64
import json
import os
import pathlib
import secrets
import subprocess
import urllib.request
import urllib.error
from zoneinfo import ZoneInfo
ROOT=pathlib.Path(__file__).resolve().parents[1]
BASE=os.environ.get('OA_API','http://127.0.0.1:8080/api')
def call(path,method='GET',data=None,token=None,expect=200):
    headers={'Authorization':'Bearer '+token} if token else {}
    if data is not None: data=json.dumps(data).encode();headers['Content-Type']='application/json'
    try:
        with urllib.request.urlopen(urllib.request.Request(BASE+path,data=data,headers=headers,method=method),timeout=60) as r: status,raw=r.status,r.read()
    except urllib.error.HTTPError as e: status,raw=e.code,e.read()
    assert status==expect,(path,status,expect,raw[:900])
    return json.loads(raw)
def sql(s):
    env=dict(os.environ,PGPASSWORD=os.environ.get('DATABASE_PASSWORD','oa-local-only'))
    return subprocess.check_output(['psql','-X','-h','127.0.0.1','-p','55432','-U','oa','-d','oa','-At','-v','ON_ERROR_STOP=1','-c',s],env=env,text=True).strip()
at=call('/auth/login','POST',dict(login='admin',password=os.environ.get('OA_ADMIN_PASSWORD','Admin@2026Verified')))['token']
prefix='wb'+secrets.token_hex(4)
def dep(s,parent=None):return call('/admin/departments','POST',dict(name=prefix+s,parentId=parent),at)['id']
parent=dep('部门');child=dep('下级',parent);outside=dep('其他')
users={}
def user(s,department,roles=['EMPLOYEE'],manager=None,enabled=True):
    body=dict(employeeNo=prefix+s,name='汇总验收'+s,departmentId=department,managerId=manager,roles=roles,enabled=enabled,password='Bench@2026Initial')
    uid=call('/admin/users','POST',body,at)['id'];token=None
    if enabled:
        token=call('/auth/login','POST',dict(login=body['employeeNo'],password=body['password']))['token']
        call('/auth/password','POST',dict(oldPassword=body['password'],newPassword='Bench@2026Changed'),token)
        token=call('/auth/login','POST',dict(login=body['employeeNo'],password='Bench@2026Changed'))['token']
    users[s]=dict(id=uid,token=token,login=body['employeeNo'])
    return uid,token
head,ht=user('head',parent,['EMPLOYEE','DEPT_HEAD'])
manager,mt=user('manager',parent,['EMPLOYEE','SUPERVISOR'])
uid,ut=user('employee',child,manager=manager)
grand,gt=user('grandchild',child,manager=head)
other,ot=user('outside',outside)
disabled,_=user('disabled',child,manager=manager,enabled=False)
finance,ft=user('finance',outside,['FINANCE','EMPLOYEE'])
call('/admin/departments/'+str(parent),'PUT',dict(name=prefix+'部门',headId=head),at)
now=dt.datetime.now(ZoneInfo('Asia/Shanghai')).replace(tzinfo=None);today=now.date()
start=today-dt.timedelta(days=10);end=today+dt.timedelta(days=10)
q=f'from={start}&to={end}'
def summary(token=ut,extra=''):return call('/workbench/summary?'+q+extra,token=token)
call('/workbench/summary',expect=401);call('/workbench/members',expect=401)
for bad in ['from=bad','from=2026-02-30','from=0000-01-01&to=0000-01-02','from=%2B999999999-12-31&to=%2B999999999-12-31','from=2026-11-12&to=2026-11-11','from=2025-01-01&to=2026-01-02','scope=bad']:
    call('/workbench/summary?'+bad,token=ut,expect=400)
for extra in ['&scope=team',f'&userId={other}',f'&departmentId={outside}']:
    call('/workbench/summary?'+q+extra,token=ut,expect=403)
for t in [mt,ht]:call('/workbench/summary?'+q+f'&departmentId={outside}&scope=team',token=t,expect=403)
assert call('/workbench/summary',token=ut)['from']==str(today.replace(day=1))
call('/workbench/summary?from=2024-01-01&to=2024-12-31',token=ut)  # inclusive 366 accepted
call('/workbench/members?offset=-1',token=ut,expect=400)
call('/workbench/summary?'+q+'&scope=team&departmentId=-1',token=at,expect=400)
# Shanghai timestamp columns: explicit dates avoid host timezone effects.
sql(f"INSERT INTO punches(user_id,punched_at,kind) SELECT {uid},'{today} 09:00'::timestamp + n*interval '1 second','NORMAL' FROM generate_series(1,251) n; INSERT INTO punches(user_id,punched_at,kind) VALUES({uid},'{today} 14:00','FIELD'),({disabled},'{today} 09:00','NORMAL'),({other},'{today} 09:00','NORMAL');")
leave_type=call('/leave-types',token=ut)[0]['id']
def req(kind,status='APPROVED',owner=uid,start_at=None,end_at=None,created=None,acted=None):
    start_at=start_at or f'{today} 09:00';end_at=end_at or f'{today} 18:00'
    rid=int(sql(f"INSERT INTO requests(user_id,kind,status,type_id,start_at,end_at,days,reason,created_at,overtime_ratio,credited_days,punch_kind) VALUES({owner},'{kind}','{status}',{leave_type if kind=='LEAVE' else 'NULL'},'{start_at}',{'NULL' if kind in ['CORRECTION','EXPENSE'] else chr(39)+end_at+chr(39)},1,'{prefix}', '{created or (now-dt.timedelta(days=4)).isoformat()}',1.5,1.5,{'NULL' if kind!='CORRECTION' else chr(39)+'NORMAL'+chr(39)}) RETURNING id").splitlines()[0])
    sql(f"INSERT INTO approval_steps(request_id,step,approver_id,status,acted_at) VALUES({rid},1,{manager},'{status if status in ['APPROVED','REJECTED'] else 'PENDING'}',{'NULL' if status not in ['APPROVED','REJECTED'] else chr(39)+(acted or (now-dt.timedelta(days=2)).isoformat())+chr(39)})")
    return rid
# Both edges are half days; only the selected first day contributes 0.5.
req('LEAVE',start_at=f'{start-dt.timedelta(days=1)} 13:00',end_at=f'{start} 13:00')
for kind in ['TRAVEL','OUTING','OVERTIME','EXPENSE','CORRECTION']:req(kind)
req('LEAVE','REJECTED');req('LEAVE','CANCELLED');req('LEAVE','WITHDRAWN')
for hours in [12,48,96]:req('TRAVEL','PENDING',created=(now-dt.timedelta(hours=hours)).isoformat())
req('EXPENSE',owner=disabled)
# Approved corrections are written once to punches, as in WorkflowService.decide.
correction=int(sql(f"SELECT id FROM requests WHERE reason='{prefix}' AND kind='CORRECTION'"))
sql(f"INSERT INTO punches(user_id,punched_at,kind,correction_request_id) VALUES({uid},'{today} 08:00','NORMAL',{correction})")
before=call('/messages/summary',token=ut)
s=summary();a=s['attendance'];p=s['approvals']
assert (a['normalPunches'],a['fieldPunches'],a['recordedDays'],a['approvedCorrections'])==(252,1,1,1),a
assert (a['leaveDays'],a['travelDays'],a['outingDays'],a['overtimeDays'],a['overtimeCreditedDays'])==(.5,1,1,1,1.5),a
assert p['completed']==7 and p['averageHours']==48,p
assert p['waiting']==dict(total=3,withinOneDay=1,oneToThreeDays=1,overThreeDays=1),p
assert p['initiatedPending']==3 and p['pendingForMe']==0
assert len(call('/punches',token=ut))==200
assert summary(at,f'&userId={manager}')['approvals']['completed']==0  # selected person cannot expand scope through their approval assignments
assert summary(mt)['approvals']['pendingForMe']==3
assert summary(mt,'&scope=team')['people']==2
assert summary(ht,'&scope=team')['people']==4
assert summary(at,f'&scope=team&departmentId={parent}')['people']==4
assert summary(ft,f'&scope=team&departmentId={parent}')['attendance']['normalPunches']==252
assert summary(at,f'&scope=team&departmentId={parent}')['approvals']['completed']==8 # disabled history retained
assert summary(mt,f'&scope=team&userId={uid}')['attendance']==a
call('/workbench/summary?'+q+f'&scope=team&userId={grand}',token=mt,expect=403)
call('/punches?userId='+str(other),token=mt,expect=403)
assert len(call('/punches?userId='+str(uid),token=mt))==200
assert {u['id'] for u in call('/workbench/members',token=mt)}=={manager,uid}
assert summary(ot)['attendance']['normalPunches']==1
assert call(f'/workbench/summary?from={today+dt.timedelta(days=1)}&to={end}',token=ut)['attendance']['normalPunches']==0
assert call('/messages/summary',token=ut)==before
# Existing meeting/task/document records do not join any workbench query; categories remain unchanged.
assert set(r['kind'] for r in p['byType'])=={'LEAVE','TRAVEL','OUTING','EXPENSE','OVERTIME','CORRECTION'}
# Create genuine non-approval work through existing APIs; it must not affect approval metrics.
call('/tasks','POST',dict(title=prefix+'待办',description='汇总隔离验收',dueAt=str(end),assigneeIds=[uid]),mt)
call('/meetings','POST',dict(title=prefix+'会议',startAt=f'{end}T09:00:00',endAt=f'{end}T10:00:00',attendeeIds=[uid],description='汇总隔离验收'),mt)
boundary='workbenchUpload'
png=base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jWTkAAAAASUVORK5CYII=')
raw=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="workbench.png"\r\nContent-Type: image/png\r\n\r\n').encode()+png+f'\r\n--{boundary}--\r\n'.encode()
with urllib.request.urlopen(urllib.request.Request(BASE+'/attachments',data=raw,headers={'Authorization':'Bearer '+at,'Content-Type':'multipart/form-data; boundary='+boundary}),timeout=60) as response:
    attachment=json.loads(response.read())['id']
call('/documents','POST',dict(title=prefix+'文档',description='汇总隔离验收',category='OTHER',departmentId=child,attachmentIds=[attachment],allowDownload=False),at)
assert summary()['approvals']==p
assert call('/messages/summary',token=ut)['pending']>before['pending']
result=dict(users=users,parent=parent,child=child,outside=outside,fromDate=str(start),toDate=str(end),summary=s)
(ROOT/'.runtime/workbench-fixture.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
print('通过：登录、403、日期、直属/部门递归/财务权限、停用历史、超过200条、半天裁剪、六类终审48小时、实时等待分段、补卡去重及消息汇总隔离')
