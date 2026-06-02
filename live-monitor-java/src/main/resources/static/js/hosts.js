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
  document.getElementById("hostRealtimeMetricsBtn")?.addEventListener("click", () => setHostMetricView("realtime"));
  document.getElementById("hostSevenDayMetricsBtn")?.addEventListener("click", () => setHostMetricView("7d"));
  document.getElementById("hostRefreshMetricsBtn")?.addEventListener("click", refreshSelectedHostMetrics);
  document.getElementById("exportHostMetricsBtn")?.addEventListener("click", exportSelectedHostMetricHistory);
  bindHostMetricChartEvents("hostCpuChart", "cpu");
  bindHostMetricChartEvents("hostMemoryChart", "memory");
  bindHostMetricChartEvents("hostChartZoomCanvas", "zoom");
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
          <span>磁盘阈值 <strong>${formatAlertThreshold(host.disk_threshold_percent, host.disk_alert_enabled)}</strong></span>
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
  const diskCount = numericMetric(host.disk_mount_count);
  const disks = normalizeDiskMetrics(host.disk_metrics || host.disk_metrics_json);
  const uniqueDiskCount = diskCount !== null ? diskCount : (uniqueDiskNames(disks).length || null);
  return `
    <div class="host-config-info-stack">
      <span><i data-lucide="cpu"></i>CPU <strong>${formatCoreCount(host.cpu_core_count)}</strong></span>
      <span><i data-lucide="memory-stick"></i>内存 <strong>${formatMemorySize(host.memory_total_mb)}</strong></span>
      <span><i data-lucide="hard-drive"></i>磁盘 <strong>${uniqueDiskCount === null ? "-" : `${uniqueDiskCount} 块`}</strong></span>
    </div>
  `;
}

function renderResourceStack(host) {
  const disks = normalizeDiskMetrics(host.disk_metrics || host.disk_metrics_json);
  const diskLabel = disks.length ? `磁盘(${disks.length})` : "磁盘";
  return `
    <div class="host-resource-stack">
      ${renderResourceLine("cpu", "CPU", host.cpu_usage_percent, host.cpu_threshold_percent, host.cpu_alert_enabled)}
      ${renderResourceLine("memory-stick", "内存", host.memory_used_percent, host.memory_threshold_percent, host.memory_alert_enabled)}
      ${renderDiskResourceBlock(host, disks, diskLabel)}
    </div>
  `;
}

function renderResourceLine(icon, label, rawValue, threshold = null, thresholdEnabled = true) {
  const value = numericMetric(rawValue);
  const percent = value === null ? 0 : Math.max(0, Math.min(100, value));
  const warn = thresholdEnabled !== false && threshold !== null && threshold !== undefined && value !== null && value >= Number(threshold);
  return `
    <div class="host-resource-line ${warn ? "warn" : ""}">
      <span><i data-lucide="${icon}"></i>${label}</span>
      <b><i style="width:${percent}%"></i></b>
      <em>${value === null ? "-" : `${Math.round(value)}%`}</em>
    </div>
  `;
}

function renderDiskResourceBlock(host, disks, diskLabel) {
  const expanded = hostState.expandedDiskHostIds?.has(Number(host.id));
  const value = numericMetric(host.disk_used_percent);
  const percent = value === null ? 0 : Math.max(0, Math.min(100, value));
  const threshold = numericMetric(host.disk_threshold_percent);
  const warn = host.disk_alert_enabled !== false && threshold !== null && value !== null && value >= threshold;
  const listId = `hostDiskMounts${host.id}`;
  return `
    <div class="host-resource-disk-block">
      <button class="host-resource-line host-resource-toggle ${warn ? "warn" : ""}" type="button"
        onclick="toggleHostDiskMounts(${host.id})" aria-expanded="${expanded}" aria-controls="${listId}"
        title="${expanded ? "收起磁盘挂载" : "展开磁盘挂载"}">
        <span><i data-lucide="hard-drive"></i>${diskLabel}<i class="host-resource-chevron ${expanded ? "open" : ""}" data-lucide="chevron-down"></i></span>
        <b><i style="width:${percent}%"></i></b>
        <em>${value === null ? "-" : `${Math.round(value)}%`}</em>
      </button>
      ${expanded ? renderInlineDiskMounts(disks, listId) : ""}
    </div>
  `;
}

function renderInlineDiskMounts(disks, listId) {
  if (!disks.length) {
    return `<div id="${listId}" class="host-inline-disk-list"><span class="host-inline-disk-empty">暂无挂载磁盘数据</span></div>`;
  }
  const sorted = [...disks].sort((a, b) => (numericMetric(b.used_percent) || 0) - (numericMetric(a.used_percent) || 0));
  return `
    <div id="${listId}" class="host-inline-disk-list">
      ${sorted.map((disk) => {
        const used = numericMetric(disk.used_percent);
        const percent = used === null ? 0 : Math.max(0, Math.min(100, used));
        const mount = disk.mount || "-";
        const filesystem = disk.filesystem || "-";
        return `
          <div class="host-inline-disk-row">
            <span title="${escapeHtml(mount)}"><i data-lucide="hard-drive"></i>${escapeHtml(mount)}</span>
            <b><i style="width:${percent}%"></i></b>
            <em>${percentText(used)}</em>
            <small title="${escapeHtml(filesystem)}">${escapeHtml(filesystem)}</small>
          </div>
        `;
      }).join("")}
    </div>
  `;
}

function toggleHostDiskMounts(hostId) {
  const id = Number(hostId);
  if (!hostState.expandedDiskHostIds) hostState.expandedDiskHostIds = new Set();
  if (hostState.expandedDiskHostIds.has(id)) {
    hostState.expandedDiskHostIds.delete(id);
  } else {
    hostState.expandedDiskHostIds.add(id);
  }
  renderHostTable();
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
  if ((host.cpu_alert_enabled !== false && cpu !== null && cpuThreshold !== null && cpu >= cpuThreshold)
    || (host.memory_alert_enabled !== false && memory !== null && memoryThreshold !== null && memory >= memoryThreshold)
    || (host.disk_alert_enabled !== false && disk !== null && diskThreshold !== null && disk >= diskThreshold)) {
    let detail = "磁盘使用率高";
    if (host.cpu_alert_enabled !== false && cpu !== null && cpuThreshold !== null && cpu >= cpuThreshold) {
      detail = "CPU 使用率高";
    } else if (host.memory_alert_enabled !== false && memory !== null && memoryThreshold !== null && memory >= memoryThreshold) {
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
  form.elements.cpu_alert_enabled.checked = host ? host.cpu_alert_enabled !== false : true;
  form.elements.memory_alert_enabled.checked = host ? host.memory_alert_enabled !== false : true;
  form.elements.disk_alert_enabled.checked = host ? host.disk_alert_enabled !== false : true;
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
  await selectHost(id);
  const modal = document.getElementById("hostDetailModal");
  if (modal) modal.hidden = false;
  if (window.lucide) window.lucide.createIcons();
  await loadSelectedHostMetrics(true);
  startHostMetricAutoRefresh();
}

function closeHostDetailModal() {
  stopHostMetricAutoRefresh();
  closeHostChartZoomModal();
  hideHostChartTooltip("hostChartTooltip");
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
      ? `${host.ip || "-"} / ${host.cluster_name || "服务器主机"} / CPU ${formatAlertThreshold(host.cpu_threshold_percent, host.cpu_alert_enabled)} / 内存 ${formatAlertThreshold(host.memory_threshold_percent, host.memory_alert_enabled)} / 磁盘 ${formatAlertThreshold(host.disk_threshold_percent, host.disk_alert_enabled)} / ${formatCheckInterval(host.check_interval)}自动刷新`
      : "打开详情后按主机检测间隔自动刷新"
  );
}

async function refreshSelectedHostMetrics() {
  if (!hostState.selectedHostId || hostState.metricRefreshing) {
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
      if (await loadSelectedHostMetricHistory()) showToast("主机指标已刷新");
      await loadHosts();
    } else {
      if (await loadSelectedHostMetrics(true)) showToast("主机指标已刷新");
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

async function loadSelectedHostMetrics(refresh = false) {
  if (!hostState.selectedHostId) {
    showToast("请先选择主机");
    return false;
  }
  if (hostState.metricView === "7d") {
    return false;
  }
  renderHostMetricLoading();
  try {
    hostState.metrics = refresh
      ? await LiveMonitorApi.refreshHostMetrics(hostState.selectedHostId)
      : await LiveMonitorApi.hostMetrics(hostState.selectedHostId);
    renderHostMetrics(hostState.metrics);
    await loadHosts();
    return true;
  } catch (error) {
    showToast(error.message);
    renderHostMetrics({ cpu: error.message, memory: "-", disk: "-" });
    return false;
  }
}

function renderHostMetrics(metrics) {
  if (!metrics) {
    setHostMetricText("hostCpuValue", "-");
    setHostMetricText("hostLoadValue", "Load -");
    setHostMetricText("hostMemoryValue", "-");
    setHostMetricText("hostMemoryHint", "Memory used");
    setHostMetricText("hostMemoryRemain", "-");
    setHostMetricText("hostDiskValue", "-");
    setHostMetricText("hostDiskHint", "挂载点使用率");
    setHostMetricText("hostDiskCapacity", "-");
    renderHostDiskMounts([]);
    drawHostMetricCharts();
    return;
  }
  const cpu = numericMetric(metrics.cpu_usage_percent);
  const load = numericMetric(metrics.load_average);
  const memory = numericMetric(metrics.memory_used_percent);
  const disk = numericMetric(metrics.disk_used_percent);
  const label = formatChartTickLabel(metrics.checked_at || metrics.checkedAt || new Date(), hostState.metricHistory.labels.length);
  if ([cpu, load, memory, disk].some((value) => value !== null)) {
    pushHostMetricLabel(label);
  }
  pushHostMetric("cpu", cpu);
  pushHostMetric("load", load);
  pushHostMetric("memory", memory);
  pushHostMetric("disk", disk);
  setHostMetricText("hostCpuValue", cpu === null ? "-" : `${cpu.toFixed(1)}%`);
  setHostMetricText("hostLoadValue", load === null ? "Load -" : `Load ${load.toFixed(2)}`);
  setHostMetricText("hostMemoryValue", memory === null ? "-" : `${memory.toFixed(1)}%`);
  renderHostMemoryCapacity(memory, metrics);
  setHostMetricText("hostDiskValue", disk === null ? "-" : `${disk.toFixed(1)}%`);
  renderHostDiskMounts(normalizeDiskMetrics(metrics.disk_metrics || metrics.disk_metrics_json));
  drawHostMetricCharts();
}

function renderHostMetricLoading() {
  setHostMetricText("hostCpuValue", "...");
  setHostMetricText("hostLoadValue", "Load ...");
  setHostMetricText("hostMemoryValue", "...");
  setHostMetricText("hostMemoryHint", "读取中...");
  setHostMetricText("hostMemoryRemain", "-");
  setHostMetricText("hostDiskValue", "...");
  setHostMetricText("hostDiskHint", "扫描挂载磁盘...");
  setHostMetricText("hostDiskCapacity", "-");
  const list = document.getElementById("hostDiskMountList");
  if (list) list.innerHTML = "";
}

async function setHostMetricView(view) {
  hostState.metricView = view;
  updateHostMetricModeButtons();
  if (view === "7d") {
    await loadSelectedHostMetricHistory();
    return;
  }
  resetHostMetricHistory();
  renderHostMetrics(null);
  await loadSelectedHostMetrics(false);
}

function updateHostMetricModeButtons() {
  const realtime = document.getElementById("hostRealtimeMetricsBtn");
  const sevenDay = document.getElementById("hostSevenDayMetricsBtn");
  const view = hostState.metricView || "realtime";
  realtime?.classList.toggle("active", view === "realtime");
  sevenDay?.classList.toggle("active", view === "7d");
}

async function loadSelectedHostMetricHistory() {
  if (!hostState.selectedHostId) {
    showToast("请先选择主机");
    return false;
  }
  renderHostMetricHistoryLoading();
  try {
    const rows = await LiveMonitorApi.hostMetricHistory(hostState.selectedHostId, 7, 10000);
    hostState.metricHistoryRows = rows || [];
    renderHostMetricHistory(hostState.metricHistoryRows);
    return true;
  } catch (error) {
    showToast(error.message || "近7天指标加载失败");
    renderHostMetricHistory([]);
    return false;
  }
}

function renderHostMetricHistoryLoading() {
  setHostMetricText("hostCpuValue", "...");
  setHostMetricText("hostLoadValue", "近7天 ...");
  setHostMetricText("hostMemoryValue", "...");
  setHostMetricText("hostMemoryHint", "近7天 ...");
  setHostMetricText("hostMemoryRemain", "-");
  setHostMetricText("hostDiskValue", "...");
  setHostMetricText("hostDiskHint", "近7天 ...");
  setHostMetricText("hostDiskCapacity", "-");
  renderHostDiskMounts([]);
}

function renderHostMetricHistory(rows) {
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
  const latestLoad = latest ? numericMetric(latest.load_average) : null;
  const latestMemory = latest ? numericMetric(latest.memory_used_percent) : null;
  const latestDisk = latest ? numericMetric(latest.disk_used_percent) : null;
  const countText = `近7天 ${normalized.length} 条`;

  setHostMetricText("hostCpuValue", latestCpu === null ? "-" : `${latestCpu.toFixed(1)}%`);
  setHostMetricText("hostLoadValue", latestLoad === null ? countText : `${countText} / Load ${latestLoad.toFixed(2)}`);
  setHostMetricText("hostMemoryValue", latestMemory === null ? "-" : `${latestMemory.toFixed(1)}%`);
  renderHostMemoryCapacity(latestMemory, latest || {});
  setHostMetricText("hostDiskValue", latestDisk === null ? "-" : `${latestDisk.toFixed(1)}%`);
  renderHostDiskMounts(latest ? normalizeDiskMetrics(latest.disk_metrics || latest.disk_metrics_json) : []);
  drawHostMetricCharts();
}

function metricSeries(rows, key) {
  return rows.map((row) => numericMetric(row[key])).map((value) => value === null ? 0 : value);
}

function renderHostMemoryCapacity(memoryPercent, metrics = {}) {
  const totalMb = numericMetric(metrics.memory_total_mb) ?? numericMetric(selectedHost()?.memory_total_mb);
  if (memoryPercent === null || totalMb === null) {
    setHostMetricText("hostMemoryHint", "Memory used");
    setHostMetricText("hostMemoryRemain", "-");
    return;
  }
  const usedMb = totalMb * memoryPercent / 100;
  const freeMb = Math.max(0, totalMb - usedMb);
  setHostMetricText("hostMemoryHint", `${formatMemorySize(usedMb)} / ${formatMemorySize(totalMb)}`);
  setHostMetricText("hostMemoryRemain", `剩余 ${formatMemorySize(freeMb)}`);
}

function renderHostDiskMounts(disks) {
  const list = document.getElementById("hostDiskMountList");
  const hint = document.getElementById("hostDiskHint");
  if (!list) return;
  if (!disks.length) {
    list.innerHTML = '<span class="host-disk-empty">暂无挂载磁盘数据</span>';
    if (hint) hint.textContent = "挂载磁盘";
    setHostMetricText("hostDiskCapacity", "-");
    return;
  }
  const sorted = [...disks].sort((a, b) => numericMetric(b.used_percent) - numericMetric(a.used_percent));
  const overview = diskCapacityOverview(disks);
  if (hint) hint.textContent = `挂载点 ${sorted.length} 个 / 最高 ${percentText(sorted[0].used_percent)}`;
  setHostMetricText(
    "hostDiskCapacity",
    overview.totalBytes ? `${formatBytes(overview.usedBytes)} / ${formatBytes(overview.totalBytes)}` : "-"
  );
  list.innerHTML = sorted.slice(0, 5).map((disk) => {
    const used = numericMetric(disk.used_percent);
    const percent = used === null ? 0 : Math.max(0, Math.min(100, used));
    const mount = disk.mount || "-";
    const filesystem = disk.filesystem || "-";
    const total = numericMetric(disk.total_bytes);
    const usedBytes = numericMetric(disk.used_bytes);
    const detail = total === null || usedBytes === null
      ? filesystem
      : `${filesystem} / ${formatBytes(usedBytes)} of ${formatBytes(total)}`;
    return `
      <div class="host-disk-mount">
        <span title="${escapeHtml(mount)}">${escapeHtml(mount)}</span>
        <b><i style="width:${percent}%"></i></b>
        <em>${percentText(used)}</em>
        <small title="${escapeHtml(detail)}">${escapeHtml(detail)}</small>
      </div>
    `;
  }).join("");
}

function diskCapacityOverview(disks) {
  return (disks || []).reduce((result, disk) => {
    result.totalBytes += numericMetric(disk.total_bytes) || 0;
    result.usedBytes += numericMetric(disk.used_bytes) || 0;
    result.availableBytes += numericMetric(disk.available_bytes) || 0;
    return result;
  }, { totalBytes: 0, usedBytes: 0, availableBytes: 0 });
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
  drawHostMetricChart("hostCpuChart", hostMetricChartDefinition("cpu"), { compact: true });
  drawHostMetricChart("hostMemoryChart", hostMetricChartDefinition("memory"), { compact: true });
  drawExpandedHostMetricChart();
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
  const maxValue = definition.key === "cpu"
    ? scaledPercentMax(definition.values, 10)
    : 100;
  return {
    ...definition,
    labels: labels.slice(Math.max(0, labels.length - definition.values.length)),
    maxValue,
    yTicks: percentTicks(maxValue),
  };
}

function drawHostMetricChart(canvasId, definition, options = {}) {
  const canvas = document.getElementById(canvasId);
  if (!canvas || !definition) return null;
  const rect = canvas.getBoundingClientRect();
  const pixelRatio = window.devicePixelRatio || 1;
  const cssWidth = Math.max(260, rect.width || canvas.width || 320);
  const cssHeight = Math.max(options.zoom ? 320 : 180, rect.height || canvas.height || 180);
  if (canvas.width !== Math.round(cssWidth * pixelRatio) || canvas.height !== Math.round(cssHeight * pixelRatio)) {
    canvas.width = Math.round(cssWidth * pixelRatio);
    canvas.height = Math.round(cssHeight * pixelRatio);
  }
  const ctx = canvas.getContext("2d");
  ctx.save();
  ctx.scale(pixelRatio, pixelRatio);
  ctx.clearRect(0, 0, cssWidth, cssHeight);

  const layout = {
    left: options.compact ? 42 : 50,
    right: 14,
    top: 16,
    bottom: options.compact ? 36 : 42,
  };
  const plot = {
    x: layout.left,
    y: layout.top,
    width: Math.max(1, cssWidth - layout.left - layout.right),
    height: Math.max(1, cssHeight - layout.top - layout.bottom),
  };
  const values = (definition.values || []).map((value) => numericMetric(value)).filter((value) => value !== null);
  const labels = labelsForChart(definition.labels, values.length);

  ctx.font = "12px Microsoft YaHei, Arial, sans-serif";
  ctx.textBaseline = "middle";
  ctx.lineWidth = 1;
  ctx.strokeStyle = "#e2eaf2";
  ctx.fillStyle = "#64748b";
  (definition.yTicks || [0, 50, 100]).forEach((tick) => {
    const y = valueToChartY(tick, plot, definition.maxValue);
    ctx.beginPath();
    ctx.moveTo(plot.x, y);
    ctx.lineTo(plot.x + plot.width, y);
    ctx.stroke();
    ctx.textAlign = "right";
    ctx.fillText(`${tick}%`, plot.x - 8, y);
  });

  ctx.strokeStyle = "#cbd8e5";
  ctx.beginPath();
  ctx.moveTo(plot.x, plot.y);
  ctx.lineTo(plot.x, plot.y + plot.height);
  ctx.lineTo(plot.x + plot.width, plot.y + plot.height);
  ctx.stroke();

  drawHostChartXTicks(ctx, plot, labels);
  drawHostThresholdLine(ctx, plot, definition);

  if (!values.length) {
    ctx.fillStyle = "#94a3b8";
    ctx.textAlign = "center";
    ctx.fillText("暂无数据", plot.x + plot.width / 2, plot.y + plot.height / 2);
    ctx.restore();
    canvas.__hostChartLayout = { plot, values, labels, definition };
    return canvas.__hostChartLayout;
  }

  const points = values.map((value, index) => ({
    value,
    label: labels[index] || `#${index + 1}`,
    x: chartIndexToX(index, values.length, plot),
    y: valueToChartY(value, plot, definition.maxValue),
  }));

  ctx.beginPath();
  points.forEach((point, index) => {
    if (index === 0) ctx.moveTo(point.x, point.y);
    else ctx.lineTo(point.x, point.y);
  });
  ctx.strokeStyle = definition.color;
  ctx.lineWidth = options.zoom ? 3 : 2.5;
  ctx.lineCap = "round";
  ctx.lineJoin = "round";
  ctx.stroke();

  if (points.length === 1) {
    drawHostChartPoint(ctx, points[0], definition.color, false);
  }

  const hoverIndex = Number.isInteger(options.hoverIndex) ? options.hoverIndex : null;
  if (hoverIndex !== null && points[hoverIndex]) {
    const point = points[hoverIndex];
    ctx.strokeStyle = "rgba(15, 23, 42, 0.22)";
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(point.x, plot.y);
    ctx.lineTo(point.x, plot.y + plot.height);
    ctx.stroke();
    drawHostChartPoint(ctx, point, definition.color, true);
  }

  ctx.restore();
  canvas.__hostChartLayout = { plot, values, labels, points, definition };
  return canvas.__hostChartLayout;
}

function drawHostChartXTicks(ctx, plot, labels) {
  const tickIndexes = chartTickIndexes(labels.length);
  ctx.fillStyle = "#64748b";
  ctx.textBaseline = "top";
  tickIndexes.forEach((index) => {
    const x = chartIndexToX(index, labels.length, plot);
    ctx.strokeStyle = "#edf2f7";
    ctx.beginPath();
    ctx.moveTo(x, plot.y + plot.height);
    ctx.lineTo(x, plot.y + plot.height + 4);
    ctx.stroke();
    ctx.textAlign = index === 0 ? "left" : (index === labels.length - 1 ? "right" : "center");
    ctx.fillText(labels[index] || `#${index + 1}`, x, plot.y + plot.height + 8);
  });
}

function drawHostThresholdLine(ctx, plot, definition) {
  const threshold = numericMetric(definition.threshold);
  if (threshold === null) return;
  const clipped = Math.min(threshold, definition.maxValue);
  const y = valueToChartY(clipped, plot, definition.maxValue);
  ctx.save();
  ctx.setLineDash([5, 4]);
  ctx.strokeStyle = "#dc2626";
  ctx.lineWidth = 1.2;
  ctx.beginPath();
  ctx.moveTo(plot.x, y);
  ctx.lineTo(plot.x + plot.width, y);
  ctx.stroke();
  ctx.setLineDash([]);
  ctx.fillStyle = "#dc2626";
  ctx.font = "12px Microsoft YaHei, Arial, sans-serif";
  ctx.textAlign = "right";
  ctx.textBaseline = threshold > definition.maxValue ? "top" : "bottom";
  ctx.fillText(`阈值 ${formatThreshold(threshold)}`, plot.x + plot.width, y + (threshold > definition.maxValue ? 3 : -4));
  ctx.restore();
}

function chartTickIndexes(length) {
  if (length <= 0) return [];
  if (length === 1) return [0];
  const middle = Math.floor((length - 1) / 2);
  return Array.from(new Set([0, middle, length - 1]));
}

function labelsForChart(labels, length) {
  const result = (labels || []).slice(Math.max(0, (labels || []).length - length));
  if (result.length >= length) return result;
  const missing = length - result.length;
  return Array.from({ length: missing }, (_, index) => `#${index + 1}`).concat(result);
}

function chartIndexToX(index, length, plot) {
  if (length <= 1) return plot.x + plot.width / 2;
  return plot.x + (plot.width / (length - 1)) * index;
}

function valueToChartY(value, plot, maxValue = 100) {
  const bounded = Math.max(0, Math.min(Number(value) || 0, maxValue));
  return plot.y + plot.height - (bounded / maxValue) * plot.height;
}

function scaledPercentMax(values, minimum = 10) {
  const max = Math.max(0, ...(values || []).map((value) => numericMetric(value) || 0));
  const raw = Math.max(minimum, max * 1.2);
  if (raw <= 10) return 10;
  if (raw <= 20) return Math.ceil(raw / 2) * 2;
  if (raw <= 50) return Math.ceil(raw / 5) * 5;
  return Math.min(100, Math.ceil(raw / 10) * 10);
}

function percentTicks(maxValue) {
  const mid = Math.round((maxValue / 2) * 10) / 10;
  return [0, mid, maxValue];
}

function drawHostChartPoint(ctx, point, color, active) {
  ctx.beginPath();
  ctx.arc(point.x, point.y, active ? 4.5 : 3.5, 0, Math.PI * 2);
  ctx.fillStyle = "#fff";
  ctx.fill();
  ctx.lineWidth = active ? 3 : 2;
  ctx.strokeStyle = color;
  ctx.stroke();
}

function bindHostMetricChartEvents(canvasId, chartKey) {
  const canvas = document.getElementById(canvasId);
  if (!canvas) return;
  canvas.addEventListener("mousemove", (event) => handleHostChartHover(event, canvas, chartKey));
  canvas.addEventListener("mouseleave", () => {
    hideHostChartTooltip(chartKey === "zoom" ? "hostChartZoomTooltip" : "hostChartTooltip");
    redrawHostChartWithoutHover(chartKey);
  });
  canvas.addEventListener("click", () => {
    const key = chartKey === "zoom" ? hostState.expandedMetricChart : chartKey;
    if (key) openHostChartZoomModal(key);
  });
}

function handleHostChartHover(event, canvas, chartKey) {
  const key = chartKey === "zoom" ? hostState.expandedMetricChart : chartKey;
  if (!key) return;
  const definition = hostMetricChartDefinition(key);
  const layout = canvas.__hostChartLayout || drawHostMetricChart(canvas.id, definition, { zoom: chartKey === "zoom", compact: chartKey !== "zoom" });
  if (!layout || !layout.points || !layout.points.length) return;
  const rect = canvas.getBoundingClientRect();
  const scaleX = canvas.width / (window.devicePixelRatio || 1) / rect.width;
  const mouseX = (event.clientX - rect.left) * scaleX;
  const nearest = layout.points.reduce((best, point, index) => {
    const distance = Math.abs(point.x - mouseX);
    return distance < best.distance ? { index, distance } : best;
  }, { index: 0, distance: Infinity });
  const options = { hoverIndex: nearest.index, zoom: chartKey === "zoom", compact: chartKey !== "zoom" };
  drawHostMetricChart(canvas.id, definition, options);
  const point = (canvas.__hostChartLayout.points || [])[nearest.index];
  showHostChartTooltip(
    chartKey === "zoom" ? "hostChartZoomTooltip" : "hostChartTooltip",
    event,
    definition,
    point
  );
}

function redrawHostChartWithoutHover(chartKey) {
  if (chartKey === "zoom") {
    drawExpandedHostMetricChart();
    return;
  }
  drawHostMetricChart(hostMetricCanvasId(chartKey), hostMetricChartDefinition(chartKey), { compact: true });
}

function showHostChartTooltip(id, event, definition, point) {
  const tooltip = document.getElementById(id);
  if (!tooltip || !point) return;
  tooltip.innerHTML = `
    <strong>${escapeHtml(definition.title)}</strong>
    <span>${escapeHtml(point.label || "-")}</span>
    <em>${escapeHtml(definition.formatter(point.value))}</em>
  `;
  tooltip.hidden = false;
  const offset = 14;
  const x = Math.min(window.innerWidth - 150, event.clientX + offset);
  const y = Math.max(12, event.clientY - 54);
  tooltip.style.left = `${x}px`;
  tooltip.style.top = `${y}px`;
}

function hideHostChartTooltip(id) {
  const tooltip = document.getElementById(id);
  if (tooltip) tooltip.hidden = true;
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
  hideHostChartTooltip("hostChartZoomTooltip");
  hostState.expandedMetricChart = null;
}

function drawExpandedHostMetricChart() {
  if (!hostState.expandedMetricChart || document.getElementById("hostChartZoomModal")?.hidden) {
    return;
  }
  drawHostMetricChart("hostChartZoomCanvas", hostMetricChartDefinition(hostState.expandedMetricChart), { zoom: true });
}

function hostMetricCanvasId(key) {
  if (key === "memory") return "hostMemoryChart";
  return "hostCpuChart";
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

function startHostMetricAutoRefresh() {
  stopHostMetricAutoRefresh();
  const host = selectedHost();
  if (!host) return;
  const seconds = Math.max(1, Number(host.check_interval || 60));
  hostState.metricTimer = window.setInterval(() => loadSelectedHostMetrics(true), seconds * 1000);
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
  const header = ["采集时间", "CPU使用率", "Load", "内存使用率", "磁盘最高使用率", "挂载磁盘"];
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
  const header = ["主机名称", "IP", "分组", "SSH", "CPU使用率", "内存使用率", "磁盘使用率", "CPU核数", "内存总量", "磁盘数量", "CPU阈值", "内存阈值", "磁盘阈值", "检测间隔", "状态", "最后采集"];
  const body = rows.map((host) => [
    host.host_name || "",
    host.ip || "",
    host.cluster_name || "",
    `${host.ssh_user || ""}@${host.ip || ""}:${host.ssh_port || 22}`,
    percentText(host.cpu_usage_percent),
    percentText(host.memory_used_percent),
    percentText(host.disk_used_percent),
    formatCoreCount(host.cpu_core_count),
    formatMemorySize(host.memory_total_mb),
    numericMetric(host.disk_mount_count) === null ? "" : `${Math.round(Number(host.disk_mount_count))}`,
    formatAlertThreshold(host.cpu_threshold_percent, host.cpu_alert_enabled),
    formatAlertThreshold(host.memory_threshold_percent, host.memory_alert_enabled),
    formatAlertThreshold(host.disk_threshold_percent, host.disk_alert_enabled),
    formatCheckInterval(host.check_interval),
    hostStateView(host).label,
    formatTime(host.metric_checked_at) || "",
  ]);
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
