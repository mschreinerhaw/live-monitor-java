document.addEventListener("DOMContentLoaded", () => {
  if (window.lucide) {
    window.lucide.createIcons();
  }

  initUserChrome();
  if (page === "dashboard") initDashboard();
  if (page === "add-service") initAddService();
  if (page === "service-detail") initServiceDetail();
  if (page === "alert-settings") initAlertSettings();
  if (page === "hosts") initHosts();
  if (page === "admin") initAdmin();
});

