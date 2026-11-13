function initCrossDatabaseBuilder(form) {
  const container = document.getElementById("crossDatabaseQueryRows");
  if (!container || container.dataset.initialized === "true") return;
  container.dataset.initialized = "true";
  document.getElementById("addCrossDatabaseQueryBtn")?.addEventListener("click", () => {
    addCrossDatabaseQueryRow(form, { alias: `db${container.querySelectorAll(".cross-db-query-row").length + 1}` });
  });
  if (!container.querySelector(".cross-db-query-row")) {
    addCrossDatabaseQueryRow(form, { alias: "A" });
    addCrossDatabaseQueryRow(form, { alias: "B" });
  }
}

function initCompareRuleTemplate(form) {
  const button = document.getElementById("applyCompareRuleTemplateBtn");
  if (!button || button.dataset.initialized === "true") return;
  button.dataset.initialized = "true";
  const templateSelect = document.getElementById("compareRuleTemplate");
  const thresholdField = document.querySelector(".compare-threshold-field");
  const syncThresholdVisibility = () => {
    const needsThreshold = ["abs_diff", "pct_diff"].includes(templateSelect?.value);
    if (thresholdField) thresholdField.hidden = !needsThreshold;
  };
  templateSelect?.addEventListener("change", syncThresholdVisibility);
  syncThresholdVisibility();
  button.addEventListener("click", () => {
    const expression = compareRuleTemplateExpression();
    if (!expression) return;
    setAssertionModeValue(form, "result_assertion_mode", "dsl");
    if (form.elements.result_advanced_assertion_expression) {
      form.elements.result_advanced_assertion_expression.value = expression;
    }
    syncResultAssertionMode(form);
    hideAssertionDslPreviews();
    showToast("比对规则已生成");
  });
}

function compareRuleTemplateExpression() {
  const template = document.getElementById("compareRuleTemplate")?.value || "same_values";
  const left = document.getElementById("compareRuleLeftField")?.value?.trim() || "";
  const right = document.getElementById("compareRuleRightField")?.value?.trim() || "";
  const threshold = document.getElementById("compareRuleThreshold")?.value?.trim() || "";
  if (!isValidSourceFieldPath(left)) {
    showToast("左字段需要填写为 A.FIELD_NAME 格式");
    document.getElementById("compareRuleLeftField")?.focus();
    return "";
  }
  if (!isValidSourceFieldPath(right)) {
    showToast("右字段需要填写为 B.FIELD_NAME 格式");
    document.getElementById("compareRuleRightField")?.focus();
    return "";
  }
  if (template === "same_values") {
    return `sameValues("${escapeRuleString(left)}", "${escapeRuleString(right)}")`;
  }
  if (template === "exact") {
    return `field("${escapeRuleString(left)}") == field("${escapeRuleString(right)}")`;
  }
  if (!isNonNegativeNumber(threshold)) {
    showToast("阈值需要填写非负数字");
    document.getElementById("compareRuleThreshold")?.focus();
    return "";
  }
  const fn = template === "pct_diff" ? "pctDiff" : "absDiff";
  return `${fn}(field("${escapeRuleString(left)}"), field("${escapeRuleString(right)}")) <= ${Number(threshold)}`;
}

function renderCrossDatabaseQueryRows(form, rows = []) {
  const container = document.getElementById("crossDatabaseQueryRows");
  if (!container) return;
  container.innerHTML = "";
  const items = rows.length ? rows : [{ alias: "A" }, { alias: "B" }];
  items.forEach((row) => addCrossDatabaseQueryRow(form, row));
}

function addCrossDatabaseQueryRow(form, query = {}) {
  const container = document.getElementById("crossDatabaseQueryRows");
  if (!container) return;
  const fieldMapping = query.field_mapping || query.fieldMapping || {};
  const row = document.createElement("div");
  row.className = "cross-db-query-row";
  row.innerHTML = `
    <div class="cross-db-query-head">
      <input data-cross-db-alias aria-label="数据源别名" placeholder="别名，如 A" value="${escapeHtml(query.alias || "")}">
      <select data-cross-db-source aria-label="引用数据源"></select>
      <button class="ghost-button compact-button" data-cross-db-preview-btn type="button">
        <i data-lucide="table-2"></i>
        <span>预览拾取</span>
      </button>
      <button class="icon-button" data-cross-db-delete-btn type="button" title="删除数据源">
        <i data-lucide="trash-2"></i>
      </button>
    </div>
    <textarea data-cross-db-sql rows="3" spellcheck="false" placeholder="SELECT FUND_CODE, FUND_NAME FROM ...">${escapeHtml(query.database_query || query.databaseQuery || "")}</textarea>
    <div class="cross-db-field-config">
      <input data-cross-db-fields placeholder="字段拾取，逗号分隔，如 FUND_CODE,FUND_NAME" value="${escapeHtml((query.assertion_fields || query.assertionFields || []).join(","))}">
      <input data-cross-db-field-mapping placeholder="字段映射，如 fund_code:FUND_CODE,nav:AMOUNT" value="${escapeHtml(formatFieldMapping(fieldMapping))}">
    </div>
    <div class="cross-db-preview" data-cross-db-preview hidden></div>
  `;
  container.appendChild(row);
  renderCrossDatabaseSourceOptionsForRow(row, container.__databaseConnectionOptions || [], query.source_service_id || query.sourceServiceId);
  row.querySelector("[data-cross-db-delete-btn]")?.addEventListener("click", () => {
    if (container.querySelectorAll(".cross-db-query-row").length <= 2) {
      showToast("跨库检测至少保留两个数据源");
      return;
    }
    row.remove();
  });
  row.querySelector("[data-cross-db-preview-btn]")?.addEventListener("click", async () => {
    await previewCrossDatabaseQueryRow(form, row);
  });
  if (window.lucide) window.lucide.createIcons();
}

function renderCrossDatabaseSourceOptions(form, connections = [], editId = "") {
  const container = document.getElementById("crossDatabaseQueryRows");
  if (!container) return;
  container.__databaseConnectionOptions = connections || [];
  container.querySelectorAll(".cross-db-query-row").forEach((row) => {
    const current = row.querySelector("[data-cross-db-source]")?.value || "";
    renderCrossDatabaseSourceOptionsForRow(row, connections, current, editId);
  });
}

function renderCrossDatabaseSourceOptionsForRow(row, connections = [], selected = "", editId = "") {
  const select = row.querySelector("[data-cross-db-source]");
  if (!select) return;
  const selectedText = String(selected || select.value || "");
  const options = (connections || [])
    .filter((service) => String(service.id || "") !== String(editId || ""))
    .map((service) => `<option value="${escapeHtml(service.id)}">${escapeHtml(databaseConnectionOptionLabel(service))}</option>`);
  select.innerHTML = ['<option value="">选择已维护数据源</option>', ...options].join("");
  select.value = selectedText;
}

async function previewCrossDatabaseQueryRow(form, row) {
  const select = row.querySelector("[data-cross-db-source]");
  const sourceId = select?.value || "";
  const container = document.getElementById("crossDatabaseQueryRows");
  const source = (container?.__databaseConnectionOptions || []).find((item) => String(item.id) === String(sourceId));
  const preview = row.querySelector("[data-cross-db-preview]");
  if (!sourceId || !source) {
    showToast("请先选择数据源");
    return;
  }
  const sql = row.querySelector("[data-cross-db-sql]")?.value?.trim() || "";
  if (!sql) {
    showToast("请先填写 SQL");
    return;
  }
  if (preview) {
    preview.hidden = false;
    preview.className = "cross-db-preview testing";
    preview.textContent = "正在查询预览...";
  }
  try {
    const result = await LiveMonitorApi.databasePreview({
      service_type: source.service_type,
      database_connection_service_id: Number(sourceId),
      database_query: sql,
      check_timeout_seconds: Number(form.elements.check_timeout_seconds?.value || 3),
    });
    renderCrossDatabasePreview(row, result);
  } catch (error) {
    if (preview) {
      preview.className = "cross-db-preview bad";
      preview.textContent = error.message;
    }
  }
}

function renderCrossDatabasePreview(row, preview) {
  const panel = row.querySelector("[data-cross-db-preview]");
  const fieldsInput = row.querySelector("[data-cross-db-fields]");
  if (!panel) return;
  const columns = preview?.columns || [];
  const selected = new Set(splitFieldList(fieldsInput?.value || ""));
  panel.hidden = false;
  panel.className = "cross-db-preview";
  panel.innerHTML = `
    <div class="database-field-picker">
      ${columns.map((column) => `
        <label>
          <input type="checkbox" data-cross-db-field value="${escapeHtml(column)}" ${selected.has(column) ? "checked" : ""}>
          <span>${escapeHtml(column)}</span>
        </label>
      `).join("")}
    </div>
    <small class="form-hint">${escapeHtml(preview?.message || `预览最多显示 ${preview?.max_rows || 5} 行`)}</small>
  `;
  panel.querySelectorAll("[data-cross-db-field]").forEach((input) => {
    input.addEventListener("change", () => {
      fieldsInput.value = Array.from(panel.querySelectorAll("[data-cross-db-field]:checked"))
        .map((item) => item.value)
        .join(",");
    });
  });
}

function collectCrossDatabaseQueries() {
  return Array.from(document.querySelectorAll("#crossDatabaseQueryRows .cross-db-query-row"))
    .map((row) => {
      const fieldMapping = parseFieldMapping(row.querySelector("[data-cross-db-field-mapping]")?.value || "");
      return {
        source_service_id: row.querySelector("[data-cross-db-source]")?.value
          ? Number(row.querySelector("[data-cross-db-source]").value)
          : null,
        alias: row.querySelector("[data-cross-db-alias]")?.value?.trim() || "",
        database_query: row.querySelector("[data-cross-db-sql]")?.value?.trim() || "",
        assertion_fields: uniqueStrings([
          ...splitFieldList(row.querySelector("[data-cross-db-fields]")?.value || ""),
          ...Object.keys(fieldMapping),
        ]),
        field_mapping: fieldMapping,
      };
    })
    .filter((item) => item.source_service_id || item.alias || item.database_query || item.assertion_fields.length || Object.keys(item.field_mapping).length);
}

function splitFieldList(value) {
  return String(value || "")
    .split(/[,，\s]+/)
    .map((item) => item.trim())
    .filter(Boolean);
}

function formatFieldMapping(mapping) {
  if (!mapping || typeof mapping !== "object") return "";
  return Object.entries(mapping)
    .filter(([source, target]) => source && target)
    .map(([source, target]) => `${source}:${target}`)
    .join(",");
}

function parseFieldMapping(value) {
  const mapping = {};
  String(value || "")
    .split(/[,，\n]+/)
    .map((item) => item.trim())
    .filter(Boolean)
    .forEach((item) => {
      const parts = item.split(/[:=]/);
      if (parts.length < 2) return;
      const source = parts[0].trim();
      const target = parts.slice(1).join(":").trim();
      if (source && target) mapping[source] = target;
    });
  return mapping;
}

function uniqueStrings(values) {
  const result = [];
  values.forEach((value) => {
    const text = String(value || "").trim();
    if (text && !result.includes(text)) result.push(text);
  });
  return result;
}

