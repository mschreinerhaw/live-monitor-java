<template>
  <div class="page-container host-page">
    <div class="page-header">
      <div class="page-header-title">
        <h1>服务器主机维护</h1>
        <p>维护 SSH / SFTP 目标主机，配置 CPU、挂载点阈值和自动检测探测策略</p>
      </div>
      <div style="display: flex; gap: 8px;">
        <button class="btn btn-ghost btn-sm" @click="fetchHosts" :disabled="loading" title="刷新主机列表">
          <IconSvg name="refresh" size="14" />
          <span>刷新</span>
        </button>
        <button class="btn btn-ghost btn-sm" @click="exportHostsCsv" title="导出主机列表">
          <IconSvg name="table" size="14" />
          <span>导出 CSV</span>
        </button>
        <button class="btn btn-primary btn-sm" @click="openCreateModal">
          <IconSvg name="plus" size="14" />
          <span>新增主机</span>
        </button>
      </div>
    </div>

    <!-- 主机统计指标卡片 -->
    <div class="grid-3" style="margin-bottom: 20px;">
      <div class="card" style="margin: 0; display: flex; align-items: center; gap: 16px;">
        <div class="stat-icon blue">
          <IconSvg name="server" size="24" />
        </div>
        <div>
          <span class="text-muted" style="font-size: 13px;">主机总数</span>
          <div style="font-size: 24px; font-weight: 700; color: #0f172a;">{{ hostsList.length }}</div>
        </div>
      </div>

      <div class="card" style="margin: 0; display: flex; align-items: center; gap: 16px;">
        <div class="stat-icon green">
          <IconSvg name="check" size="24" />
        </div>
        <div>
          <span class="text-muted" style="font-size: 13px;">在线主机</span>
          <div style="font-size: 24px; font-weight: 700; color: #16a34a;">
            {{ hostsList.filter(h => (h.status || h.last_status || 'UNKNOWN') === 'UP').length }}
          </div>
        </div>
      </div>

      <div class="card" style="margin: 0; display: flex; align-items: center; gap: 16px;">
        <div class="stat-icon red">
          <IconSvg name="bell" size="24" />
        </div>
        <div>
          <span class="text-muted" style="font-size: 13px;">告警主机</span>
          <div style="font-size: 24px; font-weight: 700; color: #dc2626;">
            {{ hostsList.filter(h => (h.status || h.last_status) === 'DOWN').length }}
          </div>
        </div>
      </div>
    </div>

    <!-- 主机表格列表 -->
    <div class="card">
      <div class="card-header" style="flex-wrap: wrap; gap: 10px;">
        <div class="card-title">
          <IconSvg name="server" size="16" />
          <span>主机列表</span>
          <span class="badge badge-info" style="margin-left: 6px;">{{ filteredHosts.length }}</span>
        </div>
        <div style="display: flex; gap: 10px; align-items: center; flex-wrap: wrap;">
          <select v-model="statusFilter" class="form-control" style="width: 120px;">
            <option value="all">全部状态</option>
            <option value="UP">正常在线</option>
            <option value="DOWN">异常告警</option>
            <option value="UNKNOWN">未知状态</option>
          </select>
          <input
            v-model="keyword"
            type="text"
            class="form-control"
            placeholder="搜索主机名称 / IP..."
            style="width: 200px;"
          />
        </div>
      </div>

      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>主机名称</th>
              <th>IP 地址</th>
              <th>SSH 端口</th>
              <th>SSH 账号</th>
              <th>CPU 利用率</th>
              <th>内存利用率</th>
              <th>磁盘利用率</th>
              <th>最新探测时间</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!pagedHosts.length">
              <td colspan="10" class="text-center text-muted" style="padding: 32px;">
                暂无服务器主机数据
              </td>
            </tr>
            <tr v-for="h in pagedHosts" :key="h.id" :class="{ 'row-active': selectedHost?.id === h.id }">
              <td>
                <strong style="cursor: pointer; color: #0284c7;" @click="selectHostDetails(h)">
                  {{ h.host_name }}
                </strong>
              </td>
              <td style="font-family: monospace;">{{ h.ip }}</td>
              <td>{{ h.port || 22 }}</td>
              <td>{{ h.username || 'root' }}</td>
              <td>
                <span v-if="getCpu(h) != null" :style="{ color: getCpu(h) > 85 ? '#dc2626' : '#1e293b', fontWeight: '500' }">
                  {{ getCpu(h).toFixed(1) }}%
                </span>
                <span v-else class="text-muted">-</span>
              </td>
              <td>
                <span v-if="getMem(h) != null" :style="{ color: getMem(h) > 85 ? '#dc2626' : '#1e293b', fontWeight: '500' }">
                  {{ getMem(h).toFixed(1) }}%
                </span>
                <span v-else class="text-muted">-</span>
              </td>
              <td>
                <span v-if="getDisk(h) != null" :style="{ color: getDisk(h) > 85 ? '#dc2626' : '#1e293b', fontWeight: '500' }">
                  {{ getDisk(h).toFixed(1) }}%
                </span>
                <span v-else class="text-muted">-</span>
              </td>
              <td style="font-size: 12px; color: #64748b;">
                {{ h.metric_checked_at || h.checked_at || '-' }}
              </td>
              <td>
                <span :class="['badge', `badge-${(h.status || h.last_status || 'unknown').toLowerCase()}`]">
                  {{ h.status || h.last_status || 'UNKNOWN' }}
                </span>
              </td>
              <td>
                <div style="display: flex; gap: 6px;">
                  <button class="btn btn-ghost btn-sm" @click="handleCheckHost(h)" title="立即探测">
                    <IconSvg name="play" size="12" />
                  </button>
                  <button class="btn btn-ghost btn-sm" @click="openEditModal(h)" title="编辑主机">
                    <IconSvg name="edit" size="12" />
                  </button>
                  <button class="btn btn-danger btn-sm" @click="handleDeleteHost(h)" title="删除主机">
                    <IconSvg name="trash" size="12" />
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 分页控制 -->
      <div style="display: flex; justify-content: space-between; align-items: center; margin-top: 14px;">
        <span class="text-muted" style="font-size: 12px;">共 {{ filteredHosts.length }} 台主机，当前第 {{ currentPage }} / {{ totalPages || 1 }} 页</span>
        <div style="display: flex; gap: 6px;">
          <button class="btn btn-ghost btn-sm" :disabled="currentPage <= 1" @click="currentPage--">上一页</button>
          <button class="btn btn-ghost btn-sm" :disabled="currentPage >= totalPages" @click="currentPage++">下一页</button>
        </div>
      </div>
    </div>

    <!-- 选定主机详细指标卡片抽屉 -->
    <div v-if="selectedHost" class="card" style="margin-top: 20px;">
      <div class="card-header">
        <div class="card-title">
          <IconSvg name="server" size="16" />
          <span>主机指标详情：{{ selectedHost.host_name }} ({{ selectedHost.ip }})</span>
        </div>
        <button class="btn btn-ghost btn-sm" @click="selectedHost = null">关闭详情</button>
      </div>

      <!-- 磁盘分区列表 -->
      <div v-if="selectedHostPartitions.length" style="margin-bottom: 16px;">
        <strong style="font-size: 13px; color: #334155; display: block; margin-bottom: 8px;">已挂载磁盘分区利用率</strong>
        <div class="grid-3">
          <div v-for="part in selectedHostPartitions" :key="part.mount || part.filesystem" class="part-card">
            <div style="display: flex; justify-content: space-between; font-size: 12px; margin-bottom: 4px;">
              <strong>{{ part.mount || part.filesystem }}</strong>
              <span :style="{ color: part.percent > 85 ? '#dc2626' : '#16a34a' }">{{ part.percent }}%</span>
            </div>
            <div style="font-size: 11px; color: #64748b; margin-bottom: 6px;">{{ part.used }} / {{ part.total }}</div>
            <div class="progress-bar">
              <div class="progress-fill" :style="{ width: `${part.percent}%`, background: part.percent > 85 ? '#dc2626' : '#0284c7' }"></div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 新增 / 编辑主机模态框 -->
    <div v-if="showModal" class="modal-overlay">
      <div class="modal-dialog">
        <div class="modal-header">
          <h3 class="modal-title">{{ editingHostId ? '编辑主机配置' : '新增主机配置' }}</h3>
          <button class="btn btn-ghost btn-sm" @click="showModal = false">
            <IconSvg name="x" size="14" />
          </button>
        </div>
        <form @submit.prevent="handleSaveHost">
          <div class="modal-body">
            <div class="form-row">
              <div class="form-group">
                <label class="form-label">主机名称</label>
                <input v-model="modalForm.host_name" type="text" class="form-control" placeholder="例如：app-server-01" required />
              </div>
              <div class="form-group">
                <label class="form-label">IP 地址</label>
                <input v-model="modalForm.ip" type="text" class="form-control" placeholder="192.168.1.10" required />
              </div>
            </div>

            <div class="form-row">
              <div class="form-group">
                <label class="form-label">SSH 端口</label>
                <input v-model.number="modalForm.port" type="number" class="form-control" placeholder="22" required />
              </div>
              <div class="form-group">
                <label class="form-label">SSH 用户名</label>
                <input v-model="modalForm.username" type="text" class="form-control" placeholder="root" required />
              </div>
            </div>

            <div class="form-group">
              <label class="form-label">认证方式</label>
              <select v-model="modalForm.auth_type" class="form-control">
                <option value="password">密码认证</option>
                <option value="key">私钥认证</option>
              </select>
            </div>

            <div v-if="modalForm.auth_type === 'password'" class="form-group">
              <label class="form-label">SSH 密码</label>
              <input v-model="modalForm.password" type="password" class="form-control" placeholder="留空则保持原密码" />
            </div>

            <div v-if="modalForm.auth_type === 'key'" class="form-group">
              <label class="form-label">私钥内容 (PEM)</label>
              <textarea v-model="modalForm.private_key" rows="3" class="form-control" placeholder="-----BEGIN RSA PRIVATE KEY-----"></textarea>
            </div>

            <div class="form-row">
              <div class="form-group">
                <label class="form-label">CPU 告警阈值 (%)</label>
                <input v-model.number="modalForm.cpu_threshold_percent" type="number" class="form-control" placeholder="85" />
              </div>
              <div class="form-group">
                <label class="form-label">内存 告警阈值 (%)</label>
                <input v-model.number="modalForm.memory_threshold_percent" type="number" class="form-control" placeholder="85" />
              </div>
            </div>

            <div class="form-group">
              <label class="form-label">绑定告警组</label>
              <select v-model="modalForm.alert_group_id" class="form-control">
                <option :value="null">-- 不绑定告警组 --</option>
                <option v-for="g in alertGroups" :key="g.id" :value="g.id">
                  {{ g.group_name }}
                </option>
              </select>
            </div>
          </div>

          <div class="modal-footer">
            <button type="button" class="btn btn-ghost" @click="handleTestSsh" :disabled="testingSsh">
              {{ testingSsh ? '正在测试 SSH...' : '测试 SSH 连接' }}
            </button>
            <button type="button" class="btn btn-ghost" @click="showModal = false">取消</button>
            <button type="submit" class="btn btn-primary">保存主机</button>
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

const loading = ref(false)
const hostsList = ref([])
const alertGroups = ref([])
const keyword = ref('')
const statusFilter = ref('all')
const currentPage = ref(1)
const pageSize = 15

const showModal = ref(false)
const editingHostId = ref(null)
const testingSsh = ref(false)
const selectedHost = ref(null)

const modalForm = reactive({
  host_name: '',
  ip: '',
  port: 22,
  username: 'root',
  auth_type: 'password',
  password: '',
  private_key: '',
  cpu_threshold_percent: 85,
  memory_threshold_percent: 85,
  alert_group_id: null
})

const getCpu = (h) => h.cpu_usage_percent ?? h.metrics?.cpu_usage_percent
const getMem = (h) => h.memory_used_percent ?? h.metrics?.memory_used_percent
const getDisk = (h) => h.disk_used_percent ?? h.metrics?.disk_used_percent

const filteredHosts = computed(() => {
  return hostsList.value.filter((h) => {
    if (statusFilter.value !== 'all') {
      const st = h.status || h.last_status || 'UNKNOWN'
      if (st !== statusFilter.value) return false
    }
    if (keyword.value.trim()) {
      const q = keyword.value.trim().toLowerCase()
      return (h.host_name || '').toLowerCase().includes(q) || (h.ip || '').includes(q)
    }
    return true
  })
})

const totalPages = computed(() => Math.ceil(filteredHosts.value.length / pageSize))

const pagedHosts = computed(() => {
  const start = (currentPage.value - 1) * pageSize
  return filteredHosts.value.slice(start, start + pageSize)
})

const selectedHostPartitions = computed(() => {
  if (!selectedHost.value) return []
  try {
    const raw = selectedHost.value.disk_metrics_json || selectedHost.value.metrics?.disk_metrics_json
    if (!raw) return []
    return JSON.parse(raw)
  } catch (_) {
    return []
  }
})

const fetchHosts = async () => {
  loading.value = true
  try {
    const [list, groups] = await Promise.all([
      api.listHosts(true),
      api.listAlertGroups(true).catch(() => [])
    ])
    hostsList.value = list || []
    alertGroups.value = groups || []
  } catch (err) {
    store.showToast('加载主机列表失败：' + err.message, 'error')
  } finally {
    loading.value = false
  }
}

const selectHostDetails = (h) => {
  selectedHost.value = h
}

const openCreateModal = () => {
  editingHostId.value = null
  Object.assign(modalForm, {
    host_name: '',
    ip: '',
    port: 22,
    username: 'root',
    auth_type: 'password',
    password: '',
    private_key: '',
    cpu_threshold_percent: 85,
    memory_threshold_percent: 85,
    alert_group_id: null
  })
  showModal.value = true
}

const openEditModal = (h) => {
  editingHostId.value = h.id
  Object.assign(modalForm, {
    host_name: h.host_name || '',
    ip: h.ip || '',
    port: h.port || 22,
    username: h.username || 'root',
    auth_type: h.private_key ? 'key' : 'password',
    password: '',
    private_key: h.private_key || '',
    cpu_threshold_percent: h.cpu_threshold_percent || 85,
    memory_threshold_percent: h.memory_threshold_percent || 85,
    alert_group_id: h.alert_group_id || null
  })
  showModal.value = true
}

const handleTestSsh = async () => {
  testingSsh.value = true
  try {
    await api.testHostSsh(modalForm)
    store.showToast('SSH 连接测试成功！', 'success')
  } catch (err) {
    store.showToast('SSH 测试失败：' + err.message, 'error')
  } finally {
    testingSsh.value = false
  }
}

const handleSaveHost = async () => {
  try {
    if (editingHostId.value) {
      await api.updateHost(editingHostId.value, modalForm)
      store.showToast('主机配置已更新', 'success')
    } else {
      await api.createHost(modalForm)
      store.showToast('主机已添加', 'success')
    }
    showModal.value = false
    await fetchHosts()
  } catch (err) {
    store.showToast('保存主机失败：' + err.message, 'error')
  }
}

const handleCheckHost = async (h) => {
  try {
    store.showToast(`正在探测主机 ${h.host_name}...`)
    await api.checkHost(h.id)
    store.showToast('主机探测完成', 'success')
    await fetchHosts()
  } catch (err) {
    store.showToast('主机探测异常：' + err.message, 'error')
  }
}

const handleDeleteHost = async (h) => {
  if (!confirm(`确定要删除主机 "${h.host_name}" 吗？`)) return
  try {
    await api.deleteHost(h.id)
    store.showToast('主机已删除', 'success')
    await fetchHosts()
  } catch (err) {
    store.showToast('删除主机失败：' + err.message, 'error')
  }
}

const exportHostsCsv = () => {
  const rows = [['主机名称', 'IP地址', 'SSH端口', '账号', 'CPU利用率', '内存利用率', '磁盘利用率', '状态', '最新探测时间']]
  hostsList.value.forEach((h) => {
    rows.push([
      `"${(h.host_name || '').replace(/"/g, '""')}"`,
      h.ip || '',
      h.port || 22,
      h.username || '',
      getCpu(h) != null ? `${getCpu(h).toFixed(1)}%` : '-',
      getMem(h) != null ? `${getMem(h).toFixed(1)}%` : '-',
      getDisk(h) != null ? `${getDisk(h).toFixed(1)}%` : '-',
      h.status || h.last_status || 'UNKNOWN',
      h.metric_checked_at || h.checked_at || '-'
    ])
  })
  const csvContent = '\uFEFF' + rows.map((e) => e.join(',')).join('\n')
  const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `live_monitor_hosts_${new Date().toISOString().slice(0, 10)}.csv`
  a.click()
  URL.revokeObjectURL(url)
  store.showToast('已导出主机列表 CSV', 'success')
}

onMounted(() => {
  fetchHosts()
})
</script>

<style scoped>
.stat-icon {
  width: 44px;
  height: 44px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.stat-icon.blue { background: #e0f2fe; color: #0284c7; }
.stat-icon.green { background: #dcfce7; color: #16a34a; }
.stat-icon.red { background: #fee2e2; color: #dc2626; }

.part-card {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  padding: 10px;
}
.row-active td {
  background: #f0f9ff !important;
}
</style>
