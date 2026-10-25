<template>
  <section class="ops-panel"><h2>{{ route.query.app ? '更新容器' : '启动镜像' }}</h2><p>确认名称和访问端口，其他参数沿用默认值。</p>
    <form v-if="spec" class="ops-form" @submit.prevent="submit">
      <div class="ops-grid">
        <label v-if="!route.query.app">启动方式<select v-model="existing" @change="selectApplication"><option value="">新建容器</option><option v-for="app in state.application" :key="app.id" :value="app.id">更新 {{ app.name || app.id }}</option></select></label>
        <label>应用名称<input v-model="spec.name" required></label>
        <label v-if="state.host.length > 1">目标服务器<select v-model="spec.host_id" required :disabled="!!existing" @change="selectHost"><option value="">请选择</option><option v-for="h in state.host" :key="h.id" :value="h.id">{{ h.name || h.id }}</option></select></label>
      </div>
      <fieldset><legend>访问端口（无需对外访问可留空）</legend><div v-for="(port, i) in spec.ports" :key="i" class="ops-row"><label>主机端口<input v-model.number="port.host" type="number" min="1024" max="65535" required></label><label>容器端口<input v-model.number="port.container" type="number" min="1" max="65535" required></label><select v-model="port.protocol"><option>tcp</option><option>udp</option></select><button type="button" @click="spec.ports.splice(i, 1)">移除</button></div><button type="button" @click="spec.ports.push({ host: null, container: null, protocol: 'tcp' })">添加端口</button></fieldset>
      <p>默认启用故障重启，手动停止后不会自动拉起。端口可访问范围由服务器绑定地址和防火墙决定。</p>
      <details><summary>启动选项（按需填写）</summary><label>镜像版本<input v-model="spec.image" required></label>
        <div class="ops-grid"><label>应用标识<input v-model="spec.id" :readonly="!!existing" required></label><label>CPU 核数<input v-model.number="spec.cpus" type="number" min="0.1" step="0.1" required></label><label>内存 MiB<input v-model.number="spec.memory_mb" type="number" min="16" required></label>
          <label>检测方式<select v-model="spec.health.mode"><option value="running">容器进程监控</option><option value="command">应用健康检查</option></select></label><label v-if="spec.health.mode === 'command'">健康检查命令（JSON 数组）<input v-model="health" required></label><label>故障恢复<select v-model="spec.restart_policy"><option value="on-failure">故障退出时自动重启</option><option value="unless-stopped">持续运行，人工停止除外</option><option value="no">关闭自动重启</option></select></label><label>应用持续不健康时自动恢复<input v-model="spec.recover_unhealthy" type="checkbox" :disabled="spec.health.mode !== 'command'"></label><label>故障重启上限<input v-model.number="spec.restart_limit" type="number" min="1" max="20" required></label>
          <label>环境变量<textarea v-model="env" placeholder="NAME=value；密钥请使用下方引用"></textarea></label><label>Secret 引用<textarea v-model="secrets" placeholder="ENV_NAME=服务器已有密钥引用"></textarea></label>
        </div>
        <p>带持久化数据的镜像请配置挂载。更新沿用既有挂载，不会自动删除原数据。</p>
        <div v-for="(mount, i) in spec.mounts" :key="i" class="ops-row"><label>卷名<input v-model="mount.source" required placeholder="lm- 开头的卷名"></label><label>容器目录<input v-model="mount.target" required></label><button type="button" @click="spec.mounts.splice(i, 1)">移除</button></div><button type="button" @click="addVolume">添加数据卷</button>
      </details>
      <p v-if="suggestedVolumes">镜像声明了数据目录：{{ suggestedVolumes }}。请先确认数据卷配置。</p>
      <p v-if="error" role="alert" class="ops-error">{{ error }}</p><p role="status">{{ message }}</p>
      <button class="btn btn-primary" :disabled="working || busy || !permitted(spec.host_id, 'deploy')">{{ working ? '正在启动…' : existing ? '确认更新' : '启动容器' }}</button>
    </form>
  </section>
</template>
<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { opsApi } from '../api/ops'
import { operationKey, parsePairs, formatPairs } from '../ops/model'
const props = defineProps({ state: Object, busy: Boolean, permitted: Function, follow: Function, refresh: Function })
const emit = defineEmits(['submitted'])
const route = useRoute(), spec = ref(null), existing = ref(''), health = ref('[]'), env = ref(''), secrets = ref(''), working = ref(false), error = ref(''), message = ref(''), suggestedVolumes = ref('')
let disposed = false
onBeforeUnmount(() => { disposed = true })
watch(() => props.state.host, () => { if (spec.value && !spec.value.host_id && props.state.host.length === 1) spec.value.host_id = props.state.host[0].id; selectHost() })
async function initialize() { try { spec.value = await opsApi.applicationTemplate(); spec.value.id = 'app-' + operationKey().replace(/[^a-zA-Z0-9]/g, '').slice(-12); spec.value.name = String(route.query.name || ''); spec.value.health.mode = 'running'; spec.value.host_id = String(route.query.host || (props.state.host.length === 1 ? props.state.host[0].id : '')); spec.value.image = String(route.query.image || ''); selectHost();
  spec.value.ports = String(route.query.ports || '').split(',').filter(Boolean).map(p => { const [port, protocol = 'tcp'] = p.split('/'); return { host: Number(port) >= 1024 ? Number(port) : null, container: Number(port), protocol } });
  const test = JSON.parse(String(route.query.health || '[]')); if (test[0] === 'CMD' || test[0] === 'CMD-SHELL') { spec.value.health.mode = 'command'; spec.value.health.command = test[0] === 'CMD' ? test.slice(1) : ['sh', '-c', test[1]] }
  health.value = JSON.stringify(spec.value.health.command); suggestedVolumes.value = String(route.query.volumes || ''); if (route.query.app && props.state.application.some(a => a.id === route.query.app)) { existing.value = String(route.query.app); selectApplication() }
} catch (e) { error.value = e.message } }
watch(() => route.query, initialize, { immediate: true })
watch(() => props.state.application, apps => { if (route.query.app && !existing.value && apps.some(a => a.id === route.query.app) && spec.value) { existing.value = String(route.query.app); selectApplication() } })
function selectHost() { if (spec.value) spec.value.environment = props.state.host.find(h => h.id === spec.value.host_id)?.environment || '' }
function selectApplication() { const app = props.state.application.find(a => a.id === existing.value); if (!app) return initialize(); const image = spec.value.image; spec.value = JSON.parse(JSON.stringify(app)); if (image && (!image.startsWith('sha256:') || spec.value.host_id === route.query.host)) spec.value.image = image; spec.value.health.mode ||= 'command'; health.value = JSON.stringify(spec.value.health.command); env.value = formatPairs(spec.value.environment_variables); secrets.value = formatPairs(spec.value.secret_refs) }
function addVolume() { spec.value.mounts.push({ type: 'volume', source: '', target: '', read_only: false }); if (spec.value.data_kind === 'stateless') spec.value.data_kind = 'volume' }
async function submit() {
  working.value = true; error.value = ''; message.value = ''
  try {
    selectHost(); const value = JSON.parse(JSON.stringify(spec.value)); value.environment_variables = parsePairs(env.value); value.secret_refs = parsePairs(secrets.value); value.health.command = value.health.mode === 'running' ? [] : JSON.parse(health.value)
    if (suggestedVolumes.value && !value.mounts.length && !window.confirm('镜像声明了数据目录，但尚未配置持久卷。确认以当前配置部署？')) return
    if (!window.confirm(`确认${existing.value ? '更新' : '部署'}「${value.name}」？\n目标：${value.host_id} / ${value.environment}\n镜像：${value.image}\n${value.data_kind !== 'stateless' ? '请确认已备份并验证新旧版本数据兼容，平台不会回退数据库结构。' : ''}`)) return
    const saved = await opsApi.save('application', value); spec.value = saved; existing.value = saved.id
    let task = await opsApi.operate({ action: 'validate', resource_id: saved.id, parameters: {}, idempotency_key: operationKey() }); props.follow(task); message.value = '正在校验镜像和部署参数…'
    const deadline = Date.now() + 1200000
    while (['QUEUED', 'RUNNING'].includes(task.status)) { if (disposed) return; if (Date.now() > deadline) throw new Error('校验仍未结束，请在操作记录中检查；尚未执行部署'); await new Promise(resolve => setTimeout(resolve, 1000)); task = await opsApi.task(task.id) }
    if (disposed) return
    if (task.status !== 'SUCCEEDED') throw new Error(task.message || '部署前校验失败')
    props.follow(await opsApi.operate({ action: 'deploy', resource_id: saved.id, parameters: { data_compatible: true }, confirmation: `deploy:${saved.id}:${saved.environment}`, idempotency_key: operationKey() })); message.value = '正在启动容器。'; emit('submitted'); await props.refresh()
  } catch (e) { error.value = e.message } finally { working.value = false }
}
</script>
