import { expect, test, type Page } from '@playwright/test'

const baseURL = process.env.BASE_URL || 'http://localhost:13000'
const adminBaseURL = process.env.ADMIN_BASE_URL || 'http://localhost:13001'

async function openWithStableLocale(page: Page, url: string) {
  await page.goto(url)
  await page.evaluate(() => {
    window.localStorage.setItem('openimage-language', 'zh-CN')
  })
  await page.reload()
}

async function expectLoadedDocument(page: Page) {
  await expect(page.locator('body')).toBeVisible()
  await expect(page).toHaveTitle(/picpilot/i)
}

test.describe('core public smoke', () => {
  test('public landing page exposes brand, navigation, and primary actions', async ({ page }) => {
    await openWithStableLocale(page, baseURL)
    await expectLoadedDocument(page)

    await expect(page.getByRole('link', { name: 'PicPilot' })).toBeVisible()
    await expect(page.getByRole('navigation', { name: 'Landing navigation' })).toBeVisible()
    await expect(page.getByRole('heading', { level: 1 })).toContainText('让商品图')
    await expect(page.getByRole('link', { name: '登录' })).toBeVisible()
    await expect(page.getByRole('link', { name: '注册' })).toBeVisible()
  })

  test('user login page renders the credential form without requiring an account', async ({ page }) => {
    await openWithStableLocale(page, new URL('/login', baseURL).toString())
    await expectLoadedDocument(page)

    await expect(page.getByRole('heading', { name: '用户登录' })).toBeVisible()
    await expect(page.getByLabel('邮箱')).toBeVisible()
    await expect(page.getByLabel('密码')).toBeVisible()
    await expect(page.getByRole('checkbox', { name: /记住登录/ })).toBeVisible()
    await expect(page.getByRole('button', { name: '登录' })).toBeDisabled()
    await expect(page.getByRole('link', { name: '立即注册' })).toBeVisible()
  })
})

test.describe('core admin smoke', () => {
  test('admin login page renders the isolated admin entry point', async ({ page }) => {
    await openWithStableLocale(page, new URL('/login', adminBaseURL).toString())
    await expectLoadedDocument(page)

    await expect(page.getByRole('heading', { name: '管理员登录' })).toBeVisible()
    await expect(page.getByLabel('管理员账号')).toBeVisible()
    await expect(page.getByLabel('密码')).toBeVisible()
    await expect(page.getByRole('button', { name: '登录管理后台' })).toBeDisabled()
    await expect(page.getByText('管理员账号由系统预置或数据库维护。')).toBeVisible()
  })
})
