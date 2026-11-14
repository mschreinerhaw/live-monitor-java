<template>
  <div v-if="!isAuthPage" class="app-layout">
    <a class="skip-link" href="#main-content" @click.prevent="focusContent">跳到主要内容</a>
    <button v-if="store.navigationOpen" class="navigation-backdrop" aria-label="关闭导航菜单" @click="store.navigationOpen = false"></button>
    <SidebarNav />
    <div class="main-content-wrapper">
      <TopHeader />
      <div v-if="store.navigationLoading" class="route-progress" role="status"><span class="sr-only">正在加载页面…</span></div>
      <main id="main-content" class="page-body" tabindex="-1" :aria-busy="store.navigationLoading" :inert="store.navigationOpen || undefined">
        <router-view />
      </main>
    </div>
    <ToastNotice />
  </div>
  <div v-else class="auth-layout">
    <router-view />
    <ToastNotice />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import SidebarNav from './components/SidebarNav.vue'
import TopHeader from './components/TopHeader.vue'
import ToastNotice from './components/ToastNotice.vue'
import { store } from './store'

const route = useRoute()
const isAuthPage = computed(() => route.path === '/login')
function focusContent() { const main = document.getElementById('main-content'); main?.focus(); main?.scrollIntoView({ block: 'start' }) }
</script>

<style>
@import './assets/styles/main.css';

.app-layout {
  display: flex;
  min-height: 100vh;
  background-color: var(--bg-color);
}

.main-content-wrapper {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow-x: hidden;
}

.page-body {
  flex: 1;
  padding: 0;
}

.auth-layout {
  min-height: 100vh;
  background-color: var(--bg-color);
}
</style>
