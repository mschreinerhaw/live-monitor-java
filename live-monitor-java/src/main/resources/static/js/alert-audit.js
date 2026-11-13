(function () {
  const state = {
    page: 1,
    pageSize: 20,
    status: "",
    query: "",
    startTime: "",
    endTime: "",
    trendDays: 14,
  };
  let trendChart = null;
  let currentItems = [];

  function escapeHtml(value) {
    if (value == null) return "";
    return String(value)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
  }

  function formatTime(value) {
    if (!value) return "-";
    try {
      const normalized = String(value).includes("T") ? value : String(value).replace(" ", "T");
      const d = new Date(normalized);
      if (Number.isNaN(d.getTime())) return value;
      return d.toLocaleString("zh-CN", { hour12: false });
    } catch (_) {
      return value;
    }
  }

  // "2026-09-02T10:30" (datetime-local) → "2026-09-02 10:30:00"
  function toServerTime(localValue) {
    if (!localValue) return "";
    const normalized = String(localValue).replace("T", " ");
    return normalized.length === 16 ? `${normalized}:00` : normalized;
  }

  function apiValue(item, snakeKey, camelKey) {
    return item?.[snakeKey] ?? item?.[camelKey];
  }

  function renderMarkdown(value) {
    const source = String(value || "");
    if (!window.marked || !window.DOMPurify) return escapeHtml(source);
    const html = window.marked.parse(source, { breaks: true, gfm: true });
    return window.DOMPurify.sanitize(html, { USE_PROFILES: { html: true } });
  }

  function markdownSummary(value) {
    const container = document.createElement("div");
    container.innerHTML = renderMarkdown(value);
    return container.textContent.replace(/\s+/g, " ").trim() || "无内容";
  }

  function openDetail(index) {
    const row = currentItems[index];
    const dialog = document.getElementById("auditDetailDialog");
    if (!row || !dialog) return;
    const serviceName = apiValue(row, "service_name", "serviceName") || `服务 #${apiValue(row, "service_id", "serviceId") || "-"}`;
    const alertType = apiValue(row, "alert_type", "alertType") || "-";
    const createdAt = formatTime(apiValue(row, "created_at", "createdAt"));
    const notifyMessage = apiValue(row, "notify_message", "notifyMessage");
    document.getElementById("auditDetailTitle").textContent = `通知详情 #${row.id ?? "-"}`;
    document.getElementById("auditDetailMeta").textContent = `${serviceName} · ${alertType} · ${createdAt}`;
    document.getElementById("auditDetailContent").innerHTML = renderMarkdown(notifyMessage);
    dialog.showModal();
    if (window.lucide) window.lucide.createIcons();
  }

  function renderRows(items) {
    const tbody = document.getElementById("auditTableBody");
    currentItems = items || [];
    if (!items || !items.length) {
      tbody.innerHTML = '<tr class="empty-row"><td colspan="6">暂无告警通知记录</td></tr>';
      return;
    }
    tbody.innerHTML = items.map((row, index) => {
      const notifyStatus = apiValue(row, "notify_status", "notifyStatus") || "";
      const serviceId = apiValue(row, "service_id", "serviceId");
      const serviceName = apiValue(row, "service_name", "serviceName");
      const clusterName = apiValue(row, "cluster_name", "clusterName");
      const serviceType = apiValue(row, "service_type", "serviceType");
      const alertType = apiValue(row, "alert_type", "alertType");
      const notifyMessage = apiValue(row, "notify_message", "notifyMessage");
      const messageSummary = markdownSummary(notifyMessage);
      const createdAt = apiValue(row, "created_at", "createdAt");
      const status = String(notifyStatus).toLowerCase();
      const pillClass = status === "success" ? "success" : "failed";
      const serviceLabel = serviceName
        ? (clusterName ? `${escapeHtml(clusterName)} / ${escapeHtml(serviceName)}` : escapeHtml(serviceName))
        : `#${serviceId || "-"}`;
      return `
        <tr>
          <td>${row.id ?? "-"}</td>
          <td>${escapeHtml(formatTime(createdAt))}</td>
          <td>${serviceLabel}<br><small style="color:#94a3b8;">${escapeHtml(serviceType || "")}</small></td>
          <td>${escapeHtml(alertType || "-")}</td>
          <td><span class="status-pill ${pillClass}">${escapeHtml(notifyStatus || "-")}</span></td>
          <td class="audit-message">
            <div class="audit-message-summary">${escapeHtml(messageSummary)}</div>
            <button class="audit-detail-button" type="button" data-detail-index="${index}">查看详情</button>
          </td>
        </tr>
      `;
    }).join("");
  }

  async function load() {
    const params = new URLSearchParams();
    params.set("page", state.page);
    params.set("page_size", state.pageSize);
    if (state.status) params.set("status", state.status);
    if (state.query) params.set("query", state.query);
    if (state.startTime) params.set("start_time", state.startTime);
    if (state.endTime) params.set("end_time", state.endTime);
    document.getElementById("auditTableBody").innerHTML =
      '<tr class="empty-row"><td colspan="6">加载中...</td></tr>';
    try {
      const data = await LiveMonitorApi.alertNotifications(params.toString());
      renderRows(data.items || []);
      document.getElementById("metricTodayTotal").textContent = data.todayTotal ?? 0;
      document.getElementById("metricTodaySuccess").textContent = data.todaySuccess ?? 0;
      document.getElementById("metricTodayFailed").textContent = data.todayFailed ?? 0;
      document.getElementById("metricTotal").textContent = data.total ?? 0;
      const totalPages = data.totalPages || 0;
      document.getElementById("pagerInfo").textContent =
        totalPages > 0 ? `第 ${data.page} / ${totalPages} 页，共 ${data.total} 条` : "无数据";
      document.getElementById("pagerPrevBtn").disabled = (data.page || 1) <= 1;
      document.getElementById("pagerNextBtn").disabled = (data.page || 1) >= totalPages;
      state.page = data.page || 1;
    } catch (err) {
      document.getElementById("auditTableBody").innerHTML =
        `<tr class="empty-row"><td colspan="6">加载失败：${escapeHtml(err.message || err)}</td></tr>`;
    }
  }

  function ensureTrendChart() {
    if (trendChart || !window.echarts) return trendChart;
    const node = document.getElementById("trendChart");
    if (!node) return null;
    trendChart = window.echarts.init(node);
    window.addEventListener("resize", () => trendChart && trendChart.resize());
    return trendChart;
  }

  async function loadTrend() {
    const chart = ensureTrendChart();
    if (!chart) return;
    try {
      const series = await LiveMonitorApi.alertNotificationsTrend(state.trendDays);
      const dates = series.map((row) => row.date);
      const success = series.map((row) => row.success ?? 0);
      const failed = series.map((row) => row.failed ?? 0);
      chart.setOption({
        tooltip: { trigger: "axis" },
        legend: { data: ["成功", "失败"], right: 8, top: 4, textStyle: { fontSize: 12 } },
        grid: { left: 40, right: 16, top: 32, bottom: 28 },
        xAxis: { type: "category", data: dates, axisLabel: { fontSize: 11 } },
        yAxis: { type: "value", minInterval: 1, axisLabel: { fontSize: 11 } },
        series: [
          {
            name: "成功",
            type: "bar",
            stack: "total",
            itemStyle: { color: "#16a34a" },
            data: success,
          },
          {
            name: "失败",
            type: "bar",
            stack: "total",
            itemStyle: { color: "#dc2626" },
            data: failed,
          },
        ],
      });
    } catch (err) {
      chart.setOption({
        title: { text: `加载趋势失败：${err.message || err}`, textStyle: { fontSize: 12, color: "#dc2626" } },
      });
    }
  }

  function applyFilters() {
    state.query = document.getElementById("filterQuery").value.trim();
    state.status = document.getElementById("filterStatus").value;
    state.pageSize = Number(document.getElementById("filterPageSize").value) || 20;
    state.startTime = toServerTime(document.getElementById("filterStart").value);
    state.endTime = toServerTime(document.getElementById("filterEnd").value);
    state.page = 1;
    load();
  }

  function resetFilters() {
    document.getElementById("filterQuery").value = "";
    document.getElementById("filterStatus").value = "";
    document.getElementById("filterStart").value = "";
    document.getElementById("filterEnd").value = "";
    document.getElementById("filterPageSize").value = "20";
    state.query = ""; state.status = ""; state.pageSize = 20;
    state.startTime = ""; state.endTime = "";
    state.page = 1;
    load();
  }

  document.addEventListener("DOMContentLoaded", () => {
    const detailDialog = document.getElementById("auditDetailDialog");
    document.getElementById("auditTableBody").addEventListener("click", (event) => {
      const button = event.target.closest("[data-detail-index]");
      if (button) openDetail(Number(button.dataset.detailIndex));
    });
    document.getElementById("auditDetailCloseBtn").addEventListener("click", () => detailDialog.close());
    detailDialog.addEventListener("cancel", (event) => event.preventDefault());
    document.getElementById("filterApplyBtn").addEventListener("click", applyFilters);
    document.getElementById("filterResetBtn").addEventListener("click", resetFilters);
    document.getElementById("filterQuery").addEventListener("keydown", (e) => {
      if (e.key === "Enter") { e.preventDefault(); applyFilters(); }
    });
    document.getElementById("pagerPrevBtn").addEventListener("click", () => {
      if (state.page > 1) { state.page -= 1; load(); }
    });
    document.getElementById("pagerNextBtn").addEventListener("click", () => {
      state.page += 1; load();
    });
    const trendSelect = document.getElementById("trendDaysSelect");
    if (trendSelect) {
      trendSelect.addEventListener("change", (e) => {
        state.trendDays = Number(e.target.value) || 14;
        loadTrend();
      });
    }
    load();
    loadTrend();
    setInterval(load, 30000);
    setInterval(loadTrend, 60000);
  });
})();
