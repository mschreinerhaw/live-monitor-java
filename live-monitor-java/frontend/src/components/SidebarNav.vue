<template>
  <aside class="ops-sidebar">
    <router-link :to="store.user?.ops_only ? '/ops/overview' : '/dashboard'" class="ops-brand">Live Monitor<small>监控与容器运维</small></router-link>
    <nav aria-label="平台导航">
      <p>部署与运行</p>
      <router-link v-for="item in primarySections" :key="item.key" :to="'/ops/' + item.key">{{ item.label }}</router-link>
      <details :open="advancedActive"><summary>高级运维</summary>
      <router-link v-for="item in advancedSections" :key="item.key" :to="'/ops/' + item.key">{{ item.label }}</router-link>
      </details>
      <details v-if="!store.user?.ops_only" :open="!route.path.startsWith('/ops/')"><summary>原有监控</summary>
      <router-link to="/dashboard">监控首页</router-link>
      <router-link to="/hosts">主机维护</router-link>
      <router-link to="/services/new">添加监控服务</router-link>
      <router-link to="/data-send">数据推送</router-link>
      <router-link to="/alerts/settings">监控告警设置</router-link>
      <router-link to="/alerts/audit">监控告警审计</router-link></details>
    </nav>
  </aside>
</template>
<script setup>
import { opsSections as sections } from '../ops/sections'
import { store } from '../store'
import { computed } from 'vue'
import { useRoute } from 'vue-router'
const route = useRoute()
const primarySections = sections.filter(item => item.primary)
const advancedSections = sections.filter(item => !item.primary)
const advancedActive = computed(() => advancedSections.some(item => route.path === '/ops/' + item.key))
</script>
<style scoped>
.ops-sidebar{width:210px;flex-shrink:0;background:#101e32;color:#d4dfed;padding:24px 14px;position:sticky;top:0;height:100vh;overflow:auto}.ops-brand{display:block;color:#fff;font-size:21px;font-weight:700;padding:0 10px 20px}.ops-brand small{display:block;font-size:12px;font-weight:400;color:#98acc5;margin-top:4px}.ops-sidebar nav{display:grid;gap:4px}.ops-sidebar nav p{font-size:11px;color:#8fa6c4;padding:16px 10px 6px}.ops-sidebar nav a{color:#c8d7e9;padding:9px 12px;border-radius:6px;font-size:13px}.ops-sidebar nav a.router-link-active{background:#1b4266;color:#fff}.ops-sidebar nav a:hover{background:#22344e}@media(max-width:900px){.ops-sidebar{width:160px;padding:16px 8px}}@media(max-width:600px){.ops-sidebar{position:static;height:auto;width:100%}.ops-sidebar nav{display:flex;overflow:auto;white-space:nowrap}.ops-sidebar nav p{display:none}.ops-brand{font-size:16px;padding-bottom:10px}}
</style>
<style scoped>
details{margin-top:12px}summary{padding:10px 12px;cursor:pointer;color:#9db6d2;font-size:13px}details a{display:block;margin-top:4px}
</style>
