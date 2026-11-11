#!/usr/bin/env python3
"""待办任务真实 HTTP 验收；使用独立账号，保留审计和历史，不更改现有配置。"""
import concurrent.futures
import datetime as dt
import json
import os
import pathlib
import secrets
import urllib.error
import urllib.request
from zoneinfo import ZoneInfo

BASE = os.environ.get('OA_API', 'http://127.0.0.1:8080/api')
ROOT = pathlib.Path(__file__).resolve().parents[1]
checks = []

def call(path, method='GET', data=None, token=None, expect=200):
    headers = {'Authorization': 'Bearer ' + token} if token else {}
    if data is not None:
        data = json.dumps(data).encode()
        headers['Content-Type'] = 'application/json'
    try:
        with urllib.request.urlopen(urllib.request.Request(BASE+path, data=data, headers=headers, method=method), timeout=30) as r:
            status, raw = r.status, r.read()
    except urllib.error.HTTPError as e:
        status, raw = e.code, e.read()
    assert status == expect, (path, status, expect, raw[:700])
    return json.loads(raw)

def check(label):
    checks.append(label)
    print('通过：'+label, flush=True)

at = call('/auth/login', 'POST', dict(login='admin', password=os.environ.get('OA_ADMIN_PASSWORD', 'Admin@2026Verified')))['token']
prefix = 'todo'+secrets.token_hex(4)
dep = call('/admin/departments', 'POST', dict(name=prefix), at)['id']
def employee(suffix, manager=None, roles=None):
    no = prefix+suffix
    uid = call('/admin/users', 'POST', dict(employeeNo=no, name=no, departmentId=dep, managerId=manager, roles=roles or ['EMPLOYEE'], enabled=True, password='Todo@2026Initial'), at)['id']
    t = call('/auth/login', 'POST', dict(login=no, password='Todo@2026Initial'))['token']
    call('/auth/password', 'POST', dict(oldPassword='Todo@2026Initial', newPassword='Todo@2026Changed'), t)
    return uid, call('/auth/login', 'POST', dict(login=no, password='Todo@2026Changed'))['token']
gid, gt = employee('guest', roles=['EMPLOYEE','SUPERVISOR'])
uid, ut = employee('owner', gid)
oid, ot = employee('other')
base = dict(title=prefix+'任务', description='纯文本任务说明', dueAt='2001-01-01', assigneeIds=[gid,gid])
for path in ['/tasks','/tasks/1','/messages?category=pending','/messages/summary']:
    call(path, expect=401)
for path,method,data in [('/tasks','POST',base),('/tasks/1','PUT',base),('/tasks/1/status','POST',dict(status='COMPLETED')),('/tasks/1/followups','POST',dict(content='跟进'))]:
    call(path,method,data,expect=401)
check('所有任务接口及消息中心未登录拒绝')
for patch in [dict(title=' '),dict(assigneeIds=[]),dict(assigneeIds=[None]),dict(assigneeIds=[-1]),dict(dueAt='错误'),dict(title='长'*201),dict(description='长'*5001),dict(assigneeIds=list(range(1,202)))]:
    call('/tasks','POST',dict(base,**patch),ut,400)
# Self-assignment has one notification, even when its input repeats the creator.
selfid = call('/tasks','POST',dict(base,assigneeIds=[uid,uid]),ut)['id']
assert len([n for n in call('/messages?category=notifications',token=ut) if n.get('task_id')==selfid])==1
call(f'/tasks/{selfid}/status','POST',dict(status='CANCELLED'),ut)
mid = call('/tasks','POST',base,ut)['id']
def detail(token=ut): return call(f'/tasks/{mid}',token=token)
def state(s,token=ut,expect=200,**kw): return call(f'/tasks/{mid}/status','POST',dict(status=s,**kw),token,expect)
def pending(token=gt): return call('/messages?category=pending',token=token)
def count(token=gt): return call('/messages/summary',token=token)['pending']
assert detail()['due_at']=='2001-01-01T23:59:59' and detail()['overdue']
assert len(detail()['assignees'])==1
assert count()==1 and any(r.get('task_id')==mid and r['item_type']=='TASK' for r in pending())
assert len([n for n in call('/messages?category=notifications',token=gt) if n.get('task_id')==mid])==1
assert not any(n.get('task_id')==mid for n in call('/messages?category=notifications',token=ut))
assert any(r['id']==mid for r in call('/tasks?scope=initiated',token=ut))
check('必填/长度/在职人员校验；去重指派、上海日期、过期、系统通知和待办汇总')
for method,path,data in [('GET',f'/tasks/{mid}',None),('PUT',f'/tasks/{mid}',base),('POST',f'/tasks/{mid}/followups',dict(content='窃取')),('POST',f'/tasks/{mid}/status',dict(status='COMPLETED'))]:
    call(path,method,data,ot,403)
assert call('/tasks',token=ot)==[]
call(f'/tasks/{mid}','PUT',base,gt,403)
state('CANCELLED',gt,403)
call(f'/tasks/{mid}/followups','POST',dict(content=' '),gt,400)
call(f'/tasks/{mid}/followups','POST',dict(content='负责人跟进'),gt)
state('IN_PROGRESS',gt,content='开始处理')
assert [f['content'] for f in detail()['followups']]==['负责人跟进','开始处理']
assert count()==1
check('越权403、权限收窄；不可变跟进记录与进行中切换')
# Existing approval and task coexist and retain their independent detail/actions.
day = (dt.datetime.now(ZoneInfo('Asia/Shanghai')).date()+dt.timedelta(days=20)).isoformat()
rid=call('/requests','POST',dict(kind='OUTING',startAt=day+'T09:00:00',endAt=day+'T18:00:00',reason=prefix),ut)['id']
assert count()==2
assert {r['item_type'] for r in pending()}=={'REQUEST','TASK'}
assert call(f'/requests/{rid}',token=gt)['status']=='PENDING'
state('COMPLETED',gt)
assert count()==1 and all(r['item_type']=='REQUEST' for r in pending())
assert call(f'/requests/{rid}',token=gt)['status']=='PENDING'
call(f'/tasks/{mid}','PUT',dict(base,assigneeIds=[oid]),ut,400)
call(f'/tasks/{mid}','PUT',dict(base,dueAt='2031-01-01'),ut,400)
state('IN_PROGRESS',gt,403)
state('IN_PROGRESS')
assert count()==2
check('审批与任务混排及分别查详情；完成移出待办、锁定名单/截止、发起人重开')
call(f'/tasks/{mid}','PUT',dict(base,assigneeIds=[oid]),ut)
assert count()==1 and count(ot)==1
assert len(detail()['followups'])==2
for token,word in [(gt,'移出'),(ot,'指派')]:
    assert any(n.get('task_id')==mid and word in n['message'] for n in call('/messages?category=notifications',token=token))
call(f'/tasks/{mid}',token=gt,expect=403)
state('CANCELLED')
assert count(ot)==0 and detail()['status']=='CANCELLED'
assert not call('/tasks?scope=initiated',token=ut)
call(f'/tasks/{mid}','PUT',base,ut,400)
call(f'/tasks/{mid}/followups','POST',dict(content='取消后跟进'),at,400)
state('TODO',ut,403)
state('IN_PROGRESS',at,400)
state('TODO',at)
assert count(ot)==1
state('COMPLETED',at)
check('改负责人通知与移出权限、历史保留；软取消不可改；仅管理员纠错恢复')
logs=call('/admin/audit',token=at)
actions={r['action'] for r in logs if str(r['target'])==str(mid)}
assert {'CREATE_TASK','UPDATE_TASK','FOLLOW_TASK','STATUS_TASK','COMPLETE_TASK','CANCEL_TASK','RESTORE_TASK'} <= actions
# Administrators may operate other creators' tasks, without replacing the creator.
assert detail(at)['creator_id']==uid
call('/tasks?dueFrom=bad-date',token=gt,expect=400)
state('INVALID',at,400)

# A creator newly assigned by an administrator receives one notice, not both
# an assignment notice and a content-change notice for the same operation.
state('IN_PROGRESS',at)
before=len([n for n in call('/messages?category=notifications',token=ut) if n.get('task_id')==mid])
call(f'/tasks/{mid}','PUT',dict(base,assigneeIds=[uid,oid]),at)
after=len([n for n in call('/messages?category=notifications',token=ut) if n.get('task_id')==mid])
assert after==before+1
state('COMPLETED',at)
# Count all 201 tasks, while each message/list page stays capped at 200.
def create(i): return call('/tasks','POST',dict(base,title=prefix+str(i),assigneeIds=[gid]),ut)['id']
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
    ids=list(pool.map(create,range(201)))
assert count()==202
page1=pending();page2=call('/messages?category=pending&offset=200',token=gt)
assert len(page1)==200 and len(page2)==2
keys=lambda rows:{(r['item_type'],r['id']) for r in rows}
assert not keys(page1)&keys(page2)
assert len(call('/tasks?scope=assigned',token=gt))==200
assert len(call('/tasks?scope=assigned&offset=200',token=gt))==1
filtered=call(f'/tasks?status=TODO&dueFrom=2001-01-01&dueTo=2001-01-01&creatorId={uid}&assigneeId={gid}',token=gt)
assert len(filtered)==200
call('/tasks?status=BAD',token=gt,expect=400)
call('/tasks?dueFrom=2031-01-02&dueTo=2031-01-01',token=gt,expect=400)
for tid in ids: call(f'/tasks/{tid}/status','POST',dict(status='CANCELLED'),ut)
assert count()==1
call(f'/requests/{rid}/cancel','POST',token=ut)
assert count()==0
check('超过200条的全量汇总、混合分页不重复、状态/截止/人员筛选；任务与审批互不干扰')
logs=call('/admin/audit',token=at)
# Audit endpoint may be paginated: current cancellation must be visible.
assert any(r['action']=='CANCEL_TASK' for r in logs)
check('创建、修改、完成、取消、恢复写入既有审计体系')
(ROOT/'.runtime/todo-http-result.json').write_text(json.dumps(dict(checks=checks,taskId=mid),ensure_ascii=False,indent=2))
print(f'共 {len(checks)} 组验收通过')
