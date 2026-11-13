<template>
  <section class="card">
    <h2>告警消息列表</h2><p v-if="!records.length">{{ loading ? '加载中…' : '暂无告警记录' }}</p>
    <div v-else class="table-wrap"><table class="table history-table">
      <thead><tr><th>时间</th><th>服务</th><th>渠道 / 类型</th><th>状态</th><th>消息内容</th></tr></thead>
      <tbody><tr v-for="(record,index) in records" :key="record.task_id || String(record.id) + '-' + index">
        <td>{{ record.created_at }}</td><td>{{ record.service_name }}</td><td>{{ record.alert_type }}</td>
        <td><span :class="['badge', deliveryTone(record.alert_status)]">{{ deliveryLabel(record.alert_status) }}</span></td>
        <td><AlertMessage :content="record.alert_content" /></td>
      </tr></tbody>
    </table></div>
    <router-link class="task-link" to="/alerts/tasks">查看任务与逐次投递结果</router-link>
  </section>
</template>
<script setup>
import AlertMessage from './AlertMessage.vue'
import { deliveryLabel, deliveryTone } from '../utils/presentation'
defineProps({ records: { type: Array, default: () => [] }, loading: Boolean })
</script>
<style scoped>
h2 { margin-bottom: 16px; font-size: 20px; }
.history-table { table-layout: fixed; min-width: 850px; }
.history-table th:nth-child(1) { width: 155px; }
.history-table th:nth-child(2) { width: 160px; }
.history-table th:nth-child(3) { width: 110px; }
.history-table th:nth-child(4) { width: 120px; }
.history-table td { vertical-align: top; overflow-wrap: anywhere; }
.task-link { display: inline-block; margin-top: 16px; font-size: 13px; }
</style>
