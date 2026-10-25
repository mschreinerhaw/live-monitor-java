<template>
  <div v-if="!isAuthPage" class="app-layout">
    <SidebarNav />
    <div class="main-content-wrapper">
      <TopHeader v-if="!route.path.startsWith('/ops/')" />
      <main class="page-body">
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

const route = useRoute()
const isAuthPage = computed(() => route.path === '/login')
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
@media(max-width:600px){.app-layout{flex-direction:column}}
</style>
