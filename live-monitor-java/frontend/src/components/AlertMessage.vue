<template>
  <div class="alert-message">
    <div :class="['message-body', { collapsed: !expanded }]">
      <div v-if="isHtml" class="message-html" v-html="safeHtml"></div>
      <div v-else class="message-text">{{ content || '—' }}</div>
    </div>
    <button v-if="longMessage" type="button" class="message-toggle" :aria-expanded="expanded" @click="expanded = !expanded">{{ expanded ? '收起消息' : '展开消息' }}</button>
  </div>
</template>
<script setup>
import { computed, ref } from 'vue'
import DOMPurify from 'dompurify'
const props = defineProps({ content: { type: String, default: '' } })
const expanded = ref(false)
const text = computed(() => props.content || '')
const isHtml = computed(() => /<\/?[a-z][a-z0-9-]*(?:\s[^<>]*|\/?)>/i.test(text.value))
const safeHtml = computed(() => DOMPurify.sanitize(text.value, {
  ALLOWED_TAGS: ['div','p','br','span','strong','b','em','i','h1','h2','h3','h4','ul','ol','li','pre','code','table','thead','tbody','tr','th','td','hr'],
  ALLOWED_ATTR: ['colspan','rowspan'], ALLOW_DATA_ATTR: false, ALLOW_ARIA_ATTR: false
}))
const longMessage = computed(() => isHtml.value || text.value.length > 180 || text.value.split('\n').length > 5)
</script>
<style scoped>
.alert-message { min-width: 0; max-width: 100%; }
.message-body { overflow-wrap: anywhere; line-height: 1.65; font-size: 13px; }
.message-body.collapsed { max-height: 132px; overflow: hidden; }
.message-text { white-space: pre-wrap; }
.message-html :deep(pre), .message-html :deep(code) { white-space: pre-wrap; font-family: inherit; }
.message-html :deep(p), .message-html :deep(pre), .message-html :deep(ul) { margin: 6px 0; }
.message-html :deep(table) { width: 100%; border-collapse: collapse; }
.message-html :deep(td), .message-html :deep(th) { border: 1px solid #e2e8f0; padding: 5px 8px; }
.message-toggle { background: none; border: 0; padding: 6px 0 0; color: var(--primary-color, #0284c7); font: inherit; font-size: 12px; cursor: pointer; }
</style>
