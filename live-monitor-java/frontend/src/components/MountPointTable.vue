<template>
  <section class="card mount-monitor" aria-label="挂载点监控">
    <h2 class="card-title">挂载点监控</h2>
    <p class="text-muted">检查指定路径是否为独立挂载点、是否只读及空间使用率。空间阈值 {{ host.disk_threshold_percent ?? 85 }}%，与磁盘阈值一致。</p>
    <p v-if="!host.mount_monitor_enabled" class="text-muted">未启用，可在编辑主机配置中启用并填写挂载点。</p>
    <div v-else class="table-wrap"><table class="table"><thead><tr><th>挂载点</th><th>设备 / 文件系统</th><th>状态</th><th>总容量 / 可用</th><th>使用率</th><th>检测说明</th></tr></thead><tbody>
      <tr v-for="row in pager.items" :key="row.mount"><td>{{ row.mount }}</td><td>{{ row.filesystem || '—' }}<div class="text-muted">{{ row.fs_type || '—' }}</div></td><td><span :class="['badge', row.mount_state === 'OK' ? 'badge-up' : row.mount_state === 'UNKNOWN' ? 'badge-unknown' : 'badge-down']">{{ labels[row.mount_state] || '待采集' }}</span></td><td>{{ bytes(row.total_bytes) }} / {{ bytes(row.available_bytes) }}</td><td>{{ row.used_percent == null ? '—' : row.used_percent + '%' }}</td><td>{{ row.mount_reason || '等待下次采集，尚无有效挂载点数据。' }}</td></tr>
      <tr v-if="!rows.length"><td colspan="6">未配置挂载点</td></tr>
    </tbody></table></div>
    <PaginationControls v-if="host.mount_monitor_enabled" v-model:page="pager.page" v-model:page-size="pager.pageSize" :total="pager.total" label="挂载点" />
  </section>
</template>
<script setup>
import { computed } from 'vue'
import PaginationControls from './PaginationControls.vue'
import { usePagination } from '../composables/usePagination'
const props = defineProps({ host: { type: Object, default: () => ({}) }, metrics: { type: Object, default: () => ({}) } })
const labels = { OK: '正常', MISSING: '挂载丢失', READ_ONLY: '只读', HIGH_USAGE: '空间超阈', UNKNOWN: '未知' }
const rows = computed(() => (props.host.mount_points || []).map(mount => {
  const disk = (props.metrics.disk_metrics || props.host.disk_metrics || []).find(row => row.monitored_mount && row.mount === mount)
  return disk || { mount, mount_state: 'UNKNOWN' }
}))
const pager = usePagination(rows)
function bytes(value) { if (value == null) return '—'; const n = Number(value); return n >= 1073741824 ? (n / 1073741824).toFixed(1) + ' GB' : (n / 1048576).toFixed(1) + ' MB' }
</script>
