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
  setText("selectedHostMeta", host ? `${host.ip || "-"} / ${host.cluster_name || "服务器主机"} / CPU ${formatThreshold(host.cpu_threshold_percent)} / 磁盘 ${formatThreshold(host.disk_threshold_percent)}` : "选择表格中的主机后查看实时指标");
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
    showToast(id ? "主机已更" : "主机已添");
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
    showToast("主机已删");
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
  setText("processModalTitle", process ? "编辑进程检测命" : "添加进程检测命");
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
      : "打开详情后按主机检测间隔自动刷新"
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
    return { className: "warning", label: "采集" };
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
      : "打开详情后按主机检测间隔自动刷新"
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


