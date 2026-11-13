<template>
  <section v-if="http || database" class="card"><h2>断言、认证与查询预览</h2>
    <template v-if="http"><div class="form-row"><label class="form-group">认证方式<select v-model="form.api_auth_type" class="form-control"><option value="none">无认证</option><option value="basic">Basic</option><option value="bearer">Bearer</option><option value="custom_header">自定义认证头</option></select></label><label class="form-group">Content-Type<input v-model="form.api_content_type" class="form-control" placeholder="application/json"></label></div>
      <div v-if="form.api_auth_type === 'basic'" class="form-row"><label class="form-group">Basic 用户名<input v-model="form.api_basic_username" class="form-control"></label><label class="form-group">Basic 密码<input v-model="form.api_basic_password" type="password" autocomplete="new-password" class="form-control" placeholder="编辑时留空保留原密码"></label></div>
      <label v-if="form.api_auth_type === 'bearer'" class="form-group">Bearer Token<input v-model="form.api_bearer_token" type="password" class="form-control" placeholder="编辑时留空保留原 Token"></label>
      <p v-if="form.api_auth_type === 'custom_header'">在上方请求头列表中配置认证头。</p>
      <div class="form-row"><label class="form-group">响应时间上限（毫秒，可留空）<input v-model.number="form.api_response_time_ms" type="number" min="1" class="form-control"></label><label class="form-group">文本断言模式<select v-model="form.api_text_assertion_mode" class="form-control"><option value="contains">包含</option><option value="not_contains">不包含</option></select></label><label class="form-group">文本断言内容<input v-model="form.api_text_assertion_value" class="form-control"></label></div>
      <label class="form-group">JSON 断言<textarea v-model="form.api_json_assertions" class="form-control" placeholder="JSON 断言规则"></textarea></label>
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
  </section>
</template>
<script setup>
import { computed, ref, reactive } from 'vue'
import { api } from '../api'
const props = defineProps({ form: { type: Object, required: true }, connections: { type: Array, default: () => [] }, serviceId: { type: [String, Number], default: null } })
const http = computed(() => ['web','api','nginx'].includes(props.form.service_type)), database = computed(() => ['mysql','oracle','postgresql','postgres','jdbc'].includes(props.form.service_type))
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
