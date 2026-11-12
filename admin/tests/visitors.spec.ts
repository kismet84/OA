import {test,expect,type Page} from '@playwright/test';
import {readFileSync} from 'node:fs';
// Run scripts/verify-phase3-visitors.py first; reuse sessions to avoid login throttling.
test.use({timezoneId:'America/New_York'});
test('员工预约和取消、前台凭码签到签离、临时登记及CSV导出',async({page,browser,request})=>{
 const f=JSON.parse(readFileSync('../.runtime/visitors-http-result.json','utf8'));
 const errors:string[]=[];
 const watch=(p:Page)=>{p.on('pageerror',e=>errors.push(e.message));p.on('console',m=>{if(m.type()==='error')errors.push(m.text())})};
 const session=async(p:Page,staff:boolean,token:string)=>{
  await p.addInitScript(({key,token})=>sessionStorage.setItem(key,token),{key:staff?'oa-staff-token':'oa-token',token});
  await p.goto(staff?'/staff/':'/');
  await expect(p.locator('nav')).toBeVisible();
 };
 const nav=async(p:Page,name:string)=>p.locator('nav').getByRole('button',{name:new RegExp(name+'$')}).click();
 const btn=(p:Page,name:string)=>p.getByRole('button',{name,exact:true});
 const dialog=(p:Page)=>p.getByRole('dialog');
 const ctx=await browser.newContext({viewport:{width:390,height:844}});
 const staff=await ctx.newPage();watch(staff);watch(page);
 await session(staff,true,f.owner.token);await nav(staff,'访客管理');
 await expect(staff.getByRole('heading',{name:'访客管理',exact:true,level:2})).toBeVisible();
 const name='浏览器来访'+Date.now();
 const date=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date());
 async function fill(p:Page,n:string){
  for(const [label,value] of Object.entries({'访客姓名':n,'手机号':'13812341234','单位':'访客浏览器验收','来访事由':'交流','人数':'2','预计开始时间':date+'T00:00','预计结束时间':date+'T23:59'}))await dialog(p).getByLabel(label,{exact:true}).fill(value);
 }
 await btn(staff,'预约访客').click();await fill(staff,name);await btn(staff,'确认预约').click();
 await expect(dialog(staff)).toContainText('待到访');
 const code=(await dialog(staff).locator('strong').innerText()).trim();expect(code).toMatch(/^\d{6}$/);
 await btn(staff,'关闭访客窗口').click();await staff.getByLabel('访客关键字').fill(name);await btn(staff,'查询访客').click();
 await expect(staff.locator('tbody')).toContainText('138****1234');
 await staff.screenshot({path:'/tmp/oa-visitors-staff.png',fullPage:true});
 expect(await staff.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
 await session(page,false,f.adminToken);await nav(page,'访客管理');
 await page.getByLabel('六位访客码').fill(code);await btn(page,'凭码签到').click();await expect(dialog(page)).toContainText('已到访');
 await btn(page,'签离').last().click();await expect(dialog(page)).toContainText('已离开');await btn(page,'关闭访客窗口').click();
 await btn(page,'临时登记').click();await fill(page,name+'临时');await dialog(page).getByLabel('在职接待人').selectOption(String(f.owner.id));await btn(page,'登记并签到').click();
 await expect(dialog(page)).toContainText('已到访');await expect(dialog(page)).toContainText('临时登记');await btn(page,'签离').last().click();await expect(dialog(page)).toContainText('已离开');await btn(page,'关闭访客窗口').click();
 await btn(page,'今日访客').click();await page.getByLabel('访客关键字').fill(name);await btn(page,'查询访客').click();await expect(page.locator('tbody tr')).toHaveCount(2);
 const download=page.waitForEvent('download');await btn(page,'导出访客 CSV').click();await (await download).saveAs('/tmp/oa-visitors.csv');
 expect(readFileSync('/tmp/oa-visitors.csv').subarray(0,3).toString('hex')).toBe('efbbbf');
 await page.screenshot({path:'/tmp/oa-visitors-admin.png',fullPage:true});
 await btn(staff,'预约访客').click();await fill(staff,name+'取消');await btn(staff,'确认预约').click();await expect(dialog(staff)).toContainText('待到访');await btn(staff,'取消预约').click();await expect(dialog(staff)).toContainText('已取消');await btn(staff,'关闭访客窗口').click();
 for(const [p,names] of [[staff,['合同管理','资产领用','消息中心','提交申请']],[page,['合同管理','资产领用','消息中心','工作台']]] as [Page,string[]][]){
  for(const name of names){await nav(p,name);await expect(p.locator('main')).not.toBeEmpty();await expect(p.locator('vite-error-overlay')).toHaveCount(0)}
 }
 await expect(page.getByText('今日预约',{exact:false})).toBeVisible();
 expect(await page.title()).toBeTruthy();expect(await staff.title()).toBeTruthy();
 expect(errors).toEqual([]);await ctx.close();
});
