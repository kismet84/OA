import { test, expect } from './fixtures';
import type { APIRequestContext } from '@playwright/test';

async function loginToken(request: APIRequestContext): Promise<string> {
  const login = await request.post('/api/auth/login', {
    data: { login: 'admin', password: process.env.OA_ADMIN_PASSWORD },
  });
  expect(login.ok()).toBeTruthy();
  const { token } = await login.json();
  return token;
}


for (const [code, message] of [
  [1, '定位权限被拒绝，请在浏览器设置中允许定位后重试'],
  [2, '当前位置不可用，请检查定位设置后重试'],
  [3, '定位超时，请到信号较好的地方重试'],
] as const) {
  test(`员工网页定位失败 code ${code}：中文提示且不确认、不提交`, async ({ page, request }) => {
    const token = await loginToken(request);
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

for (const [name, accuracy, age, message] of [
  ['精度201米', 201, 0, '定位精度须大于0且不超过200米'],
  ['过期定位', 200, 301000, '定位采集时间须在5分钟内'],
  ['未来超过2分钟', 200, -121000, '定位采集时间须在5分钟内'],
  ['合格200米定位', 200, 60000, '打卡成功'],
] as const) {
  test(`员工网页${name}：提交前校验来源、精度与时效`, async ({ page, request }) => {
    const token = await loginToken(request);
    await page.addInitScript(({ token, accuracy, age }) => {
      sessionStorage.setItem('oa-staff-token', token);
      Object.defineProperty(navigator.geolocation, 'getCurrentPosition', {
        value: (success: PositionCallback) => queueMicrotask(() => success({
          coords: { latitude: 31.23, longitude: 121.47, accuracy }, timestamp: Date.now() - age,
        } as GeolocationPosition)),
      });
    }, { token, accuracy, age });
    const payloads: any[] = [];
    await page.route('**/api/punches', async route => {
      if (route.request().method() === 'POST') {
        payloads.push(route.request().postDataJSON());
        await route.fulfill({ json: { id: 1 } });
      } else await route.continue();
    });
    let confirmations = 0;
    page.on('dialog', async dialog => { confirmations++; await dialog.accept(); });
    await page.goto('/staff/');
    await page.getByRole('navigation').getByRole('button', { name: '打卡记录', exact: true }).click();
    await page.getByRole('button', { name: '网页正常打卡', exact: true }).click();
    await expect(page.getByRole('status').filter({ hasText: message })).toContainText(message);
    if (name === '合格200米定位') {
      expect(confirmations).toBe(1);
      expect(payloads).toHaveLength(1);
      expect(payloads[0]).toMatchObject({ accuracy: 200, locationSource: 'LIVE' });
      expect(Date.now() - Date.parse(payloads[0].locatedAt)).toBeLessThan(300000);
      expect(payloads[0].locatedAt).toMatch(/Z$/);
    } else {
      expect(confirmations).toBe(0);
      expect(payloads).toHaveLength(0);
    }
  });
}
