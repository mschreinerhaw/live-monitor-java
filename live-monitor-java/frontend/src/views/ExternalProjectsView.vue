<template>
  <div class="page-container">
    <h1>外部项目与消息转发</h1><p>按消息关键字匹配项目，再转发到项目绑定的告警组。请求和渠道结果可在下方审计。</p>
    <p v-if="error" role="alert" class="text-danger">{{ error }}</p>
    <section class="card"><div class="form-row"><button class="btn btn-primary" @click="edit()">新建外部项目</button><button class="btn btn-ghost" :disabled="loading" @click="load">刷新</button><button class="btn btn-ghost" :disabled="relayEnabled === null || changing" @click="toggleRelay">{{ relayEnabled ? '停用消息转发' : '启用消息转发' }}</button></div>
      <label>搜索项目<input v-model="search" class="form-control" placeholder="项目名称 / 匹配关键字"></label>
      <div class="table-wrap"><table class="table"><thead><tr><th>项目名称</th><th>匹配关键字</th><th>告警组</th><th>说明</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-if="!filtered.length"><td colspan="6">{{ loading ? '加载中…' : '暂无外部项目' }}</td></tr><tr v-for="project in filtered" :key="project.id"><td>{{ project.project_name }}</td><td>{{ project.match_keyword }}</td><td>{{ (project.alert_group_names || []).join('、') }}</td><td>{{ project.description }}</td><td>{{ project.enabled ? '启用' : '停用' }}</td><td><button class="btn btn-ghost btn-sm" @click="edit(project)">编辑</button><button class="btn btn-ghost btn-sm" @click="remove(project)">删除</button></td></tr></tbody></table></div>
    </section>
    <section v-if="editing" class="card"><h2>{{ form.id ? '编辑外部项目' : '新建外部项目' }}</h2><form @submit.prevent="save">
      <label class="form-group">项目名称<input v-model="form.project_name" required maxlength="255" class="form-control"></label>
      <label class="form-group">匹配关键字<input v-model="form.match_keyword" maxlength="255" class="form-control" placeholder="留空时使用项目名称"></label>
      <label class="form-group">说明<textarea v-model="form.description" maxlength="1000" class="form-control"></textarea></label>
      <fieldset><legend>告警组（至少选择一个）</legend><label v-for="group in groups" :key="group.id" style="margin-right:16px"><input v-model="form.alert_group_ids" type="checkbox" :value="group.id">{{ group.group_name }}{{ group.enabled ? '' : '（已禁用）' }}</label></fieldset>
      <label><input v-model="form.enabled" type="checkbox">启用项目</label><div class="form-row"><button class="btn btn-primary" :disabled="saving">保存项目</button><button type="button" class="btn btn-ghost" @click="editing = false">取消</button></div>
    </form></section>
    <section class="card"><h2>外部消息请求审计</h2><form class="form-row" @submit.prevent="page = 1; loadAudit()"><input v-model="auditQuery" class="form-control" placeholder="搜索来源 / 消息 / 错误"><input v-model="auditStatus" class="form-control" placeholder="请求状态（可留空）"><button class="btn btn-ghost" :disabled="loadingAudit">查询审计</button></form>
      <p v-if="auditError" role="alert">{{ auditError }}</p><div class="table-wrap"><table class="table"><thead><tr><th>时间</th><th>来源</th><th>状态</th><th>消息摘要 / 渠道结果</th></tr></thead><tbody><tr v-if="!audits.length"><td colspan="4">暂无审计记录</td></tr><tr v-for="audit in audits" :key="audit.id"><td>{{ audit.created_at }}</td><td>{{ audit.client_ip }}</td><td>{{ audit.request_status || audit.status }}</td><td><details><summary>{{ audit.message_preview || audit.message_summary || audit.request_message || audit.error_message || '查看请求与渠道结果' }}</summary><pre style="white-space:pre-wrap">{{ JSON.stringify(audit, null, 2) }}</pre></details></td></tr></tbody></table></div>
      <button class="btn btn-ghost" :disabled="page <= 1 || loadingAudit" @click="page--; loadAudit()">上一页</button><span>第 {{ page }} 页 / {{ total }} 条</span><button class="btn btn-ghost" :disabled="page * 20 >= total || loadingAudit" @click="page++; loadAudit()">下一页</button>
    </section>
  </div>
</template>
<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { api } from '../api'
import { store } from '../store'
const projects = ref([]), groups = ref([]), search = ref(''), error = ref(''), editing = ref(false), saving = ref(false), loading = ref(false), changing = ref(false), relayEnabled = ref(null)
const audits = ref([]), auditQuery = ref(''), auditStatus = ref(''), auditError = ref(''), loadingAudit = ref(false), page = ref(1), total = ref(0)
const blank = () => ({ id: null, project_name: '', match_keyword: '', description: '', alert_group_ids: [], enabled: true })
const form = reactive(blank())
const filtered = computed(() => projects.value.filter(p => `${p.project_name} ${p.match_keyword}`.toLowerCase().includes(search.value.toLowerCase())))
async function load() {
  loading.value = true; error.value = ''
  try {
    const [items, bindings, services] = await Promise.all([api.externalProjects(), api.listAlertGroups(true), api.listServices(true)])
    projects.value = items; groups.value = bindings; relayEnabled.value = services.find(s => s.service_type === 'external_message')?.enabled ?? false
  } catch (e) { error.value = e.message } finally { loading.value = false }
}
function edit(project) { Object.assign(form, blank(), project || {}, { alert_group_ids: [...(project?.alert_group_ids || [])] }); editing.value = true }
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
