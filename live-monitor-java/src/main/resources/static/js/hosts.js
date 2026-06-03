const hostMetricChartInstances = {};
let hostMetricChartResizeBound = false;

async function initHosts() {
  bindHostEvents();
  await loadHostAlertGroups();
  await loadHosts();
  startHostListRefresh();
}

function bindHostEvents() {
  document.getElementById("newHostBtn")?.addEventListener("click", () => openHostModal());
  document.getElementById("batchHostCheckBtn")?.addEventListener("click", batchRefreshHostMetrics);
  document.getElementById("exportHostsBtn")?.addEventListener("click", exportHosts);
  document.getElementById("hostSearch")?.addEventListener("input", (event) => {
    hostState.filters.query = event.target.value.trim().toLowerCase();
    hostState.page = 1;
    renderHostTable();
  });
  document.getElementById("hostGroupFilter")?.addEventListener("change", (event) => {
    hostState.filters.group = event.target.value || "all";
    hostState.page = 1;
    renderHostTable();
  });
  document.getElementById("hostStatusFilter")?.addEventListener("change", (event) => {
    hostState.filters.status = event.target.value || "all";
    hostState.page = 1;
    renderHostTable();
  });
  document.getElementById("hostCollectModeFilter")?.addEventListener("change", (event) => {
    hostState.filters.collectMode = event.target.value || "all";
    hostState.page = 1;
    renderHostTable();
  });
  document.getElementById("hostPageSize")?.addEventListener("change", (event) => {
    hostState.pageSize = Number(event.target.value || 20);
    hostState.page = 1;
    renderHostTable();
  });
  document.getElementById("hostPrevPageBtn")?.addEventListener("click", () => changeHostPage(hostState.page - 1));
  document.getElementById("hostNextPageBtn")?.addEventListener("click", () => changeHostPage(hostState.page + 1));
  document.getElementById("hostPageJump")?.addEventListener("change", (event) => changeHostPage(Number(event.target.value || 1)));
  document.getElementById("hostForm")?.addEventListener("submit", saveHostForm);
  bindHostAlertThresholdToggleEvents();
  bindHostDurationToggleEvents();
  document.getElementById("hostRealtimeMetricsBtn")?.addEventListener("click", () => setHostMetricView("realtime"));
  document.getElementById("hostSevenDayMetricsBtn")?.addEventListener("click", () => setHostMetricView("7d"));
  document.getElementById("hostRefreshMetricsBtn")?.addEventListener("click", refreshSelectedHostMetrics);
  document.getElementById("exportHostMetricsBtn")?.addEventListener("click", exportSelectedHostMetricHistory);
  bindHostMetricChartResize();
  document.getElementById("closeHostChartZoomBtn")?.addEventListener("click", closeHostChartZoomModal);
  document.getElementById("hostModal")?.addEventListener("click", (event) => {
    if (event.target.id === "hostModal") closeHostModal();
  });
  document.getElementById("hostDetailModal")?.addEventListener("click", (event) => {
    if (event.target.id === "hostDetailModal") closeHostDetailModal();
  });
  document.getElementById("hostChartZoomModal")?.addEventListener("click", (event) => {
    if (event.target.id === "hostChartZoomModal") closeHostChartZoomModal();
  });
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
  try {
    const [hosts, summary] = await Promise.all([
      LiveMonitorApi.hosts(true),
      LiveMonitorApi.hostSummary(),
    ]);
    hostState.hosts = hosts || [];
    hostState.summary = summary || null;
    if (hostState.selectedHostId && !hostState.hosts.some((host) => Number(host.id) === Number(hostState.selectedHostId))) {
      closeHostDetailModal();
      hostState.selectedHostId = null;
    }
    renderHostGroupOptions();
    renderHostSummary();
    renderHostTable();
  } catch (error) {
    const table = document.getElementById("hostTable");
    if (table) table.innerHTML = `<tr><td colspan="7" class="empty">${escapeHtml(error.message)}</td></tr>`;
    showToast(error.message || "主机数据加载失败");
  }
}

function renderHostGroupOptions() {
  const select = document.getElementById("hostGroupFilter");
  if (!select) return;
  const selected = hostState.filters.group || "all";
  const groups = Array.from(new Set(hostState.hosts.map((host) => host.cluster_name || "服务器主机"))).sort();
  select.innerHTML = '<option value="all">全部分组</option>' + groups.map((group) =>
    `<option value="${escapeHtml(group)}">${escapeHtml(group)}</option>`
  ).join("");
  select.value = groups.includes(selected) ? selected : "all";
  hostState.filters.group = select.value;
}

function renderHostSummary() {
  const summary = hostState.summary || {};
  const total = Number(summary.total ?? hostState.hosts.length ?? 0);
  const online = Number(summary.online ?? 0);
  setText("hostSummaryTotal", `${total}台`);
  setText("hostSummaryOnline", `${online}台`);
  setText("hostSummaryWarning", `${Number(summary.warning ?? 0)}台`);
  setText("hostSummaryAvgCpu", percentText(summary.avg_cpu_usage_percent));
  setText("hostSummaryAvgMemory", percentText(summary.avg_memory_used_percent));
  setText("hostSummaryOnlineHint", total ? `在线率 ${Math.round((online / total) * 1000) / 10}%` : "在线率 -");
  setText("hostSummaryTotalHint", `停用 ${Number(summary.disabled ?? 0)} 台`);
}

function renderHostTable() {
  const table = document.getElementById("hostTable");
  if (!table) return;
  const rows = filteredHosts();
  const pageCount = Math.max(1, Math.ceil(rows.length / hostState.pageSize));
  if (hostState.page > pageCount) hostState.page = pageCount;
  const pageRows = rows.slice((hostState.page - 1) * hostState.pageSize, hostState.page * hostState.pageSize);
  if (!pageRows.length) {
    table.innerHTML = '<tr><td colspan="7" class="empty">暂无主机</td></tr>';
  } else {
    table.innerHTML = pageRows.map(renderHostRow).join("");
  }
  renderHostPagination(rows.length, pageCount);
  if (window.lucide) window.lucide.createIcons();
}

function renderHostRow(host) {
  const state = hostStateView(host);
  return `
    <tr>
      <td>
        <div class="host-info-cell">
          <span class="host-row-icon"><i data-lucide="server"></i></span>
          <div>
            <strong>${escapeHtml(host.host_name || "-")} ${host.service_tag ? `<small>${escapeHtml(host.service_tag)}</small>` : ""}</strong>
            <span>${escapeHtml(host.ip || "-")}</span>
            <em>备注：${escapeHtml(host.remark || host.cluster_name || "服务器主机")}</em>
            <div class="host-tag-row">
              <b>${escapeHtml(host.cluster_name || "服务器主机")}</b>
              <b class="soft">${escapeHtml(host.enabled ? "生产环境" : "已停用")}</b>
            </div>
          </div>
        </div>
      </td>
      <td>${renderHostConfigInfo(host)}</td>
      <td>${renderResourceStack(host)}</td>
      <td>
        <div class="host-config-stack host-alert-stack">
          <span>CPU 阈值 <strong>${formatAlertThreshold(host.cpu_threshold_percent, host.cpu_alert_enabled)}</strong></span>
          <span>内存阈值 <strong>${formatAlertThreshold(host.memory_threshold_percent, host.memory_alert_enabled)}</strong></span>
          <span>挂载点阈值 <strong>${formatAlertThreshold(host.disk_threshold_percent, host.disk_alert_enabled)}</strong></span>
          <span>连续异常 <strong>${formatDurationSetting(host.resource_alert_duration_seconds ?? 180, host.resource_alert_duration_enabled)}</strong></span>
          <span>连续恢复 <strong>${formatDurationSetting(host.resource_recover_duration_seconds ?? 180, host.resource_recover_duration_enabled)}</strong></span>
          <span>告警冷却 <strong>${formatDurationSetting(host.resource_alert_cooldown_seconds ?? 600, host.resource_alert_cooldown_enabled)}</strong></span>
        </div>
      </td>
      <td>
        <div class="host-config-stack">
          <span><strong>SSH 连接</strong></span>
          <span>${escapeHtml(host.ssh_user || "-")}@${escapeHtml(host.ip || "-")}:${host.ssh_port || 22}</span>
          <span><strong>检测间隔</strong></span>
          <span><i data-lucide="clock"></i>${formatCheckInterval(host.check_interval)}</span>
        </div>
      </td>
      <td>
        <div class="host-state-cell">
          <span class="state-pill ${state.className}">${state.label}</span>
          <span>${state.detail}</span>
          <small>最后采集<br>${escapeHtml(formatTime(host.metric_checked_at) || "-")}</small>
        </div>
      </td>
      <td>
        <div class="host-row-actions">
          <button class="icon-button" type="button" title="监控" aria-label="监控" onclick="openHostDetailModal(${host.id})"><i data-lucide="chart-line"></i></button>
          <button class="icon-button" type="button" title="编辑" aria-label="编辑" onclick="openHostModal(${host.id})"><i data-lucide="pencil"></i></button>
          <button class="icon-button danger-icon" type="button" title="删除" aria-label="删除" onclick="deleteHost(${host.id})"><i data-lucide="trash-2"></i></button>
          <button class="icon-button" type="button" title="更多" aria-label="更多"><i data-lucide="more-vertical"></i></button>
        </div>
      </td>
    </tr>
  `;
}

function renderHostConfigInfo(host) {
  const disks = normalizeDiskMetrics(host.disk_metrics || host.disk_metrics_json);
  const physicalDisks = normalizePhysicalDiskMetrics(host.physical_disk_metrics || host.physical_disk_metrics_json, disks);
  const physicalDiskCount = physicalDisks.length || (uniqueDiskNames(disks).length || null);
  const diskCountText = physicalDiskCount === null ? "-" : `${physicalDiskCount} 块`;
  const expanded = hostState.expandedPhysicalDiskHostIds?.has(Number(host.id));
  const listId = `hostPhysicalDisks${host.id}`;
  return `
    <div class="host-config-info-stack">
      <span><i data-lucide="cpu"></i>CPU <strong>${formatCoreCount(host.cpu_core_count)}</strong></span>
      <span><i data-lucide="memory-stick"></i>内存 <strong>${formatMemorySize(host.memory_total_mb)}</strong></span>
      <button class="host-config-disk-toggle" type="button"
        onclick="toggleHostPhysicalDisks(${host.id})" aria-expanded="${expanded}" aria-controls="${listId}"
        title="${expanded ? "收起物理磁盘" : "展开物理磁盘"}">
        <span><i data-lucide="hard-drive"></i>物理磁盘<i class="host-resource-chevron ${expanded ? "open" : ""}" data-lucide="chevron-down"></i></span>
        <strong>${diskCountText}</strong>
      </button>
      ${expanded ? renderPhysicalDiskConfigRows(physicalDisks, listId) : ""}
    </div>
  `;
}

function renderPhysicalDiskConfigRows(physicalDisks, listId) {
  if (!physicalDisks.length) {
    return `<div id="${listId}" class="host-config-disk-list"><span class="host-inline-disk-empty">暂无物理磁盘数据</span></div>`;
  }
  const sorted = [...physicalDisks].sort((a, b) => String(a.device || a.name || "").localeCompare(String(b.device || b.name || "")));
  return `
    <div id="${listId}" class="host-config-disk-list" aria-label="物理磁盘列表">
      ${sorted.map((disk) => {
        const name = disk.device || disk.name || "-";
        const total = numericMetric(disk.total_bytes);
        return `
          <div class="host-config-disk-row">
            <span title="${escapeHtml(name)}"><i data-lucide="hard-drive"></i>${escapeHtml(name)}</span>
            <strong>${total === null ? "容量未知" : formatBytes(total)}</strong>
          </div>
        `;
      }).join("")}
    </div>
  `;
}

function toggleHostPhysicalDisks(hostId) {
  const id = Number(hostId);
  if (!hostState.expandedPhysicalDiskHostIds) hostState.expandedPhysicalDiskHostIds = new Set();
  if (hostState.expandedPhysicalDiskHostIds.has(id)) {
    hostState.expandedPhysicalDiskHostIds.delete(id);
  } else {
    hostState.expandedPhysicalDiskHostIds.add(id);
  }
  renderHostTable();
}

function renderResourceStack(host) {
  return `
    <div class="host-resource-stack">
      ${renderResourceLine("cpu", "CPU", host.cpu_usage_percent, host.cpu_threshold_percent, host.cpu_alert_enabled)}
      ${renderResourceLine("memory-stick", "内存", host.memory_used_percent, host.memory_threshold_percent, host.memory_alert_enabled)}
      ${renderResourceLine("hard-drive", "磁盘", host.disk_used_percent, host.disk_threshold_percent, host.disk_alert_enabled)}
    </div>
  `;
}

function renderResourceLine(icon, label, rawValue, threshold = null, thresholdEnabled = true) {
  const value = numericMetric(rawValue);
  const percent = value === null ? 0 : Math.max(0, Math.min(100, value));
  const warn = thresholdEnabled !== false && threshold !== null && threshold !== undefined && value !== null && value > Number(threshold);
  return `
    <div class="host-resource-line ${warn ? "warn" : ""}">
      <span><i data-lucide="${icon}"></i>${label}</span>
      <b><i style="width:${percent}%"></i></b>
      <em>${value === null ? "-" : `${Math.round(value)}%`}</em>
    </div>
  `;
}

function renderHostPagination(total, pageCount) {
  setText("hostTableTotal", `共 ${total} 条记录`);
  const pageSize = document.getElementById("hostPageSize");
  if (pageSize) pageSize.value = String(hostState.pageSize);
  const jump = document.getElementById("hostPageJump");
  if (jump) {
    jump.max = pageCount;
    jump.value = hostState.page;
  }
  const prev = document.getElementById("hostPrevPageBtn");
  const next = document.getElementById("hostNextPageBtn");
  if (prev) prev.disabled = hostState.page <= 1;
  if (next) next.disabled = hostState.page >= pageCount;
  const buttons = document.getElementById("hostPageButtons");
  if (!buttons) return;
  const pages = [];
  for (let page = 1; page <= pageCount && page <= 5; page++) pages.push(page);
  buttons.innerHTML = pages.map((page) =>
    `<button class="${page === hostState.page ? "active" : ""}" type="button" onclick="changeHostPage(${page})">${page}</button>`
  ).join("");
}

function changeHostPage(page) {
  const rows = filteredHosts();
  const pageCount = Math.max(1, Math.ceil(rows.length / hostState.pageSize));
  hostState.page = Math.max(1, Math.min(pageCount, Number(page) || 1));
  renderHostTable();
}

function filteredHosts() {
  const query = (hostState.filters.query || "").trim().toLowerCase();
  const group = hostState.filters.group || "all";
  const status = hostState.filters.status || "all";
  return hostState.hosts.filter((host) =>
    hostMatchesQuery(host, query)
      && (group === "all" || (host.cluster_name || "服务器主机") === group)
      && hostMatchesStatusFilter(host, status)
  );
}

function hostMatchesQuery(host, query) {
  if (!query) return true;
  return [
    host.host_name,
    host.ip,
    host.cluster_name,
    host.ssh_user,
    host.remark,
    hostStateView(host).label,
  ].join(" ").toLowerCase().includes(query);
}

function hostMatchesStatusFilter(host, filter) {
  if (filter === "all") return true;
  return hostStateView(host).key === filter;
}

function hostStateView(host) {
  if (!host.enabled) {
    return { key: "disabled", className: "disabled", label: "离线", detail: "已停用" };
  }
  const hasMetric = ["cpu_usage_percent", "memory_used_percent", "disk_used_percent"]
    .some((key) => numericMetric(host[key]) !== null);
  if (!hasMetric) {
    return { key: "offline", className: "disabled", label: "离线", detail: "等待采集" };
  }
  const cpu = numericMetric(host.cpu_usage_percent);
  const memory = numericMetric(host.memory_used_percent);
  const disk = numericMetric(host.disk_used_percent);
  const cpuThreshold = numericMetric(host.cpu_threshold_percent);
  const memoryThreshold = numericMetric(host.memory_threshold_percent);
  const diskThreshold = numericMetric(host.disk_threshold_percent);
  if ((host.cpu_alert_enabled !== false && cpu !== null && cpuThreshold !== null && cpu > cpuThreshold)
    || (host.memory_alert_enabled !== false && memory !== null && memoryThreshold !== null && memory > memoryThreshold)
    || (host.disk_alert_enabled !== false && disk !== null && diskThreshold !== null && disk > diskThreshold)) {
    let detail = "挂载点使用率高";
    if (host.cpu_alert_enabled !== false && cpu !== null && cpuThreshold !== null && cpu > cpuThreshold) {
      detail = "CPU 使用率高";
    } else if (host.memory_alert_enabled !== false && memory !== null && memoryThreshold !== null && memory > memoryThreshold) {
      detail = "内存使用率高";
    }
    return { key: "warning", className: "danger", label: "告警", detail };
  }
  return { key: "online", className: "enabled", label: "在线", detail: "采集中" };
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

const hostAlertThresholdToggleFields = [
  {
    toggleName: "cpu_alert_enabled",
    valueName: "cpu_threshold_percent",
  },
  {
    toggleName: "memory_alert_enabled",
    valueName: "memory_threshold_percent",
  },
  {
    toggleName: "disk_alert_enabled",
    valueName: "disk_threshold_percent",
  },
];

function bindHostAlertThresholdToggleEvents() {
  const form = document.getElementById("hostForm");
  if (!form) return;
  hostAlertThresholdToggleFields.forEach((field) => {
    form.elements[field.toggleName]?.addEventListener("change", () => syncHostAlertThresholdToggle(form, field));
  });
}

function syncHostAlertThresholdToggle(form, field) {
  const enabled = form.elements[field.toggleName]?.checked !== false;
  if (form.elements[field.valueName]) form.elements[field.valueName].disabled = !enabled;
}

function syncHostAlertThresholdToggles(form) {
  hostAlertThresholdToggleFields.forEach((field) => syncHostAlertThresholdToggle(form, field));
}

const hostDurationToggleFields = [
  {
    toggleName: "resource_alert_duration_enabled",
    valueName: "resource_alert_duration_seconds",
    unitName: "resource_alert_duration_unit",
  },
  {
    toggleName: "resource_recover_duration_enabled",
    valueName: "resource_recover_duration_seconds",
    unitName: "resource_recover_duration_unit",
  },
  {
    toggleName: "resource_alert_cooldown_enabled",
    valueName: "resource_alert_cooldown_seconds",
    unitName: "resource_alert_cooldown_unit",
  },
];

function bindHostDurationToggleEvents() {
  const form = document.getElementById("hostForm");
  if (!form) return;
  hostDurationToggleFields.forEach((field) => {
    form.elements[field.toggleName]?.addEventListener("change", () => syncHostDurationToggle(form, field));
  });
}

function syncHostDurationToggle(form, field) {
  const enabled = form.elements[field.toggleName]?.checked !== false;
  if (form.elements[field.valueName]) form.elements[field.valueName].disabled = !enabled;
  if (form.elements[field.unitName]) form.elements[field.unitName].disabled = !enabled;
}

function syncHostDurationToggles(form) {
  hostDurationToggleFields.forEach((field) => syncHostDurationToggle(form, field));
}

function secondsToMinuteSecondParts(seconds, fallbackSeconds = 60, allowZero = false) {
  const fallback = allowZero ? Math.max(0, Number(fallbackSeconds || 0)) : Math.max(1, Number(fallbackSeconds || 60));
  const normalized = Number.isFinite(Number(seconds)) ? Number(seconds) : fallback;
  const value = allowZero ? Math.max(0, normalized) : Math.max(1, normalized);
  if (value >= 60 && value % 60 === 0) return { value: value / 60, unit: "minutes" };
  return { value, unit: "seconds" };
}

function setHostDurationField(form, valueName, unitName, seconds, fallbackSeconds, allowZero = false) {
  const parts = secondsToMinuteSecondParts(seconds ?? fallbackSeconds, fallbackSeconds, allowZero);
  form.elements[valueName].value = parts.value;
  form.elements[unitName].value = parts.unit;
}

function hostDurationFieldToSeconds(form, valueName, unitName, fallbackSeconds, allowZero = false) {
  const valueText = form.elements[valueName]?.value;
  const value = valueText === "" ? NaN : Number(valueText);
  if (!Number.isFinite(value)) return fallbackSeconds;
  const multiplier = form.elements[unitName]?.value === "minutes" ? 60 : 1;
  const min = allowZero ? 0 : 1;
  return Math.min(Math.max(min, Math.round(value * multiplier)), 31536000);
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
  form.elements.memory_threshold_percent.value = host?.memory_threshold_percent ?? 85;
  form.elements.disk_threshold_percent.value = host?.disk_threshold_percent ?? 85;
  setHostDurationField(form, "resource_alert_duration_seconds", "resource_alert_duration_unit", host?.resource_alert_duration_seconds, 180);
  setHostDurationField(form, "resource_recover_duration_seconds", "resource_recover_duration_unit", host?.resource_recover_duration_seconds, 180);
  setHostDurationField(form, "resource_alert_cooldown_seconds", "resource_alert_cooldown_unit", host?.resource_alert_cooldown_seconds, 600, true);
  form.elements.cpu_alert_enabled.checked = host ? host.cpu_alert_enabled !== false : true;
  form.elements.memory_alert_enabled.checked = host ? host.memory_alert_enabled !== false : true;
  form.elements.disk_alert_enabled.checked = host ? host.disk_alert_enabled !== false : true;
  syncHostAlertThresholdToggles(form);
  form.elements.resource_alert_duration_enabled.checked = host ? host.resource_alert_duration_enabled !== false : true;
  form.elements.resource_recover_duration_enabled.checked = host ? host.resource_recover_duration_enabled !== false : true;
  form.elements.resource_alert_cooldown_enabled.checked = host ? host.resource_alert_cooldown_enabled !== false : true;
  syncHostDurationToggles(form);
  const intervalParts = secondsToIntervalParts(host?.check_interval || 60);
  form.elements.check_interval_value.value = host?.check_interval_value || intervalParts.value;
  form.elements.check_interval_unit.value = host?.check_interval_unit || intervalParts.unit;
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
    memory_threshold_percent: Number(form.elements.memory_threshold_percent.value || 85),
    disk_threshold_percent: Number(form.elements.disk_threshold_percent.value || 85),
    resource_alert_duration_enabled: form.elements.resource_alert_duration_enabled.checked,
    resource_recover_duration_enabled: form.elements.resource_recover_duration_enabled.checked,
    resource_alert_cooldown_enabled: form.elements.resource_alert_cooldown_enabled.checked,
    resource_alert_duration_seconds: hostDurationFieldToSeconds(form, "resource_alert_duration_seconds", "resource_alert_duration_unit", 180),
    resource_recover_duration_seconds: hostDurationFieldToSeconds(form, "resource_recover_duration_seconds", "resource_recover_duration_unit", 180),
    resource_alert_cooldown_seconds: hostDurationFieldToSeconds(form, "resource_alert_cooldown_seconds", "resource_alert_cooldown_unit", 600, true),
    cpu_alert_enabled: form.elements.cpu_alert_enabled.checked,
    memory_alert_enabled: form.elements.memory_alert_enabled.checked,
    disk_alert_enabled: form.elements.disk_alert_enabled.checked,
    check_interval_value: Number(form.elements.check_interval_value.value || 1),
    check_interval_unit: form.elements.check_interval_unit.value || "minutes",
    check_interval: intervalToSeconds(
      form.elements.check_interval_value.value || 1,
      form.elements.check_interval_unit.value || "minutes"
    ),
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

async function batchRefreshHostMetrics() {
  const button = document.getElementById("batchHostCheckBtn");
  if (button) button.disabled = true;
  try {
    const result = await LiveMonitorApi.refreshAllHostMetrics();
    showToast(`批量检测完成：成功 ${result.success || 0} 台，失败 ${result.failed || 0} 台`);
    await loadHosts();
  } catch (error) {
    showToast(error.message || "批量检测失败");
  } finally {
    if (button) button.disabled = false;
  }
}

async function openHostDetailModal(id) {
  const token = ++hostState.metricLifecycleToken;
  await selectHost(id);
  const modal = document.getElementById("hostDetailModal");
  if (modal) modal.hidden = false;
  if (window.lucide) window.lucide.createIcons();
  await loadSelectedHostMetrics(true, token);
  if (isHostDetailMetricSessionActive(token)) {
    startHostMetricAutoRefresh(token);
  }
}

function closeHostDetailModal() {
  hostState.metricLifecycleToken += 1;
  stopHostMetricAutoRefresh();
  closeHostChartZoomModal();
  disposeHostMetricChart("hostCpuChart");
  disposeHostMetricChart("hostMemoryChart");
  const modal = document.getElementById("hostDetailModal");
  if (modal) modal.hidden = true;
}

async function selectHost(id) {
  hostState.selectedHostId = id ? Number(id) : null;
  hostState.metrics = null;
  hostState.metricView = "realtime";
  hostState.metricHistoryRows = [];
  resetHostMetricHistory();
  updateHostMetricModeButtons();
  renderSelectedHostSummary();
  renderHostMetrics(null);
}

function selectedHost() {
  return hostState.hosts.find((host) => Number(host.id) === Number(hostState.selectedHostId));
}

function renderSelectedHostSummary() {
  const host = selectedHost();
  setText("selectedHostTitle", host ? `${host.host_name || host.ip} 指标` : "主机指标");
  setText(
    "selectedHostMeta",
    host
      ? `主机：${host.ip || "-"} ｜ 状态：${hostStateView(host).label} ｜ 刷新：每${formatCheckInterval(host.check_interval)}`
      : "打开详情后按主机检测间隔自动刷新"
  );
}

async function refreshSelectedHostMetrics() {
  const token = hostState.metricLifecycleToken;
  if (!isHostDetailMetricSessionActive(token) || hostState.metricRefreshing) {
    return;
  }
  const button = document.getElementById("hostRefreshMetricsBtn");
  hostState.metricRefreshing = true;
  if (button) {
    button.disabled = true;
    button.classList.add("is-spinning");
  }
  try {
    if (hostState.metricView === "7d") {
      await LiveMonitorApi.refreshHostMetrics(hostState.selectedHostId);
      if (!isHostDetailMetricSessionActive(token)) return;
      if (await loadSelectedHostMetricHistory(token)) showToast("主机指标已刷新");
      await loadHosts();
    } else {
      if (await loadSelectedHostMetrics(true, token)) showToast("主机指标已刷新");
    }
  } catch (error) {
    showToast(error.message || "指标刷新失败");
  } finally {
    hostState.metricRefreshing = false;
    if (button) {
      button.disabled = false;
      button.classList.remove("is-spinning");
    }
  }
}

async function loadSelectedHostMetrics(refresh = false, token = hostState.metricLifecycleToken) {
  if (!hostState.selectedHostId) {
    showToast("请先选择主机");
    return false;
  }
  if (hostState.metricView === "7d") {
    return false;
  }
  if (!isHostDetailMetricSessionActive(token)) {
    return false;
  }
  const hostId = hostState.selectedHostId;
  renderHostMetricLoading();
  try {
    const metrics = refresh
      ? await LiveMonitorApi.refreshHostMetrics(hostId)
      : await LiveMonitorApi.hostMetrics(hostId);
    if (!isHostDetailMetricSessionActive(token) || Number(hostState.selectedHostId) !== Number(hostId)) {
      return false;
    }
    hostState.metrics = metrics;
    renderHostMetrics(hostState.metrics);
    await loadHosts();
    return true;
  } catch (error) {
    if (!isHostDetailMetricSessionActive(token)) {
      return false;
    }
    showToast(error.message);
    renderHostMetrics({ cpu: error.message, memory: "-", disk: "-" });
    return false;
  }
}

function renderHostMetrics(metrics) {
  updateHostMetricModePanels();
  if (!metrics) {
    setHostMetricText("hostCpuValue", "-");
    setHostMetricText("hostMemoryValue", "-");
    setHostMetricText("hostDiskValue", "-");
    setHostMetricText("hostDiskHint", "挂载点使用率");
    setHostMetricText("hostDiskCapacity", "-");
    renderHostRealtimeOverview(null);
    renderHostDiskMounts([]);
    disposeHostMetricChart("hostCpuChart");
    disposeHostMetricChart("hostMemoryChart");
    return;
  }
  const cpu = numericMetric(metrics.cpu_usage_percent);
  const memory = numericMetric(metrics.memory_used_percent);
  const disk = numericMetric(metrics.disk_used_percent);
  setHostMetricText("hostCpuValue", cpu === null ? "-" : `${cpu.toFixed(1)}%`);
  setHostMetricText("hostMemoryValue", memory === null ? "-" : `${memory.toFixed(1)}%`);
  setHostMetricText("hostDiskValue", disk === null ? "-" : `${disk.toFixed(1)}%`);
  renderHostRealtimeOverview(metrics);
  renderHostDiskMounts(normalizeDiskMetrics(metrics.disk_metrics || metrics.disk_metrics_json));
  disposeHostMetricChart("hostCpuChart");
  disposeHostMetricChart("hostMemoryChart");
}

function renderHostMetricLoading() {
  updateHostMetricModePanels();
  setHostMetricText("hostCpuValue", "...");
  setHostMetricText("hostMemoryValue", "...");
  setHostMetricText("hostDiskValue", "...");
  setHostMetricText("hostDiskHint", "扫描挂载点...");
  setHostMetricText("hostDiskCapacity", "-");
  renderHostRealtimeOverview(null, true);
  const list = document.getElementById("hostDiskMountList");
  if (list) list.innerHTML = "";
}

async function setHostMetricView(view) {
  const token = hostState.metricLifecycleToken;
  if (!isHostDetailMetricSessionActive(token)) {
    return;
  }
  hostState.metricView = view;
  updateHostMetricModeButtons();
  if (view === "7d") {
    await loadSelectedHostMetricHistory(token);
    return;
  }
  resetHostMetricHistory();
  renderHostMetrics(null);
  await loadSelectedHostMetrics(false, token);
}

function updateHostMetricModeButtons() {
  const realtime = document.getElementById("hostRealtimeMetricsBtn");
  const sevenDay = document.getElementById("hostSevenDayMetricsBtn");
  const view = hostState.metricView || "realtime";
  realtime?.classList.toggle("active", view === "realtime");
  sevenDay?.classList.toggle("active", view === "7d");
  updateHostMetricModePanels();
}

async function loadSelectedHostMetricHistory(token = hostState.metricLifecycleToken) {
  if (!hostState.selectedHostId) {
    showToast("请先选择主机");
    return false;
  }
  if (!isHostDetailMetricSessionActive(token)) {
    return false;
  }
  const hostId = hostState.selectedHostId;
  renderHostMetricHistoryLoading();
  try {
    const rows = await LiveMonitorApi.hostMetricHistory(hostId, 7, 10000);
    if (!isHostDetailMetricSessionActive(token) || Number(hostState.selectedHostId) !== Number(hostId)) {
      return false;
    }
    hostState.metricHistoryRows = rows || [];
    renderHostMetricHistory(hostState.metricHistoryRows);
    return true;
  } catch (error) {
    if (!isHostDetailMetricSessionActive(token)) {
      return false;
    }
    showToast(error.message || "近7天指标加载失败");
    renderHostMetricHistory([]);
    return false;
  }
}

function renderHostMetricHistoryLoading() {
  updateHostMetricModePanels();
  setHostMetricText("hostCpuValue", "...");
  setHostMetricText("hostMemoryValue", "...");
  setHostMetricText("hostDiskValue", "...");
  setHostMetricText("hostDiskHint", "近7天 ...");
  setHostMetricText("hostDiskCapacity", "-");
  renderHostDiskMounts([]);
  drawHostMetricCharts();
}

function renderHostMetricHistory(rows) {
  updateHostMetricModePanels();
  const normalized = (rows || []).filter(Boolean);
  resetHostMetricHistory();
  hostState.metricHistory.labels = normalized.map((row, index) =>
    formatChartTickLabel(row.checked_at || row.checkedAt, index)
  );
  hostState.metricHistory.cpu = metricSeries(normalized, "cpu_usage_percent");
  hostState.metricHistory.load = metricSeries(normalized, "load_average");
  hostState.metricHistory.memory = metricSeries(normalized, "memory_used_percent");
  hostState.metricHistory.disk = metricSeries(normalized, "disk_used_percent");

  const latest = normalized[normalized.length - 1] || null;
  const latestCpu = latest ? numericMetric(latest.cpu_usage_percent) : null;
  const latestMemory = latest ? numericMetric(latest.memory_used_percent) : null;
  const latestDisk = latest ? numericMetric(latest.disk_used_percent) : null;

  setHostMetricText("hostCpuValue", latestCpu === null ? "-" : `${latestCpu.toFixed(1)}%`);
  setHostMetricText("hostMemoryValue", latestMemory === null ? "-" : `${latestMemory.toFixed(1)}%`);
  setHostMetricText("hostDiskValue", latestDisk === null ? "-" : `${latestDisk.toFixed(1)}%`);
  renderHostDiskMounts(latest ? normalizeDiskMetrics(latest.disk_metrics || latest.disk_metrics_json) : []);
  drawHostMetricCharts();
}

function metricSeries(rows, key) {
  return rows.map((row) => numericMetric(row[key])).map((value) => value === null ? 0 : value);
}

function renderHostRealtimeOverview(metrics, loading = false) {
  const cpu = metrics ? numericMetric(metrics.cpu_usage_percent) : null;
  const memory = metrics ? numericMetric(metrics.memory_used_percent) : null;

  setHostGauge("hostCpuGauge", cpu);
  setHostGauge("hostMemoryGauge", memory);
  setHostMetricText("hostCpuGaugeValue", loading ? "..." : percentText(cpu));
  setHostMetricText("hostMemoryGaugeValue", loading ? "..." : percentText(memory));
}

function updateHostMetricModePanels() {
  const isHistory = hostState.metricView === "7d";
  setHidden("hostCpuRealtime", isHistory);
  setHidden("hostMemoryRealtime", isHistory);
  setHidden("hostCpuChart", !isHistory);
  setHidden("hostMemoryChart", !isHistory);
  setText("hostCpuModeHint", isHistory ? "近7天趋势" : "实时状态");
  setText("hostMemoryModeHint", isHistory ? "近7天趋势" : "实时状态");
  const statsHidden = !isHistory;
  setHidden("hostCpuStats", statsHidden);
  setHidden("hostMemoryStats", statsHidden);
  if (!isHistory) {
    closeHostChartZoomModal();
    disposeHostMetricChart("hostCpuChart");
    disposeHostMetricChart("hostMemoryChart");
  }
}

function setHidden(id, hidden) {
  const node = document.getElementById(id);
  if (node) node.hidden = Boolean(hidden);
}

function setHostGauge(id, value) {
  const node = document.getElementById(id);
  if (!node) return;
  const number = numericMetric(value);
  const percent = number === null ? 0 : Math.max(0, Math.min(100, number));
  node.style.setProperty("--value", String(percent));
  node.style.setProperty("--gauge-deg", `${percent * 1.8}deg`);
}

function metricHealthState(value, threshold, enabled = true) {
  const number = numericMetric(value);
  const limit = numericMetric(threshold);
  if (enabled === false || limit === null) {
    return { label: "未设阈值", className: "muted", distance: "告警关闭" };
  }
  if (number === null) {
    return { label: "暂无数据", className: "muted", distance: "-" };
  }
  const diff = Math.round((limit - number) * 10) / 10;
  if (diff < 0) {
    return { label: "告警", className: "bad", distance: `已超过 ${percentText(Math.abs(diff))}` };
  }
  if (number >= limit * 0.8) {
    return { label: "注意", className: "warn", distance: `还差 ${percentText(diff)}` };
  }
  return { label: "正常", className: "ok", distance: `还差 ${percentText(diff)}` };
}

function setHostStatusClass(id, className) {
  const node = document.getElementById(id);
  if (!node) return;
  node.classList.remove("ok", "warn", "bad", "muted", "loading");
  node.classList.add(className || "muted");
}

function renderHostDiskMounts(disks) {
  const list = document.getElementById("hostDiskMountList");
  const hint = document.getElementById("hostDiskHint");
  if (!list) return;
  const host = selectedHost() || {};
  const threshold = host.disk_alert_enabled === false ? null : numericMetric(host.disk_threshold_percent);
  if (!disks.length) {
    list.innerHTML = '<span class="host-disk-empty">暂无挂载点数据</span>';
    if (hint) hint.textContent = "挂载点";
    setHostMetricText("hostDiskCapacity", "");
    setHostMetricText("hostDiskTopSummary", "最高挂载点：-");
    setHostMetricText("hostDiskAlertDistance", "-");
    return;
  }
  const sorted = [...disks].sort((a, b) => numericMetric(b.used_percent) - numericMetric(a.used_percent));
  const topDisk = sorted[0] || {};
  const topUsed = numericMetric(topDisk.used_percent);
  const diskState = metricHealthState(topUsed, threshold, host.disk_alert_enabled);
  if (hint) hint.textContent = `挂载点 ${sorted.length} 个 / 最高 ${percentText(sorted[0].used_percent)}`;
  setHostMetricText("hostDiskValue", percentText(topUsed));
  setHostMetricText("hostDiskTopSummary", `最高挂载点：${topDisk.mount || "-"} ${percentText(topUsed)}`);
  setHostMetricText("hostDiskAlertDistance", diskState.distance);
  setHostMetricText("hostDiskCapacity", "");
  list.innerHTML = sorted.slice(0, 5).map((disk) => {
    const used = numericMetric(disk.used_percent);
    const percent = used === null ? 0 : Math.max(0, Math.min(100, used));
    const mount = disk.mount || "-";
    const state = metricHealthState(used, threshold, host.disk_alert_enabled);
    return `
      <div class="host-disk-mount">
        <span title="${escapeHtml(mount)}">${escapeHtml(mount)}</span>
        <b><i style="width:${percent}%"></i></b>
        <em>${percentText(used)}</em>
        <i class="host-disk-status ${state.className}">${state.label}</i>
      </div>
    `;
  }).join("");
}

function resetHostMetricHistory() {
  hostState.metricHistory = { labels: [], cpu: [], load: [], memory: [], disk: [] };
}

function pushHostMetricLabel(label) {
  hostState.metricHistory.labels.push(label);
  while (hostState.metricHistory.labels.length > 30) hostState.metricHistory.labels.shift();
}

function pushHostMetric(key, value) {
  const rows = hostState.metricHistory[key];
  if (value === null) return;
  rows.push(value);
  while (rows.length > 30) rows.shift();
}

function setHostMetricText(id, value) {
  const node = document.getElementById(id);
  if (node) node.textContent = value;
}

function drawHostMetricCharts() {
  if (hostState.metricView !== "7d") {
    disposeHostMetricChart("hostCpuChart");
    disposeHostMetricChart("hostMemoryChart");
    return;
  }
  renderHostMetricStats();
  drawHostMetricChart("hostCpuChart", hostMetricChartDefinition("cpu"), { compact: true });
  drawHostMetricChart("hostMemoryChart", hostMetricChartDefinition("memory"), { compact: true });
  drawExpandedHostMetricChart();
}

function renderHostMetricStats() {
  renderHostMetricStat("hostCpuStats", hostMetricChartDefinition("cpu"));
  renderHostMetricStat("hostMemoryStats", hostMetricChartDefinition("memory"));
}

function renderHostMetricStat(id, definition) {
  const node = document.getElementById(id);
  if (!node || !definition) return;
  const stats = hostMetricSeriesStats(definition.values);
  const threshold = numericMetric(definition.threshold);
  if (!stats) {
    node.textContent = `当前 - | 平均 - | 最大 - | 阈值 ${threshold === null ? "-" : formatThreshold(threshold)}`;
    return;
  }
  node.textContent = [
    `当前 ${definition.formatter(stats.current)}`,
    `平均 ${definition.formatter(stats.avg)}`,
    `最大 ${definition.formatter(stats.max)}`,
    `最小 ${definition.formatter(stats.min)}`,
    `阈值 ${threshold === null ? "-" : formatThreshold(threshold)}`,
  ].join(" | ");
}

function hostMetricSeriesStats(values) {
  const series = (values || []).map((value) => numericMetric(value)).filter((value) => value !== null);
  if (!series.length) return null;
  const sum = series.reduce((total, value) => total + value, 0);
  return {
    current: series[series.length - 1],
    avg: sum / series.length,
    max: Math.max(...series),
    min: Math.min(...series),
  };
}

function hostMetricChartDefinition(key) {
  const definitions = {
    cpu: {
      key: "cpu",
      title: "CPU 使用率",
      color: "#2563eb",
      values: hostState.metricHistory.cpu || [],
      formatter: percentText,
      threshold: selectedHost()?.cpu_alert_enabled === false ? null : numericMetric(selectedHost()?.cpu_threshold_percent),
    },
    memory: {
      key: "memory",
      title: "内存使用率",
      color: "#16a34a",
      values: hostState.metricHistory.memory || [],
      formatter: percentText,
      threshold: selectedHost()?.memory_alert_enabled === false ? null : numericMetric(selectedHost()?.memory_threshold_percent),
    },
  };
  const definition = definitions[key] || definitions.cpu;
  const labels = hostState.metricHistory.labels || [];
  const maxValue = scaledPercentMax(definition.values, 10, definition.threshold);
  return {
    ...definition,
    labels: labels.slice(Math.max(0, labels.length - definition.values.length)),
    maxValue,
  };
}

function drawHostMetricChart(canvasId, definition, options = {}) {
  const element = document.getElementById(canvasId);
  if (!element || !definition || !document.body.contains(element)) {
    disposeHostMetricChart(canvasId);
    return null;
  }
  if (!window.echarts) {
    element.innerHTML = '<p class="empty">图表资源未加载</p>';
    return null;
  }

  const chart = getHostMetricChart(canvasId, element);
  if (!chart) return null;
  hostMetricChartInstances[canvasId] = chart;
  if (options.compact) {
    element.onclick = () => openHostChartZoomModal(definition.key);
  } else {
    element.onclick = null;
  }
  chart.setOption(hostMetricEChartOption(definition, options), true);
  window.requestAnimationFrame(() => {
    if (hostMetricChartInstances[canvasId] === chart && !isHostMetricChartDisposed(chart) && document.body.contains(element)) {
      chart.resize();
    }
  });
  return chart;
}

function getHostMetricChart(canvasId, element) {
  const cachedChart = hostMetricChartInstances[canvasId];
  if (cachedChart && !isHostMetricChartDisposed(cachedChart)) {
    return cachedChart;
  }
  delete hostMetricChartInstances[canvasId];
  const domChart = window.echarts.getInstanceByDom(element);
  if (domChart && !isHostMetricChartDisposed(domChart)) {
    return domChart;
  }
  return window.echarts.init(element);
}

function isHostMetricChartDisposed(chart) {
  return !chart || (typeof chart.isDisposed === "function" && chart.isDisposed());
}

function disposeHostMetricChart(canvasId) {
  const element = document.getElementById(canvasId);
  const chart = hostMetricChartInstances[canvasId]
    || (element && window.echarts ? window.echarts.getInstanceByDom(element) : null);
  delete hostMetricChartInstances[canvasId];
  if (element) element.onclick = null;
  if (!chart || isHostMetricChartDisposed(chart)) return;
  try {
    chart.dispose();
  } catch (error) {
    console.warn("Failed to dispose host metric chart", canvasId, error);
  }
}

function hostMetricEChartOption(definition, options = {}) {
  const labels = labelsForChart(definition.labels, (definition.values || []).length);
  const values = (definition.values || []).map((value) => numericMetric(value)).map((value) => value === null ? null : value);
  const threshold = numericMetric(definition.threshold);
  const showDataZoom = Boolean(options.zoom);
  const hasData = values.some((value) => value !== null);
  return {
    color: [definition.color],
    animationDuration: 260,
    tooltip: {
      trigger: "axis",
      confine: true,
      axisPointer: {
        type: "line",
        snap: true,
      },
      formatter(params) {
        const item = Array.isArray(params) ? params[0] : params;
        if (!item) return "";
        return [
          `<strong>${escapeHtml(definition.title)}</strong>`,
          escapeHtml(labels[item.dataIndex] || "-"),
          `<br><span>${escapeHtml(definition.formatter(item.value))}</span>`,
        ].join("<br>");
      },
    },
    grid: {
      top: options.zoom ? 28 : 18,
      right: options.zoom ? 34 : 16,
      bottom: showDataZoom ? 76 : 34,
      left: 46,
      containLabel: true,
    },
    axisPointer: {
      snap: true,
    },
    xAxis: {
      type: "category",
      boundaryGap: false,
      data: labels,
      axisLabel: {
        color: "#64748b",
        hideOverlap: true,
        formatter(value) {
          return String(value || "").replace(" ", "\n");
        },
      },
      axisLine: { lineStyle: { color: "#d9e1e8" } },
      axisTick: { show: false },
    },
    yAxis: {
      type: "value",
      min: 0,
      max: definition.maxValue,
      axisLabel: {
        color: "#64748b",
        formatter: "{value}%",
      },
      splitLine: { lineStyle: { color: "#edf2f7" } },
    },
    dataZoom: showDataZoom ? [
      { type: "inside", filterMode: "none", throttle: 60 },
      {
        type: "slider",
        height: 24,
        bottom: 22,
        filterMode: "none",
        brushSelect: false,
        handleSize: "80%",
      },
    ] : [
      { type: "inside", filterMode: "none", throttle: 80 },
    ],
    graphic: hasData ? [] : [
      {
        type: "text",
        left: "center",
        top: "middle",
        style: {
          text: "暂无数据",
          fill: "#94a3b8",
          font: "12px Microsoft YaHei, Arial, sans-serif",
        },
      },
    ],
    series: [
      {
        name: definition.title,
        type: "line",
        data: values,
        smooth: true,
        sampling: "lttb",
        connectNulls: true,
        showSymbol: false,
        symbol: "circle",
        symbolSize: 7,
        lineStyle: {
          width: options.zoom ? 3 : 2.5,
        },
        areaStyle: {
          opacity: options.zoom ? 0.12 : 0.08,
        },
        emphasis: {
          focus: "series",
          scale: true,
        },
        markPoint: hasData ? {
          symbol: "pin",
          symbolSize: options.zoom ? 54 : 44,
          label: {
            formatter(params) {
              return definition.formatter(params.value);
            },
          },
          data: [
            { type: "max", name: "最高值" },
          ],
        } : undefined,
        markLine: threshold === null ? undefined : {
          silent: true,
          symbol: "none",
          lineStyle: {
            color: "#dc2626",
            type: "dashed",
            width: 1.2,
          },
          label: {
            color: "#dc2626",
            formatter: `阈值 ${formatThreshold(threshold)}`,
          },
          data: [
            { yAxis: threshold, name: "阈值" },
          ],
        },
      },
    ],
  };
}

function labelsForChart(labels, length) {
  const result = (labels || []).slice(Math.max(0, (labels || []).length - length));
  if (result.length >= length) return result;
  const missing = length - result.length;
  return Array.from({ length: missing }, (_, index) => `#${index + 1}`).concat(result);
}

function scaledPercentMax(values, minimum = 10, threshold = null) {
  const max = Math.max(0, ...(values || []).map((value) => numericMetric(value) || 0));
  const thresholdValue = numericMetric(threshold);
  const raw = Math.max(minimum, max * 1.2, thresholdValue === null ? 0 : thresholdValue * 1.1);
  if (raw <= 10) return 10;
  if (raw <= 20) return Math.ceil(raw / 2) * 2;
  if (raw <= 50) return Math.ceil(raw / 5) * 5;
  return Math.min(100, Math.ceil(raw / 10) * 10);
}

function bindHostMetricChartResize() {
  if (hostMetricChartResizeBound) return;
  hostMetricChartResizeBound = true;
  window.addEventListener("resize", () => {
    Object.entries(hostMetricChartInstances).forEach(([canvasId, chart]) => {
      const element = document.getElementById(canvasId);
      if (!element || !document.body.contains(element) || isHostMetricChartDisposed(chart)) {
        delete hostMetricChartInstances[canvasId];
        return;
      }
      chart.resize();
    });
  }, { passive: true });
}

function openHostChartZoomModal(chartKey) {
  hostState.expandedMetricChart = chartKey;
  const definition = hostMetricChartDefinition(chartKey);
  setText("hostChartZoomTitle", `${definition.title}趋势`);
  setText("hostChartZoomMeta", `${selectedHost()?.host_name || selectedHost()?.ip || "-"} / ${hostState.metricView === "7d" ? "近7天" : "实时"}`);
  const modal = document.getElementById("hostChartZoomModal");
  if (modal) modal.hidden = false;
  if (window.lucide) window.lucide.createIcons();
  window.requestAnimationFrame(drawExpandedHostMetricChart);
}

function closeHostChartZoomModal() {
  const modal = document.getElementById("hostChartZoomModal");
  if (modal) modal.hidden = true;
  disposeHostMetricChart("hostChartZoomCanvas");
  hostState.expandedMetricChart = null;
}

function drawExpandedHostMetricChart() {
  if (!hostState.expandedMetricChart || document.getElementById("hostChartZoomModal")?.hidden) {
    return;
  }
  const definition = hostMetricChartDefinition(hostState.expandedMetricChart);
  drawHostMetricChart("hostChartZoomCanvas", definition, { zoom: true });
}

function isHostDetailMetricSessionActive(token = hostState.metricLifecycleToken) {
  const modal = document.getElementById("hostDetailModal");
  return token === hostState.metricLifecycleToken
    && Boolean(hostState.selectedHostId)
    && Boolean(modal)
    && !modal.hidden;
}

function formatChartTickLabel(value, index = 0) {
  if (!value) return `#${index + 1}`;
  const date = value instanceof Date ? value : new Date(String(value).replace(" ", "T"));
  if (Number.isNaN(date.getTime())) return String(value).slice(0, 16);
  return date.toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
}

function startHostMetricAutoRefresh(token = hostState.metricLifecycleToken) {
  stopHostMetricAutoRefresh();
  const host = selectedHost();
  if (!host) return;
  const seconds = Math.max(1, Number(host.check_interval || 60));
  hostState.metricTimer = window.setInterval(() => {
    if (!isHostDetailMetricSessionActive(token)) {
      stopHostMetricAutoRefresh();
      return;
    }
    loadSelectedHostMetrics(true, token);
  }, seconds * 1000);
}

function stopHostMetricAutoRefresh() {
  if (!hostState.metricTimer) return;
  window.clearInterval(hostState.metricTimer);
  hostState.metricTimer = null;
}

function startHostListRefresh() {
  stopHostListRefresh();
  hostState.listMetricTimer = window.setInterval(loadHosts, 60000);
}

function stopHostListRefresh() {
  if (!hostState.listMetricTimer) return;
  window.clearInterval(hostState.listMetricTimer);
  hostState.listMetricTimer = null;
}

async function exportSelectedHostMetricHistory() {
  if (!hostState.selectedHostId) {
    showToast("请先选择主机");
    return;
  }
  let rows = hostState.metricHistoryRows || [];
  if (!rows.length) {
    try {
      rows = await LiveMonitorApi.hostMetricHistory(hostState.selectedHostId, 7, 10000);
      hostState.metricHistoryRows = rows || [];
    } catch (error) {
      showToast(error.message || "指标数据导出失败");
      return;
    }
  }
  if (!rows.length) {
    showToast("暂无近7天指标数据可导出");
    return;
  }
  const host = selectedHost() || {};
  const header = ["采集时间", "CPU使用率", "Load", "内存使用率", "挂载点最高使用率", "挂载点"];
  const body = rows.map((row) => [
    formatTime(row.checked_at) || row.checked_at || "",
    percentText(row.cpu_usage_percent),
    numericMetric(row.load_average) === null ? "" : Number(row.load_average).toFixed(2),
    percentText(row.memory_used_percent),
    percentText(row.disk_used_percent),
    formatDiskMountSummary(row),
  ]);
  const csv = [header, ...body].map((row) => row.map(csvCell).join(",")).join("\n");
  const blob = new Blob(["\ufeff" + csv], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  const name = sanitizeFilename(host.host_name || host.ip || `host-${hostState.selectedHostId}`);
  link.href = url;
  link.download = `${name}-metrics-7d-${new Date().toISOString().slice(0, 10)}.csv`;
  link.click();
  URL.revokeObjectURL(url);
  showToast("近7天指标数据已导出");
}

function formatDiskMountSummary(row) {
  const disks = normalizeDiskMetrics(row.disk_metrics || row.disk_metrics_json);
  return disks.map((disk) => `${disk.mount || "-"} ${percentText(disk.used_percent)}`).join("; ");
}

function sanitizeFilename(value) {
  return String(value || "host").replace(/[\\/:*?"<>|]/g, "_");
}

function exportHosts() {
  const rows = filteredHosts();
  const header = ["主机名称", "IP", "分组", "SSH", "CPU使用率", "内存使用率", "挂载点最高使用率", "CPU核数", "内存总量", "物理磁盘数量", "物理磁盘容量", "CPU阈值", "内存阈值", "挂载点阈值", "检测间隔", "状态", "最后采集"];
  const body = rows.map((host) => {
    const disks = normalizeDiskMetrics(host.disk_metrics || host.disk_metrics_json);
    const physicalDisks = normalizePhysicalDiskMetrics(host.physical_disk_metrics || host.physical_disk_metrics_json, disks);
    const diskCount = physicalDisks.length || (uniqueDiskNames(disks).length || null);
    const capacity = physicalDiskCapacityOverview(physicalDisks, disks);
    return [
      host.host_name || "",
      host.ip || "",
      host.cluster_name || "",
      `${host.ssh_user || ""}@${host.ip || ""}:${host.ssh_port || 22}`,
      percentText(host.cpu_usage_percent),
      percentText(host.memory_used_percent),
      percentText(host.disk_used_percent),
      formatCoreCount(host.cpu_core_count),
      formatMemorySize(host.memory_total_mb),
      diskCount === null ? "" : `${Math.round(Number(diskCount))}`,
      capacity.totalBytes ? formatBytes(capacity.totalBytes) : "",
      formatAlertThreshold(host.cpu_threshold_percent, host.cpu_alert_enabled),
      formatAlertThreshold(host.memory_threshold_percent, host.memory_alert_enabled),
      formatAlertThreshold(host.disk_threshold_percent, host.disk_alert_enabled),
      formatCheckInterval(host.check_interval),
      hostStateView(host).label,
      formatTime(host.metric_checked_at) || "",
    ];
  });
  const csv = [header, ...body].map((row) => row.map(csvCell).join(",")).join("\n");
  const blob = new Blob(["\ufeff" + csv], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = `hosts-${new Date().toISOString().slice(0, 10)}.csv`;
  link.click();
  URL.revokeObjectURL(url);
}

function csvCell(value) {
  return `"${String(value ?? "").replace(/"/g, '""')}"`;
}

function normalizeDiskMetrics(value) {
  if (!value) return [];
  if (Array.isArray(value)) return value.filter(Boolean);
  if (typeof value === "string") {
    try {
      const parsed = JSON.parse(value);
      return Array.isArray(parsed) ? parsed.filter(Boolean) : [];
    } catch (error) {
      return [];
    }
  }
  return [];
}

function normalizePhysicalDiskMetrics(value, mountDisks = []) {
  const direct = normalizeDiskMetrics(value);
  if (direct.length) return direct;
  return derivePhysicalDisksFromMounts(mountDisks);
}

function derivePhysicalDisksFromMounts(mountDisks = []) {
  const disksByName = new Map();
  (mountDisks || []).forEach((mountDisk) => {
    const name = mountDisk.physical_disk_device
      || (mountDisk.physical_disk_name ? `/dev/${mountDisk.physical_disk_name}` : "")
      || diskDeviceFromFilesystem(mountDisk.filesystem);
    if (!name) return;
    if (!disksByName.has(name)) {
      disksByName.set(name, {
        name: name.replace(/^\/dev\//, ""),
        device: name,
        total_bytes: 0,
        mount_points: [],
        mounted: true,
      });
    }
    const disk = disksByName.get(name);
    const physicalTotal = numericMetric(mountDisk.physical_disk_total_bytes);
    const mountTotal = numericMetric(mountDisk.total_bytes);
    if (physicalTotal !== null) {
      disk.total_bytes = Math.max(numericMetric(disk.total_bytes) || 0, physicalTotal);
    } else if (mountTotal !== null) {
      disk.total_bytes = (numericMetric(disk.total_bytes) || 0) + mountTotal;
    }
    if (mountDisk.mount && !disk.mount_points.includes(mountDisk.mount)) {
      disk.mount_points.push(mountDisk.mount);
    }
  });
  return Array.from(disksByName.values()).map((disk) => ({
    ...disk,
    total_bytes: disk.total_bytes || null,
  }));
}

function diskDeviceFromFilesystem(filesystem) {
  const value = String(filesystem || "").trim();
  if (!value.startsWith("/dev/")) return "";
  const name = value.split("/").pop().replace(/[0-9]+$/g, "").replace(/p$/g, "");
  if (!name || /^sr[0-9]+$/i.test(name)) return "";
  return `/dev/${name}`;
}

function physicalDiskCapacityOverview(physicalDisks, mountDisks = []) {
  const disks = physicalDisks && physicalDisks.length ? physicalDisks : derivePhysicalDisksFromMounts(mountDisks);
  return disks.reduce((result, disk) => {
    result.totalBytes += numericMetric(disk.total_bytes) || 0;
    return result;
  }, { totalBytes: 0 });
}

function uniqueDiskNames(disks) {
  const names = new Set();
  (disks || []).forEach((disk) => {
    const filesystem = String(disk.filesystem || "").trim();
    if (!filesystem || filesystem === "-") return;
    const name = filesystem.split("/").pop().replace(/[0-9]+$/g, "").replace(/p$/g, "");
    if (/^sr[0-9]+$/i.test(name)) return;
    if (name) names.add(name);
  });
  return Array.from(names);
}

function numericMetric(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function percentText(value) {
  const number = numericMetric(value);
  return number === null ? "-" : `${Math.round(number * 10) / 10}%`;
}

function formatThreshold(value) {
  const number = numericMetric(value);
  return number === null ? "-" : `${Number(number).toFixed(number % 1 ? 1 : 0)}%`;
}

function formatAlertThreshold(value, enabled = true) {
  return enabled === false ? "关闭" : formatThreshold(value);
}

function formatDurationSetting(seconds, enabled = true) {
  return enabled === false ? "关闭" : formatCheckInterval(seconds);
}

function formatCoreCount(value) {
  const number = numericMetric(value);
  return number === null ? "-" : `${Math.round(number)} 核`;
}

function formatMemorySize(value) {
  const number = numericMetric(value);
  if (number === null) return "-";
  if (number >= 1024) {
    return `${Math.round((number / 1024) * 10) / 10} GB`;
  }
  return `${Math.round(number)} MB`;
}

function formatBytes(value) {
  const number = numericMetric(value);
  if (number === null) return "-";
  const units = ["B", "KB", "MB", "GB", "TB", "PB"];
  let size = Math.max(0, number);
  let unitIndex = 0;
  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024;
    unitIndex++;
  }
  const precision = size >= 10 || unitIndex === 0 ? 0 : 1;
  return `${size.toFixed(precision)} ${units[unitIndex]}`;
}
