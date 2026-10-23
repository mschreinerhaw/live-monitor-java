<template>
  <div class="page-container service-detail-page">
    <div class="page-header">
      <div class="page-header-title">
        <router-link to="/dashboard" class="back-link">
          <IconSvg name="arrow-left" size="14" />
          <span>返回监控总览</span>
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
        <router-link v-if="service" :to="`/services/${service.id}/edit`" class="btn btn-ghost">
          <IconSvg name="edit" size="14" />
          <span>编辑</span>
        </router-link>
      </div>
    </div>

    <div v-if="service" class="grid-2">
      <!-- 基础指标 -->
      <div class="card">
        <div class="card-header">
          <div class="card-title">
            <IconSvg name="activity" size="16" />
            <span>实时探测状态</span>
          </div>
          <span :class="['badge', `badge-${(service.lastStatus || 'unknown').toLowerCase()}`]">
            {{ service.lastStatus || 'UNKNOWN' }}
          </span>
        </div>

        <div style="display: flex; flex-direction: column; gap: 12px; font-size: 13px;">
          <div><strong>最近响应时间：</strong> {{ service.lastResponseTimeMs != null ? `${service.lastResponseTimeMs} ms` : '-' }}</div>
          <div><strong>最近检测时间：</strong> {{ service.lastCheckedAt || '-' }}</div>
          <div><strong>检测响应信息：</strong> {{ service.lastMessage || '-' }}</div>
          <div><strong>定时检测：</strong> {{ service.enabled ? '已启用' : '已停用' }}</div>
          <div><strong>检测间隔：</strong> {{ service.check_interval }} 秒</div>
        </div>
      </div>

      <!-- 服务配置 -->
      <div class="card">
        <div class="card-header">
          <div class="card-title">
            <IconSvg name="server" size="16" />
            <span>配置参数摘要</span>
          </div>
        </div>

        <div style="display: flex; flex-direction: column; gap: 12px; font-size: 13px;">
          <div><strong>服务类型：</strong> {{ service.service_type }}</div>
          <div><strong>集群分组：</strong> {{ service.cluster_name || '-' }}</div>
          <div><strong>目标端点：</strong> {{ service.endpoint || service.host || '-' }}</div>
          <div v-if="service.database_query"><strong>查询 SQL：</strong> <code style="background:#f1f5f9;padding:2px 4px;border-radius:3px;">{{ service.database_query }}</code></div>
          <div v-if="service.data_send_selected_columns?.length"><strong>数据发送列：</strong> {{ service.data_send_selected_columns.join(', ') }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api'
import { store } from '../store'

const route = useRoute()
const service = ref(null)
const checking = ref(false)

const fetchServiceDetail = async () => {
  try {
    const data = await api.getService(route.params.id)
    service.value = data
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

onMounted(() => {
  fetchServiceDetail()
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
