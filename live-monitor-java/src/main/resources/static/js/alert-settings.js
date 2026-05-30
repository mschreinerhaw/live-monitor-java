async function initAlertSettings() {
  const layout = document.getElementById('alertSettingsLayout');
  const bindingBtn = document.getElementById('showBindingTestBtn');
  const configBtn = document.getElementById('showAlertConfigBtn');
  const newAlertBtn = document.getElementById('newAlertGroupBtn');
  const modal = document.getElementById("alertConfigModal");
  const bindingModal = document.getElementById("alertBindingModal");

  function switchAlertPage(mode) {
    const isConfig = mode === 'config';
    if (layout) {
      layout.classList.toggle('config-mode', isConfig);
      layout.classList.toggle('binding-mode', !isConfig);
    }
    if (bindingBtn) {
      bindingBtn.classList.toggle('active', !isConfig);
      bindingBtn.setAttribute('aria-selected', String(!isConfig));
    }
    if (configBtn) {
      configBtn.classList.toggle('active', isConfig);
      configBtn.setAttribute('aria-selected', String(isConfig));
    }
  }

  bindingBtn?.addEventListener('click', () => switchAlertPage('binding'));
  configBtn?.addEventListener('click', () => switchAlertPage('config'));
  newAlertBtn?.addEventListener('click', () => {
    switchAlertPage('config');
    openAlertConfigModal(null);
  });

  document.getElementById("reloadAlertSettingsBtn")?.addEventListener("click", loadAlertSettings);
  document.getElementById("reloadAlertBindingBtn")?.addEventListener("click", loadAlertSettings);
  document.getElementById("newServiceBindingBtn")?.addEventListener("click", () => {
    openServiceBindingModal();
  });
  document.getElementById("batchAlertActionBtn")?.addEventListener("click", () => {
    const selectedIds = Array.from(document.querySelectorAll("#alertSettingsTable input[data-alert-row-check]:checked"))
      .map((input) => Number(input.dataset.alertRowCheck))
      .filter(Boolean);
    if (!selectedIds.length) {
      showToast("请选择要批量操作的服务");
      return;
    }
    openServiceBindingModal(selectedIds);
  });
  document.getElementById("alertBindingStatusFilter")?.addEventListener("change", (event) => {
    alertSettingsState.filters.status = event.target.value || "all";
    renderAlertSettingsTable();
  });
  document.getElementById("alertServiceTypeFilter")?.addEventListener("change", (event) => {
    alertSettingsState.filters.serviceType = event.target.value || "all";
    renderAlertSettingsTable();
  });
  document.getElementById("alertServiceSearchInput")?.addEventListener("input", (event) => {
    alertSettingsState.filters.query = event.target.value.trim().toLowerCase();
    renderAlertSettingsTable();
  });
  document.getElementById("selectAllAlertServices")?.addEventListener("change", (event) => {
    document.querySelectorAll("#alertSettingsTable input[data-alert-row-check]").forEach((input) => {
      input.checked = event.target.checked;
    });
  });
  document.getElementById("deleteAlertGroupBtn")?.addEventListener("click", deleteSelectedAlertGroup);
  document.getElementById("closeAlertConfigModalBtn")?.addEventListener("click", closeAlertConfigModal);
  document.getElementById("alertGroupList")?.addEventListener("click", handleAlertGroupListClick);
  document.getElementById("alertSettingsTable")?.addEventListener("click", handleAlertSettingsTableClick);
  document.getElementById("alertSettingsTable")?.addEventListener("change", handleAlertSettingsTableChange);
  modal?.addEventListener("click", (event) => {
    if (event.target === modal) closeAlertConfigModal();
  });
  bindingModal?.addEventListener("click", (event) => {
    if (event.target === bindingModal) closeServiceBindingModal();
  });
  document.getElementById("closeAlertBindingModalBtn")?.addEventListener("click", closeServiceBindingModal);
  document.getElementById("cancelAlertBindingBtn")?.addEventListener("click", closeServiceBindingModal);
  document.getElementById("alertBindingServiceSelect")?.addEventListener("change", () => {
    syncServiceBindingGroupFromService();
    syncServiceBindingScheduleFromService();
    renderServiceBindingPreview();
  });
  document.getElementById("alertBindingGroupSelect")?.addEventListener("change", renderServiceBindingPreview);
  document.getElementById("alertBindingIntervalValue")?.addEventListener("input", renderServiceBindingPreview);
  document.getElementById("alertBindingIntervalUnit")?.addEventListener("change", renderServiceBindingPreview);
  document.getElementById("alertBindingForm")?.addEventListener("submit", submitServiceBindingForm);
  document.getElementById("alertChannelTypeSelect")?.addEventListener("change", syncChannelInputs);
  setupRecipientList({
    hiddenId: "alertMobileInput",
    inputId: "alertMobileRecipientInput",
    listId: "alertMobileRecipientList",
    addButtonId: "addMobileRecipientBtn",
    emptyText: "暂无手机号接收人",
  });
  setupRecipientList({
    hiddenId: "alertEmailInput",
    inputId: "alertEmailRecipientInput",
    listId: "alertEmailRecipientList",
    addButtonId: "addEmailRecipientBtn",
    emptyText: "暂无邮箱接收",
  });
  setupRecipientList({
    hiddenId: "alertEmailCcInput",
    inputId: "alertEmailCcRecipientInput",
    listId: "alertEmailCcRecipientList",
    addButtonId: "addEmailCcRecipientBtn",
    emptyText: "暂无抄送人",
  });
  setupRecipientList({
    hiddenId: "wecomMentionedListInput",
    inputId: "wecomMentionedRecipientInput",
    listId: "wecomMentionedList",
    addButtonId: "addWecomMentionedBtn",
    emptyText: "暂无 @ 成员",
  });
  setupRecipientList({
    hiddenId: "wecomMentionedMobileInput",
    inputId: "wecomMentionedMobileRecipientInput",
    listId: "wecomMentionedMobileList",
    addButtonId: "addWecomMentionedMobileBtn",
    emptyText: "暂无 @ 手机号",
  });
  setupRecipientList({
    hiddenId: "dingtalkAtMobileInput",
    inputId: "dingtalkAtMobileRecipientInput",
    listId: "dingtalkAtMobileList",
    addButtonId: "addDingtalkAtMobileBtn",
    emptyText: "暂无 @ 手机号",
  });

  document.getElementById("alertGroupForm")?.addEventListener("submit", async (event) => {
    event.preventDefault();
    const groupName = document.getElementById("alertGroupNameInput").value.trim();
    if (!groupName) {
      showToast("请输入配置名");
      return;
    }
    commitPendingRecipientInput({
      hiddenId: "alertMobileInput",
      inputId: "alertMobileRecipientInput",
      listId: "alertMobileRecipientList",
      emptyText: "暂无手机号接收人",
    });
    commitPendingRecipientInput({
      hiddenId: "alertEmailInput",
      inputId: "alertEmailRecipientInput",
      listId: "alertEmailRecipientList",
      emptyText: "暂无邮箱接收",
    });
    commitPendingRecipientInput({
      hiddenId: "alertEmailCcInput",
      inputId: "alertEmailCcRecipientInput",
      listId: "alertEmailCcRecipientList",
      emptyText: "暂无抄送人",
    });
    commitPendingRecipientInput({
      hiddenId: "wecomMentionedListInput",
      inputId: "wecomMentionedRecipientInput",
      listId: "wecomMentionedList",
      emptyText: "暂无 @ 成员",
    });
    commitPendingRecipientInput({
      hiddenId: "wecomMentionedMobileInput",
      inputId: "wecomMentionedMobileRecipientInput",
      listId: "wecomMentionedMobileList",
      emptyText: "暂无 @ 手机号",
    });
    commitPendingRecipientInput({
      hiddenId: "dingtalkAtMobileInput",
      inputId: "dingtalkAtMobileRecipientInput",
      listId: "dingtalkAtMobileList",
      emptyText: "暂无 @ 手机号",
    });
    const channelPayload = buildAlertChannelPayload();
    if (findAlertConfigByType(channelPayload.channel_type, alertSettingsState.selectedGroupId)) {
      showToast("相同类型的告警配置已存在，请直接修改已有记录");
      return;
    }
    if (channelPayload.channel_type === "email" && !channelPayload.alert_email) {
      showToast("请输入邮箱接收人");
      return;
    }
    if (channelPayload.channel_type === "email" && !channelPayload.smtp_host) {
      showToast("请输入发送邮件服务器域名");
      return;
    }
    if (channelPayload.channel_type === "email" && !channelPayload.smtp_port) {
      showToast("请输入发送邮件服务器端口");
      return;
    }
    if (channelPayload.channel_type === "email" && !channelPayload.smtp_from) {
      showToast("请输入发件人");
      return;
    }
    if (channelPayload.channel_type === "email" && channelPayload.smtp_auth && !channelPayload.smtp_user) {
      showToast("请输入邮件认证账户名");
      return;
    }
    if (channelPayload.channel_type === "sms" && !channelPayload.alert_mobile) {
      showToast("请输入手机号接收");
      return;
    }
    if (["wecom", "dingtalk"].includes(channelPayload.channel_type) && !channelPayload.webhook_url) {
      showToast("请输入机器人 Webhook 地址");
      return;
    }

    const groupPayload = {
      group_name: groupName,
      description: document.getElementById("alertGroupDescriptionInput").value.trim() || null,
      enabled: document.getElementById("alertGroupEnabledInput").checked,
      policy_ids: checkedIds("policyChecklist"),
      channel_ids: [],
    };
    if (!groupPayload.policy_ids.length) {
      showToast("请至少选择一个告警策");
      return;
    }

    try {
      const currentGroup = alertSettingsState.groups.find((item) => Number(item.id) === Number(alertSettingsState.selectedGroupId));
      const currentChannel = groupPrimaryChannel(currentGroup);
      const savedChannel = currentChannel
        ? await LiveMonitorApi.updateAlertChannel(currentChannel.id, channelPayload)
        : await LiveMonitorApi.createAlertChannel(channelPayload);
      groupPayload.channel_ids = [savedChannel.id];

      const saved = alertSettingsState.selectedGroupId
        ? await LiveMonitorApi.updateAlertGroup(alertSettingsState.selectedGroupId, groupPayload)
        : await LiveMonitorApi.createAlertGroup(groupPayload);
      alertSettingsState.selectedGroupId = saved.id;
      await loadAlertSettings();
      closeAlertConfigModal();
      showToast("告警配置已保");
    } catch (error) {
      showToast(error.message);
    }
  });

  await loadAlertSettings();
}

async function loadAlertSettings() {
  try {
    const [services, groups, policies, channels, alerts] = await Promise.all([
      LiveMonitorApi.services(true),
      LiveMonitorApi.alertGroups(true),
      LiveMonitorApi.alertPolicies(true),
      LiveMonitorApi.alertChannels(true),
      LiveMonitorApi.allAlerts(200).catch(() => []),
    ]);
    alertSettingsState.services = services || [];
    alertSettingsState.groups = groups || [];
    alertSettingsState.policies = policies || [];
    alertSettingsState.channels = channels || [];
    alertSettingsState.alerts = alerts || [];
    renderAlertGroups();
    renderAlertSettingsSummary();
    renderAlertServiceTypeFilter();
    if (!document.getElementById("alertConfigModal")?.hidden && alertSettingsState.selectedGroupId) {
      renderSelectedAlertGroup();
    }
    renderAlertSettingsTable();
  } catch (error) {
    const table = document.getElementById("alertSettingsTable");
    if (table) table.innerHTML = `<tr><td colspan="9" class="empty">${escapeHtml(error.message)}</td></tr>`;
    showToast(error.message);
  }
}


function renderAlertGroups() {
  const list = document.getElementById("alertGroupList");
  if (!list) return;
  if (!alertSettingsState.groups.length) {
    list.innerHTML = '<tr><td colspan="7" class="empty">暂无告警配置</td></tr>';
    return;
  }
  list.innerHTML = alertSettingsState.groups.map((group) => {
    const channel = groupPrimaryChannel(group);
    const testBusy = isAlertConfigTestBusy(group.id);
    return `
    <tr class="clickable-row" data-alert-group-id="${group.id}">
      <td>${renderChannelTypePill(groupAlertType(group))}</td>
      <td><strong>${escapeHtml(group.group_name)}</strong></td>
      <td class="wrap-cell">${escapeHtml(channelRecipientText(groupPrimaryChannel(group) || {}))}</td>
      <td class="wrap-cell">${escapeHtml(groupPolicyText(group))}</td>
      <td>${group.service_count || 0} 个服务</td>
      <td>${group.enabled ? '<span class="state-pill enabled">启用</span>' : '<span class="state-pill disabled">停用</span>'}</td>
      <td class="actions-column">
        <div class="row-actions compact">
          <button class="icon-button" type="button" title="${channel ? "测试告警配置" : "未配置通知渠道"}" ${!channel || testBusy ? "disabled" : ""} data-alert-test-config-id="${group.id}">
            <i data-lucide="${testBusy ? "loader-circle" : "send"}"></i>
          </button>
          <button class="icon-button" type="button" title="编辑" data-alert-edit-id="${group.id}"><i data-lucide="pencil"></i></button>
          <button class="icon-button" type="button" title="${group.service_count ? "有关联服务，不允许删" : "删除"}" ${group.service_count ? "disabled" : ""} data-alert-delete-id="${group.id}"><i data-lucide="trash-2"></i></button>
        </div>
      </td>
    </tr>
    ${renderAlertConfigTestResultRow(group)}
  `;
  }).join("");
  if (window.lucide) window.lucide.createIcons();
}

function selectAlertGroup(id) {
  openAlertConfigModal(id);
}

function renderAlertConfigTestResultRow(group) {
  const result = alertSettingsState.testResults[`config:${group.id}`];
  if (!result) return "";
  const stateClass = result.pending ? "testing" : (result.ok ? "ok" : "bad");
  const details = (result.details || [])
    .filter((item) => item !== null && item !== undefined && String(item).trim() !== "")
    .map((item) => `<span>${escapeHtml(item)}</span>`)
    .join("");
  return `
    <tr class="binding-result-row">
      <td colspan="7">
        <div class="test-result binding-test-result ${stateClass}">
          <strong>${escapeHtml(result.title)}</strong>
          <div>${details || "<span>-</span>"}</div>
        </div>
      </td>
    </tr>
  `;
}

async function openAlertConfigModal(id) {
  alertSettingsState.selectedGroupId = id ? Number(id) : null;
  if (alertSettingsState.selectedGroupId) {
    try {
      const detail = await LiveMonitorApi.alertGroup(alertSettingsState.selectedGroupId, true);
      alertSettingsState.groups = alertSettingsState.groups.map((group) =>
        Number(group.id) === Number(detail.id) ? detail : group
      );
    } catch (error) {
      showToast(error.message);
    }
  }
  renderSelectedAlertGroup();
  const modal = document.getElementById("alertConfigModal");
  if (modal) modal.hidden = false;
  if (window.lucide) window.lucide.createIcons();
  document.getElementById("alertGroupNameInput")?.focus();
}

function closeAlertConfigModal() {
  const modal = document.getElementById("alertConfigModal");
  if (modal) modal.hidden = true;
  alertSettingsState.selectedGroupId = null;
}

function openServiceBindingModal(serviceIds = []) {
  const ids = serviceIds.map(Number).filter(Boolean);
  alertSettingsState.bindingServiceIds = ids;
  renderServiceBindingServiceOptions(ids);
  renderServiceBindingGroupOptions(ids);
  renderServiceBindingScheduleOptions(ids);
  if (!ids.length) syncServiceBindingGroupFromService();
  renderServiceBindingPreview();

  const title = document.getElementById("alertBindingFormTitle");
  if (title) {
    title.textContent = ids.length > 1 ? "批量服务绑定" : (ids.length === 1 ? "编辑服务绑定" : "新增服务绑定");
  }

  const modal = document.getElementById("alertBindingModal");
  if (modal) modal.hidden = false;
  if (window.lucide) window.lucide.createIcons();
  document.getElementById("alertBindingServiceSelect")?.focus();
}

function closeServiceBindingModal() {
  const modal = document.getElementById("alertBindingModal");
  if (modal) modal.hidden = true;
  alertSettingsState.bindingServiceIds = [];
}

function renderServiceBindingServiceOptions(selectedIds = []) {
  const select = document.getElementById("alertBindingServiceSelect");
  const summary = document.getElementById("alertBindingServiceSummary");
  const serviceField = document.getElementById("alertBindingServiceField");
  if (!select) return;

  if (selectedIds.length > 1) {
    select.innerHTML = "";
    select.disabled = true;
    if (serviceField) serviceField.hidden = true;
    if (summary) {
      summary.hidden = false;
      summary.textContent = `已选择 ${selectedIds.length} 个服务`;
    }
    return;
  }

  select.disabled = false;
  if (serviceField) serviceField.hidden = false;
  if (summary) summary.hidden = true;

  const preferredId = selectedIds[0]
    || alertSettingsState.services.find((service) => !service.alert_group_id)?.id
    || alertSettingsState.services[0]?.id
    || "";
  select.innerHTML = [
    '<option value="">请选择服务</option>',
    ...alertSettingsState.services.map((service) => `
      <option value="${service.id}" ${Number(preferredId) === Number(service.id) ? "selected" : ""}>
        ${escapeHtml(service.service_name)}${service.alert_group_id ? " / 已绑" : ""}
      </option>
    `),
  ].join("");
}

function renderServiceBindingGroupOptions(selectedIds = []) {
  const select = document.getElementById("alertBindingGroupSelect");
  if (!select) return;
  const selectedServices = selectedIds.length
    ? selectedIds.map((id) => alertSettingsState.services.find((service) => Number(service.id) === Number(id))).filter(Boolean)
    : [];
  const commonGroupId = selectedServices.length
    && selectedServices.every((service) => Number(service.alert_group_id || 0) === Number(selectedServices[0].alert_group_id || 0))
      ? selectedServices[0].alert_group_id || ""
      : "";
  select.innerHTML = renderAlertGroupSelectOptions(commonGroupId);
}

function selectedBindingServices() {
  if (alertSettingsState.bindingServiceIds.length) {
    return alertSettingsState.bindingServiceIds
      .map((id) => alertSettingsState.services.find((service) => Number(service.id) === Number(id)))
      .filter(Boolean);
  }
  const select = document.getElementById("alertBindingServiceSelect");
  const id = Number(select?.value || 0);
  return id ? alertSettingsState.services.filter((service) => Number(service.id) === id) : [];
}

function syncServiceBindingGroupFromService() {
  if (alertSettingsState.bindingServiceIds.length) return;
  const groupSelect = document.getElementById("alertBindingGroupSelect");
  const service = selectedBindingServices()[0];
  if (groupSelect && service) groupSelect.value = service.alert_group_id || "";
}

function syncServiceBindingScheduleFromService() {
  if (alertSettingsState.bindingServiceIds.length) return;
  const service = selectedBindingServices()[0];
  if (service) setBindingSchedule(service.check_interval);
}

function renderServiceBindingScheduleOptions(selectedIds = []) {
  const selectedServices = selectedIds.length
    ? selectedIds.map((id) => alertSettingsState.services.find((service) => Number(service.id) === Number(id))).filter(Boolean)
    : selectedBindingServices();
  const commonSeconds = selectedServices.length
    && selectedServices.every((service) => Number(service.check_interval || 0) === Number(selectedServices[0].check_interval || 0))
      ? selectedServices[0].check_interval
      : (selectedServices[0]?.check_interval || 60);
  setBindingSchedule(commonSeconds);
}

function setBindingSchedule(seconds) {
  const valueInput = document.getElementById("alertBindingIntervalValue");
  const unitSelect = document.getElementById("alertBindingIntervalUnit");
  const parts = secondsToIntervalParts(seconds);
  if (valueInput) valueInput.value = parts.value;
  if (unitSelect) unitSelect.value = parts.unit;
}

function renderServiceBindingPreview() {
  const preview = document.getElementById("alertBindingPreview");
  if (!preview) return;
  const services = selectedBindingServices();
  const groupId = document.getElementById("alertBindingGroupSelect")?.value || "";
  const group = groupId ? alertSettingsState.groups.find((item) => Number(item.id) === Number(groupId)) : null;
  const intervalValue = document.getElementById("alertBindingIntervalValue")?.value || 1;
  const intervalUnit = document.getElementById("alertBindingIntervalUnit")?.value || "minutes";
  const intervalText = formatCheckInterval(intervalToSeconds(intervalValue, intervalUnit));
  const serviceText = services.length === 1
    ? services[0].service_name
    : (services.length ? `${services.length} 个服务` : "未选择服务");
  preview.innerHTML = `
    <span class="recipient-icon"><i data-lucide="${group ? channelIcon(groupAlertType(group)) : "bell-off"}"></i></span>
    <span>
      <strong>${escapeHtml(serviceText)}</strong>
      <small>${group ? `${escapeHtml(group.group_name)} / ${escapeHtml(groupPolicyText(group))}` : "保存后将取消告警绑定"} / ${escapeHtml(intervalText)}</small>
    </span>
  `;
  if (window.lucide) window.lucide.createIcons();
}

async function submitServiceBindingForm(event) {
  event.preventDefault();
  const services = selectedBindingServices();
  if (!services.length) {
    showToast("请选择服务");
    return;
  }
  const groupValue = document.getElementById("alertBindingGroupSelect")?.value || "";
  const intervalValue = Number(document.getElementById("alertBindingIntervalValue")?.value || 1);
  const intervalUnit = document.getElementById("alertBindingIntervalUnit")?.value || "minutes";
  try {
    const updatedServices = await Promise.all(services.map((service) =>
      LiveMonitorApi.updateAlertSettings(service.id, {
        alert_group_id: groupValue ? Number(groupValue) : null,
        check_interval_value: intervalValue,
        check_interval_unit: intervalUnit,
        check_interval: intervalToSeconds(intervalValue, intervalUnit),
      })
    ));
    alertSettingsState.services = alertSettingsState.services.map((service) =>
      updatedServices.find((item) => Number(item.id) === Number(service.id)) || service
    );
    await loadAlertSettings();
    closeServiceBindingModal();
    showToast(groupValue ? "服务告警绑定已保" : "服务告警绑定已取");
  } catch (error) {
    showToast(error.message);
  }
}

function renderSelectedAlertGroup() {
  const group = alertSettingsState.groups.find((item) => Number(item.id) === Number(alertSettingsState.selectedGroupId));
  const channel = groupPrimaryChannel(group);
  const groupNameInput = document.getElementById("alertGroupNameInput");
  const groupDescInput = document.getElementById("alertGroupDescriptionInput");
  const groupEnabledInput = document.getElementById("alertGroupEnabledInput");
  const title = document.getElementById("alertConfigFormTitle");
  if (title) title.textContent = group ? "编辑告警配置" : "新增告警配置";
  if (groupNameInput) groupNameInput.value = group?.group_name || "";
  if (groupDescInput) groupDescInput.value = group?.description || "";
  if (groupEnabledInput) groupEnabledInput.checked = group ? Boolean(group.enabled) : true;
  renderPolicyChecklist(group ? group.policy_ids || [] : alertSettingsState.policies.map((policy) => policy.id));
  fillAlertChannelForm(channel);
  updateAlertTypeOptions(channel?.channel_type || "");
  const deleteButton = document.getElementById("deleteAlertGroupBtn");
  if (deleteButton) {
    deleteButton.disabled = !group || Number(group.service_count || 0) > 0;
    deleteButton.title = group?.service_count ? "有关联服务，不允许删" : "删除";
  }
}

function groupPrimaryChannel(group) {
  if (!group) return null;
  if (Array.isArray(group.channels) && group.channels.length) return group.channels[0];
  const ids = new Set((group.channel_ids || []).map(Number));
  return alertSettingsState.channels.find((channel) => ids.has(Number(channel.id))) || null;
}

function groupAlertType(group) {
  return groupPrimaryChannel(group)?.channel_type || "";
}

function findAlertConfigByType(type, exceptGroupId) {
  return alertSettingsState.groups.find((group) =>
    groupAlertType(group) === type && Number(group.id) !== Number(exceptGroupId || 0)
  );
}

function updateAlertTypeOptions(currentType) {
  const select = document.getElementById("alertChannelTypeSelect");
  if (!select) return;
  const usedTypes = new Set(
    alertSettingsState.groups
      .filter((group) => Number(group.id) !== Number(alertSettingsState.selectedGroupId || 0))
      .map(groupAlertType)
      .filter(Boolean)
  );
  Array.from(select.options).forEach((option) => {
    option.disabled = usedTypes.has(option.value) && option.value !== currentType;
  });
  if (select.selectedOptions[0]?.disabled) {
    const nextOption = Array.from(select.options).find((option) => !option.disabled);
    if (nextOption) select.value = nextOption.value;
  }
  syncChannelInputs();
}

function renderChannelTypePill(type) {
  const icon = channelIcon(type);
  return `
    <span class="type-pill">
      <i data-lucide="${icon}"></i>
      ${channelTypeLabel(type)}
    </span>
  `;
}

function groupPolicyText(group) {
  const policies = Array.isArray(group?.policies) ? group.policies : [];
  if (policies.length) return policies.map(policyDisplayName).join("");
  const ids = new Set((group?.policy_ids || []).map(Number));
  const names = alertSettingsState.policies
    .filter((policy) => ids.has(Number(policy.id)))
    .map(policyDisplayName);
  return names.length ? names.join("") : "未选择策略";
}

function renderPolicyChecklist(selectedIds) {
  const container = document.getElementById("policyChecklist");
  if (!container) return;
  if (!alertSettingsState.policies.length) {
    container.innerHTML = '<p class="empty">暂无策略</p>';
    return;
  }
  const selected = new Set(selectedIds.map(Number));
  container.innerHTML = alertSettingsState.policies.map((policy) => `
    <label class="choice-card">
      <input type="checkbox" value="${policy.id}" ${selected.has(Number(policy.id)) ? "checked" : ""}>
      <span>
        <strong>${escapeHtml(policyDisplayName(policy))}</strong>
        <small>${escapeHtml(policyText(policy))}</small>
      </span>
    </label>
  `).join("");
}

function renderChannelChecklist(selectedIds) {
  const container = document.getElementById("channelChecklist");
  if (!container) return;
  if (!alertSettingsState.channels.length) {
    container.innerHTML = '<p class="empty">暂无渠道，请先在渠道库保存一个渠道</p>';
    return;
  }
  const selected = new Set(selectedIds.map(Number));
  container.innerHTML = alertSettingsState.channels.map((channel) => `
    <label class="choice-card">
      <input type="checkbox" value="${channel.id}" ${selected.has(Number(channel.id)) ? "checked" : ""} onchange="renderRecipientSummary(checkedIds('channelChecklist'))">
      <span>
        <strong>${escapeHtml(channel.channel_name)}</strong>
        <small>${channelTypeLabel(channel.channel_type)} · ${channel.enabled ? "启用" : "停用"} · ${escapeHtml(channelRecipientText(channel))}</small>
      </span>
    </label>
  `).join("");
}

function renderRecipientSummary(selectedIds) {
  const container = document.getElementById("recipientSummary");
  if (!container) return;
  const selected = new Set((selectedIds || []).map(Number));
  const channels = alertSettingsState.channels.filter((channel) => selected.has(Number(channel.id)));
  if (!channels.length) {
    container.innerHTML = '<p class="empty">选择通知渠道后显示接收人</p>';
    return;
  }
  container.innerHTML = channels.map((channel) => `
    <article class="recipient-item">
      <span class="recipient-icon"><i data-lucide="${channelIcon(channel.channel_type)}"></i></span>
      <span>
        <strong>${escapeHtml(channel.channel_name)}</strong>
        <small>${escapeHtml(channelRecipientText(channel))}</small>
      </span>
    </article>
  `).join("");
  if (window.lucide) window.lucide.createIcons();
}

function checkedIds(containerId) {
  return Array.from(document.querySelectorAll(`#${containerId} input[type="checkbox"]:checked`))
    .map((input) => Number(input.value))
    .filter(Boolean);
}

function policyText(policy) {
  if (policy.trigger_type === "consecutive_down") return `连续失败 ${policy.trigger_value || 3} 次触发`;
  if (policy.trigger_type === "latency_gt_ms") return `响应时间超过 ${policy.trigger_value || 3000} ms 触发`;
  if (policy.trigger_type === "recovered") return "服务 DOWN 恢复 UP 时触发";
  return `${policy.trigger_type}: ${policy.trigger_value || "-"}`;
}

function policyDisplayName(policy) {
  if (policy.trigger_type === "consecutive_down") return `DOWN 连续 ${policy.trigger_value || 3} 次`;
  if (policy.trigger_type === "latency_gt_ms") return `响应时间 > ${Math.round(Number(policy.trigger_value || 3000) / 1000)} 秒`;
  if (policy.trigger_type === "recovered") return "服务恢复";
  return policy.policy_name || policy.trigger_type || "告警策略";
}

function channelTypeLabel(type) {
  return { email: "邮件", sms: "短信", webhook: "Webhook", wecom: "企业微信", dingtalk: "钉钉" }[type] || type || "渠道";
}

function channelIcon(type) {
  return { email: "mail", sms: "message-square", webhook: "webhook", wecom: "messages-square", dingtalk: "bot" }[type] || "send";
}

function channelRecipientText(channel) {
  if (channel.channel_type === "email") {
    const recipients = formatRecipientsForText(channel.alert_email || "") || "未填写邮";
    const cc = formatRecipientsForText(channel.alert_cc || "");
    return cc ? `${recipients}；抄送：${cc}` : recipients;
  }
  if (channel.channel_type === "sms") return formatMobilesForTextarea(channel.alert_mobile || "").replaceAll("\n", "") || "未填写手机号";
  return channel.webhook_url || channel.sms_api_url || `未填写${channelTypeLabel(channel.channel_type)} Webhook`;
}

async function deleteSelectedAlertGroup() {
  const groupId = alertSettingsState.selectedGroupId;
  if (!groupId) return;
  const group = alertSettingsState.groups.find((item) => Number(item.id) === Number(groupId));
  if (Number(group?.service_count || 0) > 0) {
    showToast("有关联服务的告警配置不允许删除，仅能修改");
    return;
  }
  if (!window.confirm("确定删除该告警配置？")) return;
  try {
    await LiveMonitorApi.deleteAlertGroup(groupId);
    alertSettingsState.selectedGroupId = null;
    await loadAlertSettings();
    closeAlertConfigModal();
    showToast("告警配置已删");
  } catch (error) {
    showToast(error.message);
  }
}

function deleteAlertConfig(id) {
  alertSettingsState.selectedGroupId = Number(id);
  deleteSelectedAlertGroup();
}

function formatMobilesForTextarea(value) {
  return String(value || "")
    .split(/[,;\s\uFF0C\uFF1B]+/)
    .map((item) => item.trim())
    .filter(Boolean)
    .join("\n");
}

function parseRecipients(value) {
  return String(value || "")
    .split(/[,;\s\uFF0C\uFF1B]+/)
    .map((item) => item.trim())
    .filter(Boolean);
}

function uniqueRecipients(values) {
  const seen = new Set();
  return values.filter((value) => {
    const key = value.toLowerCase();
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function formatRecipientsForText(value) {
  return parseRecipients(value).join("");
}

function setupRecipientList(options) {
  const input = document.getElementById(options.inputId);
  const addButton = document.getElementById(options.addButtonId);
  const list = document.getElementById(options.listId);
  if (!input || !addButton || !list || input.dataset.enhanced === "true") return;
  input.dataset.enhanced = "true";

  addButton.addEventListener("click", () => addRecipientToList(options));
  input.addEventListener("keydown", (event) => {
    if (event.key !== "Enter") return;
    event.preventDefault();
    addRecipientToList(options);
  });
  list.addEventListener("click", (event) => {
    const button = event.target.closest("[data-recipient]");
    if (!button) return;
    removeRecipientFromList(options, button.dataset.recipient);
  });
  renderRecipientList(options);
}

function setRecipientListValue(hiddenId, listId, inputId, value) {
  const hidden = document.getElementById(hiddenId);
  if (hidden) hidden.value = uniqueRecipients(parseRecipients(value)).join(",");
  renderRecipientList({ hiddenId, listId, inputId });
}

function addRecipientToList(options) {
  const hidden = document.getElementById(options.hiddenId);
  const input = document.getElementById(options.inputId);
  if (!hidden || !input) return;
  const nextValues = parseRecipients(input.value);
  if (!nextValues.length) {
    showToast("请输入接收人");
    return;
  }
  hidden.value = uniqueRecipients([...parseRecipients(hidden.value), ...nextValues]).join(",");
  input.value = "";
  renderRecipientList(options);
}

function commitPendingRecipientInput(options) {
  const input = document.getElementById(options.inputId);
  if (!input || !input.value.trim()) return;
  addRecipientToList(options);
}

function removeRecipientFromList(options, value) {
  const hidden = document.getElementById(options.hiddenId);
  if (!hidden) return;
  hidden.value = parseRecipients(hidden.value)
    .filter((item) => item !== value)
    .join(",");
  renderRecipientList(options);
}

function renderRecipientList(options) {
  const hidden = document.getElementById(options.hiddenId);
  const list = document.getElementById(options.listId);
  if (!hidden || !list) return;
  const recipients = parseRecipients(hidden.value);
  if (!recipients.length) {
    list.innerHTML = `<p class="recipient-empty">${options.emptyText || "暂无接收"}</p>`;
    return;
  }
  list.innerHTML = recipients.map((recipient) => `
    <span class="recipient-chip">
      <span>${escapeHtml(recipient)}</span>
      <button class="icon-button recipient-remove-button" type="button" title="移除" data-recipient="${escapeHtml(recipient)}">
        <i data-lucide="x"></i>
      </button>
    </span>
  `).join("");
  if (window.lucide) window.lucide.createIcons();
}

function renderAlertChannelOptions() {
  const select = document.getElementById("alertChannelConfigSelect");
  if (!select) return;
  if (!alertSettingsState.channels.length) {
    select.innerHTML = '<option value="">暂无渠道，保存后创建</option>';
    return;
  }
  select.innerHTML = alertSettingsState.channels.map((channel) => `
    <option value="${channel.id}">${escapeHtml(channel.channel_name)} (${channelTypeLabel(channel.channel_type)})</option>
  `).join("");
  select.value = String(alertSettingsState.selectedChannelId || alertSettingsState.channels[0].id);
}

function renderSelectedAlertChannel() {
  const channel = alertSettingsState.channels.find((item) => item.id === alertSettingsState.selectedChannelId);
  fillAlertChannelForm(channel);
}

function fillAlertChannelForm(channel) {
  const setValueIfExists = (id, value) => {
    const el = document.getElementById(id);
    if (el) el.value = value;
  };
  const setCheckedIfExists = (id, checked) => {
    const el = document.getElementById(id);
    if (el) el.checked = checked;
  };

  setValueIfExists("alertChannelNameInput", channel?.channel_name || "");
  setValueIfExists("alertChannelTypeSelect", channel?.channel_type || "sms");
  setCheckedIfExists("alertChannelEnabledInput", channel ? Boolean(channel.enabled) : true);
  setRecipientListValue("alertEmailInput", "alertEmailRecipientList", "alertEmailRecipientInput", channel?.alert_email || "");
  setRecipientListValue("alertEmailCcInput", "alertEmailCcRecipientList", "alertEmailCcRecipientInput", channel?.alert_cc || "");
  setRecipientListValue("alertMobileInput", "alertMobileRecipientList", "alertMobileRecipientInput", channel?.alert_mobile || "");
  setValueIfExists("smtpHostInput", channel?.smtp_host || "");
  setValueIfExists("smtpPortInput", channel?.smtp_port || "");
  setValueIfExists("smtpUserInput", channel?.smtp_user || "");
  setValueIfExists("smtpPasswordInput", "");
  setValueIfExists("smtpFromInput", channel?.smtp_from || "");
  setCheckedIfExists("smtpAuthInput", channel ? Boolean(channel.smtp_auth ?? channel.smtp_user) : true);
  setCheckedIfExists("smtpTlsInput", Boolean(channel?.smtp_use_tls));
  setCheckedIfExists("smtpSslInput", Boolean(channel?.smtp_use_ssl) || Number(channel?.smtp_port || 0) === 465);
  setValueIfExists("smtpSslTrustInput", channel?.smtp_ssl_trust || "");
  setValueIfExists("smsApiUrlInput", channel?.webhook_url || channel?.sms_api_url || "");
  setValueIfExists("smsApiTokenInput", "");
  setValueIfExists("smsUsernameInput", channel?.sms_username || "");
  setValueIfExists("smsPasswordInput", "");
  setCheckedIfExists("smsPasswordIsMd5Input", channel ? Boolean(channel.sms_password_is_md5) : true);
  setValueIfExists("smsPasswordMd5Input", "");
  setValueIfExists("smsRstypeInput", channel?.sms_rstype || "text");
  setValueIfExists("smsExtCodeInput", channel?.sms_ext_code || "");
  setValueIfExists("wecomWebhookUrlInput", channel?.webhook_url || "");
  setRecipientListValue("wecomMentionedListInput", "wecomMentionedList", "wecomMentionedRecipientInput", channel?.wecom_mentioned_list || "");
  setRecipientListValue("wecomMentionedMobileInput", "wecomMentionedMobileList", "wecomMentionedMobileRecipientInput", channel?.wecom_mentioned_mobiles || "");
  setCheckedIfExists("wecomAtAllInput", Boolean(channel?.wecom_at_all));
  setValueIfExists("dingtalkWebhookUrlInput", channel?.webhook_url || "");
  setValueIfExists("dingtalkSecretInput", "");
  setRecipientListValue("dingtalkAtMobileInput", "dingtalkAtMobileList", "dingtalkAtMobileRecipientInput", channel?.dingtalk_at_mobiles || "");
  setCheckedIfExists("dingtalkAtAllInput", Boolean(channel?.dingtalk_at_all));
  syncChannelInputs();
}

function syncChannelInputs() {
  const typeSelect = document.getElementById("alertChannelTypeSelect");
  if (!typeSelect) return;
  const channelType = typeSelect.value || "email";
  document.querySelectorAll(".channel-field").forEach((field) => {
    field.hidden = true;
  });
  document.querySelectorAll(`.${channelType}-channel-field`).forEach((field) => {
    field.hidden = false;
  });
}

function buildAlertChannelPayload() {
  const getValueIfExists = (id) => {
    const el = document.getElementById(id);
    return el ? el.value.trim() : "";
  };
  const getCheckedIfExists = (id) => {
    const el = document.getElementById(id);
    return el ? el.checked : false;
  };

  const channelTypeSelect = document.getElementById("alertChannelTypeSelect");
  if (!channelTypeSelect) return {};

  const channelType = channelTypeSelect.value;
  const smtpPort = getValueIfExists("smtpPortInput");
  const apiUrl = getWebhookUrlForType(channelType, getValueIfExists);
  const groupName = getValueIfExists("alertGroupNameInput");
  return {
    channel_name: groupName ? `${groupName}通知` : `${channelTypeLabel(channelType)}通知`,
    channel_type: channelType,
    alert_email: channelType === "email" ? getValueIfExists("alertEmailInput") || null : null,
    alert_cc: channelType === "email" ? getValueIfExists("alertEmailCcInput") || null : null,
    alert_mobile: channelType === "sms" ? getValueIfExists("alertMobileInput") || null : null,
    smtp_host: channelType === "email" ? getValueIfExists("smtpHostInput") || null : null,
    smtp_port: channelType === "email" && smtpPort ? Number(smtpPort) : null,
    smtp_user: channelType === "email" ? getValueIfExists("smtpUserInput") || null : null,
    smtp_password: channelType === "email" ? getValueIfExists("smtpPasswordInput") || null : null,
    smtp_from: channelType === "email" ? getValueIfExists("smtpFromInput") || null : null,
    smtp_auth: channelType === "email" ? getCheckedIfExists("smtpAuthInput") : false,
    smtp_use_tls: channelType === "email" ? getCheckedIfExists("smtpTlsInput") : false,
    smtp_use_ssl: channelType === "email" ? getCheckedIfExists("smtpSslInput") : false,
    smtp_ssl_trust: channelType === "email" ? getValueIfExists("smtpSslTrustInput") || null : null,
    sms_api_url: channelType === "sms" ? apiUrl || null : null,
    sms_api_token: channelType === "sms" ? getValueIfExists("smsApiTokenInput") || null : null,
    sms_username: channelType === "sms" ? getValueIfExists("smsUsernameInput") || null : null,
    sms_password: channelType === "sms" ? getValueIfExists("smsPasswordInput") || null : null,
    sms_password_is_md5: channelType === "sms" ? getCheckedIfExists("smsPasswordIsMd5Input") : true,
    sms_password_md5: channelType === "sms" ? getValueIfExists("smsPasswordMd5Input") || null : null,
    sms_rstype: channelType === "sms" ? getValueIfExists("smsRstypeInput") || "text" : "text",
    sms_ext_code: channelType === "sms" ? getValueIfExists("smsExtCodeInput") || null : null,
    webhook_url: ["webhook", "wecom", "dingtalk"].includes(channelType) ? apiUrl || null : null,
    dingtalk_secret: channelType === "dingtalk" ? getValueIfExists("dingtalkSecretInput") || null : null,
    dingtalk_at_mobiles: channelType === "dingtalk" ? getValueIfExists("dingtalkAtMobileInput") || null : null,
    dingtalk_at_all: channelType === "dingtalk" ? getCheckedIfExists("dingtalkAtAllInput") : false,
    wecom_mentioned_list: channelType === "wecom" ? getValueIfExists("wecomMentionedListInput") || null : null,
    wecom_mentioned_mobiles: channelType === "wecom" ? getValueIfExists("wecomMentionedMobileInput") || null : null,
    wecom_at_all: channelType === "wecom" ? getCheckedIfExists("wecomAtAllInput") : false,
    enabled: getCheckedIfExists("alertGroupEnabledInput"),
  };
}

function getWebhookUrlForType(channelType, getValue) {
  if (channelType === "wecom") return getValue("wecomWebhookUrlInput");
  if (channelType === "dingtalk") return getValue("dingtalkWebhookUrlInput");
  return getValue("smsApiUrlInput");
}

function renderAlertSettingsTable() {
  const tbody = document.getElementById("alertSettingsTable");
  if (!tbody) return;
  if (!alertSettingsState.services.length) {
    tbody.innerHTML = '<tr><td colspan="3" class="empty">暂无服务</td></tr>';
    return;
  }
  tbody.innerHTML = alertSettingsState.services.map((service) => {
    const checkBusy = isAlertActionBusy(service.id, "check");
    const testBusy = isAlertActionBusy(service.id, "test");
    return `
    <tr>
      <td>${escapeHtml(service.service_name)}</td>
      <td>
        <select class="config-select" data-service-alert-group-id="${service.id}">
          ${renderAlertGroupSelectOptions(service.alert_group_id)}
        </select>
      </td>
      <td class="actions-column">
        <div class="row-actions compact">
          <button class="icon-button" type="button" title="${checkBusy ? "服务探测" : "服务探测"}" aria-label="服务探测" data-alert-action="check" data-service-id="${service.id}" ${checkBusy ? "disabled" : ""}><i data-lucide="${checkBusy ? "loader-circle" : "zap"}"></i></button>
          <button class="icon-button" type="button" title="${testBusy ? "告警测试" : "告警测试"}" aria-label="告警测试" data-alert-action="test" data-service-id="${service.id}" ${testBusy ? "disabled" : ""}><i data-lucide="${testBusy ? "loader-circle" : "bell"}"></i></button>
        </div>
      </td>
    </tr>
    ${renderAlertActionResultRow(service)}
  `;
  }).join("");
  if (window.lucide) window.lucide.createIcons();
}

function renderAlertActionResultRow(service) {
  const result = alertSettingsState.testResults[String(service.id)];
  if (!result) return "";
  const stateClass = result.pending ? "testing" : (result.ok ? "ok" : "bad");
  const details = (result.details || [])
    .filter((item) => item !== null && item !== undefined && String(item).trim() !== "")
    .map((item) => `<span>${escapeHtml(item)}</span>`)
    .join("");
  return `
    <tr class="binding-result-row">
      <td colspan="9">
        <div class="test-result binding-test-result ${stateClass}">
          <strong>${escapeHtml(result.title)}</strong>
          <div>${details || "<span>-</span>"}</div>
        </div>
      </td>
    </tr>
  `;
}

function renderAlertSettingsSummary() {
  setText("alertMetricServiceCount", alertSettingsState.services.length);
  setText("alertMetricEnabledCount", alertSettingsState.groups.filter((group) => group.enabled).length);

  const todayKey = dayKey(new Date());
  const yesterday = new Date();
  yesterday.setDate(yesterday.getDate() - 1);
  const yesterdayKey = dayKey(yesterday);
  const todayCount = alertSettingsState.alerts.filter((alert) => dayKey(parseApiDate(alert.created_at || alert.createdAt)) === todayKey).length;
  const yesterdayCount = alertSettingsState.alerts.filter((alert) => dayKey(parseApiDate(alert.created_at || alert.createdAt)) === yesterdayKey).length;
  setText("alertMetricTodayCount", todayCount);

  const trend = document.getElementById("alertMetricTodayTrend");
  if (trend) {
    const delta = todayCount - yesterdayCount;
    trend.textContent = `较昨"${delta === 0 ? "0" : `${delta > 0 ? "+" : ""}${delta}`}`;
    trend.classList.toggle("positive", delta < 0);
    trend.classList.toggle("negative", delta > 0);
  }

  const delivered = alertSettingsState.alerts.filter((alert) => alertDeliveryState(alert).className === "sent").length;
  const rate = alertSettingsState.alerts.length ? Math.round((delivered / alertSettingsState.alerts.length) * 1000) / 10 : 0;
  setText("alertMetricNotifyRate", `${rate}%`);
}

function renderAlertServiceTypeFilter() {
  const select = document.getElementById("alertServiceTypeFilter");
  if (!select) return;
  const current = alertSettingsState.filters.serviceType || "all";
  const types = Array.from(new Set(alertSettingsState.services.map((service) => serviceTypeGroup(service.service_type))));
  const labels = {
    interface: "接口服务",
    database: "数据",
    middleware: "中间",
    host: "主机服务",
    other: "其他服务",
  };
  select.innerHTML = [
    '<option value="all">全部服务类型</option>',
    ...types.map((type) => `<option value="${type}">${labels[type] || "其他服务"}</option>`),
  ].join("");
  select.value = types.includes(current) ? current : "all";
  alertSettingsState.filters.serviceType = select.value;
}

function renderAlertSettingsTable() {
  const tbody = document.getElementById("alertSettingsTable");
  if (!tbody) return;
  const filteredServices = filteredAlertServices();
  const selectAll = document.getElementById("selectAllAlertServices");
  if (selectAll) selectAll.checked = false;
  setText("alertBindingTotalText", `"${filteredServices.length} 条`);

  if (!alertSettingsState.services.length) {
    tbody.innerHTML = '<tr><td colspan="9" class="empty">暂无服务</td></tr>';
    return;
  }
  if (!filteredServices.length) {
    tbody.innerHTML = '<tr><td colspan="9" class="empty">暂无匹配服务</td></tr>';
    return;
  }

  tbody.innerHTML = filteredServices.map((service) => {
    const testBusy = isAlertActionBusy(service.id, "test");
    const group = alertGroupForService(service);
    const lastAlert = lastServiceAlert(service);
    const notifyState = alertDeliveryState(lastAlert);
    const bindingStatus = alertBindingStatus(service, group);
    return `
    <tr>
      <td class="select-column"><input type="checkbox" data-alert-row-check="${service.id}" aria-label="选择 ${escapeHtml(service.service_name)}"></td>
      <td>
        <span class="alert-service-cell">
          <span class="alert-service-icon type-${escapeHtml(service.service_type || "custom")}"><i data-lucide="${serviceTypeIcon(service.service_type)}"></i></span>
          <span class="alert-service-name" title="${escapeHtml(service.service_name)}">${escapeHtml(service.service_name)}</span>
        </span>
      </td>
      <td>${renderAlertServiceTypeTag(service.service_type)}</td>
      <td><span class="state-pill ${bindingStatus.className}">${bindingStatus.text}</span></td>
      <td>
        <div class="alert-binding-rule-cell">
          <select class="alert-binding-select ${service.alert_group_id ? "bound" : ""}" data-service-alert-group-id="${service.id}" aria-label="设置 ${escapeHtml(service.service_name)} 的告警绑">
            ${renderAlertGroupSelectOptions(service.alert_group_id)}
          </select>
          <small>${alertRuleText(group)}</small>
        </div>
      </td>
      <td>${formatCheckInterval(service.check_interval)}</td>
      <td>${lastAlert ? formatTime(lastAlert.created_at || lastAlert.createdAt) : "-"}</td>
      <td><span class="alert-notify-state ${notifyState.className}">${notifyState.text}</span></td>
      <td class="actions-column">
        <div class="row-actions compact">
          <button class="icon-button alert-row-button" type="button" title="${testBusy ? "告警测试" : "测试"}" data-alert-action="test" data-service-id="${service.id}" ${testBusy ? "disabled" : ""}>
            <i data-lucide="${testBusy ? "loader-circle" : "zap"}"></i>
            <span>测试</span>
          </button>
          <button class="icon-button alert-row-button" type="button" title="查看告警记录" data-alert-records="${service.id}">
            <i data-lucide="bell"></i>
            <span>告警记录</span>
          </button>
          <button class="icon-button alert-row-menu" type="button" title="编辑绑定" data-alert-bind="${service.id}">
            <i data-lucide="link"></i>
          </button>
        </div>
      </td>
    </tr>
    ${renderAlertActionResultRow(service)}
  `;
  }).join("");
  if (window.lucide) window.lucide.createIcons();
}

function filteredAlertServices() {
  return alertSettingsState.services.filter((service) => {
    const group = alertGroupForService(service);
    const status = alertSettingsState.filters.status || "all";
    const type = alertSettingsState.filters.serviceType || "all";
    const query = alertSettingsState.filters.query || "";
    if (status === "bound" && !service.alert_group_id) return false;
    if (status === "unbound" && service.alert_group_id) return false;
    if (status === "enabled" && !group?.enabled) return false;
    if (status === "disabled" && (!group || group.enabled)) return false;
    if (type !== "all" && serviceTypeGroup(service.service_type) !== type) return false;
    if (query) {
      const haystack = [
        service.service_name,
        service.service_type,
        service.cluster_name,
        service.host,
        service.url,
        service.endpoint,
      ].filter(Boolean).join(" ").toLowerCase();
      if (!haystack.includes(query)) return false;
    }
    return true;
  });
}

function alertGroupForService(service) {
  return alertSettingsState.groups.find((group) => Number(group.id) === Number(service.alert_group_id)) || null;
}

function alertBindingStatus(service, group) {
  if (!service.alert_group_id) return { text: "未绑", className: "disabled" };
  if (!group) return { text: "配置缺失", className: "danger" };
  return group.enabled
    ? { text: "告警启用", className: "enabled" }
    : { text: "告警停用", className: "warning" };
}

function alertRuleText(group) {
  if (!group) return "未绑";
  const count = Array.isArray(group.policies) && group.policies.length
    ? group.policies.length
    : (group.policy_ids || []).length;
  return count ? `${count} 条规则` : "未设置规";
}

function renderAlertServiceTypeTag(type) {
  const group = serviceTypeGroup(type);
  const labels = {
    interface: "接口服务",
    database: "数据",
    middleware: "中间",
    host: "主机服务",
    other: serviceTypeLabel(type),
  };
  return `<span class="alert-type-tag type-${group}">${escapeHtml(labels[group] || serviceTypeLabel(type))}</span>`;
}

function serviceTypeGroup(type) {
  if (["mysql", "oracle", "postgresql", "postgres"].includes(type)) return "database";
  if (["redis", "zookeeper"].includes(type)) return "middleware";
  if (["host", "process"].includes(type)) return "host";
  if (["web", "http", "https", "nginx", "port", "tcp"].includes(type)) return "interface";
  return "other";
}

function lastServiceAlert(service) {
  return alertSettingsState.alerts.find((alert) => alertMatchesService(alert, service)) || null;
}

function alertMatchesService(alert, service) {
  const alertServiceId = alert?.service_id ?? alert?.serviceId;
  if (alertServiceId !== undefined && alertServiceId !== null) {
    return Number(alertServiceId) === Number(service.id);
  }
  const alertServiceName = alert?.service_name ?? alert?.serviceName;
  return alertServiceName && alertServiceName === service.service_name;
}

function alertDeliveryState(alert) {
  if (!alert) return { text: "未触", className: "" };
  const status = String(alert.alert_status ?? alert.alertStatus ?? "").toLowerCase();
  if (["success", "sent", "delivered", "ok"].includes(status)) return { text: "已通知", className: "sent" };
  if (["failed", "error"].includes(status)) return { text: "通知失败", className: "failed" };
  return { text: status || "未触", className: "" };
}

function showServiceAlertRecords(serviceId) {
  const service = alertSettingsState.services.find((item) => Number(item.id) === Number(serviceId));
  if (!service) return;
  const records = alertSettingsState.alerts.filter((alert) => alertMatchesService(alert, service)).slice(0, 3);
  if (!records.length) {
    setAlertActionResult(serviceId, {
      ok: false,
      title: "暂无告警记录",
      details: ["该服务还没有产生告警通知记录"],
    });
    return;
  }
  setAlertActionResult(serviceId, {
    ok: records.every((record) => alertDeliveryState(record).className === "sent"),
    title: "最近告警记",
    details: records.map((record) => {
      const status = alertDeliveryState(record).text;
      const time = formatTime(record.created_at || record.createdAt);
      return `${time} / ${status} / ${record.alert_type || record.alertType || "-"}`;
    }),
  });
}

function parseApiDate(value) {
  if (!value) return null;
  const normalized = String(value).includes("T") ? String(value) : String(value).replace(" ", "T");
  const date = new Date(normalized);
  return Number.isNaN(date.getTime()) ? null : date;
}

function dayKey(date) {
  if (!date) return "";
  const month = `${date.getMonth() + 1}`.padStart(2, "0");
  const day = `${date.getDate()}`.padStart(2, "0");
  return `${date.getFullYear()}-${month}-${day}`;
}

function isAlertActionBusy(serviceId, action) {
  return Boolean(alertSettingsState.busyActions[`${serviceId}:${action}`]);
}

function setAlertActionBusy(serviceId, action, busy) {
  const key = `${serviceId}:${action}`;
  if (busy) {
    alertSettingsState.busyActions[key] = true;
  } else {
    delete alertSettingsState.busyActions[key];
  }
}

function setAlertActionResult(serviceId, result) {
  alertSettingsState.testResults[String(serviceId)] = result;
  renderAlertSettingsTable();
}

function isAlertConfigTestBusy(groupId) {
  return Boolean(alertSettingsState.busyActions[`config:${groupId}:test`]);
}

function setAlertConfigTestBusy(groupId, busy) {
  const key = `config:${groupId}:test`;
  if (busy) {
    alertSettingsState.busyActions[key] = true;
  } else {
    delete alertSettingsState.busyActions[key];
  }
}

function setAlertConfigTestResult(groupId, result) {
  alertSettingsState.testResults[`config:${groupId}`] = result;
  renderAlertGroups();
}

function normalizeApiValue(item, snakeKey, camelKey) {
  return item?.[snakeKey] ?? item?.[camelKey];
}

function renderAlertGroupSelectOptions(selectedId) {
  return [
    '<option value="">不绑定</option>',
    ...alertSettingsState.groups.map((group) => `
      <option value="${group.id}" ${Number(selectedId) === Number(group.id) ? "selected" : ""}>
        ${escapeHtml(group.group_name)}${group.enabled ? "" : " / 已停"}
      </option>
    `),
  ].join("");
}

function renderAlertGroupSelectOptions(selectedId) {
  return [
    '<option value="">不绑定</option>',
    ...alertSettingsState.groups.map((group) => `
      <option value="${group.id}" ${Number(selectedId) === Number(group.id) ? "selected" : ""}>
        ${escapeHtml(group.group_name)}${group.enabled ? "" : " / 已停"}
      </option>
    `),
  ].join("");
}

async function bindServiceAlertGroup(serviceId, value) {
  try {
    const updated = await LiveMonitorApi.updateServiceAlertGroup(serviceId, {
      alert_group_id: value ? Number(value) : null,
    });
    alertSettingsState.services = alertSettingsState.services.map((service) =>
      Number(service.id) === Number(updated.id) ? updated : service
    );
    renderAlertSettingsTable();
    showToast("服务告警组已更新");
  } catch (error) {
    showToast(error.message);
    renderAlertSettingsTable();
  }
}

async function testServiceAlert(serviceId) {
  setAlertActionBusy(serviceId, "check", true);
  setAlertActionResult(serviceId, {
    pending: true,
    ok: false,
    title: "服务探测",
    details: ["正在请求后端探测接口..."],
  });
  try {
    showToast("正在探测服务...");
    const result = await LiveMonitorApi.checkService(serviceId);
    const status = result?.status || "UNKNOWN";
    const responseTime = normalizeApiValue(result, "response_time_ms", "responseTimeMs");
    const checkedAt = normalizeApiValue(result, "checked_at", "checkedAt");
    setAlertActionResult(serviceId, {
      ok: status === "UP",
      title: `服务探测${status === "UP" ? "成功" : "完成"}`,
      details: [
        `状态：${status}`,
        `响应时间：${responseTime ?? "-"} ms`,
        `结果：${result?.message || "-"}`,
        checkedAt ? `时间：${formatTime(checkedAt)}` : "",
      ],
    });
    showToast("服务探测已完成");
    await loadAlertSettings();
  } catch (error) {
    setAlertActionResult(serviceId, {
      ok: false,
      title: "服务探测失败",
      details: [`错误：${error.message}`],
    });
    showToast(`服务探测失败: ${error.message}`);
  } finally {
    setAlertActionBusy(serviceId, "check", false);
    renderAlertSettingsTable();
  }
}

async function sendTestAlert(serviceId) {
  const service = alertSettingsState.services.find((s) => Number(s.id) === Number(serviceId));
  if (!service) {
    showToast("找不到服");
    return;
  }
  if (!service.alert_group_id) {
    setAlertActionResult(serviceId, {
      ok: false,
      title: "告警测试未发",
      details: ["该服务未绑定告警组，请先绑定告警配置"],
    });
    showToast("该服务未绑定告警组，请先绑定");
    return;
  }
  setAlertActionBusy(serviceId, "test", true);
  setAlertActionResult(serviceId, {
    pending: true,
    ok: false,
    title: "告警测试发送中",
    details: ["正在请求后端告警测试接口..."],
  });
  try {
    showToast("正在发送告警测试...");
    const result = await LiveMonitorApi.alertTest(serviceId);
    const record = result?.record || {};
    const alertStatus = normalizeApiValue(record, "alert_status", "alertStatus") || (result?.success ? "success" : "failed");
    const alertType = normalizeApiValue(record, "alert_type", "alertType") || "-";
    const alertContent = normalizeApiValue(record, "alert_content", "alertContent") || result?.error || "-";
    const createdAt = normalizeApiValue(record, "created_at", "createdAt");
    setAlertActionResult(serviceId, {
      ok: Boolean(result?.success),
      title: result?.success ? "告警测试已发送" : "告警测试发送失败",
      details: [
        `发送状态：${alertStatus}`,
        `告警类型：${alertType}`,
        `内容：${alertContent}`,
        createdAt ? `时间：${formatTime(createdAt)}` : "",
      ],
    });
    showToast(result?.success ? "告警测试已发送" : "告警测试发送失败");
  } catch (error) {
    setAlertActionResult(serviceId, {
      ok: false,
      title: "告警测试失败",
      details: [`错误：${error.message}`],
    });
    showToast(`告警测试失败: ${error.message}`);
  } finally {
    setAlertActionBusy(serviceId, "test", false);
    renderAlertSettingsTable();
  }
}

async function testAlertConfig(groupId) {
  const group = alertSettingsState.groups.find((item) => Number(item.id) === Number(groupId));
  const channel = groupPrimaryChannel(group);
  if (!group || !channel) {
    showToast("该告警配置缺少通知渠道");
    return;
  }
  setAlertConfigTestBusy(groupId, true);
  setAlertConfigTestResult(groupId, {
    pending: true,
    ok: false,
    title: "告警配置测试发送中",
    details: [`渠道：${channelTypeLabel(channel.channel_type)}`, "正在请求后端配置测试接口..."],
  });
  try {
    const result = await LiveMonitorApi.testAlertChannel(channel.id);
    setAlertConfigTestResult(groupId, {
      ok: Boolean(result?.success),
      title: result?.success ? "告警配置测试成功" : "告警配置测试失败",
      details: [
        `配置：${group.group_name || "-"}`,
        `渠道：${channelTypeLabel(result?.channel_type || channel.channel_type)}`,
        result?.tested_at ? `时间：${formatTime(result.tested_at)}` : "",
        result?.message ? `结果：${result.message}` : "",
      ],
    });
    showToast(result?.success ? "告警配置测试成功" : "告警配置测试失败");
  } catch (error) {
    setAlertConfigTestResult(groupId, {
      ok: false,
      title: "告警配置测试失败",
      details: [
        `配置：${group.group_name || "-"}`,
        `渠道：${channelTypeLabel(channel.channel_type)}`,
        `错误：${error.message}`,
      ],
    });
    showToast(`告警配置测试失败: ${error.message}`);
  } finally {
    setAlertConfigTestBusy(groupId, false);
    renderAlertGroups();
  }
}

function handleAlertGroupListClick(event) {
  const testButton = event.target.closest("[data-alert-test-config-id]");
  if (testButton) {
    event.stopPropagation();
    testAlertConfig(Number(testButton.dataset.alertTestConfigId));
    return;
  }
  const deleteButton = event.target.closest("[data-alert-delete-id]");
  if (deleteButton) {
    event.stopPropagation();
    deleteAlertConfig(Number(deleteButton.dataset.alertDeleteId));
    return;
  }
  const editButton = event.target.closest("[data-alert-edit-id]");
  if (editButton) {
    event.stopPropagation();
    openAlertConfigModal(Number(editButton.dataset.alertEditId));
    return;
  }
  const row = event.target.closest("[data-alert-group-id]");
  if (row) {
    openAlertConfigModal(Number(row.dataset.alertGroupId));
  }
}

function handleAlertSettingsTableClick(event) {
  const target = event.target instanceof Element ? event.target : event.target?.parentElement;
  const recordsButton = target?.closest("[data-alert-records]");
  if (recordsButton) {
    event.preventDefault();
    showServiceAlertRecords(Number(recordsButton.dataset.alertRecords));
    return;
  }
  const bindButton = target?.closest("[data-alert-bind]");
  if (bindButton) {
    event.preventDefault();
    openServiceBindingModal([Number(bindButton.dataset.alertBind)]);
    return;
  }
  const button = target?.closest("[data-alert-action]");
  if (!button) return;
  event.preventDefault();
  const serviceId = Number(button.dataset.serviceId);
  if (button.dataset.alertAction === "check") {
    testServiceAlert(serviceId);
  } else if (button.dataset.alertAction === "test") {
    sendTestAlert(serviceId);
  }
}

function handleAlertSettingsTableChange(event) {
  const select = event.target.closest("[data-service-alert-group-id]");
  if (!select) return;
  bindServiceAlertGroup(Number(select.dataset.serviceAlertGroupId), select.value);
}

function renderResultTable(results) {
  const tbody = document.getElementById("resultTable");
  if (!tbody) return;
  if (!results.length) {
    tbody.innerHTML = '<tr><td colspan="4" class="empty">暂无检测历史</td></tr>';
    return;
  }
  tbody.innerHTML = results.map((item) => `
    <tr>
      <td>${renderStatus(item.status)}</td>
      <td>${item.response_time_ms ?? "-"} ms</td>
      <td>${escapeHtml(item.message || "-")}</td>
      <td>${formatTime(item.checked_at)}</td>
    </tr>
  `).join("");
}

function renderAlerts(container, alerts, options = {}) {
  if (!container) return;
  const rows = options.uniqueByService ? uniqueByInstance(alerts) : alerts;
  if (!rows.length) {
    container.innerHTML = '<p class="empty">暂无告警</p>';
    return;
  }
  container.innerHTML = rows.map((alert) => `
    <article class="alert-item">
      <strong>${escapeHtml(alert.service_name || alert.alert_type || "告警")}</strong>
      <p>${escapeHtml(alert.alert_content || "-")}</p>
      <small>${alert.alert_type || "-"} · ${alert.alert_status || "-"} · ${formatTime(alert.created_at)}</small>
    </article>
  `).join("");
}

function renderDashboardActivity(container, alerts, results) {
  if (!container) return;
  const alertRows = uniqueByInstance(alerts);
  const resultRows = uniqueByInstance(results);
  const serviceRows = uniqueByInstance(dashboardState.services);
  dashboardState.lastActivityCandidates = [...alertRows, ...resultRows, ...serviceRows];

  const visibleAlerts = alertRows.filter((item) => !dashboardState.dismissedActivityKeys.has(activityItemKey(item)));
  if (visibleAlerts.length) {
    dashboardState.lastActivityRows = visibleAlerts;
    renderAlerts(container, visibleAlerts);
    return;
  }
  const uniqueResults = resultRows.filter((item) => !dashboardState.dismissedActivityKeys.has(activityItemKey(item)));
  if (!uniqueResults.length) {
    const services = serviceRows.filter((service) => !dashboardState.dismissedActivityKeys.has(activityItemKey(service)));
    dashboardState.lastActivityRows = services;
    container.innerHTML = services.map((service) => `
      <article class="activity-item">
        <span class="activity-status ${statusLabel(service.last_status)}"></span>
        <div>
          <strong>${escapeHtml(service.service_name)}</strong>
          <p>${statusLabel(service.last_status)} · ${renderLatency(service.last_response_time_ms)} · ${formatTime(service.last_checked_at)}</p>
        </div>
      </article>
  `).join("") || '<p class="empty">暂无动态</p>';
    return;
  }
  dashboardState.lastActivityRows = uniqueResults;
  container.innerHTML = uniqueResults.map((item) => `
    <article class="activity-item">
      <span class="activity-status ${statusLabel(item.status)}"></span>
      <div>
        <strong>${escapeHtml(item.service_name || "检测记")}</strong>
        <p>${statusLabel(item.status)} · ${renderLatency(item.response_time_ms)} · ${formatTime(item.checked_at)}</p>
      </div>
    </article>
  `).join("");
}

function uniqueByInstance(items) {
  const seen = new Set();
  return items.filter((item) => {
    const key = instanceKey(item);
    if (key === null || key === undefined || seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function instanceKey(item) {
  return item?.service_id ?? item?.serviceId ?? item?.id ?? item?.service_name ?? item?.serviceName;
}

function activityItemKey(item) {
  const instance = instanceKey(item);
  const activityId = item?.id ?? item?.created_at ?? item?.createdAt ?? item?.checked_at ?? item?.checkedAt ?? item?.last_checked_at ?? item?.lastCheckedAt ?? "";
  const status = item?.alert_status ?? item?.alertStatus ?? item?.status ?? item?.last_status ?? item?.lastStatus ?? "";
  return `${instance ?? "unknown"}:${activityId}:${status}`;
}


