function collectAssertionRuleRows(containerId) {
  return Array.from(document.querySelectorAll(`#${containerId} .assertion-rule-row`)).map((row) => ({
    type: row.querySelector("[data-assertion-type]")?.value || "json_compare",
    path: row.querySelector("[data-assertion-path]")?.value || "",
    operator: row.querySelector("[data-assertion-operator]")?.value || "==",
    value: row.querySelector("[data-assertion-value]")?.value || "",
  }));
}

function collectResultAssertionRuleRows() {
  return collectAssertionRuleRows("resultAssertionRows");
}

const ASSERTION_COMPARISON_OPERATORS = [
  ["==", "等于"],
  ["!=", "不等于"],
  [">", "大于"],
  [">=", "大于等于"],
  ["<", "小于"],
  ["<=", "小于等于"],
];

const ASSERTION_TEXT_OPERATORS = [
  ["contains", "包含"],
  ["notContains", "不包含"],
  ["icontains", "包含（忽略大小写）"],
];

function renderResultAssertionRuleRows(rules = []) {
  renderAssertionRuleRows("resultAssertionRows", rules, [{ type: "json_compare" }]);
}

function renderApiAssertionRuleRows(rules = []) {
  renderAssertionRuleRows("apiAssertionRows", rules, [{ type: "status", operator: "==", value: "200" }]);
}

function renderAssertionRuleRows(containerId, rules = [], defaults = [{ type: "json_compare" }]) {
  const container = document.getElementById(containerId);
  if (!container) return;
  container.innerHTML = "";
  const rows = rules.length ? rules : defaults;
  rows.forEach((rule) => addAssertionRuleRow(container, rule));
  updateAssertionDeleteButtons(container);
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
  initApiAssertionBuilder();
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
  initAssertionBuilder("resultAssertionRows", "addResultAssertionRuleBtn", { type: "json_compare" });
}

function initApiAssertionBuilder() {
  initAssertionBuilder("apiAssertionRows", "addApiAssertionRuleBtn", { type: "status", operator: "==", value: "200" });
}

function initAssertionBuilder(containerId, addButtonId, defaultRule) {
  const container = document.getElementById(containerId);
  if (!container) return;
  document.getElementById(addButtonId)?.addEventListener("click", () => {
    addAssertionRuleRow(container, { type: "json_compare" });
    hideAssertionDslPreviews();
  });
  if (!container.querySelector(".assertion-rule-row")) {
    addAssertionRuleRow(container, defaultRule);
  }
}

function addResultAssertionRuleRow(rule = {}) {
  addAssertionRuleRow(document.getElementById("resultAssertionRows"), rule);
}

function addApiAssertionRuleRow(rule = {}) {
  addAssertionRuleRow(document.getElementById("apiAssertionRows"), rule);
}

function addAssertionRuleRow(container, rule = {}) {
  if (!container) return;
  const row = document.createElement("div");
  row.className = "assertion-rule-row";
  row.innerHTML = `
    <select data-assertion-type aria-label="断言类型">
      <option value="json_compare">JSON 字段</option>
      <option value="field_compare">字段间比较</option>
      <option value="contains">文本包含</option>
      <option value="not_contains">文本不包含</option>
      <option value="icontains">文本包含（忽略大小写）</option>
      <option value="response_time">响应时间</option>
      <option value="status">HTTP 状态码</option>
      <option value="regex_compare">正则提取</option>
    </select>
    <input data-assertion-path aria-label="字段路径" placeholder="$.code" required>
    <select data-assertion-operator aria-label="比较方式">
    </select>
    <input data-assertion-value aria-label="期望值" placeholder="0" required>
    <button class="icon-button" type="button" title="删除断言">
      <i data-lucide="trash-2"></i>
    </button>
  `;
  row.querySelector("[data-assertion-type]").value = rule.type || "json_compare";
  row.querySelector("[data-assertion-path]").value = rule.path || "";
  row.dataset.assertionOperator = rule.operator || "==";
  row.querySelector("[data-assertion-value]").value = rule.value || "";
  row.querySelector("button")?.addEventListener("click", () => {
    if (container.querySelectorAll(".assertion-rule-row").length <= 1) {
      resetAssertionRuleRow(row);
      showToast("至少保留一条可视化断言");
      return;
    }
    row.remove();
    updateAssertionDeleteButtons(container);
    hideAssertionDslPreviews();
  });
  container.appendChild(row);
  updateAssertionRuleRowState(row);
  if (typeof applyResultAssertionFieldOptionsToRow === "function") {
    applyResultAssertionFieldOptionsToRow(row);
  }
  updateAssertionDeleteButtons(container);
  if (window.lucide) window.lucide.createIcons();
}

function updateAssertionRuleRowState(row) {
  const type = row.querySelector("[data-assertion-type]")?.value || "json_compare";
  const pathInput = row.querySelector("[data-assertion-path]");
  const operatorSelect = row.querySelector("[data-assertion-operator]");
  const valueInput = row.querySelector("[data-assertion-value]");
  const typeChanged = row.dataset.assertionType !== type;
  const pendingOperator = row.dataset.assertionOperator || "";
  row.dataset.assertionType = type;
  const textRule = ["contains", "not_contains", "icontains"].includes(type);
  const noPathRule = textRule || type === "response_time" || type === "status";
  const textComparableRule = type === "json_compare" || type === "regex_compare" || type === "field_compare";
  const allowedOperators = textComparableRule
    ? [...ASSERTION_COMPARISON_OPERATORS, ...ASSERTION_TEXT_OPERATORS]
    : ASSERTION_COMPARISON_OPERATORS;
  syncAssertionOperatorOptions(operatorSelect, allowedOperators, pendingOperator);
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
    pathInput.placeholder = "FUND_CODE 或 $.FUND_CODE";
    pathInput.maxLength = 180;
    pathInput.title = '可填写字段名或 JSONPath，例如 FUND_CODE、$.FUND_CODE 或 $.rows[0].code';
    valueInput.placeholder = "0、011389 或 OK";
    valueInput.maxLength = 200;
    valueInput.title = "期望值支持数字、true、false、null 或文本；前导 0 会按文本匹配";
  } else if (type === "field_compare") {
    pathInput.placeholder = "左字段，例如 FUND_CODE";
    pathInput.maxLength = 180;
    pathInput.title = "按同一行比较两个字段，所有行都需要满足";
    valueInput.placeholder = "右字段，例如 fund_code";
    valueInput.maxLength = 180;
    valueInput.title = "填写要比较的右侧字段名";
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
    if (typeChanged && !pendingOperator) operatorSelect.value = "<";
  } else if (type === "status") {
    valueInput.placeholder = "200";
    valueInput.type = "number";
    valueInput.min = "100";
    valueInput.max = "599";
    valueInput.inputMode = "numeric";
    valueInput.title = "填写 100 到 599 的 HTTP 状态码";
    if (typeChanged && !pendingOperator) operatorSelect.value = "==";
  } else {
    valueInput.placeholder = "success";
    valueInput.maxLength = 500;
    valueInput.title = "填写要匹配的响应文本";
  }
  if (type !== "response_time" && type !== "status") {
    valueInput.type = "text";
  }
  delete row.dataset.assertionOperator;
  if (typeof applyResultAssertionFieldOptionsToRow === "function") {
    applyResultAssertionFieldOptionsToRow(row);
  }
  updateAssertionDeleteButtons();
}

function syncAssertionOperatorOptions(select, options, preferred) {
  if (!select) return;
  const current = preferred || select.value || "==";
  const allowed = new Set(options.map(([value]) => value));
  select.innerHTML = options.map(([value, label]) => `<option value="${escapeHtml(value)}">${escapeHtml(label)}</option>`).join("");
  select.value = allowed.has(current) ? current : options[0][0];
}

function resetAssertionRuleRow(row) {
  const defaultRule = row.closest("#apiAssertionRows")
    ? { type: "status", operator: "==", value: "200" }
    : { type: "json_compare", operator: "==", value: "" };
  row.querySelector("[data-assertion-type]").value = defaultRule.type;
  row.querySelector("[data-assertion-path]").value = "";
  row.querySelector("[data-assertion-operator]").value = defaultRule.operator;
  const valueInput = row.querySelector("[data-assertion-value]");
  valueInput.type = "text";
  valueInput.value = defaultRule.value;
  row.dataset.assertionType = "";
  row.dataset.assertionOperator = defaultRule.operator;
  updateAssertionRuleRowState(row);
  hideAssertionDslPreviews();
}

function updateAssertionDeleteButtons(container = null) {
  const containers = container
    ? [container]
    : Array.from(document.querySelectorAll("#apiAssertionRows, #resultAssertionRows"));
  containers.forEach((target) => updateAssertionDeleteButtonsForContainer(target));
}

function updateAssertionDeleteButtonsForContainer(container) {
  if (!container) return;
  const rows = Array.from(container.querySelectorAll(".assertion-rule-row"));
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
  const isCrossDatabase = form?.elements.service_type?.value === "cross_database";
  const useAdvanced = isDatabase && databaseRuleModeValue(form) === "advanced";
  document.querySelectorAll(".database-simple-rule-only").forEach((item) => {
    item.hidden = isDatabase && useAdvanced;
    setPanelControlsDisabled(item, isDatabase && useAdvanced);
  });
  document.querySelectorAll(".result-rule-only").forEach((item) => {
    const hiddenForDatabase = (isDatabase && !useAdvanced) || (!isDatabase && !isCrossDatabase);
    item.hidden = hiddenForDatabase;
    setPanelControlsDisabled(item, hiddenForDatabase);
  });
  const testRuleButton = document.getElementById("testRuleBtn");
  if (testRuleButton && (isDatabase || isCrossDatabase)) {
    testRuleButton.disabled = isDatabase && !useAdvanced;
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

function isResultAssertionRuleMode(form) {
  return form?.elements.service_type?.value === "cross_database" || isDatabaseAdvancedRuleMode(form);
}

function hideAssertionDslPreviews() {
  const apiPreview = document.getElementById("apiAssertionDslPreview");
  if (apiPreview) {
    apiPreview.value = "";
    const apiPreviewField = apiPreview.closest(".assertion-preview-field");
    if (apiPreviewField) apiPreviewField.hidden = true;
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
  const apiPreviewField = apiPreview.closest(".assertion-preview-field");
  if (apiPreviewField) apiPreviewField.hidden = false;
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
  if (form.elements.service_type?.value === "cross_database" && !validateCrossDatabaseConfig(form)) {
    return false;
  }
  if (form.elements.service_type?.value === "api" && assertionModeValue(form, "api_assertion_mode") === "visual") {
    return validateApiVisualAssertionConfig(form);
  }
  if (isResultAssertionRuleMode(form) && assertionModeValue(form, "result_assertion_mode") === "visual") {
    return validateResultVisualAssertionConfig(form);
  }
  return true;
}

function validateCrossDatabaseConfig(form) {
  const rows = Array.from(document.querySelectorAll("#crossDatabaseQueryRows .cross-db-query-row"));
  const activeRows = rows.filter((row) => row.querySelector("[data-cross-db-source]")?.value);
  if (activeRows.length < 2) {
    showToast("跨库检测至少需要选择两个数据源");
    rows[0]?.querySelector("[data-cross-db-source]")?.focus();
    return false;
  }
  const aliases = new Set();
  for (const row of activeRows) {
    const aliasInput = row.querySelector("[data-cross-db-alias]");
    const sqlInput = row.querySelector("[data-cross-db-sql]");
    const mappingInput = row.querySelector("[data-cross-db-field-mapping]");
    const alias = aliasInput?.value?.trim() || "";
    if (!isValidFieldName(alias)) {
      return failAssertionInput(aliasInput, "数据源别名只能包含字母、数字和下划线，并且不能以数字开头");
    }
    if (aliases.has(alias)) {
      return failAssertionInput(aliasInput, "数据源别名不能重复");
    }
    aliases.add(alias);
    if (!sqlInput?.value?.trim()) {
      return failAssertionInput(sqlInput, "跨库检测 SQL 不能为空");
    }
    const invalidMapping = invalidFieldMappingText(mappingInput?.value || "");
    if (invalidMapping) {
      return failAssertionInput(mappingInput, invalidMapping);
    }
  }
  if (!buildResultAssertionExpression(form)) {
    showToast("跨库检测需要填写结果比对断言规则");
    return false;
  }
  return true;
}

function validateApiVisualAssertionConfig(form) {
  if (assertionModeValue(form, "api_assertion_mode") === "dsl") return true;
  return validateAssertionRuleRows("apiAssertionRows", () => addApiAssertionRuleRow({ type: "status", operator: "==", value: "200" }));
}

function validateResultVisualAssertionConfig(form) {
  if (assertionModeValue(form, "result_assertion_mode") === "dsl") return true;
  return validateAssertionRuleRows("resultAssertionRows", () => addResultAssertionRuleRow({ type: "json_compare" }));
}

function validateAssertionRuleRows(containerId, addDefaultRow) {
  const rows = Array.from(document.querySelectorAll(`#${containerId} .assertion-rule-row`));
  if (!rows.length) {
    addDefaultRow();
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
    if (!isValidAssertionFieldPath(path)) return { control: pathInput, message: "JSON 字段可填写字段名或 JSONPath，例如 FUND_CODE、$.FUND_CODE 或 $.rows[0].code" };
    if (!value) return { control: valueInput, message: "JSON 字段断言需要填写期望值" };
  } else if (type === "field_compare") {
    if (!isValidFieldName(path)) return { control: pathInput, message: "左字段名格式不正确" };
    if (!isValidFieldName(value)) return { control: valueInput, message: "右字段名格式不正确" };
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
  return buildAssertionVisualExpression("apiAssertionRows");
}

function buildResultAssertionExpression(form) {
  if (assertionModeValue(form, "result_assertion_mode") === "dsl") {
    return form.elements.result_advanced_assertion_expression?.value?.trim() || "";
  }
  return buildResultVisualAssertionExpression();
}

function buildResultVisualAssertionExpression() {
  return buildAssertionVisualExpression("resultAssertionRows");
}

function buildAssertionVisualExpression(containerId) {
  return Array.from(document.querySelectorAll(`#${containerId} .assertion-rule-row`))
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
    const left = `regex("${escapeRuleString(path)}")`;
    return textOperatorExpression(left, operator, value) || `${left} ${operator} ${formatRuleOperand(value)}`;
  }
  if (type === "field_compare") {
    if (!path || !value) return "";
    return `allRowsCompare("${escapeRuleString(path)}", "${escapeRuleString(operator)}", "${escapeRuleString(value)}")`;
  }
  if (!path || !value) return "";
  const left = assertionFieldAccessor(path);
  return textOperatorExpression(left, operator, value) || `${left} ${operator} ${formatRuleOperand(value)}`;
}

function assertionFieldAccessor(path) {
  const text = String(path || "").trim();
  if (text.startsWith("$")) return `json("${escapeRuleString(text)}")`;
  return `field("${escapeRuleString(text)}")`;
}

function textOperatorExpression(left, operator, value) {
  const fn = { contains: "contains", notContains: "notContains", icontains: "icontains" }[operator];
  if (!fn) return "";
  return `${fn}(${left}, "${escapeRuleString(value)}")`;
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

function isValidAssertionFieldPath(value) {
  const text = String(value || "").trim();
  return isValidJsonPath(text) || isValidFieldName(text);
}

function isValidFieldName(value) {
  return /^[A-Za-z_][A-Za-z0-9_]*$/.test(String(value || "").trim());
}

function isValidSourceFieldPath(value) {
  const text = String(value || "").trim();
  const parts = text.split(".");
  return parts.length === 2 && isValidFieldName(parts[0]) && isValidFieldName(parts[1]);
}

function invalidFieldMappingText(value) {
  const items = String(value || "")
    .split(/[,，\n]+/)
    .map((item) => item.trim())
    .filter(Boolean);
  for (const item of items) {
    const parts = item.split(/[:=]/);
    if (parts.length < 2 || !isValidFieldName(parts[0]) || !isValidFieldName(parts.slice(1).join(":"))) {
      return "字段映射格式应为 source_field:COMMON_FIELD，字段名只能包含字母、数字和下划线";
    }
  }
  return "";
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
  if (/^-?0\d+(\.\d+)?$/.test(text)) return `"${escapeRuleString(text)}"`;
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
