<template>
  <div class="page-container ops-workspace runtime-console">
    <div class="page-header"><div><h1>{{ imagesPage ? '镜像管理' : '容器管理' }}</h1><p>{{ imagesPage ? '导入镜像，点击启动。' : '查看 Runtime OS 容器状态，管理启动、停止和自动拉起。' }}</p></div><div class="ops-actions"><label v-if="state.host.length > 1" class="runtime-host">运行主机<select v-model="hostId"><option v-for="host in state.host" :key="host.id" :value="host.id">{{ host.name || host.id }}</option></select></label><button class="btn btn-secondary" :disabled="loading" @click="refresh">刷新</button></div></div>
    <p v-if="error" role="alert" class="ops-error">{{ error }}</p>
    <p v-if="!loading && !state.host.length" class="ops-empty">尚未连接 Runtime OS 主机，请先完成安装包中的主机连接配置。</p>
    <ImageLibraryPanel v-if="imagesPage" :state="scopedState" :active-task="activeTask" :busy="busy" :permitted="permitted" :follow="follow" :operate="operate" />
    <RuntimeContainersPanel v-else :monitoring="monitoring" :state="state" :host-id="hostId" :busy="busy" :permitted="permitted" :operate="operate" />
    <section v-if="activeTask && activeTask.host_id === hostId && !['images', 'logs', 'stack_logs'].includes(activeTask.action)" class="ops-panel runtime-task" aria-live="polite"><div class="runtime-summary"><strong>{{ actionLabel(activeTask.action) }}</strong><span>{{ statusLabel(activeTask.status) }}</span></div><progress :value="activeTask.progress" max="100"></progress><p>{{ messageLabel(activeTask.message) }} · {{ activeTask.progress }}%</p></section>
    <div v-if="showStart" class="runtime-overlay" @click.self="closeStart"><div role="dialog" aria-modal="true" aria-label="启动镜像" class="runtime-dialog"><button class="runtime-close" aria-label="关闭启动窗口" @click="closeStart">×</button><QuickDeploymentPanel :state="scopedState" :busy="busy" :permitted="permitted" :follow="follow" :refresh="refresh" @submitted="router.push('/ops/containers')" /></div></div>
  </div>
</template>
<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { store } from '../store'
import { useOpsPlatform } from '../ops/useOpsPlatform'
import { preferredHost } from '../ops/runtimeConsole'
import { actionLabel, statusLabel, messageLabel } from '../ops/presentation'
import ImageLibraryPanel from '../components/ImageLibraryPanel.vue'
import QuickDeploymentPanel from '../components/QuickDeploymentPanel.vue'
import RuntimeContainersPanel from '../components/RuntimeContainersPanel.vue'
const route = useRoute(), router = useRouter()
const { state, monitoring, activeTask, busy, loading, error, refresh, follow, operate, permitted } = useOpsPlatform(route, {consoleOnly: true})
const storageKey = 'runtime-console.host.' + (store.user?.user_id || '')
const hostId = ref('')
watch([() => state.host, () => route.query], ([hosts]) => {
  const selected = String(route.query.host || state.application.find(app => app.id === route.query.app || app.id === route.query.target)?.host_id || '')
  hostId.value = preferredHost(hosts, monitoring.value.applications, selected || hostId.value || localStorage.getItem(storageKey) || '')
}, { immediate: true })
watch(hostId, host => { if (host) localStorage.setItem(storageKey, host) })
const scopedState = computed(() => ({...state, host: state.host.filter(host => host.id === hostId.value), application: state.application.filter(app => app.host_id === hostId.value)}))
const imagesPage = computed(() => route.params.section === 'images')
const showStart = computed(() => imagesPage.value && Boolean(route.query.image || route.query.app))
function closeStart() { router.replace({path: '/ops/images'}) }
</script>
<style src="../assets/styles/container-ops.css"></style>
<style scoped>
.page-header h1{font-size:24px;margin:0 0 8px}.page-header p{color:var(--text-muted);font-size:13px}.runtime-host{display:flex;align-items:center;gap:8px;font-size:12px}.runtime-host select{max-width:220px;padding:8px;border:1px solid var(--border-color);border-radius:6px}.runtime-overlay{position:fixed;inset:0;background:#0f172a66;z-index:200;display:flex;align-items:center;justify-content:center;padding:20px}.runtime-dialog{position:relative;background:var(--bg-color);border-radius:12px;width:min(900px,100%);max-height:90vh;overflow:auto;padding:24px}.runtime-close{position:absolute;right:12px;top:8px;font-size:24px;border:0;background:none;z-index:1;cursor:pointer}.runtime-task p{font-size:12px;color:var(--text-muted)}
@media(max-width:600px){.page-header{flex-wrap:wrap;gap:12px}.runtime-dialog{padding:16px}.runtime-overlay{padding:8px}.runtime-host select{max-width:180px}}
</style>
<style>
.runtime-summary{display:flex;align-items:center;gap:24px;flex-wrap:wrap}.runtime-summary .btn{margin-left:auto}.runtime-hint{font-size:12px;color:var(--text-muted);margin:20px 0}.runtime-buttons{white-space:nowrap}.runtime-buttons a{font-size:12px;margin-left:8px}.runtime-alert{margin-top:16px;padding:10px;background:#fff7ed;color:#b45309;border-radius:6px;font-size:12px}.runtime-log{margin-top:20px}.runtime-log h3{flex:1;font-size:14px}.runtime-console .ops-panel h2{font-size:17px}.runtime-console code{font-size:11px}
</style>
