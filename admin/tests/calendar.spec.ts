import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
test.use({ locale: 'zh-CN', timezoneId: 'America/Los_Angeles' });
async function fixture(request: APIRequestContext) {
  const call = async (path: string, method = 'GET', data?: any, token?: string, status = 200) => {
    const r = await request.fetch('/api' + path, {
      method,
      data,
      headers: token ? { Authorization: 'Bearer ' + token } : {},
    });
    expect(r.status(), await r.text()).toBe(status);
    return r.json();
  };
  const admin = await call('/auth/login', 'POST', {
    login: 'admin',
    password: process.env.OA_ADMIN_PASSWORD || 'Admin@2026Verified',
  });
  const stamp = Date.now();
  const dept = await call(
    '/admin/departments',
    'POST',
    { name: '日程浏览器' + stamp },
    admin.token,
  );
  const make = async (suffix: string) => {
    const no = 'calweb' + stamp + suffix;
    const u = await call(
      '/admin/users',
      'POST',
      {
        employeeNo: no,
        name: no,
        roles: ['EMPLOYEE'],
        departmentId: dept.id,
        enabled: true,
        password: 'Calendar@2026Initial',
      },
      admin.token,
    );
    const first = await call('/auth/login', 'POST', {
      login: no,
      password: 'Calendar@2026Initial',
    });
    await call(
      '/auth/password',
      'POST',
      { oldPassword: 'Calendar@2026Initial', newPassword: 'Calendar@2026Changed' },
      first.token,
    );
    const login = await call('/auth/login', 'POST', {
      login: no,
      password: 'Calendar@2026Changed',
    });
    return { ...u, no, ...login };
  };
  const owner = await make('o'),
    guest = await make('g');
  const room = await call(
    '/meeting-rooms',
    'POST',
    { name: '日程验收室' + stamp, location: '三楼', capacity: 5, enabled: true },
    admin.token,
  );
  return { call, admin, owner, guest, room, stamp };
}
async function login(page: Page, no: string, password = 'Calendar@2026Changed') {
  await page.goto('/staff/');
  await page.getByLabel('工号 / 手机号').fill(no);
  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByRole('button', { name: '登录员工办公' }).click();
  await expect(page.locator('nav')).toBeVisible();
}
test('员工网页完整预定、冲突、通知回复、修改重置、取消后重订，手机宽度可用', async ({
  page,
  request,
  browser,
}) => {
  const f = await fixture(request);
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await login(page, f.owner.no);
  await page.locator('nav').getByRole('button', { name: '日程与会议' }).click();
  await page.getByLabel('起始日期', { exact: true }).fill('2031-02-03');
  await page.getByLabel('截止日期', { exact: true }).fill('2031-02-03');
  await page.getByRole('button', { name: '查询会议', exact: true }).click();
  const title = '网页会议' + f.stamp;
  const dialog = page.getByRole('dialog');
  async function create(name: string) {
    await page.getByRole('button', { name: '创建会议 / 预定会议室', exact: true }).click();
    await dialog.getByLabel('会议标题').fill(name);
    await dialog.getByLabel('开始时间（北京时间）').fill('2031-02-03T09:00');
    await dialog.getByLabel('结束时间（北京时间）').fill('2031-02-03T10:00');
    await dialog.getByLabel('预定会议室').selectOption(String(f.room.id));
    await dialog.getByLabel('说明', { exact: true }).fill('会议说明仅参会人可见');
    await dialog.getByLabel(/邀请同事/).selectOption(String(f.guest.id));
    await expect(dialog.getByRole('button', { name: '保存会议', exact: true })).toBeEnabled();
    await dialog.getByRole('button', { name: '保存会议', exact: true }).click();
  }
  await create(title);
  await expect(dialog).toContainText('待回复');
  await page.getByLabel('关闭会议弹窗').click();
  await create(title + '冲突');
  await expect(dialog.getByRole('alert')).toContainText('会议室时段冲突：' + title);
  await expect(dialog).toContainText('所选会议室占用时段');
  await page.getByLabel('关闭会议弹窗').click();
  const guestContext = await browser.newContext({
    viewport: { width: 390, height: 844 },
    timezoneId: 'America/New_York',
  });
  const guest = await guestContext.newPage();
  await login(guest, f.guest.no);
  await guest.locator('nav').getByRole('button', { name: '消息中心' }).click();
  await guest.getByRole('tab', { name: '系统通知（站内通知）' }).click();
  await guest
    .getByRole('row')
    .filter({ hasText: '会议邀请：' + title })
    .getByRole('button', { name: '查看会议', exact: true })
    .click();
  await expect(guest.getByRole('dialog')).toContainText('会议说明仅参会人可见');
  await guest.getByRole('button', { name: '接受邀请', exact: true }).click();
  await expect(guest.getByRole('dialog')).toContainText('接受');
  await page
    .getByRole('row')
    .filter({ hasText: title })
    .getByRole('button', { name: '查看会议', exact: true })
    .click();
  await expect(dialog).toContainText('接受');
  await dialog.getByRole('button', { name: '修改会议', exact: true }).click();
  await dialog.getByLabel('开始时间（北京时间）').fill('2031-02-03T10:00');
  await dialog.getByLabel('结束时间（北京时间）').fill('2031-02-03T11:00');
  await expect(dialog.getByRole('button', { name: '保存会议', exact: true })).toBeEnabled();
  await dialog.getByRole('button', { name: '保存会议', exact: true }).click();
  await expect(dialog).toContainText('待回复');
  await dialog.getByRole('button', { name: '取消会议', exact: true }).click();
  await expect(dialog).toContainText('已取消');
  await page.getByLabel('关闭会议弹窗').click();
  // Rebook exactly the cancelled interval.
  await page.getByRole('button', { name: '创建会议 / 预定会议室', exact: true }).click();
  await dialog.getByLabel('会议标题').fill(title + '重订');
  await dialog.getByLabel('开始时间（北京时间）').fill('2031-02-03T10:00');
  await dialog.getByLabel('结束时间（北京时间）').fill('2031-02-03T11:00');
  await dialog.getByLabel('预定会议室').selectOption(String(f.room.id));
  await expect(dialog.getByRole('button', { name: '保存会议', exact: true })).toBeEnabled();
  await dialog.getByRole('button', { name: '保存会议', exact: true }).click();
  await expect(dialog).toContainText('无其他参会人');
  await dialog.getByRole('button', { name: '取消会议', exact: true }).click();
  await guestContext.close();
  expect(errors).toEqual([]);
  await f.call('/meeting-rooms/' + f.room.id, 'DELETE', undefined, f.admin.token);
});
test('中文管理员维护会议室、创建会议、筛选和取消', async ({ page, request }) => {
  const f = await fixture(request);
  await page.goto('/');
  await page.getByLabel('工号 / 手机号').fill('admin');
  await page
    .getByLabel('密码', { exact: true })
    .fill(process.env.OA_ADMIN_PASSWORD || 'Admin@2026Verified');
  await page.getByRole('button', { name: '登录管理后台' }).click();
  await page.locator('nav').getByRole('button', { name: '日程与会议' }).click();
  await page.getByRole('button', { name: '新增会议室', exact: true }).click();
  const d = page.getByRole('dialog');
  const name = '后台会议室' + f.stamp;
  await d.getByLabel('会议室名称').fill(name);
  await d.getByLabel('容纳人数').fill('8');
  await d.getByRole('button', { name: '保存会议室', exact: true }).click();
  await expect(d).not.toBeVisible();
  const roomRow = page.getByRole('row').filter({ hasText: name });
  await roomRow.getByRole('button', { name: '修改会议室', exact: true }).click();
  await d.getByLabel('地点', { exact: true }).fill('二楼');
  await d.getByRole('button', { name: '保存会议室', exact: true }).click();
  await expect(roomRow).toContainText('二楼');
  await page.getByRole('button', { name: '创建会议 / 预定会议室', exact: true }).click();
  await d.getByLabel('会议标题').fill('管理员会议' + f.stamp);
  await d.getByRole('button', { name: '保存会议', exact: true }).click();
  await expect(d).toContainText('组织者：系统管理员');
  await d.getByRole('button', { name: '取消会议', exact: true }).click();
  await expect(d).toContainText('已取消');
  await page.getByLabel('关闭会议弹窗').click();
  await page.getByLabel('会议室筛选').selectOption(String(f.room.id));
  await page.getByLabel('组织者筛选').selectOption(String(f.owner.id));
  await page.getByRole('button', { name: '查询会议', exact: true }).click();
  await expect(page.getByText('第 1 页 · 每页最多200条')).toBeVisible();
  await roomRow.getByRole('button', { name: '删除 / 停用', exact: true }).click();
  await expect(roomRow).not.toBeVisible();
  await f.call('/meeting-rooms/' + f.room.id, 'DELETE', undefined, f.admin.token);
});
