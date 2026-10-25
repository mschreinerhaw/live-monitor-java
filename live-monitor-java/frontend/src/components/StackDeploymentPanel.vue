<template>
  <section class="ops-panel ops-form">
    <h2>应用与依赖栈</h2>
    <p>一个发布单元管理多个业务镜像、依赖顺序、网络和数据。服务端批准的发布包保存配置与密钥引用；页面仅提交包标识及校验值。</p>
    <form @submit.prevent="save('stack', draft)">
      <div class="ops-grid">
        <label>应用栈标识<input v-model="draft.id" required :readonly="!!draft.revision"></label>
        <label>应用栈名称<input v-model="draft.name" required></label>
        <label>目标服务器<select v-model="draft.host_id" required :disabled="!!draft.revision" @change="selectHost"><option value="">选择服务器</option><option v-for="host in state.host" :key="host.id" :value="host.id">{{ host.name }} · {{ host.environment }}</option></select></label>
        <label>部署环境<input v-model="draft.environment" readonly required></label>
        <label>已批准发布包<input v-model="draft.package_ref" required></label>
        <label class="ops-wide">发布包 SHA256<input v-model="draft.package_sha256" required pattern="[a-f0-9]{64}"></label>
      </div>
      <button class="btn btn-primary" :disabled="busy || !permitted(draft.host_id, 'stack_deploy')">保存应用栈</button>
      <button type="button" @click="reset">新建</button>
    </form>
  </section>
  <section v-for="stack in state.stack" :key="stack.id" class="ops-panel ops-stack">
    <h2>{{ stack.name }} <span class="ops-badge">{{ stack.environment }}</span></h2>
    <p>{{ stack.host_id }} · {{ stack.package_ref }} · 运行意图：{{ stack.desired_state }}</p>
    <code>{{ stack.package_sha256 }}</code>
    <div class="ops-actions">
      <button @click="edit(stack)">编辑发布配置</button>
      <button :disabled="busy || !permitted(stack.host_id, 'stack_plan')" @click="operate('stack_plan', stack)">部署前检查</button>
      <button :disabled="busy || !permitted(stack.host_id, 'stack_probe')" @click="operate('stack_probe', stack)">采集运行状态</button>
      <button :disabled="busy || !permitted(stack.host_id, 'stack_deploy')" @click="release('stack_deploy', stack)">备份并发布应用栈</button>
      <button :disabled="busy || !permitted(stack.host_id, 'stack_rollback')" @click="release('stack_rollback', stack)">备份并回滚配置</button>
      <button :disabled="busy || !permitted(stack.host_id, 'stack_backup')" @click="release('stack_backup', stack)">一致性备份</button>
      <button :disabled="busy || !permitted(stack.host_id, 'stack_start')" @click="operate('stack_start', stack)">启动应用栈</button>
      <button :disabled="busy || !permitted(stack.host_id, 'stack_stop')" @click="operate('stack_stop', stack)">停止应用栈</button>
    </div>
    <p v-if="snapshot(stack)">最近采集：{{ time(snapshot(stack).observed_at) }} · {{ snapshot(stack).data?.healthy ? '健康' : '存在异常 / 待检查' }}</p>
    <div class="ops-table-wrap"><table><thead><tr><th>服务</th><th>镜像 Digest</th><th>状态</th><th>恢复</th><th>操作</th></tr></thead><tbody>
      <tr v-for="service in snapshot(stack)?.data?.services || []" :key="service.service"><td>{{ service.service }}</td><td><code>{{ service.image }}</code></td><td>{{ service.status }} · {{ service.manual_stop ? '人工停机' : service.healthy ? '健康' : '异常' }}</td><td>{{ service.recovery_attempts }} 次 {{ service.circuit_open ? ' / 已熔断' : '' }}</td><td>
        <small>CPU {{ service.resources?.CPUPerc || '—' }} / 内存 {{ service.resources?.MemUsage || '—' }}</small>
        <button v-for="action in serviceActions" :key="action.key" :disabled="busy || !permitted(stack.host_id, action.key)" @click="operate(action.key, stack, { service: service.service })">{{ action.label }}</button>
        <button :disabled="!permitted(stack.host_id, 'stack_logs')" @click="startLogs(stack, service.service)">实时日志</button>
      </td></tr>
    </tbody></table></div>
    <div v-if="selected?.stackId === stack.id" class="stack-logs"><h3>{{ selected.service }} 实时日志</h3><button @click="stopLogs">停止跟踪</button><p v-if="logError">{{ logError }}</p><pre>{{ logText || '正在拉取日志…' }}</pre></div>
    <h3>依赖服务</h3><div v-for="dependency in snapshot(stack)?.data?.dependencies || []" :key="dependency.name" class="ops-record"><strong>{{ dependency.name }}</strong><span>{{ dependency.healthy ? '健康' : '异常' }} · {{ dependency.management }}</span><code>{{ dependency.image }}</code></div>
    <details><summary>发布与回滚历史</summary><div v-for="version in state.stack_version.filter(v => v.stack_id === stack.id)" :key="version.id" class="ops-record"><span>{{ time(version.created_at) }}</span><strong>{{ version.action }}</strong><code>{{ version.spec?.package_ref }} · {{ version.task_id }}</code></div></details>
  </section>
</template>
<script setup>
import { onBeforeUnmount, reactive, ref } from 'vue'
import { opsApi } from '../api/ops'
import { createLogFollower } from '../ops/liveLogs'
const props = defineProps({ state: Object, busy: Boolean, permitted: Function, time: Function, operate: Function, save: Function })
const draft = reactive({ id: '', name: '', host_id: '', environment: '', package_ref: '', package_sha256: '' })
const serviceActions = [{ key: 'stack_service_start', label: '启动' }, { key: 'stack_service_stop', label: '停止' }, { key: 'stack_service_restart', label: '重启' }]
const selected = ref(null), logText = ref(''), logError = ref('')
const follower = createLogFollower(opsApi, logs => { logText.value = logs }, error => { logError.value = error.message })
function stopLogs() { follower.stop(); selected.value = null }
function startLogs(stack, service) { stopLogs(); selected.value = { stackId: stack.id, service }; logText.value = ''; logError.value = ''; follower.start(stack.id, { action: 'stack_logs', parameters: { service } }) }
onBeforeUnmount(stopLogs)
function selectHost() { draft.environment = props.state.host.find(h => h.id === draft.host_id)?.environment || '' }
function reset() { Object.keys(draft).forEach(k => delete draft[k]); Object.assign(draft, { id: '', name: '', host_id: '', environment: '', package_ref: '', package_sha256: '' }) }
function edit(stack) { reset(); Object.assign(draft, stack) }
function snapshot(stack) { return props.state.stack_snapshot.find(s => s.stack_id === stack.id) }
function release(action, stack) { if (window.confirm('此操作将暂停应用进行一致性备份，再执行发布或恢复配置。确认已校验数据兼容性且允许本次维护窗口？原数据不会删除。')) return props.operate(action, stack, { data_compatible: true }) }
</script>
<style scoped>.stack-logs pre{white-space:pre-wrap;overflow-wrap:anywhere;max-height:360px;overflow:auto}</style>
