let auditLogSearchTimer = null;

async function initAdmin() {
  document.getElementById("createUserForm")?.addEventListener("submit", handleCreateUserSubmit);
  document.getElementById("resetPasswordForm")?.addEventListener("submit", handleResetPasswordSubmit);
  document.getElementById("addUserBtn")?.addEventListener("click", openCreateUserModal);
  document.getElementById("reloadUsersBtn")?.addEventListener("click", loadAdminUsers);
  document.getElementById("reloadAuditLogsBtn")?.addEventListener("click", () => loadAuditLogs());
  document.getElementById("auditLogSearchForm")?.addEventListener("submit", handleAuditLogSearchSubmit);
  document.getElementById("auditLogSearchInput")?.addEventListener("input", handleAuditLogSearchInput);
  document.getElementById("clearAuditLogSearchBtn")?.addEventListener("click", handleClearAuditLogSearch);
  document.getElementById("auditLogPageSize")?.addEventListener("change", handleAuditLogPageSizeChange);
  document.getElementById("auditLogPagination")?.addEventListener("click", handleAuditLogPaginationClick);
  document.querySelectorAll("[data-admin-tab]").forEach((tab) => {
    tab.addEventListener("click", () => setAdminTab(tab.dataset.adminTab));
  });
  document.getElementById("userTable")?.addEventListener("click", (event) => {
    const resetButton = event.target.closest("[data-reset-user]");
    if (resetButton) {
      openResetPasswordModal(resetButton.dataset.resetUser);
      return;
    }
    const statusButton = event.target.closest("[data-toggle-user]");
    if (statusButton) {
      handleToggleUserStatus(statusButton.dataset.toggleUser, statusButton.dataset.enabled === "true");
      return;
    }
    const deleteButton = event.target.closest("[data-delete-user]");
    if (deleteButton) {
      handleDeleteUser(deleteButton.dataset.deleteUser);
    }
  });
  document.getElementById("createUserModal")?.addEventListener("click", (event) => {
    if (event.target.id === "createUserModal") closeCreateUserModal();
  });
  document.getElementById("resetPasswordModal")?.addEventListener("click", (event) => {
    if (event.target.id === "resetPasswordModal") closeResetPasswordModal();
  });

  try {
    const user = await LiveMonitorApi.currentUser();
    if (!user?.authenticated) {
      window.location.href = "/login.html?redirect=/admin";
      return;
    }
    adminState.currentUser = user;
    if (!user.admin) {
      document.getElementById("adminDeniedPanel").hidden = false;
      return;
    }
    document.getElementById("adminContent").hidden = false;
    document.getElementById("adminCurrentUser").textContent = `当前管理员：${user.user_id || user.display_name || user.name || "-"}`;
    setAdminTab("users");
    await Promise.all([loadAdminUsers(), loadAuditLogs()]);
  } catch (error) {
    showToast(error.message || "用户信息加载失败");
  }

  if (window.lucide) window.lucide.createIcons();
}

function setAdminTab(tabName) {
  const normalized = tabName === "audit" ? "audit" : "users";
  const panels = {
    users: document.getElementById("userListPanel"),
    audit: document.getElementById("auditLogPanel"),
  };
  Object.entries(panels).forEach(([name, panel]) => {
    if (panel) panel.hidden = name !== normalized;
  });
  document.querySelectorAll("[data-admin-tab]").forEach((tab) => {
    const active = tab.dataset.adminTab === normalized;
    tab.classList.toggle("active", active);
    tab.setAttribute("aria-selected", active ? "true" : "false");
  });
  if (normalized === "audit" && !adminState.auditLogsLoaded) {
    loadAuditLogs();
  }
}

async function loadAdminUsers() {
  const table = document.getElementById("userTable");
  if (table) {
    table.innerHTML = '<tr><td colspan="6" class="empty">加载中...</td></tr>';
  }
  try {
    adminState.users = await LiveMonitorApi.users() || [];
    renderAdminUsers();
  } catch (error) {
    if (table) {
      table.innerHTML = `<tr><td colspan="6" class="empty">${escapeHtml(error.message || "用户列表加载失败")}</td></tr>`;
    }
  }
}

function renderAdminUsers() {
  const table = document.getElementById("userTable");
  if (!table) return;
  if (!adminState.users.length) {
    table.innerHTML = '<tr><td colspan="6" class="empty">暂无用户</td></tr>';
    return;
  }
  table.innerHTML = adminState.users.map((user) => {
    const userId = user.user_id || user.userId || "-";
    const enabled = Number(user.status ?? 1) === 1;
    const adminUser = userId.toLowerCase() === "admin";
    const safeUserId = escapeHtml(userId);
    const statusAction = adminUser
      ? ""
      : `<button class="text-button admin-action-text" type="button" data-toggle-user="${safeUserId}" data-enabled="${enabled}">${enabled ? "禁用" : "启用"}</button>`;
    return `
      <tr>
        <td><strong>${safeUserId}</strong></td>
        <td>${escapeHtml(user.name || "-")}</td>
        <td><span class="user-status ${enabled ? "enabled" : "disabled"}">${enabled ? "启用" : "停用"}</span></td>
        <td>${Number(user.logins || 0)}</td>
        <td>${escapeHtml(formatTime(user.last_login || user.lastLogin) || "-")}</td>
        <td class="actions-column">
          <div class="admin-row-actions">
            <button class="text-button admin-action-text" type="button" data-reset-user="${safeUserId}">修改密码</button>
            ${statusAction}
            ${adminUser ? "" : `<button class="text-button admin-action-text danger" type="button" data-delete-user="${safeUserId}">删除</button>`}
          </div>
        </td>
      </tr>
    `;
  }).join("");
  if (window.lucide) window.lucide.createIcons();
}

async function loadAuditLogs(options = {}) {
  const pageState = adminState.auditLogPage;
  if (Object.prototype.hasOwnProperty.call(options, "page")) {
    pageState.page = Math.max(1, Number(options.page) || 1);
  }
  if (Object.prototype.hasOwnProperty.call(options, "pageSize")) {
    pageState.pageSize = Math.max(1, Number(options.pageSize) || 20);
  }
  if (Object.prototype.hasOwnProperty.call(options, "query")) {
    pageState.query = (options.query || "").trim();
  }
  syncAuditLogControls();

  const table = document.getElementById("auditLogTable");
  if (table) {
    table.innerHTML = '<tr><td colspan="4" class="empty">加载中...</td></tr>';
  }
  try {
    const payload = await LiveMonitorApi.auditLogs({
      page: pageState.page,
      pageSize: pageState.pageSize,
      query: pageState.query,
    });
    if (Array.isArray(payload)) {
      adminState.auditLogs = payload;
      pageState.total = payload.length;
      pageState.totalPages = 1;
      pageState.page = 1;
    } else {
      adminState.auditLogs = payload?.items || [];
      pageState.page = Number(payload?.page || pageState.page);
      pageState.pageSize = Number(payload?.page_size || payload?.pageSize || pageState.pageSize);
      pageState.total = Number(payload?.total || 0);
      pageState.totalPages = Number(payload?.total_pages || payload?.totalPages || 1);
    }
    adminState.auditLogsLoaded = true;
    syncAuditLogControls();
    renderAuditLogs();
    renderAuditLogPagination();
  } catch (error) {
    if (table) {
      table.innerHTML = `<tr><td colspan="4" class="empty">${escapeHtml(error.message || "审计日志加载失败")}</td></tr>`;
    }
    renderAuditLogPagination(error.message || "审计日志加载失败");
  }
}

function renderAuditLogs() {
  const table = document.getElementById("auditLogTable");
  if (!table) return;
  if (!adminState.auditLogs.length) {
    const message = adminState.auditLogPage.query ? "未找到匹配的审计日志" : "暂无审计日志";
    table.innerHTML = `<tr><td colspan="4" class="empty">${message}</td></tr>`;
    return;
  }
  table.innerHTML = adminState.auditLogs.map((log) => {
    const userId = log.user_id || log.userId || "-";
    const userName = log.user_name || log.userName || "";
    const action = log.action || "-";
    const actionLabel = action === "LOGOUT" ? "登出" : "登录";
    const eventTime = log.event_time || log.eventTime;
    return `
      <tr>
        <td>
          <strong>${escapeHtml(userId)}</strong>
          ${userName && userName !== userId ? `<span class="audit-user-name">${escapeHtml(userName)}</span>` : ""}
        </td>
        <td>${escapeHtml(log.ip_address || log.ipAddress || "-")}</td>
        <td><span class="audit-action ${action === "LOGOUT" ? "logout" : "login"}">${actionLabel}</span></td>
        <td>${escapeHtml(formatTime(eventTime) || "-")}</td>
      </tr>
    `;
  }).join("");
  if (window.lucide) window.lucide.createIcons();
}

function syncAuditLogControls() {
  const searchInput = document.getElementById("auditLogSearchInput");
  if (searchInput && searchInput.value !== adminState.auditLogPage.query) {
    searchInput.value = adminState.auditLogPage.query;
  }
  const pageSize = document.getElementById("auditLogPageSize");
  if (pageSize && pageSize.value !== String(adminState.auditLogPage.pageSize)) {
    pageSize.value = String(adminState.auditLogPage.pageSize);
  }
}

function renderAuditLogPagination(errorMessage) {
  const info = document.getElementById("auditLogPageInfo");
  const prev = document.getElementById("auditLogPrevPage");
  const next = document.getElementById("auditLogNextPage");
  const numbers = document.getElementById("auditLogPageNumbers");
  if (!info || !prev || !next || !numbers) return;

  const state = adminState.auditLogPage;
  const total = Number(state.total || 0);
  const page = Math.max(1, Number(state.page || 1));
  const pageSize = Math.max(1, Number(state.pageSize || 20));
  const totalPages = Math.max(1, Number(state.totalPages || 1));

  if (errorMessage) {
    info.textContent = errorMessage;
    numbers.innerHTML = "";
    prev.disabled = true;
    next.disabled = true;
    return;
  }

  if (!total) {
    info.textContent = state.query ? "未找到匹配日志" : "共 0 条";
  } else {
    const start = (page - 1) * pageSize + 1;
    const end = Math.min(total, page * pageSize);
    info.textContent = `第 ${start}-${end} 条 / 共 ${total} 条`;
  }

  prev.disabled = page <= 1 || !total;
  next.disabled = page >= totalPages || !total;
  numbers.innerHTML = auditLogPageRange(page, totalPages).map((item) => {
    if (item === "...") {
      return '<span class="audit-page-ellipsis">...</span>';
    }
    const active = item === page ? " active" : "";
    return `<button class="page-number${active}" type="button" data-audit-page="${item}" aria-label="第 ${item} 页">${item}</button>`;
  }).join("");
  if (window.lucide) window.lucide.createIcons();
}

function auditLogPageRange(page, totalPages) {
  if (totalPages <= 7) {
    return Array.from({ length: totalPages }, (_, index) => index + 1);
  }
  if (page <= 4) {
    return [1, 2, 3, 4, 5, "...", totalPages];
  }
  if (page >= totalPages - 3) {
    return [1, "...", totalPages - 4, totalPages - 3, totalPages - 2, totalPages - 1, totalPages];
  }
  return [1, "...", page - 1, page, page + 1, "...", totalPages];
}

function handleAuditLogSearchSubmit(event) {
  event.preventDefault();
  clearTimeout(auditLogSearchTimer);
  const input = document.getElementById("auditLogSearchInput");
  loadAuditLogs({ page: 1, query: input?.value || "" });
}

function handleAuditLogSearchInput(event) {
  clearTimeout(auditLogSearchTimer);
  auditLogSearchTimer = setTimeout(() => {
    loadAuditLogs({ page: 1, query: event.target.value || "" });
  }, 350);
}

function handleClearAuditLogSearch() {
  clearTimeout(auditLogSearchTimer);
  const input = document.getElementById("auditLogSearchInput");
  if (input) input.value = "";
  loadAuditLogs({ page: 1, query: "" });
}

function handleAuditLogPageSizeChange(event) {
  loadAuditLogs({ page: 1, pageSize: event.target.value });
}

function handleAuditLogPaginationClick(event) {
  const pageButton = event.target.closest("[data-audit-page]");
  const actionButton = event.target.closest("[data-audit-page-action]");
  const state = adminState.auditLogPage;
  if (pageButton) {
    loadAuditLogs({ page: Number(pageButton.dataset.auditPage) });
    return;
  }
  if (!actionButton) return;
  if (actionButton.dataset.auditPageAction === "prev") {
    loadAuditLogs({ page: Math.max(1, Number(state.page || 1) - 1) });
  } else if (actionButton.dataset.auditPageAction === "next") {
    loadAuditLogs({ page: Math.min(Number(state.totalPages || 1), Number(state.page || 1) + 1) });
  }
}

function openCreateUserModal() {
  const form = document.getElementById("createUserForm");
  form?.reset();
  if (form?.elements.enabled) form.elements.enabled.checked = true;
  const modal = document.getElementById("createUserModal");
  if (modal) modal.hidden = false;
  form?.elements.user_id?.focus();
  if (window.lucide) window.lucide.createIcons();
}

function closeCreateUserModal() {
  const modal = document.getElementById("createUserModal");
  if (modal) modal.hidden = true;
}

function openResetPasswordModal(userId) {
  const form = document.getElementById("resetPasswordForm");
  if (!form) return;
  const user = adminState.users.find((item) => (item.user_id || item.userId) === userId);
  const label = user?.name ? `${userId} / ${user.name}` : userId;
  form.reset();
  form.elements.user_id.value = userId;
  const resetUserLabel = document.getElementById("resetUserLabel");
  if (resetUserLabel) resetUserLabel.value = label;
  const title = document.getElementById("resetPasswordTitle");
  if (title) title.textContent = `修改密码：${userId}`;
  const modal = document.getElementById("resetPasswordModal");
  if (modal) modal.hidden = false;
  form.elements.new_password.focus();
  if (window.lucide) window.lucide.createIcons();
}

function closeResetPasswordModal() {
  const modal = document.getElementById("resetPasswordModal");
  if (modal) modal.hidden = true;
}

async function handleCreateUserSubmit(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const payload = Object.fromEntries(new FormData(form).entries());
  if (payload.password !== payload.confirm_password) {
    showToast("两次输入的初始密码不一致");
    return;
  }
  const submit = form.querySelector("button[type='submit']");
  submit.disabled = true;
  try {
    await LiveMonitorApi.createUser({
      user_id: payload.user_id.trim(),
      name: payload.name.trim() || null,
      password: payload.password,
      enabled: Boolean(payload.enabled),
    });
    showToast("用户已新增");
    form.reset();
    form.elements.enabled.checked = true;
    closeCreateUserModal();
    await loadAdminUsers();
  } catch (error) {
    showToast(error.message || "新增用户失败");
  } finally {
    submit.disabled = false;
  }
}

async function handleResetPasswordSubmit(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const payload = Object.fromEntries(new FormData(form).entries());
  if (!payload.user_id) {
    showToast("请选择需要修改密码的用户");
    return;
  }
  if (payload.new_password !== payload.confirm_password) {
    showToast("两次输入的新密码不一致");
    return;
  }
  const submit = form.querySelector("button[type='submit']");
  submit.disabled = true;
  try {
    await LiveMonitorApi.resetUserPassword(payload.user_id, {
      new_password: payload.new_password,
    });
    showToast("用户密码已更新");
    form.reset();
    closeResetPasswordModal();
  } catch (error) {
    showToast(error.message || "密码更新失败");
  } finally {
    submit.disabled = false;
  }
}

async function handleToggleUserStatus(userId, enabled) {
  if (!userId) return;
  const nextEnabled = !enabled;
  const confirmed = await showConfirmDialog({
    title: nextEnabled ? "启用用户" : "禁用用户",
    message: `确定${nextEnabled ? "启用" : "禁用"}用户 ${userId} 吗？`,
    detail: nextEnabled ? "" : "禁用后该用户将无法登录系统。",
    confirmText: nextEnabled ? "启用" : "禁用",
    danger: !nextEnabled,
  });
  if (!confirmed) return;
  try {
    await LiveMonitorApi.updateUserStatus(userId, { enabled: nextEnabled });
    showToast(`用户已${nextEnabled ? "启用" : "禁用"}`);
    await loadAdminUsers();
  } catch (error) {
    showToast(error.message || "用户状态更新失败");
  }
}

async function handleDeleteUser(userId) {
  if (!userId) return;
  const confirmed = await showConfirmDialog({
    title: "删除用户",
    message: `确定删除用户 ${userId} 吗？`,
    detail: "删除后该账号将无法登录，且用户记录不可恢复。",
    confirmText: "删除",
    danger: true,
  });
  if (!confirmed) return;
  try {
    await LiveMonitorApi.deleteUser(userId);
    showToast("用户已删除");
    await loadAdminUsers();
  } catch (error) {
    showToast(error.message || "删除用户失败");
  }
}
