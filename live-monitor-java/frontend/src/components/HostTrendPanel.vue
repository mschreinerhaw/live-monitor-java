<template>
  <section class="host-trend-panel">
    <header><h2>主机趋势 <small>最近 24 小时</small></h2><select v-model="selected" aria-label="趋势主机"><option value="">选择主机</option><option v-for="h in hosts" :key="h.id" :value="String(h.id)">{{ h.host_name || h.ip }}</option></select><button :disabled="busy || !selected" @click="load">刷新趋势</button></header>
    <p v-if="error" role="status">{{ error }}</p><p v-else-if="busy">正在读取历史指标…</p><p v-else-if="!rows.length">{{ selected ? '暂无历史指标' : '选择主机查看历史指标' }}</p>
    <div v-show="rows.length && !busy && !error" ref="element" class="trend-chart" role="img" aria-label="所选主机 CPU、内存、磁盘历史使用率趋势"></div>
    <small v-if="rows.length && !busy && !error" class="range">{{ rows[0].checked_at }} — {{ rows[rows.length-1].checked_at }} · {{ rows.length }} 个实际采集点</small>
  </section>
</template>
<script setup>
import { ref, watch, nextTick, onBeforeUnmount } from 'vue'
const props=defineProps({hosts:{type:Array,default:()=>[]}})
const selected=ref(''),rows=ref([]),busy=ref(false),error=ref(''),element=ref(null)
let controller,chart,observer,version=0
watch(()=>props.hosts.map(h=>String(h.id)).join(','),()=>{if(!props.hosts.some(h=>String(h.id)===selected.value))selected.value=props.hosts.length?String(props.hosts[0].id):''},{immediate:true})
watch(selected,load,{immediate:true})
async function load(){const id=selected.value,request=++version;controller?.abort();rows.value=[];error.value='';busy.value=false;if(!id)return;const abort=new AbortController();controller=abort;busy.value=true;const timer=setTimeout(()=>abort.abort(),15000)
 try{const response=await fetch(`/api/hosts/${encodeURIComponent(id)}/metrics/history?days=1&limit=10000`,{credentials:'same-origin',signal:abort.signal});if(!response.ok)throw Error('历史指标暂不可用');const data=await response.json();if(request!==version)return;rows.value=(Array.isArray(data)?data:[]).filter(r=>r.checked_at).sort((a,b)=>String(a.checked_at).localeCompare(String(b.checked_at)));busy.value=false;if(!rows.value.length)return;await nextTick();const echarts=await import('echarts');if(request!==version)return;if(!chart){chart=echarts.init(element.value);observer=new ResizeObserver(()=>chart?.resize());observer.observe(element.value)}chart.setOption({tooltip:{trigger:'axis'},legend:{data:['CPU','内存','磁盘'],right:10,top:0},grid:{top:32,left:40,right:15,bottom:28},xAxis:{type:'category',data:rows.value.map(r=>r.checked_at),axisLabel:{formatter:v=>String(v).slice(11,16),fontSize:10}},yAxis:{type:'value',min:0,max:100,axisLabel:{formatter:'{value}%',fontSize:10}},series:[['CPU','cpu_usage_percent','#1689e7'],['内存','memory_used_percent','#26aa7b'],['磁盘','disk_used_percent','#e4a03d']].map(([name,key,color])=>({name,type:'line',showSymbol:false,connectNulls:false,lineStyle:{width:1.5},color,data:rows.value.map(r=>r[key] == null?null:Number(r[key]))}))},true);chart.resize()}
 catch(e){if(request===version)error.value=e.name==='AbortError'?'历史指标请求已超时，请重试':e.message}finally{clearTimeout(timer);if(request===version)busy.value=false}}
onBeforeUnmount(()=>{version++;controller?.abort();observer?.disconnect();chart?.dispose()})
</script>
<style scoped>
.host-trend-panel{background:white;border:1px solid #dce7f4;border-radius:5px;padding:14px}.host-trend-panel header{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.host-trend-panel h2{font-size:15px;color:#173958;margin:0 auto 0 0}.host-trend-panel h2 small{font-size:11px;color:#6f8295;margin-left:8px}.host-trend-panel select,.host-trend-panel button{border:1px solid #dce7f4;padding:5px 8px;background:#fff;border-radius:4px;font-size:12px;max-width:100%}.host-trend-panel button{color:#16738c;cursor:pointer}.host-trend-panel p,.range{font-size:11px;color:#708295}.trend-chart{height:180px;margin-top:10px}.range{display:block;text-align:right}@media(max-width:600px){.host-trend-panel header h2{width:100%}.host-trend-panel select{flex:1;min-width:0}}
</style>
