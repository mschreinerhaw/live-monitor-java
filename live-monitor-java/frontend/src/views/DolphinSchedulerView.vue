<template>
  <div class="page-container enterprise-page dolphin-product" :class="{ 'overview-screen': tab === 'overview' }">
    <header class="product-header">
      <div class="product-title">
        <div>
          <small v-if="tab === 'overview'" class="ds-overview-caption">监控总览</small><button v-else class="link-button ds-back" @click="selectTab('overview')">← 返回监控总览</button><div class="title-line">
            <h1 tabindex="-1">DolphinScheduler 运行监控中心</h1>
            <span :class="['freshness-pill', healthTone]"><i></i>{{ healthLabel }}</span>
          </div>
          <p>按集群与 ETL_Date 查看执行情况，先发现问题，再定位项目、实例与节点。</p>
        </div>
      </div>
      <div class="header-actions"><button v-if="tab === 'overview'" class="btn btn-ghost" @click="selectTab('project-list')">查看明细 →</button>
        <span
          v-if="runtimeCollection?.last_success"
          class="updated-at"
          :title="runtimeCollection.last_success"
          >数据更新 {{ timeOnly(runtimeCollection.last_success) }}</span
        ><button class="btn btn-primary" :disabled="busy || !cluster" @click="load">
          <IconSvg name="refresh" size="14" :class="{ spinning: busy }" />刷新
        </button>
        <div ref="managementEl" class="management">
          <button
            ref="managementTrigger"
            class="btn btn-ghost"
            :aria-expanded="managementOpen"
            aria-controls="ds-management"
            @click="managementOpen = !managementOpen"
          >
            <IconSvg name="settings" size="15" />管理<IconSvg name="chevron" size="12" />
          </button>
          <div
            v-if="managementOpen"
            id="ds-management"
            class="management-panel"
            @keydown.esc.stop.prevent="closeManagement"
          >
            <span class="menu-caption">连接与通知</span
            ><button @click="editConnection(null)"><IconSvg name="plus" size="15" />新增连接</button
            ><button :disabled="!selected" @click="editConnection(selected)">
              <IconSvg name="settings" size="15" />连接设置</button
            ><button :disabled="!selected" @click="editConnection(selected, 'alerts')">
              <IconSvg name="bell" size="15" />告警配置
            </button>
            <div class="menu-separator"></div>
            <button :disabled="!cluster || busy" @click="collectNow">
              <IconSvg name="refresh" size="15" />立即采集</button
            ><button :disabled="!selected" @click="openCollectionSettings">
              <IconSvg name="database" size="15" />采集配置
            </button>
          </div>
        </div>
      </div>
    </header>

    <div v-if="calendarError" class="product-notice notice-error" role="alert"><span>{{ calendarError }}；当前结果仍对应上一次已应用的范围。</span><button class="notice-link" :disabled="calendarBusy" @click="resolveCalendarDate">重试日期查询</button></div>
    <div v-if="error" class="product-notice notice-error" role="alert">
      <IconSvg name="info" size="17" />
      <div>
        <strong>当前请求未完成</strong>
        <p>{{ error }}<span v-if="dashboard">。当前保留上一次成功结果。</span></p>
      </div>
      <button class="notice-link" :disabled="busy" @click="load">重试</button>
    </div>
    <div v-if="notice" class="product-notice notice-info" role="status">
      <IconSvg name="check" size="16" /><span>{{ notice }}</span
      ><button class="dismiss-notice" aria-label="关闭提示" @click="notice = ''">
        <IconSvg name="x" size="14" />
      </button>
    </div>
    <div
      v-if="dashboard && dashboard.execution_coverage_reliable === false"
      class="product-notice notice-warning"
      role="status"
    >
      <IconSvg name="info" size="17" /><span
        >当前查询范围尚未完整采集。执行统计仅包含已采集实例，“未执行工作流”暂时显示未知。</span
      >
    </div>

    <div v-if="tab === 'overview' && connections.length" class="overview-scope"><label>监控集群 <select v-model="cluster" class="form-control" @change="changeCluster"><option v-for="c in connections" :key="c.id" :value="String(c.id)">{{ connectionLabel(c) }}</option></select></label><button class="btn btn-ghost cluster-manage" :disabled="!selected" @click="editConnection(selected)"><IconSvg name="settings" size="14" />集群管理</button><span>{{ scopeDescription }}</span><label class="overview-etl">ETL_Date <input v-model="calendarNatural" type="date" aria-label="概览 ETL_Date" @change="resolveCalendarDate" /><small v-if="calendarBusy" role="status">查询交易日历…</small><small v-else-if="calendarMeta" class="etl-date-source">{{ calendarMeta.source==='trading' ? '交易日历' : calendarMeta.source==='instance' ? '实例日期' : '默认日历' }} → {{ date }}</small></label><button v-if="project" class="link-button" :disabled="busy" @click="project = ''; changeProject()">查看全部项目</button><label class="refresh-toggle"><input v-model="autoRefresh" type="checkbox" />每 30 秒自动刷新</label></div>
    <section v-if="tab !== 'overview'" class="card scope-card" aria-label="监控范围">
      <div class="filters scope-filters">
        <label
          >监控集群<select v-model="cluster" class="form-control" @change="changeCluster">
            <option value="">请选择集群</option>
            <option v-for="c in connections" :key="c.id" :value="String(c.id)">
              {{ connectionLabel(c) }}
            </option>
          </select></label
        >
        <label
          >项目<select
            v-model="project"
            aria-label="项目"
            class="form-control"
            @change="changeProject"
          >
            <option value="">全部项目</option>
            <option v-for="p in projectOptions" :key="p.id" :value="p.id">
              {{ p.description || p.name }} · {{ p.id }}
            </option>
          </select></label
        >
        <label
          >执行时间范围<select
            v-model="period"
            aria-label="执行时间范围"
            class="form-control"
            @change="changePeriod"
          >
            <option value="today">今天</option>
            <option value="24h">最近24小时</option>
            <option value="all">已采集全部</option>
            <option value="custom">自定义</option>
          </select></label
        >
        <label
          >业务日期<input v-model="calendarNatural" type="date" aria-label="业务日期" class="form-control" @change="resolveCalendarDate" /><small v-if="calendarBusy">查询交易日历…</small><small v-else-if="calendarMeta">{{ calendarMeta.source==='trading'?'交易日历':calendarMeta.source==='instance'?'实例日期':'默认日历' }} → {{ date }}</small></label>
        <button
          class="btn btn-primary apply-button"
          :disabled="busy || !cluster"
          @click="applyFilters"
        >
          应用筛选
        </button>
      </div>
      <div v-if="period === 'custom'" class="custom-time">
        <label>开始时间<input v-model="start" type="datetime-local" class="form-control" /></label
        ><span>至</span
        ><label>结束时间<input v-model="end" type="datetime-local" class="form-control" /></label>
      </div>
      <div class="scope-footer">
        <div class="scope-context">
          <IconSvg name="table" size="13" /><span>{{ scopeDescription }}</span
          ><span v-if="filtersDirty" class="pending-filter" role="status"
            >筛选已更改，请应用筛选</span
          >
        </div>
        <label class="refresh-toggle"
          ><input v-model="autoRefresh" type="checkbox" />每 30 秒自动刷新</label
        >
      </div>
    </section>

    <nav v-if="tab !== 'overview'" class="tabs product-tabs" role="tablist" aria-label="集群监控页面">
      <button
        v-for="(t, index) in tabs"
        :id="'ds-tab-' + t.key"
        :key="t.key"
        role="tab"
        :aria-label="t.name"
        :aria-selected="tab === t.key"
        :aria-controls="'ds-panel-' + t.key"
        :tabindex="tab === t.key ? 0 : -1"
        :class="{ selected: tab === t.key }"
        @click="selectTab(t.key)"
        @keydown="moveTab($event, index)"
      >
        {{ t.name
        }}<span
          v-if="tabCount(t.key) != null"
          :class="{ 'count-alert': t.key === 'alerts' && tabCount(t.key) > 0 }"
          >{{ number(tabCount(t.key)) }}</span
        >
      </button>
    </nav>

    <div
      v-if="failures.length && tab !== 'overview'"
      class="product-notice notice-warning collection-notice"
      role="status"
    >
      <IconSvg name="info" size="17" />
      <div>
        <strong>部分数据延迟，正在保留最近成功结果</strong>
        <details>
          <summary>查看采集状态</summary>
          <p v-for="c in failures" :key="c.lane">
            {{ laneName(c.lane) }} · {{ c.error_message || '数据已过期' }} · 最后成功
            {{ c.last_success || '尚未成功' }}
          </p>
        </details>
      </div>
    </div>
    <div v-if="!dashboard && busy" class="card loading-state" role="status">
      <IconSvg name="refresh" size="24" class="spinning" /><strong>正在读取监控数据</strong>
      <p>项目执行情况与资源状态即将呈现</p>
    </div>
    <section v-else-if="!connections.length" class="card empty-state">
      <IconSvg name="server" size="32" />
      <h2>连接你的 DolphinScheduler 集群</h2>
      <p>配置 API 地址与访问 Token，开始查看项目执行与资源情况。</p>
      <button class="btn btn-primary" @click="editConnection(null)">
        <IconSvg name="plus" size="14" />新增连接
      </button>
    </section>

    <div
      :id="'ds-panel-' + tab"
      role="tabpanel"
      :aria-labelledby="'ds-tab-' + tab"
      class="monitor-panel"
    >
      <template v-if="tab === 'overview' && dashboard">
        <div class="section-context">
          <span
            >{{
              dashboard.project_count == null
                ? '项目待同步'
                : number(dashboard.project_count) + ' 个项目'
            }}<i>·</i>执行实例 <strong>{{ number(dashboard.workflow_statistics?.total) }}</strong
            ><i>·</i>任务定义 {{ number(dashboard.task_definitions) }}</span
          ><span>当前筛选范围</span>
        </div>
        <DolphinOverview :projects="dashboard.projects || []" :nodes="dashboard.nodes || []" :known="dashboard.project_count != null" :pending="busy || filtersDirty" :reliable="dashboard.execution_coverage_reliable === true" :fresh="collectionFresh(resourceCollection)" @definitions="openProject" @instances="openInstances" @projects="selectTab('project-list')" @resources="selectTab('resources')" @alerts="selectTab('alerts')" />
        <div class="execution-layout"><DolphinExecutionOverview :dashboard="dashboard" :metrics="metricCards" :pending="busy || filtersDirty" @metric="openMetric" /><div class="overview-side"><section class="ds-stat-group overview-assets"><h2>工作流资产 <small>静态配置</small></h2><div class="asset-metrics"><button class="asset-card" :disabled="busy || filtersDirty" @click="selectTab('project-list')"><span>项目数</span><strong>{{ number(dashboard.project_count) }}</strong></button><button class="asset-card" :disabled="busy || filtersDirty" @click="openMetric({key:'online'})"><span>工作流定义总量</span><strong>{{ number(dashboard.workflow_definitions) }}</strong></button><button class="asset-card" :disabled="busy || filtersDirty" @click="openMetric({key:'online'})"><span>上线工作流</span><strong>{{ number(dashboard.online_workflows) }}</strong></button><button class="asset-card" :disabled="busy || filtersDirty" @click="openMetric({key:'online'})" title="已启用的调度配置数量，不代表当日应执行数量"><span>启用调度</span><strong>{{ number(enabledSchedules) }}</strong></button></div></section>        <DolphinResourceSummary
          :nodes="dashboard.nodes || []"
          :fresh="collectionFresh(resourceCollection)"
          @details="selectTab('resources')"
        />
</div></div>
        <DolphinProjectCards :projects="dashboard.projects || []" :known="dashboard.project_count != null" :pending="busy || filtersDirty" :reliable="dashboard.execution_coverage_reliable === true" :scope-key="projectScopeKey" @definitions="openProject" @instances="openInstances" @projects="selectTab('project-list')" />

        <nav class="diagnosis-path" aria-label="定位排查路径"><strong>定位排查路径</strong><button :disabled="!selected" @click="editConnection(selected)">集群<small>连接与采集</small></button><span>→</span><button :disabled="busy || filtersDirty" @click="selectTab('project-list')">项目<small>定位异常项目</small></button><span>→</span><button :disabled="busy || filtersDirty" @click="kind='workflow_instance'; selectTab('instances')">工作流实例<small>查看当日执行</small></button><span>→</span><button :disabled="busy || filtersDirty" @click="kind='task_instance'; selectTab('instances')">任务实例<small>查看状态与日志</small></button><span>→</span><span class="diagnosis-log">日志<small>从任务实例进入</small></span></nav>

      </template>

        <details v-if="tab !== 'overview'" class="collection-details">
          <summary><IconSvg name="database" size="14" />采集状态</summary>
          <div class="table-wrap">
            <table class="table">
              <thead>
                <tr>
                  <th>采集内容</th>
                  <th>状态</th>
                  <th>最后成功</th>
                  <th>连续失败</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="c in primaryCollection" :key="c.lane">
                  <td>{{ laneName(c.lane) }}</td>
                  <td>
                    <span :class="['badge', collectionFresh(c) ? 'badge-up' : 'badge-unknown']">{{
                      collectionFresh(c) ? '正常' : '待更新'
                    }}</span>
                  </td>
                  <td>{{ c.last_success || '尚未成功' }}</td>
                  <td>{{ c.failures ?? '—' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </details>
      <template v-if="tab === 'project-list' && dashboard">
        <DolphinProjectTable
          v-model:filter="projectFocus"
          :projects="dashboard.projects || []"
          :known="dashboard.project_count != null"
          :pending="busy || filtersDirty"
          :scope-key="projectScopeKey"
          @definitions="openProject"
          @instances="openInstances"
        />
      </template>
      <section v-if="tab === 'projects'" class="card detail-section">
        <header class="detail-heading">
          <div>
            <h2>项目定义与权限</h2>
            <p>
              {{
                currentProject
                  ? projectLabel(currentProject)
                  : '查看已上线工作流、任务节点与项目授权'
              }}
            </p>
          </div>
          <button
            v-if="project"
            class="btn btn-ghost btn-sm"
            :disabled="busy || !currentProject"
            @click="editProject"
          >
            <IconSvg name="users" size="14" />配置责任人
          </button>
        </header>
        <div v-if="!project" class="empty-state">
          <IconSvg name="file" size="30" /><strong>选择项目，查看工作流定义</strong>
          <p>在上方项目筛选中选择一个项目，或从项目列表进入。</p>
          <button class="btn btn-ghost" @click="selectTab('project-list')">返回项目列表</button>
        </div>
        <template v-else
          ><div class="definition-facts">
            <span
              >已上线 <strong>{{ number(currentProject?.online_count) }}</strong></span
            ><span
              >已下线 <strong>{{ number(currentProject?.offline_count) }}</strong></span
            ><span
              >启用定时 <strong>{{ number(currentProject?.schedule_count) }}</strong></span
            ><span
              >任务定义 <strong>{{ number(currentProject?.task_definition_count) }}</strong></span
            >
          </div>
          <div class="detail-toolbar">
            <label
              >定义类型<select
                v-model="definitionKind"
                class="form-control"
                @change="loadDefinitions"
              >
                <option value="workflow_definition">工作流定义</option>
                <option value="task_definition">任务定义</option>
              </select></label
            ><button class="btn btn-ghost" @click="loadDefinitions">查询定义与授权</button>
          </div>
          <div class="table-wrap">
            <table class="table">
              <thead>
                <tr>
                  <th>{{ definitionKind === 'task_definition' ? '任务' : '工作流' }}</th>
                  <th>定义编码</th>
                  <th>发布状态</th>
                  <th>定时状态</th>
                  <th>任务节点</th>
                  <th>最近采集运行</th>
                  <th>所有者</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="d in definitionPager.items" :key="d.id">
                  <td>
                    <button class="link-button name-link" @click="openDefinition(d)">
                      {{ d.name }}
                    </button>
                  </td>
                  <td class="mono">{{ d.id }}</td>
                  <td>
                    <span
                      :class="['badge', d.releaseState === 'ONLINE' ? 'badge-up' : 'badge-unknown']"
                      >{{
                        d.releaseState === 'ONLINE'
                          ? '已上线'
                          : d.releaseState === 'OFFLINE'
                          ? '已下线'
                          : d.taskType || '未知'
                      }}</span
                    >
                  </td>
                  <td>
                    {{
                      d.scheduleReleaseState === 'ONLINE'
                        ? '已启用'
                        : d.scheduleReleaseState === 'OFFLINE'
                        ? '未启用'
                        : '未知'
                    }}
                  </td>
                  <td>{{ d.task_count ?? '—' }}</td>
                  <td>{{ d.last_collected_run || '未知' }}</td>
                  <td>{{ d.userName || '未知' }}</td>
                </tr>
                <tr v-if="!definitionPager.total">
                  <td colspan="7" class="table-empty">
                    {{ definitionLoading ? '正在读取定义…' : '暂无已采集定义' }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <PaginationControls
            v-model:page="definitionPager.page"
            v-model:page-size="definitionPager.pageSize"
            :total="definitionPager.total"
            label="工作流定义"
          />
          <div class="authorization-note">
            <IconSvg name="users" size="14" /><span
              >授权用户：{{
                authorizations.map((a) => a.userName).join('、') || '暂无已同步授权关系'
              }}</span
            ><span class="muted">所有者与业务责任人分别维护</span>
          </div></template
        >
      </section>

      <template v-if="tab === 'instances'">
        <section class="card detail-section">
          <header class="detail-heading">
            <div>
              <h2>任务运行明细</h2>
              <p>跟踪执行状态、业务日期与运行节点，定位失败原因。</p>
            </div>
            <span class="total-tag">{{ number(instances.total) }} 条记录</span>
          </header>
          <div class="detail-toolbar instance-toolbar">
            <label
              >记录类型<select v-model="kind" class="form-control" @change="queryInstances">
                <option value="workflow_instance">工作流实例</option>
                <option value="task_instance">任务实例</option>
              </select></label
            ><label
              >状态<select
                v-model="state"
                aria-label="状态"
                class="form-control"
                @change="queryInstances"
              >
                <option value="">全部状态</option>
                <option v-for="s in stateOptions" :key="s" :value="s">{{ stateLabel(s) }}</option>
              </select></label
            ><button class="btn btn-primary" @click="queryInstances">查询</button
            ><button
              v-if="state || workflow || task"
              class="btn btn-ghost"
              @click="resetInstanceFilters"
            >
              重置明细筛选
            </button>
            <details class="advanced-instance">
              <summary>更多筛选<IconSvg name="chevron" size="12" /></summary>
              <div>
                <label
                  >工作流编码<input
                    v-model="workflow"
                    class="form-control"
                    @change="queryInstances" /></label
                ><label v-if="kind === 'task_instance'"
                  >任务编码<input v-model="task" class="form-control" @change="queryInstances"
                /></label>
              </div>
            </details>
          </div>
          <div class="table-wrap">
            <table class="table instance-table">
              <thead>
                <tr>
                  <th>执行实例</th>
                  <th>业务日期</th>
                  <th>状态</th>
                  <th>开始 / 结束</th>
                  <th>耗时 / 重试</th>
                  <th>节点 / 用户</th>
                  <th>排查</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="r in instances.items" :key="r.id">
                  <td class="instance-name">
                    <span class="name-text" :title="r.name">{{ r.name }}</span
                    ><small class="mono">实例 {{ r.id }}</small
                    ><small
                      :title="
                        '工作流 ' +
                        r.definition_code +
                        (r.task_code ? ' / 任务 ' + r.task_code : '')
                      "
                      >{{ r.task_type || '工作流' }} · {{ r.task_code || r.definition_code }}</small
                    >
                  </td>
                  <td>
                    <span :class="{ muted: !r.etl_date }">{{ r.etl_date || '未知' }}</span
                    ><small>{{ dateSource(r.etl_date_source) }}</small>
                  </td>
                  <td>
                    <span :class="['execution-status', statusTone(r.state)]" :title="r.state"
                      ><i></i>{{ stateLabel(r.state) }}</span
                    >
                  </td>
                  <td class="time-cell">
                    <span>{{ r.start_time || '—' }}</span
                    ><small>{{
                      r.end_time || (r.state === 'RUNNING_EXECUTION' ? '执行中' : '—')
                    }}</small>
                  </td>
                  <td>
                    <span class="duration">{{ durationText(r) }}</span
                    ><small>重试 {{ r.retry_times ?? r.run_times ?? 0 }} 次</small>
                  </td>
                  <td class="host-cell">
                    <span :title="r.host">{{ r.host || '未知' }}</span
                    ><small>{{ r.executor || '执行用户未知' }}</small
                    ><small
                      v-for="n in (r.running_nodes || []).slice(0, 2)"
                      :key="n.id"
                      :title="n.name"
                      >{{ n.host || '未知节点' }} · {{ stateLabel(n.state) }}</small
                    ><small v-if="r.running_nodes?.length > 2"
                      >另有 {{ r.running_nodes.length - 2 }} 个活动节点</small
                    >
                  </td>
                  <td class="investigate-cell">
                    <button
                      v-if="kind === 'task_instance' && r.log_available"
                      class="btn btn-ghost btn-sm"
                      @click="showLog(r)"
                    >
                      <IconSvg name="file" size="13" />日志</button
                    ><button
                      v-else-if="kind === 'workflow_instance'"
                      class="btn btn-ghost btn-sm"
                      @click="showTasks(r)"
                    >
                      任务节点<IconSvg name="chevron-right" size="12" /></button
                    ><span v-else class="muted">—</span
                    ><small
                      v-if="r.failure_reason"
                      class="failure-reason"
                      :title="r.failure_reason"
                      >{{ r.failure_reason }}</small
                    >
                  </td>
                </tr>
                <tr v-if="!instances.items.length">
                  <td colspan="7">
                    <div class="empty-state small-empty">
                      <IconSvg name="search" size="27" /><strong>{{
                        instances.total == null
                          ? '执行记录尚未完成采集'
                          : '当前筛选范围内没有执行记录'
                      }}</strong>
                      <p>可调整项目、业务日期或执行时间范围。</p>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div class="instance-pagination">
            <span
              >{{
                instances.total == null
                  ? '数据待采集'
                  : '共 ' + number(instances.total) + ' 条记录'
              }}<i>·</i>每页 20 条</span
            >
            <div>
              <button
                class="btn btn-ghost btn-sm"
                :disabled="instances.page <= 1"
                @click="changeInstancePage(-1)"
              >
                <IconSvg name="chevron-left" size="13" />上一页</button
              ><span>第 {{ instances.page || 1 }} 页 · 共 {{ instances.total ?? '未知' }} 条</span
              ><button
                class="btn btn-ghost btn-sm"
                :disabled="!instances.total || instances.page * 20 >= instances.total"
                @click="changeInstancePage(1)"
              >
                下一页<IconSvg name="chevron-right" size="13" />
              </button>
            </div>
          </div>
        </section>
        <section class="card detail-section">
          <header class="detail-heading">
            <div>
              <h2>业务日期执行情况</h2>
              <p>按已采集任务汇总；同一业务日期的多次执行保留记录。</p>
            </div>
          </header>
          <div class="table-wrap">
            <table class="table">
              <thead>
                <tr>
                  <th>业务日期</th>
                  <th>任务实例</th>
                  <th>运行</th>
                  <th>成功</th>
                  <th>失败</th>
                  <th>等待</th>
                  <th>未成功</th>
                  <th>最新尝试完成率</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="d in dates" :key="d.etl_date">
                  <td>
                    <button
                      class="link-button"
                      :disabled="d.etl_date === 'UNKNOWN'"
                      @click="selectBusinessDate(d)"
                    >
                      {{ d.etl_date === 'UNKNOWN' ? '业务日期未知' : d.etl_date }}
                    </button>
                  </td>
                  <td>{{ number(d.total) }}</td>
                  <td>{{ number(d.running) }}</td>
                  <td class="text-success">{{ number(d.success) }}</td>
                  <td :class="{ 'text-danger': d.failed > 0 }">{{ number(d.failed) }}</td>
                  <td>{{ number(d.waiting) }}</td>
                  <td>{{ number(d.unfinished) }}</td>
                  <td>
                    {{
                      d.completion_rate == null
                        ? '未知'
                        : Number(d.completion_rate).toFixed(1) + '%'
                    }}
                  </td>
                </tr>
                <tr v-if="!dates.length">
                  <td colspan="8" class="table-empty">暂无业务日期汇总</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </template>

      <section v-if="tab === 'resources' && dashboard" class="card detail-section">
        <header class="detail-heading">
          <div>
            <h2>Master / Worker</h2>
            <p>节点在线状态与资源使用情况，主机内存和 JVM 堆分别展示。</p>
          </div>
          <span class="total-tag">{{ dashboard.nodes.length }} 个节点</span>
        </header>
        <div class="table-wrap">
          <table class="table resource-table">
            <thead>
              <tr>
                <th>角色 / 节点</th>
                <th>在线状态</th>
                <th>CPU</th>
                <th>主机内存</th>
                <th>JVM 堆 / GC</th>
                <th>可用磁盘 / 负载</th>
                <th>等待 / 执行线程</th>
                <th>历史</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="n in dashboard.nodes" :key="n.id">
                <td>
                  <span class="node-role"
                    ><IconSvg name="server" size="14" />{{
                      n.role === 'masters' ? 'Master' : 'Worker'
                    }}</span
                  ><small class="mono">{{ n.host }}:{{ n.port }}</small>
                </td>
                <td>
                  <span
                    :class="[
                      'execution-status',
                      n.online && collectionFresh(resourceCollection) ? 'is-success' : 'is-waiting',
                    ]"
                    ><i></i
                    >{{
                      collectionFresh(resourceCollection)
                        ? n.online
                          ? '在线'
                          : '离线 / 心跳过期'
                        : '数据过期'
                    }}</span
                  ><small>{{ n.heartbeat }}</small
                  ><small
                    v-if="Math.abs(n.source_clock_skew_seconds || 0) > 60"
                    class="text-warning"
                    :title="
                      '源时钟偏差 ' +
                      Number(n.source_clock_skew_seconds).toFixed(0) +
                      ' 秒，已校正在线判断'
                    "
                    >时钟存在偏差</small
                  >
                </td>
                <td>
                  <span class="resource-number">{{ pct(n.cpu_percent) }}</span>
                  <div class="metric-track" aria-hidden="true">
                    <span
                      :class="{ high: n.cpu_percent >= 85 }"
                      :style="{ width: Math.min(100, n.cpu_percent || 0) + '%' }"
                    ></span>
                  </div>
                </td>
                <td>
                  <span class="resource-number">{{ pct(n.host_memory_percent) }}</span>
                  <div class="metric-track" aria-hidden="true">
                    <span
                      :class="{ high: n.host_memory_percent >= 85 }"
                      :style="{ width: Math.min(100, n.host_memory_percent || 0) + '%' }"
                    ></span>
                  </div>
                </td>
                <td>
                  <span>{{ n.jvm_available ? pct(n.heap_committed_percent) : '不可用' }}</span
                  ><small v-if="n.jvm_available"
                    >{{ mb(n.heap_used_bytes) }} / {{ mb(n.heap_committed_bytes) }} MB</small
                  ><small v-if="n.heap_max_bytes"
                    >最大堆 {{ mb(n.heap_max_bytes) }} MB · {{ pct(n.heap_max_percent) }}</small
                  ><small v-if="n.jvm_available">GC 累计 {{ n.gc_time_seconds }} s</small
                  ><small v-else>{{ n.jvm_unavailable_reason }}</small>
                </td>
                <td>
                  {{ n.disk_available_gb ?? '未知' }} GB<small
                    >负载 {{ n.load_average ?? '未知' }}</small
                  >
                </td>
                <td>
                  {{ n.waiting_tasks ?? '未知' }} / {{ n.execution_threads ?? '未知'
                  }}<small>关联运行 {{ n.running_instances ?? '未知' }}</small>
                </td>
                <td>
                  <button class="btn btn-ghost btn-sm" @click="showHistory(n)">
                    趋势<IconSvg name="chevron-right" size="12" />
                  </button>
                </td>
              </tr>
              <tr v-if="!dashboard.nodes.length">
                <td colspan="8" class="table-empty">节点资源尚未采集</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="resource-footnote">
          <IconSvg name="info" size="13" />堆比例以已提交堆为分母，最大堆单独列示；GC 为累计耗时。
        </div>
        <div class="database-status">
          <span class="database-label"><IconSvg name="database" size="15" />元数据库</span
          ><span v-for="d in dashboard.databases" :key="d.id"
            >{{ d.dbType
            }}<span :class="['badge', d.state === 'YES' ? 'badge-up' : 'badge-unknown']">{{
              d.state === 'YES' ? '连接正常' : d.state || '未知'
            }}</span
            ><span class="muted"
              >活跃连接 {{ d.threadsRunningConnections ?? '未知' }} /
              {{ d.maxConnections ?? '未知' }}</span
            ></span
          ><span v-if="!dashboard.databases.length" class="muted">暂无采集数据</span>
        </div>
      </section>

      <template v-if="tab === 'alerts'">
        <section class="card detail-section">
          <header class="detail-heading">
            <div>
              <h2>项目告警</h2>
              <p>优先查看尚未恢复的异常，通知发送结果可追踪。</p>
            </div>
            <router-link class="btn btn-ghost btn-sm" :to="'/alerts/tasks?service_id=' + cluster"
              >发送任务与重试记录<IconSvg name="chevron-right" size="13"
            /></router-link>
          </header>
          <div class="detail-toolbar">
            <label
              >事件状态<select v-model="incidentFilter" class="form-control" aria-label="事件状态">
                <option value="active">未恢复</option>
                <option value="all">全部事件</option>
                <option value="recovered">已恢复</option>
                <option value="aggregated">聚合抑制</option>
              </select></label
            ><button
              class="btn btn-ghost"
              :disabled="!project || !currentProject"
              @click="editProject"
            >
              <IconSvg name="users" size="14" />配置所选项目责任人与告警组</button
            ><button class="btn btn-primary" @click="editingRule = true">
              <IconSvg name="plus" size="14" />新增 / 覆盖规则
            </button>
          </div>
          <div class="table-wrap">
            <table class="table incident-table">
              <thead>
                <tr>
                  <th>项目</th>
                  <th>异常类型</th>
                  <th>说明</th>
                  <th>状态</th>
                  <th>发生 / 恢复</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="i in incidentPager.items" :key="i.incident_key">
                  <td>
                    {{ incidentProject(i.project_code)
                    }}<small class="mono">{{ i.project_code }}</small>
                  </td>
                  <td>{{ incidentCategory(i.category) }}</td>
                  <td>
                    <p class="incident-message" :title="i.message">{{ i.message }}</p>
                    <button class="link-button incident-detail-link" @click="incidentDetail = i">
                      查看详情
                    </button>
                  </td>
                  <td>
                    <span
                      :class="[
                        'badge',
                        Number(i.active) === 1
                          ? 'badge-down'
                          : Number(i.active) === 0
                          ? 'badge-up'
                          : 'badge-unknown',
                      ]"
                      >{{
                        Number(i.active) === 2
                          ? '聚合抑制'
                          : Number(i.active) === 1
                          ? '未恢复'
                          : '已恢复'
                      }}</span
                    >
                  </td>
                  <td class="time-cell">
                    {{ i.opened_at }}<small>{{ i.resolved_at || '—' }}</small>
                  </td>
                </tr>
                <tr v-if="!incidentPager.total">
                  <td colspan="5" class="table-empty">
                    {{
                      incidentFilter === 'active'
                        ? '当前范围没有未恢复告警'
                        : '当前范围没有匹配事件'
                    }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <PaginationControls
            v-model:page="incidentPager.page"
            v-model:page-size="incidentPager.pageSize"
            :total="incidentPager.total"
            label="告警事件"
          />
        </section>
        <section class="card detail-section">
          <header class="detail-heading">
            <div>
              <h2>已配置规则</h2>
              <p>任务、工作流、项目、平台依次覆盖。未配置责任人或接收组时，通知默认运维组。</p>
            </div>
          </header>
          <div class="table-wrap">
            <table class="table">
              <thead>
                <tr>
                  <th>范围</th>
                  <th>规则类型</th>
                  <th>阈值 / 日期</th>
                  <th>状态</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="r in rules" :key="r.scope_type + ':' + r.scope_id + ':' + r.rule_type">
                  <td>{{ scopeType(r.scope_type) }} · {{ r.scope_id }}</td>
                  <td>{{ incidentCategory(r.rule_type) }}</td>
                  <td>{{ r.threshold_value ?? r.business_date }} {{ r.deadline }}</td>
                  <td>
                    <span :class="['badge', r.enabled ? 'badge-up' : 'badge-unknown']">{{
                      r.enabled ? '已启用' : '已停用'
                    }}</span>
                  </td>
                </tr>
                <tr v-if="!rules.length">
                  <td colspan="4" class="table-empty">未配置覆盖规则，使用平台默认策略</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </template>
      <template v-if="tab === 'settings' && selected">
        <section class="card detail-section settings-section">
          <header class="detail-heading">
            <div>
              <h2>集群采集与通知</h2>
              <p>调整采样频率、业务日期识别与通知接收范围。</p>
            </div>
          </header>
          <form @submit.prevent="saveSettings">
            <div class="filters">
              <label
                >接口配置档<select v-model="settings.api_profile" class="form-control">
                  <option value="generic">通用 REST（明确日期）</option>
                  <option value="ld-2.0.8">已核验 ld-2.0.8</option>
                </select></label
              ><label class="setting-toggle"
                ><input
                  type="checkbox"
                  v-model="settings.resolve_verified_system_dates"
                />依据已核验逻辑调度时间解析业务日期</label
              ><label class="setting-toggle"
                ><input type="checkbox" v-model="settings.enterprise_enabled" />启用周期采集</label
              ><label class="setting-toggle"
                ><input
                  type="checkbox"
                  v-model="settings.enterprise_alerts_enabled"
                />启用项目与集群告警</label
              ><label class="setting-toggle"
                ><input type="checkbox" v-model="settings.collect_jvm" />通过已有 SSH 映射采集
                JVM</label
              ><label
                >定义同步（秒）<input
                  class="form-control"
                  type="number"
                  min="15"
                  max="86400"
                  v-model.number="settings.inventory_interval_seconds" /></label
              ><label
                >用户同步（秒）<input
                  class="form-control"
                  type="number"
                  min="15"
                  max="86400"
                  v-model.number="settings.users_interval_seconds" /></label
              ><label
                >运行采集（秒）<input
                  class="form-control"
                  type="number"
                  min="15"
                  max="86400"
                  v-model.number="settings.runtime_interval_seconds" /></label
              ><label
                >资源采样（秒）<input
                  class="form-control"
                  type="number"
                  min="10"
                  max="86400"
                  v-model.number="settings.resources_interval_seconds" /></label
              ><label
                >接口超时（秒）<input
                  class="form-control"
                  type="number"
                  min="1"
                  max="60"
                  v-model.number="settings.api_timeout_seconds" /></label
              ><label
                >失败重试（次）<input
                  class="form-control"
                  type="number"
                  min="0"
                  max="3"
                  v-model.number="settings.api_retries" /></label
              ><label
                >首次实例窗口（小时）<input
                  class="form-control"
                  type="number"
                  min="1"
                  max="744"
                  v-model.number="settings.initial_window_hours" /></label
              ><label
                >心跳过期（秒）<input
                  class="form-control"
                  type="number"
                  min="30"
                  max="3600"
                  v-model.number="settings.heartbeat_timeout_seconds" /></label
              ><label
                >保留天数<input
                  class="form-control"
                  type="number"
                  min="1"
                  max="3650"
                  v-model.number="settings.retention_days" /></label
              ><label
                >业务日期参数名（逗号分隔）<input
                  class="form-control"
                  v-model="settings.etl_date_keys"
                  placeholder="ETL_Date,dataDate,RQ,@rq" /></label
              >
              <fieldset class="default-ops-groups">
                <legend>默认运维告警组（可多选）</legend>
                <div class="group-options">
                  <label v-for="g in groups" :key="g.id" :class="['group-option', {selected:settings.default_ops_group_ids?.includes(g.id)}]">
                    <input v-model="settings.default_ops_group_ids" type="checkbox" :value="g.id">
                    <span>{{ g.group_name }}{{ g.enabled===false?'（已禁用）':'' }}</span>
                  </label>
                </div>
                <p v-if="!groups.length" class="form-hint">暂无可选告警组，请先在告警中心配置。</p>
              </fieldset>
            </div>
            <p class="form-hint">
              现场 ld-2.0.8 已验证。JVM 需要已维护的 SSH 主机凭据及
              jstat；缺失指标保留未知。生产通知会发往所选既有渠道。
            </p>
            <div class="page-actions">
              <button class="btn btn-primary" :disabled="busy">保存配置</button
              ><button type="button" class="btn btn-ghost" @click="testConnection">
                只读连接测试
              </button>
            </div>
          </form>
        </section>
      </template>
    </div>
    <DetailDrawer
      :open="!!taskInstance"
      id="ds-tasks-title"
      title="工作流任务节点"
      @close="taskInstance = null"
      ><template v-if="taskInstance"
        ><h3>{{ taskInstance.name }}</h3>
        <p v-if="taskError" role="alert">{{ taskError }}</p>
        <p v-if="taskLoading">正在读取任务节点…</p>
        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>任务</th>
                <th>类型</th>
                <th>ETL_Date</th>
                <th>节点 / 执行用户 / 重试</th>
                <th>状态</th>
                <th>开始 / 结束</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="task in taskPager.items" :key="task.id">
                <td>
                  {{ task.name }}<small>{{ task.id }}</small>
                </td>
                <td>{{ task.task_type }}</td>
                <td>{{ task.etl_date || '未提供' }}</td>
                <td>
                  {{ task.host || '—' }}<br />{{ task.executor || '—' }} /
                  {{ task.retry_times || '0' }}
                </td>
                <td>{{ stateLabel(task.state) }}</td>
                <td>{{ task.start_time }}<br />{{ task.end_time }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-if="!taskLoading && !tasks.length && !taskError">该实例暂无任务节点。</p>
        <PaginationControls
          v-model:page="taskPager.page"
          v-model:page-size="taskPager.pageSize"
          :total="taskPager.total"
          label="任务节点" /></template
    ></DetailDrawer>
    <DolphinConnectionDialog
      :open="connectionOpen"
      :connection="connectionForm"
      :groups="groups"
      :mode="connectionMode"
      @close="connectionOpen = false"
      @saved="connectionSaved"
    />
    <DetailDrawer
      :open="!!incidentDetail"
      id="ds-incident-details"
      title="告警详情"
      @close="incidentDetail = null"
      ><template v-if="incidentDetail"
        ><dl class="incident-facts">
          <div>
            <dt>项目</dt>
            <dd>{{ incidentProject(incidentDetail.project_code) }}</dd>
          </div>
          <div>
            <dt>异常类型</dt>
            <dd>{{ incidentCategory(incidentDetail.category) }}</dd>
          </div>
          <div>
            <dt>发生时间</dt>
            <dd>{{ incidentDetail.opened_at || '未提供' }}</dd>
          </div>
          <div>
            <dt>恢复时间</dt>
            <dd>{{ incidentDetail.resolved_at || '尚未恢复' }}</dd>
          </div>
        </dl>
        <h3 class="incident-detail-title">异常说明</h3>
        <p class="incident-full-message">{{ incidentDetail.message }}</p>
        <router-link class="btn btn-ghost" :to="'/alerts/tasks?service_id=' + cluster"
          >查看发送任务与重试记录</router-link
        ></template
      ></DetailDrawer
    >
    <DetailDrawer :open="editingProject" title="项目责任人与告警组" @close="editingProject = false"
      ><form @submit.prevent="saveProject">
        <p>
          项目 {{ project }} · 所有者 {{ currentProject?.owner || '未知' }}，与业务责任人分别保存。
        </p>
        <label class="form-group"
          >主要责任人<input
            v-model="projectForm.primary_owner"
            class="form-control"
            list="ds-users"
            maxlength="255" /></label
        ><label class="form-group"
          >备用责任人<input
            v-model="projectForm.backup_owner"
            class="form-control"
            list="ds-users"
            maxlength="255" /></label
        ><datalist id="ds-users">
          <option v-for="u in users" :key="u.id" :value="u.userName" /></datalist
        ><label class="form-group"
          >接收告警组<select multiple v-model="projectForm.group_ids" class="form-control">
            <option v-for="g in groups" :key="g.id" :value="g.id">{{ g.group_name }}</option>
          </select></label
        ><label><input v-model="projectForm.alert_enabled" type="checkbox" />启用项目告警</label>
        <p class="form-hint">
          告警组内已有渠道定义实际接收对象，请确保组内接收人包含责任人。仅设置姓名不会新增邮件或短信接收人。
        </p>
        <button class="btn btn-primary">保存</button>
      </form></DetailDrawer
    >
    <DetailDrawer :open="editingRule" title="告警规则覆盖" @close="editingRule = false"
      ><form @submit.prevent="saveRule">
        <label class="form-group"
          >范围<select v-model="ruleForm.scope_type" class="form-control">
            <option value="platform">平台</option>
            <option value="project">项目</option>
            <option value="workflow">工作流</option>
            <option value="task">任务</option>
          </select></label
        ><label class="form-group"
          >范围编码<input
            v-model="ruleForm.scope_id"
            class="form-control"
            required
            placeholder="平台填 *，其余填写稳定编码" /></label
        ><label class="form-group"
          >规则类型<select v-model="ruleForm.rule_type" class="form-control">
            <option v-for="r in ruleTypes" :key="r" :value="r">{{ r }}</option>
          </select></label
        ><label class="form-group"
          >阈值<input
            v-model.number="ruleForm.threshold_value"
            type="number"
            min="0"
            class="form-control"
          /><small>超时单位秒，磁盘单位 GB，资源使用率单位 %，积压单位任务数</small></label
        ><template v-if="ruleForm.rule_type === 'ETL_DEADLINE'"
          ><label class="form-group"
            >目标业务日期<input
              type="date"
              required
              v-model="ruleForm.business_date"
              class="form-control" /></label
          ><label class="form-group"
            >截止时间<input
              type="datetime-local"
              required
              v-model="ruleForm.deadline"
              class="form-control" /></label></template
        ><label
          ><input v-model="ruleForm.enabled" type="checkbox" />启用规则（取消可覆盖停用）</label
        ><button class="btn btn-primary">保存规则</button>
      </form></DetailDrawer
    >
    <DetailDrawer :open="!!logTask" title="任务日志" @close="logTask = null"
      ><template v-if="logTask"
        ><p>实例 {{ logTask.id }}，每次最多 100 行</p>
        <p v-if="logError" class="inline-error" role="alert">{{ logError }}</p>
        <pre class="task-log">{{
          logLoading
            ? '正在读取日志…'
            : logError
            ? '日志读取未完成'
            : logContent || '该范围暂无日志'
        }}</pre>
        <div class="page-actions">
          <button class="btn btn-ghost" :disabled="logOffset === 0" @click="changeLogOffset(-100)">
            上一段</button
          ><button class="btn btn-ghost" @click="changeLogOffset(100)">下一段</button>
        </div></template
      ></DetailDrawer
    >
    <DetailDrawer :open="!!historyNode" title="节点资源趋势" @close="historyNode = null"
      ><template v-if="historyNode"
        ><p>{{ historyNode.host }} · {{ historyNode.role }}</p>
        <p v-if="historyError" class="inline-error" role="alert">{{ historyError }}</p>
        <p v-else-if="historyEmpty" class="form-hint">当前范围暂无采样</p>
        <select
          aria-label="趋势时间范围"
          v-model.number="historyHours"
          @change="loadHistory"
          class="form-control"
        >
          <option :value="1">近 1 小时</option>
          <option :value="24">近 24 小时</option>
          <option :value="168">近 7 天</option>
        </select>
        <div ref="chartEl" style="height: 320px; width: 100%"></div>
        <p class="form-hint">
          CPU、主机内存与已提交堆使用率分别绘制，缺失采样断开显示；48 小时以前按小时均值归档。
        </p></template
      ></DetailDrawer
    >

  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import IconSvg from '../components/IconSvg.vue'
import DolphinProjectCards from '../components/DolphinProjectCards.vue'
import DolphinExecutionOverview from '../components/DolphinExecutionOverview.vue'
import DolphinOverview from '../components/DolphinOverview.vue'
import DolphinProjectTable from '../components/DolphinProjectTable.vue'
import DolphinResourceSummary from '../components/DolphinResourceSummary.vue'
import DolphinConnectionDialog from '../components/DolphinConnectionDialog.vue'
import * as echarts from 'echarts'
import { api } from '../api'
import DetailDrawer from '../components/DetailDrawer.vue'
import PaginationControls from '../components/PaginationControls.vue'
import { usePagination } from '../composables/usePagination'
const router = useRouter()
const calendarNatural=ref(''),calendarMeta=ref(null),calendarError=ref(''),calendarBusy=ref(false)
let calendarController,calendarVersion=0
function clearCalendarRequest(){calendarVersion++;calendarController?.abort();calendarBusy.value=false;calendarError.value='';calendarMeta.value=null}
async function resolveCalendarDate(){
 const natural=calendarNatural.value,version=++calendarVersion;calendarController?.abort();calendarError.value='';calendarMeta.value=null
 if(!natural){calendarBusy.value=false;date.value='';await applyFilters();return}
 const abort=new AbortController();calendarController=abort;calendarBusy.value=true;const timeout=setTimeout(()=>abort.abort(),15000)
 try{const response=await fetch(`/api/calendars/trading/resolve?natural_date=${natural.replaceAll('-','')}`,{credentials:'same-origin',signal:abort.signal});const result=await response.json();if(version!==calendarVersion)return;if(!response.ok)throw Error(result.message||result.error||'交易日历查询失败');
 if(result.status==='RESOLVED' && /^\d{4}-\d{2}-\d{2}$/.test(result.etl_date || '')){date.value=result.etl_date;calendarMeta.value={source:'trading'}}
 else if(result.status==='MISSING'){date.value=natural;calendarMeta.value={source:'default'}}
 else throw Error(result.status==='AMBIGUOUS'?'交易日历存在多条映射，请先维护为唯一业务日期':'交易日历映射无效，请检查日历数据');
 calendarBusy.value=false;await applyFilters()
 }catch(e){if(version===calendarVersion)calendarError.value=e.name==='AbortError'?'交易日历查询超时，请重试':e.message}finally{clearTimeout(timeout);if(version===calendarVersion)calendarBusy.value=false}
}
onBeforeUnmount(()=>{calendarVersion++;calendarController?.abort()})
const route = useRoute(),
  connectionOpen = ref(false),
  connectionForm = ref(null),
  connectionMode = ref('connection'),
  projectOptions = ref([]),
  period = ref('today')
const logError = ref(''),
  logLoading = ref(false),
  historyError = ref(''),
  historyEmpty = ref(false)
const incidentDetail = ref(null)
const managementOpen = ref(false),
  managementEl = ref(null),
  managementTrigger = ref(null),
  autoRefresh = ref(true),
  projectFocus = ref('all'),
  incidentFilter = ref('active'),
  appliedSignature = ref(''),
  appliedScope = ref(null),
  definitionLoading = ref(false)
const connections = ref([]),
  cluster = ref(''),
  project = ref(''),
  date = ref(''),
  tab = ref(route.query.view === 'details' ? (['project-list','projects','instances','resources','alerts','settings'].includes(route.query.tab) ? route.query.tab : 'project-list') : 'overview'),
  dashboard = ref(null),
  busy = ref(false),
  error = ref(''),
  notice = ref(''),
  groups = ref([]),
  users = ref([]),
  definitions = ref([]),
  authorizations = ref([]),
  incidents = ref([]),
  rules = ref([]),
  dates = ref([]),
  instances = ref({ items: [], page: 1, total: null })
const definitionKind = ref('workflow_definition'),
  kind = ref('workflow_instance'),
  workflow = ref(''),
  task = ref(''),
  state = ref(''),
  start = ref(''),
  end = ref(''),
  instancePage = ref(1),
  settings = ref({}),
  editingProject = ref(false),
  projectForm = ref({}),
  editingRule = ref(false),
  ruleForm = ref({
    scope_type: 'project',
    scope_id: '',
    rule_type: 'WORKFLOW_FAILURE',
    threshold_value: 1,
    enabled: true,
  }),
  logTask = ref(null),
  logContent = ref(''),
  logOffset = ref(0),
  historyNode = ref(null),
  historyHours = ref(24),
  chartEl = ref(null)
const tabs = [
  { key: 'overview', name: '监控总览' },
  { key: 'project-list', name: '项目明细' },
  { key: 'projects', name: '工作流定义' },
  { key: 'instances', name: '任务运行明细' },
  { key: 'resources', name: '集群资源' },
  { key: 'alerts', name: '项目告警' },
  { key: 'settings', name: '采集配置' },
]
watch(tab, key => {
  const query = { ...route.query }
  if (key === 'overview') { delete query.view; delete query.tab }
  else { query.view = 'details'; query.tab = key }
  if (route.query.view !== query.view || route.query.tab !== query.tab) router.push({ path: route.path, query })
  nextTick(() => { window.scrollTo({ top: 0, behavior: 'instant' }); if (key === 'overview') document.querySelector('.overview-screen h1')?.focus() })
})
watch(() => [route.query.view, route.query.tab], ([view, target]) => {
  const key = view === 'details' ? (tabs.some(t => t.key === target && t.key !== 'overview') ? target : 'project-list') : 'overview'
  if (tab.value !== key) selectTab(key)
})
const stateOptions = [
  'success',
  'waiting',
  'READY_PAUSE',
  'READY_STOP',
  'RUNNING_EXECUTION',
  'SUBMITTED_SUCCESS',
  'SUCCESS',
  'FORCED_SUCCESS',
  'FAILURE',
  'STOP',
  'KILL',
  'PAUSE',
  'DELAY_EXECUTION',
  'SERIAL_WAIT',
  'WAITING_THREAD',
  'WAITING_DEPEND',
  'NEED_FAULT_TOLERANCE',
]
const stateNames = {
  success: '成功（含强制成功）',
  waiting: '全部待执行',
  SUCCESS: '成功',
  FORCED_SUCCESS: '强制成功',
  FAILURE: '失败',
  STOP: '已停止',
  KILL: '已终止',
  PAUSE: '已暂停',
  RUNNING_EXECUTION: '运行中',
  SUBMITTED_SUCCESS: '已提交',
  DELAY_EXECUTION: '延迟执行',
  SERIAL_WAIT: '串行等待',
  READY_PAUSE: '准备暂停',
  READY_STOP: '准备停止',
  WAITING_THREAD: '等待线程',
  WAITING_DEPEND: '等待依赖',
  NEED_FAULT_TOLERANCE: '待容错',
}
const stateLabel = (s) => stateNames[s] || s || '未知'
const ruleTypes = [
  'WORKFLOW_FAILURE',
  'TASK_FAILURE',
  'TIMEOUT',
  'ETL_DEADLINE',
  'BACKLOG',
  'CPU_HIGH',
  'MEMORY_HIGH',
  'JVM_HIGH',
  'DISK_LOW',
]
const selected = computed(() => connections.value.find((c) => String(c.id) === cluster.value))
const currentProject = computed(() => dashboard.value?.projects.find((p) => p.id === project.value))
function connectionLabel(connection) {
  try {
    const config = typeof connection.config_json === 'string' ? JSON.parse(connection.config_json) : connection.config_json
    const host = new URL(config?.base_url).host
    return connection.service_name?.includes(host) ? connection.service_name : `${connection.service_name || 'DolphinScheduler'} · ${host}`
  } catch { return connection.service_name || 'DolphinScheduler' }
}
const enabledSchedules = computed(() => {
  const rows = dashboard.value?.projects
  if (!rows?.length || rows.some(p => p.schedule_count == null || p.unknown_schedule_count == null || p.unknown_schedule_count > 0)) return null
  return rows.reduce((total, p) => total + Number(p.schedule_count), 0)
})
const metricCards = computed(() => [
  {
    key: 'online',
    label: '上线工作流',
    value: dashboard.value?.online_workflows,
    tone: 'neutral',
    icon: 'file',
    hint: '查看工作流定义',
  },
  {
    key: 'running',
    label: '运行中实例',
    value: dashboard.value?.workflow_statistics?.running,
    tone: 'running',
    icon: 'activity',
    hint: '跟踪当前执行',
    state: 'RUNNING_EXECUTION',
  },
  {
    key: 'success',
    label: '已成功实例',
    value: dashboard.value?.workflow_statistics?.success,
    tone: 'success',
    icon: 'check',
    hint: '包含强制成功',
    state: 'success',
  },
  {
    key: 'failed',
    label: '已失败实例',
    value: dashboard.value?.workflow_statistics?.failed,
    tone: 'failed',
    icon: 'info',
    hint: '查看失败原因',
    state: 'FAILURE',
  },
  {
    key: 'waiting',
    label: '待执行实例',
    value: dashboard.value?.workflow_statistics?.waiting,
    tone: 'waiting',
    icon: 'play-circle',
    hint: '等待、延迟及准备状态',
    state: 'waiting',
  },
  {
    key: 'unstarted',
    label: '未执行工作流',
    value: dashboard.value?.not_executed_workflows,
    tone: 'neutral',
    icon: 'table',
    hint: '定位尚未执行的项目',
  },
])
const filteredIncidents = computed(() =>
  incidents.value.filter(
    (i) =>
      (!project.value || !i.project_code || i.project_code === project.value) &&
      (incidentFilter.value === 'all' ||
        Number(i.active) === { active: 1, recovered: 0, aggregated: 2 }[incidentFilter.value])
  )
)
const definitionPager = usePagination(definitions, [project]),
  incidentPager = usePagination(filteredIncidents, [project, incidentFilter])
const collectionFresh = (c) =>
  c?.fresh !== undefined
    ? c.fresh
    : !!c?.last_success &&
      !c.failures &&
      Date.now() - new Date(c.last_success.replace(' ', 'T') + '+08:00').getTime() <
        (Number(settings.value[c.lane.split(':')[0] + '_interval_seconds']) || 300) * 3000
const failures = computed(() =>
    (dashboard.value?.collection || []).filter(
      (c) => c.failures || (c.last_success && !collectionFresh(c))
    )
  ),
  primaryCollection = computed(() =>
    ['inventory', 'runtime', 'resources', 'users'].map(
      (lane) => (dashboard.value?.collection || []).find((c) => c.lane === lane) || { lane }
    )
  ),
  resourceCollection = computed(() => primaryCollection.value.find((c) => c.lane === 'resources'))
const pct = (v) => (v == null ? '未知' : Number(v).toFixed(1) + '%'),
  mb = (v) => (v == null ? '未知' : (Number(v) / 1048576).toFixed(0))
let generation = 0,
  queryGeneration = 0,
  summaryGeneration = 0,
  definitionGeneration = 0,
  definitionScope = '',
  timer,
  chart
async function guarded(fn) {
  error.value = ''
  try {
    await fn()
  } catch (e) {
    error.value = e.message
  }
}
async function call(path, params = {}, method = 'GET', body) {
  return api.dolphinMonitor(cluster.value, path, params, method, body)
}
async function load() {
  if (!cluster.value || calendarBusy.value || calendarError.value) return
  const id = cluster.value,
    n = generation,
    q = ++summaryGeneration,
    params = summaryParams(),
    signature = JSON.stringify(params),
    scope = { ...params, period: period.value }
  busy.value = true
  error.value = ''
  try {
    const [data, events, configured] = await Promise.all([
      call('/summary', params),
      call('/incidents'),
      call('/rules'),
    ])
    if (
      id !== cluster.value ||
      n !== generation ||
      q !== summaryGeneration ||
      signature !== JSON.stringify(summaryParams())
    )
      return
    dashboard.value = data
    appliedSignature.value = signature
    appliedScope.value = scope
    if (data.project_options) projectOptions.value = data.project_options
    incidents.value = events
    rules.value = configured
    if (tab.value === 'instances') await queryInstances(false)
  } catch (e) {
    if (id === cluster.value && q === summaryGeneration) error.value = e.message
  } finally {
    if (q === summaryGeneration) busy.value = false
  }
}
async function changeCluster() {
  clearCalendarRequest(); calendarNatural.value=''
  ++generation
  appliedSignature.value = ''
  appliedScope.value = null
  projectFocus.value = 'all'
  dashboard.value = null
  project.value = ''
  definitions.value = []
  instances.value = { items: [], page: 1, total: null }
  date.value = ''
  workflow.value = ''
  task.value = ''
  error.value = ''
  try {
    const config = JSON.parse(selected.value?.config_json || '{}')
    settings.value = {
      enterprise_enabled: false,
      enterprise_alerts_enabled: false,
      collect_jvm: false,
      api_profile: 'generic',
      resolve_verified_system_dates: false,
      inventory_interval_seconds: 300,
      users_interval_seconds: 300,
      runtime_interval_seconds: 30,
      resources_interval_seconds: 15,
      api_timeout_seconds: 15,
      api_retries: 1,
      initial_window_hours: 24,
      heartbeat_timeout_seconds: 120,
      retention_days: 90,
      etl_date_keys: 'ETL_Date,etl_date,etlDate,dataDate',
      default_ops_group_ids: [],
      ...config,
    }
  } catch {
    settings.value = {}
  }
  updateRange()
  await guarded(async () => {
    ;[users.value, projectOptions.value] = await Promise.all([call('/users'), call('/projects')])
  })
  await load()
}
const tasks = ref([]),
  taskInstance = ref(null),
  taskLoading = ref(false),
  taskError = ref(''),
  taskPager = usePagination(tasks, [taskInstance])
async function showTasks(row) {
  taskInstance.value = row
  tasks.value = []
  taskError.value = ''
  taskLoading.value = true
  try {
    const rows = await api.dolphinTasks(cluster.value, row.project_code, row.id)
    if (taskInstance.value === row) tasks.value = rows
  } catch (e) {
    if (taskInstance.value === row) taskError.value = e.message
  } finally {
    if (taskInstance.value === row) taskLoading.value = false
  }
}

function textTime(d) {
  return d.toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai', hour12: false }).replace(' ', 'T')
}
function updateRange() {
  const now = new Date()
  if (period.value === 'today') {
    start.value = textTime(now).slice(0, 10) + 'T00:00:00'
    end.value = ''
  } else if (period.value === '24h') {
    start.value = textTime(new Date(now.getTime() - 86400000))
    end.value = ''
  } else if (period.value === 'all') {
    start.value = ''
    end.value = ''
  }
}
function summaryParams() {
  return {
    project: project.value,
    etl_date: date.value,
    start: start.value ? (start.value.length === 16 ? start.value + ':00' : start.value) : '',
    end: end.value ? (end.value.length === 16 ? end.value + ':59' : end.value) : '',
  }
}
async function applyFilters() {
  if (calendarBusy.value || calendarError.value) return
  instancePage.value = 1
  await load()
}
async function changePeriod() {
  updateRange()
  await applyFilters()
}
function openInstances(p, nextState = '') {
  project.value = p.id
  workflow.value = ''
  task.value = ''
  kind.value = 'workflow_instance'
  state.value = nextState
  tab.value = 'instances'
  applyFilters()
}
function editConnection(c, mode = 'connection') {
  closeManagement()
  connectionForm.value = c
  connectionMode.value = mode
  connectionOpen.value = true
}
async function connectionSaved(id) {
  await guarded(async () => {
    connections.value = await api.dolphinConnections()
    if (String(id) !== cluster.value) {
      cluster.value = String(id)
      await changeCluster()
    } else await load()
  })
}
async function sync() {
  await guarded(async () => {
    await call('/sync', {}, 'POST')
    notice.value = '采集任务已提交，首次定义同步可能需要数分钟。'
    await load()
  })
}
async function queryInstances(reset = true) {
  if (!cluster.value || calendarBusy.value || calendarError.value) return
  if (reset) instancePage.value = 1
  const id = cluster.value,
    p = project.value,
    q = ++queryGeneration,
    scope = appliedScope.value || summaryParams()
  await guarded(async () => {
    const [rows, summary] = await Promise.all([
      call('/instances', {
        kind: kind.value,
        project: p,
        workflow: workflow.value,
        task: task.value,
        state: state.value,
        etl_date: scope.etl_date,
        start: scope.start,
        end: scope.end,
        page: instancePage.value,
        page_size: 20,
      }),
      call('/business-dates', { project: p }),
    ])
    if (id !== cluster.value || p !== project.value || q !== queryGeneration) return
    instances.value = rows
    instancePage.value = rows.page
    dates.value = summary
  })
}
function selectTab(key) {
  tab.value = key
  if (key === 'instances') queryInstances()
  if (key === 'projects' && project.value) loadDefinitions()
}
async function changeProject() {
  workflow.value = ''
  task.value = ''
  await applyFilters()
  if (tab.value === 'projects') loadDefinitions()
}
async function loadDefinitions() {
  if (!project.value) return
  const id = cluster.value,
    p = project.value,
    k = definitionKind.value,
    q = ++definitionGeneration,
    scope = id + ':' + p + ':' + k
  if (scope !== definitionScope) {
    definitions.value = []
    authorizations.value = []
    definitionScope = scope
  }
  definitionLoading.value = true
  await guarded(async () => {
    const [rows, auth] = await Promise.all([
      call('/definitions', { project: p, kind: k }),
      call('/projects/' + p + '/authorizations'),
    ])
    if (q === definitionGeneration && id === cluster.value && p === project.value) {
      definitions.value = rows
      authorizations.value = auth
    }
  })
  if (q === definitionGeneration) definitionLoading.value = false
}
function openDefinition(d) {
  workflow.value = d.definition_code || d.id
  task.value = d.task_code || ''
  kind.value = d.task_code ? 'task_instance' : 'workflow_instance'
  tab.value = 'instances'
  queryInstances()
}
async function openProject(p) {
  project.value = p.id
  workflow.value = ''
  task.value = ''
  tab.value = 'projects'
  await load()
  await loadDefinitions()
}
function editProject() {
  if (!project.value || !currentProject.value) return
  const c = currentProject.value?.config || {}
  projectForm.value = {
    primary_owner: c.primary_owner || '',
    backup_owner: c.backup_owner || '',
    group_ids: JSON.parse(c.group_ids || '[]'),
    alert_enabled: c.alert_enabled !== 0,
  }
  editingProject.value = true
}
async function saveProject() {
  await guarded(async () => {
    await call('/projects/' + project.value + '/config', {}, 'PUT', projectForm.value)
    editingProject.value = false
    await load()
  })
}
async function saveRule() {
  await guarded(async () => {
    const body = { ...ruleForm.value }
    if (body.deadline)
      body.deadline = body.deadline.replace('T', ' ') + (body.deadline.length === 16 ? ':00' : '')
    await call('/rules', {}, 'PUT', body)
    editingRule.value = false
    await load()
  })
}
async function saveSettings() {
  await guarded(async () => {
    const data = {
      ...selected.value,
      service_type: 'dolphinscheduler',
      config: { ...settings.value },
      check_interval_unit: 'seconds',
      check_interval_value: selected.value.check_interval,
    }
    delete data.secret_config_json
    await api.saveDolphinConnection(selected.value.id, data)
    connections.value = await api.dolphinConnections()
    notice.value = '集群配置已保存'
    await load()
  })
}
async function testConnection() {
  await guarded(async () => {
    const r = await call('/test', {}, 'POST')
    notice.value = '连接正常，可访问 ' + r.project_count + ' 个项目'
  })
}
async function showLog(row) {
  logTask.value = row
  logOffset.value = 0
  await loadLog()
}
async function loadLog() {
  const id = logTask.value?.id,
    offset = logOffset.value
  if (!id) return
  logContent.value = ''
  logError.value = ''
  logLoading.value = true
  try {
    const result = await call('/logs/' + id, { offset, limit: 100 })
    if (logTask.value?.id === id && logOffset.value === offset) logContent.value = result.content
  } catch (e) {
    if (logTask.value?.id === id && logOffset.value === offset) logError.value = e.message
  } finally {
    if (logTask.value?.id === id && logOffset.value === offset) logLoading.value = false
  }
}
async function showHistory(node) {
  historyNode.value = node
  await nextTick()
  await loadHistory()
}
async function loadHistory() {
  const id = historyNode.value?.id,
    hours = historyHours.value
  if (!id) return
  historyError.value = ''
  historyEmpty.value = false
  try {
    const rows = await call('/nodes/' + encodeURIComponent(id) + '/history', { hours })
    await nextTick()
    if (!chartEl.value || id !== historyNode.value?.id || hours !== historyHours.value) return
    historyEmpty.value = !rows.length
    chart?.dispose()
    chart = echarts.init(chartEl.value)
    chart.setOption({
      animation: false,
      color: ['#0284c7', '#10b981', '#8b5cf6'],
      tooltip: { trigger: 'axis', confine: true },
      legend: {
        top: 0,
        data: ['CPU', '主机内存', 'JVM 已提交堆'],
        textStyle: { fontSize: 11, color: '#64748b' },
      },
      grid: { left: 44, right: 15, bottom: 55, top: 42 },
      xAxis: {
        type: 'category',
        data: rows.map((r) => r.sampled_at),
        axisLabel: { fontSize: 10, color: '#64748b', formatter: value => hours>24?String(value).slice(5,16):String(value).slice(11,16) },
        axisLine: { lineStyle: { color: '#e2e8f0' } },
      },
      yAxis: {
        type: 'value',
        min: 0,
        max: 100,
        axisLabel: { formatter: '{value}%', fontSize: 10, color: '#64748b' },
        splitLine: { lineStyle: { color: '#f1f5f9' } },
      },
      dataZoom: [{ type: 'inside' }, { type: 'slider', height: 15, bottom: 0 }],
      series: [
        ['CPU', 'cpu_percent'],
        ['主机内存', 'host_memory_percent'],
        ['JVM 已提交堆', 'heap_committed_percent'],
      ].map(([name, key]) => ({
        name,
        type: 'line',
        showSymbol: false,
        connectNulls: false,
        lineStyle: { width: 1.5 },
        data: rows.map((r) => (r.metrics_stale ? null : r[key] ?? null)),
      })),
    })
  } catch (e) {
    if (id === historyNode.value?.id && hours === historyHours.value) historyError.value = e.message
  }
}
function collectNow() {
  closeManagement()
  sync()
}
function openCollectionSettings() {
  closeManagement()
  selectTab('settings')
}
function changeInstancePage(delta) {
  instancePage.value += delta
  queryInstances(false)
}
function selectBusinessDate(row) {
  clearCalendarRequest(); calendarNatural.value=row.etl_date; calendarMeta.value={source:'instance'}
  date.value = row.etl_date
  kind.value = 'task_instance'
  applyFilters()
}
function changeLogOffset(delta) {
  logOffset.value = Math.max(0, logOffset.value + delta)
  loadLog()
}
const resizeChart = () => chart?.resize()
watch(historyNode, (value) => {
  if (!value) {
    chart?.dispose()
    chart = null
  }
})
const runtimeCollection = computed(() => primaryCollection.value.find((c) => c.lane === 'runtime'))
const filtersDirty = computed(
  () => calendarBusy.value || !!calendarError.value || (!!appliedSignature.value && appliedSignature.value !== JSON.stringify(summaryParams()))
)
const healthTone = computed(() =>
  !dashboard.value?.enterprise_enabled
    ? 'neutral'
    : primaryCollection.value.every(collectionFresh)
    ? 'healthy'
    : failures.value.length
    ? 'warning'
    : 'neutral'
)
const healthLabel = computed(() =>
  !cluster.value
    ? '暂无集群'
    : !dashboard.value
    ? '正在连接'
    : !dashboard.value.enterprise_enabled
    ? '采集未启用'
    : primaryCollection.value.every(collectionFresh)
    ? '采集正常'
    : failures.value.length
    ? '数据延迟'
    : '等待采集'
)
const projectScopeKey = computed(() => {
  const s = appliedScope.value || {}
  return JSON.stringify([
    cluster.value,
    s.project,
    s.etl_date,
    s.period,
    s.period === 'custom' ? s.start : '',
    s.period === 'custom' ? s.end : '',
  ])
})
const number = (v) => (v == null ? '未知' : Number(v).toLocaleString('zh-CN'))
const projectLabel = (p) => p?.description?.trim() || p?.name || p?.id || '全部项目'
const timeOnly = (value) => value?.split(' ')[1]?.split('.')[0] || value || '—'
const scopeDescription = computed(() => {
  const s = appliedScope.value || { ...summaryParams(), period: period.value }
  const p = projectOptions.value.find((p) => p.id === s.project)
  const range =
    s.period === 'custom'
      ? (s.start || '起始不限') + ' 至 ' + (s.end || '当前')
      : { today: '今天', '24h': '最近24小时', all: '已采集全部' }[s.period]
  return (
    projectLabel(p) +
    ' · 执行：' +
    range +
    ' · ' +
    (s.etl_date ? '业务日期 ' + s.etl_date : '业务日期不限')
  )
})
function closeManagement() {
  if (managementOpen.value) {
    managementOpen.value = false
    managementTrigger.value?.focus()
  }
}
function outsideManagement(event) {
  if (managementOpen.value && !managementEl.value?.contains(event.target))
    managementOpen.value = false
}
function tabCount(key) {
  return key === 'overview'
    ? dashboard.value?.project_count
    : key === 'instances'
    ? dashboard.value?.workflow_statistics?.total
    : key === 'resources'
    ? dashboard.value
      ? dashboard.value.nodes.length
      : null
    : key === 'alerts'
    ? dashboard.value
      ? incidents.value.filter(
          (i) =>
            Number(i.active) === 1 &&
            (!project.value || !i.project_code || i.project_code === project.value)
        ).length
      : null
    : null
}
function moveTab(event, index) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const next =
    event.key === 'Home'
      ? 0
      : event.key === 'End'
      ? tabs.length - 1
      : (index + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length
  selectTab(tabs[next].key)
  nextTick(() => document.getElementById('ds-tab-' + tabs[next].key)?.focus())
}
function openMetric(m) {
  if (m.key === 'online') {
    selectTab('projects')
    return
  }
  if (m.key === 'unstarted') {
    tab.value = 'project-list'
    projectFocus.value = 'unstarted'
    nextTick(() =>
      document.getElementById('ds-project-title')?.scrollIntoView({
        block: 'start',
        behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth',
      })
    )
    return
  }
  kind.value = 'workflow_instance'
  workflow.value = ''
  task.value = ''
  state.value = m.state
  tab.value = 'instances'
  queryInstances()
}
function resetInstanceFilters() {
  state.value = ''
  workflow.value = ''
  task.value = ''
  queryInstances()
}
function statusTone(s) {
  return ['SUCCESS', 'FORCED_SUCCESS'].includes(s)
    ? 'is-success'
    : s === 'FAILURE'
    ? 'is-failed'
    : s === 'RUNNING_EXECUTION'
    ? 'is-running'
    : 'is-waiting'
}
function dateSource(source) {
  return (
    {
      workflow_parameters: '工作流参数',
      task_parameters: '任务参数',
      consistent_task_parameters: '任务参数',
      verified_logical_schedule: '调度业务日期',
      UNKNOWN: '未识别',
    }[source] || '来源未提供'
  )
}
function durationText(row) {
  const value = Number(row.duration_seconds)
  if (row.duration_seconds == null || !Number.isFinite(value)) return row.duration || '—'
  const seconds = Math.max(0, Math.floor(value)),
    days = Math.floor(seconds / 86400),
    hours = Math.floor((seconds % 86400) / 3600),
    minutes = Math.floor((seconds % 3600) / 60)
  return days
    ? number(days) + ' 天 ' + hours + ' 小时'
    : hours
    ? hours + ' 小时 ' + minutes + ' 分'
    : minutes
    ? minutes + ' 分 ' + (seconds % 60) + ' 秒'
    : seconds + ' 秒'
}
function laneName(lane) {
  const [key, id] = lane.split(':')
  const names = {
    inventory: '工作流定义',
    runtime: '执行实例',
    users: '用户与授权',
    resources: '集群资源',
    runtime_coverage: '实例覆盖校验',
    authorization: '项目授权',
    alerts: '告警处理',
  }
  return (
    (names[key] || '数据同步') +
    (id
      ? ' · ' +
        (names[id] ||
          (projectOptions.value.find((p) => p.id === id)
            ? projectLabel(projectOptions.value.find((p) => p.id === id))
            : id))
      : '')
  )
}
function incidentProject(code) {
  return code
    ? projectLabel(projectOptions.value.find((p) => p.id === code)) === '全部项目'
      ? code
      : projectLabel(projectOptions.value.find((p) => p.id === code))
    : '集群'
}
function incidentCategory(type) {
  const aggregate = type?.startsWith('AGGREGATED_'),
    key = aggregate ? type.slice(11) : type
  return (
    ({
      WORKFLOW_FAILURE: '工作流失败',
      TASK_FAILURE: '任务失败',
      TIMEOUT: '运行超时',
      BACKLOG: '任务积压',
      ETL_DEADLINE: '业务日期超期',
      NODE_OFFLINE: '节点离线',
      CPU_HIGH: 'CPU 负载过高',
      MEMORY_HIGH: '主机内存过高',
      JVM_HIGH: 'JVM 堆使用率过高',
      DISK_LOW: '磁盘空间不足',
      COLLECTION_INTERRUPTED: '采集中断',
      COLLECTION_STALE: '数据过期',
      CLUSTER_ROOT_CAUSE: '集群关联异常',
    }[key] || key) + (aggregate ? '（聚合）' : '')
  )
}
function scopeType(type) {
  return { platform: '平台', project: '项目', workflow: '工作流', task: '任务' }[type] || type
}
onMounted(async () => {
  document.addEventListener('pointerdown', outsideManagement)
  window.addEventListener('resize', resizeChart)
  await guarded(async () => {
    ;[connections.value, groups.value] = await Promise.all([
      api.dolphinConnections(),
      api.listAlertGroups(),
    ])
    cluster.value = String(
      connections.value.find((c) => String(c.id) === String(route.query.edit))?.id ||
        connections.value[0]?.id ||
        ''
    )
    if (cluster.value) await changeCluster()
    if (route.query.edit && selected.value) editConnection(selected.value)
  })
  timer = setInterval(() => {
    if (
      autoRefresh.value &&
      !filtersDirty.value &&
      !editingProject.value &&
      !editingRule.value &&
      !connectionOpen.value &&
      !managementOpen.value &&
      !incidentDetail.value &&
      !historyNode.value &&
      !logTask.value &&
      !taskInstance.value &&
      !busy.value &&
      tab.value !== 'settings'
    ) {
      updateRange()
      load()
    }
  }, 30000)
})
onBeforeUnmount(() => {
  generation++
  queryGeneration++
  definitionGeneration++
  clearInterval(timer)
  document.removeEventListener('pointerdown', outsideManagement)
  window.removeEventListener('resize', resizeChart)
  chart?.dispose()
})
</script>
<style scoped src="../assets/styles/dolphin-monitor.css"></style>
