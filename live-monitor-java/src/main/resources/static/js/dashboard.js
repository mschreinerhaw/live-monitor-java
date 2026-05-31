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
  bindDashboardSearchInput("serviceListSearch");
  initResourceMonitoring();
  await loadDashboard();
  window.setInterval(loadDashboard, 30000);
}

function bindDashboardSearchInput(id) {
  document.getElementById(id)?.addEventListener("input", (event) => {
    setDashboardQuery(event.target.value);
  });
}

function setDashboardQuery(value) {
  dashboardState.query = value.trim().toLowerCase();
  ["serviceListSearch"].forEach((id) => {
    const input = document.getElementById(id);
    if (input && input.value !== value) input.value = value;
  });
  renderServiceTable();
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
        name: clusterName || service.service_name || "未命名服",
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
            <p>${escapeHtml(types || "监控服务")} · ${total} 个实"· 正常 ${up} / 异常 ${down} / 未知 ${unknown}</p>
          </div>
        </div>
        <div class="service-group-actions">
          <label class="inline-select">
            <span>实例告警</span>
            <select onchange="applyServiceGroupAlert(${group.index}, this.value)">
              ${renderDashboardAlertGroupOptions(group.commonAlertGroupId)}
            </select>
          </label>
          <button class="icon-button" type="button" title="检测全部实" onclick="manualCheckGroup(${group.index})">
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
        ${escapeHtml(group.group_name)}${group.enabled ? "" : " / 已停"}
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
        <small>${escapeHtml(service.alert_group_name || "未绑定告")}</small>
      </div>
      <div class="row-actions compact">
        <a title="详情" href="${serviceDetailHref(service.id)}"><i data-lucide="eye"></i></a>
        <button title="立即检" onclick="manualCheck(${service.id})"><i data-lucide="refresh-cw"></i></button>
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
        ${escapeHtml(group.group_name)}${group.enabled ? "" : " / 已停"}
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
      text: "未绑",
      title: "点击绑定告警策略",
    };
  }
  const alertGroup = dashboardState.alertGroups.find((item) => Number(item.id) === Number(group.commonAlertGroupId));
  return {
    icon: "bell",
    className: "bound",
    text: alertGroup?.group_name || "已绑",
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
            <div class="service-stat-row" aria-label="实例状态筛">
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
          <button class="icon-button" type="button" title="检测全部实" onclick="manualCheckGroup(${group.index})">
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
        <small>${escapeHtml(service.alert_group_name || "未绑定告")}</small>
      </div>
      <div class="row-actions compact">
        <a title="详情" href="${serviceDetailHref(service.id)}"><i data-lucide="eye"></i></a>
        <button type="button" title="立即检" onclick="manualCheck(${service.id})"><i data-lucide="refresh-cw"></i></button>
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
          <button class="icon-button" type="button" title="检测全部实" aria-label="检测全部实" onclick="manualCheckGroup(${group.index})">
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
      <div class="service-stat-row" aria-label="实例状态筛">
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
              <span>状"/span>
              <span>响应时间</span>
              <span>监控图表</span>
              <span>最后上报时"/span>
              <span>告警状"/span>
              <span>操作</span>
            </div>
            ${group.visibleInstances.map(renderInstanceRow).join("") || '<p class="empty">暂无匹配实例</p>'}
          </div>
        </div>
        <div class="instance-list-footer">
          <span>"${group.visibleInstances.length} "/span>
          <div class="pager">
            <button class="icon-button" type="button" disabled aria-label="上一"><i data-lucide="chevron-left"></i></button>
            <button class="page-number active" type="button">1</button>
            <button class="icon-button" type="button" disabled aria-label="下一"><i data-lucide="chevron-right"></i></button>
            <button class="page-size" type="button">10 ""<i data-lucide="chevron-down"></i></button>
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
      <div class="instance-alert-state">${escapeHtml(service.alert_group_name || "未绑定告")}</div>
      <div class="row-actions compact">
        <a title="详情" href="${serviceDetailHref(service.id)}" aria-label="详情"><i data-lucide="eye"></i></a>
        <button type="button" title="立即检" aria-label="立即检" onclick="manualCheck(${service.id})"><i data-lucide="refresh-cw"></i></button>
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
          <button class="icon-button" type="button" title="检测全部实" aria-label="检测全部实" onclick="manualCheckGroup(${group.index})">
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
      <div class="service-stat-row" aria-label="实例状态筛">
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
              <span>状"/span>
              <span>响应时间</span>
              <span>趋势</span>
              <span>最后上报时"/span>
              <span>告警状"/span>
              <span>操作</span>
            </div>
            ${group.visibleInstances.map(renderInstanceRow).join("") || '<p class="empty">暂无匹配实例</p>'}
          </div>
        </div>
        <div class="instance-list-footer">
          <span>"${group.visibleInstances.length} "/span>
          <div class="pager">
            <button class="icon-button" type="button" disabled aria-label="上一"><i data-lucide="chevron-left"></i></button>
            <button class="page-number active" type="button">1</button>
            <button class="icon-button" type="button" disabled aria-label="下一"><i data-lucide="chevron-right"></i></button>
            <button class="page-size" type="button">10 ""<i data-lucide="chevron-down"></i></button>
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
  if (!window.confirm(`确定删除服务「${group.name}」？这会删除该服务下 ${count} 个实例。`)) return;
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
    showToast("服务实例检测完");
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
    showToast("实例告警已批量关");
  } catch (error) {
    showToast(error.message);
    renderServiceTable();
  }
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

