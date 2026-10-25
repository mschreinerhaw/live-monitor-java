<template>
  <section class="ops-panel">
    <h2>上传镜像包</h2><p>选择服务器，上传 docker save 导出的 .tar / .tar.gz / .tgz 文件。平台校验后自动解压导入，无需登录服务器。</p>
    <form class="ops-form" @submit.prevent="upload">
      <div class="ops-grid"><label>目标服务器<select v-model="hostId" required @change="images = []"><option value="">请选择</option><option v-for="h in state.host" :key="h.id" :value="h.id">{{ h.name || h.id }} · {{ h.environment }}</option></select></label>
      <label>镜像包<input type="file" accept=".tar,.tar.gz,.tgz" required @change="file = $event.target.files[0]"></label></div>
      <div class="ops-actions"><button class="btn btn-primary" :disabled="uploading || busy || !permitted(hostId, 'import_image')">{{ uploading ? '正在上传，请勿关闭页面…' : '上传并导入' }}</button><button type="button" :disabled="busy || uploading || !permitted(hostId, 'images')" @click="list">刷新镜像列表</button></div>
      <p v-if="message" role="status">{{ message }}</p><p v-if="error" class="ops-error" role="alert">{{ error }}</p>
      <p v-if="!state.host.length">首次使用？<router-link to="/ops/servers">先连接一台服务器</router-link>。</p>
    </form>
  </section>
  <section class="ops-panel"><h2>服务器镜像</h2><p>选择镜像开始部署；更新已有应用时可选择应用并保留原配置。离线镜像绑定当前服务器和环境。</p>
    <div class="ops-table-wrap"><table><thead><tr><th>镜像 / 版本</th><th>不可变标识</th><th>操作</th></tr></thead><tbody><tr v-for="image in images" :key="image.image || image.ID"><td>{{ image.tags?.join(', ') || image.Repository || '未命名镜像' }}</td><td><code>{{ image.image || image.ID }}</code></td><td><button class="btn btn-primary" :disabled="!image.image || !permitted(hostId, 'deploy')" @click="select(image)">部署 / 更新应用</button></td></tr></tbody></table></div>
    <p v-if="!images.length" class="ops-empty">选择服务器后刷新列表，或上传一个镜像包。导入进度显示在下方任务记录中。</p>
  </section>
</template>
<script setup>
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { opsApi } from '../api/ops'
import { operationKey } from '../ops/model'
const props = defineProps({ state: Object, activeTask: Object, busy: Boolean, permitted: Function, follow: Function, operate: Function })
const router = useRouter(), hostId = ref(''), file = ref(null), images = ref([]), uploading = ref(false), error = ref(''), message = ref('')
watch(() => props.state.host, hosts => { if (!hostId.value && hosts.length === 1) hostId.value = hosts[0].id }, { immediate: true })
watch(() => props.activeTask, task => {
  if (task?.host_id !== hostId.value || !['images', 'import_image'].includes(task.action)) return
  if (task.status === 'SUCCEEDED') { images.value = JSON.parse(task.result_json || '{}').images || []; message.value = task.action === 'import_image' ? '导入完成，请从下方镜像列表部署。' : '镜像列表已刷新。' }
  else if (['FAILED', 'INTERRUPTED'].includes(task.status)) error.value = task.message
})
async function upload() {
  const host = props.state.host.find(h => h.id === hostId.value)
  if (!host || !file.value || !window.confirm(`将镜像包导入「${host.name || host.id}」（${host.environment}）？此步骤不会启动或替换应用。`)) return
  uploading.value = true; error.value = ''; message.value = ''
  try { const body = new FormData(); body.append('file', file.value); body.append('host_id', host.id); body.append('confirmation', `import_image:${host.id}:${host.environment}`); body.append('idempotency_key', operationKey()); props.follow(await opsApi.importImage(body)); message.value = '上传完成，后台正在校验和导入。' }
  catch (e) { error.value = e.message } finally { uploading.value = false }
}
function list() { return props.operate('images', props.state.host.find(h => h.id === hostId.value)) }
function select(image) { router.push({ path: '/ops/deployments', query: { host: hostId.value, image: image.image, ports: (image.ports || []).join(','), health: JSON.stringify(image.healthcheck || []), volumes: (image.volumes || []).join(',') } }) }
</script>
