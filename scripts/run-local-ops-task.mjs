import { readFileSync, writeFileSync } from 'node:fs'
import { randomUUID } from 'node:crypto'
import assert from 'node:assert/strict'

const options = Object.fromEntries(process.argv.slice(2).map(arg => { const at = arg.indexOf('='); assert(at > 2, 'Use --key=value'); return [arg.slice(2, at), arg.slice(at + 1)] }))
const base = options.url || 'http://127.0.0.1:18080'
assert.equal(new URL(base).hostname, '127.0.0.1', 'This CLI talks to the local control plane, never directly to Docker')
const raw = readFileSync(options.auth || 'target/runtime-os-production-control/secrets-private.json')
const credentials = JSON.parse(raw.toString(raw[0] === 255 && raw[1] === 254 ? 'utf16le' : 'utf8').replace(/^\uFEFF/, ''))
let cookie = ''
async function api(path, data) {
  const response = await fetch(base + path, { method: data ? 'POST' : 'GET', headers: { cookie, 'Content-Type': 'application/json', 'X-Ops-Request': '1' }, ...(data ? { body: JSON.stringify(data) } : {}) })
  const next = response.headers.get('set-cookie'); if (next) cookie = next.split(';')[0]
  const body = await response.json()
  assert(response.ok, `${path} HTTP ${response.status}: ${body.message || body.detail || 'Request failed'}`)
  return path.startsWith('/api/ops/') ? body.data : body
}
for (let attempt = 0; attempt < 45; attempt++) {
  try { const response = await fetch(base + '/api/health'); assert(response.ok); break } catch (error) {
    if (attempt === 44) throw error
    await new Promise(resolve => setTimeout(resolve, 2000))
  }
}
await api('/api/auth/login', { username: credentials.username, password: credentials.password })
let taskId = options['task-id']
if (!taskId) {
  assert(options.action && options.resource, 'Use --task-id or provide --action and --resource')
  const parameters = options.parameters ? JSON.parse(readFileSync(options.parameters, 'utf8').replace(/^\uFEFF/, '')) : {}
  const task = await api('/api/ops/operations', { action: options.action, resource_id: options.resource, parameters, confirmation: options.confirmation || '', idempotency_key: options.key || randomUUID() })
  taskId = task.id
  if (options.output) writeFileSync(options.output, JSON.stringify(task, null, 2))
  console.log(JSON.stringify({ acceptedTaskId: taskId, action: task.action }))
}
let previous = '', complete = null
const deadline = Date.now() + 1500000
while (Date.now() < deadline) {
  const task = await api('/api/ops/tasks/' + taskId)
  const status = `${task.status}:${task.progress}:${task.message}`
  if (status !== previous) { console.log(JSON.stringify({ id: task.id, status: task.status, progress: task.progress, message: task.message })); previous = status }
  if (options.output) writeFileSync(options.output, JSON.stringify(task, null, 2))
  if (['SUCCEEDED', 'FAILED', 'INTERRUPTED'].includes(task.status)) { complete = task; break }
  await new Promise(resolve => setTimeout(resolve, 2000))
}
assert(complete, 'Task still running; inspect its journal before retrying')
assert.equal(complete.status, 'SUCCEEDED', complete.message)
