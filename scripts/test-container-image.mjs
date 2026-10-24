import { execFileSync } from 'node:child_process'
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { createHash } from 'node:crypto'
import assert from 'node:assert/strict'
import { chromium } from '../live-monitor-java/frontend/node_modules/playwright-core/index.mjs'

const name = process.env.LIVE_MONITOR_TEST_PLATFORM_CONTAINER || 'live-monitor-platform-acceptance', base = process.env.LIVE_MONITOR_TEST_PLATFORM_URL || 'http://127.0.0.1:18081'
assert.equal(new URL(base).hostname,'127.0.0.1')
const inspect = JSON.parse(execFileSync('docker', ['inspect', name], { encoding: 'utf8' }))[0]
assert.equal(inspect.Config.Labels['live-monitor.acceptance'], 'true')
assert.equal(inspect.Config.User, '10001:10001')
assert.equal(inspect.State.Health.Status, 'healthy')
assert(!inspect.Mounts.some(m => m.Destination.includes('docker.sock')))
const artifact = createHash('sha256').update(readFileSync('target/container-ops-delivery/app.jar')).digest('hex')
const runningHash = execFileSync('docker', ['exec', name, 'sha256sum', '/app/app.jar'], { encoding: 'utf8' }).split(/\s/)[0]
assert.equal(runningHash, artifact)
assert.equal(execFileSync('docker', ['exec', name, 'id', '-u'], { encoding: 'utf8' }).trim(), '10001')
const browser = await chromium.launch({ executablePath: process.env.LIVE_MONITOR_BROWSER || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless: true, args: ['--no-proxy-server'] })
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } }), page = await context.newPage(), errors = []
  page.on('pageerror', e => errors.push(e.message))
  const health = await context.request.get(base + '/api/health'); assert.equal(health.status(), 200)
  const login = await context.request.post(base + '/api/auth/login', { data: { username: 'admin', password: '000000' } }); assert.equal(login.status(), 200)
  await page.goto(base + '/#/ops/overview'); await page.getByRole('heading', { name: '平台总览', exact: true }).waitFor()
  const capabilities = await context.request.get(base + '/api/ops/capabilities'); assert.equal(capabilities.status(), 200)
  assert.equal((await capabilities.json()).data.administrator, true)
  assert.deepEqual(errors, [])
  mkdirSync('target/e2e/image', { recursive: true })
  await page.screenshot({ path: 'target/e2e/image/platform.png' })
  const report = { image: inspect.Config.Image, imageId: inspect.Image, sourceRevision: inspect.Config.Labels['org.opencontainers.image.revision'],
    health: inspect.State.Health.Status, user: inspect.Config.User, runningArtifactSha256: artifact, hostDockerSocketMounted: false,
    healthHttpStatus: health.status(), vue3PageRendered: true, authenticatedOpsApi: true, browserErrors: errors }
  writeFileSync('target/e2e/image/acceptance.json', JSON.stringify(report, null, 2)); console.log(JSON.stringify(report))
} finally { await browser.close() }
