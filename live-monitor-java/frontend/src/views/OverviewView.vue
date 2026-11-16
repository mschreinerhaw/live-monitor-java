<template>
  <div class="overview">
    <div class="overview-heading"><div><small>监控总览</small><h1>全局监控</h1><p>通过状态分布、资源对比与主机热力图掌握全局运行情况。</p></div><div><select v-model.number="interval" aria-label="自动刷新周期"><option :value="0">暂停刷新</option><option :value="30">每 30 秒</option><option :value="60">每 60 秒</option></select> <button class="btn btn-primary" :disabled="loading" @click="load">{{ loading ? '刷新中…' : '刷新快照' }}</button><p class="updated"><router-link class="scheduler-entry" to="/dolphinscheduler">调度监控 →</router-link> · 最近刷新 {{ updated || '尚未加载' }}</p></div></div>
    <p v-if="error" role="alert" class="overview-error">{{ error }}；下方保留的快照不代表当前状态。</p>
    <p v-for="(message, key) in data.errors" :key="key" role="alert" class="overview-error">{{ message }}</p>
    <details class="overview-stat-details"><summary>资源与告警统计 <span class="stat-summary-alert">异常服务 {{ data.summary?.down ?? '—' }}</span><span class="stat-summary-alert">活动告警 {{ data.active_service_alerts ?? '—' }}</span></summary><div class="overview-stats"><article v-for="item in stats" :key="item.label"><span>{{ item.label }}</span><strong v-if="item.label==='正常 / 异常服务'">{{ data.summary?.up ?? '—' }} / <em class="stat-error-number">{{ data.summary?.down ?? '—' }}</em></strong><strong v-else :class="{'stat-error-number':item.label==='活动服务告警'}">{{ item.value }}</strong><small>{{ item.note }}</small></article></div></details>
    <div class="overview-filters"><input v-model="search" type="search" placeholder="搜索集群、服务或主机" aria-label="搜索资源" /><select v-model="selected" aria-label="集群范围"><option value="">全部集群</option><option v-for="c in clusters" :key="c.key" :value="c.key">{{ c.name }}</option></select><span>图表与异常列表按当前范围展示</span></div>
    <section class="overview-panel attention-panel"><div class="panel-heading"><div><h2>待处理异常 <span class="attention-count">{{ abnormal.length }}</span></h2><p class="scope-note">当前范围内最近探测失败的服务，点击查看原因与关联资源</p></div><router-link to="/services">服务明细 →</router-link></div><div class="attention-list"><router-link v-for="s in abnormal.slice(0,3)" :key="s.id" :to="'/services/' + s.id" class="exception-row"><span class="resource-status down">异常</span><strong>{{ s.service_name }}</strong><span class="issue-reason" :title="s.last_message">{{ s.last_message || '最近探测失败，查看详情定位原因' }}</span><small>{{ s.last_checked_at || '暂无探测时间' }}</small></router-link><p v-if="abnormal.length>3" class="scope-note">另有 {{ abnormal.length-3 }} 个异常服务，请进入服务明细查看。</p><p v-if="!abnormal.length" class="attention-empty">当前范围暂无已知异常服务</p></div></section>
    <section class="overview-panel heatmap-panel"><div class="panel-heading"><h2 aria-label="主机资源热力图">主机资源热力图 <small class="heat-count">展示 {{ heatHosts.length }} / {{ visibleHosts.length }} 台</small></h2><div class="heat-actions"><select v-model.number="heatLimit" aria-label="热力图主机数量"><option :value="10">Top 10 主机</option><option :value="20">Top 20 主机</option><option :value="0">全部主机</option></select><div class="heat-tabs" role="group" aria-label="热力图指标"><button v-for="m in heatMetrics" :key="m.key" :aria-pressed="metric === m.key" :class="{active:metric===m.key}" @click="metric=m.key">{{ m.label }}</button></div></div></div><p class="scope-note">按当前指标使用率降序展示，默认 Top 10。颜色表示使用率：绿色 &lt;70%，橙色 70–85%，红色 ≥85%；灰色为停用、采集异常、过期或缺失。</p><div class="heatmap"><router-link v-for="h in heatHosts" :key="h.id" :to="'/hosts/' + h.id" :class="['heat-cell', heatTone(h)]" :title="`${h.host_name} / ${h.ip}\n${h.metric_checked_at || '暂无采集时间'}\n${h.last_message || ''}`"><strong>{{ h.host_name || h.ip }}</strong><span>{{ hostValue(h) }}</span><span class="heat-blocks" aria-hidden="true"><i v-for="n in 10" :key="n" :class="{filled:!hostState(h) && validMetric(h) && n <= Math.ceil(h[metric]/10)}"></i></span><small class="heat-updated">{{ h.metric_checked_at || '暂无采集时间' }}</small></router-link></div><p v-if="!visibleHosts.length">暂无关联主机数据</p><router-link to="/hosts">查看主机详情与历史趋势 →</router-link></section>
    <OverviewCharts :services="chartServices" :hosts="chartHosts" :services-known="Array.isArray(data.services)" :hosts-known="Array.isArray(data.hosts)" />
  </div>
</template>

<script setup>
import { computed, ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute } from 'vue-router'
import OverviewCharts from '../components/OverviewCharts.vue'
const route = useRoute(), data = ref({}), loading = ref(false), error = ref(''), updated = ref('')
const heatLimit=ref(10)
const search = ref(String(route.query.q || '')), selected = ref(''), metric = ref('cpu_usage_percent'), interval = ref(30)
watch(() => route.query.q, value => { search.value = String(value || '') })
const heatMetrics=[{key:'cpu_usage_percent',label:'CPU'},{key:'memory_used_percent',label:'内存'},{key:'disk_used_percent',label:'磁盘'}]
let timer, controller
const services = computed(() => data.value.services || [])
const hosts = computed(() => data.value.hosts || [])
const clusterKey = s => s.cluster_name ? 'configured:' + s.cluster_name : 'unassigned:'
const clusters = computed(() => {
  const groups = new Map()
  services.value.forEach(s => { const key = clusterKey(s); if (!groups.has(key)) groups.set(key, {key, name:s.cluster_name || '未分组资源', rows:[], hosts:new Set(),up:0,down:0,unknown:0,disabled:0}); const c = groups.get(key); c.rows.push(s); if(s.host_id != null)c.hosts.add(s.host_id); c[s.enabled === false ? 'disabled' : s.last_status === 'UP' ? 'up' : s.last_status === 'DOWN' ? 'down' : 'unknown']++ })
  return [...groups.values()].sort((a,b)=>b.down-a.down || b.unknown-a.unknown)
})
const matches = text => String(text || '').toLowerCase().includes(search.value.toLowerCase())

const scoped = computed(() => services.value.filter(s => (!selected.value || clusterKey(s) === selected.value)))
const chartServices = computed(()=>scoped.value.filter(s=>matches(`${s.service_name || ''} ${s.cluster_name || ''}`)))
const visibleHosts = computed(() => {const ids=new Set(scoped.value.map(s=>s.host_id)),matchedIds=new Set(chartServices.value.map(s=>s.host_id));return hosts.value.filter(h=>(!selected.value || ids.has(h.id)) && (matches(`${h.host_name || ''} ${h.ip || ''}`) || !!search.value && matchedIds.has(h.id)))})
const chartHosts = computed(()=>visibleHosts.value.map(h=>{const quality=hostState(h);const value=key=>quality || h[key]==null || !Number.isFinite(Number(h[key])) || Number(h[key])<0 || Number(h[key])>100 ? null : Number(h[key]);return {id:h.id,name:h.host_name || h.ip || String(h.id),quality,checkedAt:h.metric_checked_at,cpu:value('cpu_usage_percent'),memory:value('memory_used_percent'),disk:value('disk_used_percent')}}))
const abnormal = computed(() => chartServices.value.filter(s=>s.enabled!==false && s.last_status==='DOWN'))

function hostState(h) {
  if(h.enabled === false || h.monitor_enabled === false) return '已停用'
  if(h.last_status !== 'UP') return '采集异常 / 未知'
  const time = Number(h.last_checked_at_epoch_ms) || Date.parse(String(h.metric_checked_at || '').replace(' ', 'T'))
  if(!Number.isFinite(time)) return '暂无数据'
  if(Date.now() - time > Math.max(180, (h.check_interval || 60) * 3) * 1000) return '采集过期'
  return ''
}
const validMetric = h => h[metric.value] != null && Number.isFinite(Number(h[metric.value])) && Number(h[metric.value]) >= 0 && Number(h[metric.value]) <= 100
const heatHosts=computed(()=>{
 const rows=visibleHosts.value.slice().sort((a,b)=>{
  const av=!hostState(a)&&validMetric(a)?Number(a[metric.value]):-1,bv=!hostState(b)&&validMetric(b)?Number(b[metric.value]):-1
  return bv-av || String(a.host_name||a.ip||a.id).localeCompare(String(b.host_name||b.ip||b.id),'zh-CN')
 })
 return heatLimit.value ? rows.slice(0,heatLimit.value) : rows
})
const hostValue = h => hostState(h) || (!validMetric(h) ? '暂无数据' : Number(h[metric.value]).toFixed(1) + '%')
const heatTone = h => hostState(h) || !validMetric(h) ? 'unknown' : h[metric.value] >= 85 ? 'down' : h[metric.value] >= 70 ? 'warn' : 'up'
const stats = computed(() => {const summary = data.value.summary; return [
  {label:'已配置集群',value:data.value.services ? clusters.value.filter(c=>c.key !== 'unassigned:').length : '—',note:'按现有资源配置分组'},
  {label:'启用服务实例',value:summary?.instances ?? '—',note:'当前已启用的监控实例'},
  {label:'正常 / 异常服务',value:summary ? `${summary.up} / ${summary.down}` : '—',note:`未知 ${summary?.unknown ?? '—'}`},
  {label:'主机 / 可用采集',value:data.value.hosts ? `${hosts.value.length} / ${hosts.value.filter(h=>!hostState(h)).length}` : '—',note:'可用采集排除过期和异常'},
  {label:'活动服务告警',value:data.value.active_service_alerts ?? '—',note:'启用服务中尚未恢复的告警状态'}]})
async function load() {
  if(loading.value) return
  loading.value=true; controller = new AbortController(); const timeout=setTimeout(()=>controller.abort(),20000)
  try {const response=await fetch('/api/overview/summary',{credentials:'same-origin',signal:controller.signal}); if(!response.ok)throw new Error(`请求失败 (${response.status})`); data.value=await response.json(); error.value=''; updated.value=new Date().toLocaleString('zh-CN',{hour12:false})} catch(e){error.value=e.name==='AbortError'?'快照请求已超时':e.message} finally {clearTimeout(timeout);loading.value=false}
}
function schedule(){clearInterval(timer); if(interval.value)timer=setInterval(load,interval.value*1000)}
watch(interval,schedule)
onMounted(()=>{load();schedule()})
onBeforeUnmount(()=>{clearInterval(timer);controller?.abort()})
</script>

<style scoped>
.attention-panel{border-left:3px solid #dc7b42}.attention-count{display:inline-block;margin-left:6px;padding:2px 8px;border-radius:12px;background:#fff0e4;color:#a84e22;font-size:13px}.attention-list{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:0 24px}.attention-empty{color:#527568;font-size:13px}.attention-panel .scope-note{margin:4px 0}.scheduler-entry{color:#16738c;text-decoration:none}.overview{color:#263746;padding:24px}.overview-heading,.panel-heading{display:flex;justify-content:space-between;align-items:center;gap:16px;flex-wrap:wrap}.overview-heading{margin-bottom:20px}.overview h1{font-size:25px;margin:6px 0}.overview h2{font-size:16px;margin:0}.overview-heading p,.scope-note,.updated{font-size:12px;color:#687b89;line-height:1.7}.overview-heading small{letter-spacing:2px;color:#387c92}.updated{text-align:right;margin-bottom:0}.overview select,.overview input{padding:8px;border:1px solid #d2dde3;border-radius:4px;background:white;max-width:100%}.overview-stats{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:12px;margin-bottom:18px}.overview-stats article,.overview-panel{background:#fff;border:1px solid #dce4e9;border-radius:5px}.overview-stats article{padding:16px;border-top:3px solid #27879a;display:flex;flex-direction:column;gap:8px}.overview-stats span{font-size:12px;color:#617482}.overview-stats strong{font-size:27px}.overview-stats small{font-size:11px;color:#81909c}.overview-panel{padding:18px;margin-bottom:18px}.overview-panel a{color:#16738c;text-decoration:none;font-size:13px}.cluster-card p,.resource-status{font-size:11px;padding:3px 7px;border-radius:3px}.up{background:#e7f4ed;color:#206b46}.down{background:#fbe9e8;color:#aa3838}.warn{background:#fff1d9;color:#96600e}.unknown{background:#edf0f3;color:#6a7883}.overview-columns{display:grid;grid-template-columns:1.2fr 1fr;gap:18px}.heatmap{display:grid;grid-template-columns:repeat(auto-fill,minmax(135px,1fr));gap:8px;margin-bottom:15px}.heat-cell{display:flex;flex-direction:column;padding:12px;gap:8px;border-radius:4px}.overview .heat-cell{color:inherit}.heat-cell strong{overflow-wrap:anywhere}.heat-cell span{font-size:19px}.heat-cell small{font-size:10px}.exception-row{display:flex;align-items:center;flex-wrap:wrap;gap:12px;padding:13px 0;border-bottom:1px solid #edf1f4}.exception-row small{margin-left:auto;color:#7d8b96}.overview-error{background:#fff0da;color:#975c14;padding:12px}.overview hr{border:0;border-top:1px solid #e4ebef;margin:22px 0}@media(max-width:1100px){.overview-stats{grid-template-columns:repeat(3,1fr)}.overview-columns{grid-template-columns:1fr}}@media(max-width:600px){.overview-stats{grid-template-columns:repeat(2,1fr)}.overview-heading{align-items:flex-start}.updated{text-align:left}}
</style>

<style scoped>
.overview{padding:20px;background:#f6f9fd}.overview-heading{margin-bottom:14px}.overview h1{font-size:24px}.overview-stats{gap:10px;margin-bottom:12px}.overview-stats article{padding:12px 15px;border-top:0;border-color:#dce7f4;background:linear-gradient(120deg,#f1f7ff,#fff)}.overview-stats strong{font-size:25px}.overview-panel{padding:14px;margin-bottom:12px;border-color:#dce7f4}.panel-heading h2{color:#173958;font-size:15px}.scope-note{margin:6px 0 10px}.attention-list{display:block}.exception-row{display:grid;grid-template-columns:50px minmax(130px,1fr) minmax(120px,2fr) 150px;gap:12px;padding:9px 0;font-size:12px}.exception-row .issue-reason{color:#617482;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.exception-row small{margin:0;text-align:right;font-size:11px}.heatmap{grid-template-columns:repeat(auto-fill,minmax(110px,1fr));gap:7px;margin-bottom:8px}.heat-cell{padding:10px;gap:6px;border:1px solid #dfe9e6}.heat-cell strong{font-size:11px}.heat-cell span{font-size:19px}.heat-cell small{font-size:9px}.heat-tabs{display:flex;gap:2px;background:#edf3fa;border-radius:5px;padding:3px}.heat-tabs button{padding:4px 13px;border:0;background:none;color:#50657a;font-size:12px;cursor:pointer;border-radius:4px}.heat-tabs .active{color:white;background:#1689e7}.heat-blocks{display:grid;grid-template-columns:repeat(10,1fr);gap:2px}.heat-blocks i{height:9px;background:currentColor;opacity:.08}.heat-blocks i.filled{opacity:.3}@media(max-width:900px){.overview-stats{grid-template-columns:repeat(3,minmax(0,1fr))}.exception-row{grid-template-columns:50px 1fr}.exception-row .issue-reason{grid-column:1/-1}.exception-row small{text-align:left}}@media(max-width:600px){.overview{padding:16px}.overview-stats{grid-template-columns:repeat(2,minmax(0,1fr))}}
</style>

<style scoped>
.overview-filters{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:12px}.overview-filters input{width:260px}.overview-filters select{width:180px}.overview-filters span{font-size:11px;color:#708295}.attention-panel{padding-top:10px;padding-bottom:10px}.attention-panel .scope-note{display:none}.overview-stats article{padding:9px 14px}.overview-stats strong{font-size:24px}.heatmap-details{margin-top:12px}.heatmap-details>summary{font-size:14px;font-weight:600;color:#173958;cursor:pointer;padding:2px 0}.heatmap-details[open]>summary{margin-bottom:12px}@media(max-width:600px){.overview-filters input{width:100%}.overview-filters select{flex:1}}
</style>

<style scoped>
.heatmap-panel .panel-heading{margin-bottom:8px}.heatmap{grid-template-columns:repeat(6,minmax(0,1fr));gap:12px;margin:14px 0}.heat-cell{min-width:0;min-height:145px;padding:14px;gap:9px;border-radius:7px;box-sizing:border-box;transition:box-shadow .15s,transform .15s}.heat-cell:hover{transform:translateY(-2px);box-shadow:0 4px 12px #24466914}.heat-cell strong{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;min-height:34px;font-size:12px;line-height:17px}.heat-cell>span:not(.heat-blocks){font-size:26px;font-variant-numeric:tabular-nums;line-height:30px}.heat-cell.unknown>span:not(.heat-blocks){font-size:14px}.heat-cell .heat-blocks{margin-top:auto}.heat-cell .heat-updated{font-size:10px;line-height:16px;white-space:nowrap;color:inherit;opacity:.8}.heat-blocks i{height:8px}.heatmap-panel>a{display:inline-block;margin-top:2px}@media(max-width:1250px){.heatmap{grid-template-columns:repeat(4,minmax(0,1fr))}}@media(max-width:950px){.heatmap{grid-template-columns:repeat(3,minmax(0,1fr))}}@media(max-width:600px){.heatmap{grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.heat-cell{padding:11px;min-height:140px}.heat-cell .heat-updated{font-size:9px}}@media(max-width:350px){.heatmap{grid-template-columns:1fr}}
</style>

<style scoped>
.heat-actions{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.heat-actions select{padding:6px 8px;font-size:11px;background:#f8fbff;border-color:#dce7f4}.heat-count{font-size:11px;font-weight:400;color:#7a8fa4;margin-left:8px}.heatmap{grid-template-columns:repeat(5,minmax(0,1fr))}@media(max-width:1250px){.heatmap{grid-template-columns:repeat(4,minmax(0,1fr))}}@media(max-width:950px){.heatmap{grid-template-columns:repeat(3,minmax(0,1fr))}}@media(max-width:600px){.heatmap{grid-template-columns:repeat(2,minmax(0,1fr))}.heat-actions{gap:8px}.heat-count{display:block;margin:5px 0 0}}@media(max-width:350px){.heatmap{grid-template-columns:1fr}}
</style>

<style scoped>
.overview-stat-details{margin-bottom:12px;border:1px solid #dce7f4;border-radius:6px;background:#fff;padding:10px 14px}.overview-stat-details>summary{cursor:pointer;font-size:12px;color:#58718b}.stat-summary-alert{margin-left:16px;color:#dc2626;font-size:12px}.overview-stat-details .overview-stats{margin-top:12px;margin-bottom:0}.overview .stat-error-number{color:#dc2626;font-style:normal}.overview .attention-count{color:#dc2626;background:#fff0ef}@media(max-width:600px){.stat-summary-alert{display:inline-block;margin:6px 8px 0 0}}
</style>
