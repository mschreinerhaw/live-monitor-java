<template>
  <div class="login-wrapper">
    <div class="login-card">
      <div class="login-head">
        <div class="login-brand">
          <IconSvg name="activity" size="24" />
        </div>
        <h2>Runtime OS</h2>
        <p>镜像与容器管理</p>
      </div>

      <form @submit.prevent="handleLogin" class="login-form">
        <div class="form-group">
          <label class="form-label">用户名</label>
          <input
            v-model="form.username"
            type="text"
            required
            class="form-control"
            placeholder="请输入管理员或运维账号"
            autocomplete="username"
          />
        </div>

        <div class="form-group">
          <label class="form-label">密码</label>
          <input
            v-model="form.password"
            type="password"
            required
            class="form-control"
            placeholder="请输入密码"
            autocomplete="current-password"
          />
        </div>

        <button :disabled="loading" type="submit" class="btn btn-primary btn-block">
          <span v-if="loading">登录中...</span>
          <span v-else>立即登录</span>
        </button>
      </form>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import { store } from '../store'

const router = useRouter()
const loading = ref(false)
const form = reactive({
  username: 'admin',
  password: ''
})

const handleLogin = async () => {
  loading.value = true
  try {
    await api.login(form)
    await store.fetchCurrentUser()
    store.showToast('登录成功', 'success')
    router.push('/ops/containers')
  } catch (err) {
    store.showToast(err.message || '用户名或密码错误', 'error')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-wrapper {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: radial-gradient(circle at top, #1e293b 0%, #0f172a 100%);
  padding: 20px;
}

.login-card {
  width: 100%;
  max-width: 400px;
  background: #ffffff;
  border-radius: 12px;
  box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.3), 0 8px 10px -6px rgba(0, 0, 0, 0.2);
  border: 1px solid rgba(255, 255, 255, 0.1);
  padding: 36px 32px;
}

.login-head {
  text-align: center;
  margin-bottom: 28px;
}

.login-brand {
  width: 52px;
  height: 52px;
  margin: 0 auto 14px;
  background: linear-gradient(135deg, #0284c7 0%, #0369a1 100%);
  color: #ffffff;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 10px rgba(2, 132, 199, 0.4);
}

.login-head h2 {
  font-size: 22px;
  font-weight: 700;
  color: #0f172a;
}
.login-head p {
  font-size: 13px;
  color: #64748b;
  margin-top: 4px;
}

.btn-block {
  width: 100%;
  height: 42px;
  font-size: 14px;
  font-weight: 600;
  margin-top: 10px;
}
</style>
