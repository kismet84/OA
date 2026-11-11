import { test, expect } from '@playwright/test';

test('管理员通过中文页面维护组织、账号、审批并核对打卡', async ({ page }) => {
  const suffix = Date.now().toString().slice(-9);
  const deptName = '浏览器验收部门' + suffix;
  const userName = '浏览器验收员工' + suffix;
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.goto('/');
  await page.getByLabel('工号 / 手机号').fill('admin');
  await page
    .getByLabel('密码', { exact: true })
    .fill(process.env.OA_ADMIN_PASSWORD || 'Admin@2026Verified');
  await page.getByRole('button', { name: '登录管理后台', exact: true }).click();
  await expect(page.getByRole('heading', { name: '工作台', exact: true })).toBeVisible();
  await page.screenshot({ path: '../.runtime/admin-overview.png', fullPage: true });
  await page.locator('nav').getByRole('button', { name: '部门组织' }).click();
  await page.getByRole('button', { name: '新增', exact: true }).click();
  let dialog = page.getByRole('dialog');
  await dialog.getByLabel('部门名称').fill(deptName);
  await dialog.getByRole('button', { name: '保存', exact: true }).click();
  await expect(dialog).not.toBeVisible();
  await expect(page.getByRole('cell', { name: deptName, exact: true })).toBeVisible();
  await page.locator('nav').getByRole('button', { name: '账号与角色' }).click();
  await page.getByRole('button', { name: '新增', exact: true }).click();
  dialog = page.getByRole('dialog');
  await dialog.getByLabel('工号', { exact: true }).fill('web' + suffix);
  await dialog.getByLabel('姓名', { exact: true }).fill(userName);
  const deptId = await dialog
    .getByLabel('主部门')
    .locator('option')
    .filter({ hasText: deptName })
    .getAttribute('value');
  await dialog.getByLabel('主部门').selectOption(deptId!);
  const managerOption = dialog
    .getByRole('combobox', { name: /^直属上级/ })
    .locator('option')
    .filter({ hasText: '验收manager' })
    .first();
  await dialog
    .getByRole('combobox', { name: /^直属上级/ })
    .selectOption((await managerOption.getAttribute('value'))!);
  await dialog.getByLabel('初始密码').fill('Web@2026Initial');
  await dialog.getByRole('button', { name: '保存', exact: true }).click();
  await expect(dialog).not.toBeVisible();
  await expect(page.getByRole('cell', { name: userName, exact: true })).toBeVisible();
  await page.locator('nav').getByRole('button', { name: '公司网络' }).click();
  await page.getByRole('button', { name: '新增', exact: true }).click();
  dialog = page.getByRole('dialog');
  await dialog.getByLabel('登记值').fill('浏览器验收WiFi' + suffix);
  await dialog.getByRole('button', { name: '保存', exact: true }).click();
  await expect(dialog).not.toBeVisible();
  await page.locator('nav').getByRole('button', { name: '审批单据' }).click();
  await page.getByRole('button', { name: '提交申请', exact: true }).click();
  dialog = page.getByRole('dialog');
  const employeeId = await dialog
    .getByLabel('代提交员工')
    .locator('option')
    .filter({ hasText: userName })
    .getAttribute('value');
  await dialog.getByLabel('代提交员工').selectOption(employeeId!);
  await dialog.getByLabel('申请类型').selectOption('TRAVEL');
  const day = new Date(Date.now() + 86400000 * 3).toISOString().slice(0, 10);
  await dialog.getByLabel('开始日期').fill(day);
  await dialog.getByLabel('结束日期').fill(day);
  await dialog.getByLabel('申请理由').fill('浏览器验收出差' + suffix);
  await page.screenshot({ path: '../.runtime/admin-request-form.png', fullPage: true });
  expect(errors).toEqual([]);
  await expect(dialog.getByRole('button', { name: '保存', exact: true })).toBeVisible();
  await dialog.getByRole('button', { name: '保存', exact: true }).click();
  await expect(dialog).not.toBeVisible();
  const row = page.getByRole('row').filter({ hasText: userName });
  await expect(row).toContainText('待审批');
  await row.getByRole('button', { name: '查看 / 审批' }).click();
  dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('浏览器验收出差' + suffix);
  await dialog.getByLabel('审批意见').fill('管理员浏览器验收通过');
  await dialog.getByRole('button', { name: '提交审批', exact: true }).click();
  await expect(dialog.getByRole('button', { name: '提交审批', exact: true })).not.toBeVisible();
  await expect(dialog).toContainText('已批准');
  await dialog.getByRole('button', { name: '关闭弹窗' }).click();
  await expect(row).toContainText('已批准');
  await page.getByRole('button', { name: '导出 CSV' }).click();
  await page.locator('nav').getByRole('button', { name: '模拟打卡' }).click();
  await page.getByLabel('模拟员工').selectOption(employeeId!);
  await page.getByLabel('公司 Wi-Fi 名称').fill('浏览器验收WiFi' + suffix);
  await page.getByRole('button', { name: '提交模拟打卡', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('打卡成功');
  await page.locator('nav').getByRole('button', { name: '打卡记录' }).click();
  await expect(page.getByRole('row').filter({ hasText: userName })).toContainText('正常打卡');
  for (const name of ['假期配置', '假期余额', '审批分流', '站内通知', '操作审计']) {
    await page.locator('nav').getByRole('button', { name }).click();
    await expect(page.getByRole('heading', { name, exact: true }).first()).toBeVisible();
  }
  await page.screenshot({ path: '../.runtime/admin-audit.png', fullPage: true });
  await page.getByRole('button', { name: '退出', exact: true }).click();
  await expect(page.getByRole('heading', { name: '管理员登录' })).toBeVisible();
  expect(errors).toEqual([]);
});

test('新管理员首次改密后才能进入工作台', async ({ page, request }) => {
  const root = await request.post('/api/auth/login', {
    data: { login: 'admin', password: process.env.OA_ADMIN_PASSWORD || 'Admin@2026Verified' },
  });
  expect(root.ok()).toBeTruthy();
  const auth = await root.json();
  const login = 'uiadmin' + Date.now().toString().slice(-9);
  const headers = { Authorization: 'Bearer ' + auth.token };
  const departments = await (await request.get('/api/admin/departments', { headers })).json();
  const created = await request.post('/api/admin/users', {
    headers,
    data: {
      employeeNo: login,
      name: '首次改密验收管理员',
      roles: ['ADMIN', 'EMPLOYEE'],
      departmentId: departments[0].id,
      enabled: true,
      password: 'UiAdmin@2026Initial',
    },
  });
  expect(created.ok()).toBeTruthy();
  await request.post('/api/auth/logout', { headers });
  await page.goto('/');
  await page.getByLabel('工号 / 手机号').fill(login);
  await page.getByLabel('密码', { exact: true }).fill('UiAdmin@2026Initial');
  await page.getByRole('button', { name: '登录管理后台', exact: true }).click();
  await expect(page.getByRole('heading', { name: '首次登录，请修改密码' })).toBeVisible();
  await expect(page.locator('nav')).not.toBeVisible();
  await page.getByLabel('原密码', { exact: true }).fill('UiAdmin@2026Initial');
  await page.getByLabel('新密码（10–72 位）').fill('UiAdmin@2026Changed');
  await page.getByRole('button', { name: '保存新密码' }).click();
  await expect(page.getByRole('heading', { name: '管理员登录' })).toBeVisible();
  await page.getByLabel('工号 / 手机号').fill(login);
  await page.getByLabel('密码', { exact: true }).fill('UiAdmin@2026Changed');
  await page.getByRole('button', { name: '登录管理后台', exact: true }).click();
  await expect(page.getByRole('heading', { name: '工作台', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '退出', exact: true }).click();
  await expect(page.getByRole('heading', { name: '管理员登录' })).toBeVisible();
});

test('正式环境隐藏模拟打卡并通过 HTTPS 登录', async ({ page }) => {
  test.skip(!process.env.OA_PROD_WEB, '由 verify-production.sh 启动正式环境和 HTTPS 后验证');
  await page.goto(process.env.OA_PROD_WEB!);
  await page.getByLabel('工号 / 手机号').fill('admin');
  await page
    .getByLabel('密码', { exact: true })
    .fill(process.env.OA_ADMIN_PASSWORD || 'Admin@2026Verified');
  await page.getByRole('button', { name: '登录管理后台', exact: true }).click();
  await expect(page.getByRole('heading', { name: '工作台', exact: true })).toBeVisible();
  await expect(page.locator('nav').getByRole('button', { name: '模拟打卡' })).toHaveCount(0);
  await expect(page.locator('aside')).toContainText('正式环境');
  await page.screenshot({ path: '../.runtime/admin-production.png', fullPage: true });
  await page.getByRole('button', { name: '退出', exact: true }).click();
  await expect(page.getByRole('heading', { name: '管理员登录' })).toBeVisible();
});
