import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests',
  workers: 1,
  retries: 0,
  timeout: 60000,
  use: {
    baseURL: process.env.OA_WEB || 'http://127.0.0.1:5173',
    headless: true,
    ignoreHTTPSErrors: process.env.OA_LOCAL_TLS === 'true',
    viewport: { width: 1440, height: 1000 },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  reporter: [['list']],
});
