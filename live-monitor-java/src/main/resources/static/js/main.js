const page = document.body.dataset.page;
let dashboardState = { services: [], recentResults: [], filter: "all", query: "" };
let alertSettingsState = {
  services: [],
  groups: [],
  policies: [],
  channels: [],
  selectedGroupId: null,
  selectedChannelId: null,
};

document.addEventListener("DOMContentLoaded", () => {
  if (window.lucide) {
    window.lucide.createIcons();
  }

  if (page === "dashboard") initDashboard();
  if (page === "add-service") initAddService();
  if (page === "service-detail") initServiceDetail();
  if (page === "alert-settings") initAlertSettings();
});

function statusLabel(status) {
  return status || "UNKNOWN";
}

function serviceTypeLabel(type) {
  return {
    web: "Web 应用",
    redis: "Redis",
    zookeeper: "ZooKeeper",
  }[type] || type;
}

function formatTime(value) {
  if (!value) return "-";
  const normalized = value.includes("T") ? value : `${value.replace(" ", "T")}Z`;
  const date = new Date(normalized);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("zh-CN", { hour12: false });
}

function endpointText(service) {
  if (service.service_type === "web") return service.url || "-";
  return `${service.host || "-"}:${service.port || "-"}`;
}

function serviceDetailHref(id) {
  return `/services/${id}`;
}

function serviceEditHref(id) {
  return `/services/${id}/edit`;
}

function isHttpService(service) {
  return service.service_type === "web" && /^https?:\/\//i.test(service.url || "");
}

function serviceOpenButton(service) {
  if (!isHttpService(service)) return "";
  return `
    <a title="打开服务" href="${escapeHtml(service.url)}" target="_blank" rel="noopener">
      <i data-lucide="external-link"></i>
    </a>
  `;
}

function endpointHtml(service) {
  const text = escapeHtml(endpointText(service));
  if (!isHttpService(service)) return text;
  return `<a class="endpoint-link" href="${escapeHtml(service.url)}" target="_blank" rel="noopener">${text}</a>`;
}

function showToast(message) {
  const toast = document.getElementById("toast");
  if (!toast) {
    alert(message);
    return;
  }
  toast.textContent = message;
  toast.hidden = false;
  window.clearTimeout(showToast.timer);
  showToast.timer = window.setTimeout(() => {
    toast.hidden = true;
  }, 2600);
}

function renderStatus(status) {
  const value = statusLabel(status);
  return `<span class="status-pill status-${value}"><span class="status-dot"></span>${value}</span>`;
}

async function initDashboard() {
  document.getElementById("refreshBtn")?.addEventListener("click", loadDashboard);
  document.getElementById("activityRefreshBtn")?.addEventListener("click", loadDashboard);
  document.getElementById("activityClearReloadBtn")?.addEventListener("click", clearDashboardAndReload);
  document.querySelectorAll(".segmented button").forEach((button) => {
    button.addEventListener("click", () => {
      document.querySelectorAll(".segmented button").forEach((item) => item.classList.remove("active"));
      button.classList.add("active");
      dashboardState.filter = button.dataset.filter;
      renderServiceTable();
    });
  });
  document.getElementById("serviceSearch")?.addEventListener("input", (event) => {
    dashboardState.query = event.target.value.trim().toLowerCase();
    renderServiceTable();
  });
  await loadDashboard();
  window.setInterval(loadDashboard, 30000);
}

function clearDashboardView() {
  dashboardState.services = [];
  dashboardState.recentResults = [];
  document.getElementById("totalCount").textContent = "0";
  document.getElementById("upCount").textContent = "0";
  document.getElementById("downCount").textContent = "0";
  const avgLatency = document.getElementById("avgLatency");
  const availabilityRate = document.getElementById("availabilityRate");
  const serviceTable = document.getElementById("serviceTable");
  const activityPanel = document.getElementById("recentAlerts");
  const refreshTime = document.getElementById("lastRefreshTime");
  if (avgLatency) avgLatency.textContent = "-";
  if (availabilityRate) availabilityRate.textContent = "-";
  if (serviceTable) serviceTable.innerHTML = '<tr><td colspan="9" class="empty">重新加载中...</td></tr>';
  if (activityPanel) activityPanel.innerHTML = '<p class="empty">重新加载中...</p>';
  if (refreshTime) refreshTime.textContent = "--:--:--";
}

async function clearDashboardAndReload() {
  clearDashboardView();
  await loadDashboard();
}

async function loadDashboard() {
  try {
    const data = await LiveMonitorApi.dashboard();
    dashboardState.services = data.services || [];
    dashboardState.recentResults = data.recent_results || [];
    const summary = data.summary || {};
    document.getElementById("totalCount").textContent = summary.total ?? 0;
    document.getElementById("upCount").textContent = summary.up ?? 0;
    document.getElementById("downCount").textContent = summary.down ?? 0;
    renderDashboardMetrics(summary);
    renderServiceTable();
    renderDashboardActivity(document.getElementById("recentAlerts"), data.recent_alerts || [], dashboardState.recentResults);
    const refreshTime = document.getElementById("lastRefreshTime");
    if (refreshTime) refreshTime.textContent = new Date().toLocaleTimeString("zh-CN", { hour12: false });
  } catch (error) {
    document.getElementById("serviceTable").innerHTML =
      `<tr><td colspan="9" class="empty">${error.message}</td></tr>`;
  }
}

function renderDashboardMetrics(summary) {
  const latencies = dashboardState.services
    .map((service) => Number(service.last_response_time_ms))
    .filter((value) => Number.isFinite(value));
  const avg = latencies.length
    ? Math.round(latencies.reduce((sum, value) => sum + value, 0) / latencies.length)
    : "-";
  const total = Number(summary.total || 0);
  const availability = total ? `${Math.round((Number(summary.up || 0) / total) * 100)}%` : "-";
  const avgLatency = document.getElementById("avgLatency");
  const availabilityRate = document.getElementById("availabilityRate");
  if (avgLatency) avgLatency.textContent = avg;
  if (availabilityRate) availabilityRate.textContent = availability;
}

function renderServiceTable() {
  const tbody = document.getElementById("serviceTable");
  if (!tbody) return;

  const rows = dashboardState.services.filter((service) => {
    const status = statusLabel(service.last_status);
    const haystack = [
      service.service_name,
      service.service_type,
      service.cluster_name,
      endpointText(service),
    ].join(" ").toLowerCase();
    const statusMatch = dashboardState.filter === "all" || status === dashboardState.filter;
    const queryMatch = !dashboardState.query || haystack.includes(dashboardState.query);
    return statusMatch && queryMatch;
  });

  if (!rows.length) {
    tbody.innerHTML = '<tr><td colspan="9" class="empty">暂无匹配服务</td></tr>';
    return;
  }

  tbody.innerHTML = rows.map((service) => `
    <tr>
      <td>${renderStatus(service.last_status)}</td>
      <td><strong>${escapeHtml(service.service_name)}</strong></td>
      <td>${serviceTypeLabel(service.service_type)}</td>
      <td>${escapeHtml(service.cluster_name || "-")}</td>
      <td>${endpointHtml(service)}</td>
      <td>${renderLatency(service.last_response_time_ms)}</td>
      <td>${renderSparkline(service)}</td>
      <td>${formatTime(service.last_checked_at)}</td>
      <td>
        <div class="row-actions">
          <a title="详情" href="${serviceDetailHref(service.id)}"><i data-lucide="eye"></i></a>
          <button title="立即检测" onclick="manualCheck(${service.id})"><i data-lucide="refresh-cw"></i></button>
          ${serviceOpenButton(service)}
          <a title="配置" href="${serviceEditHref(service.id)}"><i data-lucide="settings"></i></a>
        </div>
      </td>
    </tr>
  `).join("");
  if (window.lucide) window.lucide.createIcons();
}

function renderLatency(value) {
  if (value === null || value === undefined) return '<span class="latency latency-unknown">-</span>';
  const level = value > 2000 ? "bad" : value > 500 ? "warn" : "ok";
  return `<span class="latency latency-${level}">${value} ms</span>`;
}

function renderSparkline(service) {
  const status = statusLabel(service.last_status);
  const seed = Number(service.id || 1);
  const base = status === "DOWN" ? [3, 2, 1, 2, 1, 1] : status === "UNKNOWN" ? [1, 2, 1, 3, 2, 1] : [2, 3, 3, 4, 3, 5];
  const bars = base.map((height, index) => {
    const adjusted = Math.max(1, Math.min(5, height + ((seed + index) % 2)));
    return `<span style="--h:${adjusted}"></span>`;
  }).join("");
  return `<span class="sparkline spark-${status}">${bars}</span>`;
}

async function manualCheck(id) {
  try {
    await LiveMonitorApi.checkService(id);
    await loadDashboard();
  } catch (error) {
    showToast(error.message);
  }
}

async function initAddService() {
  const form = document.getElementById("serviceForm");
  const typeSelect = document.getElementById("serviceType");
  const pathMatch = window.location.pathname.match(/\/services\/(\d+)\/edit$/);
  const params = new URLSearchParams(window.location.search);
  const editId = pathMatch?.[1] || params.get("id");
  const syncFields = () => {
    const isWeb = typeSelect.value === "web";
    const isRedis = typeSelect.value === "redis";
    const isZookeeper = typeSelect.value === "zookeeper";
    toggleFieldSet(".web-only", isWeb);
    toggleFieldSet(".host-port", !isWeb);
    toggleFieldSet(".redis-only", isRedis);
    toggleFieldSet(".zookeeper-only", isZookeeper);
    const urlInput = form.elements.url;
    const hostInput = form.elements.host;
    const portInput = form.elements.port;
    if (urlInput) urlInput.required = isWeb;
    if (hostInput) hostInput.required = !isWeb;
    if (portInput) portInput.required = !isWeb;
  };
  typeSelect.addEventListener("change", syncFields);
  syncFields();
  await loadServiceAlertConfigOptions(form);

  document.getElementById("testConnectionBtn")?.addEventListener("click", async () => {
    const resultBox = document.getElementById("connectionTestResult");
    if (!form.reportValidity()) return;
    if (resultBox) {
      resultBox.hidden = false;
      resultBox.className = "test-result span-2 testing";
      resultBox.textContent = "正在测试连接...";
    }
    try {
      const result = await LiveMonitorApi.testService(buildServicePayload(form));
      if (resultBox) {
        resultBox.className = `test-result span-2 ${result.status === "UP" ? "ok" : "bad"}`;
        resultBox.innerHTML = `${escapeHtml(result.status)} · 响应时间 ${result.response_time_ms ?? "-"}ms · ${escapeHtml(result.message || "-")}`;
      }
    } catch (error) {
      if (resultBox) {
        resultBox.className = "test-result span-2 bad";
        resultBox.textContent = error.message;
      }
    }
  });

  if (editId) {
    try {
      const editService = await LiveMonitorApi.service(editId);
      document.title = "编辑服务 - Live Monitor";
      document.getElementById("serviceFormTitle").textContent = "编辑监控服务";
      document.getElementById("serviceFormNav").textContent = "编辑服务";
      document.querySelector("#serviceFormSubmit span").textContent = "保存修改";
      document.getElementById("serviceFormCancel").href = serviceDetailHref(editService.id);
      fillServiceForm(form, editService);
      syncFields();
    } catch (error) {
      showToast(error.message);
    }
  }

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const data = buildServicePayload(form);

    try {
      const service = editId
        ? await LiveMonitorApi.updateService(editId, data)
        : await LiveMonitorApi.createService(data);
      showToast(editId ? "服务修改已保存" : "服务已保存");
      window.setTimeout(() => {
        window.location.href = serviceDetailHref(service.id);
      }, 500);
    } catch (error) {
      showToast(error.message);
    }
  });
}

function toggleFieldSet(selector, visible) {
  document.querySelectorAll(selector).forEach((item) => {
    item.style.display = visible ? "" : "none";
  });
}

function buildServicePayload(form) {
  const data = Object.fromEntries(new FormData(form).entries());
  data.enabled = form.elements.enabled.checked;
  data.redis_cluster_mode = Boolean(form.elements.redis_cluster_mode?.checked);
  data.check_interval = Number(data.check_interval || 60);
  data.check_timeout_seconds = Number(data.check_timeout_seconds || 3);
  data.port = data.port ? Number(data.port) : null;
  data.expected_status_code = data.expected_status_code ? Number(data.expected_status_code) : null;
  data.zookeeper_expected_nodes = data.zookeeper_expected_nodes ? Number(data.zookeeper_expected_nodes) : null;
  data.url = data.service_type === "web" ? data.url.trim() : null;
  data.host = data.service_type === "web" ? null : data.host.trim();
  data.http_method = data.service_type === "web" ? data.http_method || "GET" : "GET";
  data.response_keyword = data.service_type === "web" ? data.response_keyword || null : null;
  data.expected_status_code = data.service_type === "web" ? data.expected_status_code : null;
  data.redis_username = data.service_type === "redis" ? data.redis_username || null : null;
  data.redis_password = data.service_type === "redis" ? data.redis_password || null : null;
  data.redis_cluster_mode = data.service_type === "redis" ? data.redis_cluster_mode : false;
  data.zookeeper_check_mode = data.service_type === "zookeeper" ? data.zookeeper_check_mode || "ruok" : "ruok";
  data.zookeeper_check_command = data.service_type === "zookeeper" ? data.zookeeper_check_command || "ruok" : "ruok";
  data.zookeeper_expected_nodes = data.service_type === "zookeeper" ? data.zookeeper_expected_nodes : null;
  data.alert_group_id = data.alert_group_id ? Number(data.alert_group_id) : null;
  data.alert_config_id = null;
  data.cluster_name = data.cluster_name || null;
  return data;
}

async function loadServiceAlertConfigOptions(form) {
  const select = form.elements.alert_group_id || form.elements.alert_config_id;
  if (!select) return;
  try {
    const groups = await LiveMonitorApi.alertGroups(false);
    select.innerHTML = [
      '<option value="">不绑定告警组</option>',
      ...groups.map((group) =>
        `<option value="${group.id}">${escapeHtml(group.group_name)} (${group.channels?.length || 0} 个渠道)</option>`
      ),
    ].join("");
  } catch (error) {
    select.innerHTML = '<option value="">告警组加载失败</option>';
    showToast(error.message);
  }
}

function fillServiceForm(form, service) {
  [
    "service_type",
    "service_name",
    "cluster_name",
    "check_interval",
    "check_timeout_seconds",
    "url",
    "http_method",
    "expected_status_code",
    "response_keyword",
    "host",
    "port",
    "redis_username",
    "zookeeper_check_mode",
    "zookeeper_check_command",
    "zookeeper_expected_nodes",
    "alert_group_id",
  ].forEach((name) => {
    if (form.elements[name]) {
      const defaults = {
        check_timeout_seconds: 3,
        http_method: "GET",
        zookeeper_check_command: "ruok",
      };
      form.elements[name].value = service[name] ?? defaults[name] ?? "";
    }
  });
  if (form.elements.redis_password) {
    form.elements.redis_password.value = "";
    form.elements.redis_password.placeholder = service.service_type === "redis"
      ? "留空则保持原密码"
      : "Redis AUTH 密码";
  }
  form.elements.enabled.checked = Boolean(service.enabled);
  if (form.elements.redis_cluster_mode) {
    form.elements.redis_cluster_mode.checked = Boolean(service.redis_cluster_mode);
  }
}

async function initServiceDetail() {
  const params = new URLSearchParams(window.location.search);
  const pathMatch = window.location.pathname.match(/\/services\/(\d+)$/);
  const id = pathMatch?.[1] || params.get("id");
  if (!id) {
    showToast("缺少服务 ID");
    return;
  }
  document.getElementById("manualCheckBtn")?.addEventListener("click", async () => {
    try {
      await LiveMonitorApi.checkService(id);
      await loadServiceDetail(id);
      showToast("检测完成");
    } catch (error) {
      showToast(error.message);
    }
  });
  await loadServiceDetail(id);
  window.setInterval(() => loadServiceDetail(id), 30000);
}

async function loadServiceDetail(id) {
  const [service, results, alerts] = await Promise.all([
    LiveMonitorApi.service(id),
    LiveMonitorApi.results(id, 20),
    LiveMonitorApi.alerts(id, 50),
  ]);

  document.getElementById("detailName").textContent = service.service_name;
  document.getElementById("detailMeta").innerHTML =
    `${escapeHtml(serviceTypeLabel(service.service_type))} · ${escapeHtml(service.cluster_name || "未分组")} · ${endpointHtml(service)}`;
  const editLink = document.getElementById("serviceEditLink");
  if (editLink) editLink.href = serviceEditHref(service.id);
  const openLink = document.getElementById("serviceOpenLink");
  if (openLink) {
    openLink.hidden = !isHttpService(service);
    if (isHttpService(service)) openLink.href = service.url;
  }
  const status = statusLabel(service.last_status);
  document.getElementById("detailStatus").className = `status-pill status-${status}`;
  document.getElementById("detailStatus").textContent = status;
  document.getElementById("currentStatus").textContent = status;
  document.getElementById("currentMessage").textContent = service.last_message || "暂无检测结果";
  document.getElementById("lastResponse").textContent = service.last_response_time_ms ?? "-";
  document.getElementById("lastChecked").textContent = formatTime(service.last_checked_at);
  document.getElementById("checkInterval").textContent = service.check_interval;

  renderResultTable(results);
  renderAlerts(document.getElementById("detailAlerts"), alerts);
  window.LiveMonitorCharts?.renderTrendChart(document.getElementById("trendChart"), results);
  if (window.lucide) window.lucide.createIcons();
}

async function initAlertSettings() {
  const layout = document.getElementById('alertSettingsLayout');
  const bindingBtn = document.getElementById('showBindingTestBtn');
  const configBtn = document.getElementById('showAlertConfigBtn');
  const newAlertBtn = document.getElementById('newAlertGroupBtn');

  function switchAlertPage(mode) {
    const isConfig = mode === 'config';
    if (layout) {
      layout.classList.toggle('config-mode', isConfig);
      layout.classList.toggle('binding-mode', !isConfig);
    }
    if (bindingBtn) {
      bindingBtn.classList.toggle('active', !isConfig);
      bindingBtn.setAttribute('aria-selected', String(!isConfig));
    }
    if (configBtn) {
      configBtn.classList.toggle('active', isConfig);
      configBtn.setAttribute('aria-selected', String(isConfig));
    }
  }

  bindingBtn?.addEventListener('click', () => switchAlertPage('binding'));
  configBtn?.addEventListener('click', () => switchAlertPage('config'));
  newAlertBtn?.addEventListener('click', () => switchAlertPage('config'));

  document.getElementById("reloadAlertSettingsBtn")?.addEventListener("click", loadAlertSettings);
  document.getElementById("deleteAlertGroupBtn")?.addEventListener("click", deleteSelectedAlertGroup);
  document.getElementById("newAlertChannelBtn")?.addEventListener("click", () => {
    alertSettingsState.selectedChannelId = null;
    renderAlertChannelOptions();
    renderSelectedAlertChannel();
  });
  document.getElementById("alertChannelConfigSelect")?.addEventListener("change", (event) => {
    alertSettingsState.selectedChannelId = Number(event.target.value) || null;
    renderSelectedAlertChannel();
  });
  document.getElementById("alertChannelTypeSelect")?.addEventListener("change", syncChannelInputs);

  document.getElementById("alertGroupForm")?.addEventListener("submit", async (event) => {
    event.preventDefault();
    const groupName = document.getElementById("alertGroupNameInput").value.trim();
    if (!groupName) {
      showToast("请输入告警组名称");
      return;
    }
    const payload = {
      group_name: groupName,
      description: document.getElementById("alertGroupDescriptionInput").value.trim() || null,
      enabled: document.getElementById("alertGroupEnabledInput").checked,
      policy_ids: checkedIds("policyChecklist"),
      channel_ids: checkedIds("channelChecklist"),
    };
    if (!payload.policy_ids.length) {
      showToast("请至少选择一个告警策略");
      return;
    }
    if (!payload.channel_ids.length) {
      showToast("请至少选择一个通知渠道");
      return;
    }

    try {
      const saved = alertSettingsState.selectedGroupId
        ? await LiveMonitorApi.updateAlertGroup(alertSettingsState.selectedGroupId, payload)
        : await LiveMonitorApi.createAlertGroup(payload);
      alertSettingsState.selectedGroupId = saved.id;
      await loadAlertSettings();
      showToast("告警组已保存");
    } catch (error) {
      showToast(error.message);
    }
  });

  document.getElementById("alertChannelForm")?.addEventListener("submit", async (event) => {
    event.preventDefault();
    const payload = buildAlertChannelPayload();
    if (!payload.channel_name) {
      showToast("请输入渠道名称");
      return;
    }
    if (payload.channel_type === "email" && !payload.alert_email) {
      showToast("请输入邮箱接收人");
      return;
    }
    if (payload.channel_type === "sms" && !payload.alert_mobile) {
      showToast("请输入手机号接收人");
      return;
    }

    try {
      const saved = alertSettingsState.selectedChannelId
        ? await LiveMonitorApi.updateAlertChannel(alertSettingsState.selectedChannelId, payload)
        : await LiveMonitorApi.createAlertChannel(payload);
      alertSettingsState.selectedChannelId = saved.id;
      await loadAlertSettings();
      showToast("通知渠道已保存");
    } catch (error) {
      showToast(error.message);
    }
  });

  await loadAlertSettings();
}

async function loadAlertSettings() {
  try {
    const [services, groups, policies, channels, alerts] = await Promise.all([
      LiveMonitorApi.services(true),
      LiveMonitorApi.alertGroups(true),
      LiveMonitorApi.alertPolicies(true),
      LiveMonitorApi.alertChannels(true),
      LiveMonitorApi.allAlerts(20),
    ]);
    alertSettingsState.services = services || [];
    alertSettingsState.groups = groups || [];
    alertSettingsState.policies = policies || [];
    alertSettingsState.channels = channels || [];
    if (!alertSettingsState.selectedGroupId && alertSettingsState.groups.length) {
      alertSettingsState.selectedGroupId = alertSettingsState.groups[0].id;
    }
    if (!alertSettingsState.selectedChannelId && alertSettingsState.channels.length) {
      alertSettingsState.selectedChannelId = alertSettingsState.channels[0].id;
    }
    renderAlertGroups();
    renderSelectedAlertGroup();
    renderAlertChannelOptions();
    renderSelectedAlertChannel();
    renderAlertSettingsTable();
    renderAlerts(document.getElementById("settingsRecentAlerts"), alerts || []);
  } catch (error) {
    const table = document.getElementById("alertSettingsTable");
    if (table) table.innerHTML = `<tr><td colspan="2" class="empty">${error.message}</td></tr>`;
    showToast(error.message);
  }
}

function renderAlertGroups() {
  const list = document.getElementById("alertGroupList");
  if (!list) return;
  if (!alertSettingsState.groups.length) {
    list.innerHTML = '<p class="empty">暂无告警组</p>';
    return;
  }
  list.innerHTML = alertSettingsState.groups.map((group) => `
    <button class="group-item ${group.id === alertSettingsState.selectedGroupId ? "active" : ""}" type="button" onclick="selectAlertGroup(${group.id})">
      <span>
        <strong>${escapeHtml(group.group_name)}</strong>
        <small>${group.enabled ? "启用" : "停用"} · ${group.service_count || 0} 个服务 · ${group.channels?.length || 0} 个渠道</small>
      </span>
      <span>${group.policy_ids?.length || 0}</span>
    </button>
  `).join("");
}

function selectAlertGroup(id) {
  alertSettingsState.selectedGroupId = id;
  renderAlertGroups();
  renderSelectedAlertGroup();
}

function renderSelectedAlertGroup() {
  const group = alertSettingsState.groups.find((item) => item.id === alertSettingsState.selectedGroupId);
  const groupNameInput = document.getElementById("alertGroupNameInput");
  const groupDescInput = document.getElementById("alertGroupDescriptionInput");
  const groupEnabledInput = document.getElementById("alertGroupEnabledInput");
  if (groupNameInput) groupNameInput.value = group?.group_name || "";
  if (groupDescInput) groupDescInput.value = group?.description || "";
  if (groupEnabledInput) groupEnabledInput.checked = group ? Boolean(group.enabled) : true;
  renderPolicyChecklist(group?.policy_ids || []);
  renderChannelChecklist(group?.channel_ids || []);
  renderRecipientSummary(group?.channel_ids || []);
  const deleteButton = document.getElementById("deleteAlertGroupBtn");
  if (deleteButton) deleteButton.disabled = !group;
}

function renderPolicyChecklist(selectedIds) {
  const container = document.getElementById("policyChecklist");
  if (!container) return;
  if (!alertSettingsState.policies.length) {
    container.innerHTML = '<p class="empty">暂无策略</p>';
    return;
  }
  const selected = new Set(selectedIds.map(Number));
  container.innerHTML = alertSettingsState.policies.map((policy) => `
    <label class="choice-card">
      <input type="checkbox" value="${policy.id}" ${selected.has(Number(policy.id)) ? "checked" : ""}>
      <span>
        <strong>${escapeHtml(policy.policy_name)}</strong>
        <small>${escapeHtml(policyText(policy))}</small>
      </span>
    </label>
  `).join("");
}

function renderChannelChecklist(selectedIds) {
  const container = document.getElementById("channelChecklist");
  if (!container) return;
  if (!alertSettingsState.channels.length) {
    container.innerHTML = '<p class="empty">暂无渠道，请先在渠道库保存一个渠道</p>';
    return;
  }
  const selected = new Set(selectedIds.map(Number));
  container.innerHTML = alertSettingsState.channels.map((channel) => `
    <label class="choice-card">
      <input type="checkbox" value="${channel.id}" ${selected.has(Number(channel.id)) ? "checked" : ""} onchange="renderRecipientSummary(checkedIds('channelChecklist'))">
      <span>
        <strong>${escapeHtml(channel.channel_name)}</strong>
        <small>${channelTypeLabel(channel.channel_type)} · ${channel.enabled ? "启用" : "停用"} · ${escapeHtml(channelRecipientText(channel))}</small>
      </span>
    </label>
  `).join("");
}

function renderRecipientSummary(selectedIds) {
  const container = document.getElementById("recipientSummary");
  if (!container) return;
  const selected = new Set((selectedIds || []).map(Number));
  const channels = alertSettingsState.channels.filter((channel) => selected.has(Number(channel.id)));
  if (!channels.length) {
    container.innerHTML = '<p class="empty">选择通知渠道后显示接收人</p>';
    return;
  }
  container.innerHTML = channels.map((channel) => `
    <article class="recipient-item">
      <span class="recipient-icon"><i data-lucide="${channelIcon(channel.channel_type)}"></i></span>
      <span>
        <strong>${escapeHtml(channel.channel_name)}</strong>
        <small>${escapeHtml(channelRecipientText(channel))}</small>
      </span>
    </article>
  `).join("");
  if (window.lucide) window.lucide.createIcons();
}

function checkedIds(containerId) {
  return Array.from(document.querySelectorAll(`#${containerId} input[type="checkbox"]:checked`))
    .map((input) => Number(input.value))
    .filter(Boolean);
}

function policyText(policy) {
  if (policy.trigger_type === "consecutive_down") return `连续失败 ${policy.trigger_value || 3} 次触发`;
  if (policy.trigger_type === "latency_gt_ms") return `响应时间超过 ${policy.trigger_value || 3000} ms 触发`;
  if (policy.trigger_type === "recovered") return "服务从 DOWN 恢复到 UP 时触发";
  return `${policy.trigger_type}: ${policy.trigger_value || "-"}`;
}

function channelTypeLabel(type) {
  return { email: "邮件", sms: "短信", webhook: "Webhook", dingtalk: "钉钉" }[type] || type || "渠道";
}

function channelIcon(type) {
  return { email: "mail", sms: "message-square", webhook: "webhook", dingtalk: "bot" }[type] || "send";
}

function channelRecipientText(channel) {
  if (channel.channel_type === "email") return channel.alert_email || "未填写邮箱";
  if (channel.channel_type === "sms") return formatMobilesForTextarea(channel.alert_mobile || "").replaceAll("\n", "、") || "未填写手机号";
  return channel.webhook_url || channel.sms_api_url || "未填写 Webhook";
}

async function deleteSelectedAlertGroup() {
  const groupId = alertSettingsState.selectedGroupId;
  if (!groupId) return;
  if (!window.confirm("删除告警组后，已绑定服务会变为未绑定。确定删除？")) return;
  try {
    await LiveMonitorApi.deleteAlertGroup(groupId);
    alertSettingsState.selectedGroupId = null;
    await loadAlertSettings();
    showToast("告警组已删除");
  } catch (error) {
    showToast(error.message);
  }
}

function formatMobilesForTextarea(value) {
  return String(value || "")
    .split(/[,;\s\uFF0C\uFF1B]+/)
    .map((item) => item.trim())
    .filter(Boolean)
    .join("\n");
}

function renderAlertChannelOptions() {
  const select = document.getElementById("alertChannelConfigSelect");
  if (!select) return;
  if (!alertSettingsState.channels.length) {
    select.innerHTML = '<option value="">暂无渠道，保存后创建</option>';
    return;
  }
  select.innerHTML = alertSettingsState.channels.map((channel) => `
    <option value="${channel.id}">${escapeHtml(channel.channel_name)} (${channelTypeLabel(channel.channel_type)})</option>
  `).join("");
  select.value = String(alertSettingsState.selectedChannelId || alertSettingsState.channels[0].id);
}

function renderSelectedAlertChannel() {
  const channel = alertSettingsState.channels.find((item) => item.id === alertSettingsState.selectedChannelId);
  const setValueIfExists = (id, value) => {
    const el = document.getElementById(id);
    if (el) el.value = value;
  };
  const setCheckedIfExists = (id, checked) => {
    const el = document.getElementById(id);
    if (el) el.checked = checked;
  };

  setValueIfExists("alertChannelNameInput", channel?.channel_name || "");
  setValueIfExists("alertChannelTypeSelect", channel?.channel_type || "email");
  setCheckedIfExists("alertChannelEnabledInput", channel ? Boolean(channel.enabled) : true);
  setValueIfExists("alertEmailInput", channel?.alert_email || "");
  setValueIfExists("alertMobileInput", formatMobilesForTextarea(channel?.alert_mobile || ""));
  setValueIfExists("smtpHostInput", channel?.smtp_host || "");
  setValueIfExists("smtpPortInput", channel?.smtp_port || "");
  setValueIfExists("smtpUserInput", channel?.smtp_user || "");
  setValueIfExists("smtpPasswordInput", "");
  setValueIfExists("smtpFromInput", channel?.smtp_from || "");
  setCheckedIfExists("smtpTlsInput", Boolean(channel?.smtp_use_tls));
  setValueIfExists("smsApiUrlInput", channel?.webhook_url || channel?.sms_api_url || "");
  setValueIfExists("smsApiTokenInput", "");
  setValueIfExists("smsUsernameInput", channel?.sms_username || "");
  setValueIfExists("smsPasswordInput", "");
  setCheckedIfExists("smsPasswordIsMd5Input", channel ? Boolean(channel.sms_password_is_md5) : true);
  setValueIfExists("smsPasswordMd5Input", "");
  setValueIfExists("smsRstypeInput", channel?.sms_rstype || "text");
  setValueIfExists("smsExtCodeInput", channel?.sms_ext_code || "");
  syncChannelInputs();
}

function syncChannelInputs() {
  const typeSelect = document.getElementById("alertChannelTypeSelect");
  if (!typeSelect) return;
  const channelType = typeSelect.value || "email";
  document.querySelectorAll(".channel-field").forEach((field) => {
    field.hidden = true;
  });
  document.querySelectorAll(`.${channelType}-channel-field`).forEach((field) => {
    field.hidden = false;
  });
}

function buildAlertChannelPayload() {
  const getValueIfExists = (id) => {
    const el = document.getElementById(id);
    return el ? el.value.trim() : "";
  };
  const getCheckedIfExists = (id) => {
    const el = document.getElementById(id);
    return el ? el.checked : false;
  };

  const channelTypeSelect = document.getElementById("alertChannelTypeSelect");
  if (!channelTypeSelect) return {};

  const channelType = channelTypeSelect.value;
  const smtpPort = getValueIfExists("smtpPortInput");
  const apiUrl = getValueIfExists("smsApiUrlInput");
  return {
    channel_name: getValueIfExists("alertChannelNameInput"),
    channel_type: channelType,
    alert_email: channelType === "email" ? getValueIfExists("alertEmailInput") || null : null,
    alert_mobile: channelType === "sms" ? getValueIfExists("alertMobileInput") || null : null,
    smtp_host: channelType === "email" ? getValueIfExists("smtpHostInput") || null : null,
    smtp_port: channelType === "email" && smtpPort ? Number(smtpPort) : null,
    smtp_user: channelType === "email" ? getValueIfExists("smtpUserInput") || null : null,
    smtp_password: channelType === "email" ? getValueIfExists("smtpPasswordInput") || null : null,
    smtp_from: channelType === "email" ? getValueIfExists("smtpFromInput") || null : null,
    smtp_use_tls: channelType === "email" ? getCheckedIfExists("smtpTlsInput") : false,
    sms_api_url: channelType === "sms" ? apiUrl || null : null,
    sms_api_token: channelType === "sms" ? getValueIfExists("smsApiTokenInput") || null : null,
    sms_username: channelType === "sms" ? getValueIfExists("smsUsernameInput") || null : null,
    sms_password: channelType === "sms" ? getValueIfExists("smsPasswordInput") || null : null,
    sms_password_is_md5: channelType === "sms" ? getCheckedIfExists("smsPasswordIsMd5Input") : true,
    sms_password_md5: channelType === "sms" ? getValueIfExists("smsPasswordMd5Input") || null : null,
    sms_rstype: channelType === "sms" ? getValueIfExists("smsRstypeInput") || "text" : "text",
    sms_ext_code: channelType === "sms" ? getValueIfExists("smsExtCodeInput") || null : null,
    webhook_url: ["webhook", "dingtalk"].includes(channelType) ? apiUrl || null : null,
    enabled: getCheckedIfExists("alertChannelEnabledInput"),
  };
}

function renderAlertSettingsTable() {
  const tbody = document.getElementById("alertSettingsTable");
  if (!tbody) return;
  if (!alertSettingsState.services.length) {
    tbody.innerHTML = '<tr><td colspan="3" class="empty">暂无服务</td></tr>';
    return;
  }
  tbody.innerHTML = alertSettingsState.services.map((service) => `
    <tr>
      <td>${escapeHtml(service.service_name)}</td>
      <td>
        <select class="config-select" onchange="bindServiceAlertGroup(${service.id}, this.value)">
          ${renderAlertGroupSelectOptions(service.alert_group_id)}
        </select>
      </td>
      <td class="actions-column">
        <div class="row-actions compact">
          <button class="icon-button" title="服务探测" onclick="testServiceAlert(${service.id})"><i data-lucide="zap"></i></button>
          <button class="icon-button" title="告警测试" onclick="sendTestAlert(${service.id})"><i data-lucide="bell"></i></button>
        </div>
      </td>
    </tr>
  `).join("");
  if (window.lucide) window.lucide.createIcons();
}

function renderAlertGroupSelectOptions(selectedId) {
  return [
    '<option value="">不绑定</option>',
    ...alertSettingsState.groups.map((group) => `
      <option value="${group.id}" ${Number(selectedId) === Number(group.id) ? "selected" : ""}>
        ${escapeHtml(group.group_name)}${group.enabled ? "" : " / 已停用"}
      </option>
    `),
  ].join("");
}

async function bindServiceAlertGroup(serviceId, value) {
  try {
    const updated = await LiveMonitorApi.updateServiceAlertGroup(serviceId, {
      alert_group_id: value ? Number(value) : null,
    });
    alertSettingsState.services = alertSettingsState.services.map((service) =>
      service.id === updated.id ? updated : service
    );
    renderAlertSettingsTable();
    showToast("服务告警组已更新");
  } catch (error) {
    showToast(error.message);
    renderAlertSettingsTable();
  }
}

async function testServiceAlert(serviceId) {
  try {
    showToast("正在探测服务...");
    await LiveMonitorApi.checkService(serviceId);
    showToast("服务探测已完成");
    await loadAlertSettings();
  } catch (error) {
    showToast(`服务探测失败: ${error.message}`);
  }
}

async function sendTestAlert(serviceId) {
  const service = alertSettingsState.services.find((s) => s.id === serviceId);
  if (!service) {
    showToast("找不到服务");
    return;
  }
  if (!service.alert_group_id) {
    showToast("该服务未绑定告警组，请先绑定");
    return;
  }
  try {
    showToast("正在发送告警测试...");
    // 构建测试告警请求
    const response = await fetch(`/api/services/${serviceId}/alert-test`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
    });

    if (!response.ok) {
      const error = await response.json();
      throw new Error(error.detail || "告警测试发送失败");
    }
    showToast("告警测试已发送");
  } catch (error) {
    showToast(`告警测试失败: ${error.message}`);
  }
}

function renderResultTable(results) {
  const tbody = document.getElementById("resultTable");
  if (!tbody) return;
  if (!results.length) {
    tbody.innerHTML = '<tr><td colspan="4" class="empty">暂无检测历史</td></tr>';
    return;
  }
  tbody.innerHTML = results.map((item) => `
    <tr>
      <td>${renderStatus(item.status)}</td>
      <td>${item.response_time_ms ?? "-"} ms</td>
      <td>${escapeHtml(item.message || "-")}</td>
      <td>${formatTime(item.checked_at)}</td>
    </tr>
  `).join("");
}

function renderAlerts(container, alerts, options = {}) {
  if (!container) return;
  const rows = options.uniqueByService ? uniqueByService(alerts) : alerts;
  if (!rows.length) {
    container.innerHTML = '<p class="empty">暂无告警</p>';
    return;
  }
  container.innerHTML = rows.map((alert) => `
    <article class="alert-item">
      <strong>${escapeHtml(alert.service_name || alert.alert_type || "告警")}</strong>
      <p>${escapeHtml(alert.alert_content || "-")}</p>
      <small>${alert.alert_type || "-"} · ${alert.alert_status || "-"} · ${formatTime(alert.created_at)}</small>
    </article>
  `).join("");
}

function renderDashboardActivity(container, alerts, results) {
  if (!container) return;
  if (alerts.length) {
    renderAlerts(container, alerts, { uniqueByService: true });
    return;
  }
  const uniqueResults = uniqueByService(results);
  if (!uniqueResults.length) {
    container.innerHTML = uniqueByService(dashboardState.services).map((service) => `
      <article class="activity-item">
        <span class="activity-status ${statusLabel(service.last_status)}"></span>
        <div>
          <strong>${escapeHtml(service.service_name)}</strong>
          <p>${statusLabel(service.last_status)} · ${renderLatency(service.last_response_time_ms)} · ${formatTime(service.last_checked_at)}</p>
        </div>
      </article>
    `).join("") || '<p class="empty">暂无动态</p>';
    return;
  }
  container.innerHTML = uniqueResults.map((item) => `
    <article class="activity-item">
      <span class="activity-status ${statusLabel(item.status)}"></span>
      <div>
        <strong>${escapeHtml(item.service_name || "检测记录")}</strong>
        <p>${statusLabel(item.status)} · ${renderLatency(item.response_time_ms)} · ${formatTime(item.checked_at)}</p>
      </div>
    </article>
  `).join("");
}

function uniqueByService(items) {
  const seen = new Set();
  return items.filter((item) => {
    const key = item.service_id ?? item.id ?? item.service_name;
    if (key === null || key === undefined || seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}
