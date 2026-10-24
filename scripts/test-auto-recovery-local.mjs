import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
assert.equal(process.env.LIVE_MONITOR_ALLOW_LOCAL_FAULTS, '1', 'Explicit isolated fault-injection opt-in required')
const base = 'http://127.0.0.1:18084', fixture = JSON.parse(readFileSync('target/e2e/fixture-private.json', 'utf8'))
const login = await fetch(base + '/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'admin', password: '000000' }) })
assert.equal(login.status, 200); const cookie = login.headers.get('set-cookie').split(';')[0]
async function api(path, data) { const r = await fetch(base + path, { method: data ? 'POST' : 'GET', headers: { cookie, 'Content-Type': 'application/json', 'X-Ops-Request': '1' }, ...(data ? { body: JSON.stringify(data) } : {}) }); const value = await r.json(); assert(r.ok, JSON.stringify({ path, status: r.status, message: value.message })); return path.startsWith('/api/ops/') ? value.data : value }
async function until(check, seconds = 150) { const deadline = Date.now() + seconds * 1000; while (Date.now() < deadline) { const value = await check(); if (value) return value; await new Promise(resolve => setTimeout(resolve, 750)) } throw Error('Automatic recovery acceptance deadline exceeded') }
const suffix = randomUUID().slice(0, 8), host = 'auto-host-' + suffix, appId = 'auto-' + suffix
assert.equal((await api('/api/ops/resources/host')).length, 0, 'Fresh isolated platform database required')
assert.equal((await api('/api/ops/resources/application')).length, 0)
const monitor = await api('/api/hosts', { host_name: host, ip: '127.0.0.1', ssh_port: 22222, ssh_user: 'root', private_key: fixture.private_key, enabled: true, check_interval: 31536000 })
await api('/api/ops/resources/host', { id: host, name: '自动恢复隔离验收', monitor_host_id: monitor.id, environment: 'acceptance', ssh_host_key: fixture.ssh_host_key })
await api('/api/ops/resources/registry', { id: 'registry-' + suffix, name: 'Test Docker Hub', address: 'docker.io' })
const spec = { ...await api('/api/ops/templates/application'), id: appId, name: appId, host_id: host, environment: 'acceptance',
  image: 'docker.io/library/python@sha256:d9368b3a5ac59afea7b5d4f2e2aea0941dbf9fdee9c369c5bec00b98244bc929',
  cpus: 0.5, memory_mb: 64, command: ['python', '-m', 'http.server', '8080'], restart_policy: 'no', restart_limit: 1,
  failure_window_seconds: 1, recover_unhealthy: true, health_deadline_seconds: 20,
  health: { command: ['python', '-c', "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/')"], interval_seconds: 1, timeout_seconds: 1 } }
await api('/api/ops/resources/application', spec)
async function operation(action) { const t = await api('/api/ops/operations', { action, resource_id: appId, parameters: {}, confirmation: `${action}:${appId}:acceptance`, idempotency_key: randomUUID() }); return until(async () => { const task = await api('/api/ops/tasks/' + t.id); if (task.status === 'FAILED') throw Error(task.message); return task.status === 'SUCCEEDED' ? task : null }) }
await operation('deploy')
const name = 'lm-acceptance-' + appId
function inspect() { return JSON.parse(execFileSync('docker', ['inspect', name], { encoding: 'utf8' }))[0] }
function fault() { const container = inspect(); assert.equal(container.Config.Labels['live-monitor.app'], appId); assert.equal(container.Config.Labels['live-monitor.environment'], 'acceptance'); execFileSync('docker', ['kill', name], { stdio: 'pipe' }) }
let stopped = false
try {
const firstFault = Date.now(); fault()
const recovered = await until(async () => (await api('/api/ops/tasks')).find(t => t.resource_id === appId && t.action === 'recover' && t.actor === 'system' && t.status === 'SUCCEEDED'))
assert.equal(JSON.parse(recovered.result_json).reason, 'exited')
await until(() => inspect().State.Running && inspect().State.Health.Status === 'healthy', 30)
await until(async () => (await api('/api/ops/resources/alert')).some(a => a.app_id === appId && a.status === 'RECOVERED'), 60)
const secondFault = Date.now(); fault()
const circuit = await until(async () => (await api('/api/ops/resources/alert')).find(a => a.app_id === appId && a.code === 'CIRCUIT_OPEN' && a.status === 'OPEN' && a.created_at >= secondFault), 90)
assert.equal(inspect().State.Running, false)
assert.equal((await api('/api/ops/tasks')).filter(t => t.resource_id === appId && t.action === 'recover').length, 1)
await operation('stop')
stopped = true
const result = { appId, hostId: host, firstFaultAt: firstFault, automaticRecoveryTaskId: recovered.id, recoveryActor: recovered.actor,
  recoveryReason: JSON.parse(recovered.result_json).reason, recoverySeconds: (recovered.updated_at - firstFault) / 1000,
  recoveryEventObserved: true, secondFaultAt: secondFault, circuitAlertId: circuit.id, circuitSeverity: circuit.severity,
  recoveryAttempts: 1, noRetryAfterLimit: true, manuallyStoppedAtEnd: true, productionFaultInjection: false }
mkdirSync('target/e2e', { recursive: true }); writeFileSync('target/e2e/automatic-recovery.json', JSON.stringify(result, null, 2)); console.log(JSON.stringify(result))
} finally { if (!stopped) await operation('stop').catch(() => {}) }
