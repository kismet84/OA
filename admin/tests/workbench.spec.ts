import { test, expect, type Page } from './fixtures';
import { readFileSync } from 'node:fs';
// Prepare with scripts/verify-phase2-workbench.py (independent HTTP/SQL fixtures).
const fixture = () => JSON.parse(readFileSync('../.runtime/workbench-fixture.json', 'utf8'));
test.use({ locale:'zh-CN',timezoneId:'America/New_York' });
async function login(page:Page, login:string, admin=false){
  await page.goto(admin?'/':'/staff/');
  await page.getByLabel('工号 / 手机号').fill(login);
  await page.getByLabel('密码',{exact:true}).fill(admin?(process.env.OA_ADMIN_PASSWORD||'Admin@2026Verified'):'Bench@2026Changed');
  await page.getByRole('button',{name:admin?'登录管理后台':'登录员工办公',exact:true}).click();
  await expect(page.getByRole('heading',{name:'考勤与审批汇总',exact:true})).toBeVisible();
}
async function range(page:Page,from:string,to:string){
  await page.getByLabel('汇总开始日期').fill(from);await page.getByLabel('汇总结束日期').fill(to);
  await page.getByRole('button',{name:'查询汇总',exact:true}).click();
  await expect(page.getByTestId('workbench-range')).toContainText(from+' 至 '+to);
}
test('员工只看本人数字、日期生效、消息数字和原入口保留，打卡分页',async({page})=>{
  const f=fixture();await login(page,f.users.employee.login);
  const box=page.getByRole('region',{name:'考勤与审批汇总'});
  await expect(box.getByLabel('统计范围',{exact:true})).toHaveCount(0);
  await range(page,f.fromDate,f.toDate);
  await expect(page.getByTestId('normalPunches')).toHaveText('252');
  await expect(page.getByTestId('leaveDays')).toHaveText('0.5');
  await expect(box.getByText(/待审批 \d+ · 待办任务 \d+ · 未读通知/)).toBeVisible();
  await expect(page.getByRole('heading',{name:'新建申请'})).toBeVisible();
  await page.setViewportSize({width:390,height:844});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBeTruthy();
  await page.setViewportSize({width:1440,height:1000});
  await box.getByText('按员工查看打卡记录',{exact:true}).click();
  await expect(box.getByLabel('查看员工打卡').locator('option')).toHaveCount(2);
  await box.getByLabel('查看员工打卡').selectOption(String(f.users.employee.id));
  await expect(box.getByRole('button',{name:'下一页记录'})).toBeEnabled();
  await box.getByRole('button',{name:'下一页记录'}).click();
  await expect(box.locator('details tbody tr')).toHaveCount(53);
  const future=new Date(f.toDate+'T00:00:00Z');future.setUTCDate(future.getUTCDate()+1);const date=future.toISOString().slice(0,10);
  await range(page,date,date);await expect(page.getByTestId('normalPunches')).toHaveText('0');
  await page.getByRole('navigation').getByRole('button',{name:'消息中心',exact:true}).click();
  await expect(page.getByRole('heading',{name:'消息中心',exact:true})).toBeVisible();
});
test('直属上级团队仅含本人和直接下属，可查看下属打卡',async({page})=>{
  const f=fixture();await login(page,f.users.manager.login);
  await page.getByLabel('统计范围',{exact:true}).selectOption('team');await range(page,f.fromDate,f.toDate);
  await expect(page.getByTestId('workbench-range')).toContainText('在职人数 2');
  await expect(page.getByTestId('normalPunches')).toHaveText('252');
  await expect(page.getByLabel('统计部门')).toHaveCount(0);
  await page.getByText('按员工查看打卡记录',{exact:true}).click();
  await expect(page.getByLabel('查看员工打卡').locator('option')).toHaveCount(3);
  await page.getByLabel('查看员工打卡').selectOption(String(f.users.employee.id));
  await expect(page.getByRole('button',{name:'下一页记录'})).toBeEnabled();
});
test('后台保留组织数字与入门引导，按部门包含下级并排除停用',async({page})=>{
  const f=fixture();await login(page,'admin',true);
  await expect(page.getByText('在册账号',{exact:true})).toBeVisible();await expect(page.getByRole('heading',{name:'开始使用'})).toBeVisible();
  await page.getByLabel('统计部门',{exact:true}).selectOption(String(f.parent));await range(page,f.fromDate,f.toDate);
  await expect(page.getByTestId('workbench-range')).toContainText('在职人数 4');await expect(page.getByTestId('normalPunches')).toHaveText('252');
  await page.getByLabel('统计部门',{exact:true}).selectOption(String(f.outside));
  await page.getByRole('button',{name:'查询汇总',exact:true}).click();await expect(page.getByTestId('normalPunches')).toHaveText('1');
  await expect(page.getByText(/未读通知/).first()).toBeVisible();
});
