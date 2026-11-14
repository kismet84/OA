import { test, expect } from './fixtures';

test.use({channel:"chromium",permissions:["geolocation"],geolocation:{latitude:31.23,longitude:121.47,accuracy:12}});
const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jWTkAAAAASUVORK5CYII=','base64');
function pdf(){
 const objects=['<< /Type /Catalog /Pages 2 0 R >>','<< /Type /Pages /Kids [3 0 R] /Count 1 >>','<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >>'];
 let body='%PDF-1.4\n';const offsets=[0];objects.forEach((o,i)=>{offsets.push(Buffer.byteLength(body));body+=`${i+1} 0 obj\n${o}\nendobj\n`});
 const xref=Buffer.byteLength(body);body+='xref\n0 4\n0000000000 65535 f \n'+offsets.slice(1).map(n=>String(n).padStart(10,'0')+' 00000 n \n').join('');return Buffer.from(body+`trailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`);
}
test('审查修复：管理员改派/代批界面与真实预览路径',async({page,request})=>{
 test.setTimeout(120000);
 const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));page.on('console',m=>{if(m.type()==='error'&&!m.location().url.endsWith('/favicon.ico'))errors.push(m.text()+' '+m.location().url)});
 page.on('dialog',d=>d.accept());
 await page.addInitScript(()=>{(window as any).cspViolations=[];document.addEventListener('securitypolicyviolation',e=>(window as any).cspViolations.push(e.violatedDirective+':'+e.blockedURI))});
 const call=async(path:string,method='GET',data?:any,token?:string)=>{const r=await request.fetch('/api'+path,{method,data,headers:token?{Authorization:'Bearer '+token}:{}});expect(r.status(),await r.text()).toBe(200);return r.json()};
 const at=(await call('/auth/login','POST',{login:'admin',password:process.env.OA_ADMIN_PASSWORD||'Admin@2026Verified'})).token;
 let testNetwork:number|undefined;
 try {
 const prefix='reviewweb'+Date.now();const dep=(await call('/admin/departments','POST',{name:prefix},at)).id;
 const make=async(suffix:string,roles:string[],managerId?:number)=>{const login=prefix+suffix;const id=(await call('/admin/users','POST',{employeeNo:login,name:login,departmentId:dep,enabled:true,roles,managerId,password:'Review@2026Initial'},at)).id;const t=(await call('/auth/login','POST',{login,password:'Review@2026Initial'})).token;await call('/auth/password','POST',{oldPassword:'Review@2026Initial',newPassword:'Review@2026Changed'},t);return {id,token:(await call('/auth/login','POST',{login,password:'Review@2026Changed'})).token}};
 const manager=await make('manager',['SUPERVISOR']);const next=await make('next',['EMPLOYEE']);const owner=await make('owner',['EMPLOYEE'],manager.id);
 const upload=async(name:string,mimeType:string,buffer:Buffer)=>{const r=await request.post('/api/attachments',{headers:{Authorization:'Bearer '+at},multipart:{file:{name,mimeType,buffer}}});expect(r.ok()).toBeTruthy();return (await r.json()).id};
 const image=await upload('review.png','image/png',png);const pdfId=await upload('review.pdf','application/pdf',pdf());
 const contract=(await call('/contracts','POST',{name:prefix,type:'SERVICE',counterparty:prefix,userId:owner.id,amount:1,signedDate:'2032-01-01',effectiveDate:'2032-01-01',attachmentIds:[image,pdfId]},at)).id;
 await call(`/contracts/${contract}/submit`,'POST',undefined,owner.token);
 const category=(await call('/assets/categories','POST',{name:prefix,kind:'ITEM',enabled:true},at)).id;
 const asset=(await call('/assets/requests','POST',{categoryId:category,quantity:1,purpose:prefix,expectedDate:'2032-01-01'},owner.token)).id;
 const workflow=(await call('/requests','POST',{kind:'OUTING',reason:prefix,startAt:'2032-06-01T09:00:00',endAt:'2032-06-01T13:00:00'},owner.token)).id;
 const response=await page.goto('/');
 const csp=response?.headers()['content-security-policy'];
 if(process.env.OA_LOCAL_TLS==='true'){expect(csp).toContain("img-src 'self' data: blob:");expect(csp).toContain("frame-src 'self' blob:");expect(csp).not.toContain('unsafe-eval')}
 await page.getByLabel('工号或手机号').fill('admin');await page.getByLabel('密码',{exact:true}).fill(process.env.OA_ADMIN_PASSWORD||'Admin@2026Verified');await page.getByRole('button',{name:'登录管理后台',exact:true}).click();await expect(page.locator('nav')).toBeVisible();expect(await page.title()).toBeTruthy();
 const nav=async(name:string)=>page.locator('nav').getByRole('button',{name:new RegExp(name+'$')}).click();
 await nav('合同管理');await page.getByLabel('对方单位关键字').fill(prefix);await page.getByRole('button',{name:'查询合同',exact:true}).click();await page.getByRole('row').filter({hasText:prefix}).getByRole('button',{name:'查看合同'}).click();
 const d=page.getByRole('dialog'),panel=page.getByRole('group',{name:'管理员审批处理'});
 await panel.getByLabel('新审批人（在职员工）').selectOption(String(next.id));await panel.getByLabel('管理员操作说明').fill('浏览器改派验收');await panel.getByRole('button',{name:'改派审批人',exact:true}).click();await expect(d).toContainText('浏览器改派验收');expect((await call(`/contracts/${contract}`,'GET',undefined,next.token)).canApprove).toBe(true);expect((await call(`/contracts/${contract}`,'GET',undefined,manager.token)).canApprove).toBe(false);
 // Exercise both real image and PDF blob preview branches under the response CSP.
 await d.locator('li').filter({hasText:'review.png'}).getByRole('button',{name:'预览合同附件'}).click();const img=d.getByAltText('合同附件预览');await expect(img).toBeVisible();await expect.poll(()=>img.evaluate((i:HTMLImageElement)=>i.naturalWidth)).toBeGreaterThan(0);await d.getByRole('button',{name:'关闭预览',exact:true}).click();
 await d.locator('li').filter({hasText:'review.pdf'}).getByRole('button',{name:'预览合同附件'}).click();await expect(d.locator('iframe')).toHaveAttribute('src',/^blob:/);await expect.poll(()=>page.frames().some(f=>f.url().startsWith('blob:'))).toBeTruthy();await page.screenshot({path:'/tmp/oa-review-contract-pdf.png'});await d.getByRole('button',{name:'关闭预览',exact:true}).click();
 await panel.getByLabel('管理员操作说明').fill('浏览器代批拒绝');await panel.getByRole('button',{name:'管理员代批驳回',exact:true}).click();await expect(d).toContainText('管理员代批：浏览器代批拒绝');await expect(panel).toHaveCount(0);await d.getByRole('button',{name:'关闭合同窗口'}).click();
 await nav('资产领用');await page.getByRole('button',{name:'领用记录',exact:true}).click();await page.getByRole('row').filter({hasText:prefix}).getByRole('button',{name:'查看领用'}).click();
 await panel.getByLabel('新审批人（在职员工）').selectOption(String(next.id));await panel.getByLabel('管理员操作说明').fill('资产改派验收');await panel.getByRole('button',{name:'改派审批人',exact:true}).click();await expect(d).toContainText('资产改派验收');await panel.getByLabel('管理员操作说明').fill('资产代批验收');await panel.getByRole('button',{name:'管理员代批同意',exact:true}).click();await expect(d).toContainText('管理员代批：资产代批验收');expect((await call(`/assets/requests/${asset}`,'GET',undefined,owner.token)).status).toBe('READY');await page.screenshot({path:'/tmp/oa-review-admin-proxy.png'});await d.getByRole('button',{name:'关闭资产窗口'}).click();
 await nav('审批与申请');await page.getByRole('row').filter({hasText:prefix}).getByRole('button',{name:'查看 / 审批',exact:true}).click();await panel.getByLabel('新审批人（在职员工）').selectOption(String(next.id));await panel.getByLabel('管理员操作说明').fill('一般审批改派');await panel.getByRole('button',{name:'改派审批人',exact:true}).click();await expect(d).toContainText('一般审批改派');await panel.getByLabel('管理员操作说明').fill('一般审批代批');await panel.getByRole('button',{name:'管理员代批驳回',exact:true}).click();await expect(d).toContainText('管理员代批：一般审批代批');expect((await call(`/requests/${workflow}`,'GET',undefined,owner.token)).status).toBe('REJECTED');await d.getByRole('button',{name:'关闭弹窗',exact:true}).click();
 const documentFile=await upload('document.png','image/png',png);await call('/documents','POST',{title:prefix,category:'FILE',attachmentIds:[documentFile]},at);
 await nav('文档资料');await page.getByLabel('标题关键字').fill(prefix);await page.getByRole('button',{name:'筛选文档',exact:true}).click();await page.getByRole('row').filter({hasText:prefix}).getByRole('button',{name:'查看文档'}).click();await d.getByRole('button',{name:'水印预览',exact:true}).click();const watermarked=d.getByAltText('带查看人水印的文档预览');await expect.poll(()=>watermarked.evaluate((i:HTMLImageElement)=>i.naturalWidth)).toBeGreaterThan(0);await page.screenshot({path:'/tmp/oa-review-document-watermark.png'});
 await d.getByRole('button',{name:'关闭文档',exact:true}).click();
 const networks=await call('/admin/networks','GET',undefined,at);if(!networks.some((n:any)=>n.kind==='IP'&&n.value==='127.0.0.1')){await call('/admin/networks','POST',{kind:'IP',value:'127.0.0.1'},at);testNetwork=(await call('/admin/networks','GET',undefined,at)).find((n:any)=>n.kind==='IP'&&n.value==='127.0.0.1').id;}
 await page.setViewportSize({width:390,height:844});await page.goto('/staff/');await page.getByLabel('工号或手机号').fill(prefix+'owner');await page.getByLabel('密码',{exact:true}).fill('Review@2026Changed');await page.getByRole('button',{name:'登录员工办公',exact:true}).click();await expect(page.locator('nav')).toBeVisible();await nav('打卡记录');
 await page.context().grantPermissions(['geolocation'],{origin:new URL(page.url()).origin});await page.context().setGeolocation({latitude:31.23,longitude:121.47,accuracy:12});
 const punchRequest=page.waitForRequest(r=>r.url().endsWith('/api/punches')&&r.method()==='POST',{timeout:15000});await page.getByRole('button',{name:'网页正常打卡',exact:true}).click();const payload=(await punchRequest).postDataJSON();expect(payload.locationSource).toBe('LIVE');expect(payload.locatedAt).toMatch(/Z$/);expect(payload.latitude).toBe(31.23);await expect(page.getByRole('status')).toContainText('打卡成功');await expect(page.getByRole('status')).toContainText('北京时间');await expect(page.locator('tbody')).toContainText('现场实时');await expect(page.getByRole('columnheader',{name:'定位采集时间',exact:true})).toBeVisible();await page.screenshot({path:'/tmp/oa-review-staff-punch.png'});
 expect(await page.evaluate(()=>(window as any).cspViolations)).toEqual([]);await expect(page.locator('vite-error-overlay')).toHaveCount(0);expect(errors).toEqual([]);
 } finally {if(testNetwork)await call('/admin/networks/'+testNetwork,'DELETE',undefined,at)}
});
