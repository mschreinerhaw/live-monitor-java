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

function selectedDatabaseAssertionFields(form) {
  return databaseAssertionFields(form);
}

function databaseAssertionFields(form) {
  if (!form) return [];
  try {
    return JSON.parse(form.dataset.databaseAssertionFields || "[]");
  } catch (error) {
    return [];
  }
}

function renderDatabaseSelectedFields(form, fields = []) {
  setDatabaseAssertionFields(form, fields);
  const panel = document.getElementById("databasePreviewPanel");
  if (!panel || !fields?.length) return;
  panel.hidden = false;
  panel.className = "test-result database-only span-2";
  panel.innerHTML = `已自动拾取字段：${fields.map((field) => `<code>${escapeHtml(field)}</code>`).join(" ")}`;
}

function renderDatabasePreview(form, preview) {
  const panel = document.getElementById("databasePreviewPanel");
  if (!panel) return;
  const columns = databasePreviewColumnNames(preview);
  const columnTypes = databasePreviewColumnTypes(preview, columns);
  const rows = preview?.rows || [];
  panel.hidden = false;
  panel.className = "test-result database-only span-2";
  if (!columns.length) {
    setDatabaseAssertionFields(form, []);
    panel.textContent = preview?.message || "查询没有返回结果集";
    return;
  }
  setDatabaseAssertionFields(form, columns, columnTypes);
  const fieldControls = columns.map((column) => {
    const type = columnTypes[column] || "";
    return `<span class="database-field-chip"><strong>${escapeHtml(column)}</strong>${type ? `<em>${escapeHtml(type)}</em>` : ""}</span>`;
  }).join("");
  const head = columns.map((column) => `<th>${escapeHtml(column)}</th>`).join("");
  const body = rows.map((row) => `
    <tr>${columns.map((column) => `<td>${escapeHtml(row?.[column] ?? "")}</td>`).join("")}</tr>
  `).join("");
  panel.innerHTML = `
    <div class="database-preview-fields">
      <span class="database-preview-fields-label">自动拾取字段</span>
      <div class="database-preview-field-chips">${fieldControls}</div>
    </div>
    <div class="table-wrap">
      <table>
        <thead><tr>${head}</tr></thead>
        <tbody>${body || `<tr><td colspan="${columns.length}">无数据</td></tr>`}</tbody>
      </table>
    </div>
    <small class="form-hint">${escapeHtml(preview?.message || `预览最多显示 ${preview?.max_rows || 5} 行`)}</small>
  `;
  syncDatabaseAssertionFieldsFromPreview(form, rows);
}

function syncDatabaseAssertionFieldsFromPreview(form, rows = []) {
  const fields = databaseAssertionFields(form);
  if (fields.length && form.elements.rule_test_body) {
    const projectedRows = rows.map((row) => {
      const item = {};
      fields.forEach((field) => {
        item[field] = row?.[field] ?? null;
        item[String(field).toLowerCase()] = row?.[field] ?? null;
      });
      return item;
    });
    form.elements.rule_test_body.value = JSON.stringify({ ...(projectedRows[0] || {}), rows: projectedRows }, null, 2);
  }
}

function setDatabaseAssertionFields(form, fields = [], columnTypes = {}) {
  const uniqueFields = Array.from(new Set((fields || []).map((field) => String(field || "").trim()).filter(Boolean)));
  form.dataset.databaseAssertionFields = JSON.stringify(uniqueFields);
  form.dataset.databaseAssertionFieldTypes = JSON.stringify(columnTypes || {});
  renderDatabaseAssertionFieldList(uniqueFields, columnTypes || {});
  syncResultAssertionFieldOptions(form, uniqueFields);
}

function renderDatabaseAssertionFieldList(fields = [], columnTypes = {}) {
  const panel = document.getElementById("databaseAssertionFieldList");
  if (!panel) return;
  panel.hidden = !fields.length;
  if (!fields.length) {
    panel.innerHTML = "";
    return;
  }
  panel.innerHTML = `
    <span>自动拾取字段</span>
    <div class="database-preview-field-chips">
      ${fields.map((field) => {
        const type = columnTypes[field] || "";
        return `<span class="database-field-chip"><strong>${escapeHtml(field)}</strong>${type ? `<em>${escapeHtml(type)}</em>` : ""}</span>`;
      }).join("")}
    </div>
  `;
}

function syncResultAssertionFieldOptions(form, fields = databaseAssertionFields(form)) {
  const datalist = ensureDatabaseAssertionFieldDatalist(form);
  if (datalist) {
    datalist.innerHTML = fields.map((field) => `<option value="${escapeHtml(field)}"></option>`).join("");
  }
  document.querySelectorAll("#resultAssertionRows .assertion-rule-row").forEach((row) => {
    applyResultAssertionFieldOptionsToRow(row, fields);
  });
}

function applyResultAssertionFieldOptionsToRow(row, fields = databaseAssertionFields(document.getElementById("serviceForm"))) {
  const type = row.querySelector("[data-assertion-type]")?.value || "json_compare";
  const pathInput = row.querySelector("[data-assertion-path]");
  const valueInput = row.querySelector("[data-assertion-value]");
  const hasFields = Boolean(fields?.length);
  if (pathInput) {
    if (hasFields && ["json_compare", "field_compare"].includes(type)) {
      pathInput.setAttribute("list", "databaseAssertionFieldOptions");
    } else {
      pathInput.removeAttribute("list");
    }
  }
  if (valueInput) {
    if (hasFields && type === "field_compare") {
      valueInput.setAttribute("list", "databaseAssertionFieldOptions");
    } else {
      valueInput.removeAttribute("list");
    }
  }
}

function ensureDatabaseAssertionFieldDatalist(form) {
  if (!form) return null;
  let datalist = document.getElementById("databaseAssertionFieldOptions");
  if (!datalist) {
    datalist = document.createElement("datalist");
    datalist.id = "databaseAssertionFieldOptions";
    form.appendChild(datalist);
  }
  return datalist;
}

function databasePreviewColumnNames(preview) {
  return (preview?.columns || [])
    .map((column) => typeof column === "string" ? column : column?.name || column?.label || "")
    .filter(Boolean);
}

function databasePreviewColumnTypes(preview, columns = databasePreviewColumnNames(preview)) {
  const source = preview?.columnTypes || preview?.column_types || {};
  if (!Array.isArray(source)) return source || {};
  return columns.reduce((types, column, index) => {
    if (source[index]) types[column] = source[index];
    return types;
  }, {});
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
function isDatabaseServiceType(type) {
  return ["mysql", "oracle", "postgresql", "postgres", "jdbc"].includes(type);
}
