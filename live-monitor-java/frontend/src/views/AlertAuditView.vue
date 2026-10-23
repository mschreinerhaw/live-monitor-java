<template>
  <div class="page-container alert-audit-page">
    <div class="page-header">
      <div class="page-header-title">
        <h1>告警通知审计流水</h1>
        <p>系统对外发送的全部告警与事件通知日志，详细记录投递状态、接收人与失败原因</p>
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
        <span class="text-muted" style="font-size: 12px;">今日通知总数</span>
        <div style="font-size: 22px; font-weight: 700; margin-top: 4px;">{{ stats.today_total }}</div>
      </div>
      <div class="card" style="margin: 0; padding: 16px;">
        <span class="text-muted" style="font-size: 12px;">今日发送成功</span>
        <div style="font-size: 22px; font-weight: 700; color: #16a34a; margin-top: 4px;">{{ stats.today_success }}</div>
      </div>
      <div class="card" style="margin: 0; padding: 16px;">
        <span class="text-muted" style="font-size: 12px;">今日发送失败</span>
        <div style="font-size: 22px; font-weight: 700; color: #dc2626; margin-top: 4px;">{{ stats.today_failed }}</div>
      </div>
      <div class="card" style="margin: 0; padding: 16px;">
        <span class="text-muted" style="font-size: 12px;">当前筛选总数</span>
        <div style="font-size: 22px; font-weight: 700; color: #0284c7; margin-top: 4px;">{{ total }}</div>
      </div>
    </div>

    <!-- ECharts 趋势图卡片 -->
    <div class="card" style="margin-bottom: 20px;">
      <div class="card-header">
        <div class="card-title">
          <IconSvg name="activity" size="16" />
          <span>每日发送量走势趋势</span>
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
    </div>

    <!-- 审计流水表格 -->
    <div class="card">
      <div class="card-header" style="flex-wrap: wrap; gap: 10px;">
        <div class="card-title">
          <IconSvg name="activity" size="16" />
          <span>通知审计流水</span>
        </div>
        <div style="display: flex; gap: 10px; align-items: center; flex-wrap: wrap;">
          <select v-model="statusFilter" class="form-control" style="width: 130px;" @change="fetchLogs">
            <option value="">全部状态</option>
            <option value="success">发送成功</option>
            <option value="failed">发送失败</option>
          </select>
          <input
            v-model="keyword"
            type="text"
            class="form-control"
            placeholder="搜索服务 / 接收人 / 内容..."
            style="width: 220px;"
            @input="handleSearch"
          />
        </div>
      </div>

      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>通知时间</th>
              <th>服务名称</th>
              <th>集群分组</th>
              <th>告警类型</th>
              <th>状态</th>
              <th>通知内容 / 响应详情</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!logList.length">
              <td colspan="6" class="text-center text-muted" style="padding: 36px;">
                暂无告警通知审计记录
              </td>
            </tr>
            <tr v-for="item in logList" :key="item.id">
              <td style="font-family: monospace; font-size: 12px;">{{ formatTime(item.createdAt || item.created_at) }}</td>
              <td><strong>{{ item.serviceName || item.service_name || '-' }}</strong></td>
              <td>{{ item.clusterName || item.cluster_name || '-' }}</td>
              <td><span class="badge badge-info">{{ item.alertType || item.alert_type || item.alertKey || '-' }}</span></td>
              <td>
                <span :class="['badge', (item.notifyStatus || item.status) === 'success' ? 'badge-up' : 'badge-down']">
                  {{ (item.notifyStatus || item.status) === 'success' ? '成功' : '失败' }}
                </span>
              </td>
              <td style="max-width: 360px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;" :title="item.notifyMessage || item.message || item.content">
                {{ item.notifyMessage || item.message || item.content || '-' }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 分页控制 -->
      <div style="display: flex; justify-content: space-between; align-items: center; margin-top: 16px;">
        <span class="text-muted" style="font-size: 12px;">第 {{ page }} / {{ totalPages || 1 }} 页，共 {{ total }} 条记录</span>
        <div style="display: flex; gap: 8px;">
          <button class="btn btn-ghost btn-sm" :disabled="page <= 1" @click="page--; fetchLogs()">上一页</button>
          <button class="btn btn-ghost btn-sm" :disabled="page >= totalPages" @click="page++; fetchLogs()">下一页</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts'
import { api } from '../api'
import { store } from '../store'

const loading = ref(false)
const logList = ref([])
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const statusFilter = ref('')
const keyword = ref('')
const trendDays = ref(14)

const stats = reactive({
  today_total: 0,
  today_success: 0,
  today_failed: 0
})

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
  try {
    const [res, trendRes] = await Promise.all([
      api.alertNotifications({
        page: page.value,
        page_size: pageSize.value,
        status: statusFilter.value || undefined,
        query: keyword.value.trim() || undefined
      }),
      api.alertNotificationsTrend(trendDays.value).catch(() => [])
    ])
    logList.value = res?.items || res?.records || []
    total.value = res?.total != null ? res.total : logList.value.length

    // 统计更新
    stats.today_total = res?.todayTotal != null ? res.todayTotal : (res?.today_total != null ? res.today_total : total.value)
    stats.today_success = res?.todaySuccess != null ? res.todaySuccess : (res?.today_success != null ? res.today_success : logList.value.filter(i => (i.notifyStatus || i.status) === 'success').length)
    stats.today_failed = res?.todayFailed != null ? res.todayFailed : (res?.today_failed != null ? res.today_failed : (stats.today_total - stats.today_success))

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
  try {
    const trendRes = await api.alertNotificationsTrend(days)
    updateTrendChart(trendRes)
  } catch (_) {}
}

const initTrendChart = () => {
  if (!trendChartContainer.value) return
  trendChart = echarts.init(trendChartContainer.value)
  updateTrendChart([])
}

const updateTrendChart = (trendData = []) => {
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
    if (logList.value.length) {
      counts[counts.length - 1] = logList.value.length
    }
  }

  trendChart.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: '4%', right: '4%', top: '15%', bottom: '12%', containLabel: true },
    xAxis: { type: 'category', data: dates, axisLabel: { fontSize: 11 } },
    yAxis: { type: 'value', minInterval: 1, axisLabel: { fontSize: 11 } },
    series: [{
      name: '发送量',
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
  initTrendChart()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  if (trendChart) trendChart.dispose()
})
</script>
