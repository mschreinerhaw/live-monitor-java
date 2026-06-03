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
    const isDatabase = isDatabaseServiceType(typeSelect.value);
    const isGenericJdbc = typeSelect.value === "jdbc";
    toggleFieldSet(".web-only", isWeb);
    toggleFieldSet(".host-field", !isWeb && !isGenericJdbc);
    toggleFieldSet(".port-field", !isWeb && !isProcess && !isGenericJdbc);
    toggleFieldSet(".process-only", isProcess);
    toggleFieldSet(".redis-only", isRedis);
    toggleFieldSet(".zookeeper-only", isZookeeper);
    toggleFieldSet(".database-only", isDatabase);
    toggleFieldSet(".jdbc-only", isGenericJdbc);
    toggleFieldSet(".standard-database-only", isDatabase && !isGenericJdbc);
    const urlInput = form.elements.url;
    const hostInput = form.elements.host;
    const portInput = form.elements.port;
    const databaseNameInput = form.elements.database_name;
    const jdbcDriverClassInput = form.elements.jdbc_driver_class;
    const jdbcUrlInput = form.elements.jdbc_url;
    const processNameInput = form.elements.process_name;
    const processKeywordInput = form.elements.process_match_keyword;
    const processCommandInput = form.elements.check_command;
    if (urlInput) urlInput.required = isWeb;
    if (hostInput) hostInput.required = !isWeb && !isProcess && !isGenericJdbc;
    if (portInput) portInput.required = !isWeb && !isDatabase && !isProcess;
    if (processNameInput) processNameInput.required = isProcess;
    if (processKeywordInput) processKeywordInput.required = false;
    if (processCommandInput) processCommandInput.required = isProcess;
    if (databaseNameInput) databaseNameInput.required = typeSelect.value === "oracle";
    if (jdbcDriverClassInput) jdbcDriverClassInput.required = isGenericJdbc;
    if (jdbcUrlInput) jdbcUrlInput.required = isGenericJdbc;
    if (jdbcDriverClassInput) {
      jdbcDriverClassInput.placeholder = {
        mysql: "可选，MySQL 5.x：com.mysql.jdbc.Driver；MySQL 8.x：com.mysql.cj.jdbc.Driver",
        oracle: "可选，例如：oracle.jdbc.OracleDriver",
        postgresql: "可选，例如：org.postgresql.Driver",
        postgres: "可选，例如：org.postgresql.Driver",
        jdbc: "必填，例如：com.microsoft.sqlserver.jdbc.SQLServerDriver",
      }[typeSelect.value] || "可选，填写 lib 下驱动 jar 对应的 Driver 类";
    }
    if (portInput && !portInput.value) {
      portInput.placeholder = {
        port: "例如 8080",
        redis: "6379",
        zookeeper: "2181",
        mysql: "3306",
        oracle: "1521",
        postgresql: "5432",
        jdbc: "在 JDBC 连接串中填写端口",
        process: "已登录 SSH 主机 IP",
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
      showToast(editId ? "服务修改已保" : "服务已保");
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

function secondsToServiceMinuteSecondParts(seconds, fallbackSeconds = 60, allowZero = false) {
  const fallback = allowZero ? Math.max(0, Number(fallbackSeconds || 0)) : Math.max(1, Number(fallbackSeconds || 60));
  const normalized = Number.isFinite(Number(seconds)) ? Number(seconds) : fallback;
  const value = allowZero ? Math.max(0, normalized) : Math.max(1, normalized);
  if (value >= 60 && value % 60 === 0) return { value: value / 60, unit: "minutes" };
  return { value, unit: "seconds" };
}

function setServiceDurationField(form, valueName, unitName, seconds, fallbackSeconds, allowZero = false) {
  const parts = secondsToServiceMinuteSecondParts(seconds ?? fallbackSeconds, fallbackSeconds, allowZero);
  form.elements[valueName].value = parts.value;
  form.elements[unitName].value = parts.unit;
}

function serviceDurationFieldToSeconds(form, valueName, unitName, fallbackSeconds, allowZero = false) {
  const valueText = form.elements[valueName]?.value;
  const value = valueText === "" ? NaN : Number(valueText);
  if (!Number.isFinite(value)) return fallbackSeconds;
  const multiplier = form.elements[unitName]?.value === "minutes" ? 60 : 1;
  const min = allowZero ? 0 : 1;
  return Math.min(Math.max(min, Math.round(value * multiplier)), 31536000);
}

function buildServicePayload(form) {
  const data = Object.fromEntries(new FormData(form).entries());
  const serviceType = ["http", "https"].includes(data.service_type) ? "web" : data.service_type;
  data.enabled = form.elements.enabled.checked;
  data.service_alert_enabled = form.elements.service_alert_enabled?.checked !== false;
  data.ignore_ssl_verification = Boolean(form.elements.ignore_ssl_verification?.checked);
  data.redis_cluster_mode = Boolean(form.elements.redis_cluster_mode?.checked);
  const intervalValue = Number(data.check_interval_value || 1);
  const intervalUnit = data.check_interval_unit || "minutes";
  data.check_interval = intervalToSeconds(intervalValue, intervalUnit);
  data.check_interval_value = intervalValue;
  data.check_interval_unit = intervalUnit;
  data.check_timeout_seconds = Number(data.check_timeout_seconds || 3);
  data.service_consecutive_failures = Number(data.service_consecutive_failures || 3);
  data.service_recover_successes = Number(data.service_recover_successes || 2);
  data.service_alert_cooldown_seconds = serviceDurationFieldToSeconds(
    form,
    "service_alert_cooldown_seconds",
    "service_alert_cooldown_unit",
    600,
    true
  );
  data.port = data.port ? Number(data.port) : null;
  if (!data.port && serviceType === "mysql") data.port = 3306;
  if (!data.port && serviceType === "oracle") data.port = 1521;
  if (!data.port && serviceType === "postgresql") data.port = 5432;
  if (serviceType === "process" || serviceType === "jdbc") data.port = null;
  data.expected_status_code = data.expected_status_code ? Number(data.expected_status_code) : null;
  data.zookeeper_expected_nodes = data.zookeeper_expected_nodes ? Number(data.zookeeper_expected_nodes) : null;
  const isDatabase = isDatabaseServiceType(serviceType);
  const isGenericJdbc = serviceType === "jdbc";
  const isProcess = serviceType === "process";
  data.service_type = serviceType;
  const isWebUrl = isWebUrlServiceType(serviceType);
  data.url = isWebUrl ? normalizeWebUrl(data.url, data.web_scheme) : null;
  data.host = isWebUrl || isGenericJdbc ? null : data.host.trim();
  data.http_method = isWebUrl ? data.http_method || "GET" : "GET";
  data.response_keyword = isWebUrl ? data.response_keyword || null : null;
  data.expected_status_code = isWebUrl ? data.expected_status_code : null;
  data.ignore_ssl_verification = isWebUrl ? data.ignore_ssl_verification : false;
  data.database_name = isDatabase && !isGenericJdbc ? data.database_name || null : null;
  data.database_username = isDatabase ? data.database_username || null : null;
  data.database_password = isDatabase ? data.database_password || null : null;
  data.database_query = isDatabase ? data.database_query || null : null;
  data.expected_result = isDatabase ? data.expected_result || null : null;
  data.database_result_operator = isDatabase ? data.database_result_operator || "fuzzy" : "fuzzy";
  data.jdbc_driver_class = isDatabase ? data.jdbc_driver_class || null : null;
  data.jdbc_url = isGenericJdbc ? data.jdbc_url || null : null;
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
  data.monitor_reason = data.monitor_reason?.trim() || null;
  delete data.web_scheme;
  delete data.service_alert_cooldown_unit;
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
    "monitor_reason",
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
    "jdbc_driver_class",
    "jdbc_url",
    "expected_result",
    "database_result_operator",
    "service_consecutive_failures",
    "service_recover_successes",
    "service_alert_cooldown_seconds",
    "alert_group_id",
  ].forEach((name) => {
    if (form.elements[name]) {
      const defaults = {
        check_timeout_seconds: 3,
        http_method: "GET",
        database_result_operator: "fuzzy",
        process_match_mode: "fuzzy",
        check_command: service.check_command || service.process_check_command,
        process_min_instances: 1,
        zookeeper_check_command: "ruok",
        service_consecutive_failures: 3,
        service_recover_successes: 2,
        service_alert_cooldown_seconds: 600,
      };
      form.elements[name].value = name === "web_scheme"
        ? webSchemeFromUrl(service.url)
        : service[name] ?? defaults[name] ?? "";
    }
  });
  const intervalParts = secondsToIntervalParts(service.check_interval);
  if (form.elements.check_interval_value) {
    form.elements.check_interval_value.value = service.check_interval_value || intervalParts.value;
  }
  if (form.elements.check_interval_unit) {
    form.elements.check_interval_unit.value = service.check_interval_unit || intervalParts.unit;
  }
  if (form.elements.service_alert_cooldown_seconds && form.elements.service_alert_cooldown_unit) {
    setServiceDurationField(
      form,
      "service_alert_cooldown_seconds",
      "service_alert_cooldown_unit",
      service.service_alert_cooldown_seconds,
      600,
      true
    );
  }
  if (form.elements.redis_password) {
    form.elements.redis_password.value = "";
    form.elements.redis_password.placeholder = service.service_type === "redis"
      ? "留空则保持原密码"
      : "Redis AUTH 密码";
  }
  if (form.elements.database_password) {
    form.elements.database_password.value = "";
    form.elements.database_password.placeholder = isDatabaseServiceType(service.service_type)
      ? "留空则保持原密码"
      : "数据库连接密";
  }
  form.elements.enabled.checked = Boolean(service.enabled);
  if (form.elements.service_alert_enabled) {
    form.elements.service_alert_enabled.checked = service.service_alert_enabled !== false;
  }
  if (form.elements.redis_cluster_mode) {
    form.elements.redis_cluster_mode.checked = Boolean(service.redis_cluster_mode);
  }
  if (form.elements.ignore_ssl_verification) {
    form.elements.ignore_ssl_verification.checked = Boolean(service.ignore_ssl_verification);
  }
}

function isDatabaseServiceType(type) {
  return ["mysql", "oracle", "postgresql", "postgres", "jdbc"].includes(type);
}

function statusIconHtml(status) {
  const icon = {
    UP: "circle-check",
    DOWN: "triangle-alert",
    UNKNOWN: "circle-help",
  }[status] || "circle-help";
  return `<i data-lucide="${icon}"></i>`;
}

function responseSeverity(value) {
  if (value === null || value === undefined || value === "") return "unknown";
  const number = Number(value);
  if (!Number.isFinite(number)) return "unknown";
  if (number >= 1000) return "bad";
  if (number >= 500) return "warn";
  return "ok";
}

function responseStats(results) {
  const values = results
    .map((item) => item.response_time_ms)
    .filter((value) => value !== null && value !== undefined && value !== "")
    .map((value) => Number(value))
    .filter((value) => Number.isFinite(value))
    .sort((a, b) => a - b);
  if (!values.length) return null;
  const total = values.reduce((sum, value) => sum + value, 0);
  const p95Index = Math.min(values.length - 1, Math.ceil(values.length * 0.95) - 1);
  return {
    average: Math.round(total / values.length),
    p95: Math.round(values[p95Index]),
  };
}

function setStateClass(element, prefix, value) {
  if (!element) return;
  ["UP", "DOWN", "UNKNOWN", "ok", "warn", "bad", "unknown"].forEach((state) => {
    element.classList.remove(`${prefix}-${state}`);
  });
  element.classList.add(`${prefix}-${value}`);
}

function renderServiceAlerts(container, alerts) {
  if (!container) return;
  if (!alerts.length) {
    container.innerHTML = '<p class="empty">暂无告警</p>';
    return;
  }
  container.innerHTML = alerts.map((alert) => {
    const content = alert.alert_content || "-";
    return `
      <article class="alert-item">
        <strong>${escapeHtml(alert.service_name || alert.alert_type || "告警")}</strong>
        <p class="alert-content" title="${escapeHtml(content)}">${escapeHtml(content)}</p>
        <small>${escapeHtml(alert.alert_type || "-")} · ${escapeHtml(alert.alert_status || "-")} · ${formatTime(alert.created_at)}</small>
      </article>
    `;
  }).join("");
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
      showToast("检测完");
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
    `${escapeHtml(serviceTypeLabel(service.service_type))} · ${escapeHtml(service.cluster_name || "未分")} · ${endpointHtml(service)}`;
  const editLink = document.getElementById("serviceEditLink");
  if (editLink) editLink.href = serviceEditHref(service.id);
  const openLink = document.getElementById("serviceOpenLink");
  if (openLink) {
    openLink.hidden = !isHttpService(service);
    if (isHttpService(service)) openLink.href = service.url;
  }
  const status = statusLabel(service.last_status);
  const detailStatus = document.getElementById("detailStatus");
  detailStatus.className = `status-pill status-${status}`;
  detailStatus.innerHTML = `${statusIconHtml(status)}<span>${status}</span>`;
  setStateClass(document.getElementById("currentStatusMetric"), "status-card", status);

  document.getElementById("currentStatus").innerHTML = `${statusIconHtml(status)}<span>${status}</span>`;
  const message = service.last_message || "暂无检测结果";
  const currentMessage = document.getElementById("currentMessage");
  currentMessage.textContent = message;
  currentMessage.title = message;

  const responseSeverityValue = responseSeverity(service.last_response_time_ms);
  const lastResponse = document.getElementById("lastResponse");
  lastResponse.className = `latency-value latency-${responseSeverityValue}`;
  lastResponse.textContent = service.last_response_time_ms ?? "-";
  setStateClass(document.getElementById("responseMetric"), "metric", responseSeverityValue);
  const stats = responseStats(results);
  document.getElementById("responseSummary").textContent = stats
    ? `平均 ${stats.average} ms / P95 ${stats.p95} ms`
    : "暂无历史样本";
  document.getElementById("lastChecked").textContent = formatTime(service.last_checked_at);
  document.getElementById("checkInterval").textContent = formatCheckInterval(service.check_interval);

  renderResultTable(results);
  renderServiceAlerts(document.getElementById("detailAlerts"), alerts);
  window.LiveMonitorCharts?.renderTrendChart(document.getElementById("trendChart"), results);
  if (window.lucide) window.lucide.createIcons();
}


