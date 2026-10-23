<template>
  <div class="page-container dashboard-page">
    <!-- 顶部状态指示栏 -->
    <div class="page-header">
      <div class="page-header-title">
        <h1>监控服务总览</h1>
        <p>轻量事件驱动实时监控平台，毫秒级探测与全链路健康分析</p>
      </div>
      <div class="header-actions">
        <button class="btn btn-ghost" @click="fetchData" :disabled="loading" title="刷新状态">
          <IconSvg name="refresh" size="14" />
          <span>刷新</span>
        </button>
        <router-link to="/services/new" class="btn btn-primary">
          <IconSvg name="plus" size="14" />
          <span>新增监控服务</span>
        </router-link>
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
        <div class="metric-sub">急需处理</div>
      </div>

      <div class="metric-card unknown" :class="{ active: statusFilter === 'UNKNOWN' }" @click="statusFilter = 'UNKNOWN'">
        <span class="metric-label">未知状态</span>
        <div class="metric-num text-muted">{{ summary.unknown || 0 }}</div>
        <div class="metric-sub">待检测</div>
      </div>

      <div class="metric-card alert">
        <span class="metric-label">今日告警</span>
        <div class="metric-num text-warn">{{ summary.today_alerts || 0 }}</div>
        <div class="metric-sub">昨日: {{ summary.yesterday_alerts || 0 }} 次</div>
      </div>
    </div>

    <div class="dashboard-content-layout">
      <!-- 左侧：服务列表 -->
      <div class="main-services-area">
        <div class="card">
          <div class="card-header">
            <div class="card-title">
              <IconSvg name="activity" size="16" />
              <span>监控服务列表</span>
              <span class="badge badge-info" style="margin-left: 6px;">{{ filteredServices.length }}</span>
            </div>
            <div class="table-search">
              <input
                v-model="keyword"
                type="text"
                class="form-control"
                placeholder="搜索服务名称 / 集群 / 类型..."
                style="width: 220px;"
              />
            </div>
          </div>

          <div class="table-wrap">
            <table class="table">
              <thead>
                <tr>
                  <th>服务名称</th>
                  <th>类型</th>
                  <th>集群 / 分组</th>
                  <th>端点 / 目标</th>
                  <th>状态</th>
                  <th>响应时间</th>
                  <th>检测间隔</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="!filteredServices.length">
                  <td colspan="8" class="text-center text-muted" style="padding: 32px;">
                    暂无符合条件的监控服务
                  </td>
                </tr>
                <tr v-for="srv in filteredServices" :key="srv.id">
                  <td>
                    <strong>
                      <router-link :to="`/services/${srv.id}`">{{ srv.service_name }}</router-link>
                    </strong>
                    <div v-if="srv.monitor_reason" class="text-muted" style="font-size: 11px;">
                      {{ srv.monitor_reason }}
                    </div>
                  </td>
                  <td>
                    <span class="type-tag">{{ serviceTypeLabel(srv.service_type) }}</span>
                  </td>
                  <td>{{ srv.cluster_name || '-' }}</td>
                  <td style="font-family: monospace; font-size: 12px; max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">
                    {{ srv.endpoint || srv.host || '-' }}
                  </td>
                  <td>
                    <span :class="['badge', `badge-${(srv.lastStatus || 'unknown').toLowerCase()}`]">
                      {{ srv.lastStatus || 'UNKNOWN' }}
                    </span>
                  </td>
                  <td>{{ srv.lastResponseTimeMs ? `${srv.lastResponseTimeMs}ms` : '-' }}</td>
                  <td>{{ srv.check_interval ? `${srv.check_interval}s` : '60s' }}</td>
                  <td>
                    <div class="btn-group">
                      <button class="btn btn-ghost btn-sm" @click="handleCheckNow(srv)" title="立即探测">
                        <IconSvg name="play" size="12" />
                      </button>
                      <router-link :to="`/services/${srv.id}/edit`" class="btn btn-ghost btn-sm" title="编辑服务">
                        <IconSvg name="edit" size="12" />
                      </router-link>
                      <button class="btn btn-danger btn-sm" @click="handleDelete(srv)" title="删除服务">
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

      <!-- 右侧：系统资源与告警流水 -->
      <div class="side-panel-area">
        <!-- 系统资源卡片 -->
        <div class="card">
          <div class="card-header">
            <div class="card-title">
              <IconSvg name="server" size="16" />
              <span>本地服务系统资源</span>
            </div>
          </div>
          <div v-if="sysMetrics" class="resource-gauges">
            <div class="gauge-item">
              <div class="gauge-head">
                <span>CPU 使用率</span>
                <strong>{{ sysMetrics.cpu_usage_percent?.toFixed(1) || 0 }}%</strong>
              </div>
              <div class="progress-bar">
                <div class="progress-fill" :style="{ width: `${sysMetrics.cpu_usage_percent || 0}%`, background: getProgressColor(sysMetrics.cpu_usage_percent) }"></div>
              </div>
            </div>

            <div class="gauge-item">
              <div class="gauge-head">
                <span>内存 使用率</span>
                <strong>{{ sysMetrics.memory_used_percent?.toFixed(1) || 0 }}%</strong>
              </div>
              <div class="progress-bar">
                <div class="progress-fill" :style="{ width: `${sysMetrics.memory_used_percent || 0}%`, background: getProgressColor(sysMetrics.memory_used_percent) }"></div>
              </div>
            </div>

            <div class="gauge-item">
              <div class="gauge-head">
                <span>磁盘 使用率</span>
                <strong>{{ sysMetrics.disk_used_percent?.toFixed(1) || 0 }}%</strong>
              </div>
              <div class="progress-bar">
                <div class="progress-fill" :style="{ width: `${sysMetrics.disk_used_percent || 0}%`, background: getProgressColor(sysMetrics.disk_used_percent) }"></div>
              </div>
            </div>
          </div>
          <div v-else class="text-center text-muted" style="padding: 16px;">
            资源指标采集正常运转中
          </div>
        </div>

        <!-- 快速入口卡片 -->
        <div class="card">
          <div class="card-header">
            <div class="card-title">
              <IconSvg name="send" size="16" />
              <span>常用快捷工具</span>
            </div>
          </div>
          <div class="quick-links">
            <router-link to="/data-send" class="quick-link-item">
              <div>
                <strong>数据发送功能</strong>
                <p>SQL 结果短信预览与即时发送 (最多10条)</p>
              </div>
              <IconSvg name="play" size="14" />
            </router-link>
            <router-link to="/hosts" class="quick-link-item">
              <div>
                <strong>主机资源监控</strong>
                <p>Linux SSH 主机与挂载分区采集维护</p>
              </div>
              <IconSvg name="play" size="14" />
            </router-link>
            <router-link to="/alerts/settings" class="quick-link-item">
              <div>
                <strong>告警通道与规则</strong>
                <p>短信 SMS、邮件与 Webhook 通道管理</p>
              </div>
              <IconSvg name="play" size="14" />
            </router-link>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { api } from '../api'
import { store } from '../store'

const loading = ref(false)
const statusFilter = ref('all')
const keyword = ref('')
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
const sysMetrics = ref(null)

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

const getProgressColor = (val) => {
  if (val >= 85) return '#dc2626'
  if (val >= 70) return '#ea580c'
  return '#16a34a'
}

const filteredServices = computed(() => {
  return servicesList.value.filter((s) => {
    // 状态过滤
    if (statusFilter.value !== 'all') {
      if ((s.lastStatus || 'UNKNOWN') !== statusFilter.value) {
        return false
      }
    }
    // 关键字过滤
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

const fetchData = async () => {
  loading.value = true
  try {
    const [dashRes, srvRes, sysRes] = await Promise.all([
      api.dashboard().catch(() => ({})),
      api.listServices(true).catch(() => []),
      api.systemMetrics().catch(() => null)
    ])

    Object.assign(summary, dashRes || {})
    servicesList.value = srvRes || []
    sysMetrics.value = sysRes
  } catch (err) {
    store.showToast('加载监控总览失败：' + err.message, 'error')
  } finally {
    loading.value = false
  }
}

const handleCheckNow = async (srv) => {
  try {
    store.showToast(`正在探测 ${srv.service_name}...`)
    const res = await api.checkAndStoreService(srv.id)
    store.showToast(`探测完成: ${res.status || 'UP'} (${res.responseTimeMs || 0}ms)`, 'success')
    await fetchData()
  } catch (err) {
    store.showToast(`探测异常: ${err.message}`, 'error')
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

onMounted(() => {
  fetchData()
})
</script>

<style scoped>
.metrics-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 16px;
  margin-bottom: 24px;
}
@media (max-width: 1024px) {
  .metrics-grid {
    grid-template-columns: repeat(3, 1fr);
  }
}
@media (max-width: 640px) {
  .metrics-grid {
    grid-template-columns: 1fr;
  }
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

.metric-label {
  font-size: 13px;
  color: var(--text-muted);
  font-weight: 500;
}
.metric-num {
  font-size: 26px;
  font-weight: 700;
  margin: 6px 0 2px;
  color: var(--text-main);
  line-height: 1.2;
}
.metric-sub {
  font-size: 12px;
  color: var(--text-light);
}

.text-success { color: var(--status-up); }
.text-danger { color: var(--status-down); }
.text-warn { color: var(--status-warn); }

.dashboard-content-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: 20px;
}
@media (max-width: 1024px) {
  .dashboard-content-layout {
    grid-template-columns: 1fr;
  }
}

.type-tag {
  display: inline-block;
  background: #f1f5f9;
  color: #475569;
  font-size: 12px;
  padding: 2px 6px;
  border-radius: 4px;
}

.btn-group {
  display: flex;
  gap: 6px;
}

.resource-gauges {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.gauge-item .gauge-head {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #334155;
  margin-bottom: 4px;
}
.progress-bar {
  height: 6px;
  background: #e2e8f0;
  border-radius: 3px;
  overflow: hidden;
}
.progress-fill {
  height: 100%;
  transition: width 0.3s ease;
}

.quick-links {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.quick-link-item {
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
.quick-link-item:hover {
  background: #f8fafc;
  border-color: #cbd5e1;
}
.quick-link-item strong {
  font-size: 13px;
  color: #0f172a;
}
.quick-link-item p {
  font-size: 11px;
  color: #64748b;
  margin-top: 2px;
}
</style>
