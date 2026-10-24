<template>
  <aside class="ops-sidebar">
    <router-link to="/dashboard" class="ops-brand">Live Monitor<small>监控与容器运维</small></router-link>
    <nav aria-label="平台导航">
      <p>原有监控</p>
      <router-link to="/dashboard">监控首页</router-link>
      <router-link to="/hosts">主机维护</router-link>
      <router-link to="/services/new">添加监控服务</router-link>
      <router-link to="/data-send">数据推送</router-link>
      <router-link to="/alerts/settings">监控告警设置</router-link>
      <router-link to="/alerts/audit">监控告警审计</router-link>
      <p>容器运维平台</p>
      <router-link v-for="item in sections" :key="item.key" :to="'/ops/' + item.key">{{ item.label }}</router-link>
    </nav>
  </aside>
</template>
<script setup>
import { opsSections as sections } from '../ops/sections'
</script>
<style scoped>
.ops-sidebar{width:210px;flex-shrink:0;background:#101e32;color:#d4dfed;padding:24px 14px;position:sticky;top:0;height:100vh;overflow:auto}.ops-brand{display:block;color:#fff;font-size:21px;font-weight:700;padding:0 10px 20px}.ops-brand small{display:block;font-size:12px;font-weight:400;color:#98acc5;margin-top:4px}.ops-sidebar nav{display:grid;gap:4px}.ops-sidebar nav p{font-size:11px;color:#8fa6c4;padding:16px 10px 6px}.ops-sidebar nav a{color:#c8d7e9;padding:9px 12px;border-radius:6px;font-size:13px}.ops-sidebar nav a.router-link-active{background:#1b4266;color:#fff}.ops-sidebar nav a:hover{background:#22344e}@media(max-width:900px){.ops-sidebar{width:160px;padding:16px 8px}}@media(max-width:600px){.ops-sidebar{position:static;height:auto;width:100%}.ops-sidebar nav{display:flex;overflow:auto;white-space:nowrap}.ops-sidebar nav p{display:none}.ops-brand{font-size:16px;padding-bottom:10px}}
</style>
