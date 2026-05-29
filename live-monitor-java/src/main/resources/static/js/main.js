const page = document.body.dataset.page;
let dashboardState = {
  services: [],
  recentResults: [],
  alertGroups: [],
  serviceGroups: [],
  expandedServiceGroupKeys: new Set(),
  expandedInitialized: false,
  filter: "all",
  query: "",
  instanceQueries: {},
  instancePages: {},
  groupStatusFilters: {},
  dismissedActivityKeys: new Set(),
  lastActivityCandidates: [],
  lastActivityRows: [],
};
let alertSettingsState = {
  services: [],
  groups: [],
  policies: [],
  channels: [],
  selectedGroupId: null,
  selectedChannelId: null,
  testResults: {},
  busyActions: {},
};
let hostState = {
  hosts: [],
  processes: [],
  processStatus: {},
  alertGroups: [],
  selectedHostId: null,
  metrics: null,
  metricTimer: null,
  listMetricTimer: null,
  listMetricSnapshots: {},
  metricHistory: {
    cpu: [],
    load: [],
    memory: [],
    disk: [],
  },
};
let notificationState = {
  alerts: [],
  seenAlertId: Number(localStorage.getItem("liveMonitorSeenAlertId") || 0),
  poller: null,
};
let resourceMetricState = {
  history: {
    cpu: [],
    memory: [],
    disk: [],
    network: [],
  },
  poller: null,
};

document.addEventListener("DOMContentLoaded", () => {
  if (window.lucide) {
    window.lucide.createIcons();
  }

  initUserChrome();
  if (page === "dashboard") initDashboard();
  if (page === "add-service") initAddService();
  if (page === "service-detail") initServiceDetail();
  if (page === "alert-settings") initAlertSettings();
  if (page === "hosts") initHosts();
});

function statusLabel(status) {
  return status || "UNKNOWN";
}

function serviceTypeLabel(type) {
  return {
    web: "Web 应用 (HTTP/HTTPS)",
    http: "HTTP 应用",
    https: "HTTPS 应用",
    nginx: "Nginx",
    host: "服务器主机",
    process: "进程检测",
    port: "端口检测 (TCP/UDP)",
    tcp: "TCP 端口",
    redis: "Redis",
    zookeeper: "ZooKeeper",
    mysql: "MySQL",
    oracle: "Oracle",
    postgresql: "PostgreSQL",
    postgres: "PostgreSQL",
  }[type] || type;
}

function serviceTypeIcon(type) {
  return {
    web: "globe",
    http: "globe",
    https: "lock",
    nginx: "network",
    host: "server",
    process: "activity",
    port: "plug",
    tcp: "plug",
    redis: "layers-3",
    zookeeper: "workflow",
    mysql: "database",
    oracle: "database",
    postgresql: "database",
    postgres: "database",
  }[type] || "server";
}

function serviceTypeIconHtml(type) {
  return `<span class="service-type-icon service-type-${escapeHtml(type || "custom")}" title="${escapeHtml(serviceTypeLabel(type))}">
    <i data-lucide="${serviceTypeIcon(type)}"></i>
  </span>`;
}

function renderServiceTypeOptionContent(type, label) {
  return `${serviceTypeIconHtml(type)}<span>${escapeHtml(label || serviceTypeLabel(type))}</span>`;
}

function initServiceTypePicker(select) {
  if (!select || select.dataset.enhanced === "true") return;
  select.dataset.enhanced = "true";

  const picker = document.createElement("div");
  picker.className = "service-type-picker";
  picker.innerHTML = `
    <button class="service-type-trigger" type="button" aria-haspopup="listbox" aria-expanded="false"></button>
    <div class="service-type-menu" role="listbox" hidden></div>
  `;
  select.insertAdjacentElement("afterend", picker);

  const trigger = picker.querySelector(".service-type-trigger");
  const menu = picker.querySelector(".service-type-menu");

  const close = () => {
    picker.classList.remove("open");
    trigger.setAttribute("aria-expanded", "false");
    menu.hidden = true;
  };
  const open = () => {
    picker.classList.add("open");
    trigger.setAttribute("aria-expanded", "true");
    menu.hidden = false;
    const active = menu.querySelector(".service-type-option.active") || menu.querySelector(".service-type-option");
    active?.focus();
  };
  const render = () => {
    const selected = select.selectedOptions[0] || select.options[0];
    trigger.innerHTML = `
      <span class="service-type-trigger-content">
        ${renderServiceTypeOptionContent(selected.value, selected.textContent.trim())}
      </span>
      <i data-lucide="chevron-down"></i>
    `;
    menu.innerHTML = Array.from(select.options).map((option) => `
      <button class="service-type-option ${option.value === select.value ? "active" : ""}" type="button" role="option" data-value="${escapeHtml(option.value)}" aria-selected="${option.value === select.value}">
        ${renderServiceTypeOptionContent(option.value, option.textContent.trim())}
      </button>
    `).join("");
    if (window.lucide) window.lucide.createIcons();
  };

  trigger.addEventListener("click", () => {
    if (picker.classList.contains("open")) {
      close();
    } else {
      render();
      open();
    }
  });
  trigger.addEventListener("keydown", (event) => {
    if (event.key === "ArrowDown" || event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      render();
      open();
    }
  });
  menu.addEventListener("click", (event) => {
    const option = event.target.closest(".service-type-option");
    if (!option) return;
    select.value = option.dataset.value;
    select.dispatchEvent(new Event("change", { bubbles: true }));
    close();
    trigger.focus();
  });
  menu.addEventListener("keydown", (event) => {
    const options = Array.from(menu.querySelectorAll(".service-type-option"));
    const index = options.indexOf(document.activeElement);
    if (event.key === "Escape") {
      event.preventDefault();
      close();
      trigger.focus();
    }
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      const nextIndex = event.key === "ArrowDown"
        ? Math.min(options.length - 1, index + 1)
        : Math.max(0, index - 1);
      options[nextIndex]?.focus();
    }
  });
  document.addEventListener("click", (event) => {
    if (!picker.contains(event.target)) close();
  });
  select.addEventListener("change", render);
  render();
}

function formatTime(value) {
  if (!value) return "-";
  const normalized = value.includes("T") ? value : `${value.replace(" ", "T")}Z`;
  const date = new Date(normalized);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("zh-CN", { hour12: false });
}

function endpointText(service) {
  if (isWebUrlServiceType(service.service_type)) return service.url || "-";
  if (service.service_type === "process") {
    return `${service.host || "-"} / ${service.process_name || service.process_match_keyword || "-"} / ${service.check_command || "-"}`;
  }
  if (service.service_type === "host") {
    return `${service.host || service.endpoint || "-"} / CPU ${service.cpu_threshold_percent ?? "-"}% / 磁盘 ${service.disk_threshold_percent ?? "-"}%`;
  }
  if (["mysql", "oracle", "postgresql", "postgres"].includes(service.service_type)) {
    const endpoint = `${service.host || "-"}:${service.port || "-"}`;
    return service.database_name ? `${endpoint}/${service.database_name}` : endpoint;
  }
  return `${service.host || "-"}:${service.port || "-"}`;
}

function serviceDetailHref(id) {
  return `/services/${id}`;
}

function serviceEditHref(id) {
  return `/services/${id}/edit`;
}

function isHttpService(service) {
  return isWebUrlServiceType(service.service_type) && /^https?:\/\//i.test(service.url || "");
}

function isWebUrlServiceType(type) {
  return ["web", "http", "https", "nginx"].includes(type);
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

async function initUserChrome() {
  const topbar = document.querySelector(".topbar");
  if (!topbar || document.getElementById("notificationBell")) return;
  const userArea = document.createElement("div");
  userArea.className = "topbar-user";
  userArea.innerHTML = `
    <div class="notification-wrap">
      <button class="icon-button notification-bell" id="notificationBell" type="button" title="\u544a\u8b66\u901a\u77e5" aria-label="\u544a\u8b66\u901a\u77e5" aria-expanded="false">
        <i data-lucide="bell"></i>
        <span id="notificationBadge" class="notification-badge" hidden>0</span>
      </button>
      <div id="notificationPanel" class="notification-panel" hidden>
        <div class="notification-panel-head">
          <strong>\u544a\u8b66\u901a\u77e5</strong>
          <button class="text-button" type="button" onclick="markNotificationsSeen()">\u5168\u90e8\u5df2\u8bfb</button>
        </div>
        <div id="notificationList" class="notification-list"><p class="empty">\u6682\u65e0\u544a\u8b66</p></div>
      </div>
    </div>
    <div class="user-menu-wrap">
      <button class="current-user" type="button" id="userMenuButton" aria-haspopup="menu" aria-expanded="false">
        <span id="currentUserName">admin</span>
        <i data-lucide="chevron-down"></i>
      </button>
      <div id="userMenuPanel" class="user-menu-panel" role="menu" hidden>
        <button class="user-menu-logout" type="button" id="userLogoutButton" role="menuitem">
          <i data-lucide="log-out"></i>
          <span>\u9000\u51fa\u767b\u5f55</span>
        </button>
      </div>
    </div>
  `;
  topbar.appendChild(userArea);
  document.getElementById("notificationBell")?.addEventListener("click", toggleNotificationPanel);
  document.getElementById("userMenuButton")?.addEventListener("click", toggleUserMenu);
  document.getElementById("userLogoutButton")?.addEventListener("click", logout);
  document.addEventListener("click", closeNotificationPanelOnOutsideClick);
  document.addEventListener("click", closeUserMenuOnOutsideClick);
  if (window.lucide) window.lucide.createIcons();
  try {
    const user = await LiveMonitorApi.currentUser();
    if (user?.name) document.getElementById("currentUserName").textContent = user.name;
  } catch (error) {
    // 401 handling lives in api.js.
  }
  await loadBellAlerts(false);
  notificationState.poller = window.setInterval(() => loadBellAlerts(true), 10000);
}

async function loadBellAlerts(notifyNew) {
  try {
    const alerts = await LiveMonitorApi.allAlerts(20);
    notificationState.alerts = alerts || [];
    const latestId = Number(notificationState.alerts[0]?.id || 0);
    if (notifyNew && latestId > notificationState.seenAlertId && notificationState.seenAlertId > 0) {
      showToast("\u6536\u5230\u65b0\u544a\u8b66\u901a\u77e5");
    }
    renderNotificationBell();
  } catch (error) {
    renderNotificationBell();
  }
}

function renderNotificationBell() {
  const badge = document.getElementById("notificationBadge");
  const bell = document.getElementById("notificationBell");
  if (!badge) return;
  const unread = notificationState.alerts.filter((alert) => Number(alert.id || 0) > notificationState.seenAlertId).length;
  badge.textContent = unread > 99 ? "99+" : String(unread);
  badge.hidden = unread === 0;
  bell?.classList.toggle("has-unread", unread > 0);
  renderNotificationList();
}

function renderNotificationList() {
  const list = document.getElementById("notificationList");
  if (!list) return;
  if (!notificationState.alerts.length) {
    list.innerHTML = '<p class="empty">\u6682\u65e0\u544a\u8b66</p>';
    return;
  }
  list.innerHTML = notificationState.alerts.map((alert) => `
    <article class="notification-item ${Number(alert.id || 0) > notificationState.seenAlertId ? "unread" : ""}">
      <strong>${escapeHtml(alert.service_name || "\u544a\u8b66")}</strong>
      <p>${escapeHtml(alert.alert_content || "-")}</p>
      <small>${escapeHtml(alert.alert_type || "-")} · ${formatTime(alert.created_at)}</small>
    </article>
  `).join("");
}

function toggleNotificationPanel(event) {
  event.stopPropagation();
  const panel = document.getElementById("notificationPanel");
  const bell = document.getElementById("notificationBell");
  if (!panel || !bell) return;
  closeUserMenu();
  panel.hidden = !panel.hidden;
  bell.setAttribute("aria-expanded", String(!panel.hidden));
  if (!panel.hidden) {
    markNotificationsSeen(false);
  }
}

function toggleUserMenu(event) {
  event.stopPropagation();
  const panel = document.getElementById("userMenuPanel");
  const button = document.getElementById("userMenuButton");
  if (!panel || !button) return;
  closeNotificationPanel();
  panel.hidden = !panel.hidden;
  button.setAttribute("aria-expanded", String(!panel.hidden));
}

function closeNotificationPanelOnOutsideClick(event) {
  const wrap = document.querySelector(".notification-wrap");
  if (!wrap || wrap.contains(event.target)) return;
  closeNotificationPanel();
}

function closeNotificationPanel() {
  const panel = document.getElementById("notificationPanel");
  const bell = document.getElementById("notificationBell");
  if (!panel || panel.hidden) return;
  panel.hidden = true;
  bell?.setAttribute("aria-expanded", "false");
}

function closeUserMenuOnOutsideClick(event) {
  const wrap = document.querySelector(".user-menu-wrap");
  if (!wrap || wrap.contains(event.target)) return;
  closeUserMenu();
}

function closeUserMenu() {
  const panel = document.getElementById("userMenuPanel");
  const button = document.getElementById("userMenuButton");
  if (!panel || panel.hidden) return;
  panel.hidden = true;
  button?.setAttribute("aria-expanded", "false");
}

function markNotificationsSeen(closePanel = true) {
  const latestId = Number(notificationState.alerts[0]?.id || 0);
  notificationState.seenAlertId = Math.max(notificationState.seenAlertId, latestId);
  localStorage.setItem("liveMonitorSeenAlertId", String(notificationState.seenAlertId));
  renderNotificationBell();
  if (closePanel) {
    const panel = document.getElementById("notificationPanel");
    const bell = document.getElementById("notificationBell");
    if (panel) panel.hidden = true;
    bell?.setAttribute("aria-expanded", "false");
  }
}

async function logout() {
  try {
    await LiveMonitorApi.logout();
  } finally {
    window.location.href = "/login.html";
  }
}

async function initDashboard() {
  document.getElementById("activityRefreshBtn")?.addEventListener("click", loadDashboard);
  document.getElementById("activityClearReloadBtn")?.addEventListener("click", clearDashboardActivityMessages);
  document.querySelectorAll(".status-filter-grid .metric").forEach((card) => {
    const activate = () => setDashboardFilter(card.dataset.filter || "all");
    card.addEventListener("click", activate);
    card.addEventListener("keydown", (event) => {
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        activate();
      }
    });
  });
  document.getElementById("serviceSearch")?.addEventListener("input", (event) => {
    dashboardState.query = event.target.value.trim().toLowerCase();
    renderServiceTable();
  });
  initResourceMonitoring();
  await loadDashboard();
  window.setInterval(loadDashboard, 30000);
}

function initResourceMonitoring() {
  if (!document.getElementById("cpuChart")) return;
  loadSystemMetrics();
  resourceMetricState.poller = window.setInterval(loadSystemMetrics, 5000);
}

async function loadSystemMetrics() {
  try {
    const metrics = await LiveMonitorApi.systemMetrics();
    pushMetric("cpu", Number(metrics.cpu || 0));
    pushMetric("memory", Number(metrics.memory || 0));
    pushMetric("disk", Number(metrics.disk || 0));
    pushMetric("network", Number(metrics.network_kbps || 0));
    setText("cpuMetric", `${formatMetric(metrics.cpu)}%`);
    setText("memoryMetric", `${formatMetric(metrics.memory)}%`);
    setText("diskMetric", `${formatMetric(metrics.disk)}%`);
    setText("networkMetric", `${formatMetric(metrics.network_kbps)} KB/s`);
    setText("metricRefreshTime", new Date().toLocaleTimeString("zh-CN", { hour12: false }));
    drawMetricCharts();
  } catch (error) {
    pushMetric("cpu", nextSyntheticMetric("cpu", 42));
    pushMetric("memory", nextSyntheticMetric("memory", 58));
    pushMetric("disk", nextSyntheticMetric("disk", 66));
    pushMetric("network", nextSyntheticMetric("network", 12));
    drawMetricCharts();
  }
}

function pushMetric(key, value) {
  const rows = resourceMetricState.history[key];
  rows.push(Number.isFinite(value) ? value : 0);
  while (rows.length > 24) rows.shift();
}

function nextSyntheticMetric(key, fallback) {
  const rows = resourceMetricState.history[key];
  const last = rows.length ? rows[rows.length - 1] : fallback;
  return Math.max(0, Math.min(key === "network" ? 100 : 100, last + Math.round((Math.random() - 0.45) * 12)));
}

function drawMetricCharts() {
  drawLineChart("cpuChart", resourceMetricState.history.cpu, "#2563eb", 100);
  drawLineChart("memoryChart", resourceMetricState.history.memory, "#16a34a", 100);
  drawLineChart("diskChart", resourceMetricState.history.disk, "#b7791f", 100);
  const networkMax = Math.max(20, ...resourceMetricState.history.network) * 1.2;
  drawLineChart("networkChart", resourceMetricState.history.network, "#0e7490", networkMax);
}

function drawLineChart(canvasId, values, color, maxValue) {
  const canvas = document.getElementById(canvasId);
  if (!canvas) return;
  const ctx = canvas.getContext("2d");
  const width = canvas.width;
  const height = canvas.height;
  const padding = 8;
  ctx.clearRect(0, 0, width, height);
  ctx.strokeStyle = "#e5edf4";
  ctx.lineWidth = 1;
  for (let i = 0; i < 3; i++) {
    const y = padding + ((height - padding * 2) / 2) * i;
    ctx.beginPath();
    ctx.moveTo(padding, y);
    ctx.lineTo(width - padding, y);
    ctx.stroke();
  }
  const rows = values.length ? values : [0];
  const step = (width - padding * 2) / Math.max(1, rows.length - 1);
  ctx.beginPath();
  rows.forEach((value, index) => {
    const x = padding + step * index;
    const y = height - padding - (Math.max(0, Math.min(value, maxValue)) / maxValue) * (height - padding * 2);
    if (index === 0) ctx.moveTo(x, y);
    else ctx.lineTo(x, y);
  });
  ctx.strokeStyle = color;
  ctx.lineWidth = 2.5;
  ctx.lineCap = "round";
  ctx.lineJoin = "round";
  ctx.stroke();
}

function formatMetric(value) {
  const number = Number(value || 0);
  return Number.isFinite(number) ? number.toFixed(number >= 10 ? 0 : 1) : "0";
}

function setText(id, value) {
  const node = document.getElementById(id);
  if (node) node.textContent = value;
}

function clearDashboardActivityMessages() {
  const itemsToDismiss = dashboardState.lastActivityCandidates.length
    ? dashboardState.lastActivityCandidates
    : dashboardState.lastActivityRows;
  itemsToDismiss.forEach((item) => {
    dashboardState.dismissedActivityKeys.add(activityItemKey(item));
  });
  dashboardState.lastActivityCandidates = [];
  dashboardState.lastActivityRows = [];

  const activityPanel = document.getElementById("recentAlerts");
  if (activityPanel) activityPanel.innerHTML = '<p class="empty">暂无动态</p>';
  showToast("实时动态已清空");
}

async function loadDashboard() {
  try {
    const [data, alertGroups] = await Promise.all([
      LiveMonitorApi.dashboard(),
      LiveMonitorApi.alertGroups(false).catch(() => dashboardState.alertGroups),
    ]);
    dashboardState.services = data.services || [];
    dashboardState.recentResults = data.recent_results || [];
    dashboardState.alertGroups = alertGroups || [];
    const summary = data.summary || {};
    const totalCount = document.getElementById("totalCount");
    const upCount = document.getElementById("upCount");
    const downCount = document.getElementById("downCount");
    if (totalCount) totalCount.textContent = summary.total ?? 0;
    if (upCount) upCount.textContent = summary.up ?? 0;
    if (downCount) downCount.textContent = summary.down ?? 0;
    renderDashboardMetrics(summary);
    renderServiceTable();
    renderDashboardActivity(document.getElementById("recentAlerts"), data.recent_alerts || [], dashboardState.recentResults);
    setText("alertMetric", String((data.recent_alerts || []).length));
    const refreshTime = document.getElementById("lastRefreshTime");
    if (refreshTime) refreshTime.textContent = new Date().toLocaleTimeString("zh-CN", { hour12: false });
  } catch (error) {
    document.getElementById("serviceGroupList").innerHTML =
      `<p class="empty">${error.message}</p>`;
  }
}

async function clearRecentAlerts() {
  const confirmed = window.confirm("\u786e\u5b9a\u6e05\u7406\u6700\u8fd1\u544a\u8b66\uff1f\u6b64\u64cd\u4f5c\u4f1a\u540c\u6b65\u5220\u9664\u6570\u636e\u5e93\u4e2d\u7684\u544a\u8b66\u8bb0\u5f55\uff0c\u4e14\u4e0d\u53ef\u6062\u590d\u3002");
  if (!confirmed) return;
  try {
    const result = await LiveMonitorApi.clearAlerts();
    notificationState.alerts = [];
    notificationState.seenAlertId = 0;
    localStorage.setItem("liveMonitorSeenAlertId", "0");
    renderNotificationBell();
    await loadDashboard();
    showToast(`\u544a\u8b66\u5df2\u6e05\u7406\uff0c\u540c\u6b65\u5220\u9664 ${result?.deleted ?? 0} \u6761\u6570\u636e\u5e93\u8bb0\u5f55`);
  } catch (error) {
    showToast(error.message);
  }
}

function renderDashboardMetrics(summary) {
  const total = Number(summary.total || dashboardState.services.length || 0);
  const up = Number(summary.up || 0);
  const down = Number(summary.down || 0);
  const unknown = Math.max(0, total - up - down);
  const unknownCount = document.getElementById("unknownCount");
  if (unknownCount) unknownCount.textContent = unknown;
  renderStatusFilterCards();
}

function setDashboardFilter(filter) {
  dashboardState.filter = filter || "all";
  renderStatusFilterCards();
  renderServiceTable();
}

function renderStatusFilterCards() {
  document.querySelectorAll(".status-filter-grid .metric").forEach((card) => {
    const active = (card.dataset.filter || "all") === dashboardState.filter;
    card.classList.toggle("active", active);
    card.setAttribute("aria-pressed", String(active));
  });
}

function renderServiceTable() {
  const container = document.getElementById("serviceGroupList");
  if (!container) return;

  dashboardState.serviceGroups = buildServiceGroups(dashboardState.services);
  dashboardState.expandedInitialized = true;
  const groups = dashboardState.serviceGroups
    .map((group, index) => ({
      ...group,
      index,
      visibleInstances: group.instances.filter((service) =>
        instanceMatchesDashboardFilter(service, group)
        && instanceMatchesGroupStatus(service, group.key)
      ),
    }))
    .filter((group) => group.visibleInstances.length);

  if (!groups.length) {
    container.innerHTML = '<p class="empty">暂无匹配服务</p>';
    return;
  }

  container.innerHTML = groups.map(renderServiceGroupCard).join("");
  if (window.lucide) window.lucide.createIcons();
}

function buildServiceGroups(services) {
  const groupMap = new Map();
  services.forEach((service) => {
    const clusterName = (service.cluster_name || "").trim();
    const key = clusterName ? `cluster:${clusterName}` : `service:${service.id}`;
    if (!groupMap.has(key)) {
      groupMap.set(key, {
        key,
        name: clusterName || service.service_name || "未命名服务",
        clusterName,
        instances: [],
      });
    }
    groupMap.get(key).instances.push(service);
  });
  return Array.from(groupMap.values()).map((group) => ({
    ...group,
    status: aggregateGroupStatus(group.instances),
    commonAlertGroupId: commonAlertGroupId(group.instances),
  }));
}

function aggregateGroupStatus(instances) {
  const statuses = instances.map((service) => statusLabel(service.last_status));
  if (statuses.includes("DOWN")) return "DOWN";
  if (statuses.includes("UNKNOWN") || !statuses.length) return "UNKNOWN";
  return "UP";
}

function commonAlertGroupId(instances) {
  const ids = instances.map((service) => service.alert_group_id || "").filter((id, index, list) => list.indexOf(id) === index);
  return ids.length === 1 ? ids[0] : "__mixed";
}

function instanceMatchesDashboardFilter(service, group) {
  const status = statusLabel(service.last_status);
  const haystack = [
    group.name,
    service.service_name,
    service.service_type,
    service.cluster_name,
    service.alert_group_name,
    endpointText(service),
  ].join(" ").toLowerCase();
  const statusMatch = dashboardState.filter === "all" || status === dashboardState.filter;
  const queryMatch = !dashboardState.query || haystack.includes(dashboardState.query);
  return statusMatch && queryMatch;
}

function instanceMatchesGroupQuery(service, groupKey) {
  const query = (dashboardState.instanceQueries[groupKey] || "").trim().toLowerCase();
  if (!query) return true;
  return instanceSearchText(service).includes(query);
}

function instanceSearchText(service) {
  const protocol = (service.url || "").match(/^([a-z]+):\/\//i)?.[1] || service.service_type || "";
  return [
    service.service_name,
    service.service_type,
    service.cluster_name,
    service.alert_group_name,
    service.host,
    service.port,
    service.url,
    protocol,
    endpointText(service),
  ].join(" ").toLowerCase();
}

function instanceMatchesGroupStatus(service, groupKey) {
  const filter = dashboardState.groupStatusFilters[groupKey] || "all";
  return filter === "all" || statusLabel(service.last_status) === filter;
}

function setServiceGroupInstanceQuery(index, value) {
  const group = dashboardState.serviceGroups[index];
  if (!group) return;
  dashboardState.instanceQueries[group.key] = value || "";
  dashboardState.instancePages[group.key] = 1;
  renderServiceTable();
  window.setTimeout(() => {
    const input = document.getElementById(`instance-search-${index}`);
    if (!input) return;
    input.focus();
    input.setSelectionRange(input.value.length, input.value.length);
  }, 0);
}

function setServiceGroupStatusFilter(index, filter) {
  const group = dashboardState.serviceGroups[index];
  if (!group) return;
  dashboardState.groupStatusFilters[group.key] = filter || "all";
  dashboardState.instancePages[group.key] = 1;
  renderServiceTable();
}

function setServiceGroupInstancePage(index, page) {
  const group = dashboardState.serviceGroups[index];
  if (!group) return;
  dashboardState.instancePages[group.key] = Math.max(1, Number(page) || 1);
  renderServiceTable();
}

function renderGroupStatButton(label, count, filter) {
  const active = dashboardState.filter === filter;
  return `
    <button class="service-stat ${active ? "active" : ""}" type="button" aria-pressed="${active}" onclick="setDashboardFilter('${filter}')">
      <span>${label}</span>
      <strong>${count}</strong>
    </button>
  `;
}

function renderLegacyServiceGroupCard(group) {
  const total = group.instances.length;
  const up = group.instances.filter((service) => statusLabel(service.last_status) === "UP").length;
  const down = group.instances.filter((service) => statusLabel(service.last_status) === "DOWN").length;
  const unknown = total - up - down;
  const uniqueTypes = Array.from(new Set(group.instances.map((service) => service.service_type)));
  const types = uniqueTypes.map(serviceTypeLabel).join(" / ");
  const addInstanceHref = `/services/new?cluster_name=${encodeURIComponent(group.name)}`;
  const collapsed = !dashboardState.expandedServiceGroupKeys.has(group.key);
  const bodyId = `service-group-body-${group.index}`;
  return `
    <article class="service-group-card service-group-${group.status} ${collapsed ? "collapsed" : ""}">
      <div class="service-group-head">
        <div class="service-group-title">
          <button class="icon-button group-toggle" type="button" title="${collapsed ? "展开分组" : "收起分组"}" aria-expanded="${!collapsed}" aria-controls="${bodyId}" onclick="toggleServiceGroup(${group.index})">
            <i data-lucide="${collapsed ? "chevron-right" : "chevron-down"}"></i>
          </button>
          ${renderStatus(group.status)}
          <div class="service-type-stack">
            ${uniqueTypes.slice(0, 3).map(serviceTypeIconHtml).join("")}
          </div>
          <div>
            <h2>${escapeHtml(group.name)}</h2>
            <p>${escapeHtml(types || "监控服务")} · ${total} 个实例 · 正常 ${up} / 异常 ${down} / 未知 ${unknown}</p>
          </div>
        </div>
        <div class="service-group-actions">
          <label class="inline-select">
            <span>实例告警</span>
            <select onchange="applyServiceGroupAlert(${group.index}, this.value)">
              ${renderDashboardAlertGroupOptions(group.commonAlertGroupId)}
            </select>
          </label>
          <button class="icon-button" type="button" title="检测全部实例" onclick="manualCheckGroup(${group.index})">
            <i data-lucide="refresh-cw"></i>
          </button>
          <a class="primary-button" href="${addInstanceHref}">
            <i data-lucide="plus"></i>
            <span>新增实例</span>
          </a>
        </div>
      </div>
      <div id="${bodyId}" class="instance-list" ${collapsed ? "hidden" : ""}>
        ${group.visibleInstances.map(renderInstanceRow).join("")}
      </div>
    </article>
  `;
}

function renderDashboardAlertGroupOptions(selectedId) {
  const mixed = selectedId === "__mixed";
  return [
    mixed ? '<option value="__mixed" selected>当前为混合配置</option>' : "",
    `<option value="" ${selectedId === "" ? "selected" : ""}>不绑定告警</option>`,
    ...dashboardState.alertGroups.map((group) => `
      <option value="${group.id}" ${Number(selectedId) === Number(group.id) ? "selected" : ""}>
        ${escapeHtml(group.group_name)}${group.enabled ? "" : " / 已停用"}
      </option>
    `),
  ].join("");
}

function renderLegacyInstanceRow(service) {
  return `
    <div class="instance-row">
      <div class="instance-status">${renderStatus(service.last_status)}</div>
      ${serviceTypeIconHtml(service.service_type)}
      <div class="instance-main">
        <strong>${escapeHtml(service.service_name)}</strong>
        <small>${escapeHtml(serviceTypeLabel(service.service_type))} · ${endpointHtml(service)}</small>
      </div>
      <div class="instance-health">
        ${renderLatency(service.last_response_time_ms)}
        ${renderSparkline(service)}
      </div>
      <div class="instance-meta">
        <span>${formatTime(service.last_checked_at)}</span>
        <small>${escapeHtml(service.alert_group_name || "未绑定告警")}</small>
      </div>
      <div class="row-actions compact">
        <a title="详情" href="${serviceDetailHref(service.id)}"><i data-lucide="eye"></i></a>
        <button title="立即检测" onclick="manualCheck(${service.id})"><i data-lucide="refresh-cw"></i></button>
        ${serviceOpenButton(service)}
        <a title="配置" href="${serviceEditHref(service.id)}"><i data-lucide="settings"></i></a>
      </div>
    </div>
  `;
}

function renderDashboardAlertGroupOptions(selectedId) {
  const mixed = selectedId === "__mixed";
  return [
    mixed ? '<option value="__mixed" selected>混合配置</option>' : "",
    `<option value="" ${selectedId === "" ? "selected" : ""}>不绑定告警</option>`,
    ...dashboardState.alertGroups.map((group) => `
      <option value="${group.id}" ${Number(selectedId) === Number(group.id) ? "selected" : ""}>
        ${escapeHtml(group.group_name)}${group.enabled ? "" : " / 已停用"}
      </option>
    `),
  ].join("");
}

function alertBindingSummary(group) {
  if (group.commonAlertGroupId === "__mixed") {
    return {
      icon: "bell-dot",
      className: "mixed",
      text: "混合配置",
      title: "该服务下实例存在不同告警策略",
    };
  }
  if (!group.commonAlertGroupId) {
    return {
      icon: "bell-off",
      className: "unbound",
      text: "未绑定",
      title: "点击绑定告警策略",
    };
  }
  const alertGroup = dashboardState.alertGroups.find((item) => Number(item.id) === Number(group.commonAlertGroupId));
  return {
    icon: "bell",
    className: "bound",
    text: alertGroup?.group_name || "已绑定",
    title: "点击修改告警策略",
  };
}

function renderServiceGroupCard(group) {
  const total = group.instances.length;
  const up = group.instances.filter((service) => statusLabel(service.last_status) === "UP").length;
  const down = group.instances.filter((service) => statusLabel(service.last_status) === "DOWN").length;
  const unknown = total - up - down;
  const uniqueTypes = Array.from(new Set(group.instances.map((service) => service.service_type)));
  const types = uniqueTypes.map(serviceTypeLabel).join(" / ");
  const addInstanceHref = `/services/new?cluster_name=${encodeURIComponent(group.name)}`;
  const primaryService = group.instances[0];
  const collapsed = !dashboardState.expandedServiceGroupKeys.has(group.key);
  const bodyId = `service-group-body-${group.index}`;
  const alertSelectId = `service-group-alert-${group.index}`;

  return `
    <article class="service-group-card service-group-${group.status} ${collapsed ? "collapsed" : ""}">
      <div class="service-group-head">
        <div class="service-group-title">
          <button class="icon-button group-toggle" type="button" title="${collapsed ? "展开实例" : "收起实例"}" aria-expanded="${!collapsed}" aria-controls="${bodyId}" onclick="toggleServiceGroup(${group.index})">
            <i data-lucide="${collapsed ? "chevron-right" : "chevron-down"}"></i>
          </button>
          ${renderStatus(group.status)}
          <div class="service-group-copy">
            <h2>${escapeHtml(group.name)}</h2>
            <p>${escapeHtml(types || "监控服务")} · ${total} 个实例</p>
            <div class="service-stat-row" aria-label="实例状态筛选">
              ${renderGroupStatButton("实例", total, "all")}
              ${renderGroupStatButton("正常", up, "UP")}
              ${renderGroupStatButton("异常", down, "DOWN")}
              ${renderGroupStatButton("未知", unknown, "UNKNOWN")}
            </div>
          </div>
        </div>
        <div class="service-group-actions">
          <label class="inline-select">
            <span>告警策略</span>
            <select id="${alertSelectId}" onchange="applyServiceGroupAlert(${group.index}, this.value)">
              ${renderDashboardAlertGroupOptions(group.commonAlertGroupId)}
            </select>
          </label>
          <button class="icon-button" type="button" title="检测全部实例" onclick="manualCheckGroup(${group.index})">
            <i data-lucide="refresh-cw"></i>
          </button>
          <details class="action-menu">
            <summary class="icon-button" title="服务管理" aria-label="服务管理">
              <i data-lucide="more-vertical"></i>
            </summary>
            <div class="action-menu-panel">
              <a class="icon-only-menu-item" href="${serviceEditHref(primaryService.id)}" title="编辑服务" aria-label="编辑服务"><i data-lucide="pencil"></i></a>
              <a href="${addInstanceHref}"><i data-lucide="plus"></i><span>新增实例</span></a>
              <button type="button" onclick="focusServiceGroupAlert(${group.index})"><i data-lucide="bell"></i><span>绑定告警</span></button>
              <button class="danger icon-only-menu-item" type="button" title="删除服务" aria-label="删除服务" onclick="deleteServiceGroup(${group.index})"><i data-lucide="trash-2"></i></button>
            </div>
          </details>
        </div>
      </div>
      <div id="${bodyId}" class="instance-list" ${collapsed ? "hidden" : ""}>
        ${group.visibleInstances.map(renderInstanceRow).join("")}
        <div class="instance-list-footer">
          <a class="ghost-button" href="${addInstanceHref}">
            <i data-lucide="plus"></i>
            <span>新增实例</span>
          </a>
        </div>
      </div>
    </article>
  `;
}

function renderInstanceRow(service) {
  return `
    <div class="instance-row">
      <div class="instance-status">${renderStatus(service.last_status)}</div>
      ${serviceTypeIconHtml(service.service_type)}
      <div class="instance-main">
        <strong>${escapeHtml(service.service_name)}</strong>
        <small>${escapeHtml(serviceTypeLabel(service.service_type))} · ${endpointHtml(service)}</small>
      </div>
      <div class="instance-health">
        ${renderLatency(service.last_response_time_ms)}
        ${renderSparkline(service)}
      </div>
      <div class="instance-meta">
        <span>${formatTime(service.last_checked_at)}</span>
        <small>${escapeHtml(service.alert_group_name || "未绑定告警")}</small>
      </div>
      <div class="row-actions compact">
        <a title="详情" href="${serviceDetailHref(service.id)}"><i data-lucide="eye"></i></a>
        <button type="button" title="立即检测" onclick="manualCheck(${service.id})"><i data-lucide="refresh-cw"></i></button>
        ${serviceOpenButton(service)}
        <a class="instance-text-action" title="编辑实例" href="${serviceEditHref(service.id)}"><i data-lucide="pencil"></i><span>编辑</span></a>
        <button class="instance-text-action danger" type="button" title="删除实例" onclick="deleteServiceInstance(${service.id})"><i data-lucide="trash-2"></i><span>删除</span></button>
      </div>
    </div>
  `;
}

function renderGroupStatButton(label, count, filter, index, groupKey) {
  const active = (dashboardState.groupStatusFilters[groupKey] || "all") === filter;
  return `
    <button class="service-stat service-stat-${filter} ${active ? "active" : ""}" type="button" aria-pressed="${active}" onclick="setServiceGroupStatusFilter(${index}, '${filter}')">
      <span>${label}</span>
      <strong>${count}</strong>
    </button>
  `;
}

function renderServiceGroupCard(group) {
  const total = group.instances.length;
  const up = group.instances.filter((service) => statusLabel(service.last_status) === "UP").length;
  const down = group.instances.filter((service) => statusLabel(service.last_status) === "DOWN").length;
  const unknown = total - up - down;
  const uniqueTypes = Array.from(new Set(group.instances.map((service) => service.service_type)));
  const primaryType = uniqueTypes[0];
  const types = uniqueTypes.map(serviceTypeLabel).join(" / ");
  const addInstanceHref = `/services/new?cluster_name=${encodeURIComponent(group.name)}`;
  const primaryService = group.instances[0];
  const collapsed = !dashboardState.expandedServiceGroupKeys.has(group.key);
  const bodyId = `service-group-body-${group.index}`;
  const alertSelectId = `service-group-alert-${group.index}`;
  const alertDetailsId = `service-group-alert-menu-${group.index}`;
  const groupQuery = dashboardState.instanceQueries[group.key] || "";
  const alertSummary = alertBindingSummary(group);

  return `
    <article class="service-group-card service-group-${group.status} ${collapsed ? "collapsed" : ""}">
      <div class="service-group-head">
        <div class="service-group-title">
          <button class="icon-button group-toggle" type="button" title="${collapsed ? "展开实例" : "收起实例"}" aria-expanded="${!collapsed}" aria-controls="${bodyId}" onclick="toggleServiceGroup(${group.index})">
            <i data-lucide="${collapsed ? "chevron-down" : "chevron-up"}"></i>
          </button>
          ${renderStatus(group.status)}
          ${serviceTypeIconHtml(primaryType)}
          <div class="service-group-copy">
            <h2>${escapeHtml(group.name)}</h2>
            <p>${escapeHtml(types || "监控服务")} · ${total} 个实例</p>
          </div>
        </div>
        <div class="service-group-actions">
          <label class="inline-select service-alert-select">
            <span>告警策略</span>
            <select id="${alertSelectId}" onchange="applyServiceGroupAlert(${group.index}, this.value)">
              ${renderDashboardAlertGroupOptions(group.commonAlertGroupId)}
            </select>
          </label>
          <button class="icon-button" type="button" title="检测全部实例" aria-label="检测全部实例" onclick="manualCheckGroup(${group.index})">
            <i data-lucide="refresh-cw"></i>
          </button>
          <details class="action-menu">
            <summary class="icon-button" title="服务管理" aria-label="服务管理">
              <i data-lucide="more-vertical"></i>
            </summary>
            <div class="action-menu-panel">
              <a class="icon-only-menu-item" href="${serviceEditHref(primaryService.id)}" title="编辑服务" aria-label="编辑服务"><i data-lucide="pencil"></i></a>
              <a href="${addInstanceHref}"><i data-lucide="plus"></i><span>新增实例</span></a>
              <button type="button" onclick="focusServiceGroupAlert(${group.index})"><i data-lucide="bell"></i><span>绑定告警</span></button>
              <button class="danger icon-only-menu-item" type="button" title="删除服务" aria-label="删除服务" onclick="deleteServiceGroup(${group.index})"><i data-lucide="trash-2"></i></button>
            </div>
          </details>
        </div>
      </div>
      <div class="service-stat-row" aria-label="实例状态筛选">
        ${renderGroupStatButton("实例", total, "all", group.index, group.key)}
        ${renderGroupStatButton("正常", up, "UP", group.index, group.key)}
        ${renderGroupStatButton("异常", down, "DOWN", group.index, group.key)}
        ${renderGroupStatButton("未知", unknown, "UNKNOWN", group.index, group.key)}
      </div>
      <div id="${bodyId}" class="instance-list" ${collapsed ? "hidden" : ""}>
        <div class="instance-list-head">
          <h3>实例列表</h3>
          <div class="instance-list-tools">
            <label class="search instance-search">
              <input id="instance-search-${group.index}" type="search" value="${escapeHtml(groupQuery)}" placeholder="搜索实例名称、IP" oninput="setServiceGroupInstanceQuery(${group.index}, this.value)">
              <i data-lucide="search"></i>
            </label>
          </div>
        </div>
        <div class="instance-table-wrap">
          <div class="instance-table">
            <div class="instance-table-head">
              <span>实例名称</span>
              <span>类型</span>
              <span>状态</span>
              <span>响应时间</span>
              <span>监控图表</span>
              <span>最后上报时间</span>
              <span>告警状态</span>
              <span>操作</span>
            </div>
            ${group.visibleInstances.map(renderInstanceRow).join("") || '<p class="empty">暂无匹配实例</p>'}
          </div>
        </div>
        <div class="instance-list-footer">
          <span>共 ${group.visibleInstances.length} 条</span>
          <div class="pager">
            <button class="icon-button" type="button" disabled aria-label="上一页"><i data-lucide="chevron-left"></i></button>
            <button class="page-number active" type="button">1</button>
            <button class="icon-button" type="button" disabled aria-label="下一页"><i data-lucide="chevron-right"></i></button>
            <button class="page-size" type="button">10 条/页 <i data-lucide="chevron-down"></i></button>
          </div>
        </div>
      </div>
    </article>
  `;
}

function renderInstanceRow(service) {
  const status = statusLabel(service.last_status);
  return `
    <div class="instance-row">
      <div class="instance-main">
        <strong>${escapeHtml(service.service_name)}</strong>
        <small>${escapeHtml(endpointText(service))}</small>
      </div>
      <div class="instance-type-cell">
        ${serviceTypeIconHtml(service.service_type)}
        <span>${escapeHtml(serviceTypeLabel(service.service_type))}</span>
      </div>
      <div class="instance-status-cell">
        <span class="status-dot-text status-${status}"><span class="status-dot"></span>${status}</span>
      </div>
      <div class="instance-latency">${renderLatency(service.last_response_time_ms)}</div>
      <div class="instance-health">${renderSparkline(service)}</div>
      <div class="instance-meta"><span>${formatTime(service.last_checked_at)}</span></div>
      <div class="instance-alert-state">${escapeHtml(service.alert_group_name || "未绑定告警")}</div>
      <div class="row-actions compact">
        <a title="详情" href="${serviceDetailHref(service.id)}" aria-label="详情"><i data-lucide="eye"></i></a>
        <button type="button" title="立即检测" aria-label="立即检测" onclick="manualCheck(${service.id})"><i data-lucide="refresh-cw"></i></button>
        <a title="编辑实例" href="${serviceEditHref(service.id)}" aria-label="编辑实例"><i data-lucide="pencil"></i></a>
        <button class="danger" type="button" title="删除实例" aria-label="删除实例" onclick="deleteServiceInstance(${service.id})"><i data-lucide="trash-2"></i></button>
      </div>
    </div>
  `;
}

function renderServiceGroupCard(group) {
  const total = group.instances.length;
  const up = group.instances.filter((service) => statusLabel(service.last_status) === "UP").length;
  const down = group.instances.filter((service) => statusLabel(service.last_status) === "DOWN").length;
  const unknown = total - up - down;
  const uniqueTypes = Array.from(new Set(group.instances.map((service) => service.service_type)));
  const primaryType = uniqueTypes[0];
  const types = uniqueTypes.map(serviceTypeLabel).join(" / ");
  const addInstanceHref = `/services/new?cluster_name=${encodeURIComponent(group.name)}`;
  const primaryService = group.instances[0];
  const collapsed = !dashboardState.expandedServiceGroupKeys.has(group.key);
  const bodyId = `service-group-body-${group.index}`;
  const alertSelectId = `service-group-alert-${group.index}`;
  const alertDetailsId = `service-group-alert-menu-${group.index}`;
  const groupQuery = dashboardState.instanceQueries[group.key] || "";
  const alertSummary = alertBindingSummary(group);

  return `
    <article class="service-group-card service-group-${group.status} ${collapsed ? "collapsed" : ""}">
      <div class="service-group-head">
        <div class="service-group-title">
          <button class="icon-button group-toggle" type="button" title="${collapsed ? "展开实例" : "收起实例"}" aria-expanded="${!collapsed}" aria-controls="${bodyId}" onclick="toggleServiceGroup(${group.index})">
            <i data-lucide="${collapsed ? "chevron-down" : "chevron-up"}"></i>
          </button>
          ${renderStatus(group.status)}
          ${serviceTypeIconHtml(primaryType)}
          <div class="service-group-copy">
            <h2>${escapeHtml(group.name)}</h2>
            <p>${escapeHtml(types || "监控服务")} · 实例 ${total} · 正常 ${up} · 异常 ${down} · 未知 ${unknown}</p>
          </div>
        </div>
        <div class="service-group-actions">
          <details class="alert-bind-menu" id="${alertDetailsId}">
            <summary class="alert-state-button alert-state-${alertSummary.className}" title="${escapeHtml(alertSummary.title)}">
              <i data-lucide="${alertSummary.icon}"></i>
              <span>${escapeHtml(alertSummary.text)}</span>
            </summary>
            <div class="alert-bind-panel">
              <label>
                <span>告警策略</span>
                <select id="${alertSelectId}" onchange="applyServiceGroupAlert(${group.index}, this.value)">
                  ${renderDashboardAlertGroupOptions(group.commonAlertGroupId)}
                </select>
              </label>
            </div>
          </details>
          <button class="icon-button" type="button" title="检测全部实例" aria-label="检测全部实例" onclick="manualCheckGroup(${group.index})">
            <i data-lucide="refresh-cw"></i>
          </button>
          <details class="action-menu">
            <summary class="icon-button" title="服务管理" aria-label="服务管理">
              <i data-lucide="more-vertical"></i>
            </summary>
            <div class="action-menu-panel">
              <a class="icon-only-menu-item" href="${serviceEditHref(primaryService.id)}" title="编辑服务" aria-label="编辑服务"><i data-lucide="pencil"></i></a>
              <a href="${addInstanceHref}"><i data-lucide="plus"></i><span>新增实例</span></a>
              <button type="button" onclick="focusServiceGroupAlert(${group.index})"><i data-lucide="bell"></i><span>绑定告警</span></button>
              <button class="danger icon-only-menu-item" type="button" title="删除服务" aria-label="删除服务" onclick="deleteServiceGroup(${group.index})"><i data-lucide="trash-2"></i></button>
            </div>
          </details>
        </div>
      </div>
      <div class="service-stat-row" aria-label="实例状态筛选">
        ${renderGroupStatButton("实例", total, "all", group.index, group.key)}
        ${renderGroupStatButton("正常", up, "UP", group.index, group.key)}
        ${renderGroupStatButton("异常", down, "DOWN", group.index, group.key)}
        ${renderGroupStatButton("未知", unknown, "UNKNOWN", group.index, group.key)}
      </div>
      <div id="${bodyId}" class="instance-list" ${collapsed ? "hidden" : ""}>
        <div class="instance-list-head">
          <h3>实例列表</h3>
          <div class="instance-list-tools">
            <label class="search instance-search">
              <input id="instance-search-${group.index}" type="search" value="${escapeHtml(groupQuery)}" placeholder="搜索实例名称、IP" oninput="setServiceGroupInstanceQuery(${group.index}, this.value)">
              <i data-lucide="search"></i>
            </label>
          </div>
        </div>
        <div class="instance-table-wrap">
          <div class="instance-table">
            <div class="instance-table-head">
              <span>实例名称</span>
              <span>类型</span>
              <span>状态</span>
              <span>响应时间</span>
              <span>趋势</span>
              <span>最后上报时间</span>
              <span>告警状态</span>
              <span>操作</span>
            </div>
            ${group.visibleInstances.map(renderInstanceRow).join("") || '<p class="empty">暂无匹配实例</p>'}
          </div>
        </div>
        <div class="instance-list-footer">
          <span>共 ${group.visibleInstances.length} 条</span>
          <div class="pager">
            <button class="icon-button" type="button" disabled aria-label="上一页"><i data-lucide="chevron-left"></i></button>
            <button class="page-number active" type="button">1</button>
            <button class="icon-button" type="button" disabled aria-label="下一页"><i data-lucide="chevron-right"></i></button>
            <button class="page-size" type="button">10 条/页 <i data-lucide="chevron-down"></i></button>
          </div>
        </div>
      </div>
    </article>
  `;
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
  const webSchemeSelect = form.elements.web_scheme;
  initServiceTypePicker(typeSelect);
  const hostOptionsPromise = loadProcessHostOptions(form);
  const pathMatch = window.location.pathname.match(/\/services\/(\d+)\/edit$/);
  const params = new URLSearchParams(window.location.search);
  const editId = pathMatch?.[1] || params.get("id");
  const presetClusterName = params.get("cluster_name");
  if (!editId && presetClusterName && form.elements.cluster_name) {
    form.elements.cluster_name.value = presetClusterName;
  }
  const syncFields = () => {
    const isWeb = isWebUrlServiceType(typeSelect.value);
    const isRedis = typeSelect.value === "redis";
    const isZookeeper = typeSelect.value === "zookeeper";
    const isProcess = typeSelect.value === "process";
    const isDatabase = ["mysql", "oracle", "postgresql", "postgres"].includes(typeSelect.value);
    toggleFieldSet(".web-only", isWeb);
    toggleFieldSet(".host-field", !isWeb);
    toggleFieldSet(".port-field", !isWeb && !isProcess);
    toggleFieldSet(".process-only", isProcess);
    toggleFieldSet(".redis-only", isRedis);
    toggleFieldSet(".zookeeper-only", isZookeeper);
    toggleFieldSet(".database-only", isDatabase);
    const urlInput = form.elements.url;
    const hostInput = form.elements.host;
    const portInput = form.elements.port;
    const databaseNameInput = form.elements.database_name;
    const processNameInput = form.elements.process_name;
    const processKeywordInput = form.elements.process_match_keyword;
    const processCommandInput = form.elements.check_command;
    if (urlInput) urlInput.required = isWeb;
    if (hostInput) hostInput.required = !isWeb && !isProcess;
    if (portInput) portInput.required = !isWeb && !isDatabase && !isProcess;
    if (processNameInput) processNameInput.required = isProcess;
    if (processKeywordInput) processKeywordInput.required = false;
    if (processCommandInput) processCommandInput.required = isProcess;
    if (databaseNameInput) databaseNameInput.required = typeSelect.value === "oracle";
    if (portInput && !portInput.value) {
      portInput.placeholder = {
        port: "例如：8080",
        redis: "6379",
        zookeeper: "2181",
        mysql: "3306",
        oracle: "1521",
        postgresql: "5432",
        process: "已登记 SSH 主机 IP",
      }[typeSelect.value] || "端口";
    }
  };
  typeSelect.addEventListener("change", syncFields);
  webSchemeSelect?.addEventListener("change", () => normalizeWebUrlInput(form));
  syncFields();
  await loadServiceAlertConfigOptions(form);
  await hostOptionsPromise;

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
      typeSelect.dispatchEvent(new Event("change", { bubbles: true }));
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
  const serviceType = ["http", "https"].includes(data.service_type) ? "web" : data.service_type;
  data.enabled = form.elements.enabled.checked;
  data.ignore_ssl_verification = Boolean(form.elements.ignore_ssl_verification?.checked);
  data.redis_cluster_mode = Boolean(form.elements.redis_cluster_mode?.checked);
  data.check_interval = Number(data.check_interval || 60);
  data.check_timeout_seconds = Number(data.check_timeout_seconds || 3);
  data.port = data.port ? Number(data.port) : null;
  if (!data.port && serviceType === "mysql") data.port = 3306;
  if (!data.port && serviceType === "oracle") data.port = 1521;
  if (!data.port && serviceType === "postgresql") data.port = 5432;
  if (serviceType === "process") data.port = null;
  data.expected_status_code = data.expected_status_code ? Number(data.expected_status_code) : null;
  data.zookeeper_expected_nodes = data.zookeeper_expected_nodes ? Number(data.zookeeper_expected_nodes) : null;
  const isDatabase = ["mysql", "oracle", "postgresql", "postgres"].includes(serviceType);
  const isProcess = serviceType === "process";
  data.service_type = serviceType;
  const isWebUrl = isWebUrlServiceType(serviceType);
  data.url = isWebUrl ? normalizeWebUrl(data.url, data.web_scheme) : null;
  data.host = isWebUrl ? null : data.host.trim();
  data.http_method = isWebUrl ? data.http_method || "GET" : "GET";
  data.response_keyword = isWebUrl ? data.response_keyword || null : null;
  data.expected_status_code = isWebUrl ? data.expected_status_code : null;
  data.ignore_ssl_verification = isWebUrl ? data.ignore_ssl_verification : false;
  data.database_name = isDatabase ? data.database_name || null : null;
  data.database_username = isDatabase ? data.database_username || null : null;
  data.database_password = isDatabase ? data.database_password || null : null;
  data.database_query = isDatabase ? data.database_query || null : null;
  data.expected_result = isDatabase ? data.expected_result || null : null;
  data.redis_username = serviceType === "redis" ? data.redis_username || null : null;
  data.redis_password = serviceType === "redis" ? data.redis_password || null : null;
  data.redis_cluster_mode = serviceType === "redis" ? data.redis_cluster_mode : false;
  data.zookeeper_check_mode = serviceType === "zookeeper" ? data.zookeeper_check_mode || "ruok" : "ruok";
  data.zookeeper_check_command = serviceType === "zookeeper" ? data.zookeeper_check_command || "ruok" : "ruok";
  data.zookeeper_expected_nodes = serviceType === "zookeeper" ? data.zookeeper_expected_nodes : null;
  data.host_id = isProcess && data.host_id ? Number(data.host_id) : null;
  data.process_name = isProcess ? data.process_name || data.process_match_keyword || null : null;
  data.process_match_keyword = isProcess ? data.process_match_keyword || data.process_name || null : null;
  data.process_match_mode = isProcess ? data.process_match_mode || "fuzzy" : "fuzzy";
  data.check_command = isProcess ? data.check_command || null : null;
  data.process_check_command = isProcess ? data.check_command : null;
  data.process_min_instances = isProcess ? Number(data.process_min_instances || 1) : null;
  data.alert_group_id = data.alert_group_id ? Number(data.alert_group_id) : null;
  data.alert_config_id = null;
  data.cluster_name = data.cluster_name || null;
  delete data.web_scheme;
  return data;
}

async function loadProcessHostOptions(form) {
  const select = form.elements.host_id;
  if (!select || !window.LiveMonitorApi?.hosts) return;
  try {
    const hosts = await LiveMonitorApi.hosts(false);
    select.innerHTML = [
      '<option value="">按地址匹配已登记主机</option>',
      ...hosts.map((host) => `
        <option value="${host.id}" data-ip="${escapeHtml(host.ip || "")}">
          ${escapeHtml(host.host_name || host.ip || "SSH 主机")} (${escapeHtml(host.ip || "-")})
        </option>
      `),
    ].join("");
    select.addEventListener("change", () => {
      const option = select.selectedOptions[0];
      if (option?.dataset.ip && form.elements.host) {
        form.elements.host.value = option.dataset.ip;
      }
    });
  } catch (error) {
    select.innerHTML = '<option value="">主机列表加载失败，可手动填写地址</option>';
  }
}

function normalizeWebUrl(value, scheme) {
  const url = String(value || "").trim();
  if (!url) return "";
  if (/^https?:\/\//i.test(url)) return url;
  return `${scheme === "http" ? "http" : "https"}://${url}`;
}

function webSchemeFromUrl(value) {
  return /^http:\/\//i.test(value || "") ? "http" : "https";
}

function normalizeWebUrlInput(form) {
  const urlInput = form.elements.url;
  if (!urlInput || !urlInput.value.trim()) return;
  urlInput.value = normalizeWebUrl(urlInput.value, form.elements.web_scheme?.value);
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
    "web_scheme",
    "url",
    "http_method",
    "expected_status_code",
    "response_keyword",
    "host",
    "host_id",
    "port",
    "process_name",
    "process_match_keyword",
    "process_match_mode",
    "check_command",
    "process_min_instances",
    "redis_username",
    "zookeeper_check_mode",
    "zookeeper_check_command",
    "zookeeper_expected_nodes",
    "database_name",
    "database_username",
    "database_query",
    "expected_result",
    "alert_group_id",
  ].forEach((name) => {
    if (form.elements[name]) {
      const defaults = {
        check_timeout_seconds: 3,
        http_method: "GET",
        process_match_mode: "fuzzy",
        check_command: service.check_command || service.process_check_command,
        process_min_instances: 1,
        zookeeper_check_command: "ruok",
      };
      form.elements[name].value = name === "web_scheme"
        ? webSchemeFromUrl(service.url)
        : service[name] ?? defaults[name] ?? "";
    }
  });
  if (form.elements.redis_password) {
    form.elements.redis_password.value = "";
    form.elements.redis_password.placeholder = service.service_type === "redis"
      ? "留空则保持原密码"
      : "Redis AUTH 密码";
  }
  if (form.elements.database_password) {
    form.elements.database_password.value = "";
    form.elements.database_password.placeholder = ["mysql", "oracle", "postgresql", "postgres"].includes(service.service_type)
      ? "留空则保持原密码"
      : "数据库连接密码";
  }
  form.elements.enabled.checked = Boolean(service.enabled);
  if (form.elements.redis_cluster_mode) {
    form.elements.redis_cluster_mode.checked = Boolean(service.redis_cluster_mode);
  }
  if (form.elements.ignore_ssl_verification) {
    form.elements.ignore_ssl_verification.checked = Boolean(service.ignore_ssl_verification);
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

  document.getElementById("detailName").innerHTML =
    `${serviceTypeIconHtml(service.service_type)}<span>${escapeHtml(service.service_name)}</span>`;
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
  const modal = document.getElementById("alertConfigModal");

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
  newAlertBtn?.addEventListener('click', () => {
    switchAlertPage('config');
    openAlertConfigModal(null);
  });

  document.getElementById("reloadAlertSettingsBtn")?.addEventListener("click", loadAlertSettings);
  document.getElementById("deleteAlertGroupBtn")?.addEventListener("click", deleteSelectedAlertGroup);
  document.getElementById("closeAlertConfigModalBtn")?.addEventListener("click", closeAlertConfigModal);
  document.getElementById("alertGroupList")?.addEventListener("click", handleAlertGroupListClick);
  document.getElementById("alertSettingsTable")?.addEventListener("click", handleAlertSettingsTableClick);
  document.getElementById("alertSettingsTable")?.addEventListener("change", handleAlertSettingsTableChange);
  modal?.addEventListener("click", (event) => {
    if (event.target === modal) closeAlertConfigModal();
  });
  document.getElementById("alertChannelTypeSelect")?.addEventListener("change", syncChannelInputs);
  setupRecipientList({
    hiddenId: "alertMobileInput",
    inputId: "alertMobileRecipientInput",
    listId: "alertMobileRecipientList",
    addButtonId: "addMobileRecipientBtn",
    emptyText: "暂无手机号接收人",
  });
  setupRecipientList({
    hiddenId: "alertEmailInput",
    inputId: "alertEmailRecipientInput",
    listId: "alertEmailRecipientList",
    addButtonId: "addEmailRecipientBtn",
    emptyText: "暂无邮箱接收人",
  });
  setupRecipientList({
    hiddenId: "alertEmailCcInput",
    inputId: "alertEmailCcRecipientInput",
    listId: "alertEmailCcRecipientList",
    addButtonId: "addEmailCcRecipientBtn",
    emptyText: "暂无抄送人",
  });

  document.getElementById("alertGroupForm")?.addEventListener("submit", async (event) => {
    event.preventDefault();
    const groupName = document.getElementById("alertGroupNameInput").value.trim();
    if (!groupName) {
      showToast("请输入配置名称");
      return;
    }
    commitPendingRecipientInput({
      hiddenId: "alertMobileInput",
      inputId: "alertMobileRecipientInput",
      listId: "alertMobileRecipientList",
      emptyText: "暂无手机号接收人",
    });
    commitPendingRecipientInput({
      hiddenId: "alertEmailInput",
      inputId: "alertEmailRecipientInput",
      listId: "alertEmailRecipientList",
      emptyText: "暂无邮箱接收人",
    });
    commitPendingRecipientInput({
      hiddenId: "alertEmailCcInput",
      inputId: "alertEmailCcRecipientInput",
      listId: "alertEmailCcRecipientList",
      emptyText: "暂无抄送人",
    });
    const channelPayload = buildAlertChannelPayload();
    if (findAlertConfigByType(channelPayload.channel_type, alertSettingsState.selectedGroupId)) {
      showToast("相同类型的告警配置已存在，请直接修改已有记录");
      return;
    }
    if (channelPayload.channel_type === "email" && !channelPayload.alert_email) {
      showToast("请输入邮箱接收人");
      return;
    }
    if (channelPayload.channel_type === "email" && !channelPayload.smtp_host) {
      showToast("请输入发送邮件服务器域名");
      return;
    }
    if (channelPayload.channel_type === "email" && !channelPayload.smtp_port) {
      showToast("请输入发送邮件服务器端口");
      return;
    }
    if (channelPayload.channel_type === "email" && !channelPayload.smtp_from) {
      showToast("请输入发件人");
      return;
    }
    if (channelPayload.channel_type === "email" && channelPayload.smtp_auth && !channelPayload.smtp_user) {
      showToast("请输入邮件认证账户名");
      return;
    }
    if (channelPayload.channel_type === "sms" && !channelPayload.alert_mobile) {
      showToast("请输入手机号接收人");
      return;
    }

    const groupPayload = {
      group_name: groupName,
      description: document.getElementById("alertGroupDescriptionInput").value.trim() || null,
      enabled: document.getElementById("alertGroupEnabledInput").checked,
      policy_ids: checkedIds("policyChecklist"),
      channel_ids: [],
    };
    if (!groupPayload.policy_ids.length) {
      showToast("请至少选择一个告警策略");
      return;
    }

    try {
      const currentGroup = alertSettingsState.groups.find((item) => Number(item.id) === Number(alertSettingsState.selectedGroupId));
      const currentChannel = groupPrimaryChannel(currentGroup);
      const savedChannel = currentChannel
        ? await LiveMonitorApi.updateAlertChannel(currentChannel.id, channelPayload)
        : await LiveMonitorApi.createAlertChannel(channelPayload);
      groupPayload.channel_ids = [savedChannel.id];

      const saved = alertSettingsState.selectedGroupId
        ? await LiveMonitorApi.updateAlertGroup(alertSettingsState.selectedGroupId, groupPayload)
        : await LiveMonitorApi.createAlertGroup(groupPayload);
      alertSettingsState.selectedGroupId = saved.id;
      await loadAlertSettings();
      closeAlertConfigModal();
      showToast("告警配置已保存");
    } catch (error) {
      showToast(error.message);
    }
  });

  await loadAlertSettings();
}

async function loadAlertSettings() {
  try {
    const [services, groups, policies, channels] = await Promise.all([
      LiveMonitorApi.services(true),
      LiveMonitorApi.alertGroups(true),
      LiveMonitorApi.alertPolicies(true),
      LiveMonitorApi.alertChannels(true),
    ]);
    alertSettingsState.services = services || [];
    alertSettingsState.groups = groups || [];
    alertSettingsState.policies = policies || [];
    alertSettingsState.channels = channels || [];
    renderAlertGroups();
    if (!document.getElementById("alertConfigModal")?.hidden && alertSettingsState.selectedGroupId) {
      renderSelectedAlertGroup();
    }
    renderAlertSettingsTable();
  } catch (error) {
    const table = document.getElementById("alertSettingsTable");
    if (table) table.innerHTML = `<tr><td colspan="3" class="empty">${error.message}</td></tr>`;
    showToast(error.message);
  }
}

function focusServiceGroupAlert(index) {
  const select = document.getElementById(`service-group-alert-${index}`);
  if (!select) return;
  select.focus();
  showToast("请选择要绑定的告警策略");
}

function focusServiceGroupAlert(index) {
  const menu = document.getElementById(`service-group-alert-menu-${index}`);
  if (menu) menu.open = true;
  const select = document.getElementById(`service-group-alert-${index}`);
  if (!select) return;
  select.focus();
  showToast("请选择要绑定的告警策略");
}

async function deleteServiceInstance(id) {
  const service = dashboardState.services.find((item) => Number(item.id) === Number(id));
  const name = service?.service_name || "该实例";
  if (!window.confirm(`确定删除实例「${name}」？该实例的检测记录和告警记录也会被删除。`)) return;
  try {
    await LiveMonitorApi.deleteService(id);
    await loadDashboard();
    showToast("实例已删除");
  } catch (error) {
    showToast(error.message);
  }
}

async function deleteServiceGroup(index) {
  const group = dashboardState.serviceGroups[index];
  if (!group) return;
  const count = group.instances.length;
  if (!window.confirm(`确定删除服务「${group.name}」？这会删除该服务下的 ${count} 个实例。`)) return;
  try {
    await Promise.all(group.instances.map((service) => LiveMonitorApi.deleteService(service.id)));
    dashboardState.expandedServiceGroupKeys.delete(group.key);
    await loadDashboard();
    showToast("服务已删除");
  } catch (error) {
    showToast(error.message);
  }
}

async function manualCheckGroup(index) {
  const group = dashboardState.serviceGroups[index];
  if (!group) return;
  try {
    await Promise.all(group.instances.map((service) => LiveMonitorApi.checkService(service.id)));
    await loadDashboard();
    showToast("服务实例检测完成");
  } catch (error) {
    showToast(error.message);
  }
}

function toggleServiceGroup(index) {
  const group = dashboardState.serviceGroups[index];
  if (!group) return;
  if (dashboardState.expandedServiceGroupKeys.has(group.key)) {
    dashboardState.expandedServiceGroupKeys.delete(group.key);
  } else {
    dashboardState.expandedServiceGroupKeys.add(group.key);
  }
  renderServiceTable();
}

async function applyServiceGroupAlert(index, value) {
  if (value === "__mixed") return;
  const group = dashboardState.serviceGroups[index];
  if (!group) return;
  try {
    const alertGroupId = value ? Number(value) : null;
    await Promise.all(group.instances.map((service) =>
      LiveMonitorApi.updateServiceAlertGroup(service.id, { alert_group_id: alertGroupId })
    ));
    await loadDashboard();
    showToast("实例告警已批量关联");
  } catch (error) {
    showToast(error.message);
    renderServiceTable();
  }
}

function renderAlertGroups() {
  const list = document.getElementById("alertGroupList");
  if (!list) return;
  if (!alertSettingsState.groups.length) {
    list.innerHTML = '<tr><td colspan="7" class="empty">暂无告警配置</td></tr>';
    return;
  }
  list.innerHTML = alertSettingsState.groups.map((group) => `
    <tr class="clickable-row" data-alert-group-id="${group.id}">
      <td>${renderChannelTypePill(groupAlertType(group))}</td>
      <td><strong>${escapeHtml(group.group_name)}</strong></td>
      <td class="wrap-cell">${escapeHtml(channelRecipientText(groupPrimaryChannel(group) || {}))}</td>
      <td class="wrap-cell">${escapeHtml(groupPolicyText(group))}</td>
      <td>${group.service_count || 0} 个服务</td>
      <td>${group.enabled ? '<span class="state-pill enabled">启用</span>' : '<span class="state-pill disabled">停用</span>'}</td>
      <td class="actions-column">
        <div class="row-actions compact">
          <button class="icon-button" type="button" title="编辑" data-alert-edit-id="${group.id}"><i data-lucide="pencil"></i></button>
          <button class="icon-button" type="button" title="${group.service_count ? "有关联服务，不允许删除" : "删除"}" ${group.service_count ? "disabled" : ""} data-alert-delete-id="${group.id}"><i data-lucide="trash-2"></i></button>
        </div>
      </td>
    </tr>
  `).join("");
  if (window.lucide) window.lucide.createIcons();
}

function selectAlertGroup(id) {
  openAlertConfigModal(id);
}

async function openAlertConfigModal(id) {
  alertSettingsState.selectedGroupId = id ? Number(id) : null;
  if (alertSettingsState.selectedGroupId) {
    try {
      const detail = await LiveMonitorApi.alertGroup(alertSettingsState.selectedGroupId, true);
      alertSettingsState.groups = alertSettingsState.groups.map((group) =>
        Number(group.id) === Number(detail.id) ? detail : group
      );
    } catch (error) {
      showToast(error.message);
    }
  }
  renderSelectedAlertGroup();
  const modal = document.getElementById("alertConfigModal");
  if (modal) modal.hidden = false;
  if (window.lucide) window.lucide.createIcons();
  document.getElementById("alertGroupNameInput")?.focus();
}

function closeAlertConfigModal() {
  const modal = document.getElementById("alertConfigModal");
  if (modal) modal.hidden = true;
  alertSettingsState.selectedGroupId = null;
}

function renderSelectedAlertGroup() {
  const group = alertSettingsState.groups.find((item) => Number(item.id) === Number(alertSettingsState.selectedGroupId));
  const channel = groupPrimaryChannel(group);
  const groupNameInput = document.getElementById("alertGroupNameInput");
  const groupDescInput = document.getElementById("alertGroupDescriptionInput");
  const groupEnabledInput = document.getElementById("alertGroupEnabledInput");
  const title = document.getElementById("alertConfigFormTitle");
  if (title) title.textContent = group ? "编辑告警配置" : "新增告警配置";
  if (groupNameInput) groupNameInput.value = group?.group_name || "";
  if (groupDescInput) groupDescInput.value = group?.description || "";
  if (groupEnabledInput) groupEnabledInput.checked = group ? Boolean(group.enabled) : true;
  renderPolicyChecklist(group ? group.policy_ids || [] : alertSettingsState.policies.map((policy) => policy.id));
  fillAlertChannelForm(channel);
  updateAlertTypeOptions(channel?.channel_type || "");
  const deleteButton = document.getElementById("deleteAlertGroupBtn");
  if (deleteButton) {
    deleteButton.disabled = !group || Number(group.service_count || 0) > 0;
    deleteButton.title = group?.service_count ? "有关联服务，不允许删除" : "删除";
  }
}

function groupPrimaryChannel(group) {
  if (!group) return null;
  if (Array.isArray(group.channels) && group.channels.length) return group.channels[0];
  const ids = new Set((group.channel_ids || []).map(Number));
  return alertSettingsState.channels.find((channel) => ids.has(Number(channel.id))) || null;
}

function groupAlertType(group) {
  return groupPrimaryChannel(group)?.channel_type || "";
}

function findAlertConfigByType(type, exceptGroupId) {
  return alertSettingsState.groups.find((group) =>
    groupAlertType(group) === type && Number(group.id) !== Number(exceptGroupId || 0)
  );
}

function updateAlertTypeOptions(currentType) {
  const select = document.getElementById("alertChannelTypeSelect");
  if (!select) return;
  const usedTypes = new Set(
    alertSettingsState.groups
      .filter((group) => Number(group.id) !== Number(alertSettingsState.selectedGroupId || 0))
      .map(groupAlertType)
      .filter(Boolean)
  );
  Array.from(select.options).forEach((option) => {
    option.disabled = usedTypes.has(option.value) && option.value !== currentType;
  });
  if (select.selectedOptions[0]?.disabled) {
    const nextOption = Array.from(select.options).find((option) => !option.disabled);
    if (nextOption) select.value = nextOption.value;
  }
  syncChannelInputs();
}

function renderChannelTypePill(type) {
  const icon = channelIcon(type);
  return `
    <span class="type-pill">
      <i data-lucide="${icon}"></i>
      ${channelTypeLabel(type)}
    </span>
  `;
}

function groupPolicyText(group) {
  const policies = Array.isArray(group?.policies) ? group.policies : [];
  if (policies.length) return policies.map(policyDisplayName).join("、");
  const ids = new Set((group?.policy_ids || []).map(Number));
  const names = alertSettingsState.policies
    .filter((policy) => ids.has(Number(policy.id)))
    .map(policyDisplayName);
  return names.length ? names.join("、") : "未选择策略";
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
        <strong>${escapeHtml(policyDisplayName(policy))}</strong>
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

function policyDisplayName(policy) {
  if (policy.trigger_type === "consecutive_down") return `DOWN 连续 ${policy.trigger_value || 3} 次`;
  if (policy.trigger_type === "latency_gt_ms") return `响应时间 > ${Math.round(Number(policy.trigger_value || 3000) / 1000)} 秒`;
  if (policy.trigger_type === "recovered") return "服务恢复";
  return policy.policy_name || policy.trigger_type || "告警策略";
}

function channelTypeLabel(type) {
  return { email: "邮件", sms: "短信", webhook: "Webhook", dingtalk: "钉钉" }[type] || type || "渠道";
}

function channelIcon(type) {
  return { email: "mail", sms: "message-square", webhook: "webhook", dingtalk: "bot" }[type] || "send";
}

function channelRecipientText(channel) {
  if (channel.channel_type === "email") {
    const recipients = formatRecipientsForText(channel.alert_email || "") || "未填写邮箱";
    const cc = formatRecipientsForText(channel.alert_cc || "");
    return cc ? `${recipients}；抄送：${cc}` : recipients;
  }
  if (channel.channel_type === "sms") return formatMobilesForTextarea(channel.alert_mobile || "").replaceAll("\n", "、") || "未填写手机号";
  return channel.webhook_url || channel.sms_api_url || "未填写 Webhook";
}

async function deleteSelectedAlertGroup() {
  const groupId = alertSettingsState.selectedGroupId;
  if (!groupId) return;
  const group = alertSettingsState.groups.find((item) => Number(item.id) === Number(groupId));
  if (Number(group?.service_count || 0) > 0) {
    showToast("有关联服务的告警配置不允许删除，仅能修改");
    return;
  }
  if (!window.confirm("确定删除该告警配置？")) return;
  try {
    await LiveMonitorApi.deleteAlertGroup(groupId);
    alertSettingsState.selectedGroupId = null;
    await loadAlertSettings();
    closeAlertConfigModal();
    showToast("告警配置已删除");
  } catch (error) {
    showToast(error.message);
  }
}

function deleteAlertConfig(id) {
  alertSettingsState.selectedGroupId = Number(id);
  deleteSelectedAlertGroup();
}

function formatMobilesForTextarea(value) {
  return String(value || "")
    .split(/[,;\s\uFF0C\uFF1B]+/)
    .map((item) => item.trim())
    .filter(Boolean)
    .join("\n");
}

function parseRecipients(value) {
  return String(value || "")
    .split(/[,;\s\uFF0C\uFF1B]+/)
    .map((item) => item.trim())
    .filter(Boolean);
}

function uniqueRecipients(values) {
  const seen = new Set();
  return values.filter((value) => {
    const key = value.toLowerCase();
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function formatRecipientsForText(value) {
  return parseRecipients(value).join("、");
}

function setupRecipientList(options) {
  const input = document.getElementById(options.inputId);
  const addButton = document.getElementById(options.addButtonId);
  const list = document.getElementById(options.listId);
  if (!input || !addButton || !list || input.dataset.enhanced === "true") return;
  input.dataset.enhanced = "true";

  addButton.addEventListener("click", () => addRecipientToList(options));
  input.addEventListener("keydown", (event) => {
    if (event.key !== "Enter") return;
    event.preventDefault();
    addRecipientToList(options);
  });
  list.addEventListener("click", (event) => {
    const button = event.target.closest("[data-recipient]");
    if (!button) return;
    removeRecipientFromList(options, button.dataset.recipient);
  });
  renderRecipientList(options);
}

function setRecipientListValue(hiddenId, listId, inputId, value) {
  const hidden = document.getElementById(hiddenId);
  if (hidden) hidden.value = uniqueRecipients(parseRecipients(value)).join(",");
  renderRecipientList({ hiddenId, listId, inputId });
}

function addRecipientToList(options) {
  const hidden = document.getElementById(options.hiddenId);
  const input = document.getElementById(options.inputId);
  if (!hidden || !input) return;
  const nextValues = parseRecipients(input.value);
  if (!nextValues.length) {
    showToast("请输入接收人");
    return;
  }
  hidden.value = uniqueRecipients([...parseRecipients(hidden.value), ...nextValues]).join(",");
  input.value = "";
  renderRecipientList(options);
}

function commitPendingRecipientInput(options) {
  const input = document.getElementById(options.inputId);
  if (!input || !input.value.trim()) return;
  addRecipientToList(options);
}

function removeRecipientFromList(options, value) {
  const hidden = document.getElementById(options.hiddenId);
  if (!hidden) return;
  hidden.value = parseRecipients(hidden.value)
    .filter((item) => item !== value)
    .join(",");
  renderRecipientList(options);
}

function renderRecipientList(options) {
  const hidden = document.getElementById(options.hiddenId);
  const list = document.getElementById(options.listId);
  if (!hidden || !list) return;
  const recipients = parseRecipients(hidden.value);
  if (!recipients.length) {
    list.innerHTML = `<p class="recipient-empty">${options.emptyText || "暂无接收人"}</p>`;
    return;
  }
  list.innerHTML = recipients.map((recipient) => `
    <span class="recipient-chip">
      <span>${escapeHtml(recipient)}</span>
      <button class="icon-button recipient-remove-button" type="button" title="移除" data-recipient="${escapeHtml(recipient)}">
        <i data-lucide="x"></i>
      </button>
    </span>
  `).join("");
  if (window.lucide) window.lucide.createIcons();
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
  fillAlertChannelForm(channel);
}

function fillAlertChannelForm(channel) {
  const setValueIfExists = (id, value) => {
    const el = document.getElementById(id);
    if (el) el.value = value;
  };
  const setCheckedIfExists = (id, checked) => {
    const el = document.getElementById(id);
    if (el) el.checked = checked;
  };

  setValueIfExists("alertChannelNameInput", channel?.channel_name || "");
  setValueIfExists("alertChannelTypeSelect", channel?.channel_type || "sms");
  setCheckedIfExists("alertChannelEnabledInput", channel ? Boolean(channel.enabled) : true);
  setRecipientListValue("alertEmailInput", "alertEmailRecipientList", "alertEmailRecipientInput", channel?.alert_email || "");
  setRecipientListValue("alertEmailCcInput", "alertEmailCcRecipientList", "alertEmailCcRecipientInput", channel?.alert_cc || "");
  setRecipientListValue("alertMobileInput", "alertMobileRecipientList", "alertMobileRecipientInput", channel?.alert_mobile || "");
  setValueIfExists("smtpHostInput", channel?.smtp_host || "");
  setValueIfExists("smtpPortInput", channel?.smtp_port || "");
  setValueIfExists("smtpUserInput", channel?.smtp_user || "");
  setValueIfExists("smtpPasswordInput", "");
  setValueIfExists("smtpFromInput", channel?.smtp_from || "");
  setCheckedIfExists("smtpAuthInput", channel ? Boolean(channel.smtp_auth ?? channel.smtp_user) : true);
  setCheckedIfExists("smtpTlsInput", Boolean(channel?.smtp_use_tls));
  setCheckedIfExists("smtpSslInput", Boolean(channel?.smtp_use_ssl) || Number(channel?.smtp_port || 0) === 465);
  setValueIfExists("smtpSslTrustInput", channel?.smtp_ssl_trust || "");
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
  const groupName = getValueIfExists("alertGroupNameInput");
  return {
    channel_name: groupName ? `${groupName}通知` : `${channelTypeLabel(channelType)}通知`,
    channel_type: channelType,
    alert_email: channelType === "email" ? getValueIfExists("alertEmailInput") || null : null,
    alert_cc: channelType === "email" ? getValueIfExists("alertEmailCcInput") || null : null,
    alert_mobile: channelType === "sms" ? getValueIfExists("alertMobileInput") || null : null,
    smtp_host: channelType === "email" ? getValueIfExists("smtpHostInput") || null : null,
    smtp_port: channelType === "email" && smtpPort ? Number(smtpPort) : null,
    smtp_user: channelType === "email" ? getValueIfExists("smtpUserInput") || null : null,
    smtp_password: channelType === "email" ? getValueIfExists("smtpPasswordInput") || null : null,
    smtp_from: channelType === "email" ? getValueIfExists("smtpFromInput") || null : null,
    smtp_auth: channelType === "email" ? getCheckedIfExists("smtpAuthInput") : false,
    smtp_use_tls: channelType === "email" ? getCheckedIfExists("smtpTlsInput") : false,
    smtp_use_ssl: channelType === "email" ? getCheckedIfExists("smtpSslInput") : false,
    smtp_ssl_trust: channelType === "email" ? getValueIfExists("smtpSslTrustInput") || null : null,
    sms_api_url: channelType === "sms" ? apiUrl || null : null,
    sms_api_token: channelType === "sms" ? getValueIfExists("smsApiTokenInput") || null : null,
    sms_username: channelType === "sms" ? getValueIfExists("smsUsernameInput") || null : null,
    sms_password: channelType === "sms" ? getValueIfExists("smsPasswordInput") || null : null,
    sms_password_is_md5: channelType === "sms" ? getCheckedIfExists("smsPasswordIsMd5Input") : true,
    sms_password_md5: channelType === "sms" ? getValueIfExists("smsPasswordMd5Input") || null : null,
    sms_rstype: channelType === "sms" ? getValueIfExists("smsRstypeInput") || "text" : "text",
    sms_ext_code: channelType === "sms" ? getValueIfExists("smsExtCodeInput") || null : null,
    webhook_url: ["webhook", "dingtalk"].includes(channelType) ? apiUrl || null : null,
    enabled: getCheckedIfExists("alertGroupEnabledInput"),
  };
}

function renderAlertSettingsTable() {
  const tbody = document.getElementById("alertSettingsTable");
  if (!tbody) return;
  if (!alertSettingsState.services.length) {
    tbody.innerHTML = '<tr><td colspan="3" class="empty">暂无服务</td></tr>';
    return;
  }
  tbody.innerHTML = alertSettingsState.services.map((service) => {
    const checkBusy = isAlertActionBusy(service.id, "check");
    const testBusy = isAlertActionBusy(service.id, "test");
    return `
    <tr>
      <td>${escapeHtml(service.service_name)}</td>
      <td>
        <select class="config-select" data-service-alert-group-id="${service.id}">
          ${renderAlertGroupSelectOptions(service.alert_group_id)}
        </select>
      </td>
      <td class="actions-column">
        <div class="row-actions compact">
          <button class="icon-button" type="button" title="${checkBusy ? "服务探测中" : "服务探测"}" aria-label="服务探测" data-alert-action="check" data-service-id="${service.id}" ${checkBusy ? "disabled" : ""}><i data-lucide="${checkBusy ? "loader-circle" : "zap"}"></i></button>
          <button class="icon-button" type="button" title="${testBusy ? "告警测试中" : "告警测试"}" aria-label="告警测试" data-alert-action="test" data-service-id="${service.id}" ${testBusy ? "disabled" : ""}><i data-lucide="${testBusy ? "loader-circle" : "bell"}"></i></button>
        </div>
      </td>
    </tr>
    ${renderAlertActionResultRow(service)}
  `;
  }).join("");
  if (window.lucide) window.lucide.createIcons();
}

function renderAlertActionResultRow(service) {
  const result = alertSettingsState.testResults[String(service.id)];
  if (!result) return "";
  const stateClass = result.pending ? "testing" : (result.ok ? "ok" : "bad");
  const details = (result.details || [])
    .filter((item) => item !== null && item !== undefined && String(item).trim() !== "")
    .map((item) => `<span>${escapeHtml(item)}</span>`)
    .join("");
  return `
    <tr class="binding-result-row">
      <td colspan="3">
        <div class="test-result binding-test-result ${stateClass}">
          <strong>${escapeHtml(result.title)}</strong>
          <div>${details || "<span>-</span>"}</div>
        </div>
      </td>
    </tr>
  `;
}

function isAlertActionBusy(serviceId, action) {
  return Boolean(alertSettingsState.busyActions[`${serviceId}:${action}`]);
}

function setAlertActionBusy(serviceId, action, busy) {
  const key = `${serviceId}:${action}`;
  if (busy) {
    alertSettingsState.busyActions[key] = true;
  } else {
    delete alertSettingsState.busyActions[key];
  }
}

function setAlertActionResult(serviceId, result) {
  alertSettingsState.testResults[String(serviceId)] = result;
  renderAlertSettingsTable();
}

function normalizeApiValue(item, snakeKey, camelKey) {
  return item?.[snakeKey] ?? item?.[camelKey];
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
      Number(service.id) === Number(updated.id) ? updated : service
    );
    renderAlertSettingsTable();
    showToast("服务告警组已更新");
  } catch (error) {
    showToast(error.message);
    renderAlertSettingsTable();
  }
}

async function testServiceAlert(serviceId) {
  setAlertActionBusy(serviceId, "check", true);
  setAlertActionResult(serviceId, {
    pending: true,
    ok: false,
    title: "服务探测中",
    details: ["正在请求后端探测接口..."],
  });
  try {
    showToast("正在探测服务...");
    const result = await LiveMonitorApi.checkService(serviceId);
    const status = result?.status || "UNKNOWN";
    const responseTime = normalizeApiValue(result, "response_time_ms", "responseTimeMs");
    const checkedAt = normalizeApiValue(result, "checked_at", "checkedAt");
    setAlertActionResult(serviceId, {
      ok: status === "UP",
      title: `服务探测${status === "UP" ? "成功" : "完成"}`,
      details: [
        `状态：${status}`,
        `响应时间：${responseTime ?? "-"} ms`,
        `结果：${result?.message || "-"}`,
        checkedAt ? `时间：${formatTime(checkedAt)}` : "",
      ],
    });
    showToast("服务探测已完成");
    await loadAlertSettings();
  } catch (error) {
    setAlertActionResult(serviceId, {
      ok: false,
      title: "服务探测失败",
      details: [`错误：${error.message}`],
    });
    showToast(`服务探测失败: ${error.message}`);
  } finally {
    setAlertActionBusy(serviceId, "check", false);
    renderAlertSettingsTable();
  }
}

async function sendTestAlert(serviceId) {
  const service = alertSettingsState.services.find((s) => Number(s.id) === Number(serviceId));
  if (!service) {
    showToast("找不到服务");
    return;
  }
  if (!service.alert_group_id) {
    setAlertActionResult(serviceId, {
      ok: false,
      title: "告警测试未发送",
      details: ["该服务未绑定告警组，请先绑定告警配置。"],
    });
    showToast("该服务未绑定告警组，请先绑定");
    return;
  }
  setAlertActionBusy(serviceId, "test", true);
  setAlertActionResult(serviceId, {
    pending: true,
    ok: false,
    title: "告警测试发送中",
    details: ["正在请求后端告警测试接口..."],
  });
  try {
    showToast("正在发送告警测试...");
    const result = await LiveMonitorApi.alertTest(serviceId);
    const record = result?.record || {};
    const alertStatus = normalizeApiValue(record, "alert_status", "alertStatus") || (result?.success ? "success" : "failed");
    const alertType = normalizeApiValue(record, "alert_type", "alertType") || "-";
    const alertContent = normalizeApiValue(record, "alert_content", "alertContent") || result?.error || "-";
    const createdAt = normalizeApiValue(record, "created_at", "createdAt");
    setAlertActionResult(serviceId, {
      ok: Boolean(result?.success),
      title: result?.success ? "告警测试已发送" : "告警测试发送失败",
      details: [
        `发送状态：${alertStatus}`,
        `告警类型：${alertType}`,
        `内容：${alertContent}`,
        createdAt ? `时间：${formatTime(createdAt)}` : "",
      ],
    });
    showToast(result?.success ? "告警测试已发送" : "告警测试发送失败");
  } catch (error) {
    setAlertActionResult(serviceId, {
      ok: false,
      title: "告警测试失败",
      details: [`错误：${error.message}`],
    });
    showToast(`告警测试失败: ${error.message}`);
  } finally {
    setAlertActionBusy(serviceId, "test", false);
    renderAlertSettingsTable();
  }
}

function handleAlertGroupListClick(event) {
  const deleteButton = event.target.closest("[data-alert-delete-id]");
  if (deleteButton) {
    event.stopPropagation();
    deleteAlertConfig(Number(deleteButton.dataset.alertDeleteId));
    return;
  }
  const editButton = event.target.closest("[data-alert-edit-id]");
  if (editButton) {
    event.stopPropagation();
    openAlertConfigModal(Number(editButton.dataset.alertEditId));
    return;
  }
  const row = event.target.closest("[data-alert-group-id]");
  if (row) {
    openAlertConfigModal(Number(row.dataset.alertGroupId));
  }
}

function handleAlertSettingsTableClick(event) {
  const target = event.target instanceof Element ? event.target : event.target?.parentElement;
  const button = target?.closest("[data-alert-action]");
  if (!button) return;
  event.preventDefault();
  const serviceId = Number(button.dataset.serviceId);
  if (button.dataset.alertAction === "check") {
    testServiceAlert(serviceId);
  } else if (button.dataset.alertAction === "test") {
    sendTestAlert(serviceId);
  }
}

function handleAlertSettingsTableChange(event) {
  const select = event.target.closest("[data-service-alert-group-id]");
  if (!select) return;
  bindServiceAlertGroup(Number(select.dataset.serviceAlertGroupId), select.value);
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
  const rows = options.uniqueByService ? uniqueByInstance(alerts) : alerts;
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
  const alertRows = uniqueByInstance(alerts);
  const resultRows = uniqueByInstance(results);
  const serviceRows = uniqueByInstance(dashboardState.services);
  dashboardState.lastActivityCandidates = [...alertRows, ...resultRows, ...serviceRows];

  const visibleAlerts = alertRows.filter((item) => !dashboardState.dismissedActivityKeys.has(activityItemKey(item)));
  if (visibleAlerts.length) {
    dashboardState.lastActivityRows = visibleAlerts;
    renderAlerts(container, visibleAlerts);
    return;
  }
  const uniqueResults = resultRows.filter((item) => !dashboardState.dismissedActivityKeys.has(activityItemKey(item)));
  if (!uniqueResults.length) {
    const services = serviceRows.filter((service) => !dashboardState.dismissedActivityKeys.has(activityItemKey(service)));
    dashboardState.lastActivityRows = services;
    container.innerHTML = services.map((service) => `
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
  dashboardState.lastActivityRows = uniqueResults;
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

function uniqueByInstance(items) {
  const seen = new Set();
  return items.filter((item) => {
    const key = instanceKey(item);
    if (key === null || key === undefined || seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function instanceKey(item) {
  return item?.service_id ?? item?.serviceId ?? item?.id ?? item?.service_name ?? item?.serviceName;
}

function activityItemKey(item) {
  const instance = instanceKey(item);
  const activityId = item?.id ?? item?.created_at ?? item?.createdAt ?? item?.checked_at ?? item?.checkedAt ?? item?.last_checked_at ?? item?.lastCheckedAt ?? "";
  const status = item?.alert_status ?? item?.alertStatus ?? item?.status ?? item?.last_status ?? item?.lastStatus ?? "";
  return `${instance ?? "unknown"}:${activityId}:${status}`;
}

async function initHosts() {
  document.getElementById("reloadHostsBtn")?.addEventListener("click", loadHosts);
  document.getElementById("newHostBtn")?.addEventListener("click", () => openHostModal());
  document.getElementById("hostStatusFilter")?.addEventListener("change", loadHosts);
  document.getElementById("hostForm")?.addEventListener("submit", saveHostForm);
  document.getElementById("hostModal")?.addEventListener("click", (event) => {
    if (event.target.id === "hostModal") closeHostModal();
  });
  document.getElementById("hostDetailModal")?.addEventListener("click", (event) => {
    if (event.target.id === "hostDetailModal") closeHostDetailModal();
  });
  await loadHostAlertGroups();
  await loadHosts();
}

async function loadHostAlertGroups() {
  try {
    hostState.alertGroups = await LiveMonitorApi.alertGroups(false) || [];
    renderHostAlertGroupOptions();
  } catch (error) {
    hostState.alertGroups = [];
  }
}

function renderHostAlertGroupOptions(selectedId = "") {
  const select = document.getElementById("hostAlertGroupSelect");
  if (!select) return;
  select.innerHTML = [
    '<option value="">不绑定告警组</option>',
    ...hostState.alertGroups.map((group) => `<option value="${group.id}">${escapeHtml(group.group_name)}</option>`),
  ].join("");
  select.value = selectedId ? String(selectedId) : "";
}

async function loadHosts() {
  const includeDisabled = document.getElementById("hostStatusFilter")?.value === "all";
  try {
    hostState.hosts = await LiveMonitorApi.hosts(includeDisabled) || [];
    if (hostState.selectedHostId && !hostState.hosts.some((host) => Number(host.id) === Number(hostState.selectedHostId))) {
      hostState.selectedHostId = null;
    }
    renderHostTable();
  } catch (error) {
    const table = document.getElementById("hostTable");
    if (table) table.innerHTML = `<tr><td colspan="8" class="empty">${escapeHtml(error.message)}</td></tr>`;
  }
}

function renderHostTable() {
  const table = document.getElementById("hostTable");
  if (!table) return;
  if (!hostState.hosts.length) {
    table.innerHTML = '<tr><td colspan="8" class="empty">暂无主机</td></tr>';
    return;
  }
  table.innerHTML = hostState.hosts.map((host) => `
    <tr>
      <td>
        <strong>${escapeHtml(host.host_name || "-")}</strong>
        <small class="muted">${escapeHtml(host.ip || "-")}</small>
      </td>
      <td>${escapeHtml(host.cluster_name || "服务器主机")}</td>
      <td>${escapeHtml(host.ssh_user || "-")}@${escapeHtml(host.ip || "-")}:${host.ssh_port || 22}</td>
      <td>${formatThreshold(host.cpu_threshold_percent)}</td>
      <td>${formatThreshold(host.disk_threshold_percent)}</td>
      <td>${host.check_interval || 60} 秒</td>
      <td><span class="state-pill ${host.enabled ? "enabled" : "disabled"}">${host.enabled ? "启用" : "停用"}</span></td>
      <td onclick="event.stopPropagation()">
        <div class="row-actions compact">
          <button class="icon-button" type="button" title="编辑" onclick="openHostModal(${host.id})"><i data-lucide="pencil"></i></button>
          <button class="icon-button" type="button" title="拉取指标" onclick="selectHost(${host.id}); loadSelectedHostMetrics()"><i data-lucide="activity"></i></button>
          <button class="icon-button danger-icon" type="button" title="删除" onclick="deleteHost(${host.id})"><i data-lucide="trash-2"></i></button>
        </div>
      </td>
    </tr>
  `).join("");
  if (window.lucide) window.lucide.createIcons();
}

function formatThreshold(value) {
  return value === null || value === undefined ? "-" : `${Number(value).toFixed(Number(value) % 1 ? 1 : 0)}%`;
}

async function selectHost(id) {
  hostState.selectedHostId = id ? Number(id) : null;
  hostState.processStatus = {};
  resetHostMetricHistory();
  renderHostTable();
  renderSelectedHostSummary();
  renderHostMetrics(null);
  if (hostState.selectedHostId) {
    await loadHostProcesses(hostState.selectedHostId);
  } else {
    hostState.processes = [];
    renderHostProcesses();
  }
}

function selectedHost() {
  return hostState.hosts.find((host) => Number(host.id) === Number(hostState.selectedHostId));
}

function renderSelectedHostSummary() {
  const host = selectedHost();
  setText("selectedHostTitle", host ? `${host.host_name || host.ip} 指标` : "主机指标");
  setText("selectedHostMeta", host ? `${host.ip || "-"} / ${host.cluster_name || "服务器主机"} / CPU ${formatThreshold(host.cpu_threshold_percent)} / 磁盘 ${formatThreshold(host.disk_threshold_percent)}` : "选择表格中的主机后查看实时指标。");
}

function openHostModal(id = null) {
  const host = id ? hostState.hosts.find((item) => Number(item.id) === Number(id)) : null;
  fillHostForm(host);
  renderHostAlertGroupOptions(host?.alert_group_id || "");
  const modal = document.getElementById("hostModal");
  if (modal) modal.hidden = false;
  if (window.lucide) window.lucide.createIcons();
}

function closeHostModal() {
  const modal = document.getElementById("hostModal");
  if (modal) modal.hidden = true;
}

function fillHostForm(host) {
  const form = document.getElementById("hostForm");
  if (!form) return;
  form.reset();
  form.elements.id.value = host?.id || "";
  form.elements.host_name.value = host?.host_name || "";
  form.elements.ip.value = host?.ip || "";
  form.elements.cluster_name.value = host?.cluster_name || "服务器主机";
  form.elements.ssh_port.value = host?.ssh_port || 22;
  form.elements.ssh_user.value = host?.ssh_user || "";
  form.elements.ssh_password.value = "";
  form.elements.private_key.value = "";
  form.elements.cpu_threshold_percent.value = host?.cpu_threshold_percent ?? 85;
  form.elements.disk_threshold_percent.value = host?.disk_threshold_percent ?? 85;
  form.elements.check_interval.value = host?.check_interval || 60;
  form.elements.alert_group_id.value = host?.alert_group_id || "";
  form.elements.enabled.checked = host ? Boolean(host.enabled) : true;
  setText("hostModalTitle", host ? "编辑主机" : "添加主机");
}

function buildHostPayload(form) {
  return {
    host_name: form.elements.host_name.value.trim(),
    ip: form.elements.ip.value.trim(),
    cluster_name: form.elements.cluster_name.value.trim() || "服务器主机",
    ssh_port: Number(form.elements.ssh_port.value || 22),
    ssh_user: form.elements.ssh_user.value.trim() || null,
    ssh_password: form.elements.ssh_password.value || null,
    private_key: form.elements.private_key.value || null,
    cpu_threshold_percent: Number(form.elements.cpu_threshold_percent.value || 85),
    disk_threshold_percent: Number(form.elements.disk_threshold_percent.value || 85),
    check_interval: Number(form.elements.check_interval.value || 60),
    alert_group_id: form.elements.alert_group_id.value ? Number(form.elements.alert_group_id.value) : null,
    enabled: form.elements.enabled.checked,
  };
}

async function saveHostForm(event) {
  event.preventDefault();
  const form = event.currentTarget;
  if (!form.reportValidity()) return;
  const id = form.elements.id.value;
  try {
    const saved = id
      ? await LiveMonitorApi.updateHost(id, buildHostPayload(form))
      : await LiveMonitorApi.createHost(buildHostPayload(form));
    hostState.selectedHostId = saved.id;
    closeHostModal();
    showToast(id ? "主机已更新" : "主机已添加");
    await loadHosts();
  } catch (error) {
    showToast(error.message);
  }
}

async function deleteHost(id) {
  if (!window.confirm("删除主机后，对应监控服务和进程检测配置也会删除。确定删除？")) return;
  try {
    await LiveMonitorApi.deleteHost(id);
    if (Number(hostState.selectedHostId) === Number(id)) hostState.selectedHostId = null;
    showToast("主机已删除");
    await loadHosts();
  } catch (error) {
    showToast(error.message);
  }
}

async function loadSelectedHostMetrics() {
  if (!hostState.selectedHostId) {
    showToast("请先选择主机");
    return;
  }
  renderHostMetricLoading();
  try {
    hostState.metrics = await LiveMonitorApi.hostMetrics(hostState.selectedHostId);
    renderHostMetrics(hostState.metrics);
  } catch (error) {
    showToast(error.message);
    renderHostMetrics({ cpu: error.message, memory: "-", disk: "-" });
  }
}

function renderHostMetrics(metrics) {
  if (!metrics) {
    setHostMetricText("hostCpuValue", "-");
    setHostMetricText("hostLoadValue", "Load -");
    setHostMetricText("hostMemoryValue", "-");
    setHostMetricText("hostDiskValue", "-");
    drawHostMetricCharts();
    return;
  }
  const cpu = numericMetric(metrics.cpu_usage_percent);
  const load = numericMetric(metrics.load_average);
  const memory = numericMetric(metrics.memory_used_percent);
  const disk = numericMetric(metrics.disk_used_percent);
  pushHostMetric("cpu", cpu);
  pushHostMetric("load", load);
  pushHostMetric("memory", memory);
  pushHostMetric("disk", disk);
  setHostMetricText("hostCpuValue", cpu === null ? "-" : `${cpu.toFixed(1)}%`);
  setHostMetricText("hostLoadValue", load === null ? "Load -" : `Load ${load.toFixed(2)}`);
  setHostMetricText("hostMemoryValue", memory === null ? "-" : `${memory.toFixed(1)}%`);
  setHostMetricText("hostDiskValue", disk === null ? "-" : `${disk.toFixed(1)}%`);
  drawHostMetricCharts();
}

function renderHostMetricLoading() {
  setHostMetricText("hostCpuValue", "...");
  setHostMetricText("hostLoadValue", "Load ...");
  setHostMetricText("hostMemoryValue", "...");
  setHostMetricText("hostDiskValue", "...");
}

function resetHostMetricHistory() {
  hostState.metricHistory = { cpu: [], load: [], memory: [], disk: [] };
}

function numericMetric(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function pushHostMetric(key, value) {
  const rows = hostState.metricHistory[key];
  if (value === null) return;
  rows.push(value);
  while (rows.length > 24) rows.shift();
}

function setHostMetricText(id, value) {
  const node = document.getElementById(id);
  if (node) node.textContent = value;
}

function drawHostMetricCharts() {
  drawLineChart("hostCpuChart", hostState.metricHistory.cpu, "#2563eb", 100);
  drawLineChart("hostMemoryChart", hostState.metricHistory.memory, "#16a34a", 100);
  drawLineChart("hostDiskChart", hostState.metricHistory.disk, "#b7791f", 100);
}

async function loadHostProcesses(hostId) {
  try {
    hostState.processes = await LiveMonitorApi.hostProcesses(hostId) || [];
    renderHostProcesses();
  } catch (error) {
    const table = document.getElementById("hostProcessTable");
    if (table) table.innerHTML = `<tr><td colspan="5" class="empty">${escapeHtml(error.message)}</td></tr>`;
  }
}

function openProcessModal(id = null) {
  if (!hostState.selectedHostId) {
    showToast("请先选择主机");
    return;
  }
  const process = id ? hostState.processes.find((item) => Number(item.id) === Number(id)) : null;
  const form = document.getElementById("hostProcessForm");
  if (form) {
    form.reset();
    form.elements.id.value = process?.id || "";
    form.elements.process_name.value = process?.process_name || "";
    form.elements.match_keyword.value = process?.match_keyword || "";
    form.elements.check_command.value = process?.check_command || "";
    form.elements.enabled.checked = process ? Boolean(process.enabled) : true;
  }
  setText("processModalTitle", process ? "编辑进程检测命令" : "添加进程检测命令");
  const modal = document.getElementById("processModal");
  if (modal) modal.hidden = false;
  if (window.lucide) window.lucide.createIcons();
}

function closeProcessModal() {
  const modal = document.getElementById("processModal");
  if (modal) modal.hidden = true;
}

async function saveHostProcessForm(event) {
  event.preventDefault();
  if (!hostState.selectedHostId) {
    showToast("请先选择主机");
    return;
  }
  const form = event.currentTarget;
  if (!form.reportValidity()) return;
  const id = form.elements.id.value;
  const payload = {
    process_name: form.elements.process_name.value.trim(),
    match_keyword: form.elements.match_keyword.value.trim() || form.elements.process_name.value.trim(),
    check_command: form.elements.check_command.value.trim(),
    enabled: form.elements.enabled.checked,
  };
  try {
    if (id) {
      await LiveMonitorApi.updateHostProcess(id, payload);
    } else {
      await LiveMonitorApi.createHostProcess(hostState.selectedHostId, payload);
    }
    closeProcessModal();
    await loadHostProcesses(hostState.selectedHostId);
    showToast(id ? "进程检测命令已更新" : "进程检测命令已添加");
  } catch (error) {
    showToast(error.message);
  }
}

async function deleteHostProcess(processId) {
  try {
    await LiveMonitorApi.deleteHostProcess(processId);
    await loadHostProcesses(hostState.selectedHostId);
    showToast("进程检测已删除");
  } catch (error) {
    showToast(error.message);
  }
}

async function loadSelectedProcessStatus() {
  if (!hostState.selectedHostId) {
    showToast("请先选择主机");
    return;
  }
  try {
    hostState.processStatus = await LiveMonitorApi.hostProcessStatus(hostState.selectedHostId) || {};
    renderHostProcesses();
  } catch (error) {
    showToast(error.message);
  }
}

function renderHostProcesses() {
  const tbody = document.getElementById("hostProcessTable");
  if (!tbody) return;
  if (!hostState.selectedHostId) {
    tbody.innerHTML = '<tr><td colspan="5" class="empty">请先选择主机</td></tr>';
    return;
  }
  if (!hostState.processes.length) {
    tbody.innerHTML = '<tr><td colspan="5" class="empty">暂无进程检测命令</td></tr>';
    return;
  }
  tbody.innerHTML = hostState.processes.map((process) => {
    const status = hostState.processStatus[String(process.id)];
    const running = status?.running;
    const label = running === true ? "UP" : running === false ? "DOWN" : "UNKNOWN";
    return `
      <tr>
        <td>${renderStatus(label)}</td>
        <td>
          <strong>${escapeHtml(process.process_name || "-")}</strong>
          <small class="muted">${escapeHtml(process.match_keyword || "-")}</small>
        </td>
        <td class="wrap-cell"><code>${escapeHtml(process.check_command || "-")}</code></td>
        <td class="wrap-cell">${escapeHtml(status?.output || "-")}</td>
        <td>
          <div class="row-actions compact">
            <button class="icon-button" type="button" title="编辑" onclick="openProcessModal(${process.id})"><i data-lucide="pencil"></i></button>
            <button class="icon-button danger-icon" type="button" title="删除" onclick="deleteHostProcess(${process.id})"><i data-lucide="trash-2"></i></button>
          </div>
        </td>
      </tr>
    `;
  }).join("");
  if (window.lucide) window.lucide.createIcons();
}

async function initHosts() {
  document.getElementById("reloadHostsBtn")?.addEventListener("click", loadHosts);
  document.getElementById("newHostBtn")?.addEventListener("click", () => openHostModal());
  document.getElementById("hostStatusFilter")?.addEventListener("change", loadHosts);
  document.getElementById("hostForm")?.addEventListener("submit", saveHostForm);
  document.getElementById("hostModal")?.addEventListener("click", (event) => {
    if (event.target.id === "hostModal") closeHostModal();
  });
  document.getElementById("hostDetailModal")?.addEventListener("click", (event) => {
    if (event.target.id === "hostDetailModal") closeHostDetailModal();
  });
  await loadHostAlertGroups();
  await loadHosts();
}

async function loadHosts() {
  const includeDisabled = document.getElementById("hostStatusFilter")?.value === "all";
  try {
    hostState.hosts = await LiveMonitorApi.hosts(includeDisabled) || [];
    if (hostState.selectedHostId && !hostState.hosts.some((host) => Number(host.id) === Number(hostState.selectedHostId))) {
      closeHostDetailModal();
      hostState.selectedHostId = null;
    }
    renderHostTable();
  } catch (error) {
    const table = document.getElementById("hostTable");
    if (table) table.innerHTML = `<tr><td colspan="8" class="empty">${escapeHtml(error.message)}</td></tr>`;
  }
}

function renderHostTable() {
  const table = document.getElementById("hostTable");
  if (!table) return;
  if (!hostState.hosts.length) {
    table.innerHTML = '<tr><td colspan="8" class="empty">暂无主机</td></tr>';
    return;
  }
  table.innerHTML = hostState.hosts.map((host) => `
    <tr>
      <td>
        <strong>${escapeHtml(host.host_name || "-")}</strong>
        <small class="muted">${escapeHtml(host.ip || "-")}</small>
      </td>
      <td>${escapeHtml(host.cluster_name || "服务器主机")}</td>
      <td>${escapeHtml(host.ssh_user || "-")}@${escapeHtml(host.ip || "-")}:${host.ssh_port || 22}</td>
      <td>${formatThreshold(host.cpu_threshold_percent)}</td>
      <td>${formatThreshold(host.disk_threshold_percent)}</td>
      <td>${host.check_interval || 60} 秒</td>
      <td><span class="state-pill ${host.enabled ? "enabled" : "disabled"}">${host.enabled ? "启用" : "停用"}</span></td>
      <td>
        <div class="row-actions compact">
          <button class="icon-button" type="button" title="查看详情" onclick="openHostDetailModal(${host.id})"><i data-lucide="line-chart"></i></button>
          <button class="icon-button" type="button" title="编辑" onclick="openHostModal(${host.id})"><i data-lucide="pencil"></i></button>
          <button class="icon-button danger-icon" type="button" title="删除" onclick="deleteHost(${host.id})"><i data-lucide="trash-2"></i></button>
        </div>
      </td>
    </tr>
  `).join("");
  if (window.lucide) window.lucide.createIcons();
}

async function selectHost(id) {
  hostState.selectedHostId = id ? Number(id) : null;
  hostState.metrics = null;
  resetHostMetricHistory();
  renderSelectedHostSummary();
  renderHostMetrics(null);
}

function renderSelectedHostSummary() {
  const host = selectedHost();
  setText("selectedHostTitle", host ? `${host.host_name || host.ip} 指标` : "主机指标");
  setText(
    "selectedHostMeta",
    host
      ? `${host.ip || "-"} / ${host.cluster_name || "服务器主机"} / CPU ${formatThreshold(host.cpu_threshold_percent)} / 磁盘 ${formatThreshold(host.disk_threshold_percent)} / ${host.check_interval || 60} 秒自动刷新`
      : "打开详情后按主机检测间隔自动刷新。"
  );
}

async function openHostDetailModal(id) {
  await selectHost(id);
  const modal = document.getElementById("hostDetailModal");
  if (modal) modal.hidden = false;
  if (window.lucide) window.lucide.createIcons();
  await loadSelectedHostMetrics();
  startHostMetricAutoRefresh();
}

function closeHostDetailModal() {
  stopHostMetricAutoRefresh();
  const modal = document.getElementById("hostDetailModal");
  if (modal) modal.hidden = true;
}

function startHostMetricAutoRefresh() {
  stopHostMetricAutoRefresh();
  const host = selectedHost();
  if (!host) return;
  const seconds = Math.max(5, Number(host.check_interval || 60));
  hostState.metricTimer = window.setInterval(loadSelectedHostMetrics, seconds * 1000);
}

function stopHostMetricAutoRefresh() {
  if (!hostState.metricTimer) return;
  window.clearInterval(hostState.metricTimer);
  hostState.metricTimer = null;
}

async function initHosts() {
  document.getElementById("reloadHostsBtn")?.addEventListener("click", loadHosts);
  document.getElementById("newHostBtn")?.addEventListener("click", () => openHostModal());
  document.getElementById("hostStatusFilter")?.addEventListener("change", loadHosts);
  document.getElementById("hostForm")?.addEventListener("submit", saveHostForm);
  document.getElementById("hostModal")?.addEventListener("click", (event) => {
    if (event.target.id === "hostModal") closeHostModal();
  });
  document.getElementById("hostDetailModal")?.addEventListener("click", (event) => {
    if (event.target.id === "hostDetailModal") closeHostDetailModal();
  });
  await loadHostAlertGroups();
  await loadHosts();
  startHostListMetricScheduler();
}

async function loadHosts() {
  const includeDisabled = document.getElementById("hostStatusFilter")?.value === "all";
  try {
    hostState.hosts = await LiveMonitorApi.hosts(includeDisabled) || [];
    const ids = new Set(hostState.hosts.map((host) => String(host.id)));
    if (hostState.selectedHostId && !ids.has(String(hostState.selectedHostId))) {
      closeHostDetailModal();
      hostState.selectedHostId = null;
    }
    renderHostTable();
  } catch (error) {
    const table = document.getElementById("hostTable");
    if (table) table.innerHTML = `<tr><td colspan="11" class="empty">${escapeHtml(error.message)}</td></tr>`;
  }
}

function renderHostTable() {
  const table = document.getElementById("hostTable");
  if (!table) return;
  if (!hostState.hosts.length) {
    table.innerHTML = '<tr><td colspan="11" class="empty">暂无主机</td></tr>';
    return;
  }
  table.innerHTML = hostState.hosts.map((host) => `
    <tr>
      <td>
        <strong>${escapeHtml(host.host_name || "-")}</strong>
        <small class="muted">${escapeHtml(host.ip || "-")}</small>
      </td>
      <td>${escapeHtml(host.cluster_name || "服务器主机")}</td>
      <td>${escapeHtml(host.ssh_user || "-")}@${escapeHtml(host.ip || "-")}:${host.ssh_port || 22}</td>
      <td>${renderHostMetricTag(host.cpu_usage_percent, host.cpu_threshold_percent)}</td>
      <td>${renderHostMetricTag(host.memory_used_percent)}</td>
      <td>${renderHostMetricTag(host.disk_used_percent, host.disk_threshold_percent)}</td>
      <td>${formatThreshold(host.cpu_threshold_percent)}</td>
      <td>${formatThreshold(host.disk_threshold_percent)}</td>
      <td>${host.check_interval || 60} 秒</td>
      <td>${renderHostState(host)}</td>
      <td>
        <div class="row-actions compact">
          <button class="icon-button" type="button" title="查看详情" onclick="openHostDetailModal(${host.id})"><i data-lucide="line-chart"></i></button>
          <button class="icon-button" type="button" title="编辑" onclick="openHostModal(${host.id})"><i data-lucide="pencil"></i></button>
          <button class="icon-button danger-icon" type="button" title="删除" onclick="deleteHost(${host.id})"><i data-lucide="trash-2"></i></button>
        </div>
      </td>
    </tr>
  `).join("");
  if (window.lucide) window.lucide.createIcons();
}

function renderHostMetricTag(rawValue, threshold = null) {
  const value = numericMetric(rawValue);
  const label = value === null ? "-" : `${value.toFixed(1)}%`;
  const warn = threshold !== null && threshold !== undefined && value !== null && value >= Number(threshold);
  const className = warn ? "bad" : value === null ? "loading" : "ok";
  return `<span class="host-metric-tag ${className}">${label}</span>`;
}

function startHostListMetricScheduler() {
  stopHostListMetricScheduler();
  const seconds = hostListRefreshSeconds();
  hostState.listMetricTimer = window.setTimeout(async () => {
    await loadHosts();
    startHostListMetricScheduler();
  }, seconds * 1000);
}

function stopHostListMetricScheduler() {
  if (!hostState.listMetricTimer) return;
  window.clearInterval(hostState.listMetricTimer);
  hostState.listMetricTimer = null;
}

function hostListRefreshSeconds() {
  const intervals = hostState.hosts
    .filter((host) => Boolean(host.enabled))
    .map((host) => Number(host.check_interval || 60))
    .filter((value) => Number.isFinite(value) && value > 0);
  return Math.max(5, Math.min(...intervals, 60));
}

function renderHostState(host) {
  const state = hostStateView(host);
  return `<span class="state-pill ${state.className}">${state.label}</span>`;
}

function hostStateView(host) {
  if (!host.enabled) {
    return { className: "disabled", label: "停用" };
  }
  const hasMetric = ["cpu_usage_percent", "memory_used_percent", "disk_used_percent"]
    .some((key) => numericMetric(host[key]) !== null);
  if (!hasMetric) {
    return { className: "warning", label: "采集中" };
  }
  const cpu = numericMetric(host.cpu_usage_percent);
  const disk = numericMetric(host.disk_used_percent);
  const cpuThreshold = numericMetric(host.cpu_threshold_percent);
  const diskThreshold = numericMetric(host.disk_threshold_percent);
  if ((cpu !== null && cpuThreshold !== null && cpu >= cpuThreshold)
    || (disk !== null && diskThreshold !== null && disk >= diskThreshold)) {
    return { className: "danger", label: "异常" };
  }
  return { className: "enabled", label: "正常" };
}

async function selectHost(id) {
  hostState.selectedHostId = id ? Number(id) : null;
  hostState.metrics = null;
  resetHostMetricHistory();
  renderSelectedHostSummary();
  renderHostMetrics(null);
}

function renderSelectedHostSummary() {
  const host = selectedHost();
  setText("selectedHostTitle", host ? `${host.host_name || host.ip} 指标` : "主机指标");
  setText(
    "selectedHostMeta",
    host
      ? `${host.ip || "-"} / ${host.cluster_name || "服务器主机"} / CPU ${formatThreshold(host.cpu_threshold_percent)} / 磁盘 ${formatThreshold(host.disk_threshold_percent)} / ${host.check_interval || 60} 秒自动刷新`
      : "打开详情后按主机检测间隔自动刷新。"
  );
}

async function openHostDetailModal(id) {
  await selectHost(id);
  const modal = document.getElementById("hostDetailModal");
  if (modal) modal.hidden = false;
  if (window.lucide) window.lucide.createIcons();
  await loadSelectedHostMetrics();
  startHostMetricAutoRefresh();
}

function closeHostDetailModal() {
  stopHostMetricAutoRefresh();
  const modal = document.getElementById("hostDetailModal");
  if (modal) modal.hidden = true;
}

function startHostMetricAutoRefresh() {
  stopHostMetricAutoRefresh();
  const host = selectedHost();
  if (!host) return;
  const seconds = Math.max(5, Number(host.check_interval || 60));
  hostState.metricTimer = window.setInterval(loadSelectedHostMetrics, seconds * 1000);
}

function stopHostMetricAutoRefresh() {
  if (!hostState.metricTimer) return;
  window.clearInterval(hostState.metricTimer);
  hostState.metricTimer = null;
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function serviceTypeLabel(type) {
  return {
    web: "\u0057\u0065\u0062 \u5e94\u7528 (HTTP/HTTPS)",
    http: "HTTP \u5e94\u7528",
    https: "HTTPS \u5e94\u7528",
    nginx: "Nginx",
    host: "\u670d\u52a1\u5668\u4e3b\u673a",
    process: "\u8fdb\u7a0b\u670d\u52a1",
    port: "\u7aef\u53e3\u670d\u52a1",
    tcp: "TCP \u7aef\u53e3",
    redis: "Redis",
    zookeeper: "ZooKeeper",
    mysql: "MySQL",
    oracle: "Oracle",
    postgresql: "PostgreSQL",
    postgres: "PostgreSQL",
  }[type] || type || "\u81ea\u5b9a\u4e49\u670d\u52a1";
}

function renderDashboardMetrics(summary) {
  const total = Number(summary.total || dashboardState.services.length || 0);
  const up = Number(summary.up || 0);
  const down = Number(summary.down || 0);
  const unknown = Math.max(0, total - up - down);
  setText("totalCount", String(total));
  setText("instanceCount", String(dashboardState.services.length || total));
  setText("upCount", String(up));
  setText("downCount", String(down));
  setText("unknownCount", String(unknown));
  renderStatusFilterCards();
}

function renderStatusFilterCards() {
  document.querySelectorAll(".status-filter-grid .metric[data-filter]").forEach((card) => {
    const active = card.dataset.filter === dashboardState.filter;
    card.classList.toggle("active", active);
    card.setAttribute("aria-pressed", String(active));
  });
  document.querySelectorAll(".status-filter-grid .metric:not([data-filter])").forEach((card) => {
    card.classList.remove("active");
    card.setAttribute("aria-pressed", "false");
  });
}

function renderDashboardAlertGroupOptions(selectedId) {
  const mixed = selectedId === "__mixed";
  return [
    mixed ? '<option value="__mixed" selected>\u6df7\u5408\u914d\u7f6e</option>' : "",
    `<option value="" ${selectedId === "" ? "selected" : ""}>\u672a\u7ed1\u5b9a</option>`,
    ...dashboardState.alertGroups.map((group) => `
      <option value="${group.id}" ${Number(selectedId) === Number(group.id) ? "selected" : ""}>
        ${escapeHtml(group.group_name)}${group.enabled ? "" : " / \u5df2\u505c\u7528"}
      </option>
    `),
  ].join("");
}

function alertBindingText(group) {
  if (group.commonAlertGroupId === "__mixed") return "\u6df7\u5408\u914d\u7f6e";
  if (!group.commonAlertGroupId) return "\u672a\u7ed1\u5b9a";
  const alertGroup = dashboardState.alertGroups.find((item) => Number(item.id) === Number(group.commonAlertGroupId));
  return alertGroup?.group_name || "\u5df2\u7ed1\u5b9a";
}

function renderGroupStatButton(label, count, filter, index, groupKey) {
  const active = (dashboardState.groupStatusFilters[groupKey] || "all") === filter;
  return `
    <button class="service-stat service-stat-${filter} ${active ? "active" : ""}" type="button" aria-pressed="${active}" onclick="setServiceGroupStatusFilter(${index}, '${filter}')">
      <span>${label}</span>
      <strong>${count}</strong>
    </button>
  `;
}

function renderServiceGroupCard(group) {
  const total = group.instances.length;
  const up = group.instances.filter((service) => statusLabel(service.last_status) === "UP").length;
  const down = group.instances.filter((service) => statusLabel(service.last_status) === "DOWN").length;
  const unknown = total - up - down;
  const uniqueTypes = Array.from(new Set(group.instances.map((service) => service.service_type)));
  const primaryType = uniqueTypes[0];
  const types = uniqueTypes.map(serviceTypeLabel).join(" / ");
  const primaryService = group.instances[0];
  const collapsed = !dashboardState.expandedServiceGroupKeys.has(group.key);
  const addInstanceHref = `/services/new?cluster_name=${encodeURIComponent(group.name)}`;
  const bodyId = `service-group-body-${group.index}`;

  return `
    <article class="service-row-card service-group-${group.status}">
      <div class="service-row ${collapsed ? "is-collapsed" : ""}">
        <button class="icon-button group-toggle" type="button" title="${collapsed ? "\u5c55\u5f00\u5b9e\u4f8b" : "\u6536\u8d77\u5b9e\u4f8b"}" aria-expanded="${!collapsed}" aria-controls="${bodyId}" onclick="toggleServiceGroup(${group.index})">
          <i data-lucide="${collapsed ? "chevron-right" : "chevron-down"}"></i>
        </button>
        ${renderStatus(group.status)}
        ${serviceTypeIconHtml(primaryType)}
        <div class="service-row-main">
          <h2>${escapeHtml(group.name)}</h2>
          <p>${escapeHtml(types || "\u76d1\u63a7\u670d\u52a1")}</p>
          <p>\u5206\u7ec4: ${escapeHtml(group.clusterName || "\u9ed8\u8ba4\u5206\u7ec4")}</p>
        </div>
        <div class="service-row-meta">
          <div class="service-stat-row" aria-label="\u5b9e\u4f8b\u72b6\u6001\u7b5b\u9009">
            ${renderGroupStatButton("\u5b9e\u4f8b", total, "all", group.index, group.key)}
            ${renderGroupStatButton("\u6b63\u5e38", up, "UP", group.index, group.key)}
            ${renderGroupStatButton("\u5f02\u5e38", down, "DOWN", group.index, group.key)}
            ${renderGroupStatButton("\u672a\u77e5", unknown, "UNKNOWN", group.index, group.key)}
          </div>
          <p><i data-lucide="bell"></i> \u544a\u8b66\u7b56\u7565: ${escapeHtml(alertBindingText(group))}</p>
          <p><i data-lucide="clock-3"></i> \u6700\u540e\u66f4\u65b0\u65f6\u95f4: ${formatTime(latestGroupCheckTime(group.instances))}</p>
        </div>
        <div class="service-row-actions">
          <button class="icon-button" type="button" title="\u7acb\u5373\u68c0\u6d4b" aria-label="\u7acb\u5373\u68c0\u6d4b" onclick="manualCheckGroup(${group.index})">
            <i data-lucide="refresh-cw"></i>
          </button>
          <a class="icon-button" href="${addInstanceHref}" title="\u65b0\u589e\u5b9e\u4f8b" aria-label="\u65b0\u589e\u5b9e\u4f8b"><i data-lucide="plus"></i></a>
          <a class="icon-button" href="${serviceEditHref(primaryService.id)}" title="\u7f16\u8f91\u670d\u52a1" aria-label="\u7f16\u8f91\u670d\u52a1"><i data-lucide="pencil"></i></a>
        </div>
      </div>
      ${renderInstancePanel(group, bodyId, collapsed)}
    </article>
  `;
}

function renderInstancePanel(group, bodyId, collapsed) {
  const query = dashboardState.instanceQueries[group.key] || "";
  const rows = group.visibleInstances.filter((service) => instanceMatchesGroupQuery(service, group.key));
  const pageSize = 8;
  const totalPages = Math.max(1, Math.ceil(rows.length / pageSize));
  const currentPage = Math.min(Math.max(1, Number(dashboardState.instancePages[group.key] || 1)), totalPages);
  const start = (currentPage - 1) * pageSize;
  const pageRows = rows.slice(start, start + pageSize);

  return `
    <div id="${bodyId}" class="instance-mini-list instance-panel" ${collapsed ? "hidden" : ""}>
      <div class="instance-toolbar">
        <div class="instance-title">\u5b9e\u4f8b\uff08${rows.length}\uff09</div>
        <div class="instance-actions">
          <input
            id="instance-search-${group.index}"
            class="instance-search"
            type="search"
            value="${escapeHtml(query)}"
            placeholder="\u641c\u7d22\u5b9e\u4f8b / IP / \u7aef\u53e3"
            oninput="setServiceGroupInstanceQuery(${group.index}, this.value)"
          >
        </div>
      </div>
      <div class="instance-table-wrap">
        <table class="instance-table">
          <thead>
            <tr>
              <th>\u5b9e\u4f8b\u540d\u79f0</th>
              <th>\u5730\u5740</th>
              <th>\u72b6\u6001</th>
              <th>\u54cd\u5e94\u65f6\u95f4</th>
              <th>\u6700\u8fd1\u4e0a\u62a5\u65f6\u95f4</th>
              <th>\u64cd\u4f5c</th>
            </tr>
          </thead>
          <tbody>
            ${pageRows.map(renderInstanceTableRow).join("") || '<tr><td class="empty" colspan="6">\u6682\u65e0\u5339\u914d\u5b9e\u4f8b</td></tr>'}
          </tbody>
        </table>
      </div>
      ${renderInstancePagination(group.index, rows.length, currentPage, totalPages)}
    </div>
  `;
}

function renderInstancePagination(index, total, currentPage, totalPages) {
  return `
    <div class="instance-pagination">
      <span>\u5171 ${total} \u4e2a\u5b9e\u4f8b</span>
      <div class="instance-page-actions">
        <button class="icon-button" type="button" title="\u4e0a\u4e00\u9875" aria-label="\u4e0a\u4e00\u9875" ${currentPage <= 1 ? "disabled" : ""} onclick="setServiceGroupInstancePage(${index}, ${currentPage - 1})">
          <i data-lucide="chevron-left"></i>
        </button>
        <span>${currentPage} / ${totalPages}</span>
        <button class="icon-button" type="button" title="\u4e0b\u4e00\u9875" aria-label="\u4e0b\u4e00\u9875" ${currentPage >= totalPages ? "disabled" : ""} onclick="setServiceGroupInstancePage(${index}, ${currentPage + 1})">
          <i data-lucide="chevron-right"></i>
        </button>
      </div>
    </div>
  `;
}

function renderInstanceTableRow(service) {
  const status = statusLabel(service.last_status);
  return `
    <tr>
      <td class="instance-name-cell">${escapeHtml(service.service_name || "-")}</td>
      <td class="instance-address-cell">${endpointHtml(service)}</td>
      <td><span class="status-dot-text status-${status}"><span class="status-dot"></span>${status}</span></td>
      <td>${renderLatency(service.last_response_time_ms)}</td>
      <td>${formatTime(service.last_checked_at)}</td>
      <td>
        <span class="instance-row-actions">
          <a class="instance-action" href="${serviceDetailHref(service.id)}"><i data-lucide="eye"></i><span>\u67e5\u770b</span></a>
          <a class="instance-action" href="${serviceEditHref(service.id)}"><i data-lucide="pencil"></i><span>\u7f16\u8f91</span></a>
          <button class="instance-action danger" type="button" onclick="deleteServiceInstance(${service.id})"><i data-lucide="trash-2"></i><span>\u5220\u9664</span></button>
        </span>
      </td>
    </tr>
  `;
}

function latestGroupCheckTime(instances) {
  return instances
    .map((service) => service.last_checked_at)
    .filter(Boolean)
    .sort()
    .at(-1) || "";
}

function relativeTime(value) {
  if (!value) return "-";
  const normalized = value.includes("T") ? value : `${value.replace(" ", "T")}Z`;
  const date = new Date(normalized);
  if (Number.isNaN(date.getTime())) return formatTime(value);
  const minutes = Math.max(1, Math.round((Date.now() - date.getTime()) / 60000));
  if (minutes < 60) return `${minutes} \u5206\u949f\u524d`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} \u5c0f\u65f6\u524d`;
  return `${Math.round(hours / 24)} \u5929\u524d`;
}

function renderAlerts(container, alerts, options = {}) {
  if (!container) return;
  const rows = options.uniqueByService ? uniqueByInstance(alerts) : alerts;
  if (!rows.length) {
    container.innerHTML = '<p class="empty">\u6682\u65e0\u544a\u8b66</p>';
    return;
  }
  container.innerHTML = rows.slice(0, 4).map((alert, index) => `
    <article class="alert-item">
      <span class="alert-icon ${index > 1 ? "warning" : ""}"><i data-lucide="${index > 1 ? "octagon-alert" : "triangle-alert"}"></i></span>
      <span>
        <strong>${escapeHtml(alert.service_name || alert.alert_type || "\u544a\u8b66")}</strong>
        <p>${escapeHtml(alert.alert_content || alert.alert_type || "-")}</p>
      </span>
      <time class="alert-time">${relativeTime(alert.created_at)}</time>
    </article>
  `).join("");
  if (window.lucide) window.lucide.createIcons();
}

function renderDashboardActivity(container, alerts, results) {
  if (!container) return;
  const alertRows = uniqueByInstance(alerts);
  dashboardState.lastActivityCandidates = alertRows;
  const visibleAlerts = alertRows.filter((item) => !dashboardState.dismissedActivityKeys.has(activityItemKey(item)));
  if (visibleAlerts.length) {
    dashboardState.lastActivityRows = visibleAlerts;
    renderAlerts(container, visibleAlerts);
    return;
  }
  const resultRows = uniqueByInstance(results).filter((item) => !dashboardState.dismissedActivityKeys.has(activityItemKey(item)));
  dashboardState.lastActivityRows = resultRows;
  container.innerHTML = resultRows.slice(0, 4).map((item) => `
    <article class="activity-item">
      <span class="alert-icon ${statusLabel(item.status) === "DOWN" ? "" : "warning"}"><i data-lucide="${statusLabel(item.status) === "DOWN" ? "triangle-alert" : "bell"}"></i></span>
      <span>
        <strong>${escapeHtml(item.service_name || "\u68c0\u6d4b\u8bb0\u5f55")}</strong>
        <p>${statusLabel(item.status)} / ${renderLatency(item.response_time_ms)} / ${formatTime(item.checked_at)}</p>
      </span>
      <time class="alert-time">${relativeTime(item.checked_at)}</time>
    </article>
  `).join("") || '<p class="empty">\u6682\u65e0\u544a\u8b66</p>';
  if (window.lucide) window.lucide.createIcons();
}
