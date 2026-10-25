import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parsePairs, formatPairs, isTerminal, operationKey, uniqueRuntimeContainers } from '../src/ops/model.js'
import { createLogFollower } from '../src/ops/liveLogs.js'
import { deliverySteps } from '../src/ops/delivery.js'
import { preferredHost, containerRows, recoveryText } from '../src/ops/runtimeConsole.js'
import { installationFields, installationParameters, installationError, installationDiagnostic } from '../src/ops/installerModel.js'

test('installer displays package-defined fields and does not expose generated credentials',()=>{
  const schema={port:{type:'integer'},password:{type:'secret'},key:{type:'generated'},image:{type:'text',hidden:true}}
  assert.deepEqual(installationFields(schema).map(([key])=>key),['port','password'])
  assert.deepEqual(installationParameters(schema,{port:'18888',password:'test',key:'must-not-send',image:'sha256:id',unexpected:'ignored'}),{port:18888,password:'test',image:'sha256:id'})
})
test('installer diagnostics exclude parameter values, plans and raw tool results',()=>{
  const result=installationDiagnostic({product:'test',parameters:{password:'private-value'}},{id:'task',status:'FAILED',result_json:'private-raw-output'},{checks:[{passed:false}],configuration:{password:'private-value'}})
  assert(!JSON.stringify(result).includes('private'));assert.equal(result.taskId,'task');assert.deepEqual(result.checks,[{passed:false}])
})
test('installer errors identify failed services and require a new expired preflight',()=>{
  assert(installationError('Agent operation failed [INSTALL_PLAN_EXPIRED_OR_CHANGED]').includes('重新检查'))
  assert(installationError('Agent operation failed [INSTALL_FAILED_DATA_RETAINED_CHECK_HOST_DIAGNOSTICS]; services: api,mcp').includes('api,mcp'))
})

test('runtime console chooses a registered running target without business names', () => {
  const hosts = [{id: 'sandbox'}, {id: 'customer-node'}], apps = [{host_id: 'customer-node', desired_state: 'RUNNING'}]
  assert.equal(preferredHost(hosts, apps), 'customer-node')
  assert.equal(preferredHost(hosts, apps, 'sandbox'), 'sandbox')
  assert.equal(preferredHost(hosts, apps, 'unauthorized'), 'customer-node')
  assert.equal(preferredHost([], apps), '')
})
test('runtime container rows remain bound to host and application identities', () => {
  const apps = [{id: 'suite', host_id: 'a', kind: 'stack', services: [{service: 'api'}]}, {id: 'other', host_id: 'b', kind: 'application', services: [{name: 'api'}]}]
  const rows = containerRows(apps, 'a')
  assert.equal(rows.length, 1); assert.equal(rows[0].app.id, 'suite'); assert.equal(rows[0].service.service, 'api')
  assert.equal(new Set(containerRows(apps).map(row => row.key)).size, 2)
})
test('automatic restart display respects manual stop, stale data and circuit state', () => {
  const app = {desired_state: 'RUNNING', recovery: {restart_policy: 'on-failure'}}
  assert.equal(recoveryText(app, {}), '已开启')
  assert.equal(recoveryText(app, {manual_stop: true}), '人工停止')
  assert.equal(recoveryText({...app, stale: true}, {}), '等待采集')
  assert.equal(recoveryText(app, {circuit_open: true}), '重试已达上限')
  assert.equal(recoveryText(app, {maintenance: true}), '维护中')
})
test('a configured retry limit alone does not claim stack recovery is enabled', () => {
  assert.equal(recoveryText({kind: 'stack', recovery: {limit: 3, recover_unhealthy: false}}, {restart_policy: 'no'}), '未开启')
})

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
