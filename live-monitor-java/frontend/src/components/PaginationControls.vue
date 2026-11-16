<template>
  <nav class="list-pagination" :aria-label="label + '分页'">
    <span class="text-muted" role="status">共 {{ total }} 条 · 显示 {{ total ? (page - 1) * pageSize + 1 : 0 }}–{{ Math.min(page * pageSize, total) }} 条 · 第 {{ page }} / {{ pages }} 页</span>
    <div class="pagination-buttons"><label>每页条数<select class="form-control" :aria-label="label + '每页条数'" :value="pageSize" :disabled="loading" @change="resize"><option v-for="size in pageSizes" :key="size" :value="size">{{ size }} 条</option></select></label><button type="button" class="btn btn-ghost btn-sm" :disabled="loading || page <= 1" @click="go(1)">首页</button><button type="button" class="btn btn-ghost btn-sm" :disabled="loading || page <= 1" @click="go(page - 1)">上一页</button><label class="jump-label">跳至<input class="form-control" type="number" min="1" :max="pages" :value="page" :disabled="loading || total === 0" :aria-label="label + '跳转页码'" @change="jump"></label><button type="button" class="btn btn-ghost btn-sm" :disabled="loading || page >= pages" @click="go(page + 1)">下一页</button><button type="button" class="btn btn-ghost btn-sm" :disabled="loading || page >= pages" @click="go(pages)">末页</button></div>
  </nav>
</template>
<script setup>
import { computed } from 'vue'
const props = defineProps({ pageSizes: { type: Array, default: () => [10,20,50,100] }, page: { type: Number, default: 1 }, pageSize: { type: Number, default: 10 }, total: { type: Number, default: 0 }, loading: Boolean, label: { type: String, default: '列表' } })
const emit = defineEmits(['update:page','update:pageSize','change'])
const pages = computed(() => Math.max(1, Math.ceil(props.total / props.pageSize)))
const clamp = value => Math.max(1, Math.min(pages.value, Math.floor(Number(value) || 1)))
function go(value) { emit('update:page', clamp(value)); emit('change') }
function resize(event) { emit('update:pageSize', Number(event.target.value)); emit('update:page', 1); emit('change') }
function jump(event) { const value = clamp(event.target.value); event.target.value = value; go(value) }
</script>
<style scoped>
.list-pagination { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; margin-top: 16px; font-size: 12px; }
.pagination-buttons { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; }
.pagination-buttons label { display: inline-flex; align-items: center; gap: 6px; white-space: nowrap; }
.pagination-buttons .form-control { height: 32px; min-height: 32px; padding: 4px 6px; width: 84px; }
.pagination-buttons input.form-control { width: 60px; }
@media(max-width: 560px) { .jump-label { display: none !important; } }
</style>
