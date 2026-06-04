function statusLabel(status) {
  return status || "UNKNOWN";
}

function serviceTypeLabel(type) {
  return {
    web: "Web 应用 (HTTP/HTTPS)",
    api: "API 接口请求",
    http: "HTTP 应用",
    https: "HTTPS 应用",
    nginx: "Nginx",
    host: "服务器主机",
    process: "进程检测",
    port: "端口检测 (TCP/UDP)",
    tcp: "TCP 端口",
    redis: "Redis",
    zookeeper: "ZooKeeper",
    mysql: "MySQL",
    oracle: "Oracle",
    postgresql: "PostgreSQL",
    postgres: "PostgreSQL",
    jdbc: "通用 JDBC",
  }[type] || type;
}

function serviceTypeIcon(type) {
  return {
    web: "globe",
    api: "braces",
    http: "globe",
    https: "lock",
    nginx: "network",
    host: "server",
    process: "activity",
    port: "plug",
    tcp: "plug",
    redis: "layers-3",
    zookeeper: "workflow",
    mysql: "database",
    oracle: "database",
    postgresql: "database",
    postgres: "database",
    jdbc: "database",
  }[type] || "server";
}

function serviceTypeIconHtml(type) {
  return `<span class="service-type-icon service-type-${escapeHtml(type || "custom")}" title="${escapeHtml(serviceTypeLabel(type))}">
    <i data-lucide="${serviceTypeIcon(type)}"></i>
  </span>`;
}

function renderServiceTypeOptionContent(type, label) {
  return `${serviceTypeIconHtml(type)}<span>${escapeHtml(label || serviceTypeLabel(type))}</span>`;
}

function initServiceTypePicker(select) {
  if (!select || select.dataset.enhanced === "true") return;
  select.dataset.enhanced = "true";

  const picker = document.createElement("div");
  picker.className = "service-type-picker";
  picker.innerHTML = `
    <button class="service-type-trigger" type="button" aria-haspopup="listbox" aria-expanded="false"></button>
    <div class="service-type-menu" role="listbox" hidden></div>
  `;
  select.insertAdjacentElement("afterend", picker);

  const trigger = picker.querySelector(".service-type-trigger");
  const menu = picker.querySelector(".service-type-menu");

  const close = () => {
    picker.classList.remove("open");
    trigger.setAttribute("aria-expanded", "false");
    menu.hidden = true;
  };
  const open = () => {
    picker.classList.add("open");
    trigger.setAttribute("aria-expanded", "true");
    menu.hidden = false;
    const active = menu.querySelector(".service-type-option.active") || menu.querySelector(".service-type-option");
    active?.focus();
  };
  const render = () => {
    const selected = select.selectedOptions[0] || select.options[0];
    trigger.innerHTML = `
      <span class="service-type-trigger-content">
        ${renderServiceTypeOptionContent(selected.value, selected.textContent.trim())}
      </span>
      <i data-lucide="chevron-down"></i>
    `;
    menu.innerHTML = Array.from(select.options).map((option) => `
      <button class="service-type-option ${option.value === select.value ? "active" : ""}" type="button" role="option" data-value="${escapeHtml(option.value)}" aria-selected="${option.value === select.value}">
        ${renderServiceTypeOptionContent(option.value, option.textContent.trim())}
      </button>
    `).join("");
    if (window.lucide) window.lucide.createIcons();
  };

  trigger.addEventListener("click", () => {
    if (picker.classList.contains("open")) {
      close();
    } else {
      render();
      open();
    }
  });
  trigger.addEventListener("keydown", (event) => {
    if (event.key === "ArrowDown" || event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      render();
      open();
    }
  });
  menu.addEventListener("click", (event) => {
    const option = event.target.closest(".service-type-option");
    if (!option) return;
    select.value = option.dataset.value;
    select.dispatchEvent(new Event("change", { bubbles: true }));
    close();
    trigger.focus();
  });
  menu.addEventListener("keydown", (event) => {
    const options = Array.from(menu.querySelectorAll(".service-type-option"));
    const index = options.indexOf(document.activeElement);
    if (event.key === "Escape") {
      event.preventDefault();
      close();
      trigger.focus();
    }
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      const nextIndex = event.key === "ArrowDown"
        ? Math.min(options.length - 1, index + 1)
        : Math.max(0, index - 1);
      options[nextIndex]?.focus();
    }
  });
  document.addEventListener("click", (event) => {
    if (!picker.contains(event.target)) close();
  });
  select.addEventListener("change", render);
  render();
}

function formatTime(value) {
  if (!value) return "-";
  const normalized = value.includes("T") ? value : `${value.replace(" ", "T")}Z`;
  const date = new Date(normalized);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("zh-CN", { hour12: false });
}

function endpointText(service) {
  if (isWebUrlServiceType(service.service_type)) return service.url || "-";
  if (service.service_type === "process") {
    return `${service.host || "-"} / ${service.process_name || service.process_match_keyword || "-"} / ${service.check_command || "-"}`;
  }
  if (service.service_type === "host") {
    return `${service.host || service.endpoint || "-"} / CPU ${alertThresholdText(service.cpu_threshold_percent, service.cpu_alert_enabled)} / 内存 ${alertThresholdText(service.memory_threshold_percent, service.memory_alert_enabled)} / 磁盘 ${alertThresholdText(service.disk_threshold_percent, service.disk_alert_enabled)}`;
  }
  if (service.service_type === "jdbc") {
    return service.jdbc_url || service.endpoint || "-";
  }
  if (["mysql", "oracle", "postgresql", "postgres"].includes(service.service_type)) {
    const endpoint = `${service.host || "-"}:${service.port || "-"}`;
    return service.database_name ? `${endpoint}/${service.database_name}` : endpoint;
  }
  return `${service.host || "-"}:${service.port || "-"}`;
}

function alertThresholdText(value, enabled = true) {
  if (enabled === false) return "关闭";
  return value === null || value === undefined || value === "" ? "-%" : `${value}%`;
}

function serviceDetailHref(id) {
  return `/services/${id}`;
}

function serviceEditHref(id) {
  return `/services/${id}/edit`;
}

function isHttpService(service) {
  return isWebUrlServiceType(service.service_type) && /^https?:\/\//i.test(service.url || "");
}

function isWebUrlServiceType(type) {
  return ["web", "api", "http", "https", "nginx"].includes(type);
}

function serviceOpenButton(service) {
  if (!isHttpService(service)) return "";
  return `
    <a title="打开服务" href="${escapeHtml(service.url)}" target="_blank" rel="noopener">
      <i data-lucide="external-link"></i>
    </a>
  `;
}

function endpointHtml(service) {
  const text = escapeHtml(endpointText(service));
  if (!isHttpService(service)) return text;
  return `<a class="endpoint-link" href="${escapeHtml(service.url)}" target="_blank" rel="noopener">${text}</a>`;
}

function showToast(message) {
  const toast = document.getElementById("toast");
  if (!toast) {
    alert(message);
    return;
  }
  toast.textContent = message;
  toast.hidden = false;
  window.clearTimeout(showToast.timer);
  showToast.timer = window.setTimeout(() => {
    toast.hidden = true;
  }, 2600);
}

function renderStatus(status) {
  const value = statusLabel(status);
  return `<span class="status-pill status-${value}"><span class="status-dot"></span>${value}</span>`;
}


function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function alertContentText(content) {
  const raw = String(content || "").trim();
  if (!raw) return "-";
  const htmlLike = /<\/?[a-z][\s\S]*>/i.test(raw) || raw.includes("&lt;");
  if (!htmlLike || typeof document === "undefined") {
    return raw.replace(/\s+/g, " ").trim();
  }
  const decoded = decodeHtmlEntities(raw);
  const blockAware = decoded
    .replace(/<\s*br\s*\/?>/gi, "\n")
    .replace(/<\/\s*(div|p|li|tr|section|article|h[1-6])\s*>/gi, "\n");
  const template = document.createElement("template");
  template.innerHTML = blockAware;
  return (template.content.textContent || "")
    .replace(/\r/g, "")
    .split("\n")
    .map((line) => line.replace(/\s+/g, " ").trim())
    .filter(Boolean)
    .join("\n")
    .trim() || raw.replace(/\s+/g, " ").trim();
}

function decodeHtmlEntities(value) {
  if (typeof document === "undefined") return String(value || "");
  const textarea = document.createElement("textarea");
  textarea.innerHTML = String(value || "");
  return textarea.value;
}

function alertContentSummary(content) {
  const lines = alertContentText(content).split(/\n+/).map((line) => line.trim()).filter(Boolean);
  const reason = alertSectionValue(lines, "\u544a\u8b66\u539f\u56e0");
  const host = alertFieldValue(lines, "\u4e3b\u673aIP");
  const level = alertFieldValue(lines, "\u544a\u8b66\u7ea7\u522b");
  const time = alertFieldValue(lines, "\u544a\u8b66\u65f6\u95f4");
  const metrics = lines.filter((line) => /^(CPU|\u5185\u5b58|\u78c1\u76d8)[\uff1a:]/.test(line)).slice(0, 3);
  const fallback = lines.find((line) => !line.includes("style=") && !line.startsWith("<")) || "-";
  return {
    title: lines[0] || "\u544a\u8b66",
    reason: reason || fallback,
    host,
    level,
    time,
    metrics,
    text: lines.join("\n"),
  };
}

function alertFieldValue(lines, label) {
  const prefix = `${label}\uff1a`;
  const asciiPrefix = `${label}:`;
  for (const line of lines) {
    if (line.startsWith(prefix)) return line.slice(prefix.length).trim();
    if (line.startsWith(asciiPrefix)) return line.slice(asciiPrefix.length).trim();
  }
  return "";
}

function alertSectionValue(lines, label) {
  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index];
    const direct = alertFieldValue([line], label);
    if (direct) return direct;
    if (line === label && lines[index + 1]) return lines[index + 1];
  }
  return "";
}

function serviceTypeLabel(type) {
  return {
    web: "\u0057\u0065\u0062 \u5e94\u7528 (HTTP/HTTPS)",
    api: "API \u63a5\u53e3\u8bf7\u6c42",
    http: "HTTP \u5e94\u7528",
    https: "HTTPS \u5e94\u7528",
    nginx: "Nginx",
    host: "\u670d\u52a1\u5668\u4e3b\u673a",
    process: "\u8fdb\u7a0b\u670d\u52a1",
    port: "\u7aef\u53e3\u670d\u52a1",
    tcp: "TCP \u7aef\u53e3",
    redis: "Redis",
    zookeeper: "ZooKeeper",
    mysql: "MySQL",
    oracle: "Oracle",
    postgresql: "PostgreSQL",
    postgres: "PostgreSQL",
    jdbc: "\u901a\u7528 JDBC",
  }[type] || type || "\u81ea\u5b9a\u4e49\u670d\u52a1";
}

function intervalToSeconds(value, unit) {
  const number = Math.max(1, Number(value || 60));
  const multipliers = {
    seconds: 1,
    minutes: 60,
    hours: 3600,
    days: 86400,
  };
  return Math.min(Math.round(number * (multipliers[unit] || 1)), 31536000);
}

function secondsToIntervalParts(seconds) {
  const value = Math.max(1, Number(seconds || 60));
  if (value % 86400 === 0) return { value: value / 86400, unit: "days" };
  if (value % 3600 === 0) return { value: value / 3600, unit: "hours" };
  if (value % 60 === 0) return { value: value / 60, unit: "minutes" };
  return { value, unit: "seconds" };
}

function formatCheckInterval(seconds) {
  const parts = secondsToIntervalParts(seconds);
  const labels = {
    seconds: "\u79d2",
    minutes: "\u5206\u949f",
    hours: "\u5c0f\u65f6",
    days: "\u5929",
  };
  return `${parts.value} ${labels[parts.unit] || "\u79d2"}`;
}


