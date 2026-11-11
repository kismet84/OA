import { test, expect, type APIRequestContext, type Page } from '@playwright/test';

test.use({ locale: 'zh-CN', timezoneId: 'America/New_York' });
async function fixture(request: APIRequestContext) {
  const call = async (path: string, method = 'GET', data?: any, token?: string, status = 200) => {
    const r = await request.fetch('/api' + path, { method, data,
      headers: token ? { Authorization: 'Bearer ' + token } : {} });
    expect(r.status(), await r.text()).toBe(status);
    return r.json();
  };
  const admin = await call('/auth/login', 'POST', {
    login: 'admin', password: process.env.OA_ADMIN_PASSWORD || 'Admin@2026Verified',
  });
  const stamp = Date.now();
  const dept = await call('/admin/departments', 'POST', { name: '任务浏览器' + stamp }, admin.token);
  const make = async (suffix: string, managerId?: number, roles = ['EMPLOYEE']) => {
    const no = 'taskweb' + stamp + suffix;
    const u = await call('/admin/users', 'POST', { employeeNo: no, name: no,
      departmentId: dept.id, enabled: true, managerId, roles, password: 'Todo@2026Initial' }, admin.token);
    const first = await call('/auth/login', 'POST', { login: no, password: 'Todo@2026Initial' });
    await call('/auth/password', 'POST', { oldPassword: 'Todo@2026Initial', newPassword: 'Todo@2026Changed' }, first.token);
    const session = await call('/auth/login', 'POST', { login: no, password: 'Todo@2026Changed' });
    return { ...u, no, ...session };
  };
  const guest = await make('g', undefined, ['EMPLOYEE', 'SUPERVISOR']);
  const owner = await make('o', guest.id);
  return { call, admin, guest, owner, stamp };
}
async function login(page: Page, no: string, admin = false) {
  await page.goto(admin ? '/' : '/staff/');
  await page.getByLabel('工号 / 手机号').fill(no);
  await page.getByLabel('密码', { exact: true }).fill(admin
    ? process.env.OA_ADMIN_PASSWORD || 'Admin@2026Verified' : 'Todo@2026Changed');
  await page.getByRole('button', { name: admin ? '登录管理后台' : '登录员工办公', exact: true }).click();
  await expect(page.locator('nav')).toBeVisible();
}

test('员工创建指派、审批任务混排、通知详情、跟进完成后移出，手机宽度可用', async ({ page, request, browser }) => {
  const f = await fixture(request);
  const errors: string[] = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  await login(page, f.owner.no);
  await expect(page).toHaveTitle(/员工/);
  await page.locator('nav').getByRole('button', { name: '待办任务' }).click();
  await page.getByRole('button', { name: '创建任务', exact: true }).click();
  const d = page.getByRole('dialog');
  const title = '网页指派任务' + f.stamp;
  await d.getByLabel('任务标题').fill(title);
  await d.getByLabel('任务说明', { exact: true }).fill('任务说明只对参与者可见');
  await d.getByLabel('截止日期时间（北京时间，可选）').fill('2001-01-01T18:00');
  await d.getByRole('checkbox', { name: f.guest.no + ' · ' + f.guest.no, exact: true }).check();
  await d.getByRole('button', { name: '保存任务', exact: true }).click();
  await expect(d).toContainText('已过期');
  await expect(d).toContainText('2001-01-01 18:00');
  await d.getByRole('button', { name: '修改任务', exact: true }).click();
  await d.getByLabel('任务说明', { exact: true }).fill('已修改的任务说明');
  await d.getByRole('button', { name: '保存任务', exact: true }).click();
  await expect(d).toContainText('已修改的任务说明');
  await d.getByRole('button', { name: '关闭任务', exact: true }).click();
  // Create an ordinary approval using its existing API; both must remain independently actionable.
  const rid = (await f.call('/requests', 'POST', { kind: 'OUTING', startAt: '2032-03-01T09:00:00',
    endAt: '2032-03-01T18:00:00', reason: '并存审批' + f.stamp }, f.owner.token)).id;
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, locale: 'zh-CN', timezoneId: 'America/Los_Angeles' });
  const guest = await ctx.newPage();
  guest.on('pageerror', e => errors.push(e.message));
  guest.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  await login(guest, f.guest.no);
  await guest.locator('nav').getByRole('button', { name: '消息中心', exact: true }).click();
  await expect(guest.getByText('待办 2 ·', { exact: false })).toBeVisible();
  const taskRow = guest.getByRole('row').filter({ hasText: title });
  await expect(taskRow).toContainText('任务');
  const approval = guest.getByRole('row').filter({ hasText: '审批单' });
  await expect(approval).toContainText('外出');
  await approval.getByRole('button', { name: '查看单据', exact: true }).click();
  await expect(guest.getByRole('dialog')).toContainText('并存审批' + f.stamp);
  await guest.getByRole('button', { name: '关闭申请详情' }).click();
  await guest.screenshot({ path: '/tmp/oa-tasks-pending-mobile.png', fullPage: true });
  await taskRow.getByRole('button', { name: '查看任务', exact: true }).click();
  const gd = guest.getByRole('dialog');
  await expect(gd).toContainText('已修改的任务说明');
  await expect(gd.getByRole('button', { name: '修改任务', exact: true })).toHaveCount(0);
  await gd.getByLabel('跟进说明（完成时建议填写）').fill('已开始办理');
  await gd.getByRole('button', { name: '追加跟进', exact: true }).click();
  await expect(gd).toContainText('已开始办理');
  await gd.getByRole('button', { name: '开始处理', exact: true }).click();
  await expect(gd).toContainText('进行中');
  await gd.getByLabel('跟进说明（完成时建议填写）').fill('处理完毕');
  await gd.getByRole('button', { name: '标记已完成', exact: true }).click();
  await expect(gd).toContainText('状态：已完成');
  await expect(gd).toContainText('处理完毕');
  await guest.screenshot({ path: '/tmp/oa-tasks-completed-mobile.png', fullPage: true });
  await gd.getByRole('button', { name: '关闭任务', exact: true }).click();
  await guest.locator('nav').getByRole('button', { name: '消息中心', exact: true }).click();
  await expect(guest.getByText('待办 1 ·', { exact: false })).toBeVisible();
  await expect(guest.getByRole('row').filter({ hasText: title })).toHaveCount(0);
  await expect(guest.getByRole('row').filter({ hasText: '审批单' })).toHaveCount(1);
  await guest.getByRole('tab', { name: '系统通知（站内通知）', exact: true }).click();
  await guest.getByRole('row').filter({ hasText: '任务指派：' + title }).getByRole('button', { name: '查看任务', exact: true }).click();
  await expect(gd).toContainText('状态：已完成');
  await f.call('/requests/' + rid + '/cancel', 'POST', undefined, f.owner.token);
  await ctx.close();
  expect(errors).toEqual([]);
});

test('管理员本人创建、筛选全部任务、代跟进、完成重开、取消恢复', async ({ page, request }) => {
  const f = await fixture(request);
  await login(page, 'admin', true);
  await page.locator('nav').getByRole('button', { name: '待办任务' }).click();
  await page.getByRole('button', { name: '创建任务', exact: true }).click();
  const d = page.getByRole('dialog');
  const title = '管理员任务' + f.stamp;
  await d.getByLabel('任务标题').fill(title);
  await d.getByLabel('截止日期时间（北京时间，可选）').fill('2032-03-01T18:00');
  await d.getByRole('checkbox', { name: f.guest.no + ' · ' + f.guest.no, exact: true }).check();
  await d.getByRole('button', { name: '保存任务', exact: true }).click();
  await expect(d).toContainText('发起人：系统管理员');
  await d.getByLabel('跟进说明（完成时建议填写）').fill('管理员跟进');
  await d.getByRole('button', { name: '追加跟进', exact: true }).click();
  await expect(d).toContainText('管理员跟进');
  await d.getByRole('button', { name: '标记已完成', exact: true }).click();
  await expect(d).toContainText('状态：已完成');
  await d.getByRole('button', { name: '重开为进行中', exact: true }).click();
  await expect(d).toContainText('状态：进行中');
  await d.getByRole('button', { name: '取消任务', exact: true }).click();
  await expect(d).toContainText('状态：已取消');
  await expect(d.getByRole('button', { name: '追加跟进' })).toHaveCount(0);
  await d.getByRole('button', { name: '恢复为待处理', exact: true }).click();
  await expect(d).toContainText('状态：待处理');
  await page.screenshot({ path: '/tmp/oa-tasks-admin.png', fullPage: false });
  await d.getByRole('button', { name: '关闭任务', exact: true }).click();
  await page.getByLabel('任务范围').selectOption('');
  await page.getByLabel('任务状态').selectOption('TODO');
  await page.getByLabel('截止起日').fill('2032-03-01');
  await page.getByLabel('截止止日').fill('2032-03-01');
  await page.getByLabel('发起人筛选', { exact: true }).selectOption(String(f.admin.user.id));
  await page.getByLabel('负责人筛选', { exact: true }).selectOption(String(f.guest.id));
  await page.getByRole('button', { name: '查询任务', exact: true }).click();
  await page.getByRole('row').filter({ hasText: title }).getByRole('button', { name: '查看任务', exact: true }).click();
  await d.getByRole('button', { name: '取消任务', exact: true }).click();
  await expect(d).toContainText('状态：已取消');
});
