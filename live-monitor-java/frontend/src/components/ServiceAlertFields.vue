<template>
  <section class="card alert-policy-card">
    <header><h2>告警规则与接收组</h2><p>选择接收组，并设置异常与恢复的确认条件。</p></header>
    <div class="policy-switches">
      <label><input v-model="form.enabled" type="checkbox">启用定时检查</label>
      <label><input v-model="form.service_alert_enabled" type="checkbox">启用服务可用性告警</label>
    </div>
    <fieldset class="groups-fieldset">
      <legend>绑定告警组（可多选）</legend>
      <div class="group-options"><label v-for="group in groups" :key="group.id" :class="['group-option', { selected: form.alert_group_ids?.includes(group.id) }]"><input v-model="form.alert_group_ids" type="checkbox" :value="group.id"><span>{{ group.group_name }}{{ group.enabled ? '' : '（已禁用）' }}</span></label></div>
      <p v-if="!form.alert_group_ids?.length" role="status" class="binding-hint">未绑定告警组，发生异常时无法向外部渠道发送消息。</p>
    </fieldset>
    <div class="policy-grid">
      <label>DOWN 连续失败次数（1 表示首次通知）<input v-model.number="form.service_consecutive_failures" type="number" min="1" max="1000" required class="form-control"></label>
      <label>恢复 UP 连续成功次数<input v-model.number="form.service_recover_successes" type="number" min="1" max="1000" required class="form-control"></label>
    </div>
    <div class="unknown-policy">
      <label class="unknown-switch"><input v-model="form.unknown_alert_enabled" type="checkbox">启用 UNKNOWN 检查异常告警</label>
      <div class="policy-grid">
        <label>UNKNOWN 连续异常次数<input v-model.number="form.unknown_consecutive_failures" type="number" min="1" max="1000" required class="form-control"></label>
        <label>持续异常提醒间隔（秒，0 表示仅首次通知）<input v-model.number="form.alert_repeat_interval_seconds" type="number" min="0" max="31536000" required class="form-control"></label>
      </div>
    </div>
  </section>
</template>
<script setup>
defineProps({ form: { type: Object, required: true }, groups: { type: Array, default: () => [] } })
</script>
<style scoped>
.alert-policy-card { display: flex; flex-direction: column; gap: 22px; }
header h2 { font-size: 20px; margin: 0 0 6px; }
header p, .binding-hint { font-size: 13px; color: var(--text-muted, #64748b); line-height: 1.6; margin: 0; }
.policy-switches { display: flex; flex-wrap: wrap; gap: 14px 28px; }
.policy-switches label, .unknown-switch { display: inline-flex; align-items: center; gap: 8px; font-size: 14px; cursor: pointer; }
input[type="checkbox"] { width: 16px; height: 16px; flex-shrink: 0; accent-color: var(--primary-color, #0284c7); }
.groups-fieldset { border: 0; padding: 0; margin: 0; min-width: 0; }
.groups-fieldset legend { font-size: 13px; font-weight: 600; margin-bottom: 10px; }
.group-options { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 8px; }
.group-option { display: inline-flex; align-items: center; gap: 8px; border: 1px solid #dce3ec; border-radius: 8px; background: #f8fafc; padding: 10px 14px; font-size: 14px; cursor: pointer; }
.group-option.selected { background: #eff8ff; border-color: #0284c7; }
.policy-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; }
.policy-grid label { display: flex; flex-direction: column; gap: 8px; font-size: 13px; font-weight: 500; min-width: 0; }
.policy-grid .form-control { height: 40px; }
.unknown-policy { background: #f8fafc; border: 1px solid #e6ebf1; border-radius: 10px; padding: 18px; }
.unknown-switch { margin-bottom: 18px; }
@media (max-width: 680px) { .policy-grid { grid-template-columns: minmax(0, 1fr); } .unknown-policy { padding: 14px; } }
</style>