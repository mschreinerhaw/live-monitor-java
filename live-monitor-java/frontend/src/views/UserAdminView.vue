<template>
  <div class="page-container user-admin-page">
    <div class="page-header">
      <div class="page-header-title">
        <h1>用户账号与安全审计</h1>
        <p>系统运维人员与管理员账号权限管理，提供用户登录安全审计日志</p>
      </div>
      <div>
        <button v-if="currentTab === 'users'" class="btn btn-primary" @click="showCreateModal = true">
          <IconSvg name="plus" size="14" />
          <span>新建用户</span>
        </button>
      </div>
    </div>

    <!-- 选项卡切换 -->
    <div class="tabs-nav" style="margin-bottom: 20px;">
      <button class="tab-btn" :class="{ active: currentTab === 'users' }" @click="currentTab = 'users'">
        用户账号列表 ({{ users.length }})
      </button>
      <button class="tab-btn" :class="{ active: currentTab === 'logs' }" @click="currentTab = 'logs'; fetchAuditLogs()">
        登录审计日志
      </button>
    </div>

    <!-- 1. 用户列表 Tab -->
    <div v-if="currentTab === 'users'" class="card">
      <div class="card-header">
        <div class="card-title">
          <IconSvg name="users" size="16" />
          <span>用户列表</span>
        </div>
      </div>

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
            <tr v-for="u in users" :key="u.id">
              <td>{{ u.id }}</td>
              <td><strong>{{ u.user_id || u.userid }}</strong></td>
              <td>{{ u.name || '-' }}</td>
              <td>
                <span class="badge" :class="u.grade === 1 ? 'badge-info' : ''">
                  {{ u.grade === 1 ? '超级管理员' : '普通用户' }}
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
                    v-if="u.user_id !== 'admin' && u.userid !== 'admin'"
                    class="btn btn-ghost btn-sm"
                    @click="toggleStatus(u)"
                  >
                    {{ u.status === 1 ? '禁用' : '启用' }}
                  </button>
                  <button class="btn btn-ghost btn-sm" @click="openResetPwdModal(u)">
                    重置密码
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
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
import { ref, reactive, onMounted } from 'vue'
import { api } from '../api'
import { store } from '../store'

const currentTab = ref('users')
const users = ref([])
const auditLogs = ref([])

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
  try {
    const list = await api.listUsers()
    users.value = list || []
  } catch (err) {
    store.showToast('加载用户列表失败：' + err.message, 'error')
  }
}

const fetchAuditLogs = async () => {
  try {
    const res = await api.listLoginAuditLogs({ page: 1, page_size: 50 })
    auditLogs.value = res.records || res.items || []
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

const toggleStatus = async (u) => {
  const nextStatus = u.status === 1 ? 0 : 1
  const uid = u.user_id || u.userid
  try {
    await api.updateUserStatus(uid, { status: nextStatus })
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
    await api.resetUserPassword(uid, { password: newPassword.value })
    store.showToast('密码重置成功', 'success')
    showResetModal.value = false
  } catch (err) {
    store.showToast('重置密码失败：' + err.message, 'error')
  }
}

onMounted(() => {
  fetchUsers()
})
</script>

<style scoped>
.tabs-nav {
  display: flex;
  gap: 8px;
  border-bottom: 2px solid #e2e8f0;
}
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
