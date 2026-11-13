<template>
  <div class="page-container database-page">
    <div class="page-header"><div class="page-header-title"><h1>数据库监控维护</h1><p>维护数据库连接、SQL 检查及告警配置；数据库所在服务器的 SSH 资源采集请前往服务器主机。</p></div><div class="page-actions"><button class="btn btn-ghost" :disabled="loading" @click="load">刷新</button><router-link :to="{ path: '/services/new', query: { from: 'databases', type: 'mysql' } }" class="btn btn-primary">新增数据库监控</router-link></div></div>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <div class="grid-3 database-summary"><div class="card"><span class="text-muted">数据库监控</span><strong>{{ databases.length }}</strong></div><div class="card"><span class="text-muted">检查正常</span><strong class="text-success">{{ databases.filter(item => item.enabled !== false && item.last_status === 'UP').length }}</strong></div><div class="card"><span class="text-muted">需要关注</span><strong class="text-danger">{{ databases.filter(item => item.enabled !== false && item.last_status !== 'UP').length }}</strong></div></div>
    <section class="card"><div class="card-header"><h2 class="card-title">数据库列表 <span class="badge badge-info">{{ filtered.length }}</span></h2><form class="toolbar" @submit.prevent><input v-model="keyword" aria-label="搜索数据库" placeholder="名称 / 地址 / 集群" class="form-control database-search"><select v-model="type" aria-label="数据库类型" class="form-control database-filter"><option value="">全部类型</option><option v-for="(label,key) in types" :key="key" :value="key">{{ label }}</option></select><select v-model="status" aria-label="数据库状态" class="form-control database-filter"><option value="">全部状态</option><option value="UP">检查正常</option><option value="DOWN">检查失败</option><option value="UNKNOWN">检查异常</option><option value="DISABLED">已停用</option></select><button class="btn btn-ghost" type="button" @click="keyword = ''; type = ''; status = ''">重置筛选</button></form></div>
      <div class="table-wrap"><table class="table database-table" :aria-busy="loading"><thead><tr><th>数据库名称 / 集群</th><th>数据库类型</th><th>连接目标</th><th>检查状态</th><th>最近检查 / 周期</th><th>告警组</th><th>操作</th></tr></thead><tbody><tr v-if="!paged.length"><td colspan="7" class="empty-state">{{ loading ? '正在加载数据库…' : '没有符合条件的数据库监控' }}</td></tr><tr v-for="item in paged" :key="item.id"><td><router-link :to="detail(item)"><strong>{{ item.service_name }}</strong></router-link><div class="form-hint">{{ item.cluster_name || '未分组' }}</div></td><td>{{ databaseTypes[item.service_type] }}</td><td class="connection-target">{{ target(item) }}<div v-if="item.database_name" class="form-hint">{{ item.database_name }}</div></td><td><span :class="['badge', tone(item)]">{{ label(item) }}</span><details v-if="item.last_message && item.last_status !== 'UP'"><summary>检查原因</summary><p>{{ item.last_message }}</p></details></td><td>{{ item.last_checked_at || '尚未检查' }}<div class="form-hint">每 {{ item.check_interval || 60 }} 秒</div></td><td>{{ (item.alert_group_names || []).join('、') || item.alert_group_name || '未绑定' }}</td><td class="table-actions"><router-link :to="detail(item)" class="btn btn-ghost btn-sm">详情</router-link><router-link :to="{ path: '/services/' + item.id + '/edit', query: { from: 'databases' } }" class="btn btn-ghost btn-sm">编辑</router-link><button class="btn btn-ghost btn-sm" :disabled="checking === item.id || item.enabled === false" @click="check(item)">立即检查</button></td></tr></tbody></table></div>
      <div class="pagination-bar"><span class="text-muted">共 {{ filtered.length }} 条 · 第 {{ page }} / {{ pages }} 页</span><div class="page-actions"><button class="btn btn-ghost btn-sm" :disabled="page <= 1" @click="page--">上一页</button><button class="btn btn-ghost btn-sm" :disabled="page >= pages" @click="page++">下一页</button></div></div>
    </section>
  </div>
</template>
<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { api } from '../api'
import { store } from '../store'
import { databaseTypes, isDatabase } from '../utils/assets'
const databases = ref([]), loading = ref(false), error = ref(''), checking = ref(null), keyword = ref(''), type = ref(''), status = ref(''), page = ref(1)
const types = { mysql: 'MySQL', oracle: 'Oracle', postgresql: 'PostgreSQL', jdbc: '通用 JDBC' }
const filtered = computed(() => databases.value.filter(item => (!type.value || (item.service_type === 'postgres' ? 'postgresql' : item.service_type) === type.value) && (!status.value || (item.enabled === false ? 'DISABLED' : item.last_status || 'UNKNOWN') === status.value) && `${item.service_name} ${item.host || ''} ${item.cluster_name || ''} ${item.database_name || ''}`.toLowerCase().includes(keyword.value.trim().toLowerCase())))
const pages = computed(() => Math.max(1, Math.ceil(filtered.value.length / 10)))
const paged = computed(() => filtered.value.slice((page.value - 1) * 10, page.value * 10))
watch([keyword,type,status], () => { page.value = 1 }); watch(pages, value => { page.value = Math.min(page.value, value) })
const detail = item => ({ path: '/services/' + item.id, query: { from: 'databases' } })
const label = item => item.enabled === false ? '已停用' : ({ UP: '检查正常', DOWN: '检查失败', UNKNOWN: '检查异常' }[item.last_status] || '等待检查')
const tone = item => item.enabled === false ? 'badge-unknown' : item.last_status === 'UP' ? 'badge-up' : item.last_status === 'DOWN' ? 'badge-down' : 'badge-warn'
// JDBC URLs may contain credentials. The host/port fields are sufficient for the list.
const target = item => item.host ? item.host + (item.port ? ':' + item.port : '') : item.database_connection_service_id ? '复用连接 #' + item.database_connection_service_id : 'JDBC 连接（详情中维护）'
async function load() { if (loading.value) return; loading.value = true; error.value = ''; try { databases.value = (await api.listServices(true)).filter(isDatabase) } catch (e) { error.value = e.message } finally { loading.value = false } }
async function check(item) { checking.value = item.id; try { const result = await api.checkAndStoreService(item.id); store.showToast(result.status === 'UP' ? '数据库检查正常' : '数据库检查完成，请查看结果', result.status === 'UP' ? 'success' : 'info'); await load() } catch (e) { error.value = e.message } finally { checking.value = null } }
let timer
onMounted(() => { load(); timer = setInterval(() => { if (document.visibilityState === 'visible' && checking.value == null) load() }, 15000) })
onBeforeUnmount(() => clearInterval(timer))
</script>
<style scoped>
.database-summary { margin-bottom: 20px; }
.database-summary .card { margin: 0; }
.database-summary strong { display: block; font-size: 26px; margin-top: 4px; }
.database-search { width: 220px; }
.database-filter { width: 140px; }
.database-table { min-width: 1000px; }
.connection-target { max-width: 240px; overflow-wrap: anywhere; }
.database-table details p { max-width: 280px; white-space: normal; overflow-wrap: anywhere; }
@media(max-width:560px) { .database-search,.database-filter { width: 100%; } }
</style>
