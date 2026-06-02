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
  document.getElementById("exportHostMetricsBtn")?.addEventListener("click", exportSelectedHostMetricHistory);
  document.getElementById("hostModal")?.addEventListener("click", (event) => {
    if (event.target.id === "hostModal") closeHostModal();
  });
  document.getElementById("hostDetailModal")?.addEventListener("click", (event) => {
    if (event.target.id === "hostDetailModal") closeHostDetailModal();
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

async function loadSelectedHostMetrics(refresh = false) {
  if (!hostState.selectedHostId) {
    showToast("请先选择主机");
    return;
  }
  if (hostState.metricView === "7d") {
    return;
  }
  renderHostMetricLoading();
  try {
    hostState.metrics = refresh
      ? await LiveMonitorApi.refreshHostMetrics(hostState.selectedHostId)
      : await LiveMonitorApi.hostMetrics(hostState.selectedHostId);
    renderHostMetrics(hostState.metrics);
    await loadHosts();
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
    setHostMetricText("hostMemoryHint", "Memory used");
    setHostMetricText("hostDiskValue", "-");
    renderHostDiskMounts([]);
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
  setHostMetricText("hostMemoryHint", "Memory used");
  setHostMetricText("hostDiskValue", disk === null ? "-" : `${disk.toFixed(1)}%`);
  renderHostDiskMounts(normalizeDiskMetrics(metrics.disk_metrics || metrics.disk_metrics_json));
  drawHostMetricCharts();
}

function renderHostMetricLoading() {
  setHostMetricText("hostCpuValue", "...");
  setHostMetricText("hostLoadValue", "Load ...");
  setHostMetricText("hostMemoryValue", "...");
  setHostMetricText("hostMemoryHint", "读取中...");
  setHostMetricText("hostDiskValue", "...");
  setHostMetricText("hostDiskHint", "扫描挂载磁盘...");
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
    return;
  }
  renderHostMetricHistoryLoading();
  try {
    const rows = await LiveMonitorApi.hostMetricHistory(hostState.selectedHostId, 7, 10000);
    hostState.metricHistoryRows = rows || [];
    renderHostMetricHistory(hostState.metricHistoryRows);
  } catch (error) {
    showToast(error.message || "近7天指标加载失败");
    renderHostMetricHistory([]);
  }
}

function renderHostMetricHistoryLoading() {
  setHostMetricText("hostCpuValue", "...");
  setHostMetricText("hostLoadValue", "近7天 ...");
  setHostMetricText("hostMemoryValue", "...");
  setHostMetricText("hostMemoryHint", "近7天 ...");
  setHostMetricText("hostDiskValue", "...");
  setHostMetricText("hostDiskHint", "近7天 ...");
  renderHostDiskMounts([]);
}

function renderHostMetricHistory(rows) {
  const normalized = (rows || []).filter(Boolean);
  resetHostMetricHistory();
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
  setHostMetricText("hostMemoryHint", countText);
  setHostMetricText("hostDiskValue", latestDisk === null ? "-" : `${latestDisk.toFixed(1)}%`);
  renderHostDiskMounts(latest ? normalizeDiskMetrics(latest.disk_metrics || latest.disk_metrics_json) : []);
  drawHostMetricCharts();
}

function metricSeries(rows, key) {
  return rows.map((row) => numericMetric(row[key])).map((value) => value === null ? 0 : value);
}

function renderHostDiskMounts(disks) {
  const list = document.getElementById("hostDiskMountList");
  const hint = document.getElementById("hostDiskHint");
  if (!list) return;
  if (!disks.length) {
    list.innerHTML = '<span class="host-disk-empty">暂无挂载磁盘数据</span>';
    if (hint) hint.textContent = "挂载磁盘";
    return;
  }
  const sorted = [...disks].sort((a, b) => numericMetric(b.used_percent) - numericMetric(a.used_percent));
  if (hint) hint.textContent = `挂载磁盘 ${sorted.length} 个 / 最高 ${percentText(sorted[0].used_percent)}`;
  list.innerHTML = sorted.map((disk) => {
    const used = numericMetric(disk.used_percent);
    const percent = used === null ? 0 : Math.max(0, Math.min(100, used));
    const mount = disk.mount || "-";
    const filesystem = disk.filesystem || "-";
    return `
      <div class="host-disk-mount">
        <span title="${escapeHtml(mount)}">${escapeHtml(mount)}</span>
        <b><i style="width:${percent}%"></i></b>
        <em>${percentText(used)}</em>
        <small title="${escapeHtml(filesystem)}">${escapeHtml(filesystem)}</small>
      </div>
    `;
  }).join("");
}

function resetHostMetricHistory() {
  hostState.metricHistory = { cpu: [], load: [], memory: [], disk: [] };
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
  drawLineChart("hostDiskChart", hostState.metricHistory.disk, "#7c3aed", 100);
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
