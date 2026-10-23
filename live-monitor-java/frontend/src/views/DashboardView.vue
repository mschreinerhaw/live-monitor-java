<template>
  <div class="page-container dashboard-page">
    <!-- 顶部状态指示栏 -->
    <div class="page-header">
      <div class="page-header-title">
        <h1>监控服务总览</h1>
        <p>轻量事件驱动实时监控平台，毫秒级探测与全链路健康分析</p>
      </div>
      <div class="header-refresh-box">
        <span class="live-dot" :class="{ pulse: refreshing }"></span>
        <span class="text-muted" style="font-size: 13px;">最后更新:</span>
        <time class="time-tag">{{ lastRefreshTime }}</time>
        <button class="btn btn-ghost btn-sm" @click="fetchData" :disabled="loading" title="手动刷新">
          <IconSvg name="refresh" size="14" />
        </button>
      </div>
    </div>

    <!-- 概览指标卡片网格 -->
    <div class="metrics-grid">
      <div class="metric-card total" :class="{ active: statusFilter === 'all' }" @click="statusFilter = 'all'">
        <span class="metric-label">服务总数</span>
        <div class="metric-num">{{ summary.total || 0 }}</div>
        <div class="metric-sub">{{ summary.instances || 0 }} 个实例</div>
      </div>

      <div class="metric-card up" :class="{ active: statusFilter === 'UP' }" @click="statusFilter = 'UP'">
        <span class="metric-label">运行正常</span>
        <div class="metric-num text-success">{{ summary.up || 0 }}</div>
        <div class="metric-sub">可用状态</div>
      </div>

      <div class="metric-card down" :class="{ active: statusFilter === 'DOWN' }" @click="statusFilter = 'DOWN'">
        <span class="metric-label">服务异常</span>
        <div class="metric-num text-danger">{{ summary.down || 0 }}</div>
        <div class="metric-sub">急需关注</div>
      </div>

      <div class="metric-card unknown" :class="{ active: statusFilter === 'UNKNOWN' }" @click="statusFilter = 'UNKNOWN'">
        <span class="metric-label">未知状态</span>
        <div class="metric-num text-muted">{{ summary.unknown || 0 }}</div>
        <div class="metric-sub">等待探测</div>
      </div>

      <div class="metric-card alert">
        <span class="metric-label">今日告警</span>
        <div class="metric-num text-warn">{{ summary.today_alerts || 0 }}</div>
        <div class="metric-sub">昨日: {{ summary.yesterday_alerts || 0 }} 次</div>
      </div>
    </div>

    <!-- 主工作区与侧边栏两栏布局 -->
    <div class="dashboard-content-layout">
      <!-- 左侧：服务列表与工作台 (参考 Cloudera Manager 风格设计) -->
      <div class="main-services-area">
        <div class="card cm-services-card">
          <!-- CM 风格头部工具栏 -->
          <div class="cm-toolbar">
            <div class="cm-toolbar-left">
              <div class="cm-section-heading">
                <span class="cm-heading-icon">
                  <IconSvg name="server" size="18" />
                </span>
                <span class="cm-heading-title">服务管理</span>
                <span class="cm-heading-badge">{{ filteredServices.length }}</span>
              </div>

              <!-- 视图布局切换 -->
              <div class="cm-view-segmented">
                <button
                  type="button"
                  class="cm-seg-btn"
                  :class="{ active: viewLayout === 'cm-table' }"
                  @click="viewLayout = 'cm-table'"
                  title="紧凑表格视图"
                >
                  <IconSvg name="table" size="13" />
                  <span>服务表格</span>
                </button>
                <button
                  type="button"
                  class="cm-seg-btn"
                  :class="{ active: viewLayout === 'cm-cards' }"
                  @click="viewLayout = 'cm-cards'"
                  title="服务卡片视图"
                >
                  <IconSvg name="columns" size="13" />
                  <span>服务卡片</span>
                </button>
                <button
                  type="button"
                  class="cm-seg-btn"
                  :class="{ active: viewLayout === 'cm-grouped' }"
                  @click="viewLayout = 'cm-grouped'"
                  title="集群分组折叠视图"
                >
                  <IconSvg name="activity" size="13" />
                  <span>集群分组</span>
                </button>
              </div>
            </div>

            <div class="cm-toolbar-right">
              <div class="cm-search-box">
                <IconSvg name="search" size="14" class="cm-search-icon" />
                <input
                  v-model="keyword"
                  type="text"
                  class="cm-search-input"
                  placeholder="按服务名、类型、集群过滤..."
                />
              </div>

              <button class="btn btn-ghost btn-sm" @click="exportExcel" title="导出服务列表">
                <IconSvg name="table" size="13" />
                <span>导出 CSV</span>
              </button>

              <router-link to="/services/new" class="btn btn-primary btn-sm">
                <IconSvg name="plus" size="13" />
                <span>添加服务</span>
              </router-link>
            </div>
          </div>

          <!-- 模式 1：Cloudera Manager 标准紧凑服务表格视图 -->
          <div v-if="viewLayout === 'cm-table'" class="table-wrap cm-table-wrap">
            <table class="table cm-table">
              <thead>
                <tr>
                  <th style="width: 50px; text-align: center;">状态</th>
                  <th style="min-width: 200px;">服务名称与描述</th>
                  <th style="width: 130px;">服务类型</th>
                  <th style="width: 140px;">集群 / 命名空间</th>
                  <th style="min-width: 180px;">探测端点 / 资产</th>
                  <th style="width: 140px;">告警通道 / 组</th>
                  <th style="width: 100px;">响应延时</th>
                  <th style="width: 80px;">轮询周期</th>
                  <th style="width: 110px; text-align: right;">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="!filteredServices.length">
                  <td colspan="9" class="text-center text-muted" style="padding: 48px;">
                    暂无符合条件的监控服务
                  </td>
                </tr>
                <tr v-for="srv in filteredServices" :key="srv.id" class="cm-table-row">
                  <!-- 健康状态指示灯 (圆形大图标指示状态) -->
                  <td style="text-align: center;">
                    <span
                      class="cm-health-dot"
                      :class="(srv.lastStatus || 'unknown').toLowerCase()"
                      :title="getStatusTooltip(srv)"
                    ></span>
                  </td>

                  <!-- 服务名称与说明 -->
                  <td>
                    <div class="cm-service-name-cell">
                      <router-link :to="`/services/${srv.id}`" class="cm-service-title">
                        {{ srv.service_name }}
                      </router-link>
                      <span v-if="srv.enabled === false" class="cm-disabled-tag">已停用</span>
                    </div>
                    <div class="cm-service-desc" :title="srv.monitor_reason || srv.lastMessage">
                      {{ srv.monitor_reason || srv.lastMessage || '运行稳定' }}
                    </div>
                  </td>

                  <!-- 服务类型 -->
                  <td>
                    <span class="cm-type-pill">
                      <span class="cm-type-bullet" :class="srv.service_type"></span>
                      <span>{{ serviceTypeLabel(srv.service_type) }}</span>
                    </span>
                  </td>

                  <!-- 集群分组 -->
                  <td>
                    <span class="cm-cluster-label">
                      {{ srv.cluster_name || '默认集群 (default)' }}
                    </span>
                  </td>

                  <!-- 探测端点 / 资产 -->
                  <td>
                    <div class="cm-endpoint-text" :title="srv.endpoint || srv.host || '-'">
                      {{ srv.endpoint || srv.host || '-' }}
                    </div>
                  </td>

                  <!-- 告警组 -->
                  <td>
                    <span class="cm-alert-group-tag" :title="getAlertGroupName(srv)">
                      {{ getAlertGroupName(srv) }}
                    </span>
                  </td>

                  <!-- 响应延时与健康指标 -->
                  <td>
                    <div class="cm-latency-box">
                      <strong :style="{ color: getLatencyColor(srv.lastResponseTimeMs) }">
                        {{ srv.lastResponseTimeMs != null ? `${srv.lastResponseTimeMs} ms` : '-' }}
                      </strong>
                    </div>
                  </td>

                  <!-- 检测周期 -->
                  <td>
                    <span class="text-muted" style="font-size: 12px;">
                      {{ srv.check_interval ? `${srv.check_interval}s` : '60s' }}
                    </span>
                  </td>

                  <!-- 行内快捷操作 -->
                  <td style="text-align: right;">
                    <div class="cm-row-actions">
                      <button
                        class="cm-action-icon-btn"
                        :disabled="checkingMap[srv.id]"
                        @click="handleCheckNow(srv)"
                        title="立即探测"
                      >
                        <IconSvg name="play" size="13" />
                      </button>
                      <router-link :to="`/services/${srv.id}/edit`" class="cm-action-icon-btn" title="配置服务">
                        <IconSvg name="settings" size="13" />
                      </router-link>
                      <button class="cm-action-icon-btn danger" @click="handleDelete(srv)" title="删除服务">
                        <IconSvg name="trash" size="13" />
                      </button>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <!-- 模式 2：Cloudera Manager 服务卡片网格视图 -->
          <div v-else-if="viewLayout === 'cm-cards'" class="cm-cards-grid">
            <div
              v-for="srv in filteredServices"
              :key="srv.id"
              class="cm-service-card"
              :class="(srv.lastStatus || 'unknown').toLowerCase()"
            >
              <div class="cm-card-top">
                <div class="cm-card-header-left">
                  <span class="cm-health-dot" :class="(srv.lastStatus || 'unknown').toLowerCase()"></span>
                  <div>
                    <router-link :to="`/services/${srv.id}`" class="cm-card-name">
                      {{ srv.service_name }}
                    </router-link>
                    <div class="cm-card-cluster">{{ srv.cluster_name || '默认集群' }}</div>
                  </div>
                </div>

                <span class="cm-type-pill">
                  {{ serviceTypeLabel(srv.service_type) }}
                </span>
              </div>

              <div class="cm-card-body">
                <div class="cm-card-metric-row">
                  <span class="cm-meta-label">探测端点</span>
                  <span class="cm-meta-val" :title="srv.endpoint || srv.host || '-'">
                    {{ srv.endpoint || srv.host || '-' }}
                  </span>
                </div>
                <div class="cm-card-metric-row">
                  <span class="cm-meta-label">响应时间</span>
                  <span class="cm-meta-val" :style="{ color: getLatencyColor(srv.lastResponseTimeMs), fontWeight: '600' }">
                    {{ srv.lastResponseTimeMs != null ? `${srv.lastResponseTimeMs} ms` : '-' }}
                  </span>
                </div>
                <div class="cm-card-metric-row">
                  <span class="cm-meta-label">检测周期</span>
                  <span class="cm-meta-val">{{ srv.check_interval || 60 }} 秒</span>
                </div>
              </div>

              <div class="cm-card-footer">
                <span class="cm-card-status-text" :class="(srv.lastStatus || 'unknown').toLowerCase()">
                  {{ srv.lastStatus === 'UP' ? '健康运行' : srv.lastStatus === 'DOWN' ? '服务异常' : '待探测' }}
                </span>
                <div class="cm-card-actions">
                  <button
                    class="cm-action-icon-btn"
                    :disabled="checkingMap[srv.id]"
                    @click="handleCheckNow(srv)"
                    title="立即探测"
                  >
                    <IconSvg name="play" size="13" />
                  </button>
                  <router-link :to="`/services/${srv.id}/edit`" class="cm-action-icon-btn" title="配置">
                    <IconSvg name="settings" size="13" />
                  </router-link>
                  <button class="cm-action-icon-btn danger" @click="handleDelete(srv)" title="删除">
                    <IconSvg name="trash" size="13" />
                  </button>
                </div>
              </div>
            </div>
          </div>

          <!-- 模式 3：集群折叠分组视图 -->
          <div v-else class="cm-grouped-layout">
            <div v-for="(items, groupName) in groupedServices" :key="groupName" class="cm-group-panel">
              <div class="cm-group-panel-head">
                <div style="display: flex; align-items: center; gap: 8px;">
                  <span class="cm-group-badge-icon">
                    <IconSvg name="server" size="14" />
                  </span>
                  <strong>{{ groupName || '未归类集群' }}</strong>
                  <span class="cm-group-count">{{ items.length }} 个服务</span>
                </div>
                <div class="cm-group-stats">
                  <span class="text-success" style="font-size: 12px; font-weight: 600;">
                    UP: {{ items.filter(i => i.lastStatus === 'UP').length }}
                  </span>
                  <span class="text-danger" style="font-size: 12px; font-weight: 600; margin-left: 8px;">
                    DOWN: {{ items.filter(i => i.lastStatus === 'DOWN').length }}
                  </span>
                </div>
              </div>

              <div class="table-wrap" style="border: none; border-radius: 0;">
                <table class="table cm-table">
                  <tbody>
                    <tr v-for="srv in items" :key="srv.id" class="cm-table-row">
                      <td style="width: 40px; text-align: center;">
                        <span class="cm-health-dot" :class="(srv.lastStatus || 'unknown').toLowerCase()"></span>
                      </td>
                      <td style="min-width: 180px;">
                        <router-link :to="`/services/${srv.id}`" class="cm-service-title">
                          {{ srv.service_name }}
                        </router-link>
                      </td>
                      <td style="width: 120px;">
                        <span class="cm-type-pill">{{ serviceTypeLabel(srv.service_type) }}</span>
                      </td>
                      <td>
                        <div class="cm-endpoint-text">{{ srv.endpoint || srv.host || '-' }}</div>
                      </td>
                      <td style="width: 100px;">
                        <strong :style="{ color: getLatencyColor(srv.lastResponseTimeMs) }">
                          {{ srv.lastResponseTimeMs != null ? `${srv.lastResponseTimeMs} ms` : '-' }}
                        </strong>
                      </td>
                      <td style="width: 80px;">
                        <span :class="['badge', `badge-${(srv.lastStatus || 'unknown').toLowerCase()}`]">
                          {{ srv.lastStatus || 'UNKNOWN' }}
                        </span>
                      </td>
                      <td style="text-align: right; width: 110px;">
                        <div class="cm-row-actions">
                          <button class="cm-action-icon-btn" :disabled="checkingMap[srv.id]" @click="handleCheckNow(srv)" title="探测">
                            <IconSvg name="play" size="13" />
                          </button>
                          <router-link :to="`/services/${srv.id}/edit`" class="cm-action-icon-btn" title="编辑">
                            <IconSvg name="settings" size="13" />
                          </router-link>
                        </div>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 右侧：ECharts 资源实时监控仪表盘与最近告警 -->
      <div class="side-panel-area">
        <!-- 系统资源监控卡片 (ECharts 原生图表) -->
        <div class="card">
          <div class="card-header">
            <div class="card-title">
              <IconSvg name="server" size="16" />
              <span>本地服务资源走势</span>
            </div>
            <small class="text-muted" style="font-size: 11px;">每 5 秒刷新</small>
          </div>

          <div class="resource-charts-box">
            <div class="metric-row-stat">
              <div class="metric-chip">
                <span>CPU</span>
                <strong style="color: #0284c7;">{{ latestMetrics.cpu }}%</strong>
              </div>
              <div class="metric-chip">
                <span>内存</span>
                <strong style="color: #10b981;">{{ latestMetrics.memory }}%</strong>
              </div>
              <div class="metric-chip">
                <span>磁盘</span>
                <strong style="color: #f59e0b;">{{ latestMetrics.disk }}%</strong>
              </div>
            </div>

            <!-- ECharts 折线走势容器 -->
            <div ref="chartContainer" style="width: 100%; height: 200px;"></div>
          </div>
        </div>

        <!-- 常用快捷操作卡片 -->
        <div class="card">
          <div class="card-header">
            <div class="card-title">
              <IconSvg name="send" size="16" />
              <span>常用快捷导航</span>
            </div>
          </div>
          <div class="quick-nav-list">
            <router-link to="/hosts" class="quick-nav-item">
              <div>
                <strong>主机服务器维护</strong>
                <p>Linux SSH 主机与挂载分区采集监控</p>
              </div>
              <IconSvg name="server" size="14" />
            </router-link>
            <router-link to="/alerts/settings" class="quick-nav-item">
              <div>
                <strong>告警通道与规则</strong>
                <p>短信 SMS、邮件与 Webhook 通道管理</p>
              </div>
              <IconSvg name="bell" size="14" />
            </router-link>
            <router-link to="/alerts/audit" class="quick-nav-item">
              <div>
                <strong>通知审计流水</strong>
                <p>全量告警事件发送明细与失败分析</p>
              </div>
              <IconSvg name="activity" size="14" />
            </router-link>
          </div>
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
const refreshing = ref(false)
const lastRefreshTime = ref('--:--:--')
const statusFilter = ref('all')
const keyword = ref('')
const viewLayout = ref('cm-table') // 'cm-table' | 'cm-cards' | 'cm-grouped'
const checkingMap = reactive({})

const summary = reactive({
  total: 0,
  instances: 0,
  up: 0,
  down: 0,
  unknown: 0,
  today_alerts: 0,
  yesterday_alerts: 0
})

const servicesList = ref([])

// ECharts 相关
const chartContainer = ref(null)
let myChart = null
const metricHistory = {
  times: [],
  cpu: [],
  memory: [],
  disk: []
}
const latestMetrics = reactive({
  cpu: 0,
  memory: 0,
  disk: 0
})

const serviceTypeLabel = (type) => {
  const map = {
    web: 'Web 应用',
    api: 'API 接口',
    process: '进程检测',
    port: '端口检测',
    redis: 'Redis',
    zookeeper: 'ZooKeeper',
    mysql: 'MySQL',
    oracle: 'Oracle',
    postgresql: 'PostgreSQL',
    jdbc: '通用 JDBC',
    data_send: '数据发送',
    external_message: '消息转发'
  }
  return map[type] || type || '自定义'
}

const getStatusTooltip = (srv) => {
  const st = srv.lastStatus || 'UNKNOWN'
  if (st === 'UP') return `服务运行良好 (耗时 ${srv.lastResponseTimeMs || 0}ms)`
  if (st === 'DOWN') return `服务异常告警: ${srv.lastMessage || '连接失败'}`
  return '未检测或状态未知'
}

const getLatencyColor = (ms) => {
  if (ms == null) return '#94a3b8'
  if (ms < 100) return '#10b981'
  if (ms < 500) return '#0284c7'
  if (ms < 1500) return '#f59e0b'
  return '#ef4444'
}

const getAlertGroupName = (srv) => {
  if (srv.alert_group_names && srv.alert_group_names.length) {
    return srv.alert_group_names.join(', ')
  }
  return srv.alert_group_name || '未绑定'
}

const filteredServices = computed(() => {
  return servicesList.value.filter((s) => {
    if (statusFilter.value !== 'all') {
      if ((s.lastStatus || 'UNKNOWN') !== statusFilter.value) {
        return false
      }
    }
    if (keyword.value.trim()) {
      const q = keyword.value.trim().toLowerCase()
      const name = (s.service_name || '').toLowerCase()
      const cluster = (s.cluster_name || '').toLowerCase()
      const type = (s.service_type || '').toLowerCase()
      return name.includes(q) || cluster.includes(q) || type.includes(q)
    }
    return true
  })
})

const groupedServices = computed(() => {
  const groups = {}
  filteredServices.value.forEach((srv) => {
    const key = srv.cluster_name || '默认集群 (default)'
    if (!groups[key]) groups[key] = []
    groups[key].push(srv)
  })
  return groups
})

const fetchData = async () => {
  loading.value = true
  refreshing.value = true
  try {
    const [dashRes, srvRes] = await Promise.all([
      api.dashboard().catch(() => ({})),
      api.listServices(true).catch(() => [])
    ])

    Object.assign(summary, dashRes.summary || dashRes || {})
    servicesList.value = srvRes || []
    lastRefreshTime.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  } catch (err) {
    store.showToast('加载数据异常：' + err.message, 'error')
  } finally {
    loading.value = false
    refreshing.value = false
  }
}

const handleCheckNow = async (srv) => {
  checkingMap[srv.id] = true
  try {
    store.showToast(`正在探测 ${srv.service_name}...`)
    const res = await api.checkAndStoreService(srv.id)
    srv.lastStatus = res.status
    srv.lastResponseTimeMs = res.responseTimeMs
    srv.lastMessage = res.message
    store.showToast(`探测完成: ${res.status} (${res.responseTimeMs || 0}ms)`, res.status === 'UP' ? 'success' : 'error')
  } catch (err) {
    store.showToast(`探测失败: ${err.message}`, 'error')
  } finally {
    checkingMap[srv.id] = false
  }
}

const handleDelete = async (srv) => {
  if (!confirm(`确定要删除监控服务 "${srv.service_name}" 吗？此操作不可恢复。`)) return
  try {
    await api.deleteService(srv.id)
    store.showToast('服务已删除', 'success')
    await fetchData()
  } catch (err) {
    store.showToast('删除失败：' + err.message, 'error')
  }
}

const exportExcel = () => {
  const rows = [
    ['服务ID', '服务名称', '服务类型', '集群', '端点', '状态', '响应时间(ms)', '检测间隔(s)', '告警组']
  ]
  filteredServices.value.forEach((s) => {
    rows.push([
      s.id,
      `"${(s.service_name || '').replace(/"/g, '""')}"`,
      s.service_type,
      s.cluster_name || '',
      `"${(s.endpoint || s.host || '').replace(/"/g, '""')}"`,
      s.lastStatus || 'UNKNOWN',
      s.lastResponseTimeMs != null ? s.lastResponseTimeMs : '',
      s.check_interval || 60,
      `"${getAlertGroupName(s).replace(/"/g, '""')}"`
    ])
  })
  const csvContent = '﻿' + rows.map((e) => e.join(',')).join('
')
  const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `live_monitor_services_${new Date().toISOString().slice(0, 10)}.csv`
  a.click()
  URL.revokeObjectURL(url)
  store.showToast('已导出服务列表 CSV', 'success')
}

// 资源折线图初始化与轮询
const initECharts = () => {
  if (!chartContainer.value) return
  myChart = echarts.init(chartContainer.value)
  const option = {
    tooltip: { trigger: 'axis' },
    legend: { data: ['CPU', '内存', '磁盘'], textStyle: { fontSize: 11 }, top: 0 },
    grid: { left: '10%', right: '6%', top: '22%', bottom: '15%' },
    xAxis: {
      type: 'category',
      data: metricHistory.times,
      axisLabel: { fontSize: 10 }
    },
    yAxis: {
      type: 'value',
      min: 0,
      max: 100,
      axisLabel: { formatter: '{value}%', fontSize: 10 }
    },
    series: [
      { name: 'CPU', type: 'line', smooth: true, showSymbol: false, data: metricHistory.cpu, color: '#0284c7' },
      { name: '内存', type: 'line', smooth: true, showSymbol: false, data: metricHistory.memory, color: '#10b981' },
      { name: '磁盘', type: 'line', smooth: true, showSymbol: false, data: metricHistory.disk, color: '#f59e0b' }
    ]
  }
  myChart.setOption(option)
}

const pollSystemMetrics = async () => {
  try {
    const m = await api.systemMetrics()
    if (!m) return
    const nowStr = new Date().toLocaleTimeString('zh-CN', { hour12: false })
    const cpuVal = Number((m.cpu || m.cpu_usage_percent || 0).toFixed(1))
    const memVal = Number((m.memory || m.memory_used_percent || 0).toFixed(1))
    const diskVal = Number((m.disk || m.disk_used_percent || 0).toFixed(1))

    latestMetrics.cpu = cpuVal
    latestMetrics.memory = memVal
    latestMetrics.disk = diskVal

    metricHistory.times.push(nowStr)
    metricHistory.cpu.push(cpuVal)
    metricHistory.memory.push(memVal)
    metricHistory.disk.push(diskVal)

    if (metricHistory.times.length > 20) {
      metricHistory.times.shift()
      metricHistory.cpu.shift()
      metricHistory.memory.shift()
      metricHistory.disk.shift()
    }

    if (myChart) {
      myChart.setOption({
        xAxis: { data: metricHistory.times },
        series: [
          { data: metricHistory.cpu },
          { data: metricHistory.memory },
          { data: metricHistory.disk }
        ]
      })
    }
  } catch (_) {}
}

const handleResize = () => {
  if (myChart) myChart.resize()
}

let autoRefreshTimer = null
let metricTimer = null

onMounted(async () => {
  await fetchData()
  initECharts()
  await pollSystemMetrics()
  window.addEventListener('resize', handleResize)
  autoRefreshTimer = setInterval(fetchData, 30000)
  metricTimer = setInterval(pollSystemMetrics, 5000)
})

onBeforeUnmount(() => {
  if (autoRefreshTimer) clearInterval(autoRefreshTimer)
  if (metricTimer) clearInterval(metricTimer)
  window.removeEventListener('resize', handleResize)
  if (myChart) myChart.dispose()
})
</script>

<style scoped>
.header-refresh-box {
  display: flex;
  align-items: center;
  gap: 8px;
  background: #ffffff;
  padding: 4px 10px;
  border-radius: 6px;
  border: 1px solid #e2e8f0;
}
.live-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #10b981;
}
.live-dot.pulse {
  background: #0284c7;
  box-shadow: 0 0 0 4px rgba(2, 132, 199, 0.2);
}
.time-tag {
  font-family: monospace;
  font-weight: 600;
  font-size: 13px;
  color: #0f172a;
}

.metrics-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 16px;
  margin-bottom: 24px;
}
@media (max-width: 1024px) {
  .metrics-grid { grid-template-columns: repeat(3, 1fr); }
}
@media (max-width: 640px) {
  .metrics-grid { grid-template-columns: 1fr; }
}

.metric-card {
  background: #ffffff;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  padding: 16px 20px;
  cursor: pointer;
  transition: all 0.2s ease;
  user-select: none;
}
.metric-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
}
.metric-card.active {
  border-color: #0284c7;
  background: #f0f9ff;
}

.metric-label { font-size: 13px; color: var(--text-muted); font-weight: 500; }
.metric-num { font-size: 26px; font-weight: 700; margin: 6px 0 2px; color: var(--text-main); line-height: 1.2; }
.metric-sub { font-size: 12px; color: var(--text-light); }
.text-success { color: var(--status-up); }
.text-danger { color: var(--status-down); }
.text-warn { color: var(--status-warn); }

.dashboard-content-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: 20px;
}
@media (max-width: 1024px) {
  .dashboard-content-layout { grid-template-columns: 1fr; }
}

/* ======================================================== */
/* Cloudera Manager (CM) 风格专业企业级设计规范 */
/* ======================================================== */
.cm-services-card {
  padding: 0;
  overflow: hidden;
  border-radius: 10px;
}

.cm-toolbar {
  padding: 14px 18px;
  background: #f8fafc;
  border-bottom: 1px solid var(--border-color);
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}

.cm-toolbar-left, .cm-toolbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.cm-section-heading {
  display: flex;
  align-items: center;
  gap: 8px;
}
.cm-heading-icon {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  background: #e0f2fe;
  color: #0284c7;
  display: flex;
  align-items: center;
  justify-content: center;
}
.cm-heading-title {
  font-size: 15px;
  font-weight: 700;
  color: #0f172a;
}
.cm-heading-badge {
  background: #e2e8f0;
  color: #475569;
  font-size: 11.5px;
  font-weight: 700;
  padding: 1px 7px;
  border-radius: 10px;
}

.cm-view-segmented {
  display: inline-flex;
  background: #e2e8f0;
  padding: 2px;
  border-radius: 6px;
  gap: 2px;
}
.cm-seg-btn {
  background: transparent;
  border: none;
  font-size: 12px;
  font-weight: 500;
  padding: 4px 10px;
  border-radius: 4px;
  color: #475569;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  transition: all 0.15s;
}
.cm-seg-btn.active {
  background: #ffffff;
  color: #0284c7;
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(0,0,0,0.08);
}

.cm-search-box {
  position: relative;
  display: flex;
  align-items: center;
}
.cm-search-icon {
  position: absolute;
  left: 9px;
  color: #94a3b8;
  pointer-events: none;
}
.cm-search-input {
  height: 32px;
  padding: 4px 10px 4px 30px;
  border-radius: 6px;
  border: 1px solid var(--border-color);
  font-size: 12.5px;
  background: #ffffff;
  outline: none;
  width: 200px;
  transition: all 0.15s;
}
.cm-search-input:focus {
  border-color: var(--border-focus);
  width: 240px;
}

/* CM 健康状态指示灯 */
.cm-health-dot {
  display: inline-block;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  position: relative;
  transition: all 0.2s ease;
}
.cm-health-dot.up {
  background: #10b981;
  box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.2);
}
.cm-health-dot.down {
  background: #ef4444;
  box-shadow: 0 0 0 3px rgba(239, 68, 68, 0.25);
  animation: cm-pulse 1.8s infinite;
}
.cm-health-dot.unknown {
  background: #94a3b8;
  box-shadow: 0 0 0 3px rgba(148, 163, 184, 0.2);
}

@keyframes cm-pulse {
  0% { transform: scale(0.95); opacity: 0.8; }
  50% { transform: scale(1.15); opacity: 1; }
  100% { transform: scale(0.95); opacity: 0.8; }
}

/* 表格样式增强 */
.cm-table-wrap {
  border: none;
  border-radius: 0;
}
.cm-table th {
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.3px;
  padding: 10px 14px;
  background: #f8fafc;
}
.cm-table-row:hover td {
  background: #f1f5f9;
}

.cm-service-name-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}
.cm-service-title {
  font-size: 13.5px;
  font-weight: 600;
  color: #0f172a;
  text-decoration: none;
}
.cm-service-title:hover {
  color: #0284c7;
  text-decoration: underline;
}
.cm-disabled-tag {
  background: #fee2e2;
  color: #b91c1c;
  font-size: 10px;
  padding: 1px 5px;
  border-radius: 4px;
}
.cm-service-desc {
  font-size: 11.5px;
  color: #64748b;
  margin-top: 2px;
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cm-type-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: #f1f5f9;
  border: 1px solid #e2e8f0;
  color: #334155;
  font-size: 11.5px;
  font-weight: 500;
  padding: 2px 8px;
  border-radius: 12px;
}
.cm-type-bullet {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #0284c7;
}
.cm-type-bullet.redis { background: #dc2626; }
.cm-type-bullet.mysql, .cm-type-bullet.oracle, .cm-type-bullet.postgresql { background: #0284c7; }
.cm-type-bullet.zookeeper { background: #16a34a; }
.cm-type-bullet.data_send { background: #ea580c; }

.cm-cluster-label {
  font-size: 12.5px;
  color: #334155;
  font-weight: 500;
}

.cm-endpoint-text {
  font-family: Consolas, Monaco, monospace;
  font-size: 11.5px;
  color: #475569;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cm-alert-group-tag {
  font-size: 11.5px;
  color: #475569;
  background: #f8fafc;
  padding: 2px 6px;
  border-radius: 4px;
  border: 1px solid #e2e8f0;
  display: inline-block;
  max-width: 130px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cm-latency-box strong {
  font-size: 12.5px;
  font-family: monospace;
}

.cm-row-actions {
  display: inline-flex;
  gap: 4px;
}
.cm-action-icon-btn {
  background: #ffffff;
  border: 1px solid var(--border-color);
  color: #475569;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s;
}
.cm-action-icon-btn:hover {
  background: #f0f9ff;
  border-color: #0284c7;
  color: #0284c7;
}
.cm-action-icon-btn.danger:hover {
  background: #fef2f2;
  border-color: #ef4444;
  color: #ef4444;
}

/* CM 模式 2：卡片网格 */
.cm-cards-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
  padding: 16px;
  background: #f8fafc;
}
.cm-service-card {
  background: #ffffff;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.04);
  display: flex;
  flex-direction: column;
  transition: all 0.2s ease;
  overflow: hidden;
}
.cm-service-card:hover {
  box-shadow: 0 6px 12px -2px rgba(0,0,0,0.08);
  transform: translateY(-2px);
}
.cm-service-card.down {
  border-top: 3px solid #ef4444;
}
.cm-service-card.up {
  border-top: 3px solid #10b981;
}

.cm-card-top {
  padding: 12px 14px;
  border-bottom: 1px solid #f1f5f9;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.cm-card-header-left {
  display: flex;
  align-items: center;
  gap: 8px;
}
.cm-card-name {
  font-size: 13.5px;
  font-weight: 700;
  color: #0f172a;
  text-decoration: none;
}
.cm-card-cluster {
  font-size: 11px;
  color: #94a3b8;
}

.cm-card-body {
  padding: 12px 14px;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.cm-card-metric-row {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
}
.cm-meta-label {
  color: #64748b;
}
.cm-meta-val {
  color: #1e293b;
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cm-card-footer {
  padding: 8px 14px;
  background: #f8fafc;
  border-top: 1px solid #f1f5f9;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.cm-card-status-text {
  font-size: 11.5px;
  font-weight: 600;
}
.cm-card-status-text.up { color: #10b981; }
.cm-card-status-text.down { color: #ef4444; }
.cm-card-status-text.unknown { color: #94a3b8; }
.cm-card-actions {
  display: flex;
  gap: 4px;
}

/* CM 模式 3：折叠分组 */
.cm-grouped-layout {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 16px;
  background: #f8fafc;
}
.cm-group-panel {
  background: #ffffff;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  overflow: hidden;
}
.cm-group-panel-head {
  padding: 10px 16px;
  background: #f1f5f9;
  border-bottom: 1px solid var(--border-color);
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.cm-group-badge-icon {
  width: 22px;
  height: 22px;
  background: #e2e8f0;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #475569;
}
.cm-group-count {
  font-size: 11px;
  background: #ffffff;
  border: 1px solid #cbd5e1;
  padding: 1px 6px;
  border-radius: 10px;
  color: #475569;
}

/* 侧边栏 ECharts */
.metric-row-stat {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin-bottom: 8px;
  text-align: center;
}
.metric-chip {
  background: #f8fafc;
  padding: 6px;
  border-radius: 6px;
  border: 1px solid #e2e8f0;
}
.metric-chip span { display: block; font-size: 11px; color: #64748b; }
.metric-chip strong { font-size: 15px; }

.quick-nav-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.quick-nav-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  text-decoration: none;
  color: #1e293b;
  transition: all 0.15s ease;
}
.quick-nav-item:hover {
  background: #f8fafc;
  border-color: #cbd5e1;
}
.quick-nav-item strong { font-size: 13px; color: #0f172a; }
.quick-nav-item p { font-size: 11px; color: #64748b; margin-top: 2px; }
</style>
