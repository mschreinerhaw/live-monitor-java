<template><section class="card"><h2>最近告警记录</h2><p v-if="!records.length">暂无告警记录</p><div v-else class="table-wrap"><table class="table"><thead><tr><th>时间</th><th>服务</th><th>渠道 / 类型</th><th>状态</th><th>消息内容</th></tr></thead><tbody><tr v-for="(record,index) in records" :key="record.task_id || String(record.id) + '-' + index"><td>{{ record.created_at }}</td><td>{{ record.service_name }}</td><td>{{ record.alert_type }}</td><td>{{ record.alert_status }}</td><td style="white-space:pre-wrap">{{ record.alert_content }}</td></tr></tbody></table></div><router-link to="/alerts/tasks">查看任务与逐次投递结果</router-link></section></template>
<script setup>
defineProps({ records: { type: Array, default: () => [] } })
</script>
