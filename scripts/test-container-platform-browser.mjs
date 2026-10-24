import { chromium } from '../live-monitor-java/frontend/node_modules/playwright-core/index.mjs'
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs'
import { randomUUID } from 'node:crypto'
import assert from 'node:assert/strict'

const base = process.env.LIVE_MONITOR_ACCEPTANCE_URL || 'http://127.0.0.1:18080'
assert.equal(new URL(base).hostname, '127.0.0.1', 'This acceptance fixture must never run on production')
const fixture = JSON.parse(readFileSync('target/e2e/fixture-private.json', 'utf8'))
const output = 'target/e2e/browser'; mkdirSync(output, { recursive: true })
const suffix = randomUUID().slice(0, 8), hostId = 'e2e-' + suffix, appId = 'web-' + suffix
const image = process.env.LIVE_MONITOR_TEST_IMAGE || 'docker.io/library/python@sha256:d9368b3a5ac59afea7b5d4f2e2aea0941dbf9fdee9c369c5bec00b98244bc929'
const browser = await chromium.launch({ executablePath: process.env.LIVE_MONITOR_BROWSER || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless: true, args: ['--no-proxy-server'] })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
const page = await context.newPage(), errors = [], checks = {}
page.setDefaultTimeout(30000); page.on('pageerror', error => errors.push(error.message))
page.on('dialog', dialog => {
  if (dialog.type() === 'prompt') dialog.accept(dialog.message().split('请输入 ').pop())
  else dialog.accept()
})
async function api(path, data, ctx = context) {
  const response = await ctx.request.fetch(base + path, { method: data ? 'POST' : 'GET', headers: { 'X-Ops-Request': '1' }, ...(data ? { data } : {}) })
  const body = await response.json()
  assert(response.ok(), JSON.stringify({ path, status: response.status(), message: body.message || body.detail }))
  return path.startsWith('/api/ops/') ? body.data : body
}
async function waitTask(id, expected = 'SUCCEEDED') {
  const deadline = Date.now() + 240000
  while (Date.now() < deadline) {
    const task = await api('/api/ops/tasks/' + id)
    if (['SUCCEEDED', 'FAILED', 'INTERRUPTED'].includes(task.status)) { assert.equal(task.status, expected, task.message); return task }
    await page.waitForTimeout(500)
  }
  throw new Error('Task deadline: ' + id)
}
async function clickOperation(button, action) {
  const pending = page.waitForResponse(r => r.url().endsWith('/api/ops/operations') && r.request().postDataJSON().action === action)
  await button.click(); const response = await pending; assert(response.ok(), await response.text())
  const task = (await response.json()).data
  return waitTask(task.id)
}
async function operation(action, id, environment, parameters = {}) {
  const task = await api('/api/ops/operations', { action, resource_id: id, idempotency_key: randomUUID(), parameters, confirmation: `${action}:${id}:${environment}` })
  return waitTask(task.id)
}
try {
  await page.goto(base + '/#/login')
  await page.getByPlaceholder('请输入管理员或运维账号').fill(process.env.LIVE_MONITOR_TEST_USERNAME || 'admin')
  await page.getByPlaceholder('请输入密码').fill(process.env.LIVE_MONITOR_TEST_PASSWORD || '000000')
  await page.getByRole('button', { name: '立即登录' }).click(); await page.waitForURL('**/#/dashboard')
  checks.loginAndOriginalDashboard = true
  for (const path of ['/api/dashboard', '/api/services', '/api/hosts', '/api/alert-channels', '/api/alerts']) await api(path)
  checks.originalMonitorApis = true
  const monitor = await api('/api/hosts', { host_name: hostId, ip: '127.0.0.1', ssh_port: 22222, ssh_user: 'root', private_key: fixture.private_key, enabled: true, check_interval: 31536000 })
  await page.goto(base + '/#/ops/servers')
  await page.getByLabel('服务器标识', { exact: true }).fill(hostId)
  await page.getByLabel('显示名称', { exact: true }).fill('本地隔离验收服务器 ' + suffix)
  await page.getByLabel('监控主机').selectOption(String(monitor.id))
  await page.getByLabel('环境', { exact: true }).fill('acceptance')
  await page.getByLabel('分组', { exact: true }).fill('local-test')
  await page.getByLabel('SSH 主机公钥', { exact: true }).fill(fixture.ssh_host_key)
  const saved = page.waitForResponse(r => r.url().endsWith('/api/ops/resources/host') && r.request().method() === 'POST')
  await page.getByRole('button', { name: '注册服务器', exact: true }).click(); assert((await saved).ok())
  const probe = await clickOperation(page.getByRole('row').filter({ hasText: '本地隔离验收服务器 ' + suffix }).getByRole('button', { name: '连接检测' }), 'probe')
  assert(JSON.parse(probe.result_json).docker_version); checks.pinnedSshAgentAndServerRegistration = true
  const unavailableMonitor = await api('/api/hosts', { host_name: 'offline-' + suffix, ip: '127.0.0.1', ssh_port: 1, ssh_user: 'root', private_key: fixture.private_key, enabled: true, check_interval: 31536000 })
  const unavailableId = 'offline-' + suffix
  await api('/api/ops/resources/host', { id: unavailableId, name: '隔离离线验收', monitor_host_id: unavailableMonitor.id, environment: 'acceptance', group: 'offline-test', ssh_host_key: fixture.ssh_host_key })
  const parallel = await Promise.all([api('/api/ops/operations', { action: 'probe', resource_id: hostId, parameters: {}, idempotency_key: randomUUID() }), api('/api/ops/operations', { action: 'probe', resource_id: unavailableId, parameters: {}, idempotency_key: randomUUID() })])
  await Promise.all([waitTask(parallel[0].id), waitTask(parallel[1].id, 'FAILED')]); checks.offlineHostDoesNotBlockOtherHost = true
  await page.goto(base + '/#/ops/images')
  await page.getByLabel('仓库标识', { exact: true }).fill('registry-' + suffix)
  await page.getByLabel('名称', { exact: true }).fill('Docker Hub')
  await page.getByLabel('域名与端口', { exact: true }).fill('docker.io')
  const registrySaved = page.waitForResponse(r => r.url().endsWith('/api/ops/resources/registry') && r.request().method() === 'POST')
  await page.getByRole('button', { name: '保存仓库', exact: true }).click(); assert((await registrySaved).ok()); checks.registryConfigurationThroughUi = true
  const template = await api('/api/ops/templates/application')
  const spec = { ...template, id: appId, name: '隔离 HTTP 验收应用 ' + suffix, host_id: hostId, environment: 'acceptance', image, memory_mb: 64, cpus: 0.5,
    command: ['python', '-m', 'http.server', '8080'], health: { command: ['python', '-c', "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/')"], interval_seconds: 1, timeout_seconds: 1 },
    restart_policy: 'no', health_deadline_seconds: 20 }
  await api('/api/ops/resources/application', spec)
  await page.goto(base + '/#/ops/deployments'); await page.getByRole('button', { name: '刷新', exact: true }).click()
  const app = page.locator('.ops-app').filter({ hasText: spec.name })
  await app.getByRole('button', { name: '编辑配置' }).click()
  await page.getByLabel('应用名称', { exact: true }).fill(spec.name + '（表单验收）')
  const applicationSaved = page.waitForResponse(r => r.url().endsWith('/api/ops/resources/application') && r.request().method() === 'POST')
  await page.getByRole('button', { name: '保存部署配置', exact: true }).click(); assert((await applicationSaved).ok()); checks.standardDeploymentFormSaved = true
  await clickOperation(app.getByRole('button', { name: '发布前校验' }), 'validate')
  const deployed = await clickOperation(app.getByRole('button', { name: '确认发布 / 升级' }), 'deploy')
  assert(JSON.parse(deployed.result_json).healthy); checks.deploymentThroughUiJavaSshAndDocker = true
  assert((await api('/api/ops/resources/version')).some(v => v.app_id === appId)); checks.immutableVersionHistory = true
  await operation('probe', hostId, 'acceptance')
  for (const section of ['overview', 'servers', 'images', 'deployments', 'containers', 'backups', 'alerts', 'tasks', 'permissions']) {
    await page.goto(base + '/#/ops/' + section); await page.locator('.ops-workspace h1').waitFor()
    await page.screenshot({ path: `${output}/${section}.png`, fullPage: false })
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false, section + ' desktop overflow')
  }
  checks.nineRealPagesAndDesktopLayout = true
  await page.setViewportSize({ width: 390, height: 844 }); await page.goto(base + '/#/ops/deployments')
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false)
  await page.screenshot({ path: output + '/mobile.png', fullPage: false }); checks.mobileLayout = true
  await page.setViewportSize({ width: 1440, height: 1000 }); await page.goto(base + '/#/ops/containers')
  const logsPanel = page.locator('.ops-panel').filter({ has: page.getByRole('heading', { name: '实时日志', exact: true }) })
  await logsPanel.getByRole('button', { name: spec.name + '（表单验收）', exact: true }).click()
  await logsPanel.locator('pre').filter({ hasText: 'GET / HTTP' }).waitFor(); await logsPanel.getByRole('button', { name: '停止跟踪' }).click(); checks.liveStdoutAndStderrLogs = true
  const row = page.getByRole('row').filter({ hasText: appId })
  await clickOperation(row.getByRole('button', { name: '停止', exact: true }), 'stop')
  let apps = await api('/api/ops/resources/application'); assert.equal(apps.find(a => a.id === appId).desired_state, 'STOPPED'); checks.manualStopIntent = true
  await clickOperation(row.getByRole('button', { name: '启动', exact: true }), 'start')
  checks.containerLifecycleThroughUi = true
  const userId = 'viewer-' + suffix, password = randomUUID()
  await api('/api/admin/users', { user_id: userId, password, name: '隔离只读验收用户', enabled: true })
  await api('/api/ops/resources/grant', { id: 'grant-' + suffix, user_id: userId, host_id: hostId, environment: 'acceptance', role: 'viewer', actions: 'read,logs' })
  const viewer = await browser.newContext()
  await api('/api/auth/login', { username: userId, password }, viewer)
  assert.equal((await viewer.request.get(base + '/api/hosts')).status(), 403)
  assert.equal((await viewer.request.post(base + '/api/services', { data: {} })).status(), 403)
  checks.legacyManagementBypassDenied = true
  const viewerPage = await viewer.newPage()
  await viewerPage.goto(base + '/#/hosts')
  await viewerPage.waitForURL('**/#/ops/overview')
  assert.equal(await viewerPage.locator('.ops-sidebar a[href="#/hosts"]').count(), 0)
  checks.operationsOnlyNavigation = true
  const denied = await viewer.request.post(base + '/api/ops/operations', { headers: { 'X-Ops-Request': '1' }, data: { action: 'restart', resource_id: appId, parameters: {}, idempotency_key: randomUUID(), confirmation: `restart:${appId}:acceptance` } })
  assert.equal(denied.status(), 403); checks.directUnauthorizedOperationDenied = true
  const scopedHosts = await api('/api/ops/resources/host', null, viewer); assert(scopedHosts.every(h => h.id === hostId)); checks.serverAndEnvironmentReadScope = true
  const otherHostDenied = await viewer.request.post(base + '/api/ops/operations', { headers: { 'X-Ops-Request': '1' }, data: { action: 'probe', resource_id: unavailableId, parameters: {}, idempotency_key: randomUUID() } })
  assert.equal(otherHostDenied.status(), 403); checks.crossServerOperationDenied = true
  assert((await api('/api/ops/audit')).some(a => a.outcome === 'DENIED')); checks.permissionAudit = true
  await operation('stop', appId, 'acceptance')
  assert.deepEqual(errors, []); checks.noBrowserErrors = true
  writeFileSync(output + '/acceptance.json', JSON.stringify({ checks, hostId, appId, deployedTaskId: deployed.id, errors }, null, 2))
  console.log(JSON.stringify(checks))
} catch (error) { await page.screenshot({ path: output + '/failure.png', fullPage: false }).catch(() => {}); writeFileSync(output + '/failed-checks.json', JSON.stringify({ checks, error: error.message, errors }, null, 2)); throw error }
finally { await browser.close() }
