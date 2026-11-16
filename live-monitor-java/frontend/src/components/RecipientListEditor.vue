<template>
  <section class="recipient-editor" :aria-label="label">
    <div class="recipient-heading">
      <strong>{{ label }}</strong
      ><span>{{ values.length }} 位接收人</span>
    </div>
    <div class="recipient-add">
      <textarea
        rows="2"
        :inputmode="kind === 'phone' ? 'tel' : kind === 'email' ? 'email' : 'text'"
        ref="input"
        v-model="draft"
        class="form-control"
        :aria-label="'添加' + label"
        :placeholder="placeholder"
        @input="validateDraft"
        @keydown.enter.prevent="commit"
        @blur="commit"
      ></textarea>
      <button type="button" class="btn btn-ghost btn-sm" @click="commit">添加</button>
    </div>
    <p v-if="error" class="recipient-error" role="alert">{{ error }}</p>
    <p v-else class="recipient-hint">支持逗号、分号或换行分隔；重复接收人自动合并。</p>
    <div v-if="values.length" class="recipient-table-wrap">
      <table>
        <thead>
          <tr>
            <th>{{ kind === 'phone' ? '手机号' : kind === 'email' ? '邮箱地址' : '成员 ID' }}</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="value in values" :key="value">
            <td>{{ value }}</td>
            <td>
              <button
                type="button"
                class="recipient-remove"
                :aria-label="'移除' + value"
                @click="remove(value)"
              >
                移除
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-else class="recipient-empty">尚未配置{{ label }}</p>
  </section>
</template>
<script setup>
import { computed, ref, watch } from 'vue'
import { splitRecipients } from '../utils/alertRecipients'
const props = defineProps({
  modelValue: String,
  label: { type: String, required: true },
  kind: { type: String, default: 'email' },
  placeholder: String,
})
const emit = defineEmits(['update:modelValue'])
const values = computed(() => splitRecipients(props.modelValue))
const draft = ref(''),
  error = ref(''),
  input = ref(null)
const valid = (value) =>
  props.kind === 'email'
    ? /^[^\s@]+@[^\s@]+$/.test(value)
    : props.kind === 'phone'
    ? /^\+?\d{5,20}$/.test(value)
    : !/[,;，；\s]/.test(value)
function validateDraft() {
  const invalid = splitRecipients(draft.value).find((value) => !valid(value))
  error.value = invalid ? `请检查${props.label}格式：${invalid}` : ''
  input.value?.setCustomValidity(error.value)
  return !invalid
}
function commit() {
  if (!validateDraft()) return false
  if (draft.value.trim())
    emit(
      'update:modelValue',
      [...new Set([...values.value, ...splitRecipients(draft.value)])].join(',')
    )
  draft.value = ''
  return true
}
function remove(value) {
  emit('update:modelValue', values.value.filter((item) => item !== value).join(','))
}
watch(
  () => props.modelValue,
  () => {
    if (!draft.value) {
      error.value = ''
      input.value?.setCustomValidity('')
    }
  }
)
defineExpose({ commit })
</script>
<style scoped>
.recipient-editor {
  border: 1px solid #dce7f4;
  border-radius: 8px;
  background: #fbfdff;
  padding: 14px;
  min-width: 0;
}
.recipient-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}
.recipient-heading strong {
  font-size: 13px;
  color: #334155;
}
.recipient-heading span,
.recipient-hint,
.recipient-empty {
  font-size: 11px;
  color: #7c8da0;
}
.recipient-add {
  display: flex;
  align-items: center;
  gap: 8px;
}
.recipient-add textarea {
  min-width: 0;
  flex: 1;
}
.recipient-hint,
.recipient-error {
  margin: 7px 0;
  font-size: 11px;
  line-height: 1.6;
}
.recipient-error {
  color: #b93838;
}
.recipient-table-wrap {
  overflow-x: auto;
  margin-top: 10px;
}
.recipient-editor table {
  border-collapse: collapse;
  width: 100%;
  font-size: 12px;
}
.recipient-editor th,
.recipient-editor td {
  text-align: left;
  padding: 8px;
  border-bottom: 1px solid #e4ebf3;
  overflow-wrap: anywhere;
}
.recipient-editor th {
  background: #f0f5fb;
  font-weight: 500;
  color: #657c93;
  position: sticky;
  top: 0;
}
.recipient-editor th:last-child,
.recipient-editor td:last-child {
  width: 48px;
  text-align: right;
}
.recipient-remove {
  border: 0;
  background: none;
  color: #bf4c42;
  cursor: pointer;
  font-size: 11px;
  white-space: nowrap;
}
.recipient-empty {
  margin: 10px 0 0;
}
.recipient-remove:focus-visible {
  outline: 2px solid #1689e7;
  outline-offset: 2px;
}
</style>
