export function deliverySteps(state, monitoring, tasks) {
  const deployed = monitoring.applications.filter(a => a.status !== 'NOT_DEPLOYED')
  const success = actions => tasks.some(t => t.status === 'SUCCEEDED' && actions.includes(t.action))
  const allFresh = monitoring.hosts.length > 0 && monitoring.hosts.every(h => !h.stale)
  return [
    { key: 'host', title: '1 · 注册服务器并验证 Agent', detail: '关联主机凭据、核验公钥、分组与环境；连接检测必须成功且采集未过期。', route: 'servers', action: '配置服务器', done: allFresh },
    { key: 'image', title: '2 · 批准镜像来源', detail: '配置仓库和凭据引用，解析或拉取不可变 Digest；应用栈还需批准发布包。', route: 'images', action: '配置镜像', done: state.registry.length > 0 },
    { key: 'config', title: '3 · 配置应用及依赖', detail: '多服务应用登记发布包与 SHA256；单容器填写端口、卷、健康检查和恢复策略。', route: 'stacks', action: '配置应用栈', done: state.application.length + state.stack.length > 0 },
    { key: 'release', title: '4 · 校验、备份并确认发布', detail: '先检查，再明确授权发布；观察任务进度、失败原因和版本记录。', route: 'tasks', action: '查看发布任务', done: deployed.length > 0 && deployed.every(a => ['HEALTHY', 'STOPPED'].includes(a.status)) },
    { key: 'monitor', title: '5 · 验证持续监控', detail: '确认全部已发布应用数据新鲜；查看服务、依赖、CPU、内存、恢复策略及事件。', route: 'overview', action: '查看监控', done: allFresh && deployed.length > 0 && deployed.every(a => !a.stale) && tasks.some(t => t.actor === 'system' && t.status === 'SUCCEEDED' && ['probe', 'stack_probe'].includes(t.action)) },
    { key: 'alert', title: '6 · 设置告警规则与运维授权', detail: '配置应用范围、事件等级和处理人员；在隔离环境校验故障告警、自动恢复与人工停机保护。', route: 'alerts', action: '配置告警', done: state.alert_rule.some(r => r.enabled) && state.grant.some(g => g.enabled) },
    { key: 'backup', title: '7 · 完成备份恢复验收', detail: '配置定时备份并在隔离目标实际恢复；备份成功不等于恢复测试通过。', route: 'backups', action: '验证备份', done: state.backup.some(b => b.verified && b.restore_tested) && success(['restore_test']) }
  ]
}
