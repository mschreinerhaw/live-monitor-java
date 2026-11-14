<template>
  <section v-if="http || database" class="card service-advanced" :class="{ 'http-advanced': http }">
    <h2 class="advanced-heading"><button type="button" class="advanced-toggle" :aria-expanded="advancedOpen" aria-controls="service-advanced-fields" @click="advancedOpen = !advancedOpen"><span><span class="advanced-title">高级参数</span><span class="advanced-hint">{{ http ? '认证、响应断言与表达式测试，按需配置' : '查询预览、跨数据库对照与断言表达式，按需配置' }}</span></span><span class="advanced-toggle-label">{{ advancedOpen ? '收起' : '展开' }}<IconSvg name="chevron" :class="{ rotated: advancedOpen }" size="16" /></span></button></h2>
    <div id="service-advanced-fields" v-show="advancedOpen" @invalid.capture="advancedOpen = true">
    <template v-if="http"><div class="advanced-section"><h3>请求与认证</h3><div class="form-row"><label class="form-group">认证方式<select v-model="form.api_auth_type" class="form-control"><option value="none">无认证</option><option value="basic">Basic</option><option value="bearer">Bearer</option><option value="custom_header">自定义认证头</option></select></label><label class="form-group">Content-Type<input v-model="form.api_content_type" class="form-control" placeholder="application/json"></label></div>
      <div v-if="form.api_auth_type === 'basic'" class="form-row"><label class="form-group">Basic 用户名<input v-model="form.api_basic_username" class="form-control"></label><label class="form-group">Basic 密码<input v-model="form.api_basic_password" type="password" autocomplete="new-password" class="form-control" placeholder="编辑时留空保留原密码"></label></div>
      <label v-if="form.api_auth_type === 'bearer'" class="form-group">Bearer Token<input v-model="form.api_bearer_token" type="password" class="form-control" placeholder="编辑时留空保留原 Token"></label>
      <p v-if="form.api_auth_type === 'custom_header'">在上方请求头列表中配置认证头。</p>
      </div><div class="advanced-section"><h3>响应断言</h3><p class="form-hint">以下条件可留空；配置后将与基础探测条件一同判断服务状态。</p>
      <div class="form-row assertion-row"><label class="form-group">响应时间上限（毫秒，可留空）<input v-model.number="form.api_response_time_ms" type="number" min="1" class="form-control"></label><label class="form-group">文本断言模式<select v-model="form.api_text_assertion_mode" class="form-control"><option value="contains">包含</option><option value="not_contains">不包含</option></select></label><label class="form-group">文本断言内容<input v-model="form.api_text_assertion_value" class="form-control"></label></div>
      <label class="form-group">JSON 断言<textarea v-model="form.api_json_assertions" class="form-control" placeholder="JSON 断言规则"></textarea></label>
      </div>
    </template>
    <template v-if="database"><div class="form-row"><label class="form-group">数据库结果匹配<select v-model="form.database_result_operator" class="form-control"><option v-for="operator in ['fuzzy','exact','gt','lt','eq','json','regex']" :key="operator">{{ operator }}</option></select></label><label class="form-group">预期结果<input v-model="form.expected_result" class="form-control"></label></div>
      <label class="form-group">参与断言的字段（逗号分隔）<input v-model="assertionFields" class="form-control"></label><button type="button" class="btn btn-ghost" :disabled="busy" @click="preview">查询预览</button>
      <div v-if="previewResult" class="table-wrap"><table class="table"><thead><tr><th v-for="column in previewResult.columns" :key="column">{{ column }}</th></tr></thead><tbody><tr v-for="(row,index) in previewResult.rows" :key="index"><td v-for="column in previewResult.columns" :key="column">{{ row[column] }}</td></tr></tbody></table></div>
      <h3>跨数据库对照查询</h3><div v-for="(item,index) in form.cross_database_queries" :key="index" class="card"><label>数据源<select v-model="item.source_service_id" required class="form-control"><option v-for="source in connections" :key="source.id" :value="source.id">{{ source.service_name }}</option></select></label><label>查询别名<input v-model="item.alias" required class="form-control"></label><label>只读查询 SQL<textarea v-model="item.database_query" class="form-control"></textarea></label><label>参与断言的字段（逗号分隔）<input :value="(item.assertion_fields || []).join(', ')" @input="item.assertion_fields = $event.target.value.split(',').map(value => value.trim()).filter(Boolean)" class="form-control"></label>
<label>字段映射（JSON 对象）<textarea :value="JSON.stringify(item.field_mapping || {}, null, 2)" @input="mapping(item, $event)" class="form-control"></textarea></label><button type="button" class="btn btn-ghost" @click="form.cross_database_queries.splice(index,1)">删除查询</button></div><button type="button" class="btn btn-ghost" @click="form.cross_database_queries.push({ source_service_id: null, alias: 'db' + (form.cross_database_queries.length + 1), database_query: '', assertion_fields: [], field_mapping: {} })">添加对照数据库</button>
    </template>
    <label class="form-group">断言表达式<textarea v-model="form.api_assertion_expression" class="form-control" placeholder="例如：status_code == 200"></textarea></label>
    <details><summary>测试断言表达式</summary><label>模拟响应状态<input v-model.number="sample.status_code" type="number" min="100" max="599" class="form-control"></label><label>模拟响应耗时（毫秒）<input v-model.number="sample.response_time_ms" type="number" min="0" class="form-control"></label><label>响应正文<textarea v-model="sample.body" class="form-control"></textarea></label><button type="button" class="btn btn-ghost" :disabled="busy" @click="testRule">测试规则</button><pre v-if="ruleResult" style="white-space:pre-wrap">{{ JSON.stringify(ruleResult,null,2) }}</pre></details>
    <p v-if="error" role="alert">{{ error }}</p>
    </div>
  </section>
</template>
<script setup>
import { computed, ref, reactive, watch } from 'vue'
import { api } from '../api'
import IconSvg from './IconSvg.vue'
const props = defineProps({ form: { type: Object, required: true }, connections: { type: Array, default: () => [] }, serviceId: { type: [String, Number], default: null } })
const http = computed(() => ['web','api','nginx'].includes(props.form.service_type)), database = computed(() => ['mysql','oracle','postgresql','postgres','jdbc'].includes(props.form.service_type))
const advancedOpen = ref(false)
watch(() => props.form.service_type, () => { advancedOpen.value = false })
const busy=ref(false),error=ref(''),previewResult=ref(null),ruleResult=ref(null),sample=reactive({status_code:200,response_time_ms:10,body:'{}'})
const assertionFields=computed({get:()=> (props.form.database_assertion_fields || []).join(', '),set:value=>{props.form.database_assertion_fields=value.split(',').map(s=>s.trim()).filter(Boolean)}})
function mapping(item, event) {
  try {
    const value = JSON.parse(event.target.value || '{}')
    if (!value || Array.isArray(value) || typeof value !== 'object' || Object.values(value).some(item => typeof item !== 'string')) throw new Error('字段映射必须是字符串值的 JSON 对象')
    item.field_mapping = value
    event.target.setCustomValidity('')
    error.value = ''
  } catch (e) {
    event.target.setCustomValidity(e.message)
    error.value = e.message
  }
}
async function preview(){busy.value=true;error.value='';try{const payload={...props.form};previewResult.value=props.serviceId?await api.existingServiceDatabasePreview(props.serviceId,payload):await api.databasePreview(payload)}catch(e){error.value=e.message}finally{busy.value=false}}
async function testRule(){busy.value=true;error.value='';try{ruleResult.value=await api.testRule({...sample,expression:props.form.api_assertion_expression})}catch(e){error.value=e.message}finally{busy.value=false}}
</script>
<style scoped>
.advanced-heading { margin: 0; }
.advanced-toggle { display: flex; align-items: center; justify-content: space-between; gap: 16px; width: 100%; padding: 0; border: 0; background: transparent; text-align: left; color: inherit; cursor: pointer; font: inherit; }
.advanced-title { display: block; font-size: 18px; font-weight: 600; }
.advanced-hint { display: block; margin-top: 6px; color: #64748b; font-size: 13px; font-weight: 400; line-height: 1.6; }
.advanced-toggle-label { display: inline-flex; align-items: center; gap: 6px; flex-shrink: 0; color: #0284c7; font-size: 13px; font-weight: 500; }
.advanced-toggle-label .rotated { transform: rotate(180deg); }
.advanced-toggle:focus-visible { outline: 2px solid #0284c7; outline-offset: 6px; border-radius: 4px; }
.service-advanced #service-advanced-fields { margin-top: 20px; padding-top: 20px; border-top: 1px solid #e2e8f0; }
.advanced-section { margin-bottom: 20px; }
.advanced-section h3 { margin: 0 0 12px; font-size: 14px; font-weight: 600; }
.advanced-section .form-hint { margin: -4px 0 14px; }
.http-advanced .form-row { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.http-advanced .assertion-row { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.http-advanced .form-group { display: flex; flex-direction: column; gap: 8px; margin-bottom: 16px; font-size: 13px; font-weight: 500; }
.http-advanced textarea { min-height: 84px; font-family: Consolas, monospace; font-weight: 400; }
.http-advanced details { padding: 14px 16px; border: 1px solid #e2e8f0; border-radius: 8px; background: #f8fafc; }
.http-advanced details summary { cursor: pointer; font-size: 13px; font-weight: 500; }
.http-advanced details[open] summary { margin-bottom: 16px; }
.http-advanced details label { display: block; margin-bottom: 12px; font-size: 13px; }
@media(max-width: 640px) { .http-advanced .form-row { grid-template-columns: minmax(0, 1fr); gap: 0; } }
</style>
