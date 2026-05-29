function statusLabel(status) {
  return status || "UNKNOWN";
}

function serviceTypeLabel(type) {
  return {
    web: "Web 应用 (HTTP/HTTPS)",
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
  }[type] || type;
}

function serviceTypeIcon(type) {
  return {
    web: "globe",
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
    return `${service.host || service.endpoint || "-"} / CPU ${service.cpu_threshold_percent ?? "-"}% / 磁盘 ${service.disk_threshold_percent ?? "-"}%`;
  }
  if (["mysql", "oracle", "postgresql", "postgres"].includes(service.service_type)) {
    const endpoint = `${service.host || "-"}:${service.port || "-"}`;
    return service.database_name ? `${endpoint}/${service.database_name}` : endpoint;
  }
  return `${service.host || "-"}:${service.port || "-"}`;
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
  return ["web", "http", "https", "nginx"].includes(type);
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

function serviceTypeLabel(type) {
  return {
    web: "\u0057\u0065\u0062 \u5e94\u7528 (HTTP/HTTPS)",
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
  }[type] || type || "\u81ea\u5b9a\u4e49\u670d\u52a1";
}


