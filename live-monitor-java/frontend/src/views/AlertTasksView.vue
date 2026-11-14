<template><div class="page-container"><div class="page-header"><div class="page-header-title"><h1>告警任务与重试管理</h1><p>查看各渠道的发送结果、重试计划和逐次发送记录。</p></div><router-link to="/alerts/audit" class="btn btn-ghost">查看通知审计</router-link></div><AlertTasksPanel /></div></template>
<script setup>
import AlertTasksPanel from '../components/AlertTasksPanel.vue'
</script>
