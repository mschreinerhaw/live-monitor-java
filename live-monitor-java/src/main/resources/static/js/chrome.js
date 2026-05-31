async function initUserChrome() {
  const topbar = document.querySelector(".topbar");
  if (!topbar || document.getElementById("notificationBell")) return;
  const userArea = document.createElement("div");
  userArea.className = "topbar-user";
  userArea.innerHTML = `
    <div class="notification-wrap">
      <button class="icon-button notification-bell" id="notificationBell" type="button" title="\u544a\u8b66\u901a\u77e5" aria-label="\u544a\u8b66\u901a\u77e5" aria-expanded="false">
        <i data-lucide="bell"></i>
        <span id="notificationBadge" class="notification-badge" hidden>0</span>
      </button>
      <div id="notificationPanel" class="notification-panel" hidden>
        <div class="notification-panel-head">
          <strong>\u544a\u8b66\u901a\u77e5</strong>
          <button class="text-button" type="button" onclick="markNotificationsSeen()">\u5168\u90e8\u5df2\u8bfb</button>
        </div>
        <div id="notificationList" class="notification-list"><p class="empty">\u6682\u65e0\u544a\u8b66</p></div>
      </div>
    </div>
    <div class="user-menu-wrap">
      <button class="current-user" type="button" id="userMenuButton" aria-haspopup="menu" aria-expanded="false">
        <span id="currentUserName">admin</span>
        <i data-lucide="chevron-down"></i>
      </button>
      <div id="userMenuPanel" class="user-menu-panel" role="menu" hidden>
        <a class="user-menu-link" href="/admin" id="adminMenuLink" role="menuitem" hidden>
          <i data-lucide="users"></i>
          <span>\u7528\u6237\u7ef4\u62a4</span>
        </a>
        <button class="user-menu-logout" type="button" id="userLogoutButton" role="menuitem">
          <i data-lucide="log-out"></i>
          <span>\u9000\u51fa\u767b\u5f55</span>
        </button>
      </div>
    </div>
  `;
  const dashboardRefresh = document.querySelector(".dashboard-refresh");
  if (document.body?.dataset?.page === "dashboard" && dashboardRefresh) {
    userArea.prepend(dashboardRefresh);
  }
  topbar.appendChild(userArea);
  document.getElementById("notificationBell")?.addEventListener("click", toggleNotificationPanel);
  document.getElementById("userMenuButton")?.addEventListener("click", toggleUserMenu);
  document.getElementById("userLogoutButton")?.addEventListener("click", logout);
  document.addEventListener("click", closeNotificationPanelOnOutsideClick);
  document.addEventListener("click", closeUserMenuOnOutsideClick);
  if (window.lucide) window.lucide.createIcons();
  try {
    const user = await LiveMonitorApi.currentUser();
    if (user?.user_id || user?.display_name || user?.name) {
      document.getElementById("currentUserName").textContent = user.user_id || user.display_name || user.name;
    }
    const adminMenuLink = document.getElementById("adminMenuLink");
    if (adminMenuLink) adminMenuLink.hidden = !user?.admin;
  } catch (error) {
    // 401 handling lives in api.js.
  }
  await loadBellAlerts(false);
  notificationState.poller = window.setInterval(() => loadBellAlerts(true), 10000);
}

async function loadBellAlerts(notifyNew) {
  try {
    const alerts = await LiveMonitorApi.allAlerts(20);
    notificationState.alerts = alerts || [];
    const latestId = Number(notificationState.alerts[0]?.id || 0);
    if (notifyNew && latestId > notificationState.seenAlertId && notificationState.seenAlertId > 0) {
      showToast("\u6536\u5230\u65b0\u544a\u8b66\u901a\u77e5");
    }
    renderNotificationBell();
  } catch (error) {
    renderNotificationBell();
  }
}

function renderNotificationBell() {
  const badge = document.getElementById("notificationBadge");
  const bell = document.getElementById("notificationBell");
  if (!badge) return;
  const unread = notificationState.alerts.filter((alert) => Number(alert.id || 0) > notificationState.seenAlertId).length;
  badge.textContent = unread > 99 ? "99+" : String(unread);
  badge.hidden = unread === 0;
  bell?.classList.toggle("has-unread", unread > 0);
  renderNotificationList();
}

function renderNotificationList() {
  const list = document.getElementById("notificationList");
  if (!list) return;
  if (!notificationState.alerts.length) {
    list.innerHTML = '<p class="empty">\u6682\u65e0\u544a\u8b66</p>';
    return;
  }
  list.innerHTML = notificationState.alerts.map((alert) => `
    <article class="notification-item ${Number(alert.id || 0) > notificationState.seenAlertId ? "unread" : ""}">
      <strong>${escapeHtml(alert.service_name || "\u544a\u8b66")}</strong>
      <p>${escapeHtml(alert.alert_content || "-")}</p>
      <small>${escapeHtml(alert.alert_type || "-")} · ${formatTime(alert.created_at)}</small>
    </article>
  `).join("");
}

function toggleNotificationPanel(event) {
  event.stopPropagation();
  const panel = document.getElementById("notificationPanel");
  const bell = document.getElementById("notificationBell");
  if (!panel || !bell) return;
  closeUserMenu();
  panel.hidden = !panel.hidden;
  bell.setAttribute("aria-expanded", String(!panel.hidden));
  if (!panel.hidden) {
    markNotificationsSeen(false);
  }
}

function toggleUserMenu(event) {
  event.stopPropagation();
  const panel = document.getElementById("userMenuPanel");
  const button = document.getElementById("userMenuButton");
  if (!panel || !button) return;
  closeNotificationPanel();
  panel.hidden = !panel.hidden;
  button.setAttribute("aria-expanded", String(!panel.hidden));
}

function closeNotificationPanelOnOutsideClick(event) {
  const wrap = document.querySelector(".notification-wrap");
  if (!wrap || wrap.contains(event.target)) return;
  closeNotificationPanel();
}

function closeNotificationPanel() {
  const panel = document.getElementById("notificationPanel");
  const bell = document.getElementById("notificationBell");
  if (!panel || panel.hidden) return;
  panel.hidden = true;
  bell?.setAttribute("aria-expanded", "false");
}

function closeUserMenuOnOutsideClick(event) {
  const wrap = document.querySelector(".user-menu-wrap");
  if (!wrap || wrap.contains(event.target)) return;
  closeUserMenu();
}

function closeUserMenu() {
  const panel = document.getElementById("userMenuPanel");
  const button = document.getElementById("userMenuButton");
  if (!panel || panel.hidden) return;
  panel.hidden = true;
  button?.setAttribute("aria-expanded", "false");
}

function markNotificationsSeen(closePanel = true) {
  const latestId = Number(notificationState.alerts[0]?.id || 0);
  notificationState.seenAlertId = Math.max(notificationState.seenAlertId, latestId);
  localStorage.setItem("liveMonitorSeenAlertId", String(notificationState.seenAlertId));
  renderNotificationBell();
  if (closePanel) {
    const panel = document.getElementById("notificationPanel");
    const bell = document.getElementById("notificationBell");
    if (panel) panel.hidden = true;
    bell?.setAttribute("aria-expanded", "false");
  }
}

async function logout() {
  try {
    await LiveMonitorApi.logout();
  } finally {
    window.location.href = "/login.html";
  }
}


