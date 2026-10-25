import { request } from './index'

async function call(path, options) {
  const result = await request('/api/ops' + path, options)
  if (!result.success) throw new Error(result.message || '运维请求失败')
  return result.data
}
const post = (path, body) => call(path, { method: 'POST', headers: { 'X-Ops-Request': '1' }, body: JSON.stringify(body) })
export const opsApi = {
  overview: () => call('/overview'),
  capabilities: () => call('/capabilities'),
  applicationTemplate: () => call('/templates/application'),
  list: kind => call('/resources/' + encodeURIComponent(kind)),
  save: (kind, value) => post('/resources/' + encodeURIComponent(kind), value),
  monitorHosts: () => call('/monitor-hosts'),
  tasks: () => call('/tasks'),
  task: id => call('/tasks/' + encodeURIComponent(id)),
  operate: request => post('/operations', request),
  importImage: body => call('/images/import', { method: 'POST', headers: { 'X-Ops-Request': '1' }, body }),
  acknowledge: id => post('/alerts/' + encodeURIComponent(id) + '/ack', {}),
  audit: () => call('/audit')
}
