import { test, expect } from './fixtures';

for (const [code, message] of [
  [1, '定位权限被拒绝，请在浏览器设置中允许定位后重试'],
  [2, '当前位置不可用，请检查定位设置后重试'],
  [3, '定位超时，请到信号较好的地方重试'],
] as const) {
  test(`员工网页定位失败 code ${code}：中文提示且不确认、不提交`, async ({ page, request }) => {
    const login = await request.post('/api/auth/login', {
      data: { login: 'admin', password: process.env.OA_ADMIN_PASSWORD || 'Admin@2026Verified' },
    });
    expect(login.ok()).toBeTruthy();
    const { token } = await login.json();
    await page.addInitScript(({ token, code }) => {
      sessionStorage.setItem('oa-staff-token', token);
      Object.defineProperty(navigator.geolocation, 'getCurrentPosition', {
        value: (_success: PositionCallback, failure: PositionErrorCallback, options: PositionOptions) => {
          (window as any).punchLocationOptions = options;
          queueMicrotask(() => failure({ code, message: 'Raw English geolocation error' } as GeolocationPositionError));
        },
      });
    }, { token, code });
    let punches = 0;
    let confirmations = 0;
    page.on('request', req => {
      if (new URL(req.url()).pathname === '/api/punches' && req.method() === 'POST') punches++;
    });
    page.on('dialog', async dialog => { confirmations++; await dialog.dismiss(); });
    await page.goto('/staff/');
    await page.getByRole('navigation').getByRole('button', { name: '打卡记录', exact: true }).click();
    const punch = page.getByRole('button', { name: '网页正常打卡', exact: true });
    for (let attempt = 0; attempt < 2; attempt++) {
      await punch.click();
      await expect(page.getByRole('status').filter({ hasText: message })).toHaveText(message);
      await expect(punch).toBeEnabled();
    }
    expect(await page.evaluate(() => (window as any).punchLocationOptions)).toEqual({
      enableHighAccuracy: true, maximumAge: 0, timeout: 45000,
    });
    expect(confirmations).toBe(0);
    expect(punches).toBe(0);
  });
}
