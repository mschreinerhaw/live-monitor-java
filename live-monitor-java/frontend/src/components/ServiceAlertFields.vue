<template>
  <section class="card"><h2>告警规则与接收组</h2>
    <label><input v-model="form.enabled" type="checkbox">启用定时检查</label>
    <label style="margin-left:20px"><input v-model="form.service_alert_enabled" type="checkbox">启用服务可用性告警</label>
    <fieldset><legend>绑定告警组（可多选）</legend><label v-for="group in groups" :key="group.id" style="margin-right:16px"><input v-model="form.alert_group_ids" type="checkbox" :value="group.id">{{ group.group_name }}{{ group.enabled ? '' : '（已禁用）' }}</label></fieldset>
    <p v-if="!form.alert_group_ids?.length" role="status">未绑定告警组，发生异常时无法向外部渠道发送消息。</p>
    <div class="form-row"><label class="form-group">DOWN 连续失败次数（1 表示首次通知）<input v-model.number="form.service_consecutive_failures" type="number" min="1" max="1000" required class="form-control"></label><label class="form-group">恢复 UP 连续成功次数<input v-model.number="form.service_recover_successes" type="number" min="1" max="1000" required class="form-control"></label></div>
    <label><input v-model="form.unknown_alert_enabled" type="checkbox">启用 UNKNOWN 检查异常告警</label>
    <div class="form-row"><label class="form-group">UNKNOWN 连续异常次数<input v-model.number="form.unknown_consecutive_failures" type="number" min="1" max="1000" required class="form-control"></label><label class="form-group">持续异常提醒间隔（秒，0 表示仅首次通知）<input v-model.number="form.alert_repeat_interval_seconds" type="number" min="0" max="31536000" required class="form-control"></label></div>
  </section>
</template>
<script setup>
defineProps({ form: { type: Object, required: true }, groups: { type: Array, default: () => [] } })
</script>
