<template>
  <section class="card contacts-book">
    <header class="card-header">
      <div>
        <h2 class="card-title">
          联系人通讯录 <span class="badge badge-info">{{ contacts.length }}</span>
        </h2>
        <p>统一维护联系方式，在项目或告警组中勾选联系人；修改后后续告警自动使用新地址。</p>
      </div>
      <div class="book-actions">
        <button class="btn btn-ghost btn-sm" :disabled="loading" @click="load">刷新通讯录</button
        ><button class="btn btn-primary btn-sm" @click="edit()">新增联系人</button>
      </div>
    </header>
    <p v-if="error" class="book-error" role="alert">{{ error }}</p>
    <label class="book-search"
      >搜索通讯录<input
        v-model="search"
        type="search"
        class="form-control"
        placeholder="姓名、邮箱、手机号或备注"
    /></label>
    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>姓名</th>
            <th>邮箱</th>
            <th>手机号</th>
            <th>企微成员</th>
            <th>备注</th>
            <th>状态</th>
            <th>引用数</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="contact in pager.items" :key="contact.id">
            <td>
              <strong>{{ contact.name }}</strong>
            </td>
            <td>{{ contact.email || '—' }}</td>
            <td>{{ contact.mobile || '—' }}</td>
            <td>{{ contact.wecom_user || '—' }}</td>
            <td>{{ contact.description || '—' }}</td>
            <td>
              <span :class="['badge', contact.enabled ? 'badge-up' : 'badge-unknown']">{{
                contact.enabled ? '启用' : '停用'
              }}</span>
            </td>
            <td>{{ contact.binding_count || 0 }}</td>
            <td>
              <div class="book-actions">
                <button class="btn btn-ghost btn-sm" @click="edit(contact)">编辑联系人</button
                ><button
                  class="btn btn-danger btn-sm"
                  :disabled="!!contact.binding_count"
                  :title="
                    contact.binding_count
                      ? '已被引用，可编辑后停用或先解除绑定'
                      : '删除未引用联系人'
                  "
                  @click="remove(contact)"
                >
                  删除
                </button>
              </div>
            </td>
          </tr>
          <tr v-if="!pager.total">
            <td colspan="8">
              {{ loading ? '正在加载通讯录…' : '暂无联系人，请先新增联系人，再到项目或告警组选择' }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <PaginationControls
      v-model:page="pager.page"
      v-model:page-size="pager.pageSize"
      :total="pager.total"
      label="联系人通讯录"
    />
    <DetailDrawer
      :open="editing"
      :title="form.id ? '编辑联系人' : '新增联系人'"
      id="contact-editor-title"
      presentation="modal"
      :show-footer="false"
      close-label="关闭联系人编辑"
      @close="!saving && (editing = false)"
    >
      <form class="contact-form" @submit.prevent="save">
        <p class="contact-form-note">
          请至少填写邮箱、手机号或企业微信成员 ID 中的一项。停用后联系人会从实际投递中排除。
        </p>
        <div class="contact-form-grid">
          <label
            >姓名<input v-model="form.name" class="form-control" maxlength="120" required /></label
          ><label
            >邮箱<input
              v-model="form.email"
              type="email"
              class="form-control"
              maxlength="255" /></label
          ><label
            >手机号<input
              v-model="form.mobile"
              type="tel"
              pattern="\+?[0-9]{5,20}"
              class="form-control"
              maxlength="21" /></label
          ><label
            >企业微信成员 ID<input v-model="form.wecom_user" class="form-control" maxlength="120"
          /></label>
        </div>
        <label
          >备注<textarea
            v-model="form.description"
            class="form-control"
            maxlength="1000"
          ></textarea></label
        ><label class="contact-enabled"
          ><input v-model="form.enabled" type="checkbox" />启用联系人</label
        >
        <p v-if="saveError" class="book-error" role="alert">{{ saveError }}</p>
        <div class="book-actions form-actions">
          <button type="button" class="btn btn-ghost" :disabled="saving" @click="editing = false">
            取消</button
          ><button class="btn btn-primary" :disabled="saving">
            {{ saving ? '保存中…' : '保存联系人' }}
          </button>
        </div>
      </form>
    </DetailDrawer>
  </section>
</template>
<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { api } from '../api'
import { store } from '../store'
import { usePagination } from '../composables/usePagination'
import PaginationControls from './PaginationControls.vue'
import DetailDrawer from './DetailDrawer.vue'
const emit = defineEmits(['changed'])
const contacts = ref([]),
  search = ref(''),
  loading = ref(false),
  error = ref(''),
  editing = ref(false),
  saving = ref(false),
  saveError = ref('')
const blank = () => ({
    id: null,
    name: '',
    email: '',
    mobile: '',
    wecom_user: '',
    description: '',
    enabled: true,
  }),
  form = reactive(blank())
const filtered = computed(() =>
  contacts.value.filter((c) =>
    [c.name, c.email, c.mobile, c.description, c.wecom_user]
      .join(' ')
      .toLowerCase()
      .includes(search.value.toLowerCase())
  )
)
const pager = usePagination(filtered, [search])
async function load() {
  loading.value = true
  error.value = ''
  try {
    contacts.value = await api.listAlertContacts()
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
function edit(contact) {
  Object.assign(form, blank(), contact || {})
  form.enabled = contact ? !!contact.enabled : true
  saveError.value = ''
  editing.value = true
}
async function save() {
  if (saving.value) return
  if (![form.email, form.mobile, form.wecom_user].some((v) => String(v || '').trim())) {
    saveError.value = '请至少填写一种联系方式'
    return
  }
  saving.value = true
  saveError.value = ''
  try {
    const payload = Object.fromEntries(
      Object.keys(blank())
        .filter((k) => k !== 'id')
        .map((k) => [k, form[k]])
    )
    if (form.id) await api.updateAlertContact(form.id, payload)
    else await api.createAlertContact(payload)
    editing.value = false
    await load()
    emit('changed')
    store.showToast('联系人已保存', 'success')
  } catch (e) {
    saveError.value = e.message
  } finally {
    saving.value = false
  }
}
async function remove(contact) {
  if (!window.confirm(`删除联系人“${contact.name}”？`)) return
  try {
    await api.deleteAlertContact(contact.id)
    await load()
    emit('changed')
  } catch (e) {
    error.value = e.message
  }
}
onMounted(load)
</script>
<style scoped>
.contacts-book .card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}
.contacts-book header p {
  color: #7a8fa4;
  font-size: 12px;
  line-height: 1.7;
  margin-top: 7px;
}
.book-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.book-search {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 12px;
  color: #718399;
  max-width: 360px;
  margin-bottom: 18px;
}
.book-error {
  font-size: 12px;
  color: #b13e35;
  background: #fff2ed;
  padding: 10px 12px;
  border-radius: 5px;
  line-height: 1.6;
}
.contacts-book td {
  max-width: 230px;
  overflow-wrap: anywhere;
}
.contact-form {
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 4px;
}
.contact-form label {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 12px;
  color: #50677d;
  min-width: 0;
}
.contact-form input,
.contact-form textarea {
  box-sizing: border-box;
  width: 100%;
  min-width: 0;
}
.contact-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
}
.contact-form .contact-enabled {
  flex-direction: row;
  align-items: center;
  gap: 8px;
}
.contact-enabled input {
  width: 16px;
  height: 16px;
  margin: 0;
  accent-color: #0284c7;
}
.form-actions {
  justify-content: flex-end;
  padding-top: 14px;
  border-top: 1px solid #e2ebf4;
}
.contact-form-note {
  font-size: 12px;
  color: #7b8da1;
  line-height: 1.7;
  margin: 0;
}
@media (max-width: 600px) {
  .contact-form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
