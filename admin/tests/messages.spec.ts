import { test, expect } from './fixtures';

test('公告部门发布、多附件、回执与撤回；员工消息中心和抄送只读', async ({ page, request }) => {
  const suffix = Date.now().toString().slice(-9);
  const admin = await (await request.post('/api/auth/login', { data: { login:'admin', password:process.env.OA_ADMIN_PASSWORD || 'Admin@2026Verified' } })).json();
  const headers = { Authorization:'Bearer '+admin.token };
  const users = await (await request.get('/api/admin/users',{headers})).json();
  const manager = users.find((u:any)=>u.enabled && u.roles.includes('SUPERVISOR'));
  const department = await (await request.post('/api/admin/departments',{headers,data:{name:'消息浏览器部门'+suffix}})).json();
  async function create(kind:string) {
    const login='msgweb'+kind+suffix;
    const created=await request.post('/api/admin/users',{headers,data:{employeeNo:login,name:'消息浏览器'+kind+suffix,roles:['EMPLOYEE'],departmentId:department.id,managerId:manager.id,enabled:true,password:'Message@2026Initial'}});
    expect(created.ok()).toBeTruthy();
    const id=(await created.json()).id;
    const auth=await (await request.post('/api/auth/login',{data:{login,password:'Message@2026Initial'}})).json();
    expect((await request.post('/api/auth/password',{headers:{Authorization:'Bearer '+auth.token},data:{oldPassword:'Message@2026Initial',newPassword:'Message@2026Changed'}})).ok()).toBeTruthy();
    const session=await request.post('/api/auth/login',{data:{login,password:'Message@2026Changed'}});
    expect(session.ok()).toBeTruthy();
    return {id,login,token:(await session.json()).token};
  }
  const employee=await create('employee'), cc=await create('cc');
  const initialSummary=await request.get('/api/messages/summary',{headers:{Authorization:'Bearer '+cc.token}});
  expect(initialSummary.ok()).toBeTruthy();
  const initialUnread=(await initialSummary.json()).announcements;
  expect(typeof initialUnread).toBe('number');
  const errors:string[]=[];
  page.on('pageerror',e=>errors.push(e.message));
  page.on('console',m=>{if(m.type()==='error') errors.push(m.text());});
  async function signIn(login:string,password:string) {
    await page.goto('/');
    await page.getByLabel('工号 / 手机号').fill(login);
    await page.getByLabel('密码',{exact:true}).fill(password);
    await page.getByRole('button',{name:'登录管理后台',exact:true}).click();
    await expect(page.locator('nav')).toBeVisible();
  }
  async function signOut() {
    await page.getByRole('button',{name:'退出',exact:true}).click();
    await expect(page.getByRole('heading',{name:'管理员登录',exact:true})).toBeVisible();
  }
  await signIn('admin',process.env.OA_ADMIN_PASSWORD || 'Admin@2026Verified');
  await page.locator('nav').getByRole('button',{name:'公告管理'}).click();
  await page.getByRole('button',{name:'发布公告',exact:true}).click();
  let dialog=page.getByRole('dialog');
  const title='浏览器公告'+suffix;
  await dialog.getByLabel('公告标题').fill(title);
  await dialog.getByLabel('公告正文').fill('中文公告正文\n请阅读附件。');
  await dialog.getByLabel('发布范围').selectOption(String(department.id));
  await dialog.getByLabel('公告附件').setInputFiles([
    {name:'通知一.pdf',mimeType:'application/pdf',buffer:Buffer.from('%PDF-1.4\nnotice 1')},
    {name:'通知二.pdf',mimeType:'application/pdf',buffer:Buffer.from('%PDF-1.4\nnotice 2')},
  ]);
  await expect(dialog).toContainText('通知二.pdf');
  await expect(dialog.getByRole('button',{name:'确认发布'})).toBeEnabled();
  await dialog.getByRole('button',{name:'确认发布'}).click();
  await expect(dialog).not.toBeVisible();
  let row=page.getByRole('row').filter({hasText:title});
  await row.getByRole('button',{name:'查看公告与回执'}).click();
  await expect(dialog).toContainText('已读 0 人 / 未读 2 人');
  await page.screenshot({path:'../.runtime/announce-admin.png',fullPage:true});
  await dialog.getByRole('button',{name:'关闭公告弹窗'}).click();
  // Submit via existing request UI with CC selection.
  await page.locator('nav').getByRole('button',{name:'审批单据'}).click();
  await page.getByRole('button',{name:'提交申请',exact:true}).click();
  await dialog.getByLabel('代提交员工').selectOption(String(employee.id));
  await dialog.getByLabel('申请类型',{exact:true}).selectOption('OUTING');
  await dialog.getByLabel('抄送同事（可多选）').selectOption(String(cc.id));
  const day=new Date(Date.now()+86400000*3).toISOString().slice(0,10);
  await dialog.getByLabel('开始日期').fill(day);
  await dialog.getByLabel('结束日期').fill(day);
  await dialog.getByLabel('申请理由').fill('消息浏览器抄送申请'+suffix);
  await dialog.getByRole('button',{name:'保存',exact:true}).click();
  await expect(dialog).not.toBeVisible();
  await signOut();
  await signIn(cc.login,'Message@2026Changed');
  await expect(page.getByRole('heading',{name:'消息中心',exact:true})).toBeVisible();
  expect(page.url()).toContain('127.0.0.1:5173');
  await expect(page).toHaveTitle(/OA/);
  await expect(page.locator('vite-error-overlay')).toHaveCount(0);
  await expect(page.locator('nav').getByRole('button',{name:'公告管理'})).toHaveCount(0);
  await expect(page.getByText('未读公告 '+(initialUnread+1),{exact:false})).toBeVisible();
  await page.getByRole('tab',{name:'公告',exact:true}).click();
  row=page.getByRole('row').filter({hasText:title});
  await expect(row.getByRole('button',{name:'标记已读'})).toBeVisible();
  await row.getByRole('button',{name:'打开公告'}).click();
  dialog=page.getByRole('dialog');
  await expect(dialog).toContainText('中文公告正文');
  const download=page.waitForEvent('download');
  await dialog.getByRole('button',{name:'下载：通知一.pdf',exact:true}).click();
  expect((await download).suggestedFilename()).toBe('通知一.pdf');
  await dialog.getByRole('button',{name:'关闭公告弹窗'}).click();
  await expect(row.getByRole('button',{name:'标记已读'})).toHaveCount(0);
  await expect(page.getByText('未读公告 '+initialUnread,{exact:false})).toBeVisible();
  await page.getByRole('tab',{name:'抄送我的'}).click();
  await page.getByRole('button',{name:'查看单据',exact:true}).click();
  await expect(dialog).toContainText('消息浏览器抄送申请'+suffix);
  await expect(dialog).toContainText('消息浏览器cc'+suffix);
  await expect(dialog.getByRole('button',{name:'提交审批'})).toHaveCount(0);
  await expect(dialog.getByRole('button',{name:/撤回申请|标记已支付/})).toHaveCount(0);
  await dialog.getByRole('button',{name:'关闭弹窗',exact:true}).click();
  await page.getByRole('tab',{name:'系统通知（站内通知）'}).click();
  await page.getByRole('button',{name:'标为已读'}).click();
  await expect(page.getByRole('button',{name:'标为已读'})).toHaveCount(0);
  await page.getByRole('tab',{name:'待我审批'}).click();
  await page.getByRole('tab',{name:'我发起的'}).click();
  await page.getByRole('tab',{name:'抄送我的'}).click();
  await page.screenshot({path:'../.runtime/messages-employee.png',fullPage:true});
  await page.setViewportSize({width:390,height:844});
  await expect(page.getByRole('tab',{name:'公告',exact:true})).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
  await page.screenshot({path:'../.runtime/messages-mobile.png',fullPage:true});
  await page.setViewportSize({width:1440,height:1000});
  await signOut();
  await signIn('admin',process.env.OA_ADMIN_PASSWORD || 'Admin@2026Verified');
  await page.locator('nav').getByRole('button',{name:'公告管理'}).click();
  row=page.getByRole('row').filter({hasText:title});
  await row.getByRole('button',{name:'查看公告与回执'}).click();
  await expect(dialog).toContainText('已读 1 人 / 未读 1 人');
  await dialog.getByRole('button',{name:'关闭公告弹窗'}).click();
  await row.getByRole('button',{name:'撤回公告',exact:true}).click();
  await expect(row.getByRole('button',{name:'撤回公告',exact:true})).toHaveCount(0);
  await signOut();
  await signIn(cc.login,'Message@2026Changed');
  await page.getByRole('tab',{name:'公告',exact:true}).click();
  await expect(page.getByRole('row').filter({hasText:title})).toHaveCount(0);
  expect(errors).toEqual([]);
});
