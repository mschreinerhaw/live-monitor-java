const { test, expect } = require('@playwright/test')

test('overview isolates missing metrics, filters clusters by IDs, and links resources', async ({ page }) => {
  const errors = []
  page.on('pageerror', e => errors.push(e.message))
  let failed = false
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    let json = []
    if (path === '/api/auth/me') json = { authenticated: true, user_id: 'admin', admin: true }
    if (path === '/api/overview/summary') {
      if (failed) return route.fulfill({ status: 503, json: {} })
      json = { summary: { instances: 2, up: 1, down: 1, unknown: 0, today_alerts: 3 }, errors: {}, services: [
        { id: 1, service_name: 'Redis A', cluster_name: 'A', host_id: 11, enabled: true, last_status: 'DOWN' },
        { id: 2, service_name: 'Redis B', cluster_name: 'B', host_id: 12, enabled: true, last_status: 'UP' }
      ], hosts: [
        { id: 11, host_name: 'host-a', enabled: true, last_status: 'UP', last_checked_at_epoch_ms: Date.now(), metric_checked_at: 'recent', cpu_usage_percent: null },
        { id: 12, host_name: 'host-b', enabled: true, last_status: 'UP', last_checked_at_epoch_ms: 1, metric_checked_at: 'old', cpu_usage_percent: 0 }
      ] }
    }
    await route.fulfill({ json })
  })
  await page.goto('/#/dashboard')
  await expect(page.getByRole('heading', { name: '全局监控', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: '最近告警事件', exact: true })).toHaveCount(0)
  await expect(page.locator('aside').getByRole('link', { name: '集群健康与热力图', exact: true })).toHaveCount(0)
  await expect(page.locator('.heat-cell').filter({ hasText: 'host-a' })).toContainText('暂无数据')
  await expect(page.locator('.heat-cell').filter({ hasText: 'host-b' })).toContainText('采集过期')
  await page.getByLabel('集群范围').selectOption('configured:A')
  await expect(page.locator('.heat-cell')).toHaveCount(1)
  await expect(page.locator('.heat-cell')).toHaveAttribute('href', '#/hosts/11')
  await expect(page.locator('.exception-row').first()).toHaveAttribute('href', '#/services/1')
  await page.goto('/#/clusters?q=Redis')
  await expect(page).toHaveURL(/#\/dashboard\?q=Redis/)
  await expect(page.getByLabel('搜索资源')).toHaveValue('Redis')
  failed = true
  await page.getByRole('button', { name: '刷新快照' }).click()
  await expect(page.getByRole('alert')).toContainText('503')
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  expect(errors).toEqual([])
})
