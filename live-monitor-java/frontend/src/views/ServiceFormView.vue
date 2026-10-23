<template>
  <div class="page-container service-form-page">
    <div class="page-header">
      <div class="page-header-title">
        <router-link to="/dashboard" class="back-nav-link">
          <IconSvg name="arrow-left" size="14" />
          <span>返回监控总览</span>
        </router-link>
        <h1 style="margin-top: 6px;">{{ isEdit ? '编辑监控服务' : '添加监控服务' }}</h1>
        <p>配置探测目标、检测策略、报警阈值与数据发送规则</p>
      </div>
    </div>

    <form @submit.prevent="handleSubmit">
      <!-- 1. 基础配置 -->
      <div class="card">
        <div class="card-header">
          <div class="card-title">
            <IconSvg name="activity" size="16" />
            <span>基本信息</span>
          </div>
        </div>

        <div class="form-row">
          <div class="form-group">
            <label class="form-label">服务类型</label>
            <select v-model="form.service_type" class="form-control" required @change="handleTypeChange">
              <option value="web">Web 应用 (HTTP/HTTPS)</option>
              <option value="api">API 接口请求</option>
              <option value="process">进程检测</option>
              <option value="port">端口检测 (TCP/UDP)</option>
              <option value="redis">Redis</option>
              <option value="zookeeper">ZooKeeper</option>
              <option value="mysql">MySQL</option>
              <option value="oracle">Oracle</option>
              <option value="postgresql">PostgreSQL</option>
              <option value="jdbc">通用 JDBC</option>
              <option value="data_send">数据发送</option>
            </select>
          </div>

          <div class="form-group">
            <label class="form-label">服务名称</label>
            <input
              v-model="form.service_name"
              type="text"
              required
              class="form-control"
              placeholder="例如：核心交易系统 Web"
              maxlength="120"
            />
          </div>

          <div class="form-group">
            <label class="form-label">集群分组</label>
            <input
              v-model="form.cluster_name"
              type="text"
              class="form-control"
              placeholder="例如：prod-a"
              maxlength="60"
            />
          </div>
        </div>

        <div class="form-group">
          <label class="form-label">监控说明</label>
          <textarea
            v-model="form.monitor_reason"
            rows="2"
            class="form-control"
            placeholder="例如：核心业务链路入口，检测可用性与响应时间"
            maxlength="1000"
          ></textarea>
        </div>

        <div class="form-row">
          <div class="form-group">
            <label class="form-label">检测间隔 (秒)</label>
            <input
              v-model.number="form.check_interval"
              type="number"
              min="1"
              max="31536000"
              class="form-control"
              required
            />
          </div>
          <div class="form-group">
            <label class="form-label">检测超时 (秒)</label>
            <input
              v-model.number="form.check_timeout_seconds"
              type="number"
              min="0.5"
              max="60"
              step="0.5"
              class="form-control"
              required
            />
          </div>
        </div>
      </div>

      <!-- 2. 服务类型专属配置 -->

      <!-- 2.1 Web应用 -->
      <div v-if="form.service_type === 'web'" class="card">
        <div class="card-header">
          <div class="card-title">
            <IconSvg name="activity" size="16" />
            <span>Web 探测参数</span>
          </div>
        </div>
        <div class="form-row">
          <div class="form-group" style="grid-column: span 2;">
            <label class="form-label">目标 URL</label>
            <input v-model="form.url" type="url" class="form-control" placeholder="https://example.com/health" required />
          </div>
          <div class="form-group">
            <label class="form-label">HTTP 请求方法</label>
            <select v-model="form.http_method" class="form-control">
              <option value="GET">GET</option>
              <option value="POST">POST</option>
              <option value="HEAD">HEAD</option>
            </select>
          </div>
          <div class="form-group">
            <label class="form-label">期望状态码</label>
            <input v-model.number="form.expected_status_code" type="number" class="form-control" placeholder="200" />
          </div>
        </div>
        <div class="form-group">
          <label class="form-label">响应包含关键字</label>
          <input v-model="form.response_keyword" type="text" class="form-control" placeholder="例如：OK 或 success" />
        </div>
        <label style="display: flex; align-items: center; gap: 6px; font-size: 13px; cursor: pointer;">
          <input v-model="form.ignore_ssl_verification" type="checkbox" />
          <span>忽略 SSL 证书验证</span>
        </label>
      </div>

      <!-- 2.2 API接口 -->
      <div v-if="form.service_type === 'api'" class="card">
        <div class="card-header">
          <div class="card-title">
            <IconSvg name="activity" size="16" />
            <span>API 接口探测参数</span>
          </div>
        </div>
        <div class="form-row">
          <div class="form-group" style="grid-column: span 2;">
            <label class="form-label">API 地址</label>
            <input v-model="form.url" type="url" class="form-control" placeholder="https://api.example.com/v1/ping" required />
          </div>
          <div class="form-group">
            <label class="form-label">HTTP 方法</label>
            <select v-model="form.http_method" class="form-control">
              <option value="GET">GET</option>
              <option value="POST">POST</option>
              <option value="PUT">PUT</option>
            </select>
          </div>
        </div>

        <div class="form-group">
          <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px;">
            <label class="form-label" style="margin: 0;">请求头 (Headers)</label>
            <button type="button" class="btn btn-ghost btn-sm" @click="addApiHeader">
              <IconSvg name="plus" size="12" />
              <span>添加 Header</span>
            </button>
          </div>
          <div v-for="(h, idx) in form.api_headers" :key="idx" style="display: flex; gap: 8px; margin-bottom: 6px;">
            <input v-model="h.name" type="text" class="form-control" placeholder="Header Name" style="width: 200px;" />
            <input v-model="h.value" type="text" class="form-control" placeholder="Header Value" />
            <button type="button" class="btn btn-danger btn-sm" @click="removeApiHeader(idx)">
              <IconSvg name="trash" size="12" />
            </button>
          </div>
        </div>

        <div class="form-group">
          <label class="form-label">请求体 (Request Body)</label>
          <textarea v-model="form.api_request_body" rows="3" class="form-control" placeholder='{"query":"status"}'></textarea>
        </div>
      </div>

      <!-- 2.3 进程检测 -->
      <div v-if="form.service_type === 'process'" class="card">
        <div class="card-header">
          <div class="card-title">
            <IconSvg name="server" size="16" />
            <span>进程检测参数</span>
          </div>
        </div>
        <div class="form-row">
          <div class="form-group">
            <label class="form-label">目标主机 (SSH)</label>
            <select v-model="form.host_id" class="form-control" required>
              <option value="">-- 选择已维护的主机 --</option>
              <option v-for="h in hostsList" :key="h.id" :value="h.id">
                {{ h.host_name }} ({{ h.ip }})
              </option>
            </select>
          </div>
          <div class="form-group">
            <label class="form-label">进程名</label>
            <input v-model="form.process_name" type="text" class="form-control" placeholder="例如：nginx" />
          </div>
          <div class="form-group">
            <label class="form-label">匹配关键字</label>
            <input v-model="form.process_match_keyword" type="text" class="form-control" placeholder="例如：java -jar app.jar" />
          </div>
        </div>
        <div class="form-group">
          <label class="form-label">自定义检查命令 (可选)</label>
          <input v-model="form.process_check_command" type="text" class="form-control" placeholder="pgrep -f app.jar" />
        </div>
      </div>

      <!-- 2.4 端口检测 -->
      <div v-if="form.service_type === 'port'" class="card">
        <div class="card-header">
          <div class="card-title">
            <IconSvg name="activity" size="16" />
            <span>端口检测参数</span>
          </div>
        </div>
        <div class="form-row">
          <div class="form-group">
            <label class="form-label">目标主机 / IP</label>
            <input v-model="form.host" type="text" class="form-control" placeholder="127.0.0.1" required />
          </div>
          <div class="form-group">
            <label class="form-label">目标端口</label>
            <input v-model.number="form.port" type="number" class="form-control" placeholder="8080" required />
          </div>
        </div>
      </div>

      <!-- 2.5 Redis -->
      <div v-if="form.service_type === 'redis'" class="card">
        <div class="card-header">
          <div class="card-title">
            <IconSvg name="database" size="16" />
            <span>Redis 检测参数</span>
          </div>
        </div>
        <div class="form-row">
          <div class="form-group">
            <label class="form-label">Redis 主机</label>
            <input v-model="form.host" type="text" class="form-control" placeholder="127.0.0.1" required />
          </div>
          <div class="form-group">
            <label class="form-label">端口</label>
            <input v-model.number="form.port" type="number" class="form-control" placeholder="6379" required />
          </div>
          <div class="form-group">
            <label class="form-label">密码</label>
            <input v-model="form.redis_password" type="password" class="form-control" placeholder="密码 (无则留空)" />
          </div>
        </div>
        <label style="display: flex; align-items: center; gap: 6px; font-size: 13px; cursor: pointer;">
          <input v-model="form.redis_cluster_mode" type="checkbox" />
          <span>启用 Redis 集群模式探测</span>
        </label>
      </div>

      <!-- 2.6 ZooKeeper -->
      <div v-if="form.service_type === 'zookeeper'" class="card">
        <div class="card-header">
          <div class="card-title">
            <IconSvg name="activity" size="16" />
            <span>ZooKeeper 检测参数</span>
          </div>
        </div>
        <div class="form-row">
          <div class="form-group">
            <label class="form-label">ZooKeeper 主机</label>
            <input v-model="form.host" type="text" class="form-control" placeholder="127.0.0.1" required />
          </div>
          <div class="form-group">
            <label class="form-label">端口</label>
            <input v-model.number="form.port" type="number" class="form-control" placeholder="2181" required />
          </div>
          <div class="form-group">
            <label class="form-label">检测四字命令</label>
            <select v-model="form.zookeeper_check_mode" class="form-control">
              <option value="ruok">ruok (心跳探测)</option>
              <option value="stat">stat (状态探测)</option>
              <option value="srvr">srvr (服务端信息)</option>
            </select>
          </div>
        </div>
      </div>

      <!-- 2.7 数据库 (MySQL, Oracle, PostgreSQL, 通用JDBC) -->
      <div v-if="isDatabaseType" class="card">
        <div class="card-header">
          <div class="card-title">
            <IconSvg name="database" size="16" />
            <span>数据库探测配置</span>
          </div>
        </div>
        <div class="form-row">
          <div class="form-group">
            <label class="form-label">引用已维护连接 (可选)</label>
            <select v-model="form.database_connection_service_id" class="form-control" @change="handleDbConnectionChange">
              <option value="">-- 手动填写连接信息 --</option>
              <option v-for="conn in dbConnections" :key="conn.id" :value="conn.id">
                {{ conn.cluster_name ? `${conn.cluster_name} / ` : '' }}{{ conn.service_name }} ({{ conn.endpoint }})
              </option>
            </select>
          </div>
          <div class="form-group">
            <label class="form-label">数据库主机</label>
            <input v-model="form.host" type="text" class="form-control" placeholder="127.0.0.1" :readonly="Boolean(form.database_connection_service_id)" />
          </div>
          <div class="form-group">
            <label class="form-label">端口</label>
            <input v-model.number="form.port" type="number" class="form-control" :readonly="Boolean(form.database_connection_service_id)" />
          </div>
        </div>
        <div class="form-row">
          <div class="form-group">
            <label class="form-label">数据库名 / 服务名</label>
            <input v-model="form.database_name" type="text" class="form-control" :readonly="Boolean(form.database_connection_service_id)" />
          </div>
          <div class="form-group">
            <label class="form-label">用户名</label>
            <input v-model="form.database_username" type="text" class="form-control" :readonly="Boolean(form.database_connection_service_id)" />
          </div>
          <div class="form-group">
            <label class="form-label">密码</label>
            <input v-model="form.database_password" type="password" class="form-control" placeholder="留空保持原密码" :readonly="Boolean(form.database_connection_service_id)" />
          </div>
        </div>
        <div v-if="form.service_type === 'jdbc'" class="form-row">
          <div class="form-group" style="grid-column: span 2;">
            <label class="form-label">JDBC 连接串 (URL)</label>
            <input v-model="form.jdbc_url" type="text" class="form-control" placeholder="jdbc:sqlserver://127.0.0.1:1433;databaseName=test" />
          </div>
          <div class="form-group">
            <label class="form-label">JDBC 驱动类</label>
            <input v-model="form.jdbc_driver_class" type="text" class="form-control" placeholder="com.microsoft.sqlserver.jdbc.SQLServerDriver" />
          </div>
        </div>
        <div class="form-group">
          <label class="form-label">探测 SQL 语句</label>
          <input v-model="form.database_query" type="text" class="form-control" placeholder="SELECT 1" />
        </div>
      </div>

      <!-- 2.8 数据发送服务项 (核心新增需求) -->
      <div v-if="form.service_type === 'data_send'" class="card">
        <div class="card-header">
          <div class="card-title">
            <IconSvg name="send" size="16" />
            <span>数据发送配置</span>
            <span class="badge badge-info" style="margin-left: 8px;">最多 10 条记录 · 适配手机短信显示限制</span>
          </div>
        </div>

        <div class="form-row">
          <div class="form-group">
            <label class="form-label">选择已维护好的数据库监控</label>
            <select v-model="form.database_connection_service_id" class="form-control" required>
              <option value="">-- 请选择已维护好的数据库监控 --</option>
              <option v-for="conn in dbConnections" :key="conn.id" :value="conn.id">
                {{ conn.cluster_name ? `${conn.cluster_name} / ` : '' }}{{ conn.service_name }} ({{ (conn.service_type || '').toUpperCase() }})
              </option>
            </select>
          </div>

          <div class="form-group">
            <label class="form-label">短信播报标题 (可选)</label>
            <input v-model="form.data_send_title" type="text" class="form-control" placeholder="例如：核心业务大额提醒" maxlength="40" />
          </div>
        </div>

        <div class="form-group">
          <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px;">
            <label class="form-label" style="margin: 0;">编写查询的 SQL 语句 (只读 SELECT)</label>
            <span class="text-muted" style="font-size: 12px;">单次最多返回并发送 10 条记录</span>
          </div>
          <textarea
            v-model="form.database_query"
            rows="3"
            class="form-control"
            style="font-family: Consolas, monospace;"
            placeholder="SELECT FUND_CODE, FUND_NAME, NAV FROM T_FUND WHERE STATUS = 1 ORDER BY UPDATE_TIME DESC"
            required
          ></textarea>
          <div style="display: flex; justify-content: flex-end; margin-top: 8px;">
            <button
              type="button"
              class="btn btn-ghost"
              :disabled="previewingData"
              @click="handlePreviewDataSend"
            >
              <IconSvg name="table" size="14" />
              <span>{{ previewingData ? '正在查询预览...' : '预览查询结果' }}</span>
            </button>
          </div>
        </div>

        <!-- 查询结果数据表格预览 -->
        <div v-if="dataSendPreviewResult" class="table-preview-section" style="margin-bottom: 18px;">
          <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px;">
            <strong style="font-size: 13px; color: #0f172a; display: flex; align-items: center; gap: 6px;">
              <IconSvg name="table" size="14" />
              <span>查询结果数据预览 (最多 10 条记录)</span>
            </strong>
            <span class="badge badge-info">
              共返回 {{ dataSendPreviewResult.rowCount }} 条数据 · {{ dataSendPreviewResult.allColumns?.length || 0 }} 个字段
            </span>
          </div>

          <div class="table-wrap" style="max-height: 220px;">
            <table class="table">
              <thead>
                <tr>
                  <th style="width: 40px;">#</th>
                  <th v-for="col in dataSendPreviewResult.allColumns" :key="col">{{ col }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="!dataSendPreviewResult.rows?.length">
                  <td :colspan="(dataSendPreviewResult.allColumns?.length || 0) + 1" class="text-center text-muted">
                    (无查询记录)
                  </td>
                </tr>
                <tr v-for="(row, idx) in dataSendPreviewResult.rows" :key="idx">
                  <td style="color: #94a3b8; font-size: 11px;">{{ idx + 1 }}</td>
                  <td v-for="col in dataSendPreviewResult.allColumns" :key="col">
                    {{ row[col] ?? '-' }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- 适合手机短信显示的列数限制与勾选 -->
        <div v-if="dataSendPreviewResult?.allColumns?.length" class="form-group">
          <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px;">
            <label class="form-label" style="margin: 0;">短信发送列配置 (适合手机短信显示限制)</label>
            <span :style="{ color: selectedColumnsCount > 4 ? '#dc2626' : '#0284c7', fontWeight: '600', fontSize: '12px' }">
              已选 {{ selectedColumnsCount }}/4 列
            </span>
          </div>
          <div style="background: #eff6ff; border: 1px solid #bfdbfe; color: #1e40af; padding: 6px 12px; border-radius: 6px; font-size: 12px; margin-bottom: 8px;">
            ★ <strong>适合手机短信显示的限制：</strong>手机屏幕单行宽度有限，最多允许勾选 <strong>4 列</strong>（推荐 2~3 列，如“代码 | 名称 | 净值”）。超出 4 列将导致排版混乱。
          </div>
          <div class="col-chips-group">
            <div
              v-for="col in dataSendPreviewResult.allColumns"
              :key="col"
              class="col-chip-item"
              :class="{ active: isColumnSelected(col) }"
            >
              <label class="col-checkbox-label">
                <input
                  type="checkbox"
                  :checked="isColumnSelected(col)"
                  @change="toggleColumnSelection(col, $event)"
                />
                <span>{{ col }}</span>
              </label>
              <input
                v-model="form.data_send_column_aliases[col]"
                type="text"
                placeholder="别名"
                class="alias-input"
                @input="refreshLiveSmsPreview"
              />
            </div>
          </div>
        </div>

        <!-- 手机短信效果实时模拟面板 -->
        <div v-if="dataSendPreviewResult?.smsContent" class="sms-preview-card">
          <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px;">
            <strong style="font-size: 13px; color: #0f172a; display: flex; align-items: center; gap: 6px;">
              <IconSvg name="smartphone" size="14" />
              <span>手机短信效果预览</span>
            </strong>
            <span style="font-size: 12px; color: #64748b;">
              共 {{ dataSendPreviewResult.charCount || 0 }} 字符 · 预计 {{ dataSendPreviewResult.smsCount || 1 }} 条短信 (70字/条)
            </span>
          </div>
          <pre class="sms-bubble-text">{{ dataSendPreviewResult.smsContent }}</pre>
        </div>
      </div>

      <!-- 3. 告警策略与告警组配置 -->
      <div class="card">
        <div class="card-header">
          <div class="card-title">
            <IconSvg name="bell" size="16" />
            <span>告警策略与目标配置</span>
          </div>
        </div>

        <div class="form-row">
          <div class="form-group">
            <label class="form-label">绑定告警组 (包含短信 SMS 渠道)</label>
            <select v-model="form.alert_group_id" class="form-control">
              <option :value="null">-- 不绑定告警组 --</option>
              <option v-for="g in alertGroups" :key="g.id" :value="g.id">
                {{ g.group_name }}
              </option>
            </select>
          </div>
          <div class="form-group">
            <label class="form-label">连续失败告警阈值 (次)</label>
            <input v-model.number="form.service_consecutive_failures" type="number" min="1" max="100" class="form-control" />
          </div>
        </div>
      </div>

      <!-- 4. 测试连接反馈 -->
      <div v-if="testResult" class="card" :class="[testResult.status === 'UP' ? 'test-ok' : 'test-bad']">
        <div style="display: flex; align-items: center; gap: 8px;">
          <IconSvg :name="testResult.status === 'UP' ? 'check' : 'x'" size="18" />
          <strong>探测状态: {{ testResult.status }}</strong>
          <span style="font-size: 12px; color: #64748b;">耗时: {{ testResult.responseTimeMs || 0 }}ms</span>
        </div>
        <p style="margin-top: 6px; font-size: 13px;">{{ testResult.message }}</p>
      </div>

      <!-- 底部操作按钮 -->
      <div class="form-actions">
        <router-link to="/dashboard" class="btn btn-ghost">取消</router-link>
        <button type="button" class="btn btn-ghost" :disabled="testing" @click="handleTestConnection">
          <IconSvg name="play" size="14" />
          <span>{{ testing ? '正在测试...' : '测试连接 / 发送' }}</span>
        </button>
        <button type="submit" class="btn btn-primary" :disabled="saving">
          <IconSvg name="check" size="14" />
          <span>{{ saving ? '保存中...' : '保存监控服务' }}</span>
        </button>
      </div>
    </form>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import { store } from '../store'

const route = useRoute()
const router = useRouter()
const isEdit = computed(() => Boolean(route.params.id))
const serviceId = computed(() => route.params.id)

const testing = ref(false)
const saving = ref(false)
const testResult = ref(null)
const previewingData = ref(false)
const dataSendPreviewResult = ref(null)

const dbConnections = ref([])
const alertGroups = ref([])
const hostsList = ref([])

const form = reactive({
  service_type: 'web',
  service_name: '',
  cluster_name: '',
  monitor_reason: '',
  check_interval: 60,
  check_timeout_seconds: 3,
  enabled: true,

  // Web / API
  url: '',
  http_method: 'GET',
  expected_status_code: 200,
  response_keyword: '',
  ignore_ssl_verification: false,
  api_headers: [],
  api_request_body: '',

  // Process / Port
  host_id: '',
  process_name: '',
  process_match_keyword: '',
  process_check_command: '',

  // Redis / ZK
  redis_password: '',
  redis_cluster_mode: false,
  zookeeper_check_mode: 'ruok',

  // Database
  database_connection_service_id: '',
  host: '',
  port: null,
  database_name: '',
  database_username: '',
  database_password: '',
  jdbc_url: '',
  jdbc_driver_class: '',
  database_query: '',

  // Data Send (服务项)
  data_send_title: '',
  data_send_selected_columns: [],
  data_send_column_aliases: {},

  // Alert
  alert_group_id: null,
  service_consecutive_failures: 3
})

const isDatabaseType = computed(() => {
  return ['mysql', 'oracle', 'postgresql', 'jdbc'].includes(form.service_type)
})

const selectedColumnsCount = computed(() => {
  return form.data_send_selected_columns?.length || 0
})

const isColumnSelected = (col) => {
  return form.data_send_selected_columns.includes(col)
}

const toggleColumnSelection = (col, e) => {
  if (e.target.checked) {
    if (form.data_send_selected_columns.length >= 4) {
      e.target.checked = false
      store.showToast('适合手机短信显示的限制：最多只能选择 4 列！', 'error')
      return
    }
    form.data_send_selected_columns.push(col)
  } else {
    form.data_send_selected_columns = form.data_send_selected_columns.filter((c) => c !== col)
  }
  refreshLiveSmsPreview()
}

const addApiHeader = () => {
  form.api_headers.push({ name: '', value: '' })
}

const removeApiHeader = (idx) => {
  form.api_headers.splice(idx, 1)
}

const handleTypeChange = () => {
  if (form.service_type === 'mysql') form.port = 3306
  if (form.service_type === 'oracle') form.port = 1521
  if (form.service_type === 'postgresql') form.port = 5432
  if (form.service_type === 'redis') form.port = 6379
  if (form.service_type === 'zookeeper') form.port = 2181
  if (form.service_type === 'data_send') {
    if (!form.database_query) {
      form.database_query = 'SELECT FUND_CODE, FUND_NAME, NAV FROM T_FUND WHERE STATUS = 1 ORDER BY UPDATE_TIME DESC'
    }
  }
}

const handleDbConnectionChange = () => {
  const selected = dbConnections.value.find((c) => String(c.id) === String(form.database_connection_service_id))
  if (selected) {
    form.host = selected.host || selected.endpoint || ''
    form.port = selected.port || null
    form.database_name = selected.database_name || ''
    form.database_username = selected.database_username || ''
    form.jdbc_url = selected.jdbc_url || ''
    form.jdbc_driver_class = selected.jdbc_driver_class || ''
  }
}

const handlePreviewDataSend = async () => {
  if (!form.database_connection_service_id) {
    store.showToast('请先选择已维护好的数据库监控', 'error')
    return
  }
  if (!form.database_query?.trim()) {
    store.showToast('请填写查询 SQL 语句', 'error')
    return
  }

  previewingData.value = true
  try {
    const res = await api.dataSendPreview({
      databaseConnectionServiceId: Number(form.database_connection_service_id),
      databaseQuery: form.database_query.trim(),
      maxRows: 10,
      title: form.data_send_title?.trim() || form.service_name || '',
      selectedColumns: form.data_send_selected_columns?.length ? form.data_send_selected_columns : null,
      columnAliases: Object.keys(form.data_send_column_aliases).length ? form.data_send_column_aliases : null
    })

    dataSendPreviewResult.value = res
    if (!form.data_send_selected_columns.length) {
      form.data_send_selected_columns = res.selectedColumns || []
    }
    store.showToast(`查询成功：共获取 ${res.rowCount} 条数据，包含 ${res.allColumns?.length || 0} 个字段`, 'success')
  } catch (err) {
    store.showToast('查询失败：' + err.message, 'error')
  } finally {
    previewingData.value = false
  }
}

let refreshTimer = null
const refreshLiveSmsPreview = () => {
  clearTimeout(refreshTimer)
  refreshTimer = setTimeout(async () => {
    if (!form.database_connection_service_id || !form.database_query?.trim()) return
    if (!form.data_send_selected_columns.length || form.data_send_selected_columns.length > 4) return
    try {
      const res = await api.dataSendPreview({
        databaseConnectionServiceId: Number(form.database_connection_service_id),
        databaseQuery: form.database_query.trim(),
        maxRows: 10,
        title: form.data_send_title?.trim() || form.service_name || '',
        selectedColumns: form.data_send_selected_columns,
        columnAliases: form.data_send_column_aliases
      })
      if (dataSendPreviewResult.value) {
        dataSendPreviewResult.value.smsContent = res.smsContent
        dataSendPreviewResult.value.charCount = res.charCount
        dataSendPreviewResult.value.smsCount = res.smsCount
      }
    } catch (_) {}
  }, 300)
}

const handleTestConnection = async () => {
  testing.value = true
  testResult.value = null
  try {
    const payload = { ...form }
    if (form.service_type === 'data_send') {
      if (form.data_send_selected_columns.length > 4) {
        throw new Error('适合手机短信显示的限制：最多只能选择 4 列！')
      }
      payload.data_send_max_rows = 10
    }
    const res = isEdit.value
      ? await api.testExistingService(serviceId.value, payload)
      : await api.testService(payload)
    testResult.value = res
    store.showToast(`探测完成: ${res.status}`, res.status === 'UP' ? 'success' : 'error')
  } catch (err) {
    testResult.value = { status: 'DOWN', message: err.message, responseTimeMs: 0 }
    store.showToast('测试失败：' + err.message, 'error')
  } finally {
    testing.value = false
  }
}

const handleSubmit = async () => {
  saving.value = true
  try {
    const payload = { ...form }
    if (form.service_type === 'data_send') {
      if (!form.database_connection_service_id) {
        throw new Error('请选择已维护好的数据库监控')
      }
      if (!form.database_query?.trim()) {
        throw new Error('请填写查询 SQL 语句')
      }
      if (form.data_send_selected_columns.length > 4) {
        throw new Error('适合手机短信显示的限制：最多只能选择 4 列！')
      }
      payload.data_send_max_rows = 10
    }
    if (isEdit.value) {
      await api.updateService(serviceId.value, payload)
      store.showToast('监控服务已更新', 'success')
    } else {
      await api.createService(payload)
      store.showToast('监控服务已创建', 'success')
    }
    router.push('/dashboard')
  } catch (err) {
    store.showToast('保存失败：' + err.message, 'error')
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  try {
    const [dbList, groupList, hList] = await Promise.all([
      api.databaseConnections(true).catch(() => []),
      api.listAlertGroups(true).catch(() => []),
      api.listHosts(true).catch(() => [])
    ])
    dbConnections.value = dbList || []
    alertGroups.value = groupList || []
    hostsList.value = hList || []

    if (isEdit.value) {
      const srv = await api.getService(serviceId.value)
      Object.assign(form, srv)
      if (srv.api_headers) form.api_headers = srv.api_headers
      if (srv.service_type === 'data_send') {
        form.data_send_selected_columns = srv.data_send_selected_columns || []
        form.data_send_column_aliases = srv.data_send_column_aliases || {}
        handlePreviewDataSend()
      }
    }
  } catch (err) {
    store.showToast('加载服务信息失败：' + err.message, 'error')
  }
})
</script>

<style scoped>
.back-nav-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: var(--text-muted);
}
.back-nav-link:hover {
  color: var(--primary-color);
}

.col-chips-group {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.col-chip-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: #ffffff;
  border: 1px solid #cbd5e1;
  padding: 4px 8px;
  border-radius: 6px;
  font-size: 13px;
}
.col-chip-item.active {
  border-color: #0284c7;
  background: #f0f9ff;
}
.col-checkbox-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin: 0;
  cursor: pointer;
  font-weight: 500;
}
.alias-input {
  width: 55px;
  font-size: 11px;
  padding: 1px 4px;
  border: 1px solid #cbd5e1;
  border-radius: 3px;
}

.sms-preview-card {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 14px;
  margin-top: 10px;
}
.sms-bubble-text {
  margin: 0;
  padding: 10px;
  background: #ffffff;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  color: #334155;
  word-break: break-all;
}

.test-ok {
  background: #f0fdf4;
  border-color: #86efac;
  color: #166534;
}
.test-bad {
  background: #fef2f2;
  border-color: #fca5a5;
  color: #991b1b;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 24px;
}
</style>
