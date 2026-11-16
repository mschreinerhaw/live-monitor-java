<template>
  <div class="overview">
    <div class="overview-heading"><div><small>监控总览</small><h1>全局监控</h1><p>掌握整体健康，优先处理异常，再查看集群与主机资源。</p></div><div><select v-model.number="interval" aria-label="自动刷新周期"><option :value="0">暂停刷新</option><option :value="30">每 30 秒</option><option :value="60">每 60 秒</option></select> <button class="btn btn-primary" :disabled="loading" @click="load">{{ loading ? '刷新中…' : '刷新快照' }}</button><p class="updated"><router-link class="scheduler-entry" to="/dolphinscheduler">调度监控 →</router-link> · 最近刷新 {{ updated || '尚未加载' }}</p></div></div>
    <p v-if="error" role="alert" class="overview-error">{{ error }}；下方保留的快照不代表当前状态。</p>
    <p v-for="(message, key) in data.errors" :key="key" role="alert" class="overview-error">{{ message }}</p>
    <div class="overview-stats"><article v-for="item in stats" :key="item.label"><span>{{ item.label }}</span><strong>{{ item.value }}</strong><small>{{ item.note }}</small></article></div>
    <section class="overview-panel attention-panel"><div class="panel-heading"><div><h2>需要关注 <span class="attention-count">{{ abnormal.length }}</span></h2><p class="scope-note">当前范围内探测异常的服务</p></div><router-link to="/services">查看全部服务 →</router-link></div><div class="attention-list"><router-link v-for="s in abnormal" :key="s.id" :to="'/services/' + s.id" class="exception-row"><span class="resource-status down">异常</span><strong>{{ s.service_name }}</strong><small>{{ s.last_checked_at || '暂无探测时间' }}</small></router-link><p v-if="!abnormal.length" class="attention-empty">当前范围暂无已知异常服务</p></div></section>
    <section class="overview-panel"><div class="panel-heading"><h2>集群健康</h2><input v-model="search" type="search" placeholder="搜索集群、服务或主机" aria-label="搜索资源" /><select v-model="selected" aria-label="集群范围"><option value="">全部集群</option><option v-for="c in clusters" :key="c.key" :value="c.key">{{ c.name }}</option></select></div>
      <p class="scope-note">点击集群筛选下方资源，再次点击可恢复全部范围。</p>
      <div class="cluster-grid"><button v-for="c in visibleClusters" :key="c.key" class="cluster-card" :class="{selected:selected === c.key}" @click="selected = selected === c.key ? '' : c.key"><div><strong>{{ c.name }}</strong><span :class="['resource-status', c.down ? 'down' : c.unknown || c.disabled === c.rows.length ? 'unknown' : 'up']">{{ c.down ? '存在异常' : c.disabled === c.rows.length ? '已停用' : c.unknown ? '状态待确认' : '正常' }}</span></div><p>{{ c.rows.length }} 服务实例 · {{ c.hosts.size }} 关联主机</p><small>正常 {{ c.up }} / 异常 {{ c.down }} / 未知 {{ c.unknown }} / 停用 {{ c.disabled }}</small></button></div><p v-if="!visibleClusters.length">暂无匹配集群</p>
    </section>
    <section class="overview-panel"><div class="panel-heading"><h2>主机资源热力图</h2><select v-model="metric" aria-label="热力图指标"><option value="cpu_usage_percent">CPU</option><option value="memory_used_percent">内存</option><option value="disk_used_percent">磁盘</option></select></div><p class="scope-note">颜色表示使用率：绿色 &lt;70%，橙色 70–85%，红色 ≥85%；灰色为停用、采集异常、过期或缺失。</p><div class="heatmap"><router-link v-for="h in visibleHosts" :key="h.id" :to="'/hosts/' + h.id" :class="['heat-cell', heatTone(h)]" :title="`${h.host_name} / ${h.ip}\n${h.metric_checked_at || '暂无采集时间'}\n${h.last_message || ''}`"><strong>{{ h.host_name || h.ip }}</strong><span>{{ hostValue(h) }}</span><small>{{ h.metric_checked_at || '暂无采集时间' }}</small></router-link></div><p v-if="!visibleHosts.length">暂无关联主机数据</p><router-link to="/hosts">查看主机详情与历史趋势 →</router-link></section>


  </div>
</template>

<script setup>
import { computed, ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute } from 'vue-router'
const route = useRoute(), data = ref({}), loading = ref(false), error = ref(''), updated = ref('')
const search = ref(String(route.query.q || '')), selected = ref(''), metric = ref('cpu_usage_percent'), interval = ref(30)
watch(() => route.query.q, value => { search.value = String(value || '') })
let timer, controller
const services = computed(() => data.value.services || [])
const hosts = computed(() => data.value.hosts || [])
const clusterKey = s => s.cluster_name ? 'configured:' + s.cluster_name : 'unassigned:'
const clusters = computed(() => {
  const groups = new Map()
  services.value.forEach(s => { const key = clusterKey(s); if (!groups.has(key)) groups.set(key, {key, name:s.cluster_name || '未分组资源', rows:[], hosts:new Set(),up:0,down:0,unknown:0,disabled:0}); const c = groups.get(key); c.rows.push(s); if(s.host_id != null)c.hosts.add(s.host_id); c[s.enabled === false ? 'disabled' : s.last_status === 'UP' ? 'up' : s.last_status === 'DOWN' ? 'down' : 'unknown']++ })
  return [...groups.values()]
})
const matches = text => String(text || '').toLowerCase().includes(search.value.toLowerCase())
const visibleClusters = computed(() => clusters.value.filter(c => (!selected.value || selected.value === c.key) && (matches(c.name) || c.rows.some(s => matches(s.service_name)))))
const scoped = computed(() => services.value.filter(s => (!selected.value || clusterKey(s) === selected.value)))
const visibleHosts = computed(() => {const ids = new Set(scoped.value.map(s => s.host_id)); return hosts.value.filter(h => (!selected.value || ids.has(h.id)) && matches(`${h.host_name} ${h.ip}`))})
const abnormal = computed(() => scoped.value.filter(s => s.enabled !== false && s.last_status === 'DOWN' && matches(s.service_name)))

function hostState(h) {
  if(h.enabled === false || h.monitor_enabled === false) return '已停用'
  if(h.last_status !== 'UP') return '采集异常 / 未知'
  const time = Number(h.last_checked_at_epoch_ms) || Date.parse(String(h.metric_checked_at || '').replace(' ', 'T'))
  if(!Number.isFinite(time)) return '暂无数据'
  if(Date.now() - time > Math.max(180, (h.check_interval || 60) * 3) * 1000) return '采集过期'
  return ''
}
const hostValue = h => hostState(h) || (h[metric.value] == null ? '暂无数据' : Number(h[metric.value]).toFixed(1) + '%')
const heatTone = h => hostState(h) || h[metric.value] == null ? 'unknown' : h[metric.value] >= 85 ? 'down' : h[metric.value] >= 70 ? 'warn' : 'up'
const stats = computed(() => {const summary = data.value.summary; return [
  {label:'已配置集群',value:data.value.services ? clusters.value.filter(c=>c.key !== 'unassigned:').length : '—',note:'按现有资源配置分组'},
  {label:'启用服务实例',value:summary?.instances ?? '—',note:'当前已启用的监控实例'},
  {label:'正常 / 异常服务',value:summary ? `${summary.up} / ${summary.down}` : '—',note:`未知 ${summary?.unknown ?? '—'}`},
  {label:'主机 / 可用采集',value:data.value.hosts ? `${hosts.value.length} / ${hosts.value.filter(h=>!hostState(h)).length}` : '—',note:'可用采集排除过期和异常'}]})
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
.attention-panel{border-left:3px solid #dc7b42}.attention-count{display:inline-block;margin-left:6px;padding:2px 8px;border-radius:12px;background:#fff0e4;color:#a84e22;font-size:13px}.attention-list{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:0 24px}.attention-empty{color:#527568;font-size:13px}.attention-panel .scope-note{margin:4px 0}.scheduler-entry{color:#16738c;text-decoration:none}.overview{color:#263746;padding:24px}.overview-heading,.panel-heading{display:flex;justify-content:space-between;align-items:center;gap:16px;flex-wrap:wrap}.overview-heading{margin-bottom:20px}.overview h1{font-size:25px;margin:6px 0}.overview h2{font-size:16px;margin:0}.overview-heading p,.scope-note,.updated{font-size:12px;color:#687b89;line-height:1.7}.overview-heading small{letter-spacing:2px;color:#387c92}.updated{text-align:right;margin-bottom:0}.overview select,.overview input{padding:8px;border:1px solid #d2dde3;border-radius:4px;background:white;max-width:100%}.overview-stats{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin-bottom:18px}.overview-stats article,.overview-panel{background:#fff;border:1px solid #dce4e9;border-radius:5px}.overview-stats article{padding:16px;border-top:3px solid #27879a;display:flex;flex-direction:column;gap:8px}.overview-stats span{font-size:12px;color:#617482}.overview-stats strong{font-size:27px}.overview-stats small{font-size:11px;color:#81909c}.overview-panel{padding:18px;margin-bottom:18px}.overview-panel a{color:#16738c;text-decoration:none;font-size:13px}.cluster-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(240px,1fr));gap:12px}.cluster-card{text-align:left;border:1px solid #dce4e9;border-left:3px solid #26899a;border-radius:4px;background:#fafcfd;padding:15px;cursor:pointer;color:inherit}.cluster-card.selected{outline:2px solid #258c9e}.cluster-card>div{display:flex;justify-content:space-between;gap:12px}.cluster-card p,.cluster-card small{font-size:12px}.resource-status{font-size:11px;padding:3px 7px;border-radius:3px}.up{background:#e7f4ed;color:#206b46}.down{background:#fbe9e8;color:#aa3838}.warn{background:#fff1d9;color:#96600e}.unknown{background:#edf0f3;color:#6a7883}.overview-columns{display:grid;grid-template-columns:1.2fr 1fr;gap:18px}.heatmap{display:grid;grid-template-columns:repeat(auto-fill,minmax(135px,1fr));gap:8px;margin-bottom:15px}.heat-cell{display:flex;flex-direction:column;padding:12px;gap:8px;border-radius:4px}.overview .heat-cell{color:inherit}.heat-cell strong{overflow-wrap:anywhere}.heat-cell span{font-size:19px}.heat-cell small{font-size:10px}.exception-row{display:flex;align-items:center;flex-wrap:wrap;gap:12px;padding:13px 0;border-bottom:1px solid #edf1f4}.exception-row small{margin-left:auto;color:#7d8b96}.overview-error{background:#fff0da;color:#975c14;padding:12px}.overview hr{border:0;border-top:1px solid #e4ebef;margin:22px 0}@media(max-width:1100px){.overview-stats{grid-template-columns:repeat(3,1fr)}.overview-columns{grid-template-columns:1fr}}@media(max-width:600px){.overview-stats{grid-template-columns:repeat(2,1fr)}.overview-heading{align-items:flex-start}.updated{text-align:left}}
</style>
