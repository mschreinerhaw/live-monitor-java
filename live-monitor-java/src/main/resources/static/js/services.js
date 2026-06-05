async function initAddService() {
  const form = document.getElementById("serviceForm");
  if (!form) return;
  await loadServiceTypeSections();
  const typeSelect = document.getElementById("serviceType");
  const webSchemeSelect = form.elements.web_scheme;
  let editingExistingService = false;
  let hydratingServiceForm = false;
  let activeDatabaseType = isDatabaseServiceType(typeSelect.value) ? typeSelect.value : "";
  const databaseTypeDrafts = {};
  let databaseConnectionOptions = [];
  initServiceTypePicker(typeSelect);
  const hostOptionsPromise = loadProcessHostOptions(form);
  const pathMatch = window.location.pathname.match(/\/services\/(\d+)\/edit$/);
  const params = new URLSearchParams(window.location.search);
  const editId = pathMatch?.[1] || params.get("id");
  const databaseConnectionsPromise = loadDatabaseConnectionOptions(form, editId).then((connections) => {
    databaseConnectionOptions = connections;
    renderDatabaseConnectionOptions(form, databaseConnectionOptions, typeSelect.value, editId);
    renderCrossDatabaseSourceOptions(form, databaseConnectionOptions, editId);
  });
  const presetClusterName = params.get("cluster_name");
  if (!editId && presetClusterName && form.elements.cluster_name) {
    form.elements.cluster_name.value = presetClusterName;
  }
  const syncFields = () => {
    const isWeb = isWebUrlServiceType(typeSelect.value);
    const isApi = typeSelect.value === "api";
    const isRedis = typeSelect.value === "redis";
    const isZookeeper = typeSelect.value === "zookeeper";
    const isProcess = typeSelect.value === "process";
    const isCrossDatabase = typeSelect.value === "cross_database";
    const isDatabase = isDatabaseServiceType(typeSelect.value);
    const isGenericJdbc = typeSelect.value === "jdbc";
    syncCrossDatabaseCopy(form, isCrossDatabase);
    const databaseConnectionSelect = form.elements.database_connection_service_id;
    if (databaseConnectionSelect) {
      renderDatabaseConnectionOptions(form, databaseConnectionOptions, typeSelect.value, editId);
      renderCrossDatabaseSourceOptions(form, databaseConnectionOptions, editId);
    }
    const usesDatabaseConnectionRef = Boolean(isDatabase && databaseConnectionSelect?.value);
    toggleFieldSet(".web-only", isWeb && !isApi);
    toggleFieldSet(".web-monitor-only", isWeb && !isApi);
    toggleFieldSet(".api-only", isApi);
    toggleFieldSet(".result-rule-only", !isApi && (isDatabase || isCrossDatabase));
    toggleFieldSet(".host-field", !isWeb && !isCrossDatabase && !isGenericJdbc);
    toggleFieldSet(".port-field", !isWeb && !isProcess && !isCrossDatabase && !isGenericJdbc);
    toggleFieldSet(".process-only", isProcess);
    toggleFieldSet(".redis-only", isRedis);
    toggleFieldSet(".zookeeper-only", isZookeeper);
    toggleFieldSet(".database-only", isDatabase);
    toggleFieldSet(".cross-db-only", isCrossDatabase);
    toggleFieldSet(".database-connection-manual-only", isDatabase && !usesDatabaseConnectionRef);
    toggleFieldSet(".jdbc-only", isGenericJdbc);
    toggleFieldSet(".standard-database-only", isDatabase && !isGenericJdbc);
    syncApiFieldCopy(form, isApi);
    const urlInput = form.elements.url;
    const apiUrlInput = form.elements.api_url;
    const hostInput = form.elements.host;
    const portInput = form.elements.port;
    const databaseNameInput = form.elements.database_name;
    const jdbcDriverClassInput = form.elements.jdbc_driver_class;
    const jdbcUrlInput = form.elements.jdbc_url;
    const processNameInput = form.elements.process_name;
    const processKeywordInput = form.elements.process_match_keyword;
    const processCommandInput = form.elements.check_command;
    if (urlInput) urlInput.required = isWeb && !isApi;
    if (apiUrlInput) apiUrlInput.required = isApi;
    if (hostInput) hostInput.required = !isWeb && !isProcess && !isGenericJdbc && !isCrossDatabase && !usesDatabaseConnectionRef;
    if (portInput) portInput.required = !isWeb && !isDatabase && !isProcess && !isCrossDatabase;
    if (processNameInput) processNameInput.required = isProcess;
    if (processKeywordInput) processKeywordInput.required = false;
    if (processCommandInput) processCommandInput.required = isProcess;
    if (databaseNameInput) databaseNameInput.required = typeSelect.value === "oracle" && !usesDatabaseConnectionRef;
    if (jdbcDriverClassInput) jdbcDriverClassInput.required = isGenericJdbc && !usesDatabaseConnectionRef;
    if (jdbcUrlInput) jdbcUrlInput.required = isGenericJdbc && !usesDatabaseConnectionRef;
    syncApiMethodFields(form);
    syncApiAuthFields(form);
    syncApiTestVisibility(isApi);
    syncDatabaseRuleMode(form);
    syncResultAssertionMode(form);
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
    if (isDatabase) {
      applyDatabaseTypePlaceholders(form, typeSelect.value);
    }
  };
  const handleServiceTypeChange = () => {
    const nextType = typeSelect.value;
    if (!hydratingServiceForm && activeDatabaseType && activeDatabaseType !== nextType) {
      databaseTypeDrafts[activeDatabaseType] = snapshotDatabaseTypeConfig(form);
    }
    syncFields();
    if (isDatabaseServiceType(nextType)) {
      if (!hydratingServiceForm && activeDatabaseType !== nextType) {
        restoreDatabaseTypeConfig(form, databaseTypeDrafts[nextType], nextType);
      }
      activeDatabaseType = nextType;
    } else {
      activeDatabaseType = "";
    }
  };
  typeSelect.addEventListener("change", handleServiceTypeChange);
  webSchemeSelect?.addEventListener("change", () => normalizeWebUrlInput(form));
  form.elements.api_http_method?.addEventListener("change", () => syncApiMethodFields(form));
  form.elements.api_auth_type?.addEventListener("change", () => syncApiAuthFields(form));
  form.elements.database_connection_service_id?.addEventListener("change", () => {
    syncFields();
    resetDatabasePreviewPanel();
  });
  initCrossDatabaseBuilder(form);
  initCompareRuleTemplate(form);
  document.getElementById("addApiHeaderBtn")?.addEventListener("click", () => addApiHeaderRow());
  form.elements.enabled?.addEventListener("change", () => syncServiceEditingLock(form, editingExistingService));
  initAssertionModeControls(form);
  renderApiHeaderRows([]);
  syncFields();
  await loadServiceAlertConfigOptions(form);
  await databaseConnectionsPromise;
  await hostOptionsPromise;

  const runConnectionTest = async (targetBox) => {
    const resultBox = document.getElementById("connectionTestResult");
    const apiResultBox = targetBox || null;
    const restoreLockedControls = unlockServiceFormForSubmit(form);
    let payload;
    try {
      if (!form.reportValidity()) return;
      if (!validateServiceAssertionConfig(form)) return;
      payload = buildServicePayload(form);
    } finally {
      restoreLockedControls();
    }
    const activeResultBox = apiResultBox || resultBox;
    if (activeResultBox) {
      activeResultBox.hidden = false;
      activeResultBox.className = apiResultBox ? "api-test-result testing" : "test-result span-2 testing";
      activeResultBox.textContent = typeSelect.value === "api" ? "正在测试请求..." : "正在测试连接...";
    }
    try {
      const result = editId
        ? await LiveMonitorApi.testExistingService(editId, payload)
        : await LiveMonitorApi.testService(payload);
      if (activeResultBox) {
        if (apiResultBox) {
          renderApiRequestTestResult(activeResultBox, result);
        } else {
          activeResultBox.className = `test-result span-2 ${result.status === "UP" ? "ok" : "bad"}`;
          activeResultBox.innerHTML = `${escapeHtml(result.status)} · 响应时间 ${result.response_time_ms ?? "-"}ms · ${escapeHtml(result.message || "-")}`;
        }
      }
    } catch (error) {
      if (activeResultBox) {
        activeResultBox.className = apiResultBox ? "api-test-result bad" : "test-result span-2 bad";
        activeResultBox.textContent = error.message;
      }
    }
  };
  document.getElementById("testConnectionBtn")?.addEventListener("click", async () => {
    await runConnectionTest();
  });
  document.getElementById("apiTestRequestBtn")?.addEventListener("click", async () => {
    await runConnectionTest(document.getElementById("apiRequestTestResult"));
  });

  if (editId) {
    try {
      editingExistingService = true;
      const editService = await LiveMonitorApi.service(editId);
      document.title = "编辑服务 - Live Monitor";
      document.getElementById("serviceFormTitle").textContent = "编辑监控服务";
      document.getElementById("serviceFormNav").textContent = "编辑服务";
      document.querySelector("#serviceFormSubmit span").textContent = "保存修改";
      document.getElementById("serviceFormCancel").href = serviceDetailHref(editService.id);
      hydratingServiceForm = true;
      try {
        fillServiceForm(form, editService);
        typeSelect.dispatchEvent(new Event("change", { bubbles: true }));
        activeDatabaseType = isDatabaseServiceType(typeSelect.value) ? typeSelect.value : "";
      } finally {
        hydratingServiceForm = false;
      }
      syncServiceEditingLock(form, editingExistingService);
    } catch (error) {
      showToast(error.message);
    }
  }

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!validateServiceAssertionConfig(form)) return;
    const restoreLockedControls = unlockServiceFormForSubmit(form);
    const data = buildServicePayload(form);
    restoreLockedControls();

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

  document.getElementById("testRuleBtn")?.addEventListener("click", async () => {
    const resultBox = document.getElementById("ruleTestResult") || document.getElementById("connectionTestResult");
    if (assertionModeValue(form, "result_assertion_mode") === "visual" && !validateResultVisualAssertionConfig(form)) return;
    const payload = buildRuleTestPayload(form);
    if (!payload.expression) {
      showToast("请先填写响应断言规则");
      return;
    }
    if (resultBox) {
      resultBox.hidden = false;
      resultBox.className = "test-result span-2 testing";
      resultBox.textContent = "正在测试规则...";
    }
    try {
      const result = await LiveMonitorApi.testRule(payload);
      if (resultBox) {
        resultBox.className = `test-result span-2 ${result.matched ? "ok" : "bad"}`;
        resultBox.innerHTML = renderRuleTestResult(payload, result);
      }
    } catch (error) {
      if (resultBox) {
        resultBox.className = "test-result span-2 bad";
        resultBox.textContent = error.message;
      }
    }
  });

  document.getElementById("databasePreviewBtn")?.addEventListener("click", async () => {
    const panel = document.getElementById("databasePreviewPanel");
    const restoreLockedControls = unlockServiceFormForSubmit(form);
    let payload;
    try {
      payload = buildDatabasePreviewPayload(form);
    } finally {
      restoreLockedControls();
    }
    if (!payload.service_type) return;
    if (panel) {
      panel.hidden = false;
      panel.className = "test-result database-only span-2 testing";
      panel.textContent = "正在查询预览...";
    }
    try {
      const preview = editId
        ? await LiveMonitorApi.existingServiceDatabasePreview(editId, payload)
        : await LiveMonitorApi.databasePreview(payload);
      renderDatabasePreview(form, preview);
    } catch (error) {
      if (panel) {
        panel.className = "test-result database-only span-2 bad";
        panel.textContent = error.message;
      }
    }
  });
}

const SERVICE_TYPE_FORM_PARTIALS = [
  "/partials/service-forms/web.html",
  "/partials/service-forms/api.html",
  "/partials/service-forms/target.html",
  "/partials/service-forms/process.html",
  "/partials/service-forms/redis.html",
  "/partials/service-forms/zookeeper.html",
  "/partials/service-forms/cross-database.html",
  "/partials/service-forms/database.html",
];

async function loadServiceTypeSections() {
  const container = document.getElementById("serviceTypeSections");
  if (!container || container.dataset.loaded === "true") return;
  try {
    const fragments = await Promise.all(
      SERVICE_TYPE_FORM_PARTIALS.map(async (url) => {
        const response = await fetch(url, { cache: "no-cache" });
        if (!response.ok) throw new Error(`加载表单片段失败：${url}`);
        return response.text();
      })
    );
    container.innerHTML = fragments.join("\n");
    container.dataset.loaded = "true";
    if (window.lucide) window.lucide.createIcons();
  } catch (error) {
    showToast(error.message || "服务类型表单加载失败");
    throw error;
  }
}

function syncServiceEditingLock(form, editingExistingService = false) {
  if (!form) return;
  const serviceType = form.elements.service_type?.value || "";
  const locked = Boolean(
    editingExistingService
    && form.elements.enabled
    && !form.elements.enabled.checked
    && !isDatabaseServiceType(serviceType)
    && serviceType !== "cross_database"
  );
  form.classList.toggle("form-edit-locked", locked);
  Array.from(form.elements).forEach((control) => {
    if (isServiceLockExemptControl(control)) return;
    if (locked) {
      control.disabled = true;
      control.dataset.serviceEditLocked = "true";
      return;
    }
    if (control.dataset.serviceEditLocked === "true") {
      control.disabled = false;
    }
    delete control.dataset.serviceEditLocked;
  });
  if (!locked) {
    syncApiAssertionMode(form);
    syncDatabaseRuleMode(form);
    syncResultAssertionMode(form);
  }
  const testButton = document.getElementById("testConnectionBtn");
  if (testButton) testButton.disabled = false;
  const testRuleButton = document.getElementById("testRuleBtn");
  if (testRuleButton) testRuleButton.disabled = false;
  const previewButton = document.getElementById("databasePreviewBtn");
  if (previewButton) previewButton.disabled = false;
  const apiTestButton = document.getElementById("apiTestRequestBtn");
  if (apiTestButton) apiTestButton.disabled = false;
}

function isServiceLockExemptControl(control) {
  if (!control) return true;
  return control.name === "enabled"
    || control.id === "serviceFormSubmit"
    || control.id === "testConnectionBtn"
    || control.id === "testRuleBtn"
    || control.id === "databasePreviewBtn"
    || control.id === "apiTestRequestBtn";
}

function unlockServiceFormForSubmit(form) {
  const controls = Array.from(form.elements).filter((control) => control.dataset.serviceEditLocked === "true");
  controls.forEach((control) => {
    control.disabled = false;
  });
  return () => {
    if (form.elements.enabled?.checked) return;
    controls.forEach((control) => {
      control.disabled = true;
    });
  };
}

function toggleFieldSet(selector, visible) {
  document.querySelectorAll(selector).forEach((item) => {
    item.style.display = visible ? "" : "none";
  });
}

function syncApiFieldCopy(form, isApi) {
  const copyKey = isApi ? "api" : "web";
  form.querySelectorAll("[data-web-label][data-api-label]").forEach((item) => {
    item.textContent = item.dataset[`${copyKey}Label`] || item.textContent;
  });
  form.querySelectorAll("[data-web-placeholder][data-api-placeholder]").forEach((item) => {
    item.placeholder = item.dataset[`${copyKey}Placeholder`] || item.placeholder;
  });
}

function syncCrossDatabaseCopy(form, isCrossDatabase) {
  const subtitle = document.getElementById("serviceFormSubtitle");
  if (subtitle) {
    subtitle.textContent = isCrossDatabase
      ? "配置多数据源 SQL、字段映射、比对规则和告警策略"
      : "配置检测目标、探测条件和告警配置";
  }
  const serviceNameInput = form?.elements.service_name;
  if (serviceNameInput && !serviceNameInput.value) {
    serviceNameInput.placeholder = isCrossDatabase ? "例如：TA 与估值系统基金数据比对" : "例如：交易系统 Web";
  }
  const monitorReasonInput = form?.elements.monitor_reason;
  if (monitorReasonInput && !monitorReasonInput.value) {
    monitorReasonInput.placeholder = isCrossDatabase
      ? "例如：每日比对多库聚合结果，差异触发统一告警"
      : "例如：这是核心交易链路入口，需要持续监控可用性和响应时间";
  }
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
  if (serviceType === "process" || serviceType === "jdbc" || serviceType === "cross_database") data.port = null;
  data.expected_status_code = data.expected_status_code ? Number(data.expected_status_code) : null;
  data.zookeeper_expected_nodes = data.zookeeper_expected_nodes ? Number(data.zookeeper_expected_nodes) : null;
  const isDatabase = isDatabaseServiceType(serviceType);
  const isCrossDatabase = serviceType === "cross_database";
  const isGenericJdbc = serviceType === "jdbc";
  const isProcess = serviceType === "process";
  const databaseConnectionServiceId = isDatabase && data.database_connection_service_id
    ? Number(data.database_connection_service_id)
    : null;
  const usesDatabaseConnectionRef = Boolean(databaseConnectionServiceId);
  data.service_type = serviceType;
  const isWebUrl = isWebUrlServiceType(serviceType);
  const isApi = serviceType === "api";
  const isDatabaseAdvancedRule = isDatabaseAdvancedRuleMode(form);
  if (isApi) {
    data.url = normalizeWebUrl(data.api_url, "https");
    data.http_method = data.api_http_method || "GET";
    data.expected_status_code = null;
    data.response_keyword = null;
    data.api_assertion_expression = buildApiAssertionExpression(form) || null;
    data.ignore_ssl_verification = Boolean(form.elements.api_ignore_ssl_verification?.checked);
    data.api_headers = collectApiHeaders();
    data.api_content_type = data.api_content_type || "application/json";
    data.api_request_body = ["POST", "PUT"].includes(data.http_method) ? data.api_request_body || null : null;
    data.api_auth_type = data.api_auth_type || "none";
    data.api_basic_username = data.api_auth_type === "basic" ? data.api_basic_username || null : null;
    data.api_basic_password = data.api_auth_type === "basic" ? data.api_basic_password || null : null;
    data.api_bearer_token = data.api_auth_type === "bearer" ? data.api_bearer_token || null : null;
    data.api_auth_app_id = data.api_auth_type === "custom_header" ? data.api_auth_app_id || null : null;
    data.api_auth_app_secret = data.api_auth_type === "custom_header" ? data.api_auth_app_secret || null : null;
    data.api_response_time_ms = null;
    data.api_json_assertions = null;
    data.api_text_assertion_mode = "contains";
    data.api_text_assertion_value = null;
  } else {
    data.url = isWebUrl ? normalizeWebUrl(data.url, data.web_scheme) : null;
    data.http_method = isWebUrl ? data.http_method || "GET" : "GET";
    data.response_keyword = isWebUrl ? data.response_keyword || null : null;
    data.api_assertion_expression = (isDatabase && isDatabaseAdvancedRule) || isCrossDatabase
      ? buildResultAssertionExpression(form) || null
      : null;
    data.expected_status_code = isWebUrl ? data.expected_status_code : null;
    data.ignore_ssl_verification = isWebUrl ? data.ignore_ssl_verification : false;
    data.api_headers = [];
    data.api_content_type = null;
    data.api_request_body = null;
    data.api_auth_type = "none";
    data.api_basic_username = null;
    data.api_basic_password = null;
    data.api_bearer_token = null;
    data.api_auth_app_id = null;
    data.api_auth_app_secret = null;
    data.api_response_time_ms = null;
    data.api_json_assertions = null;
    data.api_text_assertion_mode = "contains";
    data.api_text_assertion_value = null;
  }
  if (usesDatabaseConnectionRef) data.port = null;
  data.host = isWebUrl || isGenericJdbc || isCrossDatabase || usesDatabaseConnectionRef ? null : data.host?.trim() || null;
  data.database_connection_service_id = databaseConnectionServiceId;
  data.database_name = isDatabase && !isGenericJdbc && !usesDatabaseConnectionRef ? data.database_name || null : null;
  data.database_username = isDatabase && !usesDatabaseConnectionRef ? data.database_username || null : null;
  data.database_password = isDatabase && !usesDatabaseConnectionRef ? data.database_password || null : null;
  data.database_query = isDatabase ? data.database_query || null : null;
  data.expected_result = isDatabase && !isDatabaseAdvancedRule ? data.expected_result || null : null;
  data.database_result_operator = isDatabase && !isDatabaseAdvancedRule ? data.database_result_operator || "fuzzy" : "fuzzy";
  data.database_assertion_fields = isDatabase && isDatabaseAdvancedRule ? selectedDatabaseAssertionFields(form) : [];
  data.cross_database_queries = isCrossDatabase ? collectCrossDatabaseQueries() : [];
  data.jdbc_driver_class = isDatabase && !usesDatabaseConnectionRef ? data.jdbc_driver_class || null : null;
  data.jdbc_url = isGenericJdbc && !usesDatabaseConnectionRef ? data.jdbc_url || null : null;
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
  delete data.api_url;
  delete data.api_http_method;
  delete data.api_expected_status_code;
  delete data.api_assertion_mode;
  delete data.api_advanced_assertion_expression;
  delete data.api_ignore_ssl_verification;
  delete data.result_assertion_mode;
  delete data.result_advanced_assertion_expression;
  delete data.database_rule_mode;
  delete data.service_alert_cooldown_unit;
  delete data.rule_test_status_code;
  delete data.rule_test_response_time_ms;
  delete data.rule_test_body;
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
    "monitor_reason",
    "check_timeout_seconds",
    "web_scheme",
    "url",
    "http_method",
    "expected_status_code",
    "response_keyword",
    "api_assertion_expression",
    "api_content_type",
    "api_request_body",
    "api_auth_type",
    "api_basic_username",
    "api_auth_app_id",
    "api_response_time_ms",
    "api_json_assertions",
    "api_text_assertion_mode",
    "api_text_assertion_value",
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
    "database_connection_service_id",
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
        api_content_type: "application/json",
        api_auth_type: "none",
        api_text_assertion_mode: "contains",
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
  if (form.elements.api_url) {
    form.elements.api_url.value = service.url || "";
  }
  if (form.elements.api_http_method) {
    form.elements.api_http_method.value = service.http_method || "GET";
  }
  if (form.elements.api_expected_status_code) {
    form.elements.api_expected_status_code.value = service.expected_status_code || 200;
  }
  if (form.elements.api_ignore_ssl_verification) {
    form.elements.api_ignore_ssl_verification.checked = Boolean(service.ignore_ssl_verification);
  }
  renderApiHeaderRows(service.api_headers || []);
  if (form.elements.api_basic_password) {
    form.elements.api_basic_password.value = "";
    form.elements.api_basic_password.placeholder = service.api_auth_type === "basic" ? "留空则保持原密码" : "";
  }
  if (form.elements.api_bearer_token) {
    form.elements.api_bearer_token.value = "";
    form.elements.api_bearer_token.placeholder = service.api_auth_type === "bearer" ? "留空则保持原 Token" : "Bearer Token";
  }
  if (form.elements.api_auth_app_secret) {
    form.elements.api_auth_app_secret.value = "";
    form.elements.api_auth_app_secret.placeholder = service.api_auth_type === "custom_header" ? "留空则保持原 AppSecret" : "";
  }
  if (service.service_type === "api" && form.elements.api_advanced_assertion_expression) {
    const savedExpression = service.api_assertion_expression || "";
    renderApiAssertionRuleRows(apiVisualAssertionRulesFromService(service));
    if (savedExpression) {
      setAssertionModeValue(form, "api_assertion_mode", "dsl");
      form.elements.api_advanced_assertion_expression.value = savedExpression;
    } else {
      setAssertionModeValue(form, "api_assertion_mode", "visual");
      form.elements.api_advanced_assertion_expression.value = "";
    }
  } else if (form.elements.result_advanced_assertion_expression) {
    const savedExpression = service.api_assertion_expression || "";
    if (isDatabaseServiceType(service.service_type)) {
      setAssertionModeValue(form, "database_rule_mode", savedExpression ? "advanced" : "simple");
    }
    if (savedExpression) {
      setAssertionModeValue(form, "result_assertion_mode", "dsl");
      form.elements.result_advanced_assertion_expression.value = savedExpression;
    } else {
      setAssertionModeValue(form, "result_assertion_mode", "visual");
    }
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
  renderCrossDatabaseQueryRows(form, service.cross_database_queries || service.crossDatabaseQueries || []);
  syncApiMethodFields(form);
  syncApiAuthFields(form);
  syncApiAssertionMode(form);
  syncDatabaseRuleMode(form);
  syncResultAssertionMode(form);
  hideAssertionDslPreviews();
  renderDatabaseSelectedFields(form, service.database_assertion_fields || []);
}

function apiVisualAssertionRulesFromService(service) {
  const rules = [];
  if (service.expected_status_code) {
    rules.push({ type: "status", operator: "==", value: String(service.expected_status_code) });
  }
  if (service.api_response_time_ms) {
    rules.push({ type: "response_time", operator: "<", value: String(service.api_response_time_ms) });
  }
  String(service.api_json_assertions || "")
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .forEach((line) => {
      const normalized = normalizeJsonAssertionLine(line);
      const match = normalized.match(/^json\s*\(\s*"([^"]+)"\s*\)\s*(==|!=|>=|<=|>|<)\s*(.+)$/i);
      if (match) {
        rules.push({ type: "json_compare", path: match[1], operator: match[2], value: match[3] });
      }
    });
  if (service.api_text_assertion_value) {
    rules.push({
      type: service.api_text_assertion_mode === "not_contains" ? "not_contains" : "contains",
      value: service.api_text_assertion_value,
    });
  }
  return rules;
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


