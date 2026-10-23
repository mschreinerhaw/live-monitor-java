<template>
  <div class="page-container alert-settings-page">
    <div class="page-header">
      <div class="page-header-title">
        <h1>告警配置与通知管理</h1>
        <p>维护告警渠道（短信 SMS、邮件、Webhook）、告警组与全局告警规则策略</p>
      </div>
      <div>
        <button class="btn btn-primary" @click="openCreateChannelModal">
          <IconSvg name="plus" size="14" />
          <span>新建告警渠道</span>
        </button>
      </div>
    </div>

    <!-- 选项卡导航 -->
    <div class="tabs-nav" style="margin-bottom: 20px;">
      <button class="tab-btn" :class="{ active: currentTab === 'channels' }" @click="currentTab = 'channels'">
        告警渠道 ({{ channels.length }})
      </button>
      <button class="tab-btn" :class="{ active: currentTab === 'groups' }" @click="currentTab = 'groups'">
        告警组 ({{ groups.length }})
      </button>
    </div>

    <!-- 1. 告警渠道列表 -->
    <div v-if="currentTab === 'channels'" class="card">
      <div class="card-header">
        <div class="card-title">
          <IconSvg name="bell" size="16" />
          <span>通知渠道列表</span>
        </div>
      </div>

      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>渠道名称</th>
              <th>渠道类型</th>
              <th>状态</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!channels.length">
              <td colspan="5" class="text-center text-muted" style="padding: 32px;">暂无告警渠道配置</td>
            </tr>
            <tr v-for="ch in channels" :key="ch.id">
              <td><strong>{{ ch.channelName }}</strong></td>
              <td>
                <span class="badge badge-info">{{ (ch.channelType || '').toUpperCase() }}</span>
              </td>
              <td>
                <span class="badge" :class="ch.enabled !== false ? 'badge-up' : 'badge-down'">
                  {{ ch.enabled !== false ? '已启用' : '已禁用' }}
                </span>
              </td>
              <td>{{ ch.createdAt || '-' }}</td>
              <td>
                <div style="display: flex; gap: 6px;">
                  <button class="btn btn-ghost btn-sm" @click="handleTestChannel(ch)">
                    <IconSvg name="send" size="12" />
                    <span>测试发送</span>
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

    <!-- 2. 告警组列表 -->
    <div v-if="currentTab === 'groups'" class="card">
      <div class="card-header">
        <div class="card-title">
          <IconSvg name="users" size="16" />
          <span>告警组列表</span>
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
            <tr v-if="!groups.length">
              <td colspan="5" class="text-center text-muted" style="padding: 32px;">暂无告警组配置</td>
            </tr>
            <tr v-for="g in groups" :key="g.id">
              <td><strong>{{ g.group_name }}</strong></td>
              <td>{{ g.description || '-' }}</td>
              <td>{{ g.channels?.length || 0 }} 个渠道</td>
              <td>
                <span class="badge" :class="g.enabled !== false ? 'badge-up' : 'badge-down'">
                  {{ g.enabled !== false ? '启用' : '禁用' }}
                </span>
              </td>
              <td>
                <button class="btn btn-danger btn-sm" @click="handleDeleteGroup(g)">
                  <IconSvg name="trash" size="12" />
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 新建渠道模态框 -->
    <div v-if="showChannelModal" class="modal-overlay">
      <div class="modal-dialog">
        <div class="modal-header">
          <h3 class="modal-title">新建告警渠道</h3>
          <button class="btn btn-ghost btn-sm" @click="showChannelModal = false">
            <IconSvg name="x" size="14" />
          </button>
        </div>
        <form @submit.prevent="handleSaveChannel">
          <div class="modal-body">
            <div class="form-group">
              <label class="form-label">渠道名称</label>
              <input v-model="channelForm.channelName" type="text" class="form-control" placeholder="例如：运维组紧急短信渠道" required />
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

            <div v-if="channelForm.channelType === 'sms'" class="form-group">
              <label class="form-label">SMS 网关 URL</label>
              <input v-model="channelForm.sms_api_url" type="url" class="form-control" placeholder="http://127.0.0.1/smsSendServlet.htm" required />
            </div>

            <div v-if="channelForm.channelType === 'sms'" class="form-group">
              <label class="form-label">默认接收手机号 (可多号逗号分隔)</label>
              <input v-model="channelForm.alert_mobile" type="text" class="form-control" placeholder="18211092191,13800000000" />
            </div>

            <div v-if="channelForm.channelType === 'webhook'" class="form-group">
              <label class="form-label">Webhook 地址</label>
              <input v-model="channelForm.webhook_url" type="url" class="form-control" placeholder="https://oapi.example.com/robot/send" required />
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
              <input v-model="groupForm.groupName" type="text" class="form-control" placeholder="例如：核心业务应急组" required />
            </div>
            <div class="form-group">
              <label class="form-label">描述</label>
              <input v-model="groupForm.description" type="text" class="form-control" placeholder="核心系统运维及开发应急群" />
            </div>
            <div class="form-group">
              <label class="form-label">选择绑定的通知渠道</label>
              <div style="display: flex; flex-direction: column; gap: 8px;">
                <label v-for="ch in channels" :key="ch.id" style="display: flex; align-items: center; gap: 6px; font-size: 13px;">
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
import { ref, reactive, onMounted } from 'vue'
import { api } from '../api'
import { store } from '../store'

const currentTab = ref('channels')
const channels = ref([])
const groups = ref([])

const showChannelModal = ref(false)
const showGroupModal = ref(false)

const channelForm = reactive({
  channelName: '',
  channelType: 'sms',
  sms_api_url: 'http://127.0.0.1/smsSendServlet.htm',
  alert_mobile: '',
  webhook_url: '',
  enabled: true
})

const groupForm = reactive({
  groupName: '',
  description: '',
  channelIds: [],
  enabled: true
})

const fetchData = async () => {
  try {
    const [chList, grList] = await Promise.all([
      api.listAlertChannels(true).catch(() => []),
      api.listAlertGroups(true).catch(() => [])
    ])
    channels.value = chList || []
    groups.value = grList || []
  } catch (err) {
    store.showToast('加载告警配置失败：' + err.message, 'error')
  }
}

const openCreateChannelModal = () => {
  Object.assign(channelForm, {
    channelName: '',
    channelType: 'sms',
    sms_api_url: 'http://127.0.0.1/smsSendServlet.htm',
    alert_mobile: '',
    webhook_url: '',
    enabled: true
  })
  showChannelModal.value = true
}

const handleSaveChannel = async () => {
  try {
    await api.createAlertChannel(channelForm)
    store.showToast('告警渠道已创建', 'success')
    showChannelModal.value = false
    await fetchData()
  } catch (err) {
    store.showToast('创建渠道失败：' + err.message, 'error')
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
</style>
