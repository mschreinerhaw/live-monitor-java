(function () {
  "use strict";

  var API_BASE = "/gateway";
  var API_CONFIG = {
    loginId: "console",
    loginPwd: "123456",
    namespace: "livedata",
    xAmsToken: "aaabfc8b3eebfdfdb9faf418c285048a",
    servicePath: "/service/com.apex.livedata.fundNetValueCompare/call"
  };

  var state = {
    records: [],
    page: 1,
    pageSize: 10,
    hasQueried: false,
    sessionId: ""
  };

  var els = {};

  function byId(id) {
    return document.getElementById(id);
  }

  function normalizeKey(key) {
    return String(key || "").replace(/[_\-\s]/g, "").toLowerCase();
  }

  function readCaseInsensitive(obj, key, fallback) {
    if (!obj || typeof obj !== "object") {
      return fallback;
    }

    if (Object.prototype.hasOwnProperty.call(obj, key)) {
      return obj[key];
    }

    var target = normalizeKey(key);
    var keys = Object.keys(obj);
    for (var i = 0; i < keys.length; i += 1) {
      if (normalizeKey(keys[i]) === target) {
        return obj[keys[i]];
      }
    }

    return fallback;
  }

  function normalizeRecord(row) {
    return {
      etlDate: readCaseInsensitive(row, "ETL_DATE", ""),
      fundCode: readCaseInsensitive(row, "FUND_CODE", ""),
      fundName: readCaseInsensitive(row, "FUND_NAME", ""),
      valuSysNav: readCaseInsensitive(row, "VALU_SYS_NAV", ""),
      corpOffwebNav: readCaseInsensitive(row, "CORP_OFFWEB_NAV", ""),
      taSysNav: readCaseInsensitive(row, "TA_SYS_NAV", ""),
      xbrlSysNav: readCaseInsensitive(row, "XBRL_SYS_NAV", ""),
      veriRslt: readCaseInsensitive(row, "VERI_RSLT", ""),
      diffExpl: readCaseInsensitive(row, "DIFF_EXPL", ""),
      updtTime: readCaseInsensitive(row, "UPDT_TIME", "")
    };
  }

  function parseRecords(response) {
    var records = readCaseInsensitive(response, "records", null);
    if (!Array.isArray(records)) {
      var data = readCaseInsensitive(response, "data", null);
      records = readCaseInsensitive(data, "records", null);
    }
    if (!Array.isArray(records)) {
      var result = readCaseInsensitive(response, "result", null);
      records = readCaseInsensitive(result, "records", null);
    }
    if (!Array.isArray(records)) {
      return [];
    }
    return records.map(normalizeRecord);
  }

  function readResponseCode(response) {
    return readCaseInsensitive(response, "retu_code", "") ||
      readCaseInsensitive(response, "code", "") ||
      readCaseInsensitive(readCaseInsensitive(response, "data", null), "retu_code", "") ||
      readCaseInsensitive(readCaseInsensitive(response, "data", null), "code", "") ||
      readCaseInsensitive(readCaseInsensitive(response, "result", null), "retu_code", "") ||
      readCaseInsensitive(readCaseInsensitive(response, "result", null), "code", "");
  }

  function readResponseMemo(response) {
    return readCaseInsensitive(response, "memo", "") ||
      readCaseInsensitive(response, "note", "") ||
      readCaseInsensitive(response, "message", "") ||
      readCaseInsensitive(readCaseInsensitive(response, "data", null), "memo", "") ||
      readCaseInsensitive(readCaseInsensitive(response, "data", null), "note", "") ||
      readCaseInsensitive(readCaseInsensitive(response, "data", null), "message", "") ||
      readCaseInsensitive(readCaseInsensitive(response, "result", null), "memo", "") ||
      readCaseInsensitive(readCaseInsensitive(response, "result", null), "note", "") ||
      readCaseInsensitive(readCaseInsensitive(response, "result", null), "message", "");
  }

  function isResponseSuccess(response) {
    var code = readResponseCode(response);
    if (code === "" || code == null) {
      return true;
    }
    var text = String(code).toUpperCase();
    return text === "SUCCESS" || text === "0";
  }

  function isInvalidSessionResponse(response) {
    return String(readResponseCode(response)) === "-10002" ||
      String(readResponseMemo(response)).indexOf("sessionId无效") > -1;
  }

  function escapeHtml(value) {
    return String(value == null ? "" : value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function toTimestamp(value) {
    if (value == null || value === "") {
      return NaN;
    }
    if (value instanceof Date) {
      return value.getTime();
    }
    var text = String(value).trim();
    if (/^\d+$/.test(text)) {
      var number = Number(text);
      return text.length === 10 ? number * 1000 : number;
    }
    var parsed = Date.parse(text);
    return Number.isNaN(parsed) ? NaN : parsed;
  }

  function pad2(value) {
    return String(value).padStart(2, "0");
  }

  function formatDate(value) {
    var raw = String(value == null ? "" : value).trim();
    if (/^\d{8}$/.test(raw)) {
      return raw.slice(0, 4) + "-" + raw.slice(4, 6) + "-" + raw.slice(6, 8);
    }

    var ts = toTimestamp(value);
    if (Number.isNaN(ts)) {
      return value ? String(value) : "-";
    }
    var d = new Date(ts);
    return d.getFullYear() + "-" + pad2(d.getMonth() + 1) + "-" + pad2(d.getDate());
  }

  function formatTime(value) {
    var ts = toTimestamp(value);
    if (Number.isNaN(ts)) {
      return value ? String(value) : "-";
    }
    var d = new Date(ts);
    return pad2(d.getHours()) + ":" + pad2(d.getMinutes()) + ":" + pad2(d.getSeconds());
  }

  function formatNav(value) {
    if (value == null || value === "") {
      return "-";
    }
    var n = Number(value);
    return Number.isFinite(n) ? n.toFixed(4) : String(value);
  }

  function toNumber(value) {
    if (value == null || value === "") {
      return NaN;
    }
    var n = Number(value);
    return Number.isFinite(n) ? n : NaN;
  }

  function sameNav(base, value) {
    if (base == null || value == null || base === "" || value === "") {
      return false;
    }
    var b = Number(base);
    var v = Number(value);
    return Number.isFinite(b) && Number.isFinite(v) && Math.abs(b - v) < 0.00005;
  }

  function navClass(row, value) {
    if (value == null || value === "") {
      return "empty";
    }
    return sameNav(row.valuSysNav, value) ? "nav-same" : "nav-diff";
  }

  function isMissingValue(value) {
    if (value == null || value === "") {
      return true;
    }
    var text = String(value).trim().toLowerCase();
    return text === "-" || text === "null" || text === "undefined";
  }

  function resultType(row) {
    var value = String(row.veriRslt || "").toLowerCase();
    if (value.indexOf("缺") > -1 || value.indexOf("missing") > -1) {
      return "missing";
    }
    if (isMissingValue(row.corpOffwebNav) || isMissingValue(row.taSysNav) || isMissingValue(row.xbrlSysNav)) {
      return "missing";
    }
    if (value.indexOf("全部") > -1 || value.indexOf("一致") > -1 || value.indexOf("success") > -1) {
      return "ok";
    }
    return "risk";
  }

  function resultClass(row) {
    var type = resultType(row);
    return type === "risk" ? "risk" : type === "missing" ? "missing" : "ok";
  }

  function resultText(row) {
    var type = resultType(row);
    return type === "ok" ? "一致" : type === "missing" ? "缺失" : "异常";
  }

  function standardizedDiff(row) {
    if (resultType(row) === "ok") {
      return "-";
    }

    var sources = [
      { name: "官网", value: row.corpOffwebNav },
      { name: "TA", value: row.taSysNav },
      { name: "XBRL", value: row.xbrlSysNav }
    ];

    var missing = sources.filter(function (item) {
      return isMissingValue(item.value);
    });
    if (missing.length === 1) {
      return missing[0].name + "数据缺失";
    }
    if (missing.length > 1) {
      return "多来源数据缺失";
    }

    var diffSources = sources.filter(function (item) {
      return !sameNav(row.valuSysNav, item.value);
    });
    if (diffSources.length === 1) {
      return diffSources[0].name + "净值不一致";
    }
    if (diffSources.length > 1) {
      return "多来源净值不一致";
    }
    return "-";
  }

  function rowHtml(row) {
    return [
      "<tr>",
      "<td>" + escapeHtml(formatDate(row.etlDate)) + "</td>",
      "<td>" + escapeHtml(row.fundCode || "-") + "</td>",
      "<td>" + escapeHtml(row.fundName || "-") + "</td>",
      "<td class=\"nav-base\">" + escapeHtml(formatNav(row.valuSysNav)) + "</td>",
      "<td class=\"" + navClass(row, row.corpOffwebNav) + "\">" + escapeHtml(formatNav(row.corpOffwebNav)) + "</td>",
      "<td class=\"" + navClass(row, row.taSysNav) + "\">" + escapeHtml(formatNav(row.taSysNav)) + "</td>",
      "<td class=\"" + navClass(row, row.xbrlSysNav) + "\">" + escapeHtml(formatNav(row.xbrlSysNav)) + "</td>",
      "<td><span class=\"pill " + resultClass(row) + "\">" + escapeHtml(resultText(row)) + "</span></td>",
      "<td>" + escapeHtml(standardizedDiff(row)) + "</td>",
      "<td>" + escapeHtml(formatTime(row.updtTime)) + "</td>",
      "</tr>"
    ].join("");
  }

  function renderOverview() {
    var total = state.records.length;
    var missing = state.records.filter(function (row) {
      return resultType(row) === "missing";
    }).length;
    var abnormal = state.records.filter(function (row) {
      return resultType(row) === "risk";
    }).length;
    var ok = state.records.filter(function (row) {
      return resultType(row) === "ok";
    }).length;
    var rate = total ? (ok / total * 100).toFixed(2) + "%" : "0.00%";

    els.totalMetric.textContent = String(total);
    els.abnormalMetric.textContent = String(abnormal);
    els.missingMetric.textContent = String(missing);
    els.rateMetric.textContent = rate;
  }

  function render() {
    var total = state.records.length;
    var maxPage = Math.max(1, Math.ceil(total / state.pageSize));
    state.page = Math.min(Math.max(1, state.page), maxPage);

    var start = (state.page - 1) * state.pageSize;
    var pageRecords = state.records.slice(start, start + state.pageSize);

    if (!pageRecords.length) {
      var emptyText = state.hasQueried ? "当前条件未查询到核验数据" : "请选择交易日期后进行查询";
      els.body.innerHTML = "<tr><td colspan=\"10\" class=\"empty\">" + emptyText + "</td></tr>";
    } else {
      els.body.innerHTML = pageRecords.map(rowHtml).join("");
    }

    els.totalText.textContent = "共 " + total + " 条核验记录";
    els.pageNo.textContent = String(state.page);
    els.prevPage.disabled = state.page <= 1;
    els.nextPage.disabled = state.page >= maxPage;
    renderOverview();
  }

  function setMessage(text, isError) {
    els.message.textContent = text || "";
    els.message.classList.toggle("error", Boolean(isError));
  }

  function joinUrl(baseUrl, path) {
    var base = String(baseUrl || "").trim().replace(/\/+$/, "");
    var suffix = String(path || "").replace(/^\/+/, "");
    return suffix ? base + "/" + suffix : base;
  }

  function jsonHeaders() {
    return {
      Accept: "*/*",
      "Content-Type": "application/json;charset=UTF-8"
    };
  }

  async function postJson(url, payload) {
    var response;
    try {
      response = await fetch(url, {
        method: "POST",
        headers: jsonHeaders(),
        cache: "no-store",
        body: JSON.stringify(payload)
      });
    } catch (err) {
      throw new Error("请求未发通，请检查网关地址、网络连通性或浏览器跨域 CORS 配置。建议通过同源代理访问网关。");
    }

    if (!response.ok) {
      throw new Error("接口请求失败：" + response.status);
    }

    return response.json();
  }

  function formatApiDate(value) {
    return String(value || "").replace(/-/g, "");
  }

  function getDefaultEtlDate() {
    var d = new Date();
    d.setDate(d.getDate() - 1);
    return d.getFullYear() + "-" + pad2(d.getMonth() + 1) + "-" + pad2(d.getDate());
  }

  function readSessionId(response) {
    return readCaseInsensitive(response, "sessionId", "") ||
      readCaseInsensitive(response, "session_id", "") ||
      readCaseInsensitive(readCaseInsensitive(response, "data", null), "sessionId", "") ||
      readCaseInsensitive(readCaseInsensitive(response, "data", null), "session_id", "") ||
      readCaseInsensitive(readCaseInsensitive(response, "result", null), "sessionId", "") ||
      readCaseInsensitive(readCaseInsensitive(response, "result", null), "session_id", "");
  }

  async function loginGateway() {
    var result = await postJson(joinUrl(API_BASE, "/login"), {
      loginId: API_CONFIG.loginId,
      loginPwd: API_CONFIG.loginPwd
    });
    var sessionId = readSessionId(result);
    if (!sessionId) {
      if (!isResponseSuccess(result)) {
        throw new Error(readResponseMemo(result) || "登录失败");
      }
      throw new Error("登录响应中未获取到 sessionId。");
    }
    state.sessionId = sessionId;
    return sessionId;
  }

  async function callFundNetValueCompare(sessionId, etlDate, fundCode) {
    return postJson(joinUrl(API_BASE, API_CONFIG.servicePath), {
      sessionId: sessionId,
      namespace: API_CONFIG.namespace,
      head: {
        "x-ams-token": API_CONFIG.xAmsToken
      },
      data: {
        etl_date: etlDate,
        fund_code: fundCode || ""
      }
    });
  }

  async function queryFundNetValueCompare(etlDate, fundCode) {
    var sessionId = await loginGateway();
    var result = await callFundNetValueCompare(sessionId, etlDate, fundCode);
    if (isInvalidSessionResponse(result)) {
      sessionId = await loginGateway();
      result = await callFundNetValueCompare(sessionId, etlDate, fundCode);
    }
    return result;
  }

  function applyLocalFilters(records) {
    var keyword = els.fundCode.value.trim().toLowerCase();
    var date = els.etlDate.value.trim();

    return records.filter(function (row) {
      var rowCode = String(row.fundCode || "").toLowerCase();
      var rowName = String(row.fundName || "").toLowerCase();
      var codeMatched = !keyword || rowCode.indexOf(keyword) > -1 || rowName.indexOf(keyword) > -1;
      var dateMatched = !date || formatDate(row.etlDate) === date;
      return codeMatched && dateMatched;
    });
  }

  async function query() {
    setMessage("", false);
    if (!els.etlDate.value.trim() && !els.fundCode.value.trim()) {
      state.records = [];
      state.page = 1;
      state.hasQueried = false;
      els.loadSummary.textContent = "未执行查询";
      setMessage("请输入交易日期或基金代码/基金名称后查询。", true);
      render();
      return;
    }

    els.queryBtn.disabled = true;
    els.queryBtn.textContent = "查询中";
    els.loadSummary.textContent = "正在获取数据";

    try {
      var data = await queryFundNetValueCompare(formatApiDate(els.etlDate.value.trim()), els.fundCode.value.trim());
      if (!isResponseSuccess(data)) {
        throw new Error(readResponseMemo(data) || "接口返回失败");
      }

      state.records = applyLocalFilters(parseRecords(data));
      state.page = 1;
      state.hasQueried = true;
      els.loadSummary.textContent = abnormalRows().length ? "发现异常数据" : "查询完成";
      setMessage("", false);
      render();
    } catch (err) {
      state.records = [];
      state.hasQueried = true;
      els.loadSummary.textContent = "查询失败";
      setMessage(err && err.message ? err.message : "查询失败", true);
      render();
    } finally {
      els.queryBtn.disabled = false;
      els.queryBtn.textContent = "查询";
    }
  }

  function abnormalRows() {
    return state.records.filter(function (row) {
      return resultType(row) !== "ok";
    });
  }

  function resetFilters() {
    els.etlDate.value = getDefaultEtlDate();
    els.fundCode.value = "";
    state.records = [];
    state.page = 1;
    state.hasQueried = false;
    els.loadSummary.textContent = "未执行查询";
    setMessage("", false);
    render();
  }

  function downloadText(filename, text) {
    var blob = new Blob(["\ufeff" + text], { type: "text/csv;charset=utf-8" });
    var url = URL.createObjectURL(blob);
    var a = document.createElement("a");
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  }

  function exportReport() {
    var rows = abnormalRows();
    if (!rows.length) {
      setMessage("当前没有异常数据可导出。", false);
      return;
    }

    var header = ["交易日期", "基金代码", "基金名称", "估值净值", "官网净值", "TA净值", "XBRL净值", "核验结果", "差异说明", "更新时间"];
    var lines = [header.join(",")].concat(rows.map(function (row) {
      return [
        formatDate(row.etlDate),
        row.fundCode,
        row.fundName,
        formatNav(row.valuSysNav),
        formatNav(row.corpOffwebNav),
        formatNav(row.taSysNav),
        formatNav(row.xbrlSysNav),
        resultText(row),
        standardizedDiff(row),
        formatTime(row.updtTime)
      ].map(function (value) {
        return "\"" + String(value == null ? "" : value).replace(/"/g, "\"\"") + "\"";
      }).join(",");
    }));

    downloadText("fund_check_abnormal_report.csv", lines.join("\n"));
  }

  function bindEvents() {
    els.queryBtn.addEventListener("click", query);
    els.resetBtn.addEventListener("click", resetFilters);
    els.exportBtn.addEventListener("click", exportReport);
    els.prevPage.addEventListener("click", function () {
      state.page -= 1;
      render();
    });
    els.nextPage.addEventListener("click", function () {
      state.page += 1;
      render();
    });
    els.fundCode.addEventListener("keydown", function (event) {
      if (event.key === "Enter") {
        query();
      }
    });
  }

  function init() {
    els = {
      etlDate: byId("etlDate"),
      fundCode: byId("fundCode"),
      queryBtn: byId("queryBtn"),
      resetBtn: byId("resetBtn"),
      exportBtn: byId("exportBtn"),
      body: byId("fundTableBody"),
      totalText: byId("totalText"),
      pageNo: byId("pageNo"),
      prevPage: byId("prevPage"),
      nextPage: byId("nextPage"),
      loadSummary: byId("loadSummary"),
      message: byId("message"),
      totalMetric: byId("totalMetric"),
      abnormalMetric: byId("abnormalMetric"),
      missingMetric: byId("missingMetric"),
      rateMetric: byId("rateMetric")
    };

    bindEvents();
    els.etlDate.value = getDefaultEtlDate();
    render();
  }

  document.addEventListener("DOMContentLoaded", init);
})();
