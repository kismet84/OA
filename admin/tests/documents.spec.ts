import { test, expect, type Page } from '@playwright/test';

test.use({ locale: 'zh-CN', timezoneId: 'America/New_York' });
const png = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jWTkAAAAASUVORK5CYII=',
  'base64',
);
async function login(page: Page, no: string, admin = false) {
  await page.goto(admin ? '/' : '/staff/');
  await page.getByLabel('工号 / 手机号').fill(no);
  await page
    .getByLabel('密码', { exact: true })
    .fill(admin ? process.env.OA_ADMIN_PASSWORD || 'Admin@2026Verified' : 'Docs@2026Changed');
  await page
    .getByRole('button', { name: admin ? '登录管理后台' : '登录员工办公', exact: true })
    .click();
  await expect(page.locator('nav')).toBeVisible();
  await page.locator('nav').getByRole('button', { name: '文档资料' }).click();
}
test('管理员发布部门文档、员工水印预览、范围隔离、禁下载、下架及恢复通知回执', async ({
  page,
  request,
  browser,
}) => {
  test.setTimeout(120000);
  page.setDefaultTimeout(15000);
  const call = async (path: string, method = 'GET', data?: any, token?: string, status = 200) => {
    const r = await request.fetch('/api' + path, {
      method,
      data,
      headers: token ? { Authorization: 'Bearer ' + token } : {},
    });
    expect(r.status(), await r.text()).toBe(status);
    return r.json();
  };
  const at = (
    await call('/auth/login', 'POST', {
      login: 'admin',
      password: process.env.OA_ADMIN_PASSWORD || 'Admin@2026Verified',
    })
  ).token;
  const stamp = Date.now();
  const department = await call('/admin/departments', 'POST', { name: '文档部门' + stamp }, at);
  const child = await call(
    '/admin/departments',
    'POST',
    { name: '文档下级' + stamp, parentId: department.id },
    at,
  );
  const other = await call('/admin/departments', 'POST', { name: '文档其他' + stamp }, at);
  const make = async (s: string, dept: number) => {
    const no = 'docsweb' + stamp + s;
    const u = await call(
      '/admin/users',
      'POST',
      {
        employeeNo: no,
        name: '文档员工' + s,
        departmentId: dept,
        enabled: true,
        roles: ['EMPLOYEE'],
        password: 'Docs@2026Initial',
      },
      at,
    );
    const first = await call('/auth/login', 'POST', { login: no, password: 'Docs@2026Initial' });
    await call(
      '/auth/password',
      'POST',
      { oldPassword: 'Docs@2026Initial', newPassword: 'Docs@2026Changed' },
      first.token,
    );
    return {
      ...u,
      no,
      ...(await call('/auth/login', 'POST', { login: no, password: 'Docs@2026Changed' })),
    };
  };
  const employee = await make('a', child.id),
    outsider = await make('b', other.id);
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await login(page, 'admin', true);
  await page.getByRole('button', { name: '发布文档', exact: true }).click();
  const d = page.getByRole('dialog');
  const title = '浏览器制度' + stamp;
  await d.getByLabel('文档标题', { exact: true }).fill(title);
  await d.getByLabel('文档说明', { exact: true }).fill('仅本部门和下级可查看的制度');
  await d.getByLabel('文档分类', { exact: true }).selectOption('POLICY');
  await d.getByLabel('接收范围', { exact: true }).selectOption(String(department.id));
  await d
    .locator('input[type=file]')
    .setInputFiles({ name: '制度.png', mimeType: 'image/png', buffer: png });
  await expect(d.getByText('制度.png', { exact: false })).toBeVisible();
  const published = page.waitForResponse(
    (r) => r.url().endsWith('/api/documents') && r.request().method() === 'POST',
  );
  await d.getByRole('button', { name: '确认发布文档', exact: true }).click();
  const response = await published;
  expect(response.ok(), await response.text()).toBeTruthy();
  const id = (await response.json()).id;
  await expect(d).toContainText('未读 1 人');
  await d.getByRole('button', { name: '水印预览', exact: true }).click();
  await expect(d.getByAltText('带查看人水印的文档预览')).toBeVisible();
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, locale: 'zh-CN' });
  const staff = await ctx.newPage();
  staff.on('pageerror', (e) => errors.push(e.message));
  await login(staff, employee.no);
  await staff.getByLabel('标题关键字').fill(title);
  await staff.getByRole('button', { name: '筛选文档' }).click();
  await staff
    .getByRole('row')
    .filter({ hasText: title })
    .getByRole('button', { name: '查看文档' })
    .click();
  const sd = staff.getByRole('dialog');
  await expect(sd).toContainText('仅本部门和下级可查看的制度');
  await sd.getByRole('button', { name: '水印预览' }).click();
  await expect(sd).toContainText('文档员工a · ' + employee.no);
  await expect(sd.getByAltText('带查看人水印的文档预览')).toBeVisible();
  expect(
    await sd.locator('img').evaluate((img: HTMLImageElement) => img.naturalWidth),
  ).toBeGreaterThan(0);
  await staff.screenshot({ path: '../.runtime/docs-staff-browser.png', fullPage: true });
  const downloaded = staff.waitForEvent('download');
  await sd.getByRole('button', { name: '下载水印件' }).click();
  expect((await downloaded).suggestedFilename()).toContain('水印');
  const outsideCtx = await browser.newContext();
  const outsidePage = await outsideCtx.newPage();
  await login(outsidePage, outsider.no);
  await outsidePage.getByLabel('标题关键字').fill(title);
  await outsidePage.getByRole('button', { name: '筛选文档' }).click();
  await expect(outsidePage.getByRole('row').filter({ hasText: title })).toHaveCount(0);
  await call('/documents/' + id, 'GET', undefined, outsider.token, 403);
  // Close download via the actual management editor.
  await d.getByRole('button', { name: '修改文档' }).click();
  await d.getByLabel('允许下载水印件').uncheck();
  await d.getByRole('button', { name: '保存文档', exact: true }).click();
  await expect(d).toContainText('已读 1 人');
  await sd.getByRole('button', { name: '关闭文档' }).click();
  await staff.getByRole('button', { name: '筛选文档' }).click();
  await staff
    .getByRole('row')
    .filter({ hasText: title })
    .getByRole('button', { name: '查看文档' })
    .click();
  await expect(sd.getByRole('button', { name: '下载水印件' })).toHaveCount(0);
  await expect(sd).toContainText('禁止下载');
  const file = (await call('/documents/' + id, 'GET', undefined, employee.token)).attachments[0].id;
  await call(
    `/documents/${id}/attachments/${file}/download`,
    'GET',
    undefined,
    employee.token,
    403,
  );
  await call('/attachments/' + file, 'GET', undefined, employee.token, 403);
  await sd.getByRole('button', { name: '水印预览' }).click();
  await expect(sd.locator('img')).toBeVisible();
  await d.getByRole('button', { name: '下架文档', exact: true }).click();
  await expect(d).toContainText('已下架');
  await sd.getByRole('button', { name: '关闭文档' }).click();
  await staff.getByRole('button', { name: '筛选文档' }).click();
  await expect(staff.getByRole('row').filter({ hasText: title })).toHaveCount(0);
  await staff.locator('nav').getByRole('button', { name: '消息中心', exact: true }).click();
  await staff.getByRole('tab', { name: '系统通知（站内通知）' }).click();
  await staff
    .getByRole('row')
    .filter({ hasText: title })
    .getByRole('button', { name: '查看文档' })
    .click();
  await expect(staff.getByRole('alert')).toContainText('文档已下架或您不在接收范围内');
  await d.getByRole('button', { name: '重新上架', exact: true }).click();
  await expect(d).toContainText('已发布');
  await d.getByRole('button', { name: '关闭文档' }).click();
  await page.getByLabel('标题关键字').fill(title);
  await page.getByRole('button', { name: '筛选文档' }).click();
  await page
    .getByRole('row')
    .filter({ hasText: title })
    .getByRole('button', { name: '查看文档' })
    .click();
  await expect(d).toContainText('文档员工a');
  await page.screenshot({ path: '../.runtime/docs-admin-browser.png', fullPage: true });
  await call('/documents/' + id + '/withdraw', 'POST', undefined, at);
  await ctx.close();
  await outsideCtx.close();
  expect(errors).toEqual([]);
});
