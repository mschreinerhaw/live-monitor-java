(function () {
  const storedBase = window.localStorage.getItem("liveMonitorApiBase");
  const API_BASE = storedBase || "";
  const LOGIN_PATH = "/login.html";
  const EMBED_TOKEN = getAuthUrlToken();
  bindAuthUrlNavigation();

  async function request(path, options = {}) {
    const {
      headers = {},
      redirectOnUnauthorized = !EMBED_TOKEN,
      skipEmbedToken = false,
      ...fetchOptions
    } = options;
    const requestHeaders = { ...headers };
    if (fetchOptions.body !== undefined && !(fetchOptions.body instanceof FormData)) {
      requestHeaders["Content-Type"] = requestHeaders["Content-Type"] || "application/json";
    }
    if (EMBED_TOKEN && !skipEmbedToken) {
      requestHeaders["X-Embed-Token"] = requestHeaders["X-Embed-Token"] || EMBED_TOKEN;
    }

    const response = await fetch(`${API_BASE}${withEmbedToken(path, skipEmbedToken)}`, {
      credentials: API_BASE ? "include" : "same-origin",
      headers: requestHeaders,
      ...fetchOptions,
    });

    if (response.status === 204) {
      return null;
    }

    const contentType = response.headers.get("content-type") || "";
    const payload = contentType.includes("application/json")
      ? await response.json()
      : await response.text();

    if (!response.ok) {
      if (response.status === 401 && redirectOnUnauthorized) {
        redirectToLogin();
        return null;
      }
      const message = payload && (payload.detail || payload.message || payload.error)
        ? (payload.detail || payload.message || payload.error)
        : `HTTP ${response.status}`;
      throw new Error(message);
    }
    return payload;
  }

  function redirectToLogin() {
    if (window.location.pathname.endsWith(LOGIN_PATH)) return;
    const current = `${window.location.pathname}${window.location.search}${window.location.hash}`;
    const target = `${LOGIN_PATH}?redirect=${encodeURIComponent(current)}`;
    window.location.href = target;
  }

  function getAuthUrlToken() {
    const params = new URLSearchParams(window.location.search);
    const token = params.get("token") || "";
    if (token) {
      window.sessionStorage.setItem("liveMonitorEmbedToken", token);
      return token;
    }
    if (window.location.pathname.endsWith(LOGIN_PATH)) {
      window.sessionStorage.removeItem("liveMonitorEmbedToken");
      return "";
    }
    return window.sessionStorage.getItem("liveMonitorEmbedToken") || "";
  }

  function withEmbedToken(path, skipEmbedToken = false) {
    if (skipEmbedToken || !EMBED_TOKEN || !path.startsWith("/api/")) return path;
    const separator = path.includes("?") ? "&" : "?";
    return `${path}${separator}token=${encodeURIComponent(EMBED_TOKEN)}`;
  }

  function bindAuthUrlNavigation() {
    if (!EMBED_TOKEN) return;
    document.addEventListener("click", (event) => {
      const link = event.target.closest("a[href]");
      if (!link || link.target || link.hasAttribute("download")) return;
      const url = new URL(link.getAttribute("href"), window.location.origin);
      if (url.origin !== window.location.origin || url.pathname.startsWith("/api/") || url.pathname === LOGIN_PATH) return;
      url.searchParams.set("token", EMBED_TOKEN);
      link.href = `${url.pathname}${url.search}${url.hash}`;
    }, true);
  }

  window.LiveMonitorApi = {
    hasEmbedToken: () => Boolean(EMBED_TOKEN),
    login: (username, password) => request("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, password }),
      redirectOnUnauthorized: false,
      skipEmbedToken: true,
    }),
    dashboard: () => request("/api/dashboard"),
    services: (includeDisabled = false) => request(`/api/services?include_disabled=${includeDisabled}`),
    service: (id) => request(`/api/services/${id}`),
    databaseConnections: (includeDisabled = true) => request(`/api/database/connections?include_disabled=${includeDisabled}`),
    createService: (data) => request("/api/services", { method: "POST", body: JSON.stringify(data) }),
    testService: (data) => request("/api/services/test", { method: "POST", body: JSON.stringify(data) }),
    testExistingService: (id, data) => request(`/api/services/${id}/test`, { method: "POST", body: JSON.stringify(data) }),
    testRule: (data) => request("/api/rules/test", { method: "POST", body: JSON.stringify(data) }),
    databasePreview: (data) => request("/api/database/preview", { method: "POST", body: JSON.stringify(data) }),
    existingServiceDatabasePreview: (id, data) => request(`/api/services/${id}/database/preview`, { method: "POST", body: JSON.stringify(data) }),
    updateService: (id, data) => request(`/api/services/${id}`, { method: "PUT", body: JSON.stringify(data) }),
    deleteService: (id) => request(`/api/services/${id}`, { method: "DELETE" }),
    alertPolicies: (includeDisabled = true) => request(`/api/alert-policies?include_disabled=${includeDisabled}`),
    alertChannels: (includeDisabled = true) => request(`/api/alert-channels?include_disabled=${includeDisabled}`),
    createAlertChannel: (data) => request("/api/alert-channels", { method: "POST", body: JSON.stringify(data) }),
    updateAlertChannel: (id, data) => request(`/api/alert-channels/${id}`, { method: "PUT", body: JSON.stringify(data) }),
    testAlertChannel: (id) => request(`/api/alert-channels/${id}/test`, { method: "POST" }),
    deleteAlertChannel: (id) => request(`/api/alert-channels/${id}`, { method: "DELETE" }),
    alertGroups: (includeDisabled = true) => request(`/api/alert-groups?include_disabled=${includeDisabled}`),
    alertGroup: (id, includeSecrets = false) => request(`/api/alert-groups/${id}?include_secrets=${includeSecrets}`),
    createAlertGroup: (data) => request("/api/alert-groups", { method: "POST", body: JSON.stringify(data) }),
    updateAlertGroup: (id, data) => request(`/api/alert-groups/${id}`, { method: "PUT", body: JSON.stringify(data) }),
    deleteAlertGroup: (id) => request(`/api/alert-groups/${id}`, { method: "DELETE" }),
    alertConfigs: (includeDisabled = true) => request(`/api/alert-configs?include_disabled=${includeDisabled}`),
    createAlertConfig: (data) => request("/api/alert-configs", { method: "POST", body: JSON.stringify(data) }),
    updateAlertConfig: (id, data) => request(`/api/alert-configs/${id}`, { method: "PUT", body: JSON.stringify(data) }),
    deleteAlertConfig: (id) => request(`/api/alert-configs/${id}`, { method: "DELETE" }),
    updateServiceAlertConfig: (id, data) => request(`/api/services/${id}/alert-config`, { method: "PUT", body: JSON.stringify(data) }),
    updateServiceAlertGroup: (id, data) => request(`/api/services/${id}/alert-group`, { method: "PUT", body: JSON.stringify(data) }),
    updateAlertSettings: (id, data) => request(`/api/services/${id}/alert-settings`, { method: "PUT", body: JSON.stringify(data) }),
    alertTest: (id) => request(`/api/services/${id}/alert-test`, { method: "POST" }),
    checkService: (id) => request(`/api/services/${id}/check`, { method: "POST" }),
    results: (id, limit = 100) => request(`/api/services/${id}/results?limit=${limit}`),
    alerts: (id, limit = 50) => request(`/api/services/${id}/alerts?limit=${limit}`),
    allAlerts: (limit = 50) => request(`/api/alerts?limit=${limit}`),
    clearAlerts: () => request("/api/alerts", { method: "DELETE" }),
    currentUser: () => request("/api/auth/me", { redirectOnUnauthorized: false }),
    logout: () => request("/api/auth/logout", { method: "POST", redirectOnUnauthorized: false }),
    createEmbedToken: (data = {}) => request("/api/embed-token", { method: "POST", body: JSON.stringify(data) }),
    users: () => request("/api/admin/users"),
    auditLogs: () => request("/api/admin/audit-logs"),
    createUser: (data) => request("/api/admin/users", { method: "POST", body: JSON.stringify(data) }),
    changePassword: (data) => request("/api/admin/password", { method: "PUT", body: JSON.stringify(data) }),
    resetUserPassword: (userId, data) => request(`/api/admin/users/${encodeURIComponent(userId)}/password`, { method: "PUT", body: JSON.stringify(data) }),
    updateUserStatus: (userId, data) => request(`/api/admin/users/${encodeURIComponent(userId)}/status`, { method: "PUT", body: JSON.stringify(data) }),
    deleteUser: (userId) => request(`/api/admin/users/${encodeURIComponent(userId)}`, { method: "DELETE" }),
    systemMetrics: () => request("/api/system-metrics"),
    hosts: (includeDisabled = false) => request(`/api/hosts?include_disabled=${includeDisabled}`),
    hostSummary: () => request("/api/hosts/summary"),
    host: (id) => request(`/api/hosts/${id}`),
    createHost: (data) => request("/api/hosts", { method: "POST", body: JSON.stringify(data) }),
    updateHost: (id, data) => request(`/api/hosts/${id}`, { method: "PUT", body: JSON.stringify(data) }),
    deleteHost: (id) => request(`/api/hosts/${id}`, { method: "DELETE" }),
    hostMetrics: (id) => request(`/api/hosts/${id}/metrics`),
    hostMetricHistory: (id, days = 7, limit = 10000) => request(`/api/hosts/${id}/metrics/history?days=${days}&limit=${limit}`),
    refreshHostMetrics: (id) => request(`/api/hosts/${id}/metrics/refresh`, { method: "POST" }),
    refreshAllHostMetrics: () => request("/api/hosts/metrics/refresh", { method: "POST" }),
    hostProcesses: (id) => request(`/api/hosts/${id}/processes`),
    createHostProcess: (id, data) => request(`/api/hosts/${id}/processes`, { method: "POST", body: JSON.stringify(data) }),
    updateHostProcess: (id, data) => request(`/api/host-processes/${id}`, { method: "PUT", body: JSON.stringify(data) }),
    deleteHostProcess: (id) => request(`/api/host-processes/${id}`, { method: "DELETE" }),
    hostProcessStatus: (id) => request(`/api/hosts/${id}/process-status`),
  };
})();
