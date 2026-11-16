<template>
  <div :class="['external-projects-page', embedded ? 'embedded-projects' : 'page-container']">
    <div v-if="!embedded" class="page-header">
      <div class="page-header-title">
        <h1>外部项目与消息转发</h1>
        <p>按关键字匹配项目，将外部消息发送至项目绑定的告警组。</p>
      </div>
      <div class="page-actions">
        <router-link to="/external-requests" class="btn btn-ghost">外部消息请求</router-link
        ><button class="btn btn-ghost" @click="integration?.reveal()">接入说明</button
        ><button class="btn btn-ghost" :disabled="loading" @click="load">刷新</button
        ><button class="btn btn-primary" @click="edit()">新建外部项目</button>
      </div>
    </div>
    <p v-if="error" role="alert" class="inline-error">{{ error }}</p>
    <section class="card">
      <div class="card-header">
        <div>
          <h2 class="card-title">
            项目列表 <span class="badge badge-info">{{ projects.length }}</span>
          </h2>
          <p class="form-hint">
            项目可指定专属联系人，也可沿用告警组默认联系人；告警组提供通知渠道。
          </p>
        </div>
        <div class="toolbar">
          <button v-if="embedded" class="btn btn-primary btn-sm" @click="edit()">
            新建项目告警配置</button
          ><span :class="['badge', relayEnabled ? 'badge-up' : 'badge-unknown']">{{
            relayEnabled === null
              ? '正在读取转发状态'
              : relayEnabled
              ? '消息转发已启用'
              : '消息转发已停用'
          }}</span
          ><button
            class="btn btn-ghost btn-sm"
            :disabled="relayEnabled === null || changing"
            @click="toggleRelay"
          >
            {{ changing ? '正在更新…' : relayEnabled ? '停用消息转发' : '启用消息转发' }}
          </button>
        </div>
      </div>
      <label class="filter-label project-search"
        >搜索项目<input v-model="search" class="form-control" placeholder="项目名称 / 匹配关键字"
      /></label>
      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>项目名称</th>
              <th>匹配关键字</th>
              <th>告警组</th>
              <th v-if="embedded">告警联系人</th>
              <th>说明</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!filtered.length">
              <td :colspan="embedded ? 7 : 6">{{ loading ? '加载中…' : '暂无外部项目' }}</td>
            </tr>
            <tr v-for="project in projectPager.items" :key="project.id">
              <td>{{ project.project_name }}</td>
              <td>{{ project.match_keyword }}</td>
              <td>{{ (project.alert_group_names || []).join('、') }}</td>
              <td v-if="embedded" class="project-recipient-cell">
                <ul>
                  <li v-for="r in recipients(project)" :key="r.id">
                    {{ r.value }} <small>{{ r.type }}</small>
                  </li>
                </ul>
                <span v-if="!recipients(project).length" class="text-muted">尚未配置接收地址</span>
              </td>
              <td>{{ project.description }}</td>
              <td>{{ project.enabled ? '启用' : '停用' }}</td>
              <td>
                <button class="btn btn-ghost btn-sm" @click="integration?.reveal(project.id)">
                  接入示例</button
                ><button class="btn btn-ghost btn-sm" @click="edit(project)">编辑</button
                ><button class="btn btn-danger btn-sm" @click="remove(project)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <PaginationControls
        v-model:page="projectPager.page"
        v-model:page-size="projectPager.pageSize"
        :total="projectPager.total"
        label="外部项目"
      />
    </section>
    <section v-if="editing" ref="editor" class="card project-editor">
      <div class="card-header">
        <h2>{{ form.id ? '编辑外部项目' : '新建外部项目' }}</h2>
      </div>
      <form @submit.prevent="save">
        <label class="form-group"
          >项目名称<input v-model="form.project_name" required maxlength="255" class="form-control"
        /></label>
        <label class="form-group"
          >匹配关键字<input
            v-model="form.match_keyword"
            maxlength="255"
            class="form-control"
            placeholder="留空时使用项目名称"
        /></label>
        <label class="form-group"
          >说明<textarea
            v-model="form.description"
            maxlength="1000"
            class="form-control"
          ></textarea>
        </label>
        <fieldset class="project-groups">
          <legend>告警组（至少选择一个）</legend>
          <label v-for="group in groups" :key="group.id"
            ><input v-model="form.alert_group_ids" type="checkbox" :value="group.id" />{{
              group.group_name
            }}{{ group.enabled ? '' : '（已禁用）' }}</label
          >
        </fieldset>
        <ContactSelector v-model="form.contact_ids" label="项目专属联系人" hint="选择后优先发送给项目联系人；未选择时沿用所绑定告警组的默认联系人。渠道仍由告警组决定。" />
      <label class="project-enabled"
          ><input v-model="form.enabled" type="checkbox" />启用项目</label
        >
        <div class="page-actions">
          <button class="btn btn-primary" :disabled="saving">
            {{ saving ? '保存中…' : '保存项目' }}</button
          ><button type="button" class="btn btn-ghost" @click="editing = false">取消</button>
        </div>
      </form>
    </section>
    <ExternalIntegrationGuide ref="integration" :projects="projects" />
  </div>
</template>
<script setup>
import { ref, reactive, computed, nextTick, onMounted } from 'vue'
import { api } from '../api'
import { store } from '../store'
import PaginationControls from '../components/PaginationControls.vue'
import { usePagination } from '../composables/usePagination'
import ExternalIntegrationGuide from '../components/ExternalIntegrationGuide.vue'
import { projectRecipients } from '../utils/alertRecipients'
import ContactSelector from '../components/ContactSelector.vue'
const props = defineProps({ embedded: Boolean, channels: { type: Array, default: () => [] } })
const emit = defineEmits(['saved'])
const addressBook=ref([])
const recipients = (project) => projectRecipients(project, groups.value, props.channels, addressBook.value)
const projects = ref([]),
  groups = ref([]),
  search = ref(''),
  error = ref(''),
  editing = ref(false),
  saving = ref(false),
  loading = ref(false),
  changing = ref(false),
  relayEnabled = ref(null)
const blank = () => ({
  id: null,
  project_name: '',
  match_keyword: '',
  description: '',
  alert_group_ids: [],
  contact_ids: [],
  enabled: true,
})
const form = reactive(blank())
const editor = ref(null)
const integration = ref(null)
const filtered = computed(() =>
  projects.value.filter((p) =>
    `${p.project_name} ${p.match_keyword}`.toLowerCase().includes(search.value.toLowerCase())
  )
)
const projectPager = usePagination(filtered, [search])
async function load() {
  loading.value = true
  error.value = ''
  try {
    const [items, bindings, services, contacts] = await Promise.all([
      api.externalProjects(),
      api.listAlertGroups(true),
      api.listServices(true),
      api.listAlertContacts(),
    ])
    addressBook.value=contacts
    projects.value = items
    groups.value = bindings
    relayEnabled.value =
      services.find((s) => s.service_type === 'external_message')?.enabled ?? false
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
async function edit(project) {
  Object.assign(form, blank(), project || {}, {
    alert_group_ids: [...(project?.alert_group_ids || [])],
    contact_ids: [...(project?.contact_ids || [])],
  })
  editing.value = true
  await nextTick()
  editor.value?.scrollIntoView({ block: 'start', behavior: 'smooth' })
  editor.value?.querySelector('input')?.focus({ preventScroll: true })
}
async function save() {
  if (!form.alert_group_ids.length) {
    error.value = '请至少选择一个告警组'
    return
  }
  saving.value = true
  error.value = ''
  try {
    if (form.id) await api.updateExternalProject(form.id, form)
    else await api.createExternalProject(form)
    editing.value = false
    await load()
    store.showToast('外部项目已保存', 'success')
    emit('saved')
  } catch (e) {
    error.value = e.message
  } finally {
    saving.value = false
  }
}
async function remove(project) {
  if (!window.confirm(`删除外部项目“${project.project_name}”？`)) return
  try {
    await api.deleteExternalProject(project.id)
    await load()
  } catch (e) {
    error.value = e.message
  }
}
async function toggleRelay() {
  changing.value = true
  error.value = ''
  try {
    const result = await api.externalMessageStatus(!relayEnabled.value)
    relayEnabled.value = result.enabled
  } catch (e) {
    error.value = e.message
  } finally {
    changing.value = false
  }
}
onMounted(load)
</script>
<style scoped>
.project-search {
  max-width: 360px;
  margin-bottom: 16px;
}
.project-editor {
  scroll-margin-top: 76px;
}
.project-groups {
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 12px 16px;
  margin-bottom: 16px;
}
.project-groups legend {
  padding: 0 6px;
  color: #475569;
  font-size: 13px;
}
.project-groups label {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin: 4px 20px 4px 0;
}
.project-enabled {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 20px;
}
.audit-filter-row {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) minmax(140px, 200px) auto;
  gap: 12px;
  align-items: end;
  margin-bottom: 16px;
}
@media (max-width: 640px) {
  .audit-filter-row {
    grid-template-columns: 1fr;
  }
  .project-search {
    max-width: 100%;
  }
}
</style>

<style scoped>
.embedded-projects .card {
  margin-bottom: 0;
}
.project-recipient-cell {
  min-width: 200px;
  max-width: 300px;
}
.project-recipient-cell ul {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
  max-height: 130px;
  overflow: auto;
}
.project-recipient-cell li {
  font-size: 12px;
  overflow-wrap: anywhere;
}
.project-recipient-cell small {
  font-size: 10px;
  color: #7a8c9f;
}
.embedded-projects .project-groups {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.embedded-projects .project-groups label {
  padding: 9px 12px;
  border: 1px solid #e1e9f3;
  border-radius: 6px;
  margin: 0;
  background: #f8fbff;
}
.embedded-projects .project-editor form {
  max-width: 800px;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
</style>
