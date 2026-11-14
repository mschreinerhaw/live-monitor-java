<template>
    <DetailDrawer :open="editing" id="ds-config-title" presentation="modal" :show-footer="false" close-label="关闭配置" :title="!form.id ? '新增 DolphinScheduler 连接' : modalMode === 'alerts' ? '告警配置' : '连接设置'" @close="closeEditor"><section v-if="editing" class="dolphin-config"><form @submit.prevent="save"><div v-show="modalMode !== 'alerts'"><div class="form-row"><label class="form-group">连接名称<input v-model="form.service_name" required maxlength="120" class="form-control"></label><label class="form-group">接口根地址<input v-model="form.config.base_url" type="url" required class="form-control" placeholder="http://scheduler:12345/dolphinscheduler"></label><label class="form-group">Token<input v-model="token" type="password" autocomplete="new-password" :required="!form.id" class="form-control" placeholder="编辑时留空保留原 Token"></label></div><div class="form-row"><label class="form-group">项目编码（逗号分隔，留空监控全部可访问项目）<input v-model="form.config.project_codes" class="form-control" placeholder="6819918706304"></label><label class="form-group">滚动检查窗口（小时）<input v-model.number="form.config.window_hours" required type="number" min="1" max="744" class="form-control"></label><label class="form-group">检查间隔（秒）<input v-model.number="form.check_interval" required type="number" min="30" max="86400" class="form-control"></label></div></div><div v-show="modalMode !== 'connection'"><div class="form-row"><label class="form-group">异常状态（逗号分隔）<input v-model="form.config.alert_states" required class="form-control"><span class="form-hint">支持 FAILURE、STOP、KILL、PAUSE、NEED_FAULT_TOLERANCE</span></label><label class="form-group">运行超时（分钟，0 关闭）<input v-model.number="form.config.running_timeout_minutes" required type="number" min="0" max="10080" class="form-control"></label></div><label class="check-label"><input v-model="form.config.alert_on_empty" type="checkbox">窗口内无实例时视为检查异常（UNKNOWN）</label><ServiceAlertFields :form="form" :groups="groups" /><p class="form-hint">启用项目告警后按项目与异常分别通知；未配置项目接收组时使用默认运维组。发送失败与重试可追踪。Token 加密保存，编辑时留空保留。</p></div><p v-if="saveError" role="alert" class="inline-error">{{ saveError }}</p><div class="page-actions"><button class="btn btn-primary" :disabled="saving">{{ saving ? '正在保存…' : modalMode === 'alerts' ? '保存告警配置' : '保存连接' }}</button><button type="button" class="btn btn-ghost" @click="closeEditor">取消</button></div></form></section></DetailDrawer>

</template>
<script setup>
import {ref,watch} from 'vue'
import {api} from '../api'
import DetailDrawer from './DetailDrawer.vue'
import ServiceAlertFields from './ServiceAlertFields.vue'
const props=defineProps({open:Boolean,connection:Object,groups:Array,mode:String})
const emit=defineEmits(['close','saved'])
const editing=ref(false),token=ref(''),saving=ref(false),saveError=ref(''),form=ref({}),modalMode=ref('new')
watch(()=>[props.open,props.connection,props.mode],()=>{
 editing.value=props.open;if(!props.open)return
 modalMode.value=props.connection?props.mode || 'connection':'new';token.value='';saveError.value=''
 if(props.connection){let config={};try{config=JSON.parse(props.connection.config_json || '{}')}catch{}form.value={...props.connection,config,alert_group_ids:[...(props.connection.alert_group_ids || [])]}}
 else form.value={service_name:'DolphinScheduler',service_type:'dolphinscheduler',enabled:true,service_alert_enabled:true,check_interval:60,service_consecutive_failures:1,service_recover_successes:1,unknown_alert_enabled:true,unknown_consecutive_failures:1,alert_repeat_interval_seconds:300,alert_group_ids:[],config:{base_url:'',project_codes:'',window_hours:24,alert_states:'FAILURE,STOP,KILL',running_timeout_minutes:180,alert_on_empty:false,enterprise_enabled:true,enterprise_alerts_enabled:true}}
},{immediate:true})
function closeEditor(){if(!saving.value){token.value='';emit('close')}}
async function save(){saving.value=true;saveError.value='';try{
 const data={...form.value,service_type:'dolphinscheduler',check_interval_unit:'seconds',check_interval_value:form.value.check_interval};delete data.check_timeout_seconds;delete data.secret_config_json
 if(token.value.trim())data.secret_config={token:token.value.trim()}
 const result=await api.saveDolphinConnection(form.value.id,data);token.value='';emit('saved',result.id);emit('close')
}catch(e){saveError.value=e.message}finally{saving.value=false}}
</script>
<style scoped>
.form-row{display:flex;flex-wrap:wrap;gap:16px}.form-group{display:flex;flex-direction:column;gap:8px;font-size:13px;flex:1;min-width:200px}.check-label{display:flex;gap:8px;margin:16px 0}.page-actions{margin-top:20px}
</style>
