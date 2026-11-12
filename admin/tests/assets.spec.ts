import {test,expect,type Page} from '@playwright/test';
test.use({locale:'zh-CN',timezoneId:'America/New_York'});
test('资产台账、员工申请、上级待办审批、行政发放、员工归还确认和消耗品扣库存',async({page,request,browser})=>{
 test.setTimeout(120000);
 const errors:string[]=[];
 const watch=(p:Page)=>{p.on('pageerror',e=>errors.push(e.message));p.on('console',m=>{if(m.type()==='error')errors.push(m.text())})};watch(page);
 const call=async(path:string,method='GET',data?:any,token?:string)=>{
  const r=await request.fetch('/api'+path,{method,data,headers:token?{Authorization:'Bearer '+token}:{}});expect(r.status(),await r.text()).toBe(200);return r.json();
 };
 const at=(await call('/auth/login','POST',{login:'admin',password:process.env.OA_ADMIN_PASSWORD||'Admin@2026Verified'})).token;
 const stamp=Date.now();const dep=(await call('/admin/departments','POST',{name:'资产浏览器'+stamp},at)).id;
 const make=async(suffix:string,managerId?:number)=>{
  const login='assetweb'+stamp+suffix;
  const id=(await call('/admin/users','POST',{employeeNo:login,name:login,departmentId:dep,enabled:true,managerId,roles:suffix==='m'?['EMPLOYEE','SUPERVISOR']:['EMPLOYEE'],password:'Asset@2026Initial'},at)).id;
  const first=await call('/auth/login','POST',{login,password:'Asset@2026Initial'});
  await call('/auth/password','POST',{oldPassword:'Asset@2026Initial',newPassword:'Asset@2026Changed'},first.token);
  const session=await call('/auth/login','POST',{login,password:'Asset@2026Changed'});
  return {id,login,...session};
 };
 const manager=await make('m');const owner=await make('o',manager.id);
 const login=async(p:Page,no:string,admin=false)=>{
  await p.goto(admin?'/':'/staff/');await p.getByLabel('工号 / 手机号').fill(no);await p.getByLabel('密码',{exact:true}).fill(admin?(process.env.OA_ADMIN_PASSWORD||'Admin@2026Verified'):'Asset@2026Changed');
  await p.getByRole('button',{name:admin?'登录管理后台':'登录员工办公',exact:true}).click();await expect(p.locator('nav')).toBeVisible();
 };
 const nav=async(p:Page,name:string)=>{await p.locator('nav').getByRole('button',{name:new RegExp(name+'$')}).click()};
 const btn=(p:Page,name:string)=>p.getByRole('button',{name,exact:true});
 const dialog=(p:Page)=>p.getByRole('dialog');
 const close=async(p:Page)=>{await btn(p,'关闭资产窗口').click()};
 const row=(p:Page,id:number)=>p.locator('tbody tr').filter({has:p.getByRole('cell',{name:String(id),exact:true})});
 await login(page,'admin',true);await expect(page.getByRole('heading',{name:'考勤与审批汇总',exact:true})).toBeVisible();await nav(page,'资产领用');
 const cname='电脑'+stamp;const sname='文具'+stamp;
 for(const [name,kind] of [[cname,'ITEM'],[sname,'CONSUMABLE']]){
  await btn(page,'资产分类').click();await btn(page,'新增分类').click();await dialog(page).getByLabel('分类名称').fill(name!);await dialog(page).getByLabel('管理方式').selectOption(kind!);await btn(page,'保存资产信息').click();await expect(dialog(page)).toHaveCount(0);
 }
 const categories=await call('/assets/categories','GET',undefined,at);const cid=categories.find((c:any)=>c.name===cname).id;const sid=categories.find((c:any)=>c.name===sname).id;
 const code='PC-'+stamp;
 for(const consumable of [false,true]){
  await btn(page,'资产台账').click();await btn(page,'新增资产').click();await dialog(page).getByLabel('资产分类',{exact:true}).selectOption(String(consumable?sid:cid));
  await dialog(page).getByLabel('资产名称',{exact:true}).fill(consumable?'鼠标'+stamp:'办公电脑'+stamp);
  if(!consumable)await dialog(page).getByLabel('资产编号（按件必填）').fill(code);
  await btn(page,'保存资产信息').click();await expect(dialog(page)).toHaveCount(0);
 }
 const ledger=await call('/assets','GET',undefined,at);const aid=ledger.find((a:any)=>a.code===code).id;const stockId=ledger.find((a:any)=>a.name==='鼠标'+stamp).id;
 await row(page,stockId).getByRole('button',{name:'调整库存或状态'}).click();await dialog(page).getByLabel('库存增减数量（入库正数，调减负数）').fill('5');await dialog(page).getByLabel('调整原因').fill('初次入库');await btn(page,'保存资产信息').click();await expect(dialog(page)).toHaveCount(0);
 const employeeCtx=await browser.newContext({viewport:{width:390,height:844},timezoneId:'America/Los_Angeles'});const employee=await employeeCtx.newPage();watch(employee);await login(employee,owner.login);
 await expect(employee.getByText(/待办 \d+ · 未读通知/)).toBeVisible();await nav(employee,'资产领用');
 const apply=async(consumable:boolean)=>{
  await btn(employee,'发起领用').click();await employee.getByLabel('可申请资产').selectOption((consumable?'CONSUMABLE:':'ITEM:')+(consumable?stockId:cid));await employee.getByLabel('数量',{exact:true}).fill(consumable?'2':'1');await employee.getByLabel('用途',{exact:true}).fill('网页验收'+stamp);await employee.getByLabel('期望领用日期',{exact:true}).fill('2032-03-01');await btn(employee,'提交领用申请').click();await expect(dialog(employee)).toBeVisible();
  const records=await call('/assets/requests','GET',undefined,owner.token);const id=records[0].id;await close(employee);return id;
 };
 const rid=await apply(false);
 // The original approval and task stay in the same pending list.
 await call('/requests','POST',{kind:'OUTING',startAt:'2032-03-01T09:00:00',endAt:'2032-03-01T18:00:00',reason:'保留审批'},owner.token);
 await call('/tasks','POST',{title:'保留原任务'+stamp,assigneeIds:[manager.id]},owner.token);
 const managerCtx=await browser.newContext();const supervisor=await managerCtx.newPage();watch(supervisor);await login(supervisor,manager.login);await nav(supervisor,'消息中心');await expect(supervisor.getByText('待办 3 ·',{exact:false})).toBeVisible();await expect(supervisor.getByRole('row').filter({hasText:'保留原任务'})).toBeVisible();await expect(supervisor.getByRole('row').filter({hasText:'审批单'})).toBeVisible();
 await supervisor.getByRole('row').filter({hasText:'资产领用'}).getByRole('button',{name:'处理资产领用'}).click();await btn(supervisor,'同意领用').click();await expect(dialog(supervisor)).toHaveCount(0);await nav(supervisor,'消息中心');await expect(supervisor.getByText('待办 2 ·',{exact:false})).toBeVisible();
 const issue=async(id:number,item:boolean)=>{
  await btn(page,'资产待办').click();await row(page,id).getByRole('button',{name:'查看领用'}).click();
  if(item)await dialog(page).getByRole('checkbox',{name:code+' · 办公电脑'+stamp,exact:true}).check();
  await btn(page,'确认发放').click();await expect(dialog(page)).toContainText(item?'使用中':'已发放');await close(page);
 };
 await issue(rid,true);
 await btn(employee,'我的资产').click();await expect(employee.getByRole('row').filter({hasText:code})).toBeVisible();await employee.getByRole('row').filter({hasText:code}).getByRole('button',{name:'查看领用'}).click();await btn(employee,'发起归还登记').click();await expect(dialog(employee)).toContainText('等待行政确认');await close(employee);
 await btn(page,'资产待办').click();await row(page,rid).getByRole('button',{name:'查看领用'}).click();await dialog(page).getByLabel('处理说明（驳回、拒绝发放和确认归还必填）').fill('已收回并检查');await btn(page,'确认收回资产').click();await expect(dialog(page)).toContainText('已归还');await close(page);await btn(page,'资产台账').click();await expect(row(page,aid)).toContainText('在库');
 const consumable=await apply(true);await nav(supervisor,'资产领用');await btn(supervisor,'资产待办').click();await row(supervisor,consumable).getByRole('button',{name:'查看领用'}).click();await btn(supervisor,'同意领用').click();await expect(dialog(supervisor)).toHaveCount(0);await issue(consumable,false);
 const balance=(await call('/assets?categoryId='+sid,'GET',undefined,at)).find((a:any)=>a.id===stockId).stock;expect(balance).toBe(3);
 await btn(employee,'我的申请').click();await row(employee,consumable).getByRole('button',{name:'查看领用'}).click();await expect(dialog(employee)).toContainText('无需归还');await expect(btn(employee,'发起归还登记')).toHaveCount(0);await close(employee);
 await employee.screenshot({path:'/tmp/oa-assets-staff-mobile.png',fullPage:true});
 expect(await employee.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
 await btn(page,'资产台账').click();await page.screenshot({path:'/tmp/oa-assets-admin.png',fullPage:true});
 await nav(page,'工作台');await expect(page.getByRole('heading',{name:'考勤与审批汇总',exact:true})).toBeVisible();
 await expect(page.locator('vite-error-overlay')).toHaveCount(0);expect(errors).toEqual([]);
 await employeeCtx.close();await managerCtx.close();
});
