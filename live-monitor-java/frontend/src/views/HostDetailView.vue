<template>
  <div class="page-container"><div class="page-header"><div class="page-header-title"><router-link to="/hosts" class="back-nav-link">← 返回主机列表</router-link><h1>{{ host?.host_name || '主机详情' }}</h1><p>{{ host?.ip }} · {{ host?.cluster_name || '未分组' }}</p></div><div class="page-actions"><button class="btn btn-ghost" :disabled="busy" @click="load">刷新详情</button><button class="btn btn-primary" :disabled="busy" @click="refreshMetrics">立即采集指标</button></div></div><p v-if="error" role="alert" class="inline-error">{{ error }}</p>
    <section class="card"><div class="grid-4 resource-summary"><div><span>CPU 使用率</span><strong>{{ metrics.cpu_usage_percent ?? '—' }}%</strong></div><div><span>内存使用率</span><strong>{{ metrics.memory_used_percent ?? '—' }}%</strong></div><div><span>磁盘使用率</span><strong>{{ metrics.disk_used_percent ?? '—' }}%</strong></div><div><span>最近采集</span><time>{{ metrics.checked_at || '暂无采集数据' }}</time></div></div>
      <h2>磁盘分区与物理磁盘</h2><div class="table-wrap"><table class="table"><thead><tr><th>磁盘 / 挂载点</th><th>使用情况</th><th>详情</th></tr></thead><tbody><tr v-for="(disk, index) in diskPager.items" :key="index"><td>{{ disk.mount || disk.mount_point || disk.device || disk.name }}</td><td>{{ disk.used_percent ?? disk.usage_percent ?? '—' }}%</td><td><details><summary>磁盘详情</summary><pre>{{ JSON.stringify(disk, null, 2) }}</pre></details></td></tr></tbody></table></div>
<PaginationControls v-model:page="diskPager.page" v-model:page-size="diskPager.pageSize" :total="diskPager.total" label="磁盘分区" />

    </section>
    <section class="card"><h2>资源趋势</h2><label>时间范围<select v-model="days" class="form-control" @change="loadHistory"><option :value="1">最近一天</option><option :value="7">最近七天</option><option :value="30">最近三十天</option></select></label><div ref="chartElement" style="height:300px"></div><p v-if="!history.length">暂无历史指标</p></section>
    <section class="card"><h2>进程配置与检测</h2><button class="btn btn-ghost" :disabled="busy" @click="checkProcesses">检测进程状态</button><p v-if="!processes.length">尚未配置进程</p>
      <div class="table-wrap"><table class="table"><thead><tr><th>进程</th><th>匹配关键字</th><th>检查命令</th><th>启用</th><th>检测结果</th><th>操作</th></tr></thead><tbody><tr v-for="process in processPager.items" :key="process.id"><td>{{ process.process_name }}</td><td>{{ process.match_keyword }}</td><td>{{ process.check_command }}</td><td>{{ process.enabled ? '是' : '否' }}</td><td>{{ states[process.id]?.running === true ? '运行中' : states[process.id]?.running === false ? '未运行' : '未检测' }}<pre style="white-space:pre-wrap">{{ states[process.id]?.output }}</pre></td><td><button class="btn btn-ghost btn-sm" @click="editProcess(process)">编辑</button><button class="btn btn-ghost btn-sm" @click="removeProcess(process)">删除</button></td></tr></tbody></table></div>
<PaginationControls v-model:page="processPager.page" v-model:page-size="processPager.pageSize" :total="processPager.total" label="主机进程" />

      <h3>{{ processForm.id ? '编辑进程' : '新增进程' }}</h3><form @submit.prevent="saveProcess"><div class="form-row"><label class="form-group">进程名称<input v-model="processForm.process_name" required class="form-control"></label><label class="form-group">匹配关键字<input v-model="processForm.match_keyword" class="form-control"></label><label class="form-group">检查命令<input v-model="processForm.check_command" required class="form-control" placeholder="pgrep -f application"></label></div><label><input v-model="processForm.enabled" type="checkbox">启用</label><button class="btn btn-primary" :disabled="busy">保存进程</button><button type="button" class="btn btn-ghost" @click="editProcess()">重置</button></form>
    </section>
  </div>
</template>
<style scoped>
.resource-summary { margin-bottom: 24px; }
.resource-summary > div { padding: 14px; background: #f8fafc; border: 1px solid var(--border-color); border-radius: 8px; }
.resource-summary span { display: block; color: var(--text-muted); font-size: 12px; margin-bottom: 6px; }
.resource-summary strong { font-size: 24px; }
.resource-summary time { font-size: 13px; overflow-wrap: anywhere; }
@media(max-width: 560px) { .resource-summary { grid-template-columns: repeat(2,minmax(0,1fr)); } }
</style>
<script setup>
import PaginationControls from '../components/PaginationControls.vue'
import { usePagination } from '../composables/usePagination'
import { ref, reactive, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { useRoute } from 'vue-router'
import * as echarts from 'echarts'
import { api } from '../api'
const route = useRoute(), host = ref(null), metrics = ref({}), processes = ref([]), states = ref({}), history = ref([]), days = ref(7), chartElement = ref(null), error = ref(''), busy = ref(false)
const diskPager = usePagination(() => [...(metrics.value.disk_metrics || []), ...(metrics.value.physical_disk_metrics || [])], [() => route.params.id])
const processPager = usePagination(processes, [() => route.params.id])
let chart
const blank = () => ({ id: null, process_name: '', match_keyword: '', check_command: '', enabled: true })
const processForm = reactive(blank())
async function execute(action) { busy.value = true; error.value = ''; try { await action() } catch (e) { error.value = e.message } finally { busy.value = false } }
async function loadHistory() {
  try {
    history.value = await api.hostMetricHistory(route.params.id, days.value); await nextTick()
    if (!chartElement.value) return
    if (!chart) chart = echarts.init(chartElement.value)
    const rows = [...history.value].sort((a, b) => String(a.checked_at).localeCompare(String(b.checked_at)))
    chart.setOption({ tooltip: { trigger: 'axis' }, legend: { data: ['CPU', '内存', '磁盘'] }, xAxis: { type: 'category', data: rows.map(row => row.checked_at) }, yAxis: { type: 'value', max: 100 }, series: [['CPU', 'cpu_usage_percent'], ['内存', 'memory_used_percent'], ['磁盘', 'disk_used_percent']].map(([name, key]) => ({ name, type: 'line', data: rows.map(row => row[key] ?? null), showSymbol: false })) })
  } catch (e) { error.value = e.message }
}
async function load() { await execute(async () => { const [item, current, list] = await Promise.all([api.getHost(route.params.id), api.hostMetrics(route.params.id), api.hostProcesses(route.params.id)]); host.value = item; metrics.value = current; processes.value = list; await loadHistory() }) }
async function refreshMetrics() { await execute(async () => { metrics.value = await api.checkHost(route.params.id); await loadHistory() }) }
function editProcess(process) { Object.assign(processForm, blank(), process || {}) }
async function saveProcess() { await execute(async () => { if (processForm.id) await api.updateHostProcess(processForm.id, processForm); else await api.createHostProcess(route.params.id, processForm); processes.value = await api.hostProcesses(route.params.id); editProcess() }) }
async function removeProcess(process) { if (window.confirm(`删除进程“${process.process_name}”？`)) await execute(async () => { await api.deleteHostProcess(process.id); processes.value = await api.hostProcesses(route.params.id) }) }
async function checkProcesses() { await execute(async () => { states.value = await api.hostProcessStatus(route.params.id) }) }
function resize() { chart?.resize() }
watch(() => route.params.id, () => { states.value = {}; editProcess(); load() })
onMounted(() => { load(); window.addEventListener('resize', resize) })
onBeforeUnmount(() => { chart?.dispose(); window.removeEventListener('resize', resize) })
</script>
