export const installationFields = schema => Object.entries(schema || {}).filter(([, field]) => field.type !== 'generated' && !field.hidden)
export function installationParameters(schema, values) {
  return Object.fromEntries(Object.entries(schema || {}).filter(([,field]) => field.type !== 'generated').map(([key,field]) => [key, field.type === 'integer' ? Number(values[key]) : String(values[key] ?? '')]))
}
export function installationError(message) {
  if(String(message).includes('INSTALL_RESOURCE_LIMITS')||String(message).includes('INSTALL_MEMORY_POLICY'))return '安装组件超出主机已批准的安装资源额度，请完成交付初始化配置。'
  const labels = { INSTALL_SIGNATURE_NOT_TRUSTED:'安装包签名未通过，请使用正式发行包。', INSTALL_NOT_ENABLED_ON_HOST:'主机尚未启用安装功能，请完成交付初始化配置。', INSTALL_CAPABILITY_NOT_APPROVED:'主机尚未批准安装包要求的运行能力。', INSTALL_PLAN_EXPIRED_OR_CHANGED:'检查结果已失效，请返回重新检查。', INSTALL_PREFLIGHT_FAILED:'主机资源或端口发生变化，请返回重新检查。', INSTALL_FAILED_DATA_RETAINED_CHECK_HOST_DIAGNOSTICS:'组件启动或健康检查未通过，本次服务已停止，数据保留。', INSTALL_FAILED_STOP_REQUIRES_OPERATOR:'安装未完成，服务停止状态需要人工检查，数据保留。', HOST_DISK_FULL:'主机磁盘空间不足。' }
  const code = Object.keys(labels).find(key => String(message).includes(key))
  const services = String(message).match(/services: ([A-Za-z0-9_,.-]+)/)?.[1]
  return code ? labels[code] + (services ? ' 相关组件：' + services : '') : String(message)
}
export function installationDiagnostic(metadata, task, plan) {
  return { product:metadata?.product,version:metadata?.version,taskId:task?.id,status:task?.status,message:task?.message,events:task?.events,checks:plan?.checks,services:metadata?.services }
}
