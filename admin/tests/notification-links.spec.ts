import {test, expect} from '@playwright/test';

test('系统通知按 request_id 打开单据，旧通知不显示查看单据', async ({page}) => {
  await page.addInitScript(() => sessionStorage.setItem('oa-staff-token', 'fake-session'));
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/auth/me') return route.fulfill({json:{user:{id:1,name:'测试员工',roles:'EMPLOYEE',mustChange:false,version:0}}});
    if (path === '/api/messages/summary' || path === '/api/attendance-policy') return route.fulfill({json:{}});
    if (path === '/api/messages') return route.fulfill({json:[
      {id:7,message:'新审批通知',request_id:42}, {id:8,message:'历史无关联通知'}
    ]});
    if (path === '/api/requests/42') return route.fulfill({json:{id:42,user_id:1,kind:'TRAVEL',status:'WITHDRAWN',reason:'关联单据内容',steps:[],cc:[],attachments:[],reassignments:[]}});
    return route.fulfill({json:[]});
  });
  await page.goto('/staff/');
  await page.getByRole('navigation',{name:'员工功能'}).getByRole('button',{name:'消息中心',exact:true}).click();
  await page.getByRole('tab',{name:'系统通知（站内通知）',exact:true}).click();
  const newRow=page.getByRole('row').filter({hasText:'新审批通知'});
  const oldRow=page.getByRole('row').filter({hasText:'历史无关联通知'});
  await expect(newRow.getByRole('button',{name:'查看单据'})).toBeVisible();
  await expect(oldRow.getByRole('button',{name:'查看单据'})).toHaveCount(0);
  await newRow.getByRole('button',{name:'查看单据'}).click();
  await expect(page.getByRole('dialog',{name:'申请详情'})).toContainText('关联单据内容');
});
