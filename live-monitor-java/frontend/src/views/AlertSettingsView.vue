<template>
  <div class="page-container alert-settings-page">
    <div class="page-header">
      <div class="page-header-title">
        <h1>告警配置与通知管理</h1>
        <p>按项目和服务配置告警对象，统一维护接收人、通知渠道与触发策略</p>
      </div>
      <div>
        <button
          v-if="currentTab === 'config'"
          class="btn btn-primary"
          @click="openCreateChannelModal"
        >
          <IconSvg name="plus" size="14" />
          <span>新建告警渠道</span>
        </button>
      </div>
    </div>

    <p v-if="loadError" role="alert" class="settings-error">{{ loadError }}</p>
    <!-- 顶部大 Tab 切换 -->
    <div class="tabs-nav" style="margin-bottom: 20px">
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
      <button
        class="tab-btn"
        :class="{ active: currentTab === 'projects' }"
        @click="currentTab = 'projects'"
      >
        项目告警配置
      </button>
      <button
        class="tab-btn"
        :class="{ active: currentTab === 'contacts' }"
        @click="currentTab = 'contacts'"
      >
        告警联系人
      </button>
    </div>
    <div v-if="currentTab === 'projects'" class="project-routing-tab">
      <div class="settings-explainer">
        <strong>按项目配置告警人</strong><span>选择项目的告警组，再通过告警组绑定渠道接收人。</span
        ><button class="btn btn-ghost btn-sm" @click="currentTab = 'contacts'">查看联系人</button>
      </div>
      <ExternalProjectsView embedded :channels="channelsList" @saved="fetchData" />
    </div>
    <section v-if="currentTab === 'contacts'" class="card contacts-panel">
      <div class="card-header">
        <div>
          <h2 class="card-title">
            告警联系人 <span class="badge badge-info">{{ contacts.length }}</span>
          </h2>
          <p class="form-hint">
            按接收地址列出归属渠道、告警组和项目；同一地址在不同渠道中分别维护。
          </p>
        </div>
        <button class="btn btn-ghost btn-sm" @click="currentTab = 'config'">管理通知渠道</button>
      </div>
      <label class="contact-search"
        >搜索联系人<input
          v-model="contactSearch"
          class="form-control"
          placeholder="手机号、邮箱、渠道、告警组或项目"
      /></label>
      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>接收地址 / 联系方式</th>
              <th>类型</th>
              <th>通知渠道</th>
              <th>告警组</th>
              <th>关联项目</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="contact in contactPager.items" :key="contact.id">
              <td class="contact-address">{{ contact.value }}</td>
              <td>{{ contact.type }}</td>
              <td>{{ contact.channel.channelName }}</td>
              <td>{{ contact.groups.map((g) => g.group_name).join('、') || '尚未绑定告警组' }}</td>
              <td>{{ contact.projects.map((p) => p.project_name).join('、') || '—' }}</td>
              <td>
                <span
                  :class="['badge', contact.channel.enabled !== false ? 'badge-up' : 'badge-down']"
                  >{{ contact.channel.enabled !== false ? '渠道启用' : '渠道停用' }}</span
                >
              </td>
              <td>
                <button class="btn btn-ghost btn-sm" @click="openEditChannelModal(contact.channel)">
                  维护联系人
                </button>
              </td>
            </tr>
            <tr v-if="!contactPager.total">
              <td colspan="7">
                {{ loading ? '正在加载联系人…' : '暂无匹配联系人，请在通知渠道中添加接收人' }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <PaginationControls
        v-model:page="contactPager.page"
        v-model:page-size="contactPager.pageSize"
        :total="contactPager.total"
        label="告警联系人"
      />
    </section>

    <!-- 1. 服务绑定与测试 Tab -->
    <div v-if="currentTab === 'bindings'" class="card">
      <div class="card-header" style="flex-wrap: wrap; gap: 10px">
        <div class="card-title">
          <IconSvg name="activity" size="16" />
          <span>服务与告警组绑定配置</span>
          <span class="badge badge-info" style="margin-left: 6px"
            >{{ servicesList.length }} 个服务</span
          >
        </div>
        <div style="display: flex; gap: 8px">
          <input
            v-model="serviceSearch"
            type="text"
            class="form-control"
            placeholder="搜索服务名称 / 集群..."
            style="width: 200px"
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
              <td colspan="6" class="text-center text-muted" style="padding: 32px">暂无服务数据</td>
            </tr>
            <tr v-for="srv in bindingPager.items" :key="srv.id">
              <td>
                <strong>{{ srv.service_name }}</strong>
              </td>
              <td>
                <span class="type-tag">{{ srv.service_type }}</span>
              </td>
              <td>{{ srv.cluster_name || '-' }}</td>
              <td style="max-width: 320px">
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
                <span
                  :class="[
                    'badge',
                    srv.service_alert_enabled !== false ? 'badge-up' : 'badge-down',
                  ]"
                >
                  {{ srv.service_alert_enabled !== false ? '已启用' : '已停用' }}
                </span>
              </td>
              <td>
                <div style="display: flex; gap: 6px">
                  <button class="btn btn-ghost btn-sm" @click="handleSaveServiceBindings(srv)">
                    <IconSvg name="check" size="12" />
                    <span>保存绑定</span>
                  </button>
                  <button
                    class="btn btn-ghost btn-sm"
                    @click="handleTestServiceAlert(srv)"
                    title="触发测试告警"
                  >
                    <IconSvg name="send" size="12" />
                    <span>测试告警</span>
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <PaginationControls
        v-model:page="bindingPager.page"
        v-model:page-size="bindingPager.pageSize"
        :total="bindingPager.total"
        label="服务告警绑定"
      />
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
                <th>告警联系人</th>
                <th>状态</th>
                <th>创建时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="!channelsList.length">
                <td colspan="6" class="text-center text-muted" style="padding: 24px">
                  暂无通知渠道配置
                </td>
              </tr>
              <tr v-for="ch in channelPager.items" :key="ch.id">
                <td>
                  <strong>{{ ch.channelName }}</strong>
                </td>
                <td>
                  <span class="badge badge-info">{{ (ch.channelType || '').toUpperCase() }}</span>
                </td>
                <td class="channel-recipient-cell">
                  <ul class="recipient-summary">
                    <li v-for="r in channelRecipients(ch)" :key="r.id">
                      {{ r.value }} <small>{{ r.type }}</small>
                    </li>
                  </ul>
                  <span v-if="!channelRecipients(ch).length" class="text-muted">{{
                    ['http', 'webhook'].includes(ch.channelType) ? '由接口配置接收' : '未配置接收人'
                  }}</span>
                </td>
                <td>
                  <span :class="['badge', ch.enabled !== false ? 'badge-up' : 'badge-down']">
                    {{ ch.enabled !== false ? '正常启用' : '已禁用' }}
                  </span>
                </td>
                <td>{{ ch.createdAt || '-' }}</td>
                <td>
                  <div style="display: flex; gap: 6px">
                    <button class="btn btn-ghost btn-sm" @click="handleTestChannel(ch)">
                      <IconSvg name="send" size="12" />
                      <span>测试发送</span>
                    </button>
                    <button
                      class="btn btn-ghost btn-sm"
                      title="编辑渠道"
                      @click="openEditChannelModal(ch)"
                    >
                      <IconSvg name="edit" size="12" />
                    </button>
                    <button
                      class="btn btn-danger btn-sm"
                      title="删除渠道"
                      @click="handleDeleteChannel(ch)"
                    >
                      <IconSvg name="trash" size="12" />
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <PaginationControls
          v-model:page="channelPager.page"
          v-model:page-size="channelPager.pageSize"
          :total="channelPager.total"
          label="告警渠道"
        />
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
                <td colspan="5" class="text-center text-muted" style="padding: 24px">
                  暂无告警组配置
                </td>
              </tr>
              <tr v-for="g in groupPager.items" :key="g.id">
                <td>
                  <strong>{{ g.group_name }}</strong>
                </td>
                <td>{{ g.description || '-' }}</td>
                <td>{{ g.channels?.length || 0 }} 个通知渠道</td>
                <td>
                  <span :class="['badge', g.enabled !== false ? 'badge-up' : 'badge-down']">
                    {{ g.enabled !== false ? '启用' : '禁用' }}
                  </span>
                </td>
                <td>
                  <div style="display: flex; gap: 6px">
                    <button class="btn btn-ghost btn-sm" @click="openEditGroup(g)">编辑</button
                    ><button class="btn btn-ghost btn-sm" @click="testGroup(g)">测试</button
                    ><button class="btn btn-danger btn-sm" @click="handleDeleteGroup(g)">
                      <IconSvg name="trash" size="12" />
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <PaginationControls
          v-model:page="groupPager.page"
          v-model:page-size="groupPager.pageSize"
          :total="groupPager.total"
          label="告警组"
        />
      </div>
    </div>

    <!-- 新建 / 编辑通知渠道模态框 -->
    <div
      v-if="showChannelModal"
      class="modal-overlay"
      @keydown="modalKeydown($event, 'channel')"
      @click.self="!savingChannel && (showChannelModal = false)"
    >
      <div
        ref="channelDialog"
        class="modal-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="channel-dialog-title"
      >
        <div class="modal-header">
          <h3 id="channel-dialog-title" class="modal-title">
            {{ editingChannelId ? '编辑通知渠道' : '新建通知渠道' }}
          </h3>
          <button
            class="btn btn-ghost btn-sm"
            aria-label="关闭通知渠道"
            :disabled="savingChannel"
            @click="showChannelModal = false"
          >
            <IconSvg name="x" size="14" />
          </button>
        </div>
        <form @submit.prevent="handleSaveChannel">
          <div class="modal-body"><p class="form-hint">接收人修改会同步应用到引用此渠道的告警组、项目和服务。</p>
            <div class="form-group">
              <label class="form-label" for="alert-field-0">渠道名称</label>
              <input
                id="alert-field-0"
                v-model="channelForm.channelName"
                type="text"
                class="form-control"
                placeholder="例如：核心业务应急短信渠道"
                required
              />
            </div>

            <div class="form-group">
              <label class="form-label" for="alert-field-1">渠道类型</label>
              <select
                id="alert-field-1"
                v-model="channelForm.channelType"
                class="form-control"
                required
              >
                <option value="sms">短信 (SMS 网关)</option>
                <option value="email">邮件 (SMTP)</option>
                <option value="webhook">通用 Webhook</option>
                <option value="http">自定义 HTTP</option>
                <option value="dingtalk">钉钉群机器人</option>
                <option value="wecom">企业微信机器人</option>
              </select>
            </div>

            <!-- 短信网关配置 -->
            <div v-if="channelForm.channelType === 'sms'">
              <div class="form-group">
                <label class="form-label" for="alert-field-2">SMS 网关 URL</label>
                <input
                  id="alert-field-2"
                  v-model="channelForm.sms_api_url"
                  type="url"
                  class="form-control"
                  placeholder="http://127.0.0.1/smsSendServlet.htm"
                  required
                />
              </div>
              <div class="form-group">
                <label class="form-label" for="alert-field-3">网关用户名 (可选)</label>
                <input
                  id="alert-field-3"
                  v-model="channelForm.sms_username"
                  type="text"
                  class="form-control"
                  placeholder="xxzx"
                />
              </div>
              <RecipientListEditor
                v-model="channelForm.alert_mobile"
                label="短信接收人"
                kind="phone"
                placeholder="13800000000"
              />
            </div>

            <!-- 邮件配置 -->
            <div v-if="channelForm.channelType === 'email'">
              <div class="form-row">
                <div class="form-group">
                  <label class="form-label" for="alert-field-4">SMTP 主机</label>
                  <input
                    id="alert-field-4"
                    v-model="channelForm.smtp_host"
                    type="text"
                    class="form-control"
                    placeholder="smtp.example.com"
                  />
                </div>
                <div class="form-group">
                  <label class="form-label" for="alert-field-5">SMTP 端口</label>
                  <input
                    id="alert-field-5"
                    v-model.number="channelForm.smtp_port"
                    type="number"
                    class="form-control"
                    placeholder="465"
                  />
                </div>
              </div>
              <RecipientListEditor
                v-model="channelForm.alert_email"
                label="邮件接收人"
                kind="email"
                placeholder="ops@example.com"
              />
            </div>

            <!-- Webhook / 钉钉 / 企微配置 -->
            <div v-if="['webhook', 'dingtalk', 'wecom'].includes(channelForm.channelType)">
              <div class="form-group">
                <label class="form-label" for="alert-field-6">Webhook URL</label>
                <input
                  id="alert-field-6"
                  v-model="channelForm.webhook_url"
                  type="url"
                  class="form-control"
                  placeholder="https://oapi.example.com/robot/send"
                  required
                />
              </div>
            </div>
            <ChannelAdvancedFields :form="channelForm" />
            <p v-if="channelError" role="alert" class="settings-error">{{ channelError }}</p>
          </div>
          <div class="modal-footer">
            <button
              type="button"
              class="btn btn-ghost"
              :disabled="savingChannel"
              @click="showChannelModal = false"
            >
              取消
            </button>
            <button type="submit" class="btn btn-primary" :disabled="savingChannel">
              {{ savingChannel ? '保存中…' : '保存渠道' }}
            </button>
          </div>
        </form>
      </div>
    </div>

    <!-- 新建告警组模态框 -->
    <div
      v-if="showGroupModal"
      class="modal-overlay"
      @keydown="modalKeydown($event, 'group')"
      @click.self="!savingGroup && (showGroupModal = false)"
    >
      <div
        ref="groupDialog"
        class="modal-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="group-dialog-title"
      >
        <div class="modal-header">
          <h3 id="group-dialog-title" class="modal-title">
            {{ editingGroupId ? '编辑告警组' : '新建告警组' }}
          </h3>
          <button
            class="btn btn-ghost btn-sm"
            aria-label="关闭告警组"
            :disabled="savingGroup"
            @click="showGroupModal = false"
          >
            <IconSvg name="x" size="14" />
          </button>
        </div>
        <form @submit.prevent="handleSaveGroup">
          <div class="modal-body">
            <div class="form-group">
              <label class="form-label" for="alert-field-7">告警组名称</label>
              <input
                id="alert-field-7"
                v-model="groupForm.groupName"
                type="text"
                class="form-control"
                placeholder="例如：核心交易应急组"
                required
              />
            </div>
            <div class="form-group">
              <label class="form-label" for="alert-field-8">说明描述</label>
              <input
                id="alert-field-8"
                v-model="groupForm.description"
                type="text"
                class="form-control"
                placeholder="生产核心系统运维组"
              />
            </div>
            <div class="form-group">
              <label class="form-label">勾选绑定的通知渠道</label>
              <div style="display: flex; flex-direction: column; gap: 8px">
                <label
                  v-for="ch in channelsList"
                  :key="ch.id"
                  style="
                    display: flex;
                    align-items: center;
                    gap: 6px;
                    font-size: 13px;
                    cursor: pointer;
                  "
                >
                  <input type="checkbox" :value="ch.id" v-model="groupForm.channelIds" :aria-label="ch.channelName + ' (' + (ch.channelType || '').toUpperCase() + ')'" />
                  <span>{{ ch.channelName }} ({{ (ch.channelType || '').toUpperCase() }})<small class="channel-choice-recipients">#{{ ch.id }} · {{ channelRecipients(ch).map(r=>r.value).join('、') || '未配置接收人 / 由接口配置接收' }}</small></span>
                </label>
              </div>
            </div>
            <fieldset class="settings-fieldset">
              <legend>触发策略</legend>
              <label v-for="policy in policiesList" :key="policy.id" style="display: block"
                ><input type="checkbox" v-model="groupForm.policyIds" :value="policy.id" />{{
                  policy.policy_name
                }}
                · {{ policy.trigger_type }} · {{ policy.trigger_value }}</label
              >
            </fieldset>
            <label class="option-switch"
              ><input v-model="groupForm.enabled" type="checkbox" />启用告警组</label
            >
            <p v-if="groupError" role="alert" class="settings-error">{{ groupError }}</p>
          </div>
          <div class="modal-footer">
            <button
              type="button"
              class="btn btn-ghost"
              :disabled="savingGroup"
              @click="showGroupModal = false"
            >
              取消
            </button>
            <button type="submit" class="btn btn-primary" :disabled="savingGroup">
              {{ savingGroup ? '保存中…' : '保存告警组' }}
            </button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>

<script setup>
import PaginationControls from '../components/PaginationControls.vue'
import { usePagination } from '../composables/usePagination'
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { api } from '../api'
import { store } from '../store'
import ChannelAdvancedFields from '../components/ChannelAdvancedFields.vue'
import RecipientListEditor from '../components/RecipientListEditor.vue'
import ExternalProjectsView from './ExternalProjectsView.vue'
import { channelRecipients, groupChannelIds } from '../utils/alertRecipients'

const currentTab = ref('bindings')
const loading = ref(false),
  loadError = ref(''),
  projectList = ref([]),
  contactSearch = ref('')
const savingChannel = ref(false),
  savingGroup = ref(false),
  channelError = ref(''),
  groupError = ref('')
const channelDialog = ref(null),
  groupDialog = ref(null)
const serviceSearch = ref('')
const servicesList = ref([])
const channelsList = ref([])
const groupsList = ref([])

const showChannelModal = ref(false)
const editingChannelId = ref(null)
const showGroupModal = ref(false)
const editingGroupId = ref(null)
const policiesList = ref([])
let modalFocus = null,
  bodyOverflow = ''
watch(
  () => showChannelModal.value || showGroupModal.value,
  async (open) => {
    if (open) {
      modalFocus = document.activeElement
      bodyOverflow = document.body.style.overflow
      document.body.style.overflow = 'hidden'
      await nextTick()
      ;(channelDialog.value || groupDialog.value)?.querySelector('button')?.focus()
    } else {
      document.body.style.overflow = bodyOverflow
      if (modalFocus?.isConnected) modalFocus.focus()
    }
  }
)
onBeforeUnmount(() => {
  if (showChannelModal.value || showGroupModal.value) document.body.style.overflow = bodyOverflow
})
function modalKeydown(event, type) {
  const saving = type === 'channel' ? savingChannel.value : savingGroup.value
  if (event.key === 'Escape' && !saving) {
    event.preventDefault()
    event.stopPropagation()
    if (type === 'channel') showChannelModal.value = false
    else showGroupModal.value = false
  }
  if (event.key === 'Tab') {
    const dialog = type === 'channel' ? channelDialog.value : groupDialog.value
    const items = [...dialog.querySelectorAll('button,input,select,textarea,a[href]')].filter(
        (el) => !el.disabled && el.getClientRects().length
      ),
      first = items[0],
      last = items.at(-1)
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last?.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first?.focus()
    }
  }
}

const channelDefaults = () => ({
  channelName: '',
  channelType: 'sms',
  enabled: true,
  sms_api_url: '',
  sms_api_urls: [],
  sms_username: '',
  sms_password: '',
  sms_api_token: '',
  alert_mobile: '',
  sms_password_is_md5: true,
  sms_rstype: 'text',
  sms_ext_code: '',
  smtp_host: '',
  smtp_port: 465,
  smtp_user: '',
  smtp_password: '',
  smtp_from: '',
  alert_email: '',
  alert_cc: '',
  smtp_auth: true,
  smtp_use_tls: false,
  smtp_use_ssl: true,
  smtp_ssl_trust: '',
  webhook_url: '',
  http_method: 'POST',
  http_headers: '',
  http_body: '',
  dingtalk_secret: '',
  dingtalk_at_mobiles: '',
  dingtalk_at_all: false,
  wecom_mentioned_list: '',
  wecom_mentioned_mobiles: '',
  wecom_at_all: false,
  proxy_type: 'none',
  proxy_host: '',
  proxy_port: null,
  proxy_username: '',
  proxy_password: '',
})
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
  enabled: true,
})

const groupForm = reactive({
  groupName: '',
  description: '',
  channelIds: [],
  policyIds: [],
  enabled: true,
})

// 缓存服务临时修改的告警组勾选
const serviceGroupChanges = reactive({})

const filteredBindingServices = computed(() => {
  if (!serviceSearch.value.trim()) return servicesList.value
  const q = serviceSearch.value.trim().toLowerCase()
  return servicesList.value.filter(
    (s) =>
      (s.service_name || '').toLowerCase().includes(q) ||
      (s.cluster_name || '').toLowerCase().includes(q)
  )
})

const bindingPager = usePagination(filteredBindingServices, [serviceSearch])
const contacts = computed(() =>
  channelsList.value.flatMap((channel) =>
    channelRecipients(channel).map((contact) => {
      const groups = groupsList.value.filter((group) =>
          groupChannelIds(group).includes(String(channel.id))
        ),
        ids = new Set(groups.map((g) => String(g.id)))
      return {
        ...contact,
        channel,
        groups,
        projects: projectList.value.filter((project) =>
          (project.alert_group_ids || []).some((id) => ids.has(String(id)))
        ),
      }
    })
  )
)
const filteredContacts = computed(() =>
  contacts.value.filter((c) =>
    [
      c.value,
      c.type,
      c.channel.channelName,
      ...c.groups.map((g) => g.group_name),
      ...c.projects.map((p) => p.project_name),
    ]
      .join(' ')
      .toLowerCase()
      .includes(contactSearch.value.toLowerCase())
  )
)
const contactPager = usePagination(filteredContacts, [contactSearch])
const channelPager = usePagination(channelsList)
const groupPager = usePagination(groupsList)

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
    serviceGroupChanges[srv.id] = serviceGroupChanges[srv.id].filter((id) => id !== groupId)
  }
}

const handleSaveServiceBindings = async (srv) => {
  const groupIds =
    serviceGroupChanges[srv.id] !== undefined
      ? serviceGroupChanges[srv.id]
      : srv.alert_group_ids || (srv.alert_group_id ? [srv.alert_group_id] : [])
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
  loading.value = true
  loadError.value = ''
  const sources = [
    ['服务', servicesList, () => api.listServices(true)],
    ['通知渠道', channelsList, () => api.listAlertChannels(true)],
    ['告警组', groupsList, () => api.listAlertGroups(true)],
    ['触发策略', policiesList, () => api.listAlertPolicies(true)],
    ['项目', projectList, () => api.externalProjects()],
  ]
  const results = await Promise.allSettled(sources.map(([, , read]) => read()))
  const errors = []
  results.forEach((result, index) => {
    if (result.status === 'fulfilled') sources[index][1].value = result.value || []
    else errors.push(sources[index][0] + '加载失败：' + result.reason.message)
  })
  loadError.value = errors.join('；')
  loading.value = false
}

const openCreateChannelModal = () => {
  channelError.value = ''
  editingChannelId.value = null
  Object.assign(channelForm, channelDefaults())
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
    enabled: true,
  })
  showChannelModal.value = true
}

const openEditChannelModal = (ch) => {
  channelError.value = ''
  editingChannelId.value = ch.id
  Object.assign(channelForm, channelDefaults(), ch)
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
    enabled: ch.enabled !== false,
  })
  showChannelModal.value = true
}

const handleSaveChannel = async () => {
  if (savingChannel.value) return
  savingChannel.value = true
  channelError.value = ''
  try {
    if (editingChannelId.value) {
      await api.updateAlertChannel(
        editingChannelId.value,
        Object.fromEntries(Object.keys(channelDefaults()).map((key) => [key, channelForm[key]]))
      )
      store.showToast('告警渠道已更新', 'success')
    } else {
      await api.createAlertChannel(
        Object.fromEntries(Object.keys(channelDefaults()).map((key) => [key, channelForm[key]]))
      )
      store.showToast('告警渠道已创建', 'success')
    }
    showChannelModal.value = false
    await fetchData()
  } catch (err) {
    channelError.value = '保存渠道失败：' + err.message
    store.showToast(channelError.value, 'error')
  } finally {
    savingChannel.value = false
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
  groupError.value = ''
  editingGroupId.value = null
  Object.assign(groupForm, {
    groupName: '',
    description: '',
    channelIds: [],
    policyIds: [],
    enabled: true,
  })
  groupForm.policyIds = policiesList.value.filter((p) => p.enabled).map((p) => p.id)
  showGroupModal.value = true
}

const openEditGroup = async (group) => {
  try {
    const detail = await api.getAlertGroup(group.id)
    groupError.value = ''
    editingGroupId.value = group.id
    Object.assign(groupForm, {
      groupName: detail.group_name,
      description: detail.description || '',
      channelIds: detail.channel_ids || detail.channels?.map((c) => c.id) || [],
      policyIds: detail.policy_ids || detail.policies?.map((p) => p.id) || [],
      enabled: detail.enabled !== false,
    })
    showGroupModal.value = true
  } catch (e) {
    store.showToast(e.message, 'error')
  }
}
const testGroup = async (group) => {
  try {
    const result = await api.testAlertGroup(group.id)
    store.showToast(
      result.success
        ? '组内启用渠道测试均成功'
        : '组内有渠道测试失败或没有启用渠道：' +
            result.results
              .filter((r) => !r.success)
              .map((r) => r.message)
              .join('；'),
      result.success ? 'success' : 'error'
    )
  } catch (e) {
    store.showToast(e.message, 'error')
  }
}
const handleSaveGroup = async () => {
  if (savingGroup.value) return
  savingGroup.value = true
  groupError.value = ''
  try {
    if (editingGroupId.value) await api.updateAlertGroup(editingGroupId.value, groupForm)
    else await api.createAlertGroup(groupForm)
    store.showToast('告警组已保存', 'success')
    showGroupModal.value = false
    await fetchData()
  } catch (err) {
    groupError.value = '保存告警组失败：' + err.message
    store.showToast(groupError.value, 'error')
  } finally {
    savingGroup.value = false
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

<style scoped>
.alert-settings-page {
  background: #f6f9fd;
}
.alert-settings-page .page-header {
  margin-bottom: 20px;
}
.alert-settings-page .page-header h1 {
  font-size: 25px;
}
.alert-settings-page .page-header p {
  font-size: 12px;
  color: #718398;
}
.tabs-nav {
  gap: 6px;
  flex-wrap: wrap;
}
.tab-btn {
  padding: 12px 18px;
  border-radius: 6px 6px 0 0;
  font-size: 13px;
}
.tab-btn.active {
  background: #edf6ff;
}
.alert-settings-page .card {
  border-color: #dce7f4;
  border-radius: 8px;
  padding: 20px;
}
.alert-settings-page .card-header {
  padding-bottom: 14px;
  margin-bottom: 14px;
}
.alert-settings-page .table th {
  background: #f4f8fd;
  color: #647a91;
  font-size: 12px;
}
.alert-settings-page .table td {
  font-size: 12px;
  vertical-align: top;
}
.recipient-summary {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 5px;
  max-height: 110px;
  overflow: auto;
}
.recipient-summary li {
  overflow-wrap: anywhere;
}
.recipient-summary small {
  color: #8193a7;
  font-size: 10px;
}
.channel-recipient-cell {
  min-width: 180px;
  max-width: 280px;
}
.settings-error {
  background: #fff1ed;
  border: 1px solid #f4c5bc;
  color: #b1483c;
  padding: 10px 12px;
  border-radius: 5px;
  font-size: 12px;
  line-height: 1.6;
}
.settings-explainer {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  padding: 14px 16px;
  background: #eef6ff;
  border: 1px solid #dbe9fb;
  border-radius: 7px;
  margin-bottom: 14px;
  font-size: 12px;
  color: #58738e;
}
.settings-explainer strong {
  color: #234d74;
}
.settings-explainer button {
  margin-left: auto;
}
.contact-search {
  display: flex;
  flex-direction: column;
  gap: 7px;
  font-size: 12px;
  color: #64748b;
  width: 340px;
  max-width: 100%;
  margin-bottom: 16px;
}
.contact-address {
  font-weight: 600;
  color: #214d70;
  max-width: 260px;
  overflow-wrap: anywhere;
}
.contacts-panel td {
  max-width: 260px;
  overflow-wrap: anywhere;
}
.alert-settings-page .modal-dialog {
  max-width: 800px;
  overflow: hidden;
  border-radius: 12px;
}
.alert-settings-page .modal-dialog > form {
  flex: 1;
  min-height: 0;
}
.alert-settings-page .modal-body {
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding: 22px 24px;
  overflow-y: auto;
}
.alert-settings-page .modal-body > .form-group,
.alert-settings-page .modal-body .form-group {
  margin: 0;
  min-width: 0;
}
.alert-settings-page .modal-body > div:not(.form-group) {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.alert-settings-page .modal-body .form-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
}
.alert-settings-page .modal-body .form-label {
  font-size: 12px;
  display: block;
  margin-bottom: 8px;
}
.alert-settings-page .modal-body .form-control {
  min-width: 0;
  width: 100%;
  box-sizing: border-box;
  height: 40px;
}
.alert-settings-page .modal-header {
  padding: 18px 24px;
  background: #fff;
}
.alert-settings-page .modal-footer {
  padding: 14px 24px;
  gap: 10px;
}
.settings-fieldset {
  margin: 0;
  border: 1px solid #dce7f4;
  border-radius: 8px;
  padding: 12px 14px;
  min-width: 0;
}
.settings-fieldset legend {
  font-size: 12px;
  color: #526d85;
  padding: 0 5px;
}
.settings-fieldset label {
  display: flex !important;
  align-items: center;
  gap: 9px;
  padding: 8px 0;
  font-size: 12px;
  color: #52677d;
  line-height: 1.6;
}
.option-switch {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}
.alert-settings-page input[type='checkbox'] {
  width: 16px;
  height: 16px;
  margin: 0;
  accent-color: #0284c7;
  flex: none;
}
.alert-settings-page .modal-body .form-group > div label {
  padding: 10px 12px;
  background: #f7faff;
  border: 1px solid #e0e9f5;
  border-radius: 6px;
}
.alert-settings-page .modal-body .form-group > div {
  gap: 8px;
}
.alert-settings-page .modal-body .form-group > div label span {
  overflow-wrap: anywhere;
}
@media (max-width: 640px) {
  .alert-settings-page .card {
    padding: 14px;
  }
  .alert-settings-page .modal-overlay {
    padding: 10px;
  }
  .alert-settings-page .modal-dialog {
    max-height: calc(100dvh - 20px);
  }
  .alert-settings-page .modal-body {
    padding: 16px;
    gap: 16px;
  }
  .alert-settings-page .modal-body .form-row {
    grid-template-columns: 1fr;
  }
  .alert-settings-page .modal-header,
  .alert-settings-page .modal-footer {
    padding: 14px 16px;
  }
  .tab-btn {
    padding: 10px 12px;
  }
  .settings-explainer button {
    margin-left: 0;
  }
}
</style>

<style scoped>
.channel-choice-recipients{display:block;margin-top:4px;color:#7a8fa3;font-size:11px;font-weight:400;line-height:1.5;overflow-wrap:anywhere}
</style>
