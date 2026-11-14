import {test, expect} from '@playwright/test';

test('员工提交在响应丢失后复用幂等键，修改内容和成功后换新键', async ({page}) => {
  const keys: string[] = [];
  let accepted = false;
  await page.addInitScript(() => sessionStorage.setItem('oa-staff-token', crypto.randomUUID()));
  await page.route('**/api/**', async route => {
    const req = route.request(), path = new URL(req.url()).pathname;
    if (path === '/api/requests' && req.method() === 'POST') {
      keys.push(req.headers()['idempotency-key']!);
      if (!accepted) return route.abort('connectionfailed');
      return route.fulfill({json: {id: 123}});
    }
    if (path === '/api/auth/me') return route.fulfill({json:{user:{id:1,name:'测试员工',roles:'EMPLOYEE',mustChange:false,version:0}}});
    if (path === '/api/attendance-policy' || path === '/api/messages/summary') return route.fulfill({json:{}});
    if (path === '/api/requests/123') return route.fulfill({json:{id:123,user_id:1,kind:'TRAVEL',status:'PENDING',steps:[],cc:[],reassignments:[],attachments:[]}});
    return route.fulfill({json:[]});
  });
  await page.goto('/staff/');
  await page.getByLabel('申请类型',{exact:true}).selectOption('TRAVEL');
  await page.getByLabel('开始日期',{exact:true}).fill('2035-01-01');
  await page.getByLabel('结束日期',{exact:true}).fill('2035-01-01');
  await page.getByLabel('申请理由').fill('测试提交');
  const submit=page.getByRole('button',{name:'提交申请',exact:true}).last();
  for (let i=0;i<2;i++) {
    await submit.click();
    await expect(page.getByRole('alert')).toContainText('网络连接失败');
    await expect(submit).toBeEnabled();
  }
  expect(keys[0]).toMatch(/^[0-9a-f-]{36}$/);
  expect(keys[1]).toBe(keys[0]);
  await page.getByLabel('申请理由').fill('修改后的提交');
  accepted=true;await submit.click();
  await expect(page.getByRole('dialog',{name:'申请详情'})).toBeVisible();
  expect(keys[2]).not.toBe(keys[1]);
  await page.getByLabel('关闭申请详情').click();
  await page.getByRole('navigation',{name:'员工功能'}).getByRole('button',{name:'提交申请',exact:true}).click();
  await page.getByLabel('申请类型',{exact:true}).selectOption('TRAVEL');
  await page.getByLabel('开始日期',{exact:true}).fill('2035-01-02');
  await page.getByLabel('结束日期',{exact:true}).fill('2035-01-02');
  await page.getByLabel('申请理由').fill('下一次提交');
  await page.getByRole('button',{name:'提交申请',exact:true}).last().click();
  await expect(page.getByRole('dialog',{name:'申请详情'})).toBeVisible();
  expect(keys[3]).not.toBe(keys[2]);
});
