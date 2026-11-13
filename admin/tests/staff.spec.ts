import { test, expect, type APIRequestContext, type Page } from '@playwright/test';

test.use({ locale: 'zh-CN', timezoneId: 'America/Los_Angeles' });

const initial = 'Staff@2026Initial', changed = 'Staff@2026Changed';
async function fixture(request: APIRequestContext) {
  const call = async (method: string, path: string, data?: any, token?: string): Promise<any> => {
    const res = await request.fetch('/api'+path,{method,data,headers: token?{Authorization:'Bearer '+token}:{}});
    expect(res.ok(),await res.text()).toBeTruthy(); return res.json();
  };
  const admin = await call('POST','/auth/login',{login:'admin',password:process.env.OA_ADMIN_PASSWORD || 'Admin@2026Verified'});
  const stamp=Date.now();
  const dept=await call('POST','/admin/departments',{name:'员工网页验收'+stamp},admin.token);
  const make=async (no:string,roles:string[],managerId?:number)=>call('POST','/admin/users',{employeeNo:no,name:no,phone:roles[0]==='EMPLOYEE'?'139'+String(stamp).slice(-8):undefined,roles,departmentId:dept.id,managerId,enabled:true,password:initial,jobTitle:'网页验收',workPhone:'021-12345678'},admin.token);
  const supervisorNo='webboss'+stamp, employeeNo='webstaff'+stamp;
  const supervisor=await make(supervisorNo,['SUPERVISOR']);
  const employee=await make(employeeNo,['EMPLOYEE'],supervisor.id);
  const first=await call('POST','/auth/login',{login:supervisorNo,password:initial});
  await call('POST','/auth/password',{oldPassword:initial,newPassword:changed},first.token);
  return {call,admin,dept,employee,supervisor,employeeNo,supervisorNo,phone:'139'+String(stamp).slice(-8)};
}
async function login(page:Page,account:string,password:string){
  await page.goto('/staff/'); await expect(page).toHaveTitle('公司 OA · 员工办公');
  await page.getByLabel('工号 / 手机号').fill(account);await page.getByLabel('密码',{exact:true}).fill(password);
  await page.getByRole('button',{name:'登录员工办公',exact:true}).click();
}
const nav = (page:Page,label:string) => page.getByRole('navigation',{name:'员工功能'}).getByRole('button',{name:label,exact:true}).click();

test('员工网页：首次改密、手机号登录、六类提交、审批、公告回执与设备退出',async({page,request,browser})=>{
  test.setTimeout(180000); page.setDefaultTimeout(10000);
  const f=await fixture(request), errors:string[]=[];
  const year=Number(new Intl.DateTimeFormat('en',{timeZone:'Asia/Shanghai',year:'numeric'}).format(new Date()))+1;
  page.on('pageerror',e=>errors.push(e.message));page.on('console',m=>{if(m.type()==='error')errors.push(m.text())});
  await login(page,f.employeeNo,initial);
  await expect(page.getByRole('heading',{name:'首次登录，请修改密码'})).toBeVisible();
  await expect(page.getByRole('navigation')).toHaveCount(0);
  await page.getByLabel('原密码').fill(initial);await page.getByLabel('新密码').fill(changed);
  await page.getByRole('button',{name:'保存并重新登录'}).click();await expect(page.getByText('密码已修改，所有旧会话已退出，请重新登录')).toBeVisible();
  await login(page,f.phone,changed);await expect(page.getByRole('heading',{name:'新建申请'})).toBeVisible();
  await page.goto('/'); await expect(page.getByRole('heading',{name:'管理员登录',exact:true})).toBeVisible();
  await page.goto('/staff/'); await expect(page.getByRole('heading',{name:'新建申请'})).toBeVisible();
  const staff=await f.call('POST','/auth/login',{login:f.employeeNo,password:changed,deviceName:'待退出的手机',platform:'Android'});
  // The configured rule and balances come from the backend; no test changes global policy.
  const types=await f.call('GET','/leave-types',undefined,staff.token);
  const leaveType=types.find((t:any)=>t.code==='ANNUAL');
  await f.call('PUT','/admin/balances',{userId:f.employee.id,typeId:leaveType.id,year,amount:10},f.admin.token);
  async function submit(kind:string,date?:string){
    await nav(page,'提交申请');await page.getByLabel('申请类型',{exact:true}).selectOption(kind);
    if(kind==='LEAVE')await page.getByLabel('假期类型',{exact:true}).selectOption(String(leaveType.id));
    if(date){await page.getByLabel('开始日期',{exact:true}).fill(date);await page.getByLabel('结束日期',{exact:true}).fill(date);await page.getByLabel('结束时间',{exact:true}).selectOption('13:00')}
    if(kind==='CORRECTION'){
      const now=new Date(Date.now()-60000).toLocaleString('sv-SE',{timeZone:'Asia/Shanghai'}).slice(0,16).replace(' ','T');
      await page.getByLabel('补卡时间（北京时间）',{exact:true}).fill(now);
    }
    if(kind==='EXPENSE'){
      await page.getByLabel('报销总额（元）').fill('30');
      await page.getByLabel('用途 / 类别').fill('交通');await page.getByLabel('明细金额（元）').fill('10');
      await page.getByRole('button',{name:'添加明细'}).click();await page.getByLabel('用途 / 类别').nth(1).fill('餐费');await page.getByLabel('明细金额（元）').nth(1).fill('20');
      await page.locator('input[type=file]').setInputFiles([{name:'票据一.pdf',mimeType:'application/pdf',buffer:Buffer.from('%PDF-1.4\nweb receipt one')},{name:'票据二.pdf',mimeType:'application/pdf',buffer:Buffer.from('%PDF-1.4\nweb receipt two')}]);
      await expect(page.getByText('票据二.pdf',{exact:false})).toBeVisible();
    }
    if(kind==='OVERTIME') await page.getByLabel('抄送同事（可多选）').selectOption(String(f.supervisor.id));
    await page.getByLabel('申请理由').fill('员工网页'+kind);
    const response=page.waitForResponse(r=>r.url().endsWith('/api/requests')&&r.request().method()==='POST');
    await page.getByRole('button',{name:'提交申请',exact:true}).last().click();const res=await response;expect(res.ok(),await res.text()).toBeTruthy();const id=(await res.json()).id;
    await expect(page.getByRole('dialog',{name:'申请详情'})).toContainText('待审批');return id;
  }
  const overtime=await submit('OVERTIME',`${year}-08-20`);await page.getByLabel('关闭申请详情').click();
  for(const [kind,date] of [['LEAVE',`${year}-08-21`],['TRAVEL',`${year}-08-22`],['OUTING',`${year}-08-23`],['CORRECTION',undefined],['EXPENSE',undefined]]){
    await submit(kind!,date);
    if(kind==='EXPENSE'){
      const dialog=page.getByRole('dialog');await expect(dialog).toContainText('餐费');await expect(dialog.getByRole('button',{name:/下载：/})).toHaveCount(2);
      const downloaded=page.waitForEvent('download');await dialog.getByRole('button',{name:'下载：票据一.pdf'}).click();expect((await downloaded).suggestedFilename()).toBe('票据一.pdf');
    }
    await page.getByRole('button',{name:'撤回申请',exact:true}).click();await expect(page.getByRole('dialog')).toContainText('已撤回');await page.getByLabel('关闭申请详情').click();
  }
  const context=await browser.newContext({baseURL:test.info().project.use.baseURL,ignoreHTTPSErrors:process.env.OA_LOCAL_TLS==='true'});
  const boss=await context.newPage();await login(boss,f.supervisorNo,changed);await nav(boss,'消息中心');
  const row=boss.getByRole('row').filter({hasText:f.employeeNo});
  await row.getByRole('button',{name:'查看单据'}).click();
  await boss.getByLabel('审批意见（驳回必填）').fill('网页审批通过');await boss.getByRole('button',{name:'提交审批'}).click();
  await expect(boss.getByRole('dialog')).toContainText('已批准');await boss.getByLabel('关闭申请详情').click();
  await boss.getByRole('tab',{name:'抄送我的',exact:true}).click();await expect(boss.getByRole('table')).toContainText(f.employeeNo);await context.close();
  await nav(page,'消息中心');await page.getByRole('tab',{name:'我发起的',exact:true}).click();
  const own=page.getByRole('row').filter({hasText:'加班'});await own.getByRole('button',{name:'查看单据'}).click();
  await expect(page.getByRole('dialog')).toContainText('网页审批通过');await page.getByRole('button',{name:'撤销申请',exact:true}).click();await expect(page.getByRole('dialog')).toContainText('已撤销');await page.getByLabel('关闭申请详情').click();
  const announcement=await f.call('POST','/announcements',{title:'网页公告'+f.employeeNo,body:'请阅读并回执',departmentId:f.dept.id},f.admin.token);
  await page.getByRole('tab',{name:'公告',exact:true}).click();
  await page.getByRole('row').filter({hasText:'网页公告'+f.employeeNo}).getByRole('button',{name:'打开公告'}).click();
  await expect(page.getByRole('dialog',{name:'公告详情'})).toContainText('请阅读并回执');await page.getByLabel('关闭公告弹窗').click();
  const receipts=await f.call('GET','/announcements/'+announcement.id+'/receipts',undefined,f.admin.token);
  expect(receipts.read.some((r:any)=>r.user_id===f.employee.id && r.read_at)).toBeTruthy();
  await page.screenshot({path:'../.runtime/staff-desktop.png',fullPage:true});
  await nav(page,'通讯录');await page.getByLabel('搜索姓名 / 工号 / 部门').fill(f.supervisorNo);await page.getByRole('button',{name:'查询',exact:true}).click();await expect(page.getByRole('table')).toContainText('021-12345678');
  await nav(page,'打卡记录');await expect(page.getByText(/网页使用现场实时定位，提交前显示采集时间/)).toBeVisible();
  await nav(page,'个人中心');await expect(page.getByRole('table').first()).toContainText(String(year));
  await page.getByLabel('工作电话',{exact:true}).fill('021-87654321');await page.getByRole('button',{name:'保存工作电话'}).click();await expect(page.getByRole('status')).toContainText('工作电话已更新');
  const revoked = page.waitForResponse(r => r.url().endsWith('/api/auth/sessions/others') && r.request().method() === 'DELETE');
  await page.getByRole('button',{name:'退出其他设备'}).click();
  expect((await revoked).status()).toBe(200);
  expect((await request.get('/api/auth/me',{headers:{Authorization:'Bearer '+staff.token}})).status()).toBe(401);
  await page.setViewportSize({width:390,height:844});await nav(page,'提交申请');await expect(page.getByRole('heading',{name:'新建申请'})).toBeVisible();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();await page.screenshot({path:'../.runtime/staff-mobile.png',fullPage:true});
  await page.reload();await expect(page.getByRole('heading',{name:'新建申请'})).toBeVisible();
  await nav(page,'个人中心');await page.getByRole('button',{name:'退出此设备'}).click();await expect(page.getByRole('button',{name:'登录员工办公',exact:true})).toBeVisible();
  expect(errors).toEqual([]);expect(overtime).toBeGreaterThan(0);
});

test('员工网页：设备被退出后清除会话并返回登录', async ({page,request}) => {
  page.setDefaultTimeout(10000);
  const f=await fixture(request);
  const first=await f.call('POST','/auth/login',{login:f.employeeNo,password:initial});
  await f.call('POST','/auth/password',{oldPassword:initial,newPassword:changed},first.token);
  await login(page,f.employeeNo,changed);await expect(page.getByRole('heading',{name:'新建申请'})).toBeVisible();
  const other=await f.call('POST','/auth/login',{login:f.employeeNo,password:changed});
  await f.call('DELETE','/auth/sessions/others',undefined,other.token);
  await nav(page,'个人中心');
  await expect(page.getByRole('button',{name:'登录员工办公',exact:true})).toBeVisible();
  expect(await page.evaluate(()=>sessionStorage.getItem('oa-staff-token'))).toBeNull();
  await expect(page.getByRole('navigation')).toHaveCount(0);
});
