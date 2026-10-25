// The console follows registered resources and permissions, never business names/IPs.
export function preferredHost(hosts, applications, saved = '') {
  if (hosts.some(host => host.id === saved)) return saved
  const running = applications.find(app => app.desired_state === 'RUNNING' && hosts.some(host => host.id === app.host_id))
  return running?.host_id || hosts[0]?.id || ''
}
export function containerRows(applications, hostId) {
  return applications.filter(app => !hostId || app.host_id === hostId).flatMap(app =>
    (app.services || []).map(service => ({ app, service, key: app.kind + ':' + app.id + ':' + (service.service || service.name) })))
}
export function recoveryText(app, service) {
  if (app.stale || service.stale) return '等待采集'
  if (service.manual_stop || app.desired_state === 'STOPPED') return '人工停止'
  if (service.maintenance) return '维护中'
  if (service.circuit_open) return '重试已达上限'
  if (service.next_recovery_at) return '等待重试'
  const policy = service.restart_policy || app.recovery?.restart_policy
  return (policy && policy !== 'no') || app.recovery?.recover_unhealthy ? '已开启' : '未开启'
}
