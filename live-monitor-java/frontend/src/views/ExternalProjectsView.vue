<template>
  <div class="page-container external-projects-page">
    <div class="page-header"><div class="page-header-title"><h1>外部项目与消息转发</h1><p>按关键字匹配项目，将外部消息发送至项目绑定的告警组。</p></div><div class="page-actions"><button class="btn btn-ghost" @click="integration?.reveal()">接入说明</button><button class="btn btn-ghost" :disabled="loading" @click="load">刷新</button><button class="btn btn-primary" @click="edit()">新建外部项目</button></div></div>
    <p v-if="error" role="alert" class="inline-error">{{ error }}</p>
    <section class="card"><div class="card-header"><div><h2 class="card-title">项目列表 <span class="badge badge-info">{{ projects.length }}</span></h2><p class="form-hint">项目接收组决定消息的发送渠道。</p></div><div class="toolbar"><span :class="['badge', relayEnabled ? 'badge-up' : 'badge-unknown']">{{ relayEnabled === null ? '正在读取转发状态' : relayEnabled ? '消息转发已启用' : '消息转发已停用' }}</span><button class="btn btn-ghost btn-sm" :disabled="relayEnabled === null || changing" @click="toggleRelay">{{ changing ? '正在更新…' : relayEnabled ? '停用消息转发' : '启用消息转发' }}</button></div></div>
      <label class="filter-label project-search">搜索项目<input v-model="search" class="form-control" placeholder="项目名称 / 匹配关键字"></label>
      <div class="table-wrap"><table class="table"><thead><tr><th>项目名称</th><th>匹配关键字</th><th>告警组</th><th>说明</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-if="!filtered.length"><td colspan="6">{{ loading ? '加载中…' : '暂无外部项目' }}</td></tr><tr v-for="project in filtered" :key="project.id"><td>{{ project.project_name }}</td><td>{{ project.match_keyword }}</td><td>{{ (project.alert_group_names || []).join('、') }}</td><td>{{ project.description }}</td><td>{{ project.enabled ? '启用' : '停用' }}</td><td><button class="btn btn-ghost btn-sm" @click="integration?.reveal(project.id)">接入示例</button><button class="btn btn-ghost btn-sm" @click="edit(project)">编辑</button><button class="btn btn-danger btn-sm" @click="remove(project)">删除</button></td></tr></tbody></table></div>
    </section>
    <section v-if="editing" ref="editor" class="card project-editor"><div class="card-header"><h2>{{ form.id ? '编辑外部项目' : '新建外部项目' }}</h2></div><form @submit.prevent="save">
      <label class="form-group">项目名称<input v-model="form.project_name" required maxlength="255" class="form-control"></label>
      <label class="form-group">匹配关键字<input v-model="form.match_keyword" maxlength="255" class="form-control" placeholder="留空时使用项目名称"></label>
      <label class="form-group">说明<textarea v-model="form.description" maxlength="1000" class="form-control"></textarea></label>
      <fieldset class="project-groups"><legend>告警组（至少选择一个）</legend><label v-for="group in groups" :key="group.id"><input v-model="form.alert_group_ids" type="checkbox" :value="group.id">{{ group.group_name }}{{ group.enabled ? '' : '（已禁用）' }}</label></fieldset>
      <label class="project-enabled"><input v-model="form.enabled" type="checkbox">启用项目</label><div class="page-actions"><button class="btn btn-primary" :disabled="saving">{{ saving ? '保存中…' : '保存项目' }}</button><button type="button" class="btn btn-ghost" @click="editing = false">取消</button></div>
    </form></section>
    <ExternalIntegrationGuide ref="integration" :projects="projects" />
    <section class="card"><div class="card-header"><div><h2 class="card-title">外部消息请求审计</h2><p class="form-hint">查看请求处理状态及各渠道的发送结果。</p></div></div><form class="audit-filter-row" @submit.prevent="page = 1; loadAudit()"><label class="filter-label">搜索请求<input v-model="auditQuery" class="form-control" placeholder="来源 / 消息 / 错误"></label><label class="filter-label">请求状态<select v-model="auditStatus" class="form-control"><option value="">全部状态</option><option value="SUCCESS">处理成功</option><option value="FAILED">处理失败</option><option value="REJECTED">请求被拒绝</option><option value="CONFIG_ENABLED">转发已启用</option><option value="CONFIG_DISABLED">转发已停用</option></select></label><button class="btn btn-primary" :disabled="loadingAudit">查询审计</button></form>
      <p v-if="auditError" role="alert">{{ auditError }}</p><div class="table-wrap"><table class="table"><thead><tr><th>时间</th><th>来源</th><th>状态</th><th>消息摘要 / 渠道结果</th></tr></thead><tbody><tr v-if="!audits.length"><td colspan="4">暂无审计记录</td></tr><tr v-for="audit in audits" :key="audit.id"><td>{{ audit.created_at }}</td><td>{{ audit.client_ip }}</td><td><span :class="['badge', requestTone(audit.request_status || audit.status)]">{{ requestLabel(audit.request_status || audit.status) }}</span></td><td><details><summary>{{ audit.message_preview || audit.message_summary || audit.request_message || audit.error_message || '查看请求与渠道结果' }}</summary><pre style="white-space:pre-wrap">{{ JSON.stringify(audit, null, 2) }}</pre></details></td></tr></tbody></table></div>
      <div class="pagination-bar"><span class="text-muted">第 {{ page }} 页 · 共 {{ total }} 条</span><div class="page-actions"><button class="btn btn-ghost" :disabled="page <= 1 || loadingAudit" @click="page--; loadAudit()">上一页</button><button class="btn btn-ghost" :disabled="page * 20 >= total || loadingAudit" @click="page++; loadAudit()">下一页</button></div></div>
    </section>
  </div>
</template>
<script setup>
import { ref, reactive, computed, nextTick, onMounted } from 'vue'
import { api } from '../api'
import { store } from '../store'
import ExternalIntegrationGuide from '../components/ExternalIntegrationGuide.vue'
const projects = ref([]), groups = ref([]), search = ref(''), error = ref(''), editing = ref(false), saving = ref(false), loading = ref(false), changing = ref(false), relayEnabled = ref(null)
const audits = ref([]), auditQuery = ref(''), auditStatus = ref(''), auditError = ref(''), loadingAudit = ref(false), page = ref(1), total = ref(0)
const blank = () => ({ id: null, project_name: '', match_keyword: '', description: '', alert_group_ids: [], enabled: true })
const form = reactive(blank())
const editor = ref(null)
const integration = ref(null)
const requestLabel = status => ({ SUCCESS: '处理成功', FAILED: '处理失败', REJECTED: '请求被拒绝', CONFIG_ENABLED: '转发已启用', CONFIG_DISABLED: '转发已停用' }[status] || status || '未知状态')
const requestTone = status => status === 'SUCCESS' ? 'badge-up' : ['FAILED','REJECTED'].includes(status) ? 'badge-down' : 'badge-info'
const filtered = computed(() => projects.value.filter(p => `${p.project_name} ${p.match_keyword}`.toLowerCase().includes(search.value.toLowerCase())))
async function load() {
  loading.value = true; error.value = ''
  try {
    const [items, bindings, services] = await Promise.all([api.externalProjects(), api.listAlertGroups(true), api.listServices(true)])
    projects.value = items; groups.value = bindings; relayEnabled.value = services.find(s => s.service_type === 'external_message')?.enabled ?? false
  } catch (e) { error.value = e.message } finally { loading.value = false }
}
async function edit(project) { Object.assign(form, blank(), project || {}, { alert_group_ids: [...(project?.alert_group_ids || [])] }); editing.value = true; await nextTick(); editor.value?.scrollIntoView({ block: 'start', behavior: 'smooth' }); editor.value?.querySelector('input')?.focus({ preventScroll: true }) }
async function save() {
  if (!form.alert_group_ids.length) { error.value = '请至少选择一个告警组'; return }
  saving.value = true; error.value = ''
  try { if (form.id) await api.updateExternalProject(form.id, form); else await api.createExternalProject(form); editing.value = false; await load(); store.showToast('外部项目已保存', 'success') }
  catch (e) { error.value = e.message } finally { saving.value = false }
}
async function remove(project) {
  if (!window.confirm(`删除外部项目“${project.project_name}”？`)) return
  try { await api.deleteExternalProject(project.id); await load() } catch (e) { error.value = e.message }
}
async function toggleRelay() {
  changing.value = true; error.value = ''
  try { const result = await api.externalMessageStatus(!relayEnabled.value); relayEnabled.value = result.enabled }
  catch (e) { error.value = e.message } finally { changing.value = false }
}
async function loadAudit() {
  loadingAudit.value = true; auditError.value = ''
  try { const result = await api.externalAudit({ page: page.value, page_size: 20, query: auditQuery.value, status: auditStatus.value }); audits.value = result.items || []; total.value = result.total || 0 }
  catch (e) { auditError.value = e.message } finally { loadingAudit.value = false }
}
onMounted(() => { load(); loadAudit() })
</script>
<style scoped>
.project-search { max-width: 360px; margin-bottom: 16px; }
.project-editor { scroll-margin-top: 76px; }
.project-groups { border: 1px solid var(--border-color); border-radius: 8px; padding: 12px 16px; margin-bottom: 16px; }
.project-groups legend { padding: 0 6px; color: #475569; font-size: 13px; }
.project-groups label { display: inline-flex; align-items: center; gap: 8px; margin: 4px 20px 4px 0; }
.project-enabled { display: flex; align-items: center; gap: 8px; margin-bottom: 20px; }
.audit-filter-row { display: grid; grid-template-columns: minmax(180px, 1fr) minmax(140px, 200px) auto; gap: 12px; align-items: end; margin-bottom: 16px; }
@media(max-width: 640px) { .audit-filter-row { grid-template-columns: 1fr; } .project-search { max-width: 100%; } }
</style>
