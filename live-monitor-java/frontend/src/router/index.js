import { createRouter, createWebHashHistory } from 'vue-router'
import { store } from '../store'

const routes = [
  {
    path: '/ops/:section',
    name: 'ContainerOps',
    component: () => import('../views/OpsPlatformView.vue')
  },
  {
    path: '/',
    redirect: '/dashboard'
  },
  {
    path: '/dashboard',
    name: 'Dashboard',
    component: () => import('../views/DashboardView.vue')
  },
  {
    path: '/hosts',
    name: 'Hosts',
    component: () => import('../views/HostView.vue')
  },
  {
    path: '/services/new',
    name: 'AddService',
    component: () => import('../views/ServiceFormView.vue')
  },
  {
    path: '/services/:id/edit',
    name: 'EditService',
    component: () => import('../views/ServiceFormView.vue')
  },
  {
    path: '/services/:id',
    name: 'ServiceDetail',
    component: () => import('../views/ServiceDetailView.vue')
  },
  {
    path: '/data-send',
    name: 'DataSend',
    component: () => import('../views/DataSendView.vue')
  },
  {
    path: '/alerts/settings',
    name: 'AlertSettings',
    component: () => import('../views/AlertSettingsView.vue')
  },
  {
    path: '/alerts/audit',
    name: 'AlertAudit',
    component: () => import('../views/AlertAuditView.vue')
  },
  {
    path: '/admin',
    name: 'UserAdmin',
    component: () => import('../views/UserAdminView.vue')
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/LoginView.vue')
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

// 登录与未授权路由守卫
router.beforeEach(async (to, from, next) => {
  // 兼容直接从非 hash 地址进入（如 /data-send、/hosts 等）
  if (to.matched.length === 0) {
    const rawPath = window.location.pathname.replace(/^\//, '')
    if (rawPath && rawPath !== 'index.html' && rawPath !== '/') {
      next({ path: '/' + rawPath, replace: true })
      return
    }
    next('/dashboard')
    return
  }

  // 访问登录页时放行
  if (to.path === '/login') {
    next()
    return
  }

  // 其他受保护页面检查是否登录
  if (!store.user) {
    const user = await store.fetchCurrentUser()
    if (!user) {
      next('/login')
      return
    }
  }

  if (store.user?.ops_only && !to.path.startsWith('/ops/')) {
    next('/ops/overview')
    return
  }
  next()
})

export default router
