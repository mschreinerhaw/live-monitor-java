<template>
  <section class="execution-overview" aria-label="ETL_Date 执行总览">
    <header><h2>ETL_Date 执行总览</h2><span>实例状态分布</span></header>
    <div class="execution-visual">
      <div class="execution-flow">
        <div class="flow-stage planned" title="需要业务应执行计划，不能用上线定义数代替"><span>应执行工作流</span><strong>{{ number(dashboard.expected_workflows) }}</strong><small>业务计划口径</small></div>
        <span class="flow-separator">已采集执行 ↓</span>
        <button class="flow-stage executed" :disabled="pending || total == null" @click="$emit('metric', {key:'total',state:''})"><span>已执行实例</span><strong>{{ number(total) }}</strong><small>包含重跑、补数</small></button>
        <span class="flow-separator">其中成功 ↓</span>
        <button class="flow-stage succeeded" :disabled="pending || stats.success == null" @click="$emit('metric', {key:'success',state:'success'})"><span>成功实例</span><strong>{{ number(stats.success) }}</strong></button>
      </div>
      <div class="execution-bars">
        <div class="bar-column-labels"><span>执行状态 / 数量</span><span>占已执行实例</span></div>
        <button v-for="m in rows" :key="m.key" :class="['metric', 'execution-bar', 'bar-' + m.key]" :disabled="pending || m.value == null" @click="$emit('metric', m)">
          <span class="bar-label">{{ m.label }}</span><span class="bar-track" aria-hidden="true"><i :style="{width: ratio(m.value) == null ? '0%' : ratio(m.value) + '%'}"></i></span><strong>{{ number(m.value) }}</strong><span class="bar-ratio">{{ ratio(m.value) == null ? '—' : ratio(m.value).toFixed(1) + '%' }}</span>
        </button>
        <button :class="['metric','unstarted-summary']" :disabled="pending || unstarted.value == null" @click="$emit('metric', unstarted)"><span>未执行工作流 <small>上线定义中未观测到执行</small></span><strong>{{ number(unstarted.value) }}</strong><span>→</span></button>
      </div>
    </div>
    <p class="execution-note">定义数、业务应执行数与实例数口径不同；占比按已采集的执行实例计算。</p>
  </section>
</template>
<script setup>
import { computed } from 'vue'
const props = defineProps({ dashboard:{type:Object,required:true}, metrics:{type:Array,default:()=>[]}, pending:Boolean })
defineEmits(['metric'])
const number = n => n == null ? '未知' : Number(n).toLocaleString('zh-CN')
const stats = computed(() => props.dashboard.workflow_statistics || {})
const total = computed(() => stats.value.total)
const rows = computed(() => [{key:'total',label:'已执行实例',value:total.value,state:''}, ...['success','failed','running','waiting'].map(key=>props.metrics.find(m=>m.key===key)).filter(Boolean)])
const unstarted = computed(() => props.metrics.find(m=>m.key==='unstarted') || {key:'unstarted',value:null})
const ratio = n => n == null || !(total.value > 0) ? null : Math.max(0,Math.min(100,Number(n)/total.value*100))
</script>
<style scoped>
.execution-overview{background:#fff;border:1px solid #dce7f4;border-radius:8px;padding:16px;min-width:0;box-shadow:0 2px 5px #163e6510}header{display:flex;align-items:center;justify-content:space-between;gap:10px}h2{font-size:17px;margin:0;color:#173958}header>span{font-size:11px;color:#71869c}.execution-visual{display:grid;grid-template-columns:minmax(125px,.7fr) minmax(0,1.6fr);gap:20px;margin-top:16px;align-items:center}.execution-flow{display:flex;flex-direction:column;align-items:center}.flow-stage{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:2px;min-height:66px;width:100%;border:0;background:#e1eeff;color:#163c6a;font:inherit;clip-path:polygon(0 0,100% 0,90% 100%,10% 100%);padding:8px 16px}.flow-stage span{font-size:12px}.flow-stage strong{font-size:27px;line-height:1.2}.flow-stage small{font-size:10px;color:#64748b}.planned{background:#edf3fa}.executed{width:85%;background:linear-gradient(#c9e5ff,#e3f1ff);cursor:pointer}.succeeded{width:70%;background:linear-gradient(#daf8df,#bcefc9);color:#16743b;cursor:pointer}.flow-separator{font-size:10px;color:#7b8fa7;line-height:20px}.bar-column-labels{display:flex;justify-content:space-between;font-size:10px;color:#8395a7;margin-bottom:9px}.execution-bars .execution-bar{display:grid;grid-template-columns:74px minmax(0,1fr) 42px 48px;gap:9px;align-items:center;width:100%;border:0;border-radius:4px;background:transparent;padding:8px 0;margin:0;box-shadow:none;text-align:left;color:#263746;cursor:pointer;min-height:0}.bar-label{font-size:12px}.bar-track{height:19px;background:#f0f5fb;overflow:hidden;border-radius:0 3px 3px 0}.bar-track i{display:block;height:100%;background:linear-gradient(90deg,#1683ec,#00a7ff);min-width:0}.bar-failed .bar-track i{background:#ef5555}.bar-success .bar-track i{background:#30af70}.bar-waiting .bar-track i{background:#e9a22b}.execution-bar strong{font-size:15px;text-align:right;line-height:1}.bar-ratio{font-size:11px;color:#73859a;text-align:right}.execution-bars .unstarted-summary{display:flex;align-items:center;justify-content:space-between;gap:10px;margin:12px 0 0;border:0;border-top:1px solid #e5edf6;border-radius:0;padding:10px 0 0;background:none;box-shadow:none;min-height:0;width:100%;font-size:11px;color:#64748b;cursor:pointer;text-align:left}.unstarted-summary small{display:block;font-size:10px;font-weight:400;color:#93a1b3;margin-top:3px}.unstarted-summary strong{font-size:16px;margin-left:auto;line-height:1;color:#344d65}.execution-note{font-size:10px;color:#8192a6;line-height:1.6;margin:12px 0 0}button:disabled{cursor:default;opacity:.65}button:focus-visible{outline:2px solid #1683ec;outline-offset:3px}.execution-bar:hover:not(:disabled){background:#f5f9ff}@media(max-width:600px){.execution-visual{grid-template-columns:1fr;gap:14px}.execution-flow{flex-direction:row;gap:3px}.flow-stage,.executed,.succeeded{width:33%;clip-path:none;border-radius:4px;padding:8px 3px}.flow-separator{display:none}.execution-bars .execution-bar{gap:6px;grid-template-columns:74px minmax(0,1fr) 36px 43px}}
</style>
