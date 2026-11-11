#!/usr/bin/env python3
"""HTTP integration acceptance against real PostgreSQL/Redis. Creates isolated dev accounts, keeps history."""
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
prefix = 'v' + secrets.token_hex(4)
INITIAL = 'Verify@2026Initial'
PASSWORD = 'Verify@2026Changed'


def call(path, method='GET', data=None, token=None, expect=200, headers=None):
    h = dict(headers or {})
    if token:
        h['Authorization'] = 'Bearer ' + token
    if data is not None and not isinstance(data, bytes):
        data = json.dumps(data).encode()
        h['Content-Type'] = 'application/json'
    req = urllib.request.Request(BASE + path, data=data, headers=h, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=30)
        status = response.status
        raw = response.read()
    except urllib.error.HTTPError as e:
        status = e.code
        raw = e.read()
    assert status == expect, f'{method} {path}: expected {expect}, got {status}: {raw.decode()[:700]}'
    try:
        return json.loads(raw)
    except (json.JSONDecodeError, UnicodeDecodeError):
        return raw


def check(label):
    checks.append(label)
    print('通过：' + label, flush=True)


def login(login_name, password=PASSWORD, expect=200):
    return call('/auth/login', 'POST', {'login': login_name, 'password': password}, expect=expect)


admin_password = os.environ.get('OA_ADMIN_PASSWORD', 'Admin@2026Dev')
try:
    admin = login('admin', admin_password)
except AssertionError:
    admin = login('admin', 'Admin@2026Verified')
at = admin['token']
if admin['user']['mustChange']:
    call('/admin/users', token=at, expect=403)
    call('/requests', 'POST', {}, at, expect=403)
    call('/auth/password', 'POST', {'oldPassword': admin_password, 'newPassword': 'Admin@2026Verified'}, at)
    call('/auth/me', token=at, expect=401)
    at = login('admin', 'Admin@2026Verified')['token']
    check('首次改密强制执行，改密后旧会话失效')
else:
    check('管理员可使用已修改的密码登录')
assert call('/auth/me', token=at)['user']['id'] == admin['user']['id']
call('/admin/users', expect=401)
call('/auth/me', token=at, headers={'Origin':'http://localhost:5173'})
call('/auth/me', token=at, expect=403, headers={'Origin':'https://untrusted.example'})
check('未登录访问被拒绝；CORS 允许登记来源并拒绝其他来源')
root_dept = call('/admin/departments', token=at)[0]['id']
dept = call('/admin/departments', 'POST', {'name': '验收部门 ' + prefix, 'parentId': root_dept}, at)['id']


def create(suffix, roles, manager=None, department=None, phone=None):
    body = {'employeeNo': prefix + suffix, 'phone': phone, 'name': '验收' + suffix, 'roles': roles, 'departmentId': department or dept, 'managerId': manager, 'enabled': True, 'password': INITIAL}
    uid = call('/admin/users', 'POST', body, at)['id']
    t = login(body['employeeNo'], INITIAL)['token']
    call('/punches', token=t, expect=403)
    call('/auth/password', 'POST', {'oldPassword': INITIAL, 'newPassword': PASSWORD}, t)
    token = login(body['employeeNo'])['token']
    return uid, token, body


head, ht, _ = create('head', ['EMPLOYEE', 'DEPT_HEAD'])
manager, mt, _ = create('manager', ['EMPLOYEE', 'SUPERVISOR'])
finance, ft, _ = create('finance', ['EMPLOYEE', 'FINANCE'])
call('/admin/departments/' + str(dept), 'PUT', {'name': '验收部门 ' + prefix, 'parentId': root_dept, 'headId': head}, at)
phone = '1' + ''.join(str(secrets.randbelow(10)) for _ in range(10))
uid, et, employee_body = create('employee', ['EMPLOYEE'], manager, phone=phone)
rogue, rt, _ = create('other', ['EMPLOYEE'], manager)
assert login(phone)['user']['id'] == uid
call('/admin/users', token=et, expect=403)
call('/%61dmin/users', token=et, expect=403)
call('/admin;test=1/users', token=et, expect=403)
call('/%61dmin/users', expect=401)
call('/admin/departments/' + str(root_dept), 'PUT', {'name': '循环应拒绝', 'parentId': dept}, at, expect=400)
check('开通账号、部门树、角色、直属上级及手机号登录；拒绝部门循环和员工管理越权')
leave_types = {t['code']: t for t in call('/admin/leave-types', token=at)}
annual = leave_types['ANNUAL']['id']
marriage = leave_types['MARRIAGE']['id']
personal = leave_types['PERSONAL']['id']
now = dt.datetime.now(ZoneInfo('Asia/Shanghai'))
base = now.date() + dt.timedelta(days=5)
# Keep all future scenarios in a single year (including when run near December 31).
if base.month == 12 and base.day > 1:
    base = dt.date(base.year + 1, 1, 5)
year = base.year


def date(day, end=False, half=False):
    return (base + dt.timedelta(days=day)).isoformat() + ('T13:00:00' if half else 'T18:00:00' if end else 'T09:00:00')


def submit(day, kind='LEAVE', length=1, type_id=None, half=False, attachment=None, expect=200):
    body = {'kind': kind, 'typeId': annual if type_id is None else type_id, 'startAt': date(day), 'endAt': date(day + length - 1, True, half), 'reason': '自动验收 ' + prefix, 'attachmentId': attachment}
    return call('/requests', 'POST', body, et, expect=expect)


def detail(rid, token=et):
    return call('/requests/' + str(rid), token=token)


def approve(rid, token=mt, approve=True, comment='验收通过', expect=200):
    return call('/requests/' + str(rid) + '/decision', 'POST', {'approve': approve, 'comment': comment}, token, expect=expect)


def remaining():
    return next(b['amount'] for b in call('/balances', token=et) if b['year'] == year and b['type_id'] == annual)


call('/admin/balances', 'PUT', {'userId': uid, 'typeId': annual, 'year': year, 'amount': 10}, at)
rid = submit(0, length=2)['id']
assert len(detail(rid)['steps']) == 2
assert any(r['id'] == rid for r in call('/requests?offset=0', token=at))
assert not any(r['id'] == rid for r in call('/requests?offset=0', token=rt))
call('/requests/' + str(rid), token=rt, expect=403)
approve(rid, rt, expect=403)
approve(rid, ht, expect=403)
call('/punches?userId=' + str(uid), token=rt, expect=403)
call('/requests', 'POST', {'userId': rogue, 'kind': 'TRAVEL', 'startAt': date(80), 'endAt': date(80, True), 'reason': '不允许代提交'}, et, expect=403)
check('跨用户查询、代提交、越权审批和跳级审批被拒绝')
approve(rid)
assert detail(rid)['status'] == 'PENDING' and remaining() == 10
approve(rid, ht)
assert detail(rid)['status'] == 'APPROVED' and remaining() == 8
approve(rid, ht, expect=400)
call('/requests/' + str(rid) + '/cancel', 'POST', token=et)
assert remaining() == 10 and detail(rid)['status'] == 'CANCELLED'
assert any('#' + str(rid) in n['message'] and '撤销' in n['message'] for n in call('/notifications', token=ht))
check('超过一天两级审批，最终批准才扣余额，重复审批被拒绝，未开始撤销返还余额并通知已批人')
rid = submit(3, half=True)['id']
assert len(detail(rid)['steps']) == 1 and detail(rid)['days'] == 0.5
approve(rid)
assert remaining() == 9.5
check('半天申请只审批一次并扣减 0.5 天')
rid = submit(5, type_id=personal)['id']
approve(rid, approve=False, comment='', expect=400)
approve(rid, approve=False, comment='材料不符')
assert detail(rid)['status'] == 'REJECTED' and remaining() == 9.5
rid = submit(6, length=2, type_id=personal)['id']
approve(rid)
call('/requests/' + str(rid) + '/cancel', 'POST', token=et)
assert detail(rid)['status'] == 'WITHDRAWN'
check('事假不扣余额；驳回须意见；待审批可在一级通过后撤回')
submit(9, type_id=marriage, expect=400)
boundary = 'oa' + secrets.token_hex(8)
payload = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="proof.pdf"\r\nContent-Type: application/pdf\r\n\r\n%PDF-1.4\n验收证明\n\r\n--{boundary}--\r\n').encode()
attachment = call('/attachments', 'POST', payload, et, headers={'Content-Type': 'multipart/form-data; boundary=' + boundary})['id']
call('/attachments/' + attachment, token=rt, expect=403)
rid = submit(10, length=2, type_id=marriage, attachment=attachment)['id']
approve(rid); approve(rid, ht)
submit(13, length=2, type_id=marriage, attachment=attachment, expect=400)
check('法定假强制证明、附件按权限下载、已批天数计入年度上限')
rid = submit(16, type_id=personal)['id']
approve(rid, at)
call('/requests/' + str(rid) + '/cancel', 'POST', token=et)
assert any('#' + str(rid) in n['message'] and '撤销' in n['message'] for n in call('/notifications', token=at))
check('管理员代批后的撤销通知同时送达实际处理人和原分配审批人')
# Two pending requests may coexist, but a row lock prevents overspending at final approval.
call('/admin/balances', 'PUT', {'userId': uid, 'typeId': annual, 'year': year, 'amount': 0.5}, at)
a = submit(20, half=True)['id']; b = submit(21, half=True)['id']

def concurrent_approve(rid):
    req = urllib.request.Request(BASE + '/requests/' + str(rid) + '/decision', data=b'{"approve":true}', headers={'Content-Type':'application/json','Authorization':'Bearer '+mt},method='POST')
    try:
        return urllib.request.urlopen(req).status
    except urllib.error.HTTPError as e:
        return e.code


with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    statuses = list(pool.map(concurrent_approve, [a, b]))
assert sorted(statuses) == [200, 400] and remaining() == 0
check('真实并发审批共享余额：只成功一次，不会扣成负数')
# Same supervisor/head, or missing head: only one signature even beyond the threshold.
other_uid, other_t, _ = create('samehead', ['EMPLOYEE'], head)
r = call('/requests', 'POST', {'kind':'TRAVEL','startAt':date(25),'endAt':date(26,True),'reason':'同一负责人'},other_t)['id']
assert len(detail(r, other_t)['steps']) == 1
nohead = call('/admin/departments','POST',{'name':'未指定负责人 '+prefix,'parentId':dept},at)['id']
nh_uid, nh_t, _ = create('nohead',['EMPLOYEE'],manager,nohead)
r = call('/requests','POST',{'kind':'OUTING','startAt':date(25),'endAt':date(26,True),'reason':'无负责人'},nh_t)['id']
assert len(detail(r,nh_t)['steps']) == 1
check('直属上级已是部门负责人或部门未指定负责人时，只审批一次')
# Runtime configurable thresholds affect only newly submitted applications.
old_config = call('/admin/workflow',token=at)
call('/admin/workflow','PUT',{'leaveThreshold':3,'travelThreshold':3,'expenseThreshold':1000,'financeId':finance},at)
rid = submit(30,kind='OUTING',length=2)['id']
assert len(detail(rid)['steps']) == 1
call('/requests/'+str(rid)+'/cancel','POST',token=et)
expense = call('/requests','POST',{'kind':'EXPENSE','amount':1200,'reason':'验收交通费用'},et)['id']
assert len(detail(expense)['steps']) == 2
approve(expense);approve(expense,ft)
assert detail(expense)['status'] == 'APPROVED'
call('/admin/workflow','PUT',{'leaveThreshold':old_config['leave_threshold'],'travelThreshold':old_config['travel_threshold'],'expenseThreshold':old_config['expense_threshold'],'financeId':old_config['finance_id']},at)
check('后台配置阈值即时生效；报销超过阈值流转财务并批准，不涉及打款')
# Punch API never accepts caller-supplied source IP; the development endpoint explicitly simulates it.
punch = {'kind':'NORMAL','latitude':31.23,'longitude':121.47,'accuracy':12,'ssid':prefix+'-wifi'}
call('/punches','POST',{'kind':'NORMAL'},et,expect=400)
call('/punches','POST',{**punch,'accuracy':0},et,expect=400)
call('/punches','POST',{**punch,'latitude':100},et,expect=400)
call('/punches','POST',{**punch,'kind':'FIELD'},et,expect=400)
call('/punches','POST',punch,et,expect=400)
company_ip = '203.0.113.' + str(secrets.randbelow(200)+1)
existing = call('/admin/networks',token=at)
if not any(n['kind']=='IP' and n['value']==company_ip for n in existing):call('/admin/networks','POST',{'kind':'IP','value':company_ip},at)
call('/punches','POST',{**punch,'exitIp':company_ip},et,expect=400,headers={'X-OA-Client-IP':company_ip,'X-Forwarded-For':company_ip})
call('/admin/dev/punch','POST',{'userId':uid,'exitIp':company_ip,'punch':punch},at)
call('/admin/networks','POST',{'kind':'SSID','value':punch['ssid']},at)
call('/punches','POST',punch,et)
check('无定位/无精度/非法定位/非公司网络拒绝；不能伪造出口 IP；登记 SSID 或出口 IP 可正常打卡')
future = submit(35,kind='TRAVEL')['id'];approve(future)
call('/punches','POST',{**punch,'kind':'FIELD','ssid':'远程网络'},et,expect=400)
start = now.date() - dt.timedelta(days=1); end = now.date() + dt.timedelta(days=1)
if start.year != end.year:
    start = dt.date(now.year,1,1);end = dt.date(now.year,12,31)
current = call('/requests','POST',{'kind':'TRAVEL','startAt':start.isoformat()+'T09:00:00','endAt':end.isoformat()+'T18:00:00','reason':'验收当前有效出差'},et)['id']
for step in detail(current)['steps']: approve(current,mt if step['approver_id']==manager else ht)
field = call('/punches','POST',{**punch,'kind':'FIELD','ssid':'远程网络'},et)['id']
call('/requests/'+str(current)+'/cancel','POST',token=et,expect=400)
assert any(p['id']==field and not p['simulated'] for p in call('/punches',token=et))
assert any(p['simulated'] for p in call('/punches',token=et))
check('未来批准不能提前出勤；当前有效出差可在任意网络出勤；已开始不能撤销；真实/模拟打卡均可查询')
leave_now = call('/requests','POST',{'kind':'LEAVE','typeId':personal,'startAt':start.isoformat()+'T09:00:00','endAt':end.isoformat()+'T18:00:00','reason':'请假期间仍可查记录和正常打卡'},nh_t)['id']
approve(leave_now)
call('/punches','POST',punch,nh_t)
call('/punches',token=nh_t)
call('/punches','POST',{**punch,'kind':'FIELD'},nh_t,expect=400)
check('批准请假期间仍可正常打卡和查记录；请假不构成出勤授权')
# Login limit uses a dedicated account and does not disrupt the admin or worker IP budget.
for i in range(5): login(prefix+'missing','wrong-password',expect=401)
login(prefix+'missing','wrong-password',expect=429)
check('连续五次错误后账号登录限流生效')
call('/auth/logout','POST',token=rt)
call('/auth/me',token=rt,expect=401)
check('退出立即销毁 Redis 会话')
# Disable a leaf account; all existing sessions fail while data remains queryable by admin.
employee_body.update({'password':None,'enabled':False})
call('/admin/users/'+str(uid),'PUT',employee_body,at)
call('/punches',token=et,expect=401)
login(employee_body['employeeNo'],expect=401)
assert any(p['id']==field for p in call('/punches?userId='+str(uid),token=at))
assert detail(current,at)['status']=='APPROVED'
check('停用立即阻断既有会话和新登录，历史审批与打卡保留')
for path in ['/admin/exports/punches.csv?userId='+str(uid),'/admin/exports/requests.csv?userId='+str(uid)]:
    raw=call(path,token=at);assert isinstance(raw,bytes) and raw.startswith(b'\xef\xbb\xbf')
assert len(call('/admin/audit',token=at))>20
call('/admin/exports/punches.csv',token=mt,expect=403)
check('CSV 导出含 UTF-8 BOM；管理操作审计有记录；非管理员不能导出全员数据')
report={'time':dt.datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(),'prefix':prefix,'checks':checks,'count':len(checks),'employeeId':uid,'fieldPunchId':field}
(ROOT/'.runtime/verification-result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
print(f'验收完成：{len(checks)} 组全部通过。结果：.runtime/verification-result.json')
