import { reactive } from 'vue'
import { api } from '../api'

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
      this.user = user
      return user
    } catch (_) {
      this.user = null
      return null
    }
  },

  async fetchNotifications() {
    try {
      const alerts = await api.allAlerts(20)
      this.notifications.list = alerts || []
      this.notifications.unreadCount = (alerts || []).filter(a => !a.seen).length
    } catch (_) {}
  }
})
