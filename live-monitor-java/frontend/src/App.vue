<template>
  <div class="app-container">
    <HeaderNav v-if="showHeader" />
    <router-view />
    <ToastNotice />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import HeaderNav from './components/HeaderNav.vue'
import ToastNotice from './components/ToastNotice.vue'

const route = useRoute()
const showHeader = computed(() => route.path !== '/login')
</script>

<style>
@import './assets/styles/main.css';
</style>
