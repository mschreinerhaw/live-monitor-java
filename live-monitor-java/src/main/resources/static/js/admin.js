async function initAdmin() {
  document.getElementById("createUserForm")?.addEventListener("submit", handleCreateUserSubmit);
  document.getElementById("resetPasswordForm")?.addEventListener("submit", handleResetPasswordSubmit);
  document.getElementById("addUserBtn")?.addEventListener("click", openCreateUserModal);
  document.getElementById("reloadUsersBtn")?.addEventListener("click", loadAdminUsers);
  document.getElementById("reloadAuditLogsBtn")?.addEventListener("click", loadAuditLogs);
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
  if (normalized === "audit" && !adminState.auditLogs.length) {
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

async function loadAuditLogs() {
  const table = document.getElementById("auditLogTable");
  if (table) {
    table.innerHTML = '<tr><td colspan="4" class="empty">加载中...</td></tr>';
  }
  try {
    adminState.auditLogs = await LiveMonitorApi.auditLogs() || [];
    renderAuditLogs();
  } catch (error) {
    if (table) {
      table.innerHTML = `<tr><td colspan="4" class="empty">${escapeHtml(error.message || "审计日志加载失败")}</td></tr>`;
    }
  }
}

function renderAuditLogs() {
  const table = document.getElementById("auditLogTable");
  if (!table) return;
  if (!adminState.auditLogs.length) {
    table.innerHTML = '<tr><td colspan="4" class="empty">暂无审计日志</td></tr>';
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
