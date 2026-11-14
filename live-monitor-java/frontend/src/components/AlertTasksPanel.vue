<template>
  <section class="card" aria-label="告警发送任务">
    <div class="card-header"><div><h2 class="card-title">告警发送任务</h2><p class="form-hint">发送失败后自动重试；重试耗尽的任务可由管理员手动重试。</p></div><span class="text-muted">共 {{ total }} 条匹配任务</span></div>
    <div class="task-counts"><span class="text-muted">全部任务概况</span><span v-for="count in counts" :key="count.delivery_status" :class="['badge', deliveryTone(count.delivery_status)]">{{ deliveryLabel(count.delivery_status) }}：{{ count.total }}</span></div>
    <p v-if="oldest">最早未完成时间：{{ oldest }}</p>
    <form class="task-filters" @submit.prevent="page = 1; load()">
      <label v-if="!serviceId" class="form-group">监控服务<select v-model="filters.service_id" aria-label="监控服务" class="form-control"><option value="">全部服务</option><option v-if="filters.service_id && !services.some(item => String(item.id) === String(filters.service_id))" :value="filters.service_id">服务 #{{ filters.service_id }}</option><option v-for="service in services" :key="service.id" :value="String(service.id)">{{ service.service_name }}</option></select></label>
      <label class="form-group">告警渠道<select v-model="filters.channel_id" aria-label="告警渠道" class="form-control"><option value="">全部渠道</option><option v-if="filters.channel_id && !channels.some(item => String(item.id) === String(filters.channel_id))" :value="filters.channel_id">渠道 #{{ filters.channel_id }}</option><option v-for="channel in channels" :key="channel.id" :value="String(channel.id)">{{ channel.channel_name }}</option></select></label>
      <label class="form-group">发送状态<select v-model="filters.status" aria-label="发送状态" class="form-control"><option value="">全部状态</option><option v-for="status in statuses" :key="status" :value="status">{{ deliveryLabel(status) }}</option></select></label>
      <label class="form-group">开始时间<input v-model="filters.start_time" type="datetime-local" class="form-control"></label>
      <label class="form-group">结束时间<input v-model="filters.end_time" type="datetime-local" class="form-control"></label>
      <div class="task-filter-actions"><button class="btn btn-primary" :disabled="loading">查询 / 刷新</button><button type="button" class="btn btn-ghost" :disabled="loading" @click="reset">重置筛选</button></div>
    </form>
    <p v-if="error" role="alert" class="text-danger">{{ error }}</p>
    <div class="table-wrap"><table class="table"><thead><tr><th>服务 / 渠道</th><th>事件 / 任务</th><th>状态</th><th>尝试次数</th><th>下次执行</th><th>最近失败原因</th><th>操作</th></tr></thead>
      <tbody><tr v-if="!items.length"><td colspan="7">{{ loading ? '加载中…' : '没有符合条件的任务' }}</td></tr>
        <tr v-for="task in items" :key="task.id"><td>{{ task.service_name || task.service_id }} / {{ task.channel_name || task.channel_id }}</td><td><span class="text-muted">事件 {{ task.event_id }}</span><br><small class="task-id" :title="task.id">{{ task.id }}</small></td><td><span :class="['badge', deliveryTone(task.delivery_status)]">{{ deliveryLabel(task.delivery_status) }}</span></td><td>{{ task.attempt_count }}</td><td>{{ statuses.slice(0, 3).includes(task.delivery_status) ? time(task.next_retry_at) : '—' }}</td><td>{{ task.last_error || '—' }}</td><td class="table-actions">
          <button type="button" class="btn btn-ghost btn-sm" @click="details(task)">发送历史</button>
          <button v-if="task.delivery_status === 'FINAL_FAILED' && store.user?.admin" type="button" class="btn btn-ghost btn-sm" :disabled="busy === task.id" @click="retry(task)">手动重试</button>
        </td></tr>
      </tbody></table></div>
    <PaginationControls v-model:page="page" v-model:page-size="pageSize" :total="total" :loading="loading" label="发送任务" @change="load" />
    <DetailDrawer :open="!!selected" title="发送任务详情" @close="selected = null"><template v-if="selected"><div class="detail-metadata"><strong>{{ selected.service_name }} / {{ selected.channel_name }}</strong><span :class="['badge', deliveryTone(selected.delivery_status)]">{{ deliveryLabel(selected.delivery_status) }}</span><p class="text-muted">任务 {{ selected.id }}</p></div><h3>告警消息</h3><AlertMessage :content="selected.alert_content" /><h3 class="detail-section-title">发送历史</h3><p v-if="!attempts.length" class="empty-state">尚无发送尝试，任务正在等待处理。</p><ol class="attempt-timeline"><li v-for="attempt in attemptPager.items" :key="attempt.id"><time>{{ attempt.created_at }}</time><strong>{{ deliveryLabel(attempt.delivery_status) }}</strong><p>{{ attempt.error_message || '渠道已受理请求' }}</p></li></ol><PaginationControls v-model:page="attemptPager.page" v-model:page-size="attemptPager.pageSize" :total="attemptPager.total" label="发送尝试" /></template></DetailDrawer>
  </section>
</template>
<script setup>
import { ref, reactive, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api'
import { store } from '../store'
import AlertMessage from './AlertMessage.vue'
import PaginationControls from './PaginationControls.vue'
import { usePagination } from '../composables/usePagination'
import DetailDrawer from './DetailDrawer.vue'
import { deliveryLabel, deliveryTone } from '../utils/presentation'
const props = defineProps({ serviceId: { type: [String, Number], default: null } })
const route = useRoute()
const filters = reactive({ service_id: '', channel_id: '', status: '', start_time: '', end_time: '' })
const statuses = ['PENDING', 'SENDING', 'FAILED', 'SUCCESS', 'FINAL_FAILED']
const services = ref([]), channels = ref([])
onMounted(async () => {
  const options = await Promise.allSettled([api.listServices(true), api.listAlertChannels(true)])
  if (options[0].status === 'fulfilled') services.value = options[0].value
  if (options[1].status === 'fulfilled') channels.value = options[1].value
})
function reset() { Object.assign(filters, { service_id: '', channel_id: '', status: '', start_time: '', end_time: '' }); page.value = 1; load() }
const items = ref([]), counts = ref([]), oldest = ref(null), total = ref(0), page = ref(1), pageSize = ref(10), error = ref(''), loading = ref(false), busy = ref(null), selected = ref(null), attempts = ref([])
let generation = 0
const attemptPager = usePagination(attempts, [selected])
const time = value => value ? new Date(Number(value)).toLocaleString() : '立即执行'
async function load() {
  const token = ++generation
  loading.value = true; error.value = ''
  try {
    const result = await api.alertTasks({ ...filters, service_id: props.serviceId || filters.service_id, start_time: filters.start_time.replace('T', ' '), end_time: filters.end_time.replace('T', ' '), page: page.value, page_size: pageSize.value })
    if (token !== generation) return
    page.value = result.page || page.value
    items.value = result.items || []; total.value = result.total || 0; counts.value = result.counts || []; oldest.value = result.oldest_pending_at
  } catch (e) { if (token === generation) error.value = e.message }
  finally { if (token === generation) loading.value = false }
}
async function details(task) {
  error.value = ''; selected.value = null
  try { attempts.value = await api.alertTaskAttempts(task.id); selected.value = task } catch (e) { error.value = e.message }
}
async function retry(task) {
  busy.value = task.id; error.value = ''
  try { await api.retryAlertTask(task.id); store.showToast('已创建新的重试任务，原失败记录已保留', 'success'); await load() }
  catch (e) { error.value = e.message } finally { busy.value = null }
}
watch(() => [props.serviceId, route.query.service_id, route.query.channel_id], () => {
  filters.service_id = route.query.service_id || ''
  filters.channel_id = route.query.channel_id || ''
  page.value = 1; load()
}, { immediate: true })
</script>
<style scoped>
h2 { margin-bottom: 8px; }
.task-counts { display: flex; flex-wrap: wrap; gap: 8px; margin: 12px 0 18px; }
.task-filters { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); align-items: end; gap: 16px; margin: 16px 0 20px; }
.task-filters .form-group { display: flex; flex-direction: column; gap: 6px; margin: 0; font-size: 13px; font-weight: 500; min-width: 0; }
.task-filters .form-control { height: 40px; min-width: 0; }
.task-filters > .btn { height: 40px; align-self: end; justify-self: start; width: auto; min-width: 120px; padding: 0 20px; }
.task-filter-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.task-filter-actions .btn { height: 40px; }
.task-pagination { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin-top: 16px; }
.task-id { display: inline-block; max-width: 170px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; vertical-align: middle; font-family: monospace; }
.table { min-width: 940px; }
.table td { vertical-align: top; }
@media (max-width: 600px) { .task-filters { grid-template-columns: minmax(0, 1fr); } }
</style>
