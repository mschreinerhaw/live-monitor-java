<template>
  <div class="page-container alert-settings-page">
    <div class="page-header">
      <div class="page-header-title">
        <h1>告警配置与通知管理</h1>
        <p>维护监控服务与告警组的绑定关系，配置短信 SMS、邮件、Webhook 渠道及应急策略</p>
      </div>
      <div>
        <button v-if="currentTab === 'config'" class="btn btn-primary" @click="openCreateChannelModal">
          <IconSvg name="plus" size="14" />
          <span>新建告警渠道</span>
        </button>
      </div>
    </div>

    <!-- 顶部大 Tab 切换 -->
    <div class="tabs-nav" style="margin-bottom: 20px;">
      <button
        class="tab-btn"
        :class="{ active: currentTab === 'bindings' }"
        @click="currentTab = 'bindings'"
      >
        服务绑定与测试
      </button>
      <button
        class="tab-btn"
        :class="{ active: currentTab === 'config' }"
        @click="currentTab = 'config'"
      >
        告警渠道与告警组
      </button>
    </div>

    <!-- 1. 服务绑定与测试 Tab -->
    <div v-if="currentTab === 'bindings'" class="card">
      <div class="card-header" style="flex-wrap: wrap; gap: 10px;">
        <div class="card-title">
          <IconSvg name="activity" size="16" />
          <span>服务与告警组绑定配置</span>
          <span class="badge badge-info" style="margin-left: 6px;">{{ servicesList.length }} 个服务</span>
        </div>
        <div style="display: flex; gap: 8px;">
          <input
            v-model="serviceSearch"
            type="text"
            class="form-control"
            placeholder="搜索服务名称 / 集群..."
            style="width: 200px;"
          />
        </div>
      </div>

      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>服务名称</th>
              <th>服务类型</th>
              <th>集群</th>
              <th>绑定告警组</th>
              <th>告警启用</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!filteredBindingServices.length">
              <td colspan="6" class="text-center text-muted" style="padding: 32px;">暂无服务数据</td>
            </tr>
            <tr v-for="srv in filteredBindingServices" :key="srv.id">
              <td><strong>{{ srv.service_name }}</strong></td>
              <td><span class="type-tag">{{ srv.service_type }}</span></td>
              <td>{{ srv.cluster_name || '-' }}</td>
              <td style="max-width: 320px;">
                <div class="group-checkbox-row">
                  <label v-for="g in groupsList" :key="g.id" class="group-checkbox-chip">
                    <input
                      type="checkbox"
                      :value="g.id"
                      :checked="isServiceGroupChecked(srv, g.id)"
                      @change="toggleServiceGroup(srv, g.id, $event)"
                    />
                    <span>{{ g.group_name }}</span>
                  </label>
                </div>
              </td>
              <td>
                <span :class="['badge', srv.service_alert_enabled !== false ? 'badge-up' : 'badge-down']">
                  {{ srv.service_alert_enabled !== false ? '已启用' : '已停用' }}
                </span>
              </td>
              <td>
                <div style="display: flex; gap: 6px;">
                  <button class="btn btn-ghost btn-sm" @click="handleSaveServiceBindings(srv)">
                    <IconSvg name="check" size="12" />
                    <span>保存绑定</span>
                  </button>
                  <button class="btn btn-ghost btn-sm" @click="handleTestServiceAlert(srv)" title="触发测试告警">
                    <IconSvg name="send" size="12" />
                    <span>测试告警</span>
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 2. 告警渠道与告警组 Tab -->
    <div v-if="currentTab === 'config'">
      <!-- 2.1 通知渠道管理 -->
      <div class="card">
        <div class="card-header">
          <div class="card-title">
            <IconSvg name="bell" size="16" />
            <span>通知渠道 ({{ channelsList.length }})</span>
          </div>
          <button class="btn btn-primary btn-sm" @click="openCreateChannelModal">
            <IconSvg name="plus" size="12" />
            <span>新建渠道</span>
          </button>
        </div>

        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>渠道名称</th>
                <th>类型</th>
                <th>状态</th>
                <th>创建时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="!channelsList.length">
                <td colspan="5" class="text-center text-muted" style="padding: 24px;">暂无通知渠道配置</td>
              </tr>
              <tr v-for="ch in channelsList" :key="ch.id">
                <td><strong>{{ ch.channelName }}</strong></td>
                <td><span class="badge badge-info">{{ (ch.channelType || '').toUpperCase() }}</span></td>
                <td>
                  <span :class="['badge', ch.enabled !== false ? 'badge-up' : 'badge-down']">
                    {{ ch.enabled !== false ? '正常启用' : '已禁用' }}
                  </span>
                </td>
                <td>{{ ch.createdAt || '-' }}</td>
                <td>
                  <div style="display: flex; gap: 6px;">
                    <button class="btn btn-ghost btn-sm" @click="handleTestChannel(ch)">
                      <IconSvg name="send" size="12" />
                      <span>测试发送</span>
                    </button>
                    <button class="btn btn-ghost btn-sm" @click="openEditChannelModal(ch)">
                      <IconSvg name="edit" size="12" />
                    </button>
                    <button class="btn btn-danger btn-sm" @click="handleDeleteChannel(ch)">
                      <IconSvg name="trash" size="12" />
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- 2.2 告警组管理 -->
      <div class="card">
        <div class="card-header">
          <div class="card-title">
            <IconSvg name="users" size="16" />
            <span>告警组 ({{ groupsList.length }})</span>
          </div>
          <button class="btn btn-primary btn-sm" @click="openCreateGroupModal">
            <IconSvg name="plus" size="12" />
            <span>新建告警组</span>
          </button>
        </div>

        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>告警组名称</th>
                <th>说明</th>
                <th>绑定渠道数</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="!groupsList.length">
                <td colspan="5" class="text-center text-muted" style="padding: 24px;">暂无告警组配置</td>
              </tr>
              <tr v-for="g in groupsList" :key="g.id">
                <td><strong>{{ g.group_name }}</strong></td>
                <td>{{ g.description || '-' }}</td>
                <td>{{ g.channels?.length || 0 }} 个通知渠道</td>
                <td>
                  <span :class="['badge', g.enabled !== false ? 'badge-up' : 'badge-down']">
                    {{ g.enabled !== false ? '启用' : '禁用' }}
                  </span>
                </td>
                <td>
                  <div style="display: flex; gap: 6px;">
                    <button class="btn btn-danger btn-sm" @click="handleDeleteGroup(g)">
                      <IconSvg name="trash" size="12" />
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- 新建 / 编辑通知渠道模态框 -->
    <div v-if="showChannelModal" class="modal-overlay">
      <div class="modal-dialog">
        <div class="modal-header">
          <h3 class="modal-title">{{ editingChannelId ? '编辑通知渠道' : '新建通知渠道' }}</h3>
          <button class="btn btn-ghost btn-sm" @click="showChannelModal = false">
            <IconSvg name="x" size="14" />
          </button>
        </div>
        <form @submit.prevent="handleSaveChannel">
          <div class="modal-body">
            <div class="form-group">
              <label class="form-label">渠道名称</label>
              <input v-model="channelForm.channelName" type="text" class="form-control" placeholder="例如：核心业务应急短信渠道" required />
            </div>

            <div class="form-group">
              <label class="form-label">渠道类型</label>
              <select v-model="channelForm.channelType" class="form-control" required>
                <option value="sms">短信 (SMS 网关)</option>
                <option value="email">邮件 (SMTP)</option>
                <option value="webhook">通用 Webhook</option>
                <option value="dingtalk">钉钉群机器人</option>
                <option value="wecom">企业微信机器人</option>
              </select>
            </div>

            <!-- 短信网关配置 -->
            <div v-if="channelForm.channelType === 'sms'">
              <div class="form-group">
                <label class="form-label">SMS 网关 URL</label>
                <input v-model="channelForm.sms_api_url" type="url" class="form-control" placeholder="http://127.0.0.1/smsSendServlet.htm" required />
              </div>
              <div class="form-group">
                <label class="form-label">网关用户名 (可选)</label>
                <input v-model="channelForm.sms_username" type="text" class="form-control" placeholder="xxzx" />
              </div>
              <div class="form-group">
                <label class="form-label">默认接收手机号 (可多号逗号分隔)</label>
                <input v-model="channelForm.alert_mobile" type="text" class="form-control" placeholder="18211092191,13800000000" />
              </div>
            </div>

            <!-- 邮件配置 -->
            <div v-if="channelForm.channelType === 'email'">
              <div class="form-row">
                <div class="form-group">
                  <label class="form-label">SMTP 主机</label>
                  <input v-model="channelForm.smtp_host" type="text" class="form-control" placeholder="smtp.example.com" />
                </div>
                <div class="form-group">
                  <label class="form-label">SMTP 端口</label>
                  <input v-model.number="channelForm.smtp_port" type="number" class="form-control" placeholder="465" />
                </div>
              </div>
              <div class="form-group">
                <label class="form-label">默认接收邮箱</label>
                <input v-model="channelForm.alert_email" type="email" class="form-control" placeholder="ops@example.com" />
              </div>
            </div>

            <!-- Webhook / 钉钉 / 企微配置 -->
            <div v-if="['webhook', 'dingtalk', 'wecom'].includes(channelForm.channelType)">
              <div class="form-group">
                <label class="form-label">Webhook URL</label>
                <input v-model="channelForm.webhook_url" type="url" class="form-control" placeholder="https://oapi.example.com/robot/send" required />
              </div>
            </div>
          </div>
          <div class="modal-footer">
            <button type="button" class="btn btn-ghost" @click="showChannelModal = false">取消</button>
            <button type="submit" class="btn btn-primary">保存渠道</button>
          </div>
        </form>
      </div>
    </div>

    <!-- 新建告警组模态框 -->
    <div v-if="showGroupModal" class="modal-overlay">
      <div class="modal-dialog">
        <div class="modal-header">
          <h3 class="modal-title">新建告警组</h3>
          <button class="btn btn-ghost btn-sm" @click="showGroupModal = false">
            <IconSvg name="x" size="14" />
          </button>
        </div>
        <form @submit.prevent="handleSaveGroup">
          <div class="modal-body">
            <div class="form-group">
              <label class="form-label">告警组名称</label>
              <input v-model="groupForm.groupName" type="text" class="form-control" placeholder="例如：核心交易应急组" required />
            </div>
            <div class="form-group">
              <label class="form-label">说明描述</label>
              <input v-model="groupForm.description" type="text" class="form-control" placeholder="生产核心系统运维组" />
            </div>
            <div class="form-group">
              <label class="form-label">勾选绑定的通知渠道</label>
              <div style="display: flex; flex-direction: column; gap: 8px;">
                <label v-for="ch in channelsList" :key="ch.id" style="display: flex; align-items: center; gap: 6px; font-size: 13px; cursor: pointer;">
                  <input type="checkbox" :value="ch.id" v-model="groupForm.channelIds" />
                  <span>{{ ch.channelName }} ({{ (ch.channelType || '').toUpperCase() }})</span>
                </label>
              </div>
            </div>
          </div>
          <div class="modal-footer">
            <button type="button" class="btn btn-ghost" @click="showGroupModal = false">取消</button>
            <button type="submit" class="btn btn-primary">保存告警组</button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { api } from '../api'
import { store } from '../store'

const currentTab = ref('bindings')
const serviceSearch = ref('')
const servicesList = ref([])
const channelsList = ref([])
const groupsList = ref([])

const showChannelModal = ref(false)
const editingChannelId = ref(null)
const showGroupModal = ref(false)

const channelForm = reactive({
  channelName: '',
  channelType: 'sms',
  sms_api_url: 'http://127.0.0.1/smsSendServlet.htm',
  sms_username: '',
  alert_mobile: '',
  smtp_host: '',
  smtp_port: 465,
  alert_email: '',
  webhook_url: '',
  enabled: true
})

const groupForm = reactive({
  groupName: '',
  description: '',
  channelIds: [],
  enabled: true
})

// 缓存服务临时修改的告警组勾选
const serviceGroupChanges = reactive({})

const filteredBindingServices = computed(() => {
  if (!serviceSearch.value.trim()) return servicesList.value
  const q = serviceSearch.value.trim().toLowerCase()
  return servicesList.value.filter(s => (s.service_name || '').toLowerCase().includes(q) || (s.cluster_name || '').toLowerCase().includes(q))
})

const isServiceGroupChecked = (srv, groupId) => {
  if (serviceGroupChanges[srv.id] !== undefined) {
    return serviceGroupChanges[srv.id].includes(groupId)
  }
  const currentIds = srv.alert_group_ids || (srv.alert_group_id ? [srv.alert_group_id] : [])
  return currentIds.includes(groupId)
}

const toggleServiceGroup = (srv, groupId, e) => {
  if (serviceGroupChanges[srv.id] === undefined) {
    const current = srv.alert_group_ids || (srv.alert_group_id ? [srv.alert_group_id] : [])
    serviceGroupChanges[srv.id] = [...current]
  }
  if (e.target.checked) {
    if (!serviceGroupChanges[srv.id].includes(groupId)) {
      serviceGroupChanges[srv.id].push(groupId)
    }
  } else {
    serviceGroupChanges[srv.id] = serviceGroupChanges[srv.id].filter(id => id !== groupId)
  }
}

const handleSaveServiceBindings = async (srv) => {
  const groupIds = serviceGroupChanges[srv.id] !== undefined
    ? serviceGroupChanges[srv.id]
    : (srv.alert_group_ids || (srv.alert_group_id ? [srv.alert_group_id] : []))
  try {
    await api.updateServiceAlertGroups(srv.id, groupIds)
    srv.alert_group_ids = groupIds
    store.showToast(`已更新服务 "${srv.service_name}" 的告警组绑定`, 'success')
  } catch (err) {
    store.showToast('更新绑定失败：' + err.message, 'error')
  }
}

const handleTestServiceAlert = async (srv) => {
  try {
    store.showToast(`正在向 "${srv.service_name}" 发送测试告警...`)
    await api.testServiceAlert(srv.id)
    store.showToast('测试告警消息已成功投递', 'success')
  } catch (err) {
    store.showToast('测试告警发送失败：' + err.message, 'error')
  }
}

const fetchData = async () => {
  try {
    const [srvRes, chRes, grRes] = await Promise.all([
      api.listServices(true).catch(() => []),
      api.listAlertChannels(true).catch(() => []),
      api.listAlertGroups(true).catch(() => [])
    ])
    servicesList.value = srvRes || []
    channelsList.value = chRes || []
    groupsList.value = grRes || []
  } catch (err) {
    store.showToast('加载告警配置失败：' + err.message, 'error')
  }
}

const openCreateChannelModal = () => {
  editingChannelId.value = null
  Object.assign(channelForm, {
    channelName: '',
    channelType: 'sms',
    sms_api_url: 'http://127.0.0.1/smsSendServlet.htm',
    sms_username: '',
    alert_mobile: '',
    smtp_host: '',
    smtp_port: 465,
    alert_email: '',
    webhook_url: '',
    enabled: true
  })
  showChannelModal.value = true
}

const openEditChannelModal = (ch) => {
  editingChannelId.value = ch.id
  Object.assign(channelForm, {
    channelName: ch.channelName || '',
    channelType: ch.channelType || 'sms',
    sms_api_url: ch.sms_api_url || 'http://127.0.0.1/smsSendServlet.htm',
    sms_username: ch.sms_username || '',
    alert_mobile: ch.alert_mobile || '',
    smtp_host: ch.smtp_host || '',
    smtp_port: ch.smtp_port || 465,
    alert_email: ch.alert_email || '',
    webhook_url: ch.webhook_url || '',
    enabled: ch.enabled !== false
  })
  showChannelModal.value = true
}

const handleSaveChannel = async () => {
  try {
    if (editingChannelId.value) {
      await api.updateAlertChannel(editingChannelId.value, channelForm)
      store.showToast('告警渠道已更新', 'success')
    } else {
      await api.createAlertChannel(channelForm)
      store.showToast('告警渠道已创建', 'success')
    }
    showChannelModal.value = false
    await fetchData()
  } catch (err) {
    store.showToast('保存渠道失败：' + err.message, 'error')
  }
}

const handleTestChannel = async (ch) => {
  try {
    store.showToast(`正在发送测试消息至 ${ch.channelName}...`)
    const res = await api.testAlertChannel(ch.id)
    store.showToast(res.message || '测试消息发送成功', res.success ? 'success' : 'error')
  } catch (err) {
    store.showToast('测试失败：' + err.message, 'error')
  }
}

const handleDeleteChannel = async (ch) => {
  if (!confirm(`确定删除渠道 "${ch.channelName}" 吗？`)) return
  try {
    await api.deleteAlertChannel(ch.id)
    store.showToast('渠道已删除', 'success')
    await fetchData()
  } catch (err) {
    store.showToast('删除失败：' + err.message, 'error')
  }
}

const openCreateGroupModal = () => {
  Object.assign(groupForm, {
    groupName: '',
    description: '',
    channelIds: [],
    enabled: true
  })
  showGroupModal.value = true
}

const handleSaveGroup = async () => {
  try {
    await api.createAlertGroup(groupForm)
    store.showToast('告警组已创建', 'success')
    showGroupModal.value = false
    await fetchData()
  } catch (err) {
    store.showToast('创建告警组失败：' + err.message, 'error')
  }
}

const handleDeleteGroup = async (g) => {
  if (!confirm(`确定删除告警组 "${g.group_name}" 吗？`)) return
  try {
    await api.deleteAlertGroup(g.id)
    store.showToast('告警组已删除', 'success')
    await fetchData()
  } catch (err) {
    store.showToast('删除失败：' + err.message, 'error')
  }
}

onMounted(() => {
  fetchData()
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

.type-tag {
  background: #f1f5f9;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 11px;
}

.group-checkbox-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.group-checkbox-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 12px;
  cursor: pointer;
}
</style>
