<template><section class="card"><h2>数据发送历史</h2><button class="btn btn-ghost" :disabled="loading" @click="load">刷新发送历史</button><p v-if="error" role="alert">{{ error }}</p><div class="table-wrap"><table class="table"><thead><tr><th>时间</th><th>结果</th><th>接收人</th><th>行数</th><th>详情</th></tr></thead><tbody><tr v-if="!items.length"><td colspan="5">暂无发送记录</td></tr><tr v-for="(item,index) in items" :key="item.id || index"><td>{{ item.createdAt || item.created_at }}</td><td>{{ item.success ? '成功' : '失败' }}</td><td>{{ item.recipientSummary }}</td><td>{{ item.rowCount }}</td><td>{{ item.message }}</td></tr></tbody></table></div></section></template>
<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api'
const items=ref([]),error=ref(''),loading=ref(false)
async function load(){loading.value=true;error.value='';try{items.value=await api.dataSendHistory()}catch(e){error.value=e.message}finally{loading.value=false}}
onMounted(load)
</script>
