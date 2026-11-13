import { computed, reactive, ref, unref, watch } from 'vue'

// Refreshing a list keeps its page; filters reset it and shorter results clamp it.
export function usePagination(source, resetOn = []) {
  const page = ref(1), pageSize = ref(10)
  const rows = computed(() => (typeof source === 'function' ? source() : unref(source)) || [])
  const total = computed(() => rows.value.length)
  const pages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))
  watch(pages, value => { page.value = Math.min(page.value, value) })
  watch(pageSize, () => { page.value = 1 })
  if (resetOn.length) watch(resetOn, () => { page.value = 1 })
  return reactive({ page, pageSize, total, pages, items: computed(() => rows.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value)) })
}
