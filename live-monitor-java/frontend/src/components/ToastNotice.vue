<template>
  <transition name="fade">
    <div v-if="store.toast.visible" :class="['toast-notice', store.toast.type]">
      <IconSvg :name="iconName" size="18" />
      <span>{{ store.toast.message }}</span>
    </div>
  </transition>
</template>

<script setup>
import { computed } from 'vue'
import { store } from '../store'
import IconSvg from './IconSvg.vue'

const iconName = computed(() => {
  if (store.toast.type === 'success') return 'check'
  if (store.toast.type === 'error') return 'x'
  return 'info'
})
</script>

<style scoped>
.toast-notice {
  position: fixed;
  top: 24px;
  right: 24px;
  z-index: 9999;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 18px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.1), 0 4px 6px -4px rgba(0, 0, 0, 0.1);
  color: #ffffff;
}

.toast-notice.info {
  background: #0284c7;
}

.toast-notice.success {
  background: #16a34a;
}

.toast-notice.error {
  background: #dc2626;
}

.fade-enter-active, .fade-leave-active {
  transition: opacity 0.2s, transform 0.2s;
}
.fade-enter-from, .fade-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
</style>
