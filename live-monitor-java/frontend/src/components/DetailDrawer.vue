<template>
  <Teleport to="body">
    <div v-if="open" class="drawer-overlay" @click.self="$emit('close')" @keydown="keydown">
      <section ref="panel" class="detail-drawer" role="dialog" aria-modal="true" :aria-labelledby="id">
        <header class="drawer-header"><h2 :id="id">{{ title }}</h2><button type="button" class="btn btn-ghost btn-sm" aria-label="关闭详情" @click="$emit('close')"><IconSvg name="x" size="18" /></button></header>
        <div class="drawer-body"><slot /></div>
        <footer class="drawer-footer"><slot name="footer" /><button type="button" class="btn btn-ghost" @click="$emit('close')">关闭详情</button></footer>
      </section>
    </div>
  </Teleport>
</template>
<script setup>
import { ref, watch, nextTick, onBeforeUnmount } from 'vue'
import IconSvg from './IconSvg.vue'
const props = defineProps({ open: Boolean, title: String, id: { type: String, default: 'detail-drawer-title' } })
const emit = defineEmits(['close']), panel = ref(null)
let previous = null, overflow = ''
watch(() => props.open, async value => {
  if (value) { previous = document.activeElement; overflow = document.body.style.overflow; document.body.style.overflow = 'hidden'; await nextTick(); panel.value?.querySelector('button')?.focus() }
  else { document.body.style.overflow = overflow; if (previous?.isConnected) previous.focus() }
})
function keydown(event) {
  if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); emit('close') }
  if (event.key === 'Tab') {
    const elements = [...panel.value.querySelectorAll('button,a,input,select,textarea,summary,[tabindex="0"]')].filter(element => !element.disabled && element.getClientRects().length)
    const first = elements[0], last = elements[elements.length - 1]
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
  }
}
onBeforeUnmount(() => { if (props.open) document.body.style.overflow = overflow })
</script>
