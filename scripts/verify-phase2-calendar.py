#!/usr/bin/env python3
"""日程会议真实 HTTP 验收：独立账号、并发预定、通知与权限。"""
import concurrent.futures
import datetime as dt
import json
import os
import pathlib
import secrets
import urllib.error
import urllib.request
from decimal import Decimal
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
    try:
        return json.loads(raw)
    except (ValueError, UnicodeDecodeError):
        return raw.decode('utf-8-sig')

def check(label):
    checks.append(label)
    print('通过：'+label, flush=True)

at = call('/auth/login', 'POST', dict(login='admin', password=os.environ.get('OA_ADMIN_PASSWORD', 'Admin@2026Verified')))['token']
prefix='cal'+secrets.token_hex(4)
dep=call('/admin/departments','POST',dict(name=prefix),at)['id']
def employee(suffix):
    no=prefix+suffix
    uid=call('/admin/users','POST',dict(employeeNo=no,name=no,departmentId=dep,roles=['EMPLOYEE'],enabled=True,password='Calendar@2026Initial'),at)['id']
    t=call('/auth/login','POST',dict(login=no,password='Calendar@2026Initial'))['token']
    call('/auth/password','POST',dict(oldPassword='Calendar@2026Initial',newPassword='Calendar@2026Changed'),t)
    return uid,call('/auth/login','POST',dict(login=no,password='Calendar@2026Changed'))['token'],no
uid,et,eno=employee('owner')
pid,pt,pno=employee('guest')
oid,ot,ono=employee('other')
room_data=dict(name=prefix,location='三楼',capacity=1,enabled=True)
rid=call('/meeting-rooms','POST',room_data,at)['id']
base=dict(title=prefix+'项目会议',startAt='2031-02-03T09:00:00',endAt='2031-02-03T10:00:00',roomId=rid,description='仅参会人可见的说明',attendeeIds=[pid,uid,pid])
for path in ['/meetings','/meeting-rooms','/meetings/1']:
    call(path,expect=401)
call('/meetings','POST',base,expect=401)
check('未登录拒绝；复用八小时登录会话')
call('/meeting-rooms','POST',room_data,et,403)
call('/meeting-rooms','POST',room_data,at,409)
call('/meeting-rooms','POST',dict(name='错误',enabled=True,capacity=0),at,400)
call('/meeting-rooms','POST',dict(name='小数',enabled=True,capacity=1.5),at,400)
check('会议室维护权限、启用名称唯一、正整数容量')
res=call('/meetings','POST',base,et); mid=res['id']
assert res['warnings'] and '容纳人数' in res['warnings'][0]
row=call(f'/meetings/{mid}',token=et)
assert [p['user_id'] for p in row['attendees']]==[pid]
assert row['start_at']=='2031-02-03T09:00:00'
notes=call('/messages?category=notifications',token=pt)
assert any(n.get('meeting_id')==mid and '邀请' in n['message'] for n in notes)
assert not any(n.get('meeting_id')==mid for n in call('/messages?category=notifications',token=et))
check('创建、上海时间、去重、组织者自动参加、超容量软提示与系统通知关联')
conflict=call('/meetings','POST',dict(base,title='冲突会议'),ot,409)
assert base['title'] in conflict['message']
public=call(f'/meetings/{mid}',token=ot)
assert public['title']==base['title'] and 'description' not in public and 'attendees' not in public
assert not public['canEdit'] and not public['canReply']
assert any(m['id']==mid for m in call('/meetings?day=2031-02-03',token=ot))
for method,path,data in [('PUT',f'/meetings/{mid}',base),('POST',f'/meetings/{mid}/cancel',None),('POST',f'/meetings/{mid}/reply',dict(response='ACCEPTED'))]:
    call(path,method,data,ot,403)
call(f'/meetings/{mid}/reply','POST',dict(response='ACCEPTED'),et,403)
check('会议室占用可见、说明与名单保密、越权修改取消回复403')
call(f'/meetings/{mid}/reply','POST',dict(response='DECLINED',reason='已有安排'),pt)
assert call(f'/meetings/{mid}',token=et)['attendees'][0]['reason']=='已有安排'
call(f'/meetings/{mid}/reply','POST',dict(response='ACCEPTED'),pt)
assert call(f'/meetings/{mid}',token=at)['attendees'][0]['response']=='ACCEPTED'
call(f'/meetings/{mid}/reply','POST',dict(response='INVALID'),pt,400)
check('接受、请假及原因、管理员查回复；非法回复400')
call(f'/meetings/{mid}','PUT',dict(base,title='仅改标题'),et)
assert call(f'/meetings/{mid}',token=et)['attendees'][0]['response']=='ACCEPTED'
moved=dict(base,startAt='2031-02-03T10:00:00',endAt='2031-02-03T11:00:00')
call(f'/meetings/{mid}','PUT',moved,et)
assert call(f'/meetings/{mid}',token=et)['attendees'][0]['response']=='PENDING'
assert any(n.get('meeting_id')==mid and '变更' in n['message'] for n in call('/messages?category=notifications',token=pt))
second=call('/meetings','POST',dict(base,title='端点相邻'),ot)['id']
call(f'/meetings/{mid}','PUT',base,et,409)
assert call(f'/meetings/{mid}',token=et)['start_at']=='2031-02-03T10:00:00'
check('标题修改保留回复；改期重置并通知；相邻时段允许；冲突改期原子回滚')
call(f'/meetings/{mid}','PUT',dict(moved,attendeeIds=[oid]),et)
assert not call(f'/meetings/{mid}',token=pt)['canReply']
assert any(n.get('meeting_id')==mid and '移出' in n['message'] for n in call('/messages?category=notifications',token=pt))
assert any(n.get('meeting_id')==mid and '邀请' in n['message'] for n in call('/messages?category=notifications',token=ot))
call(f'/meetings/{mid}/cancel','POST',token=et)
assert not any(m['id']==mid for m in call('/meetings?day=2031-02-03',token=et))
assert call(f'/meetings/{mid}',token=ot)['cancelled_at']
call(f'/meetings/{mid}/reply','POST',dict(response='ACCEPTED'),ot,400)
call(f'/meetings/{mid}','PUT',moved,et,400)
assert any(n.get('meeting_id')==mid and '取消' in n['message'] for n in call('/messages?category=notifications',token=ot))
rebook=call('/meetings','POST',moved,et)['id']
check('新加入及移出通知、软取消通知、默认隐藏、取消不可回复修改、释放后重订')
soft=call('/meetings','POST',dict(moved,roomId=None),et)
assert any('个人日程冲突' in w for w in soft['warnings'])
call('/meetings','POST',dict(base,endAt=base['startAt']),et,400)
call('/meetings','POST',dict(base,endAt=None),et,400)
call('/meetings','POST',dict(base,roomId=None,attendeeIds=[999999999]),et,400)
call('/meetings?from=2031-02-03T10:00&to=2031-02-03T09:00',token=et,expect=400)
call('/meetings','POST',dict(base,roomId=None,startAt='2020-01-01T09:00',endAt='2020-01-01T10:00',attendeeIds=[]),et)
check('个人冲突软提示、不订会议室、过去开始允许、结束必填且晚于开始')
call('/meeting-rooms/'+str(rid),'PUT',dict(room_data,enabled=False),at)
call('/meetings','POST',dict(base,startAt='2031-02-04T09:00',endAt='2031-02-04T10:00'),et,400)
call(f'/meetings/{rebook}','PUT',dict(moved,endAt='2031-02-03T12:00'),et,400)
call(f'/meetings/{rebook}','PUT',dict(moved,title='停用后仅修改说明',description='仍可维护'),et)
assert call(f'/meetings/{rebook}',token=et)['room_name']==prefix
call('/meeting-rooms/'+str(rid),'DELETE',token=at)
assert any(r['id']==rid and not r['enabled'] for r in call('/meeting-rooms?management=true',token=at))
check('停用禁止新订及改期、历史可查看和维护说明、有记录删除自动停用')
empty=call('/meeting-rooms','POST',dict(name=prefix+'空',enabled=True),at)['id']
call('/meeting-rooms/'+str(empty),'DELETE',token=at)
assert not any(r['id']==empty for r in call('/meeting-rooms?management=true',token=at))
call('/meeting-rooms/'+str(rid),'PUT',room_data,at)
reservation=dict(base,startAt='2031-02-05T09:00',endAt='2031-02-05T10:00')
def reserve(_):
    # Capture a genuine 409 without weakening the common HTTP assertion.
    req=urllib.request.Request(BASE+'/meetings',data=json.dumps(reservation).encode(),headers={'Authorization':'Bearer '+et,'Content-Type':'application/json'},method='POST')
    try:
        with urllib.request.urlopen(req) as r:return r.status
    except urllib.error.HTTPError as e:return e.code
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    assert sorted(pool.map(reserve,range(2)))==[200,409]
check('无记录会议室可删除；并发请求仅一笔预定成功')
# Admin can maintain another organizer's meeting without becoming its organizer.
call(f'/meetings/{rebook}','PUT',dict(moved,title='管理员维护'),at)
assert call(f'/meetings/{rebook}',token=at)['organizer_id']==uid
call(f'/meetings/{rebook}/cancel','POST',token=at)
call(f'/meetings/{second}/cancel','POST',token=at)
call('/meeting-rooms/'+str(rid),'DELETE',token=at)
audit=call('/admin/audit',token=at)
assert any(a['action']=='CREATE_MEETING' and str(a['target'])==str(rebook) for a in audit)
assert any(a['action']=='CANCEL_MEETING' and str(a['target'])==str(rebook) for a in audit)
check('管理员修改与取消、组织者保持创建人、审计保留')
result=ROOT/'.runtime/phase2-calendar-verification.json'
result.write_text(json.dumps(dict(checks=checks),ensure_ascii=False,indent=2))
print(f'日程会议 HTTP 验收通过：{len(checks)} 组')
