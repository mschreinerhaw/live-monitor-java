<template>
  <fieldset class="contact-selector">
    <legend>{{ label }}</legend>
    <p class="contact-hint">{{ hint }}</p><p class="contact-hint">邮件、短信按联系人地址发送；群机器人调整 @ 提醒，HTTP / Webhook 接收端由渠道配置决定。</p>
    <p v-if="error" role="alert" class="contact-error">
      {{ error }} <button type="button" @click="load">重试</button>
    </p>
    <input
      v-model="search"
      type="search"
      class="form-control"
      aria-label="筛选可选联系人"
      placeholder="搜索姓名、邮箱或手机号"
    />
    <p class="selection-summary">
      已选择 {{ modelValue.length }} 位联系人<span v-if="inactive"
        >，其中 {{ inactive }} 位已停用，不参与投递</span
      >
    </p>
    <div class="contact-options">
      <label
        v-for="contact in filtered"
        :key="contact.id"
        :class="{ selected: modelValue.includes(contact.id), inactive: !contact.enabled }"
      >
        <input
          type="checkbox"
          :checked="modelValue.includes(contact.id)"
          :disabled="!contact.enabled && !modelValue.includes(contact.id)"
          :aria-label="'选择联系人 ' + contact.name"
          @change="toggle(contact.id, $event.target.checked)"
        />
        <span
          ><strong>{{ contact.name }}</strong
          ><small>{{
            [contact.email, contact.mobile, contact.wecom_user].filter(Boolean).join(' · ')
          }}</small></span
        ><em v-if="!contact.enabled">已停用</em>
      </label>
      <p v-if="!filtered.length" class="contact-hint">
        {{ loading ? '正在加载通讯录…' : '暂无匹配联系人，请先在告警中心的联系人通讯录中维护' }}
      </p>
    </div>
  </fieldset>
</template>
<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../api'
const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  label: { type: String, default: '选择告警联系人' },
  hint: { type: String, default: '未选择联系人时沿用原有渠道接收地址。联系人停用后不参与投递。' },
})
const emit = defineEmits(['update:modelValue'])
const contacts = ref([]),
  search = ref(''),
  loading = ref(false),
  error = ref('')
const filtered = computed(() =>
  contacts.value
    .filter((c) =>
      [c.name, c.email, c.mobile, c.wecom_user]
        .join(' ')
        .toLowerCase()
        .includes(search.value.toLowerCase())
    )
    .sort(
      (a, b) => Number(props.modelValue.includes(b.id)) - Number(props.modelValue.includes(a.id))
    )
)
const inactive = computed(
  () => contacts.value.filter((c) => !c.enabled && props.modelValue.includes(c.id)).length
)
function toggle(id, checked) {
  emit(
    'update:modelValue',
    checked
      ? [...new Set([...props.modelValue, id])]
      : props.modelValue.filter((value) => value !== id)
  )
}
async function load() {
  loading.value = true
  error.value = ''
  try {
    contacts.value = await api.listAlertContacts()
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>
<style scoped>
.contact-selector {
  border: 1px solid #dce7f4;
  border-radius: 8px;
  padding: 14px;
  min-width: 0;
  margin: 0;
}
.contact-selector legend {
  padding: 0 5px;
  color: #335575;
  font-size: 13px;
  font-weight: 600;
}
.contact-hint,
.selection-summary {
  font-size: 11px;
  color: #7b8fa4;
  line-height: 1.7;
  margin: 0 0 10px;
}
.selection-summary {
  margin: 9px 0;
}
.selection-summary span {
  color: #b36b22;
}
.contact-selector > .form-control {
  box-sizing: border-box;
  width: 100%;
  height: 36px;
}
.contact-options {
  display: flex;
  flex-direction: column;
  gap: 7px;
  max-height: 240px;
  overflow: auto;
}
.contact-options label {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px;
  border: 1px solid #e3ebf4;
  background: #fafcff;
  border-radius: 6px;
  cursor: pointer;
}
.contact-options label.selected {
  background: #eff8ff;
  border-color: #92c7ee;
}
.contact-options label.inactive {
  background: #f7f8fa;
  color: #8a98a7;
}
.contact-options label input {
  width: 16px;
  height: 16px;
  flex: none;
  accent-color: #0284c7;
}
.contact-options label > span {
  min-width: 0;
  flex: 1;
}
.contact-options strong {
  font-size: 12px;
}
.contact-options small {
  display: block;
  overflow-wrap: anywhere;
  font-size: 11px;
  font-weight: 400;
  color: #8091a3;
  margin-top: 4px;
}
.contact-options em {
  font-size: 10px;
  font-style: normal;
  white-space: nowrap;
}
.contact-error {
  font-size: 12px;
  color: #b84236;
}
.contact-error button {
  border: 0;
  background: none;
  color: #1689e7;
  cursor: pointer;
}
</style>
