(function () {
  const storedBase = window.localStorage.getItem("liveMonitorApiBase");
  const API_BASE = storedBase || "";

  async function request(path, options = {}) {
    const response = await fetch(`${API_BASE}${path}`, {
      credentials: "same-origin",
      headers: {
        "Content-Type": "application/json",
        ...(options.headers || {}),
      },
      ...options,
    });

    if (response.status === 204) {
      return null;
    }

    const contentType = response.headers.get("content-type") || "";
    const payload = contentType.includes("application/json")
      ? await response.json()
      : await response.text();

    if (!response.ok) {
      if (response.status === 401 && !window.location.pathname.endsWith("/login.html")) {
        window.location.href = "/login.html";
        return null;
      }
      const message = payload && (payload.detail || payload.message || payload.error)
        ? (payload.detail || payload.message || payload.error)
        : `HTTP ${response.status}`;
      throw new Error(message);
    }
    return payload;
  }

  window.LiveMonitorApi = {
    dashboard: () => request("/api/dashboard"),
    services: (includeDisabled = false) => request(`/api/services?include_disabled=${includeDisabled}`),
    service: (id) => request(`/api/services/${id}`),
    createService: (data) => request("/api/services", { method: "POST", body: JSON.stringify(data) }),
    testService: (data) => request("/api/services/test", { method: "POST", body: JSON.stringify(data) }),
    updateService: (id, data) => request(`/api/services/${id}`, { method: "PUT", body: JSON.stringify(data) }),
    deleteService: (id) => request(`/api/services/${id}`, { method: "DELETE" }),
    alertPolicies: (includeDisabled = true) => request(`/api/alert-policies?include_disabled=${includeDisabled}`),
    alertChannels: (includeDisabled = true) => request(`/api/alert-channels?include_disabled=${includeDisabled}`),
    createAlertChannel: (data) => request("/api/alert-channels", { method: "POST", body: JSON.stringify(data) }),
    updateAlertChannel: (id, data) => request(`/api/alert-channels/${id}`, { method: "PUT", body: JSON.stringify(data) }),
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
    currentUser: () => request("/api/auth/me"),
    logout: () => request("/api/auth/logout", { method: "POST" }),
    systemMetrics: () => request("/api/system-metrics"),
    hosts: (includeDisabled = false) => request(`/api/hosts?include_disabled=${includeDisabled}`),
    host: (id) => request(`/api/hosts/${id}`),
    createHost: (data) => request("/api/hosts", { method: "POST", body: JSON.stringify(data) }),
    updateHost: (id, data) => request(`/api/hosts/${id}`, { method: "PUT", body: JSON.stringify(data) }),
    deleteHost: (id) => request(`/api/hosts/${id}`, { method: "DELETE" }),
    hostMetrics: (id) => request(`/api/hosts/${id}/metrics`),
    hostProcesses: (id) => request(`/api/hosts/${id}/processes`),
    createHostProcess: (id, data) => request(`/api/hosts/${id}/processes`, { method: "POST", body: JSON.stringify(data) }),
    updateHostProcess: (id, data) => request(`/api/host-processes/${id}`, { method: "PUT", body: JSON.stringify(data) }),
    deleteHostProcess: (id) => request(`/api/host-processes/${id}`, { method: "DELETE" }),
    hostProcessStatus: (id) => request(`/api/hosts/${id}/process-status`),
  };
})();
