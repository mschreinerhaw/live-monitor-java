import { createRouter, createWebHashHistory } from 'vue-router'
import { store } from '../store'

const routes = [
  { path: '/external-requests', component: () => import('../views/ExternalRequestsView.vue') },
  { path: '/databases', component: () => import('../views/DatabaseView.vue') },
  { path: '/alerts/recent', component: () => import('../views/RecentAlertsView.vue') },
  { path: '/alerts/tasks', component: () => import('../views/AlertTasksView.vue') },
  { path: '/external-projects', component: () => import('../views/ExternalProjectsView.vue') },
  { path: '/hosts/:id', component: () => import('../views/HostDetailView.vue') },
  { path: '/account', component: () => import('../views/AccountView.vue') },
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

// Normalize server-forwarded deep links before the hash history captures its base.
if (!window.location.hash && !['/', '/index.html'].includes(window.location.pathname)) {
  window.history.replaceState(window.history.state, '', '/' + window.location.search + '#' + window.location.pathname)
}
const router = createRouter({
  history: createWebHashHistory('/'),
  routes
})

// 登录与未授权路由守卫
router.beforeEach(async (to, from, next) => {
  store.navigationLoading = true
  // 兼容直接从非 hash 地址进入（如 /data-send、/hosts 等）
  if (to.matched.length === 0) {
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

  if (to.path === '/admin' && !store.user?.admin) { next('/account'); return }
  next()
})

router.afterEach(() => { store.navigationLoading = false })
router.onError(() => { store.navigationLoading = false; store.showToast('页面加载失败，请刷新重试', 'error') })

export default router
