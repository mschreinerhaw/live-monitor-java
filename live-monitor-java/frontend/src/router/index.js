import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
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

// 兼容直接通过无 hash 路径访问（如 /hosts、/data-send 等），自动映射到 hash 路由
router.beforeEach((to, from, next) => {
  if (to.matched.length === 0) {
    const rawPath = window.location.pathname.replace(/^\//, '')
    if (rawPath && rawPath !== 'index.html' && rawPath !== '/') {
      next({ path: '/' + rawPath, replace: true })
      return
    }
    next('/dashboard')
    return
  }
  next()
})

export default router
