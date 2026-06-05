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
