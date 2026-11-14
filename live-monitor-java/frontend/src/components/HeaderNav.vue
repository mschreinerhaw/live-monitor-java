<template>
  <header class="topbar">
    <div class="topbar-inner">
      <router-link to="/dashboard" class="brand">
        <span class="brand-mark">
          <IconSvg name="activity" size="18" />
        </span>
        <div class="brand-copy">
          <strong>Live Monitor</strong>
          <small>轻量事件驱动监控平台</small>
        </div>
      </router-link>

      <nav class="nav">
        <router-link to="/dashboard" active-class="active">监控首页</router-link>
        <router-link to="/hosts" active-class="active">主机维护</router-link>
        <router-link to="/services/new" active-class="active">添加服务</router-link>
        <router-link to="/alerts/settings" active-class="active">告警设置</router-link>
        <router-link to="/alerts/audit" active-class="active">告警审计</router-link>
      </nav>

      <div class="topbar-user">
        <!-- 告警通知铃铛 -->
        <div class="notify-wrapper">
          <button class="icon-btn" @click="toggleNotifications" title="告警通知">
            <IconSvg name="bell" size="18" />
            <span v-if="store.notifications.unreadCount > 0" class="notify-badge">
              {{ store.notifications.unreadCount }}
            </span>
          </button>

          <div v-if="showNotificationPanel" class="notify-panel">
            <div class="notify-panel-head">
              <strong>告警通知</strong>
              <button class="link-btn" @click="markAllSeen">全部已读</button>
            </div>
            <div class="notify-list">
              <div v-if="!store.notifications.list.length" class="notify-empty">暂无告警记录</div>
              <div
                v-for="item in store.notifications.list"
                :key="item.id"
                class="notify-item"
              >
                <div class="notify-title">{{ item.service_name || '服务异常' }}</div>
                <div class="notify-msg">{{ item.message || item.alert_type }}</div>
                <div class="notify-time">{{ item.created_at || item.checked_at }}</div>
              </div>
            </div>
          </div>
        </div>

        <!-- 用户信息与退出 -->
        <div class="user-dropdown">
          <button class="user-btn" @click="showUserMenu = !showUserMenu">
            <span>{{ store.user?.user_id || store.user?.name || 'admin' }}</span>
            <IconSvg name="chevron" size="14" />
          </button>

          <div v-if="showUserMenu" class="user-menu">
            <router-link v-if="store.user?.admin" to="/admin" class="user-menu-item" @click="showUserMenu = false">
              <IconSvg name="users" size="14" />
              <span>用户维护</span>
            </router-link>
            <button class="user-menu-item logout" @click="handleLogout">
              <IconSvg name="logout" size="14" />
              <span>退出登录</span>
            </button>
          </div>
        </div>
      </div>
    </div>
  </header>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { store } from '../store'
import { api } from '../api'
import IconSvg from './IconSvg.vue'

const router = useRouter()
const showNotificationPanel = ref(false)
const showUserMenu = ref(false)

const toggleNotifications = () => {
  showNotificationPanel.value = !showNotificationPanel.value
  showUserMenu.value = false
}

const markAllSeen = () => {
  store.notifications.unreadCount = 0
  store.showToast('已标记所有告警为已读', 'success')
}

const handleLogout = async () => {
  try {
    await api.logout()
  } catch (_) {}
  store.user = null
  router.push('/login')
}

let notifyInterval = null
onMounted(async () => {
  await store.fetchCurrentUser()
  await store.fetchNotifications()
  notifyInterval = setInterval(() => {
    store.fetchNotifications()
  }, 15000)
})

onBeforeUnmount(() => {
  if (notifyInterval) clearInterval(notifyInterval)
})
</script>

<style scoped>
.topbar {
  background: #176b87;
  color: #ffffff;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
  position: sticky;
  top: 0;
  z-index: 100;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.08);
}

.topbar-inner {
  max-width: 1400px;
  margin: 0 auto;
  padding: 0 20px;
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  color: #ffffff;
  text-decoration: none;
}
.brand-mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  background: rgba(255, 255, 255, 0.15);
  border-radius: 8px;
}
.brand-copy strong {
  font-size: 15px;
  font-weight: 700;
  letter-spacing: -0.2px;
  display: block;
  line-height: 1.2;
}
.brand-copy small {
  font-size: 11px;
  opacity: 0.8;
  display: block;
}

.nav {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-left: 32px;
  flex: 1;
}

.nav a {
  color: rgba(255, 255, 255, 0.85);
  font-size: 13.5px;
  font-weight: 500;
  padding: 6px 12px;
  border-radius: 6px;
  text-decoration: none;
  transition: all 0.15s ease;
}
.nav a:hover {
  color: #ffffff;
  background: rgba(255, 255, 255, 0.12);
}
.nav a.active {
  color: #ffffff;
  background: rgba(255, 255, 255, 0.2);
  font-weight: 600;
}

.topbar-user {
  display: flex;
  align-items: center;
  gap: 12px;
}

.icon-btn {
  background: transparent;
  border: none;
  color: rgba(255, 255, 255, 0.9);
  width: 34px;
  height: 34px;
  border-radius: 6px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  transition: background 0.15s;
}
.icon-btn:hover {
  background: rgba(255, 255, 255, 0.15);
}

.notify-badge {
  position: absolute;
  top: 4px;
  right: 4px;
  background: #ef4444;
  color: #ffffff;
  font-size: 10px;
  font-weight: 700;
  padding: 1px 5px;
  border-radius: 10px;
  line-height: 1;
}

.notify-wrapper {
  position: relative;
}

.notify-panel {
  position: absolute;
  top: 44px;
  right: 0;
  width: 320px;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  box-shadow: 0 10px 25px -5px rgba(0,0,0,0.15);
  color: #1e293b;
  overflow: hidden;
  z-index: 1000;
}
.notify-panel-head {
  padding: 12px 14px;
  border-bottom: 1px solid #f1f5f9;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.link-btn {
  background: none;
  border: none;
  color: #0284c7;
  font-size: 12px;
  cursor: pointer;
}
.notify-list {
  max-height: 320px;
  overflow-y: auto;
}
.notify-empty {
  padding: 24px;
  text-align: center;
  color: #94a3b8;
  font-size: 13px;
}
.notify-item {
  padding: 10px 14px;
  border-bottom: 1px solid #f8fafc;
  font-size: 12px;
}
.notify-title {
  font-weight: 600;
  color: #0f172a;
}
.notify-msg {
  color: #64748b;
  margin-top: 2px;
}
.notify-time {
  color: #94a3b8;
  font-size: 11px;
  margin-top: 4px;
}

.user-dropdown {
  position: relative;
}
.user-btn {
  background: rgba(255, 255, 255, 0.12);
  border: 1px solid rgba(255, 255, 255, 0.2);
  color: #ffffff;
  padding: 5px 10px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.user-btn:hover {
  background: rgba(255, 255, 255, 0.2);
}
.user-menu {
  position: absolute;
  top: 40px;
  right: 0;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  box-shadow: 0 10px 15px -3px rgba(0,0,0,0.1);
  min-width: 130px;
  z-index: 1000;
  overflow: hidden;
}
.user-menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 9px 14px;
  font-size: 13px;
  color: #334155;
  text-decoration: none;
  background: none;
  border: none;
  cursor: pointer;
  text-align: left;
}
.user-menu-item:hover {
  background: #f8fafc;
  color: #0284c7;
}
.user-menu-item.logout {
  color: #dc2626;
  border-top: 1px solid #f1f5f9;
}
.user-menu-item.logout:hover {
  background: #fef2f2;
}
</style>
