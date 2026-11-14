<template>
  <div class="page-container user-admin-page">
    <div class="page-header">
      <div class="page-header-title">
        <h1>用户安全管理</h1>
        <p>统一管理当前账号、系统用户、个人密码与登录安全审计。</p>
      </div>
      <div>
        <button v-if="store.user?.admin && currentTab === 'users'" class="btn btn-primary" @click="showCreateModal = true">
          <IconSvg name="plus" size="14" />
          <span>新建用户</span>
        </button>
      </div>
    </div>

    <!-- 选项卡切换 -->
    <div class="tabs-nav" role="tablist" aria-label="用户安全管理" @keydown="handleTabKey">
      <button v-for="tab in tabs" :id="'security-tab-' + tab.id" :key="tab.id" type="button" role="tab" :aria-selected="currentTab === tab.id" :aria-controls="'security-panel-' + tab.id" :tabindex="currentTab === tab.id ? 0 : -1" class="tab-btn" :class="{ active: currentTab === tab.id }" @click="selectTab(tab.id)">{{ tab.name }}</button>
    </div>

    <section :id="'security-panel-' + currentTab" role="tabpanel" :aria-labelledby="'security-tab-' + currentTab">
    <section v-if="currentTab === 'users'" class="card current-account" aria-label="当前登录用户">
      <div class="card-header"><h2 class="card-title"><IconSvg name="users" size="16" />当前登录用户</h2><span class="badge badge-info">当前账号</span></div>
      <dl class="account-details">
        <div><dt>登录账号</dt><dd>{{ store.user?.user_id }}</dd></div>
        <div><dt>姓名 / 昵称</dt><dd>{{ store.user?.name || '-' }}</dd></div>
        <div><dt>账号角色</dt><dd>{{ store.user?.admin ? '超级管理员' : '普通用户' }}</dd></div>
      </dl>
      <button class="btn btn-ghost btn-sm" @click="selectTab('password')"><IconSvg name="lock" size="14" />个人密码</button>
    </section>
    <PersonalPasswordForm v-if="currentTab === 'password'" />
    <p v-if="currentTab === 'users' && !store.user?.admin" class="text-muted">仅管理员可查看和管理系统用户列表。</p>

    <!-- 1. 用户列表 Tab -->
    <div v-if="currentTab === 'users' && store.user?.admin" class="card" :aria-busy="usersLoading">
      <div class="card-header">
        <div class="card-title">
          <IconSvg name="users" size="16" />
          <span>系统用户列表<span v-if="!usersLoading && !usersError">（{{ users.length }}）</span></span>
        </div>
        <button class="btn btn-ghost btn-sm" :disabled="usersLoading" @click="fetchUsers">刷新列表</button>
      </div>

      <p v-if="usersError" role="alert" class="inline-error">{{ usersError }}，请刷新列表重试。</p>
      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>ID</th>
              <th>登录账号</th>
              <th>姓名 / 昵称</th>
              <th>权限等级</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="usersLoading && !users.length"><td colspan="6" class="text-center text-muted">正在加载系统用户…</td></tr>
            <tr v-else-if="!usersLoading && !usersError && !users.length"><td colspan="6" class="text-center text-muted">暂无系统用户</td></tr>
            <tr v-for="u in userPager.items" :key="u.id || u.user_id || u.userid" :class="{ 'current-user-row': isCurrentUser(u) }">
              <td>{{ u.id }}</td>
              <td><strong>{{ u.user_id || u.userid }}</strong> <span v-if="isCurrentUser(u)" class="badge badge-info">当前用户</span></td>
              <td>{{ u.name || '-' }}</td>
              <td>
                <span class="badge" :class="isAdminUser(u) ? 'badge-info' : ''">
                  {{ isAdminUser(u) ? '超级管理员' : '普通用户' }}
                </span>
              </td>
              <td>
                <span :class="['badge', u.status === 1 ? 'badge-up' : 'badge-down']">
                  {{ u.status === 1 ? '正常' : '已禁用' }}
                </span>
              </td>
              <td>
                <div style="display: flex; gap: 8px;">
                  <button
                    v-if="!isAdminUser(u)"
                    class="btn btn-ghost btn-sm"
                    @click="toggleStatus(u)"
                  >
                    {{ u.status === 1 ? '禁用' : '启用' }}
                  </button>
                  <button v-if="!isAdminUser(u)" class="btn btn-ghost btn-sm" @click="removeUser(u)">删除</button><button v-if="isCurrentUser(u)" class="btn btn-ghost btn-sm" @click="selectTab('password')">修改密码</button><button v-else class="btn btn-ghost btn-sm" @click="openResetPwdModal(u)">
                    重置密码
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
<PaginationControls v-model:page="userPager.page" v-model:page-size="userPager.pageSize" :total="userPager.total" label="用户账号" />

    </div>

    <!-- 2. 登录审计日志 Tab -->
    <div v-if="currentTab === 'logs'" class="card">
      <div class="card-header">
        <div class="card-title">
          <IconSvg name="activity" size="16" />
          <span>登录安全审计日志</span>
        </div>
        <button class="btn btn-ghost btn-sm" @click="fetchAuditLogs">刷新日志</button>
      </div>

      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>登录时间</th>
              <th>用户账号</th>
              <th>操作类型</th>
              <th>来源 IP</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!auditLogs.length">
              <td colspan="4" class="text-center text-muted" style="padding: 24px;">暂无登录审计记录</td>
            </tr>
            <tr v-for="log in auditLogs" :key="log.id || log.event_time">
              <td style="font-family: monospace; font-size: 12px;">{{ log.event_time || '-' }}</td>
              <td><strong>{{ log.user_id || log.userId }}</strong></td>
              <td>
                <span class="badge badge-info">{{ log.action || 'LOGIN' }}</span>
              </td>
              <td style="font-family: monospace;">{{ log.ip_address || log.ipAddress || '-' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <section v-if="currentTab === 'logs'" class="card"><form class="toolbar" @submit.prevent="auditPage = 1; fetchAuditLogs()"><input v-model="auditQuery" class="form-control" placeholder="搜索登录用户 / IP"><button class="btn btn-ghost">查询登录审计</button></form><PaginationControls v-model:page="auditPage" v-model:page-size="auditPageSize" :total="auditTotal" label="登录审计" @change="fetchAuditLogs" /></section>
    </section>
    <!-- 新建用户模态框 -->
    <div v-if="showCreateModal" class="modal-overlay">
      <div class="modal-dialog">
        <div class="modal-header">
          <h3 class="modal-title">新建系统用户</h3>
          <button class="btn btn-ghost btn-sm" @click="showCreateModal = false">
            <IconSvg name="x" size="14" />
          </button>
        </div>
        <form @submit.prevent="handleCreateUser">
          <div class="modal-body">
            <div class="form-group">
              <label class="form-label">登录用户名</label>
              <input v-model="createForm.userId" type="text" class="form-control" placeholder="英文小写或数字" required />
            </div>
            <div class="form-group">
              <label class="form-label">姓名 / 显示昵称</label>
              <input v-model="createForm.name" type="text" class="form-control" placeholder="例如：张三" required />
            </div>
            <div class="form-group">
              <label class="form-label">初始密码</label>
              <input v-model="createForm.password" type="password" class="form-control" placeholder="至少 6 位密码" required />
            </div>
          </div>
          <div class="modal-footer">
            <button type="button" class="btn btn-ghost" @click="showCreateModal = false">取消</button>
            <button type="submit" class="btn btn-primary">立即创建</button>
          </div>
        </form>
      </div>
    </div>

    <!-- 重置密码模态框 -->
    <div v-if="showResetModal" class="modal-overlay">
      <div class="modal-dialog">
        <div class="modal-header">
          <h3 class="modal-title">重置用户密码</h3>
          <button class="btn btn-ghost btn-sm" @click="showResetModal = false">
            <IconSvg name="x" size="14" />
          </button>
        </div>
        <form @submit.prevent="handleResetPassword">
          <div class="modal-body">
            <p style="margin-bottom: 12px; color: #475569;">
              目标用户：<strong>{{ currentSelectedUser?.user_id || currentSelectedUser?.userid }}</strong>
            </p>
            <div class="form-group">
              <label class="form-label">新密码</label>
              <input v-model="newPassword" type="password" class="form-control" placeholder="请输入新密码" required />
            </div>
          </div>
          <div class="modal-footer">
            <button type="button" class="btn btn-ghost" @click="showResetModal = false">取消</button>
            <button type="submit" class="btn btn-primary">确认修改</button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>

<script setup>
import PaginationControls from '../components/PaginationControls.vue'
import PersonalPasswordForm from '../components/PersonalPasswordForm.vue'
import { usePagination } from '../composables/usePagination'
import { ref, reactive, computed, watch, nextTick, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import { store } from '../store'

const route = useRoute(), router = useRouter()
const tabs = computed(() => [{ id: 'users', name: '用户管理' }, { id: 'password', name: '个人密码' }, ...(store.user?.admin ? [{ id: 'logs', name: '登录审计' }] : [])])
const currentTab = computed(() => tabs.value.some(tab => tab.id === route.query.tab) ? route.query.tab : 'users')
function selectTab(tab) { router.push({ path: '/admin', query: { ...route.query, tab } }) }
async function handleTabKey(event) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const index = tabs.value.findIndex(tab => tab.id === currentTab.value)
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.value.length - 1 : (index + (event.key === 'ArrowRight' ? 1 : -1) + tabs.value.length) % tabs.value.length
  await router.push({ path: '/admin', query: { ...route.query, tab: tabs.value[next].id } })
  await nextTick()
  document.getElementById('security-tab-' + currentTab.value)?.focus()
}
const isCurrentUser = user => (user.user_id || user.userid) === store.user?.user_id
const isAdminUser = user => String(user.user_id || user.userid).toLowerCase() === 'admin'
const users = ref([])
const sortedUsers = computed(() => [...users.value].sort((a, b) => Number(isCurrentUser(b)) - Number(isCurrentUser(a))))
const userPager = usePagination(sortedUsers)
const usersLoading = ref(false), usersError = ref('')
const auditLogs = ref([])
const auditPageSize = ref(10)
const auditPage = ref(1), auditTotal = ref(0), auditQuery = ref('')

const showCreateModal = ref(false)
const showResetModal = ref(false)
const currentSelectedUser = ref(null)
const newPassword = ref('')

const createForm = reactive({
  userId: '',
  name: '',
  password: ''
})

const fetchUsers = async () => {
  if (!store.user?.admin) return
  usersLoading.value = true
  usersError.value = ''
  try {
    const list = await api.listUsers()
    users.value = list || []
  } catch (err) {
    usersError.value = '加载用户列表失败：' + err.message
  } finally { usersLoading.value = false }
}

const fetchAuditLogs = async () => {
  try {
    const res = await api.listLoginAuditLogs({ page: auditPage.value, page_size: auditPageSize.value, query: auditQuery.value })
    auditLogs.value = res.records || res.items || []
    auditTotal.value = res.total || 0
    auditPage.value = res.page || auditPage.value
  } catch (_) {}
}

const handleCreateUser = async () => {
  try {
    await api.createUser(createForm)
    store.showToast('用户创建成功', 'success')
    showCreateModal.value = false
    createForm.userId = ''
    createForm.name = ''
    createForm.password = ''
    await fetchUsers()
  } catch (err) {
    store.showToast('创建失败：' + err.message, 'error')
  }
}

const removeUser = async u => {
  const id = u.user_id || u.userid
  if (!window.confirm('删除用户“' + id + '”？')) return
  try { await api.deleteUser(id); await fetchUsers() } catch (e) { store.showToast(e.message, 'error') }
}
const toggleStatus = async (u) => {
  const nextStatus = u.status === 1 ? 0 : 1
  const uid = u.user_id || u.userid
  try {
    await api.updateUserStatus(uid, { enabled: nextStatus === 1 })
    store.showToast('状态更新成功', 'success')
    await fetchUsers()
  } catch (err) {
    store.showToast('更新失败：' + err.message, 'error')
  }
}

const openResetPwdModal = (u) => {
  currentSelectedUser.value = u
  newPassword.value = ''
  showResetModal.value = true
}

const handleResetPassword = async () => {
  const uid = currentSelectedUser.value?.user_id || currentSelectedUser.value?.userid
  try {
    await api.resetUserPassword(uid, { new_password: newPassword.value })
    store.showToast('密码重置成功', 'success')
    showResetModal.value = false
  } catch (err) {
    store.showToast('重置密码失败：' + err.message, 'error')
  }
}

onMounted(() => {
  fetchUsers()
  if (currentTab.value === 'logs') fetchAuditLogs()
})
watch(currentTab, tab => { if (tab === 'logs') fetchAuditLogs() })
</script>

<style scoped>
.tabs-nav {
  display: flex;
  gap: 8px;
  border-bottom: 2px solid #e2e8f0;
  margin-bottom: 20px;
  flex-wrap: wrap;
}
.account-details { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; margin: 0 0 16px; }
.account-details dt { color: #64748b; font-size: 13px; margin-bottom: 6px; }
.account-details dd { margin: 0; font-weight: 600; overflow-wrap: anywhere; }
.current-user-row { background: #f0f9ff; }
@media (max-width: 600px) { .account-details { grid-template-columns: 1fr; } }
.tab-btn {
  background: none;
  border: none;
  padding: 8px 16px;
  font-size: 14px;
  font-weight: 600;
  color: #64748b;
  cursor: pointer;
  position: relative;
  bottom: -2px;
}
.tab-btn.active {
  color: #0284c7;
  border-bottom: 2px solid #0284c7;
}
</style>
