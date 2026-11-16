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
  await expect(page.locator('.host-trend-panel')).toHaveCount(0)
  await expect(page.getByRole('heading', { name: '最近告警事件', exact: true })).toHaveCount(0)
  await expect(page.locator('aside').getByRole('link', { name: '集群健康与热力图', exact: true })).toHaveCount(0)
  await expect(page.getByRole('heading',{name:'集群健康概览',exact:true})).toHaveCount(0)
  await expect(page.locator('.cluster-card')).toHaveCount(0)
  await expect(page.getByRole('heading',{name:'服务状态分布',exact:true})).toBeVisible()
  await expect(page.getByRole('heading',{name:'主机资源对比',exact:true})).toBeVisible()
  await expect(page.getByRole('heading',{name:'主机资源热力图',exact:true})).toBeVisible()
  await expect(page.locator('.heat-cell').filter({ hasText: 'host-a' })).toContainText('暂无数据')
  await expect(page.locator('.heat-cell').filter({ hasText: 'host-b' })).toContainText('采集过期')
  await expect(page.locator('.status-legend')).toContainText('正常 1')
  await expect(page.locator('.status-legend')).toContainText('异常 1')
  await page.getByLabel('集群范围').selectOption('configured:A')
  await expect(page.locator('.status-legend')).toContainText('正常 0')
  await expect(page.locator('.overview-charts .host-chart canvas')).toBeVisible()
  await page.locator('.metric-values>summary').click()
  await expect(page.locator('.metric-values tbody tr')).toHaveCount(1)
  await expect(page.locator('.metric-values tbody tr')).toContainText('暂无数据')
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


test('host comparison defaults to top ten and heatmap defaults to top ten without history requests',async({page})=>{
 const errors=[],history=[];page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;let json=[];
  if(path==='/api/auth/me')json={authenticated:true,user_id:'admin',admin:true};
  if(path.endsWith('/metrics/history'))history.push(path);
  if(path==='/api/overview/summary')json={summary:{instances:0,up:0,down:0,unknown:0},services:[],hosts:Array.from({length:100},(_,i)=>({id:i+1,host_name:'host-'+String(i+1).padStart(2,'0'),enabled:true,last_status:'UP',last_checked_at_epoch_ms:Date.now(),metric_checked_at:'2026-11-16 16:00:00',cpu_usage_percent:i+1,memory_used_percent:i+1,disk_used_percent:10})),errors:{},active_service_alerts:0};
  await route.fulfill({json});
 });
 await page.goto('/#/dashboard');await expect(page.locator('.host-chart canvas')).toBeVisible();
 await expect(page.getByLabel('资源对比主机数量')).toHaveValue('10');await expect(page.getByLabel('热力图主机数量')).toHaveValue('10');await expect(page.locator('.heat-cell')).toHaveCount(10);await expect(page.locator('.heat-cell').first()).toBeVisible();await page.getByLabel('热力图主机数量').selectOption('0');await expect(page.locator('.heat-cell')).toHaveCount(100);await page.getByLabel('热力图主机数量').selectOption('10');
 await page.locator('.metric-values summary').click();await expect(page.locator('.metric-values tbody tr')).toHaveCount(10);
 await expect(page.locator('.metric-values tbody tr').first()).toContainText('host-100');
 await page.getByLabel('资源对比主机数量').selectOption('0');await expect(page.locator('.metric-values tbody tr')).toHaveCount(100);
 await page.getByLabel('资源对比主机数量').selectOption('10');await expect(page.locator('.metric-values tbody tr')).toHaveCount(10);
 await page.getByRole('button',{name:'内存',exact:true}).click();await expect(page.locator('.heat-cell').first()).toContainText('100.0%');
 await page.screenshot({path:'../../tmp/global-host-layout-desktop.png',fullPage:true});
 await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await page.screenshot({path:'../../tmp/global-host-layout-mobile.png',fullPage:true});
 expect(history).toEqual([]);expect(errors).toEqual([]);
})
