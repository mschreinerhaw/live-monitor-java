import { reactive } from 'vue'
import { api } from '../api'

export const store = reactive({
  user: null,
  navigationOpen: false,
  navigationLoading: false,
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
      this.user = user && user.authenticated !== false ? user : null
      return this.user
    } catch (_) {
      this.user = null
      return null
    }
  },

  async fetchNotifications() {
    try {
      const alerts = await api.allAlerts(20)
      this.notifications.list = alerts || []
      let seen = []
      try { seen = JSON.parse(localStorage.getItem('lm_seen_notifications:' + this.user?.user_id) || '[]') } catch (_) {}
      const keys = new Set(Array.isArray(seen) ? seen : [])
      this.notifications.unreadCount = (alerts || []).filter(a => !keys.has(String(a.task_id || a.id))).length
    } catch (_) {}
  },

  markAllNotificationsSeen() {
    const storageKey = 'lm_seen_notifications:' + this.user?.user_id
    let previous = []
    try { previous = JSON.parse(localStorage.getItem(storageKey) || '[]') } catch (_) {}
    const ids = [...new Set([...(Array.isArray(previous) ? previous : []), ...this.notifications.list.map(item => String(item.task_id || item.id))])].slice(-1000)
    try { localStorage.setItem(storageKey, JSON.stringify(ids)) } catch (_) {}
    this.notifications.unreadCount = 0
  }
})
