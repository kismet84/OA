import {test,expect,type APIRequestContext,type Page} from './fixtures';
async function fixture(request:APIRequestContext){
 const stamp=Date.now()+''+Math.random().toString(36).slice(2,5);
 const admin=await (await request.post('/api/auth/login',{data:{login:'admin',password:process.env.OA_ADMIN_PASSWORD||'Admin@2026Verified'}})).json();
 const headers={Authorization:'Bearer '+admin.token};
 const dept=await (await request.post('/api/admin/departments',{headers,data:{name:'优化浏览器'+stamp}})).json();
 async function user(suffix:string,roles:string[],managerId?:number){
  const login='optweb'+suffix+stamp;
  const created=await request.post('/api/admin/users',{headers,data:{employeeNo:login,name:'优化'+suffix+stamp,departmentId:dept.id,managerId,roles,enabled:true,password:'Optimize@2026Initial'}});expect(created.ok()).toBeTruthy();const id=(await created.json()).id;
  let session=await (await request.post('/api/auth/login',{data:{login,password:'Optimize@2026Initial'}})).json();
  expect((await request.post('/api/auth/password',{headers:{Authorization:'Bearer '+session.token},data:{oldPassword:'Optimize@2026Initial',newPassword:'Optimize@2026Changed'}})).ok()).toBeTruthy();
  session=await (await request.post('/api/auth/login',{data:{login,password:'Optimize@2026Changed'}})).json();return {id,token:session.token,name:'优化'+suffix+stamp};
 }
 const manager=await user('manager',['SUPERVISOR']),owner=await user('owner',['EMPLOYEE'],manager.id);
 return {admin,headers,dept,manager,owner,stamp};
}
async function session(page:Page,token:string,staff=false){await page.goto(staff?'/staff/':'/');await page.evaluate(({token,staff})=>sessionStorage.setItem(staff?'oa-staff-token':'oa-token',token),{token,staff});await page.reload();await expect(page.locator('nav')).toBeVisible();}

test('管理台规则保存、日历逐行导入、月度考勤与快捷导出',async({page,request})=>{
 const f=await fixture(request);const saved=await (await request.get('/api/admin/attendance-rules',{headers:f.headers})).json();const urge=await (await request.get('/api/admin/urge-config',{headers:f.headers})).json();
 const day='2098-10-01';
 const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));
 try{
  await session(page,f.admin.token);await page.locator('nav').getByRole('button',{name:'考勤规则'}).click();
  await page.getByLabel('迟到宽限（分钟）').fill('6');await page.getByRole('button',{name:'保存考勤规则',exact:true}).click();await expect(page.getByText('考勤规则已保存')).toBeVisible();
  await page.getByLabel('日历年份').fill('2098');await page.getByLabel('批量导入日历').fill(day+',HOLIDAY,验收节日\nbad,WORKDAY,错误日期');await page.getByRole('button',{name:'批量保存日历'}).click();await expect(page.getByText('成功 1 行，失败 1 行')).toBeVisible();await expect(page.getByText('第 2 行：日期格式无效')).toBeVisible();
  await page.getByLabel('手动催办冷却（分钟）').fill('121');await page.getByRole('button',{name:'保存催办配置'}).click();await expect(page.getByText('催办配置已保存')).toBeVisible();
  await page.locator('nav').getByRole('button',{name:'月度考勤'}).click();await expect(page.getByRole('heading',{name:'月度考勤',exact:true,level:2})).toBeVisible();await expect(page.getByRole('columnheader',{name:'缺卡次数'})).toBeVisible();
  const downloaded=page.waitForEvent('download');await page.getByRole('button',{name:'导出月度考勤',exact:true}).click();expect((await downloaded).suggestedFilename()).toBe('月度考勤.csv');
  await page.screenshot({path:'../.runtime/opt-attendance-desktop.png',fullPage:false});
  await page.locator('nav').getByRole('button',{name:'工作台'}).click();await expect(page.getByRole('heading',{name:'快捷导出'})).toBeVisible();await expect(page.getByRole('button',{name:'本月报销汇总',exact:true})).toBeVisible();
  expect(errors).toEqual([]);
 }finally{await request.put('/api/admin/attendance-rules',{headers:f.headers,data:saved});await request.put('/api/admin/urge-config',{headers:f.headers,data:urge});await request.delete('/api/admin/work-calendar/'+day,{headers:f.headers});}
});

test('员工大图压缩、PDF原样上传、模板管理、复制再发起、催办冷却与导航角标刷新',async({page,request,browser})=>{
 test.setTimeout(120000);const f=await fixture(request),oh={Authorization:'Bearer '+f.owner.token};
 const future='2032-11-20';const submitted=await request.post('/api/requests',{headers:oh,data:{kind:'OUTING',reason:'复制源'+f.stamp,startAt:future+'T09:00:00',endAt:future+'T13:00:00'}});expect(submitted.ok()).toBeTruthy();const rid=(await submitted.json()).id;
 await session(page,f.manager.token);const badge=page.locator('nav .nav-badge[aria-label="审批单据待办 1"]');await expect(badge).toHaveText('1');await badge.click();await expect(page.getByRole('row').filter({hasText:f.owner.name})).toBeVisible();await expect(page.getByRole('row').filter({hasText:f.owner.name})).toHaveCount(1);
 const staff=await browser.newPage();await session(staff,f.owner.token,true);
 await staff.getByLabel('申请类型',{exact:true}).selectOption('OUTING');await staff.getByLabel('申请理由').fill('模板内容'+f.stamp);await staff.getByLabel('模板名称',{exact:true}).fill('常用拜访');await staff.getByRole('button',{name:'保存为常用模板',exact:true}).click();await expect(staff.getByText('模板已保存',{exact:true})).toBeVisible();
 await staff.getByLabel('申请理由').fill('更改后');await staff.getByLabel('从模板填单').selectOption({label:'常用拜访（外出）'});await expect(staff.getByLabel('申请理由')).toHaveValue('模板内容'+f.stamp);await expect(staff.getByText('请重新选择日期，附件不会复制',{exact:true})).toBeVisible();
 const image=await staff.evaluate(()=>{const canvas=document.createElement('canvas');canvas.width=4000;canvas.height=3000;const ctx=canvas.getContext('2d')!,data=ctx.createImageData(4000,3000);let seed=17;for(let i=0;i<data.data.length;i+=4){seed=(Math.imul(seed,1664525)+1013904223)|0;data.data[i]=seed&255;data.data[i+1]=(seed>>>8)&255;data.data[i+2]=(seed>>>16)&255;data.data[i+3]=255;}ctx.putImageData(data,0,0);return canvas.toDataURL('image/jpeg',0.98).split(',')[1]!;});
 const original=Buffer.from(image,'base64');expect(original.length).toBeGreaterThan(3*1024*1024);
 let uploaded=staff.waitForResponse(r=>r.url().endsWith('/api/attachments')&&r.request().method()==='POST');
 await staff.locator('input[type=file]').setInputFiles({name:'大图.jpeg',mimeType:'image/jpeg',buffer:original});const photo=await (await uploaded).json();await expect(staff.getByText('大图.jpg',{exact:false})).toBeVisible();
 const photoResponse=await request.get('/api/attachments/'+photo.id,{headers:oh});expect(photoResponse.ok()).toBeTruthy();const compressed=await photoResponse.body();expect(compressed.length).toBeLessThan(original.length);expect(compressed[0]).toBe(255);expect(compressed[1]).toBe(216);
 const pdf=Buffer.from('%PDF-1.4\nexact PDF bytes\n');uploaded=staff.waitForResponse(r=>r.url().endsWith('/api/attachments')&&r.request().method()==='POST');await staff.locator('input[type=file]').setInputFiles({name:'原始.pdf',mimeType:'application/pdf',buffer:pdf});const file=await (await uploaded).json();const got=await request.get('/api/attachments/'+file.id,{headers:oh});expect(await got.body()).toEqual(pdf);
 await staff.locator('nav').getByRole('button',{name:'消息中心',exact:true}).click();await staff.getByRole('tab',{name:'我发起的',exact:true}).click();await staff.getByRole('row').filter({hasText:f.owner.name}).getByRole('button',{name:'查看单据'}).click();let dialog=staff.getByRole('dialog');await dialog.getByRole('button',{name:'催办',exact:true}).click();await expect(dialog.getByRole('button',{name:'催办',exact:true})).toBeDisabled();await expect(dialog.getByText(/可再次催办时间/)).toBeVisible();
 await dialog.getByRole('button',{name:'复制再发起'}).click();await expect(staff.getByRole('dialog')).toHaveCount(0);await expect(staff.getByLabel('开始日期',{exact:true})).toHaveValue('');await expect(staff.getByLabel('申请理由')).toHaveValue('复制源'+f.stamp);
 await request.post('/api/requests/'+rid+'/decision',{headers:{Authorization:'Bearer '+f.manager.token},data:{approve:true}});await page.evaluate(()=>window.dispatchEvent(new Event('focus')));await expect(page.locator('nav .nav-badge[aria-label^="审批单据待办"]')).toHaveCount(0);
 await staff.setViewportSize({width:390,height:844});await staff.screenshot({path:'../.runtime/opt-staff-mobile.png',fullPage:false});await expect(staff.locator('vite-error-overlay')).toHaveCount(0);
 await staff.getByText('模板管理',{exact:true}).click();await staff.getByRole('button',{name:'删除模板',exact:true}).click();await expect(staff.getByRole('button',{name:'删除模板',exact:true})).toHaveCount(0);await staff.close();
});
