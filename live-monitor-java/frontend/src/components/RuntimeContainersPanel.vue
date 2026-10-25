<template>
  <section class="ops-panel runtime-containers">
    <div class="runtime-summary"><span>容器 <strong>{{ rows.length }}</strong></span><span>运行中 <strong>{{ running }}</strong></span><span>异常 <strong>{{ abnormal }}</strong></span><router-link class="btn btn-primary" to="/ops/images">导入 / 启动镜像</router-link></div>
    <p class="runtime-hint">异常退出按策略自动拉起；手动停止后保持停机。</p>
    <div class="ops-table-wrap"><table><thead><tr><th>容器</th><th>状态</th><th>CPU / 内存</th><th>自动拉起</th><th>操作</th></tr></thead><tbody><tr v-for="row in rows" :key="row.key"><td><strong>{{ row.service.service || row.app.name || row.service.name }}</strong><small>{{ row.app.kind === 'stack' ? row.app.name : row.service.name }}</small></td><td><span class="ops-badge" :class="{ warning: isAbnormal(row) }">{{ status(row) }}</span></td><td>{{ row.service.resources?.CPUPerc || '—' }}<small>{{ row.service.resources?.MemUsage || '—' }}</small></td><td>{{ recoveryText(row.app, row.service) }}<small v-if="row.service.native_restarts || row.service.recovery_attempts">已重启 {{ (row.service.native_restarts || row.service.restart_count || 0) + (row.service.recovery_attempts || 0) }} 次</small></td><td class="runtime-buttons"><button v-for="action in actions" :key="action.key" :disabled="busy || !permitted(row.app.host_id, operation(row.app, action.key)) || (action.key === 'start' && row.service.status === 'running' && !row.service.manual_stop) || (action.key === 'stop' && row.service.manual_stop)" @click="run(row, action.key)">{{ action.label }}</button><button :disabled="!permitted(row.app.host_id, row.app.kind === 'stack' ? 'stack_logs' : 'logs')" @click="showLogs(row)">日志</button><router-link v-if="row.app.kind !== 'stack'" :to="{path: '/ops/images', query: {target: row.app.id}}">更新镜像</router-link></td></tr></tbody></table></div>
    <p v-if="!rows.length" class="ops-empty">还没有运行容器。从镜像管理导入镜像后点击“启动”。</p>
    <div v-if="alerts.length" class="runtime-alert" role="status">{{ alerts.length }} 条待处理异常：{{ alerts[0].code || '容器运行异常' }}。请查看异常容器的状态或日志。</div>
    <div v-if="logTarget" class="runtime-log"><div class="runtime-summary"><h3>{{ logTarget }} · 日志</h3><button @click="closeLogs">关闭</button></div><pre>{{ logError || (logs === null ? '正在读取日志…' : logs || '暂无日志') }}</pre></div>
  </section>
</template>
<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { containerRows, recoveryText } from '../ops/runtimeConsole'
import { createLogFollower } from '../ops/liveLogs'
import { opsApi } from '../api/ops'
const props = defineProps({ monitoring: Object, state: Object, hostId: String, busy: Boolean, permitted: Function, operate: Function })
const rows = computed(() => containerRows(props.monitoring.applications, props.hostId))
const running = computed(() => rows.value.filter(row => !row.app.stale && !row.service.stale && row.service.status === 'running').length)
const isAbnormal = row => !row.app.stale && !row.service.stale && !row.service.manual_stop && row.app.desired_state !== 'STOPPED' && (!row.service.healthy || row.service.resource_abnormal || row.service.image_drift || row.service.circuit_open)
const abnormal = computed(() => rows.value.filter(isAbnormal).length)
const alerts = computed(() => props.monitoring.alerts.filter(alert => alert.host_id === props.hostId && alert.status === 'OPEN'))
const actions = [{key: 'start', label: '启动'}, {key: 'stop', label: '停止'}, {key: 'restart', label: '重启'}]
const operation = (app, key) => app.kind === 'stack' ? 'stack_service_' + key : key
const status = ({app, service}) => app.stale || service.stale ? '状态未知' : service.manual_stop || app.desired_state === 'STOPPED' ? '已停止' : service.status !== 'running' ? '已退出' : service.health === 'starting' ? '启动中' : service.healthy ? '运行中' : '运行异常'
function run(row, action) { return props.operate(operation(row.app, action), props.state[row.app.kind].find(item => item.id === row.app.id), row.app.kind === 'stack' ? {service: row.service.service} : {}) }
const logTarget = ref(''), logs = ref(null), logError = ref('')
const follower = createLogFollower(opsApi, value => { logs.value = value }, error => { logError.value = error.message })
function closeLogs() { follower.stop(); logTarget.value = '' }
function showLogs({app, service}) { closeLogs(); logs.value = null; logError.value = ''; logTarget.value = service.service || service.name; follower.start(app.id, app.kind === 'stack' ? { action: 'stack_logs', parameters: {service: service.service} } : {}) }
watch(() => props.hostId, closeLogs)
onBeforeUnmount(closeLogs)
</script>
