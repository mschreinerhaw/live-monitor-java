<template>
  <section class="ops-panel install-wizard">
    <h2>安装 Runtime OS</h2><p>上传完整安装包，按步骤完成安装。目标主机：{{ host?.name || host?.id || '尚未选择' }}</p>
    <ol class="install-steps"><li v-for="(label, index) in labels" :key="label" :class="{current:step===index,done:step>index}">{{ index+1 }} {{ label }}</li></ol>
    <p v-if="error" role="alert" class="ops-error">{{ error }} <button v-if="task" @click="downloadDiagnostic">下载诊断记录</button></p>
    <form v-if="step===0" class="ops-form" @submit.prevent="upload"><label>Runtime OS 安装包<input type="file" accept=".tar,.tar.gz,.tgz" required :disabled="working" @change="file=$event.target.files[0]"></label><p>使用发行方提供的完整安装包，包含业务镜像、依赖、初始化配置及签名；普通单镜像包请在镜像列表导入。</p><button class="btn btn-primary" :disabled="working || !host || !permitted(host.id,'install_upload')">{{ working ? '正在上传和检查安装包…' : '上传并继续' }}</button></form>
    <form v-if="step===1 && metadata" class="ops-form" @submit.prevent="plan"><h3>{{ metadata.name }} · {{ metadata.version }}</h3><p>包含 {{ metadata.services.length }} 个组件，密码与内部凭据自动生成。</p><div class="ops-grid"><label v-for="[key, field] in visibleFields" :key="key">{{ field.label || key }}<input v-model="values[key]" :type="field.type==='integer'?'number':field.type==='secret'?'password':'text'" :required="field.required!==false" :min="field.min" :max="field.max" :maxlength="field.max_length || 8192" autocomplete="off"></label></div><div class="ops-actions"><button class="btn btn-primary" :disabled="working || !permitted(host.id,'install_plan')">{{ working ? '正在检查环境…' : '下一步：检查环境' }}</button><button type="button" :disabled="working" @click="reset">重新选择安装包</button></div></form>
    <div v-if="step===2 && planResult"><h3>安装前检查</h3><ul class="install-checks"><li v-for="(check,index) in planResult.checks" :key="index"><span :class="check.passed?'passed':'failed'">{{ check.passed?'通过':'未通过' }}</span>{{ checkLabels[check.code] || check.code }} {{ check.port || '' }}</li></ul><p>安装版本：{{ metadata.version }} · 组件：{{ metadata.services.length }} 个</p><p>数据保存在独立目录，不接管或覆盖已有安装。</p><p v-if="metadata.capabilities?.includes('docker_socket')">此安装包将启用容器执行能力，相关组件需要访问主机 Docker 运行时。</p><p v-if="metadata.capabilities?.includes('host_identity')">使用本机标识完成产品授权识别。</p><label v-if="planResult.ready"><input v-model="confirmed" type="checkbox">确认在这台主机安装并启动上述组件</label><div class="ops-actions"><button class="btn btn-primary" :disabled="working || !planResult.ready || !confirmed || !permitted(host.id,'install_apply')" @click="apply">开始安装</button><button :disabled="working" @click="step=1">返回修改配置</button></div></div>
    <div v-if="step===3"><h3>{{ result ? '安装完成' : failed ? '安装未完成' : '正在安装' }}</h3><p v-if="!result">{{ statusMessage }}</p><p v-if="failed">当前数据与诊断记录已保留，请核对失败原因后处理，平台不会自动删除数据重新安装。</p><div v-if="result"><p>组件已完成健康检查，已接入容器监控和既有自动恢复策略。</p><div v-for="entry in result.entrypoints" :key="entry.url"><a :href="entry.url" target="_blank" rel="noopener noreferrer">{{ entry.label || '打开 Runtime OS' }} ↗</a></div><button class="btn btn-primary" @click="emit('completed')">查看容器</button></div></div>
    <div v-if="task && working" aria-live="polite"><progress :value="task.progress" max="100"></progress><p>{{ statusMessage }} · {{ task.progress }}%</p></div>
  </section>
</template>
<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { opsApi } from '../api/ops'
import { operationKey, isTerminal } from '../ops/model'
import { messageLabel } from '../ops/presentation'
import { installationFields, installationParameters, installationError, installationDiagnostic } from '../ops/installerModel'
const props=defineProps({host:Object,permitted:Function,follow:Function,refresh:Function}),emit=defineEmits(['completed'])
const labels=['上传安装包','确认配置','检查与确认','安装完成'],step=ref(0),file=ref(null),working=ref(false),error=ref(''),metadata=ref(null),values=reactive({}),planResult=ref(null),task=ref(null),confirmed=ref(false),result=ref(null),failed=ref(false)
const checkLabels={DOCKER_READY:'Docker 与 Compose',MEMORY:'主机内存',AVAILABLE_MEMORY:'当前可用内存',CPU:'处理器',DISK:'可用磁盘',ARCHITECTURE:'处理器架构',PORT:'访问端口',PROJECT_AVAILABLE:'安装位置未被占用'}
const visibleFields=computed(()=>installationFields(metadata.value?.parameters))
const statusMessage=computed(()=>messageLabel(task.value?.message || 'CONNECTING'))
const storageKey='runtime-install.'+(props.host?.id||'');let disposed=false
onBeforeUnmount(()=>{disposed=true})
function persist(){if(task.value)localStorage.setItem(storageKey,JSON.stringify({id:metadata.value?.installation_id||'',taskId:task.value.id}))}
function setMetadata(value){metadata.value=value;for(const [key,field] of Object.entries(value.parameters||{}))if(field.type!=='generated')values[key]=field.default??''}
function finish(value){
 const output=JSON.parse(value.result_json||'{}')
 if(value.action==='install_upload'){setMetadata(output);step.value=1}
 if(value.action==='install_plan'){planResult.value=output;Object.assign(values,output.configuration||{});confirmed.value=false;step.value=2}
 if(value.action==='install_apply'){result.value=output;step.value=3;props.refresh()}
 persist()
}
async function follow(value){task.value=value;props.follow(value);persist();const deadline=Date.now()+1500000
 while(!isTerminal(task.value.status)){if(disposed)return; if(Date.now()>deadline)throw new Error('任务仍在运行，请重新打开向导核对结果；不要重复提交安装');await new Promise(resolve=>setTimeout(resolve,1500));if(disposed)return;task.value=await opsApi.task(value.id)}
 if(task.value.status!=='SUCCEEDED')throw new Error(task.value.message||'安装任务未成功')
 finish(task.value)
}
async function guarded(action){working.value=true;error.value='';failed.value=false;try{await action()}catch(e){error.value=installationError(e.message);failed.value=step.value===3;persist()}finally{working.value=false}}
function upload(){return guarded(async()=>{if(!file.value||!props.host)return;const body=new FormData();body.append('file',file.value);body.append('host_id',props.host.id);body.append('confirmation',`install_upload:${props.host.id}:${props.host.environment}`);body.append('idempotency_key',operationKey());await follow(await opsApi.uploadInstaller(body))})}
function plan(){return guarded(async()=>{await follow(await opsApi.planInstaller(metadata.value.installation_id,{parameters:installationParameters(metadata.value.parameters,values),idempotency_key:operationKey()}))})}
function downloadDiagnostic(){const value=installationDiagnostic(metadata.value,task.value,planResult.value),url=URL.createObjectURL(new Blob([JSON.stringify(value,null,2)],{type:'application/json'})),link=document.createElement('a');link.href=url;link.download='runtime-os-install-diagnostic.json';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000)}
function apply(){if(!confirmed.value)return;step.value=3;return guarded(async()=>{await follow(await opsApi.applyInstaller(metadata.value.installation_id,{plan_id:planResult.value.plan_id,confirmation:`install_apply:${metadata.value.installation_id}:${props.host.environment}`,idempotency_key:operationKey()}))})}
function reset(){localStorage.removeItem(storageKey);metadata.value=null;task.value=null;result.value=null;planResult.value=null;step.value=0;error.value=''}
onMounted(async()=>{try{const saved=JSON.parse(localStorage.getItem(storageKey)||'null');if(!saved)return;const installations=await opsApi.list('installer'),existing=installations.find(item=>item.id===saved.id);if(existing)setMetadata({...existing,installation_id:existing.id});if(saved.taskId)await guarded(()=>followFromSaved(saved.taskId))}catch(e){error.value=e.message}})
async function followFromSaved(id){const value=await opsApi.task(id);if(value.action==='install_apply')step.value=3;await follow(value)}
</script>
<style scoped>
.install-steps{display:flex;padding:0;gap:12px;list-style:none;margin:24px 0;font-size:13px;flex-wrap:wrap}.install-steps li{padding:8px 12px;border-radius:6px;background:#edf1f5;color:#73819b}.install-steps li.current{background:#e0f2fe;color:#0369a1}.install-steps li.done{color:#047857}.install-checks{list-style:none;padding:0}.install-checks li{padding:10px 0;border-bottom:1px solid #edf1f5}.install-checks span{margin-right:16px}.passed{color:#047857}.failed{color:#b91c1c}.install-wizard p{font-size:13px;line-height:1.7;color:#64748b}.install-wizard h3{font-size:16px}.install-wizard a{display:inline-block;margin:10px 0}
</style>
