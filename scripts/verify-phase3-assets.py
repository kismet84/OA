#!/usr/bin/env python3
"""资产领用真实 HTTP 验收；创建独立测试数据，保留已有记录。"""
import concurrent.futures
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
prefix = 'asset'+secrets.token_hex(4)
dep = call('/admin/departments', 'POST', dict(name=prefix), at)['id']

def user(suffix, roles=None, manager=None):
    body = dict(employeeNo=prefix+suffix, name='资产验收'+suffix, departmentId=dep, enabled=True, roles=roles or ['EMPLOYEE'], managerId=manager, password='Asset@2026Initial')
    uid = call('/admin/users', 'POST', body, at)['id']
    t = call('/auth/login', 'POST', dict(login=body['employeeNo'], password=body['password']))['token']
    call('/auth/password', 'POST', dict(oldPassword=body['password'], newPassword='Asset@2026Changed'), t)
    t = call('/auth/login', 'POST', dict(login=body['employeeNo'], password='Asset@2026Changed'))['token']
    return dict(id=uid, token=t, body=body, login=body['employeeNo'])

manager = user('manager', ['EMPLOYEE', 'SUPERVISOR'])
owner = user('owner', manager=manager['id'])
other = user('other')
finance = user('finance', ['EMPLOYEE', 'FINANCE'])
ut, mt, ot, ft = (u['token'] for u in [owner, manager, other, finance])
catbody = dict(name=prefix+'电脑', kind='ITEM', enabled=True)
cid = call('/assets/categories', 'POST', catbody, at)['id']
ccid = call('/assets/categories', 'POST', dict(name=prefix+'办公用品', kind='CONSUMABLE', enabled=True), ft)['id']
itembody = dict(categoryId=cid, code=prefix+'-01', name='验收笔记本', brand='通用', location='行政柜', enabled=True)
aid = call('/assets', 'POST', itembody, at)['id']
bid = call('/assets', 'POST', dict(itembody, code=prefix+'-02'), ft)['id']
sid = call('/assets', 'POST', dict(categoryId=ccid, name=prefix+'鼠标', unit='个', enabled=True), ft)['id']

def adjust(id, **data):
    return call(f'/assets/{id}/adjust', 'POST', dict(reason='验收入库或维护', **data), ft)
def detail(id, t=ut):
    return call(f'/assets/requests/{id}', token=t)
def action(id, name, data=None, t=ut, expect=200):
    return call(f'/assets/requests/{id}/{name}', 'POST', data, t, expect)
def apply(t=ut, **patch):
    return call('/assets/requests', 'POST', (dict(categoryId=cid, quantity=1, purpose='办公使用', expectedDate='2032-03-01') | patch), t)['id']
def pending(t):
    return call('/messages/summary', token=t)['pending']
def stock():
    return next(a['stock'] for a in call('/assets?categoryId='+str(ccid), token=ft) if a['id']==sid)
def summary():
    return call('/workbench/summary?from=2032-03-01&to=2032-03-31', token=ut)

for path, method, data in [('/assets', 'GET', None), ('/assets/catalog', 'GET', None), ('/assets/categories', 'GET', None), ('/assets/mine', 'GET', None), ('/assets/requests', 'POST', {}), ('/assets/requests/1/issue', 'POST', {}), ('/assets/requests/1/approve', 'POST', {}), ('/assets/requests/1/confirm-return', 'POST', {})]:
    call(path, method, data, expect=401)
for t in [ut, mt, ot]:
    call('/assets', token=t, expect=403)
    call('/assets/categories', 'POST', catbody, t, 403)
    call('/assets', 'POST', itembody, t, 403)
    call('/assets/requests?scope=all', token=t, expect=403)
    call(f'/assets/{aid}/adjust', 'POST', dict(status='SCRAPPED', reason='越权'), t, 403)
    call('/assets/outstanding/'+str(owner['id']), token=t, expect=403)
call('/assets', 'POST', itembody, at, 409)
call('/assets/mine?offset=-1', token=ut, expect=400)
call('/assets/requests?scope=bad', token=ut, expect=400)
call('/assets/requests', 'POST', dict(categoryId=cid, quantity=0, purpose='测试', expectedDate='2032-03-01'), ut, 400)
call('/assets/requests', 'POST', dict(categoryId=cid, quantity=1, purpose='测试', expectedDate='2032-03-01', returnDate='2032-02-01'), ut, 400)
call(f'/assets/{sid}/adjust', 'POST', dict(delta=1, reason=''), at, 400)
check('未登录401、员工和上级越权403、编号重复409、参数与原因400')
# Existing approval and task must coexist with assets.
call('/requests', 'POST', dict(kind='OUTING', startAt='2032-03-01T09:00:00', endAt='2032-03-01T18:00:00', reason='并存审批'), ut)
call('/tasks', 'POST', dict(title=prefix+'并存任务', description='保留待办', assigneeIds=[manager['id']]), ut)
base = pending(mt)
assert base == 2
before = summary()
rid = apply()
assert detail(rid)['status']=='PENDING' and detail(rid)['needs_return']
assert pending(mt)==base+1
mixed = call('/messages?category=pending', token=mt)
assert {r['item_type'] for r in mixed}=={'REQUEST', 'TASK', 'ASSET'}
assert len(mixed)==pending(mt)
call(f'/assets/requests/{rid}', token=ot, expect=403)
action(rid, 'approve', dict(approved=True), ot, 403)
action(rid, 'issue', dict(assetIds=[aid]), ut, 403)
fbase = pending(ft)
action(rid, 'approve', dict(approved=True, note='同意'+('审'*998)), mt)
assert detail(rid)['status']=='READY' and pending(mt)==base and pending(ft)==fbase+1
assert not call('/assets/requests?scope=pending&userId='+str(other['id']), token=mt)
action(rid, 'issue', dict(assetIds=[]), ft, 400)
action(rid, 'issue', dict(assetIds=[aid], note='已交付'), ft)
assert detail(rid)['status']=='IN_USE' and pending(ft)==fbase
assert any(a['asset_id']==aid and a['returned_at'] is None for a in call('/assets/mine', token=ut))
assert call('/assets/outstanding/'+str(owner['id']), token=at)['count']==1
call(f'/assets/{aid}/adjust', 'POST', dict(status='SCRAPPED', reason='已领用不能报废'), ft, 409)
check('直属上级一次审批、待发放与发放状态、审批任务资产三类待办数字合并、我的资产和未归还提示')
r2 = apply(); action(r2, 'approve', dict(approved=True), mt)
action(r2, 'issue', dict(assetIds=[aid]), ft, 409)
assert detail(r2)['status']=='READY'
action(rid, 'withdraw', t=ut, expect=409)
action(rid, 'return', dict(note='员工归还'))
assert pending(ft)==fbase+2  # r2 ready + rid return
assert detail(rid)['return_requested']
action(rid, 'return', {}, expect=409)
action(rid, 'confirm-return', dict(note='越权'), ut, 403)
action(rid, 'confirm-return', dict(note='行政已收回'), ft)
assert detail(rid)['status']=='RETURNED' and not detail(rid)['return_requested']
assert next(a for a in call('/assets?categoryId='+str(cid), token=at) if a['id']==aid)['status']=='AVAILABLE'
action(r2, 'issue', dict(assetIds=[aid]), ft)
action(r2, 'confirm-return', dict(note='离职直接收回', repair=True), ft)
assert next(a for a in call('/assets?categoryId='+str(cid), token=at) if a['id']==aid)['status']=='REPAIR'
adjust(aid, status='SCRAPPED'); adjust(aid, status='AVAILABLE')
assert call('/assets/outstanding/'+str(owner['id']), token=at)['count']==0
check('不能重复发放或直接报废；员工登记行政确认回到在库；行政直接收回维修、报废与恢复保留历史')
for stage in ['PENDING','READY']:
    r=apply()
    if stage=='READY': action(r,'approve',dict(approved=True),mt)
    action(r,'withdraw')
    assert detail(r)['status']=='WITHDRAWN'
    action(r,'issue',dict(assetIds=[bid]),ft,409)
r=apply(); action(r,'approve',dict(approved=False,note='用途不符'),mt); assert detail(r)['status']=='REJECTED'
r=apply(); action(r,'approve',dict(approved=True),mt); action(r,'issue',dict(rejected=True,note='无法安排'),ft); assert detail(r)['status']=='REJECTED'
r=apply(ot,needsReturn=False); assert detail(r,ot)['status']=='READY'
action(r,'issue',dict(assetIds=[bid]),ft); assert detail(r,ot)['status']=='ISSUED'
action(r,'confirm-return',dict(note='无需预计归还仍可直接收回'),ft)
check('发放前两个阶段可撤回；上级驳回与行政拒发；无上级直接待发放、按件可选无需归还')
adjust(sid,delta=3)
def consume():
    return apply(ot,categoryId=None,consumableId=sid,quantity=2,needsReturn=True)
x,y=consume(),consume()
# A real concurrent issue race must never take the balance below zero.
def race(id):
    try:
        action(id,'issue',{},ft)
        return 200
    except AssertionError as e:
        assert e.args[0][2]==409, e
        return 409
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    assert sorted(pool.map(race,[x,y]))==[200,409]
assert stock()==1
issued=x if detail(x,ot)['status']=='ISSUED' else y
assert not detail(issued,ot)['needs_return']
action(issued,'return',{},ot,409)
call(f'/assets/{sid}/adjust','POST',dict(delta=-2,reason='库存不足'),ft,409);assert stock()==1
# Single item race for the same physical asset, separate applications.
x,y=apply(ot),apply(ot)
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    def itemrace(id):
        try: action(id,'issue',dict(assetIds=[aid]),ft);return 200
        except AssertionError as e:
            assert e.args[0][2]==409,e
            return 409
    assert sorted(pool.map(itemrace,[x,y]))==[200,409]
# Multiple-item issuance is atomic even if an earlier asset was available.
third=call('/assets','POST',dict(itembody,code=prefix+'-03'),ft)['id']
fourth=call('/assets','POST',dict(itembody,code=prefix+'-04'),ft)['id']
multi=apply(ot,quantity=2)
call(f'/assets/{fourth}/adjust','POST',dict(status='REPAIR',reason='检修'),ft)
action(multi,'issue',dict(assetIds=[third,fourth]),ft,409)
assert detail(multi,ot)['status']=='READY' and not detail(multi,ot)['assignments']
assert next(a for a in call('/assets',token=ft) if a['id']==third)['status']=='AVAILABLE'
adjust(fourth,status='AVAILABLE')
action(multi,'issue',dict(assetIds=[third,fourth],note='发'*1000),ft)
assert len(detail(multi,ot)['assignments'])==2
action(multi,'confirm-return',dict(note='收'*1000),ft)
assert all(a['returned_at'] for a in detail(multi,ot)['assignments'])
assert all(a['status']=='AVAILABLE' for a in call('/assets',token=ft) if a['id'] in [third,fourth])
check('多件发放事务回滚与整单归还、1000字处理说明不影响审计与业务提交')
check('真实并发：消耗品库存不足409且余额不负、按件同一编号仅能发放一次；消耗品无需归还')
assert summary()==before
records=call('/assets/requests?scope=all&userId='+str(owner['id'])+'&departmentId='+str(dep)+'&assetId='+str(aid),token=ft)
assert records and len(records)<=200 and all(r['user_id']==owner['id'] for r in records)
assert all('user_id' not in c and 'serial_number' not in c for c in call('/assets/catalog',token=ut))
call('/assets/categories/'+str(cid),'PUT',dict(catbody,enabled=False),at)
assert not any(c['kind']=='ITEM' and c['id']==cid for c in call('/assets/catalog',token=ut))
assert detail(rid)['status']=='RETURNED'
call('/assets/categories/'+str(cid),'PUT',catbody,at)
notices=call('/messages?category=notifications',token=ut)
assert any(n.get('asset_request_id')==rid and '归还已确认' in n['message'] for n in notices)
check('台账停用保留历史、按人部门资产查询、员工目录不泄露明细、复用系统通知；工作台六类统计完全不变')
(ROOT/'.runtime/assets-http-result.json').write_text(json.dumps(dict(checks=checks,prefix=prefix,departmentId=dep),ensure_ascii=False,indent=2))
print('资产 HTTP 验收全部通过',flush=True)
