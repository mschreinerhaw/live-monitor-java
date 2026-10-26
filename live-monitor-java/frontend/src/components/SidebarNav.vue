<template>
  <aside class="sidebar-nav" :class="{ collapsed: isCollapsed }">
    <div class="sidebar-header">
      <router-link to="/dashboard" class="sidebar-brand">
        <span class="brand-logo">
          <IconSvg name="activity" size="20" />
        </span>
        <div v-show="!isCollapsed" class="brand-text">
          <strong class="brand-name">Live Monitor</strong>
          <span class="brand-tag">事件监控</span>
        </div>
      </router-link>
    </div>

    <!-- 导航菜单组 -->
    <div class="sidebar-menu-wrapper">
      <div v-for="group in menuGroups" :key="group.title" class="menu-group">
        <div v-show="!isCollapsed" class="group-title">{{ group.title }}</div>
        <router-link
          v-for="item in group.items"
          :key="item.path"
          :to="item.path"
          class="menu-item"
          :class="{ active: isRouteActive(item) }"
          :title="isCollapsed ? item.label : ''"
        >
          <span class="menu-icon">
            <IconSvg :name="item.icon" size="18" />
          </span>
          <span v-show="!isCollapsed" class="menu-label">{{ item.label }}</span>
          <span
            v-if="!isCollapsed && item.badge && item.badge() > 0"
            class="menu-badge"
          >
            {{ item.badge() }}
          </span>
        </router-link>
      </div>
    </div>

    <!-- 底部收起与环境标识 -->
    <div class="sidebar-footer">
      <button
        type="button"
        class="collapse-toggle-btn"
        @click="toggleSidebar"
        :title="isCollapsed ? '展开菜单' : '收起菜单'"
      >
        <IconSvg :name="isCollapsed ? 'chevron-right' : 'chevron-left'" size="16" />
        <span v-show="!isCollapsed" style="margin-left: 8px;">收起导航</span>
      </button>
    </div>
  </aside>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import { store } from '../store'
import IconSvg from './IconSvg.vue'

const route = useRoute()
const isCollapsed = ref(localStorage.getItem('lm_sidebar_collapsed') === 'true')

const toggleSidebar = () => {
  isCollapsed.value = !isCollapsed.value
  localStorage.setItem('lm_sidebar_collapsed', isCollapsed.value)
}

const isRouteActive = (item) => {
  if (item.path === '/dashboard') {
    return route.path === '/dashboard' || route.path === '/'
  }
  if (item.path === '/services/new') {
    return route.path.startsWith('/services')
  }
  if (item.path === '/alerts/settings') {
    return route.path.startsWith('/alerts/settings')
  }
  return route.path.startsWith(item.path)
}

const menuGroups = [
  {
    title: '监控中心',
    items: [
      { label: '监控总览', path: '/dashboard', icon: 'activity' },
      { label: '服务接入', path: '/services/new', icon: 'plus' },
      { label: '数据发送', path: '/data-send', icon: 'send' }
    ]
  },
  {
    title: '资产基础设施',
    items: [
      { label: '主机维护', path: '/hosts', icon: 'server' }
    ]
  },
  {
    title: '告警与安全',
    items: [
      { label: '告警规则配置', path: '/alerts/settings', icon: 'settings' },
      {
        label: '通知审计流水',
        path: '/alerts/audit',
        icon: 'bell',
        badge: () => store.notifications.unreadCount
      },
      { label: '用户安全管理', path: '/admin', icon: 'users' }
    ]
  }
]
</script>

<style scoped>
.sidebar-nav {
  width: 240px;
  background: #0f172a;
  color: #f8fafc;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  transition: width 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  border-right: 1px solid #1e293b;
  position: sticky;
  top: 0;
  height: 100vh;
  z-index: 101;
  overflow: hidden;
}

.sidebar-nav.collapsed {
  width: 68px;
}

.sidebar-header {
  height: 56px;
  display: flex;
  align-items: center;
  padding: 0 18px;
  border-bottom: 1px solid #1e293b;
}

.sidebar-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #ffffff;
  text-decoration: none;
  overflow: hidden;
  white-space: nowrap;
}

.brand-logo {
  width: 32px;
  height: 32px;
  background: linear-gradient(135deg, #0284c7 0%, #0369a1 100%);
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #ffffff;
  flex-shrink: 0;
  box-shadow: 0 2px 4px rgba(2, 132, 199, 0.3);
}

.brand-text {
  display: flex;
  flex-direction: column;
}

.brand-name {
  font-size: 15px;
  font-weight: 700;
  letter-spacing: -0.3px;
  color: #f8fafc;
  line-height: 1.2;
}

.brand-tag {
  font-size: 10.5px;
  color: #94a3b8;
  letter-spacing: 0.2px;
}

.sidebar-menu-wrapper {
  flex: 1;
  overflow-y: auto;
  padding: 14px 10px;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.menu-group {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.group-title {
  font-size: 11px;
  font-weight: 600;
  color: #64748b;
  padding: 4px 12px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.menu-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 9px 12px;
  border-radius: 8px;
  color: #94a3b8;
  text-decoration: none;
  font-size: 13.5px;
  font-weight: 500;
  transition: all 0.15s ease;
  white-space: nowrap;
}

.menu-item:hover {
  background: #1e293b;
  color: #f8fafc;
}

.menu-item.active {
  background: #0284c7;
  color: #ffffff;
  font-weight: 600;
  box-shadow: 0 2px 6px rgba(2, 132, 199, 0.35);
}

.menu-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.menu-label {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
}

.menu-badge {
  background: #ef4444;
  color: #ffffff;
  font-size: 10px;
  font-weight: 700;
  padding: 1px 6px;
  border-radius: 999px;
}

.sidebar-footer {
  padding: 12px 10px;
  border-top: 1px solid #1e293b;
}

.collapse-toggle-btn {
  width: 100%;
  background: transparent;
  border: none;
  color: #94a3b8;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 8px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  transition: all 0.15s;
}

.collapse-toggle-btn:hover {
  background: #1e293b;
  color: #f8fafc;
}
</style>
