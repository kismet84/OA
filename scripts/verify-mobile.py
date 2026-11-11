#!/usr/bin/env python3
"""员工资料、通讯录和设备会话的真实 HTTP 验收；保留独立测试数据。"""
import json, os, secrets, urllib.request, urllib.error, urllib.parse
BASE=os.environ.get('OA_API','http://127.0.0.1:8080/api')
def call(path, method='GET', data=None, token=None, expect=200):
    headers={}
    if token: headers['Authorization']='Bearer '+token
    if data is not None: data=json.dumps(data).encode(); headers['Content-Type']='application/json'
    try:
        with urllib.request.urlopen(urllib.request.Request(BASE+path,data=data,headers=headers,method=method),timeout=30) as r: status,raw=r.status,r.read()
    except urllib.error.HTTPError as e: status,raw=e.code,e.read()
    assert status==expect,(path,status,expect,raw[:500])
    return json.loads(raw)
def login(no,pw='Mobile@2026Changed',**kw): return call('/auth/login','POST',dict(login=no,password=pw,**kw))['token']
at=login('admin',os.environ.get('OA_ADMIN_PASSWORD','Admin@2026Verified'))
prefix='app'+secrets.token_hex(4)
dep=call('/admin/departments','POST',dict(name=prefix+'部门'),at)['id']
body=dict(employeeNo=prefix,name='员工端验收',phone='138'+str(secrets.randbelow(100000000)).zfill(8),jobTitle='工程师',workPhone='021-12345678',roles=['EMPLOYEE'],departmentId=dep,enabled=True,password='Mobile@2026Initial')
uid=call('/admin/users','POST',body,at)['id']
t=login(prefix,'Mobile@2026Initial')
call('/directory',token=t,expect=403)
call('/auth/password','POST',dict(oldPassword=body['password'],newPassword='Mobile@2026Changed'),t)
call('/auth/me',token=t,expect=401)
t=login(prefix,deviceName='员工安卓',platform='Android')
t2=login(prefix,deviceName='员工苹果',platform='iOS')
call('/auth/login','POST',dict(login=prefix,password='Mobile@2026Changed',deviceName='x'*101),expect=400)
call('/auth/login','POST',dict(login=prefix,password='Mobile@2026Changed',platform='x'*31),expect=400)
call('/admin/users/'+str(uid)+'/sessions/others','DELETE',token=t,expect=403)
profile=call('/profile',token=t)
assert profile['job_title']=='工程师' and profile['work_phone']==body['workPhone']
call('/profile/work-phone','PUT',dict(workPhone='010-87654321',employeeNo='tamper',roles=['ADMIN']),t)
assert call('/profile',token=t)['employee_no']==prefix
call('/profile/work-phone','PUT',dict(workPhone='x'*41),t,400)
call('/profile/work-phone','PUT',dict(workPhone='私人电话abc'),t,400)
people=call('/directory?departmentId='+str(dep),token=t)
assert len(people)==1 and people[0]['work_phone']=='010-87654321'
assert not {'phone','password_hash','session_version','roles','manager_id'} & people[0].keys()
assert any(d['id']==dep for d in call('/directory/departments',token=t))
assert len(call('/directory?search='+prefix,token=t))==1
assert len(call('/directory?search='+urllib.parse.quote(prefix+'部门'),token=t))==1
assert call('/directory?search=%25',token=t)==[]
child=call('/admin/departments','POST',dict(name=prefix+'子部门',parentId=dep),at)['id']
call('/admin/users/'+str(uid),'PUT',{**body,'password':None,'departmentId':child},at)
call('/auth/me',token=t,expect=401)
call('/auth/me',token=t2,expect=401)
t=login(prefix,deviceName='员工安卓',platform='Android')
t2=login(prefix,deviceName='员工苹果',platform='iOS')
assert len(call('/directory?departmentId='+str(dep),token=t))==1
sessions=call('/auth/sessions',token=t)
assert len(sessions)==2 and sum(x['current'] for x in sessions)==1
assert {s['device_name'] for s in sessions}=={'员工安卓','员工苹果'}
call('/admin/users/'+str(uid)+'/sessions',token=t,expect=403)
admin_sid=next(s['id'] for s in call('/auth/sessions',token=at) if s['current'])
call('/auth/sessions/'+admin_sid,'DELETE',token=t,expect=404)
call('/auth/sessions/others','DELETE',token=t)
call('/auth/me',token=t2,expect=401)
assert len(call('/auth/sessions',token=t))==1
t2=login(prefix)
sessions=call('/admin/users/'+str(uid)+'/sessions',token=at)
sid=next(s['id'] for s in call('/auth/sessions',token=t2) if s['current'])
call('/admin/users/'+str(uid)+'/sessions/'+sid,'DELETE',token=at)
call('/auth/me',token=t2,expect=401)
t3=login(prefix)
sid=next(s['id'] for s in call('/auth/sessions',token=t3) if s['current'])
call('/auth/sessions/'+sid,'DELETE',token=t3)
call('/auth/me',token=t3,expect=401)
t3=login(prefix)
call('/auth/password','POST',dict(oldPassword='Mobile@2026Changed',newPassword='Mobile@2026Again'),t3)
call('/auth/me',token=t,expect=401)
call('/auth/me',token=t3,expect=401)
t=login(prefix,'Mobile@2026Again')
assert len(call('/auth/sessions',token=t))==1
summary=call('/messages/summary',token=t)
assert set(summary)=={'pending','notifications','announcements'}
call('/admin/users/'+str(uid),'PUT',{**body,'password':None,'enabled':False},at)
call('/auth/me',token=t,expect=401)
assert call('/directory?departmentId='+str(dep),token=at)==[]
print('通过：首次改密、资料字段白名单、工作电话自助修改、组织搜索、停用隐藏、设备名称校验、本人/管理员踢设备、消息汇总')
# Save only non-secret identifiers for Dart live test account provisioning by the operator.
print('测试工号：'+prefix)
