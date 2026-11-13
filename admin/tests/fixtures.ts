import { test as base } from '@playwright/test';
import { execFileSync } from 'node:child_process';
export * from '@playwright/test';
/** Independent local suites must not exhaust the shared dev IP's 15-minute login quota.
 * Production throttle and all within-test authentication checks remain unchanged. */
export const test = base.extend<{localLoginQuota: void}>({
  localLoginQuota: [async ({baseURL},use)=>{
    const host=new URL(baseURL||'http://127.0.0.1:5173').hostname;
    if(['127.0.0.1','localhost'].includes(host)) {
      execFileSync('redis-cli',['-h','127.0.0.1','-p','56379','DEL','login-ip:127.0.0.1'],{stdio:'ignore'});
    }
    await use();
  },{auto:true}],
});
