import { reactive } from 'vue'
import { api } from '../api'
import { opsApi } from '../api/ops'

export const store = reactive({
  user: null,
  toast: {
    visible: false,
    message: '',
    type: 'info',
    timer: null
  },
  notifications: {
    list: [],
    unreadCount: 0,
    isOpen: false
  },

  showToast(message, type = 'info', duration = 3000) {
    if (this.toast.timer) clearTimeout(this.toast.timer)
    this.toast.message = message
    this.toast.type = type
    this.toast.visible = true
    this.toast.timer = setTimeout(() => {
      this.toast.visible = false
    }, duration)
  },

  async fetchCurrentUser() {
    try {
      const user = await api.currentUser()
      if (user && !user.embed) {
        try { user.ops_only = !!(await opsApi.capabilities()).ops_only } catch (_) {}
      }
      this.user = user
      return user
    } catch (_) {
      this.user = null
      return null
    }
  },

  async fetchNotifications() {
    try {
      const results = await Promise.allSettled([this.user?.ops_only ? Promise.resolve([]) : api.allAlerts(20), this.user?.embed ? Promise.resolve([]) : opsApi.list('alert')])
      const original = results[0].status === 'fulfilled' ? results[0].value || [] : []
      const operations = results[1].status === 'fulfilled' ? results[1].value || [] : []
      const merged = [...original, ...operations.map(a => ({ ...a, ops_event: true, service_name: a.app_id || a.host_id,
        message: `${a.code} · ${a.status}`, created_at: new Date(a.created_at).toISOString(), seen: !!a.acknowledged_by || a.status === 'RECOVERED' }))]
        .sort((a, b) => new Date(b.created_at || b.checked_at).getTime() - new Date(a.created_at || a.checked_at).getTime()).slice(0, 20)
      this.notifications.list = merged
      this.notifications.unreadCount = merged.filter(a => !a.seen).length
    } catch (_) {}
  }
})
