const API_BASE = ''

async function request(endpoint, options = {}) {
  const url = `${API_BASE}${endpoint}`
  const defaultHeaders = {
    'Accept': 'application/json',
    ...(options.body ? { 'Content-Type': 'application/json' } : {})
  }

  const config = {
    ...options,
    headers: {
      ...defaultHeaders,
      ...(options.headers || {})
    },
    credentials: 'same-origin'
  }

  try {
    const response = await fetch(url, config)
    if (response.status === 401 && !endpoint.includes('/api/auth/')) {
      if (window.location.hash !== '#/login') {
        window.location.hash = '#/login'
      }
      throw new Error('未授权，请先登录')
    }

    if (response.status === 204) {
      return null
    }

    const contentType = response.headers.get('content-type') || ''
    const isJson = contentType.includes('application/json')
    const data = isJson ? await response.json() : await response.text()

    if (!response.ok) {
      const errorMsg = (typeof data === 'object' && (data.message || data.detail || data.error)) || response.statusText || '请求失败'
      throw new Error(errorMsg)
    }

    return data
  } catch (err) {
    throw err
  }
}

export const api = {
  // Auth
  login: (credentials) => request('/api/auth/login', { method: 'POST', body: JSON.stringify(credentials) }),
  logout: () => request('/api/auth/logout', { method: 'POST' }),
  currentUser: () => request('/api/auth/current-user'),
  changePassword: (data) => request('/api/auth/change-password', { method: 'POST', body: JSON.stringify(data) }),

  // Dashboard & Services
  dashboard: () => request('/api/dashboard'),
  systemMetrics: () => request('/api/system-metrics/latest').catch(() => null),
  listServices: (includeDisabled = true) => request(`/api/services?include_disabled=${includeDisabled}`),
  getService: (id) => request(`/api/services/${id}`),
  createService: (data) => request('/api/services', { method: 'POST', body: JSON.stringify(data) }),
  updateService: (id, data) => request(`/api/services/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
  deleteService: (id) => request(`/api/services/${id}`, { method: 'DELETE' }),
  testService: (data) => request('/api/services/test', { method: 'POST', body: JSON.stringify(data) }),
  testExistingService: (id, data) => request(`/api/services/${id}/test`, { method: 'POST', body: JSON.stringify(data) }),
  checkAndStoreService: (id) => request(`/api/services/${id}/check`, { method: 'POST' }),

  // Database Assets & Data Send
  databaseConnections: (includeDisabled = true) => request(`/api/database/connections?include_disabled=${includeDisabled}`),
  databasePreview: (data) => request('/api/database/preview', { method: 'POST', body: JSON.stringify(data) }),
  dataSendPreview: (data) => request('/api/data-send/preview', { method: 'POST', body: JSON.stringify(data) }),
  dataSendExecute: (data) => request('/api/data-send/send', { method: 'POST', body: JSON.stringify(data) }),
  dataSendHistory: () => request('/api/data-send/history'),

  // Hosts
  listHosts: (includeDisabled = true) => request(`/api/hosts?include_disabled=${includeDisabled}`),
  getHost: (id) => request(`/api/hosts/${id}`),
  createHost: (data) => request('/api/hosts', { method: 'POST', body: JSON.stringify(data) }),
  updateHost: (id, data) => request(`/api/hosts/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
  deleteHost: (id) => request(`/api/hosts/${id}`, { method: 'DELETE' }),
  testHostSsh: (data) => request('/api/hosts/test-ssh', { method: 'POST', body: JSON.stringify(data) }),
  checkHost: (id) => request(`/api/hosts/${id}/check`, { method: 'POST' }),

  // Alerts
  listAlertChannels: (includeDisabled = true) => request(`/api/alert-channels?include_disabled=${includeDisabled}`),
  createAlertChannel: (data) => request('/api/alert-channels', { method: 'POST', body: JSON.stringify(data) }),
  updateAlertChannel: (id, data) => request(`/api/alert-channels/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
  deleteAlertChannel: (id) => request(`/api/alert-channels/${id}`, { method: 'DELETE' }),
  testAlertChannel: (id) => request(`/api/alert-channels/${id}/test`, { method: 'POST' }),

  listAlertGroups: (includeDisabled = true) => request(`/api/alert-groups?include_disabled=${includeDisabled}`),
  getAlertGroup: (id, includeSecrets = false) => request(`/api/alert-groups/${id}?include_secrets=${includeSecrets}`),
  createAlertGroup: (data) => request('/api/alert-groups', { method: 'POST', body: JSON.stringify(data) }),
  updateAlertGroup: (id, data) => request(`/api/alert-groups/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
  deleteAlertGroup: (id) => request(`/api/alert-groups/${id}`, { method: 'DELETE' }),

  allAlerts: (limit = 20) => request(`/api/alerts?limit=${limit}`),
  auditNotifyRecords: (params = {}) => {
    const query = new URLSearchParams(params).toString()
    return request(`/api/alert-notifies/audit?${query}`)
  },

  // Users (Admin)
  listUsers: () => request('/api/users'),
  createUser: (data) => request('/api/users', { method: 'POST', body: JSON.stringify(data) }),
  updateUserStatus: (id, data) => request(`/api/users/${id}/status`, { method: 'PUT', body: JSON.stringify(data) }),
  resetUserPassword: (id, data) => request(`/api/users/${id}/password`, { method: 'PUT', body: JSON.stringify(data) }),
}
