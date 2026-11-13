const API_BASE = ''
const dataSendAliases = value => {
  const result = { ...value }
  for (const key of ['database_connection_service_id','database_service_name','all_columns','selected_columns','column_aliases','sms_content','row_count','column_count','char_count','sms_count','recipient_summary','created_at']) {
    if (value[key] !== undefined) result[key.replace(/_([a-z])/g, (_, letter) => letter.toUpperCase())] = value[key]
  }
  return result
}
const query = (params = {}) => {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') search.set(key === 'pageSize' ? 'page_size' : key, value)
  }
  return search.toString() ? `?${search}` : ''
}

async function request(endpoint, options = {}) {
  if (options.body && typeof options.body === 'string') {
    const value = JSON.parse(options.body)
    const normalized = Object.fromEntries(Object.entries(value).map(([key, item]) => [key.replace(/[A-Z]/g, letter => `_${letter.toLowerCase()}`), item]))
    for (const key of Object.keys(normalized)) {
      if (normalized[key] === '' && /password|secret|token|private_key/.test(key)) delete normalized[key]
    }
    options = { ...options, body: JSON.stringify(normalized) }
  }
  const url = `${API_BASE}${endpoint}`
  const defaultHeaders = {
    'Accept': 'application/json',
    ...(options.body ? { 'Content-Type': 'application/json' } : {})
  }
  const embedToken = new URLSearchParams(window.location.search).get('token')
  if (embedToken) defaultHeaders['X-Embed-Token'] = embedToken

  const config = {
    ...options,
    headers: {
      ...defaultHeaders,
      ...(options.headers || {})
    },
    credentials: 'same-origin'
  }

  const response = await fetch(url, config)
  if (response.status === 401 && !endpoint.includes('/api/auth/login')) {
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
  if ((endpoint.includes('test') || endpoint.includes('/api/data-send/')) && data && typeof data === 'object' && data.success === false) {
    throw new Error(data.error || data.message || '测试发送失败，请检查渠道结果')
  }

  return data
}

export const api = {
  dolphinConnections: () => request('/api/dolphinscheduler/connections'),
  saveDolphinConnection: (id,data) => request('/api/dolphinscheduler/connections' + (id ? '/' + id : ''), { method: id ? 'PUT' : 'POST', body: JSON.stringify(data) }),
  dolphinProjects: id => request(`/api/dolphinscheduler/${id}/projects`),
  dolphinOverview: (id,params) => request(`/api/dolphinscheduler/${id}/overview${query(params)}`),
  dolphinTasks: (id,project,instance) => request(`/api/dolphinscheduler/${id}/projects/${project}/instances/${instance}/tasks`),
  // Auth
  login: (credentials) => request('/api/auth/login', { method: 'POST', body: JSON.stringify(credentials) }),
  logout: () => request('/api/auth/logout', { method: 'POST' }),
  currentUser: () => request('/api/auth/me'),
  changePassword: (data) => request('/api/admin/password', { method: 'PUT', body: JSON.stringify(data) }),

  // Dashboard & Services
  dashboard: () => request('/api/dashboard'),
  systemMetrics: () => request('/api/system-metrics').catch(() => null),
  listServices: (includeDisabled = true) => request(`/api/services?include_disabled=${includeDisabled}`),
  getService: (id) => request(`/api/services/${id}`),
  createService: (data) => request('/api/services', { method: 'POST', body: JSON.stringify(data) }),
  updateService: (id, data) => request(`/api/services/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
  deleteService: (id) => request(`/api/services/${id}`, { method: 'DELETE' }),
  testService: (data) => request('/api/services/test', { method: 'POST', body: JSON.stringify(data) }),
  testExistingService: (id, data) => request(`/api/services/${id}/test`, { method: 'POST', body: JSON.stringify(data) }),
  checkAndStoreService: (id) => request(`/api/services/${id}/check`, { method: 'POST' }),
  testRule: (data) => request('/api/rules/test', { method: 'POST', body: JSON.stringify(data) }),
  serviceResults: (id, limit = 50) => request(`/api/services/${id}/results?limit=${limit}`),
  serviceAlerts: (id, limit = 50) => request(`/api/services/${id}/alerts?limit=${limit}`),
  updateServiceAlertSettings: (id, data) => request(`/api/services/${id}/alert-settings`, { method: 'PUT', body: JSON.stringify(data) }),

  // Database Assets & Data Send
  databaseConnections: (includeDisabled = true) => request(`/api/database/connections?include_disabled=${includeDisabled}`),
  databasePreview: (data) => request('/api/database/preview', { method: 'POST', body: JSON.stringify(data) }),
  existingServiceDatabasePreview: (id, data) => request(`/api/services/${id}/database/preview`, { method: 'POST', body: JSON.stringify(data) }),
  dataSendPreview: async (data) => dataSendAliases(await request('/api/data-send/preview', { method: 'POST', body: JSON.stringify(data) })),
  dataSendExecute: async (data) => dataSendAliases(await request('/api/data-send/send', { method: 'POST', body: JSON.stringify(data) })),
  dataSendHistory: async () => (await request('/api/data-send/history')).map(dataSendAliases),

  // Hosts
  listHosts: (includeDisabled = true) => request(`/api/hosts?include_disabled=${includeDisabled}`),
  getHost: (id) => request(`/api/hosts/${id}`),
  createHost: (data) => request('/api/hosts', { method: 'POST', body: JSON.stringify(data) }),
  updateHost: (id, data) => request(`/api/hosts/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
  deleteHost: (id) => request(`/api/hosts/${id}`, { method: 'DELETE' }),
  testHostSsh: (data, id = null) => request(`/api/hosts/test-ssh${query({ host_id: id })}`, { method: 'POST', body: JSON.stringify(data) }),
  checkHost: (id) => request(`/api/hosts/${id}/metrics/refresh`, { method: 'POST' }),
  hostMetrics: (id) => request(`/api/hosts/${id}/metrics`),
  hostMetricHistory: (id, days = 7) => request(`/api/hosts/${id}/metrics/history?days=${days}`),
  hostProcesses: (id) => request(`/api/hosts/${id}/processes`),
  hostProcessStatus: (id) => request(`/api/hosts/${id}/process-status`),
  createHostProcess: (id, data) => request(`/api/hosts/${id}/processes`, { method: 'POST', body: JSON.stringify(data) }),
  updateHostProcess: (id, data) => request(`/api/host-processes/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
  deleteHostProcess: (id) => request(`/api/host-processes/${id}`, { method: 'DELETE' }),

  // Alerts & Channels & Groups
  listAlertChannels: async (includeDisabled = true) => (await request(`/api/alert-channels?include_disabled=${includeDisabled}`)).map(channel => ({ ...channel, channelName: channel.channel_name, channelType: channel.channel_type })),
  createAlertChannel: (data) => request('/api/alert-channels', { method: 'POST', body: JSON.stringify(data) }),
  updateAlertChannel: (id, data) => request(`/api/alert-channels/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
  deleteAlertChannel: (id) => request(`/api/alert-channels/${id}`, { method: 'DELETE' }),
  testAlertChannel: (id) => request(`/api/alert-channels/${id}/test`, { method: 'POST' }),

  listAlertGroups: (includeDisabled = true) => request(`/api/alert-groups?include_disabled=${includeDisabled}`),
  getAlertGroup: (id, includeSecrets = false) => request(`/api/alert-groups/${id}?include_secrets=${includeSecrets}`),
  createAlertGroup: (data) => request('/api/alert-groups', { method: 'POST', body: JSON.stringify(data) }),
  updateAlertGroup: (id, data) => request(`/api/alert-groups/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
  deleteAlertGroup: (id) => request(`/api/alert-groups/${id}`, { method: 'DELETE' }),
  // Group tests use the real channel test endpoint and retain each channel result.
  testAlertGroup: async (id) => {
    const group = await api.getAlertGroup(id)
    const results = await Promise.all((group.channels || []).filter(c => c.enabled).map(async c => {
      try { return { channel_id: c.id, ...await api.testAlertChannel(c.id) } }
      catch (e) { return { channel_id: c.id, success: false, message: e.message } }
    }))
    return { success: results.length > 0 && results.every(r => r.success), results }
  },
  alertTasks: (params = {}) => request(`/api/alert-tasks${query(params)}`),
  alertTaskAttempts: (id) => request(`/api/alert-tasks/${id}/attempts`),
  retryAlertTask: (id) => request(`/api/alert-tasks/${id}/retry`, { method: 'POST' }),
  externalProjects: () => request('/api/external-projects?include_disabled=true'),
  createExternalProject: (data) => request('/api/external-projects', { method: 'POST', body: JSON.stringify(data) }),
  updateExternalProject: (id, data) => request(`/api/external-projects/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
  deleteExternalProject: (id) => request(`/api/external-projects/${id}`, { method: 'DELETE' }),
  externalAudit: (params = {}) => request(`/api/external/messages/audit${query(params)}`),
  externalMessageStatus: (enabled) => request('/api/external/messages/status', { method: 'PUT', body: JSON.stringify({ enabled }) }),

  listAlertPolicies: (includeDisabled = true) => request(`/api/alert-policies?include_disabled=${includeDisabled}`),
  allAlerts: (limit = 20) => request(`/api/alerts?limit=${limit}`),
  alertNotifications: (params = {}) => {
    const search = new URLSearchParams()
    if (params.page) search.set('page', params.page)
    if (params.pageSize || params.page_size) search.set('page_size', params.pageSize || params.page_size)
    if (params.start_time) search.set('start_time', params.start_time)
    if (params.end_time) search.set('end_time', params.end_time)
    if (params.status) search.set('status', params.status)
    if (params.query) search.set('query', params.query)
    const suffix = search.toString()
    return request(`/api/alert-notifications${suffix ? `?${suffix}` : ''}`)
  },
  alertNotificationsTrend: (days = 14) => request(`/api/alert-notifications/trend?days=${days}`),

  // Service Alert Bindings
  updateServiceAlertGroups: (serviceId, groupIds) => request(`/api/services/${serviceId}/alert-group`, {
    method: 'PUT',
    body: JSON.stringify({ alert_group_ids: groupIds })
  }),
  testServiceAlert: (serviceId) => request(`/api/services/${serviceId}/alert-test`, { method: 'POST' }),

  // Users (Admin)
  listUsers: () => request('/api/admin/users'),
  deleteUser: (userId) => request(`/api/admin/users/${encodeURIComponent(userId)}`, { method: 'DELETE' }),
  createUser: (data) => request('/api/admin/users', { method: 'POST', body: JSON.stringify(data) }),
  updateUserStatus: (userId, data) => request(`/api/admin/users/${encodeURIComponent(userId)}/status`, {
    method: 'PUT',
    body: JSON.stringify(data)
  }),
  resetUserPassword: (userId, data) => request(`/api/admin/users/${encodeURIComponent(userId)}/password`, {
    method: 'PUT',
    body: JSON.stringify(data)
  }),
  listLoginAuditLogs: (params = {}) => {
    const search = new URLSearchParams()
    if (params.page) search.set('page', params.page)
    if (params.pageSize || params.page_size) search.set('page_size', params.pageSize || params.page_size)
    if (params.query) search.set('query', params.query)
    const suffix = search.toString()
    return request(`/api/admin/audit-logs${suffix ? `?${suffix}` : ''}`)
  }
}
