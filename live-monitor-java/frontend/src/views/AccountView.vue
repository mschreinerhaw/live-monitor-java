<template><div class="page-container"><h1>修改个人密码</h1><form class="card" @submit.prevent="save"><label class="form-group">当前密码<input v-model="form.current_password" type="password" autocomplete="current-password" required class="form-control"></label><label class="form-group">新密码<input v-model="form.new_password" type="password" autocomplete="new-password" required maxlength="128" class="form-control"></label><label class="form-group">确认新密码<input v-model="confirmation" type="password" autocomplete="new-password" required class="form-control"></label><p v-if="error" role="alert">{{ error }}</p><button class="btn btn-primary" :disabled="saving">修改密码</button></form></div></template>
<script setup>
import { reactive, ref } from 'vue'
import { api } from '../api'
import { store } from '../store'
const form = reactive({ current_password: '', new_password: '' }), confirmation = ref(''), error = ref(''), saving = ref(false)
async function save() {
  error.value = ''
  if (confirmation.value !== form.new_password) { error.value = '两次新密码不一致'; return }
  saving.value = true
  try { await api.changePassword(form); form.current_password = ''; form.new_password = ''; confirmation.value = ''; store.showToast('密码已修改', 'success') }
  catch (e) { error.value = e.message } finally { saving.value = false }
}
</script>
