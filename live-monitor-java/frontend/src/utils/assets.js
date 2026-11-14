export const databaseTypes = { mysql: 'MySQL', oracle: 'Oracle', postgresql: 'PostgreSQL', postgres: 'PostgreSQL', jdbc: '通用 JDBC' }
export const isDatabase = service => Object.prototype.hasOwnProperty.call(databaseTypes, String(service.service_type || '').toLowerCase())
export function mountState(host) {
  if (!host.mount_monitor_enabled) return { label: '未启用', tone: 'badge-unknown', reason: '未启用挂载点监控' }
  if (host.enabled === false || host.monitor_enabled === false) return { label: '已停用', tone: 'badge-unknown', reason: '主机监控已停用' }
  if (Number(host.last_checked_at_epoch_ms) > 0 && Date.now() - Number(host.last_checked_at_epoch_ms) > Math.max(180, Number(host.check_interval || 60) * 3) * 1000) return { label: '数据过期', tone: 'badge-warn', reason: '挂载点状态超过多个采集周期未更新' }
  const points = host.mount_points || []
  const rows = points.map(path => (host.disk_metrics || []).find(row => row.monitored_mount && row.mount === path))
  const failed = rows.filter(row => row && ['MISSING', 'READ_ONLY', 'HIGH_USAGE'].includes(row.mount_state))
  if (failed.length) return { label: '挂载异常', tone: 'badge-down', reason: failed.map(row => row.mount + '：' + row.mount_reason).join('；') }
  if (!points.length || rows.some(row => !row || row.mount_state !== 'OK')) return { label: '未知 / 待采集', tone: 'badge-unknown', reason: '尚无完整有效的挂载点检测结果' }
  return { label: '正常', tone: 'badge-up', reason: points.length + ' 个挂载点正常' }
}
export function resourceWarnings(host) {
  if (host.enabled === false || host.monitor_enabled === false) return []
  return [
    ['CPU', host.cpu_usage_percent, host.cpu_threshold_percent, host.cpu_alert_enabled],
    ['内存', host.memory_used_percent, host.memory_threshold_percent, host.memory_alert_enabled],
    ['磁盘', host.disk_used_percent, host.disk_threshold_percent, host.disk_alert_enabled]
  ].filter(([, value, threshold, enabled]) => enabled !== false && value != null && Number(value) > Number(threshold ?? 85)).map(([name]) => name).concat(mountState(host).label === '挂载异常' ? ['挂载点'] : [])
}
export function hostState(host) {
  if (host.enabled === false || host.monitor_enabled === false) return { key: 'DISABLED', label: '已停用', tone: 'badge-unknown', reason: '主机或关联监控服务已停用，不执行定时检查。' }
  const status = host.last_status || host.status
  if (!status) return { key: 'PENDING', label: '等待采集', tone: 'badge-unknown', reason: '尚无检查结果，等待首次定时采集。' }
  const checkedAt = Number(host.last_checked_at_epoch_ms)
  if (checkedAt > 0 && Date.now() - checkedAt > Math.max(180, Number(host.check_interval || 60) * 3) * 1000) return { key: 'STALE', label: '数据过期', tone: 'badge-warn', reason: '超过多个采集周期未更新，请检查定时检查及主机连接。' }
  if (status === 'UP') {
    const warnings = resourceWarnings(host)
    return warnings.length ? { key: 'WARNING', label: '资源超阈', tone: 'badge-warn', reason: warnings.includes('挂载点') ? mountState(host).reason : warnings.join('、') + ' 超过配置阈值；告警通知由确认时长等规则决定。' } : { key: 'UP', label: '采集正常', tone: 'badge-up', reason: host.last_message || '最近一次资源采集成功。' }
  }
  return { key: 'ERROR', label: '采集异常', tone: 'badge-down', reason: host.last_message || '最近一次检查未得到有效结果，请检查 SSH 连接和采集命令。' }
}
