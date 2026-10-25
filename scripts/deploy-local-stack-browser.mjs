import { chromium } from '../live-monitor-java/frontend/node_modules/playwright-core/index.mjs'
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import assert from 'node:assert/strict'

const options = Object.fromEntries(process.argv.slice(2).map(value => { const i = value.indexOf('='); assert(i > 2); return [value.slice(2, i), value.slice(i + 1)] }))
assert.equal(options.execute, 'acknowledged', 'Explicitly authorize this UI deployment with --execute=acknowledged')
assert(options.stack && options.output)
const base = options.url || 'http://127.0.0.1:18080'
assert.equal(new URL(base).hostname, '127.0.0.1')
const credentials = JSON.parse(readFileSync(options.auth || 'target/runtime-os-production-control/secrets-private.json', 'utf8').replace(/^\uFEFF/, ''))
const browser = await chromium.launch({ executablePath: process.env.LIVE_MONITOR_BROWSER || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless: true, args: ['--no-proxy-server'] })
mkdirSync(options.output, { recursive: true })
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } }), page = await context.newPage(), errors = []
  page.on('pageerror', error => errors.push(error.message))
  assert.equal((await context.request.post(base + '/api/auth/login', { data: { username: credentials.username, password: credentials.password } })).status(), 200)
  const resources = await (await context.request.get(base + '/api/ops/resources/stack')).json()
  const stack = resources.data.find(s => s.id === options.stack)
  assert(stack)
  const expected = `stack_deploy:${stack.id}:${stack.environment}`
  page.on('dialog', dialog => {
    if (dialog.type() === 'confirm' && dialog.message().includes('维护窗口')) return dialog.accept()
    if (dialog.type() === 'prompt' && dialog.message().includes(expected)) return dialog.accept(expected)
    return dialog.dismiss()
  })
  await page.goto(base + '/#/ops/stacks')
  await page.getByRole('heading', { name: '应用与依赖栈', exact: true }).waitFor()
  await page.screenshot({ path: options.output + '/before.png', fullPage: false })
  const card = page.locator('.ops-stack').filter({ has: page.getByRole('heading', { name: new RegExp(stack.name) }) })
  const responsePromise = page.waitForResponse(r => r.url().endsWith('/api/ops/operations') && r.request().postDataJSON()?.action === 'stack_deploy')
  await card.getByRole('button', { name: '备份并发布应用栈', exact: true }).click()
  const response = await responsePromise
  assert(response.ok(), 'Platform rejected deployment')
  let task = (await response.json()).data, previous = ''
  writeFileSync(options.output + '/task.json', JSON.stringify(task, null, 2))
  console.log(JSON.stringify({ taskId: task.id, launchedThroughVue: true, stackId: stack.id, packageSha256: stack.package_sha256 }))
  const deadline = Date.now() + 1500000
  while (Date.now() < deadline) {
    task = (await (await context.request.get(base + '/api/ops/tasks/' + task.id)).json()).data
    writeFileSync(options.output + '/task.json', JSON.stringify(task, null, 2))
    const status = `${task.status}:${task.progress}:${task.message}`
    if (status !== previous) {
      console.log(JSON.stringify({ status: task.status, progress: task.progress, message: task.message }))
      await page.screenshot({ path: options.output + '/progress.png', fullPage: false }); previous = status
    }
    if (['SUCCEEDED', 'FAILED', 'INTERRUPTED'].includes(task.status)) break
    await page.waitForTimeout(2000)
  }
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await page.screenshot({ path: options.output + '/after.png', fullPage: false })
  writeFileSync(options.output + '/browser.json', JSON.stringify({ launchedThroughVue: true, finalStatus: task.status, taskId: task.id, errors }, null, 2))
  assert.equal(task.status, 'SUCCEEDED', task.message)
  assert.deepEqual(errors, [])
} finally {
  await browser.close()
}
