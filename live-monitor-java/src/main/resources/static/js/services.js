async function initAddService() {
  const form = document.getElementById("serviceForm");
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
    const isDatabase = isDatabaseServiceType(typeSelect.value);
    const isGenericJdbc = typeSelect.value === "jdbc";
    const databaseConnectionSelect = form.elements.database_connection_service_id;
    if (databaseConnectionSelect) {
      renderDatabaseConnectionOptions(form, databaseConnectionOptions, typeSelect.value, editId);
    }
    const usesDatabaseConnectionRef = Boolean(isDatabase && databaseConnectionSelect?.value);
    toggleFieldSet(".web-only", isWeb && !isApi);
    toggleFieldSet(".web-monitor-only", isWeb && !isApi);
    toggleFieldSet(".api-only", isApi);
    toggleFieldSet(".result-rule-only", !isApi && isDatabase);
    toggleFieldSet(".host-field", !isWeb && !isGenericJdbc && !usesDatabaseConnectionRef);
    toggleFieldSet(".port-field", !isWeb && !isProcess && !isGenericJdbc && !usesDatabaseConnectionRef);
    toggleFieldSet(".process-only", isProcess);
    toggleFieldSet(".redis-only", isRedis);
    toggleFieldSet(".zookeeper-only", isZookeeper);
    toggleFieldSet(".database-only", isDatabase);
    toggleFieldSet(".database-connection-manual-only", isDatabase && !usesDatabaseConnectionRef);
    toggleFieldSet(".jdbc-only", isGenericJdbc && !usesDatabaseConnectionRef);
    toggleFieldSet(".standard-database-only", isDatabase && !isGenericJdbc && !usesDatabaseConnectionRef);
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
    if (hostInput) hostInput.required = !isWeb && !isProcess && !isGenericJdbc && !usesDatabaseConnectionRef;
    if (portInput) portInput.required = !isWeb && !isDatabase && !isProcess;
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
    const resultBox = document.getElementById("connectionTestResult");
    if (!validateResultVisualAssertionConfig(form)) return;
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

function syncServiceEditingLock(form, editingExistingService = false) {
  if (!form) return;
  const locked = Boolean(editingExistingService && form.elements.enabled && !form.elements.enabled.checked);
  form.classList.toggle("form-edit-locked", locked);
  Array.from(form.elements).forEach((control) => {
    if (isServiceLockExemptControl(control)) return;
    control.disabled = locked;
    control.dataset.serviceEditLocked = locked ? "true" : "";
  });
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

function syncApiMethodFields(form) {
  const method = form.elements.api_http_method?.value || "GET";
  const showBody = method === "POST" || method === "PUT";
  toggleFieldSet(".api-body-only", showBody);
}

function syncApiAuthFields(form) {
  const authType = form.elements.api_auth_type?.value || "none";
  toggleFieldSet(".api-auth-basic", authType === "basic");
  toggleFieldSet(".api-auth-bearer", authType === "bearer");
  toggleFieldSet(".api-auth-custom", authType === "custom_header");
}

function syncApiTestVisibility(isApi) {
  const testButton = document.getElementById("testConnectionBtn");
  if (testButton) testButton.style.display = isApi ? "none" : "";
}

function renderApiHeaderRows(headers = []) {
  const container = document.getElementById("apiHeaderRows");
  if (!container) return;
  container.innerHTML = "";
  const rows = headers.length ? headers : [{ name: "Content-Type", value: "application/json" }];
  rows.forEach((header) => addApiHeaderRow(header.name, header.value));
}

function addApiHeaderRow(name = "", value = "") {
  const container = document.getElementById("apiHeaderRows");
  if (!container) return;
  const row = document.createElement("div");
  row.className = "api-header-row";
  row.innerHTML = `
    <input data-api-header-name placeholder="Header 名称" value="${escapeHtml(name)}">
    <input data-api-header-value placeholder="Header 值" value="${escapeHtml(value)}">
    <button class="icon-button" type="button" title="删除请求头">
      <i data-lucide="trash-2"></i>
    </button>
  `;
  row.querySelector("button")?.addEventListener("click", () => row.remove());
  container.appendChild(row);
  if (window.lucide) window.lucide.createIcons();
}

function collectApiHeaders() {
  return Array.from(document.querySelectorAll("#apiHeaderRows .api-header-row"))
    .map((row) => ({
      name: row.querySelector("[data-api-header-name]")?.value?.trim() || "",
      value: row.querySelector("[data-api-header-value]")?.value?.trim() || "",
    }))
    .filter((header) => header.name);
}

const DATABASE_TYPE_CONFIG_FIELDS = [
  "database_connection_service_id",
  "host",
  "port",
  "database_name",
  "database_username",
  "database_password",
  "jdbc_driver_class",
  "jdbc_url",
  "database_rule_mode",
  "expected_result",
  "database_result_operator",
  "database_query",
  "result_advanced_assertion_expression",
  "rule_test_status_code",
  "rule_test_response_time_ms",
  "rule_test_body",
];

function snapshotDatabaseTypeConfig(form) {
  const fields = {};
  DATABASE_TYPE_CONFIG_FIELDS.forEach((name) => {
    if (form.elements[name]) fields[name] = form.elements[name].value;
  });
  return {
    fields,
    resultAssertionMode: assertionModeValue(form, "result_assertion_mode"),
    assertionRows: collectResultAssertionRuleRows(),
    databaseAssertionFields: form.dataset.databaseAssertionFields || "[]",
  };
}

function restoreDatabaseTypeConfig(form, draft, serviceType) {
  const fields = draft?.fields || defaultDatabaseTypeConfig(serviceType);
  DATABASE_TYPE_CONFIG_FIELDS.forEach((name) => {
    if (form.elements[name]) form.elements[name].value = fields[name] ?? "";
  });
  setAssertionModeValue(form, "result_assertion_mode", draft?.resultAssertionMode || "visual");
  renderResultAssertionRuleRows(draft?.assertionRows?.length ? draft.assertionRows : [{ type: "json_compare" }]);
  form.dataset.databaseAssertionFields = draft?.databaseAssertionFields || "[]";
  applyDatabaseTypePlaceholders(form, serviceType);
  resetDatabasePreviewPanel();
  hideAssertionDslPreviews();
  syncDatabaseRuleMode(form);
  syncResultAssertionMode(form);
}

function defaultDatabaseTypeConfig(serviceType) {
  return {
    database_connection_service_id: "",
    host: "",
    port: "",
    database_name: "",
    database_username: "",
    database_password: "",
    jdbc_driver_class: serviceType === "jdbc" ? "" : "",
    jdbc_url: "",
    database_rule_mode: "simple",
    expected_result: "",
    database_result_operator: "fuzzy",
    database_query: "",
    result_advanced_assertion_expression: "",
    rule_test_status_code: "200",
    rule_test_response_time_ms: "68",
    rule_test_body: "",
  };
}

function collectResultAssertionRuleRows() {
  return Array.from(document.querySelectorAll("#resultAssertionRows .assertion-rule-row")).map((row) => ({
    type: row.querySelector("[data-assertion-type]")?.value || "json_compare",
    path: row.querySelector("[data-assertion-path]")?.value || "",
    operator: row.querySelector("[data-assertion-operator]")?.value || "==",
    value: row.querySelector("[data-assertion-value]")?.value || "",
  }));
}

function renderResultAssertionRuleRows(rules = []) {
  const container = document.getElementById("resultAssertionRows");
  if (!container) return;
  container.innerHTML = "";
  const rows = rules.length ? rules : [{ type: "json_compare" }];
  rows.forEach((rule) => addResultAssertionRuleRow(rule));
  updateAssertionDeleteButtons();
}

function resetDatabasePreviewPanel() {
  const panel = document.getElementById("databasePreviewPanel");
  if (!panel) return;
  panel.hidden = true;
  panel.innerHTML = "";
  panel.className = "test-result database-only span-2";
}

function applyDatabaseTypePlaceholders(form, serviceType) {
  const portInput = form.elements.port;
  const databaseNameInput = form.elements.database_name;
  const jdbcDriverClassInput = form.elements.jdbc_driver_class;
  if (portInput && !portInput.value) {
    portInput.placeholder = {
      mysql: "3306",
      oracle: "1521",
      postgresql: "5432",
      postgres: "5432",
      jdbc: "在 JDBC 连接串中填写端口",
    }[serviceType] || "端口";
  }
  if (databaseNameInput) {
    databaseNameInput.placeholder = {
      mysql: "MySQL 库名，例如：app",
      oracle: "Oracle 服务名，例如：ORCLPDB1",
      postgresql: "PostgreSQL 库名，例如：app",
      postgres: "PostgreSQL 库名，例如：app",
    }[serviceType] || "数据库名 / 服务名";
  }
  if (jdbcDriverClassInput) {
    jdbcDriverClassInput.placeholder = {
      mysql: "可选，MySQL 5.x：com.mysql.jdbc.Driver；MySQL 8.x：com.mysql.cj.jdbc.Driver",
      oracle: "可选，例如：oracle.jdbc.OracleDriver",
      postgresql: "可选，例如：org.postgresql.Driver",
      postgres: "可选，例如：org.postgresql.Driver",
      jdbc: "必填，例如：com.microsoft.sqlserver.jdbc.SQLServerDriver",
    }[serviceType] || "可选，填写 lib 下驱动 jar 对应的 Driver 类";
  }
}

function initAssertionModeControls(form) {
  initResultAssertionBuilder();
  document.getElementById("generateApiAssertionDslBtn")?.addEventListener("click", () => {
    showApiAssertionDslPreview(form);
  });
  document.getElementById("generateResultAssertionDslBtn")?.addEventListener("click", () => {
    showResultAssertionDslPreview(form);
  });
  form.querySelectorAll('input[name="api_assertion_mode"], input[name="result_assertion_mode"], [name="database_rule_mode"]').forEach((input) => {
    input.addEventListener("change", () => {
      syncApiAssertionMode(form);
      syncDatabaseRuleMode(form);
      syncResultAssertionMode(form);
      hideAssertionDslPreviews();
    });
  });
  form.addEventListener("input", (event) => {
    if (event.target?.closest?.(".api-assert-section, .assertion-config-section")) {
      hideAssertionDslPreviews();
    }
  });
  form.addEventListener("change", (event) => {
    const row = event.target?.closest?.(".assertion-rule-row");
    if (row) updateAssertionRuleRowState(row);
    if (event.target?.closest?.(".api-assert-section, .assertion-config-section")) {
      hideAssertionDslPreviews();
    }
  });
  syncApiAssertionMode(form);
  syncDatabaseRuleMode(form);
  syncResultAssertionMode(form);
  hideAssertionDslPreviews();
}

function initResultAssertionBuilder() {
  const container = document.getElementById("resultAssertionRows");
  if (!container) return;
  document.getElementById("addResultAssertionRuleBtn")?.addEventListener("click", () => {
    addResultAssertionRuleRow({ type: "json_compare" });
    hideAssertionDslPreviews();
  });
  if (!container.querySelector(".assertion-rule-row")) {
    addResultAssertionRuleRow({ type: "json_compare" });
  }
}

function addResultAssertionRuleRow(rule = {}) {
  const container = document.getElementById("resultAssertionRows");
  if (!container) return;
  const row = document.createElement("div");
  row.className = "assertion-rule-row";
  row.innerHTML = `
    <select data-assertion-type aria-label="断言类型">
      <option value="json_compare">JSON 字段</option>
      <option value="contains">文本包含</option>
      <option value="not_contains">文本不包含</option>
      <option value="icontains">文本包含（忽略大小写）</option>
      <option value="response_time">响应时间</option>
      <option value="status">HTTP 状态码</option>
      <option value="regex_compare">正则提取</option>
    </select>
    <input data-assertion-path aria-label="字段路径" placeholder="$.code" required>
    <select data-assertion-operator aria-label="比较方式">
      <option value="==">等于</option>
      <option value="!=">不等于</option>
      <option value=">">大于</option>
      <option value=">=">大于等于</option>
      <option value="<">小于</option>
      <option value="<=">小于等于</option>
    </select>
    <input data-assertion-value aria-label="期望值" placeholder="0" required>
    <button class="icon-button" type="button" title="删除断言">
      <i data-lucide="trash-2"></i>
    </button>
  `;
  row.querySelector("[data-assertion-type]").value = rule.type || "json_compare";
  row.querySelector("[data-assertion-path]").value = rule.path || "";
  row.querySelector("[data-assertion-operator]").value = rule.operator || "==";
  row.querySelector("[data-assertion-value]").value = rule.value || "";
  row.querySelector("button")?.addEventListener("click", () => {
    if (container.querySelectorAll(".assertion-rule-row").length <= 1) {
      resetAssertionRuleRow(row);
      showToast("至少保留一条可视化断言");
      return;
    }
    row.remove();
    updateAssertionDeleteButtons();
    hideAssertionDslPreviews();
  });
  container.appendChild(row);
  updateAssertionRuleRowState(row);
  if (window.lucide) window.lucide.createIcons();
}

function updateAssertionRuleRowState(row) {
  const type = row.querySelector("[data-assertion-type]")?.value || "json_compare";
  const pathInput = row.querySelector("[data-assertion-path]");
  const operatorSelect = row.querySelector("[data-assertion-operator]");
  const valueInput = row.querySelector("[data-assertion-value]");
  const typeChanged = row.dataset.assertionType !== type;
  row.dataset.assertionType = type;
  const textRule = ["contains", "not_contains", "icontains"].includes(type);
  const noPathRule = textRule || type === "response_time" || type === "status";
  row.classList.toggle("assertion-rule-row-text", textRule);
  row.classList.toggle("assertion-rule-row-short", !textRule && noPathRule);
  pathInput.style.display = noPathRule ? "none" : "";
  operatorSelect.style.display = textRule ? "none" : "";
  pathInput.disabled = noPathRule;
  operatorSelect.disabled = textRule;
  pathInput.required = !noPathRule;
  valueInput.required = true;
  pathInput.removeAttribute("pattern");
  pathInput.removeAttribute("maxlength");
  valueInput.removeAttribute("pattern");
  valueInput.removeAttribute("min");
  valueInput.removeAttribute("max");
  valueInput.removeAttribute("inputmode");
  if (type === "json_compare") {
    pathInput.placeholder = "$.code";
    pathInput.pattern = "\\$([.][A-Za-z_][A-Za-z0-9_]*|\\[[0-9]+\\])*";
    pathInput.maxLength = 180;
    pathInput.title = 'JSONPath 需以 $ 开头，例如 $.code 或 $.rows[0].code';
    valueInput.placeholder = "0、true 或 OK";
    valueInput.maxLength = 200;
    valueInput.title = "期望值支持数字、true、false、null 或文本";
  } else if (type === "regex_compare") {
    pathInput.placeholder = "CPU:\\s*([0-9.]+)";
    pathInput.maxLength = 240;
    pathInput.title = "填写正则表达式；如需比较数值，请使用捕获组提取";
    valueInput.placeholder = "80";
    valueInput.maxLength = 200;
    valueInput.title = "期望值支持数字或文本";
  } else if (type === "response_time") {
    valueInput.placeholder = "3000";
    valueInput.type = "number";
    valueInput.min = "0";
    valueInput.inputMode = "numeric";
    valueInput.title = "填写非负毫秒数";
    if (typeChanged) operatorSelect.value = "<";
  } else if (type === "status") {
    valueInput.placeholder = "200";
    valueInput.type = "number";
    valueInput.min = "100";
    valueInput.max = "599";
    valueInput.inputMode = "numeric";
    valueInput.title = "填写 100 到 599 的 HTTP 状态码";
    if (typeChanged) operatorSelect.value = "==";
  } else {
    valueInput.placeholder = "success";
    valueInput.maxLength = 500;
    valueInput.title = "填写要匹配的响应文本";
  }
  if (type !== "response_time" && type !== "status") {
    valueInput.type = "text";
  }
  updateAssertionDeleteButtons();
}

function resetAssertionRuleRow(row) {
  row.querySelector("[data-assertion-type]").value = "json_compare";
  row.querySelector("[data-assertion-path]").value = "";
  row.querySelector("[data-assertion-operator]").value = "==";
  const valueInput = row.querySelector("[data-assertion-value]");
  valueInput.type = "text";
  valueInput.value = "";
  row.dataset.assertionType = "";
  updateAssertionRuleRowState(row);
  hideAssertionDslPreviews();
}

function updateAssertionDeleteButtons() {
  const rows = Array.from(document.querySelectorAll("#resultAssertionRows .assertion-rule-row"));
  rows.forEach((row) => {
    const button = row.querySelector("button");
    if (!button) return;
    button.disabled = rows.length <= 1;
    button.title = rows.length <= 1 ? "至少保留一条断言" : "删除断言";
  });
}

function syncApiAssertionMode(form) {
  const useDsl = assertionModeValue(form, "api_assertion_mode") === "dsl";
  document.querySelectorAll("[data-api-assertion-visual]").forEach((item) => {
    item.hidden = useDsl;
    setPanelControlsDisabled(item, useDsl);
  });
  document.querySelectorAll("[data-api-assertion-dsl]").forEach((item) => {
    item.hidden = !useDsl;
    setPanelControlsDisabled(item, !useDsl);
  });
}

function syncResultAssertionMode(form) {
  const useDsl = assertionModeValue(form, "result_assertion_mode") === "dsl";
  const blockedByDatabaseSimpleMode = isDatabaseServiceType(form?.elements.service_type?.value)
    && databaseRuleModeValue(form) !== "advanced";
  document.querySelectorAll("[data-result-assertion-visual]").forEach((item) => {
    item.hidden = useDsl;
    setPanelControlsDisabled(item, useDsl || blockedByDatabaseSimpleMode);
  });
  document.querySelectorAll("[data-result-assertion-dsl]").forEach((item) => {
    item.hidden = !useDsl;
    setPanelControlsDisabled(item, !useDsl || blockedByDatabaseSimpleMode);
  });
}

function syncDatabaseRuleMode(form) {
  const isDatabase = isDatabaseServiceType(form?.elements.service_type?.value);
  const useAdvanced = isDatabase && databaseRuleModeValue(form) === "advanced";
  document.querySelectorAll(".database-simple-rule-only").forEach((item) => {
    item.hidden = isDatabase && useAdvanced;
    setPanelControlsDisabled(item, isDatabase && useAdvanced);
  });
  document.querySelectorAll(".result-rule-only").forEach((item) => {
    const hiddenForDatabase = isDatabase && !useAdvanced;
    item.hidden = hiddenForDatabase;
    setPanelControlsDisabled(item, hiddenForDatabase);
  });
  const testRuleButton = document.getElementById("testRuleBtn");
  if (testRuleButton && isDatabase) {
    testRuleButton.disabled = !useAdvanced;
  }
}

function setPanelControlsDisabled(container, disabled) {
  container.querySelectorAll("input, select, textarea, button").forEach((control) => {
    control.disabled = disabled;
  });
}

function assertionModeValue(form, name) {
  return form?.querySelector(`input[name="${name}"]:checked`)?.value
    || form?.elements?.[name]?.value
    || "visual";
}

function setAssertionModeValue(form, name, value) {
  const input = form?.querySelector(`input[name="${name}"][value="${value}"]`);
  if (input) {
    input.checked = true;
  } else if (form?.elements?.[name]) {
    form.elements[name].value = value;
  }
}

function databaseRuleModeValue(form) {
  return assertionModeValue(form, "database_rule_mode");
}

function isDatabaseAdvancedRuleMode(form) {
  return isDatabaseServiceType(form?.elements.service_type?.value) && databaseRuleModeValue(form) === "advanced";
}

function hideAssertionDslPreviews() {
  const apiPreview = document.getElementById("apiAssertionDslPreview");
  if (apiPreview) {
    apiPreview.hidden = true;
    apiPreview.value = "";
  }
  const resultPreview = document.getElementById("resultAssertionDslPreview");
  if (resultPreview) {
    resultPreview.value = "";
    const resultPreviewField = resultPreview.closest(".assertion-preview-field");
    if (resultPreviewField) resultPreviewField.hidden = true;
  }
}

function showApiAssertionDslPreview(form) {
  if (!form) return;
  if (!validateApiVisualAssertionConfig(form)) return;
  const apiPreview = document.getElementById("apiAssertionDslPreview");
  if (!apiPreview) return;
  apiPreview.value = buildApiVisualAssertionExpression(form);
  apiPreview.hidden = false;
}

function showResultAssertionDslPreview(form) {
  if (!form) return;
  if (!validateResultVisualAssertionConfig(form)) return;
  const resultPreview = document.getElementById("resultAssertionDslPreview");
  if (!resultPreview) return;
  const expression = buildResultVisualAssertionExpression();
  resultPreview.value = expression;
  const resultPreviewField = resultPreview.closest(".assertion-preview-field");
  if (resultPreviewField) resultPreviewField.hidden = false;
  if (form.elements.api_assertion_expression) {
    form.elements.api_assertion_expression.value = expression;
  }
}

function validateServiceAssertionConfig(form) {
  if (!form) return true;
  if (form.elements.service_type?.value === "api" && assertionModeValue(form, "api_assertion_mode") === "visual") {
    return validateApiVisualAssertionConfig(form);
  }
  if (isDatabaseAdvancedRuleMode(form) && assertionModeValue(form, "result_assertion_mode") === "visual") {
    return validateResultVisualAssertionConfig(form);
  }
  return true;
}

function validateApiVisualAssertionConfig(form) {
  const responseTime = form.elements.api_response_time_ms?.value?.trim();
  if (responseTime && !isPositiveNumber(responseTime)) {
    return failAssertionInput(form.elements.api_response_time_ms, "响应时间必须填写大于 0 的毫秒数");
  }
  const jsonAssertions = form.elements.api_json_assertions?.value || "";
  const invalidLine = jsonAssertions
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .find((line) => !isValidJsonAssertionLine(line));
  if (invalidLine) {
    return failAssertionInput(form.elements.api_json_assertions, `JSON断言格式不正确：${invalidLine}`);
  }
  return true;
}

function validateResultVisualAssertionConfig(form) {
  if (assertionModeValue(form, "result_assertion_mode") === "dsl") return true;
  const rows = Array.from(document.querySelectorAll("#resultAssertionRows .assertion-rule-row"));
  if (!rows.length) {
    addResultAssertionRuleRow({ type: "json_compare" });
    showToast("至少保留一条可视化断言");
    return false;
  }
  for (const row of rows) {
    const invalid = invalidAssertionRule(row);
    if (invalid) {
      return failAssertionInput(invalid.control, invalid.message);
    }
  }
  return true;
}

function invalidAssertionRule(row) {
  const type = row.querySelector("[data-assertion-type]")?.value || "json_compare";
  const pathInput = row.querySelector("[data-assertion-path]");
  const valueInput = row.querySelector("[data-assertion-value]");
  const path = pathInput?.value?.trim() || "";
  const value = valueInput?.value?.trim() || "";
  if (type === "json_compare") {
    if (!isValidJsonPath(path)) return { control: pathInput, message: "JSON 字段路径需以 $ 开头，例如 $.code 或 $.rows[0].code" };
    if (!value) return { control: valueInput, message: "JSON 字段断言需要填写期望值" };
  } else if (type === "regex_compare") {
    if (!path) return { control: pathInput, message: "正则提取需要填写正则表达式" };
    try {
      new RegExp(path);
    } catch (error) {
      return { control: pathInput, message: "正则表达式格式不正确" };
    }
    if (!value) return { control: valueInput, message: "正则提取需要填写期望值" };
  } else if (type === "response_time") {
    if (!isNonNegativeNumber(value)) return { control: valueInput, message: "响应时间需要填写非负毫秒数" };
  } else if (type === "status") {
    if (!/^\d+$/.test(value) || Number(value) < 100 || Number(value) > 599) {
      return { control: valueInput, message: "HTTP 状态码需要填写 100 到 599 的整数" };
    }
  } else if (!value) {
    return { control: valueInput, message: "文本断言需要填写匹配内容" };
  }
  return null;
}

function failAssertionInput(control, message) {
  if (control) {
    control.focus();
    control.setCustomValidity(message);
    control.reportValidity();
    window.setTimeout(() => control.setCustomValidity(""), 0);
  }
  showToast(message);
  return false;
}

function buildApiAssertionExpression(form) {
  if (assertionModeValue(form, "api_assertion_mode") === "dsl") {
    return form.elements.api_advanced_assertion_expression?.value?.trim() || "";
  }
  return buildApiVisualAssertionExpression(form);
}

function buildApiVisualAssertionExpression(form) {
  const expressions = [];
  const responseTime = form.elements.api_response_time_ms?.value;
  if (responseTime) {
    expressions.push(`responseMs() < ${Number(responseTime)}`);
  }
  const jsonAssertions = form.elements.api_json_assertions?.value || "";
  jsonAssertions.split(/\r?\n/).map((line) => line.trim()).filter(Boolean).forEach((line) => {
    expressions.push(normalizeJsonAssertionLine(line));
  });
  const textValue = form.elements.api_text_assertion_value?.value?.trim();
  if (textValue) {
    const fn = form.elements.api_text_assertion_mode?.value === "not_contains" ? "notContains" : "contains";
    expressions.push(`${fn}("${escapeRuleString(textValue)}")`);
  }
  return expressions.join(" && ");
}

function buildResultAssertionExpression(form) {
  if (assertionModeValue(form, "result_assertion_mode") === "dsl") {
    return form.elements.result_advanced_assertion_expression?.value?.trim() || "";
  }
  return buildResultVisualAssertionExpression();
}

function buildResultVisualAssertionExpression() {
  return Array.from(document.querySelectorAll("#resultAssertionRows .assertion-rule-row"))
    .map((row) => assertionRuleExpression(row))
    .filter(Boolean)
    .join(" && ");
}

function assertionRuleExpression(row) {
  const type = row.querySelector("[data-assertion-type]")?.value || "json_compare";
  const path = row.querySelector("[data-assertion-path]")?.value?.trim() || "";
  const operator = row.querySelector("[data-assertion-operator]")?.value || "==";
  const value = row.querySelector("[data-assertion-value]")?.value?.trim() || "";
  if (["contains", "not_contains", "icontains"].includes(type)) {
    if (!value) return "";
    const fn = { contains: "contains", not_contains: "notContains", icontains: "icontains" }[type];
    return `${fn}("${escapeRuleString(value)}")`;
  }
  if (type === "response_time") {
    if (!value) return "";
    const number = Number(value);
    if (!Number.isFinite(number)) return "";
    return `responseMs() ${operator} ${number}`;
  }
  if (type === "status") {
    if (!value) return "";
    const number = Number(value);
    if (!Number.isFinite(number)) return "";
    return `status() ${operator} ${number}`;
  }
  if (type === "regex_compare") {
    if (!path || !value) return "";
    return `regex("${escapeRuleString(path)}") ${operator} ${formatRuleOperand(value)}`;
  }
  if (!path || !value) return "";
  return `json("${escapeRuleString(path)}") ${operator} ${formatRuleOperand(value)}`;
}

function normalizeJsonAssertionLine(line) {
  if (/^json\s*\(/i.test(line)) return line;
  const match = line.match(/^(\$[^\s=!<>]+)\s*(==|!=|>=|<=|>|<)\s*(.+)$/);
  if (!match) return line;
  return `json("${escapeRuleString(match[1])}") ${match[2]} ${match[3]}`;
}

function isValidJsonAssertionLine(line) {
  const functionMatch = line.match(/^json\s*\(\s*"([^"]+)"\s*\)\s*(==|!=|>=|<=|>|<)\s*(.+)$/i);
  if (functionMatch) {
    return isValidJsonPath(functionMatch[1]) && Boolean(functionMatch[3]?.trim());
  }
  const match = line.match(/^(\$[^\s=!<>]+)\s*(==|!=|>=|<=|>|<)\s*(.+)$/);
  return Boolean(match && isValidJsonPath(match[1]) && match[3]?.trim());
}

function isValidJsonPath(value) {
  return /^\$([.][A-Za-z_][A-Za-z0-9_]*|\[[0-9]+\])*$/.test(String(value || "").trim());
}

function isNonNegativeNumber(value) {
  const number = Number(value);
  return value !== "" && Number.isFinite(number) && number >= 0;
}

function isPositiveNumber(value) {
  const number = Number(value);
  return value !== "" && Number.isFinite(number) && number > 0;
}

function escapeRuleString(value) {
  return String(value).replaceAll("\\", "\\\\").replaceAll('"', '\\"');
}

function formatRuleOperand(value) {
  const text = String(value || "").trim();
  if (!text) return '""';
  if (/^-?\d+(\.\d+)?$/.test(text) || /^(true|false|null)$/i.test(text)) return text.toLowerCase();
  if (/^".*"$/.test(text)) return text;
  return `"${escapeRuleString(text)}"`;
}

function renderApiRequestTestResult(container, result) {
  container.hidden = false;
  container.className = `api-test-result ${result.status === "UP" ? "ok" : "bad"}`;
  const size = formatBytes(result.response_size_bytes);
  const body = result.response_body || "";
  container.innerHTML = `
    <div class="api-test-metrics">
      <div><span>HTTP状态码</span><strong>${escapeHtml(result.http_status_code ?? "-")}</strong></div>
      <div><span>响应时间</span><strong>${escapeHtml(result.response_time_ms ?? "-")}ms</strong></div>
      <div><span>响应大小</span><strong>${escapeHtml(size)}</strong></div>
      <div><span>检测结果</span><strong>${escapeHtml(result.status || "-")}</strong></div>
    </div>
    <pre>${escapeHtml(prettyResponseBody(body))}</pre>
    <small>${escapeHtml(result.message || "-")}</small>
  `;
}

function prettyResponseBody(body) {
  if (!body) return "";
  try {
    return JSON.stringify(JSON.parse(body), null, 2);
  } catch (error) {
    return body;
  }
}

function formatBytes(value) {
  const bytes = Number(value);
  if (!Number.isFinite(bytes)) return "-";
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)}MB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)}KB`;
  return `${bytes}B`;
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
  const databaseConnectionServiceId = isDatabase && data.database_connection_service_id
    ? Number(data.database_connection_service_id)
    : null;
  const usesDatabaseConnectionRef = Boolean(databaseConnectionServiceId);
  data.service_type = serviceType;
  const isWebUrl = isWebUrlServiceType(serviceType);
  const isApi = serviceType === "api";
  const isDatabaseAdvancedRule = isDatabaseAdvancedRuleMode(form);
  if (isApi) {
    const apiVisualMode = assertionModeValue(form, "api_assertion_mode") !== "dsl";
    data.url = normalizeWebUrl(data.api_url, "https");
    data.http_method = data.api_http_method || "GET";
    data.expected_status_code = apiVisualMode && data.api_expected_status_code ? Number(data.api_expected_status_code) : null;
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
    data.api_response_time_ms = apiVisualMode && data.api_response_time_ms ? Number(data.api_response_time_ms) : null;
    data.api_json_assertions = apiVisualMode ? data.api_json_assertions?.trim() || null : null;
    data.api_text_assertion_mode = apiVisualMode ? data.api_text_assertion_mode || "contains" : "contains";
    data.api_text_assertion_value = apiVisualMode ? data.api_text_assertion_value?.trim() || null : null;
  } else {
    data.url = isWebUrl ? normalizeWebUrl(data.url, data.web_scheme) : null;
    data.http_method = isWebUrl ? data.http_method || "GET" : "GET";
    data.response_keyword = isWebUrl ? data.response_keyword || null : null;
    data.api_assertion_expression = isDatabase && isDatabaseAdvancedRule
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
  data.host = isWebUrl || isGenericJdbc || usesDatabaseConnectionRef ? null : data.host?.trim() || null;
  data.database_connection_service_id = databaseConnectionServiceId;
  data.database_name = isDatabase && !isGenericJdbc && !usesDatabaseConnectionRef ? data.database_name || null : null;
  data.database_username = isDatabase && !usesDatabaseConnectionRef ? data.database_username || null : null;
  data.database_password = isDatabase && !usesDatabaseConnectionRef ? data.database_password || null : null;
  data.database_query = isDatabase ? data.database_query || null : null;
  data.expected_result = isDatabase && !isDatabaseAdvancedRule ? data.expected_result || null : null;
  data.database_result_operator = isDatabase && !isDatabaseAdvancedRule ? data.database_result_operator || "fuzzy" : "fuzzy";
  data.database_assertion_fields = isDatabase && isDatabaseAdvancedRule ? selectedDatabaseAssertionFields(form) : [];
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

function buildDatabasePreviewPayload(form) {
  const data = Object.fromEntries(new FormData(form).entries());
  const serviceType = ["http", "https"].includes(data.service_type) ? "web" : data.service_type;
  const isGenericJdbc = serviceType === "jdbc";
  const databaseConnectionServiceId = data.database_connection_service_id ? Number(data.database_connection_service_id) : null;
  const usesDatabaseConnectionRef = Boolean(databaseConnectionServiceId);
  data.service_type = serviceType;
  data.database_connection_service_id = databaseConnectionServiceId;
  data.host = isGenericJdbc || usesDatabaseConnectionRef ? null : data.host?.trim() || null;
  data.port = data.port ? Number(data.port) : null;
  if (!data.port && serviceType === "mysql") data.port = 3306;
  if (!data.port && serviceType === "oracle") data.port = 1521;
  if (!data.port && serviceType === "postgresql") data.port = 5432;
  if (usesDatabaseConnectionRef) data.port = null;
  data.database_name = !isGenericJdbc && !usesDatabaseConnectionRef ? data.database_name || null : null;
  data.database_username = !usesDatabaseConnectionRef ? data.database_username || null : null;
  data.database_password = !usesDatabaseConnectionRef ? data.database_password || null : null;
  data.database_query = data.database_query || null;
  data.jdbc_driver_class = !usesDatabaseConnectionRef ? data.jdbc_driver_class || null : null;
  data.jdbc_url = isGenericJdbc && !usesDatabaseConnectionRef ? data.jdbc_url || null : null;
  data.check_timeout_seconds = Number(data.check_timeout_seconds || 3);
  data.database_assertion_fields = isDatabaseAdvancedRuleMode(form) ? selectedDatabaseAssertionFields(form) : [];
  return data;
}

function buildRuleTestPayload(form) {
  return {
    expression: buildResultAssertionExpression(form),
    status_code: Number(form.elements.rule_test_status_code?.value || 200),
    response_time_ms: Number(form.elements.rule_test_response_time_ms?.value || 0),
    body: form.elements.rule_test_body?.value || "",
  };
}

function renderRuleTestResult(payload, result) {
  const status = result.matched ? "通过" : "不通过";
  const hit = result.hit_content || "-";
  const reason = result.failure_reason || "-";
  return [
    `返回状态码：${escapeHtml(String(payload.status_code))}`,
    `响应耗时：${escapeHtml(String(payload.response_time_ms))}ms`,
    `规则结果：${status}`,
    `命中内容：${escapeHtml(hit)}`,
    `失败原因：${escapeHtml(reason)}`,
  ].join("<br>");
}

function selectedDatabaseAssertionFields(form) {
  const checked = Array.from(document.querySelectorAll("#databasePreviewPanel input[data-database-field]:checked"))
    .map((input) => input.value)
    .filter(Boolean);
  if (checked.length) {
    form.dataset.databaseAssertionFields = JSON.stringify(checked);
    return checked;
  }
  try {
    return JSON.parse(form.dataset.databaseAssertionFields || "[]");
  } catch (error) {
    return [];
  }
}

function renderDatabaseSelectedFields(form, fields = []) {
  form.dataset.databaseAssertionFields = JSON.stringify(fields || []);
  const panel = document.getElementById("databasePreviewPanel");
  if (!panel || !fields?.length) return;
  panel.hidden = false;
  panel.className = "test-result database-only span-2";
  panel.innerHTML = `已选择检测字段：${fields.map((field) => `<code>${escapeHtml(field)}</code>`).join(" ")}`;
}

function renderDatabasePreview(form, preview) {
  const panel = document.getElementById("databasePreviewPanel");
  if (!panel) return;
  const columns = preview?.columns || [];
  const rows = preview?.rows || [];
  const selected = new Set(selectedDatabaseAssertionFields(form));
  panel.hidden = false;
  panel.className = "test-result database-only span-2";
  if (!columns.length) {
    panel.textContent = preview?.message || "查询没有返回结果集";
    return;
  }
  const fieldControls = columns.map((column) => `
    <label class="switch-row">
      <input type="checkbox" data-database-field value="${escapeHtml(column)}" ${selected.has(column) ? "checked" : ""}>
      <span>${escapeHtml(column)}</span>
    </label>
  `).join("");
  const head = columns.map((column) => `<th>${escapeHtml(column)}</th>`).join("");
  const body = rows.map((row) => `
    <tr>${columns.map((column) => `<td>${escapeHtml(row?.[column] ?? "")}</td>`).join("")}</tr>
  `).join("");
  panel.innerHTML = `
    <div class="database-preview-fields">${fieldControls}</div>
    <div class="table-wrap">
      <table>
        <thead><tr>${head}</tr></thead>
        <tbody>${body || `<tr><td colspan="${columns.length}">无数据</td></tr>`}</tbody>
      </table>
    </div>
    <small class="form-hint">${escapeHtml(preview?.message || `预览最多显示 ${preview?.max_rows || 5} 行`)}</small>
  `;
  panel.querySelectorAll("input[data-database-field]").forEach((input) => {
    input.addEventListener("change", () => {
      syncDatabaseAssertionFieldsFromPreview(form, rows);
    });
  });
  syncDatabaseAssertionFieldsFromPreview(form, rows);
}

function syncDatabaseAssertionFieldsFromPreview(form, rows = []) {
  const fields = Array.from(document.querySelectorAll("#databasePreviewPanel input[data-database-field]:checked"))
    .map((input) => input.value)
    .filter(Boolean);
  form.dataset.databaseAssertionFields = JSON.stringify(fields);
  if (fields.length && form.elements.rule_test_body) {
    const projectedRows = rows.map((row) => {
      const item = {};
      fields.forEach((field) => {
        item[field] = row?.[field] ?? null;
        item[String(field).toLowerCase()] = row?.[field] ?? null;
      });
      return item;
    });
    form.elements.rule_test_body.value = JSON.stringify({ rows: projectedRows }, null, 2);
  }
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

async function loadDatabaseConnectionOptions(form, editId = "") {
  const select = form.elements.database_connection_service_id;
  if (!select || !window.LiveMonitorApi?.databaseConnections) return [];
  try {
    const connections = await LiveMonitorApi.databaseConnections(true);
    renderDatabaseConnectionOptions(form, connections, form.elements.service_type?.value, editId);
    return connections;
  } catch (error) {
    select.innerHTML = '<option value="">连接列表加载失败，请手动填写</option>';
    showToast(error.message);
    return [];
  }
}

function renderDatabaseConnectionOptions(form, connections = [], serviceType = "", editId = "") {
  const select = form.elements.database_connection_service_id;
  if (!select) return;
  const current = select.value || "";
  const normalizedType = ["http", "https"].includes(serviceType) ? "web" : serviceType;
  const options = (connections || [])
    .filter((service) => String(service.id || "") !== String(editId || ""))
    .filter((service) => service.service_type === normalizedType);
  select.innerHTML = [
    '<option value="">手动填写连接信息</option>',
    ...options.map((service) => `
      <option value="${escapeHtml(service.id)}">${escapeHtml(databaseConnectionOptionLabel(service))}</option>
    `),
  ].join("");
  if (current && options.some((service) => String(service.id) === String(current))) {
    select.value = current;
  } else {
    select.value = "";
  }
}

function databaseConnectionOptionLabel(service) {
  const cluster = service.cluster_name ? `${service.cluster_name} / ` : "";
  return `${cluster}${service.service_name || "数据库连接"} · ${endpointText(service)}`;
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
  if (form.elements.api_advanced_assertion_expression
    && service.service_type === "api"
    && service.api_assertion_expression
    && !service.api_response_time_ms
    && !service.api_json_assertions
    && !service.api_text_assertion_value) {
    form.elements.api_advanced_assertion_expression.value = service.api_assertion_expression;
  }
  if (service.service_type === "api" && form.elements.api_advanced_assertion_expression) {
    const visualExpression = buildApiVisualAssertionExpression(form);
    const savedExpression = service.api_assertion_expression || "";
    if (savedExpression && savedExpression !== visualExpression) {
      setAssertionModeValue(form, "api_assertion_mode", "dsl");
      form.elements.api_advanced_assertion_expression.value = savedExpression;
    } else {
      setAssertionModeValue(form, "api_assertion_mode", "visual");
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
  syncApiMethodFields(form);
  syncApiAuthFields(form);
  syncApiAssertionMode(form);
  syncDatabaseRuleMode(form);
  syncResultAssertionMode(form);
  hideAssertionDslPreviews();
  renderDatabaseSelectedFields(form, service.database_assertion_fields || []);
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


