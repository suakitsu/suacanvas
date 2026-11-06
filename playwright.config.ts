import { defineConfig, devices } from '@playwright/test'

const userBaseURL = process.env.BASE_URL || 'http://localhost:13000'
const adminBaseURL = process.env.ADMIN_BASE_URL || 'http://localhost:13001'

export default defineConfig({
  testDir: './tests/e2e',
  timeout: 30_000,
  expect: {
    timeout: 10_000
  },
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never', outputFolder: 'output/playwright/report' }]] : 'list',
  outputDir: 'output/playwright/test-results',
  webServer: [
    {
      command: 'npm run start -- -p 13000',
      cwd: './openImage-ts',
      url: userBaseURL,
      reuseExistingServer: !process.env.CI,
      timeout: 120_000
    },
    {
      command: 'npm run start -- -p 13001',
      cwd: './openImage-admin-ts',
      url: adminBaseURL,
      reuseExistingServer: !process.env.CI,
      timeout: 120_000
    }
  ],
  use: {
    baseURL: userBaseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure'
  },
  metadata: {
    userBaseURL,
    adminBaseURL
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] }
    }
  ]
})
