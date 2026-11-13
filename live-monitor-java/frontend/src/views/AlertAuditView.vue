<template>
  <div class="page-container alert-audit-page">
    <div class="page-header">
      <div class="page-header-title">
        <h1>告警通知审计流水</h1>
        <p>每个渠道发送任务计一条通知，重试不重复计数。渠道受理成功不代表已送达收件箱或已读。</p>
      </div>
      <div>
        <button class="btn btn-ghost btn-sm" @click="fetchLogs" :disabled="loading" title="刷新审计流水">
          <IconSvg name="refresh" size="14" />
          <span>刷新</span>
        </button>
      </div>
    </div>

    <!-- 统计指标卡片 -->
    <div class="grid-4" style="margin-bottom: 20px;">
      <div class="card" style="margin: 0; padding: 16px;">
        <span class="text-muted" style="font-size: 12px;">今日通知及未发送记录</span>
        <div style="font-size: 22px; font-weight: 700; margin-top: 4px;">{{ stats.today_total }}</div>
      </div>
      <div class="card" style="margin: 0; padding: 16px;">
        <span class="text-muted" style="font-size: 12px;">今日渠道已受理</span>
        <div style="font-size: 22px; font-weight: 700; color: #16a34a; margin-top: 4px;">{{ stats.today_success }}</div>
      </div>
      <div class="card" style="margin: 0; padding: 16px;">
        <span class="text-muted" style="font-size: 12px;">今日最终失败</span>
        <div style="font-size: 22px; font-weight: 700; color: #dc2626; margin-top: 4px;">{{ stats.today_failed }}</div>
      </div>
      <div class="card" style="margin: 0; padding: 16px;">
        <span class="text-muted" style="font-size: 12px;">当前筛选总数</span>
        <div style="font-size: 22px; font-weight: 700; color: #0284c7; margin-top: 4px;">{{ total }}</div>
      </div>
    </div>

    <div class="audit-explanation" role="status">今日处理中 {{ stats.today_pending }} 条 · 未发送（配置阻塞）{{ stats.today_blocked }} 条。未绑定告警组或没有启用渠道时不会发送消息，请在服务配置中检查接收组。</div>

    <!-- ECharts 趋势图卡片 -->
    <details class="card audit-trend" @toggle="toggleTrend">
      <summary>通知趋势 <span class="text-muted">展开查看每日记录变化</span></summary>
      <div class="audit-trend-content">
      <div class="card-header">
        <div class="card-title">
          <IconSvg name="activity" size="16" />
          <span>每日通知及未发送记录趋势</span>
        </div>
        <div style="display: flex; gap: 6px;">
          <button
            class="btn btn-ghost btn-sm"
            :class="{ active: trendDays === 7 }"
            @click="changeTrendDays(7)"
          >
            最近 7 天
          </button>
          <button
            class="btn btn-ghost btn-sm"
            :class="{ active: trendDays === 14 }"
            @click="changeTrendDays(14)"
          >
            最近 14 天
          </button>
          <button
            class="btn btn-ghost btn-sm"
            :class="{ active: trendDays === 30 }"
            @click="changeTrendDays(30)"
          >
            最近 30 天
          </button>
        </div>
      </div>
      <div ref="trendChartContainer" style="width: 100%; height: 220px;"></div>
      <p v-if="trendFailed" class="form-hint" role="status">趋势暂不可用，请刷新重试。</p>
      </div>
    </details>

    <!-- 审计流水表格 -->
    <div class="card">
      <div class="card-header" style="flex-wrap: wrap; gap: 10px;">
        <div class="card-title">
          <IconSvg name="activity" size="16" />
          <span>通知审计流水</span>
        </div>
        <div class="audit-filter-controls">
<label>开始<input v-model="startTime" type="datetime-local" class="form-control" @change="page = 1; fetchLogs()"></label><label>结束<input v-model="endTime" type="datetime-local" class="form-control" @change="page = 1; fetchLogs()"></label>
          <select v-model="statusFilter" aria-label="通知状态" class="form-control" style="width: 150px;" @change="page = 1; fetchLogs()">
            <option value="">全部状态</option>
            <option value="success">渠道已受理</option>
            <option value="failed">最终失败</option><option value="pending">排队中</option><option value="retrying">重试中</option><option value="sending">发送中</option><option value="blocked">未发送（配置阻塞）</option>
          </select>
          <input
            v-model="keyword"
            aria-label="搜索通知"
            type="text"
            class="form-control"
            placeholder="搜索服务 / 渠道 / 内容 / 原因..."
            style="width: 220px;"
            @input="handleSearch"
          />
          <button class="btn btn-ghost" :disabled="loading" @click="resetFilters">重置筛选</button>
        </div>
      </div>

      <div class="table-wrap">
        <table class="table" :aria-busy="loading">
          <thead>
            <tr>
              <th>通知时间</th>
              <th>服务名称</th>
              <th>渠道 / 当前接收目标</th>
              <th>告警类型</th>
              <th>状态</th>
              <th>通知内容 / 响应详情</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!logList.length">
              <td colspan="6" class="text-center text-muted" style="padding: 36px;">
                {{ loading ? '正在加载通知记录…' : '暂无符合筛选条件的通知记录' }}
              </td>
            </tr>
            <tr v-for="item in logList" :key="item.task_id || item.taskId || item.id">
              <td style="font-family: monospace; font-size: 12px;">{{ formatTime(item.createdAt || item.created_at) }}</td>
              <td><strong>{{ item.serviceName || item.service_name || '-' }}</strong></td>
              <td><div>{{ item.channel_name || item.channelName || '未记录渠道' }}</div><div class="text-muted audit-target">{{ item.current_recipients || item.currentRecipients || '接收目标未记录' }}</div></td>
              <td><span class="badge badge-info">{{ item.alertType || item.alert_type || item.alertKey || '-' }}</span></td>
              <td>
                <span :class="['badge', statusOf(item) === 'success' ? 'badge-up' : ['pending','retrying','sending'].includes(statusOf(item)) ? 'badge-info' : 'badge-down']">
                  {{ statusLabel(item) }}
                </span>
              </td>
              <td class="audit-details">
                <div v-if="item.last_error || item.lastError" class="audit-error">{{ item.last_error || item.lastError }}</div>
                <div v-if="statusOf(item) === 'blocked'" class="audit-error">{{ blockedReason(item) }}</div>
                <div v-if="item.task_id || item.taskId" class="text-muted">任务 {{ item.task_id || item.taskId }} · 尝试 {{ item.attempt_count ?? item.attemptCount ?? 0 }} 次</div>
                <div v-if="statusOf(item) === 'retrying'" class="text-muted">下次重试：{{ retryTime(item) }}</div>
                <details><summary>查看完整消息与处理说明</summary><pre>{{ item.notify_message || item.notifyMessage || item.message || item.content || '-' }}</pre><p v-if="statusOf(item) === 'success'">渠道已受理请求。邮件表示 SMTP 服务器接受邮件，仍需检查收件地址、垃圾邮件及退信；系统未取得收件人送达或已读回执。</p><p v-if="!item.task_id && !item.taskId">历史记录未关联发送任务，无法核对当时接收目标。</p><p v-else>接收目标显示当前渠道配置；历史任务未保存发送时的接收目标快照。</p></details>
                <router-link v-if="item.task_id || item.taskId" :to="{ path: '/alerts/tasks', query: { service_id: item.service_id || item.serviceId, channel_id: item.channel_id || item.channelId } }">查看发送任务与重试</router-link>
                <router-link v-if="statusOf(item) === 'blocked' && (item.service_id || item.serviceId)" :to="'/services/' + (item.service_id || item.serviceId) + '/edit'">检查服务告警配置</router-link>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <PaginationControls v-model:page="page" v-model:page-size="pageSize" :total="total" :loading="loading" label="通知审计" @change="fetchLogs" />
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, nextTick, onMounted, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts'
import PaginationControls from '../components/PaginationControls.vue'
import { api } from '../api'
import { store } from '../store'

const startTime = ref(''), endTime = ref('')
const loading = ref(false)
const logList = ref([])
const page = ref(1)
const pageSize = ref(10)
const total = ref(0)
const statusFilter = ref('')
const keyword = ref('')
const trendDays = ref(14)
const trendFailed = ref(false), lastTrendData = ref([])
async function toggleTrend(event) {
  if (event.target.open) { await nextTick(); if (!trendChart) initTrendChart(); else trendChart.resize() }
}
function resetFilters() { clearTimeout(searchTimer); startTime.value = ''; endTime.value = ''; statusFilter.value = ''; keyword.value = ''; page.value = 1; fetchLogs() }

const stats = reactive({
  today_total: 0,
  today_success: 0,
  today_failed: 0, today_pending: 0, today_blocked: 0
})

const statusOf = item => item.notify_status || item.notifyStatus || item.status
const statusLabel = item => ({ success: '渠道已受理', failed: '最终失败', pending: '排队中', retrying: '重试中', sending: '发送中', blocked: '未发送（配置阻塞）' }[statusOf(item)] || '未知状态')
const blockedReason = item => {
  const message = item.notify_message || item.notifyMessage || ''
  if (message.includes('no alert group bound')) return '未绑定告警组：没有接收渠道，消息未发送。'
  if (message.includes('alert group is disabled')) return '告警组已停用：消息未发送。'
  if (message.includes('no enabled alert channels')) return '没有启用的告警渠道：消息未发送。'
  return message
}
const retryTime = item => { const timestamp = item.next_retry_at ?? item.nextRetryAt; return timestamp ? new Date(Number(timestamp)).toLocaleString() : '-' }

const trendChartContainer = ref(null)
let trendChart = null

const totalPages = computed(() => Math.ceil(total.value / pageSize.value))

const formatTime = (val) => {
  if (!val) return '-'
  if (typeof val === 'string') return val.replace('T', ' ').slice(0, 19)
  return String(val)
}

const fetchLogs = async () => {
  loading.value = true
  trendFailed.value = false
  try {
    const [res, trendRes] = await Promise.all([
      api.alertNotifications({
        page: page.value,
        page_size: pageSize.value,
        start_time: startTime.value.replace('T', ' ') || undefined, end_time: endTime.value.replace('T', ' ') || undefined,
        status: statusFilter.value || undefined,
        query: keyword.value.trim() || undefined
      }),
      api.alertNotificationsTrend(trendDays.value).catch(() => { trendFailed.value = true; return [] })
    ])
    page.value = res?.page || page.value
    logList.value = res?.items || res?.records || []
    total.value = res?.total != null ? res.total : logList.value.length

    // 统计更新
    stats.today_total = res?.today_total ?? res?.todayTotal ?? 0
    stats.today_success = res?.today_success ?? res?.todaySuccess ?? 0
    stats.today_failed = res?.today_failed ?? res?.todayFailed ?? 0
    stats.today_pending = res?.today_pending ?? res?.todayPending ?? 0
    stats.today_blocked = res?.today_blocked ?? res?.todayBlocked ?? 0

    updateTrendChart(trendRes)
  } catch (err) {
    if (!err.message?.includes('未授权')) {
      store.showToast('加载审计日志失败：' + err.message, 'error')
    }
  } finally {
    loading.value = false
  }
}

let searchTimer = null
const handleSearch = () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    page.value = 1
    fetchLogs()
  }, 400)
}

const changeTrendDays = async (days) => {
  trendDays.value = days
  trendFailed.value = false
  try {
    const trendRes = await api.alertNotificationsTrend(days)
    updateTrendChart(trendRes)
  } catch (_) { trendFailed.value = true }
}

const initTrendChart = () => {
  if (!trendChartContainer.value) return
  trendChart = echarts.init(trendChartContainer.value)
  updateTrendChart(lastTrendData.value)
}

const updateTrendChart = (trendData = []) => {
  lastTrendData.value = trendData
  if (!trendChart) return
  let dates = []
  let counts = []
  if (Array.isArray(trendData) && trendData.length) {
    dates = trendData.map(d => d.date || d.day || '')
    counts = trendData.map(d => Number(d.count || d.total || 0))
  } else {
    for (let i = trendDays.value - 1; i >= 0; i--) {
      const d = new Date()
      d.setDate(d.getDate() - i)
      dates.push(`${d.getMonth() + 1}/${d.getDate()}`)
      counts.push(0)
    }
  }

  trendChart.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: '4%', right: '4%', top: '15%', bottom: '12%', containLabel: true },
    xAxis: { type: 'category', data: dates, axisLabel: { fontSize: 11 } },
    yAxis: { type: 'value', minInterval: 1, axisLabel: { fontSize: 11 } },
    series: [{
      name: '通知及未发送记录',
      type: 'line',
      smooth: true,
      data: counts,
      areaStyle: { opacity: 0.15, color: '#0284c7' },
      color: '#0284c7'
    }]
  })
}

const handleResize = () => {
  if (trendChart) trendChart.resize()
}

onMounted(async () => {
  await fetchLogs()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  clearTimeout(searchTimer)
  window.removeEventListener('resize', handleResize)
  if (trendChart) trendChart.dispose()
})
</script>

<style scoped>
.audit-trend summary { cursor: pointer; font-weight: 600; }
.audit-trend summary span { font-weight: 400; font-size: 12px; margin-left: 8px; }
.audit-trend-content { padding-top: 16px; }
.audit-filter-controls { display: flex; align-items: end; flex-wrap: wrap; gap: 10px; }
.audit-filter-controls label { display: flex; flex-direction: column; gap: 6px; font-size: 13px; }
.audit-explanation { margin-bottom: 20px; padding: 12px 16px; background: #eff6ff; color: #334155; border-radius: 8px; }
.audit-target { max-width: 240px; overflow-wrap: anywhere; font-size: 12px; }
.audit-details { min-width: 260px; max-width: 520px; white-space: normal; overflow-wrap: anywhere; }
.audit-error { color: #b91c1c; margin-bottom: 6px; white-space: pre-wrap; }
.audit-details pre { white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; max-height: 320px; overflow: auto; }
.audit-details summary { cursor: pointer; margin: 8px 0; color: #0284c7; }
.audit-details a { display: inline-block; margin-top: 6px; }
</style>
