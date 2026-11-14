<template>
  <header class="top-navbar">
    <div class="navbar-left">
      <button class="topbar-icon-btn mobile-menu-toggle" aria-label="打开导航菜单" aria-controls="primary-navigation" :aria-expanded="store.navigationOpen" @click="store.navigationOpen = !store.navigationOpen"><IconSvg name="menu" size="20" /></button>
      <!-- 面包屑导航 -->
      <nav class="breadcrumb-trail" aria-label="面包屑">
        <router-link to="/dashboard" class="bc-root">平台</router-link>
        <span class="bc-sep">/</span>
        <span class="bc-current">{{ currentRouteTitle }}</span>
      </nav>
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
        <button ref="notificationButton" class="topbar-icon-btn" @click="toggleNotifications" title="告警通知" aria-label="告警通知" :aria-expanded="showNotificationPanel" aria-controls="notification-panel">
          <IconSvg name="bell" size="18" />
          <span v-if="store.notifications.unreadCount > 0" class="notify-badge">
            {{ store.notifications.unreadCount }}
          </span>
        </button>

        <section v-if="showNotificationPanel" id="notification-panel" class="notify-dropdown" aria-label="通知动态">
          <div class="notify-dropdown-head">
            <strong>通知动态 <span class="text-muted">最近 {{ store.notifications.list.length }} 条</span></strong>
            <button class="text-btn" :disabled="!store.notifications.unreadCount" @click="markAllSeen">全部已读</button>
          </div>
          <div class="notify-list">
            <div v-if="!store.notifications.list.length" class="notify-empty">暂无告警记录</div>
            <router-link
              v-for="item in notificationPager.items"
              :key="item.task_id || item.id"
              class="notify-item"
              :to="{ path: '/alerts/recent', query: { service_id: item.service_id } }"
            >
              <div class="notify-item-title">{{ item.service_name || '监控服务' }} <span :class="['badge', deliveryTone(item.alert_status)]">{{ deliveryLabel(item.alert_status) }}</span></div>
              <div class="notify-item-msg">{{ plainMessage(item.alert_content || item.message || item.alert_type) }}</div>
              <div class="notify-item-time">{{ item.created_at || item.checked_at }}</div>
            </router-link>
          </div>
          <PaginationControls v-model:page="notificationPager.page" v-model:page-size="notificationPager.pageSize" :total="notificationPager.total" label="通知动态" class="notification-pagination" />
          <router-link to="/alerts/recent" class="notify-footer">查看全部告警记录</router-link>
        </section>
      </div>

      <!-- 用户下拉信息 -->
      <div class="user-profile-dropdown">
        <button ref="userButton" class="user-profile-btn" @click="showUserMenu = !showUserMenu; showNotificationPanel = false" aria-label="账号菜单" :aria-expanded="showUserMenu" aria-controls="account-menu">
          <span class="user-avatar">{{ (store.user?.user_id || 'A').slice(0, 1).toUpperCase() }}</span>
          <span class="user-name">{{ store.user?.user_id || store.user?.name || 'admin' }}</span>
          <IconSvg name="chevron" size="14" />
        </button>

        <div v-if="showUserMenu" id="account-menu" class="user-popover-menu">
          <router-link to="/admin?tab=password" class="popover-item" @click="showUserMenu = false"><IconSvg name="lock" size="14" /><span>修改个人密码</span></router-link>
          <router-link to="/admin" class="popover-item" @click="showUserMenu = false">
            <IconSvg name="users" size="14" />
            <span>用户安全管理</span>
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
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { store } from '../store'
import { api } from '../api'
import IconSvg from './IconSvg.vue'
import { plainMessage, deliveryLabel, deliveryTone } from '../utils/presentation'
import PaginationControls from './PaginationControls.vue'
import { usePagination } from '../composables/usePagination'

const router = useRouter()
const route = useRoute()
const showNotificationPanel = ref(false)
const showUserMenu = ref(false)
const notificationPager = usePagination(() => store.notifications.list)
const notificationButton = ref(null), userButton = ref(null)
function dismiss(event) {
  if (event.type === 'keydown') {
    if (event.key !== 'Escape') return
    if (showNotificationPanel.value) notificationButton.value?.focus()
    else if (showUserMenu.value) userButton.value?.focus()
  } else if (event.target.closest('.notify-wrapper, .user-profile-dropdown')) return
  showNotificationPanel.value = false; showUserMenu.value = false
}
watch(() => route.fullPath, () => { showNotificationPanel.value = false; showUserMenu.value = false })

const currentRouteTitle = computed(() => {
  const map = {
    '/dashboard': '监控总览',
    '/hosts': '服务器主机',
    '/databases': '数据库监控',
    '/services/new': '添加服务',
    '/data-send': '数据发送',
    '/alerts/settings': '告警规则配置',
    '/alerts/audit': '通知审计流水',
    '/alerts/recent': '最近告警记录',
    '/dolphinscheduler': 'DolphinScheduler 监控', '/alerts/tasks': '告警任务与重试', '/external-projects': '外部项目与消息', '/external-requests': '外部消息请求', '/account': '个人密码',
    '/admin': '用户安全管理'
  }
  if (route.query.from === 'databases') return route.path.endsWith('/edit') ? '编辑数据库监控' : route.path === '/services/new' ? '添加数据库监控' : '数据库详情'
  if (/^\/services\/[^/]+\/edit$/.test(route.path)) return '编辑监控服务'
  if (/^\/services\/[^/]+$/.test(route.path)) return '服务详情'
  if (/^\/hosts\/[^/]+$/.test(route.path)) return '主机详情'
  return map[route.path] || '监控总览'
})

const toggleNotifications = () => {
  showNotificationPanel.value = !showNotificationPanel.value
  showUserMenu.value = false
}

const markAllSeen = () => {
  store.markAllNotificationsSeen()
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
  document.addEventListener('pointerdown', dismiss)
  document.addEventListener('keydown', dismiss)
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
  document.removeEventListener('pointerdown', dismiss)
  document.removeEventListener('keydown', dismiss)
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
  background: #0284c7;
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
  display: block;
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
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  overflow-wrap: anywhere;
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
.navbar-left { display: flex; align-items: center; gap: 8px; min-width: 0; }
.mobile-menu-toggle { display: none; }
.notify-item-title { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.notify-item-title .badge { flex-shrink: 0; font-size: 10px; }
.notify-item:hover { background: #f8fafc; }
.notify-footer { display: block; text-align: center; padding: 12px; border-top: 1px solid var(--border-color); font-size: 12px; }
.notification-pagination { padding: 10px 12px; margin: 0; }
.notify-dropdown { max-height: calc(100dvh - 76px); overflow-y: auto; }
@media (max-width: 900px) {
  .mobile-menu-toggle { display: inline-flex; flex-shrink: 0; }
  .top-navbar { padding: 0 12px; }
  .navbar-right { gap: 8px; }
  .quick-action-btn { display: none; }
  .notify-dropdown { position: fixed; top: 62px; right: 12px; width: min(360px, calc(100vw - 24px)); }
  .bc-root, .bc-sep { display: none; }
  .bc-current { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .breadcrumb-trail { min-width: 0; }
  .user-name { display: none; }
  .user-profile-btn { padding: 4px; }
}
</style>
