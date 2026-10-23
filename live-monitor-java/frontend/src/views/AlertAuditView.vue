<template>
  <div class="page-container alert-audit-page">
    <div class="page-header">
      <div class="page-header-title">
        <h1>告警通知审计流水</h1>
        <p>系统对外发送的全部告警与事件通知日志，详细记录投递状态、接收人与失败原因</p>
      </div>
      <div>
        <button class="btn btn-ghost" @click="fetchLogs" :disabled="loading">
          <IconSvg name="refresh" size="14" />
          <span>刷新</span>
        </button>
      </div>
    </div>

    <!-- 审计表格 -->
    <div class="card">
      <div class="card-header">
        <div class="card-title">
          <IconSvg name="activity" size="16" />
          <span>通知审计记录</span>
        </div>
        <div style="display: flex; gap: 10px;">
          <select v-model="statusFilter" class="form-control" style="width: 140px;" @change="fetchLogs">
            <option value="">全部状态</option>
            <option value="success">发送成功</option>
            <option value="failed">发送失败</option>
          </select>
        </div>
      </div>

      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>通知时间</th>
              <th>服务 / 项目</th>
              <th>渠道名称</th>
              <th>渠道类型</th>
              <th>接收目标</th>
              <th>状态</th>
              <th>内容摘要</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!logList.length">
              <td colspan="7" class="text-center text-muted" style="padding: 32px;">
                暂无告警通知审计记录
              </td>
            </tr>
            <tr v-for="item in logList" :key="item.id">
              <td style="font-family: monospace; font-size: 12px;">{{ item.created_at || '-' }}</td>
              <td><strong>{{ item.service_name || item.project_name || '-' }}</strong></td>
              <td>{{ item.channel_name || '-' }}</td>
              <td><span class="badge badge-info">{{ (item.channel_type || '').toUpperCase() }}</span></td>
              <td>{{ item.recipient || '-' }}</td>
              <td>
                <span class="badge" :class="item.status === 'success' ? 'badge-up' : 'badge-down'">
                  {{ item.status === 'success' ? '成功' : '失败' }}
                </span>
              </td>
              <td style="max-width: 280px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;" :title="item.content || item.error_message">
                {{ item.content || item.error_message || '-' }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 分页控制 -->
      <div style="display: flex; justify-content: space-between; align-items: center; margin-top: 16px;">
        <span class="text-muted" style="font-size: 13px;">第 {{ page }} 页，共 {{ total }} 条记录</span>
        <div style="display: flex; gap: 8px;">
          <button class="btn btn-ghost btn-sm" :disabled="page <= 1" @click="page--; fetchLogs()">上一页</button>
          <button class="btn btn-ghost btn-sm" :disabled="logList.length < pageSize" @click="page++; fetchLogs()">下一页</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api'
import { store } from '../store'

const loading = ref(false)
const logList = ref([])
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const statusFilter = ref('')

const fetchLogs = async () => {
  loading.value = true
  try {
    const res = await api.auditNotifyRecords({
      page: page.value,
      page_size: pageSize.value,
      status: statusFilter.value || undefined
    })
    logList.value = res.items || res.records || []
    total.value = res.total || logList.value.length
  } catch (err) {
    store.showToast('加载审计日志失败：' + err.message, 'error')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  fetchLogs()
})
</script>
