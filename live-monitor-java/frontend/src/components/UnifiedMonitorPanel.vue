<template>
  <section class="ops-panel unified-monitor">
    <h2>应用总体监控</h2><p>覆盖单容器应用、多服务应用栈及依赖；未知或过期数据不计作健康。最近汇总 {{ time(monitoring.observed_at) }}</p>
    <div class="ops-grid ops-form"><label>服务器<select v-model="host"><option value="">全部服务器</option><option v-for="h in monitoring.hosts" :key="h.id" :value="h.id">{{ h.name || h.id }}</option></select></label><label>环境<select v-model="environment"><option value="">全部环境</option><option v-for="env in environments" :key="env">{{ env }}</option></select></label></div>
    <p v-if="!applications.length" class="ops-empty">尚无当前范围的应用。请从部署交付向导注册服务器并配置应用。</p>
    <article v-for="app in applications" :key="app.kind + app.id" class="ops-app">
      <h3>{{ app.name || app.id }} <span class="ops-badge" :class="{ warning: !['HEALTHY', 'STOPPED'].includes(app.status) }">{{ statusText[app.status] || app.status }}</span></h3>
      <p>{{ app.host_id }} · {{ app.environment }} · 最近采集 {{ time(app.observed_at) }} · {{ app.stale ? '数据已过期' : '持续采集中' }}</p><code>{{ app.version || '尚未发布' }}</code>
      <p v-if="app.kind === 'stack'">恢复判定窗口 {{ app.recovery.failure_window_seconds ?? '待采集' }} 秒 · 最多 {{ app.recovery.limit ?? '待采集' }} 次 · Docker 原生重启与平台健康恢复协作</p>
      <p v-else>重启策略 {{ app.recovery.restart_policy || '尚未发布' }} · 最多 {{ app.recovery.limit ?? '—' }} 次 · unhealthy 恢复 {{ app.recovery.recover_unhealthy ? '启用' : '未启用' }}</p>
      <div class="ops-table-wrap"><table><thead><tr><th>服务</th><th>运行 / 健康</th><th>CPU / 内存</th><th>恢复状态</th><th>操作</th></tr></thead><tbody><tr v-for="service in app.services" :key="service.container_id || service.name"><td>{{ service.name }}<small>{{ service.container_id?.slice(0, 12) || '容器缺失' }}</small></td><td>{{ service.status }} / {{ service.manual_stop ? '人工停机' : service.stale ? '未知' : service.healthy ? '健康' : '异常' }}<small v-if="service.image_drift">镜像漂移，禁止自动恢复</small></td><td>{{ service.resources?.CPUPerc || '—' }} / {{ service.resources?.MemUsage || '—' }}</td><td>{{ service.circuit_open ? '已熔断，需人工处理' : service.manual_stop ? '人工停止，禁止自动拉起' : '策略生效' }}<small>平台 {{ service.recovery_attempts || 0 }} 次 / 原生 {{ service.restart_count ?? service.native_restarts ?? 0 }} 次</small><small v-if="service.next_recovery_at">下次尝试 {{ time(service.next_recovery_at) }}</small></td><td><button v-for="action in actions" :key="action.key" :disabled="busy || !permitted(app.host_id, operation(app, action.key))" @click="run(app, service, action.key)">{{ action.label }}</button></td></tr></tbody></table></div>
      <div v-for="d in app.dependencies" :key="d.name" class="ops-record"><strong>{{ d.name }}</strong><span>{{ app.stale ? '未知' : d.healthy ? '健康' : '异常' }}</span><span>{{ d.management }}</span></div>
      <router-link :to="app.kind === 'stack' ? '/ops/stacks' : '/ops/deployments'">配置、实时日志与版本 →</router-link>
    </article>
  </section>
</template>
<script setup>
import { computed, ref } from 'vue'
const props = defineProps({ monitoring: Object, state: Object, busy: Boolean, permitted: Function, operate: Function, time: Function })
const host = ref(''), environment = ref('')
const statusText = { HEALTHY: '健康', UNHEALTHY: '异常', UNKNOWN: '采集过期 / 未知', STOPPED: '人工停机', NOT_DEPLOYED: '未发布' }
const environments = computed(() => [...new Set(props.monitoring.hosts.map(h => h.environment))])
const applications = computed(() => props.monitoring.applications.filter(a => (!host.value || a.host_id === host.value) && (!environment.value || a.environment === environment.value)).sort((a, b) => Number(a.status === 'STOPPED') - Number(b.status === 'STOPPED')))
const actions = [{ key: 'start', label: '启动' }, { key: 'stop', label: '停止' }, { key: 'restart', label: '重启' }]
const operation = (app, key) => app.kind === 'stack' ? 'stack_service_' + key : key
function run(app, service, key) { return props.operate(operation(app, key), props.state[app.kind].find(a => a.id === app.id), app.kind === 'stack' ? { service: service.service } : {}) }
</script>
