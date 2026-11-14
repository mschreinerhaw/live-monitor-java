<template>
  <div class="page-container external-requests-page"><div class="page-header"><div class="page-header-title"><h1>外部消息请求</h1><p>独立查询外部消息的来源、项目、处理状态和渠道结果。</p></div><div class="page-actions"><router-link to="/external-projects" class="btn btn-ghost">项目配置与接入说明</router-link><button class="btn btn-ghost" :disabled="loading" @click="load">刷新</button></div></div>
    <section class="card"><div class="card-header"><h2 class="card-title">外部消息请求审计</h2></div><form class="request-filters" @submit.prevent="page = 1; load()"><label class="filter-label">搜索请求<input v-model="query" class="form-control" placeholder="项目 / 来源 / 消息 / 错误"></label><label class="filter-label">请求状态<select v-model="status" class="form-control" aria-label="请求状态"><option value="">全部状态</option><option v-for="(text,value) in statuses" :key="value" :value="value">{{ text }}</option></select></label><div class="page-actions"><button class="btn btn-primary" :disabled="loading">查询请求</button><button type="button" class="btn btn-ghost" :disabled="loading" @click="query = ''; status = ''; page = 1; load()">重置筛选</button></div></form>
      <p v-if="error" role="alert" class="inline-error">{{ error }}</p><div class="table-wrap"><table class="table" :aria-busy="loading"><thead><tr><th>时间</th><th>来源</th><th>项目</th><th>状态</th><th>消息 / 渠道结果</th></tr></thead><tbody><tr v-if="!items.length"><td colspan="5" class="empty-state">{{ loading ? '正在加载请求…' : '没有符合条件的请求' }}</td></tr><tr v-for="item in items" :key="item.id"><td>{{ item.created_at }}</td><td>{{ item.client_ip || '—' }}</td><td>{{ item.project_name || '未匹配项目' }}</td><td><span :class="['badge', item.request_status === 'SUCCESS' ? 'badge-up' : ['FAILED','REJECTED'].includes(item.request_status) ? 'badge-down' : 'badge-info']">{{ statuses[item.request_status] || item.request_status || '未知状态' }}</span></td><td class="request-message"><details><summary>{{ item.message_preview || item.message_summary || item.request_message || item.error_message || '查看请求与渠道结果' }}</summary><pre>{{ JSON.stringify(item,null,2) }}</pre></details></td></tr></tbody></table></div>
      <PaginationControls v-model:page="page" v-model:page-size="pageSize" :total="total" :loading="loading" label="外部消息请求" @change="load" />
    </section>
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api'
import PaginationControls from '../components/PaginationControls.vue'
const items = ref([]), query = ref(''), status = ref(''), error = ref(''), loading = ref(false), page = ref(1), pageSize = ref(10), total = ref(0)
const statuses = { SUCCESS: '处理成功', FAILED: '处理失败', REJECTED: '请求被拒绝', CONFIG_ENABLED: '转发已启用', CONFIG_DISABLED: '转发已停用' }
let generation = 0
async function load() { const request = ++generation; loading.value = true; error.value = ''; try { const data = await api.externalAudit({ page: page.value, page_size: pageSize.value, query: query.value, status: status.value }); if (request !== generation) return; items.value = data.items || []; total.value = data.total || 0; page.value = data.page || page.value } catch (e) { if (request === generation) error.value = e.message } finally { if (request === generation) loading.value = false } }
onMounted(load)
</script>
<style scoped>
.request-filters { display: grid; grid-template-columns: minmax(160px,1fr) minmax(130px,200px) auto; gap: 12px; align-items: end; margin-bottom: 16px; }
.request-message { max-width: 560px; overflow-wrap: anywhere; }
.request-message pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 360px; overflow-y: auto; }
@media(max-width:640px) { .request-filters { grid-template-columns: 1fr; } }
</style>
