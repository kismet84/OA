#!/usr/bin/env python3
"""方向2/4/5：本地HTTP验收，独立账号；finally恢复规则和特殊日期。仅dev支持模拟时钟。"""
import datetime as dt
import json
import os
import secrets
import subprocess
import urllib.error
import urllib.request
from zoneinfo import ZoneInfo
BASE=os.environ.get('OA_API','http://127.0.0.1:8080/api')
def call(path,method='GET',data=None,token=None,expect=200,raw=False):
    headers={'Authorization':'Bearer '+token} if token else {}
    if data is not None: data=json.dumps(data).encode();headers['Content-Type']='application/json'
    try:
        with urllib.request.urlopen(urllib.request.Request(BASE+path,data=data,headers=headers,method=method),timeout=60) as r: status,body=r.status,r.read()
    except urllib.error.HTTPError as e: status,body=e.code,e.read()
    assert status==expect,(path,status,expect,body[:600])
    return body if raw else json.loads(body)
def check(text): print('通过：'+text,flush=True)
at=call('/auth/login','POST',{'login':'admin','password':os.environ.get('OA_ADMIN_PASSWORD','Admin@2026Verified')})['token']
prefix='opt'+secrets.token_hex(4)
dep=call('/admin/departments','POST',{'name':prefix},at)['id']
def user(suffix,roles=None,manager=None):
    no=prefix+suffix
    uid=call('/admin/users','POST',dict(employeeNo=no,name=no,departmentId=dep,managerId=manager,roles=roles or ['EMPLOYEE'],enabled=True,password='Optimize@2026Initial'),at)['id']
    token=call('/auth/login','POST',dict(login=no,password='Optimize@2026Initial'))['token']
    call('/auth/password','POST',dict(oldPassword='Optimize@2026Initial',newPassword='Optimize@2026Changed'),token)
    token=call('/auth/login','POST',dict(login=no,password='Optimize@2026Changed'))['token']
    return uid,token
manager,mt=user('manager',['SUPERVISOR'])
owner,ot=user('owner',manager=manager)
normal,nt=user('normal',manager=manager)
early,et=user('early',manager=manager)
missing,mit=user('missing',manager=manager)
leave,lt=user('leave',manager=manager)
field,ft=user('field',manager=manager)
now=dt.datetime.now(ZoneInfo('Asia/Shanghai'))
today=now.date()-dt.timedelta(days=1);day=today.isoformat();month=day[:7]
# Seed only these isolated fixtures' employment date, so this test runs at any hour.
# All business operations, punches, approval transitions and assertions use HTTP.
fixture_ids=','.join(str(int(uid)) for uid in [owner,normal,early,missing,leave,field])
subprocess.run(['psql','-h','127.0.0.1','-p','55432','-U','oa','-d','oa','-v','ON_ERROR_STOP=1','-c',f"UPDATE users SET created_at='{day} 00:00:00' WHERE id IN ({fixture_ids})"],check=True,stdout=subprocess.DEVNULL,env={**os.environ,'PGPASSWORD':os.environ.get('DATABASE_PASSWORD','oa-local-only')})
rule=call('/admin/attendance-rules',token=at);urge=call('/admin/urge-config',token=at)
test_network=None
calendar=next((r for r in call(f'/admin/work-calendar?year={today.year}',token=at) if r['day']==day),None)
def daily(uid):return call(f'/attendance/daily?userId={uid}&from={day}&to={day}',token=at)[0]
def punch(uid,time,kind='NORMAL'):
    return call('/admin/dev/punch','POST',dict(userId=uid,exitIp='127.0.0.1',at=day+'T'+time+':00',punch=dict(kind=kind,latitude=31.2,longitude=121.4,accuracy=10,locationSource='LIVE',locatedAt=dt.datetime.now(dt.timezone.utc).isoformat(),ssid=prefix+'-wifi')),at)
def apply(kind,token=ot,**kw):return call('/requests','POST',dict(kind=kind,reason='优化HTTP验收',**kw),token)['id']
def approve(rid):
    for _ in range(5):
        r=call('/requests/'+str(rid),token=at)
        if r['status']!='PENDING': return
        call('/requests/'+str(rid)+'/decision','POST',dict(approve=True),at)
try:
    call('/admin/attendance-rules','PUT',{**rule,'effective_from':day,'work_start':'09:00','work_end':'18:00','workdays':'1,2,3,4,5','late_grace_minutes':5,'early_grace_minutes':5,'enabled':True},at)
    call('/admin/urge-config','PUT',{**urge,'urge_cooldown_minutes':120,'auto_urge_enabled':True,'auto_urge_hours':24,'auto_urge_repeat_hours':0},at)
    result=call('/admin/work-calendar/batch','POST',{'text':f'{day},WORKDAY,验收补班\nbad,HOLIDAY,错误日期'},at)
    assert result['success']==1 and result['failed']==1
    call('/admin/networks','POST',dict(kind='SSID',value=prefix+'-wifi'),at)
    test_network=next(n['id'] for n in call('/admin/networks',token=at) if n['kind']=='SSID' and n['value']==prefix+'-wifi')
    punch(normal,'09:05');punch(normal,'18:00');assert daily(normal)['status']=='NORMAL'
    punch(owner,'09:06');punch(owner,'18:00');assert daily(owner)['status']=='LATE' and daily(owner)['lateMinutes']==6
    punch(early,'09:00');punch(early,'17:00');assert daily(early)['status']=='EARLY'
    punch(missing,'09:00');assert daily(missing)['missingPunch']=='下班卡'
    rid=apply('CORRECTION',mit,startAt=day+'T18:00:00',punchKind='NORMAL');approve(rid);assert daily(missing)['status']=='NORMAL'
    types=call('/leave-types',token=lt);typ=next(t for t in types if not t['deduct_balance'] and not t['proof_required'])
    rid=apply('LEAVE',lt,typeId=typ['id'],startAt=day+'T09:00:00',endAt=(now.date()+dt.timedelta(days=1)).isoformat()+'T18:00:00');approve(rid);assert daily(leave)['status']=='LEAVE'
    rid=apply('TRAVEL',ft,startAt=day+'T09:00:00',endAt=(now.date()+dt.timedelta(days=1)).isoformat()+'T18:00:00');approve(rid);assert daily(field)['status']=='MISSING';punch(field,'10:00','FIELD');assert daily(field)['status']=='FIELD'
    call('/admin/work-calendar','POST',dict(day=day,kind='HOLIDAY',name='验收节日'),at);assert daily(early)['status']=='REST'
    call('/admin/work-calendar','POST',dict(day=day,kind='WORKDAY',name='验收补班'),at);assert daily(early)['status']=='EARLY'
    summary=call(f'/attendance/summary?month={month}&userId={owner}',token=ot)[0];assert summary['lateCount']==1
    call(f'/attendance/daily?userId={normal}&from={day}&to={day}',token=ot,expect=403)
    check('规则与日历逐行校验、授权；正常/迟到/早退/缺卡/请假/外勤/节假日/补班/补卡重算及汇总')
    for suffix in ['attendance-monthly.csv','expenses-monthly.csv']:
        for m in [month,(today.replace(day=1)-dt.timedelta(days=1)).isoformat()[:7]]:
            raw=call(f'/admin/exports/{suffix}?month={m}&departmentId={dep}',token=at,raw=True)
            assert raw.startswith(b'\xef\xbb\xbf');text=raw.decode('utf-8-sig');assert '\r\n' in text
            assert ('缺卡次数' if suffix.startswith('attendance') else '合计') in text
    check('两个月度CSV：场景月份及前月BOM、中文表头、CRLF与报销合计')
    future=(today+dt.timedelta(days=3)).isoformat()
    rid=apply('OUTING',startAt=future+'T09:00:00',endAt=future+'T13:00:00')
    call(f'/requests/{rid}/urge','POST',token=mt,expect=403)
    result=call(f'/requests/{rid}/urge','POST',token=ot);assert result['urgedUserIds']==[manager]
    call(f'/requests/{rid}/urge','POST',token=ot,expect=429)
    assert any(r['request_id']==rid and '【催办】' in r['message'] for r in call('/notifications',token=mt))
    future_clock=(now+dt.timedelta(hours=25)).isoformat()
    assert call('/admin/dev/run-auto-urge','POST',dict(requestId=rid,now=future_clock),at)['sent']==1
    assert call('/admin/dev/run-auto-urge','POST',dict(requestId=rid,now=future_clock),at)['sent']==0
    contract=call('/contracts','POST',dict(name=prefix,type='SERVICE',counterparty='验收单位',amount=1,signedDate=day,effectiveDate=day),ot)['id'];call(f'/contracts/{contract}/submit','POST',token=ot)
    call(f'/contracts/{contract}/urge','POST',token=ot);call(f'/contracts/{contract}/urge','POST',token=ot,expect=429)
    category=call('/assets/categories','POST',dict(name=prefix,kind='ITEM',enabled=True),at)['id']
    asset=call('/assets/requests','POST',dict(categoryId=category,quantity=1,purpose='验收',expectedDate=future),ot)['id']
    call(f'/assets/requests/{asset}/urge','POST',token=ot);call(f'/assets/requests/{asset}/urge','POST',token=ot,expect=429)
    check('通用申请/合同/资产手动催办、权限、冷却与通知；自动超时发一次且巡检去重')
    template=call('/request-templates','POST',dict(name='外出模板',kind='OUTING',payload=dict(reason='拜访',ccUserIds=[normal],startAt=day,attachmentIds=['不可复制'])),ot)['id']
    stored=call('/request-templates',token=ot)[0];assert 'startAt' not in stored['payload'] and 'attachmentIds' not in stored['payload']
    call(f'/request-templates/{template}','PUT',dict(name='已重命名',kind='OUTING',payload=stored['payload']),ot)
    call(f'/request-templates/{template}','DELETE',token=mt,expect=403)
    draft=call(f'/requests/{rid}/copy',token=ot);assert draft['kind']=='OUTING' and not {'startAt','endAt','attachments','status','steps'} & draft['payload'].keys()
    call(f'/request-templates/{template}','DELETE',token=ot)
    s=call('/messages/summary',token=mt)
    assert s['approvalsRequests']==len(call('/requests?inbox=true',token=mt))
    assert s['approvalsContracts']==len(call('/contracts?scope=pending',token=mt))
    assert s['approvalsAssets']==len(call('/assets/requests?scope=pending',token=mt))
    assert s['tasks']==len(call('/tasks?scope=assigned&status=ACTIVE',token=mt))
    assert s['pending']==sum(s[k] for k in ['approvalsRequests','approvalsContracts','approvalsAssets','tasks'])
    check('模板CRUD、所有权、白名单及复制草稿；拆分角标与各待办子列表完全一致')
    for business,id in [('requests',rid),('contracts',contract),('assets/requests',asset)]:call(f'/{business}/{id}/'+('cancel' if business=='requests' else 'withdraw'),'POST',token=ot)
finally:
    if test_network:call('/admin/networks/'+str(test_network),'DELETE',token=at)
    call('/admin/attendance-rules','PUT',rule,at)
    call('/admin/urge-config','PUT',urge,at)
    if calendar:call('/admin/work-calendar','POST',calendar,at)
    else:call('/admin/work-calendar/'+day,'DELETE',token=at)
print('优化验收全部通过；全局配置已恢复',flush=True)
