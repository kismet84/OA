#!/usr/bin/env python3
"""文档资料真实 HTTP 验收：使用独立测试账号及部门，不删除历史数据。"""
import io
import json
import os
import pathlib
import secrets
import urllib.request
import urllib.error
from urllib.parse import urlencode
from datetime import datetime
from zoneinfo import ZoneInfo
from PIL import Image, ImageChops

BASE=os.environ.get('OA_API','http://127.0.0.1:8080/api')
ROOT=pathlib.Path(__file__).resolve().parents[1]
checks=[]
def call(path,method='GET',data=None,token=None,expect=200,headers=None):
    h=dict(headers or {})
    if token:h['Authorization']='Bearer '+token
    if data is not None and not isinstance(data,bytes):data=json.dumps(data).encode();h['Content-Type']='application/json'
    try:
        with urllib.request.urlopen(urllib.request.Request(BASE+path,data=data,headers=h,method=method),timeout=90) as r:status,raw=r.status,r.read()
    except urllib.error.HTTPError as e:status,raw=e.code,e.read()
    assert status==expect,(method,path,status,expect,raw[:700])
    try:return json.loads(raw)
    except (ValueError,UnicodeDecodeError):return raw

def check(s):checks.append(s);print('通过：'+s,flush=True)
at=call('/auth/login','POST',dict(login='admin',password=os.environ.get('OA_ADMIN_PASSWORD','Admin@2026Verified')))['token']
prefix='docs'+secrets.token_hex(4)
def dep(s,parent=None):return call('/admin/departments','POST',dict(name=prefix+s,parentId=parent),at)['id']
parent=dep('部门');child=dep('下级',parent);outside=dep('其他')
def employee(s,department,enabled=True):
    body=dict(employeeNo=prefix+s,name='文档验收'+s,departmentId=department,enabled=enabled,roles=['EMPLOYEE'],password='Docs@2026Initial')
    uid=call('/admin/users','POST',body,at)['id']
    if not enabled:return uid,None,body
    t=call('/auth/login','POST',dict(login=body['employeeNo'],password=body['password']))['token']
    call('/auth/password','POST',dict(oldPassword=body['password'],newPassword='Docs@2026Changed'),t)
    return uid,call('/auth/login','POST',dict(login=body['employeeNo'],password='Docs@2026Changed'))['token'],body
uid,ut,ub=employee('owner',parent)
cid,ct,cb=employee('child',child);oid,ot,ob=employee('outside',outside)
did,_,_=employee('disabled',child,False)
image=Image.new('RGB',(800,600),'white');b=io.BytesIO();image.save(b,format='PNG');png=b.getvalue()
b=io.BytesIO();image.save(b,format='JPEG');jpeg=b.getvalue()
b=io.BytesIO();image.save(b,format='PDF',save_all=True,append_images=[image]);pdf=b.getvalue()
def upload(data=png,name='制度.png',token=at,expect=200):
    boundary='oa'+secrets.token_hex(8)
    raw=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: application/octet-stream\r\n\r\n').encode()+data+f'\r\n--{boundary}--\r\n'.encode()
    return call('/attachments','POST',raw,token,expect,{'Content-Type':'multipart/form-data; boundary='+boundary})
f=upload()['id'];p=upload(pdf,'制度.pdf')['id'];j=upload(jpeg,'照片.jpg')['id'];foreign=upload(token=ut)['id']
base=dict(title=prefix+'制度',description='公司制度说明',category='POLICY',departmentId=parent,attachmentIds=[f,p,j],allowDownload=True)
for path in ['/documents','/documents/1','/documents/1/receipts',f'/documents/1/attachments/{f}/preview',f'/documents/1/attachments/{f}/pages/1',f'/documents/1/attachments/{f}/download']:
    call(path,expect=401)
for path,method in [('/documents','POST'),('/documents/1','PUT'),('/documents/1/withdraw','POST'),('/documents/1/republish','POST')]:
    call(path,method,base,expect=401);call(path,method,base,ut,403)
for patch in [dict(title=' '),dict(title='长'*201),dict(description='长'*5001),dict(category='BAD'),dict(attachmentIds=[]),dict(attachmentIds=[f]*11),dict(attachmentIds=[f,f]),dict(attachmentIds=[foreign]),dict(attachmentIds=[None]),dict(attachmentIds=[str(secrets.token_hex(16))]),dict(departmentId=-1)]:
    call('/documents','POST',dict(base,**patch),at,400)
upload(b'not image',expect=400);upload(b'\x89PNG\r\n\x1a\n'+b'x'*(10*1024*1024),expect=400)
bad=upload(b'%PDF-broken','损坏.pdf')['id'];call('/documents','POST',dict(base,attachmentIds=[bad]),at,400)
missing=upload()['id'];(ROOT/'.runtime/uploads'/missing).unlink();call('/documents','POST',dict(base,attachmentIds=[missing]),at,400)
check('未登录拒绝、仅管理员操作；必填、长度、枚举、重复、所有权、类型、10MB、缺失及损坏文件校验')
id=call('/documents','POST',base,at)['id'];path=f'/documents/{id}';fp=f'{path}/attachments/{f}';pp=f'{path}/attachments/{p}'
for token in [ut,ct]:assert any(r['id']==id for r in call('/documents',token=token))
assert not any(r['id']==id for r in call('/documents',token=ot))
for suffix in ['', '/receipts',f'/attachments/{f}/preview',f'/attachments/{f}/pages/1',f'/attachments/{f}/download']:
    call(path+suffix,token=ot,expect=403)
for t in [ut,ct]:
    notices=[n for n in call('/messages?category=notifications',token=t) if n.get('document_id')==id]
    assert len(notices)==1 and notices[0]['request_id'] is None and notices[0]['task_id'] is None
assert not any(n.get('document_id')==id for n in call('/messages?category=pending',token=ut))
r=call(path+'/receipts',token=at);assert {x['user_id'] for x in r['unread']}=={uid,cid}
call(path,token=ut);assert call(path+'/receipts',token=at)['readCount']==0
call('/documents?limit=201',token=ut,expect=400);call('/documents?publishedFrom=bad',token=ut,expect=400);call('/documents?publishedFrom=2031-01-02&publishedTo=2031-01-01',token=ut,expect=400)
assert any(r['id']==id for r in call('/documents?'+urlencode(dict(category='POLICY',keyword=prefix,publishedFrom='2020-01-01',publishedTo='2090-01-01')),token=ut))
assert not call('/documents?category=OTHER&keyword='+prefix,token=ut)
check('部门含下级、排除停用和其他部门、403不泄露正文、筛选分页；仅系统通知，不进入审批待办')
info=call(pp+'/preview',token=ut);assert datetime.now(ZoneInfo('Asia/Shanghai')).date().isoformat() in info['watermark'];assert info['pages']==2 and ub['name'] in info['watermark'] and ub['employeeNo'] in info['watermark']
call(pp+'/pages/0',token=ut,expect=400);call(pp+'/pages/3',token=ut,expect=400)
assert call(path+'/receipts',token=at)['readCount']==0
marked=call(fp+'/pages/1',token=ut);im=Image.open(io.BytesIO(marked)).convert('RGB')
assert im.height>600 and ImageChops.difference(im,Image.new('RGB',im.size,'white')).getbbox()
# Red watermark pixels must occur in the content region, not only in metadata or a UI label.
assert sum(1 for r,g,b in im.crop((0,0,800,600)).getdata() if r>g+10 and r>b+10)>100
other=call(fp+'/pages/1',token=ct);assert marked!=other
(ROOT/'.runtime/docs-watermark.png').write_bytes(marked)
first=call(path+'/receipts',token=at)['read'];first=next(r for r in first if r['user_id']==uid)['read_at']
call(pp+'/pages/2',token=ut)
assert next(r for r in call(path+'/receipts',token=at)['read'] if r['user_id']==uid)['read_at']==first
out=call(pp+'/download',token=ut);assert out.startswith(b'%PDF') and out!=pdf
(ROOT/'.runtime/docs-watermark.pdf').write_bytes(out)
assert call(fp+'/download',token=ut)==marked
assert call(f'{path}/attachments/{j}/pages/1',token=ut).startswith(b'\x89PNG')
call('/attachments/'+f,token=ut,expect=403);assert call('/attachments/'+f,token=at)==png
check('PDF两页及图片直接预览；内容区有可见个性化水印；下载重绘水印PDF/PNG；首次成功预览才记已读且不覆盖')
call(path,'PUT',dict(base,allowDownload=False),at)
call(fp+'/download',token=ut,expect=403);call(pp+'/download',token=ut,expect=403)
call(fp+'/pages/1',token=ut);call('/attachments/'+f,token=ut,expect=403)
assert len([n for n in call('/messages?category=notifications',token=ut) if n.get('document_id')==id])==1
# New scope immediately revokes old recipients, grants new recipients, preserves receipts.
call(path,'PUT',dict(base,departmentId=outside,allowDownload=False),at)
call(path,token=ut,expect=403);call(fp+'/pages/1',token=ct,expect=403)
assert any(n.get('document_id')==id for n in call('/messages?category=notifications',token=ot))
r=call(path+'/receipts',token=at);assert r['unreadCount']==1 and len(r['historical'])==2
# Organization changes also revoke access immediately.
call('/admin/users/'+str(oid),'PUT',dict(ob,departmentId=parent,password=None),at)
ot=call('/auth/login','POST',dict(login=ob['employeeNo'],password='Docs@2026Changed'))['token']
call(path,token=ot,expect=403)
call(path,'PUT',dict(base,departmentId=parent,allowDownload=False),at)
# Replace attachment: old original remains guarded, current preview no longer accepts it.
f2=upload()['id'];call(path,'PUT',dict(base,attachmentIds=[f2],allowDownload=False),at)
call(fp+'/pages/1',token=ut,expect=403);call('/attachments/'+f,token=ut,expect=403)
check('关闭下载接口403且预览仍为水印；修改范围/组织立即生效、新接收人通知；历史回执和旧附件保护')
call(path+'/withdraw','POST',token=at)
assert not any(r['id']==id for r in call('/documents',token=ut))
call(path,token=ut,expect=403);call(f'{path}/attachments/{f2}/pages/1',token=ut,expect=403)
assert call(path,token=at)['status']=='WITHDRAWN'
assert call(path+'/receipts',token=at)['readCount']==2
before=len([n for n in call('/messages?category=notifications',token=ut) if n.get('document_id')==id])
call(path+'/republish','POST',token=at)
assert len([n for n in call('/messages?category=notifications',token=ut) if n.get('document_id')==id])==before+1
assert call(path,token=ut)['status']=='PUBLISHED'
logs=call('/admin/audit',token=at)
assert {'PUBLISH_DOCUMENT','UPDATE_DOCUMENT','WITHDRAW_DOCUMENT','REPUBLISH_DOCUMENT'} <= {r['action'] for r in logs if str(r['target'])==str(id)}
check('下架不可见/不可预览，管理员可查回执和原件；重新上架再通知；四类审计')
# The announcement authorization path still serves its independent original.
af=upload()['id'];aid=call('/announcements','POST',dict(title=prefix+'公告',body='原公告授权保持',departmentId=parent,attachmentIds=[af]),at)['id']
assert call('/attachments/'+af,token=ut)==png
call('/attachments/'+af,token=ct);call('/attachments/'+af,token=ot)
call('/documents','POST',dict(base,attachmentIds=[af]),at,400)
call('/announcements','POST',dict(title='不可混用',body='文档附件不可复用',attachmentIds=[f2]),at,400)
call('/announcements/'+str(aid)+'/withdraw','POST',token=at)
call('/attachments/'+af,token=ut,expect=403)
call(path+'/withdraw','POST',token=at)
all_data=dict(base,departmentId=None,attachmentIds=[f2]);all_data.pop('allowDownload')
all_id=call('/documents','POST',all_data,at)['id']
assert call(f'/documents/{all_id}',token=ut)['allow_download'] is True
all_people=call(f'/documents/{all_id}/receipts',token=at)['unread']
assert {uid,cid,oid}.issubset({r['user_id'] for r in all_people}) and did not in {r['user_id'] for r in all_people}
assert any(r['id']==all_id for r in call('/documents?'+urlencode(dict(keyword=prefix)),token=ot))
call(f'/documents/{all_id}/withdraw','POST',token=at)
check('全员范围及默认允许下载、停用账号排除')
check('公告附件授权及撤回保持原规则，与文档原件保护互不干扰')
(ROOT/'.runtime/docs-http-result.json').write_text(json.dumps(dict(checks=checks,documentId=id),ensure_ascii=False,indent=2))
print(f'共 {len(checks)} 组验收通过')
