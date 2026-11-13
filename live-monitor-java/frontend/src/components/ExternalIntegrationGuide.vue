<template>
  <section ref="section" class="card integration-guide" aria-labelledby="external-integration-title">
    <div class="card-header"><div><h2 id="external-integration-title" tabindex="-1" class="card-title">外部消息接入说明</h2><p class="form-hint">配置项目与接收组后，从外部系统发送 JSON 消息即可触发转发。</p></div><span class="badge badge-info">HTTP POST</span></div>
    <ol class="integration-steps"><li><strong>配置接收</strong><span>启用项目，设置匹配关键字，并绑定可用的告警组。</span></li><li><strong>发送消息</strong><span>消息中包含项目关键字，按下方示例调用接口。</span></li><li><strong>核对结果</strong><span>检查响应的 success 字段，并在“外部消息请求”子菜单查看渠道结果。</span></li></ol>
    <label class="filter-label">接口地址<div class="endpoint-row"><input :value="endpoint" class="form-control" readonly aria-label="外部消息接口地址"><button type="button" class="btn btn-ghost" @click="copy(endpoint, '接口地址')">复制地址</button></div></label>
    <p class="form-hint">请求头：Content-Type: application/json。当前转发接口无需登录会话；项目和渠道管理操作仍需登录。外部调用方需要能够访问此地址。</p>
    <div class="example-toolbar"><label class="filter-label">示例项目<select v-model="projectId" class="form-control" aria-label="示例项目"><option v-if="!projects.length" value="">请先新建外部项目</option><option v-for="project in projects" :key="project.id" :value="String(project.id)">{{ project.project_name }}{{ project.enabled ? '' : '（已停用）' }}</option></select></label><span v-if="selected" class="form-hint">匹配关键字：{{ selected.match_keyword || selected.project_name }}</span></div>
    <p v-if="!selected || !selected.enabled" class="integration-note" role="status">{{ selected ? '当前项目已停用，请启用后再调用。' : '请先新建并启用项目、绑定告警组；下方占位关键字需替换为实际配置。' }}</p>
    <div class="example-heading"><div role="tablist" aria-label="调用示例语言" class="example-tabs"><button v-for="(label, key) in languages" :id="'example-tab-' + key" :key="key" type="button" role="tab" :aria-selected="language === key" aria-controls="external-code-example" :tabindex="language === key ? 0 : -1" :class="['btn', language === key ? 'btn-primary' : 'btn-ghost']" @click="language = key" @keydown="moveTab($event, key)">{{ label }}</button></div><button type="button" class="btn btn-ghost btn-sm" @click="copy(example, '调用示例')">复制示例</button></div>
    <div id="external-code-example" role="tabpanel" :aria-labelledby="'example-tab-' + language" tabindex="0" class="example-code"><pre>{{ example }}</pre></div>
    <p class="form-hint">示例使用当前访问域名和所选项目的关键字。复制命令到外部调用方执行后，会向项目接收组发送真实消息。</p>
    <details class="integration-details"><summary>请求格式与项目匹配规则</summary><ul><li>推荐发送 {"content":"包含项目关键字的消息"}；也支持 message、text 字段或其他合法 JSON。展示内容按 message、text、content 的顺序读取。</li><li>项目按完整请求内容匹配启用项目的关键字；多个项目同时匹配时，优先关键字更长的项目，只转发到一个项目。</li><li>匹配关键字留空时使用项目名称。接口总开关、项目、告警组和渠道都需要启用。</li></ul></details>
    <details class="integration-details"><summary>如何判断发送成功与排查错误</summary><p>HTTP 200 仅表示返回了业务结果；还需确认 success 为 true。success_count、failed_count 和 records 说明各渠道结果。渠道受理成功不代表收件人已读。</p><pre class="response-example">{{ responseExample }}</pre><p class="form-hint">以上为主要字段示例，以实际响应为准；完整响应还包含 records 等字段。</p><div class="table-wrap"><table class="table"><thead><tr><th>返回结果</th><th>含义与处理方式</th></tr></thead><tbody><tr><td>400</td><td>请求为空或不是合法 JSON，检查请求体及 Content-Type。</td></tr><tr><td>413</td><td>提取后的消息超过 100000 个字符，请缩短消息内容。</td></tr><tr><td>403</td><td>消息转发总开关已关闭，请在项目列表上方启用。</td></tr><tr><td>422</td><td>没有匹配到启用项目，或匹配项目未绑定告警组；检查关键字和绑定关系。</td></tr><tr><td>503</td><td>外部消息转发服务不可用，请检查系统配置。</td></tr><tr><td>200 / success: false</td><td>渠道未发送成功，检查 failed_count、records 和“外部消息请求”子菜单中的错误详情。</td></tr></tbody></table></div></details>
  </section>
</template>
<script setup>
import { ref, computed, watch, nextTick } from 'vue'
import { store } from '../store'
const props = defineProps({ projects: { type: Array, default: () => [] } })
const section = ref(null), projectId = ref(''), language = ref('curl')
const languages = { curl: 'cURL', json: 'JSON 请求体', python: 'Python', powershell: 'PowerShell' }
const endpoint = window.location.origin + '/api/external/messages'
const selected = computed(() => props.projects.find(project => String(project.id) === projectId.value))
watch(() => props.projects, projects => { if (!projects.some(project => String(project.id) === projectId.value)) projectId.value = String((projects.find(project => project.enabled) || projects[0])?.id || '') }, { immediate: true })
const payload = computed(() => ({ content: `项目名称:${selected.value?.match_keyword || selected.value?.project_name || '你的项目关键字'},流程名称:外部消息接入验证,执行状态:failure,状态类型:失败,消息:请检查任务运行情况` }))
const jsonBody = computed(() => JSON.stringify(payload.value, null, 2))
const shellQuote = value => "'" + value.replace(/'/g, "'\\''") + "'"
const example = computed(() => {
  if (language.value === 'json') return jsonBody.value
  if (language.value === 'python') return `import json\nimport requests\n\npayload = json.loads(${JSON.stringify(JSON.stringify(payload.value))})\nresponse = requests.post(\n    ${JSON.stringify(endpoint)},\n    json=payload,\n    timeout=120,\n)\nresponse.raise_for_status()\nresult = response.json()\nif not result.get("success"):\n    raise RuntimeError(result)\nprint(result)`
  if (language.value === 'powershell') return `$payload = @'\n${jsonBody.value}\n'@\n$body = [System.Text.Encoding]::UTF8.GetBytes($payload)\n$result = Invoke-RestMethod -Method Post \u0060\n  -Uri '${endpoint.replace(/'/g, "''")}' \u0060\n  -ContentType 'application/json; charset=utf-8' \u0060\n  -Body $body -TimeoutSec 120\nif (-not $result.success) { throw ($result | ConvertTo-Json -Depth 10) }\n$result | ConvertTo-Json -Depth 10`
  return `curl --request POST ${shellQuote(endpoint)} \\\n  --header 'Content-Type: application/json' \\\n  --data-raw ${shellQuote(jsonBody.value)}`
})
const responseExample = computed(() => JSON.stringify({ success: true, project_id: selected.value?.id || 1, project_name: selected.value?.project_name || '示例项目', delivery_count: 1, success_count: 1, failed_count: 0 }, null, 2))
async function copy(value, label) {
  try {
    try { if (!navigator.clipboard?.writeText) throw new Error('Clipboard unavailable'); await navigator.clipboard.writeText(value) }
    catch (_) {
      const focus = document.activeElement, textarea = document.createElement('textarea')
      textarea.value = value; textarea.readOnly = true; textarea.style.cssText = 'position:fixed;left:-9999px;top:0'
      document.body.appendChild(textarea)
      try { textarea.select(); if (!document.execCommand('copy')) throw new Error('Copy denied') }
      finally { textarea.remove(); if (focus?.isConnected) focus.focus() }
    }
    store.showToast(label + '已复制', 'success')
  } catch (_) { store.showToast('复制失败，请手动选择示例文本', 'error') }
}
function moveTab(event, key) {
  const keys = Object.keys(languages), index = keys.indexOf(key)
  const target = event.key === 'ArrowRight' ? (index + 1) % keys.length : event.key === 'ArrowLeft' ? (index + keys.length - 1) % keys.length : event.key === 'Home' ? 0 : event.key === 'End' ? keys.length - 1 : -1
  if (target < 0) return
  event.preventDefault(); language.value = keys[target]; nextTick(() => document.getElementById('example-tab-' + language.value)?.focus())
}
async function reveal(id) { if (id != null) projectId.value = String(id); await nextTick(); section.value?.scrollIntoView({ block: 'start', behavior: 'smooth' }); section.value?.querySelector('h2')?.focus({ preventScroll: true }) }
defineExpose({ reveal })
</script>
<style scoped>
.integration-guide { scroll-margin-top: 76px; }
.integration-steps { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 20px; padding-left: 20px; margin: 0 0 20px; }
.integration-steps strong { display: block; margin-bottom: 4px; }
.integration-steps span { color: var(--text-muted); font-size: 12px; }
.endpoint-row { display: flex; gap: 8px; }
.endpoint-row input { flex: 1; font-family: monospace; }
.example-toolbar { display: flex; flex-wrap: wrap; align-items: end; gap: 12px; margin: 20px 0 16px; }
.example-toolbar label { width: min(100%,360px); }
.example-heading, .example-tabs { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.example-heading { justify-content: space-between; margin-bottom: 10px; }
.example-code { background: #0f172a; color: #e2e8f0; padding: 16px; border-radius: 8px; max-width: 100%; overflow-x: auto; }
.example-code pre { font: 12px/1.8 Consolas,monospace; white-space: pre-wrap; overflow-wrap: anywhere; }
.integration-note { padding: 10px 12px; background: #fffbeb; color: #92400e; border-radius: 6px; margin-bottom: 16px; }
.integration-details { margin-top: 20px; border-top: 1px solid var(--border-color); padding-top: 16px; }
.integration-details summary { cursor: pointer; font-weight: 600; }
.integration-details ul { margin: 12px 0; padding-left: 20px; }
.integration-details p { margin: 12px 0; }
.response-example { background: #f8fafc; border-radius: 8px; padding: 16px; white-space: pre-wrap; overflow-wrap: anywhere; }
@media(max-width: 640px) { .integration-steps { grid-template-columns: 1fr; gap: 12px; } .endpoint-row { flex-wrap: wrap; } .endpoint-row input { flex-basis: 100%; } }
</style>
