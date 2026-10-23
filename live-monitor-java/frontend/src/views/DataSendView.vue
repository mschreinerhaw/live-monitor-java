<template>
  <div class="page-container data-send-page">
    <div class="page-header">
      <div class="page-header-title">
        <router-link to="/dashboard" class="back-link">
          <IconSvg name="arrow-left" size="14" />
          <span>返回监控总览</span>
        </router-link>
        <h1 style="margin-top: 6px;">数据发送功能</h1>
        <p>选择已维护好的数据库监控，编写查询 SQL 并预览结果，将数据发送至手机短信。单次限制最多 10 条记录，列数严格适配手机短信显示限制。</p>
      </div>
      <div>
        <router-link to="/services/new" class="btn btn-ghost">
          <IconSvg name="plus" size="14" />
          <span>保存为定时监控服务</span>
        </router-link>
      </div>
    </div>

    <!-- 1. 查询配置卡片 -->
    <div class="card">
      <div class="card-header">
        <div class="card-title">
          <IconSvg name="database" size="16" />
          <span>1. 选择数据库监控与编写 SQL</span>
        </div>
      </div>

      <div class="form-row" style="margin-bottom: 16px;">
        <div class="form-group">
          <label class="form-label">选择已维护好的数据库监控</label>
          <select v-model="selectedConnId" class="form-control" @change="handleConnChange">
            <option value="">-- 请选择已维护好的数据库监控 --</option>
            <option v-for="c in dbConnections" :key="c.id" :value="c.id">
              {{ c.cluster_name ? `${c.cluster_name} / ` : '' }}{{ c.service_name }} ({{ c.service_type.toUpperCase() }})
            </option>
          </select>
          <div v-if="selectedConnInfo" class="conn-info-bar">
            {{ selectedConnInfo }}
          </div>
        </div>

        <div class="form-group">
          <label class="form-label">短信播报标题 (可选)</label>
          <input v-model="smsTitle" type="text" class="form-control" placeholder="例如：核心业务指标播报" maxlength="40" />
        </div>
      </div>

      <div class="form-group">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px;">
          <label class="form-label" style="margin: 0;">编写查询的 SQL 语句 (只读 SELECT)</label>
          <span class="text-muted" style="font-size: 12px;">单次最多返回并发送 10 条记录</span>
        </div>
        <textarea
          v-model="querySql"
          rows="4"
          spellcheck="false"
          class="form-control"
          style="font-family: Consolas, monospace;"
          placeholder="SELECT FUND_CODE, FUND_NAME, NAV FROM T_FUND WHERE STATUS = 1 ORDER BY UPDATE_TIME DESC"
        ></textarea>
      </div>

      <div style="display: flex; justify-content: space-between; align-items: center; margin-top: 12px;">
        <span class="text-muted" style="font-size: 12px;">内置只读保护与 10 条上限保护。</span>
        <button
          type="button"
          class="btn btn-primary"
          :disabled="previewing"
          @click="handlePreviewQuery"
        >
          <IconSvg name="table" size="14" />
          <span>{{ previewing ? '正在连接查询...' : '预览查询结果' }}</span>
        </button>
      </div>
    </div>

    <!-- 2. 查询结果数据表格预览 -->
    <div v-if="previewResult" class="card">
      <div class="card-header">
        <div class="card-title">
          <IconSvg name="table" size="16" />
          <span>2. 查询结果数据预览 (最多 10 条记录)</span>
        </div>
        <span class="badge badge-info">
          返回 {{ previewResult.rowCount }} 条记录 · {{ previewResult.allColumns?.length || 0 }} 个字段
        </span>
      </div>

      <div class="table-wrap" style="max-height: 240px; margin-bottom: 16px;">
        <table class="table">
          <thead>
            <tr>
              <th style="width: 40px;">#</th>
              <th v-for="c in previewResult.allColumns" :key="c">{{ c }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!previewResult.rows?.length">
              <td :colspan="(previewResult.allColumns?.length || 0) + 1" class="text-center text-muted">
                (无查询结果)
              </td>
            </tr>
            <tr v-for="(r, idx) in previewResult.rows" :key="idx">
              <td style="color: #94a3b8; font-size: 11px;">{{ idx + 1 }}</td>
              <td v-for="c in previewResult.allColumns" :key="c">{{ r[c] ?? '-' }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 3. 手机短信发送列配置 (适合手机短信显示的限制) -->
      <div style="margin-top: 16px;">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px;">
          <label class="form-label" style="margin: 0;">短信发送列设置 (手机短信显示限制)</label>
          <span :style="{ color: selectedColumns.length > 4 ? '#dc2626' : '#0284c7', fontWeight: '600', fontSize: '12px' }">
            已选 {{ selectedColumns.length }}/4 列
          </span>
        </div>
        <div style="background: #eff6ff; border: 1px solid #bfdbfe; color: #1e40af; padding: 6px 12px; border-radius: 6px; font-size: 12px; margin-bottom: 10px;">
          ★ <strong>适合手机短信显示的限制：</strong>手机屏幕单行宽度有限，最多允许选择 <strong>4 列</strong>（推荐 2~3 列，如“代码 | 名称 | 净值”）。超出 4 列将导致排版混乱。
        </div>

        <div class="col-chips-wrap">
          <div
            v-for="col in previewResult.allColumns"
            :key="col"
            class="chip-box"
            :class="{ active: selectedColumns.includes(col) }"
          >
            <label style="display: inline-flex; align-items: center; gap: 4px; margin: 0; cursor: pointer; font-weight: 500;">
              <input
                type="checkbox"
                :checked="selectedColumns.includes(col)"
                @change="toggleCol(col, $event)"
              />
              <span>{{ col }}</span>
            </label>
            <input
              v-model="columnAliases[col]"
              type="text"
              placeholder="别名"
              class="alias-input"
              @input="updateSmsLive"
            />
          </div>
        </div>
      </div>

      <!-- 4. 手机短信排版预览 -->
      <div class="sms-preview-card" style="margin-top: 18px;">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px;">
          <strong style="font-size: 13px; color: #0f172a; display: flex; align-items: center; gap: 6px;">
            <IconSvg name="smartphone" size="14" />
            <span>手机短信排版效果模拟</span>
          </strong>
          <span style="font-size: 12px; color: #64748b;">
            共 {{ previewResult.charCount || 0 }} 字符 · 预计 {{ previewResult.smsCount || 1 }} 条短信 (70字/条)
          </span>
        </div>
        <pre class="sms-bubble">{{ previewResult.smsContent }}</pre>
      </div>
    </div>

    <!-- 5. 发送控制卡片 -->
    <div v-if="previewResult" class="card">
      <div class="card-header">
        <div class="card-title">
          <IconSvg name="send" size="16" />
          <span>3. 设置发送目标并发出</span>
        </div>
      </div>

      <div class="form-row" style="margin-bottom: 16px;">
        <div class="form-group">
          <label class="form-label">选择短信网关渠道</label>
          <select v-model="selectedChannelId" class="form-control">
            <option :value="null">-- 默认短信渠道 --</option>
            <option v-for="ch in channels" :key="ch.id" :value="ch.id">
              {{ ch.channelName }} ({{ ch.channelType.toUpperCase() }})
            </option>
          </select>
        </div>
        <div class="form-group">
          <label class="form-label">选择告警组 (多目标推送)</label>
          <select v-model="selectedGroupId" class="form-control">
            <option :value="null">-- 不指定告警组 --</option>
            <option v-for="g in groups" :key="g.id" :value="g.id">
              {{ g.group_name }}
            </option>
          </select>
        </div>
      </div>

      <div class="form-group">
        <label class="form-label">接收手机号 (可选，多个以英文逗号分隔)</label>
        <input v-model="mobiles" type="text" class="form-control" placeholder="例如：18211092191,13800138000" />
      </div>

      <div style="display: flex; justify-content: flex-end; margin-top: 18px;">
        <button
          type="button"
          class="btn btn-success"
          style="padding: 10px 24px; font-size: 14px;"
          :disabled="sending"
          @click="handleSend"
        >
          <IconSvg name="send" size="16" />
          <span>{{ sending ? '正在发送数据短信...' : '立即发送数据' }}</span>
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { api } from '../api'
import { store } from '../store'

const dbConnections = ref([])
const channels = ref([])
const groups = ref([])

const selectedConnId = ref('')
const selectedConnInfo = ref('')
const smsTitle = ref('')
const querySql = ref('SELECT FUND_CODE, FUND_NAME, NAV FROM T_FUND WHERE STATUS = 1 ORDER BY UPDATE_TIME DESC')

const previewing = ref(false)
const sending = ref(false)
const previewResult = ref(null)

const selectedColumns = ref([])
const columnAliases = reactive({})

const selectedChannelId = ref(null)
const selectedGroupId = ref(null)
const mobiles = ref('')

const handleConnChange = () => {
  const c = dbConnections.value.find((item) => String(item.id) === String(selectedConnId.value))
  if (c) {
    selectedConnInfo.value = `主机: ${c.host || c.endpoint || '-'} | 端口: ${c.port || '-'} | 库名: ${c.database_name || c.databaseName || '-'}`
  } else {
    selectedConnInfo.value = ''
  }
}

const handlePreviewQuery = async () => {
  if (!selectedConnId.value) {
    store.showToast('请先选择已维护好的数据库监控', 'error')
    return
  }
  if (!querySql.value.trim()) {
    store.showToast('请编写查询 SQL 语句', 'error')
    return
  }

  previewing.value = true
  try {
    const res = await api.dataSendPreview({
      databaseConnectionServiceId: Number(selectedConnId.value),
      databaseQuery: querySql.value.trim(),
      maxRows: 10,
      title: smsTitle.value.trim(),
      selectedColumns: selectedColumns.value.length ? selectedColumns.value : null,
      columnAliases: Object.keys(columnAliases).length ? columnAliases : null
    })

    previewResult.value = res
    if (!selectedColumns.value.length) {
      selectedColumns.value = res.selectedColumns || []
    }
    store.showToast(`查询成功：共获取 ${res.rowCount} 条数据，包含 ${res.allColumns?.length || 0} 个字段`, 'success')
  } catch (err) {
    store.showToast('查询失败：' + err.message, 'error')
  } finally {
    previewing.value = false
  }
}

const toggleCol = (col, e) => {
  if (e.target.checked) {
    if (selectedColumns.value.length >= 4) {
      e.target.checked = false
      store.showToast('适合手机短信显示的限制：最多只能选择 4 列！', 'error')
      return
    }
    selectedColumns.value.push(col)
  } else {
    selectedColumns.value = selectedColumns.value.filter((c) => c !== col)
  }
  updateSmsLive()
}

let timer = null
const updateSmsLive = () => {
  clearTimeout(timer)
  timer = setTimeout(async () => {
    if (!selectedConnId.value || !querySql.value.trim()) return
    if (!selectedColumns.value.length || selectedColumns.value.length > 4) return
    try {
      const res = await api.dataSendPreview({
        databaseConnectionServiceId: Number(selectedConnId.value),
        databaseQuery: querySql.value.trim(),
        maxRows: 10,
        title: smsTitle.value.trim(),
        selectedColumns: selectedColumns.value,
        columnAliases: columnAliases
      })
      if (previewResult.value) {
        previewResult.value.smsContent = res.smsContent
        previewResult.value.charCount = res.charCount
        previewResult.value.smsCount = res.smsCount
      }
    } catch (_) {}
  }, 300)
}

const handleSend = async () => {
  if (!selectedConnId.value || !querySql.value.trim()) {
    store.showToast('请配置数据库和查询 SQL', 'error')
    return
  }
  if (selectedColumns.value.length > 4) {
    store.showToast('适合手机短信显示的限制：最多只能选择 4 列！', 'error')
    return
  }

  sending.value = true
  try {
    const res = await api.dataSendExecute({
      databaseConnectionServiceId: Number(selectedConnId.value),
      databaseQuery: querySql.value.trim(),
      maxRows: 10,
      title: smsTitle.value.trim(),
      selectedColumns: selectedColumns.value,
      columnAliases: columnAliases,
      channelId: selectedChannelId.value ? Number(selectedChannelId.value) : null,
      alertGroupId: selectedGroupId.value ? Number(selectedGroupId.value) : null,
      mobiles: mobiles.value.trim() || null
    })
    store.showToast(`数据发送成功！共发送 ${res.rowCount} 条数据至 ${res.recipientSummary}`, 'success')
  } catch (err) {
    store.showToast('发送失败：' + err.message, 'error')
  } finally {
    sending.value = false
  }
}

onMounted(async () => {
  try {
    const [connList, chList, grList] = await Promise.all([
      api.databaseConnections(true).catch(() => []),
      api.listAlertChannels(false).catch(() => []),
      api.listAlertGroups(false).catch(() => [])
    ])
    dbConnections.value = connList || []
    channels.value = chList || []
    groups.value = grList || []
  } catch (err) {
    store.showToast('加载数据库监控列表失败：' + err.message, 'error')
  }
})
</script>

<style scoped>
.back-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: var(--text-muted);
}
.back-link:hover {
  color: var(--primary-color);
}

.conn-info-bar {
  margin-top: 6px;
  font-size: 12px;
  color: #64748b;
  background: #f8fafc;
  padding: 4px 8px;
  border-radius: 4px;
  border: 1px solid #e2e8f0;
}

.col-chips-wrap {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.chip-box {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: #ffffff;
  border: 1px solid #cbd5e1;
  padding: 4px 8px;
  border-radius: 6px;
  font-size: 13px;
}
.chip-box.active {
  border-color: #0284c7;
  background: #f0f9ff;
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
}
.sms-bubble {
  margin: 0;
  padding: 12px;
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
</style>
