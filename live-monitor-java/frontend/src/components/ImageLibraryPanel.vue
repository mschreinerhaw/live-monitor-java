<template>
  <section class="ops-panel">
    <h2>导入镜像</h2><p>选择 .tar / .tar.gz / .tgz 镜像包，自动解压并导入。</p>
    <form class="ops-form" @submit.prevent="upload">
      <div class="ops-grid"><label v-if="state.host.length > 1">目标服务器<select v-model="hostId" required :disabled="uploading || busy"><option value="">请选择</option><option v-for="h in state.host" :key="h.id" :value="h.id">{{ h.name || h.id }}</option></select></label>
      <label>镜像包<input type="file" accept=".tar,.tar.gz,.tgz" required @change="file = $event.target.files[0]"></label></div>
      <div class="ops-actions"><button class="btn btn-primary" :disabled="uploading || busy || !permitted(hostId, 'import_image')">{{ uploading ? '正在上传，请勿关闭页面…' : '上传并导入' }}</button><button type="button" :disabled="busy || uploading || !permitted(hostId, 'images')" @click="list">刷新镜像列表</button></div>
      <p v-if="message" role="status">{{ message }}</p><p v-if="error" class="ops-error" role="alert">{{ error }}</p>
    </form>
  </section>
  <section class="ops-panel"><h2>镜像列表</h2><p v-if="route.query.target">选择一个镜像更新容器，保留原来的端口和数据配置。<router-link to="/ops/images">取消更新</router-link></p>
    <div class="ops-table-wrap"><table><thead><tr><th>镜像 / 版本</th><th>镜像 ID</th><th>操作</th></tr></thead><tbody><tr v-for="image in images" :key="image.image || image.ID"><td>{{ image.tags?.join(', ') || image.Repository || '未命名镜像' }}</td><td><code :title="image.image || image.ID">{{ (image.image || image.ID || '').replace(/^sha256:/, '').slice(-12) }}</code></td><td><button class="btn btn-primary" :disabled="!image.image || !permitted(hostId, 'deploy')" @click="select(image)">{{ route.query.target ? '更新容器' : '启动' }}</button></td></tr></tbody></table></div>
    <p v-if="!images.length" class="ops-empty">暂无镜像，导入镜像包后即可启动容器。</p>
  </section>
</template>
<script setup>
import { ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { opsApi } from '../api/ops'
import { operationKey } from '../ops/model'
const props = defineProps({ state: Object, activeTask: Object, busy: Boolean, permitted: Function, follow: Function, operate: Function })
const router = useRouter(), route = useRoute(), hostId = ref(''), file = ref(null), images = ref([]), uploading = ref(false), error = ref(''), message = ref('')
watch(() => props.state.host, hosts => { if (!hosts.some(host => host.id === hostId.value)) hostId.value = hosts[0]?.id || '' }, { immediate: true })
watch(hostId, host => { images.value = []; if (host && props.permitted(host, 'images')) list() }, { immediate: true })
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
function select(image) { router.push({ path: '/ops/images', query: { host: hostId.value, image: image.image, name: image.tags?.[0] || image.Repository || '', ...(route.query.target ? {app: String(route.query.target)} : {}), ports: (image.ports || []).join(','), health: JSON.stringify(image.healthcheck || []), volumes: (image.volumes || []).join(',') } }) }
</script>
