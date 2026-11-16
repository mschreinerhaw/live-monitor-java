<template>
  <section class="ds-overview-panel" style="margin-top: 18px">
    <header>
      <div>
        <h2>项目执行情况 <small>异常优先</small></h2>
        <p>占比按各项目已采集执行实例数计算；点击条形图查看对应实例，暂无执行时占比显示 —。</p>
      </div>
      <div class="actions">
        <button
          :class="['project-filter', { active: onlyAbnormal }]"
          :aria-pressed="onlyAbnormal"
          @click="onlyAbnormal = !onlyAbnormal"
        >
          仅看异常 ({{ projects.filter((p) => p.workflow_statistics?.failed > 0).length }})</button
        ><button class="project-filter" :aria-pressed="!onlyAbnormal" @click="onlyAbnormal = false">
          查看全部</button
        ><input
          v-model="search"
          type="search"
          aria-label="搜索概览项目"
          placeholder="搜索项目名称或编码"
        /><button class="link-button" @click="$emit('projects')">项目明细 →</button>
      </div>
    </header>
    <div class="project-health-grid">
      <article
        v-for="p in pager.items"
        :key="p.id"
        class="project-health-card"
        :class="{ 'has-failure': p.workflow_statistics?.failed > 0 }"
      >
        <div class="project-title">
          <button class="link-button" :disabled="pending" @click="$emit('definitions', p)">
            {{ label(p) }}</button
          ><span :class="['status', projectTone(p)]">{{ projectState(p) }}</span>
        </div>
        <p>
          {{ number(p.online_count) }} 上线工作流 ·
          {{ number(p.workflow_statistics?.total) }} 次执行
        </p>
        <div class="project-bar-heading"><span>执行状态 / 实例数</span><span>占比</span></div>
        <div class="project-counts">
          <button
            v-for="s in states"
            :key="s.key"
            :class="['project-state-bar', 'state-' + s.key]"
            :aria-label="
              label(p) + '的' + s.label + '实例 ' + number(p.workflow_statistics?.[s.key])
            "
            :title="s.label + '占已采集执行实例 ' + percentage(p, s.key)"
            :disabled="pending || p.workflow_statistics?.[s.key] == null"
            @click="$emit('instances', p, s.state)"
          >
            <span class="state-label">{{ s.label }}</span
            ><span class="state-track" aria-hidden="true"
              ><i :style="{ width: (ratio(p, s.key) ?? 0) + '%' }"></i></span
            ><strong
              :class="s.key === 'failed' && p.workflow_statistics?.failed > 0 ? 'failed-count' : ''"
              >{{ number(p.workflow_statistics?.[s.key]) }}</strong
            ><span class="state-percentage">{{ percentage(p, s.key) }}</span>
          </button>
        </div>
      </article>
    </div>
    <p v-if="!pager.total" class="empty">{{ known ? '暂无匹配项目' : '项目等待同步' }}</p>
    <PaginationControls
      v-model:page="pager.page"
      v-model:page-size="pager.pageSize"
      :total="pager.total"
      label="项目健康"
    />
  </section>
</template>
<script setup>
import { computed, ref } from 'vue'
import PaginationControls from './PaginationControls.vue'
import { usePagination } from '../composables/usePagination'
const props = defineProps({
  projects: { type: Array, default: () => [] },
  known: Boolean,
  pending: Boolean,
  reliable: Boolean,
  scopeKey: String,
})
defineEmits(['instances', 'definitions', 'projects'])
const onlyAbnormal = ref(false)
const search = ref(''),
  label = (p) => p.description?.trim() || p.name || String(p.id),
  number = (n) => (n == null ? '未知' : Number(n).toLocaleString('zh-CN'))
const states = [
  { key: 'running', label: '运行中', state: 'RUNNING_EXECUTION' },
  { key: 'success', label: '成功', state: 'success' },
  { key: 'failed', label: '失败', state: 'FAILURE' },
  { key: 'waiting', label: '待执行', state: 'waiting' },
]
function ratio(project, key) {
  const total = project.workflow_statistics?.total,
    count = project.workflow_statistics?.[key]
  if (
    total == null ||
    count == null ||
    !Number.isFinite(Number(total)) ||
    !Number.isFinite(Number(count)) ||
    Number(total) <= 0 ||
    Number(count) < 0 ||
    Number(count) > Number(total)
  )
    return null
  return (Number(count) / Number(total)) * 100
}
const percentage = (project, key) => {
  const value = ratio(project, key)
  return value == null ? '—' : value.toFixed(1) + '%'
}
const ordered = computed(() =>
  props.projects
    .filter(
      (p) =>
        (!onlyAbnormal.value || p.workflow_statistics?.failed > 0) &&
        `${label(p)} ${p.id}`.toLowerCase().includes(search.value.toLowerCase())
    )
    .slice()
    .sort(
      (a, b) =>
        (b.workflow_statistics?.failed || 0) - (a.workflow_statistics?.failed || 0) ||
        (b.workflow_statistics?.running || 0) - (a.workflow_statistics?.running || 0)
    )
)
const pager = usePagination(ordered, [search, onlyAbnormal, () => props.scopeKey])
const projectState = (p) =>
  p.workflow_statistics?.failed > 0
    ? '存在失败'
    : p.workflow_statistics?.running > 0
    ? '运行中'
    : !props.reliable || p.workflow_statistics?.total == null
    ? '待确认'
    : p.workflow_statistics?.waiting > 0
    ? '待执行'
    : '未发现失败'
const projectTone = (p) =>
  p.workflow_statistics?.failed > 0
    ? 'down'
    : p.workflow_statistics?.running > 0
    ? 'running'
    : !props.reliable || p.workflow_statistics?.total == null
    ? 'unknown'
    : p.workflow_statistics?.waiting > 0
    ? 'warn'
    : 'up'
</script>
<style scoped>
.ds-overview-panel {
  background: white;
  border: 1px solid #dce7f4;
  border-radius: 8px;
  padding: 14px;
  margin-bottom: 18px;
}
.ds-overview-panel header,
.actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.ds-overview-panel header {
  margin-bottom: 14px;
}
.ds-overview-panel h2 {
  font-size: 15px;
  color: #173958;
  margin: 0;
}
.ds-overview-panel h2 small {
  font-size: 11px;
  font-weight: 400;
  color: #708295;
}
.ds-overview-panel p {
  font-size: 11px;
  color: #708295;
  line-height: 1.7;
  margin: 5px 0 0;
}
.project-health-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(270px, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}
.project-health-card {
  border: 1px solid #d7e5f2;
  background: linear-gradient(130deg, #f4f9ff, #fff);
  border-radius: 7px;
  padding: 14px;
  min-width: 0;
}
.project-health-card.has-failure {
  background: linear-gradient(130deg, #fff5f3, #fff);
  border-color: #f59999;
}
.project-title {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: flex-start;
}
.project-title button {
  text-align: left;
  font-size: 13px;
  font-weight: 650;
  overflow-wrap: anywhere;
}
.link-button {
  border: 0;
  background: none;
  color: #16738c;
  cursor: pointer;
  font: inherit;
  font-size: 12px;
  padding: 0;
}
.status {
  font-size: 10px;
  padding: 3px 6px;
  border-radius: 3px;
  white-space: nowrap;
}
.down {
  background: #fbe9e8;
  color: #aa3838;
}
.up {
  background: #e7f4ed;
  color: #206b46;
}
.unknown {
  background: #edf0f3;
  color: #6a7883;
}
.running {
  background: #e5f1fa;
  color: #226591;
}
.warn {
  background: #fff1d9;
  color: #96600e;
}
.project-bar-heading {
  display: flex;
  justify-content: space-between;
  margin-top: 12px;
  padding-top: 9px;
  border-top: 1px solid #e4ebef;
  font-size: 10px;
  color: #7d8fa3;
}
.project-counts {
  display: grid;
  grid-template-columns: 1fr;
  gap: 2px;
  margin-top: 5px;
}
.project-state-bar {
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr) 34px 46px;
  align-items: center;
  gap: 8px;
  width: 100%;
  border: 0;
  background: none;
  text-align: left;
  padding: 6px 0;
  cursor: pointer;
  color: #536c7d;
  border-radius: 3px;
}
.project-state-bar:disabled {
  cursor: default;
}
.project-state-bar:hover:not(:disabled) {
  background: #edf5ff;
}
.project-state-bar .state-label {
  font-size: 11px;
}
.project-state-bar strong {
  font-size: 13px;
  text-align: right;
  font-variant-numeric: tabular-nums;
  color: #263746;
}
.project-state-bar .failed-count {
  color: #c13b36;
}
.state-track {
  height: 9px;
  background: #eaf0f7;
  border-radius: 2px;
  overflow: hidden;
}
.state-track i {
  display: block;
  height: 100%;
  background: #1689e7;
  border-radius: 2px;
}
.state-success .state-track i {
  background: #27a981;
}
.state-failed .state-track i {
  background: #ed6060;
}
.state-waiting .state-track i {
  background: #e3a23e;
}
.state-percentage {
  text-align: right;
  font-size: 10px;
  font-variant-numeric: tabular-nums;
  color: #708295;
}
.actions {
  gap: 7px;
}
.actions input {
  border: 1px solid #d2dde3;
  border-radius: 4px;
  padding: 6px 8px;
  font-size: 11px;
  width: 175px;
  max-width: 100%;
}
.project-filter {
  border: 1px solid #dce7f4;
  background: white;
  color: #64748b;
  border-radius: 4px;
  padding: 5px 9px;
  font-size: 11px;
  cursor: pointer;
}
.project-filter.active {
  background: #1689e7;
  color: white;
  border-color: #1689e7;
}
.empty {
  padding: 12px 0;
}
button:focus-visible {
  outline: 2px solid #27879a;
  outline-offset: 3px;
}
@media (max-width: 600px) {
  .actions {
    width: 100%;
  }
  .actions input {
    flex: 1;
    min-width: 0;
  }
  .project-health-grid {
    grid-template-columns: 1fr;
  }
  .project-health-card {
    padding: 12px;
  }
}
</style>
