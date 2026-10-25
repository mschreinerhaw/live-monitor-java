import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parsePairs, formatPairs, isTerminal, operationKey, uniqueRuntimeContainers } from '../src/ops/model.js'
import { createLogFollower } from '../src/ops/liveLogs.js'
import { deliverySteps } from '../src/ops/delivery.js'

test('delivery cannot pass monitoring on stale evidence or restore from backup alone', () => {
  const state = { registry: [{}], application: [], stack: [{}], alert_rule: [], grant: [], backup: [{ verified: true, restore_tested: false }] }
  const monitoring = { hosts: [{ stale: true }], applications: [{ status: 'UNKNOWN', stale: true }] }
  const steps = deliverySteps(state, monitoring, [{ status: 'SUCCEEDED', action: 'stack_deploy' }])
  for (const key of ['host', 'release', 'monitor', 'alert', 'backup']) assert.equal(steps.find(s => s.key === key).done, false, key)
})

test('delivery monitoring requires a successful background sample, not only a manual probe', () => {
  const state = { registry: [], application: [], stack: [{}], alert_rule: [], grant: [], backup: [] }
  const monitoring = { hosts: [{ stale: false }], applications: [{ status: 'HEALTHY', stale: false }] }
  const tasks = [{ status: 'SUCCEEDED', action: 'stack_probe', actor: 'admin' }]
  assert.equal(deliverySteps(state, monitoring, tasks).find(s => s.key === 'monitor').done, false)
  tasks.push({ status: 'SUCCEEDED', action: 'stack_probe', actor: 'system' })
  assert.equal(deliverySteps(state, monitoring, tasks).find(s => s.key === 'monitor').done, true)
})

test('environment values preserve equals signs, spaces and Unicode', () => {
  assert.deepEqual(parsePairs('URL=https://example.test?a=b=c\nNAME=中文 空格\nEMPTY='), { URL: 'https://example.test?a=b=c', NAME: '中文 空格', EMPTY: '' })
})
test('duplicate variables are rejected rather than silently overwritten', () => {
  assert.throws(() => parsePairs('A=1\nA=2'), /重复/)
})
test('invalid lines cannot be submitted as a deployment', () => {
  assert.throws(() => parsePairs('NOT_A_PAIR'), /格式/)
  assert.throws(() => parsePairs('=missing'), /格式/)
})
test('secret references round trip without resolution or data loss', () => {
  const refs = { DB_PASSWORD: 'postgres-password', API_TOKEN: 'api-token' }
  assert.deepEqual(parsePairs(formatPairs(refs)), refs)
})
test('failed and unknown outcomes terminate streaming without claiming success', () => {
  for (const status of ['SUCCEEDED', 'FAILED', 'INTERRUPTED']) assert.equal(isTerminal(status), true)
  for (const status of ['QUEUED', 'RUNNING', '']) assert.equal(isTerminal(status), false)
})
test('request identity also works on internal HTTP origins without randomUUID', () => {
  assert.equal(operationKey({ getRandomValues: bytes => bytes.fill(255) }), 'ff'.repeat(16))
})

test('shared dependencies count once while separate hosts remain distinct', () => {
  assert.equal(uniqueRuntimeContainers([{ container_id: 'shared-db', host_id: 'host-a' }, { container_id: 'shared-db', host_id: 'host-a' }]).length, 1)
  assert.equal(uniqueRuntimeContainers([{ name: 'database', host_id: 'host-a' }, { name: 'database', host_id: 'host-b' }]).length, 2)
})

test('external process endpoints do not inflate the running Docker container count', () => {
  assert.equal(uniqueRuntimeContainers([{ container_id: 'external:license', status: 'running' }, { container_id: 'docker-container', status: 'running' }]).length, 1)
})
test('late log responses from another application are discarded', async () => {
  const pending = [], seen = [], timers = { setInterval: () => 1, clearInterval: () => {} }
  const api = { operate: body => new Promise(resolve => pending.push({ id: body.resource_id, resolve })) }
  const follower = createLogFollower(api, (logs, appId) => seen.push({ logs, appId }), error => { throw error }, timers)
  const first = follower.start('host-a-app'), second = follower.start('host-b-app')
  pending[1].resolve({ status: 'SUCCEEDED', result_json: JSON.stringify({ logs: 'B logs' }) }); await second
  pending[0].resolve({ status: 'SUCCEEDED', result_json: JSON.stringify({ logs: 'A logs' }) }); await first
  assert.deepEqual(seen, [{ logs: 'B logs', appId: 'host-b-app' }]); follower.stop()
})
test('stopping log follow prevents outstanding results from updating the page', async () => {
  let resolve; const seen = [], timers = { setInterval: () => 1, clearInterval: () => {} }
  const follower = createLogFollower({ operate: () => new Promise(done => { resolve = done }) }, v => seen.push(v), error => { throw error }, timers)
  const pending = follower.start('app'); follower.stop(); resolve({ status: 'SUCCEEDED', result_json: '{"logs":"private"}' }); await pending
  assert.deepEqual(seen, [])
})

test('stack log following keeps the selected service in every request', async () => {
  const requests = [], timers = { setInterval: () => 1, clearInterval: () => {} }
  const follower = createLogFollower({ operate: async request => { requests.push(request); return { status: 'SUCCEEDED', result_json: '{"logs":"service output"}' } } }, () => {}, error => { throw error }, timers)
  await follower.start('example-stack', { action: 'stack_logs', parameters: { service: 'web' } })
  assert.equal(requests[0].action, 'stack_logs'); assert.deepEqual(requests[0].parameters, { service: 'web' }); follower.stop()
})
