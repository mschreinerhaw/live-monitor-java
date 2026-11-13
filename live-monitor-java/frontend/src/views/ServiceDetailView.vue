<template>
  <div class="page-container service-detail-page">
    <div class="page-header">
      <div class="page-header-title">
        <router-link :to="route.query.from === 'databases' ? '/databases' : '/dashboard'" class="back-link">
          <IconSvg name="arrow-left" size="14" />
          <span>{{ route.query.from === 'databases' ? '返回数据库监控' : '返回监控总览' }}</span>
        </router-link>
        <h1 style="margin-top: 6px;">{{ service?.service_name || '服务详情' }}</h1>
        <p>{{ service?.monitor_reason || '轻量监控服务实例运行状态与历史探测轨迹' }}</p>
      </div>
      <div style="display: flex; gap: 8px;">
        <button class="btn btn-ghost" @click="fetchServiceDetail">
          <IconSvg name="refresh" size="14" />
          <span>刷新</span>
        </button>
        <button class="btn btn-primary" @click="handleCheckNow" :disabled="checking">
          <IconSvg name="play" size="14" />
          <span>{{ checking ? '正在探测...' : '立即探测' }}</span>
        </button>
        <router-link v-if="service" :to="{ path: `/services/${service.id}/edit`, query: route.query.from === 'databases' ? { from: 'databases' } : {} }" class="btn btn-ghost">
          <IconSvg name="edit" size="14" />
          <span>编辑</span>
        </router-link>
      </div>
    </div>

    <div v-if="service">
      <div class="grid-2" style="margin-bottom: 20px;">
        <!-- 基础健康卡片 -->
        <div class="card" style="margin: 0;">
          <div class="card-header">
            <div class="card-title">
              <IconSvg name="activity" size="16" />
              <span>实时探测健康状态</span>
            </div>
            <span :class="['badge', `badge-${(service.last_status || 'unknown').toLowerCase()}`]">
              {{ service.last_status || 'UNKNOWN' }}
            </span>
          </div>

          <div style="display: flex; flex-direction: column; gap: 12px; font-size: 13px;">
            <div><strong>响应耗时：</strong> {{ service.last_response_time_ms != null ? `${service.last_response_time_ms} ms` : '-' }}</div>
            <div><strong>最新检测时间：</strong> {{ service.last_checked_at || '-' }}</div>
            <div><strong>检测返回详情：</strong> {{ service.last_message || '-' }}</div>
            <div><strong>定时检测：</strong> {{ service.enabled ? '已启用' : '已停用' }}</div>
            <div><strong>检测周期：</strong> {{ service.check_interval }} 秒</div>
          </div>
        </div>

        <!-- 服务配置卡片 -->
        <div class="card" style="margin: 0;">
          <div class="card-header">
            <div class="card-title">
              <IconSvg name="server" size="16" />
              <span>配置参数摘要</span>
            </div>
          </div>

          <div style="display: flex; flex-direction: column; gap: 12px; font-size: 13px;">
            <div><strong>服务类型：</strong> <span class="badge badge-info">{{ service.service_type }}</span></div>
            <div><strong>集群分组：</strong> {{ service.cluster_name || '-' }}</div>
            <div><strong>目标端点：</strong> {{ service.endpoint || service.host || '-' }}</div>
            <div v-if="service.database_query"><strong>查询 SQL：</strong> <code style="background:#f1f5f9;padding:2px 4px;border-radius:3px;">{{ service.database_query }}</code></div>
            <div v-if="service.data_send_selected_columns?.length"><strong>数据发送列：</strong> {{ service.data_send_selected_columns.join(', ') }}</div>
          </div>
        </div>
      </div>

      <!-- 响应时间历史走势图 -->
      <div class="card" style="margin-bottom: 20px;">
        <div class="card-header">
          <div class="card-title">
            <IconSvg name="activity" size="16" />
            <span>响应时间历史走势 (ms)</span>
          </div>
        </div>
        <div ref="respChartContainer" style="width: 100%; height: 220px;"></div>
      </div>

      <!-- 历史探测结果流水 -->
      <div class="card">
        <div class="card-header">
          <div class="card-title">
            <IconSvg name="table" size="16" />
            <span>最近探测记录流水</span>
          </div>
          <label class="filter-label">查询范围<select v-model.number="historyLimit" class="form-control" @change="resultPager.page = 1; fetchServiceDetail()"><option v-for="count in [50,100,200,500]" :key="count" :value="count">最近 {{ count }} 次</option></select></label>
        </div>

        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>检测时间</th>
                <th>状态</th>
                <th>响应耗时</th>
                <th>返回信息</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="!historyRecords.length">
                <td colspan="4" class="text-center text-muted" style="padding: 24px;">暂无探测流水记录</td>
              </tr>
              <tr v-for="(rec, i) in resultPager.items" :key="i">
                <td style="font-family: monospace; font-size: 12px;">{{ rec.checkedAt || rec.checked_at || '-' }}</td>
                <td>
                  <span :class="['badge', `badge-${(rec.status || 'unknown').toLowerCase()}`]">
                    {{ rec.status }}
                  </span>
                </td>
                <td>{{ rec.responseTimeMs ?? rec.response_time_ms ?? '-' }} ms</td>
                <td style="max-width: 360px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">
                  {{ rec.message || '-' }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
<PaginationControls v-model:page="resultPager.page" v-model:page-size="resultPager.pageSize" :total="resultPager.total" label="最近探测记录" />

      </div>
    </div>
    <AlertTasksPanel v-if="service" :key="service.last_checked_at" :service-id="route.params.id" />
  </div>
</template>

<script setup>
import PaginationControls from '../components/PaginationControls.vue'
import { usePagination } from '../composables/usePagination'
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute } from 'vue-router'
import * as echarts from 'echarts'
import { api } from '../api'
import { store } from '../store'
import AlertTasksPanel from '../components/AlertTasksPanel.vue'


const route = useRoute()
const service = ref(null)
const checking = ref(false)
const historyLimit = ref(100)
const historyRecords = ref([])
const resultPager = usePagination(historyRecords, [() => route.params.id])


const respChartContainer = ref(null)
let respChart = null

const fetchServiceDetail = async () => {
  try {
    const [data, results] = await Promise.all([
      api.getService(route.params.id),
      api.serviceResults(route.params.id, historyLimit.value)
    ])
    service.value = data
    historyRecords.value = results || []

    updateRespChart()
  } catch (err) {
    store.showToast('加载服务详情失败：' + err.message, 'error')
  }
}

const handleCheckNow = async () => {
  checking.value = true
  try {
    const res = await api.checkAndStoreService(route.params.id)
    store.showToast(`探测完成: ${res.status || 'UP'} (${res.responseTimeMs || 0}ms)`, 'success')
    await fetchServiceDetail()
  } catch (err) {
    store.showToast('探测失败：' + err.message, 'error')
  } finally {
    checking.value = false
  }
}

const initRespChart = () => {
  if (!respChartContainer.value) return
  respChart = echarts.init(respChartContainer.value)
  updateRespChart()
}

const updateRespChart = () => {
  if (!respChart) return
  const times = historyRecords.value.map(r => (r.checkedAt || r.checked_at || '').slice(-8))
  const values = historyRecords.value.map(r => r.responseTimeMs ?? r.response_time_ms ?? 0)

  respChart.setOption({
    tooltip: { trigger: 'axis', formatter: '{b}<br/>响应耗时: {c} ms' },
    grid: { left: '4%', right: '4%', top: '15%', bottom: '12%', containLabel: true },
    xAxis: { type: 'category', data: times.reverse(), axisLabel: { fontSize: 11 } },
    yAxis: { type: 'value', axisLabel: { formatter: '{value} ms', fontSize: 11 } },
    series: [{
      name: '耗时',
      type: 'line',
      smooth: true,
      data: values.reverse(),
      color: '#176b87',
      areaStyle: { opacity: 0.15, color: '#176b87' }
    }]
  })
}

const handleResize = () => {
  if (respChart) respChart.resize()
}

onMounted(async () => {
  await fetchServiceDetail()
  initRespChart()
  window.addEventListener('resize', handleResize)
})

watch(() => route.params.id, fetchServiceDetail)
onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  if (respChart) respChart.dispose()
})
</script>

<style scoped>
.back-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: var(--text-muted);
}
.back-link:hover {
  color: var(--primary-color);
}
</style>
