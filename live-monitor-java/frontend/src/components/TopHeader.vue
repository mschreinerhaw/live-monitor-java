<template>
  <header class="top-navbar">
    <div class="navbar-left">
      <!-- 面包屑导航 -->
      <div class="breadcrumb-trail">
        <span class="bc-root">平台</span>
        <span class="bc-sep">/</span>
        <span class="bc-current">{{ currentRouteTitle }}</span>
      </div>
    </div>

    <div class="navbar-right">
      <!-- 快捷入口 -->
      <router-link to="/services/new" class="quick-action-btn">
        <IconSvg name="plus" size="14" />
        <span>接入服务</span>
      </router-link>

      <router-link to="/data-send" class="quick-action-btn">
        <IconSvg name="send" size="14" />
        <span>数据发送</span>
      </router-link>

      <!-- 告警通知铃铛 -->
      <div class="notify-wrapper">
        <button class="topbar-icon-btn" @click="toggleNotifications" title="告警通知">
          <IconSvg name="bell" size="18" />
          <span v-if="store.notifications.unreadCount > 0" class="notify-badge">
            {{ store.notifications.unreadCount }}
          </span>
        </button>

        <div v-if="showNotificationPanel" class="notify-dropdown">
          <div class="notify-dropdown-head">
            <strong>告警通知</strong>
            <button class="text-btn" @click="markAllSeen">全部已读</button>
          </div>
          <div class="notify-list">
            <div v-if="!store.notifications.list.length" class="notify-empty">暂无告警记录</div>
            <div
              v-for="item in store.notifications.list"
              :key="item.id"
              class="notify-item"
            >
              <div class="notify-item-title">{{ item.service_name || '服务异常' }}</div>
              <div class="notify-item-msg">{{ item.message || item.alert_type }}</div>
              <div class="notify-item-time">{{ item.created_at || item.checked_at }}</div>
            </div>
          </div>
        </div>
      </div>

      <!-- 用户下拉信息 -->
      <div class="user-profile-dropdown">
        <button class="user-profile-btn" @click="showUserMenu = !showUserMenu">
          <span class="user-avatar">{{ (store.user?.user_id || 'A').slice(0, 1).toUpperCase() }}</span>
          <span class="user-name">{{ store.user?.user_id || store.user?.name || 'admin' }}</span>
          <IconSvg name="chevron" size="14" />
        </button>

        <div v-if="showUserMenu" class="user-popover-menu">
          <router-link v-if="store.user?.admin" to="/admin" class="popover-item" @click="showUserMenu = false">
            <IconSvg name="users" size="14" />
            <span>用户维护</span>
          </router-link>
          <button class="popover-item logout" @click="handleLogout">
            <IconSvg name="logout" size="14" />
            <span>退出登录</span>
          </button>
        </div>
      </div>
    </div>
  </header>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { store } from '../store'
import { api } from '../api'
import IconSvg from './IconSvg.vue'

const router = useRouter()
const route = useRoute()
const showNotificationPanel = ref(false)
const showUserMenu = ref(false)

const currentRouteTitle = computed(() => {
  const map = {
    '/dashboard': '监控总览',
    '/hosts': '主机维护',
    '/services/new': '添加服务',
    '/data-send': '数据发送',
    '/alerts/settings': '告警规则配置',
    '/alerts/audit': '通知审计流水',
    '/admin': '用户安全管理'
  }
  return map[route.path] || '监控详情'
})

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
  if (route.path !== '/login') {
    await store.fetchCurrentUser()
    await store.fetchNotifications()
    notifyInterval = setInterval(() => {
      if (store.user && route.path !== '/login') {
        store.fetchNotifications()
      }
    }, 15000)
  }
})

onBeforeUnmount(() => {
  if (notifyInterval) clearInterval(notifyInterval)
})
</script>

<style scoped>
.top-navbar {
  height: 56px;
  background: #ffffff;
  border-bottom: 1px solid var(--border-color);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  position: sticky;
  top: 0;
  z-index: 100;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
}

.breadcrumb-trail {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13.5px;
}
.bc-root {
  color: var(--text-muted);
}
.bc-sep {
  color: var(--border-color);
}
.bc-current {
  color: var(--text-main);
  font-weight: 600;
}

.navbar-right {
  display: flex;
  align-items: center;
  gap: 14px;
}

.quick-action-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  font-weight: 500;
  padding: 5px 12px;
  border-radius: 6px;
  background: #f8fafc;
  color: #334155;
  border: 1px solid #cbd5e1;
  text-decoration: none;
  transition: all 0.15s;
}
.quick-action-btn:hover {
  background: #f0f9ff;
  border-color: #0284c7;
  color: #0284c7;
}

.topbar-icon-btn {
  background: transparent;
  border: none;
  color: #475569;
  width: 36px;
  height: 36px;
  border-radius: 8px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  transition: background 0.15s;
}
.topbar-icon-btn:hover {
  background: #f1f5f9;
  color: #0f172a;
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

.notify-wrapper, .user-profile-dropdown {
  position: relative;
}

.notify-dropdown {
  position: absolute;
  top: 46px;
  right: 0;
  width: 320px;
  background: #ffffff;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-lg);
  color: var(--text-main);
  overflow: hidden;
  z-index: 1000;
}

.notify-dropdown-head {
  padding: 12px 16px;
  border-bottom: 1px solid var(--border-color);
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: #f8fafc;
}
.text-btn {
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
  color: var(--text-light);
  font-size: 13px;
}
.notify-item {
  padding: 10px 16px;
  border-bottom: 1px solid #f8fafc;
  font-size: 12px;
}
.notify-item-title {
  font-weight: 600;
  color: var(--text-main);
}
.notify-item-msg {
  color: var(--text-muted);
  margin-top: 2px;
}
.notify-item-time {
  color: var(--text-light);
  font-size: 11px;
  margin-top: 4px;
}

.user-profile-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  background: #f8fafc;
  border: 1px solid var(--border-color);
  padding: 4px 10px;
  border-radius: 20px;
  cursor: pointer;
  font-size: 13px;
  color: var(--text-main);
  transition: all 0.15s;
}
.user-profile-btn:hover {
  background: #f1f5f9;
}
.user-avatar {
  width: 26px;
  height: 26px;
  background: #0284c7;
  color: #ffffff;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
}

.user-popover-menu {
  position: absolute;
  top: 44px;
  right: 0;
  background: #ffffff;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-lg);
  min-width: 130px;
  z-index: 1000;
  overflow: hidden;
}
.popover-item {
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
.popover-item:hover {
  background: #f8fafc;
  color: #0284c7;
}
.popover-item.logout {
  color: #dc2626;
  border-top: 1px solid #f1f5f9;
}
.popover-item.logout:hover {
  background: #fef2f2;
}
</style>
