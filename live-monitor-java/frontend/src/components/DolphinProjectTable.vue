<template>
  <section class="card project-section" aria-labelledby="ds-project-title">
    <header class="project-heading">
      <div>
        <h2 id="ds-project-title">
          项目执行情况
          <span class="total-tag">{{ known ? projects.length + ' 个项目' : '同步中' }}</span>
        </h2>
        <p>按当前筛选范围查看执行结果，点击状态数量进入明细。</p>
      </div>
      <label class="search-box"
        ><IconSvg name="search" size="16" /><input
          v-model="search"
          aria-label="搜索项目名称或编码"
          placeholder="搜索项目名称或编码" /><button
          v-if="search"
          type="button"
          aria-label="清空项目搜索"
          @click="search = ''"
        >
          <IconSvg name="x" size="14" /></button
      ></label>
    </header>
    <div class="list-toolbar">
      <div class="project-scopes" aria-label="项目快捷筛选">
        <button
          v-for="item in scopes"
          :key="item.key"
          type="button"
          :class="{ selected: filter === item.key }"
          :aria-pressed="filter === item.key"
          @click="$emit('update:filter', item.key)"
        >
          {{ item.label }}<span v-if="known">{{ item.count }}</span>
        </button>
      </div>
      <label class="sort-control"
        >排序<select v-model="sort" aria-label="项目排序">
          <option value="attention">异常优先</option>
          <option value="running">运行数量</option>
          <option value="name">项目名称</option>
        </select></label
      >
    </div>
    <div class="table-wrap">
      <table class="table project-table">
        <thead>
          <tr>
            <th>项目</th>
            <th class="numeric">上线工作流</th>
            <th class="numeric">运行中</th>
            <th class="numeric">已成功</th>
            <th class="numeric">已失败</th>
            <th class="numeric">待执行实例</th>
            <th class="numeric">未执行工作流</th>
            <th class="action-cell">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="p in pager.items"
            :key="p.id"
            :class="{ 'has-failure': p.workflow_statistics?.failed > 0 }"
          >
            <td class="project-name">
              <div class="name-line">
                <span
                  v-if="p.workflow_statistics?.failed > 0"
                  class="attention-dot"
                  aria-label="存在失败实例"
                ></span
                ><button
                  class="project-link"
                  :disabled="pending"
                  :title="label(p)"
                  @click="$emit('definitions', p)"
                >
                  {{ label(p) }}
                </button>
              </div>
              <small
                ><span class="mono">{{ p.id }}</span
                ><span class="meta-divider">·</span
                >{{ count(p.workflow_statistics?.total) }} 次执行</small
              >
            </td>
            <td class="numeric inventory-count">
              <strong>{{ count(p.online_count) }}</strong
              ><small>任务定义 {{ count(p.task_definition_count) }}</small>
            </td>
            <td v-for="s in states" :key="s.key" class="numeric">
              <button
                type="button"
                :class="[
                  'status-count',
                  s.tone,
                  { 'is-zero': p.workflow_statistics?.[s.key] === 0 },
                ]"
                :disabled="pending || p.workflow_statistics?.[s.key] == null"
                :aria-label="
                  label(p) + '的' + s.label + '实例 ' + count(p.workflow_statistics?.[s.key])
                "
                @click="$emit('instances', p, s.state)"
              >
                {{ count(p.workflow_statistics?.[s.key]) }}
              </button>
            </td>
            <td class="numeric">
              <span class="missing-count">{{ count(p.not_executed_workflows) }}</span
              ><small v-if="p.unclassified_workflows > 0" class="unknown-count"
                >{{ count(p.unclassified_workflows) }} 待确认</small
              >
            </td>
            <td class="action-cell">
              <button
                type="button"
                class="detail-link"
                :disabled="pending"
                @click="$emit('instances', p)"
              >
                执行明细<IconSvg name="chevron-right" size="14" />
              </button>
            </td>
          </tr>
          <tr v-if="!pager.items.length">
            <td colspan="8">
              <div class="empty-projects">
                <IconSvg name="search" size="28" /><strong>{{
                  search || filter !== 'all' ? '没有匹配的项目' : '暂无项目数据'
                }}</strong>
                <p>
                  {{
                    search || filter !== 'all'
                      ? '试试其他项目名称或切换到全部项目。'
                      : '完成集群接入和首次采集后，项目将在这里显示。'
                  }}
                </p>
                <button
                  v-if="search || filter !== 'all'"
                  class="btn btn-ghost btn-sm"
                  @click="clearListFilters"
                >
                  清除列表筛选
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="mobile-projects">
      <article v-for="p in pager.items" :key="p.id" class="mobile-project">
        <header>
          <button class="project-link" :disabled="pending" @click="$emit('definitions', p)">
            <span v-if="p.workflow_statistics?.failed > 0" class="attention-dot"></span
            >{{ label(p) }}</button
          ><button class="detail-link" :disabled="pending" @click="$emit('instances', p)">
            执行明细<IconSvg name="chevron-right" size="12" />
          </button>
        </header>
        <p class="mobile-project-meta">
          <span class="mono">{{ p.id }}</span
          ><span
            >上线 {{ count(p.online_count) }} · 执行 {{ count(p.workflow_statistics?.total) }}</span
          >
        </p>
        <div class="mobile-state-grid">
          <button
            v-for="s in states"
            :key="s.key"
            type="button"
            :class="s.tone"
            :disabled="pending || p.workflow_statistics?.[s.key] == null"
            :aria-label="
              label(p) + '的' + s.label + '实例 ' + count(p.workflow_statistics?.[s.key])
            "
            @click="$emit('instances', p, s.state)"
          >
            <span>{{ s.label }}</span
            ><strong>{{ count(p.workflow_statistics?.[s.key]) }}</strong>
          </button>
        </div>
        <footer>
          <span
            >未执行工作流 <strong>{{ count(p.not_executed_workflows) }}</strong></span
          ><span v-if="p.unclassified_workflows > 0" class="unknown-count"
            >{{ count(p.unclassified_workflows) }} 待确认</span
          ><span v-else>任务定义 {{ count(p.task_definition_count) }}</span>
        </footer>
      </article>
      <div v-if="!pager.items.length" class="empty-projects">
        <IconSvg name="search" size="27" /><strong>{{
          search || filter !== 'all' ? '没有匹配的项目' : '暂无项目数据'
        }}</strong
        ><button
          v-if="search || filter !== 'all'"
          class="btn btn-ghost btn-sm"
          @click="clearListFilters"
        >
          清除列表筛选
        </button>
      </div>
    </div>
    <PaginationControls
      v-model:page="pager.page"
      v-model:page-size="pager.pageSize"
      :total="pager.total"
      :loading="pending"
      label="项目"
    />
    <details class="accounting-note">
      <summary><IconSvg name="info" size="14" />统计说明</summary>
      <p>
        上线数按工作流定义统计；运行、成功、失败、待执行按实例次数统计，重跑可以产生多个实例。“未执行工作流”指当前上线且在所选范围内未发现实例的工作流，不代表未来调度次数。业务日期或采集范围无法确认时显示未知。
      </p>
    </details>
  </section>
</template>
<script setup>
import { computed, ref, watch } from 'vue'
import IconSvg from './IconSvg.vue'
import PaginationControls from './PaginationControls.vue'
import { usePagination } from '../composables/usePagination'
const props = defineProps({
  projects: { type: Array, default: () => [] },
  known: { type: Boolean, default: true },
  pending: Boolean,
  filter: { type: String, default: 'all' },
  scopeKey: String,
})
const emit = defineEmits(['definitions', 'instances', 'update:filter'])
const search = ref(''),
  sort = ref('attention')
const label = (p) => p.description?.trim() || p.name || p.id
const count = (v) => (v == null ? '未知' : Number(v).toLocaleString('zh-CN'))
const states = [
  { key: 'running', state: 'RUNNING_EXECUTION', label: '运行中', tone: 'running' },
  { key: 'success', state: 'success', label: '成功', tone: 'success' },
  { key: 'failed', state: 'FAILURE', label: '失败', tone: 'failed' },
  { key: 'waiting', state: 'waiting', label: '待执行', tone: 'waiting' },
]
const matches = (p, key) =>
  key === 'all' ||
  (key === 'failed' && p.workflow_statistics?.failed > 0) ||
  (key === 'running' && p.workflow_statistics?.running > 0) ||
  (key === 'unstarted' && p.not_executed_workflows > 0)
const scopes = computed(() =>
  [
    { key: 'all', label: '全部项目' },
    { key: 'failed', label: '异常项目' },
    { key: 'running', label: '运行中' },
    { key: 'unstarted', label: '有未执行' },
  ].map((s) => ({ ...s, count: props.projects.filter((p) => matches(p, s.key)).length }))
)
const rows = computed(() =>
  props.projects
    .filter(
      (p) =>
        matches(p, props.filter) &&
        [label(p), p.id, p.name].join(' ').toLowerCase().includes(search.value.trim().toLowerCase())
    )
    .sort((a, b) => {
      if (sort.value === 'attention') {
        const failed = (b.workflow_statistics?.failed || 0) - (a.workflow_statistics?.failed || 0)
        if (failed) return failed
      }
      if (sort.value !== 'name') {
        const running =
          (b.workflow_statistics?.running || 0) - (a.workflow_statistics?.running || 0)
        if (running) return running
      }
      return label(a).localeCompare(label(b), 'zh-CN')
    })
)
function clearListFilters() {
  search.value = ''
  emit('update:filter', 'all')
}
const pager = usePagination(rows, [search, sort, () => props.filter])
watch(
  () => props.scopeKey,
  () => {
    search.value = ''
    pager.page = 1
    emit('update:filter', 'all')
  }
)
</script>
<style scoped>
.project-section {
  padding: 0;
  overflow: hidden;
}
.project-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
  padding: 20px 22px 16px;
}
.project-heading h2 {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 16px;
}
.project-heading p {
  font-size: 12px;
  color: var(--text-muted);
  margin-top: 5px;
}
.total-tag {
  font-size: 11px;
  font-weight: 500;
  color: #64748b;
  background: #f1f5f9;
  border-radius: 4px;
  padding: 3px 7px;
}
.search-box {
  display: flex;
  align-items: center;
  gap: 8px;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  padding: 8px 10px;
  color: #94a3b8;
  min-width: 240px;
}
.search-box:focus-within {
  border-color: #0284c7;
  box-shadow: 0 0 0 2px #e0f2fe;
}
.search-box input {
  border: 0;
  outline: none;
  font: inherit;
  font-size: 12px;
  width: 100%;
  min-width: 0;
  background: none;
  color: var(--text-main);
}
.search-box button {
  display: flex;
  border: 0;
  background: none;
  cursor: pointer;
  color: #64748b;
}
.list-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 0 22px 14px;
}
.project-scopes {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.project-scopes button {
  display: flex;
  align-items: center;
  gap: 7px;
  border: 0;
  background: none;
  border-radius: 5px;
  padding: 6px 9px;
  color: #64748b;
  font-size: 12px;
  cursor: pointer;
}
.project-scopes button.selected {
  color: #0369a1;
  background: #eff6ff;
  font-weight: 600;
}
.project-scopes span {
  font-size: 11px;
  opacity: 0.75;
}
.sort-control {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #64748b;
  white-space: nowrap;
}
.sort-control select {
  border: 0;
  color: #334155;
  background: transparent;
  cursor: pointer;
  font-size: 12px;
  max-width: 120px;
}
.table-wrap {
  border-radius: 0;
  border: 0;
  border-top: 1px solid #e8edf3;
}
.project-table {
  width: 100%;
  table-layout: auto;
  font-size: 12px;
}
.project-table th {
  font-size: 11px;
  white-space: nowrap;
  padding: 12px 16px;
  background: #f8fafc;
  color: #64748b;
  font-weight: 500;
}
.project-table td {
  padding: 16px;
  border-bottom: 1px solid #eef2f6;
  vertical-align: middle;
}
.project-table tr:last-child td {
  border-bottom: 0;
}
.project-table tbody tr:hover {
  background: #f8fbfe;
}
.project-table .numeric {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.project-name {
  min-width: 180px;
  max-width: 280px;
}
.name-line {
  display: flex;
  gap: 7px;
  align-items: center;
}
.project-link {
  font-size: 13px;
  font-weight: 600;
  color: #334155;
  background: none;
  border: 0;
  cursor: pointer;
  max-width: 245px;
  text-align: left;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.project-link:hover {
  color: #0284c7;
}
.attention-dot {
  width: 6px;
  height: 6px;
  background: #ef4444;
  border-radius: 50%;
  flex: none;
}
.project-table small {
  display: block;
  margin-top: 5px;
  font-size: 10px;
  color: #94a3b8;
  font-weight: 400;
  white-space: nowrap;
}
.meta-divider {
  padding: 0 6px;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
}
.inventory-count strong {
  font-weight: 600;
  color: #334155;
}
.status-count {
  border: 0;
  border-radius: 4px;
  min-width: 32px;
  padding: 4px 7px;
  font-size: 13px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  background: none;
  cursor: pointer;
}
.status-count.running {
  color: #0284c7;
  background: #eff6ff;
}
.status-count.success {
  color: #15803d;
  background: #f0fdf4;
}
.status-count.failed {
  color: #dc2626;
  background: #fef2f2;
}
.status-count.waiting {
  color: #b45309;
  background: #fffbeb;
}
.status-count.is-zero {
  color: #94a3b8;
  background: none;
  font-weight: 400;
}
.status-count:hover:enabled {
  box-shadow: inset 0 0 0 1px currentColor;
}
.status-count:disabled {
  cursor: default;
}
.missing-count {
  color: #475569;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.project-table .unknown-count {
  color: #b45309;
}
.action-cell {
  text-align: right;
  white-space: nowrap;
}
.detail-link {
  display: inline-flex;
  gap: 2px;
  align-items: center;
  background: none;
  border: 0;
  color: #0284c7;
  font-size: 12px;
  cursor: pointer;
  white-space: nowrap;
}
.detail-link:disabled {
  opacity: 0.5;
  cursor: wait;
}
.project-section :deep(.list-pagination) {
  margin: 0;
  padding: 16px 22px;
  border-top: 1px solid #eef2f6;
}
.accounting-note {
  padding: 10px 22px 14px;
  font-size: 11px;
  color: #94a3b8;
}
.accounting-note summary {
  display: flex;
  gap: 5px;
  align-items: center;
  cursor: pointer;
  list-style: none;
  max-width: max-content;
}
.accounting-note p {
  max-width: 920px;
  margin-top: 8px;
  color: #64748b;
  line-height: 1.8;
}
.empty-projects {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  text-align: center;
  padding: 36px;
  color: #94a3b8;
}
.empty-projects strong {
  font-size: 14px;
  font-weight: 500;
  color: #475569;
}
.empty-projects p {
  font-size: 12px;
}
.status-count:focus-visible,
.project-link:focus-visible,
.detail-link:focus-visible,
.project-scopes button:focus-visible {
  outline: 2px solid #0284c7;
  outline-offset: 3px;
}
@media (max-width: 760px) {
  .project-heading {
    flex-direction: column;
    align-items: stretch;
    padding: 16px;
  }
  .search-box {
    min-width: 0;
  }
  .list-toolbar {
    padding: 0 16px 12px;
    align-items: flex-start;
    flex-wrap: wrap;
  }
  .project-table {
    min-width: 880px;
  }
  .project-section :deep(.list-pagination) {
    padding: 14px 16px;
  }
  .accounting-note {
    padding: 10px 16px;
  }
}

.mobile-projects {
  display: none;
}
@media (max-width: 600px) {
  .project-section > .table-wrap {
    display: none;
  }
  .mobile-projects {
    display: block;
    border-top: 1px solid #edf2f7;
  }
  .mobile-project {
    padding: 16px;
    border-bottom: 1px solid #edf2f7;
  }
  .mobile-project:last-child {
    border-bottom: 0;
  }
  .mobile-project header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }
  .mobile-project .project-link {
    display: flex;
    align-items: center;
    gap: 6px;
    max-width: 215px;
    font-size: 13px;
    white-space: nowrap;
  }
  .mobile-project .detail-link {
    font-size: 11px;
    flex: none;
  }
  .mobile-project-meta {
    display: flex;
    justify-content: space-between;
    align-items: center;
    flex-wrap: wrap;
    gap: 5px;
    margin-top: 5px;
    font-size: 10px;
    color: #64748b;
  }
  .mobile-project-meta .mono {
    font-size: 9px;
  }
  .mobile-state-grid {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    margin-top: 12px;
    gap: 6px;
  }
  .mobile-state-grid button {
    background: #f8fafc;
    border: 0;
    border-radius: 4px;
    display: flex;
    flex-direction: column;
    gap: 4px;
    align-items: center;
    padding: 7px 3px;
    cursor: pointer;
  }
  .mobile-state-grid span {
    font-size: 10px;
    color: #64748b;
  }
  .mobile-state-grid strong {
    font-size: 15px;
    font-weight: 600;
    font-variant-numeric: tabular-nums;
  }
  .mobile-state-grid .running strong {
    color: #0284c7;
  }
  .mobile-state-grid .success strong {
    color: #15803d;
  }
  .mobile-state-grid .failed strong {
    color: #dc2626;
  }
  .mobile-state-grid .waiting strong {
    color: #b45309;
  }
  .mobile-project footer {
    display: flex;
    justify-content: space-between;
    align-items: center;
    font-size: 10px;
    color: #64748b;
    margin-top: 10px;
    gap: 6px;
  }
  .mobile-project footer strong {
    font-weight: 500;
    color: #475569;
  }
  .mobile-project footer .unknown-count {
    color: #b45309;
  }
  .mobile-state-grid button:focus-visible {
    outline: 2px solid #0284c7;
    outline-offset: 2px;
  }
}
</style>
