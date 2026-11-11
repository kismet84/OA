#!/usr/bin/env python3
"""二期真实 HTTP 验收：独立账号和单据，保留审计；恢复全局配置。"""
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
prefix = 'p2'+secrets.token_hex(4)
dep = call('/admin/departments', 'POST', dict(name=prefix), at)['id']

def user(suffix, roles=None, manager=None):
    no = prefix+suffix
    uid = call('/admin/users', 'POST', dict(employeeNo=no, name=no, departmentId=dep, managerId=manager, roles=roles or ['EMPLOYEE'], enabled=True, password='Attend@2026Initial'), at)['id']
    token = call('/auth/login', 'POST', dict(login=no, password='Attend@2026Initial'))['token']
    call('/auth/password', 'POST', dict(oldPassword='Attend@2026Initial', newPassword='Attend@2026Changed'), token)
    token = call('/auth/login', 'POST', dict(login=no, password='Attend@2026Changed'))['token']
    return uid, token

head, ht = user('head', ['EMPLOYEE', 'DEPT_HEAD'])
manager, mt = user('manager', ['EMPLOYEE', 'SUPERVISOR'])
uid, et = user('employee', manager=manager)
cc, ct = user('cc', manager=manager)
other, ot = user('other', manager=manager)
fallback, bt = user('fallback')
config = call('/admin/workflow', token=at)
settings = dict(leaveThreshold=config['leave_threshold'], travelThreshold=config['travel_threshold'], expenseThreshold=config['expense_threshold'], financeId=config['finance_id'], correctionDays=config['correction_days'], overtimeRatio=config['overtime_ratio'])
now = dt.datetime.now(ZoneInfo('Asia/Shanghai')).replace(tzinfo=None)
today = now.date()

def iso(day, hour):
    return dt.datetime.combine(day, dt.time(hour)).isoformat()
def submit(kind, start, end=None, token=et, expect=200, **kw):
    result = call('/requests', 'POST', dict(kind=kind, startAt=start, endAt=end, reason='二期验收', **kw), token, expect)
    return result.get('id')
def detail(rid, token=et):
    return call('/requests/'+str(rid), token=token)
def decide(rid, token=mt, approve=True, comment='', expect=200):
    return call(f'/requests/{rid}/decision', 'POST', dict(approve=approve, comment=comment), token, expect)
def cancel(rid, token=et, expect=200):
    return call(f'/requests/{rid}/cancel', 'POST', token=token, expect=expect)
def balance(token=et, year=None):
    rows = call('/balances', token=token)
    return sum(Decimal(str(r['amount'])) for r in rows if r['type_name']=='调休' and r['year']==(year or today.year))

try:
    call('/admin/workflow', 'PUT', dict(settings, correctionDays=3, overtimeRatio=1, leaveThreshold=1, travelThreshold=1), at)
    call('/admin/workflow', 'PUT', dict(settings, correctionDays=-1), at, 400)
    call('/admin/workflow', 'PUT', dict(settings, overtimeRatio=0), at, 400)
    call('/admin/workflow', 'PUT', dict(settings, overtimeRatio=1.001), at, 400)
    call('/admin/workflow', 'PUT', settings, et, 403)
    assert call('/attendance-policy', token=et)['correction_days']==3
    # No manager or head: correction goes to administrator, never self approval.
    atime = iso(today-dt.timedelta(days=1), 10)
    rid = submit('CORRECTION', atime, token=bt, punchKind='NORMAL')
    assert len(detail(rid, bt)['steps'])==1
    assert 'ADMIN' in next(u for u in call('/admin/users', token=at) if u['id']==detail(rid, bt)['steps'][0]['approver_id'])['roles']
    decide(rid, at)
    cancel(rid, bt, 400)
    call('/admin/departments/'+str(dep), 'PUT', dict(name=prefix, headId=head), at)
    rid = submit('CORRECTION', atime, token=bt, punchKind='FIELD', expect=400)
    rid = submit('CORRECTION', atime, token=bt, punchKind='NORMAL', expect=400)
    # Main employee: arbitrary historical minutes, no location/network, single supervisor step.
    correction = submit('CORRECTION', atime, punchKind='NORMAL', ccUserIds=[cc])
    assert len(detail(correction)['steps'])==1
    submit('CORRECTION', atime, punchKind='NORMAL', expect=400)
    submit('CORRECTION', (now+dt.timedelta(hours=1)).isoformat(), punchKind='NORMAL', expect=400)
    submit('CORRECTION', iso(today-dt.timedelta(days=4), 10), punchKind='NORMAL', expect=400)
    submit('CORRECTION', atime, punchKind='FIELD', expect=400)
    submit('CORRECTION', atime, punchKind='INVALID', expect=400)
    submit('CORRECTION', atime, expect=400)
    call('/requests', 'POST', dict(kind='CORRECTION', startAt=atime, punchKind='NORMAL', reason=' '), et, 400)
    call('/requests/'+str(correction), token=ot, expect=403)
    decide(correction, ot, expect=403)
    assert any(r['id']==correction for r in call('/messages?category=pending', token=mt))
    assert any(r['id']==correction for r in call('/messages?category=initiated', token=et))
    assert any(r['id']==correction for r in call('/messages?category=cc', token=ct))
    decide(correction, approve=False, expect=400)
    decide(correction)
    decide(correction, expect=400)
    punch = next(p for p in call('/punches', token=et) if p['correction_request_id']==correction)
    assert dt.datetime.fromisoformat(punch['punched_at']).replace(tzinfo=None)==dt.datetime.fromisoformat(atime)
    assert punch['latitude'] is None and punch['longitude'] is None and punch['exit_ip'] is None
    cancel(correction, expect=400)
    submit('CORRECTION', atime, punchKind='NORMAL', expect=400)
    assert len([n for n in call('/messages?category=notifications', token=ct) if n['request_id']==correction])==2
    check('补卡期限、未来时间、原因、重复、权限、单级审批、消息抄送和无伪造定位记录；批准不可撤销')
    r = submit('CORRECTION', iso(today-dt.timedelta(days=3), 9), punchKind='NORMAL')
    cancel(r)
    r = submit('CORRECTION', iso(today-dt.timedelta(days=3), 9), punchKind='NORMAL')
    decide(r, approve=False, comment='资料不符')
    r = submit('CORRECTION', iso(today-dt.timedelta(days=3), 9), punchKind='NORMAL')
    cancel(r)
    check('上海日期第3日可补，第4日不可补；待批撤回和驳回可重提')
    # Field correction at inclusive start; exclusive end rejected even if past.
    trip = submit('TRAVEL', iso(today-dt.timedelta(days=2), 9), iso(today+dt.timedelta(days=1), 18))
    decide(trip); decide(trip, ht)
    field = submit('CORRECTION', iso(today-dt.timedelta(days=2), 9), punchKind='FIELD')
    decide(field)
    assert next(p for p in call('/punches', token=et) if p['correction_request_id']==field)['kind']=='FIELD'
    # The exclusive end predicate is also checked in the deterministic JUnit test.
    check('出勤补卡使用补卡时刻的已批准出差资格，允许等于出差开始时刻')
    # Overtime can overlap travel/outing; historical periods also supported.
    past = submit('OVERTIME', iso(today-dt.timedelta(days=2), 13), iso(today-dt.timedelta(days=2), 18), ccUserIds=[cc])
    decide(past)
    assert balance()==Decimal('0.5')
    cancel(past, expect=400)
    check('出差期间允许加班、过去加班可提交；批准按1:1计入调休，已开始不可撤销')
    future = today+dt.timedelta(days=10)
    short = submit('OVERTIME', iso(future, 9), iso(future, 13))
    submit('OVERTIME', iso(future, 9), iso(future, 18), expect=400)
    types = {t['code']:t['id'] for t in call('/leave-types', token=et)}
    submit('LEAVE', iso(future, 9), iso(future, 13), typeId=types['PERSONAL'], expect=400)
    decide(short)
    assert balance()==Decimal('1')
    # Ratio/config snapshot remains unchanged on cancellation.
    call('/admin/workflow', 'PUT', dict(settings, correctionDays=3, overtimeRatio=1.25, leaveThreshold=1), at)
    cancel(short)
    assert balance()==Decimal('0.5')
    long = submit('OVERTIME', iso(future+dt.timedelta(days=1), 9), iso(future+dt.timedelta(days=2), 18))
    assert len(detail(long)['steps'])==2
    decide(long)
    assert balance()==Decimal('0.5')
    decide(long, ht)
    assert balance()==Decimal('3')
    assert Decimal(str(detail(long)['credited_days']))==Decimal('2.5')
    cancel(long)
    assert balance()==Decimal('0.5')
    check('加班与请假/加班互斥；一日阈值分流；最终批准才入账；按比例快照精确撤销退回')
    leave = submit('LEAVE', iso(future, 13), iso(future, 18), typeId=types['PERSONAL'])
    submit('OVERTIME', iso(future, 13), iso(future, 18), expect=400)
    decide(leave)
    submit('OVERTIME', iso(future, 13), iso(future, 18), expect=400)
    # Normal correction allowed inside approved leave; use same-day future-independent old leave time through yesterday-to-tomorrow leave on another user.
    cancel(leave)
    r = submit('OVERTIME', iso(future, 13), iso(future, 18)); cancel(r)
    submit('OVERTIME', iso(future, 9), expect=400)
    submit('OVERTIME', iso(future, 13), iso(future, 9), expect=400)
    submit('OVERTIME', iso(future, 9), iso(future, 13), userId=other, expect=403)
    # Concurrent final approvals on different requests must both credit exactly once.
    rs = [submit('OVERTIME', iso(future+dt.timedelta(days=n), 9), iso(future+dt.timedelta(days=n), 13)) for n in (3,4)]
    before = balance()
    with concurrent.futures.ThreadPoolExecutor(2) as pool:
        list(pool.map(decide, rs))
    assert balance()==before+Decimal('1.25')
    # Repeat approval races: one success, one business error, one ledger credit.
    race = submit('OVERTIME', iso(future+dt.timedelta(days=5), 9), iso(future+dt.timedelta(days=5), 13))
    before = balance()
    def racing(_):
        try: decide(race); return True
        except AssertionError as e:
            assert ', 400,' in str(e), str(e)
            return False
    with concurrent.futures.ThreadPoolExecutor(2) as pool:
        assert sum(pool.map(racing, range(2)))==1
    assert balance()==before+Decimal('0.625')
    changes = call('/balance-history', token=et)
    assert len([c for c in changes if c['request_id']==race])==1
    assert all(Decimal(str(c['balance_after']))>=0 for c in changes)
    assert call('/admin/balance-history?userId='+str(uid), token=at)
    call('/admin/balance-history', token=et, expect=403)
    check('起止/代提权限校验、并发批准不漏加不重复；余额流水可核对且权限隔离')
    # Consume the overtime credit, then attempt cancellation: rollback without negative balance.
    call('/admin/balances', 'PUT', dict(userId=uid, typeId=types['COMP'], year=today.year, amount=0), at)
    cancel(race, expect=400)
    assert detail(race)['status']=='APPROVED'
    assert len([c for c in call('/balance-history', token=et) if c['request_id']==race])==1
    # Restore enough then cancel and prove exact debit.
    call('/admin/balances', 'PUT', dict(userId=uid, typeId=types['COMP'], year=today.year, amount=1), at)
    cancel(race)
    assert balance()==Decimal('0.375')
    check('撤销加班余额不足时事务回滚；余额足够后精确退回，管理员录入也保留流水')
    # Concurrent duplicate correction submissions.
    concurrent_at = iso(today-dt.timedelta(days=1), 15)
    def duplicate(_):
        try: return submit('CORRECTION', concurrent_at, punchKind='NORMAL')
        except AssertionError as e:
            assert ', 400,' in str(e), str(e)
            return None
    with concurrent.futures.ThreadPoolExecutor(2) as pool:
        assert len([x for x in pool.map(duplicate, range(2)) if x])==1
    check('并发重复补卡仅生成一条待批单据')
    # Missing manager falls back to department head, including single-step long overtime.
    fallback_correction = submit('CORRECTION', iso(today-dt.timedelta(days=1), 11), token=bt, punchKind='NORMAL')
    assert detail(fallback_correction, bt)['steps'][0]['approver_id']==head
    decide(fallback_correction, ht)
    fallback_overtime = submit('OVERTIME', iso(future, 9), iso(future+dt.timedelta(days=1), 18), token=bt)
    assert len(detail(fallback_overtime, bt)['steps'])==1
    cancel(fallback_overtime, bt)
    # Approved leave remains compatible with normal corrections; it never grants FIELD eligibility.
    leave_during = submit('LEAVE', iso(today-dt.timedelta(days=1), 9), iso(today+dt.timedelta(days=1), 18), token=ot, typeId=types['PERSONAL'])
    decide(leave_during); decide(leave_during, ht)
    during = submit('CORRECTION', iso(today-dt.timedelta(days=1), 12), token=ot, punchKind='NORMAL')
    decide(during)
    submit('CORRECTION', iso(today-dt.timedelta(days=1), 12), token=ot, punchKind='FIELD', expect=400)
    call('/admin/balances', 'PUT', dict(userId=uid, typeId=types['ANNUAL'], year=today.year, amount=0.25), at, 400)
    # Cross-year overtime credits only its start year, with exactly reversible fractional credit.
    start_year = today.year+1
    cross = submit('OVERTIME', iso(dt.date(start_year,12,31), 13), iso(dt.date(start_year+1,1,1), 13))
    before_start, before_end = balance(year=start_year), balance(year=start_year+1)
    decide(cross)
    assert balance(year=start_year)==before_start+Decimal('1.25')
    assert balance(year=start_year+1)==before_end
    cancel(cross)
    assert balance(year=start_year)==before_start
    check('部门负责人兜底及审批去重；批准请假仍可正常补卡但不授予出勤；旧余额半天限制及跨年加班开始年度入账')
    csv = call('/admin/exports/requests.csv?kind=OVERTIME&userId='+str(uid), token=at)
    assert '加班' in csv and '兑换调休天数' in csv
    csv = call('/admin/exports/punches.csv?userId='+str(uid), token=at)
    assert '补卡申请编号' in csv
    assert all(r['kind']=='OVERTIME' for r in call('/requests?kind=OVERTIME&userId='+str(uid), token=at))
    check('后台按新类型筛选、中文导出及补卡来源字段')
    # Reuse test employee for browser and Dart live acceptance, identifiers only; no token/password saved.
    (ROOT/'.runtime/phase2-fixture.json').write_text(json.dumps(dict(employeeNo=prefix+'employee', userId=uid, managerId=manager, departmentId=dep, ccId=cc), ensure_ascii=False))
finally:
    call('/admin/workflow', 'PUT', settings, at)
(ROOT/'.runtime/phase2-http-checks.json').write_text(json.dumps(checks, ensure_ascii=False, indent=2))
print(f'二期 HTTP 验收通过，共 {len(checks)} 组')
