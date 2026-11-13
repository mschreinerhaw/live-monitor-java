<template>
  <div class="page-container">
    <div class="page-header"><div class="page-header-title"><h1>最近告警记录</h1><p>查看告警消息与渠道结果；任务重试和逐次投递详情请前往告警任务菜单。</p></div></div>
    <section class="card"><form class="history-filters" @submit.prevent="load">
      <label>监控服务<select v-model="serviceId" class="form-control"><option value="">全部服务</option><option v-for="service in services" :key="service.id" :value="service.id">{{ service.service_name }}</option></select></label>
      <label>最近记录数量<select v-model="limit" class="form-control"><option v-for="count in [20,50,100,200]" :key="count" :value="count">{{ count }} 条</option></select></label>
      <button class="btn btn-primary" :disabled="loading">查询 / 刷新</button>
    </form><p v-if="error" role="alert">{{ error }}</p></section>
    <AlertHistoryTable :records="records" :loading="loading" />
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api'
import AlertHistoryTable from '../components/AlertHistoryTable.vue'
const services = ref([]), records = ref([]), serviceId = ref(''), limit = ref(50), loading = ref(false), error = ref('')
async function load() {
  loading.value = true; error.value = ''
  try { records.value = serviceId.value ? await api.serviceAlerts(serviceId.value, limit.value) : await api.allAlerts(limit.value) }
  catch (e) { error.value = e.message } finally { loading.value = false }
}
onMounted(async () => { await load(); try { services.value = await api.listServices(true) } catch (e) { error.value = e.message } })
</script>
<style scoped>
.history-filters { display: flex; flex-wrap: wrap; gap: 16px; align-items: end; }
.history-filters label { display: flex; flex-direction: column; gap: 6px; font-size: 13px; min-width: 200px; }
.history-filters .btn, .history-filters .form-control { height: 40px; }
</style>
