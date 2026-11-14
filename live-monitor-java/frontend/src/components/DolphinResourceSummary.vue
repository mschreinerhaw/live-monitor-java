<template>
  <section class="resource-summary" aria-label="集群资源概览">
    <div class="resource-intro">
      <span class="resource-icon"><IconSvg name="server" size="20" /></span>
      <div>
        <h2>集群资源概览</h2>
        <p>在线节点最高使用率</p>
      </div>
      <button type="button" class="resource-detail" @click="$emit('details')">
        资源详情<IconSvg name="chevron-right" size="14" />
      </button>
    </div>
    <div v-for="r in roles" :key="r.role" class="role-summary">
      <div class="role-title">
        <strong>{{ r.label }}</strong
        ><span
          :class="[
            'role-status',
            fresh && r.total && r.online === r.total ? 'healthy' : 'uncertain',
          ]"
          ><i></i>{{ fresh ? r.online + ' / ' + r.total + ' 在线' : '数据待更新' }}</span
        >
      </div>
      <div class="role-values">
        <div
          v-for="m in [
            { label: 'CPU', key: 'cpu_percent' },
            { label: '主机内存', key: 'host_memory_percent' },
          ]"
          :key="m.key"
        >
          <span>{{ m.label }}</span
          ><strong>{{ pct(r[m.key]) }}</strong>
          <div class="resource-track" aria-hidden="true">
            <span
              :class="{ high: r[m.key] >= 85 }"
              :style="{ width: Math.min(100, r[m.key] || 0) + '%' }"
            ></span>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>
<script setup>
import { computed } from 'vue'
import IconSvg from './IconSvg.vue'
const props = defineProps({ nodes: { type: Array, default: () => [] }, fresh: Boolean })
defineEmits(['details'])
const pct = (v) => (v == null ? '未知' : Number(v).toFixed(1) + '%')
const roles = computed(() =>
  [
    { role: 'masters', label: 'Master' },
    { role: 'workers', label: 'Worker' },
  ].map((r) => {
    const nodes = props.nodes.filter((n) => n.role === r.role),
      row = { ...r, total: nodes.length, online: nodes.filter((n) => n.online).length }
    for (const key of ['cpu_percent', 'host_memory_percent']) {
      const values = nodes
        .filter(
          (n) => n.online && !n.metrics_stale && n[key] != null && Number.isFinite(Number(n[key]))
        )
        .map((n) => Number(n[key]))
      row[key] = values.length ? Math.max(...values) : null
    }
    return row
  })
)
</script>
<style scoped>
.resource-summary {
  display: grid;
  grid-template-columns: 1.1fr 1fr 1fr;
  gap: 0;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  margin-bottom: 22px;
  overflow: hidden;
}
.resource-intro {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 18px 20px;
  flex-wrap: wrap;
}
.resource-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  background: #f1f5f9;
  color: #64748b;
  border-radius: 8px;
}
.resource-intro h2 {
  font-size: 13px;
  font-weight: 600;
  color: #334155;
}
.resource-intro p {
  font-size: 11px;
  color: #94a3b8;
  margin-top: 4px;
}
.resource-detail {
  display: flex;
  align-items: center;
  gap: 2px;
  border: 0;
  background: none;
  color: #0284c7;
  font-size: 11px;
  cursor: pointer;
  margin-left: auto;
}
.role-summary {
  padding: 16px 22px;
  border-left: 1px solid #edf2f7;
}
.role-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.role-title > strong {
  font-size: 12px;
  color: #475569;
  font-weight: 600;
}
.role-status {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 10px;
  white-space: nowrap;
}
.role-status i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
}
.healthy {
  color: #15803d;
}
.uncertain {
  color: #b45309;
}
.role-values {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 24px;
  margin-top: 10px;
}
.role-values > div {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 5px;
  font-size: 11px;
}
.role-values span {
  color: #94a3b8;
}
.role-values strong {
  font-weight: 500;
  font-variant-numeric: tabular-nums;
  color: #334155;
}
.resource-track {
  grid-column: 1/-1;
  height: 3px;
  background: #f1f5f9;
  border-radius: 2px;
  overflow: hidden;
}
.resource-track span {
  display: block;
  height: 100%;
  background: #38bdf8;
  min-width: 0;
}
.resource-track span.high {
  background: #f59e0b;
}
.resource-detail:focus-visible {
  outline: 2px solid #0284c7;
  outline-offset: 3px;
}
@media (max-width: 1060px) {
  .resource-summary {
    grid-template-columns: 1fr 1fr;
  }
  .resource-intro {
    grid-column: 1/-1;
    padding: 12px 18px;
    border-bottom: 1px solid #edf2f7;
  }
  .resource-icon {
    width: 30px;
    height: 30px;
  }
  .role-summary {
    padding: 14px 18px;
  }
  .role-summary:nth-child(2) {
    border-left: 0;
  }
}
@media (max-width: 480px) {
  .role-values {
    gap: 14px;
    grid-template-columns: 1fr;
  }
  .role-summary {
    padding: 14px;
  }
  .role-title {
    flex-wrap: wrap;
    gap: 5px;
  }
  .resource-intro {
    gap: 9px;
  }
}
</style>
