import assert from 'node:assert/strict'
import { writeFileSync, mkdirSync } from 'node:fs'
const base = process.env.LIVE_MONITOR_ACCEPTANCE_URL || 'http://127.0.0.1:18080'
assert.equal(new URL(base).hostname, '127.0.0.1', 'Local acceptance only')
const login = await fetch(base + '/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'admin', password: process.env.LIVE_MONITOR_TEST_PASSWORD || '000000' }) })
assert.equal(login.status, 200)
const cookie = login.headers.get('set-cookie').split(';')[0]
const get = async path => { const response = await fetch(base + path, { headers: { cookie } }); assert.equal(response.status, 200); return (await response.json()).data }
const applications = await get('/api/ops/resources/application'), snapshots = await get('/api/ops/resources/snapshot'), tasks = await get('/api/ops/tasks')
const bindings = new Map(applications.map(a => [a.id, a.host_id])), unique = new Set()
let containers = 0, historicalReceipts = 0
for (const snapshot of snapshots) for (const container of snapshot.data.containers || []) {
  assert.equal(bindings.get(container.app_id), snapshot.host_id, 'Snapshot crossed application host boundary')
  assert(!unique.has(container.app_id), 'Container was counted in multiple logical hosts')
  unique.add(container.app_id); containers++
}
for (const task of tasks.filter(t => t.action === 'probe')) {
  for (const container of JSON.parse(task.result_json || '{}').containers || []) assert.equal(bindings.get(container.app_id), task.host_id)
  historicalReceipts++
}
const result = { applicationBindings: bindings.size, snapshotsChecked: snapshots.length, containersChecked: containers, historicalReceiptsChecked: historicalReceipts,
  noCrossHostApplicationData: true, noDuplicateContainerCount: true }
mkdirSync('target/e2e', { recursive: true }); writeFileSync('target/e2e/host-scope.json', JSON.stringify(result, null, 2)); console.log(JSON.stringify(result))
