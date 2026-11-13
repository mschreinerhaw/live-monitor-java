<template>
  <section class="card" aria-label="告警发送任务">
    <h2>告警发送任务</h2>
    <p>生产消息按渠道持久化处理。FAILED 会自动重试，FINAL_FAILED 可由管理员手动重试。</p>
    <div class="task-counts"><span v-for="count in counts" :key="count.delivery_status" class="badge badge-info">{{ count.delivery_status }}：{{ count.total }}</span></div>
    <p v-if="oldest">最早未完成时间：{{ oldest }}</p>
    <form class="task-filters" @submit.prevent="page = 1; load()">
      <label v-if="!serviceId" class="form-group">服务 ID<input v-model="filters.service_id" type="number" min="1" class="form-control"></label>
      <label class="form-group">渠道 ID<input v-model="filters.channel_id" type="number" min="1" class="form-control"></label>
      <label class="form-group">状态<select v-model="filters.status" class="form-control"><option value="">全部</option><option v-for="status in statuses" :key="status">{{ status }}</option></select></label>
      <label class="form-group">开始时间<input v-model="filters.start_time" type="datetime-local" class="form-control"></label>
      <label class="form-group">结束时间<input v-model="filters.end_time" type="datetime-local" class="form-control"></label>
      <button class="btn btn-primary" :disabled="loading">查询 / 刷新</button>
    </form>
    <p v-if="error" role="alert" class="text-danger">{{ error }}</p>
    <div class="table-wrap"><table class="table"><thead><tr><th>服务 / 渠道</th><th>事件 / 任务</th><th>状态</th><th>尝试次数</th><th>下次执行</th><th>最近失败原因</th><th>操作</th></tr></thead>
      <tbody><tr v-if="!items.length"><td colspan="7">{{ loading ? '加载中…' : '没有符合条件的任务' }}</td></tr>
        <tr v-for="task in items" :key="task.id"><td>{{ task.service_name || task.service_id }} / {{ task.channel_name || task.channel_id }}</td><td>{{ task.event_id }}<br><small>{{ task.id }}</small></td><td>{{ task.delivery_status }}</td><td>{{ task.attempt_count }}</td><td>{{ statuses.slice(0, 3).includes(task.delivery_status) ? time(task.next_retry_at) : '—' }}</td><td>{{ task.last_error || '—' }}</td><td>
          <button type="button" class="btn btn-ghost btn-sm" @click="details(task)">发送历史</button>
          <button v-if="task.delivery_status === 'FINAL_FAILED' && store.user?.admin" type="button" class="btn btn-ghost btn-sm" :disabled="busy === task.id" @click="retry(task)">手动重试</button>
        </td></tr>
      </tbody></table></div>
    <div class="task-pagination"><button type="button" class="btn btn-ghost" :disabled="page <= 1 || loading" @click="page--; load()">上一页</button><span>第 {{ page }} 页 / 共 {{ total }} 条</span><button type="button" class="btn btn-ghost" :disabled="page * 50 >= total || loading" @click="page++; load()">下一页</button></div>
    <div v-if="selected" class="card"><h3>任务 {{ selected.id }} 的发送历史</h3><AlertMessage :content="selected.alert_content" /><p v-if="!attempts.length">尚无发送尝试</p><ol><li v-for="attempt in attempts" :key="attempt.id">{{ attempt.created_at }} · {{ attempt.delivery_status }} · {{ attempt.error_message || '成功' }}</li></ol><button class="btn btn-ghost" @click="selected = null">关闭详情</button></div>
  </section>
</template>
<script setup>
import { ref, reactive, watch } from 'vue'
import { api } from '../api'
import { store } from '../store'
import AlertMessage from './AlertMessage.vue'
const props = defineProps({ serviceId: { type: [String, Number], default: null } })
const filters = reactive({ service_id: '', channel_id: '', status: '', start_time: '', end_time: '' })
const statuses = ['PENDING', 'SENDING', 'FAILED', 'SUCCESS', 'FINAL_FAILED']
const items = ref([]), counts = ref([]), oldest = ref(null), total = ref(0), page = ref(1), error = ref(''), loading = ref(false), busy = ref(null), selected = ref(null), attempts = ref([])
let generation = 0
const time = value => value ? new Date(Number(value)).toLocaleString() : '立即执行'
async function load() {
  const token = ++generation
  loading.value = true; error.value = ''
  try {
    const result = await api.alertTasks({ ...filters, service_id: props.serviceId || filters.service_id, start_time: filters.start_time.replace('T', ' '), end_time: filters.end_time.replace('T', ' '), page: page.value })
    if (token !== generation) return
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
watch(() => props.serviceId, () => { page.value = 1; load() }, { immediate: true })
</script>
<style scoped>
h2 { margin-bottom: 8px; }
.task-counts { display: flex; flex-wrap: wrap; gap: 8px; margin: 12px 0 18px; }
.task-filters { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); align-items: end; gap: 16px; margin: 16px 0 20px; }
.task-filters .form-group { display: flex; flex-direction: column; gap: 6px; margin: 0; font-size: 13px; font-weight: 500; min-width: 0; }
.task-filters .form-control { height: 40px; min-width: 0; }
.task-filters > .btn { height: 40px; align-self: end; justify-self: start; width: auto; min-width: 120px; padding: 0 20px; }
.task-pagination { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin-top: 16px; }
@media (max-width: 600px) { .task-filters { grid-template-columns: minmax(0, 1fr); } }
</style>
