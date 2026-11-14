async function loadProcessHostOptions(form) {
  const select = form.elements.host_id;
  if (!select || !window.LiveMonitorApi?.hosts) return;
  try {
    const hosts = await LiveMonitorApi.hosts(false);
    select.innerHTML = [
      '<option value="">按地址匹配已登记主机</option>',
      ...hosts.map((host) => `
        <option value="${host.id}" data-ip="${escapeHtml(host.ip || "")}">
          ${escapeHtml(host.host_name || host.ip || "SSH 主机")} (${escapeHtml(host.ip || "-")})
        </option>
      `),
    ].join("");
    select.addEventListener("change", () => {
      const option = select.selectedOptions[0];
      if (option?.dataset.ip && form.elements.host) {
        form.elements.host.value = option.dataset.ip;
      }
    });
  } catch (error) {
    select.innerHTML = '<option value="">主机列表加载失败，可手动填写地址</option>';
  }
}
